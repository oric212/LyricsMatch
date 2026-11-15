package com.lyricsmatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, intent.getStringExtra("title") ?: "Demo")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, intent.getStringExtra("artist") ?: "")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, intent.getStringExtra("album"))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, intent.getLongExtra("durationMs", 0))
        // --ez noArt true simulates a player without album art (tests the online cover lookup).
        if (!intent.getBooleanExtra("noArt", false)) {
            meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, demoCover(intent.getStringExtra("title") ?: ""))
        }
        s.setMetadata(meta.build())
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

    /** Generated stand-in album cover (a warm gradient), so demos show artwork and tint. */
    private fun demoCover(seed: String): Bitmap {
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val hue = (seed.hashCode() and 0x7fffffff) % 360f
        val c1 = Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.95f))
        val c2 = Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.85f, 0.45f))
        Canvas(bmp).drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 300f, 300f, c1, c2, Shader.TileMode.CLAMP)
        })
        return bmp
    }

    companion object {
        private var session: MediaSession? = null
    }
}
