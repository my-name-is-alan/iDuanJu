import UIKit

/// A page of the book: chapter index + page index inside that chapter (for the current layout).
struct NovelPageRef: Hashable {
    var chapter: Int
    var index: Int
}

/// Turn styles (WeRead / 阅读): page curl, slide, cover, none — plus continuous vertical scrolling (SwiftUI).
enum NovelTurnStyle: Int, CaseIterable, Identifiable {
    case curl, slide, cover, none, scroll
    var id: Int { rawValue }
    var label: String {
        switch self {
        case .curl: "仿真"
        case .slide: "滑动"
        case .cover: "覆盖"
        case .none: "无动画"
        case .scroll: "上下滚动"
        }
    }
}

/// The sentence being read aloud (chapter-relative range).
struct NovelHighlight: Equatable { var chapter: Int; var range: NSRange }

/// Page text style + size; changing it invalidates every chapter's pagination.
struct NovelPageStyle: Equatable {
    var size: CGSize
    var fontSize: CGFloat
    var lineSpacing: Double
    var indent: Bool
    var fontID: String
    var text: UIColor
    var secondary: UIColor
    var background: UIColor
    var highlight: UIColor
}

/// Chapter text → (styled string, page ranges), cached per chapter for the current style.
@MainActor
final class NovelPaginator {
    let book: NovelBook
    private(set) var style: NovelPageStyle?
    private var cache: [Int: (text: NSAttributedString, pages: [NSRange], placeholder: Bool)] = [:]
    private var scrollCache: [Int: (text: NSAttributedString, placeholder: Bool)] = [:]
    private let store = NovelStore.shared

    init(book: NovelBook) { self.book = book }

    var chapterCount: Int { book.chapters.count }

    /// Returns true when the layout changed (callers re-render).
    @discardableResult func configure(_ s: NovelPageStyle) -> Bool {
        guard s != style else { return false }
        style = s
        cache = [:]
        scrollCache = [:]
        generation += 1
        return true
    }

    private var generation = 0
    private var warming: Set<Int> = []

    /// Lays a neighbouring chapter out in the background. Paginating a whole chapter on the main thread was the hitch at every
    /// chapter boundary.
    func warm(_ i: Int) {
        guard let s = style, book.chapters.indices.contains(i), cache[i]?.placeholder != false, !warming.contains(i) else { return }
        let title = book.chapters[i].title, raw = store.chapterText(book, i)
        guard !raw.isEmpty else { return }   // 书源 chapter still to be fetched: the loader announces it
        let font = NovelFonts.font(s.fontID, size: s.fontSize), bold = NovelFonts.font(s.fontID, size: s.fontSize * 1.3, bold: true)
        let token = generation
        warming.insert(i)
        Task { [weak self] in
            let out = await Task.detached(priority: .userInitiated) { () -> (NSAttributedString, [NSRange]) in
                let a = NovelLayout.attributed(raw, title: title, size: s.fontSize, lineSpacing: s.lineSpacing, indent: s.indent, font: font, bold: bold, color: s.text)
                return (a, NovelLayout.paginate(a, size: s.size))
            }.value
            guard let self else { return }
            self.warming.remove(i)
            if token == self.generation, self.cache[i]?.placeholder != false { self.cache[i] = (out.0, out.1, false) }
        }
    }

    /// Lightweight attributed text for scroll mode (never runs TextKit multi-page pagination or triggers network inside view body).
    func scrollChapter(_ i: Int, triggerLoad: Bool = false) -> NSAttributedString {
        if let c = scrollCache[i], (!c.placeholder || !store.isCached(book, i)) { return c.text }
        if let c = cache[i], (!c.placeholder || !store.isCached(book, i)) { return c.text }
        guard let s = style, book.chapters.indices.contains(i) else { return NSAttributedString() }
        let title = book.chapters[i].title
        var raw = store.chapterText(book, i)
        var isPlaceholder = false
        if book.isWeb && raw.isEmpty {
            isPlaceholder = true
            let loader = NovelWebLoader.shared
            if triggerLoad { loader.ensure(book, i) }
            raw = title + "\n" + (loader.error(book, i).map { "加载失败：\($0)" } ?? "正在加载…")
        }
        let a = NovelLayout.attributed(raw, title: title, size: s.fontSize, lineSpacing: s.lineSpacing,
            indent: s.indent, font: NovelFonts.font(s.fontID, size: s.fontSize), bold: NovelFonts.font(s.fontID, size: s.fontSize * 1.3, bold: true), color: s.text)
        if scrollCache.count > 16 { scrollCache = scrollCache.filter { abs($0.key - i) <= 6 } }
        scrollCache[i] = (a, isPlaceholder)
        return a
    }

    /// Side-effect-free check for inline `<img>` tags (safe inside SwiftUI `body`; never paginates or triggers network).
    func hasCachedImages(_ i: Int) -> Bool {
        guard book.isWeb, book.chapters.indices.contains(i) else { return false }
        if let c = scrollCache[i], !c.placeholder { return c.text.string.range(of: "<img", options: .caseInsensitive) != nil }
        if let c = cache[i], !c.placeholder { return c.text.string.range(of: "<img", options: .caseInsensitive) != nil }
        guard store.isCached(book, i) else { return false }
        return store.chapterText(book, i).range(of: "<img", options: .caseInsensitive) != nil
    }

    func chapter(_ i: Int) -> (text: NSAttributedString, pages: [NSRange]) {
        if let c = cache[i], (!c.placeholder || !store.isCached(book, i)) { return (c.text, c.pages) }
        guard let s = style, book.chapters.indices.contains(i) else { return (NSAttributedString(), [NSRange(location: 0, length: 0)]) }
        let title = book.chapters[i].title
        var raw = store.chapterText(book, i)
        var isPlaceholder = false
        if book.isWeb && raw.isEmpty {
            isPlaceholder = true
            // 书源 chapter not downloaded yet: placeholder page, fetched in the background (reader re-paginates on arrival).
            let loader = NovelWebLoader.shared
            loader.ensure(book, i)
            raw = title + "\n" + (loader.error(book, i).map { "加载失败：\($0)" } ?? "正在加载…")
        }
        let a = NovelLayout.attributed(raw, title: title, size: s.fontSize, lineSpacing: s.lineSpacing,
            indent: s.indent, font: NovelFonts.font(s.fontID, size: s.fontSize), bold: NovelFonts.font(s.fontID, size: s.fontSize * 1.3, bold: true), color: s.text)
        let p = NovelLayout.paginate(a, size: s.size)
        if cache.count > 6 { cache = cache.filter { abs($0.key - i) <= 2 } }   // keep neighbours only
        cache[i] = (a, p, isPlaceholder)
        return (a, p)
    }

    /// Drops cached layouts (either a single arrived 书源 chapter or all chapters).
    func invalidate(chapter i: Int? = nil) {
        if let i {
            cache.removeValue(forKey: i)
            scrollCache.removeValue(forKey: i)
            warming.remove(i)
        } else {
            cache = [:]
            scrollCache = [:]
            warming.removeAll()
            generation += 1
        }
    }

    func pageCount(_ chapter: Int) -> Int { self.chapter(chapter).pages.count }

    func range(_ r: NovelPageRef) -> NSRange {
        let p = chapter(r.chapter).pages
        return p.indices.contains(r.index) ? p[r.index] : NSRange(location: 0, length: 0)
    }

    func next(_ r: NovelPageRef) -> NovelPageRef? {
        if r.index + 1 < pageCount(r.chapter) { return NovelPageRef(chapter: r.chapter, index: r.index + 1) }
        return r.chapter + 1 < chapterCount ? NovelPageRef(chapter: r.chapter + 1, index: 0) : nil
    }

    func prev(_ r: NovelPageRef) -> NovelPageRef? {
        if r.index > 0 { return NovelPageRef(chapter: r.chapter, index: r.index - 1) }
        return r.chapter > 0 ? NovelPageRef(chapter: r.chapter - 1, index: pageCount(r.chapter - 1) - 1) : nil
    }

    /// Page containing chapter offset `o` (Int.max → last page).
    func ref(chapter: Int, offset o: Int) -> NovelPageRef {
        let p = self.chapter(chapter).pages
        let i = o == .max ? p.count - 1 : (p.lastIndex { $0.location <= o } ?? 0)
        return NovelPageRef(chapter: chapter, index: max(0, i))
    }

    /// Whole-book page numbers estimated from this chapter's density (WeRead shows "19649 / 25070").
    func bookPageLabel(_ r: NovelPageRef) -> String {
        let c = chapter(r.chapter)
        let per = max(1, Double(c.text.length) / Double(max(1, c.pages.count)))
        let start = book.chapters.indices.contains(r.chapter) ? book.chapters[r.chapter].start : 0
        let total = book.chapters.last?.end ?? 0
        let off = c.pages.indices.contains(r.index) ? c.pages[r.index].location : 0
        return "\(Int(Double(start + off) / per) + 1) / \(Int(Double(total) / per) + 1)"
    }
}

// MARK: - Page view

/// One rendered page: chapter name on top, the text slice, whole-book page number bottom-right.
final class NovelPageView: UIView {
    let ref: NovelPageRef
    private let header = UILabel()
    private let footer = UILabel()
    // Force TextKit 1 (`NSLayoutManager`) to match `NovelLayout.paginate` pixel-for-pixel;
    // iOS 16+'s default TextKit 2 computes taller CJK line heights and clips 1-2 lines at the bottom.
    private let textView = UITextView(usingTextLayoutManager: false)
    private var insets: UIEdgeInsets = .zero

    init(ref: NovelPageRef, paginator: NovelPaginator, insets: UIEdgeInsets, highlight: NovelHighlight?) {
        self.ref = ref
        self.insets = insets
        super.init(frame: .zero)
        let s = paginator.style ?? NovelPageStyle(
            size: CGSize(width: 320, height: 500), fontSize: 18, lineSpacing: 0.5, indent: true, fontID: "system",
            text: UIColor(white: 0.12, alpha: 1), secondary: UIColor(white: 0.56, alpha: 1),
            background: .white, highlight: UIColor.systemGreen.withAlphaComponent(0.22)
        )
        backgroundColor = s.background
        header.font = .systemFont(ofSize: 12)
        header.textColor = s.secondary
        header.text = paginator.book.chapters.indices.contains(ref.chapter) ? paginator.book.chapters[ref.chapter].title : ""
        footer.font = .monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        footer.textColor = s.secondary
        footer.textAlignment = .right
        footer.text = paginator.bookPageLabel(ref)
        footer.accessibilityIdentifier = "novel.page"
        let chapter = paginator.chapter(ref.chapter)
        footer.accessibilityLabel = "\(ref.index + 1) / \(chapter.pages.count)"
        textView.isEditable = false
        textView.isSelectable = false
        textView.isScrollEnabled = false
        textView.backgroundColor = .clear
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.isUserInteractionEnabled = false
        let r = paginator.range(ref)
        if r.location >= 0, NSMaxRange(r) <= chapter.text.length {
            let t = NSMutableAttributedString(attributedString: chapter.text.attributedSubstring(from: r))
            // A page that starts mid-paragraph continues a line: no first-line indent (TextKit laid it out that way).
            if t.length > 0, r.location > 0, (chapter.text.string as NSString).character(at: r.location - 1) != 10,
            let ps = t.attribute(.paragraphStyle, at: 0, effectiveRange: nil) as? NSParagraphStyle, ps.firstLineHeadIndent > 0 {
                let m = ps.mutableCopy() as! NSMutableParagraphStyle
                m.firstLineHeadIndent = 0
                let first = (t.string as NSString).paragraphRange(for: NSRange(location: 0, length: 0))
                t.addAttribute(.paragraphStyle, value: m, range: first)
            }
            if let h = highlight?.range, highlight?.chapter == ref.chapter {
                let x = NSIntersectionRange(h, r)
                if x.length > 0 { t.addAttribute(.backgroundColor, value: s.highlight, range: NSRange(location: x.location - r.location, length: x.length)) }
            }
            textView.attributedText = t
            // Nonvisual body readiness for VoiceOver and native QA; footer existence also matches loading placeholders.
            textView.isAccessibilityElement = true
            textView.accessibilityIdentifier = "novel.page.body"
            textView.accessibilityValue = t.string
        }
        [header, textView, footer].forEach(addSubview)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = max(1, bounds.width - insets.left - insets.right)
        let h = max(1, bounds.height - insets.top - insets.bottom - 62)
        header.frame = CGRect(x: insets.left, y: insets.top, width: w, height: 22)
        textView.frame = CGRect(x: insets.left, y: insets.top + 32, width: w, height: h)
        footer.frame = CGRect(x: insets.left, y: bounds.height - insets.bottom - 30, width: w, height: 30)
    }
}

final class NovelPageVC: UIViewController {
    let page: NovelPageView
    init(_ page: NovelPageView) { self.page = page; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }
    override func loadView() { view = page }
}

// MARK: - Pager

/// Hosts the paged turn styles. 仿真 / 滑动 use UIPageViewController (interactive, follows the finger);
/// 覆盖 drags the top page over / off the next one; 无动画 swaps instantly. Taps: left / right third turn,
/// middle → `onCenterTap`; swipe up → `onSwipeUp`.
@MainActor
final class NovelPagerController: UIViewController, UIPageViewControllerDataSource, UIPageViewControllerDelegate, UIGestureRecognizerDelegate {
    let paginator: NovelPaginator
    var insets: UIEdgeInsets = .zero
    var onPageChange: ((NovelPageRef) -> Void)?
    var onCenterTap: (() -> Void)?
    var onSwipeUp: (() -> Void)?
    var highlight: NovelHighlight? { didSet { if highlight != oldValue { refreshCurrent() } } }
    private(set) var current: NovelPageRef
    private(set) var style: NovelTurnStyle = .slide

    private var pvc: UIPageViewController?
    // 覆盖 / 无动画
    private var top: NovelPageView?
    private var under: NovelPageView?
    private var dragging: (forward: Bool, target: NovelPageRef)?
    private var coverToken = 0

    init(paginator: NovelPaginator, start: NovelPageRef) {
        self.paginator = paginator
        current = start
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.clipsToBounds = true
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        tap.delegate = self
        view.addGestureRecognizer(tap)
        let up = UISwipeGestureRecognizer(target: self, action: #selector(swipedUp))
        up.direction = .up
        up.delegate = self
        view.addGestureRecognizer(up)
        let pan = UIPanGestureRecognizer(target: self, action: #selector(panned(_:)))
        pan.delegate = self
        view.addGestureRecognizer(pan)
        build()
        warmNeighbours()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard view.bounds.width > 20, view.bounds.height > 80, let s = paginator.style else { return }
        // Keep pagination locked to the exact textView frame size so safe-area or container differences can never clip bottom lines.
        let actualW = max(1, view.bounds.width - insets.left - insets.right)
        let actualH = max(1, view.bounds.height - insets.top - insets.bottom - 62)
        if abs(actualW - s.size.width) > 1 || abs(actualH - s.size.height) > 1 {
            let off = paginator.range(current).location
            let updated = NovelPageStyle(size: CGSize(width: actualW, height: actualH),
                fontSize: s.fontSize, lineSpacing: s.lineSpacing, indent: s.indent, fontID: s.fontID,
                text: s.text, secondary: s.secondary, background: s.background, highlight: s.highlight)
            if paginator.configure(updated) {
                current = paginator.ref(chapter: current.chapter, offset: off)
                show(current, forceRebuild: false)
                let settled = current
                DispatchQueue.main.async { [weak self] in self?.onPageChange?(settled) }
            }
        } else {
            if let p = pvc, p.view.frame != view.bounds {
                UIView.performWithoutAnimation {
                    p.view.frame = view.bounds
                    p.view.layoutIfNeeded()
                }
            }
            if dragging == nil, let t = top, t.frame != view.bounds {
                t.frame = view.bounds
            }
        }
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        if let p = g as? UIPanGestureRecognizer, !(g is UIScreenEdgePanGestureRecognizer) {
            // Own pan only drives 覆盖 / 无动画, and only horizontally.
            guard pvc == nil else { return false }
            let v = p.velocity(in: view)
            return abs(v.x) > abs(v.y)
        }
        return true
    }

    func setStyle(_ s: NovelTurnStyle) {
        guard s != style else { return }
        style = s
        if isViewLoaded { build() }
    }

    private func makePage(_ r: NovelPageRef) -> NovelPageView {
        NovelPageView(ref: r, paginator: paginator, insets: insets, highlight: highlight)
    }

    /// Rebuilds the visible page(s), e.g. after a style or layout change.
    /// Always runs without animation so SwiftUI menu-dismiss springs never corrupt UIPageViewController layout/touch state.
    func build() {
        coverToken += 1
        dragging = nil
        UIView.performWithoutAnimation {
            pvc?.dataSource = nil
            pvc?.delegate = nil
            pvc?.willMove(toParent: nil); pvc?.view.removeFromSuperview(); pvc?.removeFromParent(); pvc = nil
            top?.removeFromSuperview(); under?.removeFromSuperview(); top = nil; under = nil
            switch style {
            case .curl, .slide:
                let p = UIPageViewController(transitionStyle: style == .curl ? .pageCurl : .scroll, navigationOrientation: .horizontal,
                    options: style == .slide ? [.interPageSpacing: 0] : nil)
                p.dataSource = self
                p.delegate = self
                p.isDoubleSided = false
                // UIPageViewController's own edge taps would fight with ours.
                p.gestureRecognizers.filter { $0 is UITapGestureRecognizer }.forEach { $0.isEnabled = false }
                addChild(p)
                p.view.frame = view.bounds
                p.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                view.insertSubview(p.view, at: 0)
                p.didMove(toParent: self)
                p.setViewControllers([NovelPageVC(makePage(current))], direction: .forward, animated: false)
                p.view.layoutIfNeeded()
                pvc = p
            case .cover, .none, .scroll:
                let t = makePage(current)
                t.frame = view.bounds
                t.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                view.insertSubview(t, at: 0)
                t.layoutIfNeeded()
                top = t
            }
        }
    }

    func show(_ r: NovelPageRef, forceRebuild: Bool = false) {
        current = r
        coverToken += 1
        dragging = nil
        guard isViewLoaded else { return }
        if !forceRebuild, (style == .curl || style == .slide), let p = pvc,
           p.transitionStyle == (style == .curl ? .pageCurl : .scroll) {
            UIView.performWithoutAnimation {
                p.view.frame = view.bounds
                // Resetting dataSource flushes UIPageViewController's cached prev/next pages without tearing down the VC.
                p.dataSource = nil
                p.dataSource = self
                p.setViewControllers([NovelPageVC(makePage(current))], direction: .forward, animated: false)
                p.view.layoutIfNeeded()
            }
            warmNeighbours()
            return
        }
        if !forceRebuild, (style == .cover || style == .none), pvc == nil {
            UIView.performWithoutAnimation {
                under?.removeFromSuperview(); under = nil
                top?.removeFromSuperview()
                let t = makePage(current)
                t.frame = view.bounds
                t.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                view.insertSubview(t, at: 0)
                t.layoutIfNeeded()
                top = t
            }
            warmNeighbours()
            return
        }
        build()
        warmNeighbours()
    }

    /// Flushes cached adjacent pages when a neighbouring web chapter arrives without disturbing the active page.
    func reloadNeighbours() {
        guard isViewLoaded, dragging == nil else { return }
        if let p = pvc {
            UIView.performWithoutAnimation {
                p.dataSource = nil
                p.dataSource = self
            }
        }
        warmNeighbours()
    }

    private func refreshCurrent() {
        guard isViewLoaded else { return }
        UIView.performWithoutAnimation {
            if let p = pvc {
                p.setViewControllers([NovelPageVC(makePage(current))], direction: .forward, animated: false)
            } else if let t = top {
                let n = makePage(current); n.frame = t.frame; n.autoresizingMask = t.autoresizingMask
                view.insertSubview(n, aboveSubview: t); t.removeFromSuperview(); top = n
            }
        }
    }

    /// Animated turn in the current style (taps, 自动翻页, 听书).
    func turn(_ d: Int) {
        guard dragging == nil, let target = d > 0 ? paginator.next(current) : paginator.prev(current) else { return }
        switch style {
        case .curl, .slide:
            guard let p = pvc else { return }
            p.setViewControllers([NovelPageVC(makePage(target))], direction: d > 0 ? .forward : .reverse, animated: true) { [weak self] _ in
                self?.settle(target)
            }
        case .cover:
            beginCover(forward: d > 0, target: target)
            finishCover(commit: true, velocity: 0)
        case .none, .scroll:
            show(target)
            onPageChange?(target)
            warmNeighbours()
        }
    }

    private func settle(_ r: NovelPageRef) {
        current = r
        onPageChange?(r)
        warmNeighbours()
    }

    private func warmNeighbours() {
        paginator.warm(current.chapter + 1)
        paginator.warm(current.chapter - 1)
    }

    // MARK: Taps

    @objc private func tapped(_ g: UITapGestureRecognizer) {
        let x = g.location(in: view).x, w = view.bounds.width
        if x < w / 3 { turn(-1) } else if x > w * 2 / 3 { turn(+1) } else { onCenterTap?() }
    }

    @objc private func swipedUp() { onSwipeUp?() }

    // MARK: UIPageViewController

    func pageViewController(_ p: UIPageViewController, viewControllerBefore vc: UIViewController) -> UIViewController? {
        guard let r = (vc as? NovelPageVC)?.page.ref, let t = paginator.prev(r) else { return nil }
        return NovelPageVC(makePage(t))
    }

    func pageViewController(_ p: UIPageViewController, viewControllerAfter vc: UIViewController) -> UIViewController? {
        guard let r = (vc as? NovelPageVC)?.page.ref, let t = paginator.next(r) else { return nil }
        return NovelPageVC(makePage(t))
    }

    func pageViewController(_ p: UIPageViewController, didFinishAnimating finished: Bool, previousViewControllers: [UIViewController], transitionCompleted completed: Bool) {
        guard completed, let r = (p.viewControllers?.first as? NovelPageVC)?.page.ref else { return }
        settle(r)
    }

    // MARK: 覆盖 / 无动画 (pan)

    @objc private func panned(_ g: UIPanGestureRecognizer) {
        let tx = g.translation(in: view).x
        switch g.state {
        case .began:
            let forward = g.velocity(in: view).x < 0
            guard let target = forward ? paginator.next(current) : paginator.prev(current) else { return }
            if style == .none { dragging = (forward, target); return }
            beginCover(forward: forward, target: target)
        case .changed:
            guard let d = dragging, style == .cover else { return }
            let w = view.bounds.width
            if d.forward { top?.frame.origin.x = min(0, tx) } else { under?.frame.origin.x = min(0, -w + max(0, tx)) }
        case .ended, .cancelled:
            guard let d = dragging else { return }
            let v = g.velocity(in: view).x
            let commit = g.state == .ended && (d.forward ? (tx < -40 || v < -300) : (tx > 40 || v > 300))
            if style == .none {
                dragging = nil
                if commit { show(d.target); onPageChange?(d.target) }
            } else {
                finishCover(commit: commit, velocity: v)
            }
        default: break
        }
    }

    /// Forward: the current page lies on top and slides off to the left, revealing the next one underneath.
    /// Backward: the previous page enters from the left on top of the current one.
    private func beginCover(forward: Bool, target: NovelPageRef) {
        guard let t = top else { return }
        let w = view.bounds.width
        let n = makePage(target)
        n.frame = view.bounds
        n.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        if forward {
            view.insertSubview(n, belowSubview: t)
            shadow(t)
        } else {
            n.frame.origin.x = -w
            view.insertSubview(n, aboveSubview: t)
            shadow(n)
        }
        under = n
        dragging = (forward, target)
    }

    private func finishCover(commit: Bool, velocity: CGFloat) {
        guard let d = dragging, let t = top, let n = under else { dragging = nil; return }
        let token = coverToken
        let w = view.bounds.width
        UIView.animate(withDuration: 0.28, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
            if d.forward { t.frame.origin.x = commit ? -w : 0 } else { n.frame.origin.x = commit ? 0 : -w }
        } completion: { [weak self] _ in
            guard let self, self.coverToken == token else { return }
            if commit {
                t.removeFromSuperview()
                n.layer.shadowOpacity = 0
                n.frame = self.view.bounds
                self.top = n
                self.settle(d.target)
            } else {
                n.removeFromSuperview()
                t.layer.shadowOpacity = 0
            }
            self.under = nil
            self.dragging = nil
        }
    }

    private func shadow(_ v: UIView) {
        v.layer.shadowColor = UIColor.black.cgColor
        v.layer.shadowOpacity = 0.25
        v.layer.shadowRadius = 8
        v.layer.shadowOffset = CGSize(width: 2, height: 0)
    }
}
