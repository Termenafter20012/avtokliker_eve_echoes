package com.example.myapplication

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    // Инструмент для запроса разрешения "Поверх других окон"
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            // Если дали права на окно, сразу просим права на запись экрана
            requestScreenCapture()
        } else {
            Toast.makeText(this, "Необходимо разрешение для плавающего окна", Toast.LENGTH_SHORT).show()
        }
    }

    // НОВЫЙ ИНСТРУМЕНТ: Запрос разрешения на запись экрана (для поиска картинок)
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Проверяем, нажал ли пользователь "Начать" в системном окне записи экрана
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Запускаем сервис и передаем ему "токен" (разрешение) на запись экрана
            startFloatingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Необходимо разрешение на запись экрана", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStartClick = { checkPermissionsAndStart() }
                    )
                }
            }
        }
    }

    // Функция проверки всех разрешений по цепочке
    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            // Если разрешение на окно уже есть, просим запись экрана
            requestScreenCapture()
        }
    }

    // Вызываем системное окно Android "Разрешить приложению доступ к экрану?"
    private fun requestScreenCapture() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // Запускаем наш сервис и передаем ему важные данные
    private fun startFloatingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, FloatingWindowService::class.java).apply {
            // Кладем в "багажник" Интента данные для записи экрана, чтобы сервис мог ими воспользоваться
            putExtra("SCREEN_CAPTURE_RESULT_CODE", resultCode)
            putExtra("SCREEN_CAPTURE_INTENT", data)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish() // Сворачиваем приложение
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier, onStartClick: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onStartClick) {
            Text(text = "Запустить Автокликер")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MyApplicationTheme {
        MainScreen(onStartClick = {})
    }
}