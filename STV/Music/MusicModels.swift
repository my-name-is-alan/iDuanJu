import Foundation
import SwiftUI

enum MusicSourceKind: String, Codable, CaseIterable, Identifiable, Hashable {
    case netease
    case qq
    case kugou
    case qishui
    case navidrome
    case emby
    case fnos
    case plugin
    case itunes

    var id: String { rawValue }

    var label: String {
        switch self {
        case .netease: "网易云"
        case .qq: "QQ音乐"
        case .kugou: "酷狗"
        case .qishui: "汽水音乐"
        case .navidrome: "Navidrome"
        case .emby: "Emby"
        case .fnos: "飞牛音乐"
        case .plugin: "插件"
        case .itunes: "iTunes"
        }
    }

    var fullName: String {
        switch self {
        case .netease: "网易云音乐"
        case .qq: "QQ 音乐"
        case .kugou: "酷狗音乐"
        case .qishui: "汽水音乐"
        case .navidrome: "Navidrome / Subsonic"
        case .emby: "Emby / Jellyfin"
        case .fnos: "飞牛音乐 (fnOS)"
        case .plugin: "自定义 .idjplugin"
        case .itunes: "Apple / iTunes 试听"
        }
    }

    var isNAS: Bool {
        self == .navidrome || self == .emby || self == .fnos
    }

    var isOnlineDefault: Bool {
        self == .netease || self == .qq || self == .kugou || self == .qishui
    }
}

enum MusicEntitlement: String, Codable, Hashable {
    case full
    case preview
    case unavailable
}

enum MusicAudioQuality: String, Codable, CaseIterable, Identifiable, Hashable {
    case standard
    case exhigh
    case lossless
    case hires

    var id: String { rawValue }

    var label: String {
        switch self {
        case .standard: "标准音质"
        case .exhigh: "极高音质"
        case .lossless: "无损 FLAC"
        case .hires: "Hi-Res 母带"
        }
    }

    var shortBadge: String {
        switch self {
        case .standard: "128k"
        case .exhigh: "HQ 320k"
        case .lossless: "FLAC"
        case .hires: "Hi-Res"
        }
    }

    var detail: String {
        switch self {
        case .standard: "128kbps MP3/AAC · 省流秒播"
        case .exhigh: "320kbps HQ · 高品质均衡"
        case .lossless: "CD 级无损 FLAC · 会员推荐"
        case .hires: "最高解析度母带 · 优先 Hi-Res / FLAC"
        }
    }
}

struct MusicTrack: Identifiable, Codable, Hashable {
    let id: String
    let source: MusicSourceKind
    let sourceId: String
    let title: String
    let artist: String
    let album: String
    let coverURL: String
    let durationSec: Double
    var qualityLabel: String
    var entitlement: MusicEntitlement
    var streamURL: String?
    var streamHeaders: [String: String]?
    var pluginId: String?
    var extraHash: String?
    var artistId: String? = nil
    var albumId: String? = nil

    var primaryArtistName: String {
        artist.components(separatedBy: "/").first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? artist
    }

    var seed: UInt64 {
        var h: UInt64 = 1469598103934665603
        for b in (id + title + artist).utf8 {
            h ^= UInt64(b)
            h &*= 1099511628211
        }
        return h == 0 ? 7 : h
    }

    var formattedDuration: String {
        let s = max(0, Int(durationSec.rounded()))
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    var sourceDisplayLabel: String {
        if let pid = pluginId, !pid.isEmpty { return pid }
        return source.label
    }
}

struct MusicArtistRef: Hashable, Identifiable {
    let name: String
    let artistId: String?
    let source: MusicSourceKind
    let coverURL: String?
    var id: String { "\(source.rawValue):\(artistId ?? name)" }
}

struct MusicAlbumRef: Hashable, Identifiable {
    let name: String
    let albumId: String?
    let artist: String
    let artistId: String?
    let source: MusicSourceKind
    let coverURL: String?
    var id: String { "\(source.rawValue):\(albumId ?? name)" }
}

struct MusicAlbumSummary: Identifiable, Hashable {
    let id: String
    let name: String
    let artist: String
    let artistId: String?
    let source: MusicSourceKind
    let coverURL: String
    let yearText: String?
    let trackCount: Int
}

struct MusicArtistDetail: Hashable {
    let id: String
    let name: String
    let source: MusicSourceKind
    let coverURL: String
    let bio: String
    let hotSongs: [MusicTrack]
    let albums: [MusicAlbumSummary]
}

struct MusicAlbumDetail: Hashable {
    let id: String
    let name: String
    let artist: String
    let artistId: String?
    let source: MusicSourceKind
    let coverURL: String
    let releaseDate: String?
    let company: String?
    let description: String
    let tracks: [MusicTrack]
}

struct MusicChart: Identifiable, Hashable {
    let id: String
    let source: MusicSourceKind
    let name: String
    let updateFrequency: String
    let updatedLabel: String
    let coverURLs: [String]
    let previewTracks: [MusicTrack]
    var tracks: [MusicTrack]
}

struct MusicPlaylist: Identifiable, Codable, Hashable {
    let id: String
    var name: String
    var subtitle: String
    var createdAt: Date
    var tracks: [MusicTrack]
    var coverURL: String?
    var source: MusicSourceKind?
    var remoteId: String?
    var trackCountHint: Int?
    var playCountText: String?
    var isSubscribed: Bool?

    init(
        id: String,
        name: String,
        subtitle: String,
        createdAt: Date = Date(),
        tracks: [MusicTrack] = [],
        coverURL: String? = nil,
        source: MusicSourceKind? = nil,
        remoteId: String? = nil,
        trackCountHint: Int? = nil,
        playCountText: String? = nil,
        isSubscribed: Bool? = nil
    ) {
        self.id = id
        self.name = name
        self.subtitle = subtitle
        self.createdAt = createdAt
        self.tracks = tracks
        self.coverURL = coverURL
        self.source = source
        self.remoteId = remoteId
        self.trackCountHint = trackCountHint
        self.playCountText = playCountText
        self.isSubscribed = isSubscribed
    }

    enum CodingKeys: String, CodingKey {
        case id, name, subtitle, createdAt, tracks, coverURL, source, remoteId, trackCountHint, playCountText, isSubscribed
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        subtitle = try c.decodeIfPresent(String.self, forKey: .subtitle) ?? ""
        createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
        tracks = try c.decodeIfPresent([MusicTrack].self, forKey: .tracks) ?? []
        coverURL = try c.decodeIfPresent(String.self, forKey: .coverURL)
        source = try c.decodeIfPresent(MusicSourceKind.self, forKey: .source)
        remoteId = try c.decodeIfPresent(String.self, forKey: .remoteId)
        trackCountHint = try c.decodeIfPresent(Int.self, forKey: .trackCountHint)
        playCountText = try c.decodeIfPresent(String.self, forKey: .playCountText)
        isSubscribed = try c.decodeIfPresent(Bool.self, forKey: .isSubscribed)
    }

    var isCloud: Bool { source != nil && !(remoteId ?? "").isEmpty }
    var displayTrackCount: Int { max(tracks.count, trackCountHint ?? 0) }
    var displayCoverURL: String {
        if let c = coverURL, !c.isEmpty { return c }
        return tracks.first(where: { !$0.coverURL.isEmpty })?.coverURL ?? ""
    }
}

struct MusicLyricLine: Identifiable, Hashable {
    let id: Int
    let timeSec: Double
    let text: String
    var translation: String?
    var romaji: String?
}

struct MusicSignalPath: Equatable {
    var sourceStage: String
    var streamStage: String
    var decodeStage: String
    var outputStage: String
    var isLossless: Bool
    var isCached: Bool
}

struct MusicAlternative: Identifiable, Hashable {
    let id: String
    let track: MusicTrack
    let latencyMs: Int
    let qualityLabel: String
    let entitlement: MusicEntitlement
    let resolvedURL: String?
    let headers: [String: String]?
}

struct MusicNASConfig: Identifiable, Codable, Hashable {
    let id: String
    var kind: MusicSourceKind
    var name: String
    var serverURL: String
    var username: String
    var password: String
    var enabled: Bool
}

struct MusicSourcePref: Identifiable, Codable, Hashable {
    let id: String
    var kind: MusicSourceKind
    var enabled: Bool
    var customCookie: String?
    var accountNickname: String? = nil
    var accountAvatarURL: String? = nil
}

struct YueqianKeep: Equatable {
    let monthText: String
    let dayText: String
    let weekdayText: String
    let lyricLine: String
    let track: MusicTrack
    let openedDaysThisMonth: Int
}

enum MusicLyricsParser {
    private static let tagRegex = try? NSRegularExpression(pattern: #"\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]"#)
    /// Qishui / Douyin / Kugou KRC line header: `[startMs,durationMs]<wStart,wDur,0>word...`
    private static let krcLineRegex = try? NSRegularExpression(pattern: #"^\[(\d+),(\d+)\](.*)$"#)
    private static let krcWordTagRegex = try? NSRegularExpression(pattern: #"<[^>]+>"#)

    /// Parses standard synced LRC and word-timed KRC (+ optional translated & romaji LRC) into sorted `MusicLyricLine`s.
    static func parse(lrc: String, translation: String? = nil, romaji: String? = nil) -> [MusicLyricLine] {
        let base = parseSingle(lrc)
        guard !base.isEmpty else { return [] }
        let transMap = translation.map(parseSingle) ?? []
        let romaMap = romaji.map(parseSingle) ?? []

        func nearest(_ list: [(Double, String)], to t: Double) -> String? {
            guard !list.isEmpty else { return nil }
            var best: (Double, String)?
            var bestDiff = 0.65
            for item in list {
                let d = abs(item.0 - t)
                if d < bestDiff {
                    bestDiff = d
                    best = item
                }
            }
            return best?.1
        }

        return base.enumerated().map { idx, pair in
            MusicLyricLine(
                id: idx,
                timeSec: pair.0,
                text: pair.1,
                translation: nearest(transMap, to: pair.0),
                romaji: nearest(romaMap, to: pair.0)
            )
        }
    }

    static func decodeHTMLEntities(_ input: String) -> String {
        guard input.contains("&") else { return input }
        return input
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&#x27;", with: "'")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#34;", with: "\"")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    private static func parseSingle(_ raw: String) -> [(Double, String)] {
        guard let regex = tagRegex else { return [] }
        let normalized = decodeHTMLEntities(raw)
        var rows: [(Double, String)] = []
        for line in normalized.components(separatedBy: .newlines) {
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            let ns = trimmed as NSString
            let matches = regex.matches(in: trimmed, range: NSRange(location: 0, length: ns.length))
            if !matches.isEmpty {
                let lastEnd = matches.last!.range.location + matches.last!.range.length
                let text = ns.substring(from: lastEnd).trimmingCharacters(in: .whitespaces)
                guard !text.isEmpty else { continue }
                for m in matches {
                    let minStr = ns.substring(with: m.range(at: 1))
                    let secStr = ns.substring(with: m.range(at: 2))
                    let fracRange = m.range(at: 3)
                    let fracStr = fracRange.location != NSNotFound ? ns.substring(with: fracRange) : ""
                    let minutes = Double(minStr) ?? 0
                    let seconds = Double(secStr) ?? 0
                    let frac: Double
                    if fracStr.count == 3 { frac = (Double(fracStr) ?? 0) / 1000.0 }
                    else if fracStr.count == 2 { frac = (Double(fracStr) ?? 0) / 100.0 }
                    else if fracStr.count == 1 { frac = (Double(fracStr) ?? 0) / 10.0 }
                    else { frac = 0 }
                    rows.append((minutes * 60.0 + seconds + frac, text))
                }
            } else if let krcRe = krcLineRegex,
                      let km = krcRe.firstMatch(in: trimmed, range: NSRange(location: 0, length: ns.length)) {
                let startMs = Double(ns.substring(with: km.range(at: 1))) ?? 0
                let bodyRaw = ns.substring(with: km.range(at: 3))
                let stripped: String
                if let wRe = krcWordTagRegex {
                    stripped = wRe.stringByReplacingMatches(in: bodyRaw, range: NSRange(location: 0, length: (bodyRaw as NSString).length), withTemplate: "")
                } else {
                    stripped = bodyRaw
                }
                let cleaned = stripped
                    .replacingOccurrences(of: "  ", with: " ")
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if !cleaned.isEmpty {
                    rows.append((startMs / 1000.0, cleaned))
                }
            }
        }
        return rows.sorted { $0.0 < $1.0 }
    }

    /// Picks a poetic 6..24-char hook line from synced lyrics (skipping credit lines like 作词/作曲/编曲).
    static func pickYueqianHook(from lines: [MusicLyricLine]) -> String? {
        let creditWords = ["作词", "作曲", "编曲", "制作", "混音", "母带", "录音", "吉他", "和声", "出品", "监制", "词：", "曲：", "词:", "曲:"]
        let good = lines.map(\.text).filter { text in
            let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard t.count >= 6, t.count <= 26 else { return false }
            return !creditWords.contains(where: { t.contains($0) })
        }
        guard !good.isEmpty else { return nil }
        return good[min(good.count / 3, good.count - 1)]
    }
}

/// Persistent client-only store for music favorites, playlists, play history, source order, NAS servers, and LRU audio cache.
@MainActor
final class MusicStore: ObservableObject {
    static let shared = MusicStore()

    static let favoritesKey = "stv.music.favorites"
    static let playlistsKey = "stv.music.playlists"
    static let cloudPlaylistsKey = "stv.music.cloudPlaylists"
    static let recentsKey = "stv.music.recents"
    static let sourcesKey = "stv.music.sources"
    static let nasKey = "stv.music.nas"
    static let settingsKey = "stv.music.settings"

    struct Settings: Codable, Equatable {
        var lrclibEnabled: Bool = true
        var musicBrainzEnabled: Bool = true
        var wifiOnlyCache: Bool = true
        var maxCacheMB: Int = 2048
        var autoFallback: Bool = true
        var preferredQuality: MusicAudioQuality = .hires

        init(
            lrclibEnabled: Bool = true,
            musicBrainzEnabled: Bool = true,
            wifiOnlyCache: Bool = true,
            maxCacheMB: Int = 2048,
            autoFallback: Bool = true,
            preferredQuality: MusicAudioQuality = .hires
        ) {
            self.lrclibEnabled = lrclibEnabled
            self.musicBrainzEnabled = musicBrainzEnabled
            self.wifiOnlyCache = wifiOnlyCache
            self.maxCacheMB = maxCacheMB
            self.autoFallback = autoFallback
            self.preferredQuality = preferredQuality
        }

        enum CodingKeys: String, CodingKey {
            case lrclibEnabled, musicBrainzEnabled, wifiOnlyCache, maxCacheMB, autoFallback, preferredQuality
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            lrclibEnabled = try c.decodeIfPresent(Bool.self, forKey: .lrclibEnabled) ?? true
            musicBrainzEnabled = try c.decodeIfPresent(Bool.self, forKey: .musicBrainzEnabled) ?? true
            wifiOnlyCache = try c.decodeIfPresent(Bool.self, forKey: .wifiOnlyCache) ?? true
            maxCacheMB = try c.decodeIfPresent(Int.self, forKey: .maxCacheMB) ?? 2048
            autoFallback = try c.decodeIfPresent(Bool.self, forKey: .autoFallback) ?? true
            preferredQuality = try c.decodeIfPresent(MusicAudioQuality.self, forKey: .preferredQuality) ?? .hires
        }
    }

    @Published private(set) var favorites: [MusicTrack] = []
    @Published private(set) var playlists: [MusicPlaylist] = []
    @Published private(set) var cloudPlaylists: [MusicPlaylist] = []
    @Published private(set) var syncingCloudPlaylists: Bool = false
    @Published private(set) var recents: [MusicTrack] = []
    @Published var sourcePrefs: [MusicSourcePref] = []
    @Published var nasConfigs: [MusicNASConfig] = []
    @Published var settings: Settings = Settings() {
        didSet { save(settings, key: Self.settingsKey) }
    }
    @Published private(set) var cacheBytes: Int64 = 0
    @Published private(set) var cachedCount: Int = 0
    @Published private(set) var nasTracks: [MusicTrack] = []

    let cacheDirectory: URL

    private init() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
        cacheDirectory = caches.appendingPathComponent("MusicAudioCache", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
        purgeLegacyDotAudioFiles()
        reloadFromDefaults()
        refreshCacheStats()
    }

    func reloadFromDefaults() {
        favorites = load([MusicTrack].self, key: Self.favoritesKey) ?? []
        playlists = load([MusicPlaylist].self, key: Self.playlistsKey) ?? []
        cloudPlaylists = load([MusicPlaylist].self, key: Self.cloudPlaylistsKey) ?? []
        recents = load([MusicTrack].self, key: Self.recentsKey) ?? []
        nasConfigs = load([MusicNASConfig].self, key: Self.nasKey) ?? []
        settings = load(Settings.self, key: Self.settingsKey) ?? Settings()

        let loadedSources = load([MusicSourcePref].self, key: Self.sourcesKey)
        if var loaded = loadedSources, !loaded.isEmpty {
            if !loaded.contains(where: { $0.kind == .qishui }) {
                let insertIdx = loaded.firstIndex(where: { $0.kind == .itunes }) ?? loaded.count
                loaded.insert(MusicSourcePref(id: "qishui", kind: .qishui, enabled: true, customCookie: nil), at: insertIdx)
                save(loaded, key: Self.sourcesKey)
            }
            sourcePrefs = loaded
        } else {
            sourcePrefs = [
                MusicSourcePref(id: "netease", kind: .netease, enabled: true, customCookie: nil),
                MusicSourcePref(id: "qq", kind: .qq, enabled: true, customCookie: nil),
                MusicSourcePref(id: "kugou", kind: .kugou, enabled: true, customCookie: nil),
                MusicSourcePref(id: "qishui", kind: .qishui, enabled: true, customCookie: nil),
                MusicSourcePref(id: "itunes", kind: .itunes, enabled: true, customCookie: nil)
            ]
        }
    }

    var enabledOnlineSources: [MusicSourceKind] {
        sourcePrefs.filter(\.enabled).map(\.kind)
    }

    func isSourceLoggedIn(_ kind: MusicSourceKind) -> Bool {
        guard let ck = sourcePrefs.first(where: { $0.kind == kind })?.customCookie else { return false }
        return !ck.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func isFavorite(_ track: MusicTrack) -> Bool {
        favorites.contains { $0.id == track.id || ($0.title == track.title && $0.artist == track.artist) }
    }

    func toggleFavorite(_ track: MusicTrack) {
        if let idx = favorites.firstIndex(where: { $0.id == track.id || ($0.title == track.title && $0.artist == track.artist) }) {
            favorites.remove(at: idx)
        } else {
            favorites.insert(track, at: 0)
        }
        save(favorites, key: Self.favoritesKey)
    }

    func recordPlay(_ track: MusicTrack) {
        recents.removeAll { $0.id == track.id }
        recents.insert(track, at: 0)
        if recents.count > 80 { recents = Array(recents.prefix(80)) }
        save(recents, key: Self.recentsKey)
    }

    @discardableResult
    func cloneChartAsPlaylist(name: String, tracks: [MusicTrack]) -> (added: Int, skipped: Int) {
        if let idx = playlists.firstIndex(where: { $0.name == name }) {
            var existing = playlists[idx]
            let existingKeys = Set(existing.tracks.map { "\($0.title.lowercased())|\($0.artist.lowercased())" })
            var added = 0, skipped = 0
            for t in tracks {
                let k = "\(t.title.lowercased())|\(t.artist.lowercased())"
                if existingKeys.contains(k) { skipped += 1 }
                else { existing.tracks.append(t); added += 1 }
            }
            playlists[idx] = existing
            save(playlists, key: Self.playlistsKey)
            return (added, skipped)
        }
        var seen = Set<String>()
        var unique: [MusicTrack] = []
        var skipped = 0
        for t in tracks {
            let k = "\(t.title.lowercased())|\(t.artist.lowercased())"
            if seen.insert(k).inserted { unique.append(t) } else { skipped += 1 }
        }
        let pl = MusicPlaylist(
            id: UUID().uuidString,
            name: name,
            subtitle: "本地克隆歌单 · \(unique.count) 首",
            createdAt: Date(),
            tracks: unique
        )
        playlists.insert(pl, at: 0)
        save(playlists, key: Self.playlistsKey)
        return (unique.count, skipped)
    }

    func addTrack(_ track: MusicTrack, toPlaylistId pid: String) {
        guard let idx = playlists.firstIndex(where: { $0.id == pid }) else { return }
        if !playlists[idx].tracks.contains(where: { $0.id == track.id }) {
            playlists[idx].tracks.insert(track, at: 0)
            playlists[idx].subtitle = "本地歌单 · \(playlists[idx].tracks.count) 首"
            save(playlists, key: Self.playlistsKey)
        }
    }

    func createPlaylist(name: String, initialTrack: MusicTrack? = nil) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let items = initialTrack.map { [$0] } ?? []
        let pl = MusicPlaylist(id: UUID().uuidString, name: trimmed, subtitle: "自建歌单 · \(items.count) 首", createdAt: Date(), tracks: items)
        playlists.insert(pl, at: 0)
        save(playlists, key: Self.playlistsKey)
    }

    func deletePlaylist(id: String) {
        playlists.removeAll { $0.id == id }
        save(playlists, key: Self.playlistsKey)
    }

    func toggleSource(_ id: String) {
        guard let idx = sourcePrefs.firstIndex(where: { $0.id == id }) else { return }
        sourcePrefs[idx].enabled.toggle()
        save(sourcePrefs, key: Self.sourcesKey)
    }

    func promoteSource(_ id: String) {
        guard let idx = sourcePrefs.firstIndex(where: { $0.id == id }), idx > 0 else { return }
        let item = sourcePrefs.remove(at: idx)
        sourcePrefs.insert(item, at: 0)
        save(sourcePrefs, key: Self.sourcesKey)
    }

    func setSourceCookie(_ id: String, cookie: String?) {
        guard let idx = sourcePrefs.firstIndex(where: { $0.id == id }) else { return }
        let kind = sourcePrefs[idx].kind
        let trimmed = cookie?.trimmingCharacters(in: .whitespacesAndNewlines)
        sourcePrefs[idx].customCookie = (trimmed?.isEmpty == false) ? trimmed : nil
        if sourcePrefs[idx].customCookie == nil {
            sourcePrefs[idx].accountNickname = nil
            sourcePrefs[idx].accountAvatarURL = nil
            cloudPlaylists.removeAll { $0.source == kind }
            save(cloudPlaylists, key: Self.cloudPlaylistsKey)
        }
        save(sourcePrefs, key: Self.sourcesKey)
    }

    func setSourceAccount(_ id: String, cookie: String, nickname: String?, avatarURL: String?) {
        guard let idx = sourcePrefs.firstIndex(where: { $0.id == id }) else { return }
        sourcePrefs[idx].customCookie = cookie
        sourcePrefs[idx].accountNickname = nickname
        sourcePrefs[idx].accountAvatarURL = avatarURL
        save(sourcePrefs, key: Self.sourcesKey)
    }

    func logoutSource(_ id: String) {
        setSourceCookie(id, cookie: nil)
    }

    func cookie(for kind: MusicSourceKind) -> String? {
        sourcePrefs.first(where: { $0.kind == kind })?.customCookie
    }

    var cookieMap: [MusicSourceKind: String] {
        var map: [MusicSourceKind: String] = [:]
        for p in sourcePrefs {
            if let ck = p.customCookie?.trimmingCharacters(in: .whitespacesAndNewlines), !ck.isEmpty {
                map[p.kind] = ck
            }
        }
        return map
    }

    var hasAnyLoggedInSource: Bool {
        !cookieMap.isEmpty
    }

    var loginFingerprint: String {
        sourcePrefs.compactMap { p -> String? in
            guard let ck = p.customCookie?.trimmingCharacters(in: .whitespacesAndNewlines), !ck.isEmpty else { return nil }
            return "\(p.kind.rawValue):\(ck.count):\(p.accountNickname ?? "")"
        }.joined(separator: "|")
    }

    func updateCloudPlaylistTracks(playlistId: String, tracks: [MusicTrack]) {
        guard !tracks.isEmpty, let idx = cloudPlaylists.firstIndex(where: { $0.id == playlistId }) else { return }
        cloudPlaylists[idx].tracks = tracks
        cloudPlaylists[idx].trackCountHint = max(cloudPlaylists[idx].trackCountHint ?? 0, tracks.count)
        save(cloudPlaylists, key: Self.cloudPlaylistsKey)
    }

    @discardableResult
    func syncCloudPlaylists() async -> Int {
        let map = cookieMap
        guard !map.isEmpty else {
            if !cloudPlaylists.isEmpty {
                cloudPlaylists = []
                save(cloudPlaylists, key: Self.cloudPlaylistsKey)
            }
            return 0
        }
        if syncingCloudPlaylists { return cloudPlaylists.count }
        syncingCloudPlaylists = true
        defer { syncingCloudPlaylists = false }
        let fetched = await MusicProviderHub.shared.fetchUserCloudPlaylists(cookies: map)
        if !fetched.isEmpty {
            let existingTracks = Dictionary(uniqueKeysWithValues: cloudPlaylists.map { ($0.id, $0.tracks) })
            cloudPlaylists = fetched.map { pl in
                var merged = pl
                if merged.tracks.isEmpty, let cached = existingTracks[pl.id], !cached.isEmpty {
                    merged.tracks = cached
                }
                return merged
            }
            save(cloudPlaylists, key: Self.cloudPlaylistsKey)
        }
        return cloudPlaylists.count
    }

    func saveNAS(_ config: MusicNASConfig) {
        if let idx = nasConfigs.firstIndex(where: { $0.id == config.id }) {
            nasConfigs[idx] = config
        } else {
            nasConfigs.append(config)
        }
        save(nasConfigs, key: Self.nasKey)
    }

    func removeNAS(id: String) {
        nasConfigs.removeAll { $0.id == id }
        save(nasConfigs, key: Self.nasKey)
    }

    func deleteNAS(id: String) { removeNAS(id: id) }

    func setNASTracks(_ tracks: [MusicTrack]) {
        nasTracks = tracks
    }

    var formattedCacheSize: String {
        let mb = Double(cacheBytes) / 1_048_576.0
        if mb >= 1024 { return String(format: "%.2f GB", mb / 1024.0) }
        return String(format: "%.1f MB", mb)
    }

    // MARK: - Smart LRU Local Audio Cache (Real Extensions Required by AVFoundation)

    func cachedFileURL(for track: MusicTrack) -> URL? {
        let base = safeCacheStem(for: track)
        for ext in ["flac", "wav", "m4a", "mp3", "aac"] {
            let f = cacheDirectory.appendingPathComponent("\(base).\(ext)")
            if FileManager.default.fileExists(atPath: f.path),
               let attrs = try? FileManager.default.attributesOfItem(atPath: f.path),
               let sz = attrs[.size] as? Int64, sz > 16_384 {
                return f
            }
        }
        return nil
    }

    func removeCachedFile(for track: MusicTrack) {
        let base = safeCacheStem(for: track)
        for ext in ["flac", "wav", "m4a", "mp3", "aac", "audio"] {
            let f = cacheDirectory.appendingPathComponent("\(base).\(ext)")
            try? FileManager.default.removeItem(at: f)
        }
        refreshCacheStats()
    }

    func cacheAudioData(_ data: Data, for track: MusicTrack, sourceURL: URL? = nil) {
        guard data.count > 16_384, let ext = Self.detectAudioExtension(data: data, url: sourceURL) else { return }
        let base = safeCacheStem(for: track)
        for oldExt in ["flac", "wav", "m4a", "mp3", "aac", "audio"] where oldExt != ext {
            try? FileManager.default.removeItem(at: cacheDirectory.appendingPathComponent("\(base).\(oldExt)"))
        }
        let dest = cacheDirectory.appendingPathComponent("\(base).\(ext)")
        try? data.write(to: dest, options: .atomic)
        trimCacheLRUIfNeeded()
        refreshCacheStats()
    }

    static func detectAudioExtension(data: Data, url: URL?) -> String? {
        guard data.count >= 12 else { return nil }
        let b = [UInt8](data.prefix(12))
        if b[0] == 0x3C || b[0] == 0x7B { return nil }
        if b[0] == 0x66 && b[1] == 0x4C && b[2] == 0x61 && b[3] == 0x43 { return "flac" }
        if b[4] == 0x66 && b[5] == 0x74 && b[6] == 0x79 && b[7] == 0x70 { return "m4a" }
        if (b[0] == 0x49 && b[1] == 0x44 && b[2] == 0x33) || (b[0] == 0xFF && (b[1] & 0xE0) == 0xE0) { return "mp3" }
        if b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 { return "wav" }
        let pathExt = url?.pathExtension.lowercased() ?? ""
        if ["mp3", "m4a", "flac", "aac", "wav"].contains(pathExt) { return pathExt }
        return nil
    }

    private func purgeLegacyDotAudioFiles() {
        let fm = FileManager.default
        if let items = try? fm.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: nil) {
            for u in items where u.pathExtension.lowercased() == "audio" {
                try? fm.removeItem(at: u)
            }
        }
    }

    func clearCache() {
        let fm = FileManager.default
        if let items = try? fm.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: nil) {
            for u in items { try? fm.removeItem(at: u) }
        }
        refreshCacheStats()
    }

    func clearAudioCache() { clearCache() }

    func refreshCacheStats() {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: [.fileSizeKey]) else {
            cacheBytes = 0; cachedCount = 0; return
        }
        var total: Int64 = 0
        for u in items {
            let sz = (try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            total += Int64(sz)
        }
        cacheBytes = total
        cachedCount = items.count
    }

    private func trimCacheLRUIfNeeded() {
        let limit = Int64(max(128, settings.maxCacheMB)) * 1024 * 1024
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(at: cacheDirectory, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]) else { return }
        var entries: [(url: URL, size: Int64, date: Date)] = items.map { u in
            let rv = try? u.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            return (u, Int64(rv?.fileSize ?? 0), rv?.contentModificationDate ?? .distantPast)
        }
        var total = entries.reduce(0) { $0 + $1.size }
        guard total > limit else { return }
        entries.sort { $0.date < $1.date }
        for e in entries where total > limit {
            try? fm.removeItem(at: e.url)
            total -= e.size
        }
    }

    private func safeCacheStem(for track: MusicTrack) -> String {
        track.id.replacingOccurrences(of: "[^A-Za-z0-9_-]", with: "_", options: .regularExpression)
    }

    private func save<T: Encodable>(_ value: T, key: String) {
        if let data = try? JSONEncoder().encode(value) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    private func load<T: Decodable>(_ type: T.Type, key: String) -> T? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
}
