package com.example.skip.data

import android.content.Context
import android.os.SystemClock
import com.example.skip.data.db.ClickLogEntity
import com.example.skip.data.db.ClickLogThrottleCountEntity
import com.example.skip.data.db.SkipDatabase
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.RuleLog
import com.example.skip.util.PrivacySanitizer
import com.example.skip.util.RomUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class ClickLogPersistencePayload(
    val logsJson: String,
    val throttleCountsJson: String
)

data class ClickLogThrottleSummary(
    val counts: Map<String, Int>,
    val rangeStartMillis: Long?,
    val rangeEndMillis: Long?
)

private data class ThrottleBucketKey(
    val dayStartMillis: Long,
    val reasonKey: String
)

internal enum class LogStorageErrorCode(val value: String) {
    LegacyClickLogsCorrupt("legacy_click_logs_corrupt"),
    LegacyThrottleCountsCorrupt("legacy_throttle_counts_corrupt"),
    QuarantineWriteFailed("quarantine_write_failed"),
    DatabaseUnavailable("database_unavailable"),
    ClickLogWriteFailed("click_log_write_failed"),
    ThrottleWriteFailed("throttle_write_failed")
}

internal sealed interface LogStorageState {
    data object Initializing : LogStorageState
    data class Ready(val legacyDataQuarantined: Boolean = false) : LogStorageState
    data class Degraded(
        val errorCode: LogStorageErrorCode,
        val retryAtMillis: Long?
    ) : LogStorageState
}

data class LogStorageDiagnosticSnapshot(
    val state: String,
    val pendingWriteCount: Int,
    val droppedPendingWriteCount: Long,
    val lastErrorCode: String
)

private data class PendingClickLogWrite(
    val log: ClickLog,
    val entity: ClickLogEntity,
    val duplicateCutoffMillis: Long,
    val enqueuedAtMillis: Long,
    val generation: Long
)

private data class PendingWriteDiagnostic(
    val pendingWriteCount: Int = 0,
    val droppedPendingWriteCount: Long = 0L
)


object LogRepository {
    private const val MAX_CLICK_LOG_COUNT = 1000
    private const val MAX_LOG_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val DUPLICATE_WINDOW_MS = 5000L
    private const val SAFETY_EVENT_WINDOW_MS = 30_000L
    private const val THROTTLE_FLUSH_DELAY_MS = 30_000L
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val LEGACY_MIGRATION_RETRY_BACKOFF_MS = 30_000L
    private const val STORAGE_READY_TIMEOUT_MS = 5_000L
    private const val MAX_PENDING_CLICK_WRITES = 1_000
    private val PENDING_WRITE_RETRY_DELAYS_MS = longArrayOf(500L, 2_000L, 10_000L, 30_000L)
    private val internalReasonCode = Regex("^[a-z0-9_\\-;]+$")

    internal object JsonNullValue
    private val lock = Any()
    private val clickLogBuffer = ClickLogBuffer(
        maxCount = MAX_CLICK_LOG_COUNT,
        maxAgeMs = MAX_LOG_AGE_MS,
        duplicateWindowMs = DUPLICATE_WINDOW_MS
    )
    private val rateLimiter = ClickLogRateLimiter(safetyWindowMs = SAFETY_EVENT_WINDOW_MS)
    private val pendingThrottleCounts = linkedMapOf<ThrottleBucketKey, Long>()
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val persistenceQueue = ClickLogPersistenceQueue(writeScope)
    private val migrationStateLock = Any()
    private var migrationAttempt: CompletableDeferred<Result<Unit>>? = null
    private var migrationFailedAtElapsedMillis = 0L
    private var migrationCompleted = false
    private var legacyDataQuarantined = false
    private var migrationRetryJob: Job? = null
    private val mutableStorageState = MutableStateFlow<LogStorageState>(LogStorageState.Initializing)
    internal val storageState: StateFlow<LogStorageState> = mutableStorageState.asStateFlow()
    private val mutablePendingWriteDiagnostic = MutableStateFlow(PendingWriteDiagnostic())
    internal val storageDiagnosticState: StateFlow<LogStorageDiagnosticSnapshot> = combine(
        storageState,
        mutablePendingWriteDiagnostic
    ) { state, pending ->
        state.toDiagnosticSnapshot(pending)
    }.stateIn(
        scope = writeScope,
        started = SharingStarted.Eagerly,
        initialValue = LogStorageState.Initializing.toDiagnosticSnapshot(PendingWriteDiagnostic())
    )
    private var throttleFlushJob: Job? = null
    private var throttleGeneration = 0L
    private val pendingClickWrites = ArrayDeque<PendingClickLogWrite>()
    private var pendingDrainJob: Job? = null
    private var pendingDrainRetryIndex = 0
    private var pendingWriteGeneration = 0L
    private var droppedPendingWriteCount = 0L

    fun start(context: Context) {
        ensureMigrationStarted(context.applicationContext)
    }

    fun retryStorageNow(context: Context) {
        synchronized(migrationStateLock) {
            migrationRetryJob?.cancel()
            migrationRetryJob = null
            if (migrationAttempt?.isCompleted == true && !migrationCompleted) {
                migrationAttempt = null
                migrationFailedAtElapsedMillis = 0L
            }
        }
        ensureMigrationStarted(context.applicationContext, force = true)
        schedulePendingDrain(context.applicationContext, immediate = true)
    }

    suspend fun awaitLegacyMigration(context: Context) {
        val result = withTimeoutOrNull(STORAGE_READY_TIMEOUT_MS) {
            ensureMigrationStarted(context.applicationContext).await()
        } ?: throw IllegalStateException("日志存储初始化超时")
        result.getOrThrow()
    }

    internal fun getStorageDiagnosticSnapshot(): LogStorageDiagnosticSnapshot {
        val state = storageState.value
        return synchronized(lock) {
            state.toDiagnosticSnapshot(
                PendingWriteDiagnostic(
                    pendingWriteCount = pendingClickWrites.size,
                    droppedPendingWriteCount = droppedPendingWriteCount
                )
            )
        }
    }

    internal fun resetStorageStateForTest() {
        synchronized(migrationStateLock) {
            migrationRetryJob?.cancel()
            migrationRetryJob = null
            migrationAttempt = null
            migrationFailedAtElapsedMillis = 0L
            migrationCompleted = false
            legacyDataQuarantined = false
            mutableStorageState.value = LogStorageState.Initializing
        }
        synchronized(lock) {
            pendingDrainJob?.cancel()
            pendingDrainJob = null
            throttleFlushJob?.cancel()
            throttleFlushJob = null
            pendingClickWrites.clear()
            pendingThrottleCounts.clear()
            clickLogBuffer.clear()
            pendingDrainRetryIndex = 0
            pendingWriteGeneration += 1L
            throttleGeneration += 1L
            droppedPendingWriteCount = 0L
            rateLimiter.reset()
            publishPendingWriteDiagnosticLocked()
        }
    }

    fun addClickLog(context: Context, log: ClickLog) {
        if (log.stage.isDebugOnly && !SettingsRepository.isDebugToastEnabled(context)) return
        val now = System.currentTimeMillis()
        val cleaned = log.copy(
            nodeText = PrivacySanitizer.sanitizeText(log.nodeText),
            contentDescription = PrivacySanitizer.sanitizeText(log.contentDescription),
            clickedNodeText = PrivacySanitizer.sanitizeText(log.clickedNodeText),
            reason = sanitizeDiagnosticReason(log.reason),
            failureReason = sanitizeDiagnosticReason(log.failureReason),
            detail = PrivacySanitizer.sanitizeText(log.detail),
            blockedReason = sanitizeDiagnosticReason(log.blockedReason),
            effectConfirmReason = sanitizeDiagnosticReason(log.effectConfirmReason),
            rescanReason = sanitizeDiagnosticReason(log.rescanReason)
        )
        val appContext = context.applicationContext
        var throttleGenerationAtEvent: Long? = null
        var shouldDrain = false
        synchronized(lock) {
            val rateLimit = rateLimiter.shouldStore(cleaned, now)
            if (rateLimit.allowed) {
                clickLogBuffer.add(cleaned, now)
                val entity = ClickLogEntityMapper.toEntity(cleaned)
                if (pendingClickWrites.size >= MAX_PENDING_CLICK_WRITES) {
                    pendingClickWrites.removeFirst()
                    droppedPendingWriteCount++
                }
                pendingClickWrites.addLast(
                    PendingClickLogWrite(
                        log = cleaned,
                        entity = entity,
                        duplicateCutoffMillis = now - DUPLICATE_WINDOW_MS,
                        enqueuedAtMillis = now,
                        generation = pendingWriteGeneration
                    )
                )
                publishPendingWriteDiagnosticLocked()
                shouldDrain = true
            } else {
                val bucket = ThrottleBucketKey(dayStartMillis(now), rateLimit.throttleAggregateKey)
                pendingThrottleCounts[bucket] = (pendingThrottleCounts[bucket] ?: 0L) + 1L
                throttleGenerationAtEvent = throttleGeneration
            }
        }
        throttleGenerationAtEvent?.let { generation ->
            scheduleThrottleFlush(appContext, generation)
        }
        if (shouldDrain) schedulePendingDrain(appContext, immediate = true)
    }

    suspend fun getClickLogs(context: Context): List<ClickLog> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val ready = runCatching { awaitLegacyMigration(appContext) }.isSuccess
        if (!ready) return@withContext getCachedClickLogs()
        runCatching { persistenceQueue.execute {
            val dao = SkipDatabase.get(appContext).logDao()
            dao.deleteExpiredClickLogs(System.currentTimeMillis() - MAX_LOG_AGE_MS)
            dao.trimClickLogs(MAX_CLICK_LOG_COUNT)
            val databaseLogs = dao.getClickLogs(MAX_CLICK_LOG_COUNT).map { entity ->
                ClickLogCodec.fromJson(JSONObject(entity.payloadJson), strict = true)
            }
            synchronized(lock) {
                val pending = pendingClickWrites.map { it.log }
                val merged = (databaseLogs + pending)
                    .distinctBy { ClickLogEntityMapper.toEntity(it).storageKey }
                    .sortedByDescending(ClickLog::timeMillis)
                    .take(MAX_CLICK_LOG_COUNT)
                clickLogBuffer.replaceAll(merged)
                merged
            }
        } }.getOrElse {
            markStorageDegraded(LogStorageErrorCode.DatabaseUnavailable)
            getCachedClickLogs()
        }
    }

    internal fun getCachedClickLogs(): List<ClickLog> {
        return synchronized(lock) { clickLogBuffer.snapshot() }
    }

    suspend fun getClickLogThrottleCounts(context: Context): Map<String, Int> {
        return getClickLogThrottleSummary(context).counts
    }

    suspend fun getClickLogThrottleSummary(context: Context): ClickLogThrottleSummary =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val ready = runCatching { awaitLegacyMigration(appContext) }.isSuccess
            if (!ready) return@withContext cachedThrottleSummary()
            runCatching { persistenceQueue.execute {
                val generation = synchronized(lock) { throttleGeneration }
                flushPendingThrottleCountsInQueue(appContext, generation)
                val currentDay = dayStartMillis(System.currentTimeMillis())
                val rows = SkipDatabase.get(appContext).logDao()
                    .getThrottleCounts(currentDay - 6L * DAY_MS)
                val counts = rows.groupBy { it.reasonKey }.mapValues { (_, values) ->
                    values.sumOf { it.count }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                ClickLogThrottleSummary(
                    counts = counts,
                    rangeStartMillis = rows.minOfOrNull { it.dayStartMillis },
                    rangeEndMillis = rows.maxOfOrNull { it.dayStartMillis + DAY_MS - 1L }
                )
            } }.getOrElse {
                markStorageDegraded(LogStorageErrorCode.DatabaseUnavailable)
                cachedThrottleSummary()
            }
        }

    private fun cachedThrottleSummary(): ClickLogThrottleSummary {
        val counts = synchronized(lock) {
            pendingThrottleCounts.entries
                .groupBy { it.key.reasonKey }
                .mapValues { (_, entries) ->
                    entries.sumOf { it.value }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
        }
        return ClickLogThrottleSummary(counts, null, null)
    }

    internal fun deserializeClickLogPersistence(raw: String): List<ClickLog> =
        ClickLogCodec.deserializeClickLogPersistence(raw)

    suspend fun clearClickLogs(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        awaitLegacyMigration(appContext)
        lateinit var clearResult: Deferred<Result<Unit>>
        val staleJobs = synchronized(lock) {
            throttleGeneration += 1L
            pendingWriteGeneration += 1L
            pendingClickWrites.clear()
            pendingThrottleCounts.clear()
            rateLimiter.reset()
            publishPendingWriteDiagnosticLocked()
            val staleThrottleJob = throttleFlushJob.also { throttleFlushJob = null }
            val staleDrainJob = pendingDrainJob.also { pendingDrainJob = null }
            clearResult = persistenceQueue.enqueueCatching {
                SkipDatabase.get(appContext).logDao().clearAllLogsAndCounts()
                synchronized(lock) { clickLogBuffer.clear() }
            }
            listOfNotNull(staleThrottleJob, staleDrainJob)
        }
        staleJobs.forEach(Job::cancel)
        clearResult.await().getOrThrow()
    }

    fun flushPendingWritesAsync(context: Context) {
        val appContext = context.applicationContext
        schedulePendingDrain(appContext, immediate = true)
        writeScope.launch { runCatching { flushPendingWrites(appContext) } }
    }

    suspend fun flushPendingWrites(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        awaitLegacyMigration(appContext)
        schedulePendingDrain(appContext, immediate = true)
        val drainJob = synchronized(lock) { pendingDrainJob }
        val drained = withTimeoutOrNull(STORAGE_READY_TIMEOUT_MS) {
            drainJob?.join()
            synchronized(lock) { pendingClickWrites.isEmpty() }
        } == true
        check(drained) { "点击日志仍有待写数据" }
        lateinit var flushResult: Deferred<Result<Unit>>
        synchronized(lock) {
            val generation = throttleGeneration
            flushResult = persistenceQueue.enqueueCatching {
                flushPendingThrottleCountsInQueue(appContext, generation)
            }
        }
        flushResult.await().getOrThrow()
    }

    suspend fun exportClickLogsAsJson(
        context: Context,
        versionName: String,
        logs: List<ClickLog>? = null
    ): String {
        val exportLogs = logs ?: getClickLogs(context)
        val deviceInfo = RomUtils.getDeviceInfo()
        return JSONObject()
            .put("exportTime", Instant.ofEpochMilli(System.currentTimeMillis()).toString())
            .put("skipVersion", versionName)
            .put("device", JSONObject()
                .put("brand", deviceInfo.brand)
                .put("manufacturer", deviceInfo.manufacturer)
                .put("model", deviceInfo.model)
                .put("androidVersion", deviceInfo.androidVersion)
                .put("sdkInt", deviceInfo.sdkInt)
                .put("rom", deviceInfo.romType.label)
            )
            .put("events", JSONArray().apply {
                exportLogs.forEach { put(ClickLogCodec.toExportJson(it)) }
            })
            .toString(2)
    }

    internal fun serializeClickLogPersistence(
        logs: List<ClickLog>,
        throttleCounts: Map<String, Int>
    ): ClickLogPersistencePayload = ClickLogCodec.serializeClickLogPersistence(logs, throttleCounts)

    fun addRuleLog(context: Context, log: RuleLog) = RuleLogRepository.addRuleLog(context, log)

    fun getRuleLogs(context: Context): List<RuleLog> = RuleLogRepository.getRuleLogs(context)

    fun clearRuleLogs(context: Context) = RuleLogRepository.clearRuleLogs(context)

    internal fun clickLogToJson(log: ClickLog): JSONObject = ClickLogCodec.clickLogToJson(log)

    internal fun isSuccessfulHit(log: ClickLog): Boolean {
        return log.success == true || log.stage == ClickLogStage.ClickEffectConfirmed
    }

    internal fun isFailureHit(log: ClickLog): Boolean {
        return log.success == false ||
            log.stage == ClickLogStage.ClickFailed ||
            log.stage == ClickLogStage.ClickEffectUnknown
    }

    internal fun isSafetyBlockedHit(log: ClickLog): Boolean {
        return log.blockedBySafety ||
            log.clickSkippedBySafetyMode ||
            log.stage == ClickLogStage.SkippedBySafety ||
            log.stage == ClickLogStage.ClickSkippedBySafetyMode ||
            log.blockedReason == "blocked_by_safety_policy" ||
            log.blockedReason.contains("safety", ignoreCase = true)
    }

    internal fun isCoordinateFallbackHit(log: ClickLog): Boolean {
        return log.clickTargetSource == ClickTargetSourceLog.CoordinateFallback || log.isFixedCoordinateClick
    }

    internal fun clickLogJsonFields(log: ClickLog): Map<String, Any> =
        ClickLogCodec.clickLogJsonFields(log)

    internal fun deserializeThrottleCounts(raw: String): Map<String, Int> =
        ClickLogCodec.deserializeThrottleCounts(raw)

    private fun ensureMigrationStarted(
        context: Context,
        force: Boolean = false
    ): Deferred<Result<Unit>> {
        synchronized(migrationStateLock) {
            migrationAttempt?.let { currentAttempt ->
                if (migrationCompleted || !currentAttempt.isCompleted) return currentAttempt
                if (LegacyMigrationRetryPolicy.shouldReuseAttempt(
                        failedAtElapsedMillis = migrationFailedAtElapsedMillis,
                        nowElapsedMillis = SystemClock.elapsedRealtime(),
                        retryBackoffMs = LEGACY_MIGRATION_RETRY_BACKOFF_MS
                    ) && !force
                ) {
                    return currentAttempt
                }
                migrationAttempt = null
                migrationFailedAtElapsedMillis = 0L
            }
            val attempt = CompletableDeferred<Result<Unit>>()
            migrationAttempt = attempt
            mutableStorageState.value = LogStorageState.Initializing
            val job = persistenceQueue.enqueue {
                var quarantined = false
                val result = runCatching {
                    quarantined = LegacyLogMigrator.migrate(
                        context = context,
                        maxLogAgeMs = MAX_LOG_AGE_MS,
                        dayMs = DAY_MS,
                        maxClickLogCount = MAX_CLICK_LOG_COUNT
                    )
                }
                synchronized(migrationStateLock) {
                    if (migrationAttempt === attempt) {
                        migrationFailedAtElapsedMillis = if (result.isFailure) {
                            SystemClock.elapsedRealtime()
                        } else {
                            0L
                        }
                        migrationCompleted = result.isSuccess
                        legacyDataQuarantined = legacyDataQuarantined || quarantined
                        mutableStorageState.value = if (result.isSuccess) {
                            LogStorageState.Ready(legacyDataQuarantined)
                        } else {
                            val code = if (result.exceptionOrNull() is QuarantineWriteException) {
                                LogStorageErrorCode.QuarantineWriteFailed
                            } else {
                                LogStorageErrorCode.DatabaseUnavailable
                            }
                            LogStorageState.Degraded(
                                errorCode = code,
                                retryAtMillis = System.currentTimeMillis() + LEGACY_MIGRATION_RETRY_BACKOFF_MS
                            )
                        }
                    }
                }
                attempt.complete(result)
                if (result.isSuccess) {
                    schedulePendingDrain(context, immediate = true)
                } else {
                    scheduleMigrationRetry(context)
                }
            }
            job.invokeOnCompletion { error ->
                if (!attempt.isCompleted && error != null) {
                    synchronized(migrationStateLock) {
                        if (migrationAttempt === attempt) {
                            migrationFailedAtElapsedMillis = SystemClock.elapsedRealtime()
                            migrationCompleted = false
                            mutableStorageState.value = LogStorageState.Degraded(
                                LogStorageErrorCode.DatabaseUnavailable,
                                System.currentTimeMillis() + LEGACY_MIGRATION_RETRY_BACKOFF_MS
                            )
                        }
                    }
                    attempt.complete(Result.failure(error))
                    scheduleMigrationRetry(context)
                }
            }
            return attempt
        }
    }

    private fun scheduleMigrationRetry(context: Context) {
        synchronized(migrationStateLock) {
            if (migrationCompleted || migrationRetryJob?.isActive == true) return
            migrationRetryJob = writeScope.launch {
                delay(LEGACY_MIGRATION_RETRY_BACKOFF_MS)
                synchronized(migrationStateLock) {
                    migrationRetryJob = null
                    if (migrationAttempt?.isCompleted == true && !migrationCompleted) {
                        migrationAttempt = null
                        migrationFailedAtElapsedMillis = 0L
                    }
                }
                ensureMigrationStarted(context, force = true)
            }
        }
    }

    private fun schedulePendingDrain(context: Context, immediate: Boolean) {
        lateinit var job: Job
        synchronized(lock) {
            if (pendingClickWrites.isEmpty() || pendingDrainJob?.isActive == true) return
            val generation = pendingWriteGeneration
            val retryDelay = if (immediate) 0L else {
                PENDING_WRITE_RETRY_DELAYS_MS[
                    pendingDrainRetryIndex.coerceIn(PENDING_WRITE_RETRY_DELAYS_MS.indices)
                ]
            }
            job = writeScope.launch {
                if (retryDelay > 0L) delay(retryDelay)
                val result = runCatching { drainPendingWrites(context, generation) }
                val shouldRetry = synchronized(lock) {
                    if (pendingDrainJob === job) pendingDrainJob = null
                    if (result.isSuccess) {
                        pendingDrainRetryIndex = 0
                    } else {
                        pendingDrainRetryIndex = (pendingDrainRetryIndex + 1)
                            .coerceAtMost(PENDING_WRITE_RETRY_DELAYS_MS.lastIndex)
                    }
                    generation == pendingWriteGeneration && pendingClickWrites.isNotEmpty()
                }
                if (result.isFailure) {
                    val errorCode = synchronized(migrationStateLock) {
                        if (migrationCompleted) {
                            LogStorageErrorCode.ClickLogWriteFailed
                        } else {
                            LogStorageErrorCode.DatabaseUnavailable
                        }
                    }
                    markStorageDegraded(errorCode)
                } else if (!shouldRetry) {
                    markStorageReady()
                }
                if (shouldRetry) schedulePendingDrain(context, immediate = false)
            }
            pendingDrainJob = job
        }
    }

    private suspend fun drainPendingWrites(context: Context, generation: Long) {
        awaitLegacyMigration(context)
        while (true) {
            val queuedWrite = synchronized(lock) {
                if (generation != pendingWriteGeneration) return
                val pending = pendingClickWrites.firstOrNull() ?: return
                pending to persistenceQueue.enqueueCatching {
                    SkipDatabase.get(context).logDao().storeClickLog(
                        entity = pending.entity,
                        duplicateCutoffMillis = pending.duplicateCutoffMillis,
                        expiryCutoffMillis = System.currentTimeMillis() - MAX_LOG_AGE_MS,
                        maxCount = MAX_CLICK_LOG_COUNT
                    )
                }
            }
            val pending = queuedWrite.first
            queuedWrite.second.await().getOrThrow()
            synchronized(lock) {
                if (generation != pendingWriteGeneration) return
                val head = pendingClickWrites.firstOrNull()
                if (head?.entity?.storageKey == pending.entity.storageKey) {
                    pendingClickWrites.removeFirst()
                    publishPendingWriteDiagnosticLocked()
                }
            }
        }
    }

    private fun markStorageDegraded(errorCode: LogStorageErrorCode) {
        synchronized(migrationStateLock) {
            mutableStorageState.value = LogStorageState.Degraded(
                errorCode = errorCode,
                retryAtMillis = System.currentTimeMillis() + LEGACY_MIGRATION_RETRY_BACKOFF_MS
            )
        }
    }

    private fun markStorageReady() {
        synchronized(migrationStateLock) {
            if (migrationCompleted) {
                mutableStorageState.value = LogStorageState.Ready(legacyDataQuarantined)
            }
        }
    }

    private fun publishPendingWriteDiagnosticLocked() {
        mutablePendingWriteDiagnostic.value = PendingWriteDiagnostic(
            pendingWriteCount = pendingClickWrites.size,
            droppedPendingWriteCount = droppedPendingWriteCount
        )
    }

    private fun LogStorageState.toDiagnosticSnapshot(
        pending: PendingWriteDiagnostic
    ): LogStorageDiagnosticSnapshot {
        return LogStorageDiagnosticSnapshot(
            state = when (this) {
                LogStorageState.Initializing -> "initializing"
                is LogStorageState.Ready -> "ready"
                is LogStorageState.Degraded -> "degraded"
            },
            pendingWriteCount = pending.pendingWriteCount,
            droppedPendingWriteCount = pending.droppedPendingWriteCount,
            lastErrorCode = (this as? LogStorageState.Degraded)?.errorCode?.value.orEmpty()
        )
    }

    private fun scheduleThrottleFlush(context: Context, generation: Long) {
        lateinit var job: Job
        synchronized(lock) {
            if (
                generation != throttleGeneration ||
                throttleFlushJob?.isActive == true ||
                pendingThrottleCounts.isEmpty()
            ) {
                return
            }
            job = writeScope.launch {
                delay(THROTTLE_FLUSH_DELAY_MS)
                val result = persistenceQueue.enqueueCatching {
                    flushPendingThrottleCountsInQueue(context, generation)
                }.await()
                if (result.isFailure) {
                    markStorageDegraded(LogStorageErrorCode.ThrottleWriteFailed)
                } else if (synchronized(lock) { pendingClickWrites.isEmpty() }) {
                    markStorageReady()
                }
                val reschedule = synchronized(lock) {
                    if (throttleFlushJob === job) throttleFlushJob = null
                    generation == throttleGeneration && pendingThrottleCounts.isNotEmpty()
                }
                if (reschedule) scheduleThrottleFlush(context, generation)
            }
            throttleFlushJob = job
        }
    }

    private suspend fun flushPendingThrottleCountsInQueue(context: Context, generation: Long) {
        val snapshot = synchronized(lock) {
            if (generation != throttleGeneration) return
            pendingThrottleCounts.toMap().also { pendingThrottleCounts.clear() }
        }
        if (snapshot.isEmpty()) return
        try {
            val now = System.currentTimeMillis()
            val currentDay = dayStartMillis(now)
            SkipDatabase.get(context).logDao().addThrottleCounts(
                counts = snapshot.map { (bucket, count) ->
                    ClickLogThrottleCountEntity(
                        dayStartMillis = bucket.dayStartMillis,
                        reasonKey = bucket.reasonKey,
                        count = count,
                        updatedAtMillis = now
                    )
                },
                cutoffDayStartMillis = currentDay - 6L * DAY_MS
            )
        } catch (error: Throwable) {
            synchronized(lock) {
                if (generation == throttleGeneration) {
                    snapshot.forEach { (bucket, count) ->
                        pendingThrottleCounts[bucket] = (pendingThrottleCounts[bucket] ?: 0L) + count
                    }
                }
            }
            throw error
        }
    }

    internal fun sanitizeDiagnosticReason(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.matches(internalReasonCode)) trimmed else PrivacySanitizer.sanitizeText(value)
    }

    private fun dayStartMillis(timeMillis: Long): Long {
        return Math.floorDiv(timeMillis, DAY_MS) * DAY_MS
    }


}
