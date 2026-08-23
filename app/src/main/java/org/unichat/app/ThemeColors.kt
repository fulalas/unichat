package org.unichat.app

import android.content.Context
import android.util.TypedValue

fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Context.protocolAccentOf(proto: String): Int = getColor(
    when (proto) {
        ProtoPicker.TG -> R.color.accent
        ProtoPicker.SG -> R.color.accent_sg
        else -> R.color.accent_wa
    }
)

fun Context.protocolAccent(chatId: String): Int = protocolAccentOf(ProtoPicker.of(chatId))
