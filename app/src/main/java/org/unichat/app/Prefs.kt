package org.unichat.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    private const val FILE = "unichat_prefs"
    private const val KEY_NIGHT = "night_mode"
    private const val KEY_FONT = "font_scale"
    private const val KEY_TG_LINKED = "tg_linked"
    private const val KEY_TG_SELF = "tg_self_id"
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

    // Persisted, unlike Bridge's in-memory copy, because search uses it to tell
    // you it really did read everything — an answer that must survive a
    // restart.
    fun historyComplete(ctx: Context, chatId: String): Boolean =
        prefs(ctx).getBoolean(KEY_COMPLETE + chatId, false)

    fun setHistoryComplete(ctx: Context, chatId: String) =
        prefs(ctx).edit().putBoolean(KEY_COMPLETE + chatId, true).apply()

    // A deleted or re-linked account re-syncs from nothing, and a search still
    // promising it had read everything would be exactly the false comfort this
    // replaced.
    fun clearHistoryComplete(ctx: Context, chatId: String) {
        prefs(ctx).edit().remove(KEY_COMPLETE + chatId).apply()
    }

    // Takes a predicate rather than clearing the lot: unlinking one account
    // must not touch the chats of an account that is still linked. Drafts and
    // anchors included because a re-linked account inherited the old sync's
    // message text and anchors pointing at ids that no longer exist.
    fun clearChatPrefsWhere(ctx: Context, matches: (String) -> Boolean) {
        val p = prefs(ctx)
        val e = p.edit()
        for (key in p.all.keys) {
            for (prefix in arrayOf(KEY_COMPLETE, KEY_DRAFT)) {
                if (key.startsWith(prefix) && matches(key.removePrefix(prefix))) e.remove(key)
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

    // Persisted because it otherwise costs a getMe round trip after TDLib
    // authorizes: a share sheet that starts the process asks for it in the same
    // breath, and got "tg:0" — a target that cannot be sent to and opens an
    // empty chat.
    fun tgSelfId(ctx: Context): Long = prefs(ctx).getLong(KEY_TG_SELF, 0L)

    fun setTgSelfId(ctx: Context, id: Long) =
        prefs(ctx).edit().putLong(KEY_TG_SELF, id).apply()

    fun protoEnabled(ctx: Context, proto: String): Boolean =
        prefs(ctx).getBoolean(KEY_PROTO_ENABLED + proto, true)

    fun setProtoEnabled(ctx: Context, proto: String, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_PROTO_ENABLED + proto, enabled).apply()

    fun clearProtoEnabled(ctx: Context, proto: String) =
        prefs(ctx).edit().remove(KEY_PROTO_ENABLED + proto).apply()

    fun sgDiscoverable(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SG_DISCOVERABLE, true)

    fun setSgDiscoverable(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SG_DISCOVERABLE, on).apply()

    // Honoured locally only: the account record that would publish it lives in
    // the storage service, which this account cannot write yet.
    fun sgReadReceipts(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SG_READ_RECEIPTS, true)

    fun setSgReadReceipts(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SG_READ_RECEIPTS, on).apply()

    fun sgContactsRestored(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SG_CONTACTS_RESTORED, false)

    fun setSgContactsRestored(ctx: Context, done: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SG_CONTACTS_RESTORED, done).apply()

    fun tgLinked(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_TG_LINKED, false)

    fun setTgLinked(ctx: Context, linked: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TG_LINKED, linked).apply()
}
