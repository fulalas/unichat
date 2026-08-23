package org.unichat.app

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.unichat.wmbridge.EventListener
import org.unichat.wmbridge.Wmbridge

/**
 * Signal, as a third protocol beside WhatsApp and Telegram.
 *
 * The Go half lives in the same aar as whatsmeow (see gobridge/signal.go), so
 * this talks to Wmbridge like Bridge does, but keeps its own EventListener and
 * writes into the shared Db under an "sg:" prefix — the same shape Tg uses.
 */
object Signal : EventListener {
    private const val TAG = "UniChatSg"
    const val PREFIX = "sg:"

    /** Marks a chat addressed by PNI because discovery returned no ACI.
     *  Uppercase, matching how signalmeow spells incoming service ids. */
    const val PNI_PREFIX = "PNI:"

    // Two threads, not one: connect() blocks for up to 30 s waiting for the
    // socket and contact discovery is a round trip over it, so sharing a single
    // thread with reactions and read receipts made a reaction sent seconds
    // after launch wait for all of startup. Each stays single-threaded, because
    // both halves depend on their own calls staying in order — registration
    // steps on [control], and a read receipt after the read on [ops].
    private val control = Executors.newSingleThreadExecutor()
    private val ops = Executors.newSingleThreadExecutor()
    // Written on [control], read from the UI thread via hasSession(); without
    // volatile the main thread could keep seeing a linked account as absent.
    @Volatile private var started = false
    @Volatile private var linked = false
    @Volatile var state: String = "disconnected"
        private set
    @Volatile private var appContext: Context? = null
    // Cached because selfProtocol() asks for it once per chat row, on the main
    // thread, and it only changes at register/logout. Bridge memoises the
    // WhatsApp id for the same reason.
    @Volatile private var selfIdMemo: String = ""

    fun isSgId(id: String): Boolean = id.startsWith(PREFIX)

    fun hasSession(): Boolean = linked

    fun selfId(): String {
        if (!linked) return ""
        if (selfIdMemo.isEmpty()) selfIdMemo = Wmbridge.signalSelfID()
        return selfIdMemo
    }

    /**
     * Opens the Signal store. Cheap when the device was never linked, so it can
     * run on every start; connecting is separate.
     */
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
            if (Wmbridge.signalConnect()) discoverContacts()
        }
    }

    /**
     * Asks Signal which address-book numbers have accounts. A registered
     * primary starts with no contact list at all — the storage-service manifest
     * is encrypted with the previous master key, which registering replaced —
     * so this is the only way the account learns who is on Signal.
     */
    fun discoverContacts() {
        val ctx = appContext ?: return
        control.execute {
            val entries = PhoneBook.allEntries(ctx)
            if (entries.isEmpty()) return@execute
            // Keep the address-book name against the number: discovery answers
            // with an ACI and the number it matched, never a name.
            val byNumber = entries.associate { PhoneBook.digitsOf(it.number) to it.name }
            namesByNumber = byNumber
            val csv = byNumber.keys.joinToString(",")
            val err = Wmbridge.signalDiscoverContacts(csv)
            if (err.isNotEmpty()) Log.w(TAG, "contact discovery: $err")
        }
    }

    @Volatile private var namesByNumber: Map<String, String> = emptyMap()

    /** Drops the socket but keeps the link, for the Manage accounts pause. */
    fun disconnect() = control.execute { Wmbridge.signalDisconnect() }


    fun logout() = control.execute {
        // Before signalLogout, which is what closes the store: a reaction or
        // read receipt queued on [ops] is a blocking network send still writing
        // through that handle. They shared one thread before the split, so this
        // ordering used to be free. Capped, because the send may be waiting on a
        // network that is not coming back and the account still has to go.
        runCatching { ops.submit(Runnable {}).get(5, TimeUnit.SECONDS) }
        Wmbridge.signalLogout()
        linked = false
        started = false
        selfIdMemo = ""
        // On [control], after the Go side has closed the store: the media
        // and session tree is ours to remove and nothing else is holding it.
        // Then reopen, so registering again without restarting the app has a
        // store to write into rather than reporting "signal not initialised".
        appContext?.let { ctx ->
            runCatching { java.io.File(ctx.filesDir, "signal").deleteRecursively() }
            if (Wmbridge.signalInit(ctx.filesDir.absolutePath + "/signal", this)) started = true
        }
        // Drop this protocol's rows the way a WhatsApp or Telegram unlink does,
        // or the chat list keeps listing chats no account can open any more.
        Bridge.db.clearSignalData()
        Bridge.notifyChatsChanged()
    }

    /**
     * Turns a bridge error code into text for the user. The Go side returns
     * codes rather than sentences so they can be translated here; an
     * "upstream:" payload is a server or signalmeow message with no code of its
     * own and is shown as it came.
     */
    fun errorText(ctx: Context, code: String): String {
        val arg = code.substringAfter(':', "")
        return when (code.substringBefore(':')) {
            "not_initialised" -> ctx.getString(R.string.signal_err_not_initialised)
            "no_session" -> ctx.getString(R.string.signal_err_no_session)
            "code_rejected" -> ctx.getString(R.string.signal_err_code_rejected)
            "not_registered" -> ctx.getString(R.string.signal_err_not_registered)
            "no_master_key" -> ctx.getString(R.string.signal_err_no_master_key)
            "no_manifest" -> ctx.getString(R.string.signal_err_no_manifest)
            "manifest_locked" -> ctx.getString(R.string.signal_err_manifest_locked)
            "store_failed" -> ctx.getString(R.string.signal_err_store_failed)
            "no_backup" -> ctx.getString(R.string.signal_err_no_backup)
            "wrong_pin" -> ctx.getString(R.string.signal_err_wrong_pin, arg)
            "upstream" -> arg
            else -> code
        }
    }

    // --- Registration (primary device) ----------------------------------
    // Each call blocks on the network, so they all run on [control] and report
    // back on the main thread. An empty string means success; anything else is
    // an error code for errorText().

    fun registerStart(number: String, onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRegisterStart(number)
        Bridge.runOnUi { onDone(err) }
    }

    fun needsCaptcha(): Boolean = Wmbridge.signalRegisterNeedsCaptcha()

    fun registerSubmitCaptcha(token: String, onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRegisterSubmitCaptcha(token)
        Bridge.runOnUi { onDone(err) }
    }

    /** Always SMS: there is no voice-call option in the UI. */
    fun registerRequestCode(onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRegisterRequestCode("sms")
        Bridge.runOnUi { onDone(err) }
    }

    fun registerSubmitCode(number: String, code: String, onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRegisterSubmitCode(number, code)
        if (err.isEmpty()) {
            linked = true
            // Not the state left over from before registering — which after a
            // logout in the same run is "logged_out", the opposite of what just
            // happened. connect() below is about to dial.
            state = "connecting"
            Bridge.notifyAccountState(ProtoPicker.SG, state)
        }
        Bridge.runOnUi { onDone(err) }
        // connect(), not the raw bridge call: it is what honours the pause
        // setting and kicks off contact discovery, which a fresh registration
        // needs more than anything — there is no contact list to sync.
        if (err.isEmpty()) connect()
    }

    // --- Profile and privacy ---------------------------------------------

    // Served from the contact row publishSelfContact() already wrote, so the
    // profile screen does not make an HTTPS profile fetch from onCreate.
    fun myName(): String =
        if (linked) Bridge.db.contactName(selfId()).orEmpty() else ""

    fun myPhone(): String = if (linked) Wmbridge.signalMyPhone() else ""

    fun fetchAbout(onResult: (String) -> Unit) = control.execute {
        val about = Wmbridge.signalMyAbout()
        Bridge.runOnUi { onResult(about) }
    }

    /**
     * Signal's profile endpoint replaces every field at once, so a name-only or
     * about-only edit has to resend the other one or it is blanked.
     */
    fun setProfile(name: String?, about: String?, onResult: (Boolean) -> Unit) = control.execute {
        val currentName = name ?: Wmbridge.signalMyName()
        val currentAbout = about ?: Wmbridge.signalMyAbout()
        // Discoverability travels with this call because minting a missing
        // profile key rewrites the account attributes, which carry it.
        val discoverable = appContext?.let { Prefs.sgDiscoverable(it) } ?: true
        val ok = Wmbridge.signalSetProfile(currentName, currentAbout, discoverable)
        Bridge.runOnUi { onResult(ok) }
    }

    fun setDiscoverable(discoverable: Boolean, onResult: (Boolean) -> Unit) = control.execute {
        val ok = Wmbridge.signalSetDiscoverable(discoverable)
        Bridge.runOnUi { onResult(ok) }
    }

    fun restoreFromPin(pin: String, onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRestoreFromPIN(pin)
        Bridge.runOnUi { onDone(err) }
    }

    fun sendText(chatId: String, text: String, quoted: MessageRow?): Boolean =
        Wmbridge.signalSendTextQuoted(
            chatId, text,
            quoted?.id.orEmpty(), quoted?.text.orEmpty(), quoted?.senderId.orEmpty()
        ).isNotEmpty()

    fun react(msg: MessageRow, emoji: String) =
        ops.execute { Wmbridge.signalReact(msg.chatId, msg.id, msg.senderId, emoji) }

    fun delete(chatId: String, msgId: String) =
        ops.execute { Wmbridge.signalDelete(chatId, msgId) }

    fun edit(chatId: String, msgId: String, newText: String): Boolean =
        Wmbridge.signalEdit(chatId, msgId, newText)

    /**
     * Marks the chat read locally and acks the newest unread message, matching
     * what the WhatsApp path does. Nothing happens when there is nothing new.
     */
    fun markChatRead(chatId: String) = ops.execute {
        val latest = Bridge.db.latestUnread(chatId) ?: return@execute
        Bridge.db.markChatRead(chatId)
        Bridge.notifyChatsChanged()
        val ctx = appContext
        if (ctx == null || Prefs.sgReadReceipts(ctx)) Wmbridge.signalMarkRead(chatId, latest.id)
    }

    fun setTyping(chatId: String, typing: Boolean) =
        ops.execute { Wmbridge.signalSetTyping(chatId, typing) }

    fun sendAttachment(
        chatId: String, path: String, caption: String, mime: String,
        voiceNote: Boolean = false,
    ): Boolean = Wmbridge.signalSendAttachment(chatId, path, caption, mime, voiceNote).isNotEmpty()

    fun sendContact(chatId: String, name: String, numbers: List<String>): Boolean =
        Wmbridge.signalSendContact(chatId, name, numbers.joinToString(",")).isNotEmpty()

    /**
     * Resolves a phone number to a Signal chat id. Blocking; worker threads
     * only. "" when the number is not on Signal.
     *
     * An existing chat wins over whatever discovery answers: the server may
     * return a PNI for someone whose chat is keyed by the ACI learned when they
     * first messaged us, and taking the lookup's word for it opened a second,
     * empty chat next to the real one.
     */
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
        Io.files.execute { Wmbridge.signalDownloadAttachment(msg.chatId, msg.id, msg.fileId) }
        return true
    }

    // --- EventListener ---------------------------------------------------
    // Only the callbacks Signal actually raises are implemented; the rest are
    // part of the shared WhatsApp-shaped interface and stay no-ops.

    override fun onStateChanged(state: String) {
        this.state = state
        if (state == "logged_out") {
            linked = false
            selfIdMemo = ""
        }
        Bridge.notifyAccountState(ProtoPicker.SG, state)
    }

    override fun onContact(
        id: String, name: String, phone: String, isSelf: Boolean, isGroup: Boolean, isSaved: Boolean,
    ) {
        // Discovery reports a number with no name; the address book is what the
        // user actually recognises, so prefer it over an empty label.
        val label = name.ifEmpty { namesByNumber[PhoneBook.digitsOf(phone)].orEmpty() }
        Bridge.db.upsertContact(id, label, phone, isSelf, isGroup, isSaved)
        Bridge.notifyChatsChanged()
    }

    override fun onChat(
        chatId: String, name: String, unreadCount: Long, isArchived: Boolean, lastMessageTime: Long,
    ) {
        // An empty name means "only the timestamp changed" (a send we echoed).
        // upsertChat would write the blank over a resolved title.
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
                quotedType = quotedType, senderName = senderName, forwarded = isForwarded
            ),
            notify = !isHistory,
            // Never for our own send: the file is already on this device and the
            // caller hands its path over right after. Downloading it back raced
            // with that, and the bubble ended up pointing at nothing — a voice
            // note you could see but not play.
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

    // Companion linking is gone; this account is a primary. Part of the
    // WhatsApp-shaped listener interface, never raised for Signal.
    override fun onQrCode(code: String) {}
    override fun onPairCode(code: String) {}
    override fun onPairError(code: String) {}
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
        // The chat list previews a reaction on the newest message, so it
        // changes too, not just the open chat.
        Bridge.notifyChatsChanged()
    }
    /**
     * Writes the local path onto the message row. Left as a no-op stub this
     * whole time, which is why a Signal voice note appeared in the chat but
     * could not be played, and why received media never rendered: the row's
     * file_path stayed empty no matter how the transfer went.
     */
    override fun onFileDownloaded(chatId: String, msgId: String, filePath: String, status: Long) {
        if (Bridge.db.setFileState(chatId, msgId, filePath, status.toInt()) == 0 &&
            filePath.isNotEmpty()
        ) {
            // No row to attach it to, so the file would sit on disk unreachable.
            runCatching { java.io.File(filePath).delete() }
        }
        // Releases the in-flight claim Bridge.downloadFile took; without it a
        // failed download stayed unretryable for the rest of the run. Also what
        // reports the failure and resumes playback the user asked for.
        Bridge.onFileTransferDone(chatId, msgId, filePath, status.toInt())
        Bridge.notifyChat(chatId)
    }

    override fun onDownloadProgress(chatId: String, msgId: String, pct: Long) =
        Bridge.postDownloadProgress(chatId, msgId, pct.toInt())
    override fun onMessageRead(chatId: String, msgId: String) {}
    override fun onMessagePlayed(chatId: String, msgId: String) {}
    override fun onChatReadSelf(chatId: String) {}
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
