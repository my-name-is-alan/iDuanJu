package app.iduanju.music

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Policy controlling whether background music automatically resumes after a video / TTS / audiobook finishes. */
enum class MusicResumePolicy(val key: String, val label: String, val detail: String) {
    SMART("smart", "智能恢复", "短看视频（≤ 3 分钟）退出自动淡入续播；长看保持悬浮条暂停"),
    ALWAYS("always", "总是恢复", "退出视频或听书后一律淡入续播（拔出耳机除外）"),
    MANUAL("manual", "仅手动恢复", "看视频中断后保持暂停，仅手动点击续播");

    companion object {
        fun fromKey(raw: String?): MusicResumePolicy = entries.firstOrNull { it.key == raw } ?: SMART
    }
}

/**
 * Global single-app audio arbiter coordinating Music, Video (incl. PiP), Novel TTS, and Novel Audiobook.
 * 1:1 behavioral mirror of iOS `AppAudioArbiter.swift`.
 */
object AppAudioArbiter {
    enum class Owner { NONE, MUSIC, VIDEO, NOVEL_TTS, NOVEL_AUDIO }

    interface MusicHooks {
        fun isPlaying(): Boolean
        fun fadeOutAndPause()
        fun fadeInAndResume()
    }

    interface VideoHooks {
        fun pauseForMusicTakeover()
    }

    const val SMART_RESUME_THRESHOLD_MS = 180_000L
    private const val POLICY_KEY = "stv.music.resumePolicy"

    private var appContext: Context? = null

    var resumePolicy by mutableStateOf(MusicResumePolicy.SMART)
        private set

    var activeOwner by mutableStateOf(Owner.NONE)
        private set

    var appInForeground: Boolean = true
        internal set

    var musicInterruptedAtMs: Long? = null
        private set
    var musicInterruptedBy: Owner? = null
        private set

    private var headphoneUnpluggedDuringInterrupt = false

    var musicHooks: MusicHooks? = null
    var videoHooks: VideoHooks? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        reloadFromPrefs()
    }

    fun reloadFromPrefs() {
        val sp = appContext?.getSharedPreferences("idj", Context.MODE_PRIVATE) ?: return
        resumePolicy = MusicResumePolicy.fromKey(sp.getString(POLICY_KEY, null))
    }

    fun updatePolicy(policy: MusicResumePolicy) {
        resumePolicy = policy
        appContext?.getSharedPreferences("idj", Context.MODE_PRIVATE)
            ?.edit()?.putString(POLICY_KEY, policy.key)?.apply()
    }

    fun lifecycleObserver(): DefaultLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { appInForeground = true }
        override fun onStop(owner: LifecycleOwner) { appInForeground = false }
    }

    /** Pure decision helper for unit tests and runtime evaluation. */
    fun shouldAutoResumeMusic(
        policy: MusicResumePolicy,
        wasPlayingBeforeInterrupt: Boolean,
        elapsedMs: Long,
        appInForeground: Boolean,
        headphoneUnplugged: Boolean,
    ): Boolean {
        if (!wasPlayingBeforeInterrupt || !appInForeground || headphoneUnplugged) return false
        return when (policy) {
            MusicResumePolicy.MANUAL -> false
            MusicResumePolicy.ALWAYS -> true
            MusicResumePolicy.SMART -> elapsedMs in 0L..SMART_RESUME_THRESHOLD_MS
        }
    }

    fun claim(owner: Owner, nowMs: Long = System.currentTimeMillis()) {
        if (owner == Owner.MUSIC) {
            if (activeOwner == Owner.VIDEO) {
                videoHooks?.pauseForMusicTakeover()
            }
            activeOwner = Owner.MUSIC
            clearPendingResume()
            return
        }

        if (musicHooks?.isPlaying() == true) {
            musicInterruptedAtMs = nowMs
            musicInterruptedBy = owner
            headphoneUnpluggedDuringInterrupt = false
            musicHooks?.fadeOutAndPause()
        }
        activeOwner = owner
    }

    fun release(owner: Owner, nowMs: Long = System.currentTimeMillis(), inForeground: Boolean = appInForeground) {
        val started = musicInterruptedAtMs
        val shouldResume = if (started != null && musicInterruptedBy == owner) {
            val res = shouldAutoResumeMusic(
                policy = resumePolicy,
                wasPlayingBeforeInterrupt = true,
                elapsedMs = nowMs - started,
                appInForeground = inForeground,
                headphoneUnplugged = headphoneUnpluggedDuringInterrupt,
            )
            clearPendingResume()
            res
        } else {
            false
        }

        if (activeOwner == owner) {
            activeOwner = if (shouldResume) Owner.MUSIC else Owner.NONE
        }
        if (shouldResume) {
            musicHooks?.fadeInAndResume()
        }
    }

    fun handleHeadphoneUnplugged() {
        headphoneUnpluggedDuringInterrupt = true
        clearPendingResume()
    }

    fun clearPendingResume() {
        musicInterruptedAtMs = null
        musicInterruptedBy = null
    }
}
