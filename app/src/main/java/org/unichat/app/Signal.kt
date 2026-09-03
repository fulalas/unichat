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

    /** Uppercase, matching how signalmeow spells incoming service ids. */
    const val PNI_PREFIX = "PNI:"

    // Two threads, not one: connect() blocks for up to 30 s waiting for the
    // socket and contact discovery is a round trip over it, so sharing a single
    // thread with reactions and read receipts made a reaction sent seconds
    // after launch wait for all of startup. Each stays single-threaded, because
    // both halves depend on their own calls staying in order — registration
    // steps on [control], and a read receipt after the read on [ops].
    private val control = Executors.newSingleThreadExecutor()
    private val ops = Executors.newSingleThreadExecutor()

    /** Skipped once the account is gone: logout waits for what
     *  is already running, but a task queued behind it would otherwise reach a
     *  closed store, or a tree that is no longer there. */
    private fun ops(work: () -> Unit) = ops.execute { if (linked) work() }
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

    // The bridge writes "PNI:", older rows carry a lowercase one.
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
            // The stored contact list first: it is the only source for people
            // who do not publish their number, and it is what the official app
            // shows. Then the address book, for anyone it does not cover.
            //
            // Only ever promoted here: a transient storage-service failure
            // would otherwise put the row back to offering a restore that has
            // already happened. Registering again clears it instead.
            if (Wmbridge.signalSyncContacts()) {
                appContext?.let { Prefs.setSgContactsRestored(it, true) }
            }
            discoverContacts()
        }
    }

    /**
     * A linked device is handed the account's own key, so it can read the
     * contact list Signal keeps for the account. Registering instead makes this
     * app the account's main device with a fresh key, which leaves that list
     * unreadable — only people findable by number ever show up.
     */
    fun startLink() = control.execute { Wmbridge.signalLinkStart("UniChat") }

    fun stopLink() = control.execute { Wmbridge.signalLinkStop() }

    /**
     * Discovery used to run only on connect, so anyone saved to the phone after
     * that was invisible until the app was restarted. Throttled: it is a round
     * trip over the socket and the address book rarely changes twice in a
     * minute.
     */
    fun refreshContacts() {
        val ctx = appContext ?: return
        // Paused means off the network. Discovery is its own request and works
        // while disconnected, so without this a paused account went on sending
        // the whole address book to Signal every time the app was opened.
        if (!linked || !Prefs.protoEnabled(ctx, ProtoPicker.SG)) return
        val now = System.currentTimeMillis()
        if (now - lastDiscovery < DISCOVERY_GAP_MS) return
        discoverContacts()
    }

    @Volatile private var lastDiscovery = 0L
    private const val DISCOVERY_GAP_MS = 60_000L

    /**
     * A registered primary starts with no contact list at all — the
     * storage-service manifest is encrypted with the previous master key, which
     * registering replaced — so discovery is the only way the account learns
     * who is on Signal.
     */
    fun discoverContacts() {
        val ctx = appContext ?: return
        lastDiscovery = System.currentTimeMillis()
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

    fun disconnect() = control.execute { Wmbridge.signalDisconnect() }


    fun logout() = control.execute {
        // Cleared first, so anything still queued on [ops] turns into a no-op,
        // then wait for what is already running: signalLogout below closes the
        // store those sends write through, and they shared one thread with this
        // before the split, so the ordering used to be free. The wait is capped
        // because a send may be stuck on a network that is not coming back, and
        // the account still has to go.
        linked = false
        runCatching { ops.submit(Runnable {}).get(5, TimeUnit.SECONDS) }
        // Io.files too: startDownload does not run on [ops], so draining only
        // that queue still left an attachment fetch calling into signalmeow
        // while signalLogout closed the store, and writing into signal/media
        // while the tree below was being removed.
        runCatching { Io.files.submit(Runnable {}).get(5, TimeUnit.SECONDS) }
        Wmbridge.signalLogout()
        started = false
        selfIdMemo = ""
        // Reopened, so registering again without restarting the app has a
        // store to write into rather than reporting "signal not initialised".
        appContext?.let { ctx ->
            runCatching { java.io.File(ctx.filesDir, "signal").deleteRecursively() }
            if (Wmbridge.signalInit(ctx.filesDir.absolutePath + "/signal", this)) started = true
        }
        appContext?.let { Prefs.setSgContactsRestored(it, false) }
        // Drop this protocol's rows the way a WhatsApp or Telegram unlink does,
        // or the chat list keeps listing chats no account can open any more.
        Bridge.db.clearSignalData()
        Bridge.notifyChatsChanged()
    }

    /**
     * An "upstream:" payload is a server or signalmeow message with no code of
     * its own and is shown as it came.
     */
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

    /** Always SMS: there is no voice-call option in the UI. */
    fun registerRequestCode(onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRegisterRequestCode("sms")
        Bridge.runOnUi { onDone(err) }
    }

    fun registerSubmitCode(number: String, code: String, onDone: (String) -> Unit) = control.execute {
        val err = Wmbridge.signalRegisterSubmitCode(number, code)
        if (err.isEmpty()) {
            linked = true
            // A new account key: whatever the old one could read, this one
            // cannot, so the restore is on offer again.
            appContext?.let { Prefs.setSgContactsRestored(it, false) }
            // Not the state left over from before registering — which after a
            // logout in the same run is "logged_out", the opposite of what just
            // happened. connect() below is about to dial.
            state = "connecting"
            Bridge.notifyAccountState(ProtoPicker.SG, state)
        }
        Bridge.runOnUi { onDone(err) }
        // connect(), not the raw bridge call: it is what honours the pause
        // setting and kicks off contact discovery, which a fresh registration
        // has no contact list to sync without.
        if (err.isEmpty()) connect()
    }

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

    /**
     * Read receipts and typing indicators are honoured locally rather than
     * published: they live in the storage-service account record, which this
     * account cannot write yet. Discoverability is the one the server holds.
     */
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
            // Stored only once the server took it, or the switch would claim a
            // change the account never made.
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

    /**
     * Signal takes a plain body plus style ranges, offsets in UTF-16 units —
     * which is what a Kotlin string index already is. Sent with the `*bold*` /
     * `_italic_` markers still in, the other side showed them as literal
     * asterisks and underscores.
     */
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

    /**
     * Blocking; worker threads only.
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
        // Never refused for a store that has simply not opened yet: [linked] is
        // published at the end of init's task, and a cold start from a Signal
        // notification binds its bubbles first, so answering false there dropped
        // the request with no transfer, no status and no toast — the attachment
        // stayed undownloaded until tapped again. Queued behind init on the very
        // thread that sets the flag instead; an account that really is gone then
        // reports the failure from inside the task.
        if (!linked) control.execute { queueDownload(msg) } else queueDownload(msg)
        return true
    }

    private fun queueDownload(msg: MessageRow) = Io.files.execute {
        // Re-checked inside: logout clears the flag and then closes the store
        // and deletes the tree this writes into, so a task queued before it must
        // not run afterwards.
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
        // Discovery reports a number with no name; the address book is what the
        // user actually recognises, so prefer it over an empty label.
        val label = name.ifEmpty { namesByNumber[PhoneBook.digitsOf(phone)].orEmpty() }
        // Never blank out a name already stored: these arrive repeatedly (the
        // self row on every connect, contacts on every storage sync), and an
        // empty one would retitle the chat with its bare number or id.
        val stored = Bridge.db.contactName(id).orEmpty()
        // Except a "name" that is only the number, which is what an earlier
        // version wrote for the account's own row. Keeping it would outlive the
        // bug and block the real profile name from ever landing.
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
                quotedType = quotedType, senderName = senderName,
                forwarded = isForwarded, latitude = latitude, longitude = longitude
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

    // Without the error forwarded, a link that timed out left the screen on a
    // dead code with nothing said.
    override fun onQrCode(code: String) = Bridge.notifyQrCode(ProtoPicker.SG, code)
    override fun onPairError(code: String) = Bridge.notifyPairError(ProtoPicker.SG, code)

    // Signal has no pair-by-number flow.
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
        // The chat list previews a reaction on the newest message, so it
        // changes too, not just the open chat.
        Bridge.notifyChatsChanged()
    }
    /**
     * Left as a no-op stub for a long time, which is why a Signal voice note
     * appeared in the chat but could not be played and received media never
     * rendered: the row's file_path stayed empty no matter how the transfer
     * went.
     */
    override fun onFileDownloaded(chatId: String, msgId: String, filePath: String, status: Long) {
        if (Bridge.db.setFileState(chatId, msgId, filePath, status.toInt()) == 0 &&
            filePath.isNotEmpty()
        ) {
            // No row to attach it to, so the file would sit on disk unreachable.
            runCatching { java.io.File(filePath).delete() }
        }
        // Releases the in-flight claim Bridge.downloadFile took; without it a
        // failed download stayed unretryable for the rest of the run.
        Bridge.onFileTransferDone(chatId, msgId, filePath, status.toInt())
        Bridge.notifyChat(chatId)
    }

    override fun onDownloadProgress(chatId: String, msgId: String, pct: Long) =
        Bridge.postDownloadProgress(chatId, msgId, pct.toInt())
    // chatId is the reader, not the chat: a Signal receipt names no chat, so in
    // a group the two differ and the row has to be looked up by message id.
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

    // chatId is the message's author, which is the chat only in a 1:1. Falling
    // back to it when the row is missing cleared the unread of the author's own
    // 1:1 whenever the read was for a group message this device never stored.
    override fun onChatReadSelf(chatId: String, msgId: String) {
        val target = Bridge.db.messageChat(msgId, PREFIX, fromMe = false) ?: return
        Bridge.onChatReadSelf(target, msgId)
    }
    override fun onChatDeleted(chatId: String, deleteMedia: Boolean) =
        Bridge.onChatDeletedRemotely(chatId, deleteMedia)

    // No-ops: part of the shared WhatsApp-shaped interface, never raised by
    // Signal.
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
