package com.example.skip

import com.example.skip.ui.apps.recentExecutionModeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDetailExecutionModeLabelTest {
    @Test
    fun preciseScopeUsesPreciseHistoryLabel() {
        assertEquals(
            "最近执行模式：精确规则接管",
            recentExecutionModeLabel("precise_takeover")
        )
    }

    @Test
    fun everyStandardScopeUsesGenericRuleHistoryLabel() {
        listOf("default_splash_only", "custom_splash_only", "custom_and_default").forEach { scope ->
            assertEquals("最近执行模式：通用规则", recentExecutionModeLabel(scope))
        }
    }

    @Test
    fun missingScopeDoesNotClaimFallback() {
        assertEquals("最近执行模式：暂无记录", recentExecutionModeLabel(null))
    }
}
