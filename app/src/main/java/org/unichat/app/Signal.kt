package org.unichat.app

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.unichat.wmbridge.EventListener
import org.unichat.wmbridge.Wmbridge

object Signal : EventListener {
    private const val TAG = "UniChatSg"
    const val PREFIX = "sg:"

    const val PNI_PREFIX = "PNI:"

    private val control = Executors.newSingleThreadExecutor()
    private val ops = Executors.newSingleThreadExecutor()

    private fun ops(work: () -> Unit) = ops.execute { if (linked) work() }
    @Volatile private var started = false
    @Volatile private var linked = false
    @Volatile var state: String = "disconnected"
        private set
    @Volatile private var appContext: Context? = null
    @Volatile private var selfIdMemo: String = ""

    fun isSgId(id: String): Boolean = id.startsWith(PREFIX)

    fun isPniId(id: String): Boolean =
        id.startsWith(PREFIX) && id.regionMatches(PREFIX.length, PNI_PREFIX, 0, PNI_PREFIX.length, true)

    fun hasSession(): Boolean = linked

    fun selfId(): String {
        if (!linked) return ""
        if (selfIdMemo.isEmpty()) selfIdMemo = Wmbridge.signalSelfID()
        return selfIdMemo
    }

    fun init(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        val dir = context.filesDir.absolutePath + "/signal"
        control.execute {
            if (!Wmbridge.signalInit(dir, this)) {
                Log.w(TAG, "signal store init failed")
                started = false
                return@execute
            }
            linked = Wmbridge.signalHasSession()
            Log.i(TAG, "store ready, linked=$linked")
            if (linked && Prefs.protoEnabled(context, ProtoPicker.SG)) connect()
        }
    }

    fun connect() {
        if (!linked) return
        control.execute {
            if (!Wmbridge.signalConnect()) return@execute
            if (Wmbridge.signalSyncContacts()) {
                appContext?.let { Prefs.setSgContactsRestored(it, true) }
            }
            discoverContacts()
        }
    }

    fun startLink() = control.execute { Wmbridge.signalLinkStart("UniChat") }

    fun stopLink() = control.execute { Wmbridge.signalLinkStop() }

    fun refreshContacts() {
        val ctx = appContext ?: return
        if (!linked || !Prefs.protoEnabled(ctx, ProtoPicker.SG)) return
        val now = System.currentTimeMillis()
        if (now - lastDiscovery < DISCOVERY_GAP_MS) return
        discoverContacts()
    }

    @Volatile private var lastDiscovery = 0L
    private const val DISCOVERY_GAP_MS = 60_000L

    fun discoverContacts() {
        val ctx = appContext ?: return
        lastDiscovery = System.currentTimeMillis()
        control.execute {
            val entries = PhoneBook.allEntries(ctx)
            if (entries.isEmpty()) return@execute
            val byNumber = entries.associate { PhoneBook.digitsOf(it.number) to it.name }
            namesByNumber = byNumber
            val csv = byNumber.keys.joinToString(",")
            val err = Wmbridge.signalDiscoverContacts(csv)
            if (err.isNotEmpty()) Log.w(TAG, "contact discovery: $err")
        }
    }

    @Volatile private var namesByNumber: Map<String, String> = emptyMap()

    fun disconnect() = control.execute { Wmbridge.signalDisconnect() }


    fun logout() = control.execute {
        linked = false
        runCatching { ops.submit(Runnable {}).get(5, TimeUnit.SECONDS) }
        runCatching { Io.files.submit(Runnable {}).get(5, TimeUnit.SECONDS) }
        Wmbridge.signalLogout()
        started = false
        selfIdMemo = ""
        appContext?.let { ctx ->
            runCatching { java.io.File(ctx.filesDir, "signal").deleteRecursively() }
            if (Wmbridge.signalInit(ctx.filesDir.absolutePath + "/signal", this)) started = true
        }
        appContext?.let { Prefs.setSgContactsRestored(it, false) }
        Bridge.db.clearSignalData()
        Bridge.notifyChatsChanged()
    }

    fun errorText(ctx: Context, code: String): String {
        val arg = code.substringAfter(':', "")
        return when (code.substringBefore(':')) {
            "not_initialised" -> ctx.getString(R.string.signal_err_not_initialised)
            "no_session" -> ctx.getString(R.string.signal_err_no_session)
            "code_rejected" -> ctx.getString(R.string.signal_err_code_rejected)
            "not_registered" -> ctx.getString(R.string.signal_err_not_registered)
            "manifest_locked" -> ctx.getString(R.string.signal_err_manifest_locked)
            "store_failed" -> ctx.getString(R.string.signal_err_store_failed)
            "no_backup" -> ctx.getString(R.string.signal_err_no_backup)
            "wrong_pin" -> arg.toIntOrNull()?.let {
                ctx.resources.getQuantityString(R.plurals.signal_err_wrong_pin, it, it)
            } ?: ctx.getString(R.string.signal_err_wrong_pin_unknown)
            "upstream" -> arg
            else -> code
        }
    }

    fun registerStart(number: String, onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRegisterStart(number)
        Bridge.runOnUi { onDone(err) }
    }

    fun needsCaptcha(): Boolean = Wmbridge.signalRegisterNeedsCaptcha()

    fun registerSubmitCaptcha(token: String, onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRegisterSubmitCaptcha(token)
        Bridge.runOnUi { onDone(err) }
    }

    fun registerRequestCode(onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRegisterRequestCode("sms")
        Bridge.runOnUi { onDone(err) }
    }

    fun registerSubmitCode(number: String, code: String, onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRegisterSubmitCode(number, code)
        if (err.isEmpty()) {
            linked = true
            appContext?.let { Prefs.setSgContactsRestored(it, false) }
            state = "connecting"
            Bridge.notifyAccountState(ProtoPicker.SG, state)
        }
        Bridge.runOnUi { onDone(err) }
        if (err.isEmpty()) connect()
    }

    fun myName(): String =
        if (linked) Bridge.db.contactName(selfId()).orEmpty() else ""

    fun myPhone(): String = if (linked) Wmbridge.signalMyPhone() else ""

    fun fetchAbout(onResult: (String) -> Unit) = control.execute {
        val about = Wmbridge.signalMyAbout()
        Bridge.runOnUi { onResult(about) }
    }

    fun setProfile(name: String?, about: String?, onResult: (Boolean) -> Unit) = control.execute {
        val currentName = name ?: Wmbridge.signalMyName()
        val currentAbout = about ?: Wmbridge.signalMyAbout()
        val discoverable = appContext?.let { Prefs.sgDiscoverable(it) } ?: true
        val ok = Wmbridge.signalSetProfile(currentName, currentAbout, discoverable)
        Bridge.runOnUi { onResult(ok) }
    }

    fun setDiscoverable(discoverable: Boolean, onResult: (Boolean) -> Unit) = control.execute {
        val ok = Wmbridge.signalSetDiscoverable(discoverable)
        Bridge.runOnUi { onResult(ok) }
    }

    fun privacySettings(): Map<String, String> {
        val ctx = appContext ?: return emptyMap()
        return mapOf(
            "discoverable" to if (Prefs.sgDiscoverable(ctx)) "all" else "none",
            "readreceipts" to if (Prefs.sgReadReceipts(ctx)) "all" else "none",
        )
    }

    fun setPrivacy(name: String, value: String, onResult: (Boolean) -> Unit) {
        val on = value == "all"
        val ctx = appContext
        if (ctx == null) {
            Bridge.runOnUi { onResult(false) }
            return
        }
        when (name) {
            "readreceipts" -> {
                Prefs.setSgReadReceipts(ctx, on)
                Bridge.runOnUi { onResult(true) }
            }
            "discoverable" -> setDiscoverable(on) { ok ->
                if (ok) Prefs.setSgDiscoverable(ctx, on)
                onResult(ok)
            }
            else -> Bridge.runOnUi { onResult(false) }
        }
    }

    fun restoreFromPin(pin: String, onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRestoreFromPIN(pin)
        if (err.isEmpty()) {
            appContext?.let { Prefs.setSgContactsRestored(it, true) }
            Bridge.notifyAccountState(ProtoPicker.SG, state)
        }
        Bridge.runOnUi { onDone(err) }
    }

    fun sendLocation(chatId: String, msgId: String, latitude: Double, longitude: Double): String =
        Wmbridge.signalSendLocation(chatId, msgId, latitude, longitude)

    fun sendText(chatId: String, msgId: String, text: String, quoted: MessageRow?): String {
        val (body, styles) = styled(text)
        return Wmbridge.signalSendTextQuoted(
            chatId, msgId, body, styles,
            quoted?.id.orEmpty(), quoted?.text.orEmpty(), quoted?.senderId.orEmpty()
        )
    }

    private fun styled(text: String): Pair<String, String> {
        val (plain, marks) = Markup.parse(text)
        val styles = marks.joinToString(";") {
            "${it.start},${it.end - it.start},${if (it.bold) "b" else "i"}"
        }
        return plain to styles
    }

    fun react(msg: MessageRow, emoji: String) =
        ops { Wmbridge.signalReact(msg.chatId, msg.id, msg.senderId, emoji) }

    fun delete(chatId: String, msgId: String) =
        ops { Wmbridge.signalDelete(chatId, msgId) }

    fun deleteChat(chatId: String, recent: List<MessageRow>) = ops {
        val encoded = recent.joinToString("\n") { "${it.senderId}|${it.id}" }
        if (!Wmbridge.signalDeleteChat(chatId, encoded)) {
            Bridge.toastUi(R.string.delete_chat_failed)
        }
    }

    fun edit(chatId: String, msgId: String, newText: String, fileId: String): Boolean {
        val (body, styles) = styled(newText)
        val fileIds =
            if (fileId.isEmpty()) ""
            else (listOf(fileId) + Bridge.db.albumFileIds(chatId, msgId)).joinToString("\n")
        return Wmbridge.signalEdit(chatId, msgId, body, styles, fileIds)
    }

    fun markChatRead(chatId: String) = ops {
        val latest = Bridge.db.latestUnread(chatId) ?: return@ops
        Bridge.db.markChatRead(chatId)
        Bridge.notifyChatsChanged()
        val ctx = appContext
        if (ctx == null || Prefs.sgReadReceipts(ctx)) Wmbridge.signalMarkRead(chatId, latest.id)
    }

    fun setTyping(chatId: String, typing: Boolean) =
        ops { Wmbridge.signalSetTyping(chatId, typing) }

    fun sendAttachment(
        chatId: String, msgId: String, path: String, caption: String, mime: String,
        voiceNote: Boolean = false,
    ): String = Wmbridge.signalSendAttachment(chatId, msgId, path, caption, mime, voiceNote)

    fun sendContact(chatId: String, msgId: String, name: String, numbers: List<String>): String =
        Wmbridge.signalSendContact(chatId, msgId, name, numbers.joinToString(","))

    fun lookupNumber(number: String): String {
        val digits = PhoneBook.digitsOf(number)
        Bridge.db.chatIdByPhone(digits, PREFIX)?.let { return it }
        val found = Wmbridge.signalLookupNumber(digits)
        if (found.isEmpty()) return ""
        Log.i(TAG, "number resolved to $found")
        return found
    }

    fun startDownload(msg: MessageRow): Boolean {
        if (msg.fileId.isEmpty()) return false
        if (!linked) control.execute { queueDownload(msg) } else queueDownload(msg)
        return true
    }

    private fun queueDownload(msg: MessageRow) = Io.files.execute {
        if (linked) Wmbridge.signalDownloadAttachment(msg.chatId, msg.id, msg.fileId)
        else Bridge.onFileTransferDone(msg.chatId, msg.id, "", 3)
    }

    override fun onStateChanged(state: String) {
        this.state = state
        if (state == "linked") {
            linked = Wmbridge.signalHasSession()
            selfIdMemo = ""
            Bridge.notifyAccountState(ProtoPicker.SG, state)
            connect()
            return
        }
        if (state == "logged_out") {
            linked = false
            selfIdMemo = ""
        }
        Bridge.notifyAccountState(ProtoPicker.SG, state)
    }

    override fun onContact(
        id: String, name: String, phone: String, isSelf: Boolean, isGroup: Boolean, isSaved: Boolean,
    ) {
        val label = name.ifEmpty { namesByNumber[PhoneBook.digitsOf(phone)].orEmpty() }
        val stored = Bridge.db.contactName(id).orEmpty()
        val worthKeeping = if (
            stored.isNotEmpty() && phone.isNotEmpty() &&
            PhoneBook.digitsOf(stored) == PhoneBook.digitsOf(phone)
        ) "" else stored
        val keep = label.ifEmpty { worthKeeping }
        Bridge.db.upsertContact(id, keep, phone, isSelf, isGroup, isSaved)
        Bridge.notifyChatsChanged()
    }

    override fun onChat(
        chatId: String, name: String, unreadCount: Long, isArchived: Boolean, lastMessageTime: Long,
    ) {
        if (name.isEmpty()) {
            Bridge.db.bumpChat(chatId, lastMessageTime)
        } else {
            Bridge.db.upsertChat(chatId, name, isArchived, lastMessageTime)
        }
        Bridge.notifyChatsChanged()
    }

    override fun onMessage(
        chatId: String, msgId: String, senderId: String, text: String,
        fromMe: Boolean, timeSent: Long, isRead: Boolean, msgType: String, fileId: String,
        latitude: Double, longitude: Double,
        isHistory: Boolean, isEdited: Boolean, quotedId: String, quotedText: String,
        quotedType: String, senderName: String, isForwarded: Boolean,
    ) {
        Bridge.ingestMessage(
            MessageRow(
                msgId, chatId, senderId, text, fromMe, timeSent, isRead, msgType, fileId,
                edited = isEdited, quotedId = quotedId, quotedText = quotedText,
                quotedType = quotedType, senderName = senderName,
                forwarded = isForwarded, latitude = latitude, longitude = longitude
            ),
            notify = !isHistory,
            fetchMedia = !fromMe,
        )
    }

    override fun onChatState(chatId: String, userId: String, state: String) =
        Bridge.onChatState(chatId, userId, state)

    override fun onLog(level: Long, message: String) {
        when (level) {
            3L -> Log.e(TAG, message)
            2L -> Log.w(TAG, message)
            else -> Log.d(TAG, message)
        }
    }

    override fun onQrCode(code: String) = Bridge.notifyQrCode(ProtoPicker.SG, code)
    override fun onPairError(code: String) = Bridge.notifyPairError(ProtoPicker.SG, code)

    override fun onPairCode(code: String) {}
    override fun onContactsSynced() = Bridge.notifyChatsChanged()
    override fun onMessageDeleted(chatId: String, msgId: String) {
        if (msgId.isEmpty()) return
        Bridge.db.deleteMessage(chatId, msgId)
        Bridge.notifyChat(chatId)
        Bridge.notifyChatsChanged()
    }

    override fun onReaction(chatId: String, msgId: String, senderId: String, emoji: String) {
        if (emoji.isEmpty()) {
            Bridge.db.deleteReaction(chatId, msgId, senderId)
        } else {
            Bridge.db.upsertReaction(chatId, msgId, senderId, emoji)
        }
        Bridge.notifyChat(chatId)
        Bridge.notifyChatsChanged()
    }
    override fun onFileDownloaded(chatId: String, msgId: String, filePath: String, status: Long) {
        if (Bridge.db.setFileState(chatId, msgId, filePath, status.toInt()) == 0 &&
            filePath.isNotEmpty()
        ) {
            runCatching { java.io.File(filePath).delete() }
        }
        Bridge.onFileTransferDone(chatId, msgId, filePath, status.toInt())
        Bridge.notifyChat(chatId)
    }

    override fun onDownloadProgress(chatId: String, msgId: String, pct: Long) =
        Bridge.postDownloadProgress(chatId, msgId, pct.toInt())
    override fun onMessageRead(chatId: String, msgId: String) {
        val target = Bridge.db.messageChat(msgId, PREFIX, fromMe = true) ?: return
        Bridge.onMessageRead(target, msgId)
    }

    override fun onMessagePlayed(chatId: String, msgId: String) {
        val target = Bridge.db.messageChat(msgId, PREFIX, fromMe = true) ?: return
        Bridge.onMessagePlayed(target, msgId)
    }

    override fun onMessageSendFailed(chatId: String, msgId: String) =
        Bridge.onMessageSendFailed(chatId, msgId)

    override fun onChatReadSelf(chatId: String, msgId: String) {
        val target = Bridge.db.messageChat(msgId, PREFIX, fromMe = false) ?: return
        Bridge.onChatReadSelf(target, msgId)
    }
    override fun onChatDeleted(chatId: String, deleteMedia: Boolean) =
        Bridge.onChatDeletedRemotely(chatId, deleteMedia)

    override fun onMute(chatId: String, muted: Boolean) {}
    override fun onPresence(userId: String, isOnline: Boolean, lastSeen: Long) {}
    override fun onSyncProgress(progress: Long) {}
    override fun onChatHistoryDelivered(
        chatId: String, count: Long, forExport: Boolean, oldestId: String,
        oldestTime: Long, oldestFromMe: Boolean,
    ) {}
    override fun onExportMessage(
        chatId: String, msgId: String, senderId: String, text: String, fromMe: Boolean,
        timeSent: Long, msgType: String, fileId: String, senderName: String, isEdited: Boolean,
    ) {}
}
