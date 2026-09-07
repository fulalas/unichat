package org.unichat.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

fun Context.copyToClipboard(label: String, text: String, confirmMsg: Int) {
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < 33) {
        Toast.makeText(this, confirmMsg, Toast.LENGTH_SHORT).show()
    }
}
