package org.unichat.app

import android.content.Context
import android.util.TypedValue

fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    if (!theme.resolveAttribute(attr, tv, true)) {
        android.util.Log.w("ThemeColors", "attr $attr not in active theme")
    }
    return tv.data
}

fun Context.protocolAccentOf(proto: String): Int = getColor(Accounts.of(proto).accentRes)

fun Context.protocolAccent(chatId: String): Int = getColor(Accounts.ofChat(chatId).accentRes)
