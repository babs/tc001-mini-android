package tc001.viewer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FID = 0x01
private const val EOF = 0x02
private const val ERR = 0x40

private fun frame(k: Int) = capture.copyOfRange(k * FRAME, (k + 1) * FRAME)

/** One UVC payload: a 12-byte header (length, [flags], PTS/SCR left zero) then [data]. */
private fun payload(flags: Int, data: ByteArray = ByteArray(0)) = byteArrayOf(12, flags.toByte()) + ByteArray(10) + data

/** [data] cut into [chunk]-byte payloads with [fid]; EOF on the last one when [eof]. */
private fun payloads(data: ByteArray, fid: Int, eof: Boolean = true, chunk: Int = 4096) =
    (data.indices step chunk).map { o ->
        val last = o + chunk >= data.size
        payload(fid or if (last && eof) EOF else 0, data.copyOfRange(o, minOf(o + chunk, data.size)))
    }

/** Feeds every payload (in a larger buffer, as bulkTransfer fills one) and returns copies of the frames delivered. */
private fun UvcAssembler.feedAll(packets: List<ByteArray>): List<ByteArray> {
    val buffer = ByteArray(16_384) { 0x5a }
    return packets.mapNotNull { p ->
        p.copyInto(buffer)
        feed(buffer, p.size)?.copyOf()
    }
}

class UvcTest {
    @Test
    fun framesSplitAcrossPayloadsReassembleByteIdentical() { // 001-AC1
        val a = UvcAssembler()
        for (k in 0 until 5) {
            val packets = payloads(frame(k), fid = k % 2)
            val out = a.feedAll(packets)
            assertEquals("frame $k: one frame, on its EOF payload", 1, out.size)
            assertArrayEquals(frame(k), out[0])
        }
        assertEquals(0, a.rejected)
    }

    @Test
    fun fidToggleWithoutEofCompletesThePreviousFrame() { // 001-AC1
        val a = UvcAssembler()
        assertEquals(0, a.feedAll(payloads(frame(0), fid = 0, eof = false)).size)
        val out = a.feedAll(payloads(frame(1), fid = 1, eof = false) + payloads(frame(2), fid = 0, eof = false).take(1))
        assertEquals(2, out.size)
        assertArrayEquals(frame(0), out[0])
        assertArrayEquals(frame(1), out[1])
        assertEquals(0, a.rejected)
    }

    @Test
    fun errPayloadDropsItsFrame() { // 001-AC1
        val a = UvcAssembler()
        val bad = payloads(frame(0), fid = 0).toMutableList()
        bad[2] = payload(ERR, ByteArray(4096))
        assertEquals(0, a.feedAll(bad).size)
        assertEquals("one ERR, the rest of the frame dropped silently", 1, a.uvcError)
        assertEquals(1, a.rejected)
        assertArrayEquals(frame(1), a.feedAll(payloads(frame(1), fid = 1)).single())
    }

    @Test
    fun errWithoutEofIsClearedByTheNextFidToggle() { // 001-AC1
        val a = UvcAssembler()
        val bad = payloads(frame(0), fid = 0, eof = false).toMutableList()
        bad[0] = payload(ERR, ByteArray(4096))
        assertEquals(0, a.feedAll(bad).size)
        assertArrayEquals(frame(1), a.feedAll(payloads(frame(1), fid = 1)).single())
        assertEquals(1, a.rejected)
    }

    @Test
    fun shortOversizeAndBadMagicFramesAreRejectedAndTheNextOneComesThrough() { // 001-AC1
        val a = UvcAssembler()
        val short = payloads(frame(0), fid = 0).dropLast(1) + payload(EOF)
        assertEquals(0, a.feedAll(short).size)
        assertEquals(1, a.short)

        assertEquals(0, a.feedAll(payloads(frame(0) + byteArrayOf(1, 2), fid = 1)).size)
        assertEquals(1, a.oversize)

        assertEquals(0, a.feedAll(payloads(frame(0).also { it[0] = 0 }, fid = 0)).size)
        assertEquals(0, a.feedAll(payloads(frame(0).also { it[HEADER - 1] = 0 }, fid = 1)).size)
        assertEquals("bad magic, bad marker", 2, a.badMagic)

        assertArrayEquals(frame(3), a.feedAll(payloads(frame(3), fid = 0)).single())
        assertEquals(4, a.rejected)
    }

    @Test
    fun oversizeWithoutEofIsDiscardedUntilTheNextFidToggle() { // 001-AC1
        val a = UvcAssembler()
        val packets = payloads(frame(0) + ByteArray(FRAME), fid = 0, eof = false) + payloads(frame(2), fid = 1)
        assertArrayEquals(frame(2), a.feedAll(packets).single())
        assertEquals(1, a.oversize)
        assertEquals(1, a.rejected)
    }

    @Test
    fun aStreamCutMidFrameNeverDeliversThePartialFrame() { // 001-AC8
        val a = UvcAssembler()
        val cut = payloads(frame(0), fid = 0, eof = false).take(2)
        assertEquals(0, a.feedAll(cut).size)
        assertEquals("nothing rejected until a boundary arrives", 0, a.rejected)
        assertArrayEquals(frame(1), a.feedAll(payloads(frame(1), fid = 1)).single())
        assertEquals("the cut frame counts as short on the next FID", 1, a.short)
    }

    @Test
    fun malformedHeadersAreRejectedWithoutDisturbingTheFrame() { // 001-AC1
        val a = UvcAssembler()
        val packets = payloads(frame(4), fid = 1)
        val buffer = ByteArray(16_384)
        assertNull(a.feedAll(packets.take(1)).singleOrNull())
        assertNull("empty transfer", a.feed(buffer, 0))
        assertNull("one byte", a.feed(byteArrayOf(2), 1))
        assertNull("header length 0", a.feed(byteArrayOf(0, EOF.toByte(), 9, 9), 4))
        assertNull("header length 1", a.feed(byteArrayOf(1, EOF.toByte(), 9, 9), 4))
        assertNull("header longer than the payload", a.feed(byteArrayOf(12, EOF.toByte(), 9, 9), 4))
        assertEquals(5, a.badHeader)
        assertEquals("rejected header=5 err=0 short=0 oversize=0 magic=0", a.toString())
        assertArrayEquals(frame(4), a.feedAll(packets.drop(1)).single())
        assertEquals(5, a.rejected)
    }

    @Test
    fun headerOnlyPayloadsBetweenFramesAreNotRejections() { // 001-AC1
        val a = UvcAssembler()
        val packets = listOf(payload(0), payload(FID), payload(0)) + payloads(frame(0), fid = FID) + payload(0) + payload(EOF)
        assertArrayEquals(frame(0), a.feedAll(packets).single())
        assertEquals(0, a.rejected)
    }

    @Test
    fun boundaryPayloadThatAlsoEndsTheNextFrame() { // 001-AC1
        val a = UvcAssembler()
        // FID toggle completes frame 0; the same payload's EOF ends a short frame: frame 0 is still delivered.
        val shortTail = a.feedAll(payloads(frame(0), fid = 0, eof = false) + payload(FID or EOF, ByteArray(10)))
        assertArrayEquals(frame(0), shortTail.single())
        assertEquals(1, a.short)
        // FID toggle completes frame 1; the same payload is a whole frame with EOF: the newest frame wins.
        val whole = byteArrayOf(2, (FID or EOF).toByte()) + frame(3)
        val out = a.feedAll(payloads(frame(1), fid = 0, eof = false)) + listOfNotNull(a.feed(whole, whole.size)?.copyOf())
        assertArrayEquals(frame(3), out.single())
    }

    @Test
    fun deliveredFramesAlternateBetweenTwoBuffersAndStayIntactUntilTheNextFeed() { // 001-AC1
        val a = UvcAssembler()
        fun deliver(k: Int): ByteArray {
            val packets = payloads(frame(k), fid = k % 2)
            for (p in packets.dropLast(1)) assertNull(a.feed(p, p.size))
            return a.feed(packets.last(), packets.last().size)!!
        }
        val f0 = deliver(0)
        val f1 = deliver(1)
        assertNotSame(f0, f1)
        assertArrayEquals("assembling frame 1 left the returned frame 0 alone", frame(0), f0)
        assertSame(f0, deliver(2))
        assertSame(f1, deliver(3))
    }

    @Test
    fun probeLaysOutUvcFieldsLittleEndian() { // 001-AC1
        val expected = ByteArray(34)
        expected[0] = 1 // bmHint: dwFrameInterval
        expected[2] = 1 // bFormatIndex: YUYV
        expected[3] = 5 // bFrameIndex: 4x2321
        byteArrayOf(0x80.toByte(), 0x1a, 0x06, 0x00).copyInto(expected, 4) // 400000 x 100 ns = 25 fps
        assertArrayEquals(expected, buildProbe(34))
        assertArrayEquals(expected.copyOf(26), buildProbe(26))
        assertArrayEquals(expected.copyOf(48), buildProbe(48))
    }

    @Test
    fun parsesAProbeResponse() { // 001-AC1
        val r = buildProbe(34)
        byteArrayOf(0x20, 0x1c, 0x02, 0x00).copyInto(r, 18) // dwMaxVideoFrameSize 138272
        byteArrayOf(0x94.toByte(), 0x48, 0x00, 0x00).copyInto(r, 22) // dwMaxPayloadTransferSize 18580
        assertEquals(Probe(1, 5, 400_000, 138_272, 18_580), parseProbe(r, 34))
        assertEquals(Probe(1, 5, 400_000, 138_272, 18_580), parseProbe(r, 26))
        assertNull(parseProbe(r, 25))
        assertNull("failed GET_CUR", parseProbe(r, -1))
    }

    @Test
    fun probeIsAcceptedOnlyWhenFullyWrittenAndMode2IsEchoed() { // 001-AC1
        val mode2 = Probe(1, 5, 400_000, 138_272, 6_916)
        assertTrue(probeAccepted(34, 34, mode2))
        assertTrue("the camera may change the other fields", probeAccepted(26, 26, mode2.copy(frameInterval = 333_333, maxPayloadSize = 0)))
        assertFalse("short SET_CUR", probeAccepted(34, 26, mode2))
        assertFalse("failed SET_CUR", probeAccepted(34, -1, mode2))
        assertFalse("failed or short GET_CUR", probeAccepted(34, 34, null))
        assertFalse("other format", probeAccepted(34, 34, mode2.copy(formatIndex = 2)))
        assertFalse("other frame", probeAccepted(34, 34, mode2.copy(frameIndex = 1)))
    }

    @Test
    fun commitIsAcceptedOnlyWhenTheWholeResponseIsWrittenBack() { // 001-AC1
        assertTrue(commitAccepted(34, 34))
        assertTrue(commitAccepted(26, 26))
        assertFalse("short commit", commitAccepted(34, 26))
        assertFalse("failed commit", commitAccepted(34, -1))
        assertFalse("no probe response to commit", commitAccepted(-1, -1))
        assertFalse("response shorter than a probe", commitAccepted(25, 25))
    }

    @Test
    fun onlyConsecutiveFastFailuresMeanTheCameraIsGone() { // 001-AC8
        val w = ReadWatch(250, 0)
        assertFalse(w.gone(-1, 0))
        assertFalse(w.gone(-1, 124))
        assertTrue("third fast -1 in a row", w.gone(-1, 3))
        assertTrue("stays gone", w.gone(-1, 3))

        val timeouts = ReadWatch(250, 0)
        repeat(10) { assertFalse("timeouts (slow -1) are a quiet camera, not an unplug", timeouts.gone(-1, 125)) }

        val data = ReadWatch(250, 0)
        assertFalse(data.gone(-1, 1))
        assertFalse(data.gone(-1, 1))
        assertFalse("a read with data resets the count", data.gone(6_916, 1))
        assertFalse(data.gone(-1, 1))
        assertFalse(data.gone(0, 1))
        assertFalse(data.gone(-1, 1))
        assertFalse("a timeout neither counts nor resets", data.gone(-1, 250))
        assertFalse(data.gone(-1, 1))
        assertTrue(data.gone(-1, 1))
    }

    @Test
    fun aStreamWithoutFramesFor3sIsStalled() { // 001-AC8
        val w = ReadWatch(250, 1_000)
        assertFalse(w.stalled(1_000))
        assertFalse("measured from commit", w.stalled(3_999))
        assertTrue(w.stalled(4_000))
        w.frame(4_000)
        assertFalse("measured from the last frame", w.stalled(6_999))
        assertTrue(w.stalled(7_000))
    }

    @Test
    fun aBulkReadHoldsExactlyOnePayload() { // 001-AC1
        assertEquals("the TC001 Mini's dwMaxPayloadTransferSize", 6_916, readSize(6_916))
        assertEquals(1, readSize(1))
        assertEquals(1 shl 20, readSize(1 shl 20))
        assertEquals("capped", 1 shl 20, readSize((1 shl 20) + 1))
        assertEquals("not reported", 16_384, readSize(0))
        assertEquals("u32 above 2 GiB", 16_384, readSize(Int.MIN_VALUE))
    }

    @Test
    fun probeLengthFollowsBcdUvc() { // 001-AC1
        val device = byteArrayOf(18, 1) + ByteArray(16)
        val config = byteArrayOf(9, 2) + ByteArray(7)
        val intf = byteArrayOf(9, 4, 0, 0, 0, 0x0e, 0x01, 0, 0)
        fun header(bcd: Int) = byteArrayOf(13, 0x24, 0x01, bcd.toByte(), (bcd shr 8).toByte()) + ByteArray(8)
        assertEquals(26, probeLength(device + config + intf + header(0x0100)))
        assertEquals(34, probeLength(device + config + intf + header(0x0110)))
        assertEquals(48, probeLength(device + config + intf + header(0x0150)))
        assertEquals("no VideoControl header", 34, probeLength(device + config + intf))
        assertEquals("zero-length descriptor stops the walk", 34, probeLength(device + byteArrayOf(0, 0x24, 0x01, 0, 1)))
        assertEquals("truncated header", 34, probeLength(device + byteArrayOf(13, 0x24, 0x01, 0x00)))
        assertEquals("VS input header is subtype 1 of another interface: first header wins", 26,
            probeLength(device + header(0x0100) + header(0x0150)))
        assertEquals("CS_INTERFACE of another subtype is skipped", 48,
            probeLength(device + byteArrayOf(5, 0x24, 0x02, 0, 0) + header(0x0150)))
        assertEquals(34, probeLength(ByteArray(0)))
    }
}
