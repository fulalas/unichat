package org.unichat.app

import android.content.Context
import android.util.TypedValue

fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Context.protocolAccent(chatId: String): Int =
    getColor(if (Tg.isTgId(chatId)) R.color.accent else R.color.accent_wa)
