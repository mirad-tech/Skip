package com.example.skip

import android.app.Activity
import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

class ScannerFixtureActivity : Activity() {
    enum class Scenario {
        SplashSkipButton,
        MobileTicketHome,
        ChromeLocationBarAttachmentAdd,
        BilibiliDanmakuClose,
        BilibiliCountdownSkip,
        GenericCloseOnly,
        BilibiliSearchClearButton,
        FocusedTopSearchField,
        StandaloneSkipInsideClickableParent,
        StandaloneSkipInsideEditableActionPath,
        CoordinateIdentityChildInsideClickableParent,
        CoordinateIdentityInsideVisibleNonClickableParent
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
                    Scenario.GenericCloseOnly -> addView(
                        GenericCloseOnlyButton(context),
                        FrameLayout.LayoutParams(120, 80)
                    )
                    Scenario.BilibiliSearchClearButton -> addView(
                        BilibiliSearchContainer(context),
                        FrameLayout.LayoutParams(1080, 180)
                    )
                    Scenario.FocusedTopSearchField -> addView(
                        FocusedTopSearchContainer(context),
                        FrameLayout.LayoutParams(1080, 180)
                    )
                    Scenario.StandaloneSkipInsideClickableParent -> addView(
                        StandaloneSkipClickableParent(context),
                        FrameLayout.LayoutParams(144, 112)
                    )
                    Scenario.StandaloneSkipInsideEditableActionPath -> addView(
                        StandaloneSkipEditableActionParent(context),
                        FrameLayout.LayoutParams(144, 112)
                    )
                    Scenario.CoordinateIdentityChildInsideClickableParent -> addView(
                        CoordinateIdentityClickableParent(context),
                        FrameLayout.LayoutParams(120, 104)
                    )
                    Scenario.CoordinateIdentityInsideVisibleNonClickableParent -> addView(
                        VisibleNonClickableCoordinateParent(context),
                        FrameLayout.LayoutParams(1080, 1920)
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
        info?.setBoundsInScreen(
            Rect(
                0,
                0,
                Resources.getSystem().displayMetrics.widthPixels,
                Resources.getSystem().displayMetrics.heightPixels
            )
        )
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
        info?.setBoundsInScreen(topRightBounds(width = 120, height = 80, rightMargin = 60, top = 40))
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

private class GenericCloseOnlyButton(
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
        info?.setBoundsInScreen(Rect(920, 40, 1_040, 120))
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

private class StandaloneSkipClickableParent(
    context: android.content.Context
) : FrameLayout(context) {
    init {
        isEnabled = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(
            StandaloneSkipTextView(context),
            FrameLayout.LayoutParams(72, 64)
        )
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(true)
        info?.setBoundsInScreen(topRightBounds(width = 144, height = 112, rightMargin = 40, top = 24))
    }
}

private class StandaloneSkipTextView(
    context: android.content.Context
) : TextView(context) {
    init {
        text = "跳过"
        isEnabled = true
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.text = "跳过"
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(false)
        info?.setBoundsInScreen(topRightBounds(width = 72, height = 64, rightMargin = 64, top = 48))
    }
}

private class StandaloneSkipEditableActionParent(
    context: android.content.Context
) : FrameLayout(context) {
    init {
        isEnabled = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(
            EditableActionPathNode(context),
            FrameLayout.LayoutParams(96, 80)
        )
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(true)
        info?.setBoundsInScreen(topRightBounds(width = 144, height = 112, rightMargin = 40, top = 24))
    }
}

private class EditableActionPathNode(
    context: android.content.Context
) : FrameLayout(context) {
    init {
        isEnabled = true
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(
            StandaloneSkipTextView(context),
            FrameLayout.LayoutParams(72, 64)
        )
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.setEditable(true)
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(false)
        info?.setBoundsInScreen(topRightBounds(width = 104, height = 88, rightMargin = 56, top = 32))
    }
}

private class CoordinateIdentityClickableParent(
    context: android.content.Context
) : FrameLayout(context) {
    init {
        isEnabled = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(
            CoordinateIdentityDecorativeChild(context),
            FrameLayout.LayoutParams(60, 60)
        )
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.viewIdResourceName = "com.example.news:id/splash_skip"
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(true)
        info?.setBoundsInScreen(Rect(896, 24, 1_016, 128))
    }
}

private class CoordinateIdentityDecorativeChild(
    context: android.content.Context
) : ImageView(context) {
    init {
        isEnabled = true
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.text = ""
        info?.contentDescription = ""
        info?.viewIdResourceName = ""
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(false)
        info?.setBoundsInScreen(Rect(920, 40, 980, 100))
    }
}

private class VisibleNonClickableCoordinateParent(
    context: android.content.Context
) : FrameLayout(context) {
    init {
        isEnabled = true
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(
            NestedCoordinateSkipButton(context),
            FrameLayout.LayoutParams(60, 60)
        )
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(false)
        info?.setBoundsInScreen(Rect(0, 0, 1080, 1920))
    }
}

private class NestedCoordinateSkipButton(
    context: android.content.Context
) : Button(context) {
    init {
        text = "跳过"
        isEnabled = true
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo?) {
        super.onInitializeAccessibilityNodeInfo(info)
        info?.text = "跳过"
        info?.viewIdResourceName = "com.example.news:id/nested_splash_skip"
        info?.setVisibleToUser(true)
        info?.setEnabled(true)
        info?.setClickable(true)
        info?.setBoundsInScreen(Rect(920, 40, 980, 100))
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

private fun topRightBounds(
    width: Int,
    height: Int,
    rightMargin: Int,
    top: Int
): Rect {
    val screenWidth = Resources.getSystem().displayMetrics.widthPixels.coerceAtLeast(1)
    val right = (screenWidth - rightMargin).coerceAtLeast(width)
    return Rect(right - width, top, right, top + height)
}
