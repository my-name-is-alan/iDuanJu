import Foundation
import WebKit

/// Hongguo web account session (QR login via https://hongguoduanju.com) + cloud watch-history sync.
/// Uses a persistent same-origin `WKWebView` (`WKWebsiteDataStore.default()`) for `/api/pc/*` requests so
/// `HttpOnly` / `SameSite` session cookies set during QR login are always sent natively by WebKit without
/// cookie-jar flush races or `URLSession` mismatches.
@MainActor
final class HongguoWebAccount: ObservableObject {
    struct Profile: Codable, Equatable {
        let nickname: String
        let avatarUrl: String
    }

    enum QRPollResult: Equatable {
        case waiting
        case scanned
        case confirmed(redirectURL: String?)
        case expired
        case failed
    }

    static let shared = HongguoWebAccount()
    static let webOrigin = "https://hongguoduanju.com"
    static let pcUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private static let profileKey = "stv.hg.web.profile"
    private static let cookieKey = "stv.hg.web.cookie"
    private static let sessionTagTTL: TimeInterval = 15 * 60
    private static let reportThrottle: TimeInterval = 30
    /// Grace window after WebView confirms login during which transient empty checks must never wipe `profile`.
    private static let loginGraceWindow: TimeInterval = 90

    @Published private(set) var profile: Profile?
    @Published private(set) var syncing = false
    @Published private(set) var lastSyncNote: String?

    var isLoggedIn: Bool { profile != nil }

    /// Live QR-login WebView while `HongguoLoginView` is presented, preferred over the background API WebView.
    weak var activeLoginWebView: WKWebView?

    private var cachedCookieHeader: String?
    private var lastConfirmedLoginAt: Date?
    private var sessionTags: [String: (tag: String, at: Date)] = [:]
    private var lastReportedAt: [String: Date] = [:]

    private var apiWebView: WKWebView?
    private var apiWebViewReady = false
    private var apiReadyWaiters: [CheckedContinuation<Bool, Never>] = []
    private var pendingCalls: [String: CheckedContinuation<(status: Int, text: String)?, Never>] = [:]
    private lazy var apiBridge = APIBridge(owner: self)

    private let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 15
        c.httpShouldSetCookies = false
        c.httpCookieAcceptPolicy = .never
        return URLSession(configuration: c)
    }()

    private init() {
        cachedCookieHeader = UserDefaults.standard.string(forKey: Self.cookieKey)
        if let data = UserDefaults.standard.data(forKey: Self.profileKey),
           let saved = try? JSONDecoder().decode(Profile.self, from: data),
           !saved.nickname.isEmpty {
            profile = saved
        }
    }

    // MARK: - Same-origin WebKit RPC

    private final class APIBridge: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        weak var owner: HongguoWebAccount?
        init(owner: HongguoWebAccount) { self.owner = owner }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == "idjApi",
                  let dict = message.body as? [String: Any],
                  let reqId = dict["reqId"] as? String else { return }
            let status = dict["status"] as? Int ?? 0
            let text = dict["text"] as? String ?? ""
            Task { @MainActor [weak owner] in
                owner?.completeCall(reqId: reqId, result: status > 0 ? (status, text) : nil)
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            Task { @MainActor [weak owner] in
                owner?.markApiWebViewReady(true)
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            Task { @MainActor [weak owner] in
                owner?.markApiWebViewReady(false)
            }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            Task { @MainActor [weak owner] in
                owner?.markApiWebViewReady(false)
            }
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            Task { @MainActor [weak owner] in
                owner?.handleApiWebViewTerminated(webView)
            }
        }
    }

    private func handleApiWebViewTerminated(_ webView: WKWebView) {
        if apiWebView === webView {
            apiWebView = nil
            apiWebViewReady = false
            markApiWebViewReady(false)
        }
    }

    private func markApiWebViewReady(_ ok: Bool) {
        apiWebViewReady = ok
        let waiters = apiReadyWaiters
        apiReadyWaiters.removeAll()
        for w in waiters { w.resume(returning: ok) }
    }

    private func completeCall(reqId: String, result: (status: Int, text: String)?) {
        guard let cont = pendingCalls.removeValue(forKey: reqId) else { return }
        cont.resume(returning: result)
    }

    /// Ensures a lightweight same-origin `WKWebView` on `https://hongguoduanju.com` is ready to run `fetch(...)`.
    private func ensureApiWebView() async -> WKWebView? {
        if let live = activeLoginWebView, live.url?.host?.lowercased().contains("hongguoduanju.com") == true {
            return live
        }
        if let wv = apiWebView, apiWebViewReady, wv.url?.host?.lowercased().contains("hongguoduanju.com") == true {
            return wv
        }
        if apiWebView == nil {
            let ucc = WKUserContentController()
            ucc.add(apiBridge, name: "idjApi")
            let cfg = WKWebViewConfiguration()
            cfg.userContentController = ucc
            cfg.websiteDataStore = .default()
            cfg.defaultWebpagePreferences.preferredContentMode = .desktop
            let wv = WKWebView(frame: CGRect(x: 0, y: 0, width: 320, height: 240), configuration: cfg)
            wv.customUserAgent = Self.pcUserAgent
            wv.navigationDelegate = apiBridge
            apiWebView = wv
            apiWebViewReady = false
            // Load a tiny same-origin endpoint so cookies + origin are bound without heavy video DOM.
            if let u = URL(string: "\(Self.webOrigin)/robots.txt") {
                wv.load(URLRequest(url: u))
            }
        } else if let wv = apiWebView, !wv.isLoading, !apiWebViewReady {
            if let u = URL(string: "\(Self.webOrigin)/robots.txt") {
                wv.load(URLRequest(url: u))
            }
        }

        let ready = await withTaskGroup(of: Bool.self) { group in
            group.addTask { @MainActor in
                if self.apiWebViewReady { return true }
                return await withCheckedContinuation { cont in
                    self.apiReadyWaiters.append(cont)
                }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                return false
            }
            let first = await group.next() ?? false
            group.cancelAll()
            return first
        }
        return ready ? apiWebView : nil
    }

    /// Executes a same-origin `fetch` inside WebKit so `HttpOnly` cookies are sent natively.
    private func webViewFetch(
        path: String,
        method: String = "GET",
        headers: [String: String] = [:],
        body: String? = nil,
        referrer: String? = nil
    ) async -> (status: Int, text: String)? {
        guard let wv = await ensureApiWebView() else { return nil }
        let isDedicatedApiWV = (wv === apiWebView)
        if !isDedicatedApiWV {
            // For `activeLoginWebView`, ensure `idjApi` handler is attached once.
            wv.configuration.userContentController.removeScriptMessageHandler(forName: "idjApi")
            wv.configuration.userContentController.add(apiBridge, name: "idjApi")
        }

        let reqId = UUID().uuidString
        let hdrData = (try? JSONSerialization.data(withJSONObject: headers)) ?? Data("{}".utf8)
        let hdrJSON = String(data: hdrData, encoding: .utf8) ?? "{}"
        let pathLit = Self.jsStringLiteral(path)
        let methodLit = Self.jsStringLiteral(method)
        let reqIdLit = Self.jsStringLiteral(reqId)
        let bodyExpr = body.map { Self.jsStringLiteral($0) } ?? "undefined"
        let referrerExpr = referrer.map { Self.jsStringLiteral($0) } ?? "undefined"

        let js = """
        (function() {
          var reqId = \(reqIdLit);
          var opts = { method: \(methodLit), credentials: 'include', headers: \(hdrJSON) };
          var bodyVal = \(bodyExpr);
          if (bodyVal !== undefined) opts.body = bodyVal;
          var refVal = \(referrerExpr);
          if (refVal !== undefined) opts.referrer = refVal;
          fetch(\(pathLit), opts)
            .then(function(r) { return r.text().then(function(t) { return { status: r.status, text: t }; }); })
            .then(function(res) {
              window.webkit.messageHandlers.idjApi.postMessage({ reqId: reqId, status: res.status, text: res.text });
            })
            .catch(function() {
              window.webkit.messageHandlers.idjApi.postMessage({ reqId: reqId, status: 0, text: '' });
            });
        })();
        """

        return await withTaskGroup(of: (status: Int, text: String)?.self) { group in
            group.addTask { @MainActor in
                await withCheckedContinuation { cont in
                    self.pendingCalls[reqId] = cont
                    wv.evaluateJavaScript(js) { _, err in
                        if err != nil {
                            Task { @MainActor in self.completeCall(reqId: reqId, result: nil) }
                        }
                    }
                }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 8_000_000_000)
                await MainActor.run { self.completeCall(reqId: reqId, result: nil) }
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    private static func jsStringLiteral(_ s: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: [s]),
              let arr = String(data: data, encoding: .utf8),
              arr.count >= 2 else { return "\"\"" }
        return String(arr.dropFirst().dropLast())
    }

    /// Snapshot cookies for `hongguoduanju.com` from `WKWebsiteDataStore` into `UserDefaults`.
    func syncCookiesFromWebView() async -> String? {
        let all = await WKWebsiteDataStore.default().httpCookieStore.allCookies()
        let matching = all.filter { c in
            let d = c.domain.lowercased()
            return d == "hongguoduanju.com" || d == ".hongguoduanju.com" || d.hasSuffix(".hongguoduanju.com")
        }
        guard !matching.isEmpty else {
            return cachedCookieHeader
        }
        let header = matching.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
        cachedCookieHeader = header
        UserDefaults.standard.set(header, forKey: Self.cookieKey)
        return header
    }

    /// Writes natively captured `Set-Cookie` cookies into WebKit's persistent store and refreshes the cached header.
    func installCookies(_ cookies: [HTTPCookie]) async {
        guard !cookies.isEmpty else { return }
        let store = WKWebsiteDataStore.default().httpCookieStore
        for cookie in cookies {
            await store.setCookie(cookie)
        }
        _ = await syncCookiesFromWebView()
    }

    /// Builds a direct `/passport/web/check_qrconnect/` URL from either a captured URL or QR token.
    nonisolated static func buildCheckQRURL(checkURL: String?, token: String?) -> URL? {
        if let raw = checkURL?.trimmingCharacters(in: .whitespacesAndNewlines),
           !raw.isEmpty,
           let u = URL(string: raw),
           u.host?.lowercased().contains("hongguoduanju.com") == true {
            return u
        }
        guard let tok = token?.trimmingCharacters(in: .whitespacesAndNewlines), !tok.isEmpty else { return nil }
        var comps = URLComponents(string: "\(webOrigin)/passport/web/check_qrconnect/")
        comps?.queryItems = [
            URLQueryItem(name: "next", value: "\(webOrigin)/"),
            URLQueryItem(name: "token", value: tok),
            URLQueryItem(name: "passport_jssdk_version", value: "2.4.13"),
            URLQueryItem(name: "passport_jssdk_type", value: "normal"),
            URLQueryItem(name: "is_from_ttaccountsdk", value: "1"),
            URLQueryItem(name: "aid", value: "513755"),
            URLQueryItem(name: "language", value: "zh"),
            URLQueryItem(name: "account_sdk_source", value: "web")
        ]
        return comps?.url
    }

    /// Parses ByteDance passport `check_qrconnect` JSON into a normalized poll result.
    nonisolated static func parseQRConnectResponse(_ data: Data) -> QRPollResult {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let d = root["data"] as? [String: Any] else {
            return .failed
        }
        let rawStatus: String
        if let s = d["status"] as? String {
            rawStatus = s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        } else if let n = d["status"] as? Int {
            rawStatus = String(n)
        } else {
            rawStatus = ""
        }
        let redirect = (d["redirect_url"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        switch rawStatus {
        case "confirmed", "3":
            return .confirmed(redirectURL: (redirect?.isEmpty == false) ? redirect : nil)
        case "scanned", "2":
            return .scanned
        case "expired", "5", "refuse", "refused", "cancel", "cancelled", "4":
            return .expired
        case "new", "1":
            return .waiting
        default:
            let errCode = d["error_code"] as? Int ?? 0
            if errCode != 0 { return .expired }
            return .failed
        }
    }

    /// Merges newly received `HTTPCookie` values into an existing cookie dictionary keyed by name.
    nonisolated static func mergeCookieMap(_ base: [String: HTTPCookie], incoming: [HTTPCookie]) -> [String: HTTPCookie] {
        var next = base
        for c in incoming {
            if let exp = c.expiresDate, exp <= Date() {
                next.removeValue(forKey: c.name)
            } else {
                next[c.name] = c
            }
        }
        return next
    }

    /// Native `check_qrconnect` poll that works even when ByteDance's in-page JS SDK stopped polling after
    /// iOS background suspension ("Network Error") or when `WKWebView`'s WebContent process was terminated.
    /// Captures all `Set-Cookie` headers (and follows `redirect_url` hops if present) into `WKWebsiteDataStore`.
    func pollQRConnectNative(
        checkURL: String?,
        token: String?,
        csrfToken: String? = nil,
        traceId: String? = nil
    ) async -> QRPollResult {
        guard let url = Self.buildCheckQRURL(checkURL: checkURL, token: token) else { return .failed }

        // Build cookie map from WebKit's live store so passport CSRF + ttwid cookies are included.
        let storeCookies = await WKWebsiteDataStore.default().httpCookieStore.allCookies().filter { c in
            let d = c.domain.lowercased()
            return d == "hongguoduanju.com" || d == ".hongguoduanju.com" || d.hasSuffix(".hongguoduanju.com")
        }
        var cookieMap: [String: HTTPCookie] = [:]
        for c in storeCookies { cookieMap[c.name] = c }

        func cookieHeader(for targetURL: URL) -> String {
            let host = (targetURL.host ?? "").lowercased()
            let matching = cookieMap.values.filter { c in
                let d = c.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
                return host == d || host.hasSuffix("." + d)
            }
            if !matching.isEmpty {
                return matching.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
            }
            return cachedCookieHeader ?? ""
        }

        let resolvedCsrf = (csrfToken?.isEmpty == false ? csrfToken : nil)
            ?? cookieMap["passport_csrf_token"]?.value
            ?? cookieMap["passport_csrf_token_default"]?.value

        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue(Self.pcUserAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("\(Self.webOrigin)/", forHTTPHeaderField: "Referer")
        req.setValue("application/json, text/javascript", forHTTPHeaderField: "Accept")
        let hdr = cookieHeader(for: url)
        if !hdr.isEmpty { req.setValue(hdr, forHTTPHeaderField: "Cookie") }
        if let resolvedCsrf, !resolvedCsrf.isEmpty {
            req.setValue(resolvedCsrf, forHTTPHeaderField: "x-tt-passport-csrf-token")
        }
        if let traceId, !traceId.isEmpty {
            req.setValue(traceId, forHTTPHeaderField: "x-tt-passport-trace-id")
        }

        final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
            func urlSession(
                _ session: URLSession,
                task: URLSessionTask,
                willPerformHTTPRedirection response: HTTPURLResponse,
                newRequest request: URLRequest,
                completionHandler: @escaping (URLRequest?) -> Void
            ) {
                completionHandler(nil)
            }
        }

        do {
            let noRedirect = NoRedirectDelegate()
            let (data, resp) = try await session.data(for: req, delegate: noRedirect)
            guard let http = resp as? HTTPURLResponse else { return .failed }

            var collected: [HTTPCookie] = []
            if let fields = http.allHeaderFields as? [String: String], let respURL = http.url ?? URL(string: Self.webOrigin) {
                let cookies = HTTPCookie.cookies(withResponseHeaderFields: fields, for: respURL)
                collected.append(contentsOf: cookies)
                cookieMap = Self.mergeCookieMap(cookieMap, incoming: cookies)
            }

            let result = Self.parseQRConnectResponse(data)
            if case .confirmed(let redirectStr) = result {
                if let redirectStr, let startRedirect = URL(string: redirectStr) {
                    var currentURL: URL? = startRedirect
                    var hops = 0
                    while let nextURL = currentURL, hops < 6 {
                        hops += 1
                        var hopReq = URLRequest(url: nextURL)
                        hopReq.httpMethod = "GET"
                        hopReq.setValue(Self.pcUserAgent, forHTTPHeaderField: "User-Agent")
                        hopReq.setValue("\(Self.webOrigin)/", forHTTPHeaderField: "Referer")
                        let hopCookie = cookieHeader(for: nextURL)
                        if !hopCookie.isEmpty { hopReq.setValue(hopCookie, forHTTPHeaderField: "Cookie") }
                        guard let (_, hopResp) = try? await session.data(for: hopReq, delegate: noRedirect),
                              let hopHTTP = hopResp as? HTTPURLResponse else { break }
                        if let hopFields = hopHTTP.allHeaderFields as? [String: String] {
                            let hopCookies = HTTPCookie.cookies(withResponseHeaderFields: hopFields, for: nextURL)
                            collected.append(contentsOf: hopCookies)
                            cookieMap = Self.mergeCookieMap(cookieMap, incoming: hopCookies)
                        }
                        if (300..<400).contains(hopHTTP.statusCode),
                           let loc = hopHTTP.value(forHTTPHeaderField: "Location"),
                           let resolved = URL(string: loc, relativeTo: nextURL)?.absoluteURL {
                            currentURL = resolved
                        } else {
                            currentURL = nil
                        }
                    }
                }
                if !collected.isEmpty {
                    await installCookies(collected)
                }
                // Update fallback cookie header immediately so `refreshProfile()` can use it even if WebKit is still waking up.
                let hgCookies = cookieMap.values.filter { c in
                    let d = c.domain.lowercased()
                    return d == "hongguoduanju.com" || d == ".hongguoduanju.com" || d.hasSuffix(".hongguoduanju.com")
                }
                if !hgCookies.isEmpty {
                    let merged = hgCookies.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
                    cachedCookieHeader = merged
                    UserDefaults.standard.set(merged, forKey: Self.cookieKey)
                }
            } else if !collected.isEmpty {
                await installCookies(collected)
            }
            return result
        } catch {
            return .failed
        }
    }

    private func saveProfile(_ next: Profile?) {
        profile = next
        if let next, let data = try? JSONEncoder().encode(next) {
            UserDefaults.standard.set(data, forKey: Self.profileKey)
        } else {
            UserDefaults.standard.removeObject(forKey: Self.profileKey)
        }
    }

    private static func parseProfileJSON(_ data: Data) -> (hasRoot: Bool, profile: Profile?) {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return (false, nil)
        }
        let candidates: [[String: Any]?] = [
            root["profile"] as? [String: Any],
            (root["data"] as? [String: Any])?["profile"] as? [String: Any],
            root["user"] as? [String: Any],
            (root["data"] as? [String: Any])?["user"] as? [String: Any]
        ]
        for cand in candidates {
            guard let p = cand else { continue }
            let nick = (
                (p["nickname"] as? String) ??
                (p["name"] as? String) ??
                (p["screen_name"] as? String) ??
                (p["user_name"] as? String) ?? ""
            ).trimmingCharacters(in: .whitespacesAndNewlines)
            let avatar = (
                (p["avatarUrl"] as? String) ??
                (p["avatar_url"] as? String) ??
                (p["avatar"] as? String) ?? ""
            ).trimmingCharacters(in: .whitespacesAndNewlines)
            if !nick.isEmpty {
                return (true, Profile(nickname: nick, avatarUrl: avatar))
            }
        }
        return (true, nil)
    }

    /// Called when the in-app WKWebView directly observes a logged-in profile response or SSR state.
    func acceptWebViewLogin(nickname: String, avatarUrl: String) async {
        let trimmed = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        lastConfirmedLoginAt = Date()
        sessionTags.removeAll()
        lastSyncNote = nil
        saveProfile(Profile(nickname: trimmed, avatarUrl: avatarUrl.trimmingCharacters(in: .whitespacesAndNewlines)))
        _ = await syncCookiesFromWebView()
        try? await Task.sleep(nanoseconds: 500_000_000)
        _ = await syncCookiesFromWebView()
    }

    private var withinLoginGrace: Bool {
        guard let t = lastConfirmedLoginAt else { return false }
        return Date().timeIntervalSince(t) < Self.loginGraceWindow
    }

    @discardableResult
    func refreshProfile() async -> Profile? {
        syncing = true
        defer { syncing = false }

        // 1) Primary: same-origin fetch inside WebKit (always carries HttpOnly session cookies).
        if let res = await webViewFetch(path: "/api/pc/profile", headers: ["Accept": "application/json"]),
           res.status == 200,
           let data = res.text.data(using: .utf8) {
            let parsed = Self.parseProfileJSON(data)
            if let found = parsed.profile {
                lastConfirmedLoginAt = Date()
                saveProfile(found)
                _ = await syncCookiesFromWebView()
                return found
            }
            if parsed.hasRoot && !withinLoginGrace {
                saveProfile(nil)
                return nil
            }
            return profile
        }

        // 2) Fallback: URLSession with snapshotted cookies (never clear profile on fallback miss if already logged in).
        let cookie = await syncCookiesFromWebView()
        guard let cookie, !cookie.isEmpty, let url = URL(string: "\(Self.webOrigin)/api/pc/profile") else {
            return profile
        }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue(cookie, forHTTPHeaderField: "Cookie")
        req.setValue(Self.pcUserAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("\(Self.webOrigin)/", forHTTPHeaderField: "Referer")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        do {
            let (data, resp) = try await session.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            if code == 200 {
                let parsed = Self.parseProfileJSON(data)
                if let found = parsed.profile {
                    lastConfirmedLoginAt = Date()
                    saveProfile(found)
                    return found
                }
            }
            return profile
        } catch {
            return profile
        }
    }

    func logout() {
        lastConfirmedLoginAt = nil
        cachedCookieHeader = nil
        UserDefaults.standard.removeObject(forKey: Self.cookieKey)
        UserDefaults.standard.removeObject(forKey: Self.profileKey)
        sessionTags.removeAll()
        lastReportedAt.removeAll()
        profile = nil
        lastSyncNote = nil

        Task {
            let dataStore = WKWebsiteDataStore.default()
            let all = await dataStore.httpCookieStore.allCookies()
            for c in all where c.domain.lowercased().contains("hongguoduanju.com") {
                await dataStore.httpCookieStore.deleteCookie(c)
            }
            let records = await dataStore.dataRecords(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes())
            let targets = records.filter { $0.displayName.lowercased().contains("hongguoduanju") }
            if !targets.isEmpty {
                await dataStore.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), for: targets)
            }
        }
    }

    func reportProgressAsync(
        seriesId: String,
        vid: String,
        episodeNumber: Int,
        positionMs: Int64,
        accumulatedMs: Int64,
        force: Bool = false
    ) {
        guard isLoggedIn,
              !seriesId.isEmpty, seriesId.allSatisfy(\.isNumber),
              !vid.isEmpty, vid.allSatisfy(\.isNumber),
              episodeNumber > 0, positionMs > 0 else { return }

        let key = "\(seriesId):\(vid)"
        let now = Date()
        if !force, let prev = lastReportedAt[key], now.timeIntervalSince(prev) < Self.reportThrottle {
            return
        }
        lastReportedAt[key] = now

        Task { [weak self] in
            guard let self else { return }
            let cookie = await self.syncCookiesFromWebView() ?? ""
            let playerPath = "/player/\(seriesId)/\(vid)"
            let payload: [String: Any] = [
                "book_id": seriesId,
                "book_type": 2,
                "is_delete": false,
                "read_timestamp_ms": String(Int64(Date().timeIntervalSince1970 * 1000)),
                "vid": vid,
                "vid_index": String(episodeNumber),
                "current_play_position": String(max(1, positionMs)),
                "player_accumulate_total_time": String(max(1000, accumulatedMs))
            ]
            guard let bodyData = try? JSONSerialization.data(withJSONObject: payload),
                  let bodyString = String(data: bodyData, encoding: .utf8) else { return }

            var lastReason: String?
            for attempt in 0..<2 {
                guard let sessionTag = await self.fetchSessionTag(
                    seriesId: seriesId,
                    vid: vid,
                    cookie: cookie,
                    forceRefresh: attempt > 0
                ) else {
                    self.lastSyncNote = "未获取到网页同步会话，请尝试重新登录"
                    return
                }

                // Primary: same-origin POST inside WebKit with explicit player Referer so HttpOnly cookies match natively.
                if let res = await self.webViewFetch(
                    path: "/api/pc/watch-history",
                    method: "POST",
                    headers: [
                        "Content-Type": "application/json; charset=utf-8",
                        "Accept": "application/json",
                        "X-PC-History-Session": sessionTag
                    ],
                    body: bodyString,
                    referrer: playerPath
                ), res.status > 0 {
                    let json = res.text.data(using: .utf8).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
                    let ok = (json?["isSuccess"] as? Bool) == true
                    if ok {
                        self.lastSyncNote = "已同步第 \(episodeNumber) 集到红果"
                        return
                    }
                    self.sessionTags.removeValue(forKey: key)
                    lastReason = (json?["reason"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "HTTP \(res.status)"
                    if attempt == 0 { continue }
                }

                // Fallback: URLSession
                guard !cookie.isEmpty, let url = URL(string: "\(Self.webOrigin)/api/pc/watch-history") else { break }
                var req = URLRequest(url: url)
                req.httpMethod = "POST"
                req.httpBody = bodyData
                req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
                req.setValue("application/json", forHTTPHeaderField: "Accept")
                req.setValue(cookie, forHTTPHeaderField: "Cookie")
                req.setValue(Self.pcUserAgent, forHTTPHeaderField: "User-Agent")
                req.setValue(Self.webOrigin, forHTTPHeaderField: "Origin")
                req.setValue("\(Self.webOrigin)\(playerPath)", forHTTPHeaderField: "Referer")
                req.setValue(sessionTag, forHTTPHeaderField: "X-PC-History-Session")

                do {
                    let (data, resp) = try await self.session.data(for: req)
                    let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
                    let ok = (json?["isSuccess"] as? Bool) == true
                    if ok {
                        self.lastSyncNote = "已同步第 \(episodeNumber) 集到红果"
                        return
                    }
                    self.sessionTags.removeValue(forKey: key)
                    lastReason = (json?["reason"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "HTTP \(code)"
                } catch {
                    // Silent network failure during background sync
                    return
                }
            }
            if let reason = lastReason {
                self.lastSyncNote = "同步未生效（\(reason)）"
            }
        }
    }

    private func fetchSessionTag(seriesId: String, vid: String, cookie: String, forceRefresh: Bool = false) async -> String? {
        let key = "\(seriesId):\(vid)"
        if !forceRefresh, let cached = sessionTags[key], Date().timeIntervalSince(cached.at) < Self.sessionTagTTL {
            return cached.tag
        }
        let paths = ["/player/\(seriesId)/\(vid)", "/player/\(seriesId)"]
        for path in paths {
            if let res = await webViewFetch(path: path, referrer: "/"), (200..<300).contains(res.status),
               let tag = Self.extractSessionTag(from: res.text) {
                sessionTags[key] = (tag, Date())
                return tag
            }
            guard !cookie.isEmpty, let url = URL(string: "\(Self.webOrigin)\(path)") else { continue }
            var req = URLRequest(url: url)
            req.httpMethod = "GET"
            req.setValue(cookie, forHTTPHeaderField: "Cookie")
            req.setValue(Self.pcUserAgent, forHTTPHeaderField: "User-Agent")
            req.setValue("\(Self.webOrigin)/", forHTTPHeaderField: "Referer")
            guard let (data, resp) = try? await session.data(for: req),
                  let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                  let html = String(data: data, encoding: .utf8),
                  let tag = Self.extractSessionTag(from: html) else { continue }
            sessionTags[key] = (tag, Date())
            return tag
        }
        return nil
    }

    private static func extractSessionTag(from html: String) -> String? {
        let patterns = [
            "\"pcHistorySessionTag\"\\s*:\\s*\"([^\"]+)\"",
            "\\\\\"pcHistorySessionTag\\\\\"\\s*:\\s*\\\\\"([^\\\\\"]+)\\\\\""
        ]
        let ns = html as NSString
        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern),
                  let match = regex.firstMatch(in: html, range: NSRange(location: 0, length: ns.length)),
                  match.numberOfRanges >= 2 else { continue }
            let range = match.range(at: 1)
            if range.location != NSNotFound {
                let value = ns.substring(with: range)
                if !value.isEmpty { return value }
            }
        }
        return nil
    }
}
