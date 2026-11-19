package tc001.viewer

import android.content.Context

/** Debug builds replay the bundled capture until a usable camera is attached, so the UI runs on an emulator; camera errors show their message. */
fun Context.fallbackSource(): FrameSource? = ReplaySource(assets.open("replay-mode2.bin").use { it.readBytes() })

const val FPS_OVERLAY = true
