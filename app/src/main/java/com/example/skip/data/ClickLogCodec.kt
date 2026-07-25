package com.example.skip.data

import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal object ClickLogCodec {
    private const val FIELD_SEPARATOR = "\t"

    fun deserializeClickLogPersistence(raw: String): List<ClickLog> {
        if (raw.isBlank()) return emptyList()
        return if (raw.trimStart().startsWith("[")) {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.opt(index)
                    require(item is JSONObject) { "损坏的旧点击日志：第 $index 项不是对象" }
                    add(item.asClickLog(strict = true))
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

    fun serializeClickLogPersistence(
        logs: List<ClickLog>,
        throttleCounts: Map<String, Int>
    ): ClickLogPersistencePayload {
        return ClickLogPersistencePayload(
            logsJson = JSONArray().apply {
                logs.forEach { put(it.asJson()) }
            }.toString(),
            throttleCountsJson = JSONObject().apply {
                throttleCounts.forEach { (key, count) -> put(key, count) }
            }.toString()
        )
    }

    fun clickLogToJson(log: ClickLog): JSONObject {
        return log.asJson()
    }

    fun clickLogJsonFields(log: ClickLog): Map<String, Any> {
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

    fun fromJson(json: JSONObject, strict: Boolean = false): ClickLog =
        json.asClickLog(strict)

    fun toExportJson(log: ClickLog): JSONObject = log.asExportJson()

    private fun ClickLog.asJson(): JSONObject {
        return JSONObject().apply {
            clickLogJsonFields(this@asJson).forEach { (key, value) ->
                put(key, if (value == LogRepository.JsonNullValue) JSONObject.NULL else value)
            }
        }
    }

    private fun JSONObject.asClickLog(strict: Boolean = false): ClickLog {
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

    private fun ClickLog.asExportJson(): JSONObject {
        return asJson()
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

    fun deserializeThrottleCounts(raw: String): Map<String, Int> {
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
        return value ?: LogRepository.JsonNullValue
    }
}
