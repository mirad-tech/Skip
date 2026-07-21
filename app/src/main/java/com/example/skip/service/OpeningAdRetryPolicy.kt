package com.example.skip.service

internal object OpeningAdRetryPolicy {
    val delaysMs = listOf(80L, 250L, 600L)

    private val retryableReasons = setOf(
        "root_window_null",
        "root_window_null_before_click",
        "no_candidate_found",
        "score_below_min_score",
        "candidate_below_threshold",
        "candidate_lost_before_click",
        "candidate_changed_before_click",
        "current_target_root_missing",
        "current_target_missing",
        "coordinate_root_missing",
        "coordinate_target_missing",
        "gesture_cancelled",
        "gesture_dispatch_returned_false",
        "coordinate_fallback_cancelled",
        "coordinate_fallback_dispatch_returned_false"
    )

    fun nextDelayMs(retriesPerformed: Int): Long? {
        return delaysMs.getOrNull(retriesPerformed.coerceAtLeast(0))
    }

    fun shouldRetry(
        reason: String,
        retriesPerformed: Int,
        isWithinRuleWindow: Boolean,
        samePackage: Boolean = true,
        sameActivity: Boolean = true,
        activeTextInput: Boolean = false
    ): Boolean {
        if (!isWithinRuleWindow || !samePackage || !sameActivity || activeTextInput) return false
        if (nextDelayMs(retriesPerformed) == null) return false
        return reason.trim() in retryableReasons
    }
}

internal object OpeningAdRecoveryGate {
    fun canRun(
        masterEnabled: Boolean,
        sessionKey: String,
        terminalSessionKey: String?
    ): Boolean {
        return masterEnabled && sessionKey.isNotBlank() && terminalSessionKey != sessionKey
    }
}
