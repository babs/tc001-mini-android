package tc001.viewer.core

const val SIZE = 96
const val HEADER = 136
const val FRAME = HEADER + SIZE * SIZE * 2
const val MAGIC = 0x70827773
const val MARKER = 0xAABBCCDD.toInt()

internal fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

internal fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

/** True when FRAME bytes at [offset] carry the header magic and marker. */
fun isFrame(bytes: ByteArray, offset: Int = 0) =
    offset >= 0 && bytes.size - offset >= FRAME &&
        u32(bytes, offset) == MAGIC && u32(bytes, offset + HEADER - 4) == MARKER

/**
 * Celsius plane (row-major SIZE x SIZE) of the mode-2 frame at [offset], written into [out], or null
 * (out untouched) when fewer than FRAME bytes remain or the magic / marker do not match.
 * [bytes] is only read.
 */
fun parse(bytes: ByteArray, offset: Int = 0, out: FloatArray = FloatArray(SIZE * SIZE)): FloatArray? {
    if (!isFrame(bytes, offset)) return null
    val base = offset + HEADER
    for (i in 0 until SIZE * SIZE) out[i] = u16(bytes, base + 2 * i) / 64f - 50f
    return out
}
