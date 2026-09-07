package org.unichat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : BaseActivity(), Bridge.UiListener {

    private companion object {
        private const val M_SEARCH = 2
        private const val M_THEME = 3
        private const val M_FONT = 4
        private const val M_ABOUT = 5
        private const val M_PRIVACY = 6
        private const val M_PROFILE = 7
        private const val M_ACCOUNTS = 9
        private const val M_MUTE = 10
        private const val M_DELETE = 11
        private const val M_OPEN_OTHER = 12
    }

    private lateinit var chatList: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var emptyText: TextView
    private lateinit var adapter: ChatListAdapter
    private var dragSelect: DragSelectTouchListener? = null
    private val io = Io.executor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Bridge.init(this) || !Bridge.hasAnySession()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        chatList = findViewById(R.id.chatList)
        emptyText = findViewById(R.id.emptyText)
        adapter = ChatListAdapter(
            onClick = { chat ->
                if (PhoneBook.isPhoneEntry(chat.id)) openPhoneEntry(chat)
                else openChat(chat.id, chat.name)
            },
            onAvatarClick = { chat ->
                if (!PhoneBook.isPhoneEntry(chat.id)) Bridge.openAvatar(this, chat.id)
            },
            onSelectionChanged = { onSelectionChanged() },
            onDragArm = { dragSelect?.arm() },
        )
        lm = LinearLayoutManager(this)
        chatList.layoutManager = lm
        chatList.adapter = adapter
        dragSelect = DragSelectTouchListener(adapter, onDragFinished = { onDragSelectFinished() })
            .also { chatList.addOnItemTouchListener(it) }
        chatList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) syncWatchedChats()
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        WmService.start(this)
        Bridge.connect()

        Bridge.addListener(this)
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (!Bridge.hasAnySession()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        updateSubtitle()
        refreshAccountMenu()
        Signal.refreshContacts()
        reload()
        syncWatchedChats()
        refreshDayIfChanged()
        scheduleMidnightRefresh()
    }

    override fun onStop() {
        super.onStop()
        started = false
        dragSelect?.stopDrag()
        syncWatchedChats()
        chatList.removeCallbacks(midnightRefresh)
        chatList.removeCallbacks(bgReloadRelease)
        bgReloadCooldown = false
        bgReloadPending = false
    }

    private var renderedDay = TimeFormat.dayStamp()

    private fun refreshDayIfChanged() {
        val today = TimeFormat.dayStamp()
        if (today == renderedDay) return
        renderedDay = today
        adapter.notifyDataSetChanged()
    }

    private val midnightRefresh = Runnable {
        refreshDayIfChanged()
        scheduleMidnightRefresh()
    }

    private fun scheduleMidnightRefresh() {
        chatList.removeCallbacks(midnightRefresh)
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 1)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        chatList.postDelayed(midnightRefresh, cal.timeInMillis - System.currentTimeMillis())
    }

    override fun onDestroy() {
        super.onDestroy()
        Bridge.removeListener(this)
    }

    private var allChats: List<ChatRow> = emptyList()
    private var query: String = ""
    private var contactsAsked = false
    private var started = false
    private var bgReloadCooldown = false
    private var bgReloadPending = false

    private val bgReloadRelease = Runnable {
        bgReloadCooldown = false
        if (bgReloadPending) {
            bgReloadPending = false
            reloadFromEvent()
        }
    }

    private fun reloadFromEvent() {
        if (started) {
            reload()
            return
        }
        if (bgReloadCooldown) {
            bgReloadPending = true
            return
        }
        bgReloadCooldown = true
        reload()
        chatList.postDelayed(bgReloadRelease, 1000)
    }

    private fun reload() {
        io.execute {
            val chats = Bridge.visibleChats()
            runOnUiThread {
                allChats = chats
                applyFilter()
            }
        }
    }

    private fun withChatStates(chats: List<ChatRow>): List<ChatRow> = chats.map {
        val self = isSelfChat(this, it.id)
        val state = if (self) "" else Bridge.chatState(it.id) ?: ""
        val online = !self && !it.isGroup && Bridge.isOnline(it.id)
        if (state == it.transientState && online == it.online) it
        else it.copy(transientState = state, online = online)
    }

    private var reloadAfterDrag = false

    private fun onDragSelectFinished() {
        if (!reloadAfterDrag) return
        reloadAfterDrag = false
        applyFilter()
    }

    private fun submitChats(chats: List<ChatRow>) {
        if (dragSelect?.isDragging == true) {
            reloadAfterDrag = true
            return
        }
        val atTop = !chatList.canScrollVertically(-1)
        adapter.submit(withChatStates(chats)) {
            if (atTop) chatList.scrollToPosition(0)
            chatList.post { syncWatchedChats() }
        }
    }

    private val watched = HashSet<String>()

    private fun syncWatchedChats() {
        val wanted = HashSet<String>()
        if (started) {
            val first = (lm.findFirstVisibleItemPosition() - 2).coerceAtLeast(0)
            val last = lm.findLastVisibleItemPosition() + 2
            for (pos in first..last) {
                val row = adapter.rowAt(pos) ?: continue
                if (!row.isGroup && Tg.isTgId(row.id)) wanted.add(row.id)
            }
        }
        for (id in watched - wanted) Bridge.watchChatActions(id, false)
        for (id in wanted - watched) Bridge.watchChatActions(id, true)
        watched.clear()
        watched.addAll(wanted)
    }

    private fun openChat(chatId: String, name: String) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("chatId", chatId)
        intent.putExtra("chatName", name)
        startActivity(intent)
    }

    private fun openPhoneEntry(chat: ChatRow) = ProtoPicker.pick(this) { proto ->
        resolveNumberThenOpen(Accounts.of(proto), PhoneBook.numberOf(chat.id)) { id ->
            Bridge.rememberContact(id, chat.name)
            openChat(id, chat.name)
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val filterDebounce = Runnable { applyFilter() }

    private fun applyFilter() {
        if (query.isEmpty()) {
            submitChats(allChats)
            emptyText.visibility = if (allChats.isEmpty()) View.VISIBLE else View.GONE
            return
        }
        val q = query
        io.execute {
            val contacts = Bridge.db.searchContacts(q)
            val fromPhone = PhoneBook.search(this, q)
            val folded = Search.fold(q)
            runOnUiThread {
                if (query != q) return@runOnUiThread
                val chatMatches = allChats.filter {
                    Search.contains(it.displayLabel(), folded) ||
                        Search.contains(it.name, folded) ||
                        Search.contains(it.id, folded)
                }
                val seen = chatMatches.mapTo(HashSet()) { it.id }
                val known = chatMatches + contacts.filter { it.id !in seen }
                val knownDigits = HashSet<String>()
                for (row in known) {
                    if (isPhoneId(row.id)) knownDigits.add(row.id.substringBefore('@'))
                    if (row.lastText.startsWith("+")) {
                        PhoneBook.digitsOf(row.lastText).takeIf { it.length >= 8 }
                            ?.let { knownDigits.add(it) }
                    }
                }
                val newOnes = fromPhone
                    .filter { PhoneBook.digitsOf(it.number) !in knownDigits }
                    .map {
                        ChatRow(
                            id = it.id, name = it.name, lastText = it.number,
                            lastTime = 0, lastFromMe = false, lastRead = false,
                            unread = 0, isGroup = false,
                        )
                    }
                val results = known + newOnes
                submitChats(results)
                emptyText.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateSubtitle() {
        var active = 0
        var connected = 0
        var connecting = 0
        var waLinked = false
        for (account in Accounts.ALL) {
            if (!account.isLinked()) continue
            if (!Bridge.protoEnabled(account.proto)) continue
            if (account.proto == ProtoPicker.WA) waLinked = true
            active++
            when (account.state) {
                "connected" -> connected++
                "connecting" -> connecting++
            }
        }
        supportActionBar?.subtitle = when {
            active == 0 -> getString(R.string.state_disconnected)
            connected == active && waLinked && Bridge.syncProgress in 0..99 ->
                getString(R.string.state_syncing, Bridge.syncProgress)
            connected == active -> getString(R.string.state_connected)
            connected > 0 || connecting > 0 -> getString(R.string.state_connecting)
            else -> getString(R.string.state_disconnected)
        }
    }

    override fun onChatsChanged() = reloadFromEvent()

    override fun onPresence(userId: String, isOnline: Boolean, lastSeen: Long) {
        if (!started || query.isNotEmpty()) return
        if (allChats.none { it.id == userId }) return
        submitChats(allChats)
    }

    override fun onChatState(chatId: String, state: String) {
        if (!started || query.isNotEmpty()) return
        if (allChats.none { it.id == chatId }) {
            reloadFromEvent()
            return
        }
        submitChats(allChats)
    }

    override fun onTgAuth(state: String, message: String) {
        if (state != "wait_phone" || !started) return
        if (!Bridge.hasAnySession()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        refreshAccountMenu()
    }

    override fun onMessagesChanged(chatId: String, rowIds: Set<String>?) {}

    override fun onSyncProgress(progress: Int) = updateSubtitle()

    override fun onAccountState(proto: String, state: String) {
        updateSubtitle()
        if (state == "logged_out" && started) {
            if (!Bridge.hasAnySession()) {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }
            refreshAccountMenu()
        }
    }

    private var menuLinkedAccounts = -1

    private fun refreshAccountMenu() {
        if (Accounts.linked().size == menuLinkedAccounts) return
        invalidateOptionsMenu()
        if (query.isEmpty()) return
        query = ""
        applyFilter()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val searchItem = menu.add(0, M_SEARCH, 0, R.string.search)
        searchItem.setIcon(android.R.drawable.ic_menu_search)
        searchItem.setShowAsAction(
            MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW
        )
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                if (query.isNotEmpty()) {
                    query = ""
                    applyFilter()
                }
                return true
            }
        })
        val searchView = SearchView(this)
        searchView.queryHint = getString(R.string.search)
        searchView.findViewById<android.widget.TextView>(androidx.appcompat.R.id.search_src_text)
            ?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        searchView.setOnSearchClickListener {
            if (!PhoneBook.granted(this) && !contactsAsked) {
                contactsAsked = true
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2)
            }
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean = true
            override fun onQueryTextChange(q: String?): Boolean {
                query = q.orEmpty().trim()
                mainHandler.removeCallbacks(filterDebounce)
                if (query.isEmpty()) applyFilter()
                else mainHandler.postDelayed(filterDebounce, 200)
                return true
            }
        })
        searchItem.actionView = searchView

        menu.add(0, M_PROFILE, 1, R.string.profile)
        menu.add(0, M_PRIVACY, 2, R.string.privacy)
        menu.add(0, M_THEME, 3, R.string.theme)
        menu.add(0, M_FONT, 4, R.string.font_size)
        menu.add(0, M_ACCOUNTS, 5, R.string.manage_accounts)
        menuLinkedAccounts = Accounts.linked().size
        menu.add(0, M_ABOUT, 6, R.string.about)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_THEME -> { showThemeDialog(); return true }
            M_FONT -> { showFontSizeDialog(); return true }
            M_ABOUT -> { showAboutDialog(); return true }
            M_PRIVACY -> {
                ProtoPicker.pick(this) { proto ->
                    startActivity(
                        Intent(this, PrivacyActivity::class.java).putExtra("proto", proto)
                    )
                }
                return true
            }
            M_PROFILE -> {
                ProtoPicker.pick(this) { proto ->
                    startActivity(
                        Intent(this, ProfileActivity::class.java).putExtra("proto", proto)
                    )
                }
                return true
            }
            M_ACCOUNTS -> {
                startActivity(Intent(this, AccountsActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private var actionMode: androidx.appcompat.view.ActionMode? = null

    private fun onSelectionChanged() {
        val count = adapter.selectedCount()
        if (count == 0) {
            actionMode?.finish()
            return
        }
        if (actionMode == null) actionMode = startSupportActionMode(selectionCallback)
        actionMode?.title = count.toString()
        actionMode?.invalidate()
    }

    private val selectionCallback = object : androidx.appcompat.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: androidx.appcompat.view.ActionMode, menu: Menu): Boolean {
            menu.add(0, M_MUTE, 0, R.string.mute_chat).apply {
                setIcon(R.drawable.ic_mute)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            menu.add(0, M_DELETE, 1, R.string.delete_chat).apply {
                setIcon(R.drawable.ic_delete)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            menu.add(0, M_OPEN_OTHER, 2, R.string.open_on_other_account)
            return true
        }

        override fun onPrepareActionMode(mode: androidx.appcompat.view.ActionMode, menu: Menu): Boolean {
            val chats = adapter.selectedChats()
            val unmuting = chats.all { it.muted }
            menu.findItem(M_MUTE)?.apply {
                setTitle(if (unmuting) R.string.unmute_chat else R.string.mute_chat)
                setIcon(if (unmuting) null else getDrawable(R.drawable.ic_mute))
            }
            menu.findItem(M_OPEN_OTHER)?.isVisible = chats.size == 1
            return true
        }

        override fun onActionItemClicked(mode: androidx.appcompat.view.ActionMode, item: MenuItem): Boolean {
            val chats = adapter.selectedChats()
            if (chats.isEmpty()) return false
            when (item.itemId) {
                M_MUTE -> {
                    val mute = !chats.all { it.muted }
                    for (chat in chats) if (chat.muted != mute) Bridge.setMuted(chat.id, mute)
                    mode.finish()
                }
                M_DELETE -> showDeleteChatDialog(chats)
                M_OPEN_OTHER -> { openOnOtherAccount(chats[0]); mode.finish() }
            }
            return true
        }

        override fun onDestroyActionMode(mode: androidx.appcompat.view.ActionMode) {
            actionMode = null
            adapter.clearSelection()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (adapter.selectionMode) adapter.clearSelection()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun openOnOtherAccount(chat: ChatRow) {
        if (chat.isGroup) {
            Toast.makeText(this, R.string.no_number_for_chat, Toast.LENGTH_SHORT).show()
            return
        }
        val own = Accounts.ofChat(chat.id).proto
        val digits = Bridge.db.contactPhone(chat.id).ifEmpty {
            if (isPhoneId(chat.id)) chat.id.substringBefore('@') else ""
        }
        if (digits.isEmpty()) {
            Toast.makeText(this, R.string.no_number_for_chat, Toast.LENGTH_SHORT).show()
            return
        }
        val others = ProtoPicker.active().filter { it != own }
        val label = chat.displayLabel()
        val name = if (label == chat.id) "" else label
        ProtoPicker.pickFrom(this, others) { proto ->
            resolveNumberThenOpen(Accounts.of(proto), "+" + digits.removePrefix("+")) { id ->
                Bridge.rememberContact(id, name)
                openChat(id, name.ifEmpty { label })
            }
        }
    }

    private fun showDeleteChatDialog(chats: List<ChatRow>) {
        val deleteMedia = booleanArrayOf(true)
        val title =
            if (chats.size == 1) getString(R.string.delete_chat_title)
            else getString(R.string.delete_chats_title, chats.size)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.delete_chat_media)), deleteMedia
            ) { _, _, isChecked -> deleteMedia[0] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_chat) { _, _ ->
                for (chat in chats) Bridge.deleteChat(chat.id, deleteMedia[0])
                actionMode?.finish()
            }
            .show()
    }

    private fun showAboutDialog() {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(
                getString(
                    R.string.about_message,
                    info?.versionName ?: "?", info?.longVersionCode ?: 0L
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.let {
            android.text.util.Linkify.addLinks(it, android.text.util.Linkify.WEB_URLS)
        }
    }

    private fun showThemeDialog() {
        val modes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        )
        val labels = arrayOf(
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_system),
        )
        val current = modes.indexOf(Prefs.nightMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.setNightMode(this, modes[which])
                AppCompatDelegate.setDefaultNightMode(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFontSizeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_font_size, null)
        val seek = view.findViewById<android.widget.SeekBar>(R.id.fontSeek)
        val preview = view.findViewById<android.widget.TextView>(R.id.fontPreview)
        fun scaleFor(progress: Int) = Prefs.FONT_MIN + progress * 0.1f
        fun progressFor(scale: Float) = Math.round((scale - Prefs.FONT_MIN) / 0.1f)
        val baseSp = 17f
        val currentScale = Prefs.fontScale(this)
        seek.progress = progressFor(currentScale).coerceIn(0, seek.max)
        preview.textSize = baseSp * scaleFor(seek.progress) / currentScale
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                preview.textSize = baseSp * scaleFor(p) / currentScale
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.font_size)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newScale = scaleFor(seek.progress)
                if (newScale != currentScale) {
                    Prefs.setFontScale(this, newScale)
                    recreate()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
