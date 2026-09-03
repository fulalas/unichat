package org.unichat.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

fun renderQr(content: String, size: Int): Bitmap? = try {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
} catch (e: Exception) {
    android.util.Log.w("Qr", "QR encode failed", e)
    null
}
