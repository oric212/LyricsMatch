package com.lyricsmatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * DEBUG BUILDS ONLY. Fakes a music app for emulator demos: publishes a real MediaSession
 * (song metadata + a "playing" clock, no audio) that LyricsMatch picks up like Spotify.
 *
 *   adb shell am broadcast -n com.lyricsmatch/.DemoPlayerReceiver \
 *       --es title "Song" --es artist "Artist" --el durationMs 240000 --el startMs 30000
 *   adb shell am broadcast -n com.lyricsmatch/.DemoPlayerReceiver --ez stop true
 */
class DemoPlayerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        session?.release()
        session = null
        if (intent.getBooleanExtra("stop", false)) return

        val s = MediaSession(context.applicationContext, "LyricsMatchDemo")
        val playing = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_PLAY_PAUSE)
        s.setCallback(object : MediaSession.Callback() {
            override fun onSeekTo(pos: Long) {
                s.setPlaybackState(
                    playing.setState(PlaybackState.STATE_PLAYING, pos, 1f, SystemClock.elapsedRealtime()).build()
                )
            }
        })
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, intent.getStringExtra("title") ?: "Demo")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, intent.getStringExtra("artist") ?: "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, intent.getStringExtra("album"))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, intent.getLongExtra("durationMs", 0))
                .build()
        )
        s.setPlaybackState(
            playing.setState(
                PlaybackState.STATE_PLAYING,
                intent.getLongExtra("startMs", 0),
                1f,
                SystemClock.elapsedRealtime(),
            ).build()
        )
        s.isActive = true
        session = s
    }

    companion object {
        private var session: MediaSession? = null
    }
}
