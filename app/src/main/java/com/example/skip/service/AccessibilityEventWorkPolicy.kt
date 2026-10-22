package com.example.skip.service

internal enum class AccessibilityEventWork {
    ProcessFully,
    SkipTreeRecordExpiry,
    Drop
}

internal object AccessibilityEventWorkPolicy {
    const val WINDOW_STATE_CHANGED = 0x00000020
    const val WINDOW_CONTENT_CHANGED = 0x00000800
    const val WINDOWS_CHANGED = 0x00400000
    const val MIN_CONTENT_SCAN_INTERVAL_MS = 300L

    fun decide(
        eventType: Int,
        elapsedSinceForegroundMs: Long?,
        ruleWindowMs: Long,
        lastTreeWalkElapsedRealtime: Long,
        nowElapsedRealtime: Long,
        hasPendingClick: Boolean,
        hasActiveSession: Boolean = true,
        masterStateChanged: Boolean = false
    ): AccessibilityEventWork {
        // A setting transition must be observed even when ordinary content work is dropped.
        if (masterStateChanged) return AccessibilityEventWork.ProcessFully
        if (hasPendingClick) return AccessibilityEventWork.ProcessFully
        if (eventType == WINDOW_STATE_CHANGED || eventType == WINDOWS_CHANGED) {
            return AccessibilityEventWork.ProcessFully
        }
        if (eventType != WINDOW_CONTENT_CHANGED) return AccessibilityEventWork.ProcessFully
        if (!hasActiveSession) return AccessibilityEventWork.Drop
        if (elapsedSinceForegroundMs != null &&
            ruleWindowMs > 0L &&
            elapsedSinceForegroundMs >= ruleWindowMs
        ) {
            return AccessibilityEventWork.SkipTreeRecordExpiry
        }
        if (lastTreeWalkElapsedRealtime > 0L &&
            nowElapsedRealtime - lastTreeWalkElapsedRealtime < MIN_CONTENT_SCAN_INTERVAL_MS
        ) {
            return AccessibilityEventWork.Drop
        }
        return AccessibilityEventWork.ProcessFully
    }
}
