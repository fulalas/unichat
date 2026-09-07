package org.unichat.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

fun Activity.targetChoices(): Pair<List<String>, List<String>> {
    val ids = ArrayList<String>()
    val labels = ArrayList<String>()
    val chats = Bridge.visibleChats()
    // Every notes-to-self stays pinned above the chats rather than ordered by
    // recency: Telegram's is where files are sent, and ordering these by recency
    // pushed it under the others on any day it went unused.
    // selfIdBlocking, not selfId: both callers are on a worker thread, and a
    // share that started this process gets here before TDLib knows who we are.
    val selves = Accounts.active()
        .map { it.selfIdBlocking() }
        .filter { it.isNotEmpty() }
        .distinct()
    for (self in selves) {
        ids.add(self)
        labels.add(selfPickerLabel(this, self))
    }
    val selfIds = selves.toHashSet()
    for (chat in chats) {
        if (chat.id in selfIds) continue
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
): AlertDialog {
    val view = layoutInflater.inflate(R.layout.dialog_target_picker, null)
    val search: EditText = view.findViewById(R.id.pickerSearch)
    val list: ListView = view.findViewById(R.id.pickerList)
    list.layoutParams.height = resources.displayMetrics.heightPixels / 2

    val adapter = TargetAdapter(this, labels, ids)
    list.adapter = adapter

    val dialog = AlertDialog.Builder(this)
        .setTitle(titleRes)
        .setView(view)
        .setPositiveButton(R.string.send) { _, _ -> onPick(adapter.chosenIds()) }
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        .setOnCancelListener { onCancel() }
        .show()
    val send = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    send.isEnabled = false

    // the row the finger hit is the only one that changed; rebinding the whole
    // list would re-request every visible avatar per tick
    list.setOnItemClickListener { _, row, position, _ ->
        send.isEnabled = adapter.toggle(position, row)
    }

    search.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            adapter.filter(Search.fold(s?.toString()?.trim().orEmpty()))
        }

        override fun beforeTextChanged(cs: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(cs: CharSequence?, start: Int, before: Int, count: Int) {}
    })
    return dialog
}

/**
 * Rows carry the avatar and its protocol-coloured ring, so the target list reads
 * like the chat list: a name alone said nothing about which account would send.
 */
private class TargetAdapter(
    private val context: Context,
    private val labels: List<String>,
    private val ids: List<String>,
) : BaseAdapter() {

    private class Holder(row: View) {
        val avatar: ImageView = row.findViewById(R.id.targetAvatar)
        val ring: View = row.findViewById(R.id.targetRing)
        val name: TextView = row.findViewById(R.id.targetName)
        val check: CheckBox = row.findViewById(R.id.targetCheck)
    }

    private val visible = labels.indices.toMutableList()
    // indexes into `labels`/`ids`, not list positions, so ticks survive a re-filter
    private val chosen = LinkedHashSet<Int>()
    // three accents at most, and getView runs per row per bind
    private val ringTints = HashMap<Int, ColorStateList>()
    private val avatarPx = (40 * context.resources.displayMetrics.density).toInt()

    fun chosenIds(): List<String> = chosen.map { ids[it] }

    /** Returns whether anything is ticked, for the dialog's Send button. */
    fun toggle(position: Int, row: View): Boolean {
        val index = visible[position]
        if (!chosen.remove(index)) chosen.add(index)
        (row.tag as? Holder)?.check?.isChecked = index in chosen
        return chosen.isNotEmpty()
    }

    fun filter(folded: String) {
        visible.clear()
        labels.indices.filterTo(visible) { folded.isEmpty() || Search.contains(labels[it], folded) }
        notifyDataSetChanged()
    }

    override fun getCount(): Int = visible.size

    override fun getItem(position: Int): Any = labels[visible[position]]

    override fun getItemId(position: Int): Long = visible[position].toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_target, parent, false)
            .also {
                val holder = Holder(it)
                holder.avatar.clipToOutline = true
                holder.avatar.outlineProvider = ViewOutlineProvider.BACKGROUND
                it.tag = holder
            }
        val holder = row.tag as Holder
        val index = visible[position]
        val id = ids[index]
        val label = labels[index]
        holder.name.text = label
        holder.check.isChecked = index in chosen
        val accent = context.protocolAccent(id)
        holder.ring.backgroundTintList =
            ringTints.getOrPut(accent) { ColorStateList.valueOf(accent) }
        AvatarLoader.load(id, label, holder.avatar, avatarPx)
        return row
    }
}
