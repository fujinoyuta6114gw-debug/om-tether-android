package com.example.omtether.focus

import kotlin.math.max
import kotlin.math.min

data class FocusFitGeometry(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val fittedWidth: Float,
    val fittedHeight: Float,
    val fitScale: Float,
)

data class NormalizedFocusPoint(
    val x: Float,
    val y: Float,
)

data class FocusTranslation(
    val x: Float,
    val y: Float,
)

/**
 * Geometry shared by the focus-review gestures and unit tests.
 *
 * [zoom] is relative to ContentScale.Fit. A zoom where `fitScale * zoom == 1` maps one retained
 * review-image pixel to one display pixel (the focus screen's 100% view).
 */
object FocusViewportMath {
    fun fitGeometry(
        viewportWidth: Int,
        viewportHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): FocusFitGeometry {
        val safeViewportWidth = viewportWidth.coerceAtLeast(1).toFloat()
        val safeViewportHeight = viewportHeight.coerceAtLeast(1).toFloat()
        val safeImageWidth = imageWidth.coerceAtLeast(1).toFloat()
        val safeImageHeight = imageHeight.coerceAtLeast(1).toFloat()
        val fitScale = min(
            safeViewportWidth / safeImageWidth,
            safeViewportHeight / safeImageHeight,
        )
        return FocusFitGeometry(
            viewportWidth = safeViewportWidth,
            viewportHeight = safeViewportHeight,
            fittedWidth = safeImageWidth * fitScale,
            fittedHeight = safeImageHeight * fitScale,
            fitScale = fitScale,
        )
    }

    fun oneToOneZoom(geometry: FocusFitGeometry): Float =
        (1f / geometry.fitScale.coerceAtLeast(0.0001f)).coerceAtLeast(0.05f)

    fun clampCenter(
        center: NormalizedFocusPoint,
        geometry: FocusFitGeometry,
        zoom: Float,
    ): NormalizedFocusPoint {
        val safeZoom = zoom.coerceAtLeast(0.0001f)
        val halfVisibleX = geometry.viewportWidth /
            (2f * geometry.fittedWidth.coerceAtLeast(1f) * safeZoom)
        val halfVisibleY = geometry.viewportHeight /
            (2f * geometry.fittedHeight.coerceAtLeast(1f) * safeZoom)
        return NormalizedFocusPoint(
            x = clampAxis(center.x, halfVisibleX),
            y = clampAxis(center.y, halfVisibleY),
        )
    }

    fun translationForCenter(
        center: NormalizedFocusPoint,
        geometry: FocusFitGeometry,
        zoom: Float,
    ): FocusTranslation = FocusTranslation(
        x = -(center.x - 0.5f) * geometry.fittedWidth * zoom,
        y = -(center.y - 0.5f) * geometry.fittedHeight * zoom,
    )

    fun panCenter(
        center: NormalizedFocusPoint,
        panX: Float,
        panY: Float,
        geometry: FocusFitGeometry,
        zoom: Float,
    ): NormalizedFocusPoint {
        val safeZoom = zoom.coerceAtLeast(0.0001f)
        val moved = NormalizedFocusPoint(
            x = center.x - panX / (geometry.fittedWidth.coerceAtLeast(1f) * safeZoom),
            y = center.y - panY / (geometry.fittedHeight.coerceAtLeast(1f) * safeZoom),
        )
        return clampCenter(moved, geometry, safeZoom)
    }

    fun normalizedPointAtViewport(
        viewportX: Float,
        viewportY: Float,
        center: NormalizedFocusPoint,
        geometry: FocusFitGeometry,
        zoom: Float,
    ): NormalizedFocusPoint? {
        val safeZoom = zoom.coerceAtLeast(0.0001f)
        val translation = translationForCenter(center, geometry, safeZoom)
        val fittedX = (
            viewportX - geometry.viewportWidth / 2f - translation.x
            ) / safeZoom + geometry.fittedWidth / 2f
        val fittedY = (
            viewportY - geometry.viewportHeight / 2f - translation.y
            ) / safeZoom + geometry.fittedHeight / 2f
        val normalized = NormalizedFocusPoint(
            x = fittedX / geometry.fittedWidth.coerceAtLeast(1f),
            y = fittedY / geometry.fittedHeight.coerceAtLeast(1f),
        )
        return normalized.takeIf { it.x in 0f..1f && it.y in 0f..1f }
    }

    fun displayedPixelPercent(geometry: FocusFitGeometry, zoom: Float): Int =
        (geometry.fitScale * zoom * 100f).toInt().coerceAtLeast(1)

    private fun clampAxis(value: Float, halfVisible: Float): Float {
        if (halfVisible >= 0.5f) return 0.5f
        return value.coerceIn(
            min(halfVisible, 0.5f),
            max(1f - halfVisible, 0.5f),
        )
    }
}
