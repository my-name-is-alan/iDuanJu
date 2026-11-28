import Combine
import SwiftUI

enum HomeTab: Hashable {
    case hongguo(HGRankGenre)
    case actorRank
    case huangguo(HuangguoSub)
    /// Opt-in long-video site (片源管理), listed after the built-in tabs.
    case site(DramaSource)
    var label: String {
        switch self {
        case .actorRank: "剧星榜"
        case .hongguo(let g): g == .all ? "红果榜单" : g.label
        case .huangguo(let s): "黄果" + s.label
        case .site(let s): s.label
        }
    }
    /// Label inside a source page's secondary chips.
    var subLabel: String {
        switch self {
        case .hongguo(.all): "全部榜单"
        case .hongguo(.ai): "红果 AI剧"
        case .huangguo(.ai): "黄果 AI 站"
        case .huangguo(let s): s.label
        default: label
        }
    }
}

/// Top-level home pages (横滑翻页 + 顶部分类条): one page per source; each source's own boards
/// (红果榜单 / 真人剧 / …) are secondary chips inside its page. Adult sites only appear once enabled in
/// 片源管理 (and unlocked with Face ID when the lock is on) — `SourceGate.visibleSites`.
enum HomeSource: Hashable, Identifiable {
    case hongguo, huangguo, ai
    case site(DramaSource)
    /// Telegram 频道 feed (not a drama source; its page is `ChannelFeedView`).
    case channels
    /// 漫画 (MangaHomeView).
    case manga
    /// 小说 (NovelHomeView: local TXT / EPUB bookshelf).
    case novel
    /// Stable unique identity (never collides even when a plugin shares a display name with a built-in source).
    var key: String {
        switch self {
        case .hongguo: "builtin:hongguo"
        case .huangguo: "builtin:huangguo"
        case .ai: "builtin:ai"
        case .site(let s): "site:" + s.rawValue
        case .channels: "builtin:channels"
        case .manga: "builtin:manga"
        case .novel: "builtin:novel"
        }
    }
    var id: String { key }
    var label: String {
        switch self {
        case .hongguo: "红果"
        case .huangguo: "黄果"
        case .ai: "AI剧"
        case .site(let s): s.label
        case .channels: "频道"
        case .manga: "漫画"
        case .novel: "小说"
        }
    }
    @MainActor var subs: [HomeTab] {
        switch self {
        case .hongguo: [.hongguo(.all), .hongguo(.human), .hongguo(.comic), .actorRank]
        case .huangguo: [.huangguo(.video), .huangguo(.legacy)]
        case .ai: SourceGate.shared.showHuangguo ? [.hongguo(.ai), .huangguo(.ai)] : [.hongguo(.ai)]
        case .site(let s): [.site(s)]
        case .channels, .manga, .novel: []
        }
    }
    var isImported: Bool {
        if case .site(let s) = self { return s.isDynamic }
        return false
    }
    var categoryTag: String {
        switch self {
        case .hongguo, .huangguo, .ai: return "内置"
        case .channels, .manga, .novel: return "功能"
        case .site(let s):
            if s.pluginID != nil { return "插件" }
            if s.cmsKey != nil { return "采集站" }
            return "内置"
        }
    }
    var canHideFromHome: Bool { self != .hongguo }
    @MainActor static var defaultAll: [HomeSource] {
        let g = SourceGate.shared
        let nonTVBoxSites = g.visibleSites.filter { src in
            if let k = src.cmsKey, let s = CMSStore.site(k), s.origin == "TVBox" || s.origin.hasPrefix("TVBox") { return false }
            return true
        }
        var list: [HomeSource] = [.hongguo]
            + (g.showHuangguo ? [.huangguo] : [])
            + [.ai]
            + nonTVBoxSites.map { .site($0) }
        if g.showChannelsTab { list.append(.channels) }
        if g.showMangaTab { list.append(.manga) }
        if g.showNovelTab { list.append(.novel) }
        return list
    }
    /// Every enabled source/feature tab eligible for Home, including ones folded off the top strip.
    @MainActor static var allManageable: [HomeSource] {
        let raw = defaultAll
        let byKey = Dictionary(uniqueKeysWithValues: raw.map { ($0.key, $0) })
        return SourceGate.shared.orderedKeys(raw.map(\.key)).compactMap { byKey[$0] }
    }
    /// Active tabs actually pinned on the Home top bar & horizontal pager.
    @MainActor static var all: [HomeSource] {
        let g = SourceGate.shared
        let visible = allManageable.filter { !g.isHiddenOnHome($0.key) }
        return visible.isEmpty ? [.hongguo] : visible
    }
}

/// First page of one rank board. Each shelf observes only its own board, so a board arriving re-renders one shelf instead of
/// every section of every home page.
@MainActor
final class BoardFeed: ObservableObject {
    enum State {
        case idle, loaded([HGSeries]), failed(Error)
    }
    let board: BoardSpec
    @Published private(set) var state: State = .idle
    private var loading = false

    init(_ b: BoardSpec) { board = b }

    var items: [HGSeries] { if case .loaded(let i) = state { return i }; return [] }
    var error: Error? { if case .failed(let e) = state { return e }; return nil }

    func load(force: Bool = false) {
        guard !loading else { return }
        if case .loaded = state, !force { return }
        loading = true
        Task {
            defer { loading = false }
            do { state = .loaded(try await board.fetch(HGPage(items: [], hasMore: true, nextOffset: nil), []).items) }
            catch {
                // A failed refresh keeps what is on screen.
                if items.isEmpty { state = .failed(error) }
            }
        }
    }
}

/// Keeps the board feeds alive across page switches and re-entries.
@MainActor
final class BoardCache {
    static let shared = BoardCache()
    private var feeds: [String: BoardFeed] = [:]
    func feed(_ b: BoardSpec) -> BoardFeed {
        if let f = feeds[b.id] { return f }
        let f = BoardFeed(b)
        feeds[b.id] = f
        return f
    }
}

// MARK: - Floating header plumbing

/// Vertical extent of a view in window coordinates, and the bottom safe area under it.
struct VSpan: Equatable {
    var top: CGFloat
    var bottom: CGFloat
    var inset: CGFloat = 0
}

/// Reports where a view really is. Frames scaled by a running navigation transition are skipped.
private struct SpanReader: View {
    @Binding var span: VSpan?
    var body: some View {
        GeometryReader { g in
            let f = g.frame(in: .global)
            let now = abs(f.width - g.size.width) < 0.5 ? VSpan(top: f.minY, bottom: f.maxY, inset: g.safeAreaInsets.bottom) : nil
            Color.clear.onAppear { set(now) }.compatOnChange(of: now) { set($0) }
        }
    }
    private func set(_ now: VSpan?) {
        guard let now else { return }
        if let s = span, abs(s.top - now.top) < 0.5, abs(s.bottom - now.bottom) < 0.5, abs(s.inset - now.inset) < 0.5 { return }
        span = now
    }
}

/// What the pages under the floating home header need: where their content rests and where to report "scrolled".
struct HomeHeader {
    /// False outside 发现, where the same pages have a bar of their own.
    var active = false
    /// From the header's lower edge to the floating tab bar, in window coordinates; nil until the first layout.
    var rest: VSpan?
    /// Where the pager starts, and how far it reaches under the tab bar.
    var pagerTop: CGFloat = 0
    var tabBar: CGFloat = 0
    var onScrolled: (Bool) -> Void = { _ in }
}

private struct HomeHeaderKey: EnvironmentKey { static let defaultValue = HomeHeader() }
extension EnvironmentValues {
    var homeHeader: HomeHeader {
        get { self[HomeHeaderKey.self] }
        set { self[HomeHeaderKey.self] = newValue }
    }
}

/// Insets from geometry, not from assumptions: a pager cell's position and safe area differ between systems, so a page
/// compares where its content would start and end with where the header and the tab bar really are.
private struct HomePageInset: ViewModifier {
    @Environment(\.homeHeader) private var header
    @State private var own: VSpan?
    /// A cell that is not on screen yet can report a frame of its own coordinate space: only believe plausible ones.
    private var placed: VSpan? { own.flatMap { abs($0.top - header.pagerTop) < 40 ? $0 : nil } }
    private var top: CGFloat {
        guard let rest = header.rest else { return 114 }
        return max(0, rest.top - (placed?.top ?? header.pagerTop))
    }
    private var bottom: CGFloat {
        guard let rest = header.rest, let placed else { return header.tabBar }
        return max(0, placed.bottom - rest.bottom)
    }
    func body(content: Content) -> some View {
        if header.active {
            content
                .safeAreaInset(edge: .top, spacing: 0) { Color.clear.frame(height: top) }
                .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: bottom) }
                .compatOnScrolled(threshold: 6) { header.onScrolled($0) }
                .background { SpanReader(span: $own) }
        } else {
            content
        }
    }
}

extension View {
    /// For the scroll view of a home page: rest below the floating header, scroll under it. No-op outside 发现.
    func homePageInset() -> some View { modifier(HomePageInset()) }
}

/// 发现页：玻璃搜索栏/筛选 + 来源分类条浮在内容上，下方按来源横滑翻页（红果 / 黄果 / AI剧 / 已开启的长视频站 / 频道 / 漫画 / 小说），
/// 分类条联动（选中项自动滚到居中、指示条跟随）。每页：二级榜单选择 → 竖海报 hero → 真实榜单分段。
/// 横滑冲突：hero 轮播区域内的横滑归轮播；其它区域横滑翻来源页；首页是根页面，没有系统侧滑返回。
struct HomeView: View {
    @ObservedObject private var gate = SourceGate.shared
    @ObservedObject private var pluginStore = PluginStore.shared
    @ObservedObject private var cmsStore = CMSStore.shared
    @State private var source: HomeSource
    @State private var scrollSourceKey: String?
    @State private var subs: [HomeSource: HomeTab]
    /// Cached: building it walks every site, plugin and 采集站, and it used to run several times per body.
    @State private var sources: [HomeSource] = HomeSource.all
    /// Lazy page mounting: only build the active page, its immediate ±1 swipe neighbors, and previously visited pages
    /// so jumping across tabs or installing a new plugin never instantiates all intermediate pages on the main thread.
    @State private var visited: Set<HomeSource>
    @State private var scrolled: [HomeSource: Bool] = [:]
    @State private var headerSpan: VSpan?
    @State private var safeSpan: VSpan?
    @State private var showChannels = false
    @State private var reorderingSources = false
    @State private var channelQuery = ""
    @State private var channelFilter = "全部"
    @Environment(\.layoutMetrics) private var layout
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.railLeadingInset) private var railInset
    private var rest: VSpan? {
        guard let h = headerSpan, let s = safeSpan else { return nil }
        return VSpan(top: h.bottom, bottom: s.bottom)
    }
    private var bottomInset: CGFloat { safeSpan?.inset ?? GlassTabBar.clearance + 34 }
    /// Home-indicator part of the bottom safe area; the rest is the floating tab bar (none next to the iPad rail).
    private var indicator: CGFloat { bottomInset >= GlassTabBar.clearance ? bottomInset - GlassTabBar.clearance : bottomInset }
    private var pinned: Bool { scrolled[source] ?? false }
    private var sourceRevisionToken: String {
        "\(gate.revision)-\(pluginStore.revision)-\(cmsStore.revision)"
    }

    init() {
        let e = ProcessInfo.processInfo.environment["STV_HOME_TAB"] ?? ""
        var src = HomeSource.all.first ?? .hongguo, sub: [HomeSource: HomeTab] = [:]
        if e == "actorRank" { src = .hongguo; sub[.hongguo] = .actorRank }
        #if DEBUG
        if let s = DramaSource(rawValue: e), s.isLongVideo { src = .site(s) }
        if e == "huangguo" { src = .huangguo }
        if e == "ai" { src = .ai }
        if e == "channels" { src = .channels }
        if e == "manga" { src = .manga }
        if e == "novel" { src = .novel }
        #endif
        _source = State(initialValue: src)
        _scrollSourceKey = State(initialValue: src.key)
        _subs = State(initialValue: sub)
        _visited = State(initialValue: [src])
    }

    private func shouldMount(_ s: HomeSource) -> Bool {
        if s == source || visited.contains(s) { return true }
        guard let cur = sources.firstIndex(of: source), let idx = sources.firstIndex(of: s) else { return false }
        return abs(cur - idx) <= 1
    }

    private func pageCell(_ s: HomeSource) -> some View {
        HomePageSlot(
            source: s,
            mounted: shouldMount(s),
            tab: Binding(get: { subs[s] ?? s.subs.first ?? .hongguo(.all) }, set: { subs[s] = $0 })
        )
        .environment(\.homeHeader, HomeHeader(active: true, rest: rest, pagerTop: safeSpan?.top ?? 0, tabBar: bottomInset - indicator, onScrolled: { v in
            guard s == source else { return }
            if scrolled[s] != v { scrolled[s] = v }
        }))
    }

    @ViewBuilder
    private var pagerContent: some View {
        if #available(iOS 17.0, *) {
            // Native ScrollView paging avoids UIPageViewController selection desyncs on iOS 17/18/26
            // when sources are added, removed, reordered, or mounted lazily.
            // GeometryReader keeps each page full-bleed (matching TabView(.page) without double safe-area insets),
            // and scrollPosition(id:) binds directly to ForEach's Identifiable ID type (String = HomeSource.key).
            GeometryReader { geo in
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 0) {
                            ForEach(sources, id: \.key) { s in
                                pageCell(s)
                                    .frame(width: geo.size.width, height: geo.size.height)
                                    .id(s.key)
                            }
                        }
                        .scrollTargetLayout()
                    }
                    .scrollTargetBehavior(.paging)
                    .scrollPosition(id: $scrollSourceKey)
                    .scrollIndicators(.hidden)
                    .task(id: Int(geo.size.width)) {
                        guard geo.size.width > 0 else { return }
                        if source != sources.first {
                            proxy.scrollTo(source.key, anchor: .leading)
                        }
                        scrollSourceKey = source.key
                    }
                    .compatOnChange(of: source) { next in
                        if !visited.contains(next) { visited.insert(next) }
                        if scrollSourceKey != next.key {
                            let prevIdx = scrollSourceKey.flatMap { k in sources.firstIndex(where: { $0.key == k }) }
                            let nextIdx = sources.firstIndex(of: next)
                            if let prevIdx, let nextIdx, abs(prevIdx - nextIdx) == 1 {
                                withAnimation(DS.select) {
                                    scrollSourceKey = next.key
                                    proxy.scrollTo(next.key, anchor: .leading)
                                }
                            } else {
                                scrollSourceKey = next.key
                                proxy.scrollTo(next.key, anchor: .leading)
                            }
                        }
                    }
                }
            }
        } else {
            TabView(selection: $source) {
                ForEach(sources) { s in
                    pageCell(s)
                        .tag(s)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .id(sources.map(\.key).joined(separator: "|"))
        }
    }

    var body: some View {
        pagerContent
        // Pages run under the floating tab bar and dissolve behind its lower edge. The pager stops at the home indicator:
        // iOS 26 centres pages in the UIKit safe region, so overlapping the indicator lifts every page by half its inset.
        .overlay(alignment: .bottom) {
            if bottomInset > indicator {
                LinearGradient(colors: [STVTheme.bg.opacity(0), STVTheme.bg], startPoint: .top, endPoint: .bottom)
                    .frame(height: 36).allowsHitTesting(false)
            }
        }
        .padding(.bottom, indicator)
        .ignoresSafeArea(.container, edges: .bottom)
        .background { SpanReader(span: $safeSpan).ignoresSafeArea(.keyboard) }
        .overlay(alignment: .top) { header }
        .dsAmbient(visible: !pinned)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $showChannels, onDismiss: { reorderingSources = false }) { channelPanel }
        .compatOnChange(of: sourceRevisionToken) { _ in refreshSources() }
        .compatOnChange(of: scrollSourceKey) { nextKey in
            guard let nextKey, nextKey != source.key, let next = sources.first(where: { $0.key == nextKey }) else { return }
            if !visited.contains(next) { visited.insert(next) }
            source = next
        }
        .task {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            await NovelStore.prewarm()
            await LegadoSourceStore.prewarm()
        }
        .onAppear { refreshSources() }
    }

    private func refreshSources() {
        let now = HomeSource.all
        if now != sources { sources = now }
        if !now.contains(source), let first = now.first { source = first }
        if !visited.contains(source) { visited.insert(source) }
        if scrollSourceKey != source.key { scrollSourceKey = source.key }
    }

    /// Use the currently visible home source as the initial search provider.
    private var activeSearchSource: DramaSource {
        switch source {
        case .hongguo: return .hongguo
        case .huangguo: return .huangguo
        case .site(let site): return site
        case .ai:
            if case .some(.huangguo(.ai)) = subs[.ai] { return .huangguo }
            return .hongguo
        case .channels, .manga, .novel: return .hongguo
        }
    }

    // MARK: Header (search + filter, source bar)

    private var channelButtonLabel: String {
        let total = HomeSource.allManageable.count
        let hidden = HomeSource.allManageable.count - sources.count
        if total > 7 || hidden > 0 { return "频道 · \(total)" }
        return "频道"
    }

    private var header: some View {
        VStack(spacing: 0) {
            if layout.useRail {
                HStack(spacing: 12) {
                    HomeChannelBar(items: sources, selection: $source, label: \.label, ink: STVTheme.text, horizontalPadding: layout.pagePadding)
                    Button { showChannels = true } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "line.3.horizontal.decrease").font(.system(size: 13, weight: .semibold))
                            Text(channelButtonLabel).font(.system(size: 14, weight: .medium))
                        }
                        .foregroundStyle(STVTheme.text)
                        .padding(.horizontal, 14).frame(height: 38).contentShape(Capsule())
                        .liquidGlassCapsule(interactive: true)
                    }
                    .buttonStyle(GlassPress()).accessibilityLabel("筛选与全部频道").accessibilityIdentifier("home.filter")
                    .padding(.trailing, layout.pagePadding)
                }
                .padding(.top, 6)
            } else {
                LiquidGlassGroup(spacing: 10) {
                    HStack(spacing: 10) {
                        NavigationLink(value: Route.searchFromSource(activeSearchSource)) {
                            DSSearchBarLabel()
                                .padding(.horizontal, 16).frame(height: 46).contentShape(Capsule())
                                .liquidGlassCapsule(interactive: true)
                        }
                        .buttonStyle(GlassPress()).accessibilityLabel("搜索").accessibilityIdentifier("home.search")
                        Button { showChannels = true } label: {
                            HStack(spacing: 5) {
                                Image(systemName: "line.3.horizontal.decrease").font(.system(size: 14, weight: .semibold))
                                Text(channelButtonLabel).font(.system(size: 14, weight: .medium))
                            }
                            .foregroundStyle(STVTheme.text)
                            .padding(.horizontal, 16).frame(height: 46).contentShape(Capsule())
                            .liquidGlassCapsule(interactive: true)
                        }
                        .buttonStyle(GlassPress()).accessibilityLabel("筛选与全部频道").accessibilityIdentifier("home.filter")
                    }
                }
                .padding(.horizontal, layout.pagePadding)
                HomeChannelBar(items: sources, selection: $source, label: \.label, ink: STVTheme.text, horizontalPadding: layout.pagePadding)
                    .padding(.top, 12)
            }
        }
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background { LiquidEdgeFade(visible: pinned) }
        .background { SpanReader(span: $headerSpan) }
    }

    private var channelPanel: some View {
        let allItems = HomeSource.allManageable
        let visibleItems = sources
        let hiddenItems = allItems.filter { gate.isHiddenOnHome($0.key) }
        let importedItems = allItems.filter(\.isImported)
        let q = channelQuery.trimmingCharacters(in: .whitespacesAndNewlines)

        func matches(_ item: HomeSource) -> Bool {
            if !q.isEmpty && !item.label.localizedCaseInsensitiveContains(q) && !item.categoryTag.localizedCaseInsensitiveContains(q) {
                return false
            }
            switch channelFilter {
            case "首页显示": return !gate.isHiddenOnHome(item.key)
            case "已收起": return gate.isHiddenOnHome(item.key)
            case "内置": return item.categoryTag == "内置" || item.categoryTag == "功能"
            case "采集站": return item.categoryTag == "采集站"
            case "插件": return item.categoryTag == "插件"
            default: return true
            }
        }

        let filteredVisible = visibleItems.filter(matches)
        let filteredHidden = hiddenItems.filter(matches)
        let cmsCount = allItems.filter { $0.categoryTag == "采集站" }.count
        let pluginCount = allItems.filter { $0.categoryTag == "插件" }.count
        let filterTabs: [(String, Int)] = {
            var list: [(String, Int)] = [("全部", allItems.count), ("首页显示", visibleItems.count)]
            if !hiddenItems.isEmpty { list.append(("已收起", hiddenItems.count)) }
            if cmsCount > 0 || pluginCount > 0 {
                list.append(("内置", allItems.filter { $0.categoryTag == "内置" || $0.categoryTag == "功能" }.count))
                if cmsCount > 0 { list.append(("采集站", cmsCount)) }
                if pluginCount > 0 { list.append(("插件", pluginCount)) }
            }
            return list
        }()
        let multiSubBuiltins = visibleItems.filter { $0.subs.count > 1 && matches($0) }

        return DSSheet("频道管理", subtitle: "首页显示 \(visibleItems.count) 个 · 已收起 \(hiddenItems.count) 个（搜索均可用）") {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // Mode switcher: 快速直达 | 管理与排序
                    SegmentedTabs(
                        items: [false, true],
                        selection: $reorderingSources,
                        label: { $0 ? "管理与排序" : "快速直达" }
                    )
                    .accessibilityIdentifier("home.order.toggle")

                    // Live search box across all sources
                    HStack(spacing: 8) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(STVTheme.secondary)
                        TextField("搜索频道或片源名称…", text: $channelQuery)
                            .font(.system(size: 14.5))
                            .textInputAutocapitalization(.never)
                            .disableAutocorrection(true)
                        if !channelQuery.isEmpty {
                            Button { channelQuery = "" } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 15))
                                    .foregroundStyle(STVTheme.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 13)
                    .frame(height: 40)
                    .background(STVTheme.glass, in: Capsule())

                    // Category filter chips
                    if filterTabs.count > 2 {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(filterTabs, id: \.0) { tab, count in
                                    let on = channelFilter == tab
                                    Button {
                                        DS.tick()
                                        withAnimation(DS.select) { channelFilter = tab }
                                    } label: {
                                        Text("\(tab) \(count)")
                                            .dsFont(12.5, on ? .bold : .medium, relativeTo: .caption)
                                            .foregroundStyle(on ? STVTheme.bg : STVTheme.secondary)
                                            .padding(.horizontal, 12).frame(height: 30)
                                            .background(on ? STVTheme.text : STVTheme.glass, in: Capsule())
                                    }
                                    .buttonStyle(PressScale())
                                }
                            }
                        }
                    }

                    if reorderingSources {
                        // One-tap batch cleanup bar for heavy source importers
                        if !importedItems.isEmpty || !gate.homeOrder.isEmpty || !hiddenItems.isEmpty {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("一键整理首页顶栏").dsFont(13, .semibold, relativeTo: .footnote).foregroundStyle(STVTheme.secondary)
                                HStack(spacing: 8) {
                                    if !importedItems.isEmpty {
                                        let allImportedHidden = importedItems.allSatisfy { gate.isHiddenOnHome($0.key) }
                                        Button {
                                            DS.tick()
                                            withAnimation(DS.select) {
                                                gate.setHomeHiddenBatch(importedItems.map(\.key), hidden: !allImportedHidden)
                                                refreshSources()
                                            }
                                        } label: {
                                            Text(allImportedHidden ? "展开导入源" : "仅保留内置")
                                                .dsFont(13, .semibold, relativeTo: .footnote)
                                                .foregroundStyle(STVTheme.accent)
                                                .frame(maxWidth: .infinity, minHeight: 38)
                                                .background(STVTheme.accentSoft, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                        }
                                        .buttonStyle(PressScale())
                                    }
                                    if !hiddenItems.isEmpty {
                                        Button {
                                            DS.tick()
                                            withAnimation(DS.select) {
                                                gate.clearHomeHidden()
                                                refreshSources()
                                            }
                                        } label: {
                                            Text("全部显示")
                                                .dsFont(13, .medium, relativeTo: .footnote)
                                                .foregroundStyle(STVTheme.text)
                                                .frame(maxWidth: .infinity, minHeight: 38)
                                                .background(STVTheme.glass, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                        }
                                        .buttonStyle(PressScale())
                                    }
                                    if !gate.homeOrder.isEmpty {
                                        Button {
                                            DS.tick()
                                            withAnimation(DS.select) {
                                                gate.resetHomeOrder()
                                                refreshSources()
                                            }
                                        } label: {
                                            Text("默认顺序")
                                                .dsFont(13, .medium, relativeTo: .footnote)
                                                .foregroundStyle(STVTheme.secondary)
                                                .frame(maxWidth: .infinity, minHeight: 38)
                                                .background(STVTheme.glass, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                        }
                                        .buttonStyle(PressScale())
                                        .accessibilityIdentifier("home.order.reset")
                                    }
                                }
                                Text("收起的片源仅从首页顶栏隐藏，全局搜索仍完全可用。")
                                    .dsFont(11.5, relativeTo: .caption).foregroundStyle(STVTheme.tertiary)
                            }
                        }

                        // Home feature switches
                        VStack(alignment: .leading, spacing: 10) {
                            Text("功能频道开关").dsFont(13, .semibold, relativeTo: .footnote).foregroundStyle(STVTheme.secondary)
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 78), spacing: 8)], spacing: 8) {
                                homeFeatureChip("频道", on: gate.showChannelsTab) { gate.setShowChannelsTab(!gate.showChannelsTab) }
                                homeFeatureChip("漫画", on: gate.showMangaTab) { gate.setShowMangaTab(!gate.showMangaTab) }
                                homeFeatureChip("小说", on: gate.showNovelTab) { gate.setShowNovelTab(!gate.showNovelTab) }
                                homeFeatureChip("底部音乐", on: gate.showMusicTab) { gate.setShowMusicTab(!gate.showMusicTab) }
                            }
                        }

                        // Visible sources (press-and-drag reorder + pin + fold off Home)
                        if !filteredVisible.isEmpty {
                            VStack(alignment: .leading, spacing: 10) {
                                HStack {
                                    Text("首页已显示（\(filteredVisible.count)）").dsFont(13, .semibold, relativeTo: .footnote).foregroundStyle(STVTheme.secondary)
                                    Spacer()
                                    Text("长按或拖 ≡ 排序").dsFont(11.5, relativeTo: .caption).foregroundStyle(STVTheme.tertiary)
                                }
                                HomeSourceOrderRows(
                                    sources: filteredVisible,
                                    onChange: { updatedSubset in
                                        if q.isEmpty && (channelFilter == "全部" || channelFilter == "首页显示") {
                                            sources = updatedSubset
                                            gate.setHomeOrder(updatedSubset.map(\.key))
                                        } else {
                                            // Apply relative reorder within the full visible list
                                            let subsetKeys = Set(updatedSubset.map(\.key))
                                            var iter = updatedSubset.makeIterator()
                                            let merged = visibleItems.map { subsetKeys.contains($0.key) ? (iter.next() ?? $0) : $0 }
                                            sources = merged
                                            gate.setHomeOrder(merged.map(\.key))
                                        }
                                    },
                                    onPinToTop: { item in
                                        var copy = visibleItems
                                        copy.removeAll { $0.key == item.key }
                                        copy.insert(item, at: 0)
                                        sources = copy
                                        gate.setHomeOrder(copy.map(\.key))
                                    },
                                    onHideFromHome: { item in
                                        DS.tick()
                                        withAnimation(DS.select) {
                                            gate.setHomeHidden(item.key, hidden: true)
                                            refreshSources()
                                        }
                                    }
                                )
                            }
                        }

                        // Hidden / folded sources (1-tap restore to Home)
                        if !filteredHidden.isEmpty {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("已从首页收起（\(filteredHidden.count) · 搜索仍可用）")
                                    .dsFont(13, .semibold, relativeTo: .footnote).foregroundStyle(STVTheme.secondary)
                                VStack(spacing: 0) {
                                    ForEach(Array(filteredHidden.enumerated()), id: \.element.id) { idx, item in
                                        HStack(spacing: 10) {
                                            Text(item.label)
                                                .dsFont(15, .medium, relativeTo: .body)
                                                .foregroundStyle(STVTheme.secondary)
                                                .lineLimit(1)
                                            DSTag(text: item.categoryTag)
                                            Spacer(minLength: 0)
                                            Button {
                                                DS.tick()
                                                withAnimation(DS.select) {
                                                    gate.setHomeHidden(item.key, hidden: false)
                                                    refreshSources()
                                                }
                                            } label: {
                                                HStack(spacing: 4) {
                                                    Image(systemName: "plus").font(.system(size: 11, weight: .bold))
                                                    Text("放回首页").dsFont(12.5, .semibold, relativeTo: .caption)
                                                }
                                                .foregroundStyle(STVTheme.accent)
                                                .padding(.horizontal, 10).frame(height: 30)
                                                .background(STVTheme.accentSoft, in: Capsule())
                                            }
                                            .buttonStyle(PressScale())
                                        }
                                        .padding(.horizontal, 14).frame(minHeight: 46)
                                        if idx < filteredHidden.count - 1 { Hairline().padding(.leading, 14) }
                                    }
                                }
                                .background(STVTheme.glass, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            }
                        }
                    } else {
                        // Mode 1: 快速直达
                        if !filteredVisible.isEmpty {
                            VStack(alignment: .leading, spacing: 10) {
                                HStack {
                                    Text("首页频道（\(filteredVisible.count)）").dsFont(13, .semibold, relativeTo: .footnote).foregroundStyle(STVTheme.secondary)
                                    Spacer()
                                    if !importedItems.isEmpty {
                                        Button {
                                            DS.tick()
                                            withAnimation(DS.select) { reorderingSources = true }
                                        } label: {
                                            Text("管理 / 收起 ›").dsFont(12.5, .medium, relativeTo: .caption).foregroundStyle(STVTheme.accent)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 88), spacing: 10)], spacing: 10) {
                                    ForEach(filteredVisible) { s in
                                        let on = s == source
                                        Button {
                                            DS.tick()
                                            source = s
                                            showChannels = false
                                        } label: {
                                            Text(s.label).dsFont(14, on ? .semibold : .regular, relativeTo: .subheadline)
                                                .foregroundStyle(on ? STVTheme.accent : STVTheme.text)
                                                .lineLimit(1).minimumScaleFactor(0.8)
                                                .frame(maxWidth: .infinity, minHeight: 42)
                                                .padding(.horizontal, 6)
                                                .background(on ? STVTheme.accentSoft : STVTheme.glass,
                                                            in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                                                .contentShape(RoundedRectangle(cornerRadius: 14))
                                        }
                                        .buttonStyle(PressScale()).accessibilityAddTraits(on ? .isSelected : [])
                                        .accessibilityIdentifier("panel.source.\(s.label)")
                                    }
                                }
                            }
                        }

                        // Built-in multi-sub sections ONLY (never duplicate single-page CMS/plugins!)
                        if q.isEmpty && (channelFilter == "全部" || channelFilter == "内置" || channelFilter == "首页显示") {
                            ForEach(multiSubBuiltins) { s in
                                VStack(alignment: .leading, spacing: 10) {
                                    Text(s.label + " · 板块").dsFont(13, .semibold, relativeTo: .footnote).foregroundStyle(STVTheme.secondary)
                                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 10)], spacing: 10) {
                                        ForEach(s.subs, id: \.self) { t in
                                            let on = s == source && (subs[s] ?? s.subs[0]) == t
                                            Button { subs[s] = t; source = s; showChannels = false } label: {
                                                Text(t.subLabel).dsFont(14, on ? .semibold : .regular, relativeTo: .subheadline)
                                                    .foregroundStyle(on ? STVTheme.accent : STVTheme.text)
                                                    .lineLimit(1).minimumScaleFactor(0.8)
                                                    .frame(maxWidth: .infinity, minHeight: 42)
                                                    .background(on ? STVTheme.accentSoft : STVTheme.glass,
                                                                in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                                                    .contentShape(RoundedRectangle(cornerRadius: 14))
                                            }
                                            .buttonStyle(PressScale()).accessibilityAddTraits(on ? .isSelected : [])
                                            .accessibilityIdentifier("panel.\(s.label).\(t.subLabel)")
                                        }
                                    }
                                }
                            }
                        }

                        // Folded sources (still 1-tap reachable!)
                        if !filteredHidden.isEmpty {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("已收起的片源（\(filteredHidden.count) · 点按放回并打开）")
                                    .dsFont(13, .semibold, relativeTo: .footnote).foregroundStyle(STVTheme.secondary)
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 10)], spacing: 10) {
                                    ForEach(filteredHidden) { s in
                                        Button {
                                            DS.tick()
                                            gate.setHomeHidden(s.key, hidden: false)
                                            refreshSources()
                                            source = s
                                            showChannels = false
                                        } label: {
                                            HStack(spacing: 4) {
                                                Text(s.label).dsFont(13.5, .medium, relativeTo: .subheadline)
                                                    .foregroundStyle(STVTheme.secondary)
                                                    .lineLimit(1).minimumScaleFactor(0.8)
                                            }
                                            .frame(maxWidth: .infinity, minHeight: 40)
                                            .padding(.horizontal, 6)
                                            .background(STVTheme.glass.opacity(0.65), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(STVTheme.hairline, style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
                                        }
                                        .buttonStyle(PressScale())
                                    }
                                }
                            }
                        }

                        if filteredVisible.isEmpty && filteredHidden.isEmpty {
                            Text("没有匹配的频道或片源")
                                .dsFont(14, relativeTo: .subheadline)
                                .foregroundStyle(STVTheme.tertiary)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 28)
                        }
                    }
                }
                .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 28)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("home.channelPanel")
        .dsSheetChrome([.medium, .large])
    }

    private func homeFeatureChip(_ label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button {
            DS.tick()
            withAnimation(DS.select) {
                action()
                refreshSources()
            }
        } label: {
            HStack(spacing: 5) {
                Image(systemName: on ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 13, weight: .semibold))
                Text(label).dsFont(13.5, on ? .semibold : .regular, relativeTo: .subheadline)
            }
            .foregroundStyle(on ? STVTheme.accent : STVTheme.secondary)
            .frame(maxWidth: .infinity, minHeight: 40)
            .background(on ? STVTheme.accentSoft : STVTheme.glass, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

/// Shared inline press-and-drag reorder list for Home sources (supports 长按拖动 / 左侧 ≡ 即时拖动 / 置顶 / 从首页收起).
struct HomeSourceOrderRows: View {
    let sources: [HomeSource]
    let onChange: ([HomeSource]) -> Void
    var onPinToTop: ((HomeSource) -> Void)? = nil
    var onHideFromHome: ((HomeSource) -> Void)? = nil

    @State private var draggingKey: String? = nil
    @State private var dragAnchorY: CGFloat = 0
    @State private var dragAnchorSet = false
    @State private var dragOffsetY: CGFloat = 0
    private let rowHeight: CGFloat = 50

    var body: some View {
        VStack(spacing: 0) {
            ForEach(sources, id: \.id) { item in
                let idx = sources.firstIndex(where: { $0.id == item.id }) ?? 0
                let isDragging = draggingKey == item.key
                HStack(spacing: 8) {
                    // Instant drag handle (touch-and-drag immediately without ScrollView contention)
                    Image(systemName: "line.3.horizontal")
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(isDragging ? STVTheme.accent : STVTheme.tertiary)
                        .frame(width: 28, height: rowHeight)
                        .contentShape(Rectangle())
                        .highPriorityGesture(
                            DragGesture(minimumDistance: 2, coordinateSpace: .named("homeOrderList"))
                                .onChanged { v in handleDragChanged(item: item, locationY: v.location.y) }
                                .onEnded { _ in finishDrag() }
                        )
                    Text("\(idx + 1)")
                        .dsFont(12, .bold, relativeTo: .caption)
                        .foregroundStyle(idx == 0 ? STVTheme.accent : STVTheme.tertiary)
                        .frame(width: 20, alignment: .center)
                    Text(item.label)
                        .dsFont(15, isDragging ? .semibold : .medium, relativeTo: .body)
                        .foregroundStyle(STVTheme.text)
                        .lineLimit(1)
                    if case .site(let s) = item {
                        DSTag(text: s.pluginID != nil ? "插件" : (s.cmsKey != nil ? "采集站" : "内置"))
                    }
                    Spacer(minLength: 0)
                    Button { moveTop(item) } label: {
                        Image(systemName: "arrow.up.to.line")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(idx > 0 ? STVTheme.secondary : STVTheme.tertiary.opacity(0.35))
                            .frame(width: 32, height: 36).contentShape(Rectangle())
                    }
                    .buttonStyle(PressScale()).disabled(idx == 0 && onPinToTop == nil).accessibilityLabel("置顶 \(item.label)")
                    if let onHide = onHideFromHome, item.canHideFromHome {
                        Button { onHide(item) } label: {
                            Image(systemName: "eye.slash")
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(STVTheme.secondary)
                                .frame(width: 32, height: 36).contentShape(Rectangle())
                        }
                        .buttonStyle(PressScale()).accessibilityLabel("从首页收起 \(item.label)")
                    }
                }
                .padding(.horizontal, 10)
                .frame(height: rowHeight)
                .background(
                    RoundedRectangle(cornerRadius: isDragging ? 14 : 0, style: .continuous)
                        .fill(isDragging ? STVTheme.card : Color.clear)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(isDragging ? STVTheme.accent.opacity(0.75) : Color.clear, lineWidth: 1.2)
                )
                .overlay(alignment: .bottom) {
                    if !isDragging && idx < sources.count - 1 { Hairline().padding(.leading, 46) }
                }
                .shadow(color: isDragging ? Color.black.opacity(0.24) : .clear, radius: 10, y: 4)
                .scaleEffect(isDragging ? 1.02 : 1.0)
                .offset(y: isDragging ? dragOffsetY : 0)
                .zIndex(isDragging ? 10 : 0)
                .contentShape(Rectangle())
                // Press-and-hold anywhere on the row to lift and drag smoothly
                .simultaneousGesture(
                    LongPressGesture(minimumDuration: 0.22)
                        .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named("homeOrderList")))
                        .onChanged { phase in
                            switch phase {
                            case .second(true, nil):
                                if draggingKey != item.key {
                                    draggingKey = item.key
                                    dragAnchorSet = false
                                    dragOffsetY = 0
                                    DS.tick()
                                }
                            case .second(true, let drag?):
                                handleDragChanged(item: item, locationY: drag.location.y)
                            default:
                                break
                            }
                        }
                        .onEnded { _ in finishDrag() }
                )
                .accessibilityAction(named: "上移") { move(item, by: -1) }
                .accessibilityAction(named: "下移") { move(item, by: 1) }
            }
        }
        .coordinateSpace(name: "homeOrderList")
        .background(STVTheme.glass, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func handleDragChanged(item: HomeSource, locationY: CGFloat) {
        if draggingKey != item.key || !dragAnchorSet {
            draggingKey = item.key
            dragAnchorY = locationY
            dragAnchorSet = true
            dragOffsetY = 0
            DS.tick()
            return
        }
        var dy = locationY - dragAnchorY
        guard let curIdx = sources.firstIndex(where: { $0.id == item.id }) else { return }
        if dy > rowHeight * 0.52, curIdx + 1 < sources.count {
            var copy = sources
            copy.swapAt(curIdx, curIdx + 1)
            dragAnchorY += rowHeight
            dy -= rowHeight
            dragOffsetY = dy
            DS.tick()
            withAnimation(DS.select) { onChange(copy) }
        } else if dy < -rowHeight * 0.52, curIdx - 1 >= 0 {
            var copy = sources
            copy.swapAt(curIdx, curIdx - 1)
            dragAnchorY -= rowHeight
            dy += rowHeight
            dragOffsetY = dy
            DS.tick()
            withAnimation(DS.select) { onChange(copy) }
        } else {
            dragOffsetY = dy
        }
    }

    private func finishDrag() {
        withAnimation(DS.select) {
            draggingKey = nil
            dragAnchorSet = false
            dragOffsetY = 0
        }
    }

    private func moveTop(_ item: HomeSource) {
        if let onPin = onPinToTop {
            DS.tick()
            withAnimation(DS.select) { onPin(item) }
            return
        }
        guard let idx = sources.firstIndex(where: { $0.id == item.id }), idx > 0 else { return }
        DS.tick()
        var copy = sources
        let picked = copy.remove(at: idx)
        copy.insert(picked, at: 0)
        withAnimation(DS.select) { onChange(copy) }
    }

    private func move(_ item: HomeSource, by delta: Int) {
        guard let idx = sources.firstIndex(where: { $0.id == item.id }) else { return }
        let target = idx + delta
        guard sources.indices.contains(target) else { return }
        DS.tick()
        var copy = sources
        copy.swapAt(idx, target)
        withAnimation(DS.select) { onChange(copy) }
    }
}

/// Stable-identity wrapper so lazy mounting never alters the pager cell's outer view type.
private struct HomePageSlot: View {
    let source: HomeSource
    let mounted: Bool
    @Binding var tab: HomeTab
    var body: some View {
        ZStack {
            if mounted {
                HomePage(source: source, tab: $tab)
            } else {
                Color.clear
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Built only when the pager reaches it: 频道 / 漫画 / 小说 load their stores from disk on first use, and constructing those
/// views together with the header used to do all of that at launch.
private struct HomePage: View {
    let source: HomeSource
    @Binding var tab: HomeTab
    var body: some View {
        switch source {
        case .channels: ChannelFeedView(embedded: true)
        case .manga: MangaHomeView(embedded: true)
        case .novel: NovelHomeView(embedded: true)
        default: HomeFeedPage(source: source, tab: $tab)
        }
    }
}

/// One source page: secondary board chips → hero → real board sections. Vertical scroll inside a horizontal pager.
private struct HomeFeedPage: View {
    let source: HomeSource
    @Binding var tab: HomeTab
    @State private var legacyChannels: [HuangguoChannel] = []
    @State private var legacyError: Error?
    @Environment(\.layoutMetrics) private var layout
    private var gutter: CGFloat { layout.pagePadding }
    /// DEBUG `STV_HOME_SCROLL=bottom` opens the feed scrolled to the end (simulator screenshots).
    private static var debugAnchor: UnitPoint? {
        #if DEBUG
        if ProcessInfo.processInfo.environment["STV_HOME_SCROLL"] == "bottom" { return .bottom }
        #endif
        return nil
    }

    var body: some View {
        if case .site(let source) = tab, source.cmsKey != nil || source.pluginID != nil {
            DynamicSiteFeed(source: source)
        } else {
            rankFeed
        }
    }

    private var rankFeed: some View {
        let boards = self.boards
        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if source.subs.count > 1 { subChips } else if case .site = source { boardChips(boards) }
                if tab == .actorRank {
                    ActorRankList().padding(.vertical, 16)
                } else {
                    // Lazy: boards below the fold are neither built nor requested until they are scrolled to.
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if let first = boards.first { HomeHeroSection(feed: BoardCache.shared.feed(first), board: first).id(first.id) }
                        if source == .hongguo { HomeContinueSection() }
                        sections(boards)
                    }
                }
            }
            .padding(.bottom, 24)
            .compatScrollContent()
        }
        .scrollIndicators(.hidden)
        .compatDefaultScrollAnchor(Self.debugAnchor)
        .accessibilityIdentifier("home.page.\(source.label)")
        .homePageInset()
        .refreshable {
            if tab == .actorRank { await ActorRankStore.shared.reload() } else { boards.forEach { BoardCache.shared.feed($0).load(force: true) } }
        }
        .task(id: tab) { if case .huangguo(.legacy) = tab, legacyChannels.isEmpty { await loadLegacy() } }
    }

    /// ui-redesign 10: secondary boards are the same segmented control as 找剧.
    private var subChips: some View {
        SegmentedTabs(items: source.subs, selection: $tab, label: \.subLabel, idPrefix: "home.sub")
            .padding(.horizontal, gutter)
            .padding(.top, 8)
    }

    /// Long-video site pages: same chip row height as 红果/黄果 (top never jumps); each chip opens that board.
    private func boardChips(_ boards: [BoardSpec]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 8) {
                ForEach(Array(boards.enumerated()), id: \.element.id) { i, b in
                    NavigationLink(value: Route.board(b)) { Text(b.title) }
                    .buttonStyle(ToneChipStyle(on: i == 0, neutral: true))
                    .accessibilityIdentifier("home.sub.\(b.title)")
                }
            }.padding(.horizontal, gutter).padding(.vertical, 5)
        }
        .frame(height: 42)
        .compatScrollBounceBasedOnSize(axes: .horizontal)
        .mask(FadeMask())
        .padding(.top, 8)
    }

    // MARK: Data

    private var boards: [BoardSpec] {
        switch tab {
        case .hongguo(let g): g.subs.map { BoardSpec(kind: .hongguo(g, $0)) }
        case .huangguo(.ai): HuangguoClient.aiChannels.map { BoardSpec(kind: .huangguo($0)) }
        case .huangguo(.video): HuangguoClient.videoChannels.map { BoardSpec(kind: .huangguo($0)) }
        case .huangguo(.legacy): legacyChannels.map { BoardSpec(kind: .huangguo($0)) }
        case .site(let s): (Sites.provider(s)?.categories ?? []).map { BoardSpec(kind: .site(s, $0)) }
        case .actorRank: []
        }
    }

    @ViewBuilder private func sections(_ boards: [BoardSpec]) -> some View {
        if case .huangguo(.legacy) = tab, legacyChannels.isEmpty {
            if let legacyError { ErrorStateView(error: legacyError) { Task { await loadLegacy() } } }
            else { HomeCardsSkeleton(landscape: false).padding(.horizontal, gutter).padding(.top, 24) }
        } else {
            let landscape: Bool = { if case .site(let s) = tab { return s.isLongVideo } else { return false } }()
            ForEach(Array(boards.enumerated()), id: \.element.id) { n, b in
                HomeBoardSection(feed: BoardCache.shared.feed(b), board: b, skip: n == 0 ? HomeHeroSection.count : 0, landscape: landscape) {
                    boards.forEach { BoardCache.shared.feed($0).load(force: true) }
                }
                .padding(.top, 24)
            }
        }
    }

    private func loadLegacy() async {
        do { legacyChannels = try await HuangguoClient.shared.legacyChannels(); legacyError = nil } catch { legacyError = error }
    }
}

/// Hero of the first board; observes that board only.
private struct HomeHeroSection: View {
    static let count = 5
    @ObservedObject var feed: BoardFeed
    let board: BoardSpec
    @State private var index = 0
    @Environment(\.layoutMetrics) private var layout
    var body: some View {
        let picks = Array(feed.items.prefix(Self.count))
        let gutter = layout.pagePadding
        let skelH: CGFloat = layout.tier == .expanded ? 286 : (layout.tier == .medium ? 228 : 177)
        Group {
            if !picks.isEmpty {
                PosterHero(board: board.title, picks: picks, index: $index)
                    .padding(.horizontal, gutter).padding(.top, layout.useRail ? 10 : 16)
                    .transition(.opacity)
            } else if feed.error == nil {
                ShimmerBlock(corner: layout.tier == .compact ? 22 : 24).frame(height: skelH)
                    .padding(.horizontal, gutter).padding(.top, layout.useRail ? 10 : 16)
            }
        }
        .animation(.easeOut(duration: 0.25), value: picks.isEmpty)
        .onAppear { feed.load() }
    }
}

/// Watch history lives in its own view: playback writes progress every few seconds, which must not re-render the feed.
private struct HomeContinueSection: View {
    @ObservedObject private var library = LibraryStore.shared
    var body: some View {
        let items = Array(library.continueWatching.prefix(10))
        if !items.isEmpty { HomeContinueRow(items: items).padding(.top, 24) }
    }
}

/// One board: shelf, skeleton or inline error.
private struct HomeBoardSection: View {
    @ObservedObject var feed: BoardFeed
    let board: BoardSpec
    /// Items already shown by the hero above.
    let skip: Int
    let landscape: Bool
    let reloadAll: () -> Void
    @State private var verify: VerifyTarget?
    @Environment(\.layoutMetrics) private var layout

    var body: some View {
        Group {
            if let e = feed.error {
                if skip > 0 {
                    ErrorStateView(error: e, retry: reloadAll)
                } else {
                    VStack(alignment: .leading, spacing: 12) {
                        HomeShelf(title: board.title, note: nil, items: [], more: board)
                        HStack(spacing: 10) {
                            Text(e.verifyTarget != nil ? "需要站点验证" : "加载失败").dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.secondary)
                            Spacer(minLength: 0)
                            if let t = e.verifyTarget { Button { verify = t } label: { MiniButtonLabel(title: "去验证") } }
                            Button { feed.load(force: true) } label: { MiniButtonLabel(title: "重试") }
                        }
                        .buttonStyle(PressScale())
                        .padding(.horizontal, layout.pagePadding)
                    }
                }
            } else if case .loaded(let items) = feed.state {
                // 第一个榜单一律跳过 hero 里已展示的剧；平板端首榜展开为无框 4/5/6 列整行海报矩阵，避免第二行大面积空位。
                let isTabletMatrix = layout.tier != .compact && skip > 0 && !landscape
                let rem = Array(items.dropFirst(skip))
                let shown: [HGSeries] = {
                    if isTabletMatrix {
                        let cols = layout.posterCols
                        let fullRows = (rem.count / cols) * cols
                        let count = fullRows > 0 ? min(fullRows, cols * 2) : cols
                        return Array(rem.prefix(count))
                    }
                    return Array(rem.prefix(layout.tier == .compact ? 12 : 16))
                }()
                HomeShelf(title: board.title, note: nil, items: shown, more: board, gridOnTablet: isTabletMatrix)
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    DSSectionHeader(title: board.title, inset: layout.pagePadding)
                    HomeCardsSkeleton(landscape: landscape).padding(.horizontal, layout.pagePadding)
                }
            }
        }
        .onAppear { feed.load() }
        .sheet(item: $verify) { t in SiteVerifySheet(target: t, onDone: reloadAll) }
    }
}

private struct HomeCardsSkeleton: View {
    let landscape: Bool
    var body: some View {
        HStack(alignment: .top, spacing: landscape ? 12 : 10) {
            ForEach(0..<(landscape ? 2 : 3), id: \.self) { _ in
                VStack(alignment: .leading, spacing: 8) {
                    ShimmerBlock().aspectRatio(landscape ? 16/9 : 3/4, contentMode: .fit)
                    ShimmerBlock(corner: 5).frame(height: 12).frame(maxWidth: 90)
                }
            }
        }
    }
}

/// CMS / JS plugin home: every returned category is a selectable module, and the selected one pages its own list.
private struct DynamicSiteFeed: View {
    let source: DramaSource
    @State private var categories: [SiteCategory] = []
    @State private var selected = ""
    @StateObject private var pager = Pager()
    @State private var didLoad = false

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Color.clear.frame(height: 0).id("dynamic.top")
                    if categories.count > 1 { chips }
                    if let e = pager.error, pager.items.isEmpty {
                        ErrorStateView(error: e) { reload(selected) }
                    } else if pager.items.isEmpty && pager.isLoading {
                        HomeCardsSkeleton(landscape: source.isLongVideo).padding(.horizontal, 16)
                    } else if pager.items.isEmpty {
                        DSEmptyState("该分类暂无内容", systemImage: "rectangle.stack", message: "部分采集站的父分类不直接提供资源，请选择动作片、国产剧等具体分类。")
                    } else {
                        NumberedGrid(items: pager.items, numbered: false, hasMore: pager.hasMore, loading: pager.isLoading) { pager.loadMore() }
                    }
                }
                .padding(.vertical, 8)
                .compatScrollContent()
            }
            .scrollIndicators(.hidden)
            .accessibilityIdentifier("home.page.\(source.label)")
            .refreshable { await refresh() }
            .overlay(alignment: .bottomTrailing) {
                Button {
                    withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo("dynamic.top", anchor: .top) }
                    Task { await refresh() }
                } label: {
                    Group {
                        if pager.isLoading { DSSpinner(size: 18, tint: STVTheme.text) }
                        else { Image(systemName: "arrow.clockwise").font(.system(size: 17, weight: .semibold)).foregroundStyle(STVTheme.text) }
                    }
                    .frame(width: 48, height: 48)
                    .liquidGlass(Circle(), interactive: true)
                    .contentShape(Circle())
                }
                .buttonStyle(GlassPress())
                .disabled(pager.isLoading)
                .padding(.trailing, 18).padding(.bottom, 24)
                .accessibilityLabel("刷新列表")
                .accessibilityIdentifier("home.dynamic.refresh")
            }
            .homePageInset()
        }
        .task {
            guard !didLoad else { return }
            didLoad = true
            await refresh()
        }
    }

    private var chips: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 8) {
                    ForEach(categories) { c in
                        let on = c.id == selected
                        Button { DS.tick(); withAnimation(DS.select) { selected = c.id }; reload(c.id) } label: { Text(c.title) }
                            .buttonStyle(ToneChipStyle(on: on))
                            .id(c.id)
                            .accessibilityIdentifier("home.module.\(c.title)")
                            .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }.padding(.horizontal, 16)
            }
            .frame(height: 34)
            .compatScrollBounceBasedOnSize(axes: .horizontal)
            .compatOnChange(of: selected) { id in withAnimation(DS.select) { proxy.scrollTo(id, anchor: .center) } }
        }
    }

    private func refresh() async {
        let loaded = await Self.loadCategories(source)
        categories = loaded
        let id = loaded.contains { $0.id == selected } ? selected : (loaded.first?.id ?? "")
        selected = id
        reload(id)
    }

    private func reload(_ id: String) {
        let source = source
        pager.reset { prev, _ in
            guard let provider = Sites.provider(source) else { throw ProviderError.unsupported(source.label) }
            let category = SiteCategory(id: id, title: "", path: id)
            let page = try await provider.list(category, page: max(1, prev.nextOffset ?? 1))
            if (prev.nextOffset ?? 1) <= 1, source.pluginID != nil {
                let latestCats = provider.categories
                if !latestCats.isEmpty {
                    await MainActor.run {
                        if categories.map(\.id) != latestCats.map(\.id) { categories = latestCats }
                    }
                }
            }
            return page
        }
    }

    private static func loadCategories(_ source: DramaSource) async -> [SiteCategory] {
        if let key = source.cmsKey {
            var stored = CMSStore.site(key)?.categories ?? []
            if let site = CMSStore.site(key), let fresh = try? await MacCMSProvider.fetchCategories(api: site.api), !fresh.isEmpty {
                stored = fresh
                await MainActor.run { CMSStore.shared.cacheCategories(key, fresh) }
            }
            let latest = SiteCategory(id: "", title: "最新", path: "")
            return [latest] + stored.map { SiteCategory(id: $0.id, title: $0.name, path: $0.id) }
        }
        let fallback = SiteCategory(id: "", title: "推荐", path: "")
        guard let provider = Sites.provider(source) else { return [fallback] }
        let cats = provider.categories
        return cats.isEmpty ? [fallback] : cats
    }
}

enum HomeFmt {
    /// 次要信息：类型（最多两个标签）· 集数 / 热度；长视频是时长 · 观看。
    static func meta(_ s: HGSeries) -> String {
        if s.source.isLongVideo { return Fmt.meta(s) }
        let genres = s.tags.prefix(2).joined(separator: " · ")
        let parts = [genres.isEmpty ? nil : genres, s.episodeCount > 0 ? "\(s.episodeCount)集" : nil].compactMap { $0 }
        return parts.isEmpty ? Fmt.meta(s) : parts.joined(separator: " · ")
    }
    /// 右下角小角标：「更新至 N 集」/「全 N 集」/ 长视频时长。
    static func progress(_ s: HGSeries) -> String? {
        if let sub = s.subtitle, sub.contains("更新至") || sub.hasPrefix("全") { return sub }
        if s.source.isLongVideo { return s.duration > 0 ? Fmt.clock(s.duration) : nil }
        return s.episodeCount > 0 ? "全\(s.episodeCount)集" : nil
    }
}

/// 分类条：未选中 17pt 次要色，选中 20pt 粗体 + 品牌色短条（随选中项滑动）；点击或翻页时选中项自动滚到居中。
struct HomeChannelBar<T: Hashable>: View {
    let items: [T]
    @Binding var selection: T
    let label: (T) -> String
    var ink: Color = .white
    var horizontalPadding: CGFloat = 20
    @Namespace private var ns
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .lastTextBaseline, spacing: 22) {
                    ForEach(items, id: \.self) { it in
                        let on = it == selection
                        // Do NOT wrap `selection = it` in `withAnimation`: `TabView(.page)` (`UIPageViewController`)
                        // otherwise sweeps & synchronously instantiates every intermediate page on the main thread.
                        Button { if !on { DS.tick(); selection = it } } label: {
                            UnderlineTabLabel(title: label(it), on: on, ink: ink, inkOff: STVTheme.secondary, ns: ns)
                                .frame(minHeight: 40, alignment: .bottom)
                                .contentShape(Rectangle())
                        }.buttonStyle(.plain).id(it).accessibilityIdentifier("home.channel.\(label(it))")
                            .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
                .padding(.horizontal, horizontalPadding)
                .animation(DS.select, value: selection)
                .animation(DS.select, value: items)
            }
            .accessibilityIdentifier("home.channels")
            .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.93),
                                         .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing))
            .compatOnChange(of: selection) { it in withAnimation(DS.select) { proxy.scrollTo(it, anchor: .center) } }
            .compatOnChange(of: items) { _ in proxy.scrollTo(selection, anchor: .center) }
            .onAppear { proxy.scrollTo(selection, anchor: .center) }
        }
        .frame(height: 40)
    }
}

/// 竖海报 hero：整块用当前海报模糊做底，左侧完整 3:4 竖海报（不裁成横图），
/// 右侧榜单名、片名、标签/集数/热度、简介或主演，以及「查看详情」。横滑切换，右上指示点。
/// 长视频站点（横版封面）同一布局里海报区按 16:9 显示在上方。
private struct PosterHero: View {
    @Environment(\.zoomNS) private var ns
    @Environment(\.layoutMetrics) private var layout
    @ObservedObject private var library = LibraryStore.shared
    let board: String
    let picks: [HGSeries]
    @Binding var index: Int

    private var isTablet: Bool { layout.tier != .compact }
    private var corner: CGFloat { isTablet ? 24 : 22 }

    var body: some View {
        TabView(selection: $index) {
            ForEach(Array(picks.enumerated()), id: \.element.id) { i, s in
                Group {
                    if isTablet {
                        tabletPage(s, rank: i + 1)
                    } else {
                        NavigationLink(value: Route.series(s)) { page(s, rank: i + 1) }.buttonStyle(.plain)
                    }
                }
                .tag(i)
                .accessibilityIdentifier("home.hero.page.\(i)")
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        // Same card height for every source so switching pages never makes the top jump.
        .frame(height: height)
        .background { backdrop }
        .overlay(alignment: .bottomTrailing) { dots }
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: corner, style: .continuous)
                .strokeBorder(Color.white.opacity(0.22), style: StrokeStyle(lineWidth: 1.15, lineCap: .round, dash: [7, 5]))
        )
        // Shadow of a plain shape behind the card: radius stays inside horizontal pagePadding so the pager never clips it.
        .background { RoundedRectangle(cornerRadius: corner, style: .continuous).fill(Color.black.shadow(.drop(color: .black.opacity(isTablet ? 0.20 : 0.22), radius: isTablet ? 14 : 15, y: isTablet ? 8 : 14))).padding(1) }
        // Auto-advance; the timer restarts on every page change, so a manual swipe gets a full 6 s.
        .task(id: "\(picks.count)-\(index)") {
            guard picks.count > 1, Self.autoAdvance else { return }
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.5)) { index = (index + 1) % picks.count }
        }
    }

    /// `.hero`: 14 pt padding around a 112 pt 3:4 poster (landscape sites: 16:9 at 176 pt).
    private static let pad: CGFloat = 14
    private static let posterHeight: CGFloat = 112 * 4 / 3
    private static let landscapeHeight: CGFloat = pad + 99 + pad + 62
    private static let portraitHeight: CGFloat = pad + posterHeight + pad
    private var height: CGFloat {
        if layout.tier == .expanded { return 286 }
        if layout.tier == .medium { return 228 }
        return landscape ? Self.landscapeHeight : Self.portraitHeight
    }
    /// DEBUG `STV_HERO_STILL=1` (UI tests) disables auto-advance so taps never race a page change.
    static var autoAdvance: Bool {
        #if DEBUG
        if ProcessInfo.processInfo.environment["STV_HERO_STILL"] == "1" { return false }
        #endif
        return true
    }
    private var landscape: Bool { picks.first?.isLandscapeCover ?? false }

    /// Poster colours: the current cover blurred + 3-layer cinema vignette scrim.
    private var backdrop: some View {
        let url = picks.indices.contains(index) ? picks[index].cover : ""
        return ZStack {
            Color(red: 0.09, green: 0.10, blue: 0.12)
            CoverBackdrop(url: url).opacity(isTablet ? 0.92 : 0.90)
            LinearGradient(
                stops: [
                    .init(color: .black.opacity(isTablet ? 0.16 : 0.18), location: 0),
                    .init(color: .black.opacity(isTablet ? 0.42 : 0.45), location: 0.55),
                    .init(color: .black.opacity(isTablet ? 0.76 : 0.62), location: 1),
                ],
                startPoint: .topTrailing,
                endPoint: .bottomLeading
            )
        }
        .allowsHitTesting(false)
    }

    private func kicker(_ rank: Int) -> String { board.isEmpty ? "TOP \(rank)" : "\(board) · TOP \(rank)" }

    /// Ambient Glass Stage cinema hero for Medium / Expanded / Ultra tablets.
    private func tabletPage(_ s: HGSeries, rank: Int) -> some View {
        let prog = library.progress(for: s)
        let fav = library.isFavorite(s)
        let expanded = layout.tier == .expanded
        let posterW: CGFloat = landscape ? (expanded ? 320 : 230) : (expanded ? 168 : 132)
        let posterH: CGFloat = landscape ? posterW * 9 / 16 : posterW * 4 / 3
        let playTitle: String = {
            if let p = prog { return "继续第 \(p.episodeNumber) 集" }
            return "立即播放"
        }()

        return HStack(alignment: .center, spacing: expanded ? 28 : 20) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 5) {
                    YMDoodleSparkle().fill(YMTheme.lime).frame(width: 10, height: 10)
                    Text(kicker(rank))
                        .font(.system(size: 12, weight: .heavy)).tracking(0.6)
                        .foregroundStyle(YMTheme.lime)
                        .lineLimit(1)
                }
                Text(s.title)
                    .font(.system(size: expanded ? 28 : 23, weight: .bold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)
                let heatText = s.playCount > 0 ? DS.count(s.playCount) + (s.playCount < 10_000 ? " 热度" : "热度") : nil
                let meta = [DS.genres(s), DS.episodes(s), heatText]
                    .compactMap { $0 }.joined(separator: " · ")
                if !meta.isEmpty {
                    Text(meta)
                        .font(.system(size: 13.5, weight: .medium))
                        .foregroundStyle(.white.opacity(0.78))
                        .lineLimit(1)
                        .padding(.top, 8)
                }
                if !s.actors.isEmpty {
                    Text("主演 " + s.actors.prefix(4).joined(separator: " / "))
                        .font(.system(size: 12.5))
                        .foregroundStyle(.white.opacity(0.62))
                        .lineLimit(1)
                        .padding(.top, 4)
                }
                Spacer(minLength: 14)
                HStack(spacing: 10) {
                    NavigationLink(value: Route.play(s, prog?.episodeIndex ?? 0)) {
                        HStack(spacing: 7) {
                            Image(systemName: "play.fill").font(.system(size: 13, weight: .bold))
                            Text(playTitle).font(.system(size: 14.5, weight: .semibold))
                        }
                        .foregroundStyle(Color(red: 0.067, green: 0.075, blue: 0.090))
                        .padding(.horizontal, 22)
                        .frame(height: 44)
                        .background(Color.white.opacity(0.97))
                        .overlay(alignment: .bottomLeading) {
                            if let p = prog, p.fraction > 0.01 {
                                GeometryReader { g in
                                    ZStack(alignment: .leading) {
                                        YMTheme.lime.opacity(0.22)
                                        YMTheme.lime
                                            .frame(width: g.size.width * CGFloat(min(max(p.fraction, 0.06), 1)))
                                    }
                                }
                                .frame(height: 4)
                            }
                        }
                        .clipShape(Capsule())
                    }
                    .buttonStyle(PressScale(scale: 0.97))
                    .accessibilityLabel(playTitle)

                    NavigationLink(value: Route.series(s)) {
                        Text("详情")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 18)
                            .frame(height: 44)
                            .liquidGlassCapsule(tone: .clear, interactive: true, onMedia: true)
                    }
                    .buttonStyle(PressScale(scale: 0.96))

                    Button { DS.tick(); library.toggleFavorite(s) } label: {
                        Image(systemName: fav ? "heart.fill" : "heart")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(fav ? STVTheme.favorite : .white)
                            .frame(width: 44, height: 44)
                            .liquidGlass(Circle(), tone: .clear, interactive: true, onMedia: true)
                    }
                    .buttonStyle(PressScale(scale: 0.92))
                    .accessibilityLabel(fav ? "取消收藏" : "收藏")
                }
            }
            .frame(maxWidth: 540, maxHeight: .infinity, alignment: .leading)

            Spacer(minLength: 8)

            NavigationLink(value: Route.series(s)) {
                CoverView(url: s.cover, corner: 16, maxPixelSize: landscape ? CoverPixels.wide : CoverPixels.poster)
                    .frame(width: posterW, height: posterH)
                    .overlay(YMWobblyFrame(seed: UInt64(abs(s.id.hashValue) % 97 + 11), inset: -2).stroke(Color.white.opacity(0.45), lineWidth: 1.35))
                    .shadow(color: .black.opacity(0.42), radius: 18, y: 10)
                    .zoomSource(s.id, ns)
                    .accessibilityIdentifier("home.hero.cover")
            }
            .buttonStyle(PressScale(scale: 0.98))
        }
        .padding(.horizontal, expanded ? 30 : 22)
        .padding(.vertical, expanded ? 24 : 18)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder private func page(_ s: HGSeries, rank: Int) -> some View {
        if landscape {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top, spacing: 14) {
                    CoverView(url: s.cover, corner: 12, maxPixelSize: CoverPixels.wide)
                        .frame(width: 176, height: 99)
                        .zoomSource(s.id, ns)
                        .overlay(alignment: .bottomTrailing) { if let p = HomeFmt.progress(s) { HomeBadge(text: p).padding(5) } }
                        .accessibilityIdentifier("home.hero.cover")
                    VStack(alignment: .leading, spacing: 6) {
                        Text(kicker(rank)).font(.system(size: 11, weight: .heavy)).tracking(0.6)
                            .foregroundStyle(Color(red: 0.949, green: 0.710, blue: 0.361)).lineLimit(1)
                        Text(s.title).font(.system(size: 16, weight: .bold)).foregroundStyle(.white).lineLimit(3)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                let line = Fmt.meta(s)
                if !line.isEmpty { Text(line).font(.system(size: 12)).foregroundStyle(.white.opacity(0.74)).lineLimit(1).padding(.top, 9) }
                Spacer(minLength: 0)
                detailButton
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(14)
        } else {
            HStack(alignment: .top, spacing: 14) {
                CoverView(url: s.cover, corner: 12, maxPixelSize: CoverPixels.poster)
                    .frame(width: 112, height: 112 * 4 / 3)
                    .overlay {
                        LinearGradient(stops: [.init(color: .clear, location: 0.46), .init(color: .black.opacity(0.62), location: 1)],
                                       startPoint: .top, endPoint: .bottom)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .overlay(YMWobblyFrame(seed: UInt64(abs(s.id.hashValue) % 97 + 5), inset: -1.5).stroke(Color.white.opacity(0.42), lineWidth: 1.25))
                    .overlay(alignment: .bottomTrailing) {
                        if let e = DS.episodes(s) {
                            Text(e).font(.system(size: 11)).foregroundStyle(.white.opacity(0.9)).padding(.trailing, 8).padding(.bottom, 7)
                        }
                    }
                    .zoomSource(s.id, ns)
                info(s, rank: rank)
            }
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    private func info(_ s: HGSeries, rank: Int) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 4) {
                YMDoodleSparkle().fill(YMTheme.lime).frame(width: 9, height: 9)
                Text(kicker(rank)).font(.system(size: 11, weight: .heavy)).tracking(0.6)
                    .foregroundStyle(YMTheme.lime).lineLimit(1)
            }
            Text(s.title).font(.system(size: 20, weight: .bold)).foregroundStyle(.white).lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 6)
            let line1 = [DS.genres(s), s.episodeCount > 0 ? "\(s.episodeCount) 集" : nil].compactMap { $0 }.joined(separator: " · ")
            let line2 = s.playCount > 0 ? DS.count(s.playCount) + "热度" : (s.actors.isEmpty ? "" : "主演 " + s.actors.prefix(3).joined(separator: " / "))
            VStack(alignment: .leading, spacing: 0) {
                if !line1.isEmpty { Text(line1).lineLimit(1) }
                if !line2.isEmpty { Text(line2).lineLimit(1) }
            }
            .font(.system(size: 12)).foregroundStyle(.white.opacity(0.74))
            .lineSpacing(3)
            .padding(.top, 7)
            Spacer(minLength: 0)
            detailButton
        }
        .padding(.top, 3).padding(.bottom, 1)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var detailButton: some View {
        PillButtonLabel(title: "查看详情", icon: "play.fill", tone: .white, small: true)
    }

    private var dots: some View {
        HStack(spacing: 4) {
            ForEach(picks.indices, id: \.self) { i in
                Capsule().fill(i == index ? YMTheme.lime : Color.white.opacity(0.38)).frame(width: i == index ? 16 : 5, height: 5)
            }
        }
        .animation(DS.select, value: index)
        .padding(.trailing, isTablet ? 28 : 14).padding(.bottom, isTablet ? 18 : 14 + 1 + 13.5)
        .opacity(picks.count > 1 ? 1 : 0)
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("第 \(index + 1) 部，共 \(picks.count) 部")
        .accessibilityIdentifier("home.hero.dots")
    }
}

/// ui-redesign 10 `.cw`: horizontal 继续观看 cards on phones; equal-width 4-col (Expanded) / 2-col (Medium)
/// frameless cinema grid on tablets so the right edge stays 0px flush with the poster matrix below.
struct HomeContinueRow: View {
    @Environment(\.zoomNS) private var ns
    @Environment(\.layoutMetrics) private var layout
    let items: [WatchProgress]
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            DSSectionHeader(title: "继续观看", inset: layout.pagePadding) {
                NavigationLink(value: Route.history) { MoreLabel() }.buttonStyle(.plain).accessibilityIdentifier("home.more.继续观看")
            }
            if layout.continueGridCols > 0 {
                let cols = Array(repeating: GridItem(.flexible(), spacing: 14, alignment: .top), count: layout.continueGridCols)
                LazyVGrid(columns: cols, spacing: 14) {
                    ForEach(Array(items.prefix(layout.continueGridCols))) { p in
                        tabletContinueCard(p)
                    }
                }
                .padding(.horizontal, layout.pagePadding)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 10) {
                        ForEach(items) { p in
                            ZStack(alignment: .trailing) {
                                NavigationLink(value: Route.series(p.series)) { card(p) }
                                    .buttonStyle(PressScale(scale: 0.98))
                                    .contextMenu { Button("删除观看记录", systemImage: "trash", role: .destructive) { LibraryStore.shared.removeProgress(p.series) } }
                                NavigationLink(value: Route.play(p.series, p.episodeIndex)) { playButton }
                                    .buttonStyle(PressScale()).padding(.trailing, 12)
                                    .accessibilityLabel("继续播放 \(p.series.title)")
                            }
                        }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 2)
                }
                .frame(height: 94)
                .compatScrollBounceBasedOnSize(axes: .horizontal)
            }
        }
    }

    private func tabletContinueCard(_ p: WatchProgress) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .bottomTrailing) {
                NavigationLink(value: Route.series(p.series)) {
                    Color.clear
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .overlay {
                            if p.series.cover.isEmpty {
                                ZStack {
                                    LinearGradient(
                                        colors: [Color(red: 0.14, green: 0.16, blue: 0.21), Color(red: 0.08, green: 0.09, blue: 0.12)],
                                        startPoint: .topLeading, endPoint: .bottomTrailing
                                    )
                                    Image(systemName: "film")
                                        .font(.system(size: 24, weight: .light))
                                        .foregroundStyle(.white.opacity(0.30))
                                }
                            } else {
                                CoverView(url: p.series.cover, corner: 0, maxPixelSize: CoverPixels.wide)
                                    .zoomSource(p.series.id, ns)
                            }
                        }
                        .overlay {
                            LinearGradient(
                                stops: [
                                    .init(color: .black.opacity(0.04), location: 0),
                                    .init(color: .black.opacity(0.20), location: 0.54),
                                    .init(color: .black.opacity(0.68), location: 1),
                                ],
                                startPoint: .top, endPoint: .bottom
                            )
                        }
                        .overlay(alignment: .bottomLeading) {
                            GeometryReader { g in
                                ZStack(alignment: .leading) {
                                    Color.white.opacity(0.22)
                                    STVTheme.accent.frame(width: g.size.width * CGFloat(min(max(p.fraction, 0.04), 1)))
                                }
                            }
                            .frame(height: 3)
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(PressScale(scale: 0.98))
                .contextMenu { Button("删除观看记录", systemImage: "trash", role: .destructive) { LibraryStore.shared.removeProgress(p.series) } }

                NavigationLink(value: Route.play(p.series, p.episodeIndex)) {
                    Image(systemName: "play.fill")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white)
                        .offset(x: 1)
                        .frame(width: 36, height: 36)
                        .liquidGlass(Circle(), tone: .clear, interactive: true, onMedia: true)
                }
                .buttonStyle(PressScale(scale: 0.92))
                .padding(.trailing, 10)
                .padding(.bottom, 11)
                .accessibilityLabel("继续播放 \(p.series.title)")
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(p.series.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(STVTheme.text)
                    .lineLimit(1)
                Text(line(p))
                    .font(.system(size: 12))
                    .foregroundStyle(STVTheme.secondary)
                    .lineLimit(1)
            }
        }
    }

    private func card(_ p: WatchProgress) -> some View {
        HStack(spacing: 12) {
            CoverView(url: p.series.cover, corner: 10, maxPixelSize: CoverPixels.thumb).frame(width: 52, height: 52 * 4 / 3).zoomSource(p.series.id, ns)
            VStack(alignment: .leading, spacing: 0) {
                Text(p.series.title).font(.system(size: 15, weight: .semibold)).foregroundStyle(STVTheme.text).lineLimit(1)
                Text(line(p)).font(.system(size: 12)).foregroundStyle(STVTheme.secondary).lineLimit(1).padding(.top, 2)
                DSProgressBar(value: p.fraction).padding(.top, 9)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Color.clear.frame(width: 38, height: 38)
        }
        .padding(.leading, 10).padding(.trailing, 12).padding(.vertical, 10)
        .frame(width: 296)
        .dsCard(18)
    }

    private var playButton: some View {
        Image(systemName: "play.fill").font(.system(size: 13, weight: .bold)).foregroundStyle(STVTheme.bg)
            .offset(x: 1)
            .frame(width: 38, height: 38)
            .background(Circle().fill(STVTheme.text))
            .contentShape(Circle())
    }

    private func line(_ p: WatchProgress) -> String {
        if p.series.source.isLongVideo { return Fmt.resume(p) }
        let total = p.series.episodeCount
        return total > 0 ? "看到第 \(p.episodeNumber) 集 · 共 \(total) 集" : "看到第 \(p.episodeNumber) 集"
    }
}

/// ui-redesign 10/11: horizontal row on phones; borderless 4/5/6-column poster matrix for the primary tablet board.
struct HomeShelf: View {
    @Environment(\.layoutMetrics) private var layout
    let title: String
    let note: String?
    let items: [HGSeries]
    var more: BoardSpec? = nil
    var gridOnTablet: Bool = false
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            DSSectionHeader(title: title, note: note, inset: layout.pagePadding) {
                if let more {
                    NavigationLink(value: Route.board(more)) { MoreLabel() }.buttonStyle(.plain).accessibilityIdentifier("home.more.\(title)")
                }
            }
            if !items.isEmpty {
                let landscape = items.first?.isLandscapeCover ?? false
                if gridOnTablet && layout.tier != .compact && !landscape {
                    LazyVGrid(columns: layout.posterGridItems(spacing: 14), spacing: 18) {
                        ForEach(items) { s in
                            PosterCellView(series: s, subtitle: DS.shelfMeta(s))
                                .accessibilityIdentifier("home.card")
                        }
                    }
                    .padding(.horizontal, layout.pagePadding)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: layout.tier == .compact ? 10 : 14) {
                            ForEach(items) { s in
                                PosterCellView(series: s, subtitle: DS.shelfMeta(s))
                                    .frame(width: landscape ? (layout.tier == .compact ? 200 : 240) : layout.shelfCardWidth)
                                    .accessibilityIdentifier("home.card")
                            }
                        }
                        .padding(.horizontal, layout.pagePadding)
                    }
                    .compatScrollBounceBasedOnSize(axes: .horizontal)
                }
            }
        }
    }
}

struct HomeBadge: View {
    let text: String
    var body: some View {
        Text(text).font(.system(size: 10, weight: .semibold)).foregroundStyle(.white).monospacedDigit()
            .lineLimit(1).padding(.horizontal, 5).frame(height: 17)
            .background(.black.opacity(0.62), in: RoundedRectangle(cornerRadius: 5))
    }
}

/// Full list of one board, 3-column numbered grid with infinite paging.
struct BoardListView: View {
    let board: BoardSpec
    @StateObject private var pager = Pager()
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let e = pager.error, pager.items.isEmpty { ErrorStateView(error: e) { reload() } }
                else { NumberedGrid(items: pager.items, hasMore: pager.hasMore, loading: pager.isLoading) { pager.loadMore() } }
            }.padding(.vertical, 8)
        }
        .accessibilityIdentifier("screen.board")
        .dsNavigation(board.title)
        .task { if pager.items.isEmpty { reload() } }
    }
    private func reload() { let b = board; pager.reset { try await b.fetch($0, $1) } }
}
