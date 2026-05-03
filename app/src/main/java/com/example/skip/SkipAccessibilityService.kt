package com.example.skip

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class SkipAccessibilityService : AccessibilityService() {
    private var lastClickAt = 0L
    private var lastPageKey: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        if (
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        if (!SkipConfigStore.isMasterEnabled(this)) return

        val root = rootInActiveWindow ?: return
        val packageName = (event.packageName ?: root.packageName)?.toString().orEmpty()
        if (!shouldHandlePackage(packageName)) return

        val pageKey = "$packageName:${event.className?.toString().orEmpty()}"
        val now = System.currentTimeMillis()
        if (pageKey == lastPageKey && now - lastClickAt < MIN_CLICK_INTERVAL_MS) {
            return
        }

        val rules = SkipRules(
            textKeywords = SkipConfigStore.getKeywords(this),
            viewIdKeywords = SkipConfigStore.getViewIdKeywords(this)
        )
        val clickableNode = findMatchingClickableNode(root, rules) ?: return
        if (clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastPageKey = pageKey
            lastClickAt = now
        }
    }

    override fun onInterrupt() = Unit

    private fun shouldHandlePackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == this.packageName) return false
        if (isProtectedPackage(packageName)) return false
        return SkipConfigStore.getWhitelistPackages(this).contains(packageName)
    }

    private fun isProtectedPackage(packageName: String): Boolean {
        val lower = packageName.lowercase(Locale.ROOT)
        if (lower in protectedExactPackages) return true
        if (protectedPrefixes.any { lower.startsWith(it) }) return true
        return protectedKeywords.any { lower.contains(it) }
    }

    private fun findMatchingClickableNode(
        root: AccessibilityNodeInfo,
        rules: SkipRules
    ): AccessibilityNodeInfo? {
        if (rules.textKeywords.isEmpty() && rules.viewIdKeywords.isEmpty()) return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && node.matchesRules(rules)) {
                val clickable = node.findClickableAncestor()
                if (clickable != null && clickable.isSafeClickTarget()) return clickable
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::add)
            }
        }

        return null
    }

    private fun AccessibilityNodeInfo.matchesRules(rules: SkipRules): Boolean {
        val textValues = listOfNotNull(
            text?.toString(),
            contentDescription?.toString()
        )
        val textMatched = textValues.any { value ->
            value.isLikelySkipText(rules.textKeywords)
        }
        if (textMatched) return true

        val normalizedViewId = viewIdResourceName?.normalizeForRuleMatch().orEmpty()
        return normalizedViewId.isNotEmpty() &&
            rules.viewIdKeywords.any { keyword ->
                normalizedViewId.contains(keyword.normalizeForRuleMatch())
            }
    }

    private fun AccessibilityNodeInfo.findClickableAncestor(): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        while (current != null) {
            if (current.isVisibleToUser && current.isEnabled && current.isClickable) {
                return current
            }
            if (++depth > MAX_CLICKABLE_PARENT_DEPTH) return null
            current = current.parent
        }
        return null
    }

    private fun AccessibilityNodeInfo.isSafeClickTarget(): Boolean {
        if (!isVisibleToUser || !isEnabled || isPassword) return false
        val classNameValue = className?.toString().orEmpty()
        if (classNameValue.contains("EditText", ignoreCase = true)) return false

        val bounds = Rect()
        getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val areaRatio = bounds.width().toFloat() * bounds.height() / (screenWidth * screenHeight)
        if (areaRatio > MAX_CLICK_TARGET_SCREEN_RATIO) return false

        return bounds.width() >= MIN_CLICK_TARGET_SIZE_PX &&
            bounds.height() >= MIN_CLICK_TARGET_SIZE_PX
    }

    private fun String.isLikelySkipText(keywords: List<String>): Boolean {
        val normalized = trim()
        if (normalized.isEmpty()) return false

        val lower = normalized.lowercase(Locale.ROOT)
        if (blockedTextFragments.any { lower.contains(it) }) return false

        return keywords.any { keyword ->
            lower.contains(keyword.lowercase(Locale.ROOT)) &&
                normalized.length <= MAX_SKIP_TEXT_LENGTH
        }
    }

    private fun String.normalizeForRuleMatch(): String {
        return lowercase(Locale.ROOT)
            .replace("-", "_")
            .replace(".", "_")
            .replace(":", "_")
    }

    private data class SkipRules(
        val textKeywords: List<String>,
        val viewIdKeywords: List<String>
    )

    companion object {
        private const val MIN_CLICK_INTERVAL_MS = 1000L
        private const val MAX_CLICKABLE_PARENT_DEPTH = 4
        private const val MAX_CLICK_TARGET_SCREEN_RATIO = 0.35f
        private const val MIN_CLICK_TARGET_SIZE_PX = 8
        private const val MAX_SKIP_TEXT_LENGTH = 32

        private val protectedExactPackages = setOf(
            "com.android.settings",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.eg.android.alipaygphone",
            "com.tencent.mm",
            "com.unionpay",
            "com.unionpay.tsmservice",
            "com.paypal.android.p2pmobile"
        )

        private val protectedPrefixes = listOf(
            "com.android.settings",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller"
        )

        private val protectedKeywords = listOf(
            "alipay",
            "bank",
            "finance",
            "financial",
            "paypal",
            "pay",
            "payment",
            "unionpay",
            "wallet",
            "securities",
            "broker",
            "insurance"
        )

        private val blockedTextFragments = listOf(
            "跳过登录",
            "跳过验证",
            "跳过绑定",
            "跳过设置",
            "不跳过",
            "skip login",
            "skip verification",
            "skip setup"
        )
    }
}
