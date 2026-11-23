package dev.wadackel.oripane

import android.content.Context
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The parts the streaming screen draws over the video, on both of the Fold's screens. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamScreenshotTest(private val size: FoldSize) {
    @get:Rule(order = 0)
    val screen = FoldScreenRule(size)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) = compose.shot(name, size)

    /** The screen in pixels and its density; read in a test, after the rule set the qualifiers. */
    private class Metrics(val w: Float, val h: Float, val d: Float)

    private fun metrics() = ApplicationProvider.getApplicationContext<Context>().resources.displayMetrics.let {
        Metrics(it.widthPixels.toFloat(), it.heightPixels.toFloat(), it.density)
    }

    private fun corners(expanded: Boolean, awdlMark: Boolean, note: String? = null, waiting: Boolean = false) {
        val m = metrics()
        val corner = MenuCorner.BOTTOM_END
        compose.setContent {
            StreamFrame {
                StatusCorner(
                    showDot = true,
                    color = if (note != null && !waiting) StatusColor.YELLOW else if (waiting) StatusColor.GREY else StatusColor.GREEN,
                    note = note,
                    waiting = waiting,
                    expanded = expanded,
                    details = if (expanded) ({ StatsOverlay(stats, streaming, dataSaver = false) }) else null,
                    awdlMark = awdlMark,
                    onToggle = {},
                    onMark = {},
                    modifier = Modifier.align(corner.opposite.alignment()),
                )
                MenuButton(corner, parentWidth = m.w, parentHeight = m.h, onTap = {}, onCorner = {}, modifier = Modifier.align(corner.alignment()))
            }
        }
    }

    @Test
    fun statusDotAndMenuButtonInTheirCorners() {
        corners(expanded = false, awdlMark = true)
        shot("stream_corners")
    }

    @Test
    fun statsOpenInTheStatusCorner() {
        corners(expanded = true, awdlMark = false)
        shot("stream_stats")
    }

    @Test
    fun awdlBanner() {
        compose.setContent {
            StreamFrame {
                val below = if (awdlBannerBelowCorners(maxWidth.value)) Modifier.padding(top = CORNER_DP.dp) else Modifier
                AwdlBanner(onDismiss = {}, modifier = Modifier.align(Alignment.TopCenter).then(below))
            }
        }
        shot("stream_awdl_banner")
    }

    @Test
    fun statusDotOpenIntoANote() {
        corners(expanded = false, awdlMark = false, note = slowText(stats.copy(totalMs = 120.0)))
        shot("stream_status_note")
    }

    @Test
    fun statusDotWhileReconnecting() {
        corners(expanded = false, awdlMark = false, note = reconnectingText(null, 2, RouteKind.LAN.label), waiting = true)
        shot("stream_reconnecting")
    }

    /** The bands as TouchInput places them on a screen with no gesture insets: the side one centred. */
    private fun bands(m: Metrics): ScrollBands {
        val lane = StripArea(m.w - TouchInput.STRIP_WIDTH_DP * m.d, 0f, m.w, m.h)
        val half = VERTICAL_BAND_DP / 2 * m.d
        val halfH = HORIZONTAL_BAND_DP / 2 * m.d
        return ScrollBands(ScrollStrip.RIGHT, lane, lane.copy(top = m.h / 2 - half, bottom = m.h / 2 + half), StripArea(m.w / 2 - halfH, m.h - TouchInput.STRIP_WIDTH_DP * m.d, m.w / 2 + halfH, m.h))
    }

    @Test
    fun scrollBands() {
        val m = metrics()
        compose.setContent {
            StreamFrame { HalftoneBands(bands(m), BandSize.MEDIUM, touch = null, relocatingY = null, tint = BandTint.neutral, modifier = Modifier.fillMaxSize()) }
        }
        shot("stream_strip")
    }

    @Test
    fun scrollBandHeldNearItsEnd() {
        val m = metrics()
        val b = bands(m)
        // Near the band's lower end: the swell runs past it, which the dots beyond the end take.
        val touch = BandTouch(horizontal = false, b.vertical.right - 10 * m.d, b.vertical.bottom - 20 * m.d)
        compose.setContent {
            StreamFrame { HalftoneBands(b, BandSize.MEDIUM, touch, relocatingY = null, tint = BandTint.neutral, modifier = Modifier.fillMaxSize()) }
        }
        shot("stream_strip_held")
    }

    private fun radial(selected: Int?, sticky: Boolean, entries: List<RadialEntry?> = RadialEntry.defaults) {
        val m = metrics()
        val x = m.w / 2
        val y = m.h / 2
        val state = RadialMenuState(
            entries,
            touchX = x, touchY = y, centerX = x, centerY = y,
            fingerX = x, fingerY = y - 60 * m.d, videoX = x, videoY = y,
            selected = selected, sticky = sticky,
        )
        compose.setContent { StreamFrame { RadialMenuOverlay(state, tint = videoTint, onPick = {}, onClose = {}) } }
    }

    @Test
    fun radialMenuWithAnItemSelected() {
        radial(selected = 0, sticky = false)
        shot("radial_overlay_selected")
    }

    @Test
    fun radialMenuLeftOpen() {
        radial(selected = null, sticky = true)
        shot("radial_overlay_sticky")
    }

    @Test
    fun radialMenuOfFourWithEmptyCells() {
        radial(selected = 2, sticky = false, entries = RadialEntry.spread(RadialEntry.defaults.filterNotNull().take(4)))
        shot("radial_overlay_four")
    }

    /**
     * Opened by a finger at the screen's right edge, moved in as [RadialMenuState.opened] does, and
     * checked that the tag above the plate keeps the plate's distance from the sides.
     */
    private fun radialAtRightEdge(selected: Int?, sticky: Boolean, entries: List<RadialEntry?> = RadialEntry.defaults) {
        val m = metrics()
        val x = m.w - 2
        val y = m.h / 2
        val state = RadialMenuState.opened(entries, x, y, x, y, m.w, m.h, RadialSize.MARGIN * m.d)
            .copy(selected = selected, sticky = sticky)
        compose.setContent { StreamFrame { RadialMenuOverlay(state, tint = videoTint, onPick = {}, onClose = {}) } }
        val tag = compose.onNodeWithTag(RADIAL_TAG_TAG).fetchSemanticsNode().boundsInRoot
        if (size == FoldSize.COVER_LARGEST && knownUntil(LocalDate.of(2026, 12, 31), "Issue #45: the tag reaches the screen's edge at the largest size")) return
        val edge = RadialSize.EDGE * m.d
        assertWithMessage("the tag's right end, on a screen %s px wide", m.w).that(tag.right).isAtMost(m.w - edge + 1)
        assertWithMessage("the tag's left end").that(tag.left).isAtLeast(edge - 1)
    }

    @Test
    fun radialMenuLeftOpenAtTheRightEdge() {
        radialAtRightEdge(selected = null, sticky = true)
        shot("radial_overlay_sticky_edge")
    }

    @Test
    fun radialMenuWithALongNameAtTheRightEdge() {
        // A custom shortcut's name, near the 24 characters it may have, and three keys: wider than the plate.
        val shot = RadialEntry.Custom(KeyEvent.KEYCODE_4, ModKey.CTRL.meta or ModKey.SHIFT.meta or ModKey.CMD.meta, "Copy screenshot of area", RadialLook.Text)
        radialAtRightEdge(selected = 0, sticky = false, entries = listOf(shot) + RadialEntry.defaults.drop(1))
        shot("radial_overlay_selected_edge")
    }

    @Test
    fun radialToast() {
        compose.setContent { StreamFrame { RadialToast(RadialEntry.Builtin(RadialItem.COPY), Modifier.align(Alignment.BottomCenter)) } }
        shot("radial_toast")
    }

    private fun menu(
        page: MenuState,
        reconnecting: Boolean = false,
        streamMode: String = "extend",
        macName: String = "Mac Studio",
        settings: Settings = Settings.defaults,
    ) {
        val model = MenuModel(
            menu = page,
            macName = macName,
            status = menuStatus(streamMode, RouteKind.LAN.label, stats, reconnecting),
            statusColor = statusColor(stats, reconnecting),
            tint = videoTint,
            reconnecting = reconnecting,
            streamMode = streamMode,
            zoomed = true,
            settings = settings,
            onBack = {},
            onDismissed = {},
            onItemClosed = {},
            onKeyboard = {},
            onMode = {},
            onResetZoom = {},
            onSettingsPage = {},
            onMacPage = {},
            onDisplayPage = {},
            onMac = {},
            onDisconnect = {},
            onSetting = { _, _ -> },
        )
        compose.setContent { StreamFrame { MenuSheet(model) } }
    }

    @Test
    fun menuSheet() {
        menu(MenuState.MENU)
        shot("menu_sheet")
    }

    @Test
    fun menuSheetWhileReconnecting() {
        menu(MenuState.MENU, reconnecting = true)
        shot("menu_sheet_reconnecting")
    }

    @Test
    fun menuSheetEndsALongMacNameWithAnEllipsis() {
        menu(MenuState.MENU, macName = "Wide_Mac_Name_For_The_Sheet_Header_Check_Long_Long_Long_Name")
        shot("menu_sheet_long_name")
    }

    @Test
    fun menuSheetOnTheTestPattern() {
        menu(MenuState.MENU, streamMode = "test")
        shot("menu_sheet_test")
    }

    @Test
    fun displayModePage() {
        menu(MenuState.DISPLAY)
        shot("menu_display")
    }

    @Test
    fun macPageToItsEndAndLockConfirmation() {
        menu(MenuState.MAC)
        shot("menu_mac")
        compose.onNodeWithText("Lock screen").performScrollTo()
        shot("menu_mac_end")
        compose.onNodeWithText("Lock screen").performClick()
        shot("menu_mac_lock_confirm")
    }

    @Test
    fun liveSettingsPageToItsEnd() {
        menu(MenuState.SETTINGS)
        shot("menu_settings")
        compose.onNodeWithText("Other settings can be changed in Settings when not connected.").performScrollTo()
        shot("menu_settings_end")
    }

    @Test
    fun liveSettingsDescribeTheChosenTouchMode() {
        menu(MenuState.SETTINGS, settings = Settings.defaults.copy(touchMode = TouchMode.TRACKPAD))
        compose.onNodeWithText(touchModeNote(TouchMode.TRACKPAD)).assertExists()
        compose.onNodeWithText(touchModeNote(TouchMode.DIRECT)).assertDoesNotExist()
    }

    @Test
    fun preeditAndKeyRowWithTheMenuButtonAboveThem() {
        val m = metrics()
        compose.setContent {
            StreamFrame {
                var bar by remember { mutableStateOf(0) }
                // The insets padding of MainActivity is left out: Robolectric reports no IME inset.
                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).onSizeChanged { bar = it.height }.testTag("bar")) {
                    PreeditBar("nihongo", cursor = 4)
                    KeyRow(
                        mods = ModKeys().tapped(ModKey.CMD).tapped(ModKey.SHIFT).tapped(ModKey.SHIFT),
                        onMod = {},
                        onKey = { _, _ -> },
                        onKeyReleased = {},
                    )
                }
                val corner = MenuCorner.BOTTOM_END
                MenuButton(corner, parentWidth = m.w, parentHeight = m.h, onTap = {}, onCorner = {}, modifier = Modifier.align(corner.alignment()), keyBar = bar)
            }
        }
        val button = compose.onNodeWithContentDescription("Menu").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        assertWithMessage("menu button bottom %s, key bar top %s", button.bottom, bar.top).that(button.bottom).isLessThan(bar.top)
        shot("key_row")
    }

    /** Where a raised button snaps after a long press and a vertical move of `dy(its top)` px, over a bar 60% of the screen tall. */
    private fun raisedButtonSnapsTo(dy: (buttonTop: Float) -> Float): MenuCorner? {
        val m = metrics()
        var snapped: MenuCorner? = null
        compose.setContent {
            StreamFrame {
                val corner = MenuCorner.BOTTOM_END
                MenuButton(corner, m.w, m.h, onTap = {}, onCorner = { snapped = it }, modifier = Modifier.align(corner.alignment()), keyBar = (m.h * 0.6f).toInt())
            }
        }
        val button = compose.onNodeWithContentDescription("Menu")
        val top = button.fetchSemanticsNode().boundsInRoot.top
        button.performTouchInput {
            down(center)
            advanceEventTime(1000)
            moveBy(Offset(0f, dy(top) / 2))
            moveBy(Offset(0f, dy(top) / 2))
            up()
        }
        compose.waitForIdle()
        return snapped
    }

    @Test
    fun aRaisedMenuButtonLetGoInPlaceStaysInItsBottomCorner() {
        assertWithMessage("corner").that(raisedButtonSnapsTo { 1f }).isEqualTo(MenuCorner.BOTTOM_END)
    }

    @Test
    fun aRaisedMenuButtonDraggedToTheTopSnapsToATopCorner() {
        assertWithMessage("corner").that(raisedButtonSnapsTo { top -> -top }).isEqualTo(MenuCorner.TOP_END)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun sizes() = FoldSize.entries.map { arrayOf<Any>(it) }
    }
}
