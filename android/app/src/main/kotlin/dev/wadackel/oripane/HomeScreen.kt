/**
 * The home screen: one large card for the chosen Mac (its state, the display mode, and Connect) and
 * a small tile for every Mac, which picks the card's Mac. Which Macs it lists, in what order, and
 * what each one's state says.
 */
package dev.wadackel.oripane

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One Mac on the home screen: a saved Mac or one found nearby. */
sealed interface HomeEntry {
    /** What the home screen remembers the chosen Mac by. */
    val key: String

    data class Saved(val mac: SavedMac) : HomeEntry {
        override val key get() = "saved:${mac.hostId}"
    }

    data class Found(val mac: Discovered) : HomeEntry {
        override val key get() = "found:${mac.id ?: mac.addr}"
    }
}

/**
 * A connection to [key]'s Mac that failed, why, and what the failure said. [naming], from
 * [ClientState.Failed], says it again with the Mac's name of now.
 */
data class HomeFailure(val key: String, val reason: FailReason, val message: String, val naming: ((String) -> String)? = null) {
    /** What the card of the Mac called [name] says about this failure. */
    fun messageFor(name: String) = naming?.invoke(name) ?: message
}

/** A service whose TXT id matches a saved Mac is that Mac, reached through its own entry. */
private fun unsavedFound(saved: List<SavedMac>, discovered: List<Discovered>) = discovered.filter { d -> saved.none { it.hostId == d.id } }

/** Every Mac in the order of the tiles: the saved ones newest first, then the found ones in the order they were found. */
fun homeEntries(saved: List<SavedMac>, discovered: List<Discovered>): List<HomeEntry> =
    saved.sortedByDescending { it.lastUsed }.map { HomeEntry.Saved(it) } + unsavedFound(saved, discovered).map { HomeEntry.Found(it) }

/** The Mac on the card: the one picked by [selected] while it is listed, else the first tile. */
fun chosenEntry(entries: List<HomeEntry>, selected: String?) = entries.firstOrNull { it.key == selected } ?: entries.firstOrNull()

/**
 * The names on the home screen that more than one Mac carries: those show an address too, since
 * nothing else tells them apart.
 */
fun repeatedNames(saved: List<SavedMac>, discovered: List<Discovered>): Set<String> {
    return (saved.map { it.displayName } + unsavedFound(saved, discovered).map { it.name }).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}

/** The address a saved Mac's card shows when its name repeats: where it was last reached. */
val SavedMac.shownAddress get() = lanAddrs.firstOrNull() ?: tailnet.firstOrNull()

fun lastUsedText(time: Long, now: Long = System.currentTimeMillis()): String {
    if (time <= 0) return ""
    val a = Calendar.getInstance().apply { timeInMillis = time }
    val b = Calendar.getInstance().apply { timeInMillis = now }
    val today = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return if (today) "Today " + SimpleDateFormat("HH:mm", Locale.US).format(Date(time)) else SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(time))
}

val HomeEntry.name get() = when (this) {
    is HomeEntry.Saved -> mac.displayName
    is HomeEntry.Found -> mac.name
}

/**
 * The icon for a Mac by its name: a laptop for a MacBook, a display for the rest. The host does not
 * say what model it runs on, and the name is what the user sees it by.
 */
@DrawableRes
fun macKindIcon(name: String) = if (name.contains("MacBook", ignoreCase = true)) R.drawable.ic_laptop else R.drawable.ic_mac

/** What the home screen can say about a Mac without connecting to it. */
enum class MacState(val color: StatusColor) {
    /** Bonjour found it on this network. */
    NEARBY(StatusColor.GREEN),
    /** Saved, not seen on this network: it may still be reached at an address it had or over Tailscale. */
    AWAY(StatusColor.GREY),
    /** The last connection to it could not reach it. One the Mac refused keeps the state it had. */
    UNREACHABLE(StatusColor.GREY),
    /** Found on this network and not paired yet. */
    NOT_ADDED(StatusColor.YELLOW),
}

fun macState(entry: HomeEntry, discovered: List<Discovered>, failure: HomeFailure?) = when {
    failure?.key == entry.key && failure.reason == FailReason.CONNECT_FAILED -> MacState.UNREACHABLE
    entry is HomeEntry.Found -> MacState.NOT_ADDED
    discovered.any { it.id == (entry as HomeEntry.Saved).mac.hostId } -> MacState.NEARBY
    else -> MacState.AWAY
}

/** The line under the name on the card. */
fun macStateText(entry: HomeEntry, state: MacState, repeated: Set<String>): String {
    val addr = when (entry) {
        is HomeEntry.Saved -> entry.mac.shownAddress
        is HomeEntry.Found -> entry.mac.addr
    }?.takeIf { entry.name in repeated }
    val parts = when (state) {
        MacState.NEARBY -> listOf("Ready", "LAN")
        MacState.AWAY -> {
            val mac = (entry as HomeEntry.Saved).mac
            listOfNotNull("Tailscale".takeIf { mac.tailnet.isNotEmpty() }, lastUsedText(mac.lastUsed).takeIf { it.isNotEmpty() }?.let { "last used $it" })
        }
        MacState.UNREACHABLE -> listOf("Not reachable")
        MacState.NOT_ADDED -> listOf("Nearby", "not added yet")
    }
    return (parts + listOfNotNull(addr)).joinToString(" · ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    saved: List<SavedMac>,
    discovered: List<Discovered>,
    selected: String?,
    mode: String,
    failure: HomeFailure?,
    onSelect: (HomeEntry) -> Unit,
    onMode: (String) -> Unit,
    onConnect: (HomeEntry) -> Unit,
    onDetail: (SavedMac) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
) {
    LaunchedEffect(Unit) { log("saved count=${saved.size}") }
    val entries = homeEntries(saved, discovered)
    val chosen = chosenEntry(entries, selected)
    val repeated = repeatedNames(saved, discovered)
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth >= WIDE_SETTINGS
        val side = if (wide) Space.l else Space.m
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            HomeHeader(onAdd, onSettings, Modifier.padding(start = side, end = side - (Sizes.primary - Sizes.secondary) / 2))
            if (failure != null && entries.none { it.key == failure.key }) {
                Text(failure.message, style = Type.body, color = Palette.danger, modifier = Modifier.padding(horizontal = side + 4.dp, vertical = Space.xs))
            }
            if (chosen == null) {
                EmptyHome(onAdd)
                return@Column
            }
            val board = @Composable { m: Modifier ->
                val state = macState(chosen, discovered, failure)
                MacCard(chosen, state, macStateText(chosen, state, repeated), failure?.takeIf { it.key == chosen.key }?.messageFor(chosen.name), mode, onMode, onConnect, onDetail, m)
            }
            val tiles = @Composable { m: Modifier ->
                MacTiles(entries, chosen, discovered, failure, wide, onSelect, onDetail, onAdd, m)
            }
            if (wide) {
                Row(Modifier.padding(start = side, end = side, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    board(Modifier.width(380.dp))
                    tiles(Modifier.weight(1f))
                }
            } else {
                board(Modifier.padding(start = side, end = side, top = 4.dp))
                tiles(Modifier.padding(start = side, end = side, top = Space.m))
            }
        }
    }
}

/** The mark and the wordmark, then Add a Mac and Settings as round grey buttons. */
@Composable
private fun HomeHeader(onAdd: () -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.oripane_symbol), contentDescription = null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(Space.xs))
        Text("Oripane", style = Type.wordmark, color = Palette.text, modifier = Modifier.weight(1f))
        RoundButton(R.drawable.ic_plus, "Add a Mac", onClick = onAdd)
        RoundButton(R.drawable.ic_settings, "Settings", onClick = onSettings)
    }
}

/**
 * The chosen Mac's card. Its ground takes the colour of the last frame seen from this Mac, washed in
 * from the corner; a Mac never streamed from stays grey. Under the name: the display mode and the
 * white round Connect, or, after a connection to it failed ([failure]), why and a quiet Try again,
 * or, for one not paired yet, what pairing does and a white round Pair.
 */
@Composable
private fun MacCard(
    entry: HomeEntry,
    state: MacState,
    stateText: String,
    failure: String?,
    mode: String,
    onMode: (String) -> Unit,
    onConnect: (HomeEntry) -> Unit,
    onDetail: (SavedMac) -> Unit,
    modifier: Modifier,
) {
    val tint = (entry as? HomeEntry.Saved)?.mac?.tint?.let { Color(it) }
    val shape = RoundedCornerShape(Radius.surface)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Palette.surface1).then(if (tint != null) Modifier.background(videoWash(tint, 0.38f, 0.75f)) else Modifier).padding(Space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Box(Modifier.size(Sizes.primary).clip(RoundedCornerShape(Radius.inner)).background(Palette.well), contentAlignment = Alignment.Center) {
                Icon(painterResource(macKindIcon(entry.name)), contentDescription = null, tint = Palette.icon, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = Type.title, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
                    StateDot(state)
                    Text(stateText, style = Type.note, color = Palette.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (entry is HomeEntry.Saved) RoundButton(R.drawable.ic_more, "Details", background = Palette.well) { onDetail(entry.mac) }
        }
        Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            when {
                failure != null -> {
                    CardNote(failure, Modifier.weight(1f))
                    RoundButton(R.drawable.ic_retry, "Try again", primary = true, background = Palette.surface3, tint = Palette.text) { onConnect(entry) }
                }
                state == MacState.NOT_ADDED -> {
                    CardNote("Found on this Wi-Fi. Pairing shows the same code on both screens.", Modifier.weight(1f))
                    RoundButton(R.drawable.ic_plus, "Pair", primary = true) { onConnect(entry) }
                }
                else -> {
                    DisplayModeControl(mode, onMode, Modifier.weight(1f))
                    RoundButton(R.drawable.ic_connect, "Connect", primary = true) { onConnect(entry) }
                }
            }
        }
    }
}

@Composable
private fun CardNote(text: String, modifier: Modifier) {
    Text(text, fontFamily = Inter, fontSize = 12.5.sp, lineHeight = 18.sp, color = Palette.textMuted, modifier = modifier)
}

@Composable
private fun StateDot(state: MacState) {
    Box(Modifier.size(7.dp).background(state.color.color(), CircleShape))
}

/**
 * The display mode on the card: the three modes as segments as tall as the Connect button beside
 * them, each a small drawing of the phone and the Mac with its name under it.
 */
@Composable
private fun DisplayModeControl(mode: String, onMode: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier.height(Sizes.primary).clip(RoundedCornerShape(Radius.inner)).background(Palette.well).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        displayModes.forEach { (key, label) ->
            val on = key == mode
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Radius.innermost))
                    .background(if (on) Palette.chosen else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onMode(key) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val ink = if (on) Palette.text else Palette.textMuted
                Canvas(Modifier.size(16.dp, 10.2.dp)) { modeArt(key, ink) }
                Text(label, fontFamily = Inter, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, color = ink, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * A display mode drawn on a 44 by 28 grid: the Mac's screen on the left, the phone on the right,
 * and what each shows. Extend: the phone is a screen of its own. Mirror: both show the Mac's
 * picture, letterboxed on the phone. Match: the Mac's picture takes the phone's shape.
 */
private fun DrawScope.modeArt(mode: String, ink: Color) {
    val u = size.width / 44f
    val line = 1.3f * u
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Color, stroke: Boolean = false) =
        drawRoundRect(color, Offset(x * u, y * u), androidx.compose.ui.geometry.Size(w * u, h * u), CornerRadius(r * u), style = if (stroke) Stroke(line) else androidx.compose.ui.graphics.drawscope.Fill)
    rect(1f, 3f, 26f, 18f, 2.5f, Color.White.copy(alpha = 0.08f))
    rect(1f, 3f, 26f, 18f, 2.5f, ink.copy(alpha = ink.alpha * 0.55f), stroke = true)
    drawLine(ink.copy(alpha = ink.alpha * 0.55f), Offset(10f * u, 25f * u), Offset(18f * u, 25f * u), line)
    when (mode) {
        "extend" -> rect(32f, 5f, 11f, 19f, 2.2f, ink.copy(alpha = ink.alpha * 0.9f))
        "mirror" -> {
            rect(5f, 7f, 18f, 10f, 1f, ink.copy(alpha = ink.alpha * 0.45f))
            rect(32f, 5f, 11f, 19f, 2.2f, ink, stroke = true)
            rect(33.5f, 11.5f, 8f, 5f, 0.6f, ink.copy(alpha = ink.alpha * 0.9f))
        }
        else -> {
            rect(9.5f, 4.5f, 9f, 15f, 1f, ink.copy(alpha = ink.alpha * 0.45f))
            rect(32f, 5f, 11f, 19f, 2.2f, ink, stroke = true)
            rect(33.5f, 6.5f, 8f, 16f, 1.2f, ink.copy(alpha = ink.alpha * 0.9f))
        }
    }
}

/**
 * Every Mac as a tile, two to a row on the cover screen and three on the inner one, so a name of
 * some length fits beside its dot: its icon, then its state's dot before its name. The chosen
 * one has a white ring; one that could not be reached is faded. A tap picks the card's Mac, a long
 * press on a saved one opens its details. The last tile adds a Mac.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MacTiles(
    entries: List<HomeEntry>,
    chosen: HomeEntry,
    discovered: List<Discovered>,
    failure: HomeFailure?,
    wide: Boolean,
    onSelect: (HomeEntry) -> Unit,
    onDetail: (SavedMac) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier,
) {
    val height = if (wide) 96.dp else 76.dp
    val cells: List<@Composable (Modifier) -> Unit> = entries.map<HomeEntry, @Composable (Modifier) -> Unit> { e ->
        { m: Modifier ->
            val state = macState(e, discovered, failure)
            val on = e.key == chosen.key
            val shape = RoundedCornerShape(Radius.surface)
            val press = Modifier.combinedClickable(
                role = Role.RadioButton,
                onClick = { onSelect(e) },
                onLongClick = (e as? HomeEntry.Saved)?.let { s -> { onDetail(s.mac) } },
            )
            Column(
                m.height(height).clip(shape).background(Palette.surface1).then(if (on) Modifier.border(1.5.dp, Palette.lead, shape) else Modifier).then(press).padding(horizontal = Space.xs)
                    .alpha(if (state == MacState.UNREACHABLE) 0.5f else 1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(painterResource(macKindIcon(e.name)), contentDescription = null, tint = Palette.icon, modifier = Modifier.size(18.dp))
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StateDot(state)
                    Text(e.name, fontFamily = Inter, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    } + listOf<@Composable (Modifier) -> Unit>({ m: Modifier ->
        val shape = RoundedCornerShape(Radius.surface)
        Column(
            m.height(height).clip(shape).border(1.dp, Palette.surface3, shape).combinedClickable(role = Role.Button, onClick = onAdd),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(22.dp).background(Palette.surface2, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = Palette.icon, modifier = Modifier.size(14.dp))
            }
            Text("Add", fontFamily = Inter, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = Palette.textMuted, modifier = Modifier.padding(top = Space.xs))
        }
    })
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        val columns = if (wide) 3 else 2
        cells.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                row.forEach { it(Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** No Mac saved or found yet: the mark, what to do on the Mac, and adding one by its address. */
@Composable
private fun ColumnScope.EmptyHome(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(100.dp).border(1.dp, Palette.surface2, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(64.dp).background(Palette.surface1, CircleShape), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.oripane_symbol), contentDescription = null, modifier = Modifier.size(26.dp))
            }
        }
        Text("Looking for Macs nearby", style = Type.title, color = Palette.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 56.dp))
        Text(
            "Open Oripane on your Mac. It appears here on the same Wi-Fi, or add it by address over Tailscale.",
            style = Type.body,
            color = Palette.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Space.s).widthIn(max = 360.dp),
        )
        SecondaryButton("Add by address", R.drawable.ic_plus, Modifier.padding(top = Space.l), onClick = onAdd)
    }
}
