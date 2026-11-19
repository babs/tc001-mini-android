package tc001.viewer.core

/** View settings kept across launches. */
data class Settings(
    val palette: Palette = Palette.IRONBOW,
    val quarterTurns: Int = 0,
    val mirror: Boolean = false,
    val smoothing: Float = 0.5f,
) {
    /** Key/values to store, read back by [from]. */
    fun toMap(): Map<String, Any> = mapOf(
        PALETTE to palette.name,
        QUARTER_TURNS to quarterTurns,
        MIRROR to mirror,
        SMOOTHING to smoothing,
    )

    companion object {
        const val PALETTE = "palette"
        const val QUARTER_TURNS = "quarterTurns"
        const val MIRROR = "mirror"
        const val SMOOTHING = "smoothing"

        /** Settings from stored key/values ([toMap], SharedPreferences.getAll); missing, mistyped or out-of-range ones get defaults. */
        fun from(stored: Map<String, *>) = Settings().run {
            Settings(
                Palette.entries.firstOrNull { it.name == stored[PALETTE] } ?: palette,
                (stored[QUARTER_TURNS] as? Int)?.takeIf { it in 0..3 } ?: quarterTurns,
                stored[MIRROR] as? Boolean ?: mirror,
                (stored[SMOOTHING] as? Float)?.takeIf { it in 0f..1f } ?: smoothing,
            )
        }
    }
}
