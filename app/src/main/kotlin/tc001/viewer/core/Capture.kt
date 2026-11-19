package tc001.viewer.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Milliseconds: two photos in the same second must not collide.
private val NAME = DateTimeFormatter.ofPattern("'TC001_'yyyyMMdd_HHmmss_SSS")

/** Base name of a photo or video taken at [epochMillis], in [zone] local time: `TC001_yyyyMMdd_HHmmss_SSS`. */
fun captureName(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    NAME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

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
