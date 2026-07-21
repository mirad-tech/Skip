package com.example.skip.data

import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.util.RomUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRepositoryPersistenceUnitTest {
    @Test
    fun queuedReadWaitsForAllPreviouslyEnqueuedWrites() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = ClickLogPersistenceQueue(scope)
        val allowWrite = CompletableDeferred<Unit>()
        var value = "before"
        try {
            queue.enqueue {
                allowWrite.await()
                value = "after"
            }
            val read = async { queue.execute { value } }

            allowWrite.complete(Unit)

            assertEquals("after", read.await())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun fireAndForgetQueueCapturesFailureAsResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = ClickLogPersistenceQueue(scope)
        try {
            val result = queue.enqueueCatching { error("room unavailable") }.await()

            assertTrue(result.isFailure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clickLogJsonRoundTripKeepsSchema3TimingAndRescanFields() {
        val original = ClickLog(
            timeMillis = 123L,
            packageName = "com.example.video",
            stage = ClickLogStage.ClickFailed,
            failureReason = "gesture_dispatch_failed_after_candidate_revalidation",
            actualClickDelayMs = 5184L,
            callbackQueueDelayMs = 5084L,
            scanDurationMs = 37L,
            retryCount = 2,
            rescanReason = "gesture_dispatch_failed"
        )
        val raw = JSONArray().put(LogRepository.clickLogToJson(original)).toString()

        val restored = LogRepository.deserializeClickLogPersistence(raw).single()

        assertEquals(original, restored)
    }

    @Test
    fun internalReasonCodesAreNotTruncated() {
        val reason = "candidate_changed_after_delayed_callback;gesture_dispatch_failed"

        assertTrue(reason.length > 30)
        assertEquals(reason, LogRepository.sanitizeDiagnosticReason(reason))
    }

    @Test
    fun freeTextStillUsesPrivacySanitizationLimit() {
        val freeText = "这是一段不属于内部原因码的自由文本，用于确认日志仍然执行隐私脱敏和长度限制。"

        assertTrue(LogRepository.sanitizeDiagnosticReason(freeText).length <= 30)
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptedLegacyClickLogsFailClosed() {
        LogRepository.deserializeClickLogPersistence("[{\"packageName\":\"com.example\"}]")
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptedLegacyThrottleCountsFailClosed() {
        LogRepository.deserializeThrottleCounts("{\"reason\":\"not-a-number\"}")
    }

    @Test
    fun millionRepeatedNoisyEventsOnlyPassTheSynchronousGateOnce() {
        val limiter = ClickLogRateLimiter(windowMs = 2_000L)
        val noisyLog = ClickLog(
            timeMillis = 1L,
            packageName = "com.example.video",
            stage = ClickLogStage.NoCandidateFound,
            failureReason = "no_candidate_found"
        )

        var allowedCount = 0
        repeat(1_000_000) {
            if (limiter.shouldStore(noisyLog, now = 1_000L).allowed) allowedCount++
        }

        assertEquals(1, allowedCount)
    }

    @Test
    fun failedLegacyMigrationAttemptIsReusedUntilBackoffExpires() {
        assertTrue(
            LegacyMigrationRetryPolicy.shouldReuseAttempt(
                failedAtElapsedMillis = 1_000L,
                nowElapsedMillis = 30_999L,
                retryBackoffMs = 30_000L
            )
        )
        assertFalse(
            LegacyMigrationRetryPolicy.shouldReuseAttempt(
                failedAtElapsedMillis = 1_000L,
                nowElapsedMillis = 31_000L,
                retryBackoffMs = 30_000L
            )
        )
    }

    @Test
    fun diagnosticTimingCountsOnlyAttemptAndTerminalScanSamples() {
        val logs = listOf(
            ClickLog(
                timeMillis = 1L,
                packageName = "com.example",
                stage = ClickLogStage.ClickAttempted,
                actualClickDelayMs = 120L,
                callbackQueueDelayMs = 20L,
                scanDurationMs = 99L,
                rescanReason = "candidate_changed"
            ),
            ClickLog(
                timeMillis = 2L,
                packageName = "com.example",
                stage = ClickLogStage.ClickActionSuccess,
                actualClickDelayMs = 120L,
                callbackQueueDelayMs = 20L,
                scanDurationMs = 99L,
                rescanReason = "candidate_changed"
            ),
            ClickLog(
                timeMillis = 3L,
                packageName = "com.example",
                stage = ClickLogStage.RuleMatched,
                scanDurationMs = 10L,
                rescanReason = "absolute_schedule"
            ),
            ClickLog(
                timeMillis = 4L,
                packageName = "com.example",
                stage = ClickLogStage.NoCandidateFound,
                scanDurationMs = 11L,
                rescanReason = "root_window_null"
            ),
            ClickLog(
                timeMillis = 5L,
                packageName = "com.example",
                stage = ClickLogStage.ClickEffectConfirmed,
                success = true,
                scanDurationMs = 10L,
                rescanReason = "absolute_schedule"
            ),
            ClickLog(
                timeMillis = 6L,
                packageName = "com.example",
                stage = ClickLogStage.ClickCancelledTimeWindowExpired,
                actualClickDelayMs = 5_184L,
                callbackQueueDelayMs = 5_084L
            ),
            ClickLog(
                timeMillis = 7L,
                packageName = "com.example",
                stage = ClickLogStage.RootWindowNull,
                failureReason = "root_window_null",
                rescanReason = "root_window_null"
            )
        )
        val report = JSONObject(
            DiagnosticReportRepository.buildReportJson(
                versionName = "test",
                exportTimeMillis = 10L,
                deviceInfo = RomUtils.DeviceInfo("", "", "", "", 0, RomUtils.RomType.Unknown),
                runtimeState = SettingsRepository.DiagnosticSnapshot(
                    masterEnabled = true,
                    safetyModeEnabled = false,
                    debugLogEnabled = false,
                    releaseDisclosureAccepted = true,
                    accessibilityServiceEnabled = true,
                    serviceConnectedAt = 0L,
                    serviceActiveAt = 0L,
                    serviceInterruptedAt = 0L,
                    lastClickAt = 0L,
                    lastFailureReason = "",
                    appPolicies = emptyList()
                ),
                rules = emptyList(),
                rulePackages = emptyList(),
                clickLogs = logs,
                ruleLogs = emptyList(),
                keywords = emptyList(),
                viewIdKeywords = emptyList()
            )
        )
        val summary = report.getJSONObject("diagnosticSummary")
        val timing = summary.getJSONObject("callbackTiming")
        val recovery = summary.getJSONObject("rescanRecovery")

        assertTrue(report.has("logStorage"))
        assertFalse(report.toString().contains("click_logs_quarantine_v1"))
        assertEquals(2, timing.getInt("sampleCount"))
        assertEquals(5_084L, timing.getLong("maxMs"))
        assertEquals(2, timing.getInt("scanDurationSampleCount"))
        assertEquals(3, recovery.getInt("attemptCount"))
        assertEquals(1, recovery.getInt("successCount"))
        assertEquals(2, recovery.getJSONObject("reasonCounts").getInt("root_window_null"))
        assertFalse(recovery.getJSONObject("reasonCounts").has("candidate_changed"))
    }
}
