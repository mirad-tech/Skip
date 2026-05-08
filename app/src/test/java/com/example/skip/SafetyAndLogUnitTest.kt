package com.example.skip

import com.example.skip.data.RuleRepository
import com.example.skip.data.LogRepository
import com.example.skip.engine.SafetyGuard
import com.example.skip.engine.ScoreEvaluator
import com.example.skip.model.ClickLog
import com.example.skip.model.ClickLogStage
import com.example.skip.model.ClickMethodLog
import com.example.skip.model.ClickTargetSourceLog
import com.example.skip.model.RuleArea
import com.example.skip.service.DelayedClickSafetyCheck
import com.example.skip.util.PrivacySanitizer
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
            ClickLogStage.ClickSkippedBySafetyMode,
            ClickLogStage.fromValue("click_skipped_by_safety_mode")
        )
        assertEquals(
            ClickLogStage.ClickMisfireSelfOpened,
            ClickLogStage.fromValue("click_misfire_self_opened")
        )
        assertEquals(
            ClickLogStage.ClickCancelledPackageChanged,
            ClickLogStage.fromValue("click_cancelled_package_changed")
        )
    }

    @Test
    fun delayedClickPackageCheckAllowsSamePackage() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "com.example.news",
            selfPackageName = "com.example.skip"
        )

        assertTrue(result.allowed)
    }

    @Test
    fun delayedClickPackageCheckBlocksSystemUiBeforeClick() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "com.android.systemui",
            selfPackageName = "com.example.skip"
        )

        assertFalse(result.allowed)
        assertEquals(ClickLogStage.SkippedBySafety, result.stage)
        assertEquals("safety_guard_before_delayed_click", result.blockedReason)
    }

    @Test
    fun delayedClickPackageCheckBlocksLauncherBeforeClick() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "com.bbk.launcher2",
            selfPackageName = "com.example.skip"
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
            selfPackageName = "com.example.skip"
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
            selfPackageName = "com.example.skip"
        )

        assertFalse(result.allowed)
        assertEquals(ClickLogStage.ClickCancelledPackageChanged, result.stage)
        assertEquals("package_changed_before_click", result.reason)
        assertTrue(result.detail.contains("pendingPackageName=com.example.news"))
        assertTrue(result.detail.contains("currentPackageName=com.example.other"))
    }

    @Test
    fun delayedClickPackageCheckBlocksUnknownPackageBeforeClick() {
        val result = DelayedClickSafetyCheck.evaluate(
            pendingPackageName = "com.example.news",
            currentPackageName = "",
            selfPackageName = "com.example.skip"
        )

        assertFalse(result.allowed)
        assertEquals(ClickLogStage.ClickCancelledPackageUnknown, result.stage)
        assertEquals("current_package_unknown_before_click", result.reason)
    }

    @Test
    fun clickLogKeepsSafetyModeAndTargetFields() {
        val log = ClickLog(
            timeMillis = 1L,
            packageName = "com.example.news",
            safetyModeEnabled = true,
            clickSkippedBySafetyMode = true,
            candidateBounds = "1,2,3,4",
            clickTargetSource = ClickTargetSourceLog.FixedPositionForbidden
        )

        assertTrue(log.safetyModeEnabled)
        assertTrue(log.clickSkippedBySafetyMode)
        assertEquals("1,2,3,4", log.candidateBounds)
        assertEquals(ClickTargetSourceLog.FixedPositionForbidden, log.clickTargetSource)
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
            "candidateAreaRatio"
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
                clickTargetSource = ClickTargetSourceLog.GestureOnNodeCenter
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
}
