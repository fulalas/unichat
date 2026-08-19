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
    val fileStatus: Int = 0, // 0 none, 1 downloading, 2 downloaded, 3 failed
    val edited: Boolean = false,
    val quotedId: String = "",
    val quotedText: String = "",
    val quotedType: String = "",
    val senderName: String = "",
    val played: Boolean = false,
    val forwarded: Boolean = false,
    val reactions: String = "", // comma-separated emojis, one per reacting user
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

fun MessageRow.coordinates(): String =
    if (msgType == "location") "%.6f,%.6f".format(java.util.Locale.US, latitude, longitude) else ""

data class SenderInfo(val senderId: String, val fromMe: Boolean, val senderName: String)

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
        "video" -> labeled("🎥", R.string.video_label)
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
        // a contact card's body is "name\nphone..."; previews show just the name
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

class Db(context: Context) : SQLiteOpenHelper(context, "unichat.db", null, 24) {

    private val ctx: Context = context.applicationContext

    init {
        setWriteAheadLoggingEnabled(true)
    }

    companion object {
        // Chats the user deleted locally. Without this the next mirror pass put
        // them straight back: both protocols re-announce their whole chat list
        // on connect, so a deleted chat returned on every app start.
        private const val CREATE_DELETED_CHATS =
            "CREATE TABLE IF NOT EXISTS deleted_chats(" +
                "id TEXT PRIMARY KEY, deleted_at INTEGER NOT NULL)"

        private const val CREATE_REACTIONS =
            "CREATE TABLE reactions(" +
                "chat_id TEXT NOT NULL, msg_id TEXT NOT NULL, sender_id TEXT NOT NULL," +
                "emoji TEXT NOT NULL," +
                "PRIMARY KEY(chat_id, msg_id, sender_id))"

        // partial index so the chat list's per-chat unread COUNT(*) is
        // O(unread) instead of scanning every message row of the chat
        private const val CREATE_UNREAD_INDEX =
            "CREATE INDEX idx_msg_unread ON messages(chat_id) WHERE from_me=0 AND is_read=0"

        // Every message window query and the chat list's newest-message lookup
        // order by (chat_id, time_sent). IF NOT EXISTS so onCreate and the
        // upgrade path can share one statement — this index shipped in onCreate
        // only, so databases created before it existed never got it.
        private const val CREATE_TIME_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_time ON messages(chat_id, time_sent)"

        // chatOfMessage looks a message up by id, which the (chat_id, id) primary key
        // cannot serve — it full-scanned the largest table, on the media
        // download path (every download whose first setFileState matched 0 rows).
        // The two repair sweeps (unplayed voice notes, "[Type]" placeholders) run
        // on every chat open and every history page, and their filters are not
        // covered by idx_msg_time — so the common case, finding nothing, walked
        // the chat's whole message list twice.
        private const val CREATE_UNPLAYED_AUDIO_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_unplayed_audio ON messages(chat_id, time_sent) " +
                "WHERE msg_type='audio' AND played=0"
        private const val CREATE_PLACEHOLDER_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_placeholder ON messages(chat_id, time_sent) " +
                "WHERE msg_type='' AND file_id=''"
        // contact cards stored before the app kept their body (name/numbers);
        // the repair sweep re-fetches them, and runs on every chat open. The
        // predicate is wider than the sweep's (which uses text='' only, and a
        // query condition may imply the index predicate) so v23/v24 databases
        // need no rebuild.
        private const val CREATE_EMPTY_CONTACT_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_empty_contact ON messages(chat_id, time_sent) " +
                "WHERE msg_type='contact' AND (text='' OR file_id='')"

        private const val CREATE_ID_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_id ON messages(id)"
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
                "forwarded INTEGER NOT NULL DEFAULT 0," +
                "latitude REAL NOT NULL DEFAULT 0, longitude REAL NOT NULL DEFAULT 0," +
                "PRIMARY KEY(chat_id, id))"
        )
        db.execSQL(CREATE_TIME_INDEX)
        db.execSQL(CREATE_UNREAD_INDEX)
        db.execSQL(CREATE_ID_INDEX)
        db.execSQL(CREATE_REACTIONS)
        db.execSQL(CREATE_DELETED_CHATS)
        db.execSQL(CREATE_UNPLAYED_AUDIO_INDEX)
        db.execSQL(CREATE_PLACEHOLDER_INDEX)
        db.execSQL(CREATE_EMPTY_CONTACT_INDEX)
    }

    /**
     * Everything here is a local cache of what the phone holds, so a schema
     * regression (sideloading an older build over a newer database) is recovered
     * by rebuilding from scratch and re-syncing. The base class's default throws
     * SQLiteDowngradeFailedException instead, which crashed the app on every
     * launch with no way out.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
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
            // is_saved arrived in v9 defaulting to 0 and is only backfilled by
            // the next contact sync; until then search would find nothing. If
            // no row is flagged yet (fresh migration), optimistically mark all
            // named people saved — the first sync then corrects the flags.
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
            // idx_msg_time was only ever created in onCreate, so every database
            // upgraded from an older build ran the hottest queries unindexed
            db.execSQL(CREATE_TIME_INDEX)
        }
        if (oldVersion < 16) {
            db.execSQL("ALTER TABLE messages ADD COLUMN quoted_type TEXT NOT NULL DEFAULT ''")
            db.execSQL(CREATE_ID_INDEX)
        }
        if (oldVersion < 18) {
            db.execSQL(CREATE_DELETED_CHATS)
        }
        if (oldVersion < 24) {
            // v23 shipped this index on text='' only; replace with the wider
            // predicate so the file_id backfill sweep is covered too
            db.execSQL("DROP INDEX IF EXISTS idx_msg_empty_contact")
            db.execSQL(CREATE_EMPTY_CONTACT_INDEX)
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
        if (oldVersion < 21) {
            db.execSQL("UPDATE messages SET msg_type='sticker' WHERE msg_type='image' AND file_id LIKE 'stk:%'")
        }
        if (oldVersion < 20) {
            // Telegram media could be pointing at the wrong file entirely: a
            // stale file id was trusted to fetch with, and TDLib reuses those
            // ids across sessions. Which rows are wrong is not knowable, so
            // every association is dropped and re-derived from the message.
            db.execSQL(
                "UPDATE messages SET file_path='', file_status=0 " +
                    "WHERE chat_id LIKE 'tg:%' AND file_path!=''"
            )
        }
        if (oldVersion < 19) {
            db.execSQL(CREATE_UNPLAYED_AUDIO_INDEX)
            db.execSQL(CREATE_PLACEHOLDER_INDEX)
        }
        if (oldVersion < 17) {
            db.execSQL("ALTER TABLE chats ADD COLUMN react_text TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE chats ADD COLUMN react_time INTEGER NOT NULL DEFAULT 0")
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

    // chatId -> when it was deleted. Cached because upsertMessage consults it on
    // every stored message, on the protocol threads.
    private val deletedChats: MutableMap<String, Long> by lazy {
        val m = java.util.concurrent.ConcurrentHashMap<String, Long>()
        queryList("SELECT id, deleted_at FROM deleted_chats", null) {
            it.getString(0) to it.getLong(1)
        }.forEach { m[it.first] = it.second }
        m
    }

    /**
     * Whether a chat the user deleted must stay deleted for an event stamped
     * [atTime]. Anything the deletion already covered is dropped; something
     * genuinely newer brings the chat back (and retires the tombstone), which is
     * the behaviour a local delete has always had for new incoming messages.
     */
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

    /**
     * Fold a duplicate chat [fromId] into [toId], moving its messages and
     * reactions. Heals a 1:1 chat that was keyed by a contact's LID before the
     * LID→phone mapping was known (see Bridge.reconcileLidChats). Idempotent: a no-op
     * when [fromId] has no local chat. Returns true if anything was merged.
     */
    fun mergeChat(fromId: String, toId: String): Boolean {
        if (fromId == toId) return false
        val db = writableDatabase
        if (!chatExists(db, fromId)) return false
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO chats(id) VALUES(?)", arrayOf(toId))
            // move messages/reactions; OR IGNORE skips any row that already
            // exists under the target's key, then the leftovers are dropped
            db.execSQL("UPDATE OR IGNORE messages SET chat_id=? WHERE chat_id=?", arrayOf(toId, fromId))
            db.execSQL("DELETE FROM messages WHERE chat_id=?", arrayOf(fromId))
            db.execSQL("UPDATE OR IGNORE reactions SET chat_id=? WHERE chat_id=?", arrayOf(toId, fromId))
            db.execSQL("DELETE FROM reactions WHERE chat_id=?", arrayOf(fromId))
            db.execSQL(
                "UPDATE chats SET " +
                    "last_time=max(last_time,(SELECT last_time FROM chats WHERE id=?))," +
                    "muted=max(muted,(SELECT muted FROM chats WHERE id=?))," +
                    "archived=max(archived,(SELECT archived FROM chats WHERE id=?)) WHERE id=?",
                arrayOf(fromId, fromId, fromId, toId)
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
                // Guarded like every other field below. It used to be an
                // unconditional text=excluded.text, so a history-sync
                // re-delivery of a media message — which carries no caption —
                // wiped the caption already stored for it. A genuine edit always
                // carries its new body, so it still overwrites.
                "text=CASE WHEN excluded.text!='' THEN excluded.text ELSE text END," +
                "is_read=max(is_read, excluded.is_read), edited=max(edited, excluded.edited)," +
                // (no played= clause: played is not one of the inserted columns,
                // so excluded.played was always the 0 default and max(played,0)
                // reduced to played — a no-op that read like a real guard.
                // Leaving it out preserves the column, which was the intent.)
                "sender_name=CASE WHEN excluded.sender_name!='' THEN excluded.sender_name ELSE sender_name END," +
                "quoted_type=CASE WHEN excluded.quoted_type!='' THEN excluded.quoted_type ELSE quoted_type END," +
                // the post-send reconciliation upsert corrects the optimistic
                // echo's device-clock time with the server timestamp and adds
                // the upload's download reference; edits re-deliver with
                // time_sent 0 / empty file_id, which must not clobber
                "time_sent=CASE WHEN excluded.time_sent>0 THEN excluded.time_sent ELSE time_sent END," +
                "file_id=CASE WHEN excluded.file_id!='' THEN excluded.file_id ELSE file_id END," +
                // same guard as file_id: an edit or history re-delivery of a
                // location carries no coordinates and must not zero the stored ones
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

    fun messageSender(chatId: String, msgId: String): SenderInfo? = queryFirst(
        "SELECT sender_id, from_me, sender_name FROM messages WHERE chat_id=? AND id=?",
        arrayOf(chatId, msgId)
    ) { SenderInfo(it.getString(0), it.getInt(1) != 0, it.getString(2)) }

    // One transaction: as two independent statements, a process kill or an
    // SQLite error between them left the message gone but its reactions behind —
    // orphan rows no later query can reach or clean up.
    fun deleteMessage(chatId: String, msgId: String) = writableDatabase.transact {
        execSQL("DELETE FROM messages WHERE chat_id=? AND id=?", arrayOf(chatId, msgId))
        execSQL("DELETE FROM reactions WHERE chat_id=? AND msg_id=?", arrayOf(chatId, msgId))
    }

    fun chatMediaPaths(chatId: String): List<String> = queryList(
        "SELECT file_path FROM messages WHERE chat_id=? AND file_path!=''",
        arrayOf(chatId)
    ) { it.getString(0) }

    fun deleteChat(chatId: String) {
        val now = System.currentTimeMillis() / 1000
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
        deletedChats[chatId] = now
    }

    // Sets a chat's local mute flag (suppresses its notifications). Update-only:
    // a mute event for a chat we have no row for is ignored rather than
    // materialising a blank phantom chat; the flag is picked up by reconcile
    // once the chat exists.
    // Returns whether a chat row actually took the flag, so a caller that also
    // pushes the change to the server can tell that nothing was stored locally
    // (and must not then record the chat as having an unconfirmed local mute).
    fun setMuted(chatId: String, muted: Boolean): Boolean {
        writableDatabase.compileStatement("UPDATE chats SET muted=? WHERE id=?").use { stmt ->
            stmt.bindLong(1, if (muted) 1L else 0L)
            stmt.bindString(2, chatId)
            return stmt.executeUpdateDelete() > 0
        }
    }

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

    fun unplayedAudioIds(chatId: String, limit: Int): List<String> = queryList(
        "SELECT id FROM messages WHERE chat_id=? AND msg_type='audio' AND played=0 " +
            "ORDER BY time_sent DESC LIMIT ?",
        arrayOf(chatId, limit.toString())
    ) { it.getString(0) }

    fun placeholderMessageIds(chatId: String, limit: Int): List<String> = queryList(
        "SELECT id FROM messages WHERE chat_id=? AND msg_type='' AND file_id='' " +
            "AND text LIKE '[%]' ORDER BY time_sent DESC LIMIT ?",
        arrayOf(chatId, limit.toString())
    ) { it.getString(0) }

    // Contact cards from before the body was kept, so the repair can refill
    // them. Deliberately text='' only: file_id (the messageable id) stays
    // empty forever for cards that legitimately have none — a wider predicate
    // would re-ask the phone for those on every run without end.
    fun emptyContactSenders(chatId: String, limit: Int): List<Pair<String, String>> = queryList(
        "SELECT id, sender_id FROM messages " +
            "WHERE chat_id=? AND msg_type='contact' AND text='' " +
            "ORDER BY time_sent DESC LIMIT ?",
        arrayOf(chatId, limit.toString())
    ) { Pair(it.getString(0), it.getString(1)) }

    fun setPlayed(chatId: String, msgId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET played=1 WHERE chat_id=? AND id=?", arrayOf(chatId, msgId)
        )
    }

    // One message's playback-relevant fields. Keyed by id, not by file path:
    // Telegram serves a single file for every copy of the same voice note, so a
    // path can belong to several rows.
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

    // Returns the number of rows updated (0 when the message no longer exists,
    // e.g. its chat was deleted while a download was still in flight).
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
            "UPDATE messages SET is_read=1 WHERE chat_id=? AND from_me=? AND is_read=0 " +
                "AND CAST(id AS INTEGER)<=?",
            arrayOf(chatId, if (incoming) "0" else "1", upToId.toString())
        )
    }

    fun clearReactions(chatId: String, msgId: String) {
        writableDatabase.execSQL(
            "DELETE FROM reactions WHERE chat_id=? AND msg_id=?", arrayOf(chatId, msgId)
        )
    }

    /**
     * Clears "downloading" flags left by a previous run. No transfer survives a
     * process death, so a stored status of 1 is always stale — and it made the
     * bubble skip its own retry, leaving that media blank for good.
     */
    fun clearStaleDownloads() {
        writableDatabase.execSQL("UPDATE messages SET file_status=0 WHERE file_status=1")
    }

    private fun clearProtocolData(match: String) = writableDatabase.transact {
        execSQL("DELETE FROM messages WHERE chat_id $match 'tg:%'")
        execSQL("DELETE FROM reactions WHERE chat_id $match 'tg:%'")
        execSQL("DELETE FROM chats WHERE id $match 'tg:%'")
        execSQL("DELETE FROM contacts WHERE id $match 'tg:%'")
        execSQL("DELETE FROM deleted_chats WHERE id $match 'tg:%'")
    }

    fun clearWaData() = clearProtocolData("NOT LIKE")

    /** Renames a chat without touching its other columns. upsertChat writes
     *  `archived` too, so using it for a rename un-archived the chat. */
    fun renameChat(id: String, name: String) {
        if (name.isEmpty()) return
        writableDatabase.execSQL(
            "INSERT INTO chats(id, name) VALUES(?,?) " +
                "ON CONFLICT(id) DO UPDATE SET name=excluded.name",
            arrayOf(id, name)
        )
    }

    fun clearTgData() = clearProtocolData("LIKE")

    /**
     * Replaces a message's media reference. Telegram file ids are only valid
     * inside the TDLib session that issued them, so a stored one goes stale
     * across restarts and has to be re-resolved and written back.
     */
    fun setFileId(chatId: String, msgId: String, fileId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET file_id=? WHERE chat_id=? AND id=?",
            arrayOf(fileId, chatId, msgId)
        )
    }

    fun markMessageRead(chatId: String, msgId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET is_read=1 WHERE chat_id=? AND id=?", arrayOf(chatId, msgId)
        )
    }

    fun markChatRead(chatId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET is_read=1 WHERE chat_id=? AND from_me=0 AND is_read=0",
            arrayOf(chatId)
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

    fun latestUnread(chatId: String): MessageRow? =
        oneMessage(chatId, "from_me=0 AND is_read=0", "time_sent DESC")

    fun chats(): List<ChatRow> = namePreviewMentions(queryList(
        // The newest message is resolved ONCE, by rowid, and joined — four
        // separate correlated subqueries used to re-find the same row for its
        // type, text, from_me and is_read (five lookups per chat row on a query
        // that runs on every chat-list event). They also ordered by time_sent
        // alone: whatsapp timestamps are second-resolution, so within a burst
        // each subquery could pick a DIFFERENT message and the preview, tick and
        // from-me flag could disagree with each other and with the chat screen,
        // which orders by (time_sent, rowid). This shares that tiebreaker.
        "SELECT c.id," +
            "COALESCE(NULLIF(c.name,''), NULLIF(ct.name,''), c.id) AS display_name," +
            "COALESCE(lm.msg_type,'') AS last_type," +
            "COALESCE(lm.text,'') AS last_text," +
            "(SELECT GROUP_CONCAT(emoji) FROM reactions r " +
            "WHERE r.chat_id=c.id AND r.msg_id=lm.id) AS last_reactions," +
            "c.last_time," +
            "(SELECT COUNT(*) FROM messages WHERE chat_id=c.id AND from_me=0 AND is_read=0) AS unread," +
            // Telegram groups/channels have negative raw ids; private chats are
            // positive. (Must match isGroupId in Jid.kt.)
            "CASE WHEN c.id LIKE '%@g.us' OR c.id LIKE 'tg:-%' THEN 1 ELSE 0 END AS is_group," +
            "COALESCE(lm.from_me,0) AS last_from_me," +
            "COALESCE(lm.is_read,0) AS last_read," +
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
            muted = it.getInt(10) != 0
        )
    })

    // @mentions travel as the mentioned user's raw id, so a preview showed a
    // bare 15-digit LID where the bubble now shows a name. The contacts scan is
    // paid only when a preview actually carries a mention.
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
            "latitude, longitude," +
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
        reactions = it.getString(19) ?: ""
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
            // whatsapp timestamps are second-resolution, so time_sent alone
            // can't order messages sent within the same second. rowid is the
            // insertion counter (== arrival order for live messages; the
            // in-place edit/reconcile upsert preserves it), so it's the
            // tiebreaker on both the newest-N window and the final order.
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
        // '%' and '_' typed in the search box are literal characters to the
        // user, not wildcards — unescaped, a single "_" matched every contact
        // and silently returned the whole address book
        val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return queryList(
            // saved contacts and joined groups; @lid alias rows of phone-JID
            // contacts are stored with is_saved=0, so a LID row only shows up
            // here when it is the contact's sole (saved) identity
            "SELECT id, name, phone, is_group FROM contacts " +
                "WHERE is_self=0 AND (is_saved=1 OR is_group=1) " +
                "AND (name LIKE ? ESCAPE '\\' OR phone LIKE ? ESCAPE '\\' OR id LIKE ? ESCAPE '\\') " +
                "ORDER BY name COLLATE NOCASE LIMIT ?",
            arrayOf(like, like, like, limit.toString())
        ) {
            val phone = it.getString(2)
            ChatRow(
                id = it.getString(0), name = it.getString(1),
                lastText = if (phone.isNotEmpty()) "+$phone" else "",
                lastTime = 0, unread = 0, isGroup = it.getInt(3) != 0
            )
        }
    }

    fun nextAudioMessage(chatId: String, afterMsgId: String): MessageRow? {
        val after = queryFirst(
            "SELECT time_sent, rowid FROM messages WHERE chat_id=? AND id=?",
            arrayOf(chatId, afterMsgId)
        ) { Pair(it.getLong(0), it.getLong(1)) } ?: return null
        // (time_sent, rowid) so a voice note in the same second as the current
        // one still chains, matching the display order's rowid tiebreaker.
        return queryFirst(
            // from_me is part of the media-retry message key: hardcoding it
            // false sent an unanswerable retry receipt for our OWN expired
            // voice notes, so chaining onto one always died on the 60s timeout
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

    // Oldest locally known message of a chat, used as the anchor for on-demand history
    // requests. Skips messages with no real timestamp (time_sent=0 — e.g. an edit whose
    // original was never stored): such a row sorts as "oldest" but can't anchor a request
    // for anything older (the phone would be asked for messages before epoch 0 and answer
    // with nothing), silently stalling pagination.
    fun oldestMessage(chatId: String): MessageRow? =
        oneMessage(chatId, "time_sent>0", "time_sent ASC")

    fun newestMessage(chatId: String): MessageRow? =
        oneMessage(chatId, "time_sent>0", "time_sent DESC, rowid DESC")

    fun messageCount(chatId: String): Int = queryFirst(
        "SELECT COUNT(*) FROM messages WHERE chat_id=?", arrayOf(chatId)
    ) { it.getInt(0) } ?: 0

    /**
     * Which WhatsApp chat a message id now lives under, or null if it isn't
     * stored — it finds a row whose chat was re-keyed by [mergeChat] while
     * something was still holding the old id.
     *
     * WhatsApp only: its message ids are globally unique, so an id alone
     * identifies a row. TDLib ids are unique only within a chat (the first
     * message of every Telegram chat shares the same id), so 'tg:' chats are
     * excluded rather than matched arbitrarily by LIMIT 1.
     */
    fun chatOfMessage(msgId: String): String? = queryFirst(
        "SELECT chat_id FROM messages WHERE id=? AND chat_id NOT LIKE 'tg:%' LIMIT 1",
        arrayOf(msgId)
    ) { it.getString(0) }

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
            // NULLIF on the contact name too: a nameless contact row (an empty
            // push name) used to win the COALESCE and title the chat with an
            // empty string instead of falling through to the phone number
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
}
