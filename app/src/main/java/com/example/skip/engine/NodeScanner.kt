package com.example.skip.engine

import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.MatchResult
import com.example.skip.model.ScanReport
import com.example.skip.model.SkipRule

object NodeScanner {
    fun scan(
        root: AccessibilityNodeInfo,
        rules: List<SkipRule>,
        appElapsedMs: Long
    ): ScanReport {
        if (rules.isEmpty()) {
            return ScanReport(
                bestMatch = null,
                candidateCount = 0,
                bestCandidateScore = null,
                bestCandidateMinScore = null,
                bestCandidateBounds = "",
                failureReason = "no_enabled_rules"
            )
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var bestMatch: MatchResult? = null
        var candidateCount = 0
        var bestCandidate: CandidateEvaluation? = null
        var lastFailureReason = "no_candidate_found"

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            rules.forEach { rule ->
                val candidate = RuleMatcher.evaluate(node, rule, appElapsedMs)
                if (candidate != null) {
                    candidateCount++
                    if (candidate.isBetterCandidateThan(bestCandidate)) {
                        bestCandidate = candidate
                    }
                    if (candidate.failureReason.isNotBlank()) {
                        lastFailureReason = candidate.failureReason
                    }
                    val match = candidate.match
                    if (match != null && match.isBetterThan(bestMatch)) {
                        bestMatch = match
                    }
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }

        return ScanReport(
            bestMatch = bestMatch,
            candidateCount = candidateCount,
            bestCandidateScore = bestCandidate?.score,
            bestCandidateMinScore = bestCandidate?.minScore,
            bestCandidateBounds = bestCandidate?.target?.boundsString().orEmpty(),
            failureReason = when {
                candidateCount == 0 -> "no_candidate_found"
                bestMatch == null -> lastFailureReason.ifBlank { "candidate_below_threshold" }
                else -> ""
            },
            bestCandidateRuleId = bestCandidate?.rule?.id.orEmpty(),
            bestCandidateRuleName = bestCandidate?.let { candidate ->
                listOf(candidate.rule.name, candidate.matchedKeyword)
                    .filter { it.isNotBlank() }
                    .joinToString(" / ")
            }.orEmpty(),
            bestCandidateRuleSource = bestCandidate?.rule?.source,
            bestCandidateAppName = bestCandidate?.rule?.appName.orEmpty(),
            bestCandidateMatchedKeyword = bestCandidate?.matchedKeyword.orEmpty(),
            defaultRuleAreaAllowed = bestCandidate?.defaultRuleAreaAllowed,
            textKeywordIsStandaloneSkip = bestCandidate?.textKeywordIsStandaloneSkip == true,
            candidateAreaRatio = bestCandidate?.candidateAreaRatio,
            isLargeCandidateBounds = bestCandidate?.isLargeCandidateBounds == true,
            clickTargetSource = bestCandidate?.clickTargetSource
                ?: com.example.skip.model.ClickTargetSourceLog.None
        )
    }

    fun findBestMatch(
        root: AccessibilityNodeInfo,
        rules: List<SkipRule>,
        appElapsedMs: Long
    ): MatchResult? {
        return scan(root, rules, appElapsedMs).bestMatch
    }

    private fun MatchResult.isBetterThan(other: MatchResult?): Boolean {
        if (other == null) return true
        if (priority != other.priority) return priority > other.priority
        return score > other.score
    }

    private fun CandidateEvaluation.isBetterCandidateThan(other: CandidateEvaluation?): Boolean {
        if (other == null) return true
        if (rule.priority != other.rule.priority) return rule.priority > other.rule.priority
        return score > other.score
    }
}
