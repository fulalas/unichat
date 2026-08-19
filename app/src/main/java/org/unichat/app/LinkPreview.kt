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

/**
 * The card under a message that links somewhere: site name, title, description
 * and picture, read from the page's Open Graph tags.
 *
 * Neither protocol hands us this. WhatsApp puts a preview in the message only
 * when the SENDER's client attached one, and TDLib resolves them behind an
 * option this client does not run, so the page is fetched here — once per URL,
 * however many chats it was shared in — and the answer is stored, including the
 * answer "this link has no preview" (or it would be re-fetched on every bind).
 */
object LinkPreview {

    private const val TAG = "UniChatLinkPreview"
    // Enough to reach the Open Graph tags of a page that buries them behind
    // inline script: YouTube's sit ~690 KB in, so the old 512 KB ceiling cut the
    // read short and every YouTube link came back "no preview". Reading stops at
    // </head> (see readAtMost), so an ordinary page still costs a few KB.
    private const val MAX_HTML_BYTES = 2 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_DESCRIPTION = 320
    const val IMAGE_DIR = "linkprev"

    /**
     * Sites gate their Open Graph tags on the crawler token every link-preview
     * client sends, and answer anything else with an app shell or a bot check:
     * IMDb replied 202 with an empty body to both a plain browser agent and an
     * agent naming only this app, so its links never got a card. This app's own
     * name rides along after the token, which sites match as a substring.
     *
     * It is also what makes previews cheap: YouTube serves a crawler its tags in
     * the first 3 KB, against ~690 KB of script for anyone else.
     */
    private const val USER_AGENT =
        "facebookexternalhit/1.1 UniChat/1.0 (+https://github.com/fulalas/unichat)"

    class Row(
        val url: String,
        val site: String,
        val title: String,
        val description: String,
        val imagePath: String,
        val hasPreview: Boolean,
    )

    private val fetcher = Executors.newFixedThreadPool(2)
    // Decoding gets its own pool: a fetch can hold a thread for minutes (six
    // redirect hops, each with its own connect and read timeout), and sharing
    // one pool meant two unreachable hosts stalled every card's picture.
    private val decoder = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    // url -> answer. A null VALUE is not possible in a ConcurrentHashMap, so a
    // link with no preview is memoised as a Row with hasPreview=false rather
    // than as an absent key, which would look like "never asked".
    private val cache = ConcurrentHashMap<String, Row>()

    // Everyone waiting on a URL still being fetched, NOT merely the fact that
    // one is running: leaving a chat and coming straight back builds a new
    // adapter whose request would land while the first is in flight, and simply
    // dropping it left those rows with no card until they were scrolled away
    // and back. Guarded by its own monitor; touched from the UI and the pool.
    private val waiters = HashMap<String, MutableList<(Row) -> Unit>>()

    fun cached(url: String): Row? = cache[url]

    /**
     * The first http(s) link in [text], given a scheme when it was typed
     * without one (as Linkify does, so a tap opens what the text linkifies to).
     * Returns null for text with no link — the common case, so the cheap '.'
     * test comes first.
     */
    fun firstUrl(text: String): String? {
        if (text.indexOf('.') < 0) return null
        val matcher = android.util.Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val raw = matcher.group()
            val scheme = raw.substringBefore("://", "")
            // Patterns.WEB_URL also matches bare "8.5" and "file.txt"; only take
            // something that is either explicitly http(s) or has a real host
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

    /**
     * Asks for [url]'s preview if it is not already known or being fetched.
     * [onReady] runs on the main thread once there is an answer — including a
     * negative one, so a caller that hid its card can stop waiting for it.
     */
    fun request(ctx: Context, url: String, onReady: (Row) -> Unit) {
        cache[url]?.let { onReady(it); return }
        synchronized(waiters) {
            val list = waiters.getOrPut(url) { ArrayList() }
            list.add(onReady)
            if (list.size > 1) return // a fetch is already running; we are queued on it
        }
        val appCtx = ctx.applicationContext
        fetcher.execute {
            val row = try {
                stored(url) ?: fetch(appCtx, url)
            } catch (e: Throwable) {
                // includes OutOfMemoryError from decoding a hostile image: one
                // bad link must not take down the fetch pool
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
        // the image is in cacheDir, which Android may reclaim under storage
        // pressure — a card with a hole where the picture was is worse than one
        // that briefly has no picture, so re-fetch instead
        if (row.imagePath.isNotEmpty() && !File(row.imagePath).exists()) {
            Bridge.db.forgetLinkPreviewImages(listOf(row.imagePath))
            return null
        }
        return row
    }

    /**
     * Only a page that was actually READ is remembered. A link that could not be
     * reached at all — no connectivity, a timeout, a refused request — answers
     * "no preview" for this run but is deliberately NOT written down: storing it
     * would mark every link scrolled past while offline as previewless forever,
     * since nothing ever expires those rows.
     */
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

    /**
     * Follows redirects by hand rather than letting HttpURLConnection do it: it
     * refuses to follow one that changes scheme, which is exactly what the
     * http→https hop every site now does is — so a link typed without a scheme
     * used to land on the redirect page itself, which carries no Open Graph tags.
     */
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
                val charset = type.substringAfter("charset=", "").trim().ifEmpty { "UTF-8" }
                val bytes = conn.inputStream.use { readAtMost(it, MAX_HTML_BYTES, HEAD_END) }
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

    private fun open(url: String): HttpURLConnection? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", java.util.Locale.getDefault().language + ",en;q=0.8")
        }
    }.getOrNull()

    private val HEAD_END = "</head>".toByteArray()

    /**
     * Reads up to [limit] bytes, stopping early once [stopAfter] has been seen.
     * Everything this class reads lives in the document head, and pages that
     * bury the Open Graph tags behind hundreds of KB of inline script would
     * otherwise be downloaded whole — megabytes, on someone's mobile data, for
     * four lines of metadata.
     */
    private fun readAtMost(
        input: java.io.InputStream, limit: Int, stopAfter: ByteArray? = null,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        // the tail of the previous chunk, so a marker split across two reads is
        // still found without re-scanning everything read so far
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

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        var i = from.coerceAtLeast(0)
        val last = haystack.size - needle.size
        outer@ while (i <= last) {
            for (k in needle.indices) {
                if (haystack[i + k] != needle[k]) {
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

    // Deliberately regex over the raw head rather than a real parser: the app
    // ships no HTML parser, and everything read here is a handful of meta tags.
    private fun parseMeta(html: String): Map<String, String> {
        val head = html.substringBefore("</head>", html).take(MAX_HTML_BYTES)
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
            // first tag of each kind wins, matching how browsers read them
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

    // Deliberately not Html.fromHtml: it parses the value as a block of HTML and
    // folds every line break into a space, so a description written as separate
    // paragraphs came out as one run-on line with its words stuck together.
    // A meta tag's content is plain text with entities, nothing more.
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
        // tab and newline are the only controls worth keeping; a page writes
        // them as entities because an attribute cannot hold them literally
        if (value < 0x20 && value != 0x09 && value != 0x0A) return null
        if (value in 0xD800..0xDFFF) return null // lone surrogate: not a character
        return String(Character.toChars(value))
    }

    // A link glued to the word before it. YouTube strips the line breaks out of
    // its own og:description when writing the tag, so the text arrives as
    // "…at the Apollohttp://livingcolour.comhttp://facebook.com/livingcolour" —
    // the break is not something this app lost, it is not in the page. Put one
    // back, since a run-on line is what the reader would otherwise see.
    private val GLUED_URL = Regex("(?<=[^\\s(\\[<\"'])(https?://)")

    private fun tidyDescription(text: String): String = GLUED_URL.replace(text, "\n$1")

    private fun downloadImage(ctx: Context, imageUrl: String): String? {
        val bytes = readBinary(imageUrl) ?: return null
        return runCatching {
            val dir = File(ctx.cacheDir, IMAGE_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val out = File(dir, fileNameFor(imageUrl))
            out.writeBytes(bytes)
            // a body that is not a decodable image would render as a blank gap
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
    private val decoding: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Paints a preview's picture, decoded off the main thread and shared
     *  between every bubble carrying the same link. [widthPx] is the width it
     *  is drawn at — see [applyBounds]. */
    fun loadImage(path: String, view: android.widget.ImageView, widthPx: Int) {
        view.tag = path
        bitmaps.get(path)?.let { applyBounds(view, it, widthPx); view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        waiting.await(path, view, widthPx)
        if (!decoding.add(path)) return
        decoder.execute {
            var delivering = false
            try {
                val bmp = ImageLoader.decodeSampled(path, widthPx) ?: return@execute
                bitmaps.put(path, bmp)
                delivering = true
                main.post { deliver(path, bmp) }
            } finally {
                decoding.remove(path)
                if (!delivering) waiting.abandon(path)
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

    /**
     * Sizes the view to the card's full width at the picture's own proportions,
     * so it is neither cut nor left sitting narrow with space beside it.
     *
     * The bounds have to be set explicitly. A match_parent width does not work
     * inside the card: LinearLayout re-measures a match_parent child with its
     * FIRST-PASS height pinned as exact, which switches adjustViewBounds off —
     * the view filled the width while the picture stayed small and centred in it.
     */
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
