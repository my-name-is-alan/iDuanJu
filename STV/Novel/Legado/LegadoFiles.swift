import Foundation
import CryptoKit

/// Sandboxed per-source file cache, script loader, ZIP reader, and Chinese numeral chapter helper for iOS.
enum LegadoFiles {
    private static let maxBytes = 16 * 1024 * 1024
    private static let maxEntries = 512
    private static let lock = NSLock()
    nonisolated(unsafe) private static var memCache: [String: (exp: TimeInterval, data: Data)] = [:]

    private static var rootDir: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("legado-files", isDirectory: true)
    }

    private static func sha256(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private static func baseDir(for sourceID: String) -> URL {
        let dir = rootDir.appendingPathComponent(String(sha256(sourceID).prefix(16)), isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func safeURL(sourceID: String, relPath: String) throws -> URL {
        let base = baseDir(for: sourceID).standardizedFileURL
        let cleaned = relPath.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\\", with: "/")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !cleaned.isEmpty else { throw LegadoError.rule("文件路径不能为空") }
        let candidate = base.appendingPathComponent(cleaned).standardizedFileURL
        guard candidate.path == base.path || candidate.path.hasPrefix(base.path + "/") else {
            throw LegadoError.rule("路径超出书源沙箱目录")
        }
        return candidate
    }

    static func fetchCached(_ urlStr: String, ctx: LegadoContext, saveTimeSec: Int = 0) throws -> Data {
        let key = ctx.source.bookSourceUrl + "|" + urlStr
        let now = Date().timeIntervalSince1970
        lock.lock()
        if let hit = memCache[key], hit.exp == 0 || hit.exp > now { lock.unlock(); return hit.data }
        lock.unlock()

        let base = baseDir(for: ctx.source.bookSourceUrl)
        let hash = String(sha256(urlStr).prefix(24))
        let binURL = base.appendingPathComponent("cache_\(hash).bin")
        let expURL = base.appendingPathComponent("cache_\(hash).exp")
        if let cached = try? Data(contentsOf: binURL) {
            let exp = (try? String(contentsOf: expURL, encoding: .utf8)).flatMap { Double($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? 0
            if exp == 0 || exp > now {
                lock.lock(); memCache[key] = (exp, cached); lock.unlock()
                return cached
            }
        }
        let req = try LegadoURL.request(urlStr, ctx: ctx, base: ctx.source.bookSourceUrl)
        let resp = try req.fetch()
        guard resp.body.count <= maxBytes else { throw LegadoError.rule("文件过大") }
        let exp: TimeInterval = saveTimeSec > 0 ? now + Double(saveTimeSec) : 0
        lock.lock(); memCache[key] = (exp, resp.body); lock.unlock()
        try? resp.body.write(to: binURL, options: .atomic)
        try? String(exp).write(to: expURL, atomically: true, encoding: .utf8)
        return resp.body
    }

    static func importScript(_ pathOrURL: String, ctx: LegadoContext) throws -> String {
        let t = pathOrURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return "" }
        if t.lowercased().hasPrefix("http://") || t.lowercased().hasPrefix("https://") {
            return String(decoding: try fetchCached(t, ctx: ctx, saveTimeSec: 0), as: UTF8.self)
        }
        guard let d = readFile(sourceID: ctx.source.bookSourceUrl, path: t) else { return "" }
        return String(decoding: d, as: UTF8.self)
    }

    static func cacheFile(_ urlStr: String, ctx: LegadoContext, saveTimeSec: Int = 0) throws -> String {
        let t = urlStr.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return "" }
        return String(decoding: try fetchCached(t, ctx: ctx, saveTimeSec: saveTimeSec), as: UTF8.self)
    }

    static func downloadFile(_ urlStr: String, ctx: LegadoContext) throws -> String {
        let data = try fetchCached(urlStr.trimmingCharacters(in: .whitespacesAndNewlines), ctx: ctx, saveTimeSec: 0)
        let ext = URL(string: urlStr)?.pathExtension.prefix(8).filter(\.isLetterOrDigit) ?? ""
        let rel = "dl_\(sha256(urlStr).prefix(20))" + (ext.isEmpty ? ".bin" : ".\(ext)")
        let target = try safeURL(sourceID: ctx.source.bookSourceUrl, relPath: rel)
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: target, options: .atomic)
        return rel
    }

    static func readFile(sourceID: String, path: String) -> Data? {
        guard let u = try? safeURL(sourceID: sourceID, relPath: path),
              let attrs = try? FileManager.default.attributesOfItem(atPath: u.path),
              let sz = attrs[.size] as? Int, sz <= maxBytes else { return nil }
        return try? Data(contentsOf: u)
    }

    static func readTxtFile(sourceID: String, path: String, charset: String? = nil) -> String {
        guard let d = readFile(sourceID: sourceID, path: path) else { return "" }
        return String(data: d, encoding: LegadoHTTP.encoding(charset) ?? .utf8) ?? String(decoding: d, as: UTF8.self)
    }

    static func deleteFile(sourceID: String, path: String) -> Bool {
        guard let u = try? safeURL(sourceID: sourceID, relPath: path) else { return false }
        return (try? FileManager.default.removeItem(at: u)) != nil
    }

    static func getTxtInFolder(sourceID: String, relDir: String) -> String {
        guard let dir = try? safeURL(sourceID: sourceID, relPath: relDir),
              let items = try? FileManager.default.subpathsOfDirectory(atPath: dir.path) else { return "" }
        var out: [String] = []
        var total = 0
        for rel in items.sorted() where rel.lowercased().hasSuffix(".txt") {
            let u = dir.appendingPathComponent(rel)
            if let d = try? Data(contentsOf: u), total + d.count <= maxBytes {
                total += d.count
                out.append(String(decoding: d, as: UTF8.self))
            }
        }
        return out.joined(separator: "\n")
    }

    /// Minimal ZIP parser (stored method 0 and raw-deflate method 8 via `LegadoBytes.inflate`).
    static func extractZipEntries(_ zipData: Data) throws -> [(name: String, data: Data)] {
        let b = [UInt8](zipData)
        guard b.count >= 22 else { return [] }
        func u16(_ i: Int) -> Int { Int(b[i]) | (Int(b[i + 1]) << 8) }
        func u32(_ i: Int) -> Int { Int(b[i]) | (Int(b[i + 1]) << 8) | (Int(b[i + 2]) << 16) | (Int(b[i + 3]) << 24) }
        // Locate End of Central Directory (PK\x05\x06)
        var eocd = -1
        let minPos = max(0, b.count - 65557)
        for i in stride(from: b.count - 22, through: minPos, by: -1) {
            if b[i] == 0x50 && b[i + 1] == 0x4B && b[i + 2] == 0x05 && b[i + 3] == 0x06 { eocd = i; break }
        }
        guard eocd >= 0 else { return [] }
        let entryCount = min(u16(eocd + 10), maxEntries)
        var cd = u32(eocd + 16)
        var out: [(String, Data)] = []
        var totalBytes = 0
        for _ in 0..<entryCount {
            guard cd + 46 <= b.count, b[cd] == 0x50 && b[cd + 1] == 0x4B && b[cd + 2] == 0x01 && b[cd + 3] == 0x02 else { break }
            let method = u16(cd + 10)
            let compSize = u32(cd + 20)
            let uncompSize = u32(cd + 24)
            let nameLen = u16(cd + 28)
            let extraLen = u16(cd + 30)
            let commentLen = u16(cd + 32)
            let localOff = u32(cd + 42)
            guard cd + 46 + nameLen <= b.count else { break }
            let name = String(bytes: b[(cd + 46)..<(cd + 46 + nameLen)], encoding: .utf8) ?? ""
            cd += 46 + nameLen + extraLen + commentLen
            if name.hasSuffix("/") { continue }
            guard uncompSize <= maxBytes, totalBytes + uncompSize <= maxBytes else { throw LegadoError.rule("解压内容过大") }
            guard localOff + 30 <= b.count, b[localOff] == 0x50 && b[localOff + 1] == 0x4B && b[localOff + 2] == 0x03 && b[localOff + 3] == 0x04 else { continue }
            let lNameLen = u16(localOff + 26)
            let lExtraLen = u16(localOff + 28)
            let dataStart = localOff + 30 + lNameLen + lExtraLen
            guard dataStart + compSize <= b.count else { continue }
            let slice = Data(b[dataStart..<(dataStart + compSize)])
            let content: Data
            if method == 0 { content = slice }
            else if method == 8 { content = try LegadoBytes.inflate(slice, raw: true) }
            else { continue }
            totalBytes += content.count
            out.append((name, content))
        }
        return out
    }

    static func unzipFile(sourceID: String, zipPath: String) throws -> String {
        guard let zipData = readFile(sourceID: sourceID, path: zipPath) else { throw LegadoError.rule("压缩文件不存在：\(zipPath)") }
        let destRel = "unzip_\(sha256(zipPath).prefix(16))"
        let entries = try extractZipEntries(zipData)
        for (name, content) in entries {
            let target = try safeURL(sourceID: sourceID, relPath: destRel + "/" + name)
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try content.write(to: target, options: .atomic)
        }
        return destRel
    }

    static func getZipEntryBytes(_ urlOrPath: String, entryPath: String, ctx: LegadoContext) throws -> Data? {
        let raw: Data
        if urlOrPath.lowercased().hasPrefix("http://") || urlOrPath.lowercased().hasPrefix("https://") {
            raw = try fetchCached(urlOrPath, ctx: ctx, saveTimeSec: 0)
        } else {
            guard let d = readFile(sourceID: ctx.source.bookSourceUrl, path: urlOrPath) else { return nil }
            raw = d
        }
        let want = entryPath.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        for (name, data) in try extractZipEntries(raw) {
            let n = name.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            if n == want || n.hasSuffix("/" + want) { return data }
        }
        return nil
    }

    /// Chinese numeral chapter normalizer (`第一百二十三章` → `第123章`).
    static func toNumChapter(_ title: String) -> String {
        guard let re = try? NSRegularExpression(pattern: #"(第\s*)([零〇一二两三四五六七八九十百千万亿]+)(\s*[章节回卷集部篇话])"#) else { return title }
        var out = title
        for m in re.matches(in: title, range: NSRange(title.startIndex..., in: title)).reversed() {
            guard let whole = Range(m.range, in: out),
                  let g1 = Range(m.range(at: 1), in: title),
                  let g2 = Range(m.range(at: 2), in: title),
                  let g3 = Range(m.range(at: 3), in: title),
                  let num = chineseToInt(String(title[g2])) else { continue }
            out.replaceSubrange(whole, with: "\(title[g1])\(num)\(title[g3])")
        }
        return out
    }

    private static func chineseToInt(_ raw: String) -> Int64? {
        guard !raw.isEmpty else { return nil }
        let digits: [Character: Int64] = ["零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9]
        let units: [Character: Int64] = ["十": 10, "百": 100, "千": 1000]
        if raw.allSatisfy({ digits[$0] != nil }) {
            return raw.reduce(Int64(0)) { $0 * 10 + digits[$1]! }
        }
        var total: Int64 = 0, section: Int64 = 0, number: Int64 = 0
        for c in raw {
            if let d = digits[c] { number = d }
            else if let u = units[c] { section += ((number == 0 && u == 10) ? 1 : number) * u; number = 0 }
            else if c == "万" { total += (section + number) * 10_000; section = 0; number = 0 }
            else if c == "亿" { total += (section + number) * 100_000_000; section = 0; number = 0 }
            else { return nil }
        }
        return total + section + number
    }
}

private extension Character {
    var isLetterOrDigit: Bool { isLetter || isNumber }
}
