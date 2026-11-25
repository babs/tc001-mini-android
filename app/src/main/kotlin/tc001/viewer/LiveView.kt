package tc001.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import tc001.viewer.core.pictureSize

/**
 * Shows composed frames from the source thread, turned back onto the screen by the phone's quarter turns
 * relative to it. The surface buffer is the turned picture's exact size, so a frame is a 1:1 copy done before the next
 * compose starts (no tearing) and the compositor scales it.
 */
class LiveView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private val lock = Any()
    private var surface: SurfaceHolder? = null
    // Turned back onto the portrait screen, the picture of every turn fills the upright one's box (003-AC8): one size.
    private val frameWidth = pictureSize(0).first
    private val frameHeight = pictureSize(0).second

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.RGBX_8888) // SurfaceView defaults to RGB_565: banded gradients
        holder.setFixedSize(frameWidth, frameHeight)
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

    /** Source thread: draws [bitmap] turned back by [turns], the phone's quarters the screen has not turned (004-AC1); dropped while there is no surface. */
    fun show(bitmap: Bitmap, turns: Int) = synchronized(lock) {
        val holder = surface ?: return
        val canvas = holder.lockCanvas() ?: return
        // A buffer not at the fixed size yet (a first frame before it applies): centred on black, never stretched.
        if (canvas.width != frameWidth || canvas.height != frameHeight) canvas.drawColor(Color.BLACK)
        canvas.save()
        // Every size is even: the turn about the buffer centre lands on whole pixels, still a 1:1 copy.
        canvas.translate(canvas.width / 2f, canvas.height / 2f)
        canvas.rotate(-90f * turns)
        canvas.drawBitmap(bitmap, -bitmap.width / 2f, -bitmap.height / 2f, null)
        canvas.restore()
        holder.unlockCanvasAndPost(canvas)
    }
}
