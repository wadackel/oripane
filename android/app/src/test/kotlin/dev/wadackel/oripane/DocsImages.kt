package dev.wadackel.oripane

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * The finishing done to a screen capture before it goes into the README: scaling and the rounded
 * frame.
 *
 * Done here rather than by an image tool afterwards so the committed images are exactly what the
 * test produced, and a verify run compares the finished picture rather than an input to it.
 */
internal object DocsImages {
    private const val BORDER = 0xFF2A2D33.toInt()

    /** Scales by halving first, since a single bilinear pass below half size drops detail. */
    fun scaleToWidth(source: Bitmap, width: Int): Bitmap {
        var current = source
        while (current.width / 2 >= width) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        val height = Math.round(current.height * width.toFloat() / current.width)
        return Bitmap.createScaledBitmap(current, width, height, true)
    }

    /** Rounded corners and a hairline border, with transparency outside so it sits on any page. */
    fun frame(source: Bitmap, radius: Float): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        canvas.drawRoundRect(bounds, radius, radius, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = BORDER
        }
        bounds.inset(1.5f, 1.5f)
        canvas.drawRoundRect(bounds, radius, radius, stroke)
        return out
    }
}
