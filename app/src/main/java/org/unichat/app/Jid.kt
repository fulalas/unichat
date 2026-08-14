package org.unichat.app

// JID/display helpers — the single owners of the "@g.us" group-server literal
// and of the "how to label an id nobody has a name for" fallback rules.
// (Db.kt's SQL keeps its own '%@g.us' LIKE; it must match isGroupId.)

/** True for a group chat id: WhatsApp group JIDs live on the g.us server;
 *  Telegram groups/supergroups/channels have negative raw ids. */
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

/** Display name of a chat, falling back to the phone number when the chat has
 *  no resolved name (its name is still its own id). */
fun ChatRow.displayLabel(): String =
    if (name == id && id.contains("@")) phoneLabel(id) else name

/** Display name of a message sender: contact name if known, else the sender's
 *  WhatsApp push name, else the phone-number fallback.
 *  A stored name is routinely the empty string (a contact row with no push name,
 *  a Telegram user with no first/last name), which is "no name" here — without
 *  the emptiness check it satisfies the Elvis and renders a blank sender. */
fun senderLabel(names: Map<String, String>, senderId: String, senderName: String): String =
    names[senderId]?.takeIf { it.isNotEmpty() } ?: senderName.ifEmpty { phoneLabel(senderId) }

// An @mention as it travels on the wire: the mentioned user's bare id digits.
// Since WhatsApp moved groups to LIDs those digits are a 15-digit @lid, which
// reads as pure noise in a bubble. Anchored so an email/handle ("a@1234567")
// is left alone.
private val MENTION = Regex("(?<![A-Za-z0-9])@(\\d{7,})")

/** True if the text carries at least one @mention — the cheap gate callers use
 *  before paying for the name lookups. */
fun hasMention(text: String): Boolean = text.contains('@') && MENTION.containsMatchIn(text)

/** Renders @mentions in a message body with the mentioned person's name.
 *  A mentioned user is keyed by their LID or their phone JID, so `lookup` is
 *  asked for both. Ids nobody has a name for are left exactly as they were sent.
 *  An empty result counts as "no name": a nameless @lid row would otherwise stop
 *  the phone-JID lookup and render a bare "@". */
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

/**
 * "WhatsApp" / "Telegram" when [chatId] is YOUR OWN chat on that account, ""
 * otherwise. You have one self-chat per linked account, both carrying your own
 * name, so every list that can show both has to say which is which.
 */
fun selfProtocol(ctx: android.content.Context, chatId: String): String = when {
    chatId.isEmpty() -> ""
    Tg.hasSession() && chatId == Tg.selfId() -> ctx.getString(R.string.telegram)
    chatId == Bridge.selfId() -> ctx.getString(R.string.whatsapp)
    else -> ""
}

/** Chat-list/title name: "Rafael (Telegram)" for your own chat. */
fun ChatRow.displayLabelWithProto(ctx: android.content.Context): String {
    val proto = selfProtocol(ctx, id)
    return if (proto.isEmpty()) displayLabel() else "${displayLabel()} ($proto)"
}

/** Target-picker name for your own chat: "You (Telegram)". */
fun selfPickerLabel(ctx: android.content.Context, chatId: String): String =
    ctx.getString(R.string.you_proto, selfProtocol(ctx, chatId))
