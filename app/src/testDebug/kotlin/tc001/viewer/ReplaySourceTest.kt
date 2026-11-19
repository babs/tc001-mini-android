package tc001.viewer

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tc001.viewer.core.FRAME
import tc001.viewer.core.capture

class ReplaySourceTest {
    @Test
    fun loopsTheCaptureAt25Fps() { // 001-AC11
        val seen = Collections.synchronizedList(ArrayList<List<Byte>>())
        val latch = CountDownLatch(12)
        val source = ReplaySource(capture + ByteArray(FRAME / 2) { 0x55 })
        val t0 = System.nanoTime()
        source.start {
            seen += it.toList()
            latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        source.stop()
        val n = seen.size
        for (k in 0 until 12) {
            val expected = capture.copyOfRange(k % 5 * FRAME, (k % 5 + 1) * FRAME).toList()
            assertEquals("frame $k is capture frame ${k % 5}, trailing partial chunk skipped", expected, seen[k])
        }
        assertTrue("11 periods of 40 ms, got $elapsedMs ms", elapsedMs in 400..1500)
        Thread.sleep(100)
        assertEquals("no frame after stop()", n, seen.size)
    }

    @Test
    fun stopWaitsForTheConsumerCallToEnd() { // 001-AC11
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = ReplaySource(capture)
        source.start {
            entered.countDown()
            // Stands in for uninterruptible work (lockCanvas): ignores the interrupt, then restores it.
            var interrupted = false
            while (true) {
                try {
                    release.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val stopped = CountDownLatch(1)
        Thread {
            source.stop()
            stopped.countDown()
        }.start()
        assertFalse("stop() returned while the consumer was inside the call", stopped.await(300, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun restartsAfterStop() { // 001-AC11
        val source = ReplaySource(capture)
        repeat(2) {
            val latch = CountDownLatch(2)
            source.start { latch.countDown() }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            source.stop()
        }
        source.stop()
    }
}
