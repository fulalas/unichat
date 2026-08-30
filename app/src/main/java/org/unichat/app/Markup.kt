package org.unichat.app

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan

/**
 * WhatsApp has no formatting entities: `*bold*` and `_italic_` are carried
 * literally in the message body and every client renders them itself. So the
 * markers are what we store, and Telegram's entities are translated to and
 * from them at its own edge (see Tg.formattedText).
 *
 * A marker only opens when the character before it is not part of a word and
 * the one after it is not a space, and only closes under the mirror rule —
 * WhatsApp's own test, and the reason `snake_case`, `a*b`, `2 * 3` and URLs
 * with underscores stay literal instead of eating half the message.
 */
object Markup {
    class Mark(val start: Int, val end: Int, val bold: Boolean)

    private const val BOLD = '*'
    private const val ITALIC = '_'

    fun parse(text: String): Pair<String, List<Mark>> {
        if (!text.contains(BOLD) && !text.contains(ITALIC)) return text to emptyList()
        val out = StringBuilder()
        val marks = ArrayList<Mark>()
        scan(text, 0, text.length, out, marks)
        // TDLib wants the entities in reading order, outer before inner; scan
        // closes a run only after walking what is nested inside it
        marks.sortWith(compareBy({ it.start }, { it.start - it.end }))
        return out.toString() to marks
    }

    fun render(text: String): CharSequence {
        val (plain, marks) = parse(text)
        if (marks.isEmpty()) return text
        val sp = SpannableStringBuilder(plain)
        for (m in marks) {
            sp.setSpan(
                StyleSpan(if (m.bold) Typeface.BOLD else Typeface.ITALIC),
                m.start, m.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sp
    }

    fun withMarkers(plain: String, marks: List<Mark>): String {
        // Telegram allows runs that cross (bold [0,5) with italic [3,8)); their
        // markers fail closeOf's mirror rule and arrived as literal symbols
        val usable = ArrayList<Mark>()
        for (m in marks) {
            if (!readableBack(plain, m)) continue
            if (usable.any { crosses(it, m) }) continue
            usable.add(m)
        }
        if (usable.isEmpty()) return plain
        val at = Array(plain.length + 1) { StringBuilder() }
        for (m in usable) {
            val marker = if (m.bold) BOLD else ITALIC
            at[m.start].append(marker)
            // a run ending where another does must close from the inside out
            at[m.end].insert(0, marker)
        }
        val out = StringBuilder()
        for (i in plain.indices) {
            out.append(at[i])
            out.append(plain[i])
        }
        out.append(at[plain.length])
        return out.toString()
    }

    // Markers cannot express a run that starts inside a word or is padded with
    // spaces — parse would refuse to open one there — and Telegram entities can
    // do both. Marking it anyway showed the asterisks instead of the styling.
    private fun readableBack(plain: String, m: Mark): Boolean {
        if (m.start < 0 || m.end > plain.length || m.end <= m.start) return false
        if (plain[m.start].isWhitespace() || plain[m.end - 1].isWhitespace()) return false
        if (m.start > 0 && plain[m.start - 1].isLetterOrDigit()) return false
        return m.end == plain.length || !plain[m.end].isLetterOrDigit()
    }

    private fun crosses(a: Mark, b: Mark): Boolean =
        (a.start < b.start && b.start < a.end && a.end < b.end) ||
            (b.start < a.start && a.start < b.end && b.end < a.end)

    private fun scan(
        src: String, from: Int, to: Int, out: StringBuilder, marks: MutableList<Mark>,
    ) {
        var i = from
        while (i < to) {
            val c = src[i]
            if ((c == BOLD || c == ITALIC) && opens(src, i, to)) {
                val close = closeOf(src, i, to, c)
                if (close > 0) {
                    val start = out.length
                    scan(src, i + 1, close, out, marks)
                    marks.add(Mark(start, out.length, c == BOLD))
                    i = close + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
    }

    private fun opens(src: String, i: Int, to: Int): Boolean {
        if (i > 0 && src[i - 1].isLetterOrDigit()) return false
        val next = if (i + 1 < to) src[i + 1] else return false
        return !next.isWhitespace() && next != src[i]
    }

    private fun closeOf(src: String, open: Int, to: Int, marker: Char): Int {
        for (j in open + 2 until to) {
            if (src[j] != marker || src[j - 1].isWhitespace()) continue
            if (j + 1 < src.length && src[j + 1].isLetterOrDigit()) continue
            return j
        }
        return -1
    }
}
