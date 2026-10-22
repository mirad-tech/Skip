package com.example.skip.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.skip.data.LogRepository
import com.example.skip.data.RuleRepository
import com.example.skip.data.SettingsRepository
import com.example.skip.engine.CoordinateFallbackMatcher
import com.example.skip.engine.CoordinateFallbackMatchResult
import com.example.skip.engine.HighRiskClickDecision
import com.example.skip.engine.HighRiskClickPolicy
import com.example.skip.engine.NodeScanBudget
import com.example.skip.engine.NodeScanner
import com.example.skip.engine.NodeTraversalResult
import com.example.skip.engine.OpeningAdWindowPolicy
import com.example.skip.engine.RulePlanProvider
import com.example.skip.engine.SafetyGuard
import com.example.skip.engine.ScanMissClassifier
import com.example.skip.engine.ScoreEvaluator
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.MatchResult
import com.example.skip.model.ScanReport
import com.example.skip.model.SkipRule
import com.example.skip.util.AccessibilityNodeAccess
import com.example.skip.util.InstalledAppUtils

class SkipAccessibilityService : AccessibilityService() {
    private var foregroundState = ForegroundWindowState()
    internal val lastRuleClickAt = mutableMapOf<String, Long>()
    internal var lastClickSignature: String? = null
    internal val mainHandler = Handler(Looper.getMainLooper())
    private val windowResolver by lazy(LazyThreadSafetyMode.NONE) {
        AccessibilityWindowResolver(
            selfPackageName = packageName,
            activeRootProvider = { AccessibilityNodeAccess.freshActiveRoot(this) },
            interactiveWindowsProvider = { windows }
        )
    }
    internal val feedbackController by lazy(LazyThreadSafetyMode.NONE) {
        ServiceFeedbackController(this, mainHandler)
    }
    internal val eventLogger by lazy(LazyThreadSafetyMode.NONE) {
        ServiceEventLogger(this)
    }
    private val inputMethodWindowController by lazy(LazyThreadSafetyMode.NONE) {
        InputMethodWindowController(this, mainHandler)
    }
    private val pendingClickCoordinator by lazy(LazyThreadSafetyMode.NONE) {
        PendingClickCoordinator(this)
    }
    private val openingAdRecoveryState = OpeningAdRecoveryState()
    private var lastWindowStateChangedAt = 0L
    internal var currentActivityName: String = ""
    internal var currentActivityIdentityKnown = false
    private val lastCoordinateFallbackBlockedAt = mutableMapOf<String, Long>()
    private var lastTreeWalkElapsedRealtime = 0L
    private var lastEffectiveRuleWindowMs = OpeningAdWindowPolicy.MAX_WINDOW_MS
    private var loggedTimeWindowExpiryKey: String? = null
    private var loggedAutomationPaused = false
    private var masterWasEnabled = false
    private var openingAdExpiry: Runnable? = null
    private val openingAdWorkToken = Any()

    internal val foregroundPackage: String?
        get() = foregroundState.currentForegroundPackage

    internal val foregroundStateSnapshot: ForegroundWindowState
        get() = foregroundState

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setCacheEnabled(false)
        }
        inputMethodWindowController.refreshEnabledInputMethodPackages()
        inputMethodWindowController.registerSettingsObserver()
        SettingsRepository.markServiceConnected(this)
        RuleRepository.disableRulesForPackage(this, packageName)
        LogRepository.start(applicationContext)
        masterWasEnabled = SettingsRepository.isMasterEnabled(this)
        resetForegroundBaseline(windowResolver.observeForeground())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !event.isSupportedEvent()) return
        val masterEnabled = SettingsRepository.isMasterEnabled(this)
        expireOpeningAdSessionIfNeeded()
        val elapsedSinceForegroundMs = foregroundState.elapsedSinceStart(SystemClock.elapsedRealtime())
        val work = AccessibilityEventWorkPolicy.decide(
            eventType = event.eventType,
            elapsedSinceForegroundMs = elapsedSinceForegroundMs,
            ruleWindowMs = lastEffectiveRuleWindowMs,
            lastTreeWalkElapsedRealtime = lastTreeWalkElapsedRealtime,
            nowElapsedRealtime = SystemClock.elapsedRealtime(),
            hasPendingClick = pendingClickCoordinator.pendingClick != null,
            hasActiveSession = foregroundState.sessionKey != null &&
                !foregroundState.detectionFinished && foregroundState.isForegroundVisible,
            masterStateChanged = masterEnabled != masterWasEnabled
        )
        when (work) {
            AccessibilityEventWork.Drop -> return
            AccessibilityEventWork.SkipTreeRecordExpiry -> {
                recordRuleWindowExpiryIfNeeded()
                return
            }
            AccessibilityEventWork.ProcessFully -> {
                AccessibilityNodeAccess.withCacheBoundary(this) {
                    processAccessibilityEvent(event, masterEnabled)
                }
            }
        }
    }

    private fun processAccessibilityEvent(event: AccessibilityEvent, masterEnabled: Boolean) {

        val now = System.currentTimeMillis()
        val windowStateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowStateChangedAt = now
        }
        SettingsRepository.markServiceActive(this)
        val masterStateChanged = masterEnabled != masterWasEnabled
        val foregroundObservation = windowResolver.observeForeground(
            event.packageName?.toString().orEmpty(),
            event.className?.toString().orEmpty(),
            event.windowId
        )
        if (masterStateChanged) {
            masterWasEnabled = masterEnabled
            openingAdExpiry?.let(mainHandler::removeCallbacks)
            openingAdExpiry = null
            pendingClickCoordinator.clear()
            cancelOpeningAdRecovery(resetRetrySession = true)
        }
        // Track foreground changes while paused, and preserve a real switch on re-enable.
        updateForegroundWindow(foregroundObservation, now, windowStateChanged, masterStateChanged)
        if (!masterEnabled) {
            currentActivityName = ""
            currentActivityIdentityKnown = false
            pendingClickCoordinator.clear()
            terminateOpeningAdRecovery()
            SettingsRepository.setLastFailureReason(this, "automation_paused", forcePersist = true)
            // Log once per pause so diagnostics can show why nothing was processed, without
            // paying for an event-context build on every event while the master switch is off.
            if (!loggedAutomationPaused) {
                loggedAutomationPaused = true
                eventLogger.logEvent(
                    stage = ClickLogStage.SkippedByDisabledSetting,
                    eventContext = buildEventContext(
                        eventType = event.eventType,
                        eventPackageName = event.packageName?.toString().orEmpty(),
                        packageName = "",
                        activityName = "",
                        windowId = event.windowId,
                        root = null,
                        now = now,
                        defaultRuleWindowMs = RuleRepository.DEFAULT_RULE_WINDOW_MS
                    ),
                    failureReason = "automation_paused"
                )
            }
            return
        }
        loggedAutomationPaused = false

        val eventPackageName = event.packageName?.toString().orEmpty()
        val rootSelection = windowResolver.selectRootForEvent(eventPackageName)
        val root = rootSelection.root
        val rootPackageName = root?.packageName?.toString().orEmpty()
        val packageResolution = EventWindowTracker.resolveTrustedPackage(
            eventPackageName = eventPackageName,
            rootPackageName = rootPackageName
        )
        val currentPackage = packageResolution.resolvedPackageName
        if (inputMethodWindowController.isInputMethodWindow(event, root, currentPackage)) {
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
            eventLogger.logEvent(
                stage = ClickLogStage.SkippedBySafety,
                eventContext = inputMethodContext,
                ruleType = "safety",
                failureReason = InputMethodWindowPolicy.BLOCKED_REASON,
                blockedReason = InputMethodWindowPolicy.BLOCKED_REASON
            )
            SettingsRepository.setLastFailureReason(this, InputMethodWindowPolicy.BLOCKED_REASON)
            pendingClickCoordinator.clear()
            terminateOpeningAdRecovery()
            return
        }
        val observedActivityName = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString().orEmpty()
        } else {
            ""
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventPackageName == foregroundState.currentForegroundPackage &&
            foregroundState.isForegroundVisible
        ) {
            currentActivityIdentityKnown = observedActivityName.isNotBlank()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            currentActivityIdentityKnown = false
        }
        if (observedActivityName.isNotBlank() && eventPackageName == foregroundState.currentForegroundPackage &&
            foregroundState.isForegroundVisible
        ) {
            currentActivityName = observedActivityName
        }
        val activityName = currentActivityName

        pendingClickCoordinator.pendingClick?.let { pending ->
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
            pendingClickCoordinator.handlePendingEventFastPath(pending, pendingEventContext)
            return
        }

        if (foregroundState.detectionFinished || !foregroundState.isForegroundVisible ||
            currentPackage != foregroundState.currentForegroundPackage
        ) return

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

        eventLogger.logEvent(
            stage = ClickLogStage.ServiceEventReceived,
            eventContext = eventContext,
            detail = joinDetails(packageResolution.detail, rootSelection.detail)
        )

        if (currentPackage.isBlank()) {
            eventLogger.logEvent(
                stage = ClickLogStage.EventPackageNull,
                eventContext = eventContext,
                failureReason = "event_package_null"
            )
            SettingsRepository.setLastFailureReason(this, "event_package_null")
            return
        }

        if (SafetyGuard.isSelfPackage(this, currentPackage)) {
            markTreeWorkIfContentRead(event.eventType)
            eventLogger.logEvent(
                stage = ClickLogStage.SkippedSelfPackage,
                eventContext = eventContext,
                failureReason = "skipped_self_package"
            )
            return
        }

        if (SafetyGuard.isProtectedPackage(currentPackage)) {
            val ownLabelOnLauncher = root?.containsOwnAppLabel(currentPackage) == true
            markTreeWorkIfContentRead(event.eventType)
            val blockedReason = if (ownLabelOnLauncher) {
                "own_app_label_on_launcher"
            } else {
                SafetyGuard.protectedPackageReason(currentPackage)
                    .ifBlank { "system_or_launcher_package" }
            }
            eventLogger.logEvent(
                stage = ClickLogStage.SkippedBySafety,
                eventContext = eventContext,
                ruleType = "safety",
                failureReason = "safety_guard_blocked",
                blockedReason = blockedReason,
                isSelfAppLabelCandidate = ownLabelOnLauncher
            )
            SettingsRepository.setLastFailureReason(this, "safety_guard_blocked")
            feedbackController.showDebugToast("已跳过自动点击：安全保护应用", "safety:$currentPackage")
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
        lastEffectiveRuleWindowMs = ruleWindowMs
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
            eventLogger.logEvent(
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
            val expiryKey = currentOpeningAdSessionKey()
            if (loggedTimeWindowExpiryKey != expiryKey) {
                loggedTimeWindowExpiryKey = expiryKey
                eventLogger.logEvent(
                    stage = ClickLogStage.SkippedByTimeWindow,
                    eventContext = eventContext,
                    ruleType = if (customRules.isNotEmpty()) "custom" else "default",
                    ruleName = if (customRules.isNotEmpty()) "自定义规则" else "默认开屏跳过",
                    failureReason = failureReason,
                    ruleScope = windowExpiredScope
                )
            }
            SettingsRepository.setLastFailureReason(this, failureReason)
            terminateOpeningAdRecovery()
            return
        }

        if (rules.isEmpty()) {
            eventLogger.logEvent(
                stage = ClickLogStage.NoCandidateFound,
                eventContext = eventContext,
                failureReason = "no_enabled_rules"
            )
            SettingsRepository.setLastFailureReason(this, "no_enabled_rules")
            terminateOpeningAdRecovery()
            return
        }

        val activeRules = rules.filter { rule ->
            val last = lastRuleClickAt[rule.id] ?: 0L
            now - last >= rule.cooldownMs
        }
        if (activeRules.isEmpty()) {
            eventLogger.logEvent(
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
            eventLogger.logEvent(
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
        if (!isSessionOpen(eventContext.openingAdSessionKey, currentPackage, ruleWindowMs)) return
        if (ActiveTextInputGuard.hasFocusedEditableInput(root)) {
            if (!isSessionOpen(eventContext.openingAdSessionKey, currentPackage, ruleWindowMs)) return
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

        if (!isSessionOpen(eventContext.openingAdSessionKey, currentPackage, ruleWindowMs)) return
        val appElapsedMs = foregroundState.elapsedSinceStart(SystemClock.elapsedRealtime()) ?: return
        val activeActivityScopedRules = NodeScanner.filterRulesForActivity(activeRules, eventContext.activityName)
        val scanStartedAt = SystemClock.elapsedRealtime()
        val scan = NodeScanner.scan(root, activeRules, appElapsedMs, eventContext.activityName)
        markTreeWalk()
        val scanDurationMs = (SystemClock.elapsedRealtime() - scanStartedAt).coerceAtLeast(0L)
        logScan(scan, eventContext, scanDurationMs = scanDurationMs)
        val match = scan.bestMatch
        if (match == null) {
            if (!isSessionOpen(eventContext.openingAdSessionKey, currentPackage, ruleWindowMs)) return
            when (
                val coordinateResult = CoordinateFallbackMatcher.findResult(
                root = root,
                rules = activeActivityScopedRules,
                packageName = currentPackage,
                selfPackageName = packageName,
                elapsedSinceForegroundMs = foregroundState.elapsedSinceStart(SystemClock.elapsedRealtime()) ?: return,
                screenWidth = resources.displayMetrics.widthPixels,
                screenHeight = resources.displayMetrics.heightPixels
                )
            ) {
                is CoordinateFallbackMatchResult.Matched -> {
                    val fallback = coordinateResult.match
                    val signature = "$currentPackage:${fallback.rule.id}:coordinate:${fallback.x}:${fallback.y}"
                    val lastAnyRuleClick = lastRuleClickAt.values.maxOrNull() ?: 0L
                    if (signature == lastClickSignature && now - lastAnyRuleClick < REPEAT_CLICK_GUARD_MS) return
                    pendingClickCoordinator.startStableCoordinateFallback(
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
            eventLogger.logEvent(
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
        AccessibilityNodeAccess.clearCache(this)
        pendingClickCoordinator.clear()
        cancelOpeningAdRecovery(resetRetrySession = true)
        resetForegroundBaseline(windowResolver.observeForeground())
        LogRepository.flushPendingWritesAsync(applicationContext)
        SettingsRepository.markServiceInterrupted(this)
        feedbackController.hidePopup()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        AccessibilityNodeAccess.clearCache(this)
        pendingClickCoordinator.clear()
        cancelOpeningAdRecovery(resetRetrySession = true)
        inputMethodWindowController.unregisterSettingsObserver()
        SettingsRepository.flushRuntimeDiagnostics(this)
        LogRepository.flushPendingWritesAsync(applicationContext)
        feedbackController.hidePopup()
        super.onDestroy()
    }

    private fun updateForegroundWindow(
        observation: ForegroundObservation,
        now: Long,
        windowStateChanged: Boolean,
        masterStateChanged: Boolean = false
    ) {
        val previous = foregroundState
        foregroundState = EventWindowTracker.updateForegroundWindow(
            state = foregroundState,
            observation = observation,
            now = now,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            windowStateChanged = windowStateChanged,
            masterEnabled = masterWasEnabled,
            masterStateChanged = masterStateChanged
        )
        if (foregroundState.sessionId != previous.sessionId) {
            pendingClickCoordinator.clear()
            lastClickSignature = null
            cancelOpeningAdRecovery(resetRetrySession = true)
            currentActivityName = ""
            currentActivityIdentityKnown = false
            lastTreeWalkElapsedRealtime = 0L
            lastEffectiveRuleWindowMs = OpeningAdWindowPolicy.MAX_WINDOW_MS
            loggedTimeWindowExpiryKey = null
            scheduleOpeningAdExpiry()
        }
    }

    private fun resetForegroundBaseline(observation: ForegroundObservation) {
        openingAdExpiry?.let(mainHandler::removeCallbacks)
        openingAdExpiry = null
        pendingClickCoordinator.clear()
        cancelOpeningAdRecovery(resetRetrySession = true)
        foregroundState = ForegroundWindowState(sessionId = foregroundState.sessionId + 1L)
        updateForegroundWindow(observation, System.currentTimeMillis(), windowStateChanged = false)
    }

    private fun scheduleOpeningAdExpiry() {
        openingAdExpiry?.let(mainHandler::removeCallbacks)
        openingAdExpiry = null
        val key = currentOpeningAdSessionKey() ?: return
        if (foregroundState.detectionFinished) return
        val remaining = OpeningAdWindowPolicy.MAX_WINDOW_MS -
            (foregroundState.elapsedSinceStart(SystemClock.elapsedRealtime()) ?: return)
        val expiry = Runnable {
            if (currentOpeningAdSessionKey() == key) expireOpeningAdSessionIfNeeded()
        }
        openingAdExpiry = expiry
        mainHandler.postDelayed(expiry, remaining.coerceAtLeast(0L))
    }

    private fun expireOpeningAdSessionIfNeeded() {
        if (foregroundState.detectionFinished) return
        val elapsed = foregroundState.elapsedSinceStart(SystemClock.elapsedRealtime()) ?: return
        if (elapsed < OpeningAdWindowPolicy.MAX_WINDOW_MS) return
        recordRuleWindowExpiryIfNeeded()
        val key = currentOpeningAdSessionKey() ?: return
        finishOpeningAdSession(key)
        pendingClickCoordinator.cancelUnsentClick(key)
    }

    internal fun finishOpeningAdSession(sessionKey: String?) {
        if (sessionKey == null || sessionKey != currentOpeningAdSessionKey()) return
        foregroundState = EventWindowTracker.finishSession(foregroundState, sessionKey)
        openingAdExpiry?.let(mainHandler::removeCallbacks)
        openingAdExpiry = null
        terminateOpeningAdRecovery(sessionKey)
    }

    internal fun refreshForegroundForClick() {
        updateForegroundWindow(windowResolver.observeForeground(), System.currentTimeMillis(), windowStateChanged = false)
    }

    internal fun isSessionOpen(sessionKey: String?, packageName: String, windowMs: Long): Boolean {
        return EventWindowTracker.isSessionOpen(
            foregroundState, sessionKey, packageName, SystemClock.elapsedRealtime(), windowMs
        )
    }

    private fun joinDetails(vararg details: String): String {
        return details.filter { it.isNotBlank() }.joinToString(";")
    }

    internal fun displayAppName(packageName: String, configuredAppName: String): String {
        return AppDisplayNamePolicy.displayName(
            configuredAppName = configuredAppName,
            packageName = packageName,
            resolveLabel = { InstalledAppUtils.getAppLabel(this, it) }
        )
    }

    private fun recordRuleWindowExpiryIfNeeded() {
        val packageName = foregroundState.currentForegroundPackage.orEmpty()
        if (packageName.isBlank()) return
        val expiryKey = currentOpeningAdSessionKey() ?: return
        if (loggedTimeWindowExpiryKey == expiryKey) return
        loggedTimeWindowExpiryKey = expiryKey
        val eventContext = buildEventContext(
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            eventPackageName = packageName,
            packageName = packageName,
            activityName = currentActivityName,
            windowId = -1,
            root = null,
            now = System.currentTimeMillis(),
            defaultRuleWindowMs = lastEffectiveRuleWindowMs
        )
        eventLogger.logEvent(
            stage = ClickLogStage.SkippedByTimeWindow,
            eventContext = eventContext,
            ruleType = "default",
            ruleName = "默认开屏跳过",
            failureReason = "default_rule_window_expired",
            ruleScope = "expired_without_scan"
        )
        SettingsRepository.setLastFailureReason(this, "default_rule_window_expired")
        terminateOpeningAdRecovery()
    }

    internal fun markTreeWalk() {
        lastTreeWalkElapsedRealtime = SystemClock.elapsedRealtime()
    }

    /**
     * Advances the content-scan throttle for the branches that return before the node scan.
     *
     * Without this, `lastTreeWalkElapsedRealtime` stays at 0 while a launcher (or Skip itself) is in
     * the foreground, so `AccessibilityEventWorkPolicy` can never throttle
     * `TYPE_WINDOW_CONTENT_CHANGED`. The protected-package branch then pays for the bounded
     * `containsOwnAppLabel` tree walk on every single content event. (The self-package branch reads
     * no tree; it marks for symmetry so a future tree read there cannot reintroduce the same bug.)
     *
     * Known, accepted trade-off: the throttle timestamp is global and is consulted before the
     * package is resolved, so up to 300 ms of content events from *any* package are dropped after
     * one of these branches runs. That window is bounded and a foreground transition normally
     * arrives as `TYPE_WINDOW_STATE_CHANGED`, which is never throttled and clears the mark.
     */
    private fun markTreeWorkIfContentRead(eventType: Int) {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) markTreeWalk()
    }

    private fun postAccessibilityTask(action: () -> Unit, delayMs: Long) {
        mainHandler.postDelayed(
            { AccessibilityNodeAccess.withCacheBoundary(this, action) },
            openingAdWorkToken,
            delayMs
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
                hasPendingClick = pendingClickCoordinator.pendingClick != null,
                hasActiveRules = activeRules.isNotEmpty()
            )
        ) {
            return
        }
        val key = baseEventContext.openingAdSessionKey ?: return
        if (!isSessionOpen(key, packageName, baseEventContext.defaultRuleWindowMs)) return
        if (!OpeningAdRecoveryGate.canRun(
                masterEnabled = isOpeningAdRecoveryAllowed(),
                sessionKey = key,
                terminalSessionKey = openingAdRecoveryState.terminalSessionKey
            )
        ) return
        if (openingAdRecoveryState.rescanKey == key) return
        val startedAt = baseEventContext.foregroundStartElapsedRealtimeMs ?: return
        val delaysMs = OpeningAdRescanPolicy.remainingDelaysMs(
            foregroundStartElapsedRealtimeMs = startedAt,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            ruleWindowMs = baseEventContext.defaultRuleWindowMs
        )
        if (delaysMs.isEmpty()) return
        openingAdRecoveryState.markRescansScheduled(key)
        val expectedActivityName = baseEventContext.activityName
        delaysMs.forEach { delayMs ->
            postAccessibilityTask(
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
        if (!isSameOpeningAdSession(key, packageName)) return
        refreshForegroundForClick()
        if (!isSessionOpen(key, packageName, defaultRuleWindowMs)) return
        if (!OpeningAdRecoveryGate.canRun(
                masterEnabled = isOpeningAdRecoveryAllowed(),
                sessionKey = key,
                terminalSessionKey = openingAdRecoveryState.terminalSessionKey
            )
        ) {
            terminateOpeningAdRecovery()
            return
        }
        if (openingAdRecoveryState.rescanKey != key || pendingClickCoordinator.pendingClick != null) return
        if (!isSameOpeningAdSession(key, packageName) || hasActivityChanged(expectedActivityName)) {
            terminateOpeningAdRecovery(key)
            return
        }

        val now = System.currentTimeMillis()
        val rootSelection = windowResolver.selectRootForPackage(packageName)
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
            eventLogger.logEvent(
                stage = ClickLogStage.RootWindowNull,
                eventContext = eventContext,
                failureReason = "root_window_null",
                retryCount = openingAdRecoveryState.retryCount,
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
                    retriesPerformed = openingAdRecoveryState.retryCount
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
            eventLogger.logEvent(
                stage = ClickLogStage.SkippedBySafety,
                eventContext = eventContext,
                failureReason = "active_text_input",
                blockedReason = "active_text_input",
                retryCount = openingAdRecoveryState.retryCount,
                rescanReason = ABSOLUTE_RESCAN_REASON
            )
            terminateOpeningAdRecovery()
            return
        }

        if (!isSessionOpen(key, currentPackage, ruleWindowMs)) return
        val appElapsedMs = foregroundState.elapsedSinceStart(SystemClock.elapsedRealtime()) ?: return
        val scanStartedAt = SystemClock.elapsedRealtime()
        val scan = NodeScanner.scan(root, activeRules, appElapsedMs, eventContext.activityName)
        val scanDurationMs = (SystemClock.elapsedRealtime() - scanStartedAt).coerceAtLeast(0L)
        logScan(
            scan = scan,
            eventContext = eventContext,
            scanDurationMs = scanDurationMs,
            retryCount = openingAdRecoveryState.retryCount,
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
                retryCount = openingAdRecoveryState.retryCount,
                scanDurationMs = scanDurationMs,
                rescanReason = ABSOLUTE_RESCAN_REASON
            )
            return
        }

        logScanMiss(
            scan = scan,
            eventContext = eventContext,
            scanDurationMs = scanDurationMs,
            retryCount = openingAdRecoveryState.retryCount,
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
            retriesPerformed = openingAdRecoveryState.retryCount
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

    internal fun scheduleOpeningAdRetry(
        packageName: String,
        activeRules: List<SkipRule>,
        baseEventContext: EventContext,
        reason: String,
        retriesPerformed: Int
    ): Int? {
        if (
            activeRules.isEmpty() ||
            pendingClickCoordinator.pendingClick != null ||
            openingAdRecoveryState.retryScheduled
        ) return null
        val startedAt = baseEventContext.foregroundStartElapsedRealtimeMs ?: return null
        val sessionKey = baseEventContext.openingAdSessionKey ?: return null
        if (!isSessionOpen(sessionKey, packageName, baseEventContext.defaultRuleWindowMs)) return null
        if (!OpeningAdRecoveryGate.canRun(
                masterEnabled = isOpeningAdRecoveryAllowed(),
                sessionKey = sessionKey,
                terminalSessionKey = openingAdRecoveryState.terminalSessionKey
            )
        ) {
            return null
        }
        if (!isSameOpeningAdSession(sessionKey, packageName) ||
            hasActivityChanged(baseEventContext.activityName)
        ) {
            return null
        }
        if (openingAdRecoveryState.retrySessionKey != sessionKey) {
            openingAdRecoveryState.beginRetrySession(sessionKey)
        }

        val performedCount = maxOf(retriesPerformed, openingAdRecoveryState.retryCount)
        if (!OpeningAdRetryPolicy.shouldRetry(
                reason = reason,
                retriesPerformed = performedCount,
                isWithinRuleWindow = baseEventContext.isWithinDefaultRuleWindow
            )
        ) {
            return null
        }
        val delayMs = OpeningAdRetryPolicy.nextDelayMs(performedCount) ?: return null
        val now = SystemClock.elapsedRealtime()
        val windowEndAt = startedAt + OpeningAdWindowPolicy.effectiveWindowMs(baseEventContext.defaultRuleWindowMs)
        if (now + delayMs >= windowEndAt) return null

        val nextRetryCount = performedCount + 1
        val generation = openingAdRecoveryState.scheduleRetry()
        val request = OpeningAdRetryRequest(
            sessionKey = sessionKey,
            packageName = packageName,
            expectedActivityName = baseEventContext.activityName,
            activeRules = activeRules,
            defaultRuleWindowMs = baseEventContext.defaultRuleWindowMs,
            retryCount = nextRetryCount,
            reason = reason
        )
        postAccessibilityTask(
            {
                if (!openingAdRecoveryState.acceptScheduledRetry(generation, nextRetryCount)) {
                    return@postAccessibilityTask
                }
                runOpeningAdRetry(request)
            },
            delayMs
        )
        return nextRetryCount
    }

    private fun runOpeningAdRetry(request: OpeningAdRetryRequest) {
        if (!isSameOpeningAdSession(request.sessionKey, request.packageName)) return
        refreshForegroundForClick()
        if (!isSessionOpen(request.sessionKey, request.packageName, request.defaultRuleWindowMs)) return
        if (!OpeningAdRecoveryGate.canRun(
                masterEnabled = isOpeningAdRecoveryAllowed(),
                sessionKey = request.sessionKey,
                terminalSessionKey = openingAdRecoveryState.terminalSessionKey
            )
        ) {
            terminateOpeningAdRecovery()
            return
        }
        if (pendingClickCoordinator.pendingClick != null) {
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
        val rootSelection = windowResolver.selectRootForPackage(request.packageName)
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
            eventLogger.logEvent(
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
            eventLogger.logEvent(
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
        if (!isSessionOpen(request.sessionKey, currentPackage, request.defaultRuleWindowMs)) return
        val appElapsedMs = foregroundState.elapsedSinceStart(SystemClock.elapsedRealtime()) ?: return
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

        if (!isSessionOpen(request.sessionKey, currentPackage, request.defaultRuleWindowMs)) return
        when (
            val coordinateResult = CoordinateFallbackMatcher.findResult(
                root = root,
                rules = activityScopedRules,
                packageName = currentPackage,
                selfPackageName = packageName,
                elapsedSinceForegroundMs = foregroundState.elapsedSinceStart(SystemClock.elapsedRealtime()) ?: return,
                screenWidth = resources.displayMetrics.widthPixels,
                screenHeight = resources.displayMetrics.heightPixels
            )
        ) {
            is CoordinateFallbackMatchResult.Matched -> {
                val fallback = coordinateResult.match
                val signature = "$currentPackage:${fallback.rule.id}:coordinate:${fallback.x}:${fallback.y}"
                pendingClickCoordinator.startStableCoordinateFallback(
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
            key == currentOpeningAdSessionKey()
    }

    private fun currentOpeningAdSessionKey(): String? {
        return foregroundState.sessionKey
    }

    internal fun pendingSessionKey(pending: PendingClick): String? {
        return pending.eventContext.openingAdSessionKey
    }

    internal fun hasActivityChanged(expectedActivityName: String): Boolean {
        val expected = expectedActivityName.trim()
        val current = currentActivityName.trim()
        return expected.isNotBlank() &&
            currentActivityIdentityKnown &&
            current.isNotBlank() &&
            !expected.equals(current, ignoreCase = true)
    }

    internal fun cancelScheduledOpeningAdRetry() {
        openingAdRecoveryState.cancelScheduledRetry()
    }

    /**
     * Recovery work (rescans and retries) must stop as soon as automation is no longer allowed
     * to click. Safety mode is a hard stop for clicking, so a scheduled retry must not resurrect
     * a click that the user just downgraded to observe-only.
     */
    internal fun isOpeningAdRecoveryAllowed(): Boolean {
        return SettingsRepository.isMasterEnabled(this) &&
            !SettingsRepository.isSafetyModeEnabled(this)
    }

    private fun cancelOpeningAdRecovery(resetRetrySession: Boolean = false) {
        mainHandler.removeCallbacksAndMessages(openingAdWorkToken)
        openingAdRecoveryState.cancel(resetRetrySession)
    }

    internal fun terminateOpeningAdRecovery(sessionKey: String? = currentOpeningAdSessionKey()) {
        if (sessionKey != currentOpeningAdSessionKey()) return
        mainHandler.removeCallbacksAndMessages(openingAdWorkToken)
        openingAdRecoveryState.terminate(sessionKey)
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
            eventLogger.logEvent(
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

        pendingClickCoordinator.startStableClick(
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

    internal fun buildEventContext(
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
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
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
            timeWindowDecision = snapshot.timeWindowDecision,
            openingAdSessionKey = snapshot.sessionKey,
            foregroundStartElapsedRealtimeMs = snapshot.foregroundStartElapsedRealtimeMs
        )
    }

    private fun AccessibilityEvent.isSupportedEvent(): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    internal fun logCoordinateFallbackBlocked(
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

        eventLogger.logEvent(
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

    private fun logScan(
        scan: ScanReport,
        eventContext: EventContext,
        scanDurationMs: Long? = null,
        retryCount: Int = 0,
        rescanReason: String = ""
    ) {
        if (scan.failureReason == HighRiskClickPolicy.BLOCKED_REASON) return
        if (scan.candidateCount > 0) {
            eventLogger.logEvent(
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
        eventLogger.logEvent(
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
        return ScanMissClassifier.classify(scan.failureReason, scan.candidateCount)
    }

    private fun logMatch(
        match: MatchResult,
        eventContext: EventContext,
        scan: ScanReport,
        retryCount: Int = 0,
        scanDurationMs: Long? = null,
        rescanReason: String = ""
    ) {
        eventLogger.logEvent(
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

    internal fun highRiskDecisionForPending(pending: PendingClick): HighRiskClickDecision {
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

    internal fun logBlockedByHighRiskPolicy(
        eventContext: EventContext,
        pending: PendingClick,
        decision: HighRiskClickDecision,
        scan: ScanReport? = null,
        clickTargetSource: ClickTargetSourceLog = pending.clickTargetSource,
        clearPending: Boolean = false
    ) {
        if (clearPending) pendingClickCoordinator.clearIfSignature(pending.signature)
        SettingsRepository.setLastFailureReason(
            this,
            HighRiskClickPolicy.BLOCKED_REASON,
            forcePersist = true
        )
        feedbackController.showDebugToast("安全策略已阻止高风险点击，已记录日志", "safety:${pending.packageName}:${pending.ruleId}")
        eventLogger.logEvent(
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

    private fun AccessibilityNodeInfo.containsOwnAppLabel(packageName: String): Boolean {
        return NodeScanBudget.walk(
            root = this,
            childCountOf = { it.childCount },
            childAt = AccessibilityNodeAccess::child,
            stopWhen = { node ->
                SafetyGuard.isOwnAppIconOnLauncher(
                    this@SkipAccessibilityService,
                    packageName,
                    node.text?.toString().orEmpty(),
                    node.contentDescription?.toString().orEmpty()
                )
            }
        ) == NodeTraversalResult.Stopped
    }

    companion object {
        private const val REPEAT_CLICK_GUARD_MS = 3000L
        internal const val STABLE_CLICK_DELAY_MS = 100L
        private const val ABSOLUTE_RESCAN_REASON = "opening_ad_absolute_rescan"
        private const val COORDINATE_BLOCKED_LOG_INTERVAL_MS = 2_000L
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
