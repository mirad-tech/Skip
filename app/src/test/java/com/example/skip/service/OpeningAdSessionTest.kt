package com.example.skip.service

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningAdSessionTest {
    private val app = "com.example.reader"
    private val other = "com.example.video"
    private val home = ForegroundObservation(ForegroundWindowKind.Navigation, "com.android.launcher")

    private fun observe(
        state: ForegroundWindowState,
        observation: ForegroundObservation,
        elapsed: Long,
        wall: Long = 100_000L + elapsed,
        windowEvent: Boolean = true
    ) = EventWindowTracker.updateForegroundWindow(state, observation, wall, elapsed, windowEvent)

    private fun application(pkg: String = app) = ForegroundObservation(ForegroundWindowKind.Application, pkg)

    private fun opened(): ForegroundWindowState {
        val baseline = observe(ForegroundWindowState(), home, 0L, windowEvent = false)
        return observe(baseline, application(), 1_000L)
    }

    private fun isOpen(state: ForegroundWindowState, now: Long, key: String? = state.sessionKey, window: Long = 6_000L) =
        EventWindowTracker.isSessionOpen(state, key, app, now, window)

    @Test
    fun firstObservationIsOnlyABaselineEvenWhenTheAppIsAlreadyOpen() {
        val baseline = observe(ForegroundWindowState(), application(), 10_000L)
        assertNull(baseline.sessionKey)
        assertFalse(isOpen(baseline, 10_001L))
        val sameApp = observe(baseline, application(), 12_000L)
        assertNull(sameApp.sessionKey)
        val nextApp = observe(sameApp, application(other), 13_000L)
        assertTrue(EventWindowTracker.isSessionOpen(nextApp, nextApp.sessionKey, other, 13_001L, 6_000L))
    }

    @Test
    fun allows5999MillisecondsButStopsAt6000() {
        val state = opened()
        assertTrue(isOpen(state, 6_999L))
        assertFalse(isOpen(state, 7_000L))
        assertFalse(isOpen(state, 10_000L, window = 15_000L))
    }

    @Test
    fun shorterPreciseWindowKeepsTheOriginalForegroundStart() {
        val state = observe(opened(), application(), 2_000L)
        assertTrue(isOpen(state, 3_999L, window = 3_000L))
        assertFalse(isOpen(state, 4_000L, window = 3_000L))
        assertEquals(1_000L, state.foregroundStartElapsedRealtimeMs)
    }

    @Test
    fun activityAndContentEventsNeverExtendTheCycle() {
        val initial = opened()
        val activity = observe(initial, application(), 5_000L)
        val content = observe(activity, application(), 6_500L, windowEvent = false)
        assertEquals(initial.sessionKey, content.sessionKey)
        assertEquals(initial.foregroundStartElapsedRealtimeMs, content.foregroundStartElapsedRealtimeMs)
        assertFalse(isOpen(content, 7_000L))
    }

    @Test
    fun wallClockChangesDoNotAffectTheDeadline() {
        val initial = opened()
        for (wall in listOf(0L, Long.MAX_VALUE / 2)) {
            val changedClock = observe(initial, application(), 6_900L, wall = wall)
            assertEquals(initial.foregroundStartTimeMillis, changedClock.foregroundStartTimeMillis)
            assertTrue(isOpen(changedClock, 6_999L))
            assertFalse(isOpen(changedClock, 7_000L))
        }
    }

    @Test
    fun notificationKeyboardAndPermissionWindowsDoNotRearm() {
        val overlays = listOf(
            ForegroundWindowClassifier.classify("com.android.systemui", AccessibilityWindowInfo.TYPE_SYSTEM, "android.widget.FrameLayout"),
            ForegroundWindowClassifier.classify("com.keyboard", AccessibilityWindowInfo.TYPE_INPUT_METHOD, ""),
            ForegroundWindowClassifier.classify("com.android.permissioncontroller", AccessibilityWindowInfo.TYPE_APPLICATION, "GrantPermissionsActivity")
        )
        overlays.forEach { overlay ->
            val initial = opened()
            val suspended = observe(initial, overlay, 2_000L)
            assertFalse(isOpen(suspended, 2_001L))
            val returned = observe(suspended, application(), 3_000L)
            assertEquals(initial.sessionKey, returned.sessionKey)
            assertTrue(isOpen(returned, 3_001L))
            assertFalse(isOpen(returned, 7_000L))
        }
    }

    @Test
    fun overlaysAfterExpiryDoNotStartAnotherCycle() {
        val initial = opened()
        val suspended = observe(initial, ForegroundObservation(ForegroundWindowKind.Transient), 20_000L)
        val returned = observe(suspended, application(), 21_000L)
        assertEquals(initial.sessionKey, returned.sessionKey)
        assertFalse(isOpen(returned, 21_001L))
    }

    @Test
    fun successStopsOrdinaryEventsAndRecoveryUntilANewVisit() {
        val initial = opened()
        val finished = EventWindowTracker.finishSession(initial, initial.sessionKey)
        val activity = observe(finished, application(), 2_000L)
        assertFalse(isOpen(activity, 2_001L))
        assertEquals(AccessibilityEventWork.Drop, AccessibilityEventWorkPolicy.decide(
            AccessibilityEventWorkPolicy.WINDOW_CONTENT_CHANGED, 1_001L, 6_000L,
            0L, 2_001L, false, hasActiveSession = !activity.detectionFinished
        ))
        assertFalse(OpeningAdRetryPolicy.shouldRetry("no_candidate_found", 0, isOpen(activity, 2_001L)))
        assertFalse(OpeningAdRescanPolicy.shouldSchedule(
            com.example.skip.model.ClickLogStage.NoCandidateFound, isOpen(activity, 2_001L), false, true
        ))
        val away = observe(activity, application(other), 3_000L)
        val returned = observe(away, application(), 4_000L)
        assertTrue(isOpen(returned, 4_001L))
        assertNotEquals(initial.sessionKey, returned.sessionKey)
    }

    @Test
    fun homeAndExplicitRecentsPermitAnotherVisit() {
        val recents = ForegroundWindowClassifier.classify(
            "com.android.systemui", AccessibilityWindowInfo.TYPE_SYSTEM,
            "com.android.systemui.recents.RecentsActivity"
        )
        for (navigation in listOf(home, recents)) {
            val initial = opened()
            val away = observe(initial, navigation, 10_000L)
            assertNull(away.sessionKey)
            val returned = observe(away, application(), 11_000L)
            assertTrue(isOpen(returned, 11_001L))
            assertNotEquals(initial.sessionKey, returned.sessionKey)
        }
    }

    @Test
    fun oldCycleCannotClickOrFinishAfterLeavingAndReturningToSameApp() {
        val initial = opened()
        val otherApp = observe(initial, application(other), 2_000L)
        val returned = observe(otherApp, application(), 3_000L)
        assertFalse(isOpen(returned, 3_001L, key = initial.sessionKey))
        assertEquals(returned, EventWindowTracker.finishSession(returned, initial.sessionKey))
        assertTrue(isOpen(returned, 3_001L))
    }

    @Test
    fun unknownFocusAndBackgroundContentCannotCreateNewCycles() {
        val initial = opened()
        val backgroundContent = observe(initial, application(other), 2_000L, windowEvent = false)
        assertEquals(initial.sessionKey, backgroundContent.sessionKey)
        assertFalse(isOpen(backgroundContent, 2_001L))
        val unknown = observe(backgroundContent, ForegroundObservation(ForegroundWindowKind.Unknown), 3_000L)
        assertEquals(initial.sessionKey, unknown.sessionKey)
        assertFalse(isOpen(unknown, 3_001L))
    }

    @Test
    fun reconnectDoesNotTreatTheCurrentAppAsANewLaunch() {
        val initial = opened()
        val baseline = observe(ForegroundWindowState(sessionId = initial.sessionId + 1L), application(), 5_000L, windowEvent = false)
        assertFalse(isOpen(baseline, 5_001L))
        assertFalse(isOpen(baseline, 5_001L, key = initial.sessionKey))
        assertNull(observe(baseline, application(), 5_100L).sessionKey)
    }

    @Test
    fun realProtectedAppsAreForegroundBoundariesButCannotBeScanned() {
        val initial = opened()
        val protectedApp = observe(initial, application("com.tencent.mm"), 2_000L)
        assertFalse(EventWindowTracker.isSessionOpen(protectedApp, protectedApp.sessionKey, "com.tencent.mm", 2_001L, 6_000L))
        val returned = observe(protectedApp, application(), 3_000L)
        assertTrue(isOpen(returned, 3_001L))
        assertNotEquals(initial.sessionKey, returned.sessionKey)
    }
}
