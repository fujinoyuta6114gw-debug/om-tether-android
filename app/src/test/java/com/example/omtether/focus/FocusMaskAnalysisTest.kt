package com.example.omtether.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMaskAnalysisTest {
    @Test
    fun higherSensitivityIncludesMoreModerateEdges() {
        val histogram = IntArray(256).apply {
            this[40] = 80
            this[120] = 15
            this[220] = 5
        }

        val strictThreshold = FocusMaskAnalysis.thresholdForHistogram(histogram, 0f)
        val sensitiveThreshold = FocusMaskAnalysis.thresholdForHistogram(histogram, 1f)

        assertTrue(strictThreshold >= sensitiveThreshold)
        assertEquals(220, strictThreshold)
        assertEquals(120, sensitiveThreshold)
    }

    @Test
    fun thresholdNeverFallsBelowNoiseFloor() {
        val histogram = IntArray(256).apply { this[3] = 100 }

        assertEquals(24, FocusMaskAnalysis.thresholdForHistogram(histogram, 1f))
    }
}
