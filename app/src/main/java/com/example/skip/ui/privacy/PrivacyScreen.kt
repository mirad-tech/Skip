package com.example.skip.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.skip.engine.SafetyGuard
import com.example.skip.ui.common.SimpleScreenScaffold

@Composable
fun PrivacyScreen(
    mode: PrivacyPageMode,
    versionName: String,
    onBack: () -> Unit
) {
    SimpleScreenScaffold(
        title = mode.title,
        onBack = onBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (mode) {
                PrivacyPageMode.Privacy -> {
                    InfoCard(
                        title = "本地处理",
                        body = "本 App 只在你主动开启无障碍服务后读取当前可见界面节点，用于识别你启用应用中的跳过、关闭类控件。"
                    )
                    InfoCard(
                        title = "隐私边界",
                        body = "规则、按应用开关、日志和统计都保存在本机；除非你手动导出 JSON 文件，否则不会离开设备。"
                    )
                    InfoCard(
                        title = "不会读取或上传",
                        body = "不联网，不上传屏幕内容，不记录完整页面内容，不收集账号、密码、验证码、支付信息，也不读取短信、联系人、相册、定位、相机或麦克风。"
                    )
                    InfoCard(
                        title = "高风险场景",
                        body = "同意、授权、允许、支付、购买、确认支付、登录、注册、隐私政策、用户协议、安装、删除、卸载、转账、发送、提交等内容命中后只写安全日志，不执行点击。"
                    )
                    InfoCard(
                        title = "联网变更",
                        body = "如未来新增联网能力，必须先更新隐私说明、权限说明和用户同意流程，不能静默启用。"
                    )
                }

                PrivacyPageMode.Safety -> {
                    InfoCard(
                        title = "默认保护",
                        body = SafetyGuard.protectedSummary()
                    )
                    InfoCard(
                        title = "实现边界",
                        body = "不使用 Root、Hook、改包、抓包、注入或绕过授权方案；坐标兜底也只对你明确创建或导入的规则生效。"
                    )
                }

                PrivacyPageMode.Permissions -> {
                    InfoCard(
                        title = "无障碍权限",
                        body = "必需。用于读取当前可见界面节点、报告 View ID，并在命中低风险规则后执行一次点击；不上传屏幕内容，不记录完整页面内容，不用于高风险按钮自动点击。关闭方式：系统设置 > 无障碍 > Skip。"
                    )
                    InfoCard(
                        title = "文档选择器",
                        body = "可选。仅在你主动导入或导出规则、日志时打开系统文件选择器，不申请外部存储权限。"
                    )
                    InfoCard(
                        title = "未申请的权限",
                        body = "当前不申请网络、通知、定位、通讯录、相机、麦克风、短信、外部存储等权限，也不接入广告 SDK、统计 SDK 或联网 SDK。"
                    )
                }

                PrivacyPageMode.About -> {
                    InfoCard(
                        title = "Skip $versionName",
                        body = "一个本地化的 Android 辅助点击工具。"
                    )
                    InfoCard(
                        title = "项目定位",
                        body = "减少重复点击，不破解广告，不绕过其他 App 的安全机制。"
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

enum class PrivacyPageMode(val title: String) {
    Privacy("隐私说明"),
    Safety("安全保护"),
    Permissions("权限说明"),
    About("关于")
}
