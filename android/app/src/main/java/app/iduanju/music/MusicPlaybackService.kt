package app.iduanju.music

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.iduanju.IdjApp

/**
 * Dedicated background Media3 service for serverless music playback.
 * Keeps playback alive with lock-screen / Bluetooth / headset controls and media notification.
 */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = runCatching { IdjApp.instance.musicPlayer }.getOrNull()
        val session = controller?.mediaSession ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        if (session !in sessions) addSession(session)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        runCatching { IdjApp.instance.musicPlayer.mediaSession }.getOrNull()

    // Keep service foreground across brief track-resolve gaps so Android never kills screen-off next-track changes.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val changing = runCatching { IdjApp.instance.musicPlayer.changing }.getOrDefault(false)
        super.onUpdateNotification(session, startInForegroundRequired || changing)
    }
}
