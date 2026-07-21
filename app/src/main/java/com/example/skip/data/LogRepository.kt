package com.example.skip.data

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.example.skip.data.db.ClickLogEntity
import com.example.skip.data.db.ClickLogThrottleCountEntity
import com.example.skip.data.db.SkipDatabase
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.RuleLog
import com.example.skip.model.RuleSource
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

private class QuarantineWriteException : IllegalStateException("quarantine_write_failed")

object LogRepository {
    private const val KEY_CLICK_LOGS = "click_logs"
    private const val KEY_CLICK_LOG_THROTTLE_COUNTS = "click_log_throttle_counts"
    private const val KEY_CLICK_LOGS_QUARANTINE = "click_logs_quarantine_v1"
    private const val KEY_THROTTLE_COUNTS_QUARANTINE = "click_log_throttle_counts_quarantine_v1"
    private const val KEY_LEGACY_QUARANTINE_META = "legacy_log_quarantine_meta_v1"
    private const val KEY_RULE_LOGS = "rule_logs"
    private const val MAX_CLICK_LOG_COUNT = 1000
    private const val MAX_RULE_LOG_COUNT = 100
    private const val MAX_LOG_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val FIELD_SEPARATOR = "\t"
    private const val ROW_SEPARATOR = "\n"
    private const val DUPLICATE_WINDOW_MS = 5000L
    private const val SAFETY_EVENT_WINDOW_MS = 30_000L
    private const val THROTTLE_FLUSH_DELAY_MS = 30_000L
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val LEGACY_MIGRATION_KEY = "shared_preferences_click_logs_v1"
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
            LogStorageDiagnosticSnapshot(
                state = when (state) {
                    LogStorageState.Initializing -> "initializing"
                    is LogStorageState.Ready -> "ready"
                    is LogStorageState.Degraded -> "degraded"
                },
                pendingWriteCount = pendingClickWrites.size,
                droppedPendingWriteCount = droppedPendingWriteCount,
                lastErrorCode = (state as? LogStorageState.Degraded)?.errorCode?.value.orEmpty()
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
                val entity = cleaned.toEntity()
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
                JSONObject(entity.payloadJson).toClickLog(strict = true)
            }
            synchronized(lock) {
                val pending = pendingClickWrites.map { it.log }
                val merged = (databaseLogs + pending)
                    .distinctBy { it.toEntity().storageKey }
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

    internal fun deserializeClickLogPersistence(raw: String): List<ClickLog> {
        if (raw.isBlank()) return emptyList()
        return if (raw.trimStart().startsWith("[")) {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.opt(index)
                    require(item is JSONObject) { "损坏的旧点击日志：第 $index 项不是对象" }
                    add(item.toClickLog(strict = true))
                }
            }
        } else {
            raw.lineSequence()
                .filter(String::isNotBlank)
                .mapIndexed { index, row ->
                    row.deserializeLegacyClickLog()
                        ?: throw IllegalArgumentException("损坏的旧点击日志：第 ${index + 1} 行无法解析")
                }
                .toList()
        }
    }

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
                exportLogs.forEach { put(it.toExportJson()) }
            })
            .toString(2)
    }

    internal fun serializeClickLogPersistence(
        logs: List<ClickLog>,
        throttleCounts: Map<String, Int>
    ): ClickLogPersistencePayload {
        return ClickLogPersistencePayload(
            logsJson = JSONArray().apply {
                logs.forEach { put(it.toJson()) }
            }.toString(),
            throttleCountsJson = JSONObject().apply {
                throttleCounts.forEach { (key, count) -> put(key, count) }
            }.toString()
        )
    }

    fun addRuleLog(context: Context, log: RuleLog) {
        val logs = buildList {
            add(log)
            addAll(getRuleLogs(context))
        }.take(MAX_RULE_LOG_COUNT)

        SettingsRepository.prefs(context)
            .edit { putString(KEY_RULE_LOGS, logs.joinToString(ROW_SEPARATOR) { it.serialize() }) }
    }

    fun getRuleLogs(context: Context): List<RuleLog> {
        return SettingsRepository.prefs(context)
            .getString(KEY_RULE_LOGS, null)
            .orEmpty()
            .lineSequence()
            .mapNotNull { it.deserializeRuleLog() }
            .toList()
    }

    fun clearRuleLogs(context: Context) {
        SettingsRepository.prefs(context).edit { remove(KEY_RULE_LOGS) }
    }

    internal fun clickLogToJson(log: ClickLog): JSONObject {
        return log.toJson()
    }

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

    internal fun clickLogJsonFields(log: ClickLog): Map<String, Any> {
        return linkedMapOf(
            "timeMillis" to log.timeMillis,
            "packageName" to log.packageName,
            "appName" to log.appName,
            "activityName" to log.activityName,
            "ruleType" to log.ruleType,
            "ruleName" to log.ruleName,
            "ruleId" to log.ruleId,
            "ruleKind" to log.ruleKind,
            "planScope" to log.planScope,
            "effectiveRuleWindowMs" to nullableJsonValue(log.effectiveRuleWindowMs),
            "candidateRelocated" to log.candidateRelocated,
            "relocationMethod" to log.relocationMethod,
            "stage" to log.stage.value,
            "success" to nullableJsonValue(log.success),
            "reason" to log.reason,
            "failureReason" to log.failureReason,
            "detail" to log.detail,
            "eventType" to nullableJsonValue(log.eventType),
            "eventPackageName" to log.eventPackageName,
            "rootWindowNull" to log.rootWindowNull,
            "windowId" to nullableJsonValue(log.windowId),
            "rootChildCount" to nullableJsonValue(log.rootChildCount),
            "canRetrieveWindowContent" to log.canRetrieveWindowContent,
            "candidateCount" to nullableJsonValue(log.candidateCount),
            "bestCandidateScore" to nullableJsonValue(log.bestCandidateScore),
            "bestCandidateBounds" to log.bestCandidateBounds,
            "minScore" to nullableJsonValue(log.minScore),
            "matchedKeyword" to log.matchedKeyword,
            "nodeText" to log.nodeText,
            "contentDescription" to log.contentDescription,
            "viewIdResourceName" to log.viewIdResourceName,
            "boundsInScreen" to log.boundsInScreen,
            "nodeClickable" to nullableJsonValue(log.nodeClickable),
            "parentClickable" to nullableJsonValue(log.parentClickable),
            "score" to nullableJsonValue(log.score),
            "area" to log.area,
            "clickMethod" to log.clickMethod.value,
            "actionReturnValue" to nullableJsonValue(log.actionReturnValue),
            "clickResult" to nullableJsonValue(log.clickResult),
            "effectConfirmed" to nullableJsonValue(log.effectConfirmed),
            "delayBeforeClickMs" to nullableJsonValue(log.delayBeforeClickMs),
            "actualClickDelayMs" to nullableJsonValue(log.actualClickDelayMs),
            "callbackQueueDelayMs" to nullableJsonValue(log.callbackQueueDelayMs),
            "scanDurationMs" to nullableJsonValue(log.scanDurationMs),
            "rescanReason" to log.rescanReason,
            "retryCount" to log.retryCount,
            "deviceRom" to log.deviceRom,
            "elapsedSinceAppStartMs" to nullableJsonValue(log.elapsedSinceAppStartMs),
            "foregroundPackage" to log.foregroundPackage,
            "foregroundStartTimeMillis" to nullableJsonValue(log.foregroundStartTimeMillis),
            "elapsedSinceForegroundMs" to nullableJsonValue(log.elapsedSinceForegroundMs),
            "defaultRuleWindowMs" to nullableJsonValue(log.defaultRuleWindowMs),
            "isWithinDefaultRuleWindow" to nullableJsonValue(log.isWithinDefaultRuleWindow),
            "ruleScope" to log.ruleScope,
            "timeWindowDecision" to log.timeWindowDecision,
            "isSystemPackage" to log.isSystemPackage,
            "isLauncherPackage" to log.isLauncherPackage,
            "isSelfPackage" to log.isSelfPackage,
            "isSelfAppLabelCandidate" to log.isSelfAppLabelCandidate,
            "blockedBySafety" to log.blockedBySafety,
            "blockedReason" to log.blockedReason,
            "defaultRuleAreaAllowed" to nullableJsonValue(log.defaultRuleAreaAllowed),
            "textKeywordIsStandaloneSkip" to log.textKeywordIsStandaloneSkip,
            "standaloneSkipAllowed" to log.standaloneSkipAllowed,
            "effectConfirmReason" to log.effectConfirmReason,
            "safetyModeEnabled" to log.safetyModeEnabled,
            "clickSkippedBySafetyMode" to log.clickSkippedBySafetyMode,
            "candidateBounds" to log.candidateBounds,
            "candidateCenterX" to nullableJsonValue(log.candidateCenterX),
            "candidateCenterY" to nullableJsonValue(log.candidateCenterY),
            "clickedNodeBounds" to log.clickedNodeBounds,
            "clickedNodeClassName" to log.clickedNodeClassName,
            "clickedNodeText" to log.clickedNodeText,
            "clickedNodeViewId" to log.clickedNodeViewId,
            "clickedParentDepth" to nullableJsonValue(log.clickedParentDepth),
            "candidateAreaRatio" to nullableJsonValue(log.candidateAreaRatio),
            "gestureX" to nullableJsonValue(log.gestureX),
            "gestureY" to nullableJsonValue(log.gestureY),
            "isLargeCandidateBounds" to log.isLargeCandidateBounds,
            "isFixedCoordinateClick" to log.isFixedCoordinateClick,
            "clickTargetSource" to log.clickTargetSource.value
        )
    }

    private fun ClickLog.toJson(): JSONObject {
        return JSONObject().apply {
            clickLogJsonFields(this@toJson).forEach { (key, value) ->
                put(key, if (value == JsonNullValue) JSONObject.NULL else value)
            }
        }
    }

    private fun JSONObject.toClickLog(strict: Boolean = false): ClickLog {
        if (strict) {
            require(opt("timeMillis") is Number) { "点击日志缺少有效的 timeMillis" }
            require(opt("packageName") is String) { "点击日志缺少有效的 packageName" }
            val stageValue = opt("stage") as? String
            require(ClickLogStage.entries.any { it.value == stageValue }) { "点击日志缺少有效的 stage" }
        }
        return ClickLog(
            timeMillis = optLong("timeMillis"),
            packageName = optString("packageName"),
            appName = optString("appName"),
            activityName = optString("activityName"),
            ruleType = optString("ruleType"),
            ruleName = optString("ruleName"),
            ruleId = optString("ruleId"),
            ruleKind = optString("ruleKind"),
            planScope = optString("planScope", optString("ruleScope")),
            effectiveRuleWindowMs = optNullableLong("effectiveRuleWindowMs"),
            candidateRelocated = optBoolean("candidateRelocated"),
            relocationMethod = optString("relocationMethod"),
            stage = ClickLogStage.fromValue(optString("stage")),
            success = optNullableBoolean("success"),
            reason = optString("reason"),
            failureReason = optString("failureReason"),
            detail = optString("detail"),
            eventType = optNullableInt("eventType"),
            eventPackageName = optString("eventPackageName"),
            rootWindowNull = optBoolean("rootWindowNull"),
            windowId = optNullableInt("windowId"),
            rootChildCount = optNullableInt("rootChildCount"),
            canRetrieveWindowContent = optBoolean("canRetrieveWindowContent"),
            candidateCount = optNullableInt("candidateCount"),
            bestCandidateScore = optNullableInt("bestCandidateScore"),
            bestCandidateBounds = optString("bestCandidateBounds"),
            minScore = optNullableInt("minScore"),
            matchedKeyword = optString("matchedKeyword"),
            nodeText = optString("nodeText"),
            contentDescription = optString("contentDescription"),
            viewIdResourceName = optString("viewIdResourceName"),
            boundsInScreen = optString("boundsInScreen"),
            nodeClickable = optNullableBoolean("nodeClickable"),
            parentClickable = optNullableBoolean("parentClickable"),
            score = optNullableInt("score"),
            area = optString("area"),
            clickMethod = ClickMethodLog.fromValue(optString("clickMethod")),
            actionReturnValue = optNullableBoolean("actionReturnValue"),
            clickResult = optNullableBoolean("clickResult"),
            effectConfirmed = optNullableBoolean("effectConfirmed"),
            delayBeforeClickMs = optNullableLong("delayBeforeClickMs"),
            actualClickDelayMs = optNullableLong("actualClickDelayMs"),
            callbackQueueDelayMs = optNullableLong("callbackQueueDelayMs"),
            scanDurationMs = optNullableLong("scanDurationMs"),
            rescanReason = optString("rescanReason"),
            retryCount = optInt("retryCount"),
            deviceRom = optString("deviceRom"),
            elapsedSinceAppStartMs = optNullableLong("elapsedSinceAppStartMs"),
            foregroundPackage = optString("foregroundPackage"),
            foregroundStartTimeMillis = optNullableLong("foregroundStartTimeMillis"),
            elapsedSinceForegroundMs = optNullableLong("elapsedSinceForegroundMs"),
            defaultRuleWindowMs = optNullableLong("defaultRuleWindowMs"),
            isWithinDefaultRuleWindow = optNullableBoolean("isWithinDefaultRuleWindow"),
            ruleScope = optString("ruleScope"),
            timeWindowDecision = optString("timeWindowDecision"),
            isSystemPackage = optBoolean("isSystemPackage"),
            isLauncherPackage = optBoolean("isLauncherPackage"),
            isSelfPackage = optBoolean("isSelfPackage"),
            isSelfAppLabelCandidate = optBoolean("isSelfAppLabelCandidate"),
            blockedBySafety = optBoolean("blockedBySafety"),
            blockedReason = optString("blockedReason"),
            defaultRuleAreaAllowed = optNullableBoolean("defaultRuleAreaAllowed"),
            textKeywordIsStandaloneSkip = optBoolean("textKeywordIsStandaloneSkip"),
            standaloneSkipAllowed = optBoolean("standaloneSkipAllowed"),
            effectConfirmReason = optString("effectConfirmReason"),
            safetyModeEnabled = optBoolean("safetyModeEnabled"),
            clickSkippedBySafetyMode = optBoolean("clickSkippedBySafetyMode"),
            candidateBounds = optString("candidateBounds"),
            candidateCenterX = optNullableInt("candidateCenterX"),
            candidateCenterY = optNullableInt("candidateCenterY"),
            clickedNodeBounds = optString("clickedNodeBounds"),
            clickedNodeClassName = optString("clickedNodeClassName"),
            clickedNodeText = optString("clickedNodeText"),
            clickedNodeViewId = optString("clickedNodeViewId"),
            clickedParentDepth = optNullableInt("clickedParentDepth"),
            candidateAreaRatio = optNullableFloat("candidateAreaRatio"),
            gestureX = optNullableInt("gestureX"),
            gestureY = optNullableInt("gestureY"),
            isLargeCandidateBounds = optBoolean("isLargeCandidateBounds"),
            isFixedCoordinateClick = optBoolean("isFixedCoordinateClick"),
            clickTargetSource = ClickTargetSourceLog.fromValue(optString("clickTargetSource"))
        )
    }

    private fun ClickLog.toExportJson(): JSONObject {
        return toJson()
            .put("time", Instant.ofEpochMilli(timeMillis).toString())
            .put("stageLabel", stage.label)
    }

    private fun String.deserializeLegacyClickLog(): ClickLog? {
        val parts = split(FIELD_SEPARATOR)
        val time = parts.firstOrNull()?.toLongOrNull() ?: return null
        return when (parts.size) {
            3 -> ClickLog(timeMillis = time, packageName = parts[1], ruleName = parts[2])
            6 -> ClickLog(
                timeMillis = time,
                packageName = parts[1],
                appName = parts[2],
                ruleName = parts[3],
                success = parts[4].toBooleanStrictOrNull(),
                reason = parts[5]
            )
            7 -> ClickLog(
                timeMillis = time,
                packageName = parts[1],
                appName = parts[2],
                ruleType = parts[3],
                ruleName = parts[4],
                success = parts[5].toBooleanStrictOrNull(),
                reason = parts[6]
            )
            8 -> ClickLog(
                timeMillis = time,
                packageName = parts[1],
                appName = parts[2],
                ruleType = parts[3],
                ruleName = parts[4],
                success = parts[5].toBooleanStrictOrNull(),
                reason = parts[6],
                detail = parts[7]
            )
            else -> null
        }
    }

    private fun RuleLog.serialize(): String {
        return listOf(
            timeMillis.toString(),
            source.value,
            ruleName.safeField(),
            targetApp.safeField(),
            success.toString(),
            reason.safeField()
        ).joinToString(FIELD_SEPARATOR)
    }

    private fun String.deserializeRuleLog(): RuleLog? {
        val parts = split(FIELD_SEPARATOR)
        if (parts.size != 6) return null
        val time = parts[0].toLongOrNull() ?: return null
        return RuleLog(
            timeMillis = time,
            source = RuleSource.fromValue(parts[1]),
            ruleName = parts[2],
            targetApp = parts[3],
            success = parts[4].toBooleanStrictOrNull() ?: false,
            reason = parts[5]
        )
    }

    private fun String.safeField(): String {
        return replace(FIELD_SEPARATOR, " ").replace(ROW_SEPARATOR, " ")
    }

    internal fun deserializeThrottleCounts(raw: String): Map<String, Int> {
        if (raw.isBlank()) return emptyMap()
        val json = JSONObject(raw)
        return buildMap {
            json.keys().forEach { key ->
                require(key.isNotBlank()) { "损坏的旧限流计数：原因键为空" }
                val rawCount = json.get(key)
                require(rawCount is Number) { "损坏的旧限流计数：$key 不是数字" }
                val count = rawCount.toString().toLongOrNull()
                    ?: throw IllegalArgumentException("损坏的旧限流计数：$key 不是整数")
                require(count in 1L..Int.MAX_VALUE.toLong()) { "损坏的旧限流计数：$key 超出范围" }
                put(key, count.toInt())
            }
        }
    }

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
                    quarantined = migrateLegacyInQueue(context)
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

    private suspend fun migrateLegacyInQueue(context: Context): Boolean {
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
            deserializeClickLogPersistence(rawLogs)
        }.getOrElse {
            logsCorrupt = true
            emptyList()
        } else emptyList()
        val legacyCounts = if (hasLegacyThrottleCounts) runCatching {
            require(!rawCounts.isNullOrBlank()) { "legacy_throttle_counts_corrupt" }
            deserializeThrottleCounts(rawCounts)
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
        val currentDay = dayStartMillis(now)
        SkipDatabase.get(context).logDao().migrateLegacyOnce(
            migrationKey = LEGACY_MIGRATION_KEY,
            logs = legacyLogs.map { it.toEntity() },
            throttleCounts = legacyCounts.map { (key, count) ->
                ClickLogThrottleCountEntity(
                    dayStartMillis = currentDay,
                    reasonKey = key,
                    count = count.toLong(),
                    updatedAtMillis = now
                )
            },
            clickLogExpiryCutoffMillis = now - MAX_LOG_AGE_MS,
            throttleCutoffDayStartMillis = currentDay - 6L * DAY_MS,
            maxClickLogCount = MAX_CLICK_LOG_COUNT
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

    private fun ClickLog.toEntity(): ClickLogEntity {
        val payload = toJson().toString()
        return ClickLogEntity(
            storageKey = payload.sha256(),
            timeMillis = timeMillis,
            packageName = packageName,
            ruleId = ruleId,
            stage = stage.value,
            failureReason = failureReason,
            payloadJson = payload
        )
    }

    internal fun sanitizeDiagnosticReason(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.matches(internalReasonCode)) trimmed else PrivacySanitizer.sanitizeText(value)
    }

    private fun dayStartMillis(timeMillis: Long): Long {
        return Math.floorDiv(timeMillis, DAY_MS) * DAY_MS
    }

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        return if (has(key) && !isNull(key)) optInt(key) else null
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        return if (has(key) && !isNull(key)) optLong(key) else null
    }

    private fun JSONObject.optNullableBoolean(key: String): Boolean? {
        return if (has(key) && !isNull(key)) optBoolean(key) else null
    }

    private fun JSONObject.optNullableFloat(key: String): Float? {
        return if (has(key) && !isNull(key)) optDouble(key).toFloat() else null
    }

    private fun nullableJsonValue(value: Any?): Any {
        return value ?: JsonNullValue
    }
}

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

internal class ClickLogRateLimiter(
    private val windowMs: Long = 2_000L,
    private val safetyWindowMs: Long = windowMs
) {
    private val lastStoredAtByKey = mutableMapOf<String, Long>()

    fun shouldStore(log: ClickLog, now: Long): RateLimitDecision {
        val keys = log.rateLimitKeys()
            ?: return RateLimitDecision(allowed = true)
        val last = lastStoredAtByKey[keys.dedupeKey]
        if (last == null) {
            lastStoredAtByKey[keys.dedupeKey] = now
            return RateLimitDecision(allowed = true, throttleAggregateKey = keys.throttleAggregateKey)
        }
        val effectiveWindowMs = if (log.stage == ClickLogStage.SkippedBySafety) safetyWindowMs else windowMs
        if (now - last < effectiveWindowMs) {
            return RateLimitDecision(allowed = false, throttleAggregateKey = keys.throttleAggregateKey)
        }
        lastStoredAtByKey[keys.dedupeKey] = now
        return RateLimitDecision(allowed = true, throttleAggregateKey = keys.throttleAggregateKey)
    }

    fun reset() {
        lastStoredAtByKey.clear()
    }

    private fun ClickLog.rateLimitKeys(): ClickLogRateLimitKeys? {
        val reason = LogRepository.sanitizeDiagnosticReason(
            failureReason.ifBlank { this.reason }.ifBlank { blockedReason }
        )
        if (reason in NON_THROTTLED_REASONS || !stage.isNoisyStage()) return null
        val aggregateKey = listOf(packageName, stage.value, reason)
            .filter { it.isNotBlank() }
            .joinToString("|")
        val dedupeKey = if (stage == ClickLogStage.SkippedBySafety) {
            "$aggregateKey|${foregroundStartTimeMillis?.toString() ?: "unknown_session"}"
        } else {
            aggregateKey
        }
        return ClickLogRateLimitKeys(
            dedupeKey = dedupeKey,
            throttleAggregateKey = aggregateKey
        )
    }

    private fun ClickLogStage.isNoisyStage(): Boolean {
        return this == ClickLogStage.NoCandidateFound ||
            this == ClickLogStage.SkippedByTimeWindow ||
            this == ClickLogStage.SkippedByCooldown ||
            this == ClickLogStage.SkippedBySafety ||
            this == ClickLogStage.RootWindowNull ||
            this == ClickLogStage.EventPackageNull ||
            this == ClickLogStage.SkippedSelfPackage
    }

    private companion object {
        val NON_THROTTLED_REASONS = setOf(
            "candidate_lost_before_click",
            "candidate_changed_before_click"
        )
    }
}

internal data class ClickLogRateLimitKeys(
    val dedupeKey: String,
    val throttleAggregateKey: String
)

internal data class RateLimitDecision(
    val allowed: Boolean,
    val throttleAggregateKey: String = ""
)

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
