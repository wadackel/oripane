/** The radial menu editor: the plate's eight cells, each filled, moved or emptied. */
package dev.wadackel.oripane

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val RADIAL_MENU_TITLE = "Radial menu"

/** The editor's plate, for tests: TalkBack reads the rows instead. */
internal const val RADIAL_PLATE_TAG = "radialPlate"

/** The height of one row of the menu's list. */
private val RADIAL_ROW = 64.dp

/** An item dragged on the plate: the cell it left and the cell under the finger. */
private data class RadialDrag(val from: Int, val to: Int)

/**
 * The radial menu's eight cells, on the plate as it will open and in a list by direction, clockwise
 * from the top. A cell is filled or replaced from the item sheet, by a tap on the plate or on its
 * row; an item is moved by dragging it to another cell on the plate, trading places with what is
 * there (TalkBack offers Move clockwise and Move counterclockwise); the bin empties a cell. Two to
 * eight cells hold an item. Every change is saved at once, a drag when the finger lifts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadialMenuScreen(menu: List<RadialEntry?>, onChange: (List<RadialEntry?>) -> Unit, onShortcut: (cell: Int, edit: Boolean) -> Unit, onBack: () -> Unit) {
    var picker by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<RadialDrag?>(null) }
    // The plate's gestures outlive a composition: they read the menu and the callback of now.
    val latestMenu by rememberUpdatedState(menu)
    val save by rememberUpdatedState(onChange)
    val filled = menu.count { it != null }
    Page(RADIAL_MENU_TITLE, onBack) {
        Help("Both touch modes. Opens from a gesture set to Radial menu: under the finger after a long press, left open to tap after the others. Tap a cell to choose its item; drag an item to another cell to move it.")
        BoxWithConstraints(Modifier.fillMaxWidth().height((RadialSize.PLATE + 24).dp)) {
            val density = LocalDensity.current
            val center = with(density) { Offset(maxWidth.toPx() / 2, maxHeight.toPx() / 2) }
            val pitch = with(density) { RadialSize.PITCH.dp.toPx() }
            val half = with(density) { (RadialSize.PLATE / 2).dp.toPx() }
            fun cellAt(p: Offset) = radialCellAt(p.x - center.x, p.y - center.y, pitch, half)
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(center) { detectTapGestures { p -> cellAt(p)?.let { picker = it } } }
                    .pointerInput(center) {
                        // Only a touch that lands on an item is taken: from anywhere else the page scrolls.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val from = cellAt(down.position)?.takeIf { latestMenu[it] != null } ?: return@awaitEachGesture
                            val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                            moving = RadialDrag(from, cellAt(start.position) ?: from)
                            val lifted = drag(start.id) { change ->
                                change.consume()
                                moving = moving?.copy(to = cellAt(change.position) ?: from)
                            }
                            val done = moving
                            moving = null
                            if (lifted && done != null && done.from != done.to) save(radialSwap(latestMenu, done.from, done.to))
                        }
                    }
                    // The rows below say the same to TalkBack, with names the plate's icons lack.
                    .clearAndSetSemantics { testTag = RADIAL_PLATE_TAG },
            ) {
                val shown = moving?.let { radialSwap(menu, it.from, it.to) } ?: menu
                RadialPlate(shown, moving?.to, center, readable = false)
            }
        }
        Card {
            menu.forEachIndexed { cell, entry ->
                if (cell > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Palette.divider)
                val direction = radialCellNames[cell]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(RADIAL_ROW)
                        .clickable { picker = cell }
                        .semantics {
                            if (entry != null) {
                                customActions = listOf(
                                    CustomAccessibilityAction("Move clockwise") { onChange(radialSwap(menu, cell, (cell + 1) % 8)); true },
                                    CustomAccessibilityAction("Move counterclockwise") { onChange(radialSwap(menu, cell, (cell + 7) % 8)); true },
                                )
                            }
                        }
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (entry != null) {
                        RadialEntryCircle(entry, RadialSize.ITEM.dp, on = false, description = null)
                    } else {
                        Box(Modifier.size(RadialSize.ITEM.dp).border(1.dp, Palette.divider, CircleShape))
                    }
                    // Read once, with a custom shortcut's keys spelt out rather than as symbols.
                    Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "$direction, ${entry?.spokenName() ?: "empty"}" }) {
                        Text(
                            entry?.label ?: "Empty",
                            fontSize = 15.sp,
                            color = if (entry != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val combo = entry?.combo?.takeIf { it != entry.label }
                        Text(listOfNotNull(direction, combo).joinToString("  ·  "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (entry != null) {
                        IconSpot(
                            R.drawable.ic_delete,
                            "Remove ${entry.label}",
                            tint = if (filled > RadialEntry.MIN) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            enabled = filled > RadialEntry.MIN,
                        ) { onChange(menu.toMutableList().also { it[cell] = null }) }
                    } else {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DangerButton("Restore defaults", R.drawable.ic_retry) { confirm = true }
    }
    picker?.let { cell ->
        // An item already on the menu cannot go on it twice, but the cell being replaced may keep its own.
        EntrySheet(
            title = radialCellNames[cell],
            fixed = emptyList(),
            current = menu[cell]?.let(GestureAction::Run),
            taken = menu.filterIndexed { i, _ -> i != cell }.mapNotNull { (it as? RadialEntry.Builtin)?.item }.toSet(),
            onPick = { a ->
                picker = null
                val entry = (a as? GestureAction.Run)?.entry ?: return@EntrySheet
                onChange(menu.toMutableList().also { it[cell] = entry })
            },
            onShortcut = { edit ->
                picker = null
                onShortcut(cell, edit)
            },
            onDismiss = { picker = null },
        )
    }
    if (confirm) {
        ConfirmDialog(
            title = "Restore the default radial menu?",
            body = "This puts back the eight default items. Custom shortcuts on the menu are removed.",
            confirm = "Restore",
            onConfirm = { confirm = false; onChange(RadialEntry.defaults) },
            onDismiss = { confirm = false },
        )
    }
}
