package com.lyricsmatch.media

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Album art for the current song (looked up online), plus a background tint picked from it. */
class Artwork(val trackKey: String, val bitmap: Bitmap, val tint: Int)

/** Holds the song that's playing; fed by [BroadcastSource]. */
object MediaRepository {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _artwork = MutableStateFlow<Artwork?>(null)
    val artwork: StateFlow<Artwork?> = _artwork.asStateFlow()

    /** Song info from a player's public broadcast (Spotify's "Device broadcast status"). */
    fun updateFromBroadcast(np: NowPlaying?) {
        _nowPlaying.value = np
        when {
            np == null -> _artwork.value = null
            _artwork.value?.trackKey != np.trackKey -> {
                _artwork.value = null
                fetchArtwork(np) // broadcasts carry no album art
            }
        }
    }

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var fetchingKey: String? = null

    /** No art from the player (or just a generic logo): look the cover up online. */
    private fun fetchArtwork(np: NowPlaying) {
        if (fetchingKey == np.trackKey) return
        fetchingKey = np.trackKey
        io.launch {
            try {
                delay(1_500) // players sometimes send the art a moment after the title
                if (_nowPlaying.value?.trackKey != np.trackKey || _artwork.value?.trackKey == np.trackKey) return@launch
                val bmp = ArtworkFinder.find(np.artist, np.title) ?: return@launch
                if (_nowPlaying.value?.trackKey == np.trackKey && _artwork.value == null) {
                    _artwork.value = Artwork(np.trackKey, bmp, tintOf(bmp))
                }
            } finally {
                // Allow a fresh lookup if the same song comes back later (results are cached).
                if (fetchingKey == np.trackKey) fetchingKey = null
            }
        }
    }

    /** Saturation-weighted average colour, pushed to a dark, rich shade that keeps white text readable. */
    private fun tintOf(art: Bitmap): Int = try {
        val soft = if (art.config == Bitmap.Config.HARDWARE) art.copy(Bitmap.Config.ARGB_8888, false) else art
        val small = Bitmap.createScaledBitmap(soft, 12, 12, true)
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val p = small.getPixel(x, y)
            Color.colorToHSV(p, hsv)
            val weight = hsv[1] * hsv[2] + 0.05
            r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight
        }
        Color.colorToHSV(Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt()), hsv)
        hsv[1] = (hsv[1] * 1.25f).coerceAtMost(0.85f)
        hsv[2] = 0.38f
        Color.HSVToColor(hsv)
    } catch (_: RuntimeException) {
        Color.rgb(0x26, 0x21, 0x36)
    }
}
