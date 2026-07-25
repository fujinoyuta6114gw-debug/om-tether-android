package com.example.omtether

import com.example.omtether.image.NeutralPatchResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupGuidePolicyTest {
    @Test
    fun demoModeDoesNotPassCameraConnectionStep() {
        assertFalse(canAdvanceSetup(2, ConnectionPhase.DEMO, false, false, goodPatch()))
        assertTrue(canAdvanceSetup(2, ConnectionPhase.CONNECTED, false, false, goodPatch()))
    }

    @Test
    fun grayCardStepRequiresConfirmationAndAcceptablePatch() {
        assertFalse(canAdvanceSetup(3, ConnectionPhase.CONNECTED, false, false, goodPatch()))
        assertTrue(canAdvanceSetup(3, ConnectionPhase.CONNECTED, true, false, goodPatch()))
        assertFalse(canAdvanceSetup(3, ConnectionPhase.CONNECTED, true, false, badPatch()))
    }

    @Test
    fun explicitGrayCardSkipIsAllowedButDisconnectStillBlocksCompletion() {
        assertTrue(canAdvanceSetup(3, ConnectionPhase.CONNECTED, false, true, badPatch()))
        assertFalse(canAdvanceSetup(5, ConnectionPhase.DISCONNECTED, false, true, badPatch()))
        assertFalse(canAdvanceSetup(5, ConnectionPhase.DEMO, false, true, badPatch()))
        assertFalse(canAdvanceSetup(5, ConnectionPhase.CONNECTED, false, false, goodPatch()))
        assertTrue(canAdvanceSetup(5, ConnectionPhase.CONNECTED, true, false, goodPatch()))
    }

    private fun goodPatch() = NeutralPatchResult(
        red = 120f,
        green = 120f,
        blue = 120f,
        luminance = 120f,
        deviationPercent = 3f,
    )

    private fun badPatch() = NeutralPatchResult(
        red = 160f,
        green = 100f,
        blue = 80f,
        luminance = 110f,
        deviationPercent = 25f,
    )
}
