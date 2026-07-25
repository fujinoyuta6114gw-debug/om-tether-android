package com.example.omtether.camera

import kotlinx.coroutines.flow.Flow
import java.util.Locale

interface CameraController {
    val frames: Flow<ByteArray>

    suspend fun connect(): CameraSession
    suspend fun startLiveView()
    suspend fun stopLiveView()
    suspend fun capture(
        phoneSaveFormat: PhoneSaveFormat,
        onPreview: suspend (ByteArray) -> Unit,
        onObject: suspend (DownloadedObject) -> Unit,
    ): CaptureReport
    suspend fun setExposure(propertyCode: Int, value: PtpScalar): List<ExposureControl>
    suspend fun disconnect()
    fun forceClose()
    fun diagnosticsText(): String
}

enum class PhoneSaveFormat {
    JPEG,
    RAW,
}

/**
 * Selects one camera object for the Android copy without assuming a card slot.
 *
 * GetObjectHandles is requested across all storages, so [PtpObjectInfo.storageId] may identify
 * card 1 or card 2. If both cards contain the requested type, the largest object
 * is preferred and no duplicate Android copy is created.
 */
object CaptureSavePolicy {
    fun isJpeg(info: PtpObjectInfo): Boolean =
        info.format == JPEG_OBJECT_FORMAT ||
            info.filename.endsWith(".jpg", ignoreCase = true) ||
            info.filename.endsWith(".jpeg", ignoreCase = true)

    fun isRaw(info: PtpObjectInfo): Boolean =
        info.filename.endsWith(".orf", ignoreCase = true)

    fun selectPreferred(
        format: PhoneSaveFormat,
        candidates: List<PtpObjectInfo>,
    ): PtpObjectInfo? = orderedPreferred(format, candidates).firstOrNull()

    fun orderedPreferred(
        format: PhoneSaveFormat,
        candidates: List<PtpObjectInfo>,
    ): List<PtpObjectInfo> = candidates
        .asSequence()
        .filter { info ->
            when (format) {
                PhoneSaveFormat.JPEG -> isJpeg(info)
                PhoneSaveFormat.RAW -> isRaw(info)
            }
        }
        .sortedWith(
            compareByDescending<PtpObjectInfo> { it.compressedSize }
                .thenByDescending { it.imageWidth * it.imageHeight }
                .thenByDescending { it.storageId },
        )
        .toList()

    const val JPEG_OBJECT_FORMAT = 0x3801
}

object ExposureFormatter {
    data class Definition(
        val title: String,
        val candidates: List<Int>,
    )

    val definitions = listOf(
        Definition("絞り", listOf(Ptp.PROP_APERTURE, Ptp.PROP_STANDARD_F_NUMBER)),
        Definition("シャッター", listOf(Ptp.PROP_SHUTTER_SPEED, Ptp.PROP_STANDARD_EXPOSURE_TIME)),
        Definition("ISO", listOf(Ptp.PROP_ISO, Ptp.PROP_STANDARD_EXPOSURE_INDEX)),
        Definition("露出補正", listOf(Ptp.PROP_EXPOSURE_COMPENSATION, Ptp.PROP_STANDARD_EXPOSURE_BIAS)),
        Definition("WB", listOf(Ptp.PROP_WHITE_BALANCE, Ptp.PROP_STANDARD_WHITE_BALANCE)),
    )

    fun toControl(title: String, descriptor: PtpPropertyDescriptor): ExposureControl {
        val rawValues = when (descriptor.form) {
            Ptp.FORM_ENUMERATION -> descriptor.values
            Ptp.FORM_RANGE -> expandRange(descriptor)
            else -> listOf(descriptor.current)
        }.distinctBy { it.raw }

        return ExposureControl(
            propertyCode = descriptor.code,
            title = title,
            current = descriptor.current,
            options = rawValues.map { ExposureOption(it, format(descriptor.code, it.raw)) },
            writable = descriptor.writable && rawValues.size > 1,
        )
    }

    fun format(code: Int, raw: Long): String = when (code) {
        Ptp.PROP_APERTURE -> {
            if (raw == 0L) "Auto" else "f/%.1f".format(Locale.US, raw / 10.0)
        }
        Ptp.PROP_STANDARD_F_NUMBER -> {
            if (raw == 0L) "Auto" else "f/%.1f".format(Locale.US, raw / 100.0)
        }
        Ptp.PROP_STANDARD_EXPOSURE_TIME -> formatStandardExposureTime(raw)
        Ptp.PROP_SHUTTER_SPEED -> formatOlympusShutter(raw)
        Ptp.PROP_ISO -> when (raw and 0xFFFFL) {
            0xFFFFL -> "AUTO"
            0xFFFDL -> "LOW"
            else -> "ISO ${raw and 0xFFFFL}"
        }
        Ptp.PROP_STANDARD_EXPOSURE_INDEX -> if (raw == 0L) "AUTO" else "ISO $raw"
        Ptp.PROP_EXPOSURE_COMPENSATION -> {
            val signed = (raw and 0xFFFFL).toShort().toInt()
            "%+.1f EV".format(Locale.US, signed / 1000.0)
        }
        Ptp.PROP_STANDARD_EXPOSURE_BIAS -> {
            val signed = (raw and 0xFFFFL).toShort().toInt()
            "%+.1f EV".format(Locale.US, signed / 1000.0)
        }
        Ptp.PROP_WHITE_BALANCE -> when (raw and 0xFFFFL) {
            1L -> "AUTO"
            2L -> "Daylight"
            3L -> "Shade"
            4L -> "Cloudy"
            5L -> "Tungsten"
            6L -> "Fluorescent"
            7L -> "Underwater"
            8L -> "Flash"
            9L -> "Preset 1"
            10L -> "Preset 2"
            11L -> "Preset 3"
            12L -> "Preset 4"
            13L -> "Custom"
            else -> rawLabel(raw)
        }
        Ptp.PROP_STANDARD_WHITE_BALANCE -> when (raw) {
            1L -> "Manual"
            2L -> "AUTO"
            3L -> "One-touch"
            4L -> "Daylight"
            5L -> "Fluorescent"
            6L -> "Tungsten"
            7L -> "Flash"
            else -> rawLabel(raw)
        }
        else -> rawLabel(raw)
    }

    private fun expandRange(descriptor: PtpPropertyDescriptor): List<PtpScalar> {
        val minimum = descriptor.minimum?.raw ?: return listOf(descriptor.current)
        val maximum = descriptor.maximum?.raw ?: return listOf(descriptor.current)
        val step = descriptor.step?.raw ?: return listOf(descriptor.current)
        if (step <= 0L || maximum < minimum) return listOf(descriptor.current)
        val count = ((maximum - minimum) / step) + 1
        if (count !in 2L..256L) return listOf(descriptor.current)
        return List(count.toInt()) { index -> PtpScalar(descriptor.dataType, minimum + step * index) }
    }

    private fun formatStandardExposureTime(raw: Long): String {
        if (raw <= 0L) return "Bulb/Auto"
        val seconds = raw / 10_000.0
        return when {
            seconds >= 1.0 -> if (seconds % 1.0 == 0.0) "${seconds.toInt()} s" else "%.1f s".format(Locale.US, seconds)
            seconds > 0.0 -> "1/${(1.0 / seconds).toInt().coerceAtLeast(1)}"
            else -> rawLabel(raw)
        }
    }

    private fun formatOlympusShutter(raw: Long): String {
        val packed = raw and 0xFFFF_FFFFL
        when (packed) {
            0xFFFF_FFFCL -> return "Bulb"
            0xFFFF_FFFBL -> return "Time"
            0xFFFF_FFFAL -> return "Composite"
        }
        var numerator = ((packed ushr 16) and 0xFFFFL).toInt()
        var denominator = (packed and 0xFFFFL).toInt()
        if (numerator == 0 || denominator == 0) return rawLabel(raw)

        // OM bodies commonly encode 1/125 as 10/1250. Reduce the shared decimal
        // factor before presenting it.
        while (numerator % 10 == 0 && denominator % 10 == 0) {
            numerator /= 10
            denominator /= 10
        }
        return when {
            denominator == 1 -> "$numerator s"
            numerator < denominator -> "$numerator/$denominator"
            else -> {
                val seconds = numerator.toDouble() / denominator
                if (seconds % 1.0 == 0.0) "${seconds.toInt()} s" else "%.1f s".format(Locale.US, seconds)
            }
        }
    }

    private fun rawLabel(raw: Long): String = "$raw · ${Ptp.hex32(raw)}"
}
