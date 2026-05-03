package com.example.skip.engine

import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.MatchResult
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule

object RuleMatcher {
    fun evaluate(
        node: AccessibilityNodeInfo,
        rule: SkipRule,
        appElapsedMs: Long
    ): CandidateEvaluation? {
        val defaultRule = rule.source == RuleSource.BuiltIn
        val clickSelection = ClickExecutor.findClickableSelection(node, defaultRule)
        val scoredRule = ScoreEvaluator.evaluate(node, rule, appElapsedMs, clickSelection) ?: return null
        val candidate = ClickExecutor.describeTarget(node)
        val target = clickSelection?.target ?: candidate
        val match = if (scoredRule.passesMinScore && clickSelection != null) {
            MatchResult(
                sourceNode = node,
                clickNode = clickSelection.node,
                ruleId = rule.id,
                ruleName = "${rule.name} / ${scoredRule.matchedKeyword}",
                ruleSource = rule.source,
                appName = rule.appName,
                score = scoredRule.score,
                minScore = scoredRule.minScore,
                priority = rule.priority,
                matchedKeyword = scoredRule.matchedKeyword,
                area = scoredRule.area,
                target = target,
                candidate = candidate,
                clickedParentDepth = clickSelection.parentDepth,
                candidateAreaRatio = scoredRule.candidateAreaRatio,
                isLargeCandidateBounds = scoredRule.isLargeCandidateBounds,
                defaultRuleAreaAllowed = scoredRule.defaultRuleAreaAllowed,
                textKeywordIsStandaloneSkip = scoredRule.textKeywordIsStandaloneSkip,
                clickTargetSource = clickSelection.source
            )
        } else {
            null
        }
        return CandidateEvaluation(
            rule = rule,
            score = scoredRule.score,
            minScore = scoredRule.minScore,
            matchedKeyword = scoredRule.matchedKeyword,
            target = candidate,
            match = match,
            failureReason = when {
                scoredRule.failureReason.isNotBlank() -> scoredRule.failureReason
                clickSelection == null -> "no_clickable_target"
                else -> ""
            },
            defaultRuleAreaAllowed = scoredRule.defaultRuleAreaAllowed,
            textKeywordIsStandaloneSkip = scoredRule.textKeywordIsStandaloneSkip,
            candidateAreaRatio = scoredRule.candidateAreaRatio,
            isLargeCandidateBounds = scoredRule.isLargeCandidateBounds,
            clickTargetSource = match?.clickTargetSource ?: ClickTargetSourceLog.FixedPositionForbidden
        )
    }
}

data class CandidateEvaluation(
    val rule: SkipRule,
    val score: Int,
    val minScore: Int,
    val matchedKeyword: String,
    val target: ClickTargetInfo,
    val match: MatchResult?,
    val failureReason: String,
    val defaultRuleAreaAllowed: Boolean? = null,
    val textKeywordIsStandaloneSkip: Boolean = false,
    val candidateAreaRatio: Float = 0f,
    val isLargeCandidateBounds: Boolean = false,
    val clickTargetSource: ClickTargetSourceLog = ClickTargetSourceLog.None
)
