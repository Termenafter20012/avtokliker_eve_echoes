package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AutoClickerAccessibilityService : AccessibilityService() {

    // companion object - это аналог статических переменных.
    // Мы сохраняем ссылку на сам сервис, чтобы другие части приложения (например, плавающее окно)
    // могли легко достучаться до него и дать команду "Кликнуть".
    companion object {
        var instance: AutoClickerAccessibilityService? = null
    }

    // Вызывается системой Android, когда пользователь включает сервис в настройках
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this // Сохраняем ссылку на рабочий сервис
        Log.d("AutoClicker", "Служба специальных возможностей подключена")
    }

    // Эта функция вызывается, когда на экране что-то происходит (клик, скролл). 
    // Нам это отслеживать не нужно, поэтому оставляем пустой.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // Вызывается, если система прервала работу сервиса
    override fun onInterrupt() {}

    // Вызывается, когда пользователь выключает сервис в настройках
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null // Удаляем ссылку
        return super.onUnbind(intent)
    }

    // Наша главная функция: Симуляция клика в нужные координаты экрана!
    fun performClick(x: Float, y: Float) {
        val path = Path() // Создаем "путь" для жеста
        path.moveTo(x, y) // Перемещаемся в нужную точку (клик без движения)
        
        // Описание жеста (штриха). Продолжительность касания - 100 миллисекунд (как обычный быстрый тап пальцем)
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        
        // Собираем жест
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        // Даем команду системе: "Выполни этот жест (клик)"
        val result = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d("AutoClicker", "Системный клик успешно выполнен!")
            }
        }, null)
        
        Log.d("AutoClicker", "Отправлена команда клика на X=$x Y=$y. Успешно: $result")
    }
}
