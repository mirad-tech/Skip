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

class SkipAccessibilityService : AccessibilityService() {
    private var foregroundPackage: String? = null
    private var foregroundSince = 0L
    private val lastRuleClickAt = mutableMapOf<String, Long>()
    private var lastClickSignature: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsRepository.markServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!event.isSupportedEvent()) return
        SettingsRepository.markServiceActive(this)
        if (!SettingsRepository.isMasterEnabled(this)) return

        val root = rootInActiveWindow ?: return
        val packageName = (event.packageName ?: root.packageName)?.toString().orEmpty()
        val now = System.currentTimeMillis()
        updateForegroundWindow(packageName, now)

        if (!SafetyGuard.canHandlePackage(this, packageName)) {
            SettingsRepository.setLastFailureReason(this, "当前 App 不在白名单或属于安全保护名单")
            return
        }

        val appElapsedMs = now - foregroundSince
        val rules = RuleRepository.getEnabledRulesForPackage(this, packageName)
        if (rules.isEmpty()) {
            SettingsRepository.setLastFailureReason(this, "当前 App 没有启用规则")
            return
        }
        val activeRules = rules.filter { rule ->
            val last = lastRuleClickAt[rule.id] ?: 0L
            appElapsedMs <= rule.validDurationMs && now - last >= rule.cooldownMs
        }
        if (activeRules.isEmpty()) return

        val match = NodeScanner.findBestMatch(root, activeRules, appElapsedMs)
        if (match == null) {
            SettingsRepository.setLastFailureReason(this, "未找到达到分数阈值的可点击节点")
            return
        }
        val signature = "$packageName:${match.ruleName}:${match.clickNode.hashCode()}"
        val lastAnyRuleClick = lastRuleClickAt.values.maxOrNull() ?: 0L
        if (signature == lastClickSignature && now - lastAnyRuleClick < REPEAT_CLICK_GUARD_MS) return

        if (ClickExecutor.click(match.clickNode)) {
            lastRuleClickAt[match.ruleId] = now
            lastClickSignature = signature
            SettingsRepository.markLastClick(this, now)
            LogRepository.addClickLog(
                context = this,
                log = ClickLog(
                    timeMillis = now,
                    packageName = packageName,
                    ruleName = match.ruleName,
                    success = true
                )
            )
        } else {
            SettingsRepository.setLastFailureReason(this, "点击动作执行失败")
            LogRepository.addClickLog(
                context = this,
                log = ClickLog(
                    timeMillis = now,
                    packageName = packageName,
                    ruleName = match.ruleName,
                    success = false,
                    reason = "点击动作执行失败"
                )
            )
        }
    }

    override fun onInterrupt() {
        SettingsRepository.markServiceInterrupted(this)
    }

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
        private const val REPEAT_CLICK_GUARD_MS = 3000L
    }
}
