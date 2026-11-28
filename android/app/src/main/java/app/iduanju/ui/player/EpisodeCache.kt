package app.iduanju.ui.player

import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGEpisode
import app.iduanju.core.hongguo.HGResolvedVideo
import app.iduanju.core.hongguo.HGStream
import app.iduanju.core.hongguo.HGStreamKind
import app.iduanju.core.hongguo.HGSubtitle
import app.iduanju.core.tvbox.TVBoxId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A ready-to-open episode: a decrypted local file, a decrypting stream (`idjcenc://`), a local playlist or a direct
 * address. [kind] null = let the player infer it.
 */
data class Playable(
    val uri: String, val quality: String?, val codec: String, val encrypted: Boolean, val hls: Boolean = false,
    val headers: Map<String, String> = emptyMap(), val note: String? = null,
    val kind: MediaKind? = null, val subtitles: List<HGSubtitle> = emptyList(), val live: Boolean = false,
    /** [kind] came from the response, not from the address. */
    val sniffed: Boolean = false,
    /** May lead into the local network. */
    val lan: Boolean = true,
)

/**
 * Resolving, opening and warming up episodes.
 *
 * - [resolve] keeps the provider's answer for a few minutes (signed addresses expire), so 画质 / 线路 switches and the
 *   next episode start without another round trip;
 * - [open] turns one stream into a [Playable]: ad-stripped local playlist for VOD HLS, decrypt-while-downloading for
 *   CENC, the address itself otherwise;
 * - [warm] prepares the next episode (resolve + first bytes), [preload] downloads an encrypted one completely.
 */
object EpisodeCache {
    private const val RESOLVE_TTL_MS = 3 * 60_000L
    private const val WARM_BYTES = 1_500_000L
    private const val CENC_WARM_BYTES = 2_000_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Stamped<T>(val value: T, val at: Long = System.currentTimeMillis()) {
        fun fresh(ttl: Long) = System.currentTimeMillis() - at < ttl
    }
    private val resolved = ConcurrentHashMap<String, Stamped<HGResolvedVideo>>()
    private class Resolving(val vid: String, val task: Deferred<HGResolvedVideo>)
    private val resolving = ConcurrentHashMap<String, Resolving>()
    private val inflight = ConcurrentHashMap<String, Deferred<Playable>>()
    private val ready = ConcurrentHashMap<String, Stamped<Playable>>()
    /** Decrypted episodes on disk by vid, so a re-watch needs no network at all. */
    private val disk = ConcurrentHashMap<String, File>()
    @Volatile private var indexed = false
    private val firstFrames = android.util.LruCache<String, android.graphics.Bitmap>(6)
    private val done = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** vids whose encrypted file is now complete on disk (seek thumbnails can be read from it). */
    val completed: SharedFlow<String> get() = done

    /** Where the app's own playlists and decrypted episodes live. */
    val folder: String by lazy { dir().canonicalPath + File.separator }
    private fun dir(): File = File(IdjApp.instance.cacheDir, "hg-play").apply { mkdirs() }
    /** TVBox ids may be whole addresses: long ones keep a readable head and a hash, so names stay short and distinct. */
    private fun safeName(vid: String) = vid.replace(Regex("[^A-Za-z0-9_-]"), "_").let { if (it.length <= 80) it else it.take(47) + "-" + md5(vid) }
    private fun safePart(s: String?) = s.orEmpty().replace(Regex("[^A-Za-z0-9_-]"), "").take(24).ifEmpty { "q" }
    internal fun md5(s: String): String = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun key(ep: HGEpisode) = ep.vid + "|" + (ep.media?.hashCode() ?: 0)

    // ── lookup ──────────────────────────────────────────────────────────────────────────────────────────

    /** Off the main thread, once: what earlier sessions left on disk. */
    fun index() {
        if (indexed) return
        indexed = true
        val now = System.currentTimeMillis()
        dir().listFiles()?.forEach { f ->
            when {
                f.name.endsWith(".part") && now - f.lastModified() > 10 * 60_000L -> f.delete()
                f.name.endsWith(".mp4") && f.length() > 0 -> disk[f.name.substringBefore('.')] = f
                f.name.endsWith(".m3u8") && now - f.lastModified() > 24 * 3600_000L -> f.delete()
            }
        }
    }

    /** Zero-latency lookup: a complete local file, or something opened a moment ago. */
    fun peek(vid: String): Playable? {
        ready[vid]?.let { hit ->
            val p = hit.value
            if (p.uri.startsWith("/") && !p.hls) {
                if (File(p.uri).length() > 0) return p
                ready.remove(vid)
            } else if (p.uri.startsWith("$CENC_SCHEME://")) {
                val id = p.uri.removePrefix("$CENC_SCHEME://")
                val cf = CencStore.get(id)
                when {
                    cf == null || cf.isClosed -> ready.remove(vid)
                    cf.complete && cf.target.length() > 0 -> {
                        val local = Playable(cf.target.absolutePath, p.quality, p.codec, true, subtitles = p.subtitles)
                        ready[vid] = Stamped(local)
                        return local
                    }
                    cf.isFailed || !hit.fresh(RESOLVE_TTL_MS) -> {
                        ready.remove(vid)
                        CencStore.remove(id)
                    }
                    else -> return p
                }
            } else if (hit.fresh(RESOLVE_TTL_MS)) return p else ready.remove(vid)
        }
        val f = disk[safeName(vid)] ?: return null
        if (f.length() <= 0) { disk.remove(safeName(vid)); return null }
        val parts = f.name.removeSuffix(".mp4").split('.')
        return Playable(f.absolutePath, parts.getOrNull(1)?.takeIf { it != "q" }, parts.getOrNull(2).orEmpty(), true)
    }

    /** The streams of an episode that was already resolved and is still valid. */
    fun peekResolved(ep: HGEpisode): HGResolvedVideo? = resolved[key(ep)]?.takeIf { it.fresh(RESOLVE_TTL_MS) }?.value

    /** Decoded first video frame, for instant swipe-back and next-episode previews. */
    fun peekFirstFrame(vid: String): android.graphics.Bitmap? = synchronized(firstFrames) { firstFrames.get(vid) }

    /** Extract and cache frame 0 from a local video file off the main thread. */
    fun cacheFirstFrame(vid: String, localPath: String) {
        if (!localPath.startsWith("/")) return
        synchronized(firstFrames) { if (firstFrames.get(vid) != null) return }
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(localPath)
            val bmp = if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 1280)
                else r.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bmp != null) synchronized(firstFrames) { firstFrames.put(vid, bmp) }
        } catch (_: Throwable) {
        } finally {
            runCatching { r.release() }
        }
    }

    // ── resolve / open ──────────────────────────────────────────────────────────────────────────────────

    fun resolve(ep: HGEpisode, force: Boolean = false): Deferred<HGResolvedVideo> = synchronized(resolving) {
        val k = key(ep)
        if (force) {
            resolved.remove(k)
            resolving.remove(k)?.task?.cancel()
        } else {
            peekResolved(ep)?.let { return@synchronized CompletableDeferred(it) }
        }
        resolving[k]?.task?.takeIf { it.isActive } ?: scope.async(start = CoroutineStart.LAZY) {
            val r = if (TVBoxId.isTVBox(ep.vid)) runInterruptible { app.iduanju.data.Catalog.resolve(ep, force) } else app.iduanju.data.Catalog.resolve(ep, force)
            r.also { if (it.streams.isNotEmpty()) resolved[k] = Stamped(it) }
        }.also { task ->
            val entry = Resolving(ep.vid, task)
            resolving[k] = entry
            task.invokeOnCompletion { resolving.remove(k, entry) }
            task.start()
        }
    }

    /** TVBox resolving occupies the site's one thread or a hidden page: what nobody is going to play any more ends at once. */
    fun abandon(keep: String?) { resolving.values.filter { it.vid != keep && TVBoxId.isTVBox(it.vid) }.forEach { it.task.cancel() } }

    /** [live] streams are always played from their own address: a rewritten playlist would be a snapshot. */
    suspend fun open(vid: String, s: HGStream, live: Boolean = false, forceSniff: Boolean = false, onStatus: (String) -> Unit = {}): Playable = withContext(Dispatchers.IO) {
        if (s.key.isNotEmpty()) return@withContext openCenc(vid, s)
        var kind = if (forceSniff) null else MediaKinds.guess(s.url, s.format, s.kind)
        val sniffed = kind == null
        if (kind == null) kind = try { runInterruptible { MediaKinds.sniff(s.url, s.headers, s.lan, if (live) 3_000 else 4_000) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            android.util.Log.i("IDJ", "sniff failed: ${e.javaClass.simpleName}"); MediaKind.Progressive
        }
        if (kind == MediaKind.Hls && !live) {
            onStatus("解析播放列表…")
            localPlaylist(vid, s)?.let { return@withContext it.copy(sniffed = sniffed) }
        }
        Playable(s.url, s.quality, s.codec, false, hls = kind == MediaKind.Hls, headers = s.headers, kind = kind, subtitles = s.subtitles, live = live, sniffed = sniffed, lan = s.lan)
    }

    /**
     * iOS serves the ad-stripped playlist from its loopback proxy; here it is written to a local file. Null when there
     * is nothing to strip or the playlist must stay as it is (live, separate audio renditions): play the address itself.
     */
    private fun localPlaylist(vid: String, s: HGStream): Playable? {
        if (!app.iduanju.core.hls.HLSAdFilter.enabled) return null
        val headers = PlayerHttp.safe(s.headers)
        var renditions = false
        val prep = try {
            app.iduanju.core.hls.HLSLocal.prepare(s.url) { u ->
                PlayerHttp.quick(s.lan, 5_000).newCall(Request.Builder().url(u).apply { headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("播放列表 HTTP ${resp.code}")
                    PlayerHttp.playlist(resp).also { if (Regex("#EXT-X-MEDIA:[^\\n]*TYPE=AUDIO[^\\n]*URI=").containsMatchIn(it)) renditions = true }
                }
            }
        } catch (e: Exception) { android.util.Log.i("IDJ", "playlist prepare failed: ${e.message}"); return null }
        val text = prep.text
        if (renditions || !text.trimStart('﻿').startsWith("#EXTM3U")) return null
        if ("#EXT-X-ENDLIST" !in text && "#EXT-X-PLAYLIST-TYPE:VOD" !in text) return null
        val f = File(dir(), safeName(vid) + "-" + md5(s.url).take(8) + ".m3u8")
        f.writeText(text)
        val note = if (prep.removed > 0) "已去广告 ${prep.removed} 段 · ${prep.removedSeconds.toInt()}s" else null
        return Playable(f.absolutePath, s.quality, s.codec, false, hls = true, headers = s.headers, note = note, kind = MediaKind.Hls, subtitles = s.subtitles, lan = s.lan)
    }

    private fun openCenc(vid: String, s: HGStream): Playable {
        val f = File(dir(), "${safeName(vid)}.${safePart(s.quality)}.${safePart(s.codec)}.mp4")
        if (f.length() > 0) return Playable(f.absolutePath, s.quality, s.codec, true, subtitles = s.subtitles)
        val id = md5(f.name)
        val urls = listOfNotNull(s.url, s.backupURL?.takeIf { it.isNotBlank() && it != s.url })
        CencStore.open(id, urls) {
            CencFile(id, urls, mapOf("User-Agent" to "com.phoenix.read/73932"), s.key, f) { file ->
                disk[safeName(vid)] = file
                ready[vid] = Stamped(Playable(file.absolutePath, s.quality, s.codec, true, subtitles = s.subtitles))
                trim(file)
                scope.launch { cacheFirstFrame(vid, file.absolutePath) }
                done.tryEmit(vid)
            }
        }
        return Playable("$CENC_SCHEME://$id", s.quality, s.codec, true, kind = MediaKind.Progressive, subtitles = s.subtitles)
    }

    /** Remembers what the player opened, so coming back to the episode is instant. */
    fun remember(vid: String, p: Playable) { if (!p.live) ready[vid] = Stamped(p) }
    fun forget(ep: HGEpisode) {
        val prev = ready.remove(ep.vid)?.value
        if (prev != null && prev.uri.startsWith("$CENC_SCHEME://")) {
            CencStore.remove(prev.uri.removePrefix("$CENC_SCHEME://"))
        }
        resolved.remove(key(ep))
        synchronized(inflight) { inflight.remove(ep.vid)?.cancel() }
        synchronized(resolving) { resolving.remove(key(ep))?.task?.cancel() }
    }

    /** A cached file that does not play: download it again next time. */
    fun discard(ep: HGEpisode, path: String) {
        forget(ep)
        disk.remove(safeName(ep.vid))
        scope.launch { runCatching { File(path).takeIf { it.parentFile == dir() }?.delete() } }
    }

    // ── warm-up ─────────────────────────────────────────────────────────────────────────────────────────

    /** Resolve + best stream + open, shared by everyone asking for the same episode. */
    fun get(ep: HGEpisode, prefer: String? = null): Deferred<Playable> = synchronized(inflight) {
        peek(ep.vid)?.takeIf { prefer == null || it.quality == prefer }?.let { return@synchronized CompletableDeferred(it) }
        inflight[ep.vid]?.takeIf { it.isActive } ?: scope.async(start = CoroutineStart.LAZY) {
            val r = resolve(ep).await()
            val s = DecoderCaps.pick(r.streams, prefer) ?: throw Exception(r.err ?: "无可用流")
            open(ep.vid, s).also { p ->
                remember(ep.vid, p)
                if (p.uri.startsWith("$CENC_SCHEME://")) CencStore.get(p.uri.removePrefix("$CENC_SCHEME://"))?.prefetch(CENC_WARM_BYTES)
                else if (p.uri.startsWith("/") && !p.hls) scope.launch { cacheFirstFrame(ep.vid, p.uri) }
            }
        }.also { task ->
            inflight[ep.vid] = task
            task.invokeOnCompletion { inflight.remove(ep.vid, task) }
            task.start()
        }
    }

    /** Next-episode warm-up: resolved, opened, and its first bytes local. Cancelling stops the byte prefetch. */
    suspend fun warm(ep: HGEpisode, prefer: String? = null) {
        val p = try { get(ep, prefer).await() } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        val ctx = IdjApp.instance
        when {
            p.live || p.uri.startsWith("$CENC_SCHEME://") -> Unit
            p.hls && p.uri.startsWith("/") -> firstSegments(p.uri).forEach { MediaHeadCache.warm(ctx, it, p.headers, 0L) }
            p.kind == MediaKind.Progressive -> MediaHeadCache.warm(ctx, p.uri, p.headers, WARM_BYTES)
        }
    }

    /** [warm], then the whole file for encrypted episodes (short dramas are small; the swipe preview needs a frame). */
    suspend fun preload(ep: HGEpisode, prefer: String? = null) {
        warm(ep, prefer)
        val p = peek(ep.vid) ?: return
        if (p.uri.startsWith("$CENC_SCHEME://")) CencStore.get(p.uri.removePrefix("$CENC_SCHEME://"))?.prefetch(Long.MAX_VALUE)
    }

    /** init segment + the first media segment of a local playlist. */
    private fun firstSegments(path: String): List<String> = runCatching {
        val out = ArrayList<String>(3)
        File(path).useLines { lines ->
            for (l in lines) {
                val t = l.trim()
                if (t.startsWith("#EXT-X-MAP:") || t.startsWith("#EXT-X-KEY:")) Regex("URI=\"([^\"]+)\"").find(t)?.let { out.add(it.groupValues[1]) }
                else if (t.startsWith("#EXT-X-BYTERANGE")) return@useLines
                else if (t.isNotEmpty() && !t.startsWith("#")) { out.add(t); return@useLines }
            }
        }
        out.filter { it.startsWith("http", true) }
    }.getOrDefault(emptyList())

    /** Preserve the file returned to the player even when it alone exceeds the budget. */
    private fun trim(keep: File) {
        val files = dir().listFiles()?.filter { it != keep && !it.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() } + keep.length()
        val budget = IdjApp.instance.settings.playbackCacheMB.toLong() shl 20
        for (f in files) {
            if (total < budget) break
            total -= f.length()
            if (f.delete()) disk.remove(f.name.substringBefore('.'), f)
        }
    }
}
