/**
 * The radial menu over the stream, its plate and the items in its cells, which the radial menu editor shows too, and
 * the toast that names what it sent.
 */
package dev.wadackel.oripane

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Places the content so that its ([alignX], [alignY]) share sits on the parent's point ([x], [y]),
 * kept inside the parent across. It takes the parent's whole area, so siblings each place themselves.
 */
private fun Modifier.placedAt(x: Float, y: Float, alignX: Float = 0.5f, alignY: Float = 0.5f) = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(c.maxWidth, c.maxHeight) {
        p.place((x - p.width * alignX).roundToInt().coerceIn(0, (c.maxWidth - p.width).coerceAtLeast(0)), (y - p.height * alignY).roundToInt())
    }
}

/**
 * What a radial menu item shows in its circle of [circle] across: the icon, or the keys of a custom
 * shortcut shown as text, shrunk until even ⌃⌥⇧⌘F12 fits on one line. [description] is read out
 * for it; null where a name next to it says it already.
 */
@Composable
private fun RadialEntryMark(entry: RadialEntry, circle: Dp, tint: Color, description: String?, iconSize: Dp = circle * 0.53f) {
    val icon = when (entry) {
        is RadialEntry.Builtin -> entry.item.icon
        is RadialEntry.Custom -> (entry.look as? RadialLook.Icon)?.icon?.res
    }
    if (icon != null) {
        Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    } else {
        BasicText(
            entry.combo.orEmpty(),
            style = TextStyle(color = tint, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            maxLines = 1,
            softWrap = false,
            // The default floor of 12 sp would leave seven keys wider than the circle.
            autoSize = TextAutoSize.StepBased(minFontSize = 3.sp, maxFontSize = (circle.value * 0.36f).sp, stepSize = 0.25.sp),
            modifier = Modifier.width(circle * 0.74f).then(if (description == null) Modifier.clearAndSetSemantics {} else Modifier.semantics { contentDescription = description }),
        )
    }
}

/** What TalkBack reads for an item: a custom shortcut's name and keys spelt out, or the item's name. */
fun RadialEntry.spokenName() = (this as? RadialEntry.Custom)?.spoken ?: label

/**
 * An entry in the settings' lists: the circle of [size], white when [on]. [description]
 * is null where the name is written next to the circle, so TalkBack does not read it twice.
 */
@Composable
fun RadialEntryCircle(entry: RadialEntry, size: Dp, on: Boolean, modifier: Modifier = Modifier, description: String? = entry.spokenName()) {
    Box(
        modifier
            .size(size)
            .background(if (on) Palette.lead else Palette.surface2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        RadialEntryMark(entry, size, if (on) Palette.onLead else Palette.icon, description)
    }
}

/** The plate's corners and its cells': concentric, the cell's radius plus the plate's padding. */
private const val CELL_RADIUS = 12f
private const val PLATE_RADIUS = CELL_RADIUS + RadialSize.PAD

/**
 * The menu's plate centred on [center], in pixels: eight cells round the logo holding [entries], one
 * per cell, the [selected] cell filled and the others dimmed, and a small mark in each empty cell.
 * [show] carries the menu's opening. A plate that only previews a list written beside it is not
 * [readable]. [tint], the colour of the video under the menu, washes in from the plate's corner.
 */
@Composable
fun RadialPlate(entries: List<RadialEntry?>, selected: Int?, center: Offset, show: Float = 1f, readable: Boolean = true, tint: Color? = null) {
    // Spatial springs as Material's standard motion has them, as the scroll bands use.
    val lit = radialCells.indices.map { cell ->
        animateFloatAsState(if (entries[cell] != null && cell == selected) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 1400f), label = "cell$cell")
    }
    Box(
        Modifier
            .placedAt(center.x, center.y)
            .size(RadialSize.PLATE.dp)
            .graphicsLayer {
                scaleX = 0.86f + 0.14f * show
                scaleY = scaleX
                alpha = show.coerceIn(0f, 1f)
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val plate = RadialSize.PLATE.dp.toPx()
            val radius = PLATE_RADIUS.dp.toPx()
            drawIntoCanvas {
                val paint = Paint().asFrameworkPaint().apply {
                    isAntiAlias = true
                    color = Palette.surface1.toArgb()
                    setShadowLayer(15.dp.toPx(), 0f, 10.dp.toPx(), Color.Black.copy(alpha = 0.35f).toArgb())
                }
                it.nativeCanvas.drawRoundRect(0f, 0f, plate, plate, radius, radius, paint)
            }
            if (tint != null) drawRoundRect(videoWash(tint, 0.34f, 0.7f), cornerRadius = CornerRadius(radius))
            val line = 1.dp.toPx()
            clipRect(bottom = line * 1.5f) { drawRoundRect(Palette.highlight, cornerRadius = CornerRadius(radius), style = Stroke(line * 2)) }
            val mid = Offset(plate / 2, plate / 2)
            val pitch = RadialSize.PITCH.dp.toPx()
            radialCells.forEachIndexed { cell, (col, row) ->
                val at = mid + Offset(col * pitch, row * pitch)
                if (entries[cell] == null) {
                    val m = 6.dp.toPx()
                    drawRoundRect(Palette.text.copy(alpha = 0.16f), at - Offset(m / 2, m / 2), Size(m, m), CornerRadius(2.dp.toPx()))
                    return@forEachIndexed
                }
                val v = lit[cell].value.coerceIn(0f, 1f)
                if (v > 0.01f) {
                    val s = RadialSize.CELL.dp.toPx() * (0.9f + 0.1f * v)
                    drawRoundRect(Palette.lead.copy(alpha = v), at - Offset(s / 2, s / 2), Size(s, s), CornerRadius(CELL_RADIUS.dp.toPx() * s / RadialSize.CELL.dp.toPx()))
                }
            }
        }
        Image(painterResource(R.drawable.oripane_symbol), contentDescription = null, modifier = Modifier.align(Alignment.Center).size(26.dp))
        radialCells.forEachIndexed { cell, (col, row) ->
            val entry = entries[cell] ?: return@forEachIndexed
            val v = lit[cell].value
            val tint = when {
                v > 0.5f -> Palette.onLead
                selected != null -> Palette.icon.copy(alpha = 0.5f)
                else -> Palette.icon
            }
            val corner = RadialSize.PLATE / 2 - RadialSize.CELL / 2
            Box(
                Modifier.offset((corner + col * RadialSize.PITCH).dp, (corner + row * RadialSize.PITCH).dp).size(RadialSize.CELL.dp),
                contentAlignment = Alignment.Center,
            ) {
                RadialEntryMark(entry, 46.dp, tint, if (readable) entry.spokenName() else null, iconSize = (24f + 2f * v.coerceIn(0f, 1f)).dp)
            }
        }
    }
}

/** A combination on key caps, a modifier on each and the key on the last, as [keycapsOf] splits it: on the white name tag. */
@Composable
private fun Keycaps(combo: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        keycapsOf(combo).forEach { key ->
            Box(
                Modifier.height(24.dp).widthIn(min = 24.dp).background(KeyOnTag, CircleShape).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(key, color = Palette.onLead, fontSize = 12.sp, lineHeight = lineFor(12.sp), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A key cap on the white name tag: a step darker than the tag. */
private val KeyOnTag = Color(0xFFDADADF)

/**
 * The radial menu over the stream: the plate with the items in its cells, opaque enough to read on
 * any screen without darkening the video, and the selected item's name and keys above it. While the finger that opened it is down, the
 * touch area keeps that finger and this only draws; left open ([RadialMenuState.sticky]), after a
 * lift without a pick or a pick that keeps the menu open, it takes the next tap itself, so the tap
 * never reaches the Mac.
 */
@Composable
fun RadialMenuOverlay(state: RadialMenuState, tint: Color?, onPick: (Int) -> Unit, onClose: (String) -> Unit) {
    BackHandler { onClose("back") }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    // Material's expressive spatial spring: the plate settles with a little give.
    val show by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 380f), label = "show")
    val fade = show.coerceIn(0f, 1f)
    val taps = if (!state.sticky) Modifier else Modifier.pointerInput(state) {
        detectTapGestures { p ->
            val cell = state.cellAt(p.x, p.y, RadialSize.PITCH.dp.toPx(), RadialSize.PLATE.dp.toPx() / 2)
            // An empty cell is still the plate: it takes the tap and closes nothing.
            if (cell == null) onClose("outside") else state.placeIn(cell)?.let(onPick)
        }
    }
    val density = LocalDensity.current
    val center = Offset(state.centerX, state.centerY)
    Box(Modifier.fillMaxSize().then(taps)) {
        RadialPlate(state.entries, state.selected, center, show, tint = tint)
        val selected = state.selected?.let(state.entries::getOrNull)
        if (selected != null || state.sticky) {
            val edge = with(density) { (RadialSize.PLATE / 2 + 12).dp.toPx() }
            val room = with(density) { (36 + 8).dp.toPx() }
            val above = state.centerY - edge
            val place = if (above >= room) Modifier.placedAt(center.x, above, alignY = 1f) else Modifier.placedAt(center.x, state.centerY + edge, alignY = 0f)
            // The selected item's name on a white tag, as white marks what is chosen everywhere on the stream.
            val tag = if (selected != null) Modifier.plate(CircleShape, ground = Palette.lead) else Modifier.plate(CircleShape)
            Row(
                place.graphicsLayer { alpha = fade }.height(36.dp).then(tag).padding(start = 14.dp, end = if (selected?.combo?.takeIf { it != selected.label } != null) 6.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selected != null) {
                    Text(selected.label, color = Palette.onLead, fontSize = 14.sp, lineHeight = lineFor(14.sp), fontWeight = FontWeight.SemiBold)
                    // A custom shortcut without a name is named by its keys already.
                    selected.combo?.takeIf { it != selected.label }?.let { Keycaps(it) }
                } else {
                    Text("Tap an item, or outside to close", color = Palette.textMuted, fontSize = 13.sp, lineHeight = lineFor(13.sp))
                }
            }
        }
    }
}

/**
 * What the radial menu just sent, named for a moment at the bottom of the screen. It takes no
 * touches: a tap there goes to the Mac as anywhere else, or to the ring when it stays open.
 */
@Composable
fun RadialToast(entry: RadialEntry, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(if (shown) 1f else 0f, label = "toast")
    Row(
        modifier.padding(bottom = 24.dp).graphicsLayer { alpha = fade }.plate(CircleShape).padding(start = 7.dp, top = 7.dp, end = 14.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // White as the plate's selected cell is: on the menu only the logo in the middle carries the brand.
        Box(Modifier.size(28.dp).background(Palette.lead, CircleShape), contentAlignment = Alignment.Center) {
            RadialEntryMark(entry, 28.dp, Palette.onLead, null, iconSize = 16.dp)
        }
        Text(entry.label, color = Palette.text, fontSize = 13.sp, lineHeight = lineFor(13.sp), fontWeight = FontWeight.SemiBold)
    }
}
