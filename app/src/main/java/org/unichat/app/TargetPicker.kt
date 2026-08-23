package org.unichat.app

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AlertDialog

fun Activity.targetChoices(): Pair<List<String>, List<String>> {
    val ids = ArrayList<String>()
    val labels = ArrayList<String>()
    val chats = Bridge.visibleChats()
    // Every notes-to-self stays pinned above the chats, in a fixed order —
    // Telegram, Signal, WhatsApp — rather than by recency: Telegram's is where
    // files are sent, and ordering these the way everything below is ordered
    // pushed it under the others on any day it went unused.
    // selfIdBlocking, not selfId: both callers are on a worker thread, and a
    // share that started this process gets here before TDLib knows who we are.
    val selves = listOf(
        if (Tg.hasSession() && Bridge.protoEnabled(ProtoPicker.TG)) Tg.selfIdBlocking() else "",
        if (Bridge.protoEnabled(ProtoPicker.SG)) Signal.selfId() else "",
        if (Bridge.protoEnabled(ProtoPicker.WA)) Bridge.selfId() else "",
    )
        .filter { it.isNotEmpty() }
        .distinct()
    for (self in selves) {
        ids.add(self)
        labels.add(selfPickerLabel(this, self))
    }
    for (chat in chats) {
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
            val q = Search.fold(s?.toString()?.trim().orEmpty())
            visible.clear()
            labels.indices.filterTo(visible) {
                q.isEmpty() || Search.contains(labels[it], q)
            }
            adapter.clear()
            adapter.addAll(visible.map { labels[it] })
            list.clearChoices()
            visible.forEachIndexed { pos, index -> if (index in chosen) list.setItemChecked(pos, true) }
        }

        override fun beforeTextChanged(cs: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(cs: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}
