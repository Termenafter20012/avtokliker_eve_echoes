package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.Color

// Объект (Singleton) ImageMatcher - набор готовых функций для работы с картинками
object ImageMatcher {

    /**
     * Ищет маленькую картинку (template) на большом скриншоте (screen).
     * Возвращает пару координат (X, Y) центра найденной картинки или null, если ничего не найдено.
     */
    fun findTemplate(screen: Bitmap, template: Bitmap): Pair<Int, Int>? {
        // Если искомая картинка больше самого экрана - ошибка, возвращаем null
        if (screen.width < template.width || screen.height < template.height) {
            return null
        }

        val screenWidth = screen.width
        val screenHeight = screen.height
        val templateWidth = template.width
        val templateHeight = template.height

        // Для огромной скорости поиска мы не используем стандартный getPixel() (он очень медленный),
        // а сразу выгружаем все пиксели обеих картинок в простые массивы чисел (цветов).
        val screenPixels = IntArray(screenWidth * screenHeight)
        screen.getPixels(screenPixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)

        val templatePixels = IntArray(templateWidth * templateHeight)
        template.getPixels(templatePixels, 0, templateWidth, 0, 0, templateWidth, templateHeight)

        // Шаг сканирования экрана. ДОЛЖЕН БЫТЬ 1, чтобы проверять каждый пиксель. 
        // Шаг 2 пропускал половину пикселей, и если картинка стояла на нечетной координате, она не находилась.
        val step = 1 
        
        // Проходимся по экрану, как сканером (сверху вниз, слева направо)
        for (y in 0..screenHeight - templateHeight step step) {
            for (x in 0..screenWidth - templateWidth step step) {
                // Вызываем проверку: совпадает ли вырезанный кусок экрана с нашим шаблоном?
                if (checkMatch(screenPixels, screenWidth, x, y, templatePixels, templateWidth, templateHeight)) {
                    // Если да, вычисляем координаты ЦЕНТРА картинки (чтобы кликнуть ровно в середину)
                    val centerX = x + (templateWidth / 2)
                    val centerY = y + (templateHeight / 2)
                    return Pair(centerX, centerY)
                }
            }
        }
        return null // Прошли весь экран и ничего не нашли
    }

    // Функция проверки конкретного участка экрана
    private fun checkMatch(
        screenPixels: IntArray, screenWidth: Int, startX: Int, startY: Int,
        templatePixels: IntArray, templateWidth: Int, templateHeight: Int
    ): Boolean {
        // ПОРОГ СОВПАДЕНИЯ (Match Threshold) = 90% (0.90)
        // Изображение будет признано найденным, если совпадает хотя бы 90% пикселей
        val matchThreshold = 0.90f

        // ОПТИМИЗАЦИЯ: Сначала проверим 5 контрольных точек (углы и центр)
        val fastChecks = listOf(
            Pair(0, 0),
            Pair(templateWidth - 1, 0),
            Pair(0, templateHeight - 1),
            Pair(templateWidth - 1, templateHeight - 1),
            Pair(templateWidth / 2, templateHeight / 2)
        )
        
        var fastMismatches = 0
        for (check in fastChecks) {
            val tx = check.first
            val ty = check.second
            val tColor = templatePixels[ty * templateWidth + tx]
            
            if (Color.alpha(tColor) < 50) continue 
            
            val sColor = screenPixels[(startY + ty) * screenWidth + (startX + tx)]
            if (!colorsMatch(sColor, tColor)) {
                fastMismatches++
            }
        }

        // Если в 5 быстрых точках больше 1 ошибки - бракуем позицию сразу
        if (fastMismatches > 1) return false

        // --- ПОЛНАЯ ПРОВЕРКА С ПОДСЧЕТОМ КОЭФФИЦИЕНТА ---
        val step = 2 // Проверяем каждый 2-й пиксель шаблона для ускорения (этого достаточно)
        
        // Считаем общее количество непрозрачных пикселей в шаблоне, которые мы будем проверять
        var maxPossibleChecks = 0
        for (ty in 0 until templateHeight step step) {
            for (tx in 0 until templateWidth step step) {
                if (Color.alpha(templatePixels[ty * templateWidth + tx]) >= 50) {
                    maxPossibleChecks++
                }
            }
        }
        
        if (maxPossibleChecks == 0) return false
        val requiredMatches = (maxPossibleChecks * matchThreshold).toInt()

        var matchCount = 0
        var totalChecked = 0

        for (ty in 0 until templateHeight step step) {
            for (tx in 0 until templateWidth step step) {
                val tColor = templatePixels[ty * templateWidth + tx]
                if (Color.alpha(tColor) < 50) continue

                val sColor = screenPixels[(startY + ty) * screenWidth + (startX + tx)]
                totalChecked++
                
                if (colorsMatch(sColor, tColor)) {
                    matchCount++
                }
                
                // Ранний выход: если оставшихся пикселей уже не хватит, чтобы достичь 90%
                val remainingChecks = maxPossibleChecks - totalChecked
                if (matchCount + remainingChecks < requiredMatches) {
                    return false 
                }
            }
        }
        
        return matchCount >= requiredMatches
    }

    // Сравниваем цвета с учетом допуска (Tolerance) и поиска в оттенках серого (GREY)
    private fun colorsMatch(c1: Int, c2: Int): Boolean {
        val tolerance = 30 // Увеличенный допуск до 30 для защиты от искажений цвета

        val r1 = Color.red(c1)
        val g1 = Color.green(c1)
        val b1 = Color.blue(c1)
        
        val r2 = Color.red(c2)
        val g2 = Color.green(c2)
        val b2 = Color.blue(c2)
        
        // 1. Проверка по цветам (RGB)
        val rgbMatch = Math.abs(r1 - r2) <= tolerance && 
                       Math.abs(g1 - g2) <= tolerance && 
                       Math.abs(b1 - b2) <= tolerance
                       
        // 2. Проверка в оттенках серого (GREY)
        // Защищает от ситуаций, когда включен ночной режим или фильтр, меняющий цвета, но сохраняющий контуры
        val gray1 = (r1 + g1 + b1) / 3
        val gray2 = (r2 + g2 + b2) / 3
        val grayMatch = Math.abs(gray1 - gray2) <= tolerance

        // Пиксель считается совпавшим, если совпал либо его цвет, либо его яркость (GREY)
        return rgbMatch || grayMatch
    }
}
