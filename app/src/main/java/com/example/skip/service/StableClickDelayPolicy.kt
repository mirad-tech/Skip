package com.example.skip.service

import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.RuleSource
import java.util.Locale

internal object StableClickDelayPolicy {
    private const val HIGH_CONFIDENCE_SCORE_MARGIN = 50

    fun delayMs(
        ruleSource: RuleSource,
        score: Int,
        minScore: Int,
        candidateViewId: String,
        candidateText: String,
        candidateContentDescription: String,
        isLargeCandidateBounds: Boolean,
        textKeywordIsStandaloneSkip: Boolean,
        clickTargetSource: ClickTargetSourceLog,
        defaultDelayMs: Long
    ): Long {
        if (ruleSource != RuleSource.BuiltIn) return defaultDelayMs
        if (score < minScore + HIGH_CONFIDENCE_SCORE_MARGIN) return defaultDelayMs
        if (isLargeCandidateBounds || textKeywordIsStandaloneSkip) return defaultDelayMs
        if (clickTargetSource != ClickTargetSourceLog.NodeSelf &&
            clickTargetSource != ClickTargetSourceLog.ClickableParent
        ) {
            return defaultDelayMs
        }
        return if (hasStrongSkipSignal(candidateViewId, candidateText, candidateContentDescription)) {
            0L
        } else {
            defaultDelayMs
        }
    }

    private fun hasStrongSkipSignal(
        candidateViewId: String,
        candidateText: String,
        candidateContentDescription: String
    ): Boolean {
        val normalizedId = candidateViewId.normalizeForStableDelay()
        if (normalizedId.contains("skip")) return true
        val label = listOf(candidateText, candidateContentDescription)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return label.contains("跳过") && normalizedId.contains("time")
    }

    private fun String.normalizeForStableDelay(): String {
        return lowercase(Locale.ROOT)
            .replace("-", "_")
            .replace(".", "_")
            .replace(":", "_")
    }
}
