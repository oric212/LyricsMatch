package com.lyricsmatch

import android.app.Application
import com.lyricsmatch.lyrics.Karaoke

class KaraokeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Karaoke.init(this)
    }
}
