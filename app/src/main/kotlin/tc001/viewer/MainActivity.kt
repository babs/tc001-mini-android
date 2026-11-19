package tc001.viewer

import android.app.Activity
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.SeekBar
import android.widget.ToggleButton
import kotlin.math.roundToInt
import tc001.viewer.core.FrameProcessor
import tc001.viewer.core.Palette
import tc001.viewer.core.Settings

class MainActivity : Activity() {
    private val processor = FrameProcessor()
    private val composer = FrameComposer(8) // 864 x 768: both multiples of 16 for the H.264 encoder

    // Replaced whole on the UI thread, read once per frame on the source thread: a consistent snapshot.
    @Volatile
    private var settings = Settings()
    private var source: FrameSource? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var live: LiveView
    private lateinit var paletteButton: Button
    private lateinit var rotateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        settings = Settings.from(prefs.all)
        source = frameSource()

        live = findViewById(R.id.live)
        live.fit(composer.bitmap.width, composer.bitmap.height)
        live.keepScreenOn = source != null
        if (source == null) findViewById<View>(R.id.message).visibility = View.VISIBLE

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
        source?.start(::onFrame)
    }

    override fun onStop() {
        source?.stop()
        super.onStop()
    }

    // Source thread.
    private fun onFrame(frame: ByteArray) {
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
}
