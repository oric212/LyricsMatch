package com.lyricsmatch.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsmatch.media.Artwork
import com.lyricsmatch.media.NowPlaying
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Shown when a song has no lyrics: album art in the middle, song and artist below it,
 * and a mirrored live spectrum under that. The "no lyrics" caption fades away after a moment.
 */
@Composable
fun NoLyricsView(
    levels: LiveLevels,
    artwork: Artwork?,
    track: NowPlaying?,
    caption: String,
) {
    val art = remember(artwork) { artwork?.bitmap?.asImageBitmap() }
    var showCaption by remember(track?.trackKey) { mutableStateOf(true) }
    LaunchedEffect(track?.trackKey) {
        delay(4_000)
        showCaption = false
    }
    val captionAlpha by animateFloatAsState(if (showCaption) 1f else 0f, tween(1200), label = "caption")

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            caption,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(captionAlpha),
        )
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .size(230.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("♪", color = Color.White.copy(alpha = 0.5f), fontSize = 64.sp)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            track?.title ?: "",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!track?.artist.isNullOrBlank()) {
            Text(
                track!!.artist,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(32.dp))
        if (track != null) {
            MirrorSpectrum(levels, track, Modifier.fillMaxWidth().height(96.dp))
        }
    }
}

/**
 * The no-lyrics screen's main bar: grows up and down from a centre line (bass in the middle,
 * treble towards the edges), bright before the playhead and dim after it.
 */
@Composable
private fun MirrorSpectrum(levels: LiveLevels, track: NowPlaying, modifier: Modifier) {
    Canvas(modifier
    ) {
        levels.frame // redraw every frame
        val w = size.width
        val h = size.height
        val barW = 4.dp.toPx()
        val gap = 3.5.dp.toPx()
        var n = ((w + gap) / (barW + gap)).toInt()
        if (n % 2 == 0) n -= 1
        val step = (w - barW) / (n - 1)
        val c = (n - 1) / 2f
        val minH = 4.dp.toPx()
        val progress = if (track.durationMs > 0) (track.positionAt().toFloat() / track.durationMs).coerceIn(0f, 1f) else 0f
        val playedX = w * progress
        for (i in 0 until n) {
            val x = i * step
            val d = abs(i - c) / c // 0 centre … 1 edge
            val level = levels.at(d * 0.9f) * (1f - 0.3f * d)
            val bh = minH + level * (h - minH)
            val played = x + barW / 2 <= playedX
            drawRoundRect(
                Color.White.copy(alpha = if (played) 0.95f else 0.28f),
                topLeft = Offset(x, (h - bh) / 2),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW / 2),
            )
        }
    }
}
