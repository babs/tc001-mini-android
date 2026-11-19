package tc001.viewer.core

/** 3x3 box mean with edge padding, SIZE x SIZE. */
fun box3(t: FloatArray, out: FloatArray = FloatArray(SIZE * SIZE)): FloatArray {
    for (r in 0 until SIZE) for (c in 0 until SIZE) {
        var sum = 0f
        for (dr in -1..1) {
            val row = (r + dr).coerceIn(0, SIZE - 1) * SIZE
            for (dc in -1..1) sum += t[row + (c + dc).coerceIn(0, SIZE - 1)]
        }
        out[r * SIZE + c] = sum / 9f
    }
    return out
}

/**
 * Hot/cold spot tracker on the raw plane: a spot only moves when a new place beats it by [hyst] C
 * (noise-proof).
 */
class Extremes(private val hyst: Float = 0.3f, private val alpha: Float = 0.3f) {
    private val smooth = FloatArray(SIZE * SIZE)
    private val index = intArrayOf(-1, -1)
    private val value = FloatArray(2)

    /** False until the first [update]; positions are meaningless before that. */
    val ready get() = index[0] >= 0
    val hotRow get() = index[0] / SIZE
    val hotCol get() = index[0] % SIZE
    val hotCelsius get() = value[0]
    val coldRow get() = index[1] / SIZE
    val coldCol get() = index[1] % SIZE
    val coldCelsius get() = value[1]

    fun update(t: FloatArray) {
        box3(t, smooth)
        track(0, 1f)
        track(1, -1f)
    }

    private fun track(k: Int, sign: Float) {
        var cand = 0
        for (i in 1 until smooth.size) if (sign * smooth[i] > sign * smooth[cand]) cand = i
        val cur = index[k]
        if (cur < 0 || sign * (smooth[cand] - smooth[cur]) > hyst) {
            index[k] = cand
            value[k] = smooth[cand]
        } else {
            value[k] += alpha * (smooth[cur] - value[k]) // EMA steadies the label
        }
    }
}
