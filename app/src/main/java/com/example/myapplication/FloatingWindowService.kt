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
import android.util.Log
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

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var btnStart: ImageButton
    private lateinit var btnLogs: ImageButton
    private lateinit var btnClose: ImageButton

    // Переменные для окна логов
    private var logWindowView: View? = null
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
    private var debugMarkerView: View? = null
    
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
    private var targetBitmap: Bitmap? = null // Та самая картинка, которую мы ищем

    // Параметры для функции "Поиск врагов"
    private val colorNetral = 10000000
    private val colorRed = 5700351
    private var timeStartVrag: Long = 0

    // Цветовые диапазоны
    private val bgRange = 1800000..2500000
    private val digitRange = 7000000..13000000
    private val controlRange = 10000000..12000000

    // Координаты калибровки (смещения относительно угла панели)
    private var calibOffsetX = 294
    private var calibOffsetY = 51

    // Настройки из главного окна
    private var isEnemySearchEnabledBySettings = true
    private var selectedReaction = "Звук"
    private var isDevMode = false
    
    // Состояние для предотвращения спама звуком
    private var wasEnemyPresentLastTime = false

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

        // ЗАГРУЗКА НОВОЙ КАРТИНКИ: panel_1080.png
        try {
            val options = BitmapFactory.Options()
            options.inScaled = false
            targetBitmap = BitmapFactory.decodeResource(resources, R.drawable.panel_1080, options)
        } catch (e: Exception) {
            Log.e("AutoClicker", "Не удалось загрузить panel_1080.png")
        }
    }


    // Сюда прилетает ответ из MainActivity с разрешением на запись экрана
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.hasExtra("SETTING_ENEMY_SEARCH")) {
                isEnemySearchEnabledBySettings = intent.getBooleanExtra("SETTING_ENEMY_SEARCH", true)
                selectedReaction = intent.getStringExtra("SETTING_REACTION") ?: "Звук"
                isDevMode = intent.getBooleanExtra("SETTING_DEV_MODE", false)
                addLog("Настройки: Поиск=$isEnemySearchEnabledBySettings, Реакция=$selectedReaction, Dev=$isDevMode")
                
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
        params.gravity = Gravity.TOP or Gravity.START
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
        // Один клик = переключение: если ищем — стоп, если нет — старт
        btnStart.setOnTouchListener(createTouchListener {
            if (isSearching) stopSearch() else startSearch()
        })
        btnLogs.setOnTouchListener(createTouchListener  { toggleLogWindow() })
        btnClose.setOnTouchListener(createTouchListener { stopSelf() })

        // --- КНОПКИ КАЛИБРОВКИ ---
        val btnUp = floatingView.findViewById<ImageButton>(R.id.btn_up)
        val btnDown = floatingView.findViewById<ImageButton>(R.id.btn_down)
        val btnLeft = floatingView.findViewById<ImageButton>(R.id.btn_left)
        val btnRight = floatingView.findViewById<ImageButton>(R.id.btn_right)

        btnUp.setOnClickListener { calibOffsetY--; addLog("Смещение Y: $calibOffsetY") }
        btnDown.setOnClickListener { calibOffsetY++; addLog("Смещение Y: $calibOffsetY") }
        btnLeft.setOnClickListener { calibOffsetX--; addLog("Смещение X: $calibOffsetX") }
        btnRight.setOnClickListener { calibOffsetX++; addLog("Смещение X: $calibOffsetX") }

        ivZoom = floatingView.findViewById(R.id.iv_zoom)
    }

    private fun updateDevUiVisibility() {
        if (!::floatingView.isInitialized) return
        val devCalib = floatingView.findViewById<View>(R.id.dev_calibration_container)
        val devMagnifier = floatingView.findViewById<View>(R.id.dev_magnifier_container)
        
        val visibility = if (isDevMode) View.VISIBLE else View.GONE
        devCalib?.visibility = visibility
        devMagnifier?.visibility = visibility
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
                    val screenBitmap = captureScreen() // Делаем скриншот ВСЕГДА
                    
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
                addLog("Панель найдена ($x, $y) Сходство: $scorePercent%")
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
            val x = p.first + offX
            val y = p.second + offY
            if (x < 0 || x >= screen.width || y < 0 || y >= screen.height) return 0
            return screen.getPixel(x, y) and 0xFFFFFF
        }

        // Проверка контрольной точки (не съехала ли панелька)
        val controlColor = getColor(157, 48)
        val isControlOk = controlColor in controlRange
        
        if (!isControlOk) {
            addLog("Контроль смещен! (157,48): $controlColor (нужно $controlRange)")
            // Если контроль не прошел, не делаем выводов о 0, но продолжаем проверку
        }

        // Проверка Нейтралов на 0
        val neutPoints = listOf(Pair(293, 48), Pair(293, 37), Pair(305, 37), Pair(305, 49))
        val isNeutZero = isControlOk && neutPoints.all { pt ->
            val c = getColor(pt.first, pt.second)
            c in digitRange && c !in bgRange
        }

        // Проверка Минусов на 0
        val minusPoints = listOf(Pair(169, 49), Pair(169, 38), Pair(169, 48), Pair(180, 48))
        val isMinusZero = isControlOk && minusPoints.all { pt ->
            val c = getColor(pt.first, pt.second)
            c in digitRange && c !in bgRange
        }

        // Для отладки и калибровки (маркер, лупа и цвет)
        val targetX = p.first + calibOffsetX
        val targetY = p.second + calibOffsetY
        updateDebugMarker(targetX, targetY)
        updateMagnifier(screen, targetX, targetY)
        
        val cPixel = getColor(calibOffsetX, calibOffsetY)
        addLog("Цвет в ($calibOffsetX,$calibOffsetY): $cPixel")

        if (isControlOk) {
            var enemyFound = false
            if (!isNeutZero) {
                addLog(">>> зашол нейтрал <<<")
                enemyFound = true
            }
            if (!isMinusZero) {
                addLog(">>> зашол минус <<<")
                enemyFound = true
            }

            val isEnemyCurrentlyPresent = !isNeutZero || !isMinusZero

            if (isEnemyCurrentlyPresent) {
                // Звук и вибрация срабатывают только если враг ПОЯВИЛСЯ (в прошлый раз его не было)
                if (!wasEnemyPresentLastTime) {
                    addLog("!!! ОБНАРУЖЕН НОВЫЙ ВРАГ !!!")
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

    // Рисует точку 5x5 в месте проверки пикселя, гаснет через 0.5 сек
    private fun updateDebugMarker(x: Int, y: Int) {
        mainHandler.post {
            if (debugMarkerView == null) {
                debugMarkerView = View(this).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#00FF00")) // Ярко-салатовый
                }
                val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val markerParams = WindowManager.LayoutParams(
                    5, 5, // Размер 5x5 пикселей
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
                try {
                    windowManager.addView(debugMarkerView, markerParams)
                } catch (e: Exception) {}
            }

            // Показываем маркер точно по центру проверяемой точки
            debugMarkerView?.visibility = View.VISIBLE
            val params = debugMarkerView?.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                params.x = x - 2 // Центрируем 5x5 на точке
                params.y = y - 2
                try {
                    windowManager.updateViewLayout(debugMarkerView, params)
                } catch (e: Exception) {}
            }

            // Прячем через 0.5 сек чтобы не мешать наблюдению
            mainHandler.postDelayed({
                debugMarkerView?.visibility = View.INVISIBLE
            }, 500)
        }
    }

    private fun updateMagnifier(screen: Bitmap, centerX: Int, centerY: Int) {
        val size = 12 // Кроп 12x12 пикселей
        val half = size / 2
        
        // Вычисляем границы кропа
        val left = (centerX - half).coerceIn(0, screen.width - size)
        val top = (centerY - half).coerceIn(0, screen.height - size)
        
        try {
            // Вырезаем область
            val crop = Bitmap.createBitmap(screen, left, top, size, size)
            // Масштабируем в 10 раз (до 120x120)
            // filter = false позволяет видеть пиксели четкими квадратами без размытия
            val scaled = Bitmap.createScaledBitmap(crop, size * 10, size * 10, false)
            
            mainHandler.post {
                ivZoom?.setImageBitmap(scaled)
                crop.recycle()
            }
        } catch (e: Exception) {
            Log.e("AutoClicker", "Ошибка лупы: ${e.message}")
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
        when (selectedReaction) {
            "Звук" -> {
                // Используем ваш файл alarm.wav из res/raw
                val resId = resources.getIdentifier("alarm", "raw", packageName)
                if (resId != 0) {
                    try {
                        val mp = MediaPlayer.create(this, resId)
                        mp.setOnCompletionListener { it.release() }
                        mp.start()
                    } catch (e: Exception) {
                        playFallbackTone()
                    }
                } else {
                    playFallbackTone()
                }
            }
            "Вибрация" -> {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    vibrator.vibrate(300)
                }
            }
        }
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
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
        if (debugMarkerView != null) {
            try { windowManager.removeView(debugMarkerView) } catch (e: Exception) {}
        }
    }
}
