package app.iduanju.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Public hygiene: optional player device override "did,iid" — never hard-coded. */
class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("idj", Context.MODE_PRIVATE)

    var showChannelsTab by mutableStateOf(sp.getBoolean("stv.home.showChannels", true))
        private set
    var showMangaTab by mutableStateOf(sp.getBoolean("stv.home.showManga", true))
        private set
    var showNovelTab by mutableStateOf(sp.getBoolean("stv.home.showNovel", true))
        private set
    var showMusicTab by mutableStateOf(sp.getBoolean("stv.home.showMusic", true))
        private set
    var homeOrder by mutableStateOf<List<String>>(readStringList(KEY_HOME_ORDER))
        private set
    var homeHidden by mutableStateOf<Set<String>>(readStringList(KEY_HOME_HIDDEN).toSet() - "hongguo")
        private set

    fun updateShowChannelsTab(on: Boolean) { showChannelsTab = on; sp.edit().putBoolean("stv.home.showChannels", on).apply() }
    fun updateShowMangaTab(on: Boolean) { showMangaTab = on; sp.edit().putBoolean("stv.home.showManga", on).apply() }
    fun updateShowNovelTab(on: Boolean) { showNovelTab = on; sp.edit().putBoolean("stv.home.showNovel", on).apply() }
    fun updateShowMusicTab(on: Boolean) { showMusicTab = on; sp.edit().putBoolean("stv.home.showMusic", on).apply() }

    fun isHiddenOnHome(key: String): Boolean = key != "hongguo" && key in homeHidden

    fun setHomeHidden(key: String, hidden: Boolean) {
        if (key == "hongguo") return
        val next = if (hidden) homeHidden + key else homeHidden - key
        if (next == homeHidden) return
        homeHidden = next
        writeStringList(KEY_HOME_HIDDEN, next.sorted())
    }

    fun setHomeHiddenBatch(keys: Collection<String>, hidden: Boolean) {
        val valid = keys.filter { it != "hongguo" }
        if (valid.isEmpty()) return
        val next = if (hidden) homeHidden + valid else homeHidden - valid.toSet()
        if (next == homeHidden) return
        homeHidden = next
        writeStringList(KEY_HOME_HIDDEN, next.sorted())
    }

    fun clearHomeHidden() {
        if (homeHidden.isEmpty()) return
        homeHidden = emptySet()
        sp.edit().remove(KEY_HOME_HIDDEN).apply()
    }

    fun orderedKeys(activeKeys: List<String>): List<String> {
        val order = homeOrder
        if (order.isEmpty()) return activeKeys
        val rank = HashMap<String, Int>(order.size)
        order.forEachIndexed { idx, k -> rank.putIfAbsent(k, idx) }
        val known = activeKeys.filter { it in rank }.sortedBy { rank[it]!! }
        val fresh = activeKeys.filter { it !in rank }
        return known + fresh
    }

    fun updateHomeOrder(orderedVisibleKeys: List<String>) {
        val visibleSet = orderedVisibleKeys.toHashSet()
        val hiddenSaved = homeOrder.filter { it !in visibleSet }
        val merged = orderedVisibleKeys + hiddenSaved
        if (merged == homeOrder) return
        homeOrder = merged
        writeStringList(KEY_HOME_ORDER, merged)
    }

    fun resetHomeOrder() {
        if (homeOrder.isEmpty()) return
        homeOrder = emptyList()
        sp.edit().remove(KEY_HOME_ORDER).apply()
    }

    private fun readStringList(key: String): List<String> = runCatching {
        val arr = org.json.JSONArray(sp.getString(key, "[]") ?: "[]")
        buildList { for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
    }.getOrDefault(emptyList())

    private fun writeStringList(key: String, values: List<String>) {
        sp.edit().putString(key, org.json.JSONArray(values).toString()).apply()
    }

    fun reloadFromPrefs() {
        showChannelsTab = sp.getBoolean("stv.home.showChannels", true)
        showMangaTab = sp.getBoolean("stv.home.showManga", true)
        showNovelTab = sp.getBoolean("stv.home.showNovel", true)
        showMusicTab = sp.getBoolean("stv.home.showMusic", true)
        homeOrder = readStringList(KEY_HOME_ORDER)
        homeHidden = readStringList(KEY_HOME_HIDDEN).toSet() - "hongguo"
    }

    companion object {
        private const val KEY_HOME_ORDER = "stv.home.sourceOrder"
        private const val KEY_HOME_HIDDEN = "stv.home.hiddenSources"
    }

    fun playerDeviceOverride(): Pair<String, String>? {
        val s = sp.getString("hg.purePlayerDevice", null) ?: return null
        val p = s.split(",", limit = 2).map { it.trim() }
        return if (p.size == 2 && p[0].isNotEmpty() && p[1].isNotEmpty()) p[0] to p[1] else null
    }

    fun setPlayerDeviceOverride(did: String?, iid: String?) {
        if (did.isNullOrBlank() || iid.isNullOrBlank()) sp.edit().remove("hg.purePlayerDevice").apply()
        else sp.edit().putString("hg.purePlayerDevice", "$did,$iid").apply()
    }

    /** Developer option: let the player/media download follow the system proxy (default off = iOS parity). */
    var playerUsesSystemProxy: Boolean
        get() = sp.getBoolean("dev.playerSystemProxy", false)
        set(v) { sp.edit().putBoolean("dev.playerSystemProxy", v).apply(); app.iduanju.core.hongguo.HGNet.playerUsesSystemProxy = v }

    var adStripEnabled: Boolean
        get() = sp.getBoolean("stv.hls.adstrip", sp.getBoolean("adStrip", true))
        set(v) { sp.edit().putBoolean("stv.hls.adstrip", v).apply() }

    var prefetchEnabled: Boolean
        get() = sp.getBoolean("prefetchEnabled", true)
        set(v) { sp.edit().putBoolean("prefetchEnabled", v).apply() }

    var playbackCacheMB: Int
        get() = sp.getInt("playbackCacheMB", 600).coerceIn(100, 2000)
        set(v) { sp.edit().putInt("playbackCacheMB", v.coerceIn(100, 2000)).apply() }

    var danmakuArea: Float
        get() = sp.getFloat("danmakuArea", 1f / 3f)
        set(v) { sp.edit().putFloat("danmakuArea", v).apply() }
}
