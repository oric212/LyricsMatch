package com.lyricsmatch.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsmatch.lyrics.Karaoke
import com.lyricsmatch.lyrics.KaraokeState
import com.lyricsmatch.lyrics.LyricLine
import com.lyricsmatch.lyrics.LyricsStatus

private val Accent = Color(0xFFFFC857)
private val Dim = Color(0xFF8A8794)
private val Background = Color(0xFF0B0A10)

@Composable
fun App(
    hasAccess: Boolean,
    carView: Boolean,
    autoCarView: Boolean,
    state: KaraokeState,
    offsetMs: Long,
    onGrantAccess: () -> Unit,
    onToggleCarView: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background)) {
        // Keep the screen awake while singing along.
        KeepScreenOn(state.track?.isPlaying == true && state.status is LyricsStatus.Synced)

        Box(
            Modifier
                .fillMaxSize()
                .background(if (carView) Color.Black else Background)
                .systemBarsPadding()
        ) {
            when {
                !hasAccess -> PermissionScreen(onGrantAccess)
                carView -> CarScreen(state, showExit = !autoCarView, onExit = onToggleCarView)
                else -> PhoneScreen(state, offsetMs, onCarView = onToggleCarView)
            }
        }
    }
}

// ---------------------------------------------------------------- phone

@Composable
private fun PhoneScreen(state: KaraokeState, offsetMs: Long, onCarView: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackHeader(state, Modifier.weight(1f), titleSize = 18)
            TextButton(onClick = onCarView) { Text("Car view") }
        }

        Box(Modifier.weight(1f)) {
            LyricsBody(state, large = false)
        }

        if (state.status is LyricsStatus.Synced) {
            OffsetBar(offsetMs)
        }
    }
}

@Composable
private fun TrackHeader(state: KaraokeState, modifier: Modifier = Modifier, titleSize: Int) {
    val track = state.track
    Column(modifier) {
        Text(
            track?.title ?: "Nothing playing",
            fontSize = titleSize.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (track != null && track.artist.isNotBlank()) {
            Text(
                track.artist,
                fontSize = (titleSize - 4).sp,
                color = Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OffsetBar(offsetMs: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { Karaoke.nudgeOffset(-250) }) { Text("Later", color = Dim) }
        Text(
            if (offsetMs == 0L) "In sync" else "%+.2fs".format(offsetMs / 1000f),
            color = Dim,
            fontSize = 13.sp,
            modifier = Modifier.width(72.dp),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { Karaoke.nudgeOffset(+250) }) { Text("Earlier", color = Dim) }
    }
}

/** Lyrics area shared by both modes for every non-synced state. */
@Composable
private fun LyricsBody(state: KaraokeState, large: Boolean) {
    when (val s = state.status) {
        LyricsStatus.Idle -> Message("Play a song in Spotify or any music app", large)
        LyricsStatus.Loading -> Message("Finding lyrics…", large)
        LyricsStatus.Instrumental -> Message("♪  Instrumental  ♪", large)
        LyricsStatus.NotFound -> Message("No lyrics found for this song", large, retry = true)
        is LyricsStatus.Error -> Message(s.message, large, retry = true)
        is LyricsStatus.Plain -> PlainLyrics(s.text, large)
        is LyricsStatus.Synced ->
            if (large) CarLyrics(s.lines, state.currentLine)
            else SyncedLyrics(s.lines, state.currentLine)
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, current: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        LaunchedEffect(current) {
            listState.animateScrollToItem(current.coerceAtLeast(0))
        }
        LazyColumn(
            state = listState,
            // Padding keeps the active line around the upper third, like a teleprompter.
            contentPadding = PaddingValues(
                start = 24.dp, end = 24.dp,
                top = maxHeight * 0.3f, bottom = maxHeight * 0.65f,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(lines) { i, line ->
                LyricRow(line, active = i == current, past = i < current)
            }
        }
    }
}

@Composable
private fun LyricRow(line: LyricLine, active: Boolean, past: Boolean) {
    val color by animateColorAsState(
        when {
            active -> Color.White
            past -> Color.White.copy(alpha = 0.3f)
            else -> Color.White.copy(alpha = 0.55f)
        },
        label = "lineColor",
    )
    val scale by animateFloatAsState(if (active) 1f else 0.9f, label = "lineScale")
    Text(
        line.text.ifBlank { "♪" },
        color = color,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .clickable { Karaoke.seekTo(line) },
    )
}

@Composable
private fun PlainLyrics(text: String, large: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Synced lyrics unavailable — showing full text", color = Accent, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = if (large) 26.sp else 20.sp,
            lineHeight = if (large) 36.sp else 28.sp,
        )
    }
}

@Composable
private fun Message(text: String, large: Boolean, retry: Boolean = false) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = Dim,
            fontSize = if (large) 30.sp else 20.sp,
            textAlign = TextAlign.Center,
        )
        if (retry) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { Karaoke.retry() }) { Text("Try again") }
        }
    }
}

// ---------------------------------------------------------------- car view

/** Big, glanceable layout used automatically while connected to Android Auto. */
@Composable
private fun CarScreen(state: KaraokeState, showExit: Boolean, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackHeader(state, Modifier.weight(1f), titleSize = 20)
            if (showExit) TextButton(onClick = onExit) { Text("Exit") }
        }
        Box(Modifier.weight(1f)) {
            LyricsBody(state, large = true)
        }
    }
}

@Composable
private fun CarLyrics(lines: List<LyricLine>, current: Int) {
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            (slideInVertically { it / 3 } + fadeIn()) togetherWith (slideOutVertically { -it / 3 } + fadeOut())
        },
        label = "carLyrics",
        modifier = Modifier.fillMaxSize(),
    ) { index ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val now = lines.getOrNull(index)?.text?.ifBlank { "♪" } ?: "♪"
            Text(
                now,
                color = Color.White,
                fontSize = 44.sp,
                lineHeight = 52.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(28.dp))
            for (k in 1..2) {
                lines.getOrNull(index + k)?.let {
                    Text(
                        it.text.ifBlank { "♪" },
                        color = Color.White.copy(alpha = if (k == 1) 0.5f else 0.25f),
                        fontSize = 30.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- onboarding

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("LyricsMatch", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Accent)
        Spacer(Modifier.height(12.dp))
        Text(
            "Live lyrics for whatever you're playing — made for singing in the car.",
            fontSize = 18.sp,
            color = Color.White,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            "To see which song is playing, Android requires \"Notification access\". " +
                "LyricsMatch only reads the current song from your music app; it never reads your notifications.",
            fontSize = 15.sp,
            color = Dim,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGrant,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
        ) { Text("Allow access", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(20.dp))
        Text(
            "Greyed out? Open Settings › Apps › LyricsMatch › ⋮ › \"Allow restricted settings\", then try again.",
            fontSize = 13.sp,
            color = Dim,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
