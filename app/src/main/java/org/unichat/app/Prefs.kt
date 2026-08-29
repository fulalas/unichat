package org.unichat.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    private const val FILE = "unichat_prefs"
    private const val KEY_NIGHT = "night_mode"
    private const val KEY_FONT = "font_scale"
    private const val KEY_TG_LINKED = "tg_linked"
    private const val KEY_TG_SELF = "tg_self_id"
    private const val KEY_SCROLL = "scroll_"
    private const val KEY_COMPLETE = "hist_done_"
    private const val KEY_DRAFT = "draft_"
    private const val KEY_PROTO_ENABLED = "proto_enabled_"
    private const val KEY_SG_DISCOVERABLE = "sg_discoverable"
    private const val KEY_SG_READ_RECEIPTS = "sg_read_receipts"
    private const val KEY_SG_CONTACTS_RESTORED = "sg_contacts_restored"

    const val FONT_MIN = 0.8f
    const val FONT_MAX = 1.6f

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun nightMode(ctx: Context): Int =
        prefs(ctx).getInt(KEY_NIGHT, AppCompatDelegate.MODE_NIGHT_YES)

    fun setNightMode(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt(KEY_NIGHT, mode).apply()

    fun fontScale(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_FONT, 1.1f).coerceIn(FONT_MIN, FONT_MAX)

    fun setFontScale(ctx: Context, scale: Float) =
        prefs(ctx).edit().putFloat(KEY_FONT, scale.coerceIn(FONT_MIN, FONT_MAX)).apply()

    /**
     * Where a chat was left, as the id of the first visible message plus its
     * pixel offset. An anchor, not the layout manager's saved state: adapter
     * positions are meaningless in a fresh process, where the loaded window is
     * rebuilt from scratch.
     */
    fun scrollAnchor(ctx: Context, chatId: String): Pair<String, Int>? {
        val raw = prefs(ctx).getString(KEY_SCROLL + chatId, null) ?: return null
        val at = raw.lastIndexOf(':')
        if (at <= 0) return null
        val offset = raw.substring(at + 1).toIntOrNull() ?: return null
        return raw.substring(0, at) to offset
    }

    fun setScrollAnchor(ctx: Context, chatId: String, msgId: String?, offset: Int) {
        val e = prefs(ctx).edit()
        if (msgId.isNullOrEmpty()) e.remove(KEY_SCROLL + chatId)
        else e.putString(KEY_SCROLL + chatId, "$msgId:$offset")
        e.apply()
    }

    /**
     * Whether a chat has been paged back to its very first message. Persisted,
     * unlike Bridge's in-memory copy, because search uses it to tell you it
     * really did read everything — an answer that must survive a restart.
     */
    fun historyComplete(ctx: Context, chatId: String): Boolean =
        prefs(ctx).getBoolean(KEY_COMPLETE + chatId, false)

    fun setHistoryComplete(ctx: Context, chatId: String) =
        prefs(ctx).edit().putBoolean(KEY_COMPLETE + chatId, true).apply()

    /**
     * Forgets the claim for one chat. A deleted or re-linked account re-syncs
     * from nothing, and a search still promising it had read everything would
     * be exactly the false comfort this replaced.
     */
    fun clearHistoryComplete(ctx: Context, chatId: String) {
        prefs(ctx).edit().remove(KEY_COMPLETE + chatId).apply()
    }

    /** The same, for every chat [matches] names. Takes a predicate rather than
     *  clearing the lot: unlinking one account must not retract the claim for
     *  the chats of an account that is still linked. */
    fun clearHistoryCompleteWhere(ctx: Context, matches: (String) -> Boolean) {
        val p = prefs(ctx)
        val e = p.edit()
        for (key in p.all.keys) {
            if (key.startsWith(KEY_COMPLETE) && matches(key.removePrefix(KEY_COMPLETE))) {
                e.remove(key)
            }
        }
        e.apply()
    }

    fun draft(ctx: Context, chatId: String): String =
        prefs(ctx).getString(KEY_DRAFT + chatId, "").orEmpty()

    fun setDraft(ctx: Context, chatId: String, text: String) {
        val e = prefs(ctx).edit()
        if (text.isBlank()) e.remove(KEY_DRAFT + chatId)
        else e.putString(KEY_DRAFT + chatId, text)
        e.apply()
    }

    /**
     * Own Telegram user id. Persisted because it otherwise costs a getMe round
     * trip after TDLib authorizes: a share sheet that starts the process asks
     * for it in the same breath, and got "tg:0" — a target that cannot be sent
     * to and opens an empty chat.
     */
    fun tgSelfId(ctx: Context): Long = prefs(ctx).getLong(KEY_TG_SELF, 0L)

    fun setTgSelfId(ctx: Context, id: Long) =
        prefs(ctx).edit().putLong(KEY_TG_SELF, id).apply()

    /** A paused protocol stays linked but is kept off the network. Defaults to
     *  on, so an account is live the moment it is linked. */
    fun protoEnabled(ctx: Context, proto: String): Boolean =
        prefs(ctx).getBoolean(KEY_PROTO_ENABLED + proto, true)

    fun setProtoEnabled(ctx: Context, proto: String, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_PROTO_ENABLED + proto, enabled).apply()

    fun clearProtoEnabled(ctx: Context, proto: String) =
        prefs(ctx).edit().remove(KEY_PROTO_ENABLED + proto).apply()

    /** Mirrors the server-side account attribute; registration sets it true. */
    fun sgDiscoverable(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SG_DISCOVERABLE, true)

    fun setSgDiscoverable(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SG_DISCOVERABLE, on).apply()

    /** Honoured locally: the account record that would publish it lives in the
     *  storage service, which this account cannot write yet. */
    fun sgReadReceipts(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SG_READ_RECEIPTS, true)

    fun setSgReadReceipts(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SG_READ_RECEIPTS, on).apply()

    /** Whether the PIN has already unlocked the account's stored contact list.
     *  Manage accounts stops offering the recovery once it has. */
    fun sgContactsRestored(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SG_CONTACTS_RESTORED, false)

    fun setSgContactsRestored(ctx: Context, done: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SG_CONTACTS_RESTORED, done).apply()

    fun tgLinked(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_TG_LINKED, false)

    fun setTgLinked(ctx: Context, linked: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TG_LINKED, linked).apply()
}
