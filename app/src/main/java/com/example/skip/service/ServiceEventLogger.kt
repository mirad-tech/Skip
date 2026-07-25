package com.example.skip.service

import android.accessibilityservice.AccessibilityService
import com.example.skip.data.LogRepository
import com.example.skip.data.SettingsRepository
import com.example.skip.engine.SafetyGuard
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.util.InstalledAppUtils
import com.example.skip.util.RomUtils

internal class ServiceEventLogger(
    private val service: AccessibilityService
) {
    fun logEvent(
        stage: ClickLogStage,
        eventContext: EventContext,
        pending: PendingClick? = null,
        ruleType: String = pending?.ruleSource?.toClickLogType().orEmpty(),
        ruleName: String = pending?.ruleName.orEmpty(),
        ruleId: String = pending?.ruleId.orEmpty(),
        reason: String = "",
        failureReason: String = "",
        success: Boolean? = null,
        candidateCount: Int? = null,
        bestCandidateScore: Int? = null,
        bestCandidateBounds: String = "",
        minScore: Int? = null,
        matchedKeyword: String = pending?.matchedKeyword.orEmpty(),
        clickMethod: ClickMethodLog = ClickMethodLog.None,
        actionReturnValue: Boolean? = null,
        clickResult: Boolean? = null,
        effectConfirmed: Boolean? = null,
        delayBeforeClickMs: Long? = pending?.delayBeforeClickMs,
        retryCount: Int = pending?.retryCount ?: 0,
        actualClickDelayMs: Long? = pending?.actualClickDelayMs,
        callbackQueueDelayMs: Long? = pending?.callbackQueueDelayMs,
        scanDurationMs: Long? = pending?.scanDurationMs,
        rescanReason: String = pending?.rescanReason.orEmpty(),
        detail: String = "",
        blockedReason: String = "",
        defaultRuleAreaAllowed: Boolean? = pending?.defaultRuleAreaAllowed,
        textKeywordIsStandaloneSkip: Boolean = pending?.textKeywordIsStandaloneSkip == true,
        effectConfirmReason: String = "",
        clickSkippedBySafetyMode: Boolean = false,
        isSelfAppLabelCandidate: Boolean = pending?.let {
            SafetyGuard.isSelfAppLabelCandidate(service, it.candidate.text, it.candidate.contentDescription)
        } == true,
        candidateAreaRatio: Float? = pending?.candidateAreaRatio,
        isLargeCandidateBounds: Boolean = pending?.isLargeCandidateBounds == true,
        clickTargetSource: ClickTargetSourceLog = pending?.clickTargetSource ?: ClickTargetSourceLog.None,
        ruleScope: String = pending?.ruleScope.orEmpty()
    ) {
        val safetyModeEnabled = SettingsRepository.isSafetyModeEnabled(service)
        val appName = pending?.appName ?: eventContext.packageName.takeIf { it.isNotBlank() }?.let {
            InstalledAppUtils.getAppLabel(service, it)
        }.orEmpty()
        LogRepository.addClickLog(
            context = service,
            log = ClickLogEventFactory.build(
                stage = stage,
                eventContext = eventContext,
                pending = pending,
                now = System.currentTimeMillis(),
                appName = appName,
                deviceRom = RomUtils.detectRom().label,
                safetyModeEnabled = safetyModeEnabled,
                ruleType = ruleType,
                ruleName = ruleName,
                ruleId = ruleId,
                reason = reason,
                failureReason = failureReason,
                success = success,
                candidateCount = candidateCount,
                bestCandidateScore = bestCandidateScore,
                bestCandidateBounds = bestCandidateBounds,
                minScore = minScore,
                matchedKeyword = matchedKeyword,
                clickMethod = clickMethod,
                actionReturnValue = actionReturnValue,
                clickResult = clickResult,
                effectConfirmed = effectConfirmed,
                delayBeforeClickMs = delayBeforeClickMs,
                retryCount = retryCount,
                actualClickDelayMs = actualClickDelayMs,
                callbackQueueDelayMs = callbackQueueDelayMs,
                scanDurationMs = scanDurationMs,
                rescanReason = rescanReason,
                detail = detail,
                blockedReason = blockedReason,
                defaultRuleAreaAllowed = defaultRuleAreaAllowed,
                textKeywordIsStandaloneSkip = textKeywordIsStandaloneSkip,
                effectConfirmReason = effectConfirmReason,
                clickSkippedBySafetyMode = clickSkippedBySafetyMode,
                isSelfAppLabelCandidate = isSelfAppLabelCandidate,
                isSystemPackage = SafetyGuard.isSystemPackage(eventContext.packageName),
                isLauncherPackage = SafetyGuard.isLauncherPackage(eventContext.packageName),
                isSelfPackage = SafetyGuard.isSelfPackage(service, eventContext.packageName),
                candidateAreaRatio = candidateAreaRatio,
                isLargeCandidateBounds = isLargeCandidateBounds,
                clickTargetSource = clickTargetSource,
                ruleScope = ruleScope
            )
        )
    }

}
