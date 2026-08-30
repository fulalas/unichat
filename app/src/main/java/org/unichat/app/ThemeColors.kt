package org.unichat.app

import android.content.Context
import android.util.TypedValue

fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    if (!theme.resolveAttribute(attr, tv, true)) {
        // an attr missing from the theme leaves tv at 0 — invisible ticks and
        // spans with nothing logged
        android.util.Log.w("ThemeColors", "attr $attr not in active theme")
    }
    return tv.data
}

fun Context.protocolAccentOf(proto: String): Int = getColor(Accounts.of(proto).accentRes)

// Straight to the account: going through ProtoPicker.of resolved the id to one
// and then looked the same one up again by name, once per chat row.
fun Context.protocolAccent(chatId: String): Int = getColor(Accounts.ofChat(chatId).accentRes)
