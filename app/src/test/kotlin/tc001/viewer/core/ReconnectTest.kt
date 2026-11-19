package tc001.viewer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectTest {
    private fun Reconnect.stalled(delivered: Int = 1) = next(gone = false, permitted = true, retryable = true, delivered = delivered)

    // Bounded: a missing cap fails, not hangs.
    private fun Reconnect.drain(delivered: Int = 1) = generateSequence { stalled(delivered) }.take(10).toList()

    @Test
    fun aStalledStreamIsRetriedFiveTimesWithGrowingDelaysThenGivenUp() { // 001-AC8, 001-AC1
        val r = Reconnect()
        assertEquals(listOf(500L, 1_000L, 2_000L, 3_000L, 4_000L), r.drain())
        assertEquals(5, r.attempts)
        assertNull("budget spent: the error shows", r.stalled(0))
        assertNull(r.stalled(Reconnect.RESET_FRAMES - 1))
    }

    @Test
    fun theCapHoldsAcrossAChainOfShortSessions() { // 001-AC8
        val r = Reconnect()
        val chain = (1..10).map { r.stalled(it % Reconnect.RESET_FRAMES) }
        assertEquals(listOf(500L, 1_000L, 2_000L, 3_000L, 4_000L, null, null, null, null, null), chain)
    }

    @Test
    fun aGoneOrForbiddenCameraOrAPermanentErrorIsNeverRetried() { // 001-AC8
        val r = Reconnect()
        for (delivered in listOf(0, 1, 10_000)) {
            assertNull("gone, delivered=$delivered", r.next(gone = true, permitted = true, retryable = true, delivered = delivered))
            assertNull("not permitted, delivered=$delivered", r.next(gone = false, permitted = false, retryable = true, delivered = delivered))
            assertNull("not retryable, delivered=$delivered", r.next(gone = false, permitted = true, retryable = false, delivered = delivered))
        }
        assertEquals("a refusal spends no attempt", 0, r.attempts)
    }

    @Test
    fun framesFlowingAgainOrAFreshStartRestoreTheBudget() { // 001-AC8
        val r = Reconnect()
        r.drain()
        assertEquals("a session that streamed 2 s earned a fresh budget", 500L, r.stalled(Reconnect.RESET_FRAMES))
        assertEquals("a short one did not", 1_000L, r.stalled(Reconnect.RESET_FRAMES - 1))
        r.drain()
        r.reset()
        assertEquals("unplug, re-plug, onStart or a tap", 5, r.drain(0).size)
    }
}
