package app.iduanju.novel

import android.app.PendingIntent
import android.content.Context
import app.iduanju.ui.player.DecoderCaps
import app.iduanju.ui.player.MediaKinds
import app.iduanju.ui.player.PlayerFactory
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import app.iduanju.MainActivity
import app.iduanju.core.novel.NovelMedia
import app.iduanju.core.util.NetGuard
import app.iduanju.music.AppAudioArbiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.net.UnknownHostException

/**
 * The process's single Legado audio/video playback, kept outside composition: a stopped activity pauses its recomposer,
 * but lock-screen controls, chapter changes and audio focus have to keep working with the screen off. Main thread only.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object NovelPlayback {
    /** One book's chapters. [resolve] blocks on the network and only runs off the main thread. */
    class Queue(val key: String, val book: String, val type: Int, val titles: List<String>, val cover: String = "", val resolve: (Int) -> NovelMedia)

    class State(val queue: Queue, val index: Int, val media: NovelMedia?, val failure: String?) {
        val title: String get() = queue.titles.getOrElse(index) { queue.book }
        val hasPrevious: Boolean get() = index > 0
        val hasNext: Boolean get() = index + 1 < queue.titles.size
        /** Only audiobooks play on in the background; a video pauses with its screen, as iOS does. */
        val video: Boolean get() = queue.type == 4
    }

    var state by mutableStateOf<State?>(null)
        private set
    internal var session: MediaSession? = null
        private set
    val player: Player? get() = session?.player
    /** A chapter is being resolved or loaded: nothing sounds for that moment, but the playback has not stopped. */
    val changing: Boolean get() = job?.isActive == true || exo?.let { it.playWhenReady && it.playbackState == Player.STATE_BUFFERING } == true

    internal var exo: ExoPlayer? = null
        private set
    private var app: Context? = null
    private val main = MainScope()
    private var job: Job? = null
    private var prefetch: Job? = null
    /** The next chapter's address, resolved while the current one plays and loaded once it ends. */
    private var upcoming: Pair<Int, NovelMedia>? = null
    /** Resolved right before it was loaded and not ready yet: resolving it again would only repeat the failure. */
    private var fresh = -1
    /** Failed addresses of the current chapter resolved again; bounded, so a stream that keeps breaking cannot loop. */
    private var recovered = 0

    // A paused progressive download leaves its HTTP/2 stream open, and the unread data can use up the connection's
    // flow-control window: a seek's range request multiplexed onto that connection then waits until it times out.
    private val guarded by lazy {
        OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1)).socketFactory(GuardedMediaSocketFactory()).dns(object : Dns {
            override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.any { NetGuard.isBlockedIP(it.hostAddress.orEmpty()) }) throw UnknownHostException("媒体请求拒绝内网地址")
            }
        }).build()
    }

    /** A refused address (an expired signature, a removed file) does not come back by retrying: fail now and resolve it again. */
    private val refusedFails = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            when ((loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode) {
                401, 403, 404, 410 -> C.TIME_UNSET
                else -> super.getRetryDelayMsFor(loadErrorInfo)
            }
    }

    fun start(context: Context, queue: Queue, index: Int, media: NovelMedia) {
        val app = context.applicationContext.also { this.app = it }
        job?.cancel(); recovered = 0
        AppAudioArbiter.claim(
            if (queue.type == 4) AppAudioArbiter.Owner.VIDEO
            else AppAudioArbiter.Owner.NOVEL_AUDIO,
        )
        if (session == null) create(app)
        load(queue, index, media, 0, resolvedNow = true)
        if (queue.type == 4) app.stopService(Intent(app, NovelPlaybackService::class.java))
    }

    fun next() {
        state?.takeIf { it.hasNext }?.let { advance(it.index + 1) }
    }

    fun select(index: Int) {
        if (state?.queue?.titles?.indices?.contains(index) == true) skip(index)
    }

    fun previous() {
        state?.takeIf { it.hasPrevious }?.let { skip(it.index - 1) }
    }

    fun stop() {
        val prev = state
        job?.cancel(); prefetch?.cancel(); upcoming = null; fresh = -1; recovered = 0
        state = null
        session?.release(); session = null
        exo?.release(); exo = null
        app?.let { it.stopService(Intent(it, NovelPlaybackService::class.java)) }
        if (prev != null) {
            AppAudioArbiter.release(
                if (prev.video) AppAudioArbiter.Owner.VIDEO
                else AppAudioArbiter.Owner.NOVEL_AUDIO,
            )
        }
    }

    private fun create(app: Context) {
        val exo = ExoPlayer.Builder(app, DecoderCaps.renderers(app)).setLoadControl(PlayerFactory.loadControl(app)).setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // The service holds the notification and keeps the process foreground; Media3 promotes it while playing.
                if (isPlaying && state?.video == false) runCatching { app.startService(Intent(app, NovelPlaybackService::class.java)) }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) { fresh = -1; prefetchNext() }
                if (playbackState == Player.STATE_ENDED) state?.takeIf { it.hasNext && it.media != null }?.let { advance(it.index + 1) }
            }
            override fun onPlayerError(error: PlaybackException) {
                val s = state?.takeIf { it.media != null } ?: return
                // A prefetched address may have expired by the time its chapter starts, and a chapter that played for a
                // while may have outlived its address: resolve it again and go on from there. A fresh one failed for real.
                if (s.index == fresh || recovered >= 2) {
                    android.util.Log.w("NovelPlayback", "Chapter playback failed", error)
                    val reason = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "设备无法解码本章视频"
                        else -> "本章暂时无法播放，请重试"
                    }
                    state = State(s.queue, s.index, s.media, reason)
                }
                else { recovered++; skip(s.index, exo.currentPosition, retry = true) }
            }
        })
        val open = PendingIntent.getActivity(app, 0,
            Intent(app, MainActivity::class.java).putExtra("route", "novel").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        this.exo = exo
        session = MediaSession.Builder(app, ChapterPlayer(exo)).setId("novel-media").setSessionActivity(open).build()
    }

    private fun load(queue: Queue, index: Int, media: NovelMedia, positionMs: Long, resolvedNow: Boolean) {
        val exo = exo ?: return
        prefetch?.cancel(); upcoming = null
        fresh = if (resolvedNow) index else -1
        state = State(queue, index, media, null)
        exo.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(if (queue.type == 4) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        exo.setMediaSource(source(queue, index, media), positionMs)
        exo.prepare()
        exo.play()
    }

    /** Goes on with the next chapter's prefetched address; [skip] resolves it when there is none yet. */
    private fun advance(to: Int) {
        val s = state ?: return
        val ready = upcoming?.takeIf { it.first == to }?.second ?: return skip(to)
        job?.cancel(); recovered = 0
        load(s.queue, to, ready, 0, resolvedNow = false)
    }

    private fun skip(to: Int, positionMs: Long = 0, retry: Boolean = false) {
        val queue = state?.queue ?: return
        job?.cancel(); prefetch?.cancel(); upcoming = null
        if (!retry) recovered = 0
        state = State(queue, to, null, null)
        job = main.launch {
            val media = try { runInterruptible(Dispatchers.IO) { queue.resolve(to) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { state = State(queue, to, null, e.message ?: "媒体解析失败"); return@launch }
            load(queue, to, media, positionMs, resolvedNow = true)
        }
        exo?.pause()
    }

    private fun prefetchNext() {
        val s = state ?: return
        if (!s.hasNext || s.media == null || upcoming?.first == s.index + 1 || prefetch?.isActive == true) return
        val to = s.index + 1
        prefetch = main.launch {
            val media = try { runInterruptible(Dispatchers.IO) { s.queue.resolve(to) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { return@launch }
            if (state?.let { it.queue === s.queue && it.index == s.index } == true) upcoming = to to media
        }
    }

    private fun source(queue: Queue, index: Int, media: NovelMedia): MediaSource {
        val client = guarded.newBuilder()
            .addInterceptor { it.proceed(guardedMediaRequest(it.request(), media.url)) }
            .addNetworkInterceptor { it.proceed(guardedMediaRequest(it.request(), media.url)) }.build()
        val item = MediaItem.Builder().setUri(media.url).setMediaId(index.toString())
            .apply { MediaKinds.guess(media.url)?.mime?.let { setMimeType(it) } }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(queue.titles.getOrElse(index) { queue.book }).setArtist(queue.book).build()).build()
        return DefaultMediaSourceFactory(OkHttpDataSource.Factory(client).setDefaultRequestProperties(media.headers))
            .setLoadErrorHandlingPolicy(refusedFails).createMediaSource(item)
    }

    /**
     * The lock screen, the notification, a headset and the player view step through chapters, which are resolved one at a
     * time, so the player only ever holds the current chapter.
     */
    private class ChapterPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands {
            val s = NovelPlayback.state ?: return super.getAvailableCommands()
            return super.getAvailableCommands().buildUpon()
                .addIf(Player.COMMAND_SEEK_TO_NEXT, s.hasNext).addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, s.hasNext)
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, s.hasPrevious).build()
        }
        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
        override fun seekToNext() = NovelPlayback.next()
        override fun seekToNextMediaItem() = NovelPlayback.next()
        override fun seekToPrevious() {
            if (NovelPlayback.state?.hasPrevious == true && currentPosition <= maxSeekToPreviousPosition) NovelPlayback.previous() else super.seekToPrevious()
        }
        override fun seekToPreviousMediaItem() {
            if (NovelPlayback.state?.hasPrevious == true) NovelPlayback.previous() else super.seekToPreviousMediaItem()
        }
    }
}
