package com.example.omtether

import com.example.omtether.image.NeutralPatchResult

enum class NeutralPatchAssessment {
    WAITING,
    TOO_DARK,
    TOO_BRIGHT,
    GOOD,
    CLOSE,
    NEEDS_WB,
}

fun assessNeutralPatch(result: NeutralPatchResult?): NeutralPatchAssessment = when {
    result == null -> NeutralPatchAssessment.WAITING
    result.luminance < 35f -> NeutralPatchAssessment.TOO_DARK
    result.luminance > 230f -> NeutralPatchAssessment.TOO_BRIGHT
    result.deviationPercent <= 5f -> NeutralPatchAssessment.GOOD
    result.deviationPercent <= 10f -> NeutralPatchAssessment.CLOSE
    else -> NeutralPatchAssessment.NEEDS_WB
}

fun canAdvanceSetup(
    step: Int,
    phase: ConnectionPhase,
    wbConfirmed: Boolean,
    grayCardSkipped: Boolean,
    neutralPatch: NeutralPatchResult?,
): Boolean {
    val cameraConnected = phase == ConnectionPhase.CONNECTED
    return when (step) {
        2 -> cameraConnected
        3 -> cameraConnected && (
            grayCardSkipped ||
                (wbConfirmed && assessNeutralPatch(neutralPatch) in ACCEPTABLE_NEUTRAL_RESULTS)
            )
        4, 5 -> cameraConnected && (wbConfirmed || grayCardSkipped)
        else -> true
    }
}

private val ACCEPTABLE_NEUTRAL_RESULTS = setOf(
    NeutralPatchAssessment.GOOD,
    NeutralPatchAssessment.CLOSE,
)
