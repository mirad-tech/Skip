package com.example.skip.data

import com.example.skip.model.RuleAction
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleImportResult
import com.example.skip.model.RulePackage
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import org.json.JSONObject
import java.util.UUID

object RuleImportManager {
    private const val LONG_DURATION_WARNING_MS = 30_000L
    private const val MIN_COOLDOWN_MS = 800L

    fun parseRulePackage(jsonText: String, selfPackageName: String = ""): RuleImportResult {
        if (jsonText.isBlank()) {
            return RuleImportResult(false, "JSON 不能为空")
        }

        val root = runCatching { JSONObject(jsonText) }.getOrElse {
            return RuleImportResult(false, "JSON 格式错误：${it.message.orEmpty()}")
        }

        val apps = root.optJSONArray("apps")
            ?: return RuleImportResult(false, "apps 不能为空")
        if (apps.length() == 0) {
            return RuleImportResult(false, "apps 不能为空")
        }

        val warnings = mutableListOf<String>()
        val rules = mutableListOf<SkipRule>()
        val packageId = "json_${UUID.randomUUID()}"
        val rulePackage = RulePackage(
            id = packageId,
            name = root.optString("name", "本地规则包"),
            version = root.optInt("version", 1),
            author = root.optString("author", "local"),
            updateTime = root.optString("updateTime"),
            description = root.optString("description"),
            source = RuleSource.JsonFile
        )

        for (appIndex in 0 until apps.length()) {
            val app = apps.optJSONObject(appIndex)
                ?: return RuleImportResult(false, "第 ${appIndex + 1} 个 App 配置不是对象")
            val packageName = app.optString("packageName").trim()
            if (packageName.isBlank()) {
                return RuleImportResult(false, "第 ${appIndex + 1} 个 App 的 packageName 不能为空")
            }
            if (selfPackageName.isNotBlank() && packageName == selfPackageName) {
                return RuleImportResult(false, "不能为 Skip 自身导入自动跳过规则")
            }

            val appRules = app.optJSONArray("rules")
                ?: return RuleImportResult(false, "$packageName 的 rules 不能为空")
            if (appRules.length() == 0) {
                return RuleImportResult(false, "$packageName 的 rules 不能为空")
            }

            for (ruleIndex in 0 until appRules.length()) {
                val ruleJson = appRules.optJSONObject(ruleIndex)
                    ?: return RuleImportResult(false, "$packageName 第 ${ruleIndex + 1} 条规则不是对象")
                val parsed = parseRule(
                    appPackageName = packageName,
                    appName = app.optString("appName", packageName),
                    packageId = packageId,
                    ruleJson = ruleJson,
                    warningMessages = warnings
                )
                if (!parsed.success) return parsed
                rules += parsed.rules
            }
        }

        return RuleImportResult(
            success = true,
            warningMessages = warnings.distinct(),
            parsedAppCount = apps.length(),
            parsedRuleCount = rules.size,
            rulePackage = rulePackage,
            rules = rules
        )
    }

    fun createSimpleRule(
        packageName: String,
        appName: String,
        name: String,
        texts: List<String>,
        area: RuleArea,
        validDurationMs: Long,
        avoidRepeatClick: Boolean,
        selfPackageName: String = ""
    ): RuleImportResult {
        val cleanTexts = texts.cleanItems()
        if (packageName.isBlank()) return RuleImportResult(false, "请选择应用")
        if (selfPackageName.isNotBlank() && packageName == selfPackageName) {
            return RuleImportResult(false, "不能为 Skip 自身创建自动跳过规则")
        }
        if (cleanTexts.isEmpty()) return RuleImportResult(false, "按钮文字不能为空")

        val warnings = buildList {
            if (area == RuleArea.Any) add("位置选择“不确定”会提高误触风险，已自动提高匹配分数要求。")
            if (validDurationMs > LONG_DURATION_WARNING_MS) {
                add("任意时间规则会提高误触风险，请只用于按钮文字明确的弹窗。")
            }
        }
        val safeName = name.ifBlank {
            "首页弹窗关闭"
        }
        val minScore = when {
            area == RuleArea.Any && validDurationMs > LONG_DURATION_WARNING_MS -> 90
            area == RuleArea.Any -> 85
            validDurationMs > LONG_DURATION_WARNING_MS -> 82
            else -> 72
        }
        val rule = SkipRule(
            id = "user_rule_${UUID.randomUUID()}",
            source = RuleSource.UserSimple,
            name = safeName,
            packageName = packageName,
            appName = appName.ifBlank { packageName },
            matchTexts = cleanTexts,
            matchContentDescriptions = cleanTexts,
            matchViewIds = emptyList(),
            area = area,
            priority = 100,
            cooldownMs = if (avoidRepeatClick) 1200L else 800L,
            validDurationMs = validDurationMs,
            minScore = minScore,
            packageId = "local"
        )
        return RuleImportResult(
            success = true,
            warningMessages = warnings,
            parsedAppCount = 1,
            parsedRuleCount = 1,
            rules = listOf(rule)
        )
    }

    fun validateForSave(rule: SkipRule): List<String> {
        return buildList {
            if (rule.area == RuleArea.Any) add("位置为“不确定”，建议只用于按钮文字非常明确的场景。")
            if (rule.minScore < 60) add("最低分过低，可能增加误触风险。")
            if (rule.validDurationMs > LONG_DURATION_WARNING_MS) add("任意时间规则风险更高，请确认关键词明确。")
            if (rule.cooldownMs < MIN_COOLDOWN_MS) add("点击间隔低于 800ms，可能导致重复点击。")
        }
    }

    fun sampleJson(): String {
        return """
            {
              "name": "开屏跳过规则包",
              "version": 1,
              "author": "local",
              "updateTime": "2026-01-01",
              "description": "用于开屏辅助点击的规则包",
              "apps": [
                {
                  "packageName": "com.example.app",
                  "appName": "示例 App",
                  "enabled": true,
                  "rules": [
                    {
                      "id": "example_skip_001",
                      "name": "开屏跳过按钮",
                      "enabled": true,
                      "activityName": "*",
                      "matchTexts": ["跳过", "跳过广告", "Skip"],
                      "matchContentDescriptions": ["跳过", "Skip"],
                      "matchViewIds": ["skip", "ad_skip", "close"],
                      "area": "top_right",
                      "action": "click",
                      "priority": 10,
                      "cooldownMs": 1200,
                      "validDurationMs": 10000,
                      "minScore": 70
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    private fun parseRule(
        appPackageName: String,
        appName: String,
        packageId: String,
        ruleJson: JSONObject,
        warningMessages: MutableList<String>
    ): RuleImportResult {
        val id = ruleJson.optString("id").trim()
        if (id.isBlank()) return RuleImportResult(false, "$appPackageName 的 rule id 不能为空")

        val action = RuleAction.fromValue(ruleJson.optString("action", "click"))
            ?: return RuleImportResult(false, "$id 的 action 暂只支持 click")
        val area = RuleArea.fromValue(ruleJson.optString("area", "any"))
            ?: return RuleImportResult(false, "$id 的 area 不合法")

        val cooldownMs = ruleJson.optLong("cooldownMs", 1200L)
        val validDurationMs = ruleJson.optLong("validDurationMs", 10_000L)
        val minScore = ruleJson.optInt("minScore", 70)
        val matchTexts = ruleJson.optJSONArray("matchTexts").toStringList()
        val matchContentDescriptions = ruleJson
            .optJSONArray("matchContentDescriptions")
            .toStringList()
        val matchViewIds = ruleJson.optJSONArray("matchViewIds").toStringList()

        if (cooldownMs < MIN_COOLDOWN_MS) warningMessages += "$id 的 cooldownMs 低于 800，已导入但不推荐。"
        if (validDurationMs > LONG_DURATION_WARNING_MS) warningMessages += "$id 的 validDurationMs 较长，可能增加误触风险。"
        if (minScore !in 0..100) return RuleImportResult(false, "$id 的 minScore 必须在 0 到 100 之间")
        if (minScore < 60) warningMessages += "$id 的 minScore 较低，可能增加误触风险。"
        if (area == RuleArea.Any) warningMessages += "$id 使用 area=any，请确认关键词足够明确。"
        if (matchTexts.isEmpty() && matchContentDescriptions.isEmpty() && matchViewIds.isEmpty()) {
            return RuleImportResult(false, "$id 至少需要一种匹配字段")
        }

        return RuleImportResult(
            success = true,
            rules = listOf(
                SkipRule(
                    id = id,
                    source = RuleSource.JsonFile,
                    name = ruleJson.optString("name", id),
                    packageName = appPackageName,
                    appName = appName,
                    enabled = ruleJson.optBoolean("enabled", true),
                    activityName = ruleJson.optString("activityName", "*"),
                    matchTexts = matchTexts,
                    matchContentDescriptions = matchContentDescriptions,
                    matchViewIds = matchViewIds,
                    area = area,
                    action = action,
                    priority = ruleJson.optInt("priority", 10),
                    cooldownMs = cooldownMs,
                    validDurationMs = validDurationMs,
                    minScore = minScore,
                    packageId = packageId
                )
            )
        )
    }

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }.cleanItems()
    }

    private fun List<String>.cleanItems(): List<String> {
        return flatMap { it.split(',', '，', ';', '；', '\n', '\t', ' ') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
