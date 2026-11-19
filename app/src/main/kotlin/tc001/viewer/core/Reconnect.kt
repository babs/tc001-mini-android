package tc001.viewer.core

/**
 * Reconnect budget after a stream error: a camera just powered up may send a frame then pause, and a fresh
 * probe/commit a moment later streams. A session that [delivered] [RESET_FRAMES] frames earned a fresh budget.
 */
class Reconnect {
    var attempts = 0
        private set

    /**
     * Delay before the next reconnect, or null to give up: the camera is [gone] or not [permitted], the error is not
     * [retryable], or the budget is spent.
     */
    fun next(gone: Boolean, permitted: Boolean, retryable: Boolean, delivered: Int): Long? {
        if (gone || !permitted || !retryable) return null
        if (delivered >= RESET_FRAMES) attempts = 0
        return DELAYS_MS.getOrNull(attempts)?.also { attempts++ }
    }

    fun reset() {
        attempts = 0
    }

    companion object {
        val DELAYS_MS = longArrayOf(500, 1_000, 2_000, 3_000, 4_000)
        const val RESET_FRAMES = 50 // 2 s at 25 fps
    }
}
