package org.unichat.app

import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import java.lang.ref.WeakReference

fun newBitmapCache(heapDivisor: Int): LruCache<String, Bitmap> {
    val sizeKb = (Runtime.getRuntime().maxMemory() / 1024 / heapDivisor).toInt().coerceAtLeast(4096)
    return object : LruCache<String, Bitmap>(sizeKb) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
}

class PendingViews<T> {

    class Entry<T>(val view: WeakReference<ImageView>, val payload: T)

    private class Waiters<T> {
        val entries = ArrayList<Entry<T>>()
        var running = false
    }

    private val queues = HashMap<String, Waiters<T>>()

    @Synchronized
    fun await(key: String, view: ImageView, payload: T): Boolean {
        val q = queues.getOrPut(key) { Waiters() }
        q.entries.removeAll { it.view.get().let { v -> v == null || v === view } }
        q.entries.add(Entry(WeakReference(view), payload))
        if (q.running) return false
        q.running = true
        return true
    }

    @Synchronized
    fun peek(key: String): List<Entry<T>> = queues[key]?.entries?.toList().orEmpty()

    @Synchronized
    fun take(key: String): List<Entry<T>> = queues.remove(key)?.entries.orEmpty()

    @Synchronized
    fun settle(key: String, done: List<Entry<T>>): List<Entry<T>> {
        val q = queues[key] ?: return emptyList()
        q.entries.removeAll(done)
        if (q.entries.isEmpty()) {
            queues.remove(key)
            return emptyList()
        }
        return q.entries.toList()
    }
}
