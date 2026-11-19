package tc001.viewer

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import tc001.viewer.core.ReadWatch
import tc001.viewer.core.UvcAssembler
import tc001.viewer.core.buildProbe
import tc001.viewer.core.commitAccepted
import tc001.viewer.core.parseProbe
import tc001.viewer.core.probeAccepted
import tc001.viewer.core.probeLength
import tc001.viewer.core.readSize

/**
 * The TC001 Mini over USB: negotiates mode 2 on the streaming interface, then reads its bulk endpoint.
 * The source thread owns the connection from open to close. On a failure it calls [onError] (source
 * thread, string resource of the message, frames delivered) unless [stop] was called.
 */
class UsbSource(private val usb: UsbManager, val device: UsbDevice, private val onError: (Int, Int) -> Unit) : FrameSource {
    private val assembler = UvcAssembler()
    private var thread: Thread? = null
    private val lock = Any()
    private var frames = 0 // this session's, written before the thread starts then by it only

    // Cleared under [lock] by stop(), so an error checked under it cannot be reported once stop() has begun.
    @Volatile
    private var running = false

    /** Payloads and frames dropped so far, for the debug overlay; read across threads, display only. */
    val rejected get() = assembler.rejected

    override fun start(consumer: (ByteArray) -> Unit) {
        check(thread == null) { "already started" }
        running = true
        frames = 0
        thread = Thread({
            val error = stream(consumer)
            if (error != null) synchronized(lock) { if (running) onError(error, frames) }
        }, "usb").apply { start() }
    }

    // bulkTransfer cannot be interrupted: the thread sees the flag within one read timeout.
    override fun stop() {
        synchronized(lock) { running = false }
        thread?.join()
        thread = null
    }

    /** Streams until [stop]; returns null then, else the message of what failed. */
    private fun stream(consumer: (ByteArray) -> Unit): Int? {
        if (device.interfaceCount < 2) return R.string.open_failed.also { Log.w(TAG, "${device.interfaceCount} interfaces") }
        val connection = try {
            usb.openDevice(device)
        } catch (e: SecurityException) {
            Log.w(TAG, "openDevice: no permission", e)
            return R.string.usb_denied
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "openDevice: device gone", e)
            return R.string.disconnected
        } ?: return R.string.open_failed.also { Log.w(TAG, "openDevice returned null") }
        val control = device.getInterface(0)
        val streaming = device.getInterface(1)
        try {
            // force: an Android kernel with uvcvideo may have bound the interfaces.
            if (!connection.claimInterface(control, true) || !connection.claimInterface(streaming, true)) {
                Log.w(TAG, "claimInterface failed")
                return R.string.open_failed
            }
            val endpoint = (0 until streaming.endpointCount).map(streaming::getEndpoint)
                .firstOrNull { it.address == ENDPOINT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                ?: return R.string.open_failed.also { Log.w(TAG, "no bulk endpoint 0x81") }
            val maxPayload = negotiate(connection) ?: return R.string.probe_rejected
            return read(connection, endpoint, ByteArray(readSize(maxPayload)), consumer)
        } finally {
            connection.releaseInterface(streaming)
            connection.releaseInterface(control)
            connection.close()
        }
    }

    /** Probe then commit mode 2; returns dwMaxPayloadTransferSize, or null when the camera refuses or stop() came. */
    private fun negotiate(connection: UsbDeviceConnection): Int? {
        val length = probeLength(connection.rawDescriptors ?: ByteArray(0))
        val probe = buildProbe(length)
        // Each control transfer may take its whole timeout: stop() must not wait for all three.
        val set = connection.controlTransfer(SET, SET_CUR, PROBE, STREAMING, probe, length, CONTROL_TIMEOUT_MS)
        if (!running) return null
        val got = connection.controlTransfer(GET, GET_CUR, PROBE, STREAMING, probe, length, CONTROL_TIMEOUT_MS)
        if (!running) return null
        val mode = parseProbe(probe, got)
        Log.i(TAG, "probe length=$length set=$set get=$got $mode")
        if (!probeAccepted(length, set, mode)) return null
        val commit = connection.controlTransfer(SET, SET_CUR, COMMIT, STREAMING, probe, got, CONTROL_TIMEOUT_MS)
        if (!running) return null
        if (!commitAccepted(got, commit)) {
            Log.w(TAG, "commit wrote $commit/$got")
            return null
        }
        return mode?.maxPayloadSize
    }

    private fun read(connection: UsbDeviceConnection, endpoint: UsbEndpoint, buffer: ByteArray, consumer: (ByteArray) -> Unit): Int? {
        val t0 = SystemClock.uptimeMillis()
        val watch = ReadWatch(READ_TIMEOUT_MS, t0)
        var reported = 0
        var nextReport = t0 + REPORT_MS
        // ponytail: synchronous reads, 464 KB/s fits in the camera's frame buffer between calls; UsbRequest queue if frames drop.
        while (running) {
            val start = SystemClock.uptimeMillis()
            val n = connection.bulkTransfer(endpoint, buffer, buffer.size, READ_TIMEOUT_MS)
            val now = SystemClock.uptimeMillis()
            if (watch.gone(n, now - start)) {
                Log.i(TAG, "bulkTransfer failing, camera gone after $frames frames")
                return R.string.disconnected
            }
            if (n >= 0) {
                assembler.feed(buffer, n)?.let {
                    if (frames++ == 0) Log.i(TAG, "first frame ${now - t0} ms after commit, read buffer ${buffer.size}")
                    watch.frame(now)
                    consumer(it)
                }
            }
            if (watch.stalled(now)) {
                Log.w(TAG, "no frame for ${ReadWatch.STALL_MS} ms after $frames frames, $assembler")
                return R.string.no_frames
            }
            if (now >= nextReport) {
                if (assembler.rejected != reported) Log.w(TAG, "$frames frames, $assembler")
                reported = assembler.rejected
                nextReport = now + REPORT_MS
            }
        }
        Log.i(TAG, "stopped after $frames frames, $assembler")
        return null
    }

    companion object {
        const val VENDOR_ID = 0x2bdf
        const val PRODUCT_ID = 0x0102
        const val TAG = "TC001"
        private const val ENDPOINT = 0x81
        private const val STREAMING = 1
        private const val SET = 0x21
        private const val GET = 0xA1
        private const val SET_CUR = 0x01
        private const val GET_CUR = 0x81
        private const val PROBE = 0x0100
        private const val COMMIT = 0x0200
        private const val CONTROL_TIMEOUT_MS = 1000
        private const val READ_TIMEOUT_MS = 250
        private const val REPORT_MS = 5_000L
    }
}
