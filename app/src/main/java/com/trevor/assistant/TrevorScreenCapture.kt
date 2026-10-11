package com.trevor.assistant

import android.app.*
import android.content.pm.ServiceInfo
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjectionManager
import android.os.*
import android.content.Intent
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
            var virtual: android.hardware.display.VirtualDisplay? = null
            var capturedImage: android.media.Image? = null
            var bitmap: Bitmap? = null
            try {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = manager.getMediaProjection(resultCode, data)
                val metrics = resources.displayMetrics
                val width = metrics.widthPixels.coerceAtMost(1920)
                val height = metrics.heightPixels.coerceAtMost(1920)
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                val localReader = reader ?: return@launch
                virtual = projection?.createVirtualDisplay(
                    "TREVOR-Visual",
                    width, height, metrics.densityDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    localReader.surface, null, null
                ) ?: return@launch

                capturedImage = withTimeoutOrNull(3000) {
                    var image: android.media.Image? = null
                    while (image == null) {
                        image = localReader.acquireLatestImage()
                        if (image == null) delay(40)
                    }
                    image
                } ?: return@launch

                val plane = capturedImage!!.planes[0]
                // ImageReader rows can be padded beyond width * pixelStride. Copy
                // into a padded bitmap first, then crop the visible screen region.
                val rowPadding = plane.rowStride - plane.pixelStride * width
                val paddedWidth = width + (rowPadding / plane.pixelStride)
                val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                val visibleBitmap = try {
                    paddedBitmap.copyPixelsFromBuffer(plane.buffer)
                    Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
                } finally {
                    paddedBitmap.recycle()
                }
                bitmap = visibleBitmap
                val text = TrevorOcr.recognize(visibleBitmap).take(30000)
                TrevorScreenCaptureStore.save(this@TrevorScreenCaptureService, width, height, text)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Capture is best-effort; always release projection/image resources below.
            } finally {
                bitmap?.recycle()
                capturedImage?.close()
                virtual?.release()
                reader?.close()
                reader = null
                projection?.stop()
                projection = null
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
