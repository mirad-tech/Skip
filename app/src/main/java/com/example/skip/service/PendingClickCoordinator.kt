package com.example.skip.service

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.data.SettingsRepository
import com.example.skip.engine.ClickAttempt
import com.example.skip.engine.ClickExecutor
import com.example.skip.engine.CoordinateFallbackMatch
import com.example.skip.engine.CoordinateFallbackMatcher
import com.example.skip.engine.CurrentTargetRevalidator
import com.example.skip.engine.NodeScanner
import com.example.skip.engine.SafetyGuard
import com.example.skip.engine.ScoreEvaluator
import com.example.skip.engine.StandaloneSkipGestureRevalidationPolicy
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.MatchResult
import com.example.skip.model.ScanReport
import com.example.skip.model.SkipRule
import com.example.skip.util.AccessibilityNodeAccess

internal class PendingClickCoordinator(
    private val service: SkipAccessibilityService
) {
    private val callbackToken = Any()
    var pendingClick: PendingClick? = null
        private set

    fun clear() {
        pendingClick = null
        service.mainHandler.removeCallbacksAndMessages(callbackToken)
    }

    fun clearIfSignature(signature: String) {
        if (pendingClick?.signature == signature) clear()
    }

    private fun postAccessibilityTask(action: () -> Unit, delayMs: Long) {
        service.mainHandler.postDelayed(
            { AccessibilityNodeAccess.withCacheBoundary(service, action) },
            callbackToken,
            delayMs
        )
    }

    fun startStableClick(
        packageName: String,
        match: MatchResult,
        activeRules: List<SkipRule>,
        signature: String,
        eventContext: EventContext,
        retryCount: Int = 0,
        scanDurationMs: Long? = null,
        rescanReason: String = ""
    ) {
        if (!service.isSessionOpen(eventContext.openingAdSessionKey, packageName, eventContext.defaultRuleWindowMs)) return
        val delayMs = StableClickDelayPolicy.delayMs(
            ruleSource = match.ruleSource,
            score = match.score,
            minScore = match.minScore,
            candidateViewId = match.candidate.viewId,
            candidateText = match.candidate.text,
            candidateContentDescription = match.candidate.contentDescription,
            isLargeCandidateBounds = match.isLargeCandidateBounds,
            textKeywordIsStandaloneSkip = match.textKeywordIsStandaloneSkip,
            clickTargetSource = match.clickTargetSource,
            defaultDelayMs = SkipAccessibilityService.STABLE_CLICK_DELAY_MS
        )
        val pending = ClickFlowStateMachine.startFromMatch(
            packageName = packageName,
            appName = service.displayAppName(packageName, match.appName),
            match = ClickMatchSnapshot.from(match),
            activeRules = activeRules,
            signature = signature,
            eventContext = eventContext,
            delayBeforeClickMs = delayMs,
            retryCount = retryCount,
            scanDurationMs = scanDurationMs,
            rescanReason = rescanReason
        )
        service.cancelScheduledOpeningAdRetry()
        pendingClick = pending
        postAccessibilityTask({ relocateAndClick(pending) }, delayMs)
    }

    fun startStableCoordinateFallback(
        packageName: String,
        fallback: CoordinateFallbackMatch,
        activeRules: List<SkipRule>,
        signature: String,
        eventContext: EventContext,
        scan: ScanReport,
        retryCount: Int = 0,
        scanDurationMs: Long? = null,
        rescanReason: String = ""
    ) {
        if (!service.isSessionOpen(eventContext.openingAdSessionKey, packageName, eventContext.defaultRuleWindowMs)) return
        val rule = fallback.rule
        val pending = ClickFlowStateMachine.startCoordinateFallback(
            packageName = packageName,
            appName = service.displayAppName(packageName, rule.appName),
            fallback = fallback,
            activeRules = activeRules,
            signature = signature,
            delayBeforeClickMs = SkipAccessibilityService.STABLE_CLICK_DELAY_MS,
            eventContext = eventContext,
            retryCount = retryCount,
            scanDurationMs = scanDurationMs,
            rescanReason = rescanReason
        )

        val highRiskDecision = service.highRiskDecisionForPending(pending)
        if (!highRiskDecision.allowed) {
            service.logBlockedByHighRiskPolicy(
                eventContext = eventContext,
                pending = pending,
                decision = highRiskDecision,
                scan = scan,
                clickTargetSource = ClickTargetSourceLog.CoordinateFallback
            )
            service.terminateOpeningAdRecovery(service.pendingSessionKey(pending))
            return
        }

        service.eventLogger.logEvent(
            stage = ClickLogStage.RuleMatched,
            eventContext = eventContext,
            pending = pending,
            candidateCount = scan.candidateCount,
            bestCandidateScore = scan.bestCandidateScore,
            bestCandidateBounds = scan.bestCandidateBounds,
            minScore = rule.minScore,
            reason = fallback.reason,
            clickTargetSource = ClickTargetSourceLog.CoordinateFallback
        )

        if (SettingsRepository.isSafetyModeEnabled(service)) {
            service.eventLogger.logEvent(
                stage = ClickLogStage.ClickSkippedBySafetyMode,
                eventContext = eventContext,
                pending = pending,
                candidateCount = scan.candidateCount,
                bestCandidateScore = scan.bestCandidateScore,
                bestCandidateBounds = scan.bestCandidateBounds,
                minScore = rule.minScore,
                failureReason = "click_skipped_by_safety_mode",
                reason = "click_skipped_by_safety_mode",
                clickSkippedBySafetyMode = true,
                clickTargetSource = ClickTargetSourceLog.CoordinateFallback
            )
            SettingsRepository.setLastFailureReason(
                service,
                "click_skipped_by_safety_mode",
                forcePersist = true
            )
            service.terminateOpeningAdRecovery(service.pendingSessionKey(pending))
            return
        }

        service.cancelScheduledOpeningAdRetry()
        pendingClick = pending
        postAccessibilityTask(
            { runCoordinateFallback(pending) },
            SkipAccessibilityService.STABLE_CLICK_DELAY_MS
        )
    }

    private fun relocateAndClick(pending: PendingClick) {
        if (pendingClick !== pending) return
        if (!canDispatchPending(pending)) return
        val callbackPending = ClickFlowStateMachine.recordCallbackTiming(pending)
        pendingClick = callbackPending
        val root = AccessibilityNodeAccess.freshActiveRoot(service)
        if (root == null && retryPendingClick(
                pending = callbackPending,
                reason = "root_window_null",
                attempt = null
            )
        ) {
            return
        }
        if (cancelIfUnsafeDelayedClickWindow(callbackPending, root)) return

        val currentRule = callbackPending.activeRules.firstOrNull { rule ->
            rule.id == callbackPending.ruleId && rule.packageName == callbackPending.packageName
        }
        val activityDecision = PendingActivityScopePolicy.evaluate(
            rule = currentRule,
            currentActivityName = service.currentActivityName,
            activityIdentityKnown = service.currentActivityIdentityKnown
        )
        if (!activityDecision.allowed) {
            finishPendingClick(
                pending = callbackPending,
                stage = ClickLogStage.SkippedBySafety,
                success = false,
                reason = activityDecision.reason,
                attempt = null,
                blockedReason = activityDecision.reason
            )
            return
        }
        val rule = currentRule ?: run {
            finishPendingClick(
                pending = callbackPending,
                stage = ClickLogStage.SkippedBySafety,
                success = false,
                reason = "pending_rule_missing",
                attempt = null,
                blockedReason = "pending_rule_missing"
            )
            return
        }
        val elapsed = service.foregroundStateSnapshot.elapsedSinceStart(SystemClock.elapsedRealtime()) ?: return
        val currentRules = NodeScanner.filterRulesForActivity(
            rules = listOf(rule),
            currentActivityName = service.currentActivityName
        )
        val scanStartedAt = SystemClock.elapsedRealtime()
        // Defence in depth. Today the `retryPendingClick` call above and
        // `cancelIfUnsafeDelayedClickWindow` already cover a null root, so this branch is not
        // reachable; it exists so that a future reordering of those guards cannot park the service
        // on the pending fast path with no callback left to clear it.
        val activeRoot = root
        if (activeRoot == null) {
            finishPendingClick(
                pending = callbackPending,
                stage = ClickLogStage.RootWindowNull,
                success = false,
                reason = "root_window_null_before_click",
                attempt = null
            )
            return
        }
        val scan = NodeScanner.scan(activeRoot, currentRules, elapsed, service.currentActivityName)
        val scanDurationMs = (SystemClock.elapsedRealtime() - scanStartedAt).coerceAtLeast(0L)
        val match = scan.bestMatch
        if (match == null) {
            val retryPending = callbackPending.copy(scanDurationMs = scanDurationMs)
            if (!retryPendingClick(
                    pending = retryPending,
                    reason = "candidate_lost_before_click",
                    attempt = null,
                    scan = scan
                )
            ) {
                finishPendingClick(
                    pending = retryPending,
                    stage = ClickLogStage.NoCandidateFound,
                    success = false,
                    reason = "candidate_lost_before_click",
                    attempt = null,
                    scan = scan
                )
            }
            return
        }

        val matchSnapshot = ClickMatchSnapshot.from(match)
        val relocationDecision = PendingClickRelocationPolicy.evaluate(callbackPending, matchSnapshot)
        if (!relocationDecision.allowed) {
            val retryPending = callbackPending.copy(scanDurationMs = scanDurationMs)
            if (!retryPendingClick(
                    pending = retryPending,
                    reason = relocationDecision.reason,
                    attempt = null,
                    scan = scan
                )
            ) {
                finishPendingClick(
                    pending = retryPending,
                    stage = ClickLogStage.NoCandidateFound,
                    success = false,
                    reason = relocationDecision.reason,
                    attempt = null,
                    scan = scan
                )
            }
            return
        }

        val updated = ClickFlowStateMachine.relocateToMatch(callbackPending, matchSnapshot)
            .copy(scanDurationMs = scanDurationMs)
        pendingClick = updated
        val highRiskDecision = service.highRiskDecisionForPending(updated)
        if (!highRiskDecision.allowed) {
            service.logBlockedByHighRiskPolicy(
                eventContext = updated.eventContext,
                pending = updated,
                decision = highRiskDecision,
                scan = scan,
                clearPending = true
            )
            return
        }
        service.lastRuleClickAt[match.ruleId] = System.currentTimeMillis()
        service.lastClickSignature = updated.signature

        service.eventLogger.logEvent(
            stage = ClickLogStage.ClickAttempted,
            eventContext = updated.eventContext,
            pending = updated,
            clickMethod = ClickMethodLog.ActionClick,
            delayBeforeClickMs = updated.delayBeforeClickMs
        )

        val attempt = ClickExecutor.click(match.clickNode) { canDispatchPending(updated) }
        if (!isCurrentPending(updated)) return
        if (attempt.accepted) {
            val dispatched = updated.copy(firstAttempt = attempt, clickDispatched = true)
            pendingClick = dispatched
            service.eventLogger.logEvent(
                stage = ClickLogStage.ClickActionSuccess,
                eventContext = dispatched.eventContext,
                pending = dispatched,
                clickMethod = attempt.method,
                actionReturnValue = true,
                clickResult = true,
                delayBeforeClickMs = dispatched.delayBeforeClickMs
            )
            postAccessibilityTask(
                { verifyActionClick(dispatched) },
                CLICK_VERIFY_DELAY_MS
            )
        } else {
            runGestureFallback(updated.copy(firstAttempt = attempt))
        }
    }

    private fun runCoordinateFallback(pending: PendingClick) {
        if (pendingClick !== pending) return
        if (!canDispatchPending(pending)) return
        val callbackPending = ClickFlowStateMachine.recordCallbackTiming(pending)
        pendingClick = callbackPending
        val root = AccessibilityNodeAccess.freshActiveRoot(service)
        val x = callbackPending.coordinateX ?: callbackPending.candidate.bounds.centerX()
        val y = callbackPending.coordinateY ?: callbackPending.candidate.bounds.centerY()
        val rule = callbackPending.activeRules.firstOrNull { candidate ->
            candidate.id == callbackPending.ruleId && candidate.coordinateFallback?.enabled == true
        }
        if (rule == null) {
            finishPendingClick(
                pending = callbackPending,
                stage = ClickLogStage.SkippedBySafety,
                success = false,
                reason = "coordinate_rule_missing",
                attempt = null,
                blockedReason = "coordinate_rule_missing",
                clickTargetSource = ClickTargetSourceLog.CoordinateFallback
            )
            return
        }
        val currentPackageName = root?.packageName?.toString().orEmpty().ifBlank {
            service.foregroundPackage.orEmpty()
        }
        val elapsedSinceForegroundMs = service.foregroundStateSnapshot.elapsedSinceStart(SystemClock.elapsedRealtime()) ?: return
        val revalidation = CoordinateFallbackMatcher.revalidateAtPoint(
            root = root,
            rule = rule,
            expectedPackageName = callbackPending.packageName,
            currentPackageName = currentPackageName,
            selfPackageName = service.packageName,
            elapsedSinceForegroundMs = elapsedSinceForegroundMs,
            x = x,
            y = y,
            originalTarget = callbackPending.candidate,
            activeTextInput = ActiveTextInputGuard.hasFocusedEditableInput(root)
        )
        if (!revalidation.allowed || revalidation.reason != "coordinate_target_revalidated") {
            if (!retryPendingClick(
                    pending = callbackPending,
                    reason = revalidation.reason,
                    attempt = null,
                    clickTargetSource = ClickTargetSourceLog.CoordinateFallback,
                    detail = "coordinate_revalidation=${revalidation.reason}"
                )
            ) {
                finishPendingClick(
                    pending = callbackPending,
                    stage = ClickLogStage.SkippedBySafety,
                    success = false,
                    reason = revalidation.reason,
                    attempt = null,
                    blockedReason = revalidation.reason,
                    detail = "coordinate_revalidation=${revalidation.reason}",
                    clickTargetSource = ClickTargetSourceLog.CoordinateFallback
                )
            }
            return
        }
        if (cancelIfUnsafeDelayedClickWindow(callbackPending, root)) return
        val target = revalidation.target ?: run {
            if (!retryPendingClick(
                    pending = callbackPending,
                    reason = "coordinate_target_missing",
                    attempt = null,
                    clickTargetSource = ClickTargetSourceLog.CoordinateFallback
                )
            ) {
                finishPendingClick(
                    pending = callbackPending,
                    stage = ClickLogStage.NoCandidateFound,
                    success = false,
                    reason = "coordinate_target_missing",
                    attempt = null,
                    clickTargetSource = ClickTargetSourceLog.CoordinateFallback
                )
            }
            return
        }
        val updatedPending = callbackPending.copy(target = target, candidate = target)
        pendingClick = updatedPending
        service.lastRuleClickAt[updatedPending.ruleId] = System.currentTimeMillis()
        service.lastClickSignature = updatedPending.signature

        service.eventLogger.logEvent(
            stage = ClickLogStage.ClickAttempted,
            eventContext = updatedPending.eventContext,
            pending = updatedPending,
            clickMethod = ClickMethodLog.DispatchGesture,
            delayBeforeClickMs = updatedPending.delayBeforeClickMs,
            clickTargetSource = ClickTargetSourceLog.CoordinateFallback
        )

        val gestureQueued = ClickExecutor.gestureClickPoint(
            service, target, x, y, canDispatch = { canDispatchPending(updatedPending) }
        ) { attempt ->
            if (!isCurrentPending(updatedPending)) return@gestureClickPoint
            if (!attempt.accepted) {
                val failedPending = updatedPending.copy(firstAttempt = attempt)
                if (!retryPendingClick(
                        pending = failedPending,
                        reason = attempt.reason,
                        attempt = attempt,
                        clickTargetSource = ClickTargetSourceLog.CoordinateFallback
                    )
                ) {
                    finishPendingClick(
                        pending = failedPending,
                        stage = ClickLogStage.ClickFailed,
                        success = false,
                        reason = attempt.reason,
                        attempt = attempt,
                        clickTargetSource = ClickTargetSourceLog.CoordinateFallback
                    )
                }
                return@gestureClickPoint
            }
            val updated = updatedPending.copy(firstAttempt = attempt, clickDispatched = true)
            pendingClick = updated
            service.eventLogger.logEvent(
                stage = ClickLogStage.ClickActionSuccess,
                eventContext = updated.eventContext,
                pending = updated,
                clickMethod = attempt.method,
                actionReturnValue = true,
                clickResult = true,
                delayBeforeClickMs = updated.delayBeforeClickMs,
                clickTargetSource = ClickTargetSourceLog.CoordinateFallback
            )
            postAccessibilityTask(
                {
                    if (isCurrentPending(updated)) {
                        val result = verifyClickEffect(updated)
                        finishPendingClick(
                            pending = updated,
                            stage = result.stage,
                            success = result.success,
                            reason = result.reason,
                            attempt = attempt,
                            effectConfirmReason = result.reason,
                            clickTargetSource = ClickTargetSourceLog.CoordinateFallback
                        )
                    }
                },
                GESTURE_VERIFY_DELAY_MS
            )
        }
        if (gestureQueued && isCurrentPending(updatedPending)) {
            pendingClick = updatedPending.copy(clickDispatched = true)
        }
    }

    private fun verifyActionClick(pending: PendingClick) {
        if (!isCurrentPending(pending)) return
        val result = verifyClickEffect(pending)
        // A gesture retry is only justified when the framework click was not accepted. When
        // ACTION_CLICK returned true the view may simply not be torn down yet, and re-dispatching
        // a gesture at the same coordinates would click twice.
        val actionAccepted = pending.firstAttempt?.accepted == true
        if (result.shouldTryGestureFallback && !actionAccepted) {
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
        if (!canDispatchPending(pending)) return
        val root = AccessibilityNodeAccess.freshActiveRoot(service)
        if (root == null && retryPendingClick(
                pending = pending,
                reason = "current_target_root_missing",
                attempt = pending.firstAttempt
            )
        ) {
            return
        }
        if (cancelIfUnsafeDelayedClickWindow(pending, root)) return
        val currentRule = pending.activeRules.firstOrNull { rule ->
            rule.id == pending.ruleId && rule.packageName == pending.packageName
        }
        val activityDecision = PendingActivityScopePolicy.evaluate(
            rule = currentRule,
            currentActivityName = service.currentActivityName,
            activityIdentityKnown = service.currentActivityIdentityKnown
        )
        if (!activityDecision.allowed) {
            finishPendingClick(
                pending = pending,
                stage = ClickLogStage.SkippedBySafety,
                success = false,
                reason = activityDecision.reason,
                attempt = pending.firstAttempt,
                effectConfirmReason = activityDecision.reason,
                blockedReason = activityDecision.reason
            )
            return
        }
        if (SafetyGuard.isProtectedPackage(pending.packageName) ||
            (pending.textKeywordIsStandaloneSkip && !pending.standaloneSkipAllowed) ||
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
        val currentPackageName = root?.packageName?.toString().orEmpty().ifBlank {
            service.foregroundPackage.orEmpty()
        }
        val expectedTarget = ClickExecutor.targetWithActionIdentity(
            candidate = pending.candidate,
            actionTarget = pending.target
        )
        val revalidation = CurrentTargetRevalidator.revalidateAtPoint(
            root = root,
            expectedPackageName = pending.packageName,
            currentPackageName = currentPackageName,
            x = pending.candidate.bounds.centerX(),
            y = pending.candidate.bounds.centerY(),
            originalTarget = expectedTarget,
            activeTextInput = ActiveTextInputGuard.hasFocusedEditableInput(root)
        )
        val revalidatedSnapshot = revalidation.snapshot
        if (!revalidation.allowed || revalidatedSnapshot == null) {
            if (!retryPendingClick(
                    pending = pending,
                    reason = revalidation.reason,
                    attempt = pending.firstAttempt,
                    detail = "gesture_revalidation=${revalidation.reason}"
                )
            ) {
                finishPendingClick(
                    pending = pending,
                    stage = ClickLogStage.SkippedBySafety,
                    success = false,
                    reason = revalidation.reason,
                    attempt = pending.firstAttempt,
                    effectConfirmReason = revalidation.reason,
                    blockedReason = revalidation.reason,
                    detail = "gesture_revalidation=${revalidation.reason}"
                )
            }
            return
        }
        val revalidatedCandidate = revalidatedSnapshot.target
        val revalidatedCandidateAreaRatio = ClickExecutor.areaRatio(revalidatedCandidate.bounds)
        if (pending.standaloneSkipAllowed) {
            val currentAppElapsedMs = service.foregroundStateSnapshot.elapsedSinceStart(SystemClock.elapsedRealtime()) ?: return
            val standaloneDecision = StandaloneSkipGestureRevalidationPolicy.evaluate(
                ruleSource = pending.ruleSource,
                ruleKind = pending.ruleKind,
                appElapsedMs = currentAppElapsedMs,
                area = ScoreEvaluator.areaInScreen(revalidatedCandidate.bounds),
                candidateAreaRatio = revalidatedCandidateAreaRatio,
                snapshot = revalidatedSnapshot
            )
            if (!standaloneDecision.allowed) {
                finishPendingClick(
                    pending = pending,
                    stage = ClickLogStage.SkippedBySafety,
                    success = false,
                    reason = standaloneDecision.reason,
                    attempt = pending.firstAttempt,
                    effectConfirmReason = standaloneDecision.reason,
                    blockedReason = standaloneDecision.reason,
                    detail = "standalone_skip_gesture_revalidation=${standaloneDecision.reason}"
                )
                return
            }
        }
        val updated = pending.copy(
            target = revalidatedCandidate,
            candidate = revalidatedCandidate,
            candidateAreaRatio = revalidatedCandidateAreaRatio,
            isLargeCandidateBounds = ClickExecutor.isLargeDefaultCandidate(revalidatedCandidate.bounds)
        )
        pendingClick = updated
        val gestureQueued = ClickExecutor.gestureClick(
            service,
            updated.candidate,
            allowLargeBounds = false,
            canDispatch = { canDispatchPending(updated) }
        ) { gestureAttempt ->
            if (!isCurrentPending(updated)) return@gestureClick
            if (!gestureAttempt.accepted) {
                val firstReason = updated.firstAttempt?.reason.orEmpty()
                val combinedReason = listOf(firstReason, gestureAttempt.reason)
                    .filter { it.isNotBlank() }
                    .joinToString(";")
                    .ifBlank { "click_failed" }
                if (!retryPendingClick(
                        pending = updated,
                        reason = gestureAttempt.reason,
                        attempt = gestureAttempt,
                        detail = combinedReason
                    )
                ) {
                    finishPendingClick(
                        pending = updated,
                        stage = ClickLogStage.ClickFailed,
                        success = false,
                        reason = combinedReason,
                        attempt = gestureAttempt
                    )
                }
                return@gestureClick
            }
            val dispatched = updated.copy(firstAttempt = gestureAttempt, clickDispatched = true)
            pendingClick = dispatched
            service.eventLogger.logEvent(
                stage = ClickLogStage.ClickActionSuccess,
                eventContext = dispatched.eventContext,
                pending = dispatched,
                clickMethod = gestureAttempt.method,
                actionReturnValue = true,
                clickResult = true,
                delayBeforeClickMs = dispatched.delayBeforeClickMs,
                clickTargetSource = ClickTargetSourceLog.GestureOnNodeCenter
            )
            postAccessibilityTask(
                {
                    if (isCurrentPending(dispatched)) {
                        val result = verifyClickEffect(dispatched)
                        finishPendingClick(
                            pending = dispatched,
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
        if (gestureQueued && isCurrentPending(updated)) {
            pendingClick = updated.copy(clickDispatched = true)
        }
    }

    private fun verifyClickEffect(pending: PendingClick): ClickVerification {
        val root = AccessibilityNodeAccess.freshActiveRoot(service)
        return ClickEffectVerifier.evaluate(
            pendingPackageName = pending.packageName,
            selfPackageName = service.packageName,
            rootPackageName = root?.packageName?.toString().orEmpty(),
            foregroundPackageName = service.foregroundPackage.orEmpty(),
            rootWindowNull = root == null,
            targetStillPresent = ClickExecutor.isTargetPresent(root, pending.candidate)
        )
    }

    fun handlePendingEventFastPath(
        pending: PendingClick,
        eventContext: EventContext
    ) {
        if (!isCurrentPending(pending)) return
        if (!pending.clickDispatched && !canDispatchPending(pending)) return
        val currentPackage = eventContext.packageName.trim()
        when (PendingEventFastPathPolicy.evaluate(
            clickDispatched = pending.clickDispatched,
            currentPackageKnown = currentPackage.isNotBlank(),
            samePackage = currentPackage == pending.packageName,
            isWithinRuleWindow = eventContext.isWithinDefaultRuleWindow,
            activityChanged = service.hasActivityChanged(pending.eventContext.activityName)
        )) {
            PendingEventFastPathDecision.AwaitClickVerification,
            PendingEventFastPathDecision.WaitForKnownPackage -> return
            PendingEventFastPathDecision.CancelPackageChanged -> {
                finishPendingClick(
                    pending = pending,
                    stage = ClickLogStage.ClickCancelledPackageChanged,
                    success = false,
                    reason = "package_changed_before_click",
                    attempt = pending.firstAttempt,
                    effectConfirmReason = "package_changed_before_click",
                    eventContext = eventContext
                )
                return
            }
            PendingEventFastPathDecision.CancelTimeWindowExpired -> {
                finishPendingClick(
                    pending = pending,
                    stage = ClickLogStage.ClickCancelledTimeWindowExpired,
                    success = false,
                    reason = "click_cancelled_time_window_expired",
                    attempt = pending.firstAttempt,
                    effectConfirmReason = "click_cancelled_time_window_expired",
                    eventContext = eventContext
                )
                return
            }
            PendingEventFastPathDecision.CancelActivityChanged -> {
                finishPendingClick(
                    pending = pending,
                    stage = ClickLogStage.SkippedBySafety,
                    success = false,
                    reason = "activity_changed_before_click",
                    attempt = pending.firstAttempt,
                    effectConfirmReason = "activity_changed_before_click",
                    blockedReason = "activity_changed_before_click",
                    eventContext = eventContext
                )
                return
            }
            PendingEventFastPathDecision.Continue -> Unit
        }
        cancelPendingClickForActivityScope(pending, eventContext)
    }

    /**
     * Single guard for every delayed click path (semantic relocation, coordinate fallback and
     * gesture fallback). Master-off and safety-mode-on both mean "do not click", and both can
     * change while a click is already scheduled, so neither may only be checked before the delay.
     *
     * The two causes must stay distinguishable in the log: `ClickSkippedBySafetyMode` counts as a
     * safety block in `LogRepository.isSafetyBlockedHit`, so a master-off abort recorded under that
     * stage would inflate the "安全阻止" statistic while the log also says
     * `safetyModeEnabled = false`.
     */
    private fun abortPendingIfAutomationBlocked(pending: PendingClick): Boolean {
        if (!isCurrentPending(pending)) return true
        val masterEnabled = SettingsRepository.isMasterEnabled(service)
        val safetyModeEnabled = SettingsRepository.isSafetyModeEnabled(service)
        if (masterEnabled && !safetyModeEnabled) return false
        clear()
        service.terminateOpeningAdRecovery(service.pendingSessionKey(pending))
        val skippedBySafetyMode = masterEnabled && safetyModeEnabled
        val reason = if (skippedBySafetyMode) {
            "click_skipped_by_safety_mode"
        } else {
            "automation_paused"
        }
        SettingsRepository.setLastFailureReason(service, reason, forcePersist = true)
        service.eventLogger.logEvent(
            stage = if (skippedBySafetyMode) {
                ClickLogStage.ClickSkippedBySafetyMode
            } else {
                ClickLogStage.SkippedByDisabledSetting
            },
            eventContext = pending.eventContext,
            pending = pending,
            success = false,
            failureReason = reason,
            reason = reason,
            clickSkippedBySafetyMode = skippedBySafetyMode
        )
        return true
    }

    private fun retryPendingClick(
        pending: PendingClick,
        reason: String,
        attempt: ClickAttempt?,
        scan: ScanReport? = null,
        clickTargetSource: ClickTargetSourceLog = pending.clickTargetSource,
        detail: String = ""
    ): Boolean {
        if (!canDispatchPending(pending)) return false
        val root = AccessibilityNodeAccess.freshActiveRoot(service)
        val rootPackageName = root?.packageName?.toString().orEmpty()
        val samePackage = service.foregroundPackage == pending.packageName &&
            (rootPackageName.isBlank() || rootPackageName == pending.packageName)
        val sameActivity = !service.hasActivityChanged(pending.eventContext.activityName)
        val activeTextInput = root != null && ActiveTextInputGuard.hasFocusedEditableInput(root)
        val now = System.currentTimeMillis()
        val retryEventContext = service.buildEventContext(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            eventPackageName = pending.packageName,
            packageName = pending.packageName,
            activityName = service.currentActivityName.ifBlank { pending.eventContext.activityName },
            windowId = root?.windowId ?: pending.eventContext.windowId,
            root = root,
            now = now,
            defaultRuleWindowMs = pending.eventContext.defaultRuleWindowMs
        )
        if (!OpeningAdRetryPolicy.shouldRetry(
                reason = reason,
                retriesPerformed = pending.retryCount,
                isWithinRuleWindow = retryEventContext.isWithinDefaultRuleWindow,
                samePackage = samePackage,
                sameActivity = sameActivity,
                activeTextInput = activeTextInput
            )
        ) {
            return false
        }

        pendingClick = null
        val nextRetryCount = service.scheduleOpeningAdRetry(
            packageName = pending.packageName,
            activeRules = pending.activeRules,
            baseEventContext = retryEventContext,
            reason = reason,
            retriesPerformed = pending.retryCount
        )
        if (nextRetryCount == null) {
            pendingClick = pending
            return false
        }

        service.lastClickSignature = null
        service.lastRuleClickAt.remove(pending.ruleId)
        SettingsRepository.setLastFailureReason(service, reason)
        service.eventLogger.logEvent(
            stage = retryStageForReason(reason),
            eventContext = retryEventContext,
            pending = pending,
            success = false,
            failureReason = reason,
            reason = reason,
            detail = detail,
            clickMethod = attempt?.method ?: ClickMethodLog.None,
            actionReturnValue = attempt?.accepted,
            clickResult = false,
            candidateCount = scan?.candidateCount,
            bestCandidateScore = scan?.bestCandidateScore,
            bestCandidateBounds = scan?.bestCandidateBounds.orEmpty(),
            minScore = scan?.bestCandidateMinScore ?: pending.minScore,
            retryCount = pending.retryCount,
            rescanReason = reason,
            clickTargetSource = clickTargetSource
        )
        return true
    }

    private fun retryStageForReason(reason: String): ClickLogStage {
        return when (reason) {
            "root_window_null", "root_window_null_before_click", "current_target_root_missing",
            "coordinate_root_missing" -> ClickLogStage.RootWindowNull
            "score_below_min_score", "candidate_below_threshold" -> ClickLogStage.SkippedByLowScore
            "gesture_cancelled", "gesture_dispatch_returned_false",
            "coordinate_fallback_cancelled", "coordinate_fallback_dispatch_returned_false" -> {
                ClickLogStage.ClickFailed
            }
            else -> ClickLogStage.NoCandidateFound
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
        detail: String = "",
        eventContext: EventContext = pending.eventContext
    ) {
        if (!isCurrentPending(pending)) return
        clear()
        service.terminateOpeningAdRecovery(service.pendingSessionKey(pending))
        val now = System.currentTimeMillis()
        if (success) {
            service.finishOpeningAdSession(service.pendingSessionKey(pending))
            SettingsRepository.flushRuntimeDiagnostics(service, now)
            SettingsRepository.markLastClick(service, now)
            service.feedbackController.showSuccessToast("已跳过：${pending.appName}", "success:${pending.packageName}")
        } else {
            SettingsRepository.setLastFailureReason(service, reason, forcePersist = true)
            service.feedbackController.showDebugToast(
                "命中规则但点击失败，已记录日志",
                "fail:${pending.packageName}:${pending.ruleId}"
            )
        }
        service.eventLogger.logEvent(
            stage = stage,
            eventContext = eventContext,
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
        if (!canDispatchPending(pending)) return true
        val currentPackageName = root?.packageName?.toString().orEmpty().ifBlank {
            service.foregroundPackage.orEmpty()
        }
        val delayedEventContext = service.buildEventContext(
            eventType = pending.eventContext.eventType,
            eventPackageName = pending.eventContext.eventPackageName,
            packageName = currentPackageName,
            activityName = service.currentActivityName,
            windowId = root?.windowId ?: pending.eventContext.windowId,
            root = root,
            now = System.currentTimeMillis(),
            defaultRuleWindowMs = pending.eventContext.defaultRuleWindowMs
        )
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = pending.packageName,
            currentPackageName = currentPackageName,
            selfPackageName = service.packageName,
            foregroundPackageName = delayedEventContext.foregroundPackage,
            elapsedSinceForegroundMs = delayedEventContext.elapsedSinceForegroundMs,
            pendingSessionKey = pending.eventContext.openingAdSessionKey,
            currentSessionKey = delayedEventContext.openingAdSessionKey,
            isSessionOpen = delayedEventContext.isWithinDefaultRuleWindow,
            defaultRuleWindowMs = delayedEventContext.defaultRuleWindowMs,
            rootWindowNull = root == null,
            activeTextInput = ActiveTextInputGuard.hasFocusedEditableInput(root)
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
            detail = result.detail,
            eventContext = delayedEventContext
        )
        return true
    }

    private fun cancelPendingClickForActivityScope(
        pending: PendingClick,
        eventContext: EventContext
    ) {
        val currentRule = pending.activeRules.firstOrNull { rule ->
            rule.id == pending.ruleId && rule.packageName == pending.packageName
        }
        val decision = PendingActivityScopePolicy.evaluate(
            rule = currentRule,
            currentActivityName = service.currentActivityName,
            activityIdentityKnown = service.currentActivityIdentityKnown
        )
        if (decision.allowed) return
        finishPendingClick(
            pending = pending,
            stage = ClickLogStage.SkippedBySafety,
            success = false,
            reason = decision.reason,
            attempt = pending.firstAttempt,
            effectConfirmReason = decision.reason,
            blockedReason = decision.reason,
            eventContext = eventContext
        )
    }

    private fun isCurrentPending(pending: PendingClick): Boolean =
        pendingClick?.let { current ->
            current.signature == pending.signature &&
                current.eventContext.openingAdSessionKey == pending.eventContext.openingAdSessionKey
        } == true

    private fun canDispatchPending(pending: PendingClick): Boolean {
        if (!isCurrentPending(pending) || abortPendingIfAutomationBlocked(pending)) return false
        service.refreshForegroundForClick()
        val ruleWindowMs = pending.activeRules.firstOrNull { it.id == pending.ruleId }?.validDurationMs
            ?: pending.eventContext.defaultRuleWindowMs
        if (service.isSessionOpen(
                pending.eventContext.openingAdSessionKey,
                pending.packageName,
                minOf(ruleWindowMs, pending.eventContext.defaultRuleWindowMs)
            )
        ) return true
        val sameSession = pending.eventContext.openingAdSessionKey == service.foregroundStateSnapshot.sessionKey
        finishPendingClick(
            pending = pending,
            stage = if (sameSession) ClickLogStage.ClickCancelledTimeWindowExpired else ClickLogStage.ClickCancelledPackageChanged,
            success = false,
            reason = if (sameSession) "click_cancelled_time_window_expired" else "opening_ad_session_changed",
            attempt = pending.firstAttempt
        )
        return false
    }

    internal fun cancelUnsentClick(sessionKey: String) {
        val pending = pendingClick ?: return
        if (pending.clickDispatched || pending.eventContext.openingAdSessionKey != sessionKey) return
        finishPendingClick(
            pending = pending,
            stage = ClickLogStage.ClickCancelledTimeWindowExpired,
            success = false,
            reason = "click_cancelled_time_window_expired",
            attempt = pending.firstAttempt
        )
    }

    private companion object {
        const val CLICK_VERIFY_DELAY_MS = 360L
        const val GESTURE_VERIFY_DELAY_MS = 460L
    }
}
