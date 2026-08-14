package org.unichat.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/**
 * Copies [text] to the clipboard and shows a confirmation toast. On Android 13+
 * the system draws its own clipboard-copy overlay, so the toast is suppressed
 * there to avoid a double notification. Shared by every copy site (message copy,
 * link copy, pairing code) so the confirmation behaviour stays consistent.
 */
fun Context.copyToClipboard(label: String, text: String, confirmMsg: Int) {
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < 33) {
        Toast.makeText(this, confirmMsg, Toast.LENGTH_SHORT).show()
    }
}
