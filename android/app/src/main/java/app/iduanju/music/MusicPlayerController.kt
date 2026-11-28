package app.iduanju.music

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import app.iduanju.MainActivity
import app.iduanju.ui.player.DecoderCaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class MusicPlayMode(val label: String) {
    LOOP_ALL("列表循环"),
    LOOP_ONE("单曲循环"),
    SHUFFLE("随机播放");

    val next: MusicPlayMode
        get() = when (this) {
            LOOP_ALL -> LOOP_ONE
            LOOP_ONE -> SHUFFLE
            SHUFFLE -> LOOP_ALL
        }
}

/**
 * Real background-capable Android music engine powered by Media3 ExoPlayer + MediaSessionService.
 * - Direct CDN / NAS / LRU offline cache playback (zero relay server).
 * - Remaining-20s next-track pre-resolve for gapless screen-off transitions.
 * - Smooth 180ms fade-out pause & 500ms fade-in smart resume coordinated by [AppAudioArbiter].
 * - `ACTION_AUDIO_BECOMING_NOISY` listener enforces anti-speaker-blast guard on headphone unplug.
 * - Sleep timer with 30s smooth fade-out.
 */
@OptIn(UnstableApi::class)
class MusicPlayerController(
    private val ctx: Context,
    private val store: MusicStore,
) {
    private val appCtx = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val okHttp = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    var currentTrack by mutableStateOf<MusicTrack?>(null)
        private set
    var queue by mutableStateOf<List<MusicTrack>>(emptyList())
        private set
    var currentIndex by mutableIntStateOf(0)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var activeQualityLabel by mutableStateOf("")
        private set
    var activeEntitlement by mutableStateOf(MusicEntitlement.FULL)
        private set
    var currentTime by mutableDoubleStateOf(0.0)
        private set
    var duration by mutableDoubleStateOf(0.0)
        private set
    var lyrics by mutableStateOf<List<MusicLyricLine>>(emptyList())
        private set
    var activeLyricIndex by mutableIntStateOf(0)
        private set
    var signalPath by mutableStateOf<MusicSignalPath?>(null)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var playMode by mutableStateOf(MusicPlayMode.LOOP_ALL)
    var playbackRate by mutableFloatStateOf(1.0f)
        private set
    var showFullPlayer by mutableStateOf(false)
    var miniBarDismissed by mutableStateOf(false)
    var sleepRemainingSec by mutableStateOf<Int?>(null)
        private set
    var immersiveHolders by mutableStateOf<Set<String>>(emptySet())
        private set

    val hasActiveSession: Boolean get() = currentTrack != null && !miniBarDismissed
    val shouldShowGlobalMiniBar: Boolean get() = hasActiveSession && !showFullPlayer && immersiveHolders.isEmpty()
    val changing: Boolean get() = isLoading || activeLoadJob?.isActive == true

    fun enterImmersive(holderId: String) { immersiveHolders = immersiveHolders + holderId }
    fun leaveImmersive(holderId: String) { immersiveHolders = immersiveHolders - holderId }

    private var currentStreamUrl: String = ""
    private var failureRetryCount = 0

    private var exo: ExoPlayer? = null
    internal var mediaSession: MediaSession? = null
        private set

    private var activeLoadJob: Job? = null
    private var pollJob: Job? = null
    private var fadeJob: Job? = null
    private var sleepJob: Job? = null
    private var preResolvedNext: Pair<String, MusicProviderHub.ResolvedAudio>? = null
    private var preResolvingId: String? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                AppAudioArbiter.handleHeadphoneUnplugged()
                pauseImmediate()
            }
        }
    }

    init {
        AppAudioArbiter.musicHooks = object : AppAudioArbiter.MusicHooks {
            override fun isPlaying(): Boolean = this@MusicPlayerController.isPlaying
            override fun fadeOutAndPause() = this@MusicPlayerController.fadeOutAndPause()
            override fun fadeInAndResume() = this@MusicPlayerController.fadeInAndResume()
        }
        runCatching {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appCtx.registerReceiver(noisyReceiver, filter)
            }
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        exo?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val p = ExoPlayer.Builder(appCtx, DecoderCaps.renderers(appCtx))
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    runCatching { appCtx.startService(Intent(appCtx, MusicPlaybackService::class.java)) }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isLoading = false
                    val durMs = p.duration
                    if (durMs > 1000L) duration = durMs / 1000.0
                } else if (playbackState == Player.STATE_ENDED) {
                    handleTrackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val track = currentTrack ?: return
                if (failureRetryCount < 2 && currentStreamUrl.isNotBlank()) {
                    failureRetryCount++
                    statusMessage = "主源异常，正在自动换源…"
                    val failedUrl = currentStreamUrl
                    scope.launch {
                        val fb = MusicProviderHub.resolveFallbackStream(track, failedUrl, store.cookieMap())
                        if (fb != null && currentTrack?.id == track.id) {
                            loadResolved(fb, track, isRetry = true)
                        } else if (currentTrack?.id == track.id) {
                            isLoading = false
                            isPlaying = false
                            statusMessage = "当前流播放异常，请点「换源」切换线路"
                        }
                    }
                } else {
                    isLoading = false
                    isPlaying = false
                    statusMessage = "当前流播放异常，请点「换源」或在「我的 → 音乐与音源设置」登录音源"
                }
            }
        })

        val openIntent = PendingIntent.getActivity(
            appCtx,
            1002,
            Intent(appCtx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val wrapped = object : ForwardingPlayer(p) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()

            override fun hasNextMediaItem(): Boolean = queue.size > 1
            override fun hasPreviousMediaItem(): Boolean = queue.isNotEmpty()
            override fun seekToNext() = nextTrack(userInitiated = true)
            override fun seekToNextMediaItem() = nextTrack(userInitiated = true)
            override fun seekToPrevious() = previousTrack()
            override fun seekToPreviousMediaItem() = previousTrack()
        }

        mediaSession = MediaSession.Builder(appCtx, wrapped)
            .setId("idj-music")
            .setSessionActivity(openIntent)
            .build()

        exo = p
        startPoller()
        return p
    }

    private fun startPoller() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val p = exo
                if (p != null && currentTrack != null) {
                    val posSec = (p.currentPosition.coerceAtLeast(0L)) / 1000.0
                    currentTime = posSec
                    val durMs = p.duration
                    if (durMs > 1000L) duration = durMs / 1000.0
                    updateActiveLyric(posSec)
                    maybePreResolveNext(posSec)
                }
                delay(250)
            }
        }
    }

    fun play(track: MusicTrack, list: List<MusicTrack>? = null, openFull: Boolean = false) {
        if (!list.isNullOrEmpty()) {
            queue = list
            currentIndex = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        } else {
            val idx = queue.indexOfFirst { it.id == track.id }
            if (idx >= 0) {
                currentIndex = idx
            } else {
                queue = listOf(track) + queue
                currentIndex = 0
            }
        }
        miniBarDismissed = false
        if (openFull) showFullPlayer = true
        startTrack(track)
    }

    fun togglePlayPause() {
        val p = exo ?: return
        if (currentTrack == null) return
        if (p.isPlaying) {
            pauseImmediate()
            AppAudioArbiter.clearPendingResume()
        } else {
            AppAudioArbiter.claim(AppAudioArbiter.Owner.MUSIC)
            fadeJob?.cancel()
            p.volume = 1f
            p.playbackParameters = PlaybackParameters(playbackRate)
            p.play()
            isPlaying = true
        }
    }

    fun updatePlaybackRate(rate: Float) {
        playbackRate = rate.coerceIn(0.5f, 2.0f)
        exo?.playbackParameters = PlaybackParameters(playbackRate)
    }

    fun nextTrack(userInitiated: Boolean = true) {
        if (queue.isEmpty()) return
        val nextIdx = if (playMode == MusicPlayMode.SHUFFLE && queue.size > 1) {
            var pick = queue.indices.random()
            if (pick == currentIndex) pick = (currentIndex + 1) % queue.size
            pick
        } else {
            (currentIndex + 1) % queue.size
        }
        currentIndex = nextIdx
        startTrack(queue[nextIdx])
    }

    fun previousTrack() {
        if (queue.isEmpty()) return
        if (currentTime > 4.0) {
            seekTo(0.0)
            return
        }
        val prevIdx = (currentIndex - 1 + queue.size) % queue.size
        currentIndex = prevIdx
        startTrack(queue[prevIdx])
    }

    fun seekTo(seconds: Double) {
        val clamped = seconds.coerceIn(0.0, duration.coerceAtLeast(0.1))
        currentTime = clamped
        exo?.seekTo((clamped * 1000.0).toLong())
        updateActiveLyric(clamped)
    }

    fun switchToAlternative(alt: MusicAlternative) {
        val track = currentTrack ?: return
        val resolved = MusicProviderHub.ResolvedAudio(
            url = alt.streamURL,
            headers = alt.headers,
            actualSource = alt.source,
            qualityLabel = alt.qualityLabel,
            entitlement = alt.entitlement,
            isCached = false,
        )
        loadResolved(resolved, track)
        statusMessage = "已切换至 ${alt.source.label} · ${alt.qualityLabel}"
    }

    fun switchQuality(quality: MusicAudioQuality) {
        store.updateSettings { it.copy(preferredQualityId = quality.id) }
        preResolvedNext = null
        preResolvingId = null
        val track = currentTrack ?: return
        if (track.source.isNAS || track.source == MusicSourceKind.ITUNES) {
            statusMessage = "已将默认音质设为 ${quality.label}（当前音源为固定原流）"
            return
        }
        val resumeSec = currentTime
        val wasPlaying = isPlaying
        store.removeCachedFile(track)
        val enabled = store.enabledOnlineSources
        val nas = store.nasTracks
        val cookies = store.cookieMap()
        val autoFallback = store.settings.autoFallback
        activeLoadJob?.cancel()
        isLoading = true
        statusMessage = "正在切换至 ${quality.label}…"
        activeLoadJob = scope.launch {
            val resolved = MusicProviderHub.resolveStream(
                track = track,
                cachedFilePath = null,
                enabledSources = enabled,
                nasTracks = nas,
                cookies = cookies,
                autoFallback = autoFallback,
                preferredQuality = quality,
            )
            if (currentTrack?.id != track.id) return@launch
            if (resolved != null) {
                loadResolved(resolved, track, seekSec = resumeSec, autoPlay = wasPlaying)
                statusMessage = "已切换音质：${resolved.qualityLabel}"
                if (!resolved.isCached && !resolved.url.startsWith("file://") && resolved.entitlement == MusicEntitlement.FULL) {
                    backgroundCacheIfEligible(resolved.url, resolved.headers, track)
                }
            } else {
                isLoading = false
                statusMessage = "切换 ${quality.label} 失败，保持原音质"
            }
        }
    }

    fun addToQueueNext(track: MusicTrack) {
        if (queue.isEmpty()) {
            play(track)
            return
        }
        val next = queue.toMutableList()
        val insertAt = (currentIndex + 1).coerceAtMost(next.size)
        next.add(insertAt, track)
        queue = next
        statusMessage = "已加入下一首：${track.title}"
    }

    fun removeFromQueue(index: Int) {
        if (index !in queue.indices) return
        val removingCurrent = (index == currentIndex)
        val wasPlaying = isPlaying
        val next = queue.toMutableList()
        next.removeAt(index)
        queue = next
        if (queue.isEmpty()) {
            pauseImmediate()
            currentTrack = null
            currentIndex = 0
            currentTime = 0.0
            duration = 0.0
            showFullPlayer = false
            miniBarDismissed = true
            AppAudioArbiter.clearPendingResume()
            runCatching { appCtx.stopService(Intent(appCtx, MusicPlaybackService::class.java)) }
            return
        }
        if (index < currentIndex) {
            currentIndex = (currentIndex - 1).coerceAtLeast(0)
            return
        }
        if (removingCurrent) {
            currentIndex = index % queue.size
            val nextTrack = queue[currentIndex]
            if (wasPlaying) {
                startTrack(nextTrack)
            } else {
                pauseImmediate()
                currentTrack = nextTrack
                currentTime = 0.0
                duration = nextTrack.duration.coerceAtLeast(1.0)
            }
        }
    }

    fun removeFromQueue(trackId: String) {
        val idx = queue.indexOfFirst { it.id == trackId }
        if (idx >= 0) removeFromQueue(idx)
    }

    fun dismissMiniBar() {
        pauseImmediate()
        miniBarDismissed = true
        AppAudioArbiter.clearPendingResume()
        runCatching { appCtx.stopService(Intent(appCtx, MusicPlaybackService::class.java)) }
    }

    fun setSleepTimer(minutes: Int?) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null || minutes <= 0) {
            sleepRemainingSec = null
            return
        }
        sleepRemainingSec = minutes * 60
        sleepJob = scope.launch {
            while (isActive) {
                val rem = sleepRemainingSec ?: break
                if (rem <= 0) break
                delay(1000)
                val next = (sleepRemainingSec ?: 1) - 1
                sleepRemainingSec = next
                if (next in 1..30) {
                    exo?.volume = (next / 30f).coerceIn(0.05f, 1f)
                }
                if (next <= 0) {
                    pauseImmediate()
                    exo?.volume = 1f
                    sleepRemainingSec = null
                    statusMessage = "睡眠定时已结束"
                    break
                }
            }
        }
    }

    private fun startTrack(track: MusicTrack) {
        activeLoadJob?.cancel()
        fadeJob?.cancel()
        currentTrack = track
        currentTime = 0.0
        duration = track.duration.coerceAtLeast(1.0)
        lyrics = emptyList()
        activeLyricIndex = 0
        statusMessage = null
        isLoading = true

        AppAudioArbiter.claim(AppAudioArbiter.Owner.MUSIC)
        store.recordRecent(track)

        val cachedPre = preResolvedNext?.takeIf { it.first == track.id }?.second
        if (cachedPre != null) preResolvedNext = null

        val cachedPath = store.cachedFile(track)?.absolutePath
        val enabled = store.enabledOnlineSources
        val nas = store.nasTracks
        val cookies = store.cookieMap()
        val autoFallback = store.settings.autoFallback
        val lrclibEnabled = store.settings.lrclibEnabled
        val preferredQuality = store.settings.preferredQuality

        activeLoadJob = scope.launch {
            val lrcJob = launch {
                val fetched = MusicProviderHub.fetchLyrics(track, lrclibEnabled, cookies)
                if (currentTrack?.id == track.id) {
                    lyrics = fetched
                    updateActiveLyric(currentTime)
                }
            }

            val resolved = cachedPre ?: MusicProviderHub.resolveStream(
                track = track,
                cachedFilePath = cachedPath,
                enabledSources = enabled,
                nasTracks = nas,
                cookies = cookies,
                autoFallback = autoFallback,
                preferredQuality = preferredQuality,
            )

            if (currentTrack?.id != track.id) {
                lrcJob.cancel()
                return@launch
            }

            if (resolved != null) {
                loadResolved(resolved, track)
                if (!resolved.isCached && !resolved.url.startsWith("file://") && resolved.entitlement == MusicEntitlement.FULL) {
                    backgroundCacheIfEligible(resolved.url, resolved.headers, track)
                }
            } else {
                isLoading = false
                isPlaying = false
                statusMessage = "当前源暂不可播，请点「换源」切换线路"
            }
        }
    }

    private fun loadResolved(
        resolved: MusicProviderHub.ResolvedAudio,
        track: MusicTrack,
        isRetry: Boolean = false,
        seekSec: Double = 0.0,
        autoPlay: Boolean = true,
    ) {
        if (!isRetry) failureRetryCount = 0
        currentStreamUrl = resolved.url
        activeQualityLabel = resolved.qualityLabel
        activeEntitlement = resolved.entitlement
        if (currentTrack?.id == track.id) {
            val updated = track.copy(qualityLabel = resolved.qualityLabel, entitlementName = resolved.entitlement.name)
            currentTrack = updated
            if (currentIndex in queue.indices && queue[currentIndex].id == track.id) {
                val nextQ = queue.toMutableList()
                nextQ[currentIndex] = updated
                queue = nextQ
            }
        }
        val p = ensurePlayer()
        val meta = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .apply {
                if (track.coverURL.isNotBlank()) setArtworkUri(Uri.parse(track.coverURL))
            }
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(resolved.url)
            .setMediaMetadata(meta)
            .build()

        val httpFactory = OkHttpDataSource.Factory(okHttp).apply {
            if (resolved.headers.isNotEmpty()) setDefaultRequestProperties(resolved.headers)
        }
        val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(appCtx, httpFactory))
        p.setMediaSource(sourceFactory.createMediaSource(mediaItem))
        p.volume = 1f
        p.playbackParameters = PlaybackParameters(playbackRate)
        p.prepare()
        if (seekSec > 1.0) {
            p.seekTo((seekSec * 1000.0).toLong())
        }
        if (autoPlay) p.play() else p.pause()
        isLoading = false
        isPlaying = autoPlay

        val isLossless = resolved.qualityLabel.contains("FLAC", true) ||
            resolved.qualityLabel.contains("ALAC", true) ||
            resolved.qualityLabel.contains("Hi-Res", true) ||
            resolved.qualityLabel.contains("母带") ||
            resolved.qualityLabel.contains("无损") ||
            resolved.qualityLabel.contains("SQ", true)
        signalPath = MusicSignalPath(
            sourceStage = "${resolved.actualSource.fullName} · 客户端直解",
            streamStage = if (resolved.isCached) "本机 LRU 离线缓存" else "直连 CDN / NAS 原流（零中转）",
            decodeStage = "Media3 FFmpeg / 硬解 · ${resolved.qualityLabel}",
            outputStage = "Android 音频通道",
            isLossless = isLossless,
            isCached = resolved.isCached,
        )
        if (resolved.actualSource != track.source) {
            statusMessage = "已自动换源至 ${resolved.actualSource.label}（${resolved.qualityLabel}）"
        } else if (resolved.entitlement == MusicEntitlement.PREVIEW) {
            statusMessage = "当前歌曲仅返回试听片段，可点「换源」切其他平台"
        }
        runCatching { appCtx.startService(Intent(appCtx, MusicPlaybackService::class.java)) }
    }

    private fun maybePreResolveNext(sec: Double) {
        val rem = duration - sec
        if (!isPlaying || rem <= 1.0 || rem > 20.0 || queue.size <= 1 || playMode == MusicPlayMode.LOOP_ONE) return
        val nextTrack = queue[(currentIndex + 1) % queue.size]
        if (preResolvedNext?.first == nextTrack.id || preResolvingId == nextTrack.id) return
        preResolvingId = nextTrack.id
        val cachedPath = store.cachedFile(nextTrack)?.absolutePath
        val enabled = store.enabledOnlineSources
        val nas = store.nasTracks
        val cookies = store.cookieMap()
        val autoFb = store.settings.autoFallback
        val prefQ = store.settings.preferredQuality
        scope.launch {
            val res = MusicProviderHub.resolveStream(nextTrack, cachedPath, enabled, nas, cookies, autoFb, prefQ)
            if (res != null) preResolvedNext = nextTrack.id to res
            if (preResolvingId == nextTrack.id) preResolvingId = null
        }
    }

    private fun handleTrackEnded() {
        if (playMode == MusicPlayMode.LOOP_ONE) {
            seekTo(0.0)
            exo?.play()
        } else {
            nextTrack(userInitiated = false)
        }
    }

    private fun updateActiveLyric(sec: Double) {
        if (lyrics.isEmpty()) return
        var idx = 0
        for (i in lyrics.indices) {
            if (lyrics[i].time <= sec + 0.15) idx = i else break
        }
        if (idx != activeLyricIndex) activeLyricIndex = idx
    }

    // ── Smooth Fade-out / Fade-in for AppAudioArbiter ───────────────────────────────────────────────

    fun fadeOutAndPause() {
        fadeJob?.cancel()
        val p = exo ?: return
        fadeJob = scope.launch {
            val steps = 6
            for (i in 1..steps) {
                p.volume = (1f - i / steps.toFloat()).coerceAtLeast(0f)
                delay(30)
            }
            p.pause()
            p.volume = 1f
            isPlaying = false
        }
    }

    fun fadeInAndResume() {
        if (currentTrack == null) return
        val p = exo ?: return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            p.volume = 0.05f
            p.playbackParameters = PlaybackParameters(playbackRate)
            p.play()
            isPlaying = true
            val steps = 10
            for (i in 1..steps) {
                p.volume = (i / steps.toFloat()).coerceIn(0f, 1f)
                delay(50)
            }
        }
    }

    private fun pauseImmediate() {
        fadeJob?.cancel()
        exo?.pause()
        isPlaying = false
    }

    private fun backgroundCacheIfEligible(url: String, headers: Map<String, String>, track: MusicTrack) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val b = Request.Builder().url(url)
                headers.forEach { (k, v) -> b.header(k, v) }
                okHttp.newCall(b.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@launch
                    val body = resp.body ?: return@launch
                    val len = body.contentLength()
                    if (len > 45L * 1024L * 1024L) return@launch
                    val bytes = body.bytes()
                    if (bytes.size <= 45 * 1024 * 1024) {
                        withContext(Dispatchers.Main) {
                            store.cacheAudioBytes(bytes, track)
                        }
                    }
                }
            }
        }
    }
}
