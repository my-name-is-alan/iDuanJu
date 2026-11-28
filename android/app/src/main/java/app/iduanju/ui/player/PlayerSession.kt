@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.iduanju.ui.player

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import app.iduanju.BuildConfig
import app.iduanju.IdjApp
import app.iduanju.core.hongguo.HGEpisode
import app.iduanju.core.hongguo.HGPlayLine
import app.iduanju.core.hongguo.HGSeriesDetail
import app.iduanju.core.hongguo.HGStream
import app.iduanju.core.tvbox.Programme
import app.iduanju.data.WatchProgress
import app.iduanju.ui.UiEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A resolved video queue whose owner manages chapter loading and the player lifetime. */
class VideoQueueSession(val player: ExoPlayer, val detail: HGSeriesDetail, val index: Int,
    val loading: Boolean, val failure: String?, val select: (Int) -> Unit)

/** High-frequency playback state. Read it in lambdas / draw blocks only, never in a screen's composition. */
@Stable
class PlaybackClock {
    var pos by mutableLongStateOf(0L)
        internal set
    var dur by mutableLongStateOf(0L)
        internal set
    var buffered by mutableLongStateOf(0L)
        internal set

    internal fun reset() { pos = 0L; dur = 0L; buffered = 0L }
}

/** One selectable audio / subtitle track. */
@Stable
class TrackChoice(val label: String, val detail: String, val selected: Boolean, internal val group: Tracks.Group, internal val index: Int)

/**
 * Everything the one player knows, for every source: short drama, long video, TVBox VOD, TVBox live, novel video
 * books. It owns loading (resolve → open → first frame), lines and qualities, the first-frame watchdog with automatic
 * switching, progress and the sleep timer; the composables only read it and call it. Main thread only.
 */
@Stable
class PlayerSession internal constructor(
    private val ctx: Context,
    val seriesId: String,
    private val startEp: Int,
    queue: VideoQueueSession?,
    private val scope: CoroutineScope,
) {
    private val lib = IdjApp.instance.library
    val fixture = BuildConfig.DEBUG && seriesId == "ui-color-bars"
    /** Chapters of a video book: the owner loads them and keeps the player. */
    val queued = queue != null
    private var queue: VideoQueueSession? = queue
    val player: ExoPlayer = queue?.player ?: PlayerFactory.create(ctx).also { it.playWhenReady = true }
    val clock = PlaybackClock()
    val seekExtractor = SeekPreviewExtractor()

    // ── what is playing ─────────────────────────────────────────────────────────────────────────────────

    var detail by mutableStateOf<HGSeriesDetail?>(null)
        private set
    var epIndex by mutableIntStateOf(-1)
        private set
    private var lineIndex by mutableIntStateOf(0)
    val lines: List<HGPlayLine> get() = detail?.lines.orEmpty()
    val multiLine: Boolean get() = lines.size > 1
    val line: HGPlayLine? get() = lines.getOrNull(lineIndex)
    val currentLineIndex: Int get() = lineIndex
    /** Episodes of the current line (the default line's when the title has one). */
    val episodes: List<HGEpisode> get() = line?.episodes?.takeIf { it.isNotEmpty() } ?: detail?.episodes.orEmpty()
    val episode: HGEpisode? get() = episodes.getOrNull(epIndex)
    /** A live channel list: episodes are channels, navigation wraps around, nothing is resumed. */
    val channelList: Boolean get() = detail?.isLive == true
    /** 回看: the finished programme being played instead of the live signal. */
    var replay by mutableStateOf<Programme?>(null)
        private set
    /** Playing the live signal: no time line, speed or seeking. */
    val live: Boolean get() = channelList && replay == null
    val hasPrev: Boolean get() = if (channelList) episodes.size > 1 else epIndex > 0
    val hasNext: Boolean get() = if (channelList) episodes.size > 1 else epIndex >= 0 && epIndex + 1 < episodes.size
    /** Official 红果 comments, or the address a TVBox source delivered with the stream. */
    var danmakuUrl by mutableStateOf<String?>(null)
        private set
    val danmakuCapable: Boolean get() = seriesId.all { it.isDigit() } || danmakuUrl != null || PluginDanmaku.capable(seriesId)

    var streams by mutableStateOf<List<HGStream>>(emptyList())
        private set
    var stream by mutableStateOf<HGStream?>(null)
        private set
    var quality by mutableStateOf<String?>(null)
        private set
    private var playable: Playable? = null

    // ── state shown by the chrome ───────────────────────────────────────────────────────────────────────

    /** Text while something is prepared ("取流中…"); empty = media handed to the player. */
    var status by mutableStateOf("加载中…")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** The error card offers another line / quality / source. */
    var canSwitchSource by mutableStateOf(false)
        private set
    /** Short centred hint ("+10s", "已锁定屏幕", automatic-switch notices); cleared by the screen. */
    var hint by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var firstFrame by mutableStateOf(false)
        private set
    var tags by mutableStateOf(TechTags())
        private set
    var videoAspect by mutableFloatStateOf(0f)
        private set
    val nativeHdr: Boolean by derivedStateOf { tags.range != null && tags.range != "SDR" }
    /** First-frame picture carried over a swipe / auto-next until the new episode renders. */
    var handoff by mutableStateOf<android.graphics.Bitmap?>(null)
    /** Hides the previous episode's last frame while the next one loads. */
    var shade by mutableStateOf(false)
        private set
    var seekTick by mutableIntStateOf(0)
        private set
    var speed by mutableFloatStateOf(PlayerPrefs.getSpeed(ctx))
        private set
    var holding by mutableStateOf(false)
        private set
    var holdSpeed by mutableFloatStateOf(2f)
        private set
    var holdLocked by mutableStateOf(false)
        private set
    var muted by mutableStateOf(false)
        private set
    var autoNext by mutableStateOf(PlayerPrefs.getAutoNext(ctx))
        private set
    /** 0 = off, -1 = after the current episode, > 0 = minutes. */
    var sleepMode by mutableIntStateOf(0)
        private set
    var sleepEndMs by mutableLongStateOf(0L)
        private set
    var audioTracks by mutableStateOf<List<TrackChoice>>(emptyList())
        private set
    var textTracks by mutableStateOf<List<TrackChoice>>(emptyList())
        private set
    /** Set when the final episode ends (播完连刷推荐). */
    var ended by mutableStateOf(false)
        internal set

    /** The video-effect pipeline failed: the screen turns enhancement off and returns true to resume in place. */
    internal var onEffectFailure: () -> Boolean = { false }
    /** Installs the GPU effects this media needs, right before it is prepared. */
    internal var beforePrepare: () -> Unit = {}
    /** False while the video has no surface (screen off, app in the background): no first frame can be rendered then. */
    internal var hasSurface: () -> Boolean = { true }
    /** False when the Activity is stopped (screen locked / backgrounded) and NOT in PiP: prevents ghost audio after resolve finishes. */
    internal var inForeground: () -> Boolean = { true }
    /** The last episode ended and nothing follows. */
    internal var onFinalEnded: () -> Unit = {}
    /** The centre transport stays hidden after an episode change until the user taps. */
    var centerSuppressed by mutableStateOf(false)

    private var loadJob: Job? = null
    private var watchJob: Job? = null
    private var stallJob: Job? = null
    private var prefetchJob: Job? = null
    private var streamsJob: Job? = null
    private var pollJob: Job? = null
    private var completedJob: Job? = null
    private var mediaReady = false
    private var loadedAt = 0L
    /** When this stream began to be opened: the first-frame limit counts from here. */
    private var openedAt = 0L
    /** The address was resolved earlier (next-episode warm-up, the last few minutes) and may have expired since. */
    private var reused = false
    /** Where this load started, so a switch before the first frame keeps the position. */
    private var startedAt = 0L
    private val watchedMs = longArrayOf(0L)

    // Automatic switching, per episode.
    private var autoSwitches = 0
    private val triedStreams = HashSet<String>()
    private val triedLines = HashSet<Int>()
    private var refreshed = false
    private var resniffed = false
    private var liveRetries = 0
    /** Uninterrupted playback since the last load or stall. */
    private var steadyMs = 0L
    /** Where a stream broke off; stopping there again means its announced length is wrong. */
    private var cutAt = -1L
    private var renderedCount = 0L
    private var stillMs = 0L

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (buffering) steadyMs = 0L
            if (state == Player.STATE_READY) {
                error = null
                stallJob?.cancel()
                // Radio channels and audio-only files never render a frame.
                if (player.currentTracks.groups.isNotEmpty() && !player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) frameShown()
            }
            if (state == Player.STATE_BUFFERING && firstFrame && mediaReady) watchStall()
            if (state == Player.STATE_ENDED && mediaReady) onEnded()
            else if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) ended = false
        }
        override fun onPlayerError(e: PlaybackException) = onFailed(e)
        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        override fun onTracksChanged(tracks: Tracks) {
            tags = TechTags.from(player, quality)
            audioTracks = choices(tracks, C.TRACK_TYPE_AUDIO)
            textTracks = choices(tracks, C.TRACK_TYPE_TEXT)
        }
        // videoFormat / audioFormat are populated once the renderers start, after onTracksChanged
        override fun onRenderedFirstFrame() = frameShown()
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) seekTick++
        }
        override fun onVideoSizeChanged(size: VideoSize) {
            tags = TechTags.from(player, quality)
            videoAspect = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height.toFloat() else 0f
        }
    }

    internal fun start() {
        current = this
        app.iduanju.music.AppAudioArbiter.videoHooks = object : app.iduanju.music.AppAudioArbiter.VideoHooks {
            override fun pauseForMusicTakeover() {
                player.playWhenReady = false
            }
        }
        app.iduanju.music.AppAudioArbiter.claim(app.iduanju.music.AppAudioArbiter.Owner.VIDEO)
        player.addListener(listener)
        if (!queued) loadDetail()
        pollJob = scope.launch { poll() }
        scope.launch(Dispatchers.IO) { EpisodeCache.index() }
        completedJob = scope.launch {
            EpisodeCache.completed.collect { vid ->
                if (vid == episode?.vid && mediaReady) EpisodeCache.peek(vid)?.takeIf { it.uri.startsWith("/") }?.let { seekExtractor.setSource(it.uri, it.headers, it.hls) }
            }
        }
    }

    internal fun release() {
        recordProgress(force = true)
        listOf(loadJob, watchJob, stallJob, prefetchJob, streamsJob, pollJob, completedJob).forEach { it?.cancel() }
        player.removeListener(listener)
        seekExtractor.release()
        // the next player may be loading already
        if (current === this) {
            current = null
            CencStore.focus(null)
            EpisodeCache.abandon(null)
            app.iduanju.music.AppAudioArbiter.release(app.iduanju.music.AppAudioArbiter.Owner.VIDEO)
        }
        if (!queued) player.release()
    }

    /** The queue owner published a new chapter / state. */
    internal fun onQueue(q: VideoQueueSession) {
        queue = q
        val changed = epIndex != q.index
        detail = q.detail
        epIndex = q.index
        if (changed) { firstFrame = false; clock.reset() }
        error = q.failure
        canSwitchSource = false
        status = if (q.loading) "正在打开章节…" else ""
        mediaReady = !q.loading && q.failure == null
    }

    // ── detail ──────────────────────────────────────────────────────────────────────────────────────────

    private fun loadDetail() {
        error = null; status = "加载中…"
        loadJob?.cancel()
        loadJob = scope.launch {
            if (fixture) {
                detail = HGSeriesDetail(seriesId = seriesId, title = "本地彩条测试 · 非真实片源", totalEps = 48, source = "本地测试",
                    episodes = (1..48).map { HGEpisode(it, "fixture-$it", 60, "彩条第 $it 段") })
                moveTo(startEp.coerceIn(0, 47))
                return@launch
            }
            try {
                // 播完连刷推荐 may have warmed this drama's detail while the previous one was on its last episodes.
                val d = PlayerNextDrama.takeDetail(seriesId) ?: app.iduanju.data.Catalog.load(seriesId)
                if (d.episodes.isEmpty()) throw IllegalStateException("empty")
                detail = d
                lineIndex = lib.preferredLine(seriesId)?.let { id -> d.lines.indexOfFirst { it.id == id && it.episodes.isNotEmpty() } }?.takeIf { it >= 0 } ?: 0
                val at = if (d.isLive) app.iduanju.data.Catalog.channel(seriesId, startEp).find(episodes) else episodes.indexOfFirst { it.ep == startEp }
                moveTo(at.takeIf { it >= 0 } ?: 0)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                android.util.Log.w("IDJ", "Player detail load failed", e)
                status = ""; canSwitchSource = false
                error = (e as? app.iduanju.core.util.ProviderException)?.message?.takeIf { it.isNotBlank() } ?: "无法加载节目详情"
            }
        }
    }

    // ── navigation ──────────────────────────────────────────────────────────────────────────────────────

    fun select(index: Int) {
        val q = queue
        if (q != null) { q.select(index); return }
        if (index == epIndex && replay != null) { backToLive(); return }
        if (index == epIndex && error == null && mediaReady) return
        if (index !in episodes.indices) return
        recordProgress(force = true)
        moveTo(index)
    }

    /** Live lists wrap around. */
    fun neighbour(step: Int): Int? {
        val n = episodes.size
        if (n == 0 || epIndex < 0) return null
        val t = epIndex + step
        return if (channelList) ((t % n) + n) % n else t.takeIf { it in 0 until n }
    }

    fun next() { neighbour(1)?.let { select(it) } }
    fun prev() { neighbour(-1)?.let { select(it) } }

    private fun moveTo(index: Int) {
        if (epIndex >= 0 && epIndex != index) centerSuppressed = true
        val switching = epIndex >= 0
        epIndex = index
        watchedMs[0] = 0L
        resetAttempts()
        cutAt = -1L
        streams = emptyList(); stream = null; danmakuUrl = null
        ended = false
        replay = null
        load(positionMs = null, switching = switching)
    }

    // ── 回看 ────────────────────────────────────────────────────────────────────────────────────────────

    /** Programmes of the current channel on the day of [at]. */
    suspend fun programmes(at: Long): List<Programme> {
        val ep = episode?.takeIf { channelList } ?: return emptyList()
        return IdjApp.instance.tvbox.guide(ep, at)
    }

    fun canReplay(p: Programme): Boolean = episode?.takeIf { channelList }?.let { IdjApp.instance.tvbox.catchup(it, p) } != null

    fun playReplay(p: Programme) {
        if (!channelList || !canReplay(p)) { hint = "这个频道不支持回看"; return }
        replay = p
        resetAttempts()
        streams = emptyList(); stream = null
        hint = "回看：${p.title}"
        load(positionMs = null, switching = true)
    }

    fun backToLive() {
        if (replay == null) return
        replay = null
        resetAttempts()
        streams = emptyList(); stream = null
        hint = "已返回直播"
        load(positionMs = null, switching = true)
    }

    private fun resetAttempts() {
        autoSwitches = 0; triedStreams.clear(); triedLines.clear(); refreshed = false; resniffed = false; liveRetries = 0
    }

    // ── lines / qualities ───────────────────────────────────────────────────────────────────────────────

    /** Keeps the episode index and the position; remembered for the series. */
    fun selectLine(index: Int) {
        if (index == lineIndex && error == null) return
        val target = lines.getOrNull(index)?.takeIf { it.episodes.isNotEmpty() } ?: return
        lib.setPreferredLine(seriesId, target.id)
        val at = resumeAnchor()
        val before = epIndex
        switchLine(index)
        hint = "线路：${target.name}"
        autoSwitches = 0; triedLines.clear(); triedStreams.clear(); refreshed = false
        // A shorter line may not have this episode: its last one then starts from its own progress.
        load(positionMs = at.takeIf { epIndex == before })
    }

    private fun switchLine(index: Int) {
        lineIndex = index
        epIndex = epIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
        streams = emptyList(); stream = null; danmakuUrl = null
        refreshed = false; resniffed = false
    }

    fun selectStream(s: HGStream) {
        if (s.streamId == stream?.streamId && error == null) return
        if (!channelList) lib.setPreferredQuality(seriesId, s.quality)
        autoSwitches = 0; triedStreams.clear(); triedLines.clear(); refreshed = false
        hint = if (channelList) "线路：${liveLineName(s)}" else "画质：${s.quality ?: "自动"}"
        load(positionMs = resumeAnchor(), forced = s)
    }

    /** "线路 2" unless the list names its sources. */
    fun liveLineName(s: HGStream): String = s.quality?.takeIf { it.isNotBlank() } ?: "线路 ${streams.indexOfFirst { it.streamId == s.streamId } + 1}"

    /** 画质 list for an episode that started from the local cache. */
    fun ensureStreams() {
        if (queued || fixture || streams.isNotEmpty() || streamsJob?.isActive == true) return
        val ep = episode ?: return
        streamsJob = scope.launch {
            val r = try { EpisodeCache.resolve(ep).await() } catch (e: CancellationException) { currentCoroutineContext().ensureActive(); return@launch } catch (e: Exception) { return@launch }
            if (episode?.vid == ep.vid && streams.isEmpty()) {
                streams = r.streams
                if (stream == null) stream = r.streams.firstOrNull { it.quality == quality }
            }
        }
    }

    // ── loading ─────────────────────────────────────────────────────────────────────────────────────────

    private fun resumeAnchor(): Long? = if (live) null else (if (mediaReady && firstFrame) clock.pos else startedAt).takeIf { it > 0L }

    private fun resumeFor(index: Int): Long {
        val r = lib.progress(seriesId) ?: return 0L
        return if (r.episodeIndex == index && r.seconds > 3 && r.fraction < 0.95f) (r.seconds * 1000).toLong() else 0L
    }

    private fun load(positionMs: Long?, forced: HGStream? = null, forceResolve: Boolean = false, forceSniff: Boolean = false, switching: Boolean = false) {
        loadJob?.cancel(); watchJob?.cancel(); stallJob?.cancel(); prefetchJob?.cancel(); streamsJob?.cancel()
        val ep = episode ?: return
        EpisodeCache.abandon(ep.vid)
        mediaReady = false
        steadyMs = 0L; stillMs = 0L
        error = null
        firstFrame = false
        if (switching) shade = true
        clock.reset()
        seekExtractor.setSource(null)
        // Pause instead of stop(): the decoder stays allocated and is reused for the next media.
        player.pause()
        val startAt = if (channelList) positionMs?.takeIf { !live } ?: 0L else positionMs ?: resumeFor(epIndex)
        startedAt = startAt
        loadJob = scope.launch {
            // Flipping through channels: only the one the user stops at is opened.
            if (live && switching) delay(220)
            try {
                if (fixture) { playFixture(); return@launch }
                val prefer = if (channelList) null else lib.preferredQuality(seriesId)
                val quick = if (forced == null && !forceResolve && !forceSniff && !channelList)
                    EpisodeCache.peek(ep.vid)?.takeIf { prefer == null || it.quality == prefer } else null
                if (quick != null) {
                    EpisodeCache.peekResolved(ep)?.let { r ->
                        streams = r.streams; danmakuUrl = r.danmaku
                        stream = r.streams.firstOrNull { it.quality == quick.quality && it.codec == quick.codec }
                    }
                    reused = quick.hls || !quick.uri.startsWith("/")
                    openedAt = System.currentTimeMillis()
                    play(ep, quick, startAt)
                    return@launch
                }
                status = if (live) "正在连接…" else "取流中…"
                // Episode 1 of a 播完连刷推荐 drama is usually already prepared; fall back to a fresh resolve on any failure.
                val prepared = if (forced == null && !forceResolve && !channelList) PlayerNextDrama.takePrepared(ep.vid)?.let { pre ->
                    try { pre.await() } catch (e: Exception) { currentCoroutineContext().ensureActive(); null }
                } else null
                val program = replay
                reused = prepared != null || !forceResolve && EpisodeCache.peekResolved(ep) != null
                val r = if (program != null) withContext(Dispatchers.IO) { IdjApp.instance.tvbox.catchup(ep, program) } ?: throw IllegalStateException("这个频道不支持回看")
                    else EpisodeCache.peekResolved(ep)?.takeIf { prepared != null && !forceResolve } ?: EpisodeCache.resolve(ep, forceResolve).await()
                streams = r.streams
                danmakuUrl = r.danmaku
                val s = forced?.let { f -> r.streams.firstOrNull { it.streamId == f.streamId } ?: r.streams.firstOrNull { it.quality == f.quality } }
                    ?: withContext(Dispatchers.Default) { DecoderCaps.pick(r.streams, prefer) } ?: throw IllegalStateException(r.err ?: "无可用流")
                stream = s
                openedAt = System.currentTimeMillis()
                val p = prepared?.takeIf { it.quality == s.quality } ?: EpisodeCache.open(ep.vid, s, channelList, forceSniff) { status = it }
                if (!channelList) EpisodeCache.remember(ep.vid, p)
                play(ep, p, startAt)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.w("IDJ", "Player media load failed", e)
                if (!refreshed && !channelList && (reused || app.iduanju.data.Catalog.isHongguo(seriesId))) {
                    refreshed = true
                    EpisodeCache.forget(ep)
                    status = "重新取流中…"
                    delay(350L)
                    load(positionMs = startAt.takeIf { it > 0L }, forced = null, forceResolve = true)
                    return@launch
                }
                status = ""
                val reason = (e as? app.iduanju.core.util.ProviderException)?.message?.takeIf { it.isNotBlank() }
                    ?: (e as? IllegalStateException)?.message?.takeIf { it.isNotBlank() && it.any { c -> c.code > 0x2e80 } }
                if (!failover("当前线路取流失败")) fail(reason ?: "无法打开这条播放线路")
            }
        }
    }

    private fun play(ep: HGEpisode, p: Playable, startAt: Long) {
        playable = p
        quality = p.quality
        status = ""
        loadedAt = System.currentTimeMillis()
        if (!p.uri.startsWith("$CENC_SCHEME://")) seekExtractor.setSource(p.uri, p.headers, p.hls || channelList)
        p.note?.let { android.util.Log.i("IDJ", "hls: $it") }
        CencStore.focus(p.uri.takeIf { it.startsWith("$CENC_SCHEME://") }?.removePrefix("$CENC_SCHEME://"))
        val source = PlayerSources.create(ctx, p)
        if (startAt > 0L) player.setMediaSource(source, startAt) else player.setMediaSource(source)
        mediaReady = true
        player.setPlaybackSpeed(if (live) 1f else if (holding || holdLocked) holdSpeed else speed)
        beforePrepare()
        player.prepare()
        if (inForeground()) player.play() else player.playWhenReady = false
        watchFirstFrame()
        prefetch(ep)
        if (channelList) recordProgress(force = true)
    }

    private suspend fun playFixture() {
        val local = java.io.File(ctx.cacheDir, "ui-color-bars.mp4")
        if (!local.exists()) withContext(Dispatchers.IO) { ctx.assets.open("player_landscape.mp4").use { input -> local.outputStream().use { input.copyTo(it) } } }
        playable = Playable(local.absolutePath, null, "", false)
        player.setMediaSource(PlayerSources.create(ctx, playable!!))
        seekExtractor.setSource(local.absolutePath)
        mediaReady = true
        beforePrepare()
        status = ""
        player.prepare(); player.play()
    }

    private fun frameShown() {
        handoff = null
        firstFrame = true
        shade = false
        buffering = false
        error = null
        watchJob?.cancel()
        liveRetries = 0
        tags = TechTags.from(player, quality)
    }

    /** Next episodes: resolved, opened and their first bytes local, without a second player or decoder. */
    private fun prefetch(ep: HGEpisode) {
        // resolving a TVBox episode may open a hidden page or run scripts: not while another one plays
        if (channelList || queued || fixture || app.iduanju.data.Catalog.isTVBox(seriesId) || !IdjApp.instance.settings.prefetchEnabled) return
        val index = epIndex
        prefetchJob = scope.launch {
            delay(3_000)
            while (!firstFrame) delay(500)
            val long = clock.dur > 15 * 60_000L
            // Long titles: only near the end, so a signed address does not expire before it is used.
            while (long && clock.dur - clock.pos > 90_000L) delay(2_000)
            val prefer = lib.preferredQuality(seriesId)
            episodes.getOrNull(index + 1)?.let { EpisodeCache.preload(it, prefer) }
            if (!long && seriesId.all { it.isDigit() }) {
                delay(4_500)
                if (epIndex == index) episodes.getOrNull(index + 2)?.let { EpisodeCache.warm(it, prefer) }
            }
        }
    }

    // ── watchdog / failover ─────────────────────────────────────────────────────────────────────────────

    private fun watchFirstFrame() {
        watchJob?.cancel()
        if (queued || fixture) return
        watchJob = scope.launch {
            // opening (container check, playlist) is part of the limit
            delay(((if (live) LIVE_FIRST_FRAME_MS else FIRST_FRAME_MS) - (System.currentTimeMillis() - openedAt)).coerceAtLeast(MIN_FIRST_FRAME_MS))
            if (firstFrame || !mediaReady || playsUnseen()) return@launch
            // Some OEM codecs reuse the surface across back-to-back episodes without firing onRenderedFirstFrame again.
            if (player.playbackState == Player.STATE_READY && player.isPlaying && player.currentPosition > startedAt + 400L) {
                frameShown()
                return@launch
            }
            if (!refreshed && !channelList) {
                refreshed = true
                episode?.let { ep ->
                    val cur = playable
                    if (cur != null && cur.encrypted && cur.uri.startsWith("/")) EpisodeCache.discard(ep, cur.uri) else EpisodeCache.forget(ep)
                }
                hint = "加载较慢，正在重新取流…"
                load(resumeAnchor(), forced = null, forceResolve = true)
                return@launch
            }
            if (failover(if (live) "信号较慢" else "加载较慢")) return@launch
            // Nothing else to try: give the only source more time before giving up.
            delay(LAST_CHANCE_MS)
            if (!firstFrame && mediaReady && !playsUnseen()) {
                player.pause()
                fail(if (live) "信号源暂时不可用" else if (streams.size > 1 || multiLine) "长时间没有画面，请换一个画质或线路" else "加载超时，请检查网络后重试")
            }
        }
    }

    /** Playing on without a surface: nothing will ever render, and nothing is wrong. */
    private fun playsUnseen(): Boolean = !hasSurface() && player.playbackState == Player.STATE_READY

    private fun watchStall() {
        if (stallJob?.isActive == true || queued || fixture) return
        stallJob = scope.launch {
            if (live) {
                delay(8_000)
                if (player.playbackState != Player.STATE_BUFFERING || !mediaReady) return@launch
                if (!failover("信号中断")) reconnect()
                return@launch
            }
            // VOD stall watchdog: if buffering stalls without advancing bufferedPosition for 15s, recover automatically.
            var lastBuffered = player.bufferedPosition
            var stalledMs = 0L
            while (stalledMs < 15_000L) {
                delay(1_000L)
                if (player.playbackState != Player.STATE_BUFFERING || !mediaReady) return@launch
                val curBuffered = player.bufferedPosition
                if (curBuffered > lastBuffered + 1_500L) {
                    lastBuffered = curBuffered
                    stalledMs = 0L
                } else {
                    stalledMs += 1_000L
                }
            }
            if (player.playbackState != Player.STATE_BUFFERING || !mediaReady) return@launch
            if (failover("缓冲超时")) return@launch
            if (autoSwitches < MAX_AUTO_SWITCHES) {
                autoSwitches++
                hint = "缓冲超时，正在重新连接…"
                episode?.let { EpisodeCache.forget(it) }
                load(resumeAnchor(), forced = stream, forceResolve = true)
            } else {
                player.pause()
                fail("缓冲超时，请检查网络或切换线路后重试")
            }
        }
    }

    /** Live only: the same address again, at the live edge. */
    private fun reconnect() {
        if (liveRetries >= 3) { fail("信号源暂时不可用"); return }
        liveRetries++
        hint = "正在重新连接…"
        load(null, forced = stream)
    }

    /** Next quality of this line, then the next line; at most three automatic switches per episode. */
    private fun failover(reason: String): Boolean {
        if (queued || fixture || autoSwitches >= MAX_AUTO_SWITCHES) return false
        val at = resumeAnchor()
        stream?.let { triedStreams.add(streamKey(it)) }
        val untried = streams.filter { streamKey(it) !in triedStreams }
        val candidate = untried.firstOrNull { DecoderCaps.canPlay(it) } ?: untried.firstOrNull()
        if (candidate != null) {
            autoSwitches++
            hint = "$reason，已切换到「${if (channelList) liveLineName(candidate) else candidate.quality?.ifBlank { null } ?: "其他画质"}」"
            load(at, forced = candidate)
            return true
        }
        triedLines.add(lineIndex)
        val other = lines.indices.firstOrNull { it !in triedLines && epIndex in lines[it].episodes.indices } ?: return false
        autoSwitches++
        val failed = line?.name.orEmpty()
        switchLine(other)
        hint = "「$failed」不可用，已切换到「${lines[other].name}」"
        load(at)
        return true
    }

    /**
     * A server that closes early leaves a still picture under a running clock: nothing is rendered any more, far from the
     * announced end. Not the next episode: the rest is fetched again, from a fresh address.
     */
    private fun brokeOff(): Boolean {
        if (live || queued || fixture || !mediaReady) return false
        val pos = player.currentPosition
        val dur = player.duration
        if (dur <= 0 || dur - pos < maxOf(15_000L, dur / 20) || autoSwitches >= MAX_AUTO_SWITCHES) return false
        val at = (pos - stillMs).coerceAtLeast(0L)
        if (cutAt >= 0 && kotlin.math.abs(at - cutAt) < 4_000) return false
        cutAt = at
        autoSwitches++
        hint = "播放中断，正在恢复…"
        episode?.let { EpisodeCache.forget(it) }
        load(at, forced = stream, forceResolve = true)
        return true
    }

    /** Every 250 ms while playing. */
    private fun watchRendering() {
        val counters = listOfNotNull(player.videoDecoderCounters, player.audioDecoderCounters)
        if (counters.isEmpty() || !firstFrame || !mediaReady) return
        val n = counters.sumOf { it.ensureUpdated(); it.renderedOutputBufferCount.toLong() + it.skippedOutputBufferCount + it.droppedBufferCount }
        if (n != renderedCount) { renderedCount = n; stillMs = 0L } else if (!player.isLoading) { stillMs += 250L; if (stillMs == 5_000L) brokeOff() }
    }

    /** After a minute without trouble the next failure starts with all its options again. */
    private fun refill() {
        steadyMs += 250L
        if (steadyMs != 60_000L) return
        autoSwitches = 0; triedStreams.clear(); triedLines.clear(); refreshed = false; resniffed = false; liveRetries = 0
    }

    private fun streamKey(s: HGStream) = "$lineIndex|${s.streamId}|${s.url.hashCode()}"

    private fun fail(message: String) {
        watchJob?.cancel(); stallJob?.cancel()
        handoff = null
        shade = false
        status = ""
        canSwitchSource = streams.size > 1 || multiLine
        error = message
    }

    /** 重试: a fresh address for the same episode, from where it stopped. */
    fun retry() {
        val q = queue
        if (q != null) { q.select(epIndex); return }
        if (detail == null) { loadDetail(); return }
        resetAttempts()
        load(resumeAnchor(), forced = stream, forceResolve = true)
    }

    /** 换线路 on the error card: the next line / quality by hand. */
    fun switchSource() {
        val n = streams.size
        if (n > 1) {
            val i = streams.indexOfFirst { it.streamId == stream?.streamId }
            val next = streams[(i + 1).mod(n)]
            if (!multiLine || i + 1 < n) { autoSwitches = 0; triedStreams.clear(); load(resumeAnchor(), forced = next); return }
        }
        if (multiLine) selectLine((lineIndex + 1).mod(lines.size)) else retry()
    }

    private fun onFailed(e: PlaybackException) {
        if (e.errorCode in 7000..7009 && onEffectFailure()) {
            val at = player.currentPosition.coerceAtLeast(0L)
            val go = player.playWhenReady
            if (at > 0L && !live) player.seekTo(at)
            player.prepare()
            player.playWhenReady = go
            hint = "当前设备不支持 GPU 画质增强，已自动回退"
            return
        }
        // A late error of the previous episode while the next one is still being resolved.
        if (queued || !mediaReady) return
        android.util.Log.w("IDJ", "playback error ${e.errorCodeName}", e)
        if (live && e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition(); player.prepare(); return
        }
        val http = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        // A signed address that outlived its validity: resolve again once before blaming the source.
        val expired = if (reused) http != null && http in 400..499 else (http == 401 || http == 403 || http == 404 || http == 410) && System.currentTimeMillis() - loadedAt > 30_000L
        if (!refreshed && expired && !channelList) {
            refreshed = true
            episode?.let { EpisodeCache.forget(it) }
            load(resumeAnchor(), forced = stream, forceResolve = true)
            return
        }
        val current = playable
        val ep = episode
        if (!refreshed && current != null && ep != null && current.encrypted) {
            // A damaged cache file, or a download whose address ran out half way: start over with a fresh one.
            refreshed = true
            if (current.uri.startsWith("/")) EpisodeCache.discard(ep, current.uri) else EpisodeCache.forget(ep)
            load(resumeAnchor(), forced = stream, forceResolve = true)
            return
        }
        // The address looked like one container and is another.
        if (!resniffed && playable?.sniffed == false && playable?.encrypted == false &&
            (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
            resniffed = true
            load(resumeAnchor(), forced = stream, forceSniff = true)
            return
        }
        val reason = when {
            e.errorCode in 4000..4999 -> "当前画质无法解码"
            http != null -> "播放地址失效（$http）"
            live -> "信号中断"
            else -> "播放失败"
        }
        if (failover(reason)) return
        if (live && firstFrame) { reconnect(); return }
        fail(
            when {
                generateSequence<Throwable>(e) { it.cause }.any { it is app.iduanju.tvbox.LanRefused } -> app.iduanju.core.tvbox.TVBoxEngine.LAN_REFUSED
                e.errorCode in 4000..4999 -> "设备无法解码这个视频"
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED || e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接失败，请检查网络后重试"
                http != null -> "播放地址失效（HTTP $http）"
                e.errorCode in 2000..2999 -> "视频加载失败，请重试"
                e.errorCode in 3000..3999 -> "无法识别这个视频的格式"
                live -> "信号源暂时不可用"
                else -> "播放失败：${e.errorCodeName.removePrefix("ERROR_CODE_")}"
            },
        )
    }

    private fun onEnded() {
        if (brokeOff()) return
        if (sleepMode == -1) {
            setSleep(0, quiet = true)
            player.pause()
            hint = "本集已播完，定时暂停"
            return
        }
        if (queued) return
        if (replay != null) { backToLive(); return }
        if (live) { reconnect(); return }
        val n = episodes.size
        if (epIndex + 1 < n) {
            if (autoNext) {
                handoff = episodes.getOrNull(epIndex + 1)?.vid?.let { EpisodeCache.peekFirstFrame(it) }
                next()
            }
        } else if (n > 0) {
            ended = true
            onFinalEnded()
        }
    }

    // ── transport ───────────────────────────────────────────────────────────────────────────────────────

    fun togglePlay() {
        // what the player still holds is the previous episode
        if (!mediaReady) return
        if (player.isPlaying) player.pause()
        else {
            if (live && mediaReady && player.playbackState != Player.STATE_IDLE) player.seekToDefaultPosition()
            if (player.playbackState == Player.STATE_IDLE && mediaReady && error == null) player.prepare()
            player.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        if (live) return
        val max = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, max))
        hint = if (deltaMs < 0) "-${-deltaMs / 1000}s" else "+${deltaMs / 1000}s"
    }

    fun seekTo(ms: Long) {
        if (live) return
        val max = player.duration.coerceAtLeast(0L)
        val t = if (max > 0L) ms.coerceIn(0L, max) else ms.coerceAtLeast(0L)
        player.seekTo(t)
        if (mediaReady) clock.pos = t
    }

    fun applySpeed(value: Float) {
        speed = value
        holdLocked = false
        holding = false
        PlayerPrefs.setSpeed(ctx, value)
        if (!live) player.setPlaybackSpeed(value)
    }

    fun startHold(initial: Float = 2f) {
        if (live) return
        holding = true
        holdLocked = false
        holdSpeed = initial
        player.setPlaybackSpeed(initial)
    }

    fun updateHoldSpeed(value: Float) {
        if (live || (!holding && !holdLocked)) return
        holdSpeed = value
        player.setPlaybackSpeed(value)
    }

    fun lockHold() {
        if (live || !holding) return
        holdLocked = true
        holding = false
        player.setPlaybackSpeed(holdSpeed)
    }

    fun endHold(force: Boolean = false) {
        if (force) holdLocked = false
        if (!holding && !force) return
        holding = false
        if (!holdLocked && !live) player.setPlaybackSpeed(speed)
    }

    fun hold(on: Boolean) {
        if (on) startHold(2f) else endHold(force = false)
    }

    /** Re-sync Compose playing state with ExoPlayer after Activity resume / PiP transition. */
    fun syncPlayingState() {
        playing = player.isPlaying
    }

    fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
        hint = if (muted) "已静音" else "已取消静音"
    }

    fun applyAutoNext(on: Boolean) { autoNext = on; PlayerPrefs.setAutoNext(ctx, on) }

    fun setSleep(mode: Int, quiet: Boolean = false) {
        sleepMode = mode
        sleepEndMs = if (mode > 0) System.currentTimeMillis() + mode * 60_000L else 0L
        if (!quiet) hint = when (mode) {
            0 -> "定时关闭：已取消"
            -1 -> "定时关闭：播完本集暂停"
            else -> "定时关闭：$mode 分钟后暂停"
        }
    }

    // ── tracks ──────────────────────────────────────────────────────────────────────────────────────────

    private fun choices(tracks: Tracks, type: Int): List<TrackChoice> {
        val out = ArrayList<TrackChoice>()
        for (g in tracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val n = out.size + 1
                val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
                val label = f.label?.takeIf { it.isNotBlank() } ?: lang ?: if (type == C.TRACK_TYPE_AUDIO) "音轨 $n" else "字幕 $n"
                val detail = if (type == C.TRACK_TYPE_AUDIO) listOfNotNull(
                    lang?.takeIf { it != label }, TechTags.audioName(f.sampleMimeType), f.channelCount.takeIf { it > 0 }?.let { if (it == 6) "5.1" else if (it == 8) "7.1" else "$it.0" },
                ).joinToString(" · ") else lang?.takeIf { it != label }.orEmpty()
                out.add(TrackChoice(label, detail, g.isTrackSelected(i), g, i))
            }
        }
        return out
    }

    fun selectAudio(t: TrackChoice) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index)).build()
    }

    /** null = subtitles off. */
    fun selectText(t: TrackChoice?) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, t == null)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .apply { if (t != null) addOverride(TrackSelectionOverride(t.group.mediaTrackGroup, t.index)) }.build()
        hint = if (t == null) "字幕：关闭" else "字幕：${t.label}"
    }

    // ── clock / progress ────────────────────────────────────────────────────────────────────────────────

    private suspend fun poll() {
        var tick = 0
        while (currentCoroutineContext().isActive) {
            val isPlaying = player.isPlaying
            if (mediaReady) {
                clock.pos = player.currentPosition.coerceAtLeast(0L)
                clock.dur = if (live) 0L else player.duration.coerceAtLeast(0L)
                clock.buffered = player.bufferedPosition
            }
            playing = isPlaying
            if (isPlaying && !firstFrame && mediaReady && player.playbackState == Player.STATE_READY && clock.pos > startedAt + 500L && hasSurface()) {
                frameShown()
            }
            if (isPlaying) { watchedMs[0] += 250L; if (firstFrame) { refill(); watchRendering() } }
            if (sleepMode > 0 && sleepEndMs > 0L && System.currentTimeMillis() >= sleepEndMs) {
                player.pause()
                setSleep(0, quiet = true)
                hint = "定时关闭已暂停"
            }
            UiEvidence.playerPositionMs = clock.pos; UiEvidence.playerPlaying = isPlaying
            if (++tick % 20 == 0 && isPlaying && !channelList) recordProgress(force = false)
            delay(250)
        }
    }

    private fun recordProgress(force: Boolean) {
        if (queued || fixture) return
        val d = detail ?: return
        val ep = episode ?: return
        val now = System.currentTimeMillis()
        if (d.isLive) {
            lib.record(WatchProgress(seriesId, d.title, d.cover, epIndex, ep.ep, 0.0, 0.0, now, ep.title, ep.page.orEmpty()))
            return
        }
        if (!mediaReady || !(firstFrame || player.playbackState == Player.STATE_READY)) return
        val pos = runCatching { player.currentPosition }.getOrDefault(clock.pos).coerceAtLeast(0L)
        val dur = runCatching { player.duration.coerceAtLeast(0L) }.getOrDefault(clock.dur)
        lib.record(WatchProgress(seriesId, d.title, d.cover, epIndex, ep.ep, pos / 1000.0, dur / 1000.0, now))
        if (app.iduanju.data.Catalog.isHongguo(seriesId) && pos > 0L) {
            if (!force) IdjApp.instance.hgWebAccount.reportProgressAsync(seriesId, ep.vid, ep.ep, pos, watchedMs[0].coerceAtLeast(1500L), force = false)
            else if (watchedMs[0] >= 1000L) IdjApp.instance.hgWebAccount.reportProgressAsync(seriesId, ep.vid, ep.ep, pos, watchedMs[0], force = true)
        }
    }

    private companion object {
        /** Started last: while one screen fades into the next, both sessions are there for a moment. */
        var current: PlayerSession? = null
        const val MIN_FIRST_FRAME_MS = 2_000L
        const val FIRST_FRAME_MS = 8_000L
        const val LIVE_FIRST_FRAME_MS = 6_000L
        const val LAST_CHANCE_MS = 17_000L
        const val MAX_AUTO_SWITCHES = 3
    }
}
