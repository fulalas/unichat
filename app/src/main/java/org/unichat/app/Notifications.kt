package org.unichat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

object Notifications {

    private const val CHANNEL_MESSAGES = "messages"
    private const val GROUP = "org.unichat.app.MESSAGES"
    private const val SUMMARY_ID = 100
    private const val MSG_ID = 1
    private const val MAX_LINES = 6

    private data class Line(val sender: String, val text: String, val time: Long)

    private val history = ConcurrentHashMap<String, ArrayDeque<Line>>()

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_MESSAGES) != null) return
        val channel = NotificationChannel(
            CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH
        )
        channel.enableVibration(true)
        manager.createNotificationChannel(channel)
    }

    fun notifyMessage(
        context: Context,
        chatId: String,
        chatName: String,
        senderName: String,
        preview: String,
        isGroup: Boolean,
        timeSent: Long,
        chatAvatarPath: String,
        senderAvatarPath: String,
    ) {
        ensureChannel(context)
        // An in-place edit re-delivers with timeSent 0 (the bridge's "keep the
        // original order" sentinel); using it as a wall clock would stamp the
        // notification 1 Jan 1970 and sort it to the bottom of the shade.
        val whenMs = if (timeSent > 0) timeSent * 1000 else System.currentTimeMillis()
        // computeIfAbsent, not getOrPut: the latter is a get-then-put pair, and
        // history is also mutated from other threads (cancel/onDismissed/rekey
        // run elsewhere while this runs on the bridge's notify executor), so two
        // concurrent first-messages for a chat each built a deque and one
        // message's lines were silently dropped.
        val lines = history.computeIfAbsent(chatId) { ArrayDeque() }
        synchronized(lines) {
            lines.addLast(Line(if (isGroup) senderName else chatName, preview, whenMs))
            while (lines.size > MAX_LINES) lines.removeFirst()
        }

        val self = Person.Builder().setName(context.getString(R.string.you)).build()
        val style = Notification.MessagingStyle(self)
        if (isGroup) {
            style.conversationTitle = chatName
            style.isGroupConversation = true
        }
        val senderIcon = if (senderAvatarPath.isNotEmpty())
            Icon.createWithFilePath(senderAvatarPath) else null
        val iconOwner = if (isGroup) senderName else chatName
        synchronized(lines) {
            for (line in lines) {
                val person = Person.Builder().setName(line.sender)
                if (senderIcon != null && line.sender == iconOwner) person.setIcon(senderIcon)
                style.addMessage(line.text, line.time, person.build())
            }
        }

        val contentIntent = chatContentIntent(context, chatId, chatName)

        // Unique data per chat so PendingIntents never collide by request code.
        val deleteIntent = PendingIntent.getService(
            context, 0,
            Intent(context, WmService::class.java)
                .setAction(WmService.ACTION_NOTIF_DISMISSED)
                .setData(android.net.Uri.parse("unichat:dismiss:$chatId"))
                .putExtra("chatId", chatId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = Notification.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setGroup(GROUP)
            .setWhen(whenMs)
        if (chatAvatarPath.isNotEmpty()) {
            builder.setLargeIcon(Icon.createWithFilePath(chatAvatarPath))
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(chatId, MSG_ID, builder.build())
        syncSummary(context, manager)
    }

    // liveCount is overridable because NotificationManager.cancel is a one-way
    // binder call applied asynchronously: re-reading activeNotifications right
    // after cancelling still lists the cancelled children, which kept an empty
    // summary alive. Such callers pass the survivors they computed themselves.
    private fun syncSummary(
        context: Context,
        manager: NotificationManager,
        liveCount: Int = liveChatNotifications(manager).size,
    ) {
        if (liveCount >= 2) {
            val summary = Notification.Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 0, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            manager.notify(SUMMARY_ID, summary)
        } else {
            manager.cancel(SUMMARY_ID)
        }
    }

    // Read from the system rather than from [history]: posted notifications
    // outlive the process, and the user can dismiss one without our delete
    // intent running.
    private fun liveChatNotifications(manager: NotificationManager): List<String> =
        try {
            manager.activeNotifications
                .filter { it.id == MSG_ID }
                .mapNotNull { it.tag }
        } catch (e: Exception) {
            // activeNotifications can throw if the process has no listener
            // access yet
            history.keys.toList()
        }

    fun chatContentIntent(context: Context, chatId: String, chatName: String? = null): PendingIntent {
        val openIntent = Intent(context, ChatActivity::class.java)
            .putExtra("chatId", chatId)
            .setAction("open_" + chatId)
        if (chatName != null) openIntent.putExtra("chatName", chatName)
        return TaskStackBuilder.create(context)
            .addNextIntentWithParentStack(openIntent)
            .getPendingIntent(
                chatId.hashCode(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }

    // After the LID→phone merge re-keys a chat, the posted notification kept
    // the dead tag: cancelling by the new id was a no-op (it stayed in the
    // shade) and tapping it opened a chat whose rows had moved — a permanently
    // blank screen.
    fun rekey(context: Context, fromId: String, toId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Cancelled unconditionally: [history] is in-process state while the
        // posted notification outlives the process, so no lines for fromId is
        // no evidence that the old tag is gone from the shade — after a restart
        // (or after onDismissed dropped the lines) it is exactly the stale
        // notification this function exists to remove.
        manager.cancel(fromId, MSG_ID)
        val moved = history.remove(fromId)?.let { synchronized(it) { it.toList() } }
        if (moved != null) {
            val target = history.computeIfAbsent(toId) { ArrayDeque() }
            synchronized(target) {
                // merged in time order rather than assigned: messages may
                // already have arrived under the new id, and overwriting threw
                // them away (appending would show them out of order)
                val merged = (target + moved).sortedBy { it.time }
                target.clear()
                for (line in merged) target.addLast(line)
                while (target.size > MAX_LINES) target.removeFirst()
            }
        }
        syncSummary(context, manager)
    }

    fun cancel(context: Context, chatId: String) {
        history.remove(chatId)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(chatId, MSG_ID)
        syncSummary(context, manager)
    }

    fun onDismissed(context: Context, chatId: String) {
        history.remove(chatId)
        syncSummary(context, context.getSystemService(NotificationManager::class.java))
    }

    // Enumerated from the shade so notifications a PREVIOUS process posted are
    // cancelled too — logging out after a process restart used to leave them
    // behind, still deep-linking into chats whose rows had just been deleted.
    // The predicate is what keeps one account's unlink from clearing another
    // account's alerts.
    fun cancelMessagesFor(context: Context, owns: (String) -> Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val (mine, others) = liveChatNotifications(manager).partition(owns)
        for (tag in mine) manager.cancel(tag, MSG_ID)
        // Every owned entry, not just the ones with a live notification:
        // tapping the auto-cancel summary removes the children without firing
        // their delete intent, so history outlives the shade. Relinking a
        // different account that shares a bare JID then had notifyMessage
        // reuse the stale deque and replay the previous account's lines.
        history.keys.removeAll { owns(it) }
        syncSummary(context, manager, others.size)
    }
}
