package com.example.skip.model

data class ClickLog(
    val timeMillis: Long,
    val packageName: String,
    val appName: String = "",
    val activityName: String = "",
    val ruleType: String = "",
    val ruleName: String = "",
    val ruleId: String = "",
    val stage: ClickLogStage = ClickLogStage.ClickEffectUnknown,
    val success: Boolean? = null,
    val reason: String = "",
    val failureReason: String = "",
    val detail: String = "",
    val eventType: Int? = null,
    val eventPackageName: String = "",
    val rootWindowNull: Boolean = false,
    val windowId: Int? = null,
    val rootChildCount: Int? = null,
    val canRetrieveWindowContent: Boolean = false,
    val candidateCount: Int? = null,
    val bestCandidateScore: Int? = null,
    val bestCandidateBounds: String = "",
    val minScore: Int? = null,
    val matchedKeyword: String = "",
    val nodeText: String = "",
    val contentDescription: String = "",
    val viewIdResourceName: String = "",
    val boundsInScreen: String = "",
    val nodeClickable: Boolean? = null,
    val parentClickable: Boolean? = null,
    val score: Int? = null,
    val area: String = "",
    val clickMethod: ClickMethodLog = ClickMethodLog.None,
    val actionReturnValue: Boolean? = null,
    val clickResult: Boolean? = null,
    val effectConfirmed: Boolean? = null,
    val delayBeforeClickMs: Long? = null,
    val retryCount: Int = 0,
    val deviceRom: String = "",
    val elapsedSinceAppStartMs: Long? = null,
    val defaultRuleWindowMs: Long? = null,
    val isSystemPackage: Boolean = false,
    val isLauncherPackage: Boolean = false,
    val isSelfPackage: Boolean = false,
    val isSelfAppLabelCandidate: Boolean = false,
    val blockedBySafety: Boolean = false,
    val blockedReason: String = "",
    val defaultRuleAreaAllowed: Boolean? = null,
    val textKeywordIsStandaloneSkip: Boolean = false,
    val effectConfirmReason: String = "",
    val safetyModeEnabled: Boolean = false,
    val clickSkippedBySafetyMode: Boolean = false,
    val candidateBounds: String = "",
    val candidateCenterX: Int? = null,
    val candidateCenterY: Int? = null,
    val clickedNodeBounds: String = "",
    val clickedNodeClassName: String = "",
    val clickedNodeText: String = "",
    val clickedNodeViewId: String = "",
    val clickedParentDepth: Int? = null,
    val candidateAreaRatio: Float? = null,
    val gestureX: Int? = null,
    val gestureY: Int? = null,
    val isLargeCandidateBounds: Boolean = false,
    val isFixedCoordinateClick: Boolean = false,
    val clickTargetSource: ClickTargetSourceLog = ClickTargetSourceLog.None
)

enum class ClickLogStage(val value: String, val label: String, val isDebugOnly: Boolean = false) {
    ServiceEventReceived("service_event_received", "收到事件", true),
    EventPackageNull("event_package_null", "包名为空"),
    RootWindowNull("root_window_null", "窗口为空"),
    NoCandidateFound("no_candidate_found", "暂无候选"),
    CandidateFound("candidate_found", "发现候选", true),
    RuleMatched("rule_matched", "规则命中"),
    ClickAttempted("click_attempted", "尝试点击"),
    ClickActionSuccess("click_action_success", "点击已返回"),
    ClickEffectConfirmed("click_effect_confirmed", "效果已确认"),
    ClickEffectUnknown("click_effect_unknown", "效果未知"),
    ClickMisfireSelfOpened("click_misfire_self_opened", "误开 Skip"),
    ClickCancelledPackageUnknown("click_cancelled_package_unknown", "包名未知"),
    ClickCancelledPackageChanged("click_cancelled_package_changed", "包名已变化"),
    ClickCancelledSelfPackage("click_cancelled_self_package", "取消自身点击"),
    ClickSkippedBySafetyMode("click_skipped_by_safety_mode", "安全模式跳过点击"),
    ClickFailed("click_failed", "点击失败"),
    SkippedBySafety("skipped_by_safety", "安全保护"),
    SkippedByBlacklist("skipped_by_blacklist", "黑名单拦截"),
    SkippedSelfPackage("skipped_self_package", "忽略自身"),
    SkippedByDisabledSetting("skipped_by_disabled_setting", "总开关关闭"),
    SkippedByTimeWindow("skipped_by_time_window", "时间窗外"),
    SkippedByLowScore("skipped_by_low_score", "分数不足");

    companion object {
        fun fromValue(value: String): ClickLogStage {
            return entries.firstOrNull { it.value == value } ?: ClickEffectUnknown
        }
    }
}

enum class ClickMethodLog(val value: String, val label: String) {
    ActionClick("ACTION_CLICK", "ACTION_CLICK"),
    DispatchGesture("DISPATCH_GESTURE", "DISPATCH_GESTURE"),
    None("NONE", "NONE");

    companion object {
        fun fromValue(value: String): ClickMethodLog {
            return entries.firstOrNull { it.value == value } ?: None
        }
    }
}

enum class ClickTargetSourceLog(val value: String, val label: String) {
    NodeSelf("NODE_SELF", "节点"),
    ClickableParent("CLICKABLE_PARENT", "父节点"),
    GestureOnNodeCenter("GESTURE_ON_NODE_CENTER", "节点中心"),
    FixedPositionForbidden("FIXED_POSITION_FORBIDDEN", "固定位置已禁用"),
    None("NONE", "NONE");

    companion object {
        fun fromValue(value: String): ClickTargetSourceLog {
            return entries.firstOrNull { it.value == value } ?: None
        }
    }
}
