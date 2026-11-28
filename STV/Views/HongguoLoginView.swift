import SwiftUI
import UIKit
import WebKit

/// Native QR login sheet for 红果短剧 (mirrors Android's `HongguoLoginScreen.kt`).
/// Drives a desktop-viewport `WKWebView` on `https://hongguoduanju.com`, extracts the QR image via JS bridge,
/// and verifies login directly inside WebKit's same-origin session so `HttpOnly` cookies are never lost.
struct HongguoLoginView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var account = HongguoWebAccount.shared
    @State private var qrImage: UIImage?
    @State private var qrStatus: String = "loading"
    @State private var refreshNonce = 0
    @State private var checkNonce = 0
    @State private var saveNotice: String?
    @State private var loggedInName: String?
    @State private var qrToken: String?
    @State private var qrCheckURL: String?
    @State private var qrCsrfToken: String?
    @State private var qrTraceId: String?
    @State private var webViewAlive = true
    @State private var bgTaskId: UIBackgroundTaskIdentifier = .invalid
    @State private var bgPollTask: Task<Void, Never>?
    @State private var verifyingNow = false

    var body: some View {
        DSSheet("红果短剧扫码登录", subtitle: "使用红果短剧 App 扫描下方二维码，同步本机观看进度") {
            ScrollView {
                VStack(spacing: 20) {
                    // QR card
                    VStack(spacing: 14) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(Color.white)
                                .frame(width: 224, height: 224)

                            if let loggedInName {
                                VStack(spacing: 10) {
                                    Image(systemName: "checkmark.circle.fill")
                                        .font(.system(size: 48))
                                        .foregroundStyle(STVTheme.ok)
                                    Text("已登录：\(loggedInName)")
                                        .font(.headline)
                                        .foregroundStyle(.black)
                                }
                            } else if let qrImage {
                                Image(uiImage: qrImage)
                                    .interpolation(.none)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 200, height: 200)
                                    .opacity(qrStatus == "expired" ? 0.25 : 1)

                                if qrStatus == "expired" {
                                    Button {
                                        resetQrForRefresh()
                                    } label: {
                                        VStack(spacing: 6) {
                                            Image(systemName: "arrow.clockwise.circle.fill")
                                                .font(.system(size: 36))
                                            Text("二维码已过期\n点击刷新")
                                                .font(.caption.weight(.semibold))
                                                .multilineTextAlignment(.center)
                                        }
                                        .foregroundStyle(.black)
                                        .padding(14)
                                        .background(.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                                    }
                                }
                            } else {
                                VStack(spacing: 10) {
                                    DSSpinner(size: 24, tint: .gray)
                                    Text("正在获取二维码…")
                                        .font(.caption)
                                        .foregroundStyle(.gray)
                                }
                            }
                        }

                        Text(statusLabel)
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(qrStatus == "scanned" ? STVTheme.accent : STVTheme.secondary)
                    }
                    .padding(22)
                    .frame(maxWidth: 320)
                    .dsCard(24)

                    HStack(spacing: 12) {
                        Button {
                            resetQrForRefresh()
                        } label: { PillButtonPlain(title: "刷新二维码", icon: "arrow.clockwise") }
                            .buttonStyle(PressScale())

                        if let qrImage {
                            Button {
                                UIImageWriteToSavedPhotosAlbum(qrImage, nil, nil, nil)
                                saveNotice = "已保存二维码到相册，请切到红果短剧 App 相册扫码并确认，切回后自动完成登录"
                            } label: { PillButtonLabel(title: "保存到相册", icon: "square.and.arrow.down", expand: true) }
                                .buttonStyle(PressScale())
                        }
                    }
                    .frame(maxWidth: 320)

                    if let saveNotice {
                        Text(saveNotice)
                            .font(.caption)
                            .foregroundStyle(STVTheme.accent)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 24)
                    }

                    if loggedInName == nil && qrImage != nil {
                        Button {
                            Task {
                                await verifyQrAndLogin(userInitiated: true)
                            }
                        } label: {
                            MiniButtonLabel(title: verifyingNow ? "正在核验登录状态…" : "我已扫码确认，立即检查登录状态")
                        }
                        .disabled(verifyingNow)
                        .buttonStyle(PressScale())
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 20)
                .padding(.bottom, 32)
            }
            .background {
                ZStack(alignment: .topLeading) {
                    // Keep WKWebView mounted in `.background` (never expanding screen layout width) at 1280×900
                    // so desktop login modal renders cleanly and WebKit timers stay active on app foregrounding.
                    HongguoQRWebView(
                        refreshNonce: refreshNonce,
                        checkNonce: checkNonce,
                        onQrUpdate: { dataURL, status in
                            // Once we have a captured token, ignore transient DOM "expired" flips if the dialog
                            // merely hit a background "Network Error" — our direct check_qrconnect poll is authoritative.
                            if status == "expired", qrToken != nil, qrStatus != "expired" {
                                Task { await verifyQrAndLogin(userInitiated: false) }
                                return
                            }
                            qrStatus = status
                            if let img = Self.decodeDataURL(dataURL) {
                                qrImage = img
                            } else if status == "loading" && dataURL.isEmpty {
                                qrImage = nil
                            }
                        },
                        onQrMeta: { token, checkUrl, csrfToken, traceId in
                            if !token.isEmpty { qrToken = token }
                            if !checkUrl.isEmpty { qrCheckURL = checkUrl }
                            if !csrfToken.isEmpty { qrCsrfToken = csrfToken }
                            if !traceId.isEmpty { qrTraceId = traceId }
                            webViewAlive = true
                        },
                        onQrPollStatus: { status in
                            handleServerPollStatus(status)
                        },
                        onWebProcessTerminated: {
                            webViewAlive = false
                            // Keep the existing QR & token alive for native recovery if user already got a QR!
                            if qrToken == nil && qrCheckURL == nil {
                                resetQrForRefresh()
                            } else {
                                Task { await verifyQrAndLogin(userInitiated: false) }
                            }
                        },
                        onLoggedIn: { nickname, avatarUrl in
                            completeLogin(nickname: nickname, avatarUrl: avatarUrl)
                        }
                    )
                    .frame(width: 1280, height: 900)
                    .allowsHitTesting(false)

                    STVTheme.bg.ignoresSafeArea()
                }
                .clipped()
                .ignoresSafeArea()
            }
            .onChange(of: scenePhase) { phase in
                if phase == .active {
                    handleForegroundResume()
                } else if phase == .background {
                    startBackgroundPollingWindow()
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
                handleForegroundResume()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in
                startBackgroundPollingWindow()
            }
            .onDisappear {
                stopBackgroundPollingWindow()
            }
            .task {
                while !Task.isCancelled && loggedInName == nil {
                    try? await Task.sleep(nanoseconds: 2_000_000_000)
                    guard !Task.isCancelled, loggedInName == nil else { break }
                    await verifyQrAndLogin(userInitiated: false)
                }
            }
        }
        // The hidden login page sits behind an opaque ground, so this sheet is full height and not glass.
        .dsSheetChrome([.large])
        .compatPresentationBackground(STVTheme.bg)
    }

    private func resetQrForRefresh() {
        saveNotice = nil
        qrStatus = "loading"
        qrToken = nil
        qrCheckURL = nil
        qrCsrfToken = nil
        qrTraceId = nil
        webViewAlive = true
        refreshNonce += 1
    }

    private func handleServerPollStatus(_ status: String) {
        switch status {
        case "scanned":
            qrStatus = "scanned"
        case "expired":
            qrStatus = "expired"
        case "confirmed":
            saveNotice = "已确认登录，正在同步账号信息…"
            Task {
                for delayNs in [0 as UInt64, 450_000_000, 1_200_000_000] {
                    if delayNs > 0 { try? await Task.sleep(nanoseconds: delayNs) }
                    guard loggedInName == nil else { return }
                    if let p = await account.refreshProfile() {
                        completeLogin(nickname: p.nickname, avatarUrl: p.avatarUrl)
                        return
                    }
                }
            }
        default:
            break
        }
    }

    private func startBackgroundPollingWindow() {
        guard loggedInName == nil, (qrToken != nil || qrCheckURL != nil), bgTaskId == .invalid else { return }
        bgTaskId = UIApplication.shared.beginBackgroundTask(withName: "HongguoQRPoll") {
            Task { @MainActor in
                stopBackgroundPollingWindow()
            }
        }
        bgPollTask?.cancel()
        bgPollTask = Task {
            while !Task.isCancelled && loggedInName == nil && bgTaskId != .invalid {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard !Task.isCancelled, loggedInName == nil else { break }
                let res = await account.pollQRConnectNative(
                    checkURL: qrCheckURL,
                    token: qrToken,
                    csrfToken: qrCsrfToken,
                    traceId: qrTraceId
                )
                switch res {
                case .scanned:
                    qrStatus = "scanned"
                case .expired:
                    qrStatus = "expired"
                    stopBackgroundPollingWindow()
                    return
                case .confirmed:
                    if let p = await account.refreshProfile() {
                        completeLogin(nickname: p.nickname, avatarUrl: p.avatarUrl)
                    }
                    stopBackgroundPollingWindow()
                    return
                case .waiting, .failed:
                    break
                }
            }
        }
    }

    private func stopBackgroundPollingWindow() {
        bgPollTask?.cancel()
        bgPollTask = nil
        if bgTaskId != .invalid {
            let id = bgTaskId
            bgTaskId = .invalid
            UIApplication.shared.endBackgroundTask(id)
        }
    }

    private func handleForegroundResume() {
        stopBackgroundPollingWindow()
        guard loggedInName == nil else { return }
        Task {
            for delayNs in [0 as UInt64, 650_000_000, 1_800_000_000] {
                if delayNs > 0 { try? await Task.sleep(nanoseconds: delayNs) }
                guard !Task.isCancelled, loggedInName == nil else { return }
                await verifyQrAndLogin(userInitiated: false)
            }
        }
    }

    private func verifyQrAndLogin(userInitiated: Bool) async {
        guard loggedInName == nil else { return }
        if userInitiated { verifyingNow = true }
        defer { if userInitiated { verifyingNow = false } }

        // 1) Poke live WebView to re-poll check_qrconnect directly inside same-origin WebKit (revives SDK after
        //    background "Network Error" without losing the current QR token).
        if webViewAlive {
            checkNonce += 1
        }

        // 2) Run native URLSession check_qrconnect replay (works even if WKWebView was suspended or terminated
        //    while user switched to Hongguo App to scan the screenshot).
        var pollResult: HongguoWebAccount.QRPollResult = .failed
        if qrToken != nil || qrCheckURL != nil {
            pollResult = await account.pollQRConnectNative(
                checkURL: qrCheckURL,
                token: qrToken,
                csrfToken: qrCsrfToken,
                traceId: qrTraceId
            )
            switch pollResult {
            case .scanned:
                qrStatus = "scanned"
            case .expired:
                qrStatus = "expired"
            case .confirmed:
                if userInitiated { saveNotice = "已确认授权，正在完成登录…" }
            case .waiting, .failed:
                break
            }
        }

        // 3) Check profile (either native check_qrconnect or WebView's own check_qrconnect may have landed cookies).
        if let p = await account.refreshProfile() {
            completeLogin(nickname: p.nickname, avatarUrl: p.avatarUrl)
            return
        }

        if case .confirmed = pollResult {
            // Cookies just landed in WKWebsiteDataStore; retry profile briefly while WebKit commits.
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard loggedInName == nil else { return }
            if let p = await account.refreshProfile() {
                completeLogin(nickname: p.nickname, avatarUrl: p.avatarUrl)
                return
            }
        }

        if userInitiated && loggedInName == nil {
            switch pollResult {
            case .scanned:
                saveNotice = "已检测到扫码，请在红果短剧 App 内点击「确认登录」后再切回"
            case .expired:
                saveNotice = "二维码已过期，请点击「刷新二维码」重新获取"
            case .confirmed:
                saveNotice = "正在同步红果账号会话，请稍等 1–2 秒后再点一次"
            default:
                saveNotice = "尚未检测到确认状态，请确认已在红果短剧 App 中点击「确认登录」"
            }
        }
    }

    private func completeLogin(nickname: String, avatarUrl: String) {
        let trimmed = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        guard loggedInName == nil, !trimmed.isEmpty else { return }
        stopBackgroundPollingWindow()
        loggedInName = trimmed
        saveNotice = nil
        Task {
            await account.acceptWebViewLogin(nickname: trimmed, avatarUrl: avatarUrl)
            try? await Task.sleep(nanoseconds: 700_000_000)
            dismiss()
        }
    }

    private var statusLabel: String {
        if loggedInName != nil { return "登录成功，正在返回…" }
        switch qrStatus {
        case "scanned": return "已扫描，请在红果短剧 App 上确认登录"
        case "expired": return "二维码已过期，请点击刷新"
        case "ready": return "打开红果短剧 App 扫一扫"
        default: return "正在连接红果短剧网页登录…"
        }
    }

    private static func decodeDataURL(_ s: String) -> UIImage? {
        guard let comma = s.firstIndex(of: ",") else { return nil }
        let b64 = String(s[s.index(after: comma)...])
        guard let data = Data(base64Encoded: b64, options: .ignoreUnknownCharacters) else { return nil }
        return UIImage(data: data)
    }
}

private struct HongguoQRWebView: UIViewRepresentable {
    let refreshNonce: Int
    let checkNonce: Int
    let onQrUpdate: (String, String) -> Void
    let onQrMeta: (String, String, String, String) -> Void
    let onQrPollStatus: (String) -> Void
    let onWebProcessTerminated: () -> Void
    let onLoggedIn: (String, String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(
            onQrUpdate: onQrUpdate,
            onQrMeta: onQrMeta,
            onQrPollStatus: onQrPollStatus,
            onWebProcessTerminated: onWebProcessTerminated,
            onLoggedIn: onLoggedIn
        )
    }

    func makeUIView(context: Context) -> WKWebView {
        let ucc = WKUserContentController()
        ucc.add(context.coordinator, name: "idjLogin")
        ucc.addUserScript(WKUserScript(source: Self.viewportScript, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        ucc.addUserScript(WKUserScript(source: Self.qrHookScript, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        ucc.addUserScript(WKUserScript(source: Self.bridgeJS, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        let cfg = WKWebViewConfiguration()
        cfg.userContentController = ucc
        cfg.websiteDataStore = .default()
        cfg.defaultWebpagePreferences.preferredContentMode = .desktop
        let wv = WKWebView(frame: CGRect(x: 0, y: 0, width: 1280, height: 900), configuration: cfg)
        wv.customUserAgent = HongguoWebAccount.pcUserAgent
        wv.navigationDelegate = context.coordinator
        context.coordinator.webView = wv
        HongguoWebAccount.shared.activeLoginWebView = wv
        if let url = URL(string: HongguoWebAccount.webOrigin) {
            wv.load(URLRequest(url: url))
        }
        return wv
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if context.coordinator.lastNonce != refreshNonce {
            context.coordinator.lastNonce = refreshNonce
            if uiView.url == nil {
                if let url = URL(string: HongguoWebAccount.webOrigin) {
                    uiView.load(URLRequest(url: url))
                }
            } else {
                uiView.evaluateJavaScript("window.__idjActions && window.__idjActions.refreshQr();", completionHandler: nil)
            }
        }
        if context.coordinator.lastCheckNonce != checkNonce {
            context.coordinator.lastCheckNonce = checkNonce
            uiView.evaluateJavaScript("window.__idjActions && window.__idjActions.pollQrNow();", completionHandler: nil)
        }
    }

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        if HongguoWebAccount.shared.activeLoginWebView === uiView {
            HongguoWebAccount.shared.activeLoginWebView = nil
        }
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "idjLogin")
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "idjApi")
        uiView.stopLoading()
    }

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        let onQrUpdate: (String, String) -> Void
        let onQrMeta: (String, String, String, String) -> Void
        let onQrPollStatus: (String) -> Void
        let onWebProcessTerminated: () -> Void
        let onLoggedIn: (String, String) -> Void
        weak var webView: WKWebView?
        var lastNonce = 0
        var lastCheckNonce = 0

        init(
            onQrUpdate: @escaping (String, String) -> Void,
            onQrMeta: @escaping (String, String, String, String) -> Void,
            onQrPollStatus: @escaping (String) -> Void,
            onWebProcessTerminated: @escaping () -> Void,
            onLoggedIn: @escaping (String, String) -> Void
        ) {
            self.onQrUpdate = onQrUpdate
            self.onQrMeta = onQrMeta
            self.onQrPollStatus = onQrPollStatus
            self.onWebProcessTerminated = onWebProcessTerminated
            self.onLoggedIn = onLoggedIn
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == "idjLogin",
                  let dict = message.body as? [String: Any] else { return }
            if (dict["loggedIn"] as? Bool) == true {
                let nickname = dict["nickname"] as? String ?? ""
                let avatarUrl = dict["avatarUrl"] as? String ?? ""
                if !nickname.isEmpty {
                    DispatchQueue.main.async { [onLoggedIn] in
                        onLoggedIn(nickname, avatarUrl)
                    }
                }
                return
            }
            let type = dict["type"] as? String ?? ""
            if type == "qrMeta" {
                let token = dict["token"] as? String ?? ""
                let checkUrl = dict["checkUrl"] as? String ?? ""
                let csrfToken = dict["csrfToken"] as? String ?? ""
                let traceId = dict["traceId"] as? String ?? ""
                DispatchQueue.main.async { [onQrMeta] in
                    onQrMeta(token, checkUrl, csrfToken, traceId)
                }
                return
            }
            if type == "qrPoll" {
                let status = dict["status"] as? String ?? ""
                if !status.isEmpty {
                    DispatchQueue.main.async { [onQrPollStatus] in
                        onQrPollStatus(status)
                    }
                }
                return
            }
            let src = dict["qrSrc"] as? String ?? ""
            let status = dict["qrStatus"] as? String ?? "loading"
            DispatchQueue.main.async { [onQrUpdate] in
                onQrUpdate(src, status)
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            webView.evaluateJavaScript(HongguoQRWebView.bridgeJS, completionHandler: nil)
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            DispatchQueue.main.async { [onWebProcessTerminated] in
                onWebProcessTerminated()
            }
        }
    }

    private static let viewportScript: String = """
    (function() {
      try {
        var obs = new MutationObserver(function() {
          var vp = document.querySelector('meta[name="viewport"]');
          if (vp && vp.getAttribute('content') !== 'width=1280') {
            vp.setAttribute('content', 'width=1280');
          }
        });
        obs.observe(document.documentElement, { childList: true, subtree: true });
      } catch (e) {}
    })();
    """

    /// Intercepts ByteDance passport `get_qrcode` and `check_qrconnect` XHR/fetch traffic at document start so
    /// native Swift captures the live QR `token` and exact signed `check_qrconnect` URL before iOS backgrounding
    /// can kill the SDK's polling timer with a "Network Error".
    private static let qrHookScript: String = """
    (function() {
      if (window.__idjQrHookInstalled) return;
      window.__idjQrHookInstalled = true;
      window.__idjQrMeta = { token: '', checkUrl: '', csrfToken: '', traceId: '' };

      function post(payload) {
        try {
          if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.idjLogin) {
            window.webkit.messageHandlers.idjLogin.postMessage(payload);
          }
        } catch (e) {}
      }

      function emitMeta() {
        var m = window.__idjQrMeta;
        if (m.token || m.checkUrl) {
          post({ type: 'qrMeta', token: m.token, checkUrl: m.checkUrl, csrfToken: m.csrfToken, traceId: m.traceId });
        }
      }

      function absUrl(u) {
        try { return new URL(String(u), location.href).href; } catch (e) { return String(u || ''); }
      }

      function parseTokenFromUrl(u) {
        try { return new URL(u, location.href).searchParams.get('token') || ''; } catch (e) { return ''; }
      }

      function inspectResponse(url, text) {
        try {
          var j = JSON.parse(text);
          var d = j && j.data;
          if (!d || typeof d !== 'object') return;
          if (url.indexOf('/passport/web/get_qrcode') >= 0) {
            if (d.token) {
              window.__idjQrMeta.token = String(d.token);
              window.__idjQrMeta.checkUrl = '';
              emitMeta();
            }
          } else if (url.indexOf('/passport/web/check_qrconnect') >= 0) {
            var st = String(d.status || '').toLowerCase();
            if (st === '2' || st === 'scanned') {
              post({ type: 'qrPoll', status: 'scanned' });
            } else if (st === '3' || st === 'confirmed') {
              post({ type: 'qrPoll', status: 'confirmed' });
              if (d.redirect_url && !window.__idjRedirectHandled) {
                window.__idjRedirectHandled = true;
                fetch(d.redirect_url, { credentials: 'include', redirect: 'follow' }).catch(function(){});
              }
            } else if (st === '5' || st === 'expired' || st === '4' || st === 'refuse' || st === 'refused') {
              post({ type: 'qrPoll', status: 'expired' });
            }
          }
        } catch (e) {}
      }

      var origOpen = XMLHttpRequest.prototype.open;
      var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
      var origSend = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open = function(method, url) {
        this.__idjUrl = absUrl(url);
        this.__idjHeaders = {};
        if (this.__idjUrl.indexOf('/passport/web/check_qrconnect') >= 0) {
          window.__idjQrMeta.checkUrl = this.__idjUrl;
          var tok = parseTokenFromUrl(this.__idjUrl);
          if (tok) window.__idjQrMeta.token = tok;
          emitMeta();
        }
        return origOpen.apply(this, arguments);
      };
      XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
        try {
          var k = String(name || '').toLowerCase();
          if (this.__idjHeaders) this.__idjHeaders[k] = String(value || '');
          if (this.__idjUrl && this.__idjUrl.indexOf('/passport/web/check_qrconnect') >= 0) {
            if (k === 'x-tt-passport-csrf-token' && value) window.__idjQrMeta.csrfToken = String(value);
            if (k === 'x-tt-passport-trace-id' && value) window.__idjQrMeta.traceId = String(value);
          }
        } catch (e) {}
        return origSetHeader.apply(this, arguments);
      };
      XMLHttpRequest.prototype.send = function() {
        var self = this;
        var u = self.__idjUrl || '';
        if (u.indexOf('/passport/web/check_qrconnect') >= 0) emitMeta();
        if (u.indexOf('/passport/web/get_qrcode') >= 0 || u.indexOf('/passport/web/check_qrconnect') >= 0) {
          self.addEventListener('load', function() {
            if (self.status === 200 && typeof self.responseText === 'string') {
              inspectResponse(u, self.responseText);
            }
          });
        }
        return origSend.apply(this, arguments);
      };

      if (window.fetch) {
        var origFetch = window.fetch;
        window.fetch = function(input, init) {
          var u = absUrl(typeof input === 'string' ? input : (input && input.url) || '');
          if (u.indexOf('/passport/web/check_qrconnect') >= 0) {
            window.__idjQrMeta.checkUrl = u;
            var tok = parseTokenFromUrl(u);
            if (tok) window.__idjQrMeta.token = tok;
            emitMeta();
          }
          var p = origFetch.apply(this, arguments);
          if (u.indexOf('/passport/web/get_qrcode') >= 0 || u.indexOf('/passport/web/check_qrconnect') >= 0) {
            p.then(function(res) {
              if (res && res.ok) {
                res.clone().text().then(function(t) { inspectResponse(u, t); }).catch(function(){});
              }
            }).catch(function(){});
          }
          return p;
        };
      }
    })();
    """

    private static let bridgeJS: String = """
    (function() {
      try {
        var st = document.getElementById('idj-login-fix');
        if (!st) {
          st = document.createElement('style');
          st.id = 'idj-login-fix';
          (document.head || document.documentElement).appendChild(st);
        }
        st.textContent = '*,*::before,*::after{animation:none!important;transition:none!important;-webkit-backdrop-filter:none!important;backdrop-filter:none!important;}video{display:none!important;}[role="dialog"],[class*="pc-login-dialog"]{max-height:calc(100% - 32px)!important;opacity:1!important;visibility:visible!important;}';
        var vp = document.querySelector('meta[name="viewport"]');
        if (vp) vp.setAttribute('content', 'width=1280');
        else if (document.head) {
          var m = document.createElement('meta');
          m.name = 'viewport';
          m.content = 'width=1280';
          document.head.appendChild(m);
        }

        function isElVisible(el) {
          if (!el) return false;
          if (el.offsetWidth <= 0 || el.offsetHeight <= 0) return false;
          var cs = getComputedStyle(el);
          return cs.display !== 'none' && cs.visibility !== 'hidden' && cs.opacity !== '0';
        }
        function findDialog() {
          return document.querySelector('[role="dialog"], [class*="pc-login-dialog"]');
        }
        function notifyLogin(nick, avatar) {
          if (window.__idjLoggedInNotified || !nick) return;
          window.__idjLoggedInNotified = true;
          if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.idjLogin) {
            window.webkit.messageHandlers.idjLogin.postMessage({
              loggedIn: true,
              nickname: String(nick).trim(),
              avatarUrl: String(avatar || '').trim()
            });
          }
        }
        function extractProfile(j) {
          if (!j || typeof j !== 'object') return null;
          var cands = [j.profile, j.data && j.data.profile, j.user, j.data && j.data.user];
          for (var i = 0; i < cands.length; i++) {
            var p = cands[i];
            if (p && typeof p === 'object') {
              var nick = (p.nickname || p.name || p.screen_name || p.user_name || '').trim();
              if (nick) {
                return { nickname: nick, avatarUrl: (p.avatarUrl || p.avatar_url || p.avatar || '').trim() };
              }
            }
          }
          return null;
        }
        function checkProfileInWebView() {
          if (window.__idjLoggedInNotified) return;
          try {
            var rd = window._ROUTER_DATA && window._ROUTER_DATA.loaderData;
            if (rd) {
              for (var k in rd) {
                var found = extractProfile(rd[k]);
                if (found) { notifyLogin(found.nickname, found.avatarUrl); return; }
              }
            }
          } catch (e) {}
          fetch('/api/pc/profile?t=' + Date.now(), { credentials: 'include', cache: 'no-store', headers: { 'Accept': 'application/json' } })
            .then(function(r) { return r.ok ? r.json() : null; })
            .then(function(j) {
              var found = extractProfile(j);
              if (found) notifyLogin(found.nickname, found.avatarUrl);
            })
            .catch(function() {});
        }
        function buildPollUrl() {
          var m = window.__idjQrMeta || {};
          if (m.checkUrl) return m.checkUrl;
          if (m.token) {
            return '/passport/web/check_qrconnect/?next=' + encodeURIComponent(location.origin + '/') +
              '&token=' + encodeURIComponent(m.token) +
              '&passport_jssdk_version=2.4.13&passport_jssdk_type=normal&is_from_ttaccountsdk=1&aid=513755&language=zh&account_sdk_source=web';
          }
          return '';
        }
        function pollQrInWebView() {
          if (window.__idjLoggedInNotified) return;
          var url = buildPollUrl();
          if (url && !window.__idjPollBusy) {
            window.__idjPollBusy = true;
            var m = window.__idjQrMeta || {};
            var hdrs = { 'Accept': 'application/json, text/javascript' };
            if (m.csrfToken) hdrs['x-tt-passport-csrf-token'] = m.csrfToken;
            if (m.traceId) hdrs['x-tt-passport-trace-id'] = m.traceId;
            fetch(url, { credentials: 'include', cache: 'no-store', headers: hdrs })
              .then(function(r) { return r.ok ? r.json() : null; })
              .then(function(j) {
                var d = j && j.data;
                if (!d) return;
                var st = String(d.status || '').toLowerCase();
                if (st === '3' || st === 'confirmed') {
                  var follow = d.redirect_url
                    ? fetch(d.redirect_url, { credentials: 'include', redirect: 'follow' }).catch(function(){})
                    : Promise.resolve();
                  follow.then(function() {
                    checkProfileInWebView();
                    setTimeout(checkProfileInWebView, 500);
                  });
                }
              })
              .catch(function() {})
              .then(function() { window.__idjPollBusy = false; });
          }
          checkProfileInWebView();
        }
        window.__idjActions = {
          refreshQr: function() {
            window.__idjRedirectHandled = false;
            if (window.__idjQrMeta) {
              window.__idjQrMeta.token = '';
              window.__idjQrMeta.checkUrl = '';
            }
            var dlg = findDialog();
            if (dlg) {
              var mask = dlg.querySelector('.web-login-scan-code__content__qrcode-wrapper__mask, [class*="qrcode-wrapper__mask"]');
              if (mask && isElVisible(mask)) {
                mask.click();
                var innerBtn = mask.querySelector('button, [role="button"], svg, span');
                if (innerBtn) innerBtn.click();
                return 'clicked';
              }
            }
            location.reload();
            return 'reloaded';
          },
          checkLogin: function() {
            checkProfileInWebView();
          },
          pollQrNow: function() {
            pollQrInWebView();
          }
        };
        if (window.__idjLoginOpenTimer) clearInterval(window.__idjLoginOpenTimer);
        var openTries = 0;
        window.__idjLoginOpenTimer = setInterval(function() {
          openTries++;
          try {
            var dlg = findDialog();
            var rect = dlg ? dlg.getBoundingClientRect() : null;
            if (dlg && rect.width > 0 && rect.height > 0) {
              clearInterval(window.__idjLoginOpenTimer);
              window.__idjLoginOpenTimer = null;
              return;
            }
            if (openTries % 2 === 1) {
              var selectors = ['button', 'a, [role="button"]', 'span, div'];
              var clicked = false;
              for (var s = 0; s < selectors.length && !clicked; s++) {
                var btns = document.querySelectorAll(selectors[s]);
                for (var i = 0; i < btns.length; i++) {
                  if ((btns[i].textContent || '').trim() === '登录') {
                    btns[i].click();
                    clicked = true;
                    break;
                  }
                }
              }
            }
          } catch (e) {}
          if (openTries >= 40) {
            clearInterval(window.__idjLoginOpenTimer);
            window.__idjLoginOpenTimer = null;
          }
        }, 500);
        window.__idjLastQrKey = null;
        if (window.__idjStateObserver) clearInterval(window.__idjStateObserver);
        window.__idjStateObserver = setInterval(function() {
          try {
            var dlg = findDialog();
            var qrSrc = '';
            var qrStatus = 'loading';
            if (dlg) {
              var imgs = dlg.querySelectorAll('img');
              for (var i = 0; i < imgs.length; i++) {
                var im = imgs[i];
                var src = im.getAttribute('src') || im.src || '';
                if (src.indexOf('data:image/') === 0 && (im.naturalWidth >= 100 || !!im.closest('.web-login-scan-code') || (im.alt || '').indexOf('二维码') >= 0)) {
                  qrSrc = src;
                  break;
                }
              }
              if (!qrSrc) {
                var scanImg = dlg.querySelector('.web-login-scan-code img, [alt*="二维码"]');
                if (scanImg) {
                  var ss = scanImg.getAttribute('src') || scanImg.src || '';
                  if (ss.indexOf('data:image/') === 0) qrSrc = ss;
                }
              }
              if (!qrSrc) {
                var canvas = dlg.querySelector('.web-login-scan-code canvas');
                if (canvas && canvas.width >= 100) {
                  try { qrSrc = canvas.toDataURL('image/png'); } catch (ce) {}
                }
              }
              var dlgText = dlg.textContent || '';
              var mask = dlg.querySelector('.web-login-scan-code__content__qrcode-wrapper__mask, [class*="qrcode-wrapper__mask"]');
              if (dlgText.indexOf('已扫描') >= 0 || dlgText.indexOf('请在手机') >= 0) {
                qrStatus = 'scanned';
              } else if (isElVisible(mask) || dlgText.indexOf('点击刷新') >= 0 || dlgText.indexOf('已过期') >= 0 || dlgText.indexOf('二维码失效') >= 0) {
                qrStatus = 'expired';
              } else if (qrSrc) {
                qrStatus = 'ready';
              }
            }
            var qrKey = qrStatus + '|' + qrSrc;
            if (qrKey !== window.__idjLastQrKey) {
              window.__idjLastQrKey = qrKey;
              if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.idjLogin) {
                window.webkit.messageHandlers.idjLogin.postMessage({ qrSrc: qrSrc, qrStatus: qrStatus });
              }
            }
            pollQrInWebView();
          } catch (e) {}
        }, 1500);
        if (!window.__idjVisBound) {
          window.__idjVisBound = true;
          document.addEventListener('visibilitychange', function() {
            if (!document.hidden) pollQrInWebView();
          });
        }
      } catch (e) {}
    })();
    """
}
