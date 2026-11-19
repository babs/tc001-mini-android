package tc001.viewer

import tc001.viewer.core.FRAME

/**
 * Camera stand-in: loops the FRAME-sized chunks of a recorded capture at the camera's 25 fps; a trailing
 * partial chunk is ignored.
 */
class ReplaySource(capture: ByteArray) : FrameSource {
    private val frames = List(capture.size / FRAME) { capture.copyOfRange(it * FRAME, (it + 1) * FRAME) }
    private var thread: Thread? = null

    override fun start(consumer: (ByteArray) -> Unit) {
        check(thread == null) { "already started" }
        thread = Thread({
            var i = 0
            var next = System.nanoTime()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    consumer(frames[i])
                    i = (i + 1) % frames.size
                    next += PERIOD_NS
                    val wait = next - System.nanoTime()
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    } else {
                        next = System.nanoTime() // behind schedule (slow consumer, debugger): no catch-up burst
                    }
                }
            } catch (_: InterruptedException) {
            }
        }, "replay").apply { start() }
    }

    override fun stop() {
        thread?.run {
            interrupt()
            join()
        }
        thread = null
    }

    private companion object {
        const val PERIOD_NS = 40_000_000L
    }
}
