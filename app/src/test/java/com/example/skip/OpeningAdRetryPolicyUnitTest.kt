package com.example.skip

import com.example.skip.model.ClickLogStage
import com.example.skip.engine.ScoreEvaluator
import com.example.skip.service.OpeningAdRecoveryGate
import com.example.skip.service.OpeningAdRescanPolicy
import com.example.skip.service.OpeningAdRetryPolicy
import com.example.skip.service.PendingEventFastPathDecision
import com.example.skip.service.PendingEventFastPathPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningAdRetryPolicyUnitTest {
    @Test
    fun eightSecondWindowUsesAbsoluteOpeningAdCheckpoints() {
        assertEquals(
            listOf(120L, 320L, 720L, 1_500L, 3_000L, 5_000L, 7_000L),
            OpeningAdRescanPolicy.absoluteOffsetsMs(8_000L)
        )
    }

    @Test
    fun rescanDelaysAreMeasuredFromForegroundStartNotFromFailure() {
        assertEquals(
            listOf(141L, 2_141L, 4_141L),
            OpeningAdRescanPolicy.remainingDelaysMs(
                foregroundStartTimeMillis = 10_000L,
                nowMillis = 12_859L,
                ruleWindowMs = 8_000L
            )
        )
    }

    @Test
    fun rootWindowMissCanStartAbsoluteRescans() {
        assertTrue(
            OpeningAdRescanPolicy.shouldSchedule(
                stage = ClickLogStage.RootWindowNull,
                isWithinDefaultRuleWindow = true,
                hasPendingClick = false,
                hasActiveRules = true
            )
        )
    }

    @Test
    fun transientRetriesUseThreeRealAttempts() {
        assertEquals(80L, OpeningAdRetryPolicy.nextDelayMs(0))
        assertEquals(250L, OpeningAdRetryPolicy.nextDelayMs(1))
        assertEquals(600L, OpeningAdRetryPolicy.nextDelayMs(2))
        assertNull(OpeningAdRetryPolicy.nextDelayMs(3))

        listOf(
            "root_window_null",
            "no_candidate_found",
            "score_below_min_score",
            "candidate_lost_before_click",
            "candidate_changed_before_click",
            "current_target_missing",
            "gesture_cancelled",
            "gesture_dispatch_returned_false"
        ).forEach { reason ->
            assertTrue(
                reason,
                OpeningAdRetryPolicy.shouldRetry(
                    reason = reason,
                    retriesPerformed = 0,
                    isWithinRuleWindow = true
                )
            )
        }
    }

    @Test
    fun safetyAndContextChangesNeverRetry() {
        listOf(
            "blocked_by_safety_policy",
            "sensitive_skip_semantic",
            "ambiguous_candidate_large_bounds",
            "generic_skip_context_missing",
            ScoreEvaluator.GENERIC_CLOSE_BLOCKED_REASON,
            "text_input_clear_button",
            "active_text_input",
            "package_changed_before_click",
            "activity_changed_before_click",
            "click_cancelled_time_window_expired"
        ).forEach { reason ->
            assertFalse(
                reason,
                OpeningAdRetryPolicy.shouldRetry(
                    reason = reason,
                    retriesPerformed = 0,
                    isWithinRuleWindow = true
                )
            )
        }

        assertFalse(
            OpeningAdRetryPolicy.shouldRetry(
                reason = "no_candidate_found",
                retriesPerformed = 0,
                isWithinRuleWindow = false
            )
        )
        assertFalse(
            OpeningAdRetryPolicy.shouldRetry(
                reason = "no_candidate_found",
                retriesPerformed = 0,
                isWithinRuleWindow = true,
                samePackage = false
            )
        )
        assertFalse(
            OpeningAdRetryPolicy.shouldRetry(
                reason = "no_candidate_found",
                retriesPerformed = 0,
                isWithinRuleWindow = true,
                sameActivity = false
            )
        )
        assertFalse(
            OpeningAdRetryPolicy.shouldRetry(
                reason = "no_candidate_found",
                retriesPerformed = 0,
                isWithinRuleWindow = true,
                activeTextInput = true
            )
        )
    }

    @Test
    fun terminalSessionAndPausedMasterCannotRestartRecovery() {
        assertFalse(
            OpeningAdRecoveryGate.canRun(
                masterEnabled = true,
                sessionKey = "com.example:1000",
                terminalSessionKey = "com.example:1000"
            )
        )
        assertFalse(
            OpeningAdRecoveryGate.canRun(
                masterEnabled = false,
                sessionKey = "com.example:1000",
                terminalSessionKey = null
            )
        )
        assertTrue(
            OpeningAdRecoveryGate.canRun(
                masterEnabled = true,
                sessionKey = "com.example:2000",
                terminalSessionKey = "com.example:1000"
            )
        )
    }

    @Test
    fun dispatchedClickWaitsForVerificationAcrossPackageAndActivityChanges() {
        assertEquals(
            PendingEventFastPathDecision.AwaitClickVerification,
            PendingEventFastPathPolicy.evaluate(
                clickDispatched = true,
                currentPackageKnown = true,
                samePackage = false,
                isWithinRuleWindow = false,
                activityChanged = true
            )
        )
        assertEquals(
            PendingEventFastPathDecision.CancelPackageChanged,
            PendingEventFastPathPolicy.evaluate(
                clickDispatched = false,
                currentPackageKnown = true,
                samePackage = false,
                isWithinRuleWindow = true,
                activityChanged = false
            )
        )
    }
}
