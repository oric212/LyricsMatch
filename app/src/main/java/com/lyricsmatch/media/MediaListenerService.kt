package com.lyricsmatch.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Android only exposes other apps' media sessions to apps holding notification access.
 * This service never reads or touches notifications; it only watches media sessions.
 */
class MediaListenerService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var controllers: List<MediaController> = emptyList()
    private var selected: MediaController? = null

    private val component by lazy { ComponentName(this, MediaListenerService::class.java) }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = refresh(null)
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { refresh(it) }

    override fun onListenerConnected() {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        sessionManager = msm
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
        } catch (_: SecurityException) {
            return
        }
        refresh(null)
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = emptyList()
        selected = null
        MediaRepository.update(null)
    }

    private fun refresh(list: List<MediaController>?) {
        val fresh = list ?: try {
            sessionManager?.getActiveSessions(component).orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = fresh
        controllers.forEach { it.registerCallback(controllerCallback, handler) }
        publish()
    }

    /** Prefer whatever is playing; otherwise stick with the last chosen app so pausing doesn't switch songs. */
    private fun publish() {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val previous = controllers.firstOrNull { it.sessionToken == selected?.sessionToken }
        selected = playing ?: previous ?: controllers.firstOrNull()
        MediaRepository.update(selected)
    }

    companion object {
        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
