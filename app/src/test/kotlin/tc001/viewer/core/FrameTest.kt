package tc001.viewer.core

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// The debug replay asset doubles as the test fixture: one copy of the capture in the tree.
val capture: ByteArray = File("src/debug/assets/replay-mode2.bin").readBytes()

class FrameTest {
    @Test
    fun frame0MatchesCaptureStats() { // 001-AC2
        val t = parse(capture)!!
        assertEquals(SIZE * SIZE, t.size)
        assertEquals(22.70, t.min().toDouble(), 0.01)
        assertEquals(23.69, t.max().toDouble(), 0.01)
        assertEquals(23.04, t.average(), 0.01)
    }

    @Test
    fun celsiusIsRawOver64Minus50() { // 001-AC2
        val t = parse(capture)!!
        for (i in intArrayOf(0, 4607, SIZE * SIZE - 1)) {
            val o = HEADER + 2 * i
            val raw = (capture[o].toInt() and 0xff) or ((capture[o + 1].toInt() and 0xff) shl 8)
            assertEquals(raw / 64.0 - 50, t[i].toDouble(), 1e-6)
        }
    }

    @Test
    fun allFramesParseAndBytesStayUntouched() { // 001-AC2
        val before = capture.copyOf()
        assertEquals(5 * FRAME, capture.size)
        for (k in 0 until 5) assertNotNull("frame $k", parse(capture, k * FRAME))
        assertArrayEquals(before, capture)
    }

    @Test
    fun parsesIntoTheCallersBuffer() { // 001-AC2
        val out = FloatArray(SIZE * SIZE) { Float.NaN }
        assertSame(out, parse(capture, FRAME, out))
        assertEquals(parse(capture, FRAME)!!.toList(), out.toList())
        val untouched = FloatArray(SIZE * SIZE) { 7f }
        assertNull(parse(capture, 4 * FRAME + 2, untouched))
        assertTrue(untouched.all { it == 7f })
    }

    @Test
    fun rejectsMalformedFrames() { // 001-AC2
        val frame = capture.copyOf(FRAME)
        assertNull("bad magic", parse(frame.copyOf().also { it[0] = 0 }))
        assertNull("bad marker", parse(frame.copyOf().also { it[HEADER - 1] = 0 }))
        assertNull("short frame", parse(frame.copyOf(FRAME - 2)))
        assertNull("truncated tail", parse(capture, 4 * FRAME + 2))
        assertNull("negative offset", parse(capture, -1))
        assertNull("empty", parse(ByteArray(0)))
    }
}
