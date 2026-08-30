package org.unichat.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object Tg {

    private const val TAG = "UniChatTg"

    private const val MEMBER_PAGE = 200
    private const val REACTION_PAGE = 50

    const val PREFIX = "tg:"

    fun isTgId(id: String): Boolean = id.startsWith(PREFIX)
    private fun chatIdOf(id: String): Long = id.removePrefix(PREFIX).toLongOrNull() ?: 0L
    private fun idFor(raw: Long): String = PREFIX + raw

    @Volatile private var clientId: Int = -1
    @Volatile private var appContext: Context? = null
    @Volatile var state: String = "disconnected"
        private set
    @Volatile var authState: String = ""
        private set
    @Volatile private var myId: Long = 0
    @Volatile private var myFirstName: String = ""
    @Volatile private var myLastName: String = ""
    @Volatile private var myPhone: String = ""
    @Volatile private var appVersion: String = "1.0"

    private val executor = Executors.newSingleThreadExecutor()
    // Unbounded paging work — export, sync-all, seek, the initial chat-list
    // load. These occupy a thread for minutes at a time, so they must not sit
    // on `executor`, where they would stall opening a chat, muting or logging
    // out for the whole run.
    private val pager = Executors.newSingleThreadExecutor()
    // On the pager, downloads queued behind the startup chat-list load and every
    // history page — with a blocking request each, a screenful of photos took
    // minutes to appear.
    private val downloader = Executors.newFixedThreadPool(3)

    // Kept off Io.executor, the app-wide serial worker every screen's DB reads
    // share: one request() can block for 15s, which would stall the chat list
    // behind it.
    val io: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor()

    internal fun <T> async(work: () -> T, onResult: (T) -> Unit) = io.execute {
        val result = work()
        Bridge.runOnUi { onResult(result) }
    }

    private val pending = ConcurrentHashMap<Long, Pair<CountDownLatch, Array<JSONObject?>>>()
    private val nextExtra = AtomicLong(1)

    // openChat is fire-and-forget and TDLib rejects it until it is authorized, so
    // a cold start straight onto a chat (notification tap, share) lost the open
    // for the whole visit — and with it every typing/recording action, which
    // TDLib delivers for a private chat only while the chat is open. Keep what
    // the screens asked for and replay it once TDLib is ready.
    @Volatile private var ready = false
    @Volatile private var readyLatch = CountDownLatch(1)
    private val openCounts = ConcurrentHashMap<String, Int>()

    private val readInbox = ConcurrentHashMap<Long, Long>()
    private val readOutbox = ConcurrentHashMap<Long, Long>()
    // A set of waiting messages per file id, not a single one: TDLib dedups
    // files by remote id, so the same sticker or a re-sent photo shares one file
    // id across messages, and keeping only the last one left the earlier bubbles
    // stuck on a spinner forever.
    private val fileTargets = ConcurrentHashMap<Int, MutableSet<Pair<String, String>>>()
    private val historyBusy = CopyOnWriteArraySet<String>()
    private val historyExhausted = CopyOnWriteArraySet<String>()
    // Messages already re-fetched once by the placeholder repair. A content
    // type this build still does not map re-stores the SAME "[Type]" text, so
    // without this it was deleted and re-inserted on every chat open and every
    // history page — churning rowids (the order tiebreaker), rewriting its
    // reactions, and eating the repair budget that stale voice notes need.
    private val repairAttempted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // updateMessageContent re-fetches the message off the serial executor, so a
    // logout wipe or a deletion can land while its request is in flight. Both
    // bump this from the executor (or ahead of queueing onto it) and the store
    // is dropped once its generation has moved on — otherwise the re-fetch
    // re-inserted the message after the wipe, which showed up as a ghost chat
    // from the account just unlinked, and brought a deleted message back.
    private val refetchGen = AtomicLong(0)

    fun hasSession(): Boolean = appContext?.let { Prefs.tgLinked(it) } == true

    // Empty, never "tg:0", while the id is unknown: callers either compare it
    // (no chat matches "") or offer it as a send target, and a target of tg:0
    // fails the send and opens an empty chat.
    fun selfId(): String = if (myId == 0L) "" else idFor(myId)

    // Blocking; worker threads only. A share sheet starts the process and asks
    // for its send targets straight away, so leaving out the notes-to-self entry
    // (or offering it as tg:0) is what the first share of a session used to get.
    fun selfIdBlocking(): String {
        selfId().let { if (it.isNotEmpty()) return it }
        // Short budgets: this runs on Io.executor, the serial worker every
        // screen's DB reads share, so waiting out the full request timeout here
        // freezes the rest of the app rather than just this picker.
        if (!awaitReady(5_000)) return ""
        if (myId == 0L) fetchMe(3_000)
        return selfId()
    }

    @Synchronized
    fun init(context: Context) {
        if (clientId >= 0) return
        appContext = context.applicationContext
        myId = Prefs.tgSelfId(context)
        runCatching {
            appVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: appVersion
        }
        clientId = TdJson.createClientId()
        Thread({ receiveLoop() }, "tg-receive").start()
        send(JSONObject().put("@type", "getOption").put("name", "version"))
    }

    private fun send(obj: JSONObject) {
        val id = clientId
        if (id >= 0) TdJson.send(id, obj.toString())
    }

    // Blocking; never on the login path — the auth requests themselves run
    // before this can be satisfied and would sit here until the timeout.
    private fun awaitReady(timeoutMs: Long = 20_000): Boolean {
        if (ready) return true
        if (!hasSession()) return false
        return try {
            readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
    }

    private fun request(obj: JSONObject, timeoutMs: Long = 15_000): JSONObject? {
        val extra = nextExtra.getAndIncrement()
        val latch = CountDownLatch(1)
        val slot = arrayOfNulls<JSONObject>(1)
        pending[extra] = Pair(latch, slot)
        obj.put("@extra", extra)
        send(obj)
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        } catch (e: InterruptedException) {
            return null
        } finally {
            pending.remove(extra)
        }
        val res = slot[0]
        if (res != null && res.optString("@type") == "error") {
            lastError = res.optString("message")
            Log.w(TAG, "request failed: ${obj.optString("@type")} -> $lastError")
            return null
        }
        return res
    }

    private fun receiveLoop() {
        while (true) {
            val raw = TdJson.receiveString(10.0) ?: continue
            try {
                val obj = JSONObject(raw)
                val extra = obj.optLong("@extra", -1)
                if (extra >= 0) {
                    pending[extra]?.let { (latch, slot) ->
                        slot[0] = obj
                        latch.countDown()
                    }
                    continue
                }
                onUpdate(obj)
            } catch (e: Throwable) {
                Log.e(TAG, "update handling failed", e)
            }
        }
    }

    private fun onAuthState(st: JSONObject) {
        val type = st.optString("@type")
        authState = type
        val ctx = appContext
        when (type) {
            "authorizationStateWaitTdlibParameters" -> {
                val dir = (ctx?.filesDir?.absolutePath ?: "") + "/tg"
                send(
                    JSONObject()
                        .put("@type", "setTdlibParameters")
                        .put("database_directory", "$dir/db")
                        .put("files_directory", "$dir/files")
                        .put("use_message_database", true)
                        .put("use_secret_chats", false)
                        .put("api_id", BuildConfig.TG_API_ID)
                        .put("api_hash", BuildConfig.TG_API_HASH)
                        .put("system_language_code", "en")
                        .put("device_model", "Android")
                        .put("application_version", appVersion)
                )
            }
            "authorizationStateWaitPhoneNumber" -> {
                ctx?.let { Prefs.setTgLinked(it, false) }
                Bridge.notifyTgAuth("wait_phone", "")
            }
            "authorizationStateWaitCode" -> Bridge.notifyTgAuth("wait_code", "")
            "authorizationStateWaitPassword" -> Bridge.notifyTgAuth("wait_password", "")
            "authorizationStateReady" -> {
                ctx?.let { Prefs.setTgLinked(it, true) }
                readyLatch.countDown()
                // `ready` flips on the executor, where openChat and closeChat
                // run. Flipped here instead, an openChat already queued could
                // read it and send the open that this replay also sends, leaving
                // TDLib one open ahead of us — and the chat open for good, since
                // only one close ever follows. The guard also keeps a repeated
                // authorizationStateReady from replaying everything twice.
                executor.execute {
                    if (!ready) {
                        ready = true
                        for ((chatId, n) in openCounts) repeat(n) { sendOpenChat(chatId) }
                    }
                }
                Bridge.notifyTgAuth("ready", "")
                // Reapply a pause chosen in Manage accounts: TDLib comes up
                // online every run, so the setting has to be pushed each time.
                appContext?.let {
                    if (!Prefs.protoEnabled(it, ProtoPicker.TG)) setNetworkEnabled(false)
                }
                onReady()
            }
            "authorizationStateClosed" -> {
                // logout completed: TDLib wiped its database AND its files
                // directory, so every cached path/id below now points at
                // something that no longer exists. Left in place they surfaced
                // as the previous account's avatars, a permanently "exhausted"
                // history and a stale selfId after the next login.
                ready = false
                readyLatch = CountDownLatch(1)
                openCounts.clear()
                readInbox.clear()
                readOutbox.clear()
                fileTargets.clear()
                historyExhausted.clear()
                historyBusy.clear()
                repairAttempted.clear()
                avatarPaths.clear()
                syncAllChat = null
                exportChatId = null
                myId = 0
                ctx?.let { Prefs.setTgSelfId(it, 0L) }
                myFirstName = ""
                myLastName = ""
                myPhone = ""
                refetchGen.incrementAndGet()
                executor.execute {
                    Bridge.db.clearTgData()
                    Bridge.notifyChatsChanged()
                }
                // a fresh client is needed after close
                clientId = TdJson.createClientId()
                send(JSONObject().put("@type", "getOption").put("name", "version"))
            }
        }
    }

    // Every auth step goes through request(), not send(): TDLib reports a wrong
    // code / wrong password / rejected number as an error object, and an error
    // carries the "@extra" of the request it answers. Sent fire-and-forget it
    // matched no update and was dropped, so a mistyped code left the login
    // screen waiting forever with no way to retry.
    private fun authStep(req: JSONObject, failState: String) {
        if (request(req) == null) {
            Bridge.notifyTgAuth(failState, lastError.ifEmpty { "" })
        }
    }

    @Volatile private var lastError: String = ""

    // TDLib's error "message" is a code, not a sentence: the ones a person can
    // act on are translated here, anything else is shown behind a label so it
    // stays diagnosable.
    fun authErrorText(ctx: Context, message: String): String = when {
        message.isEmpty() -> ctx.getString(R.string.tg_auth_failed)
        message.startsWith("PHONE_NUMBER_INVALID") -> ctx.getString(R.string.tg_err_phone_invalid)
        message.startsWith("PHONE_CODE_INVALID") -> ctx.getString(R.string.tg_err_code_invalid)
        message.startsWith("PHONE_CODE_EXPIRED") -> ctx.getString(R.string.tg_err_code_expired)
        message.startsWith("PASSWORD_HASH_INVALID") -> ctx.getString(R.string.tg_err_password_invalid)
        // FLOOD_WAIT_<seconds>: the wait is the only part worth reading.
        message.startsWith("FLOOD_WAIT_") ->
            ctx.getString(R.string.tg_err_flood, message.removePrefix("FLOOD_WAIT_"))
        else -> ctx.getString(R.string.tg_auth_failed_detail, message)
    }

    fun startPhoneLogin(phone: String) = executor.execute {
        authStep(
            JSONObject()
                .put("@type", "setAuthenticationPhoneNumber")
                .put("phone_number", phone),
            "phone_failed",
        )
    }

    fun submitCode(code: String) = executor.execute {
        authStep(
            JSONObject().put("@type", "checkAuthenticationCode").put("code", code),
            "code_failed",
        )
    }

    fun submitPassword(password: String) = executor.execute {
        authStep(
            JSONObject().put("@type", "checkAuthenticationPassword").put("password", password),
            "password_failed",
        )
    }

    fun logout() = executor.execute {
        send(JSONObject().put("@type", "logOut"))
        appContext?.let { Prefs.setTgLinked(it, false) }
    }

    // TDLib has no disconnect: telling it the device is offline is how you stop
    // it talking to the network without logging out and losing the session.
    fun setNetworkEnabled(enabled: Boolean) = executor.execute {
        send(
            JSONObject().put("@type", "setNetworkType").put(
                "type",
                JSONObject().put(
                    "@type",
                    if (enabled) "networkTypeOther" else "networkTypeNone"
                )
            )
        )
    }

    private fun fetchMe(timeoutMs: Long = 15_000) {
        request(JSONObject().put("@type", "getMe"), timeoutMs)?.let { me ->
            myId = me.optLong("id")
            appContext?.let { Prefs.setTgSelfId(it, myId) }
            myFirstName = me.optString("first_name")
            myLastName = me.optString("last_name")
            myPhone = me.optString("phone_number")
        }
    }

    private fun onReady() = pager.execute {
        fetchMe()
        var pages = 20
        while (pages-- > 0) {
            request(JSONObject().put("@type", "loadChats").put("limit", 100)) ?: break
        }
    }

    private fun onUpdate(obj: JSONObject) {
        when (obj.optString("@type")) {
            "updateAuthorizationState" -> onAuthState(obj.getJSONObject("authorization_state"))
            "updateConnectionState" -> {
                state = when (obj.getJSONObject("state").optString("@type")) {
                    "connectionStateReady" -> "connected"
                    "connectionStateWaitingForNetwork" -> "disconnected"
                    else -> "connecting"
                }
                Bridge.notifyAccountState(ProtoPicker.TG, state)
            }
            "updateNewChat" -> onNewChat(obj.getJSONObject("chat"))
            // A photo change invalidates the path we memoised for that chat —
            // TDLib writes the new picture to a different file and deletes the
            // old one, so without this the list kept showing the previous photo
            // (or the initials placeholder once the file was gone).
            "updateChatPhoto" -> {
                val chatId = idFor(obj.getLong("chat_id"))
                avatarPaths.remove(chatId)
                avatarPaths.remove("$chatId/big")
                AvatarLoader.invalidate(chatId)
                Bridge.notifyChatsChanged()
            }
            "updateChatTitle" -> {
                // renameChat, not upsertChat: the latter writes `archived` too,
                // and passing a placeholder false here un-archived the chat
                Bridge.db.renameChat(idFor(obj.getLong("chat_id")), obj.optString("title"))
                Bridge.notifyChatsChanged()
            }
            "updateChatLastMessage" -> {
                obj.optJSONObject("last_message")?.let { storeMessage(it) }
            }
            "updateNewMessage" -> {
                val msg = obj.getJSONObject("message")
                storeMessage(msg, notify = true)
            }
            "updateMessageContent" -> {
                val chatId = idFor(obj.getLong("chat_id"))
                val msgId = obj.getLong("message_id")
                // pager, not executor: a live-location peer emits this every few
                // seconds, and each blocking getMessage on the serial executor
                // stalled chat opens and logout for minutes on a slow network.
                // Only the request, though: the store goes back onto the
                // executor, guarded by the generation read here.
                val gen = refetchGen.get()
                pager.execute {
                    val fresh = request(
                        JSONObject().put("@type", "getMessage")
                            .put("chat_id", obj.getLong("chat_id")).put("message_id", msgId)
                    )
                    executor.execute {
                        if (refetchGen.get() != gen) return@execute
                        if (fresh != null) storeMessage(fresh)
                        Bridge.notifyChat(chatId)
                    }
                }
            }
            "updateMessageEdited" -> { /* content update arrives separately */ }
            "updateMessageSendSucceeded" -> {
                val msg = obj.getJSONObject("message")
                val oldId = obj.getLong("old_message_id")
                Bridge.db.deleteMessage(idFor(msg.getLong("chat_id")), oldId.toString())
                storeMessage(msg)
            }
            "updateMessageSendFailed" -> {
                val msg = obj.getJSONObject("message")
                Bridge.onMessageSendFailed(
                    idFor(msg.getLong("chat_id")), obj.getLong("old_message_id").toString()
                )
                Bridge.toastUi(R.string.send_failed)
            }
            "updateDeleteMessages" -> {
                // is_permanent=false events are cache drops, not real deletions
                if (obj.optBoolean("is_permanent")) {
                    val chatId = idFor(obj.getLong("chat_id"))
                    val ids = obj.getJSONArray("message_ids")
                    executor.execute {
                        refetchGen.incrementAndGet()
                        for (i in 0 until ids.length()) {
                            Bridge.db.deleteMessage(chatId, ids.getLong(i).toString())
                        }
                        Bridge.notifyChat(chatId)
                    }
                }
            }
            "updateChatReadInbox" -> {
                val raw = obj.getLong("chat_id")
                val upTo = obj.getLong("last_read_inbox_message_id")
                readInbox[raw] = upTo
                Bridge.db.markReadUpTo(idFor(raw), upTo, incoming = true)
                appContext?.let { Notifications.cancel(it, idFor(raw)) }
                Bridge.notifyChat(idFor(raw))
            }
            "updateChatReadOutbox" -> {
                val raw = obj.getLong("chat_id")
                val upTo = obj.getLong("last_read_outbox_message_id")
                readOutbox[raw] = upTo
                Bridge.db.markReadUpTo(idFor(raw), upTo, incoming = false)
                Bridge.notifyChat(idFor(raw))
            }
            // TDLib publishes the account's own id as an option as soon as it
            // authorizes, straight from its local database — no getMe round trip,
            // which is what the first share of a cold start would have had to
            // wait for.
            "updateOption" -> {
                if (obj.optString("name") == "my_id") {
                    val id = obj.optJSONObject("value")?.optLong("value") ?: 0L
                    if (id != 0L && id != myId) {
                        myId = id
                        appContext?.let { Prefs.setTgSelfId(it, id) }
                    }
                }
            }
            "updateUser" -> onUser(obj.getJSONObject("user"))
            "updateUserStatus" ->
                applyUserStatus(idFor(obj.getLong("user_id")), obj.optJSONObject("status"))
            "updateChatAction" -> {
                val chatId = idFor(obj.getLong("chat_id"))
                val uid = obj.getJSONObject("sender_id").optLong("user_id")
                val action = obj.getJSONObject("action").optString("@type")
                val st = when (action) {
                    "chatActionTyping" -> "typing"
                    // The sender's client reports recording only while the mic is
                    // held; the upload that follows is a separate action, so
                    // dropping it blanked the indicator for the rest of the note.
                    "chatActionRecordingVoiceNote", "chatActionUploadingVoiceNote" -> "recording"
                    "chatActionCancel" -> "paused"
                    else -> return
                }
                Bridge.onChatState(chatId, idFor(uid), st)
            }
            // The recipient played our voice note. TDLib reports this with its
            // own update — the content itself does not change, so no
            // updateMessageContent follows and the unplayed dot stayed on.
            "updateMessageContentOpened" -> {
                val chatId = idFor(obj.getLong("chat_id"))
                Bridge.db.setPlayed(chatId, obj.getLong("message_id").toString())
                Bridge.notifyChat(chatId)
            }
            // Archive moves while the app runs (and often a chat's initial
            // archive placement, after updateNewChat) arrive only here, not
            // through updateNewChat's positions.
            "updateChatPosition" -> {
                val p = obj.getJSONObject("position")
                // order is int64, which TDLib's JSON interface encodes as a string
                val present = p.optString("order", "0") != "0"
                val archived = when (p.getJSONObject("list").optString("@type")) {
                    "chatListArchive" -> present
                    "chatListMain" -> if (present) false else return
                    else -> return
                }
                Bridge.db.setArchived(idFor(obj.getLong("chat_id")), archived)
                Bridge.notifyChatsChanged()
            }
            "updateFile" -> onFile(obj.getJSONObject("file"))
            "updateMessageInteractionInfo" -> onInteractionInfo(obj)
            "updateChatNotificationSettings" -> {
                val raw = obj.getLong("chat_id")
                val muted = obj.getJSONObject("notification_settings").optInt("mute_for") > 0
                Bridge.db.setMuted(idFor(raw), muted)
                Bridge.notifyChatsChanged()
            }
        }
    }

    private fun onNewChat(chat: JSONObject) {
        val raw = chat.getLong("id")
        val id = idFor(raw)
        val title = chat.optString("title")
        var archived = false
        chat.optJSONArray("positions")?.let { positions ->
            for (i in 0 until positions.length()) {
                val p = positions.getJSONObject(i)
                if (p.getJSONObject("list").optString("@type") == "chatListArchive") archived = true
            }
        }
        readInbox[raw] = chat.optLong("last_read_inbox_message_id")
        readOutbox[raw] = chat.optLong("last_read_outbox_message_id")
        val last = chat.optJSONObject("last_message")
        Bridge.db.upsertChat(id, title, archived, last?.optLong("date") ?: 0)
        // Unconditional: writing only muted=true kept a chat unmuted from
        // another client while this app was offline muted here forever.
        val muted = chat.optJSONObject("notification_settings")?.optInt("mute_for", 0) ?: 0
        Bridge.db.setMuted(id, muted > 0)
        last?.let { storeMessage(it) }
        val type = chat.optJSONObject("type")?.optString("@type") ?: ""
        if (type == "chatTypeBasicGroup" || type == "chatTypeSupergroup") {
            Bridge.db.upsertContact(id, title, "", isSelf = false, isGroup = true, isSaved = false)
        }
        Bridge.notifyChatsChanged()
    }

    private fun onUser(user: JSONObject) {
        val uid = user.getLong("id")
        val name = listOf(user.optString("first_name"), user.optString("last_name"))
            .filter { it.isNotEmpty() }.joinToString(" ")
        Bridge.db.upsertContact(
            idFor(uid), name, user.optString("phone_number"),
            isSelf = uid == myId, isGroup = false,
            isSaved = user.optBoolean("is_contact"),
        )
        // updateUserStatus only fires when a status CHANGES; the status a
        // contact already has arrives here, with the user. Reading it only from
        // the update meant anyone who simply stayed offline never got a
        // last-seen at all.
        applyUserStatus(idFor(uid), user.optJSONObject("status"))
        Bridge.notifyContactsChangedInternal()
    }

    private fun applyUserStatus(userId: String, status: JSONObject?) {
        val type = status?.optString("@type") ?: return
        val online = type == "userStatusOnline"
        val exact = if (type == "userStatusOffline") status.optLong("was_online", 0) else 0
        Bridge.onPresence(userId, online, exact)
        Bridge.postPresenceApprox(
            userId,
            when (type) {
                "userStatusRecently" -> R.string.last_seen_recently
                "userStatusLastWeek" -> R.string.last_seen_week
                "userStatusLastMonth" -> R.string.last_seen_month
                else -> 0
            },
        )
    }

    // Free of DB writes on purpose: a search renders the window around a hit
    // WITHOUT storing it. Dropping a far-back window into the history would
    // leave an island with a gap on either side of it, and pagination anchors
    // on the oldest stored row — which is exactly how a chat ends up jumping
    // over months of messages.
    private class Parsed(val row: MessageRow, val listened: Boolean)

    private fun parseMessage(msg: JSONObject): Parsed? {
        val rawChat = msg.getLong("chat_id")
        val chatId = idFor(rawChat)
        val msgId = msg.getLong("id")
        val fromMe = msg.optBoolean("is_outgoing")
        val timeSent = msg.optLong("date")
        val sender = msg.optJSONObject("sender_id")
        val senderId = when (sender?.optString("@type")) {
            "messageSenderUser" -> idFor(sender.getLong("user_id"))
            "messageSenderChat" -> idFor(sender.getLong("chat_id"))
            else -> chatId
        }
        val isRead =
            if (fromMe) msgId <= (readOutbox[rawChat] ?: 0)
            else msgId <= (readInbox[rawChat] ?: 0)

        val content = msg.optJSONObject("content") ?: return null
        var msgType = ""
        var text = ""
        // Resolved once, by the same navigation the download path uses. Each
        // media branch used to re-walk its own content shape, so the stored
        // reference and the fetched file could drift apart — the failure
        // startDownload's comment below describes.
        var fileId = fileOf(content)?.optInt("id")?.toString() ?: ""
        var listened = false
        var latitude = 0.0
        var longitude = 0.0
        when (content.optString("@type")) {
            "messageText" -> text = markedText(content.optJSONObject("text"))
            "messagePhoto" -> {
                msgType = "image"
                text = markedText(content.optJSONObject("caption"))
            }
            "messageVideo" -> {
                msgType = "video"
                text = markedText(content.optJSONObject("caption"))
            }
            "messageVideoNote" -> msgType = "video"
            "messageAnimation" -> {
                msgType = "video"
                text = markedText(content.optJSONObject("caption"))
            }
            "messageVoiceNote" -> {
                msgType = "audio"
                val secs = content.getJSONObject("voice_note").optInt("duration")
                text = TimeFormat.mmss(secs)
                listened = content.optBoolean("is_listened")
            }
            "messageAudio" -> {
                msgType = "document"
                text = content.getJSONObject("audio").optString("file_name").ifEmpty { "audio" }
            }
            "messageDocument" -> {
                msgType = "document"
                text = content.getJSONObject("document").optString("file_name").ifEmpty { "file" }
            }
            "messageSticker" -> {
                val sticker = content.getJSONObject("sticker")
                if (sticker.optJSONObject("format")?.optString("@type") == "stickerFormatWebp") {
                    msgType = "sticker"
                } else {
                    // animated/video stickers have no still frame to show, so the
                    // row is text and must not claim a downloadable file
                    fileId = ""
                    val emoji = sticker.optString("emoji").ifEmpty { "🩹" }
                    text = appContext?.getString(R.string.sticker_with_emoji, emoji) ?: emoji
                }
            }
            // A message that is just emoji comes as its own content type with the
            // plain characters in `emoji`; without this it fell through to the
            // generic placeholder and rendered as "[AnimatedEmoji]". Falling back
            // to the placeholder when `emoji` is absent keeps the row
            // recognisable to the repair pass instead of blank.
            "messageAnimatedEmoji", "messageDice" ->
                text = content.optString("emoji").ifEmpty { placeholderFor(content) }
            "messageLocation" -> {
                msgType = "location"
                val loc = content.getJSONObject("location")
                latitude = loc.optDouble("latitude")
                longitude = loc.optDouble("longitude")
            }
            "messageCall" -> msgType = "call"
            "messageContact" -> {
                msgType = "contact"
                // same "name\nphone" body the WhatsApp bridge builds, so the
                // renderer and add-to-contacts action are protocol-blind
                content.optJSONObject("contact")?.let { c ->
                    val phone = c.optString("phone_number")
                        .let { if (it.isNotEmpty() && !it.startsWith("+")) "+$it" else it }
                    // line 1 is always the name header (the number when there
                    // is no name), so later lines are phones by construction
                    val name = listOf(c.optString("first_name"), c.optString("last_name"))
                        .filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { phone }
                    text = listOf(name, phone).filter { it.isNotEmpty() }.joinToString("\n")
                    // contact rows never download, so file_id is free to carry
                    // the shared user's id — what "Message" opens a chat with
                    c.optLong("user_id").takeIf { it != 0L }?.let { fileId = it.toString() }
                }
            }
            "messagePoll" -> msgType = "poll"
            "messageChatChangeTitle" -> text = "· " + content.optString("title")
            else -> text = placeholderFor(content)
        }

        var quotedId = ""
        var quotedText = ""
        var quotedType = ""
        msg.optJSONObject("reply_to")?.let {
            if (it.optString("@type") == "messageReplyToMessage" &&
                it.optLong("chat_id") == rawChat
            ) {
                quotedId = it.optLong("message_id").toString()
                // TDLib answers a reply with a bare message id and expects the
                // client to look the message up itself. What it does hand over,
                // when either is there, is the fragment the sender picked out and
                // the content of a message from another chat.
                quotedText = markedText(it.optJSONObject("quote")?.optJSONObject("text"))
                it.optJSONObject("content")?.let { quoted ->
                    if (quotedText.isEmpty()) {
                        quotedText = markedText(
                            quoted.optJSONObject("text") ?: quoted.optJSONObject("caption")
                        )
                    }
                    quotedType = typeOf(quoted.optString("@type"))
                }
            }
        }

        var filePath = ""
        var fileStatus = 0
        localPathOf(msg, content)?.let { filePath = it; fileStatus = 2 }

        val senderName = if (senderId != chatId) Bridge.db.contactName(senderId) ?: "" else ""
        return Parsed(
            MessageRow(
                msgId.toString(), chatId, senderId, text, fromMe, timeSent, isRead,
                msgType = msgType, fileId = fileId,
                filePath = filePath, fileStatus = fileStatus,
                latitude = latitude, longitude = longitude,
                edited = msg.optLong("edit_date") > 0, quotedId = quotedId,
                quotedText = quotedText, quotedType = quotedType,
                senderName = senderName,
                forwarded = msg.optJSONObject("forward_info") != null,
            ),
            listened,
        )
    }

    private fun storeMessage(msg: JSONObject, notify: Boolean = false) {
        val parsed = parseMessage(msg) ?: return
        val row = parsed.row
        Bridge.ingestMessage(
            row,
            notify = notify,
            fetchMedia = notify && !row.fromMe && !row.isRead,
            // Telegram bumps even for an edit: an edited message reaches us
            // through the same update as a new one, and skipping the bump left
            // the chat list ordered by whenever it was first seen.
            bump = true,
        ) {
            // upsertMessage leaves the file columns alone, so a path TDLib
            // already has is applied separately
            if (row.filePath.isNotEmpty()) {
                Bridge.db.setFileState(row.chatId, row.id, row.filePath, row.fileStatus)
            }
            // unconditional, not only when interaction_info is present: a
            // message whose last reaction was removed comes back carrying none,
            // and skipping it left the stale rows in place
            applyReactions(row.chatId, row.id, msg.optJSONObject("interaction_info"), preview = false)
            // upsertMessage deliberately never writes `played`, so apply it here
            if (parsed.listened) Bridge.db.setPlayed(row.chatId, row.id)
        }
    }

    // For the pager/worker paths, which have no catch-all like the update
    // loop's: one unexpected message shape killed the process there, and the
    // re-fetch on the next chat open made it a crash loop.
    private fun storeMessageSafe(msg: JSONObject) {
        try { storeMessage(msg) } catch (e: Exception) { Log.e(TAG, "message store failed", e) }
    }

    private fun parseMessageSafe(msg: JSONObject): Parsed? =
        try { parseMessage(msg) } catch (e: Exception) { Log.e(TAG, "message parse failed", e); null }

    private fun placeholderFor(content: JSONObject): String =
        "[" + content.optString("@type").removePrefix("message") + "]"

    private fun localPathOf(msg: JSONObject, content: JSONObject): String? {
        val file = fileOf(content) ?: return null
        val local = file.optJSONObject("local") ?: return null
        if (!local.optBoolean("is_downloading_completed")) return null
        // TDLib can still report a path whose file is gone (our own sends
        // reference the staging copy, which is swept after a day) — a row that
        // claims a dead path renders a play button that plays nothing
        val path = local.optString("path")
        return if (path.isNotEmpty() && java.io.File(path).exists()) path else null
    }

    private fun fileOf(content: JSONObject): JSONObject? {
        val file = when (content.optString("@type")) {
            "messagePhoto" -> {
                val sizes = content.getJSONObject("photo").getJSONArray("sizes")
                if (sizes.length() > 0) sizes.getJSONObject(sizes.length() - 1).getJSONObject("photo") else null
            }
            "messageVideo" -> content.getJSONObject("video").getJSONObject("video")
            "messageVideoNote" -> content.getJSONObject("video_note").getJSONObject("video")
            "messageAnimation" -> content.getJSONObject("animation").getJSONObject("animation")
            "messageVoiceNote" -> content.getJSONObject("voice_note").getJSONObject("voice")
            "messageAudio" -> content.getJSONObject("audio").getJSONObject("audio")
            "messageDocument" -> content.getJSONObject("document").getJSONObject("document")
            "messageSticker" -> content.getJSONObject("sticker").getJSONObject("sticker")
            else -> null
        }
        return file
    }

    private fun onInteractionInfo(obj: JSONObject) {
        val chatId = idFor(obj.getLong("chat_id"))
        val msgId = obj.getLong("message_id").toString()
        applyReactions(chatId, msgId, obj.optJSONObject("interaction_info"), preview = true)
        Bridge.notifyChatRow(chatId, msgId)
    }

    // Called both for the live update AND when a message is stored: a message
    // fetched from history already carries its reactions in interaction_info,
    // and reading them only from the update meant every reaction that predated
    // this session stayed invisible.
    private fun applyReactions(
        chatId: String, msgId: String, info: JSONObject?, preview: Boolean,
    ) {
        Bridge.db.clearReactions(chatId, msgId)
        val arr = info?.optJSONObject("reactions")?.optJSONArray("reactions")
        for (i in 0 until (arr?.length() ?: 0)) {
            val r = arr!!.getJSONObject(i)
            val emoji = r.getJSONObject("type").optString("emoji")
            if (emoji.isEmpty()) continue
            // One row per reaction TYPE, not per reacting user: the per-user
            // senders aren't listed here, and expanding total_count (a
            // server-controlled int32) meant a popular post issued tens of
            // thousands of inserts on the single thread that dispatches every
            // update and delivers every blocking response.
            val count = r.optInt("total_count", 1)
            val label = if (count > 1) "$emoji$count" else emoji
            Bridge.db.upsertReaction(chatId, msgId, "tg:r:$emoji", label)
        }
        // Only the live update is a fresh event; replaying history must not
        // rewrite the chat's preview line with an old reaction.
        if (preview) Bridge.notifyChatsChanged()
    }

    // Asked of the server: the Db rows for a Telegram message are one per
    // reaction TYPE with an aggregate count (see applyReactions), so they cannot
    // answer this — and a channel or a big group may refuse to say at all, which
    // reads here as nobody. Blocking; worker threads only.
    fun reactionSenders(chatId: String, msgId: String): List<Pair<String, String>> {
        val mid = msgId.toLongOrNull() ?: return emptyList()
        val answer = request(
            JSONObject().put("@type", "getMessageAddedReactions")
                .put("chat_id", chatIdOf(chatId)).put("message_id", mid)
                .put("offset", "").put("limit", REACTION_PAGE)
        ) ?: return emptyList()
        val arr = answer.optJSONArray("reactions") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val emoji = r.optJSONObject("type")?.optString("emoji").orEmpty()
            val sender = r.optJSONObject("sender_id") ?: continue
            val id = when (sender.optString("@type")) {
                "messageSenderUser" -> idFor(sender.optLong("user_id"))
                "messageSenderChat" -> idFor(sender.optLong("chat_id"))
                else -> ""
            }
            if (id.isNotEmpty() && emoji.isNotEmpty()) out.add(id to emoji)
        }
        return out
    }

    fun downloadFile(msg: MessageRow): Boolean {
        val fid = msg.fileId.toIntOrNull() ?: return false
        // the whole hand-off, DB write included: this is reached from
        // onBindViewHolder, so nothing here may touch SQLite on the main thread
        downloader.execute {
            Bridge.db.setFileState(msg.chatId, msg.id, "", 1)
            startDownload(msg, fid)
        }
        return true
    }

    // The stored id is never used to fetch. A TDLib file id is an index into the
    // session that issued it, and TDLib hands the same small integers out again
    // to unrelated files in later runs — so a stored id can now name a totally
    // different photo, which downloaded happily and was written onto this
    // message as if it belonged to it. That is how one file ended up rendering
    // in four messages across four different chats. Asking the message which
    // file it has is the only answer that cannot be stale.
    private fun startDownload(msg: MessageRow, storedFid: Int) {
        val mid = msg.id.toLongOrNull() ?: return failDownload(msg)
        val fresh = request(
            JSONObject().put("@type", "getMessage")
                .put("chat_id", chatIdOf(msg.chatId)).put("message_id", mid)
        ) ?: return failDownload(msg)
        val fid = fresh.optJSONObject("content")?.let { fileOf(it) }?.optInt("id")
        if (fid == null || fid == 0) {
            Log.w(TAG, "no file for ${msg.chatId}/${msg.id}")
            return failDownload(msg)
        }
        if (fid != storedFid) Bridge.db.setFileId(msg.chatId, msg.id, fid.toString())
        if (!issueDownload(msg, fid)) failDownload(msg)
    }

    // A file TDLib already holds is finished here and now: downloadFile answers
    // with the completed file and no updateFile follows, because nothing changed
    // — waiting for one left the bubble on its spinner for good.
    private fun issueDownload(msg: MessageRow, fid: Int): Boolean {
        val target = Pair(msg.chatId, msg.id)
        fileTargets.computeIfAbsent(fid) { ConcurrentHashMap.newKeySet() }.add(target)
        val res = request(downloadRequest(fid))
        if (res == null) {
            // Registration is not left behind on failure: these ids get reused,
            // so a dangling target would receive whatever unrelated file lands
            // on that id next.
            fileTargets[fid]?.remove(target)
            return false
        }
        var answer = res
        val claimed = completedAt(answer)
        if (claimed != null && !usable(claimed)) {
            // TDLib still believes in a local copy that is gone, and answers
            // every request with it instead of fetching anything. deleteFile
            // drops that belief, so the retry really goes to the server.
            request(JSONObject().put("@type", "deleteFile").put("file_id", fid))
            // an update for this id may have landed while those two blocking
            // calls ran, and any completion drops the whole entry — re-register,
            // or the real completion would arrive with nowhere to land
            answer = request(downloadRequest(fid)) ?: run {
                fileTargets[fid]?.remove(target)
                return false
            }
            fileTargets.computeIfAbsent(fid) { ConcurrentHashMap.newKeySet() }.add(target)
        }
        val done = completedAt(answer)
        if (done != null) {
            if (!usable(done)) {
                // still "downloaded" onto nothing even after deleteFile: TDLib
                // will send no update either, so returning true would leave the
                // row spinning at status 1 for the rest of the run
                fileTargets[fid]?.remove(target)
                return false
            }
            Bridge.db.setFileState(msg.chatId, msg.id, done, 2)
            Bridge.notifyChatRow(msg.chatId, msg.id)
            Bridge.onFileTransferDone(msg.chatId, msg.id, done, 2)
        }
        return true
    }

    private fun completedAt(res: JSONObject): String? {
        val local = res.optJSONObject("local") ?: return null
        if (!local.optBoolean("is_downloading_completed")) return null
        return local.optString("path")
    }

    // TDLib calls a file "downloaded" from its own bookkeeping, which outlives
    // the file itself: our sends point at the cacheDir staging copy that the
    // daily sweep deletes. Writing such a path back onto the message re-created
    // the dead reference the caller had just cleared, and bind → download →
    // "complete" → bind went round for good, so a path that does not resolve
    // counts as no download at all.
    private fun usable(path: String) = path.isNotEmpty() && java.io.File(path).exists()

    private fun downloadRequest(fid: Int) = JSONObject().put("@type", "downloadFile")
        .put("file_id", fid).put("priority", 16).put("synchronous", false)

    private fun failDownload(msg: MessageRow) {
        Bridge.db.setFileState(msg.chatId, msg.id, "", 3)
        Bridge.notifyChatRow(msg.chatId, msg.id)
        Bridge.onFileTransferDone(msg.chatId, msg.id, "", 3)
    }

    private fun onFile(file: JSONObject) {
        val fid = file.optInt("id")
        val targets = fileTargets[fid] ?: return
        val local = file.optJSONObject("local") ?: return
        when {
            local.optBoolean("is_downloading_completed") -> {
                fileTargets.remove(fid)
                val path = local.optString("path")
                // "completed" onto a path that no longer resolves is a failure,
                // not a download — see usable()
                val ok = usable(path)
                for ((chatId, msgId) in targets) {
                    Bridge.db.setFileState(chatId, msgId, if (ok) path else "", if (ok) 2 else 3)
                    Bridge.notifyChatRow(chatId, msgId)
                    Bridge.onFileTransferDone(chatId, msgId, if (ok) path else "", if (ok) 2 else 3)
                }
            }
            local.optBoolean("is_downloading_active") -> {
                val total = file.optLong("size")
                if (total > 0) {
                    val pct = (local.optLong("downloaded_size") * 100 / total).toInt().coerceIn(0, 99)
                    for ((chatId, msgId) in targets) Bridge.postDownloadProgress(chatId, msgId, pct)
                }
            }
            // Neither active nor completed. That alone is not a failure: TDLib
            // parks a transfer whenever the network drops or its queue is busy
            // and resumes it by itself. can_be_downloaded is the only field
            // that says the file is genuinely unreachable, so everything else
            // stays pending with its targets registered — dropping them here
            // left the eventual completion nowhere to land, and the bubble read
            // "failed" with the file already on disk.
            !local.optBoolean("can_be_downloaded", true) -> {
                fileTargets.remove(fid)
                for ((chatId, msgId) in targets) {
                    Bridge.db.setFileState(chatId, msgId, "", 3)
                    Bridge.notifyChatRow(chatId, msgId)
                    Bridge.onFileTransferDone(chatId, msgId, "", 3)
                }
            }
        }
    }

    private fun replyTo(quotedId: String): JSONObject? =
        quotedId.toLongOrNull()?.let {
            JSONObject().put("@type", "inputMessageReplyToMessage").put("message_id", it)
        }

    private fun sendMessage(chatId: String, content: JSONObject, quotedId: String = ""): Boolean {
        // A share sheet can start this process and reach a send before TDLib has
        // opened its database; until then every request is answered
        // "Unauthorized", and the shared file was dropped on the floor. Bounded
        // well under the 20s default: sends are dispatched on Bridge's single
        // send thread, so this wait holds up every other chat's sends too.
        if (!awaitReady(6_000)) return false
        val req = JSONObject().put("@type", "sendMessage")
            .put("chat_id", chatIdOf(chatId))
            .put("input_message_content", content)
        replyTo(quotedId)?.let { req.put("reply_to", it) }
        return request(req) != null
    }

    // Telegram keeps bold/italic in entities beside the text, WhatsApp keeps
    // them as markers inside it. One text is stored for both, in WhatsApp's
    // form, so the markers turn into entities here and back in markedText —
    // sending them as they are made other Telegram clients show the asterisks.
    private fun formattedText(text: String, mentions: List<Mention> = emptyList()): JSONObject {
        val (plain, marks) = Markup.parse(text)
        val ft = JSONObject().put("@type", "formattedText").put("text", plain)
        val spans = ArrayList<Triple<Int, Int, JSONObject>>()
        for (m in marks) {
            spans.add(
                Triple(
                    m.start, m.end - m.start,
                    JSONObject().put(
                        "@type",
                        if (m.bold) "textEntityTypeBold" else "textEntityTypeItalic"
                    )
                )
            )
        }
        // resolved against the text as it will be sent, markers already gone:
        // an entity offset counts characters TDLib will see, not the ones typed
        for (h in mentionHits(plain, mentions)) {
            val uid = chatIdOf(h.id)
            // TDLib rejects the whole message over entities that half-overlap
            // each other, which a bold run ending inside a mention would
            if (uid <= 0 || marks.any { half(it, h.start, h.end) }) continue
            spans.add(
                Triple(
                    h.start, h.end - h.start,
                    JSONObject().put("@type", "textEntityTypeMentionName")
                        .put("user_id", uid)
                )
            )
        }
        if (spans.isEmpty()) return ft
        spans.sortWith(compareBy({ it.first }, { -it.second }))
        val entities = JSONArray()
        for ((offset, length, type) in spans) {
            entities.put(
                JSONObject().put("@type", "textEntity")
                    .put("offset", offset).put("length", length).put("type", type)
            )
        }
        return ft.put("entities", entities)
    }

    // Only what a quote card needs to name: a reply from another chat carries
    // its content, and the row it belongs to is never stored or downloaded.
    private fun typeOf(contentType: String): String = when (contentType) {
        "messagePhoto" -> "image"
        "messageSticker" -> "sticker"
        "messageVideo", "messageVideoNote", "messageAnimation" -> "video"
        "messageVoiceNote" -> "audio"
        "messageAudio", "messageDocument" -> "document"
        "messageLocation", "messageVenue" -> "location"
        "messageContact" -> "contact"
        else -> ""
    }

    private fun half(m: Markup.Mark, start: Int, end: Int): Boolean =
        (m.start < start && m.end > start && m.end < end) ||
            (m.start > start && m.start < end && m.end > end)

    // A supergroup only answers this for members allowed to see the list, and
    // one page is taken on purpose — a channel with thousands of subscribers is
    // not something to enumerate on a profile screen.
    fun groupMembers(chatId: String): List<String> {
        val chat = request(
            JSONObject().put("@type", "getChat").put("chat_id", chatIdOf(chatId))
        ) ?: return emptyList()
        val type = chat.optJSONObject("type") ?: return emptyList()
        val members = when (type.optString("@type")) {
            "chatTypeBasicGroup" -> request(
                JSONObject().put("@type", "getBasicGroupFullInfo")
                    .put("basic_group_id", type.optLong("basic_group_id"))
            )?.optJSONArray("members")
            "chatTypeSupergroup" -> request(
                JSONObject().put("@type", "getSupergroupMembers")
                    .put("supergroup_id", type.optLong("supergroup_id"))
                    .put("filter", JSONObject().put("@type", "supergroupMembersFilterRecent"))
                    .put("offset", 0).put("limit", MEMBER_PAGE)
            )?.optJSONArray("members")
            else -> null
        } ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until members.length()) {
            val sender = members.optJSONObject(i)?.optJSONObject("member_id") ?: continue
            if (sender.optString("@type") != "messageSenderUser") continue
            val uid = sender.optLong("user_id")
            if (uid != 0L) ids.add(idFor(uid))
        }
        return ids
    }

    // Blocking. Null means the request itself failed, which the caller must not
    // keep paying for once per remaining member.
    fun cacheUser(userId: String): String? {
        val user = request(
            JSONObject().put("@type", "getUser").put("user_id", chatIdOf(userId))
        ) ?: return null
        onUser(user)
        return listOf(user.optString("first_name"), user.optString("last_name"))
            .filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun markedText(ft: JSONObject?): String {
        val plain = ft?.optString("text") ?: return ""
        val entities = ft.optJSONArray("entities") ?: return plain
        val marks = ArrayList<Markup.Mark>()
        for (i in 0 until entities.length()) {
            val e = entities.optJSONObject(i) ?: continue
            val bold = when (e.optJSONObject("type")?.optString("@type")) {
                "textEntityTypeBold" -> true
                "textEntityTypeItalic" -> false
                else -> null
            } ?: continue
            // offsets are UTF-16 units, same as a Kotlin String's, but they
            // describe the server's copy — a truncated one must not crash us
            val start = e.optInt("offset")
            val end = start + e.optInt("length")
            if (start < 0 || end <= start || end > plain.length) continue
            marks.add(Markup.Mark(start, end, bold))
        }
        return Markup.withMarkers(plain, marks)
    }

    private fun inputLocalFile(path: String) =
        JSONObject().put("@type", "inputFileLocal").put("path", path)

    fun sendText(
        chatId: String, text: String, quotedId: String = "",
        mentions: List<Mention> = emptyList(),
    ): Boolean = sendMessage(
        chatId,
        JSONObject().put("@type", "inputMessageText")
            .put("text", formattedText(text, mentions)),
        quotedId,
    )

    // This TDLib schema wraps media files in inputPhoto/inputVideo/… objects
    // (not bare InputFile — that parses as null and the send fails with
    // "InputFile is not specified").
    // Telegram's own view-once: the recipient's client destroys the media as
    // soon as it is closed. Only photo and video take it, and only in a private
    // chat — anywhere else TDLib rejects the whole send.
    private fun selfDestruct(content: JSONObject, chatId: String, viewOnce: Boolean): JSONObject {
        if (!viewOnce || isGroupId(chatId)) return content
        return content.put(
            "self_destruct_type",
            JSONObject().put("@type", "messageSelfDestructTypeImmediately")
        )
    }

    fun sendImage(
        chatId: String, path: String, caption: String, quotedId: String = "",
        viewOnce: Boolean = false,
    ): Boolean =
        sendMessage(
            chatId,
            selfDestruct(
                JSONObject().put("@type", "inputMessagePhoto")
                    .put(
                        "photo",
                        JSONObject().put("@type", "inputPhoto").put("photo", inputLocalFile(path))
                    )
                    .put("caption", formattedText(caption)),
                chatId, viewOnce,
            ),
            quotedId,
        )

    fun sendVideo(
        chatId: String, path: String, caption: String, quotedId: String = "",
        viewOnce: Boolean = false,
    ): Boolean =
        sendMessage(
            chatId,
            selfDestruct(
                JSONObject().put("@type", "inputMessageVideo")
                    .put(
                        "video",
                        JSONObject().put("@type", "inputVideo")
                            .put("video", inputLocalFile(path))
                            .put("supports_streaming", true)
                    )
                    .put("caption", formattedText(caption)),
                chatId, viewOnce,
            ),
            quotedId,
        )

    fun sendAudio(chatId: String, path: String, durationSeconds: Int, quotedId: String = "", waveform: ByteArray = ByteArray(0)): Boolean =
        sendMessage(
            chatId,
            JSONObject().put("@type", "inputMessageVoiceNote")
                .put(
                    "voice_note",
                    JSONObject().put("@type", "inputVoiceNote")
                        .put("voice_note", inputLocalFile(path))
                        .put("duration", durationSeconds)
                        .put(
                            "waveform",
                            android.util.Base64.encodeToString(waveform, android.util.Base64.NO_WRAP)
                        )
                ),
            quotedId,
        )

    // TDLib names a document after the basename of the file it is given, and
    // attachments are staged as "<prefix>_<millis>_<name>", so the recipient saw
    // that internal name. The link/copy made here lives in a per-send directory
    // that Bridge.cleanStaleCache reclaims by age — the upload continues after
    // this call returns, so it cannot be deleted here.
    fun sendDocument(chatId: String, path: String, fileName: String = "", quotedId: String = ""): Boolean {
        val src = java.io.File(path)
        val safe = safeDisplayFileName(fileName.ifEmpty { src.name })
        val sendPath = if (safe == src.name) path else namedCopy(src, safe) ?: path
        return sendMessage(
            chatId,
            JSONObject().put("@type", "inputMessageDocument")
                .put(
                    "document",
                    JSONObject().put("@type", "inputDocument").put("document", inputLocalFile(sendPath))
                ),
            quotedId,
        )
    }

    private fun namedCopy(src: java.io.File, name: String): String? {
        val cache = appContext?.cacheDir ?: return null
        return try {
            val dir = java.io.File(cache, "tgdoc/${System.nanoTime()}")
            if (!dir.mkdirs()) return null
            val out = java.io.File(dir, name)
            try {
                java.nio.file.Files.createLink(out.toPath(), src.toPath())
            } catch (_: Exception) {
                src.copyTo(out, overwrite = true)
            }
            out.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "document rename failed: $e")
            null
        }
    }

    fun sendLocation(chatId: String, lat: Double, lng: Double): Boolean = sendMessage(
        chatId,
        JSONObject().put("@type", "inputMessageLocation")
            .put(
                "location",
                JSONObject().put("@type", "location").put("latitude", lat).put("longitude", lng)
            ),
    )

    // TDLib takes the card's fields directly, so only the first number travels
    // as the contact's own — the rest ride along in the vCard, which is what
    // Telegram itself does with a multi-number card.
    fun sendContact(chatId: String, name: String, numbers: List<String>): Boolean {
        val parts = name.trim().split(" ", limit = 2)
        return sendMessage(
            chatId,
            JSONObject().put("@type", "inputMessageContact")
                .put(
                    "contact",
                    JSONObject().put("@type", "contact")
                        .put("phone_number", numbers.firstOrNull().orEmpty())
                        .put("first_name", parts[0])
                        .put("last_name", parts.getOrElse(1) { "" })
                        .put("vcard", PhoneBook.vcard(name, numbers))
                        .put("user_id", 0)
                ),
        )
    }

    fun editMessageText(chatId: String, msgId: String, newText: String): Boolean {
        val res = request(
            JSONObject().put("@type", "editMessageText")
                .put("chat_id", chatIdOf(chatId))
                .put("message_id", msgId.toLongOrNull() ?: return false)
                .put(
                    "input_message_content",
                    JSONObject().put("@type", "inputMessageText").put("text", formattedText(newText))
                )
        )
        return res != null
    }

    fun deleteMessages(chatId: String, msgIds: List<String>, revoke: Boolean) {
        val arr = JSONArray()
        for (m in msgIds) m.toLongOrNull()?.let { arr.put(it) }
        if (arr.length() == 0) return
        send(
            JSONObject().put("@type", "deleteMessages")
                .put("chat_id", chatIdOf(chatId)).put("message_ids", arr).put("revoke", revoke)
        )
    }

    fun sendReaction(chatId: String, msgId: String, emoji: String) {
        val mid = msgId.toLongOrNull() ?: return
        if (emoji.isEmpty()) {
            val msg = request(
                JSONObject().put("@type", "getMessage")
                    .put("chat_id", chatIdOf(chatId)).put("message_id", mid)
            ) ?: return
            val reactions = msg.optJSONObject("interaction_info")
                ?.optJSONObject("reactions")?.optJSONArray("reactions") ?: return
            for (i in 0 until reactions.length()) {
                val r = reactions.getJSONObject(i)
                if (!r.optBoolean("is_chosen")) continue
                send(
                    JSONObject().put("@type", "removeMessageReaction")
                        .put("chat_id", chatIdOf(chatId)).put("message_id", mid)
                        .put("reaction_type", r.getJSONObject("type"))
                )
            }
        } else {
            send(
                JSONObject().put("@type", "addMessageReaction")
                    .put("chat_id", chatIdOf(chatId)).put("message_id", mid)
                    .put("reaction_type", JSONObject().put("@type", "reactionTypeEmoji").put("emoji", emoji))
            )
        }
    }

    // TDLib requires a private chat to exist (createPrivateChat) before
    // anything can be sent to it. "" means the number has no Telegram account
    // or is not visible to us.
    fun createChatByPhone(number: String): String {
        // A failed request is not an answer: reported as "" it became "Not on
        // Telegram", which is the one thing it must never say for a lookup that
        // never happened.
        val user = request(
            JSONObject().put("@type", "searchUserByPhoneNumber").put("phone_number", number)
        ) ?: return Bridge.NUMBER_LOOKUP_FAILED
        val userId = user.optLong("id")
        return if (userId != 0L) createUserChat(userId) else ""
    }

    fun createUserChat(userId: Long): String {
        val chat = request(
            JSONObject().put("@type", "createPrivateChat").put("user_id", userId)
        ) ?: return ""
        val id = chat.optLong("id")
        return if (id != 0L) idFor(id) else ""
    }

    // Required, not an optimisation: for a private chat TDLib's
    // DialogActionManager drops every incoming typing/recording action unless
    // the dialog is open (or the peer's last-seen is exact), and
    // supergroups/channels only receive updates at all while open — which is
    // why chat actions never appeared before this was wired up.
    fun openChat(chatId: String) = executor.execute {
        openCounts.merge(chatId, 1) { a, b -> a + b }
        if (ready) sendOpenChat(chatId)
    }

    fun closeChat(chatId: String) = executor.execute {
        // only if we are the ones holding it open: an unmatched close is an
        // error inside TDLib, and there is no open of ours for it to release
        val held = openCounts.containsKey(chatId)
        openCounts.compute(chatId) { _, n -> if (n == null || n <= 1) null else n - 1 }
        if (ready && held) {
            send(JSONObject().put("@type", "closeChat").put("chat_id", chatIdOf(chatId)))
        }
    }

    private fun sendOpenChat(chatId: String) =
        send(JSONObject().put("@type", "openChat").put("chat_id", chatIdOf(chatId)))

    // openMessageContent is what clears the unplayed dot on the SENDER's side;
    // viewMessages alone only marks the message read.
    fun markVoicePlayed(chatId: String, msgId: String) = executor.execute {
        val mid = msgId.toLongOrNull() ?: return@execute
        send(
            JSONObject().put("@type", "openMessageContent")
                .put("chat_id", chatIdOf(chatId)).put("message_id", mid)
        )
    }

    fun markChatRead(chatId: String) = executor.execute {
        val latest = Bridge.db.latestUnread(chatId) ?: return@execute
        val mid = latest.id.toLongOrNull() ?: return@execute
        Bridge.db.markChatRead(chatId)
        send(
            JSONObject().put("@type", "viewMessages")
                .put("chat_id", chatIdOf(chatId))
                .put("message_ids", JSONArray().put(mid))
                .put("force_read", true)
        )
        Bridge.notifyChatsChanged()
    }

    fun setMuted(chatId: String, muted: Boolean) = executor.execute {
        Bridge.db.setMuted(chatId, muted)
        send(
            JSONObject().put("@type", "setChatNotificationSettings")
                .put("chat_id", chatIdOf(chatId))
                .put(
                    "notification_settings",
                    // Every field TDLib parses must be sent: absent Bools decode
                    // as false and absent ints as 0, so a partial object would
                    // also pin this chat's sound to "none" and turn its previews
                    // off, account-wide, with no way back from the unmute.
                    JSONObject().put("@type", "chatNotificationSettings")
                        .put("use_default_mute_for", false)
                        .put("mute_for", if (muted) 2147483647 else 0)
                        .put("use_default_sound", true)
                        .put("use_default_show_preview", true)
                        .put("use_default_mute_stories", true)
                        .put("use_default_story_sound", true)
                        .put("use_default_show_story_poster", true)
                        .put("use_default_disable_pinned_message_notifications", true)
                        .put("use_default_disable_mention_notifications", true)
                )
        )
        Bridge.notifyChatsChanged()
    }

    fun isHistoryExhausted(chatId: String): Boolean = chatId in historyExhausted

    fun requestHistoryPage(chatId: String, pageSize: Int = 100, onDone: ((Int) -> Unit)? = null) {
        if (!historyBusy.add(chatId)) { onDone?.invoke(-1); return }
        pager.execute {
            try {
                val fromId = Bridge.db.oldestMessage(chatId)?.id?.toLongOrNull() ?: 0L
                val count = fetchHistory(chatId, fromId, pageSize)
                if (count == 0) historyExhausted.add(chatId)
                if (count > 0) Bridge.notifyChat(chatId)
                onDone?.invoke(count)
            } finally {
                // released before the re-sync below: that is another blocking
                // round-trip, and holding the slot across it made a scroll
                // arriving in the window fail historyBusy.add and be dropped,
                // leaving the user stuck at the top edge with no new page
                historyBusy.remove(chatId)
            }
            syncPlayedState(chatId)
        }
    }

    private fun fetchHistory(chatId: String, fromMsgId: Long, limit: Int): Int =
        fetchHistoryPage(chatId, fromMsgId, limit).first

    // The returned oldest id is what a full walk anchors its next page on:
    // anchoring on the oldest row in the DB instead only ever extends the
    // history backwards, so a hole between two already-synced stretches could
    // never be filled.
    private fun fetchHistoryPage(chatId: String, fromMsgId: Long, limit: Int): Pair<Int, Long> {
        val res = request(
            JSONObject().put("@type", "getChatHistory")
                .put("chat_id", chatIdOf(chatId))
                .put("from_message_id", fromMsgId)
                .put("offset", 0).put("limit", limit).put("only_local", false),
            timeoutMs = 30_000,
        ) ?: return Pair(-1, 0L)
        val arr = res.optJSONArray("messages") ?: return Pair(-1, 0L)
        var oldest = 0L
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            storeMessageSafe(m)
            val id = m.optLong("id")
            if (id > 0 && (oldest == 0L || id < oldest)) oldest = id
        }
        return Pair(arr.length(), oldest)
    }

    fun requestInitialHistory(chatId: String) {
        if (!historyBusy.add(chatId)) return
        // messageCount too: this is called from ChatActivity.onCreate, on the
        // main thread
        pager.execute {
            try {
                if (Bridge.db.messageCount(chatId) < 60 &&
                    fetchHistory(chatId, 0, 60) > 0
                ) {
                    Bridge.notifyChat(chatId)
                }
                syncPlayedState(chatId)
            } finally {
                historyBusy.remove(chatId)
            }
        }
    }

    class SearchPage(val ids: List<String>, val total: Int, val nextFrom: Long)

    fun searchChat(chatId: String, query: String, fromMessageId: Long, limit: Int = 50): SearchPage? {
        val res = request(
            JSONObject().put("@type", "searchChatMessages")
                .put("chat_id", chatIdOf(chatId))
                .put("topic_id", JSONObject.NULL)
                .put("query", query)
                .put("sender_id", JSONObject.NULL)
                .put("from_message_id", fromMessageId)
                .put("offset", 0)
                // the server caps this at 100 and may return fewer than asked
                .put("limit", limit.coerceIn(1, 100))
                .put("filter", JSONObject.NULL),
            timeoutMs = 30_000,
        ) ?: return null
        val arr = res.optJSONArray("messages") ?: return null
        val ids = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val id = arr.getJSONObject(i).optLong("id")
            if (id > 0) ids.add(id.toString())
        }
        return SearchPage(ids, res.optInt("total_count"), res.optLong("next_from_message_id"))
    }

    // Asked of the server with a photo filter rather than sifted out of a slice
    // of history: a search window holds ~50 messages, of which only a handful
    // are pictures, so an album built from it ran out after a swipe or two.
    // Blocking; worker threads only.
    fun chatPhotos(chatId: String, fromMsgId: String, newer: Boolean?, limit: Int = 49): List<MessageRow> {
        val id = fromMsgId.toLongOrNull() ?: return emptyList()
        // TDLib's bounds, as in contextWindow: the offset may not pass -99, the
        // limit not 100, and the limit has to cover the offset
        val n = limit.coerceIn(1, 49)
        val offset = when (newer) {
            null -> -n
            true -> -n
            false -> 0
        }
        val count = if (newer == false) n else n * 2 + 1
        val res = request(
            JSONObject().put("@type", "searchChatMessages")
                .put("chat_id", chatIdOf(chatId))
                .put("topic_id", JSONObject.NULL)
                .put("query", "")
                .put("sender_id", JSONObject.NULL)
                .put("from_message_id", id)
                .put("offset", offset)
                .put("limit", count.coerceIn(1, 100))
                .put("filter", JSONObject().put("@type", "searchMessagesFilterPhoto")),
            timeoutMs = 30_000,
        ) ?: return emptyList()
        val arr = res.optJSONArray("messages") ?: return emptyList()
        val rows = ArrayList<MessageRow>(arr.length())
        for (i in 0 until arr.length()) parseMessageSafe(arr.getJSONObject(i))?.let { rows.add(it.row) }
        // prefer the stored row: it knows about a file already on this phone,
        // which the parsed one only does when TDLib still holds it locally
        val stored = Bridge.db.messagesByIds(chatId, rows.mapTo(HashSet()) { it.id }).associateBy { it.id }
        return rows.map { stored[it.id] ?: it }
            .sortedWith(compareBy({ it.timeSent }, { it.id.toLongOrNull() ?: 0L }))
    }

    fun contextWindow(chatId: String, msgId: String, radius: Int = 25): List<MessageRow> {
        val id = msgId.toLongOrNull() ?: return emptyList()
        // TDLib's own bounds: the offset may not go past -99 and the limit not
        // past 100, and the limit must cover the offset
        val r = radius.coerceIn(1, 49)
        val res = request(
            JSONObject().put("@type", "getChatHistory")
                .put("chat_id", chatIdOf(chatId))
                .put("from_message_id", id)
                // negative offset also returns messages NEWER than the hit, so it
                // sits in the middle of the window instead of at the top of it
                .put("offset", -r)
                .put("limit", r * 2 + 1)
                .put("only_local", false),
            timeoutMs = 30_000,
        ) ?: return emptyList()
        val arr = res.optJSONArray("messages") ?: return emptyList()
        val rows = ArrayList<MessageRow>(arr.length())
        for (i in 0 until arr.length()) parseMessageSafe(arr.getJSONObject(i))?.let { rows.add(it.row) }
        val stored = Bridge.db.messagesByIds(chatId, rows.mapTo(HashSet()) { it.id }).associateBy { it.id }
        return rows.map { stored[it.id] ?: it }
            .sortedWith(compareBy({ it.timeSent }, { it.id.toLongOrNull() ?: 0L }))
    }

    fun historySlice(chatId: String, msgId: String, newer: Boolean, count: Int = 30): List<MessageRow> {
        val id = msgId.toLongOrNull() ?: return emptyList()
        val n = count.coerceIn(1, 49)
        val res = request(
            JSONObject().put("@type", "getChatHistory")
                .put("chat_id", chatIdOf(chatId))
                .put("from_message_id", id)
                // asking for the anchor plus n is the only way TDLib walks
                // forwards from a message
                .put("offset", if (newer) -n else 0)
                .put("limit", if (newer) n + 1 else n)
                .put("only_local", false),
            timeoutMs = 30_000,
        ) ?: return emptyList()
        val arr = res.optJSONArray("messages") ?: return emptyList()
        val rows = ArrayList<MessageRow>(arr.length())
        for (i in 0 until arr.length()) {
            val row = parseMessageSafe(arr.getJSONObject(i))?.row ?: continue
            val rowId = row.id.toLongOrNull() ?: continue
            // the anchor itself comes back in the "newer" answer
            if (if (newer) rowId > id else rowId < id) rows.add(row)
        }
        val stored = Bridge.db.messagesByIds(chatId, rows.mapTo(HashSet()) { it.id }).associateBy { it.id }
        return rows.map { stored[it.id] ?: it }
            .sortedWith(compareBy({ it.timeSent }, { it.id.toLongOrNull() ?: 0L }))
    }

    // Blocks until the file lands. The ordinary download path writes progress
    // onto the message's row, which a search window does not have — those
    // messages are shown without being stored.
    fun downloadNow(chatId: String, msgId: String): String {
        val mid = msgId.toLongOrNull() ?: return ""
        // resolved from the message, never from a stored id: TDLib file ids
        // belong to the session that issued them
        val fresh = request(
            JSONObject().put("@type", "getMessage")
                .put("chat_id", chatIdOf(chatId)).put("message_id", mid)
        ) ?: return ""
        val fid = fresh.optJSONObject("content")?.let { fileOf(it) }?.optInt("id") ?: return ""
        if (fid == 0) return ""
        val done = request(
            JSONObject().put("@type", "downloadFile")
                .put("file_id", fid).put("priority", 16).put("synchronous", true),
            timeoutMs = 120_000,
        ) ?: return ""
        val path = done.optJSONObject("local")?.optString("path").orEmpty()
        return if (usable(path)) path else ""
    }

    // A message carries is_listened only when it is fetched, and paging only
    // ever reaches BACKWARD past what is already stored, so a note stored before
    // its recipient listened would keep its dot for good. Run on every chat open
    // and every page, so it converges as you scroll.
    private fun syncPlayedState(chatId: String) {
        // Two repairs share one round-trip because TDLib caps getMessages at
        // 100; the budget is split so placeholders can never starve the contact
        // repair.
        val stale = (Bridge.db.placeholderMessageIds(chatId, 30) +
            Bridge.db.emptyContactSenders(chatId, 10).map { it.first })
            .filter { repairAttempted.add("$chatId/$it") }
        val ids = LinkedHashSet(Bridge.db.unplayedAudioIds(chatId, 60)) + stale
        if (ids.isEmpty()) return
        val wanted = JSONArray()
        for (id in ids) id.toLongOrNull()?.let { wanted.put(it) }
        if (wanted.length() == 0) return
        val res = request(
            JSONObject().put("@type", "getMessages")
                .put("chat_id", chatIdOf(chatId)).put("message_ids", wanted)
        ) ?: return
        val msgs = res.optJSONArray("messages") ?: return
        var changed = false
        for (i in 0 until msgs.length()) {
            // a message the server no longer has comes back as a null entry
            val m = msgs.optJSONObject(i) ?: continue
            val msgId = m.optLong("id").toString()
            val content = m.optJSONObject("content") ?: continue
            if (content.optString("@type") == "messageVoiceNote" &&
                content.optBoolean("is_listened")
            ) {
                Bridge.db.setPlayed(chatId, msgId)
                changed = true
            }
            if (msgId in stale) {
                Bridge.db.deleteMessage(chatId, msgId)
                storeMessageSafe(m)
                changed = true
            }
        }
        if (changed) Bridge.notifyChat(chatId)
    }

    @Volatile private var syncAllChat: String? = null
    @Volatile private var syncAllRounds = 0

    fun syncAllProgress(chatId: String): Int {
        if (syncAllChat != chatId) return -1
        return Bridge.asymptoticProgress(syncAllRounds)
    }

    // Deliberately restarts from the newest message rather than resuming from
    // the oldest row held: only a full walk closes gaps left in the middle by
    // partial syncs, which is the point of asking for all messages.
    fun syncAllHistory(chatId: String): Boolean {
        if (syncAllChat != null && syncAllChat != chatId) return false
        if (syncAllChat == chatId) return true
        syncAllChat = chatId
        syncAllRounds = 0
        historyExhausted.remove(chatId)
        Bridge.notifySyncAll(chatId, 0)
        pager.execute { syncAllStep(chatId, 0L) } // 0 = start at the newest
        return true
    }

    private fun syncAllStep(chatId: String, fromId: Long) {
        if (syncAllChat != chatId) return
        val (count, oldest) = fetchHistoryPage(chatId, fromId, 100)
        when {
            count < 0 -> {
                syncAllChat = null
                Bridge.notifySyncAll(chatId, -1)
            }
            count == 0 -> {
                historyExhausted.add(chatId)
                syncAllChat = null
                Bridge.notifySyncAll(chatId, 100)
            }
            // no older anchor than the one we asked from: the walk cannot
            // advance, so treat it as the end rather than looping on it
            oldest == 0L || oldest == fromId -> {
                historyExhausted.add(chatId)
                syncAllChat = null
                Bridge.notifySyncAll(chatId, 100)
            }
            else -> {
                syncAllRounds++
                Bridge.notifyChat(chatId)
                Bridge.notifySyncAll(chatId, syncAllProgress(chatId))
                pager.execute { syncAllStep(chatId, oldest) }
            }
        }
    }

    @Volatile private var exportChatId: String? = null
    @Volatile private var exportCount = 0

    fun exportProgress(chatId: String): Int = if (exportChatId == chatId) exportCount else -1

    fun exportChat(chatId: String, uri: android.net.Uri): Boolean {
        if (exportChatId != null) return false
        exportChatId = chatId
        exportCount = 0
        pager.execute {
            var complete = true
            var messages = 0
            var success = false
            // try/finally around the whole run: a throw from the paging loop or
            // the writer (SQLite, malformed JSON, OOM on a huge chat) used to
            // leave exportChatId set, which made every later export return false
            // for the rest of the process and left the UI waiting on a
            // completion event that never came.
            try {
                val before = Bridge.db.messageCount(chatId)
                var lastAnchor = -1L
                while (true) {
                    val fromId = Bridge.db.oldestMessage(chatId)?.id?.toLongOrNull() ?: 0L
                    // Same guard as syncAllStep and seekMessage: a page that
                    // does not move the stored oldest id would be re-fetched
                    // forever, pinning a pager thread on a blocking request per
                    // round and never releasing exportChatId.
                    if (fromId == lastAnchor) { complete = false; break }
                    lastAnchor = fromId
                    val count = fetchHistory(chatId, fromId, 100)
                    if (count < 0) { complete = false; break }
                    if (count == 0) { historyExhausted.add(chatId); break }
                    // measured against the store, not summed per page: pages
                    // overlap and re-deliver, and the store dedups by id
                    exportCount = Bridge.db.messageCount(chatId) - before
                    Bridge.postChatExportProgress(chatId, exportCount)
                }
                val ctx = appContext
                if (ctx != null) {
                    val sorted = Bridge.db.messages(chatId, Int.MAX_VALUE)
                    messages = sorted.size
                    ChatExporter.write(ctx, Bridge.db, chatId, uri, sorted)
                    success = true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "export failed: $e")
                complete = false
            } finally {
                exportChatId = null
                Bridge.releaseExportUri(uri)
                Bridge.postChatExportDone(chatId, messages, complete, success)
            }
        }
        return true
    }

    fun seekMessage(chatId: String, targetId: String, fromId: String, maxPages: Int) {
        pager.execute {
            var anchor = fromId.toLongOrNull() ?: 0L
            var pages = maxPages
            while (pages-- > 0) {
                if (Bridge.db.hasMessage(chatId, targetId)) {
                    Bridge.notifySeek(chatId, targetId, true)
                    return@execute
                }
                // the same page fetch the history walk uses, rather than a
                // second hand-built getChatHistory whose own oldest-id rule had
                // already drifted from it
                val (count, oldest) = fetchHistoryPage(chatId, anchor, 100)
                // no page, an empty one, or one that did not reach further back:
                // paging again would re-fetch the same messages forever
                if (count <= 0 || oldest == 0L || oldest == anchor) break
                anchor = oldest
                Bridge.notifyChat(chatId)
            }
            Bridge.notifySeek(chatId, targetId, Bridge.db.hasMessage(chatId, targetId))
        }
    }

    private val avatarPaths = ConcurrentHashMap<String, String>()

    fun avatarPath(chatId: String, big: Boolean = false, cachedOnly: Boolean = false): String {
        val key = chatId + if (big) "/big" else ""
        // Checked, not trusted: TDLib's bookkeeping outlives the file (see
        // usable), and nothing but a photo change drops this entry — so once
        // the file was gone the memo handed the dead path back forever, the
        // decode failed, and that contact's avatar never came back.
        if (!big) avatarPaths[key]?.let { if (usable(it)) return it else avatarPaths.remove(key) }
        // Nothing memoised and the caller cannot afford to wait. Everything past
        // this point is a blocking TDLib round-trip, and the two cached-only
        // callers are the avatar decode pool and the single notify thread, both
        // of which promise not to block — asking here delayed alerts and starved
        // WhatsApp avatar decoding behind a Telegram request.
        if (cachedOnly) return ""
        val chat = request(
            JSONObject().put("@type", "getChat").put("chat_id", chatIdOf(chatId))
        ) ?: return ""
        val photo = chat.optJSONObject("photo") ?: return ""
        val file = photo.optJSONObject(if (big) "big" else "small") ?: return ""
        file.optJSONObject("local")?.let { local ->
            if (local.optBoolean("is_downloading_completed")) {
                val path = local.optString("path")
                if (path.isNotEmpty()) {
                    if (!big) avatarPaths[key] = path
                    return path
                }
            }
        }
        val fid = file.optInt("id")
        val downloaded = request(
            JSONObject().put("@type", "downloadFile")
                .put("file_id", fid).put("priority", 32).put("synchronous", true),
            timeoutMs = 20_000,
        ) ?: return ""
        val path = downloaded.optJSONObject("local")?.optString("path") ?: ""
        if (path.isNotEmpty() && !big) avatarPaths[key] = path
        return path
    }

    class PeerInfo(val phone: String, val username: String, val bio: String)

    // Blocking; worker threads only. Null for anything that is not a user — a
    // group's or channel's raw id is negative.
    fun peerInfo(chatId: String): PeerInfo? {
        val uid = chatIdOf(chatId)
        if (uid <= 0) return null
        val user = request(JSONObject().put("@type", "getUser").put("user_id", uid))
        val full = request(JSONObject().put("@type", "getUserFullInfo").put("user_id", uid))
        return PeerInfo(
            phone = user?.optString("phone_number").orEmpty(),
            username = usernameOf(user),
            bio = full?.optJSONObject("bio")?.optString("text").orEmpty(),
        )
    }

    // TDLib moved a user's handle into a `usernames` object (a user can hold
    // several) and kept the old flat `username` for older schemas; read both so
    // the field doesn't silently go blank on a TDLib bump either way.
    private fun usernameOf(user: JSONObject?): String {
        if (user == null) return ""
        user.optJSONObject("usernames")?.optJSONArray("active_usernames")?.let {
            if (it.length() > 0) return it.optString(0)
        }
        return user.optString("username")
    }

    fun myName(): String =
        listOf(myFirstName, myLastName).filter { it.isNotEmpty() }.joinToString(" ")

    fun myPhone(): String = if (myPhone.isEmpty()) "" else "+$myPhone"

    fun setMyName(name: String): Boolean {
        val parts = name.trim().split(" ", limit = 2)
        val ok = request(
            JSONObject().put("@type", "setName")
                .put("first_name", parts[0])
                .put("last_name", parts.getOrElse(1) { "" })
        ) != null
        if (ok) {
            myFirstName = parts[0]
            myLastName = parts.getOrElse(1) { "" }
        }
        return ok
    }

    fun fetchMyAbout(): String {
        val info = request(
            JSONObject().put("@type", "getUserFullInfo").put("user_id", myId)
        ) ?: return ""
        return info.optJSONObject("bio")?.optString("text") ?: ""
    }

    fun setAbout(text: String): Boolean =
        request(JSONObject().put("@type", "setBio").put("bio", text)) != null

    fun setProfilePicture(jpegPath: String): Boolean {
        val ok = request(
        JSONObject().put("@type", "setProfilePhoto")
            .put(
                "photo",
                JSONObject().put("@type", "inputChatPhotoStatic").put("photo", inputLocalFile(jpegPath))
            )
        ) != null
        if (ok) {
            val self = selfId()
            avatarPaths.remove(self)
            avatarPaths.remove("$self/big")
        }
        return ok
    }

    private val PRIVACY_KEYS = mapOf(
        "last" to "userPrivacySettingShowStatus",
        "profile" to "userPrivacySettingShowProfilePhoto",
        "status" to "userPrivacySettingShowBio",
    )

    fun fetchPrivacySettings(): Map<String, String>? {
        val out = HashMap<String, String>()
        for ((key, setting) in PRIVACY_KEYS) {
            val rules = request(
                JSONObject().put("@type", "getUserPrivacySettingRules")
                    .put("setting", JSONObject().put("@type", setting))
            ) ?: return null
            val arr = rules.optJSONArray("rules")
            var value = "none"
            if (arr != null) {
                loop@ for (i in 0 until arr.length()) {
                    when (arr.getJSONObject(i).optString("@type")) {
                        "userPrivacySettingRuleAllowAll" -> { value = "all"; break@loop }
                        "userPrivacySettingRuleAllowContacts" -> { value = "contacts"; break@loop }
                        "userPrivacySettingRuleRestrictAll" -> { value = "none"; break@loop }
                    }
                }
            }
            out[key] = value
        }
        return out
    }

    fun setPrivacySetting(name: String, value: String): Boolean {
        val setting = PRIVACY_KEYS[name] ?: return false
        val rule = when (value) {
            "all" -> "userPrivacySettingRuleAllowAll"
            "contacts" -> "userPrivacySettingRuleAllowContacts"
            "none" -> "userPrivacySettingRuleRestrictAll"
            else -> return false
        }
        return request(
            JSONObject().put("@type", "setUserPrivacySettingRules")
                .put("setting", JSONObject().put("@type", setting))
                .put(
                    "rules",
                    JSONObject().put("@type", "userPrivacySettingRules")
                        .put("rules", JSONArray().put(JSONObject().put("@type", rule)))
                )
        ) != null
    }
}
