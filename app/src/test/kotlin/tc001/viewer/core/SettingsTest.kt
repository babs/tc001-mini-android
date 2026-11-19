package tc001.viewer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    private val stored = mapOf(
        Settings.PALETTE to "BLACK_HOT",
        Settings.QUARTER_TURNS to 3,
        Settings.MIRROR to true,
        Settings.SMOOTHING to 0f,
    )

    @Test
    fun readsStoredValues() { // 001-AC5
        assertEquals(Settings(Palette.BLACK_HOT, 3, true, 0f), Settings.from(stored))
        for (p in Palette.entries) assertEquals(p, Settings.from(mapOf(Settings.PALETTE to p.name)).palette)
    }

    @Test
    fun storedMapRoundTrips() { // 001-AC5
        // Every field off its default in at least one sample: a dropped, renamed or retyped key fails.
        for (s in listOf(Settings(), Settings(Palette.WHITE_HOT, 1, true, 0.25f), Settings(Palette.BLACK_HOT, 3, false, 1f))) {
            assertEquals(s, Settings.from(s.toMap()))
        }
    }

    @Test
    fun defaultsWhenNothingIsStored() { // 001-AC5
        assertEquals(Settings(Palette.IRONBOW, 0, false, 0.5f), Settings.from(emptyMap<String, Any>()))
    }

    @Test
    fun invalidValuesFallBackToDefaults() { // 001-AC5
        val defaults = Settings()
        val bad = listOf(
            Settings.PALETTE to "RAINBOW",
            Settings.PALETTE to 1,
            Settings.QUARTER_TURNS to 4,
            Settings.QUARTER_TURNS to -1,
            Settings.QUARTER_TURNS to "1",
            Settings.MIRROR to "true",
            Settings.SMOOTHING to 1.5f,
            Settings.SMOOTHING to -0.1f,
            Settings.SMOOTHING to Float.NaN,
            Settings.SMOOTHING to 0.5, // Double, not the stored Float type
        )
        for ((key, value) in bad) {
            val parsed = Settings.from(stored + (key to value))
            val expected = when (key) {
                Settings.PALETTE -> Settings.from(stored).copy(palette = defaults.palette)
                Settings.QUARTER_TURNS -> Settings.from(stored).copy(quarterTurns = defaults.quarterTurns)
                Settings.MIRROR -> Settings.from(stored).copy(mirror = defaults.mirror)
                else -> Settings.from(stored).copy(smoothing = defaults.smoothing)
            }
            assertEquals("$key = $value", expected, parsed)
        }
    }
}
