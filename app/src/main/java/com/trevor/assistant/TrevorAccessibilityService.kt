package com.trevor.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle

/**
 * User-enabled UI interaction bridge.
 *
 * TREVOR can inspect the accessibility tree and perform semantic clicks/swipes.
 * It cannot bypass Android security boundaries, lock screens, or grant itself access.
 */
class TrevorAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: TrevorAccessibilityService? = null
            private set
    }

    private var lastPackage = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100L
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastPackage = event?.packageName?.toString().orEmpty()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun currentPackage(): String = lastPackage

    fun clickText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = target.trim()
        if (wanted.isBlank()) return false
        val node = findNode(root) { n ->
            n.text?.toString()?.trim()?.equals(wanted, true) == true ||
                n.contentDescription?.toString()?.trim()?.equals(wanted, true) == true
        } ?: findNode(root) { n ->
            n.text?.toString()?.contains(wanted, true) == true ||
                n.contentDescription?.toString()?.contains(wanted, true) == true
        } ?: return false
        return clickNode(node)
    }

    fun hasText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = target.trim()
        return findNode(root) { n ->
            n.text?.toString()?.trim()?.equals(wanted, true) == true ||
                n.contentDescription?.toString()?.trim()?.equals(wanted, true) == true
        } != null
    }

    fun hasDescription(description: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = description.trim()
        return findNode(root) {
            it.contentDescription?.toString()?.trim()?.equals(wanted, true) == true
        } != null
    }

    fun clickDescription(description: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root) {
            it.contentDescription?.toString()?.trim()?.equals(description.trim(), true) == true
        } ?: return false
        return clickNode(node)
    }

    fun clickAt(x: Int, y: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root) {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.contains(x, y) && (it.isClickable || it.isFocusable)
        } ?: return false
        return clickNode(node)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 350L): Boolean =
        dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        android.graphics.Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) },
                        0L, durationMs.coerceIn(100L, 2000L)
                    )
                ).build(), null, null
        )

    fun inspect(): List<TrevorOfflineLearning.Knowledge> {
        val root = rootInActiveWindow ?: return emptyList()
        val pkg = root.packageName?.toString().orEmpty()
        val labels = TrevorUiLearning.describeCurrentScreen(root)
        labels.forEach {
            val subject = it.targetText.ifBlank { it.contentDescription }
            if (subject.isNotBlank()) {
                TrevorOfflineLearning.observe(
                    applicationContext,
                    "$subject is a visible interactive UI element in $pkg",
                    pkg,
                    "OBSERVE_UI",
                    TrevorOfflineLearning.Outcome.UNKNOWN,
                    source = "accessibility"
                )
            }
        }
        return TrevorOfflineLearning.recall(applicationContext, pkg, pkg)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var cursor: AccessibilityNodeInfo? = node
        while (cursor != null) {
            if (cursor.isClickable && cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cursor = cursor.parent
        }
        return false
    }

    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findNode(child, predicate)?.let { return it }
        }
        return null
    }
}
