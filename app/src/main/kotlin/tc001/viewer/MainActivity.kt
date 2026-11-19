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
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
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
import android.widget.Toast
import android.widget.ToggleButton
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import tc001.viewer.core.Access
import tc001.viewer.core.FrameProcessor
import tc001.viewer.core.Palette
import tc001.viewer.core.Readouts
import tc001.viewer.core.Reconnect
import tc001.viewer.core.Settings
import tc001.viewer.core.captureName
import tc001.viewer.core.nextAccess
import tc001.viewer.core.rawSnapshot

class MainActivity : Activity() {
    private val processor = FrameProcessor()
    private val composer = FrameComposer()
    private val readouts = Readouts() // source thread only

    // Replaced whole on the UI thread, read once per frame on the source thread: a consistent snapshot.
    @Volatile
    private var settings = Settings()
    private var source: FrameSource? = null
    private var started = false
    // A pending dialog is never stacked; a CAMERA refusal holds until the next start, so dialogs never loop.
    private var usbPending = false
    private var cameraPending = false
    private var cameraDenied = false
    private var cameraName: String? = null // the camera open() last saw, so an unplug at any stage reads as one
    private lateinit var usb: UsbManager
    private lateinit var prefs: SharedPreferences
    private lateinit var live: LiveView
    private lateinit var message: TextView
    private lateinit var fps: TextView
    private lateinit var paletteButton: Button
    private lateinit var rotateButton: Button
    private lateinit var photoButton: Button
    private lateinit var recordButton: Button
    private lateinit var recordColors: ColorStateList

    // Set on the UI thread, taken by the source thread with the next composed frame.
    @Volatile
    private var photoRequested = false

    // UI thread: the recording asked for, from Record until Stop; a start still in flight leaves [recorder] null.
    private var recording: Any? = null

    // Replaced on the UI thread; the source thread draws into whichever it reads, a finished one ignores frames.
    @Volatile
    private var recorder: Recorder? = null

    // Written by the one source thread alive at a time, read by the fps overlay.
    @Volatile
    private var frames = 0
    private var shownFrames = 0
    private var shownAt = 0L
    private val fpsTick = Runnable { showFps() }
    private val reconnect = Reconnect() // UI thread
    private val retry = Runnable { if (started) open() }

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
        val (width, height) = composer.bitmap.run { width to height }
        thread(name = "recorder-prepare") { Recorder.prepare(contentResolver, width, height) }
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
        photoButton = findViewById(R.id.photo)
        photoButton.setOnClickListener { photoRequested = true }
        recordButton = findViewById(R.id.record)
        recordColors = recordButton.textColors
        recordButton.setOnClickListener { if (recording == null) startRecording() else stopRecording() }
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

    /** A fresh start (tap, onStart, plug, access granted): a full reconnect budget, then [open]. */
    private fun connect() {
        reconnect.reset()
        open()
    }

    /** The camera when attached and permitted, else the build's fallback; asks for access once if needed. */
    private fun open() {
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
                camera = UsbSource(usb, device) { error, delivered -> runOnUiThread { if (source === camera) failed(error, delivered) } }
                use(camera, R.string.plug_in)
            }
        }
    }

    /** Reconnects after a stream error while the camera is attached and permitted, else shows [error]. */
    private fun failed(error: Int, delivered: Int) {
        val device = camera()
        // Fast-failing reads also report "disconnected": a real unplug is caught by `gone` and the DETACHED receiver.
        val retryable = error != R.string.usb_denied
        val delay = reconnect.next(device == null, device != null && usb.hasPermission(device), retryable, delivered) ?: return use(null, error)
        Log.i(UsbSource.TAG, "reconnect ${reconnect.attempts}/${Reconnect.DELAYS_MS.size} after ${resources.getResourceEntryName(error)}")
        use(null, R.string.reconnecting)
        message.postDelayed(retry, delay)
    }

    private fun camera() =
        usb.deviceList.values.firstOrNull { it.vendorId == UsbSource.VENDOR_ID && it.productId == UsbSource.PRODUCT_ID }

    /** Switches to [new]; without one the view is hidden behind [text]. */
    private fun use(new: FrameSource?, text: Int) {
        // Every switch, unplug and onStop passes here: no reconnect fires on a stopped or replaced source.
        message.removeCallbacks(retry)
        source?.stop()
        // The old source thread is gone: the recording holds only its frames, finalised before the next source draws.
        stopRecording()
        photoRequested = false
        source = new
        new?.start(::onFrame)
        photoButton.isEnabled = new != null
        showRecording()
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
        readouts.fill(p)
        val view = composer.compose(p.shown, readouts, s.palette, s.smoothing, p.spots, s.quarterTurns, s.mirror)
        live.show(view)
        recorder?.draw(view)
        if (photoRequested) {
            photoRequested = false
            takePhoto(view, frame)
        }
    }

    /** Source thread: snapshots [view] and [frame] (both reused by the next frame), saved on a thread of their own. */
    private fun takePhoto(view: Bitmap, frame: ByteArray) {
        val name = captureName(System.currentTimeMillis())
        val png = view.copy(Bitmap.Config.ARGB_8888, false)
        val raw = rawSnapshot(frame)
        if (png == null) return runOnUiThread { toast(getString(R.string.photo_failed)) }
        thread(name = "photo") {
            val saved = contentResolver.savePhoto(name, png, raw)
            runOnUiThread { toast(if (saved) getString(R.string.photo_saved, name) else getString(R.string.photo_failed)) }
        }
    }

    /** The encoder and MediaStore set-up block: done on a thread of its own, the button stays disabled until it is known. */
    private fun startRecording() {
        val pressedAt = SystemClock.elapsedRealtime()
        val token = Any()
        recording = token
        showRecording()
        val name = captureName(System.currentTimeMillis())
        val (width, height) = composer.bitmap.run { width to height }
        thread(name = "recorder-start") {
            val new = Recorder.start(contentResolver, name, width, height, pressedAt) { saved ->
                runOnUiThread {
                    // Ended on its own (encoder failure): the button must not keep offering Stop.
                    if (recording === token) stopRecording()
                    toast(if (saved) getString(R.string.video_saved, name) else getString(R.string.video_failed))
                }
            }
            runOnUiThread {
                when {
                    recording !== token -> new?.finish() // stopped, backgrounded or source switched meanwhile
                    new == null -> {
                        recording = null
                        toast(getString(R.string.video_failed))
                    }
                    else -> recorder = new
                }
                showRecording()
            }
        }
    }

    /** Finalises the recording in progress, if any, without waiting: the file is written off the UI thread. */
    private fun stopRecording() {
        recording = null
        recorder?.finish()
        recorder = null
        showRecording()
    }

    private fun showRecording() {
        val on = recorder != null
        recordButton.isEnabled = source != null && (recording == null || on)
        recordButton.setText(if (on) R.string.stop else R.string.record)
        if (on) recordButton.setTextColor(Color.RED) else recordButton.setTextColor(recordColors)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

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
