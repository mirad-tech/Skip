package com.example.skip

import com.example.skip.data.RuleRepository
import com.example.skip.service.EventWindowTracker
import com.example.skip.service.ForegroundWindowState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventWindowTrackerUnitTest {
    @Test
    fun appSwitchStartsForegroundWindow() {
        val updated = EventWindowTracker.updateForegroundWindow(
            state = ForegroundWindowState(),
            resolvedPackageName = "com.example.news",
            now = 1_000L,
            selfPackageName = "com.example.skip",
            windowStateChanged = true
        )

        assertEquals("com.example.news", updated.currentForegroundPackage)
        assertEquals(1_000L, updated.foregroundStartTimeMillis)
    }

    @Test
    fun samePackageDoesNotResetForegroundWindow() {
        val updated = EventWindowTracker.updateForegroundWindow(
            state = ForegroundWindowState("com.example.news", 1_000L),
            resolvedPackageName = "com.example.news",
            now = 5_000L,
            selfPackageName = "com.example.skip",
            windowStateChanged = true
        )

        assertEquals(1_000L, updated.foregroundStartTimeMillis)
    }

    @Test
    fun systemUiDoesNotResetForegroundWindow() {
        val updated = EventWindowTracker.updateForegroundWindow(
            state = ForegroundWindowState("com.example.news", 1_000L),
            resolvedPackageName = "com.android.systemui",
            now = 2_000L,
            selfPackageName = "com.example.skip",
            windowStateChanged = true
        )

        assertEquals("com.example.news", updated.currentForegroundPackage)
        assertEquals(1_000L, updated.foregroundStartTimeMillis)
        assertTrue(updated.observedExternalWindow)
    }

    @Test
    fun launcherDoesNotResetForegroundWindow() {
        val updated = EventWindowTracker.updateForegroundWindow(
            state = ForegroundWindowState("com.example.news", 1_000L),
            resolvedPackageName = "com.bbk.launcher2",
            now = 2_000L,
            selfPackageName = "com.example.skip",
            windowStateChanged = true
        )

        assertEquals("com.example.news", updated.currentForegroundPackage)
        assertEquals(1_000L, updated.foregroundStartTimeMillis)
        assertTrue(updated.observedExternalWindow)
    }

    @Test
    fun selfPackageDoesNotResetForegroundWindow() {
        val updated = EventWindowTracker.updateForegroundWindow(
            state = ForegroundWindowState("com.example.news", 1_000L),
            resolvedPackageName = "com.example.skip",
            now = 2_000L,
            selfPackageName = "com.example.skip",
            windowStateChanged = true
        )

        assertEquals("com.example.news", updated.currentForegroundPackage)
        assertEquals(1_000L, updated.foregroundStartTimeMillis)
        assertTrue(updated.observedExternalWindow)
    }

    @Test
    fun contentChangeAfterExternalWindowDoesNotRestartForegroundWindow() {
        val updated = EventWindowTracker.updateForegroundWindow(
            state = ForegroundWindowState(
                currentForegroundPackage = "com.example.news",
                foregroundStartTimeMillis = 1_000L,
                observedExternalWindow = true
            ),
            resolvedPackageName = "com.example.news",
            now = 5_000L,
            selfPackageName = "com.example.skip",
            windowStateChanged = false
        )

        assertEquals("com.example.news", updated.currentForegroundPackage)
        assertEquals(1_000L, updated.foregroundStartTimeMillis)
        assertTrue(updated.observedExternalWindow)
    }

    @Test
    fun sameAppAfterExternalWindowRestartsForegroundWindow() {
        val updated = EventWindowTracker.updateForegroundWindow(
            state = ForegroundWindowState(
                currentForegroundPackage = "com.example.news",
                foregroundStartTimeMillis = 1_000L,
                observedExternalWindow = true
            ),
            resolvedPackageName = "com.example.news",
            now = 8_000L,
            selfPackageName = "com.example.skip",
            windowStateChanged = true
        )

        assertEquals("com.example.news", updated.currentForegroundPackage)
        assertEquals(8_000L, updated.foregroundStartTimeMillis)
        assertFalse(updated.observedExternalWindow)
    }

    @Test
    fun contentChangeAfterSixSecondsDoesNotRestartWindow() {
        val snapshot = EventWindowTracker.snapshot(
            state = ForegroundWindowState("com.example.news", 1_000L),
            activePackageName = "com.example.news",
            now = 7_100L,
            defaultRuleWindowMs = RuleRepository.DEFAULT_RULE_WINDOW_MS
        )

        assertFalse(snapshot.isWithinDefaultRuleWindow)
        assertEquals("expired", snapshot.timeWindowDecision)
    }

    @Test
    fun withinSixSecondsAllowsScanning() {
        val snapshot = EventWindowTracker.snapshot(
            state = ForegroundWindowState("com.example.news", 1_000L),
            activePackageName = "com.example.news",
            now = 6_000L,
            defaultRuleWindowMs = RuleRepository.DEFAULT_RULE_WINDOW_MS
        )

        assertTrue(snapshot.isWithinDefaultRuleWindow)
        assertEquals("within_window", snapshot.timeWindowDecision)
    }

    @Test
    fun protectedPackageSnapshotIsIgnored() {
        val snapshot = EventWindowTracker.snapshot(
            state = ForegroundWindowState("com.example.news", 1_000L),
            activePackageName = "com.android.systemui",
            now = 2_000L,
            defaultRuleWindowMs = RuleRepository.DEFAULT_RULE_WINDOW_MS
        )

        assertFalse(snapshot.isWithinDefaultRuleWindow)
        assertEquals("ignored_system_package", snapshot.timeWindowDecision)
    }

    @Test
    fun rootPackageOverridesMismatchedEventPackage() {
        val resolution = EventWindowTracker.resolveTrustedPackage(
            eventPackageName = "com.android.systemui",
            rootPackageName = "com.example.news"
        )

        assertEquals("com.example.news", resolution.resolvedPackageName)
        assertTrue(resolution.detail.contains("root_package_overrode_event_package"))
    }
}
