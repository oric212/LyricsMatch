package com.lyricsmatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.lyricsmatch.media.AudioSpectrum
import com.lyricsmatch.ui.EqualizerOptions
import androidx.activity.enableEdgeToEdge
import androidx.car.app.connection.CarConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyricsmatch.lyrics.Karaoke
import com.lyricsmatch.media.MediaListenerService
import com.lyricsmatch.media.MediaRepository
import com.lyricsmatch.ui.App

class MainActivity : ComponentActivity() {

    private var hasAccess by mutableStateOf(false)

    // Optional live equalizer (needs "record audio"; falls back to beat-style without it).
    private val spectrum = AudioSpectrum()
    private var micGranted by mutableStateOf(false)
    private var eqPromptDismissed by mutableStateOf(false)
    private val prefs by lazy { getSharedPreferences("lyricsmatch", MODE_PRIVATE) }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) spectrum.start() else dismissEqPrompt()
    }

    private fun dismissEqPrompt() {
        eqPromptDismissed = true
        prefs.edit().putBoolean("eq_prompt_dismissed", true).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always-dark UI: force light status/nav bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val carConnection = CarConnection(this).type
        eqPromptDismissed = prefs.getBoolean("eq_prompt_dismissed", false)

        setContent {
            val connection by carConnection.observeAsState(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            val state by Karaoke.state.collectAsStateWithLifecycle()
            val offset by Karaoke.offsetMs.collectAsStateWithLifecycle()
            val artwork by MediaRepository.artwork.collectAsStateWithLifecycle()
            // Manual override so car view can be used (or tested) without Android Auto.
            var manualCarView by rememberSaveable { mutableStateOf(false) }
            val inCar = connection == CarConnection.CONNECTION_TYPE_PROJECTION

            App(
                hasAccess = hasAccess,
                carView = inCar || manualCarView,
                autoCarView = inCar,
                state = state,
                artwork = artwork?.takeIf { it.trackKey == state.track?.trackKey },
                offsetMs = offset,
                onGrantAccess = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                onToggleCarView = { manualCarView = !manualCarView },
                equalizer = EqualizerOptions(
                    spectrum = spectrum.takeIf { micGranted },
                    showPrompt = !micGranted && !eqPromptDismissed,
                    onAllow = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    onDismiss = ::dismissEqPrompt,
                ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hasAccess = MediaListenerService.hasAccess(this)
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted) spectrum.start()
    }

    override fun onPause() {
        super.onPause()
        spectrum.stop() // only listens while the app is on screen
    }
}
