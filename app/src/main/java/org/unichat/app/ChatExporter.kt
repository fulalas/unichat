package org.unichat.app

import android.content.Context
import android.net.Uri
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatExporter {

    fun write(context: Context, db: Db, chatId: String, uri: Uri, messages: List<MessageRow>) {
        val names = db.contactNames()
        val chatName = db.displayName(chatId)
        val isGroup = isGroupId(chatId)
        val you = context.getString(R.string.you)
        val time = SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.US)
        // use{} on the stream itself: wrapping it first and only then taking
        // ownership leaked the provider's fd if the writer chain's construction
        // threw (e.g. OOM on the buffer)
        (context.contentResolver.openOutputStream(uri) ?: throw IOException("no stream")).use { out ->
            BufferedWriter(OutputStreamWriter(out)).use { w ->
                for (m in messages) {
                    w.write(line(context, m, time, you, chatName, isGroup, names))
                    w.newLine()
                }
            }
        }
    }

    private fun line(
        context: Context, m: MessageRow, time: SimpleDateFormat, you: String,
        chatName: String, isGroup: Boolean, names: Map<String, String>,
    ): String {
        val stamp = time.format(Date(m.timeSent * 1000))
        val sender = when {
            m.fromMe -> you
            !isGroup -> chatName
            else -> senderLabel(names, m.senderId, m.senderName)
        }
        val detail = when (m.msgType) {
            "audio" -> m.text
            "location" -> m.coordinates()
            else -> ""
        }
        val body =
            previewLabel(context, m.msgType, resolveMentions(m.text, names), emoji = true, detail = detail)
        val edited = if (m.edited) " " + context.getString(R.string.export_edited) else ""
        return "$stamp - $sender: $body$edited"
    }
}
