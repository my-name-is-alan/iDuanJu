import SwiftUI

/// Standalone Yimeng-styled Music root tab (`发现 · 排行 · 典藏 · 曲库`), mounted directly in `RootTabView`'s
/// bottom tab bar & iPad left navigation rail with 100% direct no-proxy upstream connections.
struct MusicHomeView: View {
    enum SubTab: String, CaseIterable, Hashable {
        case discover = "发现"
        case charts = "排行"
        case archive = "典藏"
        case library = "曲库"
    }

    var isRootTab: Bool = true
    @Environment(\.colorScheme) private var scheme
    @Environment(\.layoutMetrics) private var layout
    @Environment(\.railLeadingInset) private var railInset
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = MusicStore.shared
    @ObservedObject private var player = MusicPlayerEngine.shared
    @ObservedObject private var arbiter = AppAudioArbiter.shared

    @State private var subTab: SubTab = .discover
    @State private var chartPlatform: MusicSourceKind? = .netease
    @State private var charts: [MusicChart] = []
    @State private var dailyTracks: [MusicTrack] = []
    @State private var dailyIsPersonalized = false
    @State private var recommendedPlaylists: [MusicPlaylist] = []
    @State private var recPlaylistsPersonalized = false
    @State private var fmTracks: [MusicTrack] = []
    @State private var fmIsPersonalized = false
    @State private var fmSourceLabel = "私人 FM"
    @State private var loadingFM = false
    @State private var heroIndex = 0
    @State private var loadingCharts = false
    @State private var openedChart: MusicChart?
    struct YueqianPayload: Identifiable {
        let id = UUID()
        let track: MusicTrack
        let coverURL: String
        let hook: String
    }

    @State private var searchText = ""
    @State private var searchResults: [MusicTrack] = []
    @State private var searching = false
    @State private var presentedYueqian: YueqianPayload?
    @State private var yueqianHook: String = "谁都只得那双手 靠拥抱亦难任你拥有"
    @State private var toastText: String?
    @State private var archiveFocus: Double = 0
    @State private var archiveDrag: CGFloat = 0
    @State private var archiveLastIdx: Int = 0
    @State private var librarySegment = 0
    @State private var showAddNASSheet = false
    @State private var showNewPlaylistAlert = false
    @State private var newPlaylistName = ""
    @State private var editingCookieSource: MusicSourcePref?
    @State private var cookieDraft = ""
    @State private var openedPlaylist: MusicPlaylist?

    private var pad: CGFloat { layout.pagePadding }

    var body: some View {
        ZStack(alignment: .top) {
            YMTheme.base(scheme)
                .padding(.leading, -railInset)
                .ignoresSafeArea()
            if let cover = player.currentTrack?.coverURL.nilIfEmpty ?? charts.first?.coverURLs.first {
                MusicHomeAmbientBackdrop(coverURL: cover, isDark: scheme == .dark)
                    .equatable()
                    .padding(.leading, -railInset)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }

            VStack(spacing: 0) {
                if !isRootTab {
                    HStack(spacing: 12) {
                        Button { dismiss() } label: {
                            Image(systemName: "chevron.left")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(YMTheme.fg(scheme))
                                .frame(width: 38, height: 38)
                                .liquidGlassCapsule(interactive: true)
                        }
                        .buttonStyle(GlassPress())
                        Text("音乐")
                            .font(.system(size: 20, weight: .heavy))
                            .foregroundStyle(YMTheme.fg(scheme))
                        Spacer()
                    }
                    .padding(.horizontal, pad)
                    .padding(.top, 8)
                    .padding(.bottom, 2)
                }

                // Yimeng signature DoodleTabs directly at top (zero double top bar)
                HStack(spacing: 10) {
                    YMDoodleTabs(items: SubTab.allCases, selection: $subTab) { $0.rawValue }
                    Spacer(minLength: 4)
                    Button { Router.push(.musicSettings) } label: {
                        Image(systemName: "gearshape")
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(YMTheme.secondary(scheme))
                            .frame(width: 36, height: 36)
                            .background(Circle().fill(YMTheme.fill(scheme)))
                    }
                    .buttonStyle(GlassPress())
                    .accessibilityLabel("音乐与音源设置")
                }
                .padding(.horizontal, pad)
                .padding(.top, isRootTab ? 10 : 6)
                .padding(.bottom, 6)

                Group {
                    switch subTab {
                    case .discover: discoverScroll
                    case .charts: chartsScroll
                    case .archive: archiveStage
                    case .library: libraryScroll
                    }
                }
            }

            if let toastText {
                Text(toastText)
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(scheme == .dark ? .black : .white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Capsule().fill(scheme == .dark ? YMTheme.lime : Color.black))
                    .padding(.top, 52)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task {
            syncChartPlatformWithEnabledSources()
            if charts.isEmpty || recommendedPlaylists.isEmpty { await reloadCharts(force: false) }
            if store.hasAnyLoggedInSource && store.cloudPlaylists.isEmpty {
                _ = await store.syncCloudPlaylists()
            }
        }
        .compatOnChange(of: enabledSourcesFingerprint) { _ in
            syncChartPlatformWithEnabledSources()
            let allowSet = Set(enabledChartSources)
            charts = charts.filter { allowSet.contains($0.source) }
            recommendedPlaylists = recommendedPlaylists.filter { $0.source.map { allowSet.contains($0) } ?? true }
            dailyTracks = dailyTracks.filter { allowSet.contains($0.source) }
            fmTracks = fmTracks.filter { allowSet.contains($0.source) }
            heroIndex = 0
            Task { await reloadCharts(force: true) }
        }
        .compatOnChange(of: store.loginFingerprint) { _ in
            Task {
                await reloadCharts(force: true)
                if store.hasAnyLoggedInSource {
                    _ = await store.syncCloudPlaylists()
                }
            }
        }
        .task(id: yueqianTrack?.id) {
            guard let t = yueqianTrack else { return }
            let lrc = await MusicProviderHub.shared.fetchLyrics(
                for: t,
                lrclibEnabled: store.settings.lrclibEnabled
            )
            if let hook = MusicLyricsParser.pickYueqianHook(from: lrc) {
                yueqianHook = hook
            }
        }
        .compatOnChange(of: chartPlatform) { _ in
            Task { await reloadCharts(force: false) }
        }
        .sheet(item: $openedChart) { chart in
            MusicTrackListSheet(
                title: chart.name,
                subtitle: chart.updatedLabel,
                sourceLabel: chart.source.label,
                tracks: chart.tracks,
                canClone: true,
                onClone: {
                    let res = store.cloneChartAsPlaylist(name: chart.name, tracks: chart.tracks)
                    showToast("已克隆到本地歌单：导入 \(res.added) 首\(res.skipped > 0 ? "，跳过 \(res.skipped) 首重复" : "")")
                }
            )
        }
        .sheet(item: $openedPlaylist) { pl in
            MusicTrackListSheet(
                title: pl.name,
                subtitle: pl.subtitle,
                sourceLabel: "本地歌单",
                tracks: pl.tracks,
                canClone: false,
                onClone: nil
            )
        }
        .sheet(item: $presentedYueqian) { payload in
            YueqianSheetView(track: payload.track, coverURL: payload.coverURL, lyricHook: payload.hook)
        }
        .sheet(isPresented: $showAddNASSheet) {
            MusicAddNASSheet { cfg in
                store.saveNAS(cfg)
                showToast("已保存 \(cfg.kind.label) 直连配置")
                Task {
                    let tracks = await MusicProviderHub.shared.fetchNASTracks(config: cfg)
                    store.setNASTracks(tracks)
                    if !tracks.isEmpty { showToast("已直连 \(cfg.name)：加载 \(tracks.count) 首曲目") }
                }
            }
        }
        .sheet(item: $editingCookieSource) { src in
            DSSheet("配置 \(src.kind.fullName) Cookie", subtitle: "可选：仅存于本机，用于解锁无损/会员直链，无任何服务端中转") {
                VStack(alignment: .leading, spacing: 14) {
                    TextEditor(text: $cookieDraft)
                        .font(.system(size: 13, design: .monospaced))
                        .frame(height: 120)
                        .padding(8)
                        .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    HStack(spacing: 12) {
                        Button("清除") {
                            store.setSourceCookie(src.id, cookie: nil)
                            editingCookieSource = nil
                            showToast("已清除本机 Cookie")
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(YMTheme.fill(scheme), in: Capsule())
                        Button("保存") {
                            store.setSourceCookie(src.id, cookie: cookieDraft)
                            editingCookieSource = nil
                            showToast("已保存至本机存储")
                        }
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(scheme == .dark ? .black : .white)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Capsule().fill(YMTheme.fg(scheme)))
                    }
                    Spacer()
                }
                .padding(20)
            }
        }
        .alert("新建本地歌单", isPresented: $showNewPlaylistAlert) {
            TextField("歌单名称", text: $newPlaylistName)
            Button("取消", role: .cancel) { newPlaylistName = "" }
            Button("创建") {
                store.createPlaylist(name: newPlaylistName)
                newPlaylistName = ""
                showToast("已创建本地歌单")
            }
        }
    }

    // MARK: - 1. 发现 (Discover: Quick Search + Hero + 今日乐签 + 每日推荐 + 私人 FM + 最近常听)

    private var enabledChartSources: [MusicSourceKind] {
        store.enabledOnlineSources.filter { $0 == .netease || $0 == .qq || $0 == .kugou || $0 == .qishui }
    }

    private var enabledSourcesFingerprint: String {
        enabledChartSources.map(\.rawValue).joined(separator: ",")
    }

    private var visibleCharts: [MusicChart] {
        let allow = Set(enabledChartSources)
        return charts.filter { allow.contains($0.source) }
    }

    private var visibleRecommendedPlaylists: [MusicPlaylist] {
        let allow = Set(enabledChartSources)
        return recommendedPlaylists.filter { $0.source.map { allow.contains($0) } ?? true }
    }

    private var searchPlaceholderText: String {
        var names = enabledChartSources.map(\.label)
        if !store.nasConfigs.isEmpty { names.append("NAS") }
        if names.isEmpty { return "请先在设置中启用音乐源" }
        return names.count > 1 ? "聚合搜歌（\(names.joined(separator: " / "))）" : "搜歌（\(names[0])）"
    }

    private var heroTracks: [MusicTrack] {
        let allow = Set(enabledChartSources)
        let pool = dailyTracks.filter { allow.isEmpty || allow.contains($0.source) }
            + visibleCharts.flatMap(\.tracks)
            + store.recents
        var seen = Set<String>()
        var picked: [MusicTrack] = []
        for t in pool where !t.coverURL.isEmpty && seen.insert(t.id).inserted {
            picked.append(t)
            if picked.count >= 5 { break }
        }
        if picked.isEmpty, let first = pool.first { return [first] }
        return picked
    }

    private func syncChartPlatformWithEnabledSources() {
        let enabled = enabledChartSources
        if enabled.isEmpty {
            chartPlatform = nil
        } else if let cur = chartPlatform {
            if !enabled.contains(cur) { chartPlatform = enabled.first }
        } else if enabled.count == 1 {
            chartPlatform = enabled.first
        }
    }

    private var yueqianTrack: MusicTrack? {
        let base = store.favorites.first(where: { !$0.coverURL.isEmpty })
            ?? store.recents.first(where: { !$0.coverURL.isEmpty })
            ?? dailyTracks.first(where: { !$0.coverURL.isEmpty })
            ?? visibleCharts.first?.tracks.first
            ?? store.favorites.first
            ?? store.recents.first
        return base
    }

    private func resolvedCover(for track: MusicTrack) -> String {
        if !track.coverURL.isEmpty { return track.coverURL }
        for c in visibleCharts {
            if let hit = c.tracks.first(where: { ($0.id == track.id || $0.title == track.title) && !$0.coverURL.isEmpty }) {
                return hit.coverURL
            }
        }
        return visibleCharts.first?.coverURLs.first ?? ""
    }

    private var discoverScroll: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                // Multi-source instant search bar (client-side parallel search)
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(YMTheme.secondary(scheme))
                    TextField(
                        "",
                        text: $searchText,
                        prompt: Text(searchPlaceholderText).foregroundColor(YMTheme.secondary(scheme))
                    )
                    .font(.system(size: 14.5))
                    .foregroundStyle(YMTheme.fg(scheme))
                    .submitLabel(.search)
                    .onSubmit { Task { await runSearch() } }
                    if !searchText.isEmpty {
                        Button {
                            searchText = ""
                            searchResults = []
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(YMTheme.secondary(scheme))
                        }
                        .buttonStyle(.plain)
                    }
                    Button {
                        Task { await runSearch() }
                    } label: {
                        Text(searching ? "搜…" : "搜索")
                            .font(.system(size: 12.5, weight: .bold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .padding(.horizontal, 12)
                            .frame(height: 28)
                            .background(Capsule().fill(YMTheme.fg(scheme)))
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 14)
                .frame(height: 44)
                .background(Capsule().fill(YMTheme.fill(scheme)))

                if !searchResults.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("聚合搜索结果（\(searchResults.count)）")
                                .font(.system(size: 16, weight: .bold))
                            Spacer()
                            Button("清空") { searchResults = []; searchText = "" }
                                .font(.system(size: 12.5))
                                .foregroundStyle(YMTheme.secondary(scheme))
                        }
                        ForEach(searchResults.prefix(20)) { t in
                            MusicTrackRow(track: t, isCurrent: player.currentTrack?.id == t.id) {
                                player.play(track: t, in: searchResults, openFull: false)
                            }
                        }
                    }
                } else {
                    // Swipeable Hero Spotlight Carousel (left/right swipe across up to 5 picks)
                    if !heroTracks.isEmpty {
                        heroCarousel(heroTracks)
                    } else if loadingCharts {
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .fill(YMTheme.fill(scheme))
                            .frame(height: 184)
                            .overlay { ProgressView().tint(YMTheme.accent(scheme)) }
                    }

                    // 暖纸撕边「今日乐签」 (YueqianHomeTicket)
                    if let yTrack = yueqianTrack {
                        Button {
                            presentedYueqian = YueqianPayload(
                                track: yTrack,
                                coverURL: resolvedCover(for: yTrack),
                                hook: yueqianHook
                            )
                        } label: {
                            yueqianTicket(yTrack)
                        }
                        .buttonStyle(.plain)
                    }

                    // 每日推荐 + 私人 FM (Yimeng dashed cards)
                    HStack(spacing: 12) {
                        dailyMixCard
                        fmCard
                    }

                    // 推荐歌单 (横向精选/个性推荐歌单卡片流)
                    if !visibleRecommendedPlaylists.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(alignment: .firstTextBaseline) {
                                HStack(spacing: 6) {
                                    YMDoodleSparkle()
                                        .fill(YMTheme.accent(scheme))
                                        .frame(width: 11, height: 11)
                                    VStack(alignment: .leading, spacing: 2) {
                                        HStack(spacing: 6) {
                                            Text("推荐歌单")
                                                .font(.system(size: 19, weight: .bold))
                                            if recPlaylistsPersonalized {
                                                Text("个性")
                                                    .font(.system(size: 9.5, weight: .black))
                                                    .foregroundStyle(scheme == .dark ? .black : .white)
                                                    .padding(.horizontal, 5)
                                                    .padding(.vertical, 1.5)
                                                    .background(Capsule().fill(YMTheme.accent(scheme)))
                                            }
                                        }
                                        YMScribbleUnderline()
                                            .stroke(YMTheme.accent(scheme).opacity(0.68), style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
                                            .frame(width: 30, height: 4)
                                    }
                                }
                                Spacer()
                                Button {
                                    Task { await reloadCharts(force: true) }
                                } label: {
                                    Label("换一批", systemImage: "arrow.clockwise")
                                        .font(.system(size: 12.5))
                                        .foregroundStyle(YMTheme.secondary(scheme))
                                }
                                .buttonStyle(.plain)
                            }
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 12) {
                                    ForEach(visibleRecommendedPlaylists) { pl in
                                        Button {
                                            Router.push(.musicPlaylist(pl))
                                        } label: {
                                            VStack(alignment: .leading, spacing: 6) {
                                                ZStack(alignment: .bottomLeading) {
                                                    YMCoverImage(url: pl.displayCoverURL, corner: 14)
                                                        .frame(width: 136, height: 136)
                                                    LinearGradient(
                                                        colors: [.clear, .black.opacity(0.62)],
                                                        startPoint: .center,
                                                        endPoint: .bottom
                                                    )
                                                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                                    HStack(spacing: 4) {
                                                        if let badge = pl.playCountText, !badge.isEmpty {
                                                            Text(badge)
                                                                .font(.system(size: 10, weight: .bold))
                                                                .foregroundStyle(.white)
                                                                .padding(.horizontal, 6)
                                                                .padding(.vertical, 2)
                                                                .background(Capsule().fill(Color.black.opacity(0.55)))
                                                        }
                                                        Spacer(minLength: 2)
                                                        if let src = pl.source {
                                                            Text(src.label)
                                                                .font(.system(size: 9.5, weight: .bold))
                                                                .foregroundStyle(.black)
                                                                .padding(.horizontal, 5)
                                                                .padding(.vertical, 2)
                                                                .background(Capsule().fill(YMTheme.lime))
                                                        }
                                                    }
                                                    .padding(7)
                                                }
                                                .frame(width: 136, height: 136)
                                                Text(pl.name)
                                                    .font(.system(size: 13, weight: .semibold))
                                                    .foregroundStyle(YMTheme.fg(scheme))
                                                    .lineLimit(2)
                                                    .multilineTextAlignment(.leading)
                                                    .frame(width: 136, alignment: .leading)
                                            }
                                            .frame(width: 136, alignment: .topLeading)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                    }

                    // 为你推荐 (首页直出 4 首推荐歌曲预览)
                    if !dailyTracks.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(alignment: .firstTextBaseline) {
                                HStack(spacing: 6) {
                                    YMDoodleSparkle()
                                        .fill(YMTheme.accent(scheme))
                                        .frame(width: 11, height: 11)
                                    Text(dailyIsPersonalized ? "为你推荐" : "今日精选推荐")
                                        .font(.system(size: 19, weight: .bold))
                                }
                                Spacer()
                                Button("查看全部 \(dailyTracks.count) 首 ›") {
                                    Router.push(.musicDailyRec(dailyTracks))
                                }
                                .font(.system(size: 13))
                                .foregroundStyle(YMTheme.secondary(scheme))
                            }
                            VStack(spacing: 4) {
                                ForEach(Array(dailyTracks.prefix(4).enumerated()), id: \.element.id) { idx, t in
                                    MusicTrackRow(rank: idx + 1, track: t, list: dailyTracks)
                                }
                            }
                        }
                    }

                    // 权威榜单速览（严格仅展示已启用音源的榜单）
                    if !visibleCharts.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(alignment: .firstTextBaseline) {
                                HStack(spacing: 6) {
                                    YMDoodleSparkle()
                                        .fill(YMTheme.accent(scheme))
                                        .frame(width: 11, height: 11)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(enabledChartSources.count > 1 ? "权威多源榜单" : "\(enabledChartSources.first?.label ?? "音乐")实时榜单")
                                            .font(.system(size: 19, weight: .bold))
                                        YMScribbleUnderline()
                                            .stroke(YMTheme.accent(scheme).opacity(0.68), style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
                                            .frame(width: 30, height: 4)
                                    }
                                }
                                Spacer()
                                Button("全部榜单 ›") { subTab = .charts }
                                    .font(.system(size: 13.5))
                                    .foregroundStyle(YMTheme.secondary(scheme))
                            }
                            ForEach(visibleCharts.prefix(2)) { c in
                                chartCard(c)
                            }
                        }
                    }

                    // 最近常听
                    if !store.recents.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("最近播放")
                                .font(.system(size: 19, weight: .bold))
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 12) {
                                    ForEach(store.recents.prefix(10)) { t in
                                        Button {
                                            player.play(track: t, in: store.recents)
                                        } label: {
                                            VStack(alignment: .leading, spacing: 6) {
                                                YMCoverImage(url: t.coverURL, corner: 12)
                                                    .frame(width: 124, height: 124)
                                                Text(t.title)
                                                    .font(.system(size: 13.5, weight: .semibold))
                                                    .foregroundStyle(YMTheme.fg(scheme))
                                                    .lineLimit(1)
                                                    .truncationMode(.middle)
                                                Text(t.artist)
                                                    .font(.system(size: 12))
                                                    .foregroundStyle(YMTheme.secondary(scheme))
                                                    .lineLimit(1)
                                            }
                                            .frame(width: 124)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, pad)
            .padding(.top, 6)
            .padding(.bottom, player.hasActiveSession ? 176 : 112)
        }
        .refreshable { await reloadCharts(force: true) }
    }

    private func heroCarousel(_ tracks: [MusicTrack]) -> some View {
        let h: CGFloat = layout.useRail ? 228 : 184
        let safeIdx = min(max(0, heroIndex), max(0, tracks.count - 1))
        return VStack(spacing: 8) {
            TabView(selection: Binding(
                get: { safeIdx },
                set: { heroIndex = $0 }
            )) {
                ForEach(Array(tracks.enumerated()), id: \.element.id) { idx, track in
                    heroCard(track, queue: dailyTracks.isEmpty ? tracks : dailyTracks)
                        .tag(idx)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .frame(height: h)

            if tracks.count > 1 {
                HStack(spacing: 6) {
                    ForEach(0..<tracks.count, id: \.self) { i in
                        Capsule()
                            .fill(i == safeIdx ? YMTheme.accent(scheme) : YMTheme.secondary(scheme).opacity(0.32))
                            .frame(width: i == safeIdx ? 18 : 6, height: 5)
                            .animation(.easeOut(duration: 0.18), value: safeIdx)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .center)
            }
        }
    }

    private func heroCard(_ track: MusicTrack, queue: [MusicTrack]) -> some View {
        let h: CGFloat = layout.useRail ? 228 : 184
        let isPersonal = dailyIsPersonalized && dailyTracks.contains(where: { $0.id == track.id })
        return ZStack(alignment: .bottomLeading) {
            YMCoverImage(url: track.coverURL, corner: 20)
                .frame(maxWidth: .infinity)
                .frame(height: h)
            LinearGradient(
                colors: [.black.opacity(0.08), .black.opacity(0.28), .black.opacity(0.84)],
                startPoint: .top,
                endPoint: .bottom
            )
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    HStack(spacing: 4) {
                        YMDoodleSparkle().fill(YMTheme.lime).frame(width: 9, height: 9)
                        Text("\(isPersonal ? "为你推荐" : "今日主打") · \(track.source.label)")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.white.opacity(0.95))
                    }
                    .padding(.horizontal, 10)
                    .frame(height: 23)
                    .background(.ultraThinMaterial, in: Capsule())
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white.opacity(0.85))
                        .frame(width: 26, height: 26)
                        .background(.ultraThinMaterial, in: Circle())
                }
                Spacer()
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(track.title)
                            .font(.system(size: 21, weight: .heavy))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Button {
                            Router.push(.musicArtist(MusicArtistRef(
                                name: track.primaryArtistName,
                                artistId: track.artistId,
                                source: track.source,
                                coverURL: track.coverURL
                            )))
                        } label: {
                            HStack(spacing: 4) {
                                Text("\(track.artist) · \(track.qualityLabel)")
                                    .lineLimit(1)
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 9.5, weight: .bold))
                            }
                            .font(.system(size: 12.5, weight: .medium))
                            .foregroundStyle(.white.opacity(0.86))
                        }
                        .buttonStyle(.plain)
                    }
                    Spacer(minLength: 10)
                    Button {
                        player.play(track: track, in: queue, openFull: true)
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "play.fill").font(.system(size: 11))
                            Text("播放").font(.system(size: 13, weight: .bold))
                        }
                        .foregroundStyle(.black)
                        .padding(.horizontal, 15)
                        .frame(height: 32)
                        .background(Capsule().fill(Color.white))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
        }
        .frame(height: h)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(Color.white.opacity(0.20), style: StrokeStyle(lineWidth: 1.1, lineCap: .round, dash: [7, 5]))
        )
        .shadow(color: .black.opacity(0.35), radius: 16, y: 8)
        .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .onTapGesture {
            if !track.album.isEmpty || track.albumId != nil {
                Router.push(.musicAlbum(MusicAlbumRef(
                    name: track.album.isEmpty ? track.title : track.album,
                    albumId: track.albumId,
                    artist: track.primaryArtistName,
                    artistId: track.artistId,
                    source: track.source,
                    coverURL: track.coverURL
                )))
            } else {
                player.play(track: track, in: queue, openFull: true)
            }
        }
    }

    private func yueqianTicket(_ track: MusicTrack) -> some View {
        let now = Date()
        let cal = Calendar.current
        let month = cal.component(.month, from: now)
        let day = cal.component(.day, from: now)
        let wkNames = ["", "周日", "周一", "周二", "周三", "周四", "周五", "周六"]
        let wk = wkNames[cal.component(.weekday, from: now)]

        return HStack(spacing: 0) {
            VStack(spacing: 2) {
                Text("\(month)月")
                    .font(.system(size: 11.5, weight: .semibold))
                    .foregroundStyle(Color(red: 0.45, green: 0.28, blue: 0.56))
                Text(String(format: "%02d", day))
                    .font(.system(size: 32, weight: .black, design: .rounded))
                    .foregroundStyle(YMTheme.paperInk)
                Text(wk)
                    .font(.system(size: 10.5, weight: .medium))
                    .foregroundStyle(YMTheme.paperInk.opacity(0.55))
            }
            .frame(width: 82)

            Rectangle()
                .fill(Color.clear)
                .frame(width: 1)
                .overlay(
                    Path { p in
                        p.move(to: CGPoint(x: 0.5, y: 10))
                        p.addLine(to: CGPoint(x: 0.5, y: 88))
                    }
                    .stroke(YMTheme.paperInk.opacity(0.22), style: StrokeStyle(lineWidth: 1, dash: [3, 4]))
                )

            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("今日乐签")
                        .font(.system(size: 10.5, weight: .bold))
                        .foregroundStyle(Color(red: 0.48, green: 0.22, blue: 0.62))
                    Text("「\(yueqianHook)」")
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(YMTheme.paperInk)
                        .lineLimit(2)
                    Text("\(track.title) — \(track.artist)")
                        .font(.system(size: 11))
                        .foregroundStyle(YMTheme.paperInk.opacity(0.62))
                        .lineLimit(1)
                }
                Spacer(minLength: 4)
                YMCoverImage(url: resolvedCover(for: track), corner: 8)
                    .frame(width: 58, height: 58)
                    .overlay(YMWobblyFrame(seed: track.seed, inset: -2).stroke(YMTheme.paperInk.opacity(0.45), lineWidth: 1.2))
                    .rotationEffect(.degrees(4))
            }
            .padding(.horizontal, 12)
        }
        .frame(height: 98)
        .background(YMTheme.paper)
        .clipShape(YMTicketShape(cornerRadius: 16, notchX: 82, notchRadius: 7))
        .shadow(color: .black.opacity(0.24), radius: 10, y: 4)
    }

    private var dailyMixCard: some View {
        let previewTracks = dailyTracks
        let previewLine: String = {
            let names = previewTracks.prefix(2).map(\.title).joined(separator: " / ")
            if !names.isEmpty { return names }
            return store.isSourceLoggedIn(.netease) ? "网易云私人日推 · 30 首" : "多源今日混排精选 · 30 首"
        }()
        return Button {
            Router.push(.musicDailyRec(previewTracks))
        } label: {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    HStack(spacing: 5) {
                        Text("每日推荐")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(YMTheme.fg(scheme))
                        if dailyIsPersonalized {
                            Text("私人")
                                .font(.system(size: 9.5, weight: .black))
                                .foregroundStyle(scheme == .dark ? .black : .white)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1.5)
                                .background(Capsule().fill(YMTheme.accent(scheme)))
                        }
                    }
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(scheme == .dark ? .black : .white)
                        .frame(width: 30, height: 30)
                        .background(Circle().fill(YMTheme.fg(scheme)))
                }
                Text(previewLine)
                    .font(.system(size: 12))
                    .foregroundStyle(YMTheme.secondary(scheme))
                    .lineLimit(2)
            }
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 104, alignment: .topLeading)
            .ymCard(radius: 20)
        }
        .buttonStyle(.plain)
    }

    private var fmCard: some View {
        let subtitle: String = {
            if let first = fmTracks.first { return "\(first.title) · \(first.primaryArtistName)" }
            return store.isSourceLoggedIn(.netease) ? "网易云私人 FM · 直连漫游" : "多源无限漫游 · 自动换源"
        }()
        return Button {
            Task {
                if !fmTracks.isEmpty {
                    let list = fmTracks
                    player.playMode = .loopAll
                    player.play(track: list[0], in: list, openFull: false)
                    showToast("\(fmSourceLabel) 已开启（共 \(list.count) 首）")
                    // Preload a fresh batch in the background for next tap
                    let fresh = await MusicProviderHub.shared.fetchPersonalFM(cookies: store.cookieMap, enabledSources: store.enabledOnlineSources)
                    if !fresh.tracks.isEmpty {
                        fmTracks = fresh.tracks
                        fmIsPersonalized = fresh.isPersonalized
                        fmSourceLabel = fresh.sourceLabel
                    }
                } else {
                    loadingFM = true
                    let res = await MusicProviderHub.shared.fetchPersonalFM(cookies: store.cookieMap, enabledSources: store.enabledOnlineSources)
                    loadingFM = false
                    let pool = res.tracks.isEmpty ? charts.flatMap(\.tracks).shuffled() : res.tracks
                    if let first = pool.first {
                        fmTracks = pool
                        fmIsPersonalized = res.isPersonalized
                        fmSourceLabel = res.sourceLabel
                        player.play(track: first, in: pool, openFull: false)
                        showToast("\(res.sourceLabel) 已开启（共 \(pool.count) 首）")
                    }
                }
            }
        } label: {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    HStack(spacing: 5) {
                        Text("私人 FM")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(YMTheme.fg(scheme))
                        if fmIsPersonalized {
                            Text("私人")
                                .font(.system(size: 9.5, weight: .black))
                                .foregroundStyle(scheme == .dark ? .black : .white)
                                .padding(.horizontal, 5)
                                .padding(.vertical, 1.5)
                                .background(Capsule().fill(YMTheme.accent(scheme)))
                        }
                    }
                    Spacer()
                    if loadingFM {
                        ProgressView().scaleEffect(0.75).frame(width: 30, height: 30)
                    } else {
                        Image(systemName: "play.fill")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .frame(width: 30, height: 30)
                            .background(Circle().fill(YMTheme.fg(scheme)))
                    }
                }
                YMAnimatedWave(waves: 4, color: YMTheme.accent(scheme), lineWidth: 2.2, speed: player.isPlaying ? 3.4 : 1.6)
                    .frame(height: 16)
                Text(subtitle)
                    .font(.system(size: 11.5))
                    .foregroundStyle(YMTheme.secondary(scheme))
                    .lineLimit(1)
            }
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 104, alignment: .topLeading)
            .ymCard(radius: 20)
        }
        .buttonStyle(.plain)
    }

    // MARK: - 2. 排行 (ChartsView)

    private var chartsScroll: some View {
        let enabled = enabledChartSources
        var platforms: [(id: MusicSourceKind?, label: String)] = enabled.map { ($0, $0.label) }
        if enabled.count > 1 {
            platforms.append((nil, "全源聚合"))
        }
        let shownCharts = visibleCharts.filter { chartPlatform == nil || $0.source == chartPlatform }
        return ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if !platforms.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(platforms, id: \.label) { item in
                                let on = chartPlatform == item.id
                                Button {
                                    chartPlatform = item.id
                                } label: {
                                    Text(item.label)
                                        .font(.system(size: 13, weight: on ? .bold : .medium))
                                        .foregroundStyle(on ? (scheme == .dark ? .black : .white) : YMTheme.secondary(scheme))
                                        .padding(.horizontal, 13)
                                        .frame(height: 30)
                                        .background(Capsule().fill(on ? YMTheme.fg(scheme) : YMTheme.fill(scheme)))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }

                if enabled.isEmpty {
                    VStack(spacing: 12) {
                        Text("已关闭全部在线音乐源")
                            .font(.system(size: 15, weight: .semibold))
                        Text("在「音乐与音源设置」中开启任意音源即可查看对应榜单")
                            .font(.system(size: 12.5))
                            .foregroundStyle(YMTheme.secondary(scheme))
                        Button("前往音源设置") { Router.push(.musicSettings) }
                            .font(.system(size: 13.5, weight: .semibold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .padding(.horizontal, 18)
                            .frame(height: 36)
                            .background(Capsule().fill(YMTheme.fg(scheme)))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 60)
                } else if loadingCharts && shownCharts.isEmpty {
                    VStack(spacing: 12) {
                        ProgressView().tint(YMTheme.accent(scheme))
                        Text("正在客户端直连拉取实时榜单…")
                            .font(.system(size: 13))
                            .foregroundStyle(YMTheme.secondary(scheme))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 60)
                } else if shownCharts.isEmpty {
                    VStack(spacing: 12) {
                        Text("暂时未能拉取榜单")
                            .font(.system(size: 15, weight: .semibold))
                        Button("重新加载") { Task { await reloadCharts(force: true) } }
                            .font(.system(size: 13.5, weight: .semibold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .padding(.horizontal, 18)
                            .frame(height: 36)
                            .background(Capsule().fill(YMTheme.fg(scheme)))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 60)
                } else {
                    LazyVStack(spacing: 14) {
                        ForEach(shownCharts) { chart in
                            chartCard(chart)
                        }
                    }
                }
            }
            .padding(.horizontal, pad)
            .padding(.top, 8)
            .padding(.bottom, player.hasActiveSession ? 176 : 112)
        }
        .refreshable { await reloadCharts(force: true) }
    }

    private func chartCard(_ chart: MusicChart) -> some View {
        Button {
            Router.push(.musicChart(chart))
        } label: {
            HStack(alignment: .center, spacing: 14) {
                YMChartCollage(urls: chart.coverURLs)
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Text(chart.name)
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(YMTheme.fg(scheme))
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Text(chart.source.label)
                            .font(.system(size: 11))
                            .foregroundStyle(YMTheme.secondary(scheme))
                    }
                    ForEach(Array(chart.previewTracks.prefix(3).enumerated()), id: \.offset) { idx, t in
                        HStack(spacing: 7) {
                            YMRankBadge(rank: idx + 1)
                            Text("\(t.title) — \(t.artist)")
                                .font(.system(size: 13))
                                .foregroundStyle(YMTheme.fg(scheme).opacity(0.80))
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                    }
                    Text(chart.updatedLabel)
                        .font(.system(size: 11))
                        .foregroundStyle(YMTheme.secondary(scheme))
                }
                Spacer(minLength: 0)
            }
            .padding(14)
            .ymCard(radius: 18)
        }
        .buttonStyle(.plain)
    }

    // MARK: - 3. 典藏 (Yimeng Faithful Vertical-Drag 3D Animated Card Deck)

    private var archiveItems: [MusicTrack] {
        var combined = store.favorites + store.recents + store.nasTracks + (charts.first?.tracks ?? [])
        var seen = Set<String>()
        combined = combined.filter { seen.insert($0.album.isEmpty ? $0.id : $0.album).inserted }
        return Array(combined.prefix(16))
    }

    private var archiveStage: some View {
        let items = archiveItems
        let n = items.count
        let f = n == 0 ? 0.0 : max(0.0, min(Double(n - 1), archiveFocus - Double(archiveDrag / 200.0)))
        let ci = n == 0 ? 0 : Int(f.rounded()).clamped(to: 0...(n - 1))
        let current = n > 0 ? items[ci] : nil

        return VStack(spacing: 0) {
            if items.isEmpty {
                Spacer()
                Text("收藏歌曲或加载榜单后，上下滑动翻阅 3D 典藏唱片架")
                    .font(.system(size: 14))
                    .foregroundStyle(YMTheme.secondary(scheme))
                    .padding(.horizontal, pad)
                Spacer()
            } else if let current {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("典藏唱片架")
                            .font(.system(size: 20, weight: .black))
                            .foregroundStyle(YMTheme.fg(scheme))
                        Text("上下滑动翻阅 · \(ci + 1) / \(n)")
                            .font(.system(size: 12))
                            .foregroundStyle(YMTheme.secondary(scheme))
                    }
                    Spacer()
                    Button {
                        withAnimation(.interpolatingSpring(stiffness: 200, damping: 26)) {
                            let next = ci + 1 < n ? Double(ci + 1) : 0
                            archiveFocus = next
                            archiveLastIdx = Int(next)
                        }
                        DS.tick()
                    } label: {
                        Label("下一张", systemImage: "arrow.up.arrow.down")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(YMTheme.fg(scheme))
                            .padding(.horizontal, 12).frame(height: 30)
                            .background(Capsule().fill(YMTheme.fill(scheme)))
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, pad)
                .padding(.top, 8)

                ZStack {
                    let lo = max(0, ci - 2)
                    let hi = min(n, ci + 6)
                    ForEach(Array(lo..<hi).reversed(), id: \.self) { idx in
                        let item = items[idx]
                        let rel = Double(idx) - f
                        let isFocus = abs(rel) < 0.5
                        let lift = max(0.0, 1.0 - abs(rel))
                        YMCoverImage(url: item.coverURL, corner: 20)
                            .frame(width: layout.useRail ? 276 : 244, height: layout.useRail ? 276 : 244)
                            .overlay(
                                RoundedRectangle(cornerRadius: 20, style: .continuous)
                                    .stroke(Color.white.opacity(isFocus ? 0.24 : 0.08), lineWidth: 1)
                            )
                            .shadow(color: YMTheme.accent(scheme).opacity(0.28 * lift), radius: 26 * lift)
                            .shadow(color: .black.opacity(0.40), radius: 20, y: 14)
                            .rotation3DEffect(
                                .degrees(rel > 0 ? -36 : min(12, -rel * 10)),
                                axis: (x: 1, y: 0, z: 0),
                                anchor: .top,
                                perspective: 0.62
                            )
                            .scaleEffect(1.0 - min(0.30, abs(rel) * 0.08) + 0.04 * lift)
                            .offset(y: rel > 0 ? -CGFloat(rel) * 34 + 28 : -CGFloat(rel) * 240 + 28)
                            .opacity(rel < -1.1 ? 0 : (rel > 4.2 ? 0 : 1))
                            .zIndex(isFocus ? 100 : -abs(rel))
                            .id(item.id)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: layout.useRail ? 360 : 326)
                .contentShape(Rectangle())
                .onTapGesture {
                    player.play(track: current, in: items, openFull: false)
                }
                .highPriorityGesture(
                    DragGesture(minimumDistance: 6)
                        .onChanged { v in
                            archiveDrag = -v.translation.height
                            let liveF = max(0.0, min(Double(n - 1), archiveFocus - Double(archiveDrag / 200.0)))
                            let liveIdx = Int(liveF.rounded())
                            if liveIdx != archiveLastIdx {
                                archiveLastIdx = liveIdx
                                DS.tick()
                            }
                        }
                        .onEnded { v in
                            let predicted = (archiveFocus - Double(-v.predictedEndTranslation.height / 200.0)).rounded()
                            let clamped = max(0.0, min(Double(n - 1), predicted))
                            archiveFocus = f
                            archiveDrag = 0
                            withAnimation(.interpolatingSpring(stiffness: 200, damping: 26)) {
                                archiveFocus = clamped
                            }
                            archiveLastIdx = Int(clamped)
                        }
                )

                VStack(spacing: 6) {
                    Text(current.title)
                        .font(.system(size: 21, weight: .black))
                        .foregroundStyle(YMTheme.fg(scheme))
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .animation(.easeOut(duration: 0.18), value: current.id)
                    HStack(spacing: 8) {
                        Button {
                            Router.push(.musicArtist(MusicArtistRef(
                                name: current.primaryArtistName,
                                artistId: current.artistId,
                                source: current.source,
                                coverURL: current.coverURL
                            )))
                        } label: {
                            Text(current.primaryArtistName)
                                .font(.system(size: 13.5, weight: .semibold))
                                .foregroundStyle(YMTheme.accent(scheme))
                        }
                        .buttonStyle(.plain)
                        if !current.album.isEmpty {
                            Text("·").foregroundStyle(YMTheme.secondary(scheme))
                            Button {
                                Router.push(.musicAlbum(MusicAlbumRef(
                                    name: current.album,
                                    albumId: current.albumId,
                                    artist: current.primaryArtistName,
                                    artistId: current.artistId,
                                    source: current.source,
                                    coverURL: current.coverURL
                                )))
                            } label: {
                                Text(current.album)
                                    .font(.system(size: 13.5))
                                    .foregroundStyle(YMTheme.secondary(scheme))
                                    .lineLimit(1)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(.horizontal, pad)

                HStack(spacing: 12) {
                    Button {
                        player.play(track: current, in: items, openFull: true)
                    } label: {
                        Label("播放", systemImage: "play.fill")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .frame(maxWidth: .infinity, minHeight: 46)
                            .background(Capsule().fill(YMTheme.fg(scheme)))
                    }
                    .buttonStyle(.plain)

                    Button {
                        if !current.album.isEmpty {
                            Router.push(.musicAlbum(MusicAlbumRef(
                                name: current.album,
                                albumId: current.albumId,
                                artist: current.primaryArtistName,
                                artistId: current.artistId,
                                source: current.source,
                                coverURL: current.coverURL
                            )))
                        } else {
                            Router.push(.musicArtist(MusicArtistRef(
                                name: current.primaryArtistName,
                                artistId: current.artistId,
                                source: current.source,
                                coverURL: current.coverURL
                            )))
                        }
                    } label: {
                        Text(current.album.isEmpty ? "歌手" : "专辑")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(YMTheme.fg(scheme))
                            .frame(width: 96, height: 46)
                            .background(Capsule().fill(YMTheme.fill(scheme)))
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, pad + 8)
                .padding(.top, 14)

                Spacer(minLength: player.shouldShowGlobalMiniBar ? 84 : 24)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - 4. 曲库 (Local Favorites, Playlists, NAS & Offline Cache)

    private var libraryScroll: some View {
        let totalPlaylists = store.cloudPlaylists.count + store.playlists.count
        let segs = ["收藏", totalPlaylists > 0 ? "歌单 (\(totalPlaylists))" : "歌单", "个人 NAS", "最近"]
        let cloudLiked = store.cloudPlaylists.first(where: { $0.playCountText == "红心" || $0.name.contains("我喜欢") })
        return ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 8) {
                    ForEach(segs.indices, id: \.self) { i in
                        let on = librarySegment == i
                        Button { librarySegment = i } label: {
                            Text(segs[i])
                                .font(.system(size: 13, weight: on ? .bold : .medium))
                                .foregroundStyle(on ? (scheme == .dark ? .black : .white) : YMTheme.secondary(scheme))
                                .padding(.horizontal, 13)
                                .frame(height: 30)
                                .background(Capsule().fill(on ? YMTheme.fg(scheme) : YMTheme.fill(scheme)))
                        }
                        .buttonStyle(.plain)
                    }
                    Spacer()
                    if librarySegment == 1 {
                        if store.hasAnyLoggedInSource {
                            Button {
                                Task {
                                    let count = await store.syncCloudPlaylists()
                                    showToast(count > 0 ? "已同步 \(count) 个云端歌单" : "未拉取到云端歌单，请检查登录状态")
                                }
                            } label: {
                                if store.syncingCloudPlaylists {
                                    ProgressView().scaleEffect(0.75).frame(width: 30, height: 30)
                                } else {
                                    Image(systemName: "arrow.triangle.2.circlepath")
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundStyle(YMTheme.accent(scheme))
                                        .frame(width: 30, height: 30)
                                        .background(Circle().fill(YMTheme.fill(scheme)))
                                }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("同步账号云端歌单")
                        }
                        Button { showNewPlaylistAlert = true } label: {
                            Image(systemName: "plus")
                                .font(.system(size: 14, weight: .bold))
                                .frame(width: 30, height: 30)
                                .background(Circle().fill(YMTheme.fill(scheme)))
                        }
                        .buttonStyle(.plain)
                    }
                    Button { Router.push(.musicSettings) } label: {
                        Image(systemName: "slider.horizontal.3")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(YMTheme.secondary(scheme))
                            .frame(width: 30, height: 30)
                            .background(Circle().fill(YMTheme.fill(scheme)))
                    }
                    .buttonStyle(.plain)
                }

                if librarySegment == 0 {
                    if let liked = cloudLiked {
                        Button { Router.push(.musicPlaylist(liked)) } label: {
                            HStack(spacing: 12) {
                                YMCoverImage(url: liked.displayCoverURL, corner: 12)
                                    .frame(width: 52, height: 52)
                                VStack(alignment: .leading, spacing: 3) {
                                    HStack(spacing: 6) {
                                        Text(liked.name)
                                            .font(.system(size: 15, weight: .bold))
                                            .foregroundStyle(YMTheme.fg(scheme))
                                            .lineLimit(1)
                                        Text("云端红心")
                                            .font(.system(size: 9.5, weight: .black))
                                            .foregroundStyle(.white)
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 1.5)
                                            .background(Capsule().fill(YMTheme.heart))
                                    }
                                    Text(liked.subtitle)
                                        .font(.system(size: 12))
                                        .foregroundStyle(YMTheme.secondary(scheme))
                                }
                                Spacer()
                                Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(YMTheme.secondary(scheme))
                            }
                            .padding(12)
                            .ymCard(radius: 16)
                        }
                        .buttonStyle(.plain)
                    }
                    if store.favorites.isEmpty {
                        if cloudLiked == nil {
                            emptyHint("还没有收藏歌曲，在播放页点小红心或排行榜收藏")
                        } else {
                            Text("本机收藏为空，在播放页点红心可离线收藏到本机")
                                .font(.system(size: 12.5))
                                .foregroundStyle(YMTheme.secondary(scheme))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 20)
                        }
                    } else {
                        ForEach(store.favorites) { t in
                            MusicTrackRow(track: t, isCurrent: player.currentTrack?.id == t.id) {
                                player.play(track: t, in: store.favorites)
                            }
                        }
                    }
                } else if librarySegment == 1 {
                    // 1) Synced Account Cloud Playlists
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text("账号云端歌单（\(store.cloudPlaylists.count)）")
                                .font(.system(size: 14.5, weight: .bold))
                                .foregroundStyle(YMTheme.fg(scheme))
                            Spacer()
                            if store.hasAnyLoggedInSource {
                                Button {
                                    Task {
                                        let count = await store.syncCloudPlaylists()
                                        showToast(count > 0 ? "已同步 \(count) 个云端歌单" : "暂未获取到云端歌单")
                                    }
                                } label: {
                                    Text(store.syncingCloudPlaylists ? "同步中…" : "立即同步")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(YMTheme.accent(scheme))
                                }
                                .buttonStyle(.plain)
                            } else {
                                Button("登录同步 ›") { Router.push(.musicSettings) }
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundStyle(YMTheme.accent(scheme))
                                    .buttonStyle(.plain)
                            }
                        }
                        if store.cloudPlaylists.isEmpty {
                            Text(store.hasAnyLoggedInSource
                                ? (store.syncingCloudPlaylists ? "正在直连同步你的云端歌单…" : "点右上角「立即同步」拉取已登录账号的云端歌单")
                                : "登录后自动同步网易云 / QQ 音乐 / 酷狗 / 汽水云端歌单")
                                .font(.system(size: 12.5))
                                .foregroundStyle(YMTheme.secondary(scheme))
                                .padding(.vertical, 8)
                        } else {
                            ForEach(store.cloudPlaylists) { pl in
                                Button { Router.push(.musicPlaylist(pl)) } label: {
                                    HStack(spacing: 12) {
                                        YMCoverImage(url: pl.displayCoverURL, corner: 12)
                                            .frame(width: 56, height: 56)
                                        VStack(alignment: .leading, spacing: 3) {
                                            HStack(spacing: 6) {
                                                Text(pl.name)
                                                    .font(.system(size: 15, weight: .semibold))
                                                    .foregroundStyle(YMTheme.fg(scheme))
                                                    .lineLimit(1)
                                                if pl.playCountText == "红心" {
                                                    Image(systemName: "heart.fill")
                                                        .font(.system(size: 10))
                                                        .foregroundStyle(YMTheme.heart)
                                                }
                                            }
                                            Text(pl.subtitle)
                                                .font(.system(size: 12))
                                                .foregroundStyle(YMTheme.secondary(scheme))
                                                .lineLimit(1)
                                        }
                                        Spacer()
                                        if let src = pl.source {
                                            Text(src.label)
                                                .font(.system(size: 10, weight: .bold))
                                                .foregroundStyle(YMTheme.accent(scheme))
                                                .padding(.horizontal, 7)
                                                .padding(.vertical, 3)
                                                .background(Capsule().fill(YMTheme.fill(scheme)))
                                        }
                                        Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(YMTheme.secondary(scheme))
                                    }
                                    .padding(12)
                                    .ymCard(radius: 16)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }

                    // 2) Local Playlists
                    VStack(alignment: .leading, spacing: 10) {
                        Text("本机歌单（\(store.playlists.count)）")
                            .font(.system(size: 14.5, weight: .bold))
                            .foregroundStyle(YMTheme.fg(scheme))
                        if store.playlists.isEmpty {
                            Text("暂无本机歌单，点右上角 + 新建，或在歌单/榜单页一键「克隆歌单」")
                                .font(.system(size: 12.5))
                                .foregroundStyle(YMTheme.secondary(scheme))
                                .padding(.vertical, 8)
                        } else {
                            ForEach(store.playlists) { pl in
                                Button { Router.push(.musicPlaylist(pl)) } label: {
                                    HStack(spacing: 12) {
                                        YMCoverImage(url: pl.displayCoverURL, corner: 12)
                                            .frame(width: 56, height: 56)
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(pl.name).font(.system(size: 15.5, weight: .semibold)).foregroundStyle(YMTheme.fg(scheme))
                                            Text("\(pl.tracks.count) 首 · 本机存储").font(.system(size: 12)).foregroundStyle(YMTheme.secondary(scheme))
                                        }
                                        Spacer()
                                        Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(YMTheme.secondary(scheme))
                                    }
                                    .padding(12)
                                    .ymCard(radius: 16)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                } else if librarySegment == 2 {
                    if store.nasTracks.isEmpty {
                        VStack(spacing: 10) {
                            emptyHint("尚未直连个人 NAS 曲库（支持 Navidrome / Emby / 飞牛音乐，无需部署 Go Core）")
                            Button("添加个人 NAS 直连") { showAddNASSheet = true }
                                .font(.system(size: 13.5, weight: .semibold))
                                .foregroundStyle(scheme == .dark ? .black : .white)
                                .padding(.horizontal, 18)
                                .frame(height: 36)
                                .background(Capsule().fill(YMTheme.fg(scheme)))
                        }
                    } else {
                        ForEach(store.nasTracks) { t in
                            MusicTrackRow(track: t, isCurrent: player.currentTrack?.id == t.id) {
                                player.play(track: t, in: store.nasTracks)
                            }
                        }
                    }
                } else {
                    if store.recents.isEmpty {
                        emptyHint("还没有听歌记录")
                    } else {
                        ForEach(store.recents) { t in
                            MusicTrackRow(track: t, isCurrent: player.currentTrack?.id == t.id) {
                                player.play(track: t, in: store.recents)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, pad)
            .padding(.top, 8)
            .padding(.bottom, player.hasActiveSession ? 176 : 112)
        }
        .refreshable {
            if store.hasAnyLoggedInSource {
                _ = await store.syncCloudPlaylists()
            }
        }
    }

    // MARK: - 5. 音源 (Serverless Sources, NAS Direct, Resume Policy & LRU Cache)

    private var sourcesScroll: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Online sources & priority order
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("本机直连音源与容错顺位")
                            .font(.system(size: 15, weight: .bold))
                        Spacer()
                        Text("零服务端 · 点序号置顶")
                            .font(.system(size: 11.5))
                            .foregroundStyle(YMTheme.secondary(scheme))
                    }
                    ForEach(Array(store.sourcePrefs.enumerated()), id: \.element.id) { idx, pref in
                        HStack(spacing: 10) {
                            Button { store.promoteSource(pref.id) } label: {
                                Text("\(idx + 1)")
                                    .font(.system(size: 11, weight: .heavy, design: .rounded))
                                    .foregroundStyle(idx == 0 ? .black : YMTheme.fg(scheme))
                                    .frame(width: 22, height: 22)
                                    .background(Circle().fill(idx == 0 ? YMTheme.lime : YMTheme.fill(scheme)))
                            }
                            .buttonStyle(.plain)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(pref.kind.fullName).font(.system(size: 14, weight: .semibold))
                                Text(pref.customCookie != nil ? "已配置本机 Cookie" : "客户端公开接口直解")
                                    .font(.system(size: 11.5))
                                    .foregroundStyle(YMTheme.secondary(scheme))
                            }
                            Spacer()
                            if pref.kind == .netease {
                                Button("Cookie") {
                                    cookieDraft = pref.customCookie ?? ""
                                    editingCookieSource = pref
                                }
                                .font(.system(size: 11.5, weight: .semibold))
                                .foregroundStyle(YMTheme.accent(scheme))
                                .padding(.horizontal, 8)
                                .frame(height: 24)
                                .overlay(Capsule().stroke(YMTheme.accent(scheme).opacity(0.6), lineWidth: 1))
                                .buttonStyle(.plain)
                            }
                            Toggle("", isOn: Binding(
                                get: { pref.enabled },
                                set: { _ in store.toggleSource(pref.id) }
                            ))
                            .labelsHidden()
                            .toggleStyle(YMToggleStyle())
                        }
                        if idx < store.sourcePrefs.count - 1 {
                            Divider().overlay(YMTheme.divider(scheme))
                        }
                    }
                }
                .padding(14)
                .ymCard(radius: 20)

                // Public lyrics & auto-fallback toggles
                VStack(alignment: .leading, spacing: 12) {
                    Text("歌词补全与容错")
                        .font(.system(size: 15, weight: .bold))
                    Toggle("版权受限自动跨源容错换源", isOn: $store.settings.autoFallback)
                        .font(.system(size: 14))
                        .toggleStyle(YMToggleStyle())
                    Toggle("LRCLIB 公网同步歌词兜底", isOn: $store.settings.lrclibEnabled)
                        .font(.system(size: 14))
                        .toggleStyle(YMToggleStyle())
                }
                .padding(14)
                .ymCard(radius: 20)

                // Video / Audiobook Interruption Smart Resume Policy
                VStack(alignment: .leading, spacing: 10) {
                    Text("看视频 / 听书中断后恢复音乐")
                        .font(.system(size: 15, weight: .bold))
                    HStack(spacing: 8) {
                        ForEach(MusicResumePolicy.allCases) { p in
                            let on = arbiter.resumePolicy == p
                            Button { arbiter.resumePolicy = p } label: {
                                Text(p.label)
                                    .font(.system(size: 12.5, weight: on ? .bold : .medium))
                                    .foregroundStyle(on ? (scheme == .dark ? .black : .white) : YMTheme.secondary(scheme))
                                    .frame(maxWidth: .infinity, minHeight: 32)
                                    .background(Capsule().fill(on ? YMTheme.fg(scheme) : YMTheme.fill(scheme)))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    Text(arbiter.resumePolicy.detail + "（拔出耳机永远不自动恢复）")
                        .font(.system(size: 11.5))
                        .foregroundStyle(YMTheme.secondary(scheme))
                }
                .padding(14)
                .ymCard(radius: 20)

                // Direct Personal NAS (Navidrome / Emby / 飞牛音乐)
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("个人 NAS 纯前端直连（无需 Core）")
                            .font(.system(size: 15, weight: .bold))
                        Spacer()
                        Button("+ 添加") { showAddNASSheet = true }
                            .font(.system(size: 12.5, weight: .bold))
                            .foregroundStyle(YMTheme.accent(scheme))
                    }
                    if store.nasConfigs.isEmpty {
                        Text("支持 Navidrome (Subsonic)、Emby / Jellyfin、飞牛音乐 (fnOS)，直连家庭曲库无损原流。")
                            .font(.system(size: 12))
                            .foregroundStyle(YMTheme.secondary(scheme))
                    } else {
                        ForEach(store.nasConfigs) { cfg in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("\(cfg.name) · \(cfg.kind.label)").font(.system(size: 14, weight: .semibold))
                                    Text(cfg.serverURL).font(.system(size: 11.5)).foregroundStyle(YMTheme.secondary(scheme)).lineLimit(1)
                                }
                                Spacer()
                                Button("同步") {
                                    Task {
                                        let tracks = await MusicProviderHub.shared.fetchNASTracks(config: cfg)
                                        store.setNASTracks(tracks)
                                        showToast(tracks.isEmpty ? "未能连接 \(cfg.name)，请检查地址与账号" : "已加载 \(tracks.count) 首 NAS 曲目")
                                    }
                                }
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(YMTheme.accent(scheme))
                                Button(role: .destructive) { store.removeNAS(id: cfg.id) } label: {
                                    Image(systemName: "trash").font(.system(size: 13))
                                }
                                .padding(.leading, 8)
                            }
                        }
                    }
                }
                .padding(14)
                .ymCard(radius: 20)

                // Local LRU Cache & .idjbackup note
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("本机离线缓存与备份")
                            .font(.system(size: 15, weight: .bold))
                        Spacer()
                        Button("清空缓存") {
                            store.clearCache()
                            showToast("已清空本机音频缓存")
                        }
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(YMTheme.secondary(scheme))
                    }
                    let mb = Double(store.cacheBytes) / 1_048_576.0
                    Text(String(format: "已缓存 %.1f MB（%d 首）· 歌单/收藏/NAS 配置自动随 .idjbackup 与 WebDAV 同步", mb, store.cachedCount))
                        .font(.system(size: 12))
                        .foregroundStyle(YMTheme.secondary(scheme))
                }
                .padding(14)
                .ymCard(radius: 20)
            }
            .padding(.horizontal, pad)
            .padding(.top, 8)
            .padding(.bottom, player.hasActiveSession ? 176 : 112)
        }
    }

    private func emptyHint(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13.5))
            .foregroundStyle(YMTheme.secondary(scheme))
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 48)
    }

    private func reloadCharts(force: Bool) async {
        syncChartPlatformWithEnabledSources()
        let enabled = enabledChartSources
        if enabled.isEmpty {
            charts = []
            dailyTracks = []
            dailyIsPersonalized = false
            recommendedPlaylists = []
            recPlaylistsPersonalized = false
            fmTracks = []
            fmIsPersonalized = false
            return
        }
        loadingCharts = true
        defer { loadingCharts = false }
        let cookies = store.cookieMap
        let targetPlatform = (chartPlatform != nil && enabled.contains(chartPlatform!)) ? chartPlatform : (enabled.count == 1 ? enabled.first : nil)
        async let chartsTask = MusicProviderHub.shared.fetchCharts(for: targetPlatform, enabledSources: enabled, forceRefresh: force)
        async let dailyTask = MusicProviderHub.shared.fetchDailyRecommendations(cookies: cookies, enabledSources: enabled, forceRefresh: force)
        async let recPlTask = MusicProviderHub.shared.fetchRecommendedPlaylists(cookies: cookies, enabledSources: enabled, forceRefresh: force)
        async let fmTask = MusicProviderHub.shared.fetchPersonalFM(cookies: cookies, enabledSources: enabled)
        let (fetched, dailyRes, recRes, fmRes) = await (chartsTask, dailyTask, recPlTask, fmTask)
        let allowSet = Set(enabled)
        charts = fetched.filter { allowSet.contains($0.source) }
        dailyTracks = dailyRes.tracks.filter { allowSet.contains($0.source) }
        dailyIsPersonalized = dailyRes.isPersonalized && !dailyTracks.isEmpty
        recommendedPlaylists = recRes.playlists.filter { $0.source.map { allowSet.contains($0) } ?? true }
        recPlaylistsPersonalized = recRes.isPersonalized && !recommendedPlaylists.isEmpty
        fmTracks = fmRes.tracks.filter { allowSet.contains($0.source) }
        fmIsPersonalized = fmRes.isPersonalized && !fmTracks.isEmpty
        fmSourceLabel = fmRes.sourceLabel
        if store.hasAnyLoggedInSource && (force || store.cloudPlaylists.isEmpty) {
            _ = await store.syncCloudPlaylists()
        }
    }

    private func runSearch() async {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { searchResults = []; return }
        searching = true
        defer { searching = false }
        searchResults = await MusicProviderHub.shared.search(
            query: q,
            enabledSources: store.enabledOnlineSources,
            nasConfigs: store.nasConfigs
        )
    }

    private func showToast(_ msg: String) {
        withAnimation(.easeOut(duration: 0.2)) { toastText = msg }
        Task {
            try? await Task.sleep(nanoseconds: 2_200_000_000)
            withAnimation(.easeIn(duration: 0.2)) {
                if toastText == msg { toastText = nil }
            }
        }
    }
}

// MARK: - Track Row, Track List Sheet, Yueqian Sheet, NAS Config Sheet

struct MusicTrackRow: View {
    let track: MusicTrack
    let isCurrent: Bool
    var rank: Int? = nil
    let onTap: () -> Void
    @Environment(\.colorScheme) private var scheme
    @ObservedObject private var store = MusicStore.shared

    init(track: MusicTrack, isCurrent: Bool, rank: Int? = nil, onTap: @escaping () -> Void) {
        self.track = track
        self.isCurrent = isCurrent
        self.rank = rank
        self.onTap = onTap
    }

    init(rank: Int?, track: MusicTrack, list: [MusicTrack]) {
        self.track = track
        self.isCurrent = MusicPlayerEngine.shared.currentTrack?.id == track.id
        self.rank = rank
        self.onTap = {
            MusicPlayerEngine.shared.play(track: track, in: list)
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            Button(action: onTap) {
                HStack(spacing: 10) {
                    if let rank {
                        YMRankBadge(rank: rank)
                    }
                    YMCoverImage(url: track.coverURL, corner: 9)
                        .frame(width: 46, height: 46)
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text(track.title)
                                .font(.system(size: 15, weight: isCurrent ? .bold : .medium))
                                .foregroundStyle(isCurrent ? YMTheme.accent(scheme) : YMTheme.fg(scheme))
                                .lineLimit(1)
                                .truncationMode(.middle)
                            let showPreview = (track.source == .itunes)
                                || (track.entitlement == .preview && !store.isSourceLoggedIn(track.source))
                                || (isCurrent && MusicPlayerEngine.shared.activeEntitlement == .preview)
                            if showPreview {
                                YMPreviewCapsule()
                            }
                        }
                        HStack(spacing: 6) {
                            Text(track.album.isEmpty ? track.artist : "\(track.artist) · \(track.album)")
                                .font(.system(size: 12.5))
                                .foregroundStyle(YMTheme.secondary(scheme))
                                .lineLimit(1)
                            Text(track.sourceDisplayLabel)
                                .font(.system(size: 10.5))
                                .foregroundStyle(YMTheme.secondary(scheme).opacity(0.8))
                        }
                    }
                    Spacer(minLength: 4)
                    Text(track.formattedDuration)
                        .font(.system(size: 12).monospacedDigit())
                        .foregroundStyle(YMTheme.secondary(scheme))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Menu {
                Button {
                    Router.push(.musicArtist(MusicArtistRef(
                        name: track.primaryArtistName,
                        artistId: track.artistId,
                        source: track.source,
                        coverURL: track.coverURL
                    )))
                } label: {
                    Label("查看歌手：\(track.primaryArtistName)", systemImage: "person.wave.2")
                }

                if !track.album.isEmpty {
                    Button {
                        Router.push(.musicAlbum(MusicAlbumRef(
                            name: track.album,
                            albumId: track.albumId,
                            artist: track.primaryArtistName,
                            artistId: track.artistId,
                            source: track.source,
                            coverURL: track.coverURL
                        )))
                    } label: {
                        Label("查看专辑：\(track.album)", systemImage: "opticaldisc")
                    }
                }

                Button {
                    store.toggleFavorite(track)
                } label: {
                    Label(store.isFavorite(track) ? "取消红心" : "加入红心", systemImage: store.isFavorite(track) ? "heart.slash" : "heart")
                }

                Button {
                    onTap()
                    MusicPlayerEngine.shared.showFullPlayer = true
                } label: {
                    Label("打开全屏播放", systemImage: "play.circle")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(YMTheme.secondary(scheme))
                    .frame(width: 30, height: 34)
                    .contentShape(Rectangle())
            }
        }
        .padding(.vertical, 6)
    }
}

private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}

struct MusicTrackListSheet: View {
    let title: String
    let subtitle: String
    let sourceLabel: String
    let tracks: [MusicTrack]
    let canClone: Bool
    let onClone: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @ObservedObject private var player = MusicPlayerEngine.shared

    var body: some View {
        DSSheet(title, subtitle: "\(sourceLabel) · \(subtitle)") {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 10) {
                        Button {
                            if let first = tracks.first {
                                player.play(track: first, in: tracks, openFull: true)
                                dismiss()
                            }
                        } label: {
                            Label("播放全部（\(tracks.count)）", systemImage: "play.fill")
                                .font(.system(size: 14.5, weight: .semibold))
                                .foregroundStyle(scheme == .dark ? .black : .white)
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .background(Capsule().fill(YMTheme.fg(scheme)))
                        }
                        .buttonStyle(.plain)

                        if canClone, let onClone {
                            Button {
                                onClone()
                            } label: {
                                Text("克隆歌单")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(YMTheme.fg(scheme))
                                    .padding(.horizontal, 16)
                                    .frame(height: 44)
                                    .background(Capsule().fill(YMTheme.fill(scheme)))
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    ForEach(Array(tracks.enumerated()), id: \.element.id) { idx, t in
                        HStack(spacing: 10) {
                            if idx < 3 {
                                YMRankBadge(rank: idx + 1)
                            } else {
                                Text("\(idx + 1)")
                                    .font(.system(size: 12.5, weight: .semibold).monospacedDigit())
                                    .foregroundStyle(YMTheme.secondary(scheme))
                                    .frame(width: 22)
                            }
                            MusicTrackRow(track: t, isCurrent: player.currentTrack?.id == t.id) {
                                player.play(track: t, in: tracks, openFull: false)
                            }
                        }
                    }
                }
                .padding(.horizontal, 18)
                .padding(.bottom, 30)
            }
        }
    }
}

struct YueqianSheetView: View {
    let track: MusicTrack
    let coverURL: String
    let lyricHook: String
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var player = MusicPlayerEngine.shared

    var body: some View {
        let now = Date()
        let cal = Calendar.current
        let dateStr = String(format: "%02d / %02d", cal.component(.month, from: now), cal.component(.day, from: now))
        ZStack {
            LinearGradient(
                colors: [YMTheme.paper, Color(red: 0.92, green: 0.89, blue: 0.84)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ).ignoresSafeArea()

            VStack(spacing: 20) {
                HStack {
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(YMTheme.paperInk)
                            .frame(width: 36, height: 36)
                            .background(Circle().fill(Color.black.opacity(0.08)))
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 20)

                VStack(spacing: 16) {
                    YMCoverImage(url: coverURL.isEmpty ? track.coverURL : coverURL, corner: 14)
                        .frame(width: 228, height: 228)
                        .overlay(YMWobblyFrame(seed: track.seed, inset: -6).stroke(YMTheme.paperInk.opacity(0.45), lineWidth: 1.5))

                    Text(dateStr)
                        .font(.system(size: 40, weight: .black, design: .rounded))
                        .foregroundStyle(YMTheme.paperInk)

                    Text("「\(lyricHook)」")
                        .font(.system(size: 18, weight: .semibold, design: .serif))
                        .foregroundStyle(YMTheme.paperInk)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 16)

                    Text("\(track.title) — \(track.artist)")
                        .font(.system(size: 13))
                        .foregroundStyle(YMTheme.paperInk.opacity(0.62))

                    Button {
                        player.play(track: track, openFull: true)
                        dismiss()
                    } label: {
                        Label("听这首歌", systemImage: "play.fill")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 170, height: 44)
                            .background(Capsule().fill(YMTheme.paperInk))
                    }
                    .buttonStyle(.plain)
                }
                .padding(24)
                .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Color.white.opacity(0.85)))
                .padding(.horizontal, 24)

                Text("i短剧 × 译梦 · 今日乐签")
                    .font(.system(size: 12))
                    .foregroundStyle(YMTheme.paperInk.opacity(0.5))
                Spacer()
            }
            .padding(.top, 12)
        }
        // Scope light style strictly to this sheet's hosting VC — never propagates `.preferredColorScheme` to RootTabView (eliminates full-screen flash!).
        .stvLocalColorScheme(.light)
    }
}

struct MusicAddNASSheet: View {
    let onSave: (MusicNASConfig) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State private var kind: MusicSourceKind = .navidrome
    @State private var name = "我的 NAS 曲库"
    @State private var serverURL = "http://192.168.1.2:4533"
    @State private var username = ""
    @State private var password = ""

    var body: some View {
        DSSheet("添加个人 NAS 音乐直连", subtitle: "纯前端直连你的家庭媒体库，无需部署 Go Core") {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 8) {
                        ForEach([MusicSourceKind.navidrome, .fnos, .emby], id: \.self) { k in
                            let on = kind == k
                            Button {
                                kind = k
                                if k == .navidrome { serverURL = "http://192.168.1.2:4533" }
                                else if k == .emby { serverURL = "http://192.168.1.2:8096" }
                                else { serverURL = "http://192.168.1.2:5666" }
                            } label: {
                                Text(k.label)
                                    .font(.system(size: 13, weight: on ? .bold : .medium))
                                    .foregroundStyle(on ? (scheme == .dark ? .black : .white) : YMTheme.secondary(scheme))
                                    .frame(maxWidth: .infinity, minHeight: 34)
                                    .background(Capsule().fill(on ? YMTheme.fg(scheme) : YMTheme.fill(scheme)))
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    field("显示名称", text: $name)
                    field("服务器地址（含端口）", text: $serverURL)
                    field("用户名", text: $username)
                    VStack(alignment: .leading, spacing: 6) {
                        Text("密码").font(.system(size: 12.5, weight: .semibold)).foregroundStyle(YMTheme.secondary(scheme))
                        SecureField("输入密码", text: $password)
                            .padding(12)
                            .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }

                    Button {
                        let cfg = MusicNASConfig(
                            id: UUID().uuidString,
                            kind: kind,
                            name: name.isEmpty ? kind.label : name,
                            serverURL: serverURL,
                            username: username,
                            password: password,
                            enabled: true
                        )
                        onSave(cfg)
                        dismiss()
                    } label: {
                        Text("保存并直连")
                            .font(.system(size: 15.5, weight: .semibold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .frame(maxWidth: .infinity, minHeight: 46)
                            .background(Capsule().fill(YMTheme.fg(scheme)))
                    }
                    .disabled(serverURL.trimmingCharacters(in: .whitespaces).isEmpty || username.isEmpty)
                    .padding(.top, 6)
                }
                .padding(20)
            }
        }
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.system(size: 12.5, weight: .semibold)).foregroundStyle(YMTheme.secondary(scheme))
            TextField(label, text: text)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .padding(12)
                .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }
}

/// Equatable ambient backdrop for `MusicHomeView` so 0.25s playback time ticks never re-blur the cover image.
private struct MusicHomeAmbientBackdrop: View, Equatable {
    let coverURL: String
    let isDark: Bool

    var body: some View {
        YMCoverImage(url: coverURL, corner: 0)
            .frame(height: 320)
            .blur(radius: 40)
            .opacity(isDark ? 0.34 : 0.16)
            .mask(
                LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom)
            )
            .drawingGroup()
    }
}
