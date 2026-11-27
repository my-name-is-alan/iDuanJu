import SwiftUI

/// 四个根页面 + 窄屏底部悬浮玻璃胶囊 / 平板宽屏左侧悬浮玻璃导航轨（系统 TabBar 隐藏）。
/// 导航栏只在根页面显示；进入详情 / 播放器等二级页面时隐藏。
struct RootTabView: View {
    @Environment(\.colorScheme) private var colorScheme
    @State private var tab: Int
    @State private var mountedTabs: Set<Int>
    @State private var paths = [NavigationPath(), NavigationPath(), NavigationPath(), NavigationPath(), NavigationPath()]
    @State private var tabBarHidden = false
    @State private var restoreTask: Task<Void, Never>?
    static let items: [(String, String)] = [("首页", "house.fill"), ("TVBox", "tv.fill"),
                                            ("找剧", "square.grid.2x2.fill"), ("我的", "person.crop.circle.fill")]

    init() {
        let initial = Int(ProcessInfo.processInfo.environment["STV_TAB"] ?? "") ?? 0
        _tab = State(initialValue: initial)
        _mountedTabs = State(initialValue: [initial])
    }
    var body: some View {
        GeometryReader { geo in
            let metrics = LayoutMetrics(width: geo.size.width, height: geo.size.height)
            let showNav = paths[tab].isEmpty
            let showRail = showNav && metrics.useRail
            let showBottomBar = showNav && !metrics.useRail && !tabBarHidden
            ZStack(alignment: .leading) {
                // Deterministic lazy-mounted tab container: avoids UIKit TabView(.toolbar(.hidden)) selection
                // desyncs on iOS 18/26 while keeping each visited tab's NavigationStack and scroll state alive.
                ZStack {
                    let railInset: CGFloat = metrics.useRail ? metrics.railWidth : 0
                    tabSlot(0, reserveBottomBar: !metrics.useRail, leadingRail: railInset) { HomeView() }
                    tabSlot(1, reserveBottomBar: !metrics.useRail, leadingRail: railInset) { TVBoxHubView() }
                    tabSlot(2, reserveBottomBar: !metrics.useRail, leadingRail: railInset) { CategoryView() }
                    tabSlot(3, reserveBottomBar: !metrics.useRail, leadingRail: railInset) { ProfileView() }
                    tabSlot(4, reserveBottomBar: !metrics.useRail, leadingRail: railInset) { SearchView() }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(STVTheme.bg.ignoresSafeArea())
                .tint(STVTheme.accent)
                .simultaneousGesture(
                    DragGesture(minimumDistance: 8)
                        .onChanged { value in
                            guard showNav && !metrics.useRail else { return }
                            let dy = value.translation.height
                            let dx = value.translation.width
                            guard abs(dy) > abs(dx) * 1.2 else { return }
                            if dy < -14 {
                                restoreTask?.cancel()
                                if !tabBarHidden { tabBarHidden = true }
                            } else if dy > 14 {
                                restoreTask?.cancel()
                                if tabBarHidden { tabBarHidden = false }
                            }
                        }
                        .onEnded { _ in
                            guard tabBarHidden else { return }
                            restoreTask?.cancel()
                            restoreTask = Task { @MainActor in
                                try? await Task.sleep(nanoseconds: 450_000_000)
                                guard !Task.isCancelled else { return }
                                tabBarHidden = false
                            }
                        }
                )
                .overlay(alignment: .bottom) {
                    Group {
                        if showBottomBar {
                            GlassTabBar(selection: $tab, items: Self.items) { i in
                                if i == tab { paths[i] = NavigationPath() }
                            }
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                    .animation(DS.select, value: showBottomBar)
                }
                .compatOnChange(of: tab) { next in
                    mountedTabs.insert(next)
                    restoreTask?.cancel()
                    tabBarHidden = false
                }
                .compatOnChange(of: showNav) { visible in
                    if !visible {
                        restoreTask?.cancel()
                        tabBarHidden = false
                    }
                }
                .compatOnChange(of: colorScheme) { _ in
                    StatusBarGate.shared.refreshAppearance()
                }
                Group {
                    if showRail {
                        GlassNavigationRail(selection: $tab, items: Self.items, searchTag: 4) { i in
                            if i == tab { paths[i] = NavigationPath() }
                        }
                        .transition(.move(edge: .leading).combined(with: .opacity))
                    }
                }
                .animation(DS.select, value: showRail)
            }
            .environment(\.layoutMetrics, metrics)
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
    let items: [(String, String)]
    var searchTag: Int? = nil
    var onReselect: (Int) -> Void = { _ in }
    @Namespace private var ns
    var body: some View {
        LiquidGlassGroup(spacing: 10) {
            HStack(spacing: 10) {
                HStack(spacing: 4) {
                    ForEach(items.indices, id: \.self) { i in
                        NavBarItem(title: items[i].0, symbol: items[i].1, on: i == selection, height: 48, ns: ns) { pick(i) }
                            .accessibilityIdentifier("tab.\(i)")
                    }
                }
                .padding(6)
                .liquidGlassCapsule(interactive: true)
                .animation(DS.select, value: selection)

                if let searchTag {
                    NavSearchButton(on: selection == searchTag, size: 60) { pick(searchTag) }
                }
            }
        }
        .frame(maxWidth: 420)
        .padding(.horizontal, 16)
        .padding(.bottom, -8)
    }

    private func pick(_ i: Int) {
        if i == selection { onReselect(i) } else { selection = i }
    }
}

/// One destination of the tab bar / rail; the selection thumb slides between neighbours.
private struct NavBarItem: View {
    let title: String
    let symbol: String
    let on: Bool
    let height: CGFloat
    let ns: Namespace.ID
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: symbol).font(.system(size: 17, weight: on ? .semibold : .regular)).frame(height: 20)
                Text(title).font(.system(size: 11, weight: on ? .semibold : .medium))
            }
            .foregroundStyle(on ? STVTheme.accent : STVTheme.secondary)
            .frame(maxWidth: .infinity).frame(height: height)
            .background {
                if on {
                    Capsule(style: .continuous).fill(STVTheme.glassBorder).matchedGeometryEffect(id: "pill", in: ns)
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
            Image(systemName: "magnifyingglass")
                .font(.system(size: 19, weight: on ? .bold : .semibold))
                .foregroundStyle(on ? STVTheme.accent : STVTheme.text)
                .frame(width: size, height: size)
                .liquidGlass(Circle(), interactive: true)
                .contentShape(Circle())
        }
        .buttonStyle(PressScale(scale: 0.94))
        .accessibilityLabel("搜索")
        .accessibilityIdentifier(accessibilityID)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// Vertical floating 72pt Liquid Glass navigation rail for Expanded iPad / Stage Manager widths (>= 840pt).
struct GlassNavigationRail: View {
    @Binding var selection: Int
    let items: [(String, String)]
    var searchTag: Int? = nil
    var onReselect: (Int) -> Void = { _ in }
    @Namespace private var ns
    var body: some View {
        LiquidGlassGroup(spacing: 12) {
            VStack(spacing: 12) {
                if let searchTag {
                    NavSearchButton(on: selection == searchTag, size: 48, accessibilityID: "home.search") { pick(searchTag) }
                }
                Spacer(minLength: 8)
                VStack(spacing: 6) {
                    ForEach(items.indices, id: \.self) { i in
                        NavBarItem(title: items[i].0, symbol: items[i].1, on: i == selection, height: 52, ns: ns) { pick(i) }
                            .accessibilityIdentifier("tab.\(i)")
                    }
                }
                .frame(width: 56)
                .padding(.vertical, 6)
                .padding(.horizontal, 4)
                .liquidGlass(corner: 30, interactive: true)
                .animation(DS.select, value: selection)
                Spacer(minLength: 8)
                if searchTag != nil {
                    Color.clear.frame(height: 48)
                }
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
