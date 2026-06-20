package com.example.skip

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView

class ScannerFixtureActivity : Activity() {
    enum class Scenario {
        SplashSkipButton,
        MobileTicketHome,
        ChromeLocationBarAttachmentAdd,
        BilibiliDanmakuClose,
        BilibiliCountdownSkip,
        BilibiliSearchClearButton,
        FocusedTopSearchField
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
                    Scenario.ChromeLocationBarAttachmentAdd -> addView(
                        ChromeLocationBarAttachmentAddButton(context),
                        FrameLayout.LayoutParams(224, 224)
                    )
                    Scenario.BilibiliDanmakuClose -> addView(
                        BilibiliDanmakuCloseButton(context),
                        FrameLayout.LayoutParams(178, 120)
                    )
                    Scenario.BilibiliCountdownSkip -> addView(
                        BilibiliCountdownSkipButton(context),
                        FrameLayout.LayoutParams(356, 178)
                    )
                    Scenario.BilibiliSearchClearButton -> addView(
                        BilibiliSearchContainer(context),
                        FrameLayout.LayoutParams(1080, 180)
                    )
                    Scenario.FocusedTopSearchField -> addView(
                        FocusedTopSearchContainer(context),
                        FrameLayout.LayoutParams(1080, 180)
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

private class ChromeLocationBarAttachmentAddButton(
    context: android.content.Context
) : ImageView(context) {
    init {
        contentDescription = "打开或关闭上下文弹出式窗口"
        isEnabled = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.contentDescription = "打开或关闭上下文弹出式窗口"
        info?.viewIdResourceName = "com.android.chrome:id/location_bar_attachments_add"
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(true)
        info?.setBoundsInScreen(Rect(32, 152, 256, 376))
    }
}

private class BilibiliDanmakuCloseButton(
    context: android.content.Context
) : ImageView(context) {
    init {
        contentDescription = "关闭弹幕"
        isEnabled = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.contentDescription = "关闭弹幕"
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(true)
        info?.setBoundsInScreen(Rect(1213, 672, 1391, 792))
    }
}

private class BilibiliCountdownSkipButton(
    context: android.content.Context
) : Button(context) {
    init {
        text = "跳过 5"
        isEnabled = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.text = "跳过 5"
        info?.viewIdResourceName = "tv.danmaku.bili:id/count_down"
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(true)
        info?.setBoundsInScreen(Rect(1020, 2911, 1376, 3089))
    }
}

private class BilibiliSearchContainer(
    context: android.content.Context
) : FrameLayout(context) {
    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(
            BilibiliSearchEditText(context),
            FrameLayout.LayoutParams(820, 120)
        )
        addView(
            BilibiliSearchClearButton(context),
            FrameLayout.LayoutParams(144, 120)
        )
    }
}

private class BilibiliSearchEditText(
    context: android.content.Context
) : EditText(context) {
    init {
        setSingleLine(true)
        setText("麻薯爱燕三")
        isEnabled = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.text = "麻薯爱燕三"
        info?.contentDescription = "搜索查询"
        info?.viewIdResourceName = "tv.danmaku.bili:id/search_src_text"
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setFocused(false)
        info?.setEditable(true)
        info?.setBoundsInScreen(Rect(257, 192, 1040, 312))
    }
}

private class BilibiliSearchClearButton(
    context: android.content.Context
) : ImageView(context) {
    init {
        contentDescription = "清除查询"
        isEnabled = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.contentDescription = "清除查询"
        info?.viewIdResourceName = "tv.danmaku.bili:id/search_close_btn"
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(true)
        info?.setBoundsInScreen(Rect(1040, 192, 1184, 312))
    }
}

private class FocusedTopSearchContainer(
    context: android.content.Context
) : FrameLayout(context) {
    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(
            FocusedSearchEditText(context),
            FrameLayout.LayoutParams(820, 96)
        )
        addView(
            SearchClearButton(context),
            FrameLayout.LayoutParams(96, 96)
        )
    }
}

private class FocusedSearchEditText(
    context: android.content.Context
) : EditText(context) {
    init {
        setSingleLine(true)
        setText("deepseek v4.1")
        isEnabled = true
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { requestFocus() }
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setFocused(true)
        info?.setEditable(true)
        info?.setBoundsInScreen(Rect(96, 48, 916, 144))
    }
}

private class SearchClearButton(
    context: android.content.Context
) : ImageView(context) {
    init {
        contentDescription = "×"
        isEnabled = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.contentDescription = "×"
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(true)
        info?.setBoundsInScreen(Rect(916, 48, 1012, 144))
    }
}
