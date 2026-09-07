package org.unichat.app

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

open class BaseActivity : AppCompatActivity() {

    protected open val padForSystemBars: Boolean = true

    protected fun resolveThenOpen(progressRes: Int, resolve: () -> Any, open: (String) -> Unit) {
        if (progressRes != 0) {
            android.widget.Toast
                .makeText(this, progressRes, android.widget.Toast.LENGTH_SHORT).show()
        }
        Io.lookup.execute {
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
            id == Bridge.NUMBER_LOOKUP_FAILED -> R.string.number_check_failed
            id.isEmpty() -> account.notOnNetworkRes
            else -> id
        }
    }, open)

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
        volumeControlStream = AudioPlayer.volumeStream
    }

    override fun onResume() {
        super.onResume()
        volumeControlStream = AudioPlayer.volumeStream
    }

    override fun attachBaseContext(newBase: Context) {
        val override = Configuration()
        override.fontScale = Prefs.fontScale(newBase)
        super.attachBaseContext(newBase.createConfigurationContext(override))
    }
}
