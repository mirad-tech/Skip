package com.example.skip.data

internal object LegacyMigrationRetryPolicy {
    fun shouldReuseAttempt(
        failedAtElapsedMillis: Long,
        nowElapsedMillis: Long,
        retryBackoffMs: Long
    ): Boolean {
        if (failedAtElapsedMillis <= 0L) return true
        return nowElapsedMillis - failedAtElapsedMillis < retryBackoffMs
    }
}
