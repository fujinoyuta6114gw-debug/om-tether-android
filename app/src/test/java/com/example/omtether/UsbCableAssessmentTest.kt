package com.example.omtether

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbCableAssessmentTest {
    @Test
    fun usb3EndpointIsRecommended() {
        val result = assessUsbCable(listOf(512, 1024))

        assertEquals(UsbCableGrade.RECOMMENDED, result.grade)
        assertEquals(1024, result.observedMaxPacketSize)
    }

    @Test
    fun usb2EndpointIsLimitedButUsable() {
        val result = assessUsbCable(listOf(512, 512))

        assertEquals(UsbCableGrade.LIMITED, result.grade)
        assertEquals(512, result.observedMaxPacketSize)
    }

    @Test
    fun missingEndpointIsUnknown() {
        val result = assessUsbCable(emptyList())

        assertEquals(UsbCableGrade.UNKNOWN, result.grade)
    }
}
