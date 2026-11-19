package tc001.viewer

import android.content.Context

/** No camera driver yet and no replay in release: the view asks for the camera. */
fun Context.frameSource(): FrameSource? = null
