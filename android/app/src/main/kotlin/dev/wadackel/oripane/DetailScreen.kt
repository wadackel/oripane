/** A saved Mac's page: its display name, addresses, last use, and removal from this phone. */
package dev.wadackel.oripane

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.drop

@Composable
fun DetailScreen(mac: SavedMac, onRename: (String?) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    val name = rememberTextFieldState(mac.customName ?: "")
    var confirm by remember { mutableStateOf(false) }
    val rename by rememberUpdatedState(onRename)
    LaunchedEffect(name) {
        snapshotFlow { name.text.toString() }.drop(1).collect { rename(it.trim().ifEmpty { null }) }
    }
    Page(mac.displayName, onBack) {
        Spacer(Modifier.height(12.dp))
        // The label stays above the field so the Mac's own name shows as the placeholder while it
        // is empty; only the state-based field takes a label position.
        OutlinedTextField(
            state = name,
            label = { Text("Display name") },
            labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
            placeholder = { Text(mac.name) },
            lineLimits = TextFieldLineLimits.SingleLine,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Help("Leave empty to use the name the Mac reports.", Modifier.padding(top = 8.dp))
        val addresses = mac.lanAddrs.map { "LAN" to it } + mac.tailnet.map { "Tailscale" to it }
        if (addresses.isNotEmpty()) {
            SectionLabel("Addresses")
            Card {
                addresses.forEachIndexed { i, (route, addr) ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Palette.divider)
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Tag(route)
                        Text(addr, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        SectionLabel("Last used")
        Card { Text(lastUsedText(mac.lastUsed), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) }
        Spacer(Modifier.height(24.dp))
        DangerButton("Remove from this phone") { confirm = true }
    }
    if (confirm) {
        ConfirmDialog(
            title = "Remove ${mac.displayName}?",
            body = "This removes it from this phone only. The Mac keeps its pairing. To remove it there too, open Paired Phones in Oripane's menu on the Mac, choose this phone and Unpair….",
            confirm = "Remove",
            onConfirm = { confirm = false; onDelete() },
            onDismiss = { confirm = false },
        )
    }
}
