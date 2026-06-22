package com.example.skip.data

import com.example.skip.model.AppPolicy
import com.example.skip.model.CoordinateFallback
import com.example.skip.model.DuplicateStrategy
import com.example.skip.model.MatchMode
import com.example.skip.model.RuleAction
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleImportResult
import com.example.skip.model.RulePackage
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import com.example.skip.engine.CoordinateFallbackMatcher
import com.example.skip.engine.HighRiskClickPolicy
import com.example.skip.engine.SafeRegexMatcher
import com.example.skip.util.SimpleJson
import com.example.skip.util.SimpleJsonArray
import com.example.skip.util.SimpleJsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

object RuleImportManager {
    private const val MIN_COOLDOWN_MS = 800L

    fun readJsonText(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > RuleImportRiskPolicy.MAX_JSON_FILE_BYTES) {
                throw IllegalArgumentException(
                    "JSON 文件超过 ${RuleImportRiskPolicy.MAX_JSON_FILE_BYTES / 1024}KB 限制"
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    fun parseRulePackage(jsonText: String, selfPackageName: String = ""): RuleImportResult {
        if (jsonText.isBlank()) {
            return RuleImportResult(false, "JSON 不能为空")
        }
        if (jsonText.toByteArray(Charsets.UTF_8).size > RuleImportRiskPolicy.MAX_JSON_FILE_BYTES) {
            return RuleImportResult(
                false,
                "JSON 文件超过 ${RuleImportRiskPolicy.MAX_JSON_FILE_BYTES / 1024}KB 限制"
            )
        }

        val root = runCatching {
            SimpleJson.parseObject(jsonText, RuleImportRiskPolicy.MAX_JSON_NESTING_DEPTH)
        }.getOrElse {
            return RuleImportResult(false, "JSON 格式错误：${it.message.orEmpty()}")
        }

        val apps = root.optJSONArray("apps")
            ?: return RuleImportResult(false, "apps 不能为空")
        if (apps.length() == 0) {
            return RuleImportResult(false, "apps 不能为空")
        }
        if (apps.length() > RuleImportRiskPolicy.MAX_APP_COUNT) {
            return RuleImportResult(false, "apps 数量超过限制")
        }

        val warnings = mutableListOf<String>()
        val extraConfirmationMessages = mutableListOf<String>()
        val rules = mutableListOf<SkipRule>()
        val appPolicies = mutableListOf<AppPolicy>()
        val schemaVersion = root.optInt("schemaVersion", 1)
        if (schemaVersion > 2) {
            warnings += "schemaVersion=$schemaVersion 高于当前支持版本，未知字段会被忽略。"
        }
        val policyArray = root.optJSONArray("appPolicies")
        if (policyArray != null) {
            if (policyArray.length() > RuleImportRiskPolicy.MAX_APP_COUNT) {
                return RuleImportResult(false, "appPolicies 数量超过限制")
            }
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
            if (app.has("enabled")) {
                warnings += "$packageName 的 apps[].enabled 当前不生效；请使用 appPolicies 控制应用策略。"
            }

            val appRules = app.optJSONArray("rules")
                ?: return RuleImportResult(false, "$packageName 的 rules 不能为空")
            if (appRules.length() == 0) {
                return RuleImportResult(false, "$packageName 的 rules 不能为空")
            }
            if (appRules.length() > RuleImportRiskPolicy.MAX_RULES_PER_APP) {
                return RuleImportResult(false, "$packageName 的 rules 数量超过限制")
            }

            for (ruleIndex in 0 until appRules.length()) {
                if (rules.size >= RuleImportRiskPolicy.MAX_RULE_COUNT) {
                    return RuleImportResult(false, "规则总数超过限制")
                }
                val ruleJson = appRules.optJSONObject(ruleIndex)
                    ?: return RuleImportResult(false, "$packageName 第 ${ruleIndex + 1} 条规则不是对象")
                val parsed = parseRule(
                    appPackageName = packageName,
                    appName = app.optString("appName", packageName),
                    packageId = packageId,
                    ruleJson = ruleJson,
                    warningMessages = warnings,
                    extraConfirmationMessages = extraConfirmationMessages,
                    selfPackageName = selfPackageName
                )
                if (!parsed.success) return parsed
                rules += parsed.rules
            }
        }

        if (rules.size >= RuleImportRiskPolicy.MAX_RULE_COUNT / 2) {
            val message = "规则数量较多，可能影响扫描性能"
            warnings += message
            extraConfirmationMessages += message
        }

        return RuleImportResult(
            success = true,
            warningMessages = warnings.distinct(),
            extraConfirmationMessages = extraConfirmationMessages.distinct(),
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
            return RuleImportResult(false, "硬性阻断：坐标兜底比例必须在 0 到 1 之间")
        }
        if (coordinateFallback?.enabled == true && !coordinateFallback.hasAnchorRequirement()) {
            return RuleImportResult(false, "硬性阻断：坐标兜底必须配置锚点规则")
        }
        coordinateFallback?.takeIf { it.enabled }
            ?.let(CoordinateFallbackMatcher::anchorValidationReason)
            ?.let { return RuleImportResult(false, "硬性阻断：坐标兜底锚点不可信：$it") }
        if (cleanTexts.isEmpty() &&
            cleanDescriptions.isEmpty() &&
            cleanViewIds.isEmpty() &&
            coordinateFallback?.enabled != true
        ) {
            return RuleImportResult(false, "至少需要填写文字、描述、View ID 或启用坐标兜底")
        }
        val effectiveWindowMs = if (coordinateFallback?.enabled == true) {
            RuleRepository.canonicalCoordinateFallbackWindowMs(validDurationMs)
        } else {
            RuleRepository.DEFAULT_RULE_WINDOW_MS
        }

        val warnings = buildList {
            if (area == RuleArea.Any) add("位置选为“不确定”会提高误触风险，已自动提高匹配分数要求。")
            if (coordinateFallback?.enabled == true && validDurationMs != effectiveWindowMs) {
                add("坐标兜底时间窗已收紧为最多 ${coordinateFallbackWindowSeconds()} 秒。")
            } else if (validDurationMs != RuleRepository.DEFAULT_RULE_WINDOW_MS) {
                add("为减少误触，自定义规则已统一收紧为应用前台后的 ${defaultWindowSeconds()} 秒内生效。")
            }
            if (coordinateFallback?.enabled == true) {
                add("坐标兜底只会在普通节点匹配失败后执行，并仍受包名、时间窗和安全保护限制。")
            }
        }.toMutableList()
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
            validDurationMs = effectiveWindowMs,
            minScore = minScore,
            coordinateFallback = coordinateFallback,
            packageId = "local"
        )
        val risks = RuleImportRiskPolicy.assess(
            rule = rule,
            selfPackageName = selfPackageName,
            importedFromJson = false
        )
        risks.firstOrNull { it.level == RuleImportRiskLevel.HardBlock }?.let { risk ->
            return RuleImportResult(false, "硬性阻断：${risk.message}")
        }
        risks.filter { it.level == RuleImportRiskLevel.ExtraConfirm }.forEach { risk ->
            warnings += "需要额外确认：${risk.message}"
        }
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
                    if (rule.validDurationMs > RuleRepository.MAX_COORDINATE_FALLBACK_WINDOW_MS) {
                        add("坐标兜底时间窗不能超过 ${coordinateFallbackWindowSeconds()} 秒，保存时会收紧。")
                    }
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
                  "rules": [
                    {
                      "id": "example_skip_001",
                      "name": "开屏页面跳过控件",
                      "activityName": "*",
                      "matchTexts": ["跳过", "关闭", "Skip"],
                      "matchContentDescriptions": ["跳过", "Skip"],
                      "matchViewIds": ["com.example.app:id/ad_skip", "com.example.app:id/splash_close"],
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
        warningMessages: MutableList<String>,
        extraConfirmationMessages: MutableList<String>,
        selfPackageName: String
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
        val textMatchMode = MatchMode.fromValue(
            ruleJson.optString("textMatchMode", MatchMode.Contains.value)
        )
        val contentDescriptionMatchMode = MatchMode.fromValue(
            ruleJson.optString("contentDescriptionMatchMode", MatchMode.Contains.value)
        )
        val viewIdMatchMode = MatchMode.fromValue(
            ruleJson.optString("viewIdMatchMode", MatchMode.Contains.value)
        )
        val matchTexts = ruleJson.optJSONArray("matchTexts")
            .toStringList(splitEntries = textMatchMode != MatchMode.Regex)
        val matchContentDescriptions = ruleJson
            .optJSONArray("matchContentDescriptions")
            .toStringList(splitEntries = contentDescriptionMatchMode != MatchMode.Regex)
        val matchViewIds = ruleJson.optJSONArray("matchViewIds")
            .toStringList(splitEntries = viewIdMatchMode != MatchMode.Regex)
        validateRegexPatterns(id, "matchTexts", matchTexts, textMatchMode)?.let { return it }
        validateRegexPatterns(
            id,
            "matchContentDescriptions",
            matchContentDescriptions,
            contentDescriptionMatchMode
        )?.let { return it }
        validateRegexPatterns(id, "matchViewIds", matchViewIds, viewIdMatchMode)?.let { return it }
        val coordinateJson = ruleJson.optJSONObject("coordinateFallback")
        if (coordinateJson?.optBoolean("enabled", false) == true &&
            (!coordinateJson.has("xRatio") || !coordinateJson.has("yRatio"))
        ) {
            return RuleImportResult(false, "硬性阻断：$id 的 coordinateFallback 需要 xRatio 和 yRatio")
        }
        val coordinateFallback = coordinateJson
            ?.toCoordinateFallback()
            ?.also { fallback ->
                if (!fallback.isValid()) {
                    return RuleImportResult(false, "硬性阻断：$id 的 coordinateFallback 坐标比例必须在 0 到 1 之间")
                }
                if (fallback.enabled && !fallback.hasAnchorRequirement()) {
                    return RuleImportResult(false, "硬性阻断：$id 的 coordinateFallback 必须配置锚点")
                }
                if (fallback.enabled) {
                    CoordinateFallbackMatcher.anchorValidationReason(fallback)?.let { reason ->
                        return RuleImportResult(false, "硬性阻断：$id 的 coordinateFallback 锚点不可信：$reason")
                    }
                }
            }

        if (coordinateFallback?.enabled == true && cooldownMs < MIN_COOLDOWN_MS) {
            return RuleImportResult(false, "$id 启用 coordinateFallback 时 cooldownMs 不能低于 800")
        }
        if (cooldownMs < MIN_COOLDOWN_MS) warningMessages += "$id 的 cooldownMs 低于 800，已导入但不推荐。"
        val effectiveWindowMs = if (coordinateFallback?.enabled == true) {
            RuleRepository.canonicalCoordinateFallbackWindowMs(validDurationMs)
        } else {
            RuleRepository.DEFAULT_RULE_WINDOW_MS
        }
        if (coordinateFallback?.enabled == true && validDurationMs != effectiveWindowMs) {
            warningMessages += "$id 的 coordinateFallback 时间窗已收紧到 ${effectiveWindowMs}ms。"
        } else if (validDurationMs != RuleRepository.DEFAULT_RULE_WINDOW_MS) {
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
        val requestedEnabled = ruleJson.optBoolean("enabled", true)
        val rule = SkipRule(
            id = id,
            source = RuleSource.JsonFile,
            name = ruleJson.optString("name", id),
            packageName = appPackageName,
            appName = appName,
            enabled = false,
            activityName = ruleJson.optString("activityName", "*"),
            matchTexts = matchTexts,
            matchContentDescriptions = matchContentDescriptions,
            matchViewIds = matchViewIds,
            textMatchMode = textMatchMode,
            contentDescriptionMatchMode = contentDescriptionMatchMode,
            viewIdMatchMode = viewIdMatchMode,
            area = area,
            action = action,
            priority = ruleJson.optInt("priority", 10),
            cooldownMs = cooldownMs,
            validDurationMs = effectiveWindowMs,
            minScore = minScore,
            coordinateFallback = coordinateFallback,
            packageId = packageId
        )
        val risks = RuleImportRiskPolicy.assess(
            rule = rule,
            selfPackageName = selfPackageName,
            importedFromJson = true
        )
        risks.firstOrNull { it.level == RuleImportRiskLevel.HardBlock }?.let { risk ->
            return RuleImportResult(false, "硬性阻断：$id ${risk.message}")
        }
        risks.filter { it.level == RuleImportRiskLevel.ExtraConfirm }.forEach { risk ->
            val message = "$id 需要额外确认：${risk.message}"
            warningMessages += message
            extraConfirmationMessages += message
        }
        if (requestedEnabled) {
            warningMessages += "$id 来自 JSON，默认以停用状态导入；建议先观察再本地启用。"
        }

        return RuleImportResult(
            success = true,
            rules = listOf(rule)
        )
    }

    private fun SimpleJsonArray?.toStringList(splitEntries: Boolean = true): List<String> {
        if (this == null) return emptyList()
        val entries = buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        return if (splitEntries) entries.cleanItems() else entries.distinct()
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

    private fun validateRegexPatterns(
        ruleId: String,
        fieldName: String,
        patterns: List<String>,
        matchMode: MatchMode
    ): RuleImportResult? {
        if (matchMode != MatchMode.Regex) return null
        val invalidPattern = patterns.firstOrNull { pattern -> !SafeRegexMatcher.isValid(pattern) }
            ?: return null
        return RuleImportResult(false, "$ruleId 的 $fieldName 包含非法正则：$invalidPattern")
    }

    fun previewImport(result: RuleImportResult, strategy: DuplicateStrategy): List<String> {
        val pkg = result.rulePackage
        return buildList {
            add("规则包：${pkg?.name.orEmpty()}")
            add("作者：${pkg?.author.orEmpty()}")
            add("版本：${pkg?.version ?: 1}")
            add("更新时间：${pkg?.updateTime.orEmpty()}")
            add("描述：${pkg?.description.orEmpty()}")
            add("App 数量：${result.parsedAppCount}")
            add("规则数量：${result.parsedRuleCount}")
            add("硬性阻断规则：0")
            add("需要额外确认：${result.extraConfirmationMessages.size} 项")
            add("坐标兜底规则：${result.rules.count { it.coordinateFallback?.enabled == true }}")
            add(
                "regex 规则：${result.rules.count { rule ->
                    rule.textMatchMode == MatchMode.Regex ||
                        rule.contentDescriptionMatchMode == MatchMode.Regex ||
                        rule.viewIdMatchMode == MatchMode.Regex
                }}"
            )
            add("area=any 规则：${result.rules.count { it.area == RuleArea.Any }}")
            add("最终会导入规则：${result.rules.size}")
            add(
                "最终状态：enabled=${result.rules.count { it.enabled }} " +
                    "disabled=${result.rules.count { !it.enabled }}"
            )
            add("重复处理：${strategy.label}")
            result.appPolicies.forEach { policy ->
                add(
                    "应用策略：${policy.packageName} defaultRuleEnabled=${policy.defaultRuleEnabled} " +
                        "customRulesEnabled=${policy.customRulesEnabled}"
                )
            }
            result.rules.forEach { rule ->
                add(
                    "规则：${rule.appName.ifBlank { rule.packageName }} (${rule.packageName}) / " +
                        "${rule.name} enabled=${rule.enabled} activity=${rule.activityName}"
                )
                add(
                    "匹配：matchTexts=${rule.matchTexts.previewValue()} " +
                        "matchContentDescriptions=${rule.matchContentDescriptions.previewValue()} " +
                        "matchViewIds=${rule.matchViewIds.previewValue()}"
                )
                add(
                    "模式：textMatchMode=${rule.textMatchMode.value} " +
                        "contentDescriptionMatchMode=${rule.contentDescriptionMatchMode.value} " +
                        "viewIdMatchMode=${rule.viewIdMatchMode.value}"
                )
                add(
                    "动作：action=${rule.action.value} area=${rule.area.value} " +
                        "minScore=${rule.minScore} cooldownMs=${rule.cooldownMs} " +
                        "validDurationMs=${rule.validDurationMs}"
                )
                val fallback = rule.coordinateFallback
                add(
                    "坐标兜底：coordinateFallback=${if (fallback?.enabled == true) "enabled" else "disabled"} " +
                        "xRatio=${fallback?.xRatio ?: 0f} yRatio=${fallback?.yRatio ?: 0f} " +
                        "anchorTexts=${fallback?.anchorTexts.orEmpty().previewValue()} " +
                        "anchorContentDescriptions=${fallback?.anchorContentDescriptions.orEmpty().previewValue()} " +
                        "anchorViewIds=${fallback?.anchorViewIds.orEmpty().previewValue()}"
                )
                rule.extraConfirmationFlags().takeIf { it.isNotEmpty() }?.let { flags ->
                    add("额外确认：${flags.joinToString("、")}")
                }
            }
            result.extraConfirmationMessages.forEach { add("需要额外确认：$it") }
            result.warningMessages
                .filterNot { it in result.extraConfirmationMessages }
                .forEach { add("提示：$it") }
        }
    }

    private fun defaultWindowSeconds(): Long {
        return RuleRepository.DEFAULT_RULE_WINDOW_MS / 1000
    }

    private fun coordinateFallbackWindowSeconds(): Long {
        return RuleRepository.MAX_COORDINATE_FALLBACK_WINDOW_MS / 1000
    }

    private fun List<String>.previewValue(): String {
        return if (isEmpty()) "-" else joinToString(",")
    }

    private fun SkipRule.extraConfirmationFlags(): List<String> {
        return buildList {
            if (textMatchMode == MatchMode.Regex ||
                contentDescriptionMatchMode == MatchMode.Regex ||
                viewIdMatchMode == MatchMode.Regex
            ) {
                add("regex")
            }
            if (matchTexts.isEmpty() && matchContentDescriptions.isEmpty() && matchViewIds.isNotEmpty()) {
                add("纯 View ID")
            }
            if (coordinateFallback?.enabled == true) add("坐标兜底")
            if (minScore < 60) add("低 minScore")
            if (area == RuleArea.Any) add("area=any")
        }
    }
}
