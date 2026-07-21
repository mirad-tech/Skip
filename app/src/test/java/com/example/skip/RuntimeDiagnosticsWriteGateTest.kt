package com.example.skip

import com.example.skip.data.RuntimeDiagnosticsWriteGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeDiagnosticsWriteGateTest {
    @Test
    fun highFrequencyActivityIsPersistedAtMostOncePerInterval() {
        val gate = RuntimeDiagnosticsWriteGate(minPersistIntervalMs = 30_000L)
        gate.initialize(persistedServiceActiveAt = 1_000L, persistedFailureReason = "")

        repeat(29_999) { offset ->
            assertNull(gate.recordServiceActive(1_001L + offset))
        }

        val update = gate.recordServiceActive(31_000L)
        assertEquals(31_000L, update?.serviceActiveAt)
    }

    @Test
    fun repeatedFailureReasonDoesNotCreateAnotherWrite() {
        val gate = RuntimeDiagnosticsWriteGate(minPersistIntervalMs = 30_000L)
        gate.initialize(persistedServiceActiveAt = 1_000L, persistedFailureReason = "root_window_null")

        assertNull(gate.recordFailureReason("root_window_null", 31_000L))
        assertNull(gate.flush(31_001L))
    }

    @Test
    fun forcedFailurePersistsReasonWithoutBypassingPendingActivityThrottle() {
        val gate = RuntimeDiagnosticsWriteGate(minPersistIntervalMs = 30_000L)
        gate.initialize(persistedServiceActiveAt = 10_000L, persistedFailureReason = "")
        assertNull(gate.recordServiceActive(10_100L))

        val update = gate.recordFailureReason(
            reason = "candidate_lost_before_click",
            timeMillis = 10_200L,
            force = true
        )

        assertNull(update?.serviceActiveAt)
        assertEquals("candidate_lost_before_click", update?.lastFailureReason)

        val flushUpdate = gate.flush(10_300L)
        assertEquals(10_100L, flushUpdate?.serviceActiveAt)
        assertNull(flushUpdate?.lastFailureReason)
    }

    @Test
    fun repeatedForcedReasonDoesNotPersistThrottledActivity() {
        val gate = RuntimeDiagnosticsWriteGate(minPersistIntervalMs = 30_000L)
        gate.initialize(persistedServiceActiveAt = 10_000L, persistedFailureReason = "")

        assertNull(gate.recordServiceActive(10_100L))
        assertEquals(
            "automation_paused",
            gate.recordFailureReason("automation_paused", 10_200L, force = true)?.lastFailureReason
        )

        assertNull(gate.recordServiceActive(10_300L))
        assertNull(gate.recordFailureReason("automation_paused", 10_400L, force = true))

        val flushUpdate = gate.flush(10_500L)
        assertEquals(10_300L, flushUpdate?.serviceActiveAt)
        assertNull(flushUpdate?.lastFailureReason)
    }
}
