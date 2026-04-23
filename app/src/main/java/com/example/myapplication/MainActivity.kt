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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.theme.MyApplicationTheme
import org.opencv.android.OpenCVLoader
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.content.Context
import android.content.ComponentName

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

    // Запрос разрешения на запись экрана
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startFloatingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Необходимо разрешение на запись экрана", Toast.LENGTH_SHORT).show()
        }
    }

    // Лаунчер для возврата из Специальных возможностей
    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // Когда пользователь вернулся из настроек, продолжаем цепочку разрешений
        checkPermissionsAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Инициализация OpenCV
        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "Ошибка инициализации OpenCV!")
            Toast.makeText(this, "OpenCV не инициализирован!", Toast.LENGTH_LONG).show()
        } else {
            Log.d("OpenCV", "OpenCV успешно инициализирован.")
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        onStartClick = { checkPermissionsAndStart() },
                        onAccessibilityClick = { openAccessibilitySettings() }
                    )
                }
            }
        }
    }

    // Проверяет, включен ли наш сервис специальных возможностей
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val componentName = ComponentName(this, AutoClickerAccessibilityService::class.java)
        return enabledServices.contains(componentName.flattenToString())
    }

    // Функция проверки всех разрешений по цепочке
    private fun checkPermissionsAndStart() {
        // ШАГ 1: Проверяем Специальные возможности
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Включите Автокликер в Специальных возможностях!", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }

        // ШАГ 2: Проверяем разрешение "Поверх других окон"
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        // ШАГ 3: Все разрешения есть — запрашиваем запись экрана
        requestScreenCapture()
    }

    // Открывает раздел Специальные возможности
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilityLauncher.launch(intent)
    }

    // Вызываем системное окно Android "Разрешить приложению доступ к экрану?"
    private fun requestScreenCapture() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // Запускаем наш сервис и передаем ему важные данные
    private fun startFloatingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, FloatingWindowService::class.java).apply {
            putExtra("SCREEN_CAPTURE_RESULT_CODE", resultCode)
            putExtra("SCREEN_CAPTURE_INTENT", data)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish()
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onStartClick: () -> Unit,
    onAccessibilityClick: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onStartClick) {
            Text(text = "Запустить Автокликер")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAccessibilityClick) {
            Text(text = "Специальные возможности")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MyApplicationTheme {
        MainScreen(onStartClick = {}, onAccessibilityClick = {})
    }
}