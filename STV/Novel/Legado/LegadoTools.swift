import Foundation

/// Single-source step-by-step debugger (`Debug.kt`) and concurrent batch validator (`CheckSource.kt`) for iOS.
enum LegadoTools {
    struct DebugEvent: Identifiable, Hashable {
        let id = UUID()
        let timeMs: Int
        let stage: String
        let message: String
        let isError: Bool
    }

    struct DebugReport {
        var events: [DebugEvent]
        var succeeded: Bool
        var elapsedMs: Int
    }

    enum CheckStatus: String, Codable {
        case ok = "正常"
        case empty = "结果为空"
        case timeout = "超时"
        case jsError = "脚本错误"
        case httpError = "网络/HTTP异常"
    }

    struct CheckResult: Codable, Identifiable, Hashable {
        var sourceId: String
        var sourceName: String
        var status: CheckStatus
        var latencyMs: Int
        var detail: String
        var checkedAt: Double
        var id: String { sourceId }
    }

    private static let checkStoreKey = "stv.legado.checks.v1"
    private static let lock = NSLock()
    nonisolated(unsafe) private static var checksMem: [String: CheckResult]?

    private static let debugKey = "legado.debug.listener"
    private final class Box { let fn: (DebugEvent) -> Void; init(_ fn: @escaping (DebugEvent) -> Void) { self.fn = fn } }

    static func emitDebug(_ stage: String, _ message: String, isError: Bool = false) {
        guard let box = Thread.current.threadDictionary[debugKey] as? Box else { return }
        box.fn(DebugEvent(timeMs: 0, stage: stage, message: message, isError: isError))
    }

    static func debug(_ s: LegadoSource, query: String = "我的", onEvent: @escaping (DebugEvent) -> Void = { _ in }) -> DebugReport {
        var events: [DebugEvent] = []
        let start = Date()
        func emit(_ stage: String, _ msg: String, err: Bool = false) {
            let ev = DebugEvent(timeMs: Int(Date().timeIntervalSince(start) * 1000), stage: stage, message: msg, isError: err)
            events.append(ev)
            onEvent(ev)
        }
        let prev = Thread.current.threadDictionary[debugKey]
        Thread.current.threadDictionary[debugKey] = Box { ev in emit(ev.stage, ev.message, err: ev.isError) }
        defer {
            if let prev { Thread.current.threadDictionary[debugKey] = prev }
            else { Thread.current.threadDictionary.removeObject(forKey: debugKey) }
        }

        do {
            let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
            let keyword = q.isEmpty ? ((s.ruleSearch?["checkKeyWord"] ?? "").isEmpty ? "我的" : s.ruleSearch!["checkKeyWord"]!) : q
            emit("start", "开始调试书源：\(s.bookSourceName) (\(s.bookSourceUrl))，类型=\(s.bookSourceType ?? 0)，JS源=\(s.isJsSource)")
            let bookURL: String
            let bookName: String
            if keyword.lowercased().hasPrefix("http://") || keyword.lowercased().hasPrefix("https://") {
                bookURL = keyword; bookName = keyword
                emit("info", "直达详情地址：\(bookURL)")
            } else if (s.searchUrl ?? "").isEmpty && !s.isJsSource {
                let cats = try LegadoClient.exploreCategories(s).filter { !$0.isControl && !$0.url.isEmpty }
                emit("search", "发现分类数：\(cats.count)")
                guard let cat = cats.first else { throw LegadoError.empty("书源无搜索地址且无有效发现分类") }
                emit("search", "请求发现分类「\(cat.title)」：\(cat.url)")
                let hits = try LegadoClient.explore(s, category: cat, page: 1)
                emit("search", "发现命中 \(hits.count) 本" + (hits.first.map { "；首本：《\($0.name)》(\($0.author)) -> \($0.bookURL)" } ?? ""))
                guard let first = hits.first else { throw LegadoError.empty("发现列表为空") }
                bookURL = first.bookURL; bookName = first.name
            } else {
                emit("search", "正在搜索关键词：「\(keyword)」")
                let hits = try LegadoClient.search(s, key: keyword, page: 1)
                emit("search", "搜索命中 \(hits.count) 本" + (hits.first.map { "；首本：《\($0.name)》(\($0.author)) -> \($0.bookURL)" } ?? ""))
                guard let first = hits.first else { throw LegadoError.empty("搜索结果为空") }
                bookURL = first.bookURL; bookName = first.name
            }

            emit("info", "正在获取详情：\(bookURL)")
            let info = try LegadoClient.info(s, bookURL: bookURL)
            let finalName = info.name.isEmpty ? bookName : info.name
            emit("info", "详情解析完成：书名=《\(finalName)》 作者=\(info.author) 简介=\(info.intro.count)字")
            let bookDict: [String: Any] = ["name": finalName, "author": info.author, "bookUrl": bookURL, "tocUrl": info.tocURL]
            let chapters = try LegadoClient.toc(s, tocURL: info.tocURL, book: bookDict)
            emit("toc", "目录解析完成：共 \(chapters.count) 章" + (chapters.first.map { "；首章：「\($0.title)」 -> \($0.url)" } ?? ""))
            guard let firstCh = chapters.first else { throw LegadoError.empty("章节目录为空") }
            let nextCh = chapters.count > 1 ? chapters[1].url : nil

            if s.isMedia {
                emit("content", "正在解析媒体地址：\(firstCh.url)")
                let media = try LegadoMedia.resolve(s, chapter: firstCh, book: bookDict, nextChapterURL: nextCh)
                emit("content", "媒体解析成功：\(media.url.absoluteString)")
            } else if s.isFile && !info.downloadUrls.isEmpty {
                emit("content", "下载型书源解析成功：\(info.downloadUrls.count) 个下载链接 -> \(info.downloadUrls[0])")
            } else {
                emit("content", "正在获取首章正文：\(firstCh.url)")
                let text = try LegadoClient.content(s, chapter: firstCh, nextChapterURL: nextCh, book: bookDict)
                let preview = String(text.replacingOccurrences(of: "\n", with: " ").prefix(120))
                emit("content", "正文解析成功：共 \(text.count) 字；预览：\(preview)")
            }
            let elapsed = Int(Date().timeIntervalSince(start) * 1000)
            emit("done", "调试全部通过，耗时 \(elapsed) ms")
            return DebugReport(events: events, succeeded: true, elapsedMs: elapsed)
        } catch {
            let elapsed = Int(Date().timeIntervalSince(start) * 1000)
            let msg = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            emit("error", "调试失败：\(msg)", err: true)
            return DebugReport(events: events, succeeded: false, elapsedMs: elapsed)
        }
    }

    private static func loadChecks() -> [String: CheckResult] {
        if let m = checksMem { return m }
        guard let d = UserDefaults.standard.data(forKey: checkStoreKey),
              let decoded = try? JSONDecoder().decode([String: CheckResult].self, from: d) else {
            checksMem = [:]; return [:]
        }
        checksMem = decoded; return decoded
    }

    static func lastCheck(_ sourceId: String) -> CheckResult? {
        lock.lock(); defer { lock.unlock() }
        return loadChecks()[sourceId]
    }

    static func allChecks() -> [String: CheckResult] {
        lock.lock(); defer { lock.unlock() }
        return loadChecks()
    }

    private static func saveCheck(_ r: CheckResult) {
        lock.lock()
        var m = loadChecks()
        m[r.sourceId] = r
        checksMem = m
        if let d = try? JSONEncoder().encode(m) { UserDefaults.standard.set(d, forKey: checkStoreKey) }
        lock.unlock()
    }

    static func checkOne(_ s: LegadoSource, keyword: String = "我的", checkContent: Bool = true) -> CheckResult {
        let start = Date()
        let key = (s.ruleSearch?["checkKeyWord"] ?? "").isEmpty ? keyword : s.ruleSearch!["checkKeyWord"]!
        let result: CheckResult
        do {
            let hits: [LegadoSearchItem]
            if !(s.searchUrl ?? "").isEmpty || s.isJsSource {
                hits = try LegadoClient.search(s, key: key, page: 1)
            } else {
                guard let cat = try LegadoClient.exploreCategories(s).first(where: { !$0.isControl && !$0.url.isEmpty }) else {
                    throw LegadoError.empty("无搜索与发现地址")
                }
                hits = try LegadoClient.explore(s, category: cat, page: 1)
            }
            let ms = Int(Date().timeIntervalSince(start) * 1000)
            if hits.isEmpty {
                result = CheckResult(sourceId: s.id, sourceName: s.bookSourceName, status: .empty, latencyMs: ms, detail: "搜索/发现结果为空", checkedAt: Date().timeIntervalSince1970)
            } else if !checkContent {
                result = CheckResult(sourceId: s.id, sourceName: s.bookSourceName, status: .ok, latencyMs: ms, detail: "命中 \(hits.count) 本", checkedAt: Date().timeIntervalSince1970)
            } else {
                let info = try LegadoClient.info(s, bookURL: hits[0].bookURL)
                let bookDict: [String: Any] = ["name": info.name.isEmpty ? hits[0].name : info.name, "author": info.author, "bookUrl": hits[0].bookURL, "tocUrl": info.tocURL]
                let toc = try LegadoClient.toc(s, tocURL: info.tocURL, book: bookDict)
                guard let first = toc.first else { throw LegadoError.empty("目录为空") }
                let next = toc.count > 1 ? toc[1].url : nil
                if s.isMedia {
                    let media = try LegadoMedia.resolve(s, chapter: first, book: bookDict, nextChapterURL: next)
                    result = CheckResult(sourceId: s.id, sourceName: s.bookSourceName, status: .ok, latencyMs: Int(Date().timeIntervalSince(start) * 1000), detail: "目录 \(toc.count) 章 · 媒体可用 (\(media.url.host ?? ""))", checkedAt: Date().timeIntervalSince1970)
                } else {
                    let body = try LegadoClient.content(s, chapter: first, nextChapterURL: next, book: bookDict)
                    result = CheckResult(sourceId: s.id, sourceName: s.bookSourceName, status: .ok, latencyMs: Int(Date().timeIntervalSince(start) * 1000), detail: "目录 \(toc.count) 章 · 正文 \(body.count) 字", checkedAt: Date().timeIntervalSince1970)
                }
            }
        } catch {
            let msg = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            let status: CheckStatus = msg.contains("超时") ? .timeout
                : (msg.contains("JS") || msg.contains("规则")) ? .jsError
                : msg.contains("为空") ? .empty : .httpError
            result = CheckResult(sourceId: s.id, sourceName: s.bookSourceName, status: status, latencyMs: Int(Date().timeIntervalSince(start) * 1000), detail: String(msg.prefix(120)), checkedAt: Date().timeIntervalSince1970)
        }
        saveCheck(result)
        return result
    }
}
