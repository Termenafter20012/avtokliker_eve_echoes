package com.example.myapplication.engine

import android.graphics.Bitmap
import com.example.myapplication.capture.ScreenCapturer
import com.example.myapplication.detection.EnemyDetector
import kotlinx.coroutines.*

class BotEngine(
    private val screenCapturer: ScreenCapturer,
    private val enemyDetector: EnemyDetector,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onLogRequested(message: String)
        fun onEnemyDetected(hasEnemy: Boolean, hasNeutral: Boolean)
        fun onActivityStarted()
        fun onEngineStopped()
    }

    private var isRunning = false
    private var engineJob: Job? = null
    
    var targetBitmap: Bitmap? = null
    var isEnemySearchEnabled = true
    
    // For calibrating the magnifier
    var currentCalibOffsetX = 0
    var currentCalibOffsetY = 0

    // State tracking to prevent spam
    private var wasEnemyPresentLastTime = false
    private var lastPanelPos: Pair<Int, Int>? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        wasEnemyPresentLastTime = false
        
        callbacks.onLogRequested("=== БОТ ЗАПУЩЕН ===")

        engineJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                while (isRunning) {
                    val screenBitmap = screenCapturer.getLatestScreenshot()
                    
                    if (screenBitmap != null) {
                        if (isEnemySearchEnabled) {
                            val (panelPos, result) = enemyDetector.performMasterSearch(screenBitmap, targetBitmap)
                            
                            if (panelPos != null) {
                                lastPanelPos = panelPos
                            }
                            
                            if (result != null && result.isControlOk) {
                                handleDetectionResult(result)
                            }
                        }
                        
                        screenBitmap.recycle()
                    } else {
                        callbacks.onLogRequested("Диагностика: скриншот не получен (ждем кадр...)")
                    }
                    
                    delay(1000)
                }
            } catch (e: CancellationException) {
                // Normal stop
            } catch (e: Exception) {
                callbacks.onLogRequested("КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
            } finally {
                callbacks.onLogRequested("Цикл работы бота завершен.")
                isRunning = false
                withContext(Dispatchers.Main) {
                    callbacks.onEngineStopped()
                }
            }
        }
    }

    private fun handleDetectionResult(result: EnemyDetector.DetectionResult) {
        val isEnemyCurrentlyPresent = result.hasEnemy || result.hasNeutral
        
        callbacks.onEnemyDetected(result.hasEnemy, result.hasNeutral)

        if (isEnemyCurrentlyPresent) {
            if (!wasEnemyPresentLastTime) {
                callbacks.onLogRequested("!!! ОБНАРУЖЕНА АКТИВНОСТЬ !!!")
                callbacks.onActivityStarted()
            }
        } else {
            if (wasEnemyPresentLastTime) {
                callbacks.onLogRequested("Чисто: враги покинули локацию")
            }
            callbacks.onLogRequested("Врагов нет (0/0)")
        }
        
        wasEnemyPresentLastTime = isEnemyCurrentlyPresent
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        engineJob?.cancel()
        callbacks.onLogRequested("=== БОТ ОСТАНОВЛЕН ===")
    }

    fun isRunning(): Boolean = isRunning
    
    fun setCalibrationFromRaw(rawX: Int, rawY: Int, smallerSide: Int, scaleFactor: Double) {
        val px = if (isEnemySearchEnabled) (lastPanelPos?.first ?: 0) else 0
        val py = if (isEnemySearchEnabled) (lastPanelPos?.second ?: 0) else 0
        
        // Use exact values for known resolutions, otherwise fallback to scaling from 1080
        val newOffX = when (smallerSide) {
            1840, 1440, 1080 -> (rawX - px)
            else -> ((rawX - px) / scaleFactor).toInt()
        }
        val newOffY = when (smallerSide) {
            1840, 1440, 1080 -> (rawY - py)
            else -> ((rawY - py) / scaleFactor).toInt()
        }
        
        currentCalibOffsetX = newOffX
        currentCalibOffsetY = newOffY
        
        requestMagnifierRefresh()
    }
    
    // Explicit call from Dev UI to refresh magnifier without heavy OpenCV search
    fun requestMagnifierRefresh() {
        val screenBitmap = screenCapturer.getLatestScreenshot()
        if (screenBitmap != null) {
            val px = if (isEnemySearchEnabled) (lastPanelPos?.first ?: 0) else 0
            val py = if (isEnemySearchEnabled) (lastPanelPos?.second ?: 0) else 0
            
            enemyDetector.getPixelInfoForMagnifier(screenBitmap, Pair(px, py), currentCalibOffsetX, currentCalibOffsetY)
            screenBitmap.recycle()
        }
    }
}
