# Skip

Skip 是一款本地运行的 Android 开屏页面辅助工具。它只在用户主动开启无障碍服务、完成用途说明并启用规则后，在应用进入前台后的短时间窗口内，辅助点击明确的“跳过”“关闭”类低风险控件。

当前应用版本：`1.4.1`

## 项目定位

Skip 的核心目标是减少重复手动点击开屏页跳过按钮的操作成本，而不是绕过应用机制或替代用户决策。

本项目坚持以下边界：

- 不宣传为广告破解、广告屏蔽或绕过工具。
- 不复制、不逆向、不照搬李跳跳或其他同类产品代码。
- 默认本地处理，不上传屏幕内容、规则、日志、统计或个人数据。
- 不接入广告 SDK、统计 SDK 或联网 SDK。
- 不自动点击支付、授权、登录、注册、隐私同意、安装、删除、转账、发送、提交等高风险按钮。

## 当前能力

- 首次启动披露：进入主流程前说明应用用途、权限边界和风险。
- 无障碍用途说明：跳转系统设置前解释读取窗口内容、执行手势、报告 View ID 的用途。
- 总开关与安全模式：支持快速停用自动点击，安全模式下只记录命中不执行点击。
- 按应用管理：可单独控制每个 App 的默认规则、自定义规则和黑名单策略。
- 规则管理：支持本地创建、编辑、启用、停用、删除规则。
- JSON 导入：支持本地规则包导入，并校验包名、匹配条件、高风险词和坐标兜底限制。
- 默认规则：内置开屏跳过关键词、View ID 关键词、区域和分数策略。
- 点击日志：记录命中、点击、失败、冷却、安全模式和安全策略阻止等阶段。
- 统计页：按应用和规则统计命中、成功、失败、安全阻止和坐标兜底次数。
- 诊断包导出：用户主动导出本地 JSON 诊断包，用于分析未跳过、误跳过和系统限制原因。
- 图标外观：提供多套启动图标外观，可在应用内切换。
- 系统兼容说明：提示不同 ROM 下无障碍服务可能遇到的后台限制。

## 使用流程

1. 构建或安装 Skip APK。
2. 打开 Skip，阅读首次启动披露内容。
3. 主动同意后进入无障碍权限用途说明。
4. 点击“去系统设置开启”，在 Android 无障碍设置中手动开启 Skip。
5. 回到 Skip，在“应用管理”中选择目标 App。
6. 按需启用默认规则、自定义规则或黑名单策略。
7. 需要更精确匹配时，创建本地规则或导入 JSON 规则。
8. 在“日志与隐私”中查看点击日志、规则日志、命中统计、隐私说明和权限说明。

## 规则与匹配

规则用于描述“在哪个应用、什么时间窗口内、识别什么低风险控件、如何点击”。

当前规则能力包括：

- 按 `packageName` 绑定目标应用。
- 通过节点文字、内容描述和 View ID 匹配候选控件。
- 支持 `contains`、`exact`、`regex` 匹配模式。
- 支持区域限制、优先级、冷却时间、最低匹配分。
- 默认只在应用进入前台后的短时间窗口内生效。
- 坐标兜底默认关闭，只允许在低风险、包名明确、锚点明确、冷却明确的规则中使用。

规则格式见 [RULES_GUIDE.md](RULES_GUIDE.md)，示例见 [sample_rules.json](sample_rules.json)。

## 安全保护

Skip 会避开系统界面、桌面、安装器、权限页、输入法、支付、银行、钱包、金融、密码管理等敏感场景。

默认禁止自动点击包含以下含义的按钮或节点：

`同意`、`授权`、`允许`、`支付`、`购买`、`确认支付`、`登录`、`注册`、`隐私政策`、`用户协议`、`安装`、`删除`、`卸载`、`转账`、`发送`、`提交`

命中高风险内容时：

- 不执行 `ACTION_CLICK`。
- 不执行手势点击。
- 不执行坐标兜底点击。
- 只写入安全日志。
- 日志原因标记为 `blocked_by_safety_policy`。

## 隐私与权限

Skip 保持本地处理：

- 不申请 `INTERNET` 权限。
- 不读取短信、联系人、相册、定位、相机、麦克风、电话、账号。
- 不申请外部存储读写权限，导入导出使用系统文件选择器。
- 不 Root、不 Hook、不改包、不抓包、不注入。
- 日志只保存在本机，并对可能包含隐私的信息做最小化记录和脱敏。
- 诊断包只由用户主动导出，不自动上传。

诊断包字段说明见 [LOG_DIAGNOSTIC_GUIDE.md](LOG_DIAGNOSTIC_GUIDE.md)。

## 技术栈

- Android 单模块工程：`:app`
- Kotlin + Jetpack Compose + Material 3
- Android Gradle Plugin `9.2.0`
- Kotlin Compose 插件 `2.2.10`
- Compose BOM `2026.02.01`
- `minSdk 28`
- `targetSdk 36`
- `compileSdk 36.1`

## 构建与验证

常用验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
git diff --check
.\gradlew.bat :app:assembleRelease
```

Release 构建需要本地 `keystore.properties` 和签名文件。真实签名文件、密码和密钥不得提交到仓库。

## 项目文档

- [RULES_GUIDE.md](RULES_GUIDE.md)：规则格式、匹配字段和安全限制。
- [LOG_DIAGNOSTIC_GUIDE.md](LOG_DIAGNOSTIC_GUIDE.md)：复现问题并导出诊断包的说明。
- [RELEASE_GUIDE.md](RELEASE_GUIDE.md)：构建、签名和发布流程。
- [RELEASE_TEST_MATRIX.md](RELEASE_TEST_MATRIX.md)：发布测试矩阵。
- [COMPLIANCE_CHECKLIST.md](COMPLIANCE_CHECKLIST.md)：合规检查表。
- [ONLINE_RELEASE_PLAN.md](ONLINE_RELEASE_PLAN.md)：上线前分阶段计划。
- [RELEASE_NOTES.md](RELEASE_NOTES.md)：版本发布说明。

## 许可证

本项目使用 [MIT License](LICENSE) 开源。
