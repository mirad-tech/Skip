# Skip 发布说明

## 1.0.0

发布日期：2026-05-19

### 更新内容

- 首个 GitHub 正式发布版本，定位为本地运行的 Android 开屏页面辅助工具。
- 仅在用户主动开启无障碍服务、完成用途说明并启用规则后工作。
- 支持默认规则、自定义规则、按应用管理、黑名单策略、点击日志、统计页和诊断包导出。
- 支持本地 JSON 规则包导入，并校验包名、匹配条件、高风险词和坐标兜底限制。
- 提供首次启动披露、无障碍用途说明、隐私说明、权限说明和系统兼容说明。
- 提供多套启动图标外观，可在应用内切换。

### 安全和隐私边界

- 默认本地处理，不上传屏幕内容、规则、日志、统计或个人数据。
- 不接入广告 SDK、统计 SDK 或联网 SDK。
- 不申请 `INTERNET`、外部存储、定位、相机、麦克风、短信、联系人、电话或账号权限。
- 不自动点击支付、授权、登录、注册、隐私同意、安装、删除、转账、发送、提交等高风险按钮。
- 坐标兜底默认关闭，只允许在低风险、包名明确、锚点明确、冷却明确的规则中使用。

### 发布文件

- Release APK：`downloads/Skip-v1.0.0-release.apk`
- SHA256：`A73F1AFD0ABDB5FBC898D00D50DE2E70B1C42722BC4353DCF1DC8ECFA4F89473`

### 验证摘要

本地自动化验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
git diff --check
```

真机无障碍行为仍建议按 `RELEASE_TEST_MATRIX.md` 做人工验证。
