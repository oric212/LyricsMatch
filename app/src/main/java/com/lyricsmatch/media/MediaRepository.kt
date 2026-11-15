package com.lyricsmatch.media

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the currently selected media session; fed by [MediaListenerService]. */
object MediaRepository {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    @Volatile
    private var controller: MediaController? = null

    fun update(c: MediaController?) {
        controller = c
        _nowPlaying.value = c?.let(::snapshot)
    }

    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs.coerceAtLeast(0))
    }

    private fun snapshot(c: MediaController): NowPlaying? {
        val meta = c.metadata ?: return null
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) return null
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val state = c.playbackState
        return NowPlaying(
            packageName = c.packageName,
            title = title,
            artist = artist,
            album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position ?: 0L,
            updateTime = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
    }
}
