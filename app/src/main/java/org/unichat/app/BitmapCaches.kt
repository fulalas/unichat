package org.unichat.app

import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import java.lang.ref.WeakReference

/**
 * Callers used to each declare their own `maxMemory / 8` LruCache, so the real
 * combined bitmap budget was a quarter of the heap and tuning one copy left the
 * others at the old ceiling. [heapDivisor] splits one budget explicitly.
 */
fun newBitmapCache(heapDivisor: Int): LruCache<String, Bitmap> {
    val sizeKb = (Runtime.getRuntime().maxMemory() / 1024 / heapDivisor).toInt().coerceAtLeast(4096)
    return object : LruCache<String, Bitmap>(sizeKb) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
}

/**
 * The waiters and the claim on the run serving them live behind one lock
 * because every attempt to keep them apart raced: queueing a waiter is not
 * atomic against the run's take/settle, and the two happen on different
 * threads. A waiter landing in that window was either wiped while its own claim
 * failed, or added to a list already dropped from the map — either way nothing
 * painted it and the row kept its placeholder until rebound. The four callers
 * each patched that differently before it moved in here.
 */
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

    /**
     * Returns the waiters queued after the run started; they could not claim a
     * run of their own, so the caller must dispatch one for them. Dropping
     * [done] is what makes a permanently failing load terminate instead of
     * re-dispatching itself for ever.
     */
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
