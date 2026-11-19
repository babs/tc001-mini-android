package tc001.viewer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTest {
    private fun FrameProcessor.bounds() = lo to hi

    /** Scene min/max; every scene below spans more than 0.1 C, so no flat-scene floor applies. */
    private fun scene(frame: ByteArray) = parse(frame)!!.let { it.min() to it.max() }

    private val frame0 = capture.copyOf(FRAME)

    /** [frame0] with every pixel at [celsius]. */
    private fun flat(celsius: Float): ByteArray {
        val f = frame0.copyOf()
        val raw = ((celsius + 50f) * 64f).toInt()
        for (i in 0 until SIZE * SIZE) {
            f[HEADER + 2 * i] = raw.toByte()
            f[HEADER + 2 * i + 1] = (raw shr 8).toByte()
        }
        return f
    }

    /** [frame0] with every raw value shifted by [deltaRaw] (1/64 C units) and a hot block at the top-left. */
    private fun warmer(deltaRaw: Int): ByteArray {
        val f = frame0.copyOf()
        for (i in 0 until SIZE * SIZE) {
            val o = HEADER + 2 * i
            val hot = if (i / SIZE < 5 && i % SIZE < 5) 10 * 64 else 0
            val v = ((f[o].toInt() and 0xff) or ((f[o + 1].toInt() and 0xff) shl 8)) + deltaRaw + hot
            f[o] = v.toByte()
            f[o + 1] = (v shr 8).toByte()
        }
        return f
    }

    @Test
    fun lockedRangeIgnoresANewScene() { // 001-AC3
        val p = FrameProcessor()
        p.process(frame0, 0f)
        val before = p.bounds()
        assertEquals(scene(frame0), before)
        val colours = Renderer(1).render(p.shown, p.lo, p.hi, Palette.IRONBOW, 0f).copyOf()

        p.locked = true
        assertTrue(p.process(warmer(0), 0f))
        assertNotEquals("the new scene has its own range", scene(warmer(0)), before)
        assertEquals("labels (hi / mid / lo) keep the frozen range", before, p.bounds())
        val sameColours = Renderer(1).render(parse(frame0)!!, p.lo, p.hi, Palette.IRONBOW, 0f)
        assertArrayEquals("the frozen range maps a temperature to the same colour", colours, sameColours)

        p.locked = false
        p.process(warmer(64), 0f)
        assertEquals("unlocked: follows the scene", scene(warmer(64)), p.bounds())
        assertNotEquals(before, p.bounds())
        val followed = Renderer(1).render(parse(frame0)!!, p.lo, p.hi, Palette.IRONBOW, 0f)
        assertFalse(colours.contentEquals(followed))
    }

    @Test
    fun lockBeforeTheFirstFrameFreezesTheFirstRange() { // 001-AC3
        val p = FrameProcessor()
        p.locked = true
        p.process(frame0, 0f)
        assertEquals(scene(frame0), p.bounds())
        p.process(warmer(640), 0f)
        assertEquals(scene(frame0), p.bounds())
    }

    @Test
    fun lockedRangeSaturatesAHotterScene() { // 001-AC3
        val p = FrameProcessor()
        p.process(frame0, 0f)
        val frozen = p.bounds()
        p.locked = true
        p.process(flat(40f), 0f)
        assertEquals(frozen, p.bounds())
        val lut = Palette.IRONBOW.lut
        assertTrue("hotter than the frozen hi: top colour", Renderer(1).render(p.shown, p.lo, p.hi, Palette.IRONBOW, 0f).all { it == lut[255] })
    }

    @Test
    fun flatSceneRangeIsAtLeastATenthWide() { // 001-AC3
        val p = FrameProcessor()
        p.process(flat(25f), 0.5f)
        assertEquals(25f, p.lo, 0f)
        assertEquals(25.1f, p.hi, 1e-5f)
        val px = Renderer(1).render(p.shown, p.lo, p.hi, Palette.IRONBOW, 0.5f)
        assertTrue("no division by zero: bottom colour", px.all { it == Palette.IRONBOW.lut[0] })
    }

    @Test
    fun centreIsTheRaw2x2MeanWhateverTheOrientation() { // 001-AC5
        val t = FloatArray(SIZE * SIZE) { it.toFloat() }
        val i = 47 * SIZE + 47
        val mean = (t[i] + t[i + 1] + t[i + SIZE] + t[i + SIZE + 1]) / 4
        assertEquals(mean, centreCelsius(t), 0f)
        for (turns in 0..3) for (mirror in listOf(false, true)) {
            assertEquals("turns=$turns mirror=$mirror", mean, centreCelsius(orient(t, turns, mirror)), 0f)
        }
    }

    @Test
    fun readoutsUseTheRawFrameNotTheSmoothedOne() { // 001-AC5
        val p = FrameProcessor()
        p.process(frame0, 1f)
        val hot = warmer(640)
        p.process(hot, 1f)
        val raw = parse(hot)!!
        assertEquals(centreCelsius(raw), p.centre, 0f)
        assertEquals(raw.min() to raw.max(), p.bounds())
        assertTrue("spots on raw: the hot block", p.spots.hotRow < 5 && p.spots.hotCol < 5)
        assertTrue(p.spots.hotCelsius > raw[SIZE * SIZE / 2] + 5)
        assertTrue("display is smoothed", p.shown[0] < raw[0])
        assertNotEquals(centreCelsius(p.shown), p.centre)
    }

    @Test
    fun readoutsOnTheBundledCapture() { // 002-AC3
        val p = FrameProcessor()
        assertTrue(p.process(frame0, 0.5f))
        val r = Readouts().apply { fill(p) }
        val raw = parse(frame0)!!
        val i = (SIZE / 2 - 1) * SIZE + SIZE / 2 - 1
        assertEquals("Center: raw 2x2 mean", (raw[i] + raw[i + 1] + raw[i + SIZE] + raw[i + SIZE + 1]) / 4, r.centre, 0f)
        val smooth = box3(raw)
        assertEquals("Max: hot marker value", smooth.max(), r.max, 0f)
        assertEquals("Min: cold marker value", smooth.min(), r.min, 0f)
        assertTrue("markers are smoothed, not the raw extremes", r.max < raw.max() && r.min > raw.min())
        assertEquals("labels: the colour range", raw.min(), r.lo, 0f)
        assertEquals(raw.max(), r.hi, 0f)
        assertEquals((raw.min() + raw.max()) / 2, r.mid, 0f)
    }

    @Test
    fun lockedReadoutLabelsStayWhileMaxFollows() { // 002-AC2, 002-AC3
        val p = FrameProcessor()
        val r = Readouts()
        p.process(frame0, 0f)
        r.fill(p)
        val labels = Triple(r.lo, r.mid, r.hi)
        val max = r.max
        p.locked = true
        p.process(warmer(640), 0f)
        r.fill(p)
        assertEquals("scale labels frozen", labels, Triple(r.lo, r.mid, r.hi))
        assertTrue("Max still follows the hot marker", r.max > max + 5)
        assertEquals(p.spots.hotCelsius, r.max, 0f)
        p.locked = false
        p.process(warmer(640), 0f)
        r.fill(p)
        assertNotEquals("unlocked: labels follow", labels, Triple(r.lo, r.mid, r.hi))
    }

    @Test
    fun junkFrameLeavesTheStateUntouched() {
        val p = FrameProcessor()
        p.process(frame0, 0.5f)
        val range = p.bounds()
        val centre = p.centre
        assertFalse(p.process(ByteArray(FRAME), 0.5f))
        assertEquals(range, p.bounds())
        assertEquals(centre, p.centre, 0f)
    }
}
