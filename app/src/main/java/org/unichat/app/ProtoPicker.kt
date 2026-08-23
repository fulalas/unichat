package org.unichat.app

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

object ProtoPicker {
    const val WA = "wa"
    const val TG = "tg"
    const val SG = "sg"

    /** Every protocol the app speaks, linked or not. */
    val ALL = listOf(WA, TG, SG)

    fun linked(): List<String> = buildList {
        if (Bridge.hasSession()) add(WA)
        if (Tg.hasSession()) add(TG)
        if (Signal.hasSession()) add(SG)
    }

    fun isLinked(proto: String): Boolean = proto in linked()

    /** Linked and switched on. A paused account is disconnected from its
     *  network, so it can neither send nor read back a profile — only Manage
     *  accounts may list one. */
    fun active(): List<String> = linked().filter { Bridge.protoEnabled(it) }


    /** Which protocol a chat id belongs to. WhatsApp is the unprefixed one. */
    fun of(chatId: String): String = when {
        Tg.isTgId(chatId) -> TG
        Signal.isSgId(chatId) -> SG
        else -> WA
    }

    fun label(ctx: Context, proto: String): String = ctx.getString(
        when (proto) {
            TG -> R.string.telegram
            SG -> R.string.signal
            else -> R.string.whatsapp
        }
    )

    fun pick(ctx: Context, onPick: (String) -> Unit) = pickFrom(ctx, active(), onPick)

    fun pickFrom(ctx: Context, options: List<String>, onPick: (String) -> Unit) {
        when (options.size) {
            0 -> Toast.makeText(ctx, R.string.no_active_account, Toast.LENGTH_SHORT).show()
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
