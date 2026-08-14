package org.unichat.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// MAIN-THREAD ONLY (like the SimpleDateFormat instances it holds): the
// formatters run per row bind, so the scratch Calendars below are reused
// instead of allocating two GregorianCalendars per call in the scroll path.
object TimeFormat {
    // Honors the device's "Use 24-hour format" setting AND the current locale
    // AND the current time zone; refreshClockFormat() re-reads all three
    // (BaseActivity calls it on every onStart, so a change in Settings takes
    // effect the next time a screen is shown). The locale is tracked because
    // these formatters used to be built once at object init: after a system or
    // per-app locale change the process is not necessarily restarted, so
    // weekday and month names kept rendering in the previous language for the
    // rest of its life. The zone is tracked for the same reason and is even
    // wider-reaching: a SimpleDateFormat captures TimeZone.getDefault() in its
    // internal Calendar at construction, and so do the scratch Calendars below,
    // so after the device zone changes (travel, auto time zone) every clock
    // time, date separator, Today/Yesterday decision and dayStamp() rollover
    // would stay in the old zone.
    private var use24h: Boolean? = null
    private var locale: Locale = Locale.getDefault()
    private var zoneId: String = TimeZone.getDefault().id
    private var timeFmt = SimpleDateFormat("h:mm a", locale)
    private var dayFmt = SimpleDateFormat("EEE", locale)
    private var dateFmt = SimpleDateFormat("dd/MM/yyyy", locale)

    /** Re-reads the system 12/24-hour preference, locale and time zone.
     *  Main thread only. */
    fun refreshClockFormat(context: Context) {
        val h24 = android.text.format.DateFormat.is24HourFormat(context)
        val loc = Locale.getDefault()
        val zone = TimeZone.getDefault().id
        if (use24h == h24 && locale == loc && zoneId == zone) return
        use24h = h24
        // a zone change invalidates the same objects a locale change does, plus
        // the scratch Calendars: both are re-created from the current defaults
        if (locale != loc || zoneId != zone) {
            locale = loc
            zoneId = zone
            dayFmt = SimpleDateFormat("EEE", loc)
            dateFmt = SimpleDateFormat("dd/MM/yyyy", loc)
            sepFmt = SimpleDateFormat("MMMM d", loc)
            sepYearFmt = SimpleDateFormat("MMMM d, yyyy", loc)
            calA = Calendar.getInstance()
            calB = Calendar.getInstance()
        }
        timeFmt = SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", loc)
    }

    private var calA = Calendar.getInstance()
    private var calB = Calendar.getInstance()

    private fun atSeconds(cal: Calendar, epochSeconds: Long): Calendar {
        cal.timeInMillis = epochSeconds * 1000
        return cal
    }

    private fun now(): Calendar = atSeconds(calB, System.currentTimeMillis() / 1000)

    fun clock(epochSeconds: Long): String = timeFmt.format(Date(epochSeconds * 1000))

    /** Formats a whole-second duration as "m:ss" (e.g. 0:07), the stored
     *  voice-note duration format. Negative inputs clamp to 0. */
    fun mmss(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Parses a "m:ss" duration string into whole seconds, or 0 if malformed. */
    fun parseSeconds(text: String): Int {
        val parts = text.split(":")
        if (parts.size != 2) return 0
        val m = parts[0].toIntOrNull() ?: return 0
        val s = parts[1].toIntOrNull() ?: return 0
        return m * 60 + s
    }

    /** Compact timestamp for the chat list: time today, weekday this week, date otherwise. */
    fun compact(context: Context, epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        val then = atSeconds(calA, epochSeconds)
        val days = daysBetween(then, now())
        return when (days) {
            0 -> clock(epochSeconds)
            1 -> context.getString(R.string.yesterday)
            // 2..6 only: a negative delta means the stamp is in the future
            // (clock skew), which must fall through to the absolute date
            in 2..6 -> dayFmt.format(then.time)
            else -> dateFmt.format(then.time)
        }
    }

    /** Day + clock time for the "last seen …" subtitle, e.g. "yesterday at
     *  8:42 PM". Used mid-sentence, so the relative-day words are lowercase
     *  ("today"/"yesterday"); weekdays and dates keep their normal caps. */
    fun compactWithTime(context: Context, epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        val time = clock(epochSeconds)
        val then = atSeconds(calA, epochSeconds)
        val days = daysBetween(then, now())
        val day = when (days) {
            0 -> context.getString(R.string.today_lc)
            1 -> context.getString(R.string.yesterday_lc)
            in 2..6 -> dayFmt.format(then.time)
            else -> dateFmt.format(then.time)
        }
        return context.getString(R.string.day_at_time, day, time)
    }

    // var, not val: rebuilt by refreshClockFormat on a locale or time-zone change
    private var sepFmt = SimpleDateFormat("MMMM d", locale)
    private var sepYearFmt = SimpleDateFormat("MMMM d, yyyy", locale)

    /** Day label for a chat date separator: Today / Yesterday / "July 9". */
    fun dateSeparator(context: Context, epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        val then = atSeconds(calA, epochSeconds)
        val now = now()
        return when (daysBetween(then, now)) {
            0 -> context.getString(R.string.today)
            1 -> context.getString(R.string.yesterday)
            else -> {
                val fmt = if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)) sepFmt else sepYearFmt
                fmt.format(then.time)
            }
        }
    }

    /** True if the two epoch-second timestamps fall on the same calendar day. */
    fun sameDay(a: Long, b: Long): Boolean {
        if (a <= 0 || b <= 0) return a == b
        return dayStampOf(atSeconds(calA, a)) == dayStampOf(atSeconds(calB, b))
    }

    /** Today encoded as YEAR*1000 + DAY_OF_YEAR — a cheap calendar-day identity
     *  (the same encoding daysBetween compares), for day-rollover detection. */
    fun dayStamp(): Int = dayStampOf(now())

    private fun dayStampOf(c: Calendar): Int =
        c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)

    /**
     * Signed difference in whole calendar days (to - from), correct across year
     * boundaries and negative for a `from` in the future. Computed from the
     * Julian day number rather than DAY_OF_YEAR, which used to need a 999/0
     * sentinel for the cross-year case — and got it wrong in both directions
     * (a Dec 31 message read on Jan 1 rendered as a date instead of
     * "Yesterday", and a clock-skewed future stamp rendered as "Today").
     */
    private fun daysBetween(from: Calendar, to: Calendar): Int =
        (julianDay(to) - julianDay(from)).toInt()

    private fun julianDay(c: Calendar): Long {
        val y = c.get(Calendar.YEAR).toLong()
        val m = (c.get(Calendar.MONTH) + 1).toLong()
        val d = c.get(Calendar.DAY_OF_MONTH).toLong()
        val a = (14 - m) / 12
        val yy = y + 4800 - a
        val mm = m + 12 * a - 3
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    }
}
