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
        if (intent != null && intent.hasExtra("SCREEN_CAPTURE_RESULT_CODE")) {
            val resultCode = intent.getIntExtra("SCREEN_CAPTURE_RESULT_CODE", 0)
            val data = intent.getParcelableExtra<Intent>("SCREEN_CAPTURE_INTENT")
            if (data != null) {
                // Если разрешение есть - инициализируем запись экрана
                initMediaProjection(resultCode, data)
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

    // Настройка "виртуального экрана", который каждую секунду делает скриншоты в ImageReader
    @SuppressLint("WrongConstant")
    private fun setupVirtualDisplay() {
        if (mediaProjection == null) return

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // Создаем "читатель" с форматом пикселей RGBA_8888 (стандарт для экранов)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        // Захватываем кадры асинхронно, как только они появляются (при изменении экрана)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
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
                    bitmap.recycle() // ВАЖНО: Освобождаем промежуточный bitmap!

                    // Обновляем закешированный скриншот (с синхронизацией)
                    synchronized(bitmapLock) {
                        val oldBitmap = latestBitmap
                        latestBitmap = croppedBitmap
                        oldBitmap?.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("AutoClicker", "Ошибка обработки кадра: ${e.message}")
                } finally {
                    image.close() // Обязательно закрываем
                }
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
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
    }

    // --- ЛОГИКА ОКНА ЛОГОВ ---
    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logLine = "[$time] $message"
        
        // Дублируем в системный лог (Logcat), чтобы видеть даже если окно сломалось
        Log.d("AutoClicker", logLine)

        synchronized(logMessages) {
            logMessages.add(logLine)
            if (logMessages.size > 100) { // Увеличим до 100
                logMessages.removeAt(0)
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
                    val screenBitmap = captureScreen() // Делаем скриншот
                    
                    if (screenBitmap != null) {
                        // Ищем картинку
                        val foundPoint = try {
                            ImageMatcher.findTemplate(screenBitmap, targetBitmap!!)
                        } catch (e: Exception) {
                            addLog("ОШИБКА В ImageMatcher: ${e.message}")
                            null
                        }
                        
                        if (foundPoint != null) {
                            addLog("НАЙДЕНО! Панель X=${foundPoint.first}, Y=${foundPoint.second}")
                            // Запускаем функцию поиска врагов
                            if (!screenBitmap.isRecycled) {
                                checkEnemy(screenBitmap, foundPoint)
                            }
                        }
                        screenBitmap.recycle()
                    }
                    
                    // Ждем 0.1 секунду перед следующим поиском
                    delay(100)
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

    private fun stopSearch() {
        if (!isSearching) return
        isSearching = false
        searchJob?.cancel()
        // Возвращаем иконку зелёного треугольника (старт)
        btnStart.setImageResource(R.drawable.ic_play)
        Toast.makeText(this, "Поиск остановлен", Toast.LENGTH_SHORT).show()
        addLog("=== ПОИСК ОСТАНОВЛЕН ===")
    }

    // --- ФУНКЦИЯ ПОИСК ВРАГОВ (vrag_f) ---
    private fun checkEnemy(screen: Bitmap, p: Pair<Int, Int>): Boolean {
        // Помощник для безопасного получения цвета пикселя (без альфа-канала)
        fun getSafeColor(x: Int, y: Int): Int {
            if (x < 0 || x >= screen.width || y < 0 || y >= screen.height) return 0
            return screen.getPixel(x, y) and 0xFFFFFF
        }

        val c169 = getSafeColor(p.first + 169, p.second + 44)
        val c140 = getSafeColor(p.first + 140, p.second + 33)
        val c293 = getSafeColor(p.first + 293, p.second + 44)

        // Логируем цвета пикселей для отладки
        addLog("c169(+169,+44)=$c169 | c140(+140,+33)=$c140 | c293(+293,+44)=$c293")

        if (c169 > colorNetral && c140 == colorRed) {
            timeStartVrag = System.currentTimeMillis()
            addLog("зашол нейтрал")
            return true
        } else if (c293 > colorNetral && c140 == colorRed) {
            timeStartVrag = System.currentTimeMillis()
            addLog("зашол минус")
            return true
        }
        
        addLog("не нашол врага")
        return false
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
    }
}
