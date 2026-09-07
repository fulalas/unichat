package org.unichat.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class ChatListAdapter(
    private val onClick: (ChatRow) -> Unit,
    private val onAvatarClick: (ChatRow) -> Unit,
    private val onSelectionChanged: () -> Unit = {},
    private val onDragArm: () -> Unit = {},
) : RecyclerView.Adapter<ChatListAdapter.Holder>(), DragSelectAdapter {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatRow>() {
            override fun areItemsTheSame(a: ChatRow, b: ChatRow) = a.id == b.id
            override fun areContentsTheSame(a: ChatRow, b: ChatRow) = a == b
        }

        private val PREVIEW_WS = Regex("\\s*[\\r\\n]+\\s*")

        private val selectedTints = HashMap<Int, android.graphics.drawable.ColorDrawable>()

        private fun selectedTint(accent: Int) = selectedTints.getOrPut(accent) {
            android.graphics.drawable.ColorDrawable((0x33 shl 24) or (accent and 0xFFFFFF))
        }
    }

    private val differ = AsyncListDiffer(this, DIFF)
    private val chats: List<ChatRow> get() = differ.currentList

    fun submit(newChats: List<ChatRow>, commitCallback: Runnable? = null) {
        differ.submitList(newChats) {
            chatsById = null
            commitCallback?.run()
        }
    }

    private val selection = Selection<ChatRow>(
        rows = { chats }, idOf = { it.id },
        notifyAt = { notifyItemChanged(it) }, onChanged = { onSelectionChanged() },
        canSelect = { !PhoneBook.isPhoneEntry(it.id) },
    )

    val selectionMode: Boolean get() = selection.active

    fun selectedCount(): Int = selection.count

    private var chatsById: Map<String, ChatRow>? = null

    fun selectedChats(): List<ChatRow> {
        val byId = chatsById ?: chats.associateBy { it.id }.also { chatsById = it }
        return selection.values.map { byId[it.id] ?: it }
    }

    fun clearSelection() = selection.clear()

    override fun setSelectedAt(pos: Int, sel: Boolean) = selection.setSelectedAt(pos, sel)

    override fun commitDragSelection() = selection.commitDragSelection()

    override fun snapshotSelection(): Set<String> = selection.snapshotSelection()

    override fun idAt(pos: Int): String = selection.idAt(pos)

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.avatar)
        val name: TextView = view.findViewById(R.id.chatName)
        val lastMessage: TextView = view.findViewById(R.id.lastMessage)
        val timestamp: TextView = view.findViewById(R.id.timestamp)
        val unreadBadge: TextView = view.findViewById(R.id.unreadBadge)
        val muteIcon: ImageView = view.findViewById(R.id.muteIcon)
        val avatarRing: View = view.findViewById(R.id.avatarRing)
        val onlineDot: View = view.findViewById(R.id.onlineDot)
        var current: ChatRow? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        val holder = Holder(view)
        holder.avatar.clipToOutline = true
        holder.avatar.outlineProvider = ViewOutlineProvider.BACKGROUND
        holder.itemView.setOnClickListener {
            val chat = holder.current ?: return@setOnClickListener
            if (selectionMode) selection.toggle(chat) else onClick(chat)
        }
        holder.itemView.setOnLongClickListener {
            holder.current?.let { if (selection.start(it)) onDragArm() }
            true
        }
        holder.avatar.setOnClickListener {
            val chat = holder.current ?: return@setOnClickListener
            if (selectionMode) selection.toggle(chat) else onAvatarClick(chat)
        }
        return holder
    }

    override fun getItemCount(): Int = chats.size

    fun rowAt(position: Int): ChatRow? = chats.getOrNull(position)

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = chats[position]
        val name = row.displayLabelWithProto(holder.itemView.context)
        val chat = if (name == row.name) row else row.copy(name = name)
        holder.current = chat
        holder.name.text = name
        val context = holder.itemView.context
        val accent = context.protocolAccent(chat.id)
        val transient = when (chat.transientState) {
            "typing" -> R.string.typing
            "recording" -> R.string.recording_voice
            else -> 0
        }
        if (transient != 0) {
            holder.lastMessage.text = context.getString(transient)
            holder.lastMessage.setTextColor(accent)
        } else {
            val preview = if (chat.lastText.indexOf('\n') < 0 && chat.lastText.indexOf('\r') < 0)
                chat.lastText else chat.lastText.replace(PREVIEW_WS, " ")
            holder.lastMessage.text = Markup.render(preview)
            holder.lastMessage.setTextColor(context.getColor(R.color.text_secondary))
        }
        val time = TimeFormat.compact(context, chat.lastTime)
        holder.timestamp.text = if (chat.lastFromMe) {
            Ticks.timeWithTick(
                context, time, chat.lastRead, holder.timestamp.textSize,
                tickFirst = true, readTint = accent,
                failed = chat.lastFailed, pending = chat.lastPending,
            )
        } else {
            time
        }
        holder.muteIcon.visibility = if (chat.muted) View.VISIBLE else View.GONE
        if (chat.unread > 0) {
            holder.unreadBadge.visibility = View.VISIBLE
            holder.unreadBadge.backgroundTintList =
                android.content.res.ColorStateList.valueOf(accent)
            holder.unreadBadge.text =
                if (chat.unread > 99) context.getString(R.string.unread_overflow)
                else chat.unread.toString()
        } else {
            holder.unreadBadge.visibility = View.GONE
        }
        holder.avatarRing.backgroundTintList =
            android.content.res.ColorStateList.valueOf(accent)
        holder.itemView.foreground = if (chat.id in selection) selectedTint(accent) else null
        holder.onlineDot.visibility = if (chat.online) View.VISIBLE else View.GONE
        val real = !PhoneBook.isPhoneEntry(chat.id)
        if (real && !chat.isGroup) Bridge.subscribePresence(chat.id)
        if (real) AvatarLoader.load(chat.id, name, holder.avatar, AvatarLoader.dp(holder.avatar, 44))
        else holder.avatar.setImageBitmap(
            AvatarLoader.initials(name, AvatarLoader.dp(holder.avatar, 44))
        )
    }
}
