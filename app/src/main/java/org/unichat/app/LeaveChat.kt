package org.unichat.app

import android.content.Context
import androidx.appcompat.app.AlertDialog

fun leaveLabel(chatIds: List<String>): Int =
    if (chatIds.all { isNewsletterId(it) }) R.string.unfollow_channel else R.string.leave_chat

fun leaveChatDialog(
    ctx: Context,
    ids: List<String>,
    onConfirm: (deleted: Boolean) -> Unit,
): AlertDialog.Builder {
    val title =
        if (ids.size > 1) ctx.getString(R.string.leave_chats_title, ids.size)
        else ctx.getString(
            if (isNewsletterId(ids[0])) R.string.unfollow_title else R.string.leave_chat_title,
            chatLabel(ids[0], Bridge.db.displayName(ids[0])),
        )
    val checks = booleanArrayOf(true, true)
    return AlertDialog.Builder(ctx)
        .setTitle(title)
        .setMultiChoiceItems(
            arrayOf(ctx.getString(R.string.leave_also_delete), ctx.getString(R.string.delete_chat_media)),
            checks,
        ) { _, which, isChecked -> checks[which] = isChecked }
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(leaveLabel(ids)) { _, _ ->
            for (id in ids) Bridge.leaveChat(id, checks[0], checks[0] && checks[1])
            onConfirm(checks[0])
        }
}
