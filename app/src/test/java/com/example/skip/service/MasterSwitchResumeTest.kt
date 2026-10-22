package com.example.skip.service

import com.example.skip.engine.OpeningAdWindowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterSwitchResumeTest {
    private val reader = "com.example.reader"
    private val skip = "com.example.skip"

    private fun observe(
        state: ForegroundWindowState,
        packageName: String,
        now: Long,
        enabled: Boolean,
        changed: Boolean = false,
        windowEvent: Boolean = true
    ) = EventWindowTracker.updateForegroundWindow(
        state = state,
        observation = ForegroundObservation(ForegroundWindowKind.Application, packageName),
        now = 100_000L + now,
        nowElapsedRealtimeMs = now,
        windowStateChanged = windowEvent,
        masterEnabled = enabled,
        masterStateChanged = changed
    )

    private fun baseline(packageName: String) = observe(ForegroundWindowState(), packageName, 0L, false)

    private fun isOpen(state: ForegroundWindowState, now: Long, key: String? = state.sessionKey) =
        EventWindowTracker.isSessionOpen(state, key, reader, now, OpeningAdWindowPolicy.MAX_WINDOW_MS)

    @Test
    fun firstWindowEventAfterReenableStartsDetectionWithoutAnIntermediateContentEvent() {
        val pausedInSkip = baseline(skip)
        val returned = observe(pausedInSkip, reader, 10_000L, enabled = true, changed = true)
        assertTrue(isOpen(returned, 10_001L))
        assertFalse(returned.detectionFinished)
        assertEquals(10_000L, returned.foregroundStartElapsedRealtimeMs)
        assertTrue(isOpen(returned, 15_999L))
        assertFalse(isOpen(returned, 16_000L))
    }

    @Test
    fun visitsWhilePausedAreTrackedSoReturningToTheOriginalAppIsNotMissed() {
        val pausedInReader = baseline(reader)
        val pausedInSkip = observe(pausedInReader, skip, 1_000L, enabled = false)
        assertEquals(skip, pausedInSkip.currentForegroundPackage)
        assertNull(pausedInSkip.sessionKey)
        assertTrue(pausedInSkip.detectionFinished)

        val returned = observe(pausedInSkip, reader, 2_000L, enabled = true, changed = true)
        assertTrue(isOpen(returned, 2_001L))
        assertNotEquals(pausedInReader.sessionId, returned.sessionId)
    }

    @Test
    fun reenableContentEventIsProcessedButDoesNotOpenACycleInTheCurrentApp() {
        val pausedInSkip = baseline(skip)
        val work = AccessibilityEventWorkPolicy.decide(
            eventType = AccessibilityEventWorkPolicy.WINDOW_CONTENT_CHANGED,
            elapsedSinceForegroundMs = null,
            ruleWindowMs = 6_000L,
            lastTreeWalkElapsedRealtime = 999L,
            nowElapsedRealtime = 1_000L,
            hasPendingClick = false,
            hasActiveSession = false,
            masterStateChanged = true
        )
        assertEquals(AccessibilityEventWork.ProcessFully, work)
        val enabledInSkip = observe(pausedInSkip, skip, 1_000L, true, changed = true, windowEvent = false)
        assertNull(enabledInSkip.sessionKey)
        val returned = observe(enabledInSkip, reader, 2_000L, true)
        assertTrue(isOpen(returned, 2_001L))
    }

    @Test
    fun switchChangesBypassBothExpiryAndContentThrottling() {
        for (elapsed in listOf(100L, 6_000L, 60_000L)) {
            val work = AccessibilityEventWorkPolicy.decide(
                AccessibilityEventWorkPolicy.WINDOW_CONTENT_CHANGED, elapsed, 6_000L,
                10_000L, 10_001L, false, hasActiveSession = true, masterStateChanged = true
            )
            assertEquals(AccessibilityEventWork.ProcessFully, work)
        }
        assertEquals(AccessibilityEventWork.Drop, AccessibilityEventWorkPolicy.decide(
            AccessibilityEventWorkPolicy.WINDOW_CONTENT_CHANGED, null, 6_000L,
            0L, 10_001L, false, hasActiveSession = false, masterStateChanged = false
        ))
    }

    @Test
    fun turningOffEndsTheCurrentCycleAndReenablingInPlaceDoesNotReviveIt() {
        val running = observe(baseline(skip), reader, 1_000L, true)
        val oldKey = running.sessionKey
        val paused = observe(running, reader, 2_000L, false, changed = true, windowEvent = false)
        assertFalse(isOpen(paused, 2_001L, oldKey))
        val enabledInPlace = observe(paused, reader, 3_000L, true, changed = true, windowEvent = false)
        assertFalse(isOpen(enabledInPlace, 3_001L, oldKey))
        assertEquals(running.foregroundStartElapsedRealtimeMs, enabledInPlace.foregroundStartElapsedRealtimeMs)
    }

    @Test
    fun firstResumedCycleStillStopsOnSuccessAndRejectsOldCallbacks() {
        val oldCycle = observe(baseline(skip), reader, 1_000L, true)
        val pausedInSkip = observe(oldCycle, skip, 2_000L, false, changed = true)
        val resumed = observe(pausedInSkip, reader, 3_000L, true, changed = true)
        assertTrue(isOpen(resumed, 3_001L))
        assertFalse(isOpen(resumed, 3_001L, oldCycle.sessionKey))
        assertEquals(resumed, EventWindowTracker.finishSession(resumed, oldCycle.sessionKey))
        val finished = EventWindowTracker.finishSession(resumed, resumed.sessionKey)
        assertFalse(isOpen(observe(finished, reader, 4_000L, true), 4_001L))
    }

    @Test
    fun reenableWithUnknownOrTransientFocusDoesNotInventALaunch() {
        for (kind in listOf(ForegroundWindowKind.Unknown, ForegroundWindowKind.Transient)) {
            val state = EventWindowTracker.updateForegroundWindow(
                baseline(reader), ForegroundObservation(kind), 101_000L, 1_000L,
                windowStateChanged = true, masterEnabled = true, masterStateChanged = true
            )
            assertFalse(isOpen(state, 1_001L))
            assertFalse(isOpen(observe(state, reader, 2_000L, true), 2_001L))
        }
    }
}
