package com.example.omtether.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PtpCodecTest {
    @Test
    fun commandUsesLittleEndianPtpUsbHeader() {
        val bytes = PtpCodec.command(Ptp.OPEN_SESSION, 7L, listOf(1L))
        assertEquals(16, bytes.size)
        assertArrayEquals(
            byteArrayOf(
                0x10, 0x00, 0x00, 0x00,
                0x01, 0x00,
                0x02, 0x10,
                0x07, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x00, 0x00,
            ),
            bytes,
        )
    }

    @Test
    fun deviceInfoParsesStringsAndCapabilityArrays() {
        val out = ByteArrayOutputStream()
        out.u16(100)
        out.u32(6)
        out.u16(100)
        out.ptpString("vendor")
        out.u16(0)
        out.u16Array(listOf(Ptp.GET_DEVICE_INFO, Ptp.OMD_CAPTURE, Ptp.OMD_GET_LIVE_VIEW_IMAGE))
        out.u16Array(listOf(0x4002, 0xC102))
        out.u16Array(listOf(Ptp.PROP_ISO, Ptp.PROP_LIVE_VIEW_MODE))
        out.u16Array(emptyList())
        out.u16Array(listOf(0x3801))
        out.ptpString("OMSYSTEM")
        out.ptpString("OM-1MarkII")
        out.ptpString("1.2")
        out.ptpString("ABC123")

        val parsed = PtpDatasetParser.deviceInfo(out.toByteArray())
        assertEquals("OMSYSTEM", parsed.manufacturer)
        assertEquals("OM-1MarkII", parsed.model)
        assertTrue(Ptp.OMD_CAPTURE in parsed.operations)
        assertTrue(0xC102 in parsed.events)
        assertTrue(Ptp.PROP_LIVE_VIEW_MODE in parsed.properties)
    }

    @Test
    fun propertyDescriptorKeepsCameraAdvertisedEnumValues() {
        val out = ByteArrayOutputStream()
        out.u16(Ptp.PROP_ISO)
        out.u16(Ptp.TYPE_UINT16)
        out.write(1)
        out.u16(200)
        out.u16(400)
        out.write(Ptp.FORM_ENUMERATION)
        out.u16(3)
        out.u16(200)
        out.u16(400)
        out.u16(800)

        val descriptor = PtpDatasetParser.propertyDescriptor(out.toByteArray())
        assertTrue(descriptor.writable)
        assertEquals(400L, descriptor.current.raw)
        assertEquals(listOf(200L, 400L, 800L), descriptor.values.map { it.raw })
    }

    @Test
    fun objectHandleDatasetUsesUnsigned32BitValues() {
        val out = ByteArrayOutputStream()
        out.u32(2)
        out.u32(1)
        out.u32(0xF000_0001L)
        assertEquals(listOf(1L, 0xF000_0001L), PtpDatasetParser.objectHandles(out.toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedContainerIsRejectedBeforeAllocation() {
        PtpCodec.declaredLength(byteArrayOf(0x01, 0x00, 0x00, 0x20))
    }

    @Test
    fun exposureControlIsDisabledWithoutAnAdvertisedChoice() {
        val scalar = PtpScalar(Ptp.TYPE_UINT16, 200)
        val descriptor = PtpPropertyDescriptor(
            code = Ptp.PROP_ISO,
            dataType = Ptp.TYPE_UINT16,
            writable = true,
            factoryDefault = scalar,
            current = scalar,
            form = Ptp.FORM_NONE,
        )
        assertFalse(ExposureFormatter.toControl("ISO", descriptor).writable)
    }
}

private fun ByteArrayOutputStream.u16(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
}

private fun ByteArrayOutputStream.u32(value: Long) {
    repeat(4) { shift -> write(((value ushr (shift * 8)) and 0xFF).toInt()) }
}

private fun ByteArrayOutputStream.u16Array(values: List<Int>) {
    u32(values.size.toLong())
    values.forEach(::u16)
}

private fun ByteArrayOutputStream.ptpString(value: String) {
    write(value.length + 1)
    value.forEach { u16(it.code) }
    u16(0)
}
