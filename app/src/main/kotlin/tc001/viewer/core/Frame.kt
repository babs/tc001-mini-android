package tc001.viewer.core

const val SIZE = 96
const val HEADER = 136
const val FRAME = HEADER + SIZE * SIZE * 2
const val MAGIC = 0x70827773
const val MARKER = 0xAABBCCDD.toInt()

private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

/**
 * Celsius plane (row-major SIZE x SIZE) of the mode-2 frame at [offset], written into [out], or null
 * (out untouched) when fewer than FRAME bytes remain or the magic / marker do not match.
 * [bytes] is only read.
 */
fun parse(bytes: ByteArray, offset: Int = 0, out: FloatArray = FloatArray(SIZE * SIZE)): FloatArray? {
    if (offset < 0 || bytes.size - offset < FRAME) return null
    if (u32(bytes, offset) != MAGIC || u32(bytes, offset + HEADER - 4) != MARKER) return null
    val base = offset + HEADER
    for (i in 0 until SIZE * SIZE) out[i] = u16(bytes, base + 2 * i) / 64f - 50f
    return out
}
