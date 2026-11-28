import Foundation
import Combine

struct WatchProgress: Codable, Hashable, Identifiable {
    var series: HGSeries
    var episodeIndex: Int
    var episodeNumber: Int
    var seconds: Double
    var duration: Double
    var updatedAt: Date
    var id: String { series.id }
    var fraction: Double { duration > 0 ? min(1, seconds / duration) : 0 }
    /// "看到第3集" for dramas, "看到 41:12" for single long videos.
    var resumeLabel: String { series.source.isLongVideo ? "看到 " + Fmt.clock(Int(seconds)) : "看到第\(episodeNumber)集" }
}

/// LWW tombstone metadata so deleted or cleared search history never resurrects on WebDAV / backup sync.
struct SearchHistoryMeta: Codable, Equatable {
    var added: [String: Int64] = [:]
    var deleted: [String: Int64] = [:]
    var clearedAt: Int64 = 0
}

/// Local-only watch history + favorites, persisted as JSON in UserDefaults.
@MainActor
final class LibraryStore: ObservableObject {
    static let shared = LibraryStore()

    /// Watch records as of the last structural change (new title, other episode, player closed). Samples taken while
    /// something plays stay in `live`: publishing them every few seconds redrew every page that shows history or
    /// favorites, and re-encoded the whole history on the main thread.
    @Published private(set) var progress: [String: WatchProgress] = [:]
    private var live: [String: WatchProgress] = [:]
    private var persistTask: Task<Void, Never>?
    /// Bumped by every direct write, so a background encode that started earlier never overwrites it.
    private var writes = 0
    /// Every favorite incl. hidden sources; UI reads `favorites`, which hides locked/disabled sites.
    @Published private(set) var allFavorites: [HGSeries] = []
    var favorites: [HGSeries] { allFavorites.filter { SourceGate.shared.isVisible($0) } }
    @Published private(set) var searchHistory: [String] = []
    private(set) var searchHistoryMeta: SearchHistoryMeta = SearchHistoryMeta()

    private let d = UserDefaults.standard
    private init() {
        progress = load("stv.progress") ?? [:]
        allFavorites = load("stv.favorites") ?? []
        searchHistory = load("stv.searchHistory") ?? []
        searchHistoryMeta = load("stv.searchHistoryMeta") ?? SearchHistoryMeta()
    }

    var continueWatching: [WatchProgress] {
        progress.merging(live) { $1 }.values.filter { SourceGate.shared.isVisible($0.series) }.sorted { $0.updatedAt > $1.updatedAt }
    }

    func progress(for s: HGSeries) -> WatchProgress? { live[s.id] ?? progress[s.id] }

    func record(_ s: HGSeries, index: Int, ep: Int, seconds: Double, duration: Double) {
        guard seconds.isFinite, seconds >= 0 else { return }
        let old = progress(for: s)
        live[s.id] = WatchProgress(series: s, episodeIndex: index, episodeNumber: ep, seconds: seconds,
                                   duration: duration.isFinite ? duration : 0, updatedAt: Date())
        if old == nil || old?.episodeIndex != index { publish() }
        persistSoon()
    }

    /// The player closed or the app went to the background: make the latest samples visible and durable.
    func flush() {
        guard !live.isEmpty || persistTask != nil else { return }
        publish()
        saveProgress()
    }

    private func saveProgress() {
        writes += 1
        persistTask?.cancel(); persistTask = nil
        save(progress, "stv.progress")
    }

    private func publish() {
        guard !live.isEmpty else { return }
        progress.merge(live) { $1 }
        live.removeAll()
    }

    private func persistSoon() {
        guard persistTask == nil else { return }
        persistTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard let self, !Task.isCancelled else { return }
            self.persistTask = nil
            let snapshot = self.progress.merging(self.live) { $1 }
            let stamp = self.writes
            let data = await Task.detached(priority: .utility) { try? JSONEncoder().encode(snapshot) }.value
            if let data, stamp == self.writes { self.d.set(data, forKey: "stv.progress") }
        }
    }

    /// Records from another store (TVBox v1 history): never replaces what is already known.
    func merge(_ list: [WatchProgress]) {
        let new = list.filter { progress(for: $0.series) == nil }
        guard !new.isEmpty else { return }
        publish()
        for p in new { progress[p.id] = p }
        saveProgress()
    }

    func removeProgress(_ s: HGSeries) { live[s.id] = nil; progress[s.id] = nil; saveProgress() }
    /// Clears the visible history (hidden / locked sources keep theirs, like continueWatching).
    func clearHistory() {
        publish()
        for p in continueWatching { progress[p.series.id] = nil }
        saveProgress()
    }

    func isFavorite(_ s: HGSeries) -> Bool { allFavorites.contains { $0.id == s.id } }

    func toggleFavorite(_ s: HGSeries) {
        if isFavorite(s) { allFavorites.removeAll { $0.id == s.id } } else { allFavorites.insert(s, at: 0) }
        save(allFavorites, "stv.favorites")
    }

    func removeFavorite(_ s: HGSeries) {
        allFavorites.removeAll { $0.id == s.id }
        save(allFavorites, "stv.favorites")
    }

    func removeFavorites(_ ids: Set<String>) {
        guard !ids.isEmpty else { return }
        allFavorites.removeAll { ids.contains($0.id) }
        save(allFavorites, "stv.favorites")
    }

    func clearFavorites() {
        let visibleIDs = Set(favorites.map(\.id))
        guard !visibleIDs.isEmpty else { return }
        allFavorites.removeAll { visibleIDs.contains($0.id) }
        save(allFavorites, "stv.favorites")
    }

    func addSearch(_ q: String) {
        let t = q.trimmingCharacters(in: .whitespacesAndNewlines); guard !t.isEmpty else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000.0)
        searchHistory.removeAll { $0 == t }; searchHistory.insert(t, at: 0)
        searchHistory = Array(searchHistory.prefix(10))
        searchHistoryMeta.added[t] = now
        searchHistoryMeta.deleted.removeValue(forKey: t)
        trimSearchMeta()
        saveSearchState()
    }
    func removeSearch(_ q: String) {
        let t = q.trimmingCharacters(in: .whitespacesAndNewlines); guard !t.isEmpty else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000.0)
        searchHistory.removeAll { $0 == q || $0.trimmingCharacters(in: .whitespacesAndNewlines) == t }
        searchHistoryMeta.added.removeValue(forKey: q)
        searchHistoryMeta.added.removeValue(forKey: t)
        searchHistoryMeta.deleted[t] = now
        trimSearchMeta()
        saveSearchState()
    }
    func clearSearch() {
        let now = Int64(Date().timeIntervalSince1970 * 1000.0)
        for item in searchHistory {
            let t = item.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty { searchHistoryMeta.deleted[t] = now }
        }
        searchHistory = []
        searchHistoryMeta.added.removeAll()
        searchHistoryMeta.clearedAt = now
        trimSearchMeta()
        saveSearchState()
    }

    private func trimSearchMeta() {
        let active = Set(searchHistory)
        searchHistoryMeta.added = searchHistoryMeta.added.filter { active.contains($0.key) }
        if searchHistoryMeta.deleted.count > 100 {
            let keep = searchHistoryMeta.deleted.sorted { $0.value > $1.value }.prefix(100)
            searchHistoryMeta.deleted = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
        }
    }

    private func saveSearchState() {
        save(searchHistory, "stv.searchHistory")
        save(searchHistoryMeta, "stv.searchHistoryMeta")
    }

    nonisolated static func mergeSearchHistory(
        localItems: [String],
        localMeta: SearchHistoryMeta,
        incomingItems: [String],
        incomingMeta: SearchHistoryMeta,
        limit: Int = 10
    ) -> ([String], SearchHistoryMeta) {
        let clearedAt = max(localMeta.clearedAt, incomingMeta.clearedAt)
        var deleted = localMeta.deleted
        for (k, v) in incomingMeta.deleted where v > (deleted[k] ?? 0) { deleted[k] = v }
        var added = localMeta.added
        for (k, v) in incomingMeta.added where v > (added[k] ?? 0) { added[k] = v }

        var seen = Set<String>()
        var candidates: [String] = []
        for raw in localItems + incomingItems {
            let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty && seen.insert(t).inserted { candidates.append(t) }
        }

        let alive = candidates.filter { term in
            let addedTs = added[term] ?? 0
            let deletedTs = deleted[term] ?? 0
            if deletedTs > 0 {
                return addedTs > deletedTs && addedTs > clearedAt
            } else if clearedAt > 0 {
                return addedTs > clearedAt
            } else {
                return true
            }
        }.sorted { (added[$0] ?? 0) > (added[$1] ?? 0) }
        let limited = Array(alive.prefix(limit))
        let aliveSet = Set(limited)
        let trimmedAdded = added.filter { aliveSet.contains($0.key) }
        let trimmedDeleted: [String: Int64] = deleted.count > 100
            ? Dictionary(uniqueKeysWithValues: deleted.sorted { $0.value > $1.value }.prefix(100).map { ($0.key, $0.value) })
            : deleted
        return (limited, SearchHistoryMeta(added: trimmedAdded, deleted: trimmedDeleted, clearedAt: clearedAt))
    }

    func reloadFromDefaults() {
        writes += 1
        persistTask?.cancel()
        persistTask = nil
        live.removeAll()
        progress = load("stv.progress") ?? [:]
        allFavorites = load("stv.favorites") ?? []
        searchHistory = load("stv.searchHistory") ?? []
        searchHistoryMeta = load("stv.searchHistoryMeta") ?? SearchHistoryMeta()
    }

    private func load<T: Decodable>(_ k: String) -> T? { d.data(forKey: k).flatMap { try? JSONDecoder().decode(T.self, from: $0) } }
    private func save<T: Encodable>(_ v: T, _ k: String) { if let data = try? JSONEncoder().encode(v) { d.set(data, forKey: k) } }
}
