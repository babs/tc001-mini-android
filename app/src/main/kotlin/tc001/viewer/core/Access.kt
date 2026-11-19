package tc001.viewer.core

/** What to do next with an attached camera. */
enum class Access { ASK_CAMERA, CAMERA_DENIED, ASK_USB, WAIT, CONNECT }

/**
 * CAMERA comes first: Android grants USB access to a video-class device only to an app holding it. Each
 * permission is asked once until answered, and a CAMERA refusal stays refused, so dialogs never loop.
 */
fun nextAccess(cameraGranted: Boolean, cameraPending: Boolean, cameraDenied: Boolean, usbGranted: Boolean, usbPending: Boolean) =
    when {
        cameraGranted -> when {
            usbGranted -> Access.CONNECT
            usbPending -> Access.WAIT
            else -> Access.ASK_USB
        }
        cameraDenied -> Access.CAMERA_DENIED
        cameraPending -> Access.WAIT
        else -> Access.ASK_CAMERA
    }
