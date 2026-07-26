package com.example.omtether.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraObjectTrackerTest {
    @Test
    fun existingCardContentsBecomeBaselineWithoutImport() {
        val tracker = CameraObjectTracker()

        assertFalse(tracker.observeSnapshot(listOf(1L, 2L, 3L), queueForImport = true))
        assertEquals(emptyList<Long>(), tracker.takePending())
    }

    @Test
    fun interruptAndPollingMergeRawJpegCompanionsOnce() {
        val tracker = CameraObjectTracker()
        tracker.initialize(listOf(10L))

        assertTrue(tracker.recordEvent(11L, queueForImport = true))
        assertTrue(tracker.observeSnapshot(listOf(10L, 11L, 12L), queueForImport = true))
        assertFalse(tracker.recordEvent(12L, queueForImport = true))
        assertEquals(listOf(11L, 12L), tracker.takePending())
        assertEquals(emptyList<Long>(), tracker.takePending())
    }

    @Test
    fun appInitiatedCaptureIsMarkedKnownButNotQueued() {
        val tracker = CameraObjectTracker()
        tracker.initialize(listOf(20L))

        assertFalse(tracker.recordEvent(21L, queueForImport = false))
        assertFalse(tracker.observeSnapshot(listOf(20L, 21L), queueForImport = true))
        assertEquals(emptyList<Long>(), tracker.takePending())
    }

    @Test
    fun eventBeforeFirstSnapshotIsRetainedWithoutImportingOldFiles() {
        val tracker = CameraObjectTracker()

        assertTrue(tracker.recordEvent(31L, queueForImport = true))
        assertFalse(tracker.observeSnapshot(listOf(1L, 2L, 31L), queueForImport = true))
        assertEquals(listOf(31L), tracker.takePending())
    }
}
