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

object Bridge : EventListener {

    interface UiListener {
        fun onChatsChanged() {}
        fun onContactsChanged() {}
        fun onMessagesChanged(chatId: String, rowIds: Set<String>? = null) {}
        fun onChatMerged(fromId: String, toId: String) {}
        /** There used to be a separate callback per protocol, so a screen
         *  listening to all of them said the same thing three times. */
        fun onAccountState(proto: String, state: String) {}
        fun onQrCode(proto: String, code: String) {}
        fun onPairCode(code: String) {}
        fun onPairError(proto: String, code: String) {}
        fun onSyncProgress(progress: Int) {}
        fun onDownloadProgress(chatId: String, msgId: String, pct: Int) {}
        fun onChatState(chatId: String, state: String) {}
        fun onPresence(userId: String, isOnline: Boolean, lastSeen: Long) {}
        fun onChatSyncProgress(chatId: String, progress: Int) {}
        fun onChatExportProgress(chatId: String, fetched: Int) {}
        fun onChatExportDone(chatId: String, messages: Int, complete: Boolean, success: Boolean) {}
        fun onSeekResult(chatId: String, msgId: String, found: Boolean) {}
        fun onTgAuth(state: String, message: String) {}
    }

    private const val TAG = "UniChat"

    @Volatile private var connId: Long = -1
    @Volatile private var appContext: Context? = null
    @Volatile var activeChatId: String = ""
    // @Volatile like everything else here: it is assigned on the warm-up thread
    // while Tg and Signal reach it from their own executors, and neither gates
    // on connId — the volatile that publishes the rest of init's writes.
    @Volatile lateinit var db: Db
        private set
    @Volatile var state: String = "disconnected"
        private set
    @Volatile var syncProgress: Int = -1
        private set

    private val executor = Executors.newSingleThreadExecutor()
    // Signal's transport blocks in place on the network, where the WhatsApp and
    // Telegram ones hand off to workers of their own. Sharing [executor] meant
    // one Signal send to an unreachable recipient stalled every WhatsApp and
    // Telegram operation queued behind it for the whole HTTP timeout.
    private val sgExecutor = Executors.newSingleThreadExecutor()
    private val mediaExecutor = Executors.newFixedThreadPool(2)
    private val notifyExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<UiListener>()
    // hash sets, not CopyOnWriteArraySet: these are add/remove-heavy per bind
    // and per download, where copy-on-write is O(n) per mutation
    private val downloading: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val historyExhausted = CopyOnWriteArraySet<String>()
    private const val HISTORY_PAGE = 100L
    private const val HISTORY_TIMEOUT_MS = 30_000L

    val historyTimeoutMs: Long get() = HISTORY_TIMEOUT_MS
    private const val INITIAL_HISTORY_MIN = 60

    private data class ChatStateInfo(val state: String, val actorName: String, val actorCount: Int)
    private val chatStates = ConcurrentHashMap<String, ChatStateInfo>()
    private data class ActorState(val state: String, val name: String)
    private val chatActors = HashMap<String, LinkedHashMap<String, ActorState>>()
    private val online = ConcurrentHashMap<String, Boolean>()
    private val lastSeen = ConcurrentHashMap<String, Long>()

    fun chatState(chatId: String): String? = chatStates[chatId]?.state
    fun chatStateName(chatId: String): String? = chatStates[chatId]?.actorName
    fun chatStateActorCount(chatId: String): Int = chatStates[chatId]?.actorCount ?: 0
    fun isOnline(userId: String): Boolean = online[userId] == true
    fun lastSeenOf(userId: String): Long = lastSeen[userId] ?: 0L

    fun lastSeenApproxOf(userId: String): Int = lastSeenApprox[userId] ?: 0

    private val lastSeenApprox = ConcurrentHashMap<String, Int>()

    internal fun postPresenceApprox(userId: String, labelRes: Int) {
        if (labelRes == 0) lastSeenApprox.remove(userId) else lastSeenApprox[userId] = labelRes
        notifyUi { it.onPresence(userId, isOnline(userId), lastSeenOf(userId)) }
    }

    private val changedChats = HashMap<String, MutableSet<String>?>()
    private var chatsChanged = false
    private var contactsChanged = false
    private var notifyPending = false

    // Overlaps init's disk work (Go sqlstore open, migrations, a device-store
    // query) with process startup. init() is @Synchronized, so the first
    // Activity that calls it just blocks until this finishes.
    fun warmUp(context: Context) {
        if (connId >= 0 || warmingUp) return
        warmingUp = true
        val ctx = context.applicationContext
        Thread({ init(ctx) }, "bridge-init").start()
    }

    @Volatile private var warmingUp = false
    private var pendingSwept = false

    @Synchronized
    fun init(context: Context): Boolean {
        if (connId >= 0) return true
        val appContext = context.applicationContext
        this.appContext = appContext
        AudioPlayer.init(appContext)
        Notifications.ensureChannel(appContext)
        db = Db(appContext)
        db.clearStaleDownloads()
        // Once per process: init() re-runs its whole body on every caller while
        // the WhatsApp store refuses to open, and a second sweep would flag a
        // Signal or Telegram send in flight right then.
        if (!pendingSwept) {
            pendingSwept = true
            db.failStalePending()
        }
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

    @Volatile private var autoPlayKey: String? = null

    private fun chainNextVoice(chatId: String, finishedMsgId: String) {
        if (chatId.isEmpty()) { main.post { AudioPlayer.resetRoute() }; return }
        if (connId < 0) return
        executor.execute {
            // the DB, not the loaded rows: the next voice message can lie
            // outside the last 500 the chat has loaded
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

    fun skipToNextVoice() {
        val msgId = AudioPlayer.currentMsgId
        if (msgId.isEmpty()) return
        chainNextVoice(AudioPlayer.currentChatId, msgId)
    }

    fun hasSession(): Boolean = connId >= 0 && Wmbridge.hasSession(connId)

    fun hasAnySession(): Boolean = Accounts.ALL.any { it.isLinked() }

    private fun isTg(chatId: String) = Tg.isTgId(chatId)

    private interface Protocol {
        fun sendText(
            chatId: String, msgId: String, text: String, quoted: MessageRow?,
            mentions: List<Mention>,
        ): String
        fun sendImage(
            chatId: String, msgId: String, path: String, caption: String, quoted: MessageRow?,
            viewOnce: Boolean,
        ): String
        fun sendVideo(
            chatId: String, msgId: String, path: String, caption: String, quoted: MessageRow?,
            viewOnce: Boolean,
        ): String
        fun sendAudio(
            chatId: String, msgId: String, path: String, seconds: Int, quoted: MessageRow?,
            waveform: ByteArray, viewOnce: Boolean,
        ): String
        fun sendDocument(
            chatId: String, msgId: String, path: String, name: String, mime: String,
            quoted: MessageRow?,
        ): String
        fun sendLocation(chatId: String, msgId: String, latitude: Double, longitude: Double): String
        fun sendContact(chatId: String, msgId: String, name: String, numbers: List<String>): String

        fun newMessageId(): String

        /** False where returning only means queued and the confirmation comes
         *  later (Telegram's updateMessageSendSucceeded). */
        val ackOnSend: Boolean

        /** whatsmeow sits on a send for its whole ack timeout and TDLib queues
         *  one for as long as the phone is offline, so neither reports a send
         *  that cannot possibly go out. */
        val connected: Boolean

        fun edit(msg: MessageRow, newText: String, mentions: List<Mention>): Boolean

        fun canEditCaption(msg: MessageRow): Boolean = true
        val editWindowSeconds: Long
        val revokeWindowSeconds: Long

        fun deleteForEveryone(chatId: String, msgId: String)
        fun deleteForMe(chatId: String, msgId: String)
        /** recent must be read before the local rows go: Signal identifies the
         *  conversation to its other devices by the messages it ended with. */
        fun deleteChat(chatId: String, deleteMedia: Boolean, recent: List<MessageRow>)
        fun react(msg: MessageRow, emoji: String)

        fun setMuted(chatId: String, muted: Boolean)
        fun markChatRead(chatId: String)
        fun reportVisible(chatId: String, msgIds: List<String>) {}
        fun markVoicePlayed(msg: MessageRow)
        fun openChat(chatId: String) {}
        fun closeChat(chatId: String) {}
        fun subscribePresence(userId: String) {}

        fun requestInitialHistory(chatId: String)
        fun requestHistoryPage(chatId: String)
        fun isHistoryExhausted(chatId: String): Boolean
        fun seekMessage(chatId: String, target: String, from: MessageRow, maxPages: Int)
        fun syncAllHistory(chatId: String): Boolean
        fun syncAllProgress(chatId: String): Int
        fun exportChat(chatId: String, uri: android.net.Uri): Boolean
        fun exportProgress(chatId: String): Int

        fun startDownload(msg: MessageRow): Boolean
        fun avatarPath(chatId: String, big: Boolean, cachedOnly: Boolean): String

        val consumesStagingInput: Boolean

        // WhatsApp is end-to-end encrypted and Signal has no such query, so
        // those chats are searched locally, over what has been synced.
        fun searchServer(chatId: String, query: String, fromMessageId: Long): Tg.SearchPage? = null
        fun searchContext(chatId: String, msgId: String): List<MessageRow> = emptyList()
        fun searchSlice(chatId: String, msgId: String, newer: Boolean): List<MessageRow> = emptyList()
        fun chatPhotos(chatId: String, msgId: String, newer: Boolean?): List<MessageRow> = emptyList()
        fun searchMedia(chatId: String, msgId: String): String = ""

        /** Null means the server keeps no such list: the stored reactions are
         *  all there is. */
        fun reactionSenders(msg: MessageRow): List<Pair<String, String>>? = null

        fun viewOnceKinds(chatId: String): Set<String>
    }

    private fun protoExecutor(chatId: String) =
        if (Signal.isSgId(chatId)) sgExecutor else executor

    private val sgExporting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val WA_VIEW_ONCE = setOf("image", "video", "audio")
    private val TG_VIEW_ONCE = setOf("image", "video")

    private fun proto(chatId: String): Protocol = when (Accounts.ofChat(chatId).proto) {
        ProtoPicker.TG -> TgTransport
        ProtoPicker.SG -> SgTransport
        else -> WaTransport
    }

    // Avatars and history are not wired for Signal: those members are
    // deliberate no-ops, so a chat degrades instead of failing.
    private object SgTransport : Protocol {
        override fun sendText(
            chatId: String, msgId: String, text: String, quoted: MessageRow?,
            mentions: List<Mention>,
        ): String = Signal.sendText(chatId, msgId, text, quoted)

        // Signal has no view-once, and quoting is not wired up yet, so both are
        // ignored rather than refused: sending without the decoration beats
        // dropping the message.
        override fun sendImage(
            chatId: String, msgId: String, path: String, caption: String, quoted: MessageRow?,
            viewOnce: Boolean,
        ): String =
            Signal.sendAttachment(chatId, msgId, path, caption, mimeOfPath(path, "image/jpeg"))
        override fun sendVideo(
            chatId: String, msgId: String, path: String, caption: String, quoted: MessageRow?,
            viewOnce: Boolean,
        ): String =
            Signal.sendAttachment(chatId, msgId, path, caption, mimeOfPath(path, "video/mp4"))
        override fun sendAudio(
            chatId: String, msgId: String, path: String, seconds: Int, quoted: MessageRow?,
            waveform: ByteArray, viewOnce: Boolean,
        ): String = Signal.sendAttachment(
            chatId, msgId, path, "", mimeOfPath(path, "audio/aac"), voiceNote = true
        )
        override fun sendDocument(
            chatId: String, msgId: String, path: String, name: String, mime: String,
            quoted: MessageRow?,
        ): String = Signal.sendAttachment(
            chatId, msgId, path, "", mime.ifEmpty { "application/octet-stream" }
        )
        // Signal carries no location message, so it goes as the map link the
        // Signal app itself falls back to, in the one form signal.go parses
        // back into coordinates.
        override fun sendLocation(
            chatId: String, msgId: String, latitude: Double, longitude: Double,
        ): String = Signal.sendLocation(chatId, msgId, latitude, longitude)

        override fun sendContact(
            chatId: String, msgId: String, name: String, numbers: List<String>,
        ): String = Signal.sendContact(chatId, msgId, name, numbers)

        override val connected get() = Signal.state != "disconnected"

        // A Signal message IS its send timestamp, so two sends inside the same
        // millisecond would share one row.
        override fun newMessageId(): String {
            while (true) {
                val prev = sgLastStamp.get()
                val next = maxOf(System.currentTimeMillis(), prev + 1)
                if (sgLastStamp.compareAndSet(prev, next)) return next.toString()
            }
        }

        override val ackOnSend = true

        override fun edit(msg: MessageRow, newText: String, mentions: List<Mention>) =
            Signal.edit(msg.chatId, msg.id, newText, msg.fileId)

        override fun canEditCaption(msg: MessageRow) =
            msg.id.toLongOrNull() != null
        // Signal sets no server-side deadline on either, unlike WhatsApp's 15
        // minutes and 48 hours, so the menu entries stay available.
        override val editWindowSeconds = Long.MAX_VALUE
        override val revokeWindowSeconds = Long.MAX_VALUE

        override fun deleteForEveryone(chatId: String, msgId: String) = Signal.delete(chatId, msgId)
        // No remote call: dropping the local row is the whole of "delete for me".
        override fun deleteForMe(chatId: String, msgId: String) {}

        override fun deleteChat(chatId: String, deleteMedia: Boolean, recent: List<MessageRow>) =
            Signal.deleteChat(chatId, recent)
        override fun react(msg: MessageRow, emoji: String) = Signal.react(msg, emoji)

        // Signal has no server-side mute, but the flag still has to be written:
        // the Wa and Tg transports are what persist it, so an empty body here
        // meant muting a Signal chat did nothing at all.
        override fun setMuted(chatId: String, muted: Boolean) {
            db.setMuted(chatId, muted)
        }
        // Clearing is_read locally is what stops this repeating: ChatActivity
        // calls it on every messages-changed burst, so without it the unread
        // badge never cleared and the same receipt went out again each time.
        override fun markChatRead(chatId: String) = Signal.markChatRead(chatId)
        override fun markVoicePlayed(msg: MessageRow) {}
        override fun openChat(chatId: String) {}
        override fun closeChat(chatId: String) = Signal.setTyping(chatId, false)

        override fun requestInitialHistory(chatId: String) {}
        override fun requestHistoryPage(chatId: String) {}
        // Signal hands a newly registered account no history at all, so there
        // is never a further page to fetch — saying so stops the chat asking.
        override fun isHistoryExhausted(chatId: String) = true
        override fun seekMessage(chatId: String, target: String, from: MessageRow, maxPages: Int) {}
        override fun syncAllHistory(chatId: String) = false
        // -1, not 0: both of these mean "no operation running". Returning 0 read
        // as a live export sitting at zero messages, so every Signal chat opened
        // with "Exporting… 0 messages fetched" under its title.
        override fun syncAllProgress(chatId: String) = -1
        // Signal keeps no server-side history to walk, so the local store is
        // all of it.
        override fun exportChat(chatId: String, uri: android.net.Uri): Boolean {
            val ctx = appContext ?: return false
            // One at a time per chat, like the other two: a second run wrote the
            // same file and released the caller's write grant under the first.
            if (!sgExporting.add(chatId)) return false
            mediaExecutor.execute {
                var messages = 0
                // Throwable, not Exception: the whole history goes through one
                // list, so OutOfMemoryError is the likeliest failure and an
                // Error would otherwise leave the UI waiting for a completion
                // that never comes.
                val success = try {
                    val sorted = db.messages(chatId, Int.MAX_VALUE).sortedBy { it.timeSent }
                    messages = sorted.size
                    ChatExporter.write(ctx, db, chatId, uri, sorted)
                    true
                } catch (e: Throwable) {
                    Log.w(TAG, "signal export write failed: $e")
                    false
                }
                sgExporting.remove(chatId)
                releaseExportUri(uri)
                notifyUi { it.onChatExportDone(chatId, messages, true, success) }
            }
            return true
        }

        // Nothing to report: the export above is a local read, so it is done by
        // the time anything could ask.
        override fun exportProgress(chatId: String) = -1

        override fun startDownload(msg: MessageRow) = Signal.startDownload(msg)
        override fun avatarPath(chatId: String, big: Boolean, cachedOnly: Boolean) = ""

        override val consumesStagingInput = false

        // Signal has no view-once at all, so the option must not be offered.
        override fun viewOnceKinds(chatId: String) = emptySet<String>()
    }


    private object WaTransport : Protocol {
        override fun sendText(
            chatId: String, msgId: String, text: String, quoted: MessageRow?,
            mentions: List<Mention>,
        ): String {
            val (body, jids) = waMentionText(text, mentions)
            val mentioned = jids.joinToString(",")
            if (quoted == null) {
                return Wmbridge.sendTextMessage(connId, chatId, msgId, body, mentioned)
            }
            return Wmbridge.sendTextReply(
                connId, chatId, msgId, body, quoted.id, quotedPreview(quoted), quoted.senderId,
                mentioned
            )
        }

        override fun sendImage(
            chatId: String, msgId: String, path: String, caption: String, quoted: MessageRow?,
            viewOnce: Boolean,
        ): String {
            val (qid, qtext, qsender) = quoteArgs(quoted)
            return Wmbridge.sendImageMessage(
                connId, chatId, msgId, path, caption, qid, qtext, qsender, viewOnce
            )
        }

        override fun sendVideo(
            chatId: String, msgId: String, path: String, caption: String, quoted: MessageRow?,
            viewOnce: Boolean,
        ): String {
            val (qid, qtext, qsender) = quoteArgs(quoted)
            return Wmbridge.sendVideoMessage(
                connId, chatId, msgId, path, caption, qid, qtext, qsender, viewOnce
            )
        }

        override fun sendAudio(
            chatId: String, msgId: String, path: String, seconds: Int, quoted: MessageRow?,
            waveform: ByteArray, viewOnce: Boolean,
        ): String {
            val (qid, qtext, qsender) = quoteArgs(quoted)
            return Wmbridge.sendAudioMessage(
                connId, chatId, msgId, path, seconds.toLong(), qid, qtext, qsender, waveform,
                viewOnce
            )
        }

        override fun sendDocument(
            chatId: String, msgId: String, path: String, name: String, mime: String,
            quoted: MessageRow?,
        ): String {
            val (qid, qtext, qsender) = quoteArgs(quoted)
            return Wmbridge.sendDocumentMessage(
                connId, chatId, msgId, path, name, mime, qid, qtext, qsender
            )
        }

        override fun sendLocation(
            chatId: String, msgId: String, latitude: Double, longitude: Double,
        ): String = Wmbridge.sendLocation(connId, chatId, msgId, latitude, longitude)

        override fun sendContact(
            chatId: String, msgId: String, name: String, numbers: List<String>,
        ): String = Wmbridge.sendContactMessage(
            connId, chatId, msgId, name, PhoneBook.vcard(name, numbers)
        )

        override val connected get() = state != "disconnected"

        override fun newMessageId(): String =
            if (connId < 0) "" else Wmbridge.newMessageId(connId)

        override val ackOnSend = true

        override fun edit(msg: MessageRow, newText: String, mentions: List<Mention>): Boolean {
            val quoted = msg.quotedId.takeIf { it.isNotEmpty() }
                ?.let { db.messagesByIds(msg.chatId, listOf(it)).firstOrNull() }
            val (qid, qtext, qsender) = quoteArgs(quoted)
            val (body, jids) = waMentionText(newText, mentions)
            val byDigits = LinkedHashMap<String, String>()
            for (m in storedMentions(body) { db.contactName(it) != null }) {
                byDigits[m.id.substringBefore('@')] = m.id
            }
            for (id in jids) byDigits[id.substringBefore('@')] = id
            return Wmbridge.editMessage(
                connId, msg.chatId, msg.id, body, msg.timeSent, msg.fileId,
                qid, qtext, qsender, byDigits.values.joinToString(","),
            )
        }

        override fun canEditCaption(msg: MessageRow) =
            msg.fileId.isEmpty() || Wmbridge.canEditMedia(msg.fileId)

        override val editWindowSeconds: Long get() = waEditWindowSeconds
        // whatsmeow exposes no constant for the revoke window (only the edit
        // one), so WhatsApp's 60 hours is fixed here.
        override val revokeWindowSeconds: Long = 60L * 60 * 60

        override fun deleteForEveryone(chatId: String, msgId: String) {
            if (!Wmbridge.deleteMessageForEveryone(connId, chatId, msgId)) Log.w(TAG, "revoke failed")
        }

        override fun deleteForMe(chatId: String, msgId: String) {}

        override fun deleteChat(chatId: String, deleteMedia: Boolean, recent: List<MessageRow>) {
            val last = recent.firstOrNull()
            if (!Wmbridge.deleteChat(
                    connId, chatId, last?.id ?: "", last?.fromMe ?: false,
                    last?.senderId ?: "", last?.timeSent ?: 0L, deleteMedia
                )
            ) {
                Log.w(TAG, "delete chat failed")
                toastUi(R.string.delete_chat_failed)
            }
        }

        override fun react(msg: MessageRow, emoji: String) {
            if (!Wmbridge.sendReaction(connId, msg.chatId, msg.id, msg.senderId, msg.fromMe, emoji)) {
                Log.w(TAG, "reaction failed for chat ${msg.chatId}")
            }
        }

        override fun setMuted(chatId: String, muted: Boolean) = setMutedWa(chatId, muted)

        override fun markChatRead(chatId: String) = markChatReadWa(chatId)

        override fun markVoicePlayed(msg: MessageRow) {
            Wmbridge.markVoicePlayed(connId, msg.chatId, msg.senderId, msg.id)
        }

        override fun subscribePresence(userId: String) {
            executor.execute { Wmbridge.subscribePresence(connId, userId) }
        }

        // Contact cards stored bodyless by older builds: too recent for the
        // history walk (the phone skips the already-synced stretch), so ask the
        // phone to re-send each one; the answer arrives as a live message and
        // refills the row. Once per chat per app run — a phone that won't
        // answer must not be re-asked on every open. Executor-confined.
        private val contactSweepDone = HashSet<String>()

        override fun requestInitialHistory(chatId: String) {
            executor.execute {
                if (db.messageCount(chatId) < INITIAL_HISTORY_MIN) requestHistoryPageWa(chatId)
                if (contactSweepDone.add(chatId)) {
                    for ((msgId, senderId) in db.emptyContactSenders(chatId, 5)) {
                        resendPending.add(msgId)
                        Wmbridge.requestMessageResend(connId, chatId, senderId, msgId)
                    }
                }
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

        override val consumesStagingInput: Boolean = true

        override fun viewOnceKinds(chatId: String) = WA_VIEW_ONCE
    }

    private object TgTransport : Protocol {
        override fun sendText(
            chatId: String, msgId: String, text: String, quoted: MessageRow?,
            mentions: List<Mention>,
        ): String = Tg.sendText(chatId, text, quoted?.id ?: "", mentions)

        override fun sendImage(
            chatId: String, msgId: String, path: String, caption: String, quoted: MessageRow?,
            viewOnce: Boolean,
        ): String = Tg.sendImage(chatId, path, caption, quoted?.id ?: "", viewOnce)

        override fun sendVideo(
            chatId: String, msgId: String, path: String, caption: String, quoted: MessageRow?,
            viewOnce: Boolean,
        ): String = Tg.sendVideo(chatId, path, caption, quoted?.id ?: "", viewOnce)

        // viewOnce ignored: TDLib takes a self-destruct only on photo and video,
        // so viewOnceSupported never offers it for a Telegram voice note
        override fun sendAudio(
            chatId: String, msgId: String, path: String, seconds: Int, quoted: MessageRow?,
            waveform: ByteArray, viewOnce: Boolean,
        ): String = Tg.sendAudio(chatId, path, seconds, quoted?.id ?: "", waveform)

        override fun sendDocument(
            chatId: String, msgId: String, path: String, name: String, mime: String,
            quoted: MessageRow?,
        ): String = Tg.sendDocument(chatId, path, name, quoted?.id ?: "")

        override fun sendLocation(
            chatId: String, msgId: String, latitude: Double, longitude: Double,
        ): String = Tg.sendLocation(chatId, latitude, longitude)

        override fun sendContact(
            chatId: String, msgId: String, name: String, numbers: List<String>,
        ): String = Tg.sendContact(chatId, name, numbers)

        override fun newMessageId(): String = ""
        override val ackOnSend = false
        // "connecting" covers TDLib's connectionStateUpdating, which is where it
        // sits for a while after every cold start with a perfectly good socket.
        override val connected get() = Tg.state != "disconnected"

        override fun edit(msg: MessageRow, newText: String, mentions: List<Mention>): Boolean =
            if (msg.msgType == "") Tg.editMessageText(msg.chatId, msg.id, newText, mentions)
            else Tg.editMessageCaption(msg.chatId, msg.id, newText, mentions)

        override val editWindowSeconds: Long = 48L * 60 * 60
        override val revokeWindowSeconds: Long = 48L * 60 * 60

        override fun deleteForEveryone(chatId: String, msgId: String) =
            Tg.deleteMessages(chatId, listOf(msgId), revoke = true)

        // deleted server-side for this account too, or the message would simply
        // come back with the next history fetch
        override fun deleteForMe(chatId: String, msgId: String) =
            Tg.deleteMessages(chatId, listOf(msgId), revoke = false)

        override fun deleteChat(chatId: String, deleteMedia: Boolean, recent: List<MessageRow>) {
            Tg.deleteChatAsync(chatId)
        }

        override fun react(msg: MessageRow, emoji: String) {
            Tg.sendReaction(msg.chatId, msg.id, emoji)
        }

        override fun setMuted(chatId: String, muted: Boolean) = Tg.setMuted(chatId, muted)
        override fun markChatRead(chatId: String) = Tg.markChatRead(chatId)
        override fun reportVisible(chatId: String, msgIds: List<String>) =
            Tg.reportVisible(chatId, msgIds)
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

        override fun searchServer(chatId: String, query: String, fromMessageId: Long) =
            Tg.searchChat(chatId, query, fromMessageId)
        override fun searchContext(chatId: String, msgId: String) =
            Tg.contextWindow(chatId, msgId)
        override fun searchSlice(chatId: String, msgId: String, newer: Boolean) =
            Tg.historySlice(chatId, msgId, newer)
        override fun chatPhotos(chatId: String, msgId: String, newer: Boolean?) =
            Tg.chatPhotos(chatId, msgId, newer)
        override fun searchMedia(chatId: String, msgId: String) = Tg.downloadNow(chatId, msgId)

        override fun reactionSenders(msg: MessageRow) = Tg.reactionSenders(msg.chatId, msg.id)

        // TDLib takes a self-destruct on photo and video only, and on neither in
        // a group or the account's own chat — where the server refuses the send
        // it had just been offered.
        override fun viewOnceKinds(chatId: String): Set<String> =
            if (isGroupId(chatId) || isTgSelfChat(chatId)) emptySet() else TG_VIEW_ONCE
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

    fun disconnect() = executor.execute {
        if (connId >= 0) Wmbridge.disconnect(connId)
    }

    fun connect() = executor.execute {
        // Guarded here rather than at each call site: MainActivity, ShareActivity
        // and WmService all reconnect on their own, so a paused account would
        // come straight back on the next screen change.
        val ctx = appContext
        if (ctx != null && !Prefs.protoEnabled(ctx, ProtoPicker.WA)) return@execute
        if (state != "connected") Wmbridge.connect(connId)
    }

    fun startQrLogin() = executor.execute { Wmbridge.startLogin(connId) }

    fun stopLogin() = executor.execute { Wmbridge.stopLogin(connId) }

    // Runs on its own thread: it may block for several seconds while the
    // login socket reconnects, and the shared executor must stay free.
    fun requestPairCode(phone: String) = Thread {
        Wmbridge.requestPairCode(connId, phone)
    }.start()

    // Never a transport's thread: a protocol's send executor is
    // single-threaded, so one send stuck offline for its whole timeout held up
    // the NEXT message's bubble for just as long.
    private val stageExecutor = Executors.newSingleThreadExecutor()
    private val sgLastStamp = java.util.concurrent.atomic.AtomicLong()
    private val localIdSeq = java.util.concurrent.atomic.AtomicLong()

    private const val LOCAL_ID = "local:"

    // The run stamp keeps two runs' ids apart: the counter restarts at 1,
    // staging REPLACEs by (chat, id), and a bare counter handed a new send the
    // key of a leftover unsent row — deleting it off the screen.
    private val localIdRun = java.lang.Long.toString(System.currentTimeMillis(), 36)

    private fun mintId(chatId: String): String = proto(chatId).newMessageId()
        .ifEmpty { LOCAL_ID + localIdRun + "-" + localIdSeq.incrementAndGet() }

    private fun wireId(msgId: String) = if (msgId.startsWith(LOCAL_ID)) "" else msgId

    private fun stagedTime(chatId: String, msgId: String): Long =
        if (Signal.isSgId(chatId)) (msgId.toLongOrNull() ?: 0L) / 1000
        else System.currentTimeMillis() / 1000

    private fun stage(
        chatId: String, msgId: String, text: String, msgType: String = "",
        filePath: String = "", quoted: MessageRow? = null,
        latitude: Double = 0.0, longitude: Double = 0.0,
    ): MessageRow {
        val row = MessageRow(
            id = msgId, chatId = chatId, senderId = selfIdOf(chatId), text = text,
            fromMe = true, timeSent = stagedTime(chatId, msgId), isRead = false,
            msgType = msgType, filePath = filePath,
            fileStatus = if (filePath.isEmpty()) 0 else 2,
            quotedId = quoted?.id ?: "", quotedText = quoted?.let { quotedPreview(it) } ?: "",
            quotedType = quoted?.msgType ?: "",
            latitude = latitude, longitude = longitude, sendPending = true,
        )
        db.stageOutgoing(row)
        // Without this the chat list stays ordered as if nothing was sent.
        db.bumpChat(chatId, row.timeSent)
        sendQueued.add(chatId + KEY_SEP + msgId)
        armSendWatchdog(row)
        notifyChat(chatId)
        notifyChatsChanged()
        return row
    }

    // Nothing guarantees a transport reports back at all: whatsmeow waits out
    // its ack timeout and TDLib holds a send for as long as the phone is
    // offline, so an airplane-mode message sat there with no tick and no mark.
    private const val SEND_WATCHDOG_MS = 5_000L
    private const val MEDIA_WATCHDOG_MS = 60_000L
    private val watchdogs = ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()

    private fun armSendWatchdog(row: MessageRow) {
        val key = row.chatId + KEY_SEP + row.id
        val media = row.msgType in NEEDS_LOCAL_FILE
        watchdogs.put(key, retryScheduler.schedule({
            watchdogs.remove(key)
            // No ladder for media: restarting a large upload every few seconds
            // would keep it from ever finishing.
            val key2 = row.chatId + KEY_SEP + row.id
            if (db.isSendPending(row.chatId, row.id)) {
                markSendFailed(
                    row.chatId, row.id,
                    retry = !media && !sendQueued.contains(key2) && !sendInFlight.contains(key2)
                )
            }
        }, if (media) MEDIA_WATCHDOG_MS else SEND_WATCHDOG_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS))?.cancel(false)
    }

    private fun disarmSendWatchdog(chatId: String, msgId: String) {
        watchdogs.remove(chatId + KEY_SEP + msgId)?.cancel(false)
    }

    // A retry fired while the transport still holds the first attempt puts a
    // second copy of the same message on the wire beside it.
    private val sendInFlight = ConcurrentHashMap.newKeySet<String>()

    // Sends are dispatched one at a time per protocol, so a burst leaves later
    // rows waiting seconds — and the watchdog firing on one of those re-sent a
    // message whose first attempt had not started, reaching the peer twice.
    private val sendQueued = ConcurrentHashMap.newKeySet<String>()

    private fun runSend(row: MessageRow, send: (String) -> String): Boolean {
        val key = row.chatId + KEY_SEP + row.id
        sendQueued.remove(key)
        if (!proto(row.chatId).connected) {
            onMessageSendFailed(row.chatId, row.id)
            return false
        }
        sendInFlight.add(key)
        val resultId = try {
            send(wireId(row.id))
        } catch (e: Exception) {
            Log.w(TAG, "send threw for ${row.chatId}", e)
            ""
        } finally {
            sendInFlight.remove(key)
        }
        if (resultId.isEmpty()) {
            onMessageSendFailed(row.chatId, row.id)
            return false
        }
        if (resultId != row.id) {
            // Re-keying to an id the protocol has already finished with left a
            // duplicate row marked unsent that nothing ever cleared.
            if (settledBeforeRekey == row.chatId + KEY_SEP + resultId) {
                settledBeforeRekey = ""
                forgetRetry(row.chatId, row.id)
                db.deleteMessage(row.chatId, row.id)
                notifyChat(row.chatId)
                return true
            }
            db.renameMessage(row.chatId, row.id, resultId)
            moveRetryKey(row.chatId, row.id, resultId)
            // A watchdog left on the staged id finds no row, so it would pass a
            // send that is still queued.
            disarmSendWatchdog(row.chatId, row.id)
            armSendWatchdog(row.copy(id = resultId))
        }
        if (proto(row.chatId).ackOnSend) onMessageSendOk(row.chatId, resultId)
        notifyChat(row.chatId)
        return true
    }

    fun sendText(chatId: String, text: String, mentions: List<Mention> = emptyList()) =
        stageExecutor.execute {
            val row = stage(chatId, mintId(chatId), text)
            protoExecutor(chatId).execute {
                runSend(row) { id -> proto(chatId).sendText(chatId, id, text, null, mentions) }
            }
        }

    fun sendReply(
        chatId: String, text: String, quoted: MessageRow, mentions: List<Mention> = emptyList(),
    ) = stageExecutor.execute {
        val row = stage(chatId, mintId(chatId), text, quoted = quoted)
        protoExecutor(chatId).execute {
            runSend(row) { id -> proto(chatId).sendText(chatId, id, text, quoted, mentions) }
        }
    }

    private val waEditWindowSeconds: Long by lazy { Wmbridge.editWindowSeconds() }

    fun canEdit(msg: MessageRow): Boolean =
        System.currentTimeMillis() / 1000 - msg.timeSent < proto(msg.chatId).editWindowSeconds

    fun canEditCaption(msg: MessageRow): Boolean = proto(msg.chatId).canEditCaption(msg)

    fun canDeleteForEveryone(msg: MessageRow): Boolean = msg.fromMe &&
        System.currentTimeMillis() / 1000 - msg.timeSent < proto(msg.chatId).revokeWindowSeconds

    internal fun toastUi(resId: Int) {
        val ctx = appContext ?: return
        main.post {
            android.widget.Toast.makeText(ctx, resId, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // With no listener attached (app backgrounded, service still connected)
    // there is nothing to deliver to, and posting anyway allocated a closure
    // plus a Message for every event the protocols keep producing.
    private fun notifyUi(block: (UiListener) -> Unit) {
        if (listeners.isEmpty()) return
        main.post { for (l in listeners) block(l) }
    }

    fun editMessage(msg: MessageRow, newText: String, mentions: List<Mention> = emptyList()) =
        protoExecutor(msg.chatId).execute {
            val ok = proto(msg.chatId).edit(msg, newText, mentions)
            if (!ok) {
                Log.w(TAG, "edit failed")
                toastUi(R.string.edit_failed)
            }
        }

    fun deleteForEveryone(chatId: String, msgId: String) = protoExecutor(chatId).execute {
        proto(chatId).deleteForEveryone(chatId, msgId)
    }

    fun sendReaction(msg: MessageRow, emoji: String) = protoExecutor(msg.chatId).execute {
        proto(msg.chatId).react(msg, emoji)
    }

    fun deleteForMe(chatId: String, msgId: String) = protoExecutor(chatId).execute {
        forgetRetry(chatId, msgId)
        proto(chatId).deleteForMe(chatId, msgId)
        db.deleteMessage(chatId, msgId)
        notifyChat(chatId)
    }

    fun deleteChat(chatId: String, deleteMedia: Boolean) = protoExecutor(chatId).execute {
        val recent = db.recentMessages(chatId, 5)
        proto(chatId).deleteChat(chatId, deleteMedia, recent)
        executor.execute { forgetChat(chatId, deleteMedia) }
    }

    // The other device already dropped it, so no patch goes back out. WhatsApp
    // echoes our own delete back as this event, so the media flag has to be the
    // one the patch carried: hardcoding true here deleted the files of a user
    // who had explicitly unticked the box, whenever the echo won the race.
    fun onChatDeletedRemotely(chatId: String, deleteMedia: Boolean) = executor.execute {
        if (wiping) return@execute
        forgetChat(chatId, deleteMedia)
    }

    private fun forgetChat(chatId: String, deleteMedia: Boolean) {
        forgetChatRetries(chatId)
        val mediaPaths = if (deleteMedia) db.chatMediaPaths(chatId) else emptyList()
        db.deleteChat(chatId)
        db.clearScroll(chatId)
        // drop this chat's pagination state so a later re-sync starts clean —
        // including the persisted "searched everything" claim, which a re-synced
        // chat has not earned again
        historyAnchor.remove(chatId)
        historyExhausted.remove(chatId)
        appContext?.let { Prefs.clearHistoryComplete(it, chatId) }
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
        // an offline toggle used to fail with nothing but a log line
        if (!Wmbridge.setMute(connId, chatId, muted)) {
            Log.w(TAG, "mute change not synced for $chatId (offline?)")
            toastUi(R.string.mute_not_synced)
            if (storedLocally) {
                pendingMute.remove(chatId)
            }
        }
    }

    private const val PENDING_MUTE_TTL_MS = 60_000L

    private fun reconcileMutes() = executor.execute {
        val flags = db.mutedFlags()
        // Telegram and Signal chats share this table but their mute lives on
        // their own side; asking the Go bridge about a "tg:" or "sg:" id always
        // answers "not muted", which silently un-muted every such chat on each
        // connect.
        val ids = flags.keys.filter { it !in pendingMute && isWaId(it) }
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

    fun quotedPreview(m: MessageRow): String {
        val ctx = appContext ?: return m.text
        return previewLabel(ctx, m.msgType, m.text, emoji = false)
    }

    private fun quoteArgs(q: MessageRow?): Triple<String, String, String> =
        Triple(q?.id ?: "", q?.let { quotedPreview(it) } ?: "", q?.senderId ?: "")

    private fun sendMediaBlocking(row: MessageRow, send: (Protocol, String) -> String): Boolean {
        val p = proto(row.chatId)
        val ok = runSend(row) { id -> send(p, id) }
        // Only ever inside cacheDir — forwards re-send straight from the
        // permanent media dir, which must survive. Kept when the send failed:
        // the row now stays on screen as retryable, and deleting the file under
        // it left a bubble that could never be sent again.
        if (ok && p.consumesStagingInput && isStagingPath(row.filePath)) {
            java.io.File(row.filePath).delete()
        }
        return ok
    }

    fun sendAudio(
        chatId: String, filePath: String, durationSeconds: Int,
        quoted: MessageRow? = null, waveform: ByteArray = ByteArray(0),
        viewOnce: Boolean = false,
    ) = stageExecutor.execute {
        val row = stage(
            chatId, mintId(chatId), TimeFormat.mmss(durationSeconds), "audio", filePath, quoted
        )
        mediaExecutor.execute {
            sendMediaBlocking(row) { p, id ->
                p.sendAudio(chatId, id, filePath, durationSeconds, quoted, waveform, viewOnce)
            }
        }
    }

    fun sendFile(
        chatId: String, filePath: String, fileName: String, mimeType: String,
        caption: String = "", quoted: MessageRow? = null, viewOnce: Boolean = false,
    ) = stageExecutor.execute {
        val row = stageFile(chatId, filePath, fileName, mimeType, caption, quoted)
        mediaExecutor.execute { sendFileBlocking(row, fileName, mimeType, caption, quoted, viewOnce) }
    }

    // On [batchExecutor], not mediaExecutor's two workers, where a small file
    // overtook the larger one picked before it and the attachments arrived
    // shuffled.
    fun sendFileInOrder(
        chatId: String, filePath: String, fileName: String, mimeType: String,
        quoted: MessageRow? = null, viewOnce: Boolean = false,
    ) = stageExecutor.execute {
        val row = stageFile(chatId, filePath, fileName, mimeType, "", quoted)
        batchExecutor.execute { sendFileBlocking(row, fileName, mimeType, "", quoted, viewOnce) }
    }

    // A document's row text IS its file name; the bubble reads it back.
    private fun stageFile(
        chatId: String, filePath: String, fileName: String, mimeType: String,
        caption: String, quoted: MessageRow?,
    ): MessageRow = when {
        mimeType.startsWith("image/") ->
            stage(chatId, mintId(chatId), caption, "image", filePath, quoted)
        mimeType.startsWith("video/") ->
            stage(chatId, mintId(chatId), caption, "video", filePath, quoted)
        else -> stage(chatId, mintId(chatId), fileName, "document", filePath, quoted)
    }

    private fun sendFileBlocking(
        row: MessageRow, fileName: String, mimeType: String,
        caption: String, quoted: MessageRow?, viewOnce: Boolean,
    ) {
        val chatId = row.chatId
        val filePath = row.filePath
        when (row.msgType) {
            "image" -> sendMediaBlocking(row) { p, id ->
                p.sendImage(chatId, id, filePath, caption, quoted, viewOnce)
            }
            "video" -> sendMediaBlocking(row) { p, id ->
                p.sendVideo(chatId, id, filePath, caption, quoted, viewOnce)
            }
            else -> sendMediaBlocking(row) { p, id ->
                p.sendDocument(chatId, id, filePath, fileName, mimeType, quoted)
            }
        }
    }

    // WhatsApp takes photo, video and voice anywhere. Telegram self-destructs
    // photo and video in one-to-one chats only, and rejects the flag outright
    // on anything else ("Can't enable self-destruction for media").
    fun viewOnceSupported(chatId: String, kind: String): Boolean =
        kind in proto(chatId).viewOnceKinds(chatId)

    // TDLib publishes my_id late: a first run reaches this from a long-press
    // before it lands, and an unrecognised self chat was offered a view-once
    // send the server then refuses — hence the contact-row fallback.
    private fun isTgSelfChat(chatId: String): Boolean {
        Tg.selfId().let { if (it.isNotEmpty()) return chatId == it }
        return db.isSelfContact(chatId)
    }

    /** Blocking; worker threads only. */
    fun reactionsOf(msg: MessageRow): List<Pair<String, String>> =
        proto(msg.chatId).reactionSenders(msg) ?: db.reactionsOf(msg.chatId, msg.id)

    fun retrySend(msg: MessageRow): Boolean {
        if (!canResend(msg)) return false
        // The previous attempt is still inside the transport; a second one now
        // would race it onto the wire.
        if (sendInFlight.contains(msg.chatId + KEY_SEP + msg.id)) return true
        forgetRetry(msg.chatId, msg.id)
        resend(msg.chatId, msg.id)
        return true
    }

    private fun canResend(msg: MessageRow): Boolean {
        if (msg.msgType in NEEDS_LOCAL_FILE && !fileOnDisk(msg)) return false
        // Filtered, not raw: a body of blank lines passed a raw size check and
        // then threw on first() inside the executor, killing the process.
        if (msg.msgType == "contact" && msg.text.lines().count { it.isNotBlank() } < 2) return false
        return true
    }

    private fun resend(chatId: String, msgId: String) {
        // Two taps land on the same adapter row before it is refreshed, and the
        // second cannot see the first: without this the peer got it twice.
        if (!retrying.add(chatId + KEY_SEP + msgId)) return
        // Not batchExecutor: that one carries forwards and ordered multi-file
        // shares, and a chat's worth of retries against a dead transport would
        // hold every one of them up for a send timeout each.
        retryWorker.execute {
            try {
                val msg = db.messagesByIds(chatId, listOf(msgId)).firstOrNull() ?: return@execute
                if (msg.sendPending || !msg.sendFailed) return@execute
                if (!canResend(msg)) {
                    retryAttempts.remove(chatId + KEY_SEP + msgId)
                    return@execute
                }
                // TDLib still holds its own copy of this send; sending again
                // without cancelling it delivers the message twice.
                if (isTg(chatId) && !msgId.startsWith(LOCAL_ID) &&
                    !Tg.cancelQueuedSend(chatId, msgId)
                ) {
                    // Already sent; its own update clears the mark.
                    return@execute
                }
                db.setSendPending(chatId, msgId)
                armSendWatchdog(msg)
                notifyChat(chatId)
                val quoted = msg.quotedId.takeIf { it.isNotEmpty() }
                    ?.let { db.messagesByIds(chatId, listOf(it)).firstOrNull() }
                val mentions = storedMentions(msg.text) { db.contactName(it) != null }
                sendMediaBlocking(msg) { p, id -> sendRow(p, chatId, id, msg, quoted, mentions) }
            } finally {
                retrying.remove(chatId + KEY_SEP + msgId)
            }
        }
    }

    private fun sendRow(
        p: Protocol, target: String, msgId: String, m: MessageRow, quoted: MessageRow?,
        mentions: List<Mention>,
    ): String = when (m.msgType) {
        "image", "sticker" -> p.sendImage(target, msgId, m.filePath, m.text, quoted, false)
        in VIDEO_TYPES -> p.sendVideo(target, msgId, m.filePath, m.text, quoted, false)
        "audio" -> p.sendAudio(
            target, msgId, m.filePath, TimeFormat.parseSeconds(m.text), quoted, ByteArray(0), false
        )
        // the stored text IS the document's file name; its MIME type is
        // recovered from the extension rather than sent empty (which the
        // bridge downgrades to application/octet-stream, leaving the
        // recipient a generic unopenable attachment)
        "document" -> p.sendDocument(
            target, msgId, m.filePath, m.text, mimeOfPath(m.filePath), quoted
        )
        "location" -> p.sendLocation(target, msgId, m.latitude, m.longitude)
        "contact" -> {
            val card = m.text.lines().filter { it.isNotBlank() }
            p.sendContact(target, msgId, card.first(), card.drop(1))
        }
        else -> p.sendText(target, msgId, m.text, quoted, mentions)
    }

    private val retrying = ConcurrentHashMap.newKeySet<String>()

    private val NEEDS_LOCAL_FILE = PICTURE_TYPES + VIDEO_TYPES + setOf("audio", "document")

    private val RETRY_DELAYS_SECONDS = longArrayOf(2, 4, 8, 16, 32, 64, 64, 64, 64, 64)
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor()
    private val retryWorker = Executors.newSingleThreadExecutor()
    private val retryAttempts = ConcurrentHashMap<String, Int>()
    private val retryTasks = ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()

    private fun scheduleRetry(chatId: String, msgId: String) {
        val key = chatId + KEY_SEP + msgId
        // WhatsApp and Signal report one failure twice, as the bridge's event
        // and then as the send's empty answer. Counting both spent two rungs
        // per attempt: ten attempts became five.
        if (retryTasks.containsKey(key)) return
        val attempt = (retryAttempts[key] ?: 0) + 1
        retryAttempts[key] = attempt
        if (attempt > RETRY_DELAYS_SECONDS.size) return
        retryTasks[key] = retryScheduler.schedule(
            { retryTasks.remove(key); resend(chatId, msgId) },
            RETRY_DELAYS_SECONDS[attempt - 1], java.util.concurrent.TimeUnit.SECONDS
        )
    }

    private fun cancelRetry(chatId: String, msgId: String) {
        retryTasks.remove(chatId + KEY_SEP + msgId)?.cancel(false)
    }

    private fun forgetRetry(chatId: String, msgId: String) {
        cancelRetry(chatId, msgId)
        retryAttempts.remove(chatId + KEY_SEP + msgId)
    }

    // A deleted message left an armed wait firing into nothing, and its attempt
    // count behind for the rest of the run.
    private fun forgetChatRetries(chatId: String) {
        val prefix = chatId + KEY_SEP
        retryTasks.keys.filter { it.startsWith(prefix) }
            .forEach { retryTasks.remove(it)?.cancel(false) }
        retryAttempts.keys.removeAll { it.startsWith(prefix) }
    }

    // Telegram re-keys the row on every attempt, so the attempt count has to
    // follow it or each retry would look like the first.
    private fun moveRetryKey(chatId: String, oldId: String, newId: String) {
        val old = chatId + KEY_SEP + oldId
        retryTasks.remove(old)?.cancel(false)
        retryAttempts.remove(old)?.let { retryAttempts[chatId + KEY_SEP + newId] = it }
    }

    fun isStagingPath(filePath: String): Boolean {
        val cacheDir = appContext?.cacheDir ?: return false
        return filePath.startsWith(cacheDir.path + "/")
    }

    // Only a protocol that consumes the staging copy: WhatsApp deletes it the
    // moment the upload returns and swaps the row to the permanent media file,
    // so a staging path there means in-flight. Telegram and Signal never swap —
    // their rows keep pointing at the staging file for its whole life, and
    // reading "staging path" as "in-flight" there made every video, photo and
    // document the user had sent permanently unforwardable, unshareable and
    // unsaveable.
    fun isSendInFlight(msg: MessageRow): Boolean =
        msg.fromMe && !msg.sendFailed &&
            proto(msg.chatId).consumesStagingInput && isStagingPath(msg.filePath)

    fun fileOnDisk(msg: MessageRow): Boolean =
        msg.filePath.isNotEmpty() && java.io.File(msg.filePath).exists()

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
        // Link-preview pictures live far longer than a day — a chat scrolled
        // back to would re-fetch every card otherwise — but not forever. The
        // stored rows are deliberately left pointing at the deleted files:
        // LinkPreview.stored() re-fetches a preview whose picture is missing,
        // and blanking the path here instead made the row a valid "has a
        // preview, has no picture", which nothing ever fetches again.
        val previewCutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        java.io.File(ctx.cacheDir, LinkPreview.IMAGE_DIR).listFiles()?.forEach { f ->
            if (f.lastModified() < previewCutoff) f.delete()
        }
    }

    // protoExecutor, not executor: Signal.sendLocation and Signal.sendContact
    // are the two Signal calls with no ops {} of their own, so they block in
    // place on the network — on the shared executor that stalled every WhatsApp
    // and Telegram operation queued behind them (see sgExecutor).
    fun sendLocation(chatId: String, latitude: Double, longitude: Double) =
        stageExecutor.execute {
            val row = stage(
                chatId, mintId(chatId), "", "location",
                latitude = latitude, longitude = longitude
            )
            protoExecutor(chatId).execute {
                runSend(row) { id -> proto(chatId).sendLocation(chatId, id, latitude, longitude) }
            }
        }

    fun sendContact(chatId: String, name: String, numbers: List<String>) =
        stageExecutor.execute {
            // Must stay the "name\nnumber…" shape the contact card is read back
            // from (sgContactText writes the same one on the Signal side).
            val body = (listOf(name) + numbers).joinToString("\n")
            val row = stage(chatId, mintId(chatId), body, "contact")
            protoExecutor(chatId).execute {
                runSend(row) { id -> proto(chatId).sendContact(chatId, id, name, numbers) }
            }
        }

    // Each message (its upload included) is sent to completion before the next
    // starts, so a batch arrives in the order it was sent. Off the shared
    // executor/mediaExecutor so a slow upload never stalls live sending or
    // receiving, nor the downloads and avatar fetches sharing mediaExecutor.
    private val batchExecutor = Executors.newSingleThreadExecutor()

    fun forwardMessages(
        targetChatIds: List<String>, messages: List<MessageRow>, onDone: (Boolean) -> Unit,
    ) = batchExecutor.execute {
        var sent = false
        for (target in targetChatIds) {
            for (m in messages) if (forwardOneBlocking(target, m, null)) sent = true
        }
        main.post { onDone(sent) }
    }

    // Mentions are only ever passed by a retry: a forward carries the source
    // group's ids, which mean nothing (or someone else) in the target chat.
    private fun forwardOneBlocking(
        target: String, m: MessageRow, quoted: MessageRow?, mentions: List<Mention> = emptyList(),
    ): Boolean {
        // Staged inline, not on stageExecutor: a batch has to reach the target
        // in the order it was picked.
        val row = stage(
            target, mintId(target), m.text, m.msgType, m.filePath, quoted, m.latitude, m.longitude
        )
        // cross-protocol forwards work because every send re-uploads the local
        // file, so a Telegram target takes the same paths a WhatsApp one does
        return sendMediaBlocking(row) { p, id -> sendRow(p, target, id, m, quoted, mentions) }
    }

    // The chat list asks per visible row, so without this memo a scroll
    // re-sends a subscription per rebind. Deliberately
    // NOT permanent — a WhatsApp presence subscription is short-lived on the
    // server and dropped on reconnect, which is why the open chat re-arms its
    // own every 30s; a for-the-run memo swallowed that refresh and froze the
    // subtitle (and the chat-list dot) at the first value seen.
    private val presenceSubscribed = ConcurrentHashMap<String, Long>()

    // just under the chat screen's re-arm interval, so the refresh gets through
    // while a scroll's worth of rebinds still collapses into one subscription
    private const val PRESENCE_MEMO_MS = 25_000L

    // The memo is checked on the CALLING thread: doing it inside the task meant
    // every rebind still allocated and queued a Runnable that almost always did
    // nothing — onto the same serial executor that carries sends, mark-read and
    // history requests, so a fling pushed a burst of no-ops ahead of real work.
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

    // The stored file_status is no substitute: 1 survives a process death
    // mid-transfer, and it is written by the transport's own worker, so it is
    // still 0 for the first moments after a tap — long enough for the bubble to
    // show nothing and for the user to tap again.
    fun isDownloading(chatId: String, msgId: String): Boolean =
        downloading.contains("$chatId/$msgId")

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
        val key = msg.chatId + "/" + msg.id
        if (userInitiated) {
            userRequestedDownloads.add(key)
        } else if (msg.fileStatus == 3) {
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

    /** Null means no server search here, or a failed call. Blocking; worker
     *  threads only. */
    fun searchServer(chatId: String, query: String, fromMessageId: Long): Tg.SearchPage? =
        proto(chatId).searchServer(chatId, query, fromMessageId)

    fun searchContext(chatId: String, msgId: String): List<MessageRow> =
        proto(chatId).searchContext(chatId, msgId)

    /** [resolveNumber] could not ask — not "the number is not registered". */
    const val NUMBER_LOOKUP_FAILED = "failed"

    fun resolveNumber(phone: String): String =
        if (connId >= 0 && phone.isNotEmpty()) Wmbridge.resolveNumber(connId, phone)
        else NUMBER_LOOKUP_FAILED

    fun rememberContact(chatId: String, name: String) {
        if (chatId.isEmpty() || name.isEmpty()) return
        executor.execute {
            // your own number is in your address book too, and writing it back
            // as an ordinary contact (is_self=0) would list you in your own
            // search results for good
            if (chatId == selfId()) return@execute
            // never rename someone already known: the name here can come from
            // a received contact card, i.e. the sender chose it
            if (db.contactName(chatId) != null) return@execute
            db.upsertContact(
                chatId, name,
                // only a phone JID holds a real number; a @lid's digits are not
                // one, and would render as a plausible but invented "+number"
                if (isPhoneId(chatId)) chatId.substringBefore('@') else "",
                isSelf = false, isGroup = false, isSaved = true,
            )
            notifyChatsChanged()
        }
    }

    fun searchSlice(chatId: String, msgId: String, newer: Boolean): List<MessageRow> =
        proto(chatId).searchSlice(chatId, msgId, newer)

    /** [newer] null centres on the anchor. Blocking; worker threads only.
     *  Empty for WhatsApp, whose server holds nothing searchable. */
    fun chatPhotos(chatId: String, msgId: String, newer: Boolean?): List<MessageRow> =
        proto(chatId).chatPhotos(chatId, msgId, newer)

    /** Search-window rows are not stored, so the usual download path — which
     *  records its progress on the row — has nothing to write to; this hands
     *  the path straight back instead. Blocking. */
    fun searchMedia(chatId: String, msgId: String): String =
        proto(chatId).searchMedia(chatId, msgId)

    fun requestChatHistory(chatId: String) = proto(chatId).requestHistoryPage(chatId)

    fun isHistoryExhausted(chatId: String): Boolean = proto(chatId).isHistoryExhausted(chatId)

    private class SeekState(
        val chatId: String, val targetId: String, var anchor: Anchor, var pagesLeft: Int,
    ) {
        // bounded retries while the shared history slot is busy, so a long
        // export can delay a seek but never spin against it forever
        var busyRetriesLeft: Int = 40
    }
    @Volatile private var seek: SeekState? = null

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

    fun cancelSeek() = executor.execute { seek = null }

    fun requestInitialHistory(chatId: String) = proto(chatId).requestInitialHistory(chatId)

    // At most ONE on-demand history request is outstanding at a time: the
    // phone's end-of-history response names no chat, so a single in-flight
    // request is what keeps attribution unambiguous and lets a stale timeout or
    // a superseded/duplicate delivery be recognised and ignored. All slot state
    // is confined to the executor thread.
    private class HistoryReq(
        val chatId: String, val anchorId: String, val forExport: Boolean, val gen: Long,
        val forSeek: Boolean = false,
    )
    private class Anchor(val id: String, val time: Long, val fromMe: Boolean)

    @Volatile private var historyInFlight: HistoryReq? = null
    private var historyGen = 0L
    // advanced by each delivered page's reported oldest, so a page of only
    // non-displayable entries still makes progress
    private val historyAnchor = ConcurrentHashMap<String, Anchor>()

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

    private fun requestHistoryPageWa(chatId: String, retryIfBusy: Boolean = false) {
        if (chatId in historyExhausted) { endSyncAll(chatId, complete = true); return }
        val anchor = historyAnchor[chatId]
            ?: db.oldestMessage(chatId)?.let { Anchor(it.id, it.timeSent, it.fromMe) }
        if (anchor == null) { endSyncAll(chatId, complete = false); return }
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
        val exhausted = count == 0L || oldestId.isEmpty() || oldestId == req.anchorId
        if (req.forSeek) {
            val s = seek
            if (s == null || s.chatId != chatId) return@execute
            // A page of a superseded seek in the SAME chat must not advance the
            // new seek's anchor: it made the walk skip the stretch between the
            // two starting points and report the target as missing.
            if (req.anchorId != s.anchor.id) return@execute
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
            continueSyncAll(chatId)
        }
    }

    @Volatile private var syncAllChat: String? = null
    @Volatile private var syncAllRounds = 0

    fun syncAllProgress(chatId: String): Int = proto(chatId).syncAllProgress(chatId)

    internal fun asymptoticProgress(rounds: Int): Int = 100 * rounds / (rounds + 1)

    fun syncAllHistory(chatId: String): Boolean = proto(chatId).syncAllHistory(chatId)

    private fun syncAllHistoryWa(chatId: String): Boolean {
        if (chatExport != null) return false
        val cur = syncAllChat
        if (cur != null && cur != chatId) return false
        if (cur == chatId) return true
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

    private fun endSyncAll(chatId: String, complete: Boolean) {
        if (syncAllChat != chatId) return
        syncAllChat = null
        syncAllRounds = 0
        notifySyncAll(chatId, if (complete) 100 else -1)
    }

    private fun cancelSyncAllQuietly(chatId: String) {
        if (syncAllChat != chatId) return
        syncAllChat = null
        syncAllRounds = 0
    }

    internal fun notifySyncAll(chatId: String, progress: Int) =
        notifyUi { it.onChatSyncProgress(chatId, progress) }

    private class ChatExport(val chatId: String, val uri: android.net.Uri) {
        val collected = ConcurrentHashMap<String, MessageRow>()
        @Volatile var anchor: Anchor? = null
        var busyRetriesLeft: Int = 40
    }

    @Volatile private var chatExport: ChatExport? = null

    fun exportProgress(chatId: String): Int = proto(chatId).exportProgress(chatId)

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

    fun reportVisible(chatId: String, msgIds: List<String>) =
        proto(chatId).reportVisible(chatId, msgIds)

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

    fun selfIdOf(chatId: String): String = Accounts.ofChat(chatId).selfId()

    /** Blocking; worker threads only. Opening a chat under a contact's @lid
     *  alias would fork a second thread for someone already there under their
     *  phone JID (see reconcileLidChats). */
    fun resolveChatId(chatId: String): String {
        if (connId < 0 || isTg(chatId) || !chatId.endsWith("@lid")) return chatId
        return Wmbridge.resolveChatId(connId, chatId).ifEmpty { chatId }
    }

    fun fetchPrivacySettings(onResult: (Map<String, String>?) -> Unit) = executor.execute {
        val raw = Wmbridge.getPrivacySettings(connId)
        val map = if (raw.isEmpty()) null else raw.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        main.post { onResult(map) }
    }

    fun setPrivacySetting(name: String, value: String, onResult: (Boolean) -> Unit) =
        executor.execute {
            val ok = Wmbridge.setPrivacySetting(connId, name, value)
            main.post { onResult(ok) }
        }

    fun myName(): String = if (connId >= 0) Wmbridge.getMyName(connId) else ""

    fun setMyName(name: String, onResult: (Boolean) -> Unit) = executor.execute {
        val ok = connId >= 0 && Wmbridge.setMyName(connId, name)
        main.post { onResult(ok) }
    }

    fun fetchMyAbout(onResult: (String) -> Unit) = executor.execute {
        val about = if (connId >= 0) Wmbridge.getMyAbout(connId) else ""
        main.post { onResult(about) }
    }

    fun setAbout(text: String, onResult: (Boolean) -> Unit) = executor.execute {
        val ok = connId >= 0 && Wmbridge.setAbout(connId, text)
        main.post { onResult(ok) }
    }

    fun setProfilePicture(jpegPath: String, onResult: (Boolean) -> Unit) = mediaExecutor.execute {
        val ok = connId >= 0 && Wmbridge.setProfilePicture(connId, jpegPath)
        main.post { onResult(ok) }
    }

    private const val TG_NAME_LOOKUPS = 50

    /** chatId opens a chat with them, mentionId is how their group addresses
     *  them — the same person, under two ids, on WhatsApp. */
    class Member(val chatId: String, val mentionId: String, val name: String)

    /** Blocking (both protocols ask their server); worker threads only. Empty
     *  for a group whose member list this account may not read. */
    fun groupMembers(chatId: String): List<Member> {
        if (!isGroupId(chatId)) return emptyList()
        val names = db.contactNames()
        val members = if (isTg(chatId)) {
            // the update stream names most of them on the way in; the leftovers
            // cost a round trip each, so they are rationed — a TDLib that has
            // stopped answering would otherwise hold this thread for a 15s
            // timeout per member, and every other screen's lookups behind it
            var budget = TG_NAME_LOOKUPS
            Tg.groupMembers(chatId).map { id ->
                var name = names[id].orEmpty()
                if (name.isEmpty() && budget > 0) {
                    val looked = Tg.cacheUser(id)
                    budget = if (looked == null) 0 else budget - 1
                    name = looked.orEmpty()
                }
                Member(id, id, name.ifEmpty { id.removePrefix(Tg.PREFIX) })
            }
        } else {
            if (connId < 0) return emptyList()
            Wmbridge.getGroupMembers(connId, chatId).lineSequence().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0].isEmpty()) return@mapNotNull null
                // last resort is the bare digits, never "<lid>@lid": that is
                // what a mention of an unnamed member ends up reading as
                val name = names[parts[0]]?.takeIf { it.isNotEmpty() }
                    ?: names[parts[1]]?.takeIf { it.isNotEmpty() }
                    ?: if (isPhoneId(parts[0])) phoneLabel(parts[0])
                    else parts[0].substringBefore("@")
                Member(parts[0], parts[1], name)
            }.toList()
        }
        return members.sortedBy { it.name.lowercase() }
    }

    class PeerInfo(val phone: String, val nickname: String, val about: String)

    /** Blocking (both protocols ask their server); worker threads only. Every
     *  field is optional — an empty About is indistinguishable from one the
     *  contact's privacy settings hide, so the row is simply left out. */
    fun peerInfo(chatId: String): PeerInfo {
        if (isTg(chatId)) {
            val info = Tg.peerInfo(chatId) ?: return PeerInfo("", "", "")
            return PeerInfo(
                phone = if (info.phone.isEmpty()) "" else "+" + info.phone.removePrefix("+"),
                nickname = if (info.username.isEmpty()) "" else "@" + info.username,
                about = info.bio,
            )
        }
        // only a phone JID holds a real number; a @lid's digits are not one, so
        // fall back to the number the contact row carries rather than inventing
        // a plausible-looking "+<lid>"
        val phone = if (isPhoneId(chatId)) phoneLabel(chatId)
            else db.contactPhone(chatId).takeIf { it.isNotEmpty() }?.let { "+$it" }.orEmpty()
        val about = if (connId < 0) "" else Wmbridge.getUserAbout(connId, chatId)
        return PeerInfo(phone, "", about)
    }

    fun getAvatarPath(chatId: String): String =
        proto(chatId).avatarPath(chatId, big = false, cachedOnly = false)

    /** Never a network fetch: this runs on the notification path, a single
     *  serialized thread, where a stale-cache fetch is a blocking, timeout-less
     *  HTTP request that delays the alert and every task queued behind it
     *  (including the cancel fired when the user opens the chat). */
    fun getCachedAvatarPath(chatId: String): String =
        proto(chatId).avatarPath(chatId, big = false, cachedOnly = true)

    fun getAvatarFullPath(chatId: String): String =
        proto(chatId).avatarPath(chatId, big = true, cachedOnly = false)

    // The fetch can block on the network for a long time, so the activity may
    // well be gone by the time it returns — launching a viewer from a destroyed
    // activity would pop it over whatever screen the user moved on to.
    fun openAvatar(activity: android.app.Activity, chatId: String) {
        mediaExecutor.execute {
            var path = getAvatarFullPath(chatId)
            if (path.isEmpty()) path = getAvatarPath(chatId)
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
        //
        // Every id-keyed map is filtered by isWaId rather than cleared. These
        // are shared by the three protocols, so clearing them outright made
        // unlinking WhatsApp stop a Telegram voice note, cancel Signal's
        // notifications, drop both of their presence and typing state, and
        // strand their in-flight downloads — whose completion callback then
        // released a claim that was already gone.
        main.post { if (isWaId(AudioPlayer.currentChatId)) AudioPlayer.stop() }
        selfIdMemo = ""
        if (isWaId(activeChatId)) activeChatId = ""
        autoPlayKey?.let { if (isWaId(it.substringBeforeLast('/'))) autoPlayKey = null }
        // WhatsApp-only by construction: Tg keeps its own slots, and these are
        // written by the Wa transport alone.
        historyInFlight = null
        seek = null
        historyAnchor.clear()
        historyExhausted.clear()
        syncAllChat = null
        syncAllRounds = 0
        pendingMute.clear()
        // the next account starts with an empty history, so no WhatsApp chat may
        // still claim its search covered everything, keep the old account's
        // drafts, or anchor scrolling on ids from the old sync
        appContext?.let { Prefs.clearChatPrefsWhere(it) { id -> isWaId(id) } }
        downloading.removeAll { isWaId(it.substringBeforeLast('/')) }
        userRequestedDownloads.removeAll { isWaId(it.substringBeforeLast('/')) }
        autoRetriedFailures.removeAll { isWaId(it.substringBeforeLast('/')) }
        online.keys.removeAll { isWaId(it) }
        lastSeen.keys.removeAll { isWaId(it) }
        presenceSubscribed.keys.removeAll { isWaId(it) }
        lastSeenApprox.keys.removeAll { isWaId(it) }
        chatStates.keys.removeAll { isWaId(it) }
        main.post {
            chatActors.keys.removeAll { isWaId(it) }
            val gone = stateClearers.keys.filter { isWaId(it.substringBefore(KEY_SEP)) }
            for (key in gone) stateClearers.remove(key)?.let { main.removeCallbacks(it) }
        }
        appContext?.let { ctx -> Notifications.cancelMessagesFor(ctx) { id -> isWaId(id) } }
        notifyChatsChanged()
    }

    // WhatsApp is the protocol without a prefix, so an id is its only by not
    // being anyone else's. [Accounts.ofChat] is where that rule lives: spelled
    // out again here, a new protocol's prefix had to be remembered in two
    // places, and forgetting one made unlinking WhatsApp throw away that
    // protocol's live state too.
    private fun isWaId(id: String): Boolean =
        id.isNotEmpty() && Accounts.ofChat(id).proto == ProtoPicker.WA

    // Releasing the claim downloadFile took is the part that matters: without
    // it a failed download can never be retried, because every later attempt
    // sees the slot still held and dispatches nothing.
    internal fun onFileTransferDone(chatId: String, msgId: String, filePath: String, status: Int) {
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

    internal fun notifyChat(chatId: String) {
        synchronized(changedChats) {
            changedChats[chatId] = null
            chatsChanged = true
        }
        scheduleNotify()
    }

    internal fun notifyChatRow(chatId: String, msgId: String) {
        synchronized(changedChats) {
            if (changedChats.containsKey(chatId)) {
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

    override fun onStateChanged(state: String) {
        val wasConnected = this.state == "connected"
        this.state = state
        Log.i(TAG, "state: $state")
        if (state == "connected" && !wasConnected) {
            reconcileMutes()
            // the server drops every presence subscription with the socket, so
            // the memo must not keep claiming they are still in place
            presenceSubscribed.clear()
        }
        notifyAccountState(ProtoPicker.WA, state)
    }

    override fun onQrCode(code: String) =
        notifyUi { it.onQrCode(ProtoPicker.WA, code) }

    override fun onPairCode(code: String) = notifyUi { it.onPairCode(code) }

    override fun onPairError(message: String) =
        notifyUi { it.onPairError(ProtoPicker.WA, message) }

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

    // Heals chats mistakenly keyed by a contact's LID: a live message can land
    // before the LID→phone mapping is known.
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

    // mergeChat only re-keys DB rows; process-local state left under the old id
    // kept pointing at a chat that no longer exists (an orphan notification
    // whose tap opened a blank screen, a phantom "typing…", a pagination anchor
    // for nothing).
    private fun rekeyChatState(fromId: String, toId: String) {
        historyAnchor.remove(fromId)?.let { historyAnchor.putIfAbsent(toId, it) }
        if (historyExhausted.remove(fromId)) historyExhausted.add(toId)
        appContext?.let { ctx ->
            if (Prefs.historyComplete(ctx, fromId)) Prefs.setHistoryComplete(ctx, toId)
            Prefs.clearHistoryComplete(ctx, fromId)
            Prefs.draft(ctx, fromId).takeIf { it.isNotEmpty() }
                ?.let { Prefs.setDraft(ctx, toId, it) }
            Prefs.setDraft(ctx, fromId, "")
        }
        if (syncAllChat == fromId) syncAllChat = toId
        if (pendingMute.remove(fromId)) pendingMute.add(toId)
        chatStates.remove(fromId)?.let { chatStates.putIfAbsent(toId, it) }
        appContext?.let { ctx -> notifyExecutor.execute { Notifications.rekey(ctx, fromId, toId) } }
        main.post {
            chatActors.remove(fromId)?.let { actors -> chatActors[toId] = actors }
            recomputeChatState(toId)
        }
    }

    // message ids we asked the phone to re-send (contact-card repair): their
    // answers arrive as normal live messages and must not notify — they are
    // old messages the user has long seen
    private val resendPending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun onMessage(
        chatId: String, msgId: String, senderId: String, text: String,
        fromMe: Boolean, timeSent: Long, isRead: Boolean, msgType: String, fileId: String,
        latitude: Double, longitude: Double,
        isHistory: Boolean, isEdited: Boolean, quotedId: String, quotedText: String,
        quotedType: String, senderName: String, isForwarded: Boolean,
    ) {
        if (wiping) return
        val isResend = resendPending.remove(msgId)
        ingestMessage(
            MessageRow(
                msgId, chatId, senderId, text, fromMe, timeSent, isRead, msgType, fileId,
                edited = isEdited, quotedId = quotedId, quotedText = quotedText,
                quotedType = quotedType, senderName = senderName,
                forwarded = isForwarded, latitude = latitude, longitude = longitude
            ),
            // A resend is our own message coming back; it is new to the store
            // but not news to the user.
            notify = !isHistory && !isResend,
            // WhatsApp media URLs expire, so live messages fetch now; history
            // backfill downloads when scrolled into view, to avoid a download
            // storm on initial sync.
            fetchMedia = !isHistory,
        )
    }

    /**
     * [notify] is false for history backfill. [fetchMedia] is false when our
     * own send already has the file on this device — Signal downloading it back
     * raced the local copy. [bump] is false for an edit, which must not reorder
     * the chat list. [afterStore] writes the columns a shared MessageRow cannot
     * carry, while the UI has still not been told anything.
     */
    internal fun ingestMessage(
        row: MessageRow,
        notify: Boolean,
        fetchMedia: Boolean,
        bump: Boolean = !row.edited,
        afterStore: () -> Unit = {},
    ) {
        // a malformed edit/protocol message can carry an empty key; storing it
        // would create a row that can never be matched to a real message
        if (row.id.isEmpty()) { Log.w(TAG, "message with empty id for ${row.chatId}"); return }
        db.upsertMessage(row)
        afterStore()
        // The time the row KEPT, not the one just offered: a send that failed
        // holds on to the time it was sent, and bumping the chat with the
        // retry's late ack put it at the top of the list showing an old preview.
        if (bump) {
            val kept = if (row.fromMe) db.storedTime(row.chatId, row.id) else null
            db.bumpChat(row.chatId, kept ?: row.timeSent)
        }
        // A row that already carries a path has its bytes: Telegram hands one
        // over for media it has cached, and fetching again would be pure waste.
        if (fetchMedia && row.fileId.isNotEmpty() && row.filePath.isEmpty() &&
            (row.msgType in PICTURE_TYPES || row.msgType == "audio")
        ) {
            // downloadFile, not the transport directly: it is what claims the
            // in-flight slot, so a second event for the same message does not
            // start a second transfer.
            downloadFile(row)
        }
        if (notify && !row.fromMe && !row.isRead &&
            row.chatId != activeChatId && !db.isMuted(row.chatId)
        ) {
            postMessageNotification(row.chatId, row.senderId, row.text, row.msgType, row.timeSent)
        }
        notifyChat(row.chatId)
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
            val chatAvatar = getCachedAvatarPath(chatId)
            val senderAvatar = if (isGroup) getCachedAvatarPath(senderId) else chatAvatar
            Notifications.notifyMessage(
                ctx, chatId, chatName, senderName, preview, isGroup, timeSent, chatAvatar, senderAvatar
            )
        }
    }

    @Volatile private var activeChatOwner: Any? = null

    fun openChat(chatId: String, owner: Any? = null) {
        activeChatId = chatId
        activeChatOwner = owner
        // TDLib gates real-time traffic on this: without it a private chat's
        // typing/recording actions are dropped inside TDLib, and supergroups
        // deliver no updates at all while closed.
        proto(chatId).openChat(chatId)
        AudioPlayer.refreshServiceState()
        val ctx = appContext ?: return
        notifyExecutor.execute { Notifications.cancel(ctx, chatId) }
    }

    fun closeChat(chatId: String, owner: Any? = null) {
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

    /**
     * For rows a list is showing, NOT for a screen the user is in: it must not
     * claim the active chat (notification suppression) the way openChat does.
     * TDLib drops a private chat's typing/recording action unless the chat is
     * open or the peer's exact last-seen is known, so contacts who hide their
     * last-seen never showed as typing/recording in the chat list.
     */
    fun watchChatActions(chatId: String, watch: Boolean) {
        if (watch) proto(chatId).openChat(chatId) else proto(chatId).closeChat(chatId)
    }

    private fun messagePreview(text: String, msgType: String): String {
        val ctx = appContext ?: return text
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
            db.messageChat(msgId)?.let { moved ->
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
        notifyUi { it.onDownloadProgress(chatId, msgId, pct.toInt()) }
    }

    override fun onMessageRead(chatId: String, msgId: String) {
        if (wiping) return
        db.markMessageRead(chatId, msgId)
        notifyChatRow(chatId, msgId)
    }

    override fun onMessagePlayed(chatId: String, msgId: String) {
        if (wiping) return
        db.setPlayed(chatId, msgId)
        notifyChatRow(chatId, msgId)
    }

    override fun onMessageSendFailed(chatId: String, msgId: String) =
        markSendFailed(chatId, msgId, retry = true)

    private fun markSendFailed(chatId: String, msgId: String, retry: Boolean) {
        if (wiping) return
        disarmSendWatchdog(chatId, msgId)
        db.setSendFailed(chatId, msgId)
        notifyChatRow(chatId, msgId)
        if (!retry) return
        // The mark goes up either way; the ladder waits for the attempt still
        // inside the transport to answer and arm it itself.
        if (sendInFlight.contains(chatId + KEY_SEP + msgId)) return
        val first = retryAttempts[chatId + KEY_SEP + msgId] == null
        scheduleRetry(chatId, msgId)
        // Once per message, not per attempt: a send off the share sheet is
        // otherwise invisible, but ten toasts for the ten retries are worse
        // than none.
        if (first && chatId != activeChatId) toastUi(R.string.send_failed)
    }

    fun onMessageSendOk(chatId: String, msgId: String) {
        if (wiping) return
        // No row under the settled id means the staged row has not been re-keyed
        // yet: Telegram reports on its own thread and can beat the sendMessage
        // answer [runSend] waits for. One field, because its sends go out on a
        // single thread.
        if (!db.hasMessage(chatId, msgId)) settledBeforeRekey = chatId + KEY_SEP + msgId
        disarmSendWatchdog(chatId, msgId)
        forgetRetry(chatId, msgId)
        db.clearSendMarks(chatId, msgId)
        notifyChatRow(chatId, msgId)
    }

    @Volatile private var settledBeforeRekey = ""

    override fun onChatReadSelf(chatId: String, msgId: String) {
        if (wiping) return
        // WhatsApp reports the chat only; Signal names the message read, and
        // anything newer that landed here since must stay unread — so only the
        // Signal path can be left with an unread the notification still belongs
        // to. Marking the whole chat read leaves none by construction.
        val allRead = if (msgId.isEmpty()) {
            db.markChatRead(chatId)
            true
        } else {
            db.markChatReadUpTo(chatId, msgId)
            db.latestUnread(chatId) == null
        }
        if (allRead) {
            appContext?.let { ctx -> notifyExecutor.execute { Notifications.cancel(ctx, chatId) } }
        }
        notifyChat(chatId)
    }

    override fun onMute(chatId: String, muted: Boolean) {
        if (wiping) return
        pendingMute.remove(chatId)
        db.setMuted(chatId, muted)
        notifyChatsChanged()
    }

    override fun onChatDeleted(chatId: String, deleteMedia: Boolean) =
        onChatDeletedRemotely(chatId, deleteMedia)

    // Written as an escape, NOT as a literal control character: two raw NUL
    // bytes in this file used to make grep classify the largest Kotlin source
    // in the project as binary and skip it, so `grep -r` over app/src silently
    // missed every reference that lives here.
    private const val KEY_SEP = "\u0000"

    private val stateClearers = ConcurrentHashMap<String, Runnable>()

    override fun onChatState(chatId: String, userId: String, state: String) {
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

    internal fun notifyContactsChangedInternal() {
        synchronized(changedChats) { contactsChanged = true }
        notifyChatsChanged()
    }

    internal fun notifyTgAuth(state: String, message: String) =
        notifyUi { it.onTgAuth(state, message) }

    internal fun notifyAccountState(proto: String, state: String) =
        notifyUi { it.onAccountState(proto, state) }

    internal fun notifyQrCode(proto: String, code: String) =
        notifyUi { it.onQrCode(proto, code) }

    internal fun notifyPairError(proto: String, code: String) =
        notifyUi { it.onPairError(proto, code) }

    fun protoEnabled(proto: String): Boolean {
        val ctx = appContext ?: return true
        return Prefs.protoEnabled(ctx, proto)
    }

    // Behind one accessor rather than at each call site: the share/forward
    // picker read db.chats() directly and went on offering chats that could
    // neither send nor receive.
    fun visibleChats(): List<ChatRow> {
        val ctx = appContext ?: return db.chats()
        val hidden = Accounts.ALL.filterNot { Prefs.protoEnabled(ctx, it.proto) }
            .map { it.proto }.toSet()
        if (hidden.isEmpty()) return db.chats()
        return db.chats().filterNot { Accounts.ofChat(it.id).proto in hidden }
    }


    internal fun runOnUi(block: () -> Unit) = main.post(block)

    internal fun postDownloadProgress(chatId: String, msgId: String, pct: Int) =
        notifyUi { it.onDownloadProgress(chatId, msgId, pct) }

    internal fun postChatExportProgress(chatId: String, fetched: Int) =
        notifyUi { it.onChatExportProgress(chatId, fetched) }

    internal fun postChatExportDone(chatId: String, messages: Int, complete: Boolean, success: Boolean) =
        notifyUi { it.onChatExportDone(chatId, messages, complete, success) }
}
