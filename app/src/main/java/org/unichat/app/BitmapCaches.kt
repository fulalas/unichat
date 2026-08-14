package org.unichat.app

import android.graphics.Bitmap
import android.util.LruCache

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
