package org.unichat.app

import android.content.Context
import androidx.appcompat.app.AlertDialog

object ProtoPicker {
    const val WA = "wa"
    const val TG = "tg"

    fun linked(): List<String> = buildList {
        if (Bridge.hasSession()) add(WA)
        if (Tg.hasSession()) add(TG)
    }

    fun label(ctx: Context, proto: String): String =
        ctx.getString(if (proto == TG) R.string.telegram else R.string.whatsapp)

    fun pick(ctx: Context, onPick: (String) -> Unit) {
        val options = linked()
        when (options.size) {
            0 -> {}
            1 -> onPick(options[0])
            else -> AlertDialog.Builder(ctx)
                .setTitle(R.string.choose_account)
                .setItems(options.map { label(ctx, it) }.toTypedArray()) { _, which ->
                    onPick(options[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
