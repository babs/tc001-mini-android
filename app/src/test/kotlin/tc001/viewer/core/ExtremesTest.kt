package tc001.viewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtremesTest {
    private val scene = FloatArray(SIZE * SIZE) { 20f }

    private fun blob(r0: Int, c0: Int, v: Float) {
        for (r in r0..r0 + 2) for (c in c0..c0 + 2) scene[r * SIZE + c] = v
    }

    private fun Extremes.hot(): Pair<Int, Int> {
        update(scene)
        return hotRow to hotCol
    }

    private fun Extremes.cold(): Pair<Int, Int> {
        update(scene)
        return coldRow to coldCol
    }

    @Test
    fun hotSpotMovesOnlyOnAClearWinner() { // 001-AC4
        val tracker = Extremes()
        blob(10, 10, 30f)
        assertEquals(11 to 11, tracker.hot())
        blob(50, 50, 30.2f)
        assertEquals("0.2 C warmer must not move it", 11 to 11, tracker.hot())
        blob(50, 50, 31f)
        assertEquals("1.0 C warmer moves it", 51 to 51, tracker.hot())
    }

    @Test
    fun coldSpotMovesOnlyOnAClearWinner() { // 001-AC4
        val tracker = Extremes()
        blob(0, 0, -5f)
        assertEquals("corner cold spot", 0 to 0, tracker.cold())
        assertEquals(-5f, tracker.coldCelsius, 1e-4f)
        blob(80, 80, -5.2f)
        assertEquals(0 to 0, tracker.cold())
        blob(80, 80, -6f)
        assertEquals(81 to 81, tracker.cold())
    }

    @Test
    fun markersSitOnTheSmoothedExtremes() { // 001-AC4
        blob(10, 10, 30f)
        scene[70 * SIZE + 70] = 35f // single-pixel spike: raw max, but only 21.7 C after 3x3 smoothing
        val spots = Extremes()
        assertEquals(11 to 11, spots.hot())
        assertEquals(30f, spots.hotCelsius, 1e-4f)
        assertEquals(box3(scene)[11 * SIZE + 11], spots.hotCelsius, 0f)
    }

    @Test
    fun labelFollowsWithEma() { // 001-AC4
        val tracker = Extremes()
        blob(10, 10, 30f)
        tracker.update(scene)
        blob(10, 10, 31f)
        assertEquals(11 to 11, tracker.hot())
        assertEquals(30.3f, tracker.hotCelsius, 1e-4f)
    }

    @Test
    fun readyOnlyAfterTheFirstUpdate() {
        val tracker = Extremes()
        assertFalse(tracker.ready)
        tracker.update(scene)
        assertTrue(tracker.ready)
    }

    @Test
    fun box3PadsEdges() {
        val t = FloatArray(SIZE * SIZE)
        t[0] = 9f
        val s = box3(t)
        assertEquals("corner sees itself 4x through edge padding", 4f, s[0], 1e-5f)
        assertEquals(1f, s[SIZE + 1], 1e-5f)
        assertEquals(0f, s[2 * SIZE + 2], 0f)
    }
}
