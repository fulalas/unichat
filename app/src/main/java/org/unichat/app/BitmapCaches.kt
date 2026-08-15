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

/**
 * Every ImageView waiting on the decode of a given key, each with whatever the
 * painting step needs to know about it ([T] — the bounds rule, the size asked
 * for). Both bitmap loaders need this and had grown their own copy: keeping one
 * requester per key meant that when two visible views wanted the same file (a
 * forwarded photo, a contact in the list and in the toolbar) only the last one
 * was ever painted and the others sat on a placeholder until an unrelated
 * rebind. Both copies also had to learn the same staleness and leak rules.
 *
 * Views are held weakly, so a destroyed view or activity is never retained.
 * Deciding whether a view is *still* bound to the key, and how to paint it, is
 * the caller's: only it knows what it wrote into the tag.
 */
class PendingViews<T> {

    class Entry<T>(val view: WeakReference<ImageView>, val payload: T)

    private val map = ConcurrentHashMap<String, CopyOnWriteArrayList<Entry<T>>>()

    /** Queues [view] for [key], replacing any earlier entry of its own. */
    fun await(key: String, view: ImageView, payload: T) {
        val list = map.computeIfAbsent(key) { CopyOnWriteArrayList() }
        list.removeIf { it.view.get().let { v -> v == null || v === view } }
        list.add(Entry(WeakReference(view), payload))
    }

    /** The current queue without consuming it — for deciding whether the decode
     *  is still worth doing, and at what size. */
    fun peek(key: String): List<Entry<T>>? = map[key]

    /** Removes and returns the queue; every waiter is now the caller's to paint. */
    fun take(key: String): List<Entry<T>> = map.remove(key).orEmpty()

    /** Drops the queue for a key nothing will ever be delivered for. */
    fun abandon(key: String) {
        map.remove(key)
    }
}
