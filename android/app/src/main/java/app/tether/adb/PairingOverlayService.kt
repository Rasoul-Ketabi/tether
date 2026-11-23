package app.tether.adb

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.tether.R
import kotlin.math.min

/**
 * Small, user-dismissable code input over Developer options. Created only after explicit
 * SYSTEM_ALERT_WINDOW consent. Some protected Settings screens may suppress overlays;
 * the existing RemoteInput notification remains available in that case.
 */
class PairingOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val hideAfterTimeout = Runnable { stopSelf() }
    private lateinit var wm: WindowManager
    private var window: View? = null
    private var codeField: EditText? = null
    private var status: TextView? = null
    private var submitButton: Button? = null
    private var pairing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_SHOW || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (window == null) {
            try {
                createWindow()
            } catch (_: RuntimeException) {
                stopSelf() // Denied/hidden by the OS: notification remains the fallback.
            }
        }
        handler.removeCallbacks(hideAfterTimeout)
        handler.postDelayed(hideAfterTimeout, 5 * 60 * 1000L)
        return START_NOT_STICKY
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun roundBackground(color: Int) = GradientDrawable().apply {
        setColor(getColor(color))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), getColor(R.color.accent))
    }

    private fun createWindow() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundBackground(R.color.surface)
            elevation = dp(10).toFloat()
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = getString(R.string.adb_overlay_title)
            textSize = 16f
            setTextColor(getColor(R.color.text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(title)
        titleRow.addView(Button(this).apply {
            text = getString(R.string.adb_overlay_close)
            isAllCaps = false
            textSize = 12f
            setOnClickListener { stopSelf() }
        }, LinearLayout.LayoutParams(dp(62), dp(45)))
        panel.addView(titleRow)

        panel.addView(TextView(this).apply {
            text = getString(R.string.adb_overlay_hint)
            textSize = 12f
            setTextColor(getColor(R.color.muted))
        })

        val entryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = getString(R.string.adb_overlay_code_hint)
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.muted))
            textSize = 20f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            // Do not automatically focus/open the keyboard: keep Settings' code visible.
            isFocusableInTouchMode = true
        }
        codeField = input
        entryRow.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
        val pair = Button(this).apply {
            text = getString(R.string.action_pair)
            isAllCaps = false
            setTextColor(getColor(R.color.background))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
            setOnClickListener { submit() }
        }
        submitButton = pair
        entryRow.addView(pair, LinearLayout.LayoutParams(dp(86), dp(52)))
        panel.addView(entryRow)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        status = TextView(this).apply {
            text = getString(R.string.adb_overlay_drag_hint)
            textSize = 11f
            setTextColor(getColor(R.color.muted))
        }.also { panel.addView(it) }

        val layout = WindowManager.LayoutParams(
            min(dp(370), resources.displayMetrics.widthPixels - dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        // The title (not the code field) is draggable so the underlying system dialog stays readable.
        title.setOnTouchListener(object : View.OnTouchListener {
            private var downY = 0f
            private var startY = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY
                        startY = layout.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layout.y = (startY + (e.rawY - downY).toInt()).coerceIn(0,
                            resources.displayMetrics.heightPixels - dp(140))
                        window?.let { wm.updateViewLayout(it, layout) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> { v.performClick(); return true }
                }
                return false
            }
        })
        // Avoid summoning the IME until the user deliberately taps the code field.
        panel.isFocusableInTouchMode = true
        panel.requestFocus()
        wm.addView(panel, layout)
        window = panel
    }

    private fun submit() {
        if (pairing) return
        val field = codeField ?: return
        val normalized = AdbPairingCode.normalize(field.text.toString())
        if (normalized == null) {
            showStatus(getString(R.string.adb_invalid_code), R.color.bad)
            return
        }
        pairing = true
        submitButton?.isEnabled = false
        showStatus(getString(R.string.adb_pairing), R.color.muted)
        AdbPairingTask.submit(this, normalized) { result ->
            handler.post {
                if (result.isSuccess) {
                    stopSelf() // Success: remove the overlay automatically.
                } else {
                    pairing = false
                    submitButton?.isEnabled = true
                    field.text.clear()
                    showStatus(getString(R.string.adb_overlay_retry), R.color.bad)
                }
            }
        }
    }

    private fun showStatus(message: String, color: Int) {
        status?.text = message
        status?.setTextColor(getColor(color))
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideAfterTimeout)
        codeField?.text?.clear()
        window?.let { runCatching { wm.removeView(it) } }
        window = null
        codeField = null
        status = null
        submitButton = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_SHOW = "app.tether.adb.SHOW_PAIRING_OVERLAY"
        fun show(context: Context) {
            context.startService(Intent(context, PairingOverlayService::class.java).setAction(ACTION_SHOW))
        }
    }
}
