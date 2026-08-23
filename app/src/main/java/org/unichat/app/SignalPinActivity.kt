package org.unichat.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Recovers the Signal contact list by unlocking SVR with the account PIN.
 *
 * Registering this app as the primary device minted a new master key, which
 * left the account's existing storage-service manifest — the contact list,
 * groups and settings — on the server but undecryptable. The PIN is the only
 * thing that unwraps the original key.
 */
class SignalPinActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyProtocolTheme(ProtoPicker.SG)
        setContentView(R.layout.activity_signal_pin)
        title = getString(R.string.signal_pin_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val pin = findViewById<EditText>(R.id.sgPin)
        val submit = findViewById<Button>(R.id.sgPinSubmit)
        val status = findViewById<TextView>(R.id.sgPinStatus)

        submit.setOnClickListener {
            val entered = pin.text.toString().trim()
            if (entered.length < 4) {
                status.setText(R.string.signal_pin_too_short)
                return@setOnClickListener
            }
            submit.isEnabled = false
            status.setText(R.string.signal_pin_working)
            Signal.restoreFromPin(entered) { err ->
                if (isFinishing || isDestroyed) return@restoreFromPin
                if (err.isEmpty()) {
                    Toast.makeText(this, R.string.signal_pin_ok, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    submit.isEnabled = true
                    status.text = Signal.errorText(this, err)
                }
            }
        }
    }

}
