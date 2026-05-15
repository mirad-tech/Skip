package com.example.skip

import com.example.skip.data.LogRepository
import com.example.skip.data.DiagnosticReportRepository
import com.example.skip.data.IconManager
import com.example.skip.data.InstalledAppStatus
import com.example.skip.data.JsonExportWriter
import com.example.skip.data.RuleImportManager
import com.example.skip.data.RuleRepository
import com.example.skip.data.SettingsRepository
import com.example.skip.data.StatsRepository
import com.example.skip.engine.CoordinateFallbackMatcher
import com.example.skip.engine.HighRiskClickPolicy
import com.example.skip.engine.RulePlanProvider
import com.example.skip.engine.SafetyGuard
import com.example.skip.engine.ScoreEvaluator
import com.example.skip.model.AppPolicy
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.CoordinateFallback
import com.example.skip.model.InstalledApp
import com.example.skip.model.RuleArea
import com.example.skip.model.RulePackage
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import com.example.skip.model.StatsWindow
import com.example.skip.service.ClickEffectVerifier
import com.example.skip.service.DelayedClickSafetyCheck
import com.example.skip.ui.logs.CLICK_LOG_DISPLAY_LIMIT
import com.example.skip.ui.logs.displayLogsForScreen
import com.example.skip.ui.onboarding.ReleaseDisclosureCopy
import com.example.skip.ui.apps.filterBlacklistStatuses
import com.example.skip.ui.common.initialVisibleCount
import com.example.skip.ui.common.nextVisibleCount
import com.example.skip.util.PrivacySanitizer
import com.example.skip.util.RomUtils
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyAndLogUnitTest {
    @Test
    fun bankAndPaymentPackagesAreProtected() {
        assertTrue(SafetyGuard.isProtectedPackage("com.icbc.android"))
        assertTrue(SafetyGuard.isProtectedPackage("cn.com.icbc.android"))
        assertTrue(SafetyGuard.isProtectedPackage("com.eg.android.AlipayGphone"))
        assertTrue(SafetyGuard.isProtectedPackage("com.example.wallet"))
        assertFalse(SafetyGuard.isProtectedPackage("com.example.news"))
    }

    @Test
    fun highRiskPolicyBlocksRequiredReleaseTerms() {
        listOf(
            "同意",
            "授权",
            "允许",
            "支付",
            "购买",
            "确认支付",
            "登录",
            "注册",
            "隐私政策",
            "用户协议",
            "安装",
            "删除",
            "卸载",
            "转账",
            "发送",
            "提交"
        ).forEach { term ->
            val decision = HighRiskClickPolicy.evaluateTexts(
                listOf("立即$term", "button:$term")
            )

            assertFalse("term should be blocked: $term", decision.allowed)
            assertEquals(HighRiskClickPolicy.BLOCKED_REASON, decision.reason)
            assertEquals(term, decision.matchedTerm)
        }
    }

    @Test
    fun highRiskPolicyAllowsLowRiskOpeningPageTerms() {
        val decision = HighRiskClickPolicy.evaluateTexts(
            listOf("跳过", "跳过开屏", "关闭")
        )

        assertTrue(decision.allowed)
        assertEquals("", decision.reason)
    }

    @Test
    fun releaseDisclosureCopyStatesLocalProcessingAndAccessibilityPurpose() {
        val text = ReleaseDisclosureCopy.allText()

        assertTrue(text.contains("本地自动点击辅助工具"))
        assertTrue(text.contains("开屏页面助手"))
        assertTrue(text.contains("无障碍权限"))
        assertTrue(text.contains("不上传屏幕内容"))
        assertTrue(text.contains("不联网"))
        listOf(
            "同意",
            "授权",
            "允许",
            "支付",
            "购买",
            "确认支付",
            "登录",
            "注册",
            "隐私政策",
            "用户协议",
            "安装",
            "删除",
            "卸载",
            "转账",
            "发送",
            "提交"
        ).forEach { term ->
            assertTrue("missing high-risk disclosure term: $term", text.contains(term))
        }
        assertFalse(text.contains("广告破解"))
        assertFalse(text.contains("广告屏蔽"))
        assertFalse(text.contains("绕过工具"))
    }

    @Test
    fun systemLauncherAndInputPackagesAreProtected() {
        assertTrue(SafetyGuard.isProtectedPackage("com.android.systemui"))
        assertTrue(SafetyGuard.isProtectedPackage("com.bbk.launcher2"))
        assertTrue(SafetyGuard.isProtectedPackage("com.vivo.upslide"))
        assertTrue(SafetyGuard.isProtectedPackage("com.android.packageinstaller"))
        assertTrue(SafetyGuard.isProtectedPackage("com.vivo.ai.ime.nex"))
        assertFalse(SafetyGuard.isProtectedPackage("com.MobileTicket"))
    }

    @Test
    fun defaultKeywordsDoNotContainStandaloneSkip() {
        assertFalse(RuleRepository.defaultKeywords.any { it.equals("skip", ignoreCase = true) })
        assertTrue(RuleRepository.defaultKeywords.any { it.equals("skip ad", ignoreCase = true) })
    }

    @Test
    fun defaultRuleWindowIsSixSeconds() {
        assertEquals(6_000L, RuleRepository.DEFAULT_RULE_WINDOW_MS)
    }

    @Test
    fun defaultRuleMinScoreIsStableForDiagnostics() {
        assertEquals(75, RuleRepository.DEFAULT_RULE_MIN_SCORE)
    }

    @Test
    fun appPolicyDefaultsAndLegacyBlacklistMigrationKeepCustomRulesEnabled() {
        val defaultPolicy = AppPolicy.defaultFor("com.example.news")
        val migratedPolicy = AppPolicy.fromLegacyBlacklist("com.example.video")
        val protectedDecision = AppPolicy.effectiveFor(
            policy = defaultPolicy,
            packageName = "com.android.systemui",
            selfPackageName = "com.example.skip"
        )
        val selfDecision = AppPolicy.effectiveFor(
            policy = defaultPolicy,
            packageName = "com.example.skip",
            selfPackageName = "com.example.skip"
        )

        assertTrue(defaultPolicy.defaultRuleEnabled)
        assertTrue(defaultPolicy.customRulesEnabled)
        assertFalse(migratedPolicy.defaultRuleEnabled)
        assertTrue(migratedPolicy.customRulesEnabled)
        assertTrue(migratedPolicy.migratedFromBlacklist)
        assertFalse(protectedDecision.defaultRuleEnabled)
        assertFalse(protectedDecision.customRulesEnabled)
        assertFalse(selfDecision.defaultRuleEnabled)
        assertFalse(selfDecision.customRulesEnabled)
    }

    @Test
    fun rulePlanProviderUsesPolicyBeforeReturningExecutableRules() {
        val customRule = SkipRule(
            id = "custom",
            source = RuleSource.UserSimple,
            name = "自定义",
            packageName = "com.example.news",
            appName = "News",
            priority = 100
        )
        val builtInRule = customRule.copy(
            id = "built_in",
            source = RuleSource.BuiltIn,
            name = "默认",
            priority = 1
        )

        val customOnly = RulePlanProvider.plan(
            packageName = "com.example.news",
            selfPackageName = "com.example.skip",
            policy = AppPolicy(
                packageName = "com.example.news",
                defaultRuleEnabled = false,
                customRulesEnabled = true
            ),
            customRules = listOf(customRule),
            builtInRule = builtInRule
        )
        val disabled = RulePlanProvider.plan(
            packageName = "com.example.news",
            selfPackageName = "com.example.skip",
            policy = AppPolicy(
                packageName = "com.example.news",
                defaultRuleEnabled = false,
                customRulesEnabled = false
            ),
            customRules = listOf(customRule),
            builtInRule = builtInRule
        )
        val protected = RulePlanProvider.plan(
            packageName = "com.android.systemui",
            selfPackageName = "com.example.skip",
            policy = AppPolicy.defaultFor("com.android.systemui"),
            customRules = listOf(customRule.copy(packageName = "com.android.systemui")),
            builtInRule = builtInRule.copy(packageName = "com.android.systemui")
        )

        assertEquals(listOf("custom"), customOnly.rules.map { it.id })
        assertEquals("custom_only", customOnly.scope)
        assertEquals(ClickLogStage.SkippedByBlacklist, disabled.skipStage)
        assertEquals("app_policy_disabled", disabled.failureReason)
        assertTrue(protected.rules.isEmpty())
        assertEquals(ClickLogStage.SkippedBySafety, protected.skipStage)
    }

    @Test
    fun customRuleFactoryAlsoUsesSixSeconds() {
        val result = RuleImportManager.createSimpleRule(
            packageName = "com.example.news",
            appName = "News",
            name = "首页弹窗关闭",
            texts = listOf("关闭"),
            area = RuleArea.TopRight,
            validDurationMs = Long.MAX_VALUE,
            avoidRepeatClick = true
        )

        assertTrue(result.success)
        assertEquals(RuleRepository.DEFAULT_RULE_WINDOW_MS, result.rules.first().validDurationMs)
        assertTrue(result.warningMessages.any { it.contains("6 秒") })
    }

    @Test
    fun defaultRuleDoesNotHardBlockByArea() {
        listOf(
            RuleArea.TopLeft,
            RuleArea.TopCenter,
            RuleArea.TopRight,
            RuleArea.Center,
            RuleArea.BottomLeft,
            RuleArea.BottomCenter,
            RuleArea.BottomRight
        ).forEach { area ->
            assertTrue(ScoreEvaluator.isDefaultRuleAreaAllowedForCandidate(area))
        }
    }

    @Test
    fun logStagesHaveStableValues() {
        assertEquals(
            ClickLogStage.ClickEffectConfirmed,
            ClickLogStage.fromValue("click_effect_confirmed")
        )
        assertEquals(
            ClickLogStage.RootWindowNull,
            ClickLogStage.fromValue("root_window_null")
        )
        assertEquals(
            ClickLogStage.ClickCancelledTimeWindowExpired,
            ClickLogStage.fromValue("click_cancelled_time_window_expired")
        )
        assertEquals(
            ClickLogStage.SkippedByCooldown,
            ClickLogStage.fromValue("skipped_by_cooldown")
        )
    }

    @Test
    fun delayedClickPackageCheckAllowsSamePackageWithinWindow() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "com.example.news",
            selfPackageName = "com.example.skip",
            foregroundPackageName = "com.example.news",
            foregroundStartTimeMillis = 1_000L,
            now = 6_000L,
            defaultRuleWindowMs = 6_000L,
            rootWindowNull = false
        )

        assertTrue(result.allowed)
    }

    @Test
    fun delayedClickPackageCheckBlocksSystemUiBeforeClick() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "com.android.systemui",
            selfPackageName = "com.example.skip",
            foregroundPackageName = "com.example.news",
            foregroundStartTimeMillis = 1_000L,
            now = 2_000L,
            defaultRuleWindowMs = 6_000L,
            rootWindowNull = false
        )

        assertFalse(result.allowed)
        assertEquals(ClickLogStage.SkippedBySafety, result.stage)
        assertEquals("safety_guard_blocked", result.reason)
        assertEquals("safety_guard_before_delayed_click", result.blockedReason)
    }

    @Test
    fun delayedClickPackageCheckBlocksLauncherBeforeClick() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "com.bbk.launcher2",
            selfPackageName = "com.example.skip",
            foregroundPackageName = "com.example.news",
            foregroundStartTimeMillis = 1_000L,
            now = 2_000L,
            defaultRuleWindowMs = 6_000L,
            rootWindowNull = false
        )

        assertFalse(result.allowed)
        assertEquals(ClickLogStage.SkippedBySafety, result.stage)
        assertEquals("safety_guard_before_delayed_click", result.blockedReason)
    }

    @Test
    fun delayedClickPackageCheckBlocksSelfPackageBeforeClick() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "com.example.skip",
            selfPackageName = "com.example.skip",
            foregroundPackageName = "com.example.news",
            foregroundStartTimeMillis = 1_000L,
            now = 2_000L,
            defaultRuleWindowMs = 6_000L,
            rootWindowNull = false
        )

        assertFalse(result.allowed)
        assertEquals(ClickLogStage.ClickCancelledSelfPackage, result.stage)
        assertEquals("click_cancelled_self_package", result.reason)
    }

    @Test
    fun delayedClickPackageCheckBlocksOtherAppBeforeClick() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "com.example.other",
            selfPackageName = "com.example.skip",
            foregroundPackageName = "com.example.news",
            foregroundStartTimeMillis = 1_000L,
            now = 2_000L,
            defaultRuleWindowMs = 6_000L,
            rootWindowNull = false
        )

        assertFalse(result.allowed)
        assertEquals(ClickLogStage.ClickCancelledPackageChanged, result.stage)
        assertEquals("package_changed_before_click", result.reason)
    }

    @Test
    fun delayedClickPackageCheckBlocksUnknownPackageBeforeClick() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "",
            selfPackageName = "com.example.skip",
            foregroundPackageName = "com.example.news",
            foregroundStartTimeMillis = 1_000L,
            now = 2_000L,
            defaultRuleWindowMs = 6_000L,
            rootWindowNull = false
        )

        assertFalse(result.allowed)
        assertEquals(ClickLogStage.ClickCancelledPackageUnknown, result.stage)
        assertEquals("current_package_unknown_before_click", result.reason)
    }

    @Test
    fun delayedClickPackageCheckBlocksExpiredWindowBeforeClick() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "com.example.news",
            selfPackageName = "com.example.skip",
            foregroundPackageName = "com.example.news",
            foregroundStartTimeMillis = 1_000L,
            now = 7_100L,
            defaultRuleWindowMs = 6_000L,
            rootWindowNull = false
        )

        assertFalse(result.allowed)
        assertEquals(ClickLogStage.ClickCancelledTimeWindowExpired, result.stage)
        assertEquals("click_cancelled_time_window_expired", result.reason)
    }

    @Test
    fun delayedClickPackageCheckBlocksRootWindowNullBeforeClick() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "com.example.news",
            selfPackageName = "com.example.skip",
            foregroundPackageName = "com.example.news",
            foregroundStartTimeMillis = 1_000L,
            now = 2_000L,
            defaultRuleWindowMs = 6_000L,
            rootWindowNull = true
        )

        assertFalse(result.allowed)
        assertEquals(ClickLogStage.RootWindowNull, result.stage)
        assertEquals("root_window_null_before_click", result.reason)
    }

    @Test
    fun clickEffectVerifierBlocksSelfAppAfterClick() {
        val result = ClickEffectVerifier.evaluate(
            pendingPackageName = "com.example.news",
            selfPackageName = "com.example.skip",
            rootPackageName = "com.example.skip",
            foregroundPackageName = "com.example.news",
            rootWindowNull = false,
            targetStillPresent = false
        )

        assertFalse(result.success)
        assertEquals(ClickLogStage.ClickMisfireSelfOpened, result.stage)
        assertEquals("self_app_opened_after_click", result.reason)
    }

    @Test
    fun clickEffectVerifierBlocksProtectedPackageAfterClick() {
        val result = ClickEffectVerifier.evaluate(
            pendingPackageName = "com.example.news",
            selfPackageName = "com.example.skip",
            rootPackageName = "com.android.systemui",
            foregroundPackageName = "com.example.news",
            rootWindowNull = false,
            targetStillPresent = false
        )

        assertFalse(result.success)
        assertEquals(ClickLogStage.ClickEffectUnknown, result.stage)
        assertEquals("protected_package_after_click", result.reason)
    }

    @Test
    fun removeOrphanRulePackagesDropsPackagesWithoutRules() {
        val localRule = SkipRule(
            id = "local_rule",
            source = RuleSource.UserSimple,
            name = "本地规则",
            packageName = "com.example.news",
            appName = "News",
            packageId = "local"
        )
        val packages = listOf(
            RulePackage("local", "本地创建规则", 1, "local", "", "", source = RuleSource.UserSimple),
            RulePackage("json_keep", "保留", 1, "remote", "", "", source = RuleSource.JsonFile),
            RulePackage("json_orphan", "孤儿", 1, "remote", "", "", source = RuleSource.JsonFile)
        )
        val cleaned = RuleRepository.removeOrphanRulePackages(
            packages = packages,
            rules = listOf(localRule, localRule.copy(id = "json_rule", packageId = "json_keep"))
        )

        assertEquals(listOf("local", "json_keep"), cleaned.map { it.id })
    }

    @Test
    fun blacklistSearchMatchesAppName() {
        val statuses = listOf(
            appStatus(label = "新闻客户端", packageName = "com.example.news"),
            appStatus(label = "Video", packageName = "com.example.video")
        )

        val result = filterBlacklistStatuses(statuses, "新闻")

        assertEquals(listOf("com.example.news"), result.map { it.app.packageName })
    }

    @Test
    fun clickLogKeepsSafetyModeTargetAndWindowFields() {
        val log = ClickLog(
            timeMillis = 1L,
            packageName = "com.example.news",
            safetyModeEnabled = true,
            clickSkippedBySafetyMode = true,
            candidateBounds = "1,2,3,4",
            clickTargetSource = ClickTargetSourceLog.FixedPositionForbidden,
            foregroundPackage = "com.example.news",
            foregroundStartTimeMillis = 10L,
            elapsedSinceForegroundMs = 100L,
            defaultRuleWindowMs = 6_000L,
            isWithinDefaultRuleWindow = true,
            ruleScope = "default_splash_only",
            timeWindowDecision = "within_window"
        )

        assertTrue(log.safetyModeEnabled)
        assertTrue(log.clickSkippedBySafetyMode)
        assertEquals("1,2,3,4", log.candidateBounds)
        assertEquals(ClickTargetSourceLog.FixedPositionForbidden, log.clickTargetSource)
        assertEquals("default_splash_only", log.ruleScope)
        assertEquals("within_window", log.timeWindowDecision)
    }

    @Test
    fun coordinateFallbackValidatesRatiosAndBlocksBuiltInRules() {
        val fallback = CoordinateFallback(
            enabled = true,
            xRatio = 0.9f,
            yRatio = 0.12f,
            anchorTexts = listOf("跳过广告")
        )
        val rule = SkipRule(
            id = "coordinate",
            source = RuleSource.UserSimple,
            name = "坐标兜底",
            packageName = "com.example.news",
            appName = "News",
            matchTexts = listOf("跳过广告"),
            coordinateFallback = fallback
        )
        val allowed = CoordinateFallbackMatcher.evaluate(
            rule = rule,
            packageName = "com.example.news",
            selfPackageName = "com.example.skip",
            elapsedSinceForegroundMs = 500L,
            screenWidth = 1000,
            screenHeight = 2000,
            hasAnchor = true
        )
        val builtInBlocked = CoordinateFallbackMatcher.evaluate(
            rule = rule.copy(source = RuleSource.BuiltIn),
            packageName = "com.example.news",
            selfPackageName = "com.example.skip",
            elapsedSinceForegroundMs = 500L,
            screenWidth = 1000,
            screenHeight = 2000,
            hasAnchor = true
        )
        val invalid = CoordinateFallback(enabled = true, xRatio = 1.2f, yRatio = 0.2f)

        assertTrue(fallback.isValid())
        assertFalse(invalid.isValid())
        assertTrue(allowed.allowed)
        assertEquals(900, allowed.x)
        assertEquals(240, allowed.y)
        assertFalse(builtInBlocked.allowed)
        assertEquals("coordinate_fallback_built_in_forbidden", builtInBlocked.reason)
    }

    @Test
    fun coordinateFallbackRequiresPackageWindowAnchorCooldownAndSafeTerms() {
        val fallback = CoordinateFallback(
            enabled = true,
            xRatio = 0.9f,
            yRatio = 0.12f,
            anchorTexts = listOf("开屏提示")
        )
        val safeRule = SkipRule(
            id = "coordinate",
            source = RuleSource.UserSimple,
            name = "坐标兜底",
            packageName = "com.example.news",
            appName = "News",
            matchTexts = listOf("跳过"),
            cooldownMs = 1200L,
            validDurationMs = 6_000L,
            coordinateFallback = fallback
        )

        val allowed = CoordinateFallbackMatcher.evaluate(
            rule = safeRule,
            packageName = "com.example.news",
            selfPackageName = "com.example.skip",
            elapsedSinceForegroundMs = 500L,
            screenWidth = 1000,
            screenHeight = 2000,
            hasAnchor = true
        )
        val missingAnchorRequirement = CoordinateFallbackMatcher.evaluate(
            rule = safeRule.copy(
                coordinateFallback = fallback.copy(anchorTexts = emptyList())
            ),
            packageName = "com.example.news",
            selfPackageName = "com.example.skip",
            elapsedSinceForegroundMs = 500L,
            screenWidth = 1000,
            screenHeight = 2000,
            hasAnchor = true
        )
        val missingAnchorOnScreen = CoordinateFallbackMatcher.evaluate(
            rule = safeRule,
            packageName = "com.example.news",
            selfPackageName = "com.example.skip",
            elapsedSinceForegroundMs = 500L,
            screenWidth = 1000,
            screenHeight = 2000,
            hasAnchor = false
        )
        val noCooldown = CoordinateFallbackMatcher.evaluate(
            rule = safeRule.copy(cooldownMs = 0L),
            packageName = "com.example.news",
            selfPackageName = "com.example.skip",
            elapsedSinceForegroundMs = 500L,
            screenWidth = 1000,
            screenHeight = 2000,
            hasAnchor = true
        )
        val noLimitedWindow = CoordinateFallbackMatcher.evaluate(
            rule = safeRule.copy(validDurationMs = Long.MAX_VALUE),
            packageName = "com.example.news",
            selfPackageName = "com.example.skip",
            elapsedSinceForegroundMs = 500L,
            screenWidth = 1000,
            screenHeight = 2000,
            hasAnchor = true
        )
        val expired = CoordinateFallbackMatcher.evaluate(
            rule = safeRule,
            packageName = "com.example.news",
            selfPackageName = "com.example.skip",
            elapsedSinceForegroundMs = 7_000L,
            screenWidth = 1000,
            screenHeight = 2000,
            hasAnchor = true
        )
        val highRisk = CoordinateFallbackMatcher.evaluate(
            rule = safeRule.copy(matchTexts = listOf("同意")),
            packageName = "com.example.news",
            selfPackageName = "com.example.skip",
            elapsedSinceForegroundMs = 500L,
            screenWidth = 1000,
            screenHeight = 2000,
            hasAnchor = true
        )

        assertTrue(allowed.allowed)
        assertFalse(missingAnchorRequirement.allowed)
        assertEquals("coordinate_fallback_anchor_required", missingAnchorRequirement.reason)
        assertFalse(missingAnchorOnScreen.allowed)
        assertEquals("coordinate_fallback_anchor_missing", missingAnchorOnScreen.reason)
        assertFalse(noCooldown.allowed)
        assertEquals("coordinate_fallback_cooldown_required", noCooldown.reason)
        assertFalse(noLimitedWindow.allowed)
        assertEquals("coordinate_fallback_window_required", noLimitedWindow.reason)
        assertFalse(expired.allowed)
        assertEquals("coordinate_fallback_window_expired", expired.reason)
        assertFalse(highRisk.allowed)
        assertEquals(HighRiskClickPolicy.BLOCKED_REASON, highRisk.reason)
    }

    @Test
    fun importedRulesRejectHighRiskTermsAndUnsafeCoordinateFallback() {
        val highRiskJson = """
            {
              "name": "坏规则包",
              "apps": [
                {
                  "packageName": "com.example.news",
                  "rules": [
                    {
                      "id": "unsafe_agree",
                      "matchTexts": ["同意"],
                      "area": "top_right"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val noAnchorJson = """
            {
              "name": "坏规则包",
              "apps": [
                {
                  "packageName": "com.example.news",
                  "rules": [
                    {
                      "id": "bad_coordinate",
                      "matchTexts": ["跳过"],
                      "coordinateFallback": {
                        "enabled": true,
                        "xRatio": 0.9,
                        "yRatio": 0.12
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val highRisk = RuleImportManager.parseRulePackage(highRiskJson)
        val noAnchor = RuleImportManager.parseRulePackage(noAnchorJson)

        assertFalse(highRisk.success)
        assertTrue(highRisk.errorMessage.contains(HighRiskClickPolicy.BLOCKED_REASON))
        assertFalse(noAnchor.success)
        assertTrue(noAnchor.errorMessage.contains("锚点"))
    }

    @Test
    fun sampleRulesJsonCanBeImportedSafely() {
        val sampleFile = listOf(
            File("sample_rules.json"),
            File("../sample_rules.json")
        ).first { it.exists() }
        val json = sampleFile.readText()

        val result = RuleImportManager.parseRulePackage(json, selfPackageName = "com.example.skip")

        assertTrue(result.errorMessage, result.success)
        assertEquals(1, result.rules.size)
        assertFalse(result.rules.first().coordinateFallback?.enabled == true)
        assertTrue(
            HighRiskClickPolicy.evaluateRule(result.rules.first()).allowed
        )
    }

    @Test
    fun importedCoordinateFallbackRoundTripsIntoRuleModel() {
        val json = """
            {
              "schemaVersion": 2,
              "name": "本地规则包",
              "version": 1,
              "appPolicies": [
                {
                  "packageName": "com.example.news",
                  "defaultRuleEnabled": false,
                  "customRulesEnabled": true
                }
              ],
              "apps": [
                {
                  "packageName": "com.example.news",
                  "appName": "News",
                  "rules": [
                    {
                      "id": "coordinate_001",
                      "name": "右上角跳过",
                      "matchTexts": ["跳过广告"],
                      "area": "top_right",
                      "coordinateFallback": {
                        "enabled": true,
                        "xRatio": 0.9,
                        "yRatio": 0.12,
                        "anchorTexts": ["跳过广告"]
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = RuleImportManager.parseRulePackage(json, selfPackageName = "com.example.skip")

        assertTrue(result.errorMessage, result.success)
        assertEquals(1, result.appPolicies.size)
        assertFalse(result.appPolicies.first().defaultRuleEnabled)
        assertEquals(0.9f, result.rules.first().coordinateFallback?.xRatio ?: 0f, 0.001f)
        assertEquals(listOf("跳过广告"), result.rules.first().coordinateFallback?.anchorTexts)
    }

    @Test
    fun invalidCoordinateFallbackJsonIsRejected() {
        val json = """
            {
              "name": "坏规则包",
              "apps": [
                {
                  "packageName": "com.example.news",
                  "rules": [
                    {
                      "id": "bad_coordinate",
                      "matchTexts": ["跳过广告"],
                      "coordinateFallback": {
                        "enabled": true,
                        "xRatio": 1.4,
                        "yRatio": 0.2
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = RuleImportManager.parseRulePackage(json, selfPackageName = "com.example.skip")

        assertFalse(result.success)
        assertTrue(result.errorMessage.contains("coordinateFallback"))
    }

    @Test
    fun statsAggregateSuccessAndFailureByAppAndRuleFromLocalLogs() {
        val logs = listOf(
            ClickLog(
                timeMillis = 1_000L,
                packageName = "com.example.news",
                appName = "News",
                ruleId = "rule_a",
                ruleName = "跳过广告",
                stage = ClickLogStage.ClickEffectConfirmed,
                success = true
            ),
            ClickLog(
                timeMillis = 2_000L,
                packageName = "com.example.news",
                appName = "News",
                ruleId = "rule_a",
                ruleName = "跳过广告",
                stage = ClickLogStage.ClickFailed,
                success = false
            ),
            ClickLog(
                timeMillis = 3_000L,
                packageName = "com.example.video",
                appName = "Video",
                ruleId = "rule_b",
                ruleName = "关闭",
                stage = ClickLogStage.ClickSkippedBySafetyMode,
                success = null
            )
        )

        val stats = StatsRepository.aggregate(logs, StatsWindow.All, now = 10_000L)

        assertEquals(2, stats.appStats.size)
        assertEquals(2, stats.appStats.first { it.packageName == "com.example.news" }.totalCount)
        assertEquals(1, stats.appStats.first { it.packageName == "com.example.news" }.successCount)
        assertEquals(1, stats.appStats.first { it.packageName == "com.example.news" }.failureCount)
        assertEquals(2, stats.ruleStats.first { it.ruleId == "rule_a" }.totalCount)
        assertEquals(3, stats.stageStats.values.sum())
    }

    @Test
    fun statsAggregateSafetyBlockedAndCoordinateFallbackCounts() {
        val logs = listOf(
            ClickLog(
                timeMillis = 1_000L,
                packageName = "com.example.news",
                appName = "News",
                ruleId = "rule_a",
                ruleName = "打开页关闭",
                stage = ClickLogStage.SkippedBySafety,
                success = false,
                blockedBySafety = true,
                blockedReason = HighRiskClickPolicy.BLOCKED_REASON
            ),
            ClickLog(
                timeMillis = 2_000L,
                packageName = "com.example.news",
                appName = "News",
                ruleId = "rule_b",
                ruleName = "坐标兜底",
                stage = ClickLogStage.ClickEffectConfirmed,
                success = true,
                clickTargetSource = ClickTargetSourceLog.CoordinateFallback
            )
        )

        val stats = StatsRepository.aggregate(logs, StatsWindow.All, now = 10_000L)
        val app = stats.appStats.first { it.packageName == "com.example.news" }

        assertEquals(1, stats.safetyBlockedCount)
        assertEquals(1, stats.coordinateFallbackCount)
        assertEquals(1, app.safetyBlockedCount)
        assertEquals(1, app.coordinateFallbackCount)
    }

    @Test
    fun clickLogJsonKeepsNullableKeysWhenValuesAreNull() {
        val fields = LogRepository.clickLogJsonFields(
            ClickLog(
                timeMillis = 1L,
                packageName = "com.android.systemui",
                stage = ClickLogStage.SkippedBySafety,
                blockedBySafety = true,
                blockedReason = "safety_guard_before_delayed_click"
            )
        )

        listOf(
            "candidateCenterX",
            "candidateCenterY",
            "gestureX",
            "gestureY",
            "actionReturnValue",
            "effectConfirmed",
            "clickedParentDepth",
            "candidateAreaRatio",
            "foregroundStartTimeMillis",
            "elapsedSinceForegroundMs",
            "isWithinDefaultRuleWindow"
        ).forEach { key ->
            assertTrue("missing key: $key", fields.containsKey(key))
            assertEquals("not null: $key", LogRepository.JsonNullValue, fields[key])
        }
        assertEquals("", fields["candidateBounds"])
        assertEquals("", fields["clickedNodeBounds"])
        assertEquals("safety_guard_before_delayed_click", fields["blockedReason"])
        assertEquals(ClickTargetSourceLog.None.value, fields["clickTargetSource"])
    }

    @Test
    fun clickLogJsonKeepsRealClickCoordinates() {
        val fields = LogRepository.clickLogJsonFields(
            ClickLog(
                timeMillis = 1L,
                packageName = "com.example.news",
                stage = ClickLogStage.ClickActionSuccess,
                actionReturnValue = true,
                effectConfirmed = false,
                clickMethod = ClickMethodLog.DispatchGesture,
                candidateBounds = "10,20,30,40",
                candidateCenterX = 20,
                candidateCenterY = 30,
                gestureX = 20,
                gestureY = 30,
                clickedParentDepth = 1,
                candidateAreaRatio = 0.01f,
                clickTargetSource = ClickTargetSourceLog.GestureOnNodeCenter,
                foregroundPackage = "com.example.news",
                foregroundStartTimeMillis = 100L,
                elapsedSinceForegroundMs = 300L,
                defaultRuleWindowMs = 6_000L,
                isWithinDefaultRuleWindow = true,
                ruleScope = "custom_splash_only",
                timeWindowDecision = "within_window"
            )
        )

        assertEquals(20, fields["candidateCenterX"])
        assertEquals(30, fields["candidateCenterY"])
        assertEquals(20, fields["gestureX"])
        assertEquals(30, fields["gestureY"])
        assertEquals(true, fields["actionReturnValue"])
        assertEquals(false, fields["effectConfirmed"])
        assertEquals(1, fields["clickedParentDepth"])
        assertEquals(0.01f, fields["candidateAreaRatio"])
        assertEquals(ClickTargetSourceLog.GestureOnNodeCenter.value, fields["clickTargetSource"])
        assertEquals("custom_splash_only", fields["ruleScope"])
    }

    @Test
    fun diagnosticReportContainsStableSchemaSanitizedLogsAndSummaryCounts() {
        val now = 10_000L
        val json = DiagnosticReportRepository.buildReportJson(
            versionName = "1.4.0",
            exportTimeMillis = now,
            deviceInfo = RomUtils.DeviceInfo(
                manufacturer = "Google",
                brand = "google",
                model = "Pixel",
                androidVersion = "16",
                sdkInt = 36,
                romType = RomUtils.RomType.Unknown
            ),
            runtimeState = SettingsRepository.DiagnosticSnapshot(
                masterEnabled = true,
                safetyModeEnabled = true,
                debugLogEnabled = true,
                releaseDisclosureAccepted = true,
                accessibilityServiceEnabled = true,
                serviceConnectedAt = 1_000L,
                serviceActiveAt = 2_000L,
                serviceInterruptedAt = 0L,
                lastClickAt = 9_000L,
                lastFailureReason = "root_window_null",
                appPolicies = listOf(
                    AppPolicy(
                        packageName = "com.example.news",
                        defaultRuleEnabled = false,
                        customRulesEnabled = true
                    )
                )
            ),
            rules = listOf(
                SkipRule(
                    id = "rule_a",
                    source = RuleSource.UserSimple,
                    name = "右上角跳过",
                    packageName = "com.example.news",
                    appName = "News",
                    matchTexts = listOf("跳过 13812345678"),
                    coordinateFallback = CoordinateFallback(
                        enabled = true,
                        xRatio = 0.9f,
                        yRatio = 0.12f,
                        anchorTexts = listOf("跳过 13812345678")
                    )
                )
            ),
            rulePackages = listOf(
                RulePackage("local", "本地创建规则", 1, "local", "", "", source = RuleSource.UserSimple)
            ),
            clickLogs = listOf(
                ClickLog(
                    timeMillis = now - 1_000L,
                    packageName = "com.example.news",
                    ruleId = "rule_a",
                    ruleName = "右上角跳过",
                    stage = ClickLogStage.NoCandidateFound,
                    failureReason = "no_candidate_found 13812345678",
                    nodeText = "验证码 12345678",
                    contentDescription = "test@example.com"
                ),
                ClickLog(
                    timeMillis = now - 2_000L,
                    packageName = "com.example.news",
                    ruleId = "rule_a",
                    ruleName = "右上角跳过",
                    stage = ClickLogStage.SkippedByLowScore,
                    failureReason = "score_below_min",
                    candidateCount = 2,
                    score = 60,
                    minScore = 75
                ),
                ClickLog(
                    timeMillis = now - 3_000L,
                    packageName = "com.example.video",
                    ruleId = "rule_b",
                    ruleName = "坐标兜底",
                    stage = ClickLogStage.ClickFailed,
                    failureReason = "coordinate_fallback_anchor_missing",
                    clickTargetSource = ClickTargetSourceLog.FixedPositionForbidden,
                    blockedReason = "coordinate_fallback_anchor_missing"
                ),
                ClickLog(
                    timeMillis = now - 4_000L,
                    packageName = "com.android.systemui",
                    stage = ClickLogStage.SkippedBySafety,
                    failureReason = "safety_guard_blocked",
                    blockedBySafety = true,
                    blockedReason = "system_or_launcher_package",
                    isWithinDefaultRuleWindow = false,
                    timeWindowDecision = "ignored_system_package"
                ),
                ClickLog(
                    timeMillis = now - 5_000L,
                    packageName = "com.example.news",
                    ruleName = "默认开屏跳过",
                    stage = ClickLogStage.SkippedByTimeWindow,
                    failureReason = "default_rule_window_expired",
                    isWithinDefaultRuleWindow = false,
                    timeWindowDecision = "expired"
                ),
                ClickLog(
                    timeMillis = now - 8L * 24L * 60L * 60L * 1000L,
                    packageName = "com.example.old",
                    stage = ClickLogStage.RootWindowNull,
                    rootWindowNull = true
                )
            ),
            ruleLogs = listOf(
                com.example.skip.model.RuleLog(
                    timeMillis = now - 500L,
                    source = RuleSource.JsonFile,
                    ruleName = "导入 13812345678",
                    targetApp = "News",
                    success = false,
                    reason = "bad email test@example.com"
                )
            ),
            keywords = listOf("跳过"),
            viewIdKeywords = listOf("skip")
        )

        val report = com.example.skip.util.SimpleJson.parseObject(json)
        val device = report.optJSONObject("device")!!
        val runtime = report.optJSONObject("runtimeState")!!
        val rulesSnapshot = report.optJSONObject("rulesSnapshot")!!
        val clickLogs = report.optJSONArray("clickLogs")!!
        val ruleLogs = report.optJSONArray("ruleLogs")!!
        val summary = report.optJSONObject("diagnosticSummary")!!
        val reasonCounts = summary.optJSONObject("reasonCounts")!!
        val categoryCounts = summary.optJSONObject("categoryCounts")!!
        val rawSignalCounts = summary.optJSONObject("rawSignalCounts")!!
        val recentWindows = summary.optJSONObject("recentWindows")!!
        val defaultRuleRuntime = rulesSnapshot.optJSONObject("defaultRuleRuntime")!!
        val firstLog = clickLogs.optJSONObject(0)!!
        val firstRule = rulesSnapshot.optJSONArray("rules")!!.optJSONObject(0)!!
        val firstRuleLog = ruleLogs.optJSONObject(0)!!

        assertEquals(2, report.optInt("schemaVersion"))
        assertEquals("1.4.0", report.optString("skipVersion"))
        assertEquals("Pixel", device.optString("model"))
        assertTrue(runtime.optBoolean("masterEnabled"))
        assertTrue(runtime.optBoolean("accessibilityServiceEnabled"))
        assertEquals(1, runtime.optInt("appPolicyCount"))
        assertEquals(1, rulesSnapshot.optInt("ruleCount"))
        assertEquals(1, rulesSnapshot.optInt("coordinateFallbackEnabledRuleCount"))
        assertEquals(RuleRepository.DEFAULT_RULE_WINDOW_MS, defaultRuleRuntime.optLong("defaultRuleWindowMs"))
        assertEquals(RuleRepository.DEFAULT_RULE_MIN_SCORE, defaultRuleRuntime.optInt("defaultRuleMinScore"))
        assertEquals(1, defaultRuleRuntime.optInt("keywordCount"))
        assertEquals(1, defaultRuleRuntime.optInt("viewIdKeywordCount"))
        assertEquals(6, clickLogs.length())
        assertEquals(1, ruleLogs.length())
        assertEquals("no_candidate_found [PHONE]", firstLog.optString("failureReason"))
        assertEquals("验证码 [NUMBER]", firstLog.optString("nodeText"))
        assertEquals("[EMAIL]", firstLog.optString("contentDescription"))
        assertEquals("跳过 [PHONE]", firstRule.optJSONArray("matchTexts")!!.optString(0))
        assertEquals("导入 [PHONE]", firstRuleLog.optString("ruleName"))
        assertEquals("bad email [EMAIL]", firstRuleLog.optString("reason"))
        assertEquals(6, summary.optInt("totalClickLogs"))
        assertEquals(5, recentWindows.optInt("last24h"))
        assertEquals(5, recentWindows.optInt("last7d"))
        assertEquals(1, reasonCounts.optInt("no_candidate_found [PHONE]"))
        assertEquals(1, categoryCounts.optInt("noCandidate"))
        assertEquals(1, categoryCounts.optInt("lowScore"))
        assertEquals(1, categoryCounts.optInt("coordinateFallbackLimited"))
        assertEquals(1, categoryCounts.optInt("rootWindowNull"))
        assertEquals(1, categoryCounts.optInt("timeWindow"))
        assertEquals(2, rawSignalCounts.optInt("outsideDefaultWindow"))
        assertEquals(1, categoryCounts.optInt("safetyBlocked"))
    }

    @Test
    fun jsonExportWriterFailsWhenOutputStreamCannotBeOpened() {
        val result = runCatching {
            JsonExportWriter.writeJson(
                openOutputStream = { null },
                json = "{}"
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("导出文件"))
    }

    @Test
    fun jsonExportWriterWritesUtf8JsonWhenStreamIsAvailable() {
        val output = ByteArrayOutputStream()

        JsonExportWriter.writeJson(
            openOutputStream = { output },
            json = """{"message":"诊断"}"""
        )

        assertEquals("""{"message":"诊断"}""", output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun clickLogScreenCapsRenderedRowsForLargeLocalHistory() {
        val logs = (0..CLICK_LOG_DISPLAY_LIMIT + 5).map { index ->
            ClickLog(
                timeMillis = index.toLong(),
                packageName = "com.example.$index",
                stage = ClickLogStage.NoCandidateFound
            )
        }

        val displayed = displayLogsForScreen(logs)

        assertEquals(CLICK_LOG_DISPLAY_LIMIT, displayed.size)
        assertEquals(0L, displayed.first().timeMillis)
        assertEquals((CLICK_LOG_DISPLAY_LIMIT - 1).toLong(), displayed.last().timeMillis)
    }

    @Test
    fun diagnosticReportKeepsRequiredSectionsWhenLogsAreEmpty() {
        val json = DiagnosticReportRepository.buildReportJson(
            versionName = "1.4.0",
            exportTimeMillis = 1_000L,
            deviceInfo = RomUtils.DeviceInfo(
                manufacturer = "",
                brand = "",
                model = "",
                androidVersion = "",
                sdkInt = 0,
                romType = RomUtils.RomType.Unknown
            ),
            runtimeState = SettingsRepository.DiagnosticSnapshot(
                masterEnabled = false,
                safetyModeEnabled = false,
                debugLogEnabled = false,
                releaseDisclosureAccepted = false,
                accessibilityServiceEnabled = false,
                serviceConnectedAt = 0L,
                serviceActiveAt = 0L,
                serviceInterruptedAt = 0L,
                lastClickAt = 0L,
                lastFailureReason = "",
                appPolicies = emptyList()
            ),
            rules = emptyList(),
            rulePackages = emptyList(),
            clickLogs = emptyList(),
            ruleLogs = emptyList(),
            keywords = emptyList(),
            viewIdKeywords = emptyList()
        )
        val report = com.example.skip.util.SimpleJson.parseObject(json)
        val summary = report.optJSONObject("diagnosticSummary")!!

        assertTrue(report.has("device"))
        assertTrue(report.has("runtimeState"))
        assertTrue(report.has("rulesSnapshot"))
        assertTrue(report.has("clickLogs"))
        assertTrue(report.has("ruleLogs"))
        assertTrue(report.has("diagnosticSummary"))
        assertEquals(0, report.optJSONArray("clickLogs")!!.length())
        assertEquals(0, summary.optInt("totalClickLogs"))
        assertEquals(0, summary.optJSONObject("recentWindows")!!.optInt("last24h"))
    }

    @Test
    fun privacySanitizerRedactsSensitiveText() {
        val text = "电话 13812345678 邮箱 test@example.com 卡号 6222021234567890123"
        val sanitized = PrivacySanitizer.sanitizeText(text)
        assertTrue(sanitized.contains("[PHONE]"))
        assertTrue(sanitized.contains("[EMAIL]"))
        assertFalse(sanitized.contains("13812345678"))
    }

    @Test
    fun inputNodeTextIsRedacted() {
        assertEquals("[REDACTED]", PrivacySanitizer.sanitizeNodeText("secret", isInput = true))
    }

    @Test
    fun homeScreenIconUsesDrawableAssetInsteadOfAdaptiveIcon() {
        assertEquals(R.drawable.ic_skip_wordmark, IconManager.homeImageRes)
    }

    @Test
    fun paginatedListStartsWithBoundedFirstBatch() {
        assertEquals(0, initialVisibleCount(totalCount = 0, batchSize = 30))
        assertEquals(12, initialVisibleCount(totalCount = 12, batchSize = 30))
        assertEquals(30, initialVisibleCount(totalCount = 75, batchSize = 30))
    }

    @Test
    fun paginatedListAppendsWithoutExceedingTotal() {
        assertEquals(60, nextVisibleCount(currentCount = 30, totalCount = 75, batchSize = 30))
        assertEquals(75, nextVisibleCount(currentCount = 60, totalCount = 75, batchSize = 30))
        assertEquals(75, nextVisibleCount(currentCount = 90, totalCount = 75, batchSize = 30))
    }

    @Test
    fun paginatedListResetsToFilteredResultSize() {
        val expandedCount = nextVisibleCount(
            currentCount = initialVisibleCount(totalCount = 120, batchSize = 30),
            totalCount = 120,
            batchSize = 30
        )

        assertEquals(60, expandedCount)
        assertEquals(8, initialVisibleCount(totalCount = 8, batchSize = 30))
    }

    private fun appStatus(label: String, packageName: String): InstalledAppStatus {
        return InstalledAppStatus(
            app = InstalledApp(label = label, packageName = packageName, icon = null),
            isBlacklisted = true,
            appAssistanceEnabled = false,
            hasCustomRules = false,
            customRuleCount = 0,
            defaultSkipEnabled = false,
            isProtected = false
        )
    }
}
