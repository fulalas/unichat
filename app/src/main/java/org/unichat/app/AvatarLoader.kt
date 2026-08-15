package org.unichat.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Loads chat avatars via the bridge (which caches them on disk) with an
 * in-memory bitmap cache. Falls back to a colored circle with the chat's
 * initial.
 *
 * Fast scrolling used to flood a tiny FIFO thread pool with duplicate/stale
 * fetches, so on-screen rows waited behind the whole backlog. This version:
 *  - dedups in-flight fetches per chat id (one fetch at a time),
 *  - remembers only the latest requesting ImageView per id and updates that,
 *  - keeps a memory-proportional bitmap cache so returning to the top is instant,
 *  - refreshes a cached avatar in the background when it is older than an hour.
 */
object AvatarLoader {

    private const val REFRESH_MS = 60 * 60 * 1000L // 1 hour
    // A contact with no profile picture writes no file, so there is nothing to
    // cache and every bind used to re-issue a network lookup through the
    // bridge. Remember the miss for a while instead.
    private const val NO_AVATAR_MS = 30 * 60 * 1000L // 30 minutes

    // memory-sized bitmap cache (KB) so many avatars survive long scrolls
    private val cache = newBitmapCache(32)
    private val loadedAt = ConcurrentHashMap<String, Long>()
    private val missedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = Collections.synchronizedSet(HashSet<String>())

    // EVERY ImageView still waiting on a given chat id, with the size it asked
    // for. One record used to be kept, so when two views on screen wanted the
    // same id (a chat-list row and the chat toolbar, or a contact appearing
    // twice in search results) only the last requester was ever painted and the
    // others sat on their coloured-initial placeholder until an unrelated
    // rebind. Held weakly so a destroyed view/activity is never retained here.
    private class Request(val view: WeakReference<ImageView>, val sizePx: Int)
    private val requests = ConcurrentHashMap<String, CopyOnWriteArrayList<Request>>()

    /** Queues [imageView] for [chatId]'s next decode, replacing its own earlier entry. */
    private fun await(chatId: String, imageView: ImageView, sizePx: Int) {
        val list = requests.computeIfAbsent(chatId) { CopyOnWriteArrayList() }
        list.removeIf { it.view.get().let { v -> v == null || v === imageView } }
        list.add(Request(WeakReference(imageView), sizePx))
    }

    /** Paints a finished avatar into every view still bound to [chatId]. Main thread. */
    private fun deliver(chatId: String, bitmap: Bitmap) {
        val pending = requests.remove(chatId) ?: return
        for (r in pending) {
            val view = r.view.get() ?: continue
            if (view.tag == chatId) view.setImageBitmap(bitmap)
        }
    }

    // Coloured-initial placeholders, keyed by (colour, initial, size): drawing
    // one allocates a bitmap plus a Canvas/Paint, and it used to happen on the
    // main thread on every bind of every picture-less chat.
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

    /**
     * Drops the cached avatar for a chat/user id so the next load re-decodes it
     * from disk — e.g. after the user changes their own profile picture, whose
     * on-disk copy the bridge has just replaced.
     */
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
            // show the cached bitmap now, refresh it in the background
        } else {
            imageView.setImageBitmap(placeholder(chatId, name, sizePx))
        }

        // known to have no picture and asked recently: don't cross the bridge again
        val missed = missedAt[chatId]
        if (missed != null && System.currentTimeMillis() - missed < NO_AVATAR_MS) return

        await(chatId, imageView, sizePx)
        if (!inFlight.add(chatId)) return // a fetch for this id is already running; we are queued on it

        // A stale cached bitmap is already on screen, so its refresh is pure
        // background work and goes straight to the (slow) fetch pool; a first
        // load has a placeholder on screen and takes the fast path first.
        if (cached != null) fetcher.execute { resolve(chatId, sizePx, cachedOnly = false) }
        else decoder.execute { resolve(chatId, sizePx, cachedOnly = true) }
    }

    /**
     * Decodes [chatId]'s avatar and hands it to the waiting views. [cachedOnly]
     * marks the decode-pool pass, which may only look at what is already on
     * disk; when nothing is there the id is re-queued on the fetch pool, the
     * only place the blocking bridge lookup is allowed to happen.
     */
    private fun resolve(chatId: String, sizePx: Int, cachedOnly: Boolean) {
        var delivering = false
        var handedOff = false
        try {
            val pending = requests[chatId]
            // Skip only when nothing holds a live view any more and the
            // bitmap is already cached. Deliberately does NOT read
            // imageView.tag: View is not thread-safe, and this runs on a
            // worker. A view recycled onto another row still holds a live
            // reference, so we may decode something momentarily off-screen —
            // deliver() re-checks the tag on the main thread and simply
            // won't paint it, and the decode still populates the cache.
            if (pending.isNullOrEmpty() && cache.get(chatId) != null) return
            val px = pending?.maxOfOrNull { it.sizePx } ?: sizePx
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
            // the fetch pass owns the id from here on, queue included
            if (!handedOff) {
                inFlight.remove(chatId)
                // nothing will be painted, so don't leave the queue behind
                if (!delivering) requests.remove(chatId)
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
