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
import com.example.skip.engine.CoordinateFallbackMatch
import com.example.skip.engine.CoordinateFallbackMatcher
import com.example.skip.engine.HighRiskClickDecision
import com.example.skip.engine.HighRiskClickPolicy
import com.example.skip.engine.NodeScanner
import com.example.skip.engine.RulePlanProvider
import com.example.skip.engine.SafetyGuard
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
    private var foregroundState = ForegroundWindowState()
    private val lastRuleClickAt = mutableMapOf<String, Long>()
    private var lastClickSignature: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingClick: PendingClick? = null
    private var openingAdRescanKey: String? = null
    private var lastWindowStateChangedAt = 0L
    private var currentActivityName: String = ""
    private val lastToastAt = mutableMapOf<String, Long>()
    private var popupView: View? = null

    private val foregroundPackage: String?
        get() = foregroundState.currentForegroundPackage

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsRepository.markServiceConnected(this)
        RuleRepository.disableRulesForPackage(this, packageName)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !event.isSupportedEvent()) return

        val now = System.currentTimeMillis()
        val windowStateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowStateChangedAt = now
        }
        SettingsRepository.markServiceActive(this)
        if (!SettingsRepository.isMasterEnabled(this)) {
            currentActivityName = ""
            SettingsRepository.setLastFailureReason(this, "automation_paused")
            return
        }

        val eventPackageName = event.packageName?.toString().orEmpty()
        val rootSelection = selectRootForEvent(eventPackageName)
        val root = rootSelection.root
        val rootPackageName = root?.packageName?.toString().orEmpty()
        val packageResolution = EventWindowTracker.resolveTrustedPackage(
            eventPackageName = eventPackageName,
            rootPackageName = rootPackageName
        )
        val currentPackage = packageResolution.resolvedPackageName
        updateForegroundWindow(currentPackage, now, windowStateChanged)

        val observedActivityName = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString().orEmpty()
        } else {
            ""
        }
        if (observedActivityName.isNotBlank()) {
            currentActivityName = observedActivityName
        }
        val activityName = currentActivityName
        var eventContext = buildEventContext(
            eventType = event.eventType,
            eventPackageName = eventPackageName,
            packageName = currentPackage,
            activityName = activityName,
            windowId = event.windowId,
            root = root,
            now = now,
            defaultRuleWindowMs = RuleRepository.getDefaultRuleConfig(this).validDurationMs
        )

        logEvent(
            stage = ClickLogStage.ServiceEventReceived,
            eventContext = eventContext,
            detail = joinDetails(packageResolution.detail, rootSelection.detail)
        )

        if (currentPackage.isBlank()) {
            logEvent(
                stage = ClickLogStage.EventPackageNull,
                eventContext = eventContext,
                failureReason = "event_package_null"
            )
            SettingsRepository.setLastFailureReason(this, "event_package_null")
            return
        }

        if (SafetyGuard.isSelfPackage(this, currentPackage)) {
            logEvent(
                stage = ClickLogStage.SkippedSelfPackage,
                eventContext = eventContext,
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
                eventContext = eventContext,
                ruleType = "safety",
                failureReason = "safety_guard_blocked",
                blockedReason = blockedReason,
                isSelfAppLabelCandidate = ownLabelOnLauncher
            )
            SettingsRepository.setLastFailureReason(this, "safety_guard_blocked")
            showDebugToast("已跳过自动点击：安全保护应用", "safety:$currentPackage")
            return
        }

        val rulePlan = RulePlanProvider.plan(
            packageName = currentPackage,
            selfPackageName = packageName,
            policy = SettingsRepository.getAppPolicy(this, currentPackage),
            customRules = RuleRepository.getEnabledCustomRulesForPackage(this, currentPackage),
            builtInRule = RuleRepository.getBuiltInRuleForPackage(this, currentPackage)
        )
        val customRules = rulePlan.customRules
        val rules = rulePlan.rules
        val windowExpiredScope = rulePlan.scope
        val ruleWindowMs = rules.maxOfOrNull { it.validDurationMs } ?: eventContext.defaultRuleWindowMs
        if (eventContext.defaultRuleWindowMs != ruleWindowMs) {
            eventContext = buildEventContext(
                eventType = event.eventType,
                eventPackageName = eventPackageName,
                packageName = currentPackage,
                activityName = activityName,
                windowId = event.windowId,
                root = root,
                now = now,
                defaultRuleWindowMs = ruleWindowMs
            )
        }

        rulePlan.skipStage?.let { stage ->
            logEvent(
                stage = stage,
                eventContext = eventContext,
                ruleType = if (stage == ClickLogStage.SkippedBySafety) "safety" else "policy",
                failureReason = rulePlan.failureReason
            )
            SettingsRepository.setLastFailureReason(this, rulePlan.failureReason)
            return
        }

        if (!eventContext.isWithinDefaultRuleWindow) {
            val failureReason = if (customRules.isNotEmpty()) {
                "custom_rule_window_expired"
            } else {
                "default_rule_window_expired"
            }
            logEvent(
                stage = ClickLogStage.SkippedByTimeWindow,
                eventContext = eventContext,
                ruleType = if (customRules.isNotEmpty()) "custom" else "default",
                ruleName = if (customRules.isNotEmpty()) "自定义规则" else "默认开屏跳过",
                failureReason = failureReason,
                ruleScope = windowExpiredScope
            )
            SettingsRepository.setLastFailureReason(this, failureReason)
            return
        }

        if (root == null) {
            logEvent(
                stage = ClickLogStage.RootWindowNull,
                eventContext = eventContext,
                failureReason = "root_window_null"
            )
            SettingsRepository.setLastFailureReason(this, "root_window_null")
            return
        }

        if (pendingClick != null) return

        if (rules.isEmpty()) {
            SettingsRepository.setLastFailureReason(this, "no_enabled_rules")
            return
        }

        val activeRules = rules.filter { rule ->
            val last = lastRuleClickAt[rule.id] ?: 0L
            now - last >= rule.cooldownMs
        }
        if (activeRules.isEmpty()) {
            logEvent(
                stage = ClickLogStage.SkippedByCooldown,
                eventContext = eventContext,
                failureReason = "cooldown_active",
                ruleScope = windowExpiredScope
            )
            SettingsRepository.setLastFailureReason(this, "cooldown_active")
            return
        }

        val appElapsedMs = eventContext.elapsedSinceForegroundMs ?: 0L
        val activityScopedRules = NodeScanner.filterRulesForActivity(activeRules, eventContext.activityName)
        val scan = NodeScanner.scan(root, activeRules, appElapsedMs, eventContext.activityName)
        logScan(scan, eventContext)
        val match = scan.bestMatch
        if (match == null) {
            val fallback = CoordinateFallbackMatcher.find(
                root = root,
                rules = activityScopedRules,
                packageName = currentPackage,
                selfPackageName = packageName,
                elapsedSinceForegroundMs = appElapsedMs,
                screenWidth = resources.displayMetrics.widthPixels,
                screenHeight = resources.displayMetrics.heightPixels
            )
            if (fallback != null) {
                val signature = "$currentPackage:${fallback.rule.id}:coordinate:${fallback.x}:${fallback.y}"
                val lastAnyRuleClick = lastRuleClickAt.values.maxOrNull() ?: 0L
                if (signature == lastClickSignature && now - lastAnyRuleClick < REPEAT_CLICK_GUARD_MS) return
                startStableCoordinateFallback(
                    packageName = currentPackage,
                    fallback = fallback,
                    activeRules = activityScopedRules,
                    signature = signature,
                    eventContext = eventContext,
                    scan = scan
                )
                return
            }
            val stage = when {
                scan.failureReason == HighRiskClickPolicy.BLOCKED_REASON -> ClickLogStage.SkippedBySafety
                scan.candidateCount == 0 -> ClickLogStage.NoCandidateFound
                scan.failureReason == "score_below_min_score" ||
                    scan.failureReason == "candidate_below_threshold" -> ClickLogStage.SkippedByLowScore
                else -> ClickLogStage.ClickFailed
            }
            logEvent(
                stage = stage,
                eventContext = eventContext,
                ruleType = scan.bestCandidateRuleSource?.toClickLogType().orEmpty(),
                ruleName = scan.bestCandidateRuleName,
                ruleId = scan.bestCandidateRuleId,
                candidateCount = scan.candidateCount,
                bestCandidateScore = scan.bestCandidateScore,
                bestCandidateBounds = scan.bestCandidateBounds,
                minScore = scan.bestCandidateMinScore,
                matchedKeyword = scan.bestCandidateMatchedKeyword,
                failureReason = scan.failureReason,
                reason = scan.failureReason,
                detail = scan.bestCandidateMatchedKeyword,
                blockedReason = if (scan.failureReason == HighRiskClickPolicy.BLOCKED_REASON) {
                    HighRiskClickPolicy.BLOCKED_REASON
                } else {
                    ""
                }
            )
            SettingsRepository.setLastFailureReason(this, scan.failureReason)
            scheduleOpeningAdRescans(
                packageName = currentPackage,
                activeRules = activeRules,
                baseEventContext = eventContext,
                stage = stage
            )
            return
        }

        startClickFromMatch(currentPackage, match, activeRules, eventContext, scan)
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacksAndMessages(null)
        SettingsRepository.markServiceInterrupted(this)
        hidePopup()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        hidePopup()
        super.onDestroy()
    }

    private fun updateForegroundWindow(packageName: String, now: Long, windowStateChanged: Boolean) {
        val previous = foregroundState
        foregroundState = EventWindowTracker.updateForegroundWindow(
            state = foregroundState,
            resolvedPackageName = packageName,
            now = now,
            selfPackageName = this.packageName,
            windowStateChanged = windowStateChanged
        )
        if (foregroundState != previous) {
            lastClickSignature = null
            openingAdRescanKey = null
            currentActivityName = ""
        }
    }

    private fun selectRootForEvent(eventPackageName: String): RootSelection {
        return selectRoot(
            preferredPackageName = eventPackageName,
            allowSingleExternalFallback = true
        )
    }

    private fun selectRootForPackage(packageName: String): RootSelection {
        return selectRoot(
            preferredPackageName = packageName,
            allowSingleExternalFallback = false
        )
    }

    private fun selectRoot(
        preferredPackageName: String,
        allowSingleExternalFallback: Boolean
    ): RootSelection {
        val activeRoot = rootInActiveWindow
        val activePackage = activeRoot?.packageName?.toString().orEmpty().trim()
        val preferredPackage = preferredPackageName.trim()
        if (activeRoot != null &&
            activePackage.isUsableScanPackage() &&
            (preferredPackage.isBlank() ||
                activePackage == preferredPackage ||
                !preferredPackage.isUsableScanPackage())
        ) {
            return RootSelection(activeRoot)
        }

        if (preferredPackage.isUsableScanPackage()) {
            interactiveWindowRoots()
                .firstOrNull { it.packageName == preferredPackage }
                ?.let { root ->
                    return RootSelection(
                        root = root.root,
                        detail = "interactive_window_root_selected:package=${root.packageName}"
                    )
                }
        }

        if (allowSingleExternalFallback &&
            activeRoot != null &&
            !activePackage.isUsableScanPackage()
        ) {
            val usableRoots = interactiveWindowRoots()
                .filter { it.packageName.isUsableScanPackage() }
                .distinctBy { it.packageName }
            if (usableRoots.size == 1) {
                val root = usableRoots.single()
                return RootSelection(
                    root = root.root,
                    detail = "single_interactive_window_root_selected:package=${root.packageName}"
                )
            }
        }

        return RootSelection(activeRoot)
    }

    private fun interactiveWindowRoots(): List<WindowRoot> {
        return runCatching { windows }
            .getOrNull()
            .orEmpty()
            .mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                val packageName = root.packageName?.toString().orEmpty().trim()
                if (packageName.isBlank()) null else WindowRoot(root, packageName)
            }
    }

    private fun String.isUsableScanPackage(): Boolean {
        return isNotBlank() &&
            this != this@SkipAccessibilityService.packageName &&
            !SafetyGuard.isProtectedPackage(this)
    }

    private fun joinDetails(vararg details: String): String {
        return details.filter { it.isNotBlank() }.joinToString(";")
    }

    private fun displayAppName(packageName: String, configuredAppName: String): String {
        return AppDisplayNamePolicy.displayName(
            configuredAppName = configuredAppName,
            packageName = packageName,
            resolveLabel = { InstalledAppUtils.getAppLabel(this, it) }
        )
    }

    private fun scheduleOpeningAdRescans(
        packageName: String,
        activeRules: List<SkipRule>,
        baseEventContext: EventContext,
        stage: ClickLogStage
    ) {
        if (!OpeningAdRescanPolicy.shouldSchedule(
                stage = stage,
                isWithinDefaultRuleWindow = baseEventContext.isWithinDefaultRuleWindow,
                hasPendingClick = pendingClick != null,
                hasActiveRules = activeRules.isNotEmpty()
            )
        ) {
            return
        }
        val key = "$packageName:${foregroundState.foregroundStartTimeMillis}"
        if (openingAdRescanKey == key) return
        openingAdRescanKey = key
        OpeningAdRescanPolicy.delaysMs.forEach { delayMs ->
            mainHandler.postDelayed(
                { runOpeningAdRescan(key, packageName, baseEventContext.defaultRuleWindowMs) },
                delayMs
            )
        }
    }

    private fun runOpeningAdRescan(
        key: String,
        packageName: String,
        defaultRuleWindowMs: Long
    ) {
        if (openingAdRescanKey != key || pendingClick != null) return
        val rootSelection = selectRootForPackage(packageName)
        val root = rootSelection.root ?: return
        val rootPackageName = root.packageName?.toString().orEmpty()
        val packageResolution = EventWindowTracker.resolveTrustedPackage(
            eventPackageName = packageName,
            rootPackageName = rootPackageName
        )
        val currentPackage = packageResolution.resolvedPackageName
        if (currentPackage != packageName ||
            currentPackage.isBlank() ||
            SafetyGuard.isSelfPackage(this, currentPackage) ||
            SafetyGuard.isProtectedPackage(currentPackage)
        ) {
            return
        }

        val now = System.currentTimeMillis()
        updateForegroundWindow(currentPackage, now, windowStateChanged = false)
        val rulePlan = RulePlanProvider.plan(
            packageName = currentPackage,
            selfPackageName = this.packageName,
            policy = SettingsRepository.getAppPolicy(this, currentPackage),
            customRules = RuleRepository.getEnabledCustomRulesForPackage(this, currentPackage),
            builtInRule = RuleRepository.getBuiltInRuleForPackage(this, currentPackage)
        )
        if (rulePlan.skipStage != null || rulePlan.rules.isEmpty()) return
        val ruleWindowMs = rulePlan.rules.maxOfOrNull { it.validDurationMs } ?: defaultRuleWindowMs
        val eventContext = buildEventContext(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            eventPackageName = packageName,
            packageName = currentPackage,
            activityName = currentActivityName.ifBlank { "opening_ad_rescan" },
            windowId = root.windowId,
            root = root,
            now = now,
            defaultRuleWindowMs = ruleWindowMs
        )
        if (!eventContext.isWithinDefaultRuleWindow) return

        val activeRules = rulePlan.rules.filter { rule ->
            val last = lastRuleClickAt[rule.id] ?: 0L
            now - last >= rule.cooldownMs
        }
        if (activeRules.isEmpty()) return

        val appElapsedMs = eventContext.elapsedSinceForegroundMs ?: 0L
        val activityScopedRules = NodeScanner.filterRulesForActivity(activeRules, eventContext.activityName)
        val scan = NodeScanner.scan(root, activeRules, appElapsedMs, eventContext.activityName)
        logScan(scan, eventContext)
        val match = scan.bestMatch ?: return
        startClickFromMatch(currentPackage, match, activityScopedRules, eventContext, scan)
    }

    private fun startClickFromMatch(
        currentPackage: String,
        match: MatchResult,
        activeRules: List<SkipRule>,
        eventContext: EventContext,
        scan: ScanReport
    ): Boolean {
        val signature = "$currentPackage:${match.ruleId}:${match.target.boundsString()}"
        val lastAnyRuleClick = lastRuleClickAt.values.maxOrNull() ?: 0L
        if (signature == lastClickSignature &&
            System.currentTimeMillis() - lastAnyRuleClick < REPEAT_CLICK_GUARD_MS
        ) {
            return false
        }

        val pendingMatch = ClickFlowStateMachine.previewFromMatch(
            packageName = currentPackage,
            appName = displayAppName(currentPackage, match.appName),
            match = ClickMatchSnapshot.from(match),
            eventContext = eventContext
        )
        val highRiskDecision = highRiskDecisionForPending(pendingMatch)
        if (!highRiskDecision.allowed) {
            logBlockedByHighRiskPolicy(
                eventContext = eventContext,
                pending = pendingMatch,
                decision = highRiskDecision,
                scan = scan
            )
            return true
        }

        logMatch(match, eventContext, scan)
        if (SettingsRepository.isSafetyModeEnabled(this)) {
            logEvent(
                stage = ClickLogStage.ClickSkippedBySafetyMode,
                eventContext = eventContext,
                pending = pendingMatch,
                candidateCount = scan.candidateCount,
                bestCandidateScore = scan.bestCandidateScore,
                bestCandidateBounds = scan.bestCandidateBounds,
                minScore = match.minScore,
                failureReason = "click_skipped_by_safety_mode",
                reason = "click_skipped_by_safety_mode",
                clickSkippedBySafetyMode = true
            )
            SettingsRepository.setLastFailureReason(this, "click_skipped_by_safety_mode")
            return true
        }

        startStableClick(currentPackage, match, activeRules, signature, eventContext)
        return true
    }

    private fun buildEventContext(
        eventType: Int,
        eventPackageName: String,
        packageName: String,
        activityName: String,
        windowId: Int,
        root: AccessibilityNodeInfo?,
        now: Long,
        defaultRuleWindowMs: Long
    ): EventContext {
        val snapshot = EventWindowTracker.snapshot(
            state = foregroundState,
            activePackageName = packageName,
            now = now,
            defaultRuleWindowMs = defaultRuleWindowMs
        )
        return EventContext(
            eventType = eventType,
            eventPackageName = eventPackageName,
            packageName = packageName,
            activityName = activityName,
            windowId = windowId,
            rootWindowNull = root == null,
            rootChildCount = root?.childCount,
            canRetrieveWindowContent = serviceInfo?.canRetrieveWindowContent == true,
            elapsedSinceAppStartMs = snapshot.elapsedSinceForegroundMs,
            foregroundPackage = snapshot.foregroundPackage,
            foregroundStartTimeMillis = snapshot.foregroundStartTimeMillis,
            elapsedSinceForegroundMs = snapshot.elapsedSinceForegroundMs,
            defaultRuleWindowMs = snapshot.defaultRuleWindowMs,
            isWithinDefaultRuleWindow = snapshot.isWithinDefaultRuleWindow,
            timeWindowDecision = snapshot.timeWindowDecision
        )
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
            defaultDelayMs = STABLE_CLICK_DELAY_MS
        )
        val pending = ClickFlowStateMachine.startFromMatch(
            packageName = packageName,
            appName = displayAppName(packageName, match.appName),
            match = ClickMatchSnapshot.from(match),
            activeRules = activeRules,
            signature = signature,
            eventContext = eventContext,
            delayBeforeClickMs = delayMs
        )
        pendingClick = pending
        mainHandler.postDelayed({ relocateAndClick(pending) }, delayMs)
    }

    private fun startStableCoordinateFallback(
        packageName: String,
        fallback: CoordinateFallbackMatch,
        activeRules: List<SkipRule>,
        signature: String,
        eventContext: EventContext,
        scan: ScanReport
    ) {
        val rule = fallback.rule
        val pending = ClickFlowStateMachine.startCoordinateFallback(
            packageName = packageName,
            appName = displayAppName(packageName, rule.appName),
            fallback = fallback,
            activeRules = activeRules,
            signature = signature,
            delayBeforeClickMs = STABLE_CLICK_DELAY_MS,
            eventContext = eventContext
        )

        val highRiskDecision = highRiskDecisionForPending(pending)
        if (!highRiskDecision.allowed) {
            logBlockedByHighRiskPolicy(
                eventContext = eventContext,
                pending = pending,
                decision = highRiskDecision,
                scan = scan,
                clickTargetSource = ClickTargetSourceLog.CoordinateFallback
            )
            return
        }

        logEvent(
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

        if (SettingsRepository.isSafetyModeEnabled(this)) {
            logEvent(
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
            SettingsRepository.setLastFailureReason(this, "click_skipped_by_safety_mode")
            return
        }

        pendingClick = pending
        mainHandler.postDelayed({ runCoordinateFallback(pending) }, STABLE_CLICK_DELAY_MS)
    }

    private fun relocateAndClick(pending: PendingClick) {
        if (pendingClick !== pending) return
        val root = rootInActiveWindow
        if (cancelIfUnsafeDelayedClickWindow(pending, root)) return

        val elapsed = (System.currentTimeMillis() - foregroundState.foregroundStartTimeMillis).coerceAtLeast(0L)
        val currentRules = pending.activeRules.filter { it.packageName == pending.packageName }
        val scan = NodeScanner.scan(root ?: return, currentRules, elapsed, pending.eventContext.activityName)
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

        val updated = ClickFlowStateMachine.relocateToMatch(pending, ClickMatchSnapshot.from(match))
        pendingClick = updated
        val highRiskDecision = highRiskDecisionForPending(updated)
        if (!highRiskDecision.allowed) {
            logBlockedByHighRiskPolicy(
                eventContext = updated.eventContext,
                pending = updated,
                decision = highRiskDecision,
                scan = scan,
                clearPending = true
            )
            return
        }
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

    private fun runCoordinateFallback(pending: PendingClick) {
        if (pendingClick !== pending) return
        if (cancelIfUnsafeDelayedClickWindow(pending, rootInActiveWindow)) return
        val x = pending.coordinateX ?: pending.candidate.bounds.centerX()
        val y = pending.coordinateY ?: pending.candidate.bounds.centerY()
        lastRuleClickAt[pending.ruleId] = System.currentTimeMillis()
        lastClickSignature = pending.signature

        logEvent(
            stage = ClickLogStage.ClickAttempted,
            eventContext = pending.eventContext,
            pending = pending,
            clickMethod = ClickMethodLog.DispatchGesture,
            delayBeforeClickMs = pending.delayBeforeClickMs,
            clickTargetSource = ClickTargetSourceLog.CoordinateFallback
        )

        ClickExecutor.gestureClickPoint(this, pending.candidate, x, y) { attempt ->
            if (pendingClick?.signature != pending.signature) return@gestureClickPoint
            if (!attempt.accepted) {
                finishPendingClick(
                    pending = pending.copy(firstAttempt = attempt),
                    stage = ClickLogStage.ClickFailed,
                    success = false,
                    reason = attempt.reason,
                    attempt = attempt,
                    clickTargetSource = ClickTargetSourceLog.CoordinateFallback
                )
                return@gestureClickPoint
            }
            val updated = pending.copy(firstAttempt = attempt)
            pendingClick = updated
            logEvent(
                stage = ClickLogStage.ClickActionSuccess,
                eventContext = updated.eventContext,
                pending = updated,
                clickMethod = attempt.method,
                actionReturnValue = true,
                clickResult = true,
                delayBeforeClickMs = updated.delayBeforeClickMs,
                clickTargetSource = ClickTargetSourceLog.CoordinateFallback
            )
            mainHandler.postDelayed(
                {
                    if (pendingClick?.signature == updated.signature) {
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
        val root = rootInActiveWindow
        return ClickEffectVerifier.evaluate(
            pendingPackageName = pending.packageName,
            selfPackageName = packageName,
            rootPackageName = root?.packageName?.toString().orEmpty(),
            foregroundPackageName = foregroundPackage.orEmpty(),
            rootWindowNull = root == null,
            targetStillPresent = root?.let { ClickExecutor.isTargetPresent(it, pending.candidate) } ?: false
        )
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
        val currentPackageName = root?.packageName?.toString().orEmpty().ifBlank {
            foregroundPackage.orEmpty()
        }
        val delayedEventContext = buildEventContext(
            eventType = pending.eventContext.eventType,
            eventPackageName = pending.eventContext.eventPackageName,
            packageName = currentPackageName,
            activityName = pending.eventContext.activityName,
            windowId = pending.eventContext.windowId,
            root = root,
            now = System.currentTimeMillis(),
            defaultRuleWindowMs = pending.eventContext.defaultRuleWindowMs
        )
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = pending.packageName,
            currentPackageName = currentPackageName,
            selfPackageName = packageName,
            foregroundPackageName = delayedEventContext.foregroundPackage,
            foregroundStartTimeMillis = delayedEventContext.foregroundStartTimeMillis ?: 0L,
            now = System.currentTimeMillis(),
            defaultRuleWindowMs = delayedEventContext.defaultRuleWindowMs,
            rootWindowNull = root == null
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

    private fun logScan(scan: ScanReport, eventContext: EventContext) {
        if (scan.failureReason == HighRiskClickPolicy.BLOCKED_REASON) return
        if (scan.candidateCount > 0) {
            logEvent(
                stage = ClickLogStage.CandidateFound,
                eventContext = eventContext,
                ruleType = scan.bestCandidateRuleSource?.toClickLogType().orEmpty(),
                ruleName = scan.bestCandidateRuleName,
                ruleId = scan.bestCandidateRuleId,
                candidateCount = scan.candidateCount,
                bestCandidateScore = scan.bestCandidateScore,
                bestCandidateBounds = scan.bestCandidateBounds,
                minScore = scan.bestCandidateMinScore,
                matchedKeyword = scan.bestCandidateMatchedKeyword,
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
            pending = ClickFlowStateMachine.previewFromMatch(
                packageName = eventContext.packageName,
                appName = match.appName,
                match = ClickMatchSnapshot.from(match),
                eventContext = eventContext
            ),
            candidateCount = scan.candidateCount,
            bestCandidateScore = scan.bestCandidateScore,
            bestCandidateBounds = scan.bestCandidateBounds,
            minScore = match.minScore
        )
    }

    private fun highRiskDecisionForPending(pending: PendingClick): HighRiskClickDecision {
        return HighRiskClickPolicy.evaluateTexts(
            listOf(
                pending.ruleName,
                pending.matchedKeyword,
                pending.target.text,
                pending.target.contentDescription,
                pending.candidate.text,
                pending.candidate.contentDescription,
                pending.target.viewId,
                pending.candidate.viewId
            )
        )
    }

    private fun logBlockedByHighRiskPolicy(
        eventContext: EventContext,
        pending: PendingClick,
        decision: HighRiskClickDecision,
        scan: ScanReport? = null,
        clickTargetSource: ClickTargetSourceLog = pending.clickTargetSource,
        clearPending: Boolean = false
    ) {
        if (clearPending && pendingClick?.signature == pending.signature) {
            pendingClick = null
        }
        SettingsRepository.setLastFailureReason(this, HighRiskClickPolicy.BLOCKED_REASON)
        showDebugToast("安全策略已阻止高风险点击，已记录日志", "safety:${pending.packageName}:${pending.ruleId}")
        logEvent(
            stage = ClickLogStage.SkippedBySafety,
            eventContext = eventContext,
            pending = pending,
            success = false,
            reason = HighRiskClickPolicy.BLOCKED_REASON,
            failureReason = HighRiskClickPolicy.BLOCKED_REASON,
            detail = decision.matchedTerm,
            candidateCount = scan?.candidateCount,
            bestCandidateScore = scan?.bestCandidateScore,
            bestCandidateBounds = scan?.bestCandidateBounds.orEmpty(),
            minScore = scan?.bestCandidateMinScore ?: pending.minScore,
            blockedReason = HighRiskClickPolicy.BLOCKED_REASON,
            clickTargetSource = clickTargetSource
        )
    }

    private fun logEvent(
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
        clickTargetSource: ClickTargetSourceLog = pending?.clickTargetSource ?: ClickTargetSourceLog.None,
        ruleScope: String = pending?.ruleScope.orEmpty()
    ) {
        val safetyModeEnabled = SettingsRepository.isSafetyModeEnabled(this)
        val appName = pending?.appName ?: eventContext.packageName.takeIf { it.isNotBlank() }?.let {
            InstalledAppUtils.getAppLabel(this, it)
        }.orEmpty()
        LogRepository.addClickLog(
            context = this,
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
                detail = detail,
                blockedReason = blockedReason,
                defaultRuleAreaAllowed = defaultRuleAreaAllowed,
                textKeywordIsStandaloneSkip = textKeywordIsStandaloneSkip,
                effectConfirmReason = effectConfirmReason,
                clickSkippedBySafetyMode = clickSkippedBySafetyMode,
                isSelfAppLabelCandidate = isSelfAppLabelCandidate,
                isSystemPackage = SafetyGuard.isSystemPackage(eventContext.packageName),
                isLauncherPackage = SafetyGuard.isLauncherPackage(eventContext.packageName),
                isSelfPackage = SafetyGuard.isSelfPackage(this, eventContext.packageName),
                candidateAreaRatio = candidateAreaRatio,
                isLargeCandidateBounds = isLargeCandidateBounds,
                clickTargetSource = clickTargetSource,
                ruleScope = ruleScope
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
        mainHandler.post { showOverlayPopup(message) }
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
            setImageResource(
                IconManager.displayIconRes(IconManager.currentScheme(this@SkipAccessibilityService))
            )
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

    companion object {
        private const val REPEAT_CLICK_GUARD_MS = 3000L
        internal const val STABLE_CLICK_DELAY_MS = 100L
        private const val CLICK_VERIFY_DELAY_MS = 360L
        private const val GESTURE_VERIFY_DELAY_MS = 460L
        private const val TOAST_COOLDOWN_MS = 60_000L
        private const val POPUP_DURATION_MS = 1600L
    }
}

private data class RootSelection(
    val root: AccessibilityNodeInfo?,
    val detail: String = ""
)

private data class WindowRoot(
    val root: AccessibilityNodeInfo,
    val packageName: String
)

internal data class ClickVerification(
    val stage: ClickLogStage,
    val success: Boolean,
    val reason: String
)

internal object ClickEffectVerifier {
    fun evaluate(
        pendingPackageName: String,
        selfPackageName: String,
        rootPackageName: String?,
        foregroundPackageName: String?,
        rootWindowNull: Boolean,
        targetStillPresent: Boolean
    ): ClickVerification {
        val pending = pendingPackageName.trim()
        val self = selfPackageName.trim()
        val rootPackage = rootPackageName.orEmpty().trim()
        val foregroundPackage = foregroundPackageName.orEmpty().trim()

        if (rootWindowNull) {
            return ClickVerification(
                stage = ClickLogStage.ClickEffectUnknown,
                success = false,
                reason = "root_window_null_after_click"
            )
        }
        if (rootPackage == self || foregroundPackage == self) {
            return ClickVerification(
                stage = ClickLogStage.ClickMisfireSelfOpened,
                success = false,
                reason = "self_app_opened_after_click"
            )
        }
        if (rootPackage.isNotBlank() && SafetyGuard.isProtectedPackage(rootPackage)) {
            return ClickVerification(
                stage = ClickLogStage.ClickEffectUnknown,
                success = false,
                reason = "protected_package_after_click"
            )
        }
        if (rootPackage.isNotBlank() && rootPackage != pending) {
            return ClickVerification(
                stage = ClickLogStage.ClickEffectConfirmed,
                success = true,
                reason = "package_changed_after_click"
            )
        }
        if (foregroundPackage.isNotBlank() && SafetyGuard.isProtectedPackage(foregroundPackage)) {
            return ClickVerification(
                stage = ClickLogStage.ClickEffectUnknown,
                success = false,
                reason = "protected_package_after_click"
            )
        }
        if (foregroundPackage.isNotBlank() && foregroundPackage != pending) {
            return ClickVerification(
                stage = ClickLogStage.ClickEffectConfirmed,
                success = true,
                reason = "package_changed_after_click"
            )
        }
        if (SafetyGuard.isProtectedPackage(pending)) {
            return ClickVerification(
                stage = ClickLogStage.ClickEffectUnknown,
                success = false,
                reason = "protected_package_never_confirmed"
            )
        }
        return if (targetStillPresent) {
            ClickVerification(
                stage = ClickLogStage.ClickEffectUnknown,
                success = false,
                reason = "candidate_still_present"
            )
        } else {
            ClickVerification(
                stage = ClickLogStage.ClickEffectConfirmed,
                success = true,
                reason = "candidate_node_disappeared"
            )
        }
    }
}

internal object DelayedClickSafetyCheck {
    fun evaluate(
        pendingPackageName: String,
        currentPackageName: String?,
        selfPackageName: String,
        foregroundPackageName: String,
        foregroundStartTimeMillis: Long,
        now: Long,
        defaultRuleWindowMs: Long,
        rootWindowNull: Boolean
    ): DelayedClickSafetyResult {
        val pending = pendingPackageName.trim()
        val current = currentPackageName.orEmpty().trim()
        val detail = "pendingPackageName=$pending;currentPackageName=$current;foregroundPackage=$foregroundPackageName"
        if (rootWindowNull) {
            return DelayedClickSafetyResult(
                allowed = false,
                stage = ClickLogStage.RootWindowNull,
                reason = "root_window_null_before_click",
                detail = detail
            )
        }
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
                reason = "safety_guard_blocked",
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
        if (foregroundPackageName != pending) {
            return DelayedClickSafetyResult(
                allowed = false,
                stage = ClickLogStage.ClickCancelledPackageChanged,
                reason = "package_changed_before_click",
                detail = detail
            )
        }
        val elapsed = (now - foregroundStartTimeMillis).coerceAtLeast(0L)
        if (foregroundStartTimeMillis <= 0L || elapsed > defaultRuleWindowMs) {
            return DelayedClickSafetyResult(
                allowed = false,
                stage = ClickLogStage.ClickCancelledTimeWindowExpired,
                reason = "click_cancelled_time_window_expired",
                detail = "$detail;elapsedSinceForegroundMs=$elapsed"
            )
        }
        return DelayedClickSafetyResult(
            allowed = true,
            stage = ClickLogStage.ClickEffectUnknown,
            reason = "",
            detail = "$detail;elapsedSinceForegroundMs=$elapsed"
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
