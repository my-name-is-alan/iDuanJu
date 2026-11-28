import SwiftUI

/// 四个根页面 + 窄屏底部悬浮玻璃胶囊 / 平板宽屏左侧悬浮玻璃导航轨（系统 TabBar 隐藏）。
/// 导航栏只在根页面显示；进入详情 / 播放器等二级页面时隐藏。
struct RootTabView: View {
    struct TabItem: Identifiable, Equatable {
        let id: Int
        let title: String
        let symbol: String
    }

    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject private var musicPlayer = MusicPlayerEngine.shared
    @ObservedObject private var gate = SourceGate.shared
    @State private var tab: Int
    @State private var mountedTabs: Set<Int>
    @State private var paths = [NavigationPath(), NavigationPath(), NavigationPath(), NavigationPath(), NavigationPath(), NavigationPath()]

    static let musicTabId = 3
    static let profileTabId = 4
    static let searchTabId = 5

    static func visibleItems(showMusic: Bool) -> [TabItem] {
        var list: [TabItem] = [
            TabItem(id: 0, title: "首页", symbol: "house.fill"),
            TabItem(id: 1, title: "TVBox", symbol: "tv.fill"),
            TabItem(id: 2, title: "找剧", symbol: "square.grid.2x2.fill"),
        ]
        if showMusic {
            list.append(TabItem(id: musicTabId, title: "音乐", symbol: "music.note"))
        }
        list.append(TabItem(id: profileTabId, title: "我的", symbol: "person.crop.circle.fill"))
        return list
    }

    init() {
        let env = ProcessInfo.processInfo.environment
        let initial: Int
        if env["STV_HOME_TAB"] == "music" || env["STV_HOME_SRC"] == "music" {
            initial = Self.musicTabId
        } else if let raw = env["STV_TAB"], let idx = Int(raw), (0...Self.searchTabId).contains(idx) {
            initial = idx
        } else {
            initial = 0
        }
        _tab = State(initialValue: initial)
        _mountedTabs = State(initialValue: [initial])
    }
    var body: some View {
        GeometryReader { geo in
            let metrics = LayoutMetrics(width: geo.size.width, height: geo.size.height)
            let navItems = Self.visibleItems(showMusic: gate.showMusicTab)
            let showNav = paths[tab].isEmpty
            let showRail = showNav && metrics.useRail
            let showBottomBar = showNav && !metrics.useRail
            let rootBg = (tab == Self.musicTabId) ? YMTheme.base(colorScheme) : STVTheme.bg
            ZStack(alignment: .leading) {
                // Deterministic lazy-mounted tab container: avoids UIKit TabView(.toolbar(.hidden)) selection
                // desyncs on iOS 18/26 while keeping each visited tab's NavigationStack and scroll state alive.
                ZStack {
                    let railInset: CGFloat = metrics.useRail ? metrics.railWidth : 0
                    tabSlot(0, reserveBottomBar: !metrics.useRail, leadingRail: railInset) { HomeView() }
                    tabSlot(1, reserveBottomBar: !metrics.useRail, leadingRail: railInset) { TVBoxHubView() }
                    tabSlot(2, reserveBottomBar: !metrics.useRail, leadingRail: railInset) { CategoryView() }
                    if gate.showMusicTab {
                        tabSlot(Self.musicTabId, reserveBottomBar: !metrics.useRail, leadingRail: railInset) { MusicHomeView(isRootTab: true) }
                    }
                    tabSlot(Self.profileTabId, reserveBottomBar: !metrics.useRail, leadingRail: railInset) { ProfileView() }
                    tabSlot(Self.searchTabId, reserveBottomBar: !metrics.useRail, leadingRail: railInset) { SearchView() }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(rootBg.ignoresSafeArea())
                .tint(STVTheme.accent)
                .overlay(alignment: .bottom) {
                    VStack(spacing: 10) {
                        if !metrics.useRail && musicPlayer.shouldShowGlobalMiniBar {
                            MusicMiniPlayerBar()
                                .padding(.horizontal, 14)
                                .padding(.bottom, showBottomBar ? 0 : 10)
                                .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                        if showBottomBar {
                            GlassTabBar(selection: $tab, items: navItems) { i in
                                if i == tab { paths[i] = NavigationPath() }
                            }
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                    .animation(DS.select, value: showBottomBar)
                    .animation(DS.select, value: musicPlayer.shouldShowGlobalMiniBar)
                }
                .overlay(alignment: .bottomTrailing) {
                    if metrics.useRail && musicPlayer.shouldShowGlobalMiniBar {
                        MusicMiniPlayerBar()
                            .frame(maxWidth: 360)
                            .padding(.trailing, 20)
                            .padding(.bottom, 16)
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
                .compatOnChange(of: tab) { next in
                    mountedTabs.insert(next)
                }
                .compatOnChange(of: gate.showMusicTab) { on in
                    if !on && tab == Self.musicTabId { tab = 0 }
                }
                .compatOnChange(of: colorScheme) { _ in
                    StatusBarGate.shared.refreshAppearance()
                }
                Group {
                    if showRail {
                        GlassNavigationRail(selection: $tab, items: navItems, searchTag: Self.searchTabId) { i in
                            if i == tab { paths[i] = NavigationPath() }
                        }
                        .transition(.move(edge: .leading).combined(with: .opacity))
                    }
                }
                .animation(DS.select, value: showRail)
            }
            .environment(\.layoutMetrics, metrics)
        }
        .fullScreenCover(isPresented: $musicPlayer.showFullPlayer) {
            MusicNowPlayingView()
        }
        .onAppear {
            Router.handler = { r in paths[tab].append(r) }
            #if DEBUG
            if ProcessInfo.processInfo.environment["STV_TD_OPEN"] == "1" { Router.push(.tdlib) }
            #endif
        }
        // Cold start: restore a saved TDLib login (no network login, nothing is sent).
        .task { await TDLibService.shared.bootstrap() }
    }

    @ViewBuilder
    private func tabSlot<V: View>(_ idx: Int, reserveBottomBar: Bool, leadingRail: CGFloat, @ViewBuilder content: () -> V) -> some View {
        if mountedTabs.contains(idx) || tab == idx {
            let active = tab == idx
            Stack(path: $paths[idx], reserveBottomBar: reserveBottomBar, leadingRail: leadingRail, content: content)
                .opacity(active ? 1 : 0)
                .zIndex(active ? 1 : 0)
                .allowsHitTesting(active)
                .accessibilityHidden(!active)
        }
    }
}

private struct Stack<V: View>: View {
    @Environment(\.colorScheme) private var colorScheme
    @Binding var path: NavigationPath
    var reserveBottomBar: Bool = true
    var leadingRail: CGFloat = 0
    @ViewBuilder var content: V
    @Namespace private var ns
    var body: some View {
        NavigationStack(path: $path) {
            content
                .environment(\.railLeadingInset, leadingRail)
                .padding(.leading, leadingRail)
                .toolbar(.hidden, for: .tabBar)
                .toolbarColorScheme(colorScheme, for: .navigationBar)
                .safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: reserveBottomBar ? GlassTabBar.clearance : 0) }
                .routes()
        }.environment(\.zoomNS, ns)
    }
}

struct GlassTabBar: View {
    static let clearance: CGFloat = 72
    @Binding var selection: Int
    let items: [RootTabView.TabItem]
    var searchTag: Int? = nil
    var onReselect: (Int) -> Void = { _ in }
    @Namespace private var ns
    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 2) {
                ForEach(items) { item in
                    NavBarItem(id: item.id, title: item.title, symbol: item.symbol, on: item.id == selection, height: 50, ns: ns) { pick(item.id) }
                        .accessibilityIdentifier("tab.\(item.id)")
                }
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 5)
            .background(YMDockSurface(shape: Capsule(style: .continuous)))
            .animation(.spring(response: 0.34, dampingFraction: 0.78), value: selection)

            if let searchTag {
                NavSearchButton(on: selection == searchTag, size: 60) { pick(searchTag) }
            }
        }
        .frame(maxWidth: 440)
        .padding(.horizontal, 16)
        .padding(.bottom, -6)
    }

    private func pick(_ i: Int) {
        if i == selection { onReselect(i) } else { selection = i }
    }
}

/// One destination of the Yimeng-faithful tab bar / rail: hand-drawn double-pass crayon glyph + sliding obsidian/cream pill.
private struct NavBarItem: View {
    let id: Int
    let title: String
    let symbol: String
    let on: Bool
    let height: CGFloat
    let ns: Namespace.ID
    let action: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                if let glyph = YMTabGlyph(rawValue: id) {
                    YMTabIcon(glyph: glyph, size: 21, active: on)
                } else {
                    Image(systemName: symbol).font(.system(size: 17, weight: on ? .semibold : .regular)).frame(height: 21)
                }
                Text(title).font(.system(size: 10, weight: on ? .bold : .medium))
            }
            .foregroundStyle(on ? YMTheme.accent(scheme) : YMTheme.fg(scheme).opacity(0.68))
            .frame(maxWidth: .infinity).frame(height: height)
            .background {
                if on {
                    Capsule(style: .continuous)
                        .fill(scheme == .dark ? Color.white.opacity(0.11) : Color.black.opacity(0.08))
                        .matchedGeometryEffect(id: "pill", in: ns)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(PressScale(scale: 0.94))
        .accessibilityLabel(title)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

private struct NavSearchButton: View {
    let on: Bool
    let size: CGFloat
    var accessibilityID: String = "tab.search"
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            YMTabIcon(glyph: .search, size: 20, active: on)
                .frame(width: size, height: size)
                .background(YMDockSurface(shape: Circle()))
                .contentShape(Circle())
        }
        .buttonStyle(PressScale(scale: 0.94))
        .accessibilityLabel("搜索")
        .accessibilityIdentifier(accessibilityID)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// Vertical floating 72pt Yimeng-styled navigation rail for Expanded iPad / Stage Manager widths (>= 840pt).
struct GlassNavigationRail: View {
    @Binding var selection: Int
    let items: [RootTabView.TabItem]
    var searchTag: Int? = nil
    var onReselect: (Int) -> Void = { _ in }
    @Namespace private var ns
    var body: some View {
        VStack(spacing: 12) {
            if let searchTag {
                NavSearchButton(on: selection == searchTag, size: 48, accessibilityID: "home.search") { pick(searchTag) }
            }
            Spacer(minLength: 8)
            VStack(spacing: 6) {
                ForEach(items) { item in
                    NavBarItem(id: item.id, title: item.title, symbol: item.symbol, on: item.id == selection, height: 52, ns: ns) { pick(item.id) }
                        .accessibilityIdentifier("tab.\(item.id)")
                }
            }
            .frame(width: 56)
            .padding(.vertical, 6)
            .padding(.horizontal, 4)
            .background(YMDockSurface(shape: RoundedRectangle(cornerRadius: 30, style: .continuous)))
            .animation(.spring(response: 0.34, dampingFraction: 0.78), value: selection)
            Spacer(minLength: 8)
            if searchTag != nil {
                Color.clear.frame(height: 48)
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .frame(width: 72, alignment: .leading)
        .padding(.vertical, 14)
    }

    private func pick(_ i: Int) {
        if i == selection { onReselect(i) } else { selection = i }
    }
}
