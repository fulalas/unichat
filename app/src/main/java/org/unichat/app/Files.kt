package org.unichat.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

// Shared file plumbing: cacheDir staging of content URIs (attach/share/record
// flows) and FileProvider hand-off to external apps.

/** Prefixes of cacheDir staging files, shared with Bridge.cleanStaleCache's
 *  startup sweep — every staging producer must use one of these. */
val STAGING_PREFIXES = listOf("attach", "share", "rec", "avatar")

private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

// User-visible file names (the chat export) keep spaces and non-ASCII letters,
// so they can't use the strict allow-list above — but they must reject the same
// path/control characters, which a hand-rolled deny-list per call site kept
// getting subtly wrong (it let NULs and newlines through).
private val UNSAFE_DISPLAY_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

/** Sanitizes a human-readable name for use as a file name. */
fun safeDisplayFileName(name: String): String =
    name.replace(UNSAFE_DISPLAY_CHARS, "_").trim().ifEmpty { "chat" }

/** A fresh cacheDir staging file named "<prefix>_<timestamp>_<sanitized name>". */
fun Context.stagingFile(prefix: String, name: String): File {
    val safe = name.replace(UNSAFE_FILE_CHARS, "_")
    return File(cacheDir, "${prefix}_${System.currentTimeMillis()}_$safe")
}

/** Resolves a content Uri's display name, or the last path segment if the
 *  provider exposes none. Never throws: ShareActivity is exported, so the Uri
 *  comes from an arbitrary app and querying it can fail with SecurityException
 *  (a grant that was never given, or already revoked) — which, raised on the
 *  bare worker that stages a share, would take the process down instead of
 *  reporting the failure. */
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

private const val FILE_PROVIDER_AUTHORITY = "org.unichat.app.fileprovider"

/** A content Uri other apps may read [file] through, with its MIME type
 *  guessed from the extension ([fallbackMime] when unknown). */
fun Context.providedFile(file: File, fallbackMime: String): Pair<Uri, String> {
    val uri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, file)
    val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase()) ?: fallbackMime
    return uri to mime
}
