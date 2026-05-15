package com.example.skip.data

import android.content.Context
import com.example.skip.model.AppHitStats
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickTargetSourceLog
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
                    successCount = appLogs.count { it.isSuccessfulHit() },
                    failureCount = appLogs.count { it.isFailureHit() },
                    safetyBlockedCount = appLogs.count { it.isSafetyBlockedHit() },
                    coordinateFallbackCount = appLogs.count { it.isCoordinateFallbackHit() },
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
                    successCount = ruleLogs.count { it.isSuccessfulHit() },
                    failureCount = ruleLogs.count { it.isFailureHit() },
                    safetyBlockedCount = ruleLogs.count { it.isSafetyBlockedHit() },
                    coordinateFallbackCount = ruleLogs.count { it.isCoordinateFallbackHit() },
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
            safetyBlockedCount = filtered.count { it.isSafetyBlockedHit() },
            coordinateFallbackCount = filtered.count { it.isCoordinateFallbackHit() }
        )
    }

    private fun ClickLog.isSuccessfulHit(): Boolean {
        return success == true || stage == ClickLogStage.ClickEffectConfirmed
    }

    private fun ClickLog.isFailureHit(): Boolean {
        return success == false ||
            stage == ClickLogStage.ClickFailed ||
            stage == ClickLogStage.ClickEffectUnknown
    }

    private fun ClickLog.isSafetyBlockedHit(): Boolean {
        return blockedBySafety ||
            stage == ClickLogStage.SkippedBySafety ||
            blockedReason == "blocked_by_safety_policy"
    }

    private fun ClickLog.isCoordinateFallbackHit(): Boolean {
        return clickTargetSource == ClickTargetSourceLog.CoordinateFallback || isFixedCoordinateClick
    }
}
