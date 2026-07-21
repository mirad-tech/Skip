package com.example.skip

import com.example.skip.ui.home.homeAutomationActionLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAutomationActionLabelTest {
    @Test
    fun actionLabelUsesExistingSingleButtonForEveryRuntimeMode() {
        assertEquals("开启自动化", homeAutomationActionLabel(skipEnabled = false, safetyModeEnabled = false))
        assertEquals("暂停自动化", homeAutomationActionLabel(skipEnabled = true, safetyModeEnabled = false))
        assertEquals("开启观察", homeAutomationActionLabel(skipEnabled = false, safetyModeEnabled = true))
        assertEquals("暂停观察", homeAutomationActionLabel(skipEnabled = true, safetyModeEnabled = true))
    }
}
