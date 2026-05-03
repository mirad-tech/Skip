package com.example.skip.data

import android.content.Context
import com.example.skip.model.DuplicateStrategy
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
    const val DEFAULT_RULE_WINDOW_MS = 6_000L

    private const val KEY_KEYWORDS = "keywords"
    private const val KEY_VIEW_ID_KEYWORDS = "view_id_keywords"
    private const val KEY_RULES_JSON = "rules_json_v2"
    private const val KEY_RULE_PACKAGES_JSON = "rule_packages_json_v2"

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
        "关闭",
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
            .edit()
            .putStringSet(KEY_KEYWORDS, keywords.cleanConfigItems().toSet())
            .apply()
    }

    fun getViewIdKeywords(context: Context): List<String> {
        return SettingsRepository.prefs(context)
            .getStringSet(KEY_VIEW_ID_KEYWORDS, defaultViewIdKeywords.toSet())
            .orEmpty()
            .cleanConfigItems()
    }

    fun saveViewIdKeywords(context: Context, keywords: Collection<String>) {
        SettingsRepository.prefs(context)
            .edit()
            .putStringSet(KEY_VIEW_ID_KEYWORDS, keywords.cleanConfigItems().toSet())
            .apply()
    }

    fun getRules(context: Context): List<SkipRule> {
        val raw = SettingsRepository.prefs(context).getString(KEY_RULES_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toSkipRule()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveRules(context: Context, rules: List<SkipRule>) {
        SettingsRepository.prefs(context)
            .edit()
            .putString(KEY_RULES_JSON, JSONArray().apply {
                rules.forEach { put(it.toJson()) }
            }.toString())
            .apply()
    }

    fun getEnabledRulesForPackage(context: Context, packageName: String): List<SkipRule> {
        if (packageName.isBlank()) return emptyList()
        val customRules = getEnabledCustomRulesForPackage(context, packageName)
        if (SettingsRepository.isBlacklisted(context, packageName)) {
            return customRules
        }
        return (customRules + createBuiltInRuleForPackage(context, packageName))
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
        upsertRulePackage(context, packageInfo)
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
        return packageJson.put("apps", apps).toString(2)
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
        return SkipRule(
            id = "built_in_$packageName",
            source = RuleSource.BuiltIn,
            name = "默认开屏跳过",
            packageName = packageName,
            appName = packageName,
            matchTexts = getKeywords(context),
            matchContentDescriptions = getKeywords(context),
            matchViewIds = getViewIdKeywords(context),
            area = RuleArea.TopRight,
            priority = 1,
            cooldownMs = 1500L,
            validDurationMs = DEFAULT_RULE_WINDOW_MS,
            minScore = 75,
            packageId = "built_in"
        )
    }

    private fun upsertRulePackage(context: Context, rulePackage: RulePackage) {
        val updated = getRulePackages(context)
            .filterNot { it.id == rulePackage.id }
            .plus(rulePackage)
        saveRulePackages(context, updated)
    }

    private fun saveRulePackages(context: Context, packages: List<RulePackage>) {
        SettingsRepository.prefs(context)
            .edit()
            .putString(KEY_RULE_PACKAGES_JSON, JSONArray().apply {
                packages.forEach { put(it.toJson()) }
            }.toString())
            .apply()
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
            area = other.area,
            priority = maxOf(priority, other.priority),
            cooldownMs = other.cooldownMs,
            validDurationMs = other.validDurationMs,
            minScore = other.minScore,
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
            put("area", area.value)
            put("action", action.value)
            put("priority", priority)
            put("cooldownMs", cooldownMs)
            put("validDurationMs", validDurationMs)
            put("minScore", minScore)
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
            area = area,
            action = action,
            priority = optInt("priority", 10),
            cooldownMs = optLong("cooldownMs", 1200L),
            validDurationMs = optLong(
                "validDurationMs",
                if (source == RuleSource.BuiltIn) DEFAULT_RULE_WINDOW_MS else 10_000L
            ).let { if (source == RuleSource.BuiltIn && it == 10_000L) DEFAULT_RULE_WINDOW_MS else it },
            minScore = optInt("minScore", 70),
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
}
