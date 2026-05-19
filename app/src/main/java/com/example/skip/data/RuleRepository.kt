package com.example.skip.data

import android.content.Context
import androidx.core.content.edit
import com.example.skip.model.CoordinateFallback
import com.example.skip.model.DuplicateStrategy
import com.example.skip.model.MatchMode
import com.example.skip.model.RuleAction
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleImportResult
import com.example.skip.model.RulePackage
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object RuleRepository {
    const val DEFAULT_RULE_WINDOW_MS = 8_000L
    const val DEFAULT_RULE_MIN_SCORE = 70
    const val DEFAULT_RULE_COOLDOWN_MS = 1_500L
    const val MIN_DEFAULT_RULE_COOLDOWN_MS = 800L

    val defaultRuleDurationOptionsMs = listOf(6_000L, DEFAULT_RULE_WINDOW_MS, 10_000L)

    private const val KEY_KEYWORDS = "keywords"
    private const val KEY_VIEW_ID_KEYWORDS = "view_id_keywords"
    private const val KEY_RULES_JSON = "rules_json_v2"
    private const val KEY_RULE_PACKAGES_JSON = "rule_packages_json_v2"
    private const val KEY_DEFAULT_RULE_WINDOW_MS = "default_rule_window_ms_v1"
    private const val KEY_DEFAULT_RULE_MIN_SCORE = "default_rule_min_score_v1"
    private const val KEY_DEFAULT_RULE_AREA = "default_rule_area_v1"
    private const val KEY_DEFAULT_RULE_COOLDOWN_MS = "default_rule_cooldown_ms_v1"
    private const val MIN_DEFAULT_RULE_SCORE = 60
    private const val MAX_DEFAULT_RULE_SCORE = 90

    data class DefaultRuleConfig(
        val validDurationMs: Long = DEFAULT_RULE_WINDOW_MS,
        val minScore: Int = DEFAULT_RULE_MIN_SCORE,
        val area: RuleArea = RuleArea.TopRight,
        val cooldownMs: Long = DEFAULT_RULE_COOLDOWN_MS
    )

    val defaultKeywords = listOf(
        "跳过",
        "跳过广告",
        "跳过此广告",
        "跳过开屏广告",
        "跳过视频广告",
        "立即跳过",
        "关闭广告",
        "关闭此广告",
        "关闭开屏广告",
        "关闭推广",
        "Skip Ad",
        "Skip Ads",
        "skip ad",
        "skip ads",
        "skip_ad",
        "ad_skip",
        "skip button",
        "Skip Video Ad",
        "Close Ad",
        "close ad",
        "Close",
        "close",
        "×",
        "✕",
        "关闭",
        "关闭按钮",
        "进入应用",
        "我知道了"
    )

    val defaultViewIdKeywords = listOf(
        "skip",
        "skip_ad",
        "skipad",
        "ad_skip",
        "skip_btn",
        "btn_skip",
        "splash_skip",
        "splash_skip_btn",
        "splash_ad_skip",
        "ad_skip_btn",
        "tt_splash_skip",
        "tt_splash_skip_btn",
        "ksad_skip",
        "ksad_splash_skip",
        "bd_ad_skip",
        "gdt_skip",
        "close_ad",
        "ad_close",
        "close_btn"
    )

    fun getKeywords(context: Context): List<String> {
        return SettingsRepository.prefs(context)
            .getStringSet(KEY_KEYWORDS, defaultKeywords.toSet())
            .orEmpty()
            .cleanConfigItems()
            .filterNot { it.isStandaloneSkipKeyword() }
    }

    fun saveKeywords(context: Context, keywords: Collection<String>) {
        SettingsRepository.prefs(context)
            .edit { putStringSet(KEY_KEYWORDS, keywords.cleanConfigItems().toSet()) }
    }

    fun getViewIdKeywords(context: Context): List<String> {
        return SettingsRepository.prefs(context)
            .getStringSet(KEY_VIEW_ID_KEYWORDS, defaultViewIdKeywords.toSet())
            .orEmpty()
            .cleanConfigItems()
    }

    fun saveViewIdKeywords(context: Context, keywords: Collection<String>) {
        SettingsRepository.prefs(context)
            .edit { putStringSet(KEY_VIEW_ID_KEYWORDS, keywords.cleanConfigItems().toSet()) }
    }

    fun defaultDefaultRuleConfig(): DefaultRuleConfig = DefaultRuleConfig()

    fun getDefaultRuleConfig(context: Context): DefaultRuleConfig {
        val prefs = SettingsRepository.prefs(context)
        return sanitizeDefaultRuleConfig(
            DefaultRuleConfig(
                validDurationMs = prefs.getLong(KEY_DEFAULT_RULE_WINDOW_MS, DEFAULT_RULE_WINDOW_MS),
                minScore = prefs.getInt(KEY_DEFAULT_RULE_MIN_SCORE, DEFAULT_RULE_MIN_SCORE),
                area = RuleArea.fromValue(
                    prefs.getString(KEY_DEFAULT_RULE_AREA, RuleArea.TopRight.value).orEmpty()
                ) ?: RuleArea.TopRight,
                cooldownMs = prefs.getLong(KEY_DEFAULT_RULE_COOLDOWN_MS, DEFAULT_RULE_COOLDOWN_MS)
            )
        )
    }

    fun saveDefaultRuleConfig(context: Context, config: DefaultRuleConfig): DefaultRuleConfig {
        val cleanConfig = sanitizeDefaultRuleConfig(config)
        SettingsRepository.prefs(context).edit {
            putLong(KEY_DEFAULT_RULE_WINDOW_MS, cleanConfig.validDurationMs)
            putInt(KEY_DEFAULT_RULE_MIN_SCORE, cleanConfig.minScore)
            putString(KEY_DEFAULT_RULE_AREA, cleanConfig.area.value)
            putLong(KEY_DEFAULT_RULE_COOLDOWN_MS, cleanConfig.cooldownMs)
        }
        return cleanConfig
    }

    fun resetDefaultRuleConfig(context: Context) {
        SettingsRepository.prefs(context).edit {
            remove(KEY_DEFAULT_RULE_WINDOW_MS)
            remove(KEY_DEFAULT_RULE_MIN_SCORE)
            remove(KEY_DEFAULT_RULE_AREA)
            remove(KEY_DEFAULT_RULE_COOLDOWN_MS)
        }
    }

    internal fun sanitizeDefaultRuleConfig(config: DefaultRuleConfig): DefaultRuleConfig {
        val duration = if (config.validDurationMs in defaultRuleDurationOptionsMs) {
            config.validDurationMs
        } else {
            DEFAULT_RULE_WINDOW_MS
        }
        return config.copy(
            validDurationMs = duration,
            minScore = config.minScore.coerceIn(MIN_DEFAULT_RULE_SCORE, MAX_DEFAULT_RULE_SCORE),
            cooldownMs = config.cooldownMs.coerceAtLeast(MIN_DEFAULT_RULE_COOLDOWN_MS)
        )
    }

    fun getRules(context: Context): List<SkipRule> {
        val raw = SettingsRepository.prefs(context).getString(KEY_RULES_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        val validDurationMs = DEFAULT_RULE_WINDOW_MS
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toSkipRule()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
            .map { normalizeRuleWindow(it, validDurationMs) }
    }

    fun saveRules(context: Context, rules: List<SkipRule>) {
        SettingsRepository.prefs(context)
            .edit {
                putString(KEY_RULES_JSON, JSONArray().apply {
                    rules.forEach { put(it.toJson()) }
                }.toString())
            }
        cleanupRulePackages(context, rules)
    }

    fun getEnabledRulesForPackage(context: Context, packageName: String): List<SkipRule> {
        if (packageName.isBlank()) return emptyList()
        val policy = SettingsRepository.getAppPolicy(context, packageName)
        val customRules = if (policy.customRulesEnabled) {
            getEnabledCustomRulesForPackage(context, packageName)
        } else {
            emptyList()
        }
        val builtInRule = if (policy.defaultRuleEnabled) {
            listOf(createBuiltInRuleForPackage(context, packageName))
        } else {
            emptyList()
        }
        return (customRules + builtInRule)
            .sortedWith(compareByDescending<SkipRule> { it.priority }.thenBy { it.createdAt })
    }

    fun hasRulesForPackage(context: Context, packageName: String): Boolean {
        return getCustomRulesForPackage(context, packageName).isNotEmpty()
    }

    fun getCustomRulesForPackage(context: Context, packageName: String): List<SkipRule> {
        val enabledPackages = getRulePackages(context).filter { it.enabled }.map { it.id }.toSet()
        return getRules(context)
            .filter { rule ->
                rule.packageName == packageName &&
                    rule.source != RuleSource.BuiltIn &&
                    (rule.packageId == "local" || rule.packageId in enabledPackages)
            }
            .sortedWith(compareByDescending<SkipRule> { it.priority }.thenBy { it.createdAt })
    }

    fun getEnabledCustomRulesForPackage(context: Context, packageName: String): List<SkipRule> {
        return getCustomRulesForPackage(context, packageName).filter { it.enabled }
    }

    fun getBuiltInRuleForPackage(context: Context, packageName: String): SkipRule {
        return createBuiltInRuleForPackage(context, packageName)
    }

    fun upsertRule(context: Context, rule: SkipRule) {
        val updated = getRules(context)
            .filterNot { it.id == rule.id }
            .plus(rule)
        saveRules(context, updated)
    }

    fun deleteRule(context: Context, ruleId: String) {
        saveRules(context, getRules(context).filterNot { it.id == ruleId })
    }

    fun setRuleEnabled(context: Context, ruleId: String, enabled: Boolean) {
        saveRules(
            context,
            getRules(context).map { if (it.id == ruleId) it.copy(enabled = enabled) else it }
        )
    }

    fun disableRulesForPackage(context: Context, packageName: String): Int {
        val rules = getRules(context)
        var changed = 0
        val updated = rules.map { rule ->
            if (rule.packageName == packageName && rule.enabled) {
                changed++
                rule.copy(enabled = false)
            } else {
                rule
            }
        }
        if (changed > 0) saveRules(context, updated)
        return changed
    }

    fun saveImportResult(
        context: Context,
        result: RuleImportResult,
        strategy: DuplicateStrategy
    ): Int {
        val packageInfo = result.rulePackage ?: return 0
        val existingRules = getRules(context).toMutableList()
        val existingIds = existingRules.map { it.id }.toSet()
        var savedCount = 0

        result.rules.forEach { rule ->
            val index = existingRules.indexOfFirst { it.id == rule.id }
            when {
                index >= 0 && strategy == DuplicateStrategy.Skip -> Unit
                index >= 0 && strategy == DuplicateStrategy.Override -> {
                    existingRules[index] = rule
                    savedCount++
                }
                index >= 0 && strategy == DuplicateStrategy.Merge -> {
                    existingRules[index] = existingRules[index].mergeWith(rule)
                    savedCount++
                }
                rule.id !in existingIds -> {
                    existingRules.add(rule)
                    savedCount++
                }
            }
        }

        saveRules(context, existingRules)
        if (result.appPolicies.isNotEmpty()) {
            result.appPolicies.forEach { SettingsRepository.setAppPolicy(context, it) }
        }
        if (existingRules.any { it.packageId == packageInfo.id }) {
            upsertRulePackage(context, packageInfo)
        } else {
            cleanupRulePackages(context, existingRules)
        }
        return savedCount
    }

    fun getRulePackages(context: Context): List<RulePackage> {
        val raw = SettingsRepository.prefs(context)
            .getString(KEY_RULE_PACKAGES_JSON, null)
            .orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toRulePackage()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun deleteRulePackage(context: Context, packageId: String) {
        saveRules(context, getRules(context).filterNot { it.packageId == packageId })
        saveRulePackages(context, getRulePackages(context).filterNot { it.id == packageId })
    }

    fun setRulePackageEnabled(context: Context, packageId: String, enabled: Boolean) {
        saveRulePackages(
            context,
            getRulePackages(context).map { if (it.id == packageId) it.copy(enabled = enabled) else it }
        )
    }

    fun exportRulesAsJson(context: Context): String {
        val packageJson = JSONObject()
            .put("schemaVersion", 2)
            .put("name", "Skip 本地规则导出")
            .put("version", 1)
            .put("author", "local")
            .put("updateTime", java.time.LocalDate.now().toString())
            .put("description", "从 Skip App 本地导出的规则")

        val apps = JSONArray()
        getRules(context)
            .groupBy { it.packageName }
            .forEach { (packageName, rules) ->
                apps.put(
                    JSONObject()
                        .put("packageName", packageName)
                        .put("appName", rules.firstOrNull()?.appName.orEmpty())
                        .put("enabled", true)
                        .put("rules", JSONArray().apply {
                            rules.forEach { put(it.toJson(includePackage = false)) }
                        })
                )
            }
        val policies = JSONArray().apply {
            SettingsRepository.getAppPolicies(context).forEach { policy ->
                put(
                    JSONObject()
                        .put("packageName", policy.packageName)
                        .put("defaultRuleEnabled", policy.defaultRuleEnabled)
                        .put("customRulesEnabled", policy.customRulesEnabled)
                        .put("migratedFromBlacklist", policy.migratedFromBlacklist)
                )
            }
        }
        return packageJson
            .put("appPolicies", policies)
            .put("apps", apps)
            .toString(2)
    }

    fun createLocalPackageIfNeeded(context: Context) {
        if (getRulePackages(context).none { it.id == "local" }) {
            upsertRulePackage(
                context,
                RulePackage(
                    id = "local",
                    name = "本地创建规则",
                    version = 1,
                    author = "local",
                    updateTime = "",
                    description = "用户在 App 内创建的规则",
                    source = RuleSource.UserSimple
                )
            )
        }
    }

    private fun createBuiltInRuleForPackage(context: Context, packageName: String): SkipRule {
        val defaultRuleConfig = getDefaultRuleConfig(context)
        return SkipRule(
            id = "built_in_$packageName",
            source = RuleSource.BuiltIn,
            name = "默认开屏跳过",
            packageName = packageName,
            appName = packageName,
            matchTexts = getKeywords(context),
            matchContentDescriptions = getKeywords(context),
            matchViewIds = getViewIdKeywords(context),
            area = defaultRuleConfig.area,
            priority = 1,
            cooldownMs = defaultRuleConfig.cooldownMs,
            validDurationMs = defaultRuleConfig.validDurationMs,
            minScore = defaultRuleConfig.minScore,
            packageId = "built_in"
        )
    }

    private fun normalizeRuleWindow(rule: SkipRule, validDurationMs: Long = DEFAULT_RULE_WINDOW_MS): SkipRule {
        return rule.copy(validDurationMs = validDurationMs)
    }

    private fun upsertRulePackage(context: Context, rulePackage: RulePackage) {
        val updated = getRulePackages(context)
            .filterNot { it.id == rulePackage.id }
            .plus(rulePackage)
        saveRulePackages(context, updated)
    }

    internal fun removeOrphanRulePackages(
        packages: List<RulePackage>,
        rules: List<SkipRule>
    ): List<RulePackage> {
        val usedPackageIds = rules.map { it.packageId }.filter { it.isNotBlank() }.toSet()
        return packages.filter { rulePackage ->
            rulePackage.id == "local" || rulePackage.id in usedPackageIds
        }
    }

    private fun cleanupRulePackages(context: Context, rules: List<SkipRule> = getRules(context)) {
        val packages = getRulePackages(context)
        val cleaned = removeOrphanRulePackages(packages, rules)
        if (cleaned.size != packages.size) {
            saveRulePackages(context, cleaned)
        }
    }

    private fun saveRulePackages(context: Context, packages: List<RulePackage>) {
        SettingsRepository.prefs(context)
            .edit {
                putString(KEY_RULE_PACKAGES_JSON, JSONArray().apply {
                    packages.forEach { put(it.toJson()) }
                }.toString())
            }
    }

    private fun SkipRule.mergeWith(other: SkipRule): SkipRule {
        return copy(
            name = other.name.ifBlank { name },
            appName = other.appName.ifBlank { appName },
            enabled = other.enabled,
            matchTexts = (matchTexts + other.matchTexts).cleanConfigItems(),
            matchContentDescriptions = (
                matchContentDescriptions + other.matchContentDescriptions
                ).cleanConfigItems(),
            matchViewIds = (matchViewIds + other.matchViewIds).cleanConfigItems(),
            textMatchMode = other.textMatchMode,
            contentDescriptionMatchMode = other.contentDescriptionMatchMode,
            viewIdMatchMode = other.viewIdMatchMode,
            area = other.area,
            priority = maxOf(priority, other.priority),
            cooldownMs = other.cooldownMs,
            validDurationMs = other.validDurationMs,
            minScore = other.minScore,
            coordinateFallback = other.coordinateFallback ?: coordinateFallback,
            packageId = other.packageId
        )
    }

    internal fun SkipRule.toJson(includePackage: Boolean = true): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("source", source.value)
            put("name", name)
            if (includePackage) put("packageName", packageName)
            if (includePackage) put("appName", appName)
            put("enabled", enabled)
            put("activityName", activityName)
            put("matchTexts", matchTexts.toJsonArray())
            put("matchContentDescriptions", matchContentDescriptions.toJsonArray())
            put("matchViewIds", matchViewIds.toJsonArray())
            put("textMatchMode", textMatchMode.value)
            put("contentDescriptionMatchMode", contentDescriptionMatchMode.value)
            put("viewIdMatchMode", viewIdMatchMode.value)
            put("area", area.value)
            put("action", action.value)
            put("priority", priority)
            put("cooldownMs", cooldownMs)
            put("validDurationMs", validDurationMs)
            put("minScore", minScore)
            coordinateFallback?.let { put("coordinateFallback", it.toJson()) }
            put("packageId", packageId)
            put("createdAt", createdAt)
        }
    }

    private fun JSONObject.toSkipRule(): SkipRule? {
        val action = RuleAction.fromValue(optString("action", RuleAction.Click.value)) ?: return null
        val area = RuleArea.fromValue(optString("area", RuleArea.TopRight.value)) ?: return null
        val source = RuleSource.fromValue(optString("source", RuleSource.UserSimple.value))
        return SkipRule(
            id = optString("id").ifBlank { "rule_${UUID.randomUUID()}" },
            source = source,
            name = optString("name", "开屏跳过"),
            packageName = optString("packageName"),
            appName = optString("appName"),
            enabled = optBoolean("enabled", true),
            activityName = optString("activityName", "*"),
            matchTexts = optJSONArray("matchTexts").toStringList(),
            matchContentDescriptions = optJSONArray("matchContentDescriptions").toStringList(),
            matchViewIds = optJSONArray("matchViewIds").toStringList(),
            textMatchMode = MatchMode.fromValue(optString("textMatchMode", MatchMode.Contains.value)),
            contentDescriptionMatchMode = MatchMode.fromValue(
                optString("contentDescriptionMatchMode", MatchMode.Contains.value)
            ),
            viewIdMatchMode = MatchMode.fromValue(optString("viewIdMatchMode", MatchMode.Contains.value)),
            area = area,
            action = action,
            priority = optInt("priority", 10),
            cooldownMs = optLong("cooldownMs", 1200L),
            validDurationMs = DEFAULT_RULE_WINDOW_MS,
            minScore = optInt("minScore", 70),
            coordinateFallback = optJSONObject("coordinateFallback")?.toCoordinateFallback(),
            packageId = optString("packageId", "local"),
            createdAt = optLong("createdAt", System.currentTimeMillis())
        )
    }

    private fun RulePackage.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("version", version)
            .put("author", author)
            .put("updateTime", updateTime)
            .put("description", description)
            .put("enabled", enabled)
            .put("source", source.value)
            .put("createdAt", createdAt)
    }

    private fun JSONObject.toRulePackage(): RulePackage? {
        return RulePackage(
            id = optString("id").ifBlank { "pkg_${UUID.randomUUID()}" },
            name = optString("name", "规则包"),
            version = optInt("version", 1),
            author = optString("author", "local"),
            updateTime = optString("updateTime"),
            description = optString("description"),
            enabled = optBoolean("enabled", true),
            source = RuleSource.fromValue(optString("source", RuleSource.JsonFile.value)),
            createdAt = optLong("createdAt", System.currentTimeMillis())
        )
    }

    private fun Collection<String>.toJsonArray(): JSONArray {
        return JSONArray().apply { cleanConfigItems().forEach(::put) }
    }

    internal fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }.cleanConfigItems()
    }

    internal fun Collection<String>.cleanConfigItems(): List<String> {
        return map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    private fun String.isStandaloneSkipKeyword(): Boolean {
        return trim().equals("skip", ignoreCase = true)
    }

    private fun CoordinateFallback.toJson(): JSONObject {
        return JSONObject()
            .put("enabled", enabled)
            .put("xRatio", xRatio.toDouble())
            .put("yRatio", yRatio.toDouble())
            .put("anchorTexts", anchorTexts.toJsonArray())
            .put("anchorContentDescriptions", anchorContentDescriptions.toJsonArray())
            .put("anchorViewIds", anchorViewIds.toJsonArray())
    }

    private fun JSONObject.toCoordinateFallback(): CoordinateFallback {
        return CoordinateFallback(
            enabled = optBoolean("enabled", false),
            xRatio = optDouble("xRatio", 0.0).toFloat(),
            yRatio = optDouble("yRatio", 0.0).toFloat(),
            anchorTexts = optJSONArray("anchorTexts").toStringList(),
            anchorContentDescriptions = optJSONArray("anchorContentDescriptions").toStringList(),
            anchorViewIds = optJSONArray("anchorViewIds").toStringList()
        )
    }
}
