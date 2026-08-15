package org.unichat.app

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AlertDialog

/**
 * Multi-select chat picker shared by forward (ChatActivity) and external share
 * (ShareActivity). Any number of chats can be ticked; the positive button is
 * enabled only once at least one is, and onPick receives the chosen ids in tick
 * order. onCancel runs when the dialog is dismissed without a pick (used by the
 * share flow to finish the activity).
 *
 * The list is searchable. Ticks are held per chat rather than per row, so
 * narrowing the list — or clearing the query afterwards — never moves or drops
 * what was already chosen.
 */
/**
 * Every chat that can be a forward/share target, as parallel (label, id) lists
 * ready for [showTargetPicker]. Your own chat on each linked account comes
 * first, named after its protocol, and is listed even with no history — sending
 * to yourself is always available. Blocking: reads the chat table.
 *
 * The forward and share flows built this separately and had already drifted
 * (only one of them pinned a self chat with no history), even though both feed
 * the very same dialog.
 */
fun Activity.targetChoices(): Pair<List<String>, List<String>> {
    val ids = ArrayList<String>()
    val labels = ArrayList<String>()
    // resolved once, not per comparison: each is a JNI hop into the Go bridge
    // plus a preferences read
    for (self in listOf(Bridge.selfId(), if (Tg.hasSession()) Tg.selfId() else "")) {
        if (self.isEmpty() || self in ids) continue
        ids.add(self)
        labels.add(selfPickerLabel(this, self))
    }
    for (chat in Bridge.db.chats()) {
        if (chat.id in ids) continue
        ids.add(chat.id)
        labels.add(chat.displayLabelWithProto(this))
    }
    return labels to ids
}

fun Activity.showTargetPicker(
    titleRes: Int,
    labels: List<String>,
    ids: List<String>,
    onCancel: () -> Unit = {},
    onPick: (List<String>) -> Unit,
) {
    val view = layoutInflater.inflate(R.layout.dialog_target_picker, null)
    val search: EditText = view.findViewById(R.id.pickerSearch)
    val list: ListView = view.findViewById(R.id.pickerList)
    list.layoutParams.height = resources.displayMetrics.heightPixels / 2

    // ticked chats as indexes into `labels`/`ids`, in tick order — what onPick
    // reports; survives every re-filter
    val chosen = LinkedHashSet<Int>()
    // which of those the current query leaves on screen, row order
    val visible = labels.indices.toMutableList()
    val adapter = ArrayAdapter(
        this, android.R.layout.simple_list_item_multiple_choice, labels.toMutableList()
    )
    list.adapter = adapter

    val dialog = AlertDialog.Builder(this)
        .setTitle(titleRes)
        .setView(view)
        .setPositiveButton(R.string.send) { _, _ -> onPick(chosen.map { ids[it] }) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        .setOnCancelListener { onCancel() }
        .show()
    // nothing ticked yet
    val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    send.isEnabled = false

    list.setOnItemClickListener { _, _, position, _ ->
        // multipleChoice flips the row's own state before this runs
        val index = visible[position]
        if (list.isItemChecked(position)) chosen.add(index) else chosen.remove(index)
        send.isEnabled = chosen.isNotEmpty()
    }

    search.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            // same match as the main screen's chat search
            val q = s?.toString()?.trim().orEmpty()
            visible.clear()
            labels.indices.filterTo(visible) {
                q.isEmpty() || labels[it].contains(q, ignoreCase = true)
            }
            adapter.clear()
            adapter.addAll(visible.map { labels[it] })
            // rows moved, so re-mark them from `chosen`
            list.clearChoices()
            visible.forEachIndexed { pos, index -> if (index in chosen) list.setItemChecked(pos, true) }
        }

        override fun beforeTextChanged(cs: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(cs: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}
