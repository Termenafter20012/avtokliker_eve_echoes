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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

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
            startFloatingService(
                result.resultCode, 
                result.data!!, 
                pendingEnemySearch, 
                pendingReaction,
                pendingDevMode
            )
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
                        onStartClick = { isEnemySearch, reaction, isDev -> 
                            checkPermissionsAndStart(isEnemySearch, reaction, isDev) 
                        },
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

    // Переменные для временного хранения настроек во время цепочки разрешений
    private var pendingEnemySearch = true
    private var pendingReaction = "Звук"
    private var pendingDevMode = false

    // Функция проверки всех разрешений по цепочке
    private fun checkPermissionsAndStart(
        isEnemySearch: Boolean = true, 
        reaction: String = "Звук",
        isDevMode: Boolean = false
    ) {
        pendingEnemySearch = isEnemySearch
        pendingReaction = reaction
        pendingDevMode = isDevMode

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
    private fun startFloatingService(
        resultCode: Int, 
        data: Intent,
        isEnemySearch: Boolean = true,
        reaction: String = "Звук",
        isDevMode: Boolean = false
    ) {
        val intent = Intent(this, FloatingWindowService::class.java).apply {
            putExtra("SCREEN_CAPTURE_RESULT_CODE", resultCode)
            putExtra("SCREEN_CAPTURE_INTENT", data)
            putExtra("SETTING_ENEMY_SEARCH", isEnemySearch)
            putExtra("SETTING_REACTION", reaction)
            putExtra("SETTING_DEV_MODE", isDevMode)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // finish() // Не закрываем сразу, чтобы пользователь мог изменить настройки
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onStartClick: (Boolean, String, Boolean) -> Unit,
    onAccessibilityClick: () -> Unit
) {
    var isEnemySearchEnabled by remember { mutableStateOf(true) }
    var selectedReaction by remember { mutableStateOf("Звук") }
    var expanded by remember { mutableStateOf(false) }
    val reactions = listOf("Звук", "Вибрация", "Ничего")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212)) // Тёмный фон для премиальности
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ВЕРХНЯЯ КНОПКА
        Button(
            onClick = { onStartClick(isEnemySearchEnabled, selectedReaction, false) },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
        ) {
            Text(
                text = "ЗАПУСТИТЬ АВТОКЛИКЕР",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // ЦЕНТРАЛЬНЫЙ БЛОК НАСТРОЕК
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Поиск врагов
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Поиск врагов",
                    color = Color.White,
                    fontSize = 16.sp
                )
                Checkbox(
                    checked = isEnemySearchEnabled,
                    onCheckedChange = { isEnemySearchEnabled = it },
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF3F51B5))
                )
            }

            // Реакция
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Реакция",
                    color = Color.White,
                    fontSize = 16.sp
                )
                
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier.width(120.dp)
                    ) {
                        Text(text = selectedReaction)
                        Text(text = " ▼", color = Color.White)
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(Color(0xFF2C2C2C))
                    ) {
                        reactions.forEach { reaction ->
                            DropdownMenuItem(
                                text = { Text(reaction, color = Color.White) },
                                onClick = {
                                    selectedReaction = reaction
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // НИЖНИЕ КНОПКИ
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onStartClick(isEnemySearchEnabled, selectedReaction, true) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
            ) {
                Text(text = "Запустить для разработчика", fontSize = 14.sp)
            }
            
            OutlinedButton(
                onClick = onAccessibilityClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp)
            ) {
                Text(
                    text = "Спец. возможности",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MyApplicationTheme {
        MainScreen(onStartClick = { _, _, _ -> }, onAccessibilityClick = {})
    }
}