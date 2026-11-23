package tc001.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Shows composed frames from the source thread, turned back onto the portrait screen by the phone's quarter
 * turns. The surface buffer is the turned picture's exact size, so a frame is a 1:1 copy done before the next
 * compose starts (no tearing) and the compositor scales it.
 */
class LiveView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private val lock = Any()
    private var surface: SurfaceHolder? = null
    private var frameWidth = 1
    private var frameHeight = 1

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBX_8888) // SurfaceView defaults to RGB_565: banded gradients
    }

    /** UI thread: sizes the surface for [width] x [height] frames composed for a phone turned clockwise by [turns] quarters; the view keeps that aspect ratio. */
    fun fit(width: Int, height: Int, turns: Int) {
        val odd = turns % 2 != 0
        frameWidth = if (odd) height else width
        frameHeight = if (odd) width else height
        holder.setFixedSize(frameWidth, frameHeight)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        val width = minOf(maxWidth, maxHeight * frameWidth / frameHeight)
        setMeasuredDimension(width, width * frameHeight / frameWidth)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = synchronized(lock) { surface = holder }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    // The lock guarantees the source thread no longer draws once this returns, as SurfaceHolder requires.
    override fun surfaceDestroyed(holder: SurfaceHolder) = synchronized(lock) { surface = null }

    /** Source thread: draws [bitmap], composed for a phone turned clockwise by [turns] quarters, turned back by them; dropped while there is no surface. */
    fun show(bitmap: Bitmap, turns: Int) = synchronized(lock) {
        val holder = surface ?: return
        val canvas = holder.lockCanvas() ?: return
        val odd = turns % 2 != 0
        // One frame while the buffer is resized for a new turn: centred on black, never stretched, no stale pixels.
        if (canvas.width != (if (odd) bitmap.height else bitmap.width) || canvas.height != (if (odd) bitmap.width else bitmap.height)) {
            canvas.drawColor(Color.BLACK)
        }
        canvas.save()
        // Every size is even: the turn about the buffer centre lands on whole pixels, still a 1:1 copy.
        canvas.translate(canvas.width / 2f, canvas.height / 2f)
        canvas.rotate(-90f * turns)
        canvas.drawBitmap(bitmap, -bitmap.width / 2f, -bitmap.height / 2f, null)
        canvas.restore()
        holder.unlockCanvasAndPost(canvas)
    }
}
