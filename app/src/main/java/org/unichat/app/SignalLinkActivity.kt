package org.unichat.app

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

// Linking as a second device is the only setup that shows the account's own
// contact list: the main device hands over the account key, which is what that
// list is encrypted with. Registering a number instead makes this app the main
// device with a fresh key, and then only people who can be found by phone
// number ever appear.
class SignalLinkActivity : BaseActivity(), Bridge.UiListener {

    private lateinit var qr: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyProtocolTheme(ProtoPicker.SG)
        setContentView(R.layout.activity_signal_link)
        title = getString(R.string.signal_link_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        qr = findViewById(R.id.sgQr)
        progress = findViewById(R.id.sgQrProgress)
        status = findViewById(R.id.sgLinkStatus)

        findViewById<View>(R.id.sgRegisterInstead).setOnClickListener {
            startActivity(android.content.Intent(this, SignalRegisterActivity::class.java))
            finish()
        }

        Bridge.addListener(this)
        Signal.startLink()
    }

    override fun onDestroy() {
        Bridge.removeListener(this)
        // Leaving means the code on screen will never be scanned; the socket
        // would otherwise sit there until it timed out, and a second visit
        // would start a competing attempt.
        if (isFinishing && !Signal.hasSession()) Signal.stopLink()
        super.onDestroy()
    }

    override fun onQrCode(proto: String, code: String) {
        if (proto != ProtoPicker.SG) return
        // The encode plus a 512x512 IntArray is too much for the main thread.
        Io.executor.execute {
            val bitmap = renderQr(code, 512)
            runOnUiThread {
                if (isFinishing || bitmap == null) return@runOnUiThread
                progress.visibility = View.GONE
                qr.visibility = View.VISIBLE
                qr.setImageBitmap(bitmap)
                status.setText(R.string.signal_link_scan)
            }
        }
    }

    override fun onAccountState(proto: String, state: String) {
        if (proto != ProtoPicker.SG) return
        if (state == "linked" || state == "connecting" || state == "connected") {
            Toast.makeText(this, R.string.signal_link_done, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onPairError(proto: String, code: String) {
        if (proto != ProtoPicker.SG) return
        status.text = Signal.errorText(this, code)
        progress.visibility = View.GONE
    }

}
