package com.example.myapplication.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.example.myapplication.R
import kotlinx.coroutines.*

class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onStartStopClicked()
        fun onCloseClicked()
        fun onMagnifierAimChanged(rawX: Int, rawY: Int)
        fun onMagnifierRefreshRequested()
        fun getLatestLogText(): String
    }

    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var logWindowView: View? = null
    private var tvLogs: TextView? = null

    private var magnifierWindowView: View? = null
    private var ivZoom: ImageView? = null
    private var tvCoords: TextView? = null
    private var tvColorHex: TextView? = null
    private var magnifierUpdateJob: Job? = null

    private var btnStart: ImageButton? = null
    private var isDevMode = false

    private var lastTargetColor: Int = 0

    // Temporary storage for offset until BotEngine takes over completely
    var calibOffsetX = 294
    var calibOffsetY = 51
    var zoomFactor = 8

    fun showMainOverlay() {
        if (floatingView != null) return

        floatingView = LayoutInflater.from(context).inflate(R.layout.layout_floating_window, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 100
        }

        windowManager.addView(floatingView, params)
        setupMainOverlayListeners()
        updateDevUiVisibility()
    }

    fun removeAllOverlays() {
        floatingView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        logWindowView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        magnifierWindowView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        
        magnifierUpdateJob?.cancel()
        magnifierUpdateJob = null

        floatingView = null
        logWindowView = null
        magnifierWindowView = null
    }

    fun setDevMode(enabled: Boolean) {
        isDevMode = enabled
        updateDevUiVisibility()
    }

    fun updateStartButtonIcon(isSearching: Boolean) {
        if (isSearching) {
            btnStart?.setImageResource(R.drawable.ic_stop)
        } else {
            btnStart?.setImageResource(R.drawable.ic_play)
        }
    }

    fun updateLogs(text: String) {
        tvLogs?.text = text
    }

    private fun setupMainOverlayListeners() {
        val rootContainer = floatingView?.findViewById<View>(R.id.root_container) ?: return
        btnStart = floatingView?.findViewById(R.id.btn_start)
        val btnLogs = floatingView?.findViewById<ImageButton>(R.id.btn_logs)
        val btnClose = floatingView?.findViewById<ImageButton>(R.id.btn_close)
        val btnMagnifier = floatingView?.findViewById<ImageButton>(R.id.btn_magnifier)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoved = false

        val createTouchListener = { action: () -> Unit ->
            View.OnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).start()
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val diffX = (event.rawX - initialTouchX).toInt()
                        val diffY = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(diffX) > 10 || Math.abs(diffY) > 10) isMoved = true
                        params?.let {
                            it.x = initialX + diffX
                            it.y = initialY + diffY
                            windowManager.updateViewLayout(floatingView, it)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        if (!isMoved && event.action == MotionEvent.ACTION_UP) action()
                        true
                    }
                    else -> false
                }
            }
        }

        rootContainer.setOnTouchListener(createTouchListener { })
        btnStart?.setOnTouchListener(createTouchListener { callbacks.onStartStopClicked() })
        btnLogs?.setOnTouchListener(createTouchListener { toggleLogWindow() })
        btnMagnifier?.setOnTouchListener(createTouchListener { toggleMagnifierWindow() })
        btnClose?.setOnTouchListener(createTouchListener { callbacks.onCloseClicked() })
    }

    private fun updateDevUiVisibility() {
        if (floatingView == null) return
        val btnMagnifier = floatingView?.findViewById<View>(R.id.btn_magnifier)
        btnMagnifier?.visibility = if (isDevMode) View.VISIBLE else View.GONE
        
        if (isDevMode && magnifierWindowView == null) {
            showMagnifierWindow()
        } else if (!isDevMode && magnifierWindowView != null) {
            closeMagnifierWindow()
        }
    }

    // --- LOG WINDOW ---
    private fun toggleLogWindow() {
        if (logWindowView != null) closeLogWindow() else showLogWindow()
    }

    private fun showLogWindow() {
        logWindowView = LayoutInflater.from(context).inflate(R.layout.layout_log_window, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val logParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        windowManager.addView(logWindowView, logParams)

        tvLogs = logWindowView?.findViewById(R.id.tv_logs)
        tvLogs?.text = callbacks.getLatestLogText()

        logWindowView?.findViewById<Button>(R.id.btn_close_logs)?.setOnClickListener { closeLogWindow() }
        logWindowView?.findViewById<Button>(R.id.btn_copy_logs)?.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("AutoClicker Logs", callbacks.getLatestLogText())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Логи скопированы в буфер обмена", Toast.LENGTH_SHORT).show()
        }

        val logHeader = logWindowView?.findViewById<View>(R.id.log_header)
        var logInitX = 0; var logInitY = 0
        var logInitTouchX = 0f; var logInitTouchY = 0f

        logHeader?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    logInitX = logParams.x; logInitY = logParams.y
                    logInitTouchX = event.rawX; logInitTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    logParams.x = logInitX + (event.rawX - logInitTouchX).toInt()
                    logParams.y = logInitY + (event.rawY - logInitTouchY).toInt()
                    windowManager.updateViewLayout(logWindowView, logParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun closeLogWindow() {
        logWindowView?.let { windowManager.removeView(it) }
        logWindowView = null
        tvLogs = null
    }

    // --- MAGNIFIER WINDOW ---
    private fun toggleMagnifierWindow() {
        if (magnifierWindowView != null) closeMagnifierWindow() else showMagnifierWindow()
    }

    private fun showMagnifierWindow() {
        magnifierWindowView = LayoutInflater.from(context).inflate(R.layout.layout_magnifier_window, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val magParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        windowManager.addView(magnifierWindowView, magParams)

        // Periodic refresh (1 FPS) to show screen changes even if crosshair is static
        magnifierUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                delay(1000)
                callbacks.onMagnifierRefreshRequested()
            }
        }

        ivZoom = magnifierWindowView?.findViewById(R.id.iv_zoom_window)
        tvCoords = magnifierWindowView?.findViewById(R.id.tv_coords)
        tvColorHex = magnifierWindowView?.findViewById(R.id.tv_color_hex)

        val getStep = { Math.max(1, 8 / zoomFactor) }
        
        magnifierWindowView?.findViewById<ImageButton>(R.id.btn_up_mag)?.setOnClickListener { calibOffsetY -= getStep(); callbacks.onMagnifierRefreshRequested() }
        magnifierWindowView?.findViewById<ImageButton>(R.id.btn_down_mag)?.setOnClickListener { calibOffsetY += getStep(); callbacks.onMagnifierRefreshRequested() }
        magnifierWindowView?.findViewById<ImageButton>(R.id.btn_left_mag)?.setOnClickListener { calibOffsetX -= getStep(); callbacks.onMagnifierRefreshRequested() }
        magnifierWindowView?.findViewById<ImageButton>(R.id.btn_right_mag)?.setOnClickListener { calibOffsetX += getStep(); callbacks.onMagnifierRefreshRequested() }

        val tvZoomLevel = magnifierWindowView?.findViewById<TextView>(R.id.tv_zoom_level)
        val seekbarZoom = magnifierWindowView?.findViewById<android.widget.SeekBar>(R.id.seekbar_zoom)
        
        seekbarZoom?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                zoomFactor = 1 shl progress // 0->1, 1->2, 2->4, 3->8
                tvZoomLevel?.text = "x$zoomFactor"
                callbacks.onMagnifierRefreshRequested()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        magnifierWindowView?.findViewById<Button>(R.id.btn_copy_mag)?.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = "X: $calibOffsetX, Y: $calibOffsetY, Color: #${String.format("%06X", lastTargetColor)}"
            val clip = ClipData.newPlainText("Pixel Info", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Данные скопированы", Toast.LENGTH_SHORT).show()
        }

        val btnAim = magnifierWindowView?.findViewById<View>(R.id.btn_aim)
        btnAim?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {
                    callbacks.onMagnifierAimChanged(event.rawX.toInt(), event.rawY.toInt())
                    true
                }
                else -> true
            }
        }

        val dragHandle = magnifierWindowView?.findViewById<View>(R.id.mag_drag_handle)
        var mInitX = 0; var mInitY = 0
        var mTouchX = 0f; var mTouchY = 0f

        dragHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mInitX = magParams.x; mInitY = magParams.y
                    mTouchX = event.rawX; mTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    magParams.x = mInitX + (event.rawX - mTouchX).toInt()
                    magParams.y = mInitY + (event.rawY - mTouchY).toInt()
                    windowManager.updateViewLayout(magnifierWindowView, magParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun closeMagnifierWindow() {
        magnifierUpdateJob?.cancel()
        magnifierUpdateJob = null

        magnifierWindowView?.let { windowManager.removeView(it) }
        magnifierWindowView = null
        ivZoom = null
        tvCoords = null
        tvColorHex = null
    }

    fun updateMagnifierUI(scaledBitmap: Bitmap, centerX: Int, centerY: Int, targetColor: Int) {
        lastTargetColor = targetColor
        ivZoom?.setImageBitmap(scaledBitmap)
        tvCoords?.text = "X: $calibOffsetX, Y: $calibOffsetY"
        tvColorHex?.text = "HEX: #${String.format("%06X", targetColor)}"
    }
}
