package com.example.skip.service

import com.example.skip.engine.SafetyGuard
import com.example.skip.model.ClickLogStage

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
