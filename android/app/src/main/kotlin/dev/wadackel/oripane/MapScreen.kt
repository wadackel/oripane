/** A touch mode's gesture page: each gesture and what it is set to. */
package dev.wadackel.oripane

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun gesturesTitle(m: TouchMode) = if (m == TouchMode.DIRECT) "Direct gestures" else "Trackpad gestures"

@Composable
fun MapScreen(mode: TouchMode, map: TouchMap, onChange: (TouchMap) -> Unit, onShortcut: (Gesture, edit: Boolean) -> Unit, onBack: () -> Unit) {
    var picking by remember { mutableStateOf<Gesture?>(null) }
    Page(gesturesTitle(mode), onBack) {
        Help(if (mode == TouchMode.DIRECT) "Direct mode only." else "Trackpad mode only.", Modifier.padding(bottom = 12.dp))
        // Three-finger drag is a trackpad gesture; in the direct mode three fingers stay the Mac's.
        val rows = Gesture.entries.filter { mode == TouchMode.TRACKPAD || it != Gesture.THREE_FINGER_DRAG }
        SettingsCard(rows.map { g -> { p -> LinkRow(g.label, p, value = actionLabel(map[g])) { picking = g } } })
    }
    picking?.let { g ->
        val pick: (GestureAction) -> Unit = {
            picking = null
            onChange(map.with(g, it))
        }
        if (g.catalog) {
            EntrySheet(g.label, g.choices, map[g], taken = emptySet(), onPick = pick, onShortcut = { edit ->
                picking = null
                onShortcut(g, edit)
            }, onDismiss = { picking = null })
        } else {
            ChoiceSheet(g.label, listOf(null to g.choices), map[g], ::actionLabel, onDismiss = { picking = null }, onSelect = pick)
        }
    }
}
