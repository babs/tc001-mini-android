package tc001.viewer

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import kotlin.math.roundToInt
import tc001.viewer.core.Access
import tc001.viewer.core.FrameProcessor
import tc001.viewer.core.Palette
import tc001.viewer.core.Settings
import tc001.viewer.core.nextAccess

class MainActivity : Activity() {
    private val processor = FrameProcessor()
    private val composer = FrameComposer(8) // 864 x 768: both multiples of 16 for the H.264 encoder

    // Replaced whole on the UI thread, read once per frame on the source thread: a consistent snapshot.
    @Volatile
    private var settings = Settings()
    private var source: FrameSource? = null
    private var started = false
    // A pending dialog is never stacked; a CAMERA refusal holds until the next start, so dialogs never loop.
    private var usbPending = false
    private var cameraPending = false
    private var cameraDenied = false
    private var cameraName: String? = null // the camera connect() last saw, so an unplug at any stage reads as one
    private lateinit var usb: UsbManager
    private lateinit var prefs: SharedPreferences
    private lateinit var live: LiveView
    private lateinit var message: TextView
    private lateinit var fps: TextView
    private lateinit var paletteButton: Button
    private lateinit var rotateButton: Button

    // Written by the one source thread alive at a time, read by the fps overlay.
    @Volatile
    private var frames = 0
    private var shownFrames = 0
    private var shownAt = 0L
    private val fpsTick = Runnable { showFps() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Without access the system "Open TC001?" prompt handles this attach (onNewIntent): asking here would stack a second prompt.
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = camera() ?: return
                    if (usb.hasPermission(device)) connect() else use(fallbackSource(), R.string.allow_access)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val name = cameraName ?: return
                    if (name !in usb.deviceList) {
                        Log.i(UsbSource.TAG, "camera detached")
                        cameraName = null
                        use(null, R.string.disconnected)
                    }
                }
                // Below API 33 the receiver is exported: trust only our own pending request and UsbManager.
                ACTION_PERMISSION -> {
                    if (!usbPending) return
                    usbPending = false
                    val device = camera()
                    if (device == null || usb.hasPermission(device)) {
                        connect()
                    } else {
                        Log.i(UsbSource.TAG, "USB access denied")
                        use(null, R.string.usb_denied)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        settings = Settings.from(prefs.all)
        usb = getSystemService(UsbManager::class.java)

        live = findViewById(R.id.live)
        live.fit(composer.bitmap.width, composer.bitmap.height)
        message = findViewById(R.id.message)
        // Retry after a dismissed or refused prompt without leaving the app.
        message.setOnClickListener { connect() }
        fps = findViewById(R.id.fps)

        paletteButton = findViewById(R.id.palette)
        paletteButton.setOnClickListener {
            val all = Palette.entries
            update(settings.copy(palette = all[(settings.palette.ordinal + 1) % all.size]))
        }
        findViewById<ToggleButton>(R.id.lock).setOnCheckedChangeListener { _, on -> processor.locked = on }
        rotateButton = findViewById(R.id.rotate)
        rotateButton.setOnClickListener { update(settings.copy(quarterTurns = (settings.quarterTurns + 1) % 4)) }
        findViewById<ToggleButton>(R.id.mirror).apply {
            isChecked = settings.mirror
            setOnCheckedChangeListener { _, on -> update(settings.copy(mirror = on)) }
        }
        findViewById<SeekBar>(R.id.smoothing).apply {
            progress = (settings.smoothing * max).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) update(settings.copy(smoothing = progress.toFloat() / bar.max))
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        showSettings()

        // WindowInsets.Type is API 30+; below API 35 the decor already keeps content clear of the bars.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        usbPending = false
        cameraDenied = false
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(ACTION_PERMISSION)
        }
        // RECEIVER_NOT_EXPORTED is API 33; the system still delivers its USB broadcasts to a non-exported receiver.
        registerReceiver(receiver, filter, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0)
        connect()
    }

    override fun onStop() {
        started = false
        unregisterReceiver(receiver)
        use(null, R.string.plug_in)
        super.onStop()
    }

    // Re-plug: the system starts us with USB_DEVICE_ATTACHED once the user accepts, access included.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (started) connect()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        cameraPending = false
        // Empty results mean the request was cancelled: treated as a refusal, never re-asked in a loop.
        cameraDenied = grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        if (cameraDenied) Log.i(UsbSource.TAG, "camera permission denied")
        if (started) connect()
    }

    /** The camera when attached and permitted, else the build's fallback; asks for access once if needed. */
    private fun connect() {
        val device = camera()
        cameraName = device?.deviceName
        if (device == null) return use(fallbackSource(), R.string.plug_in)
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        when (nextAccess(cameraGranted, cameraPending, cameraDenied, usb.hasPermission(device), usbPending)) {
            Access.ASK_CAMERA -> {
                cameraPending = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                use(fallbackSource(), R.string.allow_access)
            }
            // No rationale after a refusal: "don't ask again", the system now refuses without a dialog.
            Access.CAMERA_DENIED -> use(
                null,
                if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) R.string.camera_denied else R.string.camera_off,
            )
            Access.ASK_USB -> {
                usbPending = true
                // Explicit (package) and immutable: the receiver reads nothing the system could add.
                val intent = Intent(ACTION_PERMISSION).setPackage(packageName)
                usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
                use(fallbackSource(), R.string.allow_access)
            }
            Access.WAIT -> use(fallbackSource(), R.string.allow_access)
            Access.CONNECT -> if ((source as? UsbSource)?.device != device) {
                lateinit var camera: UsbSource
                camera = UsbSource(usb, device) { error -> runOnUiThread { if (source === camera) use(null, error) } }
                use(camera, R.string.plug_in)
            }
        }
    }

    private fun camera() =
        usb.deviceList.values.firstOrNull { it.vendorId == UsbSource.VENDOR_ID && it.productId == UsbSource.PRODUCT_ID }

    /** Switches to [new]; without one the view is hidden behind [text]. */
    private fun use(new: FrameSource?, text: Int) {
        source?.stop()
        source = new
        new?.start(::onFrame)
        Log.i(UsbSource.TAG, "source: ${if (new == null) "none" else if (new is UsbSource) "usb" else "replay"}")
        live.keepScreenOn = new != null
        live.visibility = if (new != null) View.VISIBLE else View.INVISIBLE
        message.setText(text)
        message.visibility = if (new == null) View.VISIBLE else View.GONE
        // The rate is per source: a stopped source must not leave its last reading on screen.
        fps.removeCallbacks(fpsTick)
        fps.text = null
        fps.visibility = if (FPS_OVERLAY && new != null) View.VISIBLE else View.GONE
        if (fps.visibility == View.VISIBLE) {
            shownFrames = frames
            shownAt = SystemClock.elapsedRealtime()
            fps.postDelayed(fpsTick, 1000)
        }
    }

    private fun showFps() {
        val n = frames
        val now = SystemClock.elapsedRealtime()
        val rate = (n - shownFrames) * 1000f / (now - shownAt).coerceAtLeast(1)
        fps.text = getString(R.string.fps, rate, (source as? UsbSource)?.rejected ?: 0)
        shownFrames = n
        shownAt = now
        fps.postDelayed(fpsTick, 1000)
    }

    // Source thread.
    private fun onFrame(frame: ByteArray) {
        frames++
        val s = settings
        if (!processor.process(frame, s.smoothing)) return
        val p = processor
        live.show(composer.compose(p.shown, p.lo, p.hi, s.palette, s.smoothing, p.spots, p.centre, s.quarterTurns, s.mirror))
    }

    private fun update(new: Settings) {
        settings = new
        showSettings()
        val editor = prefs.edit()
        for ((key, value) in new.toMap()) {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                else -> error("no SharedPreferences type for $key")
            }
        }
        editor.apply()
    }

    private fun showSettings() {
        paletteButton.setText(
            when (settings.palette) {
                Palette.IRONBOW -> R.string.ironbow
                Palette.WHITE_HOT -> R.string.white_hot
                Palette.BLACK_HOT -> R.string.black_hot
            },
        )
        rotateButton.text = getString(R.string.rotation, settings.quarterTurns * 90)
    }

    private companion object {
        const val ACTION_PERMISSION = "tc001.viewer.USB_PERMISSION"
        const val REQUEST_CAMERA = 1
    }
}
