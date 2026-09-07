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

    private fun gone(): Boolean = isFinishing || isDestroyed

    override fun onDestroy() {
        super.onDestroy()
        (captcha.parent as? android.view.ViewGroup)?.removeView(captcha)
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
        findViewById<View>(R.id.sgLinkInstead).visibility = View.GONE
        busy(true, R.string.signal_register_starting)
        Signal.registerStart(number) { err ->
            if (gone()) return@registerStart
            if (err.isNotEmpty()) return@registerStart fail(err)
            if (Signal.needsCaptcha()) showCaptcha() else requestCode()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showCaptcha() {
        status.setText(R.string.signal_captcha_prompt)
        form.visibility = View.GONE
        captcha.visibility = View.VISIBLE
        captcha.settings.javaScriptEnabled = true
        captcha.webViewClient = object : WebViewClient() {
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
