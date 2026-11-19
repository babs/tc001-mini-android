package tc001.viewer

/** Delivers raw 18,568-byte mode-2 frames on its own background thread. */
interface FrameSource {
    /** [consumer] runs on the source thread; the array is only valid during the call. */
    fun start(consumer: (ByteArray) -> Unit)

    /** Returns once the source thread is gone: no [start] consumer call after that. */
    fun stop()
}
