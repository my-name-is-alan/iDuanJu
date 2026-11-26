import SwiftUI
import UniformTypeIdentifiers

/// 我的 → 插件：已安装插件、添加（网址 / 文件 / 粘贴）、订阅、内置推荐、苹果 CMS 采集站、检查更新。
struct PluginsView: View {
    @ObservedObject private var store = PluginStore.shared
    @ObservedObject private var cms = CMSStore.shared
    @ObservedObject private var gate = SourceGate.shared
    @State private var pending: PendingInstall?
    @State private var batch: BatchInstall?
    @State private var updates: [PendingInstall] = []
    @State private var busy: String?
    @State private var message: String?
    @State private var showURL = false
    @State private var urlText = ""
    @State private var showPaste = false
    @State private var showFile = false
    @State private var showSub = false
    @State private var subText = ""
    @State private var showCMS = false
    /// Bundled plugin manifests are parsed from the app bundle once, not in every body.
    @State private var bundled: [(name: String, manifest: PluginManifest, data: Data)] = []

    var body: some View {
        let plugins = store.visiblePlugins, recommended = self.recommended
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                DSSection("已安装（\(plugins.count)）", footer: hiddenAdult > 0 ? "另有 \(hiddenAdult) 个成人插件已隐藏：在「片源管理」解锁后显示。" : nil, separatorInset: 58) {
                    if plugins.isEmpty {
                        Text("还没有插件。可以从下面的网址 / 文件 / 订阅添加，或安装内置推荐。").dsFont(14, relativeTo: .subheadline)
                            .foregroundStyle(STVTheme.secondary).dsRow(vertical: 14)
                    }
                    ForEach(plugins) { p in pluginRow(p) }
                }
                DSSection("添加插件", separatorInset: 58) {
                    action("从网址添加", "link", id: "plugins.add.url") { urlText = ""; showURL = true }
                    action("从文件导入", "doc.badge.plus", subtitle: "插件 / M3U / TVBox JSON", id: "plugins.add.file") { showFile = true }
                    action("粘贴插件内容", "doc.on.clipboard", id: "plugins.add.paste") { showPaste = true }
                    action(busy == "update" ? "正在检查更新…" : "检查更新", "arrow.triangle.2.circlepath", id: "plugins.update") { await checkUpdates() }
                }
                if let message { banner(message) }
                if !updates.isEmpty {
                    DSSection("可更新", separatorInset: 58) {
                        ForEach(updates) { u in
                            DSRow(u.manifest.name, icon: "arrow.up.circle", subtitle: "可更新到 \(u.manifest.version)", value: "查看权限变化", chevron: true) { pending = u }
                        }
                    }
                }
                DSSection("订阅", separatorInset: 58) {
                    ForEach(store.subscriptions) { s in subscriptionBlock(s) }
                    action("添加订阅地址", "plus.circle", id: "plugins.sub.add") { subText = ""; showSub = true }
                    if !store.subscriptions.isEmpty {
                        action(busy == "subs" ? "正在刷新…" : "刷新全部订阅", "arrow.clockwise", id: "plugins.sub.refresh") {
                            busy = "subs"; await store.refreshSubscriptions(); busy = nil
                        }
                    }
                }
                DSSection("采集站（苹果 CMS）", footer: "采集站使用内置的苹果 CMS v10 适配器（?ac=list / ?ac=detail），不运行任何脚本；只播放线路里的 m3u8 / mp4 直链，接口需支持 https。", separatorInset: 58) {
                    ForEach(cms.visibleSites) { s in cmsRow(s) }
                    action("添加采集站", "server.rack", id: "plugins.cms.add") { showCMS = true }
                }
                if !recommended.isEmpty {
                    DSSection("推荐插件（App 内置）") {
                        ForEach(recommended, id: \.manifest.id) { b in
                            HStack(spacing: 12) {
                                DSRowLabel(title: b.manifest.name, subtitle: b.manifest.description)
                                if store.plugin(b.manifest.id) != nil { Text("已安装").dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary) }
                                else {
                                    Button { prepare { try PluginInstaller.prepare(bundle: b.data, origin: "内置：" + b.name) } } label: { MiniButtonLabel(title: "安装") }
                                        .buttonStyle(PressScale()).accessibilityIdentifier("plugins.bundled.\(b.name)")
                                }
                            }
                            .dsRow()
                        }
                    }
                } else if !bundled.isEmpty {
                    note("App 内置了 \(bundled.count) 个漫画插件（成人内容），在「片源管理」打开「成人漫画源」后才会显示在这里。")
                }
                note("插件在独立沙箱里运行：没有 eval、不能访问未声明的域名和本机 / 局域网地址，每个插件的 cookie 与存储互相隔离，接触不到 Telegram、红果账号和钥匙串。所有插件结果都会过滤未成年人相关内容。")
            }
            .padding(.top, 4).padding(.bottom, 32)
        }
        .dsNavigation("插件")
        .overlay { if busy == "prepare" { DSBusyOverlay(label: "正在校验插件…") } }
        .animation(DS.panel, value: busy)
        .sheet(item: $pending) { p in
            PluginConsentSheet(pending: p) { ok in
                pending = nil
                guard ok else { return }
                do {
                    try store.commit(p)
                    updates.removeAll { $0.manifest.id == p.manifest.id }
                    message = "已安装 \(p.manifest.name) \(p.manifest.version)"
                } catch { message = error.localizedDescription }
            }
        }
        .sheet(item: $batch) { b in
            PluginBatchConsentSheet(batch: b) { ok in
                batch = nil
                guard ok else { return }
                var installed = 0
                var failures = b.failed
                for p in b.items {
                    do { try store.commit(p); installed += 1 }
                    catch { failures.append("\(p.manifest.name)：\(error.localizedDescription)") }
                }
                message = "已安装 \(installed) 个插件" + (failures.isEmpty ? "" : "；跳过 \(failures.count) 个：\(failures.joined(separator: "；"))")
            }
        }
        .dsDialog("插件网址", isPresented: $showURL, message: "支持 TVBox 配置、M3U、插件和订阅链接") {
            [.cancel(), .primary("下一步", disabled: { urlText.trimmingCharacters(in: .whitespaces).isEmpty }) { interpret(urlText) }]
        } extra: {
            DSTextField("https://…/manifest.json 或 .idjplugin", text: $urlText, icon: "link").textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
        }
        .dsDialog("订阅地址", isPresented: $showSub) {
            [.cancel(), .primary("添加", disabled: { subText.trimmingCharacters(in: .whitespaces).isEmpty }) {
                Task { do { try await store.addSubscription(subText); message = "已添加订阅" } catch { message = error.localizedDescription } }
            }]
        } extra: {
            DSTextField("https://…/plugins.json", text: $subText, icon: "link").textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
        }
        .sheet(isPresented: $showPaste) { PasteSheet { text in showPaste = false; if let text { interpret(text) } } }
        .sheet(isPresented: $showCMS) { AddCMSSheet { showCMS = false; if let m = $0 { message = m } } }
        .fileImporter(isPresented: $showFile, allowedContentTypes: [.json, .data, .plainText, .item]) { r in
            guard case let .success(url) = r else { return }
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            guard let d = try? Data(contentsOf: url), d.count < 4 << 20 else { message = "无法读取文件"; return }
            if let text = try? TVBoxDecoder.decode(d), TVBoxConfig.isConfig(text) {
                let file = url.lastPathComponent
                Task { await importTVBox { try await TVBoxStore.shared.importLocal(d, fileName: file) } }
                return
            }
            if let list = String(data: d, encoding: .utf8), M3UImport.isChannelList(list) {
                let name = url.deletingPathExtension().lastPathComponent
                prepare { try PluginInstaller.prepareM3U(list, url: nil, name: name, origin: "文件：" + url.lastPathComponent) }
                return
            }
            prepare { try PluginInstaller.prepare(bundle: d, origin: "文件：" + url.lastPathComponent) }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("plugins.root")
        .task { if bundled.isEmpty { bundled = await Task.detached { PluginInstaller.bundled() }.value } }
        #if DEBUG
        .task { if ProcessInfo.processInfo.environment["STV_PLUGIN_CONSENT_DEMO"] == "1", pending == nil { pending = DebugPlugins.demoPending(update: true) } }
        #endif
    }

    private var hiddenAdult: Int { store.plugins.count - store.visiblePlugins.count }

    /// Bundled plugins offered for install; skip video sites that iOS already ships natively in 片源管理.
    private static let iosNativeBundledIDs: Set<String> = [
        "com.idj.bundled.jable", "com.idj.bundled.missav", "com.idj.bundled.hanime1", "com.idj.bundled.eporner",
        "com.idj.bundled.xvideos", "com.idj.bundled.xnxx", "com.idj.bundled.pornhub", "com.idj.bundled.youporn",
        "com.idj.bundled.redtube", "com.idj.bundled.hqporner",
    ]
    private var recommended: [(name: String, manifest: PluginManifest, data: Data)] {
        bundled.filter {
            !Self.iosNativeBundledIDs.contains($0.manifest.id)
                && (!$0.manifest.adult || ($0.manifest.type == .comic ? gate.showAdultManga : gate.unlocked))
        }
    }

    private func prepare(_ work: @escaping () async throws -> PendingInstall) {
        busy = "prepare"; message = nil
        Task {
            do { let p = try await Task.detached { try await work() }.value; pending = p }
            catch { message = error.localizedDescription }
            busy = nil
        }
    }

    /// Downloads and validates every missing entry, then asks for consent once for the whole batch.
    private func installAll(_ entries: [PluginSubscription.Entry], origin: String) {
        busy = "prepare"; message = nil
        Task {
            var prepared: [PendingInstall] = []
            var failures: [String] = []
            for e in entries {
                do { prepared.append(try await Task.detached { try await PluginInstaller.prepare(manifestURL: e.manifest, origin: origin) }.value) }
                catch { failures.append("\(e.name)：\(error.localizedDescription)") }
            }
            busy = nil
            if prepared.isEmpty { message = "全部安装失败：" + (failures.first ?? "未知错误"); return }
            batch = BatchInstall(items: prepared, failed: failures)
        }
    }

    private func interpret(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        busy = "prepare"; message = nil
        Task {
            do {
                switch try await Task.detached { try await PluginInstaller.interpret(t) }.value {
                case .plugin(let p): pending = p
                case .subscription(let u): try await store.addSubscription(u); message = "这是订阅列表，已添加到「订阅」"
                case .tvbox(let text): await importTVBox { try await TVBoxStore.shared.importText(text) }
                }
            } catch { message = error.localizedDescription }
            busy = nil
        }
    }

    @MainActor private func importTVBox(_ work: @MainActor () async throws -> ImportResult) async {
        busy = "prepare"; message = nil
        do {
            switch try await work() {
            case .imported(let r): message = "已导入 TVBox「\(r.name)」：\(r.sites.count) 个点播站、\(r.lives.count) 个直播源，请到「TVBox」页使用"
            case .depot(let d, _): message = "已添加 TVBox 多仓「\(d.name)」（\(d.entries.count) 条线路），请到「TVBox」页选择"
            case .attachment(let name): message = "已保存文件 \(name)"
            }
        } catch { message = error.localizedDescription }
        busy = nil
    }

    private func checkUpdates() async {
        busy = "update"; message = nil
        updates = await store.checkUpdates()
        busy = nil
        message = updates.isEmpty ? "所有插件都是最新版本" : "\(updates.count) 个插件可更新"
    }

    private func pluginRow(_ p: InstalledPlugin) -> some View {
        HStack(spacing: 10) {
            NavigationLink { PluginDetailView(id: p.id) } label: {
                HStack(spacing: 12) {
                    IconWell(systemName: p.manifest.type == .comic ? "book.closed" : "puzzlepiece.extension", size: 32, corner: 10,
                             tint: p.enabled ? STVTheme.accent : STVTheme.tertiary, ground: p.enabled ? STVTheme.accentSoft : STVTheme.glass)
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text(p.manifest.name).dsFont(16, .medium).foregroundStyle(STVTheme.text).lineLimit(1)
                            DSTag(text: p.manifest.type.label)
                            if p.manifest.adult { DSTag(text: "18+") }
                        }
                        Text(p.disabledReason ?? "v\(p.manifest.version) · \(p.manifest.author.name) · \(p.enabled ? "已启用" : "已停用")")
                            .dsFont(12, relativeTo: .caption).foregroundStyle(p.disabledReason == nil ? STVTheme.secondary : STVTheme.accentWarm).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(STVTheme.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(PressScale(scale: 0.99))
            .accessibilityIdentifier("plugins.row.\(p.id)")
            DSSwitchControl(isOn: Binding(get: { p.enabled }, set: { store.setEnabled(p.id, $0) }), label: "启用 \(p.manifest.name)")
                .accessibilityIdentifier("plugins.toggle.\(p.id)")
        }
        .dsRow()
    }

    @ViewBuilder private func subscriptionBlock(_ s: PluginSubscription) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                DSRowLabel(title: s.name.isEmpty ? s.url : s.name, icon: "antenna.radiowaves.left.and.right")
                Button { store.removeSubscription(s.url) } label: {
                    Image(systemName: "trash").font(.system(size: 15, weight: .medium)).foregroundStyle(DSTone.danger).frame(width: 40, height: 40).contentShape(Rectangle())
                }
                .buttonStyle(PressScale()).accessibilityLabel("删除订阅")
            }
            Text(s.error ?? s.url).dsFont(11, relativeTo: .caption2).foregroundStyle(s.error == nil ? STVTheme.secondary : STVTheme.accentWarm).lineLimit(1)
                .padding(.leading, 44).padding(.top, -12)
            let missing = s.plugins.filter { (!($0.adult ?? true) || gate.unlocked) && store.plugin($0.id) == nil }
            if missing.count > 1 {
                HStack {
                    Spacer(minLength: 0)
                    Button { installAll(missing, origin: "订阅：" + s.url) } label: { MiniButtonLabel(title: "全部安装（\(missing.count)）") }
                        .buttonStyle(PressScale()).accessibilityIdentifier("plugins.sub.installAll")
                }
                .padding(.leading, 44)
            }
            ForEach(s.plugins.filter { !($0.adult ?? true) || gate.unlocked }) { e in
                HStack(spacing: 8) {
                    Text(e.name).dsFont(14, relativeTo: .subheadline).foregroundStyle(STVTheme.text)
                    if e.adult ?? true { DSTag(text: "18+") }
                    Spacer(minLength: 0)
                    if let installed = store.plugin(e.id) {
                        if let ver = e.version, PluginManifest.compare(ver, installed.manifest.version) > 0 {
                            Button { prepare { try await PluginInstaller.prepare(manifestURL: e.manifest, origin: "订阅：" + s.url) } } label: { MiniButtonLabel(title: "更新 \(ver)") }
                                .buttonStyle(PressScale())
                        } else {
                            Text("已安装").dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary)
                        }
                    } else {
                        Button { prepare { try await PluginInstaller.prepare(manifestURL: e.manifest, origin: "订阅：" + s.url) } } label: { MiniButtonLabel(title: "安装") }
                            .buttonStyle(PressScale())
                    }
                }
                .padding(.leading, 44)
            }
            ForEach(s.cms.filter { !($0.adult ?? false) || gate.unlocked }) { c in
                HStack(spacing: 8) {
                    Image(systemName: "server.rack").font(.caption).foregroundStyle(STVTheme.secondary)
                    Text(c.name).dsFont(14, relativeTo: .subheadline).foregroundStyle(STVTheme.text)
                    Spacer(minLength: 0)
                    if cms.sites.contains(where: { $0.api == (try? CMSStore.normalize(c.api)) }) { Text("已添加").dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary) }
                    else {
                        Button {
                            Task { do { try await cms.add(name: c.name, api: c.api, adult: c.adult ?? false, origin: "订阅：" + s.url); message = "已添加采集站 \(c.name)" }
                                   catch { message = error.localizedDescription } }
                        } label: { MiniButtonLabel(title: "添加") }
                            .buttonStyle(PressScale())
                    }
                }
                .padding(.leading, 44)
            }
        }
        .dsRow(vertical: 12)
    }

    private func cmsRow(_ s: CMSSite) -> some View {
        HStack(spacing: 10) {
            IconWell(systemName: "server.rack", size: 32, corner: 10, tint: s.enabled ? STVTheme.accent : STVTheme.tertiary, ground: s.enabled ? STVTheme.accentSoft : STVTheme.glass)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(s.name).dsFont(16, .medium).foregroundStyle(STVTheme.text).lineLimit(1)
                    if s.adult { DSTag(text: "18+") }
                }
                Text("\(URL(string: s.api)?.host ?? s.api) · \(s.categories.count) 个分类").dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            Menu {
                Toggle("成人内容（需片源管理解锁）", isOn: Binding(get: { s.adult }, set: { cms.setAdult(s.key, $0) }))
                Button("删除", systemImage: "trash", role: .destructive) { cms.remove(s.key) }
            } label: {
                Image(systemName: "ellipsis").font(.system(size: 16, weight: .semibold)).foregroundStyle(STVTheme.secondary).frame(width: 36, height: 40).contentShape(Rectangle())
            }
            .accessibilityLabel("更多")
            DSSwitchControl(isOn: Binding(get: { s.enabled }, set: { cms.setEnabled(s.key, $0) }), label: "启用 \(s.name)")
        }
        .dsRow()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("plugins.cms.\(s.key)")
    }

    private func note(_ t: String) -> some View {
        Text(t).dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.tertiary).lineSpacing(3).padding(.horizontal, 20)
    }

    /// Import results can be long (per-site failures), so they stay on the page instead of flashing by in a toast.
    private func banner(_ t: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "info.circle").font(.system(size: 15, weight: .medium)).foregroundStyle(STVTheme.accent)
            Text(t).dsFont(14, relativeTo: .subheadline).foregroundStyle(STVTheme.text).textSelection(.enabled).accessibilityIdentifier("plugins.message")
            Spacer(minLength: 0)
            Button { message = nil } label: { Image(systemName: "xmark").font(.system(size: 11, weight: .bold)).foregroundStyle(STVTheme.tertiary).frame(width: 30, height: 30).contentShape(Rectangle()) }
                .buttonStyle(.plain).accessibilityLabel("关闭")
        }
        .padding(14)
        .background(STVTheme.accentSoft, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, 16)
        .transition(.opacity)
    }

    private func action(_ t: String, _ icon: String, subtitle: String? = nil, id: String, _ f: @escaping () async -> Void) -> some View {
        DSRow(t, icon: icon, subtitle: subtitle, chevron: true) { Task { await f() } }.accessibilityIdentifier(id)
    }
}

/// Install / update consent: what the plugin is, which domains it may reach, extra powers, and the diff on update.
struct PluginConsentSheet: View {
    let pending: PendingInstall
    let done: (Bool) -> Void
    private var m: PluginManifest { pending.manifest }

    var body: some View {
        DSSheet(pending.isUpdate ? "更新插件" : "安装插件", onClose: { done(false) }) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 8) {
                            Text(m.name).dsFont(19, .bold, relativeTo: .title3).foregroundStyle(STVTheme.text)
                            if m.adult { DSStatus(text: "18+", tone: .warn) }
                        }
                        Text("\(m.type.label) · v\(m.version) · 作者 \(m.author.name)").dsFont(14, relativeTo: .subheadline).foregroundStyle(STVTheme.secondary)
                        if let d = m.description, !d.isEmpty { Text(d).dsFont(14, relativeTo: .subheadline).foregroundStyle(STVTheme.text) }
                        Text("来源：\(pending.origin)").dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary)
                        line(pending.signedBy == nil ? "未签名（无法确认作者身份）" : "已验证作者签名", pending.signedBy == nil ? "exclamationmark.shield" : "checkmark.shield",
                             pending.signedBy == nil ? STVTheme.accentWarm : STVTheme.ok)
                    }
                    .padding(.horizontal, 20)
                    if let diff = pending.diff, pending.isUpdate {
                        DSSection("更新：从 v\(pending.existing?.manifest.version ?? "?") 到 v\(m.version) 的权限变化") {
                            if diff.isEmpty { line("权限没有变化", "checkmark.circle", STVTheme.secondary).dsRow() }
                            ForEach(diff.addedHTTP, id: \.self) { line("新增网络域名 \($0)", "plus.circle", STVTheme.accentWarm).dsRow() }
                            ForEach(diff.addedMedia, id: \.self) { line("新增图片/视频域名 \($0)", "plus.circle", STVTheme.accentWarm).dsRow() }
                            ForEach(diff.removedHTTP + diff.removedMedia, id: \.self) { line("移除 \($0)", "minus.circle", STVTheme.secondary).dsRow() }
                            ForEach(diff.flags, id: \.self) { line($0, $0.hasPrefix("新增") ? "exclamationmark.triangle" : "minus.circle", $0.hasPrefix("新增") ? STVTheme.accentWarm : STVTheme.secondary).dsRow() }
                        }
                    }
                    DSSection("允许访问的网站（只能访问这些域名）") {
                        ForEach(m.httpDomains, id: \.self) { line($0, "globe", STVTheme.text).dsRow() }
                    }
                    if Set(m.mediaDomains) != Set(m.httpDomains) {
                        DSSection("图片 / 视频地址") { ForEach(m.mediaDomains, id: \.self) { line($0, "photo", STVTheme.text).dsRow() } }
                    }
                    DSSection("额外能力", footer: "插件始终不能：eval / 动态代码、访问本机或局域网地址、读取其他插件或 Telegram / 红果 / 钥匙串数据。") {
                        capability("Cloudflare 验证（本插件独立的隐藏网页，cookie 不与其他插件共享）", m.allowsCF)
                        capability("插件私有存储（≤1MB）", m.allowsStorage)
                        capability("请你登录该站点", m.allowsLogin)
                    }
                    DSSection("脚本") {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("实现：\(pending.exported.joined(separator: " · "))").dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.text)
                            Text(pending.fileHash).font(.system(.caption2, design: .monospaced)).foregroundStyle(STVTheme.secondary).textSelection(.enabled)
                        }
                        .dsRow()
                    }
                }
                .padding(.bottom, 20)
            }
        } footer: {
            DSSheetFooter {
                Button { done(false) } label: { PillButtonPlain(title: "取消") }
                Button { done(true) } label: { PillButtonLabel(title: pending.isUpdate ? "更新" : "安装", expand: true) }
                    .accessibilityIdentifier("plugins.consent.install")
            }
            .buttonStyle(PressScale(scale: 0.98))
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("plugins.consent")
        .dsSheetChrome([.large])
    }

    private func line(_ t: String, _ icon: String, _ tint: Color) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: icon).font(.system(size: 14, weight: .medium)).foregroundStyle(tint).frame(width: 20).accessibilityHidden(true)
            Text(t).dsFont(14, relativeTo: .subheadline).foregroundStyle(tint == STVTheme.text ? STVTheme.text : tint).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func capability(_ t: String, _ on: Bool) -> some View {
        line(t, on ? "checkmark.circle.fill" : "circle", on ? STVTheme.text : STVTheme.tertiary).dsRow()
    }
}

/// One consent prompt for a subscription's "全部安装": every prepared plugin with its origin, signature
/// state and allowed domains, then a single install action.
private struct BatchInstall: Identifiable {
    let id = UUID()
    var items: [PendingInstall]
    var failed: [String]
}

private struct PluginBatchConsentSheet: View {
    let batch: BatchInstall
    let done: (Bool) -> Void

    var body: some View {
        DSSheet("安装插件（\(batch.items.count) 个）", onClose: { done(false) }) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    ForEach(batch.items) { p in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 8) {
                                Text(p.manifest.name).dsFont(16, .medium).foregroundStyle(STVTheme.text)
                                if p.manifest.adult { DSStatus(text: "18+", tone: .warn) }
                                Spacer(minLength: 0)
                                Text("v\(p.manifest.version)").dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary)
                            }
                            Text("\(p.manifest.type.label) · 作者 \(p.manifest.author.name) · 来源 \(p.origin)")
                                .dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.secondary)
                            Text(p.manifest.httpDomains.joined(separator: " · "))
                                .dsFont(11, relativeTo: .caption2).foregroundStyle(STVTheme.tertiary).lineLimit(2)
                            Text(p.signedBy == nil ? "未签名（无法确认作者身份）" : "已验证作者签名")
                                .dsFont(11, relativeTo: .caption2).foregroundStyle(p.signedBy == nil ? STVTheme.accentWarm : STVTheme.ok)
                        }
                        .dsRow(vertical: 10)
                    }
                    if !batch.failed.isEmpty {
                        Text("以下插件校验失败，将跳过：\n" + batch.failed.joined(separator: "\n"))
                            .dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.accentWarm).padding(.horizontal, 20)
                    }
                    Text("每个插件只能访问上面列出的域名，运行在独立沙箱里：没有 eval、不能访问本机 / 局域网地址，也读不到其他插件的数据。")
                        .dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.tertiary).lineSpacing(3).padding(.horizontal, 20)
                }
                .padding(.bottom, 20)
            }
        } footer: {
            DSSheetFooter {
                Button { done(false) } label: { PillButtonPlain(title: "取消") }
                Button { done(true) } label: { PillButtonLabel(title: "全部安装", expand: true) }
                    .accessibilityIdentifier("plugins.batch.consent.install")
            }
            .buttonStyle(PressScale(scale: 0.98))
        }
        .accessibilityElement(children: .contain)
        .dsSheetChrome([.large])
    }
}

private struct PasteSheet: View {
    let done: (String?) -> Void
    @State private var text = ""
    var body: some View {
        DSSheet("粘贴插件", subtitle: "支持 .idjplugin、M3U 频道列表、TVBox JSON 或插件 / 订阅链接", onClose: { done(nil) }) {
            DSTextEditor(placeholder: "粘贴 .idjplugin、M3U 频道列表、TVBox JSON 或插件 / 订阅链接", text: $text, mono: true)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(.horizontal, 20)
        } footer: {
            DSSheetFooter {
                Button { text = UIPasteboard.general.string ?? "" } label: { PillButtonPlain(title: "从剪贴板") }
                Button { done(text) } label: { PillButtonLabel(title: "下一步", expand: true) }
                    .disabled(text.isEmpty).opacity(text.isEmpty ? 0.4 : 1)
            }
            .buttonStyle(PressScale(scale: 0.98))
        }
        .dsSheetChrome([.large])
    }
}

private struct AddCMSSheet: View {
    let done: (String?) -> Void
    @State private var name = ""
    @State private var api = ""
    @State private var adult = false
    @State private var busy = false
    @State private var error: String?
    @ObservedObject private var gate = SourceGate.shared
    var body: some View {
        DSSheet("添加采集站", onClose: { done(nil) }) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    DSTextField("名称（可选）", text: $name, icon: "tag")
                    DSTextField("https://…/api.php/provide/vod/", text: $api, icon: "link", id: "cms.api")
                        .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    if gate.unlocked {
                        Toggle(isOn: $adult) { Text("成人内容（隐藏在片源管理之后）").dsFont(15).foregroundStyle(STVTheme.text) }
                            .toggleStyle(DSSwitch()).padding(.vertical, 6)
                    }
                    Text("苹果 CMS v10 采集接口。添加时会请求一次 ?ac=list 验证；http 地址会自动改用 https。")
                        .dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.tertiary).lineSpacing(3)
                    if let error { Text(error).dsFont(13, relativeTo: .footnote).foregroundStyle(DSTone.danger) }
                }
                .padding(.horizontal, 20).padding(.bottom, 12)
            }
        } footer: {
            DSSheetFooter {
                Button(action: add) {
                    ZStack {
                        PillButtonLabel(title: busy ? "" : "添加", expand: true)
                        if busy { DSSpinner(size: 18, tint: STVTheme.onAccent) }
                    }
                }
                .disabled(busy || api.isEmpty).opacity(api.isEmpty ? 0.4 : 1)
            }
            .buttonStyle(PressScale(scale: 0.98))
        }
        .dsSheetChrome([.medium, .large])
    }

    private func add() {
        busy = true; error = nil
        Task {
            do { let s = try await CMSStore.shared.add(name: name, api: api, adult: adult); done("已添加采集站 \(s.name)（\(s.categories.count) 个分类）") }
            catch { self.error = error.localizedDescription }
            busy = false
        }
    }
}

/// One installed plugin: switch, settings, domains, data, logs, uninstall.
struct PluginDetailView: View {
    let id: String
    @ObservedObject private var store = PluginStore.shared
    @Environment(\.dismiss) private var dismiss
    @State private var newDomain = ""
    @State private var askDomain = false
    @State private var confirmUninstall = false
    @State private var message: String?
    @State private var secretDrafts: [String: String] = [:]
    @State private var verify: URL?

    var body: some View {
        Group {
            if let p = store.plugin(id) { content(p) }
            else { ScrollView { DSEmptyState("插件已卸载", systemImage: "puzzlepiece.extension").padding(.top, 60) } }
        }
        .dsNavigation(store.plugin(id)?.manifest.name ?? "插件")
        .dsToast($message, icon: "checkmark.circle.fill")
    }

    private func content(_ p: InstalledPlugin) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                DSSection {
                    DSToggleRow("启用", isOn: Binding(get: { p.enabled }, set: { store.setEnabled(id, $0) })).accessibilityIdentifier("plugin.enabled")
                    if let r = p.disabledReason { Text(r).dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.accentWarm).dsRow() }
                    DSRow("版本", value: p.manifest.version)
                    DSRow("类型", value: p.manifest.type.label + (p.manifest.adult ? " · 18+" : ""))
                    DSRow("作者", value: p.manifest.author.name)
                    DSRow("来源", value: p.origin)
                    DSRow("签名", value: p.publicKey == nil ? "未签名" : "已固定作者公钥")
                }
                if let settings = p.manifest.settings, !settings.isEmpty {
                    DSSection("设置") { ForEach(settings) { s in settingRow(p, s) } }
                }
                DSSection("可访问的域名", footer: "站点换了新镜像时，可以在这里手动授权新域名（只允许 example.com 或 *.example.com）。") {
                    ForEach(p.manifest.httpDomains, id: \.self) { DSRow($0, icon: "globe") }
                    ForEach(p.extraDomains, id: \.self) { d in
                        DSRow(title: d, icon: "globe.badge.chevron.backward", subtitle: "你添加的") {
                            Button { store.removeDomain(id, d) } label: { MiniButtonLabel(title: "移除", tint: DSTone.danger, ground: DSTone.dangerSoft) }.buttonStyle(PressScale())
                        }
                    }
                    DSRow("添加域名…", icon: "plus") { newDomain = ""; askDomain = true }
                }
                DSSection("数据", separatorInset: 58) {
                    if p.manifest.allowsCF, let host = p.manifest.httpDomains.first(where: { !$0.hasPrefix("*") }), let u = URL(string: "https://\(host)/") {
                        DSRow("打开验证页（Cloudflare / 登录）", icon: "shield", chevron: true) { verify = u }
                    }
                    DSRow("清除 cookie 与存储", icon: "eraser") { store.clearData(id); message = "已清除" }
                    NavigationLink { PluginLogView(id: id) } label: { DSRowLabel(title: "运行日志", icon: "text.alignleft", chevron: true).dsRow() }
                        .buttonStyle(DSRowPress()).accessibilityIdentifier("plugin.logs")
                }
                DSSection { DSRow("卸载插件", icon: "trash", role: .destructive) { confirmUninstall = true } }
            }
            .padding(.top, 4).padding(.bottom, 32)
        }
        .dsDialog("授权新域名", isPresented: $askDomain, message: "插件之后可以访问这个域名（包括图片）。只在你信任这个插件时授权。") {
            [.cancel(), .primary("授权", disabled: { newDomain.isEmpty }) { message = store.addDomain(id, newDomain.lowercased()) ? "已授权 \(newDomain)" : "域名格式不对" }]
        } extra: {
            DSTextField("mirror.example.com", text: $newDomain, icon: "globe").textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
        }
        .dsConfirm("卸载插件？", isPresented: $confirmUninstall, message: "卸载后会删除脚本、设置、cookie 和存储。", confirm: "卸载") { store.uninstall(id); dismiss() }
        .sheet(item: Binding(get: { verify.map(VerifyTarget.init) }, set: { verify = $0?.url })) { t in
            PluginVerifySheet(pluginID: id, url: t.url) { verify = nil; Task { await store.importWebCookies(id) } }
        }
    }

    private struct VerifyTarget: Identifiable { let url: URL; var id: String { url.absoluteString } }

    @ViewBuilder private func settingRow(_ p: InstalledPlugin, _ s: PluginManifest.Setting) -> some View {
        switch s.type {
        case "select":
            DSPicker(s.name, options: s.options ?? [], selection: Binding(get: { p.settings[s.key] ?? s.default ?? "" }, set: { store.setSetting(id, s.key, $0) }), style: .row) { $0 }
        case "secret":
            HStack(spacing: 10) {
                DSTextField(s.name + (PluginSecrets.get(id, s.key) == nil ? "" : "（已保存）"), text: Binding(get: { secretDrafts[s.key] ?? "" }, set: { secretDrafts[s.key] = $0 }), secure: true)
                Button { store.setSetting(id, s.key, secretDrafts[s.key] ?? ""); secretDrafts[s.key] = nil; message = "已保存到钥匙串（插件只看到占位符）" } label: { MiniButtonLabel(title: "保存") }
                    .buttonStyle(PressScale())
            }
            .dsRow()
        default:
            VStack(alignment: .leading, spacing: 8) {
                Text(s.name).dsFont(13, relativeTo: .footnote).foregroundStyle(STVTheme.secondary)
                DSTextField(s.name, text: Binding(get: { p.settings[s.key] ?? s.default ?? "" }, set: { store.setSetting(id, s.key, $0) }))
            }
            .dsRow()
        }
    }
}

struct PluginLogView: View {
    let id: String
    @State private var lines: [String] = []
    @State private var message: String?
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 6) {
                if lines.isEmpty { DSEmptyState("暂无日志", systemImage: "text.alignleft") }
                ForEach(Array(lines.enumerated().reversed()), id: \.offset) { _, l in
                    Text(l).font(.system(.caption2, design: .monospaced)).foregroundStyle(STVTheme.text).textSelection(.enabled)
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 32)
        }
        .dsNavigation("运行日志") {
            DSBarButton(systemName: "doc.on.doc") { UIPasteboard.general.string = lines.joined(separator: "\n"); message = "已复制" }.accessibilityLabel("复制")
        }
        .dsToast($message, icon: "checkmark.circle.fill")
        .task { lines = PluginLog.lines(id) }
        .onReceive(NotificationCenter.default.publisher(for: PluginLog.changed)) { _ in lines = PluginLog.lines(id) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("plugin.logview")
    }
}
