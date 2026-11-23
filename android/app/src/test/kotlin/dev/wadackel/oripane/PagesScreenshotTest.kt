package dev.wadackel.oripane

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pages and sheets shown while not connected, on both of the Fold's screens. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PagesScreenshotTest(private val size: FoldSize) {
    @get:Rule(order = 0)
    val screen = FoldScreenRule(size)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String) = compose.shot(name, size)

    @Composable
    private fun home(saved: List<SavedMac>, discovered: List<Discovered>, selected: String? = null, failure: HomeFailure? = null) {
        HomeScreen(
            saved = saved,
            discovered = discovered,
            selected = selected,
            mode = "extend",
            failure = failure,
            onSelect = {},
            onMode = {},
            onConnect = {},
            onDetail = {},
            onAdd = {},
            onSettings = {},
        )
    }

    @Test
    fun homeWithoutMacs() {
        compose.setContent { PageFrame { home(emptyList(), emptyList()) } }
        shot("home_empty")
    }

    @Test
    fun homeWithAFoundMac() {
        compose.setContent { PageFrame { home(emptyList(), listOf(iMac)) } }
        shot("home_nearby")
    }

    @Test
    fun homeWithOneSavedMac() {
        compose.setContent { PageFrame { home(listOf(mbp), emptyList()) } }
        shot("home_one")
    }

    @Test
    fun homeWithSavedAndFoundMacs() {
        compose.setContent { PageFrame { home(listOf(studio, mbp, mbp2), listOf(studioNearby, iMac)) } }
        shot("home_saved")
    }

    @Test
    fun homeWithAMacChosenThatIsNotAdded() {
        compose.setContent { PageFrame { home(listOf(studio, mbp, mbp2), listOf(studioNearby, iMac), selected = HomeEntry.Found(iMac).key) } }
        shot("home_not_added")
    }

    @Test
    fun homeAfterAMacCouldNotBeReached() {
        val failure = HomeFailure(HomeEntry.Saved(mbp2).key, FailReason.CONNECT_FAILED, "Couldn't reach MacBook Pro. Wake it, or check that Oripane is running on it.")
        compose.setContent { PageFrame { home(listOf(studio, mbp, mbp2), listOf(iMac), selected = HomeEntry.Saved(mbp2).key, failure = failure) } }
        shot("home_error")
    }

    @Test
    fun homeAfterAFoundMacDeclinedThePairing() {
        val failure = HomeFailure(HomeEntry.Found(iMac).key, FailReason.PAIR_DENIED, "The Mac declined the pairing.")
        compose.setContent { PageFrame { home(listOf(studio, mbp, mbp2), listOf(iMac), selected = HomeEntry.Found(iMac).key, failure = failure) } }
        shot("home_declined")
    }

    @Test
    fun addPageEmpty() {
        compose.setContent { PageFrame { AddScreen(initial = "", mode = "extend", error = null, onChange = {}, onConnect = { _, _ -> }, onBack = {}) } }
        shot("add_empty")
    }

    @Test
    fun addPageRefusesAPort() {
        compose.setContent { PageFrame { AddScreen(initial = "192.168.1.20:47800", mode = "extend", error = null, onChange = {}, onConnect = { _, _ -> }, onBack = {}) } }
        shot("add_port")
    }

    @Test
    fun detailPageAndItsRemoveConfirmation() {
        compose.setContent { PageFrame { DetailScreen(studio.copy(customName = "Desk"), onRename = {}, onDelete = {}, onBack = {}) } }
        shot("detail")
        compose.onNodeWithText("Remove from this phone").performClick()
        shot("detail_remove_confirm")
    }

    @Composable
    private fun settings(section: SettingsSection?) {
        SettingsScreen(Settings.defaults, section, onChange = {}, onReset = {}, onMap = {}, onRadialMenu = {}, onBack = {})
    }

    @Test
    fun settingsIndexAndResetConfirmation() {
        compose.setContent { PageFrame { settings(null) } }
        shot("settings")
        compose.onNodeWithText("Reset all settings").performClick()
        shot("settings_reset_confirm")
    }

    @Test
    fun settingsSectionPages() {
        var section by mutableStateOf(SettingsSection.VIDEO)
        // Keyed, because Settings takes its section only as it opens.
        compose.setContent { PageFrame { key(section) { settings(section) } } }
        SettingsSection.entries.forEach {
            section = it
            shot("settings_${it.key}")
        }
    }

    @Test
    fun aboutSheetsForALibraryAndAFont() {
        compose.setContent { PageFrame { settings(SettingsSection.ABOUT) } }
        compose.onNodeWithText("AndroidX Activity").performScrollTo().performClick()
        shot("settings_about_library_sheet")
        compose.onNodeWithText("activity-compose", substring = true).assertExists()
    }

    @Test
    fun aboutFontSheetShowsASample() {
        compose.setContent { PageFrame { settings(SettingsSection.ABOUT) } }
        compose.onNodeWithText("Inter Tight").performScrollTo().performClick()
        shot("settings_about_font_sheet")
    }

    @Test
    fun touchModeSheet() {
        compose.setContent { PageFrame { settings(SettingsSection.TOUCH) } }
        compose.onNodeWithText("Touch mode").performClick()
        shot("settings_touch_mode_sheet")
    }

    private fun map(mode: TouchMode, map: TouchMap = Settings.defaults.mapFor(mode)) {
        compose.setContent { PageFrame { MapScreen(mode, map, onChange = {}, onShortcut = { _, _ -> }, onBack = {}) } }
    }

    @Test
    fun directGesturesPageToItsEnd() {
        map(TouchMode.DIRECT)
        shot("map_direct")
        compose.onNodeWithText(Gesture.entries.last().label).performScrollTo()
        shot("map_direct_end")
    }

    @Test
    fun trackpadGesturesPageToItsEnd() {
        map(TouchMode.TRACKPAD)
        shot("map_trackpad")
        compose.onNodeWithText(Gesture.entries.last().label).performScrollTo()
        shot("map_trackpad_end")
    }

    @Test
    fun choiceSheetForAGestureOutsideTheCatalog() {
        map(TouchMode.TRACKPAD)
        compose.onNodeWithText("Tap").performClick()
        shot("map_choice_sheet")
    }

    @Test
    fun entrySheetForACatalogGestureToItsEnd() {
        map(TouchMode.TRACKPAD)
        compose.onNodeWithText("Long press").performClick()
        shot("map_entry_sheet")
        compose.onNodeWithText("Custom shortcut…").performScrollTo()
        shot("map_entry_sheet_end")
    }

    @Test
    fun entrySheetForAGestureMappedToACustomShortcut() {
        map(TouchMode.TRACKPAD, Settings.defaults.mapFor(TouchMode.TRACKPAD).with(Gesture.LONG_PRESS, GestureAction.Run(custom)))
        compose.onNodeWithText("Long press").performClick()
        compose.onNodeWithText("Edit shortcut…").performScrollTo()
        shot("map_entry_sheet_custom")
    }

    private fun radialMenu() {
        compose.setContent { PageFrame { RadialMenuScreen(RadialEntry.defaults, onChange = {}, onShortcut = { _, _ -> }, onBack = {}) } }
    }

    @Test
    fun radialMenuPageToItsEndAndRestoreConfirmation() {
        radialMenu()
        shot("radial_menu")
        compose.onNodeWithText("Restore defaults").performScrollTo()
        shot("radial_menu_end")
        compose.onNodeWithText("Restore defaults").performClick()
        shot("radial_menu_restore_confirm")
    }

    @Test
    fun radialMenuWithEmptyCells() {
        compose.setContent { PageFrame { RadialMenuScreen(threeItems, onChange = {}, onShortcut = { _, _ -> }, onBack = {}) } }
        shot("radial_menu_empty_cells")
    }

    @Test
    fun aDragOnThePlateMovesTheItemUnderTheFingerAndADragFromAnEmptyCellMovesNothing() {
        val saved = mutableListOf<List<RadialEntry?>>()
        compose.setContent { PageFrame { RadialMenuScreen(threeItems, onChange = { saved += it }, onShortcut = { _, _ -> }, onBack = {}) } }
        fun dragCells(from: Pair<Int, Int>, to: Pair<Int, Int>) = compose.onNodeWithTag(RADIAL_PLATE_TAG).performTouchInput {
            val pitch = RadialSize.PITCH.dp.toPx()
            val a = center + Offset(from.first * pitch, from.second * pitch)
            val b = center + Offset(to.first * pitch, to.second * pitch)
            down(a)
            (1..8).forEach { moveTo(a + (b - a) * (it / 8f)) }
            up()
        }
        dragCells(0 to -1, -1 to -1)
        assertThat(saved).containsExactly(radialSwap(threeItems, 0, 7))
        dragCells(-1 to 0, 1 to 0)
        assertThat(saved).hasSize(1)
    }

    @Test
    fun radialMenuItemSheet() {
        radialMenu()
        // Only the row carries this description: the ring's circles are not readable.
        compose.onNodeWithContentDescription("Top, ${RadialEntry.defaults.first()!!.spokenName()}").performClick()
        shot("radial_menu_entry_sheet")
    }

    private val threeItems = RadialEntry.spread(listOf(RadialItem.COPY, RadialItem.PASTE, RadialItem.KEYBOARD).map(RadialEntry::Builtin))

    private fun shortcut(initial: RadialEntry.Custom?, edit: Boolean) {
        compose.setContent { PageFrame { RadialShortcutScreen(initial = initial, edit = edit, onSave = {}, onBack = {}) } }
    }

    @Test
    fun newShortcutPage() {
        shortcut(initial = null, edit = false)
        shot("radial_shortcut_new")
    }

    @Test
    fun editShortcutPageWithAnIconToItsEnd() {
        shortcut(initial = custom, edit = true)
        shot("radial_shortcut_icon")
        compose.onNodeWithText("Save").performScrollTo()
        shot("radial_shortcut_icon_end")
    }

    @Test
    fun theShortcutPageNamesTheKeyRowOnceAndOnlyTheButtonSave() {
        shortcut(initial = custom.copy(code = KeyEvent.KEYCODE_W, name = null), edit = true)
        compose.onNodeWithText("Key").assertExists()
        compose.onAllNodesWithText("W").assertCountEquals(1)
        compose.onAllNodes(hasText("Save") or hasContentDescription("Save")).assertCountEquals(1)
        compose.onNodeWithContentDescription("Save icon").assertExists()
    }

    @Test
    fun theShortcutNameCountsAndStopsAtTwentyFourCharactersAsSeen() {
        shortcut(initial = null, edit = false)
        val field = compose.onNode(hasSetTextAction())
        field.performTextInput("👨‍👩‍👧👍🏽")
        compose.onNodeWithText("2 / 24").assertExists()
        field.performTextClearance()
        field.performTextInput("😀".repeat(30))
        compose.onNodeWithText("24 / 24").assertExists()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("😀".repeat(24))))
    }

    @Test
    fun keySheetToItsEnd() {
        shortcut(initial = null, edit = false)
        compose.onNodeWithText("Key").performClick()
        shot("radial_shortcut_key_sheet")
        compose.onNodeWithContentDescription(radialKeys.last().spoken).performScrollTo()
        shot("radial_shortcut_key_sheet_end")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun sizes() = FoldSize.entries.map { arrayOf<Any>(it) }
    }
}
