package org.unichat.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

object AvatarLoader {

    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val NO_AVATAR_MS = 30 * 60 * 1000L

    private val cache = newBitmapCache(32)
    private val loadedAt = ConcurrentHashMap<String, Long>()
    private val missedAt = ConcurrentHashMap<String, Long>()

    private class Req(val px: Int, val name: String)

    private val requests = PendingViews<Req>()
    private val bound = java.util.WeakHashMap<ImageView, Req>()
    private val changes = ConcurrentHashMap<String, Int>()

    private fun show(entries: List<PendingViews.Entry<Req>>, chatId: String, bitmap: Bitmap?) {
        for (r in entries) {
            val view = r.view.get() ?: continue
            if (view.tag != chatId) continue
            view.setImageBitmap(bitmap ?: placeholder(chatId, r.payload.name, r.payload.px))
        }
    }

    private val placeholders = newBitmapCache(64)

    fun loadBig(
        activity: android.app.Activity, chatId: String, px: Int, into: (Bitmap?) -> Unit,
    ) {
        Io.lookup.execute {
            val path = Bridge.bestAvatarPath(chatId)
            val bmp = if (path.isEmpty()) null else ImageLoader.decodeSampled(path, px)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                into(bmp)
            }
        }
    }

    private val decoder = Executors.newFixedThreadPool(4)
    private val fetcher = Executors.newFixedThreadPool(2)
    private val refresher = Executors.newFixedThreadPool(2)
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

    fun refresh(chatId: String) = main.post {
        changes.merge(chatId, 1, Int::plus)
        loadedAt.remove(chatId)
        missedAt.remove(chatId)
        for ((view, req) in bound.entries.toList()) {
            if (view.tag == chatId && requests.await(chatId, view, req)) {
                refresher.execute { resolve(chatId, req.px, cachedOnly = false) }
            }
        }
    }

    fun showInitials(name: String, imageView: ImageView, sizePx: Int) {
        imageView.tag = null
        bound.remove(imageView)
        imageView.setImageBitmap(placeholder(name, name, sizePx))
    }

    fun load(chatId: String, name: String, imageView: ImageView, sizePx: Int) {
        imageView.tag = chatId
        val req = Req(sizePx, name)
        bound[imageView] = req
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

        if (!requests.await(chatId, imageView, req)) return

        if (cached != null) refresher.execute { resolve(chatId, sizePx, cachedOnly = false) }
        else decoder.execute { resolve(chatId, sizePx, cachedOnly = true) }
    }

    private fun resolve(chatId: String, sizePx: Int, cachedOnly: Boolean, shownFile: String = "") {
        var delivering = false
        var handedOff = false
        var pending = emptyList<PendingViews.Entry<Req>>()
        var px = sizePx
        val gen = changes[chatId]
        fun refetch(pool: ExecutorService, shown: String = "") {
            pool.execute { resolve(chatId, px, cachedOnly = false, shown) }
            handedOff = true
        }
        fun showNone() {
            forget(chatId)
            delivering = true
            main.post { show(requests.take(chatId), chatId, null) }
        }
        try {
            pending = requests.peek(chatId)
            if (pending.isEmpty() && cache.get(chatId) != null) return
            px = pending.maxOfOrNull { it.payload.px } ?: sizePx
            val path =
                if (cachedOnly) Bridge.getCachedAvatarPath(chatId) else Bridge.getAvatarPath(chatId)
            if (path.isEmpty()) {
                if (cachedOnly) refetch(fetcher) else showNone()
                return
            }
            val file = path + "@" + File(path).lastModified()
            val shown = cache.get(chatId)
            val bitmap =
                if (shown != null && file == shownFile && shown.width >= px) shown
                else ImageLoader.decodeSampled(path, px)?.let { circleCrop(it, px) }
            if (bitmap == null) {
                showNone()
                return
            }
            cache.put(chatId, bitmap)
            missedAt.remove(chatId)
            if (cachedOnly || changes[chatId] != gen) {
                main.post { show(pending, chatId, bitmap) }
                refetch(refresher, if (cachedOnly) file else "")
                return
            }
            loadedAt[chatId] = System.currentTimeMillis()
            delivering = true
            main.post { show(requests.take(chatId), chatId, bitmap) }
        } catch (t: Throwable) {
            android.util.Log.e("AvatarLoader", "resolve failed for $chatId", t)
        } finally {
            if (!handedOff && !delivering && requests.settle(chatId, pending).isNotEmpty()) refetch(fetcher)
        }
    }

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
