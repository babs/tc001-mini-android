package tc001.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import tc001.viewer.core.BAND
import tc001.viewer.core.BAR_MARGIN
import tc001.viewer.core.BAR_WIDTH
import tc001.viewer.core.CENTRE_COLOR
import tc001.viewer.core.COLD_COLOR
import tc001.viewer.core.Extremes
import tc001.viewer.core.HOT_COLOR
import tc001.viewer.core.Palette
import tc001.viewer.core.Readouts
import tc001.viewer.core.Renderer
import tc001.viewer.core.SCALE
import tc001.viewer.core.SIZE
import tc001.viewer.core.VerticalScale
import tc001.viewer.core.bandLeft
import tc001.viewer.core.formatCelsius
import tc001.viewer.core.orient
import tc001.viewer.core.orientedIndex
import tc001.viewer.core.pictureSize
import tc001.viewer.core.scaleBar
import tc001.viewer.core.scaleColumn
import tc001.viewer.core.thermalLeft

/**
 * Composes the whole view (thermal image, crosshair, spot markers, centre badge, then a band with the
 * scale bar and its labels: below and horizontal upright, beside and vertical sideways) into one Bitmap,
 * so the screen, the photo PNG and the video frames are the same pixels.
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

    // One bitmap per layout, made once: a photo copy or a recorder draw never sees one resized.
    private val upright = pictureSize(0).let { (w, h) -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    private val sideways = pictureSize(1).let { (w, h) -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    private val uprightCanvas = Canvas(upright)
    private val sidewaysCanvas = Canvas(sideways)
    private var canvas = uprightCanvas
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, 2 * scale / 3f)
    }

    // Outline thickness on every side, arm ends included.
    private val outline = scale / 6f
    private val halo = Paint(stroke).apply {
        color = 0xB0000000.toInt()
        strokeWidth = stroke.strokeWidth + 2 * outline
    }
    // One path per cross: a semi-transparent outline drawn as two lines would blend twice where they cross.
    // Butt caps: the outline's arms are longer by `outline` so the ends get the same outline as the sides.
    private val haloCross = cross(outline)
    private val strokeCross = cross(0f)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 2.5f * scale
    }
    private val fm = text.fontMetrics
    private val labels = Paint(text).apply { color = Color.WHITE }
    private val unitWidth = labels.measureText(UNIT)
    private val labelBaseline = barTop + barHeight - fm.ascent + scale / 4f
    private val vertical = VerticalScale(fm.descent - fm.ascent)
    private val column = IntArray(barHeight * (vertical.barBottom - vertical.barTop))
    private var columnPalette: Palette? = null
    private val backdrop = Paint().apply { color = 0xC0000000.toInt() }
    private val panel = Paint().apply { color = Color.rgb(24, 24, 24) }

    /**
     * Draws celsius plane [t] in colour range [r] lo..hi, turned clockwise by [quarterTurns] x 90 degrees
     * and [mirror]ed, with the [spots] tracked on the raw plane and every number from [r], laid out for a phone
     * turned by [phone] quarters, and returns that layout's bitmap ([pictureSize]), reused across calls.
     */
    fun compose(
        t: FloatArray,
        r: Readouts,
        palette: Palette,
        strength: Float,
        spots: Extremes?,
        quarterTurns: Int,
        mirror: Boolean,
        phone: Int,
    ): Bitmap {
        val bitmap = if (phone % 2 != 0) sideways else upright
        canvas = if (phone % 2 != 0) sidewaysCanvas else uprightCanvas
        val left = thermalLeft(phone)
        orient(t, quarterTurns, mirror, oriented)
        bitmap.setPixels(renderer.render(oriented, r.lo, r.hi, palette, strength), 0, side, left, 0, side, side)

        canvas.save()
        canvas.translate(left.toFloat(), 0f)
        val c = side / 2f
        stroke.color = Color.WHITE
        canvas.drawPath(haloCross, halo)
        canvas.drawPath(strokeCross, stroke)
        // Lower-right of the crosshair, clear of both arms and of the measured 2x2 block; before the markers so they stay on top.
        badge(formatCelsius(r.centre, label), c + scale + 2, c + scale + 2, CENTRE_COLOR)
        val tracked = if (spots?.ready == true) spots else null
        if (tracked != null) {
            marker(orientedIndex(tracked.hotRow, tracked.hotCol, quarterTurns, mirror), r.max, HOT_COLOR)
            marker(orientedIndex(tracked.coldRow, tracked.coldCol, quarterTurns, mirror), r.min, COLD_COLOR)
        }
        canvas.restore()
        if (bitmap === sideways) {
            verticalScale(bitmap, bandLeft(phone), r, palette)
            return bitmap
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

    /** The sideways band at [bandLeft]: the bar with hot at the top, the labels centred across the band (003-AC9). */
    private fun verticalScale(bitmap: Bitmap, bandLeft: Int, r: Readouts, palette: Palette) {
        canvas.drawRect(bandLeft.toFloat(), 0f, (bandLeft + BAND).toFloat(), side.toFloat(), panel)
        val length = vertical.barBottom - vertical.barTop
        if (palette != columnPalette) {
            val colours = scaleColumn(palette, length)
            for (y in 0 until length) column.fill(colours[y], y * barHeight, (y + 1) * barHeight)
            columnPalette = palette
        }
        bitmap.setPixels(column, 0, barHeight, bandLeft + (BAND - barHeight) / 2, vertical.barTop, barHeight, length)
        val x = bandLeft + BAND / 2f
        labels.textAlign = Paint.Align.CENTER
        canvas.drawText(UNIT, x, vertical.unitTop - fm.ascent, labels)
        canvas.drawText(label, 0, formatCelsius(r.hi, label), x, vertical.highTop - fm.ascent, labels)
        canvas.drawText(label, 0, formatCelsius(r.lo, label), x, vertical.lowTop - fm.ascent, labels)
        // Over the bar, on a backdrop: the band has no room beside it.
        val n = formatCelsius(r.mid, label)
        val w = minOf(labels.measureText(label, 0, n) + scale / 2f, BAND.toFloat())
        canvas.drawRect(x - w / 2, vertical.midTop, x + w / 2, vertical.midTop + fm.descent - fm.ascent, backdrop)
        canvas.drawText(label, 0, n, x, vertical.midTop - fm.ascent, labels)
    }

    private fun cross(ext: Float) = Path().apply {
        val c = side / 2f
        moveTo(c - half - ext, c)
        lineTo(c + half + ext, c)
        moveTo(c, c - half - ext)
        lineTo(c, c + half + ext)
    }

    private fun scaleLabel(celsius: Float, x: Float, align: Paint.Align) {
        labels.textAlign = align
        canvas.drawText(label, 0, formatCelsius(celsius, label), x, labelBaseline, labels)
    }

    private fun marker(index: Int, celsius: Float, color: Int) {
        val x = index % SIZE * scale + scale / 2f
        val y = index / SIZE * scale + scale / 2f
        stroke.color = color
        canvas.drawRect(x - half, y - half, x + half, y + half, halo)
        canvas.drawRect(x - half, y - half, x + half, y + half, stroke)

        val n = formatCelsius(celsius, label)
        val w = badgeWidth(n)
        // The label must clear the outline, not just the stroke.
        val gap = half + halo.strokeWidth / 2 + 2
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
