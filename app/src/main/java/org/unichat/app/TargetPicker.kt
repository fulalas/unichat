package org.unichat.app

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/**
 * Multi-select chat picker shared by forward (ChatActivity) and external share
 * (ShareActivity). Any number of chats can be ticked; the positive button is
 * enabled only once at least one is, and onPick receives the chosen ids in tick
 * order. onCancel runs when the dialog is dismissed without a pick (used by the
 * share flow to finish the activity).
 */
fun Activity.showTargetPicker(
    titleRes: Int,
    labels: List<String>,
    ids: List<String>,
    onCancel: () -> Unit = {},
    onPick: (List<String>) -> Unit,
) {
    val chosen = LinkedHashSet<Int>()
    val dialog = AlertDialog.Builder(this)
        .setTitle(titleRes)
        .setMultiChoiceItems(labels.toTypedArray(), BooleanArray(ids.size)) { d, which, isChecked ->
            if (isChecked) chosen.add(which) else chosen.remove(which)
            (d as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = chosen.isNotEmpty()
        }
        .setPositiveButton(R.string.send) { _, _ -> onPick(chosen.map { ids[it] }) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        .setOnCancelListener { onCancel() }
        .show()
    // nothing ticked yet
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
}
