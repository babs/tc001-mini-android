package tc001.viewer.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class CaptureTest {
    @Test
    fun nameIsLocalTimeDownToTheMillisecond() { // 001-AC6, 001-AC7
        val millis = LocalDateTime.of(2026, 11, 19, 7, 5, 9, 42_000_000).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("TC001_20261119_070509_042", captureName(millis, ZoneOffset.UTC))
        assertEquals("TC001_20261119_080509_042", captureName(millis, ZoneId.of("Europe/Paris")))
        assertNotEquals("same second, next millisecond", captureName(millis, ZoneOffset.UTC), captureName(millis + 1, ZoneOffset.UTC))
        assertEquals("device time zone by default", captureName(millis, ZoneId.systemDefault()), captureName(millis))
    }

    @Test
    fun rawSnapshotIsAByteIdenticalFrameThatOutlivesTheSourceBuffer() { // 001-AC6
        val buffer = capture.copyOf(FRAME)
        val snapshot = rawSnapshot(buffer)
        assertNotSame(buffer, snapshot)
        assertEquals(18_568, snapshot.size)
        assertArrayEquals(capture.copyOf(FRAME), snapshot)
        capture.copyInto(buffer, 0, FRAME, 2 * FRAME) // the source reuses its buffer for the next frame
        assertArrayEquals(capture.copyOf(FRAME), snapshot)
        assertEquals(parse(capture)!!.toList(), parse(snapshot)!!.toList())
    }

    @Test
    fun presentationTimeStartsAtZeroAndFollowsRealTime() { // 001-AC7
        val pts = PtsRebase()
        val base = 123_456_789_000L
        // 25 fps with frames 3 and 4 dropped: the gap stays, so the duration matches the wall clock.
        val queued = listOf(0L, 40_000, 80_000, 200_000, 240_000).map { base + it }
        assertEquals(listOf(0L, 40_000, 80_000, 200_000, 240_000), queued.map(pts::next))
    }

    @Test
    fun presentationTimeNeverGoesBack() { // 001-AC7
        val pts = PtsRebase()
        assertEquals(listOf(0L, 40_000, 40_001, 40_002, 120_000), listOf(5_000L, 45_000, 45_000, 10_000, 125_000).map(pts::next))
    }
}
