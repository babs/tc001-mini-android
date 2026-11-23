package tc001.viewer

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import tc001.viewer.core.Mailbox
import tc001.viewer.core.PtsRebase

/**
 * H.264 MP4 of the composed view, written to [VIDEO_DIR] through MediaStore. [draw] and [finish] only hand off
 * and never wait on the encoder: a draw thread feeds the encoder surface, a drain thread feeds the muxer, then
 * finalises the file (published if a frame was encoded, else deleted) and calls [done] with the outcome.
 */
class Recorder private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val fd: ParcelFileDescriptor,
    private val muxer: MediaMuxer,
    private val codec: MediaCodec,
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val done: (Boolean) -> Unit,
) {
    private class Frame(width: Int, height: Int) {
        val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
    }

    // The composed view is opaque: a plain draw replaces the previous frame.
    private val frames = Mailbox<Bitmap, Frame>(Frame(width, height), Frame(width, height)) { view, f -> f.canvas.drawBitmap(view, 0f, 0f, null) }
    private val drawer = Thread(::feed, "recorder-draw")

    @Volatile
    private var endedAt = 0L

    init {
        drawer.start()
        Thread(::drain, "recorder-drain").start()
    }

    // Source thread only.
    private var dropped = false

    /** Source thread: copies [view], sized as given to [start]; the encoder stamps it with the time it is queued. */
    fun draw(view: Bitmap) {
        // A frame of another layout would be drawn cropped into the file: dropped instead.
        if (view.width != width || view.height != height) {
            if (!dropped) Log.w(UsbSource.TAG, "recorder: dropped a ${view.width} x ${view.height} frame, recording $width x $height")
            dropped = true
            return
        }
        frames.put(view)
    }

    /** Any thread: ends the input; the drain thread finalises the file. Further calls do nothing. */
    fun finish() {
        frames.close()
        if (endedAt == 0L) endedAt = SystemClock.elapsedRealtime()
    }

    private fun feed() {
        while (true) {
            val frame = frames.take() ?: break
            try {
                val canvas = surface.lockHardwareCanvas()
                canvas.drawBitmap(frame.bitmap, 0f, 0f, null)
                surface.unlockCanvasAndPost(canvas)
            } catch (e: RuntimeException) {
                Log.w(UsbSource.TAG, "recorder: frame not queued", e)
                break
            }
        }
        finish()
        // Waits for the last queued draw (HWUI destroys its context synchronously) before the codec sees EOS or stops.
        quietly("surface release") { surface.release() }
        try {
            codec.signalEndOfInputStream()
        } catch (e: RuntimeException) {
            Log.w(UsbSource.TAG, "recorder: end of stream refused", e)
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        val pts = PtsRebase()
        var track = -1
        var samples = 0
        try {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, DEQUEUE_US)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (i >= 0) {
                    // Codec config is already in the track format.
                    if (track >= 0 && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        info.presentationTimeUs = pts.next(info.presentationTimeUs)
                        muxer.writeSampleData(track, codec.getOutputBuffer(i)!!, info)
                        samples++
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                val ended = endedAt
                if (ended > 0 && SystemClock.elapsedRealtime() - ended > EOS_TIMEOUT_MS) {
                    Log.w(UsbSource.TAG, "recorder: no end of stream after $EOS_TIMEOUT_MS ms")
                    break
                }
            }
        } catch (e: RuntimeException) {
            Log.w(UsbSource.TAG, "recorder: encoding failed after $samples frames", e)
        }
        finish()
        // The muxer writes the moov box on stop(): without it the file does not play.
        val saved = track >= 0 && quietly("muxer stop") { muxer.stop() } && samples > 0
        quietly("muxer release") { muxer.release() }
        // Releasing the codec abandons its input surface: a draw blocked on a buffer fails instead of hanging.
        quietly("codec stop") { codec.stop() }
        quietly("codec release") { codec.release() }
        drawer.join(EOS_TIMEOUT_MS)
        if (drawer.isAlive) Log.w(UsbSource.TAG, "recorder: draw thread stuck, surface leaked") else surface.release()
        quietly("close") { fd.close() }
        val published = saved && quietly("publish") { resolver.publish(uri) }
        if (!published) quietly("delete") { resolver.delete(uri, null, null) }
        Log.i(UsbSource.TAG, "recorder: $samples frames, ${if (published) "saved" else "deleted"} $uri")
        done(published)
    }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEQUEUE_US = 10_000L
        private const val EOS_TIMEOUT_MS = 3_000L
        private val entries = Any()

        // One encoder search at a time: a press during prepare waits for it instead of opening a second encoder.
        private val search = Any()
        private var cleaned = false

        // A press opens this encoder first: listing and probing codecs takes up to a second on some phones.
        @Volatile
        private var preferred: String? = null

        /** Background, before any press: press-independent set-up, so a press only opens the file and the encoder. */
        fun prepare(resolver: ContentResolver, width: Int, height: Int) {
            synchronized(entries) { clean(resolver) }
            // Only an optimisation: a failure here must never take the app down.
            try {
                synchronized(search) {
                    if (preferred != null) return
                    val t0 = SystemClock.elapsedRealtime()
                    val format = format(width, height)
                    val (codec, surface) = encoder(format, encoders(format)) ?: run {
                        Log.w(UsbSource.TAG, "recorder: no H.264 encoder for $width x $height")
                        return
                    }
                    val name = codec.name
                    // Paying the first hardware-canvas draw here kept the first recorded frames (74 vs 67-69 of 75 on the emulator).
                    quietly("warm-up draw") { surface.unlockCanvasAndPost(surface.lockHardwareCanvas()) }
                    // Surface first: its release waits for the queued warm-up draw before the codec goes away.
                    quietly("surface release") { surface.release() }
                    quietly("codec stop") { codec.stop() }
                    quietly("codec release") { codec.release() }
                    preferred = name
                    Log.i(UsbSource.TAG, "recorder: prepared $name in ${SystemClock.elapsedRealtime() - t0} ms")
                }
            } catch (e: Exception) {
                Log.w(UsbSource.TAG, "recorder: prepare failed", e)
            }
        }

        /**
         * Off the UI thread (MediaStore and codec set-up block): starts recording to [VIDEO_DIR]/[name].mp4 at
         * [width] x [height]; null (nothing left behind) on failure. [pressedAt] (elapsedRealtime) is for the log.
         */
        fun start(resolver: ContentResolver, name: String, width: Int, height: Int, pressedAt: Long, done: (Boolean) -> Unit): Recorder? {
            val uri = try {
                synchronized(entries) {
                    clean(resolver)
                    resolver.pending(VIDEOS, VIDEO_DIR, "$name.mp4", "video/mp4")
                }
            } catch (e: Exception) {
                Log.w(UsbSource.TAG, "recorder: no MediaStore entry", e)
                return null
            }
            var fd: ParcelFileDescriptor? = null
            var muxer: MediaMuxer? = null
            var codec: MediaCodec? = null
            var surface: Surface? = null
            try {
                // MP4 needs a seekable, readable descriptor: the moov box is patched at the end.
                fd = resolver.openFileDescriptor(uri, "rw") ?: error("no descriptor for $uri")
                muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val format = format(width, height)
                val opened = synchronized(search) { encoder(format, listOfNotNull(preferred)) ?: encoder(format, encoders(format)) }
                    ?: error("no H.264 encoder for $width x $height")
                codec = opened.first
                surface = opened.second
                Log.i(UsbSource.TAG, "recorder: started +${SystemClock.elapsedRealtime() - pressedAt} ms after press")
                return Recorder(resolver, uri, fd, muxer, codec, surface, width, height, done)
            } catch (e: Exception) {
                Log.w(UsbSource.TAG, "recorder: start failed", e)
                surface?.release()
                codec?.release()
                muxer?.release()
                quietly("close") { fd?.close() }
                quietly("delete") { resolver.delete(uri, null, null) }
                return null
            }
        }

        /** Caller holds [entries]. Once per process, before its first entry: every pending one is a dead process's, never a live recording's. */
        private fun clean(resolver: ContentResolver) {
            if (cleaned) return
            try {
                val n = resolver.deletePending(VIDEOS, VIDEO_DIR)
                if (n > 0) Log.i(UsbSource.TAG, "recorder: deleted $n unfinished videos")
            } catch (e: Exception) {
                Log.w(UsbSource.TAG, "recorder: unfinished videos not deleted", e)
            } finally {
                cleaned = true
            }
        }

        private fun format(width: Int, height: Int) = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 25)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // Output order = presentation order, which PtsRebase relies on.
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

        /** The default AVC encoder for [format], then every other one: some refuse a size or a rate. */
        private fun encoders(format: MediaFormat): List<String> {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            return (
                listOfNotNull(codecs.findEncoderForFormat(format)) +
                    codecs.codecInfos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } }.map { it.name }
                ).distinct()
        }

        /** The first of [names] that configures, gives an input surface and starts; null when none does. */
        private fun encoder(format: MediaFormat, names: List<String>): Pair<MediaCodec, Surface>? {
            for (name in names) {
                var codec: MediaCodec? = null
                var surface: Surface? = null
                try {
                    codec = MediaCodec.createByCodecName(name)
                    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surface = codec.createInputSurface()
                    codec.start()
                    Log.i(UsbSource.TAG, "recorder: encoder $name, $format")
                    return codec to surface
                } catch (e: Exception) {
                    Log.w(UsbSource.TAG, "recorder: encoder $name refused $format", e)
                    surface?.release()
                    codec?.release()
                }
            }
            return null
        }

        /** Runs a cleanup step that must not stop the next ones; false when it threw. */
        private fun quietly(what: String, step: () -> Unit) =
            try {
                step()
                true
            } catch (e: Exception) {
                Log.w(UsbSource.TAG, "recorder: $what failed", e)
                false
            }
    }
}
