/**
 * The trackpad mode's scroll bands, as drawn over the stream: where they sit on the screen, and the
 * halftone dot fields that mark them, swelling under the finger and reaching past a band's ends
 * while it is held. The touches themselves are the gesture recognizer's.
 */
package dev.wadackel.oripane

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Where the bands are, in the stream screen's pixels: [vertical] down [side] within the full-height
 * [lane] it may be moved along, and [horizontal] along the bottom. Each is the band's touch area; its
 * dots grow from the screen edge it touches.
 */
data class ScrollBands(val side: ScrollStrip, val lane: StripArea, val vertical: StripArea, val horizontal: StripArea)

/** A finger on a band, in the stream screen's pixels: on the bottom band when [horizontal]. */
data class BandTouch(val horizontal: Boolean, val x: Float, val y: Float)

/** The side band's length; Android excludes at most 200 dp of an edge from its Back gesture. */
const val VERTICAL_BAND_DP = 168f
const val HORIZONTAL_BAND_DP = 200f

/** The dot field of one size: how deep it reaches from the edge, the dots' pitch and their full radius, in dp. */
private data class DotField(val depth: Float, val pitch: Float, val radius: Float)

/** How deep a band's dots reach in from the edge, in dp. */
fun BandSize.depthDp() = field().depth

private fun BandSize.field() = when (this) {
    BandSize.SMALL -> DotField(depth = 14f, pitch = 4.2f, radius = 1.25f)
    BandSize.MEDIUM -> DotField(depth = 19f, pitch = 5f, radius = 1.5f)
}

/** How far past each end the dots reach while the band is held, so a swell near an end is never cut off. */
private const val REACH_DP = 70f
/** The swell's spread along the band, in dp. */
private const val SWELL_DP = 22f

/**
 * Both bands' dots. [touch] is the finger on a band, if any; [relocatingY] the side band's centre
 * while a held finger moves it. The dot colour comes from [tint], taken from the frame under each band.
 */
@Composable
fun HalftoneBands(bands: ScrollBands, size: BandSize, touch: BandTouch?, relocatingY: Float?, tint: BandTint, modifier: Modifier = Modifier) {
    // Spatial springs as Material's standard motion has them: a little give, no wobble.
    val heldV by animateFloatAsState(if (touch != null && !touch.horizontal) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 1400f), label = "bandV")
    val heldH by animateFloatAsState(if (touch?.horizontal == true) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = 1400f), label = "bandH")
    val field = size.field()
    Canvas(modifier) {
        val v = bands.vertical
        val centreY = relocatingY?.coerceIn(bands.lane.top, bands.lane.bottom) ?: ((v.top + v.bottom) / 2)
        val edgeX = if (bands.side == ScrollStrip.LEFT) v.left else v.right
        val inwardX = if (bands.side == ScrollStrip.LEFT) 1f else -1f
        val fingerV = touch?.takeIf { !it.horizontal }?.let { it.y - centreY }
        dotField(field, VERTICAL_BAND_DP, heldV, fingerV, tint.vertical) { along, depth -> Offset(edgeX + inwardX * depth, centreY + along) }
        val h = bands.horizontal
        val centreX = (h.left + h.right) / 2
        val fingerH = touch?.takeIf { it.horizontal }?.let { it.x - centreX }
        dotField(field, HORIZONTAL_BAND_DP, heldH, fingerH, tint.horizontal) { along, depth -> Offset(centreX + along, h.bottom - depth) }
    }
}

/**
 * One band's dots, laid out in dp along the band and in from its edge and placed by [at] in pixels.
 * Resting, the dots fade toward the band's ends and its inner side; [held] swells them round the
 * finger, [finger] dp from the band's centre, and grows the field past the ends to take the swell.
 */
private fun DrawScope.dotField(f: DotField, length: Float, held: Float, finger: Float?, color: Color, at: (Float, Float) -> Offset) {
    val d = density
    val half = length / 2
    val q = held.coerceIn(0f, 1f)
    val swellAt = finger?.let { (it / d).coerceIn(-(half - 10f), half - 10f) }
    val reach = q * REACH_DP
    var column = 0
    while (column * f.pitch < f.depth + 4f) {
        val depth = 2f + column * f.pitch
        var along = -half - reach
        while (along <= half + reach) {
            val fall = max(0f, 1f - depth / f.depth) * max(0f, 1f - (along / half).pow(2))
            val swell = if (swellAt == null) 0f else q * exp(-((along - swellAt) / SWELL_DP).pow(2))
            val r = f.radius * fall.pow(0.75f) + swell * f.radius * 0.95f * max(0.35f, 1f - depth / (f.depth + 6f))
            if (r >= 0.22f) {
                val a = min(1f, (0.4f + 0.4f * min(1f, swell) + 0.06f * q) * 1.7f)
                drawCircle(color.copy(alpha = a), r * d, at(along * d, depth * d))
            }
            along += f.pitch
        }
        column++
    }
}
