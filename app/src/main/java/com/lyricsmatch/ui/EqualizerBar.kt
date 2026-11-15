package com.lyricsmatch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lyricsmatch.media.AudioSpectrum
import com.lyricsmatch.media.NowPlaying
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Smoothed sound levels shared by every visual (equalizer bar, no-lyrics spectrum).
 * Live audio when [AudioSpectrum] has a signal, beat-style motion while playing otherwise,
 * flat when paused. [frame] changes every frame so readers redraw.
 */
class LiveLevels {
    val shown = FloatArray(AudioSpectrum.BANDS)
    internal val target = FloatArray(AudioSpectrum.BANDS)
    var frame by mutableLongStateOf(0L)
        internal set

    /** Interpolated level at [pos] in 0..1 across the bands. */
    fun at(pos: Float): Float {
        val p = pos.coerceIn(0f, 1f) * (shown.size - 1)
        val a = p.toInt()
        val b = min(a + 1, shown.size - 1)
        return shown[a] + (shown[b] - shown[a]) * (p - a)
    }
}

@Composable
fun rememberLiveLevels(track: NowPlaying?, spectrum: AudioSpectrum?): LiveLevels {
    val levels = remember { LiveLevels() }
    LaunchedEffect(track, spectrum) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else min(0.1f, (now - last) / 1e9f)
                last = now
                val playing = track?.isPlaying == true
                when {
                    playing && spectrum?.hasSignal == true -> spectrum.copyLevels(levels.target)
                    playing -> beatStyle(levels.target, now / 1e9)
                    else -> levels.target.fill(0f)
                }
                // Fast rise, slower fall.
                for (i in levels.shown.indices) {
                    val k = if (levels.target[i] > levels.shown[i]) 18f else 6f
                    levels.shown[i] += (levels.target[i] - levels.shown[i]) * min(1f, k * dt)
                }
                levels.frame = now
            }
        }
    }
    return levels
}

/**
 * Song progress drawn as a row of equalizer bars: bright before the playhead, dim after.
 */
@Composable
fun EqualizerBar(
    track: NowPlaying,
    levels: LiveLevels,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(26.dp)
    ) {
        levels.frame // redraw every frame
        val w = size.width
        val h = size.height
        val barW = 3.dp.toPx()
        val gap = 2.5.dp.toPx()
        val n = max(8, ((w + gap) / (barW + gap)).toInt())
        val step = (w - barW) / (n - 1)
        val minH = 3.dp.toPx()
        val progress = if (track.durationMs > 0) (track.positionAt().toFloat() / track.durationMs).coerceIn(0f, 1f) else 0f
        val playedX = w * progress
        for (i in 0 until n) {
            val x = i * step
            val bh = minH + levels.at(i.toFloat() / (n - 1)) * (h - minH)
            val color = if (x + barW / 2 <= playedX) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.22f)
            drawRoundRect(
                color,
                topLeft = Offset(x, (h - bh) / 2),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2),
            )
        }
    }
}

/** Fallback when no audio can be read: smooth, beat-like motion. */
private fun beatStyle(target: FloatArray, t: Double) {
    val beat = 0.5 + 0.5 * max(0.0, sin(t * PI * 2 * 1.05)).pow(6)
    for (i in target.indices) {
        val wave = 0.5 + 0.5 * sin(t * (2.1 + i % 7 * 0.37) + i * 0.9)
        val shape = 1 - abs(i - target.size * 0.35) / target.size
        target[i] = (0.15 + 0.6 * wave * shape * beat).coerceIn(0.0, 1.0).toFloat()
    }
}
