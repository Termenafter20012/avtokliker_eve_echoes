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
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
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
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnLogs: Button

    // Переменные для окна логов
    private var logWindowView: View? = null
    private var tvLogs: android.widget.TextView? = null
    private val logMessages = mutableListOf<String>()

    // Инструменты для записи экрана
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestBitmap: Bitmap? = null // Кешируем последний кадр
    
    // Переменные для логики поиска и автоклика
    private var isSearching = false
    private var searchJob: Job? = null
    private var targetBitmap: Bitmap? = null // Та самая картинка, которую мы ищем

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        setupFloatingWindow()
        
        // Пытаемся загрузить вашу картинку (target_image) из папки ресурсов (drawable)
        try {
            val options = BitmapFactory.Options()
            options.inScaled = false // ВАЖНО: Отключаем автоматическое масштабирование Android, иначе картинка искажается
            targetBitmap = BitmapFactory.decodeResource(resources, R.drawable.target_image, options)
        } catch (e: Exception) {
            Log.e("AutoClicker", "Не удалось загрузить target_image.png")
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

                    // Обновляем закешированный скриншот
                    val oldBitmap = latestBitmap
                    latestBitmap = croppedBitmap
                    oldBitmap?.recycle() // Очищаем старый
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
        btnStop = floatingView.findViewById(R.id.btn_stop)
        btnLogs = floatingView.findViewById(R.id.btn_logs)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoved = false

        // Функция для создания одинакового слушателя касаний (для перетаскивания и кликов с анимацией)
        val createTouchListener = { action: () -> Unit ->
            View.OnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Анимация нажатия (кнопка слегка уменьшается)
                        if (view is Button) {
                            view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                        }
                        
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
                        // Возвращаем размер кнопки в норму
                        if (view is Button) {
                            view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        }
                        
                        // Если мы не перетаскивали окно, а просто отпустили палец - выполняем действие
                        if (!isMoved && event.action == MotionEvent.ACTION_UP) {
                            action() 
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        
        // Вешаем слушатели на все элементы
        rootContainer.setOnTouchListener(createTouchListener { }) // Просто фон, ничего не делает по клику
        btnStart.setOnTouchListener(createTouchListener { startSearch() })
        btnStop.setOnTouchListener(createTouchListener { stopSearch() })
        btnLogs.setOnTouchListener(createTouchListener { toggleLogWindow() })
    }

    // --- ЛОГИКА ОКНА ЛОГОВ ---
    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logLine = "[$time] $message"
        
        logMessages.add(logLine)
        if (logMessages.size > 50) {
            logMessages.removeAt(0) // Храним только последние 50 записей
        }

        // Обновляем текст в окне логов, если оно открыто (нужно делать в главном потоке)
        CoroutineScope(Dispatchers.Main).launch {
            tvLogs?.text = logMessages.joinToString("\n")
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
        logParams.y = 500 // Показываем чуть ниже основного окна

        windowManager.addView(logWindowView, logParams)

        tvLogs = logWindowView?.findViewById(R.id.tv_logs)
        tvLogs?.text = logMessages.joinToString("\n")

        val btnClose = logWindowView?.findViewById<Button>(R.id.btn_close_logs)
        btnClose?.setOnClickListener { closeLogWindow() }
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
            val msg = "ОШИБКА: Картинка target_image.png не найдена!"
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
        Toast.makeText(this, "Поиск запущен! Ищем раз в секунду.", Toast.LENGTH_SHORT).show()
        addLog("=== ПОИСК ЗАПУЩЕН ===")

        // Запускаем фоновую задачу (Корутину)
        searchJob = CoroutineScope(Dispatchers.Default).launch {
            while (isSearching) {
                addLog("Скриншот сделан. Ищу картинку...")
                val screenBitmap = captureScreen() // Делаем скриншот
                if (screenBitmap != null) {
                    
                    // Ищем картинку
                    val foundPoint = ImageMatcher.findTemplate(screenBitmap, targetBitmap!!)
                    
                    if (foundPoint != null) {
                        // УРА! КАРТИНКА НАЙДЕНА!
                        addLog("НАЙДЕНО! Отправляю клик по координатам X=${foundPoint.first}, Y=${foundPoint.second}")
                        
                        // Вызываем наш Accessibility Service и просим кликнуть по координатам
                        AutoClickerAccessibilityService.instance?.performClick(
                            foundPoint.first.toFloat(), 
                            foundPoint.second.toFloat()
                        )
                        
                        // ТЕПЕРЬ ЦИКЛ НЕ ОСТАНАВЛИВАЕТСЯ. Мы просто идем дальше.
                    } else {
                        addLog("Картинка не найдена на этом кадре.")
                    }
                    screenBitmap.recycle() // Очищаем память от скриншота
                } else {
                    addLog("Ожидание первого кадра экрана...")
                }
                
                // Ждем РОВНО 1 секунду перед следующим поиском
                delay(1000)
            }
        }
    }

    private fun stopSearch() {
        if (!isSearching) return
        isSearching = false
        searchJob?.cancel() // Убиваем фоновую корутину
        Toast.makeText(this, "Поиск остановлен", Toast.LENGTH_SHORT).show()
        addLog("=== ПОИСК ОСТАНОВЛЕН ===")
    }

    // Отдает копию последнего закешированного скриншота
    private fun captureScreen(): Bitmap? {
        // Делаем копию, чтобы цикл мог спокойно ее анализировать и потом удалить (recycle)
        return latestBitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}
