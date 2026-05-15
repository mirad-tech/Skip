package com.example.skip.service

import com.example.skip.engine.SafetyGuard

internal data class ForegroundWindowState(
    val currentForegroundPackage: String? = null,
    val foregroundStartTimeMillis: Long = 0L,
    val observedExternalWindow: Boolean = false
)

internal data class TrustedPackageResolution(
    val resolvedPackageName: String,
    val detail: String = ""
)

internal data class DefaultRuleWindowSnapshot(
    val foregroundPackage: String = "",
    val foregroundStartTimeMillis: Long = 0L,
    val elapsedSinceForegroundMs: Long? = null,
    val defaultRuleWindowMs: Long,
    val isWithinDefaultRuleWindow: Boolean,
    val timeWindowDecision: String
)

internal object EventWindowTracker {
    fun resolveTrustedPackage(
        eventPackageName: String,
        rootPackageName: String
    ): TrustedPackageResolution {
        val eventPackage = eventPackageName.trim()
        val rootPackage = rootPackageName.trim()
        return when {
            rootPackage.isNotBlank() && eventPackage.isNotBlank() && rootPackage != eventPackage -> {
                TrustedPackageResolution(
                    resolvedPackageName = rootPackage,
                    detail = "root_package_overrode_event_package:event=$eventPackage;root=$rootPackage"
                )
            }

            rootPackage.isNotBlank() -> TrustedPackageResolution(rootPackage)
            else -> TrustedPackageResolution(eventPackage)
        }
    }

    fun updateForegroundWindow(
        state: ForegroundWindowState,
        resolvedPackageName: String,
        now: Long,
        selfPackageName: String,
        windowStateChanged: Boolean
    ): ForegroundWindowState {
        val packageName = resolvedPackageName.trim()
        if (packageName.isBlank()) return state
        if (packageName == selfPackageName || SafetyGuard.isProtectedPackage(packageName)) {
            return if (windowStateChanged && state.currentForegroundPackage != null) {
                state.copy(observedExternalWindow = true)
            } else {
                state
            }
        }
        if (packageName == state.currentForegroundPackage) {
            return if (windowStateChanged && state.observedExternalWindow) {
                state.copy(
                    foregroundStartTimeMillis = now,
                    observedExternalWindow = false
                )
            } else {
                state
            }
        }
        return ForegroundWindowState(
            currentForegroundPackage = packageName,
            foregroundStartTimeMillis = now,
            observedExternalWindow = false
        )
    }

    fun snapshot(
        state: ForegroundWindowState,
        activePackageName: String,
        now: Long,
        defaultRuleWindowMs: Long
    ): DefaultRuleWindowSnapshot {
        val activePackage = activePackageName.trim()
        val foregroundPackage = state.currentForegroundPackage.orEmpty()
        val startTime = state.foregroundStartTimeMillis
        val elapsed = if (startTime > 0L) (now - startTime).coerceAtLeast(0L) else null

        if (activePackage.isBlank() || foregroundPackage.isBlank() || startTime <= 0L) {
            return DefaultRuleWindowSnapshot(
                foregroundPackage = foregroundPackage,
                foregroundStartTimeMillis = startTime,
                elapsedSinceForegroundMs = elapsed,
                defaultRuleWindowMs = defaultRuleWindowMs,
                isWithinDefaultRuleWindow = false,
                timeWindowDecision = "not_started"
            )
        }

        if (SafetyGuard.isProtectedPackage(activePackage)) {
            return DefaultRuleWindowSnapshot(
                foregroundPackage = foregroundPackage,
                foregroundStartTimeMillis = startTime,
                elapsedSinceForegroundMs = elapsed,
                defaultRuleWindowMs = defaultRuleWindowMs,
                isWithinDefaultRuleWindow = false,
                timeWindowDecision = "ignored_system_package"
            )
        }

        if (activePackage != foregroundPackage || elapsed == null) {
            return DefaultRuleWindowSnapshot(
                foregroundPackage = foregroundPackage,
                foregroundStartTimeMillis = startTime,
                elapsedSinceForegroundMs = elapsed,
                defaultRuleWindowMs = defaultRuleWindowMs,
                isWithinDefaultRuleWindow = false,
                timeWindowDecision = "not_started"
            )
        }

        val withinWindow = elapsed <= defaultRuleWindowMs
        return DefaultRuleWindowSnapshot(
            foregroundPackage = foregroundPackage,
            foregroundStartTimeMillis = startTime,
            elapsedSinceForegroundMs = elapsed,
            defaultRuleWindowMs = defaultRuleWindowMs,
            isWithinDefaultRuleWindow = withinWindow,
            timeWindowDecision = if (withinWindow) "within_window" else "expired"
        )
    }
}
