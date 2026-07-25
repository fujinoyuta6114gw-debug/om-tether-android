package com.example.omtether.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

object Ptp {
    const val CONTAINER_COMMAND = 1
    const val CONTAINER_DATA = 2
    const val CONTAINER_RESPONSE = 3
    const val CONTAINER_EVENT = 4

    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016

    const val OMD_CAPTURE = 0x9481
    const val OMD_GET_LIVE_VIEW_IMAGE = 0x9484
    const val OMD_GET_IMAGE = 0x9485
    const val OMD_CHANGED_PROPERTIES = 0x9486

    const val PROP_APERTURE = 0xD002
    const val PROP_ISO = 0xD007
    const val PROP_EXPOSURE_COMPENSATION = 0xD008
    const val PROP_IMAGE_FORMAT = 0xD00D
    const val PROP_SHUTTER_SPEED = 0xD01C
    const val PROP_WHITE_BALANCE = 0xD01E
    const val PROP_PC_MODE = 0xD052
    const val PROP_LIVE_VIEW_MODE = 0xD06D
    const val PROP_CAPTURE_TARGET = 0xD0DC

    const val PROP_STANDARD_WHITE_BALANCE = 0x5005
    const val PROP_STANDARD_F_NUMBER = 0x5007
    const val PROP_STANDARD_EXPOSURE_TIME = 0x500D
    const val PROP_STANDARD_EXPOSURE_INDEX = 0x500F
    const val PROP_STANDARD_EXPOSURE_BIAS = 0x5010

    const val RESPONSE_OK = 0x2001
    const val RESPONSE_INVALID_OBJECT_HANDLE = 0x2009
    const val RESPONSE_SESSION_ALREADY_OPEN = 0x201E
    const val RESPONSE_DEVICE_BUSY = 0x2019

    const val EVENT_OBJECT_ADDED = 0x4002
    const val EVENT_OLYMPUS_OBJECT_ADDED = 0xC002
    const val EVENT_OM_OBJECT_ADDED_NEW = 0xC102

    const val TYPE_INT8 = 0x0001
    const val TYPE_UINT8 = 0x0002
    const val TYPE_INT16 = 0x0003
    const val TYPE_UINT16 = 0x0004
    const val TYPE_INT32 = 0x0005
    const val TYPE_UINT32 = 0x0006

    const val FORM_NONE = 0
    const val FORM_RANGE = 1
    const val FORM_ENUMERATION = 2

    const val OM1_MARK_II_VENDOR_ID = 0x33A2
    const val OM1_MARK_II_PRODUCT_ID = 0x0136

    const val LIVE_VIEW_ENABLED_VALUE = 0x04000300L
    const val MAX_CONTAINER_BYTES = 128 * 1024 * 1024

    fun hex16(value: Int): String = "0x%04X".format(Locale.US, value and 0xFFFF)
    fun hex32(value: Long): String = "0x%08X".format(Locale.US, value and 0xFFFF_FFFFL)

    fun responseName(code: Int): String = when (code) {
        RESPONSE_OK -> "OK"
        0x2002 -> "GeneralError"
        0x2005 -> "OperationNotSupported"
        0x2009 -> "InvalidObjectHandle"
        0x200F -> "AccessDenied"
        RESPONSE_DEVICE_BUSY -> "DeviceBusy"
        RESPONSE_SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        else -> "Response(${hex16(code)})"
    }
}

data class PtpContainer(
    val type: Int,
    val code: Int,
    val transactionId: Long,
    val payload: ByteArray,
)

data class PtpResult(
    val responseCode: Int,
    val transactionId: Long,
    val data: ByteArray?,
    val responseParameters: List<Long>,
)

class PtpException(
    val responseCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class PtpScalar(
    val dataType: Int,
    val raw: Long,
) {
    fun encode(): ByteArray = when (dataType) {
        Ptp.TYPE_INT8, Ptp.TYPE_UINT8 -> byteArrayOf(raw.toByte())
        Ptp.TYPE_INT16, Ptp.TYPE_UINT16 -> ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(raw.toShort())
            .array()
        Ptp.TYPE_INT32, Ptp.TYPE_UINT32 -> ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(raw.toInt())
            .array()
        else -> throw IllegalArgumentException("Unsupported scalar PTP type ${Ptp.hex16(dataType)}")
    }
}

data class PtpDeviceInfo(
    val vendorExtensionId: Long,
    val operations: Set<Int>,
    val events: Set<Int>,
    val properties: Set<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
)

data class PtpPropertyDescriptor(
    val code: Int,
    val dataType: Int,
    val writable: Boolean,
    val factoryDefault: PtpScalar,
    val current: PtpScalar,
    val form: Int,
    val minimum: PtpScalar? = null,
    val maximum: PtpScalar? = null,
    val step: PtpScalar? = null,
    val values: List<PtpScalar> = emptyList(),
)

data class PtpObjectInfo(
    val storageId: Long,
    val format: Int,
    val compressedSize: Long,
    val thumbFormat: Int,
    val thumbSize: Long,
    val imageWidth: Long,
    val imageHeight: Long,
    val filename: String,
    val captureDate: String,
)

data class CameraIdentity(
    val manufacturer: String,
    val model: String,
    val serialNumber: String,
    val usbVendorId: Int,
    val usbProductId: Int,
) {
    val displayName: String
        get() = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "OM‑1 Mark II" }
}

data class ExposureOption(
    val value: PtpScalar,
    val label: String,
)

data class ExposureControl(
    val propertyCode: Int,
    val title: String,
    val current: PtpScalar,
    val options: List<ExposureOption>,
    val writable: Boolean,
)

data class CameraSession(
    val identity: CameraIdentity,
    val exposureControls: List<ExposureControl>,
)

data class DownloadedObject(
    val handle: Long?,
    val filename: String,
    val format: Int,
    val bytes: ByteArray,
    val previewJpeg: ByteArray? = null,
)

data class CaptureObjectSummary(
    val handle: Long?,
    val filename: String,
    val format: Int,
    val byteCount: Int,
)

data class CaptureReport(
    val objects: List<CaptureObjectSummary>,
    val warnings: List<String> = emptyList(),
)
