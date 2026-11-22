/**
 * The stream's menu sheet and its pages: the menu (the Mac, what acts now, what opens a page),
 * display modes, the Mac's controls and the settings that act at once.
 */
package dev.wadackel.oripane

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** What the menu sheet shows and does; [StreamScreenState] owns the state behind it. */
class MenuModel(
    val menu: MenuState,
    /** The Mac's name on the sheet's head, and the line under it: display mode, route and frame rate. */
    val macName: String,
    val status: String,
    val statusColor: StatusColor,
    /** The colour of the video under the sheet, washed into its head. */
    val tint: Color?,
    val reconnecting: Boolean,
    /** The `mode` of the last `stream`: the value on the Display mode row and the mode ticked on its page. */
    val streamMode: String,
    val zoomed: Boolean,
    val settings: Settings,
    val onBack: () -> Unit,
    /** The sheet went away by itself: a tap outside it or a swipe down. */
    val onDismissed: () -> Unit,
    /** An item hid the sheet; called once it is off the screen. */
    val onItemClosed: () -> Unit,
    val onKeyboard: () -> Unit,
    val onMode: (String) -> Unit,
    val onResetZoom: () -> Unit,
    val onSettingsPage: () -> Unit,
    val onMacPage: () -> Unit,
    val onDisplayPage: () -> Unit,
    val onMac: (MacAction) -> Unit,
    val onDisconnect: () -> Unit,
    val onSetting: (Settings, String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(m: MenuModel) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // The sheet must be off the screen before the item acts: the keyboard needs the window's focus back.
    fun closeThen(action: () -> Unit) {
        scope.launch { sheet.hide() }.invokeOnCompletion {
            m.onItemClosed()
            action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = m.onDismissed,
        sheetState = sheet,
        containerColor = Palette.surface1,
        shape = sheetShape,
        // The handle is drawn inside the content, so the video's wash runs under it without a seam.
        dragHandle = null,
        contentWindowInsets = { sheetInsets() },
        properties = sheetProperties(dismissOnBack = false),
    ) {
        BackHandler(onBack = m.onBack)
        // A full page would run under the status bar when it shows: the sheet has no top inset.
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        // The video's colour washing down from the sheet's top-left, gone before the rows begin.
        val reach = with(LocalDensity.current) { 180.dp.toPx() }
        val wash = m.tint?.let { Modifier.background(Brush.linearGradient(listOf(it.copy(alpha = 0.30f), Color.Transparent), Offset.Zero, Offset(reach * 0.37f, reach))) } ?: Modifier
        Column(Modifier.fillMaxWidth().then(wash), horizontalAlignment = Alignment.CenterHorizontally) {
            SheetHandle()
            PageStack(m.menu, MenuState::parentPage, onBack = { m.onBack() }) { page ->
                // One column per page: AnimatedContent places its children on top of one another.
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(start = Space.m, end = Space.m, bottom = 22.dp)) {
                    when (page) {
                        MenuState.SETTINGS -> LiveSettings(m.settings, m.onSetting)
                        MenuState.MAC -> MacItems(m, ::closeThen)
                        MenuState.DISPLAY -> DisplayModeItems(m, ::closeThen)
                        else -> MenuItems(m, ::closeThen)
                    }
                }
            }
        }
    }
}

/** A page of the sheet past the menu: its title, as the menu's head names the Mac. */
@Composable
private fun SheetPageTitle(text: String) {
    Text(text, style = Type.sheetTitle, color = Palette.text, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = Space.xs).semantics { heading() })
}

/** A surface of menu rows, a step lighter than the sheet. */
@Composable
private fun MenuGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = Space.s).fillMaxWidth().clip(RoundedCornerShape(Radius.surface)).background(Palette.surface2).padding(vertical = 2.dp, horizontal = 4.dp), content = content)
}

/**
 * One 48 dp menu row, one line, with an optional [value] at its end and a chevron when it [opensPage].
 * A disabled row fades itself, value and chevron included: unlike a TextButton nothing does it for it.
 */
@Composable
private fun MenuRow(
    @DrawableRes icon: Int,
    text: String,
    first: Boolean,
    enabled: Boolean = true,
    value: String? = null,
    opensPage: Boolean = false,
    onClick: () -> Unit,
) {
    val fade = if (enabled) 1f else 0.38f
    Row(
        Modifier.fillMaxWidth().rowDivider(!first).height(Sizes.menuRow).clip(RoundedCornerShape(Radius.inner)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RowIcon(icon, Palette.icon.copy(alpha = fade))
        Text(text, style = Type.label, color = Palette.text.copy(alpha = fade), modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = Type.value, color = Palette.textMuted.copy(alpha = fade))
        if (opensPage) Chevron(Palette.textFaint.copy(alpha = fade))
    }
}

/**
 * The menu: the Mac on the head with the way out of it, then two surfaces. The first holds what acts
 * now (the keyboard, the zoom, the Mac's own controls); the second opens what changes the session.
 */
@Composable
private fun MenuItems(m: MenuModel, closeThen: (() -> Unit) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(Sizes.primary).clip(RoundedCornerShape(Radius.inner)).background(Palette.well), contentAlignment = Alignment.Center) {
            Icon(painterResource(macKindIcon(m.macName)), contentDescription = null, tint = Palette.icon, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(m.macName, style = Type.title.copy(fontSize = 20.sp), color = Palette.text, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                Box(Modifier.size(7.dp).background(m.statusColor.color(), CircleShape))
                Text(m.status, style = Type.note, color = Palette.textMuted, maxLines = 1)
            }
        }
        RoundButton(R.drawable.ic_power, "Disconnect", background = Palette.well, tint = Palette.danger) { closeThen(m.onDisconnect) }
    }
    MenuGroup {
        MenuRow(R.drawable.ic_keyboard, "Show keyboard", first = true, enabled = !m.reconnecting) { closeThen(m.onKeyboard) }
        if (m.zoomed) MenuRow(R.drawable.ic_zoom, "Reset zoom", first = false) { closeThen(m.onResetZoom) }
        MenuRow(R.drawable.ic_mac, MAC_CONTROLS, first = false, enabled = !m.reconnecting, opensPage = true, onClick = m.onMacPage)
    }
    MenuGroup {
        val test = m.streamMode == "test"
        MenuRow(
            R.drawable.ic_display,
            "Display mode",
            first = true,
            enabled = !m.reconnecting && !test,
            value = if (test) "Test pattern" else displayModes.firstOrNull { it.first == m.streamMode }?.second,
            opensPage = true,
            onClick = m.onDisplayPage,
        )
        MenuRow(R.drawable.ic_settings, "Live settings", first = false, opensPage = true, onClick = m.onSettingsPage)
    }
}

/** The page of the Mac's own controls: Mission Control, spaces, apps, media and the rest of [macMenuSections]. */
const val MAC_CONTROLS = "Mac controls"

/** What each display mode does to the phone and the Mac, under its name on the Display mode page. */
private val displayModeNotes = mapOf(
    "extend" to "Use the phone as a second display",
    "mirror" to "Show the Mac's main display as it is",
    "match" to "Fill the phone with the whole desktop; the Mac's own screen changes to match",
)

/**
 * The display modes, the streaming one ticked. Another mode closes the sheet before switching, as
 * the switch rebuilds the Mac's display; the ticked one goes back to the menu.
 */
@Composable
private fun DisplayModeItems(m: MenuModel, closeThen: (() -> Unit) -> Unit) {
    val enabled = !m.reconnecting
    SheetPageTitle("Display mode")
    MenuGroupColumn(Modifier.selectableGroup()) {
        displayModes.forEach { (mode, label) ->
            val selected = mode == m.streamMode
            val disabled = Palette.textMuted.copy(alpha = 0.38f)
            val nameColor = if (enabled) Palette.text else disabled
            Row(
                Modifier
                    .fillMaxWidth()
                    .rowDivider(mode != displayModes.first().first)
                    .heightIn(min = 64.dp)
                    .clip(RoundedCornerShape(Radius.inner))
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                        if (selected) m.onBack() else closeThen { m.onMode(mode) }
                    }
                    .padding(horizontal = Space.s, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = Type.label, color = nameColor, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                    Text(displayModeNotes.getValue(mode), style = Type.note, color = if (enabled) Palette.textMuted else disabled)
                }
                // Room for the tick on every row, so a change of selection never rewraps a note.
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = if (enabled) Palette.text else disabled, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * The Mac actions in [macMenuSections]. An action that changes the screen closes the sheet first,
 * or the sheet would hide what it opened; a volume or track key keeps it open for the next press.
 * Locking asks first, with the [LockConfirm] the radial menu asks too: unlocking takes the Mac's password.
 */
@Composable
private fun MacItems(m: MenuModel, closeThen: (() -> Unit) -> Unit) {
    var confirmLock by remember { mutableStateOf(false) }
    val enabled = !m.reconnecting
    val color = Palette.text.copy(alpha = if (enabled) 1f else 0.38f)
    fun press(a: MacAction) = when {
        a == MacAction.LOCK_SCREEN -> confirmLock = true
        a.keepsMenu -> m.onMac(a)
        else -> closeThen { m.onMac(a) }
    }
    SheetPageTitle(MAC_CONTROLS)
    macMenuSections.forEach { section ->
        Text(
            section.title,
            style = Type.heading,
            color = Palette.textMuted,
            modifier = Modifier.padding(start = 4.dp, top = Space.m, bottom = 0.dp).semantics { heading() },
        )
        MenuGroupColumn {
            section.rows.forEachIndexed { i, row ->
                val single = row.actions.singleOrNull()
                if (single != null) {
                    Row(
                        Modifier.fillMaxWidth().rowDivider(i > 0).height(Sizes.menuRow).clip(RoundedCornerShape(Radius.inner)).clickable(enabled = enabled) { press(single) }.padding(horizontal = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(single.label, style = Type.label, color = color)
                    }
                } else {
                    Row(Modifier.fillMaxWidth().rowDivider(i > 0).height(Sizes.menuRow).padding(start = Space.s, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.label.orEmpty(), style = Type.label, color = color, modifier = Modifier.weight(1f))
                        row.actions.forEach { a ->
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button) { press(a) }.semantics { contentDescription = a.label },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(macGlyph(a), color = color, fontSize = 20.sp, modifier = Modifier.clearAndSetSemantics {})
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmLock) {
        ConfirmDialog(
            title = LockConfirm.TITLE,
            body = LockConfirm.BODY,
            confirm = LockConfirm.CONFIRM,
            onConfirm = {
                confirmLock = false
                closeThen { m.onMac(MacAction.LOCK_SCREEN) }
            },
            onDismiss = { confirmLock = false },
        )
    }
}

/** The question before locking the Mac, asked the same from the Mac page and the radial menu. */
object LockConfirm {
    const val TITLE = "Lock the Mac?"
    const val BODY = "Unlocking it again takes the Mac's password, typed on the Mac or with the keyboard here."
    const val CONFIRM = "Lock"
}

/** What a button on a shared row shows; its name is read out instead. */
private fun macGlyph(a: MacAction) = when (a) {
    MacAction.SPACE_LEFT -> "←"
    MacAction.SPACE_RIGHT -> "→"
    MacAction.VOLUME_DOWN, MacAction.BRIGHTNESS_DOWN -> "−"
    MacAction.VOLUME_UP, MacAction.BRIGHTNESS_UP -> "+"
    MacAction.PREVIOUS_TRACK -> "⏮"
    MacAction.PLAY_PAUSE -> "⏯"
    MacAction.NEXT_TRACK -> "⏭"
    else -> a.label
}

/** A surface of rows inside a sheet page, without the menu's top gap. */
@Composable
private fun MenuGroupColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = Space.xs).fillMaxWidth().clip(RoundedCornerShape(Radius.surface)).background(Palette.surface2).padding(vertical = 2.dp, horizontal = 4.dp).then(modifier), content = content)
}

/**
 * The settings that act at once, in the groups and with the parts the Settings pages give them; the
 * rest wait for the next connection.
 */
@Composable
private fun LiveSettings(s: Settings, onSetting: (Settings, String) -> Unit) {
    SheetPageTitle("Live settings")
    SettingsGroups(liveSettingGroups(s, onSetting), Palette.surface2)
    Help("Other settings can be changed in Settings when not connected.", Modifier.padding(top = Space.m))
}
