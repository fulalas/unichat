package org.unichat.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File

class ImageViewActivity : BaseActivity(), Bridge.UiListener {

    override val padForSystemBars: Boolean = false

    private lateinit var pager: ViewPager2
    private var chatId: String = ""
    private var images: List<MessageRow> = emptyList()
    private var singlePath: String = ""
    private var decodeTarget = 2160

    private companion object {
        private const val OLDER_ROUNDS = 8
    }

    // Album handed over by a search window instead of read from the history:
    // those rows are not stored, so their files must be fetched one at a time
    // and there is no older history to page into. See ChatActivity.openImageViewer.
    private var windowAlbum = false
    private val windowFetching = HashSet<String>()
    // one automatic try per picture: a failed page binds again on every swipe
    // past it, and each attempt is a blocking fetch that would never stop
    private val windowFailed = HashSet<String>()
    private val windowMedia = java.util.concurrent.Executors.newFixedThreadPool(2)
    // Pulling older history: a fetched page often holds no images at all, so
    // one request is rarely enough. This counts the pages still worth pulling
    // before giving up, and is refilled time the user swipes at the oldest
    // page.
    private var olderRoundsLeft = 0
    private var awaitingOlder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getStringExtra("chatId").orEmpty()
        if (chatId.isNotEmpty()) applyProtocolTheme(Accounts.ofChat(chatId))
        supportActionBar?.hide()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }

        val path = intent.getStringExtra("path") ?: run { finish(); return }
        setContentView(R.layout.activity_image)
        pager = findViewById(R.id.pager)
        findViewById<ImageButton>(R.id.menuButton).setOnClickListener { showMenu(it) }

        // Sized to the display rather than a fixed 2160: sampleSize keeps BOTH
        // dimensions above the target, so a 4000x3000 photo used to land on
        // inSampleSize 1 and allocate ~48MB — an OOM risk for a bitmap ~11x the
        // pixels the screen can show.
        val metrics = resources.displayMetrics
        decodeTarget = maxOf(metrics.widthPixels, metrics.heightPixels) * 2

        pager.adapter = PageAdapter()
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            // Settle, not onPageSelected: that only fires when the page CHANGES,
            // so once you are sitting on the oldest image every further swipe
            // was silent and nothing more was ever fetched.
            override fun onPageScrollStateChanged(state: Int) {
                if (state != ViewPager2.SCROLL_STATE_IDLE) return
                if (windowAlbum) extendWindowAlbum() else loadOlderIfAtStart()
            }
        })

        if (chatId.isEmpty()) {
            singlePath = path
            pager.adapter?.notifyDataSetChanged()
            return
        }
        singlePath = path
        if (adoptWindowAlbum(path)) return
        Io.executor.execute {
            val all = Bridge.db.chatImages(chatId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val start = all.indexOfFirst { it.filePath == path }
                if (start < 0) {
                    return@runOnUiThread
                }
                images = all
                singlePath = ""
                pager.adapter?.notifyDataSetChanged()
                pager.setCurrentItem(start, false)
                Bridge.addListener(this@ImageViewActivity)
            }
        }
    }

    private fun adoptWindowAlbum(path: String): Boolean {
        val ids = intent.getStringArrayListExtra("windowIds") ?: return false
        val paths = intent.getStringArrayListExtra("windowPaths") ?: return false
        if (ids.size != paths.size || ids.isEmpty()) return false
        windowAlbum = true
        images = ids.indices.map { i ->
            MessageRow(
                id = ids[i], chatId = chatId, senderId = "", text = "", fromMe = false,
                timeSent = 0, isRead = true, msgType = "image", filePath = paths[i],
            )
        }
        singlePath = ""
        pager.adapter?.notifyDataSetChanged()
        // by message id, not by path: two rows can share one file (a re-sent
        // photo), and matching the path would open a different one of them
        val start = ids.indexOf(intent.getStringExtra("windowStartId").orEmpty())
        if (start >= 0) pager.setCurrentItem(start, false)
        // The handed-over slice only holds the pictures of the search window
        // (~50 messages), which is a swipe or two; ask the server for the
        // chat's real picture album around this one.
        loadWindowAlbum(centreOn = ids.getOrElse(start) { ids.first() })
        return true
    }

    private fun loadWindowAlbum(centreOn: String) {
        if (centreOn.isEmpty()) return
        windowMedia.execute {
            val album = Bridge.chatPhotos(chatId, centreOn, null)
            runOnUiThread {
                if (isFinishing || isDestroyed || album.isEmpty()) return@runOnUiThread
                mergeWindowAlbum(album, keepOn = centreOn)
            }
        }
    }

    /**
     * Folds a server-fetched page of pictures into the album, keeping the one
     * on screen under the finger.
     *
     * Ordered by message id alone, NOT by time: the rows handed over through
     * the Intent carry no timestamp, so sorting by time would bunch them all
     * before the fetched ones. Telegram ids grow with the chat, which is the
     * same order — and this album only ever exists for Telegram.
     */
    private fun mergeWindowAlbum(fetched: List<MessageRow>, keepOn: String) {
        if (fetched.isEmpty()) return
        val known = images.associateBy { it.id }
        val merged = (fetched + images) // fetched first: it carries the real metadata
            .distinctBy { it.id }
            .map { row ->
                // but a file this phone already fetched is only known locally
                val old = known[row.id]
                if (row.filePath.isEmpty() && old != null && old.filePath.isNotEmpty()) {
                    row.copy(filePath = old.filePath, fileStatus = old.fileStatus)
                } else {
                    row
                }
            }
            .sortedBy { it.id.toLongOrNull() ?: 0L }
        if (merged == images) return
        val showing = keepOn.ifEmpty { currentMsg()?.id.orEmpty() }
        images = merged
        pager.adapter?.notifyDataSetChanged()
        val at = merged.indexOfFirst { it.id == showing }
        if (at >= 0) pager.setCurrentItem(at, false)
    }

    private var windowLoading = false
    private var windowOlderDone = false
    private var windowNewerDone = false

    /** Grows the album when the user reaches either end of it. */
    private fun extendWindowAlbum() {
        if (!windowAlbum || windowLoading || images.isEmpty()) return
        val at = pager.currentItem
        val goOlder = at <= 1 && !windowOlderDone
        val goNewer = at >= images.size - 2 && !windowNewerDone
        if (!goOlder && !goNewer) return
        val anchor = if (goOlder) images.first().id else images.last().id
        val before = images.size
        windowLoading = true
        windowMedia.execute {
            val more = Bridge.chatPhotos(chatId, anchor, newer = !goOlder)
            runOnUiThread {
                windowLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                mergeWindowAlbum(more, keepOn = currentMsg()?.id.orEmpty())
                // nothing new at that end means there is nothing more to fetch;
                // without this every swipe against the end re-asks the server
                if (images.size == before) {
                    if (goOlder) windowOlderDone = true else windowNewerDone = true
                }
            }
        }
    }

    /**
     * Fetches one search-window picture. The normal download path records its
     * progress on the message's stored row, and these rows have none — so it
     * hands the path straight back, exactly as the chat screen does for the
     * bubbles of the same window.
     */
    private fun fetchWindowImage(msg: MessageRow) {
        if (msg.id in windowFailed) return
        if (!windowFetching.add(msg.id)) return
        windowMedia.execute {
            val fetched = Bridge.searchMedia(msg.chatId, msg.id)
            runOnUiThread {
                windowFetching.remove(msg.id)
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (fetched.isEmpty()) {
                    windowFailed.add(msg.id)
                    // the page is stuck showing a spinner for something that is
                    // never coming; let the bind clear it
                    val gone = images.indexOfFirst { it.id == msg.id }
                    if (gone >= 0) pager.adapter?.notifyItemChanged(gone)
                    return@runOnUiThread
                }
                val at = images.indexOfFirst { it.id == msg.id }
                if (at < 0) return@runOnUiThread
                images = images.mapIndexed { i, m ->
                    if (i == at) m.copy(filePath = fetched, fileStatus = 2) else m
                }
                pager.adapter?.notifyItemChanged(at)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Bridge.removeListener(this)
        windowMedia.shutdownNow()
    }

    override fun onMessagesChanged(chatId: String, rowIds: Set<String>?) {
        if (chatId != this.chatId || images.isEmpty()) return
        if (rowIds != null) {
            refreshPages(rowIds)
            return
        }
        Io.executor.execute {
            val fresh = Bridge.db.chatImages(chatId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                awaitingOlder = false
                if (fresh.size == images.size) {
                    pullOlder()
                }
                if (fresh.size != images.size) {
                    // older history landed: the album grew at the FRONT, so
                    // every index shifted. Re-find the image being viewed and
                    // stay on it, or the user is thrown to a different photo.
                    val currentId = images.getOrNull(pager.currentItem)?.id
                    images = fresh
                    olderRoundsLeft = 0
                    pager.adapter?.notifyDataSetChanged()
                    val at = fresh.indexOfFirst { it.id == currentId }
                    if (at >= 0) pager.setCurrentItem(at, false)
                    return@runOnUiThread
                }
                // same album: only rebind pages whose file actually arrived. A
                // wholesale notifyDataSetChanged on every message event would
                // restart the decode (and drop the zoom) of the page in view.
                val changed = fresh.filterIndexed { i, m ->
                    val old = images.getOrNull(i)
                    old != null && old.id == m.id && old.filePath != m.filePath
                }
                images = fresh
                for (m in changed) {
                    val at = fresh.indexOfFirst { it.id == m.id }
                    if (at >= 0) pager.adapter?.notifyItemChanged(at)
                }
            }
        }
    }

    private fun refreshPages(ids: Set<String>) {
        Io.executor.execute {
            val fresh = Bridge.db.messagesByIds(chatId, ids).associateBy { it.id }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val landed = ArrayList<String>()
                images = images.map { old ->
                    val now = fresh[old.id] ?: return@map old
                    if (now.filePath == old.filePath) old else { landed.add(old.id); now }
                }
                for (id in landed) {
                    val at = images.indexOfFirst { it.id == id }
                    if (at >= 0) pager.adapter?.notifyItemChanged(at)
                }
            }
        }
    }

    private fun count(): Int = if (images.isEmpty()) 1 else images.size

    private fun loadOlderIfAtStart() {
        // a search window is a fixed slice the chat screen widens, not this one
        if (windowAlbum) return
        if (chatId.isEmpty() || images.isEmpty() || pager.currentItem > 0) return
        if (Bridge.isHistoryExhausted(chatId)) return
        olderRoundsLeft = OLDER_ROUNDS
        pullOlder()
    }

    private fun pullOlder() {
        if (awaitingOlder || olderRoundsLeft <= 0) return
        if (Bridge.isHistoryExhausted(chatId)) return
        awaitingOlder = true
        olderRoundsLeft--
        Bridge.requestChatHistory(chatId)
        // a page that answers with nothing at all produces no event to react to
        pager.postDelayed({
            if (awaitingOlder) {
                awaitingOlder = false
                pullOlder()
            }
        }, Bridge.historyTimeoutMs)
    }

    private fun currentPath(): String =
        if (images.isEmpty()) singlePath
        else images.getOrNull(pager.currentItem)?.filePath.orEmpty()

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun getItemCount(): Int = count()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_page, parent, false)
            val holder = PageHolder(view)
            holder.image.onSingleTap = { finish() }
            return holder
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val msg = images.getOrNull(position)
            val path = msg?.filePath ?: singlePath
            // a recycled page keeps the previous image's zoom and bitmap
            holder.image.reset()
            holder.image.setImageDrawable(null)
            holder.image.tag = path
            if (path.isEmpty()) {
                if (msg == null) return
                if (windowAlbum) {
                    holder.progress.visibility =
                        if (msg.id in windowFailed) View.GONE else View.VISIBLE
                    fetchWindowImage(msg)
                } else {
                    holder.progress.visibility = View.VISIBLE
                    Bridge.downloadFile(msg, userInitiated = true)
                }
                return
            }
            holder.progress.visibility = View.VISIBLE
            ImageLoader.decodeAsync(path, decodeTarget) { bitmap ->
                if (isFinishing || isDestroyed) return@decodeAsync
                // the page may have been recycled onto another image while
                // this decode ran
                if (holder.image.tag != path) return@decodeAsync
                holder.progress.visibility = View.GONE
                if (bitmap != null) holder.image.setImageBitmap(bitmap)
                else if (images.isEmpty()) finish()
            }
        }
    }

    private class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ZoomImageView = view.findViewById(R.id.image)
        val progress: ProgressBar = view.findViewById(R.id.pageProgress)
    }

    private fun showMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        if (currentMsg() != null) popup.menu.add(0, 1, 0, R.string.go_to_message)
        popup.menu.add(0, 2, 1, R.string.share)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { goToMessage(); true }
                2 -> { share(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun currentMsg(): MessageRow? = images.getOrNull(pager.currentItem)

    private fun goToMessage() {
        val msg = currentMsg() ?: return
        setResult(RESULT_OK, Intent().putExtra("jumpTo", msg.id))
        finish()
    }

    private fun share() {
        val path = currentPath()
        val file = if (path.isEmpty()) null else File(path)
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val (uri, mime) = providedFile(file, "image/*")
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = mime
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }
}
