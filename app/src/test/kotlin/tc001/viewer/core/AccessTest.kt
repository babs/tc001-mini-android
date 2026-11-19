package tc001.viewer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessTest {
    private fun next(camera: Boolean = false, cameraPending: Boolean = false, cameraDenied: Boolean = false, usb: Boolean = false, usbPending: Boolean = false) =
        nextAccess(camera, cameraPending, cameraDenied, usb, usbPending)

    @Test
    fun cameraIsAskedFirstThenUsbThenTheStreamStarts() { // 001-AC9
        assertEquals(Access.ASK_CAMERA, next())
        assertEquals("USB granted is not enough without CAMERA", Access.ASK_CAMERA, next(usb = true))
        assertEquals(Access.ASK_USB, next(camera = true))
        assertEquals(Access.CONNECT, next(camera = true, usb = true))
    }

    @Test
    fun aPendingOrRefusedPermissionIsNeverAskedAgain() { // 001-AC9
        assertEquals(Access.WAIT, next(cameraPending = true))
        assertEquals(Access.CAMERA_DENIED, next(cameraDenied = true))
        assertEquals(Access.CAMERA_DENIED, next(cameraDenied = true, usb = true))
        assertEquals(Access.WAIT, next(camera = true, usbPending = true))
        assertEquals("a grant answers the pending request", Access.CONNECT, next(camera = true, usb = true, usbPending = true))
        assertEquals("granted in settings after a refusal", Access.ASK_USB, next(camera = true, cameraDenied = true))
    }

    @Test
    fun everyStateAsksAtMostWhatIsMissingAndNotPending() { // 001-AC9
        val b = listOf(false, true)
        for (c in b) for (cp in b) for (cd in b) for (u in b) for (up in b) {
            val step = nextAccess(c, cp, cd, u, up)
            val state = "camera=$c pending=$cp denied=$cd usb=$u usbPending=$up -> $step"
            if (step == Access.ASK_CAMERA) assertEquals(state, Triple(false, false, false), Triple(c, cp, cd))
            if (step == Access.ASK_USB) assertEquals(state, Triple(true, false, false), Triple(c, u, up))
            if (step == Access.CONNECT) assertEquals(state, Pair(true, true), Pair(c, u))
        }
    }
}
