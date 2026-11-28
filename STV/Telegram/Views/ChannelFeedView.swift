import Combine
import SwiftUI

/// The merged feed, prepared off the main thread. Sorting and running every ad rule (regexes included) over up to 600 posts
/// per channel used to happen inside `body`, several times per store change.
@MainActor
final class TGFeedModel: ObservableObject {
    static let shared = TGFeedModel()
    struct Snapshot {
        var all: [TGStore.Entry] = []
        var byChannel: [String: [TGStore.Entry]] = [:]
        var counts: [String: (total: Int, blocked: Int)] = [:]
        var total: (total: Int, blocked: Int) = (0, 0)
    }
    @Published private(set) var snapshot = Snapshot()
    /// False until the first build finished.
    @Published private(set) var ready = false
    private var bag: Set<AnyCancellable> = []
    private var generation = 0

    private init() {
        TGStore.shared.objectWillChange.merge(with: SourceGate.shared.objectWillChange)
            .debounce(for: .milliseconds(60), scheduler: DispatchQueue.main)
            .sink { [weak self] _ in self?.rebuild() }
            .store(in: &bag)
        rebuild()
    }

    func entries(_ only: String?) -> [TGStore.Entry] { only.map { snapshot.byChannel[$0.lowercased()] ?? [] } ?? snapshot.all }
    func counts(_ only: String?) -> (total: Int, blocked: Int) { only.map { snapshot.counts[$0.lowercased()] ?? (0, 0) } ?? snapshot.total }

    func rebuild() {
        generation += 1
        let token = generation, s = TGStore.shared
        let channels = s.visibleChannels, posts = s.posts, rules = s.rules, heuristics = s.heuristics, hide = s.mode == .hide
        Task { [weak self] in
            let built = await Task.detached(priority: .userInitiated) { Self.build(channels, posts, rules, heuristics, hide) }.value
            guard let self, token == self.generation else { return }
            self.snapshot = built
            if !self.ready { self.ready = true }
        }
    }

    private nonisolated static func build(_ channels: [TGChannel], _ posts: [String: [TGPost]], _ rules: [TGRule], _ heuristics: Bool, _ hide: Bool) -> Snapshot {
        var out = Snapshot()
        for c in channels {
            var list: [TGStore.Entry] = [], blocked = 0
            let mine = posts[c.id] ?? []
            for p in mine {
                let v = TGFilter.verdict(p, rules: rules, heuristics: heuristics, channelAdBlock: c.adBlock)
                if v.blocked { blocked += 1; if hide { continue } }
                list.append(TGStore.Entry(post: p, verdict: v))
            }
            list.sort(by: newer)
            out.byChannel[c.id] = list
            out.counts[c.id] = (mine.count, blocked)
            out.total.total += mine.count
            out.total.blocked += blocked
            out.all += list
        }
        out.all.sort(by: newer)
        return out
    }

    private nonisolated static func newer(_ a: TGStore.Entry, _ b: TGStore.Entry) -> Bool {
        (a.post.date ?? .distantPast, a.post.msgId) > (b.post.date ?? .distantPast, b.post.msgId)
    }
}

/// 「频道」: merged newest-first feed of subscribed public channels (朋友圈 style).
/// Embedded as a home source page and also pushable from 我的.
struct ChannelFeedView: View {
    var embedded = false
    @ObservedObject private var store = TGStore.shared
    @ObservedObject private var feed = TGFeedModel.shared
    @ObservedObject private var gate = SourceGate.shared
    @State private var only: String?
    @State private var viewer: ViewerPayload?
    @State private var blockTarget: TGPost?
    @State private var botTarget: TGBotLink?
    @State private var toast: String?
    @State private var addText = ""
    @State private var actions = TGActions()
    @Environment(\.openURL) private var openURL

    struct ViewerPayload: Identifiable { let id = UUID(); let urls: [String]; let index: Int }

    var body: some View {
        let entries = feed.entries(only)
        let list = ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if embedded { toolbarRow }
                channelChips.padding(.bottom, 8)
                errorBanners
                if store.visibleChannels.isEmpty {
                    emptySubscriptions
                } else if entries.isEmpty {
                    if !store.loading.isEmpty || !feed.ready || !store.cacheLoaded { DSLoading().padding(.top, 40) }
                    else { DSEmptyState("还没有内容", systemImage: "paperplane", message: "下拉刷新，或点右上角 ↻").padding(.top, 20) }
                }
                ForEach(entries) { e in
                    TGPostCard(post: e.post, channel: store.channel(e.post.channel), verdict: e.verdict, actions: actions)
                        .padding(.horizontal, e.verdict.blocked ? 16 : 0).padding(.vertical, e.verdict.blocked ? 4 : 0)
                    Hairline().padding(.leading, 66)
                }
                if !entries.isEmpty && store.hasMore(only) {
                    DSLoading()
                        .onAppear { Task { await store.loadMore(only) } }
                        .accessibilityIdentifier("tg.loadMore")
                }
            }
            .padding(.bottom, 24)
            .compatScrollContent()
        }
        .scrollIndicators(.hidden)
        .refreshable { await store.refresh(only) }
        Group {
            if embedded { list.dsToast($toast, bottom: 16).homePageInset() }
            else { list.dsToast($toast).dsNavigation("频道") { tools } }
        }
        .onAppear { wire() }
        .task {
            while !store.cacheLoaded, !Task.isCancelled { try? await Task.sleep(nanoseconds: 40_000_000) }
            if store.visibleChannels.allSatisfy({ (store.posts[$0.id] ?? []).isEmpty }) { await store.refresh() }
        }
        .fullScreenCover(item: $viewer) { v in
            ImageViewer(items: v.urls.map { .init(url: $0) }, index: v.index, onSave: { try await TGDownloads.savePhoto($0.url) })
        }
        .sheet(item: $blockTarget) { p in TGBlockSheet(post: p) }
        .sheet(item: Binding(get: { botTarget.map { BotBox(link: $0) } }, set: { botTarget = $0?.link })) { b in TGBotSheet(link: b.link) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("tg.feed")
    }

    private struct BotBox: Identifiable { let link: TGBotLink; var id: String { link.bot + link.payload } }

    private func wire() {
        actions.openImages = { p, i in
            let photos = p.media.enumerated().filter { $0.element.kind == .photo }
            let urls = photos.map(\.element.thumb)
            viewer = ViewerPayload(urls: urls, index: photos.firstIndex { $0.offset == i } ?? 0)
        }
        actions.playVideo = { p, i in
            guard let s = TGVideoProvider.series(p, media: i, channel: store.channel(p.channel)) else {
                toast = "视频过大，网页预览不提供直链，请在 Telegram 中打开"
                if let u = URL(string: p.media[i].link.isEmpty ? p.permalink : p.media[i].link) { openURL(u) }
                return
            }
            Router.push(.play(s, 0))
        }
        actions.download = { p, i in
            if TGDownloads.shared.enqueue(post: p, media: i, channel: store.channel(p.channel)) != nil { toast = "已加入下载队列" }
            else { toast = "这条媒体没有可下载的直链" }
        }
        actions.block = { blockTarget = $0 }
        actions.bot = { botTarget = $0 }
    }

    private var toolbarRow: some View {
        HStack(spacing: 12) {
            let c = feed.counts(only)
            Text(store.loading.isEmpty ? "\(c.total) 条 · 已屏蔽 \(c.blocked)" : "刷新中…").dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary)
                .accessibilityIdentifier("tg.stats")
            Spacer(minLength: 0)
            tools
        }
        .padding(.horizontal, 16).padding(.top, 2).padding(.bottom, 10)
    }

    /// One glass capsule with the feed's four actions.
    private var tools: some View {
        HStack(spacing: 0) {
            NavigationLink(value: Route.tgSearch(only)) { toolIcon("magnifyingglass") }.accessibilityIdentifier("tg.search").accessibilityLabel("搜索频道")
            NavigationLink(value: Route.downloads) { toolIcon("arrow.down.circle") }.accessibilityIdentifier("tg.downloads").accessibilityLabel("下载管理")
            NavigationLink(value: Route.tgManage) { toolIcon("slider.horizontal.3") }.accessibilityIdentifier("tg.manage").accessibilityLabel("频道管理")
            Button { Task { await store.refresh(only) } } label: { toolIcon("arrow.clockwise") }
                .accessibilityIdentifier("tg.refresh").accessibilityLabel("刷新")
        }
        .buttonStyle(GlassPress())
        .padding(.horizontal, 4)
        .liquidGlassCapsule(interactive: true)
    }

    private func toolIcon(_ name: String) -> some View {
        Image(systemName: name).font(.system(size: 16, weight: .medium)).foregroundStyle(STVTheme.text).frame(width: 38, height: 40).contentShape(Rectangle())
    }

    private var channelChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                GlassChip(title: "全部", selected: only == nil) { only = nil }
                ForEach(store.visibleChannels) { c in
                    GlassChip(title: c.displayName, selected: only == c.id) { only = c.id }
                        .accessibilityIdentifier("tg.chip.\(c.id)")
                }
            }.padding(.horizontal, 16)
        }
        .compatScrollBounceBasedOnSize(axes: .horizontal)
    }

    @ViewBuilder private var errorBanners: some View {
        let errs = store.visibleChannels.filter { (only == nil || $0.id == only) && store.errors[$0.id] != nil }
        ForEach(errs) { c in
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 14)).foregroundStyle(STVTheme.accentWarm)
                VStack(alignment: .leading, spacing: 8) {
                    Text(c.displayName + (c.subscribers.isEmpty ? "" : " · " + c.subscribers)).dsFont(13, .semibold, relativeTo: .footnote).foregroundStyle(STVTheme.text)
                    Text(store.errors[c.id] ?? "").dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            if store.needsTDLogin.contains(c.id) && !TDLibService.shared.state.isReady {
                                Button { Router.push(.tdlib) } label: { MiniButtonLabel(title: "去登录 TDLib") }.accessibilityIdentifier("tg.error.login.\(c.id)")
                            }
                            if store.tdRestricted.contains(c.id), TDLibService.shared.sensitive.canChange, !TDLibService.shared.sensitive.enabled {
                                Button {
                                    Task { try? await TDLibService.shared.setSensitive(true); await store.refresh(c.id) }
                                } label: { MiniButtonLabel(title: "显示敏感内容") }.accessibilityIdentifier("tg.error.sensitive.\(c.id)")
                            }
                            Button { if let u = URL(string: "https://t.me/\(c.username)") { openURL(u) } } label: { MiniButtonLabel(title: "在 Telegram 中打开", tint: STVTheme.secondary, ground: STVTheme.glass) }
                        }
                        .buttonStyle(PressScale())
                    }
                    .compatScrollBounceBasedOnSize(axes: .horizontal)
                }
                Spacer(minLength: 0)
            }
            .padding(14)
            .background(STVTheme.warmSoft, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .padding(.horizontal, 16).padding(.bottom, 8)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("tg.error.\(c.id)")
        }
    }

    private var emptySubscriptions: some View {
        VStack(spacing: 14) {
            DSEmptyState("还没有订阅频道", systemImage: "paperplane", message: "输入公开频道用户名或链接，例如 telegram、t.me/durov").padding(.bottom, -14)
            HStack(spacing: 10) {
                DSTextField("频道用户名 / 链接", text: $addText, icon: "at", id: "tg.empty.field").textInputAutocapitalization(.never).autocorrectionDisabled()
                Button {
                    if store.add(addText) != nil { addText = ""; Task { await store.refresh() } } else { toast = "无效的频道名" }
                } label: { PillButtonLabel(title: "订阅", height: 46) }
                    .buttonStyle(PressScale()).accessibilityIdentifier("tg.empty.add")
            }
            .padding(.horizontal, 24)
            if !gate.showAdultChannels && store.channels.contains(where: \.isAdult) {
                Text("预置的成人频道已隐藏：我的 → 片源管理 → 成人 Telegram 频道").dsFont(11, relativeTo: .caption2).foregroundStyle(STVTheme.tertiary)
            }
        }
        .frame(maxWidth: .infinity).padding(.top, 20)
    }
}

/// Global navigation helper so cards deep in a feed can push routes on the active stack.
@MainActor enum Router {
    static var handler: (Route) -> Void = { _ in }
    static func push(_ r: Route) { handler(r) }
}
