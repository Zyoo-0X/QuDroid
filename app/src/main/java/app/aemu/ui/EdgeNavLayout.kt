package app.aemu.ui

import android.content.Context
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * The machine's own navigation gestures. While a machine is on screen the phone's gestures (Back from a side edge,
 * Home from the bottom edge) must not decide: a swipe that starts at the bottom edge or the side of the machine's
 * screen (or in the frame around it) and goes up / in is the MACHINE's Home / Back.
 *
 * Taps and every other touch go through to the machine as before; only a swipe that started in an edge zone and went
 * far enough in the right direction is taken here (the machine gets a cancel, then the key).
 */
class EdgeNavLayout(ctx: Context) : FrameLayout(ctx) {
    /** whether the gestures are on (read at every touch: the choice may change while the machine runs) */
    var active: () -> Boolean = { false }
    /** the machine's screen inside this layout, or null (then the whole layout) */
    var guestBounds: () -> Rect? = { null }
    var onHome: () -> Unit = {}
    var onBack: () -> Unit = {}

    private val dp = resources.displayMetrics.density
    private val edge = 36 * dp      // how deep from the edge a swipe must start
    private val trigger = 56 * dp   // how far it must travel
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private companion object { const val LEFT = 1; const val RIGHT = 2; const val BOTTOM = 4 }

    /** when the last navigation swipe fired (uptime ms): the phone's own Back that follows the same swipe is ignored */
    @Volatile var lastFiredAt = 0L
        private set
    private var zone = 0
    private var fired = false
    private var pid = -1
    private var downX = 0f
    private var downY = 0f

    private fun bounds(): Rect {
        val b = guestBounds()
        return if (b != null && b.width() > 0 && b.height() > 0) b else Rect(0, 0, width, height)
    }

    private fun begin(e: MotionEvent) {
        zone = 0; fired = false
        if (!active()) return
        val b = bounds()
        val x = e.x; val y = e.y
        if (y >= b.bottom - edge) zone = zone or BOTTOM
        if (x <= b.left + edge) zone = zone or LEFT
        if (x >= b.right - edge) zone = zone or RIGHT
        if (zone != 0) { pid = e.getPointerId(0); downX = x; downY = y }
    }

    /** true once this touch has become a navigation swipe */
    private fun track(e: MotionEvent): Boolean {
        if (fired) return true
        if (zone == 0) return false
        val i = e.findPointerIndex(pid)
        if (i < 0) return false
        val dx = e.getX(i) - downX
        val dy = e.getY(i) - downY
        val up = -dy
        when {
            zone and BOTTOM != 0 && up >= trigger && up > abs(dx) -> { fired = true; onHome() }
            zone and LEFT != 0 && dx >= trigger && dx > abs(dy) -> { fired = true; onBack() }
            zone and RIGHT != 0 && -dx >= trigger && -dx > abs(dy) -> { fired = true; onBack() }
        }
        if (fired) { lastFiredAt = android.os.SystemClock.uptimeMillis(); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        return fired
    }

    private fun end() { zone = 0; fired = false; pid = -1 }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e)
            MotionEvent.ACTION_POINTER_DOWN -> if (!fired) zone = 0 // two fingers are not a navigation swipe
            MotionEvent.ACTION_MOVE -> if (track(e)) return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end()
        }
        return fired
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // nobody below took the touch (the frame around the screen): it is followed here
            MotionEvent.ACTION_DOWN -> { begin(e); return zone != 0 }
            MotionEvent.ACTION_MOVE -> { track(e); return fired || zone != 0 }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { val was = fired || zone != 0; end(); return was }
        }
        return fired
    }

    /** The phone's own back gesture must not take the side edges: they belong to the machine. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val w = r - l; val h = b - t
            val ex = (edge * 1.5f).toInt()
            val half = (100 * dp).toInt()           // the system takes at most 200dp of height per edge
            val mid = h / 2
            systemGestureExclusionRects = if (active() && w > 0 && h > 0) listOf(
                Rect(0, (mid - half).coerceAtLeast(0), ex, (mid + half).coerceAtMost(h)),
                Rect(w - ex, (mid - half).coerceAtLeast(0), w, (mid + half).coerceAtMost(h)),
            ) else emptyList()
        }
    }
}
