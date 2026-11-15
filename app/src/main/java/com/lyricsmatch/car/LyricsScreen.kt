package com.lyricsmatch.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lyricsmatch.lyrics.Karaoke
import com.lyricsmatch.lyrics.LyricsStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Lyrics on the car display using a PaneTemplate.
 *
 * Android Auto limits how often an app may push *new* screens, but re-sending the same
 * template type with the same title and row count counts as a refresh. So the title stays
 * fixed ("Lyrics") and there are always exactly [ROWS] rows; only their text changes.
 * The host still decides how much text is shown while the car is moving.
 */
class LyricsScreen(carContext: CarContext) : Screen(carContext) {

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Karaoke.state
                    .map { Triple(it.track?.trackKey, it.status, it.currentLine) }
                    .distinctUntilChanged()
                    .collect { invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = Karaoke.state.value
        val songLine = state.track?.let { t ->
            if (t.artist.isBlank()) t.title else "${t.title} · ${t.artist}"
        } ?: "Nothing playing"

        val pane = Pane.Builder()
        val texts: List<String> = when (val s = state.status) {
            is LyricsStatus.Synced -> {
                val i = state.currentLine
                listOf(
                    "▶ " + line(s, i),
                    line(s, i + 1),
                    line(s, i + 2),
                    songLine,
                )
            }
            is LyricsStatus.Plain -> listOf("Synced lyrics unavailable", "See full text on your phone", BLANK, songLine)
            LyricsStatus.Instrumental -> listOf("♪ Instrumental ♪", BLANK, BLANK, songLine)
            LyricsStatus.NotFound -> listOf("No lyrics for this song yet", BLANK, BLANK, songLine)
            is LyricsStatus.Error -> listOf(s.message, BLANK, BLANK, songLine)
            LyricsStatus.Loading -> listOf("Finding lyrics…", BLANK, BLANK, songLine)
            LyricsStatus.Idle -> listOf("Play a song in your music app", BLANK, BLANK, songLine)
        }
        texts.take(ROWS).forEach { pane.addRow(Row.Builder().setTitle(it).build()) }

        return PaneTemplate.Builder(pane.build())
            .setTitle("Lyrics")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun line(s: LyricsStatus.Synced, i: Int): String =
        s.lines.getOrNull(i)?.text?.ifBlank { "♪" } ?: if (i < 0) "♪" else BLANK

    private companion object {
        const val ROWS = 4
        const val BLANK = " "
    }
}
