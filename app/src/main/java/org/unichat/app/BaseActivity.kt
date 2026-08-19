package org.unichat.app

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

open class BaseActivity : AppCompatActivity() {

    protected open val padForSystemBars: Boolean = true

    // resolve returns the chat id to open (String) or a string resource to
    // toast (Int); it runs on Io.lookup because it can block for up to 75s
    protected fun resolveThenOpen(progressRes: Int, resolve: () -> Any, open: (String) -> Unit) {
        android.widget.Toast
            .makeText(this, progressRes, android.widget.Toast.LENGTH_SHORT).show()
        Io.lookup.execute {
            val out = resolve()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (out) {
                    is String -> open(out)
                    is Int -> android.widget.Toast
                        .makeText(this, out, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    protected fun resolveNumberThenOpen(number: String, open: (String) -> Unit) =
        resolveThenOpen(R.string.checking_number, {
            val jid = Bridge.resolveNumber(number)
            when {
                // never say someone is not on WhatsApp because we couldn't ask
                jid == Bridge.NUMBER_LOOKUP_FAILED -> R.string.number_check_failed
                jid.isEmpty() -> R.string.not_on_whatsapp
                else -> jid
            }
        }, open)

    protected fun applyProtocolTheme(isTelegram: Boolean) {
        if (!isTelegram) theme.applyStyle(R.style.ThemeOverlay_UniChat_Wa, true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onStart() {
        super.onStart()
        TimeFormat.refreshClockFormat(this)
    }

    override fun onContentChanged() {
        super.onContentChanged()
        if (!padForSystemBars) return
        val content = findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
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
