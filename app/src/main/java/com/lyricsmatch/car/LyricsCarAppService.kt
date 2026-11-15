package com.lyricsmatch.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Optional car-display view. Runs on the phone like any Android Auto app and projects
 * template-based UI to the head unit; nothing is installed on the car.
 * Sideloaded builds need "Unknown sources" enabled in Android Auto's developer settings.
 */
class LyricsCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = LyricsScreen(carContext)
    }
}
