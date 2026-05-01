package com.example.myapplication.detection

import android.graphics.Bitmap
import com.example.myapplication.ImageMatcher

class EnemyDetector(
    private val smallerSide: Int,
    private val scaleFactor: Double,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onLogRequested(message: String)
        fun onTargetCalibrated(x: Int, y: Int, color: Int, screen: Bitmap)
    }

    private val digitRange = 6000000..16777215
    private val bgRange = 0..5000000
    private val controlRange = 5000000..13000000

    data class DetectionResult(
        val hasEnemy: Boolean,
        val hasNeutral: Boolean,
        val isControlOk: Boolean
    )

    fun performMasterSearch(screen: Bitmap, targetBitmap: Bitmap?): Pair<Pair<Int, Int>?, DetectionResult?> {
        if (targetBitmap == null) {
            callbacks.onLogRequested("ОШИБКА: Шаблон панели не загружен!")
            return Pair(null, null)
        }

        val foundResult = try {
            ImageMatcher.findTemplate(screen, targetBitmap)
        } catch (e: Exception) {
            callbacks.onLogRequested("ОШИБКА В ImageMatcher: ${e.message}")
            null
        }

        if (foundResult != null) {
            val (x, y, score) = foundResult
            val scorePercent = (score * 100).toInt()

            if (x != -1) {
                callbacks.onLogRequested("Панель: ($x, $y). Сходство: $scorePercent%")
                val detectionResult = checkEnemy(screen, Pair(x, y), 0, 0)
                return Pair(Pair(x, y), detectionResult)
            } else {
                var msg = "Панель не найдена (Сходство: $scorePercent%)"
                if (scorePercent < 10) msg += " - Проверьте ориентацию экрана!"
                callbacks.onLogRequested(msg)
            }
        } else {
            callbacks.onLogRequested("Панель не найдена (общая ошибка)")
        }
        
        return Pair(null, null)
    }

    fun checkEnemy(screen: Bitmap, p: Pair<Int, Int>, calibOffsetX: Int, calibOffsetY: Int): DetectionResult {
        fun getColor(offX: Int, offY: Int): Int {
            // Use exact coordinates for known resolutions, otherwise fallback to scaling from 1080
            val finalOffX = when (smallerSide) {
                1840, 1440, 1080 -> offX
                else -> (offX * scaleFactor).toInt()
            }
            val finalOffY = when (smallerSide) {
                1840, 1440, 1080 -> offY
                else -> (offY * scaleFactor).toInt()
            }
            
            val x = p.first + finalOffX
            val y = p.second + finalOffY
            if (x < 0 || x >= screen.width || y < 0 || y >= screen.height) return 0
            return screen.getPixel(x, y) and 0xFFFFFF
        }

        val ctrlX = when (smallerSide) {
            1840 -> 224
            1440 -> 197
            else -> 157
        }
        val ctrlY = when (smallerSide) {
            1840 -> 52
            1440 -> 43
            else -> 48
        }
        
        val neutPoints = when (smallerSide) {
            1840 -> listOf(Pair(461, 76), Pair(461, 56), Pair(446, 56), Pair(444, 74))
            1440 -> listOf(Pair(393, 46), Pair(407, 46), Pair(393, 46), Pair(407, 64))
            else -> listOf(Pair(293, 48), Pair(293, 37), Pair(305, 37), Pair(305, 49))
        }

        val minusPoints = when (smallerSide) {
            1840 -> listOf(Pair(276, 76), Pair(276, 56), Pair(258, 56), Pair(258, 76))
            1440 -> listOf(Pair(227, 46), Pair(242, 46), Pair(227, 63), Pair(242, 63))
            else -> listOf(Pair(169, 49), Pair(169, 38), Pair(169, 48), Pair(180, 48))
        }

        val controlColor = getColor(ctrlX, ctrlY)
        val isControlOk = controlColor in controlRange
        
        if (!isControlOk) {
            val finalX = p.first + (when (smallerSide) {
                1840, 1440, 1080 -> ctrlX
                else -> (ctrlX * scaleFactor).toInt()
            })
            val finalY = p.second + (when (smallerSide) {
                1840, 1440, 1080 -> ctrlY
                else -> (ctrlY * scaleFactor).toInt()
            })
            callbacks.onLogRequested("Контроль смещен! ($finalX,$finalY): $controlColor")
        }

        val isNeutZero = isControlOk && neutPoints.all { pt ->
            val c = getColor(pt.first, pt.second)
            c in digitRange && c !in bgRange
        }

        val isMinusZero = isControlOk && minusPoints.all { pt ->
            val c = getColor(pt.first, pt.second)
            c in digitRange && c !in bgRange
        }

        if (isControlOk) {
            val hasNeutral = !isNeutZero
            val hasEnemy = !isMinusZero
            return DetectionResult(hasEnemy, hasNeutral, isControlOk)
        }

        return DetectionResult(hasEnemy = false, hasNeutral = false, isControlOk = false)
    }

    fun getPixelInfoForMagnifier(screen: Bitmap, p: Pair<Int, Int>, calibOffsetX: Int, calibOffsetY: Int) {
        val finalDebugX = when (smallerSide) {
            1840, 1440, 1080 -> calibOffsetX
            else -> (calibOffsetX * scaleFactor).toInt()
        }
        val finalDebugY = when (smallerSide) {
            1840, 1440, 1080 -> calibOffsetY
            else -> (calibOffsetY * scaleFactor).toInt()
        }
        val targetX = p.first + finalDebugX
        val targetY = p.second + finalDebugY
        
        var targetColor = 0
        if (targetX >= 0 && targetX < screen.width && targetY >= 0 && targetY < screen.height) {
            targetColor = screen.getPixel(targetX, targetY) and 0xFFFFFF
            callbacks.onLogRequested("Цвет: #${String.format("%06X", targetColor)} | Абс: ($targetX, $targetY) | Отн: ($finalDebugX, $finalDebugY)")
        }

        callbacks.onTargetCalibrated(targetX, targetY, targetColor, screen)
    }
}
