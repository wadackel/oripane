/**
 * The parts over the video while streaming: the plate they sit on, the menu button, the status
 * corner with its notes and stats, and the AWDL banner. The scroll bands are in ScrollBands.kt.
 */
package dev.wadackel.oripane

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

fun MenuCorner.alignment() = when (this) {
    MenuCorner.TOP_START -> Alignment.TopStart
    MenuCorner.TOP_END -> Alignment.TopEnd
    MenuCorner.BOTTOM_START -> Alignment.BottomStart
    MenuCorner.BOTTOM_END -> Alignment.BottomEnd
}

/**
 * The grey plate every part over the video sits on: the page's surface grey, opaque so it reads on a
 * light screen and a dark one alike, a 1 px light along its top edge and a soft shadow. [tint], the
 * colour of the video under it, washes in from the top-left corner. No blur: the video is a
 * SurfaceView, which Compose cannot see through.
 */
internal fun Modifier.plate(shape: Shape, tint: Color? = null, ground: Color = Palette.surface1, shadow: Dp = 10.dp) =
    this.shadow(shadow, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
        .clip(shape)
        .background(ground, shape)
        .then(if (tint == null) Modifier else Modifier.background(videoWash(tint, 0.34f, 0.7f), shape))
        .drawWithContent {
            drawContent()
            val outline = shape.createOutline(size, layoutDirection, this)
            val line = 1.dp.toPx()
            clipRect(bottom = line * 1.5f) { drawOutline(outline, Palette.highlight, style = Stroke(line * 2)) }
        }

/**
 * The colour of the video washing into a surface from its top-left: [strength] of [tint] at the
 * corner, gone by [reach] of the way across.
 */
internal fun videoWash(tint: Color, strength: Float, reach: Float) = object : ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size): Shader {
        // CSS's 150°: from the top-left corner, 30° off straight down; the line spans the box along it.
        val length = size.width * 0.5f + size.height * 0.866f
        val end = Offset(0.5f, 0.866f) * (length * reach)
        return LinearGradientShader(Offset.Zero, end, listOf(tint.copy(alpha = strength), Color.Transparent))
    }
}

/**
 * Takes the touches on a part over the video without acting on them: the stream's touch area fills
 * the screen under every overlay, and a tap on the part would otherwise click the Mac.
 */
internal fun Modifier.blocksTouches() = pointerInput(Unit) {}

/** Overlay text keeps its own line height: the theme's body style would add a 24 sp line. */
internal fun lineFor(size: TextUnit) = size * 1.3f

/**
 * The menu button, a 44 dp grey plate. A long press lifts it to follow the finger; on release it
 * snaps to the corner nearest its centre within a parent of [parentWidth] by [parentHeight] pixels.
 */
@Composable
fun MenuButton(corner: MenuCorner, parentWidth: Float, parentHeight: Float, onTap: () -> Unit, onCorner: (MenuCorner) -> Unit, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var sizePx by remember { mutableStateOf(0f) }
    Box(
        modifier
            .padding(16.dp)
            .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .onGloballyPositioned { origin = it.positionInParent() - drag; sizePx = it.size.width.toFloat() }
            .size(Sizes.primary)
            .plate(CircleShape)
            .pointerInput(corner, parentWidth, parentHeight) {
                detectDragGesturesAfterLongPress(
                    onDrag = { change, d -> change.consume(); drag += d },
                    onDragEnd = {
                        val c = origin + drag + Offset(sizePx / 2, sizePx / 2)
                        drag = Offset.Zero
                        onCorner(MenuCorner.nearest(c.x, c.y, parentWidth, parentHeight))
                    },
                    onDragCancel = { drag = Offset.Zero },
                )
            }
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_menu), contentDescription = "Menu", tint = Palette.icon, modifier = Modifier.size(20.dp))
    }
}

internal fun StatusColor.color() = when (this) {
    StatusColor.GREY -> Palette.statusUnknown
    StatusColor.YELLOW -> Palette.statusWarn
    StatusColor.GREEN -> Palette.statusGood
}

/**
 * The status corner: a 22 dp plate with a 7 dp dot in the status colour, shown while [showDot]. When
 * [note] has something to say it opens into a pill with the message beside the dot, and closes back
 * to the dot when the note goes; [waiting] makes the dot pulse (reconnecting). A tap on the dot shows
 * the detailed stats ([details], when [expanded]); the AWDL mark sits beside it while AWDL is on.
 */
@Composable
fun StatusCorner(
    showDot: Boolean,
    color: StatusColor,
    note: String?,
    waiting: Boolean,
    expanded: Boolean,
    details: (@Composable () -> Unit)?,
    awdlMark: Boolean,
    onToggle: () -> Unit,
    onMark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(12.dp).blocksTouches(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (expanded && details != null) {
            Box(Modifier.clickable(onClick = onToggle)) { details() }
        } else if (showDot || note != null) {
            val pulse = if (!waiting) 1f else {
                val t = rememberInfiniteTransition(label = "wait")
                t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "dot").value
            }
            // A bigger touch target than the 22 dp plate it draws.
            Box(Modifier.heightIn(min = 32.dp).clickable(onClick = onToggle), contentAlignment = Alignment.CenterStart) {
                Row(
                    Modifier.animateContentSize(spring(dampingRatio = 0.9f, stiffness = 600f)).height(if (note != null) 28.dp else 22.dp).widthIn(min = 22.dp).plate(CircleShape, shadow = 6.dp)
                        .padding(start = if (note != null) 9.dp else 0.dp, end = if (note != null) 10.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (note != null) Arrangement.Start else Arrangement.Center,
                ) {
                    Box(Modifier.size(7.dp).graphicsLayer { alpha = pulse }.background(color.color(), CircleShape))
                    if (note != null) {
                        Text(
                            note,
                            color = Palette.text,
                            style = Type.note.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 7.dp).widthIn(max = 260.dp),
                        )
                    }
                }
            }
        }
        if (awdlMark) {
            Row(
                Modifier.height(22.dp).plate(CircleShape, shadow = 6.dp).clickable(onClick = onMark).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(6.dp).background(Palette.statusWarn, CircleShape))
                Text("AWDL", color = Palette.text, fontSize = 10.sp, lineHeight = lineFor(10.sp), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** What AWDL does and how to stop it, shown when the AWDL mark is tapped. */
@Composable
fun AwdlBanner(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 8.dp).padding(top = 8.dp).widthIn(max = 560.dp).plate(RoundedCornerShape(Radius.surface)).blocksTouches().padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(7.dp).background(Palette.statusWarn, CircleShape))
        Text(
            "AWDL is on. Turn off Universal Control, AirDrop and Handoff to stop the latency spikes.",
            color = Palette.text,
            fontSize = 13.sp,
            lineHeight = lineFor(13.sp),
            modifier = Modifier.weight(1f, fill = false),
        )
        // Not a TextButton: its 40 dp minimum height and 48 dp touch target would make the banner
        // taller than its text.
        Text(
            "Dismiss",
            color = Palette.text,
            fontSize = 13.sp,
            lineHeight = lineFor(13.sp),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).background(Palette.surface2).clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
fun StatsOverlay(st: Stats, s: ClientState.Streaming, dataSaver: Boolean) {
    Text(
        (if (st.noMedia) "No video from the Mac\n" else "") + "%dx%d %s  %.0f fps%s\ntotal %.1f ms  arrive %.1f ms  decode %.1f ms\nloss %.2f%%  %.1f Mbps  rtt %.1f ms  fec %d  drop %d\n%s".format(
            s.width, s.height, s.mode, st.fps, if (dataSaver) "  data saver" else "", st.totalMs, st.arrivalMs, st.decodeMs, st.lossPercent, st.mbps, st.rttMs, st.recovered, st.dropped, st.decoder,
        ),
        color = Palette.text,
        fontSize = 11.sp,
        lineHeight = 16.5.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.plate(RoundedCornerShape(Radius.inner)).padding(horizontal = 10.dp, vertical = 7.dp),
    )
}
