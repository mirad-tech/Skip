package com.example.skip.model

data class SkipRule(
    val id: String,
    val source: RuleSource,
    val name: String,
    val packageName: String,
    val appName: String,
    val enabled: Boolean = true,
    val activityName: String = "*",
    val matchTexts: List<String> = emptyList(),
    val matchContentDescriptions: List<String> = emptyList(),
    val matchViewIds: List<String> = emptyList(),
    val area: RuleArea = RuleArea.TopRight,
    val action: RuleAction = RuleAction.Click,
    val priority: Int = 10,
    val cooldownMs: Long = 1200L,
    val validDurationMs: Long = 10_000L,
    val minScore: Int = 70,
    val packageId: String = "local",
    val createdAt: Long = System.currentTimeMillis()
)

enum class RuleSource(val value: String, val label: String) {
    UserSimple("user_simple", "普通创建"),
    JsonFile("json_file", "JSON 导入"),
    BuiltIn("built_in", "内置规则"),
    Subscription("subscription", "订阅规则");

    companion object {
        fun fromValue(value: String): RuleSource {
            return entries.firstOrNull { it.value == value } ?: UserSimple
        }
    }
}

enum class RuleArea(val value: String, val label: String) {
    TopLeft("top_left", "左上"),
    TopCenter("top_center", "上方"),
    TopRight("top_right", "右上"),
    MiddleLeft("middle_left", "左侧"),
    Center("center", "中间"),
    MiddleRight("middle_right", "右侧"),
    BottomLeft("bottom_left", "左下"),
    BottomCenter("bottom_center", "下方"),
    BottomRight("bottom_right", "右下"),
    Any("any", "不确定");

    companion object {
        fun fromValue(value: String): RuleArea? {
            return entries.firstOrNull { it.value == value }
        }
    }
}

enum class RuleAction(val value: String) {
    Click("click");

    companion object {
        fun fromValue(value: String): RuleAction? {
            return entries.firstOrNull { it.value == value }
        }
    }
}
