import Foundation
import Combine

enum LegadoSourceImportError: LocalizedError {
    case unsupportedFormat(String)
    var errorDescription: String? {
        if case .unsupportedFormat(let format) = self { return "暂不支持\(format)运行时；不是坏源，请勿当作文本书源导入" }
        return nil
    }
}

/// One 「阅读」(Legado / LegadoTeam) book source, RSS source, or single-file JS source (`mainJs`).
/// Supports all 5 Legado bookSourceTypes: 0 text, 1 audio, 2 image/manga, 3 file/download, 4 video.
struct LegadoSource: Codable, Hashable, Identifiable {
    var bookSourceUrl: String
    var bookSourceName: String
    var bookSourceGroup: String?
    var bookSourceType: Int?
    var bookUrlPattern: String?
    var bookSourceComment: String?
    var variableComment: String?
    var enabled: Bool?
    var enabledExplore: Bool?
    var enabledCookieJar: Bool?
    var concurrentRate: String?
    var header: String?
    var searchUrl: String?
    var exploreUrl: String?
    var exploreScreen: String?
    var loginUrl: String?
    var loginUi: String?
    var jsLib: String?
    var loginCheckJs: String?
    var coverDecodeJs: String?
    var mainJs: String?
    var eventListener: Bool?
    var customButton: Bool?
    var ruleSearch: [String: String]?
    var ruleExplore: [String: String]?
    var ruleBookInfo: [String: String]?
    var ruleToc: [String: String]?
    var ruleContent: [String: String]?
    var ruleReview: [String: String]?
    var customOrder: Int?
    var rssRules: [String: String]?

    var isRSS: Bool { rssRules != nil }
    var isJsSource: Bool { !(mainJs ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var isMedia: Bool { !isRSS && [1, 4].contains(bookSourceType ?? 0) }
    var isImage: Bool { !isRSS && (bookSourceType ?? 0) == 2 }
    var isFile: Bool { !isRSS && (bookSourceType ?? 0) == 3 }
    var cookieJarEnabled: Bool { enabledCookieJar ?? true }
    var maxBatchSize: Int {
        if let raw = ruleContent?["maxBatchSize"], let n = Int(raw) { return min(max(0, n), 50) }
        return isJsSource ? 10 : 0
    }
    var supportsContentBatch: Bool {
        isJsSource || (!(ruleContent?["contentBatch"] ?? "").isEmpty && maxBatchSize > 1)
    }

    var id: String { bookSourceUrl }
    var isOn: Bool { enabled ?? true }
    var hasLogin: Bool {
        !(loginUrl ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
        !(loginUi ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
        (isJsSource && LegadoJsSource.hasLogin(self))
    }
    var supported: Bool {
        !bookSourceUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && (
            isRSS || isJsSource || (
                [0, 1, 2, 3, 4].contains(bookSourceType ?? 0) &&
                (!(searchUrl ?? "").isEmpty || !(exploreUrl ?? "").isEmpty)
            )
        )
    }

    enum CodingKeys: String, CodingKey {
        case bookSourceUrl, bookSourceName, bookSourceGroup, bookSourceType, bookUrlPattern,
             bookSourceComment, variableComment, enabled, enabledExplore, enabledCookieJar,
             concurrentRate, header, searchUrl, exploreUrl, exploreScreen, loginUrl, loginUi,
             jsLib, loginCheckJs, coverDecodeJs, mainJs, eventListener, customButton,
             ruleSearch, ruleExplore, ruleBookInfo, ruleToc, ruleContent, ruleReview,
             customOrder, rssRules
    }

    init(bookSourceUrl: String, bookSourceName: String, searchUrl: String?, ruleSearch: [String: String]? = nil,
         ruleBookInfo: [String: String]? = nil, ruleToc: [String: String]? = nil, ruleContent: [String: String]? = nil,
         header: String? = nil) {
        self.bookSourceUrl = bookSourceUrl
        self.bookSourceName = bookSourceName
        self.searchUrl = searchUrl
        self.ruleSearch = ruleSearch
        self.ruleBookInfo = ruleBookInfo
        self.ruleToc = ruleToc
        self.ruleContent = ruleContent
        self.header = header
    }

    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        bookSourceUrl = try c.decode(String.self, forKey: .bookSourceUrl)
        bookSourceName = (try? c.decode(String.self, forKey: .bookSourceName)) ?? bookSourceUrl
        bookSourceGroup = Self.str(c, .bookSourceGroup)
        bookSourceType = (try? c.decode(Int.self, forKey: .bookSourceType)) ?? Self.str(c, .bookSourceType).flatMap(Int.init)
        bookUrlPattern = Self.str(c, .bookUrlPattern)
        bookSourceComment = Self.str(c, .bookSourceComment)
        variableComment = Self.str(c, .variableComment)
        enabled = try? c.decode(Bool.self, forKey: .enabled)
        enabledExplore = try? c.decode(Bool.self, forKey: .enabledExplore)
        enabledCookieJar = try? c.decode(Bool.self, forKey: .enabledCookieJar)
        concurrentRate = Self.str(c, .concurrentRate)
        header = Self.str(c, .header)
        searchUrl = Self.str(c, .searchUrl)
        if let entries = try? c.decode([[String: LegadoAny]].self, forKey: .exploreUrl) {
            let objects = entries.map { $0.compactMapValues(\.text) }
            exploreUrl = (try? JSONSerialization.data(withJSONObject: objects)).map { String(decoding: $0, as: UTF8.self) }
        } else { exploreUrl = Self.str(c, .exploreUrl) }
        exploreScreen = Self.str(c, .exploreScreen)
        loginUrl = Self.str(c, .loginUrl)
        if let entries = try? c.decode([[String: LegadoAny]].self, forKey: .loginUi) {
            let objects = entries.map { $0.compactMapValues(\.text) }
            loginUi = (try? JSONSerialization.data(withJSONObject: objects)).map { String(decoding: $0, as: UTF8.self) }
        } else { loginUi = Self.str(c, .loginUi) }
        jsLib = Self.str(c, .jsLib)
        loginCheckJs = Self.str(c, .loginCheckJs)
        coverDecodeJs = Self.str(c, .coverDecodeJs)
        mainJs = Self.str(c, .mainJs)
        eventListener = try? c.decode(Bool.self, forKey: .eventListener)
        customButton = try? c.decode(Bool.self, forKey: .customButton)
        ruleSearch = Self.rules(c, .ruleSearch)
        ruleExplore = Self.rules(c, .ruleExplore)
        ruleBookInfo = Self.rules(c, .ruleBookInfo)
        ruleToc = Self.rules(c, .ruleToc)
        ruleContent = Self.rules(c, .ruleContent)
        ruleReview = Self.rules(c, .ruleReview)
        customOrder = try? c.decode(Int.self, forKey: .customOrder)
        rssRules = Self.rules(c, .rssRules)
    }

    private static func str(_ c: KeyedDecodingContainer<CodingKeys>, _ k: CodingKeys) -> String? {
        if let s = try? c.decode(String.self, forKey: k) { return s }
        if let v = try? c.decode(LegadoAny.self, forKey: k) { return v.text }
        return nil
    }

    private static func rules(_ c: KeyedDecodingContainer<CodingKeys>, _ k: CodingKeys) -> [String: String]? {
        if let m = try? c.decode([String: LegadoAny].self, forKey: k) { return m.compactMapValues { $0.text } }
        if let s = try? c.decode(String.self, forKey: k), let d = s.data(using: .utf8),
           let m = try? JSONDecoder().decode([String: LegadoAny].self, from: d) { return m.compactMapValues { $0.text } }
        return nil
    }

    func prettyJSON() -> String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        return (try? enc.encode(self)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
    }
}

/// Any JSON value, flattened to text (strings as-is, others re-serialized).
struct LegadoAny: Codable, Hashable {
    var text: String?
    init(from d: Decoder) throws {
        let c = try d.singleValueContainer()
        if let s = try? c.decode(String.self) { text = s }
        else if let b = try? c.decode(Bool.self) { text = b ? "true" : "false" }
        else if let i = try? c.decode(Int.self) { text = String(i) }
        else if let x = try? c.decode(Double.self) { text = String(x) }
        else if c.decodeNil() { text = nil }
        else if let m = try? c.decode([String: LegadoAny].self) {
            let o = m.compactMapValues { $0.text }
            text = (try? JSONSerialization.data(withJSONObject: o)).map { String(decoding: $0, as: UTF8.self) }
        } else if let a = try? c.decode([LegadoAny].self) {
            text = (try? JSONSerialization.data(withJSONObject: a.compactMap(\.text))).map { String(decoding: $0, as: UTF8.self) }
        } else { text = nil }
    }
    func encode(to e: Encoder) throws { var c = e.singleValueContainer(); try c.encode(text) }
}

/// Installed book sources (Application Support/NovelSources.json).
@MainActor
final class LegadoSourceStore: ObservableObject {
    static let shared = LegadoSourceStore()
    @Published private(set) var sources: [LegadoSource] = []

    nonisolated static var file: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("NovelSources.json")
    }

    private init() {
        sources = Self.warm ?? Self.read()
        Self.warm = nil
        Self.prewarmed = true
    }

    private nonisolated static func read() -> [LegadoSource] {
        (try? Data(contentsOf: file)).flatMap { try? JSONDecoder().decode([LegadoSource].self, from: $0) } ?? []
    }

    private static var warm: [LegadoSource]?
    private static var prewarmed = false

    static func prewarm() async {
        guard !prewarmed else { return }
        prewarmed = true
        warm = await Task.detached(priority: .utility) { Self.read() }.value
        _ = shared
        warm = nil
    }

    var enabled: [LegadoSource] { sources.filter { $0.isOn && $0.supported } }
    func source(_ url: String) -> LegadoSource? { sources.first { $0.bookSourceUrl == url } }

    /// Parses a Legado JSON export (array or single object), RSS source, or single-file JS source (`.js`).
    nonisolated static func parse(_ data: Data) throws -> [LegadoSource] {
        var trimmed = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("\u{FEFF}") { trimmed.removeFirst() }
        if LegadoJsSource.looksLikeJsSource(trimmed) {
            return [try LegadoJsSource.extract(trimmed)]
        }
        let d = Data(trimmed.utf8)
        let object = try JSONSerialization.jsonObject(with: d)
        let rows = (object as? [Any])?.compactMap { $0 as? [String: Any] } ?? (object as? [String: Any]).map { [$0] } ?? []
        if rows.contains(where: { $0["sites"] != nil || $0["spider"] != nil || $0["lives"] != nil }) {
            throw LegadoSourceImportError.unsupportedFormat(" TVBox ")
        }
        let normalized = rows.map { row -> [String: Any] in
            guard let url = row["sourceUrl"] as? String, row["bookSourceUrl"] == nil || row["ruleArticles"] != nil || row["sortUrl"] != nil else { return row }
            var out = row
            out["bookSourceUrl"] = url
            out["bookSourceName"] = row["sourceName"] as? String ?? url
            out["rssRules"] = row
            return out
        }
        let normalizedData = try JSONSerialization.data(withJSONObject: normalized)
        return try JSONDecoder().decode([LossyItem].self, from: normalizedData).compactMap(\.value)
    }

    private struct LossyItem: Decodable {
        var value: LegadoSource?
        init(from d: Decoder) throws { value = try? LegadoSource(from: d) }
    }

    @discardableResult
    func add(_ list: [LegadoSource]) -> (added: Int, updated: Int, unsupported: Int) {
        var added = 0, updated = 0, unsupported = 0
        for s in list {
            if !s.supported { unsupported += 1 }
            if let i = sources.firstIndex(where: { $0.bookSourceUrl == s.bookSourceUrl }) { sources[i] = s; updated += 1 }
            else { sources.append(s); added += 1 }
        }
        save()
        return (added, updated, unsupported)
    }

    func update(_ s: LegadoSource, replacing oldID: String? = nil) {
        let targetID = oldID ?? s.bookSourceUrl
        if let i = sources.firstIndex(where: { $0.bookSourceUrl == targetID }) {
            sources[i] = s
        } else {
            sources.append(s)
        }
        save()
    }

    func importText(_ text: String) throws -> (added: Int, updated: Int, unsupported: Int) {
        add(try Self.parse(Data(text.utf8)))
    }

    func importURL(_ url: URL) async throws -> (added: Int, updated: Int, unsupported: Int) {
        var req = URLRequest(url: url, timeoutInterval: 30)
        req.setValue(LegadoHTTP.userAgent, forHTTPHeaderField: "User-Agent")
        let (d, _) = try await URLSession.shared.data(for: req)
        return add(try Self.parse(d))
    }

    func setEnabled(_ s: LegadoSource, _ on: Bool) {
        guard let i = sources.firstIndex(where: { $0.id == s.id }) else { return }
        sources[i].enabled = on
        save()
    }

    func remove(_ ids: Set<String>) {
        sources.removeAll { ids.contains($0.id) }
        save()
    }

    func reloadFromDisk() {
        sources = Self.read()
    }

    private func save() {
        try? FileManager.default.createDirectory(at: Self.file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(sources).write(to: Self.file)
    }
}
