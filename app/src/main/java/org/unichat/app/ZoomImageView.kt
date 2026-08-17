package org.unichat.app

import android.content.Context
import android.graphics.Matrix
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

class ZoomImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : ImageView(context, attrs) {

    companion object {
        private const val MAX_ZOOM = 6f
        private const val DOUBLE_TAP_ZOOM = 2.5f
        private const val ZOOM_ANIM_MS = 200f
    }

    var onSingleTap: (() -> Unit)? = null

    private var zoom = 1f // 1 = image fit to screen
    private var tx = 0f
    private var ty = 0f
    private var anim: Runnable? = null
    private var contentWidth = 0f
    private val matrix = Matrix()

    init {
        scaleType = ScaleType.MATRIX
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                anim = null
                applyZoom(
                    (zoom * det.scaleFactor).coerceIn(1f, MAX_ZOOM),
                    det.focusX, det.focusY
                )
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float,
            ): Boolean {
                if (zoom > 1f) {
                    anim = null
                    tx -= dx
                    ty -= dy
                    apply()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                animateZoomTo(if (zoom > 1f) 1f else DOUBLE_TAP_ZOOM, e.x, e.y)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Claim the gesture from the pager this view sits in. Two reasons, both
        // of which look like "pinch is broken": a pinch IS horizontal movement,
        // so at fit-to-screen the pager would intercept the second finger and
        // turn a zoom into a page swipe; and once zoomed in, every drag belongs
        // to the image, so paging must not start at all.
        val pinching = event.pointerCount > 1 || scaleDetector.isInProgress
        if (pinching || zoom > 1f) parent?.requestDisallowInterceptTouchEvent(true)

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // Released only once the image is back to fit-to-screen — checked after
        // the detectors have run, so a pinch that ends at 1x hands paging back
        // immediately rather than one gesture later.
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            if (zoom <= 1f) parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    // accessibility services (TalkBack, Switch Access) and key events activate
    // views via performClick, never through touch events — route it to the
    // same single-tap action so the viewer stays dismissible for them
    override fun performClick(): Boolean {
        super.performClick()
        onSingleTap?.invoke()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        apply()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        zoom = 1f
        apply()
    }

    private fun applyZoom(newZoom: Float, fx: Float, fy: Float) {
        val ratio = newZoom / zoom
        tx = fx - (fx - tx) * ratio
        ty = fy - (fy - ty) * ratio
        zoom = newZoom
        apply()
    }

    // Frame-driven (not an Animator, so it ignores the system animator scale).
    private fun animateZoomTo(target: Float, fx: Float, fy: Float) {
        val start = SystemClock.uptimeMillis()
        val from = zoom
        val tick = object : Runnable {
            override fun run() {
                if (anim !== this) return
                val t = ((SystemClock.uptimeMillis() - start) / ZOOM_ANIM_MS).coerceAtMost(1f)
                val eased = 1f - (1f - t) * (1f - t) // ease-out
                applyZoom(from + (target - from) * eased, fx, fy)
                if (t < 1f) postOnAnimation(this) else anim = null
            }
        }
        anim = tick
        postOnAnimation(tick)
    }

    /**
     * ViewPager2 asks this before claiming a horizontal drag. A zoomed-in image
     * always answers yes, in BOTH directions: the drag belongs to the picture
     * until the user zooms back out, so reaching an edge must not hand the
     * gesture to the pager. Handing it over at the edge meant a zoomed photo
     * would page one way but not the other, which reads as a broken swipe.
     */
    override fun canScrollHorizontally(direction: Int): Boolean = zoom > 1f

    fun reset() {
        anim = null
        zoom = 1f
        tx = 0f
        ty = 0f
        apply()
    }

    private fun apply() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
        val scale = minOf(vw / dw, vh / dh) * zoom
        val cw = dw * scale
        val ch = dh * scale
        contentWidth = cw
        tx = if (cw <= vw) (vw - cw) / 2f else tx.coerceIn(vw - cw, 0f)
        ty = if (ch <= vh) (vh - ch) / 2f else ty.coerceIn(vh - ch, 0f)
        matrix.setScale(scale, scale)
        matrix.postTranslate(tx, ty)
        imageMatrix = matrix
    }
}
