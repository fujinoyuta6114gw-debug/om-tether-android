package com.example.omtether.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraStorageSlotTest {
    @Test
    fun mapsPtpPhysicalStorageToOmCardSlot() {
        assertEquals(1, CameraStorageSlot.fromStorageId(0x0001_0001L))
        assertEquals(2, CameraStorageSlot.fromStorageId(0x0002_0001L))
    }

    @Test
    fun doesNotGuessUnknownStorageLayouts() {
        assertNull(CameraStorageSlot.fromStorageId(null))
        assertNull(CameraStorageSlot.fromStorageId(0x0000_0001L))
        assertNull(CameraStorageSlot.fromStorageId(0x0003_0001L))
        assertEquals("カード不明（0x00030001）", CameraStorageSlot.label(0x0003_0001L))
    }
}
