package com.lyricsmatch

import android.app.Application
import com.lyricsmatch.lyrics.Karaoke
import com.lyricsmatch.media.BroadcastSource

class KaraokeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Karaoke.init(this)
        // Permission-free song source (Spotify's "Device broadcast status" and older players).
        BroadcastSource.register(this)
    }
}
