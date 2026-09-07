package org.unichat.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object LinkPreview {

    private const val TAG = "UniChatLinkPreview"
    private const val MAX_HTML_BYTES = 2 * 1024 * 1024
    private const val CHARSET_SNIFF_BYTES = 4 * 1024
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_DESCRIPTION = 320
    const val IMAGE_DIR = "linkprev"

    private const val USER_AGENT = "TelegramBot (like TwitterBot)"

    class Row(
        val url: String,
        val site: String,
        val title: String,
        val description: String,
        val imagePath: String,
        val hasPreview: Boolean,
    )

    private val fetcher = Executors.newFixedThreadPool(2)
    private val decoder = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private val cache = ConcurrentHashMap<String, Row>()

    private val waiters = HashMap<String, MutableList<(Row) -> Unit>>()

    fun cached(url: String): Row? = cache[url]

    fun firstUrl(text: String): String? {
        if (text.indexOf('.') < 0) return null
        val matcher = android.util.Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val raw = matcher.group()
            val scheme = raw.substringBefore("://", "")
            if (scheme.isNotEmpty() && !scheme.equals("http", true) &&
                !scheme.equals("https", true)
            ) {
                continue
            }
            val withScheme = if (scheme.isEmpty()) "http://$raw" else raw
            val host = runCatching { URL(withScheme).host }.getOrNull().orEmpty()
            if (!host.contains('.')) continue
            return withScheme
        }
        return null
    }

    fun request(ctx: Context, url: String, onReady: (Row) -> Unit) {
        cache[url]?.let { onReady(it); return }
        synchronized(waiters) {
            val list = waiters.getOrPut(url) { ArrayList() }
            list.add(onReady)
            if (list.size > 1) return
        }
        val appCtx = ctx.applicationContext
        fetcher.execute {
            val row = try {
                stored(url) ?: fetch(appCtx, url)
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "preview failed for $url", e)
                empty(url)
            }
            cache[url] = row
            val pending = synchronized(waiters) { waiters.remove(url).orEmpty() }
            main.post { for (waiter in pending) waiter(row) }
        }
    }

    private fun empty(url: String) = Row(url, "", "", "", "", hasPreview = false)

    private fun stored(url: String): Row? {
        val row = Bridge.db.linkPreview(url) ?: return null
        if (row.imagePath.isNotEmpty() && !File(row.imagePath).exists()) {
            Bridge.db.forgetLinkPreviewImages(listOf(row.imagePath))
            return null
        }
        return row
    }

    private fun fetch(ctx: Context, url: String): Row {
        val html = readText(url) ?: return empty(url)
        val meta = parseMeta(html)
        val title = meta["og:title"] ?: meta["twitter:title"] ?: htmlTitle(html) ?: ""
        val description = tidyDescription(
            meta["og:description"] ?: meta["twitter:description"] ?: ""
        ).take(MAX_DESCRIPTION)
        val site = meta["og:site_name"] ?: hostLabel(url)
        if (title.isEmpty() && description.isEmpty()) return persist(empty(url))
        val imageUrl = meta["og:image"] ?: meta["twitter:image"] ?: ""
        val imagePath =
            if (imageUrl.isEmpty()) "" else downloadImage(ctx, absolute(url, imageUrl)).orEmpty()
        return persist(Row(url, site, title, description, imagePath, hasPreview = true))
    }

    private fun persist(row: Row): Row {
        runCatching { Bridge.db.putLinkPreview(row) }
        return row
    }

    private fun hostLabel(url: String): String =
        runCatching { URL(url).host.removePrefix("www.") }.getOrNull().orEmpty()

    private fun absolute(pageUrl: String, ref: String): String =
        runCatching { URL(URL(pageUrl), ref).toString() }.getOrNull() ?: ref

    private fun readText(startUrl: String): String? {
        var url = startUrl
        for (hop in 0..MAX_REDIRECTS) {
            val conn = open(url) ?: return null
            try {
                val code = conn.responseCode
                if (code in 301..308 && code != 304 && code != 306) {
                    val location = conn.getHeaderField("Location") ?: return null
                    url = absolute(url, location)
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) return null
                val type = conn.contentType.orEmpty()
                if (type.isNotEmpty() && !type.startsWith("text/html") &&
                    !type.startsWith("application/xhtml")
                ) {
                    return null
                }
                val bytes = conn.inputStream.use { readAtMost(it, MAX_HTML_BYTES, HEAD_END) }
                val charset = type.substringAfter("charset=", "").trim()
                    .ifEmpty { sniffCharset(bytes) }.ifEmpty { "UTF-8" }
                return runCatching { String(bytes, charset(charset)) }
                    .getOrElse { String(bytes, Charsets.UTF_8) }
            } catch (e: Exception) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun privateAddress(a: java.net.InetAddress): Boolean =
        a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress ||
            (a is java.net.Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC)

    private fun open(url: String): HttpURLConnection? = runCatching {
        val target = URL(url)
        require(java.net.InetAddress.getAllByName(target.host).none(::privateAddress))
        (target.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", java.util.Locale.getDefault().language + ",en;q=0.8")
        }
    }.getOrNull()

    private val META_CHARSET = Regex(
        "<meta[^>]+charset\\s*=\\s*[\"']?\\s*([\\w.:-]+)", RegexOption.IGNORE_CASE
    )

    private fun sniffCharset(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, CHARSET_SNIFF_BYTES), Charsets.ISO_8859_1)
        return META_CHARSET.find(head)?.groupValues?.get(1).orEmpty()
    }

    private val HEAD_END = "</head>".toByteArray()

    private fun readAtMost(
        input: java.io.InputStream, limit: Int, stopAfter: ByteArray? = null,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        var carry = ByteArray(0)
        while (total < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (stopAfter == null) continue
            val window = carry + buf.copyOf(n)
            if (indexOfBytes(window, stopAfter, 0) >= 0) break
            carry = window.copyOfRange(
                (window.size - stopAfter.size + 1).coerceAtLeast(0), window.size
            )
        }
        return out.toByteArray()
    }

    private fun lowerAscii(b: Byte): Byte =
        if (b >= 'A'.code.toByte() && b <= 'Z'.code.toByte()) (b + 32).toByte() else b

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        var i = from.coerceAtLeast(0)
        val last = haystack.size - needle.size
        outer@ while (i <= last) {
            for (k in needle.indices) {
                if (lowerAscii(haystack[i + k]) != needle[k]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    private val META_TAG = Regex(
        "<meta\\s+[^>]*>", RegexOption.IGNORE_CASE
    )
    private val ATTR = Regex(
        "(property|name|content)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
        RegexOption.IGNORE_CASE
    )
    private val TITLE_TAG = Regex(
        "<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private fun parseMeta(html: String): Map<String, String> {
        val cut = html.indexOf("</head>", ignoreCase = true)
        val head = (if (cut >= 0) html.substring(0, cut) else html).take(MAX_HTML_BYTES)
        val out = HashMap<String, String>()
        for (tag in META_TAG.findAll(head)) {
            var key = ""
            var content = ""
            for (attr in ATTR.findAll(tag.value)) {
                val value = attr.groupValues[3].ifEmpty { attr.groupValues[4] }
                    .ifEmpty { attr.groupValues[5] }
                when (attr.groupValues[1].lowercase()) {
                    "property", "name" -> if (key.isEmpty()) key = value.lowercase()
                    "content" -> content = value
                }
            }
            if (key.isEmpty() || content.isEmpty()) continue
            if (key.startsWith("og:") || key.startsWith("twitter:")) out.putIfAbsent(key, unescape(content))
        }
        return out
    }

    private fun htmlTitle(html: String): String? =
        TITLE_TAG.find(html)?.groupValues?.get(1)?.let { unescape(it).trim() }?.takeIf { it.isNotEmpty() }

    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z]{2,8});")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    )

    private fun unescape(text: String): String {
        if (text.indexOf('&') < 0) return text.trim()
        return ENTITY.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    codePoint(body.drop(2).toIntOrNull(16)) ?: m.value
                body.startsWith("#") -> codePoint(body.drop(1).toIntOrNull()) ?: m.value
                else -> NAMED[body.lowercase()] ?: m.value
            }
        }.trim()
    }

    private fun codePoint(value: Int?): String? {
        if (value == null || value > 0x10FFFF) return null
        if (value < 0x20 && value != 0x09 && value != 0x0A) return null
        if (value in 0xD800..0xDFFF) return null
        return String(Character.toChars(value))
    }

    private val GLUED_URL = Regex("(?<=[^\\s(\\[<\"'])(https?://)")

    private fun tidyDescription(text: String): String = GLUED_URL.replace(text, "\n$1")

    private fun downloadImage(ctx: Context, imageUrl: String): String? {
        val bytes = readBinary(imageUrl) ?: return null
        return runCatching {
            val dir = File(ctx.cacheDir, IMAGE_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val out = File(dir, fileNameFor(imageUrl))
            out.writeBytes(bytes)
            if (ImageLoader.decodeSampled(out.path, 64) == null) {
                out.delete()
                return null
            }
            out.path
        }.getOrNull()
    }

    private fun fileNameFor(url: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return digest.take(32) + ".img"
    }

    private fun readBinary(url: String): ByteArray? {
        var current = url
        for (hop in 0..MAX_REDIRECTS) {
            val conn = open(current) ?: return null
            try {
                val code = conn.responseCode
                if (code in 301..308 && code != 304 && code != 306) {
                    current = absolute(current, conn.getHeaderField("Location") ?: return null)
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) return null
                if (conn.contentLength > MAX_IMAGE_BYTES) return null
                return conn.inputStream.use { readAtMost(it, MAX_IMAGE_BYTES) }
            } catch (e: Exception) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private val bitmaps = newBitmapCache(48)
    private val waiting = PendingViews<Int>()

    fun loadImage(path: String, view: android.widget.ImageView, widthPx: Int) {
        view.tag = path
        bitmaps.get(path)?.let { applyBounds(view, it, widthPx); view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        if (waiting.await(path, view, widthPx)) dispatchDecode(path, widthPx)
    }

    private fun dispatchDecode(path: String, widthPx: Int) {
        decoder.execute {
            var delivering = false
            val queued = waiting.peek(path)
            try {
                if (queued.isEmpty()) return@execute
                val bmp = ImageLoader.decodeSampled(path, widthPx) ?: return@execute
                bitmaps.put(path, bmp)
                delivering = true
                main.post { deliver(path, bmp) }
            } finally {
                if (!delivering && waiting.settle(path, queued).isNotEmpty()) {
                    dispatchDecode(path, widthPx)
                }
            }
        }
    }

    private fun deliver(path: String, bitmap: Bitmap) {
        for (w in waiting.take(path)) {
            val view = w.view.get() ?: continue
            if (view.tag != path) continue
            applyBounds(view, bitmap, w.payload)
            view.setImageBitmap(bitmap)
        }
    }

    private fun applyBounds(view: android.widget.ImageView, bmp: Bitmap, widthPx: Int) {
        if (bmp.width <= 0 || bmp.height <= 0 || widthPx <= 0) return
        val lp = view.layoutParams ?: return
        val height = (widthPx.toLong() * bmp.height / bmp.width).toInt().coerceAtLeast(1)
        if (lp.width != widthPx || lp.height != height) {
            lp.width = widthPx
            lp.height = height
            view.layoutParams = lp
        }
    }
}
