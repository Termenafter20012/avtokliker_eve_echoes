package com.example.myapplication

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.util.Log
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.content.ClipboardManager
import android.content.ClipData
import android.media.MediaPlayer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.MultipartBody
import java.io.ByteArrayOutputStream

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var btnStart: ImageButton
    private lateinit var btnLogs: ImageButton
    private lateinit var btnClose: ImageButton

    // Переменные для окна логов и лупы
    private var logWindowView: View? = null
    private var magnifierWindowView: View? = null
    private var tvLogs: android.widget.TextView? = null
    private val logMessages = mutableListOf<String>()

    // Инструменты для записи экрана
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any() // Блокировка для синхронизации доступа к скриншоту
    private val mainHandler = Handler(Looper.getMainLooper()) // Один общий Handler для UI-операций
    
    // UI элементы
    private var ivZoom: android.widget.ImageView? = null
    private var tvCoords: android.widget.TextView? = null
    private var tvColorHex: android.widget.TextView? = null
    private var lastTargetColor: Int = 0
    
    // Оптимизация логов
    private var isLogUpdatePending = false
    private val logUpdateRunnable = Runnable {
        synchronized(logMessages) {
            val fullText = logMessages.joinToString("\n")
            mainHandler.post {
                tvLogs?.text = fullText
                isLogUpdatePending = false
            }
        }
    }
    
    // Переменные для логики поиска и автоклика
    private var isSearching = false
    private var searchJob: Job? = null
    private var targetBitmap: Bitmap? = null 
    
    // Запоминаем последнюю найденную позицию панели для прицеливания
    private var lastPanelX = 0
    private var lastPanelY = 0

    // Параметры для функции "Поиск врагов"
    private val colorNetral = 10000000
    private val colorRed = 5700351
    private var timeStartVrag: Long = 0

    // Цветовые диапазоны
    private val digitRange = 6000000..16777215 // Сделал чуть шире для планшета
    private val bgRange = 0..5000000
    private val controlRange = 5000000..13000000 // Включаем ваш цвет 7.8 млн
    
    // Данные Telegram (берутся из ресурсов для безопасности)
    private var tgTokenOverride: String? = null
    private var tgChatIdOverride: String? = null
    private var discordWebhookUrlOverride: String? = null
    
    private val DEFAULT_DISCORD_WEBHOOK = "https://discord.com/api/webhooks/1497310228054675549/l1Yi53DOt95SW787TrfN77DsYsT011xUJjUOKGhbaTti2cND4TU6Zj-nm11qUTSwUhxf"
    
    private val tgToken: String get() = if (!tgTokenOverride.isNullOrBlank()) tgTokenOverride!! else getString(R.string.tg_bot_token)
    private val tgChatId: String get() = if (!tgChatIdOverride.isNullOrBlank()) tgChatIdOverride!! else getString(R.string.tg_chat_id)
    private val discordWebhookUrl: String get() = if (!discordWebhookUrlOverride.isNullOrBlank()) discordWebhookUrlOverride!! else DEFAULT_DISCORD_WEBHOOK
    
    // Состояние для уведомлений (чтобы не спамить)
    private var lastEnemyDetected = false
    private var lastNeutralDetected = false

    // Координаты калибровки (смещения относительно угла панели)
    private var calibOffsetX = 294
    private var calibOffsetY = 51
    private var scaleFactor = 1.0 // Коэффициент масштабирования для разных разрешений

    // Настройки из главного окна
    private var isEnemySearchEnabledBySettings = true
    private var selectedReactions = setOf("Звук")
    private var isDevMode = false
    
    // Состояние для предотвращения спама звуком
    private var wasEnemyPresentLastTime = false
    private var isTabletResolution = false
    
    private var alarmPlayer: MediaPlayer? = null
    private var alarmOverlay: View? = null
    private var isAlarmActive = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        setupFloatingWindow()
        
        // Пытаемся загрузить вашу картинку (target_image) из папки ресурсов (drawable)
        /*
        try {
            val options = BitmapFactory.Options()
            options.inScaled = false // ВАЖНО: Отключаем автоматическое масштабирование Android, иначе картинка искажается
            targetBitmap = BitmapFactory.decodeResource(resources, R.drawable.target_image, options)
        } catch (e: Exception) {
            Log.e("AutoClicker", "Не удалось загрузить target_image.png")
        }
        */

        // ОПРЕДЕЛЕНИЕ МАСШТАБА И ЗАГРУЗКА КАРТИНКИ
        try {
            val metrics = resources.displayMetrics
            // Используем реальные метрики (как в MainActivity)
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
            scaleFactor = smallerSide / 1080.0
            isTabletResolution = (smallerSide >= 1500)
            
            // ПРИМЕНЯЕМ ВАШУ КАЛИБРОВКУ ДЛЯ ПЛАНШЕТА (1840)
            if (isTabletResolution) {
                calibOffsetX = 461 // Будем смотреть на нейтралов в лупу по умолчанию
                calibOffsetY = 76
                addLog("СИСТЕМА: Активирован профиль ПЛАНШЕТ (точные координаты)")
            } else {
                calibOffsetX = 294
                calibOffsetY = 51
            }
            
            val options = BitmapFactory.Options()
            options.inScaled = false
            
            // Выбираем подходящий ресурс
            val resourceId = if (smallerSide >= 1500) {
                // Если экран большой (планшет), ищем панель 1840
                // Используем reflection или getIdentifier чтобы не упасть если файла нет
                val id = resources.getIdentifier("panel_1840", "drawable", packageName)
                if (id != 0) id else R.drawable.panel_1080
            } else {
                R.drawable.panel_1080
            }
            
            targetBitmap = BitmapFactory.decodeResource(resources, resourceId, options)
            val resName = resources.getResourceEntryName(resourceId)
            addLog("СИСТЕМА: Экран ${w}x${h}, масштаб=${String.format("%.4f", scaleFactor)}")
            addLog("СИСТЕМА: Загружен шаблон [$resName]")
        } catch (e: Exception) {
            addLog("ОШИБКА инициализации: ${e.message}")
        }
    }


    // Сюда прилетает ответ из MainActivity с разрешением на запись экрана
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.hasExtra("SETTING_ENEMY_SEARCH")) {
                isEnemySearchEnabledBySettings = intent.getBooleanExtra("SETTING_ENEMY_SEARCH", true)
                val reactionsList = intent.getStringArrayListExtra("SETTING_REACTIONS")
                selectedReactions = reactionsList?.toSet() ?: setOf("Звук")
                tgTokenOverride = intent.getStringExtra("SETTING_TG_TOKEN")
                tgChatIdOverride = intent.getStringExtra("SETTING_TG_CHAT_ID")
                discordWebhookUrlOverride = intent.getStringExtra("SETTING_DISCORD_WEBHOOK")
                isDevMode = intent.getBooleanExtra("SETTING_DEV_MODE", false)
                addLog("Настройки: Поиск=$isEnemySearchEnabledBySettings, Реакции=$selectedReactions, Dev=$isDevMode")
                
                // Если сервис уже запущен и мы меняем настройки, обновляем видимость
                if (::floatingView.isInitialized) {
                    updateDevUiVisibility()
                }
            }

            if (intent.hasExtra("SCREEN_CAPTURE_RESULT_CODE")) {
                val resultCode = intent.getIntExtra("SCREEN_CAPTURE_RESULT_CODE", 0)
                val data = intent.getParcelableExtra<Intent>("SCREEN_CAPTURE_INTENT")
                if (data != null) {
                    // Если разрешение есть - инициализируем запись экрана
                    initMediaProjection(resultCode, data)
                }
            }
        }
        return START_NOT_STICKY
    }

    // Подготовка механизма записи экрана
    private fun initMediaProjection(resultCode: Int, data: Intent) {
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        
        // В Android 14+ обязательно нужно регистрировать Callback перед созданием VirtualDisplay
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                addLog("СИСТЕМА: Запись экрана остановлена (MediaProjection Stop)")
                stopSearch()
            }
        }, Handler(Looper.getMainLooper()))

        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        // Освобождаем старые ресурсы, если они были
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        
        val metrics = resources.displayMetrics
        // Принудительно выбираем альбомную ориентацию (Ширина > Высота)
        val width = maxOf(metrics.widthPixels, metrics.heightPixels)
        val height = minOf(metrics.widthPixels, metrics.heightPixels)
        val density = metrics.densityDpi

        if (mediaProjection == null) {
            addLog("ОШИБКА: MediaProjection не инициализирован")
            return
        }

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
            if (image != null) {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * image.width

                    val bitmap = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                    bitmap.recycle()

                    synchronized(bitmapLock) {
                        val oldBitmap = latestBitmap
                        latestBitmap = croppedBitmap
                        oldBitmap?.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("AutoClicker", "Ошибка обработки кадра: ${e.message}")
                } finally {
                    image.close()
                }
            }
        }, mainHandler)

        try {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            addLog("СИСТЕМА: Захват экрана настроен ($width x $height)")
        } catch (e: Exception) {
            addLog("ОШИБКА создания VirtualDisplay: ${e.message}")
        }
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "floating_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Автокликер Сервис", NotificationManager.IMPORTANCE_LOW
            )
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

        // В новых версиях Android для записи экрана обязательно нужно указывать тип сервиса при запуске
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_window, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = 100

        windowManager.addView(floatingView, params)
        
        updateDevUiVisibility()

        val rootContainer = floatingView.findViewById<View>(R.id.root_container)
        btnStart = floatingView.findViewById(R.id.btn_start)
        btnLogs  = floatingView.findViewById(R.id.btn_logs)
        btnClose = floatingView.findViewById(R.id.btn_close)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoved = false

        // Общий слушатель касаний: тащит окно или выполняет клик
        val createTouchListener = { action: () -> Unit ->
            View.OnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).start()
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val diffX = (event.rawX - initialTouchX).toInt()
                        val diffY = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(diffX) > 10 || Math.abs(diffY) > 10) isMoved = true
                        params.x = initialX + diffX
                        params.y = initialY + diffY
                        windowManager.updateViewLayout(floatingView, params)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        if (!isMoved && event.action == MotionEvent.ACTION_UP) action()
                        true
                    }
                    else -> false
                }
            }
        }

        rootContainer.setOnTouchListener(createTouchListener { })
        
        btnStart.setOnTouchListener(createTouchListener {
            if (isSearching) stopSearch() else startSearch()
        })
        btnLogs.setOnTouchListener(createTouchListener  { toggleLogWindow() })
        
        val btnMagnifier = floatingView.findViewById<ImageButton>(R.id.btn_magnifier)
        btnMagnifier.setOnTouchListener(createTouchListener {
            toggleMagnifierWindow()
        })
        
        btnClose.setOnTouchListener(createTouchListener { stopSelf() })
    }

    private fun updateDevUiVisibility() {
        if (!::floatingView.isInitialized) return
        val btnMagnifier = floatingView.findViewById<View>(R.id.btn_magnifier)
        btnMagnifier?.visibility = if (isDevMode) View.VISIBLE else View.GONE
        
        if (isDevMode && magnifierWindowView == null) {
            showMagnifierWindow()
        } else if (!isDevMode && magnifierWindowView != null) {
            closeMagnifierWindow()
        }
    }

    // --- ЛОГИКА ОКНА ЛОГОВ ---
    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logLine = "[$time] $message"
        
        // Дублируем в системный лог (Logcat), чтобы видеть даже если окно сломалось
        Log.d("AutoClicker", logLine)

        synchronized(logMessages) {
            logMessages.add(0, logLine) // Добавляем в начало списка
            if (logMessages.size > 100) {
                logMessages.removeAt(logMessages.size - 1) // Удаляем самый старый элемент с конца
            }
        }

        // Если окно открыто, планируем обновление
        if (tvLogs != null && !isLogUpdatePending) {
            isLogUpdatePending = true
            mainHandler.postDelayed(logUpdateRunnable, 100) // 0.1 сек задержка
        }
    }

    private fun toggleLogWindow() {
        if (logWindowView != null) {
            closeLogWindow()
        } else {
            showLogWindow()
        }
    }

    private fun showLogWindow() {
        logWindowView = LayoutInflater.from(this).inflate(R.layout.layout_log_window, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val logParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        logParams.gravity = Gravity.TOP or Gravity.START
        logParams.x = 0
        logParams.y = 200

        windowManager.addView(logWindowView, logParams)

        tvLogs = logWindowView?.findViewById(R.id.tv_logs)
        tvLogs?.text = logMessages.joinToString("\n")

        // Кнопка закрытия окна логов
        val btnCloseLogs = logWindowView?.findViewById<Button>(R.id.btn_close_logs)
        btnCloseLogs?.setOnClickListener { closeLogWindow() }

        // Кнопка копирования логов
        val btnCopyLogs = logWindowView?.findViewById<Button>(R.id.btn_copy_logs)
        btnCopyLogs?.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val fullLogs = logMessages.joinToString("\n")
            val clip = ClipData.newPlainText("AutoClicker Logs", fullLogs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Логи скопированы в буфер обмена", Toast.LENGTH_SHORT).show()
        }

        // --- ПЕРЕТАСКИВАНИЕ ОКНА ЛОГОВ ---
        // Находим заголовок окна логов, за который можно тащить
        val logHeader = logWindowView?.findViewById<View>(R.id.log_header)
        var logInitX = 0; var logInitY = 0
        var logInitTouchX = 0f; var logInitTouchY = 0f

        logHeader?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    logInitX = logParams.x; logInitY = logParams.y
                    logInitTouchX = event.rawX; logInitTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    logParams.x = logInitX + (event.rawX - logInitTouchX).toInt()
                    logParams.y = logInitY + (event.rawY - logInitTouchY).toInt()
                    windowManager.updateViewLayout(logWindowView, logParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun closeLogWindow() {
        if (logWindowView != null) {
            windowManager.removeView(logWindowView)
            logWindowView = null
            tvLogs = null
        }
    }

    // Запуск бесконечного цикла поиска картинки
    private fun startSearch() {
        if (isSearching) return // Уже ищем
        
        // Сбрасываем состояние для нового поиска
        wasEnemyPresentLastTime = false
        ImageMatcher.releaseCache()
        
        if (targetBitmap == null) {
            val msg = "ОШИБКА: Картинка panel_1080.png не найдена!"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            addLog(msg)
            return
        }
        if (AutoClickerAccessibilityService.instance == null) {
            val msg = "ОШИБКА: Включите Автокликер в Спец. возможностях!"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            addLog(msg)
            return
        }

        isSearching = true
        // Меняем иконку на красный квадрат (стоп)
        btnStart.setImageResource(R.drawable.ic_stop)
        Toast.makeText(this, "Поиск запущен! Ищем раз в секунду.", Toast.LENGTH_SHORT).show()
        addLog("=== ПОИСК ЗАПУЩЕН ===")

        // Запускаем фоновую задачу (Корутину)
        searchJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                while (isSearching) {
                    val screenBitmap = captureScreen()
                    
                    if (screenBitmap != null) {
                        if (isEnemySearchEnabledBySettings) {
                            // ОСНОВНОЙ РЕЖИМ: Поиск панели и врагов
                            performMasterSearch(screenBitmap)
                        } else {
                            // ПУСТОЙ ЦИКЛ
                        }
                        screenBitmap.recycle()
                    } else {
                        addLog("Диагностика: скриншот не получен (ждем кадр...)")
                    }
                    
                    // Ждем 1 секунду перед следующим поиском
                    delay(1000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Игнорируем обычную остановку корутины
            } catch (e: Exception) {
                addLog("КРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
            } finally {
                addLog("Цикл поиска завершен.")
            }
        }
    }

    private fun performMasterSearch(screen: Bitmap): Boolean {
        if (targetBitmap == null) {
            addLog("ОШИБКА: Картинка panel_1080 не загружена!")
            return false
        }
        
        // 1. Поиск панели panel_1080
        val foundResult = try {
            ImageMatcher.findTemplate(screen, targetBitmap!!)
        } catch (e: Exception) {
            addLog("ОШИБКА В ImageMatcher: ${e.message}")
            null
        }
        
        if (foundResult != null) {
            val (x, y, score) = foundResult
            val scorePercent = (score * 100).toInt()
            
            if (x != -1) {
                lastPanelX = x
                lastPanelY = y
                addLog("Панель: ($x, $y). Сходство: $scorePercent%")
                // 2. Если нашли панель, проверяем врагов
                return checkEnemy(screen, Pair(x, y))
            } else {
                var msg = "Панель не найдена (Сходство: $scorePercent%)"
                if (scorePercent < 10) msg += " - Проверьте ориентацию экрана!"
                addLog(msg)
            }
        } else {
            addLog("Панель не найдена (общая ошибка)")
        }
        return false
    }

    private fun stopSearch() {
        if (!isSearching) return
        isSearching = false
        searchJob?.cancel()
        // Возвращаем иконку зелёного треугольника (старт)
        btnStart.setImageResource(R.drawable.ic_play)
        Toast.makeText(this, "Поиск остановлен", Toast.LENGTH_SHORT).show()
        addLog("=== ПОИСК ОСТАНОВЛЕН ===")
        
        // Очищаем скриншот чтобы не искать по старым данным при перезапуске
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }

    // --- ФУНКЦИЯ ПОИСК ВРАГОВ (vrag_f) ---
    private fun checkEnemy(screen: Bitmap, p: Pair<Int, Int>): Boolean {
        
        // Вспомогательная функция для получения цвета без альфа-канала
        fun getColor(offX: Int, offY: Int): Int {
            // Если планшет - берем координаты КАК ЕСТЬ (они уже измерены для него)
            // Если телефон - используем масштаб
            val finalOffX = if (isTabletResolution) offX else (offX * scaleFactor).toInt()
            val finalOffY = if (isTabletResolution) offY else (offY * scaleFactor).toInt()
            
            val x = p.first + finalOffX
            val y = p.second + finalOffY
            if (x < 0 || x >= screen.width || y < 0 || y >= screen.height) return 0
            return screen.getPixel(x, y) and 0xFFFFFF
        }

        // --- ТОЧКИ ДЛЯ ПРОВЕРКИ ---
        val ctrlX = if (isTabletResolution) 224 else 157
        val ctrlY = if (isTabletResolution) 52 else 48
        
        val neutPoints = if (isTabletResolution) {
            listOf(Pair(461, 76), Pair(461, 56), Pair(446, 56), Pair(444, 74))
        } else {
            listOf(Pair(293, 48), Pair(293, 37), Pair(305, 37), Pair(305, 49))
        }

        val minusPoints = if (isTabletResolution) {
            listOf(Pair(276, 76), Pair(276, 56), Pair(258, 56), Pair(258, 76))
        } else {
            listOf(Pair(169, 49), Pair(169, 38), Pair(169, 48), Pair(180, 48))
        }

        // 1. Проверка контрольной точки
        val controlColor = getColor(ctrlX, ctrlY)
        val isControlOk = controlColor in controlRange
        
        if (!isControlOk) {
            val finalX = p.first + (if (isTabletResolution) ctrlX else (ctrlX * scaleFactor).toInt())
            val finalY = p.second + (if (isTabletResolution) ctrlY else (ctrlY * scaleFactor).toInt())
            addLog("Контроль смещен! ($finalX,$finalY): $controlColor")
        }

        // 2. Проверка Нейтралов на 0
        val isNeutZero = isControlOk && neutPoints.all { pt ->
            val c = getColor(pt.first, pt.second)
            c in digitRange && c !in bgRange
        }

        // 3. Проверка Минусов на 0
        val isMinusZero = isControlOk && minusPoints.all { pt ->
            val c = getColor(pt.first, pt.second)
            c in digitRange && c !in bgRange
        }

        // Для отладки (лупа смотрит на точку, которую вы настраиваете кнопками)
        val finalDebugX = if (isTabletResolution) calibOffsetX else (calibOffsetX * scaleFactor).toInt()
        val finalDebugY = if (isTabletResolution) calibOffsetY else (calibOffsetY * scaleFactor).toInt()
        val targetX = p.first + finalDebugX
        val targetY = p.second + finalDebugY
        
        updateMagnifier(screen, targetX, targetY)
        
        val cPixel = screen.getPixel(targetX, targetY) and 0xFFFFFF
        addLog("Цвет в ($finalDebugX,$finalDebugY): $cPixel")

        // Лупа всегда показывает только текущую точку калибровки
        updateMagnifier(screen, targetX, targetY)

        if (isControlOk) {
            val hasNeutral = !isNeutZero
            val hasEnemy = !isMinusZero

            // Умные уведомления (без спама)
            handleTelegramAlerts(hasEnemy, hasNeutral)
            handleDiscordAlerts(hasEnemy, hasNeutral)
            
            // ОБНОВЛЯЕМ СОСТОЯНИЕ (только здесь, один раз для всех алертов)
            lastEnemyDetected = hasEnemy
            lastNeutralDetected = hasNeutral

            val isEnemyCurrentlyPresent = hasEnemy || hasNeutral

            if (isEnemyCurrentlyPresent) {
                // Звук и вибрация срабатывают только если КТО-ТО ПОЯВИЛСЯ (в прошлый раз его не было)
                if (!wasEnemyPresentLastTime) {
                    addLog("!!! ОБНАРУЖЕНА АКТИВНОСТЬ !!!")
                    timeStartVrag = System.currentTimeMillis()
                    triggerReaction()
                }
            } else {
                // Если врагов нет, выводим статус и сбрасываем флаг для следующего появления
                if (wasEnemyPresentLastTime) {
                    addLog("Чисто: враги покинули локацию")
                }
                addLog("Врагов нет (0/0)")
            }
            
            // Запоминаем текущее состояние для следующего цикла
            wasEnemyPresentLastTime = isEnemyCurrentlyPresent
            
            return isEnemyCurrentlyPresent
        }

        return false
    }

    // Обычная лупа с перекрестием
    private fun updateMagnifier(screen: Bitmap, centerX: Int, centerY: Int) {
        val size = 12 
        val half = size / 2
        
        val left = (centerX - half).coerceIn(0, screen.width - size)
        val top = (centerY - half).coerceIn(0, screen.height - size)
        
        try {
            val crop = Bitmap.createBitmap(screen, left, top, size, size)
            val scaled = Bitmap.createScaledBitmap(crop, size * 10, size * 10, false).copy(Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(scaled)
            
            // Рисуем ПЕРЕКРЕСТИЕ в центре
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.GREEN
                strokeWidth = 2f
            }
            val mid = (size * 10) / 2f
            canvas.drawLine(mid - 15, mid, mid + 15, mid, paint)
            canvas.drawLine(mid, mid - 15, mid, mid + 15, paint)
            
            // Сохраняем цвет центрального пикселя
            lastTargetColor = screen.getPixel(centerX, centerY) and 0xFFFFFF

            mainHandler.post {
                ivZoom?.setImageBitmap(scaled)
                tvCoords?.text = "X: $centerX, Y: $centerY"
                tvColorHex?.text = "HEX: #${String.format("%06X", lastTargetColor)}"
                crop.recycle()
            }
        } catch (e: Exception) {
            Log.e("AutoClicker", "Ошибка лупы: ${e.message}")
        }
    }

    // --- ЛОГИКА ОКНА ЛУПЫ ---
    private fun toggleMagnifierWindow() {
        if (magnifierWindowView != null) closeMagnifierWindow() else showMagnifierWindow()
    }

    private fun showMagnifierWindow() {
        magnifierWindowView = LayoutInflater.from(this).inflate(R.layout.layout_magnifier_window, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val magParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        magParams.gravity = Gravity.TOP or Gravity.START
        magParams.x = 100
        magParams.y = 300

        windowManager.addView(magnifierWindowView, magParams)

        ivZoom = magnifierWindowView?.findViewById(R.id.iv_zoom_window)
        tvCoords = magnifierWindowView?.findViewById(R.id.tv_coords)
        tvColorHex = magnifierWindowView?.findViewById(R.id.tv_color_hex)

        // Кнопки смещения
        magnifierWindowView?.findViewById<ImageButton>(R.id.btn_up_mag)?.setOnClickListener { calibOffsetY--; refreshMagnifierFromCache() }
        magnifierWindowView?.findViewById<ImageButton>(R.id.btn_down_mag)?.setOnClickListener { calibOffsetY++; refreshMagnifierFromCache() }
        magnifierWindowView?.findViewById<ImageButton>(R.id.btn_left_mag)?.setOnClickListener { calibOffsetX--; refreshMagnifierFromCache() }
        magnifierWindowView?.findViewById<ImageButton>(R.id.btn_right_mag)?.setOnClickListener { calibOffsetX++; refreshMagnifierFromCache() }

        // Кнопка COPY
        magnifierWindowView?.findViewById<Button>(R.id.btn_copy_mag)?.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = "X: $calibOffsetX, Y: $calibOffsetY, Color: #${String.format("%06X", lastTargetColor)}"
            val clip = ClipData.newPlainText("Pixel Info", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Данные скопированы", Toast.LENGTH_SHORT).show()
        }

        // --- ЛОГИКА ПРИЦЕЛИВАНИЯ (btn_aim) ---
        val btnAim = magnifierWindowView?.findViewById<View>(R.id.btn_aim)
        btnAim?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {
                    val rawX = event.rawX.toInt()
                    val rawY = event.rawY.toInt()

                    // Рассчитываем смещение относительно панели (если она найдена)
                    // Если панель не найдена, lastPanelX/Y будут 0, и мы получим абсолютные координаты
                    val newOffX = if (isTabletResolution) (rawX - lastPanelX) else ((rawX - lastPanelX) / scaleFactor).toInt()
                    val newOffY = if (isTabletResolution) (rawY - lastPanelY) else ((rawY - lastPanelY) / scaleFactor).toInt()
                    
                    calibOffsetX = newOffX
                    calibOffsetY = newOffY

                    // МГНОВЕННОЕ ОБНОВЛЕНИЕ ЛУПЫ
                    synchronized(bitmapLock) {
                        latestBitmap?.let { bmp ->
                            if (!bmp.isRecycled) {
                                updateMagnifier(bmp, rawX, rawY)
                            }
                        }
                    }
                    true
                }
                else -> true
            }
        }

        // Перетаскивание окна лупы за специальный хэндл
        val dragHandle = magnifierWindowView?.findViewById<View>(R.id.mag_drag_handle)
        var mInitX = 0; var mInitY = 0
        var mTouchX = 0f; var mTouchY = 0f

        dragHandle?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mInitX = magParams.x; mInitY = magParams.y
                    mTouchX = event.rawX; mTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    magParams.x = mInitX + (event.rawX - mTouchX).toInt()
                    magParams.y = mInitY + (event.rawY - mTouchY).toInt()
                    windowManager.updateViewLayout(magnifierWindowView, magParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun refreshMagnifierFromCache() {
        synchronized(bitmapLock) {
            latestBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    // Используем текущую калибровку для перерисовки
                    val finalX = if (isTabletResolution) (lastPanelX + calibOffsetX) else (lastPanelX + (calibOffsetX * scaleFactor).toInt())
                    val finalY = if (isTabletResolution) (lastPanelY + calibOffsetY) else (lastPanelY + (calibOffsetY * scaleFactor).toInt())
                    updateMagnifier(bmp, finalX, finalY)
                }
            }
        }
    }

    private fun closeMagnifierWindow() {
        if (magnifierWindowView != null) {
            windowManager.removeView(magnifierWindowView)
            magnifierWindowView = null
            ivZoom = null
            tvCoords = null
            tvColorHex = null
        }
    }



    // Отдает копию последнего закешированного скриншота
    private fun captureScreen(): Bitmap? {
        synchronized(bitmapLock) {
            val current = latestBitmap
            if (current == null || current.isRecycled) return null
            return try {
                current.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun triggerReaction() {
        if (selectedReactions.contains("Ничего")) return
        
        if (selectedReactions.contains("Звук")) {
            startPersistentAlarm()
        }
        
        if (selectedReactions.contains("Вибрация")) {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        }
    }

    private fun handleTelegramAlerts(hasEnemy: Boolean, hasNeutral: Boolean) {
        if (!selectedReactions.contains("Telegram") || selectedReactions.contains("Ничего")) return

        // Уведомление о врагах (минусы)
        if (hasEnemy && !lastEnemyDetected) {
            sendTelegramAlert("🚨 ВНИМАНИЕ! Обнаружен ВРАГ (Минус)!")
        }
        
        // Уведомление о нейтралах
        if (hasNeutral && !lastNeutralDetected) {
            sendTelegramAlert("⚠️ ВНИМАНИЕ! Обнаружен НЕЙТРАЛ!")
        }
    }

    private fun sendTelegramAlert(message: String) {
        if (tgChatId.isEmpty()) {
            addLog("Telegram: Chat ID не задан!")
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val url = "https://api.telegram.org/bot$tgToken/sendMessage"
                
                val formBody = FormBody.Builder()
                    .add("chat_id", tgChatId)
                    .add("text", message)
                    .build()
                
                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    addLog("Telegram: Уведомление отправлено")
                } else {
                    addLog("Telegram: Ошибка ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                addLog("Telegram: Ошибка сети - ${e.message}")
            }
        }
    }

    private fun handleDiscordAlerts(hasEnemy: Boolean, hasNeutral: Boolean) {
        if (!selectedReactions.contains("Discord") || selectedReactions.contains("Ничего")) return

        if (hasEnemy && !lastEnemyDetected) {
            sendDiscordAlert("🚨 ВНИМАНИЕ! Обнаружен ВРАГ (Минус)!")
        }
        if (hasNeutral && !lastNeutralDetected) {
            sendDiscordAlert("⚠️ ВНИМАНИЕ! Обнаружен НЕЙТРАЛ!")
        }
    }

    private fun sendDiscordAlert(message: String) {
        val url = discordWebhookUrl
        if (url.isEmpty()) {
            addLog("Discord: Webhook URL не задан!")
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val json = """{"content": "$message"}"""
                val body = RequestBody.create("application/json; charset=utf-8".toMediaType(), json)
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Android AutoClicker)")
                    .post(body)
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    addLog("Discord: Уведомление отправлено")
                } else {
                    addLog("Discord: Ошибка ${response.code}")
                    val errorBody = response.body?.string()
                    if (errorBody != null) {
                        Log.e("Discord", "Error response: $errorBody")
                    }
                }
                response.close()
            } catch (e: Exception) {
                addLog("Discord: Ошибка сети - ${e.message}")
            }
        }
    }

    private fun startPersistentAlarm() {
        if (isAlarmActive) return
        isAlarmActive = true
        
        // 1. Создаем НЕВИДИМЫЙ слой на весь экран для отлова клика
        showAlarmOverlay()

        // 2. Запускаем звук (ОДИН РАЗ, но без прерываний)
        val resId = resources.getIdentifier("alarm", "raw", packageName)
        if (resId != 0) {
            try {
                // Важно: освобождаем старый плеер, если он был
                alarmPlayer?.stop()
                alarmPlayer?.release()
                
                alarmPlayer = MediaPlayer.create(this, resId)
                alarmPlayer?.isLooping = false // Проигрываем один раз
                
                // Когда звук доиграет сам до конца - убираем overlay
                alarmPlayer?.setOnCompletionListener {
                    stopAlarm()
                }
                
                alarmPlayer?.start()
            } catch (e: Exception) {
                playFallbackTone()
                stopAlarm()
            }
        } else {
            playFallbackTone()
            stopAlarm()
        }
    }

    private fun showAlarmOverlay() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        alarmOverlay = View(this).apply {
            setBackgroundColor(0x01000000) // Почти полностью прозрачный (невидимый)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    addLog("Звук прерван нажатием на экран")
                    stopAlarm()
                }
                true
            }
        }

        try {
            windowManager.addView(alarmOverlay, params)
        } catch (e: Exception) {
            Log.e("AutoClicker", "Не удалось создать overlay")
        }
    }

    private fun stopAlarm() {
        isAlarmActive = false
        
        // Останавливаем звук
        try {
            alarmPlayer?.stop()
            alarmPlayer?.release()
            alarmPlayer = null
        } catch (e: Exception) {}

        // Удаляем красный слой
        if (alarmOverlay != null) {
            try { windowManager.removeView(alarmOverlay) } catch (e: Exception) {}
            alarmOverlay = null
        }
        
        addLog("Тревога отключена пользователем")
    }

    private fun playFallbackTone() {
        try {
            val toneG = android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100)
            toneG.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
        } catch (e: Exception) {
            Log.e("AutoClicker", "Ошибка звука: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        addLog("СЕРВИС: Уничтожение сервиса (onDestroy)")
        stopSearch()
        stopAlarm() // Гасим тревогу если она орет
        
        // Удаляем основное окно
        if (::floatingView.isInitialized) {
            try { windowManager.removeView(floatingView) } catch (e: Exception) {}
        }
        
        // УДАЛЯЕМ ОКНО ЛОГОВ (если открыто)
        if (logWindowView != null) {
            try { windowManager.removeView(logWindowView) } catch (e: Exception) {}
            logWindowView = null
        }
        
        if (magnifierWindowView != null) {
            try { windowManager.removeView(magnifierWindowView) } catch (e: Exception) {}
            magnifierWindowView = null
        }

        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }
}
