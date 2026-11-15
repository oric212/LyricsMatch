package com.lyricsmatch.media

import android.os.SystemClock

/** Snapshot of the song the active music app is playing. */
data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val isPlaying: Boolean,
    /** Position reported by the player at [updateTime]. */
    val positionMs: Long,
    /** [SystemClock.elapsedRealtime] of the position report. */
    val updateTime: Long,
    val speed: Float,
    /** Spotify track ID when known (from Spotify's broadcast); used to fetch the exact cover. */
    val spotifyId: String? = null,
) {
    /** Identifies the song, independent of playback progress. */
    val trackKey: String get() = "$artist\u0000$title"

    /** Extrapolates the playhead to [now], since players only report position on state changes. */
    fun positionAt(now: Long = SystemClock.elapsedRealtime()): Long =
        if (isPlaying) positionMs + ((now - updateTime) * speed).toLong() else positionMs
}
