package tc001.viewer.core

/** Mean of the 2x2 centre pixels: the same four pixels whatever the rotation or mirror. */
fun centreCelsius(t: FloatArray): Float {
    val i = (SIZE / 2 - 1) * SIZE + SIZE / 2 - 1
    return (t[i] + t[i + 1] + t[i + SIZE] + t[i + SIZE + 1]) / 4
}

/**
 * Per-frame state of the live view: spots, centre and colour range come from the raw frame, only
 * [shown] is smoothed (display only).
 */
class FrameProcessor {
    private val raw = FloatArray(SIZE * SIZE)
    private val smoother = TemporalSmoother()
    val spots = Extremes()

    /** Written from any thread: true keeps [lo]..[hi] at their values of the last unlocked frame. */
    @Volatile
    var locked = false

    /** Colour range: the raw min/max, at least 0.1 C wide (flat scene). */
    var lo = 0f
        private set
    var hi = 0f
        private set
    private var ranged = false
    var centre = 0f
        private set

    /** Plane to render, owned by this processor and overwritten by the next [process]. */
    var shown = raw
        private set

    /** Takes a raw mode-2 [frame]; false (state untouched) when it does not parse. */
    fun process(frame: ByteArray, strength: Float): Boolean {
        parse(frame, out = raw) ?: return false
        spots.update(raw)
        if (!locked || !ranged) {
            lo = raw.min()
            hi = spanHi(lo, raw.max())
            ranged = true
        }
        centre = centreCelsius(raw)
        shown = smoother.apply(raw, strength)
        return true
    }
}

/** The numbers shown for a frame (readout line, scale labels): one holder refilled in place, no per-frame allocation. */
class Readouts {
    /** Raw 2x2 centre mean. */
    var centre = 0f
        private set

    /** Hot / cold marker values, so the readout matches the markers on the image. */
    var max = 0f
        private set
    var min = 0f
        private set

    /** Scale-bar labels: the colour range, frozen with it under lock range. */
    var lo = 0f
        private set
    var mid = 0f
        private set
    var hi = 0f
        private set

    fun fill(p: FrameProcessor) {
        centre = p.centre
        max = p.spots.hotCelsius
        min = p.spots.coldCelsius
        lo = p.lo
        mid = (p.lo + p.hi) / 2
        hi = p.hi
    }
}
