package com.example.skip.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
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
import com.example.skip.engine.CoordinateFallbackMatchResult
import com.example.skip.engine.CurrentTargetRevalidator
import com.example.skip.engine.HighRiskClickDecision
import com.example.skip.engine.HighRiskClickPolicy
import com.example.skip.engine.NodeScanner
import com.example.skip.engine.RulePlanProvider
import com.example.skip.engine.SafetyGuard
import com.example.skip.engine.ScoreEvaluator
import com.example.skip.engine.StandaloneSkipGestureRevalidationPolicy
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.MatchResult
import com.example.skip.model.RuleSource
import com.example.skip.model.ScanReport
import com.example.skip.model.SkipRule
import com.example.skip.util.InstalledAppUtils
import com.example.skip.util.RomUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SkipAccessibilityService : AccessibilityService() {
    private var foregroundState = ForegroundWindowState()
    private val lastRuleClickAt = mutableMapOf<String, Long>()
    private var lastClickSignature: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingClick: PendingClick? = null
    private var openingAdRescanKey: String? = null
    private var openingAdRetryGeneration = 0L
    private var openingAdRetryScheduled = false
    private var openingAdRetryCount = 0
    private var openingAdRetrySessionKey: String? = null
    private var openingAdTerminalSessionKey: String? = null
    private var lastWindowStateChangedAt = 0L
    private var currentActivityName: String = ""
    private var currentActivityIdentityKnown = false
    private val lastToastAt = mutableMapOf<String, Long>()
    private val lastCoordinateFallbackBlockedAt = mutableMapOf<String, Long>()
    private var popupView: View? = null
    private var enabledInputMethodPackages: Set<String> = emptySet()
    private var lastInputMethodRefreshElapsedMillis = 0L
    private var inputMethodSettingsObserver: ContentObserver? = null

    private val foregroundPackage: String?
        get() = foregroundState.currentForegroundPackage

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshEnabledInputMethodPackages()
        registerInputMethodSettingsObserver()
        SettingsRepository.markServiceConnected(this)
        RuleRepository.disableRulesForPackage(this, packageName)
        LogRepository.start(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !event.isSupportedEvent()) return

        processAccessibilityEvent(event)
    }

    private fun processAccessibilityEvent(event: AccessibilityEvent) {

        val now = System.currentTimeMillis()
        val windowStateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowStateChangedAt = now
        }
        SettingsRepository.markServiceActive(this)
        if (!SettingsRepository.isMasterEnabled(this)) {
            currentActivityName = ""
            currentActivityIdentityKnown = false
            pendingClick = null
            terminateOpeningAdRecovery()
            SettingsRepository.setLastFailureReason(this, "automation_paused", forcePersist = true)
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
        if (isInputMethodWindow(event, root, currentPackage)) {
            val inputMethodContext = buildEventContext(
                eventType = event.eventType,
                eventPackageName = eventPackageName,
                packageName = currentPackage,
                activityName = currentActivityName,
                windowId = event.windowId,
                root = root,
                now = now,
                defaultRuleWindowMs = RuleRepository.DEFAULT_RULE_WINDOW_MS
            )
            logEvent(
                stage = ClickLogStage.SkippedBySafety,
                eventContext = inputMethodContext,
                ruleType = "safety",
                failureReason = InputMethodWindowPolicy.BLOCKED_REASON,
                blockedReason = InputMethodWindowPolicy.BLOCKED_REASON
            )
            SettingsRepository.setLastFailureReason(this, InputMethodWindowPolicy.BLOCKED_REASON)
            pendingClick = null
            terminateOpeningAdRecovery()
            return
        }
        updateForegroundWindow(currentPackage, now, windowStateChanged)

        val observedActivityName = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString().orEmpty()
        } else {
            ""
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentActivityIdentityKnown = observedActivityName.isNotBlank()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            currentActivityIdentityKnown = false
        }
        if (observedActivityName.isNotBlank()) {
            currentActivityName = observedActivityName
        }
        val activityName = currentActivityName

        pendingClick?.let { pending ->
            val pendingEventContext = buildEventContext(
                eventType = event.eventType,
                eventPackageName = eventPackageName,
                packageName = currentPackage,
                activityName = activityName,
                windowId = event.windowId,
                root = root,
                now = now,
                defaultRuleWindowMs = pending.eventContext.defaultRuleWindowMs
            )
            handlePendingEventFastPath(pending, pendingEventContext)
            return
        }

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
            terminateOpeningAdRecovery()
            return
        }

        val rulePlan = RulePlanProvider.plan(
            packageName = currentPackage,
            selfPackageName = packageName,
            policy = SettingsRepository.getAppPolicy(this, currentPackage),
            customRules = RuleRepository.getEnabledCustomRulesForPackage(this, currentPackage),
            builtInRule = RuleRepository.getBuiltInRuleForPackage(this, currentPackage),
            currentActivity = activityName.takeIf { currentActivityIdentityKnown }.orEmpty(),
            builtInPreciseRules = RuleRepository.getBuiltInPreciseRulesForPackage(currentPackage)
        )
        val customRules = rulePlan.customRules
        val rules = rulePlan.rules
        val windowExpiredScope = rulePlan.scope
        val ruleWindowMs = rulePlan.effectiveWindowMs
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
            terminateOpeningAdRecovery()
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
            terminateOpeningAdRecovery()
            return
        }

        if (rules.isEmpty()) {
            SettingsRepository.setLastFailureReason(this, "no_enabled_rules")
            terminateOpeningAdRecovery()
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
            terminateOpeningAdRecovery()
            return
        }

        if (root == null) {
            logEvent(
                stage = ClickLogStage.RootWindowNull,
                eventContext = eventContext,
                failureReason = "root_window_null"
            )
            SettingsRepository.setLastFailureReason(this, "root_window_null")
            scheduleOpeningAdRescans(
                packageName = currentPackage,
                activeRules = activeRules,
                baseEventContext = eventContext,
                stage = ClickLogStage.RootWindowNull
            )
            scheduleOpeningAdRetry(
                packageName = currentPackage,
                activeRules = activeRules,
                baseEventContext = eventContext,
                reason = "root_window_null",
                retriesPerformed = 0
            )
            return
        }

        val activityScopedRules = NodeScanner.filterRulesForActivity(rules, eventContext.activityName)
        if (ActiveTextInputGuard.hasFocusedEditableInput(root)) {
            val coordinateResult = CoordinateFallbackMatcher.findResult(
                root = root,
                rules = activityScopedRules,
                packageName = currentPackage,
                selfPackageName = packageName,
                elapsedSinceForegroundMs = eventContext.elapsedSinceForegroundMs ?: 0L,
                screenWidth = resources.displayMetrics.widthPixels,
                screenHeight = resources.displayMetrics.heightPixels,
                activeTextInput = true
            )
            if (coordinateResult is CoordinateFallbackMatchResult.Blocked) {
                logCoordinateFallbackBlocked(
                    eventContext = eventContext,
                    result = coordinateResult
                )
            } else {
                SettingsRepository.setLastFailureReason(this, "active_text_input")
            }
            terminateOpeningAdRecovery()
            return
        }

        val appElapsedMs = eventContext.elapsedSinceForegroundMs ?: 0L
        val activeActivityScopedRules = NodeScanner.filterRulesForActivity(activeRules, eventContext.activityName)
        val scanStartedAt = SystemClock.elapsedRealtime()
        val scan = NodeScanner.scan(root, activeRules, appElapsedMs, eventContext.activityName)
        val scanDurationMs = (SystemClock.elapsedRealtime() - scanStartedAt).coerceAtLeast(0L)
        logScan(scan, eventContext, scanDurationMs = scanDurationMs)
        val match = scan.bestMatch
        if (match == null) {
            when (
                val coordinateResult = CoordinateFallbackMatcher.findResult(
                root = root,
                rules = activeActivityScopedRules,
                packageName = currentPackage,
                selfPackageName = packageName,
                elapsedSinceForegroundMs = appElapsedMs,
                screenWidth = resources.displayMetrics.widthPixels,
                screenHeight = resources.displayMetrics.heightPixels
                )
            ) {
                is CoordinateFallbackMatchResult.Matched -> {
                    val fallback = coordinateResult.match
                    val signature = "$currentPackage:${fallback.rule.id}:coordinate:${fallback.x}:${fallback.y}"
                    val lastAnyRuleClick = lastRuleClickAt.values.maxOrNull() ?: 0L
                    if (signature == lastClickSignature && now - lastAnyRuleClick < REPEAT_CLICK_GUARD_MS) return
                    startStableCoordinateFallback(
                        packageName = currentPackage,
                        fallback = fallback,
                        activeRules = activeActivityScopedRules,
                        signature = signature,
                        eventContext = eventContext,
                        scan = scan,
                        scanDurationMs = scanDurationMs
                    )
                    return
                }
                is CoordinateFallbackMatchResult.Blocked -> {
                    logCoordinateFallbackBlocked(
                        eventContext = eventContext,
                        result = coordinateResult,
                        scan = scan
                    )
                    terminateOpeningAdRecovery()
                    return
                }
                CoordinateFallbackMatchResult.NotApplicable -> Unit
            }
            val stage = scanMissStage(scan)
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
                scanDurationMs = scanDurationMs,
                blockedReason = if (stage == ClickLogStage.SkippedBySafety) scan.failureReason else ""
            )
            SettingsRepository.setLastFailureReason(
                this,
                scan.failureReason,
                forcePersist = stage == ClickLogStage.SkippedBySafety
            )
            if (stage == ClickLogStage.SkippedBySafety) {
                terminateOpeningAdRecovery()
                return
            }
            scheduleOpeningAdRescans(
                packageName = currentPackage,
                activeRules = activeRules,
                baseEventContext = eventContext,
                stage = stage
            )
            scheduleOpeningAdRetry(
                packageName = currentPackage,
                activeRules = activeRules,
                baseEventContext = eventContext,
                reason = scan.failureReason,
                retriesPerformed = 0
            )
            return
        }

        startClickFromMatch(
            currentPackage = currentPackage,
            match = match,
            activeRules = activeRules,
            eventContext = eventContext,
            scan = scan,
            scanDurationMs = scanDurationMs
        )
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacksAndMessages(null)
        pendingClick = null
        cancelOpeningAdRecovery(resetRetrySession = true)
        LogRepository.flushPendingWritesAsync(applicationContext)
        SettingsRepository.markServiceInterrupted(this)
        hidePopup()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        pendingClick = null
        cancelOpeningAdRecovery(resetRetrySession = true)
        unregisterInputMethodSettingsObserver()
        SettingsRepository.flushRuntimeDiagnostics(this)
        LogRepository.flushPendingWritesAsync(applicationContext)
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
            cancelOpeningAdRecovery(resetRetrySession = true)
            currentActivityName = ""
            currentActivityIdentityKnown = false
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
        if (!OpeningAdRecoveryGate.canRun(
                masterEnabled = SettingsRepository.isMasterEnabled(this),
                sessionKey = key,
                terminalSessionKey = openingAdTerminalSessionKey
            )
        ) return
        if (openingAdRescanKey == key) return
        val foregroundStartTimeMillis = baseEventContext.foregroundStartTimeMillis ?: return
        val delaysMs = OpeningAdRescanPolicy.remainingDelaysMs(
            foregroundStartTimeMillis = foregroundStartTimeMillis,
            nowMillis = System.currentTimeMillis(),
            ruleWindowMs = baseEventContext.defaultRuleWindowMs
        )
        if (delaysMs.isEmpty()) return
        openingAdRescanKey = key
        val expectedActivityName = baseEventContext.activityName
        delaysMs.forEach { delayMs ->
            mainHandler.postDelayed(
                {
                    runOpeningAdRescan(
                        key = key,
                        packageName = packageName,
                        defaultRuleWindowMs = baseEventContext.defaultRuleWindowMs,
                        expectedActivityName = expectedActivityName
                    )
                },
                delayMs
            )
        }
    }

    private fun runOpeningAdRescan(
        key: String,
        packageName: String,
        defaultRuleWindowMs: Long,
        expectedActivityName: String
    ) {
        if (!OpeningAdRecoveryGate.canRun(
                masterEnabled = SettingsRepository.isMasterEnabled(this),
                sessionKey = key,
                terminalSessionKey = openingAdTerminalSessionKey
            )
        ) {
            terminateOpeningAdRecovery()
            return
        }
        if (openingAdRescanKey != key || pendingClick != null) return
        if (!isSameOpeningAdSession(key, packageName) || hasActivityChanged(expectedActivityName)) {
            terminateOpeningAdRecovery(key)
            return
        }

        val now = System.currentTimeMillis()
        val rootSelection = selectRootForPackage(packageName)
        val root = rootSelection.root
        if (root == null) {
            val eventContext = buildEventContext(
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                eventPackageName = packageName,
                packageName = packageName,
                activityName = currentActivityName.ifBlank { expectedActivityName },
                windowId = -1,
                root = null,
                now = now,
                defaultRuleWindowMs = defaultRuleWindowMs
            )
            if (!eventContext.isWithinDefaultRuleWindow) {
                terminateOpeningAdRecovery()
                return
            }
            logEvent(
                stage = ClickLogStage.RootWindowNull,
                eventContext = eventContext,
                failureReason = "root_window_null",
                retryCount = openingAdRetryCount,
                rescanReason = ABSOLUTE_RESCAN_REASON,
                detail = rootSelection.detail
            )
            val rulePlan = currentRulePlan(packageName)
            if (rulePlan.skipStage == null && rulePlan.rules.isNotEmpty()) {
                scheduleOpeningAdRetry(
                    packageName = packageName,
                    activeRules = rulePlan.rules,
                    baseEventContext = eventContext,
                    reason = "root_window_null",
                    retriesPerformed = openingAdRetryCount
                )
            }
            return
        }
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
            terminateOpeningAdRecovery(key)
            return
        }

        updateForegroundWindow(currentPackage, now, windowStateChanged = false)
        val rulePlan = currentRulePlan(currentPackage)
        if (rulePlan.skipStage != null || rulePlan.rules.isEmpty()) {
            terminateOpeningAdRecovery()
            return
        }
        val ruleWindowMs = rulePlan.effectiveWindowMs
        val eventContext = buildEventContext(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            eventPackageName = packageName,
            packageName = currentPackage,
            activityName = currentActivityName,
            windowId = root.windowId,
            root = root,
            now = now,
            defaultRuleWindowMs = ruleWindowMs
        )
        if (!eventContext.isWithinDefaultRuleWindow) {
            terminateOpeningAdRecovery()
            return
        }

        val activeRules = rulePlan.rules.filter { rule ->
            val last = lastRuleClickAt[rule.id] ?: 0L
            now - last >= rule.cooldownMs
        }
        if (activeRules.isEmpty()) {
            terminateOpeningAdRecovery(key)
            return
        }

        if (ActiveTextInputGuard.hasFocusedEditableInput(root)) {
            SettingsRepository.setLastFailureReason(this, "active_text_input", forcePersist = true)
            logEvent(
                stage = ClickLogStage.SkippedBySafety,
                eventContext = eventContext,
                failureReason = "active_text_input",
                blockedReason = "active_text_input",
                retryCount = openingAdRetryCount,
                rescanReason = ABSOLUTE_RESCAN_REASON
            )
            terminateOpeningAdRecovery()
            return
        }

        val appElapsedMs = eventContext.elapsedSinceForegroundMs ?: 0L
        val scanStartedAt = SystemClock.elapsedRealtime()
        val scan = NodeScanner.scan(root, activeRules, appElapsedMs, eventContext.activityName)
        val scanDurationMs = (SystemClock.elapsedRealtime() - scanStartedAt).coerceAtLeast(0L)
        logScan(
            scan = scan,
            eventContext = eventContext,
            scanDurationMs = scanDurationMs,
            retryCount = openingAdRetryCount,
            rescanReason = ABSOLUTE_RESCAN_REASON
        )
        val match = scan.bestMatch
        if (match != null) {
            startClickFromMatch(
                currentPackage = currentPackage,
                match = match,
                activeRules = activeRules,
                eventContext = eventContext,
                scan = scan,
                retryCount = openingAdRetryCount,
                scanDurationMs = scanDurationMs,
                rescanReason = ABSOLUTE_RESCAN_REASON
            )
            return
        }

        logScanMiss(
            scan = scan,
            eventContext = eventContext,
            scanDurationMs = scanDurationMs,
            retryCount = openingAdRetryCount,
            rescanReason = ABSOLUTE_RESCAN_REASON
        )
        if (scanMissStage(scan) == ClickLogStage.SkippedBySafety) {
            terminateOpeningAdRecovery()
            return
        }
        val retryScheduled = scheduleOpeningAdRetry(
            packageName = currentPackage,
            activeRules = activeRules,
            baseEventContext = eventContext,
            reason = scan.failureReason,
            retriesPerformed = openingAdRetryCount
        ) != null
        if (!retryScheduled) cancelScheduledOpeningAdRetry()
    }

    private fun currentRulePlan(packageName: String) = RulePlanProvider.plan(
        packageName = packageName,
        selfPackageName = this.packageName,
        policy = SettingsRepository.getAppPolicy(this, packageName),
        customRules = RuleRepository.getEnabledCustomRulesForPackage(this, packageName),
        builtInRule = RuleRepository.getBuiltInRuleForPackage(this, packageName),
        currentActivity = currentActivityName.takeIf { currentActivityIdentityKnown }.orEmpty(),
        builtInPreciseRules = RuleRepository.getBuiltInPreciseRulesForPackage(packageName)
    )

    private fun refreshEnabledInputMethodPackages() {
        val refreshed = runCatching {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            buildSet {
                manager?.enabledInputMethodList
                    ?.mapNotNull { it.packageName.trim().takeIf(String::isNotBlank) }
                    ?.let(::addAll)
                Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    ?.let(ComponentName::unflattenFromString)
                    ?.packageName
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }.getOrNull()
        if (refreshed != null) {
            enabledInputMethodPackages = refreshed
            lastInputMethodRefreshElapsedMillis = SystemClock.elapsedRealtime()
        }
    }

    private fun registerInputMethodSettingsObserver() {
        if (inputMethodSettingsObserver != null) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refreshEnabledInputMethodPackages()
            }
        }
        inputMethodSettingsObserver = observer
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS),
                false,
                observer
            )
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                observer
            )
        }.onFailure {
            runCatching { contentResolver.unregisterContentObserver(observer) }
            inputMethodSettingsObserver = null
        }
    }

    private fun unregisterInputMethodSettingsObserver() {
        val observer = inputMethodSettingsObserver ?: return
        inputMethodSettingsObserver = null
        runCatching { contentResolver.unregisterContentObserver(observer) }
    }

    private fun refreshInputMethodsIfStale(eventType: Int) {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        if (SystemClock.elapsedRealtime() - lastInputMethodRefreshElapsedMillis >= INPUT_METHOD_REFRESH_INTERVAL_MS) {
            refreshEnabledInputMethodPackages()
        }
    }

    private fun isInputMethodWindow(
        event: AccessibilityEvent,
        root: AccessibilityNodeInfo?,
        packageName: String
    ): Boolean {
        refreshInputMethodsIfStale(event.eventType)
        val isInputMethodWindowType = runCatching {
            windows.firstOrNull { it.id == event.windowId }?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }.getOrDefault(false)
        if (InputMethodWindowPolicy.shouldBlock(
                packageName = packageName,
                eventClassName = event.className?.toString().orEmpty(),
                rootClassName = root?.className?.toString().orEmpty(),
                enabledInputMethodPackages = enabledInputMethodPackages,
                isInputMethodWindowType = isInputMethodWindowType
            )) {
            return true
        }
        return false
    }

    private fun scheduleOpeningAdRetry(
        packageName: String,
        activeRules: List<SkipRule>,
        baseEventContext: EventContext,
        reason: String,
        retriesPerformed: Int
    ): Int? {
        if (activeRules.isEmpty() || pendingClick != null || openingAdRetryScheduled) return null
        val foregroundStartTimeMillis = baseEventContext.foregroundStartTimeMillis ?: return null
        val sessionKey = "$packageName:$foregroundStartTimeMillis"
        if (!OpeningAdRecoveryGate.canRun(
                masterEnabled = SettingsRepository.isMasterEnabled(this),
                sessionKey = sessionKey,
                terminalSessionKey = openingAdTerminalSessionKey
            )
        ) {
            return null
        }
        if (!isSameOpeningAdSession(sessionKey, packageName) ||
            hasActivityChanged(baseEventContext.activityName)
        ) {
            return null
        }
        if (openingAdRetrySessionKey != sessionKey) {
            cancelScheduledOpeningAdRetry()
            openingAdRetrySessionKey = sessionKey
            openingAdRetryCount = 0
        }

        val performedCount = maxOf(retriesPerformed, openingAdRetryCount)
        if (!OpeningAdRetryPolicy.shouldRetry(
                reason = reason,
                retriesPerformed = performedCount,
                isWithinRuleWindow = baseEventContext.isWithinDefaultRuleWindow
            )
        ) {
            return null
        }
        val delayMs = OpeningAdRetryPolicy.nextDelayMs(performedCount) ?: return null
        val now = System.currentTimeMillis()
        val windowEndAt = foregroundStartTimeMillis + baseEventContext.defaultRuleWindowMs
        if (now + delayMs > windowEndAt) return null

        val nextRetryCount = performedCount + 1
        val generation = ++openingAdRetryGeneration
        openingAdRetryScheduled = true
        val request = OpeningAdRetryRequest(
            sessionKey = sessionKey,
            packageName = packageName,
            expectedActivityName = baseEventContext.activityName,
            activeRules = activeRules,
            defaultRuleWindowMs = baseEventContext.defaultRuleWindowMs,
            retryCount = nextRetryCount,
            reason = reason
        )
        mainHandler.postDelayed(
            {
                if (generation != openingAdRetryGeneration) return@postDelayed
                openingAdRetryScheduled = false
                openingAdRetryCount = maxOf(openingAdRetryCount, nextRetryCount)
                runOpeningAdRetry(request)
            },
            delayMs
        )
        return nextRetryCount
    }

    private fun runOpeningAdRetry(request: OpeningAdRetryRequest) {
        if (!OpeningAdRecoveryGate.canRun(
                masterEnabled = SettingsRepository.isMasterEnabled(this),
                sessionKey = request.sessionKey,
                terminalSessionKey = openingAdTerminalSessionKey
            )
        ) {
            terminateOpeningAdRecovery()
            return
        }
        if (pendingClick != null) {
            cancelScheduledOpeningAdRetry()
            return
        }
        if (!isSameOpeningAdSession(request.sessionKey, request.packageName) ||
            hasActivityChanged(request.expectedActivityName)
        ) {
            terminateOpeningAdRecovery(request.sessionKey)
            return
        }

        val now = System.currentTimeMillis()
        val rootSelection = selectRootForPackage(request.packageName)
        val root = rootSelection.root
        val rootPackageName = root?.packageName?.toString().orEmpty()
        val currentPackage = EventWindowTracker.resolveTrustedPackage(
            eventPackageName = request.packageName,
            rootPackageName = rootPackageName
        ).resolvedPackageName
        if (currentPackage != request.packageName ||
            currentPackage.isBlank() ||
            SafetyGuard.isSelfPackage(this, currentPackage) ||
            SafetyGuard.isProtectedPackage(currentPackage)
        ) {
            terminateOpeningAdRecovery(request.sessionKey)
            return
        }

        val eventContext = buildEventContext(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            eventPackageName = request.packageName,
            packageName = currentPackage,
            activityName = currentActivityName.ifBlank { request.expectedActivityName },
            windowId = root?.windowId ?: -1,
            root = root,
            now = now,
            defaultRuleWindowMs = request.defaultRuleWindowMs
        )
        if (!eventContext.isWithinDefaultRuleWindow) {
            terminateOpeningAdRecovery()
            return
        }

        if (root == null) {
            logEvent(
                stage = ClickLogStage.RootWindowNull,
                eventContext = eventContext,
                failureReason = "root_window_null",
                retryCount = request.retryCount,
                rescanReason = request.reason,
                detail = rootSelection.detail
            )
            scheduleOpeningAdRetry(
                packageName = request.packageName,
                activeRules = request.activeRules,
                baseEventContext = eventContext,
                reason = "root_window_null",
                retriesPerformed = request.retryCount
            )
            return
        }

        if (ActiveTextInputGuard.hasFocusedEditableInput(root)) {
            SettingsRepository.setLastFailureReason(this, "active_text_input", forcePersist = true)
            logEvent(
                stage = ClickLogStage.SkippedBySafety,
                eventContext = eventContext,
                failureReason = "active_text_input",
                blockedReason = "active_text_input",
                retryCount = request.retryCount,
                rescanReason = request.reason
            )
            terminateOpeningAdRecovery()
            return
        }

        val activityScopedRules = NodeScanner.filterRulesForActivity(
            request.activeRules,
            eventContext.activityName
        )
        if (activityScopedRules.isEmpty()) {
            terminateOpeningAdRecovery()
            return
        }
        val appElapsedMs = eventContext.elapsedSinceForegroundMs ?: 0L
        val scanStartedAt = SystemClock.elapsedRealtime()
        val scan = NodeScanner.scan(
            root = root,
            rules = request.activeRules,
            appElapsedMs = appElapsedMs,
            currentActivityName = eventContext.activityName
        )
        val scanDurationMs = (SystemClock.elapsedRealtime() - scanStartedAt).coerceAtLeast(0L)
        logScan(
            scan = scan,
            eventContext = eventContext,
            scanDurationMs = scanDurationMs,
            retryCount = request.retryCount,
            rescanReason = request.reason
        )
        val match = scan.bestMatch
        if (match != null) {
            startClickFromMatch(
                currentPackage = currentPackage,
                match = match,
                activeRules = request.activeRules,
                eventContext = eventContext,
                scan = scan,
                retryCount = request.retryCount,
                scanDurationMs = scanDurationMs,
                rescanReason = request.reason
            )
            return
        }

        when (
            val coordinateResult = CoordinateFallbackMatcher.findResult(
                root = root,
                rules = activityScopedRules,
                packageName = currentPackage,
                selfPackageName = packageName,
                elapsedSinceForegroundMs = appElapsedMs,
                screenWidth = resources.displayMetrics.widthPixels,
                screenHeight = resources.displayMetrics.heightPixels
            )
        ) {
            is CoordinateFallbackMatchResult.Matched -> {
                val fallback = coordinateResult.match
                val signature = "$currentPackage:${fallback.rule.id}:coordinate:${fallback.x}:${fallback.y}"
                startStableCoordinateFallback(
                    packageName = currentPackage,
                    fallback = fallback,
                    activeRules = activityScopedRules,
                    signature = signature,
                    eventContext = eventContext,
                    scan = scan,
                    retryCount = request.retryCount,
                    scanDurationMs = scanDurationMs,
                    rescanReason = request.reason
                )
                return
            }

            is CoordinateFallbackMatchResult.Blocked -> {
                logCoordinateFallbackBlocked(eventContext, coordinateResult, scan)
                terminateOpeningAdRecovery()
                return
            }

            CoordinateFallbackMatchResult.NotApplicable -> Unit
        }

        logScanMiss(
            scan = scan,
            eventContext = eventContext,
            scanDurationMs = scanDurationMs,
            retryCount = request.retryCount,
            rescanReason = request.reason
        )
        if (scanMissStage(scan) == ClickLogStage.SkippedBySafety) {
            terminateOpeningAdRecovery()
            return
        }
        val nextScheduled = scheduleOpeningAdRetry(
            packageName = request.packageName,
            activeRules = request.activeRules,
            baseEventContext = eventContext,
            reason = scan.failureReason,
            retriesPerformed = request.retryCount
        ) != null
        if (!nextScheduled) cancelScheduledOpeningAdRetry()
    }

    private fun isSameOpeningAdSession(key: String, packageName: String): Boolean {
        return foregroundState.currentForegroundPackage == packageName &&
            key == "$packageName:${foregroundState.foregroundStartTimeMillis}"
    }

    private fun currentOpeningAdSessionKey(): String? {
        val packageName = foregroundState.currentForegroundPackage.orEmpty()
        val startedAt = foregroundState.foregroundStartTimeMillis
        if (packageName.isBlank() || startedAt <= 0L) return null
        return "$packageName:$startedAt"
    }

    private fun pendingSessionKey(pending: PendingClick): String? {
        return pending.eventContext.foregroundStartTimeMillis
            ?.takeIf { it > 0L }
            ?.let { startedAt -> "${pending.packageName}:$startedAt" }
    }

    private fun hasActivityChanged(expectedActivityName: String): Boolean {
        val expected = expectedActivityName.trim()
        val current = currentActivityName.trim()
        return expected.isNotBlank() &&
            currentActivityIdentityKnown &&
            current.isNotBlank() &&
            !expected.equals(current, ignoreCase = true)
    }

    private fun cancelScheduledOpeningAdRetry() {
        openingAdRetryGeneration++
        openingAdRetryScheduled = false
    }

    private fun cancelOpeningAdRecovery(resetRetrySession: Boolean = false) {
        openingAdRescanKey = null
        cancelScheduledOpeningAdRetry()
        if (resetRetrySession) {
            openingAdRetrySessionKey = null
            openingAdRetryCount = 0
            openingAdTerminalSessionKey = null
        }
    }

    private fun terminateOpeningAdRecovery(sessionKey: String? = currentOpeningAdSessionKey()) {
        openingAdTerminalSessionKey = sessionKey
        cancelOpeningAdRecovery()
    }

    private fun startClickFromMatch(
        currentPackage: String,
        match: MatchResult,
        activeRules: List<SkipRule>,
        eventContext: EventContext,
        scan: ScanReport,
        retryCount: Int = 0,
        scanDurationMs: Long? = null,
        rescanReason: String = ""
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
            eventContext = eventContext,
            retryCount = retryCount,
            scanDurationMs = scanDurationMs,
            rescanReason = rescanReason
        )
        val highRiskDecision = highRiskDecisionForPending(pendingMatch)
        if (!highRiskDecision.allowed) {
            logBlockedByHighRiskPolicy(
                eventContext = eventContext,
                pending = pendingMatch,
                decision = highRiskDecision,
                scan = scan
            )
            terminateOpeningAdRecovery(pendingSessionKey(pendingMatch))
            return true
        }

        logMatch(
            match = match,
            eventContext = eventContext,
            scan = scan,
            retryCount = retryCount,
            scanDurationMs = scanDurationMs,
            rescanReason = rescanReason
        )
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
            SettingsRepository.setLastFailureReason(
                this,
                "click_skipped_by_safety_mode",
                forcePersist = true
            )
            terminateOpeningAdRecovery(pendingSessionKey(pendingMatch))
            return true
        }

        startStableClick(
            packageName = currentPackage,
            match = match,
            activeRules = activeRules,
            signature = signature,
            eventContext = eventContext,
            retryCount = retryCount,
            scanDurationMs = scanDurationMs,
            rescanReason = rescanReason
        )
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
        eventContext: EventContext,
        retryCount: Int = 0,
        scanDurationMs: Long? = null,
        rescanReason: String = ""
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
            delayBeforeClickMs = delayMs,
            retryCount = retryCount,
            scanDurationMs = scanDurationMs,
            rescanReason = rescanReason
        )
        cancelScheduledOpeningAdRetry()
        pendingClick = pending
        mainHandler.postDelayed({ relocateAndClick(pending) }, delayMs)
    }

    private fun startStableCoordinateFallback(
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
        val rule = fallback.rule
        val pending = ClickFlowStateMachine.startCoordinateFallback(
            packageName = packageName,
            appName = displayAppName(packageName, rule.appName),
            fallback = fallback,
            activeRules = activeRules,
            signature = signature,
            delayBeforeClickMs = STABLE_CLICK_DELAY_MS,
            eventContext = eventContext,
            retryCount = retryCount,
            scanDurationMs = scanDurationMs,
            rescanReason = rescanReason
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
            terminateOpeningAdRecovery(pendingSessionKey(pending))
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
            SettingsRepository.setLastFailureReason(
                this,
                "click_skipped_by_safety_mode",
                forcePersist = true
            )
            terminateOpeningAdRecovery(pendingSessionKey(pending))
            return
        }

        cancelScheduledOpeningAdRetry()
        pendingClick = pending
        mainHandler.postDelayed({ runCoordinateFallback(pending) }, STABLE_CLICK_DELAY_MS)
    }

    private fun logCoordinateFallbackBlocked(
        eventContext: EventContext,
        result: CoordinateFallbackMatchResult.Blocked,
        scan: ScanReport? = null
    ) {
        SettingsRepository.setLastFailureReason(this, result.reason, forcePersist = true)
        val key = "${eventContext.packageName}:${result.rule.id}:${result.reason}"
        val now = System.currentTimeMillis()
        val previous = lastCoordinateFallbackBlockedAt[key] ?: 0L
        if (now - previous < COORDINATE_BLOCKED_LOG_INTERVAL_MS) return
        lastCoordinateFallbackBlockedAt[key] = now

        logEvent(
            stage = ClickLogStage.SkippedBySafety,
            eventContext = eventContext,
            ruleType = result.rule.source.toClickLogType(),
            ruleName = result.rule.name,
            ruleId = result.rule.id,
            reason = result.reason,
            failureReason = result.reason,
            candidateCount = scan?.candidateCount,
            bestCandidateScore = scan?.bestCandidateScore,
            bestCandidateBounds = scan?.bestCandidateBounds.orEmpty(),
            minScore = result.rule.minScore,
            blockedReason = result.reason,
            clickTargetSource = ClickTargetSourceLog.CoordinateFallback
        )
    }

    private fun relocateAndClick(pending: PendingClick) {
        if (abortPendingIfAutomationPaused(pending)) return
        if (pendingClick !== pending) return
        val callbackPending = ClickFlowStateMachine.recordCallbackTiming(pending)
        pendingClick = callbackPending
        val root = rootInActiveWindow
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
            currentActivityName = currentActivityName,
            activityIdentityKnown = currentActivityIdentityKnown
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
        val elapsed = (System.currentTimeMillis() - foregroundState.foregroundStartTimeMillis).coerceAtLeast(0L)
        val currentRules = NodeScanner.filterRulesForActivity(
            rules = listOf(rule),
            currentActivityName = currentActivityName
        )
        val scanStartedAt = SystemClock.elapsedRealtime()
        val scan = NodeScanner.scan(root ?: return, currentRules, elapsed, currentActivityName)
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
        lastClickSignature = updated.signature

        logEvent(
            stage = ClickLogStage.ClickAttempted,
            eventContext = updated.eventContext,
            pending = updated,
            clickMethod = ClickMethodLog.ActionClick,
            delayBeforeClickMs = updated.delayBeforeClickMs
        )

        val attempt = ClickExecutor.click(match.clickNode)
        if (attempt.accepted) {
            val dispatched = updated.copy(firstAttempt = attempt, clickDispatched = true)
            pendingClick = dispatched
            logEvent(
                stage = ClickLogStage.ClickActionSuccess,
                eventContext = dispatched.eventContext,
                pending = dispatched,
                clickMethod = attempt.method,
                actionReturnValue = true,
                clickResult = true,
                delayBeforeClickMs = dispatched.delayBeforeClickMs
            )
            mainHandler.postDelayed(
                { verifyActionClick(dispatched) },
                CLICK_VERIFY_DELAY_MS
            )
        } else {
            runGestureFallback(updated.copy(firstAttempt = attempt))
        }
    }

    private fun runCoordinateFallback(pending: PendingClick) {
        if (abortPendingIfAutomationPaused(pending)) return
        if (pendingClick !== pending) return
        val callbackPending = ClickFlowStateMachine.recordCallbackTiming(pending)
        pendingClick = callbackPending
        val root = rootInActiveWindow
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
            foregroundPackage.orEmpty()
        }
        val elapsedSinceForegroundMs = (
            System.currentTimeMillis() - foregroundState.foregroundStartTimeMillis
            ).coerceAtLeast(0L)
        val revalidation = CoordinateFallbackMatcher.revalidateAtPoint(
            root = root,
            rule = rule,
            expectedPackageName = callbackPending.packageName,
            currentPackageName = currentPackageName,
            selfPackageName = packageName,
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
        lastRuleClickAt[updatedPending.ruleId] = System.currentTimeMillis()
        lastClickSignature = updatedPending.signature

        logEvent(
            stage = ClickLogStage.ClickAttempted,
            eventContext = updatedPending.eventContext,
            pending = updatedPending,
            clickMethod = ClickMethodLog.DispatchGesture,
            delayBeforeClickMs = updatedPending.delayBeforeClickMs,
            clickTargetSource = ClickTargetSourceLog.CoordinateFallback
        )

        val gestureQueued = ClickExecutor.gestureClickPoint(this, target, x, y) { attempt ->
            if (pendingClick?.signature != updatedPending.signature) return@gestureClickPoint
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
        if (gestureQueued && pendingClick?.signature == updatedPending.signature) {
            pendingClick = updatedPending.copy(clickDispatched = true)
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
        if (abortPendingIfAutomationPaused(pending)) return
        if (pendingClick?.signature != pending.signature) return
        val root = rootInActiveWindow
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
            currentActivityName = currentActivityName,
            activityIdentityKnown = currentActivityIdentityKnown
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
            foregroundPackage.orEmpty()
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
            val currentAppElapsedMs = (
                System.currentTimeMillis() - foregroundState.foregroundStartTimeMillis
                ).coerceAtLeast(0L)
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
            this,
            updated.candidate,
            allowLargeBounds = false
        ) { gestureAttempt ->
            if (pendingClick?.signature != updated.signature) return@gestureClick
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
            logEvent(
                stage = ClickLogStage.ClickActionSuccess,
                eventContext = dispatched.eventContext,
                pending = dispatched,
                clickMethod = gestureAttempt.method,
                actionReturnValue = true,
                clickResult = true,
                delayBeforeClickMs = dispatched.delayBeforeClickMs,
                clickTargetSource = ClickTargetSourceLog.GestureOnNodeCenter
            )
            mainHandler.postDelayed(
                {
                    if (pendingClick?.signature == dispatched.signature) {
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
        if (gestureQueued && pendingClick?.signature == updated.signature) {
            pendingClick = updated.copy(clickDispatched = true)
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

    private fun handlePendingEventFastPath(
        pending: PendingClick,
        eventContext: EventContext
    ) {
        val currentPackage = eventContext.packageName.trim()
        when (PendingEventFastPathPolicy.evaluate(
            clickDispatched = pending.clickDispatched,
            currentPackageKnown = currentPackage.isNotBlank(),
            samePackage = currentPackage == pending.packageName,
            isWithinRuleWindow = eventContext.isWithinDefaultRuleWindow,
            activityChanged = hasActivityChanged(pending.eventContext.activityName)
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

    private fun abortPendingIfAutomationPaused(pending: PendingClick): Boolean {
        if (SettingsRepository.isMasterEnabled(this)) return false
        if (pendingClick?.signature == pending.signature) {
            pendingClick = null
        }
        terminateOpeningAdRecovery(pendingSessionKey(pending))
        SettingsRepository.setLastFailureReason(this, "automation_paused", forcePersist = true)
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
        if (pendingClick?.signature != pending.signature) return false
        val root = rootInActiveWindow
        val rootPackageName = root?.packageName?.toString().orEmpty()
        val samePackage = foregroundPackage == pending.packageName &&
            (rootPackageName.isBlank() || rootPackageName == pending.packageName)
        val sameActivity = !hasActivityChanged(pending.eventContext.activityName)
        val activeTextInput = root != null && ActiveTextInputGuard.hasFocusedEditableInput(root)
        val now = System.currentTimeMillis()
        val retryEventContext = buildEventContext(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            eventPackageName = pending.packageName,
            packageName = pending.packageName,
            activityName = currentActivityName.ifBlank { pending.eventContext.activityName },
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
        val nextRetryCount = scheduleOpeningAdRetry(
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

        lastClickSignature = null
        lastRuleClickAt.remove(pending.ruleId)
        SettingsRepository.setLastFailureReason(this, reason)
        logEvent(
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
        if (pendingClick?.signature != pending.signature) return
        pendingClick = null
        terminateOpeningAdRecovery(pendingSessionKey(pending))
        val now = System.currentTimeMillis()
        if (success) {
            SettingsRepository.flushRuntimeDiagnostics(this, now)
            SettingsRepository.markLastClick(this, now)
            showSuccessToast("已跳过：${pending.appName}", "success:${pending.packageName}")
        } else {
            SettingsRepository.setLastFailureReason(this, reason, forcePersist = true)
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
            activityName = currentActivityName,
            windowId = root?.windowId ?: pending.eventContext.windowId,
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
            currentActivityName = currentActivityName,
            activityIdentityKnown = currentActivityIdentityKnown
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

    private fun logScan(
        scan: ScanReport,
        eventContext: EventContext,
        scanDurationMs: Long? = null,
        retryCount: Int = 0,
        rescanReason: String = ""
    ) {
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
                retryCount = retryCount,
                scanDurationMs = scanDurationMs,
                rescanReason = rescanReason,
                defaultRuleAreaAllowed = scan.defaultRuleAreaAllowed,
                textKeywordIsStandaloneSkip = scan.textKeywordIsStandaloneSkip,
                candidateAreaRatio = scan.candidateAreaRatio,
                isLargeCandidateBounds = scan.isLargeCandidateBounds,
                clickTargetSource = scan.clickTargetSource
            )
        }
    }

    private fun logScanMiss(
        scan: ScanReport,
        eventContext: EventContext,
        scanDurationMs: Long?,
        retryCount: Int,
        rescanReason: String
    ) {
        val stage = scanMissStage(scan)
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
            blockedReason = if (stage == ClickLogStage.SkippedBySafety) scan.failureReason else "",
            retryCount = retryCount,
            scanDurationMs = scanDurationMs,
            rescanReason = rescanReason
        )
        SettingsRepository.setLastFailureReason(
            this,
            scan.failureReason,
            forcePersist = stage == ClickLogStage.SkippedBySafety
        )
    }

    private fun scanMissStage(scan: ScanReport): ClickLogStage {
        return when {
            scan.failureReason == HighRiskClickPolicy.BLOCKED_REASON -> ClickLogStage.SkippedBySafety
            scan.failureReason == "text_input_clear_button" -> ClickLogStage.SkippedBySafety
            scan.failureReason == "activity_scope_mismatch" -> ClickLogStage.SkippedBySafety
            scan.failureReason == ScoreEvaluator.GENERIC_CLOSE_BLOCKED_REASON -> ClickLogStage.SkippedBySafety
            scan.candidateCount == 0 -> ClickLogStage.NoCandidateFound
            scan.failureReason == "score_below_min_score" ||
                scan.failureReason == "candidate_below_threshold" -> ClickLogStage.SkippedByLowScore
            else -> ClickLogStage.SkippedBySafety
        }
    }

    private fun logMatch(
        match: MatchResult,
        eventContext: EventContext,
        scan: ScanReport,
        retryCount: Int = 0,
        scanDurationMs: Long? = null,
        rescanReason: String = ""
    ) {
        logEvent(
            stage = ClickLogStage.RuleMatched,
            eventContext = eventContext,
            pending = ClickFlowStateMachine.previewFromMatch(
                packageName = eventContext.packageName,
                appName = match.appName,
                match = ClickMatchSnapshot.from(match),
                eventContext = eventContext,
                retryCount = retryCount,
                scanDurationMs = scanDurationMs,
                rescanReason = rescanReason
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
        SettingsRepository.setLastFailureReason(
            this,
            HighRiskClickPolicy.BLOCKED_REASON,
            forcePersist = true
        )
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
        terminateOpeningAdRecovery(pendingSessionKey(pending))
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
        private const val ABSOLUTE_RESCAN_REASON = "opening_ad_absolute_rescan"
        private const val CLICK_VERIFY_DELAY_MS = 360L
        private const val GESTURE_VERIFY_DELAY_MS = 460L
        private const val INPUT_METHOD_REFRESH_INTERVAL_MS = 5_000L
        private const val COORDINATE_BLOCKED_LOG_INTERVAL_MS = 2_000L
        private const val TOAST_COOLDOWN_MS = 60_000L
        private const val POPUP_DURATION_MS = 1600L
    }
}

private data class OpeningAdRetryRequest(
    val sessionKey: String,
    val packageName: String,
    val expectedActivityName: String,
    val activeRules: List<SkipRule>,
    val defaultRuleWindowMs: Long,
    val retryCount: Int,
    val reason: String
)

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

internal enum class PendingEventFastPathDecision {
    AwaitClickVerification,
    WaitForKnownPackage,
    CancelPackageChanged,
    CancelTimeWindowExpired,
    CancelActivityChanged,
    Continue
}

internal object PendingEventFastPathPolicy {
    fun evaluate(
        clickDispatched: Boolean,
        currentPackageKnown: Boolean,
        samePackage: Boolean,
        isWithinRuleWindow: Boolean,
        activityChanged: Boolean
    ): PendingEventFastPathDecision {
        if (clickDispatched) return PendingEventFastPathDecision.AwaitClickVerification
        if (!currentPackageKnown) return PendingEventFastPathDecision.WaitForKnownPackage
        if (!samePackage) return PendingEventFastPathDecision.CancelPackageChanged
        if (!isWithinRuleWindow) return PendingEventFastPathDecision.CancelTimeWindowExpired
        if (activityChanged) return PendingEventFastPathDecision.CancelActivityChanged
        return PendingEventFastPathDecision.Continue
    }
}

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
        rootWindowNull: Boolean,
        activeTextInput: Boolean = false
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
        if (activeTextInput) {
            return DelayedClickSafetyResult(
                allowed = false,
                stage = ClickLogStage.SkippedBySafety,
                reason = "active_text_input",
                blockedReason = "active_text_input_before_delayed_click",
                detail = "$detail;activeTextInput=true"
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
