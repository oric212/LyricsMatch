package com.lyricsmatch.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Learns what's playing from players' public broadcasts, which need no special permission:
 *
 * - Spotify, once "Device broadcast status" is turned on in Spotify's settings
 *   (song, artist, album, length, play/pause and position).
 * - Older players that send the classic `*.metachanged` / `*.playstatechanged` broadcasts.
 *
 * This is how the standard build works without notification access (which makes Google Play
 * Protect block sideloaded installs). The receiver lives as long as the app process.
 */
object BroadcastSource {
    private const val SPOTIFY = "com.spotify.music"
    private val LEGACY = listOf(
        "com.android.music", "com.sec.android.app.music", "com.miui.player", "com.htc.music",
        "com.sonyericsson.music", "com.andrew.apollo", "com.maxmpz.audioplayer", "com.amazon.mp3",
    )

    private var registered = false

    // Last known state (broadcasts arrive in pieces: metadata and playback separately).
    private var pkg = ""
    private var id = ""
    private var title = ""
    private var artist = ""
    private var album: String? = null
    private var durationMs = 0L
    private var playing = false
    private var positionMs = 0L
    private var updateTime = 0L

    fun register(context: Context) {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction("$SPOTIFY.metadatachanged")
            addAction("$SPOTIFY.playbackstatechanged")
            LEGACY.forEach {
                addAction("$it.metachanged")
                addAction("$it.playstatechanged")
            }
        }
        ContextCompat.registerReceiver(context.applicationContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action.startsWith(SPOTIFY)) onSpotify(action, intent) else onLegacy(action, intent)
            publish()
        }
    }

    private fun onSpotify(action: String, i: Intent) {
        pkg = SPOTIFY
        // Spotify stamps each broadcast with wall-clock time; convert to the elapsed-time clock.
        val sent = i.getLongExtra("timeSent", System.currentTimeMillis())
        val at = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - sent).coerceAtLeast(0)
        if (action.endsWith("metadatachanged")) {
            val newId = i.getStringExtra("id") ?: (i.getStringExtra("artist") + i.getStringExtra("track"))
            if (newId != id) {
                // New song: start from zero until a playback update says otherwise.
                id = newId
                positionMs = 0
                updateTime = at
            }
            title = i.getStringExtra("track") ?: title
            artist = i.getStringExtra("artist") ?: artist
            album = i.getStringExtra("album") ?: album
            durationMs = i.getIntExtra("length", durationMs.toInt()).toLong()
        } else {
            playing = i.getBooleanExtra("playing", playing)
            positionMs = i.getIntExtra("playbackPosition", positionMs.toInt()).toLong()
            updateTime = at
        }
    }

    private fun onLegacy(action: String, i: Intent) {
        pkg = action.substringBeforeLast('.')
        i.getStringExtra("track")?.let { newTitle ->
            val newArtist = i.getStringExtra("artist") ?: ""
            if (newTitle != title || newArtist != artist) {
                positionMs = 0
                updateTime = SystemClock.elapsedRealtime()
            }
            title = newTitle
            artist = newArtist
            album = i.getStringExtra("album")
        }
        val duration = i.getLongExtra("duration", -1)
        if (duration > 0) durationMs = duration
        if (i.hasExtra("playing")) playing = i.getBooleanExtra("playing", playing)
        val position = i.getLongExtra("position", -1)
        if (position >= 0) {
            positionMs = position
            updateTime = SystemClock.elapsedRealtime()
        }
    }

    private fun publish() {
        if (title.isBlank()) return
        if (updateTime == 0L) updateTime = SystemClock.elapsedRealtime()
        MediaRepository.updateFromBroadcast(
            NowPlaying(
                packageName = pkg,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                isPlaying = playing,
                positionMs = positionMs,
                updateTime = updateTime,
                speed = 1f,
            )
        )
    }
}
