package org.unichat.app

fun isGroupId(id: String): Boolean = id.endsWith("@g.us") || id.startsWith("tg:-")

/** True for a phone-number JID — the only id kind that holds a real phone
 *  number. A contact's @lid alias and a group's @g.us id do not. */
fun isPhoneId(id: String): Boolean = id.endsWith("@s.whatsapp.net")

/** Renders a phone JID as "+<phone number>" — the unknown-contact fallback.
 *  Any other id (a @lid alias, a group) has no phone number to show, so it is
 *  returned unchanged: prefixing '+' to a LID rendered it as a plausible-looking
 *  but entirely fake phone number in the chat title. */
fun phoneLabel(id: String): String =
    if (isPhoneId(id)) "+" + id.substringBefore("@") else id

fun ChatRow.displayLabel(): String =
    if (name == id && id.contains("@")) phoneLabel(id) else name

fun senderLabel(names: Map<String, String>, senderId: String, senderName: String): String =
    names[senderId]?.takeIf { it.isNotEmpty() } ?: senderName.ifEmpty { phoneLabel(senderId) }

// An @mention as it travels on the wire: the mentioned user's bare id digits.
// Since WhatsApp moved groups to LIDs those digits are a 15-digit @lid, which
// reads as pure noise in a bubble. Anchored so an email/handle ("a@1234567")
// is left alone.
private val MENTION = Regex("(?<![A-Za-z0-9])@(\\d{7,})")

fun hasMention(text: String): Boolean = text.contains('@') && MENTION.containsMatchIn(text)

fun resolveMentions(text: String, lookup: (String) -> String?): String {
    if (!text.contains('@')) return text
    return MENTION.replace(text) { m ->
        val id = m.groupValues[1]
        val name = lookup("$id@lid")?.takeIf { it.isNotEmpty() }
            ?: lookup("$id@s.whatsapp.net")?.takeIf { it.isNotEmpty() }
        if (name != null) "@$name" else m.value
    }
}

fun resolveMentions(text: String, names: Map<String, String>): String =
    if (names.isEmpty()) text else resolveMentions(text) { names[it] }

fun selfProtocol(ctx: android.content.Context, chatId: String): String = when {
    chatId.isEmpty() -> ""
    Tg.hasSession() && chatId == Tg.selfId() -> ctx.getString(R.string.telegram)
    chatId == Bridge.selfId() -> ctx.getString(R.string.whatsapp)
    else -> ""
}

fun ChatRow.displayLabelWithProto(ctx: android.content.Context): String {
    val proto = selfProtocol(ctx, id)
    return if (proto.isEmpty()) displayLabel() else "${displayLabel()} ($proto)"
}

fun selfPickerLabel(ctx: android.content.Context, chatId: String): String =
    ctx.getString(R.string.you_proto, selfProtocol(ctx, chatId))
