package org.unichat.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
        AudioPlayer.init(this)
        // Start the bridge here (on a worker) so opening the Go store and running
        // its migrations overlaps process startup instead of running on the main
        // thread inside the first Activity's onCreate, before the first frame.
        // Every screen still calls Bridge.init(); it is idempotent and now just
        // waits for this to land.
        Bridge.warmUp(this)
    }
}
