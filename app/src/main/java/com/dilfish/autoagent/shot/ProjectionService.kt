package com.dilfish.autoagent.shot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import com.dilfish.autoagent.engine.AgentBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * 模块 D：按需单帧截图。
 * 前台服务持有 MediaProjection；每次截图按需建 VirtualDisplay + ImageReader，
 * 抓一帧后立即释放。不做持续推流。
 */
class ProjectionService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "projection"

        @Volatile
        var projection: MediaProjection? = null
            private set

        var running = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ProjectionService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 29) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopCapture(context: Context) {
            context.stopService(Intent(context, ProjectionService::class.java))
        }
    }

    private val handlerThread = HandlerThread("projection").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null || resultCode < 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        val channel = NotificationChannel(CHANNEL_ID, "屏幕截图", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentText("截图功能已启用（仅按需抓取单帧）")
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projection?.stop()
            projection = pm.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    running = false
                }
            }, handler)
            running = true
            AgentBus.log("屏幕截图功能已启用")
        } catch (e: Exception) {
            AgentBus.log("录屏授权失败: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}

/** 截图入口：capture() 挂起直到单帧就绪（JPEG base64），失败返回 null */
object ScreenCapture {

    suspend fun capture(): String? = withContext(Dispatchers.IO) {
        val projection = ProjectionService.projection ?: return@withContext null
        if (!ProjectionService.running) return@withContext null
        try {
            captureOnce(projection)
        } catch (t: Throwable) {
            AgentBus.log("截图异常: ${t.message}")
            null
        }
    }

    private suspend fun captureOnce(projection: MediaProjection): String? {
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val deferred = CompletableDeferred<Image>()
        reader.setOnImageAvailableListener({ r ->
            val img = try {
                r.acquireLatestImage()
            } catch (_: Exception) {
                null
            }
            if (img != null && !deferred.isCompleted) deferred.complete(img)
        }, Handler(handlerLooperForReader()))

        var display: VirtualDisplay? = null
        try {
            display = projection.createVirtualDisplay(
                "autoagent-shot", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null,
            )
            val image = withTimeoutOrNull(3_000) { deferred.await() } ?: return null
            val bitmap = imageToBitmap(image)
            image.close()
            val bos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 55, bos)
            bitmap.recycle()
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } finally {
            try {
                display?.release()
            } catch (_: Exception) {
            }
            reader.close()
        }
    }

    private fun handlerLooperForReader() = android.os.Looper.getMainLooper()

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        // 裁掉行尾 padding
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
        } else bitmap
    }
}
