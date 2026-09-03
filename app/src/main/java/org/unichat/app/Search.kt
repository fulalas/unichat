package org.unichat.app

import java.text.Normalizer

/**
 * Folding must stay strictly one character in, one character out, so an offset
 * into folded text still points at the same character of the raw text — the
 * message highlighter sets its spans on the raw string. A character whose
 * lower-case or decomposed form is longer than itself (Turkish 'İ' U+0130, the
 * 'ﬁ' ligature) keeps only its first character; shifting later offsets used to
 * push setSpan past the end of the Spannable.
 */
object Search {

    // Nothing above U+2000 carries marks this strips; below it are Latin
    // (including Extended Additional, i.e. Vietnamese), Greek and Cyrillic.
    private const val TABLE_SIZE = 0x2000

    private val table: CharArray by lazy {
        CharArray(TABLE_SIZE) { computeFold(it.toChar()) }
    }

    private fun computeFold(c: Char): Char {
        val lower = c.lowercaseChar()
        if (lower.code < 0x80) return lower
        val decomposed = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
        return decomposed.firstOrNull { !isMark(it) } ?: lower
    }

    private fun isMark(c: Char): Boolean = when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    fun fold(c: Char): Char = when {
        c.code < 0x80 -> c.lowercaseChar()
        c.code < TABLE_SIZE -> table[c.code]
        else -> c.lowercaseChar()
    }

    fun fold(text: String): String {
        val out = CharArray(text.length)
        for (i in text.indices) out[i] = fold(text[i])
        return String(out)
    }

    /**
     * [foldedNeedle] must already be through [fold]; [haystack] is raw and is
     * deliberately not folded up front, because the chat scan runs this over
     * thousands of messages per keystroke and almost every one fails on the
     * first character.
     */
    fun indexOf(haystack: String, foldedNeedle: String, from: Int = 0): Int {
        if (foldedNeedle.isEmpty()) return -1
        var i = from.coerceAtLeast(0)
        val last = haystack.length - foldedNeedle.length
        outer@ while (i <= last) {
            for (k in foldedNeedle.indices) {
                if (fold(haystack[i + k]) != foldedNeedle[k]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    fun contains(haystack: String, foldedNeedle: String): Boolean =
        indexOf(haystack, foldedNeedle) >= 0
}
