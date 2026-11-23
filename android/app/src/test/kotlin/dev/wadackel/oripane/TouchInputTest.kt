package dev.wadackel.oripane

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.wadackel.oripane.protocol.InputEvent
import dev.wadackel.oripane.protocol.PointerAction
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TouchInputTest {
    private val sent = mutableListOf<InputEvent>()
    private lateinit var view: View
    private lateinit var input: TouchInput
    private var t0 = 0L

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = View(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(view, 1000, 2000) })
        view.layout(0, 0, 1000, 2000)
        input = TouchInput(view) { sent += it }
        t0 = SystemClock.uptimeMillis()
    }

    private fun event(action: Int, at: Long, vararg points: Pair<Float, Float>) = event(action, at, InputDevice.SOURCE_TOUCHSCREEN, *points)

    private fun event(action: Int, at: Long, source: Int, vararg points: Pair<Float, Float>): MotionEvent {
        val props = Array(points.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(points.size) { i -> MotionEvent.PointerCoords().apply { x = points[i].first; y = points[i].second; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(t0, t0 + at, action, points.size, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private var clock = 0L

    /** Lets the main looper run up to the event's time first, as it would between real events. */
    private fun touch(action: Int, at: Long, vararg points: Pair<Float, Float>) {
        if (at > clock) idle(at - clock)
        clock = at
        input.onTouch(event(action, at, *points))
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun pointers(action: PointerAction) = sent.filterIsInstance<InputEvent.Pointer>().filter { it.action == action }

    @Test
    fun tapIsAClickAtTheTouchedPoint() {
        touch(MotionEvent.ACTION_DOWN, 0, 250f to 500f)
        touch(MotionEvent.ACTION_UP, 60, 250f to 500f)
        assertThat(sent.map { (it as InputEvent.Pointer).action }).containsExactly(PointerAction.Move, PointerAction.Down, PointerAction.Up).inOrder()
        assertThat(pointers(PointerAction.Down).single()).isEqualTo(InputEvent.Pointer(PointerAction.Down, 0.25f, 0.25f))
    }

    @Test
    fun movingPastTheSlopPressesAtTheStartAndDrags() {
        touch(MotionEvent.ACTION_DOWN, 0, 100f to 100f)
        touch(MotionEvent.ACTION_MOVE, 16, 300f to 100f)
        touch(MotionEvent.ACTION_MOVE, 32, 500f to 100f)
        touch(MotionEvent.ACTION_UP, 48, 500f to 100f)
        assertThat(pointers(PointerAction.Down).single().x).isEqualTo(0.1f)
        assertThat(pointers(PointerAction.Drag).map { it.x }).containsExactly(0.3f, 0.5f).inOrder()
        assertThat(pointers(PointerAction.Up).single().x).isEqualTo(0.5f)
    }

    @Test
    fun restingFingerPressesAfterTheDelay() {
        touch(MotionEvent.ACTION_DOWN, 0, 100f to 100f)
        assertThat(pointers(PointerAction.Down)).isEmpty()
        idle(200)
        assertThat(pointers(PointerAction.Down)).hasSize(1)
    }

    @Test
    fun twoFingerTapIsARightClickWithoutALeftPress() {
        touch(MotionEvent.ACTION_DOWN, 0, 400f to 400f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20, 400f to 400f, 600f to 400f)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 80, 400f to 400f, 600f to 400f)
        touch(MotionEvent.ACTION_UP, 90, 400f to 400f)
        idle(200)
        assertThat(pointers(PointerAction.Down)).isEmpty()
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).containsExactly(InputEvent.RightClick(0.4f, 0.2f))
    }

    @Test
    fun twoFingerPanScrollsInTheFingersDirection() {
        touch(MotionEvent.ACTION_DOWN, 0, 400f to 400f)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 20, 400f to 400f, 600f to 400f)
        touch(MotionEvent.ACTION_MOVE, 40, 400f to 500f, 600f to 500f)
        touch(MotionEvent.ACTION_MOVE, 60, 400f to 560f, 600f to 560f)
        touch(MotionEvent.ACTION_UP, 300, 400f to 560f)
        idle(200)
        val scrolls = sent.filterIsInstance<InputEvent.Scroll>()
        assertThat(scrolls.map { it.dy }).containsExactly(100f, 60f).inOrder()
        assertThat(pointers(PointerAction.Down)).isEmpty()
        assertThat(sent.filterIsInstance<InputEvent.RightClick>()).isEmpty()
    }

    @Test
    fun switchingToTrackpadLiveCentresTheCursorAndKeepsTheFrameSize() {
        touch(MotionEvent.ACTION_DOWN, 0, 250f to 500f)
        touch(MotionEvent.ACTION_UP, 60, 250f to 500f)
        idle(500)
        // The looper ran ahead; later events keep its time, or a long press would arm between them.
        clock = 560
        sent.clear()
        val trackpad = Settings(touchMode = TouchMode.TRACKPAD)
        input.applySettings(trackpad)
        assertThat(sent).containsExactly(InputEvent.Pointer(PointerAction.Move, 0.5f, 0.5f))
        touch(MotionEvent.ACTION_DOWN, 1000, 500f to 1000f)
        touch(MotionEvent.ACTION_MOVE, 1100, 600f to 1000f)
        touch(MotionEvent.ACTION_UP, 1200, 600f to 1000f)
        assertThat(pointers(PointerAction.Move).last().x).isGreaterThan(0.5f)
    }

    @Test
    fun switchingToDirectLetsGoOfATrackpadGrab() {
        val grab = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, GestureAction.Drag)
        input.applySettings(Settings(touchMode = TouchMode.TRACKPAD, mapTrackpad = grab))
        touch(MotionEvent.ACTION_DOWN, 0, 500f to 1000f)
        touch(MotionEvent.ACTION_MOVE, 600, 501f to 1000f)
        touch(MotionEvent.ACTION_MOVE, 700, 600f to 1000f)
        assertThat(pointers(PointerAction.Down)).hasSize(1)
        assertThat(pointers(PointerAction.Up)).isEmpty()
        input.applySettings(Settings(touchMode = TouchMode.DIRECT))
        assertThat(pointers(PointerAction.Up)).hasSize(1)
        idle(2000)
        assertThat(pointers(PointerAction.Up)).hasSize(1)
    }

    @Test
    fun aScrollChangeKeepsTheTrackpadCursorWhereItIs() {
        input.applySettings(Settings(touchMode = TouchMode.TRACKPAD))
        input.applySettings(Settings(touchMode = TouchMode.TRACKPAD, touchScroll = ScrollSetting(SpeedStep(7), reverse = true)))
        assertThat(pointers(PointerAction.Move)).hasSize(1)
        touch(MotionEvent.ACTION_DOWN, 0, 900f to 1900f)
        touch(MotionEvent.ACTION_UP, 60, 900f to 1900f)
        assertThat(pointers(PointerAction.Down).single()).isEqualTo(InputEvent.Pointer(PointerAction.Down, 0.5f, 0.5f))
    }

    /** The cursor's travel on the last move of a one-finger drag from the middle, starting at [at]. */
    private fun dragTravel(at: Long): Float {
        touch(MotionEvent.ACTION_DOWN, at, 500f to 1000f)
        touch(MotionEvent.ACTION_MOVE, at + 50, 520f to 1000f)
        touch(MotionEvent.ACTION_MOVE, at + 100, 560f to 1000f)
        touch(MotionEvent.ACTION_UP, at + 150, 560f to 1000f)
        val moves = pointers(PointerAction.Move)
        return moves.last().x - moves[moves.size - 2].x
    }

    @Test
    fun aCursorSpeedChangeWhileConnectedTakesEffectOnTheNextDrag() {
        input.applySettings(Settings(touchMode = TouchMode.TRACKPAD))
        val before = dragTravel(0)
        input.applySettings(Settings(touchMode = TouchMode.TRACKPAD, cursorSpeed = SpeedStep(SpeedStep.MAX)))
        val after = dragTravel(2000)
        assertThat(before).isGreaterThan(0f)
        assertThat(after).isWithin(1e-4f).of(before * 2.25f)
    }

    @Test
    fun theViewportSurvivesATouchModeSwitchAndPlacesTaps() {
        val seen = mutableListOf<Viewport>()
        input = TouchInput(view, onViewport = { seen += it }) { sent += it }
        input.setViewport(Viewport(2f, 0.25f, 0.25f))
        assertThat(seen).containsExactly(Viewport(2f, 0.25f, 0.25f))
        input.applySettings(Settings(touchMode = TouchMode.TRACKPAD))
        input.applySettings(Settings(touchMode = TouchMode.DIRECT))
        assertThat(input.viewport).isEqualTo(Viewport(2f, 0.25f, 0.25f))
        sent.clear()
        touch(MotionEvent.ACTION_DOWN, 0, 500f to 1000f)
        touch(MotionEvent.ACTION_UP, 60, 500f to 1000f)
        assertThat(pointers(PointerAction.Down).single()).isEqualTo(InputEvent.Pointer(PointerAction.Down, 0.5f, 0.5f))
    }

    @Test
    fun aFourFingerSwipeUpAsksForMissionControl() {
        val asked = mutableListOf<RadialEntry>()
        input = TouchInput(view, onCommand = { asked += it }) { sent += it }
        fun down(i: Int) = MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        touch(MotionEvent.ACTION_DOWN, 0, 400f to 1000f)
        touch(down(1), 20, 400f to 1000f, 500f to 1000f)
        touch(down(2), 40, 400f to 1000f, 500f to 1000f, 600f to 1000f)
        touch(down(3), 50, 400f to 1000f, 500f to 1000f, 600f to 1000f, 700f to 1000f)
        touch(MotionEvent.ACTION_MOVE, 60, 400f to 800f, 500f to 800f, 600f to 800f, 700f to 800f)
        touch(MotionEvent.ACTION_UP, 100, 500f to 800f)
        assertThat(asked).containsExactly(RadialEntry.Builtin(RadialItem.MISSION_CONTROL))
        assertThat(sent.filterIsInstance<InputEvent.Scroll>()).isEmpty()
    }

    @Test
    fun touchesAroundACentredFrameAreMeasuredFromTheFrame() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = View(activity)
        val area = FrameLayout(activity).apply { addView(frame, FrameLayout.LayoutParams(1000, 2000, Gravity.CENTER)) }
        activity.setContentView(area, ViewGroup.LayoutParams(2000, 3000))
        area.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.EXACTLY))
        area.layout(0, 0, 2000, 3000)
        idle(0)
        assertThat(frame.left to frame.top).isEqualTo(500 to 500)
        input = TouchInput(frame) { sent += it }

        touch(MotionEvent.ACTION_DOWN, 0, 750f to 1500f)
        touch(MotionEvent.ACTION_UP, 60, 750f to 1500f)
        assertThat(pointers(PointerAction.Down).single()).isEqualTo(InputEvent.Pointer(PointerAction.Down, 0.25f, 0.5f))

        sent.clear()
        touch(MotionEvent.ACTION_DOWN, 500, 100f to 100f)
        touch(MotionEvent.ACTION_UP, 560, 100f to 100f)
        idle(1000)
        assertThat(sent).isEmpty()

        input.onGeneric(event(MotionEvent.ACTION_HOVER_MOVE, 2000, 1000f to 2000f))
        assertThat(sent).containsExactly(InputEvent.Pointer(PointerAction.Move, 0.5f, 0.75f))
    }

    @Test
    fun fillScreenCoversTheBlackBarsAndTapsThereClick() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = View(activity)
        val area = FrameLayout(activity).apply { addView(frame, FrameLayout.LayoutParams(1000, 2000, Gravity.CENTER)) }
        activity.setContentView(area, ViewGroup.LayoutParams(2000, 3000))
        area.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.EXACTLY))
        area.layout(0, 0, 2000, 3000)
        idle(0)
        val viewports = mutableListOf<Viewport>()
        input = TouchInput(frame, onViewport = { viewports += it }) { sent += it }

        // The bars at the sides are the wider: twice the frame fills the screen.
        input.fillScreen(0.5f, 0.5f)
        assertThat(viewports.last().zoom).isEqualTo(2f)

        touch(MotionEvent.ACTION_DOWN, 0, 100f to 100f)
        touch(MotionEvent.ACTION_UP, 60, 100f to 100f)
        assertThat(pointers(PointerAction.Down)).hasSize(1)

        sent.clear()
        input.applySettings(Settings.defaults.copy(touchMode = TouchMode.TRACKPAD))
        input.applySettings(Settings.defaults)
        sent.clear()
        touch(MotionEvent.ACTION_DOWN, 1000, 100f to 100f)
        touch(MotionEvent.ACTION_UP, 1060, 100f to 100f)
        assertThat(pointers(PointerAction.Down)).hasSize(1)

        sent.clear()
        input.onTouch(event(MotionEvent.ACTION_DOWN, 2000, InputDevice.SOURCE_MOUSE, 100f to 100f))
        val down = pointers(PointerAction.Down).single()
        // 100 px into the 2000 px wide video, and 600 px into the 4000 px tall one shown from 1000 px down.
        assertThat(down.x).isWithin(1e-5f).of(0.05f)
        assertThat(down.y).isWithin(1e-5f).of(0.15f)
    }
}
