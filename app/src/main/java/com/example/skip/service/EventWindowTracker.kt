package com.example.skip.service

import com.example.skip.engine.SafetyGuard
import com.example.skip.engine.OpeningAdWindowPolicy

internal data class ForegroundWindowState(
    val currentForegroundPackage: String? = null,
    val foregroundStartTimeMillis: Long = 0L,
    val foregroundStartElapsedRealtimeMs: Long? = null,
    val sessionId: Long = 0L,
    val initialized: Boolean = false,
    val isForegroundVisible: Boolean = false,
    val detectionFinished: Boolean = true
) {
    val sessionKey: String?
        get() = if (foregroundStartElapsedRealtimeMs != null && !currentForegroundPackage.isNullOrBlank()) {
            "$currentForegroundPackage:$sessionId"
        } else null

    fun elapsedSinceStart(nowElapsedRealtimeMs: Long): Long? =
        foregroundStartElapsedRealtimeMs?.let { (nowElapsedRealtimeMs - it).coerceAtLeast(0L) }
}

internal enum class ForegroundWindowKind { Application, Navigation, Transient, Unknown }

internal data class ForegroundObservation(
    val kind: ForegroundWindowKind,
    val packageName: String = ""
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
    val timeWindowDecision: String,
    val sessionKey: String? = null,
    val foregroundStartElapsedRealtimeMs: Long? = null
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
        observation: ForegroundObservation,
        now: Long,
        nowElapsedRealtimeMs: Long,
        windowStateChanged: Boolean,
        masterEnabled: Boolean = true,
        masterStateChanged: Boolean = false
    ): ForegroundWindowState {
        // End the old cycle without forgetting the app we are switching away from.
        val previous = if (masterStateChanged || !masterEnabled) {
            state.copy(detectionFinished = true)
        } else state
        val packageName = observation.packageName.trim()
        if (observation.kind == ForegroundWindowKind.Unknown ||
            observation.kind == ForegroundWindowKind.Transient || packageName.isBlank()
        ) {
            return previous.copy(isForegroundVisible = false)
        }
        val applicationVisible = observation.kind == ForegroundWindowKind.Application
        if (previous.initialized && packageName == previous.currentForegroundPackage) {
            return previous.copy(isForegroundVisible = applicationVisible)
        }
        // Content changes and click callbacks can suspend a cycle, but never open a new one.
        if (previous.initialized && !windowStateChanged) {
            return previous.copy(isForegroundVisible = false)
        }
        val startDetection = masterEnabled && previous.initialized && windowStateChanged && applicationVisible
        return ForegroundWindowState(
            currentForegroundPackage = packageName,
            foregroundStartTimeMillis = if (startDetection) now else 0L,
            foregroundStartElapsedRealtimeMs = if (startDetection) nowElapsedRealtimeMs else null,
            sessionId = previous.sessionId + 1L,
            initialized = true,
            isForegroundVisible = applicationVisible,
            detectionFinished = !startDetection
        )
    }

    fun finishSession(state: ForegroundWindowState, sessionKey: String?): ForegroundWindowState =
        if (sessionKey != null && state.sessionKey == sessionKey) state.copy(detectionFinished = true) else state

    fun isSessionOpen(
        state: ForegroundWindowState,
        sessionKey: String?,
        packageName: String,
        nowElapsedRealtimeMs: Long,
        windowMs: Long
    ): Boolean = sessionKey != null && sessionKey == state.sessionKey &&
        snapshot(state, packageName, nowElapsedRealtimeMs, windowMs).isWithinDefaultRuleWindow

    fun snapshot(
        state: ForegroundWindowState,
        activePackageName: String,
        nowElapsedRealtimeMs: Long,
        defaultRuleWindowMs: Long
    ): DefaultRuleWindowSnapshot {
        val activePackage = activePackageName.trim()
        val foregroundPackage = state.currentForegroundPackage.orEmpty()
        val startTime = state.foregroundStartTimeMillis
        val elapsed = state.elapsedSinceStart(nowElapsedRealtimeMs)
        val effectiveWindowMs = OpeningAdWindowPolicy.effectiveWindowMs(defaultRuleWindowMs)
        val decision = when {
            activePackage.isBlank() || foregroundPackage.isBlank() || elapsed == null -> "not_started"
            SafetyGuard.isProtectedPackage(activePackage) -> "ignored_system_package"
            activePackage != foregroundPackage || !state.isForegroundVisible -> "not_foreground"
            state.detectionFinished -> "finished"
            !OpeningAdWindowPolicy.isWithinWindow(
                state.foregroundStartElapsedRealtimeMs, nowElapsedRealtimeMs, effectiveWindowMs
            ) -> "expired"
            else -> "within_window"
        }
        return DefaultRuleWindowSnapshot(
            foregroundPackage = foregroundPackage,
            foregroundStartTimeMillis = startTime,
            elapsedSinceForegroundMs = elapsed,
            defaultRuleWindowMs = effectiveWindowMs,
            isWithinDefaultRuleWindow = decision == "within_window",
            timeWindowDecision = decision,
            sessionKey = state.sessionKey,
            foregroundStartElapsedRealtimeMs = state.foregroundStartElapsedRealtimeMs
        )
    }
}
