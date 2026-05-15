package com.example.skip.model

data class HitStats(
    val appStats: List<AppHitStats>,
    val ruleStats: List<RuleHitStats>,
    val stageStats: Map<ClickLogStage, Int>,
    val safetyBlockedCount: Int = 0,
    val coordinateFallbackCount: Int = 0
)

data class AppHitStats(
    val packageName: String,
    val appName: String,
    val totalCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val lastHitTimeMillis: Long,
    val safetyBlockedCount: Int = 0,
    val coordinateFallbackCount: Int = 0
)

data class RuleHitStats(
    val ruleId: String,
    val ruleName: String,
    val packageName: String,
    val totalCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val lastHitTimeMillis: Long,
    val safetyBlockedCount: Int = 0,
    val coordinateFallbackCount: Int = 0
)

enum class StatsWindow(val label: String, val durationMs: Long?) {
    All("全部", null),
    Today("今天", 24L * 60L * 60L * 1000L),
    Recent7Days("近 7 天", 7L * 24L * 60L * 60L * 1000L)
}
