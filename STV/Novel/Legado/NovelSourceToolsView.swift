import SwiftUI
import UIKit

// MARK: - Single-Source Debugger

struct NovelSourceDebugView: View {
    let source: LegadoSource
    @State private var query: String
    @State private var events: [LegadoTools.DebugEvent] = []
    @State private var running = false
    @State private var statusText: String?
    @State private var succeeded: Bool?

    init(source: LegadoSource) {
        self.source = source
        let def = source.ruleSearch?["checkKeyWord"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        _query = State(initialValue: def.isEmpty ? "我的" : def)
    }

    var body: some View {
        DSSheet("调试 · \(source.bookSourceName)", subtitle: "单源全链路调试") {
            VStack(spacing: 12) {
                HStack(spacing: 10) {
                    DSTextField("关键词或详情页 URL", text: $query, icon: "magnifyingglass")
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .submitLabel(.go).onSubmit { startDebug() }
                    Button(action: startDebug) {
                        ZStack {
                            PillButtonLabel(title: running ? "" : "运行", tone: .accent, expand: false)
                            if running { DSSpinner(size: 16, tint: STVTheme.onAccent) }
                        }
                    }
                    .buttonStyle(PressScale()).disabled(running)
                }
                .padding(.horizontal, 16)

                if let statusText {
                    HStack {
                        DSStatus(text: statusText, tone: succeeded == true ? .ok : .warn)
                        Spacer()
                        if !events.isEmpty {
                            Button {
                                UIPasteboard.general.string = events.map { "[\($0.timeMs)ms][\($0.stage)] \($0.message)" }.joined(separator: "\n")
                            } label: { MiniButtonLabel(title: "复制日志") }
                            .buttonStyle(PressScale())
                        }
                    }
                    .padding(.horizontal, 16)
                }

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        if events.isEmpty && !running {
                            Text("点击「运行」依次执行搜索/发现 → 详情 → 目录 → 正文/媒体，并实时输出每一步网络与规则结果。")
                                .font(.system(size: 13)).foregroundStyle(STVTheme.secondary)
                                .padding(16)
                        }
                        ForEach(events) { ev in
                            VStack(alignment: .leading, spacing: 3) {
                                HStack(spacing: 6) {
                                    Text("[\(ev.timeMs)ms]").font(.system(size: 11, design: .monospaced)).foregroundStyle(STVTheme.tertiary)
                                    Text(ev.stage.uppercased()).font(.system(size: 11, weight: .semibold, design: .monospaced))
                                        .foregroundStyle(ev.isError ? DSTone.danger : STVTheme.accent)
                                }
                                Text(ev.message)
                                    .font(.system(size: 12, design: .monospaced))
                                    .foregroundStyle(ev.isError ? DSTone.danger : STVTheme.text)
                                    .textSelection(.enabled)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(10)
                            .background(STVTheme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    }
                    .padding(.horizontal, 16).padding(.bottom, 24)
                }
            }
        }
        .dsSheetChrome([.large])
    }

    private func startDebug() {
        guard !running else { return }
        DS.endEditing()
        running = true
        events = []
        statusText = "调试中…"
        succeeded = nil
        let src = source
        let q = query
        Task {
            let report = (try? await LegadoClient.run {
                LegadoTools.debug(src, query: q) { ev in
                    Task { @MainActor in events.append(ev) }
                }
            }) ?? LegadoTools.DebugReport(events: [], succeeded: false, elapsedMs: 0)
            running = false
            succeeded = report.succeeded
            statusText = report.succeeded ? "调试通过 · \(report.elapsedMs)ms" : "调试失败 · \(report.elapsedMs)ms"
        }
    }
}

// MARK: - Concurrent Batch Source Validator

struct NovelSourceCheckView: View {
    let sources: [LegadoSource]
    var onDisableFailed: (Set<String>) -> Void
    var onDeleteFailed: (Set<String>) -> Void

    @State private var keyword = "我的"
    @State private var deepCheck = true
    @State private var running = false
    @State private var results: [LegadoTools.CheckResult] = []
    @State private var tab = 0 // 0 all, 1 ok, 2 failed
    @State private var checkTask: Task<Void, Never>?

    private var failedIDs: Set<String> { Set(results.filter { $0.status != .ok }.map(\.sourceId)) }
    private var okCount: Int { results.filter { $0.status == .ok }.count }
    private var failCount: Int { results.count - okCount }
    private var filtered: [LegadoTools.CheckResult] {
        switch tab {
        case 1: results.filter { $0.status == .ok }
        case 2: results.filter { $0.status != .ok }
        default: results
        }
    }

    var body: some View {
        DSSheet("批量校验书源", subtitle: "\(sources.count) 个书源") {
            VStack(spacing: 12) {
                HStack(spacing: 10) {
                    DSTextField("测试关键词", text: $keyword, icon: "magnifyingglass")
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button {
                        if running { checkTask?.cancel(); running = false }
                        else { startBatch() }
                    } label: {
                        PillButtonLabel(title: running ? "停止" : "开始", tone: .accent, expand: false)
                    }
                    .buttonStyle(PressScale())
                }
                .padding(.horizontal, 16)

                HStack {
                    Toggle("深度校验目录与正文", isOn: $deepCheck)
                        .font(.system(size: 13)).foregroundStyle(STVTheme.secondary)
                        .disabled(running)
                }
                .padding(.horizontal, 18)

                if running || !results.isEmpty {
                    ProgressView(value: Double(results.count), total: Double(max(1, sources.count)))
                        .tint(STVTheme.accent)
                        .padding(.horizontal, 16)
                    HStack(spacing: 8) {
                        filterPill("全部 \(results.count)", 0)
                        filterPill("正常 \(okCount)", 1)
                        filterPill("异常 \(failCount)", 2)
                        Spacer()
                    }
                    .padding(.horizontal, 16)
                }

                if !running && !failedIDs.isEmpty {
                    HStack(spacing: 10) {
                        Button { onDisableFailed(failedIDs) } label: { MiniButtonLabel(title: "禁用异常(\(failedIDs.count))") }
                            .buttonStyle(PressScale())
                        Button { onDeleteFailed(failedIDs) } label: { MiniButtonLabel(title: "删除异常", tint: DSTone.danger, ground: DSTone.dangerSoft) }
                            .buttonStyle(PressScale())
                        Spacer()
                    }
                    .padding(.horizontal, 16)
                }

                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(filtered) { r in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack(spacing: 8) {
                                    Text(r.sourceName).font(.system(size: 14, weight: .medium)).foregroundStyle(STVTheme.text).lineLimit(1)
                                    Spacer()
                                    Text("\(r.latencyMs)ms").font(.system(size: 11, design: .monospaced)).foregroundStyle(STVTheme.tertiary)
                                    DSStatus(text: r.status.rawValue, tone: r.status == .ok ? .ok : .warn)
                                }
                                Text(r.detail).font(.system(size: 12)).foregroundStyle(STVTheme.secondary).lineLimit(2)
                            }
                            .padding(12)
                            .background(STVTheme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                    }
                    .padding(.horizontal, 16).padding(.bottom, 24)
                }
            }
        }
        .dsSheetChrome([.large])
        .onDisappear { checkTask?.cancel() }
    }

    private func filterPill(_ title: String, _ idx: Int) -> some View {
        Button { DS.tick(); tab = idx } label: {
            Text(title).font(.system(size: 12, weight: .medium))
                .padding(.horizontal, 10).padding(.vertical, 5)
                .background(tab == idx ? STVTheme.accentSoft : STVTheme.glass, in: Capsule())
                .foregroundStyle(tab == idx ? STVTheme.accent : STVTheme.secondary)
        }
        .buttonStyle(PressScale())
    }

    private func startBatch() {
        DS.endEditing()
        results = []
        running = true
        let list = sources.filter(\.supported)
        let kw = keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "我的" : keyword
        let deep = deepCheck
        checkTask = Task {
            await withTaskGroup(of: LegadoTools.CheckResult?.self) { group in
                var nextIdx = 0
                let concurrency = 6
                for _ in 0..<min(concurrency, list.count) {
                    let s = list[nextIdx]; nextIdx += 1
                    group.addTask {
                        try? await LegadoClient.run { LegadoTools.checkOne(s, keyword: kw, checkContent: deep) }
                    }
                }
                for await res in group {
                    if Task.isCancelled { break }
                    if let res { results.append(res) }
                    if nextIdx < list.count && !Task.isCancelled {
                        let s = list[nextIdx]; nextIdx += 1
                        group.addTask {
                            try? await LegadoClient.run { LegadoTools.checkOne(s, keyword: kw, checkContent: deep) }
                        }
                    }
                }
            }
            running = false
        }
    }
}

// MARK: - Replace / Purify Rules Manager

struct NovelReplaceRulesView: View {
    @ObservedObject private var store = LegadoReplaceStore.shared
    @State private var editing: LegadoReplaceRule?
    @State private var banner: String?

    var body: some View {
        DSSheet("替换净化规则", subtitle: "兼容 Legado ReplaceRule JSON") {
            VStack(spacing: 10) {
                HStack(spacing: 8) {
                    Button {
                        editing = LegadoReplaceRule(id: Int64(Date().timeIntervalSince1970 * 1000))
                    } label: { MiniButtonLabel(title: "新建规则") }
                        .buttonStyle(PressScale())
                    Button {
                        let raw = UIPasteboard.general.string ?? ""
                        do {
                            let n = try store.importJSON(raw)
                            banner = n > 0 ? "已导入 \(n) 条净化规则" : "剪贴板中没有有效规则"
                        } catch {
                            banner = "导入失败：\(error.localizedDescription)"
                        }
                    } label: { MiniButtonLabel(title: "导入剪贴板") }
                        .buttonStyle(PressScale())
                    Button {
                        UIPasteboard.general.string = store.exportJSON()
                        banner = "已复制 \(store.rules.count) 条规则 JSON"
                    } label: { MiniButtonLabel(title: "复制导出") }
                        .buttonStyle(PressScale())
                    Spacer()
                }
                .padding(.horizontal, 16)

                if let banner {
                    Text(banner).font(.system(size: 12, weight: .medium)).foregroundStyle(STVTheme.accent)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 18)
                }

                if store.rules.isEmpty {
                    DSEmptyState("还没有净化规则", systemImage: "wand.and.stars", message: "可新建正则/文本替换规则去除广告水印，或从剪贴板导入 Legado ReplaceRule JSON。", actionTitle: "新建规则") {
                        editing = LegadoReplaceRule(id: Int64(Date().timeIntervalSince1970 * 1000))
                    }
                } else {
                    List {
                        ForEach(store.rules) { r in
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 3) {
                                    HStack(spacing: 6) {
                                        Text(r.name.isEmpty ? r.pattern : r.name)
                                            .font(.system(size: 15, weight: .medium)).foregroundStyle(STVTheme.text).lineLimit(1)
                                        DSStatus(text: r.isRegex ? "正则" : "文本", tone: .muted)
                                        if r.scopeTitle { DSStatus(text: "标题", tone: .accent) }
                                    }
                                    Text("\(r.pattern)  →  \(r.replacement.isEmpty ? "（删除）" : r.replacement)")
                                        .font(.system(size: 12, design: .monospaced)).foregroundStyle(STVTheme.secondary).lineLimit(1)
                                }
                                Spacer(minLength: 0)
                                DSSwitchControl(isOn: Binding(get: { r.isEnabled }, set: { store.setEnabled(r.id, $0) }), label: r.name)
                            }
                            .contentShape(Rectangle())
                            .onTapGesture { editing = r }
                            .listRowBackground(STVTheme.card)
                        }
                        .onDelete { idxs in
                            for i in idxs { store.remove(store.rules[i].id) }
                        }
                    }
                    .listStyle(.insetGrouped)
                    .scrollContentBackground(.hidden)
                }
            }
        }
        .dsSheetChrome([.large])
        .sheet(item: $editing) { rule in
            NovelReplaceRuleEditorSheet(initial: rule) { updated in
                store.upsert(updated)
            }
        }
    }
}

private struct NovelReplaceRuleEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var rule: LegadoReplaceRule
    @State private var sample = "正文示例：本章未完，请点击下一页继续阅读。广告水印 www.test.com"
    let onSave: (LegadoReplaceRule) -> Void

    init(initial: LegadoReplaceRule, onSave: @escaping (LegadoReplaceRule) -> Void) {
        _rule = State(initialValue: initial)
        self.onSave = onSave
    }

    var body: some View {
        DSSheet("编辑净化规则") {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    DSTextField("规则名称（如：去除分页提示）", text: $rule.name, icon: "tag")
                    DSTextField("分组（可选）", text: Binding(get: { rule.group ?? "" }, set: { rule.group = $0.isEmpty ? nil : $0 }), icon: "folder")
                    DSTextField("匹配规则（正则或文本）", text: $rule.pattern, icon: "text.magnifyingglass")
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    DSTextField("替换为（留空即删除）", text: $rule.replacement, icon: "arrow.right")
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    DSTextField("作用范围（书名/源地址，逗号分隔，留空全局）", text: Binding(get: { rule.scope ?? "" }, set: { rule.scope = $0.isEmpty ? nil : $0 }), icon: "scope")
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    DSTextField("排除范围（可选）", text: Binding(get: { rule.excludeScope ?? "" }, set: { rule.excludeScope = $0.isEmpty ? nil : $0 }), icon: "minus.circle")
                        .textInputAutocapitalization(.never).autocorrectionDisabled()

                    DSSection {
                        Toggle("使用正则表达式", isOn: $rule.isRegex).dsRow()
                        Hairline()
                        Toggle("作用于正文", isOn: $rule.scopeContent).dsRow()
                        Hairline()
                        Toggle("作用于章节标题", isOn: $rule.scopeTitle).dsRow()
                    }

                    Text("实时预览测试").font(.system(size: 13, weight: .semibold)).foregroundStyle(STVTheme.secondary).padding(.top, 4)
                    DSTextField("输入测试文本", text: $sample, icon: "doc.text")
                    VStack(alignment: .leading, spacing: 4) {
                        Text("替换结果：").font(.system(size: 11, weight: .semibold)).foregroundStyle(STVTheme.tertiary)
                        Text(rule.apply(to: sample))
                            .font(.system(size: 13)).foregroundStyle(STVTheme.ok)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(12)
                    .background(STVTheme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .padding(.horizontal, 16).padding(.bottom, 24)
            }
        } footer: {
            DSSheetFooter {
                Button {
                    guard !rule.pattern.isEmpty else { return }
                    if rule.name.isEmpty { rule.name = rule.pattern }
                    onSave(rule)
                    dismiss()
                } label: { PillButtonLabel(title: "保存规则", tone: .accent, expand: true) }
                .buttonStyle(PressScale(scale: 0.98))
                .disabled(rule.pattern.isEmpty)
            }
        }
        .dsSheetChrome([.large])
    }
}

// MARK: - In-App Source JSON / mainJs Editor

struct NovelSourceEditorView: View {
    @Environment(\.dismiss) private var dismiss
    let source: LegadoSource
    let onSave: (LegadoSource) -> Void

    @State private var text: String
    @State private var errorText: String?

    init(source: LegadoSource, onSave: @escaping (LegadoSource) -> Void) {
        self.source = source
        self.onSave = onSave
        _text = State(initialValue: source.isJsSource ? (source.mainJs ?? "") : source.prettyJSON())
    }

    var body: some View {
        DSSheet(source.isJsSource ? "编辑 JS 书源" : "编辑书源 JSON", subtitle: source.bookSourceName) {
            VStack(spacing: 10) {
                TextEditor(text: $text)
                    .font(.system(size: 12, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .scrollContentBackground(.hidden)
                    .padding(10)
                    .background(STVTheme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .padding(.horizontal, 16)
                if let errorText {
                    Text(errorText).font(.system(size: 12)).foregroundStyle(DSTone.danger).padding(.horizontal, 18)
                }
            }
        } footer: {
            DSSheetFooter {
                Button {
                    do {
                        let parsed: LegadoSource
                        if source.isJsSource {
                            parsed = try LegadoJsSource.extract(text)
                        } else {
                            let list = try LegadoSourceStore.parse(Data(text.utf8))
                            guard let first = list.first else { throw LegadoError.rule("JSON 未包含有效书源") }
                            parsed = first
                        }
                        onSave(parsed)
                        dismiss()
                    } catch {
                        errorText = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                    }
                } label: { PillButtonLabel(title: "校验并保存", tone: .accent, expand: true) }
                .buttonStyle(PressScale(scale: 0.98))
            }
        }
        .dsSheetChrome([.large])
    }
}

// MARK: - Paragraph Reviews Sheet (段评)

struct NovelReviewSheet: View {
    let source: LegadoSource
    let chapter: LegadoChapter
    let bookTitle: String
    let bookURL: String

    @State private var summary = LegadoReview.Summary(counts: [:], paraData: [:])
    @State private var selectedPara = 0
    @State private var comments: [LegadoReview.Comment] = []
    @State private var loading = true
    @State private var errorText: String?

    var body: some View {
        DSSheet("段评 · \(chapter.title)", subtitle: source.bookSourceName) {
            VStack(spacing: 10) {
                let sortedParas = summary.counts.keys.sorted()
                if !sortedParas.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            paraChip("整章", idx: 0, count: nil)
                            ForEach(sortedParas, id: \.self) { p in
                                paraChip("第\(p)段", idx: p, count: summary.counts[p])
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                }

                if loading {
                    Spacer()
                    DSSpinner(size: 24, tint: STVTheme.accent)
                    Spacer()
                } else if let errorText {
                    DSEmptyState("段评加载失败", systemImage: "bubble.left.and.exclamationmark.bubble.right", message: errorText)
                } else if comments.isEmpty {
                    DSEmptyState("暂无段评", systemImage: "text.bubble", message: "这一段还没有书友留下评论。")
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 10) {
                            ForEach(comments) { c in
                                VStack(alignment: .leading, spacing: 6) {
                                    HStack(spacing: 6) {
                                        Text(c.name).font(.system(size: 13, weight: .semibold)).foregroundStyle(STVTheme.text)
                                        ForEach(c.badges, id: \.self) { b in DSStatus(text: b, tone: .accent) }
                                        Spacer()
                                        if !c.time.isEmpty {
                                            Text(c.time).font(.system(size: 11)).foregroundStyle(STVTheme.tertiary)
                                        }
                                    }
                                    Text(c.content).font(.system(size: 14)).foregroundStyle(STVTheme.text)
                                    if c.likeCount > 0 || c.replyCount > 0 {
                                        HStack(spacing: 12) {
                                            if c.likeCount > 0 { Label("\(c.likeCount)", systemImage: "hand.thumbsup") }
                                            if c.replyCount > 0 { Label("\(c.replyCount)", systemImage: "bubble.right") }
                                        }
                                        .font(.system(size: 11)).foregroundStyle(STVTheme.secondary)
                                    }
                                }
                                .padding(12)
                                .background(STVTheme.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                        }
                        .padding(.horizontal, 16).padding(.bottom, 24)
                    }
                }
            }
        }
        .dsSheetChrome([.medium, .large])
        .task { await loadInitial() }
    }

    private func paraChip(_ title: String, idx: Int, count: Int?) -> some View {
        Button {
            DS.tick()
            selectedPara = idx
            Task { await loadDetail(para: idx) }
        } label: {
            Text(count.map { "\(title) (\($0))" } ?? title)
                .font(.system(size: 12, weight: .medium))
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(selectedPara == idx ? STVTheme.accentSoft : STVTheme.glass, in: Capsule())
                .foregroundStyle(selectedPara == idx ? STVTheme.accent : STVTheme.secondary)
        }
        .buttonStyle(PressScale())
    }

    private func loadInitial() async {
        loading = true; errorText = nil
        let src = source, ch = chapter, bTitle = bookTitle, bURL = bookURL
        do {
            let sum = try await LegadoClient.run {
                try LegadoReview.summary(src, chapter: ch, book: ["name": bTitle, "bookUrl": bURL])
            }
            summary = sum
            let firstPara = sum.counts.keys.sorted().first ?? 0
            selectedPara = firstPara
            await loadDetail(para: firstPara)
        } catch {
            errorText = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            loading = false
        }
    }

    private func loadDetail(para: Int) async {
        loading = true; errorText = nil
        let src = source, ch = chapter, bTitle = bookTitle, bURL = bookURL
        let pd = summary.paraData[para] ?? ""
        do {
            let page = try await LegadoClient.run {
                try LegadoReview.detail(src, chapter: ch, paraIndex: para, paraData: pd, page: 1, book: ["name": bTitle, "bookUrl": bURL])
            }
            comments = page.items
        } catch {
            errorText = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
        loading = false
    }
}

// MARK: - Inline Image & Manga Chapter Decoder

enum NovelChapterSegment: Identifiable, Hashable {
    case text(Int, String)
    case image(Int, String)

    var id: Int {
        switch self {
        case .text(let i, _), .image(let i, _): i
        }
    }
}

func splitChapterSegments(_ raw: String) -> [NovelChapterSegment] {
    guard raw.range(of: "<img", options: .caseInsensitive) != nil,
          let re = try? NSRegularExpression(pattern: #"<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["'][^>]*>"#, options: .caseInsensitive) else {
        return [.text(0, raw)]
    }
    let ns = raw as NSString
    let matches = re.matches(in: raw, range: NSRange(location: 0, length: ns.length))
    guard !matches.isEmpty else { return [.text(0, raw)] }
    var out: [NovelChapterSegment] = []
    var cursor = 0
    var idx = 0
    for m in matches {
        if m.range.location > cursor {
            let chunk = ns.substring(with: NSRange(location: cursor, length: m.range.location - cursor))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !chunk.isEmpty { out.append(.text(idx, chunk)); idx += 1 }
        }
        let src = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespacesAndNewlines)
        if !src.isEmpty { out.append(.image(idx, src)); idx += 1 }
        cursor = m.range.location + m.range.length
    }
    if cursor < ns.length {
        let tail = ns.substring(from: cursor).trimmingCharacters(in: .whitespacesAndNewlines)
        if !tail.isEmpty { out.append(.text(idx, tail)) }
    }
    return out
}

struct NovelDecodedImageView: View {
    let source: LegadoSource
    let url: String
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            } else if failed {
                HStack(spacing: 8) {
                    Image(systemName: "photo.badge.exclamationmark")
                    Text("图片加载失败").font(.system(size: 12))
                    Spacer()
                    Button("重试") { failed = false; Task { await load() } }.font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(STVTheme.secondary)
                .padding(14)
                .background(STVTheme.glass, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 10, style: .continuous).fill(STVTheme.glass).frame(height: 220)
                    DSSpinner(size: 20, tint: STVTheme.accent)
                }
            }
        }
        .task(id: url) { await load() }
    }

    private func load() async {
        guard image == nil else { return }
        let src = source, imgURL = url
        do {
            let data: Data = try await LegadoClient.run {
                let ctx = LegadoContext(source: src)
                let req = try LegadoURL.request(imgURL, ctx: ctx, base: src.bookSourceUrl)
                let resp = try req.fetch()
                return LegadoJsSource.decodeImageBytes(src, imageURL: imgURL, data: resp.body, isCover: false)
            }
            if let ui = UIImage(data: data) { image = ui } else { failed = true }
        } catch {
            failed = true
        }
    }
}
