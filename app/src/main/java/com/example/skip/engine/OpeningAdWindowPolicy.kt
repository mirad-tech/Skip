package com.example.skip.engine

/** One upper bound for every rule and every automatic click path. */
object OpeningAdWindowPolicy {
    const val MAX_WINDOW_MS = 6_000L

    fun effectiveWindowMs(requestedWindowMs: Long): Long =
        requestedWindowMs.coerceIn(0L, MAX_WINDOW_MS)

    fun isWithinWindow(startElapsedRealtimeMs: Long?, nowElapsedRealtimeMs: Long, windowMs: Long): Boolean {
        if (startElapsedRealtimeMs == null || nowElapsedRealtimeMs < startElapsedRealtimeMs) return false
        return nowElapsedRealtimeMs - startElapsedRealtimeMs < effectiveWindowMs(windowMs)
    }
}
