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

/**
 * Telegram client over TDLib's JSON interface. Mirrors chats/messages into the
 * shared local Db under "tg:"-prefixed ids and reports UI events through
 * Bridge's listener plumbing, so the existing screens work unchanged.
 *
 * Threading: one receive loop thread handles every TDLib update; blocking
 * request/response pairs are correlated via "@extra". Heavier follow-up work
 * (history pages, exports) runs on its own executor.
 */
object Tg {

    private const val TAG = "UniChatTg"

    /** Chat/user ids are namespaced so they can share the Db with WhatsApp. */
    const val PREFIX = "tg:"

    fun isTgId(id: String): Boolean = id.startsWith(PREFIX)
    private fun chatIdOf(id: String): Long = id.removePrefix(PREFIX).toLongOrNull() ?: 0L
    private fun idFor(raw: Long): String = PREFIX + raw

    // Same app credentials nchat embeds (hex-encoded there); TG_APIID/TG_APIHASH
    // equivalents. Replace with our own registration eventually.
    private val API_ID = String(byteArrayOf(0x31, 0x30, 0x34, 0x31, 0x32, 0x30, 0x32, 0x37))
    private val API_HASH = decodeHex(
        "3536373261353832633265666532643939363232326636343237386563616163"
    )

    private fun decodeHex(hex: String): String =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().decodeToString()

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

    // Short user-driven actions (auth, mute, mark-read, per-update refreshes).
    private val executor = Executors.newSingleThreadExecutor()
    // Unbounded paging work — export, sync-all, seek, the initial chat-list
    // load. These occupy a thread for minutes at a time, so they must not sit
    // on `executor`, where they would stall opening a chat, muting or logging
    // out for the whole run.
    private val pager = Executors.newSingleThreadExecutor()
    // Media downloads: a small pool of their own. On the pager they queued
    // behind the startup chat-list load and every history page — with a
    // blocking request each, a screenful of photos took minutes to appear.
    private val downloader = Executors.newFixedThreadPool(3)

    /**
     * Blocking Telegram calls made from UI code (profile, privacy). Kept off
     * Io.executor, the app-wide serial worker every screen's DB reads share:
     * one request() can block for 15s, which would stall the chat list behind it.
     */
    val io: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor()

    private val pending = ConcurrentHashMap<Long, Pair<CountDownLatch, Array<JSONObject?>>>()
    private val nextExtra = AtomicLong(1)

    // last-read markers per raw chat id, so stored rows get the right is_read
    private val readInbox = ConcurrentHashMap<Long, Long>()
    private val readOutbox = ConcurrentHashMap<Long, Long>()
    // file id -> every (chatId, msgId) waiting on that download. A set, not a
    // single pair: TDLib dedups files by remote id, so the same sticker or a
    // re-sent photo shares one file id across messages, and keeping only the
    // last one left the earlier bubbles stuck on a spinner forever.
    private val fileTargets = ConcurrentHashMap<Int, MutableSet<Pair<String, String>>>()
    // one in-flight history request per chat
    private val historyBusy = CopyOnWriteArraySet<String>()
    private val historyExhausted = CopyOnWriteArraySet<String>()
    // Messages already re-fetched once by the placeholder repair. A content
    // type this build still does not map re-stores the SAME "[Type]" text, so
    // without this it was deleted and re-inserted on every chat open and every
    // history page — churning rowids (the order tiebreaker), rewriting its
    // reactions, and eating the repair budget that stale voice notes need.
    private val repairAttempted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun hasSession(): Boolean = appContext?.let { Prefs.tgLinked(it) } == true

    fun selfId(): String = idFor(myId)

    /** Starts the TDLib client and its receive loop. Idempotent. */
    @Synchronized
    fun init(context: Context) {
        if (clientId >= 0) return
        appContext = context.applicationContext
        runCatching {
            appVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: appVersion
        }
        clientId = TdJson.createClientId()
        Thread({ receiveLoop() }, "tg-receive").start()
        // any request kicks TDLib into delivering updateAuthorizationState
        send(JSONObject().put("@type", "getOption").put("name", "version"))
    }

    // --- request plumbing ----------------------------------------------------

    private fun send(obj: JSONObject) {
        val id = clientId
        if (id >= 0) TdJson.send(id, obj.toString())
    }

    /** Sends [obj] and blocks (up to [timeoutMs]) for its "@extra" response. */
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
                    // responses that are also fresh data (e.g. messages) still
                    // fall through to onUpdate via their own update events
                    continue
                }
                onUpdate(obj)
            } catch (e: Throwable) {
                Log.e(TAG, "update handling failed", e)
            }
        }
    }

    // --- auth -----------------------------------------------------------------

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
                        .put("api_id", API_ID.toInt())
                        .put("api_hash", API_HASH)
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
                Bridge.notifyTgAuth("ready", "")
                onReady()
            }
            "authorizationStateClosed" -> {
                // logout completed: TDLib wiped its database AND its files
                // directory, so every cached path/id below now points at
                // something that no longer exists. Left in place they surfaced
                // as the previous account's avatars, a permanently "exhausted"
                // history and a stale selfId after the next login.
                readInbox.clear()
                readOutbox.clear()
                fileTargets.clear()
                historyExhausted.clear()
                historyBusy.clear()
                avatarPaths.clear()
                syncAllChat = null
                exportChatId = null
                myId = 0
                myFirstName = ""
                myLastName = ""
                myPhone = ""
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

    // Error text of the most recent failed request, for the auth screen.
    @Volatile private var lastError: String = ""

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

    private fun onReady() = pager.execute {
        request(JSONObject().put("@type", "getMe"))?.let { me ->
            myId = me.optLong("id")
            myFirstName = me.optString("first_name")
            myLastName = me.optString("last_name")
            myPhone = me.optString("phone_number")
        }
        // pull the whole main chat list; chats arrive via updateNewChat.
        // loadChats returns ok per page and error 404 once the end is reached
        // (request() maps both error and timeout to null), so page until then.
        var pages = 20
        while (pages-- > 0) {
            request(JSONObject().put("@type", "loadChats").put("limit", 100)) ?: break
        }
    }

    // --- updates ---------------------------------------------------------------

    private fun onUpdate(obj: JSONObject) {
        when (obj.optString("@type")) {
            "updateAuthorizationState" -> onAuthState(obj.getJSONObject("authorization_state"))
            "updateConnectionState" -> {
                state = when (obj.getJSONObject("state").optString("@type")) {
                    "connectionStateReady" -> "connected"
                    "connectionStateWaitingForNetwork" -> "disconnected"
                    else -> "connecting"
                }
                Bridge.notifyTgState()
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
                // re-fetch the message for a consistent row (edit, poll update…)
                executor.execute {
                    val fresh = request(
                        JSONObject().put("@type", "getMessage")
                            .put("chat_id", obj.getLong("chat_id")).put("message_id", msgId)
                    )
                    if (fresh != null) storeMessage(fresh)
                    Bridge.notifyChat(chatId)
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
                Bridge.db.deleteMessage(idFor(msg.getLong("chat_id")), obj.getLong("old_message_id").toString())
                Bridge.notifyChat(idFor(msg.getLong("chat_id")))
                Bridge.toastUi(R.string.send_failed)
            }
            "updateDeleteMessages" -> {
                // is_permanent=false events are cache drops, not real deletions
                if (obj.optBoolean("is_permanent")) {
                    val chatId = idFor(obj.getLong("chat_id"))
                    val ids = obj.getJSONArray("message_ids")
                    for (i in 0 until ids.length()) {
                        Bridge.db.deleteMessage(chatId, ids.getLong(i).toString())
                    }
                    Bridge.notifyChat(chatId)
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
            "updateUser" -> onUser(obj.getJSONObject("user"))
            "updateUserStatus" ->
                applyUserStatus(idFor(obj.getLong("user_id")), obj.optJSONObject("status"))
            "updateChatAction" -> {
                val chatId = idFor(obj.getLong("chat_id"))
                val uid = obj.getJSONObject("sender_id").optLong("user_id")
                val action = obj.getJSONObject("action").optString("@type")
                val st = when (action) {
                    "chatActionTyping" -> "typing"
                    "chatActionRecordingVoiceNote" -> "recording"
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
        val muted = chat.optJSONObject("notification_settings")?.optInt("mute_for", 0) ?: 0
        if (muted > 0) Bridge.db.setMuted(id, true)
        last?.let { storeMessage(it) }
        // group flag for the chat list (ids alone can't tell for basic groups)
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

    /**
     * Publishes a user's presence. Telegram gives an exact time only when both
     * sides share their last-seen; otherwise it reports a bucket ("recently",
     * "within a week", "within a month"), which is all its own clients show too.
     */
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

    // --- message mapping --------------------------------------------------------

    // Maps one TDLib message into the shared row shape and stores it.
    private fun storeMessage(msg: JSONObject, notify: Boolean = false) {
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

        val content = msg.optJSONObject("content") ?: return
        var msgType = ""
        var text = ""
        // The backing file is resolved once, by the same navigation the download
        // path uses. Each media branch used to re-walk its own content shape, so
        // the stored reference and the fetched file could drift apart — the
        // failure startDownload's comment below describes. Content kinds with no
        // file (text, emoji) get "", and location overwrites it with coordinates.
        var fileId = fileOf(content)?.optInt("id")?.toString() ?: ""
        var listened = false
        when (content.optString("@type")) {
            "messageText" -> text = content.getJSONObject("text").optString("text")
            "messagePhoto" -> {
                msgType = "image"
                text = content.optJSONObject("caption")?.optString("text") ?: ""
            }
            "messageVideo" -> {
                msgType = "video"
                text = content.optJSONObject("caption")?.optString("text") ?: ""
            }
            // a round "video note" plays like any other video for us
            "messageVideoNote" -> msgType = "video"
            "messageAnimation" -> {
                msgType = "video"
                text = content.optJSONObject("caption")?.optString("text") ?: ""
            }
            "messageVoiceNote" -> {
                msgType = "audio"
                val secs = content.getJSONObject("voice_note").optInt("duration")
                text = TimeFormat.mmss(secs)
                // the recipient played it (or we played a received one): this is
                // the only signal that clears the unplayed dot, and it arrives as
                // an updateMessageContent long after the message was stored
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
                    msgType = "image"
                } else {
                    // animated/video stickers have no still frame to show, so the
                    // row is text and must not claim a downloadable file
                    fileId = ""
                    text = sticker.optString("emoji").ifEmpty { "🩹" } + " (sticker)"
                }
            }
            // A message that is just emoji comes as its own content type with the
            // plain characters in `emoji`; without this it fell through to the
            // generic placeholder and rendered as "[AnimatedEmoji]".
            // the placeholder fallback when `emoji` is absent, so the row stays
            // recognisable to the repair pass instead of rendering blank
            "messageAnimatedEmoji", "messageDice" ->
                text = content.optString("emoji").ifEmpty { placeholderFor(content) }
            "messageLocation" -> {
                msgType = "location"
                val loc = content.getJSONObject("location")
                fileId = "${loc.optDouble("latitude")},${loc.optDouble("longitude")}"
                text = "%.5f, %.5f".format(loc.optDouble("latitude"), loc.optDouble("longitude"))
            }
            "messageCall" -> text = "📞 Call"
            "messageChatChangeTitle" -> text = "· " + content.optString("title")
            else -> text = placeholderFor(content)
        }

        var quotedId = ""
        msg.optJSONObject("reply_to")?.let {
            if (it.optString("@type") == "messageReplyToMessage" &&
                it.optLong("chat_id") == rawChat
            ) {
                quotedId = it.optLong("message_id").toString()
            }
        }

        // file already on disk in TDLib's store? reuse its path right away
        var filePath = ""
        var fileStatus = 0
        localPathOf(msg, content)?.let { filePath = it; fileStatus = 2 }

        val senderName = if (senderId != chatId) Bridge.db.contactName(senderId) ?: "" else ""
        Bridge.db.upsertMessage(
            MessageRow(
                msgId.toString(), chatId, senderId, text, fromMe, timeSent, isRead,
                msgType = msgType, fileId = fileId,
                edited = msg.optLong("edit_date") > 0, quotedId = quotedId,
                senderName = senderName,
                forwarded = msg.optJSONObject("forward_info") != null,
            )
        )
        if (filePath.isNotEmpty()) {
            Bridge.db.setFileState(chatId, msgId.toString(), filePath, fileStatus)
        }
        // unconditional, not only when interaction_info is present: a message
        // whose last reaction was removed comes back carrying none, and skipping
        // it left the stale rows in place
        applyReactions(chatId, msgId.toString(), msg.optJSONObject("interaction_info"), preview = false)
        // upsertMessage deliberately never writes `played`, so apply it here
        if (listened) Bridge.db.setPlayed(chatId, msgId.toString())
        Bridge.db.bumpChat(chatId, timeSent)
        if (notify && !fromMe && !isRead) {
            // eager-fetch images and voice notes for live messages, like WhatsApp
            if ((msgType == "image" || msgType == "audio") && fileId.isNotEmpty() && filePath.isEmpty()) {
                downloadFile(MessageRow(msgId.toString(), chatId, senderId, text, fromMe, timeSent, isRead, msgType = msgType, fileId = fileId))
            }
            if (chatId != Bridge.activeChatId && !Bridge.db.isMuted(chatId)) {
                Bridge.postMessageNotification(chatId, senderId, text, msgType, timeSent)
            }
        }
        Bridge.notifyChat(chatId)
    }

    /** Stand-in for a content type this build does not render, e.g. "[Poll]". */
    private fun placeholderFor(content: JSONObject): String =
        "[" + content.optString("@type").removePrefix("message") + "]"

    // The local file path inside a message's content, if already downloaded.
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

    /** The file object backing a message content, whatever its media type. */
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
        Bridge.notifyChat(chatId)
    }

    /**
     * Mirrors a message's reactions into the Db. Called both for the live
     * update AND when a message is stored: a message fetched from history
     * already carries its reactions in interaction_info, and reading them only
     * from the update meant every reaction that predated this session stayed
     * invisible.
     */
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

    // --- downloads ---------------------------------------------------------------

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

    /**
     * Resolves the message's CURRENT file and downloads that.
     *
     * The stored id is never used to fetch. A TDLib file id is an index into the
     * session that issued it, and TDLib hands the same small integers out again
     * to unrelated files in later runs — so a stored id can now name a totally
     * different photo, which downloaded happily and was written onto this
     * message as if it belonged to it. That is how one file ended up rendering
     * in four messages across four different chats. Asking the message which
     * file it has is the only answer that cannot be stale.
     */
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

    /**
     * Asks TDLib for one file. False when it rejects the id. A file it already
     * holds is finished here and now: downloadFile answers with the completed
     * file and no updateFile follows, because nothing changed — waiting for one
     * left the bubble on its spinner for good.
     */
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
            Bridge.notifyChat(msg.chatId)
            Bridge.onTgFileDone(msg.chatId, msg.id, done, 2)
        }
        return true
    }

    /** Where TDLib says a fully downloaded file sits, or null if it isn't. */
    private fun completedAt(res: JSONObject): String? {
        val local = res.optJSONObject("local") ?: return null
        if (!local.optBoolean("is_downloading_completed")) return null
        return local.optString("path")
    }

    /**
     * TDLib calls a file "downloaded" from its own bookkeeping, which outlives
     * the file itself: our sends point at the cacheDir staging copy that the
     * daily sweep deletes. Writing such a path back onto the message re-created
     * the dead reference the caller had just cleared, and bind → download →
     * "complete" → bind went round for good, so a path that does not resolve
     * counts as no download at all.
     */
    private fun usable(path: String) = path.isNotEmpty() && java.io.File(path).exists()

    private fun downloadRequest(fid: Int) = JSONObject().put("@type", "downloadFile")
        .put("file_id", fid).put("priority", 16).put("synchronous", false)

    private fun failDownload(msg: MessageRow) {
        Bridge.db.setFileState(msg.chatId, msg.id, "", 3)
        Bridge.notifyChat(msg.chatId)
        Bridge.onTgFileDone(msg.chatId, msg.id, "", 3)
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
                    Bridge.notifyChat(chatId)
                    Bridge.onTgFileDone(chatId, msgId, if (ok) path else "", if (ok) 2 else 3)
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
                    Bridge.notifyChat(chatId)
                    Bridge.onTgFileDone(chatId, msgId, "", 3)
                }
            }
        }
    }

    // --- sending -------------------------------------------------------------------

    private fun replyTo(quotedId: String): JSONObject? =
        quotedId.toLongOrNull()?.let {
            JSONObject().put("@type", "inputMessageReplyToMessage").put("message_id", it)
        }

    private fun sendMessage(chatId: String, content: JSONObject, quotedId: String = ""): Boolean {
        val req = JSONObject().put("@type", "sendMessage")
            .put("chat_id", chatIdOf(chatId))
            .put("input_message_content", content)
        replyTo(quotedId)?.let { req.put("reply_to", it) }
        // the response is the pending message; updateNewMessage stores it
        return request(req) != null
    }

    private fun formattedText(text: String) =
        JSONObject().put("@type", "formattedText").put("text", text)

    private fun inputLocalFile(path: String) =
        JSONObject().put("@type", "inputFileLocal").put("path", path)

    fun sendText(chatId: String, text: String, quotedId: String = ""): Boolean = sendMessage(
        chatId,
        JSONObject().put("@type", "inputMessageText").put("text", formattedText(text)),
        quotedId,
    )

    // This TDLib schema wraps media files in inputPhoto/inputVideo/… objects
    // (not bare InputFile — that parses as null and the send fails with
    // "InputFile is not specified").
    fun sendImage(chatId: String, path: String, caption: String, quotedId: String = ""): Boolean =
        sendMessage(
            chatId,
            JSONObject().put("@type", "inputMessagePhoto")
                .put("photo", JSONObject().put("@type", "inputPhoto").put("photo", inputLocalFile(path)))
                .put("caption", formattedText(caption)),
            quotedId,
        )

    fun sendVideo(chatId: String, path: String, caption: String, quotedId: String = ""): Boolean =
        sendMessage(
            chatId,
            JSONObject().put("@type", "inputMessageVideo")
                .put(
                    "video",
                    JSONObject().put("@type", "inputVideo")
                        .put("video", inputLocalFile(path))
                        .put("supports_streaming", true)
                )
                .put("caption", formattedText(caption)),
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

    /**
     * TDLib names a document after the basename of the file it is given, and
     * attachments are staged as "<prefix>_<millis>_<name>", so the recipient
     * saw that internal name. Hand it a correctly-named path instead: hard-link
     * where the filesystem allows it, copy otherwise. The link/copy lives in a
     * per-send directory that Bridge.cleanStaleCache reclaims by age — the
     * upload continues after this call returns, so it cannot be deleted here.
     */
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
            // remove OUR chosen reaction(s): the empty-emoji contract carries no
            // emoji to remove, so look the chosen types up first
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

    /**
     * Tells TDLib the chat is on screen. Required, not an optimisation: for a
     * private chat DialogActionManager drops every incoming typing/recording
     * action unless the dialog is open (or the peer's last-seen is exact), and
     * supergroups/channels only receive updates at all while open — which is
     * why chat actions never appeared before this was wired up.
     */
    fun openChat(chatId: String) = executor.execute {
        send(JSONObject().put("@type", "openChat").put("chat_id", chatIdOf(chatId)))
    }

    fun closeChat(chatId: String) = executor.execute {
        send(JSONObject().put("@type", "closeChat").put("chat_id", chatIdOf(chatId)))
    }

    /**
     * Marks an incoming voice note as listened, which is what clears the
     * unplayed dot on the SENDER's side. viewMessages alone only marks it read.
     */
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
                        // "forever" per Telegram convention
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

    // --- history ---------------------------------------------------------------------

    fun isHistoryExhausted(chatId: String): Boolean = chatId in historyExhausted

    /**
     * Fetches one older page into the Db. [onDone] gets the number of stored
     * messages (0 = start of history reached), -1 on failure/busy.
     */
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

    // Blocking history fetch; returns stored count, -1 on error. Executor only.
    private fun fetchHistory(chatId: String, fromMsgId: Long, limit: Int): Int =
        fetchHistoryPage(chatId, fromMsgId, limit).first

    /**
     * Stores one page and reports (count, oldest id in it). The oldest id is
     * what a full walk anchors its next page on: anchoring on the oldest row in
     * the DB instead only ever extends the history backwards, so a hole between
     * two already-synced stretches could never be filled.
     */
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
            storeMessage(m)
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
                // from 0 = newest; fills the visible window
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

    /**
     * Re-checks voice notes this chat still shows as unplayed. A message
     * carries is_listened only when it is fetched, and paging only ever reaches
     * BACKWARD past what is already stored, so a note stored before its
     * recipient listened would keep its dot for good. Bounded to one
     * getMessages call (TDLib caps it at 100) and run whenever the chat is
     * opened or another page is pulled in, so it converges as you scroll.
     */
    private fun syncPlayedState(chatId: String) {
        // Two repairs share one round-trip (TDLib caps getMessages at 100):
        // voice notes whose listened flag we may have missed, and messages
        // stored as a "[SomeType]" placeholder by an older build that did not
        // understand their content type yet.
        val stale = Bridge.db.placeholderMessageIds(chatId, 40)
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
            val msgId = m.getLong("id").toString()
            val content = m.optJSONObject("content") ?: continue
            if (content.optString("@type") == "messageVoiceNote" &&
                content.optBoolean("is_listened")
            ) {
                Bridge.db.setPlayed(chatId, msgId)
                changed = true
            }
            if (msgId in stale) {
                // dropped and re-stored, since the row's type is what is wrong
                Bridge.db.deleteMessage(chatId, msgId)
                storeMessage(m)
                changed = true
            }
        }
        if (changed) Bridge.notifyChat(chatId)
    }

    // sync-all: page until the start of the chat is reached
    @Volatile private var syncAllChat: String? = null
    @Volatile private var syncAllRounds = 0

    fun syncAllProgress(chatId: String): Int {
        if (syncAllChat != chatId) return -1
        return Bridge.asymptoticProgress(syncAllRounds)
    }

    /**
     * Walks the chat's whole history from the newest message backwards, storing
     * every page. Deliberately restarts from the top rather than resuming from
     * the oldest row held: only a full walk closes gaps left in the middle by
     * partial syncs, which is the point of asking for all messages.
     */
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

    // --- export -----------------------------------------------------------------------

    @Volatile private var exportChatId: String? = null
    @Volatile private var exportCount = 0

    fun exportProgress(chatId: String): Int = if (exportChatId == chatId) exportCount else -1

    /** Pages the full history into the Db, then writes the export file. */
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
                while (true) {
                    val fromId = Bridge.db.oldestMessage(chatId)?.id?.toLongOrNull() ?: 0L
                    val count = fetchHistory(chatId, fromId, 100)
                    if (count < 0) { complete = false; break }
                    if (count == 0) { historyExhausted.add(chatId); break }
                    exportCount += count
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
                Bridge.postChatExportDone(chatId, messages, complete, success)
            }
        }
        return true
    }

    // --- jump-to-quote seek --------------------------------------------------------------

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

    // --- avatars ------------------------------------------------------------------------

    // Small on-disk cache of resolved avatar paths, so list binds don't re-ask.
    private val avatarPaths = ConcurrentHashMap<String, String>()

    /** Path of a chat's small profile photo, downloading it if needed. Blocking. */
    fun avatarPath(chatId: String, big: Boolean = false, cachedOnly: Boolean = false): String {
        val key = chatId + if (big) "/big" else ""
        if (!big) avatarPaths[key]?.let { return it }
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

    // --- own profile ----------------------------------------------------------------------

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

    // --- privacy ------------------------------------------------------------------------------

    // UI key -> TDLib userPrivacySetting type
    private val PRIVACY_KEYS = mapOf(
        "last" to "userPrivacySettingShowStatus",
        "profile" to "userPrivacySettingShowProfilePhoto",
        "status" to "userPrivacySettingShowBio",
    )

    /** Same key/value shape the WhatsApp privacy screen uses; null on failure. */
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
