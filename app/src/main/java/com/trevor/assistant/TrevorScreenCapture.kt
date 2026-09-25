package com.trevor.assistant

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.*
import android.content.Intent
import android.content.pm.ServiceInfo
import kotlinx.coroutines.*

/** Parts 9/10: user-consented screen capture -> local OCR pipeline. */
object TrevorScreenCapture {
    const val REQUEST_CODE = 9101
    const val EXTRA_RESULT_CODE = "trevor_projection_result"
    const val EXTRA_RESULT_DATA = "trevor_projection_data"

    fun permissionIntent(context: Context): Intent =
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .createScreenCaptureIntent()

    fun start(context: Context, resultCode: Int, data: Intent) {
        context.startForegroundService(
            Intent(context, TrevorScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
        )
    }
}

class TrevorScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: android.media.projection.MediaProjection? = null
    private var reader: ImageReader? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = Notification.Builder(this, "trevor_capture")
            .setContentTitle("TREVOR visual capture")
            .setContentText("Screen analysis is active. Stop it from the notification.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(9102, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(9102, notification)
        }
        val code = intent?.getIntExtra(TrevorScreenCapture.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtraCompat<Intent>(TrevorScreenCapture.EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        captureOnce(code, data)
        return START_NOT_STICKY
    }

    private fun captureOnce(resultCode: Int, data: Intent) {
        scope.launch {
            try {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = manager.getMediaProjection(resultCode, data)
                val metrics = resources.displayMetrics
                val width = metrics.widthPixels.coerceAtMost(1920)
                val height = metrics.heightPixels.coerceAtMost(1920)
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                val localReader = reader ?: return@launch
                val virtual = projection?.createVirtualDisplay(
                    "TREVOR-Visual",
                    width, height, metrics.densityDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    localReader.surface, null, null
                ) ?: return@launch
                val image = withTimeoutOrNull(3000) {
                    var captured: android.media.Image? = null
                    while (captured == null) {
                        captured = localReader.acquireLatestImage()
                        if (captured == null) delay(40)
                    }
                    captured
                }
                if (image != null) {
                    val plane = image.planes[0]
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(plane.buffer)
                    val text = TrevorOcr.recognize(bitmap).take(30000)
                    TrevorScreenCaptureStore.save(this@TrevorScreenCaptureService, width, height, text)
                    bitmap.recycle()
                    image.close()
                }
                virtual.release()
            } catch (_: Throwable) {
            } finally {
                reader?.close()
                projection?.stop()
                stopSelf()
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel("trevor_capture", "TREVOR visual capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}

object TrevorScreenCaptureStore {
    private const val PREFS = "trevor_screen_capture"
    fun save(context: Context, width: Int, height: Int, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("width", width).putInt("height", height)
            .putString("ocr", text.take(30000)).putLong("time", System.currentTimeMillis()).apply()
    }
    fun latest(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ocr", "") ?: ""
}

private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)
