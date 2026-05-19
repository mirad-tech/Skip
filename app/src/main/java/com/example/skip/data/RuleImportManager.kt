package com.example.skip.data

import com.example.skip.model.AppPolicy
import com.example.skip.model.CoordinateFallback
import com.example.skip.model.MatchMode
import com.example.skip.model.RuleAction
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleImportResult
import com.example.skip.model.RulePackage
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import com.example.skip.engine.HighRiskClickPolicy
import com.example.skip.util.SimpleJson
import com.example.skip.util.SimpleJsonArray
import com.example.skip.util.SimpleJsonObject
import java.util.UUID

object RuleImportManager {
    private const val MIN_COOLDOWN_MS = 800L

    fun parseRulePackage(jsonText: String, selfPackageName: String = ""): RuleImportResult {
        if (jsonText.isBlank()) {
            return RuleImportResult(false, "JSON 不能为空")
        }

        val root = runCatching { SimpleJson.parseObject(jsonText) }.getOrElse {
            return RuleImportResult(false, "JSON 格式错误：${it.message.orEmpty()}")
        }

        val apps = root.optJSONArray("apps")
            ?: return RuleImportResult(false, "apps 不能为空")
        if (apps.length() == 0) {
            return RuleImportResult(false, "apps 不能为空")
        }

        val warnings = mutableListOf<String>()
        val rules = mutableListOf<SkipRule>()
        val appPolicies = mutableListOf<AppPolicy>()
        val schemaVersion = root.optInt("schemaVersion", 1)
        if (schemaVersion > 2) {
            warnings += "schemaVersion=$schemaVersion 高于当前支持版本，未知字段会被忽略。"
        }
        val policyArray = root.optJSONArray("appPolicies")
        if (policyArray != null) {
            for (index in 0 until policyArray.length()) {
                val policyJson = policyArray.optJSONObject(index)
                    ?: return RuleImportResult(false, "第 ${index + 1} 个 appPolicy 不是对象")
                val policyPackage = policyJson.optString("packageName").trim()
                if (policyPackage.isBlank()) {
                    return RuleImportResult(false, "第 ${index + 1} 个 appPolicy 的 packageName 不能为空")
                }
                if (selfPackageName.isNotBlank() && policyPackage == selfPackageName) {
                    warnings += "已忽略 Skip 自身的 appPolicy。"
                    continue
                }
                appPolicies += AppPolicy(
                    packageName = policyPackage,
                    defaultRuleEnabled = policyJson.optBoolean("defaultRuleEnabled", true),
                    customRulesEnabled = policyJson.optBoolean("customRulesEnabled", true),
                    migratedFromBlacklist = policyJson.optBoolean("migratedFromBlacklist", false)
                )
            }
        }
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
            rules = rules,
            appPolicies = appPolicies.distinctBy { it.packageName }
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
        return createLocalRule(
            packageName = packageName,
            appName = appName,
            name = name,
            texts = texts,
            contentDescriptions = texts,
            viewIds = emptyList(),
            area = area,
            enabled = true,
            priority = 100,
            cooldownMs = if (avoidRepeatClick) 1200L else 800L,
            validDurationMs = validDurationMs,
            minScore = if (area == RuleArea.Any) 85 else 72,
            coordinateFallback = null,
            selfPackageName = selfPackageName
        )
    }

    fun createLocalRule(
        packageName: String,
        appName: String,
        name: String,
        texts: List<String>,
        contentDescriptions: List<String>,
        viewIds: List<String>,
        area: RuleArea,
        enabled: Boolean,
        priority: Int,
        cooldownMs: Long,
        validDurationMs: Long,
        minScore: Int,
        coordinateFallback: CoordinateFallback?,
        selfPackageName: String = ""
    ): RuleImportResult {
        val cleanTexts = texts.cleanItems()
        val cleanDescriptions = contentDescriptions.cleanItems()
        val cleanViewIds = viewIds.cleanItems()
        if (packageName.isBlank()) return RuleImportResult(false, "请选择应用")
        if (selfPackageName.isNotBlank() && packageName == selfPackageName) {
            return RuleImportResult(false, "不能为 Skip 自身创建自动跳过规则")
        }
        if (minScore !in 0..100) return RuleImportResult(false, "最低分必须在 0 到 100 之间")
        if (cooldownMs < MIN_COOLDOWN_MS) return RuleImportResult(false, "点击间隔不能低于 800ms")
        if (coordinateFallback?.isValid() == false) {
            return RuleImportResult(false, "坐标兜底比例必须在 0 到 1 之间")
        }
        if (coordinateFallback?.enabled == true && !coordinateFallback.hasAnchorRequirement()) {
            return RuleImportResult(false, "坐标兜底必须配置锚点规则")
        }
        val highRiskDecision = HighRiskClickPolicy.evaluateTexts(
            listOf(name) + cleanTexts + cleanDescriptions +
                (coordinateFallback?.anchorTexts.orEmpty()) +
                (coordinateFallback?.anchorContentDescriptions.orEmpty())
        )
        if (!highRiskDecision.allowed) {
            return RuleImportResult(
                false,
                "规则包含高风险点击内容：${HighRiskClickPolicy.BLOCKED_REASON}（${highRiskDecision.matchedTerm}）"
            )
        }
        if (cleanTexts.isEmpty() &&
            cleanDescriptions.isEmpty() &&
            cleanViewIds.isEmpty() &&
            coordinateFallback?.enabled != true
        ) {
            return RuleImportResult(false, "至少需要填写文字、描述、View ID 或启用坐标兜底")
        }

        val warnings = buildList {
            if (area == RuleArea.Any) add("位置选为“不确定”会提高误触风险，已自动提高匹配分数要求。")
            if (validDurationMs != RuleRepository.DEFAULT_RULE_WINDOW_MS) {
                add("为减少误触，自定义规则已统一收紧为应用前台后的 ${defaultWindowSeconds()} 秒内生效。")
            }
            if (coordinateFallback?.enabled == true) {
                add("坐标兜底只会在普通节点匹配失败后执行，并仍受包名、时间窗和安全保护限制。")
            }
        }
        val rule = SkipRule(
            id = "user_rule_${UUID.randomUUID()}",
            source = RuleSource.UserSimple,
            name = name.ifBlank { "首页弹窗关闭" },
            packageName = packageName,
            appName = appName.ifBlank { packageName },
            enabled = enabled,
            matchTexts = cleanTexts,
            matchContentDescriptions = cleanDescriptions,
            matchViewIds = cleanViewIds,
            area = area,
            priority = priority,
            cooldownMs = cooldownMs,
            validDurationMs = RuleRepository.DEFAULT_RULE_WINDOW_MS,
            minScore = minScore,
            coordinateFallback = coordinateFallback,
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
            if (rule.validDurationMs != RuleRepository.DEFAULT_RULE_WINDOW_MS) {
                add("当前版本默认只在应用打开后的前 ${defaultWindowSeconds()} 秒执行自定义规则。")
            }
            if (rule.cooldownMs < MIN_COOLDOWN_MS) add("点击间隔低于 800ms，可能导致重复点击。")
            rule.coordinateFallback?.let { fallback ->
                if (fallback.enabled) {
                    add("坐标兜底只建议用于包名明确、锚点明确且普通节点匹配失败的场景。")
                }
                if (!fallback.isValid()) add("坐标兜底比例必须在 0 到 1 之间。")
            }
        }
    }

    fun sampleJson(): String {
        return """
            {
              "name": "开屏页面助手规则包",
              "schemaVersion": 2,
              "version": 1,
              "author": "local",
              "updateTime": "2026-01-01",
              "description": "用于开屏页面低风险辅助点击的规则包",
              "apps": [
                {
                  "packageName": "com.example.app",
                  "appName": "示例 App",
                  "enabled": true,
                  "rules": [
                    {
                      "id": "example_skip_001",
                      "name": "开屏页面跳过控件",
                      "enabled": true,
                      "activityName": "*",
                      "matchTexts": ["跳过", "关闭", "Skip"],
                      "matchContentDescriptions": ["跳过", "Skip"],
                      "matchViewIds": ["skip", "close"],
                      "textMatchMode": "contains",
                      "contentDescriptionMatchMode": "contains",
                      "viewIdMatchMode": "contains",
                      "area": "top_right",
                      "action": "click",
                      "priority": 10,
                      "cooldownMs": 1200,
                      "validDurationMs": 8000,
                      "minScore": 70,
                      "coordinateFallback": {
                        "enabled": false,
                        "xRatio": 0.9,
                        "yRatio": 0.12,
                        "anchorTexts": ["开屏提示"]
                      }
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
        ruleJson: SimpleJsonObject,
        warningMessages: MutableList<String>
    ): RuleImportResult {
        val id = ruleJson.optString("id").trim()
        if (id.isBlank()) return RuleImportResult(false, "$appPackageName 的 rule id 不能为空")

        val action = RuleAction.fromValue(ruleJson.optString("action", "click"))
            ?: return RuleImportResult(false, "$id 的 action 暂只支持 click")
        val area = RuleArea.fromValue(ruleJson.optString("area", "any"))
            ?: return RuleImportResult(false, "$id 的 area 不合法")

        val cooldownMs = ruleJson.optLong("cooldownMs", 1200L)
        val validDurationMs = ruleJson.optLong("validDurationMs", RuleRepository.DEFAULT_RULE_WINDOW_MS)
        val minScore = ruleJson.optInt("minScore", 70)
        val matchTexts = ruleJson.optJSONArray("matchTexts").toStringList()
        val matchContentDescriptions = ruleJson
            .optJSONArray("matchContentDescriptions")
            .toStringList()
        val matchViewIds = ruleJson.optJSONArray("matchViewIds").toStringList()
        val coordinateJson = ruleJson.optJSONObject("coordinateFallback")
        if (coordinateJson?.optBoolean("enabled", false) == true &&
            (!coordinateJson.has("xRatio") || !coordinateJson.has("yRatio"))
        ) {
            return RuleImportResult(false, "$id 的 coordinateFallback 需要 xRatio 和 yRatio")
        }
        val coordinateFallback = coordinateJson
            ?.toCoordinateFallback()
            ?.also { fallback ->
                if (!fallback.isValid()) {
                    return RuleImportResult(false, "$id 的 coordinateFallback 坐标比例必须在 0 到 1 之间")
                }
                if (fallback.enabled && !fallback.hasAnchorRequirement()) {
                    return RuleImportResult(false, "$id 的 coordinateFallback 必须配置锚点")
                }
            }

        if (coordinateFallback?.enabled == true && cooldownMs < MIN_COOLDOWN_MS) {
            return RuleImportResult(false, "$id 启用 coordinateFallback 时 cooldownMs 不能低于 800")
        }
        if (cooldownMs < MIN_COOLDOWN_MS) warningMessages += "$id 的 cooldownMs 低于 800，已导入但不推荐。"
        if (validDurationMs != RuleRepository.DEFAULT_RULE_WINDOW_MS) {
            warningMessages += "$id 的 validDurationMs 已收紧到 ${RuleRepository.DEFAULT_RULE_WINDOW_MS}ms，使用过程中不再默认扫描。"
        }
        if (minScore !in 0..100) return RuleImportResult(false, "$id 的 minScore 必须在 0 到 100 之间")
        if (minScore < 60) warningMessages += "$id 的 minScore 较低，可能增加误触风险。"
        if (area == RuleArea.Any) warningMessages += "$id 使用 area=any，请确认关键词足够明确。"
        if (matchTexts.isEmpty() &&
            matchContentDescriptions.isEmpty() &&
            matchViewIds.isEmpty() &&
            coordinateFallback?.enabled != true
        ) {
            return RuleImportResult(false, "$id 至少需要一种匹配字段")
        }
        val highRiskDecision = HighRiskClickPolicy.evaluateTexts(
            listOf(ruleJson.optString("name", id)) +
                matchTexts +
                matchContentDescriptions +
                coordinateFallback?.anchorTexts.orEmpty() +
                coordinateFallback?.anchorContentDescriptions.orEmpty()
        )
        if (!highRiskDecision.allowed) {
            return RuleImportResult(
                false,
                "$id 被安全策略拦截：${HighRiskClickPolicy.BLOCKED_REASON}（${highRiskDecision.matchedTerm}）"
            )
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
                    textMatchMode = MatchMode.fromValue(
                        ruleJson.optString("textMatchMode", MatchMode.Contains.value)
                    ),
                    contentDescriptionMatchMode = MatchMode.fromValue(
                        ruleJson.optString("contentDescriptionMatchMode", MatchMode.Contains.value)
                    ),
                    viewIdMatchMode = MatchMode.fromValue(
                        ruleJson.optString("viewIdMatchMode", MatchMode.Contains.value)
                    ),
                    area = area,
                    action = action,
                    priority = ruleJson.optInt("priority", 10),
                    cooldownMs = cooldownMs,
                    validDurationMs = RuleRepository.DEFAULT_RULE_WINDOW_MS,
                    minScore = minScore,
                    coordinateFallback = coordinateFallback,
                    packageId = packageId
                )
            )
        )
    }

    private fun SimpleJsonArray?.toStringList(): List<String> {
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

    private fun SimpleJsonObject.toCoordinateFallback(): CoordinateFallback {
        return CoordinateFallback(
            enabled = optBoolean("enabled", false),
            xRatio = optDouble("xRatio", 0.0).toFloat(),
            yRatio = optDouble("yRatio", 0.0).toFloat(),
            anchorTexts = optJSONArray("anchorTexts").toStringList(),
            anchorContentDescriptions = optJSONArray("anchorContentDescriptions").toStringList(),
            anchorViewIds = optJSONArray("anchorViewIds").toStringList()
        )
    }

    private fun defaultWindowSeconds(): Long {
        return RuleRepository.DEFAULT_RULE_WINDOW_MS / 1000
    }
}
