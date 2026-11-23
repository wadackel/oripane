/** The page that adds a Mac by its address, and how a typed address is read. */
package dev.wadackel.oripane

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.Normalizer

/**
 * What the address field holds, as a host name: a Japanese keyboard types `：` and full-width
 * digits, which NFKC folds, and `。` for a period, which it does not.
 */
fun normalizeHost(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFKC).replace('。', '.').trim()

private val portSuffix = Regex(""":\d+$""")

/** An IPv6 literal: bracketed, or hex groups with at least two colons and an optional `%` zone. */
private val ipv6Literal = Regex("""^\[.*|^[0-9A-Fa-f.]*:[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*(%\S*)?$""")

/**
 * Why [host], a normalized field, cannot be connected to as typed, or null. The field takes no port:
 * a host listens on 47800, and only a test launch hook moves it. An IPv6 address is told apart
 * first, since its last group would read as a port, and the phone connects over IPv4 only
 * (`ConnectionController.resolve`).
 */
fun addressProblem(host: String): String? = when {
    ipv6Literal.matches(host) -> "Oripane connects over IPv4; enter an IPv4 address or a name."
    portSuffix.containsMatchIn(host) -> "Leave out the port; Oripane uses 47800."
    else -> null
}

@Composable
fun AddScreen(initial: String, mode: String, error: String?, onChange: (String) -> Unit, onConnect: (host: String, mode: String) -> Unit, onBack: () -> Unit) {
    var host by remember { mutableStateOf(initial) }
    var picked by remember { mutableStateOf(mode) }
    val refused = addressProblem(normalizeHost(host))
    val canConnect = normalizeHost(host).isNotEmpty() && refused == null
    val problem = refused ?: error
    Page("Add a Mac", onBack) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it; onChange(it) },
            label = { Text("IP address or name") },
            singleLine = true,
            isError = problem != null,
            supportingText = problem?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (canConnect) onConnect(normalizeHost(host), picked) }),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Help("A Mac you have not used before pairs on the first connection: check that its dialog shows the same code and click Allow.", Modifier.padding(top = 8.dp))
        SectionLabel("Display mode")
        SegmentedChoices(displayModes, picked) { picked = it }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Connect", enabled = canConnect) { onConnect(normalizeHost(host), picked) }
    }
}
