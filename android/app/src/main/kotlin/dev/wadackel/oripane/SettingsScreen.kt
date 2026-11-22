/**
 * Settings: an index of sections, each with its current values, and the page of each section. On a
 * wide screen the index stays on the left and the chosen section shows beside it. The rows of each
 * section are built once here and shared with the stream's live settings.
 */
package dev.wadackel.oripane

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A section of Settings with a page of its own. The radial menu has no page here: its row opens its
 * editor. About holds no settings; it is last, after them.
 */
enum class SettingsSection(val key: String, val title: String, @DrawableRes val icon: Int) {
    VIDEO("video", "Video", R.drawable.ic_display),
    TOUCH("touch", "Touch & pointer", R.drawable.ic_right_click),
    GESTURES("gestures", "Gestures", R.drawable.ic_menu),
    SCROLLING("scrolling", "Scrolling", R.drawable.ic_arrow_right),
    ABOUT("about", "About", R.drawable.ic_info),
}

/** A screen at least this wide shows the index and a section side by side. */
val WIDE_SETTINGS = 600.dp

/** A group of rows on one surface, with its heading when the page has more than one group. */
class SettingsGroup(val title: String?, val rows: List<@Composable (PaddingValues) -> Unit>)

/** Groups on their surfaces, headed only when there is more than one. */
@Composable
fun SettingsGroups(groups: List<SettingsGroup>, color: Color = Palette.surface1) {
    groups.forEachIndexed { i, g ->
        if (groups.size > 1 && g.title != null) SectionLabel(g.title) else if (i > 0) Spacer(Modifier.height(Space.m))
        SettingsCard(g.rows, color)
    }
}

private fun gestureCount(map: TouchMap, mode: TouchMode) = Gesture.entries.count { (mode == TouchMode.TRACKPAD || it != Gesture.THREE_FINGER_DRAG) && map[it] != GestureAction.None }

private val Settings.radialCount get() = radialMenu.count { it != null }

private fun speedName(s: Speed) = speeds.first { it.first == s }.second

private fun bandsSummary(s: ScrollStrip) = when (s) {
    ScrollStrip.RIGHT -> "bands on the right"
    ScrollStrip.LEFT -> "bands on the left"
    ScrollStrip.OFF -> "bands off"
}

/** What a section's row in the index says under its name: the values that matter most, at a glance. */
fun sectionSummary(section: SettingsSection, s: Settings, build: BuildInfo) = when (section) {
    SettingsSection.VIDEO -> "${(s.scale * 100).toInt()}% · ${s.fps} fps · Data saver ${dataSaverOptions.first { it.first == s.dataSaver }.second.lowercase()}"
    SettingsSection.TOUCH -> "${modeLabel(s.touchMode)} · cursor ${speedName(s.cursorSpeed).lowercase()}"
    SettingsSection.GESTURES -> "Direct ${gestureCount(s.mapDirect, TouchMode.DIRECT)} · Trackpad ${gestureCount(s.mapTrackpad, TouchMode.TRACKPAD)}"
    SettingsSection.SCROLLING -> "${directions.first { it.first == s.touchScroll.reverse }.second} · ${bandsSummary(s.scrollStrip)}"
    SettingsSection.ABOUT -> "Version ${build.version}"
}

fun radialSummary(s: Settings) = "${s.radialCount} items"

/** How the touch modes differ, under each in the Touch mode sheet. */
private val touchModeNotes = mapOf(
    TouchMode.DIRECT to "Your finger presses where it touches, like a touchscreen.",
    TouchMode.TRACKPAD to "The phone is a trackpad; a cursor moves on the Mac.",
)

private const val CURSOR_NOTE = "Trackpad mode only."
private const val INCOGNITO_NOTE = "The keyboard does not learn what you type."

/**
 * The scrolling groups, the same on the Scrolling page and in the live settings, where [named]
 * headings say they are about scrolling: the live settings have other groups beside them.
 */
private fun scrollingGroups(s: Settings, set: (Settings, String) -> Unit, named: Boolean = false) = listOf(
    SettingsGroup(
        if (named) "Finger scroll" else "Finger",
        listOf(
            { p -> Choices("Speed", speeds, s.touchScroll.speed, modifier = Modifier.padding(p)) { set(s.copy(touchScroll = s.touchScroll.copy(speed = it)), "scroll.touch.speed=${it.key}") } },
            { p -> Choices("Direction", directions, s.touchScroll.reverse, modifier = Modifier.padding(p)) { set(s.copy(touchScroll = s.touchScroll.copy(reverse = it)), "scroll.touch.reverse=$it") } },
        ),
    ),
    SettingsGroup(
        if (named) "Wheel scroll" else "Wheel",
        listOf(
            { p -> Choices("Speed", speeds, s.wheelScroll.speed, modifier = Modifier.padding(p)) { set(s.copy(wheelScroll = s.wheelScroll.copy(speed = it)), "scroll.wheel.speed=${it.key}") } },
            { p -> Choices("Direction", directions, s.wheelScroll.reverse, modifier = Modifier.padding(p)) { set(s.copy(wheelScroll = s.wheelScroll.copy(reverse = it)), "scroll.wheel.reverse=$it") } },
        ),
    ),
    SettingsGroup(
        if (named) "Scroll bands" else "Bands",
        listOf(
            { p -> Choices("Position", stripSides, s.scrollStrip, note = stripNote, modifier = Modifier.padding(p)) { set(s.copy(scrollStrip = it), "scroll.strip=${it.key}") } },
            { p -> SwitchRow("Haptics", s.stripHaptics, p) { set(s.copy(stripHaptics = it), "scroll.strip.haptics=$it") } },
            { p -> SwitchRow("Inertia", s.scrollInertia, p) { set(s.copy(scrollInertia = it), "scroll.inertia=$it") } },
        ),
    ),
)

/**
 * The settings that act at once, as the stream's menu shows them. Touch mode takes segments here
 * rather than its row and sheet: a second sheet cannot open over the menu's.
 */
fun liveSettingGroups(s: Settings, set: (Settings, String) -> Unit): List<SettingsGroup> = listOf(
    SettingsGroup(
        "Video",
        listOf(
            { p -> SwitchRow("Show latency", s.showStats, p) { set(s.copy(showStats = it), "showStats=$it") } },
            { p -> Choices("Data saver", dataSaverOptions, s.dataSaver, modifier = Modifier.padding(p)) { set(s.copy(dataSaver = it), "dataSaver=${it.key}") } },
        ),
    ),
    SettingsGroup(
        "Touch & pointer",
        listOf(
            { p -> Choices("Touch mode", TouchMode.entries.map { it to modeLabel(it) }, s.touchMode, note = touchModeNote, modifier = Modifier.padding(p)) { set(s.copy(touchMode = it), "touch.mode=${it.key}") } },
            { p -> SwitchRow("Incognito keyboard", s.incognitoKeyboard, p, note = INCOGNITO_NOTE) { set(s.copy(incognitoKeyboard = it), "keyboard.incognito=$it") } },
        ),
    ),
) + scrollingGroups(s, set, named = true)

/** What a section's page holds; [onTouchMode] opens the Touch mode sheet, [onMap] a gesture page. */
private fun sectionGroups(section: SettingsSection, s: Settings, onChange: (Settings) -> Unit, onTouchMode: () -> Unit, onMap: (TouchMode) -> Unit): List<SettingsGroup> {
    val set: (Settings, String) -> Unit = { next, _ -> onChange(next) }
    return when (section) {
        SettingsSection.VIDEO -> listOf(
            SettingsGroup(
                null,
                listOf(
                    { p -> Choices("Resolution", Settings.scales.map { it to "${(it * 100).toInt()}%" }, s.scale, modifier = Modifier.padding(p)) { onChange(s.copy(scale = it)) } },
                    { p -> Choices("Frame rate", Settings.fpsChoices.map { it to "$it fps" }, s.fps, modifier = Modifier.padding(p)) { onChange(s.copy(fps = it)) } },
                    { p -> Choices("Data saver", dataSaverOptions, s.dataSaver, note = "Auto turns it on when not on Wi-Fi.", modifier = Modifier.padding(p)) { onChange(s.copy(dataSaver = it)) } },
                    { p -> Choices("Display size", listOf(2.0 to "Larger text", 1.5 to "More space"), s.uiScale, modifier = Modifier.padding(p)) { onChange(s.copy(uiScale = it)) } },
                    { p -> SwitchRow("Show latency", s.showStats, p) { onChange(s.copy(showStats = it)) } },
                ),
            ),
        )
        SettingsSection.TOUCH -> listOf(
            SettingsGroup(
                null,
                listOf(
                    { p -> LinkRow("Touch mode", p, value = modeLabel(s.touchMode), onClick = onTouchMode) },
                    { p -> Choices("Cursor speed", speeds, s.cursorSpeed, note = CURSOR_NOTE, modifier = Modifier.padding(p)) { onChange(s.copy(cursorSpeed = it)) } },
                    { p -> SwitchRow("Incognito keyboard", s.incognitoKeyboard, p, note = INCOGNITO_NOTE) { onChange(s.copy(incognitoKeyboard = it)) } },
                ),
            ),
        )
        SettingsSection.GESTURES -> listOf(
            SettingsGroup(
                null,
                TouchMode.entries.map<TouchMode, @Composable (PaddingValues) -> Unit> { mode -> { p -> LinkRow(gesturesTitle(mode), p, value = "${gestureCount(s.mapFor(mode), mode)} set", icon = R.drawable.ic_menu) { onMap(mode) } } },
            ),
        )
        SettingsSection.SCROLLING -> scrollingGroups(s, set)
        SettingsSection.ABOUT -> emptyList()
    }
}

/**
 * Settings: the index ([section] null) or a section's page. Changes are saved at once; there is no
 * save button. On a screen of [WIDE_SETTINGS] or more the index and a section show side by side,
 * and a section picked in the index changes the pane without leaving the page.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    section: SettingsSection? = null,
    onChange: (Settings) -> Unit,
    onReset: () -> Unit,
    onSection: (SettingsSection) -> Unit,
    onMap: (TouchMode) -> Unit,
    onRadialMenu: () -> Unit,
    onBack: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    var touchSheet by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_SETTINGS
        when {
            wide -> {
                var shown by rememberSaveable { mutableStateOf(section ?: SettingsSection.VIDEO) }
                SettingsSplit(
                    settings,
                    shown,
                    onChange = onChange,
                    onPick = { shown = it },
                    onRadialMenu = onRadialMenu,
                    onReset = { confirm = true },
                    onTouchMode = { touchSheet = true },
                    onMap = onMap,
                    onBack = onBack,
                )
            }
            section == null -> Page("Settings", onBack) {
                SettingsIndex(settings, null, onSection, onRadialMenu) { confirm = true }
            }
            else -> Page(section.title, onBack) {
                if (section == SettingsSection.ABOUT) AboutBody()
                if (section == SettingsSection.VIDEO) Help(NEXT_CONNECTION, Modifier.padding(bottom = Space.s))
                SettingsGroups(sectionGroups(section, settings, onChange, { touchSheet = true }, onMap))
            }
        }
    }
    if (touchSheet) {
        DescribedChoiceSheet("Touch mode", TouchMode.entries, settings.touchMode, ::modeLabel, { touchModeNotes.getValue(it) }, onDismiss = { touchSheet = false }) {
            touchSheet = false
            onChange(settings.copy(touchMode = it))
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = "Reset all settings?",
            body = "This resets every setting, both gesture maps and the radial menu. Your Macs are kept.",
            confirm = "Reset",
            onConfirm = { confirm = false; onReset() },
            onDismiss = { confirm = false },
        )
    }
}

private const val NEXT_CONNECTION = "Changes apply from the next connection."

/** The index: a row per section with its icon and summary, the radial menu's row, and the reset on a surface of its own. */
@Composable
private fun SettingsIndex(settings: Settings, selected: SettingsSection?, onSection: (SettingsSection) -> Unit, onRadialMenu: () -> Unit, onReset: () -> Unit) {
    val build = rememberBuildInfo()
    val section: (SettingsSection) -> @Composable (PaddingValues) -> Unit = { sec -> { p -> LinkRow(sec.title, p, icon = sec.icon, sub = sectionSummary(sec, settings, build), selected = sec == selected) { onSection(sec) } } }
    val rows: List<@Composable (PaddingValues) -> Unit> = listOf(SettingsSection.VIDEO, SettingsSection.TOUCH, SettingsSection.GESTURES).map(section) +
        listOf<@Composable (PaddingValues) -> Unit>({ p -> LinkRow(RADIAL_MENU_TITLE, p, icon = R.drawable.ic_mission_control, sub = radialSummary(settings), onClick = onRadialMenu) }) +
        listOf(SettingsSection.SCROLLING, SettingsSection.ABOUT).map(section)
    SettingsCard(rows)
    Spacer(Modifier.height(Space.m))
    DangerButton("Reset all settings", onClick = onReset)
}

/** The wide layout: the index on the left, the chosen section on a surface of its own on the right. */
@Composable
private fun SettingsSplit(
    settings: Settings,
    shown: SettingsSection,
    onChange: (Settings) -> Unit,
    onPick: (SettingsSection) -> Unit,
    onRadialMenu: () -> Unit,
    onReset: () -> Unit,
    onTouchMode: () -> Unit,
    onMap: (TouchMode) -> Unit,
    onBack: () -> Unit,
) {
    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = Space.s, end = Space.l, bottom = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        Column(Modifier.width(330.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            PageHeader("Settings", onBack)
            Box(Modifier.padding(start = 4.dp)) { Column { SettingsIndex(settings, shown, onPick, onRadialMenu, onReset) } }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(top = 10.dp).clip(RoundedCornerShape(Radius.surface)).background(Palette.surface1).verticalScroll(rememberScrollState()).padding(start = Space.m, end = Space.m, bottom = Space.m),
        ) {
            PageHeader(shown.title, null)
            if (shown == SettingsSection.ABOUT) AboutBody(Palette.surface2)
            if (shown == SettingsSection.VIDEO) Help(NEXT_CONNECTION, Modifier.padding(bottom = Space.s))
            SettingsGroups(sectionGroups(shown, settings, onChange, onTouchMode, onMap), Palette.surface2)
        }
    }
}
