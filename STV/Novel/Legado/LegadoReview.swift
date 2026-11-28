import Foundation

/// Paragraph review (段评) engine supporting both `ruleReview` and single-file JS source review hooks.
enum LegadoReview {
    struct Summary: Hashable {
        var counts: [Int: Int]
        var paraData: [Int: String]
    }

    struct Comment: Hashable, Identifiable {
        var id: String
        var name: String
        var avatar: String = ""
        var replyToName: String = ""
        var badges: [String] = []
        var content: String = ""
        var imageURL: String = ""
        var time: String = ""
        var likeCount: Int = 0
        var replyCount: Int = 0
    }

    struct DetailPage: Hashable {
        var items: [Comment]
        var nextPageURL: String? = nil
    }

    static func isEnabled(_ s: LegadoSource) -> Bool {
        if s.isJsSource { return LegadoJsSource.hasReview(s) }
        guard let r = s.ruleReview, !r.isEmpty else { return false }
        if r["enabled"] == "false" { return false }
        return !(r["reviewSummaryUrl"] ?? "").isEmpty || !(r["reviewUrl"] ?? "").isEmpty
    }

    static func summary(_ s: LegadoSource, chapter: LegadoChapter, book: [String: Any] = [:]) throws -> Summary {
        if s.isJsSource { return try summaryJS(s, chapter: chapter, book: book) }
        let r = s.ruleReview ?? [:]
        let urlRule = (r["reviewSummaryUrl"] ?? "").isEmpty ? (r["reviewUrl"] ?? "") : r["reviewSummaryUrl"]!
        guard !urlRule.isEmpty else { return Summary(counts: [:], paraData: [:]) }
        let ctx = LegadoContext(source: s)
        ctx.book = book
        ctx.chapter = ["title": chapter.title, "url": chapter.url, "index": chapter.index ?? 0]
        let req = try LegadoURL.request(urlRule, ctx: ctx, base: chapter.url)
        let resp = try LegadoClient.loginChecked(s, try req.fetch(), ctx: ctx)
        let page = LegadoAnalyzer(resp.text, baseURL: resp.url.absoluteString, ctx: ctx)
        guard let listRule = r["summaryListRule"], !listRule.isEmpty else { return Summary(counts: [:], paraData: [:]) }
        var counts: [Int: Int] = [:], tokens: [Int: String] = [:]
        for (i, el) in try page.elements(listRule).enumerated() {
            let a = LegadoAnalyzer(el, baseURL: resp.url.absoluteString, ctx: ctx)
            let pIdx = (r["summaryParagraphIndexRule"].flatMap { try? a.string($0) }.flatMap(Int.init)) ?? (i + 1)
            let cnt = (r["summaryCountRule"].flatMap { try? a.string($0) }.flatMap(Int.init)) ?? 1
            if cnt > 0 { counts[pIdx] = cnt }
            if let dr = r["summaryParagraphDataRule"], !dr.isEmpty, let tok = try? a.string(dr), !tok.isEmpty { tokens[pIdx] = tok }
        }
        return Summary(counts: counts, paraData: tokens)
    }

    static func detail(_ s: LegadoSource, chapter: LegadoChapter, paraIndex: Int, paraData: String = "", page: Int = 1, book: [String: Any] = [:]) throws -> DetailPage {
        if s.isJsSource { return try detailJS(s, chapter: chapter, paraIndex: paraIndex, paraData: paraData, page: page, book: book) }
        let r = s.ruleReview ?? [:]
        let urlRule = (r["reviewDetailUrl"] ?? "").isEmpty ? (r["reviewUrl"] ?? "") : r["reviewDetailUrl"]!
        guard !urlRule.isEmpty else { return DetailPage(items: []) }
        let ctx = LegadoContext(source: s)
        ctx.book = book
        ctx.key = paraData; ctx.page = page
        ctx.putVar("paraIndex", String(paraIndex))
        ctx.putVar("paraData", paraData)
        ctx.chapter = ["title": chapter.title, "url": chapter.url, "index": chapter.index ?? 0]
        let req = try LegadoURL.request(urlRule, ctx: ctx, base: chapter.url)
        let resp = try LegadoClient.loginChecked(s, try req.fetch(), ctx: ctx)
        let doc = LegadoAnalyzer(resp.text, baseURL: resp.url.absoluteString, ctx: ctx)
        let list = (r["detailListRule"].flatMap { try? doc.elements($0) }) ?? []
        var items: [Comment] = []
        for (idx, el) in list.enumerated() {
            let a = LegadoAnalyzer(el, baseURL: resp.url.absoluteString, ctx: ctx)
            func v(_ k: String) -> String { (r[k].flatMap { $0.isEmpty ? nil : (try? a.string($0)) }) ?? "" }
            let text = v("detailContentRule").isEmpty ? v("contentRule") : v("detailContentRule")
            guard !text.isEmpty else { continue }
            items.append(Comment(
                id: v("detailIdRule").isEmpty ? "c_\(page)-\(idx)" : v("detailIdRule"),
                name: v("detailNameRule").isEmpty ? "书友" : v("detailNameRule"),
                avatar: v("detailAvatarRule").isEmpty ? v("avatarRule") : v("detailAvatarRule"),
                badges: v("detailBadgeRule").components(separatedBy: .newlines).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty },
                content: text,
                time: v("postTimeRule")
            ))
        }
        let next = r["reviewDetailNextPageUrl"].flatMap { $0.isEmpty ? nil : (try? doc.string($0)) }.flatMap { $0.isEmpty ? nil : $0 }
        return DetailPage(items: items, nextPageURL: next)
    }

    static func replies(_ s: LegadoSource, chapter: LegadoChapter, paraIndex: Int, paraData: String, reviewId: String, page: Int = 1, book: [String: Any] = [:]) throws -> DetailPage {
        guard s.isJsSource else { return DetailPage(items: []) }
        let ch: [String: Any] = ["title": chapter.title, "url": chapter.url, "index": chapter.index ?? 0]
        let raw = try LegadoJsSource.run(s, expr: "typeof getReviewReplies === 'function' ? getReviewReplies(\(lit(ch)), \(lit(book)), \(paraIndex), \(lit(paraData)), \(lit(reviewId)), \(page)) : {items:[]}", baseURL: chapter.url)
        return parseJSPage(raw)
    }

    private static func summaryJS(_ s: LegadoSource, chapter: LegadoChapter, book: [String: Any]) throws -> Summary {
        let ch: [String: Any] = ["title": chapter.title, "url": chapter.url, "index": chapter.index ?? 0]
        let raw = try LegadoJsSource.run(s, expr: "typeof getReviewSummary === 'function' ? getReviewSummary(\(lit(ch)), \(lit(book))) : []", baseURL: chapter.url)
        let arr = raw as? [[String: Any]] ?? []
        var counts: [Int: Int] = [:], data: [Int: String] = [:]
        for (i, o) in arr.enumerated() {
            let idx = (o["paraIndex"] as? Int) ?? (i + 1)
            let cnt = o["count"] as? Int ?? 0
            if cnt > 0 { counts[idx] = cnt }
            if let pd = o["paraData"] as? String, !pd.isEmpty { data[idx] = pd }
        }
        return Summary(counts: counts, paraData: data)
    }

    private static func detailJS(_ s: LegadoSource, chapter: LegadoChapter, paraIndex: Int, paraData: String, page: Int, book: [String: Any]) throws -> DetailPage {
        let ch: [String: Any] = ["title": chapter.title, "url": chapter.url, "index": chapter.index ?? 0]
        let raw = try LegadoJsSource.run(s, expr: "typeof getReviewDetail === 'function' ? getReviewDetail(\(lit(ch)), \(lit(book)), \(paraIndex), \(lit(paraData)), \(page)) : {items:[]}", baseURL: chapter.url)
        return parseJSPage(raw)
    }

    private static func parseJSPage(_ raw: Any?) -> DetailPage {
        guard let obj = raw as? [String: Any], let arr = obj["items"] as? [[String: Any]] else { return DetailPage(items: []) }
        let items = arr.enumerated().map { idx, o -> Comment in
            let cObj = o["content"] as? [String: Any]
            let text = (cObj?["text"] as? String) ?? (o["content"] as? String) ?? ""
            return Comment(
                id: (o["id"] as? String) ?? "r_\(idx)",
                name: (o["name"] as? String) ?? "书友",
                avatar: o["avatar"] as? String ?? "",
                replyToName: cObj?["replyToName"] as? String ?? "",
                badges: o["badges"] as? [String] ?? [],
                content: text,
                imageURL: cObj?["img"] as? String ?? "",
                time: (cObj?["time"] as? String) ?? (o["time"] as? String) ?? "",
                likeCount: (cObj?["likeCount"] as? Int) ?? (o["likeCount"] as? Int) ?? 0,
                replyCount: (cObj?["replyCount"] as? Int) ?? (o["replyCount"] as? Int) ?? 0
            )
        }
        let next = (obj["nextPageUrl"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return DetailPage(items: items, nextPageURL: next)
    }

    private static func lit(_ v: Any) -> String {
        if let s = v as? String {
            let d = (try? JSONSerialization.data(withJSONObject: [s])) ?? Data("[\"\"]".utf8)
            let a = String(decoding: d, as: UTF8.self)
            return String(a.dropFirst().dropLast())
        }
        guard JSONSerialization.isValidJSONObject(v), let d = try? JSONSerialization.data(withJSONObject: v) else { return "null" }
        return String(decoding: d, as: UTF8.self)
    }
}
