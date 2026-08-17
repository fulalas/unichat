package org.unichat.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

object AvatarLoader {

    private const val REFRESH_MS = 60 * 60 * 1000L // 1 hour
    // A contact with no profile picture writes no file, so there is nothing to
    // cache and every bind used to re-issue a network lookup through the
    // bridge. Remember the miss for a while instead.
    private const val NO_AVATAR_MS = 30 * 60 * 1000L // 30 minutes

    private val cache = newBitmapCache(32)
    private val loadedAt = ConcurrentHashMap<String, Long>()
    private val missedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    private val requests = PendingViews<Int>()

    private fun deliver(chatId: String, bitmap: Bitmap) {
        for (r in requests.take(chatId)) {
            val view = r.view.get() ?: continue
            if (view.tag == chatId) view.setImageBitmap(bitmap)
        }
    }

    private val placeholders = newBitmapCache(64)

    // Two pools, because the two halves of a load have nothing in common:
    // decoding an avatar the bridge already has on disk takes milliseconds,
    // while asking the bridge for one it does not have blocks for a long time
    // (a 20s TDLib download for tg: ids, a timeout-less HTTP request for
    // WhatsApp ones). Sharing one pool meant four unreachable contacts pinned
    // every thread and every cached row waited behind them — possibly forever.
    // The fetch pool is deliberately small: it is network-bound work whose only
    // job is to not starve anything else.
    private val decoder = Executors.newFixedThreadPool(4)
    private val fetcher = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private val placeholderColors = intArrayOf(
        0xFF6B5B95.toInt(), 0xFF88B04B.toInt(), 0xFFDD4124.toInt(), 0xFF009B77.toInt(),
        0xFFEFC050.toInt(), 0xFF5B5EA6.toInt(), 0xFF955251.toInt(), 0xFF45B8AC.toInt(),
    )

    fun invalidate(chatId: String) {
        cache.remove(chatId)
        loadedAt.remove(chatId)
        missedAt.remove(chatId)
    }

    fun load(chatId: String, name: String, imageView: ImageView, sizePx: Int) {
        imageView.tag = chatId
        val cached = cache.get(chatId)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            val fresh = System.currentTimeMillis() - (loadedAt[chatId] ?: 0L) < REFRESH_MS
            if (fresh) return
        } else {
            imageView.setImageBitmap(placeholder(chatId, name, sizePx))
        }

        val missed = missedAt[chatId]
        if (missed != null && System.currentTimeMillis() - missed < NO_AVATAR_MS) return

        requests.await(chatId, imageView, sizePx)
        if (!inFlight.add(chatId)) return // a fetch for this id is already running; we are queued on it

        if (cached != null) fetcher.execute { resolve(chatId, sizePx, cachedOnly = false) }
        else decoder.execute { resolve(chatId, sizePx, cachedOnly = true) }
    }

    private fun resolve(chatId: String, sizePx: Int, cachedOnly: Boolean) {
        var delivering = false
        var handedOff = false
        try {
            val pending = requests.peek(chatId)
            // Skip only when nothing holds a live view any more and the
            // bitmap is already cached. Deliberately does NOT read
            // imageView.tag: View is not thread-safe, and this runs on a
            // worker. A view recycled onto another row still holds a live
            // reference, so we may decode something momentarily off-screen —
            // deliver() re-checks the tag on the main thread and simply
            // won't paint it, and the decode still populates the cache.
            if (pending.isNullOrEmpty() && cache.get(chatId) != null) return
            val px = pending?.maxOfOrNull { it.payload } ?: sizePx
            val path =
                if (cachedOnly) Bridge.getCachedAvatarPath(chatId) else Bridge.getAvatarPath(chatId)
            if (path.isEmpty()) {
                if (cachedOnly) {
                    handedOff = true
                    fetcher.execute { resolve(chatId, px, cachedOnly = false) }
                    return
                }
                forget(chatId)
                return
            }
            // Subsample on the way in: circleCrop scales to px (256 at most)
            // anyway, so a full-resolution decode of an avatar the server allows
            // up to 8 MiB allocated the whole bitmap just to throw it away.
            // decodeSampled also catches OutOfMemoryError, which a bare decode
            // on this pool let reach the thread's uncaught handler.
            val raw = ImageLoader.decodeSampled(path, px)
            if (raw == null) {
                forget(chatId)
                return
            }
            val circled = circleCrop(raw, px)
            cache.put(chatId, circled)
            loadedAt[chatId] = System.currentTimeMillis()
            missedAt.remove(chatId)
            delivering = true
            main.post { deliver(chatId, circled) }
        } finally {
            if (!handedOff) {
                inFlight.remove(chatId)
                // nothing will be painted, so don't leave the queue behind
                if (!delivering) requests.abandon(chatId)
            }
        }
    }

    /**
     * The chat has no usable picture (any more). The cached bitmap has to go
     * with the miss: keeping it meant a picture the contact removed stayed on
     * screen for the rest of the process's life, because every later bind took
     * the cache-hit branch and the refresh always landed back here.
     */
    private fun forget(chatId: String) {
        cache.remove(chatId)
        loadedAt.remove(chatId)
        missedAt[chatId] = System.currentTimeMillis()
    }

    private fun circleCrop(src: Bitmap, sizePx: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val r = sizePx / 2f
        canvas.drawCircle(r, r, r, paint)
        if (scaled !== src) src.recycle()
        return out
    }

    fun initials(name: String, sizePx: Int): Bitmap = placeholder(name, name, sizePx)

    private fun placeholder(chatId: String, name: String, sizePx: Int): Bitmap {
        // masked rather than abs(): abs(Int.MIN_VALUE) is negative, which would
        // index out of the palette the moment its size stops dividing 2^31
        val color = placeholderColors[
            ((chatId.hashCode().toLong() and 0x7fffffffL) % placeholderColors.size).toInt()
        ]
        val initial = name.trim().firstOrNull()?.uppercaseChar() ?: '?'
        val key = "$color/$initial/$sizePx"
        placeholders.get(key)?.let { return it }
        val drawn = drawPlaceholder(color, initial, sizePx)
        placeholders.put(key, drawn)
        return drawn
    }

    private fun drawPlaceholder(color: Int, initial: Char, sizePx: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        val r = sizePx / 2f
        canvas.drawCircle(r, r, r, paint)

        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = sizePx * 0.45f
        paint.textAlign = Paint.Align.CENTER
        val y = r - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initial.toString(), r, y, paint)
        return out
    }

    fun dp(imageView: ImageView, dp: Int): Int {
        val density = imageView.resources.displayMetrics.density
        return min((dp * density).toInt(), 256)
    }
}
