package com.replyassistant.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import java.io.IOException

class CaptureService : Service() {
    private val binder = LocalBinder()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionThread: HandlerThread? = null
    private var projectionHandler: Handler? = null

    @Volatile
    private var projectionReady = false

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.projectionIntent()

        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForegroundService()
        initializeProjection(resultCode, resultData)
        return START_NOT_STICKY
    }

    fun captureScreenshot(callback: (Result<Bitmap>) -> Unit) {
        val handler = projectionHandler
        if (!projectionReady || handler == null) {
            callback(Result.failure(IOException("Screen capture is not ready yet.")))
            return
        }

        handler.post {
            acquireLatestBitmapWithRetry(attempt = 0, callback = callback)
        }
    }

    override fun onDestroy() {
        projectionReady = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        projectionThread?.quitSafely()
        projectionThread = null
        projectionHandler = null
        super.onDestroy()
    }

    private fun initializeProjection(resultCode: Int, resultData: Intent) {
        if (projectionReady) return

        projectionThread = HandlerThread("ReplyAssistantProjection").also { it.start() }
        projectionHandler = Handler(projectionThread!!.looper)

        val metrics = currentDisplayMetrics()
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            MAX_IMAGES
        )

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData).also { projection ->
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        projectionReady = false
                        virtualDisplay?.release()
                        virtualDisplay = null
                        imageReader?.close()
                        imageReader = null
                    }
                },
                projectionHandler
            )
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            projectionHandler
        )

        projectionReady = virtualDisplay != null
    }

    private fun acquireLatestBitmapWithRetry(
        attempt: Int,
        callback: (Result<Bitmap>) -> Unit
    ) {
        val reader = imageReader
        if (reader == null) {
            callback(Result.failure(IOException("Image reader is not available.")))
            return
        }

        val image = reader.acquireLatestImage()
        if (image != null) {
            callback(runCatching { image.toBitmap() })
            return
        }

        if (attempt >= MAX_CAPTURE_ATTEMPTS) {
            callback(Result.failure(IOException("No screen frame was available to capture.")))
            return
        }

        projectionHandler?.postDelayed(
            { acquireLatestBitmapWithRetry(attempt + 1, callback) },
            CAPTURE_RETRY_DELAY_MS
        )
    }

    private fun Image.toBitmap(): Bitmap {
        use { image ->
            val plane = image.planes.first()
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride

            val paddedBitmap = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            paddedBitmap.copyPixelsFromBuffer(buffer)

            val bitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
            paddedBitmap.recycle()
            return bitmap
        }
    }

    private fun currentDisplayMetrics() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowManager = getSystemService(WindowManager::class.java)
        val bounds = windowManager.maximumWindowMetrics.bounds
        resources.displayMetrics.also {
            it.widthPixels = bounds.width()
            it.heightPixels = bounds.height()
        }
    } else {
        @Suppress("DEPRECATION")
        resources.displayMetrics
    }

    private fun startAsForegroundService() {
        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Reply Assistant")
            .setContentText("Capturing screen context")
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Screen capture",
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    inner class LocalBinder : Binder() {
        val service: CaptureService
            get() = this@CaptureService
    }

    companion object {
        const val EXTRA_RESULT_CODE = "com.replyassistant.extra.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "com.replyassistant.extra.RESULT_DATA"

        private const val VIRTUAL_DISPLAY_NAME = "ReplyAssistantScreenCapture"
        private const val MAX_IMAGES = 3
        private const val MAX_CAPTURE_ATTEMPTS = 12
        private const val CAPTURE_RETRY_DELAY_MS = 120L
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture"
    }
}

private fun Intent.projectionIntent(): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(CaptureService.EXTRA_RESULT_DATA, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(CaptureService.EXTRA_RESULT_DATA)
    }
}
