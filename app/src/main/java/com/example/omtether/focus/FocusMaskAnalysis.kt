package com.example.omtether.focus

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class FocusMaskResult(
    val bitmap: Bitmap,
    val highlightedPercent: Float,
    val threshold: Int,
)

/**
 * Builds a high-contrast edge overlay for post-capture focus checking.
 *
 * This is deliberately a visual aid rather than an AF-success verdict. Analysis is bounded to
 * 1024 px and callers run it on Dispatchers.Default, keeping USB transfer and Compose threads free.
 */
object FocusMaskAnalysis {
    fun createMask(bitmap: Bitmap, sensitivity: Float): FocusMaskResult {
        val scale = minOf(
            1f,
            MAX_ANALYSIS_DIMENSION.toFloat() / max(bitmap.width, bitmap.height).coerceAtLeast(1),
        )
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val working = if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        if (width < 3 || height < 3) {
            val empty = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            if (working !== bitmap) working.recycle()
            return FocusMaskResult(empty, 0f, 255)
        }

        val pixels = IntArray(width * height)
        working.getPixels(pixels, 0, width, 0, 0, width, height)
        val luminance = ByteArray(pixels.size)
        pixels.forEachIndexed { index, color ->
            luminance[index] = (
                (54 * Color.red(color) + 183 * Color.green(color) + 19 * Color.blue(color)) ushr 8
                ).toByte()
        }
        val histogram = IntArray(256)
        val magnitudes = ByteArray(pixels.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val magnitude = sobelMagnitude(luminance, width, x, y)
                magnitudes[index] = magnitude.toByte()
                histogram[magnitude]++
            }
        }
        val threshold = thresholdForHistogram(histogram, sensitivity)
        val maskPixels = IntArray(pixels.size)
        var highlighted = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val magnitude = magnitudes[index].toInt() and 0xFF
                if (magnitude < threshold) continue
                maskPixels[index] = MASK_COLOR
                if (x + 1 < width) {
                    maskPixels[index + 1] = MASK_EDGE_COLOR
                }
                highlighted++
            }
        }
        val mask = Bitmap.createBitmap(maskPixels, width, height, Bitmap.Config.ARGB_8888)
        if (working !== bitmap) working.recycle()
        val analyzedPixels = ((width - 2) * (height - 2)).coerceAtLeast(1)
        return FocusMaskResult(
            bitmap = mask,
            highlightedPercent = highlighted * 100f / analyzedPixels,
            threshold = threshold,
        )
    }

    internal fun thresholdForHistogram(histogram: IntArray, sensitivity: Float): Int {
        val sampleCount = histogram.sum().coerceAtLeast(1)
        val keepFraction = MIN_KEEP_FRACTION +
            (MAX_KEEP_FRACTION - MIN_KEEP_FRACTION) * sensitivity.coerceIn(0f, 1f)
        val targetCount = (sampleCount * keepFraction).roundToInt().coerceAtLeast(1)
        var accumulated = 0
        for (value in histogram.lastIndex downTo MIN_GRADIENT) {
            accumulated += histogram[value]
            if (accumulated >= targetCount) return value
        }
        return MIN_GRADIENT
    }

    private fun sobelMagnitude(
        luminance: ByteArray,
        width: Int,
        x: Int,
        y: Int,
    ): Int {
        val upper = (y - 1) * width + x
        val middle = y * width + x
        val lower = (y + 1) * width + x
        val upperLeft = luminance[upper - 1].toInt() and 0xFF
        val upperCenter = luminance[upper].toInt() and 0xFF
        val upperRight = luminance[upper + 1].toInt() and 0xFF
        val middleLeft = luminance[middle - 1].toInt() and 0xFF
        val middleRight = luminance[middle + 1].toInt() and 0xFF
        val lowerLeft = luminance[lower - 1].toInt() and 0xFF
        val lowerCenter = luminance[lower].toInt() and 0xFF
        val lowerRight = luminance[lower + 1].toInt() and 0xFF
        val gradientX =
            -upperLeft + upperRight -
                2 * middleLeft + 2 * middleRight -
                lowerLeft + lowerRight
        val gradientY =
            -upperLeft - 2 * upperCenter - upperRight +
                lowerLeft + 2 * lowerCenter + lowerRight
        return ((abs(gradientX) + abs(gradientY)) / 8).coerceIn(0, 255)
    }

    private const val MAX_ANALYSIS_DIMENSION = 1_024
    private const val MIN_GRADIENT = 24
    private const val MIN_KEEP_FRACTION = 0.015f
    private const val MAX_KEEP_FRACTION = 0.18f
    // Literal ARGB values keep the histogram helper JVM-testable without invoking Android stubs
    // during object initialization.
    private const val MASK_COLOR = -685_047_925 // 0xD72AFF8B
    private const val MASK_EDGE_COLOR = 0x782AFF8B
}
