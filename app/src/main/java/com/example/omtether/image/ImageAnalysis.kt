package com.example.omtether.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.max
import kotlin.math.roundToInt

data class ImageAnalysisResult(
    val histogram: IntArray,
    val highlightOverlay: Bitmap?,
    val highlightPercent: Float,
    val neutralPatch: NeutralPatchResult,
)

data class NeutralPatchResult(
    val red: Float,
    val green: Float,
    val blue: Float,
    val luminance: Float,
    val deviationPercent: Float,
)

object ImageAnalysis {
    fun decodeJpeg(
        bytes: ByteArray,
        maxDimension: Int = 2_048,
        preferredConfig: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        val largestDimension = max(bounds.outWidth, bounds.outHeight)
        while (largestDimension > 0 && largestDimension / sampleSize > maxDimension.coerceAtLeast(1)) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = preferredConfig
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    fun analyze(
        bitmap: Bitmap,
        thresholdFraction: Float,
        includeHighlightOverlay: Boolean = true,
    ): ImageAnalysisResult {
        val threshold = (thresholdFraction.coerceIn(0.70f, 1f) * 255f).roundToInt()
        val scale = minOf(1f, MAX_ANALYSIS_WIDTH.toFloat() / bitmap.width.coerceAtLeast(1))
        val width = max(1, (bitmap.width * scale).roundToInt())
        val height = max(1, (bitmap.height * scale).roundToInt())
        val working = if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val pixels = IntArray(width * height)
        working.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogram = IntArray(256)
        val overlayPixels = if (includeHighlightOverlay) IntArray(pixels.size) else null
        var highlightCount = 0
        pixels.forEachIndexed { index, color ->
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val luminance = ((54 * red + 183 * green + 19 * blue) ushr 8).coerceIn(0, 255)
            histogram[luminance]++
            if (max(red, max(green, blue)) >= threshold) {
                overlayPixels?.set(index, Color.argb(132, 255, 52, 32))
                highlightCount++
            }
        }
        val overlay = overlayPixels?.let {
            Bitmap.createBitmap(it, width, height, Bitmap.Config.ARGB_8888)
        }
        if (working !== bitmap) working.recycle()
        return ImageAnalysisResult(
            histogram = histogram,
            highlightOverlay = overlay,
            highlightPercent = if (pixels.isEmpty()) 0f else highlightCount * 100f / pixels.size,
            neutralPatch = neutralPatchFromPixels(pixels, width, height),
        )
    }

    internal fun neutralPatchFromPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
    ): NeutralPatchResult {
        if (width <= 0 || height <= 0 || pixels.size < width * height) {
            return NeutralPatchResult(0f, 0f, 0f, 0f, 100f)
        }
        val left = (width * 0.35f).roundToInt().coerceIn(0, width - 1)
        val right = (width * 0.65f).roundToInt().coerceIn(left + 1, width)
        val top = (height * 0.35f).roundToInt().coerceIn(0, height - 1)
        val bottom = (height * 0.65f).roundToInt().coerceIn(top + 1, height)
        var redTotal = 0L
        var greenTotal = 0L
        var blueTotal = 0L
        var count = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val color = pixels[y * width + x]
                redTotal += Color.red(color)
                greenTotal += Color.green(color)
                blueTotal += Color.blue(color)
                count++
            }
        }
        if (count == 0) return NeutralPatchResult(0f, 0f, 0f, 0f, 100f)
        val red = redTotal.toFloat() / count
        val green = greenTotal.toFloat() / count
        val blue = blueTotal.toFloat() / count
        val mean = (red + green + blue) / 3f
        val deviation = if (mean <= 0f) 100f else {
            maxOf(kotlin.math.abs(red - mean), kotlin.math.abs(green - mean), kotlin.math.abs(blue - mean)) *
                100f / mean
        }
        return NeutralPatchResult(
            red = red,
            green = green,
            blue = blue,
            luminance = (0.2126f * red + 0.7152f * green + 0.0722f * blue),
            deviationPercent = deviation,
        )
    }

    private const val MAX_ANALYSIS_WIDTH = 640
}
