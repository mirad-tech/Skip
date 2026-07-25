package com.example.skip.data

import android.content.Context
import com.example.skip.data.db.ClickLogThrottleCountEntity
import com.example.skip.data.db.SkipDatabase
import org.json.JSONArray
import org.json.JSONObject

internal class QuarantineWriteException : IllegalStateException("quarantine_write_failed")

internal object LegacyLogMigrator {
    private const val KEY_CLICK_LOGS = "click_logs"
    private const val KEY_CLICK_LOG_THROTTLE_COUNTS = "click_log_throttle_counts"
    private const val KEY_CLICK_LOGS_QUARANTINE = "click_logs_quarantine_v1"
    private const val KEY_THROTTLE_COUNTS_QUARANTINE = "click_log_throttle_counts_quarantine_v1"
    private const val KEY_LEGACY_QUARANTINE_META = "legacy_log_quarantine_meta_v1"
    private const val LEGACY_MIGRATION_KEY = "shared_preferences_click_logs_v1"

    suspend fun migrate(
        context: Context,
        maxLogAgeMs: Long,
        dayMs: Long,
        maxClickLogCount: Int
    ): Boolean {
        val prefs = SettingsRepository.prefs(context)
        val hasLegacyLogs = prefs.contains(KEY_CLICK_LOGS)
        val hasLegacyThrottleCounts = prefs.contains(KEY_CLICK_LOG_THROTTLE_COUNTS)
        if (!hasLegacyLogs && !hasLegacyThrottleCounts) {
            return prefs.contains(KEY_LEGACY_QUARANTINE_META)
        }

        val now = System.currentTimeMillis()
        val rawLogs = if (hasLegacyLogs) runCatching {
            prefs.getString(KEY_CLICK_LOGS, null)
        }.getOrNull() else null
        val rawCounts = if (hasLegacyThrottleCounts) runCatching {
            prefs.getString(KEY_CLICK_LOG_THROTTLE_COUNTS, null)
        }.getOrNull() else null
        var logsCorrupt = false
        var countsCorrupt = false
        val legacyLogs = if (hasLegacyLogs) runCatching {
            require(!rawLogs.isNullOrBlank()) { "legacy_click_logs_corrupt" }
            ClickLogCodec.deserializeClickLogPersistence(rawLogs)
        }.getOrElse {
            logsCorrupt = true
            emptyList()
        } else emptyList()
        val legacyCounts = if (hasLegacyThrottleCounts) runCatching {
            require(!rawCounts.isNullOrBlank()) { "legacy_throttle_counts_corrupt" }
            ClickLogCodec.deserializeThrottleCounts(rawCounts)
        }.getOrElse {
            countsCorrupt = true
            emptyMap()
        } else emptyMap()

        val quarantined = logsCorrupt || countsCorrupt
        if (quarantined) {
            val items = JSONArray()
            val reasons = JSONArray()
            val editor = prefs.edit()
            if (logsCorrupt) {
                editor.putString(KEY_CLICK_LOGS_QUARANTINE, rawLogs.orEmpty())
                    .remove(KEY_CLICK_LOGS)
                items.put(KEY_CLICK_LOGS)
                reasons.put(LogStorageErrorCode.LegacyClickLogsCorrupt.value)
            }
            if (countsCorrupt) {
                editor.putString(KEY_THROTTLE_COUNTS_QUARANTINE, rawCounts.orEmpty())
                    .remove(KEY_CLICK_LOG_THROTTLE_COUNTS)
                items.put(KEY_CLICK_LOG_THROTTLE_COUNTS)
                reasons.put(LogStorageErrorCode.LegacyThrottleCountsCorrupt.value)
            }
            editor.putString(
                KEY_LEGACY_QUARANTINE_META,
                JSONObject()
                    .put("version", 1)
                    .put("quarantinedAtMillis", now)
                    .put("items", items)
                    .put("reasonCodes", reasons)
                    .toString()
            )
            if (!editor.commit()) throw QuarantineWriteException()
        }
        val currentDay = dayStartMillis(now, dayMs)
        SkipDatabase.get(context).logDao().migrateLegacyOnce(
            migrationKey = LEGACY_MIGRATION_KEY,
            logs = legacyLogs.map(ClickLogEntityMapper::toEntity),
            throttleCounts = legacyCounts.map { (key, count) ->
                ClickLogThrottleCountEntity(
                    dayStartMillis = currentDay,
                    reasonKey = key,
                    count = count.toLong(),
                    updatedAtMillis = now
                )
            },
            clickLogExpiryCutoffMillis = now - maxLogAgeMs,
            throttleCutoffDayStartMillis = currentDay - 6L * dayMs,
            maxClickLogCount = maxClickLogCount
        )

        val removed = prefs.edit()
            .remove(KEY_CLICK_LOGS)
            .remove(KEY_CLICK_LOG_THROTTLE_COUNTS)
            .commit()
        check(
            removed || (!prefs.contains(KEY_CLICK_LOGS) && !prefs.contains(KEY_CLICK_LOG_THROTTLE_COUNTS))
        ) { "旧点击日志已写入 Room，但 SharedPreferences 清理失败" }
        return quarantined
    }

    private fun dayStartMillis(timeMillis: Long, dayMs: Long): Long {
        return Math.floorDiv(timeMillis, dayMs) * dayMs
    }
}
