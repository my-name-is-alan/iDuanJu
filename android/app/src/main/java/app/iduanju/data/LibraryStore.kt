package app.iduanju.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Watch progress (iOS WatchProgress) — local only, SharedPreferences JSON. */
data class WatchProgress(
    val seriesId: String, val title: String, val cover: String,
    val episodeIndex: Int, val episodeNumber: Int, val seconds: Double, val duration: Double, val updatedAt: Long,
    /** Channel name and group of a live list; empty for dramas. */
    val episodeTitle: String = "", val episodeGroup: String = "",
) {
    val fraction: Float get() = if (duration > 0) (seconds / duration).toFloat().coerceIn(0f, 1f) else 0f
    val isLive: Boolean get() = seriesId.startsWith("tvl:")
    val resumeLabel: String get() = when {
        isLive && episodeTitle.isNotEmpty() -> "直播 · $episodeTitle"
        isLive -> "直播"
        else -> "看到第${episodeNumber}集"
    }
}

/** LWW tombstone metadata so deleted or cleared search history is never resurrected by backup / WebDAV merge. */
data class SearchHistoryMeta(
    val added: Map<String, Long> = emptyMap(),
    val deleted: Map<String, Long> = emptyMap(),
    val clearedAt: Long = 0L,
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("added", JSONObject().apply { added.forEach { (k, v) -> put(k, v) } })
        put("deleted", JSONObject().apply { deleted.forEach { (k, v) -> put(k, v) } })
        put("clearedAt", clearedAt)
    }

    companion object {
        fun fromJSON(raw: String?): SearchHistoryMeta {
            if (raw.isNullOrBlank()) return SearchHistoryMeta()
            return runCatching { fromJSON(JSONObject(raw)) }.getOrDefault(SearchHistoryMeta())
        }

        fun fromJSON(o: JSONObject?): SearchHistoryMeta {
            if (o == null) return SearchHistoryMeta()
            val added = LinkedHashMap<String, Long>()
            o.optJSONObject("added")?.let { a -> a.keys().forEach { k -> added[k] = a.optLong(k, 0L) } }
            val deleted = LinkedHashMap<String, Long>()
            o.optJSONObject("deleted")?.let { d -> d.keys().forEach { k -> deleted[k] = d.optLong(k, 0L) } }
            return SearchHistoryMeta(added = added, deleted = deleted, clearedAt = o.optLong("clearedAt", 0L))
        }
    }
}

/** iOS LibraryStore: history (continue watching) + favourites + per-series playback choices. Main thread only. */
class LibraryStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
    // Progress is recorded every few seconds while playing: serialising up to 200 entries must not cost a frame.
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var pendingSave: Job? = null

    val history = mutableStateListOf<WatchProgress>()
    val favorites = mutableStateMapOf<String, Triple<String, String, Long>>()
    val searchHistory = mutableStateListOf<String>()
    private val searchAdded = LinkedHashMap<String, Long>()
    private val searchDeleted = LinkedHashMap<String, Long>()
    private var searchClearedAt: Long = 0L
    private val lineChoice = LinkedHashMap<String, String>()
    private val qualityChoice = LinkedHashMap<String, String>()

    private val peekCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    init {
        loadFromPrefsLocked()
    }

    private fun loadFromPrefsLocked() {
        history.clear()
        favorites.clear()
        lineChoice.clear()
        qualityChoice.clear()
        searchHistory.clear()
        searchAdded.clear()
        searchDeleted.clear()
        searchClearedAt = 0L
        peekCache.clear()
        runCatching {
            val a = JSONArray(sp.getString("history", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                val wp = WatchProgress(it.getString("sid"), it.optString("title"), it.optString("cover"), it.optInt("idx"),
                    it.optInt("ep", 1), it.optDouble("sec", 0.0), it.optDouble("dur", 0.0), it.optLong("at"), it.optString("epTitle"), it.optString("epGroup"))
                history.add(wp)
                updatePeek(wp.seriesId, wp.title, wp.cover)
            }
            val f = JSONObject(sp.getString("fav", "{}"))
            f.keys().forEach { k ->
                f.getJSONObject(k).let {
                    val t = it.optString("title"); val c = it.optString("cover")
                    favorites[k] = Triple(t, c, it.optLong("at"))
                    updatePeek(k, t, c)
                }
            }
        }
        runCatching { readMap("lineChoice", lineChoice) }
        runCatching { readMap("qualityChoice", qualityChoice) }
        val meta = SearchHistoryMeta.fromJSON(sp.getString("searchMeta", null))
        searchAdded.putAll(meta.added)
        searchDeleted.putAll(meta.deleted)
        searchClearedAt = meta.clearedAt
        runCatching {
            val a = JSONArray(sp.getString("search", "[]"))
            val seen = LinkedHashSet<String>()
            for (i in 0 until a.length()) {
                val term = a.optString(i).trim()
                if (term.isNotEmpty() && seen.add(term)) searchHistory.add(term)
            }
        }
    }

    private fun updatePeek(sid: String, title: String, cover: String) {
        if (sid.isEmpty()) return
        val prev = peekCache[sid]
        val t = title.ifEmpty { prev?.first.orEmpty() }
        val c = cover.ifEmpty { prev?.second.orEmpty() }
        if (t.isNotEmpty() || c.isNotEmpty()) peekCache[sid] = t to c
    }

    /** Thread-safe cover/title peek (safe to call from Dispatchers.IO without touching Compose snapshot state). */
    fun peekTitleSafe(sid: String): String = peekCache[sid]?.first.orEmpty()
    fun peekCoverSafe(sid: String): String = peekCache[sid]?.second.orEmpty()

    fun searchHistoryMeta(): SearchHistoryMeta = SearchHistoryMeta(
        added = searchAdded.toMap(),
        deleted = searchDeleted.toMap(),
        clearedAt = searchClearedAt,
    )

    fun addSearch(q: String) {
        val term = q.trim()
        if (term.isEmpty()) return
        val now = System.currentTimeMillis()
        searchHistory.remove(term)
        searchHistory.add(0, term)
        while (searchHistory.size > SEARCH_HISTORY_LIMIT) searchHistory.removeAt(SEARCH_HISTORY_LIMIT)
        searchAdded[term] = now
        searchDeleted.remove(term)
        trimSearchMeta()
        saveSearch()
    }

    fun removeSearch(q: String) {
        val term = q.trim()
        if (term.isEmpty()) return
        val now = System.currentTimeMillis()
        searchHistory.removeAll { it == q || it.trim() == term }
        searchAdded.remove(q)
        searchAdded.remove(term)
        searchDeleted[term] = now
        trimSearchMeta()
        saveSearch()
    }

    fun clearSearch() {
        val now = System.currentTimeMillis()
        for (term in searchHistory) {
            val t = term.trim()
            if (t.isNotEmpty()) searchDeleted[t] = now
        }
        searchHistory.clear()
        searchAdded.clear()
        searchClearedAt = now
        trimSearchMeta()
        saveSearch()
    }

    private fun trimSearchMeta() {
        val active = searchHistory.toHashSet()
        searchAdded.keys.retainAll(active)
        if (searchDeleted.size > MAX_SEARCH_TOMBSTONES) {
            val keep = searchDeleted.entries.sortedByDescending { it.value }.take(MAX_SEARCH_TOMBSTONES)
            searchDeleted.clear()
            keep.forEach { (k, v) -> searchDeleted[k] = v }
        }
    }

    private fun saveSearch() {
        sp.edit()
            .putString("search", JSONArray(searchHistory.toList()).toString())
            .putString("searchMeta", searchHistoryMeta().toJSON().toString())
            .apply()
    }

    val continueWatching: List<WatchProgress> get() = history.sortedByDescending { it.updatedAt }
    fun progress(sid: String): WatchProgress? = history.firstOrNull { it.seriesId == sid }

    fun record(p: WatchProgress) {
        history.removeAll { it.seriesId == p.seriesId }
        history.add(0, p)
        while (history.size > 200) history.removeAt(history.size - 1)
        updatePeek(p.seriesId, p.title, p.cover)
        save()
    }
    fun clearHistory() { history.clear(); save() }
    fun removeProgress(sid: String) { history.removeAll { it.seriesId == sid }; save() }
    fun isFavorite(sid: String) = favorites.containsKey(sid)
    fun toggleFavorite(sid: String, title: String, cover: String) {
        if (favorites.remove(sid) == null) {
            favorites[sid] = Triple(title, cover, System.currentTimeMillis())
            updatePeek(sid, title, cover)
        }
        save()
    }
    fun removeFavorite(sid: String) {
        if (favorites.remove(sid) != null) save()
    }
    fun removeFavorites(sids: Collection<String>) {
        if (sids.isEmpty()) return
        var changed = false
        for (sid in sids) if (favorites.remove(sid) != null) changed = true
        if (changed) save()
    }
    fun clearFavorites() {
        if (favorites.isEmpty()) return
        favorites.clear()
        save()
    }

    /** Flush pending debounced writes synchronously before creating a backup or WebDAV sync. */
    fun flush() {
        pendingSave?.cancel()
        writeSnapshotSync(history.toList(), favorites.toMap())
        sp.edit()
            .putString("search", JSONArray(searchHistory.toList()).toString())
            .putString("searchMeta", searchHistoryMeta().toJSON().toString())
            .commit()
    }

    /** Reload all library state from SharedPreferences on Main thread after backup restore. */
    fun reloadFromPrefs() {
        pendingSave?.cancel()
        loadFromPrefsLocked()
    }

    /** Smart-merge incoming progress, favorites, and search history on Main thread. */
    fun mergePortable(
        incomingProgress: List<WatchProgress>,
        incomingFavorites: List<Triple<String, Pair<String, String>, Long>>,
        incomingSearch: List<String>,
        incomingSearchMeta: SearchHistoryMeta = SearchHistoryMeta(),
    ) {
        val bySid = LinkedHashMap<String, WatchProgress>()
        for (p in history) bySid[p.seriesId] = p
        for (inc in incomingProgress) {
            val cur = bySid[inc.seriesId]
            if (cur == null || inc.updatedAt >= cur.updatedAt) bySid[inc.seriesId] = inc
        }
        val mergedHistory = bySid.values.sortedByDescending { it.updatedAt }.take(200)
        history.clear()
        history.addAll(mergedHistory)
        for (p in mergedHistory) updatePeek(p.seriesId, p.title, p.cover)

        for ((sid, pair, at) in incomingFavorites) {
            val cur = favorites[sid]
            if (cur == null || at > cur.third) {
                favorites[sid] = Triple(pair.first, pair.second, at)
                updatePeek(sid, pair.first, pair.second)
            }
        }
        if (incomingSearch.isNotEmpty() || incomingSearchMeta.clearedAt > 0L || incomingSearchMeta.deleted.isNotEmpty()) {
            val (mergedItems, mergedMeta) = mergeSearchHistoryState(
                localItems = searchHistory.toList(),
                localMeta = searchHistoryMeta(),
                incomingItems = incomingSearch,
                incomingMeta = incomingSearchMeta,
                limit = SEARCH_HISTORY_LIMIT,
            )
            searchHistory.clear()
            searchHistory.addAll(mergedItems)
            searchAdded.clear(); searchAdded.putAll(mergedMeta.added)
            searchDeleted.clear(); searchDeleted.putAll(mergedMeta.deleted)
            searchClearedAt = mergedMeta.clearedAt
            saveSearch()
        }
        flush()
    }

    // ── per-series playback choices ─────────────────────────────────────────────────────────────────────

    /** 线路 chosen by hand for this title (`HGPlayLine.id`). */
    fun preferredLine(sid: String): String? = lineChoice[sid]
    fun setPreferredLine(sid: String, lineId: String?) = choose("lineChoice", lineChoice, sid, lineId)
    /** 画质 label chosen by hand for this title. */
    fun preferredQuality(sid: String): String? = qualityChoice[sid]
    fun setPreferredQuality(sid: String, quality: String?) = choose("qualityChoice", qualityChoice, sid, quality)

    private fun choose(key: String, map: LinkedHashMap<String, String>, sid: String, value: String?) {
        map.remove(sid)
        if (!value.isNullOrEmpty()) map[sid] = value
        while (map.size > 300) map.remove(map.keys.first())
        sp.edit().putString(key, JSONObject(map as Map<*, *>).toString()).apply()
    }
    private fun readMap(key: String, into: MutableMap<String, String>) {
        val o = JSONObject(sp.getString(key, "{}") ?: "{}")
        o.keys().forEach { into[it] = o.optString(it) }
    }

    private fun writeSnapshotSync(h: List<WatchProgress>, fav: Map<String, Triple<String, String, Long>>) {
        val a = JSONArray()
        h.forEach { a.put(JSONObject().put("sid", it.seriesId).put("title", it.title).put("cover", it.cover).put("idx", it.episodeIndex)
            .put("ep", it.episodeNumber).put("sec", it.seconds).put("dur", it.duration).put("at", it.updatedAt).put("epTitle", it.episodeTitle).put("epGroup", it.episodeGroup)) }
        val f = JSONObject()
        fav.forEach { (k, v) -> f.put(k, JSONObject().put("title", v.first).put("cover", v.second).put("at", v.third)) }
        sp.edit().putString("history", a.toString()).putString("fav", f.toString()).commit()
    }

    private fun save() {
        val h = history.toList()
        val fav = favorites.toMap()
        pendingSave?.cancel()
        pendingSave = io.launch {
            delay(120)
            writeSnapshotSync(h, fav)
        }
    }

    companion object {
        const val SEARCH_HISTORY_LIMIT = 20
        private const val MAX_SEARCH_TOMBSTONES = 100

        /**
         * Tombstone-aware LWW merge for search history:
         * - Deleting or clearing locally suppresses older remote items on WebDAV sync.
         * - Re-searching a term later (higher added timestamp) restores it cleanly.
         * - Restoring a legacy backup (no metadata) into a fresh install works seamlessly.
         */
        fun mergeSearchHistoryState(
            localItems: List<String>,
            localMeta: SearchHistoryMeta,
            incomingItems: List<String>,
            incomingMeta: SearchHistoryMeta,
            limit: Int = SEARCH_HISTORY_LIMIT,
        ): Pair<List<String>, SearchHistoryMeta> {
            val clearedAt = maxOf(localMeta.clearedAt, incomingMeta.clearedAt)
            val deleted = LinkedHashMap<String, Long>()
            for ((k, v) in localMeta.deleted) deleted[k] = v
            for ((k, v) in incomingMeta.deleted) {
                val prev = deleted[k] ?: 0L
                if (v > prev) deleted[k] = v
            }
            val added = LinkedHashMap<String, Long>()
            for ((k, v) in localMeta.added) added[k] = v
            for ((k, v) in incomingMeta.added) {
                val prev = added[k] ?: 0L
                if (v > prev) added[k] = v
            }

            val candidates = LinkedHashSet<String>()
            for (raw in localItems) raw.trim().takeIf { it.isNotEmpty() }?.let { candidates += it }
            for (raw in incomingItems) raw.trim().takeIf { it.isNotEmpty() }?.let { candidates += it }

            val alive = candidates.filter { term ->
                val addedTs = added[term] ?: 0L
                val deletedTs = deleted[term] ?: 0L
                if (deletedTs > 0L) {
                    addedTs > deletedTs && addedTs > clearedAt
                } else if (clearedAt > 0L) {
                    addedTs > clearedAt
                } else {
                    true
                }
            }.sortedByDescending { added[it] ?: 0L }.take(limit)

            val aliveSet = alive.toHashSet()
            val trimmedAdded = added.filterKeys { it in aliveSet }
            val trimmedDeleted = if (deleted.size > MAX_SEARCH_TOMBSTONES) {
                deleted.entries.sortedByDescending { it.value }.take(MAX_SEARCH_TOMBSTONES).associate { it.key to it.value }
            } else deleted

            return alive to SearchHistoryMeta(added = trimmedAdded, deleted = trimmedDeleted, clearedAt = clearedAt)
        }
    }
}
