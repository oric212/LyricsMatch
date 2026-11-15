package com.lyricsmatch

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
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
import com.lyricsmatch.ui.App

class MainActivity : ComponentActivity() {

    private var hasAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always-dark UI: force light status/nav bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val carConnection = CarConnection(this).type

        setContent {
            val connection by carConnection.observeAsState(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
            val state by Karaoke.state.collectAsStateWithLifecycle()
            val offset by Karaoke.offsetMs.collectAsStateWithLifecycle()
            // Manual override so car view can be used (or tested) without Android Auto.
            var manualCarView by rememberSaveable { mutableStateOf(false) }
            val inCar = connection == CarConnection.CONNECTION_TYPE_PROJECTION

            App(
                hasAccess = hasAccess,
                carView = inCar || manualCarView,
                autoCarView = inCar,
                state = state,
                offsetMs = offset,
                onGrantAccess = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                onToggleCarView = { manualCarView = !manualCarView },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hasAccess = MediaListenerService.hasAccess(this)
    }
}
