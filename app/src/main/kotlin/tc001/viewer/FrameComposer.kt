package tc001.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import tc001.viewer.core.BAR_MARGIN
import tc001.viewer.core.BAR_WIDTH
import tc001.viewer.core.CENTRE_COLOR
import tc001.viewer.core.COLD_COLOR
import tc001.viewer.core.Extremes
import tc001.viewer.core.HOT_COLOR
import tc001.viewer.core.PICTURE_HEIGHT
import tc001.viewer.core.PICTURE_WIDTH
import tc001.viewer.core.Palette
import tc001.viewer.core.Readouts
import tc001.viewer.core.Renderer
import tc001.viewer.core.SCALE
import tc001.viewer.core.SIZE
import tc001.viewer.core.formatCelsius
import tc001.viewer.core.orient
import tc001.viewer.core.orientedIndex
import tc001.viewer.core.scaleBar

/**
 * Composes the whole view (thermal image, crosshair, spot markers, centre badge, then a band with the
 * horizontal scale bar and its labels) into one Bitmap, so the screen, the photo PNG and the
 * video frames are the same pixels.
 */
class FrameComposer {
    private val scale = SCALE
    private val renderer = Renderer(scale)
    private val side = renderer.width
    private val oriented = FloatArray(SIZE * SIZE)
    private val barLeft = BAR_MARGIN
    private val barTop = side + scale
    private val barWidth = BAR_WIDTH
    private val barHeight = 3 * scale
    private val bar = IntArray(barWidth * barHeight)
    private var barPalette: Palette? = null
    private val half = 3 * scale
    private val label = CharArray(21)

    val bitmap: Bitmap = Bitmap.createBitmap(PICTURE_WIDTH, PICTURE_HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val stroke = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, 2 * scale / 3f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 2.5f * scale
    }
    private val fm = text.fontMetrics
    private val labels = Paint(text).apply { color = Color.WHITE }
    private val unitWidth = labels.measureText(UNIT)
    private val labelBaseline = barTop + barHeight - fm.ascent + scale / 4f
    private val backdrop = Paint().apply { color = 0xC0000000.toInt() }
    private val panel = Paint().apply { color = Color.rgb(24, 24, 24) }

    /**
     * Draws celsius plane [t] in colour range [r] lo..hi, turned clockwise by [quarterTurns] x 90 degrees
     * and [mirror]ed, with the [spots] tracked on the raw plane and every number from [r], and returns
     * [bitmap], reused across calls.
     */
    fun compose(
        t: FloatArray,
        r: Readouts,
        palette: Palette,
        strength: Float,
        spots: Extremes?,
        quarterTurns: Int,
        mirror: Boolean,
    ): Bitmap {
        orient(t, quarterTurns, mirror, oriented)
        bitmap.setPixels(renderer.render(oriented, r.lo, r.hi, palette, strength), 0, side, 0, 0, side, side)

        val c = side / 2f
        stroke.color = Color.WHITE
        canvas.drawLine(c - half, c, c + half, c, stroke)
        canvas.drawLine(c, c - half, c, c + half, stroke)
        // Lower-right of the crosshair, clear of both arms and of the measured 2x2 block; before the markers so they stay on top.
        badge(formatCelsius(r.centre, label), c + scale + 2, c + scale + 2, CENTRE_COLOR)
        val tracked = if (spots?.ready == true) spots else null
        if (tracked != null) {
            marker(orientedIndex(tracked.hotRow, tracked.hotCol, quarterTurns, mirror), r.max, HOT_COLOR)
            marker(orientedIndex(tracked.coldRow, tracked.coldCol, quarterTurns, mirror), r.min, COLD_COLOR)
        }

        canvas.drawRect(0f, side.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(), panel)
        if (palette != barPalette) {
            val row = scaleBar(palette, barWidth)
            for (y in 0 until barHeight) System.arraycopy(row, 0, bar, y * barWidth, barWidth)
            barPalette = palette
        }
        bitmap.setPixels(bar, 0, barWidth, barLeft, barTop, barWidth, barHeight)
        val right = (barLeft + barWidth).toFloat()
        scaleLabel(r.lo, barLeft.toFloat(), Paint.Align.LEFT)
        scaleLabel(r.mid, side / 2f, Paint.Align.CENTER)
        scaleLabel(r.hi, right - unitWidth, Paint.Align.RIGHT)
        labels.textAlign = Paint.Align.LEFT
        canvas.drawText(UNIT, right - unitWidth, labelBaseline, labels)
        return bitmap
    }

    private fun scaleLabel(celsius: Float, x: Float, align: Paint.Align) {
        labels.textAlign = align
        canvas.drawText(label, 0, formatCelsius(celsius, label), x, labelBaseline, labels)
    }

    private fun marker(index: Int, celsius: Float, color: Int) {
        val x = index % SIZE * scale + scale / 2f
        val y = index / SIZE * scale + scale / 2f
        stroke.color = color
        canvas.drawRect(x - half, y - half, x + half, y + half, stroke)

        val n = formatCelsius(celsius, label)
        val w = badgeWidth(n)
        // Clear of the box's outer stroke edge.
        val gap = half + stroke.strokeWidth / 2 + 2
        badge(n, if (x + gap + w <= side) x + gap else x - gap - w, y - half, color)
    }

    private fun badgeWidth(n: Int) = text.measureText(label, 0, n) + scale

    /** First [n] chars of [label] on a dark backdrop at ([left], [top]), clamped inside the image. */
    private fun badge(n: Int, left: Float, top: Float, color: Int) {
        val w = badgeWidth(n)
        val h = fm.descent - fm.ascent
        val l = left.coerceIn(0f, side - w)
        val t = top.coerceIn(0f, side - h)
        canvas.drawRect(l, t, l + w, t + h, backdrop)
        text.color = color
        canvas.drawText(label, 0, n, l + scale / 2f, t - fm.ascent, text)
    }

    private companion object {
        const val UNIT = "°C"
    }
}
