package dev.wadackel.oripane

import android.content.Context
import android.view.KeyEvent
import dev.wadackel.oripane.protocol.InputEvent
import dev.wadackel.oripane.protocol.PointerAction
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsTest {
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        prefs = Prefs(RuntimeEnvironment.getApplication())
    }

    private fun raw() = RuntimeEnvironment.getApplication().getSharedPreferences("oripane", Context.MODE_PRIVATE)

    private fun item(i: RadialItem) = GestureAction.Run(RadialEntry.Builtin(i))

    private val areaShot = RadialEntry.Custom(KeyEvent.KEYCODE_4, ModKey.SHIFT.meta or ModKey.CMD.meta, "Area shot", RadialLook.Text)

    @Test
    fun anUntouchedAppHasTheDefaults() {
        val s = prefs.settings
        assertThat(s).isEqualTo(Settings.defaults)
        assertThat(s.describe()).isEqualTo(
            "video.scale=1.0 video.fps=60 dataSaver=auto video.uiScale=2.0 touch.mode=direct " +
                """touch.map.direct={"tap":"click","longPress":"dragStart","doubleTapHold":"none","threeFingerDrag":"none","twoFingerTap":"rightClick","twoFingerSwipe":"scroll",$SHORTCUT_DEFAULTS} """ +
                """touch.map.trackpad={"tap":"click","longPress":"radialMenu","doubleTapHold":"none","threeFingerDrag":"dragStart","twoFingerTap":"rightClick","twoFingerSwipe":"scroll",$SHORTCUT_DEFAULTS} """ +
                "radial.menu=missionControl+copy+spaceRight+paste+showDesktop+keyboard+spaceLeft+rightClick " +
                "touch.cursorSpeed=5 scroll.touch.speed=5 scroll.touch.reverse=false scroll.wheel.speed=5 scroll.wheel.reverse=false " +
                "scroll.strip=right scroll.strip.knob=0.64 scroll.strip.haptics=true scroll.band.size=medium scroll.inertia=true keyboard.incognito=false showStats=true",
        )
    }

    @Test
    fun settingsSurviveTheRoundTripThroughPrefs() {
        val s = Settings(
            scale = 0.75, fps = 30, dataSaver = DataSaver.OFF, uiScale = 1.5, touchMode = TouchMode.TRACKPAD,
            mapDirect = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.TWO_FINGER_TAP, GestureAction.Click),
            mapTrackpad = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.THREE_FINGER_TAP, item(RadialItem.MUTE)).with(Gesture.FOUR_FINGER_SWIPE_UP, GestureAction.None)
                .with(Gesture.TWO_FINGER_LONG_PRESS, GestureAction.Run(areaShot)).with(Gesture.DOUBLE_TAP_HOLD, GestureAction.Zoom),
            cursorSpeed = SpeedStep(7), touchScroll = ScrollSetting(SpeedStep(3), true), wheelScroll = ScrollSetting(SpeedStep(7), false), scrollStrip = ScrollStrip.LEFT, stripKnobAt = 0.3f, scrollBandSize = BandSize.SMALL, stripHaptics = false, scrollInertia = false,
            incognitoKeyboard = true, showStats = false,
        )
        prefs.settings = s
        assertThat(Prefs(RuntimeEnvironment.getApplication()).settings).isEqualTo(s)
    }

    @Test
    fun unknownValuesAndWrongTypesReadAsDefaults() {
        raw().edit().putString(SettingKeys.SCALE, "0.6").putString(SettingKeys.FPS, "x").putInt(SettingKeys.UI_SCALE, 3)
            .putBoolean(SettingKeys.TOUCH_MODE, true).putString(SettingKeys.CURSOR_SPEED, "warp").apply()
        val s = prefs.settings
        assertThat(s.scale).isEqualTo(1.0)
        assertThat(s.fps).isEqualTo(60)
        assertThat(s.uiScale).isEqualTo(2.0)
        assertThat(s.touchMode).isEqualTo(TouchMode.DIRECT)
        assertThat(s.cursorSpeed).isEqualTo(SpeedStep.default)
    }

    @Test
    fun aSpeedStoredAsSlowNormalOrFastKeepsItsOldSpeed() {
        raw().edit().putString(SettingKeys.CURSOR_SPEED, "slow").putString(SettingKeys.TOUCH_SCROLL_SPEED, "fast").putString(SettingKeys.WHEEL_SCROLL_SPEED, "normal").apply()
        val s = prefs.settings
        assertThat(s.cursorSpeed).isEqualTo(SpeedStep(3))
        assertThat(s.cursorSpeed.cursor).isWithin(1e-6f).of(0.8f)
        assertThat(s.touchScroll.speed).isEqualTo(SpeedStep(7))
        assertThat(s.touchScroll.speed.scroll).isEqualTo(2f)
        assertThat(s.wheelScroll.speed).isEqualTo(SpeedStep.default)
        assertThat(SpeedStep(7).cursor).isWithin(1e-6f).of(1.8f)
        assertThat(SpeedStep(3).scroll).isEqualTo(0.5f)
    }

    @Test
    fun aSpeedOffTheStepsReadsAsTheDefault() {
        for (stored in listOf("0", "10", "4.5", "Fast")) {
            raw().edit().putString(SettingKeys.CURSOR_SPEED, stored).apply()
            assertThat(prefs.settings.cursorSpeed).isEqualTo(SpeedStep.default)
        }
    }

    @Test
    fun everySpeedStepReadsApartAsItsMultipleOfTheDefault() {
        val steps = (SpeedStep.MIN..SpeedStep.MAX).map(::SpeedStep)
        assertThat(steps.map { speedLabel(it.scrollFactor) }).containsExactly("0.25×", "0.35×", "0.5×", "0.71×", "1×", "1.4×", "2×", "2.8×", "4×").inOrder()
        assertThat(steps.map { speedLabel(it.cursorFactor) }).containsExactly("0.44×", "0.54×", "0.67×", "0.82×", "1×", "1.2×", "1.5×", "1.8×", "2.3×").inOrder()
    }

    @Test
    fun aSpeedStepStopsAtTheEndsOfTheSlider() {
        assertThat(SpeedStep(SpeedStep.MAX) + 1).isEqualTo(SpeedStep(SpeedStep.MAX))
        assertThat(SpeedStep(SpeedStep.MIN) - 1).isEqualTo(SpeedStep(SpeedStep.MIN))
        assertThat(SpeedStep(4) + 1).isEqualTo(SpeedStep.default)
    }

    @Test
    fun theHookSetsASpeedByItsStep() {
        val p = SettingsHook.parse("touch.cursorSpeed=8,scroll.touch.speed=fast,scroll.wheel.speed=10")
        assertThat(p.writes).containsExactly(SettingKeys.CURSOR_SPEED, "8")
        assertThat(p.ignored).containsExactly(SettingKeys.TOUCH_SCROLL_SPEED, SettingKeys.WHEEL_SCROLL_SPEED).inOrder()
    }

    @Test
    fun theScrollStripAndInertiaReadUnknownValuesAsTheirDefaults() {
        raw().edit().putString(SettingKeys.SCROLL_STRIP, "middle").putString(SettingKeys.STRIP_HAPTICS, "maybe").putInt(SettingKeys.SCROLL_INERTIA, 0)
            .putString(SettingKeys.STRIP_KNOB, "1.5").apply()
        val s = prefs.settings
        assertThat(s.scrollStrip).isEqualTo(ScrollStrip.RIGHT)
        assertThat(s.stripKnobAt).isEqualTo(0.64f)
        assertThat(s.stripHaptics).isTrue()
        assertThat(s.scrollInertia).isTrue()
    }

    @Test
    fun theHookSetsTheScrollStripAndInertia() {
        val p = SettingsHook.parse("scroll.strip=left,scroll.strip.haptics=false,scroll.inertia=false,scroll.strip=up")
        assertThat(p.ignored).containsExactly(SettingKeys.SCROLL_STRIP)
        SettingsHook.apply(prefs, "scroll.strip=left,scroll.strip.haptics=false,scroll.inertia=false")
        val s = prefs.settings
        assertThat(s.scrollStrip).isEqualTo(ScrollStrip.LEFT)
        assertThat(s.stripHaptics).isFalse()
        assertThat(s.scrollInertia).isFalse()
    }

    @Test
    fun aMappingRowThatIsMissingOrNotAChoiceFallsBackAlone() {
        val m = TouchMap.fromJson("""{"tap":"rightClick","doubleTapHold":"rightClick","longPress":42}""", TouchMode.DIRECT)
        assertThat(m[Gesture.TAP]).isEqualTo(GestureAction.RIGHT_CLICK)
        assertThat(m[Gesture.DOUBLE_TAP_HOLD]).isEqualTo(GestureAction.None)
        assertThat(m[Gesture.LONG_PRESS]).isEqualTo(GestureAction.Drag)
        assertThat(m[Gesture.TWO_FINGER_SWIPE]).isEqualTo(GestureAction.Scroll)
        assertThat(TouchMap.fromJson("not json", TouchMode.TRACKPAD)).isEqualTo(TouchMap.defaults(TouchMode.TRACKPAD))
    }

    @Test
    fun theMappingTableRoundTripsAsJson() {
        val m = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, GestureAction.RIGHT_CLICK)
        assertThat(TouchMap.fromJson(m.toJson(), TouchMode.TRACKPAD)).isEqualTo(m)
        val radial = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.LONG_PRESS, GestureAction.RadialMenu)
        assertThat(TouchMap.fromJson(radial.toJson(), TouchMode.DIRECT)).isEqualTo(radial)
    }

    @Test
    fun theTrackpadLongPressOpensTheRadialMenuByDefaultAndTheDirectOneDrags() {
        assertThat(TouchMap.defaults(TouchMode.TRACKPAD)[Gesture.LONG_PRESS]).isEqualTo(GestureAction.RadialMenu)
        assertThat(TouchMap.defaults(TouchMode.DIRECT)[Gesture.LONG_PRESS]).isEqualTo(GestureAction.Drag)
    }

    @Test
    fun aShortcutRowTakesEveryRadialItemAndCustomShortcutsButAFixedRowDoesNot() {
        val m = TouchMap.fromJson(
            """{"fourFingerPinch":"copy","threeFingerTap":"keyboard","twoFingerTap":"fillScreen","doubleTapHold":"copy","tap":"paste","fourFingerSwipeUp":"notificationCenter",""" +
                """"twoFingerLongPress":{"code":11,"meta":${ModKey.SHIFT.meta or ModKey.CMD.meta},"name":"Area shot","look":"text"},"longPress":{"code":11,"meta":0}}""",
            TouchMode.TRACKPAD,
        )
        val d = TouchMap.defaults(TouchMode.TRACKPAD)
        assertThat(m[Gesture.FOUR_FINGER_PINCH]).isEqualTo(item(RadialItem.COPY))
        assertThat(m[Gesture.THREE_FINGER_TAP]).isEqualTo(item(RadialItem.KEYBOARD))
        assertThat(m[Gesture.TWO_FINGER_TAP]).isEqualTo(GestureAction.FILL_SCREEN)
        assertThat(m[Gesture.TWO_FINGER_LONG_PRESS]).isEqualTo(GestureAction.Run(areaShot))
        assertThat(m[Gesture.LONG_PRESS]).isEqualTo(GestureAction.Run(RadialEntry.Custom(KeyEvent.KEYCODE_4, 0, null, RadialLook.Text)))
        assertThat(m[Gesture.DOUBLE_TAP_HOLD]).isEqualTo(d[Gesture.DOUBLE_TAP_HOLD])
        assertThat(m[Gesture.TAP]).isEqualTo(d[Gesture.TAP])
        assertThat(m[Gesture.FOUR_FINGER_SWIPE_UP]).isEqualTo(d[Gesture.FOUR_FINGER_SWIPE_UP])
    }

    @Test
    fun theNewRowsDefaultAsOnAMacTrackpadAndTheOldMacGesturesKeepTheirDefaults() {
        for (mode in TouchMode.entries) {
            val d = TouchMap.defaults(mode)
            assertThat(d[Gesture.FOUR_FINGER_SWIPE_UP]).isEqualTo(item(RadialItem.MISSION_CONTROL))
            assertThat(d[Gesture.FOUR_FINGER_SWIPE_DOWN]).isEqualTo(item(RadialItem.APP_EXPOSE))
            assertThat(d[Gesture.FOUR_FINGER_SWIPE_LEFT]).isEqualTo(item(RadialItem.SPACE_RIGHT))
            assertThat(d[Gesture.FOUR_FINGER_SWIPE_RIGHT]).isEqualTo(item(RadialItem.SPACE_LEFT))
            assertThat(d[Gesture.FOUR_FINGER_PINCH]).isEqualTo(item(RadialItem.APPS))
            assertThat(d[Gesture.FOUR_FINGER_SPREAD]).isEqualTo(item(RadialItem.SHOW_DESKTOP))
            assertThat(d[Gesture.THREE_FINGER_TAP]).isEqualTo(GestureAction.None)
            assertThat(d[Gesture.TWO_FINGER_LONG_PRESS]).isEqualTo(GestureAction.None)
        }
    }

    @Test
    fun aCustomShortcutOnAGestureIsLoggedWithoutItsNameOrKeys() {
        val s = Settings(mapTrackpad = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.TWO_FINGER_LONG_PRESS, GestureAction.Run(areaShot)))
        val line = s.describe()
        assertThat(line).contains(""""twoFingerLongPress":"custom"""")
        assertThat(line).doesNotContain("Area shot")
        assertThat(s.mapTrackpad.toJson()).contains("Area shot")
    }

    @Test
    fun fillScreenIsStoredOnEveryShortcutRow() {
        val rows = Gesture.entries.filter { it.catalog }
        val direct = rows.fold(TouchMap.defaults(TouchMode.DIRECT)) { m, g -> m.with(g, GestureAction.FILL_SCREEN) }
        val trackpad = rows.fold(TouchMap.defaults(TouchMode.TRACKPAD)) { m, g -> m.with(g, GestureAction.FILL_SCREEN) }
        prefs.settings = Settings(mapDirect = direct, mapTrackpad = trackpad)
        val s = Prefs(RuntimeEnvironment.getApplication()).settings
        assertThat(s.mapDirect).isEqualTo(direct)
        assertThat(s.mapTrackpad).isEqualTo(trackpad)
        assertThat(raw().getString(SettingKeys.MAP_TRACKPAD, null)).contains(""""threeFingerTap":"fillScreen"""")
    }

    @Test
    fun theHookSetsShortcutRowsAndIgnoresTheOldMacRows() {
        val spec = "touch.map.trackpad.fourFingerPinch=copy,touch.map.direct.threeFingerTap=fillScreen,touch.map.trackpad.doubleTapHold=zoom," +
            "touch.map.trackpad.tap=copy,touch.mac.tap=fillScreen,touch.map.direct.fourFingerSwipeUp=none"
        val p = SettingsHook.parse(spec)
        assertThat(p.rows).containsExactly(
            Triple(TouchMode.TRACKPAD, Gesture.FOUR_FINGER_PINCH, item(RadialItem.COPY)),
            Triple(TouchMode.DIRECT, Gesture.THREE_FINGER_TAP, GestureAction.FILL_SCREEN),
            Triple(TouchMode.TRACKPAD, Gesture.DOUBLE_TAP_HOLD, GestureAction.Zoom),
            Triple(TouchMode.DIRECT, Gesture.FOUR_FINGER_SWIPE_UP, GestureAction.None),
        ).inOrder()
        assertThat(p.ignored).containsExactly("touch.map.trackpad.tap", "touch.mac.tap").inOrder()
        SettingsHook.apply(prefs, spec)
        val s = prefs.settings
        assertThat(s.mapTrackpad[Gesture.FOUR_FINGER_PINCH]).isEqualTo(item(RadialItem.COPY))
        assertThat(s.mapDirect[Gesture.THREE_FINGER_TAP]).isEqualTo(GestureAction.FILL_SCREEN)
        assertThat(s.mapDirect[Gesture.FOUR_FINGER_SWIPE_UP]).isEqualTo(GestureAction.None)
    }

    @Test
    fun migratingForgetsTheStoredTrackpadMapOnceAndKeepsTheRest() {
        val trackpad = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, GestureAction.RIGHT_CLICK)
        val direct = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.TWO_FINGER_TAP, GestureAction.Click)
        raw().edit().putString(SettingKeys.MAP_TRACKPAD, trackpad.toJson()).putString(SettingKeys.MAP_DIRECT, direct.toJson())
            .putString(MAC_KEY, """{"tap":"mute"}""").apply()
        prefs.migrate()
        val s = prefs.settings
        assertThat(s.mapTrackpad).isEqualTo(TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.THREE_FINGER_TAP, item(RadialItem.MUTE)))
        assertThat(s.mapDirect).isEqualTo(direct.with(Gesture.THREE_FINGER_TAP, item(RadialItem.MUTE)))
        assertThat(raw().getInt("settingsVersion", 0)).isEqualTo(3)
    }

    @Test
    fun aVersionTwoMacGestureTableMovesIntoBothMapsAndIsRemoved() {
        val direct = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.TWO_FINGER_TAP, GestureAction.Click)
        raw().edit().putInt("settingsVersion", 2).putString(SettingKeys.MAP_DIRECT, direct.toJson())
            .putString(MAC_KEY, """{"swipeUp":"screenshot","swipeDown":"none","swipeLeft":"spaceRight","swipeRight":"fillScreen","tap":"lockScreen"}""").apply()
        prefs.migrate()
        val moved = mapOf(
            Gesture.FOUR_FINGER_SWIPE_UP to item(RadialItem.SCREENSHOT),
            Gesture.FOUR_FINGER_SWIPE_DOWN to GestureAction.None,
            Gesture.FOUR_FINGER_SWIPE_LEFT to item(RadialItem.SPACE_RIGHT),
            Gesture.FOUR_FINGER_SWIPE_RIGHT to GestureAction.FILL_SCREEN,
            Gesture.THREE_FINGER_TAP to item(RadialItem.LOCK_SCREEN),
        )
        val s = prefs.settings
        assertThat(s.mapDirect).isEqualTo(moved.entries.fold(direct) { m, (g, a) -> m.with(g, a) })
        assertThat(s.mapTrackpad).isEqualTo(moved.entries.fold(TouchMap.defaults(TouchMode.TRACKPAD)) { m, (g, a) -> m.with(g, a) })
        assertThat(raw().contains(MAC_KEY)).isFalse()
        assertThat(raw().getInt("settingsVersion", 0)).isEqualTo(3)
    }

    @Test
    fun aVersionTwoAppWithoutMacGesturesKeepsItsMapsAsTheyAre() {
        val trackpad = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.TAP, GestureAction.RIGHT_CLICK).toJson()
        raw().edit().putInt("settingsVersion", 2).putString(SettingKeys.MAP_TRACKPAD, trackpad).apply()
        prefs.migrate()
        assertThat(raw().getString(SettingKeys.MAP_TRACKPAD, null)).isEqualTo(trackpad)
        assertThat(raw().contains(SettingKeys.MAP_DIRECT)).isFalse()
        assertThat(raw().getInt("settingsVersion", 0)).isEqualTo(3)
    }

    @Test
    fun anUnreadableMacGestureTableIsDroppedAndTheMapsStay() {
        val direct = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.TWO_FINGER_TAP, GestureAction.Click)
        raw().edit().putInt("settingsVersion", 2).putString(SettingKeys.MAP_DIRECT, direct.toJson()).putString(MAC_KEY, "not json").apply()
        prefs.migrate()
        assertThat(prefs.settings.mapDirect).isEqualTo(direct)
        assertThat(raw().contains(MAC_KEY)).isFalse()
    }

    @Test
    fun aVersionOneTrackpadDragOnLongPressBecomesTheRadialMenuAndKeepsTheOtherRows() {
        val stored = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, GestureAction.Drag).with(Gesture.TWO_FINGER_TAP, GestureAction.Click)
        raw().edit().putInt("settingsVersion", 1).putString(SettingKeys.MAP_TRACKPAD, stored.toJson()).apply()
        prefs.migrate()
        val m = prefs.settings.mapTrackpad
        assertThat(m[Gesture.LONG_PRESS]).isEqualTo(GestureAction.RadialMenu)
        assertThat(m[Gesture.TWO_FINGER_TAP]).isEqualTo(GestureAction.Click)
        assertThat(raw().getInt("settingsVersion", 0)).isEqualTo(3)
    }

    @Test
    fun aVersionOneTrackpadLongPressThatIsNoDragStaysOnMigration() {
        val stored = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, GestureAction.RIGHT_CLICK)
        raw().edit().putInt("settingsVersion", 1).putString(SettingKeys.MAP_TRACKPAD, stored.toJson()).apply()
        prefs.migrate()
        assertThat(prefs.settings.mapTrackpad).isEqualTo(stored)
    }

    @Test
    fun aDragChosenAfterTheMigrationStaysOnTheNextStart() {
        prefs.migrate()
        val chosen = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, GestureAction.Drag)
        prefs.settings = Settings.defaults.copy(mapTrackpad = chosen)
        prefs.migrate()
        assertThat(Prefs(RuntimeEnvironment.getApplication()).settings.mapTrackpad).isEqualTo(chosen)
    }

    @Test
    fun theOldTrackpadDefaultsMigrateToTheNewOnes() {
        val old = """{"tap":"click","doubleTap":"tapTwice","longPress":"none","doubleTapHold":"dragStart","twoFingerTap":"rightClick","twoFingerSwipe":"scroll"}"""
        val direct = TouchMap.defaults(TouchMode.DIRECT).with(Gesture.TWO_FINGER_TAP, GestureAction.Click)
        raw().edit().putString(SettingKeys.MAP_TRACKPAD, old).putString(SettingKeys.MAP_DIRECT, direct.toJson())
            .putString(MAC_KEY, """{"tap":"mute"}""").apply()
        prefs.migrate()
        val s = prefs.settings
        val m = s.mapTrackpad
        assertThat(m[Gesture.LONG_PRESS]).isEqualTo(GestureAction.RadialMenu)
        assertThat(m[Gesture.DOUBLE_TAP_HOLD]).isEqualTo(GestureAction.None)
        assertThat(m[Gesture.THREE_FINGER_DRAG]).isEqualTo(GestureAction.Drag)
        assertThat(m[Gesture.THREE_FINGER_TAP]).isEqualTo(item(RadialItem.MUTE))
        assertThat(s.mapDirect).isEqualTo(direct.with(Gesture.THREE_FINGER_TAP, item(RadialItem.MUTE)))
    }

    @Test
    fun migratingAgainLeavesAChosenTrackpadMapAlone() {
        prefs.migrate()
        val chosen = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, GestureAction.RIGHT_CLICK)
        prefs.settings = Settings.defaults.copy(mapTrackpad = chosen)
        prefs.migrate()
        assertThat(Prefs(RuntimeEnvironment.getApplication()).settings.mapTrackpad).isEqualTo(chosen)
    }

    @Test
    fun aStoredDoubleTapRowIsIgnoredAndATapClicksAsTheFingerLifts() {
        val stored = """{"tap":"click","doubleTap":"none","longPress":"dragStart","doubleTapHold":"none","threeFingerDrag":"dragStart","twoFingerTap":"rightClick","twoFingerSwipe":"scroll"}"""
        raw().edit().putString(SettingKeys.MAP_TRACKPAD, stored).apply()
        val r = GestureRecognizer(TouchMode.TRACKPAD, prefs.settings.mapTrackpad, SpeedStep.default, ScrollSetting(), slop = 10f, doubleTapSlop = 100f, density = 1f)
        r.setViewSize(1000, 2000)
        r.onStreamStarted()
        r.onTouch(GestureRecognizer.Sample(GestureRecognizer.Kind.DOWN, 0, listOf(GestureRecognizer.Finger(0, 500f, 1000f))))
        val up = r.onTouch(GestureRecognizer.Sample(GestureRecognizer.Kind.UP, 60, listOf(GestureRecognizer.Finger(0, 500f, 1000f))))
        assertThat(up.inputs.filterIsInstance<InputEvent.Pointer>().map { it.action }).containsExactly(PointerAction.Down, PointerAction.Up).inOrder()
    }

    @Test
    fun aVersionOfTheWrongTypeCountsAsNoneAndMigrates() {
        val trackpad = TouchMap.defaults(TouchMode.TRACKPAD).with(Gesture.TAP, GestureAction.RIGHT_CLICK)
        raw().edit().putString("settingsVersion", "1").putString(SettingKeys.MAP_TRACKPAD, trackpad.toJson()).apply()
        prefs.migrate()
        assertThat(prefs.settings.mapTrackpad).isEqualTo(TouchMap.defaults(TouchMode.TRACKPAD))
    }

    @Test
    fun scrollSpeedAndDirectionApplyToTheirOwnInputOnly() {
        val s = Settings(touchScroll = ScrollSetting(SpeedStep(7), reverse = true))
        assertThat(s.touchScroll.apply(10f)).isEqualTo(-20f)
        assertThat(s.wheelScroll.apply(10f)).isEqualTo(10f)
        assertThat(ScrollSetting(SpeedStep(3)).apply(10f)).isEqualTo(5f)
    }

    @Test
    fun theHookWritesKnownKeysAndRowsAndSkipsTheRest() {
        val p = SettingsHook.parse("touch.mode=trackpad, video.fps=30,touch.map.direct.twoFingerTap=click,video.scale=2,bogus=1,touch.map.direct.doubleTapHold=rightClick,keyboard.incognito=true,showStats=false")
        assertThat(p.writes).containsExactly("touch.mode", "trackpad", "video.fps", "30", "keyboard.incognito", "true", "showStats", "false")
        assertThat(p.rows).containsExactly(Triple(TouchMode.DIRECT, Gesture.TWO_FINGER_TAP, GestureAction.Click))
        assertThat(p.ignored).containsExactly("video.scale", "bogus", "touch.map.direct.doubleTapHold")
        SettingsHook.apply(prefs, "touch.mode=trackpad,video.fps=30,touch.map.direct.twoFingerTap=click,keyboard.incognito=true,showStats=false")
        val s = prefs.settings
        assertThat(s.touchMode).isEqualTo(TouchMode.TRACKPAD)
        assertThat(s.fps).isEqualTo(30)
        assertThat(s.mapDirect[Gesture.TWO_FINGER_TAP]).isEqualTo(GestureAction.Click)
        assertThat(s.incognitoKeyboard).isTrue()
        assertThat(s.showStats).isFalse()
        assertThat(prefs.showStats).isFalse()
    }

    @Test
    fun theMenuCornerIsSetByTheHookAndSurvivesAReset() {
        assertThat(prefs.menuCorner).isEqualTo(MenuCorner.BOTTOM_END)
        SettingsHook.apply(prefs, "ui.menuCorner=topStart")
        assertThat(prefs.menuCorner).isEqualTo(MenuCorner.TOP_START)
        prefs.settings = Settings.defaults
        assertThat(Prefs(RuntimeEnvironment.getApplication()).menuCorner).isEqualTo(MenuCorner.TOP_START)
        assertThat(SettingsHook.parse("ui.menuCorner=middle").ignored).containsExactly("ui.menuCorner")
    }

    // MARK: radial menu

    private val shot = RadialEntry.Custom(KeyEvent.KEYCODE_4, ModKey.SHIFT.meta or ModKey.CMD.meta, null, RadialLook.Text)

    private fun readRadial(json: String): List<RadialEntry?> {
        raw().edit().putString(SettingKeys.RADIAL_MENU, json).apply()
        return prefs.settings.radialMenu
    }

    @Test
    fun theRadialMenuSurvivesTheRoundTripThroughPrefs() {
        val menu = listOf(
            RadialEntry.Builtin(RadialItem.UNDO),
            shot,
            null,
            null,
            shot.copy(name = "Area shot", look = RadialLook.Icon(RadialIcon.STAR)),
            null,
            null,
            RadialEntry.Builtin(RadialItem.VOLUME_UP),
        )
        prefs.settings = Settings(radialMenu = menu)
        assertThat(Prefs(RuntimeEnvironment.getApplication()).settings.radialMenu).isEqualTo(menu)
        assertThat(raw().getString(SettingKeys.RADIAL_MENU, null)).isEqualTo(
            """[{"item":"undo"},{"code":11,"meta":65537,"look":"text"},null,null,{"code":11,"meta":65537,"name":"Area shot","look":"icon:star"},null,null,{"item":"volumeUp"}]""",
        )
    }

    @Test
    fun aRadialMenuThatIsNoJsonArrayReadsAsTheDefault() {
        assertThat(readRadial("not json")).isEqualTo(RadialEntry.defaults)
        assertThat(readRadial("""{"item":"copy"}""")).isEqualTo(RadialEntry.defaults)
    }

    @Test
    fun aMenuStoredBeforeCellsCouldBeChosenOpensAsItDidThen() {
        val copy = RadialEntry.Builtin(RadialItem.COPY)
        val paste = RadialEntry.Builtin(RadialItem.PASTE)
        val keyboard = RadialEntry.Builtin(RadialItem.KEYBOARD)
        assertThat(readRadial("""[{"item":"copy"},{"item":"paste"},{"item":"keyboard"}]""")).isEqualTo(listOf(copy, null, null, paste, null, keyboard, null, null))
    }

    @Test
    fun anUnreadableEntryInAnEightCellMenuLeavesItsCellEmpty() {
        val menu = readRadial("""[{"item":"undo"},7,{"item":"copy","code":11},{},{"item":"warp"},{"code":160,"meta":0},{"item":"undo"},{"code":11,"meta":65537}]""")
        assertThat(menu).isEqualTo(listOf(RadialEntry.Builtin(RadialItem.UNDO), null, null, null, null, null, null, shot))
    }

    @Test
    fun unreadableEntriesOfAShortMenuAreSkippedBeforeItSpreads() {
        val menu = readRadial(
            """[{"item":"undo"},7,{"item":"copy","code":11},{},{"item":"warp"},{"code":160,"meta":0},{"code":999},""" +
                """{"code":11,"meta":2097152},{"item":"undo"},{"code":11,"meta":65537},{"code":46}]""",
        )
        assertThat(menu).isEqualTo(
            RadialEntry.spread(listOf(RadialEntry.Builtin(RadialItem.UNDO), shot, RadialEntry.Custom(KeyEvent.KEYCODE_R, 0, null, RadialLook.Text))),
        )
    }

    @Test
    fun aRadialMenuOutsideTwoToEightReadsAsTheDefault() {
        assertThat(readRadial("""[{"item":"undo"}]""")).isEqualTo(RadialEntry.defaults)
        val nine = (0 until 9).joinToString(",", "[", "]") { """{"code":${KeyEvent.KEYCODE_A + it}}""" }
        assertThat(readRadial(nine)).isEqualTo(RadialEntry.defaults)
        assertThat(readRadial("""[{"item":"undo"},{"item":"undo"}]""")).isEqualTo(RadialEntry.defaults)
        assertThat(readRadial("""[{"item":"undo"},null,null,null,null,null,null,null]""")).isEqualTo(RadialEntry.defaults)
    }

    @Test
    fun aCustomNameIsTrimmedAndCutAndAnUnknownLookIsText() {
        val menu = readRadial(
            """[{"code":11,"meta":65537,"name":"   "},{"code":11,"name":"  ${"x".repeat(25)} "},""" +
                """{"code":11,"look":"neon"},{"code":11,"look":"icon:unicorn"}]""",
        )
        assertThat(menu.filterNotNull()).containsExactly(
            shot,
            RadialEntry.Custom(KeyEvent.KEYCODE_4, 0, "x".repeat(24), RadialLook.Text),
            RadialEntry.Custom(KeyEvent.KEYCODE_4, 0, null, RadialLook.Text),
            RadialEntry.Custom(KeyEvent.KEYCODE_4, 0, null, RadialLook.Text),
        ).inOrder()
    }

    @Test
    fun aNameCutAtTwentyFourEndsWithoutASpace() {
        assertThat(RadialMenuJson.name("${"a".repeat(23)} b")).isEqualTo("a".repeat(23))
    }

    @Test
    fun aNameCountsCharactersAsAPersonSeesThemAndNeverCutsOneInHalf() {
        val family = "👨‍👩‍👧"
        val thumb = "👍🏽"
        assertThat(RadialMenuJson.length(family)).isEqualTo(1)
        assertThat(RadialMenuJson.length(thumb)).isEqualTo(1)
        assertThat(RadialMenuJson.length("é")).isEqualTo(1)
        assertThat(RadialMenuJson.clip("😀".repeat(25))).isEqualTo("😀".repeat(24))
        assertThat(RadialMenuJson.clip("${"a".repeat(23)}${family}b")).isEqualTo("${"a".repeat(23)}$family")
        assertThat(RadialMenuJson.clip("${"a".repeat(23)}$thumb")).isEqualTo("${"a".repeat(23)}$thumb")
        assertThat(RadialMenuJson.name(" ${"😀".repeat(30)} ")).isEqualTo("😀".repeat(24))
    }

    @Test
    fun theSettingsLogShowsCustomEntriesWithoutTheirNamesOrKeys() {
        val s = Settings(radialMenu = RadialEntry.spread(listOf(RadialEntry.Builtin(RadialItem.COPY), shot.copy(name = "Area shot"))))
        assertThat(s.describe()).contains(" radial.menu=copy++++custom+++ ")
        assertThat(s.describe()).doesNotContain("Area shot")
        assertThat(SettingKeys.write(s)[SettingKeys.RADIAL_MENU]).contains("Area shot")
    }

    @Test
    fun fillScreenOnTheRadialMenuIsStoredAndSetByTheHook() {
        val menu = RadialEntry.spread(listOf(RadialEntry.Builtin(RadialItem.MISSION_CONTROL), RadialEntry.Builtin(RadialItem.FILL_SCREEN)))
        prefs.settings = Settings(radialMenu = menu)
        assertThat(Prefs(RuntimeEnvironment.getApplication()).settings.radialMenu).isEqualTo(menu)
        SettingsHook.apply(prefs, "radial.menu=missionControl+fillScreen")
        assertThat(prefs.settings.radialMenu).isEqualTo(menu)
    }

    @Test
    fun theHookSetsTheRadialMenuOfBuiltinItems() {
        val p = SettingsHook.parse("radial.menu=copy+paste+keyboard")
        assertThat(p.radialMenu).isEqualTo(listOf(RadialItem.COPY, null, null, RadialItem.PASTE, null, RadialItem.KEYBOARD, null, null))
        SettingsHook.apply(prefs, "radial.menu=copy+paste+keyboard")
        assertThat(prefs.settings.radialMenu).isEqualTo(p.radialMenu!!.map { it?.let(RadialEntry::Builtin) })
        assertThat(SettingsHook.parse("radial.menu=++copy+++++paste").radialMenu).isEqualTo(listOf(null, null, RadialItem.COPY, null, null, null, null, RadialItem.PASTE))
        for (bad in listOf("copy", "copy+paste+keyboard+undo+redo+cut+save+find+mute", "copy+warp", "copy+copy", "copy+++++++", "copy+paste++warp++++", "copy++paste")) {
            val q = SettingsHook.parse("radial.menu=$bad")
            assertThat(q.radialMenu).isNull()
            assertThat(q.ignored).containsExactly("radial.menu")
        }
    }

    @Test
    fun theDisplayConfigCarriesTheVideoSettings() {
        val d = displayConfig(2448, 1848, 420, 120, "extend", Settings(scale = 0.5, fps = 30, uiScale = 1.5))
        assertThat(d).isEqualTo(DisplayConfig(2448, 1848, 420, 120, "extend", 0.5, 30, 1.5))
    }
}

/** The stored key of the Mac gestures before version 3 of the settings. */
private const val MAC_KEY = "touch.mac"

/** The rows after the six that were always there, as both modes store them by default. */
private const val SHORTCUT_DEFAULTS = """"twoFingerLongPress":"none","threeFingerTap":"none","fourFingerSwipeUp":"missionControl",""" +
    """"fourFingerSwipeDown":"appExpose","fourFingerSwipeLeft":"spaceRight","fourFingerSwipeRight":"spaceLeft","fourFingerPinch":"apps","fourFingerSpread":"showDesktop""""
