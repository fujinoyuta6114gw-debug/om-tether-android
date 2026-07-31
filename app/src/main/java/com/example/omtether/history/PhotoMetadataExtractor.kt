package com.example.omtether.history

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.example.omtether.camera.CaptureSavePolicy
import com.example.omtether.camera.DownloadedObject
import com.example.omtether.image.ImageAnalysis
import java.io.ByteArrayInputStream
import kotlin.math.pow

data class ExtractedPhotoMetadata(
    val metadata: PhotoExifMetadata,
    val thumbnail: Bitmap?,
    val focusReviewBitmap: Bitmap?,
    val focusReviewUsesEmbeddedPreview: Boolean,
    val warning: String? = null,
)

/**
 * Reads metadata from the exact JPEG/ORF byte array that is handed to Android storage.
 *
 * No live camera property is consulted here. A bounded thumbnail and a bounded focus-review
 * Bitmap are decoded while the original transfer byte array is available. The ViewModel keeps
 * the larger Bitmap only for the newest two captures.
 */
object PhotoMetadataExtractor {
    fun extract(item: DownloadedObject): ExtractedPhotoMetadata {
        var exifReadFailure: Throwable? = null
        val exif = try {
            ExifInterface(ByteArrayInputStream(item.bytes))
        } catch (error: Throwable) {
            exifReadFailure = error
            null
        }

        val directFNumber = exif.doubleAttribute(ExifInterface.TAG_F_NUMBER)
        val apertureValue = exif.doubleAttribute(ExifInterface.TAG_APERTURE_VALUE)
        val directExposureTime = exif.doubleAttribute(ExifInterface.TAG_EXPOSURE_TIME)
        val shutterSpeedValue = exif.doubleAttribute(ExifInterface.TAG_SHUTTER_SPEED_VALUE)
        val exifCaptureDate = exif.stringAttribute(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_DATETIME,
        )
        val captureDate = exifCaptureDate ?: item.captureDate?.takeIf { it.isNotBlank() }
        val requestedAttributesPresent = exif?.let { parsedExif ->
            listOf(
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_APERTURE_VALUE,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_SHUTTER_SPEED_VALUE,
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_ISO_SPEED,
                ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
                ExifInterface.TAG_ISO_SPEED_RATINGS,
                ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_DATETIME,
            ).any { tag -> parsedExif.getAttribute(tag) != null }
        } == true

        val metadata = PhotoExifMetadata(
            apertureFNumber = directFNumber?.takeIf { it > 0.0 }
                ?: apertureValue?.takeIf { it.isFinite() }?.let { 2.0.pow(it / 2.0) },
            exposureTimeSeconds = directExposureTime?.takeIf { it > 0.0 }
                ?: shutterSpeedValue?.takeIf { it.isFinite() }?.let { 2.0.pow(-it) },
            iso = exif.intAttribute(
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                ExifInterface.TAG_ISO_SPEED,
                ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
                ExifInterface.TAG_ISO_SPEED_RATINGS,
            ),
            exposureBiasEv = exif.doubleAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE),
            focalLengthMm = exif.doubleAttribute(ExifInterface.TAG_FOCAL_LENGTH),
            capturedAt = captureDate,
            captureTimeSource = when {
                exifCaptureDate != null -> CaptureTimeSource.EXIF
                captureDate != null -> CaptureTimeSource.PTP_OBJECT_INFO
                else -> CaptureTimeSource.UNAVAILABLE
            },
            hasActualExif = requestedAttributesPresent,
        )

        val fullJpegAvailable = item.isJpeg()
        val embeddedPreviewBytes = item.previewJpeg?.takeIf { it.isNotEmpty() }
            ?: runCatching { exif?.thumbnail }.getOrNull()?.takeIf { it.isNotEmpty() }
        val reviewBytes = if (fullJpegAvailable) item.bytes else embeddedPreviewBytes
        val focusReviewUsesEmbeddedPreview = item.isPreviewFallback || !fullJpegAvailable
        val focusReviewBitmap = try {
            reviewBytes
                ?.let {
                    ImageAnalysis.decodeJpeg(
                        bytes = it,
                        maxDimension = FOCUS_REVIEW_MAX_DIMENSION,
                        preferredConfig = Bitmap.Config.RGB_565,
                    )
                }
                ?.let { bitmap -> orientThumbnail(bitmap, exif) }
        } catch (_: Exception) {
            null
        }
        val thumbnail = try {
            focusReviewBitmap?.let(::createHistoryThumbnail)
                ?: embeddedPreviewBytes
                    ?.let {
                        ImageAnalysis.decodeJpeg(
                            bytes = it,
                            maxDimension = HISTORY_THUMBNAIL_MAX_DIMENSION,
                            preferredConfig = Bitmap.Config.RGB_565,
                        )
                    }
                    ?.let { bitmap -> orientThumbnail(bitmap, exif) }
        } catch (_: Exception) {
            null
        }

        val warning = when {
            exifReadFailure != null -> "EXIFを読み取れませんでした"
            !requestedAttributesPresent -> "このファイルに撮影EXIFが記録されていません"
            else -> null
        }
        return ExtractedPhotoMetadata(
            metadata = metadata,
            thumbnail = thumbnail,
            focusReviewBitmap = focusReviewBitmap,
            focusReviewUsesEmbeddedPreview = focusReviewUsesEmbeddedPreview,
            warning = warning,
        )
    }

    fun fileFormat(item: DownloadedObject): CaptureFileFormat = when {
        item.filename.endsWith(".orf", ignoreCase = true) -> CaptureFileFormat.ORF
        item.isJpeg() -> CaptureFileFormat.JPEG
        else -> CaptureFileFormat.UNKNOWN
    }

    private fun DownloadedObject.isJpeg(): Boolean =
        format == CaptureSavePolicy.JPEG_OBJECT_FORMAT ||
            filename.endsWith(".jpg", ignoreCase = true) ||
            filename.endsWith(".jpeg", ignoreCase = true)

    private fun ExifInterface?.doubleAttribute(vararg tags: String): Double? {
        if (this == null) return null
        tags.forEach { tag ->
            if (getAttribute(tag) == null) return@forEach
            val value = getAttributeDouble(tag, Double.NaN)
            if (value.isFinite()) return value
        }
        return null
    }

    private fun ExifInterface?.intAttribute(vararg tags: String): Int? {
        if (this == null) return null
        tags.forEach { tag ->
            if (getAttribute(tag) == null) return@forEach
            val value = getAttributeInt(tag, -1)
            if (value > 0) return value
            val parsed = getAttribute(tag)
                ?.substringBefore(',')
                ?.trim()
                ?.toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return null
    }

    private fun ExifInterface?.stringAttribute(vararg tags: String): String? {
        if (this == null) return null
        tags.forEach { tag ->
            getAttribute(tag)?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        }
        return null
    }

    private fun orientThumbnail(bitmap: Bitmap, exif: ExifInterface?): Bitmap {
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        ) ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { oriented ->
                    if (oriented !== bitmap) bitmap.recycle()
                }
        } catch (_: Throwable) {
            bitmap
        }
    }

    private fun createHistoryThumbnail(source: Bitmap): Bitmap {
        val scale = minOf(
            1f,
            HISTORY_THUMBNAIL_MAX_DIMENSION.toFloat() /
                maxOf(source.width, source.height).coerceAtLeast(1),
        )
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return if (width == source.width && height == source.height) {
            source.copy(Bitmap.Config.RGB_565, false)
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
    }

    private const val HISTORY_THUMBNAIL_MAX_DIMENSION = 240
    // 3072 keeps a typical 5184px OM-1 JPEG at the decoder's 1/2 sample (2592px) while bounding
    // each RGB_565 review image to roughly 14 MiB for unusual aspect ratios.
    private const val FOCUS_REVIEW_MAX_DIMENSION = 3_072
}
