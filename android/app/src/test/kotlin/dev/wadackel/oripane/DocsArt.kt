package dev.wadackel.oripane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.PathParser

/**
 * The line drawing at the top of the README, an open Fold in front of a faint MacBook with the
 * same desktop on both, and the social preview built around it.
 *
 * Drawn on a canvas rather than taken from the app's screens: at the size the README shows it, a
 * real page reads as noise, and the drawing has to say only that the Mac is on the Fold. The
 * geometry is in units of a 1000 × 470 drawing.
 */
internal object DocsArt {
    /** The ink and fills of one GitHub theme; [page] fills the windows so they hide what is behind. */
    data class Theme(val ink: Int, val page: Int, val accent: Int)

    val light = Theme(ink = 0xFF24292F.toInt(), page = 0xFFFFFFFF.toInt(), accent = 0xFF07B086.toInt())
    val dark = Theme(ink = 0xFFD1D9E0.toInt(), page = 0xFF0D1117.toInt(), accent = 0xFF18E2B0.toInt())

    private const val W = 1000f
    private const val H = 470f
    private const val STROKE = 2.5f

    private val mac = RectF(60f, 30f, 660f, 402f)
    private val macScreen = RectF(mac.left + 16f, mac.top + 16f, mac.right - 16f, mac.bottom - 18f)
    private val fold = RectF(520f, 132f, 920f, 444f)
    private val foldScreen = RectF(fold.left + 13f, fold.top + 13f, fold.right - 13f, fold.bottom - 13f)

    /** The Mac's 16:10 picture on the Fold's screen, between the bars Mirror leaves. */
    private val mirrored = (foldScreen.width() / 1.6f).let { h ->
        val top = foldScreen.top + (foldScreen.height() - h) / 2
        RectF(foldScreen.left, top, foldScreen.right, top + h)
    }

    /** Grey nudged 11 % toward the mark's green, so the desktop belongs to the app without turning teal. */
    private val wallpaper = intArrayOf(mix(0xFF8C959F.toInt(), 0xFF18E2B0.toInt(), 0.11f), mix(0xFF6E7781.toInt(), 0xFF18E2B0.toInt(), 0.11f))

    /** The drawing alone, on a transparent ground, [width] pixels wide. */
    fun hero(theme: Theme, width: Int = 1600): Bitmap {
        val scale = width / W
        val out = Bitmap.createBitmap(width, Math.round(H * scale), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.scale(scale, scale)
        draw(canvas, theme)
        return out
    }

    private fun draw(canvas: Canvas, theme: Theme) {
        val pen = Pen(theme)
        canvas.saveLayerAlpha(0f, 0f, W, H, Math.round(0.45f * 255))
        pen.roundRect(canvas, mac, 14f, STROKE)
        pen.roundRect(canvas, macScreen, 4f, STROKE * 0.8f)
        val base = Path().apply {
            moveTo(20f, 402f)
            lineTo(700f, 402f)
            lineTo(700f, 406f)
            quadTo(700f, 420f, 680f, 420f)
            lineTo(40f, 420f)
            quadTo(20f, 420f, 20f, 406f)
            close()
        }
        canvas.drawPath(base, pen.stroke(STROKE))
        pen.line(canvas, 310f, 403f, 410f, 403f, STROKE)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(macScreen, 4f, 4f, Path.Direction.CW) })
        desktop(canvas, pen, macScreen)
        canvas.restore()
        val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        canvas.drawRoundRect(RectF(fold.left - 10f, fold.top - 10f, fold.right + 10f, fold.bottom + 10f), 34f, 34f, cut)
        canvas.restore()

        pen.roundRect(canvas, fold, 24f, STROKE * 1.15f)
        pen.roundRect(canvas, foldScreen, 11f, STROKE * 0.8f)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(foldScreen, 11f, 11f, Path.Direction.CW) })
        val bar = pen.stroke(STROKE * 0.6f, alpha = 0.7f).apply { pathEffect = DashPathEffect(floatArrayOf(4f, 5f), 0f) }
        canvas.drawLine(foldScreen.left, mirrored.top, foldScreen.right, mirrored.top, bar)
        canvas.drawLine(foldScreen.left, mirrored.bottom, foldScreen.right, mirrored.bottom, bar)
        desktop(canvas, pen, mirrored)
        canvas.restore()

        val hinge = pen.stroke(1f, alpha = 0.6f).apply { pathEffect = DashPathEffect(floatArrayOf(2f, 6f), 0f) }
        canvas.drawLine(foldScreen.centerX(), fold.top + 3f, foldScreen.centerX(), fold.bottom - 3f, hinge)

        val dotX = foldScreen.left + 21f
        val dotY = foldScreen.top + 21f
        canvas.drawCircle(dotX, dotY, 8.5f, pen.stroke(STROKE).apply { color = theme.accent })
        canvas.drawCircle(dotX, dotY, 2.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.accent })

        val menuX = foldScreen.right - 23f
        val menuY = foldScreen.bottom - 22f
        canvas.drawCircle(menuX, menuY, 11.5f, pen.stroke(STROKE))
        pen.line(canvas, menuX - 5f, menuY - 3f, menuX + 5f, menuY - 3f, STROKE * 0.7f)
        pen.line(canvas, menuX - 5f, menuY + 3f, menuX + 5f, menuY + 3f, STROKE * 0.7f)
    }

    /** The wallpaper, a menu bar and two windows with lines of text, fitted to [r]. */
    private fun desktop(canvas: Canvas, pen: Pen, r: RectF) {
        val wash = Paint().apply {
            shader = LinearGradient(0f, r.top, 0f, r.bottom, wallpaper[0], wallpaper[1], Shader.TileMode.CLAMP)
            alpha = Math.round(0.35f * 255)
        }
        canvas.drawRect(r, wash)
        val s = STROKE * 0.8f
        val menuBar = r.top + r.height() * 0.045f
        pen.line(canvas, r.left, menuBar, r.right, menuBar, s * 0.7f, alpha = 0.45f)
        window(canvas, pen, RectF(r.left + r.width() * 0.08f, r.top + r.height() * 0.12f, r.left + r.width() * 0.6f, r.top + r.height() * 0.68f), rows = 5, s)
        window(canvas, pen, RectF(r.left + r.width() * 0.42f, r.top + r.height() * 0.32f, r.left + r.width() * 0.9f, r.top + r.height() * 0.82f), rows = 4, s)
    }

    private fun window(canvas: Canvas, pen: Pen, r: RectF, rows: Int, s: Float) {
        val radius = maxOf(3f, r.width() * 0.02f)
        canvas.drawRoundRect(r, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pen.theme.page })
        pen.roundRect(canvas, r, radius, s)
        for (i in 0 until rows) {
            val y = r.top + r.height() * (0.2f + i * 0.7f / rows)
            val end = 0.46f + ((i * 37) % 11) / 30f
            pen.line(canvas, r.left + r.width() * 0.08f, y, r.left + r.width() * end, y, s * 0.9f, alpha = 0.6f)
        }
    }

    private class Pen(val theme: Theme) {
        fun stroke(width: Float, alpha: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            color = theme.ink
            this.alpha = Math.round(alpha * 255)
        }

        fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, alpha: Float = 1f) {
            canvas.drawLine(x1, y1, x2, y2, stroke(width, alpha).apply { strokeCap = Paint.Cap.ROUND })
        }

        fun roundRect(canvas: Canvas, r: RectF, radius: Float, width: Float) {
            canvas.drawRoundRect(r, radius, radius, stroke(width))
        }
    }

    private const val CARD_W = 1280
    private const val CARD_H = 640
    private const val GROUND = 0xFFF6F7F8.toInt()
    private const val TEXT = 0xFF1A1B1D.toInt()
    private const val TAGLINE = "A Mac's screen on an Android phone, as a second display or a mirror."

    /** The mark's planes as drawn on white (`docs/design.md`, Brand), lower plane, fold face, upper. */
    private val markPlanes = listOf(
        "M20,2.25 L20,18.05 Q20,21.8 16.25,21.8 L-16.25,21.8 Q-20,21.8 -20,18.05 L-20,2.25 Z" to 0xFF07B086.toInt(),
        "M-8,-2.25 L32,-2.25 L20,2.25 L-20,2.25 Z" to 0xFF04674E.toInt(),
        "M-8,-2.25 L-8,-18.05 Q-8,-21.8 -4.25,-21.8 L28.25,-21.8 Q32,-21.8 32,-18.05 L32,-2.25 Z" to 0xFF37E7BB.toInt(),
    )

    /**
     * The card GitHub shows when the repository is linked: the lockup and the README's tagline on
     * the left, the drawing cropped to the Fold on the right, its Mac fading out under the text.
     */
    fun socialPreview(context: Context): Bitmap {
        val out = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(GROUND)

        val artLeft = -0.18f * CARD_W
        val artWidth = 1.25f * CARD_W
        canvas.saveLayer(null, null)
        canvas.save()
        canvas.translate(artLeft, -0.11f * CARD_W)
        canvas.scale(artWidth / W, artWidth / W)
        draw(canvas, light.copy(page = GROUND))
        canvas.restore()
        val fade = Paint().apply {
            shader = LinearGradient(artLeft + artWidth * 0.38f, 0f, artLeft + artWidth * 0.58f, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat(), fade)
        canvas.restore()

        val left = 0.07f * CARD_W
        val markHeight = 0.072f * CARD_W
        val tagSize = 0.025f * CARD_W
        val tagGap = 0.024f * CARD_W
        val tagLine = tagSize * 1.3f
        val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = context.resources.getFont(R.font.inter)
            // Browsers set Inter's optical size from the type size; Android keeps the font's default,
            // which sets the line about 9 % wider.
            fontVariationSettings = "'wght' 450, 'opsz' $tagSize"
            textSize = tagSize
            color = TEXT
            alpha = Math.round(0.72f * 255)
        }
        val tag = StaticLayout.Builder.obtain(TAGLINE, 0, TAGLINE.length, tagPaint, Math.round(0.38f * CARD_W))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        val top = (CARD_H - (markHeight + tagGap + tag.lineCount * tagLine)) / 2

        lockup(canvas, context.resources.getFont(R.font.inter_tight), left, top, markHeight)

        val tagTop = top + markHeight + tagGap
        val metrics = tagPaint.fontMetrics
        val lead = (tagLine - (metrics.descent - metrics.ascent)) / 2 - metrics.ascent
        for (i in 0 until tag.lineCount) {
            val text = TAGLINE.substring(tag.getLineStart(i), tag.getLineEnd(i)).trimEnd()
            canvas.drawText(text, left, tagTop + i * tagLine + lead, tagPaint)
        }
        return out
    }

    /**
     * The mark and the word as `docs/design.md` sets them for large places: the word's size is the
     * mark's height, which puts its capitals at 0.73 of it, 0.15 of it apart, and the i's dot a
     * square turned 45° in the lower plane's green.
     */
    private fun lockup(canvas: Canvas, interTight: Typeface, left: Float, top: Float, height: Float) {
        val viewBox = RectF(34.5f, 28.5f, 73.5f, 79.5f)
        val unit = height / viewBox.height()
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(unit, unit)
        canvas.translate(-viewBox.left, -viewBox.top)
        canvas.translate(50.93f, 57.07f)
        canvas.scale(0.72f, 0.72f)
        canvas.rotate(-45f)
        for ((data, color) in markPlanes) {
            canvas.drawPath(PathParser.createPathFromPathData(data), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        canvas.restore()

        val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = interTight
            fontVariationSettings = "'wght' 580"
            textSize = height
            letterSpacing = 0.03f
            color = TEXT
        }
        val x = left + viewBox.width() * unit + height * 0.15f
        val metrics = word.fontMetrics
        val leading = (height - (metrics.descent - metrics.ascent)) / 2
        val baseline = top + leading - metrics.ascent
        canvas.drawText("Orıpane", x, baseline, word)

        val dotX = x + word.measureText("Or") + word.measureText("ı") / 2
        val dotY = baseline + metrics.ascent - leading + height * 0.095f
        val side = height * 0.15f
        canvas.save()
        canvas.rotate(45f, dotX, dotY)
        canvas.drawRect(dotX - side / 2, dotY - side / 2, dotX + side / 2, dotY + side / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF07B086.toInt() })
        canvas.restore()
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun channel(shift: Int) = Math.round(((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t)
        return Color.rgb(channel(16), channel(8), channel(0))
    }
}
