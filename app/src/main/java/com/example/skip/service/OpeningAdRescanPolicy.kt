package com.example.skip.service

import com.example.skip.model.ClickLogStage

internal object OpeningAdRescanPolicy {
    val delaysMs = listOf(120L, 320L, 720L, 1_500L, 3_000L)

    fun shouldSchedule(
        stage: ClickLogStage,
        isWithinDefaultRuleWindow: Boolean,
        hasPendingClick: Boolean,
        hasActiveRules: Boolean
    ): Boolean {
        if (!isWithinDefaultRuleWindow || hasPendingClick || !hasActiveRules) return false
        return stage == ClickLogStage.NoCandidateFound ||
            stage == ClickLogStage.SkippedByLowScore
    }
}
