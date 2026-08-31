package org.unichat.app

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import androidx.core.content.ContextCompat

object Ticks {

    private const val SENT = 0L
    private const val READ = 1L
    private const val FAILED = 2L

    private val cache = HashMap<Long, Drawable>()

    private fun tick(context: Context, state: Long, h: Int, readTint: Int?): Drawable? {
        val tint = when (state) {
            FAILED -> context.getColor(R.color.send_failed)
            READ -> readTint ?: context.themeColor(R.attr.chatAccent)
            else -> context.getColor(R.color.text_secondary)
        }
        val key = ((tint.toLong() and 0xFFFFFFFFL) shl 17) or
            ((h.toLong() and 0x7FFF) shl 2) or state
        cache[key]?.let { return it }
        val res = when (state) {
            FAILED -> R.drawable.ic_send_failed
            READ -> R.drawable.ic_check_double
            else -> R.drawable.ic_check_single
        }
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
        failed: Boolean = false,
        pending: Boolean = false,
    ): CharSequence {
        // [failed] first: a message being retried carries [pending] too, and
        // yielding to it left the chat-list row bare for the whole retry window.
        // Unconfirmed means no mark at all — a tick would claim a delivery that
        // has not happened.
        if (!failed && pending) return time
        val h = (textSizePx * 0.9f).toInt().coerceAtLeast(1)
        val state = when {
            failed -> FAILED
            read -> READ
            else -> SENT
        }
        val d = tick(context, state, h, readTint) ?: return time

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
