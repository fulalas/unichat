package org.unichat.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/**
 * Links accounts: one tab per protocol that still needs one.
 *
 * The caller says which protocol it wants (see [intent]); without that, "Link"
 * on the Telegram row used to land on the WhatsApp QR. Signal has no panel here
 * because registering as a primary device needs its own screen — its tab opens
 * that screen instead, which is also the only way a first run can reach it.
 */
class LoginActivity : BaseActivity(), Bridge.UiListener {

    companion object {
        private const val EXTRA_PROTO = "proto"

        fun intent(ctx: Context, proto: String): Intent =
            Intent(ctx, LoginActivity::class.java).putExtra(EXTRA_PROTO, proto)
    }

    // The protocols this screen links itself, resolved once. Anything else is
    // sent to its own setup activity when its tab is tapped.
    private val panels = LinkedHashMap<String, View>()
    private val tabs = LinkedHashMap<String, Button>()

    private lateinit var tabRow: LinearLayout
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

    private var showing = ProtoPicker.WA

    /** Protocols still unlinked. A link is only an event for one of these, so
     *  the repeated "connected" a live account keeps sending cannot re-trigger
     *  the just-linked flow while the user is mid-way through another form. */
    private val pending = LinkedHashSet<String>()

    private var qrStarted = false
    private var lastPairCode: String = ""
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        supportActionBar?.hide()

        tabRow = findViewById(R.id.tabRow)
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

        Accounts.ALL.filterNot { it.isLinked() }.forEach { pending.add(it.proto) }
        panels[ProtoPicker.WA] = findViewById(R.id.waPanel)
        panels[ProtoPicker.TG] = findViewById(R.id.tgPanel)
        buildTabs()
        wireWhatsApp()
        wireTelegram()

        Bridge.addListener(this)
        // Anything already linked is one this screen has nothing left to do
        // for, so there are chats to open.
        continueButton.visibility =
            if (pending.size < Accounts.ALL.size) View.VISIBLE else View.GONE
        select(requestedProto())
    }

    override fun onResume() {
        super.onResume()
        // A protocol set up on its own screen — Signal — comes back linked with
        // no event this screen listens for, so the just-linked step would never
        // run and the first run was left on the QR with no way to the chat list.
        pending.firstOrNull { Accounts.of(it).isLinked() }?.let { claimLinked(it) }
    }

    /** The one place [pending] shrinks: each protocol reports a link its own
     *  way, and every one of them lands here. */
    private fun claimLinked(proto: String) {
        if (pending.remove(proto)) onLinked(proto)
    }

    /** The protocol the caller asked for, or the first one still needing a
     *  link. Only ever one this screen has a panel for. */
    private fun requestedProto(): String {
        val asked = intent.getStringExtra(EXTRA_PROTO)
        if (asked != null && asked in panels && asked in pending) return asked
        return pending.firstOrNull { it in panels } ?: ProtoPicker.WA
    }

    // From [pending], which already knows what is unlinked, rather than asking
    // every account again — each answer is a bridge call or a prefs read.
    private fun buildTabs() {
        val inflater = LayoutInflater.from(this)
        tabRow.removeAllViews()
        tabs.clear()
        for (proto in pending) {
            val tab = inflater.inflate(R.layout.item_login_tab, tabRow, false) as Button
            tab.text = Accounts.of(proto).label(this)
            tab.setOnClickListener { select(proto) }
            tabRow.addView(tab)
            tabs[proto] = tab
        }
    }

    private fun select(proto: String) {
        if (proto !in panels) {
            startActivity(Accounts.of(proto).setupIntent(this))
            return
        }
        showing = proto
        for ((p, panel) in panels) {
            panel.visibility = if (p == proto) View.VISIBLE else View.GONE
        }
        for ((p, tab) in tabs) {
            val selected = p == proto
            tab.setTextColor(
                if (selected) protocolAccentOf(p) else getColor(R.color.text_secondary)
            )
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        // The QR socket is a live connection to WhatsApp; open it only once the
        // panel that shows the code is actually on screen. Linking Telegram
        // used to open it too, for a QR nobody was looking at.
        if (proto == ProtoPicker.WA && proto in pending && !qrStarted) {
            qrStarted = true
            Bridge.startQrLogin()
        }
        statusText.text = when {
            proto == ProtoPicker.TG -> tgStatusForState(Tg.authState)
            // Re-read rather than left behind: the bridge does not re-emit its
            // state, so coming back to this tab would otherwise replace the one
            // message that explains why the QR cannot work.
            Bridge.state == "outdated" -> getString(R.string.state_outdated)
            else -> getString(R.string.login_waiting)
        }
        // renderStep, not the "ready" path: switching to this tab with Telegram
        // already linked used to re-enter the just-linked flow, bouncing back to
        // the WhatsApp tab or finishing the screen. Linking is an event, not
        // something a tab tap replays.
        if (proto == ProtoPicker.TG) renderStep(currentTgUiState())
    }

    private fun wireWhatsApp() {
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
    }

    private fun wireTelegram() {
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
    }

    /**
     * One protocol just linked. Leaving right away made it impossible to link a
     * second account in the same sitting, so while this screen still has a
     * protocol to offer (and is the first-run screen, not the "Link account"
     * one), stay, switch to it and offer "Open chats".
     */
    private fun onLinked(proto: String) {
        WmService.start(this)
        buildTabs()
        val next = pending.firstOrNull { it in panels }
        if (next == null || !isTaskRoot) {
            goToMain()
            return
        }
        continueButton.visibility = View.VISIBLE
        val hint = getString(
            R.string.link_other_hint,
            Accounts.of(proto).label(this),
            Accounts.of(next).label(this),
        )
        Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        select(next)
        statusText.text = hint
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
                if (showing == ProtoPicker.WA) statusText.text = ""
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
        if (showing == ProtoPicker.WA) statusText.text = ""
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
        // Telegram connects before it is authorised, so a live socket says
        // nothing about the link; this is the only signal that it is done.
        if (state == "ready") {
            claimLinked(ProtoPicker.TG)
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
                if (showing == ProtoPicker.TG) statusText.text = getString(R.string.tg_waiting_code)
            }
            "wait_password" -> {
                tgVerifyButton.isEnabled = true
                tgPasswordInput.visibility = View.VISIBLE
                tgPasswordButton.visibility = View.VISIBLE
                tgPasswordButton.isEnabled = true
                if (showing == ProtoPicker.TG) {
                    statusText.text = getString(R.string.tg_waiting_password)
                }
            }
        }
    }

    override fun onAccountState(proto: String, state: String) {
        // WhatsApp only ever reports "connected" with a session in hand, so for
        // it that is the link signal.
        if (proto == ProtoPicker.WA && state == "connected") {
            claimLinked(proto)
            return
        }
        if (proto != ProtoPicker.WA) return
        // Shown whichever tab is up, and never cleared: an outdated bridge means
        // the QR will never work, and the state is not re-emitted when the user
        // comes back to the WhatsApp tab.
        if (state == "outdated") {
            statusText.text = getString(R.string.state_outdated)
            return
        }
        // Otherwise only the WhatsApp panel takes its status from the
        // connection. Telegram connects before it is authorised and keeps
        // reporting connection changes afterwards, so letting them through here
        // wiped the prompt — or the "wrong code" — onTgAuth had just put up.
        if (showing != ProtoPicker.WA) return
        statusText.text = when (state) {
            "connecting" -> getString(R.string.login_waiting)
            "disconnected" -> getString(R.string.state_disconnected)
            // every state is mapped explicitly so internal tokens (e.g.
            // "logged_out", the expected state on this screen) never show raw
            else -> ""
        }
    }

    private fun goToMain() {
        // The bridge repeats state events with no same-state dedup and this
        // listener stays registered until onDestroy (which lags finish()), so a
        // second event would otherwise start MainActivity — and the service —
        // twice.
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
