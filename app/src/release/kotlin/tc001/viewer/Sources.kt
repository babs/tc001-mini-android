package tc001.viewer

import android.content.Context

/** No replay in release: without the camera the view asks for it. */
@Suppress("UnusedReceiverParameter")
fun Context.fallbackSource(): FrameSource? = null

const val FPS_OVERLAY = false
