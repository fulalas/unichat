package org.unichat.app

import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared sizing rule for the app's in-memory bitmap caches. Both callers used
 * to declare an identical `maxMemory / 8` LruCache independently, which meant
 * the real combined bitmap budget was a quarter of the heap and tuning one copy
 * silently left the other at the old ceiling.
 *
 * [heapDivisor] splits the budget explicitly: avatars are small and many
 * (1/32), chat images are large and few (1/12), so together they stay under
 * ~12% of the heap.
 */
fun newBitmapCache(heapDivisor: Int): LruCache<String, Bitmap> {
    val sizeKb = (Runtime.getRuntime().maxMemory() / 1024 / heapDivisor).toInt().coerceAtLeast(4096)
    return object : LruCache<String, Bitmap>(sizeKb) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
}

class PendingViews<T> {

    class Entry<T>(val view: WeakReference<ImageView>, val payload: T)

    private val map = ConcurrentHashMap<String, CopyOnWriteArrayList<Entry<T>>>()

    fun await(key: String, view: ImageView, payload: T) {
        val list = map.computeIfAbsent(key) { CopyOnWriteArrayList() }
        list.removeIf { it.view.get().let { v -> v == null || v === view } }
        list.add(Entry(WeakReference(view), payload))
    }

    fun peek(key: String): List<Entry<T>>? = map[key]

    fun take(key: String): List<Entry<T>> = map.remove(key).orEmpty()

    fun abandon(key: String) {
        map.remove(key)
    }
}
