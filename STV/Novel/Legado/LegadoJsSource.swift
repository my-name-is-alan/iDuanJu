import Foundation

/// Batch chapter content context matching LegadoTeam's `BatchContentContext`.
final class LegadoBatchContext: @unchecked Sendable {
    let chapters: [LegadoChapter]
    private let lock = NSLock()
    private var byURL: [String: String] = [:]
    private var byIndex: [Int: String] = [:]

    init(chapters: [LegadoChapter]) { self.chapters = chapters }

    func put(chapterURL: String, index: Int, content: String) -> Bool {
        guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        lock.lock(); defer { lock.unlock() }
        if !chapterURL.isEmpty { byURL[chapterURL] = content }
        if index >= 0 { byIndex[index] = content }
        return true
    }

    func get(_ chapter: LegadoChapter) -> String? {
        lock.lock(); defer { lock.unlock() }
        if let v = byURL[chapter.url] { return v }
        if let idx = chapter.index, let v = byIndex[idx] { return v }
        return nil
    }

    func asMap() -> [String: String] {
        var out: [String: String] = [:]
        for ch in chapters { if let v = get(ch) { out[ch.url] = v } }
        return out
    }
}

/// LegadoTeam single-file JavaScript Book Source (`mainJs` / `.js` import) engine
/// + cover/content image byte decryption (`coverDecodeJs`, `ruleContent.imageDecode`).
enum LegadoJsSource {
    static func looksLikeJsSource(_ raw: String) -> Bool {
        var t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("\u{FEFF}") { t.removeFirst() }
        guard !t.isEmpty, !t.hasPrefix("{"), !t.hasPrefix("[") else { return false }
        return t.contains("var config") || t.contains("const config") || t.contains("let config")
            || t.contains("function search") || t.contains("function getChapters")
    }

    static func extract(_ jsText: String) throws -> LegadoSource {
        var probe = LegadoSource(bookSourceUrl: "js-source://init", bookSourceName: "JS", searchUrl: nil)
        probe.mainJs = jsText
        let ctx = LegadoContext(source: probe)
        let inspect = """
        \(LegadoJS.relaxed(jsText))
        ;({
            config: (typeof config === 'object' && config) ? config : {},
            hasSearch: typeof search === 'function',
            hasExplore: typeof explore === 'function',
            hasBookInfo: typeof getBookInfo === 'function',
            hasChapters: typeof getChapters === 'function',
            hasContent: typeof getContent === 'function',
            hasContentBatch: typeof getContentBatch === 'function',
            hasLogin: typeof login === 'function',
            hasLoginV2: typeof loginUi === 'function' && typeof loginAction === 'function',
            hasReview: typeof getReviewSummary === 'function' && typeof getReviewDetail === 'function'
        });
        """
        guard let meta = try ctx.evalJS(inspect, result: nil, baseURL: probe.bookSourceUrl) as? [String: Any],
              let cfg = meta["config"] as? [String: Any] else {
            throw LegadoError.rule("JS 书源配置解析失败")
        }
        let url = (cfg["bookSourceUrl"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { throw LegadoError.rule("JS 书源缺少 config.bookSourceUrl") }
        let hasSearch = meta["hasSearch"] as? Bool == true
        let hasExplore = meta["hasExplore"] as? Bool == true
        let hasChapters = meta["hasChapters"] as? Bool == true
        let hasContent = meta["hasContent"] as? Bool == true
        guard hasSearch || hasExplore else { throw LegadoError.rule("JS 书源缺少 search 或 explore 函数") }
        guard hasChapters && hasContent else { throw LegadoError.rule("JS 书源缺少 getChapters 或 getContent 函数") }

        var src = LegadoSource(bookSourceUrl: url, bookSourceName: (cfg["bookSourceName"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? url, searchUrl: nil)
        src.bookSourceType = cfg["bookSourceType"] as? Int ?? 0
        src.bookSourceGroup = cfg["bookSourceGroup"] as? String
        src.bookSourceComment = cfg["bookSourceComment"] as? String
        src.mainJs = jsText
        if let expArr = cfg["exploreUrl"], JSONSerialization.isValidJSONObject(expArr),
           let d = try? JSONSerialization.data(withJSONObject: expArr) {
            src.exploreUrl = String(decoding: d, as: UTF8.self)
        } else if let expStr = cfg["exploreUrl"] as? String {
            src.exploreUrl = expStr
        }
        if meta["hasLoginV2"] as? Bool == true {
            src.loginUi = #"{"version":2}"#
        } else if let lui = cfg["loginUi"], JSONSerialization.isValidJSONObject(lui),
                  let d = try? JSONSerialization.data(withJSONObject: lui) {
            src.loginUi = String(decoding: d, as: UTF8.self)
        } else if let lstr = cfg["loginUi"] as? String {
            src.loginUi = lstr
        }
        if meta["hasReview"] as? Bool == true { src.ruleReview = ["enabled": "true"] }
        return src
    }

    static func hasLogin(_ s: LegadoSource) -> Bool {
        let js = s.mainJs ?? ""
        return js.contains("function login(") || hasLoginV2(s)
    }

    static func hasLoginV2(_ s: LegadoSource) -> Bool {
        let js = s.mainJs ?? ""
        return js.contains("function loginUi(") && js.contains("function loginAction(")
    }

    static func hasReview(_ s: LegadoSource) -> Bool {
        let js = s.mainJs ?? ""
        return js.contains("function getReviewSummary(") && js.contains("function getReviewDetail(")
    }

    private static func jsonLiteral(_ value: Any) -> String {
        if let s = value as? String {
            let d = (try? JSONSerialization.data(withJSONObject: [s])) ?? Data("[\"\"]".utf8)
            let arr = String(decoding: d, as: UTF8.self)
            return String(arr.dropFirst().dropLast())
        }
        guard JSONSerialization.isValidJSONObject(value), let d = try? JSONSerialization.data(withJSONObject: value) else { return "null" }
        return String(decoding: d, as: UTF8.self)
    }

    static func run(_ s: LegadoSource, expr: String, baseURL: String? = nil, key: String? = nil, page: Int? = nil, ctx: LegadoContext? = nil) throws -> Any? {
        let c = ctx ?? LegadoContext(source: s)
        c.key = key; c.page = page
        let code = LegadoJS.relaxed(s.mainJs ?? "") + "\n;(" + expr + ");"
        return try c.evalJS(code, result: nil, baseURL: baseURL ?? s.bookSourceUrl)
    }

    static func search(_ s: LegadoSource, key: String, page: Int = 1, category: LegadoClient.ExploreCategory? = nil) throws -> [LegadoSearchItem] {
        let expr: String
        if let cat = category {
            expr = "typeof explore === 'function' ? explore(\(jsonLiteral(cat.url)), \(page)) : []"
        } else {
            expr = "typeof search === 'function' ? search(\(jsonLiteral(key)), \(page)) : []"
        }
        let raw = try run(s, expr: expr, key: key, page: page)
        let list = raw as? [[String: Any]] ?? []
        var out: [LegadoSearchItem] = []
        var seen = Set<String>()
        for o in list {
            let name = LegadoClient.stripInvisible(o["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let url = (o["bookUrl"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, !url.isEmpty else { continue }
            let abs = LegadoURL.absolute(url, base: s.bookSourceUrl)
            guard seen.insert(abs).inserted else { continue }
            let coverRaw = o["coverUrl"] as? String ?? ""
            out.append(LegadoSearchItem(
                name: name,
                author: (o["author"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                kind: o["kind"] as? String ?? "",
                intro: o["intro"] as? String ?? "",
                cover: coverRaw.isEmpty ? "" : LegadoURL.absolute(coverRaw, base: s.bookSourceUrl),
                latest: (o["latestChapterTitle"] as? String) ?? (o["lastChapter"] as? String) ?? "",
                bookURL: abs,
                sourceURL: s.bookSourceUrl,
                sourceName: s.bookSourceName
            ))
        }
        return out
    }

    static func info(_ s: LegadoSource, bookURL: String) throws -> LegadoBookInfo {
        let ctx = LegadoContext(source: s)
        ctx.book = ["bookUrl": bookURL, "tocUrl": bookURL, "origin": s.bookSourceUrl, "originName": s.bookSourceName]
        let raw = try run(s, expr: "typeof getBookInfo === 'function' ? getBookInfo(\(jsonLiteral(ctx.book))) : null", baseURL: bookURL, ctx: ctx)
        let o = raw as? [String: Any] ?? [:]
        let tocRaw = o["tocUrl"] as? String ?? ""
        let coverRaw = o["coverUrl"] as? String ?? ""
        let dl: [String] = (o["downloadUrls"] as? [String]) ?? ((o["downloadUrls"] as? String)?.components(separatedBy: .newlines).filter { !$0.isEmpty } ?? [])
        return LegadoBookInfo(
            name: o["name"] as? String ?? "",
            author: o["author"] as? String ?? "",
            intro: o["intro"] as? String ?? "",
            cover: coverRaw.isEmpty ? "" : LegadoURL.absolute(coverRaw, base: bookURL),
            kind: o["kind"] as? String ?? "",
            latest: (o["latestChapterTitle"] as? String) ?? (o["lastChapter"] as? String) ?? "",
            tocURL: tocRaw.isEmpty ? bookURL : LegadoURL.absolute(tocRaw, base: bookURL),
            wordCount: o["wordCount"] as? String ?? "",
            updateTime: o["updateTime"] as? String ?? "",
            downloadUrls: dl
        )
    }

    static func toc(_ s: LegadoSource, tocURL: String, book: [String: Any] = [:]) throws -> [LegadoChapter] {
        let ctx = LegadoContext(source: s)
        var b = book
        if b["bookUrl"] == nil { b["bookUrl"] = tocURL }
        b["tocUrl"] = tocURL
        ctx.book = b
        let raw = try run(s, expr: "getChapters(\(jsonLiteral(b)))", baseURL: tocURL, ctx: ctx)
        let list = raw as? [[String: Any]] ?? []
        var out: [LegadoChapter] = []
        let bookName = b["name"] as? String ?? ""
        for o in list {
            let titleRaw = ((o["title"] as? String) ?? (o["name"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let url = (o["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let isVol = o["isVolume"] as? Bool ?? false
            guard !titleRaw.isEmpty, !(isVol && url.isEmpty) else { continue }
            let title = LegadoReplaceStore.shared.applyTitle(titleRaw, bookName: bookName, sourceURL: s.bookSourceUrl, sourceName: s.bookSourceName)
            out.append(LegadoChapter(
                title: title,
                url: url.isEmpty ? tocURL : LegadoURL.absolute(url, base: tocURL),
                index: out.count,
                isVolume: isVol,
                isVip: o["isVip"] as? Bool ?? false,
                isPay: o["isPay"] as? Bool ?? false,
                updateTime: o["updateTime"] as? String ?? ""
            ))
        }
        return out
    }

    static func content(_ s: LegadoSource, chapter: LegadoChapter, nextChapterURL: String?, book: [String: Any] = [:]) throws -> String {
        let ctx = LegadoContext(source: s)
        ctx.book = book
        ctx.chapter = ["title": chapter.title, "url": chapter.url, "index": chapter.index ?? 0]
        ctx.nextChapterURL = nextChapterURL
        let chDict: [String: Any] = ["title": chapter.title, "url": chapter.url, "index": chapter.index ?? 0]
        let nextLit = nextChapterURL.map(jsonLiteral) ?? "null"
        let raw = LegadoAnalyzer.textOf(try run(s, expr: "getContent(\(jsonLiteral(chDict)), \(jsonLiteral(book)), \(nextLit))", baseURL: chapter.url, ctx: ctx))
        let cleaned = LegadoContent.cleanPreservingImages(raw, baseURL: chapter.url)
        let bookName = book["name"] as? String ?? ""
        let purified = LegadoReplaceStore.shared.applyContent(cleaned, bookName: bookName, sourceURL: s.bookSourceUrl, sourceName: s.bookSourceName)
        guard !purified.isEmpty else { throw LegadoError.empty("JS 书源返回空正文") }
        return purified
    }

    static func contentBatch(_ s: LegadoSource, chapters: [LegadoChapter], book: [String: Any] = [:]) throws -> [String: String] {
        let ctx = LegadoContext(source: s)
        ctx.book = book
        let batch = LegadoBatchContext(chapters: chapters)
        ctx.batch = batch
        let chArr = chapters.map { ["title": $0.title, "url": $0.url, "index": $0.index ?? 0] as [String: Any] }
        let res = try run(s, expr: "typeof getContentBatch === 'function' ? getContentBatch(\(jsonLiteral(chArr)), \(jsonLiteral(book))) : null", baseURL: s.bookSourceUrl, ctx: ctx)
        let bookName = book["name"] as? String ?? ""
        var out: [String: String] = [:]
        func accept(_ url: String, _ raw: String) {
            let c = LegadoReplaceStore.shared.applyContent(LegadoContent.cleanPreservingImages(raw, baseURL: url), bookName: bookName, sourceURL: s.bookSourceUrl, sourceName: s.bookSourceName)
            if !c.isEmpty { out[url] = c }
        }
        for (u, raw) in batch.asMap() { accept(u, raw) }
        if let map = res as? [String: Any] {
            for (k, v) in map { accept(k, LegadoAnalyzer.textOf(v)) }
        } else if let arr = res as? [Any] {
            for i in 0..<min(arr.count, chapters.count) { accept(chapters[i].url, LegadoAnalyzer.textOf(arr[i])) }
        }
        return out
    }

    /// Decodes obfuscated image or cover bytes via `ruleContent.imageDecode` or `coverDecodeJs`.
    static func decodeImageBytes(_ s: LegadoSource, imageURL: String, data: Data, isCover: Bool = false) -> Data {
        let raw = (isCover ? s.coverDecodeJs : s.ruleContent?["imageDecode"])?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !raw.isEmpty else { return data }
        var code = raw
        if code.lowercased().hasPrefix("@js:") { code.removeFirst(4) }
        else if code.lowercased().hasPrefix("<js>") { code = code.replacingOccurrences(of: "<js>", with: "").replacingOccurrences(of: "</js>", with: "") }
        let ctx = LegadoContext(source: s)
        guard let out = try? ctx.evalJS(code, result: LegadoBytes.json(data), baseURL: imageURL) else { return data }
        if let arr = out as? [Any] { return LegadoBytes.data(arr) }
        if let b64 = out as? String, let decoded = Data(base64Encoded: b64.trimmingCharacters(in: .whitespacesAndNewlines)) { return decoded }
        return data
    }
}
