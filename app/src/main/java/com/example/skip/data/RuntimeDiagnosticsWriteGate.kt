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
    private var lastPersistAt = 0L
    private var lastKnownFailureReason = ""
    private var pendingServiceActiveAt = 0L
    private var pendingFailureReason: String? = null

    fun initialize(
        persistedServiceActiveAt: Long,
        persistedFailureReason: String
    ) {
        if (initialized) return
        initialized = true
        lastPersistAt = persistedServiceActiveAt
        lastKnownFailureReason = persistedFailureReason
    }

    fun recordServiceActive(timeMillis: Long): RuntimeDiagnosticsUpdate? {
        pendingServiceActiveAt = maxOf(pendingServiceActiveAt, timeMillis)
        return drain(timeMillis, force = false)
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
        return drain(timeMillis, force)
    }

    fun flush(timeMillis: Long): RuntimeDiagnosticsUpdate? {
        return drain(timeMillis, force = true)
    }

    private fun drain(timeMillis: Long, force: Boolean): RuntimeDiagnosticsUpdate? {
        if (!force && lastPersistAt > 0L && timeMillis - lastPersistAt < minPersistIntervalMs) {
            return null
        }
        val update = RuntimeDiagnosticsUpdate(
            serviceActiveAt = pendingServiceActiveAt.takeIf { it > 0L },
            lastFailureReason = pendingFailureReason
        )
        if (update.isEmpty) return null
        pendingServiceActiveAt = 0L
        pendingFailureReason = null
        lastPersistAt = timeMillis
        return update
    }
}
