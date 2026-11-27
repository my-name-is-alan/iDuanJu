import SwiftUI

/// 漫画 home: source switch, category chips, cover grid, search, 继续阅读 / 收藏.
struct MangaHomeView: View {
    var embedded = false
    @State private var source: MangaSourceID = .baozi
    @State private var category: MangaCategory?
    @State private var items: [MangaItem] = []
    @State private var page = 1
    @State private var hasMore = true
    @State private var loading = false
    @State private var error: String?
    @State private var query = ""
    @State private var searching: String?
    /// Refreshed on plugin / gate changes; walking every comic plugin used to happen several times per body.
    @State private var sources: [MangaSourceID] = []
    @Environment(\.layoutMetrics) private var layout

    private var provider: MangaProvider { MangaFixtures.provider ?? source.provider }
    /// Built-ins + enabled comic plugins; adult ones only behind the 成人漫画 switch.
    private static func visibleSources() -> [MangaSourceID] {
        MangaSourceID.allCases.filter { !$0.isAdult || SourceGate.shared.showAdultManga }
    }

    var body: some View {
        let list = ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if embedded { sourceRow }
                SearchField(text: $query, placeholder: "搜索\(source.label)", onSubmit: { searching = query.isEmpty ? nil : query; reload() },
                            onCancel: searching == nil ? nil : { query = ""; searching = nil; reload() })
                    .padding(.horizontal, 16)
                if searching == nil { categoryRow }
                if searching == nil { MangaContinueRow() }
                if let error { Text(error).dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.accentWarm).padding(.horizontal, 16) }
                LazyVGrid(columns: layout.posterGridItems(spacing: 10), spacing: 16) {
                    ForEach(items) { m in
                        NavigationLink(value: Route.manga(m)) { MangaCell(item: m) }.buttonStyle(PressScale(scale: 0.97))
                            .accessibilityIdentifier("manga.cell.\(m.mangaId)")
                            .onAppear { if hasMore, !loading, m.id == items.last?.id { Task { await load(more: true) } } }
                    }
                    if loading && items.isEmpty {
                        ForEach(0..<9, id: \.self) { _ in
                            VStack(alignment: .leading, spacing: 6) { ShimmerBlock(corner: 10).aspectRatio(3 / 4, contentMode: .fit); ShimmerBlock(corner: 5).frame(height: 12) }
                        }
                    }
                }
                .padding(.horizontal, 16)
                if loading && !items.isEmpty { DSLoading() }
                if !loading && items.isEmpty && error == nil { DSEmptyState("没有找到漫画", systemImage: "book.closed") }
                Text("来源：\(source.note)").dsFont(11, relativeTo: .caption2).foregroundStyle(STVTheme.tertiary).padding(.horizontal, 16)
            }
            .padding(.top, 4).padding(.bottom, 24)
            .compatScrollContent()
        }
        .scrollIndicators(.hidden)
        .refreshable { await load(more: false) }
        Group {
            if embedded { list.homePageInset() }
            else { list.liquidTopBar { VStack(spacing: 0) { MangaNavBar(); sourceRow.padding(.bottom, 8) } }.background(STVTheme.bg.ignoresSafeArea()).toolbar(.hidden, for: .navigationBar) }
        }
        .task(id: "\(source.rawValue)|\(category?.id ?? "")") { await load(more: false) }
        .onAppear { sources = Self.visibleSources() }
        .onReceive(PluginStore.shared.objectWillChange.receive(on: DispatchQueue.main)) { _ in refreshSources() }
        .onReceive(SourceGate.shared.objectWillChange.receive(on: DispatchQueue.main)) { _ in refreshSources() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("manga.home")
    }

    private struct MangaNavBar: View {
        @Environment(\.dismiss) private var dismiss
        var body: some View { DSNavBar(title: "漫画") { DSBackButton { dismiss() } } }
    }

    private func refreshSources() {
        let now = Self.visibleSources()
        if now != sources { sources = now }
        if !now.contains(source) { category = nil; source = .baozi }
    }

    private var sourceRow: some View {
        HStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(sources) { s in
                        GlassChip(title: s.label, selected: s == source) { if s != source { category = nil; items = []; source = s } }
                            .accessibilityIdentifier("manga.source.\(s.rawValue)")
                    }
                }
                .padding(.leading, 16)
            }
            .compatScrollBounceBasedOnSize(axes: .horizontal)
            NavigationLink(value: Route.mangaLibrary) { DSBarLabel(systemName: "books.vertical") }
                .buttonStyle(GlassPress())
                .padding(.trailing, 16)
                .accessibilityIdentifier("manga.library").accessibilityLabel("书架")
        }
    }

    private var categoryRow: some View {
        let cats = provider.categories
        return ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 8) {
                ForEach(cats) { c in GlassChip(title: c.title, selected: c.id == (category ?? cats.first)?.id) { category = c } }
            }.padding(.horizontal, 16)
        }
        .frame(height: 34)
        .compatScrollBounceBasedOnSize(axes: .horizontal)
    }

    private func reload() { Task { await load(more: false) } }

    private func load(more: Bool) async {
        if more && (loading || !hasMore) { return }
        loading = true; error = nil
        let p = more ? page + 1 : 1
        do {
            let r: MangaPage
            if let q = searching { r = try await provider.search(q, page: p) }
            else {
                guard let first = category ?? provider.categories.first else { loading = false; return }
                r = try await provider.list(first, page: p)
            }
            if more { let seen = Set(items.map(\.id)); items += r.items.filter { !seen.contains($0.id) } } else { items = r.items }
            page = p; hasMore = r.hasMore
        } catch is CancellationError {
        } catch { self.error = error.localizedDescription; if !more { items = [] } }
        loading = false
    }
}

/// Own view: reading progress is written on every page turn.
private struct MangaContinueRow: View {
    @Environment(\.zoomNS) private var ns
    @ObservedObject private var store = MangaStore.shared
    var body: some View {
        let recent = Array(store.recent.filter { !$0.item.source.isAdult || SourceGate.shared.showAdultManga }.prefix(10))
        if !recent.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                DSSectionHeader(title: "继续阅读")
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: 10) {
                        ForEach(recent, id: \.item.id) { p in
                            NavigationLink(value: Route.manga(p.item)) {
                                VStack(alignment: .leading, spacing: 4) {
                                    CoverView(url: p.item.cover, headers: p.item.source.provider.imageHeaders, corner: 10, maxPixelSize: CoverPixels.poster).frame(width: 84, height: 112)
                                        .zoomSource("manga:" + p.item.id, ns)
                                    Text(p.item.title).font(.caption.weight(.medium)).lineLimit(1).foregroundStyle(STVTheme.text)
                                    Text(p.chapterTitle).font(.caption2).lineLimit(1).foregroundStyle(STVTheme.secondary)
                                }
                                .frame(width: 84, alignment: .leading).contentShape(Rectangle())
                            }
                            .buttonStyle(PressScale(scale: 0.97))
                        }
                    }.padding(.horizontal, 16)
                }
                .compatScrollBounceBasedOnSize(axes: .horizontal)
            }
        }
    }
}

struct MangaCell: View {
    @Environment(\.zoomNS) private var ns
    let item: MangaItem
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Color.clear.aspectRatio(3 / 4, contentMode: .fit)
                .overlay { CoverView(url: item.cover, headers: item.source.provider.imageHeaders, corner: 12, maxPixelSize: CoverPixels.poster) }
                .zoomSource("manga:" + item.id, ns)
            Text(item.title).font(.system(size: 13, weight: .semibold)).foregroundStyle(STVTheme.text).lineLimit(1).padding(.top, 3)
            Text(item.tags.prefix(2).joined(separator: " · ").isEmpty ? item.author : item.tags.prefix(2).joined(separator: " · "))
                .font(.system(size: 11.5)).foregroundStyle(STVTheme.secondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/// 书架: favorites + offline chapters.
struct MangaLibraryView: View {
    @Environment(\.layoutMetrics) private var layout
    @ObservedObject private var store = MangaStore.shared
    @State private var deleting: MangaOffline?
    var body: some View {
        let favorites = store.favorites.filter { !$0.source.isAdult || SourceGate.shared.showAdultManga }
        let offline = store.offlineList.filter { !$0.item.source.isAdult || SourceGate.shared.showAdultManga }
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                if favorites.isEmpty && offline.isEmpty {
                    DSEmptyState("书架是空的", systemImage: "books.vertical", message: "收藏漫画或下载章节后会出现在这里。").padding(.top, 40)
                }
                if !favorites.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        DSSectionHeader(title: "收藏", note: "\(favorites.count)")
                        LazyVGrid(columns: layout.posterGridItems(spacing: 10), spacing: 16) {
                            ForEach(favorites) { m in NavigationLink(value: Route.manga(m)) { MangaCell(item: m) }.buttonStyle(PressScale(scale: 0.97)) }
                        }
                        .padding(.horizontal, 16)
                    }
                }
                if !offline.isEmpty {
                    DSSection("已下载章节（\(offline.count)）") {
                        ForEach(offline, id: \.chapter.id) { o in
                            DSRow(title: o.item.title, subtitle: o.chapter.title + " · " + (o.state == .done ? "\(o.files.count) 页" : o.state == .running ? "下载中 \(o.files.count)/\(o.total)" : (o.error ?? "失败"))) {
                                Button { deleting = o } label: {
                                    Image(systemName: "trash").font(.system(size: 15, weight: .medium)).foregroundStyle(DSTone.danger).frame(width: 40, height: 40).contentShape(Rectangle())
                                }
                                .buttonStyle(PressScale()).accessibilityLabel("删除 \(o.chapter.title)")
                            }
                        }
                    }
                }
            }
            .padding(.top, 8).padding(.bottom, 32)
        }
        .dsNavigation("书架")
        .dsConfirm("删除已下载的章节？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), message: deleting.map { "\($0.item.title) · \($0.chapter.title)" }, confirm: "删除") {
            if let o = deleting { store.deleteOffline(o.item, o.chapter) }
            deleting = nil
        }
    }
}

struct MangaDetailView: View {
    @Environment(\.zoomNS) private var ns
    let item: MangaItem
    @State private var detail: MangaDetail?
    @State private var error: String?
    @State private var ascending = true
    @State private var descOpen = false
    @State private var reader: ReaderTarget?
    @ObservedObject private var store = MangaStore.shared
    private var provider: MangaProvider { MangaFixtures.provider ?? item.source.provider }

    struct ReaderTarget: Identifiable { let chapters: [MangaChapter]; let index: Int; let page: Int; var id: Int { index } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                hero
                actions
                if let d = detail, !d.desc.isEmpty {
                    Text(d.desc).dsFont(14, relativeTo: .subheadline).foregroundStyle(STVTheme.secondary).lineSpacing(4).lineLimit(descOpen ? nil : 4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle()).onTapGesture { withAnimation(DS.panel) { descOpen.toggle() } }
                        .padding(.horizontal, 20)
                }
                if let error { Text(error).dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.accentWarm).padding(.horizontal, 20) }
                if let d = detail {
                    DSSectionHeader(title: "章节", note: "共 \(d.chapters.count) 话", inset: 20) { DSOrderToggle(ascending: $ascending) }
                    let list = ascending ? d.chapters : d.chapters.reversed()
                    LazyVStack(spacing: 0) {
                        ForEach(list) { c in
                            chapterRow(d, c)
                            Hairline()
                        }
                    }
                    .padding(.horizontal, 20)
                } else if error == nil {
                    DSLoading().padding(.top, 24)
                }
            }
            .padding(.bottom, 32)
        }
        .dsNavigation("")
        .zoomDestination("manga:" + item.id, ns)
        .task {
            do { detail = try await provider.detail(item) } catch { self.error = error.localizedDescription }
        }
        .fullScreenCover(item: $reader) { r in MangaReaderView(item: detail?.item ?? item, chapters: r.chapters, index: r.index, startPage: r.page) }
    }

    private var hero: some View {
        HStack(alignment: .bottom, spacing: 16) {
            CoverView(url: detail?.item.cover ?? item.cover, headers: provider.imageHeaders, corner: 14, maxPixelSize: CoverPixels.poster, fallback: item.cover).frame(width: 116, height: 155)
                .background { RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.black.shadow(.drop(color: .black.opacity(0.28), radius: 12, y: 8))).padding(1) }
            VStack(alignment: .leading, spacing: 7) {
                DSStatus(text: item.source.label, tone: .accent)
                Text(detail?.item.title ?? item.title).dsFont(22, .heavy, relativeTo: .title2).foregroundStyle(STVTheme.text).lineLimit(3)
                if let a = detail?.item.author ?? Optional(item.author), !a.isEmpty { Text(a).dsFont(14, relativeTo: .subheadline).foregroundStyle(STVTheme.secondary) }
                Text(([detail?.status ?? ""] + (detail?.item.tags ?? item.tags)).filter { !$0.isEmpty }.prefix(5).joined(separator: " · "))
                    .dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary).lineLimit(2)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20).padding(.top, 8)
    }

    private var actions: some View {
        LiquidGlassGroup(spacing: 12) {
            HStack(spacing: 12) {
                if let d = detail, !d.chapters.isEmpty { readButton(d) }
                let on = store.isFavorite(item)
                Button { withAnimation(DS.press) { store.toggleFavorite(detail?.item ?? item) } } label: {
                    Image(systemName: on ? "heart.fill" : "heart").font(.system(size: 19, weight: .semibold)).foregroundStyle(on ? STVTheme.favorite : STVTheme.text)
                        .compatSymbolReplace().frame(width: 50, height: 50)
                        .background {
                            if on {
                                Circle().fill(STVTheme.favoriteSoft).overlay(Circle().strokeBorder(STVTheme.favoriteLine, lineWidth: 1))
                            } else {
                                Circle().fill(Color.clear).liquidGlass(Circle(), interactive: true)
                            }
                        }
                        .contentShape(Circle())
                }
                .buttonStyle(GlassPress()).accessibilityLabel(on ? "取消收藏" : "收藏").accessibilityIdentifier("manga.favorite")
            }
        }
        .padding(.horizontal, 20)
    }

    @ViewBuilder private func readButton(_ d: MangaDetail) -> some View {
        let p = store.progress[item.id], resume = p.flatMap { p in d.chapters.firstIndex { $0.chapterId == p.chapterId } }
        Button { reader = ReaderTarget(chapters: d.chapters, index: resume ?? 0, page: resume == nil ? 0 : p?.page ?? 0) } label: {
            PillButtonLabel(title: resume == nil ? "开始阅读" : "续读 \((p?.chapterTitle ?? "").prefix(8))", icon: "book.fill", tone: .ink, height: 50, expand: true)
        }
        .buttonStyle(GlassPress()).accessibilityIdentifier("manga.read")
    }

    private func chapterRow(_ d: MangaDetail, _ c: MangaChapter) -> some View {
        let off = store.offlineState(d.item, c)
        let reading = store.progress[item.id]?.chapterId == c.chapterId
        return HStack(spacing: 8) {
            Button { reader = ReaderTarget(chapters: d.chapters, index: c.index, page: reading ? store.progress[item.id]!.page : 0) } label: {
                HStack(spacing: 8) {
                    if reading { Circle().fill(STVTheme.accent).frame(width: 6, height: 6) }
                    Text(c.title).dsFont(15, reading ? .semibold : .regular, relativeTo: .subheadline).foregroundStyle(reading ? STVTheme.accent : STVTheme.text).lineLimit(1)
                    Spacer(minLength: 0)
                }
                .frame(minHeight: 48).contentShape(Rectangle())
            }
            .buttonStyle(PressScale(scale: 0.99)).accessibilityIdentifier("manga.chapter.\(c.index)")
            Group {
                switch off?.state {
                case .done?: icon("checkmark.circle.fill", STVTheme.ok, "删除下载") { store.deleteOffline(d.item, c) }
                case .running?:
                    Text("\(off!.files.count)/\(max(off!.total, 1))").font(.caption2.monospacedDigit()).foregroundStyle(STVTheme.secondary).frame(minWidth: 44)
                case .failed?: icon("exclamationmark.arrow.circlepath", STVTheme.accentWarm, "重新下载") { store.download(d.item, c) }
                case nil: icon("arrow.down.circle", STVTheme.tertiary, "下载") { store.download(d.item, c) }
                }
            }
            .accessibilityIdentifier("manga.dl.\(c.index)")
        }
    }

    private func icon(_ name: String, _ tint: Color, _ label: String, _ f: @escaping () -> Void) -> some View {
        Button(action: f) { Image(systemName: name).font(.system(size: 18)).foregroundStyle(tint).frame(width: 44, height: 44).contentShape(Rectangle()) }
            .buttonStyle(PressScale()).accessibilityLabel(label)
    }
}

/// Reader: vertical webtoon scroll or horizontal paging, preloads the next pages, zoom, progress.
struct MangaReaderView: View {
    let item: MangaItem
    let chapters: [MangaChapter]
    @State var index: Int
    var startPage = 0
    /// Chapter `startPage` belongs to (the one the reader opened on); other chapters start at page 1.
    @State private var startChapter: Int?
    @AppStorage("stv.manga.vertical") private var vertical = true
    @State private var pages: [String] = []
    @State private var page = 0
    @State private var error: String?
    @State private var chrome = true
    @State private var zoomed = false
    @State private var scale: CGFloat = 1
    @State private var baseScale: CGFloat = 1
    @State private var visible: Set<Int> = []
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.layoutMetrics) private var layout
    /// Not observed: the reader writes progress here on every page, and nothing on screen reads it back.
    private var store: MangaStore { .shared }
    private var provider: MangaProvider { MangaFixtures.provider ?? item.source.provider }
    private var chapter: MangaChapter { chapters[index] }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if pages.isEmpty {
                if let error {
                    VStack(spacing: 14) {
                        Text(error).dsFont(14, relativeTo: .subheadline).foregroundStyle(.white.opacity(0.8)).multilineTextAlignment(.center).padding(.horizontal, 32)
                        Button { Task { await load() } } label: { PillButtonLabel(title: "重试", tone: .white, small: true) }.buttonStyle(PressScale())
                    }
                }
                else { DSLoading(tint: .white.opacity(0.8)) }
            } else if vertical { verticalReader } else { pagedReader }
            if chrome { overlay.transition(.opacity) }
        }
        .animation(DS.motion(DS.panel, reduce: reduceMotion), value: chrome)
        .statusBarHidden(!chrome)
        .task(id: index) { await load() }
        .compatOnChange(of: page) { p in
            store.record(item, chapter, page: p, of: pages.count)
            preload(from: p)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("manga.reader")
        .stvLocalColorScheme(.dark)
        .compatOnChange(of: vertical) { visible = [] }
    }

    private var verticalReader: some View {
        ScrollViewReader { proxy in
            // Zoom = wider content (pages fit width × scale), centered at comfortable webtoon width on tablets.
            GeometryReader { geo in
                let baseW = layout.mangaMaxWidth.map { min(geo.size.width, $0) } ?? geo.size.width
                ScrollView(scale > 1.01 ? [.vertical, .horizontal] : .vertical) {
                    LazyVStack(spacing: 0) {
                        ForEach(pages.indices, id: \.self) { i in
                            MangaPageImage(url: pages[i], headers: provider.imageHeaders)
                                .id(i)
                                // Current page = topmost page on screen.
                                .onAppear { visible.insert(i); page = visible.min() ?? i }
                                .onDisappear { visible.remove(i); if let m = visible.min() { page = m } }
                                .accessibilityIdentifier("manga.page.\(i)")
                        }
                        chapterEnd
                    }
                    .frame(width: baseW * scale)
                    .frame(minWidth: geo.size.width)
                }
            }
            .gesture(MagnificationGesture().onChanged { scale = max(1, min(3, baseScale * $0)) }.onEnded { _ in baseScale = scale })
            .onTapGesture(count: 2) { withAnimation(DS.panel) { scale = scale > 1 ? 1 : 2; baseScale = scale } }
            .onTapGesture { chrome.toggle() }
            .onAppear { let target = page; if target > 0 { proxy.scrollTo(target, anchor: .top) } }
        }
    }

    private var pagedReader: some View {
        GeometryReader { geo in
            TabView(selection: $page) {
                ForEach(pages.indices, id: \.self) { i in
                    ZoomableImage(url: pages[i], headers: provider.imageHeaders, zoomed: $zoomed).tag(i)
                        .accessibilityIdentifier("manga.page.\(i)")
                }
                chapterEnd.tag(pages.count)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            // Zoomed: drags pan the image instead of turning the page (zoom out, or double tap, to page again).
            .scrollDisabled(zoomed)
            .onTapGesture(coordinateSpace: .local) { p in pagedTap(p, width: geo.size.width) }
        }
    }

    /// Paged mode tap zones: left third = previous page, right third = next (past the last page → next
    /// chapter), middle = show / hide the bar. Zoomed pages leave taps to the image.
    private func pagedTap(_ p: CGPoint, width w: CGFloat) {
        guard !zoomed, w > 0 else { chrome.toggle(); return }
        let x = p.x / w
        if x < 1.0 / 3, page > 0 {
            withAnimation(.easeOut(duration: 0.22)) { page -= 1 }
        } else if x > 2.0 / 3 {
            if page < pages.count { withAnimation(.easeOut(duration: 0.22)) { page += 1 } }
            else if index + 1 < chapters.count { index += 1 }
        } else {
            chrome.toggle()
            return
        }
        DS.tick()
    }

    private var chapterEnd: some View {
        VStack(spacing: 16) {
            Text("本章完").dsFont(14, relativeTo: .subheadline).foregroundStyle(.white.opacity(0.7))
            if index + 1 < chapters.count {
                Button { index += 1 } label: { PillButtonLabel(title: "下一章：\(chapters[index + 1].title)", tone: .white, height: 46) }
                    .buttonStyle(PressScale()).padding(.horizontal, 40)
                    .accessibilityIdentifier("manga.nextChapter")
            } else { Text("已是最新一章").font(.caption).foregroundStyle(.white.opacity(0.5)) }
        }.frame(maxWidth: .infinity, minHeight: 240)
    }

    /// Floating glass: close + title + mode on top, chapter transport at the bottom.
    private var overlay: some View {
        VStack {
            LiquidGlassGroup(spacing: 10) {
                HStack(spacing: 10) {
                    LiquidGlassIconButton(systemName: "chevron.down", label: "关闭", size: 44, onMedia: true) { dismiss() }
                        .accessibilityIdentifier("manga.close")
                    VStack(alignment: .leading, spacing: 1) {
                        Text(item.title).font(.system(size: 14, weight: .semibold)).lineLimit(1)
                        Text(chapter.title).font(.system(size: 11)).lineLimit(1).opacity(0.75)
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16).frame(height: 44)
                    .liquidGlassCapsule(tone: .clear, onMedia: true)
                    Spacer(minLength: 0)
                    LiquidGlassIconButton(systemName: vertical ? "rectangle.split.1x2" : "rectangle.split.2x1", label: vertical ? "切换为左右翻页" : "切换为上下滚动", size: 44, onMedia: true) { DS.tick(); vertical.toggle() }
                        .accessibilityIdentifier("manga.mode")
                }
            }
            .padding(.horizontal, 14)
            Spacer()
            HStack(spacing: 4) {
                transport("backward.end.fill", "上一章", enabled: index > 0) { index -= 1 }
                Text(pages.isEmpty ? "" : "\(min(page, pages.count - 1) + 1) / \(pages.count)").font(.system(size: 14, weight: .medium).monospacedDigit())
                    .foregroundStyle(.white).frame(minWidth: 84)
                    .accessibilityIdentifier("manga.pageCounter")
                transport("forward.end.fill", "下一章", enabled: index + 1 < chapters.count) { index += 1 }
            }
            .padding(.horizontal, 8).frame(height: 52)
            .liquidGlassCapsule(tone: .clear, interactive: true, onMedia: true)
            .padding(.bottom, 10)
        }
    }

    private func transport(_ icon: String, _ label: String, enabled: Bool, _ f: @escaping () -> Void) -> some View {
        Button(action: f) { Image(systemName: icon).font(.system(size: 17, weight: .semibold)).foregroundStyle(.white).frame(width: 52, height: 48).contentShape(Rectangle()) }
            .buttonStyle(PressScale()).disabled(!enabled).opacity(enabled ? 1 : 0.35).accessibilityLabel(label)
    }

    private func load() async {
        pages = []; error = nil; visible = []
        let loaded: [String]
        if let local = store.localPages(item, chapter) { loaded = local }
        else {
            do { loaded = try await provider.pages(item, chapter) } catch { self.error = error.localizedDescription; return }
        }
        if startChapter == nil { startChapter = index }
        let initialPage = index == startChapter ? min(startPage, max(0, loaded.count - 1)) : 0
        page = initialPage
        pages = loaded
        store.record(item, chapter, page: initialPage, of: loaded.count)
        preload(from: initialPage)
    }

    /// Warm the image cache for the next few pages.
    private func preload(from p: Int) {
        let next = pages.dropFirst(p + 1).prefix(4)
        let h = provider.imageHeaders
        for u in next { Task { _ = try? await CoverStore.shared.image(url: u, headers: h) } }
    }
}

/// Full-width page that keeps the image's aspect ratio.
struct MangaPageImage: View {
    let url: String
    var headers: [String: String] = [:]
    @State private var image: UIImage?
    @State private var failed = false
    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().aspectRatio(image.size, contentMode: .fit) }
            else if failed {
                Button { failed = false; Task { await load() } } label: { Label("加载失败，点我重试", systemImage: "arrow.clockwise").font(.system(size: 14, weight: .medium)).foregroundStyle(.white.opacity(0.7)) }
                    .buttonStyle(PressScale())
                    .frame(maxWidth: .infinity, minHeight: 300)
            } else { DSSpinner(size: 26, tint: .white.opacity(0.8)).frame(maxWidth: .infinity, minHeight: 420) }
        }
        .task(id: url) { await load() }
    }
    private func load() async {
        do { image = try await CoverStore.shared.image(url: url, headers: headers) } catch { failed = true }
    }
}

/// DEBUG `STV_MANGA_FIXTURE=1`: an offline provider with solid-colour pages (UI tests / screenshots).
enum MangaFixtures {
    static var provider: MangaProvider? {
        #if DEBUG
        if ProcessInfo.processInfo.environment["STV_MANGA_FIXTURE"] == "1" { return FixtureMangaProvider() }
        #endif
        return nil
    }
}

#if DEBUG
struct FixtureMangaProvider: MangaProvider {
    var source: MangaSourceID { .baozi }
    var categories: [MangaCategory] { [MangaCategory(id: "all|all", title: "全部")] }
    var imageHeaders: [String: String] { [:] }
    static let colors = ["3D5A80", "98C1D9", "EE6C4D", "293241", "6A994E", "BC4749", "F2CC8F", "81B29A"]
    func list(_ c: MangaCategory, page: Int) async throws -> MangaPage {
        MangaPage(items: (0..<9).map { MangaItem(source: .baozi, mangaId: "fx\($0)", title: "示例漫画 \($0 + 1)", cover: "fixture://" + Self.colors[$0 % 8], tags: ["示例"]) }, hasMore: false)
    }
    func search(_ q: String, page: Int) async throws -> MangaPage { try await list(categories[0], page: 1) }
    func detail(_ m: MangaItem) async throws -> MangaDetail {
        MangaDetail(item: m, desc: "占位数据，用于界面测试。", status: "连载中", chapters: (0..<3).map { MangaChapter(chapterId: "0_\($0)", title: "第\($0 + 1)话", index: $0) })
    }
    func pages(_ m: MangaItem, _ c: MangaChapter) async throws -> [String] { (0..<5).map { "fixture://" + Self.colors[($0 + c.index) % 8] } }
}
#endif
