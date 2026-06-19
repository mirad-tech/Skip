package com.example.skip

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.skip.data.RuleRepository
import com.example.skip.engine.NodeScanner
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule
import com.example.skip.service.ActiveTextInputGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeminiRegressionInstrumentedTest {
    @After
    fun resetScannerFixture() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.SplashSkipButton
    }

    @Test
    fun savedDefaultRuleWindowIsUsedByBuiltInRuntimeRule() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalConfig = RuleRepository.getDefaultRuleConfig(context)
        try {
            RuleRepository.saveDefaultRuleConfig(
                context,
                originalConfig.copy(validDurationMs = 10_000L)
            )

            val builtInRule = RuleRepository.getBuiltInRuleForPackage(context, "com.example.news")

            assertEquals(10_000L, builtInRule.validDurationMs)
        } finally {
            RuleRepository.saveDefaultRuleConfig(context, originalConfig)
        }
    }

    @Test
    fun invisibleParentDoesNotPreventScanningVisibleChild() {
        val rule = SkipRule(
            id = "test_rule",
            source = RuleSource.UserSimple,
            name = "测试跳过",
            packageName = "com.example.news",
            appName = "News",
            matchTexts = listOf("跳过广告"),
            area = RuleArea.Any,
            validDurationMs = 8_000L,
            minScore = 60
        )

        ActivityScenario.launch(ScannerFixtureActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val rootNode = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .rootInActiveWindow

            val match = NodeScanner.findBestMatch(rootNode, listOf(rule), appElapsedMs = 1_000L)

            assertNotNull(match)
            assertEquals("跳过广告", match!!.matchedKeyword)
        }
    }

    @Test
    fun builtInRuleMatchesSyntheticSplashSkipButton() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.SplashSkipButton
        val rule = SkipRule(
            id = "built_in_com.example.news",
            source = RuleSource.BuiltIn,
            name = "默认开屏跳过",
            packageName = "com.example.news",
            appName = "News",
            matchTexts = RuleRepository.defaultKeywords,
            matchContentDescriptions = RuleRepository.defaultKeywords,
            matchViewIds = RuleRepository.defaultViewIdKeywords,
            area = RuleArea.TopRight,
            validDurationMs = 8_000L,
            minScore = 70
        )

        ActivityScenario.launch(ScannerFixtureActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val rootNode = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .rootInActiveWindow

            val match = NodeScanner.findBestMatch(rootNode, listOf(rule), appElapsedMs = 1_000L)

            assertNotNull(match)
            assertEquals("跳过广告", match!!.matchedKeyword)
        }
    }

    @Test
    fun mobileTicketHomeAnnouncementCloseIsNotDefaultSplashCandidate() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.MobileTicketHome
        val rule = SkipRule(
            id = "built_in_com.MobileTicket",
            source = RuleSource.BuiltIn,
            name = "默认开屏跳过",
            packageName = "com.MobileTicket",
            appName = "铁路12306",
            matchTexts = RuleRepository.defaultKeywords,
            matchContentDescriptions = RuleRepository.defaultKeywords,
            matchViewIds = RuleRepository.defaultViewIdKeywords,
            area = RuleArea.TopRight,
            validDurationMs = 8_000L,
            minScore = 70
        )

        ActivityScenario.launch(ScannerFixtureActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val rootNode = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .rootInActiveWindow

            val match = NodeScanner.findBestMatch(rootNode, listOf(rule), appElapsedMs = 1_000L)

            assertNull(match)
        }
    }

    @Test
    fun chromeAttachmentAddIsNotDefaultSplashCandidate() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.ChromeLocationBarAttachmentAdd
        val rule = builtInDefaultRule(
            packageName = "com.android.chrome",
            appName = "Chrome"
        )

        ActivityScenario.launch(ScannerFixtureActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val rootNode = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .rootInActiveWindow

            val match = NodeScanner.findBestMatch(rootNode, listOf(rule), appElapsedMs = 1_000L)

            assertNull(match)
        }
    }

    @Test
    fun bilibiliDanmakuCloseIsNotDefaultSplashCandidate() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.BilibiliDanmakuClose
        val rule = builtInDefaultRule(
            packageName = "tv.danmaku.bili",
            appName = "哔哩哔哩"
        )

        ActivityScenario.launch(ScannerFixtureActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val rootNode = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .rootInActiveWindow

            val match = NodeScanner.findBestMatch(rootNode, listOf(rule), appElapsedMs = 1_000L)

            assertNull(match)
        }
    }

    @Test
    fun bilibiliCountdownSkipStillMatchesDefaultSplashCandidate() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.BilibiliCountdownSkip
        val rule = builtInDefaultRule(
            packageName = "tv.danmaku.bili",
            appName = "哔哩哔哩"
        )

        ActivityScenario.launch(ScannerFixtureActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val rootNode = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .rootInActiveWindow

            val match = NodeScanner.findBestMatch(rootNode, listOf(rule), appElapsedMs = 1_000L)

            assertNotNull(match)
            assertEquals("跳过", match!!.matchedKeyword)
        }
    }

    @Test
    fun activeTextInputGuardDetectsFocusedSearchField() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.FocusedTopSearchField

        ActivityScenario.launch(ScannerFixtureActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val rootNode = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .rootInActiveWindow

            assertTrue(ActiveTextInputGuard.hasFocusedEditableInput(rootNode))
        }
    }

    private fun builtInDefaultRule(packageName: String, appName: String): SkipRule {
        return SkipRule(
            id = "built_in_$packageName",
            source = RuleSource.BuiltIn,
            name = "默认开屏跳过",
            packageName = packageName,
            appName = appName,
            matchTexts = RuleRepository.defaultKeywords,
            matchContentDescriptions = RuleRepository.defaultKeywords,
            matchViewIds = RuleRepository.defaultViewIdKeywords,
            area = RuleArea.TopRight,
            validDurationMs = 8_000L,
            minScore = 70
        )
    }
}
