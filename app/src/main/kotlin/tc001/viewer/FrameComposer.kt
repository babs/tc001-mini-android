package tc001.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import tc001.viewer.core.BAR_MARGIN
import tc001.viewer.core.BAR_WIDTH
import tc001.viewer.core.Extremes
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
 * horizontal scale bar and the readout line) into one Bitmap, so the screen, the photo PNG and the
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
        strokeWidth = maxOf(1f, scale / 3f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 2.5f * scale
    }
    private val fm = text.fontMetrics
    private val labels = Paint(text).apply { color = Color.WHITE }
    private val unitWidth = labels.measureText(UNIT)
    private val labelBaseline = barTop + barHeight - fm.ascent + scale / 4f
    private val big = Paint(text).apply { textSize = 4f * scale }
    private val gap = big.measureText(GAP)
    private val readoutBaseline = bitmap.height - big.fontMetrics.descent - scale
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
        badge(formatCelsius(r.centre, label), c + scale + 2, c + scale + 2, Color.WHITE)
        val tracked = if (spots?.ready == true) spots else null
        if (tracked != null) {
            marker(orientedIndex(tracked.hotRow, tracked.hotCol, quarterTurns, mirror), r.max, Color.WHITE)
            marker(orientedIndex(tracked.coldRow, tracked.coldCol, quarterTurns, mirror), r.min, COLD)
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

        var x = barLeft.toFloat()
        if (tracked != null) {
            big.color = COLD
            x = readout(MIN, r.min, x)
            big.color = Color.WHITE
            x = readout(MAX, r.max, x)
        }
        big.color = Color.WHITE
        readout(CENTER, r.centre, x)
        return bitmap
    }

    private fun scaleLabel(celsius: Float, x: Float, align: Paint.Align) {
        labels.textAlign = align
        canvas.drawText(label, 0, formatCelsius(celsius, label), x, labelBaseline, labels)
    }

    /** Draws [name] then [celsius] on the readout line from [x]; returns where the next item starts, a [GAP] further. */
    private fun readout(name: String, celsius: Float, x: Float): Float {
        canvas.drawText(name, x, readoutBaseline, big)
        val at = x + big.measureText(name)
        val n = formatCelsius(celsius, label)
        canvas.drawText(label, 0, n, at, readoutBaseline, big)
        return at + big.measureText(label, 0, n) + gap
    }

    private fun marker(index: Int, celsius: Float, color: Int) {
        val x = index % SIZE * scale + scale / 2f
        val y = index / SIZE * scale + scale / 2f
        stroke.color = color
        canvas.drawRect(x - half, y - half, x + half, y + half, stroke)

        val n = formatCelsius(celsius, label)
        val w = badgeWidth(n)
        badge(n, if (x + half + 2 + w <= side) x + half + 2 else x - half - 2 - w, y - half, color)
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
        val COLD = Color.rgb(0, 200, 255)
        const val UNIT = "°C"
        const val MIN = "Min "
        const val MAX = "Max "
        const val CENTER = "Center "
        const val GAP = "  "
    }
}
