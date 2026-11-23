package dev.wadackel.oripane

import dev.wadackel.oripane.protocol.InputEvent
import dev.wadackel.oripane.protocol.PointerAction
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Turns touch samples into Mac pointer input, by touch mode and mapping table. Pure: samples and
 * times in, inputs out, so the rules are tested on the JVM. Times are milliseconds on one clock;
 * a wait has passed once `now >= start + wait`.
 *
 * In the direct mode a finger is the left button where it touches; in the trackpad mode it moves a
 * cursor kept here, and clicks land on the cursor. The cursor follows the finger from its first
 * sample; travel within the slop is undone when the touch turns out a tap, a long press or two
 * fingers, so those land where the touch began. Two fingers are a scroll, a two-finger tap or
 * long press, or a pinch that zooms the [viewport]. Four fingers swipe, pinch or spread and three
 * tap. A gesture that fires once does what the [map] gives it: a click, the radial menu left open,
 * or anything the radial menu can hold, which goes out as [Output.command] unless it is a right
 * click or Fill screen ([fillScreen]), done here. Every position sent is on the video, through the
 * viewport. Double tap and hold may drag, zoom about the second tap, or scroll with the finger.
 *
 * Positions are pixels from the frame's top-left, the frame being the video at 1x fitted inside the
 * screen. Zoomed, the video spreads past the frame over the black bars, up to the screen's edges;
 * the part of the video on screen is what a touch lands on, and its edge band is where a drag pans
 * and the trackpad cursor moves the view.
 *
 * Touches may land off the video on screen, on the black bars still showing. A direct-mode touch
 * that starts there with one finger sends nothing; once a second finger joins, the fingers act as
 * anywhere.
 *
 * The trackpad mode grabs with three fingers or a long press. A grab follows the centre of every
 * finger down, survives a lift of all but one, and outlasts the last lift by [GRACE]: three fingers
 * back carry it on, a tap or a move drops it.
 *
 * A long press may open the radial menu instead. While it is open the finger sends nothing to the
 * Mac: its moves and its lift go out as [GestureEvent]s, and the screen picks the item. A second
 * finger closes the menu; in the trackpad mode it then arms a grab, as a long press that grabs
 * would have, and in the direct mode the touch does nothing until every finger lifts.
 *
 * While the soft keyboard is up, the screen ends at its key row ([liftKeyboard]): the picture moves
 * up so the point being typed at shows above it, and every pan, follow and edge band above works in
 * that shorter screen. [panBy] moves the picture without sending anything, for the key row's drag;
 * in the direct mode three fingers do the same when the map says so.
 *
 * In the trackpad mode a touch that lands on a scroll band scrolls instead: on the [strip], the
 * band down a side edge, its finger's vertical travel goes out as scroll, and on [stripX], the band
 * along the bottom, its horizontal travel; geared up as the bands are short, and every other finger
 * is ignored until all lift. Held still, the side band is moved by the finger instead, and scrolls
 * nothing. With [inertia], a scroll from a band or from two fingers glides on after the lift,
 * slowing down, until it fades or the next touch lands.
 */
class GestureRecognizer(
    private val mode: TouchMode,
    private val map: TouchMap,
    /** Settable, as [scroll] is: a live change keeps the cursor where it is and the touch in progress. */
    var cursorSpeed: SpeedStep,
    /** Settable: a live change of the scroll settings keeps the cursor and the touch in progress. */
    var scroll: ScrollSetting,
    private val slop: Float,
    private val doubleTapSlop: Float,
    /** Pixels per dp. */
    private val density: Float,
) {
    enum class Kind { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

    data class Finger(val id: Int, val x: Float, val y: Float)

    /** [actionId]: the finger that landed or lifted, on a POINTER_DOWN or POINTER_UP; the list holds it too. */
    data class Sample(val kind: Kind, val time: Long, val fingers: List<Finger>, val actionId: Int = -1)

    private enum class Phase { IDLE, NORMAL, JUDGING, OUTSIDE, TWO_FINGER, THREE_FINGER, FOUR_FINGER, ARMED, MENU, DRAGGING, GRACE, SPENT, STRIP, HOLD_ZOOM, HOLD_SCROLL }

    /** A scroll sent at [time], kept to measure the speed at the lift. */
    private data class Sent(val time: Long, val dx: Float, val dy: Float)

    private data class Tap(val time: Long, val x: Float, val y: Float, val click: Boolean)

    /**
     * What one call produced: the inputs to send, the viewport when it changed, a radial menu entry
     * a gesture ran, and what happened to a trackpad drag or two fingers, in order, for the haptics
     * and the log.
     */
    data class Output(val inputs: List<InputEvent>, val viewport: Viewport? = null, val command: RadialEntry? = null, val events: List<GestureEvent> = emptyList())

    var viewport = Viewport()
        private set

    /** The side scroll band's touch area in the frame's pixels, null for none; settable, as the screen, the settings and the band move. */
    var strip: StripArea? = null

    /** The bottom scroll band's touch area in the frame's pixels, null for none. */
    var stripX: StripArea? = null

    /** Scrolls glide on after the lift; settable with the settings. */
    var inertia = false

    /** The frame and the screen around it; empty until the view is measured, which reads as a screen the size of the frame. */
    private var placement = Placement(0f, 0f)
    private var cursorX = 0.5f
    private var cursorY = 0.5f
    /** The trackpad cursor when an ordinary touch began, where travel within the slop is undone to. */
    private var startCursor: Pair<Float, Float>? = null

    private var phase = Phase.IDLE
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastAt = 0L
    private var moved = false
    private var pressed = false
    private var pressedByTimer = false
    /** This touch continues a click: its timer press and its tap click are the next click of a series. */
    private var continuesClick = false
    private var pressDeadline: Long? = null
    private var longPressDeadline: Long? = null
    /** A trackpad touch's path while its long press waits, and the farthest it got from where it touched, in pixels. */
    private var stillTravel = 0f
    private var stillFarthest = 0f
    /** The path as the wait's last [LONG_PRESS_SETTLE] began, to measure how far the finger went since. */
    private var settledTravel = 0f
    private var judgeDeadline: Long? = null
    private var lastTap: Tap? = null
    private var centerX = 0f
    private var centerY = 0f
    private var twoMoved = false
    private var cursorMoved = false
    private var pinching = false
    private var pinchDistance = 0f
    /** Where the two fingers touched, to weigh their gap's change against how far they travelled. */
    private var twoStart = emptyList<Finger>()
    /** The two fingers are a pinch or a scroll until they lift; before, they are still being judged. */
    private var twoJudged = false
    private var panDeadline: Long? = null
    private var pannedAt = 0L
    private var threeMoved = false
    /** A finger lifted from three or four: the touch makes no swipe or grab any more. */
    private var lifted = false
    private var threeDragDeadline: Long? = null
    private var twoHoldDeadline: Long? = null
    /** The four fingers' mean distance from their centre as they landed, to tell a pinch from a swipe. */
    private var fourRadius = 0f
    /** The video point a double tap and hold zooms about, which stays under the second tap. */
    private var holdAtX = 0f
    private var holdAtY = 0f
    /** The finger that last landed first, and the one a double tap and hold follows. */
    private var touchFinger = -1
    private var holdFinger = -1
    /** How long after the last tap, and how far from it, the touch that may continue it landed. */
    private var secondGap = 0L
    private var secondDistance = 0f
    /** The touch left a grab's grace by moving: it is no tap and takes no third finger. */
    private var graceExit = false
    /** Where the centre was measured from, and over how many fingers. */
    private var refX = 0f
    private var refY = 0f
    private var refCount = 0
    private var lastSpeed = 0f
    /** The trackpad's button is down; unlike a touch, it lasts through [graceDeadline]. */
    private var held = false
    private var graceDeadline: Long? = null
    private var command: RadialEntry? = null
    private val events = mutableListOf<GestureEvent>()
    private var stripFinger = -1
    /** The finger is on the bottom band: its horizontal travel scrolls. */
    private var stripHorizontal = false
    private var stripStartX = 0f
    private var stripStartY = 0f
    /** The side band was held still: the finger now moves it, and scrolls nothing. */
    private var relocating = false
    private var stripHoldDeadline: Long? = null
    /** Travel along the strip since the last tick. */
    private var stripTravel = 0f
    /** The scrolls of the touch in progress, from a zero at its start. */
    private val recent = mutableListOf<Sent>()
    private var flingDeadline: Long? = null
    private var flingAt = 0L
    private var flingVx = 0f
    private var flingVy = 0f
    /** In the direct mode, the video point last tapped or where the radial menu last opened: what the keyboard lifts into view. */
    private var anchor: Pair<Float, Float>? = null
    /** The viewport as the keyboard came up, to go back to when it goes; null while it is down. */
    private var beforeKeyboard: Viewport? = null
    /** The room kept above the key row while the keyboard is up; null while it is down. */
    private var keyboardMargin: Float? = null

    /** A screen the size of the frame. */
    fun setViewSize(widthPx: Int, heightPx: Int) = setViewSize(Placement(widthPx.toFloat(), heightPx.toFloat()))

    /** The viewport is clamped again: a smaller screen may show less of the video, or allow less zoom. */
    fun setViewSize(p: Placement) {
        placement = p
        viewport = viewport.clamped(p)
    }

    fun setViewport(v: Viewport) {
        viewport = v.clamped(placement)
    }

    /**
     * The soft keyboard came up, or the screen changed under it: [p] ends at its key row. The point
     * being typed at, the trackpad cursor or the direct mode's [anchor], moves up just far enough to
     * show at least [margin] pixels above the key row, the room the composition strip takes, and
     * clear of the edge band, where the trackpad cursor would pull the picture on; with no such point
     * the viewport is only kept inside the shorter screen.
     */
    fun liftKeyboard(p: Placement, margin: Float): Output = withViewport {
        if (beforeKeyboard == null) beforeKeyboard = viewport
        keyboardMargin = margin
        placement = p
        lift(margin)
    }

    private fun lift(margin: Float) {
        val p = placement
        val full = p.withoutKeyboard()
        val v = viewport.clamped(full)
        val at = if (mode == TouchMode.TRACKPAD) cursorX to cursorY else anchor
        if (at == null || full.frameH <= 0f) {
            viewport = v.clamped(p)
            return
        }
        val y = full.y.toPixel(at.second, v.zoom, v.top)
        val target = min(y, p.y.visibleEnd - p.y.offset - max(margin, EDGE_BAND * p.y.visibleEnd))
        viewport = v.copy(top = p.y.startFor(at.second, target, v.zoom)).clamped(p)
    }

    /** The soft keyboard went: the viewport it found comes back, unless the zoom changed while it was up. */
    fun dropKeyboard(p: Placement): Output = withViewport {
        val before = beforeKeyboard
        beforeKeyboard = null
        keyboardMargin = null
        placement = p
        viewport = (if (before != null && before.zoom == viewport.zoom) before else viewport).clamped(p)
    }

    /**
     * Moves the picture with a finger's travel of ([dx], [dy]) pixels, as far as the placement
     * allows, and sends nothing; a trackpad cursor left outside the edge band comes just inside,
     * as after a pinch, or its next move would pull the picture back to it.
     */
    fun panBy(dx: Float, dy: Float): Output = withViewport { out ->
        moveView(dx, dy)
        if (mode == TouchMode.TRACKPAD) cursorInsideBand(out)
    }

    private fun moveView(dx: Float, dy: Float) {
        if (placement.frameW <= 0f || placement.frameH <= 0f) return
        val v = viewport
        viewport = v.copy(left = v.left - dx / (v.zoom * placement.frameW), top = v.top - dy / (v.zoom * placement.frameH)).clamped(placement)
    }

    /** A new recognizer for a new touch mode takes over what the keyboard left: the view to go back to and the point it lifted. */
    fun takeKeyboardFrom(old: GestureRecognizer) {
        beforeKeyboard = old.beforeKeyboard
        keyboardMargin = old.keyboardMargin
        anchor = old.anchor
    }

    /** A point in pixels from the frame's top-left to the video under it. */
    fun toVideo(x: Float, y: Float) = viewport.toVideo(x, y, placement)

    /** The first stream of a connection: the trackpad cursor starts at the video's centre and the Mac's follows. */
    fun onStreamStarted(): Output = withViewport {
        if (mode == TouchMode.TRACKPAD) {
            cursorX = 0.5f
            cursorY = 0.5f
            it += move(cursorX, cursorY)
        }
    }

    /** A mouse moved the Mac's cursor, on the video; the trackpad cursor follows it. */
    fun setCursor(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, 1f)
        cursorY = y.coerceIn(0f, 1f)
    }

    fun nextDeadline(): Long? = listOfNotNull(pressDeadline, longPressDeadline, judgeDeadline, panDeadline, threeDragDeadline, twoHoldDeadline, graceDeadline, flingDeadline, stripHoldDeadline).minOrNull()

    fun onTimer(now: Long): Output = withViewport {
        expire(now, it)
        armPan(now)
    }

    fun onTouch(s: Sample): Output = withViewport { out ->
        // A deadline the timer has not delivered yet still comes before this sample.
        expire(s.time, out)
        when (s.kind) {
            Kind.DOWN -> down(s, out)
            Kind.POINTER_DOWN -> pointerDown(s, out)
            Kind.MOVE -> move(s, out)
            Kind.POINTER_UP -> when {
                phase == Phase.THREE_FINGER || phase == Phase.FOUR_FINGER -> lifted = true
                // The scroll ends with the first finger up: the one left behind sends nothing.
                phase == Phase.TWO_FINGER && s.fingers.size == 2 -> {
                    twoHoldDeadline = null
                    twoFingerFling(s.time)
                }
                // A finger left behind would move by the gap to the one that lifted.
                (phase == Phase.HOLD_ZOOM || phase == Phase.HOLD_SCROLL) && s.actionId == holdFinger -> endHold(s.time, out)
                // Android gives a lifted finger's id to the next finger down: the knob's must not live on in it.
                phase == Phase.STRIP && s.actionId == stripFinger -> {
                    stripFinger = -1
                    stripHoldDeadline = null
                }
            }
            Kind.UP -> up(s, out)
            Kind.CANCEL -> cancel(out)
        }
        armPan(s.time)
    }

    private inline fun withViewport(body: (MutableList<InputEvent>) -> Unit): Output {
        val before = viewport
        val out = mutableListOf<InputEvent>()
        command = null
        events.clear()
        body(out)
        return Output(out, viewport.takeIf { it != before }, command, events.toList())
    }

    /**
     * The touch mode changes: a held button goes up, and the touch in progress and any grace end.
     * The direct mode lets go where the finger was, as a cancel does.
     */
    fun release(): Output = withViewport { dropAll(DragEnd.MODE, it) }

    // MARK: one finger

    private fun down(s: Sample, out: MutableList<InputEvent>) {
        endFling()
        // Before the double tap's judgement: a touch in the grace is the grab's, never a second tap.
        if (held && graceDeadline != null) return startGrace(s)
        val f = s.fingers.first()
        touchFinger = f.id
        if (mode == TouchMode.TRACKPAD && strip?.contains(f.x, f.y) == true) return startStrip(f, s.time, horizontal = false)
        if (mode == TouchMode.TRACKPAD && stripX?.contains(f.x, f.y) == true) return startStrip(f, s.time, horizontal = true)
        clearTouch()
        downAt = s.time
        downX = f.x; downY = f.y
        lastX = f.x; lastY = f.y; lastAt = s.time
        if (mode == TouchMode.DIRECT && outside(f)) {
            // Clamped to the edge, it would click whatever the edge holds, though nothing is under the finger.
            lastTap = null
            phase = Phase.OUTSIDE
            return
        }
        val prev = lastTap
        val continues = prev != null && s.time - prev.time < DOUBLE_GAP && hypot(f.x - prev.x, f.y - prev.y) <= doubleTapSlop
        if (!continues) lastTap = null
        if (continues) {
            secondGap = s.time - prev!!.time
            secondDistance = hypot(f.x - prev.x, f.y - prev.y)
        }
        continuesClick = continues && prev!!.click
        // A double click keeps the wider reach: only a touch this soon and this near may grab, zoom or scroll.
        val holds = continues && secondGap <= HOLD_GAP && secondDistance <= HOLD_SLOP_DP * density
        if (holds && map[Gesture.DOUBLE_TAP_HOLD] != GestureAction.None) {
            phase = Phase.JUDGING
            judgeDeadline = s.time + HOLD_WAIT
        } else {
            startNormal(out)
        }
    }

    /** Rules 1, 2 and 4 of an ordinary touch, timed from [downAt]. */
    private fun startNormal(out: MutableList<InputEvent>) {
        phase = Phase.NORMAL
        if (mode == TouchMode.DIRECT) out += move(vx(downX), vy(downY)) else startCursor = cursorX to cursorY
        when (map[Gesture.LONG_PRESS]) {
            GestureAction.Drag -> if (mode == TouchMode.DIRECT) {
                // Pressing sooner than a tap can end would turn other tap actions into clicks.
                pressDeadline = downAt + if (map[Gesture.TAP] == GestureAction.Click) PRESS_DELAY else TAP_MAX
            } else {
                longPressDeadline = downAt + LONG_PRESS
            }
            GestureAction.RadialMenu, is GestureAction.Run -> longPressDeadline = downAt + LONG_PRESS
            else -> {}
        }
    }

    private fun move(s: Sample, out: MutableList<InputEvent>) {
        when (phase) {
            Phase.TWO_FINGER -> twoFingerMove(s, out)
            Phase.THREE_FINGER -> threeFingerMove(s, out)
            Phase.FOUR_FINGER -> fourFingerMove(s, out)
            Phase.DRAGGING -> dragMove(s, out)
            Phase.STRIP -> stripMove(s, out)
            Phase.HOLD_ZOOM, Phase.HOLD_SCROLL -> holdMove(s)?.let(out::add)
            Phase.ARMED -> armedMove(s, out)
            Phase.MENU -> {
                val f = finger(s) ?: return
                track(f, s.time)
                events += GestureEvent.RadialMoved(f.x, f.y)
            }
            Phase.GRACE -> graceMove(s, out)
            Phase.JUDGING -> {
                val f = finger(s) ?: return
                if (hypot(f.x - downX, f.y - downY) > slop) {
                    if (s.time - downAt >= HOLD_STILL) hold(f, s.time, out) else notHeld(f, s.time, out)
                } else if (mode == TouchMode.TRACKPAD) {
                    // A grab by the timer then starts from here, with the travel within the slop dropped.
                    track(f, s.time)
                }
            }
            Phase.NORMAL -> {
                val f = finger(s) ?: return
                normalMove(f, s.time, out)
            }
            else -> {}
        }
    }

    private fun normalMove(f: Finger, time: Long, out: MutableList<InputEvent>) {
        if (mode == TouchMode.TRACKPAD) return trackpadMove(f, time, out)
        if (!moved && hypot(f.x - downX, f.y - downY) > slop) {
            moved = true
            longPressDeadline = null
            pressDeadline = null
            if (!pressed) press(vx(downX), vy(downY), PointerAction.Down, out)
        }
        if (pressed) out += pointer(PointerAction.Drag, vx(f.x), vy(f.y))
        track(f, time)
    }

    /** Every sample moves the cursor; leaving the slop only decides the touch is no tap. */
    private fun trackpadMove(f: Finger, time: Long, out: MutableList<InputEvent>) {
        if (longPressDeadline != null) {
            stillTravel += hypot(f.x - lastX, f.y - lastY)
            stillFarthest = maxOf(stillFarthest, hypot(f.x - downX, f.y - downY))
            if (time <= downAt + LONG_PRESS - LONG_PRESS_SETTLE) settledTravel = stillTravel
        }
        if (!moved && hypot(f.x - downX, f.y - downY) > slop) {
            moved = true
            longPressDeadline = null
            pressDeadline = null
        }
        moveCursor(f.x - lastX, f.y - lastY, speedOf(f, time))
        // Travel within the slop may still be undone; only past it does a two-finger tap stop counting.
        if (moved) cursorMoved = true
        out += move(cursorX, cursorY)
        track(f, time)
    }

    /** The touch is a tap, a long press or two fingers after all: the cursor goes back to where it began. */
    private fun undoSlopTravel(out: MutableList<InputEvent>) {
        val (x, y) = startCursor ?: return
        if (moved || (x == cursorX && y == cursorY)) return
        // Not followed: the viewport would shake on every tap, for travel within the slop.
        cursorX = x
        cursorY = y
        out += move(x, y)
    }

    private fun up(s: Sample, out: MutableList<InputEvent>) {
        when (phase) {
            Phase.TWO_FINGER -> twoFingerUp(s, out)
            Phase.THREE_FINGER -> if (!threeMoved && s.time - downAt < TAP_MAX) fire(Gesture.THREE_FINGER_TAP, refX, refY, out)
            // Still judging means unmoved: the judgement's deadline came first otherwise. Lifted past a
            // tap's length but before the grab, it was neither, and sends nothing.
            Phase.JUDGING -> if (s.time - downAt < TAP_MAX) secondTap(s.fingers.firstOrNull() ?: Finger(0, downX, downY), s.time, out) else lastTap = null
            Phase.NORMAL -> normalUp(s.fingers.firstOrNull() ?: Finger(0, lastX, lastY), s.time, out)
            Phase.DRAGGING -> graceDeadline = s.time + GRACE
            Phase.STRIP -> {
                if (relocating) events += GestureEvent.StripRelocated(lastX, lastY)
                events += GestureEvent.StripReleased
                if (!relocating) fling(s.time, fromStrip = true)
            }
            Phase.GRACE -> endDrag(DragEnd.TAP, out)
            Phase.HOLD_ZOOM, Phase.HOLD_SCROLL -> endHold(s.time, out)
            Phase.ARMED -> lastTap = null
            Phase.MENU -> {
                lastTap = null
                val f = s.fingers.firstOrNull() ?: Finger(0, lastX, lastY)
                events += GestureEvent.RadialReleased(f.x, f.y)
            }
            else -> {}
        }
        clearTouch()
    }

    private fun normalUp(f: Finger, time: Long, out: MutableList<InputEvent>) {
        val (x, y) = if (mode == TouchMode.DIRECT) vx(f.x) to vy(f.y) else cursorX to cursorY
        when {
            pressed -> {
                out += pointer(PointerAction.Up, x, y)
                // A short unmoved press that only the timer made is still a tap for a double click, and for the keyboard's lift.
                val tapped = pressedByTimer && !moved && time - downAt < TAP_MAX
                lastTap = if (tapped) Tap(time, downX, downY, click = true) else null
                if (tapped && mode == TouchMode.DIRECT) anchor = x to y
            }
            !moved && time - downAt < TAP_MAX -> {
                // A tap that sends nothing keeps its travel: it moved the cursor and did nothing else.
                if (map[Gesture.TAP] != GestureAction.None) undoSlopTravel(out)
                tap(time, out)
            }
            else -> lastTap = null
        }
        pressed = false
    }

    /** A tap that ended an ordinary touch, at the touched point or the cursor. */
    private fun tap(time: Long, out: MutableList<InputEvent>) {
        if (mode == TouchMode.DIRECT) anchor = tapX() to tapY()
        tapAction(tapX(), tapY(), continuesClick, out)
        lastTap = Tap(time, downX, downY, click = map[Gesture.TAP] == GestureAction.Click)
    }

    private fun tapAction(x: Float, y: Float, continued: Boolean, out: MutableList<InputEvent>) {
        when (map[Gesture.TAP]) {
            GestureAction.Click -> {
                out += pointer(if (continued) PointerAction.DownContinued else PointerAction.Down, x, y)
                out += pointer(PointerAction.Up, x, y)
            }
            GestureAction.RIGHT_CLICK -> out += InputEvent.RightClick(x, y)
            else -> {}
        }
    }

    // MARK: a touch that may continue the previous tap

    /** The second tap of a double tap, released unmoved before `TAP_MAX`. */
    private fun secondTap(f: Finger, time: Long, out: MutableList<InputEvent>) {
        out += if (mode == TouchMode.DIRECT) move(vx(f.x), vy(f.y)) else move(cursorX, cursorY)
        tap(time, out)
    }

    /** The continuing touch held or moved: rule 2 of the judgement. */
    private fun hold(f: Finger, time: Long, out: MutableList<InputEvent>) {
        judgeDeadline = null
        events += GestureEvent.DoubleTapHeld(secondGap, secondDistance / density, time - downAt, moved = hypot(f.x - downX, f.y - downY) > slop)
        val action = map[Gesture.DOUBLE_TAP_HOLD]
        if (action == GestureAction.Zoom || action == GestureAction.Scroll) return startHold(action, f, time)
        if (mode == TouchMode.TRACKPAD) {
            // Grabbed as before, with the Move ahead of the Down; from then on it is a trackpad drag.
            out += move(cursorX, cursorY)
            out += pointer(PointerAction.Down, cursorX, cursorY)
            held = true
            phase = Phase.DRAGGING
            events += GestureEvent.DragStarted(DragStart.DOUBLE_TAP_HOLD)
            if (hypot(f.x - downX, f.y - downY) > slop) {
                val (dx, dy) = beyondSlop(f.x - downX, f.y - downY)
                moveCursor(dx, dy, speedOf(f.x, f.y, time))
                out += pointer(PointerAction.Drag, cursorX, cursorY)
            }
            refCount = 1
            track(f.x, f.y, time)
            return
        }
        phase = Phase.NORMAL
        out += move(vx(downX), vy(downY))
        press(vx(downX), vy(downY), PointerAction.Down, out)
        if (hypot(f.x - downX, f.y - downY) > slop) {
            moved = true
            out += pointer(PointerAction.Drag, vx(f.x), vy(f.y))
        }
        track(f, time)
    }

    /**
     * The continuing touch moved before it was held still: a tap followed by a quick move means to move
     * the cursor, not to grab, so it carries on as an ordinary touch.
     */
    private fun notHeld(f: Finger, time: Long, out: MutableList<InputEvent>) {
        judgeDeadline = null
        lastTap = null
        startNormal(out)
        normalMove(f, time, out)
    }

    /** Zoom or scroll from where the hold began: the travel within the slop only told it from a tap. */
    private fun startHold(action: GestureAction, f: Finger, time: Long) {
        phase = if (action == GestureAction.Zoom) Phase.HOLD_ZOOM else Phase.HOLD_SCROLL
        holdFinger = touchFinger
        lastTap = null
        holdAtX = vx(downX)
        holdAtY = vy(downY)
        recent.clear()
        recent += Sent(time, 0f, 0f)
        track(f, time)
    }

    /**
     * Down zooms in and up zooms out, as one-finger zoom on a map; a scroll follows the finger as two
     * fingers do. The scroll is returned, to go out; a zoom changes only the viewport.
     */
    private fun holdMove(s: Sample): InputEvent? {
        val f = s.fingers.firstOrNull { it.id == holdFinger } ?: return null
        val dx = f.x - lastX
        val dy = f.y - lastY
        track(f, s.time)
        if (phase == Phase.HOLD_SCROLL) {
            val e = InputEvent.Scroll(scroll.apply(dx / viewport.zoom), scroll.apply(dy / viewport.zoom))
            record(s.time, e.dx, e.dy)
            return e
        }
        if (placement.frameW > 0f && placement.frameH > 0f) {
            val zoom = (viewport.zoom * 2f.pow(dy / (DTH_ZOOM_DP * density))).coerceIn(1f, placement.maxZoom)
            viewport = zoomedAbout(holdAtX, holdAtY, downX, downY, zoom)
        }
        return null
    }

    /** The hold's finger lifted: the scroll glides on, and the rest of the touch does nothing. */
    private fun endHold(time: Long, out: MutableList<InputEvent>) {
        // The zoom may have left the cursor off screen, as a pinch may.
        if (phase == Phase.HOLD_ZOOM && mode == TouchMode.TRACKPAD) cursorInsideBand(out)
        if (phase == Phase.HOLD_SCROLL) fling(time, fromStrip = false)
        phase = Phase.SPENT
    }

    // MARK: two fingers

    private fun pointerDown(s: Sample, out: MutableList<InputEvent>) {
        when (phase) {
            Phase.STRIP, Phase.HOLD_ZOOM, Phase.HOLD_SCROLL -> return
            // Fingers joining a grab or its preparation only move where the centre is measured from.
            Phase.DRAGGING, Phase.ARMED -> return rebase(s)
            Phase.MENU -> {
                events += GestureEvent.RadialCancelled
                // Not armed in the direct mode: the armed grab presses at the trackpad cursor,
                // which that mode never moves, so it would press the middle of the video.
                if (mode == TouchMode.DIRECT) {
                    phase = Phase.SPENT
                    return
                }
                phase = Phase.ARMED
                return rebase(s)
            }
            Phase.GRACE -> return if (s.fingers.size >= 3) resumeDrag(s) else rebase(s)
            Phase.THREE_FINGER -> {
                // With three-finger drag, four only before the grab, which THREE_DRAG_WAIT holds for a
                // late fourth finger; otherwise only before the three travelled. A grab never swipes.
                val four = s.fingers.size == 4 && !lifted && (threeFingerDrag || !threeMoved)
                if (four) startFour(s) else phase = Phase.SPENT
                return
            }
            // A fifth finger is no gesture of ours: the touch does nothing until every finger lifts.
            Phase.FOUR_FINGER -> {
                phase = Phase.SPENT
                return
            }
            else -> {}
        }
        if (phase == Phase.TWO_FINGER && s.fingers.size == 3 && !twoMoved && !graceExit) return startThree(s)
        if (s.fingers.size != 2 || pressed) return
        if (phase != Phase.NORMAL && phase != Phase.JUDGING && phase != Phase.OUTSIDE) return
        undoSlopTravel(out)
        startTwo(s)
    }

    private fun startTwo(s: Sample) {
        phase = Phase.TWO_FINGER
        pressDeadline = null
        longPressDeadline = null
        judgeDeadline = null
        twoMoved = false
        twoJudged = false
        pinching = false
        twoStart = s.fingers.take(2)
        centerX = (s.fingers[0].x + s.fingers[1].x) / 2
        centerY = (s.fingers[0].y + s.fingers[1].y) / 2
        pinchDistance = spread(s)
        twoHoldDeadline = if (map[Gesture.TWO_FINGER_LONG_PRESS] != GestureAction.None && !cursorMoved && !graceExit) s.time + LONG_PRESS else null
        recent.clear()
        recent += Sent(s.time, 0f, 0f)
    }

    private fun twoFingerMove(s: Sample, out: MutableList<InputEvent>) {
        if (s.fingers.size < 2) return
        val cx = (s.fingers[0].x + s.fingers[1].x) / 2
        val cy = (s.fingers[0].y + s.fingers[1].y) / 2
        val d = spread(s)
        if (!twoJudged) {
            val gap = abs(d - pinchDistance)
            val shift = hypot(cx - centerX, cy - centerY)
            if (!twoMoved && gap <= slop && shift <= slop) return
            twoMoved = true
            twoHoldDeadline = null
            // Weighing the gap against the centre would favour a pinch: a finger setting off late moves
            // the gap by its whole travel and the centre by half. Against the fingers' travel, parting or
            // closing comes near 1 and moving together near 0, once both fingers are under way.
            val travel = s.fingers.zip(twoStart).sumOf { (f, o) -> hypot(f.x - o.x, f.y - o.y).toDouble() }.toFloat()
            val share = gap / travel
            val pinch = share > PINCH_SHARE
            val decided = if (pinch) gap > PINCH_SLOPS * slop else shift > slop
            if (!decided) return
            twoJudged = true
            pinching = pinch
            events += GestureEvent.TwoFingerJudged(pinch, share, gap / density, travel / density)
        }
        if (pinching) {
            pinch(cx, cy, d)
        } else if (map[Gesture.TWO_FINGER_SWIPE] == GestureAction.Scroll) {
            // Travel on the zoomed video covers less of it, so the Mac's content follows the finger.
            val e = InputEvent.Scroll(scroll.apply((cx - centerX) / viewport.zoom), scroll.apply((cy - centerY) / viewport.zoom))
            out += e
            record(s.time, e.dx, e.dy)
        }
        centerX = cx
        centerY = cy
    }

    /** Zooms by the gap's change, keeping the video point under the fingers' centre in place as far as the placement allows. */
    private fun pinch(cx: Float, cy: Float, d: Float) {
        if (pinchDistance > 0f && d > 0f && placement.frameW > 0f && placement.frameH > 0f) {
            val zoom = (viewport.zoom * d / pinchDistance).coerceIn(1f, placement.maxZoom)
            viewport = zoomedAbout(vx(cx), vy(cy), cx, cy, zoom)
        }
        pinchDistance = d
    }

    /**
     * Fill screen: below the zoom that just covers the screen, zooms to it, keeping the video point
     * ([videoX], [videoY]) where it shows; otherwise back to 1x. A trackpad cursor left outside the
     * edge band comes just inside, as after a pinch.
     */
    fun fillScreen(videoX: Float, videoY: Float): Output = withViewport { fill(videoX, videoY, it) }

    /** Fill screen from a gesture: about the video under the fingers at ([x], [y]), or the trackpad cursor. */
    private fun fillAbout(x: Float, y: Float, out: MutableList<InputEvent>) =
        if (mode == TouchMode.DIRECT) fill(vx(x), vy(y), out) else fill(cursorX, cursorY, out)

    private fun fill(videoX: Float, videoY: Float, out: MutableList<InputEvent>) {
        val v = viewport
        val fill = placement.fillZoom
        if (v.zoom < fill - FILL_SLACK) {
            viewport = zoomedAbout(videoX, videoY, placement.x.toPixel(videoX, v.zoom, v.left), placement.y.toPixel(videoY, v.zoom, v.top), fill)
        } else {
            viewport = Viewport()
            // Back at 1x with the keyboard up, the point being typed at goes above the row again, as after a reset.
            keyboardMargin?.let(::lift)
        }
        if (mode == TouchMode.TRACKPAD) cursorInsideBand(out)
    }

    /** The viewport at [zoom] with the video point ([videoX], [videoY]) at the pixel ([px], [py]), as far as the placement allows. */
    private fun zoomedAbout(videoX: Float, videoY: Float, px: Float, py: Float, zoom: Float) =
        Viewport(zoom, placement.x.startFor(videoX, px, zoom), placement.y.startFor(videoY, py, zoom)).clamped(placement)

    /** Brings the trackpad cursor just inside the edge band, and the Mac's with it. */
    private fun cursorInsideBand(out: MutableList<InputEvent>) {
        val (x, y) = insideBand(cursorX, cursorY)
        if (x == cursorX && y == cursorY) return
        cursorX = x
        cursorY = y
        out += move(x, y)
    }

    private fun spread(s: Sample) = hypot(s.fingers[0].x - s.fingers[1].x, s.fingers[0].y - s.fingers[1].y)

    private fun twoFingerUp(s: Sample, out: MutableList<InputEvent>) {
        lastTap = null
        twoFingerFling(s.time)
        // The pinch may have left the cursor off screen.
        if (pinching && mode == TouchMode.TRACKPAD) cursorInsideBand(out)
        if (graceExit || twoMoved || cursorMoved || s.time - downAt >= TAP_MAX) return
        // Clicks land under the first finger; Fill screen and the radial menu go about the centre.
        fire(Gesture.TWO_FINGER_TAP, twoCenterX(), twoCenterY(), out, clickAt = videoAt(downX, downY))
    }

    /** The two fingers held still: the long press fires about where they landed, and the touch is spent. */
    private fun twoFingerHold(out: MutableList<InputEvent>) {
        lastTap = null
        phase = Phase.SPENT
        fire(Gesture.TWO_FINGER_LONG_PRESS, twoCenterX(), twoCenterY(), out)
    }

    private fun twoCenterX() = (twoStart[0].x + twoStart[1].x) / 2

    private fun twoCenterY() = (twoStart[0].y + twoStart[1].y) / 2

    private fun twoFingerFling(time: Long) {
        if (twoJudged && !pinching && flingDeadline == null) fling(time, fromStrip = false)
    }

    // MARK: three and four fingers

    private val threeFingerDrag get() = mode == TouchMode.TRACKPAD && map[Gesture.THREE_FINGER_DRAG] == GestureAction.Drag

    private val threeFingerPan get() = mode == TouchMode.DIRECT && map[Gesture.THREE_FINGER_DRAG] == GestureAction.Pan

    /**
     * Only before the two fingers moved past the slop: once they did, the third finger joins them.
     * A grab waits [THREE_DRAG_WAIT] for a fourth finger, so a four-finger swipe never grabs.
     */
    private fun startThree(s: Sample) {
        phase = Phase.THREE_FINGER
        twoHoldDeadline = null
        threeMoved = false
        lifted = false
        rebase(s)
        if (threeFingerDrag) threeDragDeadline = s.time + THREE_DRAG_WAIT
    }

    /**
     * The centre of exactly three fingers, until one lifts: the centre of the two left would jump
     * by half the gap, and a tap whose fingers lift one by one would read as a move.
     */
    private fun threeFingerMove(s: Sample, out: MutableList<InputEvent>) {
        if (lifted || s.fingers.size != 3) return
        val (x, y) = center(s)
        if (hypot(x - refX, y - refY) > slop) threeMoved = true
        if (threeFingerPan) {
            if (!threeMoved) return
            moveView(x - lastX, y - lastY)
            return track(x, y, s.time)
        }
        if (!threeFingerDrag) return
        lastSpeed = speedOf(x, y, s.time)
        track(x, y, s.time)
        if (threeMoved && threeDragDeadline == null) pressThree(out)
    }

    private fun pressThree(out: MutableList<InputEvent>) {
        threeDragDeadline = null
        startDrag(DragStart.THREE_FINGER, lastX - refX, lastY - refY, lastSpeed, out)
    }

    private fun startFour(s: Sample) {
        phase = Phase.FOUR_FINGER
        threeDragDeadline = null
        lifted = false
        rebase(s)
        fourRadius = radius(s, refX, refY)
    }

    /**
     * As three fingers: the centre of exactly four, until one lifts. Their spread from the centre
     * changing first makes a pinch or a spread, the centre travelling first a swipe. The spread is
     * weighed by its share, since a thumb closing on three fingers moves the centre a quarter as far.
     */
    private fun fourFingerMove(s: Sample, out: MutableList<InputEvent>) {
        if (lifted || s.fingers.size != 4) return
        val (x, y) = center(s)
        val dx = x - refX
        val dy = y - refY
        val change = radius(s, x, y) - fourRadius
        val g = when {
            abs(change) >= max(FOUR_PINCH_SHARE * fourRadius, FOUR_PINCH_DISTANCE * density) ->
                if (change < 0) Gesture.FOUR_FINGER_PINCH else Gesture.FOUR_FINGER_SPREAD
            hypot(dx, dy) <= SWIPE_DISTANCE * density -> return
            abs(dx) > abs(dy) -> if (dx < 0) Gesture.FOUR_FINGER_SWIPE_LEFT else Gesture.FOUR_FINGER_SWIPE_RIGHT
            dy < 0 -> Gesture.FOUR_FINGER_SWIPE_UP
            else -> Gesture.FOUR_FINGER_SWIPE_DOWN
        }
        fire(g, refX, refY, out)
        phase = Phase.SPENT
    }

    /** The fingers' mean distance from ([x], [y]). */
    private fun radius(s: Sample, x: Float, y: Float) = s.fingers.sumOf { hypot(it.x - x, it.y - y).toDouble() }.toFloat() / s.fingers.size

    /** Where a gesture at ([x], [y]) on the frame acts on the video: under the fingers, or at the trackpad cursor. */
    private fun videoAt(x: Float, y: Float) = if (mode == TouchMode.DIRECT) vx(x) to vy(y) else cursorX to cursorY

    /**
     * Does what [g] is given, for fingers at ([x], [y]) on the frame. A right click and a click land
     * at [clickAt]; Fill screen keeps the video under the fingers in place; the radial menu opens
     * there, left open as after a long press lifted in its middle.
     */
    private fun fire(g: Gesture, x: Float, y: Float, out: MutableList<InputEvent>, clickAt: Pair<Float, Float> = videoAt(x, y)) {
        val a = map[g]
        if (a == GestureAction.None) return
        events += GestureEvent.Fired(g)
        val (cx, cy) = clickAt
        when (a) {
            GestureAction.Click -> {
                out += pointer(PointerAction.Down, cx, cy)
                out += pointer(PointerAction.Up, cx, cy)
            }
            GestureAction.RadialMenu -> {
                val (px, py) = videoAt(x, y)
                if (mode == TouchMode.DIRECT) anchor = px to py
                events += GestureEvent.RadialOpened(x, y, px, py)
                events += GestureEvent.RadialReleased(x, y)
            }
            is GestureAction.Run -> when (a.entry.send) {
                RadialSend.RightClick -> out += InputEvent.RightClick(cx, cy)
                RadialSend.FillScreen -> fillAbout(x, y, out)
                else -> command = a.entry
            }
            else -> {}
        }
    }

    private fun center(s: Sample) = s.fingers.let { f -> f.sumOf { it.x.toDouble() }.toFloat() / f.size to f.sumOf { it.y.toDouble() }.toFloat() / f.size }

    // MARK: a trackpad grab

    /** Measures from this sample's centre on: a finger more or less moves the centre, not the cursor. */
    private fun rebase(s: Sample) {
        val (x, y) = center(s)
        refX = x
        refY = y
        refCount = s.fingers.size
        track(x, y, s.time)
    }

    /**
     * Presses at the cursor and drags it by the part of ([dx], [dy]) past the slop: no Move first, the
     * cursor has not moved. The first Drag goes out even when nothing is left of the travel.
     */
    private fun startDrag(by: DragStart, dx: Float, dy: Float, speed: Float, out: MutableList<InputEvent>) {
        held = true
        phase = Phase.DRAGGING
        out += pointer(PointerAction.Down, cursorX, cursorY)
        val (sx, sy) = beyondSlop(dx, dy)
        moveCursor(sx, sy, speed)
        out += pointer(PointerAction.Drag, cursorX, cursorY)
        events += GestureEvent.DragStarted(by)
    }

    private fun dragMove(s: Sample, out: MutableList<InputEvent>) {
        if (s.fingers.size != refCount) return rebase(s)
        val (x, y) = center(s)
        moveCursor(x - lastX, y - lastY, speedOf(x, y, s.time))
        out += pointer(PointerAction.Drag, cursorX, cursorY)
        track(x, y, s.time)
    }

    /** The long press held still: the next move past the slop presses. */
    private fun arm() {
        phase = Phase.ARMED
        refX = lastX
        refY = lastY
        refCount = 1
        events += GestureEvent.Armed
    }

    private fun armedMove(s: Sample, out: MutableList<InputEvent>) {
        if (s.fingers.size != refCount) return rebase(s)
        val (x, y) = center(s)
        val speed = speedOf(x, y, s.time)
        if (hypot(x - refX, y - refY) > slop) startDrag(DragStart.LONG_PRESS, x - refX, y - refY, speed, out)
        track(x, y, s.time)
    }

    private fun startGrace(s: Sample) {
        clearTouch()
        phase = Phase.GRACE
        downAt = s.time
        rebase(s)
    }

    private fun resumeDrag(s: Sample) {
        graceDeadline = null
        lastTap = null
        phase = Phase.DRAGGING
        rebase(s)
        events += GestureEvent.DragResumed
    }

    /** A move before three fingers drops the grab; the touch goes on from this sample as a new one. */
    private fun graceMove(s: Sample, out: MutableList<InputEvent>) {
        if (s.fingers.size != refCount) return rebase(s)
        val (x, y) = center(s)
        if (hypot(x - refX, y - refY) <= slop) return track(x, y, s.time)
        endDrag(DragEnd.MOVE, out)
        graceExit = true
        downAt = s.time
        if (s.fingers.size == 1) {
            phase = Phase.NORMAL
            moved = true
            downX = x
            downY = y
            track(x, y, s.time)
        } else {
            startTwo(s)
        }
    }

    private fun endDrag(by: DragEnd, out: MutableList<InputEvent>) {
        out += pointer(PointerAction.Up, cursorX, cursorY)
        held = false
        graceDeadline = null
        lastTap = null
        events += GestureEvent.DragEnded(by)
    }

    private fun cancel(out: MutableList<InputEvent>) = dropAll(DragEnd.CANCEL, out)

    private fun dropAll(by: DragEnd, out: MutableList<InputEvent>) {
        endFling()
        if (phase == Phase.STRIP) events += GestureEvent.StripReleased
        if (pressed) out += pointer(PointerAction.Up, if (mode == TouchMode.DIRECT) vx(lastX) else cursorX, if (mode == TouchMode.DIRECT) vy(lastY) else cursorY)
        if (held) endDrag(by, out)
        if (phase == Phase.MENU) events += GestureEvent.RadialCancelled
        lastTap = null
        clearTouch()
    }

    // MARK: time

    private fun expire(now: Long, out: MutableList<InputEvent>) {
        while (true) {
            val d = nextDeadline() ?: return
            if (now < d) return
            when (d) {
                judgeDeadline -> hold(Finger(0, lastX, lastY), d, out)
                pressDeadline -> {
                    pressDeadline = null
                    if (!pressed && !moved) {
                        press(vx(downX), vy(downY), if (continuesClick) PointerAction.DownContinued else PointerAction.Down, out)
                        pressedByTimer = true
                    }
                }
                longPressDeadline -> {
                    longPressDeadline = null
                    if (!moved) if (mode == TouchMode.TRACKPAD) trackpadLongPress(out) else longPress(out)
                }
                panDeadline -> pan(d, out)
                twoHoldDeadline -> {
                    twoHoldDeadline = null
                    if (phase == Phase.TWO_FINGER && !twoMoved) twoFingerHold(out)
                }
                threeDragDeadline -> {
                    threeDragDeadline = null
                    if (phase == Phase.THREE_FINGER && threeMoved && !lifted) pressThree(out)
                }
                graceDeadline -> {
                    // A touch still down when the grace ends does nothing until every finger lifts.
                    val touching = phase == Phase.GRACE
                    endDrag(DragEnd.TIMEOUT, out)
                    if (touching) phase = Phase.SPENT
                }
                flingDeadline -> flingStep(d, out)
                stripHoldDeadline -> {
                    stripHoldDeadline = null
                    relocating = true
                    events += GestureEvent.StripHeld
                }
            }
        }
    }

    // MARK: the scroll strip and the fling

    private fun startStrip(f: Finger, time: Long, horizontal: Boolean) {
        clearTouch()
        phase = Phase.STRIP
        downAt = time
        stripFinger = f.id
        stripHorizontal = horizontal
        stripStartX = f.x
        stripStartY = f.y
        track(f, time)
        recent += Sent(time, 0f, 0f)
        lastTap = null
        // The bottom band stays where it is: only the side band moves under a held finger.
        stripHoldDeadline = if (horizontal) null else time + LONG_PRESS
        events += GestureEvent.StripTouched(f.x, f.y, horizontal)
    }

    /** Only the finger that landed on a band scrolls, and only along it; past the slop it is no hold. */
    private fun stripMove(s: Sample, out: MutableList<InputEvent>) {
        val f = s.fingers.firstOrNull { it.id == stripFinger } ?: return
        val d = if (stripHorizontal) f.x - lastX else f.y - lastY
        track(f, s.time)
        events += GestureEvent.StripMoved(f.x, f.y)
        if (relocating) return
        if (hypot(f.x - stripStartX, f.y - stripStartY) > slop) stripHoldDeadline = null
        val e = if (stripHorizontal) InputEvent.Scroll(scroll.apply(d * STRIP_GEAR), 0f) else InputEvent.Scroll(0f, scroll.apply(d * STRIP_GEAR))
        record(s.time, e.dx, e.dy)
        if (d == 0f) return
        out += e
        stripTravel += abs(d)
        val detent = STRIP_DETENT * density
        while (stripTravel >= detent) {
            stripTravel -= detent
            events += GestureEvent.StripTick
        }
    }

    /** Keeps the scrolls of the last [FLING_WINDOW] and the one before them, all the speed at a lift is measured from. */
    private fun record(time: Long, dx: Float, dy: Float) {
        recent += Sent(time, dx, dy)
        while (recent.size > 2 && recent[1].time <= time - FLING_WINDOW) recent.removeAt(0)
    }

    /**
     * The lift of a scroll: with [inertia], it glides on at the speed of the last [FLING_WINDOW],
     * measured over the scrolls sent, so the speed and direction settings carry over.
     */
    private fun fling(time: Long, fromStrip: Boolean) {
        if (!inertia) return
        val from = time - FLING_WINDOW
        val inWindow = recent.filter { it.time > from }
        if (inWindow.isEmpty()) return
        // With a scroll before the window, the window is all moving time; without, the touch began inside it.
        val start = if (recent.any { it.time <= from }) from else inWindow.first().time
        val span = (time - start).coerceAtLeast(FLING_STEP).toFloat()
        val vx = inWindow.sumOf { it.dx.toDouble() }.toFloat() / span
        val vy = inWindow.sumOf { it.dy.toDouble() }.toFloat() / span
        val speed = hypot(vx, vy)
        if (speed < FLING_MIN) return
        flingVx = vx
        flingVy = vy
        flingAt = time
        flingDeadline = time + FLING_STEP
        events += GestureEvent.FlingStarted(speed / density * 1000f, fromStrip)
    }

    /** Sends the distance the fling covers since its last step, and slows it down by as much. */
    private fun flingStep(now: Long, out: MutableList<InputEvent>) {
        val dt = (now - flingAt).toFloat()
        flingAt = now
        val k = exp(-dt / FLING_TAU)
        out += InputEvent.Scroll(flingVx * FLING_TAU * (1f - k), flingVy * FLING_TAU * (1f - k))
        flingVx *= k
        flingVy *= k
        if (hypot(flingVx, flingVy) < FLING_STOP) endFling() else flingDeadline = now + FLING_STEP
    }

    private fun endFling() {
        if (flingDeadline == null) return
        flingDeadline = null
        events += GestureEvent.FlingEnded
    }

    // MARK: following the finger or the cursor where the video runs past the screen

    /**
     * A direct-mode drag with the finger in the edge band of the video on screen pans the viewport
     * every [PAN_STEP], zoomed or with the soft keyboard up; an axis with nothing to pan to has no band.
     * An unmoved press pans only once it outlasts a tap: the band lies right above the key row, where
     * the field being typed in was just lifted, and a slow tap there would otherwise drag.
     */
    private fun armPan(now: Long) {
        val panning = mode == TouchMode.DIRECT && phase == Phase.NORMAL && pressed && edgeDepth(lastX, lastY) != (0f to 0f)
        if (!panning) {
            panDeadline = null
        } else if (panDeadline == null) {
            pannedAt = if (moved) now else max(now, downAt + TAP_MAX - PAN_STEP)
            panDeadline = pannedAt + PAN_STEP
        }
    }

    private fun pan(now: Long, out: MutableList<InputEvent>) {
        val (dx, dy) = edgeDepth(lastX, lastY)
        val seconds = (now - pannedAt) / 1000f
        pannedAt = now
        panDeadline = now + PAN_STEP
        val old = viewport
        val spanX = placement.x.visible(old.zoom)
        val spanY = placement.y.visible(old.zoom)
        viewport = old.copy(left = old.left + dx * PAN_SPEED * spanX * seconds, top = old.top + dy * PAN_SPEED * spanY * seconds).clamped(placement)
        // At the video's edge the drag point under the finger has not moved, so nothing is resent.
        if (viewport != old) out += pointer(PointerAction.Drag, vx(lastX), vy(lastY))
    }

    /**
     * How deep in the edge band of the video on screen a point is, from -1 (at the left or top edge)
     * to 1. An axis on which the zoomed video fits the screen has no band: there is nothing to pan to.
     */
    private fun edgeDepth(x: Float, y: Float): Pair<Float, Float> {
        fun depth(a: PlacementAxis, px: Float, start: Float): Float {
            if (a.visible(viewport.zoom) >= 1f) return 0f
            val (lo, hi) = a.shown(viewport.zoom, start)
            val n = ((px - lo) / (hi - lo)).coerceIn(0f, 1f)
            return when {
                n < EDGE_BAND -> -(EDGE_BAND - n) / EDGE_BAND
                n > 1f - EDGE_BAND -> (n - (1f - EDGE_BAND)) / EDGE_BAND
                else -> 0f
            }
        }
        return depth(placement.x, x, viewport.left) to depth(placement.y, y, viewport.top)
    }

    /**
     * The trackpad cursor moved into the edge band of the part of the video on screen: the viewport
     * moves so it is just inside. An axis on which the whole video shows does not move.
     */
    private fun follow() {
        val v = viewport
        fun shift(c: Float, start: Float, span: Float): Float {
            if (span >= 1f) return start
            val band = span * EDGE_BAND
            return when {
                c < start + band -> c - band
                c > start + span - band -> c - span + band
                else -> start
            }
        }
        viewport = v.copy(left = shift(cursorX, v.left, placement.x.visible(v.zoom)), top = shift(cursorY, v.top, placement.y.visible(v.zoom))).clamped(placement)
    }

    /** The nearest point to ([x], [y]) inside the edge band of the part of the video on screen. */
    private fun insideBand(x: Float, y: Float): Pair<Float, Float> {
        val v = viewport
        fun inside(c: Float, start: Float, span: Float): Float {
            if (span >= 1f) return c
            val band = span * EDGE_BAND
            return c.coerceIn(start + band, start + span - band)
        }
        return inside(x, v.left, placement.x.visible(v.zoom)) to inside(y, v.top, placement.y.visible(v.zoom))
    }

    /**
     * The slop alone lets a slow aim, whose cursor visibly moves, open the menu: on the trackpad a
     * touch whose path left the still radius, or that is still moving as the wait ends, is no long
     * press. A finger rolls as it lands, so the end of the wait tells a hold from an aim better.
     */
    private fun trackpadLongPress(out: MutableList<InputEvent>) {
        val recent = stillTravel - settledTravel
        val still = stillTravel <= LONG_PRESS_STILL_DP * density && recent <= LONG_PRESS_SETTLE_DP * density
        events += GestureEvent.LongPressJudged(still, stillFarthest / density, stillTravel / density, recent / density)
        if (still) longPress(out)
    }

    private fun longPress(out: MutableList<InputEvent>) {
        undoSlopTravel(out)
        when (map[Gesture.LONG_PRESS]) {
            is GestureAction.Run -> {
                pressDeadline = null
                phase = Phase.SPENT
                fire(Gesture.LONG_PRESS, downX, downY, out)
            }
            GestureAction.Drag -> arm()
            GestureAction.RadialMenu -> {
                phase = Phase.MENU
                if (mode == TouchMode.DIRECT) anchor = tapX() to tapY()
                events += GestureEvent.RadialOpened(lastX, lastY, tapX(), tapY())
            }
            else -> {}
        }
    }

    // MARK: helpers

    private fun press(x: Float, y: Float, action: PointerAction, out: MutableList<InputEvent>) {
        pressed = true
        out += pointer(action, x, y)
    }

    private fun clearTouch() {
        phase = Phase.IDLE
        moved = false
        pressed = false
        pressedByTimer = false
        continuesClick = false
        pressDeadline = null
        longPressDeadline = null
        stillTravel = 0f
        stillFarthest = 0f
        settledTravel = 0f
        judgeDeadline = null
        twoMoved = false
        twoJudged = false
        cursorMoved = false
        pinching = false
        panDeadline = null
        threeMoved = false
        lifted = false
        threeDragDeadline = null
        twoHoldDeadline = null
        graceExit = false
        refCount = 0
        startCursor = null
        stripFinger = -1
        stripTravel = 0f
        recent.clear()
        relocating = false
        stripHoldDeadline = null
    }

    private fun finger(s: Sample) = s.fingers.firstOrNull()

    private fun track(f: Finger, time: Long) = track(f.x, f.y, time)

    private fun track(x: Float, y: Float, time: Long) {
        lastX = x; lastY = y; lastAt = time
    }

    private fun speedOf(f: Finger, time: Long) = speedOf(f.x, f.y, time)

    /** Finger (or centre) speed in dp/s between this sample and the last. */
    private fun speedOf(x: Float, y: Float, time: Long): Float {
        val dt = (time - lastAt).coerceAtLeast(1)
        return hypot(x - lastX, y - lastY) / density / dt * 1000f
    }

    private fun moveCursor(dxPx: Float, dyPx: Float, speedDp: Float) {
        val width = placement.frameW
        val height = placement.frameH
        if (width <= 0f || height <= 0f) return
        // Zoomed in, the same travel crosses less of the video, so the cursor stays under the eye.
        val gain = cursorSpeed.cursor * (1f + min(speedDp / 1000f, 2f)) / viewport.zoom
        // dp over dp: the view's size in dp is what the travel is measured against.
        cursorX = (cursorX + dxPx * gain / width).coerceIn(0f, 1f)
        cursorY = (cursorY + dyPx * gain / height).coerceIn(0f, 1f)
        follow()
    }

    /** ([dx], [dy]) shortened by the slop, the travel that only told a grab from a tap. */
    private fun beyondSlop(dx: Float, dy: Float): Pair<Float, Float> {
        val d = hypot(dx, dy)
        if (d <= slop) return 0f to 0f
        val k = 1f - slop / d
        return dx * k to dy * k
    }

    private fun tapX() = if (mode == TouchMode.DIRECT) vx(downX) else cursorX

    private fun tapY() = if (mode == TouchMode.DIRECT) vy(downY) else cursorY

    /** Off the video on screen: on a black bar, or past the screen's edge. */
    private fun outside(f: Finger): Boolean {
        fun off(a: PlacementAxis, px: Float, start: Float): Boolean {
            if (a.frame <= 0f) return false
            val (lo, hi) = a.shown(viewport.zoom, start)
            return px < lo || px > hi
        }
        return placement.frameW > 0f && placement.frameH > 0f && (off(placement.x, f.x, viewport.left) || off(placement.y, f.y, viewport.top))
    }

    private fun vx(x: Float) = placement.x.toVideo(x, viewport.zoom, viewport.left)

    private fun vy(y: Float) = placement.y.toVideo(y, viewport.zoom, viewport.top)

    private fun move(x: Float, y: Float) = pointer(PointerAction.Move, x, y)

    private fun pointer(a: PointerAction, x: Float, y: Float) = InputEvent.Pointer(a, x, y)

    companion object {
        const val PRESS_DELAY = 120L
        const val TAP_MAX = 250L
        const val DOUBLE_GAP = 300L
        /** How long a touch continuing a tap stays still before its move counts as a double tap and hold. */
        const val HOLD_STILL = 200L
        /** How long a touch continuing a tap stays down, unmoved, before it grabs, zooms or scrolls by itself. */
        const val HOLD_WAIT = 400L
        /** How soon after a tap, and how near it in dp, a touch lands to become a double tap and hold; a double click reaches further. */
        const val HOLD_GAP = 150L
        const val HOLD_SLOP_DP = 24f
        const val LONG_PRESS = 500L
        /** How far, in dp, a trackpad finger may travel in all and still long-press; a tap still has the whole slop. */
        const val LONG_PRESS_STILL_DP = 6f
        /** The end of a trackpad long press's wait, in ms, in which the finger must have stayed put. */
        const val LONG_PRESS_SETTLE = 150L
        /** How far, in dp, a trackpad finger may travel in that end of the wait and still long-press. */
        const val LONG_PRESS_SETTLE_DP = 2f
        const val PAN_STEP = 16L
        /** How far, in dp, the four fingers' centre moves before the swipe counts. */
        const val SWIPE_DISTANCE = 48f
        /** The share of their mean distance from the centre, and the dp, that four fingers close or open by for a pinch or a spread. */
        const val FOUR_PINCH_SHARE = 0.25f
        const val FOUR_PINCH_DISTANCE = 16f
        /** Double tap and hold's zoom doubles for this many dp of travel down and halves for as many up. */
        const val DTH_ZOOM_DP = 150f
        /** From the third finger to the earliest grab: the time a four-finger swipe takes to land. */
        const val THREE_DRAG_WAIT = 80L
        /** How long a trackpad grab outlasts its last finger. */
        const val GRACE = 500L
        /** The edge band, as a share of the part of the video on screen. */
        const val EDGE_BAND = 0.1f
        /** Shown widths of the video per second at the very edge; deeper in the band is linearly faster. */
        const val PAN_SPEED = 0.5f
        /** Above this share of the two fingers' travel, their gap's change makes a pinch, not a scroll. */
        const val PINCH_SHARE = 0.5f
        /** Touch slops the gap must change by for a pinch, so that a finger setting off late still scrolls. */
        const val PINCH_SLOPS = 3f
        /** Fill screen counts a zoom this close below the fill zoom as filled: `fitInside` rounds the frame to pixels. */
        const val FILL_SLACK = 0.01f
        /** Scroll per pixel of travel along the strip, which is shorter than the screens it scrolls. */
        const val STRIP_GEAR = 2.5f
        /** Travel along the strip, in dp, between two ticks. */
        const val STRIP_DETENT = 28f
        /** How far back, in ms, the speed at the lift is measured. */
        const val FLING_WINDOW = 80L
        const val FLING_STEP = 16L
        /** The fling's speed falls by e in this many ms. */
        const val FLING_TAU = 325f
        /** Scroll pixels per ms: below the first a lift does not glide, below the second a fling stops. */
        const val FLING_MIN = 0.1f
        const val FLING_STOP = 0.05f
    }
}

/** A rectangle of the scroll strip: the whole strip on the screen, or the knob's touch area on the frame, by who holds it. */
data class StripArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/** What happened to a trackpad grab, which the direct mode never makes, to the radial menu, or to two fingers. */
sealed interface GestureEvent {
    /** A long press held still: the next move grabs. */
    data object Armed : GestureEvent

    data class DragStarted(val by: DragStart) : GestureEvent

    /** Three fingers came back within the grace. */
    data object DragResumed : GestureEvent

    /**
     * A long press or another gesture opened the radial menu at ([x], [y]) on the frame, in pixels;
     * ([videoX], [videoY]) is the point on the video a right click there would take, and the one
     * Fill screen keeps in place.
     */
    data class RadialOpened(val x: Float, val y: Float, val videoX: Float, val videoY: Float) : GestureEvent

    data class RadialMoved(val x: Float, val y: Float) : GestureEvent

    /** The finger lifted with the menu open, at ([x], [y]) on the frame. */
    data class RadialReleased(val x: Float, val y: Float) : GestureEvent

    /** The menu closed without a pick: a cancel, a change of touch mode, or a second finger. */
    data object RadialCancelled : GestureEvent

    data class DragEnded(val by: DragEnd) : GestureEvent

    /**
     * A touch that continued a tap became a double tap and hold: [gapMs] after the tap and [distanceDp]
     * from it, after [heldMs] down, by moving past the slop or by staying down.
     */
    data class DoubleTapHeld(val gapMs: Long, val distanceDp: Float, val heldMs: Long, val moved: Boolean) : GestureEvent

    /**
     * A trackpad touch that stayed within the slop for a long press's wait: [fired] when its path also
     * stayed within the still radius and, in the wait's last [GestureRecognizer.LONG_PRESS_SETTLE], within
     * the settle distance. In dp: how far it got from where it touched, its path, and its path in that end.
     */
    data class LongPressJudged(val fired: Boolean, val maxDp: Float, val travelDp: Float, val recentDp: Float) : GestureEvent

    /** A gesture that fires once did what the map gives it. */
    data class Fired(val gesture: Gesture) : GestureEvent

    /** Two fingers were judged a pinch or a scroll: the gap's change and their travel, in dp, and the gap's share of the travel. */
    data class TwoFingerJudged(val pinch: Boolean, val share: Float, val gapDp: Float, val travelDp: Float) : GestureEvent

    /** A finger landed on a scroll band at ([x], [y]) on the frame: the bottom one when [horizontal], else the side one. */
    data class StripTouched(val x: Float, val y: Float, val horizontal: Boolean = false) : GestureEvent

    data class StripMoved(val x: Float, val y: Float) : GestureEvent

    /** The finger on a band travelled another [GestureRecognizer.STRIP_DETENT]. */
    data object StripTick : GestureEvent

    /** The finger left the band: lifted or cancelled. */
    data object StripReleased : GestureEvent

    /** The side band was held still: the finger now moves it. */
    data object StripHeld : GestureEvent

    /** The finger that moved the side band lifted at ([x], [y]) on the frame, where the band now sits. */
    data class StripRelocated(val x: Float, val y: Float) : GestureEvent

    /** A scroll glides on after the lift, starting at [speedDpS] dp/s of scroll; [fromStrip] when the strip's finger lifted, not two fingers or a double tap and hold. */
    data class FlingStarted(val speedDpS: Float, val fromStrip: Boolean) : GestureEvent

    /** The fling faded out, or a touch, a cancel or a change of touch mode stopped it. */
    data object FlingEnded : GestureEvent
}

enum class DragStart(val logName: String) { THREE_FINGER("three_finger"), LONG_PRESS("long_press"), DOUBLE_TAP_HOLD("double_tap_hold") }

enum class DragEnd(val logName: String) { TIMEOUT("timeout"), TAP("tap"), MOVE("move"), CANCEL("cancel"), MODE("mode") }
