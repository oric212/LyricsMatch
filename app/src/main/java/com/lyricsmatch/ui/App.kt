package com.lyricsmatch.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.lyricsmatch.BuildConfig
import com.lyricsmatch.media.Artwork
import com.lyricsmatch.lyrics.Bidi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.lyricsmatch.media.AudioSpectrum
import com.lyricsmatch.media.MediaRepository
import com.lyricsmatch.media.NowPlaying
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
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
    carView: Boolean,
    autoCarView: Boolean,
    state: KaraokeState,
    artwork: Artwork?,
    offsetMs: Long,
    onToggleCarView: () -> Unit,
    equalizer: EqualizerOptions,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background)) {
        // Keep the screen awake while singing along.
        KeepScreenOn(state.track?.isPlaying == true && state.status is LyricsStatus.Synced)

        // Background takes the album's colour at the top and fades to near-black,
        // cross-fading when the song changes. Car view stays pure black for contrast.
        val tint by animateColorAsState(
            when {
                carView -> Color.Black
                artwork != null -> Color(artwork.tint)
                else -> Color(0xFF1C1828)
            },
            animationSpec = tween(900),
            label = "tint",
        )
        val base = if (carView) Color.Black else Background

        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to tint, 0.75f to base))
                .systemBarsPadding()
        ) {
            when {
                carView -> CarScreen(state, artwork, showExit = !autoCarView, onExit = onToggleCarView)
                else -> PhoneScreen(state, artwork, offsetMs, equalizer, onCarView = onToggleCarView)
            }
        }
    }
}

/** Optional live equalizer: [spectrum] is null until the user opts in to "record audio". */
class EqualizerOptions(
    val spectrum: AudioSpectrum?,
    val showPrompt: Boolean,
    val onAllow: () -> Unit,
    val onDismiss: () -> Unit,
)

// ---------------------------------------------------------------- phone

@Composable
private fun PhoneScreen(
    state: KaraokeState,
    artwork: Artwork?,
    offsetMs: Long,
    equalizer: EqualizerOptions,
    onCarView: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The no-lyrics screen shows the song big in the middle, so skip it up here.
            val noWords = state.status == LyricsStatus.NotFound || state.status == LyricsStatus.Instrumental
            if (noWords) Spacer(Modifier.weight(1f))
            else TrackHeader(state, artwork, Modifier.weight(1f), titleSize = 18)
            TextButton(onClick = onCarView) { Text("Car view") }
        }
        val levels = rememberLiveLevels(state.track, equalizer.spectrum)
        // On the no-lyrics screen the big spectrum below the art is the progress bar instead.
        val noLyrics = state.status == LyricsStatus.NotFound || state.status == LyricsStatus.Instrumental
        state.track?.takeIf { it.durationMs > 0 && !noLyrics }?.let { track ->
            EqualizerBar(
                track,
                levels,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        if (equalizer.showPrompt && state.track != null) {
            EqualizerPrompt(equalizer.onAllow, equalizer.onDismiss)
        }

        Box(Modifier.weight(1f)) {
            LyricsBody(state, large = false, levels = levels, artwork = artwork)
        }

        if (state.status is LyricsStatus.Synced) {
            OffsetBar(offsetMs)
        }
    }
}

@Composable
private fun TrackHeader(state: KaraokeState, artwork: Artwork?, modifier: Modifier = Modifier, titleSize: Int) {
    val track = state.track
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val artSize = (titleSize * 3).dp
        if (artwork != null) {
            Image(
                artwork.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(artSize)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(artSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) { Text("♪", color = Dim, fontSize = titleSize.sp) }
        }
        Spacer(Modifier.width(14.dp))
        Column {
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
                    fontSize = (titleSize - 3).sp,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Small, dismissible opt-in for the live equalizer. */
@Composable
private fun EqualizerPrompt(onAllow: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(start = 14.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Make the bar react to the music?", color = Color.White, fontSize = 13.sp, maxLines = 1)
            Text(
                "Optional · analysed on your phone only",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        TextButton(onClick = onAllow) { Text("Allow", fontSize = 13.sp) }
        TextButton(onClick = onDismiss) { Text("✕", color = Dim, fontSize = 13.sp) }
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
private fun LyricsBody(state: KaraokeState, large: Boolean, levels: LiveLevels? = null, artwork: Artwork? = null) {
    // Hebrew/Arabic songs: lay the lyrics out right-to-left.
    val rtl = when (val st = state.status) {
        is LyricsStatus.Synced -> remember(st) { Bidi.isRtlSong(st.lines.map { it.text }) }
        is LyricsStatus.Plain -> remember(st) { Bidi.isRtlSong(st.text.lines()) }
        else -> false
    }
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        when (val s = state.status) {
            LyricsStatus.Idle -> Message(
                "Play a song in Spotify",
                large,
                "First time? In Spotify, tap your profile picture > Settings and privacy > Playback, " +
                    "and turn on \"Device broadcast status\". That's how LyricsMatch sees what's playing.\n\n" +
                    "Already playing? Pause and play once so Spotify announces the song.",
                showVersion = true,
            )
            LyricsStatus.Loading -> Message("Finding lyrics…", large)
            // No words to show: a live sound visual on the phone; car view stays calm text.
            LyricsStatus.Instrumental ->
                if (levels != null) NoLyricsView(levels, artwork, state.track, "Instrumental · enjoy the music")
                else Message("♪  Instrumental  ♪", large)
            LyricsStatus.NotFound ->
                if (levels != null) NoLyricsView(levels, artwork, state.track, "No lyrics for this song")
                else Message("No lyrics for this song yet", large, "They'll show up automatically for the next one")
            is LyricsStatus.Error -> Message(s.message, large, "Retrying automatically…")
            is LyricsStatus.Plain -> PlainLyrics(s.text, state.track, large)
            is LyricsStatus.Synced ->
                if (large) CarLyrics(s.lines, state.currentLine)
                else SyncedLyrics(s.lines, state.currentLine)
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, current: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        // Item 0 is the intro marker, so line i lives at item i + 1.
        LaunchedEffect(current) {
            listState.animateScrollToItem(current + 1)
        }
        LazyColumn(
            state = listState,
            // Padding keeps the active line around the upper third, like a teleprompter.
            contentPadding = PaddingValues(
                start = 24.dp, end = 24.dp,
                top = maxHeight * 0.18f, bottom = maxHeight * 0.75f,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Intro marker: holds the reading position until the first line is sung.
            item(key = "intro") { IntroRow(active = current < 0) }
            itemsIndexed(lines) { i, line ->
                LyricRow(line.text.ifBlank { "♪" }, active = i == current, past = i < current, onClick = null)
            }
        }
    }
}

@Composable
private fun IntroRow(active: Boolean) {
    val breathe by rememberInfiniteTransition(label = "intro").animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    Box(Modifier.graphicsLayer { alpha = if (active) breathe else 1f }) {
        LyricRow("•  •  •", active = active, past = !active, onClick = null)
    }
}

@Composable
private fun LyricRow(text: String, active: Boolean, past: Boolean, onClick: (() -> Unit)?) {
    // High contrast for the line being sung; the rest steps back.
    val color by animateColorAsState(
        when {
            active -> Color.White
            past -> Color.White.copy(alpha = 0.22f)
            else -> Color.White.copy(alpha = 0.4f)
        },
        label = "lineColor",
    )
    val scale by animateFloatAsState(if (active) 1f else 0.86f, label = "lineScale")
    val glow by animateFloatAsState(if (active) 1f else 0f, label = "lineGlow")
    val originX = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else 0f
    Text(
        text,
        color = color,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold, // same weight for all lines so wrapping never jumps
        style = TextStyle(shadow = Shadow(Color.White.copy(alpha = 0.25f * glow), blurRadius = 28f * glow)),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(originX, 0.5f)
            }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

/**
 * Lyrics without timing: a clear "not synced" label, line-by-line layout with verse gaps,
 * and a gentle auto-scroll that follows the song's progress (pauses while you scroll).
 */
@Composable
private fun PlainLyrics(text: String, track: NowPlaying?, large: Boolean) {
    val scroll = rememberScrollState()
    var userScrollAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(scroll.isScrollInProgress) {
        if (scroll.isScrollInProgress && !autoScrolling) userScrollAt = SystemClock.elapsedRealtime()
    }
    LaunchedEffect(track, text) {
        if (track == null || track.durationMs <= 0) return@LaunchedEffect
        while (true) {
            val idle = SystemClock.elapsedRealtime() - userScrollAt > 4_000
            if (idle && scroll.maxValue > 0) {
                // Lag slightly behind the song so the line you're singing stays in view.
                val f = ((track.positionAt() - 8_000f) / track.durationMs).coerceIn(0f, 1f)
                autoScrolling = true
                scroll.animateScrollTo((f * scroll.maxValue).toInt(), tween(900))
                autoScrolling = false
            }
            if (!track.isPlaying) break
            delay(1_000)
        }
    }

    val size = if (large) 28.sp else 22.sp
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            "NOT SYNCED · SCROLLS WITH THE SONG",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.1f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(20.dp))
        text.trim().lines().forEach { line ->
            if (line.isBlank()) {
                Spacer(Modifier.height(18.dp))
            } else {
                Text(
                    line.trim(),
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = size,
                    lineHeight = size * 1.3f,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun Message(text: String, large: Boolean, hint: String? = null, showVersion: Boolean = false) {
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
        if (hint != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                hint,
                color = Dim.copy(alpha = 0.7f),
                fontSize = if (large) 18.sp else 14.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (showVersion) {
            Spacer(Modifier.height(24.dp))
            Text("Version ${BuildConfig.VERSION_NAME}", color = Dim.copy(alpha = 0.45f), fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- car view

/** Big, glanceable layout used automatically while connected to Android Auto. */
@Composable
private fun CarScreen(state: KaraokeState, artwork: Artwork?, showExit: Boolean, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackHeader(state, artwork, Modifier.weight(1f), titleSize = 20)
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

@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
