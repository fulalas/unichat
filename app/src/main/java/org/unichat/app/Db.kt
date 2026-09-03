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
    val fileStatus: Int = 0, // 0 none, 1 downloading, 2 downloaded, 3 failed
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
        // a contact card's body is "name\nphone..."
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
        // Without these tombstones the next mirror pass put a locally deleted
        // chat straight back: both protocols re-announce their whole chat list
        // on connect, so it returned on every app start.
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

        // outgoing counterpart for markReadUpTo's peer-read branch, which runs
        // per read receipt on the tg-receive thread and scanned the whole chat
        private const val CREATE_UNREAD_OUT_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_unread_out ON messages(chat_id) " +
                "WHERE from_me=1 AND is_read=0"

        // Every message window query and the chat list's newest-message lookup
        // order by (chat_id, time_sent). IF NOT EXISTS so onCreate and the
        // upgrade path can share one statement — this index shipped in onCreate
        // only, so databases created before it existed never got it.
        private const val CREATE_TIME_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_msg_time ON messages(chat_id, time_sent)"

        // In the database, not prefs: a force stop or a kill never runs onStop,
        // which is exactly when the reading position is worth keeping.
        private const val CREATE_SCROLL =
            "CREATE TABLE IF NOT EXISTS scroll (chat_id TEXT PRIMARY KEY, " +
                "msg_id TEXT NOT NULL, offset INTEGER NOT NULL)"
        // messageChat looks a message up by id, which the (chat_id, id) primary key
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

        // `status` records a link that has no preview at all (0 unknown, 1 has
        // one, 2 none), so a page that answers with nothing is not re-fetched on
        // every single bind of that bubble. Only that negative verdict expires —
        // a page's own metadata is what it is, but a "none" can be our fault.
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

    /**
     * Everything here is a local cache of what the phone holds, so a schema
     * regression (sideloading an older build over a newer database) is recovered
     * by rebuilding from scratch and re-syncing. The base class's default throws
     * SQLiteDowngradeFailedException instead, which crashed the app on every
     * launch with no way out.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS link_previews")
        db.execSQL("DROP TABLE IF EXISTS deleted_chats")
        db.execSQL("DROP TABLE IF EXISTS reactions")
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS chats")
        db.execSQL("DROP TABLE IF EXISTS contacts")
        onCreate(db)
    }

    /**
     * Blocks run in ASCENDING version order, and new ones belong at the bottom.
     * A migration step has to see the schema every earlier step produced, so a
     * block placed out of order can reference a column or index that has not
     * been added yet and take onUpgrade down mid-transaction on a real database.
     */
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
            // Telegram media could be pointing at the wrong file entirely: a
            // stale file id was trusted to fetch with, and TDLib reuses those
            // ids across sessions. Which rows are wrong is not knowable, so
            // every association is dropped and re-derived from the message.
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
            // v23 shipped this index on text='' only; replace with the wider
            // predicate so the file_id backfill sweep is covered too
            db.execSQL("DROP INDEX IF EXISTS idx_msg_empty_contact")
            db.execSQL(CREATE_EMPTY_CONTACT_INDEX)
        }
        if (oldVersion < 25) {
            db.execSQL(CREATE_LINK_PREVIEWS)
        }
        if (oldVersion < 26) {
            // v25 read only the first 512 KB of a page, so every link that
            // buries its Open Graph tags past that (YouTube) was recorded as
            // having no preview. Those verdicts are wrong, not stale.
            db.execSQL("DELETE FROM link_previews WHERE status=2")
        }
        if (oldVersion < 27) {
            // The whole table is a cache of parsed pages, so a change to the
            // parser invalidates it: v26 and earlier folded a description's line
            // breaks into nothing, running its words together.
            db.execSQL("DELETE FROM link_previews")
        }
        if (oldVersion < 28) {
            // Same reason again: previews stored before this were fetched as an
            // ordinary client, which several sites answer with a bot check
            // instead of their metadata (see LinkPreview.USER_AGENT).
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
            // Rows from before the "ptv" file id kind can hide a WhatsApp round
            // video note inside a plain video row; editing one converted it to a
            // rectangular video on the peer, so they are all locked.
            db.execSQL(
                "UPDATE messages SET caption_locked=1 WHERE msg_type='video' AND from_me=1 " +
                    "AND chat_id NOT LIKE 'tg:%' AND chat_id NOT LIKE 'sg:%'"
            )
            // A Signal album that lost a child before the flag existed re-sends
            // only the surviving attachments on edit, shrinking the peer's copy;
            // a gap in the "-n" suffixes betrays the middle deletions.
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

    // Cached because upsertMessage consults it on every stored message, on the
    // protocol threads.
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

    // Heals a 1:1 chat that was keyed by a contact's LID before the LID→phone
    // mapping was known (see Bridge.reconcileLidChats).
    fun mergeChat(fromId: String, toId: String): Boolean {
        if (fromId == toId) return false
        val db = writableDatabase
        if (!chatExists(db, fromId)) return false
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO chats(id) VALUES(?)", arrayOf(toId))
            // OR IGNORE skips any row that already exists under the target's
            // key, then the leftovers are dropped
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
                // time_sent 0 / empty file_id, which must not clobber.
                // time_pinned holds that correction off a send that failed: its
                // ack can land minutes later, and taking that time moved the
                // bubble out of the conversation it belongs to.
                "time_sent=CASE WHEN excluded.time_sent>0 AND time_pinned=0 " +
                "THEN excluded.time_sent ELSE time_sent END," +
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

    // One transaction: as two independent statements, a process kill or an
    // SQLite error between them left the message gone but its reactions behind —
    // orphan rows no later query can reach or clean up.
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
        // Editing an album's caption re-sends the attachments its rows still
        // hold, so deleting one here would drop it from the peer's copy too.
        val parent = msgId.substringBeforeLast('-', "")
        if (parent.toLongOrNull() != null && msgId.substringAfterLast('-').toIntOrNull() != null) {
            execSQL(
                "UPDATE messages SET caption_locked=1 WHERE chat_id=? AND id=?",
                arrayOf(chatId, parent)
            )
        }
    }

    fun recentMessages(chatId: String, limit: Int): List<MessageRow> = queryList(
        // Staged sends are excluded: their ids are minted here and no other
        // device has them, so a delete keyed by one is accepted and ignored.
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
        // Cache entry first: suppressed() reads it on the protocol threads, and
        // a mirror pass landing between the commit and the cache write used to
        // re-insert the rows just deleted, resurrecting the chat.
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

    // Update-only: a mute event for a chat we have no row for is ignored rather
    // than materialising a blank phantom chat; the flag is picked up by
    // reconcile once the chat exists.
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

    // Update-only, like setMuted: no phantom chat for an archive event
    // arriving before the chat row exists.
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

    // msgId "" means the chat was at the bottom, which is not the same as having
    // no position: the chat then opens on whatever arrived since.
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

    // Deliberately text='' only: file_id (the messageable id) stays empty
    // forever for cards that legitimately have none — a wider predicate would
    // re-ask the phone for those on every run without end.
    fun emptyContactSenders(chatId: String, limit: Int): List<Pair<String, String>> = queryList(
        "SELECT id, sender_id FROM messages " +
            "WHERE chat_id=? AND msg_type='contact' AND text='' " +
            "ORDER BY time_sent DESC LIMIT ?",
        arrayOf(chatId, limit.toString())
    ) { Pair(it.getString(0), it.getString(1)) }

    // Returns the rows updated, like setFileState: an id that matches nothing is
    // how a played state read from the server goes missing without a trace.
    fun setPlayed(chatId: String, msgId: String): Int =
        writableDatabase.compileStatement(
            "UPDATE messages SET played=1, send_failed=0, send_pending=0 WHERE chat_id=? AND id=?"
        ).use {
            it.bindString(1, chatId)
            it.bindString(2, msgId)
            it.executeUpdateDelete()
        }

    // Keyed by id, not by file path: Telegram serves a single file for every
    // copy of the same voice note, so a path can belong to several rows.
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

    // 0 rows means the message no longer exists — its chat can be deleted while
    // a download is still in flight.
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

    // from_me is a literal, not a bound parameter: the partial unread indexes
    // only match when the query provably implies their predicate, and a `?`
    // does not — bound, every read receipt walked the chat's whole PK prefix.
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

    // No transfer survives a process death, so a stored status of 1 is always
    // stale — and it made the bubble skip its own retry, leaving that media
    // blank for good.
    fun clearStaleDownloads() {
        writableDatabase.execSQL("UPDATE messages SET file_status=0 WHERE file_status=1")
    }

    /**
     * [predicate] is built per table with the id column's name, because it is
     * `chat_id` on messages and reactions and `id` everywhere else. Passing the
     * column in beats rewriting the finished SQL: a blind replace of "id" would
     * also hit any predicate whose literal happened to contain those letters.
     */
    private fun clearProtocolData(predicate: (String) -> String) = writableDatabase.transact {
        execSQL("DELETE FROM messages WHERE ${predicate("chat_id")}")
        execSQL("DELETE FROM reactions WHERE ${predicate("chat_id")}")
        execSQL("DELETE FROM chats WHERE ${predicate("id")}")
        execSQL("DELETE FROM contacts WHERE ${predicate("id")}")
        execSQL("DELETE FROM deleted_chats WHERE ${predicate("id")}")
        execSQL("DELETE FROM scroll WHERE ${predicate("chat_id")}")
    }

    fun clearSignalData() = clearProtocolData { "$it LIKE 'sg:%'" }

    // WhatsApp is the protocol without a prefix, so its rows can only be named
    // by excluding every other protocol's. Each new prefix has to be added here
    // or unlinking WhatsApp silently wipes that protocol's chats too.
    fun clearWaData() = clearProtocolData { "$it NOT LIKE 'tg:%' AND $it NOT LIKE 'sg:%'" }

    // Not upsertChat: it writes `archived` too, so using it for a rename
    // un-archived the chat.
    fun renameChat(id: String, name: String) {
        if (name.isEmpty()) return
        writableDatabase.execSQL(
            "INSERT INTO chats(id, name) VALUES(?,?) " +
                "ON CONFLICT(id) DO UPDATE SET name=excluded.name",
            arrayOf(id, name)
        )
    }

    fun clearTgData() = clearProtocolData { "$it LIKE 'tg:%'" }

    // Telegram file ids are only valid inside the TDLib session that issued
    // them, so a stored one goes stale across restarts and has to be re-resolved
    // and written back.
    fun setFileId(chatId: String, msgId: String, fileId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET file_id=? WHERE chat_id=? AND id=?",
            arrayOf(fileId, chatId, msgId)
        )
    }

    // Telegram ids repeat across chats, so a lookup by id alone can never answer
    // with one. Signal's are send timestamps: without prefix and fromMe, a
    // receipt for a message we sent can land on an incoming one that happens to
    // share the millisecond.
    fun messageChat(msgId: String, prefix: String = "", fromMe: Boolean? = null): String? = queryFirst(
        "SELECT chat_id FROM messages WHERE id=? AND chat_id NOT LIKE 'tg:%' AND chat_id LIKE ?" +
            (fromMe?.let { " AND from_me=${if (it) 1 else 0}" } ?: "") + " LIMIT 1",
        arrayOf(msgId, "$prefix%")
    ) { it.getString(0) }

    // send_failed is cleared here: a transport error is not proof the message
    // never left (an ack that times out still delivers), and a receipt from the
    // other side settles it. Left set, the message they had already read kept
    // the red marker for good, and tapping it sent them a second copy.
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

    // A read sync from another device names how far THAT device had read. Marking
    // the whole chat instead swallowed everything that arrived here in between.
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

    // Matched on the stored bare-digit number: contact discovery answers with
    // whichever identifier the server felt like giving, so the id it returns
    // need not be the one an existing chat is keyed by.
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

    // Not filtered through suppressed(): a chat the user deleted and is now
    // typing into is one they want back, and the send vanished without a trace.
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

    // send_failed is deliberately left standing: the mark stays until the
    // message is really sent, so a retry in progress must not clear it and leave
    // the row with no mark and no tick.
    fun setSendPending(chatId: String, msgId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET send_pending=1 WHERE chat_id=? AND id=?",
            arrayOf(chatId, msgId)
        )
    }

    // Both marks: a send the watchdog gave up on can still land, and a red mark
    // left on a delivered message makes tapping it send a second copy.
    // The stored time can differ from the one just written: a failed send keeps
    // its own (time_pinned), and the chat list must not be bumped past it.
    fun storedTime(chatId: String, msgId: String): Long? = queryFirst(
        "SELECT time_sent FROM messages WHERE chat_id=? AND id=?", arrayOf(chatId, msgId)
    ) { it.getLong(0) }

    fun clearSendMarks(chatId: String, msgId: String) {
        writableDatabase.execSQL(
            "UPDATE messages SET send_pending=0, send_failed=0 WHERE chat_id=? AND id=?",
            arrayOf(chatId, msgId)
        )
    }

    // Reactions move with the row or they are orphaned. A row already under the
    // new id means the protocol's own copy got here first, so the staged one is
    // dropped rather than overwriting a sent message with an unsent row.
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

    // A send in flight cannot survive the process: nothing is left to report
    // its outcome. Except a Telegram message TDLib accepted — its send queue
    // outlives the process and it reports on the next start, so flagging those
    // offered a retry that sent the peer a second copy. A row still under its
    // staged id never reached TDLib and is ours to fail.
    fun failStalePending() {
        writableDatabase.execSQL(
            "UPDATE messages SET send_pending=0, send_failed=1, time_pinned=1 " +
                "WHERE send_pending=1 " +
                "AND NOT (chat_id LIKE 'tg:%' AND id NOT LIKE 'local:%')"
        )
    }

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
            // Telegram groups/channels have negative raw ids, private chats
            // positive; a Signal group is any sg: id whose remainder is not a
            // 36-char UUID. Must agree with isGroupId() in Jid.kt.
            // The lowercase 'pni:' too: REPLACE is case-sensitive, and rows
            // written before the prefix was corrected use that spelling — left
            // out, every one of those one-to-one chats came back 40 characters
            // long and rendered as a group.
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
        val folded = Search.fold(query)
        if (folded.isEmpty()) return emptyList()
        val out = ArrayList<ChatRow>(limit)
        // Matched in Kotlin rather than with SQL LIKE: SQLite folds neither case
        // outside ASCII nor accents, so "sao" never found "São" — and LIKE also
        // needed '%'/'_' escaping, which a missed escape turned into "every
        // contact matches". Only saved contacts and joined groups; @lid alias
        // rows of phone-JID contacts are stored with is_saved=0, so a LID row
        // shows up only when it is the contact's sole (saved) identity.
        // One person, one row per protocol. Signal hands out the same contact
        // under an ACI and under a PNI alias, both saved and both carrying the
        // number, which listed the same name and number twice.
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
                // An id with a chat behind it is the one the person actually
                // messages from; an alias id is the weakest handle.
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
        // The winner of a duplicate pair takes the loser's slot, and the two can
        // carry different names, so the query's own ordering no longer holds.
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return out
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

    // Skips messages with no real timestamp (time_sent=0 — e.g. an edit whose
    // original was never stored): such a row sorts as "oldest" but can't anchor a
    // history request for anything older (the phone would be asked for messages
    // before epoch 0 and answer with nothing), silently stalling pagination.
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

    // An expired "has no preview" verdict answers null, like a link never looked
    // up: a site can gain preview tags, and a parser of ours that reads a page
    // wrongly today must not silence that link forever.
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

    // For previews whose cached image the sweep has reclaimed: the next bind
    // fetches them again instead of rendering a card with a hole.
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
