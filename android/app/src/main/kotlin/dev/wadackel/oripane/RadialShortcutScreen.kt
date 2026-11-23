/**
 * The page that makes or edits a key combination of the user's own, for the radial menu or a
 * gesture.
 */
package dev.wadackel.oripane

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A key combination of the user's own: up to four modifiers, one key from the list the host can
 * send, an optional name and a look. Save stores it and goes back; Back leaves without saving.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RadialShortcutScreen(initial: RadialEntry.Custom?, edit: Boolean, onSave: (RadialEntry.Custom) -> Unit, onBack: () -> Unit) {
    var meta by remember { mutableStateOf(initial?.meta ?: 0) }
    var code by remember { mutableStateOf(initial?.code) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var look by remember { mutableStateOf(initial?.look ?: RadialLook.Text) }
    var choosingKey by remember { mutableStateOf(false) }
    val entry = code?.let { RadialEntry.Custom(it, meta, RadialMenuJson.name(name), look) }
    Page(if (edit) "Edit shortcut" else "Custom shortcut", onBack) {
        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            if (entry != null) {
                RadialEntryCircle(entry, RadialSize.ITEM.dp, on = false)
            } else {
                Box(Modifier.size(RadialSize.ITEM.dp).border(1.dp, Palette.surface3, CircleShape))
            }
        }
        SectionLabel("Modifiers")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            radialModifiers.forEach { k ->
                val on = meta and k.meta != 0
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(Radius.inner))
                        .background(if (on) Palette.lead else Palette.surface1)
                        .toggleable(value = on, role = Role.Checkbox) { meta = meta xor k.meta }
                        .semantics { contentDescription = k.spoken },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(k.label, fontSize = 20.sp, color = if (on) Palette.onLead else Palette.text)
                }
            }
        }
        // A value row names itself, so a "Key" heading above it would say the word twice.
        Spacer(Modifier.height(Space.m))
        SettingsCard(listOf { p -> LinkRow("Key", p, value = code?.let { radialKey(it)?.spoken } ?: "None") { choosingKey = true } })
        SectionLabel("Name")
        OutlinedTextField(
            value = name,
            onValueChange = { name = RadialMenuJson.clip(it) },
            placeholder = { Text(entry?.combo ?: "Optional") },
            singleLine = true,
            supportingText = { Text("${RadialMenuJson.length(name)} / ${RadialMenuJson.NAME_MAX}") },
            shape = RoundedCornerShape(Radius.inner),
            modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel("Look")
        SegmentedChoices(listOf(false to "Text", true to "Icon"), look is RadialLook.Icon) { icon ->
            look = if (icon) (look as? RadialLook.Icon) ?: RadialLook.Icon(RadialIcon.STAR) else RadialLook.Text
        }
        (look as? RadialLook.Icon)?.let { chosen ->
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (RadialIcon.generic + (RadialIcon.entries - RadialIcon.generic.toSet())).forEach { icon ->
                    val on = icon == chosen.icon
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (on) Palette.lead else Palette.surface1)
                            .selectable(selected = on, role = Role.RadioButton) { look = RadialLook.Icon(icon) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(icon.res), contentDescription = "${RadialItem.of(icon.id)?.label ?: icon.id.replaceFirstChar { it.uppercase() }} icon", tint = if (on) Palette.onLead else Palette.icon, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Save", enabled = entry != null) { entry?.let(onSave) }
    }
    if (choosingKey) {
        ModalBottomSheet(
            onDismissRequest = { choosingKey = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.surface1,
            shape = sheetShape,
            dragHandle = { SheetHandle() },
            contentWindowInsets = { sheetInsets() },
            properties = sheetProperties(),
        ) {
            val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                radialKeys.groupBy { it.group }.forEach { (group, keys) ->
                    Text(group, style = Type.heading, color = Palette.textMuted, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp).semantics { heading() })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        keys.forEach { k ->
                            val on = k.code == code
                            Box(
                                Modifier
                                    .heightIn(min = 44.dp)
                                    .widthIn(min = 44.dp)
                                    .clip(RoundedCornerShape(Radius.innermost))
                                    .background(if (on) Palette.lead else Palette.surface2)
                                    .clickable { code = k.code; choosingKey = false }
                                    .semantics { contentDescription = k.spoken }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(k.label, fontSize = 16.sp, color = if (on) Palette.onLead else Palette.text, modifier = Modifier.clearAndSetSemantics {})
                            }
                        }
                    }
                }
            }
        }
    }
}
