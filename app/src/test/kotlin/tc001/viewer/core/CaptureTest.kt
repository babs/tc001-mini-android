package tc001.viewer.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun aVolumePressTakesOnePhotoAndKeepsTheVolumeWhileASourceRuns() { // 002-AC5
        assertEquals(VolumeKey.PHOTO, volumeKey(down = true, repeat = 0, sourceRunning = true))
        assertEquals("held key: repeats take no photo", VolumeKey.CONSUME, volumeKey(down = true, repeat = 1, sourceRunning = true))
        assertEquals(VolumeKey.CONSUME, volumeKey(down = true, repeat = 30, sourceRunning = true))
        assertEquals("release: swallowed, no photo", VolumeKey.CONSUME, volumeKey(down = false, repeat = 0, sourceRunning = true))
    }

    @Test
    fun withoutASourceTheSystemGetsTheVolumeKeys() { // 002-AC5
        for (down in listOf(true, false)) for (repeat in listOf(0, 1)) {
            assertEquals("down=$down repeat=$repeat", VolumeKey.PASS, volumeKey(down, repeat, sourceRunning = false))
        }
    }

    /** Flat JSON object of strings, numbers and booleans, as [photoJson] writes it. */
    private fun parseFlat(json: String): Map<String, String> {
        val body = Regex("""\{(.*)}""").matchEntire(json)!!.groupValues[1]
        return Regex(""""(\w+)":("[^"]*"|[^,"]+)""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
            .also { m -> assertEquals("nothing else in the object", json, m.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }) }
    }

    private fun readouts(locked: Boolean): Readouts {
        val p = FrameProcessor()
        p.locked = locked
        p.process(capture.copyOf(FRAME), 0f)
        return Readouts().apply { fill(p) }
    }

    @Test
    fun photoJsonHoldsTheFrameReadoutsAndSettings() { // 002-AC7
        val r = readouts(locked = true)
        val millis = LocalDateTime.of(2026, 11, 20, 7, 5, 9, 42_000_000).toInstant(ZoneOffset.UTC).toEpochMilli()
        val s = Settings(Palette.WHITE_HOT, quarterTurns = 3, mirror = true, smoothing = 0.25f)
        val m = parseFlat(photoJson(r, s, millis, ZoneId.of("Europe/Paris")))
        val buf = CharArray(21)
        fun c(v: Float) = String(buf, 0, formatCelsius(v, buf))
        assertEquals(
            mapOf(
                "min" to c(r.min), "center" to c(r.centre), "max" to c(r.max), "lo" to c(r.lo), "hi" to c(r.hi),
                "palette" to "\"white_hot\"", "rotation" to "270", "mirror" to "true", "smoothing" to "0.25", "locked" to "true",
                "time" to "\"2026-11-20T08:05:09.042+01:00\"",
            ),
            m,
        )
        assertTrue("one decimal", m.getValue("min").matches(Regex("""-?\d+\.\d""")))
        assertEquals("locked comes from the frame", "false", parseFlat(photoJson(readouts(locked = false), s, millis)).getValue("locked"))
        assertTrue("UTC offset as Z", photoJson(r, s, millis, ZoneOffset.UTC).endsWith(""""time":"2026-11-20T07:05:09.042Z"}"""))
    }

    private class Chunk(val type: String, val data: ByteArray, val crcOk: Boolean)

    private fun chunks(png: ByteArray): List<Chunk> {
        val b = ByteBuffer.wrap(png, 8, png.size - 8)
        val out = ArrayList<Chunk>()
        while (b.hasRemaining()) {
            val data = ByteArray(b.int)
            val type = ByteArray(4).also { b.get(it) }
            b.get(data)
            val crc = CRC32().apply { update(type); update(data) }.value.toInt()
            out += Chunk(String(type, Charsets.ISO_8859_1), data, crc == b.int)
        }
        return out
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(t); update(data) }.value.toInt()
        return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(t).put(data).putInt(crc).array()
    }

    /** 5 x 3 RGB PNG built as an encoder does (filter byte 0 per row), with its raw rows. */
    private fun samplePng(): Pair<ByteArray, ByteArray> {
        val rows = ByteArray(3 * (1 + 5 * 3)) { if (it % 16 == 0) 0 else (it * 37).toByte() }
        val idat = Deflater().run {
            setInput(rows)
            finish()
            ByteArray(256).let { it.copyOf(deflate(it)) }
        }
        val ihdr = ByteBuffer.allocate(13).putInt(5).putInt(3).put(8).put(2).put(0).put(0).put(0).array()
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        return rows to signature + chunk("IHDR", ihdr) + chunk("IDAT", idat) + chunk("IEND", ByteArray(0))
    }

    private fun withText(png: ByteArray, keyword: String, text: String) =
        ByteArrayOutputStream().also { writePngWithText(png, keyword, text, it) }.toByteArray()

    @Test
    fun theJsonIsATextChunkRightAfterIhdrAndThePngStillDecodes() { // 002-AC7
        val (rows, png) = samplePng()
        val json = photoJson(readouts(locked = false), Settings(), 0L, ZoneOffset.UTC)
        val out = withText(png, "tc001", json)

        assertArrayEquals("signature", png.copyOf(8), out.copyOf(8))
        val list = chunks(out)
        assertEquals(listOf("IHDR", "tEXt"), list.take(2).map { it.type })
        assertEquals("IEND", list.last().type)
        assertTrue("every CRC valid", list.all { it.crcOk })
        assertEquals("the original chunks, untouched", chunks(png).map { it.type to it.data.toList() }, (list - list[1]).map { it.type to it.data.toList() })
        assertEquals("tc001\u0000$json", String(list[1].data, Charsets.ISO_8859_1))
        assertEquals("the JSON round-trips", 11, parseFlat(json).size)

        val pixels = Inflater().run {
            setInput(list.single { it.type == "IDAT" }.data)
            ByteArray(rows.size + 1).let { it.copyOf(inflate(it)) }
        }
        assertArrayEquals("the image data still inflates to the same rows", rows, pixels)
    }

    @Test
    fun onlyAPngStartingWithIhdrTakesTheChunk() { // 002-AC7
        val (_, png) = samplePng()
        assertThrows(IllegalArgumentException::class.java) { withText(png.copyOf(20), "k", "v") }
        assertThrows(IllegalArgumentException::class.java) { withText(png.copyOf().also { it[0] = 0 }, "k", "v") }
        assertThrows("IHDR length", IllegalArgumentException::class.java) { withText(png.copyOf().also { it[11] = 12 }, "k", "v") }
        assertThrows("IHDR type", IllegalArgumentException::class.java) { withText(png.copyOf().also { it[12] = 'X'.code.toByte() }, "k", "v") }
    }

    @Test
    fun theChunkFollowsThePngTextRules() { // 002-AC7
        val (_, png) = samplePng()
        assertThrows("empty keyword", IllegalArgumentException::class.java) { withText(png, "", "v") }
        assertThrows("80-char keyword", IllegalArgumentException::class.java) { withText(png, "k".repeat(80), "v") }
        assertEquals("79 chars is the limit", "tEXt", chunks(withText(png, "k".repeat(79), "v"))[1].type)
        assertThrows("NUL in keyword", IllegalArgumentException::class.java) { withText(png, "a\u0000b", "v") }
        assertThrows("NUL in text", IllegalArgumentException::class.java) { withText(png, "k", "a\u0000b") }
        assertThrows("text not Latin-1", IllegalArgumentException::class.java) { withText(png, "k", "23.1 \u2103") }
        assertThrows("keyword not Latin-1", IllegalArgumentException::class.java) { withText(png, "t\u2103", "v") }
        assertEquals("Latin-1 is fine", "k\u0000°C", String(chunks(withText(png, "k", "°C"))[1].data, Charsets.ISO_8859_1))
    }
}
