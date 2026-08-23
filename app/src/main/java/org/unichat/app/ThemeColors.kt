package org.unichat.app

import android.content.Context
import android.util.TypedValue

fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Context.protocolAccentOf(proto: String): Int = getColor(Accounts.of(proto).accentRes)

fun Context.protocolAccent(chatId: String): Int = protocolAccentOf(ProtoPicker.of(chatId))
