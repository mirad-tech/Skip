package com.example.skip.data

import android.content.Context
import com.example.skip.model.AppHitStats
import com.example.skip.model.ClickLog
import com.example.skip.model.HitStats
import com.example.skip.model.RuleHitStats
import com.example.skip.model.StatsWindow

object StatsRepository {
    fun getStats(
        context: Context,
        window: StatsWindow = StatsWindow.All,
        now: Long = System.currentTimeMillis()
    ): HitStats {
        return aggregate(LogRepository.getClickLogs(context), window, now)
    }

    fun aggregate(
        logs: List<ClickLog>,
        window: StatsWindow,
        now: Long = System.currentTimeMillis()
    ): HitStats {
        val filtered = logs
            .filter { log -> window.durationMs == null || now - log.timeMillis <= window.durationMs }
            .filter { log -> log.packageName.isNotBlank() }

        val appStats = filtered
            .groupBy { it.packageName }
            .map { (packageName, appLogs) ->
                AppHitStats(
                    packageName = packageName,
                    appName = appLogs.firstOrNull { it.appName.isNotBlank() }?.appName.orEmpty(),
                    totalCount = appLogs.size,
                    successCount = appLogs.count(LogRepository::isSuccessfulHit),
                    failureCount = appLogs.count(LogRepository::isFailureHit),
                    safetyBlockedCount = appLogs.count(LogRepository::isSafetyBlockedHit),
                    coordinateFallbackCount = appLogs.count(LogRepository::isCoordinateFallbackHit),
                    lastHitTimeMillis = appLogs.maxOfOrNull { it.timeMillis } ?: 0L
                )
            }
            .sortedWith(compareByDescending<AppHitStats> { it.totalCount }.thenBy { it.packageName })

        val ruleStats = filtered
            .filter { it.ruleId.isNotBlank() || it.ruleName.isNotBlank() }
            .groupBy { "${it.packageName}\u0000${it.ruleId}\u0000${it.ruleName}" }
            .map { (_, ruleLogs) ->
                val first = ruleLogs.first()
                RuleHitStats(
                    ruleId = first.ruleId,
                    ruleName = first.ruleName.ifBlank { "-" },
                    packageName = first.packageName,
                    totalCount = ruleLogs.size,
                    successCount = ruleLogs.count(LogRepository::isSuccessfulHit),
                    failureCount = ruleLogs.count(LogRepository::isFailureHit),
                    safetyBlockedCount = ruleLogs.count(LogRepository::isSafetyBlockedHit),
                    coordinateFallbackCount = ruleLogs.count(LogRepository::isCoordinateFallbackHit),
                    lastHitTimeMillis = ruleLogs.maxOfOrNull { it.timeMillis } ?: 0L
                )
            }
            .sortedWith(compareByDescending<RuleHitStats> { it.totalCount }.thenBy { it.ruleName })

        val stageStats = filtered
            .groupingBy { it.stage }
            .eachCount()

        return HitStats(
            appStats = appStats,
            ruleStats = ruleStats,
            stageStats = stageStats,
            safetyBlockedCount = filtered.count(LogRepository::isSafetyBlockedHit),
            coordinateFallbackCount = filtered.count(LogRepository::isCoordinateFallbackHit)
        )
    }
}
