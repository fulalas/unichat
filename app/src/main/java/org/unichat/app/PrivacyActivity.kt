package org.unichat.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat

class PrivacyActivity : BaseActivity() {

    private lateinit var valueLastSeen: TextView
    private lateinit var valueProfile: TextView
    private lateinit var valueAbout: TextView
    private lateinit var switchReadReceipts: SwitchCompat

    private val settings = HashMap<String, String>()
    private var loaded = false
    private var proto: String = ProtoPicker.WA
    private val account get() = Accounts.of(proto)
    private val isTg get() = proto == ProtoPicker.TG
    private val isSg get() = proto == ProtoPicker.SG
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proto = intent.getStringExtra("proto") ?: ProtoPicker.WA
        // this screen belongs to one account, so it wears that
        // protocol's accent; must precede any view inflation
        applyProtocolTheme(proto)
        setContentView(R.layout.activity_privacy)
        supportActionBar?.apply {
            title = getString(R.string.privacy) + " — " + ProtoPicker.label(this@PrivacyActivity, proto)
            setDisplayHomeAsUpEnabled(true)
        }
        if (!Bridge.init(this)) { finish(); return }

        valueLastSeen = findViewById(R.id.valueLastSeen)
        valueProfile = findViewById(R.id.valueProfile)
        valueAbout = findViewById(R.id.valueAbout)
        switchReadReceipts = findViewById(R.id.switchReadReceipts)

        findViewById<View>(R.id.rowLastSeen).setOnClickListener { chooseLastSeenAndOnline() }
        findViewById<View>(R.id.rowProfile).setOnClickListener {
            chooseThreeWay(R.string.privacy_profile_photo, "profile")
        }
        findViewById<View>(R.id.rowAbout).setOnClickListener {
            chooseThreeWay(R.string.privacy_about, "status")
        }
        switchReadReceipts.setOnCheckedChangeListener { _, checked ->
            if (!rendering && loaded) apply("readreceipts", if (checked) "all" else "none")
        }
        if (isTg) {
            // Telegram has no read-receipts toggle; hide the whole row
            switchReadReceipts.visibility = View.GONE
            (switchReadReceipts.parent as? View)?.visibility = View.GONE
        }

        if (isSg) {
            renderSignal()
            return
        }
        load()
    }

    /**
     * Signal's privacy model has none of the per-audience choices WhatsApp and
     * Telegram expose — a profile is visible to anyone you message, full stop.
     * The three settings it does have are shown instead of leaving the screen
     * displaying options that cannot be applied.
     *
     * Read receipts and typing indicators are honoured locally rather than
     * published: they live in the storage-service account record, which this
     * account cannot write yet.
     */
    private fun renderSignal() {
        findViewById<View>(R.id.rowLastSeen).visibility = View.GONE
        findViewById<View>(R.id.rowAbout).visibility = View.GONE

        val discoverRow = findViewById<View>(R.id.rowProfile)
        findViewById<TextView>(R.id.labelProfile)?.setText(R.string.privacy_sg_discoverable)
        valueProfile.text = getString(
            if (Prefs.sgDiscoverable(this)) R.string.privacy_everyone else R.string.privacy_nobody
        )
        discoverRow.setOnClickListener { toggleDiscoverable() }

        rendering = true
        switchReadReceipts.isChecked = Prefs.sgReadReceipts(this)
        rendering = false
        switchReadReceipts.setOnCheckedChangeListener { _, checked ->
            if (!rendering) Prefs.setSgReadReceipts(this, checked)
        }
        loaded = true
    }

    private fun toggleDiscoverable() {
        val next = !Prefs.sgDiscoverable(this)
        Signal.setDiscoverable(next) { ok ->
            if (isFinishing) return@setDiscoverable
            if (!ok) {
                Toast.makeText(this, R.string.privacy_failed, Toast.LENGTH_LONG).show()
                return@setDiscoverable
            }
            Prefs.setSgDiscoverable(this, next)
            valueProfile.text =
                getString(if (next) R.string.privacy_everyone else R.string.privacy_nobody)
        }
    }

    private fun load() {
        account.fetchPrivacySettings { map -> onLoaded(map) }
    }

    private fun onLoaded(map: Map<String, String>?) {
        if (isFinishing) return
        if (map == null) {
            Toast.makeText(this, R.string.privacy_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        settings.putAll(map)
        loaded = true
        render()
    }

    private fun render() {
        rendering = true
        valueLastSeen.text = label(settings["last"])
        valueProfile.text = label(settings["profile"])
        valueAbout.text = label(settings["status"])
        switchReadReceipts.isChecked = settings["readreceipts"] == "all"
        rendering = false
    }

    private fun label(value: String?): String = when (value) {
        "all" -> getString(R.string.privacy_everyone)
        "contacts" -> getString(R.string.privacy_contacts)
        "contact_blacklist" -> getString(R.string.privacy_contacts_except)
        "none" -> getString(R.string.privacy_nobody)
        "match_last_seen" -> getString(R.string.privacy_same_as_last_seen)
        else -> value ?: ""
    }

    private fun chooseThreeWay(titleRes: Int, name: String, onDone: (() -> Unit)? = null) {
        if (!loaded) return
        choose(titleRes, name, listOf("all", "contacts", "none"), onDone)
    }

    private fun choose(titleRes: Int, name: String, options: List<String>, onDone: (() -> Unit)? = null) {
        val labels = options.map { label(it) }.toTypedArray()
        val current = options.indexOf(settings[name])
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                apply(name, options[which], onDone)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDone?.invoke() }
            .show()
    }

    private fun chooseLastSeenAndOnline() {
        if (!loaded) return
        if (isTg) {
            chooseThreeWay(R.string.privacy_who_last_seen, "last")
            return
        }
        choose(R.string.privacy_who_last_seen, "last", listOf("all", "contacts", "none")) {
            choose(R.string.privacy_who_online, "online", listOf("all", "match_last_seen"))
        }
    }

    private fun apply(name: String, value: String, onDone: (() -> Unit)? = null) {
        if (settings[name] == value) {
            onDone?.invoke()
            return
        }
        account.setPrivacySetting(name, value) { ok -> onApplied(name, value, ok, onDone) }
    }

    private fun onApplied(name: String, value: String, ok: Boolean, onDone: (() -> Unit)?) {
        if (isFinishing) return
        if (ok) {
            settings[name] = value
        } else {
            Toast.makeText(this, R.string.privacy_failed, Toast.LENGTH_SHORT).show()
        }
        render()
        onDone?.invoke()
    }
}
