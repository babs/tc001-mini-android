package tc001.viewer.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxTest {
    private val a = ByteArray(2)
    private val b = ByteArray(2)
    private val box = Mailbox<ByteArray, ByteArray>(a, b) { from, into -> from.copyInto(into) }

    @Test
    fun newestFrameWins() { // 001-AC7
        assertTrue(box.put(byteArrayOf(1, 1)))
        assertTrue(box.put(byteArrayOf(2, 2)))
        assertArrayEquals(byteArrayOf(2, 2), box.take())
    }

    @Test
    fun theProducerNeverWritesTheBufferTheConsumerHolds() { // 001-AC7
        box.put(byteArrayOf(1, 1))
        val first = box.take()!!
        box.put(byteArrayOf(2, 2))
        box.put(byteArrayOf(3, 3))
        assertArrayEquals("held while the next frames arrive", byteArrayOf(1, 1), first)
        val second = box.take()!!
        assertArrayEquals(byteArrayOf(3, 3), second)
        assertTrue("two buffers, reused", (first === a && second === b) || (first === b && second === a))
        box.put(byteArrayOf(4, 4))
        assertArrayEquals("held again", byteArrayOf(3, 3), second)
        assertSame("the first buffer comes back", first, box.take())
    }

    @Test
    fun takeWaitsForAFrameNotSeenYet() { // 001-AC7
        box.put(byteArrayOf(1, 1))
        box.take()
        val got = CountDownLatch(1)
        var frame: ByteArray? = null
        Thread {
            frame = box.take()
            got.countDown()
        }.start()
        assertFalse("no new frame: take() waits", got.await(200, TimeUnit.MILLISECONDS))
        box.put(byteArrayOf(5, 5))
        assertTrue(got.await(2, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(5, 5), frame)
    }

    @Test
    fun closeWakesTheConsumerAndRefusesFrames() { // 001-AC7, 001-AC8
        val done = CountDownLatch(1)
        var frame: ByteArray? = byteArrayOf()
        Thread {
            frame = box.take()
            done.countDown()
        }.start()
        Thread.sleep(100)
        box.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertNull(frame)
        assertFalse(box.put(byteArrayOf(6, 6)))
        assertEquals("refused frames are not copied", 0, a.sum() + b.sum())
        assertNull("a frame put before close is dropped", Mailbox<Int, IntArray>(IntArray(1), IntArray(1)) { f, t -> t[0] = f }.run {
            put(1)
            close()
            take()
        })
    }
}
