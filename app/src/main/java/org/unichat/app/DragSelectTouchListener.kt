package org.unichat.app

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

class DragSelectTouchListener(
    private val adapter: MessageAdapter,
    private val onDragFinished: () -> Unit = {},
) : RecyclerView.OnItemTouchListener {

    private var armed = false
    var isDragging = false
        private set
    private var anchor = -1
    private var rangeMin = -1
    private var rangeMax = -1
    private var preSelected: Set<String> = emptySet()

    private var lastX = 0f
    private var lastY = 0f
    private var rv: RecyclerView? = null
    private var velocity = 0 // px/frame, signed; 0 = not in an edge hot zone
    private var autoScrolling = false
    private var maxScrollPx = 0f

    fun arm() {
        armed = true
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        this.rv = rv
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { armed = false; isDragging = false }
            MotionEvent.ACTION_MOVE -> if (!isDragging && armed && startDrag(rv, e.x, e.y)) return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> armed = false
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x; lastY = e.y
                selectUnder(rv, e.x, e.y)
                updateAutoScroll(rv, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDrag()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}

    private fun startDrag(rv: RecyclerView, x: Float, y: Float): Boolean {
        val child = rv.findChildViewUnder(x, y) ?: return false
        val pos = rv.getChildAdapterPosition(child)
        if (pos == RecyclerView.NO_POSITION) return false
        isDragging = true
        anchor = pos
        rangeMin = pos
        rangeMax = pos
        preSelected = adapter.snapshotSelection()
        maxScrollPx = 18f * rv.resources.displayMetrics.density
        adapter.setSelectedAt(pos, true)
        adapter.commitDragSelection()
        return true
    }

    private fun selectUnder(rv: RecyclerView, x: Float, y: Float) {
        val child = rv.findChildViewUnder(x, y) ?: return
        val pos = rv.getChildAdapterPosition(child)
        if (pos != RecyclerView.NO_POSITION) selectRange(pos)
    }

    private fun selectRange(pos: Int) {
        if (anchor < 0) return
        val min = minOf(anchor, pos)
        val max = maxOf(anchor, pos)
        if (min == rangeMin && max == rangeMax) return
        if (min < rangeMin) for (i in min until rangeMin) adapter.setSelectedAt(i, true)
        if (max > rangeMax) for (i in rangeMax + 1..max) adapter.setSelectedAt(i, true)
        if (rangeMin < min) for (i in rangeMin until min) adapter.setSelectedAt(i, wasSelected(i))
        if (rangeMax > max) for (i in max + 1..rangeMax) adapter.setSelectedAt(i, wasSelected(i))
        rangeMin = min
        rangeMax = max
        adapter.commitDragSelection()
    }

    private fun wasSelected(pos: Int): Boolean = adapter.messageIdAt(pos) in preSelected

    fun stopDrag() {
        val wasDragging = isDragging
        if (isDragging) { isDragging = false; adapter.commitDragSelection() }
        armed = false
        anchor = -1; rangeMin = -1; rangeMax = -1
        preSelected = emptySet()
        velocity = 0
        rv?.removeCallbacks(autoScroll)
        autoScrolling = false
        if (wasDragging) onDragFinished()
    }

    private fun updateAutoScroll(rv: RecyclerView, y: Float) {
        val hot = (rv.height * 0.12f).coerceAtLeast(1f)
        velocity = when {
            y < hot -> -speed(hot - y, hot)
            y > rv.height - hot -> speed(y - (rv.height - hot), hot)
            else -> 0
        }
        if (velocity != 0 && !autoScrolling) {
            autoScrolling = true
            rv.postOnAnimation(autoScroll)
        }
    }

    private fun speed(into: Float, hot: Float): Int =
        (maxScrollPx * (into / hot).coerceIn(0f, 1f)).toInt().coerceAtLeast(1)

    private val autoScroll = object : Runnable {
        override fun run() {
            val rv = rv
            if (rv == null || !isDragging || velocity == 0) { autoScrolling = false; return }
            rv.scrollBy(0, velocity)
            selectUnder(rv, lastX, lastY)
            rv.postOnAnimation(this)
        }
    }
}
