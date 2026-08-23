package org.unichat.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat

/**
 * Add, pause or remove accounts. Every protocol is listed whether or not it is
 * linked, so an unlinked one stays reachable — the old overflow entry hid
 * itself once two accounts existed, which made a third protocol unusable.
 *
 * Pausing and removing are deliberately different actions: the switch only
 * takes the account off the network, while the bin destroys its local data.
 */
class AccountsActivity : BaseActivity(), Bridge.UiListener {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)
        title = getString(R.string.manage_accounts)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        list = findViewById(R.id.accountList)
        Bridge.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (account in Accounts.ALL) {
            val proto = account.proto
            val row = inflater.inflate(R.layout.item_account, list, false)
            val linked = account.isLinked()
            val enabled = Prefs.protoEnabled(this, proto)

            row.findViewById<View>(R.id.accountDot)
                .setBackgroundColor(protocolAccentOf(proto))
            row.findViewById<TextView>(R.id.accountName).text = account.label(this)
            val state = row.findViewById<TextView>(R.id.accountState)
            state.text = when {
                !linked -> getString(R.string.account_not_linked)
                enabled -> getString(R.string.account_active)
                else -> getString(R.string.account_paused)
            }

            val link = row.findViewById<Button>(R.id.accountLink)
            val toggle = row.findViewById<SwitchCompat>(R.id.accountSwitch)
            val delete = row.findViewById<ImageButton>(R.id.accountDelete)

            // Link is the only action that makes sense before an account
            // exists; pause and remove would both have nothing to act on.
            link.visibility = if (linked) View.GONE else View.VISIBLE
            toggle.visibility = if (linked) View.VISIBLE else View.GONE
            delete.visibility = if (linked) View.VISIBLE else View.GONE

            link.setOnClickListener { startSetup(proto) }
            // Set the state before the listener, or restoring it here fires the
            // listener and immediately toggles the account again.
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = enabled
            toggle.setOnCheckedChangeListener { _, checked -> setEnabled(proto, checked) }
            delete.setOnClickListener { confirmRemove(proto) }
            // Signal only: the contact list lives behind the account PIN, and
            // registering left it locked. Offer the recovery until it is done,
            // then say so instead of asking again — the row stays tappable, so
            // it can still be run a second time.
            if (proto == ProtoPicker.SG && linked) {
                row.setOnClickListener {
                    startActivity(Intent(this, SignalPinActivity::class.java))
                }
                // Appended, not substituted: the row still has to say whether
                // the account is active or paused.
                val hint = if (Prefs.sgContactsRestored(this)) {
                    getString(R.string.signal_contacts_restored)
                } else {
                    getString(R.string.signal_restore_contacts)
                }
                state.text = "${state.text} · $hint"
            }

            list.addView(row)
        }
    }

    private fun startSetup(proto: String) {
        startActivity(Accounts.of(proto).setupIntent(this))
    }

    private fun setEnabled(proto: String, enabled: Boolean) {
        Prefs.setProtoEnabled(this, proto, enabled)
        Accounts.of(proto).setNetworkEnabled(enabled)
        render()
    }

    private fun confirmRemove(proto: String) {
        val name = Accounts.of(proto).label(this)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.account_remove_title, name))
            .setMessage(R.string.account_remove_body)
            .setPositiveButton(R.string.account_remove_confirm) { _, _ -> remove(proto, name) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun remove(proto: String, name: String) {
        Accounts.of(proto).logout()
        // Deliberately NOT deleting the protocol's directory here. Every logout
        // above only QUEUES work on that protocol's own executor, so wiping the
        // tree from this thread pulled the files out from under a still-running
        // TDLib and an open Signal sqlite handle. Each protocol clears its own
        // storage as part of logging out.
        Prefs.clearProtoEnabled(this, proto)
        Toast.makeText(this, getString(R.string.account_removed, name), Toast.LENGTH_SHORT).show()
        render()
    }


    override fun onAccountState(proto: String, state: String) = render()


    override fun onDestroy() {
        Bridge.removeListener(this)
        super.onDestroy()
    }
}
