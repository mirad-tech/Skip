package com.example.skip.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.data.db.SkipDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogRepositoryInstrumentedTest {
    @Test
    fun corruptLegacyLogsAreQuarantinedWithoutBlockingStorage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SkipDatabase.resetForTest()
        context.deleteDatabase("skip_logs.db")
        val prefs = SettingsRepository.prefs(context)
        prefs.edit()
            .putString("click_logs", "[{\"packageName\":\"com.example\"}]")
            .putString("click_log_throttle_counts", "{\"no_candidate_found\":2}")
            .remove("click_logs_quarantine_v1")
            .remove("click_log_throttle_counts_quarantine_v1")
            .remove("legacy_log_quarantine_meta_v1")
            .commit()
        LogRepository.resetStorageStateForTest()

        LogRepository.awaitLegacyMigration(context)

        assertTrue(prefs.contains("click_logs_quarantine_v1"))
        assertTrue(prefs.contains("legacy_log_quarantine_meta_v1"))
        assertTrue(!prefs.contains("click_logs"))
        assertTrue(!prefs.contains("click_log_throttle_counts"))
        assertTrue(LogRepository.storageState.value is LogStorageState.Ready)
        assertTrue(LogRepository.getClickLogThrottleCounts(context).values.sum() >= 2)
    }

    @Test
    fun addClearAddIsLinearizedAndThrottleCanBeForcedToDisk() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LogRepository.awaitLegacyMigration(context)
        LogRepository.clearClickLogs(context)
        try {
            val now = System.currentTimeMillis()
            LogRepository.addClickLog(context, log(now, ClickLogStage.ClickActionSuccess, "before_clear"))
            LogRepository.clearClickLogs(context)
            LogRepository.addClickLog(context, log(now + 1L, ClickLogStage.ClickActionSuccess, "after_clear"))

            val logs = LogRepository.getClickLogs(context)
            assertEquals(listOf("after_clear"), logs.map { it.failureReason })

            LogRepository.addClickLog(context, log(now + 2L, ClickLogStage.NoCandidateFound, "no_candidate_found"))
            LogRepository.addClickLog(context, log(now + 3L, ClickLogStage.NoCandidateFound, "no_candidate_found"))
            LogRepository.flushPendingWrites(context)

            val throttleCounts = LogRepository.getClickLogThrottleCounts(context)
            assertTrue(throttleCounts.values.sum() >= 1)
        } finally {
            LogRepository.clearClickLogs(context)
        }
    }

    private fun log(timeMillis: Long, stage: ClickLogStage, reason: String): ClickLog {
        return ClickLog(
            timeMillis = timeMillis,
            packageName = "com.example.persistence.test",
            ruleId = "test_rule",
            stage = stage,
            failureReason = reason
        )
    }
}
