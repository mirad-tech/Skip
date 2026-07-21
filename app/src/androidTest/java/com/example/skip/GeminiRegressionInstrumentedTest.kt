package com.example.skip

import android.content.Intent
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.graphics.Rect
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.skip.data.LogRepository
import com.example.skip.data.RuleRepository
import com.example.skip.engine.ClickExecutor
import com.example.skip.engine.NodeScanner
import com.example.skip.engine.ScoreEvaluator
import com.example.skip.engine.CurrentTargetRevalidator
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickTargetSourceLog
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
    fun clickLogPersistencePayloadSerializesBufferedLogsAndThrottleCounts() {
        val payload = LogRepository.serializeClickLogPersistence(
            logs = listOf(
                ClickLog(
                    timeMillis = 1L,
                    packageName = "com.example.news",
                    stage = ClickLogStage.ClickActionSuccess
                )
            ),
            throttleCounts = mapOf("com.example.news:noise" to 2)
        )

        assertTrue(payload.logsJson.contains("com.example.news"))
        assertTrue(payload.throttleCountsJson.contains("com.example.news:noise"))
    }

    @Test
    fun clickLogPersistencePayloadRoundTripsForFirstLoadParsing() {
        val logs = listOf(
            ClickLog(
                timeMillis = 1L,
                packageName = "com.example.news",
                appName = "News",
                stage = ClickLogStage.ClickActionSuccess,
                success = true,
                matchedKeyword = "skip ad",
                nodeText = "跳过广告",
                textKeywordIsStandaloneSkip = true,
                standaloneSkipAllowed = true
            )
        )
        val payload = LogRepository.serializeClickLogPersistence(logs, emptyMap())

        val restored = LogRepository.deserializeClickLogPersistence(payload.logsJson)

        assertEquals(logs, restored)
        assertTrue(restored.single().textKeywordIsStandaloneSkip)
        assertTrue(restored.single().standaloneSkipAllowed)
    }

    @Test
    fun clickLogPersistenceDefaultsMissingStandaloneSkipAuthorizationToFalse() {
        val restored = LogRepository.deserializeClickLogPersistence(
            """[{"timeMillis":1,"packageName":"com.example.news","stage":"click_effect_confirmed"}]"""
        )

        assertEquals(1, restored.size)
        assertEquals(false, restored.single().standaloneSkipAllowed)
    }

    @Test
    fun clickLogPersistenceBenchmarkReportsPayloadSizeAndP95Latency() {
        val logs = (0 until BENCHMARK_LOG_COUNT).map(::benchmarkClickLog)
        val serializationSamples = mutableListOf<Long>()
        val parseSamples = mutableListOf<Long>()
        var payloadBytes = 0

        repeat(BENCHMARK_SAMPLES) {
            val serializeStart = SystemClock.elapsedRealtimeNanos()
            val payload = LogRepository.serializeClickLogPersistence(logs, emptyMap())
            serializationSamples += SystemClock.elapsedRealtimeNanos() - serializeStart
            payloadBytes = payload.logsJson.toByteArray(Charsets.UTF_8).size

            val parseStart = SystemClock.elapsedRealtimeNanos()
            val restored = LogRepository.deserializeClickLogPersistence(payload.logsJson)
            parseSamples += SystemClock.elapsedRealtimeNanos() - parseStart

            assertEquals(logs, restored)
        }

        val serializationP95Ms = p95Millis(serializationSamples)
        val parseP95Ms = p95Millis(parseSamples)
        Log.i(
            LOG_TAG,
            "clickLogs=$BENCHMARK_LOG_COUNT payloadBytes=$payloadBytes " +
                "serializeP95Ms=$serializationP95Ms parseP95Ms=$parseP95Ms"
        )

        assertTrue(payloadBytes > 0)
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

            assertEquals(8_000L, builtInRule.validDurationMs)
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

        launchScannerFixture().use {
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

        launchScannerFixture().use {
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
    fun standaloneSkipInSmallClickableParentMatchesBuiltInRule() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.StandaloneSkipInsideClickableParent
        launchScannerFixture().use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val match = NodeScanner.findBestMatch(root, listOf(builtInDefaultRule("com.example.news", "News")), 1_000L)

            assertNotNull(match)
            assertEquals("跳过", match!!.matchedKeyword)
            assertEquals(ClickTargetSourceLog.ClickableParent, match.clickTargetSource)
            assertEquals(1, match.clickedParentDepth)
            assertTrue(match.standaloneSkipAllowed)
        }
    }

    @Test
    fun standaloneSkipAfterEightSecondsDoesNotMatchBuiltInRule() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.StandaloneSkipInsideClickableParent
        launchScannerFixture().use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow

            assertNull(NodeScanner.findBestMatch(root, listOf(builtInDefaultRule("com.example.news", "News")), 8_001L))
        }
    }

    @Test
    fun standaloneSkipInsideEditableActionPathIsRejected() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.StandaloneSkipInsideEditableActionPath
        launchScannerFixture().use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val skipNode = root.findAccessibilityNodeInfosByText("跳过").first { node ->
                node.text?.toString() == "跳过"
            }

            val resolution = ClickExecutor.resolveCandidate(skipNode)
            val action = resolution.relaxedSelection

            assertNotNull(action)
            assertEquals(2, action!!.parentDepth)
            assertTrue(resolution.actionPathFor(action).hasUnsafeNode)
            assertNull(
                NodeScanner.findBestMatch(
                    root,
                    listOf(builtInDefaultRule("com.example.news", "News")),
                    1_000L
                )
            )
        }
    }

    @Test
    fun coordinateSnapshotKeepsDecorativeChildBoundsAndUsesClickableParentIdentity() {
        ScannerFixtureActivity.scenario =
            ScannerFixtureActivity.Scenario.CoordinateIdentityChildInsideClickableParent
        launchScannerFixture().use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow

            val snapshot = CurrentTargetRevalidator.snapshotAtPoint(root, 950, 70)

            assertNotNull(snapshot)
            assertEquals("com.example.news:id/splash_skip", snapshot!!.target.viewId)
            assertEquals(Rect(920, 40, 980, 100), snapshot.target.bounds)
            assertTrue(snapshot.hasClickableNodeOrAncestor)
        }
    }

    @Test
    fun coordinateSnapshotTraversesVisibleNonClickableParentToReachSafeDescendant() {
        ScannerFixtureActivity.scenario =
            ScannerFixtureActivity.Scenario.CoordinateIdentityInsideVisibleNonClickableParent
        launchScannerFixture().use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow

            val snapshot = CurrentTargetRevalidator.snapshotAtPoint(root, 950, 70)

            assertNotNull(snapshot)
            assertEquals("com.example.news:id/nested_splash_skip", snapshot!!.target.viewId)
            assertEquals(Rect(920, 40, 980, 100), snapshot.target.bounds)
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

        launchScannerFixture().use {
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

        launchScannerFixture().use {
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

        launchScannerFixture().use {
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

        launchScannerFixture().use {
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
    fun genericCloseCandidateIsSafetyTerminalInsteadOfRetryableLowScore() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.GenericCloseOnly

        launchScannerFixture().use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val rootNode = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val scan = NodeScanner.scan(
                root = rootNode,
                rules = listOf(builtInDefaultRule("com.example.shop", "Shop")),
                appElapsedMs = 1_000L
            )

            assertNull(scan.bestMatch)
            assertEquals(ScoreEvaluator.GENERIC_CLOSE_BLOCKED_REASON, scan.failureReason)
        }
    }

    @Test
    fun bilibiliSearchClearButtonIsNotDefaultSplashCandidate() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.BilibiliSearchClearButton
        val rule = builtInDefaultRule(
            packageName = "tv.danmaku.bili",
            appName = "哔哩哔哩"
        )

        launchScannerFixture().use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val rootNode = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .rootInActiveWindow

            val match = NodeScanner.findBestMatch(rootNode, listOf(rule), appElapsedMs = 1_000L)

            assertNull(match)
        }
    }

    @Test
    fun activeTextInputGuardDetectsFocusedSearchField() {
        ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.FocusedTopSearchField

        launchScannerFixture().use {
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

    private fun benchmarkClickLog(index: Int): ClickLog {
        return ClickLog(
            timeMillis = 1_700_000_000_000L + index,
            packageName = "com.example.app$index",
            appName = "Example $index",
            activityName = "com.example.app$index.SplashActivity",
            ruleType = "json",
            ruleName = "关闭开屏广告$index",
            ruleId = "rule_$index",
            stage = ClickLogStage.ClickEffectConfirmed,
            success = true,
            reason = "click_completed",
            failureReason = "",
            detail = "candidate=$index",
            eventType = 32,
            eventPackageName = "com.example.app$index",
            rootWindowNull = false,
            windowId = index,
            rootChildCount = 8,
            canRetrieveWindowContent = true,
            candidateCount = 3,
            bestCandidateScore = 92,
            bestCandidateBounds = "960,80,1060,160",
            minScore = 70,
            matchedKeyword = "跳过",
            nodeText = "跳过广告$index",
            contentDescription = "关闭广告",
            viewIdResourceName = "com.example.app$index:id/splash_skip",
            boundsInScreen = "960,80,1060,160",
            nodeClickable = true,
            parentClickable = false,
            score = 92,
            area = "top_right",
            clickMethod = ClickMethodLog.ActionClick,
            actionReturnValue = true,
            clickResult = true,
            effectConfirmed = true,
            delayBeforeClickMs = 120L,
            retryCount = 0,
            deviceRom = "Android",
            elapsedSinceAppStartMs = 1_200L,
            foregroundPackage = "com.example.app$index",
            foregroundStartTimeMillis = 1_700_000_000_000L,
            elapsedSinceForegroundMs = 1_200L,
            defaultRuleWindowMs = 8_000L,
            isWithinDefaultRuleWindow = true,
            ruleScope = "default",
            timeWindowDecision = "within_window",
            isSystemPackage = false,
            isLauncherPackage = false,
            isSelfPackage = false,
            isSelfAppLabelCandidate = false,
            blockedBySafety = false,
            blockedReason = "",
            defaultRuleAreaAllowed = true,
            textKeywordIsStandaloneSkip = false,
            effectConfirmReason = "window_changed",
            safetyModeEnabled = true,
            clickSkippedBySafetyMode = false,
            candidateBounds = "960,80,1060,160",
            candidateCenterX = 1_010,
            candidateCenterY = 120,
            clickedNodeBounds = "960,80,1060,160",
            clickedNodeClassName = "android.widget.TextView",
            clickedNodeText = "跳过广告$index",
            clickedNodeViewId = "com.example.app$index:id/splash_skip",
            clickedParentDepth = 1,
            candidateAreaRatio = 0.01f,
            gestureX = 1_010,
            gestureY = 120,
            isLargeCandidateBounds = false,
            isFixedCoordinateClick = false,
            clickTargetSource = ClickTargetSourceLog.NodeSelf
        )
    }

    private fun p95Millis(samples: List<Long>): Double {
        val sorted = samples.sorted()
        val index = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index] / 1_000_000.0
    }

    private fun launchScannerFixture(): ActivityScenario<ScannerFixtureActivity> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val intent = Intent(targetContext, ScannerFixtureActivity::class.java).apply {
            action = SCANNER_FIXTURE_ACTION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val component = "${targetContext.packageName}/${ScannerFixtureActivity::class.java.name}"
        val command = "am start -W -a $SCANNER_FIXTURE_PRIME_ACTION -f 0x10008000 -n $component"
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).use { output -> output.readBytes() }
        instrumentation.waitForIdleSync()
        return ActivityScenario.launch(intent)
    }

    private companion object {
        const val LOG_TAG = "SkipLogBenchmark"
        const val SCANNER_FIXTURE_ACTION = "com.example.skip.action.SCANNER_FIXTURE_TEST"
        const val SCANNER_FIXTURE_PRIME_ACTION = "com.example.skip.action.SCANNER_FIXTURE_PRIME"
        const val BENCHMARK_LOG_COUNT = 1_000
        const val BENCHMARK_SAMPLES = 10
    }
}
