package org.unichat.app

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Applies the user's font-scale preference to every screen, and keeps content
 * clear of the system bars.
 *
 * The inset handling is not optional decoration: with targetSdk 35 Android 15
 * enforces edge-to-edge, so the window extends under the status and navigation
 * bars whether or not the app opts in. Without this, the chat screen's composer
 * row (attach / mic / send) sat under the navigation bar and the search bar under
 * the status bar, and the hardcoded bar colours in themes.xml were ignored.
 */
open class BaseActivity : AppCompatActivity() {

    /** Screens that deliberately draw under the bars (the fullscreen viewer). */
    protected open val padForSystemBars: Boolean = true

    /**
     * Dresses a protocol-scoped screen in that protocol's accent. WhatsApp is
     * the overlay; Telegram is the base theme. Must run before any view is
     * inflated. Four screens applied this by hand with two different protocol
     * tests, so [ThemeColors.protocolAccent]'s rule for surfaces outside a chat
     * and this one for whole screens could disagree about the same chat.
     */
    protected fun applyProtocolTheme(isTelegram: Boolean) {
        if (!isTelegram) theme.applyStyle(R.style.ThemeOverlay_UniChat_Wa, true)
    }

    /** The toolbar's up arrow leaves the screen; overridden where it means more. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        // one place to pick up a change to the system 12/24-hour setting, which
        // every timestamp in the app is formatted with
        TimeFormat.refreshClockFormat(this)
    }

    override fun onContentChanged() {
        super.onContentChanged()
        if (!padForSystemBars) return
        val content = findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            // the IME inset is included so the composer rises with the keyboard
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this the hardware keys drive whichever stream the system last
        // decided was "active" — with nothing playing that is the ringer, so
        // pressing volume while a voice note played changed nothing audible.
        // Voice notes play on the media stream (the earpiece fallback moves
        // them to the call stream, which AudioPlayer reports).
        volumeControlStream = AudioPlayer.volumeStream
    }

    override fun onResume() {
        super.onResume()
        volumeControlStream = AudioPlayer.volumeStream
    }

    override fun attachBaseContext(newBase: Context) {
        // Override ONLY fontScale, on an otherwise-empty Configuration. Copying
        // the full current configuration would pin its orientation/dimensions,
        // which — now that activities handle rotation instead of being
        // recreated — would keep reporting the pre-rotation size to dialogs and
        // resource lookups. An empty Configuration leaves those fields unset so
        // they track the device on each rotation.
        val override = Configuration()
        override.fontScale = Prefs.fontScale(newBase)
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }
}
