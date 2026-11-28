package app.iduanju.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.Proxy
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 100% client-side serverless music provider hub for Android (direct upstream only, Proxy.NO_PROXY).
 * Direct upstream HTTP clients for NetEase, QQ Music, Kugou (HTTP), LRCLIB, iTunes,
 * plus personal NAS direct clients (Navidrome/OpenSubsonic, Emby/Jellyfin, fnOS).
 */
object MusicProviderHub {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val stableDeviceId: String by lazy { md5Hex("iduanju-android-${UUID.randomUUID()}") }

    internal val directClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val client = directClient

    private val noRedirectClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val longPollClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(38, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class ResolvedAudio(
        val url: String,
        val headers: Map<String, String>,
        val actualSource: MusicSourceKind,
        val qualityLabel: String,
        val entitlement: MusicEntitlement,
        val isCached: Boolean,
    )

    private data class ChartCacheEntry(val fetchedAtMs: Long, val charts: List<MusicChart>)
    private val chartCache = HashMap<MusicSourceKind, ChartCacheEntry>()
    private val chartsById = HashMap<String, MusicChart>()

    data class DailyRecResult(
        val tracks: List<MusicTrack>,
        val isPersonalized: Boolean,
        val subtitle: String,
    )

    private data class DailyRecCacheEntry(val key: String, val fetchedAtMs: Long, val result: DailyRecResult)
    @Volatile private var dailyRecCache: DailyRecCacheEntry? = null

    fun findCachedChart(id: String): MusicChart? = synchronized(chartsById) { chartsById[id] }

    fun cachedDailyTracks(limit: Int = 30): List<MusicTrack> {
        dailyRecCache?.result?.tracks?.takeIf { it.isNotEmpty() }?.let { return it.take(limit) }
        return synchronized(chartsById) {
            val nonHot = chartsById.values.filter { it.id != "ne-3778678" && !it.name.contains("热歌") }
            val buckets = (nonHot.ifEmpty { chartsById.values.toList() }).map { it.tracks }.filter { it.isNotEmpty() }
            if (buckets.isEmpty()) return emptyList()
            val out = ArrayList<MusicTrack>()
            val seen = HashSet<String>()
            val maxDepth = buckets.maxOf { it.size }
            for (d in 0 until maxDepth) {
                for (b in buckets) {
                    if (out.size >= limit) return out
                    if (d < b.size) {
                        val t = b[d]
                        if (seen.add("${t.title.lowercase(Locale.ROOT)}|${t.primaryArtistName.lowercase(Locale.ROOT)}")) {
                            out += t
                        }
                    }
                }
            }
            out
        }
    }

    suspend fun fetchDailyRecommendations(
        enabledSources: List<MusicSourceKind>,
        cookies: Map<MusicSourceKind, String>,
        force: Boolean = false,
    ): DailyRecResult = withContext(Dispatchers.IO) {
        val cal = java.util.Calendar.getInstance()
        val dayKey = "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.MONTH) + 1}-${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
        val allowed = enabledSources.toSet()
        val allowNE = allowed.isEmpty() || MusicSourceKind.NETEASE in allowed
        val allowKG = allowed.isEmpty() || MusicSourceKind.KUGOU in allowed
        val enabledKey = enabledSources.joinToString(",") { it.id }
        val neCookie = if (allowNE) cookies[MusicSourceKind.NETEASE]?.trim().orEmpty() else ""
        val kgCookie = if (allowKG) cookies[MusicSourceKind.KUGOU]?.trim().orEmpty() else ""
        val cacheKey = "$dayKey|$enabledKey|${md5Hex(neCookie).take(10)}|${md5Hex(kgCookie).take(10)}"
        val now = System.currentTimeMillis()
        if (!force) {
            dailyRecCache?.takeIf { it.key == cacheKey && now - it.fetchedAtMs < 30 * 60_000L }?.let {
                return@withContext it.result
            }
        }

        // 1) Real personalized NetEase account daily recommendations (only when NetEase source is enabled)
        if (allowNE && neCookie.isNotEmpty()) {
            val res = postNetEaseEAPI(
                apiPath = "/api/v3/discovery/recommend/songs",
                payload = JSONObject(),
                extraCookie = neCookie,
            )?.first
            if (res?.optInt("code", 0) == 200) {
                val arr = res.optJSONObject("data")?.optJSONArray("dailySongs")
                if (arr != null && arr.length() > 0) {
                    val tracks = ArrayList<MusicTrack>()
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONObject(i) ?: continue
                        val tid = t.optLong("id", 0L).takeIf { it > 0 }?.toString() ?: continue
                        val title = t.optString("name").trim().takeIf { it.isNotEmpty() } ?: continue
                        val arArr = t.optJSONArray("ar")
                        val artists = parseJoinedNames(arArr, "name").ifBlank { "未知歌手" }
                        val arId = arArr?.optJSONObject(0)?.optLong("id", 0L)?.takeIf { it > 0 }?.toString()
                        val al = t.optJSONObject("al")
                        val album = al?.optString("name").orEmpty()
                        val alId = al?.optLong("id", 0L)?.takeIf { it > 0 }?.toString()
                        val cover = secureHttps(al?.optString("picUrl").orEmpty())
                        val dt = (t.optLong("dt", 210_000L) / 1000.0).coerceAtLeast(30.0)
                        tracks += MusicTrack(
                            id = "netease:$tid",
                            title = title,
                            artist = artists,
                            album = album,
                            coverURL = cover,
                            duration = dt,
                            sourceId = MusicSourceKind.NETEASE.id,
                            sourceTrackId = tid,
                            qualityLabel = "HQ 320k",
                            entitlementName = MusicEntitlement.FULL.name,
                            artistId = arId,
                            albumId = alId,
                        )
                    }
                    if (tracks.isNotEmpty()) {
                        val out = DailyRecResult(tracks, true, "网易云账号私人日推 · 共 ${tracks.size} 首")
                        dailyRecCache = DailyRecCacheEntry(cacheKey, now, out)
                        return@withContext out
                    }
                }
            }
        }

        // 2) Real personalized Kugou account daily recommendations when Kugou is enabled & logged in
        if (allowKG && kgCookie.isNotEmpty()) {
            val kgTracks = fetchKugouDailyRecommendations(kgCookie)
            if (kgTracks.isNotEmpty()) {
                val out = DailyRecResult(kgTracks, true, "酷狗账号私人日推 · 共 ${kgTracks.size} 首")
                dailyRecCache = DailyRecCacheEntry(cacheKey, now, out)
                return@withContext out
            }
        }

        // 3) Logged-out / fallback: diverse daily-seeded round-robin mix across non-Hot charts & sources
        val charts = fetchCharts(enabledSources, cookies, force = false)
        val nonHot = charts.filter { it.id != "ne-3778678" && !it.name.contains("热歌") }
        val buckets = (nonHot.ifEmpty { charts }).map { it.tracks }.filter { it.isNotEmpty() }
        val daySeed = cal.get(java.util.Calendar.DAY_OF_YEAR)
        val picked = ArrayList<MusicTrack>()
        val seen = HashSet<String>()
        if (buckets.isNotEmpty()) {
            val maxDepth = buckets.maxOf { it.size }
            for (depth in 0 until maxDepth) {
                if (picked.size >= 30) break
                for (bIdx in buckets.indices) {
                    if (picked.size >= 30) break
                    val list = buckets[(bIdx + daySeed) % buckets.size]
                    val pos = (depth + (daySeed * 3 + bIdx * 5)) % list.size
                    val t = list[pos]
                    if (seen.add("${t.title.lowercase(Locale.ROOT)}|${t.primaryArtistName.lowercase(Locale.ROOT)}")) {
                        picked += t
                    }
                }
            }
        }
        val out = DailyRecResult(picked, false, "多源今日混排精选 · 共 ${picked.size} 首（登录网易云或酷狗解锁私人日推）")
        dailyRecCache = DailyRecCacheEntry(cacheKey, now, out)
        out
    }

    private fun fetchKugouDailyRecommendations(cookie: String): List<MusicTrack> {
        val (token, userid, _, dfid) = parseKugouCookie(cookie)
        if (token.isBlank()) return emptyList()
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val bodyStr = """{"platform":"ios"}"""
        val qp = linkedMapOf(
            "dfid" to dfid, "mid" to stableDeviceId, "uuid" to "-", "appid" to "1005",
            "clientver" to "11430", "clienttime" to ts, "token" to token,
            "userid" to userid, "platform" to "ios",
        )
        qp["signature"] = kugouAndroidSign(qp, bodyStr)
        val json = postJson(
            "https://gateway.kugou.com/everyday_song_recommend?${kugouQuery(qp)}",
            bodyStr,
            mapOf(
                "User-Agent" to "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi",
                "x-router" to "everydayrec.service.kugou.com",
                "dfid" to dfid,
                "mid" to stableDeviceId,
                "clienttime" to ts,
            ),
        ) ?: return emptyList()
        val arr = json.optJSONObject("data")?.optJSONArray("song_list") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val hash = s.optString("hash").trim().takeIf { it.isNotEmpty() } ?: continue
                val title = s.optString("songname").ifBlank { s.optString("filename") }.trim().takeIf { it.isNotEmpty() } ?: continue
                val singer = s.optString("author_name").ifBlank { s.optString("singername") }.trim().ifBlank { "未知歌手" }
                val album = s.optString("album_name").trim()
                val cover = secureHttps(s.optString("sizable_cover").replace("{size}", "400"))
                val rawDur = s.optDouble("time_length", 210_000.0)
                val durSec = (if (rawDur > 1000.0) rawDur / 1000.0 else rawDur).coerceAtLeast(30.0)
                add(
                    MusicTrack(
                        id = "kugou:$hash",
                        title = title,
                        artist = singer,
                        album = album,
                        coverURL = cover,
                        duration = durSec,
                        sourceId = MusicSourceKind.KUGOU.id,
                        sourceTrackId = hash,
                        qualityLabel = "HQ 320k",
                        entitlementName = MusicEntitlement.FULL.name,
                    ),
                )
            }
        }
    }

    // ── Personal FM, Recommended Playlists & Cloud Playlist Sync (100% Direct Zero-Proxy) ────────────

    data class PersonalFMResult(
        val tracks: List<MusicTrack>,
        val isPersonalized: Boolean,
        val sourceLabel: String,
    )

    data class RecommendPlaylistsResult(
        val playlists: List<MusicPlaylist>,
        val isPersonalized: Boolean,
    )

    private data class RecPlCacheEntry(val key: String, val fetchedAtMs: Long, val result: RecommendPlaylistsResult)
    @Volatile private var recPlaylistsCache: RecPlCacheEntry? = null
    private val remotePlaylistsById = HashMap<String, MusicPlaylist>()

    fun findCachedRemotePlaylist(id: String): MusicPlaylist? =
        synchronized(remotePlaylistsById) { remotePlaylistsById[id] }

    private fun formatPlayCount(count: Double): String = when {
        count >= 100_000_000.0 -> String.format(Locale.US, "%.1f亿", count / 100_000_000.0)
        count >= 10_000.0 -> "${(count / 10_000.0).toInt()}万"
        count > 0.0 -> "${count.toInt()}"
        else -> ""
    }

    private fun parseNetEaseSongObj(t: JSONObject, loggedIn: Boolean = false): MusicTrack? {
        val tid = t.optLong("id", 0L).takeIf { it > 0L }?.toString() ?: return null
        val title = t.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        val arArr = t.optJSONArray("ar") ?: t.optJSONArray("artists")
        val artists = parseJoinedNames(arArr, "name").ifBlank { "未知歌手" }
        val arId = arArr?.optJSONObject(0)?.optLong("id", 0L)?.takeIf { it > 0L }?.toString()
        val al = t.optJSONObject("al") ?: t.optJSONObject("album")
        val album = al?.optString("name").orEmpty()
        val alId = al?.optLong("id", 0L)?.takeIf { it > 0L }?.toString()
        val cover = secureHttps(al?.optString("picUrl").orEmpty().ifBlank { al?.optString("blurPicUrl").orEmpty() })
        val rawDt = t.optLong("dt", 0L).takeIf { it > 0L } ?: t.optLong("duration", 210_000L)
        val fee = t.optInt("fee", 0)
        val ent = if (!loggedIn && (fee == 1 || fee == 4)) MusicEntitlement.PREVIEW else MusicEntitlement.FULL
        return MusicTrack(
            id = "netease:$tid",
            title = title,
            artist = artists,
            album = album,
            coverURL = cover,
            duration = (rawDt / 1000.0).coerceAtLeast(30.0),
            sourceId = MusicSourceKind.NETEASE.id,
            sourceTrackId = tid,
            qualityLabel = if (ent == MusicEntitlement.FULL) "HQ 320k" else "320k / 自动换源",
            entitlementName = ent.name,
            artistId = arId,
            albumId = alId,
        )
    }

    suspend fun fetchPersonalFM(
        enabledSources: List<MusicSourceKind>,
        cookies: Map<MusicSourceKind, String>,
    ): PersonalFMResult = withContext(Dispatchers.IO) {
        val allowed = enabledSources.toSet()
        val allowNE = allowed.isEmpty() || MusicSourceKind.NETEASE in allowed
        val allowKG = allowed.isEmpty() || MusicSourceKind.KUGOU in allowed
        val neCookie = if (allowNE) cookies[MusicSourceKind.NETEASE]?.trim().orEmpty() else ""
        if (neCookie.isNotEmpty()) {
            val collected = ArrayList<MusicTrack>()
            val seen = HashSet<String>()
            val hdrs = mapOf("User-Agent" to UA, "Referer" to "https://music.163.com/", "Cookie" to neCookie)
            repeat(3) {
                val json = getJson("https://music.163.com/api/v1/radio/get", hdrs) ?: return@repeat
                val arr = json.optJSONArray("data") ?: return@repeat
                for (i in 0 until arr.length()) {
                    val tr = arr.optJSONObject(i)?.let { parseNetEaseSongObj(it, loggedIn = true) } ?: continue
                    if (seen.add(tr.id)) collected += tr
                }
            }
            if (collected.isNotEmpty()) {
                return@withContext PersonalFMResult(collected, true, "网易云私人 FM")
            }
        }
        val kgCookie = if (allowKG) cookies[MusicSourceKind.KUGOU]?.trim().orEmpty() else ""
        if (kgCookie.isNotEmpty()) {
            val kg = fetchKugouDailyRecommendations(kgCookie)
            if (kg.isNotEmpty()) {
                return@withContext PersonalFMResult(kg.shuffled(), true, "酷狗私人漫游 FM")
            }
        }
        val daily = fetchDailyRecommendations(enabledSources, cookies, force = false)
        PersonalFMResult(daily.tracks.shuffled(), daily.isPersonalized, "多源探索 FM")
    }

    suspend fun fetchRecommendedPlaylists(
        enabledSources: List<MusicSourceKind>,
        cookies: Map<MusicSourceKind, String>,
        force: Boolean = false,
    ): RecommendPlaylistsResult = withContext(Dispatchers.IO) {
        val neCookie = cookies[MusicSourceKind.NETEASE]?.trim().orEmpty()
        val key = "${md5Hex(neCookie).take(10)}|${enabledSources.joinToString(",") { it.id }}"
        val now = System.currentTimeMillis()
        if (!force) {
            recPlaylistsCache?.takeIf { it.key == key && now - it.fetchedAtMs < 20 * 60_000L }?.let {
                return@withContext it.result
            }
        }
        val allow = enabledSources.toSet()
        val allowNE = allow.isEmpty() || MusicSourceKind.NETEASE in allow
        val allowQQ = allow.isEmpty() || MusicSourceKind.QQ in allow
        val allowQS = allow.isEmpty() || MusicSourceKind.QISHUI in allow

        val (nePersonal, nePublic, qqList, qsCharts) = coroutineScope {
            val d1 = async { if (allowNE && neCookie.isNotEmpty()) runCatching { fetchNetEasePersonalizedPlaylists(neCookie) }.getOrDefault(emptyList()) else emptyList() }
            val d2 = async { if (allowNE) runCatching { fetchNetEasePublicRecommendPlaylists() }.getOrDefault(emptyList()) else emptyList() }
            val d3 = async { if (allowQQ) runCatching { fetchQQRecommendPlaylists() }.getOrDefault(emptyList()) else emptyList() }
            val d4 = async { if (allowQS) runCatching { fetchQishuiCharts() }.getOrDefault(emptyList()) else emptyList() }
            listOf(d1.await(), d2.await(), d3.await(), d4.await())
        }
        @Suppress("UNCHECKED_CAST")
        val p1 = nePersonal as List<MusicPlaylist>
        @Suppress("UNCHECKED_CAST")
        val p2 = nePublic as List<MusicPlaylist>
        @Suppress("UNCHECKED_CAST")
        val p3 = qqList as List<MusicPlaylist>
        @Suppress("UNCHECKED_CAST")
        val qs = (qsCharts as List<MusicChart>).map { c ->
            val rid = c.id.removePrefix("qishui:")
            MusicPlaylist(
                id = "rec:qishui:$rid",
                name = c.name,
                subtitle = "汽水精选 · ${c.tracks.size} 首",
                coverURL = c.coverURLs.firstOrNull().orEmpty(),
                tracks = c.tracks,
                sourceId = MusicSourceKind.QISHUI.id,
                remoteId = rid,
                trackCountHint = c.tracks.size,
                playCountText = "汽水精选",
            )
        }
        val merged = ArrayList<MusicPlaylist>()
        val seen = HashSet<String>()
        fun addIfNew(pl: MusicPlaylist) {
            if (seen.add(pl.id)) merged += pl
        }
        p1.forEach(::addIfNew)
        val maxLen = maxOf(p2.size, maxOf(p3.size, qs.size))
        for (i in 0 until maxLen) {
            if (i < p2.size) addIfNew(p2[i])
            if (i < p3.size) addIfNew(p3[i])
            if (i < qs.size) addIfNew(qs[i])
        }
        val capped = merged.take(18)
        synchronized(remotePlaylistsById) {
            capped.forEach { remotePlaylistsById[it.id] = it }
        }
        val out = RecommendPlaylistsResult(capped, p1.isNotEmpty())
        if (capped.isNotEmpty()) recPlaylistsCache = RecPlCacheEntry(key, now, out)
        out
    }

    private fun fetchNetEasePersonalizedPlaylists(cookie: String): List<MusicPlaylist> {
        val json = getJson(
            "https://music.163.com/api/v1/discovery/recommend/resource",
            mapOf("Referer" to "https://music.163.com/", "User-Agent" to UA, "Cookie" to cookie),
        ) ?: return emptyList()
        if (json.optInt("code", 0) != 200) return emptyList()
        val arr = json.optJSONArray("recommend") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val rid = it.optLong("id", 0L).takeIf { v -> v > 0L }?.toString() ?: continue
                val name = it.optString("name").trim().takeIf { s -> s.isNotEmpty() } ?: continue
                val pic = secureHttps(it.optString("picUrl"))
                val cnt = it.optInt("trackCount", 0)
                val pc = formatPlayCount(it.optDouble("playcount", 0.0)).ifBlank { "个性推荐" }
                val copy = it.optString("copywriter").trim()
                val sub = copy.ifBlank { "网易云个性推荐" + if (cnt > 0) " · $cnt 首" else "" }
                add(MusicPlaylist("rec:netease:$rid", name, sub, pic, emptyList(), sourceId = MusicSourceKind.NETEASE.id, remoteId = rid, trackCountHint = cnt, playCountText = pc))
            }
        }
    }

    private fun fetchNetEasePublicRecommendPlaylists(): List<MusicPlaylist> {
        val json = getJson(
            "https://music.163.com/api/personalized/playlist?limit=10",
            mapOf("Referer" to "https://music.163.com/", "User-Agent" to UA),
        ) ?: return emptyList()
        val arr = json.optJSONArray("result") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val rid = it.optLong("id", 0L).takeIf { v -> v > 0L }?.toString() ?: continue
                val name = it.optString("name").trim().takeIf { s -> s.isNotEmpty() } ?: continue
                val pic = secureHttps(it.optString("picUrl"))
                val cnt = it.optInt("trackCount", 0)
                val pc = formatPlayCount(it.optDouble("playCount", 0.0)).takeIf { s -> s.isNotBlank() }
                add(MusicPlaylist("rec:netease:$rid", name, "网易云推荐歌单" + if (cnt > 0) " · $cnt 首" else "", pic, emptyList(), sourceId = MusicSourceKind.NETEASE.id, remoteId = rid, trackCountHint = cnt, playCountText = pc))
            }
        }
    }

    private fun fetchQQRecommendPlaylists(): List<MusicPlaylist> {
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?picmid=1&rnd=0.5&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0&categoryId=10000000&sortId=5&sin=0&ein=5"
        val json = getJson(url, mapOf("Referer" to "https://y.qq.com/", "User-Agent" to UA)) ?: return emptyList()
        val arr = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val rid = it.optString("dissid").trim().takeIf { s -> s.isNotEmpty() } ?: continue
                val name = MusicLyricsParser.decodeHtmlEntities(it.optString("dissname").trim()).takeIf { s -> s.isNotEmpty() } ?: continue
                val pic = secureHttps(it.optString("imgurl"))
                val pc = formatPlayCount(it.optDouble("listennum", 0.0)).takeIf { s -> s.isNotBlank() }
                add(MusicPlaylist("rec:qq:$rid", name, "QQ 音乐精选歌单", pic, emptyList(), sourceId = MusicSourceKind.QQ.id, remoteId = rid, playCountText = pc))
            }
        }
    }

    suspend fun fetchUserCloudPlaylists(cookies: Map<MusicSourceKind, String>): List<MusicPlaylist> = withContext(Dispatchers.IO) {
        coroutineScope {
            val jobs = listOfNotNull(
                cookies[MusicSourceKind.NETEASE]?.trim()?.takeIf { it.isNotEmpty() }?.let { ck ->
                    async<Pair<Int, List<MusicPlaylist>>> { 0 to runCatching { fetchNetEaseUserPlaylists(ck) }.getOrDefault(emptyList()) }
                },
                cookies[MusicSourceKind.QQ]?.trim()?.takeIf { it.isNotEmpty() }?.let { ck ->
                    async<Pair<Int, List<MusicPlaylist>>> { 1 to runCatching { fetchQQUserPlaylists(ck) }.getOrDefault(emptyList()) }
                },
                cookies[MusicSourceKind.KUGOU]?.trim()?.takeIf { it.isNotEmpty() }?.let { ck ->
                    async<Pair<Int, List<MusicPlaylist>>> { 2 to runCatching { fetchKugouUserPlaylists(ck) }.getOrDefault(emptyList()) }
                },
                cookies[MusicSourceKind.QISHUI]?.trim()?.takeIf { it.isNotEmpty() }?.let { ck ->
                    async<Pair<Int, List<MusicPlaylist>>> { 3 to runCatching { fetchQishuiUserPlaylists(ck) }.getOrDefault(emptyList()) }
                },
            )
            jobs.map { it.await() }.sortedBy { it.first }.flatMap { it.second }
        }
    }

    private fun fetchNetEaseUserPlaylists(cookie: String): List<MusicPlaylist> {
        val hdrs = mapOf("Referer" to "https://music.163.com/", "User-Agent" to UA, "Cookie" to cookie)
        val acc = getJson("https://music.163.com/api/nuser/account/get", hdrs) ?: return emptyList()
        val uid = acc.optJSONObject("account")?.optLong("id", 0L)?.takeIf { it > 0L }
            ?: acc.optJSONObject("profile")?.optLong("userId", 0L)?.takeIf { it > 0L }
            ?: return emptyList()
        val json = getJson("https://music.163.com/api/user/playlist?uid=$uid&limit=60&offset=0&includeVideo=true", hdrs) ?: return emptyList()
        val arr = json.optJSONArray("playlist") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val rid = it.optLong("id", 0L).takeIf { v -> v > 0L }?.toString() ?: continue
                val name = it.optString("name").trim().takeIf { s -> s.isNotEmpty() } ?: continue
                val pic = secureHttps(it.optString("coverImgUrl"))
                val cnt = it.optInt("trackCount", 0)
                val sub = it.optBoolean("subscribed", false)
                val special = it.optInt("specialType", 0)
                val tag = if (special == 5) "我喜欢的音乐" else if (sub) "收藏歌单" else "自建歌单"
                add(MusicPlaylist("cloud:netease:$rid", name, "网易云 · $tag · $cnt 首", pic, emptyList(), sourceId = MusicSourceKind.NETEASE.id, remoteId = rid, trackCountHint = cnt, playCountText = if (special == 5) "红心" else null, isSubscribed = sub))
            }
        }
    }

    private fun fetchQQUserPlaylists(cookie: String): List<MusicPlaylist> {
        val uin = parseQQCookie(cookie).first
        if (uin.isBlank() || uin == "0") return emptyList()
        val json = getJson(
            "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?format=json&cid=205360838&userid=${enc(uin)}&reqfrom=1",
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to UA, "Cookie" to cookie),
        ) ?: return emptyList()
        val d = json.optJSONObject("data") ?: return emptyList()
        val out = ArrayList<MusicPlaylist>()
        d.optJSONArray("mymusic")?.let { arr ->
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val rid = it.optString("id").trim().takeIf { s -> s.isNotEmpty() && s != "0" } ?: continue
                val name = it.optString("title").trim().ifBlank { "QQ 我喜欢" }
                val cnt = it.optInt("num0", 0)
                val pic = secureHttps(it.optString("laypic").ifBlank { it.optString("picurl") })
                out += MusicPlaylist("cloud:qq:$rid", name, "QQ 音乐 · 我喜欢 · $cnt 首", pic, emptyList(), sourceId = MusicSourceKind.QQ.id, remoteId = rid, trackCountHint = cnt, playCountText = "红心")
            }
        }
        d.optJSONObject("mydiss")?.optJSONArray("list")?.let { arr ->
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val rid = it.optString("dissid").trim().takeIf { s -> s.isNotEmpty() } ?: continue
                val name = MusicLyricsParser.decodeHtmlEntities(it.optString("title").trim()).takeIf { s -> s.isNotEmpty() } ?: continue
                val pic = secureHttps(it.optString("picurl"))
                val sub = it.optString("subtitle").trim()
                out += MusicPlaylist("cloud:qq:$rid", name, if (sub.isNotBlank()) "QQ 音乐 · $sub" else "QQ 音乐 · 自建歌单", pic, emptyList(), sourceId = MusicSourceKind.QQ.id, remoteId = rid)
            }
        }
        return out
    }

    private fun fetchKugouUserPlaylists(cookie: String): List<MusicPlaylist> {
        val (token, userid, _, dfid) = parseKugouCookie(cookie)
        if (token.isBlank()) return emptyList()
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val bodyStr = """{"userid":${userid.toLongOrNull() ?: 0L},"token":"$token","total_ver":979,"type":2,"page":1,"pagesize":50}"""
        val qp = linkedMapOf("dfid" to dfid, "mid" to stableDeviceId, "uuid" to "-", "appid" to "1005", "clientver" to "11430", "clienttime" to ts, "token" to token, "userid" to userid)
        qp["signature"] = kugouAndroidSign(qp, bodyStr)
        val json = postJson(
            "https://gateway.kugou.com/v7/get_all_list?${kugouQuery(qp)}",
            bodyStr,
            mapOf("User-Agent" to "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi", "x-router" to "cloudlist.service.kugou.com", "dfid" to dfid, "mid" to stableDeviceId, "clienttime" to ts),
        ) ?: return emptyList()
        val arr = json.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val rid = it.optString("global_collection_id").trim().ifBlank { it.optString("listid").trim() }.takeIf { s -> s.isNotEmpty() } ?: continue
                val name = it.optString("name").trim().takeIf { s -> s.isNotEmpty() } ?: continue
                val cnt = it.optInt("count", 0)
                val pic = secureHttps(it.optString("pic").replace("{size}", "400"))
                add(MusicPlaylist("cloud:kugou:$rid", name, "酷狗 · 云端歌单 · $cnt 首", pic, emptyList(), sourceId = MusicSourceKind.KUGOU.id, remoteId = rid, trackCountHint = cnt, isSubscribed = it.optInt("type", 0) == 1))
            }
        }
    }

    private fun fetchQishuiUserPlaylists(cookie: String): List<MusicPlaylist> {
        val json = postJson(
            "https://beta-luna.douyin.com/luna/me/playlists",
            """{"count":50}""",
            mapOf("User-Agent" to "Luna/19.1.0 Android", "Cookie" to cookie),
        ) ?: return emptyList()
        val arr = json.optJSONArray("playlists") ?: json.optJSONArray("user_playlists") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val rid = it.optString("id").trim().takeIf { s -> s.isNotEmpty() } ?: continue
                val name = it.optString("title").ifBlank { it.optString("name") }.trim().takeIf { s -> s.isNotEmpty() } ?: continue
                val pic = qishuiCoverUrl(it.optJSONObject("url_cover"))
                val cnt = it.optInt("count_tracks", it.optInt("track_count", 0))
                add(MusicPlaylist("cloud:qishui:$rid", name, "汽水音乐 · 云端歌单 · $cnt 首", pic, emptyList(), sourceId = MusicSourceKind.QISHUI.id, remoteId = rid, trackCountHint = cnt))
            }
        }
    }

    suspend fun fetchRemotePlaylistTracks(
        source: MusicSourceKind,
        remoteId: String,
        cookie: String?,
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        val rid = remoteId.trim()
        if (rid.isEmpty()) return@withContext emptyList()
        when (source) {
            MusicSourceKind.NETEASE -> fetchNetEasePlaylistTracks(rid, cookie)
            MusicSourceKind.QQ -> fetchQQPlaylistTracks(rid, cookie)
            MusicSourceKind.KUGOU -> fetchKugouPlaylistTracks(rid, cookie)
            MusicSourceKind.QISHUI -> fetchQishuiPlaylistTracks(rid, cookie)
            else -> emptyList()
        }
    }

    private fun fetchQishuiPlaylistTracks(pid: String, cookie: String?): List<MusicTrack> {
        val hdrs = buildMap {
            put("User-Agent", "Luna/19.1.0 Android")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val detail = postJson(
            "https://beta-luna.douyin.com/luna/playlist/detail",
            JSONObject().apply { put("playlist_id", pid); put("count", 60) }.toString(),
            hdrs,
        ) ?: return emptyList()
        val mres = detail.optJSONArray("media_resources") ?: return emptyList()
        return buildList {
            for (k in 0 until mres.length()) {
                val item = mres.optJSONObject(k) ?: continue
                val ent = item.optJSONObject("entity")
                val tr = ent?.optJSONObject("track_wrapper")?.optJSONObject("track")
                    ?: ent?.optJSONObject("track")
                    ?: item.optJSONObject("track")
                tr?.let { parseQishuiTrack(it) }?.let { add(it) }
            }
        }
    }

    private fun fetchNetEasePlaylistTracks(id: String, cookie: String?): List<MusicTrack> {
        val loggedIn = !cookie.isNullOrBlank()
        val hdrs = buildMap {
            put("Referer", "https://music.163.com/")
            put("User-Agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val json = getJson("https://music.163.com/api/v6/playlist/detail?id=${enc(id)}&n=100&s=0", hdrs) ?: return emptyList()
        val pl = json.optJSONObject("playlist") ?: return emptyList()
        val direct = ArrayList<MusicTrack>()
        pl.optJSONArray("tracks")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { parseNetEaseSongObj(it, loggedIn) }?.let { direct += it }
        }
        val allIds = ArrayList<String>()
        pl.optJSONArray("trackIds")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.optLong("id", 0L)?.takeIf { it > 0L }?.let { allIds += it.toString() }
        }
        if (allIds.size <= direct.size || allIds.isEmpty()) return direct
        val targetIds = allIds.take(150)
        val byId = HashMap<String, MusicTrack>()
        direct.forEach { byId[it.sourceTrackId] = it }
        val missing = targetIds.filter { it !in byId }
        missing.chunked(50).forEach { chunk ->
            val cPayload = "[" + chunk.joinToString(",") { """{"id":$it}""" } + "]"
            val dJson = getJson("https://music.163.com/api/v3/song/detail?c=${enc(cPayload)}", hdrs) ?: return@forEach
            val songs = dJson.optJSONArray("songs") ?: return@forEach
            for (i in 0 until songs.length()) {
                songs.optJSONObject(i)?.let { parseNetEaseSongObj(it, loggedIn) }?.let { byId[it.sourceTrackId] = it }
            }
        }
        return targetIds.mapNotNull { byId[it] }.ifEmpty { direct }
    }

    private fun fetchQQPlaylistTracks(dissid: String, cookie: String?): List<MusicTrack> {
        val didNum = dissid.toLongOrNull() ?: return emptyList()
        val body = JSONObject().apply {
            put("comm", JSONObject().put("ct", 24).put("cv", 0).put("format", "json"))
            put("req", JSONObject().apply {
                put("module", "music.srfDissInfo.aiDissInfo")
                put("method", "uniform_get_Dissinfo")
                put("param", JSONObject().put("disstid", didNum).put("song_begin", 0).put("song_num", 100).put("onlysonglist", 0))
            })
        }.toString()
        val hdrs = buildMap {
            put("Referer", "https://y.qq.com/")
            put("User-Agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val json = postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body, hdrs) ?: return emptyList()
        val arr = json.optJSONObject("req")?.optJSONObject("data")?.optJSONArray("songlist") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val mid = s.optString("mid").trim().takeIf { it.isNotEmpty() } ?: continue
                val name = MusicLyricsParser.decodeHtmlEntities(s.optString("name").ifBlank { s.optString("title") }.trim()).takeIf { it.isNotEmpty() } ?: continue
                val singers = s.optJSONArray("singer")
                val artistStr = parseJoinedNames(singers, "name").ifBlank { "未知歌手" }
                val singerMid = singers?.optJSONObject(0)?.optString("mid")?.takeIf { it.isNotBlank() }
                val al = s.optJSONObject("album")
                val alName = MusicLyricsParser.decodeHtmlEntities(al?.optString("name").orEmpty())
                val alMid = al?.optString("mid").orEmpty()
                val cover = if (alMid.isNotBlank()) "https://y.gtimg.cn/music/photo_new/T002R300x300M000$alMid.jpg" else ""
                val dur = s.optDouble("interval", 210.0).coerceAtLeast(30.0)
                add(MusicTrack("qq:$mid", name, artistStr, alName, cover, dur, MusicSourceKind.QQ.id, mid, qualityLabel = "SQ / 换源", entitlementName = MusicEntitlement.FULL.name, artistId = singerMid, albumId = alMid.takeIf { it.isNotBlank() }))
            }
        }
    }

    private fun fetchKugouPlaylistTracks(globalCollectionId: String, cookie: String?): List<MusicTrack> {
        val (token, userid, _, dfid) = parseKugouCookie(cookie.orEmpty())
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val bodyStr = """{"global_collection_id":"$globalCollectionId","page":1,"pagesize":100,"type":1}"""
        val qp = linkedMapOf("dfid" to dfid, "mid" to stableDeviceId, "uuid" to "-", "appid" to "1005", "clientver" to "11430", "clienttime" to ts)
        if (token.isNotBlank()) { qp["token"] = token; qp["userid"] = userid }
        qp["signature"] = kugouAndroidSign(qp, bodyStr)
        val json = postJson(
            "https://gateway.kugou.com/v4/get_list_info?${kugouQuery(qp)}",
            bodyStr,
            mapOf("User-Agent" to "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi", "x-router" to "cloudlist.service.kugou.com", "dfid" to dfid, "mid" to stableDeviceId, "clienttime" to ts),
        ) ?: return emptyList()
        val songs = json.optJSONObject("data")?.optJSONArray("songs") ?: return emptyList()
        return buildList {
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                val hash = s.optString("hash").trim().takeIf { it.isNotEmpty() } ?: continue
                val raw = s.optString("name").trim().takeIf { it.isNotEmpty() } ?: continue
                val parts = raw.split(" - ")
                val artist = if (parts.size >= 2) parts[0].trim() else parseJoinedNames(s.optJSONArray("singerinfo"), "name").ifBlank { "未知歌手" }
                val title = if (parts.size >= 2) parts.drop(1).joinToString(" - ").trim() else raw
                val cover = secureHttps(s.optString("cover").replace("{size}", "400"))
                val dur = (s.optDouble("timelen", 210_000.0) / 1000.0).coerceAtLeast(30.0)
                add(MusicTrack("kugou:$hash", title, artist, s.optJSONObject("albuminfo")?.optString("name").orEmpty(), cover, dur, MusicSourceKind.KUGOU.id, hash, qualityLabel = "HQ 320k", entitlementName = MusicEntitlement.FULL.name))
            }
        }
    }

    // ── Charts ──────────────────────────────────────────────────────────────────────────────────────

    suspend fun fetchCharts(
        enabledSources: List<MusicSourceKind>,
        cookies: Map<MusicSourceKind, String> = emptyMap(),
        force: Boolean = false,
    ): List<MusicChart> = withContext(Dispatchers.IO) {
        val sources = enabledSources.filter { it in setOf(MusicSourceKind.NETEASE, MusicSourceKind.QQ, MusicSourceKind.KUGOU, MusicSourceKind.QISHUI) }
        if (sources.isEmpty()) return@withContext emptyList()
        coroutineScope {
            sources.map { src ->
                async {
                    val now = System.currentTimeMillis()
                    if (!force) {
                        synchronized(chartCache) {
                            chartCache[src]?.takeIf { now - it.fetchedAtMs < 15 * 60_000L }?.let { return@async it.charts }
                        }
                    }
                    val fetched = runCatching {
                        when (src) {
                            MusicSourceKind.NETEASE -> fetchNetEaseCharts(cookies[MusicSourceKind.NETEASE])
                            MusicSourceKind.QQ -> fetchQQCharts()
                            MusicSourceKind.KUGOU -> fetchKugouCharts()
                            MusicSourceKind.QISHUI -> fetchQishuiCharts()
                            else -> emptyList()
                        }
                    }.getOrDefault(emptyList())
                    if (fetched.isNotEmpty()) {
                        synchronized(chartCache) { chartCache[src] = ChartCacheEntry(now, fetched) }
                        synchronized(chartsById) { fetched.forEach { chartsById[it.id] = it } }
                    }
                    fetched
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun fetchNetEaseCharts(cookie: String?): List<MusicChart> = coroutineScope {
        val specs = listOf(
            Triple("3778678", "热歌榜", "每日更新 · 网易云最热单曲"),
            Triple("19723756", "飙升榜", "实时热度飙升 TOP30"),
            Triple("3779629", "新歌榜", "最新发行华语 / 欧美单曲"),
            Triple("2884035", "原创榜", "独立音乐人精选原创"),
        )
        specs.map { (id, name, sub) ->
            async {
                val url = "https://music.163.com/api/v6/playlist/detail?id=$id&n=30&s=0"
                val hdrs = buildMap {
                    put("Referer", "https://music.163.com/")
                    put("User-Agent", UA)
                    if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                }
                val json = getJson(url, hdrs) ?: return@async null
                val tracksArr = json.optJSONObject("playlist")?.optJSONArray("tracks") ?: return@async null
                val tracks = ArrayList<MusicTrack>()
                for (i in 0 until minOf(tracksArr.length(), 30)) {
                    val t = tracksArr.optJSONObject(i) ?: continue
                    val tid = t.optLong("id", 0L).takeIf { it > 0 }?.toString() ?: continue
                    val title = t.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val arArr = t.optJSONArray("ar")
                    val artists = parseJoinedNames(arArr, "name")
                    val arId = arArr?.optJSONObject(0)?.optLong("id", 0L)?.takeIf { it > 0 }?.toString()
                    val al = t.optJSONObject("al")
                    val album = al?.optString("name").orEmpty()
                    val alId = al?.optLong("id", 0L)?.takeIf { it > 0 }?.toString()
                    val cover = secureHttps(al?.optString("picUrl").orEmpty())
                    val dur = t.optDouble("dt", 0.0) / 1000.0
                    val fee = t.optInt("fee", 0)
                    val ent = if (fee == 0 || fee == 8) MusicEntitlement.FULL else MusicEntitlement.PREVIEW
                    val q = if (t.optJSONObject("sq") != null) "FLAC" else if (t.optJSONObject("h") != null) "HQ 320k" else "128k"
                    tracks += MusicTrack(
                        id = "netease:$tid",
                        title = title,
                        artist = artists.ifEmpty { "未知艺人" },
                        album = album,
                        coverURL = cover,
                        duration = dur,
                        sourceId = MusicSourceKind.NETEASE.id,
                        sourceTrackId = tid,
                        qualityLabel = q,
                        entitlementName = ent.name,
                        artistId = arId,
                        albumId = alId,
                    )
                }
                if (tracks.isEmpty()) return@async null
                MusicChart(
                    id = "netease-chart-$id",
                    name = name,
                    subtitle = sub,
                    sourceId = MusicSourceKind.NETEASE.id,
                    updatedLabel = "网易云 · 今日更新",
                    coverURLs = tracks.mapNotNull { it.coverURL.takeIf { u -> u.isNotBlank() } }.take(4),
                    tracks = tracks,
                )
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchQQCharts(): List<MusicChart> = coroutineScope {
        val specs = listOf(
            Triple(26, "QQ 热歌榜", "全站热门单曲风向标"),
            Triple(62, "QQ 飙升榜", "24 小时热度增速最快"),
            Triple(4, "QQ 流行指数榜", "全网社交流行热曲"),
            Triple(27, "QQ 新歌榜", "华语与国际新歌速递"),
        )
        specs.map { (topId, name, sub) ->
            async {
                val payload = JSONObject().apply {
                    put("detail", JSONObject().apply {
                        put("module", "musicToplist.ToplistInfoServer")
                        put("method", "GetDetail")
                        put("param", JSONObject().apply {
                            put("topId", topId)
                            put("offset", 0)
                            put("num", 30)
                        })
                    })
                }
                val json = postJson(
                    "https://u.y.qq.com/cgi-bin/musicu.fcg",
                    payload.toString(),
                    mapOf("Referer" to "https://y.qq.com/", "Origin" to "https://y.qq.com", "User-Agent" to UA),
                ) ?: return@async null
                val data = json.optJSONObject("detail")?.optJSONObject("data") ?: return@async null
                val arr = data.optJSONArray("songInfoList") ?: return@async null
                val tracks = ArrayList<MusicTrack>()
                for (i in 0 until minOf(arr.length(), 30)) {
                    val s = arr.optJSONObject(i) ?: continue
                    val mid = s.optString("mid").takeIf { it.isNotBlank() } ?: continue
                    val title = s.optString("title").ifBlank { s.optString("name") }.takeIf { it.isNotBlank() } ?: continue
                    val singerArr = s.optJSONArray("singer")
                    val artist = parseJoinedNames(singerArr, "name")
                    val singerMid = singerArr?.optJSONObject(0)?.optString("mid")?.takeIf { it.isNotBlank() }
                    val alb = s.optJSONObject("album")
                    val album = alb?.optString("name").orEmpty()
                    val albumMid = alb?.optString("mid").orEmpty()
                    val cover = if (albumMid.isNotBlank()) "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg" else ""
                    val dur = s.optDouble("interval", 0.0)
                    val fileObj = s.optJSONObject("file")
                    val hasFlac = (fileObj?.optLong("size_flac", 0L) ?: 0L) > 0L
                    tracks += MusicTrack(
                        id = "qq:$mid",
                        title = title,
                        artist = artist.ifEmpty { "未知艺人" },
                        album = album,
                        coverURL = cover,
                        duration = dur,
                        sourceId = MusicSourceKind.QQ.id,
                        sourceTrackId = mid,
                        qualityLabel = if (hasFlac) "FLAC" else "HQ",
                        entitlementName = MusicEntitlement.UNKNOWN.name,
                        artistId = singerMid,
                        albumId = albumMid.takeIf { it.isNotBlank() },
                    )
                }
                if (tracks.isEmpty()) return@async null
                MusicChart(
                    id = "qq-chart-$topId",
                    name = name,
                    subtitle = sub,
                    sourceId = MusicSourceKind.QQ.id,
                    updatedLabel = "QQ音乐 · 每日更新",
                    coverURLs = tracks.mapNotNull { it.coverURL.takeIf { u -> u.isNotBlank() } }.take(4),
                    tracks = tracks,
                )
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fetchKugouCharts(): List<MusicChart> = coroutineScope {
        // NOTE: mobilecdnbj.kugou.com has an SSL cert hostname mismatch on https://, so HTTP is required.
        val specs = listOf(
            Triple(8888, "酷狗 TOP500", "国民传唱金曲总榜"),
            Triple(6666, "酷狗飙升榜", "每日飙升黑马单曲"),
            Triple(31308, "内地榜", "华语内地热度排行"),
        )
        specs.map { (rankId, name, sub) ->
            async {
                val url = "http://mobilecdnbj.kugou.com/api/v3/rank/song?rankid=$rankId&page=1&pagesize=30&plat=0&version=9108"
                val json = getJson(url, mapOf("User-Agent" to UA)) ?: return@async null
                val list = json.optJSONObject("data")?.optJSONArray("info") ?: return@async null
                val tracks = ArrayList<MusicTrack>()
                for (i in 0 until minOf(list.length(), 30)) {
                    val item = list.optJSONObject(i) ?: continue
                    val hash = item.optString("hash").takeIf { it.isNotBlank() } ?: continue
                    val rawName = item.optString("filename").takeIf { it.isNotBlank() } ?: continue
                    val parts = rawName.split(" - ", limit = 2)
                    val artist = if (parts.size == 2) parts[0].trim() else "未知艺人"
                    val title = if (parts.size == 2) parts[1].trim() else rawName.trim()
                    val coverRaw = item.optString("trans_param").let {
                        item.optJSONObject("trans_param")?.optString("union_cover").orEmpty()
                    }
                    val cover = secureHttps(coverRaw.replace("{size}", "400"))
                    val dur = item.optDouble("duration", 0.0)
                    val q = if (item.optString("sqhash").isNotBlank()) "SQ" else "HQ"
                    tracks += MusicTrack(
                        id = "kugou:$hash",
                        title = title,
                        artist = artist,
                        album = item.optString("remark"),
                        coverURL = cover,
                        duration = dur,
                        sourceId = MusicSourceKind.KUGOU.id,
                        sourceTrackId = hash,
                        qualityLabel = q,
                        entitlementName = MusicEntitlement.UNKNOWN.name,
                    )
                }
                if (tracks.isEmpty()) return@async null
                MusicChart(
                    id = "kugou-chart-$rankId",
                    name = name,
                    subtitle = sub,
                    sourceId = MusicSourceKind.KUGOU.id,
                    updatedLabel = "酷狗 · 实时更新",
                    coverURLs = tracks.mapNotNull { it.coverURL.takeIf { u -> u.isNotBlank() } }.take(4),
                    tracks = tracks,
                )
            }
        }.awaitAll().filterNotNull()
    }

    // ── Multi-Source Search ─────────────────────────────────────────────────────────────────────────

    suspend fun searchAll(
        query: String,
        enabledSources: List<MusicSourceKind>,
        nasTracks: List<MusicTrack>,
        cookies: Map<MusicSourceKind, String> = emptyMap(),
    ): List<MusicTrack> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isEmpty()) return@withContext emptyList()
        val qLower = clean.lowercase(Locale.ROOT)
        val localNas = nasTracks.filter {
            it.title.lowercase(Locale.ROOT).contains(qLower) ||
                it.artist.lowercase(Locale.ROOT).contains(qLower) ||
                it.album.lowercase(Locale.ROOT).contains(qLower)
        }
        val online = coroutineScope {
            enabledSources.map { src ->
                async {
                    runCatching {
                        when (src) {
                            MusicSourceKind.NETEASE -> searchNetEase(clean, cookies[MusicSourceKind.NETEASE])
                            MusicSourceKind.QQ -> searchQQ(clean)
                            MusicSourceKind.KUGOU -> searchKugou(clean)
                            MusicSourceKind.QISHUI -> searchQishui(clean, cookies[MusicSourceKind.QISHUI])
                            MusicSourceKind.ITUNES -> searchITunes(clean)
                            else -> emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        (localNas + online).distinctBy { it.id }
    }

    private fun searchNetEase(query: String, cookie: String?): List<MusicTrack> {
        val url = "https://music.163.com/api/search/get/web?s=${enc(query)}&type=1&limit=15&offset=0"
        val hdrs = buildMap {
            put("Referer", "https://music.163.com/")
            put("User-Agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val json = getJson(url, hdrs) ?: return emptyList()
        val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()
        return buildList {
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                val id = s.optLong("id", 0L).takeIf { it > 0 }?.toString() ?: continue
                val name = s.optString("name").takeIf { it.isNotBlank() } ?: continue
                val arArr = s.optJSONArray("artists")
                val artist = parseJoinedNames(arArr, "name").ifEmpty { "未知艺人" }
                val arId = arArr?.optJSONObject(0)?.optLong("id", 0L)?.takeIf { it > 0 }?.toString()
                val alb = s.optJSONObject("album")
                val alId = alb?.optLong("id", 0L)?.takeIf { it > 0 }?.toString()
                val cover = secureHttps(alb?.optString("picUrl").orEmpty())
                val dur = s.optDouble("duration", 0.0) / 1000.0
                val fee = s.optInt("fee", 0)
                val ent = if (fee == 0 || fee == 8) MusicEntitlement.FULL else MusicEntitlement.PREVIEW
                add(
                    MusicTrack(
                        id = "netease:$id",
                        title = name,
                        artist = artist,
                        album = alb?.optString("name").orEmpty(),
                        coverURL = cover,
                        duration = dur,
                        sourceId = MusicSourceKind.NETEASE.id,
                        sourceTrackId = id,
                        qualityLabel = "HQ",
                        entitlementName = ent.name,
                        artistId = arId,
                        albumId = alId,
                    ),
                )
            }
        }
    }

    private fun searchQQ(query: String): List<MusicTrack> {
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=${enc(query)}&format=json"
        val json = getJson(url, mapOf("Referer" to "https://y.qq.com/", "User-Agent" to UA)) ?: return emptyList()
        val list = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("itemlist") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val mid = s.optString("mid").ifBlank { s.optString("id") }.takeIf { it.isNotBlank() } ?: continue
                val name = s.optString("name").takeIf { it.isNotBlank() } ?: continue
                val singer = s.optString("singer").ifBlank { "未知艺人" }
                add(
                    MusicTrack(
                        id = "qq:$mid",
                        title = name,
                        artist = singer,
                        album = "",
                        coverURL = "",
                        duration = 0.0,
                        sourceId = MusicSourceKind.QQ.id,
                        sourceTrackId = mid,
                        qualityLabel = "HQ",
                        entitlementName = MusicEntitlement.UNKNOWN.name,
                    ),
                )
            }
        }
    }

    private fun searchKugou(query: String): List<MusicTrack> {
        val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=${enc(query)}&page=1&pagesize=15&showtype=1"
        val json = getJson(url, mapOf("User-Agent" to UA)) ?: return emptyList()
        val list = json.optJSONObject("data")?.optJSONArray("info") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val hash = s.optString("hash").takeIf { it.isNotBlank() } ?: continue
                val title = s.optString("songname").takeIf { it.isNotBlank() } ?: continue
                val artist = s.optString("singername").ifBlank { "未知艺人" }
                add(
                    MusicTrack(
                        id = "kugou:$hash",
                        title = title,
                        artist = artist,
                        album = s.optString("album_name"),
                        coverURL = "",
                        duration = s.optDouble("duration", 0.0),
                        sourceId = MusicSourceKind.KUGOU.id,
                        sourceTrackId = hash,
                        qualityLabel = "HQ",
                        entitlementName = MusicEntitlement.UNKNOWN.name,
                    ),
                )
            }
        }
    }

    private fun searchITunes(query: String): List<MusicTrack> {
        val url = "https://itunes.apple.com/search?term=${enc(query)}&country=TW&entity=song&limit=10"
        val json = getJson(url, mapOf("User-Agent" to UA)) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val tid = r.optLong("trackId", 0L).takeIf { it > 0 }?.toString() ?: continue
                val title = r.optString("trackName").takeIf { it.isNotBlank() } ?: continue
                val artist = r.optString("artistName").ifBlank { "未知艺人" }
                val preview = r.optString("previewUrl").takeIf { it.isNotBlank() }
                val cover = r.optString("artworkUrl100").replace("100x100", "500x500")
                add(
                    MusicTrack(
                        id = "itunes:$tid",
                        title = title,
                        artist = artist,
                        album = r.optString("collectionName"),
                        coverURL = cover,
                        duration = r.optDouble("trackTimeMillis", 0.0) / 1000.0,
                        sourceId = MusicSourceKind.ITUNES.id,
                        sourceTrackId = tid,
                        streamURL = preview,
                        qualityLabel = "AAC 试听",
                        entitlementName = MusicEntitlement.PREVIEW.name,
                    ),
                )
            }
        }
    }

    // ── Stream Resolution & Auto-Fallback ───────────────────────────────────────────────────────────

    suspend fun resolveStream(
        track: MusicTrack,
        cachedFilePath: String?,
        enabledSources: List<MusicSourceKind>,
        nasTracks: List<MusicTrack>,
        cookies: Map<MusicSourceKind, String>,
        autoFallback: Boolean,
        preferredQuality: MusicAudioQuality = MusicAudioQuality.HIRES,
    ): ResolvedAudio? = withContext(Dispatchers.IO) {
        if (!cachedFilePath.isNullOrBlank()) {
            val cachedFile = java.io.File(cachedFilePath)
            val ext = cachedFile.extension.lowercase(Locale.ROOT)
            val isCachedLossless = ext == "flac" || ext == "wav"
            val wantsLossless = preferredQuality == MusicAudioQuality.HIRES || preferredQuality == MusicAudioQuality.LOSSLESS
            val isLoggedInOnline = (track.source in setOf(MusicSourceKind.NETEASE, MusicSourceKind.QQ, MusicSourceKind.KUGOU)) &&
                !cookies[track.source].isNullOrBlank()
            val shouldUpgradeLossyCache = isLoggedInOnline && wantsLossless && !isCachedLossless
            if (!shouldUpgradeLossyCache) {
                val fileBytes = runCatching { cachedFile.length() }.getOrDefault(0L)
                val estKbps = if (track.duration > 10.0 && fileBytes > 0L) ((fileBytes * 8.0) / (track.duration * 1000.0)).toInt() else 0
                val qLabel = when {
                    isCachedLossless && estKbps >= 1200 -> "Hi-Res 母带 · 离线缓存"
                    isCachedLossless -> "FLAC 无损 · 离线缓存"
                    ext == "mp3" && estKbps >= 260 -> "HQ 320k · 离线缓存"
                    ext == "mp3" -> "MP3 · 离线缓存"
                    ext == "m4a" || ext == "aac" -> "AAC · 离线缓存"
                    else -> "${track.qualityLabel} · 离线缓存"
                }
                return@withContext ResolvedAudio(
                    url = "file://$cachedFilePath",
                    headers = emptyMap(),
                    actualSource = track.source,
                    qualityLabel = qLabel,
                    entitlement = if (track.source == MusicSourceKind.ITUNES) MusicEntitlement.PREVIEW else MusicEntitlement.FULL,
                    isCached = true,
                )
            }
        }

        // Direct NAS / pre-populated stream
        if (!track.streamURL.isNullOrBlank()) {
            val hdrs = track.extraMeta?.filterKeys { it.startsWith("header:") }
                ?.mapKeys { it.key.removePrefix("header:") }.orEmpty()
            return@withContext ResolvedAudio(
                url = track.streamURL,
                headers = hdrs,
                actualSource = track.source,
                qualityLabel = track.qualityLabel,
                entitlement = if (track.source.isNAS) MusicEntitlement.FULL else track.entitlement,
                isCached = false,
            )
        }

        // Direct native resolve for NetEase, logged-in QQ/Kugou, and Qishui
        if (track.source == MusicSourceKind.NETEASE) {
            resolveNetEaseStream(track.sourceTrackId, cookies[MusicSourceKind.NETEASE], preferredQuality)?.let { return@withContext it }
        }
        if (track.source == MusicSourceKind.QQ) {
            cookies[MusicSourceKind.QQ]?.takeIf { it.isNotBlank() }?.let { ck ->
                resolveQQStream(track.sourceTrackId, ck, preferredQuality = preferredQuality)?.let { return@withContext it }
            }
        }
        if (track.source == MusicSourceKind.KUGOU) {
            cookies[MusicSourceKind.KUGOU]?.takeIf { it.isNotBlank() }?.let { ck ->
                resolveKugouStream(track.sourceTrackId, ck, preferredQuality = preferredQuality)?.let { return@withContext it }
            }
        }
        if (track.source == MusicSourceKind.QISHUI) {
            resolveQishuiStream(track.sourceTrackId, cookies[MusicSourceKind.QISHUI])?.let { return@withContext it }
        }

        if (!autoFallback) return@withContext null

        // 1. Personal NAS match (always full lossless if user owns it)
        bestMatch(track, nasTracks)?.let { match ->
            if (!match.streamURL.isNullOrBlank()) {
                return@withContext ResolvedAudio(
                    url = match.streamURL,
                    headers = emptyMap(),
                    actualSource = match.source,
                    qualityLabel = "NAS 原声 · ${match.qualityLabel}",
                    entitlement = MusicEntitlement.FULL,
                    isCached = false,
                )
            }
        }

        // 2. Cross-source NetEase full track
        if (track.source != MusicSourceKind.NETEASE && MusicSourceKind.NETEASE in enabledSources) {
            val hits = runCatching { searchNetEase("${track.title} ${track.artist}", cookies[MusicSourceKind.NETEASE]) }.getOrDefault(emptyList())
            val match = bestMatch(track, hits)
            if (match != null) {
                resolveNetEaseStream(match.sourceTrackId, cookies[MusicSourceKind.NETEASE], preferredQuality)?.let { return@withContext it }
            }
        }

        // 3. Reliable iTunes AAC preview fallback so user always gets audible playback
        val itHits = runCatching { searchITunes("${track.title} ${track.artist}") }.getOrDefault(emptyList())
        val itMatch = bestMatch(track, itHits) ?: itHits.firstOrNull()
        if (itMatch != null && !itMatch.streamURL.isNullOrBlank()) {
            return@withContext ResolvedAudio(
                url = itMatch.streamURL,
                headers = emptyMap(),
                actualSource = MusicSourceKind.ITUNES,
                qualityLabel = "AAC 256k · 试听",
                entitlement = MusicEntitlement.PREVIEW,
                isCached = false,
            )
        }
        null
    }

    suspend fun resolveFallbackStream(
        track: MusicTrack,
        excludingUrl: String,
        cookies: Map<MusicSourceKind, String>,
    ): ResolvedAudio? = withContext(Dispatchers.IO) {
        if (track.source == MusicSourceKind.NETEASE) {
            resolveNetEaseOuter(track.sourceTrackId)?.takeIf { it.url != excludingUrl }?.let { return@withContext it }
        }
        val itHits = runCatching { searchITunes("${track.title} ${track.primaryArtistName}") }.getOrDefault(emptyList())
        val itMatch = bestMatch(track, itHits) ?: itHits.firstOrNull()
        val u = itMatch?.streamURL
        if (!u.isNullOrBlank() && u != excludingUrl) {
            return@withContext ResolvedAudio(u, emptyMap(), MusicSourceKind.ITUNES, "AAC 256k · 试听", MusicEntitlement.PREVIEW, false)
        }
        null
    }

    @Volatile private var cachedNetEaseAnonCookie: Pair<String, Long>? = null

    private fun ensureNetEaseAnonCookie(): String? {
        val now = System.currentTimeMillis()
        cachedNetEaseAnonCookie?.takeIf { now - it.second < 12 * 3600_000L }?.let { return it.first }
        val devId = stableDeviceId
        val xorKey = "3go8&$8*3*3h0k(2)2".toByteArray(Charsets.UTF_8)
        val devBytes = devId.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(devBytes.size) { i -> (devBytes[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte() }
        val md5B64 = android.util.Base64.encodeToString(MessageDigest.getInstance("MD5").digest(xored), android.util.Base64.NO_WRAP)
        val encId = android.util.Base64.encodeToString("$devId $md5B64".toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val res = postNetEaseEAPI("/api/register/anonimous", JSONObject().apply { put("username", encId) }) ?: return null
        if (res.first.optInt("code", 0) != 200) return null
        val merged = res.second.mapNotNull { it.substringBefore(';').trim().takeIf { p -> p.contains('=') } }.joinToString("; ")
        if (merged.isBlank()) return null
        cachedNetEaseAnonCookie = merged to now
        return merged
    }

    private fun resolveNetEaseStream(
        songId: String,
        cookie: String?,
        preferredQuality: MusicAudioQuality = MusicAudioQuality.HIRES,
    ): ResolvedAudio? {
        val tiers = when (preferredQuality) {
            MusicAudioQuality.HIRES -> listOf("hires" to "flac", "lossless" to "flac", "exhigh" to "mp3", "standard" to "mp3")
            MusicAudioQuality.LOSSLESS -> listOf("lossless" to "flac", "exhigh" to "mp3", "standard" to "mp3")
            MusicAudioQuality.EXHIGH -> listOf("exhigh" to "mp3", "standard" to "mp3")
            MusicAudioQuality.STANDARD -> listOf("standard" to "mp3")
        }
        if (!cookie.isNullOrBlank()) {
            var trialCandidate: ResolvedAudio? = null
            for ((lvl, enc) in tiers) {
                val res = fetchNetEaseEnhance(songId, lvl, enc, cookie)
                if (res != null) {
                    if (res.entitlement == MusicEntitlement.FULL) return res
                    if (trialCandidate == null) trialCandidate = res
                }
            }
            if (trialCandidate != null) return trialCandidate
        }
        val anon = ensureNetEaseAnonCookie()
        val guestLevel = if (preferredQuality == MusicAudioQuality.STANDARD) "standard" else "exhigh"
        val freeEnhance = fetchNetEaseEnhance(songId, guestLevel, "mp3", anon)
        if (freeEnhance != null && freeEnhance.entitlement == MusicEntitlement.FULL) return freeEnhance
        resolveNetEaseOuter(songId)?.let { return it }
        return freeEnhance
    }

    private fun fetchNetEaseEnhance(songId: String, level: String, encodeType: String, cookie: String?): ResolvedAudio? {
        val eapiRes = postNetEaseEAPI(
            "/api/song/enhance/player/url/v1",
            JSONObject().apply {
                put("ids", "[$songId]")
                put("level", level)
                put("encodeType", encodeType)
            },
            extraCookie = cookie.orEmpty(),
        )?.first
        val firstEapi = eapiRes?.optJSONArray("data")?.optJSONObject(0)
        val eapiUrl = secureHttps(firstEapi?.optString("url").orEmpty())
        if (eapiUrl.isNotBlank() && firstEapi != null) {
            val br = firstEapi.optInt("br", 0)
            val actualLevel = firstEapi.optString("level", level).lowercase(Locale.ROOT)
            val typeStr = firstEapi.optString("type", "mp3").uppercase(Locale.ROOT)
            val isTrial = firstEapi.optJSONObject("freeTrialInfo") != null
            val q = when {
                isTrial -> "30秒试听 · ${br / 1000}k"
                actualLevel == "hires" || actualLevel == "jymaster" || br >= 1_200_000 -> "Hi-Res 母带"
                actualLevel == "lossless" || typeStr == "FLAC" || br >= 700_000 -> "FLAC 无损"
                br >= 300_000 -> "HQ 320k"
                else -> "标准 ${if (br > 0) br / 1000 else 128}k"
            }
            return ResolvedAudio(
                url = eapiUrl,
                headers = emptyMap(),
                actualSource = MusicSourceKind.NETEASE,
                qualityLabel = q,
                entitlement = if (isTrial) MusicEntitlement.PREVIEW else MusicEntitlement.FULL,
                isCached = false,
            )
        }
        val url = "https://music.163.com/api/song/enhance/player/url/v1?ids=%5B$songId%5D&level=$level&encodeType=$encodeType"
        val hdrs = buildMap {
            put("Referer", "https://music.163.com/")
            put("User-Agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val json = getJson(url, hdrs) ?: return null
        val first = json.optJSONArray("data")?.optJSONObject(0) ?: return null
        val streamUrl = secureHttps(first.optString("url").orEmpty())
        if (streamUrl.isBlank()) return null
        val br = first.optInt("br", 0)
        val isTrial = first.optJSONObject("freeTrialInfo") != null
        val q = when {
            isTrial -> "30秒试听"
            br >= 700_000 -> "FLAC 无损"
            br >= 300_000 -> "HQ 320k"
            else -> "128k"
        }
        return ResolvedAudio(
            url = streamUrl,
            headers = emptyMap(),
            actualSource = MusicSourceKind.NETEASE,
            qualityLabel = q,
            entitlement = if (isTrial) MusicEntitlement.PREVIEW else MusicEntitlement.FULL,
            isCached = false,
        )
    }

    private fun resolveNetEaseOuter(songId: String): ResolvedAudio? {
        val outer = "https://music.163.com/song/media/outer/url?id=$songId.mp3"
        val req = Request.Builder().url(outer)
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", UA)
            .build()
        return runCatching {
            noRedirectClient.newCall(req).execute().use { resp ->
                val loc = resp.header("Location").orEmpty()
                if (resp.code in 300..399 && loc.isNotBlank() && !loc.contains("/404")) {
                    ResolvedAudio(
                        url = secureHttps(loc),
                        headers = emptyMap(),
                        actualSource = MusicSourceKind.NETEASE,
                        qualityLabel = "MP3 直连",
                        entitlement = MusicEntitlement.FULL,
                        isCached = false,
                    )
                } else null
            }
        }.getOrNull()
    }

    sealed class NetEaseSMSResult {
        data object Sent : NetEaseSMSResult()
        data class Failed(val message: String) : NetEaseSMSResult()
    }

    sealed class NetEasePhoneLoginResult {
        data class Authorized(val cookie: String, val nickname: String?, val avatarURL: String?) : NetEasePhoneLoginResult()
        data class RequiresWebOrQR(val message: String) : NetEasePhoneLoginResult()
        data class Failed(val message: String) : NetEasePhoneLoginResult()
    }

    data class NetEaseQRSession(
        val key: String,
        val url: String,
        val deviceId: String,
    )

    sealed class NetEaseQRPoll {
        data object Waiting : NetEaseQRPoll()
        data class Scanned(val nickname: String?) : NetEaseQRPoll()
        data object Expired : NetEaseQRPoll()
        data class Refused(val message: String) : NetEaseQRPoll()
        data class Authorized(val cookie: String) : NetEaseQRPoll()
        data class Failed(val message: String) : NetEaseQRPoll()
    }

    data class KugouQRSession(
        val key: String,
        val imageBase64: String?,
        val qrText: String,
    )

    sealed class KugouQRPoll {
        data object Waiting : KugouQRPoll()
        data class Scanned(val nickname: String?) : KugouQRPoll()
        data object Expired : KugouQRPoll()
        data class Authorized(val cookie: String, val nickname: String?, val avatarURL: String?) : KugouQRPoll()
        data class Failed(val message: String) : KugouQRPoll()
    }

    fun normalizePhone(raw: String, countryCode: String = "86"): Pair<String, String> {
        val ct = countryCode.filter(Char::isDigit).ifEmpty { "86" }
        var digits = raw.filter(Char::isDigit)
        if (ct == "86" && digits.startsWith("86") && digits.length > 11) {
            digits = digits.drop(2)
        } else if (digits.startsWith(ct) && digits.length > ct.length + 7) {
            digits = digits.drop(ct.length)
        }
        return digits to ct
    }

    fun eapiEncrypt(apiPath: String, payload: JSONObject): String {
        val text = payload.toString()
        val digest = md5Hex("nobody${apiPath}use${text}md5forencrypt")
        val raw = "${apiPath}-36cd479b6b5-${text}-36cd479b6b5-${digest}"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
    }

    private const val NETEASE_PC_APPVER = "3.1.17.204416"
    private const val NETEASE_PC_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.17.204416 Safari/537.36"
    private val secureRandom = java.security.SecureRandom()

    private fun randomHexUpper(length: Int): String {
        val hex = "0123456789ABCDEF"
        return buildString(length) {
            repeat(length) { append(hex[secureRandom.nextInt(hex.length)]) }
        }
    }

    private fun buildNetEaseEAPIHeaderMap(
        deviceId: String,
        musicU: String? = null,
        musicA: String? = null,
        csrf: String = "",
    ): LinkedHashMap<String, String> {
        val nowMs = System.currentTimeMillis()
        val m = linkedMapOf(
            "osver" to "Microsoft-Windows-10-Professional-build-19045-64bit",
            "deviceId" to deviceId,
            "appver" to NETEASE_PC_APPVER,
            "versioncode" to "140",
            "mobilename" to "",
            "buildver" to (nowMs / 1000L).toString(),
            "resolution" to "1920x1080",
            "__csrf" to csrf,
            "os" to "pc",
            "channel" to "netease",
            "requestId" to "${nowMs}_${String.format(Locale.US, "%04d", secureRandom.nextInt(10000))}",
        )
        if (!musicU.isNullOrBlank()) m["MUSIC_U"] = musicU
        if (!musicA.isNullOrBlank()) m["MUSIC_A"] = musicA
        return m
    }

    private fun postNetEaseEAPI(
        apiPath: String,
        payload: JSONObject,
        extraCookie: String = "",
        deviceId: String? = null,
        usePCDomain: Boolean = false,
    ): Pair<JSONObject, List<String>>? {
        val suffix = if (apiPath.startsWith("/api")) apiPath.removePrefix("/api") else apiPath
        val devId = deviceId ?: stableDeviceId.uppercase(Locale.ROOT)
        var musicU: String? = null
        var musicA: String? = null
        var csrf = ""
        for (seg in extraCookie.split(';')) {
            val t = seg.trim()
            when {
                t.startsWith("MUSIC_U=") -> musicU = t.removePrefix("MUSIC_U=")
                t.startsWith("MUSIC_A=") -> musicA = t.removePrefix("MUSIC_A=")
                t.startsWith("__csrf=") -> csrf = t.removePrefix("__csrf=")
            }
        }
        val hdrMap = buildNetEaseEAPIHeaderMap(devId, musicU, musicA, csrf)
        val hdrObj = JSONObject().apply { hdrMap.forEach { (k, v) -> put(k, v) } }
        payload.put("header", hdrObj)
        payload.put("e_r", false)
        val paramsHex = eapiEncrypt(apiPath, payload)
        val baseCookie = hdrMap.entries.sortedBy { it.key }.joinToString("; ") { "${enc(it.key)}=${enc(it.value)}" }
        val host = if (usePCDomain) "https://interfacepc.music.163.com" else "https://interface.music.163.com"
        val req = Request.Builder()
            .url("$host/eapi$suffix")
            .post("params=$paramsHex".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", NETEASE_PC_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", if (extraCookie.isBlank()) baseCookie else "$baseCookie; $extraCookie")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) null else JSONObject(body) to resp.headers("Set-Cookie")
            }
        }.getOrNull()
    }

    suspend fun sendNetEaseSMSCode(phone: String, countryCode: String = "86"): NetEaseSMSResult = withContext(Dispatchers.IO) {
        val (cleanPhone, ct) = normalizePhone(phone, countryCode)
        if (cleanPhone.length < 6) return@withContext NetEaseSMSResult.Failed("请输入有效的手机号")
        val res = postNetEaseEAPI(
            "/api/sms/captcha/sent",
            JSONObject().apply {
                put("cellphone", cleanPhone)
                put("ctcode", ct)
                put("secrete", "music_middleuser_pclogin")
            },
        ) ?: return@withContext NetEaseSMSResult.Failed("无法直连网易云短信接口，请检查网络或改用扫码 / 桌面网页登录")
        val json = res.first
        val code = json.optInt("code", 0)
        if (code == 200) NetEaseSMSResult.Sent
        else NetEaseSMSResult.Failed(
            json.optString("message").ifBlank { json.optString("msg") }.ifBlank { "发送失败（状态码 $code）" },
        )
    }

    private fun parseNetEaseLoginResponse(res: Pair<JSONObject, List<String>>): NetEasePhoneLoginResult {
        val (json, setCookies) = res
        val code = json.optInt("code", 0)
        if (code == 200) {
            val map = LinkedHashMap<String, String>()
            val metaKeys = setOf("path", "domain", "expires", "max-age", "httponly", "secure", "samesite")
            for (sc in setCookies) {
                val first = sc.substringBefore(';').trim()
                val idx = first.indexOf('=')
                if (idx > 0) {
                    val k = first.substring(0, idx).trim()
                    val v = first.substring(idx + 1).trim()
                    if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
                }
            }
            val rawCookie = json.optString("cookie").orEmpty()
            if (rawCookie.isNotBlank()) {
                for (seg in rawCookie.split(';')) {
                    val t = seg.trim()
                    val idx = t.indexOf('=')
                    if (idx > 0) {
                        val k = t.substring(0, idx).trim()
                        val v = t.substring(idx + 1).trim()
                        if (k.isNotEmpty() && v.isNotEmpty() && k.lowercase(Locale.ROOT) !in metaKeys && !map.containsKey(k)) {
                            map[k] = v
                        }
                    }
                }
            }
            val merged = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (merged.isBlank()) {
                return NetEasePhoneLoginResult.RequiresWebOrQR("登录已通过但未返回 Cookie，请改用扫码或桌面网页登录")
            }
            val profile = json.optJSONObject("profile")
            val nick = profile?.optString("nickname")?.trim()?.takeIf { it.isNotEmpty() }
            val avatar = profile?.optString("avatarUrl")?.takeIf { it.isNotBlank() }?.let(::secureHttps)
            return NetEasePhoneLoginResult.Authorized(merged, nick, avatar)
        }
        val msg = json.optString("message").ifBlank { json.optString("msg") }
            .ifBlank { if (code == 502) "账号或密码错误" else if (code == 501) "账号不存在" else "登录失败（状态码 $code）" }
        if (code == -460 || code == -462 || code == 509 || code == 8821 || msg.contains("风险") || msg.contains("安全验证") || msg.contains("滑块") || msg.contains("升级")) {
            return NetEasePhoneLoginResult.RequiresWebOrQR("$msg，建议改用「扫码」或「桌面网页」完成验证")
        }
        return NetEasePhoneLoginResult.Failed(msg)
    }

    suspend fun loginNetEaseByPhone(
        phone: String,
        countryCode: String = "86",
        captcha: String? = null,
        password: String? = null,
    ): NetEasePhoneLoginResult = withContext(Dispatchers.IO) {
        val (cleanPhone, ct) = normalizePhone(phone, countryCode)
        if (cleanPhone.length < 6) return@withContext NetEasePhoneLoginResult.Failed("请输入有效的手机号")
        val payload = JSONObject().apply {
            put("type", "1")
            put("https", "true")
            put("phone", cleanPhone)
            put("countrycode", ct)
            put("remember", "true")
            put("rememberLogin", "true")
            val cap = captcha?.trim().orEmpty()
            if (cap.isNotEmpty()) {
                put("captcha", cap)
            } else if (!password.isNullOrEmpty()) {
                put("password", md5Hex(password))
            } else {
                return@withContext NetEasePhoneLoginResult.Failed("请输入验证码或密码")
            }
        }
        val res = postNetEaseEAPI("/api/w/login/cellphone", payload)
            ?: return@withContext NetEasePhoneLoginResult.Failed("无法直连网易云登录接口，请检查网络后重试")
        parseNetEaseLoginResponse(res)
    }

    suspend fun loginNetEaseByEmail(email: String, password: String): NetEasePhoneLoginResult = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim()
        if (!cleanEmail.contains("@") || password.isEmpty()) {
            return@withContext NetEasePhoneLoginResult.Failed("请输入有效的邮箱和密码")
        }
        val payload = JSONObject().apply {
            put("type", "0")
            put("https", "true")
            put("username", cleanEmail)
            put("password", md5Hex(password))
            put("rememberLogin", "true")
        }
        val res = postNetEaseEAPI("/api/w/login", payload)
            ?: return@withContext NetEasePhoneLoginResult.Failed("无法直连网易云邮箱登录接口，请检查网络后重试")
        parseNetEaseLoginResponse(res)
    }

    suspend fun createNetEaseQRSession(): NetEaseQRSession? = withContext(Dispatchers.IO) {
        val device = randomHexUpper(32)
        val anon = ensureNetEaseAnonCookie().orEmpty()
        val res = postNetEaseEAPI(
            apiPath = "/api/login/qrcode/unikey",
            payload = JSONObject().put("type", 3),
            extraCookie = anon,
            deviceId = device,
            usePCDomain = true,
        ) ?: return@withContext null
        val key = res.first.optString("unikey").takeIf { it.isNotBlank() } ?: return@withContext null
        val chainId = "v1_${device}_pc_login_${System.currentTimeMillis()}"
        val url = "https://music.163.com/login?codekey=${enc(key)}&chainId=${enc(chainId)}"
        NetEaseQRSession(key = key, url = url, deviceId = device)
    }

    suspend fun createNetEaseQRKey(): String? = createNetEaseQRSession()?.key

    suspend fun pollNetEaseQR(session: NetEaseQRSession): NetEaseQRPoll = withContext(Dispatchers.IO) {
        val anon = ensureNetEaseAnonCookie().orEmpty()
        val res = postNetEaseEAPI(
            apiPath = "/api/login/qrcode/client/login",
            payload = JSONObject().apply {
                put("key", session.key)
                put("type", 3)
            },
            extraCookie = anon,
            deviceId = session.deviceId,
            usePCDomain = true,
        ) ?: return@withContext NetEaseQRPoll.Failed("网络波动，等待重试…")
        val (json, setCookies) = res
        val code = json.optInt("code", 0)
        when (code) {
            800 -> NetEaseQRPoll.Expired
            801 -> NetEaseQRPoll.Waiting
            802 -> NetEaseQRPoll.Scanned(json.optString("nickname").takeIf { it.isNotBlank() })
            803 -> {
                val map = LinkedHashMap<String, String>()
                val metaKeys = setOf("path", "domain", "expires", "max-age", "httponly", "secure", "samesite")
                for (sc in setCookies) {
                    val p = sc.substringBefore(';').trim()
                    val idx = p.indexOf('=')
                    if (idx > 0) {
                        val k = p.substring(0, idx).trim()
                        val v = p.substring(idx + 1).trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
                    }
                }
                val rawCk = json.optString("cookie").orEmpty()
                if (rawCk.isNotBlank()) {
                    for (seg in rawCk.split(';')) {
                        val t = seg.trim()
                        val idx = t.indexOf('=')
                        if (idx > 0) {
                            val k = t.substring(0, idx).trim()
                            val v = t.substring(idx + 1).trim()
                            if (k.isNotEmpty() && v.isNotEmpty() && k.lowercase(Locale.ROOT) !in metaKeys && !map.containsKey(k)) {
                                map[k] = v
                            }
                        }
                    }
                }
                val merged = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
                if (merged.isNotBlank()) NetEaseQRPoll.Authorized(merged)
                else NetEaseQRPoll.Failed("登录成功但未返回 Cookie，请切换桌面网页登录")
            }
            else -> {
                val msg = json.optString("message").ifBlank { json.optString("msg") }
                if (code == 8821 || code == -462 || code == -460 || msg.contains("升级") || msg.contains("风险") || msg.contains("验证")) {
                    val reason = if (msg.isBlank()) "$code" else "$code：$msg"
                    NetEaseQRPoll.Refused("网易云拒绝了本次扫码（$reason），请切换「桌面网页」或「手机号」登录")
                } else {
                    NetEaseQRPoll.Failed(msg.ifBlank { "等待扫码" })
                }
            }
        }
    }

    suspend fun pollNetEaseQR(key: String): NetEaseQRPoll =
        pollNetEaseQR(NetEaseQRSession(key, "https://music.163.com/login?codekey=$key", stableDeviceId.uppercase(Locale.ROOT)))

    suspend fun fetchNetEaseAccount(cookie: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val json = getJson(
            "https://music.163.com/api/nuser/account/get",
            mapOf("Referer" to "https://music.163.com/", "Cookie" to cookie, "User-Agent" to DESKTOP_UA),
        ) ?: return@withContext null
        val profile = json.optJSONObject("profile") ?: return@withContext null
        val nick = profile.optString("nickname").takeIf { it.isNotBlank() } ?: return@withContext null
        val avatar = secureHttps(profile.optString("avatarUrl").orEmpty())
        nick to avatar
    }

    private fun kugouSign(params: Map<String, String>, body: String = ""): String {
        val salt = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
        val joined = params.keys.sorted().joinToString("") { "$it=${params[it].orEmpty()}" }
        return md5Hex(salt + joined + body + salt).uppercase(Locale.ROOT)
    }

    private fun kugouQuery(params: Map<String, String>): String =
        params.keys.sorted().joinToString("&") { "$it=${enc(params[it].orEmpty())}" }

    suspend fun createKugouQR(): KugouQRSession? = withContext(Dispatchers.IO) {
        val mid = stableDeviceId
        val ts = System.currentTimeMillis().toString()
        val baseQR = "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=1001&"
        val params = linkedMapOf(
            "appid" to "1001",
            "clienttime" to ts,
            "clientver" to "1000",
            "dfid" to "-",
            "mid" to mid,
            "plat" to "4",
            "qrcode_txt" to baseQR,
            "srcappid" to "2919",
            "type" to "1",
            "uuid" to mid,
        )
        params["signature"] = kugouSign(params)
        val json = getJson(
            "https://login-user.kugou.com/v2/qrcode?${kugouQuery(params)}",
            mapOf("Referer" to "https://www.kugou.com/", "User-Agent" to DESKTOP_UA),
        ) ?: return@withContext null
        val data = json.optJSONObject("data") ?: return@withContext null
        val key = data.optString("qrcode").takeIf { it.isNotBlank() } ?: return@withContext null
        val rawImg = data.optString("qrcode_img").takeIf { it.isNotBlank() }
        val b64 = rawImg?.substringAfter(',', rawImg)
        KugouQRSession(key = key, imageBase64 = b64, qrText = "${baseQR}qrcode=$key")
    }

    suspend fun pollKugouQR(key: String): KugouQRPoll = withContext(Dispatchers.IO) {
        val mid = stableDeviceId
        val ts = System.currentTimeMillis().toString()
        val params = linkedMapOf(
            "appid" to "1001",
            "clienttime" to ts,
            "clientver" to "1000",
            "dfid" to "-",
            "mid" to mid,
            "plat" to "4",
            "qrcode" to key,
            "srcappid" to "2919",
            "uuid" to mid,
        )
        params["signature"] = kugouSign(params)
        val req = Request.Builder()
            .url("https://login-user.kugou.com/v2/get_userinfo_qrcode?${kugouQuery(params)}")
            .header("Referer", "https://www.kugou.com/")
            .header("User-Agent", DESKTOP_UA)
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val data = JSONObject(body).optJSONObject("data") ?: return@use KugouQRPoll.Failed("网络波动，等待重试…")
                when (data.optInt("status", -1)) {
                    0 -> KugouQRPoll.Expired
                    1 -> KugouQRPoll.Waiting
                    2 -> KugouQRPoll.Scanned(data.optString("nickname").trim().takeIf { it.isNotEmpty() })
                    4 -> {
                        val map = LinkedHashMap<String, String>()
                        val token = data.optString("token").trim()
                        val uid = data.opt("userid")?.toString()?.trim().orEmpty()
                        if (token.isNotEmpty()) {
                            map["t"] = token
                            map["token"] = token
                        }
                        if (uid.isNotEmpty()) map["KugooID"] = uid
                        for (sc in resp.headers("Set-Cookie")) {
                            val p = sc.substringBefore(';').trim()
                            val idx = p.indexOf('=')
                            if (idx > 0) {
                                val k = p.substring(0, idx).trim()
                                val v = p.substring(idx + 1).trim()
                                if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
                            }
                        }
                        val merged = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        if (merged.isBlank()) return@use KugouQRPoll.Failed("未收到酷狗凭证，请改用桌面网页登录")
                        val nick = data.optString("nickname").trim().takeIf { it.isNotEmpty() }
                        val pic = data.optString("pic").takeIf { it.isNotBlank() }?.let(::secureHttps)
                        KugouQRPoll.Authorized(merged, nick, pic)
                    }
                    else -> KugouQRPoll.Failed("等待酷狗 App 扫码…")
                }
            }
        }.getOrElse { KugouQRPoll.Failed("网络波动，等待重试…") }
    }

    // ── Kugou Native Phone SMS, WeChat QR, Token Refresh & Direct /v5/url Playback ───────────────────

    enum class KugouQRKind(val label: String) {
        KUGOU("酷狗扫码"),
        WECHAT("微信扫码"),
    }

    data class KugouWeChatQRSession(
        val uuid: String,
        val imageBase64: String?,
        val qrText: String,
    )

    private const val KUGOU_PUB_KEY_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB"
    private const val KUGOU_ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp"
    private const val KUGOU_WX_APPID = "wx79f2c4418704b4f8"
    private const val KUGOU_WX_SECRET = "4efcab88b700769e376e3f6087b8abc9"

    private fun kugouRawRsaEncryptHex(plain: ByteArray): String? = runCatching {
        val spki = android.util.Base64.decode(KUGOU_PUB_KEY_B64, android.util.Base64.DEFAULT)
        val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki)) as RSAPublicKey
        val keyLen = (pub.modulus.bitLength() + 7) / 8
        val padded = ByteArray(keyLen)
        System.arraycopy(plain, 0, padded, 0, minOf(plain.size, keyLen))
        val c = BigInteger(1, padded).modPow(pub.publicExponent, pub.modulus)
        c.toString(16).padStart(keyLen * 2, '0')
    }.getOrNull()

    private fun aesCbcCrypt(data: ByteArray, key: ByteArray, iv: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun kugouAesEncrypt(obj: JSONObject, fixedKey: String? = null, fixedIv: String? = null): Pair<String, String> {
        val tempKey = fixedKey ?: md5Hex(UUID.randomUUID().toString()).take(16)
        val keyStr = if (fixedKey != null && fixedIv != null) fixedKey else md5Hex(tempKey).take(32)
        val ivStr = fixedIv ?: keyStr.takeLast(16)
        val ct = aesCbcCrypt(obj.toString().toByteArray(Charsets.UTF_8), keyStr.toByteArray(Charsets.UTF_8), ivStr.toByteArray(Charsets.UTF_8), true)
        return ct.joinToString("") { "%02x".format(it) } to tempKey
    }

    private fun kugouAesDecrypt(hex: String, tempKey: String): JSONObject? = runCatching {
        val keyStr = md5Hex(tempKey).take(32)
        val ivStr = keyStr.takeLast(16)
        val ct = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val plain = String(aesCbcCrypt(ct, keyStr.toByteArray(Charsets.UTF_8), ivStr.toByteArray(Charsets.UTF_8), false), Charsets.UTF_8)
        runCatching { JSONObject(plain) }.getOrElse { JSONObject().apply { put("token", plain) } }
    }.getOrNull()

    private fun kugouAndroidSign(params: Map<String, String>, body: String = ""): String {
        val joined = params.keys.sorted().joinToString("") { "$it=${params[it].orEmpty()}" }
        return md5Hex(KUGOU_ANDROID_SALT + joined + body + KUGOU_ANDROID_SALT)
    }

    private fun parseKugouCookie(cookie: String): List<String> {
        val map = LinkedHashMap<String, String>()
        for (seg in cookie.split(';')) {
            val t = seg.trim()
            val idx = t.indexOf('=')
            if (idx > 0) {
                val k = t.substring(0, idx).trim()
                val v = t.substring(idx + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
            }
        }
        return listOf(
            map["token"] ?: map["t"].orEmpty(),
            map["userid"] ?: map["KugooID"] ?: "0",
            map["t1"].orEmpty(),
            map["dfid"] ?: "-",
        )
    }

    suspend fun sendKugouSMSCode(phone: String, countryCode: String = "86"): NetEaseSMSResult = withContext(Dispatchers.IO) {
        val (cleanPhone, _) = normalizePhone(phone, countryCode)
        if (cleanPhone.length < 6) return@withContext NetEaseSMSResult.Failed("请输入有效的手机号")
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val bodyStr = JSONObject().apply {
            put("businessid", 5)
            put("mobile", cleanPhone)
            put("plat", 3)
        }.toString()
        val qp = linkedMapOf(
            "dfid" to "-", "mid" to stableDeviceId, "uuid" to "-",
            "appid" to "1005", "clientver" to "20489", "clienttime" to ts,
        )
        qp["signature"] = kugouAndroidSign(qp, bodyStr)
        val req = Request.Builder()
            .url("https://login-user.kugou.com/v7/send_mobile_code?${kugouQuery(qp)}")
            .post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
            .header("dfid", "-")
            .header("mid", stableDeviceId)
            .header("clienttime", ts)
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (json.optInt("status", 0) == 1) NetEaseSMSResult.Sent
                else NetEaseSMSResult.Failed(json.optString("data").ifBlank { json.optString("error") }.ifBlank { "发送失败" })
            }
        }.getOrElse { NetEaseSMSResult.Failed("无法直连酷狗短信接口，请检查网络或改用扫码") }
    }

    suspend fun loginKugouByPhone(phone: String, countryCode: String = "86", code: String): NetEasePhoneLoginResult = withContext(Dispatchers.IO) {
        val (cleanPhone, _) = normalizePhone(phone, countryCode)
        val cleanCode = code.trim()
        if (cleanPhone.length < 6) return@withContext NetEasePhoneLoginResult.Failed("请输入有效的手机号")
        if (cleanCode.isEmpty()) return@withContext NetEasePhoneLoginResult.Failed("请输入短信验证码")
        val nowMs = System.currentTimeMillis()
        val ts = (nowMs / 1000L).toString()
        val (encHex, tempKey) = kugouAesEncrypt(JSONObject().apply {
            put("mobile", cleanPhone)
            put("code", cleanCode)
        })
        val pkPlain = JSONObject().apply {
            put("clienttime_ms", nowMs)
            put("key", tempKey)
        }.toString().toByteArray(Charsets.UTF_8)
        val pkHex = kugouRawRsaEncryptHex(pkPlain)?.uppercase(Locale.ROOT)
            ?: return@withContext NetEasePhoneLoginResult.Failed("加密初始化失败")
        val maskedMobile = if (cleanPhone.length >= 11) "${cleanPhone.take(2)}*****${cleanPhone.takeLast(1)}" else cleanPhone
        val signParamsKey = md5Hex("1005${KUGOU_ANDROID_SALT}20489$nowMs")
        val bodyStr = JSONObject().apply {
            put("plat", 1)
            put("support_multi", 1)
            put("t1", 0)
            put("t2", 0)
            put("t3", "MCwwLDAsMCwwLDAsMCwwLDA=")
            put("clienttime_ms", nowMs)
            put("mobile", maskedMobile)
            put("key", signParamsKey)
            put("pk", pkHex)
            put("params", encHex)
        }.toString()
        val qp = linkedMapOf(
            "dfid" to "-", "mid" to stableDeviceId, "uuid" to "-",
            "appid" to "1005", "clientver" to "20489", "clienttime" to ts,
        )
        qp["signature"] = kugouAndroidSign(qp, bodyStr)
        val req = Request.Builder()
            .url("https://loginserviceretry.kugou.com/v7/login_by_verifycode?${kugouQuery(qp)}")
            .post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "Android16-1070-11440-130-0-LOGIN-wifi")
            .header("support-calm", "1")
            .header("dfid", "-")
            .header("mid", stableDeviceId)
            .header("clienttime", ts)
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                val root = JSONObject(resp.body?.string().orEmpty())
                if (root.optInt("status", 0) != 1) {
                    val msg = root.optString("data").ifBlank { root.optString("error") }.ifBlank { "登录失败" }
                    return@use if (msg.contains("验证") || root.optInt("error_code") == 20028) {
                        NetEasePhoneLoginResult.RequiresWebOrQR(msg)
                    } else NetEasePhoneLoginResult.Failed(msg)
                }
                val d = root.optJSONObject("data") ?: return@use NetEasePhoneLoginResult.Failed("登录响应异常")
                val secu = d.optString("secu_params")
                if (secu.isNotBlank()) {
                    kugouAesDecrypt(secu, tempKey)?.let { dec ->
                        dec.keys().forEach { k -> d.put(k, dec.opt(k)) }
                    }
                }
                val token = d.optString("token").trim()
                if (token.isEmpty()) return@use NetEasePhoneLoginResult.RequiresWebOrQR("登录成功但未解析到 token，请改用酷狗扫码")
                val uid = d.opt("userid")?.toString()?.trim().orEmpty()
                val t1 = d.optString("t1").trim()
                val parts = mutableListOf("t=$token", "token=$token")
                if (uid.isNotEmpty() && uid != "0") {
                    parts += "KugooID=$uid"
                    parts += "userid=$uid"
                }
                if (t1.isNotEmpty()) parts += "t1=$t1"
                val nick = d.optString("nickname").ifBlank { d.optString("username") }.trim().takeIf { it.isNotEmpty() }
                val pic = d.optString("pic").takeIf { it.isNotBlank() }?.let(::secureHttps)
                NetEasePhoneLoginResult.Authorized(parts.joinToString("; "), nick, pic)
            }
        }.getOrElse { NetEasePhoneLoginResult.Failed("无法直连酷狗登录接口，请检查网络或改用扫码") }
    }

    suspend fun createKugouWeChatQR(): KugouWeChatQRSession? = withContext(Dispatchers.IO) {
        val tokJson = getJson("https://api.weixin.qq.com/cgi-bin/token?appid=$KUGOU_WX_APPID&secret=$KUGOU_WX_SECRET&grant_type=client_credential", emptyMap())
            ?: return@withContext null
        val accessToken = tokJson.optString("access_token").takeIf { it.isNotBlank() } ?: return@withContext null
        val tickJson = getJson("https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=${enc(accessToken)}&type=2", emptyMap())
            ?: return@withContext null
        val ticket = tickJson.optString("ticket").takeIf { it.isNotBlank() } ?: return@withContext null
        val ts = System.currentTimeMillis().toString()
        val nonce = md5Hex(UUID.randomUUID().toString())
        val sig = MessageDigest.getInstance("SHA-1")
            .digest("appid=$KUGOU_WX_APPID&noncestr=$nonce&sdk_ticket=$ticket&timestamp=$ts".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val qrJson = getJson(
            "https://open.weixin.qq.com/connect/sdk/qrconnect?appid=$KUGOU_WX_APPID&noncestr=$nonce&timestamp=$ts&scope=snsapi_userinfo&signature=$sig",
            emptyMap(),
        ) ?: return@withContext null
        if (qrJson.optInt("errcode", -1) != 0) return@withContext null
        val uuid = qrJson.optString("uuid").takeIf { it.isNotBlank() } ?: return@withContext null
        val b64 = qrJson.optJSONObject("qrcode")?.optString("qrcodebase64")?.takeIf { it.isNotBlank() }
        KugouWeChatQRSession(uuid = uuid, imageBase64 = b64, qrText = "https://open.weixin.qq.com/connect/confirm?uuid=$uuid")
    }

    suspend fun pollKugouWeChatQR(uuid: String): KugouQRPoll = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://long.open.weixin.qq.com/connect/l/qrconnect?f=json&uuid=${enc(uuid)}")
            .header("User-Agent", DESKTOP_UA)
            .build()
        runCatching {
            longPollClient.newCall(req).execute().use { resp ->
                val j = JSONObject(resp.body?.string().orEmpty())
                when (j.optInt("wx_errcode", 408)) {
                    408 -> KugouQRPoll.Waiting
                    404 -> KugouQRPoll.Scanned(null)
                    402 -> KugouQRPoll.Expired
                    403 -> KugouQRPoll.Failed("已取消微信授权，请刷新二维码重试")
                    405 -> {
                        val code = j.optString("wx_code")
                        if (code.isBlank()) KugouQRPoll.Failed("未获取到微信授权 code") else authorizeKugouWeChat(code)
                    }
                    else -> KugouQRPoll.Waiting
                }
            }
        }.getOrElse { KugouQRPoll.Waiting }
    }

    private fun authorizeKugouWeChat(code: String): KugouQRPoll {
        val tokReq = Request.Builder()
            .url("https://api.weixin.qq.com/sns/oauth2/access_token?appid=$KUGOU_WX_APPID&secret=$KUGOU_WX_SECRET&code=${enc(code)}&grant_type=authorization_code")
            .post("".toRequestBody(null))
            .build()
        val tokJson = runCatching { client.newCall(tokReq).execute().use { JSONObject(it.body?.string().orEmpty()) } }.getOrNull()
            ?: return KugouQRPoll.Failed("微信授权换取 token 失败")
        val accessToken = tokJson.optString("access_token")
        val openid = tokJson.optString("openid")
        if (accessToken.isBlank() || openid.isBlank()) return KugouQRPoll.Failed("微信授权未返回 openid")
        val nowMs = System.currentTimeMillis()
        val ts = (nowMs / 1000L).toString()
        val (encHex, tempKey) = kugouAesEncrypt(JSONObject().apply { put("access_token", accessToken) })
        val pkHex = kugouRawRsaEncryptHex(
            JSONObject().apply { put("clienttime_ms", nowMs); put("key", tempKey) }.toString().toByteArray(Charsets.UTF_8),
        )?.uppercase(Locale.ROOT) ?: return KugouQRPoll.Failed("酷狗加密失败")
        val bodyStr = JSONObject().apply {
            put("force_login", 1)
            put("partnerid", 36)
            put("clienttime_ms", nowMs)
            put("t1", 0)
            put("t2", 0)
            put("t3", "MCwwLDAsMCwwLDAsMCwwLDA=")
            put("openid", openid)
            put("params", encHex)
            put("pk", pkHex)
        }.toString()
        val qp = linkedMapOf("dfid" to "-", "mid" to stableDeviceId, "uuid" to "-", "appid" to "1005", "clientver" to "20489", "clienttime" to ts)
        qp["signature"] = kugouAndroidSign(qp, bodyStr)
        val req = Request.Builder()
            .url("https://gateway.kugou.com/v6/login_by_openplat?${kugouQuery(qp)}")
            .post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
            .header("x-router", "login.user.kugou.com")
            .header("dfid", "-")
            .header("mid", stableDeviceId)
            .header("clienttime", ts)
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val root = JSONObject(resp.body?.string().orEmpty())
                if (root.optInt("status", 0) != 1) return@use KugouQRPoll.Failed("酷狗微信登录失败，请改用酷狗扫码或手机号")
                val d = root.optJSONObject("data") ?: return@use KugouQRPoll.Failed("登录数据异常")
                val secu = d.optString("secu_params")
                if (secu.isNotBlank()) kugouAesDecrypt(secu, tempKey)?.let { dec -> dec.keys().forEach { k -> d.put(k, dec.opt(k)) } }
                val token = d.optString("token").trim()
                if (token.isEmpty()) return@use KugouQRPoll.Failed("未收到酷狗 token")
                val uid = d.opt("userid")?.toString()?.trim().orEmpty()
                val t1 = d.optString("t1").trim()
                val parts = mutableListOf("t=$token", "token=$token")
                if (uid.isNotEmpty() && uid != "0") { parts += "KugooID=$uid"; parts += "userid=$uid" }
                if (t1.isNotEmpty()) parts += "t1=$t1"
                val nick = d.optString("nickname").ifBlank { d.optString("username") }.trim().takeIf { it.isNotEmpty() }
                val pic = d.optString("pic").takeIf { it.isNotBlank() }?.let(::secureHttps)
                KugouQRPoll.Authorized(parts.joinToString("; "), nick, pic)
            }
        }.getOrElse { KugouQRPoll.Failed("酷狗微信登录网络异常") }
    }

    private fun refreshKugouCookie(cookie: String): String? {
        val (token, userid, t1, dfid) = parseKugouCookie(cookie)
        if (token.isBlank() || userid.isBlank() || userid == "0") return null
        val nowMs = System.currentTimeMillis()
        val ts = nowMs / 1000L
        val (p3Hex, _) = kugouAesEncrypt(
            JSONObject().apply { put("clienttime", ts); put("token", token) },
            fixedKey = "90b8382a1bb4ccdcf063102053fd75b8",
            fixedIv = "f063102053fd75b8",
        )
        val (encHex, tempKey) = kugouAesEncrypt(JSONObject())
        val pkHex = kugouRawRsaEncryptHex(
            JSONObject().apply { put("clienttime_ms", nowMs); put("key", tempKey) }.toString().toByteArray(Charsets.UTF_8),
        ) ?: return null
        val bodyStr = JSONObject().apply {
            put("dfid", dfid); put("p3", p3Hex); put("plat", 1); put("t1", 0); put("t2", 0)
            put("t3", "MCwwLDAsMCwwLDAsMCwwLDA="); put("pk", pkHex); put("params", encHex)
            put("userid", userid); put("clienttime_ms", nowMs)
        }.toString()
        val qp = linkedMapOf(
            "dfid" to dfid, "mid" to stableDeviceId, "uuid" to "-",
            "appid" to "1005", "clientver" to "20489", "clienttime" to ts.toString(),
            "token" to token, "userid" to userid,
        )
        qp["signature"] = kugouAndroidSign(qp, bodyStr)
        val req = Request.Builder()
            .url("https://login-user.kugou.com/v5/login_by_token?${kugouQuery(qp)}")
            .post(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val root = JSONObject(resp.body?.string().orEmpty())
                if (root.optInt("status", 0) != 1) return@use null
                val d = root.optJSONObject("data") ?: return@use null
                val secu = d.optString("secu_params")
                if (secu.isNotBlank()) kugouAesDecrypt(secu, tempKey)?.let { dec -> dec.keys().forEach { k -> d.put(k, dec.opt(k)) } }
                val newTok = d.optString("token").trim().ifBlank { return@use null }
                val uid = d.opt("userid")?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "0" } ?: userid
                val newT1 = d.optString("t1").trim().ifBlank { t1 }
                val parts = mutableListOf("t=$newTok", "token=$newTok", "KugooID=$uid", "userid=$uid")
                if (newT1.isNotEmpty()) parts += "t1=$newT1"
                if (dfid != "-") parts += "dfid=$dfid"
                val refreshed = parts.joinToString("; ")
                runCatching {
                    val store = app.iduanju.IdjApp.instance.musicStore
                    store.sourcePrefs.firstOrNull { it.kind == MusicSourceKind.KUGOU }?.let { pref ->
                        store.setSourceAccount(pref.id, refreshed, pref.accountNickname, pref.accountAvatarURL)
                    }
                }
                refreshed
            }
        }.getOrNull()
    }

    private fun resolveKugouStream(
        hash: String,
        cookie: String,
        allowRefresh: Boolean = true,
        preferredQuality: MusicAudioQuality = MusicAudioQuality.HIRES,
    ): ResolvedAudio? {
        val cleanHash = hash.trim().lowercase(Locale.ROOT)
        val (token, userid, _, dfid) = parseKugouCookie(cookie)
        if (cleanHash.isEmpty() || token.isBlank()) return null
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val skey = md5Hex("${cleanHash}57ae12eb6890223e355ccfcb74edf70d1005${stableDeviceId}$userid")
        val specs = when (preferredQuality) {
            MusicAudioQuality.HIRES, MusicAudioQuality.LOSSLESS -> listOf("flac" to "FLAC 无损", "320" to "HQ 320k", "128" to "标准 128k")
            MusicAudioQuality.EXHIGH -> listOf("320" to "HQ 320k", "128" to "标准 128k")
            MusicAudioQuality.STANDARD -> listOf("128" to "标准 128k")
        }
        for ((q, label) in specs) {
            val qp = linkedMapOf(
                "dfid" to dfid, "mid" to stableDeviceId, "uuid" to "-", "appid" to "1005",
                "clientver" to "11430", "clienttime" to ts, "token" to token, "userid" to userid,
                "album_id" to "0", "area_code" to "1", "hash" to cleanHash, "ssa_flag" to "is_fromtrack",
                "version" to "11430", "page_id" to "151369488", "quality" to q, "album_audio_id" to "0",
                "behavior" to "play", "pid" to "2", "cmd" to "26", "pidversion" to "3001",
                "IsFreePart" to "0", "ppage_id" to "463467626,350369493,788954147",
                "cdnBackup" to "1", "module" to "", "key" to skey,
            )
            qp["signature"] = kugouAndroidSign(qp)
            val json = getJson(
                "https://gateway.kugou.com/v5/url?${kugouQuery(qp)}",
                mapOf(
                    "User-Agent" to "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi",
                    "x-router" to "trackercdn.kugou.com",
                    "dfid" to dfid,
                    "mid" to stableDeviceId,
                    "clienttime" to ts,
                ),
            ) ?: continue
            val arr = json.optJSONArray("url")
            if (arr != null && arr.length() > 0) {
                val u = arr.optString(0).trim()
                if (u.isNotEmpty()) {
                    return ResolvedAudio(
                        url = secureHttps(u),
                        headers = emptyMap(),
                        actualSource = MusicSourceKind.KUGOU,
                        qualityLabel = label,
                        entitlement = MusicEntitlement.FULL,
                        isCached = false,
                    )
                }
            }
            if (allowRefresh && (json.optInt("errcode") == 20010 || json.optInt("status") == 0)) {
                val refreshed = refreshKugouCookie(cookie)
                if (refreshed != null) return resolveKugouStream(cleanHash, refreshed, allowRefresh = false, preferredQuality = preferredQuality)
            }
        }
        return null
    }

    // ── QQ Music Serverless Native Phone SMS / QQ QR / WeChat QR Login & Vkey Playback ───────────────

    sealed class QQSMSResult {
        data object Sent : QQSMSResult()
        data class CaptchaRequired(val securityURL: String?) : QQSMSResult()
        data class Failed(val message: String) : QQSMSResult()
    }

    sealed class QQPhoneLoginResult {
        data class Authorized(val cookie: String, val nickname: String?, val avatarURL: String?) : QQPhoneLoginResult()
        data class Failed(val message: String) : QQPhoneLoginResult()
    }

    enum class QQQRKind(val label: String) {
        QQ("QQ 扫码"),
        WECHAT("微信扫码"),
    }

    data class QQQRSession(
        val kind: QQQRKind,
        val identifier: String,
        val imageBytes: ByteArray,
    )

    sealed class QQQRPoll {
        data object Waiting : QQQRPoll()
        data object Scanned : QQQRPoll()
        data object Expired : QQQRPoll()
        data object Refused : QQQRPoll()
        data class Authorized(val cookie: String, val nickname: String?, val avatarURL: String?) : QQQRPoll()
        data class Failed(val message: String) : QQQRPoll()
    }

    fun qqHash33(s: String, seed: Int = 0): Int {
        var h = seed.toLong() and 0x7fffffffL
        for (ch in s) {
            h = ((h shl 5) + h + ch.code.toLong()) and 0x7fffffffL
        }
        return h.toInt()
    }

    fun qqZZCSign(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(payload.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02X".format(it) }
        val p1Idx = intArrayOf(23, 14, 6, 36, 16, 7, 19)
        val p2Idx = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
        val scramble = intArrayOf(89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179)
        val p1 = buildString { for (i in p1Idx) append(hex[i]) }
        val p2 = buildString { for (i in p2Idx) append(hex[i]) }
        val xored = ByteArray(20) { i -> (scramble[i] xor (digest[i].toInt() and 0xFF)).toByte() }
        val b64 = android.util.Base64.encodeToString(xored, android.util.Base64.NO_WRAP)
            .replace("/", "")
            .replace("+", "")
            .replace("=", "")
        return "zzc$p1$b64$p2".lowercase(Locale.ROOT)
    }

    private fun parseQQCookie(cookie: String): Triple<String, String, Int> {
        val map = LinkedHashMap<String, String>()
        for (seg in cookie.split(';')) {
            val t = seg.trim()
            val idx = t.indexOf('=')
            if (idx > 0) {
                val k = t.substring(0, idx).trim()
                val v = t.substring(idx + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
            }
        }
        val uin = (map["qqmusic_uin"] ?: map["musicid"] ?: map["uin"].orEmpty()).filter(Char::isDigit)
        val key = map["musickey"] ?: map["qm_keyst"] ?: map["qqmusic_key"].orEmpty()
        val lt = map["tmeLoginType"]?.toIntOrNull()
            ?: when {
                key.startsWith("W_X") -> 1
                key.isNotEmpty() -> 2
                else -> 0
            }
        return Triple(uin, key, lt)
    }

    private const val QQ_QIMEI_PUB_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDEIxgwoutfwoJxcGQeedgP7FG9qaIuS0qzfR8gWkrkTZKM2iWHn2ajQpBRZjMSoSf6+KJGvar2ORhBfpDXyVtZCKpqLQ+FLkpncClKVIrBwv6PHyUvuCb0rIarmgDnzkfQAqVufEtR64iazGDKatvJ9y6B9NMbHddGSAUmRTCrHQIDAQAB"
    @Volatile private var cachedQimei36: Pair<String, Long>? = null

    private fun ensureQQQimei(): String {
        val now = System.currentTimeMillis()
        cachedQimei36?.takeIf { now - it.second < 86400_000L }?.let { return it.first }
        val q36 = runCatching {
            val spki = android.util.Base64.decode(QQ_QIMEI_PUB_B64, android.util.Base64.DEFAULT)
            val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki))
            val aid = stableDeviceId.take(16)
            val cryptKey = md5Hex(UUID.randomUUID().toString()).take(16)
            val nonce = md5Hex("${UUID.randomUUID()}-n").take(16)
            val oz = android.util.Base64.encodeToString(
                aesCbcCrypt(aid.toByteArray(), "lvcwmSYVr2Axv1gn".toByteArray(), "Zs0ntDqG2jyhKN0c".toByteArray(), true),
                android.util.Base64.NO_WRAP,
            )
            val oo = android.util.Base64.encodeToString(
                aesCbcCrypt("Pixel 8".toByteArray(), "lvcwmSYVr2Axv1gn".toByteArray(), "Zs0ntDqG2jyhKN0c".toByteArray(), true),
                android.util.Base64.NO_WRAP,
            )
            val reserved = JSONObject().apply {
                put("harmony", "0"); put("clone", "0"); put("containe", ""); put("oz", oz); put("oo", oo)
                put("kelong", "0"); put("ip", "192.168.1.25"); put("uptimes", "2026-11-28 04:00:00")
                put("multiUser", "0"); put("bod", "shiba"); put("brd", "Google"); put("dv", "shiba")
                put("firstLevel", "34"); put("manufact", "Google"); put("name", "shiba"); put("host", "abfarm")
                put("kernel", "Linux"); put("pre", "0"); put("av", "20.9.0.8"); put("ch", "")
            }.toString()
            val payload = JSONObject().apply {
                put("androidId", aid); put("platformId", 1); put("appKey", "0AND0HD6FE4HY80F")
                put("appVersion", "20.9.0.8"); put("beaconIdSrc", "k1:2026-11-01123456.123456789;k3:0000000000000000;")
                put("brand", "Google"); put("channelId", "10003505"); put("cid", ""); put("imei", "861234567890123")
                put("imsi", ""); put("mac", ""); put("model", "Pixel 8"); put("networkType", "wifi"); put("oaid", "")
                put("osVersion", "Android 14,level 34"); put("qimei", ""); put("qimei36", ""); put("sdkVersion", "1.2.13.6")
                put("targetSdkVersion", "30"); put("audit", ""); put("userId", "{}"); put("packageId", "com.tencent.qqmusic")
                put("deviceType", "Phone"); put("sdkName", ""); put("reserved", reserved)
            }.toString()
            val rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsaCipher.init(Cipher.ENCRYPT_MODE, pub)
            val keyB64 = android.util.Base64.encodeToString(rsaCipher.doFinal(cryptKey.toByteArray()), android.util.Base64.NO_WRAP)
            val ckBytes = cryptKey.toByteArray()
            val paramsB64 = android.util.Base64.encodeToString(aesCbcCrypt(payload.toByteArray(), ckBytes, ckBytes, true), android.util.Base64.NO_WRAP)
            val ts = now / 1000L
            val extra = """{"appKey":"0AND0HD6FE4HY80F"}"""
            val reqSign = md5Hex("$keyB64$paramsB64${ts * 1000L}${nonce}ZdJqM15EeO2zWc08$extra")
            val hdrSign = md5Hex("qimei_qq_androidpzAuCmaFAaFaHrdakPjLIEqKrGnSOOvH$ts")
            val qBody = JSONObject().apply {
                put("app", 0); put("os", 1)
                put("qimeiParams", JSONObject().apply {
                    put("key", keyB64); put("params", paramsB64); put("time", ts.toString())
                    put("nonce", nonce); put("sign", reqSign); put("extra", extra)
                })
            }.toString()
            val req = Request.Builder()
                .url("https://api.tencentmusic.com/tme/trpc/proxy")
                .post(qBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("method", "GetQimei")
                .header("service", "trpc.tme_datasvr.qimeiproxy.QimeiProxy")
                .header("appid", "qimei_qq_android")
                .header("sign", hdrSign)
                .header("user-agent", "QQMusic")
                .header("timestamp", ts.toString())
                .build()
            client.newCall(req).execute().use { resp ->
                val inner = JSONObject(resp.body?.string().orEmpty()).optString("data")
                JSONObject(inner).optJSONObject("data")?.optString("q36").orEmpty()
            }
        }.getOrDefault("")
        if (q36.isNotEmpty()) cachedQimei36 = q36 to now
        return q36
    }

    private fun postQQMusicCGI(
        module: String,
        method: String,
        param: JSONObject,
        commExtra: Map<String, String> = emptyMap(),
        cookie: String? = null,
    ): Pair<Int, JSONObject>? {
        val q36 = ensureQQQimei()
        val comm = JSONObject().apply {
            put("ct", "11")
            put("cv", "20090008")
            put("v", "20090008")
            put("chid", "10003505")
            put("tmeAppID", "qqmusic")
            put("OpenUDID", stableDeviceId)
            put("udid", stableDeviceId)
            put("os_ver", "14")
            put("phonetype", "Android")
            if (q36.isNotEmpty()) put("QIMEI36", q36)
            if (!cookie.isNullOrBlank()) {
                val (uin, key, lt) = parseQQCookie(cookie)
                if (uin.isNotEmpty()) put("qq", uin)
                if (key.isNotEmpty()) put("authst", key)
                if (lt > 0) put("tmeLoginType", lt.toString())
            }
            commExtra.forEach { (k, v) -> put(k, v) }
        }
        val body = JSONObject().apply {
            put("comm", comm)
            put("req_0", JSONObject().apply {
                put("module", module)
                put("method", method)
                put("param", param)
            })
        }.toString()
        val sig = qqZZCSign(body)
        val b = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musics.fcg?sign=$sig")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", "QQMusic 20090008(android 14)")
            .header("Referer", "https://y.qq.com/")
        if (!cookie.isNullOrBlank()) b.header("Cookie", cookie)
        return runCatching {
            client.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val txt = resp.body?.string().orEmpty()
                if (txt.isBlank()) return null
                val req0 = JSONObject(txt).optJSONObject("req_0") ?: return null
                req0.optInt("code", -1) to (req0.optJSONObject("data") ?: JSONObject())
            }
        }.getOrNull()
    }

    private fun buildQQCookie(data: JSONObject, defaultLoginType: Int): String? {
        val strId = data.optString("str_musicid").trim().takeIf { it.isNotEmpty() && it != "0" }
        val numId = data.optLong("musicid", 0L).takeIf { it > 0L }?.toString()
        val musicid = strId ?: numId ?: return null
        val musickey = data.optString("musickey").trim().takeIf { it.isNotEmpty() } ?: return null
        val lt = data.optInt("loginType", 0).takeIf { it > 0 }
            ?: if (musickey.startsWith("W_X")) 1 else defaultLoginType
        val parts = mutableListOf(
            "uin=$musicid",
            "qqmusic_uin=$musicid",
            "qm_keyst=$musickey",
            "qqmusic_key=$musickey",
            "musickey=$musickey",
            "tmeLoginType=$lt",
        )
        data.optString("openid").takeIf { it.isNotBlank() }?.let { parts += "openid=$it" }
        data.optString("unionid").takeIf { it.isNotBlank() }?.let { parts += "unionid=$it" }
        data.optString("encryptUin").takeIf { it.isNotBlank() }?.let { parts += "euin=$it" }
        data.optString("refresh_key").takeIf { it.isNotBlank() }?.let { parts += "refresh_key=$it" }
        data.optString("refresh_token").takeIf { it.isNotBlank() }?.let { parts += "refresh_token=$it" }
        return parts.joinToString("; ")
    }

    private fun qqLoginErrorMessage(code: Int, data: JSONObject): String = when (code) {
        20271 -> "验证码错误，请核对后重试"
        20272, 20274 -> "该手机号尚未绑定 QQ 音乐账号，请先使用 QQ / 微信扫码登录"
        20277, 20278, 20450 -> "账号受限或已封禁"
        20279 -> "登录设备数量已达上限"
        104604, 100001 -> "操作过于频繁，请稍后再试"
        1000, 104400, 104401 -> "验证码已过期或参数无效，请重新获取"
        else -> data.optString("errTip").trim()
            .ifBlank { data.optString("errMsg").trim() }
            .ifBlank { "登录失败（状态码 $code）" }
    }

    suspend fun sendQQSMSCode(phone: String, countryCode: String = "86"): QQSMSResult = withContext(Dispatchers.IO) {
        val (cleanPhone, ct) = normalizePhone(phone, countryCode)
        if (cleanPhone.length < 6) return@withContext QQSMSResult.Failed("请输入有效的手机号")
        val res = postQQMusicCGI(
            module = "music.login.LoginServer",
            method = "SendPhoneAuthCode",
            param = JSONObject().apply {
                put("tmeAppid", "qqmusic")
                put("areaCode", ct)
                put("phoneNo", cleanPhone)
            },
            commExtra = mapOf("tmeLoginMethod" to "3"),
        ) ?: return@withContext QQSMSResult.Failed("无法直连 QQ 音乐短信接口，请检查网络或改用扫码登录")
        when (res.first) {
            0 -> QQSMSResult.Sent
            20276 -> QQSMSResult.CaptchaRequired(res.second.optString("securityURL").trim().takeIf { it.isNotEmpty() })
            100001 -> QQSMSResult.Failed("发送过于频繁，请稍后再试")
            else -> QQSMSResult.Failed(qqLoginErrorMessage(res.first, res.second))
        }
    }

    suspend fun loginQQByPhone(phone: String, countryCode: String = "86", authCode: String): QQPhoneLoginResult = withContext(Dispatchers.IO) {
        val (cleanPhone, _) = normalizePhone(phone, countryCode)
        val cleanCode = authCode.trim()
        if (cleanPhone.length < 6) return@withContext QQPhoneLoginResult.Failed("请输入有效的手机号")
        if (cleanCode.isEmpty()) return@withContext QQPhoneLoginResult.Failed("请输入短信验证码")
        val res = postQQMusicCGI(
            module = "music.login.LoginServer",
            method = "Login",
            param = JSONObject().apply {
                put("code", cleanCode)
                put("loginMode", 1)
                put("phoneNo", cleanPhone)
            },
            commExtra = mapOf("tmeLoginMethod" to "3", "tmeLoginType" to "0"),
        ) ?: return@withContext QQPhoneLoginResult.Failed("无法直连 QQ 音乐登录接口，请检查网络后重试")
        if (res.first != 0) return@withContext QQPhoneLoginResult.Failed(qqLoginErrorMessage(res.first, res.second))
        val cookie = buildQQCookie(res.second, 0)
            ?: return@withContext QQPhoneLoginResult.Failed("登录成功但未收到凭证，请改用 QQ / 微信扫码登录")
        val acc = fetchQQAccount(cookie)
        QQPhoneLoginResult.Authorized(cookie, acc?.first, acc?.second)
    }

    suspend fun createQQQR(kind: QQQRKind): QQQRSession? = withContext(Dispatchers.IO) {
        when (kind) {
            QQQRKind.QQ -> {
                val t = Math.random().toString()
                val req = Request.Builder()
                    .url("https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4&t=$t&daid=383&pt_3rd_aid=100497308")
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "https://xui.ptlogin2.qq.com/")
                    .build()
                runCatching {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val bytes = resp.body?.bytes() ?: return@use null
                        val qrsig = resp.headers("Set-Cookie")
                            .map { it.substringBefore(';').trim() }
                            .firstOrNull { it.startsWith("qrsig=") }
                            ?.substringAfter("qrsig=")
                            ?.takeIf { it.isNotBlank() } ?: return@use null
                        QQQRSession(QQQRKind.QQ, qrsig, bytes)
                    }
                }.getOrNull()
            }
            QQQRKind.WECHAT -> {
                val redirect = enc("https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/")
                val href = enc("https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect")
                val pageReq = Request.Builder()
                    .url("https://open.weixin.qq.com/connect/qrconnect?appid=wx48db31d50e334801&redirect_uri=$redirect&response_type=code&scope=snsapi_login&state=STATE&href=$href")
                    .header("User-Agent", DESKTOP_UA)
                    .build()
                runCatching {
                    val uuid = client.newCall(pageReq).execute().use { resp ->
                        val html = resp.body?.string().orEmpty()
                        Regex("""uuid=([^"&']+)""").find(html)?.groupValues?.getOrNull(1)
                    } ?: return@runCatching null
                    val imgReq = Request.Builder()
                        .url("https://open.weixin.qq.com/connect/qrcode/$uuid")
                        .header("User-Agent", DESKTOP_UA)
                        .header("Referer", "https://open.weixin.qq.com/connect/qrconnect")
                        .build()
                    client.newCall(imgReq).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val bytes = resp.body?.bytes() ?: return@use null
                        QQQRSession(QQQRKind.WECHAT, uuid, bytes)
                    }
                }.getOrNull()
            }
        }
    }

    suspend fun pollQQQR(session: QQQRSession): QQQRPoll = withContext(Dispatchers.IO) {
        when (session.kind) {
            QQQRKind.QQ -> {
                val token = qqHash33(session.identifier)
                val url = "https://ssl.ptlogin2.qq.com/ptqrlogin?u1=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump&ptqrtoken=$token&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052&action=0-0-${System.currentTimeMillis()}&js_ver=20102616&js_type=1&pt_uistyle=40&aid=716027609&daid=383&pt_3rd_aid=100497308&has_onekey=1"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "https://xui.ptlogin2.qq.com/")
                    .header("Cookie", "qrsig=${session.identifier}")
                    .build()
                runCatching {
                    client.newCall(req).execute().use { resp ->
                        val txt = resp.body?.string().orEmpty()
                        val args = Regex("""'((?:\\.|[^'])*)'""").findAll(txt).map { it.groupValues[1] }.toList()
                        val code = args.firstOrNull()?.toIntOrNull() ?: return@use QQQRPoll.Failed("二维码状态解析失败")
                        when (code) {
                            66 -> QQQRPoll.Waiting
                            67 -> QQQRPoll.Scanned
                            65 -> QQQRPoll.Expired
                            68 -> QQQRPoll.Refused
                            0 -> {
                                val jump = args.getOrNull(2).orEmpty()
                                val uin = Regex("""(?:\?|&)uin=([^&]+)""").find(jump)?.groupValues?.getOrNull(1)
                                val sigx = Regex("""(?:\?|&)ptsigx=([^&]+)""").find(jump)?.groupValues?.getOrNull(1)
                                if (uin.isNullOrBlank() || sigx.isNullOrBlank()) {
                                    QQQRPoll.Failed("无法解析 QQ 授权票据")
                                } else {
                                    authorizeQQQR(uin, sigx)
                                }
                            }
                            else -> QQQRPoll.Failed(args.getOrNull(4).orEmpty().ifBlank { "等待扫码…" })
                        }
                    }
                }.getOrElse { QQQRPoll.Failed("网络波动，等待重试…") }
            }
            QQQRKind.WECHAT -> {
                val url = "https://lp.open.weixin.qq.com/connect/l/qrconnect?uuid=${enc(session.identifier)}&_=${System.currentTimeMillis()}"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "https://open.weixin.qq.com/")
                    .build()
                runCatching {
                    longPollClient.newCall(req).execute().use { resp ->
                        val txt = resp.body?.string().orEmpty()
                        val code = Regex("""wx_errcode=(\d+)""").find(txt)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: return@use QQQRPoll.Waiting
                        when (code) {
                            408 -> QQQRPoll.Waiting
                            404 -> QQQRPoll.Scanned
                            402 -> QQQRPoll.Expired
                            403 -> QQQRPoll.Refused
                            405 -> {
                                val wxCode = Regex("""wx_code='([^']+)'""").find(txt)?.groupValues?.getOrNull(1)
                                if (wxCode.isNullOrBlank()) QQQRPoll.Failed("未获取到微信授权 code")
                                else authorizeWeChatQR(wxCode)
                            }
                            else -> QQQRPoll.Waiting
                        }
                    }
                }.getOrElse { QQQRPoll.Waiting }
            }
        }
    }

    private suspend fun authorizeQQQR(uin: String, sigx: String): QQQRPoll {
        val checkUrl = "https://ssl.ptlogin2.graph.qq.com/check_sig?uin=${enc(uin)}&pttype=1&service=ptqrlogin&nodirect=0&ptsigx=${enc(sigx)}&s_url=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump&ptlang=2052&ptredirect=100&aid=716027609&daid=383&j_later=0&low_login_hour=0&regmaster=0&pt_login_type=3&pt_aid=0&pt_aaid=16&pt_light=0&pt_3rd_aid=100497308"
        val checkReq = Request.Builder()
            .url(checkUrl)
            .header("User-Agent", DESKTOP_UA)
            .header("Referer", "https://xui.ptlogin2.qq.com/")
            .build()
        val cookieMap = LinkedHashMap<String, String>()
        runCatching {
            noRedirectClient.newCall(checkReq).execute().use { resp ->
                for (sc in resp.headers("Set-Cookie")) {
                    val p = sc.substringBefore(';').trim()
                    val idx = p.indexOf('=')
                    if (idx > 0) {
                        val k = p.substring(0, idx).trim()
                        val v = p.substring(idx + 1).trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) cookieMap[k] = v
                    }
                }
            }
        }
        val pSkey = cookieMap["p_skey"] ?: return QQQRPoll.Failed("获取 p_skey 失败，请重试或改用桌面网页登录")
        val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val form = listOf(
            "response_type" to "code",
            "client_id" to "100497308",
            "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
            "scope" to "get_user_info,get_app_friends",
            "state" to "state",
            "switch" to "",
            "from_ptlogin" to "1",
            "src" to "1",
            "update_auth" to "1",
            "openapi" to "1010_1030",
            "g_tk" to qqHash33(pSkey, 5381).toString(),
            "auth_time" to System.currentTimeMillis().toString(),
            "ui" to UUID.randomUUID().toString(),
        ).joinToString("&") { "${it.first}=${enc(it.second)}" }
        val authReq = Request.Builder()
            .url("https://graph.qq.com/oauth2.0/authorize")
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", DESKTOP_UA)
            .header("Cookie", cookieHeader)
            .build()
        val code = runCatching {
            noRedirectClient.newCall(authReq).execute().use { resp ->
                val loc = resp.header("Location").orEmpty()
                Regex("""(?:\?|&)code=([^&]+)""").find(loc)?.groupValues?.getOrNull(1)
            }
        }.getOrNull() ?: return QQQRPoll.Failed("QQ 授权未返回 code，请重试或改用桌面网页登录")
        val res = postQQMusicCGI(
            module = "QQConnectLogin.LoginServer",
            method = "QQLogin",
            param = JSONObject().apply { put("code", code) },
            commExtra = mapOf("tmeLoginType" to "2"),
        ) ?: return QQQRPoll.Failed("换取 QQ 音乐凭证失败，请重试")
        if (res.first != 0) return QQQRPoll.Failed(qqLoginErrorMessage(res.first, res.second))
        val cookie = buildQQCookie(res.second, 2) ?: return QQQRPoll.Failed("未收到 QQ 音乐凭证")
        val acc = fetchQQAccount(cookie)
        return QQQRPoll.Authorized(cookie, acc?.first, acc?.second)
    }

    private suspend fun authorizeWeChatQR(code: String): QQQRPoll {
        val res = postQQMusicCGI(
            module = "music.login.LoginServer",
            method = "Login",
            param = JSONObject().apply {
                put("code", code)
                put("strAppid", "wx48db31d50e334801")
            },
            commExtra = mapOf("tmeLoginType" to "1"),
        ) ?: return QQQRPoll.Failed("换取微信登录凭证失败，请重试")
        if (res.first != 0) return QQQRPoll.Failed(qqLoginErrorMessage(res.first, res.second))
        val cookie = buildQQCookie(res.second, 1) ?: return QQQRPoll.Failed("未收到 QQ 音乐凭证")
        val acc = fetchQQAccount(cookie)
        return QQQRPoll.Authorized(cookie, acc?.first, acc?.second)
    }

    suspend fun fetchQQAccount(cookie: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val (uin, _, _) = parseQQCookie(cookie)
        val res = postQQMusicCGI(
            module = "music.UserInfo.userInfoServer",
            method = "GetLoginUserInfo",
            param = JSONObject(),
            cookie = cookie,
        )
        if (res != null && res.first == 0) {
            val info = res.second.optJSONObject("userInfo") ?: res.second
            val nick = info.optString("nick").trim()
                .ifBlank { info.optString("nickname").trim() }
                .ifBlank { res.second.optString("nick").trim() }
            val head = info.optString("headurl").ifBlank { info.optString("headpic") }.ifBlank { res.second.optString("headurl") }
            if (nick.isNotEmpty()) return@withContext nick to secureHttps(head)
        }
        if (uin.isNotEmpty()) ("QQ音乐 $uin" to "") else null
    }

    private fun refreshQQCookie(cookie: String): String? {
        val map = LinkedHashMap<String, String>()
        for (seg in cookie.split(';')) {
            val t = seg.trim()
            val idx = t.indexOf('=')
            if (idx > 0) {
                val k = t.substring(0, idx).trim()
                val v = t.substring(idx + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
            }
        }
        val (uin, musickey, lt) = parseQQCookie(cookie)
        val rk = map["refresh_key"].orEmpty()
        val rt = map["refresh_token"].orEmpty()
        if (uin.isEmpty() || musickey.isEmpty() || (rk.isEmpty() && rt.isEmpty())) return null
        val res = postQQMusicCGI(
            module = "music.login.LoginServer",
            method = "Login",
            param = JSONObject().apply {
                put("openid", map["openid"].orEmpty())
                put("refresh_token", rt)
                put("str_musicid", uin)
                put("musicid", uin.toLongOrNull() ?: 0L)
                put("musickey", musickey)
                put("unionid", map["unionid"].orEmpty())
                put("refresh_key", rk)
                put("loginMode", 2)
            },
            commExtra = mapOf("tmeLoginType" to lt.toString()),
            cookie = cookie,
        ) ?: return null
        if (res.first != 0) return null
        val refreshed = buildQQCookie(res.second, lt) ?: return null
        runCatching {
            val store = app.iduanju.IdjApp.instance.musicStore
            store.sourcePrefs.firstOrNull { it.kind == MusicSourceKind.QQ }?.let { pref ->
                store.setSourceAccount(pref.id, refreshed, pref.accountNickname, pref.accountAvatarURL)
            }
        }
        return refreshed
    }

    private fun resolveQQStream(
        songMid: String,
        cookie: String,
        allowRefresh: Boolean = true,
        preferredQuality: MusicAudioQuality = MusicAudioQuality.HIRES,
    ): ResolvedAudio? {
        val (uin, musickey, _) = parseQQCookie(cookie)
        if (uin.isEmpty() || musickey.isEmpty() || songMid.isBlank()) return null
        val specs = when (preferredQuality) {
            MusicAudioQuality.HIRES, MusicAudioQuality.LOSSLESS -> listOf(
                Triple("F000", ".flac", "FLAC 无损"),
                Triple("M800", ".mp3", "HQ 320k"),
                Triple("M500", ".mp3", "标准 128k"),
                Triple("C400", ".m4a", "AAC 96k"),
            )
            MusicAudioQuality.EXHIGH -> listOf(
                Triple("M800", ".mp3", "HQ 320k"),
                Triple("M500", ".mp3", "标准 128k"),
                Triple("C400", ".m4a", "AAC 96k"),
            )
            MusicAudioQuality.STANDARD -> listOf(
                Triple("M500", ".mp3", "标准 128k"),
                Triple("C400", ".m4a", "AAC 96k"),
            )
        }
        for ((prefix, ext, label) in specs) {
            val filename = "$prefix$songMid$songMid$ext"
            val res = postQQMusicCGI(
                module = "music.vkey.GetVkey",
                method = "UrlGetVkey",
                param = JSONObject().apply {
                    put("uin", uin)
                    put("filename", JSONArray().put(filename))
                    put("guid", stableDeviceId)
                    put("songmid", JSONArray().put(songMid))
                    put("songtype", JSONArray().put(0))
                    put("ctx", 0)
                },
                cookie = cookie,
            ) ?: continue
            if (allowRefresh && res.first in setOf(1000, 104400, 104401)) {
                val refreshed = refreshQQCookie(cookie)
                return if (refreshed != null) resolveQQStream(songMid, refreshed, allowRefresh = false, preferredQuality = preferredQuality) else null
            }
            if (res.first != 0) continue
            val purl = res.second.optJSONArray("midurlinfo")?.optJSONObject(0)?.optString("purl").orEmpty()
            if (purl.isBlank()) continue
            val sipArr = res.second.optJSONArray("sip")
            var hostBase = "https://ws.stream.qqmusic.qq.com/"
            if (sipArr != null) {
                for (i in 0 until sipArr.length()) {
                    val s = sipArr.optString(i).trim()
                    if (s.isNotEmpty()) {
                        hostBase = secureHttps(s)
                        break
                    }
                }
            }
            val fullUrl = if (purl.startsWith("http")) secureHttps(purl)
            else if (hostBase.endsWith("/")) "$hostBase$purl" else "$hostBase/$purl"
            return ResolvedAudio(
                url = fullUrl,
                headers = mapOf("Referer" to "https://y.qq.com/"),
                actualSource = MusicSourceKind.QQ,
                qualityLabel = label,
                entitlement = MusicEntitlement.FULL,
                isCached = false,
            )
        }
        return null
    }

    // ── Qishui Music (汽水音乐 / Soda Music — Charts, Search, Playback, Lyrics & Douyin QR) ────────

    data class QishuiQRSession(
        val token: String,
        val imageBase64: String?,
        val qrText: String,
        val initialCookie: String,
    )

    sealed class QishuiQRPoll {
        data object Waiting : QishuiQRPoll()
        data object Scanned : QishuiQRPoll()
        data object Expired : QishuiQRPoll()
        data object Refused : QishuiQRPoll()
        data class Authorized(val cookie: String, val nickname: String?, val avatarURL: String?) : QishuiQRPoll()
        data class Failed(val message: String) : QishuiQRPoll()
    }

    @Volatile private var cachedQishuiDiscoverTracks: List<MusicTrack> = emptyList()

    private fun qishuiCoverUrl(obj: Any?): String {
        if (obj is String) return secureHttps(obj)
        val dict = obj as? JSONObject ?: return ""
        val uri = dict.optString("uri").trim()
        val urls = dict.optJSONArray("urls")
        val first = urls?.optString(0)?.trim().orEmpty()
        if (first.isNotEmpty() && uri.isNotEmpty()) {
            val base = secureHttps(first + uri)
            return if (base.contains("~")) base else "$base~c5_500x500.jpeg"
        }
        return secureHttps(dict.optString("url"))
    }

    private fun parseQishuiTrack(tr: JSONObject): MusicTrack? {
        val tid = tr.opt("id")?.toString()?.trim().orEmpty()
        val title = tr.optString("name").trim()
        if (tid.isEmpty() || title.isEmpty()) return null
        val arArr = tr.optJSONArray("artists")
        val artists = buildList {
            if (arArr != null) {
                for (i in 0 until arArr.length()) {
                    val a = arArr.optJSONObject(i) ?: continue
                    val n = a.optString("name").trim().ifBlank { a.optJSONObject("user_info")?.optString("nickname")?.trim().orEmpty() }
                    if (n.isNotEmpty()) add(n)
                }
            }
        }.joinToString(" / ").ifEmpty { "汽水音乐人" }
        val alb = tr.optJSONObject("album")
        val cover = qishuiCoverUrl(alb?.opt("url_cover") ?: tr.opt("url_cover"))
        val rawDur = tr.optDouble("duration", 210_000.0)
        val durSec = if (rawDur > 1000.0) rawDur / 1000.0 else rawDur
        return MusicTrack(
            id = "qishui:$tid",
            title = title,
            artist = artists,
            album = alb?.optString("name").orEmpty(),
            coverURL = cover,
            duration = durSec.coerceAtLeast(20.0),
            sourceId = MusicSourceKind.QISHUI.id,
            sourceTrackId = tid,
            qualityLabel = "汽水直连",
            entitlementName = MusicEntitlement.FULL.name,
        )
    }

    private suspend fun fetchQishuiCharts(): List<MusicChart> = coroutineScope {
        val json = postJson(
            "https://beta-luna.douyin.com/luna/discover/mix",
            """{"count":10}""",
            mapOf("User-Agent" to "Luna/19.1.0 Android"),
        ) ?: return@coroutineScope emptyList()
        val blocks = json.optJSONArray("inner_block") ?: return@coroutineScope emptyList()
        val specs = mutableListOf<Triple<String, String, String>>()
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            val resArr = b.optJSONArray("resources") ?: continue
            for (j in 0 until resArr.length()) {
                val pl = resArr.optJSONObject(j)?.optJSONObject("entity")?.optJSONObject("playlist") ?: continue
                val pid = pl.opt("id")?.toString()?.trim().orEmpty()
                if (pid.isEmpty() || specs.any { it.first == pid }) continue
                val title = pl.optString("title").trim().ifBlank { b.optString("title").trim() }.ifBlank { "汽水精选" }
                specs += Triple(pid, title, qishuiCoverUrl(pl.opt("url_cover")))
                if (specs.size >= 4) break
            }
            if (specs.size >= 4) break
        }
        val charts = specs.map { (pid, title, cover) ->
            async {
                val detail = postJson(
                    "https://beta-luna.douyin.com/luna/playlist/detail",
                    JSONObject().apply { put("playlist_id", pid); put("count", 25) }.toString(),
                    mapOf("User-Agent" to "Luna/19.1.0 Android"),
                ) ?: return@async null
                val mres = detail.optJSONArray("media_resources") ?: return@async null
                val tracks = buildList {
                    for (k in 0 until mres.length()) {
                        val item = mres.optJSONObject(k) ?: continue
                        val ent = item.optJSONObject("entity")
                        val tr = ent?.optJSONObject("track_wrapper")?.optJSONObject("track")
                            ?: ent?.optJSONObject("track")
                            ?: item.optJSONObject("track")
                            ?: continue
                        parseQishuiTrack(tr)?.let(::add)
                    }
                }
                if (tracks.isEmpty()) return@async null
                val covers = tracks.take(3).map { it.coverURL }.filter { it.isNotBlank() }.ifEmpty {
                    if (cover.isNotBlank()) listOf(cover) else emptyList()
                }
                MusicChart(
                    id = "qishui:$pid",
                    name = title,
                    subtitle = "汽水发现推荐",
                    sourceId = MusicSourceKind.QISHUI.id,
                    updatedLabel = "今日精选 · 汽水音乐",
                    coverURLs = covers,
                    tracks = tracks,
                )
            }
        }.awaitAll().filterNotNull()
        val all = charts.flatMap { it.tracks }
        if (all.isNotEmpty()) cachedQishuiDiscoverTracks = all
        charts
    }

    private fun searchQishui(query: String, cookie: String?): List<MusicTrack> {
        if (!cookie.isNullOrBlank()) {
            val devDigits = stableDeviceId.filter(Char::isDigit)
            val devId = if (devDigits.length >= 16) devDigits.take(16) else "7392817263541209"
            val params = linkedMapOf(
                "aid" to "386088", "app_name" to "luna_pc", "region" to "cn", "geo_region" to "cn", "os_region" to "cn",
                "device_id" to devId, "iid" to devId, "version_name" to "3.0.0", "version_code" to "30000000",
                "channel" to "official", "build_mode" to "master", "ac" to "wifi", "tz_name" to "Asia/Shanghai",
                "device_platform" to "windows", "device_type" to "Windows", "os_version" to "Windows 11",
                "fp" to devId, "q" to query, "cursor" to "0", "search_method" to "input",
            )
            val json = getJson(
                "https://api.qishui.com/luna/pc/search/track?${kugouQuery(params)}",
                mapOf("User-Agent" to "LunaPC/3.0.0(290101097)", "Cookie" to cookie),
            )
            val groups = json?.optJSONArray("result_groups")
            if (groups != null) {
                val out = mutableListOf<MusicTrack>()
                for (i in 0 until groups.length()) {
                    val data = groups.optJSONObject(i)?.optJSONArray("data") ?: continue
                    for (j in 0 until data.length()) {
                        val item = data.optJSONObject(j) ?: continue
                        val ent = item.optJSONObject("entity")
                        val tr = ent?.optJSONObject("track")
                            ?: ent?.optJSONObject("track_wrapper")?.optJSONObject("track")
                            ?: item.optJSONObject("track")
                            ?: continue
                        parseQishuiTrack(tr)?.let { out += it }
                    }
                }
                if (out.isNotEmpty()) return out
            }
        }
        val lower = query.lowercase(Locale.ROOT)
        return cachedQishuiDiscoverTracks.filter {
            it.title.lowercase(Locale.ROOT).contains(lower) ||
                it.artist.lowercase(Locale.ROOT).contains(lower) ||
                it.album.lowercase(Locale.ROOT).contains(lower)
        }
    }

    private fun fetchQishuiSEOTrack(trackId: String, cookie: String?): Pair<String?, String?>? {
        val hdrs = buildMap {
            put("User-Agent", DESKTOP_UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val json = getJson("https://beta-luna.douyin.com/luna/h5/seo_track?track_id=${enc(trackId)}&device_platform=web", hdrs)
            ?: return null
        val lyric = json.optJSONObject("lyric")?.optString("content")?.takeIf { it.isNotBlank() }
        var audioUrl: String? = null
        val vmStr = json.optJSONObject("track_player")?.optString("video_model").orEmpty()
        if (vmStr.isNotBlank()) {
            val vlist = runCatching { JSONObject(vmStr).optJSONArray("video_list") }.getOrNull()
            if (vlist != null) {
                for (i in 0 until vlist.length()) {
                    val item = vlist.optJSONObject(i) ?: continue
                    val u = item.optString("main_url").trim().ifBlank { item.optString("backup_url").trim() }
                    if (u.isNotEmpty()) { audioUrl = u; break }
                }
            }
        }
        return audioUrl to lyric
    }

    private fun resolveQishuiStream(trackId: String, cookie: String?): ResolvedAudio? {
        if (trackId.isBlank()) return null
        if (!cookie.isNullOrBlank()) {
            val mp = postJson(
                "https://beta-luna.douyin.com/luna/media-player",
                JSONObject().apply {
                    put("media_id", trackId); put("media_type", "track")
                    put("enable_dash", false); put("enable_refresh_api", false)
                }.toString(),
                mapOf("User-Agent" to "Luna/19.1.0 Android", "Cookie" to cookie),
            )
            val info = mp?.optJSONObject("url_player_info")
            val u = info?.optString("main_url").orEmpty().ifBlank { info?.optString("url").orEmpty() }
            if (u.isNotBlank()) {
                return ResolvedAudio(
                    url = secureHttps(u),
                    headers = emptyMap(),
                    actualSource = MusicSourceKind.QISHUI,
                    qualityLabel = "汽水高音质",
                    entitlement = MusicEntitlement.FULL,
                    isCached = false,
                )
            }
        }
        val seo = fetchQishuiSEOTrack(trackId, cookie)
        val u = seo?.first
        if (!u.isNullOrBlank()) {
            return ResolvedAudio(
                url = secureHttps(u),
                headers = emptyMap(),
                actualSource = MusicSourceKind.QISHUI,
                qualityLabel = "汽水直连",
                entitlement = MusicEntitlement.FULL,
                isCached = false,
            )
        }
        return null
    }

    suspend fun createQishuiQR(): QishuiQRSession? = withContext(Dispatchers.IO) {
        val qp = linkedMapOf(
            "passport_jssdk_version" to "2.4.13", "passport_jssdk_type" to "normal",
            "is_from_ttaccountsdk" to "1", "aid" to "386088", "next" to "https://api.qishui.com",
            "need_logo" to "false", "need_short_url" to "false", "is_new_login" to "1",
        )
        val req = Request.Builder()
            .url("https://api.qishui.com/passport/web/get_qrcode/?${kugouQuery(qp)}")
            .header("User-Agent", DESKTOP_UA)
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                val data = JSONObject(resp.body?.string().orEmpty()).optJSONObject("data") ?: return@use null
                val token = data.optString("token").takeIf { it.isNotBlank() } ?: return@use null
                val qrIdx = data.optString("qrcode_index_url").orEmpty()
                val tokenFromUrl = runCatching { android.net.Uri.parse(qrIdx).getQueryParameter("token") }.getOrNull()
                val scanToken = if (!tokenFromUrl.isNullOrBlank()) tokenFromUrl else token
                // Official Qishui PC client builds `light/invoke/scan_login` QR (Passport's raw PNG points to 404 `sdk-next`).
                val scanLoginUrl = "https://bff-pc.qishui.com/light/invoke/scan_login?token=${enc(scanToken)}&os=Windows&computer_name=iDuanJu"
                val initCk = resp.headers("Set-Cookie")
                    .mapNotNull { it.substringBefore(';').trim().takeIf { p -> p.contains('=') } }
                    .joinToString("; ")
                QishuiQRSession(token = token, imageBase64 = null, qrText = scanLoginUrl, initialCookie = initCk)
            }
        }.getOrNull()
    }

    private fun parseCookiePairs(raw: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in raw.split(';')) {
            val p = part.trim()
            val eq = p.indexOf('=')
            if (eq > 0) {
                val k = p.substring(0, eq).trim()
                val v = p.substring(eq + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
        }
        return out
    }

    private fun followQishuiRedirectsCollectingCookies(startUrl: String, jar: LinkedHashMap<String, String>) {
        val noRedir = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var current = startUrl.trim()
        for (hop in 0 until 8) {
            if (current.isEmpty()) break
            val rb = Request.Builder().url(current).header("User-Agent", DESKTOP_UA)
            val ck = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (ck.isNotEmpty()) rb.header("Cookie", ck)
            val next = runCatching {
                noRedir.newCall(rb.build()).execute().use { resp ->
                    for (sc in resp.headers("Set-Cookie")) {
                        val p = sc.substringBefore(';').trim()
                        val eq = p.indexOf('=')
                        if (eq > 0) {
                            val k = p.substring(0, eq).trim()
                            val v = p.substring(eq + 1).trim()
                            if (k.isNotEmpty() && v.isNotEmpty()) jar[k] = v
                        }
                    }
                    val loc = resp.header("Location")
                    if (resp.code in 300..399 && !loc.isNullOrBlank()) {
                        java.net.URL(java.net.URL(current), loc).toString()
                    } else null
                }
            }.getOrNull()
            if (next.isNullOrBlank()) break
            current = next
        }
    }

    suspend fun pollQishuiQR(session: QishuiQRSession): QishuiQRPoll = withContext(Dispatchers.IO) {
        val qp = linkedMapOf(
            "passport_jssdk_version" to "2.4.13", "passport_jssdk_type" to "normal",
            "is_from_ttaccountsdk" to "1", "aid" to "386088", "iid" to "27960026095955",
        )
        val bodyMap = linkedMapOf(
            "need_logo" to "false", "need_short_url" to "false", "is_frontier" to "false",
            "token" to session.token, "is_new_login" to "1", "next" to "https://api.qishui.com",
        )
        val form = kugouQuery(bodyMap)
        val postReq = Request.Builder()
            .url("https://api.qishui.com/passport/web/check_qrconnect/?${kugouQuery(qp)}")
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", DESKTOP_UA)
            .apply { if (session.initialCookie.isNotBlank()) header("Cookie", session.initialCookie) }
            .build()
        runCatching {
            val pair: Pair<JSONObject?, List<String>> = runCatching {
                client.newCall(postReq).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    val j = runCatching { JSONObject(bodyStr) }.getOrNull()
                    if (resp.isSuccessful && j != null) j to resp.headers("Set-Cookie") else null to emptyList()
                }
            }.getOrDefault(null to emptyList()).let { firstTry ->
                if (firstTry.first != null) firstTry
                else {
                    val getParams = LinkedHashMap(qp).apply { putAll(bodyMap) }
                    val getReq = Request.Builder()
                        .url("https://api.qishui.com/passport/web/check_qrconnect/?${kugouQuery(getParams)}")
                        .header("User-Agent", DESKTOP_UA)
                        .apply { if (session.initialCookie.isNotBlank()) header("Cookie", session.initialCookie) }
                        .build()
                    client.newCall(getReq).execute().use { resp ->
                        runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull() to resp.headers("Set-Cookie")
                    }
                }
            }
            val d = pair.first?.optJSONObject("data") ?: return@runCatching QishuiQRPoll.Waiting
            val errCode = d.optInt("error_code", 0)
            if (errCode == 2046) return@runCatching QishuiQRPoll.Scanned
            if (errCode == 7) return@runCatching QishuiQRPoll.Waiting
            when (d.optString("status").trim().lowercase(Locale.ROOT)) {
                "new", "waiting", "" -> QishuiQRPoll.Waiting
                "1", "scanned" -> QishuiQRPoll.Scanned
                "2", "5", "expired" -> QishuiQRPoll.Expired
                "refused", "canceled" -> QishuiQRPoll.Refused
                "3", "confirmed", "success" -> {
                    val jar = parseCookiePairs(session.initialCookie)
                    for (sc in pair.second) {
                        val p = sc.substringBefore(';').trim()
                        val idx = p.indexOf('=')
                        if (idx > 0) {
                            val k = p.substring(0, idx).trim()
                            val v = p.substring(idx + 1).trim()
                            if (k.isNotEmpty() && v.isNotEmpty()) jar[k] = v
                        }
                    }
                    d.optString("sessionid").takeIf { it.isNotBlank() }?.let { jar["sessionid"] = it }
                    val redir = d.optString("redirect_url").ifBlank { d.optString("redirectUrl") }.trim()
                    if (redir.isNotEmpty()) {
                        followQishuiRedirectsCollectingCookies(redir, jar)
                    }
                    if (jar["sessionid"].isNullOrBlank() && !jar["sessionid_ss"].isNullOrBlank()) {
                        jar["sessionid"] = jar["sessionid_ss"]!!
                    }
                    val merged = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    if (merged.isBlank()) return@runCatching QishuiQRPoll.Failed("已确认但未收到汽水 Cookie，请改用桌面网页")
                    val acc = fetchQishuiAccount(merged)
                    QishuiQRPoll.Authorized(merged, acc?.first, acc?.second)
                }
                else -> QishuiQRPoll.Waiting
            }
        }.getOrElse { QishuiQRPoll.Failed("网络波动，等待重试…") }
    }

    suspend fun fetchQishuiAccount(cookie: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val json = getJson(
            "https://api.qishui.com/luna/pc/me?aid=386088",
            mapOf("User-Agent" to "LunaPC/3.0.0(290101097)", "Cookie" to cookie),
        ) ?: return@withContext null
        val info = json.optJSONObject("my_info") ?: json.optJSONObject("user") ?: json
        val nick = info.optString("nickname").trim().ifBlank { info.optString("name").trim() }
        val avatar = qishuiCoverUrl(info.opt("avatar_url") ?: info.opt("medium_avatar_url") ?: info.opt("thumb_avatar_url"))
        if (nick.isNotEmpty()) nick to avatar else null
    }

    suspend fun fetchArtistDetail(name: String, artistId: String?, sourceId: String, coverHint: String = ""): MusicArtistDetail = withContext(Dispatchers.IO) {
        var aid = if (sourceId == MusicSourceKind.NETEASE.id) artistId else null
        if (aid.isNullOrBlank()) {
            val hits = runCatching { searchNetEase(name, null) }.getOrDefault(emptyList())
            aid = hits.firstOrNull { it.primaryArtistName.contains(name, true) }?.artistId ?: hits.firstOrNull()?.artistId
        }
        if (!aid.isNullOrBlank()) {
            val topJson = getJson("https://music.163.com/api/v1/artist/$aid", mapOf("Referer" to "https://music.163.com/", "User-Agent" to UA))
            val albJson = getJson("https://music.163.com/api/artist/albums/$aid?limit=18", mapOf("Referer" to "https://music.163.com/", "User-Agent" to UA))
            if (topJson != null) {
                val artistObj = topJson.optJSONObject("artist")
                val resolvedName = artistObj?.optString("name")?.takeIf { it.isNotBlank() } ?: name
                val pic = secureHttps(artistObj?.optString("picUrl").orEmpty().ifBlank { artistObj?.optString("img1v1Url").orEmpty() }).ifBlank { coverHint }
                val bio = artistObj?.optString("briefDesc").orEmpty().trim()
                val hotArr = topJson.optJSONArray("hotSongs")
                val hotSongs = buildList {
                    if (hotArr != null) {
                        for (i in 0 until minOf(hotArr.length(), 30)) {
                            val t = hotArr.optJSONObject(i) ?: continue
                            val tid = t.optLong("id", 0L).takeIf { it > 0 }?.toString() ?: continue
                            val title = t.optString("name").takeIf { it.isNotBlank() } ?: continue
                            val arArr = t.optJSONArray("ar")
                            val al = t.optJSONObject("al")
                            val alId = al?.optLong("id", 0L)?.takeIf { it > 0 }?.toString()
                            val fee = t.optInt("fee", 0)
                            add(
                                MusicTrack(
                                    id = "netease:$tid",
                                    title = title,
                                    artist = parseJoinedNames(arArr, "name").ifEmpty { resolvedName },
                                    album = al?.optString("name").orEmpty(),
                                    coverURL = secureHttps(al?.optString("picUrl").orEmpty()).ifBlank { pic },
                                    duration = t.optDouble("dt", 0.0) / 1000.0,
                                    sourceId = MusicSourceKind.NETEASE.id,
                                    sourceTrackId = tid,
                                    qualityLabel = if (fee == 0 || fee == 8) "HQ 320k" else "320k / 自动换源",
                                    entitlementName = if (fee == 0 || fee == 8) MusicEntitlement.FULL.name else MusicEntitlement.PREVIEW.name,
                                    artistId = aid,
                                    albumId = alId,
                                ),
                            )
                        }
                    }
                }
                val hotAlbumsArr = albJson?.optJSONArray("hotAlbums")
                val fmtYear = java.text.SimpleDateFormat("yyyy", Locale.US)
                val albums = buildList {
                    if (hotAlbumsArr != null) {
                        for (i in 0 until hotAlbumsArr.length()) {
                            val a = hotAlbumsArr.optJSONObject(i) ?: continue
                            val alId = a.optLong("id", 0L).takeIf { it > 0 }?.toString() ?: continue
                            val alName = a.optString("name").takeIf { it.isNotBlank() } ?: continue
                            val pub = a.optLong("publishTime", 0L)
                            add(
                                MusicAlbumSummary(
                                    id = alId,
                                    name = alName,
                                    artist = resolvedName,
                                    artistId = aid,
                                    sourceId = MusicSourceKind.NETEASE.id,
                                    coverURL = secureHttps(a.optString("picUrl").orEmpty()),
                                    yearText = if (pub > 0) fmtYear.format(java.util.Date(pub)) else null,
                                    trackCount = a.optInt("size", 0),
                                ),
                            )
                        }
                    }
                }
                if (hotSongs.isNotEmpty() || albums.isNotEmpty() || bio.isNotEmpty()) {
                    return@withContext MusicArtistDetail(aid, resolvedName, MusicSourceKind.NETEASE.id, pic, bio, hotSongs, albums)
                }
            }
        }
        val fallback = runCatching { searchNetEase(name, null) }.getOrDefault(emptyList())
        MusicArtistDetail(artistId ?: name, name, sourceId, coverHint.ifBlank { fallback.firstOrNull()?.coverURL.orEmpty() }, "", fallback, emptyList())
    }

    suspend fun fetchAlbumDetail(name: String, albumId: String?, artist: String, artistId: String?, sourceId: String, coverHint: String = ""): MusicAlbumDetail = withContext(Dispatchers.IO) {
        var alId = if (sourceId == MusicSourceKind.NETEASE.id) albumId else null
        if (alId.isNullOrBlank()) {
            val hits = runCatching { searchNetEase("$name $artist", null) }.getOrDefault(emptyList())
            alId = hits.firstOrNull { it.album.contains(name, true) }?.albumId ?: hits.firstOrNull()?.albumId
        }
        if (!alId.isNullOrBlank()) {
            val json = getJson("https://music.163.com/api/v1/album/$alId", mapOf("Referer" to "https://music.163.com/", "User-Agent" to UA))
            if (json != null) {
                val alb = json.optJSONObject("album")
                val resolvedName = alb?.optString("name")?.takeIf { it.isNotBlank() } ?: name
                val cover = secureHttps(alb?.optString("picUrl").orEmpty()).ifBlank { coverHint }
                val artObj = alb?.optJSONObject("artist")
                val artName = artObj?.optString("name")?.takeIf { it.isNotBlank() } ?: artist
                val artId = artObj?.optLong("id", 0L)?.takeIf { it > 0 }?.toString() ?: artistId
                val pub = alb?.optLong("publishTime", 0L) ?: 0L
                val relDate = if (pub > 0) java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(pub)) else null
                val company = alb?.optString("company")?.takeIf { it.isNotBlank() }
                val desc = alb?.optString("description").orEmpty().trim()
                val songsArr = json.optJSONArray("songs")
                val tracks = buildList {
                    if (songsArr != null) {
                        for (i in 0 until songsArr.length()) {
                            val t = songsArr.optJSONObject(i) ?: continue
                            val tid = t.optLong("id", 0L).takeIf { it > 0 }?.toString() ?: continue
                            val title = t.optString("name").takeIf { it.isNotBlank() } ?: continue
                            val arArr = t.optJSONArray("ar")
                            val fee = t.optInt("fee", 0)
                            add(
                                MusicTrack(
                                    id = "netease:$tid",
                                    title = title,
                                    artist = parseJoinedNames(arArr, "name").ifEmpty { artName },
                                    album = resolvedName,
                                    coverURL = cover,
                                    duration = t.optDouble("dt", 0.0) / 1000.0,
                                    sourceId = MusicSourceKind.NETEASE.id,
                                    sourceTrackId = tid,
                                    qualityLabel = if (fee == 0 || fee == 8) "HQ 320k" else "320k / 自动换源",
                                    entitlementName = if (fee == 0 || fee == 8) MusicEntitlement.FULL.name else MusicEntitlement.PREVIEW.name,
                                    artistId = arArr?.optJSONObject(0)?.optLong("id", 0L)?.takeIf { it > 0 }?.toString() ?: artId,
                                    albumId = alId,
                                ),
                            )
                        }
                    }
                }
                if (tracks.isNotEmpty()) {
                    return@withContext MusicAlbumDetail(alId, resolvedName, artName, artId, MusicSourceKind.NETEASE.id, cover, relDate, company, desc, tracks)
                }
            }
        }
        val fallback = runCatching { searchNetEase("$name $artist", null) }.getOrDefault(emptyList())
        MusicAlbumDetail(albumId ?: name, name, artist, artistId, sourceId, coverHint.ifBlank { fallback.firstOrNull()?.coverURL.orEmpty() }, null, null, "", fallback)
    }

    suspend fun findAlternatives(
        track: MusicTrack,
        nasTracks: List<MusicTrack>,
        cookies: Map<MusicSourceKind, String>,
    ): List<MusicAlternative> = withContext(Dispatchers.IO) {
        val out = ArrayList<MusicAlternative>()
        bestMatch(track, nasTracks)?.let { m ->
            m.streamURL?.let { u ->
                out += MusicAlternative("alt-nas-${m.id}", m.source, m, "NAS 原声 · ${m.qualityLabel}", MusicEntitlement.FULL, u)
            }
        }
        val neHits = runCatching { searchNetEase("${track.title} ${track.artist}", cookies[MusicSourceKind.NETEASE]) }.getOrDefault(emptyList())
        bestMatch(track, neHits)?.let { m ->
            resolveNetEaseStream(m.sourceTrackId, cookies[MusicSourceKind.NETEASE])?.let { r ->
                out += MusicAlternative("alt-ne-${m.id}", MusicSourceKind.NETEASE, m, r.qualityLabel, r.entitlement, r.url, r.headers)
            }
        }
        val itHits = runCatching { searchITunes("${track.title} ${track.artist}") }.getOrDefault(emptyList())
        (bestMatch(track, itHits) ?: itHits.firstOrNull())?.let { m ->
            m.streamURL?.let { u ->
                out += MusicAlternative("alt-it-${m.id}", MusicSourceKind.ITUNES, m, "AAC 256k · 试听", MusicEntitlement.PREVIEW, u)
            }
        }
        out
    }

    // ── Lyrics (Qishui KRC / NetEase / QQ / Kugou + LRCLIB + NetEase Search Fallback) ────────────────

    suspend fun fetchLyrics(
        track: MusicTrack,
        lrclibEnabled: Boolean,
        cookies: Map<MusicSourceKind, String>,
    ): List<MusicLyricLine> = withContext(Dispatchers.IO) {
        if (track.source == MusicSourceKind.QISHUI) {
            val lrc = fetchQishuiSEOTrack(track.sourceTrackId, cookies[MusicSourceKind.QISHUI])?.second
            if (!lrc.isNullOrBlank()) {
                val parsed = MusicLyricsParser.parse(lrc)
                if (parsed.isNotEmpty()) return@withContext parsed
            }
        }
        if (track.source == MusicSourceKind.NETEASE) {
            val ne = fetchNetEaseLyrics(track.sourceTrackId, cookies[MusicSourceKind.NETEASE])
            if (ne.isNotEmpty()) return@withContext ne
        }
        if (track.source == MusicSourceKind.QQ) {
            val qq = fetchQQLyrics(track.sourceTrackId)
            if (qq.isNotEmpty()) return@withContext qq
        }
        if (track.source == MusicSourceKind.KUGOU) {
            val kg = fetchKugouLyrics(track.sourceTrackId, track.title, track.primaryArtistName, track.duration)
            if (kg.isNotEmpty()) return@withContext kg
        }
        if (lrclibEnabled) {
            val lrc = fetchLRCLib(track.title, track.artist)
            if (lrc.isNotEmpty()) return@withContext lrc
        }
        if (track.source != MusicSourceKind.NETEASE) {
            val hits = runCatching { searchNetEase("${track.title} ${track.artist}", cookies[MusicSourceKind.NETEASE]) }.getOrDefault(emptyList())
            val match = bestMatch(track, hits)
            if (match != null) {
                val ne = fetchNetEaseLyrics(match.sourceTrackId, cookies[MusicSourceKind.NETEASE])
                if (ne.isNotEmpty()) return@withContext ne
            }
        }
        emptyList()
    }

    private fun fetchQQLyrics(songMid: String): List<MusicLyricLine> {
        val mid = songMid.trim()
        if (mid.isEmpty()) return emptyList()
        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${enc(mid)}&nobase64=1&format=json&g_tk=5381"
        val json = getJson(url, mapOf("Referer" to "https://y.qq.com/", "User-Agent" to UA)) ?: return emptyList()
        val main = json.optString("lyric")
        if (main.isBlank()) return emptyList()
        val trans = json.optString("trans").takeIf { it.isNotBlank() }
        return MusicLyricsParser.parse(main, trans)
    }

    private fun fetchKugouLyrics(hash: String, title: String, artist: String, durationSec: Double): List<MusicLyricLine> {
        val cleanHash = hash.trim()
        val durMs = (durationSec * 1000.0).toLong().coerceAtLeast(1000L)
        val kw = enc("$artist-$title")
        val searchUrl = if (cleanHash.isNotEmpty()) {
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=${enc(cleanHash)}&duration=$durMs&keyword=$kw"
        } else {
            "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&duration=$durMs&keyword=$kw"
        }
        val sJson = getJson(searchUrl, mapOf("User-Agent" to UA)) ?: return emptyList()
        val cand = sJson.optJSONArray("candidates")?.optJSONObject(0) ?: return emptyList()
        val cid = cand.optString("id").trim()
        val key = cand.optString("accesskey").trim()
        if (cid.isEmpty() || key.isEmpty()) return emptyList()
        val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${enc(cid)}&accesskey=${enc(key)}&fmt=lrc&charset=utf8"
        val dJson = getJson(dlUrl, mapOf("User-Agent" to UA)) ?: return emptyList()
        val b64 = dJson.optString("content").trim()
        if (b64.isEmpty()) return emptyList()
        val lrc = runCatching {
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull().orEmpty()
        if (lrc.isBlank()) return emptyList()
        return MusicLyricsParser.parse(lrc)
    }

    private fun fetchNetEaseLyrics(songId: String, cookie: String?): List<MusicLyricLine> {
        val url = "https://music.163.com/api/song/lyric?id=$songId&lv=1&tv=1&rv=1"
        val hdrs = buildMap {
            put("Referer", "https://music.163.com/")
            put("User-Agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val json = getJson(url, hdrs) ?: return emptyList()
        val main = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
        if (main.isBlank()) return emptyList()
        val trans = json.optJSONObject("tlyric")?.optString("lyric")
        val roma = json.optJSONObject("romalrc")?.optString("lyric")
        return MusicLyricsParser.parse(main, trans, roma)
    }

    private fun fetchLRCLib(title: String, artist: String): List<MusicLyricLine> {
        val url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}"
        val json = getJson(url, mapOf("User-Agent" to "iDuanJu/1.6 (Android)")) ?: return emptyList()
        val synced = json.optString("syncedLyrics")
        if (synced.isBlank()) return emptyList()
        return MusicLyricsParser.parse(synced)
    }

    // ── Personal NAS Direct Clients (Navidrome / Emby / fnOS) ───────────────────────────────────────

    suspend fun fetchNASTracks(config: MusicNASConfig): List<MusicTrack> = withContext(Dispatchers.IO) {
        val base = config.serverURL.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext emptyList()
        runCatching {
            when (config.kind) {
                MusicSourceKind.NAVIDROME -> fetchNavidrome(base, config)
                MusicSourceKind.EMBY -> fetchEmby(base, config)
                MusicSourceKind.FNOS -> fetchFnos(base, config)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun fetchNavidrome(base: String, cfg: MusicNASConfig): List<MusicTrack> {
        val salt = "idj${(100000..999999).random()}"
        val token = md5Hex(cfg.passwordOrToken + salt)
        val auth = "u=${enc(cfg.username)}&t=$token&s=$salt&v=1.16.1&c=iDuanJu&f=json"
        val json = getJson("$base/rest/getRandomSongs?$auth&size=60", emptyMap()) ?: return emptyList()
        val songs = json.optJSONObject("subsonic-response")?.optJSONObject("randomSongs")?.optJSONArray("song") ?: return emptyList()
        return buildList {
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                val id = s.optString("id").takeIf { it.isNotBlank() } ?: continue
                val coverId = s.optString("coverArt")
                val cover = if (coverId.isNotBlank()) "$base/rest/getCoverArt?$auth&id=${enc(coverId)}&size=500" else ""
                val stream = "$base/rest/stream?$auth&id=${enc(id)}"
                val suffix = s.optString("suffix").uppercase(Locale.ROOT).ifBlank { "LOSSLESS" }
                add(
                    MusicTrack(
                        id = "navidrome:${cfg.id}:$id",
                        title = s.optString("title").ifBlank { "未知曲目" },
                        artist = s.optString("artist").ifBlank { "未知艺人" },
                        album = s.optString("album"),
                        coverURL = cover,
                        duration = s.optDouble("duration", 0.0),
                        sourceId = MusicSourceKind.NAVIDROME.id,
                        sourceTrackId = id,
                        streamURL = stream,
                        qualityLabel = suffix,
                        entitlementName = MusicEntitlement.FULL.name,
                        extraMeta = mapOf("sourceName" to cfg.name),
                    ),
                )
            }
        }
    }

    private fun fetchEmby(base: String, cfg: MusicNASConfig): List<MusicTrack> {
        val authHdr = "MediaBrowser Client=\"iDuanJu\", Device=\"Android\", DeviceId=\"idj-android\", Version=\"1.6\""
        val body = JSONObject().put("Username", cfg.username).put("Pw", cfg.passwordOrToken).toString()
        val login = postJson("$base/Users/AuthenticateByName", body, mapOf("X-Emby-Authorization" to authHdr))
        val accessToken = login?.optString("AccessToken").orEmpty().ifBlank { cfg.passwordOrToken }
        val userId = login?.optJSONObject("User")?.optString("Id").orEmpty()
        val url = if (userId.isNotBlank()) {
            "$base/Users/$userId/Items?IncludeItemTypes=Audio&Recursive=true&Limit=60&api_key=${enc(accessToken)}"
        } else {
            "$base/Items?IncludeItemTypes=Audio&Recursive=true&Limit=60&api_key=${enc(accessToken)}"
        }
        val json = getJson(url, mapOf("X-Emby-Token" to accessToken)) ?: return emptyList()
        val items = json.optJSONArray("Items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val id = it.optString("Id").takeIf { s -> s.isNotBlank() } ?: continue
                val cover = "$base/Items/$id/Images/Primary?maxWidth=500&api_key=${enc(accessToken)}"
                val stream = "$base/Audio/$id/stream?static=true&api_key=${enc(accessToken)}"
                val dur = it.optLong("RunTimeTicks", 0L).toDouble() / 10_000_000.0
                add(
                    MusicTrack(
                        id = "emby:${cfg.id}:$id",
                        title = it.optString("Name").ifBlank { "未知曲目" },
                        artist = it.optString("AlbumArtist").ifBlank { "未知艺人" },
                        album = it.optString("Album"),
                        coverURL = cover,
                        duration = dur,
                        sourceId = MusicSourceKind.EMBY.id,
                        sourceTrackId = id,
                        streamURL = stream,
                        qualityLabel = "NAS 原流",
                        entitlementName = MusicEntitlement.FULL.name,
                        extraMeta = mapOf("sourceName" to cfg.name),
                    ),
                )
            }
        }
    }

    private fun fetchFnos(base: String, cfg: MusicNASConfig): List<MusicTrack> {
        val hdrs = buildMap {
            put("User-Agent", UA)
            if (cfg.passwordOrToken.isNotBlank()) put("Authorization", "Bearer ${cfg.passwordOrToken}")
        }
        val json = getJson("$base/api/music/tracks?limit=60", hdrs) ?: return emptyList()
        val arr = json.optJSONArray("data") ?: json.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val id = it.optString("id").takeIf { s -> s.isNotBlank() } ?: continue
                val stream = it.optString("url").ifBlank { "$base/api/music/stream/$id" }
                add(
                    MusicTrack(
                        id = "fnos:${cfg.id}:$id",
                        title = it.optString("title").ifBlank { "未知曲目" },
                        artist = it.optString("artist").ifBlank { "未知艺人" },
                        album = it.optString("album"),
                        coverURL = it.optString("cover"),
                        duration = it.optDouble("duration", 0.0),
                        sourceId = MusicSourceKind.FNOS.id,
                        sourceTrackId = id,
                        streamURL = stream,
                        qualityLabel = it.optString("format").ifBlank { "FLAC" },
                        entitlementName = MusicEntitlement.FULL.name,
                        extraMeta = mapOf("sourceName" to cfg.name),
                    ),
                )
            }
        }
    }

    // ── Matching & HTTP Utilities ───────────────────────────────────────────────────────────────────

    fun bestMatch(target: MusicTrack, candidates: List<MusicTrack>): MusicTrack? {
        val tNorm = normalize(target.title)
        val aNorm = normalize(target.artist)
        var best: MusicTrack? = null
        var bestScore = 0
        for (c in candidates) {
            val ct = normalize(c.title)
            val ca = normalize(c.artist)
            var score = 0
            if (ct == tNorm) score += 60 else if (ct.contains(tNorm) || tNorm.contains(ct)) score += 30
            if (ca == aNorm) score += 40 else if (ca.contains(aNorm) || aNorm.contains(ca)) score += 20
            if (ct.contains("live") && !tNorm.contains("live")) score -= 12
            if (ct.contains("翻唱") && !tNorm.contains("翻唱")) score -= 25
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return if (bestScore >= 45) best else null
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("""[（(].*?[）)]"""), "")
            .replace(Regex("""[\s\-_·.,，。'"`]+"""), "")

    private fun getJson(url: String, headers: Map<String, String>): JSONObject? {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        return runCatching {
            client.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) null else JSONObject(body)
            }
        }.getOrNull()
    }

    private fun postJson(url: String, jsonBody: String, headers: Map<String, String>): JSONObject? {
        val b = Request.Builder().url(url)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        return runCatching {
            client.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) null else JSONObject(body)
            }
        }.getOrNull()
    }

    private fun parseJoinedNames(arr: JSONArray?, key: String): String {
        if (arr == null) return ""
        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString(key)?.takeIf { it.isNotBlank() }?.let { names += it }
        }
        return names.joinToString(" / ")
    }

    private fun secureHttps(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
