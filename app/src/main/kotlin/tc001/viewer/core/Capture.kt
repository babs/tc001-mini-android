package tc001.viewer.core

import java.io.OutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32

// Milliseconds: two photos in the same second must not collide.
private val NAME = DateTimeFormatter.ofPattern("'TC001_'yyyyMMdd_HHmmss_SSS")

/** Base name of a photo or video taken at [epochMillis], in [zone] local time: `TC001_yyyyMMdd_HHmmss_SSS`. */
fun captureName(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    NAME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

/**
 * The photo's metadata (002-AC7) as one flat JSON object: the frame's [r] readouts, the view settings [s] it
 * was drawn with, and [epochMillis] as ISO 8601 local time with its offset in [zone].
 */
fun photoJson(r: Readouts, s: Settings, epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val out = CharArray(21)
    fun c(celsius: Float) = String(out, 0, formatCelsius(celsius, out))
    return """{"min":${c(r.min)},"center":${c(r.centre)},"max":${c(r.max)},"lo":${c(r.lo)},"hi":${c(r.hi)},""" +
        """"palette":"${s.palette.name.lowercase()}","rotation":${s.quarterTurns * 90},"mirror":${s.mirror},""" +
        """"smoothing":${s.smoothing},"locked":${r.locked},"time":"${TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))}"}"""
}

private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
private const val IHDR_END = 8 + 4 + 4 + 13 + 4

/** Writes [png] to [out] with a `tEXt` chunk [keyword] = [text] inserted right after IHDR, so readers see it before the image data. */
fun writePngWithText(png: ByteArray, keyword: String, text: String, out: OutputStream) {
    val ihdr = png.size > IHDR_END && ByteBuffer.wrap(png, 8, 8).run { int == 13 && int == 0x49484452 }
    require(ihdr && png.copyOf(8).contentEquals(PNG_SIGNATURE)) { "not a PNG starting with IHDR" }
    // PNG tEXt rules: a 1-79 char keyword, a NUL separator, Latin-1 throughout.
    require(keyword.length in 1..79) { "tEXt keyword must be 1-79 chars" }
    require('\u0000' !in keyword && '\u0000' !in text) { "tEXt keyword and text must not hold a NUL" }
    val latin1 = Charsets.ISO_8859_1.newEncoder()
    require(latin1.canEncode(keyword) && latin1.canEncode(text)) { "tEXt is Latin-1 only" }
    val data = "$keyword\u0000$text".toByteArray(Charsets.ISO_8859_1)
    val chunk = ByteBuffer.allocate(12 + data.size).putInt(data.size).put("tEXt".toByteArray(Charsets.ISO_8859_1)).put(data)
    val crc = CRC32().apply { update(chunk.array(), 4, 4 + data.size) }
    chunk.putInt(crc.value.toInt())
    out.write(png, 0, IHDR_END)
    out.write(chunk.array())
    out.write(png, IHDR_END, png.size - IHDR_END)
}

/** The raw frame kept with a photo: a copy, as the source reuses its buffer once the frame callback returns. */
fun rawSnapshot(frame: ByteArray): ByteArray = frame.copyOf(FRAME)

/**
 * Rebases encoder timestamps (microseconds, taken from the real time each frame was queued) so the video
 * starts at 0 and only moves forward; gaps from dropped frames are kept, so the duration stays real.
 */
class PtsRebase {
    private var first = 0L
    private var last = -1L

    fun next(ptsUs: Long): Long {
        if (last < 0) first = ptsUs
        last = maxOf(ptsUs - first, last + 1)
        return last
    }
}

/** What a volume key event does (002-AC5). */
enum class VolumeKey { PHOTO, CONSUME, PASS }

/** While a source runs, a press takes one photo (a held key's repeats only swallow) and the volume stays; else the system has the key. */
fun volumeKey(down: Boolean, repeat: Int, sourceRunning: Boolean) = when {
    !sourceRunning -> VolumeKey.PASS
    down && repeat == 0 -> VolumeKey.PHOTO
    else -> VolumeKey.CONSUME
}
