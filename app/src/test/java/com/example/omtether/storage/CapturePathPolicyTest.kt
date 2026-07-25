package com.example.omtether.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CapturePathPolicyTest {
    @Test
    fun `places every capture under dedicated dated folder`() {
        assertEquals(
            "Pictures/OM Tether/2026-07-26/",
            CapturePathPolicy.relativePath("2026-07-26"),
        )
    }

    @Test
    fun `rejects malformed folder dates`() {
        assertThrows(IllegalArgumentException::class.java) {
            CapturePathPolicy.relativePath("../other")
        }
    }
}
