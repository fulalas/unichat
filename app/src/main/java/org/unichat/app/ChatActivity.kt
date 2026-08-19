package org.unichat.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.ContactsContract
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

private class PendingRecording(val file: File, val duration: Int, val amps: List<Int>)

class ChatActivity : BaseActivity(), Bridge.UiListener {

    companion object {
        private const val MAX_SCROLL_STATES = 50
        private const val SEARCH_LOAD_LIMIT = 5000
        private const val DEEP_TICK_MS = 8_000L
        private const val DEEP_IDLE_ROUNDS = 3
        private const val LOCAL_PAGE = 500
        private const val RECORD_WAKE_LOCK_MS = 30 * 60 * 1000L
        private val FILE_MEDIA_TYPES = PICTURE_TYPES + setOf("audio", "video", "document")
        private const val M_FORWARD = 1
        private const val M_COPY = 2
        private const val M_DELETE = 3
        private const val MAX_SEEK_PAGES = 20
        // Backstop against a dropped bridge callback ONLY, so it must outlast the
        // worst case of the operation it guards: MAX_SEEK_PAGES pages, each with
        // its own bridge-side timeout. A flat 60s used to abort slow-but-working
        // seeks and toast "message not loaded" while pages were still arriving.
        private val SEEK_TIMEOUT_MS = MAX_SEEK_PAGES * Bridge.historyTimeoutMs + 30_000L
        private const val SEEK_CONTEXT_ROWS = 20
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
    private val createExportFile = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let { writeChatExport(it) } }
    private lateinit var contextBar: android.view.View
    private lateinit var contextText: android.widget.TextView
    private var restoredScroll = false
    private var hasNewBelow = false
    private var pendingVideoOpen: String? = null
    private val quoteJumpReturns = ArrayDeque<String>()
    private var loadLimit = LOCAL_PAGE
    private var loadingMoreLocal = false

    private var seekQuotedId: String? = null
    private var seekOrigin: MessageRow? = null
    // A message the image viewer asked us to jump to, applied on the next list
    // commit so the reload that follows onStart cannot scroll it away again.
    private var pendingJumpId: String? = null
    private var seekCenter = false
    private var seekFetching = false
    private val seekTimeout = Runnable { if (seekQuotedId != null) failSeek() }

    private var replyTarget: MessageRow? = null
    private var editTarget: MessageRow? = null

    private lateinit var searchBar: android.view.View
    private lateinit var searchInput: EditText
    private lateinit var searchCount: android.widget.TextView
    private lateinit var searchCoverageRow: android.view.View
    private lateinit var searchCoverage: android.widget.TextView
    private lateinit var searchDeeper: android.widget.Button
    private var searchMatches: List<Int> = emptyList()
    private var currentMatch = -1
    private var searchActive = false
    private var serverHits: List<String> = emptyList()
    private var serverTotal = 0
    private var serverNextFrom = 0L
    private var currentHit = -1
    // each keystroke and each hit starts a network round trip: only the newest
    // answer of each kind may be applied
    private var searchSeq = 0
    private var windowSeq = 0
    private var hitsLoading = false
    private var deepening = false
    private var idleRounds = 0
    // how much of the chat the scan covers. Grows as deepening pulls older
    // pages in, or the newly fetched messages would sit outside the window and
    // every round would re-scan the very same rows.
    private var searchLimit = SEARCH_LOAD_LIMIT
    private var deepOldest = 0L
    // Its own thread: these calls block for up to 30s and must not sit in front
    // of the shared io queue that reloads the chat
    private val searchExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    // Whether the list is showing the temporary window around a search hit
    // rather than the chat's own history. While it is, the normal (DB-backed)
    // pagination is wrong — the window grows from the server, on both ends.
    private var windowMode = false
    private var windowLoading = false
    private var windowTopDone = false
    private var windowBottomDone = false
    // media of window rows, fetched one row at a time; ids in flight, ids that
    // failed (so a rebind doesn't retry forever), and one waiting to be opened
    private val windowFetching = HashSet<String>()
    private val windowFailed = HashSet<String>()
    private var pendingWindowOpen: String? = null
    private val windowMedia = java.util.concurrent.Executors.newFixedThreadPool(2)
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

    private val recordAmps = ArrayList<Int>()
    private val ampTicker = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            recordAmps.add(try { r.maxAmplitude } catch (e: Exception) { 0 })
            main.postDelayed(this, 100)
        }
    }

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

    private val openImage = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = result.data?.getStringExtra("jumpTo") ?: return@registerForActivityResult
        // Deliberately NOT scrolled here. This callback runs after onStart,
        // which has already started a reload, and that reload's commit restores
        // the saved position — undoing any jump made now. It is parked instead
        // and applied once the list has settled.
        pendingJumpId = target
        if (searchActive) consumePendingJump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getStringExtra("chatId") ?: run { finish(); return }
        // WhatsApp chats swap the blue chat palette for the green one; must
        // happen before any view of this screen inflates
        applyProtocolTheme(Tg.isTgId(chatId))
        setContentView(R.layout.activity_chat)

        if (!Bridge.init(this)) { finish(); return }

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
            // Always from the STORED name, never from the intent's: the chat
            // list hands over a label it has already decorated, so re-decorating
            // that produced "Rafael (Telegram) (Telegram)". The extra is only
            // good enough to paint the toolbar before this read lands.
            val name = Bridge.db.displayName(chatId).let {
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
        searchCoverageRow = findViewById(R.id.searchCoverageRow)
        searchCoverage = findViewById(R.id.searchCoverage)
        searchDeeper = findViewById(R.id.searchDeeper)
        searchDeeper.setOnClickListener { toggleDeepSearch() }
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
            onNeedDownload = { msg, userInitiated ->
                if (windowMode) fetchWindowMedia(msg)
                else Bridge.downloadFile(msg, userInitiated)
            },
            onImageClick = { msg ->
                val intent = Intent(this, ImageViewActivity::class.java)
                intent.putExtra("path", msg.filePath)
                intent.putExtra("chatId", chatId)
                openImage.launch(intent)
            },
            onDocumentClick = { msg -> openDocument(msg) },
            onVideoOpen = { msg -> openVideo(msg) },
            onLocationClick = { msg -> openLocation(msg) },
            onContactClick = { msg -> addContact(msg) },
            onContactMessage = { msg -> messageContact(msg) },
            onMessageActions = { msg -> showMessageActions(msg) },
            onQuoteClick = { msg -> onQuoteTapped(msg) },
            onSelectionChanged = { onSelectionChanged() },
            onDragArm = { dragSelect?.arm() },
        )
        lm = LinearLayoutManager(this)
        lm.stackFromEnd = true
        messageList.layoutManager = lm
        messageList.adapter = adapter
        dragSelect = DragSelectTouchListener(adapter, onDragFinished = { onDragSelectFinished() })
            .also { messageList.addOnItemTouchListener(it) }
        messageList.setItemViewCacheSize(24)
        messageList.itemAnimator = null

        scrollFab.setOnClickListener {
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
                    quoteJumpReturns.clear()
                }
                updateScrollFab()
                showFloatingDate()
                maybeLoadOlder()
                if (windowMode && lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) {
                    extendWindow(older = false)
                }
            }
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) cancelHold()
            }
        })

        attachButton.setOnClickListener {
            if (recorder != null || pendingAudio != null) finishRecording(send = false) else showAttachMenu()
        }
        actionButton.setOnClickListener {
            when {
                recorder != null || pendingAudio != null -> finishRecording(send = true)
                input.text.isNotBlank() -> sendCurrentText()
                else -> startRecording()
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateActionButton()
        })
        updateActionButton()

        Bridge.requestInitialHistory(chatId)
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
        val hook = {
            runOnUiThread {
                // the earpiece fallback moves playback to the call stream, so
                // the volume keys have to follow it mid-clip
                volumeControlStream = AudioPlayer.volumeStream
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

    override fun onDestroy() {
        super.onDestroy()
        searchExec.shutdownNow()
        windowMedia.shutdownNow()
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
        // a history walk is something the user asked for on this screen; it must
        // not keep pulling pages once they have left it
        stopDeepening()
        // remember where we were so returning restores the position — but if we
        // were at the bottom, store null so the next load jumps to the real
        // bottom and shows whatever arrived meanwhile (e.g. a file just attached
        // via an external picker, or shared in from another app), instead of
        // restoring a now-stale anchor that sits above the newest message
        val atBottomNow = isAtBottom()
        scrollStates[chatId] = if (atBottomNow) null else lm.onSaveInstanceState()
        saveScrollAnchor(atBottomNow)
        // drop the UI hook so the singleton AudioPlayer doesn't retain this
        // activity; playback itself continues in the background. Identity-checked:
        // a newer instance (share / notification deep-link into a chat while one
        // is already open) starts before this one stops, and clearing the field
        // unconditionally used to kill the live screen's audio UI updates.
        if (AudioPlayer.onStateChanged === audioHook) AudioPlayer.onStateChanged = null
        audioHook = null
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
        pendingVideoOpen = null
        if (seekQuotedId != null) clearSeek()
        dragSelect?.stopDrag()
        cancelHold()
    }

    // Tolerant "scrolled to the bottom" test: a fling rarely stops on the exact
    // last pixel, and while the newest message is on screen the user expects new
    // ones to follow it — only genuinely scrolled-up positions keep their place.
    private fun isAtBottom(): Boolean =
        !messageList.canScrollVertically(1) ||
            lm.findLastVisibleItemPosition() >= adapter.itemCount - 1

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

    private val cachedQuoteNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun quoteNamesFor(messages: List<MessageRow>): Map<String, String> {
        val labels = HashMap<String, String>()
        for (m in messages) {
            val qid = m.quotedId
            if (qid.isEmpty() || labels.containsKey(qid)) continue
            val memo = cachedQuoteNames[qid]
            if (memo != null) {
                labels[qid] = memo
                continue
            }
            val label = Bridge.db.messageSender(m.chatId, qid)?.let { s ->
                if (s.fromMe) getString(R.string.you)
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
        windowMode = false
        io.execute {
            val messages = Bridge.db.messages(chatId, loadLimit)
            val names = contactNames()
            val quoteNames = quoteNamesFor(messages)
            runOnUiThread {
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
                        !restoredScroll -> {
                            restoredScroll = true
                            val saved = scrollStates[chatId]
                            when {
                                saved != null -> lm.onRestoreInstanceState(saved)
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
                    // after the restore above, so the jump is what the user is
                    // left looking at
                    consumePendingJump()
                    if (seekQuotedId != null) driveSeek()
                }
            }
            if (markRead) Bridge.markChatRead(chatId)
        }
    }

    private fun maybeLoadOlder() {
        if (windowMode) {
            if (lm.findFirstVisibleItemPosition() <= 5) extendWindow(older = true)
            return
        }
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

    private var floatingDatePos = -1

    private fun showFloatingDate() {
        val pos = lm.findFirstVisibleItemPosition()
        val msgs = adapter.messagesSnapshot()
        if (pos < 0 || pos >= msgs.size) return
        if (pos != floatingDatePos) {
            floatingDatePos = pos
            floatingDate.text = TimeFormat.dateSeparator(this, msgs[pos].timeSent)
        }
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
    /**
     * [center] parks the message in the middle of the screen instead of merely
     * bringing it into view. A tall photo lands half cut off otherwise, which is
     * the wrong place to leave someone who asked to go to that picture.
     */
    private fun scrollToMessage(
        msgId: String, toastIfMissing: Boolean = true, center: Boolean = false,
    ): Boolean {
        if (msgId.isEmpty()) return false
        val index = adapter.indexOfMessage(msgId)
        return if (index >= 0) {
            adapter.flashMsgId = msgId
            adapter.notifyItemChanged(index, Unit)
            holdScrollToMessage(msgId, index, center)
            true
        } else {
            if (toastIfMissing) {
                Toast.makeText(this, R.string.message_not_loaded, Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    private var holdListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null

    // Jumps to a message instantly and keeps it pinned at the top for a short
    // window afterwards: a freshly widened window decodes images / settles row
    // heights over several layout passes, which would otherwise slide the target
    // off-screen right after the jump. Re-pins each layout pass until it's stable
    // (or the window elapses). Yields immediately if the user starts scrolling
    // (see cancelHold in the scroll listener).
    private fun holdScrollToMessage(msgId: String, index: Int, center: Boolean = false) {
        if (center) lm.scrollToPositionWithOffset(index, 0) else messageList.scrollToPosition(index)
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
                if (targetIndex in first..last) {
                    if (!center) { cancelHold(); return }
                    // Centring needs the row's real height, which only exists
                    // once it is laid out — so it is applied here, and re-applied
                    // while the height keeps changing (an image decoding into a
                    // taller bubble moves the target under it).
                    val view = lm.findViewByPosition(targetIndex)
                    if (view == null) { lm.scrollToPositionWithOffset(targetIndex, 0); return }
                    val visible = messageList.height -
                        messageList.paddingTop - messageList.paddingBottom
                    val wanted = (visible - view.height) / 2
                    if (kotlin.math.abs(view.top - messageList.paddingTop - wanted) <= 4) {
                        cancelHold()
                        return
                    }
                    lm.scrollToPositionWithOffset(targetIndex, wanted)
                    return
                }
                if (center) lm.scrollToPositionWithOffset(targetIndex, 0)
                else messageList.scrollToPosition(targetIndex)
            }
        }
        holdListener = listener
        messageList.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun cancelHold() {
        holdListener?.let { messageList.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        holdListener = null
    }

    private fun onQuoteTapped(origin: MessageRow) {
        if (origin.quotedId.isEmpty()) return
        if (seekQuotedId != null) clearSeek() // supersede any in-progress seek
        if (scrollToMessage(origin.quotedId, toastIfMissing = false)) {
            if (quoteJumpReturns.lastOrNull() != origin.id) quoteJumpReturns.addLast(origin.id)
            return
        }
        seekQuotedId = origin.quotedId
        seekOrigin = origin
        seekFetching = false
        updateSubtitle() // shows the "syncing message…" line
        driveSeek()
    }

    private fun driveSeek() {
        val target = seekQuotedId ?: return
        if (adapter.indexOfMessage(target) >= 0) { completeSeek(); return }
        io.execute {
            val depth = Bridge.db.messageDepth(chatId, target)
            runOnUiThread {
                if (seekQuotedId != target) return@runOnUiThread // superseded/cancelled
                if (depth > 0) {
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
                    Bridge.seekMessage(chatId, target, o, MAX_SEEK_PAGES)
                    main.removeCallbacks(seekTimeout)
                    main.postDelayed(seekTimeout, SEEK_TIMEOUT_MS)
                }
            }
        }
    }

    override fun onSeekResult(chatId: String, msgId: String, found: Boolean) {
        if (chatId != this.chatId || msgId != seekQuotedId) return
        if (found) driveSeek() // now stored → depth>0 → widen + reload + jump
        else failSeek()
    }

    private fun consumePendingJump() {
        val target = pendingJumpId ?: return
        pendingJumpId = null
        if (scrollToMessage(target, toastIfMissing = false, center = true)) return
        if (seekQuotedId == target) return // already being sought
        if (seekQuotedId != null) clearSeek()
        seekQuotedId = target
        seekOrigin = null
        seekCenter = true
        seekFetching = false
        updateSubtitle()
        driveSeek()
    }

    private fun completeSeek() {
        val target = seekQuotedId ?: return
        val origin = seekOrigin?.id
        val center = seekCenter
        clearSeek()
        if (scrollToMessage(target, toastIfMissing = false, center = center) &&
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
        seekCenter = false
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

    override fun onMessagesChanged(chatId: String, rowIds: Set<String>?) {
        if (chatId != this.chatId) return
        // while searching, the adapter holds a full-history snapshot the matches
        // index into; don't reload it out from under the search (still mark read)
        if (searchActive) {
            Bridge.markChatRead(chatId)
            // unless this IS the older history the search asked for — then the
            // window is meant to widen, and the scan re-runs over it
            if (deepening && rowIds == null) onDeepPage()
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
        // The event named the rows it touched and nothing moved, so re-read only
        // those. Ticks, reactions and download completions arrive in bursts and
        // used to re-query, re-map and re-diff the entire loaded window — up to
        // 5000 rows with a correlated reactions subquery each — several times a
        // second. Scroll position, pagination and the seek machine only react to
        // structural changes, which still take the full path below.
        if (rowIds != null && restoredScroll && adapter.itemCount > 0) {
            refreshRows(rowIds)
            Bridge.markChatRead(chatId)
            return
        }
        reload(markRead = true)
    }

    private fun refreshRows(ids: Set<String>) {
        io.execute {
            val fresh = Bridge.db.messagesByIds(chatId, ids).associateBy { it.id }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                pendingVideoOpen?.let { id ->
                    val m = fresh[id]
                    if (m != null && m.filePath.isNotEmpty()) {
                        pendingVideoOpen = null
                        openMediaFile(m)
                    } else if (m != null && m.fileStatus == 3) {
                        pendingVideoOpen = null // failed — Bridge already toasted
                    }
                }
                adapter.refreshRows(fresh)
            }
        }
    }

    private var reloadAfterDrag = false

    fun onDragSelectFinished() {
        if (!reloadAfterDrag) return
        reloadAfterDrag = false
        reload(markRead = true)
    }

    private fun openSearch() {
        searchActive = true
        pendingVideoOpen = null
        searchBar.visibility = android.view.View.VISIBLE
        searchInput.requestFocus()
        showKeyboard(searchInput)
        if (Tg.isTgId(chatId)) return
        updateCoverage()
        loadSearchWindow()
    }

    private fun loadSearchWindow() {
        io.execute {
            val all = Bridge.db.messages(chatId, searchLimit)
            val names = contactNames()
            val quoteNames = quoteNamesFor(all)
            runOnUiThread {
                if (!searchActive || isFinishing || isDestroyed) return@runOnUiThread
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
        stopDeepening()
        searchBar.visibility = android.view.View.GONE
        searchCoverageRow.visibility = android.view.View.GONE
        searchInput.setText("")
        adapter.highlightQuery = ""
        rebindVisible()
        searchMatches = emptyList()
        currentMatch = -1
        serverHits = emptyList()
        serverTotal = 0
        serverNextFrom = 0L
        currentHit = -1
        hitsLoading = false
        windowFailed.clear()
        pendingWindowOpen = null
        searchLimit = SEARCH_LOAD_LIMIT
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
            // supersede a query still in flight, or its answer would drop the
            // list into a hit window for text the search box no longer holds
            searchSeq++
            // the screen is showing the window around a hit, not the chat's own
            // history: put the history back now there is nothing to look for.
            // Keyed on the window itself — a query that ended with no hits still
            // leaves the previous one on screen.
            val showingHit = windowMode
            searchMatches = emptyList()
            currentMatch = -1
            serverHits = emptyList()
            serverTotal = 0
            serverNextFrom = 0L
            currentHit = -1
            searchCount.text = ""
            updateCoverage()
            if (showingHit) reload()
            return
        }
        // Telegram can be asked directly; WhatsApp is end-to-end encrypted, so
        // its servers hold nothing to ask about and the scan stays local
        if (Tg.isTgId(chatId)) runServerSearch(q) else runLocalSearch(q)
    }

    private fun runLocalSearch(q: String) {
        val msgs = adapter.messagesSnapshot()
        io.execute {
            val matches = msgs.indices.filter { i ->
                val m = msgs[i]
                m.msgType != "audio" && m.text.contains(q, ignoreCase = true)
            }
            runOnUiThread {
                if (adapter.highlightQuery != q) return@runOnUiThread
                val keepId = currentMatchId()
                searchMatches = matches
                val kept = matches.indexOfFirst { adapter.messageIdAt(it) == keepId }
                currentMatch = if (deepening && kept >= 0) kept else matches.size - 1
                if (!deepening && currentMatch >= 0) {
                    messageList.scrollToPosition(matches[currentMatch])
                }
                updateSearchCount()
                updateCoverage()
            }
        }
    }

    private fun currentMatchId(): String =
        searchMatches.getOrNull(currentMatch)?.let { adapter.messageIdAt(it) } ?: ""

    private fun runServerSearch(q: String) {
        val seq = ++searchSeq
        searchCount.setText(R.string.searching)
        searchExec.execute {
            val page = Bridge.searchServer(chatId, q, 0)
            runOnUiThread {
                // a newer keystroke superseded this query — or the screen is
                // gone, which the toolbar's up arrow does without closing the
                // search, leaving this to hand work to a stopped executor
                if (seq != searchSeq || !searchActive || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                if (page == null) {
                    searchCount.setText(R.string.search_failed)
                    return@runOnUiThread
                }
                serverHits = page.ids
                serverTotal = page.total
                serverNextFrom = page.nextFrom
                currentHit = if (serverHits.isEmpty()) -1 else 0 // newest match first
                updateSearchCount()
                if (currentHit >= 0) showHit(currentHit)
                // nothing matched: the previous hit's window must not stay up as
                // if it were still a result
                else if (windowMode) reload()
            }
        }
    }

    /**
     * Shows one hit surrounded by the messages that came before and after it.
     * The window is rendered straight from the server answer and never stored:
     * writing a far-back slice into the history would leave it stranded between
     * two gaps, which is how a chat starts skipping months at a time.
     */
    private fun showHit(index: Int) {
        val id = serverHits.getOrNull(index) ?: return
        val seq = ++windowSeq
        searchExec.execute {
            val window = Bridge.searchContext(chatId, id)
            val names = contactNames()
            val quoteNames = quoteNamesFor(window)
            runOnUiThread {
                if (seq != windowSeq || !searchActive || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                if (window.isEmpty()) {
                    Toast.makeText(this, R.string.message_not_loaded, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                windowMode = true
                windowTopDone = false
                windowBottomDone = false
                adapter.submit(window, names, quoteNames) {
                    scrollToMessage(id, toastIfMissing = false, center = true)
                }
            }
        }
    }

    private fun extendWindow(older: Boolean) {
        if (!windowMode || windowLoading) return
        if (if (older) windowTopDone else windowBottomDone) return
        val rows = adapter.messagesSnapshot()
        if (rows.isEmpty()) return
        val anchor = if (older) rows.first().id else rows.last().id
        windowLoading = true
        // the hit this slice belongs to: stepping to another one while it is in
        // flight would otherwise resurrect the old window over the new one
        val seq = windowSeq
        searchExec.execute {
            val more = Bridge.searchSlice(chatId, anchor, newer = !older)
            val merged = if (more.isEmpty()) emptyList() else {
                (if (older) more + rows else rows + more)
                    .distinctBy { it.id }
                    .sortedWith(compareBy({ it.timeSent }, { it.id.toLongOrNull() ?: 0L }))
            }
            val names = if (merged.isEmpty()) emptyMap() else contactNames()
            val quoteNames = if (merged.isEmpty()) emptyMap() else quoteNamesFor(merged)
            runOnUiThread {
                windowLoading = false
                if (seq != windowSeq || !windowMode || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                if (merged.isEmpty()) {
                    if (older) windowTopDone = true else windowBottomDone = true
                    return@runOnUiThread
                }
                adapter.submit(merged, names, quoteNames)
            }
        }
    }

    /**
     * Fetches the picture, video or voice note of a row in the search window.
     * Those rows are not stored, so the normal download path — which reports
     * progress by writing to the message's row — has nothing to write to and
     * left every attachment blank.
     */
    private fun fetchWindowMedia(msg: MessageRow, userInitiated: Boolean = false) {
        if (msg.fileId.isEmpty() || msg.filePath.isNotEmpty()) return
        // one automatic try per file: a failed row still binds with an empty
        // path, so every scroll past it would start another blocking fetch. A
        // tap always retries.
        if (userInitiated) windowFailed.remove(msg.id)
        else if (msg.id in windowFailed) return
        if (!windowFetching.add(msg.id)) return
        adapter.refreshRows(mapOf(msg.id to msg.copy(fileStatus = 1)))
        windowMedia.execute {
            val path = Bridge.searchMedia(msg.chatId, msg.id)
            runOnUiThread {
                windowFetching.remove(msg.id)
                if (!windowMode || isFinishing || isDestroyed) return@runOnUiThread
                if (path.isEmpty()) windowFailed.add(msg.id)
                val fresh = if (path.isEmpty()) msg.copy(fileStatus = 3)
                    else msg.copy(filePath = path, fileStatus = 2)
                adapter.refreshRows(mapOf(msg.id to fresh))
                if (pendingWindowOpen == msg.id) {
                    pendingWindowOpen = null
                    if (path.isNotEmpty()) openMediaFile(fresh)
                }
            }
        }
    }

    private fun stepHit(delta: Int) {
        if (serverHits.isEmpty()) return
        val next = currentHit + delta
        if (next < 0) return // already at the newest match
        if (next >= serverHits.size) {
            if (serverNextFrom == 0L) return // nothing older left
            loadMoreHits()
            return
        }
        currentHit = next
        updateSearchCount()
        showHit(currentHit)
    }

    private fun loadMoreHits() {
        // without this, tapping twice at the end of the list fetches the same
        // page twice: the anchor only moves when the first answer lands
        if (hitsLoading) return
        hitsLoading = true
        val seq = searchSeq
        val from = serverNextFrom
        val q = adapter.highlightQuery
        searchExec.execute {
            val page = Bridge.searchServer(chatId, q, from)
            runOnUiThread {
                hitsLoading = false
                if (seq != searchSeq || !searchActive || page == null ||
                    isFinishing || isDestroyed
                ) {
                    return@runOnUiThread
                }
                val fresh = page.ids.filterNot { it in serverHits }
                if (fresh.isEmpty()) {
                    serverNextFrom = 0L
                    return@runOnUiThread
                }
                serverHits = serverHits + fresh
                serverNextFrom = page.nextFrom
                currentHit++
                updateSearchCount()
                showHit(currentHit)
            }
        }
    }

    /**
     * Says what was actually searched. A WhatsApp chat is searched locally, so
     * "no matches" only ever means "none in what is on this phone" — naming the
     * oldest message it reached keeps that honest, and the button fetches more.
     */
    private fun updateCoverage() {
        if (!searchActive || Tg.isTgId(chatId)) {
            searchCoverageRow.visibility = android.view.View.GONE
            return
        }
        if (searchInput.text.isNullOrBlank() && !deepening) {
            searchCoverageRow.visibility = android.view.View.GONE
            return
        }
        searchCoverageRow.visibility = android.view.View.VISIBLE
        if (Bridge.isHistoryExhausted(chatId)) Prefs.setHistoryComplete(this, chatId)
        searchDeeper.setText(if (deepening) R.string.stop else R.string.search_older)
        // The date has to describe what was SCANNED, not what is stored: the
        // window holds the newest searchLimit rows, so a long chat can hold
        // messages older than the scan ever looked at. Claiming those would be
        // the very lie this line exists to avoid.
        val scanned = adapter.messagesSnapshot().firstOrNull()?.timeSent ?: 0L
        io.execute {
            val stored = Bridge.db.oldestMessage(chatId)?.timeSent ?: 0L
            runOnUiThread {
                if (!searchActive || isFinishing || isDestroyed) return@runOnUiThread
                val all = Prefs.historyComplete(this, chatId) && stored > 0 && scanned <= stored
                searchDeeper.visibility =
                    if (all) android.view.View.GONE else android.view.View.VISIBLE
                val since = if (scanned > 0) TimeFormat.dateSeparator(this, scanned) else ""
                searchCoverage.text = when {
                    all -> getString(R.string.searched_all)
                    since.isEmpty() -> ""
                    deepening -> getString(R.string.searching_older, since)
                    searchMatches.isEmpty() -> getString(R.string.no_matches_since, since)
                    else -> getString(R.string.searched_back_to, since)
                }
            }
        }
    }

    /**
     * Pulls older history one page at a time, re-scanning after each, until the
     * chat's start is reached or the user stops. Explicitly asked for: syncing
     * before every search would be slow, and doing it silently would spend the
     * phone's battery on searches that were never going to match.
     */
    private fun toggleDeepSearch() {
        if (deepening) {
            stopDeepening()
            updateCoverage()
            return
        }
        deepening = true
        idleRounds = 0
        deepOldest = adapter.messagesSnapshot().firstOrNull()?.timeSent ?: 0L
        updateCoverage()
        pullOlderPage()
    }

    private fun pullOlderPage() {
        if (!deepening) return
        if (Bridge.isHistoryExhausted(chatId)) {
            Prefs.setHistoryComplete(this, chatId)
            stopDeepening()
            updateCoverage()
            return
        }
        Bridge.requestChatHistory(chatId)
        main.postDelayed(deepTick, DEEP_TICK_MS)
    }

    // Nothing arrived since the last request. A page can simply be dropped (one
    // whole-history operation runs at a time), so retry a couple of rounds
    // before concluding the phone is not answering.
    private val deepTick = Runnable {
        if (!deepening) return@Runnable
        idleRounds++
        if (idleRounds >= DEEP_IDLE_ROUNDS) {
            stopDeepening()
            searchCoverage.text = getString(R.string.phone_unreachable)
            searchDeeper.setText(R.string.search_older)
            return@Runnable
        }
        pullOlderPage()
    }

    private fun stopDeepening() {
        deepening = false
        idleRounds = 0
        main.removeCallbacks(deepTick)
    }

    /**
     * Something changed in the chat while deepening. Only an actually older
     * message counts as the page that was asked for — an arriving message or a
     * deletion fires the same event, and treating those as progress reset the
     * "your phone didn't answer" counter forever.
     */
    private fun onDeepPage() {
        io.execute {
            val oldest = Bridge.db.oldestMessage(chatId)?.timeSent ?: 0L
            runOnUiThread {
                if (!deepening || isFinishing || isDestroyed) return@runOnUiThread
                if (deepOldest != 0L && oldest >= deepOldest) return@runOnUiThread
                deepOldest = oldest
                main.removeCallbacks(deepTick)
                idleRounds = 0
                // the fetched page sits below the window's floor; without this
                // every round would re-scan the same rows and find nothing new
                searchLimit += LOCAL_PAGE
                loadSearchWindow() // re-scans through runSearch once the diff commits
                updateCoverage()
                pullOlderPage()
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

    private fun restoreScrollAnchor(): Boolean {
        val (msgId, offset) = Prefs.scrollAnchor(this, chatId) ?: return false
        val pos = adapter.indexOfMessage(msgId)
        if (pos < 0) return false
        lm.scrollToPositionWithOffset(pos, offset)
        return true
    }

    private fun stepMatch(delta: Int) {
        // The loaded window runs oldest-first, the server's hits newest-first,
        // so "up" (delta -1, older) walks the hit list forwards. Server hits
        // also do not wrap: there is always more history behind the last one.
        if (Tg.isTgId(chatId)) {
            stepHit(-delta)
            return
        }
        if (searchMatches.isEmpty()) return
        val n = searchMatches.size
        currentMatch = ((currentMatch + delta) % n + n) % n
        messageList.scrollToPosition(searchMatches[currentMatch])
        updateSearchCount()
    }

    private fun updateSearchCount() {
        if (Tg.isTgId(chatId)) {
            searchCount.text = if (serverHits.isEmpty()) getString(R.string.no_results)
                else "${currentHit + 1}/${maxOf(serverTotal, serverHits.size)}"
            return
        }
        searchCount.text = if (searchMatches.isEmpty()) getString(R.string.no_results)
            else "${currentMatch + 1}/${searchMatches.size}"
    }

    private fun showChatMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.sync_all)
        popup.menu.add(0, 2, 1, R.string.export_chat)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
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

    private fun startChatExport() {
        val proto = getString(if (Tg.isTgId(chatId)) R.string.telegram else R.string.whatsapp)
        createExportFile.launch(
            getString(R.string.export_file_name, proto, safeDisplayFileName(chatDisplayName))
        )
    }

    private fun writeChatExport(uri: Uri) {
        // the export runs in the Bridge singleton and may outlive this activity
        // (rotation / backing out mid-export), so promote the one-shot
        // activity-result grant to a persistable one the bridge can still write
        // through after this activity is gone; the bridge releases it when done
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
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

    private fun updateSubtitle() {
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

    private fun updateActionButton() {
        if (recorder != null || pendingAudio != null) return // recording/pending UI owns the buttons meanwhile
        val sends = input.text.isNotBlank()
        actionButton.setImageResource(if (sends) R.drawable.ic_send else R.drawable.ic_mic)
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

    private fun onFilePicked(uri: Uri) {
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
                Bridge.sendFile(chatId, local.absolutePath, name, mime, quoted = quoted)
            }
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
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
        pendingRecordings[chatId] = PendingRecording(file, duration, recordAmps.toList())
        recordTimer.text = getString(R.string.recording_paused, TimeFormat.mmss(duration))
    }

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

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app_for_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLocation(msg: MessageRow) {
        val coords = msg.coordinates()
        if (coords.isEmpty()) return
        startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$coords")))
    }

    // A card's body is "name\nphone..." per person, people separated by a
    // blank line; the buttons act on the first person. The digit filter only
    // exists for rows stored by builds that interleaved several people's
    // lines with no separator — new rows are structured by construction.
    private fun cardLines(msg: MessageRow): List<String> =
        msg.text.substringBefore("\n\n").lines().filter { it.isNotBlank() }

    private fun cardPhones(msg: MessageRow): List<String> =
        cardLines(msg).drop(1).filter { PhoneBook.digitsOf(it).length >= 5 }

    private fun addContact(msg: MessageRow) {
        val name = cardLines(msg).firstOrNull() ?: return
        val intent = Intent(ContactsContract.Intents.Insert.ACTION)
        intent.type = ContactsContract.RawContacts.CONTENT_TYPE
        intent.putExtra(ContactsContract.Intents.Insert.NAME, name)
        val slots = listOf(
            ContactsContract.Intents.Insert.PHONE,
            ContactsContract.Intents.Insert.SECONDARY_PHONE,
            ContactsContract.Intents.Insert.TERTIARY_PHONE,
        )
        cardPhones(msg).take(slots.size).forEachIndexed { i, p -> intent.putExtra(slots[i], p) }
        startActivitySafely(intent)
    }

    // A number without a country code is never guessed at — a wrong guess
    // would open a chat with a stranger under the card's name.
    private fun messageContact(msg: MessageRow) {
        val name = cardLines(msg).firstOrNull().orEmpty()
        if (Tg.isTgId(chatId)) {
            val userId = msg.fileId.toLongOrNull()
            if (userId == null) {
                Toast.makeText(this, R.string.not_on_telegram, Toast.LENGTH_SHORT).show()
                return
            }
            resolveThenOpen(R.string.opening_chat, {
                Tg.createUserChat(userId).ifEmpty { R.string.chat_open_failed }
            }) { id -> openContactChat(id, name) }
            return
        }
        val waid = msg.fileId.ifEmpty {
            cardPhones(msg).firstOrNull()
                ?.let { PhoneBook.normalize(it).removePrefix("+") }.orEmpty()
        }
        if (waid.isEmpty()) {
            Toast.makeText(this, R.string.number_check_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Bridge.rememberContact("$waid@s.whatsapp.net", name)
        openContactChat("$waid@s.whatsapp.net", name)
    }

    private fun openContactChat(id: String, name: String) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("chatId", id)
        intent.putExtra("chatName", name)
        startActivity(intent) // singleTop routes to onNewIntent while on top
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: opening another chat while this one is on top lands here
        // (a card's "Message", a notification deep-link). Rebuild on the new
        // intent in place — the task and Back stack stay intact.
        if (intent.getStringExtra("chatId") == chatId) return
        setIntent(intent)
        recreate()
    }

    private fun openDocument(msg: MessageRow) {
        if (!mediaOnDisk(msg)) {
            downloadWithToast(msg)
            return
        }
        openMediaFile(msg)
    }

    // Whether the row's file is really here. A stored path can outlive its file
    // (our own Telegram sends reference the cacheDir staging copy, swept after a
    // day), and handing that path to an external viewer opens nothing.
    private fun mediaOnDisk(msg: MessageRow): Boolean =
        msg.filePath.isNotEmpty() && File(msg.filePath).exists()

    private fun openVideo(msg: MessageRow) {
        if (mediaOnDisk(msg)) {
            openMediaFile(msg)
            return
        }
        pendingVideoOpen = msg.id
        downloadWithToast(msg)
    }

    private fun openMediaFile(msg: MessageRow) {
        val (uri, mime) = providedFile(File(msg.filePath), "*/*")
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivitySafely(intent)
    }

    private fun downloadWithToast(msg: MessageRow) {
        // a search window's rows have no DB row for the transfer to report on
        if (windowMode) {
            pendingWindowOpen = msg.id
            fetchWindowMedia(msg, userInitiated = true)
        } else {
            Bridge.downloadFile(msg, userInitiated = true)
        }
        Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show()
    }

    /**
     * A list dialog whose entries carry their own action, so dispatch is by
     * identity rather than by comparing the tapped label against localized
     * strings — which silently ran the wrong action for any translation that
     * rendered two labels the same (the CAB dispatches by item id for the same
     * reason). [titleRes]/[cancellable] cover the delete confirmation, which
     * needs a title and a Cancel button; the message-action sheet has neither.
     */
    private fun showActionDialog(
        actions: List<Pair<String, () -> Unit>>,
        titleRes: Int? = null,
        cancellable: Boolean = false,
        onChosen: () -> Unit = {},
    ) {
        AlertDialog.Builder(this)
            .apply { if (titleRes != null) setTitle(titleRes) }
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
                onChosen()
            }
            .apply { if (cancellable) setNegativeButton(android.R.string.cancel, null) }
            .show()
    }

    private fun showMessageActions(msg: MessageRow) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        fun add(res: Int, action: () -> Unit) = actions.add(getString(res) to action)

        // A label-only row (view-once media, a poll) has no body on this
        // device — nothing to forward or share, so offering either would only
        // fail. A contact card with a body is the exception: its text forwards
        // and shares like any text message.
        val labelOnly = msg.msgType in LABEL_ONLY_TYPES && msg.text.isBlank()

        add(R.string.reply) { startReply(msg) }
        if (msg.msgType == "contact" && msg.text.isNotBlank()) {
            add(R.string.add_to_contacts) { addContact(msg) }
        }
        if (!labelOnly) add(R.string.forward) { pickForwardTarget(msg) }
        if (msg.fromMe && msg.msgType == "" && Bridge.canEdit(msg)) add(R.string.edit) { startEdit(msg) }
        add(R.string.delete) { confirmDelete(listOf(msg)) }
        if (msg.text.isNotEmpty() && msg.msgType != "audio") add(R.string.copy) { copyText(msg.text) }
        if (msg.msgType != "" && !labelOnly) add(R.string.share) { shareMessage(msg) }
        add(R.string.react) { showReactionPicker(msg) }

        showActionDialog(actions)
    }

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

    private fun confirmDelete(msgs: List<MessageRow>, onDone: () -> Unit = {}) {
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (msgs.all { Bridge.canDeleteForEveryone(it) }) {
            options.add(getString(R.string.delete_for_everyone) to {
                for (m in msgs) Bridge.deleteForEveryone(chatId, m.id)
            })
        }
        options.add(getString(R.string.delete_for_me) to {
            for (m in msgs) Bridge.deleteForMe(chatId, m.id)
        })
        showActionDialog(options, R.string.delete_message, cancellable = true, onChosen = onDone)
    }

    private fun shareMessage(msg: MessageRow) {
        val intent = Intent(Intent.ACTION_SEND)
        if (msg.msgType == "location") {
            val coords = msg.coordinates()
            val link = "https://maps.google.com/?q=$coords"
            intent.type = "text/plain"
            intent.putExtra(
                Intent.EXTRA_TEXT,
                listOf(msg.text, coords, link).filter { it.isNotEmpty() }.joinToString("\n")
            )
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
            return
        }
        // a contact card's body is text, not a file on disk
        if (msg.msgType != "" && msg.msgType != "contact") {
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
            if (msg.msgType in PICTURE_TYPES && msg.text.isNotEmpty()) {
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

    private fun pickForwardTarget(msg: MessageRow) {
        showForwardPicker { targets -> forwardMessagesToTargets(listOf(msg), targets) }
    }

    private fun showForwardPicker(onPick: (List<String>) -> Unit) {
        io.execute {
            val (labels, ids) = targetChoices()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ids.isEmpty()) {
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

    private fun forwardMessagesToTargets(msgs: List<MessageRow>, targets: List<String>) {
        if (targets.isEmpty() || msgs.isEmpty()) return
        val ready = ArrayList<MessageRow>()
        var stillSending = false
        var downloading = false
        for (msg in msgs) {
            if (msg.msgType in LABEL_ONLY_TYPES && msg.text.isBlank()) continue
            val isFileMedia = msg.msgType in FILE_MEDIA_TYPES
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
