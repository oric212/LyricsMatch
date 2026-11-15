package com.lyricsmatch.lyrics

import android.content.Context
import com.lyricsmatch.media.MediaRepository
import com.lyricsmatch.media.NowPlaying
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single source of truth shared by the phone UI and the car screen:
 * which song is playing, its lyrics, and which line is being sung right now.
 */
object Karaoke {
    private val scope = MainScope()
    private lateinit var app: Context

    private val _state = MutableStateFlow(KaraokeState())
    val state: StateFlow<KaraokeState> = _state.asStateFlow()

    /** User sync correction in ms; positive shows lyrics earlier. Reset on every new song. */
    private val _offsetMs = MutableStateFlow(0L)
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    /** Lyrics tagged with the track they belong to, so a stale result never shows on a new song. */
    private val lyrics = MutableStateFlow<Pair<String, LyricsStatus>?>(null)
    private var loadJob: Job? = null

    fun init(context: Context) {
        app = context.applicationContext

        scope.launch {
            MediaRepository.nowPlaying.map { it?.trackKey }.distinctUntilChanged().collect {
                _offsetMs.value = 0
                startLoad(MediaRepository.nowPlaying.value, forceRefresh = false)
            }
        }

        scope.launch {
            combine(MediaRepository.nowPlaying, lyrics, _offsetMs) { np, ly, off -> Triple(np, ly, off) }
                .collectLatest { (np, ly, off) ->
                    // Tick only while music plays; paused state is computed once.
                    while (true) {
                        _state.value = compute(np, ly, off)
                        if (np?.isPlaying != true) break
                        delay(80)
                    }
                }
        }
    }

    fun nudgeOffset(deltaMs: Long) {
        _offsetMs.value = (_offsetMs.value + deltaMs).coerceIn(-10_000, 10_000)
    }

    private fun startLoad(np: NowPlaying?, forceRefresh: Boolean) {
        loadJob?.cancel()
        if (np == null) {
            lyrics.value = null
            return
        }
        val key = np.trackKey
        lyrics.value = key to LyricsStatus.Loading
        loadJob = scope.launch {
            if (forceRefresh) LyricsRepository.forget(app, np)
            // Network trouble: keep retrying quietly while the same song plays.
            while (true) {
                val result = LyricsRepository.load(app, np)
                lyrics.value = key to result
                if (result !is LyricsStatus.Error) break
                delay(8_000)
                LyricsRepository.forget(app, np)
            }
        }
    }

    private fun compute(np: NowPlaying?, ly: Pair<String, LyricsStatus>?, offset: Long): KaraokeState {
        if (np == null) return KaraokeState()
        val status = ly?.takeIf { it.first == np.trackKey }?.second ?: LyricsStatus.Loading
        val line = if (status is LyricsStatus.Synced) {
            LrcParser.indexAt(status.lines, np.positionAt() + offset)
        } else -1
        return KaraokeState(np, status, line)
    }
}
