package org.unichat.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
        AudioPlayer.init(this)
        LinkPreview.init(this)
        Bridge.warmUp(this)
        Io.files.execute { Search.fold('á') }
        Signal.init(this)
    }
}
