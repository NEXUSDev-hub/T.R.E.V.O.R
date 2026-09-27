package com.trevor.assistant

import android.graphics.Rect
import java.util.Locale
import kotlin.math.min

enum class TrevorScreenKind { APP_UI, GAME_2D, GAME_3D, MIXED, UNKNOWN }
enum class TrevorSceneLayerType { UI, HUD, GAME_SCENE_2D, GAME_SCENE_3D, INTERACTIVE_CONTROL, TEXT, UNKNOWN }

data class TrevorSceneItem(
    val layer: TrevorSceneLayerType,
    val label: String = "",
    val bounds: Rect? = null,
    val confidence: Float = 0f,
    val evidence: String = ""
)

data class TrevorSceneDescription(
    val screenKind: TrevorScreenKind,
    val confidence: Float,
    val items: List<TrevorSceneItem>,
    val description: String
)

/**
 * Generic offline visual interpreter. No package names, game names, fixed coordinates,
 * or application-specific labels are used. It combines accessibility, OCR and local
 * visual geometry and reports uncertainty rather than inventing object identities.
 */
object WhatCanYouSee {
    fun describe(o: TrevorScreenObservation): TrevorSceneDescription {
        val w = o.width.coerceAtLeast(1)
        val h = o.height.coerceAtLeast(1)
        val items = mutableListOf<TrevorSceneItem>()

        o.elements.filter { it.text.isNotBlank() || it.description.isNotBlank() }
            .take(80).forEach { e ->
                val label = e.text.ifBlank { e.description }.trim()
                val layer = when {
                    e.clickable || e.scrollable -> TrevorSceneLayerType.INTERACTIVE_CONTROL
                    e.bounds.top < h * .22f || e.bounds.bottom > h * .78f -> TrevorSceneLayerType.HUD
                    else -> TrevorSceneLayerType.TEXT
                }
                items += TrevorSceneItem(
                    layer, label.take(120), Rect(e.bounds),
                    if (e.source == "accessibility") .90f else .72f, e.source
                )
            }

        o.visualControls.take(80).forEach { c ->
            items += TrevorSceneItem(
                TrevorSceneLayerType.INTERACTIVE_CONTROL,
                c.label.takeIf { it.isNotBlank() }.orEmpty(),
                Rect(c.bounds), min(.99f, c.confidence), c.source
            )
        }

        val controls = o.visualControls.size
        val labeled = o.visualControls.count { it.label.isNotBlank() }
        val semanticControls = o.elements.count { it.clickable || it.scrollable }
        val largeRegions = o.visualControls.count {
            it.bounds.width() > w * .22f && it.bounds.height() > h * .12f
        }
        val uiSignal = min(.55f, semanticControls * .08f + labeled * .04f)
        val sceneSignal = min(.40f, largeRegions * .08f + if (labeled == 0) .10f else 0f)

        val kind = when {
            uiSignal >= .38f && sceneSignal >= .18f -> TrevorScreenKind.MIXED
            uiSignal >= .38f -> TrevorScreenKind.APP_UI
            sceneSignal >= .18f -> {
                val depth = perspectiveCue(o, w)
                if (depth >= .55f) TrevorScreenKind.GAME_3D else TrevorScreenKind.GAME_2D
            }
            else -> TrevorScreenKind.UNKNOWN
        }
        val confidence = when (kind) {
            TrevorScreenKind.MIXED -> min(.92f, .55f + uiSignal * .4f + sceneSignal * .3f)
            TrevorScreenKind.APP_UI -> min(.90f, .50f + uiSignal * .7f)
            TrevorScreenKind.GAME_2D, TrevorScreenKind.GAME_3D -> min(.82f, .42f + sceneSignal)
            TrevorScreenKind.UNKNOWN -> .35f
        }

        if (sceneSignal > 0f) {
            items += TrevorSceneItem(
                if (kind == TrevorScreenKind.GAME_3D) TrevorSceneLayerType.GAME_SCENE_3D
                else TrevorSceneLayerType.GAME_SCENE_2D,
                confidence = sceneSignal,
                evidence = "local visual structure"
            )
        }

        val kindText = when (kind) {
            TrevorScreenKind.GAME_3D -> "a likely 3D game scene"
            TrevorScreenKind.GAME_2D -> "a likely 2D game scene"
            TrevorScreenKind.MIXED -> "a mixed application/game screen"
            TrevorScreenKind.APP_UI -> "an application UI"
            TrevorScreenKind.UNKNOWN -> "a screen whose type is uncertain"
        }
        val confidenceText = String.format(Locale.ROOT, "%.0f%%", confidence * 100f)
        val hud = items.count { it.layer == TrevorSceneLayerType.HUD }
        val text = items.count { it.layer == TrevorSceneLayerType.TEXT }
        return TrevorSceneDescription(
            kind, confidence, items.take(140),
            "I can see $kindText. I detected $controls possible visual controls, " +
                "$hud HUD-like elements, and $text text elements. " +
                "Visual classification confidence is $confidenceText."
        )
    }

    private fun perspectiveCue(o: TrevorScreenObservation, w: Int): Float {
        val boxes = o.visualControls.map { it.bounds }.filter { it.width() > 0 && it.height() > 0 }
        if (boxes.size < 3) return 0f
        val center = w / 2f
        val spread = boxes.map { kotlin.math.abs(it.centerX() - center) / w }.average()
        val sizes = boxes.map { it.width().toFloat() / w }
        val variance = (sizes.maxOrNull()!! - sizes.minOrNull()!!).coerceIn(0f, 1f)
        return (spread * .8f + variance * .8f).coerceIn(0f, 1f)
    }
}
