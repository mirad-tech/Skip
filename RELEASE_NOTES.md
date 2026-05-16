# Skip 发布说明

## 1.4.1

发布日期：2026-05-16

### 更新内容

- 优化首页和应用列表的图标加载、分页与界面细节，提升应用管理页的浏览体验。
- 增加多套启动图标外观，并将默认应用版本更新到 `1.4.1`。
- 刷新 README 项目介绍，补充当前能力、技术栈、隐私边界和 release APK 下载入口。
- 新增 `AGENT.md`，沉淀后续代理协作时需要遵守的项目入口、安全边界、构建验证和工作区清理规则。
- 不再把普通构建缓存、APK 中间产物和本地工具备份当作源码提交；本次仅按发布需要保留 `downloads/Skip-v1.4.1-release.apk`。

### 修复和清理

- 清理旧的本地计划文件、历史 APK 产物、Gradle/Kotlin/Android 缓存和构建输出，避免仓库工作区被生成物干扰。
- 明确 release 签名文件仍只保留在本地，`keystore.properties` 和 `release.keystore` 不提交。
- 记录并规避 Codex 侧只读 Git 查询进程反复生成的问题，不新增 watcher、后台 Git 循环或自动化任务。

### 发布文件

- Release APK：`downloads/Skip-v1.4.1-release.apk`
- SHA256：`877F2A463443F62B8059AACA9C10B4DE64B05A42F48DD6FB37E66D9E1678E2EE`

### 验证摘要

本地自动化验证结果（2026-05-16）：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease
git diff --check
```

`testDebugUnitTest` 和 `assembleRelease` 已通过；构建仅有既有 deprecation warning。真机无障碍行为仍需按 `RELEASE_TEST_MATRIX.md` 做人工验证。

## 1.4.0

发布日期：2026-05-14

## 定位

本版本将 Skip 明确为本地自动点击辅助工具 / 开屏页面助手。产品不宣传为广告破解、广告屏蔽或绕过工具，不复制、不逆向、不照搬李跳跳或其他同类产品代码。

## 新增和调整

- 新增首次启动披露页。
- 新增无障碍权限用途说明页。
- 调整为用户主动同意后再引导开启无障碍。
- 新增集中式高风险点击保护策略。
- 高风险命中日志标记为 `blocked_by_safety_policy`。
- 坐标兜底默认关闭，并强制包名、时间窗、锚点、冷却和安全词限制。
- 规则导入增加高风险词和坐标兜底校验。
- 统计页增加安全阻止和坐标兜底计数。
- 权限说明页补充未申请权限和文档选择器说明。
- README、发布指南、合规检查表、测试矩阵、规则指南和示例规则补齐。

## 已知限制

- 无障碍服务可能被部分 ROM 后台策略回收，需要用户在系统设置中允许后台运行。
- 不保证所有应用开屏页面都能识别。
- release 构建通过不代表所有真机场景完成验证，仍需按 `RELEASE_TEST_MATRIX.md` 做人工测试。

## 验证摘要

本地自动化验证结果（2026-05-14）：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
git diff --check
.\gradlew.bat :app:assembleRelease
```

以上四项均已通过；`git diff --check` 仅有 CRLF 工作区提示。人工验证结果记录在 `RELEASE_TEST_MATRIX.md`。
