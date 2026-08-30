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
) : RecyclerView.Adapter<MessageAdapter.Holder>() {

    companion object {
        private const val FLASH_WINDOW_MS = 6000L
        private const val FLASH_IN_MS = 250L
        private const val FLASH_HOLD_MS = 800L
        private const val FLASH_OUT_MS = 1450L
        private const val FLASH_TOTAL_MS = FLASH_IN_MS + FLASH_HOLD_MS + FLASH_OUT_MS

        private val DIFF = object : DiffUtil.ItemCallback<MessageRow>() {
            override fun areItemsTheSame(a: MessageRow, b: MessageRow) = a.id == b.id
            override fun areContentsTheSame(a: MessageRow, b: MessageRow): Boolean {
                // incoming rows never render read state (only outgoing show a
                // delivery tick), so a mark-read flip on entry shouldn't force
                // every unread incoming row to rebind
                if (!a.fromMe && a.isRead != b.isRead) return a == b.copy(isRead = a.isRead)
                return a == b
            }
        }
    }

    fun indexOfMessage(msgId: String): Int = messages.indexOfFirst { it.id == msgId }

    private val selected = LinkedHashMap<String, MessageRow>()
    var selectionMode = false
        private set

    fun selectedCount(): Int = selected.size

    fun selectedMessages(): List<MessageRow> {
        // Built once per list version instead of scanning the whole loaded window
        // on every call: this runs per drag-select row crossing, and in search
        // mode that window can hold thousands of rows.
        val byId = rowsById ?: messages.associateBy { it.id }.also { rowsById = it }
        return selected.values.map { byId[it.id] ?: it }.sortedBy { it.timeSent }
    }

    private var rowsById: Map<String, MessageRow>? = null

    private fun isSelected(msg: MessageRow): Boolean = msg.id in selected

    fun startSelection(msg: MessageRow) {
        selectionMode = true
        if (selected.put(msg.id, msg) == null) rebindRow(msg.id)
        onSelectionChanged()
    }

    fun toggleSelection(msg: MessageRow) {
        if (selected.remove(msg.id) == null) selected[msg.id] = msg
        rebindRow(msg.id)
        if (selected.isEmpty()) selectionMode = false
        onSelectionChanged()
    }

    fun clearSelection() {
        if (!selectionMode && selected.isEmpty()) return
        selectionMode = false
        val ids = HashSet(selected.keys)
        selected.clear()
        // one pass, not a linear indexOfMessage scan per id: clearing a bulk
        // drag-select in a search window ran millions of comparisons in a frame
        messages.forEachIndexed { i, m -> if (m.id in ids) notifyItemChanged(i) }
        onSelectionChanged()
    }

    private fun rebindRow(msgId: String) {
        val i = indexOfMessage(msgId)
        if (i >= 0) notifyItemChanged(i)
    }

    fun setSelectedAt(pos: Int, sel: Boolean): Boolean {
        val msg = messages.getOrNull(pos) ?: return false
        val changed = if (sel) selected.put(msg.id, msg) == null else selected.remove(msg.id) != null
        if (changed) notifyItemChanged(pos)
        return changed
    }

    fun commitDragSelection() {
        selectionMode = selected.isNotEmpty()
        onSelectionChanged()
    }

    fun snapshotSelection(): Set<String> = HashSet(selected.keys)

    fun messageIdAt(pos: Int): String = messages.getOrNull(pos)?.id ?: ""

    private val differ = AsyncListDiffer(this, DIFF)
    private val messages: List<MessageRow> get() = differ.currentList
    // AsyncListDiffer applies a submit asynchronously: refreshRows used to
    // resubmit differ.currentList, and while a submit was still diffing that
    // bumped the generation and silently dropped the newer list — new messages
    // vanished until the next full submit
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
        // A reply that arrives before the message it quotes renders "Message",
        // and when that message syncs in the reply row itself is unchanged, so
        // nothing else would ask for a redraw. The whole window used to redraw
        // for it: in a reply-heavy group that is every image reloaded and every
        // span rebuilt per incoming reply.
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
            // dropped here, not before submitList: the differ applies the new
            // list asynchronously, and selectedMessages() runs on every
            // selection change — in that window it would rebuild and cache the
            // map from the list that is still on its way out
            rowsById = null
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
        differ.submitList(submitted) { rowsById = null }
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
        // by message, not by path: one Telegram file can back several rows, and
        // matching on the path lit up every copy as "playing"
        val current = AudioPlayer.currentMsgId == msg.id && AudioPlayer.currentChatId == msg.chatId
        val downloading = isDownloading(msg)
        holder.audioButton.visibility = if (downloading) View.GONE else View.VISIBLE
        holder.audioSpinner.visibility = if (downloading) View.VISIBLE else View.GONE
        // setImageResource reloads the drawable even for an unchanged id, and this
        // runs 4x/second per visible row for the whole clip
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

    // A tap that starts a transfer changes nothing the list rebinds on — the
    // status write happens later, on the transport's own worker — so the row
    // must be told directly. Without this, only video showed anything, and that
    // only because progress callbacks kept arriving; every other type sat
    // unchanged until the file landed, so a second tap looked like the only way
    // to make it move.
    fun refreshDownloadState(recycler: RecyclerView, msgId: String) {
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? Holder ?: continue
            val msg = holder.current ?: continue
            if (msg.id != msgId || msg.filePath.isNotEmpty()) continue
            when (msg.msgType) {
                "video" -> applyVideoState(holder, msg)
                "audio" -> applyAudioState(holder, msg)
                "document" -> applyDocumentState(holder, msg)
                in PICTURE_TYPES -> applyImageState(holder, msg)
            }
        }
    }

    // Both signals, because neither covers the other: Bridge's claim is taken on
    // the tap, while file_status is written later on the transport's own worker
    // (so a status-only test showed nothing for the first moments after a tap),
    // and a search-window row carries a status with no claim behind it at all.
    private fun isDownloading(msg: MessageRow): Boolean {
        val live = msg.filePath.isEmpty() &&
            (Bridge.isDownloading(msg.chatId, msg.id) || msg.fileStatus == 1)
        // A percentage is only ever a label for a live transfer, never proof of
        // one: a download that reached 40% and then failed kept its entry, so
        // the row spun for good and a document read "Downloading 40%" where it
        // should have read "Download failed".
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

    // Retries a previously-failed download (fileStatus == 3): history media that
    // failed before the media-retry recovery existed would be stuck at that
    // status forever otherwise. Bridge throttles it to one retry per failure.
    private fun maybeAutoDownload(msg: MessageRow) {
        // A stored path outlives its file: our own Telegram sends reference the
        // cacheDir staging copy, which the daily sweep deletes after a day. The
        // row went on claiming "downloaded", so nothing here ever re-fetched it
        // and the bubble stayed blank for good. A vanished file is not a
        // download — Bridge drops the dead reference and fetches it again.
        val gone = msg.filePath.isNotEmpty() && !File(msg.filePath).exists()
        // Status 1 included: it survives a process death mid-transfer, and
        // skipping it left a row showing a spinner for a download that had
        // stopped existing, which nothing would ever restart. Bridge's in-flight
        // claim keeps this from disturbing a transfer that IS running.
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
        var flashFade: Runnable? = null
        var current: MessageRow? = null
        // one instance per holder, so a touch on another row can't clobber this
        // row's in-flight link press
        internal val linkMovement = LinkPressMovement()
        var quoteDelegateRect: android.graphics.Rect? = null
        var audioIconRes: Int = 0
        var bubbleGravity: Int = -1
        var pillGravity: Int = -1
        var imageFrameBottomMargin: Int = -1
        var cappedForWidth: Int = -1
    }

    // a long chat full of GIFs would otherwise keep dozens of decoders ticking
    // in the recycler pool
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
        // the card sits inside the padded bubble, so it caps slightly narrower
        // than the bubble itself or a long title would push the bubble wider
        // than every other one in the chat
        val cardWidth = maxWidth - (24 * metrics.density).toInt()
        holder.linkSite.maxWidth = cardWidth
        holder.linkTitle.maxWidth = cardWidth
        holder.linkDescription.maxWidth = cardWidth
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        // A rotation (or a multi-window resize) re-lays the list out without
        // rebinding it, so the visible rows would keep the previous width's caps
        // and their already-decoded images. Posted: a notify during a layout
        // pass is rejected outright.
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

        // Handlers are attached once here, not on each bind: each reads
        // holder.current, so no lambda is allocated per row per rebind.

        // Every row listener goes through this gate. The retry check used to be
        // copied into each listener and the audio seekbar was written without
        // it, which played back — and offered to download — a voice note that
        // never left the device. Only the taps that must still reach the actions
        // menu opt out.
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
        val openActions = View.OnClickListener {
            tappedRow(retryIfFailed = false)?.let(onMessageActions)
        }
        val routeByType = View.OnClickListener {
            val m = tappedRow() ?: return@OnClickListener
            when (m.msgType) {
                "document" -> onDocumentClick(m)
                "video" -> onVideoOpen(m)
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
            // a path whose file is gone opens an empty viewer: fetch instead
            if (Bridge.fileOnDisk(m)) {
                onImageClick(m)
            } else {
                onNeedDownload(m, true)
                // the tap's own row, repainted here: these two handlers do not
                // go through ChatActivity's download-with-toast path, so nothing
                // else showed the spinner until a progress callback landed
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
        // on the frame, not the icon: the icon is hidden while the spinner runs,
        // and a gone view takes no taps
        holder.audioButtonFrame.setOnClickListener {
            val m = tappedRow() ?: return@setOnClickListener
            // the stored path can be stale (a swept Telegram staging copy);
            // re-download instead of "playing" a file that is no longer there
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
                val m = tappedRow() ?: return
                if (AudioPlayer.currentMsgId == m.id) {
                    AudioPlayer.seekTo(sb?.progress ?: 0)
                } else if (Bridge.fileOnDisk(m)) {
                    AudioPlayer.play(m.filePath, m.chatId, m.id, sb?.progress ?: 0)
                } else {
                    // same stale-path rule as the play button: "playing" a swept
                    // staging copy silently did nothing
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
        // the markers are gone from the rendered text, so the search offsets
        // have to be taken against it and not against the stored message
        val full = styled.toString()
        val q = foldedQuery
        if (q.isEmpty() || q.length > full.length) return styled
        // Spans are set on the ORIGINAL string, and Search.fold is 1:1, so a
        // match offset means the same character in both. Folding `full` into a
        // lowercase copy and reusing those offsets breaks for any character
        // whose lowercase form has a different length (Turkish 'İ' U+0130
        // lowercases to two chars): every later offset shifted and setSpan ran
        // past the end of the Spannable — a crash while scrolling results.
        var idx = Search.indexOf(full, q)
        if (idx < 0) return styled
        val sp = SpannableString(styled)
        val bg = ctx.themeColor(R.attr.chatAccent)
        while (idx >= 0) {
            val end = idx + q.length
            sp.setSpan(android.text.style.BackgroundColorSpan(bg), idx, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(0xFFFFFFFF.toInt()), idx, end,
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

    /**
     * Telegram gives one row per reaction TYPE, with its own count baked into
     * the label ("👍3"); WhatsApp gives one row per person. Reading the count
     * off each row makes both add up to the number of people who reacted.
     */
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
                cp == 0x200D -> joined = true // ZWJ: the next emoji joins the previous one
                cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF -> {}
                cp in 0x1F1E6..0x1F1FF -> { // regional indicators: a pair is one flag
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
                // keycap sequences (#/*/0-9 + optional VS16 + U+20E3): the base
                // is a plain ASCII character, so it used to fall into the `else`
                // below and disqualify the whole message — a lone "1️⃣" rendered
                // small and inside a bubble unlike every other single emoji
                cp == 0x23 || cp == 0x2A || cp in 0x30..0x39 -> {
                    var j = i + n
                    if (j < s.length && s.codePointAt(j) == 0xFE0F) j += 1
                    if (j < s.length && s.codePointAt(j) == 0x20E3) {
                        count++
                        i = j + 1
                        continue
                    }
                    return 0 // a bare digit/#/* is ordinary text
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
                failed = msg.sendFailed,
            )
        } else {
            timeStr
        }

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
        // only write layoutParams when the gravity actually changed: setLayoutParams
        // unconditionally requests a layout pass that escapes the current one
        if (holder.bubbleGravity != gravity) {
            holder.bubbleGravity = gravity
            params.gravity = gravity
            holder.bubble.layoutParams = params
        }

        holder.imageFrame.visibility = View.GONE
        // a holder reused in-place for a non-image row (DiffUtil change) keeps
        // any previous animation attached and hidden; stop and release it. Image
        // rows are handled by load(), which keeps or replaces the drawable.
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
            // through previewLabel, not the stored text: a voice note keeps its
            // duration there, and a quote card reading "0:14" says nothing
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
                // before the state pass, so a download it starts is already
                // claimed by the time the spinner is decided
                maybeAutoDownload(msg)
                applyAudioState(holder, msg)
            }
            "document" -> applyDocumentState(holder, msg)
            "video" -> {
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
            // rows stored before contact cards carried a body keep the label
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

        // linkify here instead of android:autoLink, which would re-install the
        // stock movement method on every setText over LinkPressMovement.
        // Pre-checked: the WEB_URLS pattern is a large regex and this runs on
        // every bind of every visible text row, where the vast majority of
        // messages contain no URL at all.
        val linkable = holder.text.visibility == View.VISIBLE && mayContainUrl(holder.text.text)
        if (linkable) {
            android.text.util.Linkify.addLinks(holder.text, android.text.util.Linkify.WEB_URLS)
        }
        bindLinkPreview(holder, msg, linkable)
    }

    // Only plain text rows: a caption under a photo already has its own picture
    // above it, and a card under that reads as a second attachment.
    private fun bindLinkPreview(holder: Holder, msg: MessageRow, linkable: Boolean) {
        val url = if (linkable && msg.msgType.isEmpty()) urlOf(msg) else null
        if (url == null) {
            holder.linkPreview.visibility = View.GONE
            holder.linkPreview.tag = null
            return
        }
        val row = LinkPreview.cached(url)
        if (row == null) {
            holder.linkPreview.visibility = View.GONE
            holder.linkPreview.tag = null
            previewWaiters.getOrPut(url) { HashSet() }.add(msg.id)
            onNeedLinkPreview(url)
            return
        }
        // Only THIS row is no longer waiting. Dropping the whole set (which an
        // earlier revision did) lost the other rows sharing this link: a
        // routine rebind between the fetch landing and onLinkPreviewReady
        // running left every one of them card-less until scrolled away and back.
        previewWaiters[url]?.let {
            it.remove(msg.id)
            if (it.isEmpty()) previewWaiters.remove(url)
        }
        if (!row.hasPreview) {
            holder.linkPreview.visibility = View.GONE
            holder.linkPreview.tag = null
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
            // No height cap: capping would either cut the picture or box it in,
            // and this app never crops an image.
            LinkPreview.loadImage(row.imagePath, holder.linkImage, holder.linkTitle.maxWidth)
        }
    }

    private fun bindPreviewLine(view: TextView, text: String) {
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (text.isNotEmpty()) view.text = text
    }

    // Extracting a URL means running a large regex, so the answer is kept per
    // message body: a row is re-bound on every scroll past it, and in search
    // mode the loaded window holds thousands of them. Keyed on the text rather
    // than the message id, so an edited message is re-read and two people
    // sharing one link share the entry.
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
        for (id in waiting) rebindRow(id)
    }

    // Cheap necessary-condition test for Patterns.AUTOLINK_WEB_URL: it only ever
    // matches text containing a "." (a host label separator).
    private fun mayContainUrl(text: CharSequence?): Boolean {
        if (text == null) return false
        for (i in text.indices) if (text[i] == '.') return true
        return false
    }
}

// autoLink must stay off in the layout: TextView's autoLink path re-installs
// the stock movement method on every setText.
//
// One instance per holder (not a shared singleton): its gesture state is not
// tied to a widget, so sharing it would let a touch on one row clobber another
// row's in-flight press (RecyclerView splits multi-pointer events across rows).
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

    // Handles link opening itself instead of deferring to the stock
    // LinkMovementMethod: that one clamps a tap to the nearest character, so
    // touching empty bubble space beside/after a link's line would open it.
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
    // memory-sized (KB): a count-based cache of software bitmaps could pin
    // hundreds of MB before ever evicting. Sized through the shared helper, so
    // this and AvatarLoader's cache have one documented combined budget instead
    // of two independent maxMemory/8 caches (a quarter of the heap between them).
    private val cache = newBitmapCache(12)
    private val animCache = object : LruCache<String, AnimatedImageDrawable>(8) {
        override fun entryRemoved(
            evicted: Boolean, key: String, oldValue: AnimatedImageDrawable,
            newValue: AnimatedImageDrawable?,
        ) {
            // An evicted entry is no longer reachable for reuse, so nothing will
            // ever stop it: it kept ticking and holding its decoded frame
            // buffers until GC. Only when unattached — a drawable still bound to
            // a visible bubble (callback set) must keep playing.
            if (oldValue.callback == null) oldValue.stop()
        }
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private data class Tag(val path: String, val msgId: String)

    private val waiting = PendingViews<Boolean>()

    // The tag is rewritten on every bind, so a recycled holder must not be
    // painted with the decode it started.
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

    // An AnimatedImageDrawable cannot be shared between views, so it goes to the
    // first view still bound to this path and every other waiter is re-queued
    // for a decode of its own: dropping them left those bubbles blank until the
    // next rebind, since this instance is attached now and animCache only hands
    // back a detached one.
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
            // cache only after it's attached (callback now set) so a concurrent
            // bind can't grab and re-attach this same instance to a second view
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
        // Reused only while unattached: a live callback means another bubble is
        // showing it, and an AnimatedImageDrawable can't be shared.
        val reuse = animCache.get(path)
        if (reuse != null && reuse.callback == null) {
            clearAnimating(imageView)
            applyBounds(imageView, reuse.intrinsicWidth, reuse.intrinsicHeight, sticker)
            imageView.setImageDrawable(reuse)
            reuse.start()
            return
        }
        // Only flash the placeholder when this holder is now showing a
        // *different* message (RecyclerView recycled it). A sent
        // image swaps its path from the staging cache file to the permanent
        // media copy after upload, and that re-decodes identical bytes for the
        // same message — keep the current bitmap on screen and swap it in place
        // once decoding finishes, instead of blinking the placeholder.
        if (prev?.msgId != msg.id) {
            clearAnimating(imageView)
            applyBounds(imageView, 0, 0, sticker)
            imageView.setImageResource(R.drawable.image_placeholder)
        }
        // The decode target is the widest the bubble can actually draw: a fixed
        // 1080 minimum meant a 4000x3000 photo decoded at 2000x1500 (~12MB) for a
        // view about 840px wide, so a handful of photos filled the cache and
        // scrolling back re-decoded everything.
        val targetPx = if (sticker) 512 else imageView.maxWidth.coerceAtLeast(512)
        // a decode for this file may already be running; then we are only queued on it
        if (waiting.await(path, imageView, sticker)) dispatchDecode(path, targetPx)
    }

    private fun dispatchDecode(path: String, targetPx: Int) {
        executor.execute {
            var delivering = false
            // Deliberately does NOT read imageView.tag: this is a worker
            // thread and View is not thread-safe. Staleness is judged from
            // the waiting list instead — a view recycled onto another row
            // still holds a live reference, so a file that just went
            // off-screen may still be decoded, but deliverBitmap re-checks
            // the tag on the main thread and won't paint it, and the result
            // lands in the cache for the next bind either way.
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
                        // static images stay software-allocated so their bitmap is
                        // readable and safe to cache/share across recycled views;
                        // animated ones keep the default (hardware) allocator
                        if (!info.isAnimated) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } catch (e: Throwable) {
                    // includes OutOfMemoryError (an Error, not an Exception): drop
                    // this image instead of letting it kill the decode worker
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
                // Only this run's own waiters are dropped; anything queued after
                // its peek could not claim a decode of its own, so it is
                // re-dispatched here instead of starving on the placeholder.
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

    // Detaching releases the decoded frame buffers, so a recycled or hidden
    // bubble isn't holding onto them. Static bitmaps are left in place — they
    // are cheap and shared via the cache.
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

    // Deliberately not Io.executor, the app-wide serial worker every screen's DB
    // reads share: a viewer-sized decode runs for hundreds of milliseconds, and
    // queueing three of them (ViewPager2 keeps neighbours bound) stalled the
    // chat list and the open chat behind the swipe.
    fun decodeAsync(path: String, maxDim: Int, onDone: (Bitmap?) -> Unit) {
        executor.execute {
            val bitmap = decodeSampled(path, maxDim)
            main.post { onDone(bitmap) }
        }
    }

    fun decodeSampled(path: String, maxDim: Int): Bitmap? {
        // Catch Throwable like load() does: a large photo can throw
        // OutOfMemoryError, and callers run this on the shared app-wide Io
        // executor via execute(), where an escaping Error reaches the thread's
        // uncaught handler and takes down the process (along with the single
        // worker every other screen's DB reads are queued on).
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
