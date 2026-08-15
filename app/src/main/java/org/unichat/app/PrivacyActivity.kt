package org.unichat.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat

/**
 * Account privacy settings (who can see last seen/online, profile photo,
 * about, and the read-receipts toggle). Values live on the WhatsApp account,
 * so changes made here apply to all linked devices; the screen always fetches
 * fresh values on entry.
 */
class PrivacyActivity : BaseActivity() {

    private lateinit var valueLastSeen: TextView
    private lateinit var valueProfile: TextView
    private lateinit var valueAbout: TextView
    private lateinit var switchReadReceipts: SwitchCompat

    private val settings = HashMap<String, String>()
    private var loaded = false
    private var proto: String = ProtoPicker.WA
    private val isTg get() = proto == ProtoPicker.TG
    // guards the switch listener against programmatic check changes
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proto = intent.getStringExtra("proto") ?: ProtoPicker.WA
        // this screen belongs to one account, so it wears that
        // protocol's accent; must precede any view inflation
        applyProtocolTheme(isTg)
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

        load()
    }

    private fun load() {
        Bridge.fetchPrivacySettings(proto) { map -> onLoaded(map) }
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

    // Everyone / My contacts / Nobody chooser for a standard setting.
    // ("My contacts except…" needs an exception list the protocol manages
    // elsewhere, so it is displayed when active but not offered here.)
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

    // WhatsApp pairs these on one row: pick who sees the last seen, then who
    // sees the online state (Everyone or the same audience as last seen).
    // Telegram has no separate "online" audience, so the chained dialog is skipped.
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
        Bridge.setPrivacySetting(proto, name, value) { ok -> onApplied(name, value, ok, onDone) }
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
