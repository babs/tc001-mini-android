package tc001.viewer.core

/** Mode 2: YUYV format 1, frame 5 (4x2321), 25 fps in 100 ns units. */
const val FORMAT_INDEX = 1
const val FRAME_INDEX = 5
const val FRAME_INTERVAL = 400_000

/** The UVC 1.0 probe, also the fields [parseProbe] needs. */
const val PROBE_MIN = 26

/** Probe/commit control fields the driver checks. */
data class Probe(val formatIndex: Int, val frameIndex: Int, val frameInterval: Int, val maxFrameSize: Int, val maxPayloadSize: Int)

/**
 * Probe/commit length for the bcdUVC of the first VideoControl header in raw USB [descriptors]:
 * 26 (UVC 1.0), 34 (1.1), 48 (1.5); 34 when there is none, as the TC002C Duo driver sends.
 */
fun probeLength(descriptors: ByteArray): Int {
    var i = 0
    while (i + 4 < descriptors.size) {
        val length = descriptors[i].toInt() and 0xff
        if (length < 2) break
        // CS_INTERFACE / VC_HEADER: interface 0 is the VideoControl one, so the first header is its own.
        if (descriptors[i + 1] == 0x24.toByte() && descriptors[i + 2] == 0x01.toByte()) {
            val bcd = u16(descriptors, i + 3)
            return if (bcd < 0x0110) 26 else if (bcd < 0x0150) 34 else 48
        }
        i += length
    }
    return 34
}

/** SET_CUR(PROBE) payload of [length] bytes: bmHint = 1 (keep dwFrameInterval), mode 2 at 25 fps. */
fun buildProbe(length: Int): ByteArray {
    val p = ByteArray(length)
    p[0] = 1
    p[2] = FORMAT_INDEX.toByte()
    p[3] = FRAME_INDEX.toByte()
    for (k in 0 until 4) p[4 + k] = (FRAME_INTERVAL ushr 8 * k).toByte()
    return p
}

/** Fields of a GET_CUR(PROBE) response of [length] bytes, or null when shorter than [PROBE_MIN]. */
fun parseProbe(b: ByteArray, length: Int): Probe? =
    if (length < PROBE_MIN) null else Probe(b[2].toInt() and 0xff, b[3].toInt() and 0xff, u32(b, 4), u32(b, 18), u32(b, 22))

/**
 * Bulk read size: one payload, so a read never runs into the next one when a payload ends on a full packet.
 * 16 KiB when the camera reports no size (0, or above 2 GiB read as negative); capped at 1 MiB.
 */
fun readSize(maxPayload: Int) = if (maxPayload > 0) minOf(maxPayload, 1 shl 20) else 16_384

/** True when SET_CUR wrote all [length] probe bytes and the GET_CUR [mode] echoes mode 2. */
fun probeAccepted(length: Int, set: Int, mode: Probe?) =
    set == length && mode != null && mode.formatIndex == FORMAT_INDEX && mode.frameIndex == FRAME_INDEX

/** True when SET_CUR(COMMIT) wrote back the whole [got]-byte GET_CUR response. */
fun commitAccepted(got: Int, commit: Int) = got >= PROBE_MIN && commit == got

/**
 * Bulk read-loop verdicts. bulkTransfer returns -1 both on timeout and on error: only [GONE_AFTER] fast ones
 * in a row (under half of [timeoutMs]) mean the device is gone. No frame for [STALL_MS] means a stalled stream.
 */
class ReadWatch(private val timeoutMs: Int, startMs: Long) {
    private var failures = 0
    private var lastFrameMs = startMs

    /** Records a read that returned [result] after [elapsedMs]; true when the device is gone. */
    fun gone(result: Int, elapsedMs: Long): Boolean {
        if (result >= 0) failures = 0 else if (elapsedMs < timeoutMs / 2) failures++
        return failures >= GONE_AFTER
    }

    fun frame(nowMs: Long) {
        lastFrameMs = nowMs
    }

    fun stalled(nowMs: Long) = nowMs - lastFrameMs >= STALL_MS

    companion object {
        const val GONE_AFTER = 3
        const val STALL_MS = 3_000L
    }
}

/**
 * Reassembles UVC bulk payloads into mode-2 frames. A frame ends on an FID toggle or EOF and is delivered only
 * when exactly FRAME bytes long with the frame magic and marker; an ERR payload drops the rest of its frame.
 * Two preallocated buffers, no per-payload allocation: a returned frame stays intact until the next [feed].
 */
class UvcAssembler {
    private var data = ByteArray(FRAME)
    private var spare = ByteArray(FRAME)
    private var length = 0
    private var fid = -1
    private var discarding = false

    var badHeader = 0
        private set
    var uvcError = 0
        private set
    var short = 0
        private set
    var oversize = 0
        private set
    var badMagic = 0
        private set
    val rejected get() = badHeader + uvcError + short + oversize + badMagic

    /** Feeds the first [size] bytes of [packet], one UVC payload; returns a completed frame or null. */
    fun feed(packet: ByteArray, size: Int): ByteArray? {
        val headerLength = if (size >= 2) packet[0].toInt() and 0xff else 0
        if (headerLength < 2 || headerLength > size) {
            badHeader++
            return null
        }
        val flags = packet[1].toInt()
        val fid = flags and FID
        if (flags and ERR != 0) {
            uvcError++
            length = 0
            this.fid = fid
            discarding = true
            return null
        }
        var done: ByteArray? = null
        if (this.fid < 0) {
            this.fid = fid
        } else if (fid != this.fid) {
            if (!discarding) done = finish()
            this.fid = fid
            discarding = false
        }
        if (!discarding) {
            val payload = size - headerLength
            if (length + payload > FRAME) {
                oversize++
                length = 0
                discarding = true
            } else {
                System.arraycopy(packet, headerLength, data, length, payload)
                length += payload
            }
        }
        if (flags and EOF != 0) {
            if (!discarding) done = finish() ?: done
            this.fid = -1
            discarding = false
        }
        return done
    }

    private fun finish(): ByteArray? {
        val n = length
        length = 0
        when {
            n == 0 -> return null // header-only payloads between frames
            n < FRAME -> {
                short++
                return null
            }
            !isFrame(data) -> {
                badMagic++
                return null
            }
        }
        val frame = data
        data = spare
        spare = frame
        return frame
    }

    override fun toString() =
        "rejected header=$badHeader err=$uvcError short=$short oversize=$oversize magic=$badMagic"

    private companion object {
        const val FID = 0x01
        const val EOF = 0x02
        const val ERR = 0x40
    }
}
