package org.unichat.app

import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView

class ContactInfoActivity : BaseActivity(), Bridge.UiListener {

    private lateinit var chatId: String
    private lateinit var avatar: ImageView
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView

    private val io = Io.executor
    private val isGroup get() = isGroupId(chatId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getStringExtra("chatId") ?: run { finish(); return }
        applyProtocolTheme(Tg.isTgId(chatId))
        setContentView(R.layout.activity_contact_info)
        supportActionBar?.apply {
            setTitle(if (isGroup) R.string.group_info else R.string.contact_info)
            setDisplayHomeAsUpEnabled(true)
        }
        if (!Bridge.init(this)) { finish(); return }

        avatar = findViewById(R.id.avatar)
        nameView = findViewById(R.id.infoName)
        statusView = findViewById(R.id.infoStatus)
        avatar.clipToOutline = true
        avatar.outlineProvider = ViewOutlineProvider.BACKGROUND
        avatar.setOnClickListener { Bridge.openAvatar(this, chatId) }

        nameView.text = intent.getStringExtra("chatName").orEmpty()
        findViewById<View>(R.id.rowPhone).setOnClickListener { copyRow(R.id.valuePhone) }
        findViewById<View>(R.id.rowNickname).setOnClickListener { copyRow(R.id.valueNickname) }

        loadName()
        loadAvatar()
        loadDetails()
    }

    override fun onStart() {
        super.onStart()
        Bridge.addListener(this)
        if (!isGroup) Bridge.subscribePresence(chatId)
        updateStatus()
    }

    override fun onStop() {
        super.onStop()
        Bridge.removeListener(this)
    }

    private fun copyRow(valueId: Int) {
        val text = findViewById<TextView>(valueId).text?.toString().orEmpty()
        if (text.isNotEmpty()) copyToClipboard("contact", text, R.string.copied)
    }

    private fun loadName() {
        io.execute {
            // the stored name, never the intent's: the caller hands over a label
            // it has already decorated with the protocol, and decorating that
            // again produced "Rafael (Telegram) (Telegram)"
            val name = Bridge.db.displayName(chatId).let {
                val proto = selfProtocol(this, chatId)
                if (proto.isEmpty()) it else "$it ($proto)"
            }
            runOnUiThread { if (!isFinishing) nameView.text = name }
        }
    }

    /** Pixel size of the 160dp header — the most this avatar is ever drawn at,
     *  so a server-sized photo is never decoded whole just to draw it small. */
    private val avatarPx by lazy { (160 * resources.displayMetrics.density).toInt() }

    private fun loadAvatar() {
        // Off the shared serial worker: the full-size fetch blocks on the
        // network (a 20s TDLib download, a timeout-less WhatsApp request) and
        // would hold up every other screen's DB reads behind it.
        Io.lookup.execute {
            var path = Bridge.getAvatarFullPath(chatId)
            if (path.isEmpty()) path = Bridge.getAvatarPath(chatId)
            val bmp = if (path.isEmpty()) null else ImageLoader.decodeSampled(path, avatarPx)
            runOnUiThread {
                if (isFinishing || isDestroyed || bmp == null) return@runOnUiThread
                avatar.setImageBitmap(bmp)
            }
        }
    }

    private fun loadDetails() {
        if (isGroup) return
        Io.lookup.execute {
            val info = Bridge.peerInfo(chatId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                fillRow(R.id.rowPhone, R.id.valuePhone, info.phone)
                fillRow(R.id.rowNickname, R.id.valueNickname, info.nickname)
                fillRow(R.id.rowAbout, R.id.valueAbout, info.about)
            }
        }
    }

    private fun fillRow(rowId: Int, valueId: Int, text: String) {
        findViewById<View>(rowId).visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(valueId).text = text
    }

    override fun onPresence(userId: String, isOnline: Boolean, lastSeen: Long) {
        if (userId == chatId) updateStatus()
    }

    private fun updateStatus() {
        val online = !isGroup && Bridge.isOnline(chatId)
        val status = when {
            isGroup -> null
            online -> getString(R.string.online)
            Bridge.lastSeenOf(chatId) > 0 -> getString(
                R.string.last_seen, TimeFormat.compactWithTime(this, Bridge.lastSeenOf(chatId))
            )
            Bridge.lastSeenApproxOf(chatId) != 0 -> getString(Bridge.lastSeenApproxOf(chatId))
            else -> null
        }
        statusView.text = status
        statusView.setTextColor(
            if (online) themeColor(R.attr.chatAccent) else getColor(R.color.text_secondary)
        )
        statusView.visibility = if (status.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}
