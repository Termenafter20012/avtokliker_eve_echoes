package com.example.myapplication.notifications

import android.content.Context
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class ReactionManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onLogRequested: (String) -> Unit
) {
    private var alarmPlayer: MediaPlayer? = null
    private var alarmOverlay: View? = null
    private var isAlarmActive = false

    fun triggerReaction(selectedReactions: Set<String>) {
        if (selectedReactions.contains("Ничего")) return
        
        if (selectedReactions.contains("Звук")) {
            startPersistentAlarm()
        }
        
        if (selectedReactions.contains("Вибрация")) {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        }
    }

    private fun startPersistentAlarm() {
        if (isAlarmActive) return
        isAlarmActive = true
        
        showAlarmOverlay()

        val resId = context.resources.getIdentifier("alarm", "raw", context.packageName)
        if (resId != 0) {
            try {
                alarmPlayer?.stop()
                alarmPlayer?.release()
                
                alarmPlayer = MediaPlayer.create(context, resId)
                alarmPlayer?.isLooping = false
                
                alarmPlayer?.setOnCompletionListener {
                    stopAlarm()
                }
                
                alarmPlayer?.start()
            } catch (e: Exception) {
                playFallbackTone()
                stopAlarm()
            }
        } else {
            playFallbackTone()
            stopAlarm()
        }
    }

    private fun showAlarmOverlay() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        alarmOverlay = View(context).apply {
            setBackgroundColor(0x01000000) // Почти невидимый
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    onLogRequested("Звук прерван нажатием на экран")
                    stopAlarm()
                }
                true
            }
        }

        try {
            windowManager.addView(alarmOverlay, params)
        } catch (e: Exception) {
            Log.e("ReactionManager", "Не удалось создать overlay: ${e.message}")
        }
    }

    fun stopAlarm() {
        if (!isAlarmActive) return
        isAlarmActive = false
        
        try {
            alarmPlayer?.stop()
            alarmPlayer?.release()
            alarmPlayer = null
        } catch (e: Exception) {}

        if (alarmOverlay != null) {
            try { windowManager.removeView(alarmOverlay) } catch (e: Exception) {}
            alarmOverlay = null
        }
    }

    private fun playFallbackTone() {
        try {
            val toneG = ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
            toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
        } catch (e: Exception) {
            Log.e("ReactionManager", "Ошибка звука: ${e.message}")
        }
    }
}
