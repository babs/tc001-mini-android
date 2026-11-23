package tc001.viewer.core

import kotlin.math.abs

/** How close to a quarter the phone must be to switch to it (003-AC4); between quarters the current one holds. */
const val SNAP_DEGREES = 30

/**
 * Phone quarter turns clockwise from portrait (0..3) for OrientationEventListener [angle] (degrees the phone is
 * turned clockwise, negative when flat): the nearest quarter once within [SNAP_DEGREES] of it, else [current].
 */
fun snapQuarter(angle: Int, current: Int): Int {
    if (angle < 0) return current
    val q = (angle + 45) / 90 % 4
    val d = abs(angle - q * 90)
    return if (minOf(d, 360 - d) <= SNAP_DEGREES) q else current
}

/** The phone turns to draw for: [held] from Record to Stop (003-AC6), else the [phone]'s. */
fun turnToDraw(phone: Int, held: Int?) = held ?: phone

/**
 * Turns for [orient] / [orientedIndex] that apply the Rotate setting [quarterTurns] and [mirror], then turn the
 * result clockwise by [phone] quarters: mirroring reverses the direction of every turn applied after it.
 */
fun pictureTurns(quarterTurns: Int, mirror: Boolean, phone: Int) =
    (if (mirror) quarterTurns - phone else quarterTurns + phone).mod(4)
