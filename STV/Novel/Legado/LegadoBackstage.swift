/* iOS port of legado-E / LegadoTeam/legado help/http/BackstageWebView.kt + AnalyzeUrl
 * (useWebView/webJs/webViewDelayTime/sourceRegex/overrideUrlRegex/POST/HTML). GPL-3.0.
 * TLS failures are NOT bypassed; private-network hosts blocked by WKContentRuleList;
 * cancellation destroys the WKWebView cleanly. */
import Foundation
import WebKit

struct LegadoBackstageRequest {
    var url: URL
    var headers: [String: String]
    var javaScript: String?
    var sourceRegex: String?
    var delayMs: Int
    var timeout: TimeInterval = 60
    var method: String = "GET"
    var body: String? = nil
    var html: String? = nil
    var overrideUrlRegex: String? = nil
    var useCookies: Bool = false
}

/// Cooperative cancellation for blocking source work on LegadoClient.run threads.
final class LegadoCancelToken: @unchecked Sendable {
    private let lock = NSLock()
    private var flag = false
    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return flag }
    func cancel() { lock.lock(); flag = true; lock.unlock() }
    static var current: LegadoCancelToken? { Thread.current.threadDictionary["legado.cancel"] as? LegadoCancelToken }
    func bind<T>(_ work: () throws -> T) rethrows -> T {
        Thread.current.threadDictionary["legado.cancel"] = self
        defer { Thread.current.threadDictionary.removeObject(forKey: "legado.cancel") }
        return try work()
    }
}

enum LegadoBackstage {
    static let defaultJS = "document.documentElement.outerHTML"
    static let retryIntervals = [200, 400, 600, 800, 1000]
    static let maxRetry = 30
    static let sniffJS = "(function(){var a=[];try{performance.getEntriesByType('resource').forEach(function(e){a.push(e.name)})}catch(e){}document.querySelectorAll('video,audio,source').forEach(function(e){if(e.currentSrc)a.push(e.currentSrc);if(e.src)a.push(e.src)});return JSON.stringify(a)})()"
    nonisolated(unsafe) static var loader: ((LegadoBackstageRequest) throws -> LegadoResponse)?

    static func useWebView(_ v: Any?) -> Bool {
        switch v {
        case nil, is NSNull: return false
        case let b as Bool: return b
        default: let s = "\(v!)"; return !s.isEmpty && s != "false"
        }
    }
    static func evalResult(_ v: Any?) -> String? {
        guard let v, !(v is NSNull) else { return nil }
        let s = (v as? String) ?? "\(v)"
        return s.isEmpty || s == "null" || s == "undefined" ? nil : s
    }
    static func matches(_ regex: String, _ url: String) -> Bool {
        guard let re = try? NSRegularExpression(pattern: "^(?:" + regex + ")$") else { return false }
        return re.firstMatch(in: url, range: NSRange(url.startIndex..., in: url)) != nil
    }
    static func firstSniffed(_ regex: String, _ json: Any?) -> String? {
        guard let s = evalResult(json), let d = s.data(using: .utf8), let arr = (try? JSONSerialization.jsonObject(with: d)) as? [Any] else { return nil }
        return arr.compactMap { $0 as? String }.first { $0.hasPrefix("http") && matches(regex, $0) }
    }

    /// Blocking; call from a worker thread. Honors LegadoCancelToken.current.
    static func load(_ r: LegadoBackstageRequest) throws -> LegadoResponse {
        if let loader { return try loader(r) }
        precondition(!Thread.isMainThread, "WebView解析不能阻塞主线程")
        let token = LegadoCancelToken.current
        let box = ResultBox()
        let sem = DispatchSemaphore(value: 0)
        Task { @MainActor in
            let runner = Runner(request: r) { result in if box.set(result) { sem.signal() } }
            box.runner = runner
            await runner.start()
        }
        let deadline = Date().addingTimeInterval(r.timeout)
        while sem.wait(timeout: .now() + 0.1) == .timedOut {
            if token?.isCancelled == true { _ = box.set(.failure(CancellationError())); break }
            if Date() >= deadline { _ = box.set(.failure(LegadoError.network("WebView 解析超时（\(Int(r.timeout))秒）"))); break }
        }
        Task { @MainActor in box.runner?.destroy(); box.runner = nil }
        return try box.result!.get()
    }

    private final class ResultBox: @unchecked Sendable {
        private let lock = NSLock()
        var result: Result<LegadoResponse, Error>?
        nonisolated(unsafe) var runner: Runner?
        func set(_ r: Result<LegadoResponse, Error>) -> Bool { lock.lock(); defer { lock.unlock() }; if result != nil { return false }; result = r; return true }
    }

    nonisolated(unsafe) private static var compiledRules: WKContentRuleList?
    private static let privateRules = #"[{"trigger":{"url-filter":"^[a-z]+://127\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://10\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://0\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://169\\.254\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://192\\.168\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://172\\.1[6-9]\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://172\\.2[0-9]\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://172\\.3[0-1]\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://100\\.6[4-9]\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://100\\.[7-9][0-9]\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://100\\.1[0-1][0-9]\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://100\\.12[0-7]\\."},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://localhost"},"action":{"type":"block"}},{"trigger":{"url-filter":"^[a-z]+://\\["},"action":{"type":"block"}},{"trigger":{"url-filter":"^file:"},"action":{"type":"block"}},{"trigger":{"url-filter":"^ftp:"},"action":{"type":"block"}}]"#

    @MainActor private final class Runner: NSObject, WKNavigationDelegate {
        let request: LegadoBackstageRequest
        let finish: (Result<LegadoResponse, Error>) -> Void
        var web: WKWebView?
        var generation = 0
        var done = false
        init(request: LegadoBackstageRequest, finish: @escaping (Result<LegadoResponse, Error>) -> Void) { self.request = request; self.finish = finish }

        func complete(_ r: Result<LegadoResponse, Error>) {
            guard !done else { return }
            done = true
            if request.useCookies, let store = web?.configuration.websiteDataStore.httpCookieStore {
                Task { @MainActor in await LegadoCookies.syncFromWebKit(store: store) }
            }
            finish(r)
            destroy()
        }
        func destroy() { done = true; web?.stopLoading(); web?.navigationDelegate = nil; web = nil }

        func start() async {
            let cfg = WKWebViewConfiguration()
            cfg.websiteDataStore = request.useCookies ? .default() : .nonPersistent()
            cfg.mediaTypesRequiringUserActionForPlayback = .all
            cfg.allowsInlineMediaPlayback = true
            if LegadoBackstage.compiledRules == nil {
                LegadoBackstage.compiledRules = try? await WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "legado-backstage-private", encodedContentRuleList: LegadoBackstage.privateRules)
            }
            guard let rules = LegadoBackstage.compiledRules else { complete(.failure(LegadoError.rule("WebView 内网阻断规则不可用，拒绝加载"))); return }
            cfg.userContentController.add(rules)
            guard !done else { return }
            let w = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 844), configuration: cfg)
            w.customUserAgent = request.headers.first { $0.key.caseInsensitiveCompare("User-Agent") == .orderedSame }?.value ?? LegadoHTTP.userAgent
            w.navigationDelegate = self
            web = w
            if let html = request.html, !html.isEmpty {
                w.loadHTMLString(html, baseURL: request.url)
                return
            }
            var req = URLRequest(url: request.url)
            req.httpMethod = request.method
            if request.method.uppercased() == "POST", let body = request.body {
                req.httpBody = Data(body.utf8)
                if !request.headers.keys.contains(where: { $0.caseInsensitiveCompare("Content-Type") == .orderedSame }) {
                    let isJSON = body.trimmingCharacters(in: .whitespaces).hasPrefix("{")
                    req.setValue(isJSON ? "application/json; charset=UTF-8" : "application/x-www-form-urlencoded; charset=UTF-8", forHTTPHeaderField: "Content-Type")
                }
            }
            for (k, v) in request.headers where k.caseInsensitiveCompare("User-Agent") != .orderedSame { req.setValue(v, forHTTPHeaderField: k) }
            w.load(req)
        }

        nonisolated static func cheapAllowed(_ url: URL?) -> Bool {
            guard let url, let scheme = url.scheme?.lowercased() else { return false }
            if scheme == "about" || scheme == "data" || scheme == "blob" { return true }
            guard ["http", "https"].contains(scheme), let host = url.host?.trimmingCharacters(in: CharacterSet(charactersIn: "[]")).lowercased(), url.user == nil else { return false }
            if host == "localhost" || host.hasSuffix(".localhost") || host.hasSuffix(".local") { return false }
            return !(NetGuard.isIPLiteral(host) && NetGuard.isBlockedIP(host))
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
            if let ov = request.overrideUrlRegex, let navUrl = navigationAction.request.url?.absoluteString, LegadoBackstage.matches(ov, navUrl) {
                complete(.success(LegadoResponse(url: request.url, status: 200, headers: [:], body: Data(navUrl.utf8), text: navUrl)))
                return .cancel
            }
            return Runner.cheapAllowed(navigationAction.request.url) ? .allow : .cancel
        }
        func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse) async -> WKNavigationResponsePolicy {
            if navigationResponse.isForMainFrame, let http = navigationResponse.response as? HTTPURLResponse, http.statusCode >= 400 {
                complete(.failure(LegadoError.network("WebView 页面 HTTP \(http.statusCode)"))); return .cancel
            }
            return .allow
        }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { failed(error) }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { failed(error) }
        private func failed(_ error: Error) {
            if (error as NSError).code == NSURLErrorCancelled { return }
            complete(.failure(LegadoError.network("WebView 页面加载失败：\(error.localizedDescription)")))
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard !done else { return }
            generation += 1
            let gen = generation
            let delay = 100 + request.delayMs
            if let regex = request.sourceRegex {
                if let js = request.javaScript, !js.isEmpty { after(delay, gen) { webView.evaluateJavaScript(js, completionHandler: nil) } }
                sniff(regex, webView, gen, delay)
                return
            }
            let code = (request.javaScript?.isEmpty == false ? request.javaScript! : LegadoBackstage.defaultJS)
            evalLoop(code, webView, gen, delay, retry: 0)
        }
        private func after(_ ms: Int, _ gen: Int, _ work: @escaping @MainActor () -> Void) {
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(ms))
                guard let self, !self.done, self.generation == gen else { return }
                work()
            }
        }
        private func evalLoop(_ code: String, _ w: WKWebView, _ gen: Int, _ delay: Int, retry: Int) {
            after(delay, gen) { [weak self] in
                w.evaluateJavaScript(code) { value, _ in
                    guard let self, !self.done else { return }
                    if let text = LegadoBackstage.evalResult(value) {
                        let url = w.url ?? self.request.url
                        self.complete(.success(LegadoResponse(url: url, status: 200, headers: [:], body: Data(text.utf8), text: text))); return
                    }
                    if retry > LegadoBackstage.maxRetry { self.complete(.failure(LegadoError.rule("js执行超时"))); return }
                    let next = LegadoBackstage.retryIntervals[min(retry, LegadoBackstage.retryIntervals.count - 1)]
                    self.evalLoop(code, w, gen, next, retry: retry + 1)
                }
            }
        }
        private func sniff(_ regex: String, _ w: WKWebView, _ gen: Int, _ delay: Int) {
            after(delay, gen) { [weak self] in
                w.evaluateJavaScript(LegadoBackstage.sniffJS) { value, _ in
                    guard let self, !self.done else { return }
                    if let hit = LegadoBackstage.firstSniffed(regex, value) {
                        self.complete(.success(LegadoResponse(url: self.request.url, status: 200, headers: [:], body: Data(hit.utf8), text: hit))); return
                    }
                    self.sniff(regex, w, gen, 500)
                }
            }
        }
    }
}
