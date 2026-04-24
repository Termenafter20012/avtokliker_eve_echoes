package com.example.myapplication.notifications.discord

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class DiscordNotifier(
    private val webhookUrl: String,
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
        if (webhookUrl.isEmpty()) {
            onLogRequested("Discord: Webhook URL не задан!")
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val json = """{"content": "$message"}"""
                val body = RequestBody.create("application/json; charset=utf-8".toMediaType(), json)
                
                val request = Request.Builder()
                    .url(webhookUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Android AutoClicker)")
                    .post(body)
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    onLogRequested("Discord: Уведомление отправлено")
                } else {
                    onLogRequested("Discord: Ошибка ${response.code}")
                    val errorBody = response.body?.string()
                    if (errorBody != null) {
                        Log.e("Discord", "Error response: $errorBody")
                    }
                }
                response.close()
            } catch (e: Exception) {
                onLogRequested("Discord: Ошибка сети - ${e.message}")
            }
        }
    }
}
