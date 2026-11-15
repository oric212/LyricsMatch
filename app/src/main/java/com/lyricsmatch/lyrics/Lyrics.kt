package com.lyricsmatch.lyrics

import com.lyricsmatch.media.NowPlaying

data class LyricLine(val timeMs: Long, val text: String)

sealed interface LyricsStatus {
    data object Idle : LyricsStatus
    data object Loading : LyricsStatus
    data class Synced(val lines: List<LyricLine>) : LyricsStatus
    data class Plain(val text: String) : LyricsStatus
    data object Instrumental : LyricsStatus
    data object NotFound : LyricsStatus
    data class Error(val message: String) : LyricsStatus
}

data class KaraokeState(
    val track: NowPlaying? = null,
    val status: LyricsStatus = LyricsStatus.Idle,
    /** Index into [LyricsStatus.Synced.lines]; -1 before the first line. */
    val currentLine: Int = -1,
)
