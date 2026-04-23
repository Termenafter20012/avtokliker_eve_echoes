package com.example.myapplication

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

// Оптимизированный ImageMatcher с двухэтапным поиском (Пиксельная точность + Высокая скорость)
object ImageMatcher {

    private var cachedTemplateBitmap: Bitmap? = null
    private var cachedTemplateMat: Mat? = null
    private var cachedTemplateSmallMat: Mat? = null
    
    private const val COARSE_SCALE = 4.0 // Коэффициент быстрого поиска

    fun findTemplate(screen: Bitmap, template: Bitmap): Pair<Int, Int>? {
        // 1. Подготовка шаблонов (с кешированием — делается один раз)
        if (cachedTemplateBitmap != template) {
            releaseCache()
            
            val fullMat = Mat()
            Utils.bitmapToMat(template, fullMat)
            Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)
            cachedTemplateMat = fullMat
            
            val smallMat = Mat()
            Imgproc.resize(fullMat, smallMat, Size(fullMat.cols() / COARSE_SCALE, fullMat.rows() / COARSE_SCALE))
            cachedTemplateSmallMat = smallMat
            
            cachedTemplateBitmap = template
        }
        
        val tempFull = cachedTemplateMat ?: return null
        val tempSmall = cachedTemplateSmallMat ?: return null

        // 2. Подготовка скриншота
        val screenFull = Mat()
        val screenSmall = Mat()
        val resultSmall = Mat()
        var screenROI: Mat? = null
        val resultFull = Mat()

        try {
            Utils.bitmapToMat(screen, screenFull)
            Imgproc.cvtColor(screenFull, screenFull, Imgproc.COLOR_RGBA2RGB)

            // --- ЭТАП 1: БЫСТРЫЙ ПОИСК (на уменьшенной копии) ---
            Imgproc.resize(screenFull, screenSmall, Size(screenFull.cols() / COARSE_SCALE, screenFull.rows() / COARSE_SCALE))
            
            Imgproc.matchTemplate(screenSmall, tempSmall, resultSmall, Imgproc.TM_CCOEFF_NORMED)
            val mmrSmall = Core.minMaxLoc(resultSmall)
            
            if (mmrSmall.maxVal < 0.7) {
                return null // Если даже примерно не нашли — выходим
            }

            // --- ЭТАП 2: ТОЧНЫЙ ПОИСК (в окрестности найденной точки) ---
            val approxX = (mmrSmall.maxLoc.x * COARSE_SCALE).toInt()
            val approxY = (mmrSmall.maxLoc.y * COARSE_SCALE).toInt()

            val margin = 20
            val roiX = (approxX - margin).coerceIn(0, screenFull.cols() - tempFull.cols())
            val roiY = (approxY - margin).coerceIn(0, screenFull.rows() - tempFull.rows())
            val roiW = (tempFull.cols() + margin * 2).coerceAtMost(screenFull.cols() - roiX)
            val roiH = (tempFull.rows() + margin * 2).coerceAtMost(screenFull.rows() - roiY)

            screenROI = screenFull.submat(Rect(roiX, roiY, roiW, roiH))
            Imgproc.matchTemplate(screenROI, tempFull, resultFull, Imgproc.TM_CCOEFF_NORMED)
            
            val mmrFull = Core.minMaxLoc(resultFull)

            return if (mmrFull.maxVal >= 0.90) {
                Pair(roiX + mmrFull.maxLoc.x.toInt(), roiY + mmrFull.maxLoc.y.toInt())
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            // ГАРАНТИРОВАННАЯ очистка памяти — даже при ошибке или раннем return
            screenFull.release()
            screenSmall.release()
            resultSmall.release()
            screenROI?.release()
            resultFull.release()
        }
    }

    private fun releaseCache() {
        cachedTemplateMat?.release()
        cachedTemplateSmallMat?.release()
        cachedTemplateMat = null
        cachedTemplateSmallMat = null
    }
}
