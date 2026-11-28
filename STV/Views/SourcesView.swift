import SwiftUI

/// 我的 → 片源管理. 红果 is always on; 黄果 (18+) and long-video sites are opt-in and can be
/// hidden behind Face ID (`SourceGate`).
struct SourcesView: View {
    @ObservedObject private var gate = SourceGate.shared
    @ObservedObject private var pluginStore = PluginStore.shared
    @ObservedObject private var cmsStore = CMSStore.shared
    @State private var message: String?
    @State private var showHomeOrder = false
    @State private var bufferMB = BufferSettings.megabytes
    @State private var cacheBytes: Int64 = 0
    @State private var adStrip = HLSAdFilter.enabled
    @State private var engineKind = PlayerEngineSettings.preferred

    private var homeOrderSummary: String {
        let labels = HomeSource.all.prefix(4).map(\.label)
        return labels.joined(separator: " · ") + (HomeSource.all.count > 4 ? "…" : "")
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                if gate.lockEnabled && !gate.unlocked {
                    DSEmptyState("18+ 片源已锁定", systemImage: "lock.fill", message: "验证后才显示隐藏的片源。", actionTitle: "用 Face ID 解锁") { Task { await gate.unlock() } }
                        .padding(.top, 60)
                } else {
                    DSSection("内置与首页展示", separatorInset: 58) {
                        DSRow(title: DramaSource.hongguo.label, icon: "play.rectangle.on.rectangle") { DSStatus(text: "内置", tone: .muted) }
                        DSRow("首页来源排序", icon: "arrow.up.arrow.down", subtitle: homeOrderSummary, value: gate.homeOrder.isEmpty ? "默认" : "自定义", chevron: true) {
                            showHomeOrder = true
                        }
                        .accessibilityIdentifier("sources.homeOrder")
                        DSToggleRow("显示「频道」标签", isOn: Binding(get: { gate.showChannelsTab }, set: { gate.setShowChannelsTab($0) }), icon: "paperplane")
                        DSToggleRow("显示「漫画」标签", isOn: Binding(get: { gate.showMangaTab }, set: { gate.setShowMangaTab($0) }), icon: "book")
                        DSToggleRow("显示「小说」标签", isOn: Binding(get: { gate.showNovelTab }, set: { gate.setShowNovelTab($0) }), icon: "text.book.closed")
                        DSToggleRow("底部导航显示「音乐」", isOn: Binding(get: { gate.showMusicTab }, set: { gate.setShowMusicTab($0) }), icon: "music.note", subtitle: "在底部导航栏（平板侧栏）显示独立音乐入口")
                    }
                    DSSection("18+ 片源（默认隐藏）", separatorInset: 58) {
                        DSToggleRow(DramaSource.huangguo.label, isOn: Binding(get: { gate.huangguo }, set: { gate.setHuangguo($0) }), icon: "film.stack",
                                    subtitle: "18+ · 视频站 / App 源 / AI 站；开启后出现在「发现」「搜索」和 AI剧")
                            .accessibilityIdentifier("sources.huangguo")
                        ForEach(Sites.all.filter { $0.isAdult && !$0.isDynamic }) { s in siteRow(s) }
                        ForEach(pluginStore.plugins.filter { $0.manifest.adult && [.video, .shortdrama].contains($0.manifest.type) }) { p in
                            DSToggleRow(p.manifest.name, isOn: Binding(get: { p.enabled }, set: { pluginStore.setEnabled(p.id, $0) }),
                                        icon: "puzzlepiece.extension", subtitle: "JS 插件 · " + (p.manifest.httpDomains.first ?? p.manifest.id))
                        }
                        ForEach(cmsStore.sites.filter { $0.adult && $0.origin != "TVBox" && !$0.origin.hasPrefix("TVBox") }) { c in
                            DSToggleRow(c.name, isOn: Binding(get: { c.enabled }, set: { cmsStore.setEnabled(c.key, $0) }),
                                        icon: "server.rack", subtitle: "苹果 CMS 采集站 · " + (URL(string: c.api)?.host ?? c.api))
                        }
                    }
                    DSSection("插件与采集站", separatorInset: 58) {
                        DSLinkRow("插件管理", route: Route.plugins, icon: "puzzlepiece.extension", subtitle: "JS 插件、订阅、苹果 CMS 采集站；成人插件在这里解锁后才显示")
                            .accessibilityIdentifier("sources.plugins")
                    }
                    DSSection("频道与漫画（默认隐藏）",
                              footer: "开启后，该站会出现在「发现」顶部标签和「搜索」结果来源里；关闭后收藏、观看记录仍保留在本机，只是不再显示。所有请求由本机直连站点，部分站点需要先在内置浏览器完成一次 Cloudflare 验证。",
                              separatorInset: 58) {
                        DSToggleRow("成人 Telegram 频道", isOn: Binding(get: { gate.adultChannels }, set: { gate.setAdultChannels($0) }), icon: "paperplane",
                                    subtitle: "预置 @bhg1232 / @NudefilmsTV 及你标记为成人的频道；开启后才出现在「频道」")
                            .accessibilityIdentifier("sources.adultChannels")
                        DSToggleRow("成人漫画源", isOn: Binding(get: { gate.adultManga }, set: { gate.setAdultManga($0) }), icon: "book.closed",
                                    subtitle: "控制成人漫画插件（内置推荐：Manga18fx / Komikindo / 18comic）；插件结果一律过滤未成年人相关标签")
                    }
                    playback
                    privacy
                }
            }
            .padding(.top, 4).padding(.bottom, 32)
        }
        .compatDefaultScrollAnchor(DebugScroll.anchor)
        .dsNavigation("片源管理")
        .dsToast($message, icon: "checkmark.circle.fill")
        .sheet(isPresented: $showHomeOrder) { HomeSourceOrderSheet() }
        .task { if gate.lockEnabled { await gate.unlock() } }
        .task { cacheBytes = await Task.detached { PrefetchStore.shared.totalBytes() }.value }
    }

    private var playback: some View {
        DSSection("播放设置", separatorInset: 58) {
            VStack(alignment: .leading, spacing: 12) {
                DSRowLabel(title: "长视频预缓冲", icon: "arrow.down.circle", subtitle: "播放时从当前位置向后预先下载到本机，弱网下拖动/连续观看更流畅；短剧仍按预加载后续 3–4 集")
                SegmentedTabs(items: BufferSettings.options, selection: $bufferMB, label: { BufferSettings.label($0) }, idPrefix: "settings.prebuffer")
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("settings.prebuffer")
                    .compatOnChange(of: bufferMB) { v in BufferSettings.megabytes = v }
            }
            .dsRow(vertical: 14)
            DSToggleRow("去广告", isOn: Binding(get: { adStrip }, set: { adStrip = $0; HLSAdFilter.enabled = $0 }), icon: "nosign",
                        subtitle: "去掉 HLS 片源里插播的广告片段（adjump、异域名短片段、AES 片源中的明文短片段、重复片段）；不会整段丢弃正片的分段标记")
                .accessibilityIdentifier("settings.adstrip")
            VStack(alignment: .leading, spacing: 12) {
                DSRowLabel(title: "播放内核", icon: "cpu", subtitle: "默认使用系统播放器（原生支持画中画小窗、功耗更低）；如需播放杜比视界/HDR10/DTS/TrueHD 或内置 ASS 字幕，可切换为 MPVKit（暂不支持画中画）")
                SegmentedTabs(items: Array(PlayerEngineKind.allCases), selection: Binding(get: { engineKind }, set: { engineKind = $0; PlayerEngineSettings.preferred = $0 }),
                              label: { $0.label }, idPrefix: "settings.engine")
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("settings.engine")
            }
            .dsRow(vertical: 14)
            DSRow("清空预缓冲缓存", icon: "externaldrive.badge.xmark", subtitle: "上限 2GB", value: ByteCountFormatter.string(fromByteCount: cacheBytes, countStyle: .file)) {
                Task {
                    PrefetchStore.shared.clearAll()
                    cacheBytes = await Task.detached { PrefetchStore.shared.totalBytes() }.value
                    message = "已清空预缓冲缓存"
                }
            }
        }
    }

    private var privacy: some View {
        DSSection("隐私", separatorInset: 58) {
            DSToggleRow("隐藏片源锁（Face ID）", isOn: Binding(get: { gate.lockEnabled }, set: { on in Task { await gate.setLock(on) } }), icon: "faceid", subtitle: "每次启动后需验证才显示 18+ 片源")
            DSRow("清除站点验证 Cookie", icon: "trash") {
                Task { await CFCookies.clearAll(); message = "已清除站点 Cookie" }
            }
            NavigationLink { SiteLogView() } label: {
                DSRowLabel(title: "站点诊断日志", icon: "stethoscope", subtitle: "Cloudflare 验证 / Cookie / UA 记录，可复制给开发者", chevron: true).dsRow()
            }
            .buttonStyle(DSRowPress())
            DSLinkRow("开源许可", route: Route.licenses, icon: "doc.text")
        }
    }

    static func rootDomain(_ s: DramaSource) -> String {
        let parts = s.siteHost.split(separator: ".")
        return parts.count >= 2 ? parts.suffix(2).joined(separator: ".") : s.siteHost
    }

    private func siteRow(_ s: DramaSource) -> some View {
        DSToggleRow(s.label, isOn: Binding(get: { gate.enabled.contains(s) }, set: { gate.set(s, on: $0) }), icon: "globe", subtitle: Self.note(s))
    }

    fileprivate struct HomeSourceOrderSheet: View {
        @Environment(\.dismiss) private var dismiss
        @ObservedObject private var gate = SourceGate.shared
        @ObservedObject private var pluginStore = PluginStore.shared
        @ObservedObject private var cmsStore = CMSStore.shared
        @State private var list: [HomeSource] = HomeSource.all

        var body: some View {
            DSSheet("首页来源排序", subtitle: "调整顶部栏与左右横滑页面的排列顺序", onClose: { dismiss() }) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        HomeSourceOrderRows(sources: list) { updated in
                            list = updated
                            gate.setHomeOrder(updated.map(\.key))
                        }
                        if !gate.homeOrder.isEmpty {
                            Button {
                                DS.tick()
                                withAnimation(DS.select) {
                                    gate.resetHomeOrder()
                                    list = HomeSource.all
                                }
                            } label: {
                                PillButtonPlain(title: "恢复默认顺序", expand: true)
                            }
                            .buttonStyle(PressScale(scale: 0.98))
                        }
                        Text("新启用或新安装的插件会自动排在末尾，也可以随时在首页右上角展开面板里点击「排序」快速调整。")
                            .dsFont(12, relativeTo: .caption).foregroundStyle(STVTheme.tertiary).lineSpacing(3).padding(.horizontal, 4)
                    }
                    .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 28)
                }
            }
            .compatOnChange(of: "\(gate.revision)-\(pluginStore.revision)-\(cmsStore.revision)") { _ in list = HomeSource.all }
            .dsSheetChrome([.medium, .large])
        }
    }

    static func note(_ s: DramaSource) -> String {
        switch s {
        case .jable: "HLS 直连 · 镜像 jable.tv / fs1.app"
        case .missav: "HLS 1080P/720P/480P/360P · 5 个镜像自动探测（.fans/.media 免验证）"
        case .hanime1: "MP4 直链（带签名）· 常需 Cloudflare 验证"
        case .supjav: "多线路（FST/VOE/TV/ST…，不含 LUC）· HLS 经本机代理去伪装头"
        case .eporner: "官方公开 API · HLS + MP4 多画质 · 大陆直连可达"
        case .xvideos: "HLS 自适应 + MP4 · 需代理"
        case .xnxx: "HLS 自适应 + MP4 · 需代理"
        case .pornhub: "HLS 1080P/720P/480P · 需代理"
        case .youporn: "HLS + MP4 多画质 · 需代理"
        case .redtube: "HLS + MP4 多画质 · 需代理"
        case .hqporner: "MP4 多画质（mydaddy.cc 嵌入页）· 需代理"
        default: s.pluginID != nil ? "JS 插件 · " + (PluginStore.snapshot(s.pluginID!)?.manifest.httpDomains.first ?? "") :
                 s.cmsKey != nil ? "苹果 CMS 采集站 · " + (CMSStore.site(s.cmsKey!).flatMap { URL(string: $0.api)?.host } ?? "") : s.siteHost
        }
    }
}

/// AetherEngine (LGPL-3.0 + App Store exception, statically linked) and the libraries it brings:
/// FFmpeg/libzvbi (LGPL, dynamic AetherLib*.framework), dav1d, zimg, libdovi.
struct PlayerCoreLicenses: View {
    @State private var open: String?
    private struct Item: Identifiable {
        let id: String; let title: String; let license: String; let links: [(String, String)]; let note: String; let files: [String]
    }
    private let items: [Item] = [
        Item(id: "hg-current", title: "红果当前播放器签名", license: "Apache-2.0 变换来源 · OpenHongguo / gateway 0613b49",
             links: [("网关源码提交", "https://github.com/my-name-is-alan/get_video_streaming/commit/0613b49")],
             note: "当前 Medusa 封装移植自网关。KS/自定义变换源自 OpenHongguo 05aa580，按原生合成向量核对；具体来源和修改范围见 THIRD_PARTY_NOTICES.md。",
             files: ["HGCurrentPlayer-Apache-2.0"]),
        Item(id: "mpvkit", title: "MPVKit 1.0.0（mpv v0.41.0）", license: "LGPL-2.1-or-later",
             links: [("MPVKit 1.0.0", "https://github.com/mpvkit/MPVKit/tree/1.0.0"), ("mpv.io", "https://mpv.io")],
             note: "iOS 16+ 主力播放内核，内置 libmpv + MoltenVK + libplacebo + libass + libdovi，支持杜比视界、HDR10 与 DTS/TrueHD 软解。",
             files: ["FFmpeg-LGPL-2.1"]),
        Item(id: "ffmpeg", title: "FFmpeg（via MPVKit 1.0.0）", license: "LGPL-2.1-or-later（未启用 GPL）",
             links: [("MPVKit 源码与构建脚本", "https://github.com/mpvkit/MPVKit"), ("ffmpeg.org", "https://ffmpeg.org")],
             note: "随 MPVKit 提供的 LGPL 音视频解复用与解码栈（含 DTS / TrueHD / AC3 / E-AC3 软解）。",
             files: ["FFmpeg-LGPL-2.1"]),
        Item(id: "dav1d", title: "dav1d", license: "BSD-2-Clause · Copyright © VideoLAN and dav1d authors",
             links: [("code.videolan.org/videolan/dav1d", "https://code.videolan.org/videolan/dav1d")], note: "AV1 解码（Libdav1d）。", files: ["dav1d-BSD-2-Clause"]),
        Item(id: "dovi", title: "libdovi（dovi_tool dolby_vision crate）", license: "MIT · Copyright (c) quietvoid and contributors",
             links: [("github.com/quietvoid/dovi_tool", "https://github.com/quietvoid/dovi_tool")],
             note: "Dolby Vision Profile 5/7/8 RPU 解析（MIT）。", files: ["LibDovi-LICENSE"]),
        Item(id: "cpython", title: "CPython 3.13（Python 脚本运行时）", license: "PSF-2.0 · Copyright © Python Software Foundation",
             links: [("python.org", "https://www.python.org"), ("BeeWare Python-Apple-support 3.13-b18", "https://github.com/beeware/Python-Apple-support/releases/tag/3.13-b18")],
             note: "运行 TVBox 的 Python 站点脚本；标准库已精简。内含 Expat、libffi、mpdecimal、bzip2、liblzma、HACL* 等组件，全文见下。部分构建不包含该运行时。",
             files: ["CPython-LICENSE", "CPython-components"]),
    ]
    private func text(_ f: String) -> String {
        Bundle.main.url(forResource: f, withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? f
    }
    var body: some View {
        ForEach(items) { it in
            VStack(alignment: .leading, spacing: 8) {
                Text(it.title).font(.headline).foregroundStyle(STVTheme.text)
                Text(it.license).font(.subheadline).foregroundStyle(STVTheme.secondary)
                ForEach(it.links, id: \.1) { l in
                    Link(l.0, destination: URL(string: l.1)!).font(.subheadline).tint(STVTheme.accent)
                }
                Text(it.note).font(.caption).foregroundStyle(STVTheme.secondary)
                Button { withAnimation(DS.panel) { open = open == it.id ? nil : it.id } } label: { MiniButtonLabel(title: open == it.id ? "收起许可证全文" : "查看许可证全文") }.buttonStyle(PressScale())
                    .accessibilityIdentifier("licenses.\(it.id)")
                if open == it.id {
                    ForEach(it.files, id: \.self) { f in
                        Text(text(f)).font(.system(.caption2, design: .monospaced)).foregroundStyle(STVTheme.secondary).textSelection(.enabled)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .dsCard(18)
        }
    }
}

/// 开源许可 — third-party code this app ports.
struct LicensesView: View {
    @State private var expanded = false
    @State private var qjsExpanded = false
    private var licenseText: String {
        Bundle.main.url(forResource: "UAV-Downloader-LICENSE", withExtension: "txt")
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0"
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("UAV-Downloader").font(.headline).foregroundStyle(STVTheme.text)
                    Text("Copyright © ALOS (github.com/Alos21750)\nLicensed under the Apache License, Version 2.0")
                        .font(.subheadline).foregroundStyle(STVTheme.secondary)
                    Link("github.com/Alos21750/UAV-Downloader", destination: URL(string: "https://github.com/Alos21750/UAV-Downloader")!)
                        .font(.subheadline).tint(STVTheme.accent)
                    Text("i短剧 的 MissAV / Jable / SupJav / Hanime1 解析逻辑移植并改写自该项目（基于 commit de92300（2026-11-10 复查仍为最新），Python → Swift，HTML 解析改为正则）。修改内容：站点列表、详情与播放地址解析的 Swift 重写，以及与 i短剧 播放器的对接。")
                        .font(.caption).foregroundStyle(STVTheme.secondary)
                    Button { withAnimation(DS.panel) { expanded.toggle() } } label: { MiniButtonLabel(title: expanded ? "收起许可证全文" : "查看 Apache-2.0 全文") }.buttonStyle(PressScale())
                    if expanded {
                        Text(licenseText).font(.system(.caption2, design: .monospaced)).foregroundStyle(STVTheme.secondary)
                            .textSelection(.enabled)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .dsCard(18)
                VStack(alignment: .leading, spacing: 8) {
                    Text("yt-dlp").font(.headline).foregroundStyle(STVTheme.text)
                    Text("The Unlicense（公共领域）").font(.subheadline).foregroundStyle(STVTheme.secondary)
                    Link("github.com/yt-dlp/yt-dlp", destination: URL(string: "https://github.com/yt-dlp/yt-dlp")!)
                        .font(.subheadline).tint(STVTheme.accent)
                    Text("Eporner / XVideos / XNXX / Pornhub 的页面结构与接口（Eporner xhr 哈希、html5player、mediaDefinitions）参考了 yt-dlp 的对应 extractor，代码为独立的 Swift 实现。")
                        .font(.caption).foregroundStyle(STVTheme.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .dsCard(18)
                VStack(alignment: .leading, spacing: 8) {
                    Text("TDLibKit / TDLib").font(.headline).foregroundStyle(STVTheme.text)
                    Text("TDLibKit：MIT · TDLib：Boost Software License 1.0").font(.subheadline).foregroundStyle(STVTheme.secondary)
                    Link("github.com/Swiftgram/TDLibKit", destination: URL(string: "https://github.com/Swiftgram/TDLibKit")!).font(.subheadline).tint(STVTheme.accent)
                    Link("github.com/tdlib/td", destination: URL(string: "https://github.com/tdlib/td")!).font(.subheadline).tint(STVTheme.accent)
                    Text("用于频道 TDLib 登录（私有频道、频道内搜索、机器人 /start）。只使用你自己填写的 api_id / api_hash。").font(.caption).foregroundStyle(STVTheme.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .dsCard(18)
                VStack(alignment: .leading, spacing: 8) {
                    Text("QuickJS-ng").font(.headline).foregroundStyle(STVTheme.text)
                    Text("MIT License · Copyright © 2017-2024 Fabrice Bellard, Charlie Gordon; © 2023-2025 Ben Noordhuis, Saúl Ibarra Corretgé").font(.subheadline).foregroundStyle(STVTheme.secondary)
                    Link("github.com/quickjs-ng/quickjs", destination: URL(string: "https://github.com/quickjs-ng/quickjs")!).font(.subheadline).tint(STVTheme.accent)
                    Text("插件运行时（v0.10.1，源码内置于 Packages/QuickJS，未修改；沙箱封装 idj_sandbox.c 为本应用代码）。").font(.caption).foregroundStyle(STVTheme.secondary)
                    Button { withAnimation(DS.panel) { qjsExpanded.toggle() } } label: { MiniButtonLabel(title: qjsExpanded ? "收起许可证全文" : "查看 MIT 全文") }.buttonStyle(PressScale())
                    if qjsExpanded {
                        Text(Bundle.main.url(forResource: "quickjs-ng-LICENSE", withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "MIT")
                            .font(.system(.caption2, design: .monospaced)).foregroundStyle(STVTheme.secondary).textSelection(.enabled)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .dsCard(18)
                PlayerCoreLicenses()
                VStack(alignment: .leading, spacing: 8) {
                    Text("JMComic-Crawler-Python").font(.headline).foregroundStyle(STVTheme.text)
                    Text("MIT License · Copyright © hect0x7").font(.subheadline).foregroundStyle(STVTheme.secondary)
                    Link("github.com/hect0x7/JMComic-Crawler-Python", destination: URL(string: "https://github.com/hect0x7/JMComic-Crawler-Python")!).font(.subheadline).tint(STVTheme.accent)
                    Text("18comic 插件的图片分段还原（get_num / decode）参考该项目公开的算法，代码为独立的 Swift/JS 实现。").font(.caption).foregroundStyle(STVTheme.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .dsCard(18)
                VStack(alignment: .leading, spacing: 8) {
                    Text("MangaDex API · 包子漫画 · Telegram 网页预览").font(.headline).foregroundStyle(STVTheme.text)
                    Text("漫画与频道解析均为本应用独立实现；MangaDex 只请求全年龄/轻度分级内容。").font(.caption).foregroundStyle(STVTheme.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .dsCard(18)
            }.padding(.horizontal, 16).padding(.top, 4).padding(.bottom, 32)
        }
        .compatDefaultScrollAnchor(DebugScroll.anchor)
        .dsNavigation("开源许可")
    }
}

/// 站点诊断日志 — what the app knows per site (UA, cookie names, cf_clearance expiry) + the CF event log.
struct SiteLogView: View {
    @State private var lines: [String] = []
    @State private var summary: [String] = []
    @State private var message: String?

    private static func summarize() -> [String] {
        let jar = HTTPCookieStorage.shared.cookies ?? []
        let f = DateFormatter(); f.dateFormat = "MM-dd HH:mm"
        return Sites.all.map { s in
            let hosts = s.siteHosts
            let cookies = jar.filter { c in hosts.contains { CFCookies.domain(c.domain, matches: $0) } }
            let cf = cookies.filter { $0.name == "cf_clearance" }
                .map { "\($0.domain) 至 \($0.expiresDate.map(f.string) ?? "会话")" }
            return "\(s.label)\n  UA：\(SiteLog.shortUA(s.userAgent))\(SiteUA.learned(s) == nil ? "（默认）" : "（验证页学到）")\n  Cookie：\(cookies.isEmpty ? "无" : Set(cookies.map(\.name)).sorted().joined(separator: ", "))\n  cf_clearance：\(cf.isEmpty ? "无" : cf.joined(separator: "；"))"
        }
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                ForEach(summary, id: \.self) { s in
                    Text(s).font(.caption.monospaced()).foregroundStyle(STVTheme.text)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(14)
                        .dsCard(16)
                }
                Text("事件（最新在下）").dsFont(14, .semibold, relativeTo: .subheadline).foregroundStyle(STVTheme.secondary).padding(.top, 6)
                if lines.isEmpty { Text("暂无记录").font(.caption).foregroundStyle(STVTheme.secondary) }
                ForEach(Array(lines.enumerated()), id: \.offset) { _, l in
                    Text(l).font(.caption2.monospaced()).foregroundStyle(STVTheme.secondary).textSelection(.enabled)
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 32)
        }
        .dsNavigation("站点诊断日志") {
            DSBarButton(systemName: "doc.on.doc") {
                UIPasteboard.general.string = (summary + [""] + lines).joined(separator: "\n"); message = "已复制"
            }
            .accessibilityLabel("复制")
            DSBarButton(systemName: "trash") { SiteLog.clear(); lines = []; message = "已清空" }
                .accessibilityLabel("清空")
        }
        .dsToast($message, icon: "checkmark.circle.fill")
        .refreshable { reload() }
        // Reading the cookie jar for every site once, not on every body.
        .onAppear { reload() }
    }

    private func reload() { lines = SiteLog.all(); summary = Self.summarize() }
}
