package com.example.skip.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.skip.data.LogRepository
import com.example.skip.data.RuleRepository
import com.example.skip.data.SettingsRepository
import com.example.skip.engine.ClickExecutor
import com.example.skip.engine.NodeScanner
import com.example.skip.engine.SafetyGuard
import com.example.skip.model.ClickLog
import com.example.skip.model.SkipRule

class SkipAccessibilityService : AccessibilityService() {
    private var foregroundPackage: String? = null
    private var foregroundSince = 0L
    private var lastClickAt = 0L
    private var lastClickSignature: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!event.isSupportedEvent()) return
        if (!SettingsRepository.isMasterEnabled(this)) return

        val root = rootInActiveWindow ?: return
        val packageName = (event.packageName ?: root.packageName)?.toString().orEmpty()
        val now = System.currentTimeMillis()
        updateForegroundWindow(packageName, now)

        if (!SafetyGuard.canHandlePackage(this, packageName)) return
        if (now - foregroundSince > APP_START_SCAN_WINDOW_MS) return
        if (now - lastClickAt < MIN_CLICK_INTERVAL_MS) return

        val rule = SkipRule(
            textKeywords = RuleRepository.getKeywords(this),
            viewIdKeywords = RuleRepository.getViewIdKeywords(this)
        )
        val match = NodeScanner.findBestMatch(root, rule) ?: return
        val signature = "$packageName:${match.ruleName}:${match.clickNode.hashCode()}"
        if (signature == lastClickSignature && now - lastClickAt < REPEAT_CLICK_GUARD_MS) return

        if (ClickExecutor.click(match.clickNode)) {
            lastClickAt = now
            lastClickSignature = signature
            LogRepository.addClickLog(
                context = this,
                log = ClickLog(
                    timeMillis = now,
                    packageName = packageName,
                    ruleName = match.ruleName
                )
            )
        }
    }

    override fun onInterrupt() = Unit

    private fun updateForegroundWindow(packageName: String, now: Long) {
        if (packageName.isBlank()) return
        if (packageName != foregroundPackage) {
            foregroundPackage = packageName
            foregroundSince = now
            lastClickSignature = null
        }
    }

    private fun AccessibilityEvent.isSupportedEvent(): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    companion object {
        private const val MIN_CLICK_INTERVAL_MS = 1000L
        private const val REPEAT_CLICK_GUARD_MS = 3000L
        private const val APP_START_SCAN_WINDOW_MS = 10_000L
    }
}
