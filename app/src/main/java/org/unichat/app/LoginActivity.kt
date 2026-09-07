package org.unichat.app

import android.content.Context
import android.content.Intent
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

class LoginActivity : BaseActivity(), Bridge.UiListener {

    companion object {
        private const val EXTRA_PROTO = "proto"

        fun intent(ctx: Context, proto: String): Intent =
            Intent(ctx, LoginActivity::class.java).putExtra(EXTRA_PROTO, proto)
    }

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

    private val pending = LinkedHashSet<String>()

    private var awaitingSetup: String? = null

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
        continueButton.visibility =
            if (pending.size < Accounts.ALL.size) View.VISIBLE else View.GONE
        select(requestedProto())
    }

    override fun onResume() {
        super.onResume()
        val proto = awaitingSetup ?: return
        awaitingSetup = null
        if (Accounts.of(proto).isLinked()) claimLinked(proto)
    }

    private fun claimLinked(proto: String) {
        if (pending.remove(proto)) onLinked(proto)
    }

    private fun requestedProto(): String {
        val asked = intent.getStringExtra(EXTRA_PROTO)
        if (asked != null && asked in panels && asked in pending) return asked
        return pending.firstOrNull { it in panels } ?: ProtoPicker.WA
    }

    private fun buildTabs() {
        val inflater = LayoutInflater.from(this)
        tabRow.removeAllViews()
        tabs.clear()
        for (proto in pending) {
            val tab = inflater.inflate(R.layout.item_login_tab, tabRow, false) as Button
            if (tabRow.childCount == 0) {
                (tab.layoutParams as LinearLayout.LayoutParams).marginStart = 0
            }
            tab.text = Accounts.of(proto).label(this)
            tab.setOnClickListener { select(proto) }
            tabRow.addView(tab)
            tabs[proto] = tab
        }
    }

    private fun select(proto: String) {
        if (proto !in panels) {
            awaitingSetup = proto
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
        if (proto == ProtoPicker.WA && proto in pending && !qrStarted) {
            qrStarted = true
            Bridge.startQrLogin()
        }
        statusText.text = when {
            proto == ProtoPicker.TG -> tgStatusForState(Tg.authState)
            Bridge.state == "outdated" -> getString(R.string.state_outdated)
            Bridge.state == "store_broken" -> getString(R.string.state_store_broken)
            else -> getString(R.string.login_waiting)
        }
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

    override fun onQrCode(proto: String, code: String) {
        if (proto != ProtoPicker.WA) return
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

    override fun onPairError(proto: String, code: String) {
        if (proto != ProtoPicker.WA) return
        pairButton.isEnabled = true
        val message = when {
            code == "short" -> getString(R.string.pair_phone_too_short)
            code == "international" -> getString(R.string.pair_phone_not_international)
            code == "notconnected" -> getString(R.string.pair_not_connected)
            code.startsWith("other:") ->
                getString(R.string.pair_failed, code.removePrefix("other:"))
            else -> getString(R.string.pair_failed, code)
        }
        if (showing == ProtoPicker.WA) statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onTgAuth(state: String, message: String) {
        if (state.endsWith("_failed")) {
            tgSendCodeButton.isEnabled = true
            tgVerifyButton.isEnabled = true
            tgPasswordButton.isEnabled = true
            val text = Tg.authErrorText(this, message)
            if (showing == ProtoPicker.TG) statusText.text = text
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            return
        }
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
        if (proto == ProtoPicker.WA && state == "connected") {
            claimLinked(proto)
            return
        }
        if (proto != ProtoPicker.WA) return
        if (state == "outdated") {
            statusText.text = getString(R.string.state_outdated)
            return
        }
        if (state == "store_broken") {
            statusText.text = getString(R.string.state_store_broken)
            return
        }
        if (showing != ProtoPicker.WA) return
        statusText.text = when (state) {
            "connecting" -> getString(R.string.login_waiting)
            "disconnected" -> getString(R.string.state_disconnected)
            else -> ""
        }
    }

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

}
