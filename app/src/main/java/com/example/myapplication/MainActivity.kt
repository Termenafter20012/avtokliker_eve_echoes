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
import android.media.MediaPlayer
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

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
                pendingReactions,
                pendingDevMode,
                pendingTgToken,
                pendingTgChatId,
                pendingDiscordWebhook
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
        checkPermissionsAndStart(pendingEnemySearch, pendingReactions, pendingDevMode, pendingTgToken, pendingTgChatId, pendingDiscordWebhook)
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
                        onStartClick = { isEnemySearch, reaction, isDev, token, chatId, discordUrl -> 
                            checkPermissionsAndStart(isEnemySearch, reaction, isDev, token, chatId, discordUrl) 
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
    private var pendingReactions = setOf("Звук")
    private var pendingDevMode = false
    private var pendingTgToken = ""
    private var pendingTgChatId = ""
    private var pendingDiscordWebhook = ""

    // Функция проверки всех разрешений по цепочке
    private fun checkPermissionsAndStart(
        isEnemySearch: Boolean, 
        reactions: Set<String>, 
        isDev: Boolean,
        tgToken: String = "",
        tgChatId: String = "",
        discordWebhook: String = ""
    ) {
        pendingEnemySearch = isEnemySearch
        pendingReactions = reactions
        pendingDevMode = isDev
        pendingTgToken = tgToken
        pendingTgChatId = tgChatId
        pendingDiscordWebhook = discordWebhook

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
        reactions: Set<String> = setOf("Звук"),
        isDevMode: Boolean = false,
        tgToken: String = "",
        tgChatId: String = "",
        discordWebhook: String = ""
    ) {
        val intent = Intent(this, FloatingWindowService::class.java).apply {
            putExtra("SCREEN_CAPTURE_RESULT_CODE", resultCode)
            putExtra("SCREEN_CAPTURE_INTENT", data)
            putExtra("SETTING_ENEMY_SEARCH", isEnemySearch)
            putStringArrayListExtra("SETTING_REACTIONS", ArrayList(reactions))
            putExtra("SETTING_DEV_MODE", isDevMode)
            putExtra("SETTING_TG_TOKEN", tgToken)
            putExtra("SETTING_TG_CHAT_ID", tgChatId)
            putExtra("SETTING_DISCORD_WEBHOOK", discordWebhook)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // finish() // Не закрываем сразу, чтобы пользователь мог изменить настройки
    }
}

enum class AppScreen {
    HOME, EYE, MINING, ANOMALIES, TRANSPORT
}

data class MenuOption(
    val title: String,
    val icon: ImageVector,
    val screen: AppScreen,
    val tint: Color,
    val bgColor: Color
)

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onStartClick: (Boolean, Set<String>, Boolean, String, String, String) -> Unit,
    onAccessibilityClick: () -> Unit
) {
    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
    
    // Настройки поиска (сохраняем состояние при переходах)
    var isEnemySearchEnabled by remember { mutableStateOf(true) }
    var selectedReactions by remember { mutableStateOf(setOf("Звук")) }
    var isDevMode by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var tgToken by remember { mutableStateOf(prefs.getString("tg_token", "") ?: "") }
    var tgChatId by remember { mutableStateOf(prefs.getString("tg_chat_id", "") ?: "") }
    var discordWebhookUrl by remember { mutableStateOf(prefs.getString("discord_webhook", "") ?: "") }

    // Сохраняем при изменении
    LaunchedEffect(tgToken, tgChatId, discordWebhookUrl) {
        prefs.edit()
            .putString("tg_token", tgToken)
            .putString("tg_chat_id", tgChatId)
            .putString("discord_webhook", discordWebhookUrl)
            .apply()
    }

    val menuOptions = listOf(
        MenuOption("ГЛАЗ", Icons.Default.Visibility, AppScreen.EYE, Color.White, Color(0xFF2E7D32)),
        MenuOption("КОПАТЬ", Icons.Default.Lock, AppScreen.MINING, Color.Gray, Color(0xFFC62828)),
        MenuOption("АНОМАЛИИ", Icons.Default.Lock, AppScreen.ANOMALIES, Color.Gray, Color(0xFFC62828)),
        MenuOption("ПЕРЕВОЗКА", Icons.Default.Lock, AppScreen.TRANSPORT, Color.Gray, Color(0xFFC62828))
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
    ) {
        val isLandscape = maxWidth > maxHeight
        val contentPadding = 8.dp

        if (isLandscape && currentScreen == AppScreen.EYE) {
            // ГОРИЗОНТАЛЬНЫЙ РЕЖИМ ДЛЯ ЭКРАНА "ГЛАЗ"
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                // Левая колонка с навигацией
                Column(
                    modifier = Modifier.fillMaxHeight().padding(end = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(onClick = { currentScreen = AppScreen.HOME }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onAccessibilityClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Color.Gray)
                    }
                }

                // Правая колонка с настройками и кнопкой
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Верхняя панель с заголовком и кнопкой запуска
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Настройки ГЛАЗ", 
                            color = Color.White, 
                            fontSize = 18.sp, 
                            fontWeight = FontWeight.Bold
                        )
                        
                        Button(
                            onClick = { onStartClick(isEnemySearchEnabled, selectedReactions, isDevMode, tgToken, tgChatId, discordWebhookUrl) },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)), // ЗЕЛЕНАЯ
                            elevation = ButtonDefaults.buttonElevation(4.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ЗАПУСТИТЬ", fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        EyeScreen(
                            isEnemySearchEnabled,
                            { isEnemySearchEnabled = it },
                            selectedReactions,
                            { selectedReactions = it },
                            isDevMode,
                            { isDevMode = it },
                            tgToken,
                            { tgToken = it },
                            tgChatId,
                            { tgChatId = it },
                            discordWebhookUrl,
                            { discordWebhookUrl = it }
                        )
                    }
                }
            }
        } else {
            // СТАНДАРТНЫЙ РЕЖИМ (ПОРТРЕТ ИЛИ ГЛАВНЫЙ ЭКРАН)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                // ЗАГОЛОВОК
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (currentScreen != AppScreen.HOME) {
                        IconButton(onClick = { currentScreen = AppScreen.HOME }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }

                    // Кнопка запуска в заголовке для режима ГЛАЗ
                    if (currentScreen == AppScreen.EYE) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onStartClick(isEnemySearchEnabled, selectedReactions, isDevMode, tgToken, tgChatId, discordWebhookUrl) },
                            modifier = Modifier.height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)), // ЗЕЛЕНАЯ
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("ЗАПУСТИТЬ", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(onClick = onAccessibilityClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки", tint = Color.Gray)
                    }
                }

                // КОНТЕНТ ЭКРАНА
                Box(modifier = Modifier.weight(1f)) {
                    when (currentScreen) {
                        AppScreen.HOME -> HomeScreen(menuOptions) { currentScreen = it }
                        AppScreen.EYE -> {
                            Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                EyeScreen(
                                    isEnemySearchEnabled,
                                    { isEnemySearchEnabled = it },
                                    selectedReactions,
                                    { selectedReactions = it },
                                    isDevMode,
                                    { isDevMode = it },
                                    tgToken,
                                    { tgToken = it },
                                    tgChatId,
                                    { tgChatId = it },
                                    discordWebhookUrl,
                                    { discordWebhookUrl = it }
                                )
                            }
                        }
                        else -> DevelopmentScreen(currentScreen.name)
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(options: List<MenuOption>, onOptionClick: (AppScreen) -> Unit) {
    val context = LocalContext.current
    val dm = context.resources.displayMetrics
    val width = dm.widthPixels
    val height = dm.heightPixels
    val smallerSide = minOf(width, height)
    val isSupported = smallerSide >= 1080
    val supportText = if (isSupported) "Поддерживается" else "Не поддерживается (минимум 1080p)"
    val statusColor = if (isSupported) Color(0xFF4CAF50) else Color(0xFFF44336)

    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val isLandscape = maxWidth > maxHeight
            
            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    options.forEach { option ->
                        MenuCard(
                            option = option, 
                            onClick = { onOptionClick(option.screen) },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MenuCard(option = options[0], onClick = { onOptionClick(options[0].screen) }, modifier = Modifier.weight(1f).fillMaxHeight())
                        MenuCard(option = options[1], onClick = { onOptionClick(options[1].screen) }, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MenuCard(option = options[2], onClick = { onOptionClick(options[2].screen) }, modifier = Modifier.weight(1f).fillMaxHeight())
                        MenuCard(option = options[3], onClick = { onOptionClick(options[3].screen) }, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Разрешение: ${width}x${height} — $supportText",
            color = statusColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp)
        )
    }
}

@Composable
fun MenuCard(option: MenuOption, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = option.icon != Icons.Default.Lock) { onClick() },
        color = option.bgColor,
        border = BorderStroke(1.dp, Color(0xFF333333))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = option.title,
                tint = option.tint,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = option.title,
                color = option.tint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun EyeScreen(
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    reactions: Set<String>,
    onReactionsChange: (Set<String>) -> Unit,
    isDev: Boolean,
    onDevChange: (Boolean) -> Unit,
    tgToken: String,
    onTgTokenChange: (String) -> Unit,
    tgChatId: String,
    onTgChatIdChange: (String) -> Unit,
    discordWebhook: String,
    onDiscordWebhookChange: (String) -> Unit
) {
    val context = LocalContext.current
    var showHint by remember { mutableStateOf(false) }
    var showTgInstructions by remember { mutableStateOf(false) }
    var showDiscordInstructions by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Заголовок удален по просьбе пользователя

        // Switch Поиск врагов
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Поиск врагов", color = Color.White)
                IconButton(onClick = { showHint = !showHint }) {
                    Icon(Icons.Default.Info, "", tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }
            Switch(checked = isEnabled, onCheckedChange = onEnabledChange)
        }

        if (showHint) {
            Text(
                "Бот следит за панелью ЛОКАЛ. Если появится нейтрал или враг — сработает реакция.",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Реакция
        Column {
            Text("Тип реакции:", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            Surface(
                color = Color(0xFF262626),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF333333)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    val availableReactions = listOf("Звук", "Вибрация", "Telegram", "Discord")
                    
                    availableReactions.forEach { r ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newSet = reactions.toMutableSet()
                                    newSet.remove("Ничего")
                                    if (newSet.contains(r)) {
                                        newSet.remove(r)
                                    } else {
                                        newSet.add(r)
                                        if (r == "Звук") {
                                            try {
                                                val mp = MediaPlayer.create(context, R.raw.alarm)
                                                mp.start()
                                                mp.setOnCompletionListener { it.release() }
                                            } catch (e: Exception) { e.printStackTrace() }
                                        }
                                        if (r == "Вибрация") {
                                            try {
                                                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                                                    vibratorManager.defaultVibrator
                                                } else {
                                                    @Suppress("DEPRECATION")
                                                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                                                }
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                                                } else {
                                                    @Suppress("DEPRECATION")
                                                    vibrator.vibrate(100)
                                                }
                                            } catch (e: Exception) { e.printStackTrace() }
                                        }
                                    }
                                    if (newSet.isEmpty()) newSet.add("Ничего")
                                    onReactionsChange(newSet)
                                }
                                .padding(vertical = 0.dp)
                        ) {
                            Checkbox(
                                checked = reactions.contains(r) && !reactions.contains("Ничего"),
                                onCheckedChange = { checked ->
                                    val newSet = reactions.toMutableSet()
                                    newSet.remove("Ничего")
                                    if (checked) {
                                        newSet.add(r)
                                        if (r == "Звук") {
                                            try {
                                                val mp = MediaPlayer.create(context, R.raw.alarm)
                                                mp.start()
                                                mp.setOnCompletionListener { it.release() }
                                            } catch (e: Exception) { e.printStackTrace() }
                                        }
                                        if (r == "Вибрация") {
                                            try {
                                                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                                                    vibratorManager.defaultVibrator
                                                } else {
                                                    @Suppress("DEPRECATION")
                                                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                                                }
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                                                } else {
                                                    @Suppress("DEPRECATION")
                                                    vibrator.vibrate(100)
                                                }
                                            } catch (e: Exception) { e.printStackTrace() }
                                        }
                                    } else {
                                        newSet.remove(r)
                                    }
                                    if (newSet.isEmpty()) newSet.add("Ничего")
                                    onReactionsChange(newSet)
                                }
                            )
                            Text(r, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                        }
                        
                        // Подменю для Telegram
                        if (r == "Telegram" && reactions.contains("Telegram")) {
                            Column(
                                modifier = Modifier
                                    .padding(start = 32.dp, top = 0.dp, bottom = 8.dp, end = 8.dp)
                                    .fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = tgToken,
                                    onValueChange = onTgTokenChange,
                                    label = { Text("API Token", fontSize = 10.sp) },
                                    placeholder = { Text("По умолчанию", fontSize = 10.sp, color = Color.Gray) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = Color(0xFF333333),
                                        focusedBorderColor = Color(0xFF2E7D32)
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                OutlinedTextField(
                                    value = tgChatId,
                                    onValueChange = onTgChatIdChange,
                                    label = { Text("Chat ID", fontSize = 10.sp) },
                                    placeholder = { Text("По умолчанию", fontSize = 10.sp, color = Color.Gray) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = Color(0xFF333333),
                                        focusedBorderColor = Color(0xFF2E7D32)
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Создайте бота в @BotFather. Узнайте свой ID в @userinfobot.",
                                        color = Color.Gray,
                                        fontSize = 9.sp,
                                        lineHeight = 11.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { showTgInstructions = !showTgInstructions },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.HelpOutline, 
                                            contentDescription = "Инструкция",
                                            tint = Color(0xFF2E7D32),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                if (showTgInstructions) {
                                    Surface(
                                        color = Color(0xFF1E1E1E),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                                        border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.5f))
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text("Инструкция по созданию:", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            val steps = listOf(
                                                "1. Найдите @BotFather в Telegram.",
                                                "2. Отправьте команду /newbot.",
                                                "3. Введите название и юзернейм.",
                                                "4. Скопируйте API Token в поле выше.",
                                                "5. Напишите боту любое сообщение.",
                                                "6. Узнайте свой ID в @userinfobot."
                                            )
                                            steps.forEach { step ->
                                                Text(step, color = Color.Gray, fontSize = 9.sp, lineHeight = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Подменю для Discord
                        if (r == "Discord" && reactions.contains("Discord")) {
                            Column(
                                modifier = Modifier
                                    .padding(start = 32.dp, top = 0.dp, bottom = 8.dp, end = 8.dp)
                                    .fillMaxWidth()
                            ) {
                                OutlinedTextField(
                                    value = discordWebhook,
                                    onValueChange = onDiscordWebhookChange,
                                    label = { Text("Discord Webhook URL", fontSize = 10.sp) },
                                    placeholder = { Text("По умолчанию", fontSize = 10.sp, color = Color.Gray) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = Color(0xFF333333),
                                        focusedBorderColor = Color(0xFF5865F2)
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Создайте вебхук в настройках канала Discord.",
                                        color = Color.Gray,
                                        fontSize = 9.sp,
                                        lineHeight = 11.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { showDiscordInstructions = !showDiscordInstructions },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.HelpOutline, 
                                            contentDescription = "Инструкция Discord",
                                            tint = Color(0xFF5865F2),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                if (showDiscordInstructions) {
                                    Surface(
                                        color = Color(0xFF1E1E1E),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                                        border = BorderStroke(1.dp, Color(0xFF5865F2).copy(alpha = 0.5f))
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text("Как создать вебхук:", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            val steps = listOf(
                                                "1. Откройте Настройки сервера -> Интеграция.",
                                                "2. Нажмите 'Вебхуки' -> 'Новый вебхук'.",
                                                "3. Выберите канал и нажмите 'Копировать URL'.",
                                                "4. Вставьте URL в поле выше."
                                            )
                                            steps.forEach { step ->
                                                Text(step, color = Color.Gray, fontSize = 9.sp, lineHeight = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onReactionsChange(setOf("Ничего"))
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = reactions.contains("Ничего") || reactions.isEmpty(),
                            onCheckedChange = { checked ->
                                if (checked) {
                                    onReactionsChange(setOf("Ничего"))
                                } else {
                                    onReactionsChange(setOf("Звук")) // Default if unchecked
                                }
                            }
                        )
                        Text("Ничего", color = Color.White, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        // Режим разработчика
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Режим разработчика", color = Color.White)
            Checkbox(checked = isDev, onCheckedChange = onDevChange)
        }
    }
}

@Composable
fun DevelopmentScreen(name: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Construction, "", tint = Color.Gray, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Раздел $name", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("В разработке...", color = Color.Gray)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MyApplicationTheme {
        MainScreen(onStartClick = { _, _, _, _, _, _ -> }, onAccessibilityClick = {})
    }
}