package com.example.skip.data

import com.example.skip.engine.BuiltInPreciseRuleCatalog
import com.example.skip.engine.PreciseRulePolicy
import com.example.skip.engine.RulePlanProvider
import com.example.skip.model.AppPolicy
import com.example.skip.model.DuplicateStrategy
import com.example.skip.model.MatchMode
import com.example.skip.model.RuleKind
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningAdRuleWindowTest {
    private fun precise(duration: Long) = SkipRule(
        id = "precise", source = RuleSource.JsonFile, kind = RuleKind.Precise,
        name = "开屏跳过", packageName = "com.example.reader", appName = "Reader",
        activityName = "com.example.reader.SplashActivity", matchViewIds = listOf("com.example.reader:id/ad_skip"),
        textMatchMode = MatchMode.Exact, contentDescriptionMatchMode = MatchMode.Exact,
        viewIdMatchMode = MatchMode.Exact, validDurationMs = duration
    )

    @Test
    fun oldStoredStandardAndPreciseRulesAreCappedWithoutChangingTheirIdentity() {
        val oldPrecise = precise(15_000L)
        val normalized = RuleRepository.normalizeRuleWindowForStorage(oldPrecise)
        assertEquals(6_000L, normalized.validDurationMs)
        assertEquals(oldPrecise.copy(validDurationMs = 6_000L), normalized)
        val oldStandard = oldPrecise.copy(kind = RuleKind.Standard, validDurationMs = 8_000L)
        assertEquals(6_000L, RuleRepository.normalizeRuleWindowForStorage(oldStandard).validDurationMs)
        assertEquals(6_000L, RuleRepository.sanitizeDefaultRuleConfig(RuleRepository.DefaultRuleConfig(validDurationMs = 8_000L)).validDurationMs)
    }

    @Test
    fun preciseRulesRetainShorterWindows() {
        for (duration in listOf(3_000L, 5_000L, 6_000L)) {
            assertEquals(duration, RuleRepository.normalizeRuleWindowForStorage(precise(duration)).validDurationMs)
            assertEquals(duration, PreciseRulePolicy.canonicalWindowMs(duration))
        }
    }

    @Test
    fun rulePlanningCapsEvenAnUnnormalizedPreciseRule() {
        val rule = precise(15_000L)
        val plan = RulePlanProvider.plan(
            rule.packageName, "com.example.skip", AppPolicy(rule.packageName), listOf(rule), null, rule.activityName
        )
        assertEquals(6_000L, plan.effectiveWindowMs)
        assertEquals("precise_takeover", plan.scope)
        assertTrue(BuiltInPreciseRuleCatalog.forPackage("com.duowan.kiwi").all { it.validDurationMs <= 6_000L })
    }

    private fun importRule(kind: String, duration: Long) = RuleImportManager.parseRulePackage("""
        {"schemaVersion":3,"name":"window-test","apps":[{"packageName":"com.example.reader","rules":[{
          "id":"window-test","kind":"$kind","name":"开屏跳过","activityName":"com.example.reader.SplashActivity",
          "matchViewIds":["com.example.reader:id/ad_skip"],"textMatchMode":"exact",
          "contentDescriptionMatchMode":"exact","viewIdMatchMode":"exact","validDurationMs":$duration,
          "cooldownMs":1500,"minScore":70,"area":"top_right"
        }]}]}
    """.trimIndent())

    @Test
    fun legacyImportPreviewAndSaveNormalizationAgree() {
        for ((kind, duration) in listOf("standard" to 8_000L, "precise" to 15_000L)) {
            val result = importRule(kind, duration)
            assertTrue(result.errorMessage, result.success)
            val rule = result.rules.single()
            assertEquals(6_000L, rule.validDurationMs)
            val preview = RuleImportManager.previewImport(result, DuplicateStrategy.Merge, emptyList())
            assertTrue(preview.any { "validDurationMs=6000" in it })
            assertEquals(rule, RuleRepository.normalizeRuleWindowForStorage(rule))
            assertTrue(result.warningMessages.any { "6000" in it })
        }
    }

    @Test
    fun importingShorterPreciseRulesDoesNotExtendThem() {
        for (duration in listOf(3_000L, 5_000L)) {
            val result = importRule("precise", duration)
            assertTrue(result.errorMessage, result.success)
            assertEquals(duration, result.rules.single().validDurationMs)
        }
    }
}
