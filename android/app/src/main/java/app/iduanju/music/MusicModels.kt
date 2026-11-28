package app.iduanju.music

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import java.util.Locale

enum class MusicSourceKind(
    val id: String,
    val label: String,
    val fullName: String,
    val isNAS: Boolean = false,
) {
    NETEASE("netease", "网易云", "网易云音乐"),
    QQ("qq", "QQ音乐", "QQ 音乐"),
    KUGOU("kugou", "酷狗", "酷狗音乐"),
    QISHUI("qishui", "汽水音乐", "汽水音乐"),
    ITUNES("itunes", "iTunes", "iTunes 试听"),
    NAVIDROME("navidrome", "Navidrome", "Navidrome / OpenSubsonic", isNAS = true),
    EMBY("emby", "Emby", "Emby / Jellyfin", isNAS = true),
    FNOS("fnos", "飞牛音乐", "飞牛音乐 (fnOS)", isNAS = true),
    PLUGIN("plugin", "插件源", "自定义音乐插件");

    companion object {
        fun fromId(raw: String?): MusicSourceKind = entries.firstOrNull { it.id == raw } ?: NETEASE
    }
}

enum class MusicEntitlement(val badge: String) {
    FULL("完整版"),
    PREVIEW("试听"),
    CACHED("已缓存"),
    UNKNOWN("");
}

enum class MusicAudioQuality(
    val id: String,
    val label: String,
    val shortBadge: String,
    val detail: String,
) {
    STANDARD("standard", "标准音质", "128k", "128kbps MP3/AAC · 省流秒播"),
    EXHIGH("exhigh", "极高音质", "HQ 320k", "320kbps HQ · 高品质均衡"),
    LOSSLESS("lossless", "无损 FLAC", "FLAC", "CD 级无损 FLAC · 会员推荐"),
    HIRES("hires", "Hi-Res 母带", "Hi-Res", "最高解析度母带 · 优先 Hi-Res / FLAC");

    companion object {
        fun fromId(raw: String?): MusicAudioQuality =
            entries.firstOrNull { it.id == raw } ?: HIRES
    }
}

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverURL: String = "",
    val duration: Double = 0.0,
    val sourceId: String = MusicSourceKind.NETEASE.id,
    val sourceTrackId: String = "",
    val streamURL: String? = null,
    val qualityLabel: String = "SQ",
    val entitlementName: String = MusicEntitlement.UNKNOWN.name,
    val extraMeta: Map<String, String>? = null,
    val artistId: String? = null,
    val albumId: String? = null,
) {
    val source: MusicSourceKind get() = MusicSourceKind.fromId(sourceId)
    val entitlement: MusicEntitlement
        get() = runCatching { MusicEntitlement.valueOf(entitlementName) }.getOrDefault(MusicEntitlement.UNKNOWN)

    val primaryArtistName: String
        get() = artist.substringBefore("/").trim().ifEmpty { artist }

    val sourceDisplayLabel: String
        get() = extraMeta?.get("sourceName")?.takeIf { it.isNotBlank() } ?: source.label

    val formattedDuration: String
        get() {
            val sec = duration.toInt().coerceAtLeast(0)
            return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
        }
}

data class MusicAlbumSummary(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String?,
    val sourceId: String,
    val coverURL: String,
    val yearText: String?,
    val trackCount: Int,
)

data class MusicArtistDetail(
    val id: String,
    val name: String,
    val sourceId: String,
    val coverURL: String,
    val bio: String,
    val hotSongs: List<MusicTrack>,
    val albums: List<MusicAlbumSummary>,
)

data class MusicAlbumDetail(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String?,
    val sourceId: String,
    val coverURL: String,
    val releaseDate: String?,
    val company: String?,
    val description: String,
    val tracks: List<MusicTrack>,
)

data class MusicChart(
    val id: String,
    val name: String,
    val subtitle: String,
    val sourceId: String,
    val updatedLabel: String,
    val coverURLs: List<String>,
    val tracks: List<MusicTrack>,
) {
    val source: MusicSourceKind get() = MusicSourceKind.fromId(sourceId)
}

data class MusicPlaylist(
    val id: String,
    val name: String,
    val subtitle: String,
    val coverURL: String = "",
    val tracks: List<MusicTrack> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val sourceId: String? = null,
    val remoteId: String? = null,
    val trackCountHint: Int = 0,
    val playCountText: String? = null,
    val isSubscribed: Boolean = false,
) {
    val source: MusicSourceKind? get() = sourceId?.takeIf { it.isNotBlank() }?.let { MusicSourceKind.fromId(it) }
    val isCloud: Boolean get() = source != null && !remoteId.isNullOrBlank()
    val displayTrackCount: Int get() = maxOf(tracks.size, trackCountHint)
    val displayCoverURL: String get() = coverURL.ifBlank { tracks.firstOrNull { it.coverURL.isNotBlank() }?.coverURL.orEmpty() }
}

data class MusicLyricLine(
    val id: Int,
    val time: Double,
    val text: String,
    val translation: String? = null,
    val romaji: String? = null,
)

data class MusicSignalPath(
    val sourceStage: String,
    val streamStage: String,
    val decodeStage: String,
    val outputStage: String,
    val isLossless: Boolean,
    val isCached: Boolean,
)

data class MusicAlternative(
    val id: String,
    val source: MusicSourceKind,
    val track: MusicTrack,
    val qualityLabel: String,
    val entitlement: MusicEntitlement,
    val streamURL: String,
    val headers: Map<String, String> = emptyMap(),
)

data class MusicNASConfig(
    val id: String,
    val kindId: String,
    val name: String,
    val serverURL: String,
    val username: String,
    val passwordOrToken: String,
    val enabled: Boolean = true,
) {
    val kind: MusicSourceKind get() = MusicSourceKind.fromId(kindId)
}

data class MusicSourcePref(
    val id: String,
    val kindId: String,
    val enabled: Boolean = true,
    val customCookie: String? = null,
    val accountNickname: String? = null,
    val accountAvatarURL: String? = null,
) {
    val kind: MusicSourceKind get() = MusicSourceKind.fromId(kindId)
}

data class MusicSettings(
    val lrclibEnabled: Boolean = true,
    val musicBrainzEnabled: Boolean = true,
    val wifiOnlyCache: Boolean = true,
    val maxCacheMB: Int = 2048,
    val autoFallback: Boolean = true,
    val preferredQualityId: String? = null,
) {
    val preferredQuality: MusicAudioQuality
        get() = MusicAudioQuality.fromId(preferredQualityId)
}

object MusicLyricsParser {
    private val timeRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val krcLineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val krcWordTagRegex = Regex("""<[^>]+>""")
    private val creditWords = listOf("作词", "作曲", "编曲", "制作", "混音", "母带", "录音", "吉他", "和声", "出品", "监制", "词：", "曲：", "词:", "曲:")

    fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input
        return input
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }

    fun parse(main: String, translation: String? = null, romaji: String? = null): List<MusicLyricLine> {
        val base = parseSingle(main)
        if (base.isEmpty()) return emptyList()
        val trans = translation?.takeIf { it.isNotBlank() }?.let { parseSingle(it) }.orEmpty()
        val roma = romaji?.takeIf { it.isNotBlank() }?.let { parseSingle(it) }.orEmpty()

        return base.mapIndexed { idx, (time, text) ->
            val tMatch = trans.minByOrNull { kotlin.math.abs(it.first - time) }?.takeIf { kotlin.math.abs(it.first - time) < 0.85 }?.second
            val rMatch = roma.minByOrNull { kotlin.math.abs(it.first - time) }?.takeIf { kotlin.math.abs(it.first - time) < 0.85 }?.second
            MusicLyricLine(id = idx, time = time, text = text, translation = tMatch, romaji = rMatch)
        }
    }

    private fun parseSingle(raw: String): List<Pair<Double, String>> {
        val out = ArrayList<Pair<Double, String>>()
        for (line in decodeHtmlEntities(raw).lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val matches = timeRegex.findAll(trimmed).toList()
            if (matches.isNotEmpty()) {
                val content = timeRegex.replace(trimmed, "").trim()
                if (content.isEmpty()) continue
                for (m in matches) {
                    val min = m.groupValues[1].toDoubleOrNull() ?: continue
                    val sec = m.groupValues[2].toDoubleOrNull() ?: continue
                    val fracStr = m.groupValues[3]
                    val frac = when (fracStr.length) {
                        0 -> 0.0
                        1 -> (fracStr.toDoubleOrNull() ?: 0.0) / 10.0
                        2 -> (fracStr.toDoubleOrNull() ?: 0.0) / 100.0
                        else -> (fracStr.take(3).toDoubleOrNull() ?: 0.0) / 1000.0
                    }
                    out += (min * 60.0 + sec + frac) to content
                }
            } else {
                val km = krcLineRegex.matchEntire(trimmed) ?: continue
                val startMs = km.groupValues[1].toDoubleOrNull() ?: continue
                val text = krcWordTagRegex.replace(km.groupValues[3], "")
                    .replace("  ", " ")
                    .trim()
                if (text.isNotEmpty()) {
                    out += (startMs / 1000.0) to text
                }
            }
        }
        return out.sortedBy { it.first }
    }

    fun pickYueqianHook(lines: List<MusicLyricLine>): String? {
        val good = lines.map { it.text.trim() }.filter { t ->
            t.length in 6..26 && creditWords.none { t.contains(it) }
        }
        if (good.isEmpty()) return null
        return good[(good.size / 3).coerceAtMost(good.lastIndex)]
    }
}

class MusicStore(private val ctx: Context) {
    companion object {
        const val FAVORITES_KEY = "stv.music.favorites"
        const val PLAYLISTS_KEY = "stv.music.playlists"
        const val CLOUD_PLAYLISTS_KEY = "stv.music.cloudPlaylists"
        const val RECENTS_KEY = "stv.music.recents"
        const val SOURCES_KEY = "stv.music.sources"
        const val NAS_KEY = "stv.music.nas"
        const val SETTINGS_KEY = "stv.music.settings"
    }

    private val gson = Gson()
    private val sp get() = ctx.getSharedPreferences("idj", Context.MODE_PRIVATE)
    val cacheDir: File = File(ctx.cacheDir, "MusicAudioCache").apply { mkdirs() }

    var favorites by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var playlists by mutableStateOf<List<MusicPlaylist>>(emptyList())
        private set
    var cloudPlaylists by mutableStateOf<List<MusicPlaylist>>(emptyList())
        private set
    var syncingCloudPlaylists by mutableStateOf(false)
        private set
    var recents by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var sourcePrefs by mutableStateOf<List<MusicSourcePref>>(emptyList())
        private set
    var nasConfigs by mutableStateOf<List<MusicNASConfig>>(emptyList())
        private set
    var settings by mutableStateOf(MusicSettings())
        private set
    var nasTracks by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var cacheBytes by mutableLongStateOf(0L)
        private set
    var cachedCount by mutableStateOf(0)
        private set

    init {
        purgeLegacyDotAudioFiles()
        reloadFromPrefs()
        refreshCacheStats()
    }

    fun reloadFromPrefs() {
        favorites = loadList(FAVORITES_KEY, object : TypeToken<List<MusicTrack>>() {}) ?: emptyList()
        playlists = loadList(PLAYLISTS_KEY, object : TypeToken<List<MusicPlaylist>>() {}) ?: emptyList()
        cloudPlaylists = loadList(CLOUD_PLAYLISTS_KEY, object : TypeToken<List<MusicPlaylist>>() {}) ?: emptyList()
        recents = loadList(RECENTS_KEY, object : TypeToken<List<MusicTrack>>() {}) ?: emptyList()
        nasConfigs = loadList(NAS_KEY, object : TypeToken<List<MusicNASConfig>>() {}) ?: emptyList()
        settings = runCatching {
            sp.getString(SETTINGS_KEY, null)?.let { gson.fromJson(it, MusicSettings::class.java) }
        }.getOrNull() ?: MusicSettings()

        val loadedSources = loadList(SOURCES_KEY, object : TypeToken<List<MusicSourcePref>>() {})
        sourcePrefs = if (!loadedSources.isNullOrEmpty()) {
            if (loadedSources.none { it.kind == MusicSourceKind.QISHUI }) {
                val migrated = loadedSources.toMutableList()
                val idx = migrated.indexOfFirst { it.kind == MusicSourceKind.ITUNES }.takeIf { it >= 0 } ?: migrated.size
                migrated.add(idx, MusicSourcePref("qishui", MusicSourceKind.QISHUI.id, true, null))
                save(SOURCES_KEY, migrated)
                migrated
            } else {
                loadedSources
            }
        } else {
            listOf(
                MusicSourcePref("netease", MusicSourceKind.NETEASE.id, true, null),
                MusicSourcePref("qq", MusicSourceKind.QQ.id, true, null),
                MusicSourcePref("kugou", MusicSourceKind.KUGOU.id, true, null),
                MusicSourcePref("qishui", MusicSourceKind.QISHUI.id, true, null),
                MusicSourcePref("itunes", MusicSourceKind.ITUNES.id, true, null),
            )
        }
        AppAudioArbiter.reloadFromPrefs()
    }

    val enabledOnlineSources: List<MusicSourceKind>
        get() = sourcePrefs.filter { it.enabled }.map { it.kind }

    fun cookieMap(): Map<MusicSourceKind, String> = buildMap {
        sourcePrefs.forEach { p ->
            p.customCookie?.trim()?.takeIf { it.isNotEmpty() }?.let { put(p.kind, it) }
        }
    }

    fun isSourceLoggedIn(kind: MusicSourceKind): Boolean =
        sourcePrefs.firstOrNull { it.kind == kind }?.customCookie?.trim()?.isNotEmpty() == true

    fun isFavorite(track: MusicTrack): Boolean =
        favorites.any { it.id == track.id || (it.title == track.title && it.artist == track.artist) }

    fun toggleFavorite(track: MusicTrack) {
        val next = favorites.toMutableList()
        val idx = next.indexOfFirst { it.id == track.id || (it.title == track.title && it.artist == track.artist) }
        if (idx >= 0) next.removeAt(idx) else next.add(0, track)
        favorites = next
        save(FAVORITES_KEY, next)
    }

    fun recordRecent(track: MusicTrack) {
        val next = recents.filterNot { it.id == track.id }.toMutableList()
        next.add(0, track)
        if (next.size > 120) next.subList(120, next.size).clear()
        recents = next
        save(RECENTS_KEY, next)
    }

    fun createPlaylist(name: String, tracks: List<MusicTrack> = emptyList()): MusicPlaylist {
        val clean = name.trim().ifEmpty { "新建歌单" }
        val pl = MusicPlaylist(
            id = "pl-${System.currentTimeMillis()}-${(100..999).random()}",
            name = clean,
            subtitle = "${tracks.size} 首歌曲",
            coverURL = tracks.firstOrNull { it.coverURL.isNotBlank() }?.coverURL.orEmpty(),
            tracks = tracks,
        )
        val next = listOf(pl) + playlists
        playlists = next
        save(PLAYLISTS_KEY, next)
        return pl
    }

    fun cloneChartAsPlaylist(name: String, tracks: List<MusicTrack>): Pair<Int, Int> {
        val seen = HashSet<String>()
        val dedup = ArrayList<MusicTrack>()
        var skipped = 0
        for (t in tracks) {
            val k = "${t.title.lowercase(Locale.ROOT)}|${t.artist.lowercase(Locale.ROOT)}"
            if (seen.add(k)) dedup += t else skipped++
        }
        createPlaylist(name, dedup)
        return dedup.size to skipped
    }

    fun addTrack(track: MusicTrack, playlistId: String) {
        val next = playlists.map { pl ->
            if (pl.id != playlistId) pl
            else if (pl.tracks.any { it.id == track.id }) pl
            else {
                val updated = pl.tracks + track
                pl.copy(
                    tracks = updated,
                    subtitle = "${updated.size} 首歌曲",
                    coverURL = pl.coverURL.ifBlank { track.coverURL },
                )
            }
        }
        playlists = next
        save(PLAYLISTS_KEY, next)
    }

    fun deletePlaylist(id: String) {
        val next = playlists.filterNot { it.id == id }
        playlists = next
        save(PLAYLISTS_KEY, next)
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        val next = sourcePrefs.map { if (it.id == id) it.copy(enabled = enabled) else it }
        sourcePrefs = next
        save(SOURCES_KEY, next)
    }

    fun moveSource(id: String, up: Boolean) {
        val idx = sourcePrefs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val target = if (up) idx - 1 else idx + 1
        if (target !in sourcePrefs.indices) return
        val next = sourcePrefs.toMutableList()
        val item = next.removeAt(idx)
        next.add(target, item)
        sourcePrefs = next
        save(SOURCES_KEY, next)
    }

    fun setCookie(id: String, cookie: String?) {
        val clean = cookie?.trim()?.takeIf { it.isNotEmpty() }
        val removedKind = sourcePrefs.firstOrNull { it.id == id }?.kind
        val next = sourcePrefs.map {
            if (it.id == id) {
                if (clean == null) it.copy(customCookie = null, accountNickname = null, accountAvatarURL = null)
                else it.copy(customCookie = clean)
            } else it
        }
        sourcePrefs = next
        save(SOURCES_KEY, next)
        if (clean == null && removedKind != null) {
            val filtered = cloudPlaylists.filterNot { it.source == removedKind }
            if (filtered.size != cloudPlaylists.size) {
                cloudPlaylists = filtered
                save(CLOUD_PLAYLISTS_KEY, filtered)
            }
        }
    }

    fun setSourceAccount(id: String, cookie: String, nickname: String?, avatarURL: String?) {
        val next = sourcePrefs.map {
            if (it.id == id) it.copy(customCookie = cookie.trim(), accountNickname = nickname, accountAvatarURL = avatarURL)
            else it
        }
        sourcePrefs = next
        save(SOURCES_KEY, next)
    }

    fun logoutSource(id: String) = setCookie(id, null)

    val hasAnyLoggedInSource: Boolean
        get() = cookieMap().isNotEmpty()

    val loginFingerprint: String
        get() = sourcePrefs.mapNotNull { p ->
            val ck = p.customCookie?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            "${p.kind.id}:${ck.length}:${p.accountNickname.orEmpty()}"
        }.joinToString("|")

    fun findPlaylistById(id: String): MusicPlaylist? =
        playlists.firstOrNull { it.id == id }
            ?: cloudPlaylists.firstOrNull { it.id == id }
            ?: MusicProviderHub.findCachedRemotePlaylist(id)

    fun updateCloudPlaylistTracks(playlistId: String, tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        val idx = cloudPlaylists.indexOfFirst { it.id == playlistId }
        if (idx < 0) return
        val next = cloudPlaylists.toMutableList()
        val cur = next[idx]
        next[idx] = cur.copy(tracks = tracks, trackCountHint = maxOf(cur.trackCountHint, tracks.size))
        cloudPlaylists = next
        save(CLOUD_PLAYLISTS_KEY, next)
    }

    suspend fun syncCloudPlaylists(): Int {
        val map = cookieMap()
        if (map.isEmpty()) {
            if (cloudPlaylists.isNotEmpty()) {
                cloudPlaylists = emptyList()
                save(CLOUD_PLAYLISTS_KEY, cloudPlaylists)
            }
            return 0
        }
        if (syncingCloudPlaylists) return cloudPlaylists.size
        syncingCloudPlaylists = true
        return try {
            val fetched = MusicProviderHub.fetchUserCloudPlaylists(map)
            if (fetched.isNotEmpty()) {
                val existing = cloudPlaylists.associate { it.id to it.tracks }
                val merged = fetched.map { pl ->
                    if (pl.tracks.isEmpty()) {
                        val cached = existing[pl.id]
                        if (!cached.isNullOrEmpty()) pl.copy(tracks = cached) else pl
                    } else pl
                }
                cloudPlaylists = merged
                save(CLOUD_PLAYLISTS_KEY, merged)
            }
            cloudPlaylists.size
        } finally {
            syncingCloudPlaylists = false
        }
    }

    fun saveNAS(config: MusicNASConfig) {
        val next = nasConfigs.toMutableList()
        val idx = next.indexOfFirst { it.id == config.id }
        if (idx >= 0) next[idx] = config else next.add(0, config)
        nasConfigs = next
        save(NAS_KEY, next)
    }

    fun deleteNAS(id: String) {
        val next = nasConfigs.filterNot { it.id == id }
        nasConfigs = next
        save(NAS_KEY, next)
    }

    fun updateNASTracks(tracks: List<MusicTrack>) {
        nasTracks = tracks
    }

    fun updateSettings(transform: (MusicSettings) -> MusicSettings) {
        settings = transform(settings)
        save(SETTINGS_KEY, settings)
    }

    // LRU Audio Cache

    private fun cacheStem(track: MusicTrack): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(track.id.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun detectAudioExtension(bytes: ByteArray, sourceUrl: String? = null): String? {
        if (bytes.size < 12) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        if (b0 == 0x3C || b0 == 0x7B) return null // reject HTML / JSON
        if (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) return "flac"
        if (bytes[4].toInt() == 0x66 && bytes[5].toInt() == 0x74 && bytes[6].toInt() == 0x79 && bytes[7].toInt() == 0x70) return "m4a"
        if ((b0 == 0x49 && b1 == 0x44 && b2 == 0x33) || (b0 == 0xFF && (b1 and 0xE0) == 0xE0)) return "mp3"
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) return "wav"
        val ext = sourceUrl?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        return if (ext in setOf("mp3", "m4a", "flac", "aac", "wav")) ext else null
    }

    private fun purgeLegacyDotAudioFiles() {
        runCatching { cacheDir.listFiles()?.filter { it.extension.equals("audio", true) }?.forEach { it.delete() } }
    }

    fun cachedFile(track: MusicTrack): File? {
        val stem = cacheStem(track)
        for (ext in listOf("flac", "wav", "m4a", "mp3", "aac")) {
            val f = File(cacheDir, "$stem.$ext")
            if (f.isFile && f.length() > 16_000L) {
                f.setLastModified(System.currentTimeMillis())
                return f
            }
        }
        return null
    }

    fun removeCachedFile(track: MusicTrack) {
        val stem = cacheStem(track)
        runCatching {
            for (ext in listOf("flac", "wav", "m4a", "mp3", "aac")) {
                File(cacheDir, "$stem.$ext").takeIf { it.exists() }?.delete()
            }
        }
        refreshCacheStats()
    }

    fun cacheAudioBytes(bytes: ByteArray, track: MusicTrack, sourceUrl: String? = null) {
        if (bytes.size < 16_000) return
        val ext = detectAudioExtension(bytes, sourceUrl) ?: return
        runCatching {
            val stem = cacheStem(track)
            for (oldExt in listOf("flac", "wav", "m4a", "mp3", "aac")) {
                if (oldExt != ext) File(cacheDir, "$stem.$oldExt").takeIf { it.exists() }?.delete()
            }
            val f = File(cacheDir, "$stem.$ext")
            f.writeBytes(bytes)
            evictCacheIfNeeded()
            refreshCacheStats()
        }
    }

    fun clearCache() {
        runCatching { cacheDir.listFiles()?.forEach { it.delete() } }
        refreshCacheStats()
    }

    fun refreshCacheStats() {
        val files = cacheDir.listFiles()?.filter { it.isFile }.orEmpty()
        cachedCount = files.size
        cacheBytes = files.sumOf { it.length() }
    }

    private fun evictCacheIfNeeded() {
        val maxBytes = settings.maxCacheMB.coerceAtLeast(128).toLong() * 1024L * 1024L
        val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    val formattedCacheSize: String
        get() {
            val mb = cacheBytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
            else String.format(Locale.US, "%.1f MB", mb)
        }

    private fun <T> loadList(key: String, type: TypeToken<T>): T? = runCatching {
        sp.getString(key, null)?.let { gson.fromJson<T>(it, type.type) }
    }.getOrNull()

    private fun save(key: String, value: Any) {
        sp.edit().putString(key, gson.toJson(value)).apply()
    }
}
