package com.example.skip.data

import android.content.Context
import com.example.skip.model.AppPolicy
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.CoordinateFallback
import com.example.skip.model.RuleLog
import com.example.skip.model.RulePackage
import com.example.skip.model.SkipRule
import com.example.skip.util.PrivacySanitizer
import com.example.skip.util.RomUtils
import java.time.Instant

object DiagnosticReportRepository {
    const val SCHEMA_VERSION = 2

    fun exportDiagnosticReportAsJson(
        context: Context,
        versionName: String,
        exportTimeMillis: Long = System.currentTimeMillis()
    ): String {
        return buildReportJson(
            versionName = versionName,
            exportTimeMillis = exportTimeMillis,
            deviceInfo = RomUtils.getDeviceInfo(),
            runtimeState = SettingsRepository.getDiagnosticSnapshot(context),
            rules = RuleRepository.getRules(context),
            rulePackages = RuleRepository.getRulePackages(context),
            clickLogs = LogRepository.getClickLogs(context),
            ruleLogs = LogRepository.getRuleLogs(context),
            keywords = RuleRepository.getKeywords(context),
            viewIdKeywords = RuleRepository.getViewIdKeywords(context),
            defaultRuleConfig = RuleRepository.getDefaultRuleConfig(context),
            logThrottleCounts = LogRepository.getClickLogThrottleCounts(context)
        )
    }

    fun buildReportJson(
        versionName: String,
        exportTimeMillis: Long,
        deviceInfo: RomUtils.DeviceInfo,
        runtimeState: SettingsRepository.DiagnosticSnapshot,
        rules: List<SkipRule>,
        rulePackages: List<RulePackage>,
        clickLogs: List<ClickLog>,
        ruleLogs: List<RuleLog>,
        keywords: List<String>,
        viewIdKeywords: List<String>,
        defaultRuleConfig: RuleRepository.DefaultRuleConfig = RuleRepository.defaultDefaultRuleConfig(),
        logThrottleCounts: Map<String, Int> = emptyMap()
    ): String {
        val sanitizedClickLogs = clickLogs.map(::sanitizeClickLog)
        return jsonObject(
            "schemaVersion" to SCHEMA_VERSION,
            "exportTime" to Instant.ofEpochMilli(exportTimeMillis).toString(),
            "skipVersion" to versionName,
            "privacy" to privacyJson(),
            "device" to deviceJson(deviceInfo),
            "runtimeState" to runtimeJson(runtimeState),
            "rulesSnapshot" to rulesSnapshotJson(
                rules = rules,
                rulePackages = rulePackages,
                appPolicies = runtimeState.appPolicies,
                keywords = keywords,
                viewIdKeywords = viewIdKeywords,
                defaultRuleConfig = defaultRuleConfig
            ),
            "clickLogs" to jsonArray(sanitizedClickLogs.map(::clickLogJson)),
            "ruleLogs" to jsonArray(ruleLogs.map(::ruleLogJson)),
            "diagnosticSummary" to diagnosticSummaryJson(
                clickLogs = sanitizedClickLogs,
                ruleLogs = ruleLogs,
                now = exportTimeMillis,
                otherAccessibilityServices = runtimeState.otherAccessibilityServices,
                logThrottleCounts = logThrottleCounts
            )
        ).toJsonString()
    }

    private fun privacyJson(): JsonObjectValue {
        return jsonObject(
            "localOnly" to true,
            "autoUpload" to false,
            "redacted" to true,
            "screenContentIncluded" to false
        )
    }

    private fun deviceJson(deviceInfo: RomUtils.DeviceInfo): JsonObjectValue {
        return jsonObject(
            "brand" to deviceInfo.brand,
            "manufacturer" to deviceInfo.manufacturer,
            "model" to deviceInfo.model,
            "androidVersion" to deviceInfo.androidVersion,
            "sdkInt" to deviceInfo.sdkInt,
            "rom" to deviceInfo.romType.label,
            "romType" to deviceInfo.romType.name
        )
    }

    private fun runtimeJson(snapshot: SettingsRepository.DiagnosticSnapshot): JsonObjectValue {
        return jsonObject(
            "masterEnabled" to snapshot.masterEnabled,
            "safetyModeEnabled" to snapshot.safetyModeEnabled,
            "debugLogEnabled" to snapshot.debugLogEnabled,
            "releaseDisclosureAccepted" to snapshot.releaseDisclosureAccepted,
            "accessibilityServiceEnabled" to snapshot.accessibilityServiceEnabled,
            "serviceConnectedAt" to snapshot.serviceConnectedAt,
            "serviceActiveAt" to snapshot.serviceActiveAt,
            "serviceInterruptedAt" to snapshot.serviceInterruptedAt,
            "lastClickAt" to snapshot.lastClickAt,
            "lastFailureReason" to PrivacySanitizer.sanitizeText(snapshot.lastFailureReason),
            "appPolicyCount" to snapshot.appPolicies.size,
            "defaultRuleDisabledPackageCount" to snapshot.appPolicies.count { !it.defaultRuleEnabled },
            "customRulesDisabledPackageCount" to snapshot.appPolicies.count { !it.customRulesEnabled },
            "otherAccessibilityServices" to componentStringsJson(snapshot.otherAccessibilityServices)
        )
    }

    private fun rulesSnapshotJson(
        rules: List<SkipRule>,
        rulePackages: List<RulePackage>,
        appPolicies: List<AppPolicy>,
        keywords: List<String>,
        viewIdKeywords: List<String>,
        defaultRuleConfig: RuleRepository.DefaultRuleConfig
    ): JsonObjectValue {
        return jsonObject(
            "ruleCount" to rules.size,
            "enabledRuleCount" to rules.count { it.enabled },
            "coordinateFallbackRuleCount" to rules.count { it.coordinateFallback != null },
            "coordinateFallbackEnabledRuleCount" to rules.count { it.coordinateFallback?.enabled == true },
            "defaultRuleRuntime" to defaultRuleRuntimeJson(
                keywordCount = keywords.size,
                viewIdKeywordCount = viewIdKeywords.size,
                defaultRuleConfig = defaultRuleConfig
            ),
            "ruleSourceCounts" to countsJson(rules.groupingBy { it.source.value }.eachCount()),
            "keywords" to stringsJson(keywords),
            "viewIdKeywords" to stringsJson(viewIdKeywords),
            "appPolicies" to jsonArray(appPolicies.map(::appPolicyJson)),
            "rulePackages" to jsonArray(rulePackages.map(::rulePackageJson)),
            "rules" to jsonArray(rules.map(::ruleJson))
        )
    }

    private fun defaultRuleRuntimeJson(
        keywordCount: Int,
        viewIdKeywordCount: Int,
        defaultRuleConfig: RuleRepository.DefaultRuleConfig
    ): JsonObjectValue {
        val config = RuleRepository.sanitizeDefaultRuleConfig(defaultRuleConfig)
        return jsonObject(
            "defaultRuleWindowMs" to config.validDurationMs,
            "defaultRuleMinScore" to config.minScore,
            "defaultRuleArea" to config.area.value,
            "defaultRuleCooldownMs" to config.cooldownMs,
            "keywordCount" to keywordCount,
            "viewIdKeywordCount" to viewIdKeywordCount
        )
    }

    private fun appPolicyJson(policy: AppPolicy): JsonObjectValue {
        return jsonObject(
            "packageName" to policy.packageName,
            "defaultRuleEnabled" to policy.defaultRuleEnabled,
            "customRulesEnabled" to policy.customRulesEnabled,
            "migratedFromBlacklist" to policy.migratedFromBlacklist,
            "updatedAt" to policy.updatedAt
        )
    }

    private fun rulePackageJson(rulePackage: RulePackage): JsonObjectValue {
        return jsonObject(
            "id" to rulePackage.id,
            "name" to PrivacySanitizer.sanitizeText(rulePackage.name),
            "version" to rulePackage.version,
            "author" to PrivacySanitizer.sanitizeText(rulePackage.author),
            "updateTime" to rulePackage.updateTime,
            "description" to PrivacySanitizer.sanitizeText(rulePackage.description),
            "enabled" to rulePackage.enabled,
            "source" to rulePackage.source.value,
            "createdAt" to rulePackage.createdAt
        )
    }

    private fun ruleJson(rule: SkipRule): JsonObjectValue {
        return jsonObject(
            "id" to rule.id,
            "source" to rule.source.value,
            "name" to PrivacySanitizer.sanitizeText(rule.name),
            "packageName" to rule.packageName,
            "appName" to PrivacySanitizer.sanitizeText(rule.appName),
            "enabled" to rule.enabled,
            "activityName" to rule.activityName,
            "matchTexts" to stringsJson(rule.matchTexts),
            "matchContentDescriptions" to stringsJson(rule.matchContentDescriptions),
            "matchViewIds" to stringsJson(rule.matchViewIds),
            "textMatchMode" to rule.textMatchMode.value,
            "contentDescriptionMatchMode" to rule.contentDescriptionMatchMode.value,
            "viewIdMatchMode" to rule.viewIdMatchMode.value,
            "area" to rule.area.value,
            "action" to rule.action.value,
            "priority" to rule.priority,
            "cooldownMs" to rule.cooldownMs,
            "validDurationMs" to rule.validDurationMs,
            "minScore" to rule.minScore,
            "coordinateFallback" to coordinateFallbackJson(rule.coordinateFallback),
            "packageId" to rule.packageId,
            "createdAt" to rule.createdAt
        )
    }

    private fun coordinateFallbackJson(fallback: CoordinateFallback?): Any {
        return fallback?.let {
            jsonObject(
                "enabled" to it.enabled,
                "xRatio" to it.xRatio.toDouble(),
                "yRatio" to it.yRatio.toDouble(),
                "anchorTexts" to stringsJson(it.anchorTexts),
                "anchorContentDescriptions" to stringsJson(it.anchorContentDescriptions),
                "anchorViewIds" to stringsJson(it.anchorViewIds)
            )
        } ?: JsonNullValue
    }

    private fun clickLogJson(log: ClickLog): JsonObjectValue {
        val fields = LogRepository.clickLogJsonFields(log)
            .map { (key, value) -> key to if (value == LogRepository.JsonNullValue) JsonNullValue else value }
        return JsonObjectValue(
            fields + listOf(
                "time" to Instant.ofEpochMilli(log.timeMillis).toString(),
                "stageLabel" to log.stage.label
            )
        )
    }

    private fun ruleLogJson(log: RuleLog): JsonObjectValue {
        return jsonObject(
            "timeMillis" to log.timeMillis,
            "time" to Instant.ofEpochMilli(log.timeMillis).toString(),
            "source" to log.source.value,
            "sourceLabel" to log.source.label,
            "ruleName" to PrivacySanitizer.sanitizeText(log.ruleName),
            "targetApp" to PrivacySanitizer.sanitizeText(log.targetApp),
            "success" to log.success,
            "reason" to PrivacySanitizer.sanitizeText(log.reason)
        )
    }

    private fun diagnosticSummaryJson(
        clickLogs: List<ClickLog>,
        ruleLogs: List<RuleLog>,
        now: Long,
        otherAccessibilityServices: List<String>,
        logThrottleCounts: Map<String, Int>
    ): JsonObjectValue {
        val stageCounts = clickLogs.groupingBy { it.stage.value }.eachCount()
        val reasonCounts = clickLogs
            .mapNotNull { it.diagnosticReason().takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
        val blockedReasonCounts = clickLogs
            .mapNotNull { PrivacySanitizer.sanitizeText(it.blockedReason).takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
        val packageCounts = clickLogs
            .filter { it.packageName.isNotBlank() }
            .groupingBy { it.packageName }
            .eachCount()
        val ruleCounts = clickLogs
            .mapNotNull { it.ruleKey().takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()

        return jsonObject(
            "totalClickLogs" to clickLogs.size,
            "totalRuleLogs" to ruleLogs.size,
            "successCount" to clickLogs.count { it.success == true || it.stage == ClickLogStage.ClickEffectConfirmed },
            "failureCount" to clickLogs.count { it.success == false || it.stage == ClickLogStage.ClickFailed },
            "stageCounts" to countsJson(stageCounts),
            "reasonCounts" to countsJson(reasonCounts),
            "blockedReasonCounts" to countsJson(blockedReasonCounts),
            "packageCounts" to countsJson(packageCounts),
            "ruleCounts" to countsJson(ruleCounts),
            "recentWindows" to recentWindowsJson(clickLogs, now),
            "rawSignalCounts" to rawSignalCountsJson(clickLogs),
            "categoryCounts" to categoryCountsJson(clickLogs),
            "otherAccessibilityServices" to componentStringsJson(otherAccessibilityServices),
            "logThrottleCounts" to countsJson(logThrottleCounts)
        )
    }

    private fun recentWindowsJson(logs: List<ClickLog>, now: Long): JsonObjectValue {
        val oneDayMs = 24L * 60L * 60L * 1000L
        val sevenDaysMs = 7L * oneDayMs
        return jsonObject(
            "last24h" to logs.count { now - it.timeMillis <= oneDayMs },
            "last7d" to logs.count { now - it.timeMillis <= sevenDaysMs }
        )
    }

    private fun categoryCountsJson(logs: List<ClickLog>): JsonObjectValue {
        return jsonObject(
            "noCandidate" to logs.count { it.isNoCandidate() },
            "lowScore" to logs.count { it.isLowScore() },
            "cooldown" to logs.count { it.isCooldown() },
            "timeWindow" to logs.count { it.isTimeWindowIssue() },
            "safetyBlocked" to logs.count { it.isSafetyBlocked() },
            "coordinateFallbackLimited" to logs.count { it.isCoordinateFallbackLimited() },
            "rootWindowNull" to logs.count { it.stage == ClickLogStage.RootWindowNull || it.rootWindowNull },
            "packageChanged" to logs.count { it.isPackageChanged() },
            "packageUnknown" to logs.count {
                it.stage == ClickLogStage.EventPackageNull ||
                    it.stage == ClickLogStage.ClickCancelledPackageUnknown
            },
            "effectUnknown" to logs.count { it.stage == ClickLogStage.ClickEffectUnknown }
        )
    }

    private fun rawSignalCountsJson(logs: List<ClickLog>): JsonObjectValue {
        return jsonObject(
            "outsideDefaultWindow" to logs.count {
                it.isWithinDefaultRuleWindow == false ||
                    it.timeWindowDecision.contains("outside", ignoreCase = true) ||
                    it.timeWindowDecision.contains("expired", ignoreCase = true)
            }
        )
    }

    private fun ClickLog.isNoCandidate(): Boolean {
        return stage == ClickLogStage.NoCandidateFound ||
            candidateCount == 0 ||
            diagnosticReason().contains("no_candidate", ignoreCase = true)
    }

    private fun ClickLog.isLowScore(): Boolean {
        return stage == ClickLogStage.SkippedByLowScore ||
            diagnosticReason().contains("low_score", ignoreCase = true) ||
            diagnosticReason().contains("score_below", ignoreCase = true) ||
            (score != null && minScore != null && score < minScore)
    }

    private fun ClickLog.isCooldown(): Boolean {
        return stage == ClickLogStage.SkippedByCooldown ||
            diagnosticReason().contains("cooldown", ignoreCase = true)
    }

    private fun ClickLog.isTimeWindowIssue(): Boolean {
        return stage == ClickLogStage.SkippedByTimeWindow ||
            stage == ClickLogStage.ClickCancelledTimeWindowExpired ||
            diagnosticReason().contains("window_expired", ignoreCase = true)
    }

    private fun ClickLog.isSafetyBlocked(): Boolean {
        return blockedBySafety ||
            clickSkippedBySafetyMode ||
            stage == ClickLogStage.SkippedBySafety ||
            stage == ClickLogStage.ClickSkippedBySafetyMode ||
            blockedReason.contains("safety", ignoreCase = true)
    }

    private fun ClickLog.isCoordinateFallbackLimited(): Boolean {
        return clickTargetSource == ClickTargetSourceLog.FixedPositionForbidden ||
            diagnosticReason().contains("coordinate_fallback", ignoreCase = true) ||
            blockedReason.contains("coordinate_fallback", ignoreCase = true)
    }

    private fun ClickLog.isPackageChanged(): Boolean {
        return stage == ClickLogStage.ClickCancelledPackageChanged ||
            diagnosticReason().contains("package_changed", ignoreCase = true)
    }

    private fun ClickLog.diagnosticReason(): String {
        return PrivacySanitizer.sanitizeText(failureReason.ifBlank { reason })
    }

    private fun ClickLog.ruleKey(): String {
        return listOf(packageName, ruleId.ifBlank { ruleName })
            .filter { it.isNotBlank() }
            .joinToString(" / ")
    }

    private fun sanitizeClickLog(log: ClickLog): ClickLog {
        return log.copy(
            appName = PrivacySanitizer.sanitizeText(log.appName),
            ruleName = PrivacySanitizer.sanitizeText(log.ruleName),
            reason = PrivacySanitizer.sanitizeText(log.reason),
            failureReason = PrivacySanitizer.sanitizeText(log.failureReason),
            detail = PrivacySanitizer.sanitizeText(log.detail),
            matchedKeyword = PrivacySanitizer.sanitizeText(log.matchedKeyword),
            nodeText = PrivacySanitizer.sanitizeText(log.nodeText),
            contentDescription = PrivacySanitizer.sanitizeText(log.contentDescription),
            clickedNodeText = PrivacySanitizer.sanitizeText(log.clickedNodeText),
            effectConfirmReason = PrivacySanitizer.sanitizeText(log.effectConfirmReason),
            blockedReason = PrivacySanitizer.sanitizeText(log.blockedReason)
        )
    }

    private fun stringsJson(values: Collection<String>): JsonArrayValue {
        return jsonArray(values.map { PrivacySanitizer.sanitizeText(it) })
    }

    private fun componentStringsJson(values: Collection<String>): JsonArrayValue {
        return jsonArray(values.map { it.trim() }.filter { it.isNotBlank() }.distinct())
    }

    private fun countsJson(counts: Map<String, Int>): JsonObjectValue {
        return JsonObjectValue(
            counts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { (key, count) -> key to count }
        )
    }

    private fun jsonObject(vararg values: Pair<String, Any?>): JsonObjectValue {
        return JsonObjectValue(values.toList())
    }

    private fun jsonArray(values: Collection<Any?>): JsonArrayValue {
        return JsonArrayValue(values.toList())
    }

    private sealed interface JsonValue

    private data class JsonObjectValue(val values: List<Pair<String, Any?>>) : JsonValue

    private data class JsonArrayValue(val values: List<Any?>) : JsonValue

    private data object JsonNullValue : JsonValue

    private fun JsonValue.toJsonString(): String {
        return renderJsonValue(this)
    }

    private fun renderJsonValue(value: Any?): String {
        return when (value) {
            null, JsonNullValue -> "null"
            is JsonObjectValue -> value.values.joinToString(prefix = "{", postfix = "}") { (key, item) ->
                "${key.jsonEscape()}:${renderJsonValue(item)}"
            }
            is JsonArrayValue -> value.values.joinToString(prefix = "[", postfix = "]") { renderJsonValue(it) }
            is String -> value.jsonEscape()
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> value.toString().jsonEscape()
        }
    }

    private fun String.jsonEscape(): String {
        val builder = StringBuilder(length + 2)
        builder.append('"')
        forEach { char ->
            when (char) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        builder.append("\\u")
                        builder.append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        builder.append(char)
                    }
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
