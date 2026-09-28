package com.example.skip.data

import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage

internal class ClickLogRateLimiter(
    private val windowMs: Long = 2_000L,
    private val safetyWindowMs: Long = windowMs
) {
    private data class StoredLog(val timeMillis: Long, val windowMs: Long) {
        val expiresAt = timeMillis + windowMs
    }

    private val lastStoredAtByKey = mutableMapOf<String, StoredLog>()
    private var nextExpiryAt = Long.MAX_VALUE

    fun shouldStore(log: ClickLog, now: Long): RateLimitDecision {
        pruneExpired(now)
        val keys = log.rateLimitKeys()
            ?: return RateLimitDecision(allowed = true)
        val last = lastStoredAtByKey[keys.dedupeKey]
        if (last != null && now - last.timeMillis < last.windowMs) {
            return RateLimitDecision(allowed = false, throttleAggregateKey = keys.throttleAggregateKey)
        }
        if (last == null && lastStoredAtByKey.size >= MAX_TRACKED_KEYS) {
            // Preserve active windows; overflow noise uses the existing throttle summary.
            return RateLimitDecision(allowed = false, throttleAggregateKey = keys.throttleAggregateKey)
        }
        val effectiveWindowMs = if (log.stage == ClickLogStage.SkippedBySafety) safetyWindowMs else windowMs
        val stored = StoredLog(now, effectiveWindowMs)
        lastStoredAtByKey[keys.dedupeKey] = stored
        nextExpiryAt = minOf(nextExpiryAt, stored.expiresAt)
        return RateLimitDecision(allowed = true, throttleAggregateKey = keys.throttleAggregateKey)
    }

    fun reset() {
        lastStoredAtByKey.clear()
        nextExpiryAt = Long.MAX_VALUE
    }

    private fun pruneExpired(now: Long) {
        if (now < nextExpiryAt) return
        var nextExpiry = Long.MAX_VALUE
        val entries = lastStoredAtByKey.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next().value
            if (now - entry.timeMillis >= entry.windowMs) {
                entries.remove()
            } else {
                nextExpiry = minOf(nextExpiry, entry.expiresAt)
            }
        }
        nextExpiryAt = nextExpiry
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
        const val MAX_TRACKED_KEYS = 1_024

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
