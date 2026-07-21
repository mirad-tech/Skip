package com.example.skip.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkipDatabaseInstrumentedTest {
    private lateinit var database: SkipDatabase
    private lateinit var dao: LogDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SkipDatabase::class.java).build()
        dao = database.logDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun clickLogsReplaceRecentDuplicateAndRetainNewestRows() = runBlocking {
        dao.storeClickLog(entity(1_000L, "a"), 0L, 0L, 3)
        dao.storeClickLog(entity(2_000L, "b"), 0L, 0L, 3)
        dao.storeClickLog(entity(3_000L, "c"), 0L, 0L, 3)
        dao.storeClickLog(entity(4_000L, "d"), 0L, 0L, 3)

        assertEquals(listOf(4_000L, 3_000L, 2_000L), dao.getClickLogs(10).map { it.timeMillis })

        dao.storeClickLog(
            entity(timeMillis = 4_500L, payload = "replacement", dedupeKey = "d"),
            duplicateCutoffMillis = 0L,
            expiryCutoffMillis = 0L,
            maxCount = 3
        )

        val logs = dao.getClickLogs(10)
        assertEquals(3, logs.size)
        assertEquals(1, logs.count { it.packageName == "com.example" && it.ruleId == "d" })
        assertEquals("replacement", logs.first().payloadJson)
    }

    @Test
    fun throttleCountsAccumulateByDayAndPruneOlderDays() = runBlocking {
        val day = 10L * DAY_MS
        dao.addThrottleCounts(
            counts = listOf(throttle(day - 7L * DAY_MS, 9L), throttle(day, 2L)),
            cutoffDayStartMillis = day - 6L * DAY_MS
        )
        dao.addThrottleCounts(
            counts = listOf(throttle(day, 3L)),
            cutoffDayStartMillis = day - 6L * DAY_MS
        )

        val rows = dao.getThrottleCounts(day - 6L * DAY_MS)
        assertEquals(1, rows.size)
        assertEquals(day, rows.single().dayStartMillis)
        assertEquals(5L, rows.single().count)
    }

    @Test
    fun clickLogWritePrunesRowsOlderThanRetentionCutoff() = runBlocking {
        dao.insertClickLog(entity(1_000L, "expired"))

        dao.storeClickLog(
            entity(8_000L, "current"),
            duplicateCutoffMillis = 3_000L,
            expiryCutoffMillis = 2_000L,
            maxCount = 1_000
        )

        assertEquals(listOf("current"), dao.getClickLogs(10).map { it.payloadJson })
    }

    @Test
    fun legacyMigrationMarkerMakesImportIdempotent() = runBlocking {
        val day = 10L * DAY_MS
        val first = dao.migrateLegacyOnce(
            migrationKey = "legacy_v1",
            logs = listOf(entity(1_000L, "legacy")),
            throttleCounts = listOf(throttle(day, 4L)),
            clickLogExpiryCutoffMillis = 0L,
            throttleCutoffDayStartMillis = day - 6L * DAY_MS,
            maxClickLogCount = 1_000
        )
        val second = dao.migrateLegacyOnce(
            migrationKey = "legacy_v1",
            logs = listOf(entity(2_000L, "should_not_import")),
            throttleCounts = listOf(throttle(day, 4L)),
            clickLogExpiryCutoffMillis = 0L,
            throttleCutoffDayStartMillis = day - 6L * DAY_MS,
            maxClickLogCount = 1_000
        )

        assertTrue(first)
        assertFalse(second)
        assertEquals(listOf("legacy"), dao.getClickLogs(10).map { it.payloadJson })
        assertEquals(4L, dao.getThrottleCounts(day).single().count)
    }

    @Test
    fun clearRemovesLogsAndThrottleCounts() = runBlocking {
        val day = 10L * DAY_MS
        dao.storeClickLog(entity(1_000L, "log"), 0L, 0L, 1_000)
        dao.addThrottleCounts(listOf(throttle(day, 1L)), day - 6L * DAY_MS)

        dao.clearAllLogsAndCounts()

        assertTrue(dao.getClickLogs(10).isEmpty())
        assertTrue(dao.getThrottleCounts(day - 6L * DAY_MS).isEmpty())
    }

    private fun entity(
        timeMillis: Long,
        payload: String,
        dedupeKey: String = payload
    ): ClickLogEntity {
        return ClickLogEntity(
            storageKey = "$timeMillis-$payload",
            timeMillis = timeMillis,
            packageName = "com.example",
            ruleId = dedupeKey,
            stage = "no_candidate_found",
            failureReason = "no_candidate_found",
            payloadJson = payload
        )
    }

    private fun throttle(dayStartMillis: Long, count: Long): ClickLogThrottleCountEntity {
        return ClickLogThrottleCountEntity(
            dayStartMillis = dayStartMillis,
            reasonKey = "com.example|no_candidate_found|no_candidate_found",
            count = count,
            updatedAtMillis = dayStartMillis
        )
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
