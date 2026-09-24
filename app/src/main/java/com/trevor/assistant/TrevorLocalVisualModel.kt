package com.trevor.assistant

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TrevorVisualControlType { BUTTON, TAB, ICON, SLIDER, TOGGLE, CARD, TEXT, UNKNOWN }

data class TrevorVisualControl(
    val type: TrevorVisualControlType,
    val bounds: Rect,
    val label: String = "",
    val confidence: Float = 0f,
    val source: String = "local"
)

/**
 * Lightweight offline visual control detector.
 *
 * This is intentionally dependency-light: it detects UI-like geometry and pairs
 * it with OCR/accessibility labels. It does not upload frames or call an AI API.
 * It is useful as a fallback for custom-rendered UIs such as games.
 */
object TrevorLocalVisualModel {
    fun detect(bitmap: Bitmap, ocr: List<TrevorOcrBlock> = emptyList()): List<TrevorVisualControl> {
        val controls = mutableListOf<TrevorVisualControl>()
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return emptyList()

        // Sample a bounded grid and find connected-ish high-contrast rectangular regions.
        // This deliberately avoids expensive full-resolution CV on older phones.
        val maxSide = 900
        val scale = min(1f, maxSide.toFloat() / max(w, h))
        val sw = max(1, (w * scale).toInt())
        val sh = max(1, (h * scale).toInt())
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        try {
            val pixels = IntArray(sw * sh)
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            val cell = max(12, min(sw, sh) / 45)
            val candidates = mutableListOf<Rect>()

            for (y in 0 until sh step cell) {
                for (x in 0 until sw step cell) {
                    val x2 = min(sw, x + cell)
                    val y2 = min(sh, y + cell)
                    var minL = 255
                    var maxL = 0
                    var sum = 0
                    var count = 0
                    for (py in y until y2 step max(1, cell / 4)) {
                        for (px in x until x2 step max(1, cell / 4)) {
                            val c = pixels[py * sw + px]
                            val r = (c shr 16) and 255
                            val g = (c shr 8) and 255
                            val b = c and 255
                            val lum = (299 * r + 587 * g + 114 * b) / 1000
                            minL = min(minL, lum)
                            maxL = max(maxL, lum)
                            sum += lum
                            count++
                        }
                    }
                    val avg = if (count == 0) 0 else sum / count
                    if (maxL - minL >= 55 || avg >= 210) {
                        candidates += Rect(x, y, x2, y2)
                    }
                }
            }

            // Merge nearby candidate cells into coarse control regions.
            val merged = mergeCandidates(candidates, sw, sh)
            for (r0 in merged.take(120)) {
                val r = Rect(
                    (r0.left / scale).toInt(),
                    (r0.top / scale).toInt(),
                    (r0.right / scale).toInt(),
                    (r0.bottom / scale).toInt()
                )
                if (r.width() < 24 || r.height() < 18) continue
                val label = nearestOcrLabel(r, ocr, 180)
                val type = classify(r, label, w, h)
                controls += TrevorVisualControl(type, r, label, confidence(type, label), "local-cv")
            }
        } finally {
            small.recycle()
        }
        return nonMaxSuppression(controls)
    }

    private fun mergeCandidates(input: List<Rect>, w: Int, h: Int): List<Rect> {
        val out = mutableListOf<Rect>()
        for (r in input) {
            var merged = false
            for (i in out.indices) {
                val o = out[i]
                val expanded = Rect(o)
                expanded.inset(-10, -10)
                if (Rect.intersects(expanded, r)) {
                    o.union(r)
                    merged = true
                    break
                }
            }
            if (!merged) out += Rect(r)
        }
        return out.map {
            Rect(max(0, it.left), max(0, it.top), min(w, it.right), min(h, it.bottom))
        }
    }

    private fun nearestOcrLabel(r: Rect, ocr: List<TrevorOcrBlock>, maxDistance: Int): String {
        return ocr.minByOrNull { b ->
            val cx = b.bounds.centerX()
            val cy = b.bounds.centerY()
            abs(cx - r.centerX()) + abs(cy - r.centerY())
        }?.takeIf {
            abs(it.bounds.centerX() - r.centerX()) + abs(it.bounds.centerY() - r.centerY()) <= maxDistance
        }?.text.orEmpty()
    }

    private fun classify(r: Rect, label: String, w: Int, h: Int): TrevorVisualControlType {
        val s = label.lowercase()
        if (s.contains("tab") || s in setOf("home", "search", "library", "subscriptions")) return TrevorVisualControlType.TAB
        if (s.contains("on") || s.contains("off") || s.contains("toggle")) return TrevorVisualControlType.TOGGLE
        if (s.contains("slider") || s.contains("volume") || s.contains("throttle")) return TrevorVisualControlType.SLIDER
        if (r.width() > w * .65 && r.height() < h * .12) return TrevorVisualControlType.CARD
        if (r.width() < w * .2 && r.height() < h * .2) return TrevorVisualControlType.ICON
        return if (label.isNotBlank()) TrevorVisualControlType.BUTTON else TrevorVisualControlType.UNKNOWN
    }

    private fun confidence(type: TrevorVisualControlType, label: String): Float =
        min(0.99f, (if (label.isNotBlank()) .72f else .42f) +
            when (type) {
                TrevorVisualControlType.TAB, TrevorVisualControlType.TOGGLE, TrevorVisualControlType.SLIDER -> .18f
                TrevorVisualControlType.BUTTON, TrevorVisualControlType.ICON -> .12f
                else -> .05f
            })

    private fun nonMaxSuppression(input: List<TrevorVisualControl>): List<TrevorVisualControl> {
        val kept = mutableListOf<TrevorVisualControl>()
        for (c in input.sortedByDescending { it.confidence }) {
            if (kept.none { iou(it.bounds, c.bounds) > .65f }) kept += c
        }
        return kept
    }

    private fun iou(a: Rect, b: Rect): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bot = min(a.bottom, b.bottom)
        val inter = max(0, r - l) * max(0, bot - t)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union == 0) 0f else inter.toFloat() / union
    }
}
