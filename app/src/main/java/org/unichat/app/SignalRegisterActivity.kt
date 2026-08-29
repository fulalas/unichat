package org.unichat.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Registers this app as the Signal PRIMARY device for a phone number.
 *
 * Signal allows exactly one primary per number, so completing this unregisters
 * the official app on that number — the confirmation before the code is sent
 * says so, because it is not recoverable without re-registering there and
 * evicting UniChat in turn.
 */
class SignalRegisterActivity : BaseActivity() {

    private lateinit var phone: EditText
    private lateinit var code: EditText
    private lateinit var sendCode: Button
    private lateinit var verify: Button
    private lateinit var status: TextView
    private lateinit var captcha: WebView
    private lateinit var form: View

    private var number = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyProtocolTheme(ProtoPicker.SG)
        setContentView(R.layout.activity_signal_register)
        title = getString(R.string.signal_register_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        form = findViewById(R.id.sgFormScroll)
        phone = findViewById(R.id.sgPhone)
        code = findViewById(R.id.sgCode)
        sendCode = findViewById(R.id.sgSendCode)
        verify = findViewById(R.id.sgVerify)
        status = findViewById(R.id.sgRegStatus)
        captcha = findViewById(R.id.sgCaptcha)

        findViewById<View>(R.id.sgLinkInstead).setOnClickListener {
            startActivity(android.content.Intent(this, SignalLinkActivity::class.java))
            finish()
        }
        sendCode.setOnClickListener { confirmTakeover() }
        verify.setOnClickListener { submitCode() }
    }

    // Every register* call blocks on the network for seconds (two of them are an
    // SMS round trip), so backing out mid-flow landed the reply on dead views
    // and a dead context. The sibling PIN screen guards its callback the same way.
    private fun gone(): Boolean = isFinishing || isDestroyed

    override fun onDestroy() {
        super.onDestroy()
        // A WebView keeps this activity reachable through its own native state
        // until it is told to let go.
        captcha.destroy()
    }

    private fun confirmTakeover() {
        number = phone.text.toString().trim()
        if (!number.startsWith("+") || number.length < 8) {
            status.setText(R.string.signal_phone_invalid)
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.signal_takeover_title)
            .setMessage(getString(R.string.signal_takeover_body, number))
            .setPositiveButton(R.string.signal_takeover_confirm) { _, _ -> startSession() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startSession() {
        // The session lives in the bridge and cannot be resumed from the link
        // screen, so once a code is on its way, leaving would cost the user
        // another SMS.
        findViewById<View>(R.id.sgLinkInstead).visibility = View.GONE
        busy(true, R.string.signal_register_starting)
        Signal.registerStart(number) { err ->
            if (gone()) return@registerStart
            if (err.isNotEmpty()) return@registerStart fail(err)
            if (Signal.needsCaptcha()) showCaptcha() else requestCode()
        }
    }

    /**
     * Signal's captcha page finishes by navigating to a `signalcaptcha:` URL
     * whose payload is the token. There is no JS bridge to read it from, so the
     * only way to get it is to intercept that navigation.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun showCaptcha() {
        status.setText(R.string.signal_captcha_prompt)
        form.visibility = View.GONE
        captcha.visibility = View.VISIBLE
        captcha.settings.javaScriptEnabled = true
        captcha.webViewClient = object : WebViewClient() {
            // shouldOverrideUrlLoading, not onPageStarted: the WebView cannot
            // load a signalcaptcha: URL, and whether onPageStarted fires for a
            // scheme it will not navigate to varies by WebView version. This is
            // the documented hook for handing an unknown scheme back to the app.
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: android.webkit.WebResourceRequest?,
            ): Boolean {
                val token = request?.url?.toString() ?: return false
                if (!token.startsWith("signalcaptcha://")) return false
                onCaptchaToken(token)
                return true
            }
        }
        captcha.loadUrl(CAPTCHA_URL)
    }

    private fun onCaptchaToken(token: String) {
        captcha.visibility = View.GONE
        form.visibility = View.VISIBLE
        busy(true, R.string.signal_captcha_checking)
        Signal.registerSubmitCaptcha(token.removePrefix("signalcaptcha://")) { err ->
            if (gone()) return@registerSubmitCaptcha
            if (err.isNotEmpty()) fail(err) else requestCode()
        }
    }

    private fun requestCode() {
        busy(true, R.string.signal_sending_code)
        Signal.registerRequestCode { err ->
            if (gone()) return@registerRequestCode
            if (err.isNotEmpty()) return@registerRequestCode fail(err)
            busy(false, R.string.signal_code_sent)
            code.visibility = View.VISIBLE
            verify.visibility = View.VISIBLE
            code.requestFocus()
        }
    }

    private fun submitCode() {
        val entered = code.text.toString().trim()
        if (entered.length < 6) {
            status.setText(R.string.signal_code_invalid)
            return
        }
        busy(true, R.string.signal_registering)
        Signal.registerSubmitCode(number, entered) { err ->
            if (gone()) return@registerSubmitCode
            if (err.isNotEmpty()) return@registerSubmitCode fail(err)
            Toast.makeText(this, R.string.signal_registered, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun busy(working: Boolean, msgRes: Int) {
        status.setText(msgRes)
        sendCode.isEnabled = !working
        verify.isEnabled = !working
    }

    private fun fail(code: String) {
        sendCode.isEnabled = true
        verify.isEnabled = true
        status.text = Signal.errorText(this, code)
    }


    companion object {
        private const val CAPTCHA_URL =
            "https://signalcaptchas.org/registration/generate.html"
    }
}
