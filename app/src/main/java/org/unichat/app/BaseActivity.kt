package org.unichat.app

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

open class BaseActivity : AppCompatActivity() {

    protected open val padForSystemBars: Boolean = true

    // resolve runs on Io.lookup because it can block for up to 75s
    protected fun resolveThenOpen(progressRes: Int, resolve: () -> Any, open: (String) -> Unit) {
        // 0 means say nothing
        if (progressRes != 0) {
            android.widget.Toast
                .makeText(this, progressRes, android.widget.Toast.LENGTH_SHORT).show()
        }
        Io.lookup.execute {
            // an uncaught throwable in an execute()d Runnable kills the process
            val out = try {
                resolve()
            } catch (e: Exception) {
                android.util.Log.w("BaseActivity", "resolve failed", e)
                R.string.number_check_failed
            }
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

    protected fun resolveNumberThenOpen(
        account: Account, number: String, open: (String) -> Unit,
    ) = resolveThenOpen(R.string.checking_number, {
        val id = account.chatIdForNumber(number)
        when {
            // never say someone is not on the network because we couldn't ask
            id == Bridge.NUMBER_LOOKUP_FAILED -> R.string.number_check_failed
            id.isEmpty() -> account.notOnNetworkRes
            else -> id
        }
    }, open)

    /**
     * Keyed on the protocol, not on a two-way "is it Telegram" flag: under that
     * flag every non-Telegram chat took the WhatsApp overlay, so Signal chats
     * came out green.
     */
    protected fun applyProtocolTheme(proto: String) = applyProtocolTheme(Accounts.of(proto))

    protected fun applyProtocolTheme(account: Account) {
        account.themeOverlayRes?.let { theme.applyStyle(it, true) }
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
        // the full current configuration pins its orientation/dimensions, and
        // since activities handle rotation instead of being recreated, dialogs
        // and resource lookups then keep seeing the pre-rotation size.
        val override = Configuration()
        override.fontScale = Prefs.fontScale(newBase)
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }
}
