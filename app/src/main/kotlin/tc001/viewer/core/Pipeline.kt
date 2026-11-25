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

    /** [locked] as the last [process] read it: whether its [lo]..[hi] were held. */
    var rangeLocked = false
        private set
    var centre = 0f
        private set

    /** Plane to render, owned by this processor and overwritten by the next [process]. */
    var shown = raw
        private set

    /** Takes a raw mode-2 [frame]; false (state untouched) when it does not parse. */
    fun process(frame: ByteArray, strength: Float): Boolean {
        parse(frame, out = raw) ?: return false
        spots.update(raw)
        rangeLocked = locked
        if (!rangeLocked || !ranged) {
            lo = raw.min()
            hi = spanHi(lo, raw.max())
            ranged = true
        }
        centre = centreCelsius(raw)
        shown = smoother.apply(raw, strength)
        return true
    }
}

/** The numbers shown for a frame (marker and centre labels, scale labels, native readouts): one holder refilled in place, no per-frame allocation. */
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

    /** Whether lock range held [lo]..[hi] for this frame. */
    var locked = false
        private set

    fun fill(p: FrameProcessor) {
        centre = p.centre
        max = p.spots.hotCelsius
        min = p.spots.coldCelsius
        lo = p.lo
        mid = (p.lo + p.hi) / 2
        hi = p.hi
        locked = p.rangeLocked
    }
}

/** Cold (Min, cold marker), centre and hot (Max, hot marker) colours, ARGB: one set for the picture and the native readouts. */
const val COLD_COLOR = 0xFF00C8FF.toInt()
const val CENTRE_COLOR = 0xFFFFFFFF.toInt()
const val HOT_COLOR = 0xFFFF4D6D.toInt()

/** A readout's value with no source or before its first frame (004-AC6): figure dashes, one digit wide each. */
const val NO_READING = "\u2012\u2012.\u2012"

/** The native readouts, each in its picture colour; layout in main.xml / MainActivity. */
enum class Readout(val label: String, val color: Int) {
    MIN("Min", COLD_COLOR),
    CENTER("Center", CENTRE_COLOR),
    MAX("Max", HOT_COLOR);

    fun of(r: Readouts) = when (this) {
        MIN -> r.min
        CENTER -> r.centre
        MAX -> r.max
    }

    /** The native text: the picture's [formatCelsius] digits for the same [r]. */
    fun text(r: Readouts): String {
        val out = CharArray(21)
        return String(out, 0, formatCelsius(of(r), out))
    }
}

/**
 * Height of the native readout band under a picture shown full width at the top of a [width] x [height]
 * stage: all the space left, at least [min], at most half the stage so a short one (landscape, split
 * screen) still shows the picture.
 */
fun readoutBand(width: Int, height: Int, min: Int) = minOf(height / 2, maxOf(min, height - width * PICTURE_HEIGHT / PICTURE_WIDTH))

/** Largest Min/Max and Center readout sizes (002-AC4), in sp. */
const val PAIR_MAX_SP = 110f
const val CENTRE_MAX_SP = 140f

/** Min/Max size for a [fit] next to Center at [centre]: kept below it in the caps' ratio, as stacked they share its width (002-AC4, 003-AC3). */
fun pairTextSize(fit: Float, centre: Float) = minOf(fit, centre * PAIR_MAX_SP / CENTRE_MAX_SP)

/** Readout label size: [max], or less so a text [em] sizes wide fits a [width] column (003-AC3: a narrow turned band). */
fun labelTextSize(em: Float, width: Float, max: Float) = fitTextSize(em, 1f, width, Float.POSITIVE_INFINITY, 0f, max)

/** Smallest readout size: [floor] upright (002-AC4); none turned a quarter, where the box is narrow and its width must always bind (003-AC3). */
fun readoutFloor(turns: Int, floor: Float) = if (turns % 2 != 0) 0f else floor

/**
 * Largest text size, at most [max], at which text [em] sizes wide and [lineEm] sizes tall fits a [width] x
 * [height] box (same unit as [min]/[max]). [min] beats only the width bound: a box shorter than one [min]
 * line gets a smaller size rather than a clipped value. Empty text is not width-bound.
 */
fun fitTextSize(em: Float, lineEm: Float, width: Float, height: Float, min: Float, max: Float): Float {
    val byWidth = if (em > 0f) width / em else max
    return minOf(max, height / lineEm, byWidth.coerceAtLeast(min))
}
