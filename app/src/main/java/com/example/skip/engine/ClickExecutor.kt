package com.example.skip.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object ClickExecutor {
    private const val MAX_CLICKABLE_PARENT_DEPTH = 4
    private const val MAX_CLICK_TARGET_SCREEN_RATIO = 0.35f
    private const val MIN_CLICK_TARGET_SIZE_PX = 8

    fun findClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null) {
            if (current.isSafeClickTarget()) return current
            if (++depth > MAX_CLICKABLE_PARENT_DEPTH) return null
            current = current.parent
        }
        return null
    }

    fun click(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun AccessibilityNodeInfo.isSafeClickTarget(): Boolean {
        if (!isVisibleToUser || !isEnabled || !isClickable || isPassword) return false
        val classNameValue = className?.toString().orEmpty()
        if (classNameValue.contains("EditText", ignoreCase = true)) return false

        val bounds = Rect()
        getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels
            .coerceAtLeast(1)
        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
            .coerceAtLeast(1)
        val areaRatio = bounds.width().toFloat() * bounds.height() / (screenWidth * screenHeight)
        if (areaRatio > MAX_CLICK_TARGET_SCREEN_RATIO) return false

        return bounds.width() >= MIN_CLICK_TARGET_SIZE_PX &&
            bounds.height() >= MIN_CLICK_TARGET_SIZE_PX
    }
}
