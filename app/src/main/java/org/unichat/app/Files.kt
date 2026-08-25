package org.unichat.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Prefixes of cacheDir staging files, shared with Bridge.cleanStaleCache's
 *  startup sweep — every staging producer must use one of these. */
val STAGING_PREFIXES = listOf("attach", "share", "rec", "avatar")

private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

private val UNSAFE_DISPLAY_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

fun safeDisplayFileName(name: String): String =
    name.replace(UNSAFE_DISPLAY_CHARS, "_").trim().ifEmpty { "chat" }

fun Context.stagingFile(prefix: String, name: String): File {
    val safe = name.replace(UNSAFE_FILE_CHARS, "_")
    return File(cacheDir, "${prefix}_${System.currentTimeMillis()}_$safe")
}

fun Context.uriDisplayName(uri: Uri): String? {
    try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
    } catch (_: Exception) {
        return null
    }
    return uri.lastPathSegment
}

/** Copies a content Uri into a staging file; null on any failure. The partial
 *  file is unlinked on the way out: leaving it meant a source that fails
 *  part-way (a provider dying, a full disk) dropped a half-written file in
 *  cacheDir that only the 24h startup sweep ever reclaimed, and a caller that
 *  retried just added another. */
fun Context.copyUriToCache(uri: Uri, prefix: String, name: String): File? {
    val out = stagingFile(prefix, name)
    return try {
        contentResolver.openInputStream(uri).use { input ->
            if (input == null) {
                out.delete()
                return null
            }
            out.outputStream().use { input.copyTo(it) }
        }
        out
    } catch (_: Exception) {
        out.delete()
        null
    }
}

fun mimeOfPath(path: String, fallback: String = "application/octet-stream"): String =
    MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(File(path).extension.lowercase()) ?: fallback

/**
 * Copies [file] into the public Downloads collection under [name], answering
 * the name it actually landed under (MediaStore appends "(1)" and the like on a
 * collision) or null on failure. Goes through MediaStore rather than writing a
 * File in DIRECTORY_DOWNLOADS: from Android 10 on that path is not writable
 * without legacy storage, and MediaStore needs no permission for its own row.
 * IS_PENDING hides the row until the copy finishes, so a file picker never
 * offers a half-written file.
 */
fun Context.copyToDownloads(file: File, name: String): String? {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mimeOfPath(name, mimeOfPath(file.path)))
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = try {
        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } catch (_: Exception) {
        null
    } ?: return null
    return try {
        val out = contentResolver.openOutputStream(uri) ?: throw IOException("no stream")
        out.use { sink -> file.inputStream().use { it.copyTo(sink) } }
        contentResolver.update(
            uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null
        )
        uriDisplayName(uri) ?: name
    } catch (_: Exception) {
        try {
            contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
        null
    }
}

private const val FILE_PROVIDER_AUTHORITY = "org.unichat.app.fileprovider"

fun Context.providedFile(file: File, fallbackMime: String): Pair<Uri, String> {
    val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
    return uri to mimeOfPath(file.path, fallbackMime)
}
