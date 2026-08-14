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

/**
 * Per-chat incoming-message notifications, grouped under a summary. Each chat
 * gets one MessagingStyle notification that accumulates its recent messages.
 */
object Notifications {

    private const val CHANNEL_MESSAGES = "messages"
    private const val GROUP = "org.unichat.app.MESSAGES"
    private const val SUMMARY_ID = 100
    // per-chat notifications are distinguished by tag (the chatId), so a fixed
    // id is fine and hashCode collisions between chats can't merge them
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
        // whose name this notification's avatar belongs to (derived once, not
        // re-derived per line)
        val iconOwner = if (isGroup) senderName else chatName
        synchronized(lines) {
            for (line in lines) {
                val person = Person.Builder().setName(line.sender)
                if (senderIcon != null && line.sender == iconOwner) person.setIcon(senderIcon)
                style.addMessage(line.text, line.time, person.build())
            }
        }

        val contentIntent = chatContentIntent(context, chatId, chatName)

        // when the user swipes the notification away, keep our history in sync.
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

    /**
     * Single owner of the "a group summary exists iff two or more chats have a
     * live notification" rule — posting it, and dropping it so it never lingers
     * as an empty header. The count comes from the notifications actually on
     * screen (not our in-process line history, which can disagree after the
     * user taps the summary itself).
     */
    private fun syncSummary(context: Context, manager: NotificationManager) {
        if (liveChatNotifications(manager).size >= 2) {
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

    /**
     * Tags of the per-chat message notifications currently in the shade. Read
     * from the system rather than from [history] so it stays correct across a
     * process restart (posted notifications outlive us) and after the user
     * dismisses one without our delete intent running.
     */
    private fun liveChatNotifications(manager: NotificationManager): List<String> =
        try {
            manager.activeNotifications
                .filter { it.id == MSG_ID }
                .mapNotNull { it.tag }
        } catch (e: Exception) {
            // activeNotifications can throw if the process has no listener
            // access yet; fall back to what we remember posting
            history.keys.toList()
        }

    /**
     * PendingIntent that opens a chat from a notification: a ChatActivity
     * deep-link with MainActivity synthesized underneath, so the chat opens
     * from any app state and Back returns to the chat list. The unique
     * per-chat action keeps extras from being coalesced across chats, and the
     * chatId-derived request code keeps the PendingIntents distinct — every
     * notification (message or media) must use this same scheme so they can't
     * collide. chatName is optional (resolved from the DB when absent).
     */
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

    /**
     * Moves a chat's notification state to a new id after the LID→phone merge
     * re-keyed it. Without this the posted notification kept the dead tag, so
     * cancelling by the new id was a no-op (it stayed in the shade) and tapping
     * it opened a chat whose rows had moved — a permanently blank screen.
     */
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

    // The user swiped a notification away: forget its accumulated lines so they
    // don't reappear, and drop the summary if it is no longer needed.
    fun onDismissed(context: Context, chatId: String) {
        history.remove(chatId)
        syncSummary(context, context.getSystemService(NotificationManager::class.java))
    }

    // Cancels only the message notifications this object posted (not the
    // foreground-service or media-playback notifications). Enumerated from the
    // shade, so notifications a PREVIOUS process posted are cancelled too —
    // logging out after a process restart used to leave them behind, still
    // deep-linking into chats whose rows had just been deleted.
    fun cancelAllMessages(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (tag in liveChatNotifications(manager)) manager.cancel(tag, MSG_ID)
        manager.cancel(SUMMARY_ID)
        history.clear()
    }
}
