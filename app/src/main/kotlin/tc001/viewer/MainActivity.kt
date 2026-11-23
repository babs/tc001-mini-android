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
import android.graphics.Paint
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import tc001.viewer.core.Access
import tc001.viewer.core.FrameProcessor
import tc001.viewer.core.Palette
import tc001.viewer.core.Readout
import tc001.viewer.core.Readouts
import tc001.viewer.core.fitTextSize
import tc001.viewer.core.labelTextSize
import tc001.viewer.core.Reconnect
import tc001.viewer.core.Settings
import tc001.viewer.core.VolumeKey
import tc001.viewer.core.captureName
import tc001.viewer.core.CENTRE_MAX_SP
import tc001.viewer.core.PAIR_MAX_SP
import tc001.viewer.core.nextAccess
import tc001.viewer.core.pairTextSize
import tc001.viewer.core.photoJson
import tc001.viewer.core.pictureSize
import tc001.viewer.core.pictureTurns
import tc001.viewer.core.rawSnapshot
import tc001.viewer.core.readoutBand
import tc001.viewer.core.readoutFloor
import tc001.viewer.core.snapQuarter
import tc001.viewer.core.turnToDraw
import tc001.viewer.core.turnedBox
import tc001.viewer.core.volumeKey

class MainActivity : Activity() {
    private val processor = FrameProcessor()
    private val composer = FrameComposer()
    // Filled by the source thread, snapshotted by the UI thread: both under its lock, so the three values come from one frame.
    private val readouts = Readouts()
    private var filled = false // guarded by readouts: false until the current source's first frame

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
    private lateinit var readoutRow: View
    private lateinit var readoutBox: LinearLayout
    private lateinit var readoutPair: LinearLayout // Min and Max
    private lateinit var values: Map<Readout, TextView>
    private val readoutTick = Runnable { showReadouts() }
    private val measure = Paint() // UI thread, reused by ems
    private val labels = ArrayList<TextView>(Readout.entries.size)

    // Set on the UI thread, taken by the source thread with the next composed frame.
    @Volatile
    private var photoRequested = false

    // UI thread: phone quarter turns clockwise from portrait, as the accelerometer last snapped them (003-AC4).
    private var phone = 0
    private lateinit var orientation: OrientationEventListener

    // Set on the UI thread, read once per frame on the source thread: the phone turns the picture is drawn for.
    @Volatile
    private var drawn = 0

    /** A recording asked for, compared by identity; [held] is the turn drawn from Record until Stop (003-AC6). */
    private class Recording(val held: Int)

    // UI thread: the recording asked for, from Record until Stop; a start still in flight leaves [recorder] null.
    private var recording: Recording? = null

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
        // Upright size only: a sideways start falls back to the full encoder search if the prepared one refuses it.
        val (width, height) = pictureSize(0)
        thread(name = "recorder-prepare") { Recorder.prepare(contentResolver, width, height) }
        message = findViewById(R.id.message)
        // Retry after a dismissed or refused prompt without leaving the app.
        message.setOnClickListener { connect() }
        // Posted: a layout change listener runs inside the layout pass.
        (message.parent as View).addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> message.post(::fitMessage) }
        fps = findViewById(R.id.fps)
        // Follows the sensor even with auto-rotate off: the layout is locked to portrait, only the content turns.
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(angle: Int) {
                val new = snapQuarter(angle, phone)
                if (new == phone) return
                phone = new
                val t = turnToDraw(phone, recording?.held)
                if (t != drawn) turn(t)
            }
        }

        paletteButton = findViewById(R.id.palette)
        paletteButton.setOnClickListener {
            val all = Palette.entries
            update(settings.copy(palette = all[(settings.palette.ordinal + 1) % all.size]))
        }
        findViewById<ToggleButton>(R.id.lock).setOnCheckedChangeListener { _, on -> processor.locked = on }
        rotateButton = findViewById(R.id.rotate)
        rotateButton.setOnClickListener { update(settings.copy(quarterTurns = (settings.quarterTurns + 1) % 4)) }
        photoButton = findViewById(R.id.photo)
        photoButton.setOnClickListener { requestPhoto() }
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

        readoutRow = findViewById(R.id.readouts)
        readoutBox = findViewById(R.id.readout_box)
        readoutPair = LinearLayout(this)
        readoutBox.addView(readoutPair, LinearLayout.LayoutParams(MATCH_PARENT, 0, PAIR_MAX_SP))
        val min = readoutColumn(Readout.MIN, readoutPair, 1f)
        val max = readoutColumn(Readout.MAX, readoutPair, 1f)
        val centre = readoutColumn(Readout.CENTER, readoutBox, CENTRE_MAX_SP)
        values = mapOf(Readout.MIN to min, Readout.CENTER to centre, Readout.MAX to max)
        // Rows split the band PAIR : CENTRE; the floor gives the Min/Max row its label and a 56 sp value.
        val label = lineHeight(LABEL_SP)
        val bandMin = (label + lineHeight(VALUE_MIN_SP)) * (PAIR_MAX_SP + CENTRE_MAX_SP) / PAIR_MAX_SP
        // Only on a new size: a refit relayouts the band and the box; new values refit from showReadouts.
        readoutRow.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) readoutRow.post(::layoutReadouts)
        }
        readoutBox.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) readoutBox.post(::fitReadouts)
        }
        findViewById<View>(R.id.stage).addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val band = readoutBand(right - left, bottom - top, bandMin.toInt())
            // Posted: a layout change listener runs inside the layout pass.
            if (readoutRow.layoutParams.height != band) readoutRow.post {
                readoutRow.layoutParams.height = band
                readoutRow.requestLayout()
            }
        }

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
        orientation.enable()
        connect()
    }

    override fun onStop() {
        started = false
        orientation.disable()
        unregisterReceiver(receiver)
        use(null, R.string.plug_in)
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = volumeKey(keyCode, event) ?: super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = volumeKey(keyCode, event) ?: super.onKeyUp(keyCode, event)

    /** Handled volume key (002-AC5), or null for the system. */
    private fun volumeKey(keyCode: Int, event: KeyEvent): Boolean? {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return null
        return when (volumeKey(event.action == KeyEvent.ACTION_DOWN, event.repeatCount, source != null)) {
            VolumeKey.PHOTO -> true.also { requestPhoto() }
            VolumeKey.CONSUME -> true
            VolumeKey.PASS -> null
        }
    }

    private fun requestPhoto() {
        photoRequested = true
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
        synchronized(readouts) { filled = false }
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
        // Cleared, not left at the last source's numbers until the new one's first frame.
        readoutRow.removeCallbacks(readoutTick)
        for (v in values.values) v.text = null
        readoutRow.visibility = if (new != null) View.VISIBLE else View.INVISIBLE
        if (new != null) readoutRow.post(readoutTick)
        fps.visibility = if (FPS_OVERLAY && new != null) View.VISIBLE else View.GONE
        if (fps.visibility == View.VISIBLE) {
            shownFrames = frames
            shownAt = SystemClock.elapsedRealtime()
            fps.postDelayed(fpsTick, 1000)
        }
    }

    /** Draws the picture and the message for a phone turned clockwise by [turns] quarters. */
    private fun turn(turns: Int) {
        drawn = turns
        message.rotation = -90f * turns
        fitMessage()
        layoutReadouts()
    }

    /** Lays the readouts out in the box the user sees once the band turns with the phone, turned to read upright (003-AC3). */
    private fun layoutReadouts() {
        val (width, height) = turnedBox(readoutRow.width, readoutRow.height, drawn)
        val box = readoutBox.layoutParams
        if (box.width != width || box.height != height) {
            box.width = width
            box.height = height
            readoutBox.requestLayout()
        }
        readoutBox.rotation = -90f * drawn
        val stacked = drawn % 2 != 0
        val pair = readoutPair
        if (pair.orientation != if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL) {
            pair.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            for (i in 0 until pair.childCount) pair.getChildAt(i).layoutParams = weighted(pair, 1f)
            (pair.layoutParams as LinearLayout.LayoutParams).weight = if (stacked) 2 * PAIR_MAX_SP else PAIR_MAX_SP
            // Stacked, no value has a side neighbour: the gap would only shrink the width-bound values.
            val gap = if (stacked) 0 else valueGap
            for (v in values.values) v.setPadding(gap, 0, gap, 0)
            // A square band keeps its box size: the size-gated listener would not refit the new arrangement.
            readoutBox.post(::fitReadouts)
        }
    }

    /** Horizontal padding of a value: a visible gap between Min and Max side by side. */
    private val valueGap get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()

    /** Layout params sharing [parent]'s length by [weight] and filling across it. */
    private fun weighted(parent: LinearLayout, weight: Float) =
        if (parent.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, MATCH_PARENT, weight) else LinearLayout.LayoutParams(MATCH_PARENT, 0, weight)

    /** Turned a quarter, the message wraps to its slot's height, the width it has once turned. */
    private fun fitMessage() {
        val slot = (message.parent as View).height
        val width = if (drawn % 2 != 0 && slot > 0) slot else Int.MAX_VALUE
        if (message.maxWidth != width) message.maxWidth = width
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

    /** [item]'s label above its value, added to [parent] with [weight]; returns the value view. */
    private fun readoutColumn(item: Readout, parent: LinearLayout, weight: Float): TextView {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            text = item.label
            setTextColor(item.color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP)
            gravity = Gravity.CENTER
            includeFontPadding = false
            // One line at most: a wrapped label in a narrow turned box would take its value's height.
            maxLines = 1
            setHorizontallyScrolling(true)
        }
        val value = TextView(this).apply {
            setTextColor(item.color)
            setPadding(valueGap, 0, valueGap, 0)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            includeFontPadding = false
            maxLines = 1
            // Keeps one line: a hair over is clipped at the padding edge instead of wrapping the last digit away.
            setHorizontallyScrolling(true)
        }
        labels += label
        column.addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(value, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        parent.addView(column, weighted(parent, weight))
        return value
    }

    private fun sp(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    /** [v]'s text width and line height, in multiples of its text size. */
    private fun ems(v: TextView): Pair<Float, Float> {
        // At one fixed size: widths do not scale exactly with size, so measuring at the size just fitted would never settle.
        measure.set(v.paint)
        measure.textSize = EM_PX
        return measure.measureText(v.text, 0, v.length()) / EM_PX to measure.fontMetrics.run { descent - ascent } / EM_PX
    }

    /**
     * Min and Max at one shared size, the largest at which both fit their boxes and below Center; Center on its own. A wide value
     * on a narrow screen goes below the band's 56 sp, down to 32 sp upright; only a too-short box goes lower. Turned a
     * quarter, the width always binds.
     */
    private fun fitReadouts() {
        val min = values.getValue(Readout.MIN)
        val max = values.getValue(Readout.MAX)
        val centre = values.getValue(Readout.CENTER)
        // Not laid out yet: the next layout pass fits them.
        if (min.height == 0 || centre.height == 0) return
        var relabelled = false
        for (label in labels) {
            val size = labelTextSize(ems(label).first, innerWidth(label), sp(LABEL_SP))
            if (size != label.textSize) {
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                relabelled = true
            }
        }
        // A new label height resizes the value boxes: refit after that layout, which the post queues behind.
        if (relabelled) {
            readoutBox.post(::fitReadouts)
            return
        }
        val (centreEm, centreLine) = ems(centre)
        val centreSize = fitTextSize(centreEm, centreLine, innerWidth(centre), centre.height.toFloat(), readoutFloor(drawn, sp(VALUE_FIT_SP)), sp(CENTRE_MAX_SP))
        centre.setTextSize(TypedValue.COMPLEX_UNIT_PX, centreSize)
        val (minEm, line) = ems(min)
        val pair = pairTextSize(
            fitTextSize(maxOf(minEm, ems(max).first), line, innerWidth(min), min.height.toFloat(), readoutFloor(drawn, sp(VALUE_FIT_SP)), sp(PAIR_MAX_SP)),
            centreSize,
        )
        min.setTextSize(TypedValue.COMPLEX_UNIT_PX, pair)
        max.setTextSize(TypedValue.COMPLEX_UNIT_PX, pair)
    }

    private fun innerWidth(v: TextView) = (v.width - v.paddingLeft - v.paddingRight).toFloat()

    /** Pixel height of one line of default-typeface text at [size] sp, without font padding. */
    private fun lineHeight(size: Float) = Paint().apply { textSize = sp(size) }.getFontMetricsInt(null)

    private fun showReadouts() {
        val texts = synchronized(readouts) { if (filled) Readout.entries.associateWith { it.text(readouts) } else null }
        // "-" is narrower than a digit: a same-length value can still be wider, so any changed text refits.
        if (texts != null && texts.any { (item, t) -> !values.getValue(item).text.contentEquals(t) }) {
            for ((item, t) in texts) values.getValue(item).text = t
            fitReadouts()
        }
        readoutRow.postDelayed(readoutTick, READOUT_PERIOD_MS)
    }

    // Source thread.
    private fun onFrame(frame: ByteArray) {
        frames++
        val s = settings
        // The recorder is published after the turn freezes: read first, its frames always carry the frozen turn.
        val rec = recorder
        val turns = drawn
        if (!processor.process(frame, s.smoothing)) return
        val p = processor
        synchronized(readouts) {
            readouts.fill(p)
            filled = true
        }
        val view = composer.compose(p.shown, readouts, s.palette, s.smoothing, p.spots, pictureTurns(s.quarterTurns, s.mirror, turns), s.mirror, turns)
        live.show(view, turns)
        rec?.draw(view)
        if (photoRequested) {
            photoRequested = false
            takePhoto(view, frame, s, turns)
        }
    }

    /** Source thread: snapshots [view], [frame] (both reused by the next frame) and its readouts drawn with [s] for [turns], saved on a thread of their own. */
    private fun takePhoto(view: Bitmap, frame: ByteArray, s: Settings, turns: Int) {
        val now = System.currentTimeMillis()
        val name = captureName(now)
        val json = photoJson(readouts, s, turns, now)
        val png = view.copy(Bitmap.Config.ARGB_8888, false)
        val raw = rawSnapshot(frame)
        if (png == null) return runOnUiThread { toast(getString(R.string.photo_failed)) }
        thread(name = "photo") {
            val saved = contentResolver.savePhoto(name, png, raw, json)
            runOnUiThread { toast(if (saved) getString(R.string.photo_saved, name) else getString(R.string.photo_failed)) }
        }
    }

    /** The encoder and MediaStore set-up block: done on a thread of its own, the button stays disabled until it is known. */
    private fun startRecording() {
        val pressedAt = SystemClock.elapsedRealtime()
        val token = Recording(drawn)
        recording = token
        showRecording()
        val name = captureName(System.currentTimeMillis())
        // The turn is held from here to Stop (003-AC6), so every frame of the file has this size (003-AC10).
        val (width, height) = pictureSize(token.held)
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
                        stopRecording()
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
        // After finish(): no frame drawn for the new turn reaches the file.
        if (drawn != phone) turn(phone)
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
        const val READOUT_PERIOD_MS = 200L // ~5 Hz (002-AC4)
        const val LABEL_SP = 16f
        const val VALUE_MIN_SP = 56f
        const val VALUE_FIT_SP = 32f
        const val EM_PX = 100f
    }
}
