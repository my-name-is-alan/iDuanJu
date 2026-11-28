import SwiftUI
import WebKit
import CoreImage.CIFilterBuiltins

// MARK: - 1. Dedicated Music & Sources Settings Page (Mounted in 我的 -> 音乐与音源设置)

struct MusicSettingsView: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.layoutMetrics) private var layout
    @ObservedObject private var store = MusicStore.shared
    @ObservedObject private var arbiter = AppAudioArbiter.shared
    @ObservedObject private var player = MusicPlayerEngine.shared

    @State private var loginSource: MusicSourcePref?
    @State private var showAddNAS = false
    @State private var toast: String?

    private var pad: CGFloat { layout.pagePadding }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Header explainer
                VStack(alignment: .leading, spacing: 6) {
                    Text("单软件无服务端 · 本机直连架构")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(YMTheme.fg(scheme))
                    Text("支持网易云（手机号 / 邮箱 / 扫码 / 桌面网页）、QQ 音乐（QQ 与微信扫码 / 手机号 / 自动续期）、酷狗（手机号 / 酷狗与微信扫码 / 无损直链）、汽水音乐（抖音扫码 / 榜单直连与歌词）以及个人 NAS 直连，所有凭证仅保存在本机。")
                        .font(.system(size: 12.5))
                        .foregroundStyle(YMTheme.secondary(scheme))
                        .lineSpacing(3)
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .ymCard(radius: 20)

                // 1. Account login per online source
                VStack(alignment: .leading, spacing: 10) {
                    sectionTitle("音源账号登录", subtitle: "登录后解锁个人会员音质 / 无损直链")
                    ForEach(store.sourcePrefs.filter { $0.kind.isOnlineDefault }) { pref in
                        let loggedIn = (pref.customCookie?.isEmpty == false)
                        HStack(spacing: 12) {
                            if let avatar = pref.accountAvatarURL, !avatar.isEmpty {
                                YMCoverImage(url: avatar, isCircle: true)
                                    .frame(width: 42, height: 42)
                            } else {
                                Circle()
                                    .fill(loggedIn ? YMTheme.accent(scheme).opacity(0.20) : YMTheme.fill(scheme))
                                    .frame(width: 42, height: 42)
                                    .overlay(
                                        Image(systemName: loggedIn ? "person.crop.circle.badge.checkmark" : "person.crop.circle")
                                            .font(.system(size: 18, weight: .semibold))
                                            .foregroundStyle(loggedIn ? YMTheme.accent(scheme) : YMTheme.secondary(scheme))
                                    )
                            }
                            VStack(alignment: .leading, spacing: 3) {
                                HStack(spacing: 6) {
                                    Text(pref.kind.fullName)
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundStyle(YMTheme.fg(scheme))
                                    if loggedIn {
                                        Text("已登录")
                                            .font(.system(size: 10, weight: .bold))
                                            .foregroundStyle(scheme == .dark ? .black : .white)
                                            .padding(.horizontal, 6).padding(.vertical, 2)
                                            .background(Capsule().fill(YMTheme.accent(scheme)))
                                    }
                                }
                                Text(loggedIn
                                     ? (pref.accountNickname ?? "已保存本机登录凭证")
                                     : sourceLoginSubtitle(pref.kind))
                                    .font(.system(size: 12))
                                    .foregroundStyle(YMTheme.secondary(scheme))
                                    .lineLimit(1)
                            }
                            Spacer()
                            if loggedIn {
                                Button("退出") {
                                    store.logoutSource(pref.id)
                                    showToast("已退出 \(pref.kind.fullName)")
                                }
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(YMTheme.secondary(scheme))
                                .padding(.horizontal, 11).frame(height: 30)
                                .background(Capsule().fill(YMTheme.fill(scheme)))
                                .buttonStyle(.plain)
                            }
                            Button(loggedIn ? "重登" : "登录") {
                                loginSource = pref
                            }
                            .font(.system(size: 12.5, weight: .bold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .padding(.horizontal, 14).frame(height: 30)
                            .background(Capsule().fill(YMTheme.fg(scheme)))
                            .buttonStyle(.plain)
                        }
                        .padding(14)
                        .ymCard(radius: 18)
                    }
                }

                // 1.5 Preferred Audio Quality
                VStack(alignment: .leading, spacing: 10) {
                    sectionTitle("默认播放音质", subtitle: "切换后对正在播放与后续曲目生效，不满足时自动降级")
                    VStack(spacing: 8) {
                        ForEach(MusicAudioQuality.allCases) { q in
                            let selected = store.settings.preferredQuality == q
                            Button {
                                MusicPlayerEngine.shared.switchQuality(to: q)
                                showToast("默认音质已设为 \(q.label)")
                            } label: {
                                HStack(spacing: 12) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        HStack(spacing: 6) {
                                            Text(q.label)
                                                .font(.system(size: 14.5, weight: .bold))
                                                .foregroundStyle(selected ? YMTheme.accent(scheme) : YMTheme.fg(scheme))
                                            if q == .hires || q == .lossless {
                                                Text(q == .hires ? "HR" : "SQ")
                                                    .font(.system(size: 9.5, weight: .black))
                                                    .foregroundStyle(scheme == .dark ? .black : .white)
                                                    .padding(.horizontal, 5)
                                                    .padding(.vertical, 1.5)
                                                    .background(Capsule().fill(YMTheme.accent(scheme)))
                                            }
                                        }
                                        Text(q.detail)
                                            .font(.system(size: 11.5))
                                            .foregroundStyle(YMTheme.secondary(scheme))
                                    }
                                    Spacer()
                                    if selected {
                                        Image(systemName: "checkmark.circle.fill")
                                            .font(.system(size: 18))
                                            .foregroundStyle(YMTheme.accent(scheme))
                                    }
                                }
                                .padding(12)
                                .ymCard(radius: 16)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                // 2. Online source priority & auto-fallback
                VStack(alignment: .leading, spacing: 10) {
                    sectionTitle("在线音源优先级", subtitle: "主源受限时按顺序无感自动换源")
                    ForEach(Array(store.sourcePrefs.enumerated()), id: \.element.id) { idx, pref in
                        HStack(spacing: 12) {
                            Text("\(idx + 1)")
                                .font(.system(size: 13, weight: .black, design: .rounded))
                                .foregroundStyle(YMTheme.accent(scheme))
                                .frame(width: 22)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(pref.kind.fullName)
                                    .font(.system(size: 14.5, weight: .semibold))
                                    .foregroundStyle(YMTheme.fg(scheme))
                                Text(pref.customCookie?.isEmpty == false ? "账号直连（优先无损/高码率）" : "零配置公共直连")
                                    .font(.system(size: 11.5))
                                    .foregroundStyle(YMTheme.secondary(scheme))
                            }
                            Spacer()
                            if idx > 0 {
                                Button {
                                    withAnimation(.snappy) { store.promoteSource(pref.id) }
                                } label: {
                                    Image(systemName: "arrow.up.to.line")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundStyle(YMTheme.secondary(scheme))
                                        .frame(width: 30, height: 30)
                                        .background(Circle().fill(YMTheme.fill(scheme)))
                                }
                                .buttonStyle(.plain)
                            }
                            Toggle("", isOn: Binding(
                                get: { pref.enabled },
                                set: { _ in store.toggleSource(pref.id) }
                            ))
                            .labelsHidden()
                            .toggleStyle(YMToggleStyle())
                        }
                        .padding(12)
                        .ymCard(radius: 16)
                    }

                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("失败自动换源")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(YMTheme.fg(scheme))
                            Text("优先匹配个人 NAS 原声，再跨源补全完整音轨")
                                .font(.system(size: 11.5))
                                .foregroundStyle(YMTheme.secondary(scheme))
                        }
                        Spacer()
                        Toggle("", isOn: $store.settings.autoFallback)
                            .labelsHidden()
                            .toggleStyle(YMToggleStyle())
                    }
                    .padding(14)
                    .ymCard(radius: 16)
                }

                // 3. Personal NAS direct servers
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        sectionTitle("个人曲库直连（NAS）", subtitle: "Navidrome · Emby · 飞牛音乐")
                        Spacer()
                        Button {
                            showAddNAS = true
                        } label: {
                            Label("添加", systemImage: "plus")
                                .font(.system(size: 12.5, weight: .bold))
                                .foregroundStyle(scheme == .dark ? .black : .white)
                                .padding(.horizontal, 12).frame(height: 30)
                                .background(Capsule().fill(YMTheme.fg(scheme)))
                        }
                        .buttonStyle(.plain)
                    }

                    if store.nasConfigs.isEmpty {
                        Button { showAddNAS = true } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "externaldrive.connected.to.line.below")
                                    .font(.system(size: 18))
                                    .foregroundStyle(YMTheme.accent(scheme))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("直连你的 Navidrome / Emby / 飞牛音乐")
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundStyle(YMTheme.fg(scheme))
                                    Text("手机直连家中 FLAC / ALAC 原流，无需中转服务器")
                                        .font(.system(size: 11.5))
                                        .foregroundStyle(YMTheme.secondary(scheme))
                                }
                                Spacer()
                                Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(YMTheme.secondary(scheme))
                            }
                            .padding(14)
                            .ymCard(radius: 16)
                        }
                        .buttonStyle(.plain)
                    } else {
                        ForEach(store.nasConfigs) { cfg in
                            HStack(spacing: 12) {
                                Image(systemName: "server.rack")
                                    .foregroundStyle(YMTheme.accent(scheme))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("\(cfg.name) · \(cfg.kind.label)")
                                        .font(.system(size: 14.5, weight: .bold))
                                        .foregroundStyle(YMTheme.fg(scheme))
                                    Text(cfg.serverURL)
                                        .font(.system(size: 11.5))
                                        .foregroundStyle(YMTheme.secondary(scheme))
                                        .lineLimit(1)
                                }
                                Spacer()
                                Button("同步") {
                                    showToast("正在直连 \(cfg.name)…")
                                    Task {
                                        let tracks = await MusicProviderHub.shared.fetchNASTracks(config: cfg)
                                        store.setNASTracks(tracks)
                                        showToast(tracks.isEmpty ? "连接失败，请检查地址与账号" : "已加载 \(tracks.count) 首曲目")
                                    }
                                }
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(YMTheme.accent(scheme))
                                .buttonStyle(.plain)
                                Button {
                                    store.deleteNAS(id: cfg.id)
                                    showToast("已移除 \(cfg.name)")
                                } label: {
                                    Image(systemName: "trash").font(.system(size: 13)).foregroundStyle(YMTheme.secondary(scheme))
                                }
                                .buttonStyle(.plain)
                            }
                            .padding(14)
                            .ymCard(radius: 16)
                        }
                    }
                }

                // 4. Video / Audiobook interruption resume policy
                VStack(alignment: .leading, spacing: 10) {
                    sectionTitle("看视频 / 听书打断后的续播策略", subtitle: "拔出耳机一律不自动外放")
                    VStack(spacing: 8) {
                        ForEach(MusicResumePolicy.allCases) { p in
                            let selected = arbiter.resumePolicy == p
                            Button {
                                arbiter.resumePolicy = p
                                DS.tick()
                            } label: {
                                HStack(spacing: 12) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(p.label)
                                            .font(.system(size: 14.5, weight: .bold))
                                            .foregroundStyle(selected ? YMTheme.accent(scheme) : YMTheme.fg(scheme))
                                        Text(p.detail)
                                            .font(.system(size: 11.5))
                                            .foregroundStyle(YMTheme.secondary(scheme))
                                            .multilineTextAlignment(.leading)
                                    }
                                    Spacer()
                                    if selected {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundStyle(YMTheme.accent(scheme))
                                    }
                                }
                                .padding(12)
                                .background(
                                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                                        .fill(selected ? YMTheme.accent(scheme).opacity(0.12) : YMTheme.fill(scheme).opacity(0.45))
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(12)
                    .ymCard(radius: 18)
                }

                // 5. Lyrics & Offline LRU Cache
                VStack(alignment: .leading, spacing: 10) {
                    sectionTitle("歌词与本机缓存", subtitle: nil)
                    VStack(spacing: 14) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("LRCLIB 社区逐行歌词补全")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(YMTheme.fg(scheme))
                                Text("外语/独立歌曲缺词时直连 LRCLIB 补齐")
                                    .font(.system(size: 11.5))
                                    .foregroundStyle(YMTheme.secondary(scheme))
                            }
                            Spacer()
                            Toggle("", isOn: $store.settings.lrclibEnabled)
                                .labelsHidden()
                                .toggleStyle(YMToggleStyle())
                        }
                        Divider().background(YMTheme.divider(scheme))
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("离线音频缓存（LRU）")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(YMTheme.fg(scheme))
                                Text("已缓存 \(store.cachedCount) 首 · \(store.formattedCacheSize)（上限 \(store.settings.maxCacheMB) MB）")
                                    .font(.system(size: 11.5))
                                    .foregroundStyle(YMTheme.secondary(scheme))
                            }
                            Spacer()
                            Button("清空") {
                                store.clearAudioCache()
                                showToast("已清空离线音乐缓存")
                            }
                            .font(.system(size: 12.5, weight: .bold))
                            .foregroundStyle(YMTheme.heart)
                            .padding(.horizontal, 12).frame(height: 30)
                            .background(Capsule().fill(YMTheme.heart.opacity(0.14)))
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(14)
                    .ymCard(radius: 18)
                }
            }
            .padding(.horizontal, pad)
            .padding(.top, 8)
            .padding(.bottom, player.shouldShowGlobalMiniBar ? 96 : 36)
        }
        .scrollIndicators(.hidden)
        .background(YMTheme.base(scheme).ignoresSafeArea())
        .dsNavigation("音乐与音源设置")
        .dsToast($toast, icon: "checkmark.circle.fill", bottom: player.shouldShowGlobalMiniBar ? 78 : 18)
        .sheet(item: $loginSource) { src in
            MusicSourceLoginSheet(sourcePref: src) { msg in
                showToast(msg)
            }
        }
        .sheet(isPresented: $showAddNAS) {
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
    }

    private func sectionTitle(_ title: String, subtitle: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(YMTheme.fg(scheme))
            if let subtitle {
                Text(subtitle)
                    .font(.system(size: 11.5))
                    .foregroundStyle(YMTheme.secondary(scheme))
            }
        }
    }

    private func sourceLoginSubtitle(_ kind: MusicSourceKind) -> String {
        switch kind {
        case .netease: return "手机号 / 邮箱 / 扫码 / 桌面网页"
        case .qq: return "QQ与微信扫码 / 手机号 / 桌面网页"
        case .kugou: return "手机号 / 酷狗与微信扫码 / 桌面网页"
        case .qishui: return "抖音扫码 / 桌面网页（免登录也可播）"
        default: return "支持内置网页登录获取凭证"
        }
    }

    private func showToast(_ msg: String) {
        toast = msg
    }
}

// MARK: - 2. Real Serverless Source Login Sheet (Phone SMS/Pwd + Native QR + Anti-H5 Desktop WebView + Manual Cookie)

enum MusicWebLoginGuard {
    static let desktopUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private static let allowedSchemes: Set<String> = ["http", "https", "about", "data", "blob", "javascript"]

    private static let blockedHosts: Set<String> = [
        "a.app.qq.com",
        "apps.apple.com",
        "itunes.apple.com",
        "android.myapp.com",
        "sj.qq.com",
        "durl.kugou.com",
        "dl.kugou.com",
        "down.kugou.com",
        "download.kugou.com",
        "t.kugou.com",
        "m.kugou.com",
        "lf-luna.douyin.com",
        "d.douyin.com",
        "v.douyin.com",
        "zlink.fqnovel.com"
    ]

    private static let blockedPathFragments: [String] = [
        "/m/applink",
        "/applink",
        "/openapp",
        "/appdownload",
        "/download/app",
        "/download_app",
        "/luna/download",
        "/qishui/download",
        "/store/app",
        "/m/down",
        "/r/redirect",
        "/client/download",
        "openinapp"
    ]

    static func desktopLoginURL(for kind: MusicSourceKind) -> URL {
        switch kind {
        case .netease: return URL(string: "https://music.163.com/#/login")!
        case .qq: return URL(string: "https://y.qq.com/")!
        case .kugou: return URL(string: "https://www.kugou.com/")!
        case .qishui: return URL(string: "https://music.douyin.com/")!
        default: return URL(string: "https://music.163.com/#/login")!
        }
    }

    static func shouldBlock(url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), !scheme.isEmpty else { return false }
        if !allowedSchemes.contains(scheme) { return true }
        guard scheme == "http" || scheme == "https" else { return false }
        let host = (url.host ?? "").lowercased()
        if blockedHosts.contains(host) { return true }
        let lowerAbs = url.absoluteString.lowercased()
        if blockedPathFragments.contains(where: { lowerAbs.contains($0) }) { return true }
        return false
    }

    static func matchesLoggedInCookies(_ cookies: [String: String], for kind: MusicSourceKind) -> Bool {
        func has(_ k: String) -> Bool {
            guard let v = cookies[k]?.trimmingCharacters(in: .whitespacesAndNewlines) else { return false }
            return !v.isEmpty
        }
        switch kind {
        case .netease:
            return has("MUSIC_U")
        case .qq:
            return has("qm_keyst") || has("qqmusic_key") || has("musickey") || (has("uin") && has("skey"))
        case .kugou:
            return has("t") || has("token") || has("KugooID")
        case .qishui:
            return has("sessionid") || has("sessionid_ss") || has("sid_tt")
        default:
            return false
        }
    }

    static let antiH5UserScript: String = """
    (function() {
      try {
        Object.defineProperty(navigator, 'platform', { get: function() { return 'MacIntel'; }, configurable: true });
        Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; }, configurable: true });
        if (!navigator.userAgentData) {
          Object.defineProperty(navigator, 'userAgentData', {
            get: function() { return { mobile: false, platform: 'macOS', brands: [{ brand: 'Chromium', version: '131' }] }; },
            configurable: true
          });
        }
      } catch (e) {}
      var isBlockedUrl = function(u) {
        if (!u || typeof u !== 'string') return false;
        var s = u.trim().toLowerCase();
        if (!s || s.startsWith('/') || s.startsWith('#') || s.startsWith('.')) return false;
        if (/^[a-z][a-z0-9+.-]*:/.test(s)) {
          if (!(s.startsWith('http:') || s.startsWith('https:') || s.startsWith('about:') || s.startsWith('data:') || s.startsWith('blob:') || s.startsWith('javascript:'))) return true;
        }
        return s.indexOf('/m/applink') >= 0 || s.indexOf('a.app.qq.com') >= 0 || s.indexOf('apps.apple.com') >= 0 || s.indexOf('/openapp') >= 0;
      };
      try {
        var origOpen = window.open;
        window.open = function(url, name, specs) {
          if (isBlockedUrl(url)) return null;
          if (origOpen) return origOpen.apply(window, arguments);
          return null;
        };
      } catch (e) {}
      try {
        var desc = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'src');
        if (desc && desc.set) {
          Object.defineProperty(HTMLIFrameElement.prototype, 'src', {
            get: desc.get,
            set: function(v) { if (!isBlockedUrl(v)) desc.set.call(this, v); },
            configurable: true
          });
        }
        var origSetAttr = Element.prototype.setAttribute;
        Element.prototype.setAttribute = function(k, v) {
          if (this.tagName === 'IFRAME' && String(k).toLowerCase() === 'src' && isBlockedUrl(v)) return;
          return origSetAttr.call(this, k, v);
        };
      } catch (e) {}
    })();
    """
}

struct MusicSourceLoginSheet: View {
    enum Mode: String, CaseIterable {
        case phone = "手机号"
        case qr = "扫码"
        case web = "桌面网页"
        case manual = "Cookie"
    }

    enum PhoneAuthKind: String, CaseIterable {
        case sms = "短信验证码"
        case password = "手机密码"
        case email = "邮箱密码"
    }

    let sourcePref: MusicSourcePref
    var onLoggedIn: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @ObservedObject private var store = MusicStore.shared

    @State private var mode: Mode
    @State private var phoneAuthKind: PhoneAuthKind = .sms
    @State private var countryCode: String = "86"
    @State private var phoneNumber: String = ""
    @State private var emailAddress: String = ""
    @State private var smsCode: String = ""
    @State private var phonePassword: String = ""
    @State private var smsCooldown: Int = 0
    @State private var smsSending: Bool = false
    @State private var phoneSubmitting: Bool = false
    @State private var phoneMessage: String?
    @State private var phoneNeedFallback: Bool = false
    @State private var qqSecurityURL: URL?
    @State private var smsCountdownTask: Task<Void, Never>?

    @State private var qqQRKind: MusicProviderHub.QQQRKind = .qq
    @State private var qqQRSession: MusicProviderHub.QQQRSession?
    @State private var kugouQRKind: MusicProviderHub.KugouQRKind = .kugou
    @State private var kugouWXSession: MusicProviderHub.KugouWeChatQRSession?
    @State private var qishuiQRSession: MusicProviderHub.QishuiQRSession?
    @State private var neteaseQRSession: MusicProviderHub.NetEaseQRSession?
    @State private var qrKey: String = ""
    @State private var qrImage: UIImage?
    @State private var qrStatus: String = "正在生成二维码…"
    @State private var qrNeedWebFallback: Bool = false
    @State private var qrPollingTask: Task<Void, Never>?

    @State private var webStatus: String = "已启用桌面模式 · 自动拦截跳转外部 App"
    @State private var webStatusAlert: Bool = false
    @State private var webBackTick: Int = 0
    @State private var webReloadTick: Int = 0
    @State private var webHomeTick: Int = 0
    @State private var webCheckCookieTick: Int = 0

    @State private var manualCookie: String = ""

    init(sourcePref: MusicSourcePref, onLoggedIn: @escaping (String) -> Void) {
        self.sourcePref = sourcePref
        self.onLoggedIn = onLoggedIn
        let initial: Mode
        switch sourcePref.kind {
        case .netease, .kugou: initial = .phone
        case .qq, .qishui: initial = .qr
        default: initial = .web
        }
        _mode = State(initialValue: initial)
        _manualCookie = State(initialValue: sourcePref.customCookie ?? "")
    }

    private var availableModes: [Mode] {
        switch sourcePref.kind {
        case .netease: return [.phone, .qr, .web, .manual]
        case .qq: return [.qr, .phone, .web, .manual]
        case .kugou: return [.phone, .qr, .web, .manual]
        case .qishui: return [.qr, .web, .manual]
        default: return [.web, .manual]
        }
    }

    private var webGuideText: String {
        switch sourcePref.kind {
        case .netease:
            return "桌面版网易云登录页，支持手机号验证码 / 密码 / 扫码；登录完成后自动捕获 Cookie。"
        case .qq:
            return "桌面版 QQ 音乐：点击右上角「登录」可用 QQ 账号密码或扫码，已屏蔽跳转 QQ / QQ 音乐 App。"
        case .kugou:
            return "桌面版酷狗：点击右上角「登录」可用手机号验证码或扫码，已屏蔽跳转酷狗 App。"
        case .qishui:
            return "桌面版汽水音乐 / 抖音网页：支持抖音扫码或手机号登录，登录完成后自动捕获凭证。"
        default:
            return "桌面版网页登录：登录完成后将自动捕获本机凭证并返回。"
        }
    }

    var body: some View {
        DSSheet("登录 \(sourcePref.kind.fullName)", subtitle: "凭证仅保存于本机，直连上游零中转", onClose: { dismiss() }) {
            VStack(spacing: 12) {
                Picker("登录方式", selection: $mode) {
                    ForEach(availableModes, id: \.self) { m in
                        Text(m.rawValue).tag(m)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 18)

                switch mode {
                case .phone:
                    neteasePhoneBody
                case .qr:
                    qrLoginBody
                case .web:
                    sourceWebLoginBody
                case .manual:
                    manualCookieBody
                }
            }
            .padding(.top, 4)
        }
        .onAppear {
            if mode == .qr { startQRFlow() }
        }
        .compatOnChange(of: mode) { next in
            if next == .qr { startQRFlow() } else { qrPollingTask?.cancel() }
        }
        .onDisappear {
            qrPollingTask?.cancel()
            smsCountdownTask?.cancel()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            if mode == .qr, (!qrKey.isEmpty || qqQRSession != nil || kugouWXSession != nil || qishuiQRSession != nil || neteaseQRSession != nil) {
                Task { await pollQROnce() }
            }
        }
        .sheet(isPresented: Binding(get: { qqSecurityURL != nil }, set: { if !$0 { qqSecurityURL = nil } })) {
            if let secURL = qqSecurityURL {
                DSSheet("腾讯安全验证", subtitle: "完成滑块验证后关闭此页，再重新点击获取验证码", onClose: { qqSecurityURL = nil }) {
                    MusicSecurityVerifyWebView(url: secURL)
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .padding(12)
                }
            }
        }
    }

    // MARK: Phone + SMS / Password / Email Login (NetEase EAPI + QQ CGI + Kugou Android v7)

    private var neteasePhoneBody: some View {
        let isQQ = sourcePref.kind == .qq
        let isKugou = sourcePref.kind == .kugou
        let isNetEase = sourcePref.kind == .netease
        let isEmailMode = isNetEase && phoneAuthKind == .email
        return ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if isNetEase {
                    HStack(spacing: 8) {
                        ForEach(PhoneAuthKind.allCases, id: \.self) { kind in
                            let selected = phoneAuthKind == kind
                            Button {
                                phoneAuthKind = kind
                                phoneMessage = nil
                                phoneNeedFallback = false
                            } label: {
                                Text(kind.rawValue)
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(selected ? (scheme == .dark ? .black : .white) : YMTheme.secondary(scheme))
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 8)
                                    .background(Capsule().fill(selected ? YMTheme.fg(scheme) : YMTheme.fill(scheme)))
                            }
                            .buttonStyle(.plain)
                        }
                        Spacer()
                    }
                }

                if isEmailMode {
                    TextField("请输入网易云邮箱（如 xxx@163.com）", text: $emailAddress)
                        .keyboardType(.emailAddress)
                        .textContentType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(size: 15.5, weight: .medium))
                        .padding(.horizontal, 14)
                        .frame(height: 46)
                        .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                    SecureField("请输入网易云邮箱密码", text: $phonePassword)
                        .textContentType(.password)
                        .font(.system(size: 15.5))
                        .padding(.horizontal, 14)
                        .frame(height: 46)
                        .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                } else {
                    // Country Code + Phone
                    HStack(spacing: 8) {
                        HStack(spacing: 2) {
                            Text("+")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(YMTheme.secondary(scheme))
                            TextField("86", text: $countryCode)
                                .keyboardType(.numberPad)
                                .font(.system(size: 15, weight: .semibold))
                                .frame(width: 34)
                        }
                        .padding(.horizontal, 12)
                        .frame(height: 46)
                        .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                        TextField("请输入手机号", text: $phoneNumber)
                            .keyboardType(.phonePad)
                            .textContentType(.telephoneNumber)
                            .font(.system(size: 15.5, weight: .medium))
                            .padding(.horizontal, 14)
                            .frame(height: 46)
                            .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }

                    if isQQ || isKugou || phoneAuthKind == .sms {
                        HStack(spacing: 8) {
                            TextField("短信验证码", text: $smsCode)
                                .keyboardType(.numberPad)
                                .textContentType(.oneTimeCode)
                                .font(.system(size: 15.5, weight: .medium))
                                .padding(.horizontal, 14)
                                .frame(height: 46)
                                .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                            Button {
                                Task { await sendSMSCode() }
                            } label: {
                                Group {
                                    if smsSending {
                                        ProgressView().tint(YMTheme.fg(scheme))
                                    } else {
                                        Text(smsCooldown > 0 ? "\(smsCooldown)s 后重发" : "获取验证码")
                                            .font(.system(size: 13.5, weight: .bold))
                                            .foregroundStyle(smsCooldown > 0 ? YMTheme.secondary(scheme) : YMTheme.fg(scheme))
                                    }
                                }
                                .frame(minWidth: 108, minHeight: 46)
                                .padding(.horizontal, 12)
                                .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .disabled(smsSending || smsCooldown > 0 || phoneNumber.filter(\.isNumber).count < 6)
                        }
                    } else {
                        SecureField("请输入网易云账号密码", text: $phonePassword)
                            .textContentType(.password)
                            .font(.system(size: 15.5))
                            .padding(.horizontal, 14)
                            .frame(height: 46)
                            .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                }

                if let msg = phoneMessage, !msg.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(msg)
                            .font(.system(size: 12.5, weight: .medium))
                            .foregroundStyle(phoneNeedFallback ? YMTheme.accent(scheme) : YMTheme.fg(scheme))
                        if phoneNeedFallback {
                            HStack(spacing: 8) {
                                Button {
                                    mode = .qr
                                } label: {
                                    Text(isQQ ? "切换 QQ/微信扫码" : (isKugou ? "切换酷狗/微信扫码" : "切换扫码登录"))
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundStyle(scheme == .dark ? .black : .white)
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .background(Capsule().fill(YMTheme.fg(scheme)))
                                }
                                .buttonStyle(.plain)

                                Button {
                                    mode = .web
                                } label: {
                                    Text("切换桌面网页")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(YMTheme.fg(scheme))
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .background(Capsule().fill(YMTheme.fill(scheme)))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                Button {
                    Task { await submitPhoneLogin() }
                } label: {
                    HStack(spacing: 8) {
                        if phoneSubmitting {
                            ProgressView().tint(scheme == .dark ? .black : .white)
                        }
                        Text(phoneSubmitting ? "正在登录…" : "立即登录")
                            .font(.system(size: 15, weight: .bold))
                    }
                    .foregroundStyle(scheme == .dark ? .black : .white)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(Capsule().fill(YMTheme.fg(scheme)))
                }
                .buttonStyle(.plain)
                .disabled(phoneSubmitting || !canSubmitPhoneLogin)
                .opacity(canSubmitPhoneLogin ? 1 : 0.45)

                Text(
                    isQQ
                    ? "本机直连 QQ 音乐官方短信登录接口（零中转）。若触发腾讯安全滑块，将打开官方验证页由你手动完成；也可直接使用「扫码」支持 QQ / 微信登录。"
                    : (isKugou
                       ? "本机直连酷狗官方 Android v7 加密短信登录接口（零中转）。登录成功后自动启用无损直链与自动续期；也可一键切换「酷狗/微信扫码」。"
                       : "本机直连网易云 EAPI 接口，支持手机号验证码、手机密码与 163 邮箱密码登录，凭证不经过任何中转服务器。若触发官方风控滑块，可一键切换「扫码」或「桌面网页」。")
                )
                    .font(.system(size: 11.5))
                    .foregroundStyle(YMTheme.secondary(scheme))
                    .lineSpacing(3)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
    }

    private var canSubmitPhoneLogin: Bool {
        if sourcePref.kind == .netease && phoneAuthKind == .email {
            let trimmed = emailAddress.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.contains("@") && !phonePassword.isEmpty
        }
        guard phoneNumber.filter(\.isNumber).count >= 6 else { return false }
        if sourcePref.kind == .qq || sourcePref.kind == .kugou {
            return !smsCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        switch phoneAuthKind {
        case .sms: return !smsCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .password, .email: return !phonePassword.isEmpty
        }
    }

    @MainActor
    private func sendSMSCode() async {
        smsSending = true
        phoneMessage = nil
        phoneNeedFallback = false
        if sourcePref.kind == .qq {
            let res = await MusicProviderHub.shared.sendQQSMSCode(phone: phoneNumber, countryCode: countryCode)
            smsSending = false
            switch res {
            case .sent:
                phoneMessage = "验证码已发送，请查收短信"
                startSMSCooldown(60)
            case .captchaRequired(let sec):
                phoneMessage = "QQ 音乐要求先完成安全滑块验证，完成后请重新点击「获取验证码」"
                phoneNeedFallback = true
                if let sec, let url = URL(string: sec) {
                    qqSecurityURL = url
                }
            case .failed(let msg):
                phoneMessage = msg
                phoneNeedFallback = true
            }
            return
        }
        if sourcePref.kind == .kugou {
            let res = await MusicProviderHub.shared.sendKugouSMSCode(phone: phoneNumber, countryCode: countryCode)
            smsSending = false
            switch res {
            case .sent:
                phoneMessage = "酷狗验证码已发送，请查收短信"
                startSMSCooldown(60)
            case .failed(let msg):
                phoneMessage = msg
                phoneNeedFallback = true
            }
            return
        }
        let res = await MusicProviderHub.shared.sendNetEaseSMSCode(phone: phoneNumber, countryCode: countryCode)
        smsSending = false
        switch res {
        case .sent:
            phoneMessage = "验证码已发送，请查收短信"
            startSMSCooldown(60)
        case .failed(let msg):
            phoneMessage = msg
            phoneNeedFallback = msg.contains("限制") || msg.contains("频繁") || msg.contains("风险") || msg.contains("验证")
        }
    }

    @MainActor
    private func startSMSCooldown(_ seconds: Int) {
        smsCountdownTask?.cancel()
        smsCooldown = seconds
        smsCountdownTask = Task {
            while !Task.isCancelled && smsCooldown > 0 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard !Task.isCancelled else { return }
                await MainActor.run { smsCooldown = max(0, smsCooldown - 1) }
            }
        }
    }

    @MainActor
    private func submitPhoneLogin() async {
        phoneSubmitting = true
        phoneMessage = nil
        phoneNeedFallback = false
        if sourcePref.kind == .qq {
            let res = await MusicProviderHub.shared.loginQQByPhone(phone: phoneNumber, countryCode: countryCode, authCode: smsCode)
            phoneSubmitting = false
            switch res {
            case .authorized(let cookie, let nick, let avatar):
                await finishLogin(cookie: cookie, prefilledNickname: nick, prefilledAvatar: avatar)
            case .failed(let msg):
                phoneMessage = msg
                phoneNeedFallback = true
            }
            return
        }
        if sourcePref.kind == .kugou {
            let res = await MusicProviderHub.shared.loginKugouByPhone(phone: phoneNumber, countryCode: countryCode, code: smsCode)
            phoneSubmitting = false
            switch res {
            case .authorized(let cookie, let nick, let avatar):
                await finishLogin(cookie: cookie, prefilledNickname: nick, prefilledAvatar: avatar)
            case .requiresWebOrQR(let msg):
                phoneMessage = msg
                phoneNeedFallback = true
            case .failed(let msg):
                phoneMessage = msg
                phoneNeedFallback = true
            }
            return
        }
        let res: MusicProviderHub.NetEasePhoneLoginResult
        if phoneAuthKind == .email {
            res = await MusicProviderHub.shared.loginNetEaseByEmail(email: emailAddress, password: phonePassword)
        } else {
            res = await MusicProviderHub.shared.loginNetEaseByPhone(
                phone: phoneNumber,
                countryCode: countryCode,
                captcha: phoneAuthKind == .sms ? smsCode : nil,
                password: phoneAuthKind == .password ? phonePassword : nil
            )
        }
        phoneSubmitting = false
        switch res {
        case .authorized(let cookie, let nick, let avatar):
            await finishLogin(cookie: cookie, prefilledNickname: nick, prefilledAvatar: avatar)
        case .requiresWebOrQR(let msg):
            phoneMessage = msg
            phoneNeedFallback = true
        case .failed(let msg):
            phoneMessage = msg
            phoneNeedFallback = msg.contains("风险") || msg.contains("验证")
        }
    }

    // MARK: Native QR Login (NetEase + QQ/WeChat + Kugou/WeChat + Qishui/Douyin)

    private var qrLoginBody: some View {
        ScrollView {
            VStack(spacing: 16) {
                if sourcePref.kind == .qq {
                    HStack(spacing: 8) {
                        ForEach(MusicProviderHub.QQQRKind.allCases, id: \.self) { k in
                            let on = qqQRKind == k
                            Button {
                                if qqQRKind != k {
                                    qqQRKind = k
                                    startQRFlow()
                                }
                            } label: {
                                Text(k.rawValue)
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(on ? (scheme == .dark ? .black : .white) : YMTheme.secondary(scheme))
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 8)
                                    .background(Capsule().fill(on ? YMTheme.fg(scheme) : YMTheme.fill(scheme)))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.top, 4)
                } else if sourcePref.kind == .kugou {
                    HStack(spacing: 8) {
                        ForEach(MusicProviderHub.KugouQRKind.allCases, id: \.self) { k in
                            let on = kugouQRKind == k
                            Button {
                                if kugouQRKind != k {
                                    kugouQRKind = k
                                    startQRFlow()
                                }
                            } label: {
                                Text(k.rawValue)
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundStyle(on ? (scheme == .dark ? .black : .white) : YMTheme.secondary(scheme))
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 8)
                                    .background(Capsule().fill(on ? YMTheme.fg(scheme) : YMTheme.fill(scheme)))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.top, 4)
                }

                ZStack {
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(Color.white)
                        .frame(width: 220, height: 220)
                        .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
                    if let qrImage {
                        Image(uiImage: qrImage)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 190, height: 190)
                    } else {
                        ProgressView().tint(.black)
                    }
                }
                .padding(.top, (sourcePref.kind == .qq || sourcePref.kind == .kugou) ? 0 : 8)

                Text(qrStatus)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(qrNeedWebFallback ? YMTheme.accent(scheme) : YMTheme.fg(scheme))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 20)

                if qrNeedWebFallback {
                    Button {
                        mode = .web
                    } label: {
                        Text("切换桌面网页登录")
                            .font(.system(size: 13.5, weight: .bold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .padding(.horizontal, 18)
                            .frame(height: 38)
                            .background(Capsule().fill(YMTheme.accent(scheme)))
                    }
                    .buttonStyle(.plain)
                }

                Text(qrGuideHint)
                    .font(.system(size: 12))
                    .foregroundStyle(YMTheme.secondary(scheme))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 28)

                HStack(spacing: 12) {
                    Button {
                        startQRFlow()
                    } label: {
                        Label("刷新二维码", systemImage: "arrow.clockwise")
                            .font(.system(size: 13.5, weight: .semibold))
                            .foregroundStyle(YMTheme.fg(scheme))
                            .frame(maxWidth: .infinity, minHeight: 42)
                            .background(Capsule().fill(YMTheme.fill(scheme)))
                    }
                    .buttonStyle(.plain)

                    Button {
                        Task { await pollQROnce() }
                    } label: {
                        Text("我已确认登录")
                            .font(.system(size: 13.5, weight: .bold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .frame(maxWidth: .infinity, minHeight: 42)
                            .background(Capsule().fill(YMTheme.fg(scheme)))
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 24)
            }
            .padding(.bottom, 24)
        }
    }

    private var qrGuideHint: String {
        switch sourcePref.kind {
        case .qq:
            return qqQRKind == .qq
                ? "使用手机 QQ 扫一扫上方二维码，或截图后在 QQ 相册扫码，确认后自动换取 QQ 音乐本机凭证。"
                : "使用微信扫一扫上方二维码，或截图后在微信相册扫码，确认后自动换取 QQ 音乐本机凭证。"
        case .kugou:
            return kugouQRKind == .kugou
                ? "使用酷狗音乐 App 扫一扫上方二维码，或截图后用相册扫码，确认后自动保存本机凭证。"
                : "使用微信扫一扫上方二维码，或截图后在微信相册扫码，确认后自动换取酷狗本机凭证。"
        case .qishui:
            return "使用抖音或汽水音乐 App 扫一扫上方二维码，或截图后用相册扫码，确认后自动解锁完整搜索与个人歌单。"
        default:
            return "使用网易云音乐 App 扫一扫上方二维码，或截图后用网易云相册扫码，确认后切回本 App 自动完成登录。"
        }
    }

    // MARK: Anti-H5 Desktop WebView Login

    private var sourceWebLoginBody: some View {
        VStack(spacing: 8) {
            // Anti-App-Jump Status + Compact Web Toolbar
            HStack(spacing: 6) {
                HStack(spacing: 5) {
                    Image(systemName: webStatusAlert ? "shield.lefthalf.filled" : "desktopcomputer")
                        .font(.system(size: 11, weight: .bold))
                    Text(webStatus)
                        .font(.system(size: 11, weight: .semibold))
                        .lineLimit(1)
                }
                .foregroundStyle(webStatusAlert ? YMTheme.accent(scheme) : YMTheme.secondary(scheme))
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Capsule().fill(YMTheme.fill(scheme)))

                Spacer(minLength: 4)

                Button { webBackTick += 1 } label: {
                    Image(systemName: "chevron.backward")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(YMTheme.fg(scheme))
                        .frame(width: 28, height: 28)
                        .background(Circle().fill(YMTheme.fill(scheme)))
                }
                .buttonStyle(.plain)

                Button { webReloadTick += 1 } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(YMTheme.fg(scheme))
                        .frame(width: 28, height: 28)
                        .background(Circle().fill(YMTheme.fill(scheme)))
                }
                .buttonStyle(.plain)

                Button { webHomeTick += 1 } label: {
                    Text("登录页")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(YMTheme.fg(scheme))
                        .padding(.horizontal, 9)
                        .frame(height: 28)
                        .background(Capsule().fill(YMTheme.fill(scheme)))
                }
                .buttonStyle(.plain)

                Button { webCheckCookieTick += 1 } label: {
                    Text("读取凭证")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(scheme == .dark ? .black : .white)
                        .padding(.horizontal, 10)
                        .frame(height: 28)
                        .background(Capsule().fill(YMTheme.fg(scheme)))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 14)

            Text(webGuideText)
                .font(.system(size: 11.5))
                .foregroundStyle(YMTheme.secondary(scheme))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)

            MusicLoginWebView(
                kind: sourcePref.kind,
                backTick: webBackTick,
                reloadTick: webReloadTick,
                homeTick: webHomeTick,
                checkCookieTick: webCheckCookieTick,
                onStatusChange: { msg, isAlert in
                    webStatus = msg
                    webStatusAlert = isAlert
                },
                onCookieCaptured: { cookieString in
                    Task { @MainActor in
                        await finishLogin(cookie: cookieString)
                    }
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .padding(.horizontal, 12)
            .padding(.bottom, 12)
        }
    }

    private var manualCookieBody: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("粘贴 \(sourcePref.kind.fullName) 的 Cookie（例如网易云 `MUSIC_U=...`、QQ 音乐 `uin=...; qm_keyst=...`、酷狗 `t=...; KugooID=...`、汽水音乐 `sessionid=...`）")
                .font(.system(size: 12.5))
                .foregroundStyle(YMTheme.secondary(scheme))
            TextEditor(text: $manualCookie)
                .font(.system(size: 13, design: .monospaced))
                .frame(height: 140)
                .padding(8)
                .background(YMTheme.fill(scheme), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            Button {
                Task { await finishLogin(cookie: manualCookie) }
            } label: {
                Text("保存并验证")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(scheme == .dark ? .black : .white)
                    .frame(maxWidth: .infinity, minHeight: 46)
                    .background(Capsule().fill(YMTheme.fg(scheme)))
            }
            .buttonStyle(.plain)
            .disabled(manualCookie.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Spacer()
        }
        .padding(.horizontal, 20)
    }

    private func startQRFlow() {
        qrPollingTask?.cancel()
        qrKey = ""
        qqQRSession = nil
        kugouWXSession = nil
        qishuiQRSession = nil
        neteaseQRSession = nil
        qrImage = nil
        qrNeedWebFallback = false
        let kind = sourcePref.kind
        let selectedQQKind = qqQRKind
        let selectedKugouKind = kugouQRKind
        qrStatus = switch kind {
        case .qq: selectedQQKind == .qq ? "正在请求 QQ 登录二维码…" : "正在请求微信登录二维码…"
        case .kugou: selectedKugouKind == .kugou ? "正在请求酷狗二维码…" : "正在请求酷狗微信登录二维码…"
        case .qishui: "正在请求抖音 / 汽水扫码二维码…"
        default: "正在请求网易云二维码…"
        }
        qrPollingTask = Task {
            switch kind {
            case .qq:
                guard let session = await MusicProviderHub.shared.createQQQR(kind: selectedQQKind) else {
                    await MainActor.run { qrStatus = "二维码获取失败，请切换另一种扫码或「桌面网页」" }
                    return
                }
                let img = UIImage(data: session.imageData)
                await MainActor.run {
                    qqQRSession = session
                    qrImage = img
                    qrStatus = selectedQQKind == .qq ? "等待手机 QQ 扫码…" : "等待微信扫码…"
                }
            case .kugou:
                if selectedKugouKind == .wechat {
                    guard let session = await MusicProviderHub.shared.createKugouWeChatQR() else {
                        await MainActor.run { qrStatus = "酷狗微信二维码获取失败，请切换「酷狗扫码」或「手机号」" }
                        return
                    }
                    let img = session.imageData.flatMap { UIImage(data: $0) } ?? Self.makeQRImage(from: session.qrText)
                    await MainActor.run {
                        kugouWXSession = session
                        qrImage = img
                        qrStatus = "等待微信扫码…"
                    }
                } else {
                    guard let session = await MusicProviderHub.shared.createKugouQR() else {
                        await MainActor.run { qrStatus = "酷狗二维码获取失败，请切换「手机号」或「桌面网页」" }
                        return
                    }
                    let img = session.imageData.flatMap { UIImage(data: $0) } ?? Self.makeQRImage(from: session.qrText)
                    await MainActor.run {
                        qrKey = session.key
                        qrImage = img
                        qrStatus = "等待酷狗 App 扫码…"
                    }
                }
            case .qishui:
                guard let session = await MusicProviderHub.shared.createQishuiQR() else {
                    await MainActor.run { qrStatus = "汽水二维码获取失败，请切换「桌面网页」或重试" }
                    return
                }
                let img = session.imageData.flatMap { UIImage(data: $0) } ?? (session.qrText.isEmpty ? nil : Self.makeQRImage(from: session.qrText))
                await MainActor.run {
                    qishuiQRSession = session
                    qrImage = img
                    qrStatus = "请用「汽水音乐 App」扫一扫确认登录"
                }
            default:
                guard let session = await MusicProviderHub.shared.createNetEaseQRSession() else {
                    await MainActor.run {
                        qrStatus = "二维码获取失败，请切换「手机号」或「桌面网页」"
                        qrNeedWebFallback = true
                    }
                    return
                }
                let img = Self.makeQRImage(from: session.url)
                await MainActor.run {
                    neteaseQRSession = session
                    qrKey = session.key
                    qrImage = img
                    qrStatus = "等待网易云 App 扫码…"
                }
            }
            let isLongPoll = (kind == .qq && selectedQQKind == .wechat) || (kind == .kugou && selectedKugouKind == .wechat)
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: isLongPoll ? 400_000_000 : 2_200_000_000)
                guard !Task.isCancelled else { return }
                let done = await pollQROnce()
                if done { return }
            }
        }
    }

    @discardableResult
    private func pollQROnce() async -> Bool {
        if sourcePref.kind == .qq {
            guard let session = qqQRSession else { return false }
            let res = await MusicProviderHub.shared.pollQQQR(session: session)
            switch res {
            case .waiting:
                await MainActor.run { qrStatus = session.kind == .qq ? "等待手机 QQ 扫码…" : "等待微信扫码…" }
                return false
            case .scanned:
                await MainActor.run { qrStatus = session.kind == .qq ? "已扫码，请在手机 QQ 点击确认登录" : "已扫码，请在微信上点击确认登录" }
                return false
            case .expired:
                await MainActor.run { qrStatus = "二维码已过期，正在自动刷新…" }
                startQRFlow()
                return true
            case .refused:
                await MainActor.run { qrStatus = "已取消授权，点击「刷新二维码」重试" }
                return true
            case .authorized(let cookie, let nick, let avatar):
                await MainActor.run {
                    Task { await finishLogin(cookie: cookie, prefilledNickname: nick, prefilledAvatar: avatar) }
                }
                return true
            case .failed(let msg):
                await MainActor.run { qrStatus = msg }
                return false
            }
        }
        if sourcePref.kind == .qishui {
            guard let session = qishuiQRSession else { return false }
            let res = await MusicProviderHub.shared.pollQishuiQR(session: session)
            switch res {
            case .waiting:
                await MainActor.run { qrStatus = "请用「汽水音乐 App」扫一扫确认登录" }
                return false
            case .scanned:
                await MainActor.run { qrStatus = "已扫码，请在汽水音乐 App 点击确认登录" }
                return false
            case .expired:
                await MainActor.run { qrStatus = "二维码已过期，正在自动刷新…" }
                startQRFlow()
                return true
            case .refused:
                await MainActor.run { qrStatus = "已取消授权，点击「刷新二维码」重试" }
                return true
            case .authorized(let cookie, let nick, let avatar):
                await MainActor.run {
                    Task { await finishLogin(cookie: cookie, prefilledNickname: nick, prefilledAvatar: avatar) }
                }
                return true
            case .failed(let msg):
                await MainActor.run { qrStatus = msg }
                return false
            }
        }
        if sourcePref.kind == .kugou {
            if let wx = kugouWXSession {
                let res = await MusicProviderHub.shared.pollKugouWeChatQR(uuid: wx.uuid)
                switch res {
                case .waiting:
                    await MainActor.run { qrStatus = "等待微信扫码…" }
                    return false
                case .scanned:
                    await MainActor.run { qrStatus = "已扫码，请在微信点击确认登录" }
                    return false
                case .expired:
                    await MainActor.run { qrStatus = "二维码已过期，正在自动刷新…" }
                    startQRFlow()
                    return true
                case .authorized(let cookie, let nick, let avatar):
                    await MainActor.run {
                        Task { await finishLogin(cookie: cookie, prefilledNickname: nick, prefilledAvatar: avatar) }
                    }
                    return true
                case .failed(let msg):
                    await MainActor.run { qrStatus = msg }
                    return false
                }
            }
            guard !qrKey.isEmpty else { return false }
            let res = await MusicProviderHub.shared.pollKugouQR(key: qrKey)
            switch res {
            case .waiting:
                await MainActor.run { qrStatus = "等待酷狗 App 扫码…" }
                return false
            case .scanned(let nick):
                await MainActor.run { qrStatus = "已扫码\(nick.map { "（\($0)）" } ?? "")，请在酷狗 App 点击确认登录" }
                return false
            case .expired:
                await MainActor.run { qrStatus = "二维码已过期，正在自动刷新…" }
                startQRFlow()
                return true
            case .authorized(let cookie, let nick, let avatar):
                await MainActor.run {
                    Task { await finishLogin(cookie: cookie, prefilledNickname: nick, prefilledAvatar: avatar) }
                }
                return true
            case .failed(let msg):
                await MainActor.run { qrStatus = msg }
                return false
            }
        }
        guard let neSession = neteaseQRSession else { return false }
        let res = await MusicProviderHub.shared.pollNetEaseQR(session: neSession)
        switch res {
        case .waiting:
            await MainActor.run { qrStatus = "等待网易云 App 扫码…" }
            return false
        case .scanned(let nick):
            await MainActor.run { qrStatus = "已扫码\(nick.map { "（\($0)）" } ?? "")，请在手机上点击确认登录" }
            return false
        case .expired:
            await MainActor.run { qrStatus = "二维码已过期，正在自动刷新…" }
            startQRFlow()
            return true
        case .refused(let msg):
            await MainActor.run {
                qrStatus = msg
                qrNeedWebFallback = true
            }
            return true
        case .authorized(let cookie):
            await MainActor.run {
                Task { await finishLogin(cookie: cookie) }
            }
            return true
        case .failed(let msg):
            await MainActor.run { qrStatus = msg }
            return false
        }
    }

    @MainActor
    private func finishLogin(cookie: String, prefilledNickname: String? = nil, prefilledAvatar: String? = nil) async {
        let trimmed = cookie.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        qrPollingTask?.cancel()
        smsCountdownTask?.cancel()
        var nickname = prefilledNickname
        var avatar = prefilledAvatar
        if sourcePref.kind == .netease && (nickname == nil || nickname?.isEmpty == true) {
            if let acc = await MusicProviderHub.shared.fetchNetEaseAccount(cookie: trimmed) {
                nickname = acc.nickname
                avatar = acc.avatarURL
            }
        } else if sourcePref.kind == .qq && (nickname == nil || nickname?.isEmpty == true) {
            if let acc = await MusicProviderHub.shared.fetchQQAccount(cookie: trimmed) {
                nickname = acc.nickname
                avatar = acc.avatarURL
            }
        } else if sourcePref.kind == .qishui && (nickname == nil || nickname?.isEmpty == true) {
            if let acc = await MusicProviderHub.shared.fetchQishuiAccount(cookie: trimmed) {
                nickname = acc.nickname
                avatar = acc.avatarURL
            }
        }
        store.setSourceAccount(sourcePref.id, cookie: trimmed, nickname: nickname, avatarURL: avatar)
        let syncedCount = await store.syncCloudPlaylists()
        let syncNote = syncedCount > 0 ? " · 已同步 \(syncedCount) 个云端歌单" : ""
        onLoggedIn("已登录 \(sourcePref.kind.fullName)\(nickname.map { "：\($0)" } ?? "")\(syncNote)")
        dismiss()
    }

    private static func makeQRImage(from string: String) -> UIImage? {
        let ctx = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        guard let cg = ctx.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

private struct MusicLoginWebView: UIViewRepresentable {
    let kind: MusicSourceKind
    let backTick: Int
    let reloadTick: Int
    let homeTick: Int
    let checkCookieTick: Int
    let onStatusChange: (String, Bool) -> Void
    let onCookieCaptured: (String) -> Void

    private var loginURL: URL { MusicWebLoginGuard.desktopLoginURL(for: kind) }

    func makeCoordinator() -> Coordinator {
        Coordinator(kind: kind, onStatusChange: onStatusChange, onCookieCaptured: onCookieCaptured)
    }

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .nonPersistent()
        cfg.defaultWebpagePreferences.preferredContentMode = .desktop
        cfg.preferences.javaScriptCanOpenWindowsAutomatically = true
        cfg.userContentController.addUserScript(
            WKUserScript(source: MusicWebLoginGuard.antiH5UserScript, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        )
        let web = WKWebView(frame: .zero, configuration: cfg)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        web.customUserAgent = MusicWebLoginGuard.desktopUserAgent
        web.allowsBackForwardNavigationGestures = true
        context.coordinator.webView = web
        web.load(URLRequest(url: loginURL))
        context.coordinator.startPolling(web)
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        let c = context.coordinator
        if c.lastBackTick != backTick {
            c.lastBackTick = backTick
            if uiView.canGoBack { uiView.goBack() }
        }
        if c.lastReloadTick != reloadTick {
            c.lastReloadTick = reloadTick
            uiView.reload()
        }
        if c.lastHomeTick != homeTick {
            c.lastHomeTick = homeTick
            uiView.load(URLRequest(url: loginURL))
        }
        if c.lastCheckCookieTick != checkCookieTick {
            c.lastCheckCookieTick = checkCookieTick
            c.inspectCookies(uiView, manual: true)
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let kind: MusicSourceKind
        let onStatusChange: (String, Bool) -> Void
        let onCookieCaptured: (String) -> Void
        weak var webView: WKWebView?
        var lastBackTick = 0
        var lastReloadTick = 0
        var lastHomeTick = 0
        var lastCheckCookieTick = 0
        private var timer: Timer?
        private var delivered = false

        init(
            kind: MusicSourceKind,
            onStatusChange: @escaping (String, Bool) -> Void,
            onCookieCaptured: @escaping (String) -> Void
        ) {
            self.kind = kind
            self.onStatusChange = onStatusChange
            self.onCookieCaptured = onCookieCaptured
        }

        deinit { timer?.invalidate() }

        func startPolling(_ web: WKWebView) {
            timer?.invalidate()
            timer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self, weak web] _ in
                guard let self, let web, !self.delivered else { return }
                self.inspectCookies(web, manual: false)
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }
            if MusicWebLoginGuard.shouldBlock(url: url) {
                DispatchQueue.main.async {
                    self.onStatusChange("已拦截外部 App 跳转，请在桌面网页内登录", true)
                }
                decisionHandler(.cancel)
                return
            }
            // Prevent iOS Universal Links from kicking out to installed NetEase / QQ / Kugou / WeChat apps:
            // Universal Links only trigger when `.linkActivated` is allowed directly; loading programmatically enters as `.other`.
            if navigationAction.navigationType == .linkActivated,
               (navigationAction.request.httpMethod ?? "GET").uppercased() == "GET",
               navigationAction.request.httpBody == nil,
               (navigationAction.targetFrame == nil || navigationAction.targetFrame?.isMainFrame == true) {
                decisionHandler(.cancel)
                webView.load(navigationAction.request)
                return
            }
            decisionHandler(.allow)
        }

        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            if let url = navigationAction.request.url {
                if MusicWebLoginGuard.shouldBlock(url: url) {
                    DispatchQueue.main.async {
                        self.onStatusChange("已拦截外部 App 跳转，请在桌面网页内登录", true)
                    }
                } else {
                    webView.load(navigationAction.request)
                }
            }
            return nil
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            inspectCookies(webView, manual: false)
        }

        func inspectCookies(_ web: WKWebView, manual: Bool) {
            web.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] cookies in
                guard let self, !self.delivered else { return }
                var map: [String: String] = [:]
                for c in cookies where !c.value.isEmpty {
                    map[c.name] = c.value
                }
                if MusicWebLoginGuard.matchesLoggedInCookies(map, for: self.kind) {
                    self.delivered = true
                    self.timer?.invalidate()
                    let merged = map.map { "\($0.key)=\($0.value)" }.joined(separator: "; ")
                    DispatchQueue.main.async {
                        self.onCookieCaptured(merged)
                    }
                } else if manual {
                    DispatchQueue.main.async {
                        self.onStatusChange("暂未检测到登录凭证，请先在页面内完成登录", false)
                    }
                }
            }
        }
    }
}

private struct MusicSecurityVerifyWebView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .nonPersistent()
        let web = WKWebView(frame: .zero, configuration: cfg)
        web.load(URLRequest(url: url))
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

// MARK: - 3. Artist Detail View (歌手详情 — Yimeng Faithful Push Route)

struct MusicArtistDetailView: View {
    let ref: MusicArtistRef
    @Environment(\.colorScheme) private var scheme
    @Environment(\.layoutMetrics) private var layout
    @ObservedObject private var player = MusicPlayerEngine.shared
    @ObservedObject private var store = MusicStore.shared

    @State private var detail: MusicArtistDetail?
    @State private var loading = true
    @State private var bioExpanded = false

    private var pad: CGFloat { layout.pagePadding }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                // Hero header
                ZStack(alignment: .bottomLeading) {
                    YMCoverImage(url: detail?.coverURL.isEmpty == false ? detail!.coverURL : (ref.coverURL ?? ""), corner: 24)
                        .frame(maxWidth: .infinity)
                        .frame(height: layout.useRail ? 280 : 250)
                        .clipped()
                    LinearGradient(
                        colors: [.clear, .black.opacity(0.32), YMTheme.base(scheme)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    VStack(alignment: .leading, spacing: 4) {
                        Text(detail?.name ?? ref.name)
                            .font(.system(size: 28, weight: .black))
                            .foregroundStyle(YMTheme.fg(scheme))
                        HStack(spacing: 8) {
                            YMQualityBadge(label: ref.source.label, lossless: false)
                            if let d = detail {
                                Text("\(d.hotSongs.count) 首热门 · \(d.albums.count) 张专辑")
                                    .font(.system(size: 12.5))
                                    .foregroundStyle(YMTheme.secondary(scheme))
                            }
                        }
                    }
                    .padding(.horizontal, pad)
                    .padding(.bottom, 10)
                }

                // Action buttons
                HStack(spacing: 12) {
                    Button {
                        if let list = detail?.hotSongs, let first = list.first {
                            player.play(track: first, in: list, openFull: false)
                        }
                    } label: {
                        Label("播放热门", systemImage: "play.fill")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .frame(maxWidth: .infinity, minHeight: 46)
                            .background(Capsule().fill(YMTheme.fg(scheme)))
                    }
                    .buttonStyle(.plain)
                    .disabled(detail?.hotSongs.isEmpty ?? true)

                    Button {
                        if let list = detail?.hotSongs, !list.isEmpty {
                            let shuf = list.shuffled()
                            player.playMode = .shuffle
                            player.play(track: shuf[0], in: shuf, openFull: false)
                        }
                    } label: {
                        Label("随机播放", systemImage: "shuffle")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(YMTheme.fg(scheme))
                            .frame(maxWidth: .infinity, minHeight: 46)
                            .background(Capsule().fill(YMTheme.fill(scheme)))
                    }
                    .buttonStyle(.plain)
                    .disabled(detail?.hotSongs.isEmpty ?? true)
                }
                .padding(.horizontal, pad)

                // Collapsible Artist Bio
                if let bio = detail?.bio, !bio.isEmpty {
                    Button {
                        withAnimation(.snappy) { bioExpanded.toggle() }
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(bio)
                                .font(.system(size: 13.5))
                                .foregroundStyle(YMTheme.fg(scheme).opacity(0.86))
                                .lineLimit(bioExpanded ? nil : 3)
                                .multilineTextAlignment(.leading)
                            Text(bioExpanded ? "收起简介" : "展开全部简介")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(YMTheme.accent(scheme))
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .ymCard(radius: 16)
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, pad)
                }

                if loading {
                    HStack { Spacer(); ProgressView().tint(YMTheme.accent(scheme)); Spacer() }
                        .padding(.vertical, 40)
                } else if let d = detail {
                    // Hot Songs
                    if !d.hotSongs.isEmpty {
                        Text("热门歌曲")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(YMTheme.fg(scheme))
                            .padding(.horizontal, pad)
                        VStack(spacing: 4) {
                            ForEach(Array(d.hotSongs.prefix(20).enumerated()), id: \.element.id) { idx, t in
                                MusicTrackRow(rank: idx + 1, track: t, list: d.hotSongs)
                            }
                        }
                        .padding(.horizontal, pad)
                    }

                    // Albums Grid
                    if !d.albums.isEmpty {
                        Text("专辑作品")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(YMTheme.fg(scheme))
                            .padding(.horizontal, pad)
                        LazyVGrid(
                            columns: [GridItem(.adaptive(minimum: layout.useRail ? 155 : 140), spacing: 14)],
                            spacing: 16
                        ) {
                            ForEach(d.albums) { al in
                                NavigationLink(value: Route.musicAlbum(MusicAlbumRef(
                                    name: al.name,
                                    albumId: al.id,
                                    artist: al.artist,
                                    artistId: al.artistId,
                                    source: al.source,
                                    coverURL: al.coverURL
                                ))) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        YMCoverImage(url: al.coverURL, corner: 14)
                                            .aspectRatio(1, contentMode: .fit)
                                        Text(al.name)
                                            .font(.system(size: 13.5, weight: .bold))
                                            .foregroundStyle(YMTheme.fg(scheme))
                                            .lineLimit(1)
                                        Text([al.yearText, al.trackCount > 0 ? "\(al.trackCount) 首" : nil].compactMap { $0 }.joined(separator: " · "))
                                            .font(.system(size: 11.5))
                                            .foregroundStyle(YMTheme.secondary(scheme))
                                            .lineLimit(1)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, pad)
                    }
                }
            }
            .padding(.bottom, player.shouldShowGlobalMiniBar ? 96 : 36)
        }
        .scrollIndicators(.hidden)
        .background(YMTheme.base(scheme).ignoresSafeArea())
        .dsNavigation(ref.name)
        .task(id: ref.id) {
            loading = true
            detail = await MusicProviderHub.shared.fetchArtistDetail(ref: ref)
            loading = false
        }
    }
}

// MARK: - 4. Album Detail View (专辑详情 — Yimeng Faithful Push Route)

struct MusicAlbumDetailView: View {
    let ref: MusicAlbumRef
    @Environment(\.colorScheme) private var scheme
    @Environment(\.layoutMetrics) private var layout
    @ObservedObject private var player = MusicPlayerEngine.shared
    @ObservedObject private var store = MusicStore.shared

    @State private var detail: MusicAlbumDetail?
    @State private var loading = true
    @State private var toast: String?

    private var pad: CGFloat { layout.pagePadding }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                // Centered Album Art Hero
                YMCoverImage(url: detail?.coverURL.isEmpty == false ? detail!.coverURL : (ref.coverURL ?? ""), corner: 20)
                    .frame(width: 224, height: 224)
                    .shadow(color: .black.opacity(0.38), radius: 22, y: 12)
                    .padding(.top, 12)

                VStack(spacing: 6) {
                    Text(detail?.name ?? ref.name)
                        .font(.system(size: 22, weight: .black))
                        .foregroundStyle(YMTheme.fg(scheme))
                        .multilineTextAlignment(.center)
                    NavigationLink(value: Route.musicArtist(MusicArtistRef(
                        name: detail?.artist ?? ref.artist,
                        artistId: detail?.artistId ?? ref.artistId,
                        source: ref.source,
                        coverURL: detail?.coverURL ?? ref.coverURL
                    ))) {
                        HStack(spacing: 4) {
                            Text(detail?.artist ?? ref.artist)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(YMTheme.accent(scheme))
                            Image(systemName: "chevron.right")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(YMTheme.accent(scheme))
                        }
                    }
                    .buttonStyle(.plain)

                    let meta = [detail?.releaseDate, detail?.company, detail.map { "\($0.tracks.count) 首歌曲" }]
                        .compactMap { $0 }
                        .filter { !$0.isEmpty }
                        .joined(separator: " · ")
                    if !meta.isEmpty {
                        Text(meta)
                            .font(.system(size: 12))
                            .foregroundStyle(YMTheme.secondary(scheme))
                    }
                }
                .padding(.horizontal, pad)

                // Play All + Shuffle + Clone
                HStack(spacing: 10) {
                    Button {
                        if let list = detail?.tracks, let first = list.first {
                            player.play(track: first, in: list, openFull: false)
                        }
                    } label: {
                        Label("播放专辑", systemImage: "play.fill")
                            .font(.system(size: 14.5, weight: .bold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(Capsule().fill(YMTheme.fg(scheme)))
                    }
                    .buttonStyle(.plain)
                    .disabled(detail?.tracks.isEmpty ?? true)

                    Button {
                        if let d = detail, !d.tracks.isEmpty {
                            let res = store.cloneChartAsPlaylist(name: d.name, tracks: d.tracks)
                            toast = "已收藏专辑为歌单（\(res.added) 首）"
                        }
                    } label: {
                        Label("收进歌单", systemImage: "plus.rectangle.on.folder")
                            .font(.system(size: 13.5, weight: .semibold))
                            .foregroundStyle(YMTheme.fg(scheme))
                            .padding(.horizontal, 16).frame(height: 44)
                            .background(Capsule().fill(YMTheme.fill(scheme)))
                    }
                    .buttonStyle(.plain)
                    .disabled(detail?.tracks.isEmpty ?? true)
                }
                .padding(.horizontal, pad)

                if loading {
                    ProgressView().tint(YMTheme.accent(scheme)).padding(.vertical, 40)
                } else if let d = detail, !d.tracks.isEmpty {
                    VStack(spacing: 4) {
                        ForEach(Array(d.tracks.enumerated()), id: \.element.id) { idx, t in
                            MusicTrackRow(rank: idx + 1, track: t, list: d.tracks)
                        }
                    }
                    .padding(.horizontal, pad)

                    if !d.description.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("专辑介绍")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(YMTheme.fg(scheme))
                            Text(d.description)
                                .font(.system(size: 13))
                                .foregroundStyle(YMTheme.secondary(scheme))
                                .lineSpacing(4)
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .ymCard(radius: 16)
                        .padding(.horizontal, pad)
                    }
                } else {
                    Text("暂未获取到该专辑的曲目列表")
                        .font(.system(size: 14))
                        .foregroundStyle(YMTheme.secondary(scheme))
                        .padding(.top, 36)
                }
            }
            .padding(.bottom, player.shouldShowGlobalMiniBar ? 96 : 36)
        }
        .scrollIndicators(.hidden)
        .background(YMTheme.base(scheme).ignoresSafeArea())
        .dsNavigation(ref.name)
        .dsToast($toast, icon: "checkmark.circle.fill", bottom: player.shouldShowGlobalMiniBar ? 78 : 18)
        .task(id: ref.id) {
            loading = true
            detail = await MusicProviderHub.shared.fetchAlbumDetail(ref: ref)
            loading = false
        }
    }
}

// MARK: - 5. Pushable Track List View (榜单详情 / 歌单详情 — Keeps Global Mini Bar Visible!)

struct MusicTrackListView: View {
    let title: String
    let subtitle: String
    let sourceLabel: String
    let tracks: [MusicTrack]
    let canClone: Bool
    var remoteSource: MusicSourceKind? = nil
    var remotePlaylistId: String? = nil
    var playlistId: String? = nil

    @Environment(\.colorScheme) private var scheme
    @Environment(\.layoutMetrics) private var layout
    @ObservedObject private var player = MusicPlayerEngine.shared
    @ObservedObject private var store = MusicStore.shared
    @State private var loadedTracks: [MusicTrack] = []
    @State private var loadingRemote = false
    @State private var toast: String?

    private var pad: CGFloat { layout.pagePadding }
    private var displayTracks: [MusicTrack] { loadedTracks.isEmpty ? tracks : loadedTracks }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    Button {
                        if let first = displayTracks.first {
                            player.play(track: first, in: displayTracks, openFull: false)
                        }
                    } label: {
                        Label("全部播放（\(displayTracks.count) 首）", systemImage: "play.fill")
                            .font(.system(size: 14.5, weight: .bold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(Capsule().fill(YMTheme.fg(scheme)))
                    }
                    .buttonStyle(.plain)
                    .disabled(displayTracks.isEmpty)

                    if canClone {
                        Button {
                            let res = store.cloneChartAsPlaylist(name: title, tracks: displayTracks)
                            toast = "已克隆到曲库：导入 \(res.added) 首\(res.skipped > 0 ? "，跳过 \(res.skipped) 首重复" : "")"
                        } label: {
                            Label("克隆歌单", systemImage: "doc.on.doc")
                                .font(.system(size: 13.5, weight: .semibold))
                                .foregroundStyle(YMTheme.fg(scheme))
                                .padding(.horizontal, 16).frame(height: 44)
                                .background(Capsule().fill(YMTheme.fill(scheme)))
                        }
                        .buttonStyle(.plain)
                        .disabled(displayTracks.isEmpty)
                    }
                }
                .padding(.horizontal, pad)
                .padding(.top, 6)

                Text("\(sourceLabel) · \(subtitle)")
                    .font(.system(size: 12))
                    .foregroundStyle(YMTheme.secondary(scheme))
                    .padding(.horizontal, pad)

                if loadingRemote && displayTracks.isEmpty {
                    HStack(spacing: 10) {
                        ProgressView().tint(YMTheme.accent(scheme))
                        Text("正在直连拉取歌单曲目…")
                            .font(.system(size: 13.5))
                            .foregroundStyle(YMTheme.secondary(scheme))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 48)
                } else if displayTracks.isEmpty {
                    Text("该歌单暂未拉取到可播放曲目，可下拉刷新重试")
                        .font(.system(size: 13.5))
                        .foregroundStyle(YMTheme.secondary(scheme))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 48)
                } else {
                    VStack(spacing: 4) {
                        ForEach(Array(displayTracks.enumerated()), id: \.element.id) { idx, t in
                            MusicTrackRow(rank: idx + 1, track: t, list: displayTracks)
                        }
                    }
                    .padding(.horizontal, pad)
                }
            }
            .padding(.bottom, player.shouldShowGlobalMiniBar ? 96 : 36)
        }
        .scrollIndicators(.hidden)
        .background(YMTheme.base(scheme).ignoresSafeArea())
        .dsNavigation(title)
        .dsToast($toast, icon: "checkmark.circle.fill", bottom: player.shouldShowGlobalMiniBar ? 78 : 18)
        .task {
            await loadRemoteIfNeeded(force: false)
        }
        .refreshable {
            await loadRemoteIfNeeded(force: true)
        }
    }

    private func loadRemoteIfNeeded(force: Bool) async {
        guard let src = remoteSource, let rid = remotePlaylistId, !rid.isEmpty else { return }
        if !force && !displayTracks.isEmpty { return }
        loadingRemote = true
        defer { loadingRemote = false }
        let fetched = await MusicProviderHub.shared.fetchRemotePlaylistTracks(
            source: src,
            remoteId: rid,
            cookie: store.cookie(for: src)
        )
        if !fetched.isEmpty {
            loadedTracks = fetched
            if let pid = playlistId, pid.hasPrefix("cloud:") {
                store.updateCloudPlaylistTracks(playlistId: pid, tracks: fetched)
            }
        }
    }
}

// MARK: - 5. Dedicated Daily Recommendations Detail View (每日推荐详情页)

struct MusicDailyRecView: View {
    let initialTracks: [MusicTrack]
    @Environment(\.colorScheme) private var scheme
    @Environment(\.layoutMetrics) private var layout
    @ObservedObject private var player = MusicPlayerEngine.shared
    @ObservedObject private var store = MusicStore.shared
    @State private var tracks: [MusicTrack] = []
    @State private var subtitleText: String = "正在拉取今日专属推荐…"
    @State private var isPersonalized: Bool = false
    @State private var loading = false
    @State private var toast: String?

    private var pad: CGFloat { layout.pagePadding }

    var body: some View {
        let now = Date()
        let cal = Calendar.current
        let dayNum = String(format: "%02d", cal.component(.day, from: now))
        let monthNum = cal.component(.month, from: now)
        let wkNames = ["", "周日", "周一", "周二", "周三", "周四", "周五", "周六"]
        let wk = wkNames[cal.component(.weekday, from: now)]

        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Editorial Daily Banner
                HStack(spacing: 14) {
                    VStack(spacing: 2) {
                        Text("\(monthNum)月")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(YMTheme.accent(scheme))
                        Text(dayNum)
                            .font(.system(size: 36, weight: .black, design: .rounded))
                            .foregroundStyle(YMTheme.fg(scheme))
                        Text(wk)
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(YMTheme.secondary(scheme))
                    }
                    .frame(width: 72, height: 84)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(YMTheme.fill(scheme)))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(YMTheme.accent(scheme).opacity(0.35), lineWidth: 1))

                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 6) {
                            Text("每日推荐")
                                .font(.system(size: 22, weight: .heavy))
                                .foregroundStyle(YMTheme.fg(scheme))
                            if isPersonalized {
                                Text("私人日推")
                                    .font(.system(size: 10.5, weight: .black))
                                    .foregroundStyle(scheme == .dark ? .black : .white)
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 2.5)
                                    .background(Capsule().fill(YMTheme.accent(scheme)))
                            }
                        }
                        Text(subtitleText)
                            .font(.system(size: 13))
                            .foregroundStyle(YMTheme.secondary(scheme))
                        Text("点任意单曲可听，右侧 ··· 可看歌手与专辑")
                            .font(.system(size: 11.5))
                            .foregroundStyle(YMTheme.secondary(scheme).opacity(0.75))
                    }
                    Spacer(minLength: 0)
                }
                .padding(16)
                .ymCard(radius: 20)
                .padding(.horizontal, pad)
                .padding(.top, 6)

                // Action Pills
                HStack(spacing: 10) {
                    Button {
                        if let first = tracks.first {
                            player.play(track: first, in: tracks, openFull: false)
                        }
                    } label: {
                        Label("全部播放", systemImage: "play.fill")
                            .font(.system(size: 14.5, weight: .bold))
                            .foregroundStyle(scheme == .dark ? .black : .white)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(Capsule().fill(YMTheme.fg(scheme)))
                    }
                    .buttonStyle(.plain)
                    .disabled(tracks.isEmpty)

                    Button {
                        withAnimation(DS.select) { tracks.shuffle() }
                        toast = "已重排今日推荐顺序"
                    } label: {
                        Label("换一批", systemImage: "shuffle")
                            .font(.system(size: 13.5, weight: .semibold))
                            .foregroundStyle(YMTheme.fg(scheme))
                            .padding(.horizontal, 14).frame(height: 44)
                            .background(Capsule().fill(YMTheme.fill(scheme)))
                    }
                    .buttonStyle(.plain)
                    .disabled(tracks.isEmpty)

                    Button {
                        let name = "每日推荐 \(monthNum)/\(dayNum)"
                        let res = store.cloneChartAsPlaylist(name: name, tracks: tracks)
                        toast = "已存为歌单：导入 \(res.added) 首"
                    } label: {
                        Image(systemName: "plus.rectangle.on.folder")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(YMTheme.fg(scheme))
                            .frame(width: 44, height: 44)
                            .background(Circle().fill(YMTheme.fill(scheme)))
                    }
                    .buttonStyle(.plain)
                    .disabled(tracks.isEmpty)
                    .accessibilityLabel("存为本地歌单")
                }
                .padding(.horizontal, pad)

                if loading && tracks.isEmpty {
                    HStack {
                        Spacer()
                        ProgressView().tint(YMTheme.accent(scheme))
                        Spacer()
                    }
                    .padding(.vertical, 48)
                } else if tracks.isEmpty {
                    Text("暂未拉取到推荐曲目，请检查网络连接后重试")
                        .font(.system(size: 13.5))
                        .foregroundStyle(YMTheme.secondary(scheme))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 44)
                } else {
                    VStack(spacing: 4) {
                        ForEach(Array(tracks.enumerated()), id: \.element.id) { idx, t in
                            MusicTrackRow(rank: idx + 1, track: t, list: tracks)
                        }
                    }
                    .padding(.horizontal, pad)
                }
            }
            .padding(.bottom, player.shouldShowGlobalMiniBar ? 96 : 36)
        }
        .scrollIndicators(.hidden)
        .background(YMTheme.base(scheme).ignoresSafeArea())
        .dsNavigation("每日推荐")
        .dsToast($toast, icon: "checkmark.circle.fill", bottom: player.shouldShowGlobalMiniBar ? 78 : 18)
        .task {
            if tracks.isEmpty && !initialTracks.isEmpty {
                tracks = initialTracks
            }
            loading = true
            var cookies: [MusicSourceKind: String] = [:]
            for p in store.sourcePrefs { if let c = p.customCookie { cookies[p.kind] = c } }
            let res = await MusicProviderHub.shared.fetchDailyRecommendations(
                cookies: cookies,
                enabledSources: store.enabledOnlineSources,
                forceRefresh: false
            )
            if !res.tracks.isEmpty {
                tracks = res.tracks
                isPersonalized = res.isPersonalized
                subtitleText = res.subtitle
            } else if !tracks.isEmpty {
                subtitleText = "多源今日混排精选 · 共 \(tracks.count) 首"
            }
            loading = false
        }
    }
}

