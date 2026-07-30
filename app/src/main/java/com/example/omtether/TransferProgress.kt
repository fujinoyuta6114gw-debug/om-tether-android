package com.example.omtether

import kotlin.math.ceil

enum class SaveProgressStage {
    PREPARING,
    DOWNLOADING,
    WRITING,
}

data class SaveProgress(
    val stage: SaveProgressStage,
    val filename: String? = null,
    val completedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long? = null,
    val remainingSeconds: Int? = null,
) {
    val fraction: Float?
        get() = totalBytes
            .takeIf { it > 0L }
            ?.let { (completedBytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
}

internal class TransferProgressEstimator {
    private var key: String? = null
    private var startedAtMs = 0L
    private var startedBytes = 0L

    fun update(
        stage: SaveProgressStage,
        filename: String,
        completedBytes: Long,
        totalBytes: Long,
        nowMs: Long,
    ): SaveProgress {
        val nextKey = "${stage.name}:$filename"
        if (key != nextKey || nowMs < startedAtMs || completedBytes < startedBytes) {
            key = nextKey
            startedAtMs = nowMs
            startedBytes = completedBytes.coerceAtLeast(0L)
        }
        val safeTotal = totalBytes.coerceAtLeast(0L)
        val safeCompleted = completedBytes.coerceIn(0L, safeTotal.takeIf { it > 0L } ?: Long.MAX_VALUE)
        val elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L)
        val measuredBytes = (safeCompleted - startedBytes).coerceAtLeast(0L)
        val bytesPerSecond = if (elapsedMs >= MIN_ESTIMATE_WINDOW_MS && measuredBytes > 0L) {
            (measuredBytes * 1_000L / elapsedMs).coerceAtLeast(1L)
        } else {
            null
        }
        val remainingSeconds = bytesPerSecond
            ?.takeIf { safeTotal > safeCompleted }
            ?.let { speed ->
                ceil((safeTotal - safeCompleted).toDouble() / speed.toDouble())
                    .toInt()
                    .coerceIn(1, MAX_REMAINING_SECONDS)
            }
        return SaveProgress(
            stage = stage,
            filename = filename,
            completedBytes = safeCompleted,
            totalBytes = safeTotal,
            bytesPerSecond = bytesPerSecond,
            remainingSeconds = remainingSeconds,
        )
    }

    private companion object {
        const val MIN_ESTIMATE_WINDOW_MS = 400L
        const val MAX_REMAINING_SECONDS = 24 * 60 * 60
    }
}
