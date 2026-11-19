package tc001.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import tc001.viewer.core.Extremes
import tc001.viewer.core.Palette
import tc001.viewer.core.Range
import tc001.viewer.core.Renderer
import tc001.viewer.core.SIZE
import tc001.viewer.core.formatCelsius
import tc001.viewer.core.orient
import tc001.viewer.core.orientedIndex
import tc001.viewer.core.scaleBar

/**
 * Composes the whole view (thermal image, crosshair, spot markers, scale-bar panel) into one Bitmap,
 * so the screen, the photo PNG and the video frames are the same pixels.
 */
class FrameComposer(private val scale: Int) {
    private val renderer = Renderer(scale)
    private val side = renderer.width
    private val oriented = FloatArray(SIZE * SIZE)
    private val barX = side + scale
    private val barWidth = 2 * scale
    private val bar = IntArray(barWidth * side)
    private var barPalette: Palette? = null
    private val half = 3 * scale
    private val label = CharArray(21)

    val bitmap: Bitmap = Bitmap.createBitmap(side + 12 * scale, side, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val stroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, scale / 3f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 2.5f * scale
    }
    private val fm = text.fontMetrics
    private val backdrop = Paint().apply { color = 0xC0000000.toInt() }
    private val panel = Paint().apply { color = Color.rgb(24, 24, 24) }

    /**
     * Draws raw celsius plane [t] turned clockwise by [quarterTurns] x 90 degrees and [mirror]ed, with
     * the [spots] tracked on that raw plane, and returns [bitmap], reused across calls.
     */
    fun compose(
        t: FloatArray,
        range: Range,
        palette: Palette,
        strength: Float,
        spots: Extremes?,
        quarterTurns: Int,
        mirror: Boolean,
    ): Bitmap {
        orient(t, quarterTurns, mirror, oriented)
        bitmap.setPixels(renderer.render(oriented, range, palette, strength), 0, side, 0, 0, side, side)

        val c = side / 2f
        stroke.color = Color.WHITE
        canvas.drawLine(c - half, c, c + half, c, stroke)
        canvas.drawLine(c, c - half, c, c + half, stroke)
        if (spots?.ready == true) {
            marker(orientedIndex(spots.hotRow, spots.hotCol, quarterTurns, mirror), spots.hotCelsius, Color.WHITE)
            marker(orientedIndex(spots.coldRow, spots.coldCol, quarterTurns, mirror), spots.coldCelsius, COLD)
        }

        canvas.drawRect(side.toFloat(), 0f, bitmap.width.toFloat(), side.toFloat(), panel)
        if (palette != barPalette) {
            val column = scaleBar(palette, side)
            for (y in 0 until side) bar.fill(column[y], y * barWidth, (y + 1) * barWidth)
            barPalette = palette
        }
        bitmap.setPixels(bar, 0, barWidth, barX, 0, barWidth, side)
        text.color = Color.WHITE
        val x = barX + barWidth + scale.toFloat()
        drawCelsius(range.hi, x, -fm.ascent)
        drawCelsius((range.lo + range.hi) / 2, x, (side - fm.ascent - fm.descent) / 2)
        drawCelsius(range.lo, x, side - fm.descent)
        return bitmap
    }

    private fun drawCelsius(celsius: Float, x: Float, baseline: Float) =
        canvas.drawText(label, 0, formatCelsius(celsius, label), x, baseline, text)

    private fun marker(index: Int, celsius: Float, color: Int) {
        val x = index % SIZE * scale + scale / 2f
        val y = index / SIZE * scale + scale / 2f
        stroke.color = color
        canvas.drawRect(x - half, y - half, x + half, y + half, stroke)

        val n = formatCelsius(celsius, label)
        val pad = scale / 2f
        val w = text.measureText(label, 0, n) + 2 * pad
        val h = fm.descent - fm.ascent
        val left = if (x + half + 2 + w <= side) x + half + 2 else x - half - 2 - w
        val top = (y - half).coerceIn(0f, side - h)
        canvas.drawRect(left, top, left + w, top + h, backdrop)
        text.color = color
        canvas.drawText(label, 0, n, left + pad, top - fm.ascent, text)
    }

    private companion object {
        val COLD = Color.rgb(0, 200, 255)
    }
}
