package com.example.skip.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningAdRecoveryStateUnitTest {
    @Test
    fun staleRetryCallbackCannotAdvanceState() {
        val state = OpeningAdRecoveryState()
        state.beginRetrySession("pkg:1")
        val staleGeneration = state.scheduleRetry()
        state.cancelScheduledRetry()

        assertFalse(state.acceptScheduledRetry(staleGeneration, nextRetryCount = 1))
        assertEquals(0, state.retryCount)
        assertFalse(state.retryScheduled)
    }

    @Test
    fun acceptedRetryAdvancesCountMonotonically() {
        val state = OpeningAdRecoveryState()
        state.beginRetrySession("pkg:1")
        val firstGeneration = state.scheduleRetry()

        assertTrue(state.acceptScheduledRetry(firstGeneration, nextRetryCount = 2))
        val secondGeneration = state.scheduleRetry()
        assertTrue(state.acceptScheduledRetry(secondGeneration, nextRetryCount = 1))
        assertEquals(2, state.retryCount)
    }

    @Test
    fun terminatePreservesTerminalSessionAndClearsActiveRecovery() {
        val state = OpeningAdRecoveryState()
        state.markRescansScheduled("pkg:1")
        state.beginRetrySession("pkg:1")
        state.scheduleRetry()

        state.terminate("pkg:1")

        assertEquals("pkg:1", state.terminalSessionKey)
        assertNull(state.rescanKey)
        assertFalse(state.retryScheduled)
        assertEquals("pkg:1", state.retrySessionKey)
    }

    @Test
    fun fullResetClearsSessionAndTerminalState() {
        val state = OpeningAdRecoveryState()
        state.beginRetrySession("pkg:1")
        state.terminate("pkg:1")

        state.cancel(resetRetrySession = true)

        assertNull(state.retrySessionKey)
        assertNull(state.terminalSessionKey)
        assertEquals(0, state.retryCount)
    }
}
