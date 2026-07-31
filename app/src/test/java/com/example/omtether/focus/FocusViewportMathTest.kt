package com.example.omtether.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FocusViewportMathTest {
    @Test
    fun oneToOneZoom_mapsOneImagePixelToOneViewportPixel() {
        val geometry = FocusViewportMath.fitGeometry(
            viewportWidth = 1_000,
            viewportHeight = 1_000,
            imageWidth = 2_000,
            imageHeight = 1_000,
        )

        assertEquals(0.5f, geometry.fitScale, 0.0001f)
        assertEquals(2f, FocusViewportMath.oneToOneZoom(geometry), 0.0001f)
        assertEquals(100, FocusViewportMath.displayedPixelPercent(geometry, 2f))
    }

    @Test
    fun centerIsClampedWithoutShowingSpaceOutsideImage() {
        val geometry = FocusViewportMath.fitGeometry(1_000, 1_000, 2_000, 1_000)

        val clamped = FocusViewportMath.clampCenter(
            center = NormalizedFocusPoint(0.95f, 0.1f),
            geometry = geometry,
            zoom = 2f,
        )

        assertEquals(0.75f, clamped.x, 0.0001f)
        assertEquals(0.5f, clamped.y, 0.0001f)
    }

    @Test
    fun viewportCenterResolvesToSelectedImagePosition() {
        val geometry = FocusViewportMath.fitGeometry(1_000, 1_000, 2_000, 1_000)
        val center = NormalizedFocusPoint(0.7f, 0.5f)

        val resolved = FocusViewportMath.normalizedPointAtViewport(
            viewportX = 500f,
            viewportY = 500f,
            center = center,
            geometry = geometry,
            zoom = 2f,
        )

        assertNotNull(resolved)
        assertEquals(center.x, resolved!!.x, 0.0001f)
        assertEquals(center.y, resolved.y, 0.0001f)
    }
}
