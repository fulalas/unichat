package org.unichat.app

fun isGroupId(id: String): Boolean =
    id.endsWith("@g.us") || id.startsWith("tg:-") || isSgGroupId(id)

fun isSgGroupId(id: String): Boolean {
    if (!id.startsWith(Signal.PREFIX)) return false
    val bare = id.removePrefix(Signal.PREFIX)
        .removePrefix(Signal.PNI_PREFIX)
        .removePrefix("pni:")
    return bare.length != 36
}

fun isPhoneId(id: String): Boolean = id.endsWith("@s.whatsapp.net")

fun phoneLabel(id: String): String =
    if (isPhoneId(id)) "+" + id.substringBefore("@") else id

fun ChatRow.displayLabel(): String =
    if (name == id && id.contains("@")) phoneLabel(id) else name

fun senderLabel(names: Map<String, String>, senderId: String, senderName: String): String =
    names[senderId]?.takeIf { it.isNotEmpty() } ?: senderName.ifEmpty { phoneLabel(senderId) }

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

class Mention(val label: String, val id: String)

class MentionHit(val start: Int, val end: Int, val id: String)

fun storedMentions(text: String, known: (String) -> Boolean): List<Mention> =
    MENTION.findAll(text).map { m ->
        val digits = m.groupValues[1]
        val lid = "$digits@lid"
        Mention("@$digits", if (known(lid)) lid else "$digits@s.whatsapp.net")
    }.distinctBy { it.id }.toList()

fun mentionHits(text: String, members: List<Mention>): List<MentionHit> {
    if (!text.contains('@')) return emptyList()
    val hits = ArrayList<MentionHit>()
    for (m in members.sortedByDescending { it.label.length }) {
        if (m.label.length < 2) continue
        val needle = Search.fold(m.label)
        var at = Search.indexOf(text, needle)
        while (at >= 0) {
            val end = at + needle.length
            val standsAlone = (at == 0 || !text[at - 1].isLetterOrDigit()) &&
                (end >= text.length || !text[end].isLetterOrDigit())
            if (standsAlone && hits.none { at < it.end && end > it.start }) {
                hits.add(MentionHit(at, end, m.id))
            }
            at = Search.indexOf(text, needle, at + 1)
        }
    }
    return hits.sortedBy { it.start }
}

fun waMentionText(text: String, members: List<Mention>): Pair<String, List<String>> {
    val hits = mentionHits(text, members)
    if (hits.isEmpty()) return text to emptyList()
    val out = StringBuilder(text)
    for (h in hits.sortedByDescending { it.start }) {
        out.replace(h.start, h.end, "@" + h.id.substringBefore("@"))
    }
    return out.toString() to hits.map { it.id }.distinct()
}

fun selfProtocol(ctx: android.content.Context, chatId: String): String {
    if (chatId.isEmpty()) return ""
    for (i in Accounts.ALL.indices) {
        val account = Accounts.ALL[i]
        if (chatId == account.selfId()) return account.label(ctx)
    }
    return ""
}

fun isSelfChat(ctx: android.content.Context, chatId: String): Boolean =
    selfProtocol(ctx, chatId).isNotEmpty()

fun ChatRow.displayLabelWithProto(ctx: android.content.Context): String {
    val proto = selfProtocol(ctx, id)
    return if (proto.isEmpty()) displayLabel() else "${displayLabel()} ($proto)"
}

fun displayNameWithProto(ctx: android.content.Context, chatId: String): String {
    val proto = selfProtocol(ctx, chatId)
    return Bridge.db.displayName(chatId).let { if (proto.isEmpty()) it else "$it ($proto)" }
}

fun selfPickerLabel(ctx: android.content.Context, chatId: String): String =
    ctx.getString(R.string.you_proto, selfProtocol(ctx, chatId))
