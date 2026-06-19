package com.example.skip.ui.onboarding

object ReleaseDisclosureCopy {
    data class DisclosureCardCopy(
        val title: String,
        val body: String
    )

    val disclosureItems = listOf(
        "Skip 是本地自动点击辅助工具 / 开屏页面助手，只处理用户明确启用的低风险重复点击。",
        "需要无障碍权限读取当前可见界面节点，并在命中规则后执行一次点击手势。",
        "网络仅用于你在关于页手动检测新版本和下载更新 APK，访问 GitHub Releases；不上传屏幕内容、规则、日志、统计或个人数据。",
        "不会自动点击同意、授权、允许、支付、购买、确认支付、登录、注册、隐私政策、用户协议、安装、删除、卸载、转账、发送、提交等高风险按钮。",
        "不保证对所有应用生效，你可以随时关闭总开关、应用开关、规则开关或系统无障碍服务。"
    )

    val accessibilityPurposeCards = listOf(
        DisclosureCardCopy(
            title = "读取窗口内容",
            body = "仅用于在本机识别你启用应用中的跳过、关闭类控件。"
        ),
        DisclosureCardCopy(
            title = "执行手势",
            body = "仅用于点击明确命中的低风险控件。"
        ),
        DisclosureCardCopy(
            title = "报告 View ID",
            body = "用于提高规则匹配准确性，降低误触。"
        ),
        DisclosureCardCopy(
            title = "本地日志与隐私",
            body = "不上传屏幕内容，不记录完整页面内容。日志只保存在本机，并对可能包含隐私的信息做最小化记录和脱敏。"
        ),
        DisclosureCardCopy(
            title = "高风险按钮",
            body = "高风险按钮命中后只写安全日志，不执行点击。"
        )
    )

    val accessibilityPurposeItems = accessibilityPurposeCards.map { card ->
        when (card.title) {
            "读取窗口内容",
            "执行手势",
            "报告 View ID" -> "${card.title}：${card.body}"
            else -> card.body
        }
    }

    fun allText(): String {
        return (disclosureItems + accessibilityPurposeItems).joinToString("\n")
    }
}
