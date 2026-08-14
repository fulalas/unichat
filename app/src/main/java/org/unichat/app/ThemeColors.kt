package org.unichat.app

import android.content.Context
import android.util.TypedValue

/**
 * Resolves a theme color attribute — the per-protocol chat colors (chatAccent
 * & friends), which ChatActivity swaps to the WhatsApp set via a theme overlay.
 */
fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

/**
 * Accent of the protocol a chat belongs to, for surfaces shown OUTSIDE a chat
 * screen (the chat list), where the theme cannot say which protocol a given row
 * is: green for WhatsApp, blue for Telegram. Inside a chat, use ?attr/chatAccent
 * — ChatActivity's theme overlay already resolves it per protocol.
 */
fun Context.protocolAccent(chatId: String): Int =
    getColor(if (Tg.isTgId(chatId)) R.color.accent else R.color.accent_wa)
