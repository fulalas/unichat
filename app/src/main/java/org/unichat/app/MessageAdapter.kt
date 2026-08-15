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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MessageAdapter(
    private val isGroup: Boolean,
    // second arg: true when the user explicitly tapped the media (drives
    // failure feedback), false for automatic downloads on bind
    private val onNeedDownload: (MessageRow, Boolean) -> Unit,
    private val onImageClick: (MessageRow) -> Unit,
    private val onDocumentClick: (MessageRow) -> Unit,
    // video icon tap routes through onDocumentClick (download-or-open); a tap
    // elsewhere on a video bubble uses this: download and auto-open when ready
    private val onVideoOpen: (MessageRow) -> Unit,
    private val onLocationClick: (MessageRow) -> Unit,
    private val onMessageActions: (MessageRow) -> Unit,
    private val onQuoteClick: (MessageRow) -> Unit,
    // fired whenever the multi-select set or mode changes, so the host can
    // open/update/close its contextual action bar
    private val onSelectionChanged: () -> Unit = {},
    // a long-press arms a drag range-select (the listener resolves the anchor row)
    private val onDragArm: () -> Unit = {},
) : RecyclerView.Adapter<MessageAdapter.Holder>() {

    companion object {
        // long enough to cover the smooth scroll to a far-away quoted message
        private const val FLASH_WINDOW_MS = 6000L
        // jump-to-quote flash envelope: fade in, hold, fade out
        private const val FLASH_IN_MS = 250L
        private const val FLASH_HOLD_MS = 800L
        private const val FLASH_OUT_MS = 1450L
        private const val FLASH_TOTAL_MS = FLASH_IN_MS + FLASH_HOLD_MS + FLASH_OUT_MS

        // Compares rows by identity and content so the differ can rebind only
        // what changed. MessageRow is a data class, so `==` covers every field.
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

    // --- multi-select --------------------------------------------------------
    // The selected messages, captured as full rows (keyed by id) at selection
    // time — so a selection survives its row scrolling out of the loaded window.
    // Count, the delete "all mine" gate and the forward/copy set all read this
    // one source, so they can't diverge from what the user selected.
    private val selected = LinkedHashMap<String, MessageRow>()
    var selectionMode = false
        private set

    fun selectedCount(): Int = selected.size

    /**
     * Selected messages in chronological order (forward/copy order). Uses the
     * live row when it's still loaded (fresh file path / state), else the row as
     * captured when it was selected. Runs on every selection change (drag steps
     * included), so it only collects the selected rows instead of building a
     * map of the whole loaded list.
     */
    fun selectedMessages(): List<MessageRow> {
        // Built once per list version instead of scanning the whole loaded window
        // on every call: this runs per drag-select row crossing, and in search
        // mode that window can hold thousands of rows.
        val byId = rowsById ?: messages.associateBy { it.id }.also { rowsById = it }
        return selected.values.map { byId[it.id] ?: it }.sortedBy { it.timeSent }
    }

    // id → row for the currently applied list; invalidated when a submit commits
    private var rowsById: Map<String, MessageRow>? = null

    private fun isSelected(msg: MessageRow): Boolean = msg.id in selected

    /** Enters selection mode with this message selected (long-press). */
    fun startSelection(msg: MessageRow) {
        selectionMode = true
        if (selected.put(msg.id, msg) == null) rebindRow(msg.id)
        onSelectionChanged()
    }

    /** Toggles a message's selection; leaves selection mode when none remain. */
    fun toggleSelection(msg: MessageRow) {
        if (selected.remove(msg.id) == null) selected[msg.id] = msg
        rebindRow(msg.id)
        if (selected.isEmpty()) selectionMode = false
        onSelectionChanged()
    }

    /** Clears the selection and exits selection mode (host closed the bar). */
    fun clearSelection() {
        if (!selectionMode && selected.isEmpty()) return
        selectionMode = false
        val ids = selected.keys.toList()
        selected.clear()
        for (id in ids) rebindRow(id)
        onSelectionChanged()
    }

    private fun rebindRow(msgId: String) {
        val i = indexOfMessage(msgId)
        if (i >= 0) notifyItemChanged(i)
    }

    // --- drag range-select (see DragSelectTouchListener) ---------------------

    /**
     * Sets one row's selection by position, WITHOUT firing onSelectionChanged —
     * a drag touches many rows per move, so the caller batches the notify with
     * commitDragSelection(). Returns true if the state changed.
     */
    fun setSelectedAt(pos: Int, sel: Boolean): Boolean {
        val msg = messages.getOrNull(pos) ?: return false
        val changed = if (sel) selected.put(msg.id, msg) == null else selected.remove(msg.id) != null
        if (changed) notifyItemChanged(pos)
        return changed
    }

    /** Reconciles selection mode with the set and notifies the host once, after a drag step. */
    fun commitDragSelection() {
        selectionMode = selected.isNotEmpty()
        onSelectionChanged()
    }

    /** Snapshot of the selected ids, taken when a drag begins so it can restore prior state. */
    fun snapshotSelection(): Set<String> = HashSet(selected.keys)

    /** Message id at an adapter position, or "" if out of range (drag range-select). */
    fun messageIdAt(pos: Int): String = messages.getOrNull(pos)?.id ?: ""

    // DiffUtil-backed list: submitting a new list diffs it against the current
    // one on a background thread and rebinds only the rows that actually
    // changed, instead of re-rendering the whole list on every update.
    private val differ = AsyncListDiffer(this, DIFF)
    private val messages: List<MessageRow> get() = differ.currentList
    private var names: Map<String, String> = emptyMap()

    // No stable ids: DiffUtil already dispatches precise per-row updates and
    // item animations are disabled, so stable ids add no benefit — and deriving
    // one from id.hashCode() could collide and show the wrong row's content.

    var seekDragging = false
        private set
    var highlightQuery: String = ""
    // jump-to-quote: binds of this message flash its row while the arm window
    // is open (it stays armed so an unrelated reload can't swallow the flash);
    // flashPlayedAt anchors the envelope so a rebind mid-flash resumes it at
    // the right phase instead of replaying it from the start
    var flashMsgId: String = ""
        set(value) {
            field = value
            flashArmedAt = android.os.SystemClock.uptimeMillis()
            flashPlayedAt = 0L
        }
    private var flashArmedAt = 0L
    private var flashPlayedAt = 0L

    // onCommitted runs on the main thread once the new list has been applied
    // (so the caller can scroll/restore against the final item count).
    // newQuoteNames maps quoted-message id → resolved sender label; the host
    // resolves it off the main thread alongside the messages themselves, so
    // binds never touch the DB.
    fun submit(
        newMessages: List<MessageRow>,
        newNames: Map<String, String>,
        newQuoteNames: Map<String, String> = emptyMap(),
        onCommitted: (() -> Unit)? = null,
    ) {
        // Sender labels and @mention names both come from the contact-name map
        // (not part of a MessageRow), so the differ can't see a name change.
        // Refresh the visible rows in place only when it can matter: an actual
        // map change, and not the first population (freshly inserted rows
        // already bind with the new map). The host caches the map, so the usual
        // case compares the same instance and never walks it.
        val namesChanged = differ.currentList.isNotEmpty() && newNames != names
        names = newNames
        quoteNames = newQuoteNames
        differ.submitList(newMessages) {
            // dropped here, not before submitList: the differ applies the new
            // list asynchronously, and selectedMessages() runs on every
            // selection change — in that window it would rebuild and cache the
            // map from the list that is still on its way out
            rowsById = null
            if (namesChanged && itemCount > 0) notifyItemRangeChanged(0, itemCount)
            onCommitted?.invoke()
        }
    }

    fun messagesSnapshot(): List<MessageRow> = messages

    /**
     * Swaps specific rows for freshly-read copies, keeping the current list
     * shape and the name maps. For events that only changed a row's stored
     * fields (a tick, a reaction, a finished download): the differ then rebinds
     * just those rows, instead of the host re-querying its whole window.
     */
    fun refreshRows(fresh: Map<String, MessageRow>) {
        if (fresh.isEmpty() || messages.isEmpty()) return
        if (messages.none { it.id in fresh }) return
        differ.submitList(messages.map { fresh[it.id] ?: it }) { rowsById = null }
    }

    /**
     * Refreshes every visible audio row's play/pause icon and progress in place.
     * Drives both the 250ms progress ticker and playback state changes, so a
     * chaining voice note updates the old and new rows without a full-list
     * rebind (which would flicker the screen).
     */
    fun refreshAudioRows(recycler: RecyclerView) {
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? Holder ?: continue
            val msg = holder.current ?: continue
            if (msg.msgType != "audio") continue
            applyAudioState(holder, msg)
        }
    }

    /**
     * Applies live playback state to one audio row. The single owner of that
     * mapping: the bind branch and the 250ms ticker used to each spell it out,
     * and had already drifted (the ticker left a stale seekbar `max` on a row
     * whose clip had stopped).
     */
    private fun applyAudioState(holder: Holder, msg: MessageRow) {
        // by message, not by path: one Telegram file can back several rows, and
        // matching on the path lit up every copy as "playing"
        val current = AudioPlayer.currentMsgId == msg.id && AudioPlayer.currentChatId == msg.chatId
        // setImageResource reloads the drawable even for an unchanged id, and this
        // runs 4x/second per visible row for the whole clip
        val icon = if (current && AudioPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        if (holder.audioIconRes != icon) {
            holder.audioIconRes = icon
            holder.audioButton.setImageResource(icon)
            // the only way to play a voice message: it needs a real label (and
            // one that tracks the icon), not the "@null" that told accessibility
            // services there was nothing here to announce
            holder.audioButton.contentDescription = holder.audioButton.context.getString(
                if (icon == R.drawable.ic_pause) R.string.pause else R.string.play
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

    // Quote-sender labels keyed by quoted message id, resolved off the main
    // thread by the host and handed in with each submit() ("" = unknown).
    private var quoteNames: Map<String, String> = emptyMap()

    // Live download percentage per message id, so the ring survives rebinds
    // (scrolling) between progress events. Entries are dropped once the file
    // has landed and the row binds as downloaded.
    private val videoDownloadPct = HashMap<String, Int>()

    /**
     * Updates a video row's download ring in place as bytes arrive, without a
     * full-list rebind. The percentage is cached so a row scrolled offscreen
     * and back keeps its ring position.
     */
    fun setVideoProgress(recycler: RecyclerView, msgId: String, pct: Int) {
        videoDownloadPct[msgId] = pct
        for (i in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(i)) as? Holder ?: continue
            val msg = holder.current ?: continue
            if (msg.id != msgId || msg.msgType != "video" || msg.filePath.isNotEmpty()) continue
            applyVideoState(holder, msg)
        }
    }

    /**
     * Applies download state to one video row: the single owner of the
     * icon/ring/spinner rule, which the bind branch and the progress updater used
     * to express separately — and disagree on. `downloading` is derived from a
     * live progress entry as well as the row's fileStatus, because the in-memory
     * row keeps status 0 for the whole transfer (Bridge writes status 1 without
     * notifying), so a row scrolled away and back rendered as "not downloading"
     * and discarded its cached percentage.
     */
    private fun applyVideoState(holder: Holder, msg: MessageRow) {
        val downloaded = msg.filePath.isNotEmpty()
        val pct = videoDownloadPct[msg.id]
        val downloading = !downloaded && (msg.fileStatus == 1 || pct != null)
        holder.videoIcon.visibility = if (downloading) View.GONE else View.VISIBLE
        // play once it's here, classic download arrow while it isn't
        holder.videoIcon.setImageResource(if (downloaded) R.drawable.ic_play else R.drawable.ic_download)
        // the icon itself is decorative; the tappable frame around it carries the
        // label, which has to say what the tap will actually do
        holder.videoButton.contentDescription = holder.videoButton.context.getString(
            if (downloaded) R.string.play else R.string.download
        )
        // known percentage → determinate ring; otherwise (connecting, or a sender
        // that omitted the size) an indeterminate spinner
        holder.videoProgress.visibility =
            if (downloading && pct != null) View.VISIBLE else View.GONE
        holder.videoSpinner.visibility =
            if (downloading && pct == null) View.VISIBLE else View.GONE
        if (pct != null) holder.videoProgress.progress = pct
        if (downloaded) videoDownloadPct.remove(msg.id)
    }

    /**
     * Bind-time auto-download eligibility, in one place instead of once per media
     * branch. Also retries a previously-failed download (fileStatus == 3):
     * history media that failed before the media-retry recovery existed would be
     * stuck at that status forever otherwise. Bridge throttles it to one
     * automatic retry per failure.
     */
    private fun maybeAutoDownload(msg: MessageRow) {
        // A stored path outlives its file: our own Telegram sends reference the
        // cacheDir staging copy, which the daily sweep deletes after a day. The
        // row went on claiming "downloaded", so nothing here ever re-fetched it
        // and the bubble stayed blank for good. A vanished file is not a
        // download — Bridge drops the dead reference and fetches it again.
        val gone = msg.filePath.isNotEmpty() && !File(msg.filePath).exists()
        if (gone || (msg.filePath.isEmpty() && (msg.fileStatus == 0 || msg.fileStatus == 3))) {
            onNeedDownload(msg, false)
        }
    }

    // "m:ss" (e.g. 0:07), matching the stored voice-note duration format
    private fun fmtSecs(ms: Int): String = TimeFormat.mmss(ms / 1000)

    private fun speedLabel(speed: Float): String = when (speed) {
        1.5f -> "1.5×"
        2f -> "2×"
        else -> "1×"
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val dateHeader: TextView = view.findViewById(R.id.dateHeader)
        // full-width row wrapper around the bubble; tinted when selected
        val row: FrameLayout = view.findViewById(R.id.messageRow)
        val bubble: LinearLayout = view.findViewById(R.id.bubble)
        val senderName: TextView = view.findViewById(R.id.senderName)
        val imageFrame: FrameLayout = view.findViewById(R.id.imageFrame)
        val image: ImageView = view.findViewById(R.id.messageImage)
        val imageTime: TextView = view.findViewById(R.id.imageTime)
        val forwardedLabel: TextView = view.findViewById(R.id.forwardedLabel)
        val quotePreview: TextView = view.findViewById(R.id.quotePreview)
        val audioRow: LinearLayout = view.findViewById(R.id.audioRow)
        val audioMeta: LinearLayout = view.findViewById(R.id.audioMeta)
        val audioButton: ImageView = view.findViewById(R.id.audioButton)
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
        val reactionPill: TextView = view.findViewById(R.id.reactionPill)
        var flashFade: Runnable? = null
        // the row currently bound to this recycled holder; the once-attached
        // listeners read it instead of capturing a fresh lambda per bind
        var current: MessageRow? = null
        // per-view link gesture state — one instance per holder so a touch on
        // another row can't clobber this row's in-flight link press
        internal val linkMovement = LinkPressMovement()
        // last hit rect the quote TouchDelegate was built for; lets the layout
        // listener skip rebuilding (and reallocating) when bounds are unchanged
        var quoteDelegateRect: android.graphics.Rect? = null
        // last play/pause icon applied, so the 250ms playback ticker doesn't ask
        // ImageView to reload an identical drawable four times a second
        var audioIconRes: Int = 0
        // last gravity applied to the bubble / reaction pill: assigning
        // layoutParams always calls requestLayout(), even when nothing changed
        var bubbleGravity: Int = -1
        var pillGravity: Int = -1
        var imageFrameBottomMargin: Int = -1
        // screen width the bubble/image caps below were derived from
        var cappedForWidth: Int = -1
    }

    // stop a bubble's animation when its view is recycled off-screen, so a long
    // chat full of GIFs doesn't keep dozens of decoders ticking in the pool
    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        ImageLoader.clearAnimating(holder.image)
    }

    // Bubbles hug their own side and images stay within half the screen, both
    // measured off the current screen metrics. The host activity handles
    // orientation itself (configChanges in the manifest), so the holder pool
    // outlives a rotation: a holder made in portrait would keep the portrait
    // caps — and ImageLoader would size its decode from them — next to rows
    // capped for landscape. Re-applied on every bind, and to the rows already on
    // screen when the list's width changes (see onAttachedToRecyclerView).
    private fun applyWidthCaps(holder: Holder) {
        val metrics = holder.itemView.resources.displayMetrics
        val maxWidth = (metrics.widthPixels * 0.78f).toInt()
        if (holder.cappedForWidth == maxWidth) return
        holder.cappedForWidth = maxWidth
        holder.text.maxWidth = maxWidth
        holder.senderName.maxWidth = maxWidth
        // cap the quote too: a long quoted text must not stretch the bubble to
        // the full row width (bubbles should hug their own side)
        holder.quotePreview.maxWidth = maxWidth
        holder.image.maxWidth = maxWidth
        holder.image.maxHeight = (metrics.heightPixels * 0.5f).toInt()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        // A rotation (or a multi-window resize) re-lays the list out without
        // rebinding it, so the visible rows would keep the previous width's caps
        // and their already-decoded images. Posted: a notify during a layout
        // pass is rejected outright.
        recyclerView.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            val oldWidth = oldRight - oldLeft
            // width 0 is the list's first layout, not a resize
            if (oldWidth == 0 || right - left == oldWidth) return@addOnLayoutChangeListener
            v.post { if (itemCount > 0) notifyDataSetChanged() }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        val holder = Holder(view)
        // no applyWidthCaps here: a bind always follows a create and applies
        // them itself, and nothing reads the caps in between
        // round the image to follow the bubble curve (images get a slim
        // border, so square corners would poke past the bubble's radius)
        val radius = 7f * parent.resources.displayMetrics.density
        holder.image.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        holder.image.clipToOutline = true

        // Attach every touch handler once, here — not on each bind. Each reads
        // holder.current (the row most recently bound to this holder), so no new
        // lambda is allocated per row per rebind. Behaviour matches the old
        // per-bind wiring: media/label taps route by type, everything else
        // opens the actions menu. Long-press enters multi-select; while in
        // selection mode every tap toggles the row instead of its normal action.

        // Consumes a tap while multi-selecting (toggles the row). Returns true
        // when handled, so callers skip their normal action.
        fun tapWhileSelecting(): Boolean {
            if (!selectionMode) return false
            holder.current?.let { toggleSelection(it) }
            return true
        }
        val openActions = View.OnClickListener {
            if (tapWhileSelecting()) return@OnClickListener
            holder.current?.let(onMessageActions)
        }
        val routeByType = View.OnClickListener {
            if (tapWhileSelecting()) return@OnClickListener
            val m = holder.current ?: return@OnClickListener
            when (m.msgType) {
                "document" -> onDocumentClick(m)
                "video" -> onVideoOpen(m)
                "location" -> onLocationClick(m)
                else -> onMessageActions(m)
            }
        }
        val longPress = View.OnLongClickListener {
            val m = holder.current ?: return@OnLongClickListener true
            // long-press always selects (tap toggles) and arms a drag range-select
            // from this row; a plain long-press with no drag just selects the one
            startSelection(m)
            onDragArm()
            true
        }
        // links must not open (or copy) while multi-selecting — the tap toggles
        holder.linkMovement.selectionActive = { selectionMode }
        holder.itemView.setOnClickListener(openActions)
        holder.itemView.setOnLongClickListener(longPress)
        holder.bubble.setOnClickListener(routeByType)
        holder.bubble.setOnLongClickListener(longPress)
        // links: tap opens (movement method), hold copies to the clipboard —
        // never opens. Elsewhere in the text, tap/hold behave like the bubble.
        val link = holder.linkMovement
        holder.text.movementMethod = link
        holder.text.setOnClickListener {
            if (link.openedLink) {
                link.openedLink = false // the link tap already acted
            } else {
                routeByType.onClick(it)
            }
        }
        holder.text.setOnLongClickListener {
            // a hold directly on a link copies it (never opens); a hold anywhere
            // else on the row enters/extends multi-select. While already
            // selecting, links are inert (selectionActive), so this always selects.
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
            if (tapWhileSelecting()) return@setOnClickListener
            val m = holder.current ?: return@setOnClickListener
            // a path whose file is gone opens an empty viewer: fetch instead
            if (m.filePath.isNotEmpty() && File(m.filePath).exists()) onImageClick(m)
            else onNeedDownload(m, true)
        }
        holder.quotePreview.setOnClickListener {
            if (tapWhileSelecting()) return@setOnClickListener
            holder.current?.let(onQuoteClick)
        }
        // long-press on the quote strip opens the actions menu, same as the
        // bubble — otherwise the widened tap target below would swallow it
        holder.quotePreview.setOnLongClickListener(longPress)
        // widen the quote's tap target to the full bubble width: the preview
        // itself hugs its text, but the whole strip it sits on should route to
        // the quoted message (visuals unchanged — only the hit rect grows).
        // Only rebuild the delegate when the rect actually changes: relayouts
        // with unchanged bounds (e.g. a playing voice note ticking its duration
        // every 250ms) must not swap the delegate mid-gesture and drop a tap.
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
        // video: the icon downloads-or-opens; the rest of the row (label and
        // padding) downloads and auto-opens when ready, matching routeByType
        holder.videoButton.setOnClickListener {
            if (tapWhileSelecting()) return@setOnClickListener
            holder.current?.let(onDocumentClick)
        }
        holder.videoButton.setOnLongClickListener(longPress)
        holder.videoRow.setOnClickListener {
            if (tapWhileSelecting()) return@setOnClickListener
            holder.current?.let(onVideoOpen)
        }
        holder.videoRow.setOnLongClickListener(longPress)
        holder.audioButton.setOnClickListener {
            if (tapWhileSelecting()) return@setOnClickListener
            val m = holder.current ?: return@setOnClickListener
            // the stored path can be stale (a swept Telegram staging copy);
            // re-download instead of "playing" a file that is no longer there
            if (m.filePath.isNotEmpty() && java.io.File(m.filePath).exists()) {
                AudioPlayer.playPause(m.filePath, m.chatId, m.id)
            } else {
                onNeedDownload(m, true)
            }
        }
        // global playback-speed toggle; the state change refreshes every row's pill
        holder.audioSpeed.setOnClickListener { AudioPlayer.cycleSpeed() }
        holder.audioSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar?) { seekDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekDragging = false
                val m = holder.current ?: return
                if (m.filePath.isEmpty()) return
                if (AudioPlayer.currentMsgId == m.id) {
                    AudioPlayer.seekTo(sb?.progress ?: 0)
                } else {
                    AudioPlayer.play(m.filePath, m.chatId, m.id, sb?.progress ?: 0)
                }
            }
        })
        return holder
    }

    override fun getItemCount(): Int = messages.size

    // Names any @mentions, then highlights occurrences of the active search
    // query within a message's text. Mentions are resolved first so the search
    // offsets are computed against the string that is actually drawn.
    private fun highlighted(ctx: android.content.Context, raw: String): CharSequence {
        val full = resolveMentions(raw, names)
        val q = highlightQuery
        if (q.isEmpty() || q.length > full.length) return full
        // Matches against the ORIGINAL string. Searching a full.lowercase() copy
        // and applying the offsets to `full` breaks for any character whose
        // lowercase form has a different length (Turkish 'İ' U+0130 lowercases to
        // two chars), which shifted every later offset and pushed setSpan past
        // the end of the Spannable — an IndexOutOfBoundsException inside
        // onBindViewHolder, i.e. a crash while scrolling search results.
        var idx = indexOfIgnoreCase(full, q, 0)
        if (idx < 0) return full
        val sp = SpannableString(full)
        val bg = ctx.themeColor(R.attr.chatAccent)
        while (idx >= 0) {
            val end = idx + q.length
            sp.setSpan(android.text.style.BackgroundColorSpan(bg), idx, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(0xFFFFFFFF.toInt()), idx, end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            idx = indexOfIgnoreCase(full, q, end)
        }
        return sp
    }

    // Case-insensitive indexOf that never changes the string's length, matching
    // the same semantics as the host's `contains(q, ignoreCase = true)` scan.
    private fun indexOfIgnoreCase(haystack: String, needle: String, from: Int): Int {
        var i = from.coerceAtLeast(0)
        val last = haystack.length - needle.length
        while (i <= last) {
            if (haystack.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }

    // Highlights the whole row with a translucent band that fades in, holds,
    // and fades out (~2.5s total) — used when jumping to a quoted message.
    // Driven by frame callbacks, not an Animator, so it keeps its timing even
    // when the system animator duration scale is reduced or disabled.
    private fun flashRow(holder: Holder, ctx: android.content.Context) {
        // shared envelope origin across rebinds: first bind stamps it, later
        // rebinds resume mid-envelope; once played out, disarm entirely
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
                    // disarm unless a new flash was armed while we played
                    if (flashPlayedAt == start) flashMsgId = ""
                }
            }
        }
        holder.flashFade = tick
        view.postOnAnimation(tick)
    }

    // Appends the transfer state to a video/document label so a running (or
    // failed) download is visible on the bubble itself.
    private fun downloadSuffix(ctx: android.content.Context, msg: MessageRow): String = when {
        msg.filePath.isNotEmpty() -> ""
        msg.fileStatus == 1 -> " — " + ctx.getString(R.string.downloading)
        msg.fileStatus == 3 -> " — " + ctx.getString(R.string.download_failed)
        else -> ""
    }

    // Condenses the per-user reaction emojis ("😂,❤️,😂") into pill text:
    // all of them when few, else the distinct ones plus the total count.
    private fun reactionSummary(csv: String): String {
        val all = csv.split(',').filter { it.isNotEmpty() }
        if (all.size <= 3) return all.joinToString("")
        return all.distinct().take(3).joinToString("") + all.size
    }

    // Number of emoji in the string, or 0 if it contains anything else (plain
    // text is never resized). ZWJ sequences (families), skin tones, variation
    // selectors and flag pairs count as a single emoji.
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
        // the once-attached touch handlers dispatch off this
        holder.current = msg
        applyWidthCaps(holder)

        // multi-select highlight: tint the whole row while it is selected
        holder.row.setBackgroundColor(
            if (isSelected(msg)) ctx.themeColor(R.attr.chatMsgSelected)
            else android.graphics.Color.TRANSPARENT
        )

        // reset any jump-to-quote flash left over from a recycled row
        holder.flashFade?.let { holder.itemView.removeCallbacks(it) }
        holder.flashFade = null
        holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val flashAge = android.os.SystemClock.uptimeMillis() - flashArmedAt
        if (msg.id == flashMsgId && flashAge < FLASH_WINDOW_MS) {
            flashRow(holder, ctx)
        }

        // date separator above the first message of each day
        val newDay = position == 0 || !TimeFormat.sameDay(messages[position - 1].timeSent, msg.timeSent)
        if (newDay) {
            holder.dateHeader.visibility = View.VISIBLE
            holder.dateHeader.text = TimeFormat.dateSeparator(ctx, msg.timeSent)
        } else {
            holder.dateHeader.visibility = View.GONE
        }

        val editedPrefix = if (msg.edited) ctx.getString(R.string.edited) + " " else ""
        val timeStr = editedPrefix + TimeFormat.clock(msg.timeSent)
        val sticker = msg.msgType == "sticker"
        // a captionless photo draws its timestamp over the image itself,
        // WhatsApp-style, instead of on its own line under it
        val overlayTime = msg.msgType == "image" && msg.text.isEmpty()
        // a sticker with nothing else in its bubble floats directly on the
        // wallpaper — no bubble background, no border — like WhatsApp
        val bareSticker = sticker && msg.text.isEmpty() &&
            msg.quotedId.isEmpty() && msg.quotedText.isEmpty() && !msg.forwarded &&
            !(isGroup && !msg.fromMe)
        holder.time.visibility = if (overlayTime) View.GONE else View.VISIBLE
        holder.imageTime.visibility = if (overlayTime) View.VISIBLE else View.GONE
        val timeView = if (overlayTime) holder.imageTime else holder.time
        timeView.text = if (msg.fromMe) {
            Ticks.timeWithTick(ctx, timeStr, msg.isRead, timeView.textSize, tickFirst = false)
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
        holder.text.visibility = View.VISIBLE
        holder.text.textSize = 15f // default; emoji-only messages enlarge below
        // images get a slim border like WhatsApp (bare stickers none at all);
        // everything else keeps the regular text inset (reset every bind —
        // holders are recycled)
        val density = ctx.resources.displayMetrics.density
        val padH: Int; val padV: Int
        if (msg.msgType in PICTURE_TYPES) {
            val slim = if (bareSticker) 0 else (4 * density).toInt()
            padH = slim; padV = slim
        } else {
            padH = (10 * density).toInt(); padV = (6 * density).toInt()
        }
        holder.bubble.setPadding(padH, padV, padH, padV)
        // captions still need breathing room inside the slim image border
        val capPad = if (msg.msgType in PICTURE_TYPES) (6 * density).toInt() else 0
        holder.text.setPadding(capPad, if (capPad > 0) (2 * density).toInt() else 0, capPad, 0)

        holder.forwardedLabel.visibility = if (msg.forwarded) View.VISIBLE else View.GONE

        // reactions: a small pill overlapping the bubble's bottom corner,
        // hugging the same side as the bubble
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

        // quoted-message preview (reply) — tap to jump to the original message
        if (msg.quotedText.isNotEmpty() || msg.quotedId.isNotEmpty()) {
            holder.quotePreview.visibility = View.VISIBLE
            // A media quote carries no text, so it is labelled by its type
            // through previewLabel — the single owner of that mapping, reading
            // the same localized strings the bubble and chat list use. The Go
            // bridge used to spell these out ("Photo", "Voice message") in
            // English, which then showed untranslated in the quote strip only.
            val body = when {
                msg.quotedText.isNotEmpty() -> resolveMentions(msg.quotedText, names)
                msg.quotedType.isNotEmpty() ->
                    previewLabel(ctx, msg.quotedType, "", emoji = false)
                        .ifEmpty { ctx.getString(R.string.message_label) }
                else -> ctx.getString(R.string.message_label)
            }
            // headed by who wrote the quoted message, like WhatsApp; plain
            // body when that message is unknown (never synced / deleted)
            val name = if (msg.quotedId.isEmpty()) "" else quoteNames[msg.quotedId].orEmpty()
            holder.quotePreview.text = if (name.isEmpty()) body else {
                val sp = SpannableString(name + "\n" + body)
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
                // with the time overlaid the image is the bubble's last child,
                // so the gap that separated it from the caption/time goes away
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
                if (msg.text.isEmpty()) holder.text.visibility = View.GONE
                else holder.text.text = highlighted(ctx, msg.text)
            }
            "audio" -> {
                holder.audioRow.visibility = View.VISIBLE
                holder.audioMeta.visibility = View.VISIBLE
                holder.text.visibility = View.GONE
                // unplayed dot: received = you haven't played it; sent = no
                // played receipt from the recipient yet. Cleared by playing /
                // by their played receipt — which never arrives when they
                // keep read receipts off, so a sent dot can stay forever.
                holder.audioUnplayedDot.visibility = if (msg.played) View.GONE else View.VISIBLE
                // voice bubbles get the full width so the timeline is usable
                holder.audioRow.minimumWidth = holder.text.maxWidth
                // play/pause icon, speed pill, elapsed label and seekbar — the
                // same code the playback ticker uses, so they cannot drift
                applyAudioState(holder, msg)
                holder.audioSeek.isEnabled = msg.filePath.isNotEmpty()
                maybeAutoDownload(msg)
            }
            "document" -> {
                holder.text.text = highlighted(ctx, "📎 " + msg.text + downloadSuffix(ctx, msg))
            }
            "video" -> {
                // no inline player; the icon shows download state, the row opens
                // in an external app. text is hidden — the label lives in the row
                holder.text.visibility = View.GONE
                holder.videoRow.visibility = View.VISIBLE
                val label = msg.text.ifEmpty { ctx.getString(R.string.video_label) }
                holder.videoLabel.text = highlighted(ctx, label)
                applyVideoState(holder, msg)
            }
            "location" -> {
                // tap opens the coordinates (in fileId) in the user's maps app
                val label = msg.text.ifEmpty { ctx.getString(R.string.location_label) }
                holder.text.text = highlighted(ctx, "📍 $label")
            }
            // nothing this client can render (view-once media, a poll, a
            // contact card): the bubble is just the localized label
            in LABEL_ONLY_TYPES -> holder.text.text =
                previewLabel(ctx, msg.msgType, "", emoji = true)
            else -> {
                holder.text.text = highlighted(ctx, msg.text)
                // emoji-only messages render large and without a bubble, like
                // WhatsApp (unless they quote another message, which needs the
                // bubble to frame the preview)
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
        if (holder.text.visibility == View.VISIBLE && mayContainUrl(holder.text.text)) {
            android.text.util.Linkify.addLinks(holder.text, android.text.util.Linkify.WEB_URLS)
        }
    }

    // Cheap necessary-condition test for Patterns.AUTOLINK_WEB_URL: it only ever
    // matches text containing a "." (a host label separator).
    private fun mayContainUrl(text: CharSequence?): Boolean {
        if (text == null) return false
        for (i in text.indices) if (text[i] == '.') return true
        return false
    }
}

/**
 * LinkMovementMethod that separates tap from hold on links: it tracks which
 * link the current gesture started on (so the row's long-press handler can
 * copy it instead of opening the actions menu) and can swallow the touch-up
 * that ends a handled long press, so holding a link never opens it. It also
 * flags the up that did open a link, letting the TextView's click listener
 * skip the actions dialog for that tap. autoLink must stay off in the layout:
 * TextView's autoLink path re-installs the stock method on every setText.
 *
 * One instance per holder (not a shared singleton): its gesture state is not
 * tied to a widget, so sharing it would let a touch on one row clobber another
 * row's in-flight press (RecyclerView splits multi-pointer events across rows).
 */
internal class LinkPressMovement : android.text.method.LinkMovementMethod() {
    var pressedLink: android.text.style.URLSpan? = null
        private set
    // armed by the long-press handler after copying the link: eat the up
    var consumeUp = false
    // the last up opened a link; the posted click listener clears it
    var openedLink = false
    // while multi-selecting, links are inert: a tap toggles the row instead of
    // opening the link, and a hold selects instead of copying the URL
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
    // A link opens only when the gesture starts AND ends on its drawn text.
    override fun onTouchEvent(
        widget: TextView, buffer: Spannable, event: android.view.MotionEvent,
    ): Boolean {
        // during multi-select the link is dead: report no press so the row's
        // own click/long-click listeners drive toggling, and never open a link
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

/** Decodes chat images off the main thread with an in-memory cache. */
object ImageLoader {
    // memory-sized (KB): a count-based cache of software bitmaps could pin
    // hundreds of MB before ever evicting. Sized through the shared helper, so
    // this and AvatarLoader's cache have one documented combined budget instead
    // of two independent maxMemory/8 caches (a quarter of the heap between them).
    private val cache = newBitmapCache(12)
    // decoded animations kept for reuse on reappearance. Small: each entry
    // streams frames from its file source so it holds little memory, but the
    // count is capped anyway. Reused only while unattached (see load()).
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
    // files with a decode already queued/running, so a fling that passes the
    // same path in several holders doesn't decode it repeatedly
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Tracks what a bound ImageView is currently loading: the file path (for
    // the stale-decode guard) plus the message id (to tell a same-message path
    // swap apart from a recycled holder).
    private data class Tag(val path: String, val msgId: String)

    // Every view waiting on a decode of a given path, carrying the bounds rule
    // each one needs. The queue itself is PendingViews (BitmapCaches.kt), shared
    // with AvatarLoader; only the "is this view still on this path" test and the
    // painting below are ours.
    private val waiting = PendingViews<Boolean>()

    /** True while [view] still shows [path] — the tag is rewritten on every bind,
     *  so a recycled holder must not be painted with the decode it started. */
    private fun stillOn(view: ImageView, path: String) = (view.tag as? Tag)?.path == path

    // Paints a finished decode into every view still bound to this path. Main
    // thread: the only place a View's tag is read, so the decode workers never
    // touch View state (View is not thread-safe).
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
    // first view still bound to this path; any other waiter re-decodes, or
    // reuses this instance from animCache once it detaches.
    private fun deliverAnimated(path: String, drawable: AnimatedImageDrawable) {
        for (w in waiting.take(path)) {
            val view = w.view.get() ?: continue
            if (!stillOn(view, path)) continue
            clearAnimating(view)
            applyBounds(view, drawable.intrinsicWidth, drawable.intrinsicHeight, w.payload)
            view.setImageDrawable(drawable)
            drawable.start()
            // cache only after it's attached (callback now set) so a concurrent
            // bind can't grab and re-attach this same instance to a second view
            animCache.put(path, drawable)
            return
        }
    }

    fun load(msg: MessageRow, imageView: ImageView) {
        val path = msg.filePath
        // photos scale up to the standard bubble width (see applyBounds);
        // stickers keep their intrinsic size
        val sticker = msg.msgType == "sticker"
        val prev = imageView.tag as? Tag
        imageView.tag = Tag(path, msg.id)
        if (path.isEmpty()) {
            clearAnimating(imageView)
            applyBounds(imageView, 0, 0, sticker)
            imageView.setImageResource(R.drawable.image_placeholder)
            return
        }
        // The same message and file is already bound (a plain rebind, e.g. a
        // receipt tick). If an animation is showing, leave it playing —
        // re-decoding would restart it from frame one — but restart it if it was
        // stopped while recycled off-screen. Anything else (a placeholder left
        // by a failed decode, or a static bitmap) falls through to (re)load.
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
        // Reuse a previously-decoded animation rather than decoding it again,
        // but only while it isn't attached to another view — a live callback
        // means another bubble is showing it, and an AnimatedImageDrawable
        // can't be shared. Saves re-decoding the same sticker on every scroll.
        val reuse = animCache.get(path)
        if (reuse != null && reuse.callback == null) {
            clearAnimating(imageView)
            applyBounds(imageView, reuse.intrinsicWidth, reuse.intrinsicHeight, sticker)
            imageView.setImageDrawable(reuse)
            reuse.start()
            return
        }
        // Cache miss. Only flash the placeholder when this holder is now
        // showing a *different* message (RecyclerView recycled it). A sent
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
        // queue this view BEFORE claiming the slot, so a decode already running
        // for this path still delivers to it
        waiting.await(path, imageView, sticker)
        if (!inFlight.add(path)) return // a decode for this file is already running; we are queued on it
        executor.execute {
            var delivering = false
            try {
                // Deliberately does NOT read imageView.tag: this is a worker
                // thread and View is not thread-safe. Staleness is judged from
                // the waiting list instead — a view recycled onto another row
                // still holds a live reference, so a file that just went
                // off-screen may still be decoded, but deliverBitmap re-checks
                // the tag on the main thread and won't paint it, and the result
                // lands in the cache for the next bind either way.
                if (waiting.peek(path).isNullOrEmpty()) return@execute
                // another decode landed it while this one sat in the queue
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
                    main.post { deliverAnimated(path, drawable) }
                }
            } finally {
                inFlight.remove(path)
                // nothing will be painted, so don't leave the queue behind
                if (!delivering) waiting.abandon(path)
            }
        }
    }

    // Sizes the bubble's ImageView for the media it is about to show. Photos
    // render at the standard bubble width — scaled up from however small the
    // source is, like WhatsApp — so the bubble always hugs the picture (a tiny
    // thumbnail must not leave a wide quote strip towering over a small image
    // in a sea of bubble background). Height follows the aspect ratio, capped
    // at maxHeight (very tall images narrow to keep their proportions).
    // Stickers, and the placeholder (w/h = 0), fall back to intrinsic size.
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

    // Stops any animation on the view and detaches it, releasing its decoded
    // frame buffers so a recycled or hidden bubble isn't holding onto them.
    // Static bitmaps are left in place — they're cheap and shared via the cache.
    fun clearAnimating(imageView: ImageView) {
        val d = imageView.drawable
        if (d is AnimatedImageDrawable) {
            d.stop()
            imageView.setImageDrawable(null)
        }
    }

    // Largest power-of-two subsample that keeps the decoded size just above
    // maxDim in both dimensions (i.e. neither dimension is halved below it).
    private fun sampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        while (w / (sample * 2) >= maxDim || h / (sample * 2) >= maxDim) sample *= 2
        return sample
    }

    /**
     * Decodes [path] on the image pool and delivers the result on the main
     * thread. Deliberately not Io.executor, the app-wide serial worker every
     * screen's DB reads share: a viewer-sized decode runs for hundreds of
     * milliseconds, and queueing three of them (ViewPager2 keeps neighbours
     * bound) stalled the chat list and the open chat behind the swipe.
     */
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
