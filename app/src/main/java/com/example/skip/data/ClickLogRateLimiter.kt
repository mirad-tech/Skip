package com.example.skip.data

import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage

internal class ClickLogRateLimiter(
    private val windowMs: Long = 2_000L,
    private val safetyWindowMs: Long = windowMs
) {
    private val lastStoredAtByKey = mutableMapOf<String, Long>()

    fun shouldStore(log: ClickLog, now: Long): RateLimitDecision {
        val keys = log.rateLimitKeys()
            ?: return RateLimitDecision(allowed = true)
        val last = lastStoredAtByKey[keys.dedupeKey]
        if (last == null) {
            lastStoredAtByKey[keys.dedupeKey] = now
            return RateLimitDecision(allowed = true, throttleAggregateKey = keys.throttleAggregateKey)
        }
        val effectiveWindowMs = if (log.stage == ClickLogStage.SkippedBySafety) safetyWindowMs else windowMs
        if (now - last < effectiveWindowMs) {
            return RateLimitDecision(allowed = false, throttleAggregateKey = keys.throttleAggregateKey)
        }
        lastStoredAtByKey[keys.dedupeKey] = now
        return RateLimitDecision(allowed = true, throttleAggregateKey = keys.throttleAggregateKey)
    }

    fun reset() {
        lastStoredAtByKey.clear()
    }

    private fun ClickLog.rateLimitKeys(): ClickLogRateLimitKeys? {
        val reason = LogRepository.sanitizeDiagnosticReason(
            failureReason.ifBlank { this.reason }.ifBlank { blockedReason }
        )
        if (reason in NON_THROTTLED_REASONS || !stage.isNoisyStage()) return null
        val aggregateKey = listOf(packageName, stage.value, reason)
            .filter { it.isNotBlank() }
            .joinToString("|")
        val dedupeKey = if (stage == ClickLogStage.SkippedBySafety) {
            "$aggregateKey|${foregroundStartTimeMillis?.toString() ?: "unknown_session"}"
        } else {
            aggregateKey
        }
        return ClickLogRateLimitKeys(
            dedupeKey = dedupeKey,
            throttleAggregateKey = aggregateKey
        )
    }

    private fun ClickLogStage.isNoisyStage(): Boolean {
        return this == ClickLogStage.NoCandidateFound ||
            this == ClickLogStage.SkippedByTimeWindow ||
            this == ClickLogStage.SkippedByCooldown ||
            this == ClickLogStage.SkippedBySafety ||
            this == ClickLogStage.RootWindowNull ||
            this == ClickLogStage.EventPackageNull ||
            this == ClickLogStage.SkippedSelfPackage
    }

    private companion object {
        val NON_THROTTLED_REASONS = setOf(
            "candidate_lost_before_click",
            "candidate_changed_before_click"
        )
    }
}

internal data class ClickLogRateLimitKeys(
    val dedupeKey: String,
    val throttleAggregateKey: String
)

internal data class RateLimitDecision(
    val allowed: Boolean,
    val throttleAggregateKey: String = ""
)
