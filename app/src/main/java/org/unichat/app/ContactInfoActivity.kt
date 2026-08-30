package org.unichat.app

import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView

class ContactInfoActivity : BaseActivity(), Bridge.UiListener {

    companion object {
        private const val MEMBER_ROWS = 200
    }

    private lateinit var chatId: String
    private lateinit var avatar: ImageView
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView

    private val io = Io.executor
    private val isGroup get() = isGroupId(chatId)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getStringExtra("chatId") ?: run { finish(); return }
        applyProtocolTheme(Accounts.ofChat(chatId))
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
        loadMembers()
    }

    private fun loadMembers() {
        if (!isGroup) return
        // Io.lookup, not the shared worker: both protocols ask their server for
        // the member list, and on Telegram every unnamed member costs another
        // round trip, none of which may sit in front of a screen's DB reads.
        Io.lookup.execute {
            val members = Bridge.groupMembers(chatId)
            runOnUiThread {
                if (isFinishing || isDestroyed || members.isEmpty()) return@runOnUiThread
                showMembers(members)
            }
        }
    }

    private fun showMembers(members: List<Bridge.Member>) {
        val header = findViewById<TextView>(R.id.membersHeader)
        header.text =
            resources.getQuantityString(R.plurals.members_count, members.size, members.size)
        header.visibility = View.VISIBLE
        val list = findViewById<android.view.ViewGroup>(R.id.membersList)
        list.removeAllViews()
        // a WhatsApp group holds up to 1024, and every row here is inflated on
        // the main thread: the count above stays honest, the list is cut short
        for (member in members.take(MEMBER_ROWS)) {
            val row = layoutInflater.inflate(R.layout.item_member, list, false)
            val avatar = row.findViewById<ImageView>(R.id.memberAvatar)
            row.findViewById<TextView>(R.id.memberName).text = member.name
            AvatarLoader.load(member.chatId, member.name, avatar, AvatarLoader.dp(avatar, 40))
            row.setOnClickListener { openMemberChat(member) }
            list.addView(row)
        }
    }

    /**
     * CLEAR_TOP drops this screen and hands the new chat to the singleTop
     * ChatActivity already in the task, so Back leaves for the chat list instead
     * of walking back through the group's profile.
     */
    private fun openMemberChat(member: Bridge.Member) {
        startActivity(
            android.content.Intent(this, ChatActivity::class.java)
                .putExtra("chatId", member.chatId)
                .putExtra("chatName", member.name)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
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

    /** The 160dp header is the most this avatar is ever drawn at, so a
     *  server-sized photo is never decoded whole just to draw it small. */
    private val avatarPx by lazy { (160 * resources.displayMetrics.density).toInt() }

    private fun loadAvatar() {
        // Off the shared serial worker: the full-size fetch blocks on the
        // network (a 20s TDLib download, a timeout-less WhatsApp request) and
        // held up every other screen's DB reads behind it.
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
