package org.unichat.app

fun isGroupId(id: String): Boolean =
    id.endsWith("@g.us") || id.startsWith("tg:-") || isSgGroupId(id)

// A Signal 1:1 chat is keyed by the other party's ACI — or by their PNI, marked
// with a "pni:" tag, when contact discovery did not return an ACI. Either way it
// is a UUID; a group is keyed by a base64 group identifier, which is not.
fun isSgGroupId(id: String): Boolean {
    if (!id.startsWith(Signal.PREFIX)) return false
    // Both spellings: rows written before the prefix was corrected use
    // lowercase, and only one can match at the front.
    val bare = id.removePrefix(Signal.PREFIX)
        .removePrefix(Signal.PNI_PREFIX)
        .removePrefix("pni:")
    return bare.length != 36
}

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

/** A mention as it was composed: the `@Name` in the text, and who it means. */
class Mention(val label: String, val id: String)

class MentionHit(val start: Int, val end: Int, val id: String)

/**
 * Where the mentions are in a composed message. The composer writes the
 * member's name, not their id, so a draft that outlived the screen still
 * resolves — longest name first, on word boundaries ("mail@Bob" is an address
 * and "@Bobby" is someone else), and no name claimed inside another's.
 *
 * Matched through [Search], like every other name match in the app: "@joao"
 * mentions João. Folding is one character in, one out, so an offset into the
 * folded name still points at the same character of the raw text.
 */
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

/**
 * The WhatsApp wire form: the body carries the mentioned person's own digits
 * (its clients draw the chip by matching them against MentionedJID), so the
 * composed names are spliced out — back to front, or every offset after the
 * first splice would be wrong.
 */
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
        // No isLinked() check: an unlinked account has no self id, so a
        // non-empty chat id cannot match one — and asking would cost a bridge
        // call per chat row, which is what this is called from.
        if (chatId == account.selfId()) return account.label(ctx)
    }
    return ""
}

/** True for a note to self on any protocol. */
fun isSelfChat(ctx: android.content.Context, chatId: String): Boolean =
    selfProtocol(ctx, chatId).isNotEmpty()

fun ChatRow.displayLabelWithProto(ctx: android.content.Context): String {
    val proto = selfProtocol(ctx, id)
    return if (proto.isEmpty()) displayLabel() else "${displayLabel()} ($proto)"
}

fun selfPickerLabel(ctx: android.content.Context, chatId: String): String =
    ctx.getString(R.string.you_proto, selfProtocol(ctx, chatId))
