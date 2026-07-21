package com.example.skip.service

import com.example.skip.model.ClickLogStage

internal object OpeningAdRescanPolicy {
    val delaysMs = listOf(120L, 320L, 720L, 1_500L, 3_000L)

    fun absoluteOffsetsMs(ruleWindowMs: Long): List<Long> {
        if (ruleWindowMs <= 0L) return emptyList()
        return (delaysMs + listOf(ruleWindowMs - 3_000L, ruleWindowMs - 1_000L))
            .filter { offset -> offset > 0L && offset < ruleWindowMs }
            .distinct()
            .sorted()
    }

    fun remainingDelaysMs(
        foregroundStartTimeMillis: Long,
        nowMillis: Long,
        ruleWindowMs: Long
    ): List<Long> {
        if (foregroundStartTimeMillis <= 0L) return emptyList()
        return absoluteOffsetsMs(ruleWindowMs)
            .map { offset -> foregroundStartTimeMillis + offset - nowMillis }
            .filter { delay -> delay > 0L }
    }

    fun shouldSchedule(
        stage: ClickLogStage,
        isWithinDefaultRuleWindow: Boolean,
        hasPendingClick: Boolean,
        hasActiveRules: Boolean
    ): Boolean {
        if (!isWithinDefaultRuleWindow || hasPendingClick || !hasActiveRules) return false
        return stage == ClickLogStage.RootWindowNull ||
            stage == ClickLogStage.NoCandidateFound ||
            stage == ClickLogStage.SkippedByLowScore
    }
}
