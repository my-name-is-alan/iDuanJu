import Foundation
import UIKit
import WebKit

/// Per-source login state, like Legado: login info (the loginUi form), login header (added to every request of
/// the source) and the `cache` object (values with an optional lifetime). Persisted in UserDefaults.
enum LegadoSourceState {
    private static let lock = NSLock()
    private static let key = "stv.legado.state"
    nonisolated(unsafe) private static var mem: [String: [String: String]]?

    private static func load() -> [String: [String: String]] {
        if let m = mem { return m }
        let m = (UserDefaults.standard.dictionary(forKey: key) as? [String: [String: String]]) ?? [:]
        mem = m
        return m
    }

    static func get(_ source: String, _ k: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return load()[source]?[k]
    }

    static func set(_ source: String, _ k: String, _ v: String?) {
        lock.lock(); defer { lock.unlock() }
        var all = load()
        all[source, default: [:]][k] = v
        mem = all
        UserDefaults.standard.set(all, forKey: key)
    }

    static func loginInfo(_ s: String) -> [String: String] {
        guard let t = get(s, "loginInfo"), let d = t.data(using: .utf8) else { return [:] }
        return ((try? JSONSerialization.jsonObject(with: d)) as? [String: Any])?.mapValues { "\($0)" } ?? [:]
    }

    static func setLoginInfo(_ s: String, _ m: [String: String]) {
        set(s, "loginInfo", (try? JSONSerialization.data(withJSONObject: m)).map { String(decoding: $0, as: UTF8.self) })
    }

    /// Login header JSON object (merged into the source's requests).
    static func loginHeader(_ s: String) -> [String: String] {
        guard let t = get(s, "loginHeader"), let d = t.data(using: .utf8) else { return [:] }
        return ((try? JSONSerialization.jsonObject(with: d)) as? [String: Any])?.mapValues { "\($0)" } ?? [:]
    }

    static func cacheGet(_ s: String, _ k: String) -> String? {
        guard let v = get(s, "cache." + k) else { return nil }
        if let exp = get(s, "cacheExp." + k).flatMap(Double.init), exp > 0, Date().timeIntervalSince1970 > exp {
            set(s, "cache." + k, nil)
            return nil
        }
        return v
    }

    static func cachePut(_ s: String, _ k: String, _ v: String?, seconds: Double) {
        set(s, "cache." + k, v)
        set(s, "cacheExp." + k, seconds > 0 ? String(Date().timeIntervalSince1970 + seconds) : nil)
    }

    static func isLoggedIn(_ s: String) -> Bool { !loginInfo(s).isEmpty || !loginHeader(s).isEmpty || get(s, "webLogin") == "1" }

    static func logout(_ s: String) {
        lock.lock()
        var all = load()
        all[s] = nil
        mem = all
        UserDefaults.standard.set(all, forKey: key)
        lock.unlock()
        if let u = URL(string: s) {
            LegadoCookies.remove(for: u)
        }
    }
}

/// WebKit cookies → URLSession (HTTPCookieStorage.shared) + persisted snapshot, so book-source requests always carry
/// the web login cookies (including session-only cookies across app restarts).
enum LegadoCookies {
    struct Stored: Codable {
        let name: String
        let value: String
        let domain: String
        let path: String
        let secure: Bool
        let expires: Double?
    }

    private static let lock = NSLock()
    private static let key = "stv.legado.cookies.v1"
    nonisolated(unsafe) private static var mem: [Stored]?

    private static func load() -> [Stored] {
        if let m = mem { return m }
        guard let d = UserDefaults.standard.data(forKey: key),
              let list = try? JSONDecoder().decode([Stored].self, from: d) else {
            mem = []
            return []
        }
        mem = list
        return list
    }

    private static func save(_ list: [Stored]) {
        mem = list
        if let d = try? JSONEncoder().encode(list) {
            UserDefaults.standard.set(d, forKey: key)
        }
    }

    private static func domainMatches(cookieDomain: String, host: String) -> Bool {
        let cd = cookieDomain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        let h = host.lowercased()
        guard !cd.isEmpty, !h.isEmpty else { return false }
        return h == cd || h.hasSuffix("." + cd)
    }

    private static func pathMatches(cookiePath: String, requestPath: String) -> Bool {
        let cp = cookiePath.isEmpty ? "/" : cookiePath
        let rp = requestPath.isEmpty ? "/" : requestPath
        if cp == "/" { return true }
        return rp == cp || rp.hasPrefix(cp.hasSuffix("/") ? cp : cp + "/")
    }

    @MainActor static func syncFromWebKit(store: WKHTTPCookieStore = WKWebsiteDataStore.default().httpCookieStore) async {
        let cookies = await store.allCookies()
        guard !cookies.isEmpty else { return }
        let now = Date().timeIntervalSince1970
        var byKey: [String: Stored] = [:]
        lock.lock()
        for existing in load() {
            if let exp = existing.expires, exp < now { continue }
            byKey["\(existing.domain.lowercased())|\(existing.path)|\(existing.name)"] = existing
        }
        for c in cookies {
            if let exp = c.expiresDate, exp.timeIntervalSince1970 < now { continue }
            // Re-materialize without session-only drop so URLSession & HTTPCookieStorage keep it reliably.
            var props = c.properties ?? [:]
            if props[.expires] == nil {
                props[.expires] = Date(timeIntervalSinceNow: 30 * 24 * 3600)
                props.removeValue(forKey: .discard)
            }
            if let normalized = HTTPCookie(properties: props) {
                HTTPCookieStorage.shared.setCookie(normalized)
            } else {
                HTTPCookieStorage.shared.setCookie(c)
            }
            let item = Stored(
                name: c.name,
                value: c.value,
                domain: c.domain,
                path: c.path,
                secure: c.isSecure,
                expires: c.expiresDate?.timeIntervalSince1970
            )
            byKey["\(item.domain.lowercased())|\(item.path)|\(item.name)"] = item
        }
        save(Array(byKey.values))
        lock.unlock()
    }

    static func syncFromWebKitBlocking() {
        let sem = DispatchSemaphore(value: 0)
        Task { @MainActor in await syncFromWebKit(); sem.signal() }
        _ = sem.wait(timeout: .now() + 5)
    }

    /// Returns merged `Cookie` header value (HTTPCookieStorage + persisted WebKit snapshot) for `url`.
    static func cookieHeader(for url: URL) -> String? {
        guard let host = url.host, !host.isEmpty else { return nil }
        let isHTTPS = url.scheme?.lowercased() == "https"
        let reqPath = url.path.isEmpty ? "/" : url.path
        let now = Date().timeIntervalSince1970

        var pairs: [String: String] = [:]
        var order: [String] = []
        func put(_ name: String, _ value: String) {
            if pairs[name] == nil { order.append(name) }
            pairs[name] = value
        }

        lock.lock()
        let stored = load()
        lock.unlock()
        for c in stored {
            if let exp = c.expires, exp < now { continue }
            if c.secure && !isHTTPS { continue }
            if domainMatches(cookieDomain: c.domain, host: host) && pathMatches(cookiePath: c.path, requestPath: reqPath) {
                put(c.name, c.value)
            }
        }
        for c in HTTPCookieStorage.shared.cookies(for: url) ?? [] {
            put(c.name, c.value)
        }
        guard !order.isEmpty else { return nil }
        return order.compactMap { k in pairs[k].map { "\(k)=\($0)" } }.joined(separator: "; ")
    }

    static func captureResponseHeaders(_ allHeaders: [AnyHashable: Any], for url: URL) {
        var stringFields: [String: String] = [:]
        for (k, v) in allHeaders { stringFields["\(k)"] = "\(v)" }
        let parsed = HTTPCookie.cookies(withResponseHeaderFields: stringFields, for: url)
        guard !parsed.isEmpty else { return }
        let now = Date().timeIntervalSince1970
        lock.lock()
        var byKey: [String: Stored] = [:]
        for existing in load() {
            if let exp = existing.expires, exp < now { continue }
            byKey["\(existing.domain.lowercased())|\(existing.path)|\(existing.name)"] = existing
        }
        for c in parsed {
            HTTPCookieStorage.shared.setCookie(c)
            let k = "\(c.domain.lowercased())|\(c.path)|\(c.name)"
            if let exp = c.expiresDate, exp.timeIntervalSince1970 <= now {
                byKey.removeValue(forKey: k)
            } else {
                byKey[k] = Stored(name: c.name, value: c.value, domain: c.domain, path: c.path, secure: c.isSecure, expires: c.expiresDate?.timeIntervalSince1970)
            }
        }
        save(Array(byKey.values))
        lock.unlock()
    }

    static func setManual(_ cookieString: String, for url: URL) {
        guard let host = url.host, !host.isEmpty else { return }
        let parts = cookieString.split(separator: ";").compactMap { part -> (String, String)? in
            let kv = part.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            guard kv.count == 2, !kv[0].isEmpty else { return nil }
            return (kv[0], kv[1])
        }
        guard !parts.isEmpty else { return }
        let exp = Date(timeIntervalSinceNow: 30 * 24 * 3600)
        let cookies = parts.compactMap { HTTPCookie(properties: [.name: $0.0, .value: $0.1, .domain: host, .path: "/", .expires: exp]) }
        HTTPCookieStorage.shared.setCookies(cookies, for: url, mainDocumentURL: nil)
        lock.lock()
        var byKey: [String: Stored] = [:]
        for existing in load() { byKey["\(existing.domain.lowercased())|\(existing.path)|\(existing.name)"] = existing }
        for (k, v) in parts {
            let item = Stored(name: k, value: v, domain: host, path: "/", secure: false, expires: exp.timeIntervalSince1970)
            byKey["\(item.domain.lowercased())|\(item.path)|\(item.name)"] = item
        }
        save(Array(byKey.values))
        lock.unlock()
    }

    static func remove(for url: URL) {
        guard let host = url.host, !host.isEmpty else { return }
        (HTTPCookieStorage.shared.cookies(for: url) ?? []).forEach(HTTPCookieStorage.shared.deleteCookie)
        lock.lock()
        let remaining = load().filter { !domainMatches(cookieDomain: $0.domain, host: host) }
        save(remaining)
        lock.unlock()
        Task { @MainActor in
            let store = WKWebsiteDataStore.default().httpCookieStore
            let all = await store.allCookies()
            for c in all where domainMatches(cookieDomain: c.domain, host: host) {
                await store.deleteCookie(c)
            }
        }
    }
}

// MARK: - Browser (login pages, java.startBrowser)

/// Full-screen browser presented over whatever is on screen. `open` returns when the user taps 完成.
@MainActor
final class LegadoBrowser: NSObject, WKNavigationDelegate {
    static func open(url: URL?, html: String?, title: String, sourceURL: String?, userAgent: String? = nil) async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            let vc = LegadoBrowserVC(url: url, html: html, title: title, userAgent: userAgent) {
                if let s = sourceURL { LegadoSourceState.set(s, "webLogin", "1") }
                cont.resume()
            }
            let nav = UINavigationController(rootViewController: vc)
            nav.modalPresentationStyle = .fullScreen
            guard let top = Self.topController() else { cont.resume(); return }
            top.present(nav, animated: true)
        }
    }

    /// From a book-source worker thread: shows the browser and blocks until it is closed.
    nonisolated static func openBlocking(url: URL?, html: String?, title: String, sourceURL: String?, userAgent: String? = nil) {
        let sem = DispatchSemaphore(value: 0)
        Task { @MainActor in
            await open(url: url, html: html, title: title, sourceURL: sourceURL, userAgent: userAgent)
            sem.signal()
        }
        sem.wait()
    }

    static func topController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
        ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController ?? scene?.windows.first?.rootViewController
        while let p = top?.presentedViewController { top = p }
        return top
    }
}

final class LegadoBrowserVC: UIViewController {
    private let web = WKWebView(frame: .zero, configuration: {
            let c = WKWebViewConfiguration()
            c.websiteDataStore = .default()
            return c
        }())
    private let url: URL?
    private let html: String?
    private let customUA: String?
    private let onDone: () -> Void
    private var finished = false

    init(url: URL?, html: String?, title: String, userAgent: String? = nil, onDone: @escaping () -> Void) {
        self.url = url
        self.html = html
        self.customUA = userAgent
        self.onDone = onDone
        super.init(nibName: nil, bundle: nil)
        self.title = title
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        web.customUserAgent = (customUA?.isEmpty == false ? customUA : nil) ?? LegadoHTTP.userAgent
        web.frame = view.bounds
        web.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(web)
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "完成", style: .done, target: self, action: #selector(done))
        navigationItem.rightBarButtonItem?.accessibilityIdentifier = "browser.done"
        navigationItem.leftBarButtonItem = UIBarButtonItem(image: UIImage(systemName: "arrow.clockwise"), style: .plain, target: web, action: #selector(WKWebView.reload))
        if let html { web.loadHTMLString(html, baseURL: url) } else if let url { web.load(URLRequest(url: url)) }
    }

    @objc private func done() {
        guard !finished else { return }
        finished = true
        Task { @MainActor in
            // Sync cookies while the WKWebView is still alive so session cookies are captured reliably.
            await LegadoCookies.syncFromWebKit(store: web.configuration.websiteDataStore.httpCookieStore)
            dismiss(animated: true) { [onDone] in onDone() }
        }
    }
}

// MARK: - Hidden WebView (java.webView)

/// Loads a page (or HTML) in an off-screen WKWebView with the shared login cookies, then evaluates `js`
/// (default: the page HTML). Blocking; call from a worker thread.
enum LegadoWebView {
    nonisolated static func run(html: String?, url: String?, js: String?, timeout: TimeInterval = 25) throws -> String {
        let sem = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var result: Result<String, Error> = .failure(LegadoError.network("网页加载超时"))
        Task { @MainActor in
            let runner = Runner()
            result = await runner.run(html: html, url: url, js: js, timeout: timeout)
            sem.signal()
        }
        if sem.wait(timeout: .now() + timeout + 5) == .timedOut { throw LegadoError.network("网页加载超时") }
        return try result.get()
    }

    @MainActor private final class Runner: NSObject, WKNavigationDelegate {
        private var web: WKWebView?
        private var loaded: CheckedContinuation<Void, Never>?

        func run(html: String?, url: String?, js: String?, timeout: TimeInterval) async -> Result<String, Error> {
            let cfg = WKWebViewConfiguration()
            cfg.websiteDataStore = .default()
            let w = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: cfg)
            w.customUserAgent = LegadoHTTP.userAgent
            w.navigationDelegate = self
            web = w
            let base = url.flatMap { URL(string: $0) }
            await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
                loaded = c
                if let html, !html.isEmpty { w.loadHTMLString(html, baseURL: base) }
                else if let base { w.load(URLRequest(url: base)) }
                else { c.resume(); loaded = nil }
                Task { @MainActor [weak self] in
                    try? await Task.sleep(for: .seconds(timeout))
                    self?.finishLoad()
                }
            }
            // Give scripts on the page a moment (tokens are often written after load).
            try? await Task.sleep(for: .milliseconds(600))
            let code = (js?.isEmpty == false ? js! : "document.documentElement.outerHTML")
            do {
                let v = try await w.evaluateJavaScript("(function(){ return (\(code)); })()")
                web = nil
                return .success(v.map { "\($0)" } ?? "")
            } catch {
                // Statements (not an expression): run as-is.
                let v = try? await w.evaluateJavaScript(code)
                web = nil
                return .success(v.map { "\($0)" } ?? "")
            }
        }

        private func finishLoad() {
            loaded?.resume()
            loaded = nil
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { finishLoad() }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { finishLoad() }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { finishLoad() }
    }
}

// MARK: - Login UI v2 (LegadoTeam dynamic state protocol)

enum LegadoLoginV2 {
    struct V2Field: Hashable {
        var key: String
        var name: String
        var type: String
        var options: [String]
        var action: String?
        var defaultValue: String
        var value: String?
    }

    struct ActionResult {
        var stateJSON: String?
        var errors: [String: String] = [:]
        var loginJSON: String?
        var close: Bool = false
        var message: String?
    }

    static func isV2(_ s: LegadoSource) -> Bool {
        if let ui = s.loginUi?.trimmingCharacters(in: .whitespacesAndNewlines), ui.hasPrefix("{"),
           let d = ui.data(using: .utf8),
           let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
           (o["version"] as? Int) == 2 {
            return true
        }
        return s.isJsSource && LegadoJsSource.hasLoginV2(s)
    }

    private static func scriptPrefix(_ s: LegadoSource) -> String? {
        if s.isJsSource { return LegadoJS.relaxed(s.mainJs ?? "") }
        return LegadoClient.loginJS(s).map(LegadoJS.relaxed)
    }

    static func render(_ s: LegadoSource, stateJSON: String = "{}") throws -> [V2Field] {
        guard let js = scriptPrefix(s) else { return [] }
        let st = stateJSON.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "{}" : stateJSON
        let ctx = LegadoContext(source: s); ctx.interactive = true
        let code = js + "\n;(typeof loginUi === 'function' ? loginUi(\(st)) : {rows:[]});"
        guard let obj = try ctx.evalJS(code, result: nil, baseURL: s.bookSourceUrl) as? [String: Any],
              let rows = obj["rows"] as? [[String: Any]] else { return [] }
        return rows.compactMap { d -> V2Field? in
            let name = (d["name"] as? String) ?? (d["title"] as? String) ?? ""
            let key = (d["key"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? name
            guard !name.isEmpty || !key.isEmpty else { return nil }
            let options = ((d["chars"] as? [Any]) ?? (d["options"] as? [Any]) ?? []).map { "\($0)" }
            let defVal = d["default"].map { "\($0)" } ?? options.first ?? ""
            return V2Field(
                key: key,
                name: name.isEmpty ? key : name,
                type: (d["type"] as? String ?? "text").lowercased(),
                options: options,
                action: d["action"] as? String,
                defaultValue: defVal,
                value: d["value"].map { "\($0)" }
            )
        }
    }

    static func performAction(_ s: LegadoSource, action: String, stateJSON: String, form: [String: String]) throws -> ActionResult {
        guard let js = scriptPrefix(s) else { throw LegadoError.rule("书源未定义登录脚本") }
        let st = stateJSON.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "{}" : stateJSON
        let formData = (try? JSONSerialization.data(withJSONObject: form)) ?? Data("{}".utf8)
        let formStr = String(decoding: formData, as: UTF8.self)
        let actData = (try? JSONSerialization.data(withJSONObject: [action])) ?? Data("[\"\"]".utf8)
        let actStr = String(String(decoding: actData, as: UTF8.self).dropFirst().dropLast())
        let ctx = LegadoContext(source: s); ctx.interactive = true
        let code = js + "\n;(typeof loginAction === 'function' ? loginAction(\(actStr), \(st), \(formStr)) : {});"
        let raw = try ctx.evalJS(code, result: nil, baseURL: s.bookSourceUrl)
        guard let obj = raw as? [String: Any] else { return ActionResult(message: LegadoAnalyzer.textOf(raw)) }
        var res = ActionResult()
        if let stVal = obj["state"], !(stVal is NSNull), JSONSerialization.isValidJSONObject(stVal),
           let d = try? JSONSerialization.data(withJSONObject: stVal) {
            res.stateJSON = String(decoding: d, as: UTF8.self)
        }
        if let errs = obj["error"] as? [String: Any] {
            for (k, v) in errs { res.errors[k] = "\(v)" }
        }
        if let loginVal = obj["login"], !(loginVal is NSNull) {
            if let m = loginVal as? [String: Any] {
                let mapped = m.mapValues { "\($0)" }
                LegadoSourceState.setLoginInfo(s.bookSourceUrl, mapped)
                res.loginJSON = (try? JSONSerialization.data(withJSONObject: m)).map { String(decoding: $0, as: UTF8.self) }
            } else if let b = loginVal as? Bool, b {
                LegadoSourceState.setLoginInfo(s.bookSourceUrl, form)
                res.loginJSON = formStr
            }
        }
        res.close = obj["close"] as? Bool ?? false
        res.message = obj["message"] as? String
        return res
    }
}
