package tc001.viewer.core

/**
 * One-slot hand-off from a producer thread to a consumer thread over two preallocated buffers: the newest
 * frame wins, nothing is allocated per frame, and the producer never writes the buffer the consumer holds.
 */
class Mailbox<S, T>(private var slot: T, private var held: T, private val copy: (S, T) -> Unit) {
    private val lock = Object()
    private var fresh = false
    private var closed = false

    /** Producer: copies [frame] into the slot, replacing a frame not yet taken; false once closed. */
    fun put(frame: S): Boolean = synchronized(lock) {
        if (closed) return false
        copy(frame, slot)
        fresh = true
        lock.notifyAll()
        true
    }

    /** Consumer: waits for a frame not seen yet, null once closed; the buffer stays its own until the next take. */
    fun take(): T? = synchronized(lock) {
        while (!fresh && !closed) lock.wait()
        if (closed) return null
        fresh = false
        val frame = slot
        slot = held
        held = frame
        frame
    }

    /** Any thread, never waits on the consumer: [take] returns null and [put] refuses from now on. */
    fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }
}
