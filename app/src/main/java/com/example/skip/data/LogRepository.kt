package com.example.skip.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.RuleLog
import com.example.skip.model.RuleSource
import com.example.skip.util.PrivacySanitizer
import com.example.skip.util.RomUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal data class ClickLogPersistencePayload(
    val logsJson: String,
    val throttleCountsJson: String
)

object LogRepository {
    private const val KEY_CLICK_LOGS = "click_logs"
    private const val KEY_CLICK_LOG_THROTTLE_COUNTS = "click_log_throttle_counts"
    private const val KEY_RULE_LOGS = "rule_logs"
    private const val MAX_CLICK_LOG_COUNT = 1000
    private const val MAX_RULE_LOG_COUNT = 100
    private const val MAX_LOG_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val FIELD_SEPARATOR = "\t"
    private const val ROW_SEPARATOR = "\n"
    private const val DUPLICATE_WINDOW_MS = 5000L
    private const val FLUSH_DELAY_MS = 1_500L

    internal object JsonNullValue
    private val lock = Any()
    private val clickLogBuffer = ClickLogBuffer(
        maxCount = MAX_CLICK_LOG_COUNT,
        maxAgeMs = MAX_LOG_AGE_MS,
        duplicateWindowMs = DUPLICATE_WINDOW_MS
    )
    private val rateLimiter = ClickLogRateLimiter()
    private val throttleCounts = linkedMapOf<String, Int>()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val persistenceQueue = ClickLogPersistenceQueue(writeScope)
    private var clickLogsLoaded = false
    private var flushScheduled = false
    private var appContext: Context? = null

    fun addClickLog(context: Context, log: ClickLog) {
        if (log.stage.isDebugOnly && !SettingsRepository.isDebugToastEnabled(context)) return
        val now = System.currentTimeMillis()
        val cleaned = log.copy(
            nodeText = PrivacySanitizer.sanitizeText(log.nodeText),
            contentDescription = PrivacySanitizer.sanitizeText(log.contentDescription),
            clickedNodeText = PrivacySanitizer.sanitizeText(log.clickedNodeText),
            reason = PrivacySanitizer.sanitizeText(log.reason),
            failureReason = PrivacySanitizer.sanitizeText(log.failureReason),
            detail = PrivacySanitizer.sanitizeText(log.detail)
        )
        synchronized(lock) {
            ensureClickLogsLoadedLocked(context)
            val rateLimit = rateLimiter.shouldStore(cleaned, now)
            if (rateLimit.allowed) {
                clickLogBuffer.add(cleaned, now)
            } else {
                throttleCounts[rateLimit.key] = (throttleCounts[rateLimit.key] ?: 0) + 1
            }
        }
        scheduleFlush(context)
    }

    fun getClickLogs(context: Context): List<ClickLog> {
        synchronized(lock) {
            ensureClickLogsLoadedLocked(context)
            return clickLogBuffer.snapshot()
        }
    }

    fun getClickLogThrottleCounts(context: Context): Map<String, Int> {
        synchronized(lock) {
            ensureClickLogsLoadedLocked(context)
            return throttleCounts.toMap()
        }
    }

    private fun readClickLogsFromPrefs(context: Context): List<ClickLog> {
        val raw = SettingsRepository.prefs(context).getString(KEY_CLICK_LOGS, null).orEmpty()
        return deserializeClickLogPersistence(raw)
    }

    internal fun deserializeClickLogPersistence(raw: String): List<ClickLog> {
        if (raw.isBlank()) return emptyList()
        return if (raw.trimStart().startsWith("[")) {
            runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.toClickLog()?.let(::add)
                    }
                }
            }.getOrDefault(emptyList())
        } else {
            raw.lineSequence().mapNotNull { it.deserializeLegacyClickLog() }.toList()
        }
    }

    fun clearClickLogs(context: Context) {
        synchronized(lock) {
            ensureClickLogsLoadedLocked(context)
            clickLogBuffer.clear()
            throttleCounts.clear()
        }
        clearPersistedClickLogs(context.applicationContext)
    }

    fun exportClickLogsAsJson(
        context: Context,
        versionName: String,
        logs: List<ClickLog> = getClickLogs(context)
    ): String {
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
                logs.forEach { put(it.toExportJson()) }
            })
            .toString(2)
    }

    internal fun flushPendingClickLogs(context: Context) {
        val logs: List<ClickLog>
        val counts: Map<String, Int>
        synchronized(lock) {
            ensureClickLogsLoadedLocked(context)
            flushScheduled = false
            logs = clickLogBuffer.snapshot()
            counts = throttleCounts.toMap()
        }

        val payload = serializeClickLogPersistence(logs, counts)
        SettingsRepository.prefs(context).edit {
            putString(KEY_CLICK_LOGS, payload.logsJson)
            putString(KEY_CLICK_LOG_THROTTLE_COUNTS, payload.throttleCountsJson)
        }
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

    private fun JSONObject.toClickLog(): ClickLog {
        return ClickLog(
            timeMillis = optLong("timeMillis"),
            packageName = optString("packageName"),
            appName = optString("appName"),
            activityName = optString("activityName"),
            ruleType = optString("ruleType"),
            ruleName = optString("ruleName"),
            ruleId = optString("ruleId"),
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

    private fun ensureClickLogsLoadedLocked(context: Context) {
        appContext = context.applicationContext
        if (clickLogsLoaded) return
        clickLogBuffer.replaceAll(readClickLogsFromPrefs(context))
        throttleCounts.clear()
        throttleCounts.putAll(readThrottleCounts(context))
        clickLogsLoaded = true
    }

    private fun readThrottleCounts(context: Context): Map<String, Int> {
        val raw = SettingsRepository.prefs(context)
            .getString(KEY_CLICK_LOG_THROTTLE_COUNTS, null)
            .orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    val count = json.optInt(key, 0)
                    if (key.isNotBlank() && count > 0) put(key, count)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun scheduleFlush(context: Context) {
        val targetContext = context.applicationContext
        val shouldSchedule = synchronized(lock) {
            appContext = targetContext
            if (flushScheduled) {
                false
            } else {
                flushScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        mainHandler.postDelayed({
            val flushContext = appContext ?: targetContext
            persistenceQueue.enqueue {
                flushPendingClickLogs(flushContext)
            }
        }, FLUSH_DELAY_MS)
    }

    private fun clearPersistedClickLogs(context: Context) {
        persistenceQueue.enqueue {
            SettingsRepository.prefs(context).edit {
                remove(KEY_CLICK_LOGS)
                remove(KEY_CLICK_LOG_THROTTLE_COUNTS)
            }
        }
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
    private val windowMs: Long = 2_000L
) {
    private val lastStoredAtByKey = mutableMapOf<String, Long>()

    fun shouldStore(log: ClickLog, now: Long): RateLimitDecision {
        val key = log.rateLimitKey()
        if (key.isBlank()) return RateLimitDecision(allowed = true, key = "")
        val last = lastStoredAtByKey[key]
        if (last == null) {
            lastStoredAtByKey[key] = now
            return RateLimitDecision(allowed = true, key = key)
        }
        if (now - last < windowMs) {
            return RateLimitDecision(allowed = false, key = key)
        }
        lastStoredAtByKey[key] = now
        return RateLimitDecision(allowed = true, key = key)
    }

    private fun ClickLog.rateLimitKey(): String {
        if (!stage.isNoisyStage()) return ""
        if (stage == ClickLogStage.SkippedBySafety &&
            !isSystemPackage &&
            !isLauncherPackage &&
            blockedReason != "system_or_launcher_package"
        ) {
            return ""
        }
        val reason = PrivacySanitizer.sanitizeText(
            failureReason.ifBlank { this.reason }.ifBlank { blockedReason }
        )
        return listOf(packageName, stage.value, reason)
            .filter { it.isNotBlank() }
            .joinToString("|")
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
}

internal data class RateLimitDecision(
    val allowed: Boolean,
    val key: String
)
