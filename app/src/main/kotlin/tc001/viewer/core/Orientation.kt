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

/** The box a [width] x [height] area shows a user whose phone is turned by [turns] quarters (003-AC2, 003-AC3). */
fun turnedBox(width: Int, height: Int, turns: Int) = if (turns % 2 != 0) height to width else width to height

/** The composed picture's width and height for a phone turned by [turns] quarters: the scale band below upright, beside sideways (003-AC10). */
fun pictureSize(turns: Int) = turnedBox(PICTURE_WIDTH, PICTURE_HEIGHT, turns)

/** Left edge of the thermal image in the picture for [turns]: at 90° the band is on the user's left, the phone's portrait bottom edge (003-AC8). */
fun thermalLeft(turns: Int) = if (turns.mod(4) == 1) BAND else 0

/** Left edge of the sideways scale band in the picture for [turns]: beside the thermal image, on the phone's portrait bottom edge (003-AC8). */
fun bandLeft(turns: Int) = if (turns.mod(4) == 1) 0 else PICTURE_WIDTH

/** The phone turns to draw for: [held] from Record to Stop (003-AC6), else the [phone]'s. */
fun turnToDraw(phone: Int, held: Int?) = held ?: phone

/**
 * Turns for [orient] / [orientedIndex] that apply the Rotate setting [quarterTurns] and [mirror], then turn the
 * result clockwise by [phone] quarters: mirroring reverses the direction of every turn applied after it.
 */
fun pictureTurns(quarterTurns: Int, mirror: Boolean, phone: Int) =
    (if (mirror) quarterTurns - phone else quarterTurns + phone).mod(4)
