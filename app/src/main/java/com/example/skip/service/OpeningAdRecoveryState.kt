package com.example.skip.service

internal class OpeningAdRecoveryState {
    var rescanKey: String? = null
        private set
    var retryGeneration: Long = 0L
        private set
    var retryScheduled: Boolean = false
        private set
    var retryCount: Int = 0
        private set
    var retrySessionKey: String? = null
        private set
    var terminalSessionKey: String? = null
        private set

    fun markRescansScheduled(sessionKey: String) {
        rescanKey = sessionKey
    }

    fun beginRetrySession(sessionKey: String) {
        cancelScheduledRetry()
        retrySessionKey = sessionKey
        retryCount = 0
    }

    fun scheduleRetry(): Long {
        retryGeneration += 1L
        retryScheduled = true
        return retryGeneration
    }

    fun acceptScheduledRetry(generation: Long, nextRetryCount: Int): Boolean {
        if (generation != retryGeneration) return false
        retryScheduled = false
        retryCount = maxOf(retryCount, nextRetryCount)
        return true
    }

    fun cancelScheduledRetry() {
        retryGeneration += 1L
        retryScheduled = false
    }

    fun cancel(resetRetrySession: Boolean = false) {
        rescanKey = null
        cancelScheduledRetry()
        if (resetRetrySession) {
            retrySessionKey = null
            retryCount = 0
            terminalSessionKey = null
        }
    }

    fun terminate(sessionKey: String?) {
        terminalSessionKey = sessionKey
        cancel()
    }
}
