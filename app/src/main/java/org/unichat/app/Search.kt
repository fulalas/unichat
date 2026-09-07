package org.unichat.app

import java.text.Normalizer

object Search {

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
