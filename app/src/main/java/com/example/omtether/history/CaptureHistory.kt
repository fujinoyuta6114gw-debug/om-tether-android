package com.example.omtether.history

import android.graphics.Bitmap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class CaptureFileFormat {
    JPEG,
    ORF,
    UNKNOWN,
}

enum class CaptureTimeSource {
    EXIF,
    PTP_OBJECT_INFO,
    UNAVAILABLE,
}

enum class SmartphoneSaveState {
    SAVING,
    SAVED,
    FAILED,
}

/**
 * Values read from the downloaded photo itself.
 *
 * [captureTimeSource] is explicit because a camera may omit EXIF from a preview JPEG. In that
 * case only the PTP ObjectInfo timestamp is used as a clearly labelled fallback; exposure values
 * are never copied from the camera's current control state.
 */
data class PhotoExifMetadata(
    val apertureFNumber: Double? = null,
    val exposureTimeSeconds: Double? = null,
    val iso: Int? = null,
    val exposureBiasEv: Double? = null,
    val focalLengthMm: Double? = null,
    val capturedAt: String? = null,
    val captureTimeSource: CaptureTimeSource = CaptureTimeSource.UNAVAILABLE,
    val hasActualExif: Boolean = false,
)

data class CaptureHistoryItem(
    val id: String,
    val filename: String,
    val thumbnail: Bitmap?,
    /**
     * A bounded, display-oriented image used only by the focus review screen.
     *
     * The ViewModel keeps this for the newest two captures. Older history entries retain only
     * [thumbnail], so a long tethered session does not accumulate full-size Bitmaps.
     */
    val focusReviewBitmap: Bitmap?,
    val focusReviewUsesEmbeddedPreview: Boolean,
    val metadata: PhotoExifMetadata,
    val fileFormat: CaptureFileFormat,
    val isPreviewFallback: Boolean,
    val sourceStorageId: Long?,
    val sourceCardSlot: Int?,
    val smartphoneSaveState: SmartphoneSaveState,
    val savedRelativePath: String? = null,
    val savedUri: String? = null,
    val saveFailure: String? = null,
    val metadataWarning: String? = null,
)

object CameraStorageSlot {
    /**
     * PTP StorageID uses the upper 16 bits for the physical storage number.
     *
     * Only the two slot numbers exposed by the OM-1 Mark II are labelled. Unknown layouts stay
     * unknown instead of being guessed as card 1 or card 2.
     */
    fun fromStorageId(storageId: Long?): Int? {
        if (storageId == null) return null
        val physicalStorage = ((storageId and 0xFFFF_FFFFL) ushr 16).toInt() and 0xFFFF
        return physicalStorage.takeIf { it == 1 || it == 2 }
    }

    fun label(storageId: Long?, cardSlot: Int? = fromStorageId(storageId)): String = when (cardSlot) {
        1 -> "カード1"
        2 -> "カード2"
        else -> storageId?.let { "カード不明（0x%08X）".format(Locale.US, it and 0xFFFF_FFFFL) }
            ?: "カード情報なし"
    }
}

object PhotoMetadataFormatter {
    fun aperture(value: Double?): String =
        value?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { "f/%.1f".format(Locale.US, it) }
            ?: MISSING_VALUE

    fun exposureTime(seconds: Double?): String {
        val value = seconds?.takeIf { it.isFinite() && it > 0.0 } ?: return MISSING_VALUE
        if (value >= 1.0) {
            return if (abs(value - value.roundToInt()) < 0.05) {
                "${value.roundToInt()} s"
            } else {
                "%.1f s".format(Locale.US, value)
            }
        }
        if (value <= 0.5) {
            val denominator = (1.0 / value).roundToInt().coerceAtLeast(1)
            return "1/$denominator"
        }
        return "%.1f s".format(Locale.US, value)
    }

    fun iso(value: Int?): String =
        value?.takeIf { it > 0 }?.let { "ISO $it" } ?: MISSING_VALUE

    fun exposureBias(value: Double?): String =
        value?.takeIf { it.isFinite() }
            ?.let { "%+.1f EV".format(Locale.US, it) }
            ?: MISSING_VALUE

    fun focalLength(value: Double?): String {
        val millimeters = value?.takeIf { it.isFinite() && it > 0.0 } ?: return MISSING_VALUE
        return if (abs(millimeters - millimeters.roundToInt()) < 0.05) {
            "${millimeters.roundToInt()} mm"
        } else {
            "%.1f mm".format(Locale.US, millimeters)
        }
    }

    fun capturedAt(value: String?): String {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return MISSING_VALUE
        return when {
            EXIF_DATE.matches(raw.take(19)) -> {
                val date = raw.take(19)
                "${date.substring(0, 4)}/${date.substring(5, 7)}/${date.substring(8, 10)} " +
                    date.substring(11, 19)
            }
            PTP_DATE.matches(raw.take(15)) -> {
                val date = raw.take(15)
                "${date.substring(0, 4)}/${date.substring(4, 6)}/${date.substring(6, 8)} " +
                    "${date.substring(9, 11)}:${date.substring(11, 13)}:${date.substring(13, 15)}"
            }
            else -> raw
        }
    }

    fun format(format: CaptureFileFormat, previewFallback: Boolean): String = when {
        format == CaptureFileFormat.JPEG && previewFallback -> "JPEG（プレビュー）"
        format == CaptureFileFormat.JPEG -> "JPEG"
        format == CaptureFileFormat.ORF -> "ORF"
        else -> "不明"
    }

    fun smartphoneSaveState(item: CaptureHistoryItem): String = when (item.smartphoneSaveState) {
        SmartphoneSaveState.SAVING -> "保存中"
        SmartphoneSaveState.SAVED -> item.savedRelativePath
            ?.let { "保存済み（$it/${item.filename}）" }
            ?: "保存済み"
        SmartphoneSaveState.FAILED -> "未保存${item.saveFailure?.let { "（$it）" }.orEmpty()}"
    }

    const val MISSING_VALUE = "—"

    private val EXIF_DATE = Regex("""\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}""")
    private val PTP_DATE = Regex("""\d{8}T\d{6}""")
}
