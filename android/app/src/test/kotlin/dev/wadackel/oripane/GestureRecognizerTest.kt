package dev.wadackel.oripane

import dev.wadackel.oripane.GestureRecognizer.Finger
import dev.wadackel.oripane.GestureRecognizer.Kind
import dev.wadackel.oripane.GestureRecognizer.Sample
import dev.wadackel.oripane.protocol.InputEvent
import dev.wadackel.oripane.protocol.PointerAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GestureRecognizerTest {
    private val sent = mutableListOf<InputEvent>()
    private val viewports = mutableListOf<Viewport>()
    private val commands = mutableListOf<RadialEntry>()
    private val events = mutableListOf<GestureEvent>()
    private lateinit var r: GestureRecognizer
    private var clock = 0L

    private fun item(i: RadialItem) = GestureAction.Run(RadialEntry.Builtin(i))

    private fun make(
        mode: TouchMode = TouchMode.DIRECT,
        map: TouchMap = TouchMap.defaults(mode),
        speed: SpeedStep = SpeedStep.default,
        scroll: ScrollSetting = ScrollSetting(),
        shared: Map<Gesture, GestureAction> = emptyMap(),
    ) {
        r = GestureRecognizer(mode, shared.entries.fold(map) { m, (g, a) -> m.with(g, a) }, speed, scroll, slop = 10f, doubleTapSlop = 100f, density = 1f)
        r.setViewSize(1000, 2000)
        sent.clear()
        viewports.clear()
        commands.clear()
        events.clear()
        clock = 0
    }

    /** Lets the recognizer's timers run up to [t], as the view's looper would. */
    private fun at(t: Long) {
        while (true) {
            val d = r.nextDeadline() ?: break
            if (d > t) break
            take(r.onTimer(d))
        }
        clock = t
    }

    private fun touch(kind: Kind, t: Long, vararg points: Pair<Float, Float>) {
        at(t)
        take(r.onTouch(Sample(kind, t, points.mapIndexed { i, p -> Finger(i, p.first, p.second) })))
    }

    private fun take(o: GestureRecognizer.Output) {
        sent += o.inputs
        o.viewport?.let { viewports += it }
        o.command?.let { commands += it }
        events += o.events
    }

    /** As [touch], returning that one call's output. */
    private fun touchOut(kind: Kind, t: Long, vararg points: Pair<Float, Float>): GestureRecognizer.Output {
        at(t)
        return r.onTouch(Sample(kind, t, points.mapIndexed { i, p -> Finger(i, p.first, p.second) })).also(::take)
    }

    /** The still radius in the tests' pixels, at a density of 1; the slop of 10 lies beyond it. */
    private val still = GestureRecognizer.LONG_PRESS_STILL_DP

    /** What a trackpad long press that never moved logs as it fires. */
    private val stillHold = GestureEvent.LongPressJudged(fired = true, maxDp = 0f, travelDp = 0f, recentDp = 0f)

    private val noLongPress = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, GestureAction.None)

    /** The trackpad's long press as a grab, which its default no longer is. */
    private val longPressDrag = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, GestureAction.Drag)

    private fun tap(t: Long, x: Float, y: Float, length: Long) {
        touch(Kind.DOWN, t, x to y)
        touch(Kind.UP, t + length, x to y)
    }

    private fun pointers(a: PointerAction) = sent.filterIsInstance<InputEvent.Pointer>().filter { it.action == a }

    private fun actions() = sent.map {
        when (it) {
            is InputEvent.Pointer -> it.action.name
            is InputEvent.RightClick -> "RightClick"
            is InputEvent.Scroll -> "Scroll"
        }
    }

    // MARK: direct mode, defaults

    @Test
    fun aTapIsMoveDownUpAtTheTouchedPoint() {
        make()
        tap(0, 250f, 500f, 60)
        assertThat(actions()).containsExactly("Move", "Down", "Up").inOrder()
        assertThat(pointers(PointerAction.Down).single()).isEqualTo(InputEvent.Pointer(PointerAction.Down, 0.25f, 0.25f))
    }

    @Test
    fun twoQuickTapsMakeTheSecondDownContinueTheClick() {
        make()
        tap(0, 250f, 500f, 60)
        tap(160, 255f, 505f, 60)
        assertThat(actions()).containsExactly("Move", "Down", "Up", "Move", "DownContinued", "Up").inOrder()
    }

    @Test
    fun ordinaryTapsHeldPastThePressDelayStillDoubleClick() {
        make()
        tap(0, 250f, 500f, 130)
        tap(280, 250f, 500f, 130)
        assertThat(actions()).containsExactly("Move", "Down", "Up", "Move", "DownContinued", "Up").inOrder()
    }

    @Test
    fun aHeldSecondTapThatMovesDragsAsTheContinuedClick() {
        make()
        tap(0, 250f, 500f, 130)
        touch(Kind.DOWN, 280, 250f to 500f)
        touch(Kind.MOVE, 420, 350f to 500f)
        touch(Kind.MOVE, 440, 450f to 500f)
        touch(Kind.UP, 460, 450f to 500f)
        assertThat(actions()).containsExactly("Move", "Down", "Up", "Move", "DownContinued", "Drag", "Drag", "Up").inOrder()
    }

    @Test
    fun aSlowSecondTapStartsANewClick() {
        make()
        tap(0, 250f, 500f, 130)
        tap(480, 250f, 500f, 130)
        assertThat(pointers(PointerAction.DownContinued)).isEmpty()
        assertThat(pointers(PointerAction.Down)).hasSize(2)
    }

    @Test
    fun aFarSecondTapIsNotAContinuation() {
        make()
        tap(0, 250f, 500f, 60)
        tap(160, 600f, 500f, 60)
        assertThat(pointers(PointerAction.DownContinued)).isEmpty()
    }

    @Test
    fun movingPastTheSlopPressesAtTheStartAndDrags() {
        make()
        touch(Kind.DOWN, 0, 100f to 100f)
        touch(Kind.MOVE, 16, 300f to 100f)
        touch(Kind.MOVE, 32, 500f to 100f)
        touch(Kind.UP, 48, 500f to 100f)
        assertThat(pointers(PointerAction.Down).single().x).isEqualTo(0.1f)
        assertThat(pointers(PointerAction.Drag).map { it.x }).containsExactly(0.3f, 0.5f).inOrder()
        assertThat(pointers(PointerAction.Up).single().x).isEqualTo(0.5f)
    }

    @Test
    fun aRestingFingerPressesAtThePressDelay() {
        make()
        touch(Kind.DOWN, 0, 100f to 100f)
        at(119)
        assertThat(pointers(PointerAction.Down)).isEmpty()
        at(120)
        assertThat(pointers(PointerAction.Down)).hasSize(1)
    }

    @Test
    fun aTwoFingerTapIsARightClickAtTheFirstFinger() {
        make()
        touch(Kind.DOWN, 0, 400f to 400f)
        touch(Kind.POINTER_DOWN, 20, 400f to 400f, 600f to 400f)
        touch(Kind.POINTER_UP, 80, 400f to 400f, 600f to 400f)
        touch(Kind.UP, 90, 400f to 400f)
        at(400)
        assertThat(pointers(PointerAction.Down)).isEmpty()
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).containsExactly(InputEvent.RightClick(0.4f, 0.2f))
    }

    @Test
    fun twoFingersStayTwoFingersAfterOneLifts() {
        make()
        touch(Kind.DOWN, 0, 400f to 400f)
        touch(Kind.POINTER_DOWN, 20, 400f to 400f, 600f to 400f)
        touch(Kind.POINTER_UP, 40, 400f to 400f, 600f to 400f)
        touch(Kind.MOVE, 60, 600f to 700f)
        touch(Kind.UP, 400, 600f to 700f)
        assertThat(pointers(PointerAction.Down)).isEmpty()
        assertThat(pointers(PointerAction.Drag)).isEmpty()
    }

    @Test
    fun cancelReleasesAPress() {
        make()
        touch(Kind.DOWN, 0, 100f to 100f)
        touch(Kind.MOVE, 20, 300f to 100f)
        touch(Kind.CANCEL, 30, 300f to 100f)
        assertThat(actions()).containsExactly("Move", "Down", "Drag", "Up").inOrder()
    }

    // MARK: direct mode, other mappings

    @Test
    fun longPressAsRightClickNeverPresses() {
        make(map = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.LONG_PRESS, GestureAction.RIGHT_CLICK))
        touch(Kind.DOWN, 0, 100f to 100f)
        at(499)
        assertThat(pointers(PointerAction.Down)).isEmpty()
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).isEmpty()
        at(500)
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).hasSize(1)
        touch(Kind.MOVE, 600, 400f to 400f)
        touch(Kind.UP, 700, 400f to 400f)
        assertThat(actions()).containsExactly("Move", "RightClick").inOrder()
    }

    @Test
    fun tapAsRightClickSendsOnlyTheRightClick() {
        make(map = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.TAP, GestureAction.RIGHT_CLICK))
        tap(0, 100f, 100f, 150)
        assertThat(pointers(PointerAction.Down)).isEmpty()
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).hasSize(1)
    }

    @Test
    fun twoFingerTapCanBeAClick() {
        make(map = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.TWO_FINGER_TAP, GestureAction.Click))
        touch(Kind.DOWN, 0, 400f to 400f)
        touch(Kind.POINTER_DOWN, 20, 400f to 400f, 600f to 400f)
        touch(Kind.UP, 90, 400f to 400f)
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).isEmpty()
        assertThat(actions()).containsExactly("Move", "Down", "Up").inOrder()
    }

    // MARK: scroll

    @Test
    fun fingerScrollFollowsItsOwnSpeedAndDirection() {
        make(scroll = ScrollSetting(SpeedStep(7), reverse = true))
        touch(Kind.DOWN, 0, 400f to 400f)
        touch(Kind.POINTER_DOWN, 20, 400f to 400f, 600f to 400f)
        touch(Kind.MOVE, 40, 400f to 500f, 600f to 500f)
        touch(Kind.MOVE, 60, 400f to 560f, 600f to 560f)
        assertThat(sent.filterIsInstance<InputEvent.Scroll>().map { it.dy }).containsExactly(-200f, -120f).inOrder()
    }

    // MARK: scroll strip and inertia

    /** The trackpad mode with a band down the frame's right edge, 44 px wide, and one 200 px long along its bottom. */
    private fun makeStrip(mode: TouchMode = TouchMode.TRACKPAD, scroll: ScrollSetting = ScrollSetting(), inertia: Boolean = false) {
        make(mode, scroll = scroll)
        r.strip = StripArea(956f, 0f, 1000f, 2000f)
        r.stripX = StripArea(400f, 1956f, 600f, 2000f)
        r.inertia = inertia
    }

    private fun scrolls() = sent.filterIsInstance<InputEvent.Scroll>()

    /** A quick flick up the strip, lifted while still moving. */
    private fun flickUpTheStrip() {
        touch(Kind.DOWN, 0, 978f to 1500f)
        touch(Kind.MOVE, 16, 978f to 1450f)
        touch(Kind.MOVE, 32, 978f to 1400f)
        touch(Kind.MOVE, 48, 978f to 1350f)
        touch(Kind.UP, 50, 978f to 1350f)
    }

    @Test
    fun aKnobHeldStillMovesInsteadOfScrolling() {
        makeStrip(inertia = true)
        touch(Kind.DOWN, 0, 978f to 1000f)
        at(600)
        assertThat(events).contains(GestureEvent.StripHeld)
        touch(Kind.MOVE, 620, 978f to 1100f)
        touch(Kind.MOVE, 640, 978f to 1200f)
        touch(Kind.UP, 660, 978f to 1200f)
        at(3000)
        assertThat(scrolls()).isEmpty()
        assertThat(events).contains(GestureEvent.StripRelocated(978f, 1200f))
        assertThat(events.filterIsInstance<GestureEvent.FlingStarted>()).isEmpty()
    }

    @Test
    fun aThumbBackOnTheKnobWhileAnotherFingerRestsDoesNotScroll() {
        makeStrip()
        touch(Kind.DOWN, 0, 978f to 1000f)
        touch(Kind.MOVE, 20, 978f to 1040f)
        take(r.onTouch(Sample(Kind.POINTER_DOWN, 40, listOf(Finger(0, 978f, 1040f), Finger(1, 500f, 500f)), actionId = 1)))
        take(r.onTouch(Sample(Kind.POINTER_UP, 60, listOf(Finger(0, 978f, 1040f), Finger(1, 500f, 500f)), actionId = 0)))
        // Android hands the free id 0 to the thumb landing again, far from where it left.
        take(r.onTouch(Sample(Kind.POINTER_DOWN, 300, listOf(Finger(0, 978f, 1600f), Finger(1, 500f, 500f)), actionId = 0)))
        take(r.onTouch(Sample(Kind.MOVE, 320, listOf(Finger(0, 978f, 1620f), Finger(1, 500f, 500f)))))
        at(1000)
        assertThat(scrolls().map { it.dy }).containsExactly(100f)
        assertThat(events).doesNotContain(GestureEvent.StripHeld)
    }

    @Test
    fun aKnobLiftedWhileAnotherFingerRestsIsNeverHeld() {
        makeStrip()
        touch(Kind.DOWN, 0, 978f to 1000f)
        take(r.onTouch(Sample(Kind.POINTER_DOWN, 40, listOf(Finger(0, 978f, 1000f), Finger(1, 500f, 500f)), actionId = 1)))
        take(r.onTouch(Sample(Kind.POINTER_UP, 100, listOf(Finger(0, 978f, 1000f), Finger(1, 500f, 500f)), actionId = 0)))
        at(1000)
        assertThat(events).doesNotContain(GestureEvent.StripHeld)
    }

    @Test
    fun aKnobDraggedAtOnceScrollsAndIsNeverHeld() {
        makeStrip()
        touch(Kind.DOWN, 0, 978f to 1000f)
        touch(Kind.MOVE, 20, 978f to 1040f)
        at(800)
        touch(Kind.UP, 800, 978f to 1040f)
        assertThat(scrolls().single().dy).isEqualTo(100f)
        assertThat(events).doesNotContain(GestureEvent.StripHeld)
    }

    @Test
    fun aDragAlongTheStripScrollsAndLeavesTheCursorAlone() {
        makeStrip()
        touch(Kind.DOWN, 0, 978f to 1000f)
        touch(Kind.MOVE, 20, 978f to 1020f)
        touch(Kind.MOVE, 40, 978f to 1060f)
        touch(Kind.UP, 60, 978f to 1060f)
        assertThat(actions()).containsExactly("Scroll", "Scroll").inOrder()
        assertThat(scrolls().map { it.dx to it.dy }).containsExactly(0f to 50f, 0f to 100f).inOrder()
        assertThat(events.filterIsInstance<GestureEvent.StripTouched>()).hasSize(1)
        assertThat(events.last()).isEqualTo(GestureEvent.StripReleased)
    }

    @Test
    fun aDragAlongTheBottomBandScrollsSideways() {
        makeStrip()
        touch(Kind.DOWN, 0, 500f to 1978f)
        touch(Kind.MOVE, 20, 520f to 1980f)
        touch(Kind.MOVE, 40, 560f to 1976f)
        touch(Kind.UP, 60, 560f to 1976f)
        assertThat(scrolls().map { it.dx to it.dy }).containsExactly(50f to 0f, 100f to 0f).inOrder()
        assertThat(events.filterIsInstance<GestureEvent.StripTouched>()).containsExactly(GestureEvent.StripTouched(500f, 1978f, horizontal = true))
        assertThat(pointers(PointerAction.Move)).isEmpty()
    }

    @Test
    fun theBottomBandHeldStillStaysPutAndScrollsOnceItMoves() {
        makeStrip()
        touch(Kind.DOWN, 0, 500f to 1978f)
        at(800)
        touch(Kind.MOVE, 820, 540f to 1978f)
        touch(Kind.UP, 840, 540f to 1978f)
        assertThat(events).doesNotContain(GestureEvent.StripHeld)
        assertThat(events.filterIsInstance<GestureEvent.StripRelocated>()).isEmpty()
        assertThat(scrolls().single().dx).isEqualTo(100f)
    }

    @Test
    fun theBottomBandFollowsTheFingerScrollSpeedAndDirection() {
        makeStrip(scroll = ScrollSetting(SpeedStep(7), reverse = true))
        touch(Kind.DOWN, 0, 500f to 1978f)
        touch(Kind.MOVE, 20, 520f to 1978f)
        assertThat(scrolls().single().dx).isEqualTo(-100f)
    }

    @Test
    fun aTouchBesideTheStripStillMovesTheCursor() {
        makeStrip()
        touch(Kind.DOWN, 0, 900f to 1000f)
        touch(Kind.MOVE, 20, 900f to 1040f)
        touch(Kind.UP, 40, 900f to 1040f)
        assertThat(scrolls()).isEmpty()
        assertThat(pointers(PointerAction.Move)).isNotEmpty()
        assertThat(events.filterIsInstance<GestureEvent.StripTouched>()).isEmpty()
    }

    @Test
    fun theDirectModeHasNoStrip() {
        makeStrip(TouchMode.DIRECT)
        touch(Kind.DOWN, 0, 978f to 1000f)
        touch(Kind.MOVE, 20, 978f to 1040f)
        touch(Kind.UP, 40, 978f to 1040f)
        assertThat(scrolls()).isEmpty()
        assertThat(pointers(PointerAction.Drag)).isNotEmpty()
    }

    @Test
    fun theStripFollowsTheFingerScrollSpeedAndDirection() {
        makeStrip(scroll = ScrollSetting(SpeedStep(7), reverse = true))
        touch(Kind.DOWN, 0, 978f to 1000f)
        touch(Kind.MOVE, 20, 978f to 1020f)
        assertThat(scrolls().single().dy).isEqualTo(-100f)
    }

    @Test
    fun theStripTicksOncePerDetentOfTravel() {
        makeStrip()
        touch(Kind.DOWN, 0, 978f to 1000f)
        for (i in 1..10) touch(Kind.MOVE, i * 10L, 978f to 1000f - i * 10f)
        assertThat(events.count { it == GestureEvent.StripTick }).isEqualTo(3)
    }

    @Test
    fun aSecondFingerWhileOnTheStripChangesNothing() {
        makeStrip()
        touch(Kind.DOWN, 0, 978f to 1000f)
        touch(Kind.POINTER_DOWN, 20, 978f to 1000f, 500f to 500f)
        touch(Kind.MOVE, 40, 978f to 1040f, 520f to 560f)
        touch(Kind.POINTER_UP, 60, 978f to 1040f, 520f to 560f)
        touch(Kind.UP, 80, 978f to 1040f)
        assertThat(actions()).containsExactly("Scroll")
        assertThat(scrolls().single().dy).isEqualTo(100f)
    }

    @Test
    fun aFlickOnTheStripGlidesOnSlowingDownAndStops() {
        makeStrip(inertia = true)
        flickUpTheStrip()
        val atLift = scrolls().size
        at(5000)
        val glide = scrolls().drop(atLift).map { it.dy }
        assertThat(glide.size).isAtLeast(5)
        assertThat(glide.all { it < 0f }).isTrue()
        assertThat(glide.last()).isGreaterThan(glide.first())
        assertThat(r.nextDeadline()).isNull()
        assertThat(events.filterIsInstance<GestureEvent.FlingStarted>()).hasSize(1)
        assertThat(events.last()).isEqualTo(GestureEvent.FlingEnded)
    }

    @Test
    fun aTouchStopsTheGlide() {
        makeStrip(inertia = true)
        flickUpTheStrip()
        at(100)
        touch(Kind.DOWN, 100, 500f to 500f)
        val atTouch = scrolls().size
        at(3000)
        assertThat(scrolls().size).isEqualTo(atTouch)
        assertThat(events).contains(GestureEvent.FlingEnded)
    }

    @Test
    fun withoutInertiaTheStripStopsAtTheLift() {
        makeStrip(inertia = false)
        flickUpTheStrip()
        val atLift = scrolls().size
        at(3000)
        assertThat(scrolls().size).isEqualTo(atLift)
        assertThat(events.filterIsInstance<GestureEvent.FlingStarted>()).isEmpty()
    }

    @Test
    fun aFingerThatStoppedBeforeTheLiftDoesNotGlide() {
        makeStrip(inertia = true)
        touch(Kind.DOWN, 0, 978f to 1500f)
        touch(Kind.MOVE, 16, 978f to 1400f)
        touch(Kind.MOVE, 300, 978f to 1400f)
        touch(Kind.UP, 320, 978f to 1400f)
        val atLift = scrolls().size
        at(3000)
        assertThat(scrolls().size).isEqualTo(atLift)
    }

    @Test
    fun aTwoFingerScrollGlidesOnToo() {
        make()
        r.inertia = true
        touch(Kind.DOWN, 0, 400f to 1500f)
        touch(Kind.POINTER_DOWN, 10, 400f to 1500f, 600f to 1500f)
        touch(Kind.MOVE, 26, 400f to 1440f, 600f to 1440f)
        touch(Kind.MOVE, 42, 400f to 1380f, 600f to 1380f)
        touch(Kind.MOVE, 58, 400f to 1320f, 600f to 1320f)
        touch(Kind.POINTER_UP, 60, 400f to 1320f, 600f to 1320f)
        touch(Kind.UP, 62, 400f to 1320f)
        val atLift = scrolls().size
        at(3000)
        assertThat(scrolls().size).isGreaterThan(atLift)
        assertThat(scrolls().drop(atLift).all { it.dy < 0f }).isTrue()
    }

    @Test
    fun twoFingersLiftedOneAtATimeStillGlide() {
        make()
        r.inertia = true
        touch(Kind.DOWN, 0, 400f to 1500f)
        touch(Kind.POINTER_DOWN, 10, 400f to 1500f, 600f to 1500f)
        touch(Kind.MOVE, 26, 400f to 1440f, 600f to 1440f)
        touch(Kind.MOVE, 42, 400f to 1380f, 600f to 1380f)
        touch(Kind.MOVE, 58, 400f to 1320f, 600f to 1320f)
        touch(Kind.POINTER_UP, 60, 400f to 1320f, 600f to 1320f)
        touch(Kind.UP, 220, 400f to 1320f)
        assertThat(events.filterIsInstance<GestureEvent.FlingStarted>()).hasSize(1)
    }

    @Test
    fun aTwoFingerTapDoesNotGlide() {
        make()
        r.inertia = true
        touch(Kind.DOWN, 0, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 10, 400f to 1000f, 600f to 1000f)
        touch(Kind.POINTER_UP, 60, 400f to 1000f, 600f to 1000f)
        touch(Kind.UP, 70, 400f to 1000f)
        at(3000)
        assertThat(events.filterIsInstance<GestureEvent.FlingStarted>()).isEmpty()
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).hasSize(1)
    }

    @Test
    fun aPinchDoesNotGlide() {
        make()
        r.inertia = true
        touch(Kind.DOWN, 0, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 10, 400f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 26, 350f to 1000f, 650f to 1000f)
        touch(Kind.MOVE, 42, 300f to 1000f, 700f to 1000f)
        touch(Kind.POINTER_UP, 44, 300f to 1000f, 700f to 1000f)
        touch(Kind.UP, 46, 300f to 1000f)
        at(3000)
        assertThat(scrolls()).isEmpty()
        assertThat(events.filterIsInstance<GestureEvent.FlingStarted>()).isEmpty()
    }

    @Test
    fun changingTheTouchModeStopsTheGlide() {
        makeStrip(inertia = true)
        flickUpTheStrip()
        at(80)
        take(r.release())
        val atRelease = scrolls().size
        assertThat(r.nextDeadline()).isNull()
        at(3000)
        assertThat(scrolls().size).isEqualTo(atRelease)
        assertThat(events.last()).isEqualTo(GestureEvent.FlingEnded)
    }

    // MARK: trackpad

    @Test
    fun theTrackpadCursorStartsAtTheCentreAndTouchesDoNotMoveIt() {
        make(TouchMode.TRACKPAD)
        sent += r.onStreamStarted().inputs
        touch(Kind.DOWN, 0, 900f to 1900f)
        assertThat(actions()).containsExactly("Move")
        assertThat(sent.single()).isEqualTo(InputEvent.Pointer(PointerAction.Move, 0.5f, 0.5f))
    }

    @Test
    fun aTrackpadSwipeMovesTheCursorWithoutPressing() {
        make(TouchMode.TRACKPAD)
        sent += r.onStreamStarted().inputs
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 100, 600f to 1000f)
        touch(Kind.MOVE, 200, 700f to 1000f)
        touch(Kind.UP, 300, 700f to 1000f)
        val moves = pointers(PointerAction.Move)
        assertThat(moves.size).isEqualTo(3)
        assertThat(moves[1].x).isGreaterThan(0.5f)
        assertThat(moves[2].x).isGreaterThan(moves[1].x)
        assertThat(moves.all { it.y == 0.5f }).isTrue()
        assertThat(pointers(PointerAction.Down)).isEmpty()
    }

    @Test
    fun aTrackpadTapClicksAtTheCursor() {
        make(TouchMode.TRACKPAD)
        sent += r.onStreamStarted().inputs
        r.setCursor(0.2f, 0.3f)
        tap(0, 900f, 1900f, 60)
        assertThat(pointers(PointerAction.Down).single()).isEqualTo(InputEvent.Pointer(PointerAction.Down, 0.2f, 0.3f))
        assertThat(pointers(PointerAction.Up).single()).isEqualTo(InputEvent.Pointer(PointerAction.Up, 0.2f, 0.3f))
    }

    @Test
    fun aDirectTapThenHoldDragsWithAPlainDownAtTheSecondTouch() {
        make(map = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.DOUBLE_TAP_HOLD, GestureAction.Drag))
        tap(0, 100f, 100f, 50)
        touch(Kind.DOWN, 150, 100f to 100f)
        touch(Kind.MOVE, 170, 300f to 100f)
        touch(Kind.UP, 200, 300f to 100f)
        assertThat(actions()).containsExactly("Move", "Down", "Up", "Move", "Down", "Drag", "Up").inOrder()
        assertThat(pointers(PointerAction.Down).last()).isEqualTo(InputEvent.Pointer(PointerAction.Down, 0.1f, 0.05f))
    }

    @Test
    fun aTrackpadTapThenHoldDragsWithAPlainDown() {
        make(TouchMode.TRACKPAD, map = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.DOUBLE_TAP_HOLD, GestureAction.Drag))
        tap(0, 500f, 1000f, 60)
        sent.clear()
        touch(Kind.DOWN, 150, 500f to 1000f)
        touch(Kind.MOVE, 350, 600f to 1000f)
        touch(Kind.MOVE, 400, 700f to 1000f)
        touch(Kind.UP, 450, 700f to 1000f)
        assertThat(pointers(PointerAction.DownContinued)).isEmpty()
        assertThat(actions().filter { it != "Move" }).containsExactly("Down", "Drag", "Drag").inOrder()
        at(949)
        assertThat(pointers(PointerAction.Up)).isEmpty()
        at(950)
        assertThat(actions().filter { it != "Move" }).containsExactly("Down", "Drag", "Drag", "Up").inOrder()
        assertThat(events).containsExactly(GestureEvent.DoubleTapHeld(90, 0f, 200, moved = true), GestureEvent.DragStarted(DragStart.DOUBLE_TAP_HOLD), GestureEvent.DragEnded(DragEnd.TIMEOUT)).inOrder()
    }

    @Test
    fun theTrackpadCursorStaysOnScreenAndSpeedsScale() {
        make(TouchMode.TRACKPAD, map = noLongPress, speed = SpeedStep(7))
        sent += r.onStreamStarted().inputs
        touch(Kind.DOWN, 0, 0f to 1000f)
        touch(Kind.MOVE, 1000, 990f to 1000f)
        assertThat(pointers(PointerAction.Move).last().x).isEqualTo(1f)

        make(TouchMode.TRACKPAD, map = noLongPress, speed = SpeedStep(3))
        sent += r.onStreamStarted().inputs
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 1000, 600f to 1000f)
        val slow = pointers(PointerAction.Move).last().x - 0.5f
        make(TouchMode.TRACKPAD, map = noLongPress, speed = SpeedStep(7))
        sent += r.onStreamStarted().inputs
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 1000, 600f to 1000f)
        val fast = pointers(PointerAction.Move).last().x - 0.5f
        assertThat(fast).isWithin(1e-4f).of(slow * 1.8f / 0.8f)

        make(TouchMode.TRACKPAD, map = noLongPress)
        sent += r.onStreamStarted().inputs
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 10, 600f to 1000f)
        val quick = pointers(PointerAction.Move).last().x - 0.5f
        make(TouchMode.TRACKPAD, map = noLongPress)
        sent += r.onStreamStarted().inputs
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 1000, 600f to 1000f)
        val calm = pointers(PointerAction.Move).last().x - 0.5f
        assertThat(quick).isGreaterThan(calm)
    }

    @Test
    fun aTrackpadLongPressArmsQuietlyAndTheFirstMovePressesAndDrags() {
        make(TouchMode.TRACKPAD, map = longPressDrag)
        touch(Kind.DOWN, 0, 500f to 1000f)
        at(499)
        assertThat(events).isEmpty()
        at(500)
        assertThat(sent).isEmpty()
        assertThat(events).containsExactly(stillHold, GestureEvent.Armed).inOrder()
        touch(Kind.MOVE, 550, 505f to 1000f)
        assertThat(sent).isEmpty()
        val o = touchOut(Kind.MOVE, 600, 600f to 1000f)
        assertThat(o.inputs.map { (it as InputEvent.Pointer).action }).containsExactly(PointerAction.Down, PointerAction.Drag).inOrder()
        assertThat(o.inputs[0]).isEqualTo(InputEvent.Pointer(PointerAction.Down, 0.5f, 0.5f))
        assertThat((o.inputs[1] as InputEvent.Pointer).x).isGreaterThan(0.5f)
        assertThat(o.events).containsExactly(GestureEvent.DragStarted(DragStart.LONG_PRESS))
    }

    @Test
    fun anArmedTouchLiftedStillSendsNothing() {
        make(TouchMode.TRACKPAD, map = longPressDrag)
        tap(0, 500f, 1000f, 60)
        sent.clear()
        touch(Kind.DOWN, 100, 500f to 1000f)
        at(600)
        touch(Kind.UP, 700, 500f to 1000f)
        assertThat(sent).isEmpty()
        tap(800, 500f, 1000f, 60)
        assertThat(actions()).containsExactly("Down", "Up").inOrder()
    }

    @Test
    fun anArmedTouchThatChangesFingersStillOnlyWaitsForAMove() {
        make(TouchMode.TRACKPAD, map = longPressDrag, shared = mapOf(Gesture.THREE_FINGER_TAP to item(RadialItem.MUTE)))
        touch(Kind.DOWN, 0, 500f to 1000f)
        at(500)
        touch(Kind.POINTER_DOWN, 550, 500f to 1000f, 700f to 1000f)
        touch(Kind.POINTER_DOWN, 560, 500f to 1000f, 700f to 1000f, 900f to 1000f)
        touch(Kind.POINTER_UP, 600, 500f to 1000f, 700f to 1000f, 900f to 1000f)
        touch(Kind.MOVE, 650, 700f to 1000f, 900f to 1000f)
        touch(Kind.POINTER_UP, 700, 700f to 1000f, 900f to 1000f)
        touch(Kind.MOVE, 750, 900f to 1000f)
        touch(Kind.UP, 800, 900f to 1000f)
        assertThat(sent).isEmpty()
        assertThat(commands).isEmpty()
    }

    @Test
    fun aTrackpadTwoFingerTapCountsOnlyWithoutCursorTravel() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 20, 600f to 1000f)
        touch(Kind.POINTER_DOWN, 40, 600f to 1000f, 700f to 1000f)
        touch(Kind.UP, 80, 600f to 1000f)
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).isEmpty()
    }

    // MARK: outside the video

    @Test
    fun aDirectTapOutsideTheVideoSendsNothing() {
        make()
        tap(0, -50f, 500f, 60)
        tap(500, 500f, 2100f, 60)
        assertThat(sent).isEmpty()
    }

    @Test
    fun aDirectLongPressOutsideTheVideoSendsNothing() {
        for (map in listOf(TouchMap.defaults(TouchMode.DIRECT), TouchMap.defaults(TouchMode.DIRECT).with(Gesture.LONG_PRESS, GestureAction.RIGHT_CLICK))) {
            make(map = map)
            touch(Kind.DOWN, 0, -50f to 500f)
            at(1000)
            touch(Kind.UP, 1100, -50f to 500f)
            assertThat(sent).isEmpty()
            assertThat(r.nextDeadline()).isNull()
        }
    }

    @Test
    fun aDirectMoveStartingOutsideTheVideoSendsNothing() {
        make()
        touch(Kind.DOWN, 0, -50f to 500f)
        touch(Kind.MOVE, 16, 300f to 500f)
        touch(Kind.MOVE, 32, 500f to 500f)
        touch(Kind.UP, 48, 500f to 500f)
        assertThat(sent).isEmpty()
    }

    @Test
    fun aDirectTouchOutsideTheVideoEndsADoubleClick() {
        make()
        tap(0, 10f, 500f, 60)
        tap(150, -50f, 500f, 60)
        tap(300, 10f, 500f, 60)
        assertThat(pointers(PointerAction.DownContinued)).isEmpty()
        assertThat(pointers(PointerAction.Down)).hasSize(2)
    }

    @Test
    fun aDirectDragFromTheVideoOutwardStopsAtTheEdge() {
        make()
        touch(Kind.DOWN, 0, 100f to 100f)
        touch(Kind.MOVE, 16, 300f to 100f)
        touch(Kind.MOVE, 32, -200f to 100f)
        touch(Kind.UP, 48, -200f to 100f)
        assertThat(pointers(PointerAction.Drag).last().x).isEqualTo(0f)
        assertThat(pointers(PointerAction.Up).single().x).isEqualTo(0f)
    }

    @Test
    fun twoFingersStartingOutsideTheVideoScroll() {
        make()
        touch(Kind.DOWN, 0, -50f to 800f)
        touch(Kind.POINTER_DOWN, 20, -50f to 800f, 150f to 800f)
        touch(Kind.MOVE, 40, -50f to 900f, 150f to 900f)
        touch(Kind.MOVE, 60, -50f to 960f, 150f to 960f)
        assertThat(sent.filterIsInstance<InputEvent.Scroll>()).isNotEmpty()
        assertThat(sent.filterIsInstance<InputEvent.Pointer>()).isEmpty()
    }

    @Test
    fun aTwoFingerTapStartingOutsideTheVideoRightClicksAtTheEdge() {
        make()
        touch(Kind.DOWN, 0, -50f to 400f)
        touch(Kind.POINTER_DOWN, 20, -50f to 400f, 150f to 400f)
        touch(Kind.POINTER_UP, 80, -50f to 400f, 150f to 400f)
        touch(Kind.UP, 90, -50f to 400f)
        assertThat(sent).containsExactly(InputEvent.RightClick(0f, 0.2f))
    }

    @Test
    fun fourFingersStartingOutsideTheVideoSwipe() {
        make()
        val xs = listOf(-100f, 0f, 100f, 200f)
        fun at(dy: Float, n: Int) = xs.take(n).map { it to 1000f + dy }.toTypedArray()
        touch(Kind.DOWN, 0, *at(0f, 1))
        touch(Kind.POINTER_DOWN, 20, *at(0f, 2))
        touch(Kind.POINTER_DOWN, 40, *at(0f, 3))
        touch(Kind.POINTER_DOWN, 60, *at(0f, 4))
        touch(Kind.MOVE, 80, *at(-30f, 4))
        touch(Kind.MOVE, 100, *at(-60f, 4))
        assertThat(commands).containsExactly(RadialEntry.Builtin(RadialItem.MISSION_CONTROL))
    }

    @Test
    fun aDirectFingerRestingOutsideTheVideoMakesALaterTouchTwoFingers() {
        make()
        touch(Kind.DOWN, 0, -50f to 800f)
        touch(Kind.POINTER_DOWN, 400, -50f to 800f, 500f to 800f)
        touch(Kind.MOVE, 420, -50f to 900f, 500f to 900f)
        touch(Kind.MOVE, 440, -50f to 960f, 500f to 960f)
        assertThat(sent.filterIsInstance<InputEvent.Scroll>()).isNotEmpty()
        assertThat(sent.filterIsInstance<InputEvent.Pointer>()).isEmpty()
    }

    @Test
    fun aTrackpadSwipeOrTapOutsideTheVideoActsAsInside() {
        make(TouchMode.TRACKPAD)
        sent += r.onStreamStarted().inputs
        touch(Kind.DOWN, 0, 500f to -100f)
        touch(Kind.MOVE, 100, 600f to -100f)
        touch(Kind.MOVE, 200, 700f to -100f)
        touch(Kind.UP, 300, 700f to -100f)
        val moves = pointers(PointerAction.Move)
        assertThat(moves.size).isEqualTo(3)
        assertThat(moves[2].x).isGreaterThan(moves[1].x)
        assertThat(moves[1].x).isGreaterThan(0.5f)
        val cursor = moves.last()
        tap(1000, -50f, 2100f, 60)
        assertThat(pointers(PointerAction.Down).single()).isEqualTo(InputEvent.Pointer(PointerAction.Down, cursor.x, cursor.y))
    }

    @Test
    fun aTrackpadFingerRestingOutsideTheVideoArmsAGrabForAnotherFinger() {
        make(TouchMode.TRACKPAD, map = longPressDrag)
        touch(Kind.DOWN, 0, 500f to -100f)
        at(500)
        assertThat(events).containsExactly(stillHold, GestureEvent.Armed).inOrder()
        touch(Kind.POINTER_DOWN, 600, 500f to -100f, 500f to 1000f)
        touch(Kind.MOVE, 620, 500f to -100f, 600f to 1000f)
        assertThat(events).containsExactly(stillHold, GestureEvent.Armed, GestureEvent.DragStarted(DragStart.LONG_PRESS)).inOrder()
        assertThat(pointers(PointerAction.Down)).hasSize(1)
    }

    // MARK: zoom

    @Test
    fun aGapChangingFirstIsAPinchThatSendsNothing() {
        make()
        touch(Kind.DOWN, 0, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 20, 400f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 40, 350f to 1000f, 650f to 1000f)
        touch(Kind.MOVE, 60, 300f to 1000f, 700f to 1000f)
        touch(Kind.UP, 100, 300f to 1000f)
        at(1000)
        assertThat(actions()).containsExactly("Move")
        val v = r.viewport
        assertThat(v.zoom).isWithin(1e-4f).of(2f)
        assertThat(v.left).isWithin(1e-4f).of(0.25f)
        assertThat(v.top).isWithin(1e-4f).of(0.25f)
        assertThat(viewports.last()).isEqualTo(v)
    }

    @Test
    fun aCentreMovingFirstIsASwipeThatDoesNotZoom() {
        make()
        touch(Kind.DOWN, 0, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 20, 400f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 40, 400f to 1100f, 600f to 1100f)
        touch(Kind.MOVE, 60, 380f to 1150f, 640f to 1150f)
        assertThat(sent.filterIsInstance<InputEvent.Scroll>()).hasSize(2)
        assertThat(r.viewport).isEqualTo(Viewport())
        assertThat(viewports).isEmpty()
    }

    @Test
    fun aFingerSettingOffBeforeTheOtherAlongTheGapStillScrolls() {
        make()
        touch(Kind.DOWN, 0, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 20, 400f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 40, 400f to 1000f, 625f to 1000f)
        touch(Kind.MOVE, 60, 430f to 1000f, 670f to 1000f)
        assertThat(sent.filterIsInstance<InputEvent.Scroll>()).hasSize(1)
        assertThat(r.viewport).isEqualTo(Viewport())
        assertThat(viewports).isEmpty()
    }

    @Test
    fun fingersSpreadingALittleAsTheyScrollDoNotZoom() {
        make()
        touch(Kind.DOWN, 0, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 20, 400f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 40, 390f to 1060f, 615f to 1060f)
        assertThat(sent.filterIsInstance<InputEvent.Scroll>()).hasSize(1)
        assertThat(r.viewport).isEqualTo(Viewport())
        assertThat(viewports).isEmpty()
    }

    @Test
    fun aGapChangeShortOfThePinchThresholdNeitherZoomsNorRightClicks() {
        make()
        touch(Kind.DOWN, 0, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 20, 400f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 40, 388f to 1000f, 612f to 1000f)
        touch(Kind.POINTER_UP, 80, 388f to 1000f, 612f to 1000f)
        touch(Kind.UP, 90, 388f to 1000f)
        at(400)
        assertThat(r.viewport).isEqualTo(Viewport())
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).isEmpty()
        assertThat(sent.filterIsInstance<InputEvent.Scroll>()).isEmpty()
    }

    @Test
    fun aPinchWithOneFingerHeldStillZooms() {
        make()
        touch(Kind.DOWN, 0, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 20, 400f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 40, 400f to 1000f, 650f to 1000f)
        assertThat(r.viewport.zoom).isWithin(1e-4f).of(1.25f)
        touch(Kind.MOVE, 60, 400f to 1000f, 700f to 1000f)
        assertThat(r.viewport.zoom).isWithin(1e-4f).of(1.5f)
        assertThat(sent.filterIsInstance<InputEvent.Scroll>()).isEmpty()
    }

    @Test
    fun theTwoFingerJudgementIsReportedOncePerGesture() {
        make()
        touch(Kind.DOWN, 0, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 20, 400f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 40, 400f to 1100f, 600f to 1100f)
        touch(Kind.MOVE, 60, 400f to 1200f, 600f to 1200f)
        touch(Kind.UP, 100, 400f to 1200f)
        at(1000)
        touch(Kind.DOWN, 1100, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 1120, 400f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 1140, 350f to 1000f, 650f to 1000f)
        touch(Kind.MOVE, 1160, 300f to 1000f, 700f to 1000f)
        val judged = events.filterIsInstance<GestureEvent.TwoFingerJudged>()
        assertThat(judged.map { it.pinch }).containsExactly(false, true).inOrder()
        assertThat(judged[0].share).isWithin(1e-4f).of(0f)
        assertThat(judged[0].travelDp).isWithin(1e-4f).of(200f)
        assertThat(judged[1].share).isWithin(1e-4f).of(1f)
        assertThat(judged[1].gapDp).isWithin(1e-4f).of(100f)
    }

    @Test
    fun theVideoPointUnderTheCentreStaysAndMovingTheCentreDoesNotPan() {
        make()
        touch(Kind.DOWN, 0, 100f to 400f)
        touch(Kind.POINTER_DOWN, 20, 100f to 400f, 300f to 400f)
        touch(Kind.MOVE, 40, 50f to 400f, 350f to 400f)
        val (px, py) = r.toVideo(200f, 400f)
        assertThat(px).isWithin(1e-4f).of(0.2f)
        assertThat(py).isWithin(1e-4f).of(0.2f)
        assertThat(r.viewport.zoom).isWithin(1e-4f).of(1.5f)
        val before = r.viewport
        touch(Kind.MOVE, 60, 150f to 500f, 450f to 500f)
        assertThat(r.viewport.zoom).isEqualTo(before.zoom)
        assertThat(r.viewport.left).isWithin(1e-5f).of(before.left)
        assertThat(r.viewport.top).isWithin(1e-5f).of(before.top)
    }

    @Test
    fun aTapAtTheFramesCentreLandsOnTheVideoUnderIt() {
        make()
        r.setViewport(Viewport(2f, 0.25f, 0.25f))
        tap(0, 500f, 1000f, 60)
        assertThat(pointers(PointerAction.Down).single()).isEqualTo(InputEvent.Pointer(PointerAction.Down, 0.5f, 0.5f))
    }

    @Test
    fun zoomedInATwoFingerScrollMovesHalfAsFar() {
        fun scrolled(zoom: Float): Float {
            make()
            r.setViewport(Viewport(zoom, 0f, 0f))
            touch(Kind.DOWN, 0, 400f to 800f)
            touch(Kind.POINTER_DOWN, 20, 400f to 800f, 600f to 800f)
            touch(Kind.MOVE, 40, 400f to 900f, 600f to 900f)
            touch(Kind.MOVE, 60, 400f to 1000f, 600f to 1000f)
            return sent.filterIsInstance<InputEvent.Scroll>().sumOf { it.dy.toDouble() }.toFloat()
        }
        val one = scrolled(1f)
        assertThat(one).isNonZero()
        assertThat(scrolled(2f)).isWithin(1e-3f).of(one / 2)
    }

    @Test
    fun zoomedInTheTrackpadCursorMovesHalfAsFar() {
        make(TouchMode.TRACKPAD, map = noLongPress)
        sent += r.onStreamStarted().inputs
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 1000, 600f to 1000f)
        val one = pointers(PointerAction.Move).last().x - 0.5f
        make(TouchMode.TRACKPAD, map = noLongPress)
        sent += r.onStreamStarted().inputs
        r.setViewport(Viewport(2f, 0.25f, 0.25f))
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 1000, 600f to 1000f)
        val two = pointers(PointerAction.Move).last().x - 0.5f
        assertThat(two).isWithin(1e-4f).of(one / 2)
    }

    @Test
    fun theTrackpadCursorInTheEdgeBandMovesTheViewport() {
        make(TouchMode.TRACKPAD, map = noLongPress)
        sent += r.onStreamStarted().inputs
        r.setViewport(Viewport(2f, 0.25f, 0.25f))
        touch(Kind.DOWN, 0, 300f to 1000f)
        touch(Kind.MOVE, 1000, 700f to 1000f)
        assertThat(viewports).isNotEmpty()
        val cursor = pointers(PointerAction.Move).last()
        assertThat(cursor.x - r.viewport.left).isWithin(1e-4f).of(0.5f - 0.05f)
        assertThat(r.viewport.top).isEqualTo(0.25f)
    }

    @Test
    fun afterAPinchACursorOffTheViewportComesJustInsideTheBand() {
        make(TouchMode.TRACKPAD)
        sent += r.onStreamStarted().inputs
        r.setCursor(0.05f, 0.5f)
        sent.clear()
        touch(Kind.DOWN, 0, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 20, 400f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 40, 300f to 1000f, 700f to 1000f)
        touch(Kind.UP, 100, 300f to 1000f)
        assertThat(actions()).containsExactly("Move")
        val m = pointers(PointerAction.Move).single()
        assertThat(m.x).isWithin(1e-4f).of(0.3f)
        assertThat(m.y).isEqualTo(0.5f)
    }

    @Test
    fun aDirectDragInTheEdgeBandPansEveryStepAfterThePress() {
        make()
        r.setViewport(Viewport(2f, 0.25f, 0.25f))
        touch(Kind.DOWN, 0, 950f to 1000f)
        at(100)
        assertThat(r.nextDeadline()).isEqualTo(GestureRecognizer.PRESS_DELAY)
        assertThat(r.viewport.left).isEqualTo(0.25f)
        at(GestureRecognizer.PRESS_DELAY)
        assertThat(r.nextDeadline()).isEqualTo(GestureRecognizer.PRESS_DELAY + 16)
        at(GestureRecognizer.PRESS_DELAY + 16)
        // Half deep in the band: a quarter of the viewport's width per second, for 16 ms.
        assertThat(r.viewport.left).isWithin(1e-5f).of(0.25f + 0.5f * 0.5f * 0.5f * 0.016f)
        val drag = pointers(PointerAction.Drag).single()
        assertThat(drag.x).isWithin(1e-5f).of(r.viewport.left + 0.95f / 2)
        assertThat(r.nextDeadline()).isEqualTo(GestureRecognizer.PRESS_DELAY + 32)
    }

    @Test
    fun atTheVideosEdgeThePanStopsResendingTheDrag() {
        make()
        r.setViewport(Viewport(2f, 0.5f, 0.25f))
        touch(Kind.DOWN, 0, 950f to 1000f)
        at(400)
        assertThat(pointers(PointerAction.Down)).hasSize(1)
        assertThat(pointers(PointerAction.Drag)).isEmpty()
        assertThat(r.nextDeadline()).isNotNull()
    }

    @Test
    fun atZoomOneADragInTheEdgeBandWaitsForNothing() {
        make()
        touch(Kind.DOWN, 0, 950f to 1000f)
        at(400)
        assertThat(r.nextDeadline()).isNull()
    }

    // MARK: a screen larger than the frame

    /** A 16:10 frame on a square screen: from the frame's top, the screen runs from -187.5 to 812.5. */
    private val barred = Placement(1000f, 625f, 1000f, 1000f, 0f, 187.5f)

    private fun makeBarred(mode: TouchMode = TouchMode.DIRECT, map: TouchMap = TouchMap.defaults(mode)) {
        make(mode, map)
        r.setViewSize(barred)
    }

    @Test
    fun atOneTimesATouchOnABlackBarStillSendsNothing() {
        makeBarred()
        tap(0, 500f, -100f, 60)
        at(1000)
        assertThat(sent).isEmpty()
    }

    @Test
    fun zoomedOverABlackBarATapClicksTheVideoNowThere() {
        makeBarred()
        r.setViewport(Viewport(2f))
        tap(0, 500f, -100f, 60)
        val down = pointers(PointerAction.Down).single()
        // The 1250 px tall video starts at the screen's top: 87.5 px down it.
        assertThat(down.y).isWithin(1e-5f).of(87.5f / 1250f)
        assertThat(down.x).isWithin(1e-5f).of(0.25f)
    }

    @Test
    fun aDirectDragPansAtTheScreensEdgeNotTheFramesEdge() {
        makeBarred()
        r.setViewport(Viewport(2f, 0.25f, 0.1f))
        touch(Kind.DOWN, 0, 500f to -180f)
        at(GestureRecognizer.PRESS_DELAY + 32)
        assertThat(r.viewport.top).isLessThan(0.1f)

        makeBarred()
        r.setViewport(Viewport(2f, 0.25f, 0.1f))
        touch(Kind.DOWN, 0, 500f to 10f)
        at(GestureRecognizer.PRESS_DELAY + 32)
        assertThat(r.viewport.top).isEqualTo(0.1f)
    }

    @Test
    fun theTrackpadCursorAtTheScreensTopEdgeMovesTheView() {
        makeBarred(TouchMode.TRACKPAD, noLongPress)
        r.setViewport(Viewport(2f, 0.25f, 0.1f))
        touch(Kind.DOWN, 0, 500f to 900f)
        for (i in 1..20) touch(Kind.MOVE, i * 16L, 500f to 900f - i * 40f)
        assertThat(r.viewport.top).isLessThan(0.1f)
    }

    @Test
    fun pinchingBelowTheFillZoomCentresTheBarredAxis() {
        makeBarred()
        r.setViewport(Viewport(2f, 0.25f, 0.1f))
        touch(Kind.DOWN, 0, 400f to 300f)
        touch(Kind.POINTER_DOWN, 20, 400f to 300f, 600f to 300f)
        touch(Kind.MOVE, 40, 430f to 300f, 570f to 300f)
        assertThat(r.viewport.zoom).isWithin(1e-4f).of(1.4f)
        assertThat(r.viewport.top).isEqualTo(0f)
    }

    @Test
    fun aLargerScreenClampsTheViewportAgain() {
        make()
        r.setViewSize(1000, 625)
        r.setViewport(Viewport(2f, 0.25f, 0.5f))
        assertThat(r.viewport.top).isEqualTo(0.5f)
        r.setViewSize(barred)
        assertThat(r.viewport.top).isWithin(1e-6f).of(0.2f)
    }

    @Test
    fun aLowerFillZoomLowersTheLimit() {
        make()
        r.setViewSize(Placement(1000f, 200f, 1000f, 1000f, 0f, 400f))
        r.setViewport(Viewport(5f))
        assertThat(r.viewport.zoom).isEqualTo(5f)
        r.setViewSize(Placement(1000f, 200f, 1000f, 320f, 0f, 60f))
        assertThat(r.viewport.zoom).isEqualTo(4f)
    }

    // MARK: fill screen

    @Test
    fun fillScreenZoomsToTheFillZoomKeepingThePointAndGoesBack() {
        makeBarred()
        val out = r.fillScreen(0.3f, 0.5f)
        assertThat(out.viewport!!.zoom).isEqualTo(1.6f)
        assertThat(out.inputs).isEmpty()
        assertThat(out.command).isNull()
        // The point at x = 300 px before is there still.
        assertThat(r.toVideo(300f, 300f).first).isWithin(1e-5f).of(0.3f)
        assertThat(r.fillScreen(0.3f, 0.5f).viewport).isEqualTo(Viewport())
    }

    @Test
    fun fillScreenJustBelowTheFillZoomGoesBackAndWellBelowFills() {
        makeBarred()
        r.setViewport(Viewport(1.6f - 0.005f))
        r.fillScreen(0.5f, 0.5f)
        assertThat(r.viewport).isEqualTo(Viewport())
        r.setViewport(Viewport(1.6f - 0.02f))
        r.fillScreen(0.5f, 0.5f)
        assertThat(r.viewport.zoom).isEqualTo(1.6f)
    }

    @Test
    fun withoutBarsFillScreenOnlyGoesBack() {
        make()
        assertThat(r.fillScreen(0.5f, 0.5f).viewport).isNull()
        assertThat(r.viewport).isEqualTo(Viewport())
        r.setViewport(Viewport(2f, 0.25f, 0.25f))
        assertThat(r.fillScreen(0.5f, 0.5f).viewport).isEqualTo(Viewport())
    }

    @Test
    fun fillScreenBringsATrackpadCursorOutsideTheBandInside() {
        makeBarred(TouchMode.TRACKPAD)
        r.setCursor(0.02f, 0.5f)
        val out = r.fillScreen(0.02f, 0.5f)
        val left = out.viewport!!.left
        val move = out.inputs.single() as InputEvent.Pointer
        assertThat(move.action).isEqualTo(PointerAction.Move)
        assertThat(move.x).isWithin(1e-5f).of(left + GestureRecognizer.EDGE_BAND / 1.6f)
    }

    @Test
    fun fillScreenLeavesACentredTrackpadCursorAlone() {
        makeBarred(TouchMode.TRACKPAD)
        r.setCursor(0.5f, 0.5f)
        assertThat(r.fillScreen(0.5f, 0.5f).inputs).isEmpty()
    }

    private val twoTapFills = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.TWO_FINGER_TAP, GestureAction.FILL_SCREEN)

    /** A two-finger tap at 300 and 500 px across, 300 px down: centre (400, 300). Returns the lift's output. */
    private fun twoTap(t: Long): GestureRecognizer.Output {
        touch(Kind.DOWN, t, 300f to 300f)
        touch(Kind.POINTER_DOWN, t + 20, 300f to 300f, 500f to 300f)
        touch(Kind.POINTER_UP, t + 80, 300f to 300f, 500f to 300f)
        return touchOut(Kind.UP, t + 90, 300f to 300f)
    }

    private fun assertOnlyTheView(o: GestureRecognizer.Output) {
        assertThat(o.inputs.filter { it is InputEvent.RightClick || it is InputEvent.Scroll }).isEmpty()
        assertThat(o.inputs.filterIsInstance<InputEvent.Pointer>().filter { it.action == PointerAction.Down || it.action == PointerAction.Up }).isEmpty()
        assertThat(o.command).isNull()
    }

    @Test
    fun aTwoFingerTapGivenFillScreenZoomsAboutTheFingersAndBack() {
        makeBarred(map = twoTapFills)
        val out = twoTap(0)
        assertOnlyTheView(out)
        assertThat(out.viewport!!.zoom).isEqualTo(1.6f)
        assertThat(r.toVideo(400f, 300f).first).isWithin(1e-5f).of(0.4f)
        assertThat(twoTap(1000).viewport).isEqualTo(Viewport())
        assertThat(commands).isEmpty()
    }

    @Test
    fun aTrackpadTwoFingerTapGivenFillScreenZoomsAboutTheCursor() {
        makeBarred(TouchMode.TRACKPAD, TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.TWO_FINGER_TAP, GestureAction.FILL_SCREEN))
        val out = twoTap(0)
        assertOnlyTheView(out)
        assertThat(out.inputs).isEmpty()
        assertThat(r.viewport.zoom).isEqualTo(1.6f)
        assertThat(r.toVideo(500f, 300f).first).isWithin(1e-5f).of(0.5f)
    }

    @Test
    fun aThreeFingerTapGivenFillScreenTogglesWithoutAskingTheMac() {
        make(shared = mapOf(Gesture.THREE_FINGER_TAP to GestureAction.FILL_SCREEN))
        r.setViewSize(barred)
        for ((t, zoom) in listOf(0L to 1.6f, 1000L to 1f)) {
            threeDown(t, y = 300f)
            touch(Kind.POINTER_UP, t + 100, 400f to 300f, 500f to 300f, 600f to 300f)
            val out = touchOut(Kind.UP, t + 110, 500f to 300f)
            assertOnlyTheView(out)
            assertThat(r.viewport.zoom).isEqualTo(zoom)
        }
        assertThat(commands).isEmpty()
    }

    @Test
    fun aFourFingerSwipeGivenFillScreenTogglesWithoutAskingTheMac() {
        make(shared = mapOf(Gesture.FOUR_FINGER_SWIPE_UP to GestureAction.FILL_SCREEN))
        r.setViewSize(barred)
        val xs = listOf(400f, 500f, 600f, 700f)
        fun at(dy: Float, n: Int) = xs.take(n).map { it to 300f + dy }.toTypedArray()
        touch(Kind.DOWN, 0, *at(0f, 1))
        touch(Kind.POINTER_DOWN, 20, *at(0f, 2))
        touch(Kind.POINTER_DOWN, 40, *at(0f, 3))
        touch(Kind.POINTER_DOWN, 60, *at(0f, 4))
        touch(Kind.MOVE, 80, *at(-30f, 4))
        val out = touchOut(Kind.MOVE, 100, *at(-60f, 4))
        assertOnlyTheView(out)
        assertThat(r.viewport.zoom).isEqualTo(1.6f)
        // About the four fingers' centre where they landed, at 550 px across.
        assertThat(r.toVideo(550f, 300f).first).isWithin(1e-5f).of(0.55f)
        assertThat(commands).isEmpty()
    }

    // MARK: three fingers

    /** Three fingers land at 0, 20 and 40 ms around ([x], [y]), 100 px apart. */
    // MARK: three and four fingers

    /** Three fingers at 400, 500 and 600 by [y], the third at [t] + 40: centre (500, [y]). */
    private fun threeDown(t: Long = 0, y: Float = 1000f) {
        touch(Kind.DOWN, t, 400f to y)
        touch(Kind.POINTER_DOWN, t + 20, 400f to y, 500f to y)
        touch(Kind.POINTER_DOWN, t + 40, 400f to y, 500f to y, 600f to y)
    }

    private fun threeMove(t: Long, dx: Float, dy: Float, y: Float = 1000f) =
        touch(Kind.MOVE, t, 400f + dx to y + dy, 500f + dx to y + dy, 600f + dx to y + dy)

    /** A fourth finger at 700 joins three fingers moved by ([dx], 0): centre (550 + [dx], 1000). */
    private fun fourthDown(t: Long, dx: Float = 0f) =
        touch(Kind.POINTER_DOWN, t, 400f + dx to 1000f, 500f + dx to 1000f, 600f + dx to 1000f, 700f + dx to 1000f)

    private fun fourMove(t: Long, dx: Float, dy: Float, x0: Float = 0f) =
        touch(Kind.MOVE, t, 400f + x0 + dx to 1000f + dy, 500f + x0 + dx to 1000f + dy, 600f + x0 + dx to 1000f + dy, 700f + x0 + dx to 1000f + dy)

    @Test
    fun aQuickTouchAfterATrackpadTapMovesTheCursorWithoutGrabbing() {
        make(TouchMode.TRACKPAD)
        tap(0, 500f, 1000f, 60)
        touch(Kind.DOWN, 150, 500f to 1000f)
        touch(Kind.MOVE, 200, 600f to 1000f)
        touch(Kind.MOVE, 250, 700f to 1000f)
        touch(Kind.UP, 300, 700f to 1000f)
        assertThat(actions().filter { it != "Move" }).containsExactly("Down", "Up").inOrder()
        assertThat(pointers(PointerAction.Move).last().x).isGreaterThan(0.5f)
    }

    @Test
    fun twoQuickTrackpadTapsStillDoubleClick() {
        make(TouchMode.TRACKPAD)
        tap(0, 500f, 1000f, 60)
        tap(150, 500f, 1000f, 60)
        assertThat(actions().filter { it != "Move" }).containsExactly("Down", "Up", "DownContinued", "Up").inOrder()
    }

    @Test
    fun threeFingersMovedAfterTheWaitPressAndDragInOneOutput() {
        make(TouchMode.TRACKPAD)
        threeDown()
        at(130)
        assertThat(sent).isEmpty()
        val o = touchOut(Kind.MOVE, 140, 430f to 1000f, 530f to 1000f, 630f to 1000f)
        assertThat(o.inputs.map { (it as InputEvent.Pointer).action }).containsExactly(PointerAction.Down, PointerAction.Drag).inOrder()
        assertThat(o.inputs[0]).isEqualTo(InputEvent.Pointer(PointerAction.Down, 0.5f, 0.5f))
        assertThat((o.inputs[1] as InputEvent.Pointer).x).isGreaterThan(0.5f)
        assertThat(o.events).containsExactly(GestureEvent.DragStarted(DragStart.THREE_FINGER))
    }

    @Test
    fun threeFingersMovedBeforeTheWaitPressAtItsEnd() {
        make(TouchMode.TRACKPAD)
        threeDown()
        threeMove(60, 30f, 0f)
        at(119)
        assertThat(sent).isEmpty()
        val o = r.onTimer(120).also(::take)
        assertThat(o.inputs.map { (it as InputEvent.Pointer).action }).containsExactly(PointerAction.Down, PointerAction.Drag).inOrder()
        assertThat((o.inputs[1] as InputEvent.Pointer).x).isGreaterThan(0.5f)
    }

    @Test
    fun aFourthFingerWithinTheWaitSwipesInsteadOfGrabbing() {
        make(TouchMode.TRACKPAD)
        threeDown()
        threeMove(60, 20f, 0f)
        fourthDown(80, 20f)
        fourMove(100, 0f, -30f, 20f)
        assertThat(commands).isEmpty()
        fourMove(110, 0f, -60f, 20f)
        touch(Kind.UP, 200, 500f to 940f)
        at(1000)
        assertThat(commands).containsExactly(RadialEntry.Builtin(RadialItem.MISSION_CONTROL))
        assertThat(pointers(PointerAction.Down)).isEmpty()
    }

    @Test
    fun fourFingerSwipesAskForTheirMacActionsInBothModes() {
        val swipes = listOf(0f to -60f, 0f to 60f, -60f to 0f, 60f to 0f)
        for (mode in TouchMode.entries) {
            val got = swipes.map { (dx, dy) ->
                make(mode)
                threeDown()
                fourthDown(50)
                fourMove(60, dx / 2, dy / 2)
                fourMove(80, dx, dy)
                fourMove(100, dx * 3, dy * 3)
                touch(Kind.UP, 120, 500f to 1000f)
                assertThat(actions().filter { it != "Move" }).isEmpty()
                commands.single()
            }
            assertThat(got).containsExactly(RadialEntry.Builtin(RadialItem.MISSION_CONTROL), RadialEntry.Builtin(RadialItem.APP_EXPOSE), RadialEntry.Builtin(RadialItem.SPACE_RIGHT), RadialEntry.Builtin(RadialItem.SPACE_LEFT)).inOrder()
        }
    }

    @Test
    fun aFourthFingerAfterThreeTravelledOrLiftedAsksForNothing() {
        val noThreeDrag = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.THREE_FINGER_DRAG, GestureAction.None)
        for ((mode, map) in listOf(TouchMode.DIRECT to TouchMap.defaults(TouchMode.DIRECT), TouchMode.TRACKPAD to noThreeDrag)) {
            make(mode, map = map)
            threeDown()
            threeMove(60, 20f, 0f)
            fourthDown(70, 20f)
            fourMove(100, 0f, -100f, 20f)
            touch(Kind.UP, 120, 500f to 900f)
            assertThat(commands).isEmpty()
        }
        for (mode in TouchMode.entries) {
            make(mode)
            threeDown()
            touch(Kind.POINTER_UP, 50, 400f to 1000f, 500f to 1000f, 600f to 1000f)
            touch(Kind.MOVE, 55, 500f to 1000f, 600f to 1000f)
            touch(Kind.POINTER_DOWN, 60, 500f to 1000f, 600f to 1000f, 700f to 1000f)
            touch(Kind.POINTER_DOWN, 65, 400f to 1000f, 500f to 1000f, 600f to 1000f, 700f to 1000f)
            fourMove(80, 0f, -100f)
            touch(Kind.UP, 120, 500f to 900f)
            assertThat(commands).isEmpty()
            assertThat(pointers(PointerAction.Down)).isEmpty()
        }
    }

    @Test
    fun aFifthFingerSpendsTheTouch() {
        make()
        threeDown()
        fourthDown(50)
        touch(Kind.POINTER_DOWN, 60, 400f to 1000f, 500f to 1000f, 600f to 1000f, 700f to 1000f, 800f to 1000f)
        touch(Kind.MOVE, 80, 400f to 800f, 500f to 800f, 600f to 800f, 700f to 800f, 800f to 800f)
        touch(Kind.UP, 120, 500f to 800f)
        assertThat(commands).isEmpty()
    }

    @Test
    fun aThreeFingerTapAsksForTheTapsActionWithoutPressing() {
        for (mode in TouchMode.entries) {
            make(mode, shared = mapOf(Gesture.THREE_FINGER_TAP to item(RadialItem.MUTE)))
            threeDown()
            touch(Kind.POINTER_UP, 100, 400f to 1000f, 500f to 1000f, 600f to 1000f)
            touch(Kind.UP, 110, 500f to 1000f)
            at(1000)
            assertThat(commands).containsExactly(RadialEntry.Builtin(RadialItem.MUTE))
            assertThat(sent.filterIsInstance<InputEvent.RightClick>()).isEmpty()
            assertThat(pointers(PointerAction.Down)).isEmpty()
        }
    }

    @Test
    fun aThreeFingerTapWithNoActionAsksForNothing() {
        make()
        threeDown()
        touch(Kind.UP, 100, 500f to 1000f)
        at(1000)
        assertThat(commands).isEmpty()
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).isEmpty()
    }

    @Test
    fun fingersLiftingOneByOneAreATapNotAGrab() {
        make(TouchMode.TRACKPAD, shared = mapOf(Gesture.THREE_FINGER_TAP to item(RadialItem.MUTE)))
        threeDown()
        touch(Kind.POINTER_UP, 100, 400f to 1000f, 500f to 1000f, 600f to 1000f)
        // The two fingers left are centred 50 px from where the three were.
        touch(Kind.MOVE, 110, 500f to 1000f, 600f to 1000f)
        touch(Kind.POINTER_UP, 120, 500f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 130, 600f to 1000f)
        touch(Kind.UP, 140, 600f to 1000f)
        at(1000)
        assertThat(commands).containsExactly(RadialEntry.Builtin(RadialItem.MUTE))
        assertThat(pointers(PointerAction.Down)).isEmpty()
    }

    @Test
    fun threeFingersThatLostOneNeverGrab() {
        make(TouchMode.TRACKPAD)
        threeDown()
        touch(Kind.POINTER_UP, 50, 400f to 1000f, 500f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 60, 500f to 1000f, 600f to 1000f)
        touch(Kind.POINTER_DOWN, 70, 500f to 1000f, 600f to 1000f, 700f to 1000f)
        touch(Kind.MOVE, 200, 530f to 1000f, 630f to 1000f, 730f to 1000f)
        touch(Kind.MOVE, 300, 600f to 1000f, 700f to 1000f, 800f to 1000f)
        at(1000)
        assertThat(pointers(PointerAction.Down)).isEmpty()
    }

    @Test
    fun threeFingersHeldPastTheTapTimeAreNoTap() {
        make(shared = mapOf(Gesture.THREE_FINGER_TAP to item(RadialItem.MUTE)))
        threeDown()
        touch(Kind.UP, GestureRecognizer.TAP_MAX, 500f to 1000f)
        assertThat(commands).isEmpty()
    }

    @Test
    fun threeFingersMovingInTheDirectModeDoNothing() {
        for (row in listOf(GestureAction.Drag, GestureAction.None)) {
            make(TouchMode.DIRECT, map = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.THREE_FINGER_DRAG, row))
            threeDown()
            threeMove(60, 0f, 30f)
            threeMove(200, 0f, 100f)
            touch(Kind.UP, 300, 500f to 1100f)
            at(1000)
            assertThat(actions().filter { it != "Move" }).isEmpty()
            assertThat(commands).isEmpty()
        }
    }

    @Test
    fun aThirdFingerAfterTheScrollBeganKeepsScrolling() {
        make()
        touch(Kind.DOWN, 0, 400f to 1000f)
        touch(Kind.POINTER_DOWN, 20, 400f to 1000f, 500f to 1000f)
        touch(Kind.MOVE, 40, 400f to 950f, 500f to 950f)
        touch(Kind.POINTER_DOWN, 60, 400f to 950f, 500f to 950f, 600f to 950f)
        touch(Kind.MOVE, 80, 400f to 850f, 500f to 850f, 600f to 850f)
        touch(Kind.UP, 120, 500f to 850f)
        assertThat(commands).isEmpty()
        assertThat(sent.filterIsInstance<InputEvent.Scroll>()).hasSize(2)
    }

    // MARK: a trackpad grab

    /** Grabs with three fingers (Down and Drag at 140) and returns the cursor's x after that. */
    private fun grab(): Float {
        threeDown()
        touch(Kind.MOVE, 140, 430f to 1000f, 530f to 1000f, 630f to 1000f)
        return pointers(PointerAction.Drag).last().x
    }

    @Test
    fun liftingAFingerWhileGrabbingDoesNotJumpTheCursor() {
        make(TouchMode.TRACKPAD)
        grab()
        touch(Kind.MOVE, 160, 430f to 1000f, 530f to 1000f, 630f to 1000f)
        val before = pointers(PointerAction.Drag).last().x
        touch(Kind.MOVE, 200, 530f to 1000f, 630f to 1000f, 730f to 1000f)
        val kept = pointers(PointerAction.Drag).last().x - before

        make(TouchMode.TRACKPAD)
        grab()
        touch(Kind.POINTER_UP, 150, 430f to 1000f, 530f to 1000f, 630f to 1000f)
        val drags = pointers(PointerAction.Drag).size
        touch(Kind.MOVE, 160, 530f to 1000f, 630f to 1000f)
        assertThat(pointers(PointerAction.Drag)).hasSize(drags)
        val start = pointers(PointerAction.Drag).last().x
        touch(Kind.MOVE, 200, 630f to 1000f, 730f to 1000f)
        val lifted = pointers(PointerAction.Drag).last().x - start
        assertThat(lifted).isGreaterThan(0f)
        assertThat(lifted).isWithin(1e-5f).of(kept)
        assertThat(pointers(PointerAction.Up)).isEmpty()
    }

    @Test
    fun threeFingersBackWithinTheGraceCarryTheGrabOn() {
        make(TouchMode.TRACKPAD)
        grab()
        touch(Kind.UP, 200, 530f to 1000f)
        touch(Kind.DOWN, 300, 500f to 1500f)
        touch(Kind.POINTER_DOWN, 320, 500f to 1500f, 700f to 1500f)
        touch(Kind.POINTER_DOWN, 340, 500f to 1500f, 700f to 1500f, 900f to 1500f)
        val drags = pointers(PointerAction.Drag).size
        touch(Kind.MOVE, 400, 550f to 1500f, 750f to 1500f, 950f to 1500f)
        at(2000)
        assertThat(pointers(PointerAction.Down)).hasSize(1)
        assertThat(pointers(PointerAction.Drag).size).isGreaterThan(drags)
        assertThat(pointers(PointerAction.Up)).isEmpty()
        assertThat(events).containsExactly(GestureEvent.DragStarted(DragStart.THREE_FINGER), GestureEvent.DragResumed).inOrder()
    }

    @Test
    fun aGrabLetGoForTheGraceGoesUp() {
        make(TouchMode.TRACKPAD)
        grab()
        touch(Kind.UP, 200, 530f to 1000f)
        at(699)
        assertThat(pointers(PointerAction.Up)).isEmpty()
        val o = r.onTimer(700).also(::take)
        assertThat(o.inputs).hasSize(1)
        assertThat((o.inputs.single() as InputEvent.Pointer).action).isEqualTo(PointerAction.Up)
        assertThat(o.events).containsExactly(GestureEvent.DragEnded(DragEnd.TIMEOUT))
    }

    @Test
    fun aTapWithinTheGraceDropsTheGrabWithoutClicking() {
        make(TouchMode.TRACKPAD)
        grab()
        touch(Kind.UP, 200, 530f to 1000f)
        tap(300, 500f, 1000f, 60)
        assertThat(actions().filter { it != "Move" && it != "Drag" }).containsExactly("Down", "Up").inOrder()
        assertThat(events.last()).isEqualTo(GestureEvent.DragEnded(DragEnd.TAP))
        tap(400, 500f, 1000f, 60)
        assertThat(actions().filter { it != "Move" && it != "Drag" }).containsExactly("Down", "Up", "Down", "Up").inOrder()

        make(TouchMode.TRACKPAD)
        grab()
        touch(Kind.UP, 200, 530f to 1000f)
        touch(Kind.DOWN, 300, 500f to 1000f)
        touch(Kind.POINTER_DOWN, 310, 500f to 1000f, 600f to 1000f)
        touch(Kind.UP, 350, 500f to 1000f)
        at(2000)
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).isEmpty()
        assertThat(pointers(PointerAction.Up)).hasSize(1)
    }

    @Test
    fun aMoveWithinTheGraceDropsTheGrabAndMovesTheCursorFromTheNextSample() {
        make(TouchMode.TRACKPAD)
        grab()
        touch(Kind.UP, 200, 530f to 1000f)
        touch(Kind.DOWN, 300, 500f to 1000f)
        val o = touchOut(Kind.MOVE, 320, 530f to 1000f)
        assertThat(o.inputs.map { (it as InputEvent.Pointer).action }).containsExactly(PointerAction.Up)
        assertThat(o.events).containsExactly(GestureEvent.DragEnded(DragEnd.MOVE))
        val n = touchOut(Kind.MOVE, 340, 560f to 1000f)
        assertThat(n.inputs.map { (it as InputEvent.Pointer).action }).containsExactly(PointerAction.Move)
        touch(Kind.UP, 360, 560f to 1000f)
        at(2000)
        assertThat(pointers(PointerAction.Down)).hasSize(1)
    }

    @Test
    fun aTouchThatLeftTheGraceByMovingIsNoTapAndTakesNoThirdFinger() {
        make(TouchMode.TRACKPAD, shared = mapOf(Gesture.THREE_FINGER_TAP to item(RadialItem.MUTE)))
        grab()
        touch(Kind.UP, 200, 530f to 1000f)
        touch(Kind.DOWN, 300, 500f to 1000f)
        touch(Kind.POINTER_DOWN, 310, 500f to 1000f, 600f to 1000f)
        touch(Kind.MOVE, 320, 530f to 1000f, 630f to 1000f)
        touch(Kind.POINTER_DOWN, 330, 530f to 1000f, 630f to 1000f, 730f to 1000f)
        touch(Kind.UP, 350, 530f to 1000f)
        at(2000)
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).isEmpty()
        assertThat(commands).isEmpty()
        assertThat(pointers(PointerAction.Down)).hasSize(1)
    }

    @Test
    fun aGraceThatEndsUnderAFingerLeavesTheTouchSpent() {
        make(TouchMode.TRACKPAD)
        grab()
        touch(Kind.UP, 200, 530f to 1000f)
        touch(Kind.DOWN, 300, 500f to 1000f)
        at(700)
        assertThat(pointers(PointerAction.Up)).hasSize(1)
        touch(Kind.MOVE, 800, 700f to 1000f)
        touch(Kind.UP, 850, 700f to 1000f)
        at(2000)
        assertThat(actions().filter { it != "Drag" }).containsExactly("Down", "Up").inOrder()
    }

    @Test
    fun moreFingersJoinAGrabAndOnlyDrag() {
        make(TouchMode.TRACKPAD, shared = mapOf(Gesture.THREE_FINGER_TAP to item(RadialItem.MUTE)))
        grab()
        touch(Kind.POINTER_DOWN, 160, 430f to 1000f, 530f to 1000f, 630f to 1000f, 730f to 1000f)
        touch(Kind.POINTER_DOWN, 170, 430f to 1000f, 530f to 1000f, 630f to 1000f, 730f to 1000f, 830f to 1000f)
        touch(Kind.MOVE, 200, 430f to 900f, 530f to 900f, 630f to 900f, 730f to 900f, 830f to 900f)
        assertThat(commands).isEmpty()
        assertThat(pointers(PointerAction.Drag).last().y).isLessThan(0.5f)
        assertThat(pointers(PointerAction.Up)).isEmpty()
    }

    @Test
    fun aCancelLetsGoOfAGrabAndOfAGraceTouch() {
        make(TouchMode.TRACKPAD)
        grab()
        touch(Kind.CANCEL, 200, 430f to 1000f)
        assertThat(pointers(PointerAction.Up)).hasSize(1)
        assertThat(events.last()).isEqualTo(GestureEvent.DragEnded(DragEnd.CANCEL))

        make(TouchMode.TRACKPAD)
        grab()
        touch(Kind.UP, 200, 530f to 1000f)
        touch(Kind.DOWN, 300, 500f to 1000f)
        touch(Kind.CANCEL, 310, 500f to 1000f)
        assertThat(pointers(PointerAction.Up)).hasSize(1)
        at(2000)
        assertThat(pointers(PointerAction.Up)).hasSize(1)
    }

    @Test
    fun releaseLetsGoOfAHeldButtonOnly() {
        make(TouchMode.TRACKPAD)
        grab()
        val held = r.release().also(::take)
        assertThat(held.inputs.map { (it as InputEvent.Pointer).action }).containsExactly(PointerAction.Up)
        assertThat(held.events).containsExactly(GestureEvent.DragEnded(DragEnd.MODE))

        make(TouchMode.TRACKPAD)
        grab()
        touch(Kind.UP, 200, 530f to 1000f)
        assertThat(r.release().inputs.map { (it as InputEvent.Pointer).action }).containsExactly(PointerAction.Up)
        assertThat(r.nextDeadline()).isNull()

        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        assertThat(r.release().inputs).isEmpty()

        make(TouchMode.DIRECT)
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 50, 600f to 1000f)
        val direct = r.release()
        assertThat((direct.inputs.single() as InputEvent.Pointer).action).isEqualTo(PointerAction.Up)
        assertThat(direct.events).isEmpty()
    }

    @Test
    fun theDirectModeReportsNoGrabEvents() {
        make(TouchMode.DIRECT)
        touch(Kind.DOWN, 0, 500f to 1000f)
        at(600)
        touch(Kind.MOVE, 700, 600f to 1000f)
        touch(Kind.UP, 800, 600f to 1000f)
        touch(Kind.CANCEL, 900, 600f to 1000f)
        assertThat(pointers(PointerAction.Down)).isNotEmpty()
        assertThat(events).isEmpty()
    }

    // MARK: trackpad cursor within the slop

    private val start = InputEvent.Pointer(PointerAction.Move, 0.5f, 0.5f)

    @Test
    fun theTrackpadCursorMovesOnTheFirstSampleWithinTheSlop() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 16, 505f to 1000f)
        assertThat(pointers(PointerAction.Move).single().x).isGreaterThan(0.5f)
    }

    @Test
    fun theTrackpadCursorDoesNotJumpWhenTheFingerLeavesTheSlop() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        for (i in 1..4) touch(Kind.MOVE, 16L * i, 500f + 5f * i to 1000f)
        val xs = listOf(0.5f) + pointers(PointerAction.Move).map { it.x }
        assertThat(xs).hasSize(5)
        xs.zipWithNext { a, b -> assertThat(b - a).isWithin(1e-5f).of(5f * 1.2f * 1.3125f / 1000f) }
    }

    @Test
    fun aTrackpadTapAfterATravelWithinTheSlopClicksWhereTheTouchBegan() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 16, 505f to 1000f)
        assertThat(touchOut(Kind.UP, 60, 505f to 1000f).inputs).containsExactly(
            start,
            InputEvent.Pointer(PointerAction.Down, 0.5f, 0.5f),
            InputEvent.Pointer(PointerAction.Up, 0.5f, 0.5f),
        ).inOrder()
    }

    @Test
    fun aTrackpadFingerWithinTheStillRadiusOpensTheRadialMenuWhereTheTouchBegan() {
        for (dx in listOf(1f, still)) {
            make(TouchMode.TRACKPAD)
            touch(Kind.DOWN, 0, 500f to 1000f)
            touch(Kind.MOVE, 16, 500f + dx to 1000f)
            at(499)
            val out = r.onTimer(500)
            assertThat(out.inputs).containsExactly(start)
            assertThat(out.events).containsExactly(
                GestureEvent.LongPressJudged(fired = true, maxDp = dx, travelDp = dx, recentDp = 0f),
                GestureEvent.RadialOpened(500f + dx, 1000f, 0.5f, 0.5f),
            ).inOrder()
        }
    }

    @Test
    fun aTrackpadFingerPastTheStillRadiusButWithinTheSlopOpensNoMenuAndKeepsItsTravel() {
        for (dx in listOf(still + 1f, 10f)) {
            make(TouchMode.TRACKPAD)
            touch(Kind.DOWN, 0, 500f to 1000f)
            touch(Kind.MOVE, 16, 500f + dx to 1000f)
            at(1000)
            assertThat(events).containsExactly(GestureEvent.LongPressJudged(fired = false, maxDp = dx, travelDp = dx, recentDp = 0f))
            assertThat(actions()).containsExactly("Move")
            assertThat(pointers(PointerAction.Move).single().x).isGreaterThan(0.5f)
        }
    }

    @Test
    fun aTrackpadTapPastTheStillRadiusButWithinTheSlopClicksWhereTheTouchBegan() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 16, 500f + still + 2f to 1000f)
        assertThat(touchOut(Kind.UP, 60, 500f + still + 2f to 1000f).inputs).containsExactly(
            start,
            InputEvent.Pointer(PointerAction.Down, 0.5f, 0.5f),
            InputEvent.Pointer(PointerAction.Up, 0.5f, 0.5f),
        ).inOrder()
    }

    @Test
    fun aTrackpadFingerAimingBackAndForthCountsItsWholeTravel() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        val step = still * 0.4f
        touch(Kind.MOVE, 100, 500f + step to 1000f)
        touch(Kind.MOVE, 200, 500f to 1000f)
        touch(Kind.MOVE, 300, 500f + step to 1000f)
        at(1000)
        val judged = events.single() as GestureEvent.LongPressJudged
        assertThat(judged.fired).isFalse()
        assertThat(judged.maxDp).isWithin(1e-4f).of(step)
        assertThat(judged.travelDp).isWithin(1e-4f).of(3 * step)
    }

    @Test
    fun aTrackpadTouchThatMovedNeitherArmsAGrabNorFiresItsLongPress() {
        for (action in listOf(GestureAction.Drag, GestureAction.RIGHT_CLICK)) {
            make(TouchMode.TRACKPAD, map = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, action))
            touch(Kind.DOWN, 0, 500f to 1000f)
            touch(Kind.MOVE, 16, 500f + still + 1f to 1000f)
            at(1000)
            assertThat(events).containsExactly(GestureEvent.LongPressJudged(fired = false, maxDp = still + 1f, travelDp = still + 1f, recentDp = 0f))
            assertThat(actions()).containsExactly("Move")
        }
    }

    @Test
    fun aTrackpadFingerStillMovingAsTheWaitEndsOpensNoMenu() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        // Still until the last 150 ms, then three steps of half the settle distance: well within the still radius in all.
        val step = GestureRecognizer.LONG_PRESS_SETTLE_DP / 2
        for (i in 1..3) touch(Kind.MOVE, 300L + 60 * i, 500f + step * i to 1000f)
        at(1000)
        val judged = events.single() as GestureEvent.LongPressJudged
        assertThat(judged.fired).isFalse()
        assertThat(judged.travelDp).isAtMost(still)
        assertThat(judged.recentDp).isWithin(1e-4f).of(3 * step)
        assertThat(actions()).containsExactly("Move", "Move", "Move")
    }

    @Test
    fun aTrackpadFingerThatSettlesAfterLandingStillOpensTheRadialMenu() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 16, 502f to 1000f)
        touch(Kind.MOVE, 32, 504f to 1000f)
        touch(Kind.MOVE, 400, 504.5f to 1000f)
        at(500)
        assertThat(events).containsExactly(
            GestureEvent.LongPressJudged(fired = true, maxDp = 4.5f, travelDp = 4.5f, recentDp = 0.5f),
            GestureEvent.RadialOpened(504.5f, 1000f, 0.5f, 0.5f),
        ).inOrder()
    }

    @Test
    fun aDirectFingerWithinTheSlopStillOpensTheRadialMenu() {
        make(TouchMode.DIRECT, map = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.LONG_PRESS, GestureAction.RadialMenu))
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 16, 500f + still + 2f to 1000f)
        at(500)
        assertThat(events.filterIsInstance<GestureEvent.RadialOpened>()).hasSize(1)
        assertThat(events.filterIsInstance<GestureEvent.LongPressJudged>()).isEmpty()
    }

    @Test
    fun aTrackpadFingerPastTheSlopOpensNoMenuAndKeepsItsTravel() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 16, 511f to 1000f)
        at(1000)
        touch(Kind.UP, 1000, 511f to 1000f)
        assertThat(events).isEmpty()
        assertThat(actions()).containsExactly("Move")
        assertThat(pointers(PointerAction.Move).single().x).isGreaterThan(0.5f)
    }

    @Test
    fun aTrackpadTapThatSendsNothingKeepsTheTravelWithinTheSlop() {
        make(TouchMode.TRACKPAD, map = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.TAP, GestureAction.None))
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 16, 505f to 1000f)
        touch(Kind.UP, 60, 505f to 1000f)
        assertThat(sent).doesNotContain(start)
        assertThat(actions()).containsExactly("Move")
    }

    @Test
    fun aSecondFingerUndoesTheTravelWithinTheSlopAndRightClicksWhereTheTouchBegan() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 16, 505f to 1000f)
        assertThat(touchOut(Kind.POINTER_DOWN, 40, 505f to 1000f, 700f to 1000f).inputs).containsExactly(start)
        touch(Kind.UP, 80, 505f to 1000f)
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).containsExactly(InputEvent.RightClick(0.5f, 0.5f))
    }

    @Test
    fun aTrackpadLongPressRightClickUndoesTheTravelWithinTheSlop() {
        make(TouchMode.TRACKPAD, map = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, GestureAction.RIGHT_CLICK))
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 16, 501f to 1000f)
        at(499)
        assertThat(r.onTimer(500).inputs).containsExactly(start, InputEvent.RightClick(0.5f, 0.5f)).inOrder()
    }

    /** How far the first Drag lands from the Down. */
    private fun firstDragStep() = pointers(PointerAction.Drag).first().x - pointers(PointerAction.Down).single().x

    @Test
    fun aLongPressGrabDragsOnlyTheTravelPastTheSlop() {
        make(TouchMode.TRACKPAD, map = longPressDrag)
        touch(Kind.DOWN, 0, 500f to 1000f)
        at(500)
        touch(Kind.MOVE, 600, 530f to 1000f)
        assertThat(firstDragStep()).isWithin(1e-5f).of(20f * 1.2f * 1.05f / 1000f)
    }

    @Test
    fun aThreeFingerGrabDragsOnlyTheTravelPastTheSlop() {
        make(TouchMode.TRACKPAD)
        threeDown()
        threeMove(140, 30f, 0f)
        assertThat(firstDragStep()).isWithin(1e-5f).of(20f * 1.2f * 1.3f / 1000f)
    }

    @Test
    fun aThreeFingerGrabByTheTimerWithinTheSlopDragsNowhere() {
        make(TouchMode.TRACKPAD)
        threeDown()
        threeMove(60, 30f, 0f)
        threeMove(80, 0f, 0f)
        at(120)
        assertThat(pointers(PointerAction.Down)).hasSize(1)
        assertThat(firstDragStep()).isEqualTo(0f)
    }

    private val doubleTapDrag = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.DOUBLE_TAP_HOLD, GestureAction.Drag)

    @Test
    fun aDoubleTapGrabDragsOnlyTheTravelPastTheSlop() {
        make(TouchMode.TRACKPAD, map = doubleTapDrag)
        tap(0, 500f, 1000f, 60)
        sent.clear()
        touch(Kind.DOWN, 150, 500f to 1000f)
        touch(Kind.MOVE, 350, 530f to 1000f)
        // 30 px over the 200 ms held still: 0.15 dp/ms, a speed gain of 1.15.
        assertThat(firstDragStep()).isWithin(1e-5f).of(20f * 1.2f * 1.15f / 1000f)
    }

    @Test
    fun aDoubleTapGrabByTheTimerDropsTheTravelWithinTheSlop() {
        make(TouchMode.TRACKPAD, map = doubleTapDrag)
        tap(0, 500f, 1000f, 60)
        sent.clear()
        touch(Kind.DOWN, 150, 500f to 1000f)
        touch(Kind.MOVE, 200, 505f to 1000f)
        at(550)
        assertThat(pointers(PointerAction.Down)).hasSize(1)
        assertThat(pointers(PointerAction.Drag)).isEmpty()
        touch(Kind.MOVE, 566, 506f to 1000f)
        assertThat(firstDragStep()).isWithin(1e-5f).of(1f * 1.2f * 1.0625f / 1000f)
    }

    // MARK: radial menu

    @Test
    fun aTrackpadLongPressOpensTheRadialMenuAtTheFingerAndSendsNothing() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        at(499)
        assertThat(events).isEmpty()
        at(500)
        assertThat(events).containsExactly(stillHold, GestureEvent.RadialOpened(500f, 1000f, 0.5f, 0.5f)).inOrder()
        touch(Kind.MOVE, 600, 500f to 900f)
        touch(Kind.UP, 700, 500f to 900f)
        assertThat(events.drop(2)).containsExactly(GestureEvent.RadialMoved(500f, 900f), GestureEvent.RadialReleased(500f, 900f)).inOrder()
        assertThat(sent).isEmpty()
        assertThat(commands).isEmpty()
    }

    @Test
    fun aTrackpadMoveBeforeTheLongPressMovesTheCursorAndOpensNoMenu() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        touch(Kind.MOVE, 100, 600f to 1000f)
        at(1000)
        touch(Kind.UP, 1100, 600f to 1000f)
        assertThat(events).isEmpty()
        assertThat(pointers(PointerAction.Move)).isNotEmpty()
        assertThat(pointers(PointerAction.Down)).isEmpty()
    }

    @Test
    fun theRadialMenuClosesOnACancelAndOnAModeChange() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to 1000f)
        at(500)
        touch(Kind.CANCEL, 600, 500f to 1000f)
        assertThat(events.last()).isEqualTo(GestureEvent.RadialCancelled)
        touch(Kind.DOWN, 1000, 500f to 1000f)
        at(1500)
        take(r.release())
        assertThat(events.last()).isEqualTo(GestureEvent.RadialCancelled)
        assertThat(sent).isEmpty()
    }

    @Test
    fun aSecondFingerOnTheRadialMenuClosesItAndGrabsOnTheNextMove() {
        make(TouchMode.TRACKPAD)
        touch(Kind.DOWN, 0, 500f to -100f)
        at(500)
        touch(Kind.POINTER_DOWN, 600, 500f to -100f, 500f to 1000f)
        assertThat(sent).isEmpty()
        touch(Kind.MOVE, 620, 500f to -100f, 600f to 1000f)
        assertThat(events).containsExactly(
            stillHold,
            GestureEvent.RadialOpened(500f, -100f, 0.5f, 0.5f),
            GestureEvent.RadialCancelled,
            GestureEvent.DragStarted(DragStart.LONG_PRESS),
        ).inOrder()
        assertThat(pointers(PointerAction.Down)).hasSize(1)
    }

    @Test
    fun aDirectRadialMenuNeverPressesAndOpensOverTheFinger() {
        make(map = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.LONG_PRESS, GestureAction.RadialMenu))
        touch(Kind.DOWN, 0, 100f to 100f)
        at(499)
        assertThat(pointers(PointerAction.Down)).isEmpty()
        at(500)
        assertThat(events).containsExactly(GestureEvent.RadialOpened(100f, 100f, 0.1f, 0.05f))
        touch(Kind.MOVE, 600, 400f to 400f)
        touch(Kind.UP, 700, 400f to 400f)
        assertThat(actions()).containsExactly("Move")
    }

    @Test
    fun aSecondFingerOnADirectRadialMenuClosesItAndSendsNothingUntilTheFingersLift() {
        make(map = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.LONG_PRESS, GestureAction.RadialMenu))
        touch(Kind.DOWN, 0, 100f to 100f)
        at(500)
        sent.clear()
        touch(Kind.POINTER_DOWN, 600, 100f to 100f, 500f to 500f)
        touch(Kind.MOVE, 620, 100f to 100f, 700f to 700f)
        touch(Kind.MOVE, 640, 100f to 100f, 900f to 900f)
        touch(Kind.UP, 700, 100f to 100f)
        assertThat(sent).isEmpty()
        assertThat(viewports).isEmpty()
        assertThat(events.last()).isEqualTo(GestureEvent.RadialCancelled)
    }

    @Test
    fun aDirectRadialMenuStillClicksOnATapAndDragsOnAQuickMove() {
        make(map = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.LONG_PRESS, GestureAction.RadialMenu))
        tap(0, 100f, 100f, 60)
        assertThat(actions()).containsExactly("Move", "Down", "Up").inOrder()
        sent.clear()
        touch(Kind.DOWN, 1000, 100f to 100f)
        touch(Kind.MOVE, 1100, 400f to 400f)
        at(2000)
        touch(Kind.UP, 2100, 400f to 400f)
        assertThat(actions()).containsExactly("Move", "Down", "Drag", "Up").inOrder()
        assertThat(events).isEmpty()
    }

    // MARK: the catalog on one-shot gestures, the new gestures, and double tap and hold

    private val areaShot = RadialEntry.Custom(android.view.KeyEvent.KEYCODE_4, ModKey.SHIFT.meta or ModKey.CMD.meta, null, RadialLook.Text)

    private fun twoDown(t: Long = 0) {
        touch(Kind.DOWN, t, 300f to 300f)
        touch(Kind.POINTER_DOWN, t + 20, 300f to 300f, 500f to 300f)
    }

    private fun assertNothingSentButMoves() {
        assertThat(sent.filter { it !is InputEvent.Pointer || it.action != PointerAction.Move }).isEmpty()
    }

    @Test
    fun aTwoFingerLongPressRunsItsShortcutOnceAndNothingElse() {
        for (mode in TouchMode.entries) {
            make(mode, shared = mapOf(Gesture.TWO_FINGER_LONG_PRESS to GestureAction.Run(areaShot)))
            twoDown()
            at(519)
            assertThat(commands).isEmpty()
            at(520)
            assertThat(commands).containsExactly(areaShot)
            touch(Kind.MOVE, 600, 360f to 300f, 560f to 300f)
            touch(Kind.POINTER_UP, 700, 360f to 300f, 560f to 300f)
            touch(Kind.UP, 710, 360f to 300f)
            at(3000)
            assertThat(commands).containsExactly(areaShot)
            assertNothingSentButMoves()
        }
    }

    @Test
    fun twoFingersLiftedOrMovedBeforeTheLongPressRunNothing() {
        val shortcut = mapOf(Gesture.TWO_FINGER_LONG_PRESS to GestureAction.Run(areaShot))
        make(shared = shortcut)
        twoDown()
        touch(Kind.POINTER_UP, 400, 300f to 300f, 500f to 300f)
        touch(Kind.UP, 410, 300f to 300f)
        at(3000)
        assertThat(commands).isEmpty()

        make(shared = shortcut)
        twoDown()
        touch(Kind.MOVE, 100, 300f to 360f, 500f to 360f)
        at(3000)
        assertThat(commands).isEmpty()
        assertThat(sent.filterIsInstance<InputEvent.Scroll>()).isNotEmpty()

        make(shared = shortcut)
        twoDown()
        touch(Kind.MOVE, 100, 280f to 300f, 520f to 300f)
        touch(Kind.MOVE, 120, 250f to 300f, 550f to 300f)
        at(3000)
        assertThat(commands).isEmpty()

        make(shared = shortcut)
        twoDown()
        touch(Kind.POINTER_DOWN, 100, 300f to 300f, 500f to 300f, 700f to 300f)
        at(3000)
        assertThat(commands).isEmpty()
    }

    @Test
    fun aShortcutOnAOneShotGestureGoesOutAsACommandAndRightClickAndClickAsInputs() {
        make(shared = mapOf(Gesture.THREE_FINGER_TAP to item(RadialItem.COPY)))
        threeDown()
        touch(Kind.POINTER_UP, 100, 400f to 1000f, 500f to 1000f, 600f to 1000f)
        touch(Kind.UP, 110, 500f to 1000f)
        at(1000)
        assertThat(commands).containsExactly(RadialEntry.Builtin(RadialItem.COPY))
        assertNothingSentButMoves()

        make(shared = mapOf(Gesture.THREE_FINGER_TAP to GestureAction.RIGHT_CLICK))
        threeDown()
        touch(Kind.UP, 100, 500f to 1000f)
        assertThat(sent.filterIsInstance<InputEvent.RightClick>().single().x).isWithin(1e-5f).of(0.5f)
        assertThat(commands).isEmpty()

        make(shared = mapOf(Gesture.FOUR_FINGER_SWIPE_UP to GestureAction.Click))
        threeDown()
        fourthDown(50)
        fourMove(80, 0f, -60f)
        touch(Kind.UP, 120, 500f to 940f)
        assertThat(actions().filter { it != "Move" }).containsExactly("Down", "Up").inOrder()
    }

    @Test
    fun theRadialMenuOnAOneShotGestureOpensLeftOpenWhereTheFingersWere() {
        make(shared = mapOf(Gesture.THREE_FINGER_TAP to GestureAction.RadialMenu))
        threeDown()
        touch(Kind.UP, 100, 500f to 1000f)
        val radial = events.filter { it is GestureEvent.RadialOpened || it is GestureEvent.RadialReleased }
        assertThat(radial).hasSize(2)
        val opened = radial[0] as GestureEvent.RadialOpened
        assertThat(opened.x to opened.y).isEqualTo(500f to 1000f)
        assertThat(radial[1]).isEqualTo(GestureEvent.RadialReleased(500f, 1000f))
        assertNothingSentButMoves()
    }

    @Test
    fun aOneFingerLongPressRunsItsShortcutAndThenTheTouchSendsNothing() {
        for (mode in TouchMode.entries) {
            make(mode, map = TouchMap.defaults(mode).with(Gesture.LONG_PRESS, item(RadialItem.PASTE)))
            touch(Kind.DOWN, 0, 500f to 1000f)
            at(600)
            assertThat(commands).containsExactly(RadialEntry.Builtin(RadialItem.PASTE))
            touch(Kind.MOVE, 650, 700f to 1200f)
            touch(Kind.UP, 700, 700f to 1200f)
            assertThat(actions().filter { it != "Move" }).isEmpty()
            assertThat(commands).hasSize(1)
        }
    }

    /** Four fingers at the given points, landing one after another, unmoved. */
    private fun fourAt(vararg p: Pair<Float, Float>) {
        touch(Kind.DOWN, 0, p[0])
        touch(Kind.POINTER_DOWN, 10, p[0], p[1])
        touch(Kind.POINTER_DOWN, 20, p[0], p[1], p[2])
        touch(Kind.POINTER_DOWN, 30, p[0], p[1], p[2], p[3])
    }

    /** Each point [k] of the way to its centre (550, 1000): below 1 a pinch, above it a spread. */
    private fun scaled(k: Float, vararg p: Pair<Float, Float>) = p.map { (x, y) -> 550f + (x - 550f) * k to 1000f + (y - 1000f) * k }.toTypedArray()

    @Test
    fun fourFingersClosingOrOpeningAboutTheirCentrePinchOrSpread() {
        val line = arrayOf(400f to 1000f, 500f to 1000f, 600f to 1000f, 700f to 1000f)
        for (mode in TouchMode.entries) {
            for ((k, want) in listOf(0.6f to RadialItem.APPS, 1.4f to RadialItem.SHOW_DESKTOP)) {
                make(mode)
                fourAt(*line)
                touch(Kind.MOVE, 60, *scaled(1f + (k - 1f) / 2, *line))
                touch(Kind.MOVE, 80, *scaled(k, *line))
                touch(Kind.UP, 120, 550f to 1000f)
                at(1000)
                assertThat(commands).containsExactly(RadialEntry.Builtin(want))
                assertNothingSentButMoves()
            }
        }
    }

    @Test
    fun aThumbClosingOnThreeFingersIsAPinchNotASwipe() {
        make(TouchMode.TRACKPAD)
        val fingers = arrayOf(450f to 900f, 550f to 880f, 650f to 900f)
        fourAt(*fingers, 550f to 1150f)
        for ((t, y) in listOf(60L to 1110f, 80L to 1075f, 100L to 1040f, 120L to 1000f, 140L to 960f)) touch(Kind.MOVE, t, *fingers, 550f to y)
        touch(Kind.UP, 200, 550f to 960f)
        at(1000)
        assertThat(commands).containsExactly(RadialEntry.Builtin(RadialItem.APPS))
    }

    @Test
    fun fourFingersMovingTogetherStillSwipe() {
        make()
        val line = arrayOf(400f to 1000f, 500f to 1000f, 600f to 1000f, 700f to 1000f)
        fourAt(*line)
        // Spread a little as hands do while they travel: well under the pinch's share.
        touch(Kind.MOVE, 60, *scaled(1.1f, *line).map { (x, y) -> x to y - 30f }.toTypedArray())
        touch(Kind.MOVE, 80, *scaled(1.1f, *line).map { (x, y) -> x to y - 60f }.toTypedArray())
        touch(Kind.UP, 120, 550f to 940f)
        assertThat(commands).containsExactly(RadialEntry.Builtin(RadialItem.MISSION_CONTROL))
    }

    private fun holdAfterTap(mode: TouchMode, hold: GestureAction, inertia: Boolean = false) {
        make(mode, map = TouchMap.defaults(mode).with(Gesture.DOUBLE_TAP_HOLD, hold))
        r.inertia = inertia
        tap(0, 500f, 1000f, 60)
        touch(Kind.DOWN, 150, 500f to 1000f)
    }

    @Test
    fun aTapIsStillSentAsTheFingerLiftsWithZoomOrScrollOnDoubleTapAndHold() {
        for (mode in TouchMode.entries) for (hold in listOf(GestureAction.Zoom, GestureAction.Scroll)) {
            make(mode, map = TouchMap.defaults(mode).with(Gesture.DOUBLE_TAP_HOLD, hold))
            touch(Kind.DOWN, 0, 500f to 1000f)
            val up = touchOut(Kind.UP, 60, 500f to 1000f)
            assertThat(up.inputs.filterIsInstance<InputEvent.Pointer>().map { it.action }.filter { it != PointerAction.Move })
                .containsExactly(PointerAction.Down, PointerAction.Up).inOrder()
        }
    }

    @Test
    fun doubleTapAndHoldWithZoomZoomsDownwardAboutTheSecondTap() {
        for (mode in TouchMode.entries) {
            holdAfterTap(mode, GestureAction.Zoom)
            val before = r.toVideo(500f, 1000f)
            touch(Kind.MOVE, 350, 500f to 1100f)
            touch(Kind.MOVE, 400, 500f to 1100f + GestureRecognizer.DTH_ZOOM_DP)
            assertThat(r.viewport.zoom).isWithin(1e-4f).of(2f)
            val after = r.toVideo(500f, 1000f)
            assertThat(after.first).isWithin(1e-4f).of(before.first)
            assertThat(after.second).isWithin(1e-4f).of(before.second)
            touch(Kind.MOVE, 430, 500f to 500f)
            assertThat(r.viewport.zoom).isEqualTo(1f)
            touch(Kind.UP, 450, 500f to 500f)
            // The first tap's click, and no press for the hold.
            assertThat(actions().filter { it != "Move" }).containsExactly("Down", "Up").inOrder()
        }
    }

    @Test
    fun doubleTapAndHoldWithScrollScrollsWithTheFingerAndGlidesOn() {
        for (mode in TouchMode.entries) {
            holdAfterTap(mode, GestureAction.Scroll, inertia = true)
            touch(Kind.MOVE, 350, 500f to 1100f)
            touch(Kind.MOVE, 370, 500f to 1160f)
            touch(Kind.MOVE, 390, 500f to 1220f)
            assertThat(scrolls().sumOf { it.dy.toDouble() }).isWithin(1e-3).of(120.0)
            touch(Kind.UP, 400, 500f to 1220f)
            assertThat(events.filterIsInstance<GestureEvent.FlingStarted>().single().fromStrip).isFalse()
            assertThat(actions().filter { it != "Move" && it != "Scroll" }).containsExactly("Down", "Up").inOrder()
        }
    }

    @Test
    fun aDoubleTapAndHoldEndsWhenItsFingerLiftsAndASecondFingerMovesNothing() {
        for (mode in TouchMode.entries) for (hold in listOf(GestureAction.Zoom, GestureAction.Scroll)) {
            holdAfterTap(mode, hold)
            touch(Kind.MOVE, 350, 500f to 1030f)
            touch(Kind.MOVE, 370, 500f to 1040f)
            at(450)
            take(r.onTouch(Sample(Kind.POINTER_DOWN, 450, listOf(Finger(0, 500f, 1040f), Finger(1, 500f, 1500f)), actionId = 1)))
            take(r.onTouch(Sample(Kind.POINTER_UP, 550, listOf(Finger(0, 500f, 1040f), Finger(1, 500f, 1500f)), actionId = 0)))
            sent.clear()
            val zoom = r.viewport.zoom
            take(r.onTouch(Sample(Kind.MOVE, 570, listOf(Finger(1, 500f, 1501f)))))
            take(r.onTouch(Sample(Kind.MOVE, 590, listOf(Finger(1, 500f, 1700f)))))
            take(r.onTouch(Sample(Kind.UP, 610, listOf(Finger(1, 500f, 1700f)))))
            at(3000)
            assertThat(scrolls()).isEmpty()
            assertThat(r.viewport.zoom).isEqualTo(zoom)
            assertThat(actions().filter { it != "Move" }).isEmpty()
        }
    }

    @Test
    fun aSecondTouchThatMovesBeforeItIsHeldStillIsAnOrdinaryTouch() {
        for (hold in listOf(GestureAction.Drag, GestureAction.Zoom, GestureAction.Scroll)) {
            make(TouchMode.TRACKPAD, map = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.DOUBLE_TAP_HOLD, hold))
            tap(0, 500f, 1000f, 60)
            val clicks = pointers(PointerAction.Down).size
            touch(Kind.DOWN, 150, 500f to 1000f)
            touch(Kind.MOVE, 150 + GestureRecognizer.HOLD_STILL - 10, 600f to 1000f)
            touch(Kind.MOVE, 150 + GestureRecognizer.HOLD_STILL + 40, 700f to 1000f)
            touch(Kind.UP, 400, 700f to 1000f)
            at(2000)
            assertThat(pointers(PointerAction.Down)).hasSize(clicks)
            assertThat(scrolls()).isEmpty()
            assertThat(r.viewport.zoom).isEqualTo(1f)
            assertThat(events.filterIsInstance<GestureEvent.DoubleTapHeld>()).isEmpty()
            assertThat(pointers(PointerAction.Move).last().x).isGreaterThan(0.5f)
        }
    }

    @Test
    fun aSecondTouchHeldStillBeforeMovingStillHolds() {
        make(TouchMode.TRACKPAD, map = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.DOUBLE_TAP_HOLD, GestureAction.Drag))
        tap(0, 500f, 1000f, 60)
        touch(Kind.DOWN, 150, 500f to 1000f)
        touch(Kind.MOVE, 150 + GestureRecognizer.HOLD_STILL, 600f to 1000f)
        assertThat(events).contains(GestureEvent.DragStarted(DragStart.DOUBLE_TAP_HOLD))
    }

    @Test
    fun aSecondTouchTooLateOrTooFarForAHoldIsAnOrdinaryTouchButStillDoubleClicks() {
        val late = 60 + GestureRecognizer.HOLD_GAP + 10
        val far = 500f + GestureRecognizer.HOLD_SLOP_DP + 10f
        for ((t, x) in listOf(late to 500f, 150L to far)) {
            make(TouchMode.TRACKPAD, map = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.DOUBLE_TAP_HOLD, GestureAction.Drag))
            tap(0, 500f, 1000f, 60)
            touch(Kind.DOWN, t, x to 1000f)
            at(t + 600)
            assertThat(events.filterIsInstance<GestureEvent.DoubleTapHeld>()).isEmpty()
            assertThat(events.filterIsInstance<GestureEvent.DragStarted>()).isEmpty()
            touch(Kind.UP, t + 650, x to 1000f)

            make(TouchMode.TRACKPAD, map = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.DOUBLE_TAP_HOLD, GestureAction.Drag))
            tap(0, 500f, 1000f, 60)
            tap(t, x, 1000f, 60)
            assertThat(pointers(PointerAction.DownContinued)).hasSize(1)
        }
    }

    @Test
    fun aSecondTouchLiftedAfterATapsLengthButBeforeTheGrabSendsNothing() {
        make(TouchMode.TRACKPAD, map = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.DOUBLE_TAP_HOLD, GestureAction.Drag))
        tap(0, 500f, 1000f, 60)
        sent.clear()
        touch(Kind.DOWN, 150, 500f to 1000f)
        touch(Kind.UP, 150 + GestureRecognizer.TAP_MAX + 50, 500f to 1000f)
        at(3000)
        assertThat(actions().filter { it != "Move" }).isEmpty()
        assertThat(events).isEmpty()
    }
}
