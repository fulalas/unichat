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
        // menu item ids (were bare 1..7 literals in two lists 40 lines apart)
        private const val M_LOGOUT = 1
        private const val M_SEARCH = 2
        private const val M_THEME = 3
        private const val M_FONT = 4
        private const val M_ABOUT = 5
        private const val M_PRIVACY = 6
        private const val M_PROFILE = 7
        private const val M_ADD_ACCOUNT = 8
    }

    private lateinit var chatList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: ChatListAdapter
    private val io = Io.executor
    // number lookups only: they block on the network far longer than any list
    // read, and sharing io would stall every screen behind one of them
    private val lookup = java.util.concurrent.Executors.newSingleThreadExecutor()

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
                // an address-book result has no chat yet: ask WhatsApp for the
                // number first, so a mistyped or non-WhatsApp number says so
                // instead of opening a chat that can never deliver
                if (PhoneBook.isPhoneEntry(chat.id)) openPhoneEntry(chat)
                else openChat(chat.id, chat.name)
            },
            onAvatarClick = { chat ->
                if (!PhoneBook.isPhoneEntry(chat.id)) Bridge.openAvatar(this, chat.id)
            },
            onLongClick = { chat ->
                // mute/delete/etc. need a chat; there isn't one yet
                if (!PhoneBook.isPhoneEntry(chat.id)) showChatOptions(chat)
            },
        )
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = adapter

        // POST_NOTIFICATIONS only exists as a runtime permission from API 33; on
        // 29..32 checkSelfPermission always reports DENIED and requestPermissions
        // is a silent no-op, so this ran a doomed round-trip on every cold start
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        WmService.start(this)
        Bridge.connect()

        // stay registered while a chat is open on top: the list keeps updating
        // in the background (read messages, typing state), so returning to it
        // never flashes stale unread badges or "typing…" lines
        Bridge.addListener(this)
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (!Bridge.hasAnySession()) {
            // covers a logout that happened while we were in the background
            // (redirecting from there would be blocked by Android anyway)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        updateSubtitle()
        // returning from LoginActivity may have linked the second protocol;
        // drop the "Link account" entry then
        refreshAccountMenu()
        reload()
        refreshDayIfChanged()
        scheduleMidnightRefresh()
    }

    override fun onStop() {
        super.onStop()
        started = false
        chatList.removeCallbacks(midnightRefresh)
        // the throttle's pending reload is posted on this view; drop it with the
        // rest of the screen's callbacks so it can't fire after we're gone
        chatList.removeCallbacks(bgReloadRelease)
        bgReloadCooldown = false
        bgReloadPending = false
    }

    // The diff-based adapter only rebinds rows whose data changed, so the
    // day-relative time labels ("Yesterday", weekday names) would go stale
    // across midnight; force a rebind whenever the calendar day changes —
    // on return to this screen and, while it stays open, at midnight.
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
        lookup.shutdownNow()
    }

    private var allChats: List<ChatRow> = emptyList()
    private var query: String = ""
    // the contacts permission is offered once per run: the menu (and its
    // SearchView) is rebuilt often, and re-asking on every search would nag
    private var contactsAsked = false
    private var started = false
    // throttle for reloads while backgrounded: bursts (history sync, busy
    // groups) collapse to one full chat-list query per second instead of one
    // per debounced event, while single events still refresh immediately so
    // returning to this screen never shows stale data
    private var bgReloadCooldown = false
    private var bgReloadPending = false

    // A named Runnable, not an anonymous lambda: onStop claims to drop this
    // pending reload with the screen's other callbacks, but there was no handle
    // to remove, so it still fired ~1s after the screen stopped and could
    // re-enter reloadFromEvent() — a full chats() query for a dead screen.
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
            val chats = Bridge.db.chats()
            runOnUiThread {
                allChats = chats
                applyFilter()
            }
        }
    }

    // Stamps the transient typing/recording state into the rows so the
    // adapter's diff picks up state changes (they arrive as chat-list change
    // events, so the list is rebuilt when a state starts or clears). Rows are
    // only copied when their state actually differs — the map is almost
    // always empty, and identical rows keep the diff a no-op.
    private fun withChatStates(chats: List<ChatRow>): List<ChatRow> = chats.map {
        val state = Bridge.chatState(it.id) ?: ""
        // presence rides along with the typing state: both are live values the
        // DB knows nothing about, and both must reach the differ as row data or
        // the row never rebinds when they change
        val online = !it.isGroup && Bridge.isOnline(it.id)
        if (state == it.transientState && online == it.online) it
        else it.copy(transientState = state, online = online)
    }

    // Submits the list and, if it was scrolled to the very top, keeps it pinned
    // there once the diff commits — so a chat that a new message reorders to the
    // top stays visible instead of landing just above the viewport (mirrors the
    // chat screen's stay-at-bottom rule).
    private fun submitChats(chats: List<ChatRow>) {
        val atTop = !chatList.canScrollVertically(-1)
        adapter.submit(withChatStates(chats)) {
            if (atTop) chatList.scrollToPosition(0)
        }
    }

    private fun openChat(chatId: String, name: String) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("chatId", chatId)
        intent.putExtra("chatName", name)
        startActivity(intent)
    }

    /** Opens a chat with an address-book number, once WhatsApp confirms it. */
    private fun openPhoneEntry(chat: ChatRow) {
        val number = PhoneBook.numberOf(chat.id)
        Toast.makeText(this, R.string.checking_number, Toast.LENGTH_SHORT).show()
        // its own thread: the server has up to 75s to answer, and the shared io
        // queue is what every screen reads its lists through
        lookup.execute {
            val jid = Bridge.resolveNumber(number)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    // never say someone is not on WhatsApp because we couldn't ask
                    jid == Bridge.NUMBER_LOOKUP_FAILED ->
                        Toast.makeText(this, R.string.number_check_failed, Toast.LENGTH_SHORT).show()
                    jid.isEmpty() ->
                        Toast.makeText(this, R.string.not_on_whatsapp, Toast.LENGTH_SHORT).show()
                    else -> {
                        Bridge.rememberContact(jid, chat.name)
                        openChat(jid, chat.name)
                    }
                }
            }
        }
    }

    private fun applyFilter() {
        if (query.isEmpty()) {
            submitChats(allChats)
            emptyText.visibility = if (allChats.isEmpty()) View.VISIBLE else View.GONE
            return
        }
        // search both existing chats and the full contact list. Chats are
        // matched on their already-resolved display name, so someone you've
        // talked to but never saved as a contact (known only by push name)
        // still turns up — searchContacts alone would miss them.
        val q = query
        io.execute {
            val contacts = Bridge.db.searchContacts(q)
            // people this phone knows but WhatsApp has not synced to us yet
            val fromPhone = PhoneBook.search(this, q)
            runOnUiThread {
                if (query != q) return@runOnUiThread // a newer query superseded this one
                // match what the row actually SHOWS: an unresolved chat renders
                // as "+15551234567" via displayLabel, so matching only name/id
                // (neither of which has the '+') made the visible row vanish
                // from its own search results
                val chatMatches = allChats.filter {
                    it.displayLabel().contains(q, ignoreCase = true) ||
                        it.name.contains(q, ignoreCase = true) ||
                        it.id.contains(q, ignoreCase = true)
                }
                // a saved contact who also has a chat matches in both lists;
                // keep the chat row (it carries recency/unread) and drop the
                // contact duplicate, keyed on the shared JID
                val seen = chatMatches.mapTo(HashSet()) { it.id }
                val known = chatMatches + contacts.filter { it.id !in seen }
                // address-book people are the last resort: anyone already known
                // to either service is shown as themselves, with their history
                // Numbers already reachable, from both shapes a row can carry
                // them in: a phone JID, and a contact row's "+55…" preview —
                // which is the only handle on someone whose chat is keyed by a
                // @lid. Telegram ids hold no number, so those can't be matched.
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

    // Folds the linked protocols' states into one line: connected only when
    // every linked account is, connecting while any still is.
    private fun updateSubtitle() {
        val states = buildList {
            if (Bridge.hasSession()) add(Bridge.state)
            if (Tg.hasSession()) add(Tg.state)
        }
        supportActionBar?.subtitle = when {
            states.isEmpty() -> getString(R.string.state_disconnected)
            states.all { it == "connected" } && Bridge.hasSession() && Bridge.syncProgress in 0..99 ->
                getString(R.string.state_syncing, Bridge.syncProgress)
            states.all { it == "connected" } -> getString(R.string.state_connected)
            states.any { it == "connecting" } -> getString(R.string.state_connecting)
            else -> getString(R.string.state_disconnected)
        }
    }

    override fun onChatsChanged() = reloadFromEvent()

    override fun onTgStateChanged() = updateSubtitle()

    /**
     * Someone came online or went away. Re-stamps the rows from the list already
     * in memory rather than re-reading the DB: presence is chatty, and nothing
     * stored has changed — only the value withChatStates copies in.
     */
    override fun onPresence(userId: String, isOnline: Boolean, lastSeen: Long) {
        if (!started || query.isNotEmpty()) return
        // every visible row subscribes as the list scrolls, so these arrive for
        // contacts this list may not even show — and each one costs a full
        // rebuild plus a diff pass
        if (allChats.none { it.id == userId }) return
        submitChats(allChats)
    }

    /**
     * Someone started or stopped typing. Same treatment as presence, and for the
     * same reason: the typing state lives in Bridge, not in the database, so the
     * rows already in memory just need re-stamping. This used to arrive as a
     * chats-changed event, which re-ran the whole chat-list query on every actor
     * start, stop and 15-second expiry.
     */
    override fun onChatState(chatId: String, state: String) {
        if (!started || query.isNotEmpty()) return
        // A chat nobody has loaded yet (its first message is arriving with the
        // typing) is not in `allChats`, so there is no row to re-stamp — read
        // the list instead of dropping the event.
        if (allChats.none { it.id == chatId }) {
            reloadFromEvent()
            return
        }
        submitChats(allChats)
    }

    // a Telegram logout finished: leave for the login screen if nothing is left,
    // else offer to link it again
    override fun onTgAuth(state: String, message: String) {
        if (state != "wait_phone" || !started) return
        if (!Bridge.hasAnySession()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        refreshAccountMenu()
    }

    // Deliberately NOT a reload: the bridge marks the chat list changed for
    // every message event too, so onChatsChanged already covers this. Reloading
    // here as well turned one burst of N changed chats into N+1 identical
    // chats() queries (five subqueries per row) back to back on the io thread.
    override fun onMessagesChanged(chatId: String, rowIds: Set<String>?) {}

    override fun onSyncProgress(progress: Int) = updateSubtitle()

    override fun onStateChanged(state: String) {
        updateSubtitle()
        // Android blocks activity starts from the background; when stopped,
        // leave the task alone — onStart routes to login via hasAnySession().
        // With optional accounts, one protocol logging out only leaves this
        // screen when no other account remains.
        if (state == "logged_out" && started) {
            if (!Bridge.hasAnySession()) {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }
            // the unlink has landed now, so the menu can offer to link it again
            refreshAccountMenu()
        }
    }

    // how many accounts were linked when the menu was last built — the only
    // thing about it that can change while this screen lives
    private var menuLinkedAccounts = -1

    // Rebuilding the menu also replaces the SearchView with a fresh, empty one
    // while `query` keeps the text typed into the old one: the list would stay
    // filtered by a term the user can neither see nor clear. So rebuild only
    // when the linked accounts actually changed, and drop the filter with the
    // search box that held it.
    private fun refreshAccountMenu() {
        if (ProtoPicker.linked().size == menuLinkedAccounts) return
        invalidateOptionsMenu()
        if (query.isEmpty()) return
        query = ""
        applyFilter()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val searchItem = menu.add(0, M_SEARCH, 0, R.string.search)
        searchItem.setIcon(android.R.drawable.ic_menu_search)
        // ALWAYS, not IF_ROOM: "if room" is re-decided every time the search box
        // expands and collapses, and losing that contest demotes the item — the
        // icon disappeared from the bar and "Search" turned up as a line inside
        // the overflow menu, where an action view cannot even open.
        searchItem.setShowAsAction(
            MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW
        )
        // Collapsing the box — with its X, or the back arrow it puts in the
        // toolbar — reports no query change, so the list stayed filtered by a
        // term that is no longer visible anywhere.
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
        // asked for here rather than at startup: this is the one moment it is
        // obvious what it is for, and search works without it
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
                applyFilter()
                return true
            }
        })
        searchItem.actionView = searchView

        menu.add(0, M_PROFILE, 1, R.string.profile)
        menu.add(0, M_PRIVACY, 2, R.string.privacy)
        menu.add(0, M_THEME, 3, R.string.theme)
        menu.add(0, M_FONT, 4, R.string.font_size)
        menu.add(0, M_ABOUT, 5, R.string.about)
        // offered while a protocol is still unlinked
        menuLinkedAccounts = ProtoPicker.linked().size
        if (menuLinkedAccounts < 2) menu.add(0, M_ADD_ACCOUNT, 6, R.string.link_account)
        menu.add(0, M_LOGOUT, 7, R.string.logout)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_LOGOUT -> {
                // account-scoped: pick which protocol to unlink when both are
                ProtoPicker.pick(this) { proto ->
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.logout_account, ProtoPicker.label(this, proto)))
                        .setMessage(R.string.logout_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            // both logouts only queue work on their executors, so
                            // the menu can't be rebuilt here — hasSession() still
                            // says linked. onStateChanged / onTgAuth do it once
                            // the unlink has actually landed.
                            if (proto == ProtoPicker.TG) Tg.logout() else Bridge.logout()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                return true
            }
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
            M_ADD_ACCOUNT -> {
                // don't finish(): coming back keeps the chat list underneath
                startActivity(Intent(this, LoginActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // Long-press on a chat: a small menu of per-chat actions. Mute toggles the
    // local notification suppression; Delete opens the confirm dialog below.
    private fun showChatOptions(chat: ChatRow) {
        val muteLabel = getString(if (chat.muted) R.string.unmute_chat else R.string.mute_chat)
        val items = arrayOf(muteLabel, getString(R.string.delete_chat))
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_options_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> Bridge.setMuted(chat.id, !chat.muted)
                    1 -> showDeleteChatDialog(chat)
                }
            }
            .show()
    }

    // Confirm before deleting a chat locally. The checkbox (checked by default)
    // also removes the chat's downloaded media from disk.
    private fun showDeleteChatDialog(chat: ChatRow) {
        val deleteMedia = booleanArrayOf(true)
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_chat_title)
            .setMultiChoiceItems(
                arrayOf(getString(R.string.delete_chat_media)), deleteMedia
            ) { _, _, isChecked -> deleteMedia[0] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_chat) { _, _ ->
                Bridge.deleteChat(chat.id, deleteMedia[0])
            }
            .show()
    }

    private fun showAboutDialog() {
        // declared to throw NameNotFoundException; Kotlin doesn't force the
        // catch, but an unhandled one here would crash the whole screen
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
                AppCompatDelegate.setDefaultNightMode(modes[which]) // recreates activities
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFontSizeDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_font_size, null)
        val seek = view.findViewById<android.widget.SeekBar>(R.id.fontSeek)
        val preview = view.findViewById<android.widget.TextView>(R.id.fontPreview)
        // 9 steps from FONT_MIN..FONT_MAX in 0.1 increments
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
                    recreate() // re-applies font scale via attachBaseContext
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
