package com.example.skip.data

internal data class RuntimeDiagnosticsUpdate(
    val serviceActiveAt: Long? = null,
    val lastFailureReason: String? = null
) {
    val isEmpty: Boolean
        get() = serviceActiveAt == null && lastFailureReason == null
}

internal class RuntimeDiagnosticsWriteGate(
    private val minPersistIntervalMs: Long = 30_000L
) {
    private var initialized = false
    private var lastServiceActivePersistAt = 0L
    private var lastFailureReasonPersistAt = 0L
    private var lastKnownFailureReason = ""
    private var pendingServiceActiveAt = 0L
    private var pendingFailureReason: String? = null

    fun initialize(
        persistedServiceActiveAt: Long,
        persistedFailureReason: String
    ) {
        if (initialized) return
        initialized = true
        lastServiceActivePersistAt = persistedServiceActiveAt
        lastFailureReasonPersistAt = persistedServiceActiveAt
        lastKnownFailureReason = persistedFailureReason
    }

    fun recordServiceActive(timeMillis: Long): RuntimeDiagnosticsUpdate? {
        pendingServiceActiveAt = maxOf(pendingServiceActiveAt, timeMillis)
        return drain(timeMillis)
    }

    fun recordFailureReason(
        reason: String,
        timeMillis: Long,
        force: Boolean = false
    ): RuntimeDiagnosticsUpdate? {
        val cleanReason = reason.trim()
        if (cleanReason != lastKnownFailureReason) {
            lastKnownFailureReason = cleanReason
            pendingFailureReason = cleanReason
        }
        return drain(timeMillis, forceFailureReason = force)
    }

    fun flush(timeMillis: Long): RuntimeDiagnosticsUpdate? {
        return drain(
            timeMillis = timeMillis,
            forceServiceActive = true,
            forceFailureReason = true
        )
    }

    private fun drain(
        timeMillis: Long,
        forceServiceActive: Boolean = false,
        forceFailureReason: Boolean = false
    ): RuntimeDiagnosticsUpdate? {
        val persistServiceActive = pendingServiceActiveAt > 0L && (
            forceServiceActive ||
                lastServiceActivePersistAt == 0L ||
                timeMillis - lastServiceActivePersistAt >= minPersistIntervalMs
            )
        val persistFailureReason = pendingFailureReason != null && (
            forceFailureReason ||
                lastFailureReasonPersistAt == 0L ||
                timeMillis - lastFailureReasonPersistAt >= minPersistIntervalMs
            )
        val update = RuntimeDiagnosticsUpdate(
            serviceActiveAt = pendingServiceActiveAt.takeIf { persistServiceActive },
            lastFailureReason = pendingFailureReason.takeIf { persistFailureReason }
        )
        if (update.isEmpty) return null
        if (persistServiceActive) {
            pendingServiceActiveAt = 0L
            lastServiceActivePersistAt = timeMillis
        }
        if (persistFailureReason) {
            pendingFailureReason = null
            lastFailureReasonPersistAt = timeMillis
        }
        return update
    }
}
