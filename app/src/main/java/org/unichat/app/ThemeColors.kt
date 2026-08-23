package org.unichat.app

import android.content.Context
import android.util.TypedValue

fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Context.protocolAccentOf(proto: String): Int = getColor(Accounts.of(proto).accentRes)

// Straight to the account: going through ProtoPicker.of resolved the id to one
// and then looked the same one up again by name, once per chat row.
fun Context.protocolAccent(chatId: String): Int = getColor(Accounts.ofChat(chatId).accentRes)
