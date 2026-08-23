package org.unichat.app

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Asks which account an action belongs to. What a protocol *is* lives in
 *  [Accounts]; this is only the choosing. */
object ProtoPicker {
    const val WA = "wa"
    const val TG = "tg"
    const val SG = "sg"

    /** Linked and switched on, as protocol keys. */
    fun active(): List<String> = Accounts.active().map { it.proto }

    fun pick(ctx: Context, onPick: (String) -> Unit) = pickFrom(ctx, active(), onPick)

    fun pickFrom(ctx: Context, options: List<String>, onPick: (String) -> Unit) {
        when (options.size) {
            0 -> Toast.makeText(ctx, R.string.no_active_account, Toast.LENGTH_SHORT).show()
            1 -> onPick(options[0])
            else -> AlertDialog.Builder(ctx)
                .setTitle(R.string.choose_account)
                .setItems(options.map { Accounts.of(it).label(ctx) }.toTypedArray()) { _, which ->
                    onPick(options[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
