package org.unichat.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.unichat.wmbridge.EventListener
import org.unichat.wmbridge.Wmbridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Bridge : EventListener {

    interface UiListener {
        fun onChatsChanged() {}
        fun onContactsChanged() {}
        fun onMessagesChanged(chatId: String, rowIds: Set<String>? = null) {}
        fun onChatMerged(fromId: String, toId: String) {}
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
    @Volatile lateinit var db: Db
        private set
    @Volatile var state: String = "disconnected"
        private set
    @Volatile var syncProgress: Int = -1
        private set

    private val executor = Executors.newSingleThreadExecutor()
    private val sgExecutor = Executors.newSingleThreadExecutor()
    private val mediaExecutor = Executors.newFixedThreadPool(2)
    private val notifyExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<UiListener>()
    private val downloading: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val historyExhausted = CopyOnWriteArraySet<String>()
    private const val HISTORY_PAGE = 100L
    const val HISTORY_TIMEOUT_MS = 30_000L
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

    fun presenceLine(ctx: Context, chatId: String): String? = when {
        isOnline(chatId) -> ctx.getString(R.string.online)
        lastSeenOf(chatId) > 0 -> ctx.getString(
            R.string.last_seen, TimeFormat.compactWithTime(ctx, lastSeenOf(chatId))
        )
        lastSeenApproxOf(chatId) != 0 -> ctx.getString(lastSeenApproxOf(chatId))
        else -> null
    }

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

    private fun endChain(playingChatId: String = "", playingMsgId: String = "") = main.post {
        when {
            !AudioPlayer.hasCurrent -> AudioPlayer.endSession()
            AudioPlayer.currentChatId == playingChatId &&
                AudioPlayer.currentMsgId == playingMsgId -> AudioPlayer.stop()
        }
    }

    private val autoPlayTimeout = Runnable {
        if (autoPlayKey != null) {
            disarmAutoPlay()
            endChain()
        }
    }

    private fun disarmAutoPlay() {
        autoPlayKey = null
        main.removeCallbacks(autoPlayTimeout)
    }

    private fun chainNextVoice(chatId: String, finishedMsgId: String) {
        if (chatId.isEmpty() || connId < 0) { endChain(chatId, finishedMsgId); return }
        executor.execute {
            val next = db.nextAudioMessage(chatId, finishedMsgId)
            if (next == null) {
                endChain(chatId, finishedMsgId)
                return@execute
            }
            val (path, status) = db.fileState(next.chatId, next.id)
            if (status >= 2 && path.isNotEmpty()) {
                main.post { AudioPlayer.play(path, chatId, next.id) }
            } else {
                autoPlayKey = next.chatId + "/" + next.id
                main.postDelayed(autoPlayTimeout, 15_000)
                if (!downloadFile(next)) {
                    main.post { disarmAutoPlay() }
                    endChain(chatId, finishedMsgId)
                }
            }
        }
    }

    private fun markVoicePlayed(chatId: String, msgId: String) {
        if (chatId.isEmpty() || msgId.isEmpty()) return
        executor.execute {
            val msg = db.audioMessage(chatId, msgId) ?: return@execute
            if (msg.fromMe || msg.played || msg.msgType != "audio") return@execute
            db.setPlayed(chatId, msg.id)
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

        val ackOnSend: Boolean

        val connected: Boolean

        fun edit(msg: MessageRow, newText: String, mentions: List<Mention>): Boolean

        fun canEditCaption(msg: MessageRow): Boolean = true
        val editWindowSeconds: Long
        val revokeWindowSeconds: Long

        fun deleteForEveryone(chatId: String, msgId: String)
        fun deleteForMe(chatId: String, msgId: String)
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

        fun searchServer(chatId: String, query: String, fromMessageId: Long): Tg.SearchPage? = null
        fun searchContext(chatId: String, msgId: String): List<MessageRow> = emptyList()
        fun searchSlice(chatId: String, msgId: String, newer: Boolean): List<MessageRow> = emptyList()
        fun chatPhotos(chatId: String, msgId: String, newer: Boolean?): List<MessageRow> = emptyList()
        fun searchMedia(chatId: String, msgId: String): String = ""

        fun reactionSenders(msg: MessageRow): List<Pair<String, String>>? = null

        fun viewOnceKinds(chatId: String): Set<String>

        fun groupMembers(chatId: String): List<Member> = emptyList()
        fun peerInfo(chatId: String): PeerInfo = PeerInfo("", "", "")
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

    private object SgTransport : Protocol {
        override fun sendText(
            chatId: String, msgId: String, text: String, quoted: MessageRow?,
            mentions: List<Mention>,
        ): String = Signal.sendText(chatId, msgId, text, quoted)

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
        override fun sendLocation(
            chatId: String, msgId: String, latitude: Double, longitude: Double,
        ): String = Signal.sendLocation(chatId, msgId, latitude, longitude)

        override fun sendContact(
            chatId: String, msgId: String, name: String, numbers: List<String>,
        ): String = Signal.sendContact(chatId, msgId, name, numbers)

        override val connected get() = Signal.state != "disconnected"

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
        override val editWindowSeconds = Long.MAX_VALUE
        override val revokeWindowSeconds = Long.MAX_VALUE

        override fun deleteForEveryone(chatId: String, msgId: String) = Signal.delete(chatId, msgId)
        override fun deleteForMe(chatId: String, msgId: String) {}

        override fun deleteChat(chatId: String, deleteMedia: Boolean, recent: List<MessageRow>) =
            Signal.deleteChat(chatId, recent)
        override fun react(msg: MessageRow, emoji: String) = Signal.react(msg, emoji)

        override fun setMuted(chatId: String, muted: Boolean) {
            db.setMuted(chatId, muted)
        }
        override fun markChatRead(chatId: String) = Signal.markChatRead(chatId)
        override fun markVoicePlayed(msg: MessageRow) {}
        override fun closeChat(chatId: String) = Signal.setTyping(chatId, false)

        override fun requestInitialHistory(chatId: String) {}
        override fun requestHistoryPage(chatId: String) {}
        override fun isHistoryExhausted(chatId: String) = true
        override fun seekMessage(chatId: String, target: String, from: MessageRow, maxPages: Int) {}
        override fun syncAllHistory(chatId: String) = false
        override fun syncAllProgress(chatId: String) = -1
        override fun exportChat(chatId: String, uri: android.net.Uri): Boolean {
            if (!sgExporting.add(chatId)) return false
            mediaExecutor.execute {
                writeExportRows(chatId, uri, complete = true,
                    onWritten = { sgExporting.remove(chatId) }) {
                    db.messages(chatId, Int.MAX_VALUE).sortedBy { it.timeSent }
                }
            }
            return true
        }

        override fun exportProgress(chatId: String) = -1

        override fun startDownload(msg: MessageRow) = Signal.startDownload(msg)
        override fun avatarPath(chatId: String, big: Boolean, cachedOnly: Boolean) = ""

        override val consumesStagingInput = false

        override fun viewOnceKinds(chatId: String) = emptySet<String>()
    }


    private object WaTransport : Protocol {
        override fun sendText(
            chatId: String, msgId: String, text: String, quoted: MessageRow?,
            mentions: List<Mention>,
        ): String {
            val (body, jids) = waMentionText(text, mentions)
            val mentioned = jids.joinToString(",")
            val preview = LinkPreview.outgoing(body)
            if (quoted == null) {
                return Wmbridge.sendTextMessage(connId, chatId, msgId, body, mentioned, preview)
            }
            return Wmbridge.sendTextReply(
                connId, chatId, msgId, body, quoted.id, quotedPreview(quoted), quoted.senderId,
                mentioned, preview
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
            val preview = if (msg.fileId.isEmpty()) LinkPreview.outgoing(body) else null
            return Wmbridge.editMessage(
                connId, msg.chatId, msg.id, body, msg.timeSent, msg.fileId,
                qid, qtext, qsender, byDigits.values.joinToString(","), preview,
            )
        }

        override fun canEditCaption(msg: MessageRow) =
            msg.fileId.isEmpty() || Wmbridge.canEditMedia(msg.fileId)

        override val editWindowSeconds: Long get() = waEditWindowSeconds
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
                var dispatched = false
                try {
                    val (path, status) = db.fileState(msg.chatId, msg.id)
                    if (status >= 2 && path.isNotEmpty() && java.io.File(path).exists()) return@execute
                    db.setFileState(msg.chatId, msg.id, "", 1)
                    Wmbridge.downloadFile(connId, msg.chatId, msg.id, msg.fileId, msg.fromMe, msg.senderId)
                    dispatched = true
                } finally {
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

        override fun groupMembers(chatId: String): List<Member> {
            if (connId < 0) return emptyList()
            val names = db.contactNames()
            return Wmbridge.getGroupMembers(connId, chatId).lineSequence().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0].isEmpty()) return@mapNotNull null
                val name = names[parts[0]]?.takeIf { it.isNotEmpty() }
                    ?: names[parts[1]]?.takeIf { it.isNotEmpty() }
                    ?: if (isPhoneId(parts[0])) phoneLabel(parts[0])
                    else parts[0].substringBefore("@")
                Member(parts[0], parts[1], name)
            }.toList()
        }

        override fun peerInfo(chatId: String): PeerInfo {
            val phone = if (isPhoneId(chatId)) phoneLabel(chatId)
                else db.contactPhone(chatId).takeIf { it.isNotEmpty() }?.let { "+$it" }.orEmpty()
            val about = if (connId < 0) "" else Wmbridge.getUserAbout(connId, chatId)
            return PeerInfo(phone, "", about)
        }
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
        override val connected get() = Tg.state != "disconnected"

        override fun edit(msg: MessageRow, newText: String, mentions: List<Mention>): Boolean =
            if (msg.msgType == "") Tg.editMessageText(msg.chatId, msg.id, newText, mentions)
            else Tg.editMessageCaption(msg.chatId, msg.id, newText, mentions)

        override val editWindowSeconds: Long = 48L * 60 * 60
        override val revokeWindowSeconds: Long = 48L * 60 * 60

        override fun deleteForEveryone(chatId: String, msgId: String) =
            Tg.deleteMessages(chatId, listOf(msgId), revoke = true)

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

        override fun viewOnceKinds(chatId: String): Set<String> =
            if (isGroupId(chatId) || isTgSelfChat(chatId)) emptySet() else TG_VIEW_ONCE

        override fun groupMembers(chatId: String): List<Member> {
            val names = db.contactNames()
            var budget = TG_NAME_LOOKUPS
            return Tg.groupMembers(chatId).map { id ->
                var name = names[id].orEmpty()
                if (name.isEmpty() && budget > 0) {
                    val looked = Tg.cacheUser(id)
                    budget = if (looked == null) 0 else budget - 1
                    name = looked.orEmpty()
                }
                Member(id, id, name.ifEmpty { id.removePrefix(Tg.PREFIX) })
            }
        }

        override fun peerInfo(chatId: String): PeerInfo {
            val info = Tg.peerInfo(chatId) ?: return PeerInfo("", "", "")
            return PeerInfo(
                phone = if (info.phone.isEmpty()) "" else "+" + info.phone.removePrefix("+"),
                nickname = if (info.username.isEmpty()) "" else "@" + info.username,
                about = info.bio,
            )
        }
    }

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
        val ctx = appContext
        if (ctx != null && !Prefs.protoEnabled(ctx, ProtoPicker.WA)) return@execute
        if (state != "connected") Wmbridge.connect(connId)
    }

    fun startQrLogin() = executor.execute { Wmbridge.startLogin(connId) }

    fun stopLogin() = executor.execute { Wmbridge.stopLogin(connId) }

    fun requestPairCode(phone: String) = Thread {
        Wmbridge.requestPairCode(connId, phone)
    }.start()

    private val stageExecutor = Executors.newSingleThreadExecutor()
    private val sgLastStamp = java.util.concurrent.atomic.AtomicLong()
    private val localIdSeq = java.util.concurrent.atomic.AtomicLong()

    private const val LOCAL_ID = "local:"

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
        latitude: Double = 0.0, longitude: Double = 0.0, armWatchdog: Boolean = true,
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
        db.bumpChat(chatId, row.timeSent)
        sendQueued.add(chatId + KEY_SEP + msgId)
        if (armWatchdog) armSendWatchdog(row)
        notifyChat(chatId)
        return row
    }

    private const val SEND_WATCHDOG_MS = 5_000L
    private const val MEDIA_WATCHDOG_MS = 60_000L
    private val watchdogs = ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()

    private fun armSendWatchdog(row: MessageRow) {
        val key = row.chatId + KEY_SEP + row.id
        val media = row.msgType in NEEDS_LOCAL_FILE
        watchdogs.put(key, retryScheduler.schedule({
            watchdogs.remove(key)
            if (db.isSendPending(row.chatId, row.id)) {
                markSendFailed(
                    row.chatId, row.id,
                    retry = !media && !sendQueued.contains(key) && !sendInFlight.contains(key)
                )
            }
        }, if (media) MEDIA_WATCHDOG_MS else SEND_WATCHDOG_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS))?.cancel(false)
    }

    private fun disarmSendWatchdog(chatId: String, msgId: String) {
        watchdogs.remove(chatId + KEY_SEP + msgId)?.cancel(false)
    }

    private val sendInFlight = ConcurrentHashMap.newKeySet<String>()

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
            if (settledBeforeRekey == row.chatId + KEY_SEP + resultId) {
                settledBeforeRekey = ""
                forgetRetry(row.chatId, row.id)
                db.deleteMessage(row.chatId, row.id)
                notifyChat(row.chatId)
                return true
            }
            db.renameMessage(row.chatId, row.id, resultId)
            moveRetryKey(row.chatId, row.id, resultId)
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

    fun onChatDeletedRemotely(chatId: String, deleteMedia: Boolean) = executor.execute {
        if (wiping) return@execute
        forgetChat(chatId, deleteMedia)
    }

    private fun forgetChat(chatId: String, deleteMedia: Boolean) {
        forgetChatRetries(chatId)
        val mediaPaths = if (deleteMedia) db.chatMediaPaths(chatId) else emptyList()
        db.deleteChat(chatId)
        db.clearScroll(chatId)
        historyAnchor.remove(chatId)
        historyExhausted.remove(chatId)
        appContext?.let { Prefs.clearHistoryComplete(it, chatId) }
        appContext?.let { ctx -> notifyExecutor.execute { Notifications.cancel(ctx, chatId) } }
        notifyChatsChanged()
        if (mediaPaths.isNotEmpty()) mediaExecutor.execute {
            for (path in mediaPaths) {
                if (path.isNotEmpty()) runCatching { java.io.File(path).delete() }
            }
        }
    }

    private val pendingMute = CopyOnWriteArraySet<String>()

    fun setMuted(chatId: String, muted: Boolean) = executor.execute {
        proto(chatId).setMuted(chatId, muted)
    }

    private fun setMutedWa(chatId: String, muted: Boolean) {
        val storedLocally = db.setMuted(chatId, muted)
        if (storedLocally) {
            pendingMute.add(chatId)
            main.postDelayed({ pendingMute.remove(chatId) }, PENDING_MUTE_TTL_MS)
            notifyChatsChanged()
        }
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
        ordered: Boolean = false,
    ) = stageExecutor.execute {
        val row = stageFile(chatId, filePath, fileName, mimeType, caption, quoted)
        val exec = if (ordered) batchExecutor else mediaExecutor
        exec.execute { sendFileBlocking(row, fileName, mimeType, caption, quoted, viewOnce) }
    }

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

    fun viewOnceSupported(chatId: String, kind: String): Boolean =
        kind in proto(chatId).viewOnceKinds(chatId)

    private fun isTgSelfChat(chatId: String): Boolean {
        Tg.selfId().let { if (it.isNotEmpty()) return chatId == it }
        return db.isSelfContact(chatId)
    }

    fun reactionsOf(msg: MessageRow): List<Pair<String, String>> =
        proto(msg.chatId).reactionSenders(msg) ?: db.reactionsOf(msg.chatId, msg.id)

    fun retrySend(msg: MessageRow): Boolean {
        if (!canResend(msg)) return false
        if (sendInFlight.contains(msg.chatId + KEY_SEP + msg.id)) return true
        forgetRetry(msg.chatId, msg.id)
        resend(msg.chatId, msg.id)
        return true
    }

    private fun canResend(msg: MessageRow): Boolean {
        if (msg.msgType in NEEDS_LOCAL_FILE && !fileOnDisk(msg)) return false
        if (msg.msgType == "contact" && msg.text.lines().count { it.isNotBlank() } < 2) return false
        return true
    }

    private fun resend(chatId: String, msgId: String) {
        if (!retrying.add(chatId + KEY_SEP + msgId)) return
        retryWorker.execute {
            try {
                val msg = db.messagesByIds(chatId, listOf(msgId)).firstOrNull() ?: return@execute
                if (msg.sendPending || !msg.sendFailed) return@execute
                if (!canResend(msg)) {
                    retryAttempts.remove(chatId + KEY_SEP + msgId)
                    return@execute
                }
                if (isTg(chatId) && !msgId.startsWith(LOCAL_ID) &&
                    !Tg.cancelQueuedSend(chatId, msgId)
                ) {
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

    private fun forgetChatRetries(chatId: String) {
        val prefix = chatId + KEY_SEP
        retryTasks.keys.filter { it.startsWith(prefix) }
            .forEach { retryTasks.remove(it)?.cancel(false) }
        retryAttempts.keys.removeAll { it.startsWith(prefix) }
    }

    private fun moveRetryKey(chatId: String, oldId: String, newId: String) {
        val old = chatId + KEY_SEP + oldId
        retryTasks.remove(old)?.cancel(false)
        retryAttempts.remove(old)?.let { retryAttempts[chatId + KEY_SEP + newId] = it }
    }

    private fun isStagingPath(filePath: String): Boolean {
        val cacheDir = appContext?.cacheDir ?: return false
        return filePath.startsWith(cacheDir.path + "/")
    }

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
        java.io.File(ctx.cacheDir, "tgdoc").listFiles()?.forEach { dir ->
            if (dir.lastModified() < cutoff) dir.deleteRecursively()
        }
        val previewCutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        java.io.File(ctx.cacheDir, LinkPreview.IMAGE_DIR).listFiles()?.forEach { f ->
            if (f.lastModified() < previewCutoff) f.delete()
        }
    }

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
            val body = (listOf(name) + numbers).joinToString("\n")
            val row = stage(chatId, mintId(chatId), body, "contact")
            protoExecutor(chatId).execute {
                runSend(row) { id -> proto(chatId).sendContact(chatId, id, name, numbers) }
            }
        }

    private val batchExecutor = Executors.newSingleThreadExecutor()

    private const val FORWARD_FILE_WAIT_MS = 120_000L

    private val fileWaits = ConcurrentHashMap<String, CountDownLatch>()

    private val forwardExecutor = Executors.newSingleThreadExecutor()

    fun forwardMessages(
        targetChatIds: List<String>, messages: List<MessageRow>, onDone: (Boolean) -> Unit,
    ) = forwardExecutor.execute {
        val sendable = messages.filter { fetchable(it) }
        val staged = ArrayList<Pair<MessageRow, MessageRow>>(targetChatIds.size * sendable.size)
        for (target in targetChatIds) {
            for (m in sendable) {
                staged.add(m to stage(
                    target, mintId(target), m.text, m.msgType, m.filePath,
                    latitude = m.latitude, longitude = m.longitude, armWatchdog = false
                ))
            }
        }
        var sent = false
        for ((source, row) in staged) {
            val ok = try {
                forwardStagedBlocking(source, row)
            } catch (e: Exception) {
                Log.w(TAG, "forward failed for ${row.chatId}", e)
                markSendFailed(row.chatId, row.id, retry = false)
                false
            }
            if (ok) sent = true
        }
        main.post { onDone(sent) }
    }

    private fun forwardStagedBlocking(source: MessageRow, row: MessageRow): Boolean {
        val ready = withLocalFile(source)
        if (ready == null) {
            markSendFailed(row.chatId, row.id, retry = false)
            return false
        }
        val target = row.chatId
        if (ready.filePath != row.filePath) db.setFileState(target, row.id, ready.filePath, 2)
        // Only once the file is here: the watchdog would give up on a row whose
        // media is still coming down, and mark a send that has not started failed.
        armSendWatchdog(row)
        return sendMediaBlocking(row.copy(filePath = ready.filePath)) { p, id ->
            sendRow(p, target, id, ready, null, emptyList())
        }
    }

    private fun fetchable(m: MessageRow): Boolean = m.msgType !in NEEDS_LOCAL_FILE ||
        m.fileId.isNotEmpty() || storedFile(m.chatId, m.id).isNotEmpty()

    private fun storedFile(chatId: String, msgId: String): String {
        val path = db.fileState(chatId, msgId).first
        return if (path.isNotEmpty() && java.io.File(path).exists()) path else ""
    }

    private fun withLocalFile(m: MessageRow): MessageRow? {
        if (m.msgType !in NEEDS_LOCAL_FILE) return m
        storedFile(m.chatId, m.id).let { if (it.isNotEmpty()) return m.copy(filePath = it) }
        val key = m.chatId + "/" + m.id
        val latch = fileWaits.computeIfAbsent(key) { CountDownLatch(1) }
        try {
            if (!downloadFile(m, userInitiated = true)) return null
            if (storedFile(m.chatId, m.id).isEmpty()) {
                latch.await(FORWARD_FILE_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            fileWaits.remove(key)
        }
        val path = storedFile(m.chatId, m.id)
        return if (path.isEmpty()) null else m.copy(filePath = path)
    }

    private val presenceSubscribed = ConcurrentHashMap<String, Long>()

    private const val PRESENCE_MEMO_MS = 25_000L

    fun subscribePresence(userId: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = presenceSubscribed[userId]
        if (last != null && now - last < PRESENCE_MEMO_MS) return
        presenceSubscribed[userId] = now
        proto(userId).subscribePresence(userId)
    }

    private val userRequestedDownloads = CopyOnWriteArraySet<String>()

    private val autoRetriedFailures: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private const val MAX_RETRY_MEMO = 4096

    fun isDownloading(chatId: String, msgId: String): Boolean =
        downloading.contains("$chatId/$msgId")

    fun downloadFile(msg: MessageRow, userInitiated: Boolean = false): Boolean {
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
        if (!downloading.add(key)) return true
        val started = proto(msg.chatId).startDownload(msg)
        if (!started) downloading.remove(key)
        return started
    }

    fun searchServer(chatId: String, query: String, fromMessageId: Long): Tg.SearchPage? =
        proto(chatId).searchServer(chatId, query, fromMessageId)

    fun searchContext(chatId: String, msgId: String): List<MessageRow> =
        proto(chatId).searchContext(chatId, msgId)

    const val NUMBER_LOOKUP_FAILED = "failed"

    fun resolveNumber(phone: String): String =
        if (connId >= 0 && phone.isNotEmpty()) Wmbridge.resolveNumber(connId, phone)
        else NUMBER_LOOKUP_FAILED

    fun rememberContact(chatId: String, name: String) {
        if (chatId.isEmpty() || name.isEmpty()) return
        executor.execute {
            if (chatId == selfId()) return@execute
            if (db.contactName(chatId) != null) return@execute
            db.upsertContact(
                chatId, name,
                if (isPhoneId(chatId)) chatId.substringBefore('@') else "",
                isSelf = false, isGroup = false, isSaved = true,
            )
            notifyChatsChanged()
        }
    }

    fun searchSlice(chatId: String, msgId: String, newer: Boolean): List<MessageRow> =
        proto(chatId).searchSlice(chatId, msgId, newer)

    fun chatPhotos(chatId: String, msgId: String, newer: Boolean?): List<MessageRow> =
        proto(chatId).chatPhotos(chatId, msgId, newer)

    fun searchMedia(chatId: String, msgId: String): String =
        proto(chatId).searchMedia(chatId, msgId)

    fun requestChatHistory(chatId: String) = proto(chatId).requestHistoryPage(chatId)

    fun isHistoryExhausted(chatId: String): Boolean = proto(chatId).isHistoryExhausted(chatId)

    private class SeekState(
        val chatId: String, val targetId: String, var anchor: Anchor, var pagesLeft: Int,
    ) {
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

    private class HistoryReq(
        val chatId: String, val anchorId: String, val forExport: Boolean, val gen: Long,
        val forSeek: Boolean = false,
    )
    private class Anchor(val id: String, val time: Long, val fromMe: Boolean)

    @Volatile private var historyInFlight: HistoryReq? = null
    private var historyGen = 0L
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
        main.postDelayed({ executor.execute { historyTimeout(gen) } }, HISTORY_TIMEOUT_MS)
        return req
    }

    private fun historyTimeout(gen: Long) {
        val req = historyInFlight ?: return
        if (req.gen != gen) return
        historyInFlight = null
        Log.w(TAG, "history request timed out for ${req.chatId}")
        if (req.forSeek) {
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
        if (req == null || req.chatId != chatId || req.forExport != forExport) return@execute
        historyInFlight = null
        val exhausted = count == 0L || oldestId.isEmpty() || oldestId == req.anchorId
        if (req.forSeek) {
            val s = seek
            if (s == null || s.chatId != chatId) return@execute
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

    private fun writeExport(ex: ChatExport, complete: Boolean) =
        writeExportRows(ex.chatId, ex.uri, complete) {
            val all = LinkedHashMap<String, MessageRow>()
            for (m in ex.collected.values) all[m.id] = m
            ex.collected.clear()
            for (m in db.messages(ex.chatId, Int.MAX_VALUE)) all[m.id] = m
            all.values.sortedBy { it.timeSent }
        }

    internal fun writeExportRows(
        chatId: String, uri: android.net.Uri, complete: Boolean,
        onWritten: () -> Unit = {}, rows: () -> List<MessageRow>,
    ) {
        val ctx = appContext ?: return
        var messages = 0
        val success = try {
            val sorted = rows()
            messages = sorted.size
            ChatExporter.write(ctx, db, chatId, uri, sorted)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "export write failed: $e")
            false
        }
        onWritten()
        releaseExportUri(uri)
        notifyUi { it.onChatExportDone(chatId, messages, complete, success) }
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
        notifyChatsChanged()
    }

    fun selfIdOf(chatId: String): String = Accounts.ofChat(chatId).selfId()

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

    class Member(val chatId: String, val mentionId: String, val name: String)

    fun groupMembers(chatId: String): List<Member> {
        if (!isGroupId(chatId)) return emptyList()
        return proto(chatId).groupMembers(chatId).sortedBy { it.name.lowercase() }
    }

    class PeerInfo(val phone: String, val nickname: String, val about: String)

    fun peerInfo(chatId: String): PeerInfo = proto(chatId).peerInfo(chatId)

    fun getAvatarPath(chatId: String): String =
        proto(chatId).avatarPath(chatId, big = false, cachedOnly = false)

    fun getCachedAvatarPath(chatId: String): String =
        proto(chatId).avatarPath(chatId, big = false, cachedOnly = true)

    fun bestAvatarPath(chatId: String): String =
        proto(chatId).avatarPath(chatId, big = true, cachedOnly = false)
            .ifEmpty { getAvatarPath(chatId) }

    fun openAvatar(activity: android.app.Activity, chatId: String) {
        mediaExecutor.execute {
            val path = bestAvatarPath(chatId)
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

    @Volatile private var wiping = false

    fun logout() = executor.execute {
        chatExport?.let { ex ->
            chatExport = null
            writeExport(ex, complete = false)
        }
        syncAllChat?.let { endSyncAll(it, complete = false) }
        wiping = true
        try {
            Wmbridge.logout(connId)
            db.clearWaData()
        } finally {
            wiping = false
        }
        main.post { if (isWaId(AudioPlayer.currentChatId)) AudioPlayer.stop() }
        selfIdMemo = ""
        if (isWaId(activeChatId)) activeChatId = ""
        autoPlayKey?.let {
            if (isWaId(it.substringBeforeLast('/'))) main.post { disarmAutoPlay() }
        }
        historyInFlight = null
        seek = null
        historyAnchor.clear()
        historyExhausted.clear()
        syncAllChat = null
        syncAllRounds = 0
        pendingMute.clear()
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

    private fun isWaId(id: String): Boolean =
        id.isNotEmpty() && Accounts.ofChat(id).proto == ProtoPicker.WA

    internal fun onFileTransferDone(chatId: String, msgId: String, filePath: String, status: Int) =
        settleTransfer(listOf(chatId), chatId, msgId, filePath, status)

    private fun settleTransfer(
        chatIds: List<String>, playChatId: String, msgId: String, filePath: String, status: Int,
    ) {
        val keys = chatIds.map { "$it/$msgId" }
        for (key in keys) {
            downloading.remove(key)
            fileWaits[key]?.countDown()
        }
        val requested = keys.count { userRequestedDownloads.remove(it) } > 0
        if (status == 3 && requested) toastUi(R.string.download_failed)
        if (autoPlayKey in keys) {
            main.post { disarmAutoPlay() }
            if (status == 2 && filePath.isNotEmpty()) {
                main.post { AudioPlayer.play(filePath, playChatId, msgId) }
            } else {
                endChain()
            }
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
        notifyContactsChangedInternal()
    }

    override fun onChat(chatId: String, name: String, unreadCount: Long, isArchived: Boolean, lastMessageTime: Long) {
        if (wiping) return
        db.upsertChat(chatId, name, isArchived, lastMessageTime)
        notifyChatsChanged()
    }

    override fun onContactsSynced() = executor.execute { reconcileLidChats() }

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
            notify = !isHistory && !isResend,
            fetchMedia = !isHistory,
        )
    }

    internal fun ingestMessage(
        row: MessageRow,
        notify: Boolean,
        fetchMedia: Boolean,
        bump: Boolean = !row.edited,
        afterStore: () -> Unit = {},
    ) {
        if (row.id.isEmpty()) { Log.w(TAG, "message with empty id for ${row.chatId}"); return }
        db.upsertMessage(row)
        afterStore()
        if (bump) {
            val kept = if (row.fromMe) db.storedTime(row.chatId, row.id) else null
            db.bumpChat(row.chatId, kept ?: row.timeSent)
        }
        if (fetchMedia && row.fileId.isNotEmpty() && row.filePath.isEmpty() &&
            (row.msgType in PICTURE_TYPES || row.msgType == "audio")
        ) {
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
        proto(chatId).openChat(chatId)
        AudioPlayer.refreshServiceState()
        val ctx = appContext ?: return
        notifyExecutor.execute { Notifications.cancel(ctx, chatId) }
    }

    fun closeChat(chatId: String, owner: Any? = null) {
        proto(chatId).closeChat(chatId)
        if (owner != null && activeChatOwner !== owner) return
        if (activeChatId == chatId) {
            activeChatId = ""
            activeChatOwner = null
        }
        AudioPlayer.refreshServiceState()
    }

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
        notifyChatRow(chatId, msgId)
    }

    override fun onFileDownloaded(chatId: String, msgId: String, filePath: String, status: Long) {
        if (wiping) return
        var target = chatId
        var updated = db.setFileState(chatId, msgId, filePath, status.toInt())
        if (updated == 0) {
            db.messageChat(msgId)?.let { moved ->
                target = moved
                updated = db.setFileState(moved, msgId, filePath, status.toInt())
            }
        }
        if (updated == 0 && filePath.isNotEmpty()) runCatching { java.io.File(filePath).delete() }
        settleTransfer(listOf(chatId, target).distinct(), target, msgId, filePath, status.toInt())
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
        if (sendInFlight.contains(chatId + KEY_SEP + msgId)) return
        val first = retryAttempts[chatId + KEY_SEP + msgId] == null
        scheduleRetry(chatId, msgId)
        if (first && chatId != activeChatId) toastUi(R.string.send_failed)
    }

    fun onMessageSendOk(chatId: String, msgId: String) {
        if (wiping) return
        if (!db.hasMessage(chatId, msgId)) settledBeforeRekey = chatId + KEY_SEP + msgId
        disarmSendWatchdog(chatId, msgId)
        forgetRetry(chatId, msgId)
        db.clearSendMarks(chatId, msgId)
        notifyChatRow(chatId, msgId)
    }

    @Volatile private var settledBeforeRekey = ""

    override fun onChatReadSelf(chatId: String, msgId: String) {
        if (wiping) return
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
    }

    override fun onPresence(userId: String, isOnline: Boolean, lastSeenTime: Long) {
        online[userId] = isOnline
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
