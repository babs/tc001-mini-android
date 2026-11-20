package tc001.viewer.core

import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderTest {
    private val frame0 = parse(capture)!!

    private fun image(strength: Float) =
        Renderer(6).render(frame0, frame0.min(), frame0.max(), Palette.IRONBOW, strength).copyOf()

    private fun channelDiff(a: IntArray, b: IntArray): Long {
        var sum = 0L
        for (i in a.indices) for (shift in intArrayOf(16, 8, 0)) {
            sum += abs(((a[i] shr shift) and 0xff) - ((b[i] shr shift) and 0xff))
        }
        return sum
    }

    @Test
    fun strengthZeroShowsRealPixels() { // 001-AC5
        val r = Renderer(6)
        val norm = FloatArray(SIZE * SIZE) { it / (SIZE * SIZE - 1f) }
        val up = r.upscale(norm, 0f)
        for (y in 0 until r.height) for (x in 0 until r.width) {
            assertEquals(norm[(y / 6) * SIZE + x / 6], up[y * r.width + x], 0f)
        }
    }

    @Test
    fun halfStrengthLiesBetweenSharpAndFull() { // 001-AC5
        val sharp = image(0f)
        val half = channelDiff(image(0.5f), sharp)
        assertTrue("0 < $half", half > 0)
        assertTrue("$half < full", half < channelDiff(image(1f), sharp))
    }

    @Test
    fun bilinearClampsCornersAndKeepsARampMonotone() { // 001-AC5
        val r = Renderer(4, rows = 2, cols = 2)
        val up = r.upscale(floatArrayOf(0f, 1f, 2f, 3f), 1f)
        assertEquals(8, r.width)
        assertEquals(0f, up[0], 0f)
        assertEquals(3f, up[63], 0f)
        for (y in 0 until 8) for (x in 1 until 8) assertTrue(up[y * 8 + x] >= up[y * 8 + x - 1])
        for (y in 1 until 8) for (x in 0 until 8) assertTrue(up[y * 8 + x] >= up[(y - 1) * 8 + x])
    }

    @Test
    fun temporalEmaUsesAlphaOneMinus06S() { // 001-AC5
        val zero = FloatArray(4)
        val one = FloatArray(4) { 1f }
        assertSame("s = 0 disables it", zero, TemporalSmoother().apply(zero, 0f))
        for ((s, alpha) in listOf(0.5f to 0.7f, 1f to 0.4f)) {
            val ema = TemporalSmoother()
            assertArrayEquals(zero, ema.apply(zero, s), 0f)
            assertEquals(alpha, ema.apply(one, s)[0], 1e-6f)
        }
        val ema = TemporalSmoother()
        ema.apply(zero, 0.5f)
        assertSame("back to 0 returns the frame itself", one, ema.apply(one, 0f))
        assertArrayEquals("and restarts from the next frame", one, ema.apply(one, 0.5f), 0f)
    }

    @Test
    fun scaleBarIsColdOnTheLeftHotOnTheRight() { // 002-AC1
        for (p in Palette.entries) {
            val bar = scaleBar(p, BAR_WIDTH)
            assertEquals("$p", p.lut[0], bar[0])
            assertEquals("$p", p.lut[255], bar.last())
            val index = bar.map { p.lut.indexOf(it) }
            assertTrue("$p: LUT index never goes back", index.zipWithNext().all { (a, b) -> a <= b })
            assertEquals("$p: the whole LUT is shown", 256, index.distinct().size)
        }
        val luma = { c: Int -> ((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff) }
        val bar = scaleBar(Palette.IRONBOW, BAR_WIDTH)
        assertTrue(luma(bar[0]) < luma(bar[bar.size / 2]) && luma(bar[bar.size / 2]) < luma(bar.last()))
    }

    @Test
    fun pictureIs768By832ForTheEncoder() { // 002-AC1, 002-AC6
        assertEquals(768, PICTURE_WIDTH)
        assertEquals(832, PICTURE_HEIGHT)
        assertEquals("the thermal image is square, full width", PICTURE_WIDTH, Renderer(SCALE).height)
        assertEquals(PICTURE_WIDTH, Renderer(SCALE).width)
        assertEquals(0, PICTURE_WIDTH % 16)
        assertEquals(0, PICTURE_HEIGHT % 16)
    }

    @Test
    fun ironbowLutMatchesReferenceStops() {
        // Reference ironbow LUT (linear interpolation between the stops, truncated).
        val expected = mapOf(0 to 0x000000, 64 to 0x50008B, 100 to 0x94115E, 128 to 0xC81E3B,
            191 to 0xF98100, 230 to 0xFFDC2C, 255 to 0xFFFFFF)
        for ((i, rgb) in expected) assertEquals("index $i", rgb or (0xFF shl 24), Palette.IRONBOW.lut[i])
        assertEquals(0xFF000000.toInt(), Palette.WHITE_HOT.lut[0])
        assertEquals(0xFF000000.toInt(), Palette.BLACK_HOT.lut[255])
    }

    @Test
    fun outOfRangeStrengthIsClamped() { // 001-AC5
        // s outside [0, 1] must not extrapolate past the LUT ends.
        val field = FloatArray(SIZE * SIZE) { 30f }.also { it[48 * SIZE + 48] = 20f }
        fun img(s: Float) = Renderer(6).render(field, 20f, 30f, Palette.IRONBOW, s).copyOf()
        assertArrayEquals(img(1f), img(2f))
        assertArrayEquals(img(0f), img(-1f))
        assertArrayEquals(img(0f), img(Float.NaN))

        val one = FloatArray(4) { 1f }
        val ema = TemporalSmoother()
        ema.apply(FloatArray(4), 2f)
        assertEquals("alpha floors at 0.4", 0.4f, ema.apply(one, 2f)[0], 1e-6f)
        assertSame(one, TemporalSmoother().apply(one, -1f))
    }

    @Test
    fun anyPlaneOrRangeStaysInsideTheLut() {
        val lut = Palette.IRONBOW.lut
        val far = FloatArray(SIZE * SIZE) { if (it % 2 == 0) 1e30f else -1e30f }
        val ends = Renderer(2).render(far, 20f, 30f, Palette.IRONBOW, 0f)
        assertTrue("far outside a locked range: LUT ends", ends.all { it == lut[0] || it == lut[255] })
        val nan = FloatArray(SIZE * SIZE) { Float.NaN }
        val colours = lut.toSet()
        for ((t, lo, hi) in listOf(Triple(far, 20f, 30f), Triple(nan, 20f, 30f), Triple(frame0, 25f, 25f))) {
            for (s in floatArrayOf(0f, 0.5f, 1f)) {
                assertTrue(Renderer(2).render(t, lo, hi, Palette.IRONBOW, s).all { it in colours })
            }
        }
    }

    @Test
    fun orientRotatesClockwiseThenMirrors() {
        val t = FloatArray(SIZE * SIZE) { it.toFloat() }
        val n = SIZE - 1
        assertArrayEquals(t, orient(t, 0, false), 0f)
        assertEquals("90 cw: bottom-left goes top-left", t[n * SIZE], orient(t, 1, false)[0], 0f)
        assertEquals(t[SIZE * SIZE - 1], orient(t, 2, false)[0], 0f)
        assertEquals("270 cw: top-right goes top-left", t[n], orient(t, 3, false)[0], 0f)
        assertEquals(t[n], orient(t, 0, true)[0], 0f)
        assertEquals("180 + mirror: bottom-left goes top-left", t[n * SIZE], orient(t, 2, true)[0], 0f)
        val transpose = orient(t, 1, true)
        val antiTranspose = orient(t, 3, true)
        for (r in 0 until SIZE) for (c in 0 until SIZE) {
            assertEquals("90 + mirror is the transpose", t[c * SIZE + r], transpose[r * SIZE + c], 0f)
            assertEquals("270 + mirror is the anti-transpose", t[(n - c) * SIZE + n - r], antiTranspose[r * SIZE + c], 0f)
        }
        assertArrayEquals(orient(t, 3, false), orient(t, -1, false), 0f)
        val out = FloatArray(SIZE * SIZE)
        assertSame(out, orient(t, 1, true, out))
        assertThrows(IllegalArgumentException::class.java) { orient(t, 1, false, t) }
    }

    @Test
    fun markerMappingMatchesTheOrientedPlane() {
        val t = FloatArray(SIZE * SIZE) { it.toFloat() }
        for (turns in 0..3) for (mirror in listOf(false, true)) {
            val shown = orient(t, turns, mirror)
            for (r in 0 until SIZE) for (c in 0 until SIZE) {
                assertEquals("turns=$turns mirror=$mirror ($r,$c)",
                    t[r * SIZE + c], shown[orientedIndex(r, c, turns, mirror)], 0f)
            }
        }
    }

    @Test
    fun formatCelsiusMatchesPrintfOneDecimal() {
        val buf = CharArray(21)
        fun fmt(v: Float) = String(buf, 0, formatCelsius(v, buf))
        var v = -60f
        while (v < 600f) {
            if (abs(v) >= 0.05f) assertEquals(String.format(Locale.ROOT, "%.1f", v), fmt(v))
            v += 0.0137f
        }
        assertEquals("-12.3", fmt(-12.34f))
        assertEquals("100.0", fmt(99.96f))
        assertEquals("no negative zero", "0.0", fmt(-0.04f))
        assertEquals("0.0", fmt(Float.NaN))
        for (x in floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertTrue(fmt(x).length <= 21)
        }
    }
}
