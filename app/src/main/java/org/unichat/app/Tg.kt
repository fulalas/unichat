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
    private const val LISTENED_PAGES = 12

    private const val MEMBER_PAGE = 200
    private const val REACTION_PAGE = 50
private const val UNREAD_REACTION_PAGE = 100

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

    private val listed = ConcurrentHashMap.newKeySet<Long>()

    private val executor = Executors.newSingleThreadExecutor()
    private val pager = Executors.newSingleThreadExecutor()
    private val downloader = Executors.newFixedThreadPool(3)

    val io: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor()

    internal fun <T> async(work: () -> T, onResult: (T) -> Unit) = io.execute {
        val result = work()
        Bridge.runOnUi { onResult(result) }
    }

    private val pending = ConcurrentHashMap<Long, Pair<CountDownLatch, Array<JSONObject?>>>()
    private val nextExtra = AtomicLong(1)

    @Volatile private var ready = false
    @Volatile private var readyLatch = CountDownLatch(1)
    private val openCounts = ConcurrentHashMap<String, Int>()

    private val readInbox = ConcurrentHashMap<Long, Long>()
    private val readOutbox = ConcurrentHashMap<Long, Long>()
    private val fileTargets = ConcurrentHashMap<Int, MutableSet<Pair<String, String>>>()
    private val historyBusy = CopyOnWriteArraySet<String>()
    private val historyExhausted = CopyOnWriteArraySet<String>()
    private val repairAttempted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val refetchGen = AtomicLong(0)

    fun hasSession(): Boolean = appContext?.let { Prefs.tgLinked(it) } == true

    fun selfId(): String = if (myId == 0L) "" else idFor(myId)

    fun selfIdBlocking(): String {
        selfId().let { if (it.isNotEmpty()) return it }
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
        send(JSONObject().put("@type", "setLogVerbosityLevel").put("new_verbosity_level", 1))
        Thread({ receiveLoop() }, "tg-receive").start()
        send(JSONObject().put("@type", "getOption").put("name", "version"))
    }

    private fun send(obj: JSONObject) {
        val id = clientId
        if (id >= 0) TdJson.send(id, obj.toString())
    }

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
                executor.execute {
                    if (!ready) {
                        ready = true
                        for ((chatId, n) in openCounts) repeat(n) { sendOpenChat(chatId) }
                    }
                }
                Bridge.notifyTgAuth("ready", "")
                appContext?.let {
                    if (!Prefs.protoEnabled(it, ProtoPicker.TG)) setNetworkEnabled(false)
                }
                onReady()
            }
            "authorizationStateClosed" -> {
                ready = false
                readyLatch = CountDownLatch(1)
                openCounts.clear()
                readInbox.clear()
                readOutbox.clear()
                fileTargets.clear()
                historyExhausted.clear()
                historyBusy.clear()
                repairAttempted.clear()
                listenedSwept.clear()
                avatarPaths.clear()
                contactNames.clear()
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
                clientId = TdJson.createClientId()
                send(JSONObject().put("@type", "getOption").put("name", "version"))
            }
        }
    }

    private fun authStep(req: JSONObject, failState: String) {
        if (request(req) == null) {
            Bridge.notifyTgAuth(failState, lastError.ifEmpty { "" })
        }
    }

    @Volatile private var lastError: String = ""

    fun authErrorText(ctx: Context, message: String): String = when {
        message.isEmpty() -> ctx.getString(R.string.tg_auth_failed)
        message.startsWith("PHONE_NUMBER_INVALID") -> ctx.getString(R.string.tg_err_phone_invalid)
        message.startsWith("PHONE_CODE_INVALID") -> ctx.getString(R.string.tg_err_code_invalid)
        message.startsWith("PHONE_CODE_EXPIRED") -> ctx.getString(R.string.tg_err_code_expired)
        message.startsWith("PASSWORD_HASH_INVALID") -> ctx.getString(R.string.tg_err_password_invalid)
        message.startsWith("FLOOD_WAIT_") ->
            message.removePrefix("FLOOD_WAIT_").toIntOrNull()?.let {
                ctx.resources.getQuantityString(R.plurals.tg_err_flood, it, it)
            } ?: ctx.getString(R.string.tg_err_flood_unknown)
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
            "updateChatPhoto" -> {
                val chatId = idFor(obj.getLong("chat_id"))
                avatarPaths.remove(chatId)
                AvatarLoader.invalidate(chatId)
                Bridge.notifyChatsChanged()
            }
            "updateChatTitle" -> {
                Bridge.db.renameChat(idFor(obj.getLong("chat_id")), obj.optString("title"))
                Bridge.notifyChatsChanged()
            }
            "updateChatLastMessage" -> {
                obj.optJSONObject("last_message")?.let { storeMessage(it) }
                val raw = obj.getLong("chat_id")
                if (obj.optJSONArray("positions")?.length() == 0) checkChatGone(raw)
                else listed.add(raw)
            }
            "updateNewMessage" -> {
                val msg = obj.getJSONObject("message")
                storeMessage(msg, notify = true)
            }
            "updateMessageContent" -> {
                val chatId = idFor(obj.getLong("chat_id"))
                val msgId = obj.getLong("message_id")
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
            "updateMessageEdited" -> {  }
            "updateMessageSendSucceeded" -> {
                val msg = obj.getJSONObject("message")
                val oldId = obj.getLong("old_message_id")
                val chatId = idFor(msg.getLong("chat_id"))
                Bridge.onMessageSendOk(chatId, oldId.toString())
                Bridge.db.deleteMessage(chatId, oldId.toString())
                storeMessage(msg)
            }
            "updateMessageSendFailed" -> {
                val msg = obj.getJSONObject("message")
                Bridge.onMessageSendFailed(
                    idFor(msg.getLong("chat_id")), obj.getLong("old_message_id").toString()
                )
            }
            "updateDeleteMessages" -> {
                if (obj.optBoolean("is_permanent")) {
                    val chatId = idFor(obj.getLong("chat_id"))
                    val ids = obj.getJSONArray("message_ids")
                    executor.execute {
                        refetchGen.incrementAndGet()
                        for (i in 0 until ids.length()) {
                            val msgId = ids.getLong(i).toString()
                            if (cancelledSends.remove("$chatId/$msgId")) continue
                            Bridge.db.deleteMessage(chatId, msgId)
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
                    "chatActionRecordingVoiceNote", "chatActionUploadingVoiceNote" -> "recording"
                    "chatActionCancel" -> "paused"
                    else -> return
                }
                Bridge.onChatState(chatId, idFor(uid), st)
            }
            "updateMessageContentOpened" -> {
                val chatId = idFor(obj.getLong("chat_id"))
                Bridge.db.setPlayed(chatId, obj.getLong("message_id").toString())
                Bridge.notifyChat(chatId)
            }
            "updateChatPosition" -> {
                val p = obj.getJSONObject("position")
                val present = p.optString("order", "0") != "0"
                if (present) listed.add(obj.getLong("chat_id"))
                val archived = when (p.getJSONObject("list").optString("@type")) {
                    "chatListArchive" -> present
                    "chatListMain" -> if (present) false else {
                        checkChatGone(obj.getLong("chat_id"))
                        return
                    }
                    else -> return
                }
                Bridge.db.setArchived(idFor(obj.getLong("chat_id")), archived)
                Bridge.notifyChatsChanged()
            }
            "updateFile" -> onFile(obj.getJSONObject("file"))
            "updateMessageInteractionInfo" -> onInteractionInfo(obj)
            "updateChatUnreadReactionCount" -> {
                if (obj.optInt("unread_reaction_count") > 0) {
                    fetchUnreadReactions(idFor(obj.getLong("chat_id")))
                }
            }
            "updateChatNotificationSettings" -> {
                val raw = obj.getLong("chat_id")
                val muted = obj.getJSONObject("notification_settings").optInt("mute_for") > 0
                Bridge.db.setMuted(idFor(raw), muted)
                Bridge.notifyChatsChanged()
            }
        }
    }

    private fun checkChatGone(raw: Long) {
        if (raw !in listed) return
        val id = idFor(raw)
        pager.execute {
            val chat = request(JSONObject().put("@type", "getChat").put("chat_id", raw))
                ?: return@execute
            if ((chat.optJSONArray("positions")?.length() ?: 0) > 0) return@execute
            listed.remove(raw)
            Bridge.onChatDeletedRemotely(id, deleteMedia = true)
        }
    }

    private fun onNewChat(chat: JSONObject) {
        val raw = chat.getLong("id")
        val id = idFor(raw)
        val title = chat.optString("title")
        var archived = false
        chat.optJSONArray("positions")?.let { positions ->
            if (positions.length() > 0) listed.add(raw)
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
        Bridge.db.setMuted(id, muted > 0)
        last?.let { storeMessage(it) }
        if (chat.optInt("unread_reaction_count") > 0) fetchUnreadReactions(id)
        val type = chat.optJSONObject("type")?.optString("@type") ?: ""
        if (type == "chatTypeBasicGroup" || type == "chatTypeSupergroup") {
            contactNames[id] = title
            Bridge.db.upsertContact(id, title, "", isSelf = false, isGroup = true, isSaved = false)
        }
        Bridge.notifyChatsChanged()
    }

    private fun fullName(user: JSONObject): String =
        listOf(user.optString("first_name"), user.optString("last_name"))
            .filter { it.isNotEmpty() }.joinToString(" ")

    private val contactNames = ConcurrentHashMap<String, String>()

    private fun onUser(user: JSONObject) {
        val uid = user.getLong("id")
        val name = fullName(user)
        contactNames[idFor(uid)] = name
        Bridge.db.upsertContact(
            idFor(uid), name, user.optString("phone_number"),
            isSelf = uid == myId, isGroup = false,
            isSaved = user.optBoolean("is_contact"),
        )
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

    private fun parseMessage(msg: JSONObject): MessageRow? {
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
            "messageVideoNote" -> msgType = "videonote"
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
                    fileId = ""
                    val emoji = sticker.optString("emoji").ifEmpty { "🩹" }
                    text = appContext?.getString(R.string.sticker_with_emoji, emoji) ?: emoji
                }
            }
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
                content.optJSONObject("contact")?.let { c ->
                    val phone = c.optString("phone_number")
                        .let { if (it.isNotEmpty() && !it.startsWith("+")) "+$it" else it }
                    val name = fullName(c).ifEmpty { phone }
                    text = listOf(name, phone).filter { it.isNotEmpty() }.joinToString("\n")
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

        val senderName = if (senderId != chatId) {
            contactNames.getOrPut(senderId) { Bridge.db.contactName(senderId) ?: "" }
        } else ""
        return MessageRow(
            msgId.toString(), chatId, senderId, text, fromMe, timeSent, isRead,
            msgType = msgType, fileId = fileId,
            filePath = filePath, fileStatus = fileStatus,
            latitude = latitude, longitude = longitude,
            edited = msg.optLong("edit_date") > 0, quotedId = quotedId,
            quotedText = quotedText, quotedType = quotedType,
            senderName = senderName,
            forwarded = msg.optJSONObject("forward_info") != null,
            played = listened,
        )
    }

    private fun storeMessage(msg: JSONObject, notify: Boolean = false) {
        val row = parseMessage(msg) ?: return
        if (row.fromMe && msg.optJSONObject("sending_state")
                ?.optString("@type") == "messageSendingStatePending" &&
            !Bridge.db.hasMessage(row.chatId, row.id)
        ) {
            return
        }
        Bridge.ingestMessage(
            row,
            notify = notify,
            fetchMedia = notify && !row.fromMe && !row.isRead,
            bump = true,
        ) {
            if (row.filePath.isNotEmpty()) {
                Bridge.db.setFileState(row.chatId, row.id, row.filePath, row.fileStatus)
            }
            applyReactions(row.chatId, row.id, msg.optJSONObject("interaction_info"), preview = false)
            if (row.played) Bridge.db.setPlayed(row.chatId, row.id)
            if (row.msgType == "videonote") {
                Bridge.db.setMsgType(row.chatId, row.id, row.msgType)
            }
        }
    }

    private fun storeMessageSafe(msg: JSONObject) {
        try { storeMessage(msg) } catch (e: Exception) { Log.e(TAG, "message store failed", e) }
    }

    private fun parseMessageSafe(msg: JSONObject): MessageRow? =
        try { parseMessage(msg) } catch (e: Exception) { Log.e(TAG, "message parse failed", e); null }

    private fun placeholderFor(content: JSONObject): String =
        "[" + content.optString("@type").removePrefix("message") + "]"

    private fun localPathOf(msg: JSONObject, content: JSONObject): String? {
        val file = fileOf(content) ?: return null
        val local = file.optJSONObject("local") ?: return null
        if (!local.optBoolean("is_downloading_completed")) return null
        val path = local.optString("path")
        return if (usable(path)) path else null
    }

    private fun fileOf(content: JSONObject): JSONObject? =
        when (content.optString("@type")) {
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

    private fun fetchUnreadReactions(chatId: String) = io.execute {
        val answer = request(
            JSONObject().put("@type", "searchChatMessages")
                .put("chat_id", chatIdOf(chatId)).put("query", "")
                .put("from_message_id", 0).put("offset", 0)
                .put("limit", UNREAD_REACTION_PAGE)
                .put(
                    "filter",
                    JSONObject().put("@type", "searchMessagesFilterUnreadReaction")
                )
        ) ?: return@execute
        val arr = answer.optJSONArray("messages") ?: return@execute
        var changed = false
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val msgId = m.optLong("id").toString()
            if (!Bridge.db.hasMessage(chatId, msgId)) continue
            val info = m.optJSONObject("interaction_info") ?: continue
            if (applyReactions(chatId, msgId, info, preview = false)) changed = true
        }
        if (changed) Bridge.notifyChat(chatId)
    }

    private fun onInteractionInfo(obj: JSONObject) {
        val chatId = idFor(obj.getLong("chat_id"))
        val msgId = obj.getLong("message_id").toString()
        if (applyReactions(chatId, msgId, obj.optJSONObject("interaction_info"), preview = true)) {
            Bridge.notifyChatRow(chatId, msgId)
        }
    }

    private fun applyReactions(
        chatId: String, msgId: String, info: JSONObject?, preview: Boolean,
    ): Boolean {
        val arr = info?.optJSONObject("reactions")?.optJSONArray("reactions")
        val wanted = LinkedHashMap<String, String>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val r = arr!!.getJSONObject(i)
            val emoji = r.getJSONObject("type").optString("emoji")
            if (emoji.isEmpty()) continue
            val count = r.optInt("total_count", 1)
            wanted["tg:r:$emoji"] = if (count > 1) "$emoji$count" else emoji
        }
        if (Bridge.db.reactionsOf(chatId, msgId).toMap() == wanted) return false
        Bridge.db.clearReactions(chatId, msgId)
        for ((sender, label) in wanted) Bridge.db.upsertReaction(chatId, msgId, sender, label)
        if (preview) Bridge.notifyChatsChanged()
        return true
    }

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
        downloader.execute {
            Bridge.db.setFileState(msg.chatId, msg.id, "", 1)
            startDownload(msg, fid)
        }
        return true
    }

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

    private fun issueDownload(msg: MessageRow, fid: Int): Boolean {
        val target = Pair(msg.chatId, msg.id)
        fileTargets.computeIfAbsent(fid) { ConcurrentHashMap.newKeySet() }.add(target)
        val res = request(downloadRequest(fid))
        if (res == null) {
            fileTargets[fid]?.remove(target)
            return false
        }
        var answer = res
        val claimed = completedAt(answer)
        if (claimed != null && !usable(claimed)) {
            request(JSONObject().put("@type", "deleteFile").put("file_id", fid))
            answer = request(downloadRequest(fid)) ?: run {
                fileTargets[fid]?.remove(target)
                return false
            }
            fileTargets.computeIfAbsent(fid) { ConcurrentHashMap.newKeySet() }.add(target)
        }
        val done = completedAt(answer)
        if (done != null) {
            if (!usable(done)) {
                fileTargets[fid]?.remove(target)
                return false
            }
            fileDone(msg.chatId, msg.id, done, 2)
        }
        return true
    }

    private fun completedAt(res: JSONObject): String? {
        val local = res.optJSONObject("local") ?: return null
        if (!local.optBoolean("is_downloading_completed")) return null
        return local.optString("path")
    }

    private fun usable(path: String) = path.isNotEmpty() && java.io.File(path).exists()

    private fun downloadRequest(fid: Int) = JSONObject().put("@type", "downloadFile")
        .put("file_id", fid).put("priority", 16).put("synchronous", false)

    private fun fileDone(chatId: String, msgId: String, path: String, status: Int) {
        Bridge.db.setFileState(chatId, msgId, path, status)
        Bridge.notifyChatRow(chatId, msgId)
        Bridge.onFileTransferDone(chatId, msgId, path, status)
    }

    private fun failDownload(msg: MessageRow) = fileDone(msg.chatId, msg.id, "", 3)

    private fun onFile(file: JSONObject) {
        val fid = file.optInt("id")
        val targets = fileTargets[fid] ?: return
        val local = file.optJSONObject("local") ?: return
        when {
            local.optBoolean("is_downloading_completed") -> {
                fileTargets.remove(fid)
                val path = local.optString("path")
                val ok = usable(path)
                for ((chatId, msgId) in targets) {
                    fileDone(chatId, msgId, if (ok) path else "", if (ok) 2 else 3)
                }
            }
            local.optBoolean("is_downloading_active") -> {
                val total = file.optLong("size")
                if (total > 0) {
                    val pct = (local.optLong("downloaded_size") * 100 / total).toInt().coerceIn(0, 99)
                    for ((chatId, msgId) in targets) Bridge.postDownloadProgress(chatId, msgId, pct)
                }
            }
            !local.optBoolean("can_be_downloaded", true) -> {
                fileTargets.remove(fid)
                for ((chatId, msgId) in targets) fileDone(chatId, msgId, "", 3)
            }
        }
    }

    private fun replyTo(quotedId: String): JSONObject? =
        quotedId.toLongOrNull()?.let {
            JSONObject().put("@type", "inputMessageReplyToMessage").put("message_id", it)
        }

    private fun sendMessage(chatId: String, content: JSONObject, quotedId: String = ""): String {
        if (!awaitReady(6_000)) return ""
        val req = JSONObject().put("@type", "sendMessage")
            .put("chat_id", chatIdOf(chatId))
            .put("input_message_content", content)
        replyTo(quotedId)?.let { req.put("reply_to", it) }
        val sent = request(req) ?: return ""
        val id = sent.optLong("id")
        return if (id != 0L) id.toString() else ""
    }

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
        for (h in mentionHits(plain, mentions)) {
            val uid = chatIdOf(h.id)
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

    private fun typeOf(contentType: String): String = when (contentType) {
        "messagePhoto" -> "image"
        "messageSticker" -> "sticker"
        "messageVideo", "messageAnimation" -> "video"
        "messageVideoNote" -> "videonote"
        "messageVoiceNote" -> "audio"
        "messageAudio", "messageDocument" -> "document"
        "messageLocation", "messageVenue" -> "location"
        "messageContact" -> "contact"
        else -> ""
    }

    private fun half(m: Markup.Mark, start: Int, end: Int): Boolean =
        (m.start < start && m.end > start && m.end < end) ||
            (m.start > start && m.start < end && m.end > end)

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

    fun cacheUser(userId: String): String? {
        val user = request(
            JSONObject().put("@type", "getUser").put("user_id", chatIdOf(userId))
        ) ?: return null
        onUser(user)
        return fullName(user)
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
    ): String = sendMessage(
        chatId,
        JSONObject().put("@type", "inputMessageText")
            .put("text", formattedText(text, mentions)),
        quotedId,
    )

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
    ): String =
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
    ): String =
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

    fun sendAudio(chatId: String, path: String, durationSeconds: Int, quotedId: String = "", waveform: ByteArray = ByteArray(0)): String =
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

    fun sendDocument(chatId: String, path: String, fileName: String = "", quotedId: String = ""): String {
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

    fun sendLocation(chatId: String, lat: Double, lng: Double): String = sendMessage(
        chatId,
        JSONObject().put("@type", "inputMessageLocation")
            .put(
                "location",
                JSONObject().put("@type", "location").put("latitude", lat).put("longitude", lng)
            ),
    )

    fun sendContact(chatId: String, name: String, numbers: List<String>): String {
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

    fun editMessageText(
        chatId: String, msgId: String, newText: String, mentions: List<Mention> = emptyList(),
    ): Boolean {
        val res = request(
            JSONObject().put("@type", "editMessageText")
                .put("chat_id", chatIdOf(chatId))
                .put("message_id", msgId.toLongOrNull() ?: return false)
                .put(
                    "input_message_content",
                    JSONObject().put("@type", "inputMessageText")
                        .put("text", formattedText(newText, mentions))
                )
        )
        return res != null
    }

    fun editMessageCaption(
        chatId: String, msgId: String, newText: String, mentions: List<Mention> = emptyList(),
    ): Boolean {
        val res = request(
            JSONObject().put("@type", "editMessageCaption")
                .put("chat_id", chatIdOf(chatId))
                .put("message_id", msgId.toLongOrNull() ?: return false)
                .put("caption", formattedText(newText, mentions))
        )
        return res != null
    }

    fun cancelQueuedSend(chatId: String, msgId: String): Boolean {
        val mid = msgId.toLongOrNull() ?: return false
        val msg = request(
            JSONObject().put("@type", "getMessage")
                .put("chat_id", chatIdOf(chatId)).put("message_id", mid)
        ) ?: return true
        if (msg.optJSONObject("sending_state")?.optString("@type")
            != "messageSendingStatePending"
        ) {
            return false
        }
        cancelledSends.add("$chatId/$msgId")
        deleteMessages(chatId, listOf(msgId), revoke = false)
        return true
    }

    private val cancelledSends: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun deleteMessages(chatId: String, msgIds: List<String>, revoke: Boolean) {
        val arr = JSONArray()
        for (m in msgIds) m.toLongOrNull()?.let { arr.put(it) }
        if (arr.length() == 0) return
        send(
            JSONObject().put("@type", "deleteMessages")
                .put("chat_id", chatIdOf(chatId)).put("message_ids", arr).put("revoke", revoke)
        )
    }

    fun deleteChatAsync(chatId: String) = pager.execute {
        if (!deleteChat(chatId)) Bridge.toastUi(R.string.delete_chat_failed)
    }

    private fun deleteChat(chatId: String): Boolean {
        val raw = chatIdOf(chatId)
        val chat = request(JSONObject().put("@type", "getChat").put("chat_id", raw))
        if (chat == null) {
            Log.w(TAG, "delete chat: getChat failed")
            return false
        }
        if (!chat.optBoolean("can_be_deleted_only_for_self") &&
            !chat.optBoolean("can_be_deleted_for_all_users")
        ) {
            request(JSONObject().put("@type", "leaveChat").put("chat_id", raw)) ?: return false
        }
        return request(
            JSONObject().put("@type", "deleteChatHistory").put("chat_id", raw)
                .put("remove_from_chat_list", true).put("revoke", false)
        ) != null
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

    fun createChatByPhone(number: String): String {
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

    fun openChat(chatId: String) = executor.execute {
        openCounts.merge(chatId, 1) { a, b -> a + b }
        if (ready) sendOpenChat(chatId)
    }

    fun closeChat(chatId: String) = executor.execute {
        val held = openCounts.containsKey(chatId)
        openCounts.compute(chatId) { _, n -> if (n == null || n <= 1) null else n - 1 }
        if (ready && held) {
            send(JSONObject().put("@type", "closeChat").put("chat_id", chatIdOf(chatId)))
        }
    }

    private fun sendOpenChat(chatId: String) =
        send(JSONObject().put("@type", "openChat").put("chat_id", chatIdOf(chatId)))

    fun markVoicePlayed(chatId: String, msgId: String) = executor.execute {
        val mid = msgId.toLongOrNull() ?: return@execute
        send(
            JSONObject().put("@type", "openMessageContent")
                .put("chat_id", chatIdOf(chatId)).put("message_id", mid)
        )
    }

    fun reportVisible(chatId: String, msgIds: List<String>) = executor.execute {
        if (msgIds.isEmpty()) return@execute
        val ids = JSONArray()
        for (id in msgIds) id.toLongOrNull()?.let { ids.put(it) }
        if (ids.length() == 0) return@execute
        send(
            JSONObject().put("@type", "viewMessages")
                .put("chat_id", chatIdOf(chatId))
                .put("message_ids", ids)
                .put("force_read", false)
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
                historyBusy.remove(chatId)
            }
            io.execute { syncPlayedState(chatId) }
        }
    }

    private fun fetchHistory(chatId: String, fromMsgId: Long, limit: Int): Int =
        fetchHistoryPage(chatId, fromMsgId, limit).first

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
        io.execute {
            if (Bridge.db.messageCount(chatId) < 60) {
                if (historyBusy.add(chatId)) {
                    pager.execute {
                        try {
                            if (fetchHistory(chatId, 0, 60) > 0) Bridge.notifyChat(chatId)
                        } finally {
                            historyBusy.remove(chatId)
                        }
                    }
                }
            } else if (fetchHistory(chatId, 0, 60) > 0) {
                Bridge.notifyChat(chatId)
            }
            syncPlayedState(chatId)
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

    fun chatPhotos(chatId: String, fromMsgId: String, newer: Boolean?, limit: Int = 49): List<MessageRow> {
        val id = fromMsgId.toLongOrNull() ?: return emptyList()
        val n = limit.coerceIn(1, 49)
        val offset = if (newer == false) 0 else -n
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
        for (i in 0 until arr.length()) parseMessageSafe(arr.getJSONObject(i))?.let { rows.add(it) }
        return preferStored(chatId, rows)
    }

    private fun preferStored(chatId: String, rows: List<MessageRow>): List<MessageRow> {
        val stored = Bridge.db.messagesByIds(chatId, rows.mapTo(HashSet()) { it.id }).associateBy { it.id }
        return rows.map { stored[it.id] ?: it }.sortedWith(MESSAGE_ORDER)
    }

    fun contextWindow(chatId: String, msgId: String, radius: Int = 25): List<MessageRow> {
        val id = msgId.toLongOrNull() ?: return emptyList()
        val r = radius.coerceIn(1, 49)
        val res = request(
            JSONObject().put("@type", "getChatHistory")
                .put("chat_id", chatIdOf(chatId))
                .put("from_message_id", id)
                .put("offset", -r)
                .put("limit", r * 2 + 1)
                .put("only_local", false),
            timeoutMs = 30_000,
        ) ?: return emptyList()
        val arr = res.optJSONArray("messages") ?: return emptyList()
        val rows = ArrayList<MessageRow>(arr.length())
        for (i in 0 until arr.length()) parseMessageSafe(arr.getJSONObject(i))?.let { rows.add(it) }
        return preferStored(chatId, rows)
    }

    fun historySlice(chatId: String, msgId: String, newer: Boolean, count: Int = 30): List<MessageRow> {
        val id = msgId.toLongOrNull() ?: return emptyList()
        val n = count.coerceIn(1, 49)
        val res = request(
            JSONObject().put("@type", "getChatHistory")
                .put("chat_id", chatIdOf(chatId))
                .put("from_message_id", id)
                .put("offset", if (newer) -n else 0)
                .put("limit", if (newer) n + 1 else n)
                .put("only_local", false),
            timeoutMs = 30_000,
        ) ?: return emptyList()
        val arr = res.optJSONArray("messages") ?: return emptyList()
        val rows = ArrayList<MessageRow>(arr.length())
        for (i in 0 until arr.length()) {
            val row = parseMessageSafe(arr.getJSONObject(i)) ?: continue
            val rowId = row.id.toLongOrNull() ?: continue
            if (if (newer) rowId > id else rowId < id) rows.add(row)
        }
        return preferStored(chatId, rows)
    }

    fun downloadNow(chatId: String, msgId: String): String {
        val mid = msgId.toLongOrNull() ?: return ""
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

    private fun syncPlayedState(chatId: String) {
        if (!awaitReady()) return
        var changed = refreshOwnListened(chatId)
        val stale = (Bridge.db.placeholderMessageIds(chatId, 30) +
            Bridge.db.emptyContactSenders(chatId, 10).map { it.first })
            .filter { repairAttempted.add("$chatId/$it") }
        val ids = LinkedHashSet(Bridge.db.unplayedAudioIds(chatId, 60)) + stale
        val wanted = JSONArray()
        for (id in ids) id.toLongOrNull()?.let { wanted.put(it) }
        val res = if (wanted.length() == 0) null else request(
            JSONObject().put("@type", "getMessages")
                .put("chat_id", chatIdOf(chatId)).put("message_ids", wanted)
        )
        val msgs = res?.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val msgId = m.optLong("id").toString()
            val content = m.optJSONObject("content") ?: continue
            if (content.optString("@type") == "messageVoiceNote" &&
                content.optBoolean("is_listened")
            ) {
                if (Bridge.db.setPlayed(chatId, msgId) > 0) changed = true
            }
            if (msgId in stale) {
                Bridge.db.deleteMessage(chatId, msgId)
                storeMessageSafe(m)
                changed = true
            }
        }
        if (changed) Bridge.notifyChat(chatId)
    }

    private val listenedSwept: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun refreshOwnListened(chatId: String): Boolean {
        val pending = Bridge.db.unplayedAudioIds(chatId, 200, fromMeOnly = true).toHashSet()
        if (pending.isEmpty() || chatId in listenedSwept) return false
        val remaining = pending.toMutableSet()
        val oldest = pending.mapNotNull { it.toLongOrNull() }.minOrNull() ?: return false
        var changed = false
        var from = 0L
        var round = 0
        var reachedEnd = false
        while (remaining.isNotEmpty() && round < LISTENED_PAGES) {
            round++
            val res = request(
                JSONObject().put("@type", "searchChatMessages")
                    .put("chat_id", chatIdOf(chatId))
                    .put("query", "")
                    .put(
                        "sender_id",
                        JSONObject().put("@type", "messageSenderUser").put("user_id", myId)
                    )
                    .put("from_message_id", from).put("offset", 0).put("limit", 100)
            ) ?: break
            val msgs = res.optJSONArray("messages") ?: break
            if (msgs.length() == 0) { reachedEnd = true; break }
            var last = 0L
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                val id = m.optLong("id")
                if (id > 0) last = id
                val msgId = id.toString()
                if (!remaining.remove(msgId)) continue
                if (m.optJSONObject("content")?.optBoolean("is_listened") != true) continue
                if (Bridge.db.setPlayed(chatId, msgId) > 0) changed = true
            }
            if (last == 0L || last <= oldest) { reachedEnd = true; break }
            from = res.optLong("next_from_message_id").takeIf { it != 0L } ?: last
        }
        if (remaining.isEmpty() || reachedEnd) listenedSwept.add(chatId)
        return changed
    }

    @Volatile private var syncAllChat: String? = null
    @Volatile private var syncAllRounds = 0

    fun syncAllProgress(chatId: String): Int {
        if (syncAllChat != chatId) return -1
        return Bridge.asymptoticProgress(syncAllRounds)
    }

    fun syncAllHistory(chatId: String): Boolean {
        if (syncAllChat != null && syncAllChat != chatId) return false
        if (syncAllChat == chatId) return true
        syncAllChat = chatId
        syncAllRounds = 0
        historyExhausted.remove(chatId)
        Bridge.notifySyncAll(chatId, 0)
        pager.execute { syncAllStep(chatId, 0L) }
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
            try {
                val before = Bridge.db.messageCount(chatId)
                var lastAnchor = -1L
                while (true) {
                    val fromId = Bridge.db.oldestMessage(chatId)?.id?.toLongOrNull() ?: 0L
                    if (fromId == lastAnchor) { complete = false; break }
                    lastAnchor = fromId
                    val count = fetchHistory(chatId, fromId, 100)
                    if (count < 0) { complete = false; break }
                    if (count == 0) { historyExhausted.add(chatId); break }
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
                val (count, oldest) = fetchHistoryPage(chatId, anchor, 100)
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
        if (!big) avatarPaths[key]?.let { if (usable(it)) return it else avatarPaths.remove(key) }
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
        if (ok) avatarPaths.remove(selfId())
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
