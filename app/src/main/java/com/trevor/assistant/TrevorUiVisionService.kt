package com.trevor.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class TrevorUiElement(
    val text: String,
    val contentDescription: String,
    val className: String,
    val bounds: Rect,
    val clickable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean
)

data class TrevorUiSnapshot(
    val packageName: String,
    val capturedAt: Long,
    val elements: List<TrevorUiElement>,
    val ocrText: String
)

class TrevorUiVisionService : AccessibilityService() {
    companion object {
        @Volatile private var instance: TrevorUiVisionService? = null

        fun isConnected(): Boolean = instance != null
        fun tap(target: String): Boolean = instance?.tapTarget(target) == true
        fun typeText(text: String): Boolean = instance?.typeIntoFocusedField(text) == true
        fun scroll(direction: String): Boolean = instance?.scrollTarget(direction) == true
        fun snapshot(context: android.content.Context): TrevorUiSnapshot? =
            TrevorUiVisionStore.latest(context)

        fun openAccessibilitySettings(context: android.content.Context) {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return
        captureUi(pkg)
    }

    private fun captureUi(pkg: String) {
        val root = rootInActiveWindow ?: return
        val elements = ArrayList<TrevorUiElement>()
        collect(root, elements, 0)
        root.recycle()

        TrevorUiVisionStore.save(
            this,
            TrevorUiSnapshot(pkg, System.currentTimeMillis(), elements.take(500), "")
        )

        // On Android 11+, OCR the actual visible screen locally as a second
        // perception channel. No screenshot is uploaded anywhere.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            scope.launch {
                                runCatching {
                                    val buffer = result.hardwareBuffer
                                    val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                        buffer,
                                        result.colorSpace
                                    )
                                    buffer.close()
                                    if (bitmap != null) {
                                        val text = TrevorOcr.recognize(bitmap).take(12000)
                                        bitmap.recycle()
                                        TrevorUiVisionStore.updateOcr(
                                            this@TrevorUiVisionService,
                                            text
                                        )
                                    }
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) = Unit
                    }
                )
            }
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        output: MutableList<TrevorUiElement>,
        depth: Int
    ) {
        if (depth > 20 || output.size >= 500) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        output += TrevorUiElement(
            node.text?.toString().orEmpty().take(500),
            node.contentDescription?.toString().orEmpty().take(500),
            node.className?.toString().orEmpty().take(200),
            Rect(bounds),
            node.isClickable,
            node.isScrollable,
            node.isEnabled
        )

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, output, depth + 1)
            child.recycle()
        }
    }

    private fun tapTarget(target: String): Boolean {
        val query = target.trim().lowercase()
        if (query.isBlank()) return false
        val root = rootInActiveWindow ?: return false

        val node = findNode(root) {
            it.isEnabled && (
                it.text?.toString()?.trim()?.lowercase()?.contains(query) == true ||
                it.contentDescription?.toString()?.trim()?.lowercase()?.contains(query) == true
            )
        } ?: return false

        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            node.recycle()
            return true
        }

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        node.recycle()
        if (bounds.isEmpty) return false
        return dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun typeIntoFocusedField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findNode(root) { it.isFocused && it.isEditable && it.isEnabled }
            ?: findNode(root) { it.isEditable && it.isEnabled }
            ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        root.recycle()
        return ok
    }

    private fun scrollTarget(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val normalized = direction.trim().lowercase()
        val forward = normalized !in setOf("up", "back", "backward", "left")
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        val node = findNode(root) { it.isScrollable }
        if (node != null) {
            val ok = node.performAction(action)
            node.recycle()
            if (ok) return true
        }

        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = if (forward) metrics.heightPixels * 0.75f else metrics.heightPixels * 0.25f
        val endY = if (forward) metrics.heightPixels * 0.25f else metrics.heightPixels * 0.75f
        return dispatchSwipe(x, startY, x, endY)
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return AccessibilityNodeInfo.obtain(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNode(child, predicate)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}

object TrevorUiVisionStore {
    private const val PREFS = "trevor_ui_vision"
    private const val SNAPSHOT = "snapshot"

    fun save(context: android.content.Context, snapshot: TrevorUiSnapshot) {
        val json = org.json.JSONObject()
            .put("package", snapshot.packageName)
            .put("time", snapshot.capturedAt)
            .put("ocr", snapshot.ocrText)

        val elements = org.json.JSONArray()
        snapshot.elements.forEach {
            elements.put(
                org.json.JSONObject()
                    .put("text", it.text)
                    .put("desc", it.contentDescription)
                    .put("class", it.className)
                    .put("left", it.bounds.left)
                    .put("top", it.bounds.top)
                    .put("right", it.bounds.right)
                    .put("bottom", it.bounds.bottom)
                    .put("clickable", it.clickable)
                    .put("scrollable", it.scrollable)
                    .put("enabled", it.enabled)
            )
        }
        json.put("elements", elements)

        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(SNAPSHOT, json.toString().take(500_000))
            .apply()
    }

    fun updateOcr(context: android.content.Context, text: String) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString(SNAPSHOT, null) ?: return
        runCatching {
            prefs.edit()
                .putString(
                    SNAPSHOT,
                    org.json.JSONObject(raw).put("ocr", text.take(12000)).toString()
                )
                .apply()
        }
    }

    fun latest(context: android.content.Context): TrevorUiSnapshot? {
        val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(SNAPSHOT, null) ?: return null

        return runCatching {
            val json = org.json.JSONObject(raw)
            val array = json.optJSONArray("elements") ?: org.json.JSONArray()
            val elements = buildList {
                for (i in 0 until array.length()) {
                    val e = array.getJSONObject(i)
                    add(
                        TrevorUiElement(
                            e.optString("text"),
                            e.optString("desc"),
                            e.optString("class"),
                            Rect(
                                e.optInt("left"),
                                e.optInt("top"),
                                e.optInt("right"),
                                e.optInt("bottom")
                            ),
                            e.optBoolean("clickable"),
                            e.optBoolean("scrollable"),
                            e.optBoolean("enabled", true)
                        )
                    )
                }
            }

            TrevorUiSnapshot(
                json.optString("package"),
                json.optLong("time"),
                elements,
                json.optString("ocr")
            )
        }.getOrNull()
    }
}
