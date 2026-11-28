import SwiftUI
import UniformTypeIdentifiers

/// 书源管理: import Legado JSON / `.js` sources, search/filter, batch-check, debug, purify rules, edit, export.
struct NovelSourcesView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var store = LegadoSourceStore.shared
    @State private var importing = false
    @State private var login: LegadoSource?
    @State private var debugging: LegadoSource?
    @State private var editing: LegadoSource?
    @State private var showChecker = false
    @State private var showReplaceRules = false
    @State private var query = ""
    @State private var filter: Filter = .all

    enum Filter: String, CaseIterable {
        case all = "全部", on = "已启用", login = "需登录", js = "JS源", failed = "校验失败", unsupported = "不支持"
    }

    private struct Board {
        var counts: [Filter: Int] = [:]
        var loggedIn: Set<String> = []
        var checks: [String: LegadoTools.CheckResult] = [:]
        var enabled = 0
        var total = 0
    }
    @State private var board = Board()

    private func classify() {
        var b = Board()
        b.total = store.sources.count
        b.checks = LegadoTools.allChecks()
        for s in store.sources {
            let logged = s.hasLogin && LegadoSourceState.isLoggedIn(s.bookSourceUrl)
            if logged { b.loggedIn.insert(s.id) }
            b.counts[.all, default: 0] += 1
            if s.isOn && s.supported { b.enabled += 1; b.counts[.on, default: 0] += 1 }
            if s.hasLogin && !logged { b.counts[.login, default: 0] += 1 }
            if s.isJsSource { b.counts[.js, default: 0] += 1 }
            if let chk = b.checks[s.id], chk.status != .ok { b.counts[.failed, default: 0] += 1 }
            if !s.supported { b.counts[.unsupported, default: 0] += 1 }
        }
        board = b
    }

    private var visible: [LegadoSource] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let base: [LegadoSource] = switch filter {
        case .all: store.sources
        case .on: store.sources.filter { $0.isOn && $0.supported }
        case .login: store.sources.filter { $0.hasLogin && !board.loggedIn.contains($0.id) }
        case .js: store.sources.filter(\.isJsSource)
        case .failed: store.sources.filter { if let c = board.checks[$0.id] { c.status != .ok } else { false } }
        case .unsupported: store.sources.filter { !$0.supported }
        }
        guard !q.isEmpty else { return base }
        return base.filter {
            $0.bookSourceName.localizedCaseInsensitiveContains(q)
            || ($0.bookSourceGroup ?? "").localizedCaseInsensitiveContains(q)
            || $0.bookSourceUrl.localizedCaseInsensitiveContains(q)
        }
    }

    var body: some View {
        let list = visible
        List {
            Group {
                Text("共 \(board.total) 个书源 · 已启用 \(board.enabled) 个")
                    .dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.secondary)
                    .padding(.horizontal, 20).padding(.top, 4)
                if !store.sources.isEmpty {
                    DSTextField("搜索书源名 / 分组 / 域名", text: $query, icon: "magnifyingglass")
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(.horizontal, 16).padding(.top, 8)
                }
                filterChips.padding(.top, 10).padding(.bottom, 14)
            }
            .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)
            if store.sources.isEmpty {
                DSEmptyState("还没有书源", systemImage: "books.vertical", message: "导入「阅读」(Legado) 的书源 JSON 或单文件 JS 书源（.js）。App 不内置任何书源。", actionTitle: "导入书源") { importing = true }
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)
            }
            ForEach(Array(list.enumerated()), id: \.element.id) { i, s in
                row(s)
                    .overlay(alignment: .top) { if i > 0 { Hairline() } }
                    .padding(.horizontal, 14)
                    .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                    .listRowSeparator(.hidden)
                    .listRowBackground(CardSlice(first: i == 0, last: i == list.count - 1).padding(.horizontal, 16))
            }
            .onDelete { idx in store.remove(Set(idx.map { list[$0].id })) }
            if !store.sources.isEmpty {
                Text("长按书源可调试、编辑源码或导出 JSON；左滑可删除。").font(.system(size: 12)).foregroundStyle(STVTheme.tertiary)
                    .padding(.horizontal, 30).padding(.top, 14).padding(.bottom, 40)
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .liquidTopBar {
            DSNavBar(title: "书源") {
                DSBackButton { dismiss() }
            } trailing: {
                HStack(spacing: 8) {
                    RoundIconButton(systemName: "wand.and.stars", tone: .glass, size: 40) { showReplaceRules = true }
                        .accessibilityLabel("替换净化规则")
                    if !store.sources.isEmpty {
                        RoundIconButton(systemName: "checkmark.shield", tone: .glass, size: 40) { showChecker = true }
                            .accessibilityLabel("批量校验书源")
                    }
                    RoundIconButton(systemName: "plus", tone: .accent, size: 40) { importing = true }
                        .accessibilityLabel("导入书源").accessibilityIdentifier("sources.add")
                }
            }
        }
        .dsAmbient(cool: true)
        .tint(STVTheme.accent)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { classify() }
        .onReceive(store.$sources.receive(on: DispatchQueue.main)) { _ in classify() }
        .compatOnChange(of: login?.id) { classify() }
        .compatOnChange(of: showChecker) { if !showChecker { classify() } }
        .sheet(item: $login) { s in NovelSourceLoginView(source: s) }
        .sheet(item: $debugging) { s in NovelSourceDebugView(source: s) }
        .sheet(item: $editing) { s in
            NovelSourceEditorView(source: s) { updated in
                store.update(updated, replacing: s.id)
                classify()
            }
        }
        .sheet(isPresented: $showChecker) {
            NovelSourceCheckView(
                sources: visible,
                onDisableFailed: { ids in
                    for s in store.sources where ids.contains(s.id) { store.setEnabled(s, false) }
                    classify()
                },
                onDeleteFailed: { ids in
                    store.remove(ids)
                    classify()
                }
            )
        }
        .sheet(isPresented: $showReplaceRules) { NovelReplaceRulesView() }
        .sheet(isPresented: $importing) { NovelImportSheet(showBooks: false) }
    }

    private var filterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Filter.allCases, id: \.self) { f in
                    Button { DS.tick(); withAnimation(DS.select) { filter = f } } label: {
                        CountLabel(title: f.rawValue, count: board.counts[f] ?? 0)
                    }
                    .buttonStyle(ToneChipStyle(on: filter == f, neutral: f == .all))
                    .accessibilityAddTraits(filter == f ? .isSelected : [])
                    .accessibilityIdentifier("sources.filter.\(f.rawValue)")
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func typeBadge(_ s: LegadoSource) -> String? {
        if s.isJsSource { return "JS" }
        if s.isRSS { return "RSS" }
        switch s.bookSourceType ?? 0 {
        case 1: return "有声"
        case 2: return "漫画"
        case 3: return "下载"
        case 4: return "视频"
        default: return nil
        }
    }

    private func row(_ s: LegadoSource) -> some View {
        let loggedIn = board.loggedIn.contains(s.id)
        let dim = !s.supported
        let check = board.checks[s.id]
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(s.bookSourceName).font(.system(size: 16, weight: .medium)).foregroundStyle(STVTheme.text).lineLimit(1)
                        .opacity(dim ? 0.5 : 1)
                    if let badge = typeBadge(s) { DSStatus(text: badge, tone: .accent) }
                    if !s.supported { DSStatus(text: "暂不支持", tone: .muted) }
                    else if s.hasLogin { DSStatus(text: loggedIn ? "已登录" : "需登录", tone: loggedIn ? .ok : .warn) }
                    if let check {
                        DSStatus(text: check.status == .ok ? "\(check.latencyMs)ms" : check.status.rawValue, tone: check.status == .ok ? .ok : .warn)
                    }
                }
                Text(subtitle(s)).font(.system(size: 12)).foregroundStyle(STVTheme.secondary).lineLimit(1).opacity(dim ? 0.5 : 1)
            }
            Spacer(minLength: 0)
            if s.supported, s.hasLogin, !loggedIn {
                Button { login = s } label: { MiniButtonLabel(title: "登录") }.buttonStyle(PressScale())
            }
            DSSwitchControl(isOn: Binding(get: { s.isOn && s.supported }, set: { store.setEnabled(s, $0) }), label: "启用 \(s.bookSourceName)")
                .disabled(!s.supported)
        }
        .frame(minHeight: 66)
        .contentShape(Rectangle())
        .contextMenu {
            if s.hasLogin { Button("登录", systemImage: "person.crop.circle") { login = s } }
            Button("单源调试", systemImage: "ladybug") { debugging = s }
            Button(s.isJsSource ? "编辑 JS 源码" : "编辑 JSON", systemImage: "chevron.left.forwardslash.chevron.right") { editing = s }
            Button("复制导出", systemImage: "doc.on.doc") {
                UIPasteboard.general.string = s.isJsSource ? (s.mainJs ?? "") : s.prettyJSON()
            }
            Button("删除", systemImage: "trash", role: .destructive) { store.remove([s.id]) }
        }
        .swipeActions(edge: .leading) {
            Button("调试") { debugging = s }.tint(.blue)
            if s.hasLogin { Button("登录") { login = s }.tint(STVTheme.accent) }
        }
    }

    private func subtitle(_ s: LegadoSource) -> String {
        let group = (s.bookSourceGroup ?? "").isEmpty ? "综合" : s.bookSourceGroup!
        if !s.supported { return group + " · 缺少搜索/发现地址或未知源类型" }
        return [group, URL(string: s.bookSourceUrl)?.host].compactMap { $0 }.joined(separator: " · ")
    }
}

/// A list-row background that draws one slice of a rounded card (first / middle / last row).
struct CardSlice: View {
    var first: Bool
    var last: Bool
    var radius: CGFloat = 18
    var body: some View {
        CompatUnevenRoundedRectangle(topLeadingRadius: first ? radius : 0, bottomLeadingRadius: last ? radius : 0,
                               bottomTrailingRadius: last ? radius : 0, topTrailingRadius: first ? radius : 0, style: .continuous)
            .fill(STVTheme.card)
    }
}

// MARK: - 搜书

/// Searches every enabled 书源 at once; results are merged by 书名 + 作者 as sources answer.
struct NovelSearchView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var sources = LegadoSourceStore.shared
    @State private var query = ""
    @State private var groups: [Group] = []
    @State private var done = 0
    @State private var total = 0
    @State private var failures = 0
    @State private var task: Task<Void, Never>?
    @State private var manage = false

    struct Group: Identifiable, Hashable {
        var name: String
        var author: String
        var items: [LegadoSearchItem]
        var id: String { name + "|" + author }
    }

    @State private var submitted = ""
    /// Results waiting for the next flush.
    @State private var pendingItems: [LegadoSearchItem] = []
    @FocusState private var focused: Bool

    /// Unified STVTheme 0-card search: floating glass search bar, live progress line, open hairline results.
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if sources.enabled.isEmpty {
                        empty("还没有可用的书源", "先在「书源」里导入「阅读」(Legado) 书源", action: ("管理书源", { manage = true }))
                    } else if total > 0 {
                        progress.padding(.horizontal, 18).padding(.top, 10)
                        LazyVStack(spacing: 0) {
                            ForEach(Array(groups.enumerated()), id: \.element.id) { i, g in
                                NavigationLink(value: g) { resultRow(g, lead: i == 0) }
                                    .buttonStyle(PressScale(scale: 0.985))
                                    .overlay(alignment: .top) {
                                        if i > 0 { Hairline().padding(.leading, i == 1 ? 0 : 71) }
                                    }
                                    .accessibilityIdentifier("search.result.\(g.name)")
                                    .transition(.opacity)
                            }
                        }
                        .padding(.horizontal, 18).padding(.top, 4)
                        if done >= total && groups.isEmpty {
                            empty("没有找到书籍", "换个关键词，或在书源配置中启用其他来源。")
                        }
                    } else {
                        empty("搜索你的下一本书", "输入书名或作者，同时搜索已启用的书源，相同书籍会自动合并。")
                    }
                }
                .padding(.bottom, 40)
            }
            .scrollDismissesKeyboard(.immediately)
            .liquidTopBar { topBar }
            .dsAmbient(cool: true)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Group.self) { g in NovelWebDetailView(items: g.items) }
            .fullScreenCover(isPresented: $manage) { NovelSourcesView() }
            .onAppear {
                #if DEBUG
                if let q = ProcessInfo.processInfo.environment["STV_NOVEL_QUERY"], !q.isEmpty, submitted.isEmpty {
                    query = q; focused = false; run(); return
                }
                #endif
                if query.isEmpty { focused = true }
            }
        }
    }

    private var topBar: some View {
        LiquidGlassGroup(spacing: 8) { HStack(spacing: 8) {
            DSBackButton { task?.cancel(); dismiss() }
            HStack(spacing: 7) {
                Image(systemName: "magnifyingglass").font(.system(size: 15, weight: .medium)).foregroundStyle(STVTheme.secondary)
                TextField("", text: $query, prompt: Text("书名或作者").foregroundColor(STVTheme.tertiary))
                    .focused($focused).submitLabel(.search).onSubmit(run)
                    .font(.system(size: 15, weight: .medium)).foregroundStyle(STVTheme.text).tint(STVTheme.accent)
                    .autocorrectionDisabled().textInputAutocapitalization(.never)
                    .accessibilityIdentifier("search.field")
                if !query.isEmpty {
                    Button { query = ""; focused = true } label: {
                        Image(systemName: "xmark").font(.system(size: 8.5, weight: .bold)).foregroundStyle(STVTheme.bg)
                            .frame(width: 18, height: 18).background(Circle().fill(STVTheme.tertiary))
                    }
                    .buttonStyle(PressScale()).accessibilityLabel("清除").transition(.scale.combined(with: .opacity))
                    Button("搜索", action: run).font(.system(size: 13.5, weight: .semibold)).foregroundStyle(STVTheme.accent).buttonStyle(PressScale())
                }
            }
            .padding(.leading, 13).padding(.trailing, 12).frame(height: 44)
            .liquidGlassCapsule(interactive: true)
            .animation(DS.press, value: query.isEmpty)

            Button { manage = true } label: {
                HStack(spacing: 4) {
                    Text("书源").font(.system(size: 13, weight: .semibold)).foregroundStyle(STVTheme.text)
                    if !sources.enabled.isEmpty {
                        Text("\(sources.enabled.count)").font(.system(size: 11.5, weight: .semibold)).foregroundStyle(STVTheme.accent)
                    }
                }
                .padding(.horizontal, 12).frame(height: 44)
                .contentShape(Capsule())
                .liquidGlassCapsule(interactive: true)
            }
            .buttonStyle(GlassPress()).accessibilityIdentifier("search.sources")
        } }
        .padding(.horizontal, 16).padding(.top, 2).padding(.bottom, 8)
    }

    private var progress: some View {
        VStack(spacing: 7) {
            HStack(spacing: 6) {
                if done < total {
                    Circle().fill(STVTheme.accent).frame(width: 6, height: 6)
                    (Text("正在搜索 ") + Text("\(done)").foregroundColor(STVTheme.text).bold() + Text(" / \(total) 个书源 · \(groups.count) 本"))
                } else {
                    Text("共 \(groups.count) 本 · \(total) 个书源")
                }
                Spacer(minLength: 8)
                if failures > 0 { Text("\(failures) 个失败").font(.system(size: 11.5, weight: .medium)).foregroundStyle(STVTheme.accentWarm) }
            }
            .font(.system(size: 12)).foregroundStyle(STVTheme.secondary)
            .accessibilityElement(children: .combine).accessibilityIdentifier("search.progress")
            if done < total {
                DSProgressBar(value: total > 0 ? Double(done) / Double(total) : 0)
            }
        }
    }

    private func empty(_ title: String, _ text: String, action: (String, () -> Void)? = nil) -> some View {
        DSEmptyState(title, systemImage: "magnifyingglass", message: text, actionTitle: action?.0, action: action?.1).padding(.top, 50)
    }

    private func highlighted(_ name: String) -> AttributedString {
        var a = AttributedString(name)
        let q = submitted
        if !q.isEmpty, let r = a.range(of: q) { a[r].foregroundColor = STVTheme.accent }
        return a
    }

    private func resultRow(_ g: Group, lead: Bool) -> some View {
        let first = g.items[0]
        let meta = [g.author.isEmpty ? nil : g.author, first.kind.isEmpty ? nil : first.kind].compactMap { $0 }.joined(separator: " · ")
        let intro = g.items.first { !$0.intro.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }?.intro
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\n", with: " ") ?? ""
        let exact = !submitted.isEmpty && g.name == submitted
        return HStack(alignment: lead ? .top : .center, spacing: 13) {
            WebCover(url: g.items.first { !$0.cover.isEmpty }?.cover, title: g.name, author: g.author, corner: lead ? 8 : 7)
                .frame(width: lead ? 64 : 58, height: lead ? 90 : 82)
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    Text(highlighted(g.name))
                        .font(.system(size: lead ? 17 : 16, weight: .bold))
                        .foregroundStyle(STVTheme.text)
                        .lineLimit(1)
                    if exact { DSStatus(text: "最佳匹配", tone: .accent) }
                    Spacer(minLength: 4)
                    DSStatus(text: "\(g.items.count) 个书源", tone: g.items.count > 1 ? .accent : .muted)
                }
                if !meta.isEmpty {
                    Text(meta).font(.system(size: 12)).foregroundStyle(STVTheme.secondary).lineLimit(1).padding(.top, 3)
                }
                if !intro.isEmpty {
                    Text(intro)
                        .font(.system(size: 12))
                        .foregroundStyle(lead ? STVTheme.secondary : STVTheme.tertiary)
                        .lineSpacing(2)
                        .lineLimit(lead ? 2 : 1)
                        .padding(.top, 4)
                }
                HStack(spacing: 8) {
                    Text(first.latest.isEmpty ? "" : "最新：" + first.latest)
                        .font(.system(size: 11.5)).foregroundStyle(STVTheme.tertiary).lineLimit(1)
                    Spacer(minLength: 4)
                    Text((g.items.count > 1 ? "首选 " : "") + first.sourceName)
                        .font(.system(size: 11)).foregroundStyle(STVTheme.secondary).lineLimit(1)
                }
                .padding(.top, 6)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, lead ? 15 : 13)
        .contentShape(Rectangle())
    }

    private func run() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        focused = false
        submitted = q
        task?.cancel()
        groups = []; done = 0; failures = 0; pendingItems = []
        let list = sources.enabled
        total = list.count
        task = Task {
            // Sources answer in bursts: merge at most a few times per second instead of re-sorting and re-diffing the whole
            // list for every single answer.
            let flusher = Task { while !Task.isCancelled { try? await Task.sleep(nanoseconds: 180_000_000); flush(q) } }
            defer { flusher.cancel(); flush(q) }
            await withTaskGroup(of: (Bool, [LegadoSearchItem]).self) { tg in
                var it = list.makeIterator()
                func add() -> Bool {
                    guard let s = it.next() else { return false }
                    tg.addTask {
                        do { return (true, try await LegadoClient.run { try LegadoClient.search(s, key: q) }) } catch { return (false, []) }
                    }
                    return true
                }
                for _ in 0..<8 { if !add() { break } }
                for await (ok, items) in tg {
                    if Task.isCancelled { tg.cancelAll(); return }
                    done += 1
                    if !ok { failures += 1 }
                    pendingItems += items
                    _ = add()
                }
            }
        }
    }

    private func flush(_ q: String) {
        guard !pendingItems.isEmpty, submitted == q else { return }
        let items = pendingItems
        pendingItems = []
        withAnimation(DS.panel) { merge(items, query: q) }
    }

    private func merge(_ items: [LegadoSearchItem], query q: String) {
        var groups = self.groups
        for it in items {
            let author = it.author.trimmingCharacters(in: .whitespaces)
            if let i = groups.firstIndex(where: { $0.name == it.name && ($0.author == author || $0.author.isEmpty || author.isEmpty) }) {
                if !groups[i].items.contains(where: { $0.sourceURL == it.sourceURL }) { groups[i].items.append(it) }
            } else {
                groups.append(Group(name: it.name, author: author, items: [it]))
            }
        }
        groups.sort { a, b in
            func rank(_ g: Group) -> Int { g.name == q ? 0 : g.name.contains(q) || g.author == q ? 1 : 2 }
            return (rank(a), -a.items.count) < (rank(b), -b.items.count)
        }
        self.groups = groups
    }
}

/// Cover from a URL with a clothbound fallback, physical book spine crease, and optional top-left rank numeral.
struct WebCover: View {
    var url: String?
    var title: String
    var author: String? = nil
    var corner: CGFloat = 8
    var rank: Int? = nil

    var body: some View {
        Color.clear
            .overlay { BookArt(url: url, title: title, author: author, maxPixelSize: rank == nil ? CoverPixels.thumb : CoverPixels.poster) }
            .overlay { BookSpineOverlay(corner: corner) }
            .overlay(alignment: .topLeading) { if let rank { PosterRankNumeral(rank: rank) } }
            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
            .modifier(BookShadow(corner: corner))
    }
}

// MARK: - 详情

/// Aligned with DramaDetailView: blurred cover hero, single 3-column metrics strip, ink CTA + round buttons, open hairline sections.
struct NovelWebDetailView: View {
    let items: [LegadoSearchItem]
    var onRead: ((NovelBook) -> Void)? = nil
    @State private var reading: NovelBook?
    var resumeFrom: NovelBook? = nil
    @State private var pick = 0
    @State private var info: LegadoBookInfo?
    @State private var toc: [LegadoChapter]?
    @State private var error: String?
    @State private var loading = false
    @State private var alternatives: [LegadoSearchItem] = []
    @State private var findingSources = false
    @State private var downloadBook: NovelBook?
    @State private var showingMedia = false
    @State private var mediaChapter = 0
    @State private var sourceTask: Task<Void, Never>?
    @State private var loadGeneration = UUID()
    @State private var pinned = false
    @State private var actionsOut = false
    @State private var barCompact = false
    @State private var scrollTrack = ScrollTrack()
    @State private var choosingSource = false
    @State private var introOpen = false
    @State private var cachedChapters = 0
    private final class ScrollTrack { var turn: CGFloat = 0 }

    private var allItems: [LegadoSearchItem] { items + alternatives.filter { candidate in !items.contains { $0.id == candidate.id } } }
    @ObservedObject private var store = NovelStore.shared

    private var item: LegadoSearchItem { allItems[min(pick, allItems.count - 1)] }
    private var source: LegadoSource? { LegadoSourceStore.shared.source(item.sourceURL) }
    private var onShelf: NovelBook? { store.webBook(source: item.sourceURL, url: item.bookURL) }

    @Environment(\.dismiss) private var dismiss
    private var title: String { info?.name.novelNonEmpty ?? item.name }
    private var author: String { info?.author.novelNonEmpty ?? item.author }
    private var intro: String { (info?.intro.novelNonEmpty ?? item.intro).trimmingCharacters(in: .whitespacesAndNewlines) }
    private var kind: String { info?.kind.novelNonEmpty ?? item.kind }
    private var coverURL: String? { info?.cover.novelNonEmpty ?? item.cover.novelNonEmpty }
    private var latest: String { info?.latest.novelNonEmpty ?? item.latest }
    private var chapterCount: Int { toc?.count ?? onShelf?.chapters.count ?? 0 }
    private var cacheRefreshKey: String { "\(onShelf?.id ?? "none")|\(onShelf?.chapters.count ?? 0)|\(downloadBook == nil)" }
    private var canOpen: Bool { chapterCount > 0 }
    private var isMedia: Bool { source?.isMedia == true }
    private var readLabel: String {
        switch source?.bookSourceType {
        case 1: "开始收听"
        case 4: "开始观看"
        default: "开始阅读"
        }
    }
    private var tags: [String] {
        var result: [String] = []
        for raw in kind.components(separatedBy: CharacterSet(charactersIn: "·/,，|")) {
            let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty && !result.contains(t) { result.append(t) }
        }
        return Array(result.prefix(5))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                hero
                metricsStrip.padding(.top, 4)
                readButton(height: 52)
                    .accessibilityIdentifier("detail.read")
                    .padding(.horizontal, 20)
                    .padding(.top, 16)
                    .compatOnFrame(in: .named("novelDetail")) { f in
                        let out = f.maxY < 100
                        if actionsOut != out { actionsOut = out }
                    }

                if !tags.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 7) {
                            ForEach(tags, id: \.self) { t in
                                Text(t).dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary)
                                    .padding(.horizontal, 10).frame(minHeight: 26)
                                    .background(Capsule().fill(STVTheme.glass))
                            }
                        }
                        .padding(.horizontal, 20)
                    }
                    .compatScrollBounceBasedOnSize(axes: .horizontal)
                    .padding(.top, 16)
                }

                if !intro.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(intro)
                            .dsFont(14, relativeTo: .subheadline)
                            .lineSpacing(5)
                            .foregroundStyle(STVTheme.secondary)
                            .lineLimit(introOpen ? nil : 4)
                            .textSelection(.enabled)
                        if intro.count > 72 {
                            Button { withAnimation(DS.panel) { introOpen.toggle() } } label: {
                                Text(introOpen ? "收起" : "展开").font(.system(size: 13, weight: .semibold)).foregroundStyle(STVTheme.accent)
                                    .contentShape(Rectangle().inset(by: -8))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 14)
                }

                // Open hairline Directory & Source rows (0 cards)
                VStack(spacing: 0) {
                    Hairline()
                    Button { read(open: true, chapter: chapterCount > 0 ? chapterCount - 1 : nil) } label: {
                        HStack(spacing: 10) {
                            Text("目录").dsFont(15, .semibold, relativeTo: .subheadline).foregroundStyle(STVTheme.text)
                            Text(!latest.isEmpty ? "最新：" + latest : (toc?.last?.title ?? onShelf?.chapters.last?.title ?? "暂无章节信息"))
                                .dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.secondary).lineLimit(1)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if loading && chapterCount == 0 { DSSpinner(size: 15) }
                            if chapterCount > 0 {
                                Text("共 \(chapterCount) 章").dsFont(13, .medium, relativeTo: .footnote).foregroundStyle(STVTheme.secondary)
                            }
                            Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(STVTheme.tertiary)
                        }
                        .frame(minHeight: 50)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressScale(scale: 0.99))
                    .disabled(!canOpen)

                    Hairline()

                    Button { choosingSource = true } label: {
                        HStack(spacing: 10) {
                            Text("书源").dsFont(15, .semibold, relativeTo: .subheadline).foregroundStyle(STVTheme.text)
                            HStack(spacing: 6) {
                                Circle().fill(STVTheme.ok).frame(width: 6, height: 6)
                                Text(item.sourceName).dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.secondary).lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            Text(allItems.count > 1 ? "换源 (\(allItems.count))" : "换源")
                                .dsFont(13, .semibold, relativeTo: .footnote).foregroundStyle(STVTheme.accent)
                            Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(STVTheme.accent)
                        }
                        .frame(minHeight: 50)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressScale(scale: 0.99))
                    .accessibilityIdentifier("detail.source")

                    Hairline()
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)

                // Open hairline Preview Chapters (0 cards)
                if !isMedia, chapterCount > 0 {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(alignment: .firstTextBaseline) {
                            Text("试读章节").dsFont(18, .bold, relativeTo: .title3).foregroundStyle(STVTheme.text)
                            Spacer()
                            Text("点击直接阅读").dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.tertiary)
                        }
                        .padding(.top, 22).padding(.bottom, 6)

                        ForEach(0..<min(3, chapterCount), id: \.self) { index in
                            if index > 0 { Hairline() }
                            Button { read(open: true, chapter: index) } label: {
                                HStack(spacing: 10) {
                                    Text(String(format: "%02d", index + 1))
                                        .font(.system(size: 12, weight: .semibold).monospacedDigit()).foregroundStyle(STVTheme.tertiary)
                                        .frame(width: 20, alignment: .leading)
                                    Text(chapterTitle(index))
                                        .dsFont(15, relativeTo: .subheadline).foregroundStyle(STVTheme.text).lineLimit(1)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    if index == 0 { DSStatus(text: "试读", tone: .accent) }
                                    Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(STVTheme.tertiary)
                                }
                                .frame(minHeight: 48)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(PressScale(scale: 0.99))
                        }
                    }
                    .padding(.horizontal, 20)
                }

                if let error {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(error).dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.accentWarm)
                        Button { Task { await load() } } label: { MiniButtonLabel(title: "重试") }.buttonStyle(PressScale())
                    }
                    .padding(.horizontal, 20).padding(.top, 14)
                }
            }
            .padding(.bottom, 108)
            .compatScrollContent()
        }
        .coordinateSpace(name: "novelDetail")
        .compatOnScrolled(threshold: 210) { value in
            if pinned != value { pinned = value }
        }
        .compatOnScrollY { y in
            if y > scrollTrack.turn + 36 { scrollTrack.turn = y; if !barCompact { barCompact = true } }
            else if y < scrollTrack.turn - 36 { scrollTrack.turn = y; if barCompact { barCompact = false } }
        }
        .scrollIndicators(.hidden)
        .ignoresSafeArea(edges: .top)
        .background(STVTheme.bg.ignoresSafeArea())
        .overlay(alignment: .top) { navBar }
        .overlay(alignment: .bottom) { floatingBar }
        .foregroundStyle(STVTheme.text)
        .toolbar(.hidden, for: .navigationBar)
        .task(id: pick) { await load() }
        .task(id: cacheRefreshKey) {
            guard let book = onShelf else { cachedChapters = 0; return }
            let count = await Task.detached(priority: .utility) {
                NovelStore.fastCachedCount(book)
            }.value
            if !Task.isCancelled { cachedChapters = count }
        }
        .onDisappear { sourceTask?.cancel() }
        .fullScreenCover(item: $reading) { NovelReaderView(book: $0) }
        .sheet(item: $downloadBook) { book in NovelDownloadView(book: book) }
        .sheet(isPresented: $choosingSource) { sourceSheet }
        .fullScreenCover(isPresented: $showingMedia) {
            if let source {
                NovelMediaPlayer(
                    source: source,
                    chapters: toc ?? [],
                    startIndex: mediaChapter,
                    book: [
                        "cover": info?.cover.novelNonEmpty ?? item.cover,
                        "name": info?.name.isEmpty == false ? info!.name : item.name,
                        "author": info?.author.isEmpty == false ? info!.author : item.author,
                        "bookUrl": item.bookURL,
                    ]
                )
            }
        }
    }

    private func chapterTitle(_ i: Int) -> String {
        if let toc, toc.indices.contains(i) { return toc[i].title }
        if let c = onShelf?.chapters, c.indices.contains(i) { return c[i].title }
        return ""
    }

    /// 换源: every known source of this book, plus the search for more.
    private var sourceSheet: some View {
        DSSheet("换源", subtitle: "共 \(allItems.count) 个可用书源") {
            DSOptionList(options: Array(allItems.indices), selected: pick, label: { allItems.indices.contains($0) ? allItems[$0].sourceName : "" },
                         note: { allItems.indices.contains($0) ? (allItems[$0].latest.isEmpty ? nil : "最新：" + allItems[$0].latest) : nil }) { i in
                pick = i
                choosingSource = false
            }
        } footer: {
            DSSheetFooter {
                if findingSources {
                    HStack(spacing: 10) {
                        DSSpinner(size: 16)
                        Text("正在查找其他书源…").dsFont(14, relativeTo: .subheadline).foregroundStyle(STVTheme.secondary)
                        Spacer(minLength: 0)
                        Button { sourceTask?.cancel() } label: { MiniButtonLabel(title: "停止") }.buttonStyle(PressScale())
                    }
                    .frame(minHeight: 48)
                } else {
                    Button { sourceTask?.cancel(); sourceTask = Task { await findSources() } } label: { PillButtonPlain(title: "搜索其他书源", icon: "magnifyingglass") }
                        .buttonStyle(PressScale(scale: 0.98))
                }
            }
        }
        .dsSheetChrome()
    }

    private var navBar: some View {
        LiquidGlassGroup(spacing: 12) {
            ZStack {
                Text(title).dsFont(17, .semibold, relativeTo: .headline).foregroundStyle(STVTheme.text).lineLimit(1)
                    .padding(.horizontal, 112)
                    .opacity(pinned ? 1 : 0).offset(y: pinned ? 0 : 6)
                    .accessibilityHidden(!pinned)
                HStack(spacing: 0) {
                    DSBackButton(tone: pinned ? .glass : .overlay) { dismiss() }
                    Spacer(minLength: 0)
                    HStack(spacing: 0) {
                        barIcon(onShelf == nil ? "bookmark" : "bookmark.fill", active: onShelf != nil, enabled: canOpen && onShelf == nil && !isMedia) { read(open: false) }
                            .accessibilityLabel(onShelf == nil ? "加入书架" : "已在书架")
                            .accessibilityIdentifier("detail.add")
                        barIcon("arrow.down.to.line", active: false, enabled: canOpen && !isMedia) { downloadBook = onShelf ?? store.addWeb(item, info: info, toc: toc ?? []) }
                            .accessibilityLabel("下载")
                            .accessibilityIdentifier("detail.download")
                    }
                    .liquidGlassCapsule(tone: pinned ? .regular : .clear, interactive: true, onMedia: !pinned)
                }
            }
        }
        .frame(height: 44)
        .padding(.horizontal, 16).padding(.top, 2).padding(.bottom, 8)
        .background { LiquidEdgeFade(visible: pinned) }
        .animation(.easeOut(duration: 0.2), value: pinned)
    }

    private func barIcon(_ icon: String, active: Bool, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 17, weight: .medium))
                .foregroundStyle(active ? (pinned ? STVTheme.accent : Color(red: 0.62, green: 0.78, blue: 1)) : (pinned ? STVTheme.text : .white))
                .compatSymbolReplace()
                .frame(width: 46, height: 44).contentShape(Rectangle())
        }
        .buttonStyle(GlassPress())
        .disabled(!enabled)
        .opacity(enabled || active ? 1 : 0.5)
    }

    private func readButton(height: CGFloat, expand: Bool = true) -> some View {
        Button { read(open: true) } label: {
            PillButtonLabel(title: readLabel, icon: isMedia ? "play.fill" : "book.fill", tone: .accent, height: height, expand: expand, glass: true)
        }
        .buttonStyle(GlassPress())
        .disabled(!canOpen)
        .opacity(canOpen ? 1 : 0.45)
    }

    /// Sticky glass actions, shrinking while the page scrolls down (same behaviour as the drama detail).
    private var floatingBar: some View {
        ZStack(alignment: .bottom) {
            if canOpen, actionsOut {
                LiquidGlassGroup(spacing: 14) {
                    HStack(spacing: 10) {
                        readButton(height: 54, expand: !barCompact)
                        if !isMedia {
                            Button { downloadBook = onShelf ?? store.addWeb(item, info: info, toc: toc ?? []) } label: {
                                Image(systemName: "arrow.down.to.line").font(.system(size: 19, weight: .medium)).foregroundStyle(STVTheme.text)
                                    .frame(width: 54, height: 54).liquidGlass(Circle(), interactive: true).contentShape(Circle())
                            }
                            .buttonStyle(GlassPress()).accessibilityLabel("下载")
                        }
                        if barCompact { Spacer(minLength: 0) }
                    }
                }
                .padding(.horizontal, 20).padding(.bottom, 2)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(DS.panel, value: barCompact)
        .animation(DS.panel, value: actionsOut)
    }

    private var hero: some View {
        let cloth = GeneratedBookCover.clothColors(title)
        return ZStack(alignment: .bottomLeading) {
            ZStack {
                LinearGradient(colors: [cloth.0, cloth.1], startPoint: .top, endPoint: .bottom)
                if let u = coverURL, !u.isEmpty { CoverBackdrop(url: u) }
            }
            .frame(maxWidth: .infinity).frame(height: 304)
            .liquidBackgroundExtension()
            .overlay(
                LinearGradient(
                    stops: [
                        .init(color: .black.opacity(0.34), location: 0),
                        .init(color: .black.opacity(0.08), location: 0.36),
                        .init(color: STVTheme.bg.opacity(0.80), location: 0.78),
                        .init(color: STVTheme.bg, location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .allowsHitTesting(false)

            HStack(alignment: .bottom, spacing: 16) {
                WebCover(url: coverURL, title: title, author: author, corner: 11)
                    .frame(width: 108, height: 152)
                VStack(alignment: .leading, spacing: 0) {
                    Button { choosingSource = true } label: {
                        DSHeroTag(text: allItems.count > 1 ? "\(item.sourceName) · \(allItems.count) 源" : item.sourceName, trailingIcon: "chevron.down")
                        .contentShape(Rectangle().inset(by: -10))
                    }
                    .buttonStyle(PressScale())
                    Text(title)
                        .dsFont(23, .heavy, relativeTo: .title2)
                        .foregroundStyle(STVTheme.text)
                        .lineLimit(2)
                        .padding(.top, 8)
                    if !author.isEmpty || !tags.isEmpty {
                        Text([author, tags.prefix(2).joined(separator: " · ")].filter { !$0.isEmpty }.joined(separator: " · "))
                            .dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.secondary).lineLimit(1)
                            .padding(.top, 6)
                    }
                }
                .padding(.bottom, 2)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 20).padding(.bottom, 12)
        }
        .frame(height: 304)
    }

    private var metricsStrip: some View {
        HStack(spacing: 0) {
            metricCell(value: chapterCount > 0 ? "\(chapterCount)" : (loading ? "…" : "—"), unit: chapterCount > 0 ? "章" : "", label: "目录总章")
            STVTheme.hairline.frame(width: 1, height: 26)
            metricCell(value: "\(max(allItems.count, 1))", unit: "个", label: "可用书源")
            STVTheme.hairline.frame(width: 1, height: 26)
            metricCell(
                value: source?.bookSourceType == 1 ? "有声" : source?.bookSourceType == 4 ? "视频" : "\(cachedChapters)",
                unit: isMedia ? "" : "章",
                label: isMedia ? "源类型" : "离线已缓"
            )
        }
        .padding(.vertical, 13)
        .dsCard(18)
        .padding(.horizontal, 20)
    }

    private func metricCell(value: String, unit: String, label: String) -> some View {
        VStack(spacing: 2) {
            HStack(alignment: .lastTextBaseline, spacing: 2) {
                Text(value).dsFont(17, .bold, relativeTo: .headline).foregroundStyle(STVTheme.text).lineLimit(1)
                if !unit.isEmpty { Text(unit).dsFont(11, .medium, relativeTo: .caption2).foregroundStyle(STVTheme.secondary) }
            }
            Text(label).dsFont(11, relativeTo: .caption2).foregroundStyle(STVTheme.tertiary)
        }
        .frame(maxWidth: .infinity)
    }

    private func findSources() async {
        guard !findingSources else { return }
        findingSources = true
        defer { findingSources = false }
        let target = item
        let list = LegadoSourceStore.shared.enabled.filter { $0.id != target.sourceURL && !($0.searchUrl ?? "").isEmpty }
        await withTaskGroup(of: [LegadoSearchItem].self) { group in
            var iterator = list.makeIterator()
            func enqueue() {
                guard let source = iterator.next() else { return }
                group.addTask { (try? await LegadoClient.run { try LegadoClient.search(source, key: target.name) }) ?? [] }
            }
            for _ in 0..<4 { enqueue() }
            for await results in group {
                if Task.isCancelled { group.cancelAll(); return }
                let matched = results.filter { $0.name == target.name && (target.author.isEmpty || $0.author.isEmpty || $0.author == target.author) }
                for match in matched where !allItems.contains(where: { $0.id == match.id }) { alternatives.append(match) }
                enqueue()
            }
        }
        if !Task.isCancelled, alternatives.isEmpty { error = "暂未找到可用的同名书籍来源，可在书源管理中启用更多源后重试" }
    }

    private func load() async {
        guard let source else { error = "书源已删除"; return }
        loading = true; error = nil; info = nil; toc = nil
        let it = item
        let token = UUID(); loadGeneration = token
        do {
            let i = try await LegadoClient.run { try LegadoClient.info(source, bookURL: it.bookURL) }
            guard token == loadGeneration, !Task.isCancelled else { return }
            info = i
            let book: [String: String] = ["name": i.name, "author": i.author, "bookUrl": it.bookURL]
            let t = try await LegadoClient.run { try LegadoClient.toc(source, tocURL: i.tocURL, book: book) }
            guard token == loadGeneration, !Task.isCancelled else { return }
            if t.isEmpty { error = "这本书没有可读目录，可能已下架、受限或书源失效。可换一本书或切换书源后重试。" } else { toc = t }
        } catch {
            if token == loadGeneration, !Task.isCancelled { self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription }
        }
        if token == loadGeneration { loading = false }
    }

    private func read(open: Bool, chapter: Int? = nil) {
        if source?.isMedia == true { if open { mediaChapter = chapter ?? 0; showingMedia = true }; return }
        if let saved = onShelf, let toc, !toc.isEmpty, saved.chapterURLs != toc.map(\.url) { store.updateToc(saved, toc) }
        let b = onShelf ?? store.addWeb(item, info: info, toc: toc ?? [])
        if open {
            if let old = resumeFrom, old.id != b.id { store.transferProgress(from: old, to: b) }
            if let chapter, b.chapters.indices.contains(chapter) { store.record(b, chapter: chapter, offset: 0) }
            if let onRead { onRead(b) } else { reading = b }
        }
    }
}

private extension String { var novelNonEmpty: String? { isEmpty ? nil : self } }
