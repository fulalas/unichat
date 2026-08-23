package org.unichat.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

class LoginActivity : BaseActivity(), Bridge.UiListener {

    private lateinit var tabWhatsApp: Button
    private lateinit var tabTelegram: Button
    private lateinit var waPanel: View
    private lateinit var tgPanel: View

    private lateinit var qrImage: ImageView
    private lateinit var qrProgress: ProgressBar
    private lateinit var pairCodeHint: TextView
    private lateinit var pairCodeText: TextView
    private lateinit var phoneInput: EditText
    private lateinit var pairButton: Button
    private lateinit var statusText: TextView

    private lateinit var tgPhoneInput: EditText
    private lateinit var tgSendCodeButton: Button
    private lateinit var tgCodeInput: EditText
    private lateinit var tgVerifyButton: Button
    private lateinit var tgPasswordInput: EditText
    private lateinit var tgPasswordButton: Button
    private lateinit var continueButton: Button

    private var showingTg = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        supportActionBar?.hide()

        tabWhatsApp = findViewById(R.id.tabWhatsApp)
        tabTelegram = findViewById(R.id.tabTelegram)
        waPanel = findViewById(R.id.waPanel)
        tgPanel = findViewById(R.id.tgPanel)

        qrImage = findViewById(R.id.qrImage)
        qrProgress = findViewById(R.id.qrProgress)
        pairCodeHint = findViewById(R.id.pairCodeHint)
        pairCodeText = findViewById(R.id.pairCode)
        phoneInput = findViewById(R.id.phoneInput)
        pairButton = findViewById(R.id.pairButton)
        statusText = findViewById(R.id.statusText)

        tgPhoneInput = findViewById(R.id.tgPhoneInput)
        tgSendCodeButton = findViewById(R.id.tgSendCodeButton)
        tgCodeInput = findViewById(R.id.tgCodeInput)
        tgVerifyButton = findViewById(R.id.tgVerifyButton)
        tgPasswordInput = findViewById(R.id.tgPasswordInput)
        tgPasswordButton = findViewById(R.id.tgPasswordButton)
        continueButton = findViewById(R.id.continueButton)
        continueButton.setOnClickListener { goToMain() }

        if (!Bridge.init(this)) {
            statusText.text = getString(R.string.state_disconnected)
            return
        }
        val onlyTgMissing = Bridge.hasSession() && !Tg.hasSession()
        // WhatsApp already linked at open: its repeated "connected" state
        // events are not a fresh link and must not close this screen
        waLinkedHandled = Bridge.hasSession()

        tabWhatsApp.setOnClickListener { showProtocol(tg = false) }
        tabTelegram.setOnClickListener { showProtocol(tg = true) }

        pairButton.setOnClickListener {
            val phone = phoneInput.text.toString().filter { it.isDigit() }
            if (phone.length < 8) {
                Toast.makeText(this, R.string.phone_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pairButton.isEnabled = false
            statusText.text = getString(R.string.pair_requesting)
            Bridge.requestPairCode(phone)
        }

        pairCodeText.setOnLongClickListener {
            if (lastPairCode.isNotEmpty()) copyPairCode(lastPairCode)
            true
        }

        tgSendCodeButton.setOnClickListener {
            val phone = tgPhoneInput.text.toString().trim()
            if (phone.length < 8) {
                Toast.makeText(this, R.string.tg_phone_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tgSendCodeButton.isEnabled = false
            statusText.text = getString(R.string.tg_waiting_code)
            Tg.startPhoneLogin(phone)
        }
        tgVerifyButton.setOnClickListener {
            val code = tgCodeInput.text.toString().trim()
            if (code.isEmpty()) return@setOnClickListener
            tgVerifyButton.isEnabled = false
            Tg.submitCode(code)
        }
        tgPasswordButton.setOnClickListener {
            val pw = tgPasswordInput.text.toString()
            if (pw.isEmpty()) return@setOnClickListener
            tgPasswordButton.isEnabled = false
            Tg.submitPassword(pw)
        }

        statusText.text = getString(R.string.login_waiting)
        Bridge.addListener(this)
        // Only start the WhatsApp QR socket when WhatsApp still needs linking;
        // rendering the Telegram panel doesn't need it.
        if (!Bridge.hasSession()) Bridge.startQrLogin()
        continueButton.visibility = if (Bridge.hasAnySession()) View.VISIBLE else View.GONE
        showProtocol(tg = onlyTgMissing)
    }

    /**
     * One protocol just linked. Leaving right away used to make it impossible
     * to link the second account in the same sitting — so when the other
     * protocol is still missing (and this is the first-run screen, not the
     * "Link account" one), stay here, switch to its tab and offer "Open chats".
     */
    private fun onProtocolLinked(linkedTg: Boolean) {
        WmService.start(this)
        val otherMissing = if (linkedTg) !Bridge.hasSession() else !Tg.hasSession()
        if (!otherMissing || !isTaskRoot) {
            goToMain()
            return
        }
        continueButton.visibility = View.VISIBLE
        val linked = getString(if (linkedTg) R.string.telegram else R.string.whatsapp)
        val other = getString(if (linkedTg) R.string.whatsapp else R.string.telegram)
        Toast.makeText(this, getString(R.string.link_other_hint, linked, other), Toast.LENGTH_LONG).show()
        showProtocol(tg = !linkedTg)
        statusText.text = getString(R.string.link_other_hint, linked, other)
    }

    private fun showProtocol(tg: Boolean) {
        showingTg = tg
        waPanel.visibility = if (tg) View.GONE else View.VISIBLE
        tgPanel.visibility = if (tg) View.VISIBLE else View.GONE
        tabWhatsApp.setTextColor(getColor(if (tg) R.color.text_secondary else R.color.accent))
        tabTelegram.setTextColor(getColor(if (tg) R.color.accent else R.color.text_secondary))
        statusText.text = if (tg) tgStatusForState(Tg.authState) else getString(R.string.login_waiting)
        // renderStep, not renderTgAuth: the latter also owns the "ready"
        // transition, so simply switching to this tab with Telegram already
        // linked re-entered onProtocolLinked — bouncing back to the WhatsApp
        // tab, or finishing the screen outright. Linking is an event, not
        // something a tab tap should replay.
        if (tg) renderStep(currentTgUiState())
    }

    private fun currentTgUiState(): String = when (Tg.authState) {
        "authorizationStateWaitCode" -> "wait_code"
        "authorizationStateWaitPassword" -> "wait_password"
        "authorizationStateReady" -> "ready"
        else -> "wait_phone"
    }

    private fun tgStatusForState(state: String): String = when (state) {
        "authorizationStateWaitCode" -> getString(R.string.tg_waiting_code)
        "authorizationStateWaitPassword" -> getString(R.string.tg_waiting_password)
        else -> ""
    }

    private var lastPairCode: String = ""

    private fun copyPairCode(code: String) {
        copyToClipboard("pairing code", code, R.string.pair_code_copied)
    }

    override fun onDestroy() {
        super.onDestroy()
        Bridge.removeListener(this)
        if (isFinishing && !Bridge.hasSession()) Bridge.stopLogin()
    }

    override fun onQrCode(code: String) {
        // The encode plus a 512x512 IntArray used to run inline on the main
        // thread on every QR rotation (~20-30s apart for the whole screen).
        Io.executor.execute {
            val bitmap = renderQr(code, 512)
            runOnUiThread {
                if (isFinishing || bitmap == null) return@runOnUiThread
                qrProgress.visibility = View.GONE
                qrImage.visibility = View.VISIBLE
                qrImage.setImageBitmap(bitmap)
                if (!showingTg) statusText.text = ""
            }
        }
    }

    override fun onPairCode(code: String) {
        pairCodeHint.visibility = View.VISIBLE
        pairCodeText.visibility = View.VISIBLE
        pairCodeText.text = if (code.length == 8) code.substring(0, 4) + "-" + code.substring(4) else code
        lastPairCode = code
        copyPairCode(code)
        pairButton.isEnabled = true
        if (!showingTg) statusText.text = ""
    }

    override fun onPairError(code: String) {
        pairButton.isEnabled = true
        val message = when {
            code == "short" -> getString(R.string.pair_phone_too_short)
            code == "international" -> getString(R.string.pair_phone_not_international)
            code == "notconnected" -> getString(R.string.pair_not_connected)
            code.startsWith("other:") ->
                getString(R.string.pair_failed, code.removePrefix("other:"))
            else -> getString(R.string.pair_failed, code)
        }
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onTgAuth(state: String, message: String) {
        // A rejected phone/code/password: TDLib's error answers the request but
        // changes no authorization state, so without this the button stayed
        // disabled and the screen was stuck until it was reopened.
        if (state.endsWith("_failed")) {
            tgSendCodeButton.isEnabled = true
            tgVerifyButton.isEnabled = true
            tgPasswordButton.isEnabled = true
            val text = Tg.authErrorText(this, message)
            statusText.text = text
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            return
        }
        if (state == "ready") {
            onProtocolLinked(linkedTg = true)
            return
        }
        renderStep(state)
    }

    private fun renderStep(state: String) {
        when (state) {
            "wait_phone" -> {
                tgSendCodeButton.isEnabled = true
                tgCodeInput.visibility = View.GONE
                tgVerifyButton.visibility = View.GONE
                tgPasswordInput.visibility = View.GONE
                tgPasswordButton.visibility = View.GONE
            }
            "wait_code" -> {
                tgSendCodeButton.isEnabled = true
                tgCodeInput.visibility = View.VISIBLE
                tgVerifyButton.visibility = View.VISIBLE
                tgVerifyButton.isEnabled = true
                if (showingTg) statusText.text = getString(R.string.tg_waiting_code)
            }
            "wait_password" -> {
                tgVerifyButton.isEnabled = true
                tgPasswordInput.visibility = View.VISIBLE
                tgPasswordButton.visibility = View.VISIBLE
                tgPasswordButton.isEnabled = true
                if (showingTg) statusText.text = getString(R.string.tg_waiting_password)
            }
        }
    }

    override fun onStateChanged(state: String) {
        when (state) {
            // ignore repeats: once WhatsApp is linked, later state events must
            // not re-trigger the linked flow (e.g. while the user is mid-way
            // through the Telegram form)
            "connected" -> if (!waLinkedHandled) {
                waLinkedHandled = true
                onProtocolLinked(linkedTg = false)
            }
            "connecting" -> if (!showingTg) statusText.text = getString(R.string.login_waiting)
            "disconnected" -> if (!showingTg) statusText.text = getString(R.string.state_disconnected)
            "outdated" -> statusText.text = getString(R.string.state_outdated)
            // every state is mapped explicitly so internal tokens (e.g.
            // "logged_out", the expected state on this screen) never show raw
            else -> if (!showingTg) statusText.text = ""
        }
    }

    // The bridge repeats state events with no same-state dedup, and this
    // listener stays registered until onDestroy (which lags finish()), so a
    // second "connected" would otherwise start MainActivity — and the service —
    // twice.
    private var leaving = false
    private var waLinkedHandled = false

    private fun goToMain() {
        if (leaving) return
        leaving = true
        WmService.start(this)
        if (!isTaskRoot) {
            finish()
            return
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun renderQr(content: String, size: Int): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
        } catch (e: Exception) {
            android.util.Log.w("LoginActivity", "QR encode failed", e)
            null
        }
    }
}
