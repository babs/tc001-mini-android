package tc001.viewer.core

import kotlin.math.abs

data class Range(val lo: Float, val hi: Float)

/** Colour range: [locked], else the scene min/max; never narrower than 0.1 C (flat scene). */
fun range(t: FloatArray, locked: Range? = null): Range {
    val lo = locked?.lo ?: t.min()
    val hi = locked?.hi ?: t.max()
    return Range(lo, maxOf(hi, lo + 0.1f))
}

/** Scale-bar column of [height] pixels, hot (top of the LUT) at index 0. */
fun scaleBar(palette: Palette, height: Int) =
    IntArray(height) { palette.lut[((1f - it / (height - 1f)) * 255f).toInt()] }

/**
 * Displayed index of raw pixel ([row], [col]) once the plane is turned clockwise by [quarterTurns] x
 * 90 degrees, then mirrored left-right if [mirror].
 */
fun orientedIndex(row: Int, col: Int, quarterTurns: Int, mirror: Boolean): Int {
    val n = SIZE - 1
    var r = row
    var c = col
    when (quarterTurns.mod(4)) {
        1 -> { r = col; c = n - row }
        2 -> { r = n - row; c = n - col }
        3 -> { r = n - col; c = row }
    }
    return r * SIZE + if (mirror) n - c else c
}

/** Plane [t] laid out as displayed (see [orientedIndex]) into [out], which must not be [t]. */
fun orient(t: FloatArray, quarterTurns: Int, mirror: Boolean, out: FloatArray = FloatArray(SIZE * SIZE)): FloatArray {
    require(out !== t) { "orient cannot work in place" }
    for (i in 0 until SIZE * SIZE) out[orientedIndex(i / SIZE, i % SIZE, quarterTurns, mirror)] = t[i]
    return out
}

/** Writes [celsius] rounded to one decimal ("-12.3") into [out] (21 chars fit any Float); returns the length. */
fun formatCelsius(celsius: Float, out: CharArray): Int {
    val tenths = Math.round(abs(celsius.toDouble()) * 10) // NaN gives 0, infinities saturate
    var n = 0
    if (celsius < 0 && tenths > 0) out[n++] = '-'
    val start = n
    var whole = tenths / 10
    do {
        out[n++] = '0' + (whole % 10).toInt()
        whole /= 10
    } while (whole > 0)
    out.reverse(start, n)
    out[n++] = '.'
    out[n++] = '0' + (tenths % 10).toInt()
    return n
}

// NaN or out-of-range strength would extrapolate past the LUT and turn the EMA alpha negative.
private fun clampStrength(s: Float) = if (s > 0f) minOf(s, 1f) else 0f

/** Display-only temporal EMA, alpha = 1 - 0.6 s with s = strength in [0, 1]; s = 0 returns the frame itself. */
class TemporalSmoother {
    private var shown: FloatArray? = null

    /** The returned array is this smoother's state: read it, do not modify it. */
    fun apply(t: FloatArray, strength: Float): FloatArray {
        val s = clampStrength(strength)
        if (s == 0f) {
            shown = null
            return t
        }
        val prev = shown ?: return t.copyOf().also { shown = it }
        val alpha = 1f - 0.6f * s
        for (i in prev.indices) prev[i] += alpha * (t[i] - prev[i])
        return prev
    }
}

/** Pixel-centre aligned, edge-clamped bilinear tables for one axis (port of live_view.upscale_linear). */
private class Axis(n: Int, scale: Int) {
    val near = IntArray(n * scale) { it / scale }
    val i0 = IntArray(n * scale)
    val w = FloatArray(n * scale)

    init {
        for (j in i0.indices) {
            val p = ((j + 0.5) / scale - 0.5).coerceIn(0.0, n - 1.0)
            i0[j] = minOf(p.toInt(), n - 2)
            w[j] = (p - i0[j]).toFloat()
        }
    }
}

/** False-colour renderer for a rows x cols plane at an integer [scale]; buffers are reused across frames. */
class Renderer(val scale: Int, private val rows: Int = SIZE, private val cols: Int = SIZE) {
    val width = cols * scale
    val height = rows * scale
    private val ry = Axis(rows, scale)
    private val cx = Axis(cols, scale)
    private val norm = FloatArray(rows * cols)
    private val line = FloatArray(cols)
    private val plane = FloatArray(width * height)

    /** ARGB thermal image of the last [render], width x height; overwritten by the next call. */
    val pixels = IntArray(width * height)

    /**
     * Upscaled [src]: nearest + s * (bilinear - nearest), s = [strength] clamped to [0, 1], computed as
     * a convex blend so s = 0 gives the exact nearest pixels and s = 1 the exact bilinear ones.
     */
    fun upscale(src: FloatArray, strength: Float, out: FloatArray = plane): FloatArray {
        val s = clampStrength(strength)
        for (y in 0 until height) {
            val near = ry.near[y] * cols
            val o = y * width
            if (s == 0f) {
                for (x in 0 until width) out[o + x] = src[near + cx.near[x]]
                continue
            }
            val r0 = ry.i0[y] * cols
            val wr = ry.w[y]
            for (k in 0 until cols) line[k] = src[r0 + k] * (1 - wr) + src[r0 + cols + k] * wr
            for (x in 0 until width) {
                val i = cx.i0[x]
                val wc = cx.w[x]
                val bilinear = line[i] * (1 - wc) + line[i + 1] * wc
                out[o + x] = src[near + cx.near[x]] * (1 - s) + bilinear * s
            }
        }
        return out
    }

    /** Renders celsius plane [t] into [pixels] and returns it. */
    fun render(t: FloatArray, range: Range, palette: Palette, strength: Float): IntArray {
        val span = range.hi - range.lo
        for (i in norm.indices) norm[i] = ((t[i] - range.lo) / span).coerceIn(0f, 1f)
        upscale(norm, strength)
        val lut = palette.lut
        for (i in pixels.indices) pixels[i] = lut[(plane[i] * 255f).toInt()]
        return pixels
    }
}
