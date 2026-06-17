# Skip 发布说明

## 1.0.4

发布日期：2026-06-17

### 更新内容

- 默认关闭 Chrome 内置开屏跳过规则，避免搜索页左上角 `+` 被误点；Chrome 自定义规则仍可显式启用。
- 收紧默认规则的广告信号识别，避免 `attachments_add` 这类普通 View ID 被当作广告信号。
- 移除 B 站泛关闭控件特权加分，避免视频页 `关闭弹幕` 被误点。
- 增加延迟点击前的重定位一致性检查，防止 100ms 稳定等待后换成语义不同的新目标。
- 补充 Chrome `+`、B 站 `关闭弹幕` 和 B 站开屏 `跳过 5` 的回归验证。

### 版本信息

- `versionCode`：12
- `versionName`：`1.0.4`

### 发布文件

- Release APK：`Skip-v1.0.4-release.apk`
- SHA256：`ECB7A5765137EE0D995503B59F49A5D5743F802E6FDBBEB21CDFFE846E07F65C`

### 验证摘要

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
git diff --check
```

## 1.0.3

发布日期：2026-06-13

### 更新内容

- 统一点击日志结果语义，让成功、失败和安全阻止记录更一致。
- 将规则生命周期变更从 UI 层移出，集中到规则仓储处理。
- 抽离无障碍服务中的点击流程状态，降低服务主体复杂度。
- 保留隐私、权限、说明等共享页面的返回来源。
- 修复首次披露页后的无障碍用途说明返回来源，避免从系统 Hub 进入时丢失返回目标。

### 版本信息

- `versionCode`：11
- `versionName`：`1.0.3`

### 发布文件

- Release APK：`Skip-v1.0.3-release.apk`
- SHA256：`3E2F0BB83F91E22164964084F49724170C2DD0E93E64769C1126B72895421AE9`

### 验证摘要

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
git diff --check
```

## 1.0.2

发布日期：2026-05-22

### 更新内容

- 修复安全扫描报告指出的坐标兜底边界：分发固定坐标手势前必须解析并检查坐标下真实控件。
- 统一高风险点击策略，导入、保存、运行时检查都覆盖文本、content description、View ID 和坐标锚点。
- 执行 `activityName` 规则作用域，避免 Activity 级规则在同包其它页面误命中。
- JSON 导入预览展示目标应用、规则、匹配字段、动作、坐标兜底和额外确认风险点。
- 关闭 Android 自动备份中的本地自动化状态备份，并明确诊断报告为“文本脱敏但保留元数据”。
- 将首页操作文案从“关闭服务”调整为“暂停自动化”，暂停后不再持久化禁用状态事件日志。

### 版本信息

- `versionCode`：10
- `versionName`：`1.0.2`

### 发布文件

- Release APK：`Skip-v1.0.2-release.apk`
- SHA256：`670558DE4F87B235A307C975370C828AEAA8AB8B4D0B7038E6BF40419195C266`

### 验证摘要

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
git diff --check
```

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

- Release APK：`https://github.com/mirad-tech/Skip/releases/download/v1.0.0/Skip-v1.0.0-release.apk`
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
