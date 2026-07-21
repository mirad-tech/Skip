package com.example.skip.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface LogDao {
    @Query("SELECT * FROM click_logs ORDER BY timeMillis DESC, id DESC LIMIT :limit")
    suspend fun getClickLogs(limit: Int): List<ClickLogEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClickLog(entity: ClickLogEntity): Long

    @Query(
        """
        DELETE FROM click_logs
        WHERE packageName = :packageName
          AND ruleId = :ruleId
          AND stage = :stage
          AND failureReason = :failureReason
          AND timeMillis >= :duplicateCutoffMillis
        """
    )
    suspend fun deleteRecentDuplicate(
        packageName: String,
        ruleId: String,
        stage: String,
        failureReason: String,
        duplicateCutoffMillis: Long
    )

    @Query("DELETE FROM click_logs WHERE timeMillis < :cutoffMillis")
    suspend fun deleteExpiredClickLogs(cutoffMillis: Long)

    @Query(
        """
        DELETE FROM click_logs
        WHERE id IN (
            SELECT id FROM click_logs
            ORDER BY timeMillis DESC, id DESC
            LIMIT -1 OFFSET :maxCount
        )
        """
    )
    suspend fun trimClickLogs(maxCount: Int)

    @Query("DELETE FROM click_logs")
    suspend fun clearClickLogs()

    @Transaction
    suspend fun storeClickLog(
        entity: ClickLogEntity,
        duplicateCutoffMillis: Long,
        expiryCutoffMillis: Long,
        maxCount: Int
    ) {
        deleteExpiredClickLogs(expiryCutoffMillis)
        deleteRecentDuplicate(
            packageName = entity.packageName,
            ruleId = entity.ruleId,
            stage = entity.stage,
            failureReason = entity.failureReason,
            duplicateCutoffMillis = duplicateCutoffMillis
        )
        insertClickLog(entity)
        trimClickLogs(maxCount)
    }

    @Query(
        """
        SELECT dayStartMillis, reasonKey, count
        FROM click_log_throttle_counts
        WHERE dayStartMillis >= :cutoffDayStartMillis
        ORDER BY dayStartMillis ASC, reasonKey ASC
        """
    )
    suspend fun getThrottleCounts(cutoffDayStartMillis: Long): List<ClickLogThrottleCountRow>

    @Query(
        """
        UPDATE click_log_throttle_counts
        SET count = count + :delta,
            updatedAtMillis = :updatedAtMillis
        WHERE dayStartMillis = :dayStartMillis AND reasonKey = :reasonKey
        """
    )
    suspend fun addToThrottleCount(
        dayStartMillis: Long,
        reasonKey: String,
        delta: Long,
        updatedAtMillis: Long
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertThrottleCount(entity: ClickLogThrottleCountEntity): Long

    @Query("DELETE FROM click_log_throttle_counts WHERE dayStartMillis < :cutoffDayStartMillis")
    suspend fun deleteExpiredThrottleCounts(cutoffDayStartMillis: Long)

    @Query("DELETE FROM click_log_throttle_counts")
    suspend fun clearThrottleCounts()

    @Transaction
    suspend fun clearAllLogsAndCounts() {
        clearClickLogs()
        clearThrottleCounts()
    }

    @Query("SELECT value FROM storage_metadata WHERE `key` = :key LIMIT 1")
    suspend fun getMetadata(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMetadata(entity: StorageMetadataEntity)

    @Transaction
    suspend fun addThrottleCounts(
        counts: List<ClickLogThrottleCountEntity>,
        cutoffDayStartMillis: Long
    ) {
        counts.forEach { entity ->
            val updated = addToThrottleCount(
                dayStartMillis = entity.dayStartMillis,
                reasonKey = entity.reasonKey,
                delta = entity.count,
                updatedAtMillis = entity.updatedAtMillis
            )
            if (updated == 0) insertThrottleCount(entity)
        }
        deleteExpiredThrottleCounts(cutoffDayStartMillis)
    }

    @Transaction
    suspend fun migrateLegacyOnce(
        migrationKey: String,
        logs: List<ClickLogEntity>,
        throttleCounts: List<ClickLogThrottleCountEntity>,
        clickLogExpiryCutoffMillis: Long,
        throttleCutoffDayStartMillis: Long,
        maxClickLogCount: Int
    ): Boolean {
        if (getMetadata(migrationKey) != null) return false
        logs.forEach { insertClickLog(it) }
        deleteExpiredClickLogs(clickLogExpiryCutoffMillis)
        trimClickLogs(maxClickLogCount)
        addThrottleCounts(throttleCounts, throttleCutoffDayStartMillis)
        putMetadata(StorageMetadataEntity(migrationKey, "complete"))
        return true
    }
}
