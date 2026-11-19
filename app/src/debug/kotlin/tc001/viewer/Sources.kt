package tc001.viewer

import android.content.Context

/** Debug builds replay the bundled capture, so the UI runs on an emulator. */
fun Context.frameSource(): FrameSource? = ReplaySource(assets.open("replay-mode2.bin").use { it.readBytes() })
