import SwiftUI
import UIKit

struct EpisodeRange: Hashable {
    let start: Int
    let end: Int
    init(_ start: Int, _ end: Int) { self.start = start; self.end = end }
}

enum EpisodeGroups {
    /// Group size: 30 (≤300 eps), 50 (≤600), 60 (more). Tablet portrait uses 50 per tab so 8–10 col grids fill naturally.
    static func size(for total: Int, groupLimit: Int = 30) -> Int {
        if total <= groupLimit { return max(total, 1) }
        if total <= 300 { return groupLimit }
        if total <= 600 { return max(groupLimit, 50) }
        return 60
    }
    static func ranges(total: Int, groupLimit: Int = 30) -> [EpisodeRange] {
        guard total > 0 else { return [] }
        let g = size(for: total, groupLimit: groupLimit)
        if total <= g { return [EpisodeRange(0, total - 1)] }
        var out: [EpisodeRange] = []
        var i = 0
        while i < total {
            out.append(EpisodeRange(i, min(i + g - 1, total - 1)))
            i += g
        }
        return out
    }
    static func label(_ r: EpisodeRange, episodes: [HGEpisode]) -> String {
        let a = episodes.indices.contains(r.start) ? episodes[r.start].ep : r.start + 1
        let b = episodes.indices.contains(r.end) ? episodes[r.end].ep : r.end + 1
        return a == b ? "\(a)" : "\(a)-\(b)"
    }
    /// Sources that name their episodes ("20240105期", "正片", "番外") need the names; plain "第 12 集" lists read better as numbers.
    static func named(_ episodes: [HGEpisode]) -> Bool {
        episodes.contains { e in
            let t = e.title.trimmingCharacters(in: .whitespaces)
            guard !t.isEmpty else { return false }
            let core = t.replacingOccurrences(of: "第", with: "").trimmingCharacters(in: CharacterSet(charactersIn: " 集话期回EePp."))
            return Int(core) != e.ep
        }
    }
    /// Smart 2-row inline preview range anchored around the user's resume episode so both the active episode and
    /// upcoming episodes are immediately tappable without flooding the page.
    static func previewRange(total: Int, columns: Int, rows: Int = 2, resume: Int?, ascending: Bool) -> EpisodeRange {
        guard total > 0 else { return EpisodeRange(0, -1) }
        let cols = max(1, columns)
        let limit = cols * max(1, rows)
        guard total > limit else { return EpisodeRange(0, total - 1) }
        if let r = resume, r >= 0, r < total {
            let rowAlignedStart = (r / cols) * cols
            let start = min(max(0, rowAlignedStart), total - limit)
            return EpisodeRange(start, start + limit - 1)
        }
        if ascending {
            return EpisodeRange(0, limit - 1)
        } else {
            return EpisodeRange(total - limit, total - 1)
        }
    }
}

struct DramaDetailView: View {
    let series: HGSeries
    var seededDetail: HGSeriesDetail? = nil
    @State private var detail: HGSeriesDetail?
    @State private var error: Error?
    @State private var group = 0
    @State private var ascending = true
    /// Selected 线路 (`HGPlayLine.id`).
    @State private var lineID: String?
    /// Episode cells show names instead of numbers (computed once per line).
    @State private var namedEpisodes = false
    @State private var seasons: [HGSeries] = []
    /// Long-video sites: resolved 播放源 list (preloaded so the player starts instantly).
    @State private var sources: [HGStream] = []
    @State private var sourcesError: String?
    @State private var sourcesVerify: VerifyTarget?
    @State private var verifySheet: VerifyTarget?
    /// Half-screen drawer for all episodes + deferred NavigationStack push on pick.
    @State private var showAllEpisodes = false
    @State private var pendingSheetPlayIndex: Int?
    @State private var sheetPlayIndex: Int = 0
    @State private var pushSheetPlay = false
    @Environment(\.zoomNS) private var ns

    @State private var detailPinned = false
    @State private var actionsOut = false
    @State private var barCompact = false
    @State private var scrollTrack = ScrollTrack()
    @State private var introOpen = false
    @State private var introClamped = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.layoutMetrics) private var layout

    /// Scroll bookkeeping that must not invalidate the page.
    private final class ScrollTrack { var turn: CGFloat = 0 }

    private var pinned: Bool { detailPinned }
    private var title: String { detail?.title ?? series.title }

    private var useTwoPane: Bool { layout.tier == .expanded && layout.isLandscape && detail != nil }
    private var usePortraitEditorial: Bool { !useTwoPane && !layout.isLandscape && layout.tier != .compact && detail != nil }
    private var isTabletLayout: Bool { useTwoPane || usePortraitEditorial }
    private var epGroupLimit: Int { usePortraitEditorial ? 50 : 30 }
    private var episodeCols: Int { namedEpisodes ? max(2, layout.episodeCols / 2) : layout.episodeCols }

    /// Episodes of the selected line (the default line when there is only one).
    private func episodes(_ d: HGSeriesDetail) -> [HGEpisode] {
        guard let id = lineID, let line = d.lines.first(where: { $0.id == id }) else { return d.episodes }
        return line.episodes
    }
    /// Single long videos pick a 播放源; anything with several episodes (短剧, TVBox, 采集站) picks an episode.
    private func showsEpisodes(_ d: HGSeriesDetail) -> Bool { !series.source.isLongVideo || episodes(d).count > 1 }

    var body: some View {
        Group {
            if useTwoPane, let d = detail {
                twoPane(d)
            } else if usePortraitEditorial, let d = detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        portraitEditorialHero(d)
                        detailBody(d, showActions: false)
                    }
                    .padding(.top, 60)
                    .padding(.bottom, 64)
                    .compatScrollContent()
                }
                .compatOnScrolled(threshold: 220) { if detailPinned != $0 { detailPinned = $0 } }
                .scrollIndicators(.hidden)
            } else {
                phone
            }
        }
        .accessibilityIdentifier("screen.detail")
        .ignoresSafeArea(edges: .top)
        .background(STVTheme.bg.ignoresSafeArea())
        .overlay(alignment: .top) { navBar }
        .overlay(alignment: .bottom) { if !isTabletLayout { playBar } }
        .toolbar(.hidden, for: .navigationBar)
        .zoomDestination(series.id, ns)
        .navigationDestination(isPresented: $pushSheetPlay) {
            VerticalPlayerView(series: series, startIndex: sheetPlayIndex)
        }
        .sheet(isPresented: $showAllEpisodes, onDismiss: {
            if let idx = pendingSheetPlayIndex {
                pendingSheetPlayIndex = nil
                sheetPlayIndex = idx
                pushSheetPlay = true
            }
        }) {
            if let d = detail {
                allEpisodesSheet(d)
            }
        }
        .task {
            if let seededDetail { detail = seededDetail; settle() }
            else if detail == nil { await load() }
        }
        .compatOnChange(of: detail?.episodes.count) { settle() }
        .task(id: detail?.seriesId) {
            guard let d = detail else { return }
            if series.source.isTVBox {
                return // lines and episodes come with the detail
            } else if series.source.isLongVideo {
                if d.episodes.count <= 1 { await loadSources(d) }
            } else {
                seasons = await SeasonStore.seasons(for: d, series: series)
            }
        }
    }

    private var phone: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                hero
                if let d = detail { detailBody(d) }
                else if let error { ErrorStateView(error: error) { Task { await load() } }.padding(.top, 24) }
                else {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(spacing: 12) {
                            ShimmerBlock(corner: 25).frame(height: 50)
                            ShimmerBlock(corner: 25).frame(width: 50, height: 50)
                        }
                        ForEach(0..<3, id: \.self) { i in
                            ShimmerBlock(corner: 6).frame(height: 14).frame(maxWidth: i == 2 ? 190 : .infinity)
                        }
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 6), spacing: 8) {
                            ForEach(0..<12, id: \.self) { _ in ShimmerBlock(corner: 12).frame(height: 44) }
                        }
                        .padding(.top, 8)
                    }
                    .padding(.horizontal, 20).padding(.top, 18)
                }
            }
            .frame(maxWidth: layout.detailMaxWidth)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 110)
            .compatScrollContent()
        }
        .coordinateSpace(name: "detail")
        .compatOnScrolled(threshold: series.isLandscapeCover ? 200 : 230) { if detailPinned != $0 { detailPinned = $0 } }
        .compatOnScrollY { y in
            if y > scrollTrack.turn + 36 { scrollTrack.turn = y; if !barCompact { barCompact = true } }
            else if y < scrollTrack.turn - 36 { scrollTrack.turn = y; if barCompact { barCompact = false } }
        }
        .scrollIndicators(.hidden)
        .compatDefaultScrollAnchor(DebugScroll.anchor)
    }

    private func twoPane(_ d: HGSeriesDetail) -> some View {
        ZStack {
            // Ambient Glass Stage: soft optical poster halo washing seamlessly into the page ground.
            CoverBackdrop(url: cover, fallback: series.cover)
                .opacity(0.24)
                .overlay(
                    LinearGradient(
                        stops: [
                            .init(color: STVTheme.bg.opacity(0.32), location: 0),
                            .init(color: STVTheme.bg.opacity(0.84), location: 0.58),
                            .init(color: STVTheme.bg, location: 1),
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .ignoresSafeArea()
                .allowsHitTesting(false)

            GeometryReader { geo in
                let maxW = min(geo.size.width, layout.detailMaxWidth ?? 1240)
                let leftW = min(max(maxW * 0.36, 330), 420)
                HStack(alignment: .top, spacing: 34) {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 14) {
                            CoverView(url: cover, corner: 20, maxPixelSize: CoverPixels.hero, fallback: series.cover, a11yID: "detail.poster")
                                .aspectRatio(series.isLandscapeCover ? 16 / 9 : 3 / 4, contentMode: .fit)
                                .frame(maxHeight: 430)
                                .modifier(PosterLift(corner: 20))
                            DetailSourceBadge(text: series.source.label)
                            Text(title).dsFont(26, .heavy, relativeTo: .title).foregroundStyle(STVTheme.text)
                            if !heroMeta.isEmpty { Text(heroMeta).dsFont(13.5, relativeTo: .footnote).foregroundStyle(STVTheme.secondary) }
                            DetailInlineStats(detail: d)
                            HStack(spacing: 10) {
                                DetailPlayButton(series: series, multi: episodes(d).count > 1, height: 48, id: "detail.play", enabled: !d.episodes.isEmpty)
                                DetailHeart(series: series, size: 48, id: "detail.favorite")
                            }
                            .padding(.top, 4)
                        }
                        .padding(.bottom, 36)
                    }
                    .scrollIndicators(.hidden)
                    .frame(width: leftW)

                    ScrollView { detailBody(d, showActions: false).padding(.bottom, 52) }
                        .scrollIndicators(.hidden)
                        .frame(maxWidth: .infinity)
                }
                .padding(.top, 70)
                .padding(.horizontal, layout.pagePadding)
                .frame(maxWidth: layout.detailMaxWidth ?? 1240)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    // MARK: - Nav + Sticky Play Bar

    private var navBar: some View {
        LiquidGlassGroup(spacing: 12) {
            ZStack {
                Text(title).dsFont(17, .semibold, relativeTo: .headline).foregroundStyle(STVTheme.text).lineLimit(1)
                    .padding(.horizontal, 68)
                    .opacity(pinned ? 1 : 0).offset(y: pinned ? 0 : 6)
                    .accessibilityHidden(!pinned)
                HStack(spacing: 0) {
                    DSBackButton(tone: overArt ? .overlay : .glass) { dismiss() }
                    Spacer(minLength: 0)
                    ShareLink(item: shareText) {
                        Image(systemName: "square.and.arrow.up")
                            .font(.system(size: 16.5, weight: .semibold))
                            .foregroundStyle(overArt ? .white : STVTheme.text)
                            .frame(width: 44, height: 44)
                            .contentShape(Circle())
                    }
                    .liquidGlass(Circle(), tone: overArt ? .clear : .regular, interactive: true, onMedia: overArt)
                    .buttonStyle(GlassPress())
                    .accessibilityLabel("分享")
                    .accessibilityIdentifier("detail.share")
                }
            }
        }
        .frame(height: 44)
        .padding(.horizontal, 16).padding(.top, 2).padding(.bottom, 8)
        .background { LiquidEdgeFade(visible: pinned) }
        .animation(.easeOut(duration: 0.2), value: pinned)
    }

    /// Bars sit on the artwork (phone layout, before it scrolls away).
    private var overArt: Bool { !pinned && !isTabletLayout }

    private var shareText: String {
        let page = series.page.flatMap { $0.hasPrefix("http") ? "\n" + $0 : nil } ?? ""
        return "《\(title)》 · \(series.source.label)\(page)"
    }

    /// Sticky glass actions: play capsule + coral heart once the inline action row scrolls off-screen.
    private var playBar: some View {
        ZStack(alignment: .bottom) {
            if let d = detail, !d.episodes.isEmpty, actionsOut {
                LiquidGlassGroup(spacing: 12) {
                    HStack(spacing: 10) {
                        DetailPlayButton(series: series, multi: episodes(d).count > 1, height: 50, expand: !barCompact, floating: true, id: "detail.playbar")
                        DetailHeart(series: series, size: 50, id: "detail.bar.favorite")
                        if barCompact { Spacer(minLength: 0) }
                    }
                }
                .padding(.horizontal, 20).padding(.bottom, 4)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(DS.panel, value: barCompact)
        .animation(DS.panel, value: actionsOut)
    }

    // MARK: - Hero (Portrait Editorial vs 16:9 Widescreen Cinema)

    private var cover: String { DetailCover.pick(detail: detail?.cover ?? "", list: series.cover) }

    @ViewBuilder private var hero: some View {
        if series.isLandscapeCover {
            landscapeHero
        } else {
            portraitHero
        }
    }

    private var portraitHero: some View {
        ZStack(alignment: .bottomLeading) {
            CoverBackdrop(url: cover, fallback: series.cover)
                .frame(maxWidth: .infinity).frame(height: 312)
                .liquidBackgroundExtension()
                .modifier(StretchyTop(height: 312))
                .overlay(
                    LinearGradient(
                        stops: [
                            .init(color: .black.opacity(0.48), location: 0),
                            .init(color: .black.opacity(0.14), location: 0.30),
                            .init(color: STVTheme.bg.opacity(0.68), location: 0.72),
                            .init(color: STVTheme.bg.opacity(0.95), location: 0.90),
                            .init(color: STVTheme.bg, location: 1),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .allowsHitTesting(false)

            HStack(alignment: .bottom, spacing: 16) {
                CoverView(url: cover, corner: 14, maxPixelSize: CoverPixels.poster, fallback: series.cover, a11yID: "detail.poster")
                    .frame(width: 112, height: 150)
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(.white.opacity(0.18), lineWidth: 0.8)
                    )
                    .modifier(PosterLift(corner: 14))

                VStack(alignment: .leading, spacing: 7) {
                    DetailSourceBadge(text: series.source.label)
                    Text(title)
                        .dsFont(22, .heavy, relativeTo: .title2)
                        .foregroundStyle(STVTheme.text)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                        .contextMenu { Button("复制剧名", systemImage: "doc.on.doc") { UIPasteboard.general.string = title } }
                    if !heroMeta.isEmpty {
                        Text(heroMeta)
                            .dsFont(13, relativeTo: .footnote)
                            .foregroundStyle(STVTheme.secondary)
                            .lineLimit(1)
                    }
                    if let d = detail {
                        DetailInlineStats(detail: d)
                            .padding(.top, 2)
                    }
                }
                .padding(.bottom, 2)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 2)
        }
        .frame(height: 312)
    }

    private var landscapeHero: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .bottom) {
                CoverView(url: cover, corner: 0, maxPixelSize: CoverPixels.hero, fallback: series.cover, a11yID: "detail.poster", showPlaceholder: false)
                    .frame(maxWidth: .infinity)
                    .frame(height: 248)
                    .clipped()
                    .liquidBackgroundExtension()
                    .modifier(StretchyTop(height: 248))
                    .overlay(
                        LinearGradient(
                            stops: [
                                .init(color: .black.opacity(0.48), location: 0),
                                .init(color: .black.opacity(0.08), location: 0.34),
                                .init(color: .clear, location: 0.62),
                                .init(color: STVTheme.bg.opacity(0.82), location: 0.90),
                                .init(color: STVTheme.bg, location: 1),
                            ],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .allowsHitTesting(false)
            }
            .frame(height: 248)

            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    DetailSourceBadge(text: series.source.label)
                    if !heroMeta.isEmpty {
                        Text(heroMeta)
                            .dsFont(13, relativeTo: .footnote)
                            .foregroundStyle(STVTheme.secondary)
                            .lineLimit(1)
                    }
                }
                Text(title)
                    .dsFont(22, .heavy, relativeTo: .title2)
                    .foregroundStyle(STVTheme.text)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .contextMenu { Button("复制剧名", systemImage: "doc.on.doc") { UIPasteboard.general.string = title } }
                if let d = detail {
                    DetailInlineStats(detail: d)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 4)
        }
    }

    private var heroMeta: String {
        let count = detail.map { max($0.totalEps, $0.episodes.count) } ?? series.episodeCount
        let single = series.source.isLongVideo && count <= 1
        let eps: String? = {
            if let d = detail, !d.episodeLabel.isEmpty { return DS.spaced(d.episodeLabel) }
            if single { return series.duration > 0 ? Fmt.clock(series.duration) : nil }
            return count > 1 ? "全 \(count) 集" : nil
        }()
        return [eps, detail?.author].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    @ViewBuilder private func portraitEditorialHero(_ d: HGSeriesDetail) -> some View {
        HStack(alignment: .center, spacing: 22) {
            CoverView(url: cover, corner: 16, maxPixelSize: CoverPixels.wide, fallback: series.cover, a11yID: "detail.poster")
                .frame(width: 172, height: 230)
                .modifier(PosterLift(corner: 16))

            VStack(alignment: .leading, spacing: 10) {
                DetailSourceBadge(text: series.source.label)
                Text(title).dsFont(26, .heavy, relativeTo: .title).foregroundStyle(STVTheme.text).lineLimit(2)
                if !heroMeta.isEmpty { Text(heroMeta).dsFont(14, relativeTo: .subheadline).foregroundStyle(STVTheme.secondary).lineLimit(1) }
                DetailInlineStats(detail: d)
                HStack(spacing: 10) {
                    DetailPlayButton(series: series, multi: episodes(d).count > 1, height: 48, id: "detail.play", enabled: !d.episodes.isEmpty)
                    DetailHeart(series: series, size: 48, id: "detail.favorite")
                }
                .padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, layout.pagePadding)
        .padding(.vertical, 22)
        .background {
            CoverBackdrop(url: cover, fallback: series.cover).opacity(0.26)
                .overlay(LinearGradient(stops: [.init(color: STVTheme.bg.opacity(0.18), location: 0), .init(color: STVTheme.bg.opacity(0.76), location: 0.56),
                                               .init(color: STVTheme.bg, location: 1)], startPoint: .top, endPoint: .bottom))
        }
    }

    // MARK: - Body

    @ViewBuilder private func detailBody(_ d: HGSeriesDetail, showActions: Bool = true) -> some View {
        if showActions {
            HStack(spacing: 12) {
                DetailPlayButton(series: series, multi: episodes(d).count > 1, height: 50, id: "detail.play", enabled: !d.episodes.isEmpty)
                DetailHeart(series: series, size: 50, id: "detail.favorite")
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)
            .compatOnFrame(in: .named("detail")) { f in
                let out = f.maxY < 96
                if actionsOut != out { actionsOut = out }
            }
        }

        let actorNames = d.cast.isEmpty ? d.actors.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty } : []
        if !d.tags.isEmpty || !actorNames.isEmpty || !d.desc.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                if !d.tags.isEmpty || !actorNames.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(d.tags, id: \.self) { t in
                                Text(t)
                                    .font(.system(size: 11.5, weight: .medium))
                                    .foregroundStyle(STVTheme.secondary)
                                    .padding(.horizontal, 9)
                                    .frame(height: 24)
                                    .background(STVTheme.glass, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                                    .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous).strokeBorder(STVTheme.hairline, lineWidth: 0.6))
                            }
                            ForEach(actorNames, id: \.self) { name in
                                NavigationLink(value: Route.searchFor(name)) {
                                    HStack(spacing: 4) {
                                        Image(systemName: "person.fill").font(.system(size: 9.5)).foregroundStyle(STVTheme.tertiary)
                                        Text(name).font(.system(size: 11.5, weight: .medium)).foregroundStyle(STVTheme.text).lineLimit(1)
                                    }
                                    .padding(.horizontal, 9)
                                    .frame(height: 24)
                                    .background(STVTheme.glass, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                                    .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous).strokeBorder(STVTheme.hairline, lineWidth: 0.6))
                                    .contentShape(Rectangle().inset(by: -6))
                                }
                                .buttonStyle(PressScale())
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    .compatScrollBounceBasedOnSize(axes: .horizontal)
                }

                if !d.desc.isEmpty {
                    intro(d.desc)
                }
            }
            .padding(.top, 16)
        }

        if showsEpisodes(d) {
            if d.lines.count > 1 { lineSection(d).padding(.top, 22) }
            VStack(alignment: .leading, spacing: 0) { episodeSection(d) }
                .padding(.top, d.lines.count > 1 ? 18 : 22)
        } else {
            if d.lines.count > 1 { lineSection(d).padding(.top, 22) }
            if !series.source.isTVBox {
                VStack(alignment: .leading, spacing: 0) { sourcesSection }
                    .padding(.top, 22)
            }
        }

        let shelf = d.relatedSeries.count > 1 ? d.relatedSeries : seasons
        if shelf.count > 1 { relatedShelf(shelf, currentId: d.seriesId).padding(.top, 26) }
        if !d.cast.isEmpty { castRow(d.cast).padding(.top, 24) }
    }

    private func intro(_ text: String) -> some View {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return VStack(alignment: .leading, spacing: 6) {
            Text(trimmed)
                .font(.system(size: 14))
                .foregroundStyle(STVTheme.secondary)
                .lineSpacing(5)
                .lineLimit(introOpen ? nil : 3)
                .truncationMode(.tail)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background {
                    Text(trimmed)
                        .font(.system(size: 14))
                        .lineSpacing(5)
                        .fixedSize(horizontal: false, vertical: true)
                        .hidden()
                        .compatOnSize { introClamped = $0.height > 68 }
                }

            if introClamped {
                Button {
                    DS.tick()
                    withAnimation(DS.panel) { introOpen.toggle() }
                } label: {
                    HStack(spacing: 3) {
                        Text(introOpen ? "收起" : "展开简介")
                        Image(systemName: introOpen ? "chevron.up" : "chevron.down")
                            .font(.system(size: 10, weight: .bold))
                    }
                    .font(.system(size: 12.5, weight: .semibold))
                    .foregroundStyle(STVTheme.accent)
                    .contentShape(Rectangle().inset(by: -8))
                }
                .buttonStyle(PressScale())
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { if introClamped { withAnimation(DS.panel) { introOpen.toggle() } } }
        .padding(.horizontal, 20)
        .accessibilityIdentifier("detail.intro")
    }

    // MARK: - 线路 + 2行紧凑选集预览 + 全部选集半屏抽屉

    private func lineSection(_ d: HGSeriesDetail, inset: CGFloat = 20) -> some View {
        let current = d.lines.contains { $0.id == lineID } ? lineID : d.lines.first?.id
        return VStack(alignment: .leading, spacing: 10) {
            DSSectionHeader(title: "播放线路", note: "\(d.lines.count) 条可选", inset: inset)
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(d.lines) { line in
                            let on = line.id == current
                            Button { pick(line) } label: {
                                CountLabel(title: line.name, count: line.episodes.count)
                            }
                            .buttonStyle(ToneChipStyle(on: on))
                            .accessibilityAddTraits(on ? .isSelected : [])
                            .accessibilityIdentifier("detail.line.\(line.id)")
                            .id(line.id)
                        }
                    }
                    .padding(.horizontal, inset)
                }
                .compatScrollBounceBasedOnSize(axes: .horizontal)
                .onAppear { if let current { proxy.scrollTo(current, anchor: .center) } }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("detail.lines")
    }

    private func pick(_ line: HGPlayLine) {
        guard line.id != lineID else { return }
        DS.tick()
        PlayLinePrefs.set(line.id, for: series)
        withAnimation(DS.select) {
            lineID = line.id
            namedEpisodes = series.source.isLongVideo && EpisodeGroups.named(line.episodes)
        }
        selectResumeGroup()
    }

    @ViewBuilder private func episodeSection(_ d: HGSeriesDetail) -> some View {
        let list = episodes(d)
        let cols = episodeCols
        let previewLimit = cols * 2
        let hasSheet = list.count > previewLimit
        let totalNote = max(d.lines.count > 1 ? 0 : d.totalEps, list.count)

        DSSectionHeader(title: "选集", note: totalNote > 1 ? "共 \(totalNote) 集" : nil, inset: 20) {
            if hasSheet {
                Button {
                    DS.tick()
                    selectResumeGroup()
                    showAllEpisodes = true
                } label: {
                    HStack(spacing: 4) {
                        Text("全部选集")
                        Image(systemName: "chevron.right").font(.system(size: 10.5, weight: .bold))
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(STVTheme.accent)
                    .padding(.horizontal, 11)
                    .frame(height: 28)
                    .background(STVTheme.accentSoft, in: Capsule())
                    .contentShape(Rectangle().inset(by: -6))
                }
                .buttonStyle(PressScale())
                .accessibilityIdentifier("detail.episodes.all")
            } else if list.count > 1 {
                DSOrderToggle(ascending: $ascending, id: "detail.order")
            }
        }

        if !list.isEmpty {
            DetailInlineEpisodePreview(
                series: series,
                episodes: list,
                ascending: ascending,
                named: namedEpisodes,
                columns: cols
            )
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .id("\(lineID ?? "")-\(ascending)")
        }
    }

    private func allEpisodesSheet(_ d: HGSeriesDetail) -> some View {
        let list = episodes(d)
        let ranges = EpisodeGroups.ranges(total: list.count, groupLimit: epGroupLimit)
        let g = ranges.indices.contains(group) ? group : 0
        let range = ranges.isEmpty ? EpisodeRange(0, -1) : ranges[g]
        let totalNote = max(d.lines.count > 1 ? 0 : d.totalEps, list.count)

        return DSSheet(
            title: "全部选集",
            subtitle: "共 \(totalNote) 集 · 点击立即播放",
            closeID: "detail.sheet.close",
            trailing: {
                if list.count > 1 {
                    DSOrderToggle(ascending: $ascending, id: "detail.order")
                }
            }
        ) {
            VStack(alignment: .leading, spacing: 0) {
                if d.lines.count > 1 {
                    let current = d.lines.contains { $0.id == lineID } ? lineID : d.lines.first?.id
                    ScrollViewReader { proxy in
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(d.lines) { line in
                                    let on = line.id == current
                                    Button { pick(line) } label: {
                                        CountLabel(title: line.name, count: line.episodes.count)
                                    }
                                    .buttonStyle(ToneChipStyle(on: on, small: true))
                                    .id(line.id)
                                }
                            }
                            .padding(.horizontal, 20)
                        }
                        .onAppear { if let current { proxy.scrollTo(current, anchor: .center) } }
                    }
                    .padding(.bottom, 10)
                }

                if ranges.count > 1 {
                    let order = ascending ? Array(ranges.indices) : Array(ranges.indices.reversed())
                    ScrollViewReader { proxy in
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(order, id: \.self) { i in
                                    Button {
                                        DS.tick()
                                        withAnimation(DS.select) { group = i }
                                    } label: {
                                        Text(EpisodeGroups.label(ranges[i], episodes: list))
                                    }
                                    .buttonStyle(ToneChipStyle(on: i == g, neutral: true, small: true))
                                    .accessibilityAddTraits(i == g ? .isSelected : [])
                                    .accessibilityIdentifier("detail.range.\(i)")
                                    .id(i)
                                }
                            }
                            .padding(.horizontal, 20)
                            .padding(.vertical, 4)
                        }
                        .compatScrollBounceBasedOnSize(axes: .horizontal)
                        .onAppear { proxy.scrollTo(g, anchor: .center) }
                    }
                    .padding(.bottom, 8)
                }

                ScrollView {
                    if range.end >= range.start {
                        DetailEpisodeGrid(
                            series: series,
                            episodes: list,
                            range: range,
                            ascending: ascending,
                            named: namedEpisodes,
                            columns: episodeCols,
                            onPick: { index in
                                DS.tick()
                                pendingSheetPlayIndex = index
                                showAllEpisodes = false
                            }
                        )
                        .padding(.horizontal, 20)
                        .padding(.top, 6)
                        .padding(.bottom, 28)
                        .id("\(lineID ?? "")-\(g)-\(ascending)")
                    }
                }
            }
        } footer: { EmptyView() }
        .dsSheetChrome([.medium, .large])
    }

    // MARK: - Cast, Sources & Related Seasons

    private func castRow(_ cast: [HGCelebrity]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            DSSectionHeader(title: "主演阵容", inset: 20)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 14) {
                    ForEach(cast) { c in
                        let cell = VStack(spacing: 0) {
                            Group {
                                if c.avatar.isEmpty {
                                    Text(String(c.name.prefix(1)))
                                        .font(.system(size: 19, weight: .semibold)).foregroundStyle(.white)
                                        .frame(width: 56, height: 56)
                                        .background(LinearGradient(colors: [STVTheme.accent, STVTheme.accentWarm], startPoint: .topLeading, endPoint: .bottomTrailing), in: Circle())
                                } else {
                                    ActorAvatar(url: c.avatar, size: 56)
                                }
                            }
                            .overlay(Circle().strokeBorder(STVTheme.hairline, lineWidth: 0.8))
                            Text(c.name).font(.system(size: 12, weight: .semibold)).foregroundStyle(STVTheme.text).lineLimit(1).padding(.top, 6)
                            if !c.role.isEmpty { Text(c.role).font(.system(size: 11)).foregroundStyle(STVTheme.secondary).lineLimit(1) }
                        }.frame(width: 64)
                        if !c.id.isEmpty {
                            NavigationLink(value: Route.actor(c)) { cell }.buttonStyle(PressScale())
                        } else if !c.name.isEmpty {
                            NavigationLink(value: Route.searchFor(c.name)) { cell }.buttonStyle(PressScale())
                        } else {
                            cell
                        }
                    }
                }.padding(.horizontal, 20)
            }
        }
    }

    /// 播放源 — 3 columns, clean quality + protocol badge.
    @ViewBuilder private var sourcesSection: some View {
        DSSectionHeader(title: series.source.streamSectionTitle, inset: 20).padding(.bottom, 12)
        if sources.isEmpty {
            if let sourcesError {
                Text(sourcesError).dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.secondary).padding(.horizontal, 20)
                if let t = sourcesVerify {
                    Button { verifySheet = t } label: { PillButtonLabel(title: "去验证", small: true) }.buttonStyle(PressScale())
                        .padding(.horizontal, 20).padding(.top, 10)
                        .sheet(item: $verifySheet) { t in SiteVerifySheet(target: t, onDone: retrySources) }
                }
            } else {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                    ForEach(0..<3, id: \.self) { _ in ShimmerBlock().frame(height: 52) }
                }.padding(.horizontal, 20)
            }
        } else {
            let current = SitePrefs.preferredStream[series.id] ?? sources.first?.id
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                ForEach(sources) { s in
                    let on = s.id == current
                    NavigationLink(value: Route.play(series, 0)) {
                        VStack(spacing: 3) {
                            Text(s.quality ?? "默认").dsFont(15, .semibold, relativeTo: .subheadline).lineLimit(1).minimumScaleFactor(0.8)
                            Text(s.kind == .hls || s.kind == .hlsAES ? "HLS" : "MP4").font(.caption2)
                                .foregroundStyle(on ? STVTheme.accent.opacity(0.85) : STVTheme.secondary)
                        }
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(on ? STVTheme.accentSoft : STVTheme.glass, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .overlay {
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .strokeBorder(on ? STVTheme.accentLine : STVTheme.hairline, lineWidth: on ? 1.1 : 0.6)
                        }
                        .foregroundStyle(on ? STVTheme.accent : STVTheme.text)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressScale(scale: 0.97))
                    .simultaneousGesture(TapGesture().onEnded { SitePrefs.preferredStream[series.id] = s.id })
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private func retrySources() {
        guard let d = detail else { return }
        sourcesError = nil
        sourcesVerify = nil
        Task { await loadSources(d) }
    }

    private func loadSources(_ d: HGSeriesDetail) async {
        guard let ep = d.episodes.first else { return }
        do {
            let r = try await Catalog.resolve(series, ep)
            sources = r.streams
            sourcesError = r.streams.isEmpty ? (r.err ?? "暂无可用播放源") : nil
            sourcesVerify = nil
        } catch { sourcesError = error.localizedDescription; sourcesVerify = error.verifyTarget }
    }

    /// `系列` shelf: 104 pt posters, the current season ringed in accent with a 当前 badge.
    @ViewBuilder private func relatedShelf(_ items: [HGSeries], currentId: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            DSSectionHeader(title: "同系列剧集", note: "共 \(items.count) 季", inset: 20) {
                NavigationLink(value: Route.searchFor(SeasonParser.parse(detail?.title ?? series.title).base)) {
                    MoreLabel(title: "搜全系列", icon: "magnifyingglass")
                }
                .accessibilityIdentifier("detail.series.search")
            }
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(items) { s in
                        let on = s.seriesId == currentId
                        if on { relatedPoster(s, current: true, label: SeasonStore.label(s, in: items)) }
                        else {
                            NavigationLink(value: Route.series(s)) { relatedPoster(s, current: false, label: SeasonStore.label(s, in: items)) }
                                .buttonStyle(PressScale(scale: 0.97))
                        }
                    }
                }
                .padding(.horizontal, 20).padding(.top, 4)
            }
        }
    }

    private func relatedPoster(_ s: HGSeries, current: Bool, label: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            CoverView(url: s.cover, corner: 12, maxPixelSize: CoverPixels.poster)
                .frame(width: 104, height: 139)
                .zoomSource(s.id, ns)
                .overlay(alignment: .topLeading) {
                    if current {
                        Text("当前").font(.system(size: 10.5, weight: .bold)).foregroundStyle(STVTheme.onAccent)
                            .padding(.horizontal, 6).frame(height: 18)
                            .background(STVTheme.accent, in: RoundedRectangle(cornerRadius: 5, style: .continuous))
                            .padding(6)
                    }
                }
                .background {
                    if current {
                        RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(STVTheme.accent, lineWidth: 2).padding(-4)
                    }
                }
            Text(s.title).font(.system(size: 13, weight: .semibold)).foregroundStyle(STVTheme.text).lineLimit(1).padding(.top, 8)
            Text([label, s.episodeCount > 0 ? "\(s.episodeCount) 集" : nil].compactMap { $0 }.joined(separator: " · "))
                .font(.system(size: 11)).foregroundStyle(STVTheme.secondary).lineLimit(1).padding(.top, 2)
        }
        .frame(width: 104, alignment: .leading)
    }

    /// Once per loaded detail: remembered line, episode naming, resume group.
    private func settle() {
        guard let d = detail else { return }
        if lineID == nil, let id = PlayLinePrefs.preferred(series), d.lines.contains(where: { $0.id == id }) { lineID = id }
        namedEpisodes = series.source.isLongVideo && EpisodeGroups.named(episodes(d))
        selectResumeGroup()
    }

    private func selectResumeGroup() {
        guard let d = detail, let r = LibraryStore.shared.progress(for: series) else { return }
        let ranges = EpisodeGroups.ranges(total: episodes(d).count, groupLimit: epGroupLimit)
        if let i = ranges.firstIndex(where: { r.episodeIndex >= $0.start && r.episodeIndex <= $0.end }) {
            group = i
        }
    }

    private func load() async {
        do {
            let d = try await Catalog.detail(series)
            detail = d
            error = nil
            settle()
            if series.source == .hongguo {
                let i = LibraryStore.shared.progress(for: series)?.episodeIndex ?? 0
                if d.episodes.indices.contains(i) { HongguoClient.shared.prefetch(vids: [d.episodes[i].vid]) }
                if let e = await Catalog.enriched(series, d), detail?.seriesId == e.seriesId { detail = e }
            }
        } catch { self.error = error }
    }
}

// MARK: - Editorial Header Badges & Metrics

private struct DetailSourceBadge: View {
    let text: String
    var body: some View {
        HStack(spacing: 5) {
            Circle().fill(STVTheme.accent).frame(width: 5, height: 5)
            Text(text).font(.system(size: 11, weight: .semibold)).lineLimit(1)
        }
        .foregroundStyle(STVTheme.accent)
        .padding(.horizontal, 8)
        .frame(height: 22)
        .background(STVTheme.accentSoft, in: Capsule())
        .overlay(Capsule().strokeBorder(STVTheme.accentLine.opacity(0.45), lineWidth: 0.6))
    }
}

/// Borderless inline metrics strip replacing the old 1/3-width lopsided stats card (0 height when empty).
private struct DetailInlineStats: View {
    let detail: HGSeriesDetail

    private struct Metric {
        let icon: String?
        let tint: Color
        let value: String
        let unit: String
    }

    private var metrics: [Metric] {
        var out: [Metric] = []
        if detail.rating > 0 {
            out.append(Metric(icon: "star.fill", tint: STVTheme.gold, value: String(format: "%.1f", detail.rating), unit: "分"))
        }
        if !detail.hotText.isEmpty {
            out.append(Metric(icon: "flame.fill", tint: STVTheme.accentWarm, value: DS.spaced(detail.hotText), unit: "热度"))
        }
        if detail.favoriteCount > 0 {
            out.append(Metric(icon: nil, tint: STVTheme.secondary, value: DS.count(detail.favoriteCount), unit: "收藏"))
        }
        return out
    }

    var body: some View {
        let items = metrics
        if !items.isEmpty {
            HStack(spacing: 10) {
                ForEach(Array(items.enumerated()), id: \.offset) { idx, m in
                    if idx > 0 {
                        Rectangle().fill(STVTheme.hairline).frame(width: 1, height: 11)
                    }
                    HStack(spacing: 3) {
                        if let icon = m.icon {
                            Image(systemName: icon).font(.system(size: 11, weight: .bold)).foregroundStyle(m.tint)
                        }
                        Text(m.value).font(.system(size: 13, weight: .bold).monospacedDigit()).foregroundStyle(STVTheme.text)
                        Text(m.unit).font(.system(size: 11.5)).foregroundStyle(STVTheme.secondary)
                    }
                    .lineLimit(1)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("detail.stats")
        }
    }
}

// MARK: - Leaves Observing LibraryStore
// Playback writes progress every few seconds; only these leaf views re-render.

/// High-contrast cinema primary CTA (deep ink in Light Mode, luminous white in Dark Mode) with embedded progress bar.
private struct DetailPlayButton: View {
    let series: HGSeries
    let multi: Bool
    var height: CGFloat = 50
    var expand = true
    var floating = false
    var id: String? = nil
    var enabled = true
    @ObservedObject private var library = LibraryStore.shared

    private var resume: WatchProgress? { library.progress(for: series) }

    private var title: String {
        guard let r = resume else { return "立即播放" }
        return series.source.isLongVideo && !multi ? "继续 " + Fmt.clock(Int(r.seconds)) : "继续第 \(r.episodeNumber) 集"
    }

    private var subLabel: String? {
        guard let r = resume, expand else { return nil }
        let pct = Int((r.fraction * 100).rounded())
        guard pct >= 3 && pct < 99 else { return nil }
        return "\(pct)%"
    }

    var body: some View {
        let frac = resume?.fraction ?? 0
        NavigationLink(value: Route.play(series, resume?.episodeIndex)) {
            HStack(spacing: 8) {
                Image(systemName: "play.fill")
                    .font(.system(size: 13, weight: .bold))
                Text(title)
                    .font(.system(size: 16, weight: .bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                if let sub = subLabel {
                    Text(sub)
                        .font(.system(size: 12, weight: .semibold).monospacedDigit())
                        .opacity(0.62)
                }
            }
            .foregroundStyle(STVTheme.bg)
            .padding(.horizontal, 22)
            .frame(maxWidth: expand ? .infinity : nil)
            .frame(height: height)
            .background(STVTheme.text)
            .overlay(alignment: .bottomLeading) {
                if frac > 0.02 {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Color(red: 0.157, green: 0.427, blue: 0.937).opacity(0.20)
                            Color(red: 0.157, green: 0.427, blue: 0.937)
                                .frame(width: geo.size.width * min(1, max(0.06, frac)))
                        }
                    }
                    .frame(height: 3.5)
                    .allowsHitTesting(false)
                }
            }
            .clipShape(Capsule())
            .shadow(color: STVTheme.barShadow, radius: floating ? 12 : 6, y: floating ? 5 : 3)
            .contentShape(Capsule())
        }
        .buttonStyle(PressScale(scale: 0.97))
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
        .accessibilityIdentifier(id ?? "detail.play")
    }
}

/// Coral-crimson favorite button placed directly beside the primary Play CTA.
private struct DetailHeart: View {
    let series: HGSeries
    var size: CGFloat = 50
    var id = "detail.favorite"
    @ObservedObject private var library = LibraryStore.shared

    var body: some View {
        let on = library.isFavorite(series)
        Button {
            DS.tick()
            withAnimation(DS.press) { library.toggleFavorite(series) }
        } label: {
            Image(systemName: on ? "heart.fill" : "heart")
                .font(.system(size: 19, weight: .semibold))
                .foregroundStyle(on ? STVTheme.favorite : STVTheme.text)
                .compatSymbolReplace()
                .frame(width: size, height: size)
                .background {
                    if on {
                        Circle().fill(STVTheme.favoriteSoft)
                            .overlay(Circle().strokeBorder(STVTheme.favoriteLine, lineWidth: 1))
                    } else {
                        Circle().fill(Color.clear).liquidGlass(Circle(), interactive: true)
                    }
                }
                .contentShape(Circle())
        }
        .buttonStyle(GlassPress())
        .accessibilityLabel(on ? "取消收藏" : "收藏")
        .accessibilityIdentifier(id)
    }
}

/// Inline 2-row preview grid anchored around the current resume episode.
private struct DetailInlineEpisodePreview: View {
    let series: HGSeries
    let episodes: [HGEpisode]
    let ascending: Bool
    let named: Bool
    let columns: Int
    @ObservedObject private var library = LibraryStore.shared

    var body: some View {
        let resume = library.progress(for: series)?.episodeIndex
        let range = EpisodeGroups.previewRange(total: episodes.count, columns: columns, rows: 2, resume: resume, ascending: ascending)
        if range.end >= range.start {
            EpisodeCells(
                series: series,
                episodes: episodes,
                range: range,
                ascending: ascending,
                named: named,
                columns: columns,
                resume: resume,
                onPick: nil
            )
        }
    }
}

/// Full group grid used inside the half-screen All-Episodes sheet (and any inline callers).
private struct DetailEpisodeGrid: View {
    let series: HGSeries
    let episodes: [HGEpisode]
    let range: EpisodeRange
    let ascending: Bool
    let named: Bool
    let columns: Int
    var onPick: ((Int) -> Void)? = nil
    @ObservedObject private var library = LibraryStore.shared

    var body: some View {
        EpisodeCells(
            series: series,
            episodes: episodes,
            range: range,
            ascending: ascending,
            named: named,
            columns: columns,
            resume: library.progress(for: series)?.episodeIndex,
            onPick: onPick
        )
    }
}

private struct EpisodeCells: View {
    let series: HGSeries
    let episodes: [HGEpisode]
    let range: EpisodeRange
    let ascending: Bool
    let named: Bool
    let columns: Int
    let resume: Int?
    let onPick: ((Int) -> Void)?

    var body: some View {
        let order = ascending ? Array(range.start...range.end) : Array((range.start...range.end).reversed())
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: columns), spacing: 8) {
            ForEach(order, id: \.self) { i in
                if episodes.indices.contains(i) { cell(i, episodes[i]) }
            }
        }
    }

    @ViewBuilder private func cell(_ i: Int, _ e: HGEpisode) -> some View {
        let on = resume == i
        let watched = resume.map { i < $0 } ?? false
        let label = cellLabel(e, on: on, watched: watched)
        if let onPick {
            Button { onPick(i) } label: { label }
                .buttonStyle(PressScale(scale: 0.94))
                .accessibilityLabel(named && !e.title.isEmpty ? e.title : "第 \(e.ep) 集")
                .accessibilityValue(on ? "上次看到" : (watched ? "已看" : ""))
        } else {
            NavigationLink(value: Route.play(series, i)) { label }
                .buttonStyle(PressScale(scale: 0.94))
                .accessibilityLabel(named && !e.title.isEmpty ? e.title : "第 \(e.ep) 集")
                .accessibilityValue(on ? "上次看到" : (watched ? "已看" : ""))
        }
    }

    private func cellLabel(_ e: HGEpisode, on: Bool, watched: Bool) -> some View {
        Text(named && !e.title.isEmpty ? e.title : "\(e.ep)")
            .dsFont(named ? 12.5 : 15, on ? .bold : .semibold, relativeTo: .subheadline)
            .lineLimit(named ? 2 : 1)
            .minimumScaleFactor(0.8)
            .multilineTextAlignment(.center)
            .foregroundStyle(on ? STVTheme.accent : (watched ? STVTheme.secondary.opacity(0.75) : STVTheme.text))
            .padding(.horizontal, named ? 8 : 2)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(
                on ? STVTheme.accentSoft : (watched ? STVTheme.rail : STVTheme.glass),
                in: RoundedRectangle(cornerRadius: 12, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(on ? STVTheme.accentLine : STVTheme.hairline, lineWidth: on ? 1.2 : 0.6)
            }
            .overlay(alignment: .bottom) {
                if on {
                    Capsule()
                        .fill(STVTheme.accent)
                        .frame(width: 12, height: 2.5)
                        .padding(.bottom, 4)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// Poster shadow from a plain shape (the cover itself is never flattened for it).
private struct PosterLift: ViewModifier {
    let corner: CGFloat
    func body(content: Content) -> some View {
        content.background {
            RoundedRectangle(cornerRadius: corner, style: .continuous)
                .fill(Color.black.shadow(.drop(color: .black.opacity(0.34), radius: 14, y: 10)))
                .padding(1)
        }
    }
}

/// Artwork grows with the pull-down instead of leaving a gap above it.
private struct StretchyTop: ViewModifier {
    let height: CGFloat
    func body(content: Content) -> some View {
        if #available(iOS 17.0, *) {
            content.visualEffect { c, p in
                let pull = max(0, p.frame(in: .named("detail")).minY)
                return c.scaleEffect(1 + pull / height, anchor: .bottom)
            }
        } else {
            content
        }
    }
}

/// Simulator screenshot host for the related-seasons shelf (`STV_DETAIL_SHOT=1`).
/// Set `STV_LIVE_SERIES=<seriesId>` to load a real franchise instead of mock data.
struct DetailRelatedShotHost: View {
    @State private var series = HGSeries(
        seriesId: "7000001", title: "霸总的秘密助理",
        cover: "https://picsum.photos/seed/stv1/600/900", episodeCount: 12)
    @State private var seed: HGSeriesDetail?
    @State private var loading = true
    var body: some View {
        NavigationStack {
            Group {
                if let seed {
                    DramaDetailView(series: series, seededDetail: seed)
                        .id(seed.seriesId + "-\(seed.relatedSeries.count)")
                } else if loading {
                    DSLoading(tint: .white).frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    DramaDetailView(series: series, seededDetail: .previewDetail)
                }
            }
        }
        .preferredColorScheme(.dark)
        .background(STVTheme.bg)
        .task {
            let live = ProcessInfo.processInfo.environment["STV_LIVE_SERIES"] ?? ""
            if HongguoClient.looksSID(live), let d = try? await HongguoClient.shared.detail(seriesId: live) {
                series = HGSeries(seriesId: d.seriesId, title: d.title, cover: d.cover, episodeCount: max(d.totalEps, d.episodes.count))
                seed = d
            } else {
                seed = .previewDetail
            }
            loading = false
        }
    }
}

/// One-shot live probe: franchise landing for a known multi-season stick_id.
struct ResolveProbeHost: View {
    @State private var lines: [String] = ["probing…"]
    private var vid: String {
        let e = ProcessInfo.processInfo.environment["STV_PROBE_VID"] ?? ""
        return e.isEmpty ? "7683440834066271256" : e
    }
    var body: some View {
        ScrollView { Text(lines.joined(separator: "\n")).font(.system(.footnote, design: .monospaced)).padding() }
            .task { await run() }
    }
    private func run() async {
        var out: [String] = []
        let d = HGPlayerDevice.current()
        out.append("player device src=\(d.source ?? "?") did=\(d.deviceId) (len=\(d.deviceId.count))")
        do {
            let r = try await HongguoClient.shared.resolve(vid: vid)
            out.append("OK streams=\(r.streams.count) err=\(r.err ?? "nil") duration=\(r.duration.map(String.init(describing:)) ?? "nil")")
            NSLog("[HG] probe resolve OK streams=%d", r.streams.count)
        } catch {
            out.append("FAIL \(error.localizedDescription)")
            NSLog("[HG] probe resolve FAIL %@", error.localizedDescription)
        }
        lines = out
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("resolve-probe.txt")
        try? out.joined(separator: "\n").write(to: url, atomically: true, encoding: .utf8)
        NSLog("[HG] probe wrote %@", url.path)
    }
}

struct RelatedProbeHost: View {
    @State private var text = "probing…"
    var body: some View {
        ScrollView {
            Text(text).font(.system(.caption, design: .monospaced)).foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .leading).padding()
        }
        .background(Color.black)
        .task { await run() }
    }
    private func run() async {
        let sid = "7661844828728085528" // 被贬北凉… S1 from gateway tests
        var lines: [String] = ["stick_ids=" + sid]
        do {
            let items = try await HongguoClient.shared.seriesLanding(stickIds: sid)
            lines.append("landing count=\(items.count)")
            for s in items.prefix(20) {
                lines.append("\(s.seriesId) | \(s.title) | eps=\(s.episodeCount)")
            }
            let d = try await HongguoClient.shared.detail(seriesId: sid)
            lines.append("detail related=\(d.relatedSeries.count) title=\(d.title)")
            for s in d.relatedSeries.prefix(20) {
                lines.append("rel \(s.seriesId) | \(s.title)")
            }
        } catch {
            lines.append("ERR \(error)")
        }
        text = lines.joined(separator: "\n")
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("related-probe.txt")
        try? text.write(to: url, atomically: true, encoding: .utf8)
        lines.append("wrote \(url.path)")
        text = lines.joined(separator: "\n")
    }
}
