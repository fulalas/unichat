package org.unichat.app

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatRow(
    val id: String,
    val name: String,
    val lastText: String,
    val lastTime: Long,
    val unread: Int,
    val isGroup: Boolean,
    val lastFromMe: Boolean = false,
    val lastRead: Boolean = false,
    val lastFailed: Boolean = false,
    val lastPending: Boolean = false,
    val muted: Boolean = false,
    val transientState: String = "",
    val online: Boolean = false,
)

data class MessageRow(
    val id: String,
    val chatId: String,
    val senderId: String,
    val text: String,
    val fromMe: Boolean,
    val timeSent: Long,
    val isRead: Boolean,
    val msgType: String = "",
    val fileId: String = "",
    val filePath: String = "",
    val fileStatus: Int = 0,
    val edited: Boolean = false,
    val quotedId: String = "",
    val quotedText: String = "",
    val quotedType: String = "",
    val senderName: String = "",
    val played: Boolean = false,
    val forwarded: Boolean = false,
    val sendFailed: Boolean = false,
    val sendPending: Boolean = false,
    val reactions: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val captionLocked: Boolean = false,
)

val MESSAGE_ORDER: Comparator<MessageRow> =
    compareBy({ it.timeSent }, { it.id.toLongOrNull() ?: 0L })

fun MessageRow.coordinates(): String =
    if (msgType == "location") "%.6f,%.6f".format(java.util.Locale.US, latitude, longitude) else ""

const val MAP_LINK_PREFIX = "https://maps.google.com/?q="

data class SenderInfo(val senderId: String, val fromMe: Boolean, val senderName: String)

data class QuotedInfo(
    val sender: SenderInfo, val text: String, val msgType: String,
)

fun previewLabel(
    ctx: Context, msgType: String, text: String, emoji: Boolean, detail: String = "",
): String {
    fun labeled(icon: String, labelRes: Int, body: String = text) = when {
        body.isEmpty() -> if (emoji) "$icon ${ctx.getString(labelRes)}" else ctx.getString(labelRes)
        emoji -> "$icon $body"
        else -> body
    }
    return when (msgType) {
        "image" -> labeled("📷", R.string.photo_label)
        "sticker" -> labeled("🩹", R.string.sticker_label)
        in VIDEO_TYPES -> labeled("🎥", R.string.video_label)
        "location" -> {
            val base = labeled("📍", R.string.location_label)
            if (detail.isEmpty()) base else "$base: $detail"
        }
        "audio" -> {
            val label = ctx.getString(R.string.voice_message)
            val base = if (emoji) "🎤 $label" else label
            if (detail.isEmpty()) base else "$base ($detail)"
        }
        "document" -> labeled("📎", R.string.document_label)
        "contact" -> labeled("👤", R.string.contact_label,
            text.lineSequence().firstOrNull().orEmpty())
        in LABEL_ONLY_TYPES -> {
            val (icon, labelRes) = LABEL_ONLY_TYPES.getValue(msgType)
            val label = ctx.getString(labelRes)
            if (emoji) "$icon $label" else label
        }
        else -> text
    }
}

val PICTURE_TYPES = setOf("image", "sticker")

val VIDEO_TYPES = setOf("video", "videonote")

val CAPTION_TYPES = setOf("image", "video")

val LABEL_ONLY_TYPES: Map<String, Pair<String, Int>> = mapOf(
    "viewonce" to ("🔒" to R.string.view_once_label),
    "contact" to ("👤" to R.string.contact_label),
    "poll" to ("📊" to R.string.poll_label),
    "pollvote" to ("🗳" to R.string.poll_vote_label),
    "event" to ("📅" to R.string.event_label),
    "groupinvite" to ("👥" to R.string.group_invite_label),
    "livelocation" to ("📍" to R.string.live_location_label),
    "call" to ("📞" to R.string.call_label),
)

fun reactionPreview(
    ctx: Context, reactions: String?, lastFromMe: Boolean,
    chatName: String, msgType: String, msgText: String,
): String? {
    if (reactions.isNullOrEmpty()) return null
    val emoji = reactions.split(',').firstOrNull { it.isNotEmpty() } ?: return null
    val quoted = previewLabel(ctx, msgType, msgText, emoji = false)
        .replace('\n', ' ').take(40)
    val who = if (lastFromMe) chatName else ctx.getString(R.string.you)
    return if (who.isEmpty()) ctx.getString(R.string.reacted_to_noname, emoji, quoted)
    else ctx.getString(R.string.reacted_to, who, emoji, quoted)
}

class Db(context: Context) : SQLiteOpenHelper(context, "unichat.db", null, 35) {

    private val ctx: Context = context.applicationContext

    init {
        setWriteAheadLoggingEnabled(true)
    }

    companion object {
        private const val CREATE_DELETED_CHATS =
            "CREATE TABLE IF NOT EXISTS deleted_chats(" +
                "id TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)"

        private const val CREATE_REACTIONS =
            "CREATE TABLE reactions(" +
                "chat_id TEXT NOT NULL, msg_id TEXT NOT NULL, sender_id TEXT NOT NULL," +
                "emoji TEXT NOT NULL," +
                "PRIMARY KEY(chat_id, msg_id, sender_id))"

        private const val CREATE_UNREAD_INDEX =
            "CREATE INDEX idx_msg_unread ON messages(chat_id) WHERE from_me=0 AND is_read=0"

        private const val CREATE_UNREAD_OUT_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_unread_out ON messages(chat_id) " +
                "WHERE from_me=1 AND is_read=0"

        private const val CREATE_TIME_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_time ON messages(chat_id, time_sent)"

        private const val CREATE_SCROLL =
            "CREATE TABLE IF NOT EXISTS scroll (chat_id TEXT PRIMARY KEY, " +
                "msg_id TEXT NOT NULL, offset INTEGER NOT NULL)"
        private const val CREATE_UNPLAYED_AUDIO_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_unplayed_audio ON messages(chat_id, time_sent) " +
                "WHERE msg_type='audio' AND played=0"
        private const val CREATE_PLACEHOLDER_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_placeholder ON messages(chat_id, time_sent) " +
                "WHERE msg_type='' AND file_id=''"
        private const val CREATE_EMPTY_CONTACT_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_empty_contact ON messages(chat_id, time_sent) " +
                "WHERE msg_type='contact' AND (text='' OR file_id='')"

        private const val CREATE_ID_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_id ON messages(id)"

        private const val NEGATIVE_TTL_SECONDS = 7L * 24 * 60 * 60

        private const val CREATE_LINK_PREVIEWS =
            "CREATE TABLE IF NOT EXISTS link_previews(" +
                "url TEXT PRIMARY KEY, site TEXT NOT NULL DEFAULT ''," +
                "title TEXT NOT NULL DEFAULT '', description TEXT NOT NULL DEFAULT ''," +
                "image_path TEXT NOT NULL DEFAULT ''," +
                "status INTEGER NOT NULL DEFAULT 0, fetched_at INTEGER NOT NULL DEFAULT 0)"
    }

    private inline fun SQLiteDatabase.transact(body: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            body()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE contacts(" +
                "id TEXT PRIMARY KEY, name TEXT NOT NULL, phone TEXT NOT NULL DEFAULT ''," +
                "is_self INTEGER NOT NULL DEFAULT 0, is_group INTEGER NOT NULL DEFAULT 0," +
                "is_saved INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE chats(" +
                "id TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT ''," +
                "archived INTEGER NOT NULL DEFAULT 0, last_time INTEGER NOT NULL DEFAULT 0," +
                "muted INTEGER NOT NULL DEFAULT 0," +
                "react_text TEXT NOT NULL DEFAULT '', react_time INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE messages(" +
                "chat_id TEXT NOT NULL, id TEXT NOT NULL, sender_id TEXT NOT NULL DEFAULT ''," +
                "text TEXT NOT NULL DEFAULT '', from_me INTEGER NOT NULL DEFAULT 0," +
                "time_sent INTEGER NOT NULL DEFAULT 0, is_read INTEGER NOT NULL DEFAULT 0," +
                "msg_type TEXT NOT NULL DEFAULT '', file_id TEXT NOT NULL DEFAULT ''," +
                "file_path TEXT NOT NULL DEFAULT '', file_status INTEGER NOT NULL DEFAULT 0," +
                "edited INTEGER NOT NULL DEFAULT 0," +
                "quoted_id TEXT NOT NULL DEFAULT '', quoted_text TEXT NOT NULL DEFAULT ''," +
                "quoted_type TEXT NOT NULL DEFAULT ''," +
                "sender_name TEXT NOT NULL DEFAULT ''," +
                "played INTEGER NOT NULL DEFAULT 0," +
                "send_failed INTEGER NOT NULL DEFAULT 0," +
                "send_pending INTEGER NOT NULL DEFAULT 0," +
                "time_pinned INTEGER NOT NULL DEFAULT 0," +
                "forwarded INTEGER NOT NULL DEFAULT 0," +
                "latitude REAL NOT NULL DEFAULT 0, longitude REAL NOT NULL DEFAULT 0," +
                "caption_locked INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(chat_id, id))"
        )
        db.execSQL(CREATE_SCROLL)
        db.execSQL(CREATE_TIME_INDEX)
        db.execSQL(CREATE_UNREAD_INDEX)
        db.execSQL(CREATE_UNREAD_OUT_INDEX)
        db.execSQL(CREATE_ID_INDEX)
        db.execSQL(CREATE_REACTIONS)
        db.execSQL(CREATE_DELETED_CHATS)
        db.execSQL(CREATE_UNPLAYED_AUDIO_INDEX)
        db.execSQL(CREATE_PLACEHOLDER_INDEX)
        db.execSQL(CREATE_EMPTY_CONTACT_INDEX)
        db.execSQL(CREATE_LINK_PREVIEWS)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS link_previews")
        db.execSQL("DROP TABLE IF EXISTS deleted_chats")
        db.execSQL("DROP TABLE IF EXISTS reactions")
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS chats")
        db.execSQL("DROP TABLE IF EXISTS contacts")
        onCreate(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN msg_type TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN file_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN file_path TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN file_status INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN quoted_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN quoted_text TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sender_name TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE messages ADD COLUMN played INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 7) {
            db.execSQL("UPDATE messages SET played=1 WHERE from_me=1 AND msg_type='audio' AND is_read=1")
        }
        if (oldVersion < 8) {
            db.execSQL("DELETE FROM messages WHERE chat_id='status@broadcast'")
            db.execSQL("DELETE FROM chats WHERE id='status@broadcast'")
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE contacts ADD COLUMN is_saved INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 11) {
            db.execSQL(CREATE_REACTIONS)
        }
        if (oldVersion < 12) {
            db.execSQL(
                "UPDATE contacts SET is_saved=1 " +
                    "WHERE is_group=0 AND is_self=0 AND name!='' AND NOT EXISTS" +
                    "(SELECT 1 FROM contacts WHERE is_saved=1 AND is_group=0)"
            )
        }
        if (oldVersion < 13) {
            db.execSQL("ALTER TABLE chats ADD COLUMN muted INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 14) {
            db.execSQL(CREATE_UNREAD_INDEX)
        }
        if (oldVersion < 15) {
            db.execSQL(CREATE_TIME_INDEX)
        }
        if (oldVersion < 16) {
            db.execSQL("ALTER TABLE messages ADD COLUMN quoted_type TEXT NOT NULL DEFAULT ''")
            db.execSQL(CREATE_ID_INDEX)
        }
        if (oldVersion < 17) {
            db.execSQL("ALTER TABLE chats ADD COLUMN react_text TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE chats ADD COLUMN react_time INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 18) {
            db.execSQL(CREATE_DELETED_CHATS)
        }
        if (oldVersion < 19) {
            db.execSQL(CREATE_UNPLAYED_AUDIO_INDEX)
            db.execSQL(CREATE_PLACEHOLDER_INDEX)
        }
        if (oldVersion < 20) {
            db.execSQL(
                "UPDATE messages SET file_path='', file_status=0 " +
                    "WHERE chat_id LIKE 'tg:%' AND file_path!=''"
            )
        }
        if (oldVersion < 21) {
            db.execSQL("UPDATE messages SET msg_type='sticker' WHERE msg_type='image' AND file_id LIKE 'stk:%'")
        }
        if (oldVersion < 22) {
            db.execSQL("ALTER TABLE messages ADD COLUMN latitude REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN longitude REAL NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE messages SET " +
                    "latitude=CAST(substr(file_id, 1, instr(file_id, ',')-1) AS REAL)," +
                    "longitude=CAST(substr(file_id, instr(file_id, ',')+1) AS REAL)," +
                    "file_id='' " +
                    "WHERE msg_type='location' AND instr(file_id, ',')>0"
            )
        }
        if (oldVersion < 24) {
            db.execSQL("DROP INDEX IF EXISTS idx_msg_empty_contact")
            db.execSQL(CREATE_EMPTY_CONTACT_INDEX)
        }
        if (oldVersion < 25) {
            db.execSQL(CREATE_LINK_PREVIEWS)
        }
        if (oldVersion < 26) {
            db.execSQL("DELETE FROM link_previews WHERE status=2")
        }
        if (oldVersion < 27) {
            db.execSQL("DELETE FROM link_previews")
        }
        if (oldVersion < 28) {
            db.execSQL("DELETE FROM link_previews")
        }
        if (oldVersion < 29) {
            db.execSQL("ALTER TABLE messages ADD COLUMN send_failed INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 30) {
            db.execSQL(CREATE_SCROLL)
        }
        if (oldVersion < 31) {
            db.execSQL("ALTER TABLE messages ADD COLUMN send_pending INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 32) {
            db.execSQL("ALTER TABLE messages ADD COLUMN time_pinned INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 33) {
            db.execSQL("ALTER TABLE messages ADD COLUMN album_incomplete INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 34) {
            db.execSQL("ALTER TABLE messages RENAME COLUMN album_incomplete TO caption_locked")
            db.execSQL(
                "UPDATE messages SET caption_locked=1 WHERE msg_type='video' AND from_me=1 " +
                    "AND chat_id NOT LIKE 'tg:%' AND chat_id NOT LIKE 'sg:%'"
            )
            db.execSQL(
                "UPDATE messages SET caption_locked=1 WHERE chat_id LIKE 'sg:%' AND (chat_id, id) IN (" +
                    "SELECT chat_id, substr(id, 1, instr(id, '-') - 1) FROM messages " +
                    "WHERE chat_id LIKE 'sg:%' AND id LIKE '%-%' " +
                    "GROUP BY chat_id, substr(id, 1, instr(id, '-') - 1) " +
                    "HAVING count(*) != max(CAST(substr(id, instr(id, '-') + 1) AS INTEGER)))"
            )
        }
        if (oldVersion < 35) {
            db.execSQL(CREATE_UNREAD_OUT_INDEX)
        }
    }

    private fun <T> queryList(sql: String, args: Array<String>?, map: (Cursor) -> T): List<T> {
        val rows = ArrayList<T>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) rows.add(map(c)) }
        return rows
    }

    private fun <T> queryFirst(sql: String, args: Array<String>?, map: (Cursor) -> T): T? {
        readableDatabase.rawQuery(sql, args).use { c ->
            return if (c.moveToFirst()) map(c) else null
        }
    }

    fun upsertContact(
        id: String, name: String, phone: String, isSelf: Boolean, isGroup: Boolean, isSaved: Boolean,
    ) {
        writableDatabase.execSQL(
            "INSERT INTO contacts(id, name, phone, is_self, is_group, is_saved) VALUES(?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET name=excluded.name, phone=excluded.phone," +
                "is_self=excluded.is_self, is_group=excluded.is_group, is_saved=excluded.is_saved",
            arrayOf(id, name, phone, if (isSelf) 1 else 0, if (isGroup) 1 else 0, if (isSaved) 1 else 0)
        )
    }

    private val deletedChats: MutableMap<String, Long> by lazy {
        val m = java.util.concurrent.ConcurrentHashMap<String, Long>()
        queryList("SELECT id, deleted_at FROM deleted_chats", null) {
            it.getString(0) to it.getLong(1)
        }.forEach { m[it.first] = it.second }
        m
    }

    private fun suppressed(chatId: String, atTime: Long): Boolean {
        val deletedAt = deletedChats[chatId] ?: return false
        if (atTime > deletedAt) {
            deletedChats.remove(chatId)
            writableDatabase.execSQL("DELETE FROM deleted_chats WHERE id=?", arrayOf(chatId))
            return false
        }
        return true
    }

    fun upsertChat(id: String, name: String, archived: Boolean, lastTime: Long) {
        if (suppressed(id, lastTime)) return
        writableDatabase.execSQL(
            "INSERT INTO chats(id, name, archived, last_time) VALUES(?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET " +
                "name=CASE WHEN excluded.name!='' THEN excluded.name ELSE name END," +
                "archived=excluded.archived, last_time=max(last_time, excluded.last_time)",
            arrayOf(id, name, if (archived) 1 else 0, lastTime)
        )
    }

    fun mergeChat(fromId: String, toId: String): Boolean {
        if (fromId == toId) return false
        val db = writableDatabase
        if (!chatExists(db, fromId)) return false
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO chats(id) VALUES(?)", arrayOf(toId))
            db.execSQL("UPDATE OR IGNORE messages SET chat_id=? WHERE chat_id=?", arrayOf(toId, fromId))
            db.execSQL("DELETE FROM messages WHERE chat_id=?", arrayOf(fromId))
            db.execSQL("UPDATE OR IGNORE reactions SET chat_id=? WHERE chat_id=?", arrayOf(toId, fromId))
            db.execSQL("DELETE FROM reactions WHERE chat_id=?", arrayOf(fromId))
            db.execSQL(
                "UPDATE chats SET " +
                    "name=CASE WHEN name='' THEN (SELECT name FROM chats WHERE id=?) ELSE name END," +
                    "last_time=max(last_time,(SELECT last_time FROM chats WHERE id=?))," +
                    "muted=max(muted,(SELECT muted FROM chats WHERE id=?))," +
                    "archived=max(archived,(SELECT archived FROM chats WHERE id=?)) WHERE id=?",
                arrayOf(fromId, fromId, fromId, fromId, toId)
            )
            db.execSQL("DELETE FROM chats WHERE id=?", arrayOf(fromId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    private fun chatExists(db: SQLiteDatabase, id: String): Boolean {
        db.rawQuery("SELECT 1 FROM chats WHERE id=? LIMIT 1", arrayOf(id)).use { c ->
            return c.moveToFirst()
        }
    }

    fun lidChats(): List<String> =
        queryList("SELECT id FROM chats WHERE id LIKE '%@lid'", null) { it.getString(0) }

    fun bumpChat(id: String, lastTime: Long) {
        if (suppressed(id, lastTime)) return
        writableDatabase.execSQL(
            "INSERT INTO chats(id, last_time) VALUES(?,?) " +
                "ON CONFLICT(id) DO UPDATE SET last_time=max(last_time, excluded.last_time)",
            arrayOf(id, lastTime)
        )
    }

    fun upsertMessage(m: MessageRow) {
        if (suppressed(m.chatId, m.timeSent)) return
        writableDatabase.execSQL(
            "INSERT INTO messages(chat_id, id, sender_id, text, from_me, time_sent, is_read, msg_type, file_id, edited, quoted_id, quoted_text, quoted_type, sender_name, forwarded, latitude, longitude) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(chat_id, id) DO UPDATE SET " +
                "text=CASE WHEN excluded.text!='' THEN excluded.text ELSE text END," +
                "is_read=max(is_read, excluded.is_read), edited=max(edited, excluded.edited)," +
                "sender_name=CASE WHEN excluded.sender_name!='' THEN excluded.sender_name ELSE sender_name END," +
                "quoted_type=CASE WHEN excluded.quoted_type!='' THEN excluded.quoted_type ELSE quoted_type END," +
                "time_sent=CASE WHEN excluded.time_sent>0 AND time_pinned=0 " +
                "THEN excluded.time_sent ELSE time_sent END," +
                "file_id=CASE WHEN excluded.file_id!='' THEN excluded.file_id ELSE file_id END," +
                "latitude=CASE WHEN excluded.latitude!=0 THEN excluded.latitude ELSE latitude END," +
                "longitude=CASE WHEN excluded.longitude!=0 THEN excluded.longitude ELSE longitude END",
            arrayOf(
                m.chatId, m.id, m.senderId, m.text, if (m.fromMe) 1 else 0, m.timeSent,
                if (m.isRead) 1 else 0, m.msgType, m.fileId, if (m.edited) 1 else 0,
                m.quotedId, m.quotedText, m.quotedType, m.senderName, if (m.forwarded) 1 else 0,
                m.latitude, m.longitude
            )
        )
    }

    fun quotedMessage(chatId: String, msgId: String): QuotedInfo? = queryFirst(
        "SELECT sender_id, from_me, sender_name, text, msg_type FROM messages " +
            "WHERE chat_id=? AND id=?",
        arrayOf(chatId, msgId)
    ) {
        QuotedInfo(
            SenderInfo(it.getString(0), it.getInt(1) != 0, it.getString(2)),
            it.getString(3), it.getString(4),
        )
    }

    fun deleteMessage(chatId: String, msgId: String) = writableDatabase.transact {
        execSQL("DELETE FROM messages WHERE chat_id=? AND id=?", arrayOf(chatId, msgId))
        execSQL("DELETE FROM reactions WHERE chat_id=? AND msg_id=?", arrayOf(chatId, msgId))
        val newest = queryFirst(
            "SELECT max(time_sent) FROM messages WHERE chat_id=?", arrayOf(chatId)
        ) { it.getLong(0) } ?: 0
        if (newest > 0) execSQL(
            "UPDATE chats SET last_time=? WHERE id=? AND last_time>?",
            arrayOf(newest, chatId, newest)
        )
        val parent = msgId.substringBeforeLast('-', "")
        if (parent.toLongOrNull() != null && msgId.substringAfterLast('-').toIntOrNull() != null) {
            execSQL(
                "UPDATE messages SET caption_locked=1 WHERE chat_id=? AND id=?",
                arrayOf(chatId, parent)
            )
        }
    }

    fun recentMessages(chatId: String, limit: Int): List<MessageRow> = queryList(
        "SELECT id, sender_id, from_me, time_sent FROM messages WHERE chat_id=? " +
            "AND send_pending=0 AND send_failed=0 ORDER BY time_sent DESC, rowid DESC LIMIT $limit",
        arrayOf(chatId)
    ) {
        MessageRow(
            id = it.getString(0), chatId = chatId, senderId = it.getString(1), text = "",
            fromMe = it.getInt(2) != 0, timeSent = it.getLong(3), isRead = false
        )
    }

    fun chatMediaPaths(chatId: String): List<String> = queryList(
        "SELECT file_path FROM messages WHERE chat_id=? AND file_path!=''",
        arrayOf(chatId)
    ) { it.getString(0) }

    fun deleteChat(chatId: String) {
        val now = System.currentTimeMillis() / 1000
        val previous = deletedChats.put(chatId, now)
        try {
            writableDatabase.transact {
                execSQL("DELETE FROM messages WHERE chat_id=?", arrayOf(chatId))
                execSQL("DELETE FROM reactions WHERE chat_id=?", arrayOf(chatId))
                execSQL("DELETE FROM chats WHERE id=?", arrayOf(chatId))
                execSQL(
                    "INSERT INTO deleted_chats(id, deleted_at) VALUES(?,?) " +
                        "ON CONFLICT(id) DO UPDATE SET deleted_at=excluded.deleted_at",
                    arrayOf(chatId, now)
                )
            }
        } catch (e: Exception) {
            if (previous == null) deletedChats.remove(chatId)
            else deletedChats[chatId] = previous
            throw e
        }
    }

    fun setMuted(chatId: String, muted: Boolean): Boolean {
        writableDatabase.compileStatement("UPDATE chats SET muted=? WHERE id=?").use { stmt ->
            stmt.bindLong(1, if (muted) 1L else 0L)
            stmt.bindString(2, chatId)
            return stmt.executeUpdateDelete() > 0
        }
    }

    fun setArchived(chatId: String, archived: Boolean) {
        writableDatabase.execSQL(
            "UPDATE chats SET archived=? WHERE id=?",
            arrayOf(if (archived) 1 else 0, chatId)
        )
    }

    fun isSelfContact(id: String): Boolean = queryFirst(
        "SELECT is_self FROM contacts WHERE id=?", arrayOf(id)
    ) { it.getInt(0) != 0 } ?: false

    fun isMuted(chatId: String): Boolean = queryFirst(
        "SELECT muted FROM chats WHERE id=?", arrayOf(chatId)
    ) { it.getInt(0) != 0 } ?: false

    fun mutedFlags(): Map<String, Boolean> =
        queryList("SELECT id, muted FROM chats", null) { it.getString(0) to (it.getInt(1) != 0) }.toMap()

    fun upsertReaction(chatId: String, msgId: String, senderId: String, emoji: String) {
        writableDatabase.execSQL(
            "INSERT INTO reactions(chat_id, msg_id, sender_id, emoji) VALUES(?,?,?,?) " +
                "ON CONFLICT(chat_id, msg_id, sender_id) DO UPDATE SET emoji=excluded.emoji",
            arrayOf(chatId, msgId, senderId, emoji)
        )
    }

    fun reactionsOf(chatId: String, msgId: String): List<Pair<String, String>> = queryList(
        "SELECT sender_id, emoji FROM reactions WHERE chat_id=? AND msg_id=? " +
            "ORDER BY emoji, sender_id",
        arrayOf(chatId, msgId)
    ) { it.getString(0) to it.getString(1) }

    fun deleteReaction(chatId: String, msgId: String, senderId: String) {
        writableDatabase.execSQL(
            "DELETE FROM reactions WHERE chat_id=? AND msg_id=? AND sender_id=?",
            arrayOf(chatId, msgId, senderId)
        )
    }

    fun fileState(chatId: String, msgId: String): Pair<String, Int> = queryFirst(
        "SELECT file_path, file_status FROM messages WHERE chat_id=? AND id=?",
        arrayOf(chatId, msgId)
    ) { Pair(it.getString(0), it.getInt(1)) } ?: Pair("", 0)

    fun unplayedAudioIds(chatId: String, limit: Int, fromMeOnly: Boolean = false): List<String> = queryList(
        "SELECT id FROM messages WHERE chat_id=? AND msg_type='audio' AND played=0 " +
            (if (fromMeOnly) "AND from_me=1 " else "") +
            "ORDER BY time_sent DESC LIMIT ?",
        arrayOf(chatId, limit.toString())
    ) { it.getString(0) }

    fun setScroll(chatId: String, msgId: String, offset: Int) {
        writableDatabase.execSQL(
            "INSERT INTO scroll(chat_id, msg_id, offset) VALUES(?,?,?) " +
                "ON CONFLICT(chat_id) DO UPDATE SET msg_id=excluded.msg_id, offset=excluded.offset",
            arrayOf(chatId, msgId, offset)
        )
    }

    fun clearScroll(chatId: String) {
        writableDatabase.execSQL("DELETE FROM scroll WHERE chat_id=?", arrayOf(chatId))
    }

    fun scroll(chatId: String): Pair<String, Int>? = queryFirst(
        "SELECT msg_id, offset FROM scroll WHERE chat_id=?",
        arrayOf(chatId)
    ) { it.getString(0) to it.getInt(1) }

    fun placeholderMessageIds(chatId: String, limit: Int): List<String> = queryList(
        "SELECT id FROM messages WHERE chat_id=? AND msg_type='' AND file_id='' " +
            "AND text LIKE '[%]' ORDER BY time_sent DESC LIMIT ?",
        arrayOf(chatId, limit.toString())
    ) { it.getString(0) }

    fun emptyContactSenders(chatId: String, limit: Int): List<Pair<String, String>> = queryList(
        "SELECT id, sender_id FROM messages " +
            "WHERE chat_id=? AND msg_type='contact' AND text='' " +
            "ORDER BY time_sent DESC LIMIT ?",
        arrayOf(chatId, limit.toString())
    ) { Pair(it.getString(0), it.getString(1)) }

    fun setPlayed(chatId: String, msgId: String): Int =
        writableDatabase.compileStatement(
            "UPDATE messages SET played=1, send_failed=0, send_pending=0 WHERE chat_id=? AND id=?"
        ).use {
            it.bindString(1, chatId)
            it.bindString(2, msgId)
            it.executeUpdateDelete()
        }

    fun audioMessage(chatId: String, msgId: String): MessageRow? = queryFirst(
        "SELECT id, sender_id, from_me, msg_type, played FROM messages " +
            "WHERE chat_id=? AND id=?",
        arrayOf(chatId, msgId)
    ) {
        MessageRow(
            id = it.getString(0), chatId = chatId, senderId = it.getString(1),
            text = "", fromMe = it.getInt(2) != 0, timeSent = 0, isRead = false,
            msgType = it.getString(3), played = it.getInt(4) != 0
        )
    }

    fun setFileState(chatId: String, msgId: String, filePath: String, status: Int): Int {
        writableDatabase.compileStatement(
            "UPDATE messages SET file_path=?, file_status=? WHERE chat_id=? AND id=?"
        ).use { stmt ->
            stmt.bindString(1, filePath)
            stmt.bindLong(2, status.toLong())
            stmt.bindString(3, chatId)
            stmt.bindString(4, msgId)
            return stmt.executeUpdateDelete()
        }
    }

    fun markReadUpTo(chatId: String, upToId: Long, incoming: Boolean) {
        writableDatabase.execSQL(
            "UPDATE messages SET is_read=1 WHERE chat_id=? AND from_me=" +
                (if (incoming) "0" else "1") +
                " AND is_read=0 AND CAST(id AS INTEGER)<=?",
            arrayOf(chatId, upToId.toString())
        )
    }

    fun clearReactions(chatId: String, msgId: String) {
        writableDatabase.execSQL(
            "DELETE FROM reactions WHERE chat_id=? AND msg_id=?", arrayOf(chatId, msgId)
        )
    }

    fun clearStaleDownloads() {
        writableDatabase.execSQL("UPDATE messages SET file_status=0 WHERE file_status=1")
    }

    private fun clearProtocolData(predicate: (String) -> String) = writableDatabase.transact {
        execSQL("DELETE FROM messages WHERE ${predicate("chat_id")}")
        execSQL("DELETE FROM reactions WHERE ${predicate("chat_id")}")
        execSQL("DELETE FROM chats WHERE ${predicate("id")}")
        execSQL("DELETE FROM contacts WHERE ${predicate("id")}")
        execSQL("DELETE FROM deleted_chats WHERE ${predicate("id")}")
        execSQL("DELETE FROM scroll WHERE ${predicate("chat_id")}")
    }

    fun clearSignalData() = clearProtocolData { "$it LIKE 'sg:%'" }

    fun clearWaData() = clearProtocolData { "$it NOT LIKE 'tg:%' AND $it NOT LIKE 'sg:%'" }

    fun renameChat(id: String, name: String) {
        if (name.isEmpty()) return
        writableDatabase.execSQL(
            "INSERT INTO chats(id, name) VALUES(?,?) " +
                "ON CONFLICT(id) DO UPDATE SET name=excluded.name",
            arrayOf(id, name)
        )
    }

    fun clearTgData() = clearProtocolData { "$it LIKE 'tg:%'" }

    fun setFileId(chatId: String, msgId: String, fileId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET file_id=? WHERE chat_id=? AND id=?",
            arrayOf(fileId, chatId, msgId)
        )
    }

    fun messageChat(msgId: String, prefix: String = "", fromMe: Boolean? = null): String? = queryFirst(
        "SELECT chat_id FROM messages WHERE id=? AND chat_id NOT LIKE 'tg:%' AND chat_id LIKE ?" +
            (fromMe?.let { " AND from_me=${if (it) 1 else 0}" } ?: "") + " LIMIT 1",
        arrayOf(msgId, "$prefix%")
    ) { it.getString(0) }

    fun markMessageRead(chatId: String, msgId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET is_read=1, send_failed=0, send_pending=0 WHERE chat_id=? AND id=?",
            arrayOf(chatId, msgId)
        )
    }

    fun markChatRead(chatId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET is_read=1 WHERE chat_id=? AND from_me=0 AND is_read=0",
            arrayOf(chatId)
        )
    }

    fun markChatReadUpTo(chatId: String, msgId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET is_read=1 WHERE chat_id=? AND from_me=0 AND is_read=0 " +
                "AND time_sent<=(SELECT time_sent FROM messages WHERE chat_id=? AND id=?)",
            arrayOf(chatId, chatId, msgId)
        )
    }

    private fun oneMessage(chatId: String, where: String, order: String): MessageRow? = queryFirst(
        "SELECT id, sender_id, text, from_me, time_sent, is_read FROM messages " +
            "WHERE chat_id=? AND $where ORDER BY $order LIMIT 1",
        arrayOf(chatId)
    ) {
        MessageRow(
            id = it.getString(0), chatId = chatId, senderId = it.getString(1),
            text = it.getString(2), fromMe = it.getInt(3) != 0,
            timeSent = it.getLong(4), isRead = it.getInt(5) != 0
        )
    }

    fun chatIdByPhone(phone: String, prefix: String): String? = queryFirst(
        "SELECT c.id FROM contacts c JOIN chats ch ON ch.id=c.id " +
            "WHERE c.phone=? AND c.id LIKE ? LIMIT 1",
        arrayOf(phone, "$prefix%")
    ) { it.getString(0) }

    fun setMsgType(chatId: String, msgId: String, msgType: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET msg_type=? WHERE chat_id=? AND id=? AND msg_type!=?",
            arrayOf(msgType, chatId, msgId, msgType)
        )
    }

    fun albumFileIds(chatId: String, msgId: String): List<String> = queryList(
        "SELECT id, file_id FROM messages WHERE chat_id=? AND id LIKE ? AND file_id!=''",
        arrayOf(chatId, "$msgId-%")
    ) { it.getString(0) to it.getString(1) }
        .filter { (id, _) -> id.substringAfterLast('-').toIntOrNull() != null }
        .sortedBy { (id, _) -> id.substringAfterLast('-').toInt() }
        .map { (_, fileId) -> fileId }

    fun latestUnread(chatId: String): MessageRow? =
        oneMessage(chatId, "from_me=0 AND is_read=0", "time_sent DESC, rowid DESC")

    fun firstUnread(chatId: String): MessageRow? =
        oneMessage(chatId, "from_me=0 AND is_read=0 AND time_sent>0", "time_sent ASC, rowid ASC")

    fun setSendFailed(chatId: String, msgId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET send_failed=1, send_pending=0, time_pinned=1 " +
                "WHERE chat_id=? AND id=?",
            arrayOf(chatId, msgId)
        )
    }

    fun stageOutgoing(m: MessageRow) {
        deletedChats.remove(m.chatId)
        writableDatabase.transact {
            execSQL("DELETE FROM deleted_chats WHERE id=?", arrayOf(m.chatId))
            execSQL("INSERT OR IGNORE INTO chats(id) VALUES(?)", arrayOf(m.chatId))
            execSQL(
                "REPLACE INTO messages(chat_id, id, sender_id, text, from_me, time_sent, is_read, " +
                    "msg_type, file_id, file_path, file_status, quoted_id, quoted_text, quoted_type, " +
                    "latitude, longitude, send_pending) VALUES(?,?,?,?,1,?,0,?,'',?,?,?,?,?,?,?,1)",
                arrayOf(
                    m.chatId, m.id, m.senderId, m.text, m.timeSent, m.msgType, m.filePath,
                    m.fileStatus, m.quotedId, m.quotedText, m.quotedType, m.latitude, m.longitude
                )
            )
        }
    }

    fun isSendPending(chatId: String, msgId: String): Boolean = queryFirst(
        "SELECT send_pending FROM messages WHERE chat_id=? AND id=?", arrayOf(chatId, msgId)
    ) { it.getInt(0) != 0 } ?: false

    fun setSendPending(chatId: String, msgId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET send_pending=1 WHERE chat_id=? AND id=?",
            arrayOf(chatId, msgId)
        )
    }

    fun storedTime(chatId: String, msgId: String): Long? = queryFirst(
        "SELECT time_sent FROM messages WHERE chat_id=? AND id=?", arrayOf(chatId, msgId)
    ) { it.getLong(0) }

    fun clearSendMarks(chatId: String, msgId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET send_pending=0, send_failed=0 WHERE chat_id=? AND id=?",
            arrayOf(chatId, msgId)
        )
    }

    fun renameMessage(chatId: String, oldId: String, newId: String) = writableDatabase.transact {
        val taken = queryFirst(
            "SELECT 1 FROM messages WHERE chat_id=? AND id=? LIMIT 1", arrayOf(chatId, newId)
        ) { true } ?: false
        if (taken) {
            execSQL("DELETE FROM messages WHERE chat_id=? AND id=?", arrayOf(chatId, oldId))
            execSQL("DELETE FROM reactions WHERE chat_id=? AND msg_id=?", arrayOf(chatId, oldId))
            return@transact
        }
        execSQL(
            "UPDATE messages SET id=? WHERE chat_id=? AND id=?", arrayOf(newId, chatId, oldId)
        )
        execSQL(
            "UPDATE reactions SET msg_id=? WHERE chat_id=? AND msg_id=?",
            arrayOf(newId, chatId, oldId)
        )
    }

    fun failStalePending() {
        writableDatabase.execSQL(
            "UPDATE messages SET send_pending=0, send_failed=1, time_pinned=1 " +
                "WHERE send_pending=1 " +
                "AND NOT (chat_id LIKE 'tg:%' AND id NOT LIKE 'local:%')"
        )
    }

    fun chats(): List<ChatRow> = namePreviewMentions(queryList(
        "SELECT c.id," +
            "COALESCE(NULLIF(c.name,''), NULLIF(ct.name,''), c.id) AS display_name," +
            "COALESCE(lm.msg_type,'') AS last_type," +
            "COALESCE(lm.text,'') AS last_text," +
            "(SELECT GROUP_CONCAT(emoji) FROM reactions r " +
            "WHERE r.chat_id=c.id AND r.msg_id=lm.id) AS last_reactions," +
            "c.last_time," +
            "(SELECT COUNT(*) FROM messages WHERE chat_id=c.id AND from_me=0 AND is_read=0) AS unread," +
            "CASE WHEN c.id LIKE '%@g.us' OR c.id LIKE 'tg:-%' " +
                "OR (c.id LIKE 'sg:%' AND LENGTH(" +
                "REPLACE(REPLACE(REPLACE(c.id,'sg:',''),'PNI:',''),'pni:','')) <> 36) " +
                "THEN 1 ELSE 0 END AS is_group," +
            "COALESCE(lm.from_me,0) AS last_from_me," +
            "COALESCE(lm.is_read,0) AS last_read," +
            "COALESCE(lm.send_failed,0) AS last_failed," +
            "COALESCE(lm.send_pending,0) AS last_pending," +
            "c.muted " +
            "FROM chats c LEFT JOIN contacts ct ON ct.id=c.id " +
            "LEFT JOIN messages lm ON lm.rowid=(" +
            "SELECT rowid FROM messages WHERE chat_id=c.id " +
            "ORDER BY time_sent DESC, rowid DESC LIMIT 1) " +
            "WHERE c.archived=0 AND c.id!='status@broadcast' " +
            "ORDER BY c.last_time DESC",
        null
    ) {
        ChatRow(
            id = it.getString(0), name = it.getString(1),
            lastText = reactionPreview(
                ctx, it.getString(4), it.getInt(8) != 0,
                it.getString(1), it.getString(2), it.getString(3),
            ) ?: previewLabel(
                ctx, it.getString(2), it.getString(3), emoji = true,
                detail = if (it.getString(2) == "audio") it.getString(3) else "",
            ),
            lastTime = it.getLong(5), unread = it.getInt(6), isGroup = it.getInt(7) != 0,
            lastFromMe = it.getInt(8) != 0 && it.getString(4).isNullOrEmpty(),
            lastRead = it.getInt(9) != 0,
            lastFailed = it.getInt(10) != 0,
            lastPending = it.getInt(11) != 0,
            muted = it.getInt(12) != 0
        )
    })

    private fun namePreviewMentions(rows: List<ChatRow>): List<ChatRow> {
        if (rows.none { hasMention(it.lastText) }) return rows
        val names = contactNames()
        return rows.map {
            if (hasMention(it.lastText)) it.copy(lastText = resolveMentions(it.lastText, names))
            else it
        }
    }

    private fun messageColumns(src: String) =
        "SELECT id, sender_id, text, from_me, time_sent, is_read, msg_type, file_id, file_path, " +
            "file_status, edited, quoted_id, quoted_text, sender_name, played, forwarded, quoted_type," +
            "latitude, longitude, send_failed, send_pending, caption_locked," +
            "(SELECT GROUP_CONCAT(emoji) FROM reactions r " +
            "WHERE r.chat_id=$src.chat_id AND r.msg_id=$src.id) AS reactions "

    private fun fullMessage(chatId: String, it: Cursor) = MessageRow(
        id = it.getString(0), chatId = chatId, senderId = it.getString(1),
        text = it.getString(2), fromMe = it.getInt(3) != 0,
        timeSent = it.getLong(4), isRead = it.getInt(5) != 0,
        msgType = it.getString(6), fileId = it.getString(7),
        filePath = it.getString(8), fileStatus = it.getInt(9),
        edited = it.getInt(10) != 0,
        quotedId = it.getString(11), quotedText = it.getString(12),
        senderName = it.getString(13), played = it.getInt(14) != 0,
        forwarded = it.getInt(15) != 0, quotedType = it.getString(16),
        latitude = it.getDouble(17), longitude = it.getDouble(18),
        sendFailed = it.getInt(19) != 0,
        sendPending = it.getInt(20) != 0,
        captionLocked = it.getInt(21) != 0,
        reactions = it.getString(22) ?: ""
    )

    fun messagesByIds(chatId: String, ids: Collection<String>): List<MessageRow> {
        if (ids.isEmpty()) return emptyList()
        val holes = ids.joinToString(",") { "?" }
        return queryList(
            messageColumns("messages") + "FROM messages WHERE chat_id=? AND id IN ($holes)",
            (listOf(chatId) + ids).toTypedArray()
        ) { fullMessage(chatId, it) }
    }

    fun messages(chatId: String, limit: Int = 500): List<MessageRow> = queryList(
        messageColumns("m") + "FROM " +
            "(SELECT rowid AS rid, * FROM messages WHERE chat_id=? ORDER BY time_sent DESC, rowid DESC LIMIT ?) m " +
            "ORDER BY time_sent ASC, rid ASC",
        arrayOf(chatId, limit.toString())
    ) { fullMessage(chatId, it) }

    fun chatImages(chatId: String): List<MessageRow> = queryList(
        "SELECT id, sender_id, from_me, time_sent, file_id, file_path, file_status, msg_type " +
            "FROM messages WHERE chat_id=? AND msg_type IN ('image','sticker') " +
            "ORDER BY time_sent ASC, rowid ASC",
        arrayOf(chatId)
    ) {
        MessageRow(
            id = it.getString(0), chatId = chatId, senderId = it.getString(1),
            text = "", fromMe = it.getInt(2) != 0, timeSent = it.getLong(3),
            isRead = true, msgType = it.getString(7), fileId = it.getString(4),
            filePath = it.getString(5), fileStatus = it.getInt(6),
        )
    }

    fun searchContacts(query: String, limit: Int = 60): List<ChatRow> {
        val folded = Search.fold(query)
        if (folded.isEmpty()) return emptyList()
        val out = ArrayList<ChatRow>(limit)
        val bestAt = HashMap<String, Int>()
        val bestRank = HashMap<String, Int>()
        readableDatabase.rawQuery(
            "SELECT c.id, c.name, c.phone, c.is_group, " +
                "EXISTS(SELECT 1 FROM chats ch WHERE ch.id=c.id) FROM contacts c " +
                "WHERE c.is_self=0 AND (c.is_saved=1 OR c.is_group=1) " +
                "ORDER BY c.name COLLATE NOCASE",
            null
        ).use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getString(0)
                val name = c.getString(1)
                val phone = c.getString(2)
                val isGroup = c.getInt(3) != 0
                if (!Search.contains(name, folded) && !Search.contains(phone, folded) &&
                    !Search.contains(id, folded)
                ) {
                    continue
                }
                val row = ChatRow(
                    id = id, name = name,
                    lastText = if (phone.isNotEmpty()) "+$phone" else "",
                    lastTime = 0, unread = 0, isGroup = isGroup
                )
                if (isGroup || phone.isEmpty()) {
                    out.add(row)
                    continue
                }
                val key = Accounts.ofChat(id).proto + " " + phone
                val rank = (if (c.getInt(4) != 0) 2 else 0) + (if (Signal.isPniId(id)) 0 else 1)
                val at = bestAt[key]
                if (at == null) {
                    bestAt[key] = out.size
                    bestRank[key] = rank
                    out.add(row)
                } else if (rank > (bestRank[key] ?: 0)) {
                    bestRank[key] = rank
                    out[at] = row
                }
            }
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return out
    }


    fun nextAudioMessage(chatId: String, afterMsgId: String): MessageRow? {
        val after = queryFirst(
            "SELECT time_sent, rowid FROM messages WHERE chat_id=? AND id=?",
            arrayOf(chatId, afterMsgId)
        ) { Pair(it.getLong(0), it.getLong(1)) } ?: return null
        return queryFirst(
            "SELECT id, sender_id, file_id, file_path, file_status, from_me FROM messages " +
                "WHERE chat_id=? AND msg_type='audio' AND (time_sent, rowid) > (?, ?) " +
                "ORDER BY time_sent ASC, rowid ASC LIMIT 1",
            arrayOf(chatId, after.first.toString(), after.second.toString())
        ) {
            MessageRow(
                id = it.getString(0), chatId = chatId, senderId = it.getString(1),
                text = "", fromMe = it.getInt(5) != 0, timeSent = 0, isRead = false,
                msgType = "audio", fileId = it.getString(2),
                filePath = it.getString(3), fileStatus = it.getInt(4)
            )
        }
    }

    fun oldestMessage(chatId: String): MessageRow? =
        oneMessage(chatId, "time_sent>0", "time_sent ASC")

    fun newestMessage(chatId: String): MessageRow? =
        oneMessage(chatId, "time_sent>0", "time_sent DESC, rowid DESC")

    fun messageCount(chatId: String): Int = queryFirst(
        "SELECT COUNT(*) FROM messages WHERE chat_id=?", arrayOf(chatId)
    ) { it.getInt(0) } ?: 0

    fun hasMessage(chatId: String, msgId: String): Boolean = queryFirst(
        "SELECT 1 FROM messages WHERE chat_id=? AND id=? LIMIT 1", arrayOf(chatId, msgId)
    ) { true } ?: false

    fun messageDepth(chatId: String, msgId: String): Int {
        val at = queryFirst(
            "SELECT time_sent, rowid FROM messages WHERE chat_id=? AND id=? LIMIT 1",
            arrayOf(chatId, msgId)
        ) { Pair(it.getLong(0), it.getLong(1)) } ?: return 0
        return queryFirst(
            "SELECT COUNT(*) FROM messages WHERE chat_id=? AND " +
                "(time_sent > ? OR (time_sent = ? AND rowid >= ?))",
            arrayOf(chatId, at.first.toString(), at.first.toString(), at.second.toString())
        ) { it.getInt(0) } ?: 0
    }

    fun displayName(chatId: String): String {
        queryFirst(
            "SELECT COALESCE(NULLIF(c.name,''), NULLIF(ct.name,'')) FROM chats c " +
                "LEFT JOIN contacts ct ON ct.id=c.id WHERE c.id=?",
            arrayOf(chatId)
        ) { if (it.isNull(0)) null else it.getString(0) }?.let { return it }
        contactName(chatId)?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (chatId.contains("@")) phoneLabel(chatId) else chatId
    }

    fun contactNames(): Map<String, String> =
        queryList("SELECT id, name FROM contacts", null) { it.getString(0) to it.getString(1) }.toMap()

    fun contactName(id: String): String? = queryFirst(
        "SELECT name FROM contacts WHERE id=?", arrayOf(id)
    ) { if (it.isNull(0)) null else it.getString(0) }

    fun linkPreview(url: String): LinkPreview.Row? = queryFirst(
        "SELECT site, title, description, image_path, status, fetched_at " +
            "FROM link_previews WHERE url=?",
        arrayOf(url)
    ) {
        val hasPreview = it.getInt(4) == 1
        val age = System.currentTimeMillis() / 1000 - it.getLong(5)
        if (!hasPreview && age > NEGATIVE_TTL_SECONDS) return@queryFirst null
        LinkPreview.Row(
            url = url, site = it.getString(0), title = it.getString(1),
            description = it.getString(2), imagePath = it.getString(3),
            hasPreview = hasPreview,
        )
    }

    fun putLinkPreview(row: LinkPreview.Row) {
        writableDatabase.execSQL(
            "INSERT INTO link_previews(url, site, title, description, image_path, status, fetched_at) " +
                "VALUES(?,?,?,?,?,?,?) ON CONFLICT(url) DO UPDATE SET " +
                "site=excluded.site, title=excluded.title, description=excluded.description," +
                "image_path=excluded.image_path, status=excluded.status, fetched_at=excluded.fetched_at",
            arrayOf(
                row.url, row.site, row.title, row.description, row.imagePath,
                if (row.hasPreview) 1 else 2, System.currentTimeMillis() / 1000
            )
        )
    }

    fun forgetLinkPreviewImages(paths: Collection<String>) {
        if (paths.isEmpty()) return
        writableDatabase.transact {
            for (path in paths) {
                execSQL("UPDATE link_previews SET image_path='' WHERE image_path=?", arrayOf(path))
            }
        }
    }

    fun contactPhone(id: String): String = queryFirst(
        "SELECT phone FROM contacts WHERE id=?", arrayOf(id)
    ) { if (it.isNull(0)) "" else it.getString(0) } ?: ""
}
