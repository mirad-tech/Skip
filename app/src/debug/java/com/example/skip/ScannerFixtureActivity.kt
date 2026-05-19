package com.example.skip

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView

class ScannerFixtureActivity : Activity() {
    enum class Scenario {
        SplashSkipButton,
        MobileTicketHome
    }

    companion object {
        var scenario: Scenario = Scenario.SplashSkipButton
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            HiddenAccessibilityContainer(this).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                when (scenario) {
                    Scenario.SplashSkipButton -> addView(
                        VisibleSkipButton(context),
                        FrameLayout.LayoutParams(120, 80)
                    )
                    Scenario.MobileTicketHome -> addView(
                        MobileTicketAnnouncementCloseButton(context),
                        FrameLayout.LayoutParams(152, 140)
                    )
                }
            }
        )
    }
}

private class HiddenAccessibilityContainer(
    context: android.content.Context
) : FrameLayout(context) {
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.setVisibleToUser(false)
        info?.setEnabled(true)
        info?.setBoundsInScreen(Rect(0, 0, 1080, 1920))
    }
}

private class VisibleSkipButton(
    context: android.content.Context
) : Button(context) {
    init {
        text = "跳过广告"
        isEnabled = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.text = "跳过广告"
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(true)
        info?.setBoundsInScreen(Rect(900, 40, 1020, 120))
    }
}

private class MobileTicketAnnouncementCloseButton(
    context: android.content.Context
) : ImageView(context) {
    init {
        contentDescription = "关闭公告:"
        isEnabled = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.contentDescription = "关闭公告:"
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(true)
        info?.setBoundsInScreen(Rect(1288, 2783, 1440, 2923))
    }
}
