package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.myapplication.capture.ScreenCapturer
import com.example.myapplication.detection.EnemyDetector
import com.example.myapplication.engine.BotEngine
import com.example.myapplication.notifications.ReactionManager
import com.example.myapplication.notifications.discord.DiscordNotifier
import com.example.myapplication.notifications.telegram.TelegramNotifier
import com.example.myapplication.ui.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FloatingWindowService : Service() {

    private lateinit var overlayController: OverlayController
    private lateinit var screenCapturer: ScreenCapturer
    private lateinit var enemyDetector: EnemyDetector
    private lateinit var botEngine: BotEngine
    
    private var telegramNotifier: TelegramNotifier? = null
    private var discordNotifier: DiscordNotifier? = null
    private lateinit var reactionManager: ReactionManager

    // Screen Metrics
    private var isTabletResolution = false
    private var currentSmallerSide = 1080
    private var currentScaleFactor = 1.0

    // Settings
    private var selectedReactions = setOf("Звук")
    private var isDevMode = false
    private var tgTokenOverride: String? = null
    private var tgChatIdOverride: String? = null
    private var discordWebhookUrlOverride: String? = null

    private val logMessages = mutableListOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (isTablet, scale) = determineScreenMetrics(windowManager)

        initComponents(windowManager, isTablet, scale)
        
        loadTargetBitmap(isTablet)
        
        overlayController.showMainOverlay()
    }

    private fun determineScreenMetrics(windowManager: WindowManager): Pair<Boolean, Double> {
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            w = bounds.width()
            h = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val realMetrics = DisplayMetrics()
            display.getRealMetrics(realMetrics)
            w = realMetrics.widthPixels
            h = realMetrics.heightPixels
        }
        
        val smallerSide = minOf(w, h)
        currentSmallerSide = smallerSide
        currentScaleFactor = smallerSide / 1080.0
        isTabletResolution = (smallerSide >= 1500)
        
        addLog("СИСТЕМА: Экран ${w}x${h}, масштаб=${String.format("%.4f", currentScaleFactor)}")
        return Pair(isTabletResolution, currentScaleFactor)
    }

    private fun initComponents(windowManager: WindowManager, isTablet: Boolean, scale: Double) {
        // UI Controller
        overlayController = OverlayController(this, windowManager, object : OverlayController.Callbacks {
            override fun onStartStopClicked() {
                if (botEngine.isRunning()) {
                    botEngine.stop()
                    overlayController.updateStartButtonIcon(false)
                } else {
                    if (botEngine.targetBitmap == null) {
                        addLog("ОШИБКА: Шаблон панели не загружен")
                        return
                    }
                    botEngine.start()
                    overlayController.updateStartButtonIcon(true)
                }
            }
            override fun onCloseClicked() {
                stopSelf()
            }
            override fun onMagnifierAimChanged(rawX: Int, rawY: Int) {
                botEngine.setCalibrationFromRaw(rawX, rawY, currentSmallerSide, currentScaleFactor)
                overlayController.calibOffsetX = botEngine.currentCalibOffsetX
                overlayController.calibOffsetY = botEngine.currentCalibOffsetY
            }
            override fun onMagnifierRefreshRequested() {
                botEngine.currentCalibOffsetX = overlayController.calibOffsetX
                botEngine.currentCalibOffsetY = overlayController.calibOffsetY
                botEngine.requestMagnifierRefresh()
            }
            override fun getLatestLogText(): String {
                synchronized(logMessages) {
                    return logMessages.joinToString("\n")
                }
            }
        })

        // Capture logic
        screenCapturer = ScreenCapturer(this, object : ScreenCapturer.Callbacks {
            override fun onCaptureStopped() {
                botEngine.stop()
                CoroutineScope(Dispatchers.Main).launch {
                    overlayController.updateStartButtonIcon(false)
                }
            }
            override fun onError(message: String) = addLog(message)
            override fun onLogRequested(message: String) = addLog(message)
        })

        // Detection logic
        enemyDetector = EnemyDetector(currentSmallerSide, scale, object : EnemyDetector.Callbacks {
            override fun onLogRequested(message: String) = addLog(message)
            override fun onTargetCalibrated(x: Int, y: Int, color: Int, screen: Bitmap) {
                try {
                    val zoom = overlayController.zoomFactor
                    val targetPx = 300
                    val cropSize = (120 / zoom).coerceAtLeast(2)
                    
                    val left = (x - cropSize / 2).coerceIn(0, screen.width - cropSize)
                    val top = (y - cropSize / 2).coerceIn(0, screen.height - cropSize)
                    
                    if (left < 0 || top < 0 || left + cropSize > screen.width || top + cropSize > screen.height) {
                        return
                    }
                    
                    val crop = Bitmap.createBitmap(screen, left, top, cropSize, cropSize)
                    val scaled = Bitmap.createScaledBitmap(crop, targetPx, targetPx, false).copy(Bitmap.Config.ARGB_8888, true)
                    
                    val canvas = android.graphics.Canvas(scaled)
                    val paint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.GREEN
                        strokeWidth = 2f
                    }
                    val mid = targetPx / 2f
                    canvas.drawLine(mid - 15, mid, mid + 15, mid, paint)
                    canvas.drawLine(mid, mid - 15, mid, mid + 15, paint)
                    
                    crop.recycle()
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        overlayController.updateMagnifierUI(scaled, x, y, color)
                    }
                } catch (e: Exception) {
                    addLog("Ошибка лупы: ${e.message}")
                }
            }
        })

        // Reactions
        reactionManager = ReactionManager(this, windowManager) { msg -> addLog(msg) }

        // Core Engine
        botEngine = BotEngine(screenCapturer, enemyDetector, object : BotEngine.Callbacks {
            override fun onLogRequested(message: String) = addLog(message)
            override fun onEnemyDetected(hasEnemy: Boolean, hasNeutral: Boolean) {
                if (selectedReactions.contains("Telegram")) {
                    telegramNotifier?.notifyState(hasEnemy, hasNeutral)
                }
                if (selectedReactions.contains("Discord")) {
                    discordNotifier?.notifyState(hasEnemy, hasNeutral)
                }
            }
            override fun onActivityStarted() {
                CoroutineScope(Dispatchers.Main).launch {
                    reactionManager.triggerReaction(selectedReactions)
                }
            }
            override fun onEngineStopped() {
                overlayController.updateStartButtonIcon(false)
            }
        })
    }

    private fun loadTargetBitmap(isTablet: Boolean) {
        try {
            val smallerSide = (currentScaleFactor * 1080).toInt()
            
            val resourceId = when (smallerSide) {
                1840 -> R.drawable.panel_1840
                1440 -> R.drawable.panel_1440
                1080 -> R.drawable.panel_1080
                else -> {
                    addLog("ОШИБКА: Разрешение экрана ($smallerSide) не поддерживается!")
                    return
                }
            }
            
            val options = BitmapFactory.Options().apply { inScaled = false }
            botEngine.targetBitmap = BitmapFactory.decodeResource(resources, resourceId, options)
            addLog("СИСТЕМА: Загружен шаблон [${resources.getResourceEntryName(resourceId)}]")
            
            // Set initial calibration
            when (smallerSide) {
                1840 -> {
                    botEngine.currentCalibOffsetX = 461
                    botEngine.currentCalibOffsetY = 76
                    overlayController.calibOffsetX = 461
                    overlayController.calibOffsetY = 76
                }
                1440 -> {
                    botEngine.currentCalibOffsetX = 392
                    botEngine.currentCalibOffsetY = 68
                    overlayController.calibOffsetX = 392
                    overlayController.calibOffsetY = 68
                }
                1080 -> {
                    botEngine.currentCalibOffsetX = 294
                    botEngine.currentCalibOffsetY = 51
                    overlayController.calibOffsetX = 294
                    overlayController.calibOffsetY = 51
                }
            }
        } catch (e: Exception) {
            addLog("ОШИБКА загрузки шаблона: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.hasExtra("SETTING_ENEMY_SEARCH")) {
                botEngine.isEnemySearchEnabled = intent.getBooleanExtra("SETTING_ENEMY_SEARCH", true)
                selectedReactions = intent.getStringArrayListExtra("SETTING_REACTIONS")?.toSet() ?: setOf("Звук")
                isDevMode = intent.getBooleanExtra("SETTING_DEV_MODE", false)
                
                tgTokenOverride = intent.getStringExtra("SETTING_TG_TOKEN")
                tgChatIdOverride = intent.getStringExtra("SETTING_TG_CHAT_ID")
                discordWebhookUrlOverride = intent.getStringExtra("SETTING_DISCORD_WEBHOOK")
                
                // Initialize notifiers if enabled
                val tgToken = if (!tgTokenOverride.isNullOrBlank()) tgTokenOverride!! else getString(R.string.tg_bot_token)
                val tgChatId = if (!tgChatIdOverride.isNullOrBlank()) tgChatIdOverride!! else getString(R.string.tg_chat_id)
                val discordUrl = if (!discordWebhookUrlOverride.isNullOrBlank()) discordWebhookUrlOverride!! else "https://discord.com/api/webhooks/1497310228054675549/l1Yi53DOt95SW787TrfN77DsYsT011xUJjUOKGhbaTti2cND4TU6Zj-nm11qUTSwUhxf"
                
                telegramNotifier = TelegramNotifier(tgToken, tgChatId) { msg -> addLog(msg) }
                discordNotifier = DiscordNotifier(discordUrl) { msg -> addLog(msg) }
                
                overlayController.setDevMode(isDevMode)
                addLog("Настройки: Поиск=${botEngine.isEnemySearchEnabled}, Dev=$isDevMode")
            }

            if (intent.hasExtra("SCREEN_CAPTURE_RESULT_CODE")) {
                val resultCode = intent.getIntExtra("SCREEN_CAPTURE_RESULT_CODE", 0)
                val data = intent.getParcelableExtra<Intent>("SCREEN_CAPTURE_INTENT")
                if (data != null) {
                    screenCapturer.init(resultCode, data)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logLine = "[$time] $message"
        
        Log.d("AutoClicker", logLine)

        synchronized(logMessages) {
            logMessages.add(0, logLine)
            if (logMessages.size > 100) logMessages.removeAt(logMessages.size - 1)
            
            CoroutineScope(Dispatchers.Main).launch {
                overlayController.updateLogs(logMessages.joinToString("\n"))
            }
        }
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "floating_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Автокликер Сервис", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Автокликер")
            .setContentText("Сервис запущен")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        addLog("СЕРВИС: Уничтожение сервиса (onDestroy)")
        
        botEngine.stop()
        reactionManager.stopAlarm()
        overlayController.removeAllOverlays()
        screenCapturer.release()
    }
}
