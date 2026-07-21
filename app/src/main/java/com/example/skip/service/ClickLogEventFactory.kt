package com.example.skip.service

import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog

internal object ClickLogEventFactory {
    fun build(
        stage: ClickLogStage,
        eventContext: EventContext,
        pending: PendingClick? = null,
        now: Long,
        appName: String,
        deviceRom: String,
        safetyModeEnabled: Boolean,
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
        delayBeforeClickMs: Long? = null,
        actualClickDelayMs: Long? = null,
        callbackQueueDelayMs: Long? = null,
        scanDurationMs: Long? = null,
        rescanReason: String = "",
        retryCount: Int = 0,
        detail: String = "",
        blockedReason: String = "",
        defaultRuleAreaAllowed: Boolean? = pending?.defaultRuleAreaAllowed,
        textKeywordIsStandaloneSkip: Boolean = pending?.textKeywordIsStandaloneSkip == true,
        standaloneSkipAllowed: Boolean = pending?.standaloneSkipAllowed == true,
        effectConfirmReason: String = "",
        clickSkippedBySafetyMode: Boolean = false,
        isSelfAppLabelCandidate: Boolean = false,
        isSystemPackage: Boolean = false,
        isLauncherPackage: Boolean = false,
        isSelfPackage: Boolean = false,
        candidateAreaRatio: Float? = pending?.candidateAreaRatio,
        isLargeCandidateBounds: Boolean = pending?.isLargeCandidateBounds == true,
        clickTargetSource: ClickTargetSourceLog = pending?.clickTargetSource ?: ClickTargetSourceLog.None,
        ruleScope: String = pending?.ruleScope.orEmpty()
    ): ClickLog {
        val candidate = pending?.candidate
        val target = pending?.target
        val gestureX = if (clickMethod == ClickMethodLog.DispatchGesture) {
            pending?.coordinateX ?: candidate?.bounds?.let { (it.left + it.right) / 2 }
        } else {
            null
        }
        val gestureY = if (clickMethod == ClickMethodLog.DispatchGesture) {
            pending?.coordinateY ?: candidate?.bounds?.let { (it.top + it.bottom) / 2 }
        } else {
            null
        }

        return ClickLog(
            timeMillis = now,
            packageName = eventContext.packageName,
            appName = appName,
            activityName = eventContext.activityName,
            ruleType = ruleType,
            ruleName = ruleName,
            ruleId = ruleId,
            ruleKind = pending?.ruleKind?.value.orEmpty(),
            planScope = if (pending?.ruleKind?.value == "precise") "precise_takeover" else ruleScope,
            effectiveRuleWindowMs = eventContext.defaultRuleWindowMs,
            candidateRelocated = pending?.candidateRelocated == true,
            relocationMethod = pending?.relocationMethod.orEmpty(),
            stage = stage,
            success = success,
            reason = reason,
            failureReason = failureReason,
            detail = detail,
            eventType = eventContext.eventType,
            eventPackageName = eventContext.eventPackageName,
            rootWindowNull = eventContext.rootWindowNull,
            windowId = eventContext.windowId,
            rootChildCount = eventContext.rootChildCount,
            canRetrieveWindowContent = eventContext.canRetrieveWindowContent,
            candidateCount = candidateCount,
            bestCandidateScore = bestCandidateScore,
            bestCandidateBounds = bestCandidateBounds,
            minScore = minScore ?: pending?.minScore,
            matchedKeyword = matchedKeyword,
            nodeText = pending?.target?.text.orEmpty(),
            contentDescription = pending?.target?.contentDescription.orEmpty(),
            viewIdResourceName = pending?.target?.viewId.orEmpty(),
            boundsInScreen = pending?.target?.boundsString().orEmpty(),
            nodeClickable = pending?.target?.nodeClickable,
            parentClickable = pending?.target?.parentClickable,
            score = pending?.score,
            area = pending?.area.orEmpty(),
            clickMethod = clickMethod,
            actionReturnValue = actionReturnValue,
            clickResult = clickResult,
            effectConfirmed = effectConfirmed,
            delayBeforeClickMs = delayBeforeClickMs,
            actualClickDelayMs = actualClickDelayMs,
            callbackQueueDelayMs = callbackQueueDelayMs,
            scanDurationMs = scanDurationMs,
            rescanReason = rescanReason,
            retryCount = retryCount,
            deviceRom = deviceRom,
            elapsedSinceAppStartMs = eventContext.elapsedSinceAppStartMs,
            foregroundPackage = eventContext.foregroundPackage,
            foregroundStartTimeMillis = eventContext.foregroundStartTimeMillis,
            elapsedSinceForegroundMs = eventContext.elapsedSinceForegroundMs,
            defaultRuleWindowMs = eventContext.defaultRuleWindowMs,
            isWithinDefaultRuleWindow = eventContext.isWithinDefaultRuleWindow,
            ruleScope = ruleScope,
            timeWindowDecision = eventContext.timeWindowDecision,
            isSystemPackage = isSystemPackage,
            isLauncherPackage = isLauncherPackage,
            isSelfPackage = isSelfPackage,
            isSelfAppLabelCandidate = isSelfAppLabelCandidate,
            blockedBySafety = stage == ClickLogStage.SkippedBySafety,
            blockedReason = blockedReason,
            defaultRuleAreaAllowed = defaultRuleAreaAllowed,
            textKeywordIsStandaloneSkip = textKeywordIsStandaloneSkip,
            standaloneSkipAllowed = standaloneSkipAllowed,
            effectConfirmReason = effectConfirmReason,
            safetyModeEnabled = safetyModeEnabled,
            clickSkippedBySafetyMode = clickSkippedBySafetyMode,
            candidateBounds = candidate?.boundsString().orEmpty(),
            candidateCenterX = candidate?.bounds?.let { (it.left + it.right) / 2 },
            candidateCenterY = candidate?.bounds?.let { (it.top + it.bottom) / 2 },
            clickedNodeBounds = target?.boundsString().orEmpty(),
            clickedNodeClassName = target?.className.orEmpty(),
            clickedNodeText = target?.text.orEmpty(),
            clickedNodeViewId = target?.viewId.orEmpty(),
            clickedParentDepth = pending?.clickedParentDepth,
            candidateAreaRatio = candidateAreaRatio,
            gestureX = gestureX,
            gestureY = gestureY,
            isLargeCandidateBounds = isLargeCandidateBounds,
            isFixedCoordinateClick = clickTargetSource == ClickTargetSourceLog.CoordinateFallback ||
                clickTargetSource == ClickTargetSourceLog.FixedPositionForbidden,
            clickTargetSource = clickTargetSource
        )
    }
}
