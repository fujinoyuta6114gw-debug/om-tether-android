package com.example.omtether.camera

import kotlinx.coroutines.flow.Flow

interface CameraController {
    val frames: Flow<ByteArray>

    suspend fun connect(): CameraSession
    suspend fun startLiveView()
    suspend fun stopLiveView()
    suspend fun capture(
        onPreview: suspend (ByteArray) -> Unit,
        onObject: suspend (DownloadedObject) -> Unit,
    ): CaptureReport
    suspend fun setExposure(propertyCode: Int, value: PtpScalar): List<ExposureControl>
    suspend fun disconnect()
    fun forceClose()
    fun diagnosticsText(): String
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
        Ptp.PROP_APERTURE, Ptp.PROP_STANDARD_F_NUMBER -> {
            if (raw == 0L) "Auto" else if (raw in 80L..6400L) "f/%.1f".format(raw / 100.0) else rawLabel(raw)
        }
        Ptp.PROP_STANDARD_EXPOSURE_TIME -> formatStandardExposureTime(raw)
        Ptp.PROP_SHUTTER_SPEED -> rawLabel(raw)
        Ptp.PROP_ISO, Ptp.PROP_STANDARD_EXPOSURE_INDEX -> if (raw == 0L) "AUTO" else "ISO $raw"
        Ptp.PROP_EXPOSURE_COMPENSATION, Ptp.PROP_STANDARD_EXPOSURE_BIAS -> {
            if (raw in -10_000L..10_000L) "%+.1f EV".format(raw / 1000.0) else rawLabel(raw)
        }
        Ptp.PROP_WHITE_BALANCE, Ptp.PROP_STANDARD_WHITE_BALANCE -> when (raw) {
            1L -> "Manual"
            2L -> "Auto"
            3L -> "One-touch"
            4L -> "Daylight"
            5L -> "Fluorescent"
            6L -> "Tungsten"
            7L -> "Flash"
            0x8001L -> "Shade"
            0x8002L -> "Cloudy"
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
            seconds >= 1.0 -> if (seconds % 1.0 == 0.0) "${seconds.toInt()} s" else "%.1f s".format(seconds)
            seconds > 0.0 -> "1/${(1.0 / seconds).toInt()}"
            else -> rawLabel(raw)
        }
    }

    private fun rawLabel(raw: Long): String = "$raw · ${Ptp.hex32(raw)}"
}
