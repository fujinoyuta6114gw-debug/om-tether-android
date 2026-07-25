package com.example.omtether.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PtpCodec {
    fun command(code: Int, transactionId: Long, parameters: List<Long>): ByteArray {
        require(parameters.size <= 5) { "PTP permits at most five command parameters" }
        val buffer = ByteBuffer.allocate(12 + parameters.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(buffer.capacity())
        buffer.putShort(Ptp.CONTAINER_COMMAND.toShort())
        buffer.putShort(code.toShort())
        buffer.putInt(transactionId.toInt())
        parameters.forEach { buffer.putInt(it.toInt()) }
        return buffer.array()
    }

    fun data(code: Int, transactionId: Long, payload: ByteArray): ByteArray {
        require(payload.size <= Ptp.MAX_CONTAINER_BYTES - 12)
        val buffer = ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(buffer.capacity())
        buffer.putShort(Ptp.CONTAINER_DATA.toShort())
        buffer.putShort(code.toShort())
        buffer.putInt(transactionId.toInt())
        buffer.put(payload)
        return buffer.array()
    }

    fun declaredLength(headerOrContainer: ByteArray): Int {
        require(headerOrContainer.size >= 4)
        val value = (headerOrContainer[0].toInt() and 0xFF) or
            ((headerOrContainer[1].toInt() and 0xFF) shl 8) or
            ((headerOrContainer[2].toInt() and 0xFF) shl 16) or
            ((headerOrContainer[3].toInt() and 0xFF) shl 24)
        require(value in 12..Ptp.MAX_CONTAINER_BYTES) { "Invalid PTP container length $value" }
        return value
    }

    fun decode(container: ByteArray): PtpContainer {
        val expected = declaredLength(container)
        require(expected == container.size) {
            "PTP container length mismatch: header=$expected actual=${container.size}"
        }
        val cursor = PtpCursor(container)
        cursor.u32()
        val type = cursor.u16()
        val code = cursor.u16()
        val transactionId = cursor.u32()
        return PtpContainer(type, code, transactionId, cursor.bytes(cursor.remaining))
    }

    fun responseParameters(payload: ByteArray): List<Long> {
        require(payload.size % 4 == 0)
        val cursor = PtpCursor(payload)
        return buildList {
            while (cursor.remaining > 0) add(cursor.u32())
        }
    }
}

object PtpDatasetParser {
    fun deviceInfo(bytes: ByteArray): PtpDeviceInfo {
        val cursor = PtpCursor(bytes)
        cursor.u16() // StandardVersion
        val vendorExtensionId = cursor.u32()
        cursor.u16() // VendorExtensionVersion
        cursor.ptpString() // VendorExtensionDesc
        cursor.u16() // FunctionalMode
        val operations = cursor.u16Array().toSet()
        val events = cursor.u16Array().toSet()
        val properties = cursor.u16Array().toSet()
        cursor.u16Array() // CaptureFormats
        cursor.u16Array() // ImageFormats
        return PtpDeviceInfo(
            vendorExtensionId = vendorExtensionId,
            operations = operations,
            events = events,
            properties = properties,
            manufacturer = cursor.ptpString(),
            model = cursor.ptpString(),
            deviceVersion = cursor.ptpString(),
            serialNumber = cursor.ptpString(),
        )
    }

    fun propertyDescriptor(bytes: ByteArray): PtpPropertyDescriptor {
        val cursor = PtpCursor(bytes)
        val code = cursor.u16()
        val dataType = cursor.u16()
        val writable = cursor.u8() != 0
        val factoryDefault = cursor.scalar(dataType)
        val current = cursor.scalar(dataType)
        val form = cursor.u8()
        return when (form) {
            Ptp.FORM_NONE -> PtpPropertyDescriptor(
                code, dataType, writable, factoryDefault, current, form,
            )
            Ptp.FORM_RANGE -> PtpPropertyDescriptor(
                code = code,
                dataType = dataType,
                writable = writable,
                factoryDefault = factoryDefault,
                current = current,
                form = form,
                minimum = cursor.scalar(dataType),
                maximum = cursor.scalar(dataType),
                step = cursor.scalar(dataType),
            )
            Ptp.FORM_ENUMERATION -> {
                val count = cursor.u16()
                require(count <= 4096) { "Unreasonable PTP enumeration length $count" }
                PtpPropertyDescriptor(
                    code = code,
                    dataType = dataType,
                    writable = writable,
                    factoryDefault = factoryDefault,
                    current = current,
                    form = form,
                    values = List(count) { cursor.scalar(dataType) },
                )
            }
            else -> throw IllegalArgumentException("Unknown PTP property form $form")
        }
    }

    fun objectHandles(bytes: ByteArray): List<Long> {
        val cursor = PtpCursor(bytes)
        val count = cursor.u32()
        require(count <= 1_000_000L) { "Unreasonable PTP object handle count $count" }
        return List(count.toInt()) { cursor.u32() }
    }

    fun objectInfo(bytes: ByteArray): PtpObjectInfo {
        val cursor = PtpCursor(bytes)
        val storageId = cursor.u32()
        val format = cursor.u16()
        cursor.u16() // ProtectionStatus
        val compressedSize = cursor.u32()
        val thumbFormat = cursor.u16()
        val thumbSize = cursor.u32()
        cursor.u32() // ThumbPixWidth
        cursor.u32() // ThumbPixHeight
        val imageWidth = cursor.u32()
        val imageHeight = cursor.u32()
        cursor.u32() // ImageBitDepth
        cursor.u32() // ParentObject
        cursor.u16() // AssociationType
        cursor.u32() // AssociationDesc
        cursor.u32() // SequenceNumber
        val filename = cursor.ptpString()
        val captureDate = cursor.ptpString()
        if (cursor.remaining > 0) cursor.ptpString() // ModificationDate
        if (cursor.remaining > 0) cursor.ptpString() // Keywords
        return PtpObjectInfo(
            storageId = storageId,
            format = format,
            compressedSize = compressedSize,
            thumbFormat = thumbFormat,
            thumbSize = thumbSize,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            filename = filename,
            captureDate = captureDate,
        )
    }
}

class PtpCursor(private val source: ByteArray) {
    private var position = 0
    val remaining: Int get() = source.size - position

    fun u8(): Int {
        requireAvailable(1)
        return source[position++].toInt() and 0xFF
    }

    fun u16(): Int = u8() or (u8() shl 8)

    fun u32(): Long = (
        u8().toLong() or
            (u8().toLong() shl 8) or
            (u8().toLong() shl 16) or
            (u8().toLong() shl 24)
        ) and 0xFFFF_FFFFL

    fun bytes(count: Int): ByteArray {
        requireAvailable(count)
        return source.copyOfRange(position, position + count).also { position += count }
    }

    fun ptpString(): String {
        val countIncludingNull = u8()
        if (countIncludingNull == 0) return ""
        requireAvailable(countIncludingNull * 2)
        val result = StringBuilder(countIncludingNull - 1)
        repeat(countIncludingNull - 1) { result.append(u16().toChar()) }
        u16() // trailing UTF-16 NUL
        return result.toString()
    }

    fun u16Array(): List<Int> {
        val count = u32()
        require(count <= 65_536L) { "Unreasonable PTP array length $count" }
        return List(count.toInt()) { u16() }
    }

    fun scalar(dataType: Int): PtpScalar {
        val value = when (dataType) {
            Ptp.TYPE_INT8 -> u8().toByte().toLong()
            Ptp.TYPE_UINT8 -> u8().toLong()
            Ptp.TYPE_INT16 -> u16().toShort().toLong()
            Ptp.TYPE_UINT16 -> u16().toLong()
            Ptp.TYPE_INT32 -> u32().toInt().toLong()
            Ptp.TYPE_UINT32 -> u32()
            else -> throw IllegalArgumentException("Unsupported scalar PTP type ${Ptp.hex16(dataType)}")
        }
        return PtpScalar(dataType, value)
    }

    private fun requireAvailable(count: Int) {
        require(count >= 0 && count <= remaining) {
            "PTP dataset truncated at $position: need $count, have $remaining"
        }
    }
}
