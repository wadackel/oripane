package dev.wadackel.oripane

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The Mac screen the README's pictures show in place of the decoded video: a desktop drawn from
 * shapes, scaled to whatever box the stream fills.
 *
 * Drawn rather than captured, so no real desktop, its menu bar icons or its notifications can end
 * up in the images, and the pictures come out the same on every run.
 */
@Composable
fun MacDesktop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF1E3A5F), Color(0xFF4B3B78), Color(0xFF8A5A86))))
        val bar = size.height * 0.035f
        drawRect(Color.White.copy(alpha = 0.18f), size = Size(size.width, bar))
        window(Offset(0.08f, 0.12f), Size(0.52f, 0.56f), Color(0xFF1C1F24), lines = 9)
        window(Offset(0.42f, 0.30f), Size(0.48f, 0.50f), Color(0xFFF4F5F7), lines = 7)
        val dockW = size.width * 0.46f
        val dockH = size.height * 0.075f
        val dockTop = size.height - dockH - size.height * 0.02f
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset((size.width - dockW) / 2, dockTop), Size(dockW, dockH), CornerRadius(dockH * 0.3f))
        val icons = 8
        val icon = minOf(dockH * 0.7f, dockW / icons * 0.8f)
        val step = dockW / icons
        val first = (size.width - dockW) / 2 + (step - icon) / 2
        val colours = listOf(0xFF4C8DF6, 0xFF34C759, 0xFFFF9F0A, 0xFFFF453A, 0xFFBF5AF2, 0xFF64D2FF, 0xFFFFD60A, 0xFF8E8E93)
        for (i in 0 until icons) {
            drawRoundRect(Color(colours[i]), Offset(first + step * i, dockTop + (dockH - icon) / 2), Size(icon, icon), CornerRadius(icon * 0.22f))
        }
    }
}

/** A window at [at] of [extent], both as fractions of the screen, with grey bars for lines of text. */
private fun DrawScope.window(at: Offset, extent: Size, ground: Color, lines: Int) {
    val origin = Offset(size.width * at.x, size.height * at.y)
    val box = Size(size.width * extent.width, size.height * extent.height)
    val radius = CornerRadius(box.height * 0.03f)
    drawRoundRect(Color.Black.copy(alpha = 0.35f), origin + Offset(0f, box.height * 0.02f), box, radius)
    drawRoundRect(ground, origin, box, radius)
    val title = box.height * 0.08f
    val dot = title * 0.32f
    listOf(0xFFFF5F57, 0xFFFEBC2E, 0xFF28C840).forEachIndexed { i, c ->
        drawCircle(Color(c), dot / 2, origin + Offset(title * 0.5f + i * dot * 1.6f, title / 2))
    }
    val ink = if (ground.luminance() > 0.5f) Color(0xFF9AA0A8) else Color(0xFF5A606A)
    val lineH = box.height * 0.035f
    for (i in 0 until lines) {
        val w = box.width * (0.35f + 0.5f * ((i * 37) % 11) / 10f)
        drawRoundRect(ink, origin + Offset(box.width * 0.06f, title + lineH * 1.2f + i * lineH * 2.2f), Size(w, lineH), CornerRadius(lineH / 2))
    }
}

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
