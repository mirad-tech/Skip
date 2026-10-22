package com.example.skip.service

import com.example.skip.engine.OpeningAdWindowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningAdDispatchTest {
    private fun check(elapsed: Long, pendingKey: String = "app:1", currentKey: String = "app:1", open: Boolean = true) =
        DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.reader", currentPackageName = "com.example.reader",
            selfPackageName = "com.example.skip", foregroundPackageName = "com.example.reader",
            elapsedSinceForegroundMs = elapsed, pendingSessionKey = pendingKey, currentSessionKey = currentKey,
            isSessionOpen = open, defaultRuleWindowMs = 15_000L, rootWindowNull = false
        )

    @Test
    fun aQueuedClickCannotUseItsEarlierSuccessfulTimeCheck() {
        assertTrue(check(5_999L).allowed)
        assertFalse(check(6_000L).allowed)
        assertEquals("click_cancelled_time_window_expired", check(6_001L).reason)
    }

    @Test
    fun samePackageDoesNotAllowACallbackFromAnOlderVisit() {
        assertFalse(check(100L, pendingKey = "app:1", currentKey = "app:3").allowed)
        assertEquals("opening_ad_session_changed", check(100L, "app:1", "app:3").reason)
    }

    @Test
    fun completedSessionBlocksEveryFurtherDispatch() {
        assertFalse(check(1_000L, open = false).allowed)
    }

    @Test
    fun alreadyDispatchedClicksMayOnlyFinishVerificationAfterExpiry() {
        assertEquals(PendingEventFastPathDecision.AwaitClickVerification, PendingEventFastPathPolicy.evaluate(
            clickDispatched = true, currentPackageKnown = true, samePackage = true,
            isWithinRuleWindow = false, activityChanged = false
        ))
        assertEquals(PendingEventFastPathDecision.CancelTimeWindowExpired, PendingEventFastPathPolicy.evaluate(
            clickDispatched = false, currentPackageKnown = true, samePackage = true,
            isWithinRuleWindow = false, activityChanged = false
        ))
        assertFalse(OpeningAdRetryPolicy.shouldRetry("gesture_cancelled", 0, false))
    }

    @Test
    fun incompleteTargetScanStillCannotConfirmSuccessOrTriggerFallback() {
        val verification = ClickEffectVerifier.evaluate(
            "com.example.reader", "com.example.skip", "com.example.reader", "com.example.reader", false, null
        )
        assertFalse(verification.success)
        assertFalse(verification.shouldTryGestureFallback)
        assertEquals("target_scan_incomplete", verification.reason)
    }

    @Test
    fun recoveryOffsetsAllStayBeforeTheSixSecondDeadline() {
        val delays = OpeningAdRescanPolicy.remainingDelaysMs(0L, 0L, OpeningAdWindowPolicy.MAX_WINDOW_MS)
        assertEquals(listOf(120L, 320L, 720L, 1_500L, 3_000L, 5_000L), delays)
        assertTrue(OpeningAdRescanPolicy.remainingDelaysMs(1_000L, 7_000L, 6_000L).isEmpty())
    }

    @Test
    fun cancelledRecoveryGenerationCannotRunInANewSession() {
        val recovery = OpeningAdRecoveryState()
        recovery.beginRetrySession("app:1")
        val oldGeneration = recovery.scheduleRetry()
        recovery.cancel(resetRetrySession = true)
        recovery.beginRetrySession("app:3")
        val currentGeneration = recovery.scheduleRetry()
        assertFalse(recovery.acceptScheduledRetry(oldGeneration, 1))
        assertTrue(recovery.acceptScheduledRetry(currentGeneration, 1))
    }

    @Test
    fun contentEventsStopAtTheExactBoundaryWhileWindowEventsCanObserveANewApp() {
        assertEquals(AccessibilityEventWork.SkipTreeRecordExpiry, AccessibilityEventWorkPolicy.decide(
            AccessibilityEventWorkPolicy.WINDOW_CONTENT_CHANGED, 6_000L, 6_000L, 0L, 7_000L, false
        ))
        assertEquals(AccessibilityEventWork.ProcessFully, AccessibilityEventWorkPolicy.decide(
            AccessibilityEventWorkPolicy.WINDOW_STATE_CHANGED, 60_000L, 6_000L, 0L, 61_000L, false,
            hasActiveSession = false
        ))
    }
}
