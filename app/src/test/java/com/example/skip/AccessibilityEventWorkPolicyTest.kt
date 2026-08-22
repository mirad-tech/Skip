package com.example.skip

import com.example.skip.engine.PreciseRulePolicy
import com.example.skip.service.AccessibilityEventWork
import com.example.skip.service.AccessibilityEventWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityEventWorkPolicyTest {
    @Test
    fun windowStateChangedAlwaysProcessesEvenAfterRuleWindow() {
        assertEquals(
            AccessibilityEventWork.ProcessFully,
            AccessibilityEventWorkPolicy.decide(
                eventType = AccessibilityEventWorkPolicy.WINDOW_STATE_CHANGED,
                elapsedSinceForegroundMs = PreciseRulePolicy.MAX_WINDOW_MS + 1_000L,
                ruleWindowMs = 8_000L,
                lastTreeWalkElapsedRealtime = 10_000L,
                nowElapsedRealtime = 10_050L,
                hasPendingClick = false
            )
        )
    }

    @Test
    fun windowsChangedAlwaysProcessesEvenAfterRuleWindow() {
        assertEquals(
            AccessibilityEventWork.ProcessFully,
            AccessibilityEventWorkPolicy.decide(
                eventType = AccessibilityEventWorkPolicy.WINDOWS_CHANGED,
                elapsedSinceForegroundMs = 8_001L,
                ruleWindowMs = 8_000L,
                lastTreeWalkElapsedRealtime = 10_000L,
                nowElapsedRealtime = 10_050L,
                hasPendingClick = false
            )
        )
    }

    @Test
    fun expiredContentChangedRecordsExpiryWithoutTreeWalk() {
        assertEquals(
            AccessibilityEventWork.SkipTreeRecordExpiry,
            AccessibilityEventWorkPolicy.decide(
                eventType = AccessibilityEventWorkPolicy.WINDOW_CONTENT_CHANGED,
                elapsedSinceForegroundMs = 8_001L,
                ruleWindowMs = 8_000L,
                lastTreeWalkElapsedRealtime = 1_000L,
                nowElapsedRealtime = 20_000L,
                hasPendingClick = false
            )
        )
    }

    @Test
    fun throttledContentChangedIsDroppedWhileInsideWindow() {
        assertEquals(
            AccessibilityEventWork.Drop,
            AccessibilityEventWorkPolicy.decide(
                eventType = AccessibilityEventWorkPolicy.WINDOW_CONTENT_CHANGED,
                elapsedSinceForegroundMs = 1_200L,
                ruleWindowMs = 8_000L,
                lastTreeWalkElapsedRealtime = 5_000L,
                nowElapsedRealtime = 5_000L + AccessibilityEventWorkPolicy.MIN_CONTENT_SCAN_INTERVAL_MS - 1L,
                hasPendingClick = false
            )
        )
    }

    @Test
    fun contentChangedAfterThrottleIntervalIsProcessedInsideRuleWindow() {
        assertEquals(
            AccessibilityEventWork.ProcessFully,
            AccessibilityEventWorkPolicy.decide(
                eventType = AccessibilityEventWorkPolicy.WINDOW_CONTENT_CHANGED,
                elapsedSinceForegroundMs = 1_200L,
                ruleWindowMs = 8_000L,
                lastTreeWalkElapsedRealtime = 5_000L,
                nowElapsedRealtime = 5_000L + AccessibilityEventWorkPolicy.MIN_CONTENT_SCAN_INTERVAL_MS,
                hasPendingClick = false
            )
        )
    }

    @Test
    fun pendingClickEventsAreNeverDropped() {
        assertEquals(
            AccessibilityEventWork.ProcessFully,
            AccessibilityEventWorkPolicy.decide(
                eventType = AccessibilityEventWorkPolicy.WINDOW_CONTENT_CHANGED,
                elapsedSinceForegroundMs = 8_001L,
                ruleWindowMs = 8_000L,
                lastTreeWalkElapsedRealtime = 5_000L,
                nowElapsedRealtime = 5_010L,
                hasPendingClick = true
            )
        )
    }

    @Test
    fun firstContentChangedEventIsProcessedWhenNoPriorTreeWalk() {
        assertEquals(
            AccessibilityEventWork.ProcessFully,
            AccessibilityEventWorkPolicy.decide(
                eventType = AccessibilityEventWorkPolicy.WINDOW_CONTENT_CHANGED,
                elapsedSinceForegroundMs = 200L,
                ruleWindowMs = 8_000L,
                lastTreeWalkElapsedRealtime = 0L,
                nowElapsedRealtime = 200L,
                hasPendingClick = false
            )
        )
    }

    @Test
    fun accessibilityServiceGatesEventsBeforeCacheBoundary() {
        val service = listOf(
            java.io.File("app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt"),
            java.io.File("../app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt")
        ).first { it.exists() }.readText()
        val onEvent = service
            .substringAfter("override fun onAccessibilityEvent")
            .substringBefore("private fun processAccessibilityEvent")

        assertTrue(onEvent.contains("AccessibilityEventWorkPolicy.decide"))
        assertTrue(
            onEvent.indexOf("AccessibilityEventWorkPolicy.decide") <
                onEvent.indexOf("AccessibilityNodeAccess.withCacheBoundary")
        )
        assertTrue(onEvent.contains("recordRuleWindowExpiryIfNeeded"))
        assertTrue(onEvent.contains("SkipTreeRecordExpiry"))
    }

    @Test
    fun expiryRecorderDoesNotFetchRoot() {
        val service = listOf(
            java.io.File("app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt"),
            java.io.File("../app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt")
        ).first { it.exists() }.readText()
        val recorder = service
            .substringAfter("private fun recordRuleWindowExpiryIfNeeded")
            .substringBefore("internal fun markTreeWalk")
        assertTrue(recorder.contains("root = null"))
        assertTrue(!recorder.contains("selectRoot") && !recorder.contains("NodeScanner.scan"))
    }

    @Test
    fun recoveryScansDoNotMarkTreeWalk() {
        val service = listOf(
            java.io.File("app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt"),
            java.io.File("../app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt")
        ).first { it.exists() }.readText()
        val rescanFn = service
            .substringAfter("private fun runOpeningAdRescan")
            .substringBefore("private fun currentRulePlan")
        val retryFn = service
            .substringAfter("private fun runOpeningAdRetry")
            .substringBefore("internal fun isSameOpeningAdSession")
        val liveScan = service
            .substringAfter("private fun processAccessibilityEvent")
            .substringBefore("override fun onInterrupt")
        assertTrue(!rescanFn.contains("markTreeWalk()"))
        assertTrue(!retryFn.contains("markTreeWalk()"))
        assertTrue(liveScan.contains("markTreeWalk()"))
    }
}
