package tc001.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Shows composed frames from the source thread. The surface buffer is the bitmap's exact size, so a
 * frame is a 1:1 copy done before the next compose starts (no tearing) and the compositor scales it.
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

    /** Sizes the surface for frames of [width] x [height]; the view keeps that aspect ratio. */
    fun fit(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
        holder.setFixedSize(width, height)
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

    /** Source thread: draws [bitmap], sized as given to [fit]; dropped while there is no surface. */
    fun show(bitmap: Bitmap) = synchronized(lock) {
        val holder = surface ?: return
        val canvas = holder.lockCanvas() ?: return
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        holder.unlockCanvasAndPost(canvas)
    }
}
