package org.unichat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * A finished-but-unsent recording, held across activity instances. Carries the
 * amplitude envelope with the file: it is what buildWaveform renders into the
 * waveform recipients see, and it used to live only in the instance that
 * captured the audio.
 */
private class PendingRecording(val file: File, val duration: Int, val amps: List<Int>)

class ChatActivity : BaseActivity(), Bridge.UiListener {

    companion object {
        // per-chat scroll position so returning to a chat restores where you
        // were; bounded LRU (most-recent 50 chats) so it can't grow unbounded
        private const val MAX_SCROLL_STATES = 50
        private const val SEARCH_LOAD_LIMIT = 5000
        // window of local messages shown initially; grows page by page as the
        // user scrolls to the top (before older history is fetched remotely)
        private const val LOCAL_PAGE = 500
        // upper bound on how long the screen is kept (dimly) alive for a
        // single voice note
        private const val RECORD_WAKE_LOCK_MS = 30 * 60 * 1000L
        // contextual-action-bar item ids for multi-select
        private const val M_FORWARD = 1
        private const val M_COPY = 2
        private const val M_DELETE = 3
        // hard cap on history pages the bridge seek pulls while paging back from
        // the reply toward the quoted original — bounds the effort for a target
        // that turns out not to be in the phone's history at all
        private const val MAX_SEEK_PAGES = 20
        // Backstop against a dropped bridge callback ONLY, so it must outlast the
        // worst case of the operation it guards: MAX_SEEK_PAGES pages, each with
        // its own bridge-side timeout. A flat 60s used to abort slow-but-working
        // seeks and toast "message not loaded" while pages were still arriving.
        private val SEEK_TIMEOUT_MS = MAX_SEEK_PAGES * Bridge.historyTimeoutMs + 30_000L
        // rows of older context to load above a jumped-to message: just enough to
        // keep it off the top edge (so maybeLoadOlder doesn't retrigger) without
        // over-fetching a whole page beyond what the jump needs
        private const val SEEK_CONTEXT_ROWS = 20
        // how often the open chat re-subscribes to the contact's presence
        private const val PRESENCE_RESUBSCRIBE_MS = 30_000L
        private val scrollStates =
            object : LinkedHashMap<String, android.os.Parcelable?>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, android.os.Parcelable?>) =
                    size > MAX_SCROLL_STATES
            }

        // Captured-but-unsent recordings, per chat, outliving the activity that
        // made them: pendingAudio used to be instance-only, so pressing Back
        // while recording destroyed the instance and the recording was lost with
        // no trace — contradicting the promise that a captured recording is
        // never thrown away. Re-entering the chat now restores it.
        private val pendingRecordings = HashMap<String, PendingRecording>()
    }

    private lateinit var chatId: String
    private lateinit var messageList: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var scrollFab: android.view.View
    private lateinit var fabDot: android.view.View
    private lateinit var floatingDate: android.widget.TextView
    private lateinit var input: EditText
    private lateinit var attachButton: ImageButton
    private lateinit var actionButton: ImageButton
    private lateinit var recordTimer: android.widget.TextView
    private lateinit var adapter: MessageAdapter
    private var dragSelect: DragSelectTouchListener? = null

    private val pickFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onFilePicked(it) } }
    // system "save as" dialog (defaults to Downloads); null uri = cancelled
    private val createExportFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let { writeChatExport(it) } }
    private lateinit var contextBar: android.view.View
    private lateinit var contextText: android.widget.TextView
    private var restoredScroll = false
    private var hasNewBelow = false
    // a video the user tapped (outside its download icon) to open: it's being
    // downloaded and should launch automatically once the file lands
    private var pendingVideoOpen: String? = null
    // jump-to-quote origins: each quote tap pushes the message it came from,
    // so the scroll chevron first returns there (most recent first) and only
    // then drops to the bottom; reaching the bottom by hand clears the trail
    private val quoteJumpReturns = ArrayDeque<String>()
    private var loadLimit = LOCAL_PAGE
    // guards against growing the local window several times for one top-hit
    private var loadingMoreLocal = false

    // jump-to-quote seek: when the tapped quote's original isn't in the loaded
    // window, widen the window if it's stored, otherwise have the bridge page
    // history back from the reply until it lands, then jump. seekQuotedId is the
    // target; seekOrigin is the replying message (the return-trail entry and the
    // anchor the bridge pages back from); seekFetching guards the one bridge call.
    private var seekQuotedId: String? = null
    private var seekOrigin: MessageRow? = null
    private var seekFetching = false
    private val seekTimeout = Runnable { if (seekQuotedId != null) failSeek() }

    private var replyTarget: MessageRow? = null
    private var editTarget: MessageRow? = null

    private lateinit var searchBar: android.view.View
    private lateinit var searchInput: EditText
    private lateinit var searchCount: android.widget.TextView
    private var searchMatches: List<Int> = emptyList()
    private var currentMatch = -1
    private var searchActive = false
    private lateinit var toolbarTitle: android.widget.TextView
    private lateinit var toolbarSubtitle: android.widget.TextView
    private var chatDisplayName: String = ""
    private val io = Io.executor
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val isGroup get() = isGroupId(chatId)

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordStart: Long = 0

    // Held while the mic is live: the screen-off timeout would pause the
    // recording (onPause captures it as pending), which reads as the recording
    // being cut off mid-sentence. A dim-level lock keeps the screen alive but
    // lets it fade, so a long voice note doesn't burn a fully lit screen.
    private var recordWakeLock: PowerManager.WakeLock? = null

    // A finished-but-not-yet-sent recording: set when recording is stopped by a
    // focus loss (incoming call, app switch, screen off) instead of an explicit
    // send/cancel. The composer stays in its send/cancel UI so the user can
    // still fire it off or discard it — a captured recording is never thrown
    // away on its own.
    private var pendingAudio: File? = null
    private var pendingDuration: Int = 0

    private val recordTicker = object : Runnable {
        override fun run() {
            if (recorder == null) return
            val secs = ((SystemClock.elapsedRealtime() - recordStart) / 1000).toInt()
            recordTimer.text = "●  " + TimeFormat.mmss(secs)
            main.postDelayed(this, 500)
        }
    }

    // Amplitude envelope of the recording, sampled while it runs; sent along
    // with the voice note so recipients see a real waveform instead of a
    // flat line (official clients render the message's Waveform field).
    private val recordAmps = ArrayList<Int>()
    private val ampTicker = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            recordAmps.add(try { r.maxAmplitude } catch (e: Exception) { 0 })
            main.postDelayed(this, 100)
        }
    }

    // Downsamples the recorded amplitudes into WhatsApp's waveform format:
    // 64 bars, 0..100 each, sqrt-scaled against the clip's own peak so quiet
    // passages stay visible the way the official app draws them.
    private fun buildWaveform(samples: List<Int>): ByteArray {
        if (samples.isEmpty()) return ByteArray(0)
        val bars = 64
        val peak = maxOf(samples.max(), 1)
        val out = ByteArray(bars)
        for (i in 0 until bars) {
            val from = i * samples.size / bars
            val to = maxOf(from + 1, (i + 1) * samples.size / bars).coerceAtMost(samples.size)
            var m = 0
            for (j in from until to) m = maxOf(m, samples[j])
            out[i] = (kotlin.math.sqrt(m.toDouble() / peak) * 100).toInt().toByte()
        }
        return out
    }

    // the AudioPlayer state hook this instance installed, so onStop only clears
    // the singleton's field when it is still ours (see onStop)
    private var audioHook: (() -> Unit)? = null

    private val audioTicker = object : Runnable {
        override fun run() {
            if (AudioPlayer.currentPath != null) {
                adapter.refreshAudioRows(messageList)
                main.postDelayed(this, 250)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getStringExtra("chatId") ?: run { finish(); return }
        // WhatsApp chats swap the blue chat palette for the green one; must
        // happen before any view of this screen inflates
        if (!Tg.isTgId(chatId)) theme.applyStyle(R.style.ThemeOverlay_UniChat_Wa, true)
        setContentView(R.layout.activity_chat)

        if (!Bridge.init(this)) { finish(); return }

        // toolbar: avatar + name + presence line
        val toolbarView = layoutInflater.inflate(R.layout.chat_toolbar, null)
        toolbarTitle = toolbarView.findViewById(R.id.toolbarTitle)
        toolbarSubtitle = toolbarView.findViewById(R.id.toolbarSubtitle)
        val toolbarAvatar = toolbarView.findViewById<android.widget.ImageView>(R.id.toolbarAvatar)
        toolbarView.findViewById<ImageButton>(R.id.toolbarSearch).setOnClickListener { openSearch() }
        toolbarView.findViewById<ImageButton>(R.id.toolbarMenu).setOnClickListener { showChatMenu(it) }
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
            setDisplayShowCustomEnabled(true)
            // fill the toolbar width so the subtitle never ends up sized to the
            // (shorter) initial content and truncated (e.g. "last seen…")
            setCustomView(
                toolbarView,
                androidx.appcompat.app.ActionBar.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        val extraName = intent.getStringExtra("chatName")
        chatDisplayName = extraName ?: chatId
        toolbarTitle.text = chatDisplayName
        toolbarAvatar.setOnClickListener { Bridge.openAvatar(this, chatId) }
        io.execute {
            val name = (extraName ?: Bridge.db.displayName(chatId)).let {
                // your own chat exists on both accounts under the same name
                val proto = selfProtocol(this, chatId)
                if (proto.isEmpty()) it else "$it ($proto)"
            }
            runOnUiThread {
                chatDisplayName = name
                toolbarTitle.text = name
                AvatarLoader.load(chatId, name, toolbarAvatar, AvatarLoader.dp(toolbarAvatar, 38))
            }
        }

        messageList = findViewById(R.id.messageList)
        input = findViewById(R.id.messageInput)
        attachButton = findViewById(R.id.attachButton)
        actionButton = findViewById(R.id.actionButton)
        recordTimer = findViewById(R.id.recordTimer)
        contextBar = findViewById(R.id.contextBar)
        contextText = findViewById(R.id.contextText)
        scrollFab = findViewById(R.id.scrollFab)
        fabDot = findViewById(R.id.fabDot)
        floatingDate = findViewById(R.id.floatingDate)
        searchBar = findViewById(R.id.searchBar)
        searchInput = findViewById(R.id.searchInput)
        searchCount = findViewById(R.id.searchCount)
        findViewById<ImageButton>(R.id.contextCancel).setOnClickListener { clearComposeContext() }
        findViewById<ImageButton>(R.id.searchClose).setOnClickListener { closeSearch() }
        findViewById<ImageButton>(R.id.searchUp).setOnClickListener { stepMatch(-1) }
        findViewById<ImageButton>(R.id.searchDown).setOnClickListener { stepMatch(1) }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                main.removeCallbacks(searchDebounce)
                main.postDelayed(searchDebounce, 200)
            }
        })

        adapter = MessageAdapter(
            isGroup = isGroup,
            onNeedDownload = { msg, userInitiated -> Bridge.downloadFile(msg, userInitiated) },
            onImageClick = { msg ->
                val intent = Intent(this, ImageViewActivity::class.java)
                intent.putExtra("path", msg.filePath)
                intent.putExtra("chatId", chatId)
                startActivity(intent)
            },
            onDocumentClick = { msg -> openDocument(msg) },
            onVideoOpen = { msg -> openVideo(msg) },
            onLocationClick = { msg -> openLocation(msg) },
            onMessageActions = { msg -> showMessageActions(msg) },
            onQuoteClick = { msg -> onQuoteTapped(msg) },
            onSelectionChanged = { onSelectionChanged() },
            onDragArm = { dragSelect?.arm() },
        )
        lm = LinearLayoutManager(this)
        lm.stackFromEnd = true
        messageList.layoutManager = lm
        messageList.adapter = adapter
        // long-press-then-drag range selection (with edge auto-scroll)
        dragSelect = DragSelectTouchListener(adapter, onDragFinished = { onDragSelectFinished() })
            .also { messageList.addOnItemTouchListener(it) }
        // keep more offscreen rows bound so small scrolls don't rebind, and drop
        // item animations: with DiffUtil driving updates the default change
        // cross-fade just reads as flicker when a chat (re)loads
        messageList.setItemViewCacheSize(24)
        messageList.itemAnimator = null

        scrollFab.setOnClickListener {
            // after a jump-to-quote, the chevron first retraces the jump(s)
            // back to where each started; only then it drops to the bottom.
            // Pop past any origin that is stale (unloaded) or already at/above
            // the current view: the down-chevron must only ever move downward,
            // so a return target has to sit below what's on screen right now.
            val lastVisible = lm.findLastVisibleItemPosition()
            var returnId: String? = null
            while (quoteJumpReturns.isNotEmpty()) {
                val id = quoteJumpReturns.removeLast()
                if (adapter.indexOfMessage(id) > lastVisible) { returnId = id; break }
            }
            if (returnId != null) {
                scrollToMessage(returnId, toastIfMissing = false)
            } else {
                hasNewBelow = false
                messageList.scrollToPosition(adapter.itemCount - 1)
            }
            updateScrollFab()
        }
        messageList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!messageList.canScrollVertically(1)) {
                    hasNewBelow = false
                    // back at the bottom on their own — the return trail is moot
                    quoteJumpReturns.clear()
                }
                updateScrollFab()
                showFloatingDate()
                maybeLoadOlder()
            }
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // the moment the user grabs the list, stop holding a jumped-to
                // message in place so we never fight their scroll
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) cancelHold()
            }
        })

        // attach: file/location chooser while idle, cancel while recording/pending
        attachButton.setOnClickListener {
            if (recorder != null || pendingAudio != null) finishRecording(send = false) else showAttachMenu()
        }
        // action button: send recording/pending, else send text, else start recording
        actionButton.setOnClickListener {
            when {
                recorder != null || pendingAudio != null -> finishRecording(send = true)
                input.text.isNotBlank() -> sendCurrentText()
                else -> startRecording()
            }
        }
        // toggle the action icon between mic (empty) and send (has text)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateActionButton()
        })
        updateActionButton()

        // top up a sparsely-synced chat with one older page on open (skipped
        // when plenty is already local, so re-opening doesn't keep backfilling)
        Bridge.requestInitialHistory(chatId)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            adapter.selectionMode -> adapter.clearSelection()
            searchBar.visibility == android.view.View.VISIBLE -> closeSearch()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onStart() {
        super.onStart()
        Bridge.addListener(this)
        Bridge.openChat(chatId, owner = this)
        // chaining across messages is handled by Bridge and keeps running with
        // the screen off; this hook only refreshes the visible UI while shown
        val hook = {
            runOnUiThread {
                // the earpiece fallback moves playback to the call stream, so
                // the volume keys have to follow it mid-clip
                volumeControlStream = AudioPlayer.volumeStream
                // update only the visible audio rows in place instead of
                // rebinding the whole list on every play/pause/chain
                adapter.refreshAudioRows(messageList)
                main.removeCallbacks(audioTicker)
                if (AudioPlayer.currentPath != null) main.post(audioTicker)
            }
        }
        audioHook = hook
        AudioPlayer.onStateChanged = hook
        restoredScroll = false
        restorePendingRecording()
        if (!isGroup) main.post(presenceTicker)
        updateSubtitle()
        // A search survives backgrounding (the screen isn't destroyed), and the
        // normal window would replace the full-history one its match positions
        // index into — leaving the bar, the highlight and the count pointing at
        // rows that are no longer there. Same rule as onMessagesChanged.
        if (searchActive) Bridge.markChatRead(chatId) else reload(markRead = true)
        // Always resync the audio rows on return, not only while something is
        // playing: a clip that ended while the screen was off (or another app
        // was in front) leaves its row showing the last ticked position, and an
        // unchanged row is never rebound by the differ, so nothing else clears it.
        adapter.refreshAudioRows(messageList)
        if (AudioPlayer.currentPath != null) main.post(audioTicker)
    }

    override fun onPause() {
        super.onPause()
        // catches losing the foreground before onStop does (e.g. an incoming
        // call taking over): stop the mic right away, but keep the audio as a
        // pending send/cancel rather than discarding it (see
        // captureRecordingAsPending).
        captureRecordingAsPending()
    }

    override fun onStop() {
        super.onStop()
        Bridge.removeListener(this)
        Bridge.closeChat(chatId, owner = this)
        // remember where we were so returning restores the position — but if we
        // were at the bottom, store null so the next load jumps to the real
        // bottom and shows whatever arrived meanwhile (e.g. a file just attached
        // via an external picker, or shared in from another app), instead of
        // restoring a now-stale anchor that sits above the newest message
        val atBottomNow = isAtBottom()
        scrollStates[chatId] = if (atBottomNow) null else lm.onSaveInstanceState()
        // ...and durably, since the in-memory state dies with the process
        saveScrollAnchor(atBottomNow)
        // drop the UI hook so the singleton AudioPlayer doesn't retain this
        // activity; playback itself continues in the background. Identity-checked:
        // a newer instance (share / notification deep-link into a chat while one
        // is already open) starts before this one stops, and clearing the field
        // unconditionally used to kill the live screen's audio UI updates.
        if (AudioPlayer.onStateChanged === audioHook) AudioPlayer.onStateChanged = null
        audioHook = null
        // normally a no-op: onPause already turned a live recording into a
        // pending one; kept here as a fallback for any path that reaches
        // onStop without onPause (e.g. certain multi-window transitions)
        captureRecordingAsPending()
        // stopRecorder already drops it on every normal path; leaving the screen
        // must never leave the lock behind
        releaseRecordWakeLock()
        main.removeCallbacks(audioTicker)
        main.removeCallbacks(searchDebounce)
        main.removeCallbacks(presenceTicker)
        // stop any pending one-shot location request: it would otherwise keep the
        // GPS engine running, retain this activity, and eventually toast (or send
        // a location) for a screen the user has left
        releaseLocationRequests()
        // don't launch a video app in the user's face after they've left
        pendingVideoOpen = null
        // a running jump-to-quote seek can't progress once we stop listening
        if (seekQuotedId != null) clearSeek()
        // a gesture interrupted by leaving the screen never gets its UP/CANCEL
        dragSelect?.stopDrag()
        cancelHold()
    }

    // Tolerant "scrolled to the bottom" test: a fling rarely stops on the exact
    // last pixel, and while the newest message is on screen the user expects new
    // ones to follow it — only genuinely scrolled-up positions keep their place.
    private fun isAtBottom(): Boolean =
        !messageList.canScrollVertically(1) ||
            lm.findLastVisibleItemPosition() >= adapter.itemCount - 1

    // Contact-name map for group sender labels, cached across reloads (a full
    // contacts scan per message event is wasted work — the map almost never
    // changes). Invalidated when a contact upsert fires onChatsChanged.
    @Volatile private var cachedContactNames: Map<String, String>? = null

    // Only a real contact write invalidates it. Hooking this to onChatsChanged
    // dropped the cache on every single message event (the bridge marks the chat
    // list changed for those too), so the next reload immediately re-ran the full
    // contacts scan the cache exists to avoid.
    override fun onContactsChanged() {
        cachedContactNames = null
        cachedQuoteNames.clear()
    }

    private fun contactNames(): Map<String, String> =
        cachedContactNames ?: Bridge.db.contactNames().also { cachedContactNames = it }

    // quoted message id → resolved sender label, cached across reloads: the
    // labels never change for a given message, but they were re-resolved with
    // one or two point queries per distinct quote on EVERY reload.
    private val cachedQuoteNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Resolves the quote-preview sender labels for the loaded rows (quoted
    // message id → display name, "" when that message is unknown). Runs on the
    // io thread so binds never query the DB.
    private fun quoteNamesFor(messages: List<MessageRow>): Map<String, String> {
        val labels = HashMap<String, String>()
        for (m in messages) {
            val qid = m.quotedId
            if (qid.isEmpty() || labels.containsKey(qid)) continue
            // resolved once per quoted message, then reused across reloads
            val memo = cachedQuoteNames[qid]
            if (memo != null) {
                labels[qid] = memo
                continue
            }
            val label = Bridge.db.messageSender(m.chatId, qid)?.let { s ->
                if (s.fromMe) getString(R.string.you)
                // senderLabel (Jid.kt) owns the name→push-name→phone fallback
                else senderLabel(
                    Bridge.db.contactName(s.senderId)?.let { mapOf(s.senderId to it) } ?: emptyMap(),
                    s.senderId, s.senderName
                )
            } ?: ""
            labels[qid] = label
            // don't memoize "unknown": the message may sync in later
            if (label.isNotEmpty()) cachedQuoteNames[qid] = label
        }
        return labels
    }

    private fun reload(markRead: Boolean = false) {
        io.execute {
            val messages = Bridge.db.messages(chatId, loadLimit)
            // names sender labels (groups) and @mentions (any chat)
            val names = contactNames()
            val quoteNames = quoteNamesFor(messages)
            runOnUiThread {
                // a video the user asked to open: launch it the moment its
                // download lands, or drop the request if it failed
                pendingVideoOpen?.let { id ->
                    val m = messages.find { it.id == id }
                    if (m != null && m.filePath.isNotEmpty()) {
                        pendingVideoOpen = null
                        openMediaFile(m)
                    } else if (m != null && m.fileStatus == 3) {
                        pendingVideoOpen = null // failed — Bridge already toasted
                    }
                }
                // capture position/growth against the current list before the
                // diff is applied; the scroll runs in the commit callback once
                // the new item count is live
                val atBottom = isAtBottom()
                // a new latest message is detected by its id, not by list
                // growth: a full local window stays at the query LIMIT when a
                // new message pushes the oldest row out. Older pages prepended
                // by pagination keep the newest id and thus keep position.
                val newestChanged = messages.isNotEmpty() &&
                    messages.lastOrNull()?.id != adapter.messagesSnapshot().lastOrNull()?.id
                adapter.submit(messages, names, quoteNames) {
                    loadingMoreLocal = false
                    when {
                        // first load after entering: restore saved position or start at bottom
                        !restoredScroll -> {
                            restoredScroll = true
                            val saved = scrollStates[chatId]
                            when {
                                // same process: the exact layout state
                                saved != null -> lm.onRestoreInstanceState(saved)
                                // cold start: re-find the message we were on
                                !scrollStates.containsKey(chatId) && restoreScrollAnchor() -> Unit
                                adapter.itemCount > 0 ->
                                    messageList.scrollToPosition(adapter.itemCount - 1)
                            }
                        }
                        // new message (incoming or our own): auto-scroll only
                        // if already at the bottom; once scrolled up, never
                        // move — just flag new content below
                        newestChanged -> {
                            if (atBottom) {
                                messageList.scrollToPosition(adapter.itemCount - 1)
                            } else {
                                hasNewBelow = true
                            }
                        }
                    }
                    updateScrollFab()
                    // a jump-to-quote seek advances as each (local widen / remote
                    // page) reload commits: jump when the target is now loaded,
                    // else fetch the next page
                    if (seekQuotedId != null) driveSeek()
                }
            }
            if (markRead) Bridge.markChatRead(chatId)
        }
    }

    // Pagination: hitting the top of the list loads older messages — first by
    // widening the local window, then (local history exhausted) by asking the
    // primary phone for an older page. The bridge dedupes in-flight requests
    // and stops once the start of the chat is reached.
    private fun maybeLoadOlder() {
        if (!restoredScroll || searchActive || adapter.itemCount == 0) return
        // don't prepend older rows mid-drag: it would shift positions and throw
        // off the drag range-select anchor (auto-scroll stops at the window edge)
        if (dragSelect?.isDragging == true) return
        if (lm.findFirstVisibleItemPosition() > 5) return
        if (adapter.itemCount >= loadLimit) {
            if (loadingMoreLocal) return
            loadingMoreLocal = true
            loadLimit += LOCAL_PAGE
            reload()
        } else {
            Bridge.requestChatHistory(chatId)
        }
    }

    private val hideFloatingDate = Runnable {
        floatingDate.animate().alpha(0f).setDuration(200).withEndAction {
            floatingDate.visibility = android.view.View.GONE
        }
    }

    // last position the chip was formatted for; onScrolled fires per frame, so
    // skip the date re-format while the top visible row hasn't changed
    private var floatingDatePos = -1

    // Floating day chip: shows the top visible message's date while scrolling,
    // then fades out shortly after scrolling stops.
    private fun showFloatingDate() {
        val pos = lm.findFirstVisibleItemPosition()
        val msgs = adapter.messagesSnapshot()
        if (pos < 0 || pos >= msgs.size) return
        if (pos != floatingDatePos) {
            floatingDatePos = pos
            floatingDate.text = TimeFormat.dateSeparator(this, msgs[pos].timeSent)
        }
        // onScrolled fires per frame; only touch the view when it isn't already
        // showing at full opacity
        if (floatingDate.visibility != android.view.View.VISIBLE || floatingDate.alpha != 1f) {
            floatingDate.animate().cancel()
            floatingDate.alpha = 1f
            floatingDate.visibility = android.view.View.VISIBLE
        }
        main.removeCallbacks(hideFloatingDate)
        main.postDelayed(hideFloatingDate, 1200)
    }

    private fun updateScrollFab() {
        val canDown = messageList.canScrollVertically(1)
        scrollFab.visibility = if (canDown) android.view.View.VISIBLE else android.view.View.GONE
        fabDot.visibility =
            if (canDown && hasNewBelow) android.view.View.VISIBLE else android.view.View.GONE
    }

    // Always jumps straight to the target (no animation) — a smooth scroll across
    // a far, media-heavy range crawls for seconds and drifts the landing. The
    // green flash marks where it landed; holdScrollToMessage keeps it there while
    // the window settles.
    private fun scrollToMessage(msgId: String, toastIfMissing: Boolean = true): Boolean {
        if (msgId.isEmpty()) return false
        val index = adapter.indexOfMessage(msgId)
        return if (index >= 0) {
            // flash the target row when it (re)binds (the payload makes
            // RecyclerView reuse the holder in place instead of cross-fading)
            adapter.flashMsgId = msgId
            adapter.notifyItemChanged(index, Unit)
            holdScrollToMessage(msgId, index)
            true
        } else {
            if (toastIfMissing) {
                Toast.makeText(this, R.string.message_not_loaded, Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    // one active hold at a time (see holdScrollToMessage)
    private var holdListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null

    // Jumps to a message instantly and keeps it pinned at the top for a short
    // window afterwards: a freshly widened window decodes images / settles row
    // heights over several layout passes, which would otherwise slide the target
    // off-screen right after the jump. Re-pins each layout pass until it's stable
    // (or the window elapses). Yields immediately if the user starts scrolling
    // (see cancelHold in the scroll listener).
    private fun holdScrollToMessage(msgId: String, index: Int) {
        messageList.scrollToPosition(index)
        cancelHold() // drop any previous hold so they can't fight over the target
        val start = SystemClock.uptimeMillis()
        // Track the index rather than re-deriving it: indexOfMessage is a linear
        // scan over the loaded window (up to SEARCH_LOAD_LIMIT rows) and this
        // used to run it on EVERY layout pass for the whole hold. The adapter
        // list can only change through a reload, which re-issues the hold.
        var targetIndex = index
        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (SystemClock.uptimeMillis() - start > 1500) { cancelHold(); return }
                if (targetIndex >= adapter.itemCount) {
                    targetIndex = adapter.indexOfMessage(msgId)
                    if (targetIndex < 0) { cancelHold(); return }
                }
                // Settle when the target is visible — NOT only when it is the
                // FIRST visible row: with stackFromEnd a target inside the last
                // screenful can never become the first visible one, so that test
                // never went true and the hold re-scrolled every frame for the
                // full window (a self-sustaining layout loop).
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()
                if (targetIndex in first..last) { cancelHold(); return }
                messageList.scrollToPosition(targetIndex)
            }
        }
        holdListener = listener
        messageList.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun cancelHold() {
        holdListener?.let { messageList.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        holdListener = null
    }

    // --- jump to a quoted message (syncing it in if needed) ------------------

    // A quote was tapped: jump straight to the original if it's loaded,
    // otherwise start syncing it in (widening the local window, then paging the
    // phone's history) and jump once it lands.
    private fun onQuoteTapped(origin: MessageRow) {
        if (origin.quotedId.isEmpty()) return
        if (seekQuotedId != null) clearSeek() // supersede any in-progress seek
        if (scrollToMessage(origin.quotedId, toastIfMissing = false)) {
            // remember where the jump started so the chevron can lead back; skip
            // consecutive duplicates so re-tapping the same quote doesn't stack
            if (quoteJumpReturns.lastOrNull() != origin.id) quoteJumpReturns.addLast(origin.id)
            return
        }
        seekQuotedId = origin.quotedId
        seekOrigin = origin
        seekFetching = false
        updateSubtitle() // shows the "syncing message…" line
        driveSeek()
    }

    // Advances the seek: jump if the target is loaded; else widen the window if
    // it's already stored; else ask the bridge to page history back from the
    // reply until it lands. Re-entered from reload()'s commit (as fetched pages
    // arrive) and from onSeekResult.
    private fun driveSeek() {
        val target = seekQuotedId ?: return
        if (adapter.indexOfMessage(target) >= 0) { completeSeek(); return }
        io.execute {
            val depth = Bridge.db.messageDepth(chatId, target)
            runOnUiThread {
                if (seekQuotedId != target) return@runOnUiThread // superseded/cancelled
                if (depth > 0) {
                    // stored (a fetched page landed it, or it was just off-window):
                    // widen and reload — the reload commit re-enters and jumps.
                    // Load a little older context ABOVE the target too, so the
                    // jump doesn't land it at the very top edge — which would
                    // retrigger older-history loading and scroll off the target.
                    // A small margin is enough (the target pins to the top); it
                    // avoids over-fetching a whole extra page for a deep target.
                    val need = depth + SEEK_CONTEXT_ROWS
                    if (need > loadLimit) loadLimit = need
                    reload()
                } else if (!seekFetching) {
                    // not stored: page history back from the replying message.
                    // A quoted original is always older than its reply, so this
                    // walks down into any gap it sits in — which older-than-oldest
                    // pagination could never reach.
                    val o = seekOrigin ?: return@runOnUiThread failSeek()
                    seekFetching = true
                    Bridge.seekMessage(chatId, target, o.id, o.timeSent, o.fromMe, MAX_SEEK_PAGES)
                    main.removeCallbacks(seekTimeout)
                    main.postDelayed(seekTimeout, SEEK_TIMEOUT_MS)
                }
                // else fetching: wait for onSeekResult (or a reload once it lands)
            }
        }
    }

    override fun onSeekResult(chatId: String, msgId: String, found: Boolean) {
        if (chatId != this.chatId || msgId != seekQuotedId) return
        if (found) driveSeek() // now stored → depth>0 → widen + reload + jump
        else failSeek()
    }

    private fun completeSeek() {
        val target = seekQuotedId ?: return
        val origin = seekOrigin?.id
        clearSeek()
        if (scrollToMessage(target, toastIfMissing = false) &&
            origin != null && quoteJumpReturns.lastOrNull() != origin
        ) {
            quoteJumpReturns.addLast(origin)
        }
    }

    private fun failSeek() {
        clearSeek()
        Toast.makeText(this, R.string.message_not_loaded, Toast.LENGTH_SHORT).show()
    }

    private fun clearSeek() {
        seekQuotedId = null
        seekOrigin = null
        seekFetching = false
        main.removeCallbacks(seekTimeout)
        Bridge.cancelSeek() // stop the bridge paging history for an abandoned seek
        updateSubtitle()
    }

    override fun onDownloadProgress(chatId: String, msgId: String, pct: Int) {
        if (chatId != this.chatId) return
        adapter.setVideoProgress(messageList, msgId, pct)
    }

    override fun onChatMerged(fromId: String, toId: String) {
        // this chat's rows were folded into toId; retarget so the screen doesn't
        // go blank (it was querying an id that no longer has any messages)
        if (fromId != this.chatId) return
        // carry the saved scroll position over to the new key, or returning to
        // this chat would jump to the bottom and lose the user's place
        scrollStates.remove(fromId)?.let { scrollStates[toId] = it }
        Prefs.scrollAnchor(this, fromId)?.let { (id, off) ->
            Prefs.setScrollAnchor(this, toId, id, off)
            Prefs.setScrollAnchor(this, fromId, null, 0)
        }
        pendingRecordings.remove(fromId)?.let { pendingRecordings[toId] = it }
        chatId = toId
        Bridge.openChat(toId, owner = this) // re-route active-chat / notification suppression
        // the reload below replaces the adapter's list; positional search state
        // would still index into the old (possibly much larger) search window
        if (searchActive) {
            searchMatches = emptyList()
            currentMatch = -1
            updateSearchCount()
        }
        reload()
    }

    override fun onMessagesChanged(chatId: String) {
        if (chatId != this.chatId) return
        // while searching, the adapter holds a full-history snapshot the matches
        // index into; don't reload it out from under the search (still mark read)
        if (searchActive) {
            Bridge.markChatRead(chatId)
            return
        }
        // Mid-drag a reload is not safe: with a full window at the query LIMIT a
        // new message pushes the oldest row out, shifting every adapter position
        // the drag range-select's anchor is expressed in — the selection would
        // then be one row off from what's under the finger. Defer to drag end.
        if (dragSelect?.isDragging == true) {
            reloadAfterDrag = true
            Bridge.markChatRead(chatId)
            return
        }
        reload(markRead = true)
    }

    // a message change that arrived while a drag range-select was in progress
    private var reloadAfterDrag = false

    /** Called by the drag listener when a range-select gesture ends. */
    fun onDragSelectFinished() {
        if (!reloadAfterDrag) return
        reloadAfterDrag = false
        reload(markRead = true)
    }

    // --- in-chat search ------------------------------------------------------

    private fun openSearch() {
        searchActive = true
        // drop any pending "download then open" so closing search (which
        // reloads) can't suddenly launch a video the user has moved on from
        pendingVideoOpen = null
        searchBar.visibility = android.view.View.VISIBLE
        searchInput.requestFocus()
        showKeyboard(searchInput)
        // load a large recent window so search covers well beyond the normal view
        // (bounded to avoid loading a huge history into memory at once)
        io.execute {
            val all = Bridge.db.messages(chatId, SEARCH_LOAD_LIMIT)
            val names = contactNames()
            val quoteNames = quoteNamesFor(all)
            runOnUiThread {
                val q = searchInput.text?.toString().orEmpty()
                // search indexes into the applied list, so run it once the diff
                // has committed the full-history window
                adapter.submit(all, names, quoteNames) {
                    if (q.isNotBlank()) runSearch(q)
                }
            }
        }
    }

    private fun closeSearch() {
        searchActive = false
        main.removeCallbacks(searchDebounce)
        searchBar.visibility = android.view.View.GONE
        searchInput.setText("")
        adapter.highlightQuery = ""
        // clear the highlight spans off the rows already on screen; the reload
        // below only rebinds rows the diff considers changed
        rebindVisible()
        searchMatches = emptyList()
        currentMatch = -1
        searchCount.text = ""
        hideKeyboard(searchInput)
        reload() // restore the normal (recent) message window
    }

    // Coalesces keystrokes: runSearch rebinds rows and kicks off a scan, and it
    // ran on every character typed, next to the keyboard's own frame budget.
    private val searchDebounce = Runnable { runSearch(searchInput.text?.toString().orEmpty()) }

    // Rebinds only what is on screen. notifyDataSetChanged() rebound the ENTIRE
    // adapter — in search mode that window holds up to SEARCH_LOAD_LIMIT (5000)
    // rows, each bind re-running highlighted(), Linkify and the padding/gravity
    // work. Rows scrolled to later bind with the current query anyway.
    private fun rebindVisible() {
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (first < 0 || last < first) return
        adapter.notifyItemRangeChanged(first, last - first + 1)
    }

    private fun runSearch(query: String) {
        val q = query.trim()
        adapter.highlightQuery = q
        rebindVisible()
        if (q.isEmpty()) {
            searchMatches = emptyList()
            currentMatch = -1
            searchCount.text = ""
            return
        }
        // The window can hold SEARCH_LOAD_LIMIT rows, so scan off the main
        // thread — this runs on every keystroke, next to the keyboard's own
        // frame budget.
        val msgs = adapter.messagesSnapshot()
        io.execute {
            val matches = msgs.indices.filter { i ->
                val m = msgs[i]
                m.msgType != "audio" && m.text.contains(q, ignoreCase = true)
            }
            runOnUiThread {
                // a newer keystroke superseded this scan
                if (adapter.highlightQuery != q) return@runOnUiThread
                searchMatches = matches
                currentMatch = matches.size - 1 // start at the most recent match
                if (currentMatch >= 0) messageList.scrollToPosition(matches[currentMatch])
                updateSearchCount()
            }
        }
    }

    /**
     * Records the first visible message and how far it sits below the top, so a
     * later run can land on the same message even though every adapter position
     * has been rebuilt. Nothing is stored while at the bottom — that chat should
     * open on whatever arrived since.
     */
    private fun saveScrollAnchor(atBottom: Boolean) {
        if (atBottom) {
            Prefs.setScrollAnchor(this, chatId, null, 0)
            return
        }
        val pos = lm.findFirstVisibleItemPosition()
        if (pos < 0) return
        val id = adapter.messageIdAt(pos)
        if (id.isEmpty()) return
        val offset = lm.findViewByPosition(pos)?.top ?: 0
        Prefs.setScrollAnchor(this, chatId, id, offset)
    }

    /** Restores the stored anchor; false when it is gone or not loaded yet. */
    private fun restoreScrollAnchor(): Boolean {
        val (msgId, offset) = Prefs.scrollAnchor(this, chatId) ?: return false
        val pos = adapter.indexOfMessage(msgId)
        if (pos < 0) return false
        lm.scrollToPositionWithOffset(pos, offset)
        return true
    }

    private fun stepMatch(delta: Int) {
        if (searchMatches.isEmpty()) return
        val n = searchMatches.size
        currentMatch = ((currentMatch + delta) % n + n) % n
        messageList.scrollToPosition(searchMatches[currentMatch])
        updateSearchCount()
    }

    private fun updateSearchCount() {
        searchCount.text = if (searchMatches.isEmpty()) getString(R.string.no_results)
            else "${currentMatch + 1}/${searchMatches.size}"
    }

    // --- chat menu -----------------------------------------------------------

    private fun showChatMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.sync_all)
        popup.menu.add(0, 2, 1, R.string.export_chat)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    // false = another whole-history op (sync-all/export) is busy
                    if (!Bridge.syncAllHistory(chatId)) {
                        Toast.makeText(this, R.string.history_busy, Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                2 -> { startChatExport(); true }
                else -> false
            }
        }
        popup.show()
    }

    // --- chat export ---------------------------------------------------------

    private fun startChatExport() {
        // this screen serves both protocols, so the file name has to say which
        // one the conversation came from
        val proto = getString(if (Tg.isTgId(chatId)) R.string.telegram else R.string.whatsapp)
        // shared sanitizer (Files.kt), so this and the staging-file naming can't
        // drift on which characters are considered unsafe
        createExportFile.launch(
            getString(R.string.export_file_name, proto, safeDisplayFileName(chatDisplayName))
        )
    }

    // The bridge owns the export (fetch, merge, file write); this activity
    // only observes progress/completion via the listener, so leaving the chat
    // or rotating mid-export neither leaks the activity nor loses the result.
    private fun writeChatExport(uri: Uri) {
        // the export runs in the Bridge singleton and may outlive this activity
        // (rotation / backing out mid-export), so promote the one-shot
        // activity-result grant to a persistable one the bridge can still write
        // through after this activity is gone; the bridge releases it when done
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            // provider offered no persistable grant; the transient one lasts as
            // long as this activity, which still covers a quick foreground export
        }
        if (!Bridge.exportChat(chatId, uri)) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        } else {
            updateSubtitle()
        }
    }

    override fun onChatExportProgress(chatId: String, fetched: Int) {
        if (chatId == this.chatId) updateSubtitle()
    }

    override fun onChatExportDone(chatId: String, messages: Int, complete: Boolean, success: Boolean) {
        if (chatId != this.chatId) return
        updateSubtitle()
        val text = when {
            !success -> getString(R.string.export_failed)
            complete -> getString(R.string.export_done, messages)
            else -> getString(R.string.export_partial, messages)
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun onChatSyncProgress(chatId: String, progress: Int) {
        if (chatId != this.chatId) return
        updateSubtitle()
        when {
            progress >= 100 -> Toast.makeText(this, R.string.sync_done, Toast.LENGTH_SHORT).show()
            progress < 0 -> Toast.makeText(this, R.string.sync_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // --- presence ------------------------------------------------------------

    private fun updateSubtitle() {
        // single owner of the subtitle line: a running export, then sync-all,
        // then transient activity (typing/recording) take precedence over the
        // base presence line, shown in accent; it reverts cleanly as they clear
        val exportCount = Bridge.exportProgress(chatId)
        val syncPct = Bridge.syncAllProgress(chatId)
        val state = Bridge.chatState(chatId)
        val seeking = seekQuotedId != null
        val transient =
            exportCount >= 0 || syncPct >= 0 || seeking || state == "typing" || state == "recording"
        val subtitle = when {
            exportCount >= 0 -> getString(R.string.export_progress, exportCount)
            syncPct >= 0 -> getString(R.string.state_syncing, syncPct)
            seeking -> getString(R.string.syncing_message)
            state == "typing" -> activityLine(getString(R.string.typing))
            state == "recording" -> activityLine(getString(R.string.recording_voice))
            isGroup -> null
            Bridge.isOnline(chatId) -> getString(R.string.online)
            Bridge.lastSeenOf(chatId) > 0 ->
                getString(R.string.last_seen, TimeFormat.compactWithTime(this, Bridge.lastSeenOf(chatId)))
            // no exact time: Telegram only reports a bucket unless both sides
            // share their last-seen
            Bridge.lastSeenApproxOf(chatId) != 0 -> getString(Bridge.lastSeenApproxOf(chatId))
            else -> null
        }
        toolbarSubtitle.text = subtitle
        toolbarSubtitle.setTextColor(
            if (transient) themeColor(R.attr.chatAccent) else getColor(R.color.text_secondary)
        )
        toolbarSubtitle.visibility =
            if (subtitle.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    // In groups the actor is ambiguous, so name them: "Alice is typing…", or
    // "Alice, Bob are typing…" when several are active at once. One-to-one
    // chats already imply who it is, so the bare activity stands.
    private fun activityLine(activity: String): String {
        if (!isGroup) return activity
        val name = Bridge.chatStateName(chatId)
        if (name.isNullOrEmpty()) return activity
        val fmt = if (Bridge.chatStateActorCount(chatId) > 1)
            R.string.group_activity_plural else R.string.group_activity
        return getString(fmt, name, activity)
    }

    // A presence subscription is short-lived on the server side and is dropped
    // entirely on reconnect, so subscribing once when the chat opened left the
    // online/last-seen line frozen for as long as the user stayed on the screen
    // (leaving to the chat list and coming back was the only way to refresh it).
    // Re-arm it while the chat is in the foreground; each subscribe is a small
    // stanza and the server answers with the contact's current presence.
    private val presenceTicker = object : Runnable {
        override fun run() {
            Bridge.subscribePresence(chatId)
            main.postDelayed(this, PRESENCE_RESUBSCRIBE_MS)
        }
    }

    override fun onChatState(chatId: String, state: String) {
        if (chatId == this.chatId) updateSubtitle()
    }

    override fun onPresence(userId: String, isOnline: Boolean, lastSeen: Long) {
        if (userId == chatId) updateSubtitle()
    }

    // --- compose --------------------------------------------------------------

    private fun updateActionButton() {
        if (recorder != null || pendingAudio != null) return // recording/pending UI owns the buttons meanwhile
        val sends = input.text.isNotBlank()
        actionButton.setImageResource(if (sends) R.drawable.ic_send else R.drawable.ic_mic)
        // the button's job swaps with the input, so its label has to follow the
        // icon — a fixed "Send" announced the mic as a send button
        actionButton.contentDescription =
            getString(if (sends) R.string.send else R.string.record_voice)
    }

    private fun sendCurrentText() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val editing = editTarget
        // the window may have lapsed while the edit was being typed; check
        // BEFORE clearing the input, so a rejected edit doesn't throw away what
        // the user just wrote
        if (editing != null && !Bridge.canEdit(editing)) {
            Toast.makeText(this, R.string.edit_expired, Toast.LENGTH_SHORT).show()
            return
        }
        input.text.clear()
        val replying = replyTarget
        when {
            editing != null -> Bridge.editMessage(chatId, editing.id, text, editing.timeSent)
            replying != null -> Bridge.sendReply(chatId, text, replying)
            else -> Bridge.sendText(chatId, text)
        }
        clearComposeContext()
    }

    // --- attach (file picker) -------------------------------------------------

    private fun onFilePicked(uri: Uri) {
        // capture the reply target now and clear the compose bar; the file may
        // be attached as a reply to that message
        val quoted = replyTarget
        clearComposeContext()
        io.execute {
            val name = uriDisplayName(uri) ?: "file"
            val local = copyUriToCache(uri, "attach", name)
            val mime = contentResolver.getType(uri) ?: ""
            runOnUiThread {
                if (local == null) {
                    Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                // one owner of the mime→sender mapping (shared with the share flow)
                Bridge.sendFile(chatId, local.absolutePath, name, mime, quoted = quoted)
            }
        }
    }

    // --- voice recording -------------------------------------------------------

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        // a STAGING_PREFIXES name via the shared helper, so the startup sweep and
        // this producer can't drift apart on the naming contract
        val file = stagingFile("rec", "voice.ogg")
        var fresh: MediaRecorder? = null
        try {
            @Suppress("DEPRECATION")
            val r = MediaRecorder()
            fresh = r
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioSamplingRate(48000)
            r.setAudioEncodingBitRate(24000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            fresh = null // ownership transferred; stopRecorder releases it now
            recordFile = file
            recordStart = SystemClock.elapsedRealtime()
            recordAmps.clear()
            acquireRecordWakeLock()
            main.post(ampTicker)
            // swap the input for a live timer; attach becomes cancel, action sends
            input.visibility = android.view.View.GONE
            recordTimer.visibility = android.view.View.VISIBLE
            recordTimer.text = "●  0:00"
            showRecordingButtons()
            main.post(recordTicker)
        } catch (e: Exception) {
            // prepare()/start() failed (the mic is held by a call or another
            // app, or the codec config was rejected): release the recorder we
            // created, or its native encoder and audio input session stay
            // allocated until finalization — repeated attempts pile them up
            Toast.makeText(this, R.string.recording_failed, Toast.LENGTH_SHORT).show()
            try { fresh?.release() } catch (e2: Exception) {}
            recorder = null
            file.delete()
        }
    }

    private fun acquireRecordWakeLock() {
        if (recordWakeLock?.isHeld == true) return
        try {
            @Suppress("DEPRECATION")
            val wl = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "unichat:recording")
            // timeout as a backstop only: a lock leaked by an unforeseen path
            // would otherwise keep the screen on until the battery died
            wl.acquire(RECORD_WAKE_LOCK_MS)
            recordWakeLock = wl
        } catch (e: Exception) {
            // no lock is survivable (recording just stops on screen-off); a
            // crash here would lose the recording outright
        }
    }

    private fun releaseRecordWakeLock() {
        val wl = recordWakeLock ?: return
        recordWakeLock = null
        try { if (wl.isHeld) wl.release() } catch (e: Exception) {}
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 2 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        }
        if (code == 3) {
            // Only GPS gives a precise fix, and on API 29 asking it for one with
            // coarse-only permission throws (swallowed, so the user just waited
            // out the whole timeout). Take whichever providers the granted
            // permission actually allows.
            val fine = perms.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)
                .let { i -> i >= 0 && results.getOrNull(i) == PackageManager.PERMISSION_GRANTED }
            val coarse = perms.indexOf(Manifest.permission.ACCESS_COARSE_LOCATION)
                .let { i -> i >= 0 && results.getOrNull(i) == PackageManager.PERMISSION_GRANTED }
            if (fine || coarse) acquireAndSendLocation(preciseAllowed = fine)
        }
    }

    // Stops the live MediaRecorder (if any) and returns the file it wrote plus
    // its duration, without deciding whether to send, discard, or hold onto
    // it — that choice belongs to the caller.
    private fun stopRecorder(): Pair<File, Int>? {
        val r = recorder ?: return null
        recorder = null
        releaseRecordWakeLock()
        main.removeCallbacks(recordTicker)
        main.removeCallbacks(ampTicker)
        val duration = ((SystemClock.elapsedRealtime() - recordStart) / 1000).toInt()
        try {
            r.stop()
        } catch (e: Exception) {
            recordFile?.delete()
            recordFile = null
        }
        r.release()
        val file = recordFile
        recordFile = null
        return file?.let { it to duration }
    }

    // Called whenever the app loses the foreground/focus mid-recording (an
    // incoming call, switching apps, the screen turning off, an overlay
    // stealing focus…): stops the mic immediately but keeps the captured
    // audio as a pending voice message the user can still send or cancel —
    // it must never be silently thrown away.
    private fun captureRecordingAsPending() {
        // stopRecorder() returns null both when nothing was recording (the
        // common no-op path — this is called from onPause/onStop/focus loss) and
        // when stop() threw, which deletes the unusable file. Only the latter
        // leaves the composer showing the recording UI, and returning without
        // resetting it stranded the screen there: recorder and pendingAudio were
        // both null, so neither button could get back out.
        val wasRecording = recorder != null
        val result = stopRecorder()
        if (result == null) {
            if (wasRecording) resetRecordingUi()
            return
        }
        val (file, duration) = result
        if (duration < 1) {
            file.delete()
            resetRecordingUi()
            return
        }
        pendingAudio = file
        pendingDuration = duration
        // survive this instance: re-entering the chat restores the send/cancel UI
        // (amplitudes included, or the restored recording would be sent with an
        // empty waveform and render as a flat line for the recipient)
        pendingRecordings[chatId] = PendingRecording(file, duration, recordAmps.toList())
        recordTimer.text = getString(R.string.recording_paused, TimeFormat.mmss(duration))
    }

    /**
     * Re-adopts a recording captured before this instance existed (Back pressed
     * mid-recording, or the activity recreated), so the user can still send or
     * discard it instead of losing it silently.
     */
    private fun restorePendingRecording() {
        if (recorder != null || pendingAudio != null) return
        val pending = pendingRecordings[chatId] ?: return
        val file = pending.file
        if (!file.exists()) { pendingRecordings.remove(chatId); return }
        pendingAudio = file
        pendingDuration = pending.duration
        // the amplitude envelope belongs to the recording, not to the instance
        // that captured it: without it buildWaveform returned a zero-length
        // array and the voice note went out with no waveform at all
        recordAmps.clear()
        recordAmps.addAll(pending.amps)
        input.visibility = android.view.View.GONE
        recordTimer.visibility = android.view.View.VISIBLE
        recordTimer.text = getString(R.string.recording_paused, TimeFormat.mmss(pending.duration))
        showRecordingButtons()
    }

    // While a recording is live or pending, both compose buttons take on another
    // job — attach cancels, action sends — so their labels move with their icons.
    private fun showRecordingButtons() {
        attachButton.setImageResource(R.drawable.ic_close)
        attachButton.contentDescription = getString(R.string.cancel_recording)
        actionButton.setImageResource(R.drawable.ic_send)
        actionButton.contentDescription = getString(R.string.send)
    }

    private fun resetRecordingUi() {
        recordTimer.visibility = android.view.View.GONE
        input.visibility = android.view.View.VISIBLE
        attachButton.setImageResource(R.drawable.ic_attach)
        attachButton.contentDescription = getString(R.string.attach)
        updateActionButton()
    }

    private fun finishRecording(send: Boolean) {
        val file: File
        val duration: Int
        if (recorder != null) {
            val result = stopRecorder() ?: run { resetRecordingUi(); return }
            file = result.first
            duration = result.second
        } else {
            file = pendingAudio ?: return
            duration = pendingDuration
            pendingAudio = null
            pendingDuration = 0
            pendingRecordings.remove(chatId)
        }
        resetRecordingUi()
        if (send && duration >= 1) {
            // a voice note can be a reply; attach the quote then clear the bar
            val quoted = replyTarget
            clearComposeContext()
            Bridge.sendAudio(chatId, file.absolutePath, duration, quoted, buildWaveform(recordAmps))
        } else {
            // Tapping mic then send within a second gives duration 0. Silently
            // deleting it left the user believing a voice note had been sent,
            // so say what happened when they explicitly asked to send.
            if (send) Toast.makeText(this, R.string.recording_too_short, Toast.LENGTH_SHORT).show()
            file.delete()
        }
    }

    // --- attach menu / location ---------------------------------------------

    private fun showAttachMenu() {
        val items = arrayOf(getString(R.string.attach_file), getString(R.string.attach_location))
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickFile.launch("*/*")
                    1 -> sendCurrentLocation()
                }
            }
            .show()
    }

    private fun sendCurrentLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                3
            )
            return
        }
        acquireAndSendLocation(preciseAllowed = fine)
    }

    // Framework-only location: a fresh last-known fix is used directly,
    // otherwise a one-shot fix is requested from every enabled provider (GPS
    // works without any Google services; network location is opportunistic).
    private fun acquireAndSendLocation(preciseAllowed: Boolean) {
        // Drop any previous attempt first. locationTimeout used to be
        // overwritten without removing the Runnable already posted for it, so
        // tapping "location" twice left the first timeout scheduled; when it
        // fired it ran releaseLocationRequests(), cancelling the SECOND
        // request's listeners and toasting a failure for a request that was
        // still perfectly healthy.
        releaseLocationRequests()
        val lm = getSystemService(android.location.LocationManager::class.java)
        val providers = if (preciseAllowed) listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        ) else listOf(android.location.LocationManager.NETWORK_PROVIDER)
        var last: android.location.Location? = null
        for (p in providers) {
            val loc = try { lm.getLastKnownLocation(p) } catch (e: Exception) { null }
            if (loc != null && (last == null || loc.time > last!!.time)) last = loc
        }
        val fresh = last
        if (fresh != null && System.currentTimeMillis() - fresh.time < 30_000) {
            Bridge.sendLocation(chatId, fresh.latitude, fresh.longitude)
            return
        }

        val enabled = providers.filter { p -> try { lm.isProviderEnabled(p) } catch (e: Exception) { false } }
        if (enabled.isEmpty()) {
            Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.getting_location, Toast.LENGTH_SHORT).show()
        var done = false
        val timeout = Runnable {
            done = true
            releaseLocationRequests()
            Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show()
        }
        locationTimeout = timeout
        val onFix = fun(loc: android.location.Location?) {
            if (done || loc == null) return
            done = true
            main.removeCallbacks(timeout)
            locationTimeout = null
            releaseLocationRequests()
            Bridge.sendLocation(chatId, loc.latitude, loc.longitude)
        }
        for (p in enabled) requestSingleFix(lm, p, onFix)
        main.postDelayed(timeout, 25_000)
    }

    // In-flight one-shot location requests, so leaving the screen can cancel
    // them: an un-removed listener keeps the provider running and retains this
    // activity through the onFix closure, indefinitely when no fix ever arrives.
    private val locationListeners = ArrayList<android.location.LocationListener>()
    private val locationCancels = ArrayList<android.os.CancellationSignal>()
    private var locationTimeout: Runnable? = null

    private fun releaseLocationRequests() {
        val lm = getSystemService(android.location.LocationManager::class.java)
        for (l in locationListeners) runCatching { lm.removeUpdates(l) }
        locationListeners.clear()
        for (c in locationCancels) runCatching { c.cancel() }
        locationCancels.clear()
        locationTimeout?.let { main.removeCallbacks(it) }
        locationTimeout = null
    }

    private fun requestSingleFix(
        lm: android.location.LocationManager, provider: String,
        onFix: (android.location.Location?) -> Unit,
    ) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val cancel = android.os.CancellationSignal()
                locationCancels.add(cancel)
                lm.getCurrentLocation(provider, cancel, mainExecutor) { onFix(it) }
            } else {
                // a full object, NOT a lambda: LocationListener's extra
                // methods only have framework defaults from API 30 on, so on
                // API 29 a provider-status callback on a lambda-generated
                // class would crash with AbstractMethodError
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) =
                        onFix(location)
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, status: Int, extras: android.os.Bundle?) {}
                }
                locationListeners.add(listener)
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, mainLooper)
            }
        } catch (e: SecurityException) {
            onFix(null)
        }
    }

    /** Opens a location message's coordinates in whatever maps app is installed. */
    private fun openLocation(msg: MessageRow) {
        if (msg.fileId.isEmpty()) return
        val uri = Uri.parse("geo:${msg.fileId}?q=${msg.fileId}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app_for_file, Toast.LENGTH_SHORT).show()
        }
    }

    // --- documents -------------------------------------------------------------

    private fun openDocument(msg: MessageRow) {
        if (msg.filePath.isEmpty()) {
            downloadWithToast(msg)
            return
        }
        openMediaFile(msg)
    }

    // Video tapped outside its download icon: open now if it's here, otherwise
    // download and open automatically once the file lands (see reload()).
    private fun openVideo(msg: MessageRow) {
        if (msg.filePath.isNotEmpty()) {
            openMediaFile(msg)
            return
        }
        pendingVideoOpen = msg.id
        downloadWithToast(msg)
    }

    // Hands a downloaded file to an external viewer (video player, doc reader…).
    private fun openMediaFile(msg: MessageRow) {
        val (uri, mime) = providedFile(File(msg.filePath), "*/*")
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app_for_file, Toast.LENGTH_SHORT).show()
        }
    }

    // A user-asked download plus the "Downloading…" acknowledgement toast.
    private fun downloadWithToast(msg: MessageRow) {
        Bridge.downloadFile(msg, userInitiated = true)
        Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show()
    }

    // --- forward -----------------------------------------------------------

    // Message-action dialog. Each entry carries its own action, so dispatch is
    // by identity rather than by comparing the tapped label against localized
    // strings — which silently ran the wrong action for any translation that
    // rendered two labels the same (the CAB below already dispatches by id).
    private fun showMessageActions(msg: MessageRow) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        fun add(res: Int, action: () -> Unit) = actions.add(getString(res) to action)

        // view-once media is a label and nothing else here: its keys are never
        // shared with a companion device, so there is no body to forward or
        // share and offering either would only fail
        val viewOnce = msg.msgType == "viewonce"

        add(R.string.reply) { startReply(msg) }
        if (!viewOnce) add(R.string.forward) { pickForwardTarget(msg) }
        // the protocol only allows editing for a limited window after sending
        if (msg.fromMe && msg.msgType == "" && Bridge.canEdit(msg)) add(R.string.edit) { startEdit(msg) }
        add(R.string.delete) { confirmDelete(listOf(msg)) }
        if (msg.text.isNotEmpty() && msg.msgType != "audio") add(R.string.copy) { copyText(msg.text) }
        if (msg.msgType != "" && !viewOnce) add(R.string.share) { shareMessage(msg) }
        add(R.string.react) { showReactionPicker(msg) }

        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .show()
    }

    // WhatsApp's quick-reaction set in a horizontal row; tapping one sends it,
    // the neutral button clears an earlier reaction.
    private fun showReactionPicker(msg: MessageRow) {
        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER
        val pad = (14 * resources.displayMetrics.density).toInt()
        row.setPadding(pad, pad, pad, pad)
        val dialog = AlertDialog.Builder(this)
            .setView(row)
            .setNeutralButton(R.string.remove_reaction) { _, _ -> Bridge.sendReaction(msg, "") }
            .create()
        for (emoji in listOf("👍", "❤️", "😂", "😮", "😢", "🙏")) {
            val tv = android.widget.TextView(this)
            tv.text = emoji
            tv.textSize = 30f
            tv.setPadding(pad / 2, 0, pad / 2, 0)
            tv.setOnClickListener {
                Bridge.sendReaction(msg, emoji)
                dialog.dismiss()
            }
            row.addView(tv)
        }
        dialog.show()
    }

    // --- reply / edit compose context --------------------------------------

    private fun startReply(msg: MessageRow) {
        editTarget = null
        replyTarget = msg
        val preview = Bridge.quotedPreview(msg)
        contextBar.visibility = android.view.View.VISIBLE
        focusComposer()
        if (msg.fromMe) {
            contextText.text = getString(R.string.replying_to, getString(R.string.you), preview)
            return
        }
        // Resolve the author off the main thread (this runs from a dialog click)
        // and through senderLabel, the single owner of the name fallback chain —
        // falling back to the CHAT name here labelled a group reply with the
        // group's own name while the bubble above showed the push name.
        contextText.text = getString(
            R.string.replying_to, senderLabel(emptyMap(), msg.senderId, msg.senderName), preview
        )
        io.execute {
            val name = Bridge.db.contactName(msg.senderId)
                ?.let { mapOf(msg.senderId to it) } ?: emptyMap()
            val who = senderLabel(name, msg.senderId, msg.senderName)
            runOnUiThread {
                if (isFinishing || replyTarget?.id != msg.id) return@runOnUiThread
                contextText.text = getString(R.string.replying_to, who, preview)
            }
        }
    }

    private fun startEdit(msg: MessageRow) {
        replyTarget = null
        editTarget = msg
        contextText.text = getString(R.string.editing_message)
        contextBar.visibility = android.view.View.VISIBLE
        input.setText(msg.text)
        input.setSelection(input.text.length)
        focusComposer()
    }

    // Focuses the message input and opens the virtual keyboard. Reply/edit is
    // picked from the actions dialog, which still owns window focus while it
    // dismisses — showSoftInput is ignored then. If our window isn't focused
    // yet, defer the request to onWindowFocusChanged.
    private var pendingShowKeyboard = false

    private fun focusComposer() {
        input.requestFocus()
        if (hasWindowFocus()) showKeyboard() else pendingShowKeyboard = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingShowKeyboard) {
            pendingShowKeyboard = false
            input.post { showKeyboard() }
        }
        // some focus-stealing overlays (e.g. a heads-up incoming-call banner)
        // don't trigger onPause; catch those here too so a recording never
        // keeps running — and never gets discarded — behind the user's back
        if (!hasFocus) captureRecordingAsPending()
    }

    // one place that knows the SHOW_IMPLICIT flag and the window-focus caveat
    // documented above; the search bar goes through it too
    private fun showKeyboard(target: android.view.View = input) {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.showSoftInput(target, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(target: android.view.View = input) {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    private fun clearComposeContext() {
        replyTarget = null
        editTarget = null
        contextBar.visibility = android.view.View.GONE
    }

    // Delete-confirmation dialog shared by the single-message action and the
    // multi-select bar. "Delete for everyone" is offered only when EVERY
    // message is ours and still within WhatsApp's revoke window — the server
    // would reject the too-old ones.
    private fun confirmDelete(msgs: List<MessageRow>, onDone: () -> Unit = {}) {
        // same reason as showMessageActions: dispatch by the action attached to
        // the entry, never by the localized label that was tapped
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (msgs.all { Bridge.canDeleteForEveryone(it) }) {
            options.add(getString(R.string.delete_for_everyone) to {
                for (m in msgs) Bridge.deleteForEveryone(chatId, m.id)
            })
        }
        options.add(getString(R.string.delete_for_me) to {
            for (m in msgs) Bridge.deleteForMe(chatId, m.id)
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_message)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second()
                onDone()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Share a message out to any other app via the system share sheet. */
    private fun shareMessage(msg: MessageRow) {
        val intent = Intent(Intent.ACTION_SEND)
        if (msg.msgType == "location") {
            // nothing to download: share the raw coordinates (any maps app
            // accepts them) plus a clickable maps link
            val link = "https://maps.google.com/?q=${msg.fileId}"
            intent.type = "text/plain"
            intent.putExtra(
                Intent.EXTRA_TEXT,
                listOf(msg.text, msg.fileId, link).filter { it.isNotEmpty() }.joinToString("\n")
            )
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
            return
        }
        if (msg.msgType != "") {
            if (isStillSending(msg)) {
                Toast.makeText(this, R.string.still_sending, Toast.LENGTH_SHORT).show()
                return
            }
            if (msg.filePath.isEmpty()) {
                downloadWithToast(msg)
                return
            }
            val (uri, mime) = providedFile(File(msg.filePath), "*/*")
            intent.type = mime
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            if (msg.msgType == "image" && msg.text.isNotEmpty()) {
                intent.putExtra(Intent.EXTRA_TEXT, msg.text)
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, msg.text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun copyText(text: String) {
        copyToClipboard("message", text, R.string.copied)
    }

    // Single-message forward: same picker and same ordered Bridge batch path
    // as the multi-select forward, so the type mapping lives only in
    // Bridge.forwardOneBlocking.
    private fun pickForwardTarget(msg: MessageRow) {
        showForwardPicker { targets -> forwardMessagesToTargets(listOf(msg), targets) }
    }

    // Builds the forward-target picker: every chat, with "You (yourself)"
    // pinned first, labels falling back to the phone number for unknown
    // contacts. Shared by the single-message and multi-select forwards.
    private fun showForwardPicker(onPick: (List<String>) -> Unit) {
        io.execute {
            val ids = ArrayList<String>()
            val labels = ArrayList<String>()
            // your own chat on each linked account, pinned first and named after
            // its protocol; listed even with no history, since forwarding to
            // yourself is always available
            for (self in listOf(Bridge.selfId(), if (Tg.hasSession()) Tg.selfId() else "")) {
                if (self.isEmpty() || self in ids) continue
                ids.add(self)
                labels.add(selfPickerLabel(this, self))
            }
            for (chat in Bridge.db.chats()) {
                if (chat.id in ids) continue
                ids.add(chat.id)
                labels.add(chat.displayLabelWithProto(this))
            }
            runOnUiThread {
                // the chats query runs on the shared serial io thread and can be
                // queued behind other DB work, so this screen may be gone by now;
                // AlertDialog.show() on a dead window throws BadTokenException
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ids.isEmpty()) {
                    // don't just do nothing: Forward would look broken
                    Toast.makeText(this, R.string.no_chats, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                showTargetPicker(R.string.forward_to, labels, ids) { onPick(it) }
            }
        }
    }

    // A just-sent media message renders from its cacheDir staging file until
    // the upload finishes; that file is deleted right after, so re-sends must
    // wait for the permanent media copy to take its place.
    private fun isStillSending(msg: MessageRow): Boolean =
        msg.fromMe && Bridge.isStagingPath(msg.filePath)

    // --- multi-select --------------------------------------------------------
    // Long-pressing a message enters selection mode (driven by the adapter); a
    // contextual action bar then offers forward/copy/delete over the whole set.

    private var actionMode: androidx.appcompat.view.ActionMode? = null

    // Opens/updates/closes the contextual bar as the selection changes. Delete
    // is offered only when every selected message is our own.
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
        override fun onCreateActionMode(mode: androidx.appcompat.view.ActionMode, menu: android.view.Menu): Boolean {
            menu.add(0, M_FORWARD, 0, R.string.forward).apply {
                setIcon(R.drawable.ic_forward)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            menu.add(0, M_COPY, 1, R.string.copy).apply {
                setIcon(R.drawable.ic_copy)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            menu.add(0, M_DELETE, 2, R.string.delete).apply {
                setIcon(R.drawable.ic_delete)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            return true
        }

        override fun onPrepareActionMode(mode: androidx.appcompat.view.ActionMode, menu: android.view.Menu): Boolean {
            val msgs = adapter.selectedMessages()
            // hide delete if any selected message isn't ours
            menu.findItem(M_DELETE)?.isVisible = msgs.isNotEmpty() && msgs.all { it.fromMe }
            return true
        }

        override fun onActionItemClicked(mode: androidx.appcompat.view.ActionMode, item: android.view.MenuItem): Boolean {
            val msgs = adapter.selectedMessages()
            if (msgs.isEmpty()) return false
            when (item.itemId) {
                M_FORWARD -> forwardSelected(msgs)
                M_COPY -> { copySelected(msgs); mode.finish() }
                M_DELETE -> confirmDelete(msgs) { actionMode?.finish() }
            }
            return true
        }

        override fun onDestroyActionMode(mode: androidx.appcompat.view.ActionMode) {
            actionMode = null
            adapter.clearSelection()
        }
    }

    // Copies the text of the selected messages (media rows contribute their
    // caption; audio has none), newest-last, joined by newlines.
    private fun copySelected(msgs: List<MessageRow>) {
        val text = msgs.filter { it.text.isNotEmpty() && it.msgType != "audio" }
            .joinToString("\n") { it.text }
        if (text.isNotEmpty()) copyToClipboard("messages", text, R.string.copied)
        else Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show()
    }

    private fun forwardSelected(msgs: List<MessageRow>) {
        showForwardPicker { targets ->
            forwardMessagesToTargets(msgs, targets)
            actionMode?.finish()
        }
    }

    // Forwards the selected messages to every chosen chat. Media that is still
    // uploading, or not yet downloaded, is skipped (and a download is kicked
    // off); the rest are handed to the bridge as one ordered batch so they
    // arrive in the same order they were originally sent.
    private fun forwardMessagesToTargets(msgs: List<MessageRow>, targets: List<String>) {
        if (targets.isEmpty() || msgs.isEmpty()) return
        // preserve chronological order (msgs already is) so the batch is ordered
        val ready = ArrayList<MessageRow>()
        var stillSending = false
        var downloading = false
        for (msg in msgs) {
            // nothing of a view-once message exists on this device to send on
            if (msg.msgType == "viewonce") continue
            val isFileMedia = msg.msgType in listOf("image", "audio", "video", "document")
            if (isFileMedia && isStillSending(msg)) { stillSending = true; continue }
            if (isFileMedia && msg.filePath.isEmpty()) {
                Bridge.downloadFile(msg, userInitiated = true)
                downloading = true
                continue
            }
            ready.add(msg)
        }
        if (ready.isNotEmpty()) Bridge.forwardMessages(targets, ready) {}
        val toastRes = when {
            ready.isNotEmpty() -> R.string.forwarded
            stillSending -> R.string.still_sending
            downloading -> R.string.downloading
            else -> R.string.share_failed
        }
        Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show()
    }
}
