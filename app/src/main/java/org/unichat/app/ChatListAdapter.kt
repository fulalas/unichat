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
    private val onLongClick: (ChatRow) -> Unit,
) : RecyclerView.Adapter<ChatListAdapter.Holder>() {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatRow>() {
            override fun areItemsTheSame(a: ChatRow, b: ChatRow) = a.id == b.id
            override fun areContentsTheSame(a: ChatRow, b: ChatRow) = a == b
        }

        private val PREVIEW_WS = Regex("\\s*[\\r\\n]+\\s*")
    }

    private val differ = AsyncListDiffer(this, DIFF)
    private val chats: List<ChatRow> get() = differ.currentList

    fun submit(newChats: List<ChatRow>, commitCallback: Runnable? = null) {
        differ.submitList(newChats, commitCallback)
    }

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
        // set once here, not per bind: they dispatch off holder.current so a
        // recycled row acts on the chat it currently shows
        holder.itemView.setOnClickListener { holder.current?.let(onClick) }
        holder.itemView.setOnLongClickListener {
            holder.current?.let(onLongClick)
            true
        }
        holder.avatar.setOnClickListener { holder.current?.let(onAvatarClick) }
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
        val transient = when (chat.transientState) {
            "typing" -> R.string.typing
            "recording" -> R.string.recording_voice
            else -> 0
        }
        if (transient != 0) {
            holder.lastMessage.text = context.getString(transient)
            holder.lastMessage.setTextColor(context.protocolAccent(chat.id))
        } else {
            holder.lastMessage.text = Markup.render(chat.lastText.replace(PREVIEW_WS, " "))
            holder.lastMessage.setTextColor(context.getColor(R.color.text_secondary))
        }
        val time = TimeFormat.compact(context, chat.lastTime)
        holder.timestamp.text = if (chat.lastFromMe) {
            Ticks.timeWithTick(
                context, time, chat.lastRead, holder.timestamp.textSize,
                tickFirst = true, readTint = context.protocolAccent(chat.id),
                failed = chat.lastFailed,
            )
        } else {
            time
        }
        holder.muteIcon.visibility = if (chat.muted) View.VISIBLE else View.GONE
        if (chat.unread > 0) {
            holder.unreadBadge.visibility = View.VISIBLE
            holder.unreadBadge.backgroundTintList =
                android.content.res.ColorStateList.valueOf(context.protocolAccent(chat.id))
            holder.unreadBadge.text =
                if (chat.unread > 99) context.getString(R.string.unread_overflow)
                else chat.unread.toString()
        } else {
            holder.unreadBadge.visibility = View.GONE
        }
        holder.avatarRing.backgroundTintList =
            android.content.res.ColorStateList.valueOf(context.protocolAccent(chat.id))
        holder.onlineDot.visibility = if (chat.online) View.VISIBLE else View.GONE
        // WhatsApp only reports presence for contacts it has been asked about,
        // so the ask happens per visible row (Bridge subscribes once per
        // contact); Telegram pushes it unprompted. Groups have no presence.
        // An address-book search result is not an account yet — its id is a
        // local placeholder. Asking the server about it sent a real presence
        // subscription and an avatar request per shown row, for a "user" that
        // does not exist as far as WhatsApp is concerned.
        val real = !PhoneBook.isPhoneEntry(chat.id)
        if (real && !chat.isGroup) Bridge.subscribePresence(chat.id)
        if (real) AvatarLoader.load(chat.id, name, holder.avatar, AvatarLoader.dp(holder.avatar, 44))
        else holder.avatar.setImageBitmap(
            AvatarLoader.initials(name, AvatarLoader.dp(holder.avatar, 44))
        )
    }
}
