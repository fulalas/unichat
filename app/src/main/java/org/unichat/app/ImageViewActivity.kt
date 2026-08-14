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

/**
 * Fullscreen image viewer: swipe left/right for the chat's other images, pinch
 * to zoom, tap to close, share the one on screen.
 *
 * Opened either on a chat message (the whole chat's images become the album) or
 * on a single picture with no chat behind it — an avatar — which stays a
 * one-page pager rather than a special case.
 */
class ImageViewActivity : BaseActivity(), Bridge.UiListener {

    // fullscreen by design: it hides the system bars and the image should fill
    // the whole window
    override val padForSystemBars: Boolean = false

    private lateinit var pager: ViewPager2
    private var chatId: String = ""
    private var images: List<MessageRow> = emptyList()
    // the standalone picture (an avatar), when there is no chat to page through
    private var singlePath: String = ""
    // decode target: the display, with headroom for pinch zoom
    private var decodeTarget = 2160

    private companion object {
        // history pages pulled per swipe against the oldest image before the
        // user has to ask again
        private const val OLDER_ROUNDS = 8
    }
    // Pulling older history: a fetched page often holds no images at all, so
    // one request is rarely enough. This counts the pages still worth pulling
    // before giving up, and is refilled time the user swipes at the oldest
    // page.
    private var olderRoundsLeft = 0
    private var awaitingOlder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getStringExtra("chatId").orEmpty()
        // the picture belongs to a chat, and its controls take that chat's
        // accent; unknown (no chat passed) keeps the app's own colours
        if (chatId.isNotEmpty() && !Tg.isTgId(chatId)) {
            theme.applyStyle(R.style.ThemeOverlay_UniChat_Wa, true)
        }
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
                if (state == ViewPager2.SCROLL_STATE_IDLE) loadOlderIfAtStart()
            }
        })

        if (chatId.isEmpty()) {
            singlePath = path
            pager.adapter?.notifyDataSetChanged()
            return
        }
        // the album is a DB read, so the tapped picture is shown first and the
        // rest of the chat's images slot in around it once they are known
        singlePath = path
        Io.executor.execute {
            val all = Bridge.db.chatImages(chatId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val start = all.indexOfFirst { it.filePath == path }
                if (start < 0) {
                    // not in the album (an avatar opened from a chat screen)
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

    override fun onDestroy() {
        super.onDestroy()
        Bridge.removeListener(this)
    }

    /** A page's download finished: rebind it so the picture replaces the spinner. */
    override fun onMessagesChanged(chatId: String) {
        if (chatId != this.chatId || images.isEmpty()) return
        Io.executor.execute {
            val fresh = Bridge.db.chatImages(chatId)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                awaitingOlder = false
                if (fresh.size == images.size) {
                    // that page held no images: keep walking back
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

    private fun count(): Int = if (images.isEmpty()) 1 else images.size

    /**
     * The album holds the images of the history fetched so far, oldest first,
     * so page 0 is the oldest one KNOWN — not necessarily the oldest there is.
     * Swiping against it pulls history backwards until an older image turns up
     * or the chat's start is reached.
     */
    private fun loadOlderIfAtStart() {
        if (chatId.isEmpty() || images.isEmpty() || pager.currentItem > 0) return
        if (Bridge.isHistoryExhausted(chatId)) return
        // each swipe against the edge refills the budget, so a long run of
        // image-less history is walked by swiping again rather than by waiting
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

    /** File backing the page currently on screen, "" when it is not local yet. */
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
                // not downloaded yet: ask for it and wait for onMessagesChanged
                holder.progress.visibility = View.VISIBLE
                if (msg != null) Bridge.downloadFile(msg, userInitiated = true)
                return
            }
            holder.progress.visibility = View.VISIBLE
            Io.executor.execute {
                val bitmap = ImageLoader.decodeSampled(path, decodeTarget)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // the page may have been recycled onto another image while
                    // this decode ran
                    if (holder.image.tag != path) return@runOnUiThread
                    holder.progress.visibility = View.GONE
                    if (bitmap != null) holder.image.setImageBitmap(bitmap)
                    else if (images.isEmpty()) finish()
                }
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

    /** Closes the viewer and asks the chat behind it to scroll to this message. */
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
