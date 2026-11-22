/**
 * The colours taken from the video: a small copy of it is read now and then. The region under each
 * scroll band is averaged and, pushed toward the opposite lightness, becomes the band's dots; the
 * whole frame's average becomes the wash the plates over the video and the Mac's home card carry.
 */
package dev.wadackel.oripane

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The dots' colour on each band, and [frame], the colour of the whole frame as the plates and the
 * home screen's card wash it in: null when the frame has too little colour to show.
 */
data class BandTint(val vertical: Color, val horizontal: Color, val frame: Color? = null) {
    companion object {
        /** Before the first copy: the colour a mid-grey frame would give. */
        val neutral = dotColorFor(128f, 128f, 128f).let { BandTint(it, it) }
    }
}

/**
 * The wash colour of a frame whose average is ([r], [g], [b]), each 0..255: its hue at a set
 * lightness and a saturation kept within bounds, so a pale page and a dark editor both give a colour
 * that reads on the grey surfaces. A frame that is nearly grey, white or black gives null: a grey
 * wash would only muddy the surface.
 */
fun washColorFor(r: Float, g: Float, b: Float): Color? {
    val rn = (r / 255f).coerceIn(0f, 1f)
    val gn = (g / 255f).coerceIn(0f, 1f)
    val bn = (b / 255f).coerceIn(0f, 1f)
    val hi = max(rn, max(gn, bn))
    val lo = min(rn, min(gn, bn))
    val l = (hi + lo) / 2f
    val d = hi - lo
    val sat = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
    if (sat < MIN_WASH_SATURATION || l < 0.04f || l > 0.97f) return null
    val hue = when (hi) {
        rn -> 60f * (((gn - bn) / d) % 6f)
        gn -> 60f * ((bn - rn) / d + 2f)
        else -> 60f * ((rn - gn) / d + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return Color.hsl(hue, (sat * 1.4f).coerceIn(0.3f, 0.55f), 0.58f)
}

/** Below this saturation a frame's average counts as grey. */
const val MIN_WASH_SATURATION = 0.08f

/**
 * The dots for a region whose average is ([r], [g], [b]), each 0..255: the region's own hue, darkened
 * over a light region and lightened over a dark one, so the dots show on it without a colour of
 * their own.
 */
fun dotColorFor(r: Float, g: Float, b: Float): Color {
    val light = (0.299f * r + 0.587f * g + 0.114f * b) / 255f > 0.5f
    fun c(v: Float) = ((if (light) v * 0.38f else v * 1.9f + 40f).coerceIn(0f, 255f)) / 255f
    return Color(c(r), c(g), c(b))
}

/**
 * The average colour of [region] on screen, read from [pixels] (ARGB, [width] by [height]), a copy of
 * the whole video, which shows on screen over [video]. Where the region lies off the video it is over
 * the black bars, which count as black.
 */
fun averageUnder(pixels: IntArray, width: Int, height: Int, video: StripArea, region: StripArea): Triple<Float, Float, Float> {
    val l = max(region.left, video.left)
    val t = max(region.top, video.top)
    val r = min(region.right, video.right)
    val b = min(region.bottom, video.bottom)
    val regionArea = (region.right - region.left) * (region.bottom - region.top)
    if (r <= l || b <= t || regionArea <= 0f || video.right <= video.left || video.bottom <= video.top) return Triple(0f, 0f, 0f)
    // A region's far edge on a cell boundary ends in the cell before it, not the one it starts.
    fun px(x: Float) = ((x - video.left) / (video.right - video.left) * width).toInt().coerceIn(0, width - 1)
    fun pxEnd(x: Float) = (ceil((x - video.left) / (video.right - video.left) * width).toInt() - 1).coerceIn(0, width - 1)
    fun py(y: Float) = ((y - video.top) / (video.bottom - video.top) * height).toInt().coerceIn(0, height - 1)
    fun pyEnd(y: Float) = (ceil((y - video.top) / (video.bottom - video.top) * height).toInt() - 1).coerceIn(0, height - 1)
    var sr = 0L
    var sg = 0L
    var sb = 0L
    var n = 0
    for (y in py(t)..maxOf(py(t), pyEnd(b))) for (x in px(l)..maxOf(px(l), pxEnd(r))) {
        val p = pixels[y * width + x]
        sr += (p shr 16) and 0xff
        sg += (p shr 8) and 0xff
        sb += p and 0xff
        n++
    }
    val cover = (r - l) * (b - t) / regionArea
    return Triple(sr.toFloat() / n * cover, sg.toFloat() / n * cover, sb.toFloat() / n * cover)
}

/**
 * Copies a small picture of [surface] every [INTERVAL_MS] while started, and gives [onTint] the
 * colours under the bands [bands] reports, in [area]'s pixels, and the whole frame's wash colour,
 * which is read with the bands off too. A copy needs a live surface; without one it waits for the
 * next round.
 */
class BandTintSampler(
    private val surface: SurfaceView,
    private val area: View,
    private val bands: () -> ScrollBands?,
    private val size: () -> BandSize,
    private val onTint: (BandTint) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var copying = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sample()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun sample() {
        val b = bands()
        if (copying || !surface.holder.surface.isValid || surface.width == 0 || surface.height == 0) return
        val w = COPY_WIDTH
        val h = max(1, (COPY_WIDTH.toFloat() * surface.height / surface.width).roundToInt())
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val video = videoOnScreen()
        val depth = size().depthDp() * area.resources.displayMetrics.density
        copying = true
        PixelCopy.request(surface, bitmap, { result ->
            copying = false
            if (result == PixelCopy.SUCCESS) {
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                val (fr, fg, fb) = averageUnder(pixels, w, h, video, video)
                val frame = washColorFor(fr, fg, fb)
                if (b == null) {
                    onTint(BandTint.neutral.copy(frame = frame))
                } else {
                    val v = b.vertical
                    val vRegion = if (b.side == ScrollStrip.LEFT) v.copy(right = v.left + depth) else v.copy(left = v.right - depth)
                    val hRegion = b.horizontal.copy(top = b.horizontal.bottom - depth)
                    val (vr, vg, vb) = averageUnder(pixels, w, h, video, vRegion)
                    val (hr, hg, hb) = averageUnder(pixels, w, h, video, hRegion)
                    onTint(BandTint(dotColorFor(vr, vg, vb), dotColorFor(hr, hg, hb), frame))
                }
            }
            bitmap.recycle()
        }, handler)
    }

    /** The video on screen in [area]'s pixels: the surface, scaled and shifted with its zoomed frame. */
    private fun videoOnScreen(): StripArea {
        val s = IntArray(2)
        val a = IntArray(2)
        surface.getLocationOnScreen(s)
        area.getLocationOnScreen(a)
        val frame = surface.parent as? View
        val w = surface.width * (frame?.scaleX ?: 1f)
        val h = surface.height * (frame?.scaleY ?: 1f)
        val x = (s[0] - a[0]).toFloat()
        val y = (s[1] - a[1]).toFloat()
        return StripArea(x, y, x + w, y + h)
    }

    companion object {
        /** Often enough to follow a page that scrolls under the band, seldom enough to cost nothing. */
        const val INTERVAL_MS = 300L
        /** The copy's width in pixels: the colour of a band-sized region needs no more. */
        const val COPY_WIDTH = 48
    }
}
