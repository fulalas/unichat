package org.unichat.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Small persisted UI preferences: theme mode and font scale. */
object Prefs {
    private const val FILE = "unichat_prefs"
    private const val KEY_NIGHT = "night_mode"
    private const val KEY_FONT = "font_scale"
    private const val KEY_TG_LINKED = "tg_linked"
    private const val KEY_SCROLL = "scroll_"

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

    /** Passing a null [msgId] forgets the chat's anchor (it is back at the end). */
    fun setScrollAnchor(ctx: Context, chatId: String, msgId: String?, offset: Int) {
        val e = prefs(ctx).edit()
        if (msgId.isNullOrEmpty()) e.remove(KEY_SCROLL + chatId)
        else e.putString(KEY_SCROLL + chatId, "$msgId:$offset")
        e.apply()
    }

    // whether a Telegram account is linked (mirrors TDLib's async auth state so
    // startup routing has a synchronous answer)
    fun tgLinked(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_TG_LINKED, false)

    fun setTgLinked(ctx: Context, linked: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TG_LINKED, linked).apply()
}
