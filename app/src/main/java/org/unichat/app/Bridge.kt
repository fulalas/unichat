package org.unichat.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.unichat.wmbridge.EventListener
import org.unichat.wmbridge.Wmbridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Singleton owning the whatsmeow connection and the local database. Bridge
 * callbacks arrive on Go threads; they are persisted to the DB directly and
 * UI notifications are debounced onto the main thread.
 */
object Bridge : EventListener {

    interface UiListener {
        fun onChatsChanged() {}
        // a contact row was actually written (name/phone/saved flag). Distinct
        // from onChatsChanged, which also fires for every message event — a
        // contact-name cache keyed off that was invalidated constantly and never
        // survived to be used.
        fun onContactsChanged() {}
        /**
         * A chat's messages changed. [rowIds] names the rows whose content
         * changed when that is all that happened (ticks, reactions, downloads);
         * null means the window may have gained, lost or reordered rows and has
         * to be re-read in full.
         */
        fun onMessagesChanged(chatId: String, rowIds: Set<String>? = null) {}
        // a chat's rows were folded into another id (LID→phone heal); a screen
        // showing fromId must retarget to toId or it goes blank (its rows moved)
        fun onChatMerged(fromId: String, toId: String) {}
        fun onStateChanged(state: String) {}
        fun onQrCode(code: String) {}
        fun onPairCode(code: String) {}
        fun onPairError(message: String) {}
        fun onSyncProgress(progress: Int) {}
        // 0..99 while a media download streams (100/done arrives as a normal
        // message change once the file path is set)
        fun onDownloadProgress(chatId: String, msgId: String, pct: Int) {}
        fun onChatState(chatId: String, state: String) {}
        fun onPresence(userId: String, isOnline: Boolean, lastSeen: Long) {}
        // full-chat sync ("Sync all"): 0..100 while running, -1 = aborted
        fun onChatSyncProgress(chatId: String, progress: Int) {}
        // full-history export: fetched grows per page while running
        fun onChatExportProgress(chatId: String, fetched: Int) {}
        // complete = the chat's start was reached; success = the file was written
        fun onChatExportDone(chatId: String, messages: Int, complete: Boolean, success: Boolean) {}
        // jump-to-quote history seek finished: found = the target message is now
        // stored locally; false = its start was reached / cap hit without it
        fun onSeekResult(chatId: String, msgId: String, found: Boolean) {}
        // Telegram auth flow: wait_phone / wait_code / wait_password / ready
        fun onTgAuth(state: String, message: String) {}
        // Telegram connection state changed (read it from Tg.state)
        fun onTgStateChanged() {}
    }

    private const val TAG = "UniChat"

    @Volatile private var connId: Long = -1
    @Volatile private var appContext: Context? = null
    // chat currently open in the foreground; messages for it are not notified
    @Volatile var activeChatId: String = ""
    lateinit var db: Db
        private set
    @Volatile var state: String = "disconnected"
        private set
    @Volatile var syncProgress: Int = -1
        private set

    private val executor = Executors.newSingleThreadExecutor()
    private val mediaExecutor = Executors.newFixedThreadPool(2)
    private val notifyExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<UiListener>()
    // hash sets, not CopyOnWriteArraySet: these are add/remove-heavy per bind
    // and per download, where copy-on-write is O(n) per mutation
    private val downloading: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // History pagination / sync state. historyExhausted holds chats whose
    // start was reached this run (cleared on logout); the single in-flight
    // request slot and per-chat anchors live near requestChatHistory below.
    private val historyExhausted = CopyOnWriteArraySet<String>()
    private const val HISTORY_PAGE = 100L
    // the phone can be slow to answer (push wake-up, encryption, upload)
    private const val HISTORY_TIMEOUT_MS = 30_000L

    /** Per-page history timeout, so a UI-side backstop can be sized against it. */
    val historyTimeoutMs: Long get() = HISTORY_TIMEOUT_MS
    // below this many locally-stored messages, opening a chat tops it up once
    private const val INITIAL_HISTORY_MIN = 60

    // Aggregated live activity for a chat: the dominant "typing"/"recording"
    // state, the display names of everyone currently active (groups only), and
    // how many there are — so the chat screen reads it back without a per-tick
    // DB query. Recomputed from the per-actor state below whenever an actor
    // starts or stops.
    private data class ChatStateInfo(val state: String, val actorName: String, val actorCount: Int)
    private val chatStates = ConcurrentHashMap<String, ChatStateInfo>()
    // Per-actor state within each chat, mutated on the main thread only. The
    // name is resolved once off the main thread when the state arrives (groups
    // only). LinkedHashMap keeps the order actors started in, so the joined
    // names stay stable as others come and go. Each actor auto-clears after a
    // timeout (see stateClearers).
    private data class ActorState(val state: String, val name: String)
    private val chatActors = HashMap<String, LinkedHashMap<String, ActorState>>()
    // userId -> online flag / last seen unix seconds
    private val online = ConcurrentHashMap<String, Boolean>()
    private val lastSeen = ConcurrentHashMap<String, Long>()

    fun chatState(chatId: String): String? = chatStates[chatId]?.state
    fun chatStateName(chatId: String): String? = chatStates[chatId]?.actorName
    fun chatStateActorCount(chatId: String): Int = chatStates[chatId]?.actorCount ?: 0
    fun isOnline(userId: String): Boolean = online[userId] == true
    fun lastSeenOf(userId: String): Long = lastSeen[userId] ?: 0L

    /**
     * String resource describing roughly when a user was last seen, for the
     * case where no exact time exists: Telegram answers "recently" / "within a
     * week" / "within a month" instead of a timestamp when either side hides
     * their last-seen. 0 when nothing is known. WhatsApp never sets this — it
     * either gives a real time or nothing.
     */
    fun lastSeenApproxOf(userId: String): Int = lastSeenApprox[userId] ?: 0

    private val lastSeenApprox = ConcurrentHashMap<String, Int>()

    internal fun postPresenceApprox(userId: String, labelRes: Int) {
        if (labelRes == 0) lastSeenApprox.remove(userId) else lastSeenApprox[userId] = labelRes
        notifyUi { it.onPresence(userId, isOnline(userId), lastSeenOf(userId)) }
    }

    /**
     * Chats touched since the last notification, each mapped to the message ids
     * whose CONTENT changed — or to null once anything structural landed (a new
     * message, a delete, a fetched page), meaning the whole loaded window has to
     * be re-read. A read receipt, a reaction or a finished download names its
     * one row, so the open chat can refresh that row instead of re-querying and
     * re-diffing up to 5000 of them several times a second.
     */
    private val changedChats = HashMap<String, MutableSet<String>?>()
    private var chatsChanged = false
    private var contactsChanged = false
    private var notifyPending = false

    /**
     * Kicks off [init] on a background thread from Application.onCreate so its
     * disk work (opening the Go sqlstore, running its migrations, a device-store
     * query) overlaps process startup instead of running strictly before the
     * first frame. init() is @Synchronized, so the first Activity that calls it
     * simply blocks on the same monitor until this finishes.
     */
    fun warmUp(context: Context) {
        if (connId >= 0 || warmingUp) return
        warmingUp = true
        val ctx = context.applicationContext
        Thread({ init(ctx) }, "bridge-init").start()
    }

    @Volatile private var warmingUp = false

    @Synchronized
    fun init(context: Context): Boolean {
        if (connId >= 0) return true
        val appContext = context.applicationContext
        this.appContext = appContext
        AudioPlayer.init(appContext)
        Notifications.ensureChannel(appContext)
        db = Db(appContext)
        db.clearStaleDownloads()
        Tg.init(appContext)
        val dataDir = appContext.filesDir.absolutePath + "/wm"
        connId = Wmbridge.init(dataDir, this)
        if (connId >= 0) {
            AudioPlayer.onCompleted = { _, chatId, msgId -> chainNextVoice(chatId, msgId) }
            AudioPlayer.onPlayStarted = { _, chatId, msgId -> markVoicePlayed(chatId, msgId) }
            executor.execute { cleanStaleCache(appContext) }
        }
        return connId >= 0
    }

    // Voice message queued for auto-play once its download finishes.
    @Volatile private var autoPlayKey: String? = null

    /** Plays the next voice message of the chat, skipping other message types. */
    private fun chainNextVoice(chatId: String, finishedMsgId: String) {
        if (chatId.isEmpty()) { main.post { AudioPlayer.resetRoute() }; return }
        if (connId < 0) return
        executor.execute {
            // query the next voice message directly (not limited to the last 500
            // loaded rows), and use the DB as the source of truth for its file
            val next = db.nextAudioMessage(chatId, finishedMsgId)
            if (next == null) {
                main.post { AudioPlayer.resetRoute() }
                return@execute
            }
            val (path, status) = db.fileState(next.chatId, next.id)
            if (status >= 2 && path.isNotEmpty()) {
                main.post { AudioPlayer.play(path, chatId, next.id) }
            } else {
                autoPlayKey = next.chatId + "/" + next.id
                // downloadFile can decline (no fileId, or a failure already
                // auto-retried this run) and then never reports back; leaving
                // autoPlayKey armed made a LATER, unrelated download of that
                // same message (Share/Forward/open) start playback unprompted
                if (!downloadFile(next)) autoPlayKey = null
            }
        }
    }

    // Marks a received voice note as played (once) when its playback starts,
    // and sends a "played" receipt to the sender.
    private fun markVoicePlayed(chatId: String, msgId: String) {
        if (chatId.isEmpty() || msgId.isEmpty()) return
        executor.execute {
            val msg = db.audioMessage(chatId, msgId) ?: return@execute
            if (msg.fromMe || msg.played || msg.msgType != "audio") return@execute
            db.setPlayed(chatId, msg.id)
            // ParseJID does NOT reject a "tg:" id (it yields an empty-user JID),
            // so an unguarded call here sent a receipt for a chat that does not
            // exist on WhatsApp; Telegram has its own "listened" call.
            proto(chatId).markVoicePlayed(msg)
            notifyChat(chatId)
        }
    }

    /** Notification "next" button: play the voice message after the current one. */
    fun skipToNextVoice() {
        val msgId = AudioPlayer.currentMsgId
        if (msgId.isEmpty()) return
        chainNextVoice(AudioPlayer.currentChatId, msgId)
    }

    fun hasSession(): Boolean = connId >= 0 && Wmbridge.hasSession(connId)

    /** Whether ANY protocol account (WhatsApp or Telegram) is linked. */
    fun hasAnySession(): Boolean = hasSession() || Tg.hasSession()

    /** True when [chatId] belongs to the Telegram side. */
    private fun isTg(chatId: String) = Tg.isTgId(chatId)

    // --- the two transports -------------------------------------------------

    /**
     * What a chat's service can do. The public API above/below is
     * protocol-agnostic: it resolves the transport from the chat id once and
     * states the surrounding policy — which executor the call runs on, what a
     * failure toasts, what gets written to the shared Db — exactly once.
     *
     * This used to be ~35 hand-written `if (isTg(chatId))` branches spread
     * through the file. Nothing then forced the two arms of an operation to
     * agree, and several had quietly diverged; adding a capability meant
     * remembering to add a branch as well as two implementations. Now the
     * compiler requires both.
     *
     * Implementations are nested so they can reach Bridge's connection and
     * history state without any of it becoming visible to the rest of the app.
     *
     * Threading: `send*`, `edit`, `delete*`, `react`, `setMuted` and
     * `avatarPath` BLOCK and are called on whichever executor the public
     * wrapper chose. The `history*`, `seek`, `syncAll` and `export` calls START
     * work and report through UiListener; they must not block the caller.
     */
    private interface Protocol {
        fun sendText(chatId: String, text: String, quoted: MessageRow?): Boolean
        fun sendImage(chatId: String, path: String, caption: String, quoted: MessageRow?): Boolean
        fun sendVideo(chatId: String, path: String, caption: String, quoted: MessageRow?): Boolean
        fun sendAudio(
            chatId: String, path: String, seconds: Int, quoted: MessageRow?, waveform: ByteArray,
        ): Boolean
        fun sendDocument(
            chatId: String, path: String, name: String, mime: String, quoted: MessageRow?,
        ): Boolean
        fun sendLocation(chatId: String, latitude: Double, longitude: Double): Boolean

        fun edit(chatId: String, msgId: String, newText: String, origTimeSent: Long): Boolean
        /** How long after sending a message stays editable, in seconds. */
        val editWindowSeconds: Long
        /** How long after sending a message can still be revoked for everyone. */
        val revokeWindowSeconds: Long

        fun deleteForEveryone(chatId: String, msgId: String)
        /** Anything the service itself must be told; the local row is deleted by
         *  the caller either way. */
        fun deleteForMe(chatId: String, msgId: String)
        fun react(msg: MessageRow, emoji: String)

        fun setMuted(chatId: String, muted: Boolean)
        fun markChatRead(chatId: String)
        fun markVoicePlayed(msg: MessageRow)
        /** The chat came to / left the foreground. */
        fun openChat(chatId: String) {}
        fun closeChat(chatId: String) {}
        /** WhatsApp only learns a contact's presence when asked; Telegram pushes it. */
        fun subscribePresence(userId: String) {}

        fun requestInitialHistory(chatId: String)
        fun requestHistoryPage(chatId: String)
        fun isHistoryExhausted(chatId: String): Boolean
        fun seekMessage(chatId: String, target: String, from: MessageRow, maxPages: Int)
        fun syncAllHistory(chatId: String): Boolean
        fun syncAllProgress(chatId: String): Int
        fun exportChat(chatId: String, uri: android.net.Uri): Boolean
        fun exportProgress(chatId: String): Int

        /** Dispatches the transfer; false when nothing was started (so no
         *  completion callback will follow and the caller must release its claim). */
        fun startDownload(msg: MessageRow): Boolean
        fun avatarPath(chatId: String, big: Boolean, cachedOnly: Boolean): String

        /** Whether a finished send may delete its cacheDir staging input. */
        val consumesStagingInput: Boolean
    }

    private fun proto(chatId: String): Protocol = if (isTg(chatId)) TgTransport else WaTransport

    /** WhatsApp, over the gomobile bridge. */
    private object WaTransport : Protocol {
        override fun sendText(chatId: String, text: String, quoted: MessageRow?): Boolean {
            if (quoted == null) return Wmbridge.sendTextMessage(connId, chatId, text).isNotEmpty()
            return Wmbridge.sendTextReply(
                connId, chatId, text, quoted.id, quotedPreview(quoted), quoted.senderId
            ).isNotEmpty()
        }

        override fun sendImage(chatId: String, path: String, caption: String, quoted: MessageRow?): Boolean {
            val (qid, qtext, qsender) = quoteArgs(quoted)
            return Wmbridge.sendImageMessage(connId, chatId, path, caption, qid, qtext, qsender).isNotEmpty()
        }

        override fun sendVideo(chatId: String, path: String, caption: String, quoted: MessageRow?): Boolean {
            val (qid, qtext, qsender) = quoteArgs(quoted)
            return Wmbridge.sendVideoMessage(connId, chatId, path, caption, qid, qtext, qsender).isNotEmpty()
        }

        override fun sendAudio(
            chatId: String, path: String, seconds: Int, quoted: MessageRow?, waveform: ByteArray,
        ): Boolean {
            val (qid, qtext, qsender) = quoteArgs(quoted)
            return Wmbridge.sendAudioMessage(
                connId, chatId, path, seconds.toLong(), qid, qtext, qsender, waveform
            ).isNotEmpty()
        }

        override fun sendDocument(
            chatId: String, path: String, name: String, mime: String, quoted: MessageRow?,
        ): Boolean {
            val (qid, qtext, qsender) = quoteArgs(quoted)
            return Wmbridge.sendDocumentMessage(
                connId, chatId, path, name, mime, qid, qtext, qsender
            ).isNotEmpty()
        }

        override fun sendLocation(chatId: String, latitude: Double, longitude: Double): Boolean =
            Wmbridge.sendLocation(connId, chatId, latitude, longitude).isNotEmpty()

        override fun edit(chatId: String, msgId: String, newText: String, origTimeSent: Long): Boolean =
            Wmbridge.editMessage(connId, chatId, msgId, newText, origTimeSent)

        override val editWindowSeconds: Long get() = waEditWindowSeconds
        // whatsmeow exposes no constant for the revoke window (only the edit
        // one), so WhatsApp's 60 hours is fixed here.
        override val revokeWindowSeconds: Long = 60L * 60 * 60

        override fun deleteForEveryone(chatId: String, msgId: String) {
            if (!Wmbridge.deleteMessageForEveryone(connId, chatId, msgId)) Log.w(TAG, "revoke failed")
        }

        // nothing to tell the server: the message stays on the phone and the
        // other linked devices, this client just forgets it
        override fun deleteForMe(chatId: String, msgId: String) {}

        override fun react(msg: MessageRow, emoji: String) {
            if (!Wmbridge.sendReaction(connId, msg.chatId, msg.id, msg.senderId, msg.fromMe, emoji)) {
                Log.w(TAG, "reaction failed for chat ${msg.chatId}")
            }
        }

        // the optimistic local write, its reconcile shield and the offline
        // rollback are involved enough to stay in one named place
        override fun setMuted(chatId: String, muted: Boolean) = setMutedWa(chatId, muted)

        override fun markChatRead(chatId: String) = markChatReadWa(chatId)

        override fun markVoicePlayed(msg: MessageRow) {
            Wmbridge.markVoicePlayed(connId, msg.chatId, msg.senderId, msg.id)
        }

        override fun subscribePresence(userId: String) {
            executor.execute { Wmbridge.subscribePresence(connId, userId) }
        }

        override fun requestInitialHistory(chatId: String) {
            executor.execute {
                if (db.messageCount(chatId) < INITIAL_HISTORY_MIN) requestHistoryPageWa(chatId)
            }
        }

        override fun requestHistoryPage(chatId: String) {
            executor.execute { requestHistoryPageWa(chatId) }
        }

        override fun isHistoryExhausted(chatId: String): Boolean = chatId in historyExhausted

        override fun seekMessage(chatId: String, target: String, from: MessageRow, maxPages: Int) =
            seekMessageWa(chatId, target, from.id, from.timeSent, from.fromMe, maxPages)

        override fun syncAllHistory(chatId: String): Boolean = syncAllHistoryWa(chatId)

        override fun syncAllProgress(chatId: String): Int =
            if (syncAllChat != chatId) -1 else asymptoticProgress(syncAllRounds)

        override fun exportChat(chatId: String, uri: android.net.Uri): Boolean =
            exportChatWa(chatId, uri)

        override fun exportProgress(chatId: String): Int =
            chatExport?.takeIf { it.chatId == chatId }?.collected?.size ?: -1

        override fun startDownload(msg: MessageRow): Boolean {
            mediaExecutor.execute {
                // The claim used to be released as soon as the request had been
                // HANDED to Go, not when the transfer finished, so every later
                // bind of the same bubble re-entered, re-queried fileState and
                // re-issued the download. It is now released by onFileDownloaded
                // — which also covers the media-retry path, where the bridge
                // returns immediately and the answer arrives up to 60s later.
                var dispatched = false
                try {
                    val (path, status) = db.fileState(msg.chatId, msg.id)
                    // a downloaded row whose file vanished from disk re-downloads
                    if (status >= 2 && path.isNotEmpty() && java.io.File(path).exists()) return@execute
                    db.setFileState(msg.chatId, msg.id, "", 1)
                    Wmbridge.downloadFile(connId, msg.chatId, msg.id, msg.fileId, msg.fromMe, msg.senderId)
                    dispatched = true
                } finally {
                    // nothing was sent, so no callback will ever free it
                    if (!dispatched) downloading.remove(msg.chatId + "/" + msg.id)
                }
            }
            return true
        }

        override fun avatarPath(chatId: String, big: Boolean, cachedOnly: Boolean): String = when {
            connId < 0 -> ""
            cachedOnly -> Wmbridge.getCachedAvatarPath(connId, chatId)
            big -> Wmbridge.getAvatarFullPath(connId, chatId)
            else -> Wmbridge.getAvatarPath(connId, chatId)
        }

        // the bridge keeps its own permanent media copy, so the staging input is
        // disposable the moment the (blocking) send returns
        override val consumesStagingInput: Boolean = true
    }

    /** Telegram, over TDLib. Every call here is already blocking in [Tg]. */
    private object TgTransport : Protocol {
        override fun sendText(chatId: String, text: String, quoted: MessageRow?): Boolean =
            Tg.sendText(chatId, text, quoted?.id ?: "")

        override fun sendImage(chatId: String, path: String, caption: String, quoted: MessageRow?): Boolean =
            Tg.sendImage(chatId, path, caption, quoted?.id ?: "")

        override fun sendVideo(chatId: String, path: String, caption: String, quoted: MessageRow?): Boolean =
            Tg.sendVideo(chatId, path, caption, quoted?.id ?: "")

        override fun sendAudio(
            chatId: String, path: String, seconds: Int, quoted: MessageRow?, waveform: ByteArray,
        ): Boolean = Tg.sendAudio(chatId, path, seconds, quoted?.id ?: "", waveform)

        override fun sendDocument(
            chatId: String, path: String, name: String, mime: String, quoted: MessageRow?,
        ): Boolean = Tg.sendDocument(chatId, path, name, quoted?.id ?: "")

        override fun sendLocation(chatId: String, latitude: Double, longitude: Double): Boolean =
            Tg.sendLocation(chatId, latitude, longitude)

        override fun edit(chatId: String, msgId: String, newText: String, origTimeSent: Long): Boolean =
            Tg.editMessageText(chatId, msgId, newText)

        // Telegram allows editing, and deleting for everyone, for 48 hours
        override val editWindowSeconds: Long = 48L * 60 * 60
        override val revokeWindowSeconds: Long = 48L * 60 * 60

        override fun deleteForEveryone(chatId: String, msgId: String) =
            Tg.deleteMessages(chatId, listOf(msgId), revoke = true)

        // deleted server-side for this account too, or the message would simply
        // come back with the next history fetch
        override fun deleteForMe(chatId: String, msgId: String) =
            Tg.deleteMessages(chatId, listOf(msgId), revoke = false)

        override fun react(msg: MessageRow, emoji: String) {
            Tg.sendReaction(msg.chatId, msg.id, emoji)
        }

        override fun setMuted(chatId: String, muted: Boolean) = Tg.setMuted(chatId, muted)
        override fun markChatRead(chatId: String) = Tg.markChatRead(chatId)
        override fun markVoicePlayed(msg: MessageRow) = Tg.markVoicePlayed(msg.chatId, msg.id)
        override fun openChat(chatId: String) = Tg.openChat(chatId)
        override fun closeChat(chatId: String) = Tg.closeChat(chatId)

        override fun requestInitialHistory(chatId: String) = Tg.requestInitialHistory(chatId)
        override fun requestHistoryPage(chatId: String) = Tg.requestHistoryPage(chatId)
        override fun isHistoryExhausted(chatId: String): Boolean = Tg.isHistoryExhausted(chatId)

        override fun seekMessage(chatId: String, target: String, from: MessageRow, maxPages: Int) =
            Tg.seekMessage(chatId, target, from.id, maxPages)

        override fun syncAllHistory(chatId: String): Boolean = Tg.syncAllHistory(chatId)
        override fun syncAllProgress(chatId: String): Int = Tg.syncAllProgress(chatId)
        override fun exportChat(chatId: String, uri: android.net.Uri): Boolean =
            Tg.exportChat(chatId, uri)
        override fun exportProgress(chatId: String): Int = Tg.exportProgress(chatId)

        override fun startDownload(msg: MessageRow): Boolean = Tg.downloadFile(msg)

        override fun avatarPath(chatId: String, big: Boolean, cachedOnly: Boolean): String =
            Tg.avatarPath(chatId, big = big, cachedOnly = cachedOnly)

        // TDLib may still be uploading from the staging path (the send request
        // returns before the transfer finishes) and the stored row plays back
        // from it until the daily sweep, so it must survive the send.
        override val consumesStagingInput: Boolean = false
    }

    // Memoised own JID. selfId() is asked once per chat-list row bind (every row
    // has to know whether it is your own chat), and each miss was a blocking
    // gomobile/JNI hop that takes the process-wide Go mutex — so a fling through
    // the list contended with the event goroutines dozens of times a second for
    // an answer that only changes at login and logout. Cleared by logout below.
    @Volatile private var selfIdMemo: String = ""

    fun selfId(): String {
        selfIdMemo.let { if (it.isNotEmpty()) return it }
        if (connId < 0) return ""
        return Wmbridge.getSelfId(connId).also { selfIdMemo = it }
    }

    fun connect() = executor.execute {
        if (state != "connected") Wmbridge.connect(connId)
    }

    fun startQrLogin() = executor.execute { Wmbridge.startLogin(connId) }

    fun stopLogin() = executor.execute { Wmbridge.stopLogin(connId) }

    // Runs on its own thread: it may block for several seconds while the
    // login socket reconnects, and the shared executor must stay free.
    fun requestPairCode(phone: String) = Thread {
        Wmbridge.requestPairCode(connId, phone)
    }.start()

    fun sendText(chatId: String, text: String) = executor.execute {
        if (!proto(chatId).sendText(chatId, text, quoted = null)) onSendFailed("text", chatId)
    }

    fun sendReply(chatId: String, text: String, quoted: MessageRow) = executor.execute {
        if (!proto(chatId).sendText(chatId, text, quoted)) Log.w(TAG, "reply failed for chat $chatId")
    }

    /** WhatsApp's edit window, as whatsmeow reports it. */
    private val waEditWindowSeconds: Long by lazy { Wmbridge.editWindowSeconds() }

    fun canEdit(msg: MessageRow): Boolean =
        System.currentTimeMillis() / 1000 - msg.timeSent < proto(msg.chatId).editWindowSeconds

    /** Whether [msg] is still within the "delete for everyone" window (and ours). */
    fun canDeleteForEveryone(msg: MessageRow): Boolean = msg.fromMe &&
        System.currentTimeMillis() / 1000 - msg.timeSent < proto(msg.chatId).revokeWindowSeconds

    // Shows a short toast from any thread (no-op before init).
    internal fun toastUi(resId: Int) {
        val ctx = appContext ?: return
        main.post {
            android.widget.Toast.makeText(ctx, resId, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Hops to the main thread and fans an event out to every UI listener.
    // With no listener attached (app backgrounded, service still connected)
    // there is nothing to deliver to, and posting anyway allocated a closure
    // plus a Message for every event the two protocols keep producing.
    private fun notifyUi(block: (UiListener) -> Unit) {
        if (listeners.isEmpty()) return
        main.post { for (l in listeners) block(l) }
    }

    fun editMessage(chatId: String, msgId: String, newText: String, origTimeSent: Long) =
        executor.execute {
            val ok = proto(chatId).edit(chatId, msgId, newText, origTimeSent)
            if (!ok) {
                Log.w(TAG, "edit failed")
                toastUi(R.string.edit_failed)
            }
        }

    fun deleteForEveryone(chatId: String, msgId: String) = executor.execute {
        proto(chatId).deleteForEveryone(chatId, msgId)
    }

    /** Reacts to a message; an empty emoji removes the previous reaction. */
    fun sendReaction(msg: MessageRow, emoji: String) = executor.execute {
        proto(msg.chatId).react(msg, emoji)
    }

    fun deleteForMe(chatId: String, msgId: String) = executor.execute {
        proto(chatId).deleteForMe(chatId, msgId)
        db.deleteMessage(chatId, msgId)
        notifyChat(chatId)
    }

    /**
     * Deletes a chat from local storage only — UniChat forgets it, but the chat
     * is left untouched on the phone's WhatsApp and other devices (it will
     * reappear here if new messages arrive or a history sync re-delivers it).
     * When deleteMedia is true, the chat's downloaded media files are also
     * removed from disk; otherwise they are left in place.
     */
    fun deleteChat(chatId: String, deleteMedia: Boolean) = executor.execute {
        val mediaPaths = if (deleteMedia) db.chatMediaPaths(chatId) else emptyList()
        db.deleteChat(chatId)
        appContext?.let { Prefs.setScrollAnchor(it, chatId, null, 0) }
        // drop this chat's pagination state so a later re-sync starts clean
        historyAnchor.remove(chatId)
        historyExhausted.remove(chatId)
        appContext?.let { ctx -> notifyExecutor.execute { Notifications.cancel(ctx, chatId) } }
        notifyChatsChanged()
        // unlink the files off the hot path: a media-heavy chat can hold
        // thousands, and doing it inline would stall unrelated bridge work
        // (presence, history, sending) queued on this single-thread executor.
        if (mediaPaths.isNotEmpty()) mediaExecutor.execute {
            for (path in mediaPaths) {
                if (path.isNotEmpty()) runCatching { java.io.File(path).delete() }
            }
        }
    }

    // Chats whose mute was just toggled locally and whose app-state round-trip
    // has not confirmed yet (via onMute). Reconcile skips these so a reconnect
    // that races the pending write can't clobber the user's just-made change.
    private val pendingMute = CopyOnWriteArraySet<String>()

    // Mutes/unmutes a chat: updates the local flag (crossed-bell marker + no
    // notifications) and syncs the change to WhatsApp so the phone and other
    // linked devices agree. The optimistic local write shows instantly; the
    // app-state round-trip confirms via onMute.
    fun setMuted(chatId: String, muted: Boolean) = executor.execute {
        proto(chatId).setMuted(chatId, muted)
    }

    private fun setMutedWa(chatId: String, muted: Boolean) {
        // UPDATE-only: a chat we hold no row for (e.g. a contact-only search
        // result) can't carry the flag locally, so don't claim an unconfirmed
        // local write for it — reconcile has nothing to protect and would
        // otherwise skip that chat forever.
        val storedLocally = db.setMuted(chatId, muted)
        if (storedLocally) {
            pendingMute.add(chatId)
            // The app-state write can fail silently (offline: the bridge only
            // logs). Without an expiry the chat stayed excluded from every
            // later reconcile for the whole process lifetime, so a mute made on
            // another device was never picked up. onMute clears it earlier on
            // the happy path.
            main.postDelayed({ pendingMute.remove(chatId) }, PENDING_MUTE_TTL_MS)
            notifyChatsChanged()
        }
        // the bridge now reports whether the app-state write actually went out;
        // an offline toggle used to fail with nothing but a log line
        if (!Wmbridge.setMute(connId, chatId, muted)) {
            Log.w(TAG, "mute change not synced for $chatId (offline?)")
            toastUi(R.string.mute_not_synced)
            if (storedLocally) {
                // nothing was written server-side, so let reconcile correct the
                // local flag on the next connect instead of shielding it
                pendingMute.remove(chatId)
            }
        }
    }

    // how long an unconfirmed local mute toggle shields a chat from reconcile
    private const val PENDING_MUTE_TTL_MS = 60_000L

    // Reconciles local mute flags with the server-synced app state: picks up
    // mutes made on other devices (including ones set before this feature
    // existed). Reads whatsmeow's persisted ChatSettings, so it is accurate as
    // soon as we are connected. Chats not muted on the server are cleared, so a
    // stale local mute can't linger; pending local toggles are left untouched.
    private fun reconcileMutes() = executor.execute {
        val flags = db.mutedFlags()
        // Telegram chats share this table but their mute lives on Telegram's
        // servers; asking the Go bridge about a "tg:" id always answers "not
        // muted", which silently un-muted every Telegram chat on each connect.
        val ids = flags.keys.filter { it !in pendingMute && !isTg(it) }
        if (ids.isEmpty()) return@execute
        val mutedNow = Wmbridge.mutedChats(connId, ids.joinToString("\n"))
            .split("\n").filterTo(HashSet()) { it.isNotEmpty() }
        var changed = false
        for (id in ids) {
            val serverMuted = id in mutedNow
            if (serverMuted != flags[id]) {
                db.setMuted(id, serverMuted)
                changed = true
            }
        }
        if (changed) notifyChatsChanged()
    }

    // short text shown in a quote preview / used as the quoted body when replying
    fun quotedPreview(m: MessageRow): String {
        val ctx = appContext ?: return m.text
        return previewLabel(ctx, m.msgType, m.text, emoji = false)
    }

    // The (id, preview, senderId) triple every media sender passes for an
    // optional quoted message — empty strings when the send is not a reply.
    private fun quoteArgs(q: MessageRow?): Triple<String, String, String> =
        Triple(q?.id ?: "", q?.let { quotedPreview(it) } ?: "", q?.senderId ?: "")

    /**
     * Every media send: upload off the shared executor, report a failure once,
     * then release the staging input if this service is done with it. The four
     * kinds below differ only in which Protocol call they make and what a
     * failure is called, so the scaffold is stated once.
     */
    private fun sendMedia(kind: String, chatId: String, filePath: String, send: (Protocol) -> Boolean) =
        mediaExecutor.execute {
            val p = proto(chatId)
            if (!send(p)) onSendFailed(kind, chatId)
            // Only ever inside cacheDir — forwards re-send straight from the
            // permanent media dir, which must survive.
            if (p.consumesStagingInput && isStagingPath(filePath)) java.io.File(filePath).delete()
        }

    fun sendImage(chatId: String, filePath: String, caption: String, quoted: MessageRow? = null) =
        sendMedia("image", chatId, filePath) { it.sendImage(chatId, filePath, caption, quoted) }

    fun sendVideo(chatId: String, filePath: String, caption: String, quoted: MessageRow? = null) =
        sendMedia("video", chatId, filePath) { it.sendVideo(chatId, filePath, caption, quoted) }

    fun sendAudio(
        chatId: String, filePath: String, durationSeconds: Int,
        quoted: MessageRow? = null, waveform: ByteArray = ByteArray(0),
    ) = sendMedia("audio", chatId, filePath) {
        it.sendAudio(chatId, filePath, durationSeconds, quoted, waveform)
    }

    /**
     * Sends a picked/shared file as the right message type for its MIME type.
     * The single owner of the "which sender does this file go through" rule —
     * the attach flow and the external-share flow used to each spell it out.
     */
    fun sendFile(
        chatId: String, filePath: String, fileName: String, mimeType: String,
        caption: String = "", quoted: MessageRow? = null,
    ) {
        when {
            mimeType.startsWith("image/") -> sendImage(chatId, filePath, caption, quoted)
            mimeType.startsWith("video/") -> sendVideo(chatId, filePath, caption, quoted)
            else -> sendDocument(chatId, filePath, fileName, mimeType, quoted)
        }
    }

    fun sendDocument(
        chatId: String, filePath: String, fileName: String, mimeType: String, quoted: MessageRow? = null,
    ) = sendMedia("document", chatId, filePath) {
        it.sendDocument(chatId, filePath, fileName, mimeType, quoted)
    }

    // A failed media send is otherwise invisible outside logcat (the share
    // flow has already finished by the time the upload runs); surface it.
    private fun onSendFailed(kind: String, chatId: String) {
        Log.w(TAG, "$kind send failed for chat $chatId")
        toastUi(R.string.send_failed)
    }

    /**
     * Whether a path is a disposable cacheDir staging file rather than the
     * bridge's permanent media copy. The single owner of that test: it decides
     * both what a finished send deletes and whether the UI may re-send a file
     * yet (a just-sent bubble still renders from its staging copy).
     */
    fun isStagingPath(filePath: String): Boolean {
        val cacheDir = appContext?.cacheDir ?: return false
        return filePath.startsWith(cacheDir.path + "/")
    }

    // Failed or abandoned sends can leave staging files behind; sweep anything
    // stale on startup (a day is far beyond any in-flight send).
    private fun cleanStaleCache(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        ctx.cacheDir.listFiles()?.forEach { f ->
            if (STAGING_PREFIXES.any { f.name.startsWith("${it}_") } && f.lastModified() < cutoff) {
                f.delete()
            }
        }
        // Telegram documents are handed to TDLib under their real name inside a
        // per-send directory (see Tg.sendDocument); TDLib uploads asynchronously,
        // so they can only be reclaimed later, by age.
        java.io.File(ctx.cacheDir, "tgdoc").listFiles()?.forEach { dir ->
            if (dir.lastModified() < cutoff) dir.deleteRecursively()
        }
    }

    fun sendLocation(chatId: String, latitude: Double, longitude: Double) = executor.execute {
        if (!proto(chatId).sendLocation(chatId, latitude, longitude)) {
            Log.w(TAG, "location send failed for chat $chatId")
        }
    }

    // A dedicated single thread for forwarding a batch: each message (media
    // included — its upload finishes) is sent to completion before the next
    // starts, so a multi-select forward arrives in the same order it was sent.
    // Off the shared executor/mediaExecutor so a slow upload never stalls live
    // sending or receiving.
    private val forwardExecutor = Executors.newSingleThreadExecutor()

    /**
     * Forwards [messages] (already in chronological order) to each target chat,
     * one at a time so they arrive in their original order. Callers must pass
     * only messages ready to send (media with a local file); onDone fires on the
     * main thread, true when at least one message was sent.
     */
    fun forwardMessages(
        targetChatIds: List<String>, messages: List<MessageRow>, onDone: (Boolean) -> Unit,
    ) = forwardExecutor.execute {
        var sent = false
        for (target in targetChatIds) {
            for (m in messages) if (forwardOneBlocking(target, m)) sent = true
        }
        main.post { onDone(sent) }
    }

    // Sends one forwarded message synchronously (blocks through any upload) —
    // the single owner of the forward type mapping, so the batch stays
    // strictly ordered. Forwards re-send from the permanent media dir, so
    // nothing is staged in cacheDir to clean up here.
    private fun forwardOneBlocking(target: String, m: MessageRow): Boolean {
        // cross-protocol forwards work because every send re-uploads the local
        // file, so a Telegram target takes the same paths a WhatsApp one does.
        // The mapping used to be spelled out once per protocol.
        val p = proto(target)
        val ok = when (m.msgType) {
            // a forwarded sticker is re-uploaded as an ordinary image
            "image", "sticker" -> p.sendImage(target, m.filePath, m.text, null)
            "video" -> p.sendVideo(target, m.filePath, m.text, null)
            "audio" ->
                p.sendAudio(target, m.filePath, TimeFormat.parseSeconds(m.text), null, ByteArray(0))
            // the stored text IS the document's file name; its MIME type is
            // recovered from the extension rather than sent empty (which the
            // bridge downgrades to application/octet-stream, leaving the
            // recipient a generic unopenable attachment)
            "document" -> p.sendDocument(target, m.filePath, m.text, mimeOfPath(m.filePath), null)
            "location" -> p.sendLocation(target, m.latitude, m.longitude)
            else -> p.sendText(target, m.text, null)
        }
        if (!ok) onSendFailed(m.msgType.ifEmpty { "text" }, target)
        return ok
    }

    // when each contact was last subscribed: the chat list asks per visible row,
    // so without this a scroll re-sends a subscription per rebind. Deliberately
    // NOT permanent — a WhatsApp presence subscription is short-lived on the
    // server and dropped on reconnect, which is why the open chat re-arms its
    // own every 30s; a for-the-run memo swallowed that refresh and froze the
    // subtitle (and the chat-list dot) at the first value seen.
    private val presenceSubscribed = ConcurrentHashMap<String, Long>()

    // just under the chat screen's re-arm interval, so the refresh gets through
    // while a scroll's worth of rebinds still collapses into one subscription
    private const val PRESENCE_MEMO_MS = 25_000L

    /**
     * Called per visible chat-list row. The memo is checked on the CALLING
     * thread: doing it inside the task meant every rebind still allocated and
     * queued a Runnable that almost always did nothing — onto the same serial
     * executor that carries sends, mark-read and history requests, so a fling
     * pushed a burst of no-ops ahead of real work.
     */
    fun subscribePresence(userId: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = presenceSubscribed[userId]
        if (last != null && now - last < PRESENCE_MEMO_MS) return
        presenceSubscribed[userId] = now
        proto(userId).subscribePresence(userId)
    }

    // downloads the user explicitly asked for (tap on media); only these get
    // a "download failed" toast — auto-downloads of expired history media
    // would otherwise spam toasts the user never asked for
    private val userRequestedDownloads = CopyOnWriteArraySet<String>()

    // Bubbles auto-download once on bind (fileStatus == 0). A message that
    // already failed (fileStatus == 3, e.g. history media whose server copy
    // had expired before the media-retry fix) gets ONE automatic retry per
    // process lifetime — not one per bind/scroll, which would hammer the
    // phone with a media-retry request on every rebind of a permanently
    // unavailable attachment. A manual tap always retries regardless.
    // Hash set (not copy-on-write) and bounded: one entry accumulated per
    // permanently-failed media row, so a scroll through a history of expired
    // media used to add thousands of permanent entries, each add copying the
    // whole backing array.
    private val autoRetriedFailures: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private const val MAX_RETRY_MEMO = 4096

    /**
     * Downloads a message's media once; progress lands in the DB and the chat is
     * notified via onFileDownloaded. Idempotent across calls and restarts: the
     * current status is re-checked against the database.
     *
     * Returns false when the request was declined outright (no media reference,
     * or an already-auto-retried failure) — i.e. no onFileDownloaded will follow,
     * which callers waiting on one must handle.
     */
    fun downloadFile(msg: MessageRow, userInitiated: Boolean = false): Boolean {
        // A stored path outlives its file (our own Telegram sends reference the
        // cacheDir staging copy, swept after a day), and the bubble goes on
        // claiming "downloaded". Both transfer paths below already drop such a
        // path on their own worker before fetching — but with no media reference
        // neither runs, so nothing would ever correct the row and every bind
        // would re-enter here. Clear it off the UI thread: this is reached from
        // onBindViewHolder.
        if (msg.fileId.isEmpty()) {
            if (msg.filePath.isNotEmpty()) executor.execute {
                if (java.io.File(msg.filePath).exists()) return@execute
                if (db.setFileState(msg.chatId, msg.id, "", 0) > 0) notifyChatRow(msg.chatId, msg.id)
            }
            return false
        }
        // Throttling policy, one copy for both protocols: the user-initiated set
        // (so onTgFileDone / onFileDownloaded can tell a silent auto-download
        // from a manual one), the once-per-run retry of a permanently failed
        // file, and the in-flight claim.
        val key = msg.chatId + "/" + msg.id
        if (userInitiated) {
            userRequestedDownloads.add(key)
        } else if (msg.fileStatus == 3) {
            // bind-time retry of a file the server called unreachable: once per
            // run, or scrolling past it re-issues the download on every rebind
            if (autoRetriedFailures.size > MAX_RETRY_MEMO) autoRetriedFailures.clear()
            if (!autoRetriedFailures.add(key)) return false
        }
        // Claim the slot BEFORE touching the DB: this is called from
        // onBindViewHolder, so a scroll would otherwise queue one point query per
        // rebind onto the pool that performs the real transfers.
        // A stored status of 1 ("downloading") is NOT proof that a download is
        // running: it survives a process death mid-transfer, and taking it at
        // face value left those rows blank forever, with every later bind
        // returning here without re-issuing anything. Only an in-flight claim
        // from THIS run may skip the request.
        if (!downloading.add(key)) return true
        // Nothing dispatched means no completion will ever release the claim,
        // and the message would be stuck for the rest of the run with every
        // later attempt returning here.
        val started = proto(msg.chatId).startDownload(msg)
        if (!started) downloading.remove(key)
        return started
    }

    // Asks the primary phone for one older page of a chat (scroll-to-top
    // pagination). Repeatable; a page that reaches the chat's start marks it
    // exhausted for this run. Shares the single in-flight slot with sync-all
    // and export, so a request made while one is busy is simply dropped (the
    // next scroll retries).
    fun requestChatHistory(chatId: String) = proto(chatId).requestHistoryPage(chatId)

    // True once this run has paged a chat back to its start — no older history
    // remains to fetch. Lets jump-to-quote stop seeking instead of waiting for a
    // page that will never come.
    fun isHistoryExhausted(chatId: String): Boolean = proto(chatId).isHistoryExhausted(chatId)

    // --- jump-to-quote history seek -----------------------------------------
    // Fetches an unsynced quoted message by paging history BACKWARD FROM THE
    // REPLYING MESSAGE (which is always newer than what it quotes) until the
    // target lands. This reaches messages sitting in a gap between synced chunks
    // — which the global older-than-oldest pagination can never fill. Uses its
    // own anchor so it doesn't disturb scroll pagination / sync-all.
    private class SeekState(
        val chatId: String, val targetId: String, var anchor: Anchor, var pagesLeft: Int,
    ) {
        // bounded retries while the shared history slot is busy, so a long
        // export can delay a seek but never spin against it forever
        var busyRetriesLeft: Int = 40
    }
    @Volatile private var seek: SeekState? = null

    /**
     * Pages [chatId]'s history backward from the replying message (fromId/fromTime/
     * fromFromMe) until [targetId] is stored locally, up to [maxPages] pages.
     * Result via UiListener.onSeekResult on the main thread. Returns found
     * immediately if the target is already stored.
     */
    fun seekMessage(chatId: String, targetId: String, from: MessageRow, maxPages: Int) =
        proto(chatId).seekMessage(chatId, targetId, from, maxPages)

    private fun seekMessageWa(
        chatId: String, targetId: String,
        fromId: String, fromTime: Long, fromFromMe: Boolean, maxPages: Int,
    ) = executor.execute {
        if (db.hasMessage(chatId, targetId)) { notifySeek(chatId, targetId, true); return@execute }
        seek = SeekState(chatId, targetId, Anchor(fromId, fromTime, fromFromMe), maxPages)
        driveSeekPage()
    }

    // Sends the next seek page (executor-confined); retries shortly if the shared
    // history slot is momentarily busy, so a seek can't be starved by a stray
    // pagination/sync request.
    private fun driveSeekPage() {
        val s = seek ?: return
        if (db.hasMessage(s.chatId, s.targetId)) { seek = null; notifySeek(s.chatId, s.targetId, true); return }
        if (s.pagesLeft <= 0) { seek = null; notifySeek(s.chatId, s.targetId, false); return }
        if (sendHistoryPage(s.chatId, s.anchor, forExport = false, forSeek = true) != null) {
            s.pagesLeft--
        } else if (s.busyRetriesLeft-- > 0) {
            main.postDelayed({ executor.execute { driveSeekPage() } }, 400)
        } else {
            seek = null
            notifySeek(s.chatId, s.targetId, false)
        }
    }

    internal fun notifySeek(chatId: String, msgId: String, found: Boolean) =
        notifyUi { it.onSeekResult(chatId, msgId, found) }

    /**
     * Abandons any in-flight jump-to-quote seek — the caller superseded it or
     * left the chat. Stops it from paging further history (a page already sent
     * finishes but its delivery is ignored) and drops the pending result.
     */
    fun cancelSeek() = executor.execute { seek = null }

    // On-open top-up: fetch one older page only when little is stored locally,
    // so repeatedly opening a well-synced chat doesn't keep waking the phone
    // and growing the DB. Scroll-to-top pagination (requestChatHistory) is
    // unguarded — that's an explicit user request for more.
    fun requestInitialHistory(chatId: String) = proto(chatId).requestInitialHistory(chatId)

    // --- history request: single in-flight slot ------------------------------
    // At most ONE on-demand request is outstanding at a time. The phone's
    // end-of-history response names no chat, so a single in-flight request
    // keeps attribution unambiguous and lets a stale timeout or a superseded /
    // duplicate delivery be recognised (by generation / mismatch) and ignored.
    // All slot state is confined to the executor thread.

    private class HistoryReq(
        val chatId: String, val anchorId: String, val forExport: Boolean, val gen: Long,
        val forSeek: Boolean = false,
    )
    private class Anchor(val id: String, val time: Long, val fromMe: Boolean)

    @Volatile private var historyInFlight: HistoryReq? = null
    private var historyGen = 0L
    // next-request anchor per chat (non-export), advanced by each delivered
    // page's reported oldest so a page of only non-displayable entries still
    // makes progress; seeded from the local oldest message
    private val historyAnchor = ConcurrentHashMap<String, Anchor>()

    // Claims the slot and sends one page request; null if a request is already
    // in flight or the send failed. Executor-confined.
    private fun sendHistoryPage(
        chatId: String, anchor: Anchor, forExport: Boolean, forSeek: Boolean = false,
    ): HistoryReq? {
        if (historyInFlight != null) return null
        val gen = ++historyGen
        if (!Wmbridge.requestChatHistory(
                connId, chatId, anchor.id, anchor.time, anchor.fromMe, HISTORY_PAGE, forExport)
        ) return null
        val req = HistoryReq(chatId, anchor.id, forExport, gen, forSeek)
        historyInFlight = req
        // an unanswered request (phone offline/asleep) must not wedge the slot.
        // No resend on timeout: a slow-but-alive phone would then answer twice
        // and a duplicate page could be mis-attributed or mis-stored, so a
        // dropped page ends the op instead (the user can re-run it).
        main.postDelayed({ executor.execute { historyTimeout(gen) } }, HISTORY_TIMEOUT_MS)
        return req
    }

    private fun historyTimeout(gen: Long) {
        val req = historyInFlight ?: return
        if (req.gen != gen) return // already answered or superseded
        historyInFlight = null
        Log.w(TAG, "history request timed out for ${req.chatId}")
        if (req.forSeek) {
            // Only fail the seek this request actually belongs to. A timeout for
            // a superseded page used to cancel whatever seek was current and
            // report a failure for it — so tapping a second quote while the
            // first was still paging toasted "message not loaded" for the second.
            seek?.let { s ->
                if (s.chatId == req.chatId) { seek = null; notifySeek(s.chatId, s.targetId, false) }
            }
            return
        }
        if (req.forExport) chatExport?.let { if (it.chatId == req.chatId) finishExport(it, complete = false) }
        else endSyncAll(req.chatId, complete = false)
    }

    // Requests the next older page for a chat into local storage (pagination +
    // sync-all). retryIfBusy keeps a self-driven sync-all trying when a stray
    // pagination request momentarily holds the slot; plain pagination does not
    // retry (the next scroll does). Executor-confined.
    private fun requestHistoryPageWa(chatId: String, retryIfBusy: Boolean = false) {
        if (chatId in historyExhausted) { endSyncAll(chatId, complete = true); return }
        val anchor = historyAnchor[chatId]
            ?: db.oldestMessage(chatId)?.let { Anchor(it.id, it.timeSent, it.fromMe) }
        if (anchor == null) { endSyncAll(chatId, complete = false); return } // no local anchor
        historyAnchor[chatId] = anchor
        if (sendHistoryPage(chatId, anchor, forExport = false) == null &&
            retryIfBusy && syncAllChat == chatId
        ) {
            main.postDelayed({ executor.execute { requestHistoryPageWa(chatId, true) } }, 500)
        }
    }

    override fun onChatHistoryDelivered(
        chatId: String, count: Long, forExport: Boolean,
        oldestId: String, oldestTime: Long, oldestFromMe: Boolean,
    ) = executor.execute {
        val req = historyInFlight
        // ignore anything that isn't the answer to the request in flight: a
        // stale/duplicate page, or a live/initial sync we didn't request
        if (req == null || req.chatId != chatId || req.forExport != forExport) return@execute
        historyInFlight = null
        // end of history: an empty page, or nothing older than what we asked from
        val exhausted = count == 0L || oldestId.isEmpty() || oldestId == req.anchorId
        // jump-to-quote seek: walking history backward from the replying message
        // to fill in the (possibly gapped) range down to the target. Drives off
        // its own anchor, independent of the global pagination/exhaustion state.
        if (req.forSeek) {
            val s = seek
            if (s == null || s.chatId != chatId) return@execute
            when {
                db.hasMessage(chatId, s.targetId) -> { seek = null; notifySeek(chatId, s.targetId, true) }
                exhausted -> { seek = null; notifySeek(chatId, s.targetId, false) }
                else -> { s.anchor = Anchor(oldestId, oldestTime, oldestFromMe); driveSeekPage() }
            }
            return@execute
        }
        if (forExport) {
            val ex = chatExport ?: return@execute
            if (ex.chatId != chatId) return@execute
            if (exhausted) { finishExport(ex, complete = true); return@execute }
            ex.anchor = Anchor(oldestId, oldestTime, oldestFromMe)
            notifyExportProgress(ex)
            requestExportPage(ex)
            return@execute
        }
        if (exhausted) {
            historyExhausted.add(chatId)
            endSyncAll(chatId, complete = true)
        } else {
            historyAnchor[chatId] = Anchor(oldestId, oldestTime, oldestFromMe)
            continueSyncAll(chatId) // no-op unless a sync-all runs on this chat
        }
    }

    // --- full-chat sync ("Sync all") ----------------------------------------
    // Repeatedly pages a chat's history into local storage until its start is
    // reached. Only one whole-history op (sync-all or export) runs at a time.

    @Volatile private var syncAllChat: String? = null
    @Volatile private var syncAllRounds = 0

    /**
     * Percentage shown while a sync-all runs on this chat, -1 otherwise. The
     * total isn't knowable up front (the phone never reports how many messages
     * a chat holds), so it advances asymptotically per fetched page and jumps
     * to 100 when the start is reached.
     */
    fun syncAllProgress(chatId: String): Int = proto(chatId).syncAllProgress(chatId)

    /** The percentage above, shared with the Telegram sync-all so both report
     *  the same curve (and the same -1 "not running" convention). */
    internal fun asymptoticProgress(rounds: Int): Int = 100 * rounds / (rounds + 1)

    /**
     * Starts fetching a chat's entire history. Returns false if another
     * whole-history op is already running (an export, or a sync-all on a
     * different chat).
     */
    fun syncAllHistory(chatId: String): Boolean = proto(chatId).syncAllHistory(chatId)

    private fun syncAllHistoryWa(chatId: String): Boolean {
        if (chatExport != null) return false
        val cur = syncAllChat
        if (cur != null && cur != chatId) return false
        if (cur == chatId) return true // already running
        executor.execute {
            if (chatExport != null || (syncAllChat != null && syncAllChat != chatId)) return@execute
            // Restart the walk at the NEWEST message instead of resuming from
            // the oldest one held. Paging only ever moves backwards, so
            // resuming just extends the far end and leaves any hole in the
            // middle — a stretch the phone never delivered — permanently empty.
            // Re-fetching what is already stored is cheap (the upsert is
            // idempotent) and is the only thing that closes those gaps.
            historyExhausted.remove(chatId)
            db.newestMessage(chatId)?.let {
                historyAnchor[chatId] = Anchor(it.id, it.timeSent, it.fromMe)
            }
            syncAllChat = chatId
            syncAllRounds = 0
            notifySyncAll(chatId, 0)
            requestHistoryPageWa(chatId, retryIfBusy = true)
        }
        return true
    }

    private fun continueSyncAll(chatId: String) {
        if (syncAllChat != chatId) return
        syncAllRounds++
        notifySyncAll(chatId, syncAllProgress(chatId))
        requestHistoryPageWa(chatId, retryIfBusy = true)
    }

    // complete = the chat's start was reached (100%); otherwise a failure (-1,
    // e.g. the phone didn't answer). No-op if no sync-all runs on this chat.
    private fun endSyncAll(chatId: String, complete: Boolean) {
        if (syncAllChat != chatId) return
        syncAllChat = null
        syncAllRounds = 0
        notifySyncAll(chatId, if (complete) 100 else -1)
    }

    // Drops a sync-all without a completion/failure signal, for when an export
    // supersedes it (the export owns the subtitle from here).
    private fun cancelSyncAllQuietly(chatId: String) {
        if (syncAllChat != chatId) return
        syncAllChat = null
        syncAllRounds = 0
    }

    internal fun notifySyncAll(chatId: String, progress: Int) =
        notifyUi { it.onChatSyncProgress(chatId, progress) }

    // --- full-history export -------------------------------------------------
    // Fetches the chat's complete history from the phone WITHOUT storing it:
    // the bridge routes those pages to onExportMessage instead of the DB, so
    // the chat's local sync state stays exactly as it was. The bridge owns the
    // whole flow including writing the file, so no activity is captured; UIs
    // observe progress/completion through the (lifecycle-managed) UiListener.

    private class ChatExport(val chatId: String, val uri: android.net.Uri) {
        val collected = ConcurrentHashMap<String, MessageRow>()
        @Volatile var anchor: Anchor? = null
        // bounded retries while the shared history slot is busy, mirroring
        // SeekState: an unreleased slot must not spin forever. Executor-confined.
        var busyRetriesLeft: Int = 40
    }

    @Volatile private var chatExport: ChatExport? = null

    /** Fetched-message count of a running export, -1 when none is active. */
    fun exportProgress(chatId: String): Int = proto(chatId).exportProgress(chatId)

    /**
     * Exports the chat's complete history to uri: pages older than the local
     * oldest are fetched into memory only, merged with the local store, and
     * written as text. Progress and completion are reported via UiListener.
     * Returns false if another export, or a sync-all on a different chat, is
     * running (a sync-all on THIS chat is superseded).
     */
    fun exportChat(chatId: String, uri: android.net.Uri): Boolean = proto(chatId).exportChat(chatId, uri)

    private fun exportChatWa(chatId: String, uri: android.net.Uri): Boolean {
        if (chatExport != null) return false
        val cur = syncAllChat
        if (cur != null && cur != chatId) return false
        executor.execute {
            if (chatExport != null || (syncAllChat != null && syncAllChat != chatId)) return@execute
            cancelSyncAllQuietly(chatId)
            val ex = ChatExport(chatId, uri)
            chatExport = ex
            val oldest = db.oldestMessage(chatId)
            if (oldest == null || chatId in historyExhausted) {
                // empty chat, or everything the phone holds is already local
                finishExport(ex, complete = true)
                return@execute
            }
            ex.anchor = Anchor(oldest.id, oldest.timeSent, oldest.fromMe)
            notifyExportProgress(ex)
            requestExportPage(ex)
        }
        return true
    }

    private fun requestExportPage(ex: ChatExport) {
        executor.execute {
            if (chatExport !== ex) return@execute
            val anchor = ex.anchor ?: return@execute finishExport(ex, complete = false)
            // Retry a momentarily-busy slot (a stray pagination request). Bounded
            // like the seek path: this used to repost every 500ms with no cap, so
            // a slot that was never released — a delivery dropped without
            // matching historyInFlight, or a timeout that returned early — kept
            // a ChatExport (and its whole collected message map) alive and woke
            // the main looper twice a second for the process's lifetime.
            if (sendHistoryPage(ex.chatId, anchor, forExport = true) == null) {
                if (ex.busyRetriesLeft-- > 0) {
                    main.postDelayed({ requestExportPage(ex) }, 500)
                } else {
                    Log.w(TAG, "history slot busy too long; ending export of ${ex.chatId}")
                    finishExport(ex, complete = false)
                }
            }
        }
    }

    private fun notifyExportProgress(ex: ChatExport) {
        val fetched = ex.collected.size
        notifyUi { it.onChatExportProgress(ex.chatId, fetched) }
    }

    private fun finishExport(ex: ChatExport, complete: Boolean) {
        if (chatExport !== ex) return
        chatExport = null
        executor.execute { writeExport(ex, complete) }
    }

    // Merges the fetched history with the local store (local rows win — they
    // carry richer state) and writes the file; the UI learns the outcome via
    // the listener, so a recreated activity still gets the completion.
    /** Hands back the write grant the activity gave the exporter. Both protocols'
     *  exports end here: a grant left held counts against the system's per-app
     *  limit for the life of the app. */
    internal fun releaseExportUri(uri: android.net.Uri) {
        val ctx = appContext ?: return
        try {
            ctx.contentResolver.releasePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
        }
    }

    private fun writeExport(ex: ChatExport, complete: Boolean) {
        val ctx = appContext ?: return
        var messages = 0
        // catch Throwable, not Exception: a long export merges the whole fetched
        // history with the whole local store, so OutOfMemoryError is the most
        // likely failure — and being an Error it escaped the old catch, leaving
        // the UI waiting on a completion callback that never fired
        val success = try {
            val all = LinkedHashMap<String, MessageRow>()
            for (m in ex.collected.values) all[m.id] = m
            // free the fetched copy as soon as it has been merged
            ex.collected.clear()
            for (m in db.messages(ex.chatId, Int.MAX_VALUE)) all[m.id] = m
            val sorted = all.values.sortedBy { it.timeSent }
            all.clear()
            messages = sorted.size
            ChatExporter.write(ctx, db, ex.chatId, ex.uri, sorted)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "export write failed: $e")
            false
        }
        releaseExportUri(ex.uri)
        notifyUi { it.onChatExportDone(ex.chatId, messages, complete, success) }
    }

    override fun onExportMessage(
        chatId: String, msgId: String, senderId: String, text: String, fromMe: Boolean,
        timeSent: Long, msgType: String, fileId: String, senderName: String, isEdited: Boolean,
    ) {
        val ex = chatExport ?: return
        if (ex.chatId != chatId) return
        ex.collected[msgId] = MessageRow(
            msgId, chatId, senderId, text, fromMe, timeSent, isRead = true,
            msgType = msgType, fileId = fileId, senderName = senderName, edited = isEdited
        )
    }

    fun markChatRead(chatId: String) = proto(chatId).markChatRead(chatId)

    private fun markChatReadWa(chatId: String) = executor.execute {
        val latest = db.latestUnread(chatId) ?: return@execute
        db.markChatRead(chatId)
        Wmbridge.markRead(connId, chatId, latest.senderId, latest.id, latest.timeSent)
        // Only the chat LIST needs this (unread badge). A per-chat message change
        // would bounce straight back into the open chat's reload — which had just
        // called markChatRead — costing a second full window query, N+1 quote-name
        // pass and diff per incoming message, for a state change the message
        // differ deliberately ignores on incoming rows.
        notifyChatsChanged()
    }

    // --- account-scoped operations (profile, privacy) -----------------------
    // Chat-scoped calls route by the chat id's "tg:" prefix; an account has no
    // chat id, so these take the protocol explicitly. They exist so the account
    // screens don't each hand-roll the dispatch: Tg's account calls are
    // blocking, Bridge's are async-with-main-thread-callback, and every call
    // site used to re-decide which worker to use and how to get back to main.

    /** Runs a blocking Telegram account call off the UI thread and delivers its
     *  result on the main thread — the same contract the WhatsApp paths use. */
    private fun <T> onTg(work: () -> T, onResult: (T) -> Unit) {
        Tg.io.execute {
            val result = work()
            main.post { onResult(result) }
        }
    }

    private fun isTgProto(proto: String) = proto == ProtoPicker.TG

    /** Our own profile name on [proto] — the name shown to everyone. Local read. */
    fun myName(proto: String): String = if (isTgProto(proto)) Tg.myName() else myName()

    /** Our own id on [proto]. */
    fun selfId(proto: String): String = if (isTgProto(proto)) Tg.selfId() else selfId()

    fun fetchMyAbout(proto: String, onResult: (String) -> Unit) =
        if (isTgProto(proto)) onTg({ Tg.fetchMyAbout() }, onResult) else fetchMyAbout(onResult)

    fun setMyName(proto: String, name: String, onResult: (Boolean) -> Unit) =
        if (isTgProto(proto)) onTg({ Tg.setMyName(name) }, onResult) else setMyName(name, onResult)

    fun setAbout(proto: String, text: String, onResult: (Boolean) -> Unit) =
        if (isTgProto(proto)) onTg({ Tg.setAbout(text) }, onResult) else setAbout(text, onResult)

    fun setProfilePicture(proto: String, jpegPath: String, onResult: (Boolean) -> Unit) =
        if (isTgProto(proto)) onTg({ Tg.setProfilePicture(jpegPath) }, onResult)
        else setProfilePicture(jpegPath, onResult)

    fun fetchPrivacySettings(proto: String, onResult: (Map<String, String>?) -> Unit) =
        if (isTgProto(proto)) onTg({ Tg.fetchPrivacySettings() }, onResult)
        else fetchPrivacySettings(onResult)

    fun setPrivacySetting(proto: String, name: String, value: String, onResult: (Boolean) -> Unit) =
        if (isTgProto(proto)) onTg({ Tg.setPrivacySetting(name, value) }, onResult)
        else setPrivacySetting(name, value, onResult)

    // --- account privacy settings (WhatsApp transport) ----------------------

    /** Fetches the account's privacy settings; null on failure. Main-thread callback. */
    fun fetchPrivacySettings(onResult: (Map<String, String>?) -> Unit) = executor.execute {
        val raw = Wmbridge.getPrivacySettings(connId)
        val map = if (raw.isEmpty()) null else raw.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        main.post { onResult(map) }
    }

    /** Updates one privacy setting (account-wide). Main-thread callback. */
    fun setPrivacySetting(name: String, value: String, onResult: (Boolean) -> Unit) =
        executor.execute {
            val ok = Wmbridge.setPrivacySetting(connId, name, value)
            main.post { onResult(ok) }
        }

    // --- own profile --------------------------------------------------------

    /** Our own profile (push) name — the name shown to everyone. Local read, "" if unset. */
    fun myName(): String = if (connId >= 0) Wmbridge.getMyName(connId) else ""

    /** Updates our own profile (push) name (account-wide). Main-thread callback. */
    fun setMyName(name: String, onResult: (Boolean) -> Unit) = executor.execute {
        val ok = connId >= 0 && Wmbridge.setMyName(connId, name)
        main.post { onResult(ok) }
    }

    /** Fetches our own "About" text fresh from the server ("" if unset). Main-thread callback. */
    fun fetchMyAbout(onResult: (String) -> Unit) = executor.execute {
        val about = if (connId >= 0) Wmbridge.getMyAbout(connId) else ""
        main.post { onResult(about) }
    }

    /** Updates our own "About" text (account-wide). Main-thread callback. */
    fun setAbout(text: String, onResult: (Boolean) -> Unit) = executor.execute {
        val ok = connId >= 0 && Wmbridge.setAbout(connId, text)
        main.post { onResult(ok) }
    }

    /**
     * Sets our own profile picture from a square JPEG file (uploads on the media
     * executor, like other media). Main-thread callback.
     */
    fun setProfilePicture(jpegPath: String, onResult: (Boolean) -> Unit) = mediaExecutor.execute {
        val ok = connId >= 0 && Wmbridge.setProfilePicture(connId, jpegPath)
        main.post { onResult(ok) }
    }

    fun getAvatarPath(chatId: String): String =
        proto(chatId).avatarPath(chatId, big = false, cachedOnly = false)

    /**
     * On-disk avatar path only — never a network fetch. Used on the notification
     * path, which is a single serialized thread: a stale-cache fetch there is a
     * blocking, timeout-less HTTP request that delays the alert and every task
     * queued behind it (including the cancel that fires when the user opens the
     * chat). A missing/stale picture is worth less than a prompt notification.
     */
    fun getCachedAvatarPath(chatId: String): String =
        proto(chatId).avatarPath(chatId, big = false, cachedOnly = true)

    fun getAvatarFullPath(chatId: String): String =
        proto(chatId).avatarPath(chatId, big = true, cachedOnly = false)

    // Fetches the full-resolution avatar off-thread and opens it fullscreen.
    // The fetch can block on the network for a long time, so the activity may
    // well be gone by the time it returns — launching a viewer from a destroyed
    // activity would pop it over whatever screen the user moved on to.
    fun openAvatar(activity: android.app.Activity, chatId: String) {
        mediaExecutor.execute {
            var path = getAvatarFullPath(chatId)
            if (path.isEmpty()) path = getAvatarPath(chatId) // fall back to thumbnail
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (path.isEmpty()) {
                    android.widget.Toast.makeText(activity, R.string.no_avatar, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    activity.startActivity(
                        android.content.Intent(activity, ImageViewActivity::class.java)
                            .putExtra("path", path).putExtra("chatId", chatId)
                    )
                }
            }
        }
    }

    // Set while logout wipes the store: incoming Go-thread events are dropped
    // instead of re-inserting rows into the just-cleared database (which showed
    // up as ghost chats from the account that was just unlinked).
    @Volatile private var wiping = false

    fun logout() = executor.execute {
        // resolve in-flight work first so no UI is left waiting forever: a
        // running export is written out with what it has (before the local
        // store is wiped), and running sync-alls report their abort
        chatExport?.let { ex ->
            chatExport = null
            writeExport(ex, complete = false)
        }
        syncAllChat?.let { endSyncAll(it, complete = false) }
        wiping = true
        try {
            Wmbridge.logout(connId)
            // WhatsApp rows only: this is the WhatsApp unlink path (the menu
            // picks per protocol), and clearing everything destroyed a linked
            // Telegram account's whole local store along with it.
            db.clearWaData()
        } finally {
            wiping = false
        }
        // ALL per-session state, not just the history bookkeeping: anything left
        // here leaks across a logout/re-login as the previous account's data
        // (a stale "typing…", a previous account's avatar, playback that keeps
        // running on a file whose chat no longer exists).
        main.post { AudioPlayer.stop() }
        selfIdMemo = ""
        activeChatId = ""
        autoPlayKey = null
        historyInFlight = null
        seek = null
        historyAnchor.clear()
        historyExhausted.clear()
        syncAllChat = null
        syncAllRounds = 0
        pendingMute.clear()
        downloading.clear()
        userRequestedDownloads.clear()
        autoRetriedFailures.clear()
        online.clear()
        lastSeen.clear()
        presenceSubscribed.clear()
        lastSeenApprox.clear()
        chatStates.clear()
        main.post {
            chatActors.clear()
            for ((_, clearer) in stateClearers) main.removeCallbacks(clearer)
            stateClearers.clear()
        }
        appContext?.let { Notifications.cancelAllMessages(it) }
        notifyChatsChanged()
    }

    /**
     * Telegram download finished (or failed). Funnels into the same bookkeeping
     * the WhatsApp callback uses so the voice auto-chain and the "download
     * failed" toast work for both protocols — Tg writes the file state itself,
     * so this only settles the in-process claims.
     */
    internal fun onTgFileDone(chatId: String, msgId: String, filePath: String, status: Int) {
        val key = "$chatId/$msgId"
        downloading.remove(key)
        if (status == 3 && userRequestedDownloads.remove(key)) toastUi(R.string.download_failed)
        else userRequestedDownloads.remove(key)
        if (autoPlayKey == key) {
            autoPlayKey = null
            if (status == 2 && filePath.isNotEmpty()) main.post { AudioPlayer.play(filePath, chatId, msgId) }
        }
    }

    fun addListener(l: UiListener) = listeners.add(l)
    fun removeListener(l: UiListener) = listeners.remove(l)

    // --- debounced UI notification -----------------------------------------

    private val notifyRunnable = Runnable {
        val chats: List<Pair<String, Set<String>?>>
        val chatsListChanged: Boolean
        val contactsDidChange: Boolean
        synchronized(changedChats) {
            chats = changedChats.map { (id, rows) -> id to rows?.toSet() }
            chatsListChanged = chatsChanged
            contactsDidChange = contactsChanged
            changedChats.clear()
            chatsChanged = false
            contactsChanged = false
            notifyPending = false
        }
        for (l in listeners) {
            if (contactsDidChange) l.onContactsChanged()
            if (chatsListChanged) l.onChatsChanged()
            for ((chatId, rows) in chats) l.onMessagesChanged(chatId, rows)
        }
    }

    private fun scheduleNotify() {
        synchronized(changedChats) {
            if (notifyPending) return
            notifyPending = true
        }
        main.postDelayed(notifyRunnable, 150)
    }

    /** The chat's loaded window may have changed shape — a message arrived, was
     *  deleted, or a history page landed. Forces a full re-read. */
    internal fun notifyChat(chatId: String) {
        synchronized(changedChats) {
            changedChats[chatId] = null
            chatsChanged = true
        }
        scheduleNotify()
    }

    /**
     * Exactly [msgId]'s stored row changed — a tick, a reaction, a played mark,
     * a finished download. Nothing was inserted, removed or reordered, so the
     * open chat can re-read that one row. Any [notifyChat] for the same chat in
     * the same batch wins: it means the window itself moved.
     */
    internal fun notifyChatRow(chatId: String, msgId: String) {
        synchronized(changedChats) {
            if (changedChats.containsKey(chatId)) {
                // null = a structural change is already queued; leave it
                changedChats[chatId]?.add(msgId)
            } else {
                changedChats[chatId] = hashSetOf(msgId)
            }
            chatsChanged = true
        }
        scheduleNotify()
    }

    internal fun notifyChatsChanged() {
        synchronized(changedChats) { chatsChanged = true }
        scheduleNotify()
    }

    // --- EventListener (Go threads) -----------------------------------------

    override fun onStateChanged(state: String) {
        val wasConnected = this.state == "connected"
        this.state = state
        Log.i(TAG, "state: $state")
        // on a fresh connection, pull the server-synced mute settings so
        // existing (possibly other-device) mutes are reflected locally
        if (state == "connected" && !wasConnected) {
            reconcileMutes()
            // the server drops every presence subscription with the socket, so
            // the memo must not keep claiming they are still in place
            presenceSubscribed.clear()
        }
        notifyUi { it.onStateChanged(state) }
    }

    override fun onQrCode(code: String) = notifyUi { it.onQrCode(code) }

    override fun onPairCode(code: String) = notifyUi { it.onPairCode(code) }

    override fun onPairError(message: String) = notifyUi { it.onPairError(message) }

    override fun onSyncProgress(progress: Long) {
        syncProgress = progress.toInt()
        notifyUi { it.onSyncProgress(progress.toInt()) }
    }

    override fun onContact(
        id: String, name: String, phone: String, isSelf: Boolean, isGroup: Boolean, isSaved: Boolean,
    ) {
        if (wiping) return
        db.upsertContact(id, name, phone, isSelf, isGroup, isSaved)
        synchronized(changedChats) { contactsChanged = true }
        notifyChatsChanged()
    }

    override fun onChat(chatId: String, name: String, unreadCount: Long, isArchived: Boolean, lastMessageTime: Long) {
        if (wiping) return
        db.upsertChat(chatId, name, isArchived, lastMessageTime)
        notifyChatsChanged()
    }

    override fun onContactsSynced() = executor.execute { reconcileLidChats() }

    // Heal chats mistakenly keyed by a contact's LID (a live message that landed
    // before the LID→phone mapping was known). Driven off the app's own @lid chats
    // — bounded (usually none) and independent of whether the contact has a name —
    // resolving each via the bridge and folding it into its phone-JID chat.
    private fun reconcileLidChats() {
        if (connId < 0) return
        var merged = false
        for (lid in db.lidChats()) {
            val pn = Wmbridge.resolveChatId(connId, lid)
            if (pn.isEmpty() || pn == lid) continue
            if (db.mergeChat(lid, pn)) {
                merged = true
                if (activeChatId == lid) activeChatId = pn
                rekeyChatState(lid, pn)
                notifyUi { it.onChatMerged(lid, pn) }
            }
        }
        if (merged) notifyChatsChanged()
    }

    /**
     * Moves every piece of process-local per-chat state from a merged-away id to
     * its replacement. mergeChat only re-keys DB rows; anything keyed by the old
     * id here kept pointing at a chat that no longer exists (an orphan
     * notification whose tap opened a blank screen, a phantom "typing…", a
     * pagination anchor for nothing).
     */
    private fun rekeyChatState(fromId: String, toId: String) {
        historyAnchor.remove(fromId)?.let { historyAnchor.putIfAbsent(toId, it) }
        if (historyExhausted.remove(fromId)) historyExhausted.add(toId)
        if (syncAllChat == fromId) syncAllChat = toId
        if (pendingMute.remove(fromId)) pendingMute.add(toId)
        chatStates.remove(fromId)?.let { chatStates.putIfAbsent(toId, it) }
        appContext?.let { ctx -> notifyExecutor.execute { Notifications.rekey(ctx, fromId, toId) } }
        main.post {
            chatActors.remove(fromId)?.let { actors -> chatActors[toId] = actors }
            recomputeChatState(toId)
        }
    }

    override fun onMessage(
        chatId: String, msgId: String, senderId: String, text: String,
        fromMe: Boolean, timeSent: Long, isRead: Boolean, msgType: String, fileId: String,
        latitude: Double, longitude: Double,
        isHistory: Boolean, isEdited: Boolean, quotedId: String, quotedText: String,
        quotedType: String, senderName: String, isForwarded: Boolean,
    ) {
        if (wiping) return
        // a malformed edit/protocol message can carry an empty key; storing it
        // would create a row that can never be matched to a real message
        if (msgId.isEmpty()) { Log.w(TAG, "message with empty id for $chatId"); return }
        db.upsertMessage(
            MessageRow(
                msgId, chatId, senderId, text, fromMe, timeSent, isRead, msgType, fileId,
                edited = isEdited, quotedId = quotedId, quotedText = quotedText,
                quotedType = quotedType, senderName = senderName,
                forwarded = isForwarded, latitude = latitude, longitude = longitude
            )
        )
        // an edit must not reorder the chat list; only real new messages bump it
        if (!isEdited) db.bumpChat(chatId, timeSent)
        // eagerly fetch images and voice notes for LIVE messages (their media
        // URLs expire); history backfill downloads lazily when scrolled into
        // view, to avoid a download storm on initial sync
        if (!isHistory && (msgType in PICTURE_TYPES || msgType == "audio") && fileId.isNotEmpty()) {
            downloadFile(MessageRow(msgId, chatId, senderId, text, fromMe, timeSent, isRead, msgType, fileId))
        }
        // notify only for fresh, unread, incoming messages of chats not on
        // screen — and never for a muted chat
        if (!isHistory && !fromMe && !isRead && chatId != activeChatId && !db.isMuted(chatId)) {
            postMessageNotification(chatId, senderId, text, msgType, timeSent)
        }
        notifyChat(chatId)
    }

    internal fun postMessageNotification(
        chatId: String, senderId: String, text: String, msgType: String, timeSent: Long,
    ) {
        val ctx = appContext ?: return
        notifyExecutor.execute {
            // the chat may have been opened between queueing and running this
            // task (avatar fetch can block); don't (re)post for the active chat
            if (chatId == activeChatId) return@execute
            val isGroup = isGroupId(chatId)
            val chatName = db.displayName(chatId)
            val senderName = if (isGroup) db.displayName(senderId) else chatName
            val preview = messagePreview(text, msgType)
            // cached-only: see getCachedAvatarPath — a fetch here would block
            // this single notify thread on the network
            val chatAvatar = getCachedAvatarPath(chatId)
            val senderAvatar = if (isGroup) getCachedAvatarPath(senderId) else chatAvatar
            Notifications.notifyMessage(
                ctx, chatId, chatName, senderName, preview, isGroup, timeSent, chatAvatar, senderAvatar
            )
        }
    }

    // Called when a chat screen becomes active: suppress its future
    // notifications and clear any existing one. The cancel is serialized on the
    // notify executor so it runs after any post already queued for this chat.
    // Which screen instance currently claims activeChatId. Two ChatActivity
    // instances for the SAME chat can overlap (a share, or a notification
    // deep-link, while that chat is already open) and Android starts the new one
    // before stopping the old: without an owner token the departing instance's
    // closeChat() cleared the id the live one had just set, so notifications
    // started firing for the chat visibly on screen.
    @Volatile private var activeChatOwner: Any? = null

    fun openChat(chatId: String, owner: Any? = null) {
        activeChatId = chatId
        activeChatOwner = owner
        // TDLib gates real-time traffic on this: without it a private chat's
        // typing/recording actions are dropped inside TDLib, and supergroups
        // deliver no updates at all while closed.
        proto(chatId).openChat(chatId)
        // the proximity sensor is only allowed to act inside the chat that
        // owns the current voice message; tell the service to re-evaluate
        AudioPlayer.refreshServiceState()
        val ctx = appContext ?: return
        notifyExecutor.execute { Notifications.cancel(ctx, chatId) }
    }

    fun closeChat(chatId: String, owner: Any? = null) {
        // only the instance that last claimed the chat may release it
        // Before the owner check: openChat ran once for THIS screen, so its
        // close has to run too. Two screens on one chat (a share, or a
        // notification deep-link onto an open chat) otherwise left TDLib's
        // refcounted open unbalanced for the rest of the session.
        proto(chatId).closeChat(chatId)
        if (owner != null && activeChatOwner !== owner) return
        if (activeChatId == chatId) {
            activeChatId = ""
            activeChatOwner = null
        }
        AudioPlayer.refreshServiceState()
    }

    private fun messagePreview(text: String, msgType: String): String {
        val ctx = appContext ?: return text
        // runs on the notify executor, one message at a time, so a mentioned id
        // is resolved with point lookups instead of a whole-table contacts scan
        val named = if (hasMention(text)) resolveMentions(text) { db.contactName(it) } else text
        return previewLabel(ctx, msgType, named, emoji = true)
    }

    override fun onMessageDeleted(chatId: String, msgId: String) {
        if (wiping) return
        // a malformed revoke can carry an empty key; a DELETE on "" would only
        // ever match a phantom row, so refuse it outright
        if (msgId.isEmpty()) { Log.w(TAG, "revoke with empty message id for $chatId"); return }
        db.deleteMessage(chatId, msgId)
        notifyChat(chatId)
    }

    override fun onReaction(chatId: String, msgId: String, senderId: String, emoji: String) {
        if (wiping) return
        if (emoji.isEmpty()) {
            db.deleteReaction(chatId, msgId, senderId)
        } else {
            db.upsertReaction(chatId, msgId, senderId, emoji)
        }
        // the chat list previews a reaction on the newest message (see
        // Db.chats), so a reaction changes that list too, not just the chat
        notifyChatRow(chatId, msgId)
    }

    override fun onFileDownloaded(chatId: String, msgId: String, filePath: String, status: Long) {
        if (wiping) return
        var target = chatId
        var updated = db.setFileState(chatId, msgId, filePath, status.toInt())
        if (updated == 0) {
            // 0 rows can mean "the chat was deleted mid-download" OR "its rows
            // were re-keyed by the LID→phone merge while this ran". Re-resolve
            // by message id before concluding the file is orphaned: deleting it
            // here threw away perfectly good media and left the migrated row
            // stuck at file_status=1 (a permanent spinner nothing retries).
            db.chatOfMessage(msgId)?.let { moved ->
                target = moved
                updated = db.setFileState(moved, msgId, filePath, status.toInt())
            }
        }
        // genuinely orphaned: the freshly written file would linger on disk with
        // no bubble to reach it, so drop it now.
        if (updated == 0 && filePath.isNotEmpty()) runCatching { java.io.File(filePath).delete() }
        // the transfer is over: release the in-flight claim downloadFile took,
        // under both the id the request was made with and the merged one
        downloading.remove("$chatId/$msgId")
        downloading.remove("$target/$msgId")
        // a failed download is otherwise invisible (the bubble just stays
        // undownloaded); tell the user — but only for downloads they asked
        // for, not the automatic ones (expired history media fails in bulk).
        // Keyed by the id the request was MADE with, plus the merged one.
        val requested = userRequestedDownloads.remove("$chatId/$msgId") ||
            userRequestedDownloads.remove("$target/$msgId")
        if (status.toInt() == 3 && requested) toastUi(R.string.download_failed)
        if (autoPlayKey == "$chatId/$msgId" || autoPlayKey == "$target/$msgId") {
            autoPlayKey = null
            if (status.toInt() == 2 && filePath.isNotEmpty()) {
                main.post { AudioPlayer.play(filePath, target, msgId) }
            }
        }
        notifyChatRow(target, msgId)
    }

    override fun onDownloadProgress(chatId: String, msgId: String, pct: Long) {
        // fires on the download thread; hop to main and let the open chat update
        // just the affected row (no DB write, no reload)
        notifyUi { it.onDownloadProgress(chatId, msgId, pct.toInt()) }
    }

    override fun onMessageRead(chatId: String, msgId: String) {
        if (wiping) return
        db.markMessageRead(chatId, msgId)
        notifyChatRow(chatId, msgId)
    }

    override fun onMessagePlayed(chatId: String, msgId: String) {
        if (wiping) return
        // our sent voice note was played by the recipient (or a received one on
        // another device); mark it played so the UI can reflect it
        db.setPlayed(chatId, msgId)
        notifyChatRow(chatId, msgId)
    }

    override fun onChatReadSelf(chatId: String) {
        if (wiping) return
        // read on another device: clear unread locally and drop the notification
        db.markChatRead(chatId)
        appContext?.let { ctx -> notifyExecutor.execute { Notifications.cancel(ctx, chatId) } }
        notifyChat(chatId)
    }

    // mute changed on this or another device (app-state sync): mirror it locally
    // and clear any pending marker — this event is the confirmation reconcile
    // was waiting for (whether from our own toggle or another device).
    override fun onMute(chatId: String, muted: Boolean) {
        if (wiping) return
        pendingMute.remove(chatId)
        db.setMuted(chatId, muted)
        notifyChatsChanged()
    }

    // Separator for the composite "chatId + userId" keys below. Written as an
    // escape, NOT as a literal control character: two raw NUL bytes in this file
    // used to make grep classify the largest Kotlin source in the project as
    // binary and skip it, so `grep -r` over app/src silently missed every
    // reference that lives here.
    private const val KEY_SEP = "\u0000"

    // per-actor expiry timers, keyed by "chatId<KEY_SEP>userId"; main-thread only
    private val stateClearers = ConcurrentHashMap<String, Runnable>()

    override fun onChatState(chatId: String, userId: String, state: String) {
        // resolve the actor's name here (off the main thread); only groups need
        // it, so skip the DB lookup for 1:1 chats
        val name = if (state != "paused" && isGroupId(chatId)) db.displayName(userId) else ""
        main.post {
            val key = chatId + KEY_SEP + userId
            stateClearers.remove(key)?.let { main.removeCallbacks(it) }
            val actors = chatActors.getOrPut(chatId) { LinkedHashMap() }
            if (state == "paused") {
                actors.remove(userId)
            } else {
                actors[userId] = ActorState(state, name)
                // WhatsApp does not always send "paused"; expire each actor on
                // our own so one dropped stop can't pin the indicator forever
                val clearer = Runnable {
                    stateClearers.remove(key)
                    chatActors[chatId]?.remove(userId)
                    recomputeChatState(chatId)
                }
                stateClearers[key] = clearer
                main.postDelayed(clearer, 15000)
            }
            recomputeChatState(chatId)
        }
    }

    // Fold the per-actor states into the single aggregate the UI reads, then
    // notify listeners. "recording" only wins when every active actor is
    // recording; otherwise "typing" covers the mixed case. Runs on the main
    // thread (from onChatState's post or an expiry clearer).
    private fun recomputeChatState(chatId: String) {
        val actors = chatActors[chatId]
        if (actors.isNullOrEmpty()) {
            chatActors.remove(chatId)
            chatStates.remove(chatId)
            for (l in listeners) l.onChatState(chatId, "paused")
        } else {
            val state = if (actors.values.all { it.state == "recording" }) "recording" else "typing"
            val names = actors.values.mapNotNull { it.name.ifEmpty { null } }
            chatStates[chatId] = ChatStateInfo(state, names.joinToString(", "), names.size)
            for (l in listeners) l.onChatState(chatId, state)
        }
        // Deliberately NOT notifyChatsChanged(): typing state is not in the
        // database, so re-running the chat-list query answers a question nothing
        // asked. Every actor start, stop and 15s expiry used to rebuild the whole
        // list — five subqueries and a join per row — only for the caller to
        // stamp the state back in from `chatStates` afterwards. onChatState above
        // is the signal; the list re-stamps the rows it already holds.
    }

    override fun onPresence(userId: String, isOnline: Boolean, lastSeenTime: Long) {
        online[userId] = isOnline
        // only record a real last-seen; never fabricate one for contacts who
        // hide it (they send an offline presence with lastSeenTime == 0)
        if (lastSeenTime > 0) {
            lastSeen[userId] = lastSeenTime
        }
        notifyUi { it.onPresence(userId, isOnline, lastSeenOf(userId)) }
    }

    override fun onLog(level: Long, message: String) {
        when (level) {
            Wmbridge.LogError -> Log.e(TAG, message)
            Wmbridge.LogWarning -> Log.w(TAG, message)
            Wmbridge.LogDebug -> Log.d(TAG, message)
            else -> Log.i(TAG, message)
        }
    }

    // --- hooks for the Telegram client (Tg) -----------------------------------
    // Tg mirrors its data into the shared Db and reports through the same
    // listener plumbing. Only events that need a NAME of their own live here;
    // the rest of Tg calls the notification helpers above directly, which are
    // `internal` for exactly that. A second set of pure-rename forwarders
    // (postChatState -> onChatState, postSeekResult -> notifySeek, …) meant
    // every new Telegram event needed three declarations to reach the UI.

    internal fun notifyContactsChangedInternal() {
        synchronized(changedChats) { contactsChanged = true }
        notifyChatsChanged()
    }

    internal fun notifyTgAuth(state: String, message: String) =
        notifyUi { it.onTgAuth(state, message) }

    internal fun notifyTgState() = notifyUi { it.onTgStateChanged() }

    internal fun postDownloadProgress(chatId: String, msgId: String, pct: Int) =
        notifyUi { it.onDownloadProgress(chatId, msgId, pct) }

    internal fun postChatExportProgress(chatId: String, fetched: Int) =
        notifyUi { it.onChatExportProgress(chatId, fetched) }

    internal fun postChatExportDone(chatId: String, messages: Int, complete: Boolean, success: Boolean) =
        notifyUi { it.onChatExportDone(chatId, messages, complete, success) }
}
