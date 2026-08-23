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
    private val keys get() = account.privacyKeys
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        proto = intent.getStringExtra("proto") ?: ProtoPicker.WA
        // this screen belongs to one account, so it wears that
        // protocol's accent; must precede any view inflation
        applyProtocolTheme(proto)
        setContentView(R.layout.activity_privacy)
        supportActionBar?.apply {
            title = getString(R.string.privacy) + " — " + account.label(this@PrivacyActivity)
            setDisplayHomeAsUpEnabled(true)
        }
        if (!Bridge.init(this)) { finish(); return }

        valueLastSeen = findViewById(R.id.valueLastSeen)
        valueProfile = findViewById(R.id.valueProfile)
        valueAbout = findViewById(R.id.valueAbout)
        switchReadReceipts = findViewById(R.id.switchReadReceipts)

        // Each account says which rows it has; the rest are hidden rather than
        // shown as options that cannot be applied.
        val rowLastSeen = findViewById<View>(R.id.rowLastSeen)
        val rowProfile = findViewById<View>(R.id.rowProfile)
        val rowAbout = findViewById<View>(R.id.rowAbout)

        rowLastSeen.visibility = if ("last" in keys) View.VISIBLE else View.GONE
        rowLastSeen.setOnClickListener { chooseLastSeenAndOnline() }
        rowAbout.visibility = if ("status" in keys) View.VISIBLE else View.GONE
        rowAbout.setOnClickListener { chooseThreeWay(R.string.privacy_about, "status") }

        // Signal has no per-audience choices — a profile is visible to anyone
        // you message — so its one profile-shaped question takes this row.
        when {
            "profile" in keys -> rowProfile.setOnClickListener {
                chooseThreeWay(R.string.privacy_profile_photo, "profile")
            }
            "discoverable" in keys -> {
                findViewById<TextView>(R.id.labelProfile)?.setText(R.string.privacy_sg_discoverable)
                rowProfile.setOnClickListener {
                    choose(R.string.privacy_sg_discoverable, "discoverable", listOf("all", "none"))
                }
            }
            else -> rowProfile.visibility = View.GONE
        }

        if ("readreceipts" in keys) {
            switchReadReceipts.setOnCheckedChangeListener { _, checked ->
                if (!rendering && loaded) apply("readreceipts", if (checked) "all" else "none")
            }
        } else {
            switchReadReceipts.visibility = View.GONE
            (switchReadReceipts.parent as? View)?.visibility = View.GONE
        }

        load()
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
        valueProfile.text = label(settings[if ("discoverable" in keys) "discoverable" else "profile"])
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

    // Telegram derives "who can see me online" from the last-seen answer, so
    // only an account that says it has the follow-up is asked for it.
    private fun chooseLastSeenAndOnline() {
        if (!loaded) return
        if ("online" !in keys) {
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
