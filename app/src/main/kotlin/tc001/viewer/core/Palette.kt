package tc001.viewer.core

/** 256-entry ARGB lookup tables, index 0 = coldest. */
enum class Palette(val lut: IntArray) {
    IRONBOW(
        lut(
            doubleArrayOf(0.0, 0.25, 0.5, 0.75, 0.9, 1.0),
            intArrayOf(0x000000, 0x50008C, 0xC81E3C, 0xFA8200, 0xFFDC28, 0xFFFFFF),
        ),
    ),
    WHITE_HOT(lut(doubleArrayOf(0.0, 1.0), intArrayOf(0x000000, 0xFFFFFF))),
    BLACK_HOT(lut(doubleArrayOf(0.0, 1.0), intArrayOf(0xFFFFFF, 0x000000))),
}

// Linear interpolation between stops in double, truncated to 0..255 (not rounded).
private fun lut(stops: DoubleArray, rgb: IntArray) = IntArray(256) { i ->
    val x = i / 255.0
    val k = (1 until stops.size).first { x <= stops[it] }
    val w = (x - stops[k - 1]) / (stops[k] - stops[k - 1])
    var argb = 0xFF shl 24
    for (shift in intArrayOf(16, 8, 0)) {
        val a = (rgb[k - 1] shr shift) and 0xff
        val b = (rgb[k] shr shift) and 0xff
        argb = argb or ((a + w * (b - a)).toInt() shl shift)
    }
    argb
}
