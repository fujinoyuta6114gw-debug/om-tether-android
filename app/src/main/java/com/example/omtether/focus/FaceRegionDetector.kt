package com.example.omtether.focus

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import kotlin.math.max
import kotlin.math.roundToInt

data class FaceRegion(
    val centerX: Float,
    val centerY: Float,
    val relativeSize: Float,
    val confidence: Float,
)

/**
 * Lightweight, offline face jump points for the focus-review screen.
 *
 * Android's local detector requires RGB_565 and an even width. Detection is performed on a
 * bounded copy and never sends an image off-device.
 */
@Suppress("DEPRECATION")
object FaceRegionDetector {
    fun detect(bitmap: Bitmap, maxFaces: Int = 8): List<FaceRegion> {
        if (bitmap.width < 2 || bitmap.height < 2 || maxFaces <= 0) return emptyList()
        var working: Bitmap? = null
        return try {
            val scale = minOf(
                1f,
                MAX_FACE_DIMENSION.toFloat() / max(bitmap.width, bitmap.height),
            )
            var width = (bitmap.width * scale).roundToInt().coerceAtLeast(2)
            if (width % 2 != 0) width--
            val height = (bitmap.height * scale).roundToInt().coerceAtLeast(2)
            var prepared = Bitmap.createScaledBitmap(bitmap, width, height, true)
            working = prepared
            if (prepared.config != Bitmap.Config.RGB_565) {
                val converted = prepared.copy(Bitmap.Config.RGB_565, false)
                if (prepared !== bitmap) prepared.recycle()
                prepared = converted
                working = prepared
            }
            val detector = FaceDetector(width, height, maxFaces)
            val faces = arrayOfNulls<FaceDetector.Face>(maxFaces)
            val found = detector.findFaces(prepared, faces)
            val midpoint = PointF()
            (0 until found).mapNotNull { index ->
                val face = faces[index] ?: return@mapNotNull null
                face.getMidPoint(midpoint)
                val eyeDistance = face.eyesDistance()
                val confidence = face.confidence()
                if (eyeDistance <= 0f || confidence < MIN_CONFIDENCE) return@mapNotNull null
                FaceRegion(
                    centerX = (midpoint.x / width).coerceIn(0f, 1f),
                    // The midpoint is between the eyes; shift toward the center of the face.
                    centerY = ((midpoint.y + eyeDistance * 0.55f) / height).coerceIn(0f, 1f),
                    relativeSize = (eyeDistance / width).coerceIn(0f, 1f),
                    confidence = confidence,
                )
            }.sortedByDescending(FaceRegion::relativeSize)
        } catch (_: Throwable) {
            emptyList()
        } finally {
            working?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        }
    }

    private const val MAX_FACE_DIMENSION = 1_024
    private const val MIN_CONFIDENCE = 0.30f
}
