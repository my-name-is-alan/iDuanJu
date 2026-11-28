import Foundation
import UIKit

/// Full-fidelity local backup & smart-merge restore (`.idjbackup`).
///
/// Why this exists:
/// - When iOS sideload users switch signing certificates (or a certificate gets revoked) or move to a new device,
///   iOS `installd` forbids overwriting an app signed with a different `TeamIdentifier`.
/// - `AppBackup` packages all installed plugins (including JS sources, hashes, extra domains, settings & cookies),
///   subscriptions, MacCMS sites, TVBox repos, watch history, favorites, source gates/order, Legado book sources,
///   novel shelf + progress, manga favorites + progress, and Telegram channel lists into one compact `.idjbackup` file.
/// - Alongside `UIFileSharingEnabled = true`, `writeAutoBackupIfNeeded()` keeps `Documents/AutoBackup/latest.idjbackup`
///   up to date so users can still copy their backup out from Apple's built-in "Files" app even after a certificate revoke.
enum AppBackup {
    static let fileExtension = "idjbackup"
    static let magicString = "idjbackup-v1"
    private static let binaryHeader = Data([0x49, 0x44, 0x4A, 0x42, 0x01]) // "IDJB" + v1

    struct Archive: Codable {
        var magic: String = AppBackup.magicString
        var version: Int = 1
        var createdAt: Date = Date()
        var appVersion: String
        var defaultsPlistBase64: String?
        var pluginsIndexBase64: String?
        /// `<safePluginDir>` -> `<fileName>` -> base64 bytes
        var pluginFiles: [String: [String: String]] = [:]
        /// Relative path under `Application Support/tvbox/` -> base64 bytes
        var tvboxFiles: [String: String] = [:]
        var legadoSourcesBase64: String?
        /// Relative path under `Documents/Novels/` -> base64 bytes
        var novelFiles: [String: String] = [:]
        /// Cross-platform iOS <-> Android smart-merge payload (optional for backward compatibility).
        var portable: PortableData? = nil
        /// Android-only prefs blob (preserved untouched if present).
        var androidPrefsBase64: String? = nil
    }

    struct PortableData: Codable {
        struct Progress: Codable {
            var sid: String
            var title: String
            var cover: String
            var epIndex: Int
            var ep: Int
            var sec: Double
            var dur: Double
            var updatedAtMs: Int64
            var epTitle: String? = nil
            var epGroup: String? = nil
        }
        struct Favorite: Codable {
            var sid: String
            var title: String
            var cover: String
            var updatedAtMs: Int64
        }
        struct CMSSiteItem: Codable {
            var key: String
            var name: String
            var api: String
            var adult: Bool? = nil
            var enabled: Bool? = nil
            var origin: String? = nil
        }
        struct PluginBundleItem: Codable {
            var id: String
            var enabled: Bool? = nil
            var origin: String? = nil
            var bundleText: String
            var storageJson: String? = nil
        }
        struct TGChannelItem: Codable {
            var username: String
            var title: String? = nil
            var isAdult: Bool? = nil
        }
        var progress: [Progress]? = nil
        var favorites: [Favorite]? = nil
        var searchHistory: [String]? = nil
        var searchHistoryMeta: SearchHistoryMeta? = nil
        var cmsSites: [CMSSiteItem]? = nil
        var pluginBundles: [PluginBundleItem]? = nil
        var tgChannels: [TGChannelItem]? = nil
        var adultUnlocked: Bool? = nil
    }

    struct RestoreSummary: Equatable {
        var plugins: Int = 0
        var cmsSites: Int = 0
        var favorites: Int = 0
        var history: Int = 0
        var bookSources: Int = 0
        var books: Int = 0

        var toastText: String {
            var parts: [String] = []
            if plugins > 0 { parts.append("插件 \(plugins) 个") }
            if cmsSites > 0 { parts.append("采集站 \(cmsSites) 个") }
            if favorites > 0 { parts.append("收藏 \(favorites) 部") }
            if history > 0 { parts.append("历史 \(history) 条") }
            if bookSources > 0 { parts.append("书源 \(bookSources) 个") }
            if books > 0 { parts.append("书籍 \(books) 本") }
            return parts.isEmpty ? "已恢复备份设置" : "已恢复：" + parts.joined(separator: " · ")
        }
    }

    enum BackupError: LocalizedError {
        case invalidFormat
        case corruptData

        var errorDescription: String? {
            switch self {
            case .invalidFormat: return "不是有效的 i短剧 备份文件（.idjbackup）"
            case .corruptData: return "备份文件已损坏或版本不受支持"
            }
        }
    }

    nonisolated static func isBackupData(_ data: Data) -> Bool {
        if data.count >= binaryHeader.count, data.prefix(binaryHeader.count) == binaryHeader {
            return true
        }
        guard data.first == UInt8(ascii: "{") else { return false }
        let head = String(decoding: data.prefix(256), as: UTF8.self)
        return head.contains("\"\(magicString)\"")
    }

    // MARK: Export

    static func createBackupData() async throws -> Data {
        await MainActor.run { LibraryStore.shared.flush() }
        await TVBoxStore.shared.flushForBackup()

        let defaultsSnap: [String: Any] = await MainActor.run {
            let all = UserDefaults.standard.dictionaryRepresentation()
            var filtered: [String: Any] = [:]
            for (k, v) in all where shouldBackupDefaultsKey(k) {
                if PropertyListSerialization.propertyList(v, isValidFor: .binary) {
                    filtered[k] = v
                }
            }
            return filtered
        }

        let appVer: String = await MainActor.run {
            let info = Bundle.main.infoDictionary
            let s = info?["CFBundleShortVersionString"] as? String ?? "1.6"
            let b = info?["CFBundleVersion"] as? String ?? "0"
            return "\(s) (\(b))"
        }

        let portableSnap: PortableData = await MainActor.run { buildPortableSnapshot() }

        return try await Task.detached(priority: .userInitiated) {
            let fm = FileManager.default
            var archive = Archive(appVersion: appVer)
            archive.portable = portableSnap

            if !defaultsSnap.isEmpty {
                let plist = try PropertyListSerialization.data(fromPropertyList: defaultsSnap, format: .binary, options: 0)
                archive.defaultsPlistBase64 = plist.base64EncodedString()
            }

            // 1. Installed plugins & per-plugin files
            let pluginsRoot = PluginPaths.root
            let indexURL = pluginsRoot.appendingPathComponent("index.json")
            if let indexData = try? Data(contentsOf: indexURL) {
                archive.pluginsIndexBase64 = indexData.base64EncodedString()
            }
            if let children = try? fm.contentsOfDirectory(at: pluginsRoot, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) {
                for dir in children {
                    guard (try? dir.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else { continue }
                    let dirName = dir.lastPathComponent
                    guard isSafeSingleName(dirName) else { continue }
                    var filesMap: [String: String] = [:]
                    let items = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey], options: [.skipsHiddenFiles])) ?? []
                    for f in items {
                        let name = f.lastPathComponent
                        guard name != "plugin.log", isSafeSingleName(name) else { continue }
                        let rv = try? f.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
                        guard rv?.isRegularFile == true, (rv?.fileSize ?? 0) <= (4 << 20),
                              let d = try? Data(contentsOf: f) else { continue }
                        filesMap[name] = d.base64EncodedString()
                    }
                    if !filesMap.isEmpty {
                        archive.pluginFiles[dirName] = filesMap
                    }
                }
            }

            // 2. TVBox configs & local attachments (skip runtime script cache)
            let tvboxRoot = TVBoxStore.backupRoot
            var tvboxBytes = 0
            let stateFile = tvboxRoot.appendingPathComponent("tvbox_store_v2.json")
            if let d = try? Data(contentsOf: stateFile), d.count <= (4 << 20) {
                archive.tvboxFiles["tvbox_store_v2.json"] = d.base64EncodedString()
                tvboxBytes += d.count
            }
            for sub in ["repos", "local"] {
                let subDir = tvboxRoot.appendingPathComponent(sub, isDirectory: true)
                let items = (try? fm.contentsOfDirectory(at: subDir, includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey], options: [.skipsHiddenFiles])) ?? []
                for f in items {
                    let name = f.lastPathComponent
                    guard isSafeSingleName(name) else { continue }
                    let rv = try? f.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
                    let size = rv?.fileSize ?? 0
                    guard rv?.isRegularFile == true, size <= (8 << 20), tvboxBytes + size <= (24 << 20),
                          let d = try? Data(contentsOf: f) else { continue }
                    archive.tvboxFiles["\(sub)/\(name)"] = d.base64EncodedString()
                    tvboxBytes += d.count
                }
            }

            // 3. Legado book sources
            if let legadoData = try? Data(contentsOf: LegadoSourceStore.file), legadoData.count <= (32 << 20) {
                archive.legadoSourcesBase64 = legadoData.base64EncodedString()
            }

            // 4. Imported local novel texts & covers (skip web `ch/` chapter cache)
            let novelsRoot = NovelStore.root
            var novelBytes = 0
            if let bookDirs = try? fm.contentsOfDirectory(at: novelsRoot, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) {
                for dir in bookDirs {
                    guard (try? dir.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else { continue }
                    let bookID = dir.lastPathComponent
                    guard isSafeSingleName(bookID) else { continue }
                    for leaf in ["book.txt", "cover"] {
                        let f = dir.appendingPathComponent(leaf)
                        guard let rv = try? f.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                              rv.isRegularFile == true,
                              let size = rv.fileSize, size <= (12 << 20),
                              novelBytes + size <= (32 << 20),
                              let d = try? Data(contentsOf: f) else { continue }
                        archive.novelFiles["\(bookID)/\(leaf)"] = d.base64EncodedString()
                        novelBytes += d.count
                    }
                }
            }

            return try encodeArchive(archive)
        }.value
    }

    @MainActor
    static func exportTemporaryFile() async throws -> URL {
        let data = try await createBackupData()
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyyMMdd-HHmm"
        let stamp = df.string(from: Date())
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("BackupExport", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("i短剧备份-\(stamp).\(fileExtension)")
        try data.write(to: url, options: .atomic)
        // Keep Documents/AutoBackup synchronized whenever the user exports manually.
        Task.detached(priority: .utility) { writeAutoBackupFile(data) }
        return url
    }

    // MARK: Restore (Smart Merge)

    static func restore(from rawData: Data) async throws -> RestoreSummary {
        guard isBackupData(rawData) else { throw BackupError.invalidFormat }
        await MainActor.run { LibraryStore.shared.flush() }
        await TVBoxStore.shared.flushForBackup()

        let decoded: (archive: Archive, legadoSources: [LegadoSource]) = try await Task.detached(priority: .userInitiated) {
            let archive = try decodeArchive(rawData)
            guard archive.magic == magicString else { throw BackupError.invalidFormat }
            let fm = FileManager.default

            // 1. Restore plugin directories & merge index.json
            let pluginsRoot = PluginPaths.root
            for (dirName, files) in archive.pluginFiles where isSafeSingleName(dirName) {
                let targetDir = pluginsRoot.appendingPathComponent(dirName, isDirectory: true)
                try? fm.createDirectory(at: targetDir, withIntermediateDirectories: true)
                for (fileName, b64) in files where isSafeSingleName(fileName) {
                    guard let bytes = Data(base64Encoded: b64) else { continue }
                    try? bytes.write(to: targetDir.appendingPathComponent(fileName), options: .atomic)
                }
            }
            if let idxB64 = archive.pluginsIndexBase64,
               let idxData = Data(base64Encoded: idxB64),
               let incomingPlugins = try? JSONDecoder().decode([InstalledPlugin].self, from: idxData) {
                let indexURL = pluginsRoot.appendingPathComponent("index.json")
                let existingPlugins = (try? Data(contentsOf: indexURL)).flatMap { try? JSONDecoder().decode([InstalledPlugin].self, from: $0) } ?? []
                let incomingIDs = Set(incomingPlugins.map(\.id))
                // Incoming records win for restored plugins so fileHashes match restored JS files; extra local plugins stay.
                let merged = incomingPlugins + existingPlugins.filter { !incomingIDs.contains($0.id) }
                if let out = try? JSONEncoder().encode(merged) {
                    try? out.write(to: indexURL, options: .atomic)
                }
            }

            // 2. Restore TVBox files
            if !archive.tvboxFiles.isEmpty {
                let tvboxRoot = TVBoxStore.backupRoot
                try? fm.createDirectory(at: tvboxRoot, withIntermediateDirectories: true)
                for (rel, b64) in archive.tvboxFiles where rel != "tvbox_store_v2.json" && isSafeRelativePath(rel) {
                    guard let bytes = Data(base64Encoded: b64) else { continue }
                    let dest = tvboxRoot.appendingPathComponent(rel)
                    try? fm.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try? bytes.write(to: dest, options: .atomic)
                }
                if let stateB64 = archive.tvboxFiles["tvbox_store_v2.json"],
                   let stateBytes = Data(base64Encoded: stateB64) {
                    let stateFile = tvboxRoot.appendingPathComponent("tvbox_store_v2.json")
                    if let curData = try? Data(contentsOf: stateFile),
                       let curObj = LenientJson.objectOrNull(String(decoding: curData, as: UTF8.self)),
                       let incObj = LenientJson.objectOrNull(String(decoding: stateBytes, as: UTF8.self)) {
                        var curState = TVBoxState.fromJSON(curObj)
                        var incState = TVBoxState.fromJSON(incObj)
                        let incRepoIDs = Set(incState.repos.map(\.id))
                        incState.repos += curState.repos.filter { !incRepoIDs.contains($0.id) }
                        let incDepotIDs = Set(incState.depots.map(\.id))
                        incState.depots += curState.depots.filter { !incDepotIDs.contains($0.id) }
                        if incState.active.isEmpty { incState.active = curState.active }
                        curState = incState
                        try? Data(curState.toJSON().toString().utf8).write(to: stateFile, options: .atomic)
                    } else {
                        try? stateBytes.write(to: stateFile, options: .atomic)
                    }
                }
            }

            // 3. Restore local novel files
            if !archive.novelFiles.isEmpty {
                let novelsRoot = NovelStore.root
                for (rel, b64) in archive.novelFiles where isSafeRelativePath(rel) {
                    guard let bytes = Data(base64Encoded: b64) else { continue }
                    let dest = novelsRoot.appendingPathComponent(rel)
                    if !fm.fileExists(atPath: dest.path) {
                        try? fm.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
                        try? bytes.write(to: dest, options: .atomic)
                    }
                }
            }

            // 4. Parse Legado book sources off-main
            var legadoList: [LegadoSource] = []
            if let legB64 = archive.legadoSourcesBase64,
               let legData = Data(base64Encoded: legB64) {
                legadoList = (try? JSONDecoder().decode([LegadoSource].self, from: legData))
                    ?? (try? LegadoSourceStore.parse(legData))
                    ?? []
            }
            return (archive, legadoList)
        }.value

        // 5. Merge UserDefaults & reload live stores on MainActor
        await MainActor.run {
            if let plistB64 = decoded.archive.defaultsPlistBase64,
               let plistData = Data(base64Encoded: plistB64),
               let incoming = (try? PropertyListSerialization.propertyList(from: plistData, options: [], format: nil)) as? [String: Any] {
                mergeDefaults(incoming, into: UserDefaults.standard)
            }
            if let portable = decoded.archive.portable {
                applyPortable(portable)
            }
            PluginStore.shared.reloadFromDisk()
            CMSStore.shared.reloadFromDefaults()
            SourceGate.shared.reloadFromDefaults()
            LibraryStore.shared.reloadFromDefaults()
            MangaStore.shared.reloadFromDefaults()
            NovelStore.shared.reloadFromDefaults()
            NovelPrefs.shared.reloadFromDefaults()
            TGStore.shared.reloadFromDefaults()
            MusicStore.shared.reloadFromDefaults()
            if !decoded.legadoSources.isEmpty {
                _ = LegadoSourceStore.shared.add(decoded.legadoSources)
            } else {
                LegadoSourceStore.shared.reloadFromDisk()
            }
        }
        await TVBoxStore.shared.reloadFromDisk()

        let summary = await MainActor.run {
            RestoreSummary(
                plugins: PluginStore.shared.plugins.count,
                cmsSites: CMSStore.shared.sites.count,
                favorites: LibraryStore.shared.allFavorites.count,
                history: LibraryStore.shared.progress.count,
                bookSources: LegadoSourceStore.shared.sources.count,
                books: NovelStore.shared.books.count
            )
        }
        Task { await writeAutoBackupIfNeeded(force: true) }
        return summary
    }

    // MARK: Automatic Files-App Anti-Revoke Snapshot

    private nonisolated(unsafe) static var lastAutoBackupAt: Date?
    private nonisolated static let autoLock = NSLock()

    static func writeAutoBackupIfNeeded(force: Bool = false) async {
        // Skip during XCTest / screenshot harness runs.
        let env = ProcessInfo.processInfo.environment
        if env["XCTestConfigurationFilePath"] != nil || env["STV_SHOTS"] == "1" { return }

        let hasUserData = await MainActor.run {
            !PluginStore.shared.plugins.isEmpty
                || !PluginStore.shared.subscriptions.isEmpty
                || !CMSStore.shared.sites.isEmpty
                || !LibraryStore.shared.allFavorites.isEmpty
                || !LibraryStore.shared.progress.isEmpty
                || !LegadoSourceStore.shared.sources.isEmpty
                || !NovelStore.shared.books.isEmpty
                || !MangaStore.shared.favorites.isEmpty
        }
        guard hasUserData else { return }

        let shouldRun: Bool = autoLock.withLock {
            if !force, let last = lastAutoBackupAt, Date().timeIntervalSince(last) < 45 {
                return false
            }
            lastAutoBackupAt = Date()
            return true
        }
        guard shouldRun else { return }

        guard let data = try? await createBackupData() else { return }
        await Task.detached(priority: .utility) {
            writeAutoBackupFile(data)
        }.value
    }

    private nonisolated static func writeAutoBackupFile(_ data: Data) {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("AutoBackup", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let latest = dir.appendingPathComponent("latest.\(fileExtension)")
        try? data.write(to: latest, options: .atomic)
        let readme = dir.appendingPathComponent("防掉签恢复说明.txt")
        if !FileManager.default.fileExists(atPath: readme.path) {
            let note = """
            i短剧 自动防掉签备份目录
            ------------------------
            1. latest.idjbackup 会在 App 退到后台或导出备份时自动更新，包含你的已安装插件、片源、观看进度、收藏、书源与小说进度。
            2. 如果以后签名证书过期/掉签导致无法打开 App，请【先不要急着删数据】：
               先在系统自带「文件」App →「我的 iPhone」→「i短剧」→「AutoBackup」中把 latest.idjbackup 复制保存到别的目录（如 iCloud 云盘或下载项）。
            3. 卸载旧包并安装新签名的 i短剧 后，打开「我的 → 数据备份与迁移 → 从备份文件恢复」，选择 latest.idjbackup 即可一键无损恢复。
            """
            try? Data(note.utf8).write(to: readme, options: .atomic)
        }
    }

    // MARK: Serialization & Merge Helpers

    nonisolated static func encodeArchive(_ archive: Archive) throws -> Data {
        let json = try JSONEncoder().encode(archive)
        if let compressed = try? (json as NSData).compressed(using: .zlib) as Data {
            var out = binaryHeader
            out.append(compressed)
            return out
        }
        return json
    }

    nonisolated static func decodeArchive(_ raw: Data) throws -> Archive {
        let jsonData: Data
        if raw.count >= binaryHeader.count, raw.prefix(binaryHeader.count) == binaryHeader {
            let payload = raw.dropFirst(binaryHeader.count) as NSData
            guard let decompressed = try? payload.decompressed(using: .zlib) as Data else {
                throw BackupError.corruptData
            }
            jsonData = decompressed
        } else {
            jsonData = raw
        }
        guard let archive = try? JSONDecoder().decode(Archive.self, from: jsonData) else {
            throw BackupError.corruptData
        }
        return archive
    }

    private nonisolated static func shouldBackupDefaultsKey(_ k: String) -> Bool {
        guard k.hasPrefix("stv.") || k.hasPrefix("novel.") || k.hasPrefix("legado.") else { return false }
        // WebKit HttpOnly session cookies & private WebDAV server credentials never go into backup archives.
        if k.hasPrefix("stv.hg.web.") || k.hasPrefix("stv.webdav.") { return false }
        return true
    }

    @MainActor
    private static func mergeDefaults(_ incoming: [String: Any], into d: UserDefaults) {
        for (key, value) in incoming where shouldBackupDefaultsKey(key) {
            switch key {
            case "stv.progress":
                if let incData = value as? Data,
                   let incMap = try? JSONDecoder().decode([String: WatchProgress].self, from: incData) {
                    let curMap = d.data(forKey: key).flatMap { try? JSONDecoder().decode([String: WatchProgress].self, from: $0) } ?? [:]
                    let merged = curMap.merging(incMap) { cur, inc in inc.updatedAt >= cur.updatedAt ? inc : cur }
                    if let out = try? JSONEncoder().encode(merged) { d.set(out, forKey: key) }
                }
            case "stv.favorites":
                if let incData = value as? Data,
                   let incList = try? JSONDecoder().decode([HGSeries].self, from: incData) {
                    let curList = d.data(forKey: key).flatMap { try? JSONDecoder().decode([HGSeries].self, from: $0) } ?? []
                    if let out = try? JSONEncoder().encode(unionByID(incList, curList, id: \.id)) { d.set(out, forKey: key) }
                }
            case "stv.searchHistory", "stv.searchHistoryMeta":
                // Handled atomically right after the loop so items and tombstones always merge together.
                break
            case "stv.plugins.subscriptions":
                if let incData = value as? Data,
                   let incList = try? JSONDecoder().decode([PluginSubscription].self, from: incData) {
                    let curList = d.data(forKey: key).flatMap { try? JSONDecoder().decode([PluginSubscription].self, from: $0) } ?? []
                    if let out = try? JSONEncoder().encode(unionByID(incList, curList, id: \.url)) { d.set(out, forKey: key) }
                }
            case "stv.cms.sites":
                if let incData = value as? Data,
                   let incList = try? JSONDecoder().decode([CMSSite].self, from: incData) {
                    let curList = d.data(forKey: key).flatMap { try? JSONDecoder().decode([CMSSite].self, from: $0) } ?? []
                    if let out = try? JSONEncoder().encode(unionByID(incList, curList, id: \.key)) { d.set(out, forKey: key) }
                }
            case "stv.manga.favorites":
                if let incData = value as? Data,
                   let incList = try? JSONDecoder().decode([MangaItem].self, from: incData) {
                    let curList = d.data(forKey: key).flatMap { try? JSONDecoder().decode([MangaItem].self, from: $0) } ?? []
                    if let out = try? JSONEncoder().encode(unionByID(incList, curList, id: \.id)) { d.set(out, forKey: key) }
                }
            case "stv.manga.progress":
                if let incData = value as? Data,
                   let incMap = try? JSONDecoder().decode([String: MangaProgress].self, from: incData) {
                    let curMap = d.data(forKey: key).flatMap { try? JSONDecoder().decode([String: MangaProgress].self, from: $0) } ?? [:]
                    let merged = curMap.merging(incMap) { cur, inc in inc.updatedAt >= cur.updatedAt ? inc : cur }
                    if let out = try? JSONEncoder().encode(merged) { d.set(out, forKey: key) }
                }
            case "stv.novel.books":
                if let incData = value as? Data,
                   let incList = try? JSONDecoder().decode([NovelBook].self, from: incData) {
                    let curList = d.data(forKey: key).flatMap { try? JSONDecoder().decode([NovelBook].self, from: $0) } ?? []
                    if let out = try? JSONEncoder().encode(unionByID(incList, curList, id: \.id)) { d.set(out, forKey: key) }
                }
            case "stv.novel.progress":
                if let incData = value as? Data,
                   let incMap = try? JSONDecoder().decode([String: NovelProgress].self, from: incData) {
                    let curMap = d.data(forKey: key).flatMap { try? JSONDecoder().decode([String: NovelProgress].self, from: $0) } ?? [:]
                    let merged = curMap.merging(incMap) { cur, inc in inc.updatedAt >= cur.updatedAt ? inc : cur }
                    if let out = try? JSONEncoder().encode(merged) { d.set(out, forKey: key) }
                }
            case "stv.novel.readTime":
                if let incDict = value as? [String: Double] {
                    let curDict = (d.dictionary(forKey: key) as? [String: Double]) ?? [:]
                    let merged = curDict.merging(incDict) { max($0, $1) }
                    d.set(merged, forKey: key)
                }
            case "stv.tg.channels":
                if let incData = value as? Data,
                   let incList = try? JSONDecoder().decode([TGChannel].self, from: incData) {
                    let curList = d.data(forKey: key).flatMap { try? JSONDecoder().decode([TGChannel].self, from: $0) } ?? []
                    if let out = try? JSONEncoder().encode(unionByID(incList, curList, id: \.id)) { d.set(out, forKey: key) }
                }
            case "stv.tg.rules":
                if let incData = value as? Data,
                   let incList = try? JSONDecoder().decode([TGRule].self, from: incData) {
                    let curList = d.data(forKey: key).flatMap { try? JSONDecoder().decode([TGRule].self, from: $0) } ?? []
                    if let out = try? JSONEncoder().encode(unionByID(incList, curList, id: \.id)) { d.set(out, forKey: key) }
                }
            case "stv.sources.enabled", "stv.tvbox.adult":
                if let incArr = value as? [String] {
                    let curArr = d.stringArray(forKey: key) ?? []
                    d.set(Array(Set(curArr).union(incArr)).sorted(), forKey: key)
                }
            case "stv.home.sourceOrder":
                if let incArr = value as? [String] {
                    let curArr = d.stringArray(forKey: key) ?? []
                    d.set(unionByID(incArr, curArr, id: { $0 }), forKey: key)
                }
            case "stv.sources.huangguo", "stv.sources.adultChannels", "stv.sources.adultManga":
                let incBool = (value as? Bool) ?? false
                d.set(d.bool(forKey: key) || incBool, forKey: key)
            default:
                d.set(value, forKey: key)
            }
        }
        if incoming["stv.searchHistory"] != nil || incoming["stv.searchHistoryMeta"] != nil {
            let curList = d.data(forKey: "stv.searchHistory").flatMap { try? JSONDecoder().decode([String].self, from: $0) } ?? []
            let curMeta = d.data(forKey: "stv.searchHistoryMeta").flatMap { try? JSONDecoder().decode(SearchHistoryMeta.self, from: $0) } ?? SearchHistoryMeta()
            let incList = (incoming["stv.searchHistory"] as? Data).flatMap { try? JSONDecoder().decode([String].self, from: $0) } ?? []
            let incMeta = (incoming["stv.searchHistoryMeta"] as? Data).flatMap { try? JSONDecoder().decode(SearchHistoryMeta.self, from: $0) } ?? SearchHistoryMeta()
            let (merged, mergedMeta) = LibraryStore.mergeSearchHistory(
                localItems: curList,
                localMeta: curMeta,
                incomingItems: incList,
                incomingMeta: incMeta,
                limit: 10
            )
            if let out = try? JSONEncoder().encode(merged) { d.set(out, forKey: "stv.searchHistory") }
            if let outMeta = try? JSONEncoder().encode(mergedMeta) { d.set(outMeta, forKey: "stv.searchHistoryMeta") }
        }
    }

    private nonisolated static func unionByID<T, K: Hashable>(_ primary: [T], _ secondary: [T], id: (T) -> K) -> [T] {
        var seen = Set<K>()
        var out: [T] = []
        for item in primary {
            let k = id(item)
            if seen.insert(k).inserted { out.append(item) }
        }
        for item in secondary {
            let k = id(item)
            if seen.insert(k).inserted { out.append(item) }
        }
        return out
    }

    private nonisolated static func isSafeSingleName(_ name: String) -> Bool {
        !name.isEmpty && name != "." && name != ".." && !name.contains("/") && !name.contains("\\") && !name.contains("\0")
    }

    private nonisolated static func isSafeRelativePath(_ rel: String) -> Bool {
        let parts = rel.split(separator: "/", omittingEmptySubsequences: false)
        return !parts.isEmpty && parts.allSatisfy { isSafeSingleName(String($0)) }
    }

    // MARK: Cross-Platform Portable Bridge (iOS <-> Android)

    private nonisolated static func encodeBase64URL(_ s: String) -> String {
        Data(s.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private nonisolated static func decodeBase64URL(_ s: String) -> String? {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let rem = b.count % 4
        if rem > 0 { b += String(repeating: "=", count: 4 - rem) }
        guard let d = Data(base64Encoded: b) else { return nil }
        return String(data: d, encoding: .utf8)
    }

    private nonisolated static func portableSid(for series: HGSeries) -> String {
        let src = series.source
        if src == .hongguo { return series.seriesId }
        if let k = src.cmsKey { return "cms:\(k):\(series.seriesId)" }
        if let p = src.pluginID { return "plugin:\(p):\(encodeBase64URL(series.seriesId))" }
        if let u = src.tvboxUID { return "tvb:\(u):\(series.seriesId)" }
        if let u = src.tvboxLiveUID { return "tvl:\(u)" }
        if src == .huangguo { return "hg:\(series.seriesId)" }
        if src == .telegram { return "tg:\(series.seriesId)" }
        return "\(src.rawValue):\(series.seriesId)"
    }

    private nonisolated static func parsePortableSid(_ sid: String) -> (DramaSource, String) {
        if sid.hasPrefix("cms:") {
            let rest = String(sid.dropFirst(4))
            if let i = rest.firstIndex(of: ":") {
                let k = String(rest[..<i])
                let id = String(rest[rest.index(after: i)...]).components(separatedBy: "#").first ?? ""
                return (.cms(k), id)
            }
        } else if sid.hasPrefix("plugin:") {
            let parts = sid.split(separator: ":", maxSplits: 2).map(String.init)
            if parts.count == 3, let decoded = decodeBase64URL(parts[2]) {
                return (.plugin(parts[1]), decoded)
            }
        } else if sid.hasPrefix("tvb:") {
            let rest = String(sid.dropFirst(4))
            if let i = rest.firstIndex(of: ":") {
                return (.tvbox(String(rest[..<i])), String(rest[rest.index(after: i)...]))
            }
        } else if sid.hasPrefix("tvl:") {
            let uid = String(sid.dropFirst(4))
            return (.tvboxLive(uid), uid)
        } else if sid.hasPrefix("hg:") {
            return (.huangguo, String(sid.dropFirst(3)))
        } else if sid.hasPrefix("tg:") {
            return (.telegram, String(sid.dropFirst(3)))
        }
        return (.hongguo, sid)
    }

    @MainActor
    private static func buildPortableSnapshot() -> PortableData {
        let lib = LibraryStore.shared
        let progs = lib.progress.values.map { p in
            PortableData.Progress(
                sid: portableSid(for: p.series),
                title: p.series.title,
                cover: p.series.cover,
                epIndex: p.episodeIndex,
                ep: p.episodeNumber,
                sec: p.seconds,
                dur: p.duration,
                updatedAtMs: Int64(p.updatedAt.timeIntervalSince1970 * 1000)
            )
        }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let favs = lib.allFavorites.enumerated().map { idx, s in
            PortableData.Favorite(
                sid: portableSid(for: s),
                title: s.title,
                cover: s.cover,
                updatedAtMs: nowMs - Int64(idx)
            )
        }
        let cmsList = CMSStore.shared.sites.map { s in
            PortableData.CMSSiteItem(key: s.key, name: s.name, api: s.api, adult: s.adult, enabled: s.enabled, origin: s.origin)
        }
        var bundles: [PortableData.PluginBundleItem] = []
        let pluginsRoot = PluginPaths.root
        for p in PluginStore.shared.plugins {
            let dir = pluginsRoot.appendingPathComponent(p.id, isDirectory: true)
            let entryURL = dir.appendingPathComponent(p.manifest.entry)
            if let jsData = try? Data(contentsOf: entryURL),
               let jsText = String(data: jsData, encoding: .utf8),
               let bundleData = try? JSONEncoder().encode(PluginBundle(manifest: p.manifest, files: [p.manifest.entry: jsText])),
               let bundleText = String(data: bundleData, encoding: .utf8) {
                let storageText = (try? Data(contentsOf: dir.appendingPathComponent("storage.json"))).flatMap { String(data: $0, encoding: .utf8) }
                bundles.append(PortableData.PluginBundleItem(id: p.id, enabled: p.enabled, origin: p.origin, bundleText: bundleText, storageJson: storageText))
            }
        }
        let tgs = TGStore.shared.channels.map { c in
            PortableData.TGChannelItem(username: c.username, title: c.title, isAdult: c.isAdult)
        }
        return PortableData(
            progress: progs,
            favorites: favs,
            searchHistory: lib.searchHistory,
            searchHistoryMeta: lib.searchHistoryMeta,
            cmsSites: cmsList,
            pluginBundles: bundles,
            tgChannels: tgs,
            adultUnlocked: SourceGate.shared.showHuangguo
        )
    }

    @MainActor
    private static func applyPortable(_ portable: PortableData) {
        let d = UserDefaults.standard
        if let incProgs = portable.progress, !incProgs.isEmpty {
            var map = d.data(forKey: "stv.progress").flatMap { try? JSONDecoder().decode([String: WatchProgress].self, from: $0) } ?? [:]
            for p in incProgs {
                let (src, sid) = parsePortableSid(p.sid)
                let series = HGSeries(source: src, seriesId: sid, title: p.title, cover: p.cover)
                let wp = WatchProgress(series: series, episodeIndex: p.epIndex, episodeNumber: p.ep, seconds: p.sec, duration: p.dur, updatedAt: Date(timeIntervalSince1970: Double(p.updatedAtMs) / 1000.0))
                if let cur = map[series.id] {
                    if wp.updatedAt >= cur.updatedAt { map[series.id] = wp }
                } else {
                    map[series.id] = wp
                }
            }
            if let out = try? JSONEncoder().encode(map) { d.set(out, forKey: "stv.progress") }
        }
        if let incFavs = portable.favorites, !incFavs.isEmpty {
            let cur = d.data(forKey: "stv.favorites").flatMap { try? JSONDecoder().decode([HGSeries].self, from: $0) } ?? []
            let incomingSeries = incFavs.sorted { $0.updatedAtMs > $1.updatedAtMs }.map { f -> HGSeries in
                let (src, sid) = parsePortableSid(f.sid)
                return HGSeries(source: src, seriesId: sid, title: f.title, cover: f.cover)
            }
            if let out = try? JSONEncoder().encode(unionByID(incomingSeries, cur, id: \.id)) { d.set(out, forKey: "stv.favorites") }
        }
        let incSearch = portable.searchHistory ?? []
        let incSearchMeta = portable.searchHistoryMeta ?? SearchHistoryMeta()
        if !incSearch.isEmpty || incSearchMeta.clearedAt > 0 || !incSearchMeta.deleted.isEmpty {
            let cur = d.data(forKey: "stv.searchHistory").flatMap { try? JSONDecoder().decode([String].self, from: $0) } ?? []
            let curMeta = d.data(forKey: "stv.searchHistoryMeta").flatMap { try? JSONDecoder().decode(SearchHistoryMeta.self, from: $0) } ?? SearchHistoryMeta()
            let (merged, mergedMeta) = LibraryStore.mergeSearchHistory(
                localItems: cur,
                localMeta: curMeta,
                incomingItems: incSearch,
                incomingMeta: incSearchMeta,
                limit: 10
            )
            if let out = try? JSONEncoder().encode(merged) { d.set(out, forKey: "stv.searchHistory") }
            if let outMeta = try? JSONEncoder().encode(mergedMeta) { d.set(outMeta, forKey: "stv.searchHistoryMeta") }
        }
        if let incCms = portable.cmsSites, !incCms.isEmpty {
            let cur = d.data(forKey: "stv.cms.sites").flatMap { try? JSONDecoder().decode([CMSSite].self, from: $0) } ?? []
            let incSites = incCms.map { CMSSite(key: $0.key, name: $0.name, api: $0.api, adult: $0.adult ?? false, enabled: $0.enabled ?? true, origin: $0.origin ?? "同步") }
            if let out = try? JSONEncoder().encode(unionByID(incSites, cur, id: \.key)) { d.set(out, forKey: "stv.cms.sites") }
        }
        if let incTgs = portable.tgChannels, !incTgs.isEmpty {
            let cur = d.data(forKey: "stv.tg.channels").flatMap { try? JSONDecoder().decode([TGChannel].self, from: $0) } ?? []
            let mapped = incTgs.map { TGChannel(username: $0.username, title: $0.title ?? "", isAdult: $0.isAdult ?? false) }
            if let out = try? JSONEncoder().encode(unionByID(mapped, cur, id: \.id)) { d.set(out, forKey: "stv.tg.channels") }
        }
        if let incBundles = portable.pluginBundles, !incBundles.isEmpty {
            let fm = FileManager.default
            let pluginsRoot = PluginPaths.root
            let indexURL = pluginsRoot.appendingPathComponent("index.json")
            var installed = (try? Data(contentsOf: indexURL)).flatMap { try? JSONDecoder().decode([InstalledPlugin].self, from: $0) } ?? []
            var changed = false
            for item in incBundles {
                guard let bData = item.bundleText.data(using: .utf8),
                      let bundle = try? JSONDecoder().decode(PluginBundle.self, from: bData),
                      let js = bundle.files[bundle.manifest.entry] else { continue }
                let pid = bundle.manifest.id
                guard isSafeSingleName(pid), isSafeSingleName(bundle.manifest.entry) else { continue }
                let dir = pluginsRoot.appendingPathComponent(pid, isDirectory: true)
                try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
                let jsData = Data(js.utf8)
                try? jsData.write(to: dir.appendingPathComponent(bundle.manifest.entry), options: .atomic)
                if let mData = try? JSONEncoder().encode(bundle.manifest) {
                    try? mData.write(to: dir.appendingPathComponent("manifest.json"), options: .atomic)
                }
                if let st = item.storageJson, !st.isEmpty {
                    try? Data(st.utf8).write(to: dir.appendingPathComponent("storage.json"), options: .atomic)
                }
                let hash = PluginManifest.fileHash(jsData)
                let rec = InstalledPlugin(
                    manifest: bundle.manifest,
                    enabled: item.enabled ?? true,
                    fileHashes: [bundle.manifest.entry: hash],
                    origin: item.origin ?? "同步"
                )
                if let idx = installed.firstIndex(where: { $0.id == pid }) {
                    installed[idx] = rec
                } else {
                    installed.append(rec)
                }
                changed = true
            }
            if changed, let out = try? JSONEncoder().encode(installed) {
                try? out.write(to: indexURL, options: .atomic)
            }
        }
        if portable.adultUnlocked == true {
            d.set(true, forKey: "stv.sources.huangguo")
        }
    }
}
