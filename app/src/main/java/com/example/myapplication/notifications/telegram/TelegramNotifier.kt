package com.example.myapplication.notifications.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class TelegramNotifier(
    private val token: String,
    private val chatId: String,
    private val onLogRequested: (String) -> Unit
) {
    private var lastEnemyDetected = false
    private var lastNeutralDetected = false

    fun notifyState(hasEnemy: Boolean, hasNeutral: Boolean) {
        if (hasEnemy && !lastEnemyDetected) {
            sendAlert("🚨 ВНИМАНИЕ! Обнаружен ВРАГ (Минус)!")
        }
        
        if (hasNeutral && !lastNeutralDetected) {
            sendAlert("⚠️ ВНИМАНИЕ! Обнаружен НЕЙТРАЛ!")
        }

        lastEnemyDetected = hasEnemy
        lastNeutralDetected = hasNeutral
    }

    private fun sendAlert(message: String) {
        if (chatId.isEmpty() || token.isEmpty()) {
            onLogRequested("Telegram: Token или Chat ID не задан!")
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val url = "https://api.telegram.org/bot$token/sendMessage"
                
                val formBody = FormBody.Builder()
                    .add("chat_id", chatId)
                    .add("text", message)
                    .build()
                
                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    onLogRequested("Telegram: Уведомление отправлено")
                } else {
                    onLogRequested("Telegram: Ошибка ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                onLogRequested("Telegram: Ошибка сети - ${e.message}")
            }
        }
    }
}
