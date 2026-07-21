package com.example.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityNavigationDecisionTest {
    @Test
    fun grantedServiceResumesAutomationWithoutOpeningPurposeScreen() {
        val decision = enableAutomationDecision(
            releaseDisclosureAccepted = true,
            serviceEnabled = true
        )

        assertTrue(decision.enableMaster)
        assertEquals(AppScreen.Home, decision.nextScreen)
    }

    @Test
    fun missingServiceShowsPurposeWithoutChangingMasterState() {
        val decision = enableAutomationDecision(
            releaseDisclosureAccepted = true,
            serviceEnabled = false
        )

        assertFalse(decision.enableMaster)
        assertEquals(AppScreen.AccessibilityPurpose(), decision.nextScreen)
    }

    @Test
    fun missingDisclosureShowsOnboardingBeforeChangingMasterState() {
        val decision = enableAutomationDecision(
            releaseDisclosureAccepted = false,
            serviceEnabled = true
        )

        assertFalse(decision.enableMaster)
        assertEquals(AppScreen.Onboarding(), decision.nextScreen)
    }

    @Test
    fun homeEnableFlowEnablesMasterBeforeOpeningSystemSettings() {
        val decision = accessibilitySettingsDecision(AppScreen.Home)

        assertTrue(decision.enableMaster)
        assertEquals(AppScreen.Home, decision.returnScreen)
    }

    @Test
    fun systemHubEntryPreservesPausedStateBeforeOpeningSystemSettings() {
        val decision = accessibilitySettingsDecision(AppScreen.SystemHub)

        assertFalse(decision.enableMaster)
        assertEquals(AppScreen.SystemHub, decision.returnScreen)
    }
}
