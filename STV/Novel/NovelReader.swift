import SwiftUI
import UIKit
import AVFoundation

// MARK: - Look (colours sampled from WeRead) and TextKit layout

enum NovelLayout {
    struct Palette {
        var bg: Color, panel: Color, track: Color, thumb: Color
        var text: UIColor, secondary: Color
        var ink: Color { Color(text) }
    }
    static let accent = Color(red: 0.19, green: 0.59, blue: 0.08)   // #309714

    @MainActor static func palette(_ t: NovelPrefs.Theme) -> Palette {
        switch t {
        case .white:
            Palette(bg: .white, panel: Color(white: 0.965), track: Color(white: 0.90), thumb: .white,
                text: UIColor(white: 0.12, alpha: 1), secondary: Color(white: 0.56))
        case .cream:
            Palette(bg: Color(hex: 0xF7F0DE), panel: Color(hex: 0xF1E8D0), track: Color(hex: 0xE6DABE), thumb: Color(hex: 0xFBF6EA),
                text: UIColor(red: 0.22, green: 0.18, blue: 0.13, alpha: 1), secondary: Color(hex: 0x9A8B70))
        case .green:
            Palette(bg: Color(hex: 0xBFEDC4), panel: Color(hex: 0xB8EDBD), track: Color(hex: 0xA9D9AF), thumb: Color(hex: 0xCCEFCF),
                text: UIColor(red: 0.12, green: 0.16, blue: 0.12, alpha: 1), secondary: Color(hex: 0x6E8E72))
        case .night:
            Palette(bg: Color(hex: 0x111111), panel: Color(hex: 0x1C1C1C), track: Color(hex: 0x2B2B2B), thumb: Color(hex: 0x4A4A4A),
                text: UIColor(white: 0.58, alpha: 1), secondary: Color(white: 0.40))
        }
    }

    static func font(_ size: CGFloat, serif: Bool, bold: Bool = false) -> UIFont {
        if serif, let f = UIFont(name: bold ? "STSongti-SC-Bold" : "STSongti-SC-Regular", size: size) { return f }
        return .systemFont(ofSize: size, weight: bold ? .semibold : .regular)
    }

    /// Horizontal page margin for the 边距 slider (0…1).
    static func margin(_ m: Double) -> CGFloat { 12 + 28 * m }

    /// Chapter text → styled string. Characters are not changed, so ranges map 1:1 to chapter offsets.
    /// The first paragraph is the chapter heading when it matches `title`.
    static func attributed(_ text: String, title: String, size: CGFloat, lineSpacing: Double, indent: Bool = true,
        serif: Bool, color: UIColor) -> NSAttributedString {
        attributed(text, title: title, size: size, lineSpacing: lineSpacing, indent: indent,
            font: font(size, serif: serif), bold: font(size * 1.3, serif: serif, bold: true), color: color)
    }

    static func attributed(_ text: String, title: String, size: CGFloat, lineSpacing: Double, indent: Bool,
        font: UIFont, bold: UIFont, color: UIColor) -> NSAttributedString {
        let out = NSMutableAttributedString()
        let gap = size * (0.25 + 0.75 * lineSpacing)          // WeRead 适中 ≈ 0.6 em between lines
        let body = NSMutableParagraphStyle()
        body.lineSpacing = gap
        body.paragraphSpacing = gap * 0.35
        body.firstLineHeadIndent = indent ? size * 2 : 0
        body.alignment = .justified
        let head = NSMutableParagraphStyle()
        head.lineSpacing = gap * 0.6
        head.paragraphSpacing = size * 1.4
        let ns = text as NSString
        var first = true
        ns.enumerateSubstrings(in: NSRange(location: 0, length: ns.length), options: [.byParagraphs, .substringNotRequired]) { _, _, enclosing, _ in
            let piece = ns.substring(with: enclosing)
            let isHead = first && piece.trimmingCharacters(in: .newlines) == title
            first = false
            out.append(NSAttributedString(string: piece, attributes: [
                            .font: isHead ? bold : font,
                            .foregroundColor: color,
                            .paragraphStyle: isHead ? head : body,
            ]))
        }
        return out
    }

    /// Back-compat for tests: `spacing` 0/1/2 = 紧凑 / 适中 / 宽松.
    static func attributed(_ text: String, title: String, size: CGFloat, spacing: Int, serif: Bool, color: UIColor) -> NSAttributedString {
        attributed(text, title: title, size: size, lineSpacing: [0.1, 0.5, 0.9][max(0, min(2, spacing))], serif: serif, color: color)
    }

    /// Page ranges for a page of `size` (same container settings as `NovelTextView`).
    static func paginate(_ s: NSAttributedString, size: CGSize) -> [NSRange] {
        guard s.length > 0, size.width > 20, size.height > 20 else { return [NSRange(location: 0, length: s.length)] }
        let storage = NSTextStorage(attributedString: s)
        let lm = NSLayoutManager()
        storage.addLayoutManager(lm)
        var pages: [NSRange] = []
        var lastGlyphEnd = -1
        while pages.count < 4000 {
            let tc = NSTextContainer(size: size)
            tc.lineFragmentPadding = 0
            lm.addTextContainer(tc)
            let g = lm.glyphRange(for: tc)
            if g.length == 0 || NSMaxRange(g) <= lastGlyphEnd { break }
            lastGlyphEnd = NSMaxRange(g)
            pages.append(lm.characterRange(forGlyphRange: g, actualGlyphRange: nil))
            if lastGlyphEnd >= lm.numberOfGlyphs { break }
        }
        return pages.isEmpty ? [NSRange(location: 0, length: s.length)] : pages
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

// MARK: - Reader

/// Commands from SwiftUI into the UIKit pager (taps / swipes are handled inside the pager itself).
@MainActor final class NovelPagerHandle {
    weak var controller: NovelPagerController?
    var version = 0
}

/// Paged reading (仿真 / 滑动 / 覆盖 / 无动画).
struct NovelPagedView: UIViewControllerRepresentable {
    let paginator: NovelPaginator
    let handle: NovelPagerHandle
    var start: NovelPageRef
    var style: NovelTurnStyle
    var insets: UIEdgeInsets
    var highlight: NovelHighlight?
    var version: Int
    var onPage: (NovelPageRef) -> Void
    var onCenter: () -> Void
    var onSwipeUp: () -> Void

    func makeUIViewController(context: Context) -> NovelPagerController {
        let c = NovelPagerController(paginator: paginator, start: start)
        c.insets = insets
        c.setStyle(style)
        handle.controller = c
        handle.version = version
        return c
    }

    func updateUIViewController(_ c: NovelPagerController, context: Context) {
        c.onPageChange = onPage
        c.onCenterTap = onCenter
        c.onSwipeUp = onSwipeUp
        let insetsChanged = c.insets != insets
        let versionChanged = handle.version != version
        c.insets = insets
        handle.version = version
        c.setStyle(style)
        c.highlight = highlight
        if insetsChanged || versionChanged || c.current != start {
            c.show(start, forceRebuild: insetsChanged)
        }
    }
}

/// Reader after WeRead (iOS): chapter name top-left, page number bottom-right, interactive page turning,
/// tap the middle or swipe up for the toolbar (目录 · 书籍 · 进度 · 亮度 · 字体) and 听书.
struct NovelReaderView: View {
    @State private var book: NovelBook
    @Environment(\.layoutMetrics) private var layout
    @ObservedObject private var store = NovelStore.shared
    @ObservedObject private var prefs = NovelPrefs.shared
    @ObservedObject private var fonts = NovelFonts.shared
    @ObservedObject private var loader = NovelWebLoader.shared
    @Environment(\.dismiss) private var dismiss

    @State private var paginator: NovelPaginator
    @State private var handle = NovelPagerHandle()
    @State private var ref = NovelPageRef(chapter: 0, index: 0)
    @State private var version = 0
    @State private var insets = UIEdgeInsets.zero
    @State private var ready = false
    @State private var menu = false
    @State private var tab: Tab?
    @State private var hint = true
    @State private var lastLight: NovelPrefs.Theme = .white
    @State private var autoTurn = false
    @StateObject private var speaker = NovelSpeaker()
    @State private var opened = Date()
    @State private var brightness = 0.5
    @State private var savedBrightness: CGFloat?
    @State private var brightnessTouched = false
    @State private var tocSearch = false
    @State private var query = ""
    @State private var hits: [(chapter: Int, offset: Int, snippet: String)] = []
    @State private var searching = false
    /// Progress slider position while it is dragged (the page jumps once, on release).
    @State private var seekFraction: Double?
    @State private var showDownloads = false
    @State private var showSources = false
    @State private var showReviews = false
    @State private var choosingVoice = false
    /// Listing the system voices is slow: once, when 听书 is opened.
    @State private var voices: [AVSpeechSynthesisVoice] = []
    @State private var relayoutTask: Task<Void, Never>?
    /// Bounded chapter window for scroll mode so jumping to chapter N never walks 0...N in a LazyVStack.
    @State private var scrollWindow: ClosedRange<Int> = 0...0
    @State private var scrollEpoch = 0
    @State private var suppressScrollTrackUntil = Date.distantPast
    @State private var lastSavedChapter = -1
    @State private var lastSavedOffset = -1
    @Namespace private var glassNS
    @Namespace private var toolNS
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    enum Tab: Equatable { case toc, info, progress, light, font, fonts, turn, listen }

    init(book: NovelBook) {
        _book = State(initialValue: book)
        _paginator = State(initialValue: NovelPaginator(book: book))
    }

    private var pal: NovelLayout.Palette { NovelLayout.palette(prefs.theme) }
    private var hMargin: CGFloat { NovelLayout.margin(prefs.margin) }
    private var totalChars: Int { book.chapters.last?.end ?? 0 }
    private var offsetInChapter: Int { paginator.style == nil ? 0 : paginator.range(ref).location }
    private var bookOffset: Int { (book.chapters.indices.contains(ref.chapter) ? book.chapters[ref.chapter].start : 0) + offsetInChapter }
    private var fraction: Double { totalChars > 0 ? Double(bookOffset) / Double(totalChars) : 0 }
    private var highlight: NovelHighlight? { speaker.sentence.map { NovelHighlight(chapter: speaker.chapter, range: $0) } }
    private func title(_ i: Int) -> String { book.chapters.indices.contains(i) ? book.chapters[i].title : "" }

    private var currentSource: LegadoSource? { book.sourceURL.flatMap { LegadoSourceStore.shared.source($0) } }
    private var isScrollMode: Bool {
        prefs.turn == .scroll || currentSource?.isImage == true || paginator.hasCachedImages(ref.chapter)
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottom) {
                pal.bg.ignoresSafeArea()
                if ready {
                    Group {
                        if isScrollMode {
                            scrollReader(geo)
                                .frame(maxWidth: layout.readerMaxWidth)
                                .frame(maxWidth: .infinity)
                        } else {
                            NovelPagedView(paginator: paginator, handle: handle, start: ref, style: prefs.turn, insets: insets,
                                highlight: highlight, version: version,
                                onPage: { r in ref = r; save() },
                                onCenter: { menu ? hideMenu() : showMenu() },
                                onSwipeUp: { showMenu() })
                                .ignoresSafeArea()
                        }
                    }
                }
                if hint && !menu {
                    Text("↑上滑显示工具栏").font(.system(size: 12)).foregroundStyle(pal.secondary)
                        .padding(.bottom, 8).transition(.opacity).allowsHitTesting(false)
                }
                if book.isWeb, let err = loader.error(book, ref.chapter), !menu {
                    Button { loader.retry(book, ref.chapter) } label: {
                        Label("重新加载本章", systemImage: "arrow.clockwise").font(.system(size: 14, weight: .medium)).foregroundStyle(pal.ink)
                            .padding(.horizontal, 18).frame(height: 44)
                            .contentShape(Capsule())
                            .liquidGlassCapsule(tone: glassTone, interactive: true)
                    }
                    .buttonStyle(GlassPress()).padding(.bottom, 90)
                    .accessibilityHint(err)
                    .accessibilityIdentifier("novel.retry")
                }
                if menu {
                    // Everything between the floating bars closes the menu.
                    Color.clear.contentShape(Rectangle()).ignoresSafeArea().onTapGesture { hideMenu() }
                        .accessibilityHidden(true)
                    topBar.frame(maxHeight: .infinity, alignment: .top)
                        .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                }
                bottomGlass(geo)
            }
            .onAppear {
                MusicPlayerEngine.shared.enterImmersive("novelReader")
                configure(PageBox(geo)); restore()
                if savedBrightness == nil { savedBrightness = screen?.brightness }
                brightness = Double(screen?.brightness ?? 0.5)
                if book.isWeb {
                    loader.prefetch(book, around: ref.chapter)
                    Task {
                        _ = try? await loader.refreshToc(book)
                        if let updated = store.book(book.id), updated.chapters.count > book.chapters.count {
                            let cur = ref
                            let st = paginator.style
                            book = updated
                            let nextPaginator = NovelPaginator(book: updated)
                            if let st { nextPaginator.configure(st) }
                            paginator = nextPaginator
                            ref = cur
                        }
                    }
                }
            }
            .compatOnChange(of: layoutKey(geo)) { scheduleRelayout(geo) }
            .compatOnChange(of: book.id) { configure(PageBox(geo)); restore(); if book.isWeb { loader.prefetch(book, around: ref.chapter) } }
        }
        .statusBarHidden(!menu)
        .sheet(isPresented: $showDownloads) { NovelDownloadView(book: book, chapter: ref.chapter) }
        .sheet(isPresented: $showReviews) {
            if let src = currentSource, book.chapters.indices.contains(ref.chapter) {
                let chURL = (book.chapterURLs?.indices.contains(ref.chapter) == true ? book.chapterURLs![ref.chapter] : "")
                NovelReviewSheet(
                    source: src,
                    chapter: LegadoChapter(title: book.chapters[ref.chapter].title, url: chURL, index: ref.chapter),
                    bookTitle: book.title,
                    bookURL: book.bookURL ?? ""
                )
            }
        }
        .sheet(isPresented: $showSources) {
            if let sourceURL = book.sourceURL, let bookURL = book.bookURL {
                NavigationStack {
                    NovelWebDetailView(items: [LegadoSearchItem(name: book.title, author: book.author ?? "", kind: "", intro: "", cover: book.coverURL ?? "", latest: "", bookURL: bookURL, sourceURL: sourceURL, sourceName: LegadoSourceStore.shared.source(sourceURL)?.bookSourceName ?? "当前来源")], onRead: { replacement in
                        showSources = false
                        speaker.stop()
                        book = replacement
                        paginator = NovelPaginator(book: replacement)
                        ref = NovelPageRef(chapter: store.progress[replacement.id]?.chapter ?? 0, index: 0)
                        ready = false; version += 1
                    }, resumeFrom: book)
                }
            }
        }
        .dsOptions(isPresented: $choosingVoice, title: "音色", options: voices.map(\.identifier),
                   selection: Binding(get: { speaker.voiceID ?? voices.first { $0.language == "zh-CN" }?.identifier ?? "" }, set: { speaker.voiceID = $0 }),
                   label: { id in voices.first { $0.identifier == id }.map(NovelSpeaker.label) ?? "系统默认" })
        .stvLocalColorScheme(prefs.theme == .night ? .dark : .light)
        .compatOnChange(of: speaker.sentence) { s in if let s { follow(s) } }
        .compatOnChange(of: loader.lastEvent) { event in
            guard book.isWeb, event.bookID == nil || event.bookID == book.id else { return }
            if let ch = event.chapter {
                paginator.invalidate(chapter: ch)
                if isScrollMode {
                    if scrollWindow.contains(ch) { version += 1 }
                } else if ch == ref.chapter {
                    let o = offsetInChapter
                    ref = paginator.ref(chapter: ch, offset: o)
                    version += 1
                } else if abs(ch - ref.chapter) <= 1 {
                    handle.controller?.reloadNeighbours()
                }
            } else {
                let c = ref.chapter, o = offsetInChapter
                paginator.invalidate()
                if isScrollMode {
                    version += 1
                } else {
                    ref = paginator.ref(chapter: c, offset: o)
                    version += 1
                }
            }
        }
        .compatOnChange(of: ref.chapter) { c in if book.isWeb { loader.prefetch(book, around: c) } }
        .onDisappear {
            MusicPlayerEngine.shared.leaveImmersive("novelReader")
            relayoutTask?.cancel()
            loader.cancelPrefetch(book)
            store.addReadTime(book, Date().timeIntervalSince(opened))
            speaker.stop()
            if brightnessTouched, let orig = savedBrightness { screen?.brightness = orig }
        }
        .task {
            // WeRead shows the hint briefly when a book opens.
            try? await Task.sleep(for: .seconds(3))
            withAnimation(.easeOut(duration: 0.6)) { hint = false }
        }
        .task(id: autoTurn) {
            while autoTurn, !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15))
                if autoTurn, !menu { handle.controller?.turn(+1) }
            }
        }
    }

    // MARK: Layout

    private func layoutKey(_ geo: GeometryProxy) -> String {
        "\(prefs.fontSize)-\(prefs.lineSpacing)-\(prefs.margin)-\(prefs.fontID)-\(prefs.indent)-\(prefs.theme.rawValue)-\(fonts.revision)-\(Int(geo.size.width))x\(Int(geo.size.height))-\(Int(geo.safeAreaInsets.top))-\(Int(geo.safeAreaInsets.bottom))-\(Int(layout.readerMaxWidth ?? 0))"
    }

    /// Typography sliders emit a value per touch event; laying the chapter out again for each of them made them crawl.
    private func scheduleRelayout(_ geo: GeometryProxy) {
        relayoutTask?.cancel()
        let box = PageBox(geo)
        relayoutTask = Task {
            try? await Task.sleep(nanoseconds: 60_000_000)
            if !Task.isCancelled { relayout(box) }
        }
    }

    /// Page geometry as values: a GeometryProxy must not outlive the update it came from.
    private struct PageBox {
        let size: CGSize
        let safe: EdgeInsets
        init(_ g: GeometryProxy) { size = g.size; safe = g.safeAreaInsets }
    }

    private func pageStyle(_ box: PageBox) -> NovelPageStyle {
        let p = pal
        let containerW = layout.readerMaxWidth.map { min(box.size.width, $0) } ?? box.size.width
        let pageW = containerW - 2 * hMargin
        return NovelPageStyle(size: CGSize(width: max(1, pageW), height: max(1, box.size.height - 62)),
            fontSize: prefs.fontSize, lineSpacing: prefs.lineSpacing, indent: prefs.indent, fontID: prefs.fontID,
            text: p.text, secondary: UIColor(p.secondary), background: UIColor(p.bg),
            highlight: UIColor(NovelLayout.accent).withAlphaComponent(0.22))
    }

    private func configure(_ box: PageBox) {
        _ = fonts.isReady(prefs.fontID)
        let containerW = layout.readerMaxWidth.map { min(box.size.width, $0) } ?? box.size.width
        let extraH = max(0, (box.size.width - containerW) / 2)
        insets = UIEdgeInsets(top: box.safe.top, left: hMargin + extraH + box.safe.leading,
            bottom: box.safe.bottom, right: hMargin + extraH + box.safe.trailing)
        paginator.configure(pageStyle(box))
    }

    /// Re-paginates and stays on the page holding the same text.
    private func relayout(_ box: PageBox) {
        let chapter = ref.chapter, offset = isScrollMode ? 0 : offsetInChapter
        configure(box)
        if !isScrollMode {
            ref = paginator.ref(chapter: chapter, offset: offset)
            version += 1
        }
    }

    private func resetScrollWindow(around chapter: Int) {
        guard !book.chapters.isEmpty else { scrollWindow = 0...0; return }
        let c = min(max(0, chapter), book.chapters.count - 1)
        let end = min(book.chapters.count - 1, c + 1)
        scrollWindow = c...end
        scrollEpoch += 1
        suppressScrollTrackUntil = Date().addingTimeInterval(0.35)
    }

    // MARK: Scroll mode (sliding chapter window so jumping to any chapter is O(1) and never freezes)

    private func scrollReader(_ geo: GeometryProxy) -> some View {
        let validLower = min(max(0, scrollWindow.lowerBound), max(0, book.chapters.count - 1))
        let validUpper = min(max(validLower, scrollWindow.upperBound), max(0, book.chapters.count - 1))
        let visibleRange = book.chapters.isEmpty ? [] : Array(validLower...validUpper)
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 36) {
                    if validLower > 0 {
                        Button { jump(chapter: validLower - 1, offset: 0, keepMenu: true) } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "arrow.up.circle")
                                Text("上一章 · \(title(validLower - 1))").lineLimit(1)
                            }
                            .font(.system(size: 13, weight: .medium))
                            .foregroundStyle(pal.secondary)
                            .frame(maxWidth: .infinity, minHeight: 40)
                            .background(Capsule().fill(fill))
                            .contentShape(Capsule())
                        }
                        .buttonStyle(PressScale(scale: 0.98))
                    }
                    ForEach(visibleRange, id: \.self) { i in
                        VStack(alignment: .leading, spacing: 12) {
                            if let src = currentSource, paginator.hasCachedImages(i) {
                                ForEach(splitChapterSegments(paginator.scrollChapter(i).string)) { seg in
                                    switch seg {
                                    case .text(_, let chunk):
                                        Text(chunk)
                                            .font(.system(size: prefs.fontSize))
                                            .foregroundStyle(pal.ink)
                                            .lineSpacing(prefs.fontSize * (0.25 + 0.75 * prefs.lineSpacing))
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                    case .image(_, let imgURL):
                                        NovelDecodedImageView(source: src, url: imgURL)
                                    }
                                }
                            } else {
                                NovelTextView(text: scrollText(i))
                                    .accessibilityElement()
                                    .accessibilityLabel(title(i))
                                    .accessibilityValue(scrollText(i).string)
                                    .accessibilityIdentifier("novel.scroll.chapter.\(i)")
                            }
                            if book.isWeb, let err = loader.error(book, i) {
                                Button { loader.retry(book, i) } label: {
                                    Label("重新加载本章", systemImage: "arrow.clockwise")
                                        .font(.system(size: 13, weight: .medium))
                                        .foregroundStyle(pal.ink)
                                        .padding(.horizontal, 14).frame(height: 36)
                                        .background(Capsule().fill(fill))
                                }
                                .buttonStyle(PressScale())
                                .accessibilityHint(err)
                            }
                        }
                        .id(i)
                        .task(id: "\(book.id)|\(i)|\(loader.revision)") {
                            if book.isWeb { loader.ensure(book, i) }
                        }
                        .onAppear {
                            if i == validUpper, validUpper + 1 < book.chapters.count {
                                scrollWindow = validLower...(validUpper + 1)
                            }
                        }
                        .compatOnFrame(in: .named("novel.scroll")) { f in
                            trackScroll(i, f)
                        }
                    }
                }
                .padding(.horizontal, hMargin).padding(.top, 36).padding(.bottom, 80)
            }
            .id(scrollEpoch)
            .coordinateSpace(name: "novel.scroll")
            .scrollIndicators(.hidden)
            .overlay(alignment: .topLeading) {
                Text(title(ref.chapter)).font(.system(size: 12)).foregroundStyle(pal.secondary)
                    .padding(.horizontal, hMargin).frame(maxWidth: .infinity, minHeight: 28, alignment: .leading).background(pal.bg)
                    .accessibilityIdentifier("novel.scroll.title")
            }
            .contentShape(Rectangle())
            .onTapGesture { menu ? hideMenu() : showMenu() }
            .compatOnChange(of: speaker.sentence) { s in
                // 听书 in scroll mode: keep the sentence on screen (anchor at the same relative position).
                guard let s, book.chapters.indices.contains(speaker.chapter) else { return }
                if !scrollWindow.contains(speaker.chapter) {
                    resetScrollWindow(around: speaker.chapter)
                }
                let len = max(1, book.chapters[speaker.chapter].length)
                withAnimation(.easeInOut(duration: 0.3)) { proxy.scrollTo(speaker.chapter, anchor: UnitPoint(x: 0, y: min(1, Double(s.location) / Double(len)))) }
            }
        }
    }

    private func scrollText(_ i: Int) -> NSAttributedString {
        // Touch loader.revision & version so SwiftUI refreshes when a web chapter finishes or fails.
        _ = loader.revision
        _ = version
        let t = paginator.scrollChapter(i)
        guard let h = highlight, h.chapter == i, NSMaxRange(h.range) <= t.length else { return t }
        let m = NSMutableAttributedString(attributedString: t)
        m.addAttribute(.backgroundColor, value: UIColor(NovelLayout.accent).withAlphaComponent(0.22), range: h.range)
        return m
    }

    private func trackScroll(_ i: Int, _ f: CGRect) {
        guard Date() >= suppressScrollTrackUntil, f.minY <= 40, f.maxY > 40, f.height > 0 else { return }
        let r = NovelPageRef(chapter: i, index: 0)
        if r.chapter != ref.chapter {
            ref = r
            save()
        }
    }

    // MARK: Floating glass: top bar, toolbar ↔ panel, 听书

    /// Liquid Glass in the paper's own colour, so a panel over cream or night paper keeps its contrast.
    private var glassTone: LiquidGlassTone { .tinted(pal.panel.opacity(prefs.theme == .night ? 0.45 : 0.6)) }

    private var topBar: some View {
        LiquidGlassGroup(spacing: 12) {
            HStack(spacing: 10) {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundStyle(pal.ink)
                        .frame(width: 46, height: 46).contentShape(Circle())
                        .liquidGlass(Circle(), tone: glassTone, interactive: true)
                }
                .accessibilityLabel("返回")
                .accessibilityIdentifier("novel.back")
                Spacer(minLength: 0)
                if book.isWeb {
                    HStack(spacing: 0) {
                        if let src = currentSource, LegadoReview.isEnabled(src) {
                            Button { showReviews = true } label: {
                                Label("段评", systemImage: "text.bubble")
                                    .font(.system(size: 14, weight: .medium))
                                    .padding(.leading, 14).padding(.trailing, 8).frame(minHeight: 46).contentShape(Rectangle())
                            }
                        }
                        Button { save(); showSources = true } label: { Text("换源").font(.system(size: 15, weight: .medium)).padding(.leading, 14).padding(.trailing, 10).frame(minHeight: 46).contentShape(Rectangle()) }
                        Button { showDownloads = true } label: { Image(systemName: "arrow.down.circle").font(.system(size: 19, weight: .medium)).padding(.leading, 8).padding(.trailing, 16).frame(minHeight: 46).contentShape(Rectangle()) }
                            .accessibilityLabel("缓存与下载")
                    }
                    .foregroundStyle(pal.ink)
                    .liquidGlassCapsule(tone: glassTone, interactive: true)
                }
            }
            .buttonStyle(GlassPress())
        }
        .padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 18)
        // Text dissolves into the paper under the buttons instead of colliding with them.
        .background {
            LinearGradient(stops: [.init(color: pal.bg, location: 0), .init(color: pal.bg.opacity(0.92), location: 0.72), .init(color: pal.bg.opacity(0), location: 1)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .top).allowsHitTesting(false)
        }
    }

    /// One glass container at the bottom: the toolbar grows into its panel, and the 听书 button turns into the mini player
    /// when the menu closes while something is being read.
    private func bottomGlass(_ geo: GeometryProxy) -> some View {
        LiquidGlassGroup(spacing: 12) {
            VStack(alignment: .trailing, spacing: 12) {
                if menu, tab != .listen {
                    listenButton.liquidGlassID("listen", in: glassNS).padding(.trailing, 16)
                } else if !menu, speaker.active {
                    miniPlayer.liquidGlassID("listen", in: glassNS).padding(.trailing, 16).padding(.bottom, 40)
                }
                if menu {
                    ReaderMusicMiniBar()
                        .padding(.horizontal, 16)
                        .frame(maxWidth: 560).frame(maxWidth: .infinity)
                        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                    surface(geo)
                        .padding(.horizontal, 12)
                        .frame(maxWidth: 560).frame(maxWidth: .infinity)
                        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.bottom, 6)
        .background(alignment: .bottom) {
            if menu {
                LinearGradient(colors: [pal.bg.opacity(0), pal.bg], startPoint: .top, endPoint: .bottom).frame(height: 72)
                    .ignoresSafeArea(edges: .bottom).allowsHitTesting(false).transition(.opacity)
            }
        }
    }

    private func surface(_ geo: GeometryProxy) -> some View {
        let shape = RoundedRectangle(cornerRadius: 32, style: .continuous)
        return VStack(spacing: 0) {
            if let tab {
                panel(tab, height: max(220, min(460, geo.size.height - 190)))
                    .padding(.horizontal, 18).padding(.top, 18).padding(.bottom, 8)
                    .transition(.opacity)
            }
            toolbar
        }
        .frame(maxWidth: .infinity)
        // Content of a closing panel must not spill out of the shrinking glass.
        .clipShape(shape)
        .contentShape(shape)
        .onTapGesture {}
        .liquidGlass(shape, tone: glassTone)
        .onGlassSurface()
    }

    private var toolbar: some View {
        HStack(spacing: 0) {
            toolIcon(.toc, "list.bullet", "目录")
            toolIcon(.info, tab == .info ? "safari.fill" : "safari", "书籍")
            toolIcon(.progress, "circle.and.line.horizontal", "进度")
            toolIcon(.light, tab == .light ? "sun.max.fill" : "sun.max", "亮度")
            toolIcon(.font, "textformat.size", "设置")
        }
        .padding(6).frame(height: 66)
    }

    private func toolIcon(_ t: Tab, _ icon: String, _ name: String) -> some View {
        let on = tab == t || (t == .font && (tab == .fonts || tab == .turn))
        return Button { DS.tick(); open(on ? nil : t) } label: {
            VStack(spacing: 3) {
                Image(systemName: icon).font(.system(size: 19, weight: on ? .semibold : .regular)).frame(height: 24)
                Text(name).font(.system(size: 10, weight: on ? .semibold : .medium))
            }
            .foregroundStyle(on ? NovelLayout.accent : pal.ink.opacity(0.86))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background { if on { Capsule().fill(NovelLayout.accent.opacity(0.13)).matchedGeometryEffect(id: "tool", in: toolNS) } }
            .contentShape(Rectangle())
        }
        .buttonStyle(PressScale(scale: 0.94)).accessibilityLabel(name).accessibilityIdentifier("novel.menu.\(name)")
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func open(_ t: Tab?) {
        if t == .listen, voices.isEmpty { voices = NovelSpeaker.voices }
        withAnimation(DS.motion(DS.panel, reduce: reduceMotion)) { tab = t }
    }

    @ViewBuilder private func panel(_ t: Tab, height: CGFloat) -> some View {
        switch t {
        case .toc: tocPanel.frame(height: height)
        case .info: infoPanel
        case .progress: progressPanel
        case .light: lightPanel
        case .font: fontPanel
        case .fonts: fontsPanel(height: height)
        case .turn: turnPanel
        case .listen: listenPanel
        }
    }

    private func pill(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 14, weight: .medium)).foregroundStyle(pal.ink)
                .frame(maxWidth: .infinity, minHeight: 44).background(Capsule().fill(fill)).contentShape(Capsule())
        }
        .buttonStyle(PressScale(scale: 0.97))
    }

    /// Control ground inside the glass panel.
    private var fill: Color { pal.ink.opacity(prefs.theme == .night ? 0.12 : 0.07) }

    private func chip(_ title: String, on: Bool, id: String? = nil, _ action: @escaping () -> Void) -> some View {
        Button { DS.tick(); action() } label: {
            Text(title).font(.system(size: 13, weight: on ? .semibold : .regular))
                .foregroundStyle(on ? NovelLayout.accent : pal.ink)
                .padding(.horizontal, 12).frame(minHeight: 34)
                .background(Capsule().fill(on ? NovelLayout.accent.opacity(0.14) : fill))
                .overlay(Capsule().strokeBorder(on ? NovelLayout.accent.opacity(0.7) : .clear, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(PressScale())
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier(id ?? "novel.chip.\(title)")
    }

    private func subHeader(_ t: String) -> some View {
        HStack(spacing: 6) {
            Button { open(.font) } label: {
                Image(systemName: "chevron.left").font(.system(size: 15, weight: .semibold)).frame(width: 36, height: 36).background(Circle().fill(fill)).contentShape(Circle())
            }
            .buttonStyle(PressScale()).accessibilityLabel("返回设置")
            Text(t).dsFont(16, .semibold, relativeTo: .headline).accessibilityAddTraits(.isHeader)
            Spacer()
        }
        .foregroundStyle(pal.ink)
    }

    private var rule: some View { pal.ink.opacity(0.10).frame(height: 1) }

    // 进度
    private var progressPanel: some View {
        let read = store.readTime[book.id] ?? 0
        let left = Double(totalChars - bookOffset) / 450 / 60      // ~450 字/分钟
        return VStack(spacing: 18) {
            HStack(spacing: 0) {
                stat("\(Int((fraction * 100).rounded()))", "%", book.isWeb ? "还剩 \(book.chapters.count - ref.chapter - 1) 章"
                    : left >= 1 ? "约\(Int(left.rounded()))小时后读完" : "约\(max(1, Int(left * 60)))分钟后读完")
                pal.ink.opacity(0.12).frame(width: 1, height: 34)
                stat(read >= 3600 ? "\(Int(read / 3600))" : "\(Int(read / 60))", read >= 3600 ? "小时\(Int(read.truncatingRemainder(dividingBy: 3600) / 60))分钟" : "分钟", "阅读时长")
                pal.ink.opacity(0.12).frame(width: 1, height: 34)
                stat("\(ref.chapter + 1)", "/\(book.chapters.count)", "章节")
            }
            HStack(spacing: 0) {
                chapterStep("chevron.left", "上一章", enabled: ref.chapter > 0) { go(ref.chapter - 1) }
                PillSlider(value: Binding(get: { seekFraction ?? fraction }, set: { seekFraction = $0 }), track: .clear, thumb: pal.thumb, ink: pal.ink,
                           onEditing: { editing in if !editing, let f = seekFraction { jump(toFraction: f); seekFraction = nil } })
                    .accessibilityLabel("全书进度")
                chapterStep("chevron.right", "下一章", enabled: ref.chapter + 1 < book.chapters.count) { go(ref.chapter + 1) }
            }
            .foregroundStyle(pal.ink).background(Capsule().fill(fill))
            HStack(spacing: 12) {
                pill("回到本章开头") { go(ref.chapter) }
                if prefs.turn != .scroll {
                    pill(autoTurn ? "关闭自动翻页" : "开启自动翻页") { autoTurn.toggle(); if autoTurn { hideMenu() } }
                }
            }
        }
    }

    private func chapterStep(_ icon: String, _ label: String, enabled: Bool, _ f: @escaping () -> Void) -> some View {
        Button(action: f) { Image(systemName: icon).font(.system(size: 15, weight: .semibold)).frame(width: 44, height: 46).contentShape(Rectangle()) }
            .buttonStyle(PressScale()).disabled(!enabled).opacity(enabled ? 1 : 0.3).accessibilityLabel(label)
    }

    private func stat(_ big: String, _ unit: String, _ sub: String) -> some View {
        VStack(spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 1) {
                Text(big).font(.system(size: 22, weight: .semibold).monospacedDigit())
                Text(unit).font(.system(size: 12))
            }
            .foregroundStyle(pal.ink)
            Text(sub).font(.system(size: 11)).foregroundStyle(pal.secondary).lineLimit(1).minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    // 亮度 / 颜色
    private var lightPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            PillSlider(value: Binding(get: { brightness }, set: { brightness = $0; brightnessTouched = true; screen?.brightness = CGFloat($0) }),
                track: fill, thumb: pal.thumb, ink: pal.ink, leading: AnyView(Image(systemName: "sun.min").font(.system(size: 14)).foregroundStyle(pal.ink)),
                trailing: AnyView(Image(systemName: "sun.max").font(.system(size: 16)).foregroundStyle(pal.ink)))
                .accessibilityLabel("亮度")
            Text("颜色").font(.system(size: 12)).foregroundStyle(pal.secondary)
            HStack(spacing: 12) {
                ForEach(NovelPrefs.Theme.allCases, id: \.self) { t in
                    let on = t == prefs.theme
                    Button { DS.tick(); withAnimation(.easeInOut(duration: 0.25)) { prefs.theme = t } } label: {
                        RoundedRectangle(cornerRadius: 16, style: .continuous).fill(NovelLayout.palette(t).bg)
                            .overlay {
                                if t == .night { Image(systemName: "moon.fill").foregroundStyle(Color(hex: 0x4D7CFE)) }
                                else { Text("文").font(.system(size: 16, weight: .medium)).foregroundStyle(NovelLayout.palette(t).ink) }
                            }
                            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(on ? NovelLayout.accent : pal.ink.opacity(0.10), lineWidth: on ? 2 : 1))
                            .frame(height: 48)
                    }
                    .buttonStyle(PressScale(scale: 0.95))
                    .accessibilityLabel(["白色", "米黄", "护眼绿", "夜间"][min(t.rawValue, 3)])
                    .accessibilityAddTraits(on ? .isSelected : [])
                    .accessibilityIdentifier("novel.theme.\(t.rawValue)")
                }
            }
        }
        .padding(.bottom, 4)
    }

    // 字体
    private var fontPanel: some View {
        VStack(spacing: 14) {
            PillSlider(value: Binding(get: { (prefs.fontSize - 12) / 18 }, set: { v in let size = (12 + v * 18).rounded(); if size != prefs.fontSize { prefs.fontSize = size; DS.tick() } }),
                track: fill, thumb: pal.thumb, ink: pal.ink, label: "\(Int(prefs.fontSize))",
                leading: AnyView(Text("A").font(.system(size: 12)).foregroundStyle(pal.ink)),
                trailing: AnyView(Text("A").font(.system(size: 17)).foregroundStyle(pal.ink)))
                .accessibilityLabel("字号")
                .accessibilityIdentifier("novel.font.size")
            HStack(spacing: 12) {
                PillSlider(value: stepped($prefs.margin), track: fill, thumb: pal.thumb, ink: pal.ink, label: "边距",
                    trailing: AnyView(Text("大").font(.system(size: 13)).foregroundStyle(pal.ink)))
                    .accessibilityLabel("边距")
                PillSlider(value: stepped($prefs.lineSpacing), track: fill, thumb: pal.thumb, ink: pal.ink, label: "行距",
                    leading: AnyView(Text("紧").font(.system(size: 13)).foregroundStyle(pal.ink)),
                    trailing: AnyView(Text("松").font(.system(size: 13)).foregroundStyle(pal.ink)))
                    .accessibilityLabel("行距")
            }
            HStack(spacing: 10) {
                menuPill(NovelFonts.all.first { $0.id == prefs.fontID }?.name ?? "字体样式", id: "novel.fonts") { open(.fonts) }
                menuPill(prefs.indent ? "首行缩进" : "不缩进", id: "novel.indent", chevron: false) { DS.tick(); prefs.indent.toggle() }
                menuPill(prefs.turn.label, id: "novel.turn") { open(.turn) }
            }
        }
    }

    /// 1/20 steps: fine enough to look continuous, few enough not to lay the chapter out again for every pixel.
    private func stepped(_ b: Binding<Double>) -> Binding<Double> {
        Binding(get: { b.wrappedValue }, set: { v in let s = (v * 20).rounded() / 20; if s != b.wrappedValue { b.wrappedValue = s } })
    }

    private func menuPill(_ t: String, id: String, chevron: Bool = true, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 3) {
                Text(t).lineLimit(1).minimumScaleFactor(0.8)
                if chevron { Image(systemName: "chevron.right").font(.system(size: 10, weight: .semibold)) }
            }
            .font(.system(size: 14, weight: .medium)).foregroundStyle(pal.ink)
            .frame(maxWidth: .infinity, minHeight: 44).background(Capsule().fill(fill)).contentShape(Capsule())
        }
        .buttonStyle(PressScale(scale: 0.97)).accessibilityIdentifier(id)
    }

    // 字体样式
    private func fontsPanel(height: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            subHeader("字体样式")
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(NovelFonts.all) { f in fontRow(f) }
                }
            }
            .frame(maxHeight: min(330, height - 60))
        }
    }

    private func fontRow(_ f: NovelFonts.Entry) -> some View {
        let ready = fonts.isReady(f.id)
        let on = prefs.fontID == f.id
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(f.name).font(Font(NovelFonts.font(f.id, size: 17) as CTFont)).foregroundStyle(pal.ink)
                Text(fonts.errors[f.id] ?? f.note).font(.system(size: 11)).foregroundStyle(fonts.errors[f.id] == nil ? pal.secondary : DSTone.danger)
            }
            Spacer()
            if let p = fonts.progress[f.id] {
                DSProgressBar(value: p, height: 4, track: pal.ink.opacity(0.14), tint: NovelLayout.accent).frame(width: 60)
            } else if on {
                Image(systemName: "checkmark").font(.system(size: 15, weight: .semibold)).foregroundStyle(NovelLayout.accent)
            } else if !ready {
                Text(f.bytes > 0 ? "下载 \(f.bytes / 1_000_000)MB" : "下载").font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(NovelLayout.accent)
                    .padding(.horizontal, 12).frame(minHeight: 30).background(Capsule().fill(NovelLayout.accent.opacity(0.14)))
            }
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .onTapGesture { if ready { DS.tick(); prefs.fontID = f.id } else { fonts.fetch(f.id) } }
        .contextMenu { if fonts.downloaded(f.id) { Button("删除已下载的字体", role: .destructive) { if on { prefs.fontID = "system" }; fonts.delete(f.id) } } }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier("novel.font.\(f.id)")
    }

    // 翻页方式
    private var turnPanel: some View {
        VStack(alignment: .leading, spacing: 14) {
            subHeader("翻页方式")
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 10) {
                ForEach(NovelTurnStyle.allCases) { t in
                    chip(t.label, on: prefs.turn == t, id: "novel.turn.\(t.rawValue)") {
                        let c = ref.chapter, o = isScrollMode ? 0 : offsetInChapter
                        prefs.turn = t
                        autoTurn = false
                        if t == .scroll {
                            ref = NovelPageRef(chapter: c, index: 0)
                            resetScrollWindow(around: c)
                        } else {
                            ref = paginator.ref(chapter: c, offset: o)
                            version += 1
                        }
                    }
                }
            }
            Text("仿真：像纸书一样卷页；滑动：两页并排滑过；覆盖：当前页盖在下一页上被拖走；上下滚动：整本书连续滚动。左右轻点也能翻页。")
                .font(.system(size: 11)).foregroundStyle(pal.secondary).lineSpacing(2)
        }
    }

    // 书籍信息
    private var infoPanel: some View {
        let read = store.readTime[book.id] ?? 0
        return VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 14) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(book.title).dsFont(18, .bold, relativeTo: .title3).foregroundStyle(pal.ink).lineLimit(2)
                    Text("\(book.author.map { $0 + " · " } ?? "")时长 \(Int(read / 3600))时\(Int(read.truncatingRemainder(dividingBy: 3600) / 60))分")
                        .font(.system(size: 13)).foregroundStyle(pal.secondary)
                }
                Spacer(minLength: 0)
                NovelCover(book: book).frame(width: 52, height: 72)
            }
            rule
            HStack {
                infoCell("\(book.chapters.count)", "章节")
                if book.isWeb {
                    infoCell(LegadoSourceStore.shared.source(book.sourceURL ?? "")?.bookSourceName ?? "已删除", "书源")
                } else {
                    infoCell(totalChars >= 10_000 ? String(format: "%.1f万", Double(totalChars) / 10_000) : "\(totalChars)", "字数")
                    infoCell(book.format.uppercased(), "格式")
                }
                infoCell(book.addedAt.formatted(.dateTime.month().day()), "导入")
            }
        }
        .padding(.bottom, 6)
    }

    private func infoCell(_ v: String, _ k: String) -> some View {
        VStack(spacing: 4) {
            Text(v).font(.system(size: 15, weight: .semibold)).foregroundStyle(pal.ink).lineLimit(1).minimumScaleFactor(0.7)
            Text(k).font(.system(size: 11)).foregroundStyle(pal.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    // 目录 / 搜本书
    private var tocPanel: some View {
        let per: Double = isScrollMode
            ? 400
            : max(1, Double(paginator.chapter(ref.chapter).text.length) / Double(max(1, paginator.pageCount(ref.chapter))))
        return VStack(spacing: 10) {
            HStack(spacing: 0) {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").font(.system(size: 13))
                    TextField("搜本书", text: $query, prompt: Text("搜本书").foregroundColor(pal.secondary)).font(.system(size: 14)).submitLabel(.search)
                        .tint(NovelLayout.accent)
                        .onSubmit { search() }
                        .simultaneousGesture(TapGesture().onEnded { tocSearch = true })
                    if searching { DSSpinner(size: 14, tint: pal.secondary) }
                }
                .padding(.horizontal, 12).frame(maxWidth: .infinity, minHeight: 38)
                .background(Capsule().fill(tocSearch ? pal.bg.opacity(0.85) : .clear))
                Button { DS.endEditing(); withAnimation(DS.select) { tocSearch = false; query = ""; hits = [] } } label: {
                    Text("目录").font(.system(size: 14, weight: .semibold))
                        .frame(maxWidth: .infinity, minHeight: 38)
                        .background(Capsule().fill(tocSearch ? .clear : pal.bg.opacity(0.85)))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
            .foregroundStyle(pal.ink).padding(3).background(Capsule().fill(fill))
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if tocSearch {
                            ForEach(Array(hits.enumerated()), id: \.offset) { _, h in
                                Button { jump(chapter: h.chapter, offset: h.offset) } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(title(h.chapter)).font(.system(size: 12)).foregroundStyle(pal.secondary)
                                        Text(h.snippet).font(.system(size: 15)).foregroundStyle(pal.ink).lineLimit(2)
                                    }
                                    .padding(.vertical, 10).frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                                }
                                .buttonStyle(PressScale(scale: 0.99))
                                rule
                            }
                            if hits.isEmpty, !query.isEmpty, !searching { Text("没有找到").font(.system(size: 14)).foregroundStyle(pal.secondary).frame(maxWidth: .infinity).padding(.top, 24) }
                        } else {
                            ForEach(book.chapters.indices, id: \.self) { i in tocRow(i, per) }
                        }
                    }
                }
                .scrollIndicators(.hidden)
                .onAppear { proxy.scrollTo(ref.chapter, anchor: .center) }
            }
        }
    }

    private func tocRow(_ i: Int, _ per: Double) -> some View {
        let cur = i == ref.chapter
        return VStack(alignment: .leading, spacing: 0) {
            Button { jump(chapter: i, offset: 0) } label: {
                HStack {
                    Text(title(i)).font(.system(size: 16, weight: cur ? .bold : .regular)).foregroundStyle(cur ? NovelLayout.accent : pal.ink).lineLimit(1)
                    Spacer()
                    Text("\(Int(Double(book.chapters[i].start) / per) + 1)").font(.system(size: 13).monospacedDigit()).foregroundStyle(pal.secondary)
                }
                .frame(minHeight: 48).contentShape(Rectangle())
            }
            .buttonStyle(PressScale(scale: 0.99))
            .accessibilityLabel(title(i))
            .accessibilityAddTraits(cur ? .isSelected : [])
            if cur {
                HStack(spacing: 6) {
                    Image(systemName: "book").font(.system(size: 13))
                    Text("当前读到").font(.system(size: 13))
                    Spacer()
                    Text("\(Int(Double(bookOffset) / per) + 1)").font(.system(size: 13).monospacedDigit()).foregroundStyle(pal.secondary)
                }
                .foregroundStyle(NovelLayout.accent).padding(.bottom, 12)
            }
            rule
        }
        .id(i)
    }

    // 听书
    private var listenButton: some View {
        Button { if !speaker.active { startListening() }; open(.listen) } label: {
            Text("听").font(.system(size: 20, weight: .semibold)).foregroundStyle(pal.ink)
                .frame(width: 54, height: 54).contentShape(Circle())
                .liquidGlass(Circle(), tone: glassTone, interactive: true)
        }
        .buttonStyle(GlassPress()).accessibilityLabel("听书").accessibilityIdentifier("novel.listen")
    }

    /// While reading aloud with the menu closed: state + play / pause, tap to open the 听书 panel.
    private var miniPlayer: some View {
        HStack(spacing: 10) {
            Image(systemName: "waveform").compatSymbolVariableColor(isActive: !speaker.paused)
                .font(.system(size: 15)).foregroundStyle(NovelLayout.accent)
            Text(speaker.paused ? "已暂停" : "朗读中").font(.system(size: 13, weight: .medium)).foregroundStyle(pal.ink)
            Button { speaker.toggle() } label: {
                Image(systemName: speaker.paused ? "play.fill" : "pause.fill").font(.system(size: 14)).foregroundStyle(pal.ink)
                    .frame(width: 36, height: 36).background(Circle().fill(fill)).contentShape(Circle().inset(by: -4))
            }
            .buttonStyle(PressScale()).accessibilityLabel(speaker.paused ? "播放" : "暂停").accessibilityIdentifier("novel.mini.toggle")
        }
        .padding(.leading, 16).padding(.trailing, 6).frame(height: 48)
        .contentShape(Capsule())
        .liquidGlassCapsule(tone: glassTone, interactive: true)
        .onTapGesture { showMenu(); open(.listen) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("novel.miniplayer")
    }

    private var listenPanel: some View {
        let current = voices.first { $0.identifier == speaker.voiceID } ?? voices.first { $0.language == "zh-CN" }
        return VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title(speaker.chapter)).dsFont(16, .semibold, relativeTo: .headline).foregroundStyle(pal.ink).lineLimit(1)
                    Text(!speaker.active ? "未开始" : speaker.paused ? "已暂停" : "正在朗读 · 读到的句子会高亮")
                        .font(.system(size: 12)).foregroundStyle(pal.secondary)
                        .accessibilityIdentifier("novel.listen.state")
                }
                Spacer()
                Button { speaker.stop(); open(nil) } label: { Text("退出听书").font(.system(size: 13, weight: .medium)).foregroundStyle(pal.secondary).padding(.horizontal, 12).frame(minHeight: 32).background(Capsule().fill(fill)).contentShape(Capsule().inset(by: -6)) }
                    .buttonStyle(PressScale())
                    .accessibilityIdentifier("novel.listen.stop")
            }
            HStack {
                listenControl("backward.end.fill", "上一章", 18) { listenChapter(-1) }
                listenControl("gobackward", "上一句", 20) { speaker.skip(-1) }
                Button { speaker.active ? speaker.toggle() : startListening() } label: {
                    Image(systemName: speaker.active && !speaker.paused ? "pause.fill" : "play.fill").font(.system(size: 23, weight: .semibold)).compatSymbolReplace()
                        .foregroundStyle(.white).frame(width: 62, height: 62).background(Circle().fill(NovelLayout.accent)).contentShape(Circle())
                }
                .buttonStyle(PressScale()).frame(maxWidth: .infinity)
                .accessibilityLabel(speaker.active && !speaker.paused ? "暂停" : "播放").accessibilityIdentifier("novel.listen.toggle")
                listenControl("goforward", "下一句", 20) { speaker.skip(+1) }
                listenControl("forward.end.fill", "下一章", 18) { listenChapter(+1) }
            }
            optionRow("语速") {
                ForEach([0.75, 1.0, 1.25, 1.5, 2.0], id: \.self) { v in
                    chip(v == 1 ? "1.0x" : "\(v.formatted())x", on: abs(speaker.speed - v) < 0.01) { speaker.speed = v }
                }
            }
            optionRow("定时") {
                chip("关闭", on: speaker.sleep == .off) { speaker.sleep = .off }
                ForEach([15, 30, 60], id: \.self) { m in chip("\(m)分钟", on: speaker.sleep == .minutes(m)) { speaker.sleep = .minutes(m) } }
                chip("本章结束", on: speaker.sleep == .chapterEnd) { speaker.sleep = .chapterEnd }
            }
            if let until = speaker.sleepUntil {
                Text("将于 \(until.formatted(date: .omitted, time: .shortened)) 停止").font(.system(size: 11)).foregroundStyle(pal.secondary)
            }
            HStack(spacing: 10) {
                Text("音色").font(.system(size: 13)).foregroundStyle(pal.secondary).frame(width: 34, alignment: .leading)
                Button { choosingVoice = true } label: {
                    HStack { Text(current.map(NovelSpeaker.label) ?? "系统默认").lineLimit(1); Spacer(); Image(systemName: "chevron.up.chevron.down").font(.system(size: 11)) }
                        .font(.system(size: 14)).foregroundStyle(pal.ink)
                        .padding(.horizontal, 14).frame(minHeight: 40).background(Capsule().fill(fill)).contentShape(Capsule())
                }
                .buttonStyle(PressScale(scale: 0.98)).accessibilityIdentifier("novel.listen.voice")
            }
            Text("想要更自然的声音：iOS 设置 → 辅助功能 → 朗读内容 → 声音 → 中文，下载「增强 / 高级」音色。锁屏或切到后台会继续朗读。")
                .font(.system(size: 11)).foregroundStyle(pal.secondary).lineSpacing(2)
        }
    }

    private func listenControl(_ icon: String, _ label: String, _ size: CGFloat, _ action: @escaping () -> Void) -> some View {
        let off = !speaker.active && label.hasSuffix("句")
        return Button(action: action) {
            Image(systemName: icon).font(.system(size: size, weight: .medium)).foregroundStyle(pal.ink).frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
        }
        .buttonStyle(PressScale()).disabled(off).opacity(off ? 0.35 : 1).accessibilityLabel(label)
    }

    private func optionRow<C: View>(_ name: String, @ViewBuilder _ content: () -> C) -> some View {
        HStack(spacing: 10) {
            Text(name).font(.system(size: 13)).foregroundStyle(pal.secondary).frame(width: 34, alignment: .leading)
            ScrollView(.horizontal, showsIndicators: false) { HStack(spacing: 8) { content() } }
                .compatScrollBounceBasedOnSize(axes: .horizontal)
        }
    }

    // MARK: Logic

    private var screen: UIScreen? {
        (UIApplication.shared.connectedScenes.first { $0 is UIWindowScene } as? UIWindowScene)?.screen
    }

    private func showMenu() { withAnimation(DS.motion(DS.panel, reduce: reduceMotion)) { menu = true; hint = false } }
    private func hideMenu() { DS.endEditing(); withAnimation(DS.motion(DS.panel, reduce: reduceMotion)) { menu = false; tab = nil; tocSearch = false } }

    private func restore() {
        let p = store.progress[book.id]
        let c = min(max(0, p?.chapter ?? 0), max(0, book.chapters.count - 1))
        if prefs.turn == .scroll || currentSource?.isImage == true {
            ref = NovelPageRef(chapter: c, index: 0)
            resetScrollWindow(around: c)
        } else {
            ref = paginator.ref(chapter: c, offset: p?.offset ?? 0)
            if paginator.hasCachedImages(c) { resetScrollWindow(around: c) }
        }
        ready = true
    }

    private func go(_ i: Int) {
        guard book.chapters.indices.contains(i) else { return }
        jump(chapter: i, offset: 0, keepMenu: true)
    }

    private func jump(chapter i: Int, offset: Int, keepMenu: Bool = false) {
        guard book.chapters.indices.contains(i) else { return }
        if !keepMenu { hideMenu() }
        if prefs.turn == .scroll || currentSource?.isImage == true {
            ref = NovelPageRef(chapter: i, index: 0)
            resetScrollWindow(around: i)
            if book.isWeb { loader.prefetch(book, around: i) }
        } else {
            ref = paginator.ref(chapter: i, offset: offset)
            if paginator.hasCachedImages(i) {
                resetScrollWindow(around: i)
            } else {
                version += 1
            }
        }
        save(force: true)
        if speaker.active { startListening() }
    }

    private func jump(toFraction f: Double) {
        let target = Int(f * Double(totalChars))
        let i = book.chapters.lastIndex { $0.start <= target } ?? 0
        jump(chapter: i, offset: target - book.chapters[i].start, keepMenu: true)
    }

    private func search() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { hits = []; return }
        let text = store.text(book), chapters = book.chapters
        searching = true
        Task {
            // Books are tens of MB: scanning one on the main thread froze the panel.
            let found = await Task.detached(priority: .userInitiated) { Self.find(q, in: text, chapters: chapters) }.value
            if query.trimmingCharacters(in: .whitespaces) == q { hits = found }
            searching = false
        }
    }

    private nonisolated static func find(_ q: String, in text: String, chapters: [NovelChapter]) -> [(chapter: Int, offset: Int, snippet: String)] {
        let ns = text as NSString
        var found: [(chapter: Int, offset: Int, snippet: String)] = []
        var r = NSRange(location: 0, length: ns.length)
        while found.count < 200 {
            let m = ns.range(of: q, options: [.caseInsensitive], range: r)
            if m.location == NSNotFound { break }
            let ci = chapters.lastIndex { $0.start <= m.location } ?? 0
            let s = max(0, m.location - 16)
            let snippet = ns.substring(with: NSRange(location: s, length: min(60, ns.length - s))).replacingOccurrences(of: "\n", with: " ")
            found.append((ci, m.location - (chapters.indices.contains(ci) ? chapters[ci].start : 0), snippet))
            r = NSRange(location: NSMaxRange(m), length: ns.length - NSMaxRange(m))
        }
        return found
    }

    /// 听书 from the top of the visible page; at a chapter's end continues with the next one.
    private func startListening() {
        speaker.nowPlaying = (book.title, title(ref.chapter))
        speaker.onChapterEnd = { ch in
            guard ch + 1 < book.chapters.count else { speaker.stop(); return }
            speaker.nowPlaying = (book.title, title(ch + 1))
            speaker.start(chapter: ch + 1, text: store.chapterText(book, ch + 1), from: 0)
        }
        speaker.start(chapter: ref.chapter, text: store.chapterText(book, ref.chapter), from: offsetInChapter)
    }

    private func listenChapter(_ d: Int) {
        let c = (speaker.active ? speaker.chapter : ref.chapter) + d
        guard book.chapters.indices.contains(c) else { return }
        jump(chapter: c, offset: 0, keepMenu: true)
        startListening()
    }

    /// Keeps the page on the sentence being read (turning with the current animation when it is the next page).
    private func follow(_ s: NSRange) {
        let target = isScrollMode ? NovelPageRef(chapter: speaker.chapter, index: 0) : paginator.ref(chapter: speaker.chapter, offset: s.location)
        guard target != ref else { return }
        if isScrollMode {
            ref = target // the scroll view follows the sentence itself (scrollReader)
            if !scrollWindow.contains(speaker.chapter) { resetScrollWindow(around: speaker.chapter) }
        } else if paginator.next(ref) == target, let c = handle.controller {
            c.turn(+1)
        } else {
            ref = target
        }
        save()
    }

    private func save(force: Bool = false) {
        let off = isScrollMode ? 0 : offsetInChapter
        if !force, lastSavedChapter == ref.chapter, abs(lastSavedOffset - off) < 200 { return }
        lastSavedChapter = ref.chapter
        lastSavedOffset = off
        store.record(book, chapter: ref.chapter, offset: off)
    }
}


/// WeRead's capsule slider: a full-height round thumb (optionally labelled) on a pill track. The thumb lifts while dragged.
struct PillSlider: View {
    @Binding var value: Double
    var track: Color
    var thumb: Color
    var ink: Color = .primary
    var label: String? = nil
    var leading: AnyView? = nil
    var trailing: AnyView? = nil
    var onEditing: (Bool) -> Void = { _ in }
    @State private var dragging = false

    var body: some View {
        GeometryReader { g in
            let h: CGFloat = g.size.height
            let d: CGFloat = h - 6
            let x: CGFloat = 3 + (g.size.width - d - 6) * CGFloat(value.clamped)
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                HStack {
                    leading.padding(.leading, 16)
                    Spacer()
                    trailing.padding(.trailing, 16)
                }
                Circle().fill(thumb.shadow(.drop(color: .black.opacity(0.16), radius: dragging ? 5 : 3, y: 1)))
                    .overlay { if let label { Text(label).font(.system(size: 13, weight: .medium)).foregroundStyle(ink).lineLimit(1).minimumScaleFactor(0.7) } }
                    .frame(width: d, height: d)
                    .scaleEffect(dragging ? 1.1 : 1)
                    .offset(x: x)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { v in
                    if !dragging { withAnimation(DS.press) { dragging = true }; onEditing(true) }
                    value = ((v.location.x - d / 2 - 3) / (g.size.width - d - 6)).clamped
            }.onEnded { _ in
                    withAnimation(DS.press) { dragging = false }
                    onEditing(false)
            })
        }
        .frame(height: 46)
        .accessibilityElement()
        .accessibilityValue("\(Int((value.clamped * 100).rounded()))%")
        .accessibilityAdjustableAction { dir in
            onEditing(true)
            value = (value + (dir == .increment ? 0.05 : -0.05)).clamped
            onEditing(false)
        }
    }
}

private extension Double {
    var clamped: Double { Swift.min(1, Swift.max(0, self)) }
}
private extension CGFloat {
    var clamped: Double { Swift.min(1, Swift.max(0, Double(self))) }
}
