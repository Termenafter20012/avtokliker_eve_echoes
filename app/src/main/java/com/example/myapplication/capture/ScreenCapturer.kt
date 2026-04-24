package com.example.myapplication.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class ScreenCapturer(
    private val context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onCaptureStopped()
        fun onError(message: String)
        fun onLogRequested(message: String)
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(resultCode: Int, data: Intent) {
        mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                callbacks.onLogRequested("СИСТЕМА: Запись экрана остановлена (MediaProjection Stop)")
                callbacks.onCaptureStopped()
            }
        }, mainHandler)

        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        
        val metrics = context.resources.displayMetrics
        val width = maxOf(metrics.widthPixels, metrics.heightPixels)
        val height = minOf(metrics.widthPixels, metrics.heightPixels)
        val density = metrics.densityDpi

        if (mediaProjection == null) {
            callbacks.onError("ОШИБКА: MediaProjection не инициализирован")
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
                    Log.e("ScreenCapturer", "Ошибка обработки кадра: ${e.message}")
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
            callbacks.onLogRequested("СИСТЕМА: Захват экрана настроен ($width x $height)")
        } catch (e: Exception) {
            callbacks.onError("ОШИБКА создания VirtualDisplay: ${e.message}")
        }
    }

    fun getLatestScreenshot(): Bitmap? {
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

    fun release() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        synchronized(bitmapLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }
}
