package com.example.skip.data

import com.example.skip.model.ClickLog

internal class ClickLogBuffer(
    private val maxCount: Int,
    private val maxAgeMs: Long,
    private val duplicateWindowMs: Long
) {
    private val logs = mutableListOf<ClickLog>()

    fun replaceAll(values: List<ClickLog>) {
        logs.clear()
        logs.addAll(values.sortedByDescending { it.timeMillis }.take(maxCount))
    }

    fun add(log: ClickLog, now: Long) {
        logs.removeAll { existing ->
            now - existing.timeMillis > maxAgeMs || existing.isDuplicateOf(log, now)
        }
        logs.add(0, log)
        if (logs.size > maxCount) {
            logs.subList(maxCount, logs.size).clear()
        }
    }

    fun snapshot(): List<ClickLog> = logs.toList()

    fun clear() {
        logs.clear()
    }

    private fun ClickLog.isDuplicateOf(other: ClickLog, now: Long): Boolean {
        if (now - timeMillis > duplicateWindowMs) return false
        return packageName == other.packageName &&
            ruleId == other.ruleId &&
            stage == other.stage &&
            failureReason == other.failureReason
    }
}
