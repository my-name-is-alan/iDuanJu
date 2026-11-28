import Foundation
import Combine

/// Legado-compatible text replacement / purification rule (`ReplaceRule`).
struct LegadoReplaceRule: Codable, Hashable, Identifiable {
    var id: Int64
    var name: String
    var group: String?
    var pattern: String
    var replacement: String
    var scope: String?
    var scopeTitle: Bool
    var scopeContent: Bool
    var excludeScope: String?
    var isEnabled: Bool
    var isRegex: Bool
    var timeoutMillisecond: Int64
    var order: Int

    init(
        id: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        name: String = "",
        group: String? = nil,
        pattern: String = "",
        replacement: String = "",
        scope: String? = nil,
        scopeTitle: Bool = false,
        scopeContent: Bool = true,
        excludeScope: String? = nil,
        isEnabled: Bool = true,
        isRegex: Bool = true,
        timeoutMillisecond: Int64 = 3000,
        order: Int = 0
    ) {
        self.id = id
        self.name = name
        self.group = group
        self.pattern = pattern
        self.replacement = replacement
        self.scope = scope
        self.scopeTitle = scopeTitle
        self.scopeContent = scopeContent
        self.excludeScope = excludeScope
        self.isEnabled = isEnabled
        self.isRegex = isRegex
        self.timeoutMillisecond = timeoutMillisecond
        self.order = order
    }

    enum CodingKeys: String, CodingKey {
        case id, name, group, pattern, replacement, scope, scopeTitle, scopeContent, excludeScope, isEnabled, isRegex, timeoutMillisecond, order
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = (try? c.decode(Int64.self, forKey: .id)) ?? Int64(Date().timeIntervalSince1970 * 1000)
        name = (try? c.decode(String.self, forKey: .name)) ?? ""
        group = try? c.decode(String.self, forKey: .group)
        pattern = (try? c.decode(String.self, forKey: .pattern)) ?? ""
        replacement = (try? c.decode(String.self, forKey: .replacement)) ?? ""
        scope = try? c.decode(String.self, forKey: .scope)
        scopeTitle = (try? c.decode(Bool.self, forKey: .scopeTitle)) ?? false
        scopeContent = (try? c.decode(Bool.self, forKey: .scopeContent)) ?? true
        excludeScope = try? c.decode(String.self, forKey: .excludeScope)
        isEnabled = (try? c.decode(Bool.self, forKey: .isEnabled)) ?? true
        isRegex = (try? c.decode(Bool.self, forKey: .isRegex)) ?? true
        timeoutMillisecond = (try? c.decode(Int64.self, forKey: .timeoutMillisecond)) ?? 3000
        order = (try? c.decode(Int.self, forKey: .order)) ?? 0
    }

    func matchesScope(bookName: String, sourceURL: String, sourceName: String = "") -> Bool {
        let targets = [bookName, sourceURL, sourceName].filter { !$0.isEmpty }
        let seps = CharacterSet(charactersIn: ",\n，")
        if let ex = excludeScope?.trimmingCharacters(in: .whitespacesAndNewlines), !ex.isEmpty {
            let toks = ex.components(separatedBy: seps).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            if toks.contains(where: { tok in targets.contains { $0.localizedCaseInsensitiveContains(tok) } }) { return false }
        }
        guard let sc = scope?.trimmingCharacters(in: .whitespacesAndNewlines), !sc.isEmpty else { return true }
        let toks = sc.components(separatedBy: seps).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if toks.isEmpty { return true }
        return toks.contains(where: { tok in targets.contains { $0.localizedCaseInsensitiveContains(tok) } })
    }

    func apply(to text: String) -> String {
        guard !pattern.isEmpty, !text.isEmpty else { return text }
        if !isRegex { return text.replacingOccurrences(of: pattern, with: replacement) }
        guard let re = try? NSRegularExpression(pattern: pattern) else { return text }
        return re.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: replacement)
    }
}

final class LegadoReplaceStore: ObservableObject, @unchecked Sendable {
    static let shared = LegadoReplaceStore()
    @Published private(set) var rules: [LegadoReplaceRule] = []
    private let lock = NSLock()

    private static var file: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("NovelReplaceRules.json")
    }

    private init() {
        rules = Self.read()
    }

    private static func read() -> [LegadoReplaceRule] {
        (try? Data(contentsOf: file)).flatMap { try? JSONDecoder().decode([LegadoReplaceRule].self, from: $0) } ?? []
    }

    func snapshot() -> [LegadoReplaceRule] {
        lock.lock(); defer { lock.unlock() }
        return rules.sorted { $0.order < $1.order }
    }

    func upsert(_ rule: LegadoReplaceRule) {
        lock.lock()
        var list = rules
        if let i = list.firstIndex(where: { $0.id == rule.id }) { list[i] = rule } else { list.append(rule) }
        rules = list
        save(list)
        lock.unlock()
        notify(list)
    }

    func setEnabled(_ id: Int64, _ on: Bool) {
        lock.lock()
        var list = rules
        if let i = list.firstIndex(where: { $0.id == id }) { list[i].isEnabled = on; rules = list; save(list) }
        lock.unlock()
        notify(list)
    }

    func remove(_ id: Int64) {
        lock.lock()
        var list = rules
        list.removeAll { $0.id == id }
        rules = list
        save(list)
        lock.unlock()
        notify(list)
    }

    @discardableResult
    func importJSON(_ raw: String) throws -> Int {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8) else { return 0 }
        let parsed: [LegadoReplaceRule]
        if trimmed.hasPrefix("[") {
            parsed = try JSONDecoder().decode([LegadoReplaceRule].self, from: data)
        } else {
            parsed = [try JSONDecoder().decode(LegadoReplaceRule.self, from: data)]
        }
        let valid = parsed.filter { !$0.pattern.isEmpty }
        lock.lock()
        var list = rules
        for r in valid {
            if let i = list.firstIndex(where: { $0.id == r.id || (!r.name.isEmpty && $0.name == r.name && $0.pattern == r.pattern) }) {
                list[i] = r
            } else { list.append(r) }
        }
        rules = list
        save(list)
        lock.unlock()
        notify(list)
        return valid.count
    }

    func exportJSON() -> String {
        let enc = JSONEncoder(); enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        return (try? enc.encode(snapshot())).map { String(decoding: $0, as: UTF8.self) } ?? "[]"
    }

    func applyContent(_ text: String, bookName: String = "", sourceURL: String = "", sourceName: String = "") -> String {
        var cur = text
        for r in snapshot() where r.isEnabled && r.scopeContent && r.matchesScope(bookName: bookName, sourceURL: sourceURL, sourceName: sourceName) {
            cur = r.apply(to: cur)
        }
        return cur
    }

    func applyTitle(_ title: String, bookName: String = "", sourceURL: String = "", sourceName: String = "") -> String {
        var cur = title
        for r in snapshot() where r.isEnabled && r.scopeTitle && r.matchesScope(bookName: bookName, sourceURL: sourceURL, sourceName: sourceName) {
            cur = r.apply(to: cur)
        }
        return cur
    }

    func replaceAllForTest(_ list: [LegadoReplaceRule]) {
        lock.lock(); rules = list; lock.unlock()
    }

    private func save(_ list: [LegadoReplaceRule]) {
        try? FileManager.default.createDirectory(at: Self.file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(list).write(to: Self.file, options: .atomic)
    }

    private func notify(_ list: [LegadoReplaceRule]) {
        Task { @MainActor in self.rules = list }
    }
}
