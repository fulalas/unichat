package org.unichat.app

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import androidx.core.content.ContextCompat

object Ticks {

    private val cache = HashMap<Long, Drawable>()

    private fun tick(context: Context, read: Boolean, h: Int, readTint: Int?): Drawable? {
        val tint = if (read) readTint ?: context.themeColor(R.attr.chatAccent)
        else context.getColor(R.color.text_secondary)
        // pack tint (32b) | h (15b) | read (1b) into a Long with no overlap
        val key = ((tint.toLong() and 0xFFFFFFFFL) shl 16) or
            ((h.toLong() and 0x7FFF) shl 1) or (if (read) 1L else 0L)
        cache[key]?.let { return it }
        val res = if (read) R.drawable.ic_check_double else R.drawable.ic_check_single
        val d = ContextCompat.getDrawable(context, res)?.mutate() ?: return null
        d.setTint(tint)
        val w = (h * d.intrinsicWidth.toFloat() / d.intrinsicHeight).toInt().coerceAtLeast(1)
        d.setBounds(0, 0, w, h)
        cache[key] = d
        return d
    }

    fun timeWithTick(
        context: Context,
        time: CharSequence,
        read: Boolean,
        textSizePx: Float,
        tickFirst: Boolean,
        readTint: Int? = null,
    ): CharSequence {
        val h = (textSizePx * 0.9f).toInt().coerceAtLeast(1)
        val d = tick(context, read, h, readTint) ?: return time

        val sb = SpannableStringBuilder()
        if (tickFirst) {
            appendTick(sb, d)
            sb.append(" ").append(time)
        } else {
            sb.append(time).append(" ")
            appendTick(sb, d)
        }
        return sb
    }

    private fun appendTick(sb: SpannableStringBuilder, d: android.graphics.drawable.Drawable) {
        val start = sb.length
        sb.append(" ")
        sb.setSpan(ImageSpan(d, ImageSpan.ALIGN_CENTER), start, start + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
