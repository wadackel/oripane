package dev.wadackel.oripane

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.wadackel.oripane.protocol.InputEvent
import dev.wadackel.oripane.protocol.PointerAction
import kotlin.math.abs

/**
 * Turns touches and mouse events on and around the video frame into Mac pointer input. Touches go
 * through a [GestureRecognizer] built from the settings (touch mode and mapping table); this class
 * turns `MotionEvent`s into its samples and runs its timers on the view.
 *
 * Events arrive in the coordinates of [view]'s parent, which covers the whole screen, and are
 * measured from [view], the frame the video fills at 1x. Zoomed, the video spreads over the parent
 * too, so the recognizer gets both: the frame's size and where it sits on the screen.
 */
class TouchInput(
    private val view: View,
    settings: Settings = Settings.defaults,
    /** Called with every new viewport: from a pinch, a pan, Fill screen, [setViewport], or a new size of the frame or the screen. */
    private val onViewport: (Viewport) -> Unit = {},
    /** A gesture ran a radial menu entry that the phone does not carry out itself: a key, a Mac action, the keyboard. */
    private val onCommand: (RadialEntry) -> Unit = {},
    /** The radial menu opened, moved, was let go of or closed; positions are in the parent's coordinates. */
    private val onRadial: (GestureEvent) -> Unit = {},
    /** A scroll band was touched, moved along or let go of, or a fling started or ended; positions in the parent's coordinates. */
    private val onStrip: (GestureEvent) -> Unit = {},
    /** Where the scroll bands now are, in the parent's coordinates; null when there are none. */
    private val onStripArea: (ScrollBands?) -> Unit = {},
    private val send: (InputEvent) -> Unit,
) {
    private val config = ViewConfiguration.get(view.context)
    private var settings = settings
    private var recognizer = recognizer(settings)
    private var placement = Placement(0f, 0f)
    private var mousePressed = false
    /** The bands in the parent's coordinates, as last reported; until [stripReported], none were, not even none. */
    private var stripArea: ScrollBands? = null
    private var stripReported = false
    private val timer = Runnable { emit(recognizer.onTimer(SystemClock.uptimeMillis())) }

    private val trackpad get() = settings.touchMode == TouchMode.TRACKPAD

    val viewport get() = recognizer.viewport

    init {
        // A fold or a new video size moves the frame or resizes the screen without a touch to notice it.
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        view.addOnLayoutChangeListener(relayout)
        (view.parent as? View)?.addOnLayoutChangeListener(relayout)
    }

    private fun recognizer(s: Settings) = GestureRecognizer(
        mode = s.touchMode,
        map = s.map,
        cursorSpeed = s.cursorSpeed,
        scroll = s.touchScroll,
        slop = config.scaledTouchSlop.toFloat(),
        doubleTapSlop = config.scaledDoubleTapSlop.toFloat(),
        density = view.resources.displayMetrics.density,
    ).also { it.inertia = s.scrollInertia }

    /**
     * Settings changed while connected. Only a new touch mode needs a new recognizer, and it takes
     * over the frame's placement on the screen and the viewport; the cursor speed and the scroll
     * settings go into the current one, so a trackpad cursor does not jump. A button the old
     * recognizer holds goes up first: the new one would never let it go.
     */
    fun applySettings(s: Settings) {
        place()
        val modeChanged = s.touchMode != settings.touchMode
        settings = s
        if (!modeChanged) {
            recognizer.cursorSpeed = s.cursorSpeed
            recognizer.scroll = s.touchScroll
            recognizer.inertia = s.scrollInertia
            placeStrip()
            return
        }
        val v = recognizer.viewport
        // Before the callbacks go: emit arms the timer again.
        emit(recognizer.release())
        view.removeCallbacks(timer)
        recognizer = recognizer(s)
        recognizer.setViewSize(placement)
        recognizer.setViewport(v)
        placeStrip()
        emit(recognizer.onStreamStarted())
    }

    fun setViewport(v: Viewport) {
        recognizer.setViewport(v)
        onViewport(recognizer.viewport)
    }

    /**
     * The view may be laid out, or resized by a fold, after this was made: the recognizer gets the
     * frame and the screen as they are now, and a viewport they no longer allow is drawn anew.
     */
    private fun place() {
        // Before the early return: the system gesture insets change without the frame moving.
        placeStrip()
        val p = placementOf(view)
        if (p == placement) return
        placement = p
        val before = recognizer.viewport
        recognizer.setViewSize(p)
        if (recognizer.viewport != before) onViewport(recognizer.viewport)
    }

    /**
     * The side band sits on the screen's very edge, and the system's Back gesture is excluded over
     * it; the bottom band sits above the gestures the system keeps for itself there, which no app
     * may exclude. The recognizer gets them in the frame's pixels, the screen in the parent's.
     */
    private fun placeStrip() {
        val screen = view.parent as? View
        val bands = bandsIn(screen)
        recognizer.strip = bands?.vertical?.let(::inFrame)
        recognizer.stripX = bands?.horizontal?.let(::inFrame)
        if (stripReported && bands == stripArea) return
        stripReported = true
        stripArea = bands
        onStripArea(bands)
        screen?.let { s -> ViewCompat.setSystemGestureExclusionRects(s, listOfNotNull(bands?.vertical?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) })) }
        if (bands == null || screen == null) return log("strip off")
        val d = view.resources.displayMetrics.density
        log("strip side=${settings.scrollStrip.key} v_dp=%.0f h_dp=%.0f bottom_inset_dp=%.0f size=${settings.scrollBandSize.key}".format(VERTICAL_BAND_DP, HORIZONTAL_BAND_DP, (screen.height - bands.horizontal.bottom) / d))
    }

    private fun inFrame(a: StripArea) = StripArea(a.left - view.left, a.top - view.top, a.right - view.left, a.bottom - view.top)

    private fun bandsIn(screen: View?): ScrollBands? {
        if (!trackpad || settings.scrollStrip == ScrollStrip.OFF || screen == null || screen.width == 0) return null
        val insets = ViewCompat.getRootWindowInsets(screen)
        val gestures = insets?.getInsets(WindowInsetsCompat.Type.systemGestures())
        val mandatory = insets?.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        val d = view.resources.displayMetrics.density
        val width = STRIP_WIDTH_DP * d
        val x = if (settings.scrollStrip == ScrollStrip.LEFT) 0f else screen.width - width
        val lane = StripArea(x, (gestures?.top ?: 0).toFloat(), x + width, (screen.height - (gestures?.bottom ?: 0)).toFloat())
        val centre = lane.top + (lane.bottom - lane.top) * settings.stripKnobAt
        val half = VERTICAL_BAND_DP / 2 * d
        val vertical = lane.copy(top = (centre - half).coerceAtLeast(lane.top), bottom = (centre + half).coerceAtMost(lane.bottom))
        val bottom = (screen.height - (mandatory?.bottom ?: 0)).toFloat()
        val mid = screen.width / 2f
        val halfH = HORIZONTAL_BAND_DP / 2 * d
        return ScrollBands(settings.scrollStrip, lane, vertical, StripArea(mid - halfH, bottom - width, mid + halfH, bottom))
    }

    /** A point in pixels from the frame's top-left to the video under it. */
    private fun video(x: Float, y: Float) = recognizer.toVideo(x, y)

    /** Fill screen from the radial menu, about the point on the video the menu opened at. */
    fun fillScreen(videoX: Float, videoY: Float) {
        place()
        emit(recognizer.fillScreen(videoX, videoY))
    }

    /** The first stream of a connection. */
    fun onStreamStarted() = emit(recognizer.onStreamStarted())

    fun onTouch(e: MotionEvent): Boolean = inFrame(e, ::touch)

    fun onGeneric(e: MotionEvent): Boolean = inFrame(e, ::generic)

    /** [handle] gets [e] moved from the parent's coordinates to the frame's, history included. */
    private fun inFrame(e: MotionEvent, handle: (MotionEvent) -> Boolean): Boolean {
        val local = MotionEvent.obtain(e)
        try {
            local.offsetLocation(-view.left.toFloat(), -view.top.toFloat())
            return handle(local)
        } finally {
            local.recycle()
        }
    }

    private fun touch(e: MotionEvent): Boolean {
        place()
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> emit(recognizer.onTouch(sample(GestureRecognizer.Kind.DOWN, e)))
            MotionEvent.ACTION_POINTER_DOWN -> emit(recognizer.onTouch(sample(GestureRecognizer.Kind.POINTER_DOWN, e).copy(actionId = e.getPointerId(e.actionIndex))))
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    val fingers = (0 until e.pointerCount).map { GestureRecognizer.Finger(e.getPointerId(it), e.getHistoricalX(it, h), e.getHistoricalY(it, h)) }
                    emit(recognizer.onTouch(GestureRecognizer.Sample(GestureRecognizer.Kind.MOVE, e.getHistoricalEventTime(h), fingers)))
                }
                emit(recognizer.onTouch(sample(GestureRecognizer.Kind.MOVE, e)))
            }
            MotionEvent.ACTION_POINTER_UP -> emit(recognizer.onTouch(sample(GestureRecognizer.Kind.POINTER_UP, e).copy(actionId = e.getPointerId(e.actionIndex))))
            MotionEvent.ACTION_UP -> emit(recognizer.onTouch(sample(GestureRecognizer.Kind.UP, e)))
            MotionEvent.ACTION_CANCEL -> emit(recognizer.onTouch(sample(GestureRecognizer.Kind.CANCEL, e)))
        }
        return true
    }

    private fun sample(kind: GestureRecognizer.Kind, e: MotionEvent) = GestureRecognizer.Sample(
        kind,
        e.eventTime,
        (0 until e.pointerCount).map { GestureRecognizer.Finger(e.getPointerId(it), e.getX(it), e.getY(it)) },
    )

    /** Sends [out] and waits for the recognizer's next deadline. */
    private fun emit(out: GestureRecognizer.Output) {
        out.inputs.forEach(send)
        out.viewport?.let(onViewport)
        out.command?.let(onCommand)
        out.events.forEach { e -> eventLog(e)?.let(::log) }
        out.events.mapNotNull(::radialInParent).forEach(onRadial)
        out.events.mapNotNull(::stripInParent).forEach(onStrip)
        // One output can carry two events, as a grab and a cancel; only the latest is felt.
        out.events.mapNotNull { haptic(it, settings.stripHaptics) }.lastOrNull()?.let(view::performHapticFeedback)
        view.removeCallbacks(timer)
        recognizer.nextDeadline()?.let { view.postDelayed(timer, (it - SystemClock.uptimeMillis()).coerceAtLeast(0)) }
    }

    /** A radial menu event moved from the frame's coordinates to the parent's, where the menu is drawn; null for any other event. */
    private fun radialInParent(e: GestureEvent): GestureEvent? {
        val dx = view.left.toFloat()
        val dy = view.top.toFloat()
        return when (e) {
            is GestureEvent.RadialOpened -> e.copy(x = e.x + dx, y = e.y + dy)
            is GestureEvent.RadialMoved -> e.copy(x = e.x + dx, y = e.y + dy)
            is GestureEvent.RadialReleased -> e.copy(x = e.x + dx, y = e.y + dy)
            GestureEvent.RadialCancelled -> e
            else -> null
        }
    }

    /** A strip or fling event, with positions moved from the frame's coordinates to the parent's; null for any other event. */
    private fun stripInParent(e: GestureEvent): GestureEvent? = when (e) {
        is GestureEvent.StripTouched -> e.copy(x = e.x + view.left, y = e.y + view.top)
        is GestureEvent.StripMoved -> e.copy(x = e.x + view.left, y = e.y + view.top)
        is GestureEvent.StripRelocated -> e.copy(x = e.x + view.left, y = e.y + view.top)
        GestureEvent.StripReleased, GestureEvent.StripHeld, is GestureEvent.FlingStarted, GestureEvent.FlingEnded -> e
        else -> null
    }

    /** A real mouse (DeX, Bluetooth): buttons map directly, outside the mapping table. */
    private fun onMouse(e: MotionEvent): Boolean {
        val (x, y) = video(e.x, e.y)
        if (trackpad) recognizer.setCursor(x, y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
                send(InputEvent.RightClick(x, y))
            } else {
                mousePressed = true
                send(InputEvent.Pointer(PointerAction.Down, x, y))
            }
            MotionEvent.ACTION_MOVE -> if (mousePressed) send(InputEvent.Pointer(PointerAction.Drag, x, y))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (mousePressed) {
                mousePressed = false
                send(InputEvent.Pointer(PointerAction.Up, x, y))
            }
        }
        return true
    }

    private fun generic(e: MotionEvent): Boolean {
        place()
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                val (x, y) = video(e.x, e.y)
                if (trackpad) recognizer.setCursor(x, y)
                send(InputEvent.Pointer(PointerAction.Move, x, y))
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (abs(v) > 0 || abs(h) > 0) send(InputEvent.Scroll(settings.wheelScroll.apply(-h * WHEEL_PX), settings.wheelScroll.apply(v * WHEEL_PX)))
            }
            else -> return false
        }
        return true
    }

    companion object {
        private const val WHEEL_PX = 48f
        /** How far in from its edge a band takes touches: a thumb lands within it without aiming, and the trackpad keeps its width. */
        const val STRIP_WIDTH_DP = 44f

        /** Null for the radial menu's moves, which come with every touch sample. */
        private fun eventLog(e: GestureEvent): String? = when (e) {
            GestureEvent.Armed -> "gesture long_press armed"
            is GestureEvent.DragStarted -> "gesture drag start=${e.by.logName}"
            GestureEvent.DragResumed -> "gesture drag resume"
            is GestureEvent.DragEnded -> "gesture drag end=${e.by.logName}"
            is GestureEvent.Fired -> "gesture fire gesture=${e.gesture.key}"
            is GestureEvent.DoubleTapHeld ->
                "gesture double_tap_hold gap_ms=${e.gapMs} dist_dp=%.1f held_ms=${e.heldMs} by=${if (e.moved) "move" else "timer"}".format(e.distanceDp)
            is GestureEvent.LongPressJudged ->
                "gesture long_press_judge by=${if (e.fired) "timer" else "still"} max_dp=%.1f travel_dp=%.1f recent_dp=%.1f".format(e.maxDp, e.travelDp, e.recentDp)
            is GestureEvent.RadialOpened -> "gesture radial open"
            is GestureEvent.RadialMoved -> null
            is GestureEvent.RadialReleased -> "gesture radial release"
            GestureEvent.RadialCancelled -> "gesture radial cancel"
            is GestureEvent.TwoFingerJudged ->
                "gesture two_finger kind=${if (e.pinch) "pinch" else "scroll"} share=%.2f gap_dp=%.1f travel_dp=%.1f".format(e.share, e.gapDp, e.travelDp)
            is GestureEvent.StripTouched -> "gesture strip start"
            is GestureEvent.StripMoved, GestureEvent.StripTick -> null
            GestureEvent.StripReleased -> "gesture strip end"
            GestureEvent.StripHeld -> "gesture strip held"
            is GestureEvent.StripRelocated -> "gesture strip relocated"
            is GestureEvent.FlingStarted -> "gesture fling v_dp_s=%.0f from=${if (e.fromStrip) "strip" else "fingers"}".format(e.speedDpS)
            GestureEvent.FlingEnded -> "gesture fling end"
        }

        /**
         * The long press felt its arming already, so its grab is silent; a resumed grab never let go.
         * The strip's ticks are felt only with [strip] haptics on.
         */
        private fun haptic(e: GestureEvent, strip: Boolean): Int? = when (e) {
            GestureEvent.Armed -> HapticFeedbackConstants.LONG_PRESS
            is GestureEvent.DragStarted -> when {
                e.by == DragStart.LONG_PRESS -> null
                Build.VERSION.SDK_INT >= 34 -> HapticFeedbackConstants.DRAG_START
                else -> HapticFeedbackConstants.LONG_PRESS
            }
            GestureEvent.DragResumed -> null
            is GestureEvent.DragEnded -> HapticFeedbackConstants.GESTURE_END
            // Held gestures fire with nothing else under the fingers to tell that the wait is over.
            is GestureEvent.Fired -> if (e.gesture == Gesture.LONG_PRESS || e.gesture == Gesture.TWO_FINGER_LONG_PRESS) HapticFeedbackConstants.LONG_PRESS else null
            is GestureEvent.RadialOpened -> HapticFeedbackConstants.LONG_PRESS
            // The screen feels the picks, which only it can tell apart.
            is GestureEvent.RadialMoved, is GestureEvent.RadialReleased, GestureEvent.RadialCancelled -> null
            is GestureEvent.TwoFingerJudged, is GestureEvent.DoubleTapHeld, is GestureEvent.LongPressJudged -> null
            is GestureEvent.StripTouched, GestureEvent.StripTick -> if (strip) HapticFeedbackConstants.CLOCK_TICK else null
            GestureEvent.StripHeld -> HapticFeedbackConstants.LONG_PRESS
            is GestureEvent.StripMoved, GestureEvent.StripReleased, is GestureEvent.StripRelocated, is GestureEvent.FlingStarted, GestureEvent.FlingEnded -> null
        }
    }
}

/** [frame] on its parent, the area that takes the touches and covers the screen. */
fun placementOf(frame: View): Placement {
    val screen = frame.parent as? View
    return Placement(
        frame.width.toFloat(),
        frame.height.toFloat(),
        screen?.width?.toFloat() ?: 0f,
        screen?.height?.toFloat() ?: 0f,
        frame.left.toFloat(),
        frame.top.toFloat(),
    )
}
