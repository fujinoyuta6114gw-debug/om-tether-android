package com.example.omtether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferProgressEstimatorTest {
    @Test
    fun estimatesRemainingTimeAfterMeasurementWindow() {
        val estimator = TransferProgressEstimator()
        estimator.update(SaveProgressStage.DOWNLOADING, "image.orf", 0L, 10_000L, 1_000L)

        val progress = estimator.update(
            SaveProgressStage.DOWNLOADING,
            "image.orf",
            4_000L,
            10_000L,
            2_000L,
        )

        assertEquals(4_000L, progress.bytesPerSecond)
        assertEquals(2, progress.remainingSeconds)
        assertEquals(0.4f, progress.fraction!!, 0.001f)
    }

    @Test
    fun hidesEstimateUntilEnoughTimeHasElapsed() {
        val estimator = TransferProgressEstimator()
        estimator.update(SaveProgressStage.WRITING, "image.jpg", 0L, 1_000L, 1_000L)

        val progress = estimator.update(
            SaveProgressStage.WRITING,
            "image.jpg",
            500L,
            1_000L,
            1_200L,
        )

        assertNull(progress.bytesPerSecond)
        assertNull(progress.remainingSeconds)
    }

    @Test
    fun resetsEstimateWhenStageChanges() {
        val estimator = TransferProgressEstimator()
        estimator.update(SaveProgressStage.DOWNLOADING, "image.orf", 0L, 10_000L, 1_000L)
        estimator.update(SaveProgressStage.DOWNLOADING, "image.orf", 5_000L, 10_000L, 2_000L)

        val writing = estimator.update(
            SaveProgressStage.WRITING,
            "image.orf",
            0L,
            10_000L,
            2_100L,
        )

        assertNull(writing.bytesPerSecond)
        assertNull(writing.remainingSeconds)
    }
}
