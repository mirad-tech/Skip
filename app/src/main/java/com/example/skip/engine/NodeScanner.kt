package com.example.skip.engine

import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.MatchResult
import com.example.skip.model.ScanReport
import com.example.skip.model.SkipRule
import com.example.skip.util.AccessibilityNodeAccess

object NodeScanner {
    fun scan(
        root: AccessibilityNodeInfo,
        rules: List<SkipRule>,
        appElapsedMs: Long,
        currentActivityName: String = ""
    ): ScanReport {
        val scopedRules = filterRulesForActivity(rules, currentActivityName)
        if (scopedRules.isEmpty()) {
            return ScanReport(
                bestMatch = null,
                candidateCount = 0,
                bestCandidateScore = null,
                bestCandidateMinScore = null,
                bestCandidateBounds = "",
                failureReason = if (rules.isEmpty()) "no_enabled_rules" else "activity_scope_mismatch"
            )
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var bestMatch: MatchResult? = null
        var candidateCount = 0
        var bestCandidate: CandidateEvaluation? = null
        var visitedCount = 0

        while (queue.isNotEmpty() && visitedCount < NodeScanBudget.MAX_VISITED_NODES) {
            val node = queue.removeFirst()
            visitedCount++

            if (node.isVisibleToUser) {
                val signals = ClickExecutor.describeRuleCandidateSignals(node)
                var resolution: ClickCandidateResolution? = null
                scopedRules.forEach { rule ->
                    if (!ScoreEvaluator.hasPotentialRuleMatch(signals, rule)) return@forEach
                    val candidateResolution = resolution
                        ?: ClickExecutor.resolveCandidate(node, signals).also { resolution = it }
                    val candidate = RuleMatcher.evaluateResolved(
                        node = node,
                        rule = rule,
                        appElapsedMs = appElapsedMs,
                        resolution = candidateResolution
                    )
                    if (candidate != null) {
                        candidateCount++
                        if (candidate.isBetterCandidateThan(bestCandidate)) {
                            bestCandidate = candidate
                        }
                        val match = candidate.match
                        if (match != null && match.isBetterThan(bestMatch)) {
                            bestMatch = match
                        }
                    }
                }
            }

            if (node.childCount > 0) {
                for (index in 0 until node.childCount) {
                    if (!NodeScanBudget.canEnqueueChild(visitedCount, queue.size)) break
                    AccessibilityNodeAccess.child(node, index)?.let(queue::add)
                }
            }
        }

        return ScanReport(
            bestMatch = bestMatch,
            candidateCount = candidateCount,
            bestCandidateScore = bestCandidate?.score,
            bestCandidateMinScore = bestCandidate?.minScore,
            bestCandidateBounds = bestCandidate?.target?.boundsString().orEmpty(),
            failureReason = failureReasonForScan(
                candidateCount = candidateCount,
                bestMatchFound = bestMatch != null,
                bestCandidateFailureReason = bestCandidate?.failureReason,
                budgetExhausted = visitedCount >= NodeScanBudget.MAX_VISITED_NODES
            ),
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
        appElapsedMs: Long,
        currentActivityName: String = ""
    ): MatchResult? {
        return scan(root, rules, appElapsedMs, currentActivityName).bestMatch
    }

    fun filterRulesForActivity(
        rules: List<SkipRule>,
        currentActivityName: String
    ): List<SkipRule> {
        val current = currentActivityName.trim()
        return rules.filter { rule ->
            val scope = rule.activityName.trim()
            scope.isBlank() ||
                scope == "*" ||
                (current.isNotBlank() && scope.equals(current, ignoreCase = true))
        }
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

    internal fun failureReasonForScan(
        candidateCount: Int,
        bestMatchFound: Boolean,
        bestCandidateFailureReason: String?,
        budgetExhausted: Boolean = false
    ): String {
        return when {
            bestMatchFound -> ""
            budgetExhausted && candidateCount == 0 -> "scan_budget_exhausted"
            candidateCount == 0 -> "no_candidate_found"
            else -> bestCandidateFailureReason.orEmpty()
                .ifBlank { "candidate_below_threshold" }
        }
    }
}
