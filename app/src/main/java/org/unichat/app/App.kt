package org.unichat.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private val main = Handler(Looper.getMainLooper())
            private val goOffline = Runnable { Bridge.setUiVisible(false) }
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) {
                    main.removeCallbacks(goOffline)
                    Bridge.setUiVisible(true)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) main.postDelayed(goOffline, 2000)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
