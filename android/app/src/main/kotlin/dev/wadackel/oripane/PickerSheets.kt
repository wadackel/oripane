/**
 * The bottom sheets that pick what a radial menu place or a gesture does: the radial menu's items,
 * or a gesture's own choices.
 */
package dev.wadackel.oripane

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun actionLabel(a: GestureAction) = when (a) {
    GestureAction.Click -> "Click"
    GestureAction.RIGHT_CLICK -> "Right-click"
    GestureAction.Drag -> "Start drag"
    GestureAction.Scroll -> "Scroll"
    GestureAction.Zoom -> "Zoom"
    GestureAction.RadialMenu -> "Radial menu"
    GestureAction.None -> "None"
    is GestureAction.Run -> a.entry.label
}

/** The built-in items by group: editing keys, the phone's own input, the view, then the Mac page's sections. */
private val radialItemGroups: List<Pair<String, List<RadialItem>>> =
    listOf(
        "Edit" to listOf(RadialItem.UNDO, RadialItem.REDO, RadialItem.CUT, RadialItem.COPY, RadialItem.PASTE, RadialItem.SELECT_ALL, RadialItem.SAVE, RadialItem.FIND),
        "Input" to listOf(RadialItem.KEYBOARD, RadialItem.RIGHT_CLICK),
        "View" to listOf(RadialItem.FILL_SCREEN),
    ) + macMenuSections.map { section -> section.title to section.rows.flatMap { it.actions }.map { a -> RadialItem.of(a.key)!! } }

/**
 * The radial menu's vocabulary for one place, on the menu or on a gesture: [fixed] choices of the
 * place's own first, then every built-in item by group, then a shortcut of the user's own. An item
 * in [taken] cannot be picked again; [current] is marked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntrySheet(
    title: String?,
    fixed: List<GestureAction>,
    current: GestureAction?,
    taken: Set<RadialItem>,
    onPick: (GestureAction) -> Unit,
    onShortcut: (edit: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentEntry = (current as? GestureAction.Run)?.entry
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.surface1,
        shape = sheetShape,
        dragHandle = { SheetHandle() },
        contentWindowInsets = { sheetInsets() },
        properties = sheetProperties(),
    ) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            title?.let { SheetTitle(it) }
            fixed.forEach { a ->
                val on = a == current
                SheetRow(selected = on, onClick = { onPick(a) }) {
                    Text(
                        actionLabel(a),
                        style = Type.label,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) Palette.text else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (on) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Palette.text, modifier = Modifier.size(22.dp))
                }
            }
            radialItemGroups.forEach { (heading, items) ->
                SheetHeading(heading)
                items.forEach { item ->
                    val enabled = item !in taken
                    val entry = RadialEntry.Builtin(item)
                    SheetRow(enabled = enabled, onClick = { onPick(GestureAction.Run(entry)) }) {
                        RadialEntryCircle(entry, 32.dp, on = entry == currentEntry, Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.38f }, description = null)
                        Text(item.label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.weight(1f))
                        item.shortcut?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)) }
                    }
                }
            }
            SheetHeading("Custom")
            if (currentEntry is RadialEntry.Custom) {
                SheetRow(onClick = { onShortcut(true) }) { Text("Edit shortcut…", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface) }
            }
            SheetRow(onClick = { onShortcut(false) }) { Text("Custom shortcut…", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = Type.heading,
        color = Palette.textMuted,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A row of a sheet; given [selected], it is one of exclusive choices and TalkBack reads whether it is the chosen one. */
@Composable
private fun SheetRow(enabled: Boolean = true, selected: Boolean? = null, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val press = if (selected != null) {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).then(press).padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/**
 * The choices for one setting under [title], grouped under their headings (null for none), with
 * [selected] checked. A pick goes to [onSelect], which saves it and closes the sheet at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceSheet(title: String, groups: List<Pair<String?, List<T>>>, selected: T, text: (T) -> String, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.surface1,
        shape = sheetShape,
        dragHandle = { SheetHandle() },
        contentWindowInsets = { sheetInsets() },
        properties = sheetProperties(),
    ) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetTitle(title)
            groups.forEach { (heading, choices) ->
                heading?.let { SheetHeading(it) }
                choices.forEach { c ->
                    val on = c == selected
                    SheetRow(selected = on, onClick = { onSelect(c) }) {
                        Text(
                            text(c),
                            style = Type.label,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (on) Palette.text else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (on) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Palette.text, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

/**
 * A choice whose options need a sentence each to tell apart (Touch mode): one row per option with its
 * [note] under the name, [selected] checked. A pick goes to [onSelect], which saves it and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> DescribedChoiceSheet(title: String, options: List<T>, selected: T, name: (T) -> String, note: (T) -> String, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.surface1,
        shape = sheetShape,
        dragHandle = { SheetHandle() },
        contentWindowInsets = { sheetInsets() },
        properties = sheetProperties(),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            SheetTitle(title)
            options.forEach { o ->
                val on = o == selected
                SheetRow(selected = on, onClick = { onSelect(o) }) {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(name(o), style = Type.label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = Palette.text)
                        Text(note(o), style = Type.note, color = Palette.textMuted, modifier = Modifier.padding(top = 2.dp))
                    }
                    if (on) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Palette.text, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
