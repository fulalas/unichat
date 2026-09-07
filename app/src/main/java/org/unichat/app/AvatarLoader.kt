package org.unichat.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

object AvatarLoader {

    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val NO_AVATAR_MS = 30 * 60 * 1000L

    private val cache = newBitmapCache(32)
    private val loadedAt = ConcurrentHashMap<String, Long>()
    private val missedAt = ConcurrentHashMap<String, Long>()

    private val requests = PendingViews<Int>()

    private fun deliver(chatId: String, bitmap: Bitmap) {
        for (r in requests.take(chatId)) {
            val view = r.view.get() ?: continue
            if (view.tag == chatId) view.setImageBitmap(bitmap)
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

        if (!requests.await(chatId, imageView, sizePx)) return

        if (cached != null) fetcher.execute { resolve(chatId, sizePx, cachedOnly = false) }
        else decoder.execute { resolve(chatId, sizePx, cachedOnly = true) }
    }

    private fun resolve(chatId: String, sizePx: Int, cachedOnly: Boolean) {
        var delivering = false
        var handedOff = false
        var pending = emptyList<PendingViews.Entry<Int>>()
        var px = sizePx
        try {
            pending = requests.peek(chatId)
            if (pending.isEmpty() && cache.get(chatId) != null) return
            px = pending.maxOfOrNull { it.payload } ?: sizePx
            val path =
                if (cachedOnly) Bridge.getCachedAvatarPath(chatId) else Bridge.getAvatarPath(chatId)
            if (path.isEmpty()) {
                if (cachedOnly) {
                    fetcher.execute { resolve(chatId, px, cachedOnly = false) }
                    handedOff = true
                    return
                }
                forget(chatId)
                return
            }
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
        } catch (t: Throwable) {
            android.util.Log.e("AvatarLoader", "resolve failed for $chatId", t)
        } finally {
            if (!handedOff && !delivering && requests.settle(chatId, pending).isNotEmpty()) {
                fetcher.execute { resolve(chatId, px, cachedOnly = false) }
            }
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

    fun initials(name: String, sizePx: Int): Bitmap = placeholder(name, name, sizePx)

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
