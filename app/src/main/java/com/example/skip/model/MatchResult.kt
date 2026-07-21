package com.example.skip.model

import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.engine.ClickTargetInfo

data class MatchResult(
    val sourceNode: AccessibilityNodeInfo,
    val clickNode: AccessibilityNodeInfo,
    val ruleId: String,
    val ruleName: String,
    val ruleSource: RuleSource,
    val appName: String,
    val score: Int,
    val minScore: Int,
    val priority: Int,
    val matchedKeyword: String,
    val area: RuleArea,
    val target: ClickTargetInfo,
    val candidate: ClickTargetInfo,
    val clickedParentDepth: Int,
    val candidateAreaRatio: Float,
    val isLargeCandidateBounds: Boolean,
    val defaultRuleAreaAllowed: Boolean?,
    val textKeywordIsStandaloneSkip: Boolean,
    val standaloneSkipAllowed: Boolean,
    val clickTargetSource: ClickTargetSourceLog,
    val ruleKind: RuleKind = RuleKind.Standard
)

data class ScanReport(
    val bestMatch: MatchResult?,
    val candidateCount: Int,
    val bestCandidateScore: Int?,
    val bestCandidateMinScore: Int?,
    val bestCandidateBounds: String,
    val failureReason: String,
    val bestCandidateRuleId: String = "",
    val bestCandidateRuleName: String = "",
    val bestCandidateRuleSource: RuleSource? = null,
    val bestCandidateAppName: String = "",
    val bestCandidateMatchedKeyword: String = "",
    val defaultRuleAreaAllowed: Boolean? = null,
    val textKeywordIsStandaloneSkip: Boolean = false,
    val candidateAreaRatio: Float? = null,
    val isLargeCandidateBounds: Boolean = false,
    val clickTargetSource: ClickTargetSourceLog = ClickTargetSourceLog.None
)
