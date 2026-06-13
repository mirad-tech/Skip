package com.example.skip.service

import com.example.skip.engine.ClickAttempt
import com.example.skip.engine.ClickTargetInfo
import com.example.skip.engine.CoordinateFallbackMatch
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.MatchResult
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule

internal data class EventContext(
    val eventType: Int,
    val eventPackageName: String,
    val packageName: String,
    val activityName: String,
    val windowId: Int,
    val rootWindowNull: Boolean,
    val rootChildCount: Int?,
    val canRetrieveWindowContent: Boolean,
    val elapsedSinceAppStartMs: Long?,
    val foregroundPackage: String,
    val foregroundStartTimeMillis: Long?,
    val elapsedSinceForegroundMs: Long?,
    val defaultRuleWindowMs: Long,
    val isWithinDefaultRuleWindow: Boolean,
    val timeWindowDecision: String
)

internal data class ClickMatchSnapshot(
    val ruleId: String,
    val ruleName: String,
    val ruleSource: RuleSource,
    val appName: String,
    val score: Int,
    val minScore: Int,
    val matchedKeyword: String,
    val area: String,
    val target: ClickTargetInfo,
    val candidate: ClickTargetInfo,
    val clickedParentDepth: Int,
    val candidateAreaRatio: Float,
    val isLargeCandidateBounds: Boolean,
    val defaultRuleAreaAllowed: Boolean?,
    val textKeywordIsStandaloneSkip: Boolean,
    val clickTargetSource: ClickTargetSourceLog
) {
    companion object {
        fun from(match: MatchResult): ClickMatchSnapshot {
            return ClickMatchSnapshot(
                ruleId = match.ruleId,
                ruleName = match.ruleName,
                ruleSource = match.ruleSource,
                appName = match.appName,
                score = match.score,
                minScore = match.minScore,
                matchedKeyword = match.matchedKeyword,
                area = match.area.value,
                target = match.target,
                candidate = match.candidate,
                clickedParentDepth = match.clickedParentDepth,
                candidateAreaRatio = match.candidateAreaRatio,
                isLargeCandidateBounds = match.isLargeCandidateBounds,
                defaultRuleAreaAllowed = match.defaultRuleAreaAllowed,
                textKeywordIsStandaloneSkip = match.textKeywordIsStandaloneSkip,
                clickTargetSource = match.clickTargetSource
            )
        }
    }
}

internal data class PendingClick(
    val packageName: String,
    val appName: String,
    val ruleId: String,
    val ruleName: String,
    val ruleSource: RuleSource,
    val ruleScope: String,
    val score: Int,
    val minScore: Int,
    val matchedKeyword: String,
    val area: String,
    val target: ClickTargetInfo,
    val candidate: ClickTargetInfo,
    val clickedParentDepth: Int,
    val candidateAreaRatio: Float,
    val isLargeCandidateBounds: Boolean,
    val defaultRuleAreaAllowed: Boolean?,
    val textKeywordIsStandaloneSkip: Boolean,
    val clickTargetSource: ClickTargetSourceLog,
    val startedAt: Long,
    val eventContext: EventContext,
    val activeRules: List<SkipRule> = emptyList(),
    val signature: String = "",
    val delayBeforeClickMs: Long? = null,
    val retryCount: Int = 0,
    val firstAttempt: ClickAttempt? = null,
    val coordinateX: Int? = null,
    val coordinateY: Int? = null
)

internal object ClickFlowStateMachine {
    fun previewFromMatch(
        packageName: String,
        appName: String,
        match: ClickMatchSnapshot,
        eventContext: EventContext,
        startedAt: Long = System.currentTimeMillis()
    ): PendingClick {
        return basePending(
            packageName = packageName,
            appName = appName,
            match = match,
            eventContext = eventContext,
            startedAt = startedAt
        )
    }

    fun startFromMatch(
        packageName: String,
        appName: String,
        match: ClickMatchSnapshot,
        activeRules: List<SkipRule>,
        signature: String,
        eventContext: EventContext,
        delayBeforeClickMs: Long?,
        startedAt: Long = System.currentTimeMillis()
    ): PendingClick {
        return basePending(
            packageName = packageName,
            appName = appName,
            match = match,
            eventContext = eventContext,
            startedAt = startedAt
        ).copy(
            activeRules = activeRules,
            signature = signature,
            delayBeforeClickMs = delayBeforeClickMs
        )
    }

    fun startCoordinateFallback(
        packageName: String,
        appName: String,
        fallback: CoordinateFallbackMatch,
        activeRules: List<SkipRule>,
        signature: String,
        eventContext: EventContext,
        delayBeforeClickMs: Long?,
        startedAt: Long = System.currentTimeMillis()
    ): PendingClick {
        val rule = fallback.rule
        return PendingClick(
            packageName = packageName,
            appName = appName,
            ruleId = rule.id,
            ruleName = "${rule.name} / 坐标兜底",
            ruleSource = rule.source,
            ruleScope = rule.source.toRuleScope(),
            score = rule.minScore,
            minScore = rule.minScore,
            matchedKeyword = "coordinate_fallback",
            area = rule.area.value,
            target = fallback.target,
            candidate = fallback.target,
            clickedParentDepth = 0,
            candidateAreaRatio = 0f,
            isLargeCandidateBounds = false,
            defaultRuleAreaAllowed = null,
            textKeywordIsStandaloneSkip = false,
            clickTargetSource = ClickTargetSourceLog.CoordinateFallback,
            startedAt = startedAt,
            eventContext = eventContext,
            activeRules = activeRules,
            signature = signature,
            delayBeforeClickMs = delayBeforeClickMs,
            coordinateX = fallback.x,
            coordinateY = fallback.y
        )
    }

    fun relocateToMatch(pending: PendingClick, match: ClickMatchSnapshot): PendingClick {
        return pending.copy(
            ruleId = match.ruleId,
            ruleName = match.ruleName,
            ruleSource = match.ruleSource,
            ruleScope = match.ruleSource.toRuleScope(),
            score = match.score,
            minScore = match.minScore,
            matchedKeyword = match.matchedKeyword,
            area = match.area,
            target = match.target,
            candidate = match.candidate,
            clickedParentDepth = match.clickedParentDepth,
            candidateAreaRatio = match.candidateAreaRatio,
            isLargeCandidateBounds = match.isLargeCandidateBounds,
            defaultRuleAreaAllowed = match.defaultRuleAreaAllowed,
            textKeywordIsStandaloneSkip = match.textKeywordIsStandaloneSkip,
            clickTargetSource = match.clickTargetSource
        )
    }

    private fun basePending(
        packageName: String,
        appName: String,
        match: ClickMatchSnapshot,
        eventContext: EventContext,
        startedAt: Long
    ): PendingClick {
        return PendingClick(
            packageName = packageName,
            appName = appName,
            ruleId = match.ruleId,
            ruleName = match.ruleName,
            ruleSource = match.ruleSource,
            ruleScope = match.ruleSource.toRuleScope(),
            score = match.score,
            minScore = match.minScore,
            matchedKeyword = match.matchedKeyword,
            area = match.area,
            target = match.target,
            candidate = match.candidate,
            clickedParentDepth = match.clickedParentDepth,
            candidateAreaRatio = match.candidateAreaRatio,
            isLargeCandidateBounds = match.isLargeCandidateBounds,
            defaultRuleAreaAllowed = match.defaultRuleAreaAllowed,
            textKeywordIsStandaloneSkip = match.textKeywordIsStandaloneSkip,
            clickTargetSource = match.clickTargetSource,
            startedAt = startedAt,
            eventContext = eventContext
        )
    }
}

internal fun RuleSource.toClickLogType(): String {
    return when (this) {
        RuleSource.BuiltIn -> "default"
        RuleSource.UserSimple -> "custom"
        RuleSource.JsonFile -> "json"
        RuleSource.Subscription -> "json"
    }
}

internal fun RuleSource.toRuleScope(): String {
    return when (this) {
        RuleSource.BuiltIn -> "default_splash_only"
        RuleSource.UserSimple,
        RuleSource.JsonFile,
        RuleSource.Subscription -> "custom_splash_only"
    }
}
