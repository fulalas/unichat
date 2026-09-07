package org.unichat.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

class MessageAdapter(
    private val isGroup: Boolean,
    private val onNeedDownload: (MessageRow, Boolean) -> Unit,
    private val onImageClick: (MessageRow) -> Unit,
    private val onDocumentClick: (MessageRow) -> Unit,
    private val onVideoOpen: (MessageRow) -> Unit,
    private val onLocationClick: (MessageRow) -> Unit,
    private val onContactClick: (MessageRow) -> Unit,
    private val onContactMessage: (MessageRow) -> Unit,
    private val onMessageActions: (MessageRow) -> Unit,
    private val onReactionsClick: (MessageRow) -> Unit,
    private val onQuoteClick: (MessageRow) -> Unit,
    private val onRetrySend: (MessageRow) -> Unit = {},
    private val onNeedLinkPreview: (String) -> Unit = {},
    private val onLinkPreviewClick: (String) -> Unit = {},
    private val onSelectionChanged: () -> Unit = {},
    private val onDragArm: () -> Unit = {},
) : RecyclerView.Adapter<MessageAdapter.Holder>(), DragSelectAdapter {

    companion object {
        private const val FLASH_WINDOW_MS = 6000L
        private const val FLASH_IN_MS = 250L
        private const val FLASH_HOLD_MS = 800L
        private const val FLASH_OUT_MS = 1450L
        private const val FLASH_TOTAL_MS = FLASH_IN_MS + FLASH_HOLD_MS + FLASH_OUT_MS

        private val DIFF = object : DiffUtil.ItemCallback<MessageRow>() {
            override fun areItemsTheSame(a: MessageRow, b: MessageRow) = a.id == b.id
            override fun areContentsTheSame(a: MessageRow, b: MessageRow): Boolean {
                if (!a.fromMe && a.isRead != b.isRead) return a == b.copy(isRead = a.isRead)
                return a == b
            }
        }
    }

    fun indexOfMessage(msgId: String): Int = messages.indexOfFirst { it.id == msgId }

    private val selection = Selection<MessageRow>(
        rows = { messages }, idOf = { it.id },
        notifyAt = { notifyItemChanged(it) }, onChanged = { onSelectionChanged() },
    )

    val selectionMode: Boolean get() = selection.active

    fun selectedCount(): Int = selection.count

    fun selectedMessages(): List<MessageRow> {
        val byId = rowsById ?: messages.associateBy { it.id }.also { rowsById = it }
        val order = orderById ?: HashMap<String, Int>(messages.size).also { m ->
            for (i in messages.indices) m[messages[i].id] = i
            orderById = m
        }
        return selection.values.map { byId[it.id] ?: it }
            .sortedWith(compareBy({ it.timeSent }, { order[it.id] ?: Int.MAX_VALUE }))
    }

    private var rowsById: Map<String, MessageRow>? = null
    private var orderById: Map<String, Int>? = null

    private fun isSelected(msg: MessageRow): Boolean = msg.id in selection

    fun startSelection(msg: MessageRow) { selection.start(msg) }

    fun toggleSelection(msg: MessageRow) = selection.toggle(msg)

    fun clearSelection() = selection.clear()

    override fun setSelectedAt(pos: Int, sel: Boolean) = selection.setSelectedAt(pos, sel)

    override fun commitDragSelection() = selection.commitDragSelection()

    override fun snapshotSelection(): Set<String> = selection.snapshotSelection()

    override fun idAt(pos: Int): String = selection.idAt(pos)

    private val differ = AsyncListDiffer(this, DIFF)
    private val messages: List<MessageRow> get() = differ.currentList
    private var submitted: List<MessageRow> = emptyList()
    private var names: Map<String, String> = emptyMap()

    var seekDragging = false
        private set
    var highlightQuery: String = ""
        set(value) {
            field = value
            foldedQuery = Search.fold(value)
        }
    private var foldedQuery: String = ""
    var flashMsgId: String = ""
        set(value) {
            field = value
            flashArmedAt = android.os.SystemClock.uptimeMillis()
            flashPlayedAt = 0L
        }
    private var flashArmedAt = 0L
    private var flashPlayedAt = 0L

    data class QuotedPreview(val name: String, val text: String, val msgType: String)

    fun submit(
        newMessages: List<MessageRow>,
        newNames: Map<String, String>,
        newQuoteNames: Map<String, QuotedPreview> = emptyMap(),
        onCommitted: (() -> Unit)? = null,
    ) {
        val hadRows = differ.currentList.isNotEmpty()
        val namesChanged = hadRows && newNames != names
        val staleQuotes = if (hadRows && !namesChanged) {
            (newQuoteNames.keys + quoteNames.keys)
                .filterTo(HashSet()) { newQuoteNames[it] != quoteNames[it] }
        } else {
            emptySet()
        }
        names = newNames
        quoteNames = newQuoteNames
        submitted = newMessages
        differ.submitList(newMessages) {
            rowsById = null
            orderById = null
            if (namesChanged && itemCount > 0) {
                notifyItemRangeChanged(0, itemCount)
            } else if (staleQuotes.isNotEmpty()) {
                messages.forEachIndexed { pos, m ->
                    if (m.quotedId in staleQuotes) notifyItemChanged(pos)
                }
            }
            onCommitted?.invoke()
        }
    }

    fun messagesSnapshot(): List<MessageRow> = messages

    fun refreshRows(fresh: Map<String, MessageRow>) {
        if (fresh.isEmpty() || submitted.isEmpty()) return
        if (submitted.none { it.id in fresh }) return
        submitted = submitted.map { fresh[it.id] ?: it }
        differ.submitList(submitted) { rowsById = null; orderById = null }
    }

    fun refreshAudioRows(recycler: RecyclerView) {
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? Holder ?: continue
            val msg = holder.current ?: continue
            if (msg.msgType != "audio") continue
            applyAudioState(holder, msg)
        }
    }

    private fun applyAudioState(holder: Holder, msg: MessageRow) {
        val current = AudioPlayer.currentMsgId == msg.id && AudioPlayer.currentChatId == msg.chatId
        val downloading = isDownloading(msg)
        holder.audioButton.visibility = if (downloading) View.GONE else View.VISIBLE
        holder.audioSpinner.visibility = if (downloading) View.VISIBLE else View.GONE
        val icon = when {
            msg.filePath.isEmpty() -> R.drawable.ic_download
            current && AudioPlayer.isPlaying -> R.drawable.ic_pause
            else -> R.drawable.ic_play
        }
        if (holder.audioIconRes != icon) {
            holder.audioIconRes = icon
            holder.audioButton.setImageResource(icon)
            holder.audioButtonFrame.contentDescription =
                holder.audioButton.context.getString(
                    when (icon) {
                        R.drawable.ic_download -> R.string.download
                        R.drawable.ic_pause -> R.string.pause
                        else -> R.string.play
                    }
                )
        }
        val speedLabel = speedLabel(AudioPlayer.speed)
        if (holder.audioSpeed.text != speedLabel) holder.audioSpeed.text = speedLabel
        val duration = if (current) fmtSecs(AudioPlayer.positionMs) else msg.text
        if (holder.audioDuration.text != duration) holder.audioDuration.text = duration
        if (!seekDragging) {
            holder.audioSeek.max =
                if (current) maxOf(AudioPlayer.durationMs, 1) else maxOf(parseDurationMs(msg.text), 1)
            holder.audioSeek.progress = if (current) AudioPlayer.positionMs else 0
        }
    }

    private var quoteNames: Map<String, QuotedPreview> = emptyMap()

    private val downloadPct = HashMap<String, Int>()

    fun setDownloadProgress(recycler: RecyclerView, msgId: String, pct: Int) {
        downloadPct[msgId] = pct
        refreshDownloadState(recycler, msgId)
    }

    fun refreshDownloadState(recycler: RecyclerView, msgId: String) {
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? Holder ?: continue
            val msg = holder.current ?: continue
            if (msg.id != msgId || msg.filePath.isNotEmpty()) continue
            when (msg.msgType) {
                in VIDEO_TYPES -> applyVideoState(holder, msg)
                "audio" -> applyAudioState(holder, msg)
                "document" -> applyDocumentState(holder, msg)
                in PICTURE_TYPES -> applyImageState(holder, msg)
            }
        }
    }

    private fun isDownloading(msg: MessageRow): Boolean {
        val live = msg.filePath.isEmpty() &&
            (Bridge.isDownloading(msg.chatId, msg.id) || msg.fileStatus == 1)
        if (!live) downloadPct.remove(msg.id)
        return live
    }

    private fun applyImageState(holder: Holder, msg: MessageRow) {
        holder.imageSpinner.visibility = if (isDownloading(msg)) View.VISIBLE else View.GONE
    }

    private fun applyDocumentState(holder: Holder, msg: MessageRow) {
        val ctx = holder.text.context
        holder.text.text = highlighted(ctx, "📎 " + msg.text + downloadSuffix(ctx, msg))
    }

    private fun applyVideoState(holder: Holder, msg: MessageRow) {
        val downloaded = msg.filePath.isNotEmpty()
        val pct = downloadPct[msg.id]
        val downloading = isDownloading(msg)
        holder.videoIcon.visibility = if (downloading) View.GONE else View.VISIBLE
        holder.videoIcon.setImageResource(if (downloaded) R.drawable.ic_play else R.drawable.ic_download)
        holder.videoButton.contentDescription = holder.videoButton.context.getString(
            if (downloaded) R.string.play else R.string.download
        )
        holder.videoProgress.visibility =
            if (downloading && pct != null) View.VISIBLE else View.GONE
        holder.videoSpinner.visibility =
            if (downloading && pct == null) View.VISIBLE else View.GONE
        if (pct != null) holder.videoProgress.progress = pct
    }

    private fun maybeAutoDownload(msg: MessageRow) {
        val gone = msg.filePath.isNotEmpty() && !File(msg.filePath).exists()
        val pending = msg.fileStatus == 0 || msg.fileStatus == 1 || msg.fileStatus == 3
        if (gone || (msg.filePath.isEmpty() && pending)) onNeedDownload(msg, false)
    }

    private fun fmtSecs(ms: Int): String = TimeFormat.mmss(ms / 1000)

    private fun speedLabel(speed: Float): String = when (speed) {
        1.5f -> "1.5×"
        2f -> "2×"
        else -> "1×"
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val dateHeader: TextView = view.findViewById(R.id.dateHeader)
        val row: FrameLayout = view.findViewById(R.id.messageRow)
        val bubble: LinearLayout = view.findViewById(R.id.bubble)
        val senderName: TextView = view.findViewById(R.id.senderName)
        val imageFrame: FrameLayout = view.findViewById(R.id.imageFrame)
        val image: ImageView = view.findViewById(R.id.messageImage)
        val imageTime: TextView = view.findViewById(R.id.imageTime)
        val imageSpinner: android.widget.ProgressBar = view.findViewById(R.id.imageSpinner)
        val forwardedLabel: TextView = view.findViewById(R.id.forwardedLabel)
        val quotePreview: TextView = view.findViewById(R.id.quotePreview)
        val audioRow: LinearLayout = view.findViewById(R.id.audioRow)
        val audioMeta: LinearLayout = view.findViewById(R.id.audioMeta)
        val audioButtonFrame: View = view.findViewById(R.id.audioButtonFrame)
        val audioButton: ImageView = view.findViewById(R.id.audioButton)
        val audioSpinner: android.widget.ProgressBar = view.findViewById(R.id.audioSpinner)
        val audioSeek: SeekBar = view.findViewById(R.id.audioSeek)
        val audioDuration: TextView = view.findViewById(R.id.audioDuration)
        val audioUnplayedDot: View = view.findViewById(R.id.audioUnplayedDot)
        val audioSpeed: TextView = view.findViewById(R.id.audioSpeed)
        val videoRow: LinearLayout = view.findViewById(R.id.videoRow)
        val videoButton: View = view.findViewById(R.id.videoButton)
        val videoIcon: ImageView = view.findViewById(R.id.videoIcon)
        val videoProgress: android.widget.ProgressBar = view.findViewById(R.id.videoProgress)
        val videoSpinner: android.widget.ProgressBar = view.findViewById(R.id.videoSpinner)
        val videoLabel: TextView = view.findViewById(R.id.videoLabel)
        val text: TextView = view.findViewById(R.id.messageText)
        val time: TextView = view.findViewById(R.id.messageTime)
        val contactCard: LinearLayout = view.findViewById(R.id.contactCard)
        val contactBody: TextView = view.findViewById(R.id.contactBody)
        val contactActions: LinearLayout = view.findViewById(R.id.contactActions)
        val contactMessageBtn: TextView = view.findViewById(R.id.contactMessageBtn)
        val contactAddBtn: TextView = view.findViewById(R.id.contactAddBtn)
        val linkPreview: LinearLayout = view.findViewById(R.id.linkPreview)
        val linkSite: TextView = view.findViewById(R.id.linkSite)
        val linkTitle: TextView = view.findViewById(R.id.linkTitle)
        val linkDescription: TextView = view.findViewById(R.id.linkDescription)
        val linkImage: ImageView = view.findViewById(R.id.linkImage)
        val reactionPill: TextView = view.findViewById(R.id.reactionPill)
        val sendFailedBadge: ImageView = view.findViewById(R.id.sendFailedBadge)
        var flashFade: Runnable? = null
        var current: MessageRow? = null
        internal val linkMovement = LinkPressMovement()
        var quoteDelegateRect: android.graphics.Rect? = null
        var audioIconRes: Int = 0
        var bubbleGravity: Int = -1
        var pillGravity: Int = -1
        var imageFrameBottomMargin: Int = -1
        var cappedForWidth: Int = -1
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        ImageLoader.clearAnimating(holder.image)
    }

    private fun applyWidthCaps(holder: Holder) {
        val metrics = holder.itemView.resources.displayMetrics
        val maxWidth = (metrics.widthPixels * 0.78f).toInt()
        if (holder.cappedForWidth == maxWidth) return
        holder.cappedForWidth = maxWidth
        holder.text.maxWidth = maxWidth
        holder.senderName.maxWidth = maxWidth
        holder.quotePreview.maxWidth = maxWidth
        holder.image.maxWidth = maxWidth
        holder.image.maxHeight = (metrics.heightPixels * 0.5f).toInt()
        val cardWidth = maxWidth - (24 * metrics.density).toInt()
        holder.linkSite.maxWidth = cardWidth
        holder.linkTitle.maxWidth = cardWidth
        holder.linkDescription.maxWidth = cardWidth
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            val oldWidth = oldRight - oldLeft
            if (oldWidth == 0 || right - left == oldWidth) return@addOnLayoutChangeListener
            v.post { if (itemCount > 0) notifyDataSetChanged() }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        val holder = Holder(view)
        val radius = 7f * parent.resources.displayMetrics.density
        holder.image.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        holder.image.clipToOutline = true


        fun tappedRow(retryIfFailed: Boolean = true): MessageRow? {
            val m = holder.current
            if (selectionMode) {
                m?.let { toggleSelection(it) }
                return null
            }
            if (retryIfFailed && m != null && m.sendFailed) {
                onRetrySend(m)
                return null
            }
            return m
        }
        fun playableWhileFailed(h: Holder): Boolean {
            val m = h.current ?: return false
            return m.sendFailed && Bridge.fileOnDisk(m)
        }
        holder.sendFailedBadge.setOnClickListener {
            val m = holder.current ?: return@setOnClickListener
            if (selectionMode) toggleSelection(m) else onRetrySend(m)
        }
        val openActions = View.OnClickListener {
            tappedRow(retryIfFailed = false)?.let(onMessageActions)
        }
        val routeByType = View.OnClickListener {
            val m = tappedRow() ?: return@OnClickListener
            when (m.msgType) {
                "document" -> onDocumentClick(m)
                in VIDEO_TYPES -> onVideoOpen(m)
                "location" -> onLocationClick(m)
                else -> onMessageActions(m)
            }
        }
        val longPress = View.OnLongClickListener {
            val m = holder.current ?: return@OnLongClickListener true
            startSelection(m)
            onDragArm()
            true
        }
        holder.linkMovement.selectionActive = { selectionMode }
        holder.itemView.setOnClickListener(openActions)
        holder.itemView.setOnLongClickListener(longPress)
        holder.bubble.setOnClickListener(routeByType)
        holder.contactMessageBtn.setOnClickListener {
            tappedRow()?.let(onContactMessage)
        }
        holder.contactAddBtn.setOnClickListener {
            tappedRow()?.let(onContactClick)
        }
        holder.contactMessageBtn.setOnLongClickListener(longPress)
        holder.contactAddBtn.setOnLongClickListener(longPress)
        holder.bubble.setOnLongClickListener(longPress)
        val link = holder.linkMovement
        holder.text.movementMethod = link
        holder.text.setOnClickListener {
            if (link.openedLink) {
                link.openedLink = false
            } else {
                routeByType.onClick(it)
            }
        }
        holder.text.setOnLongClickListener {
            val url = link.pressedLink
            if (!selectionMode && url != null) {
                holder.text.context.copyToClipboard("url", url.url, R.string.link_copied)
                link.consumeUp = true
                holder.text.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            } else {
                val m = holder.current ?: return@setOnLongClickListener true
                startSelection(m)
                onDragArm()
            }
            true
        }
        holder.image.setOnLongClickListener(longPress)
        holder.audioRow.setOnLongClickListener(longPress)
        holder.image.setOnClickListener {
            val m = tappedRow() ?: return@setOnClickListener
            if (Bridge.fileOnDisk(m)) {
                onImageClick(m)
            } else {
                onNeedDownload(m, true)
                applyImageState(holder, m)
            }
        }
        holder.quotePreview.setOnClickListener {
            tappedRow(retryIfFailed = false)?.let(onQuoteClick)
        }
        holder.quotePreview.setOnLongClickListener(longPress)
        holder.linkPreview.setOnClickListener {
            tappedRow(retryIfFailed = false) ?: return@setOnClickListener
            (holder.linkPreview.tag as? String)?.let(onLinkPreviewClick)
        }
        holder.linkPreview.setOnLongClickListener(longPress)
        holder.bubble.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val quote = holder.quotePreview
            if (quote.visibility == View.VISIBLE) {
                val hit = android.graphics.Rect()
                quote.getHitRect(hit)
                hit.left = 0
                hit.right = v.width
                if (hit != holder.quoteDelegateRect) {
                    holder.quoteDelegateRect = hit
                    v.touchDelegate = android.view.TouchDelegate(hit, quote)
                }
            } else if (holder.quoteDelegateRect != null) {
                holder.quoteDelegateRect = null
                v.touchDelegate = null
            }
        }
        holder.videoButton.setOnClickListener {
            tappedRow()?.let(onDocumentClick)
        }
        holder.videoButton.setOnLongClickListener(longPress)
        holder.videoRow.setOnClickListener {
            tappedRow()?.let(onVideoOpen)
        }
        holder.videoRow.setOnLongClickListener(longPress)
        holder.audioButtonFrame.setOnClickListener {
            val m = tappedRow(retryIfFailed = !playableWhileFailed(holder))
                ?: return@setOnClickListener
            if (Bridge.fileOnDisk(m)) {
                AudioPlayer.playPause(m.filePath, m.chatId, m.id)
            } else {
                onNeedDownload(m, true)
                applyAudioState(holder, m)
            }
        }
        holder.audioButtonFrame.setOnLongClickListener(longPress)
        holder.reactionPill.setOnClickListener {
            tappedRow(retryIfFailed = false)?.let(onReactionsClick)
        }
        holder.reactionPill.setOnLongClickListener(longPress)
        holder.audioSpeed.setOnClickListener { AudioPlayer.cycleSpeed() }
        holder.audioSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { seekDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekDragging = false
                val m = tappedRow(retryIfFailed = !playableWhileFailed(holder)) ?: return
                if (AudioPlayer.currentMsgId == m.id) {
                    AudioPlayer.seekTo(sb?.progress ?: 0)
                } else if (Bridge.fileOnDisk(m)) {
                    AudioPlayer.play(m.filePath, m.chatId, m.id, sb?.progress ?: 0)
                } else {
                    onNeedDownload(m, true)
                    applyAudioState(holder, m)
                }
            }
        })
        return holder
    }

    override fun getItemCount(): Int = messages.size

    private fun highlighted(ctx: android.content.Context, raw: String): CharSequence {
        val styled = Markup.render(resolveMentions(raw, names))
        val full = styled.toString()
        val q = foldedQuery
        if (q.isEmpty() || q.length > full.length) return styled
        var idx = Search.indexOf(full, q)
        if (idx < 0) return styled
        val sp = SpannableString(styled)
        val bg = ctx.themeColor(R.attr.chatAccent)
        while (idx >= 0) {
            val end = idx + q.length
            sp.setSpan(android.text.style.BackgroundColorSpan(bg), idx, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(HighlightSpan(), idx, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            idx = Search.indexOf(full, q, end)
        }
        return sp
    }

    private fun flashRow(holder: Holder, ctx: android.content.Context) {
        if (flashPlayedAt == 0L) flashPlayedAt = android.os.SystemClock.uptimeMillis()
        val start = flashPlayedAt
        if (android.os.SystemClock.uptimeMillis() - start >= FLASH_TOTAL_MS) {
            flashMsgId = ""
            return
        }
        val color = ctx.themeColor(R.attr.chatQuoteFlash)
        val maxAlpha = color ushr 24
        val rgb = color and 0x00FFFFFF
        val view = holder.itemView
        val tick = object : Runnable {
            override fun run() {
                if (holder.flashFade !== this) return
                val t = android.os.SystemClock.uptimeMillis() - start
                val alpha = when {
                    t < FLASH_IN_MS -> maxAlpha * t / FLASH_IN_MS
                    t < FLASH_IN_MS + FLASH_HOLD_MS -> maxAlpha.toLong()
                    t < FLASH_TOTAL_MS -> maxAlpha * (FLASH_TOTAL_MS - t) / FLASH_OUT_MS
                    else -> 0L
                }
                view.setBackgroundColor((alpha.toInt() shl 24) or rgb)
                if (t < FLASH_TOTAL_MS) {
                    view.postOnAnimation(this)
                } else {
                    holder.flashFade = null
                    if (flashPlayedAt == start) flashMsgId = ""
                }
            }
        }
        holder.flashFade = tick
        view.postOnAnimation(tick)
    }

    private fun downloadSuffix(ctx: android.content.Context, msg: MessageRow): String = when {
        msg.filePath.isNotEmpty() -> ""
        isDownloading(msg) -> {
            val pct = downloadPct[msg.id]
            " — " + if (pct == null) ctx.getString(R.string.downloading)
            else ctx.getString(R.string.downloading_pct, pct)
        }
        msg.fileStatus == 3 -> " — " + ctx.getString(R.string.download_failed)
        else -> ""
    }

    private fun reactionSummary(csv: String): String {
        var total = 0
        val emojis = LinkedHashSet<String>()
        for (row in csv.split(',')) {
            if (row.isEmpty()) continue
            val digits = row.takeLastWhile { it.isDigit() }
            val emoji = row.dropLast(digits.length)
            if (emoji.isEmpty()) continue
            emojis.add(emoji)
            total += digits.toIntOrNull() ?: 1
        }
        val shown = emojis.take(3).joinToString("")
        return if (total > 1) "$shown $total" else shown
    }

    private fun emojiOnlyCount(text: String): Int {
        val s = text.trim()
        if (s.isEmpty() || s.length > 40) return 0
        var count = 0
        var joined = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            when {
                cp == 0x200D -> joined = true
                cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF -> {}
                cp in 0x1F1E6..0x1F1FF -> {
                    count++
                    val next = i + n
                    if (next < s.length && s.codePointAt(next) in 0x1F1E6..0x1F1FF) {
                        i = next + Character.charCount(s.codePointAt(next))
                        continue
                    }
                }
                cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
                    cp in 0x2B1B..0x2B1C || cp == 0x2B50 || cp == 0x2B55 ||
                    cp in 0x25AA..0x25FE || cp in 0x3297..0x3299 -> {
                    if (joined) joined = false else count++
                }
                cp == 0x23 || cp == 0x2A || cp in 0x30..0x39 -> {
                    var j = i + n
                    if (j < s.length && s.codePointAt(j) == 0xFE0F) j += 1
                    if (j < s.length && s.codePointAt(j) == 0x20E3) {
                        count++
                        i = j + 1
                        continue
                    }
                    return 0
                }
                Character.isWhitespace(cp) -> {}
                else -> return 0
            }
            i += n
        }
        return count
    }

    private fun parseDurationMs(text: String): Int = TimeFormat.parseSeconds(text) * 1000

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val msg = messages[position]
        val ctx = holder.itemView.context
        holder.current = msg
        applyWidthCaps(holder)

        holder.row.setBackgroundColor(
            if (isSelected(msg)) ctx.themeColor(R.attr.chatMsgSelected)
            else android.graphics.Color.TRANSPARENT
        )

        holder.flashFade?.let { holder.itemView.removeCallbacks(it) }
        holder.flashFade = null
        holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val flashAge = android.os.SystemClock.uptimeMillis() - flashArmedAt
        if (msg.id == flashMsgId && flashAge < FLASH_WINDOW_MS) {
            flashRow(holder, ctx)
        }

        val newDay = position == 0 || !TimeFormat.sameDay(messages[position - 1].timeSent, msg.timeSent)
        if (newDay) {
            holder.dateHeader.visibility = View.VISIBLE
            holder.dateHeader.text = TimeFormat.dateSeparator(ctx, msg.timeSent)
        } else {
            holder.dateHeader.visibility = View.GONE
        }

        val clock = TimeFormat.clock(msg.timeSent)
        val timeStr = if (msg.edited)
            ctx.getString(R.string.edited_time, ctx.getString(R.string.edited), clock) else clock
        val sticker = msg.msgType == "sticker"
        val overlayTime = msg.msgType == "image" && msg.text.isEmpty()
        val bareSticker = sticker && msg.text.isEmpty() &&
            msg.quotedId.isEmpty() && msg.quotedText.isEmpty() && !msg.forwarded &&
            !(isGroup && !msg.fromMe)
        holder.time.visibility = if (overlayTime) View.GONE else View.VISIBLE
        holder.imageTime.visibility = if (overlayTime) View.VISIBLE else View.GONE
        val timeView = if (overlayTime) holder.imageTime else holder.time
        timeView.text = if (msg.fromMe) {
            Ticks.timeWithTick(
                ctx, timeStr, msg.isRead, timeView.textSize, tickFirst = false,
                pending = msg.sendPending || msg.sendFailed,
            )
        } else {
            timeStr
        }
        holder.sendFailedBadge.visibility = if (msg.sendFailed) View.VISIBLE else View.GONE

        val params = holder.bubble.layoutParams as FrameLayout.LayoutParams
        val gravity: Int
        if (msg.fromMe) {
            holder.bubble.setBackgroundResource(R.drawable.bubble_out)
            gravity = Gravity.END
            holder.senderName.visibility = View.GONE
        } else {
            holder.bubble.setBackgroundResource(R.drawable.bubble_in)
            gravity = Gravity.START
            if (isGroup) {
                holder.senderName.visibility = View.VISIBLE
                holder.senderName.text = senderLabel(names, msg.senderId, msg.senderName)
            } else {
                holder.senderName.visibility = View.GONE
            }
        }
        if (holder.bubbleGravity != gravity) {
            holder.bubbleGravity = gravity
            params.gravity = gravity
            holder.bubble.layoutParams = params
        }

        holder.imageFrame.visibility = View.GONE
        if (msg.msgType !in PICTURE_TYPES) ImageLoader.clearAnimating(holder.image)
        holder.audioRow.visibility = View.GONE
        holder.audioMeta.visibility = View.GONE
        holder.videoRow.visibility = View.GONE
        holder.contactActions.visibility = View.GONE
        holder.contactCard.visibility = View.GONE
        holder.text.visibility = View.VISIBLE
        holder.text.textSize = 15f
        val density = ctx.resources.displayMetrics.density
        val padH: Int; val padV: Int
        if (msg.msgType in PICTURE_TYPES) {
            val slim = if (bareSticker) 0 else (4 * density).toInt()
            padH = slim; padV = slim
        } else {
            padH = (10 * density).toInt(); padV = (6 * density).toInt()
        }
        holder.bubble.setPadding(padH, padV, padH, padV)
        val capPad = if (msg.msgType in PICTURE_TYPES) (6 * density).toInt() else 0
        holder.text.setPadding(capPad, if (capPad > 0) (2 * density).toInt() else 0, capPad, 0)

        holder.forwardedLabel.visibility = if (msg.forwarded) View.VISIBLE else View.GONE

        if (msg.reactions.isEmpty()) {
            holder.reactionPill.visibility = View.GONE
        } else {
            holder.reactionPill.visibility = View.VISIBLE
            holder.reactionPill.text = reactionSummary(msg.reactions)
            val pillGravity = if (msg.fromMe) Gravity.END else Gravity.START
            if (holder.pillGravity != pillGravity) {
                holder.pillGravity = pillGravity
                val pillParams = holder.reactionPill.layoutParams as LinearLayout.LayoutParams
                pillParams.gravity = pillGravity
                holder.reactionPill.layoutParams = pillParams
            }
        }

        if (msg.quotedText.isNotEmpty() || msg.quotedId.isNotEmpty()) {
            holder.quotePreview.visibility = View.VISIBLE
            val stored = quoteNames[msg.quotedId]
            val text = msg.quotedText.ifEmpty { stored?.text.orEmpty() }
            val type = msg.quotedType.ifEmpty { stored?.msgType.orEmpty() }
            val body = Markup.render(
                previewLabel(ctx, type, resolveMentions(text, names), emoji = false)
                    .ifEmpty { ctx.getString(R.string.message_label) }
            )
            val name = stored?.name.orEmpty()
            holder.quotePreview.text = if (name.isEmpty()) body else {
                val sp = SpannableString(TextUtils.concat(name, "\n", body))
                sp.setSpan(ForegroundColorSpan(ctx.themeColor(R.attr.chatAccent)), 0, name.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    0, name.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                sp
            }
        } else {
            holder.quotePreview.visibility = View.GONE
        }

        when (msg.msgType) {
            in PICTURE_TYPES -> {
                holder.imageFrame.visibility = View.VISIBLE
                val bottomMargin = if (overlayTime) 0 else (2 * density).toInt()
                if (holder.imageFrameBottomMargin != bottomMargin) {
                    holder.imageFrameBottomMargin = bottomMargin
                    val frameLp = holder.imageFrame.layoutParams as LinearLayout.LayoutParams
                    frameLp.bottomMargin = bottomMargin
                    holder.imageFrame.layoutParams = frameLp
                }
                if (bareSticker) holder.bubble.background = null
                ImageLoader.load(msg, holder.image)
                maybeAutoDownload(msg)
                applyImageState(holder, msg)
                if (msg.text.isEmpty()) holder.text.visibility = View.GONE
                else holder.text.text = highlighted(ctx, msg.text)
            }
            "audio" -> {
                holder.audioRow.visibility = View.VISIBLE
                holder.audioMeta.visibility = View.VISIBLE
                holder.text.visibility = View.GONE
                holder.audioUnplayedDot.visibility = if (msg.played) View.GONE else View.VISIBLE
                holder.audioRow.minimumWidth = holder.text.maxWidth
                holder.audioSeek.isEnabled = msg.filePath.isNotEmpty()
                maybeAutoDownload(msg)
                applyAudioState(holder, msg)
            }
            "document" -> applyDocumentState(holder, msg)
            in VIDEO_TYPES -> {
                holder.text.visibility = View.GONE
                holder.videoRow.visibility = View.VISIBLE
                val label = msg.text.ifEmpty { ctx.getString(R.string.video_label) }
                holder.videoLabel.text = highlighted(ctx, label)
                applyVideoState(holder, msg)
            }
            "location" -> {
                val label = msg.text.ifEmpty { ctx.getString(R.string.location_label) }
                holder.text.text = highlighted(ctx, "📍 $label")
            }
            "contact" -> {
                if (msg.text.isEmpty()) {
                    holder.text.text = previewLabel(ctx, msg.msgType, "", emoji = true)
                } else {
                    holder.text.visibility = View.GONE
                    holder.contactCard.visibility = View.VISIBLE
                    holder.contactBody.text = highlighted(ctx, msg.text)
                    holder.contactActions.visibility = View.VISIBLE
                }
            }
            in LABEL_ONLY_TYPES -> holder.text.text =
                previewLabel(ctx, msg.msgType, "", emoji = true)
            else -> {
                holder.text.text = highlighted(ctx, msg.text)
                val emojiCount =
                    if (msg.quotedId.isEmpty() && msg.quotedText.isEmpty()) emojiOnlyCount(msg.text)
                    else 0
                when (emojiCount) {
                    1 -> holder.text.textSize = 42f
                    2 -> holder.text.textSize = 34f
                    3 -> holder.text.textSize = 26f
                }
                if (emojiCount in 1..3) holder.bubble.background = null
            }
        }

        val linkable = holder.text.visibility == View.VISIBLE && mayContainUrl(holder.text.text)
        if (linkable) {
            android.text.util.Linkify.addLinks(holder.text, android.text.util.Linkify.WEB_URLS)
            val sp = holder.text.text as? Spannable
            if (sp != null) for (s in sp.getSpans(0, sp.length, HighlightSpan::class.java)) {
                val start = sp.getSpanStart(s)
                val end = sp.getSpanEnd(s)
                sp.removeSpan(s)
                sp.setSpan(s, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        bindLinkPreview(holder, msg, linkable)
    }

    private fun bindLinkPreview(holder: Holder, msg: MessageRow, linkable: Boolean) {
        fun hide() {
            holder.linkPreview.visibility = View.GONE
            holder.linkPreview.tag = null
        }
        val url = if (linkable && msg.msgType.isEmpty()) urlOf(msg) else null
        if (url == null) {
            hide()
            return
        }
        val row = LinkPreview.cached(url)
        if (row == null) {
            hide()
            previewWaiters.getOrPut(url) { HashSet() }.add(msg.id)
            onNeedLinkPreview(url)
            return
        }
        previewWaiters[url]?.let {
            it.remove(msg.id)
            if (it.isEmpty()) previewWaiters.remove(url)
        }
        if (!row.hasPreview) {
            hide()
            return
        }
        holder.linkPreview.visibility = View.VISIBLE
        holder.linkPreview.tag = url
        bindPreviewLine(holder.linkSite, row.site)
        bindPreviewLine(holder.linkTitle, row.title)
        bindPreviewLine(holder.linkDescription, row.description)
        if (row.imagePath.isEmpty()) {
            holder.linkImage.visibility = View.GONE
            holder.linkImage.setImageDrawable(null)
        } else {
            holder.linkImage.visibility = View.VISIBLE
            LinkPreview.loadImage(row.imagePath, holder.linkImage, holder.linkTitle.maxWidth)
        }
    }

    private fun bindPreviewLine(view: TextView, text: String) {
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (text.isNotEmpty()) view.text = text
    }

    private val urlCache = LruCache<String, String>(512)

    private fun urlOf(msg: MessageRow): String? {
        urlCache.get(msg.text)?.let { return it.ifEmpty { null } }
        val found = LinkPreview.firstUrl(msg.text)
        urlCache.put(msg.text, found.orEmpty())
        return found
    }

    private val previewWaiters = HashMap<String, MutableSet<String>>()

    fun onLinkPreviewReady(url: String) {
        val waiting = previewWaiters.remove(url) ?: return
        for (id in waiting) {
            val i = indexOfMessage(id)
            if (i >= 0) notifyItemChanged(i)
        }
    }

    private fun mayContainUrl(text: CharSequence?): Boolean {
        if (text == null) return false
        for (i in text.indices) if (text[i] == '.') return true
        return false
    }
}

internal class HighlightSpan : ForegroundColorSpan(0xFFFFFFFF.toInt())

internal class LinkPressMovement : android.text.method.LinkMovementMethod() {
    var pressedLink: android.text.style.URLSpan? = null
        private set
    var consumeUp = false
    var openedLink = false
    var selectionActive: () -> Boolean = { false }

    private fun linkAt(
        widget: TextView, buffer: Spannable, event: android.view.MotionEvent,
    ): android.text.style.URLSpan? {
        val layout = widget.layout ?: return null
        val y = event.y.toInt() - widget.totalPaddingTop + widget.scrollY
        if (y < 0 || y >= layout.height) return null
        val line = layout.getLineForVertical(y)
        val x = (event.x.toInt() - widget.totalPaddingLeft + widget.scrollX).toFloat()
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val off = layout.getOffsetForHorizontal(line, x)
        return buffer.getSpans(off, off, android.text.style.URLSpan::class.java).firstOrNull()
    }

    override fun onTouchEvent(
        widget: TextView, buffer: Spannable, event: android.view.MotionEvent,
    ): Boolean {
        if (selectionActive()) {
            pressedLink = null
            consumeUp = false
            openedLink = false
            return false
        }
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                pressedLink = linkAt(widget, buffer, event)
                consumeUp = false
                openedLink = false
            }
            android.view.MotionEvent.ACTION_UP -> {
                val eat = consumeUp
                consumeUp = false
                val link = pressedLink
                pressedLink = null
                if (eat) return true
                if (link != null && linkAt(widget, buffer, event) === link) {
                    openedLink = true
                    link.onClick(widget)
                    return true
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                pressedLink = null
                consumeUp = false
                openedLink = false
            }
        }
        return false
    }
}

object ImageLoader {
    private val cache = newBitmapCache(12)
    private val animCache = object : LruCache<String, AnimatedImageDrawable>(8) {
        override fun entryRemoved(
            evicted: Boolean, key: String, oldValue: AnimatedImageDrawable,
            newValue: AnimatedImageDrawable?,
        ) {
            if (oldValue.callback == null) oldValue.stop()
        }
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private data class Tag(val path: String, val msgId: String)

    private val waiting = PendingViews<Boolean>()

    private fun stillOn(view: ImageView, path: String) = (view.tag as? Tag)?.path == path

    private fun deliverBitmap(path: String, bitmap: Bitmap) {
        for (w in waiting.take(path)) {
            val view = w.view.get() ?: continue
            if (!stillOn(view, path)) continue
            clearAnimating(view)
            applyBounds(view, bitmap.width, bitmap.height, w.payload)
            view.setImageBitmap(bitmap)
        }
    }

    private fun deliverAnimated(path: String, drawable: AnimatedImageDrawable, targetPx: Int) {
        var served = false
        var claimed = false
        for (w in waiting.take(path)) {
            val view = w.view.get() ?: continue
            if (!stillOn(view, path)) continue
            if (served) {
                if (waiting.await(path, view, w.payload)) claimed = true
                continue
            }
            served = true
            clearAnimating(view)
            applyBounds(view, drawable.intrinsicWidth, drawable.intrinsicHeight, w.payload)
            view.setImageDrawable(drawable)
            drawable.start()
            animCache.put(path, drawable)
        }
        if (claimed) dispatchDecode(path, targetPx)
    }

    fun load(msg: MessageRow, imageView: ImageView) {
        val path = msg.filePath
        val sticker = msg.msgType == "sticker"
        val prev = imageView.tag as? Tag
        imageView.tag = Tag(path, msg.id)
        if (path.isEmpty()) {
            clearAnimating(imageView)
            applyBounds(imageView, 0, 0, sticker)
            imageView.setImageResource(R.drawable.image_placeholder)
            return
        }
        if (prev?.path == path && prev.msgId == msg.id) {
            (imageView.drawable as? AnimatedImageDrawable)?.let { anim ->
                if (!anim.isRunning) anim.start()
                return
            }
        }

        val cached = cache.get(path)
        if (cached != null) {
            clearAnimating(imageView)
            applyBounds(imageView, cached.width, cached.height, sticker)
            imageView.setImageBitmap(cached)
            return
        }
        val reuse = animCache.get(path)
        if (reuse != null && reuse.callback == null) {
            clearAnimating(imageView)
            applyBounds(imageView, reuse.intrinsicWidth, reuse.intrinsicHeight, sticker)
            imageView.setImageDrawable(reuse)
            reuse.start()
            return
        }
        if (prev?.msgId != msg.id) {
            clearAnimating(imageView)
            applyBounds(imageView, 0, 0, sticker)
            imageView.setImageResource(R.drawable.image_placeholder)
        }
        val targetPx = if (sticker) 512 else imageView.maxWidth.coerceAtLeast(512)
        if (waiting.await(path, imageView, sticker)) dispatchDecode(path, targetPx)
    }

    private fun dispatchDecode(path: String, targetPx: Int) {
        executor.execute {
            var delivering = false
            val queued = waiting.peek(path)
            try {
                if (queued.isEmpty()) return@execute
                val ready = cache.get(path)
                if (ready != null) {
                    delivering = true
                    main.post { deliverBitmap(path, ready) }
                    return@execute
                }
                val drawable = try {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                        val sample = sampleSize(info.size.width, info.size.height, targetPx)
                        if (sample > 1) decoder.setTargetSampleSize(sample)
                        if (!info.isAnimated) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } catch (e: Throwable) {
                    android.util.Log.w("ImageLoader", "decode failed for $path", e)
                    return@execute
                }
                if (drawable is BitmapDrawable) {
                    cache.put(path, drawable.bitmap)
                    delivering = true
                    main.post { deliverBitmap(path, drawable.bitmap) }
                } else if (drawable is AnimatedImageDrawable) {
                    delivering = true
                    main.post { deliverAnimated(path, drawable, targetPx) }
                }
            } finally {
                if (!delivering && waiting.settle(path, queued).isNotEmpty()) {
                    dispatchDecode(path, targetPx)
                }
            }
        }
    }

    private fun applyBounds(imageView: ImageView, w: Int, h: Int, sticker: Boolean) {
        val lp = imageView.layoutParams ?: return
        var tw = ViewGroup.LayoutParams.WRAP_CONTENT
        var th = ViewGroup.LayoutParams.WRAP_CONTENT
        if (!sticker && w > 0 && h > 0) {
            tw = imageView.maxWidth
            th = tw * h / w
            if (th > imageView.maxHeight) {
                th = imageView.maxHeight
                tw = th * w / h
            }
        }
        if (lp.width != tw || lp.height != th) {
            lp.width = tw
            lp.height = th
            imageView.layoutParams = lp
        }
    }

    fun clearAnimating(imageView: ImageView) {
        val d = imageView.drawable
        if (d is AnimatedImageDrawable) {
            d.stop()
            imageView.setImageDrawable(null)
        }
    }

    private fun sampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= maxDim || h / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    fun decodeAsync(path: String, maxDim: Int, onDone: (Bitmap?) -> Unit) {
        executor.execute {
            val bitmap = decodeSampled(path, maxDim)
            main.post { onDone(bitmap) }
        }
    }

    fun decodeSampled(path: String, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            }
            BitmapFactory.decodeFile(path, opts)
        } catch (e: Throwable) {
            android.util.Log.w("ImageLoader", "decodeSampled failed for $path", e)
            null
        }
    }
}
