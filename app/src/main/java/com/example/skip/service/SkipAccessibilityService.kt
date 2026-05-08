package com.example.skip.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.skip.data.IconManager
import com.example.skip.data.LogRepository
import com.example.skip.data.RuleRepository
import com.example.skip.data.SettingsRepository
import com.example.skip.engine.ClickAttempt
import com.example.skip.engine.ClickExecutor
import com.example.skip.engine.NodeScanner
import com.example.skip.engine.SafetyGuard
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.MatchResult
import com.example.skip.model.RuleSource
import com.example.skip.model.ScanReport
import com.example.skip.model.SkipRule
import com.example.skip.util.InstalledAppUtils
import com.example.skip.util.RomUtils

class SkipAccessibilityService : AccessibilityService() {
    private var foregroundPackage: String? = null
    private var foregroundSince = 0L
    private val lastRuleClickAt = mutableMapOf<String, Long>()
    private var lastClickSignature: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingClick: PendingClick? = null
    private var lastWindowStateChangedAt = 0L
    private val lastToastAt = mutableMapOf<String, Long>()
    private var popupView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsRepository.markServiceConnected(this)
        RuleRepository.disableRulesForPackage(this, packageName)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!event.isSupportedEvent()) return

        val now = System.currentTimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowStateChangedAt = now
        }
        SettingsRepository.markServiceActive(this)

        val root = rootInActiveWindow
        val eventPackageName = event.packageName?.toString().orEmpty()
        val currentPackage = eventPackageName.ifBlank {
            root?.packageName?.toString().orEmpty()
        }
        val activityName = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString().orEmpty()
        } else {
            ""
        }
        val eventContext = EventContext(
            eventType = event.eventType,
            eventPackageName = eventPackageName,
            packageName = currentPackage,
            activityName = activityName,
            windowId = event.windowId,
            rootWindowNull = root == null,
            rootChildCount = root?.childCount,
            canRetrieveWindowContent = serviceInfo?.canRetrieveWindowContent == true,
            elapsedSinceAppStartMs = now - foregroundSince
        )

        logEvent(ClickLogStage.ServiceEventReceived, eventContext)

        if (currentPackage.isBlank()) {
            logEvent(
                stage = ClickLogStage.EventPackageNull,
                eventContext = eventContext,
                failureReason = "event_package_null"
            )
            SettingsRepository.setLastFailureReason(this, "event_package_null")
            return
        }

        updateForegroundWindow(currentPackage, now)
        val activeContext = eventContext.copy(elapsedSinceAppStartMs = now - foregroundSince)

        if (SafetyGuard.isSelfPackage(this, currentPackage)) {
            logEvent(
                stage = ClickLogStage.SkippedSelfPackage,
                eventContext = activeContext,
                failureReason = "skipped_self_package"
            )
            return
        }

        if (SafetyGuard.isProtectedPackage(currentPackage)) {
            val ownLabelOnLauncher = root?.containsOwnAppLabel(currentPackage) == true
            val blockedReason = if (ownLabelOnLauncher) {
                "own_app_label_on_launcher"
            } else {
                SafetyGuard.protectedPackageReason(currentPackage)
                    .ifBlank { "system_or_launcher_package" }
            }
            logEvent(
                stage = ClickLogStage.SkippedBySafety,
                eventContext = activeContext,
                ruleType = "safety",
                failureReason = "skipped_by_safety",
                blockedReason = blockedReason,
                isSelfAppLabelCandidate = ownLabelOnLauncher
            )
            SettingsRepository.setLastFailureReason(this, "skipped_by_safety")
            showDebugToast("已跳过自动点击：安全保护应用", "safety:$currentPackage")
            return
        }

        if (!SettingsRepository.isMasterEnabled(this)) {
            logEvent(
                stage = ClickLogStage.SkippedByDisabledSetting,
                eventContext = activeContext,
                failureReason = "skipped_by_disabled_setting"
            )
            return
        }

        if (root == null) {
            logEvent(
                stage = ClickLogStage.RootWindowNull,
                eventContext = activeContext,
                failureReason = "root_window_null"
            )
            SettingsRepository.setLastFailureReason(this, "root_window_null")
            return
        }

        if (pendingClick != null) return

        val appElapsedMs = now - foregroundSince
        val customRules = RuleRepository.getEnabledCustomRulesForPackage(this, currentPackage)
        val blacklisted = SettingsRepository.isBlacklisted(this, currentPackage)
        val defaultRuleEligible = !blacklisted && appElapsedMs <= RuleRepository.DEFAULT_RULE_WINDOW_MS
        val defaultRule = if (defaultRuleEligible) {
            listOf(RuleRepository.getBuiltInRuleForPackage(this, currentPackage))
        } else {
            emptyList()
        }
        val rules = (customRules + defaultRule)
            .sortedWith(compareByDescending<SkipRule> { it.priority }.thenBy { it.createdAt })

        if (blacklisted && customRules.isEmpty()) {
            logEvent(
                stage = ClickLogStage.SkippedByBlacklist,
                eventContext = activeContext,
                ruleType = "blacklist",
                failureReason = "skipped_by_blacklist"
            )
            return
        }

        if (!defaultRuleEligible && customRules.isEmpty()) {
            logEvent(
                stage = ClickLogStage.SkippedByTimeWindow,
                eventContext = activeContext,
                ruleType = "default",
                ruleName = "默认开屏跳过",
                failureReason = "default_rule_time_window_expired"
            )
            return
        }

        if (rules.isEmpty()) {
            SettingsRepository.setLastFailureReason(this, "no_enabled_rules")
            return
        }

        val activeRules = rules.filter { rule ->
            val last = lastRuleClickAt[rule.id] ?: 0L
            appElapsedMs <= rule.validDurationMs && now - last >= rule.cooldownMs
        }
        if (activeRules.isEmpty()) {
            logEvent(
                stage = ClickLogStage.SkippedByTimeWindow,
                eventContext = activeContext,
                failureReason = "rule_time_window_or_cooldown"
            )
            return
        }

        val scan = NodeScanner.scan(root, activeRules, appElapsedMs)
        logScan(scan, activeContext)
        val match = scan.bestMatch
        if (match == null) {
            val stage = when {
                scan.candidateCount == 0 -> ClickLogStage.NoCandidateFound
                scan.failureReason == "score_below_min_score" ||
                    scan.failureReason == "candidate_below_threshold" -> ClickLogStage.SkippedByLowScore
                else -> ClickLogStage.ClickFailed
            }
            logEvent(
                stage = stage,
                eventContext = activeContext,
                candidateCount = scan.candidateCount,
                bestCandidateScore = scan.bestCandidateScore,
                bestCandidateBounds = scan.bestCandidateBounds,
                minScore = scan.bestCandidateMinScore,
                failureReason = scan.failureReason
            )
            SettingsRepository.setLastFailureReason(this, scan.failureReason)
            return
        }

        val signature = "$currentPackage:${match.ruleId}:${match.target.boundsString()}"
        val lastAnyRuleClick = lastRuleClickAt.values.maxOrNull() ?: 0L
        if (signature == lastClickSignature && now - lastAnyRuleClick < REPEAT_CLICK_GUARD_MS) return

        logMatch(match, activeContext, scan)
        if (SettingsRepository.isSafetyModeEnabled(this)) {
            logEvent(
                stage = ClickLogStage.ClickSkippedBySafetyMode,
                eventContext = activeContext,
                pending = PendingClick.fromMatch(match, activeContext),
                candidateCount = scan.candidateCount,
                bestCandidateScore = scan.bestCandidateScore,
                bestCandidateBounds = scan.bestCandidateBounds,
                minScore = match.minScore,
                failureReason = "click_skipped_by_safety_mode",
                reason = "click_skipped_by_safety_mode",
                clickSkippedBySafetyMode = true
            )
            SettingsRepository.setLastFailureReason(this, "click_skipped_by_safety_mode")
            return
        }
        startStableClick(currentPackage, match, activeRules, signature, activeContext)
    }

    override fun onInterrupt() {
        SettingsRepository.markServiceInterrupted(this)
        hidePopup()
    }

    override fun onDestroy() {
        hidePopup()
        super.onDestroy()
    }

    private fun updateForegroundWindow(packageName: String, now: Long) {
        if (packageName.isBlank()) return
        if (packageName != foregroundPackage) {
            foregroundPackage = packageName
            foregroundSince = now
            lastClickSignature = null
        }
    }

    private fun AccessibilityEvent.isSupportedEvent(): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    private fun startStableClick(
        packageName: String,
        match: MatchResult,
        activeRules: List<SkipRule>,
        signature: String,
        eventContext: EventContext
    ) {
        val pending = PendingClick(
            packageName = packageName,
            appName = match.appName.ifBlank {
                InstalledAppUtils.getAppLabel(this, packageName)
            },
            ruleId = match.ruleId,
            ruleName = match.ruleName,
            ruleSource = match.ruleSource,
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
            clickTargetSource = match.clickTargetSource,
            startedAt = System.currentTimeMillis(),
            eventContext = eventContext,
            activeRules = activeRules,
            signature = signature,
            delayBeforeClickMs = STABLE_CLICK_DELAY_MS
        )
        pendingClick = pending
        mainHandler.postDelayed(
            { relocateAndClick(pending) },
            STABLE_CLICK_DELAY_MS
        )
    }

    private fun relocateAndClick(pending: PendingClick) {
        if (pendingClick !== pending) return
        val root = rootInActiveWindow
        if (cancelIfUnsafeDelayedClickWindow(pending, root)) return

        val elapsed = System.currentTimeMillis() - foregroundSince
        val currentRules = pending.activeRules.filter { it.packageName == pending.packageName }
        val scan = NodeScanner.scan(root ?: return, currentRules, elapsed)
        val match = scan.bestMatch
        if (match == null) {
            finishPendingClick(
                pending = pending.copy(retryCount = pending.retryCount + 1),
                stage = ClickLogStage.ClickFailed,
                success = false,
                reason = "candidate_lost_before_click",
                attempt = null,
                scan = scan
            )
            return
        }

        val updated = pending.copy(
            ruleId = match.ruleId,
            ruleName = match.ruleName,
            ruleSource = match.ruleSource,
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
        pendingClick = updated
        lastRuleClickAt[match.ruleId] = System.currentTimeMillis()
        lastClickSignature = pending.signature

        logEvent(
            stage = ClickLogStage.ClickAttempted,
            eventContext = updated.eventContext,
            pending = updated,
            clickMethod = ClickMethodLog.ActionClick,
            delayBeforeClickMs = updated.delayBeforeClickMs
        )

        val attempt = ClickExecutor.click(match.clickNode)
        if (attempt.accepted) {
            logEvent(
                stage = ClickLogStage.ClickActionSuccess,
                eventContext = updated.eventContext,
                pending = updated,
                clickMethod = attempt.method,
                actionReturnValue = true,
                clickResult = true,
                delayBeforeClickMs = updated.delayBeforeClickMs
            )
            mainHandler.postDelayed(
                { verifyActionClick(updated.copy(firstAttempt = attempt)) },
                CLICK_VERIFY_DELAY_MS
            )
        } else {
            runGestureFallback(updated.copy(firstAttempt = attempt))
        }
    }

    private fun verifyActionClick(pending: PendingClick) {
        if (pendingClick?.signature != pending.signature) return
        val result = verifyClickEffect(pending)
        if (!result.success && result.reason == "candidate_still_present") {
            runGestureFallback(pending)
            return
        }
        finishPendingClick(
            pending = pending,
            stage = result.stage,
            success = result.success,
            reason = result.reason,
            attempt = pending.firstAttempt,
            effectConfirmReason = result.reason
        )
    }

    private fun runGestureFallback(pending: PendingClick) {
        if (pendingClick?.signature != pending.signature) return
        if (cancelIfUnsafeDelayedClickWindow(pending, rootInActiveWindow)) return
        if (SafetyGuard.isProtectedPackage(pending.packageName) ||
            pending.textKeywordIsStandaloneSkip ||
            pending.isLargeCandidateBounds
        ) {
            finishPendingClick(
                pending = pending,
                stage = ClickLogStage.ClickEffectUnknown,
                success = false,
                reason = "gesture_fallback_blocked",
                attempt = pending.firstAttempt,
                effectConfirmReason = "gesture_fallback_blocked"
            )
            return
        }
        ClickExecutor.gestureClick(this, pending.candidate, allowLargeBounds = false) { gestureAttempt ->
            if (pendingClick?.signature != pending.signature) return@gestureClick
            if (!gestureAttempt.accepted) {
                val firstReason = pending.firstAttempt?.reason.orEmpty()
                finishPendingClick(
                    pending = pending,
                    stage = ClickLogStage.ClickFailed,
                    success = false,
                    reason = listOf(firstReason, gestureAttempt.reason)
                        .filter { it.isNotBlank() }
                        .joinToString(";")
                        .ifBlank { "click_failed" },
                    attempt = gestureAttempt
                )
                return@gestureClick
            }
            logEvent(
                stage = ClickLogStage.ClickActionSuccess,
                eventContext = pending.eventContext,
                pending = pending,
                clickMethod = gestureAttempt.method,
                actionReturnValue = true,
                clickResult = true,
                delayBeforeClickMs = pending.delayBeforeClickMs,
                clickTargetSource = ClickTargetSourceLog.GestureOnNodeCenter
            )
            mainHandler.postDelayed(
                {
                    if (pendingClick?.signature == pending.signature) {
                        val result = verifyClickEffect(pending)
                        finishPendingClick(
                            pending = pending,
                            stage = result.stage,
                            success = result.success,
                            reason = result.reason,
                            attempt = gestureAttempt,
                            effectConfirmReason = result.reason,
                            clickTargetSource = ClickTargetSourceLog.GestureOnNodeCenter
                        )
                    }
                },
                GESTURE_VERIFY_DELAY_MS
            )
        }
    }

    private fun verifyClickEffect(pending: PendingClick): ClickVerification {
        if (foregroundPackage == packageName) {
            return ClickVerification(
                stage = ClickLogStage.ClickMisfireSelfOpened,
                success = false,
                reason = "self_app_opened_after_click"
            )
        }
        if (!foregroundPackage.isNullOrBlank() && foregroundPackage != pending.packageName) {
            return ClickVerification(
                stage = ClickLogStage.ClickEffectConfirmed,
                success = true,
                reason = "package_changed_after_click"
            )
        }
        if (SafetyGuard.isProtectedPackage(pending.packageName)) {
            return ClickVerification(
                stage = ClickLogStage.ClickEffectUnknown,
                success = false,
                reason = "protected_package_never_confirmed"
            )
        }
        val root = rootInActiveWindow ?: return ClickVerification(
            stage = ClickLogStage.ClickEffectUnknown,
            success = false,
            reason = "root_window_null_after_click"
        )
        return if (!ClickExecutor.isTargetPresent(root, pending.candidate)) {
            ClickVerification(
                stage = ClickLogStage.ClickEffectConfirmed,
                success = true,
                reason = "candidate_node_disappeared"
            )
        } else {
            ClickVerification(
                stage = ClickLogStage.ClickEffectUnknown,
                success = false,
                reason = "candidate_still_present"
            )
        }
    }

    private fun finishPendingClick(
        pending: PendingClick,
        stage: ClickLogStage,
        success: Boolean,
        reason: String,
        attempt: ClickAttempt?,
        scan: ScanReport? = null,
        effectConfirmReason: String = "",
        clickTargetSource: ClickTargetSourceLog = pending.clickTargetSource,
        blockedReason: String = "",
        detail: String = ""
    ) {
        if (pendingClick?.signature != pending.signature) return
        pendingClick = null
        val now = System.currentTimeMillis()
        if (success) {
            SettingsRepository.markLastClick(this, now)
            showSuccessToast("已跳过：${pending.appName}", "success:${pending.packageName}")
        } else {
            SettingsRepository.setLastFailureReason(this, reason)
            showDebugToast("命中规则但点击失败，已记录日志", "fail:${pending.packageName}:${pending.ruleId}")
        }
        logEvent(
            stage = stage,
            eventContext = pending.eventContext,
            pending = pending,
            success = success,
            failureReason = if (success) "" else reason,
            reason = reason,
            detail = detail,
            clickMethod = attempt?.method ?: ClickMethodLog.None,
            actionReturnValue = attempt?.accepted,
            clickResult = success,
            effectConfirmed = if (stage == ClickLogStage.ClickEffectConfirmed) true else if (success) null else false,
            candidateCount = scan?.candidateCount,
            bestCandidateScore = scan?.bestCandidateScore,
            bestCandidateBounds = scan?.bestCandidateBounds.orEmpty(),
            minScore = scan?.bestCandidateMinScore ?: pending.minScore,
            delayBeforeClickMs = pending.delayBeforeClickMs,
            retryCount = pending.retryCount,
            effectConfirmReason = effectConfirmReason,
            clickTargetSource = clickTargetSource,
            blockedReason = blockedReason
        )
    }

    private fun cancelIfUnsafeDelayedClickWindow(
        pending: PendingClick,
        root: AccessibilityNodeInfo?
    ): Boolean {
        val currentPackageName = if (root == null) {
            ""
        } else {
            root.packageName?.toString().orEmpty().ifBlank { foregroundPackage.orEmpty() }
        }
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = pending.packageName,
            currentPackageName = currentPackageName,
            selfPackageName = packageName
        )
        if (result.allowed) return false
        finishPendingClick(
            pending = pending,
            stage = result.stage,
            success = false,
            reason = result.reason,
            attempt = pending.firstAttempt,
            effectConfirmReason = result.reason,
            blockedReason = result.blockedReason,
            detail = result.detail
        )
        return true
    }

    private fun logScan(scan: ScanReport, eventContext: EventContext) {
        if (scan.candidateCount > 0) {
            logEvent(
                stage = ClickLogStage.CandidateFound,
                eventContext = eventContext,
                candidateCount = scan.candidateCount,
                bestCandidateScore = scan.bestCandidateScore,
                bestCandidateBounds = scan.bestCandidateBounds,
                minScore = scan.bestCandidateMinScore,
                failureReason = scan.failureReason,
                defaultRuleAreaAllowed = scan.defaultRuleAreaAllowed,
                textKeywordIsStandaloneSkip = scan.textKeywordIsStandaloneSkip,
                candidateAreaRatio = scan.candidateAreaRatio,
                isLargeCandidateBounds = scan.isLargeCandidateBounds,
                clickTargetSource = scan.clickTargetSource
            )
        }
    }

    private fun logMatch(match: MatchResult, eventContext: EventContext, scan: ScanReport) {
        logEvent(
            stage = ClickLogStage.RuleMatched,
            eventContext = eventContext,
            pending = PendingClick.fromMatch(match, eventContext),
            candidateCount = scan.candidateCount,
            bestCandidateScore = scan.bestCandidateScore,
            bestCandidateBounds = scan.bestCandidateBounds,
            minScore = match.minScore
        )
    }

    private fun logEvent(
        stage: ClickLogStage,
        eventContext: EventContext,
        pending: PendingClick? = null,
        ruleType: String = pending?.ruleSource?.toLogType().orEmpty(),
        ruleName: String = pending?.ruleName.orEmpty(),
        ruleId: String = pending?.ruleId.orEmpty(),
        reason: String = "",
        failureReason: String = "",
        success: Boolean? = null,
        candidateCount: Int? = null,
        bestCandidateScore: Int? = null,
        bestCandidateBounds: String = "",
        minScore: Int? = null,
        clickMethod: ClickMethodLog = ClickMethodLog.None,
        actionReturnValue: Boolean? = null,
        clickResult: Boolean? = null,
        effectConfirmed: Boolean? = null,
        delayBeforeClickMs: Long? = null,
        retryCount: Int = 0,
        detail: String = "",
        blockedReason: String = "",
        defaultRuleAreaAllowed: Boolean? = pending?.defaultRuleAreaAllowed,
        textKeywordIsStandaloneSkip: Boolean = pending?.textKeywordIsStandaloneSkip == true,
        effectConfirmReason: String = "",
        clickSkippedBySafetyMode: Boolean = false,
        isSelfAppLabelCandidate: Boolean = pending?.let {
            SafetyGuard.isSelfAppLabelCandidate(this, it.candidate.text, it.candidate.contentDescription)
        } == true,
        candidateAreaRatio: Float? = pending?.candidateAreaRatio,
        isLargeCandidateBounds: Boolean = pending?.isLargeCandidateBounds == true,
        clickTargetSource: ClickTargetSourceLog = pending?.clickTargetSource ?: ClickTargetSourceLog.None
    ) {
        val safetyModeEnabled = SettingsRepository.isSafetyModeEnabled(this)
        val candidate = pending?.candidate
        val target = pending?.target
        val gestureX = if (clickMethod == ClickMethodLog.DispatchGesture) candidate?.bounds?.centerX() else null
        val gestureY = if (clickMethod == ClickMethodLog.DispatchGesture) candidate?.bounds?.centerY() else null
        LogRepository.addClickLog(
            context = this,
            log = ClickLog(
                timeMillis = System.currentTimeMillis(),
                packageName = eventContext.packageName,
                appName = pending?.appName ?: eventContext.packageName.takeIf { it.isNotBlank() }?.let {
                    InstalledAppUtils.getAppLabel(this, it)
                }.orEmpty(),
                activityName = eventContext.activityName,
                ruleType = ruleType,
                ruleName = ruleName,
                ruleId = ruleId,
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
                matchedKeyword = pending?.matchedKeyword.orEmpty(),
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
                retryCount = retryCount,
                deviceRom = RomUtils.detectRom().label,
                elapsedSinceAppStartMs = eventContext.elapsedSinceAppStartMs,
                defaultRuleWindowMs = RuleRepository.DEFAULT_RULE_WINDOW_MS,
                isSystemPackage = SafetyGuard.isSystemPackage(eventContext.packageName),
                isLauncherPackage = SafetyGuard.isLauncherPackage(eventContext.packageName),
                isSelfPackage = SafetyGuard.isSelfPackage(this, eventContext.packageName),
                isSelfAppLabelCandidate = isSelfAppLabelCandidate,
                blockedBySafety = stage == ClickLogStage.SkippedBySafety,
                blockedReason = blockedReason,
                defaultRuleAreaAllowed = defaultRuleAreaAllowed,
                textKeywordIsStandaloneSkip = textKeywordIsStandaloneSkip,
                effectConfirmReason = effectConfirmReason,
                safetyModeEnabled = safetyModeEnabled,
                clickSkippedBySafetyMode = clickSkippedBySafetyMode,
                candidateBounds = candidate?.boundsString().orEmpty(),
                candidateCenterX = candidate?.bounds?.centerX(),
                candidateCenterY = candidate?.bounds?.centerY(),
                clickedNodeBounds = target?.boundsString().orEmpty(),
                clickedNodeClassName = target?.className.orEmpty(),
                clickedNodeText = target?.text.orEmpty(),
                clickedNodeViewId = target?.viewId.orEmpty(),
                clickedParentDepth = pending?.clickedParentDepth,
                candidateAreaRatio = candidateAreaRatio,
                gestureX = gestureX,
                gestureY = gestureY,
                isLargeCandidateBounds = isLargeCandidateBounds,
                isFixedCoordinateClick = clickTargetSource == ClickTargetSourceLog.FixedPositionForbidden,
                clickTargetSource = clickTargetSource
            )
        )
    }

    private fun AccessibilityNodeInfo.containsOwnAppLabel(packageName: String): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(this)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (SafetyGuard.isOwnAppIconOnLauncher(
                    this@SkipAccessibilityService,
                    packageName,
                    node.text?.toString().orEmpty(),
                    node.contentDescription?.toString().orEmpty()
                )
            ) {
                return true
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }
        return false
    }

    private fun showDebugToast(message: String, key: String) {
        if (!SettingsRepository.isDebugToastEnabled(this)) return
        showToast(message, key)
    }

    private fun showSuccessToast(message: String, key: String) {
        if (!SettingsRepository.isSuccessToastEnabled(this)) return
        showToast(message, key)
    }

    private fun showToast(message: String, key: String) {
        val now = System.currentTimeMillis()
        val last = lastToastAt[key] ?: 0L
        if (now - last < TOAST_COOLDOWN_MS) return
        lastToastAt[key] = now
        mainHandler.post {
            showOverlayPopup(message)
        }
    }

    private fun showOverlayPopup(message: String) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        hidePopup()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xEE20242A.toInt())
            }
            elevation = dp(8).toFloat()
        }
        val icon = ImageView(this).apply {
            setImageResource(IconManager.currentScheme(this@SkipAccessibilityService).iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                marginEnd = dp(8)
            }
        }
        val text = TextView(this).apply {
            this.text = message
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
        }
        view.addView(icon)
        view.addView(text)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }

        runCatching {
            windowManager.addView(view, params)
            popupView = view
            mainHandler.postDelayed({ hidePopup() }, POPUP_DURATION_MS)
        }
    }

    private fun hidePopup() {
        val view = popupView ?: return
        popupView = null
        runCatching {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.removeViewImmediate(view)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun RuleSource.toLogType(): String {
        return when (this) {
            RuleSource.BuiltIn -> "default"
            RuleSource.UserSimple -> "custom"
            RuleSource.JsonFile -> "json"
            RuleSource.Subscription -> "json"
        }
    }

    companion object {
        private const val REPEAT_CLICK_GUARD_MS = 3000L
        private const val STABLE_CLICK_DELAY_MS = 260L
        private const val CLICK_VERIFY_DELAY_MS = 360L
        private const val GESTURE_VERIFY_DELAY_MS = 460L
        private const val TOAST_COOLDOWN_MS = 60_000L
        private const val POPUP_DURATION_MS = 1600L
    }
}

private data class EventContext(
    val eventType: Int,
    val eventPackageName: String,
    val packageName: String,
    val activityName: String,
    val windowId: Int,
    val rootWindowNull: Boolean,
    val rootChildCount: Int?,
    val canRetrieveWindowContent: Boolean,
    val elapsedSinceAppStartMs: Long
)

private data class PendingClick(
    val packageName: String,
    val appName: String,
    val ruleId: String,
    val ruleName: String,
    val ruleSource: RuleSource,
    val score: Int,
    val minScore: Int,
    val matchedKeyword: String,
    val area: String,
    val target: com.example.skip.engine.ClickTargetInfo,
    val candidate: com.example.skip.engine.ClickTargetInfo,
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
    val firstAttempt: ClickAttempt? = null
) {
    companion object {
        fun fromMatch(match: MatchResult, eventContext: EventContext): PendingClick {
            return PendingClick(
                packageName = eventContext.packageName,
                appName = match.appName,
                ruleId = match.ruleId,
                ruleName = match.ruleName,
                ruleSource = match.ruleSource,
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
                clickTargetSource = match.clickTargetSource,
                startedAt = System.currentTimeMillis(),
                eventContext = eventContext
            )
        }
    }
}

private data class ClickVerification(
    val stage: ClickLogStage,
    val success: Boolean,
    val reason: String
)

internal object DelayedClickSafetyCheck {
    fun evaluate(
        pendingPackageName: String,
        currentPackageName: String?,
        selfPackageName: String
    ): DelayedClickSafetyResult {
        val pending = pendingPackageName.trim()
        val current = currentPackageName.orEmpty().trim()
        val detail = "pendingPackageName=$pending;currentPackageName=$current"
        if (current.isBlank()) {
            return DelayedClickSafetyResult(
                allowed = false,
                stage = ClickLogStage.ClickCancelledPackageUnknown,
                reason = "current_package_unknown_before_click",
                detail = detail
            )
        }
        if (current == selfPackageName) {
            return DelayedClickSafetyResult(
                allowed = false,
                stage = ClickLogStage.ClickCancelledSelfPackage,
                reason = "click_cancelled_self_package",
                detail = detail
            )
        }
        if (SafetyGuard.isProtectedPackage(current)) {
            return DelayedClickSafetyResult(
                allowed = false,
                stage = ClickLogStage.SkippedBySafety,
                reason = "skipped_by_safety",
                blockedReason = "safety_guard_before_delayed_click",
                detail = detail
            )
        }
        if (current != pending) {
            return DelayedClickSafetyResult(
                allowed = false,
                stage = ClickLogStage.ClickCancelledPackageChanged,
                reason = "package_changed_before_click",
                detail = detail
            )
        }
        return DelayedClickSafetyResult(
            allowed = true,
            stage = ClickLogStage.ClickEffectUnknown,
            reason = "",
            detail = detail
        )
    }
}

internal data class DelayedClickSafetyResult(
    val allowed: Boolean,
    val stage: ClickLogStage,
    val reason: String,
    val blockedReason: String = "",
    val detail: String = ""
)
