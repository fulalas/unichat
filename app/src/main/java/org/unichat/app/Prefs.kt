package org.unichat.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    private const val FILE = "unichat_prefs"
    private const val KEY_NIGHT = "night_mode"
    private const val KEY_FONT = "font_scale"
    private const val KEY_TG_LINKED = "tg_linked"
    private const val KEY_SCROLL = "scroll_"
    private const val KEY_COMPLETE = "hist_done_"
    private const val KEY_DRAFT = "draft_"

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
     * Forgets the claim — for one chat, or (null) for every chat. A deleted or
     * re-linked account re-syncs from nothing, and a search still promising it
     * had read everything would be exactly the false comfort this replaced.
     */
    fun clearHistoryComplete(ctx: Context, chatId: String?) {
        val p = prefs(ctx)
        val e = p.edit()
        if (chatId != null) e.remove(KEY_COMPLETE + chatId)
        else p.all.keys.filter { it.startsWith(KEY_COMPLETE) }.forEach { e.remove(it) }
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

    fun tgLinked(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_TG_LINKED, false)

    fun setTgLinked(ctx: Context, linked: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TG_LINKED, linked).apply()
}
