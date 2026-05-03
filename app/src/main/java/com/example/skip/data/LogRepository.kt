package com.example.skip.data

import android.content.Context
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.RuleLog
import com.example.skip.model.RuleSource
import com.example.skip.util.PrivacySanitizer
import com.example.skip.util.RomUtils
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object LogRepository {
    private const val KEY_CLICK_LOGS = "click_logs"
    private const val KEY_RULE_LOGS = "rule_logs"
    private const val MAX_CLICK_LOG_COUNT = 1000
    private const val MAX_RULE_LOG_COUNT = 100
    private const val MAX_LOG_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val FIELD_SEPARATOR = "\t"
    private const val ROW_SEPARATOR = "\n"
    private const val DUPLICATE_WINDOW_MS = 5000L

    internal object JsonNullValue

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
        val existing = getClickLogs(context)
            .filter { now - it.timeMillis <= MAX_LOG_AGE_MS }
            .filterNot { it.isDuplicateOf(cleaned, now) }
        val logs = buildList {
            add(cleaned)
            addAll(existing)
        }.take(MAX_CLICK_LOG_COUNT)

        SettingsRepository.prefs(context)
            .edit()
            .putString(KEY_CLICK_LOGS, JSONArray().apply {
                logs.forEach { put(it.toJson()) }
            }.toString())
            .apply()
    }

    fun getClickLogs(context: Context): List<ClickLog> {
        val raw = SettingsRepository.prefs(context).getString(KEY_CLICK_LOGS, null).orEmpty()
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
        SettingsRepository.prefs(context).edit().remove(KEY_CLICK_LOGS).apply()
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

    fun addRuleLog(context: Context, log: RuleLog) {
        val logs = buildList {
            add(log)
            addAll(getRuleLogs(context))
        }.take(MAX_RULE_LOG_COUNT)

        SettingsRepository.prefs(context)
            .edit()
            .putString(KEY_RULE_LOGS, logs.joinToString(ROW_SEPARATOR) { it.serialize() })
            .apply()
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
        SettingsRepository.prefs(context).edit().remove(KEY_RULE_LOGS).apply()
    }

    private fun ClickLog.isDuplicateOf(other: ClickLog, now: Long): Boolean {
        if (now - timeMillis > DUPLICATE_WINDOW_MS) return false
        return packageName == other.packageName &&
            ruleId == other.ruleId &&
            stage == other.stage &&
            failureReason == other.failureReason
    }

    internal fun clickLogToJson(log: ClickLog): JSONObject {
        return log.toJson()
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
            "defaultRuleWindowMs" to nullableJsonValue(log.defaultRuleWindowMs),
            "isSystemPackage" to log.isSystemPackage,
            "isLauncherPackage" to log.isLauncherPackage,
            "isSelfPackage" to log.isSelfPackage,
            "isSelfAppLabelCandidate" to log.isSelfAppLabelCandidate,
            "blockedBySafety" to log.blockedBySafety,
            "blockedReason" to log.blockedReason,
            "defaultRuleAreaAllowed" to nullableJsonValue(log.defaultRuleAreaAllowed),
            "textKeywordIsStandaloneSkip" to log.textKeywordIsStandaloneSkip,
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
            defaultRuleWindowMs = optNullableLong("defaultRuleWindowMs"),
            isSystemPackage = optBoolean("isSystemPackage"),
            isLauncherPackage = optBoolean("isLauncherPackage"),
            isSelfPackage = optBoolean("isSelfPackage"),
            isSelfAppLabelCandidate = optBoolean("isSelfAppLabelCandidate"),
            blockedBySafety = optBoolean("blockedBySafety"),
            blockedReason = optString("blockedReason"),
            defaultRuleAreaAllowed = optNullableBoolean("defaultRuleAreaAllowed"),
            textKeywordIsStandaloneSkip = optBoolean("textKeywordIsStandaloneSkip"),
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
