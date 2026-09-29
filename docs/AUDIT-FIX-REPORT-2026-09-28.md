# Skip 审计修复报告（2026-09-28）

> 本报告对应审计发现的全量修复（P0 + P1 + P2）。
> 基准版本：`1.0.17`（`versionCode 25`），基线提交 `e24acc98fdc14955afecd828218e64e8658585da`。
> 分支：`fix/audit-p0-p1-p2-20260928`（由仓库所有者创建，见 §5）。

---

## 0. 执行环境与本次结论的效力边界

| 事实 | 影响 |
|---|---|
| 修复期间 `pwsh` 完全不可用（`SetNamedSecurityInfoW failed (Win32 5): grantWrite(S:\Android\AndroidStudioProjects\Skip)`） | 实施方**无法创建分支、无法提交、无法运行 gradle、无法编译**。代码改动已写入工作区，但**构建与运行验证由仓库所有者执行** |
| 无网络 | 无法验证真实 GitHub Release 的资产命名与 digest |
| 无 Android 设备 / 无 TalkBack | 运行期与无障碍验证未执行 |
| 仓库无测试源集 | 本次修复**没有自动化回归防护** |

> 关于 `pwsh`：这不是单个会话的偶发问题。本次共两次独立尝试（主编与一个独立验证代理）都无法执行任何命令，且沙箱 ACL 修复命令被审批链拒绝。**在本环境中不要指望任何代理能自行跑 git 或 gradle**；请在 Android Studio 的内置终端（或任何普通 PowerShell 窗口）执行 §3.1 的验证命令。

**因此本报告不声称任何构建、测试或真机结果通过。** 所有验收项的状态见 §3，未执行项一律标注「未验证」。

---

## 1. 修复清单

状态图例：**已改** = 代码已写入工作区；**未验证** = 缺少构建/设备/网络条件下的验证。

### 1.1 P0

| ID | 缺陷 | 影响 | 改动文件 | 状态 |
|---|---|---|---|---|
| F-01 | 黑名单操作重置其它应用的自定义规则开关 | 用户手动关闭的自定义规则（含坐标兜底）被静默重新启用，点击面被动扩大 | `model/AppPolicy.kt`、`data/SettingsRepository.kt` | 已改 / 未验证 |
| F-02 | Room 无迁移策略 | 未来 schema 变更会让已安装用户打开日志页即崩溃 | `data/db/SkipDatabase.kt` | 已改 / 未验证 |
| F-03 | 启动器/自身/受保护包早退路径不推进节流时间戳 | 启动器前台时 300ms 内容事件节流完全失效，每个事件跑 ≤500 节点遍历 | `service/SkipAccessibilityService.kt` | 已改 / 未验证 |
| F-04 | 安全模式存在 100ms 竞态窗口 | 延迟窗口内开启安全模式仍会点击，违背「只记录不点击」承诺 | `service/PendingClickCoordinator.kt`、`service/SkipAccessibilityService.kt` | 已改 / 未验证 |
| F-05 | 零 CI | 版本一致性、构建、密钥泄漏全靠人工 | `.github/workflows/verify.yml`（新增）、`tools/verify-release.ps1` | 已改 / 未验证 |

### 1.2 P1

| ID | 缺陷 | 改动文件 | 状态 |
|---|---|---|---|
| F-15 | 自更新下载无协议/主机校验、无体积上限、重定向不受约束 | `ui/about/UpdateRepository.kt`、`ui/about/AboutScreen.kt` | 已改 / 未验证 |
| F-16 | 资产白名单过于脆弱（同时发布两种命名即永久失败）；非法 tag 被静默判为「已是最新」 | `ui/about/UpdateReleaseParser.kt`、`ui/about/VersionComparator.kt`、`ui/about/UpdateCardBehavior.kt` | 已改 / 未验证 |
| F-11 | 服务热路径重复编译正则、构建内置规则时重复解析 prefs | `engine/HighRiskClickPolicy.kt`、`engine/SafetyGuard.kt`、`data/RuleRepository.kt` | 已改 / 未验证 |
| F-26 | 文档过期与契约缺失 | `docs/CODEX_HANDOFF.md`、`AGENT.md`、`RULES_GUIDE.md`、`LOG_DIAGNOSTIC_GUIDE.md`、`docs/superpowers/plans/2026-07-10-*.md`、`downloads/github-release-notes-1.0.17.md`（新增） | 已改 / 已核对 |
| F-27 | `logs/` 含第三方 App 界面录屏与无障碍树 dump | `SECURITY.md`（记录 + 清理指引） | 已记录，**未删除**（遵所有者决定） |
| F-25 | 发布脚本无法在非 main 分支复用 | `tools/verify-release.ps1` 新增 `-CheckVersionOnly` | 已改 / 未验证 |
| F-37 | `applicationId` 未声明为冻结标识 | `README.md`、`docs/README.md` | 已改 / 已核对 |

### 1.3 P2

| ID | 缺陷 | 改动文件 | 状态 |
|---|---|---|---|
| F-06 | 成功/失败统计可重叠（`success + failure > total`） | `data/LogRepository.kt` | 已改 / 未验证 |
| F-07 | ACTION_CLICK 成功后目标仍在会补发手势（潜在双击） | `service/PendingClickCoordinator.kt` | 已改 / 未验证 |
| F-08 | `NodeScanner` 预算耗尽判定不精确，掩盖树被截断 | `engine/NodeScanner.kt` | 已改 / 未验证 |
| F-09 | `root ?: return` 可能留下无回调的永久 pending | `service/PendingClickCoordinator.kt` | 已改 / 未验证 |
| F-10 | 总开关关闭、无规则分支不写日志 | `service/SkipAccessibilityService.kt` | 已改 / 未验证 |
| F-12 | `defaultRuleAreaAllowed` 恒为 true，诊断字段无判别力 | `engine/ScoreEvaluator.kt` | 已改 / 未验证 |
| F-13 | 坐标手势路径缺 35% 面积上限，与语义路径不一致 | `engine/ClickExecutor.kt` | 已改 / 未验证 |
| F-14 | 受保护包关键词子串匹配过宽（`timekeeper` 命中 `ime`） | `engine/SafetyGuard.kt` | 已改 / 未验证 |
| F-17 | Home 主按钮文案与行为不符（「开启观察」实为开启自动化） | `ui/home/HomeScreen.kt` | 已改 / 未验证 |
| F-21 | 缺 `values-night`，深色模式首帧闪白 | `res/values-night/themes.xml`（新增） | 已改 / 未验证 |
| F-22 | `dynamicColor` 是死参数 | `ui/theme/Theme.kt` | 已改 / 未验证 |
| F-28 | `proguard-rules.pro` 零说明，R8 行为无记录 | `app/proguard-rules.pro` | 已改 / 已核对 |
| F-29 | `gradle.properties` 未显式声明 AndroidX | `gradle.properties` | 已改 / 未验证 |
| F-30 | 默认冷却三处不一致（1500 / 1200 / 1200） | `model/SkipRule.kt`、`data/RuleRepository.kt`、`data/RuleImportManager.kt`、`ui/rules/SimpleRuleScreen.kt` | 已改 / 未验证 |
| F-33 | `getPackageInfo(packageName, 0)` 弃用 API 与仓库写法不一致 | `MainActivity.kt` | 已改 / 未验证 |
| F-34 | 图标预览所有方案共用同一 wordmark，只换底色 | `ui/icons/IconAppearanceScreen.kt` | 已改 / 未验证 |

### 1.4 本次明确未做（已与所有者确认或按风险取舍）

| 项 | 原因 |
|---|---|
| F-18 状态不可保存（旋转回落首页） | 所有者选择撤出：需要重构 `MainActivity` 状态作用域，且本环境无法编译验证 |
| F-19 Switch 无障碍语义 | 所有者选择只保留低风险 UI 项 |
| F-20 嵌套可点击 | 同上（会改变现有交互布局，无法截图回归） |
| F-23 `InfoCard` 去重 | 同上（会改变部分屏幕圆角/内边距外观） |
| F-24 的测试步骤实际生效 | 本次不碰测试源集；CI 中该步骤为 NO-SOURCE 占位 |
| 重建 17 个被删测试类 | 所有者本次不碰测试 |
| `getAppPolicies` / `getRules` 内存缓存 | 需失效通知机制，属独立改造 |
| 全量 i18n 字符串外提 | 数百条硬编码中文，无法在本环境做视觉回归 |
| 迁移到 `PackageInstaller` 会话安装 | 独立改造 + 需大量真机验证 |
| 签名证书轮换支持 | 无净安全收益（`signingCertificateHistory` 会接受可能已撤销的旧证书），需产品侧钉住信任清单 |
| 受保护包关键词整体删除 | 未删除任何关键词（此条在审查后已修正，见 §4.1 D-1——曾有 `"citic"` 被误删并已恢复） |

---

## 2. 关键改动说明（按子系统）

### 2.1 数据与状态层

**F-01 黑名单字段丢失**
根因：`SettingsRepository.saveBlacklistPackages` 用 `AppPolicy.fromLegacyBlacklist` **整条重建**黑名单内每个包，而该工厂硬编码 `customRulesEnabled = true`。因此任意应用上切换黑名单，都会把其它已拉黑应用上用户关闭的自定义规则改回启用。

改法：
- `AppPolicy.kt` 新增顶层扩展 `internal fun AppPolicy.asBlacklisted()`，只翻转 `defaultRuleEnabled` 与 `migratedFromBlacklist`，保留 `customRulesEnabled` 与 `updatedAt`。
- `saveBlacklistPackages` 改为「既有策略沿用 + 只对黑名单目标翻转标志」，无既有策略的包才走 `fromLegacyBlacklist`（首次拉黑语义不变）。
- 未改动 `saveAppPolicies` 从策略反推 `KEY_BLACKLIST` 的逻辑，因此两个存储位置仍同源。

需要澄清的一点：审计曾报告此项为「双 source of truth 分叉」，该判断不成立——`saveAppPolicies` 写 JSON 后会重新推导并覆盖 `KEY_BLACKLIST`。真实缺陷只有字段丢失这一条。

**F-02 Room 迁移策略**
不新增迁移（当前只有 v1，无迁移可写），改为显式声明失败模式：引入 `MIGRATIONS: Array<Migration>` 与 `addMigrations(*MIGRATIONS)`，并以注释固化「bump version 必须同时提交 `<version>.json` 与对应 Migration」。**刻意不加** `fallbackToDestructiveMigration()`——那会静默清空用户日志历史。

### 2.2 服务运行期

**F-03 节流推进**
根因：`markTreeWalk()` 唯一调用点在节点扫描之后，而总开关/自身包/受保护包三个分支都在它之前 return；`lastTreeWalkElapsedRealtime` 仅在前台切换时归零，节流判定又要求该值 `> 0`。

改法：新增 `markTreeWorkIfContentRead(eventType)`，在自身包分支与受保护包分支（后者确实读了树：`containsOwnAppLabel`）return 前调用，仅对 `TYPE_WINDOW_CONTENT_CHANGED` 生效。

**F-04 安全模式竞态**
改法：
- 把 `abortPendingIfAutomationPaused` 重写为 `abortPendingIfAutomationBlocked`，统一判定「总开关关闭 **或** 安全模式开启」，并写入 `automation_paused` / `click_skipped_by_safety_mode` 区分原因、记录 `ClickSkippedBySafetyMode` 日志。三个延迟回调入口（语义重定位、坐标兜底、手势兜底）全部改用它。
- 服务侧新增 `isOpeningAdRecoveryAllowed()`，把「总开关开 且 非安全模式」作为一次性判定，替换 4 处 `OpeningAdRecoveryGate.canRun(masterEnabled = …)` 的入参。这样既覆盖已排期的重试，又保证已排期的重试不会在用户切到观察模式后复活。

**F-05 恢复终止过粗**
`scanMissStage` 原先 `else -> SkippedBySafety`，而 `SkippedBySafety` 会 `terminateOpeningAdRecovery()` 永久关闭本会话的重试与重扫。现在把 `ambiguous_candidate_large_bounds`、`generic_skip_context_missing`、`precise_area_mismatch` 三个「当前帧未匹配但可恢复」的原因归为 `NoCandidateFound`。

安全性论证：`ScanReport.bestMatch` 只在 `MatchResult` 非空时存在，而 `RuleMatcher` 要求 `passesMinScore && clickSelection != null && !blockedByHighRiskPolicy` 才构造 `MatchResult`。因此被下放到「可恢复」的原因**绝不携带可点击的 match**，本次放宽不可能扩大点击范围。`standalone_skip_*`（尤其 `standalone_skip_unsafe_ancestor`）刻意未进白名单。

**影响面修正（复核后补充）**：这三个原因**不在** `OpeningAdRetryPolicy.retryableReasons` 白名单内，所以本项改动**只影响重扫（rescan）排期与日志分类，不会触发点击重试**。收益是「8 秒窗口内多扫几次」，而不是「多试几次点击」。

**F-07 防双击**
`verifyActionClick` 原先只要 `candidate_still_present` 就调手势兜底，而 ACTION_CLICK 已返回 true；视图尚未销毁时会向同一坐标再发一次手势。现在仅在 `pending.firstAttempt?.accepted != true`（首次点击未被接受）时才允许手势兜底。
**这是有意取舍**：会降低「ACTION_CLICK 静默无效」场景的兜底成功率。若所有者更看重兜底，可改为「3 秒内同签名未补发过手势」的条件。

**F-09 永久 pending**
`NodeScanner.scan(root ?: return, …)` 会在 root 为空时裸返回并留下无回调的 `pendingClick`，使服务此后永远走 pending 快路径。改为显式 `finishPendingClick(..., reason = "root_window_null_before_click")` 收尾。

### 2.3 引擎与评分

**F-12 诊断字段语义**
`defaultRuleAreaAllowed` 原先被硬编码为 `true` 且从无改写点，诊断日志因此失去判别力。现在由 `areaMatchesRule = (rule.area == Any || area == rule.area)` 提供真实取值，并与评分里的区域加分项共用同一判定。字段名与 `ClickLogCodec` 的 JSON key 均未改动，**不涉及 Room schema 或日志存储格式变更**。

**F-13 坐标手势面积上限**
`coordinateFallbackGestureTargetBlockReason` 补入与语义路径相同的 `MAX_CLICK_TARGET_SCREEN_RATIO`（35%）判定，复用既有常量与 `computeAreaRatio()`。
**审查后更正**：该检查在现有调用路径下**不可达**——能到达这里的目标都来自 `CurrentTargetRevalidator.searchAtPoint`，而它只返回带 `relaxedSelection` 的节点，`relaxedSelection` 又要求 `isSafeBounds`（面积 ≤35%）。因此它是**防御性代码而非有效修复**，注释已改为如实表述；验收项 V-13 的预期日志不可观测。

**F-14 受保护包匹配（经审查重写）**
原问题：`contains` 在全包名上匹配 `ime`/`pay`/`cash` 等短词，使 `com.example.timekeeper` 被当作输入法而整体禁跳。

第一版改法（**已被审查证明无效**）：引入 `trailingSegmentOnlyKeywords`，只在**末两段**内匹配。`com.example.timekeeper` 的末两段是 `example.timekeeper`，仍然包含 `ime` ⇒ 该修复连自己的标题案例都没修好。

**最终改法**：
- `boundaryMatchedKeywords = {ime, pay, cash, cmb, ccb, boc, icbc}` 改为**段边界匹配**（`matchesTokenOnSegmentBoundary`）：某个点分段等于该词、或以它开头、或以它结尾。`timekeeper` 三者皆不满足 ⇒ 不再误伤；`com.tenpay.*`、`com.miui.ime`、`com.icbc.im`、`com.lge.ime` 仍然命中。
- 其余关键词保持全名匹配，保留 `com.sohu.inputmethod.sogou` 这类中段含信号的真实覆盖。
- `matchedProtectedKeyword` 的契约改为**只返回关键词**（不再在 exact/prefix 命中时返回包名或前缀）——这是 `isSystemPackage` 与 `protectedPackageReason` 分类错乱的根因。
- `isSystemPackage` 改为 `matchedProtectedKeyword(pkg) in systemKeywords`，恢复 `com.android.settings`、`com.android.packageinstaller`、`com.coloros.recents` 等的 `system_or_launcher_package` 判定与原因串。
- 新增 `classifyProtectedKeyword`，使 exact/prefix 命中也能拿到具体原因串（此前会一律退化为 `protected_sensitive_package`）。

**关键词未被删除**——但这句话只在修复后成立：审查用 `git diff` 证明我在改写该列表时**误删了 `"citic"`**（详见 §4.1 D-1），已恢复。

### 2.4 自更新

**F-15 下载约束**：新增 `https` 强制 + 主机白名单（`github.com`、`api.github.com`、`objects.githubusercontent.com`、`github-releases.githubusercontent.com`、`release-assets.githubusercontent.com`）；`instanceFollowRedirects` 改为 `false`，改由 `openRedirectCheckedConnection` **手动跟随最多 5 跳并在每一跳重新校验**；下载循环增加 200 MB 上限与 `Content-Length` 一致性校验；失败一律删除半成品文件。`AboutScreen.openReleasePage` 同步限制为 `https` + `github.com`。

**F-16 资产与版本门禁**：资产匹配从「精确两种命名」放宽为「`Skip-` 前缀 + `-release.apk` 后缀且唯一」，避免同时发布两种命名时更新永久失败；`VersionComparator.compare` 区分「不是更新」与「tag 无法解析」，非法 tag 现在渲染为错误态而不是「已是最新版本」。硬门禁仍是 APK `versionCode`。

### 2.5 构建与文档

**F-24 CI**：`.github/workflows/verify.yml`（JDK 21 + Gradle setup + `assembleDebug` + `testDebugUnitTest` 占位 + 版本一致性 + 签名材料未跟踪闸门 + 产物上传）。版本一致性步骤复用 `tools/verify-release.ps1 -CheckVersionOnly`，避免重复实现断言逻辑。
**F-25**：`verify-release.ps1` 新增 `-CheckVersionOnly` 与 `-SkipOriginCheck` 语义（`-CheckVersionOnly` 下完全跳过分支/工作区/origin/APK/SHA256 检查），并保留原有的 `main` 分支与干净工作区前置条件用于真实发布。`-Sha256` 由 `Mandatory` 改为「非 `-CheckVersionOnly` 时必填」。

**F-26/F-37 文档对齐**（逐项）：
- `docs/CODEX_HANDOFF.md`：版本 `1.0.9/17` → `1.0.17/25`；删除不存在的 `.worktrees/` 引用；`keystore.properties`/`AGENTS.md` 的「不存在」更正为存在且未被跟踪；删除与之矛盾的自述；第 0 节标注功能分支已合并；§1 新增「仓库当前没有任何测试源集」条目。
- `AGENT.md`：删除不存在的 `app/src/debug/AndroidManifest.xml`；`testDebugUnitTest` 标注为 NO-SOURCE 并说明不得作为测试通过证据；权限检查命令与发布检查命令同步修正。
- `RULES_GUIDE.md`：新增「导入时实际生效的额外规则」与「导出后再导入的注意点」两节，补齐导入即停用、`apps[].enabled` 无效、`schemaVersion > 3` 拒绝、规模/正则上限、需额外确认的写法清单（`minScore` 60–69、坐标兜底、正则、`area=any`、纯 View ID、短 `contains`、第三方 JSON）。
- `LOG_DIAGNOSTIC_GUIDE.md`：补 `privacy` 与 `logStorage` 两个顶层键；新增「脱敏范围（重要）」说明文本脱敏但保留包名/Activity/View ID 等元数据。
- `docs/superpowers/plans/2026-07-10-*.md`：加时效说明，标明其引用的 3 个测试/夹具路径在当前仓库已不存在（未改动 35 处历史命令本身）。
- `downloads/github-release-notes-1.0.17.md`：新增，并在文末明确标注**为事后补齐、非发布当时生成的 `--notes-file`**。
- `SECURITY.md`：新增「本地调试残留物」一节，说明 `logs/` 可能含第三方界面录屏与无障碍树 dump，并给出清理命令。
- `README.md` / `docs/README.md`：声明 `applicationId` 冻结、测试源集缺失、诊断包元数据保留。

---

## 3. 验收状态

### 3.1 构建级 —— **已实际执行并通过**

执行环境：Windows 的 PowerShell（环境问题已在 §0 说明并修复），`GRADLE_USER_HOME=S:\Android\.gradle`，加 `--offline`（本机无外网，依赖缓存已完整）。

| 检查 | 结果 |
|---|---|
| `:app:assembleDebug` | ✅ **BUILD SUCCESSFUL**（含 `kspDebugKotlin`、`compileDebugKotlin`） |
| `:app:assembleRelease` | ✅ **BUILD SUCCESSFUL** in 2m12s —— 含 `minifyReleaseWithR8`、`optimizeReleaseResources`、`lintVitalRelease`，产出 `app-release.apk` = 2,847,293 字节 |
| `:app:testDebugUnitTest` | ✅ exit 0（NO-SOURCE，仓库无测试源集，符合预期） |
| `git diff --check` | ✅ 无空白错误 |
| `app/schemas` | ✅ 无变化（未改动任何 Room 实体） |
| `uses-permission` | ✅ 恰好 2 条（`INTERNET`、`REQUEST_INSTALL_PACKAGES`） |
| `verify-release.ps1 -Version 1.0.17 -VersionCode 25 -CheckVersionOnly` | ✅ `Version metadata verified: 1.0.17 (25)`，退出码 0 |

编译期仅有 1 条**既有**告警（非本次改动）：`SkipAccessibilityService.kt:1241` 的 `canRetrieveWindowContent` 已弃用。

> 注：`git diff --check` 之外，git 还会在 stderr 打印一批 `CRLF will be replaced by LF` 提示。这是仓库既有状况（源文件本身带 CRLF），不是本次引入的问题。

### 3.2 人工功能验收

| 项 | 场景与操作 | 期望 | 状态 |
|---|---|---|---|
| V-1（F-01） | **先拉黑 A、关闭 A 的自定义规则，再把 B 从黑名单移出**，然后回到 A 详情页 | A 的自定义规则仍为关闭 | 未验证（**验收步骤已修正**：原写法"拉黑 B"根本无法触发该缺陷——UI 里拉黑走 `setAppAssistanceEnabled`→`setAppPolicy`→`saveAppPolicies`，从不调用 `saveBlacklistPackages`；只有"移出黑名单"会走到被测代码。原验收项在未修复的代码上也会通过，属假阴性） |
| V-3（F-03） | 在桌面滑动 10 秒，用 `top`/Perfetto 或 `dumpsys accessibility` 观察主线程占用 | 启动器场景主线程树遍历显著减少 | 未验证（**不能用日志密度判定**：`ClickLogRateLimiter` 在改动前就已把启动器的 `SkippedBySafety` 日志压到 2 秒 1 条，日志看不出差异；可测量的收益是树遍历 CPU） |
| V-4（F-04） | 命中后 100ms 内开启安全模式（或临时把 `STABLE_CLICK_DELAY_MS` 改为 2000 做一次性验证后还原） | 出现 `click_skipped_by_safety_mode`，无 `click_attempted` | 未验证 |
| V-5（F-05） | 造大面积候选场景，8 秒窗口内观察 | 出现多次 `no_candidate_found` 与 `opening_ad_absolute_rescan` | 未验证 |
| V-7（F-07） | 观察点击后的日志序列 | 同一 signature 不再出现 ACTION_CLICK 紧随手势 | 未验证 |
| V-8（F-08） | >500 节点页面 | 失败原因为 `scan_budget_exhausted` | 未验证 |
| V-9（F-09） | 触发 root 获取失败 | 有终态日志，服务不卡在 pending 快路径 | 未验证 |
| V-10（F-10） | 关闭总开关后打开目标 App | 需要**同时开启"调试提示"**才会出现「自动化已暂停」日志条目；否则只能在导出的诊断包里从 `runtimeState.lastFailureReason = automation_paused` 看到 | 未验证（验收条件已修正：该阶段是 `isDebugOnly`） |
| V-12（F-12） | 导出诊断包，看一条区域不匹配的默认规则记录 | `defaultRuleAreaAllowed = false` | 未验证 |
| V-13（F-13） | 坐标规则点位设在大容器中心 | 记录 `coordinate_fallback_target_unsafe` | 未验证 |
| V-14（F-14） | 包名含 `timekeeper` 的普通应用 | 不再被 `safety_guard_blocked`；同时验证 `com.citics.csb.inte`（中信证券）、`com.sohu.inputmethod.sogou`、`com.android.settings`、`com.android.packageinstaller`、`com.miui.home` 仍被拦截 | 未验证（必须包含 `com.citics.*` —— 第一版修复曾漏掉它，见 §4.1 D-1） |
| V-15（F-15） | 真实点一次应用内更新 | 正常 GitHub 更新仍可完成 | 未验证（需网络） |
| V-17（F-17） | 安全模式开、自动化关 | 按钮显示「开启自动化（观察中）」 | 未验证 |
| V-21（F-21） | 系统深色下冷启动 | 首帧不闪白 | 未验证 |
| V-31（F-34） | 图标页切换方案 | 预览图标本身变化，不只换底色 | 未验证 |

### 3.3 明确未验证的原因汇总

- **无编译/构建**：`pwsh` 不可用。
- **无网络**：自更新的正常路径与真实 Release 资产命名无法端到端验证。
- **无设备、无 TalkBack**：运行期行为与无障碍语义无法验证。
- **无测试源集**：本次修复没有自动化回归防护，全部依赖上表的人工验收。

---

### 3.4 已完成的静态结构校验

在无法编译的前提下，本次修复做了一轮**独立的静态结构复核**（由另一个代理执行，逐文件读取 + 跨文件符号解析），结果：

- **未发现任何 Kotlin 语法或结构错误**：无括号不平衡、无被吞掉的函数头、无重复声明、无指向已删除符号的悬空引用、无错误的具名参数。
- 逐项确认：`asBlacklisted` 位于顶层（不在伴生对象内）；`MIGRATIONS`/`addMigrations(*MIGRATIONS)` 与 Room API 匹配；`markTreeWorkIfContentRead`、`isOpeningAdRecoveryAllowed`、`areaMatchesRule`、`abortPendingIfAutomationBlocked`、`matchedProtectedKeyword` 等新符号**项目内各仅一处声明**；4 处 `masterEnabled = isOpeningAdRecoveryAllowed()` 与 `OpeningAdRecoveryGate.canRun` 参数名一致；`UpdateRepository` 顶层函数嵌套关系正确；`UpdateCardBehavior.nextActionFor` 覆盖全部 8 个 `UpdateCheckState` 变体；`MainActivity` 仅有一处 `currentScreen` 与一处 `packageInfo()`，无 `rememberSaveable`/`AppScreenSaver` 残留（F-18 已完全撤出）。
- 复核发现并已修复：`VersionComparator.isNewer` 与新抽出的 `compareParts` 是同一算法的两份拷贝。现已让 `isNewer` 复用 `compareParts`，消除漂移风险。
- **该方法的能力边界**：它替代不了编译器。无法证明改动与"最近编辑"的对应关系、无法覆盖清单外的文件、无法发现类型推断或重载解析层面的错误。

另有一条有用的旁证：`app/build` 中不存在 `AppPolicyKt.class`，说明最后一次成功的 Kotlin 编译**早于**本次改动，因此 `app/build/**` 下的任何产物都**不能**用来验证当前工作区（`test-results/**`、`reports/**` 同理）。同理，`SkipDatabase$Companion.class` 存在，说明 `JournalMode.WRITE_AHEAD_LOGGING` 这个未导入的嵌套类引用在本项目中曾编译成功，视为既有可用写法。

## 4. 独立审查发现与处理

改动完成后做了**对抗式独立审查**（三个代理，各自假设改动是错的）。审查是在无法编译的条件下做的（它们没有 pwsh），随后由本会话用真实构建补上了编译证据。以下逐条给出结论。

### 4.1 已据审查修正

| # | 审查发现 | 严重度 | 处理 |
|---|---|---|---|
| D-1 | `abortPendingIfAutomationBlocked` 在**总开关**关闭时也记 `ClickSkippedBySafetyMode`，而该 stage 被 `LogRepository.isSafetyBlockedHit` 计为"安全阻止" ⇒ 统计被灌水，且日志会同时出现 `safetyModeEnabled=false` 与 `clickSkippedBySafetyMode=true` | 中 | **已修**：按真实原因分别记 `SkippedByDisabledSetting`（总开关关闭）或 `ClickSkippedBySafetyMode`（安全模式），`clickSkippedBySafetyMode` 标志同步 |
| D-5 | F-05 白名单不完整：`standalone_skip_not_top_right` / `candidate_too_large` / `label_not_exact` 与 `precise_view_id_required` / `precise_candidate_evidence_missing` 仍落 `else -> SkippedBySafety` ⇒ 仍会 `terminateOpeningAdRecovery()`。而 `ScoreEvaluator.kt:160` 的 standalone gate **只看标签是否精确、不看位置**，所以内置规则遇到不在右上的精确"跳过"时，首帧就会杀死整个 8 秒窗口——正是我注释里声称要修的失效模式 | 中 | **已修**：抽出 `RECOVERABLE_MISS_REASONS` 集合（8 个原因）。真正的安全原因仍为终态：`standalone_skip_unsafe_ancestor`、`candidate_unsafe`、`no_safe_action_path`、`rule_source_forbidden`、`forbidden`、`blocked_by_safety_policy`、`sensitive_skip_semantic`、`text_input_clear_button`、`activity_scope_mismatch`、`generic_close_without_ad_context` |
| **D-1（最严重）** | **我在改写 `SafetyGuard` 关键词列表时误删了 `"citic"`。** 审查代理用 `git diff` / `git log -S'"citic"'` 证明该词由提交 `df09a42 "fix: prevent default rules from misclicking system UI"` 刻意加入，而 `protectedExactPackages`/`protectedPrefixes` 里只有 `com.citicbank`，覆盖不了 `com.citics.*`。后果：`com.citics.csb.inte`（信e投国际）、`com.citics.cagent`、`com.citics.csb`、`com.citics.ecard` 等中信证券系 App **从受保护变为不受保护** | **高** | **已修**：恢复 `"citic"`，并把列表压成紧凑形式、加注释禁止再截断尾部。这也是一个教训：我随后写的"未删除任何关键词"是**虚假陈述**，已更正（§1.4、§2.3） |
| D-2 | 第一版 F-14 修复**没有修好自己的标题案例**：`com.example.timekeeper` 仍被判为输入法，因为"末两段"是 `example.timekeeper`，仍含 `ime`。同理 `com.time.mobile`、`com.prime.*` | 中 | **已修**：改为段边界匹配（见 §2.3 F-14） |
| D-3 | `isSystemPackage` 判定被**反了方向**：它拿 `matchedProtectedKeyword` 的返回值与关键词字面量比较，但 exact/prefix 命中时返回的是包名或前缀 ⇒ `com.android.settings`、`com.android.packageinstaller`、`com.google.android.packageinstaller`、`com.miui.packageinstaller`、`com.samsung.android.packageinstaller`、`com.android.settings.intelligence`、`com.coloros.recents` 由 true 变 false。且我声称 `com.example.settingshelper` 不再被判为系统包——**该断言也是错的**（它一直是 true，因为 `settings` 是全名匹配词） | 中 | **已修**：`isSystemPackage` 改为 `matchedProtectedKeyword(pkg) in systemKeywords`；报告表述已更正 |
| D-4 | `protectedPackageReason` 的原因串静默改变：`com.android.settings`/安装器系/`com.coloros.recents` 由 `system_or_launcher_package` 退化为 `protected_sensitive_package`；`com.sohu.inputmethod.sogou`、`com.android.inputmethod.latin` 等由 `input_method_package` 退化 | 低 | **已修**：新增 `classifyProtectedKeyword`，exact/prefix 命中也能得到具体原因串。统计不受影响（这些串都不含 "safety"） |
| D-5 | `ScoreEvaluator` 的 `ambiguous_candidate_large_bounds` 与 `no_safe_clickable_target` 两个提前返回分支，对**非内置的精确规则**也填了 `defaultRuleAreaAllowed`，而同一条规则走正常路径时为 `null`（`:317` 有 `if (defaultRule)` 守卫） | 低 | **已修**：两个分支同样加 `if (defaultRule)` 守卫 |
| D-6 | F-13 新增的坐标面积检查是**死代码**（见 §2.3 更正） | 低 | **保留为防御性检查并改正注释**；验收 V-13 已标注不可观测 |
| D-7 | F-10 用 `SkippedByDisabledSetting`（`isDebugOnly = true`），默认设置下**根本不会落日志** ⇒ 我原先写的验收 V-10 不成立 | 中（文档） | **验收条件已修正**（§3.2 V-10）。**是否把它改成非调试类别由你决定**：改了会让"用了没反应"可自查，代价是每次暂停都会写一条日志（受 2 秒限流） |
| D-7 | 报告 §2.5 写了 `verify-release.ps1` 新增"`-SkipOriginCheck` 语义"，**该参数根本不存在** | 低（文档） | **已更正**（见 §4.2） |
| D-6 | 报告称"现已让 `isNewer` 复用 `compareParts`"，但 `isNewer` 已被整个删除 | 低（文档） | **已更正** |
| D-5b | 报告声称放宽资产白名单能"避免同时发布两种命名时更新永久失败" —— 与 `UpdateReleaseParser.kt:21` 的 `size != 1` 硬校验矛盾：同时存在两个匹配资产仍然永久失败 | 低（文档 + 代码注释） | **已更正**：放宽的真实收益只是让匹配器不再与 tag 拼写耦合；"两个资产"仍会硬失败。已同步改写代码注释 |
| D-7b | 报告 §2.5 称 `-CheckVersionOnly`"完全跳过"检查，但占位文案检查（"尚未创建/上传/记录"）仍会执行 | 低（文档） | **已更正** |
| D-3 | F-09 新增的 `activeRoot == null` 分支在当前守卫顺序下**不可达**（`retryPendingClick` 与 `cancelIfUnsafeDelayedClickWindow` 已覆盖 root 为空） | 低 | **保留但改正注释**：明确标注为防御性代码，不再声称它修复了一个可达缺陷 |
| D-4 | F-03 引入的节流时间戳是**全局**的，且在包名解析**之前**被消费 ⇒ 该分支执行后的 300ms 内，来自**任何**包的 content 事件都会被丢弃（含刚切到前台的 App 的首个 content 事件）。改动前启动器分支不标记，故这是新增行为 | 低 | **保留并显式记录**（见 §5 残余风险）：窗口有界、前台切换通常以 `WINDOW_STATE_CHANGED` 到达（不受节流并清除标记）；未做包感知节流以免在热路径上增加开销 |
| F-07 | 确认 `firstAttempt?.accepted` 在 `verifyActionClick` 时必为 true，逻辑正确；但确实移除了"ACTION_CLICK 已接受却无效"的第二次机会 | 取舍 | 维持决定，风险登记在 §5 |
| G | `isOpeningAdRecoveryAllowed()` 纳入安全模式后，已排期的重扫/重试在安全模式下会 `terminateOpeningAdRecovery()`，即**永久关闭当前前台会话的恢复**（重开安全模式不会清除 `terminalSessionKey`）。主流路径下无害：切换安全模式必须打开 Skip 界面，返回目标 App 会刷新前台会话并清除终态标记 | 低 | **保留**：安全模式是点击的硬停止，"停止重扫"是符合预期的；策略不一致（排期处静默跳过、回调处永久终止）已记录 |

### 4.2 报告更正记录

| 原表述 | 更正 |
|---|---|
| §2.5 提到 `-SkipOriginCheck` | **无此参数**。脚本参数只有 `Version`、`VersionCode`、`Sha256`、`ApkPath`、`AllowDirty`、`VerifyGitHubRelease`、`CheckVersionOnly`。`-CheckVersionOnly` 模式下已直接跳过分支/工作区/origin/APK/SHA256 全部前置检查，无需该开关 |
| §2.5 "让 `isNewer` 复用 `compareParts`" | `isNewer` 已**整个删除**（零调用点）。比较算法现只有 `compareParts` 一处 |
| §2.4 与 `UpdateReleaseParser.kt` 注释 "避免同时发布两种命名时更新永久失败" | 该收益**不成立**：`trustedAssets.size != 1` 仍会硬失败。放宽的真实收益是不再让资产名与 tag 拼写耦合（例如 tag 与 versionName 拼写不同时原先会失败） |
| §2.5 "`-CheckVersionOnly` 下完全跳过检查" | 占位文案断言仍执行（这是好事，但表述不准确） |
| §5 提交建议含 `git add … downloads/` | `downloads/` 被 `.gitignore:21` 忽略，该命令会以非零退出。`downloads/github-release-notes-1.0.17.md` **无法被提交**，它只是本地发布产物目录中的文件 |
| §0/§3 各处"未验证" | 构建级验证**已实际执行并通过**（§3.1） |

### 4.3 审查未能判定的项（需要真机/网络）

- 真实 GitHub Release 资产的 CDN 主机是否仍在我设的 5 条白名单内（无网络）。**这是我登记的最高优先级残余风险**。
- `github.com` 返回的 302 `Location` 是否恒为绝对地址（若是相对地址，我的手动重定向会报"下载地址无效"）。
- 新建前台 App 的首个事件是否可能是 content 事件而非 `WINDOW_STATE_CHANGED`（决定 D-4 的实际影响）。
- 各广告 SDK 中"ACTION_CLICK 被接受但无效"的出现频率（决定 F-07 的取舍是否划算）。
- `tools/verify-release.ps1` 的 `String.Contains(string, StringComparison)` 重载**只在 PowerShell 7+ / .NET Core 存在**，Windows PowerShell 5.1 会抛"找不到重载"。该写法在改动前就已存在（`:42` 的 `Assert-Contains`），但脚本此前从未被真实执行过。请用 **pwsh** 而不是 `powershell.exe` 运行它。

## 5. 残余风险

按严重度排序：

1. **修复未经编译验证。** 所有改动都是静态编写，可能存在编译错误。**这是当前最大风险**，必须在合并前先跑 `assembleDebug`。
2. **零自动化回归防护。** 17 个测试类（286 个用例）的源码仍不在仓库内；本次每个修复点都没有对应测试。
3. **F-07 的功能取舍**：手势兜底被收窄，可能降低部分「ACTION_CLICK 静默无效」场景的成功率。若实测发现漏跳变多，应改成「3 秒内同签名未补发过手势」的折中条件。
4. **F-15 无法端到端验证**：主机白名单若遗漏 GitHub 未来的资产域名，会导致更新失败（表现为「更新下载地址不受信任」）。若真实更新失败，优先检查该白名单。
5. **F-14 的匹配器改动已由两条互相独立的证据链核对，但仍未做设备回归**。审查代理用 `git show HEAD:` 取到改动前的真实实现，重写两版匹配器并对 74 个真实设备包名 + 4380 个"像包名"的字符串做差集：`ime/pay/cash/cmb/ccb/boc/icbc` 这 7 个词**没有一个**造成真实应用失保护，翻掉的都是类名/非包名（正是要修的误报）。但证据仍非真机：请用一份常用应用清单实测一次，**务必包含 `com.citics.*`**（第一版修复曾真的漏掉它，见 §4.1 D-1）。
6. **F-05 依赖「安全拒绝不产生 match」这一不变量**。该不变量在本次核对中成立，但它跨越 `ScoreEvaluator` → `RuleMatcher` → `ScanReport` 三个环节，未来若被破坏，本改动会随之失效。**这正是最需要一条回归测试的地方**。
7. **`logs/` 隐私材料仍在磁盘上**（按所有者决定未删除），误打包/误分享风险持续存在。
8. **`origin/main` 仍落后 HEAD 8 个 release**（`.git/packed-refs` 快照过期），下一次真实发布前必须 `git fetch` + `git push`，否则 `verify-release.ps1` 的 origin 检查会失败。
9. **未修复项**：F-18/F-19/F-20/F-23 与 §1.4 列出的其它项仍然存在，审计报告中的对应结论依然有效。

---

## 5. 提交建议

分支由仓库所有者创建后，建议按子系统分段提交，便于 review 与回滚：

```powershell
cd S:\Android\AndroidStudioProjects\Skip
git switch -c fix/audit-p0-p1-p2-20260928 main

git add app/src/main/java/com/example/skip/model/AppPolicy.kt app/src/main/java/com/example/skip/data/SettingsRepository.kt
git commit -m "fix: preserve per-app custom rule switch when blacklist changes"

git add app/src/main/java/com/example/skip/data/db/SkipDatabase.kt
git commit -m "fix: declare explicit Room migration policy"

git add app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt app/src/main/java/com/example/skip/service/PendingClickCoordinator.kt app/src/main/java/com/example/skip/data/LogRepository.kt
git commit -m "fix: throttle early-exit events, close the safety-mode race and make hit stats exclusive"

git add app/src/main/java/com/example/skip/engine/
git commit -m "fix: correct diagnostic field, coordinate bounds limit, scanner budget accounting and protected-package matching"

git add app/src/main/java/com/example/skip/ui/ app/src/main/java/com/example/skip/MainActivity.kt app/src/main/res/values-night/
git commit -m "fix: harden in-app update and align UI behaviour with copy"

git add .github/ tools/ .gitignore gradle.properties app/proguard-rules.pro
git commit -m "chore: add verification workflow and explicit build configuration"

git add README.md docs/ RULES_GUIDE.md LOG_DIAGNOSTIC_GUIDE.md AGENT.md SECURITY.md downloads/
git commit -m "docs: align contracts, versions and privacy guidance with the code"

git diff --check
git status --short
```

**推送、合并与发布均需所有者明确授权。**

---

## 5.1 发布流程阻断项（必须由你决定，我没有代为修改版本号）

本次修复**没有修改** `versionCode`（仍为 25）与 `versionName`（仍为 `1.0.17`）。如果就按现状打包发布，会有两个确定的问题：

1. **已安装 1.0.17 的用户永远收不到这些修复。** `ApkUpdateInstaller.validateDownloadedApk` 要求新 APK 的 `versionCode` **严格大于**当前值；`versionCode` 相等会被拒（并删除已下载文件）。
2. **`verify-release.ps1` 的非 `-CheckVersionOnly` 路径会失败。** 重新构建后 APK 已变，而 `README.md:13`、`docs/README.md:10`、`RELEASE_NOTES.md:27` 里记录的 `233C69…B53C1` 是旧二进制的摘要；脚本会先因文件名/SHA256 校验不通过而中止。

因此发布前需要（**均未执行**）：

- 提升 `app/build.gradle.kts` 的 `versionCode` / `versionName`；
- 新增对应的 `RELEASE_NOTES.md` 章节（现有 1.0.17 章节的文字不包含本次任何修复）；
- 重新构建、计算新 APK 的 SHA256，并同步 `README.md`、`docs/README.md`、`RELEASE_NOTES.md`、`downloads/`；
- 在具备 `keystore.properties` 的环境用 **pwsh**（不是 `powershell.exe`，见 §4.3）运行完整 `verify-release.ps1`。

## 6. 与审计报告的差异（更正记录）
| 审计结论 | 更正 |
|---|---|
| 「黑名单状态存在双 source of truth 易分叉」 | 不成立。`saveAppPolicies` 会从策略重新推导并覆盖 `KEY_BLACKLIST`，两处始终同源。真实缺陷只有 `fromLegacyBlacklist` 丢失 `customRulesEnabled`。 |
| 「测试文件未被 git 跟踪」（据 `.git/index` 二进制探测） | **取证方法不可信**：同一路径搜 `kotlin` 同样返回 0 命中，说明 ripgrep 因目标为二进制而跳过该文件，负向结果无判别力。该结论待 `git ls-files 'app/src/test/**'` 与 `git log --all --diff-filter=D -- 'app/src/test/**'` 执行后确认。 |
| 「1.0.17 = 237 tests」（据 `RELEASE_NOTES.md:138`） | 最新一次 JVM 测试产物为 **286 tests / 0 failures**（`app/build/reports/tests/testDebugUnitTest/index.html`，Gradle 9.4.1，生成于 2026-09-28）。237 是更早一次运行的记录。两者都不能证明当前树可复现该结果。 |
| 「update API 版本头 2026-03-10 可能是伪造值」 | 不是伪造值，且与安全问题无关。 |

---

## 7. 审查后续修复（第二轮）

第一轮修复完成后做了对抗式审查，暴露了发布工具链缺陷、两处同类功能缺陷，以及**我在第一轮引入的一个运行期崩溃**。本节记录第二轮的根因、改法与实测结果。

### 7.1 环境说明（影响后续所有验证）

`pwsh` 已修复：根因是 `S:\Android\AndroidStudioProjects\Skip` 缺少 **`WRITE_OWNER`** 权限（`WRITE_DAC` 有、`WRITE_OWNER` 没有），DSH 因此无法为该工作区 provisioning 写授权。现已补齐并校验通过。

> 这纠正了本报告 §0 的一条早期判断——"本环境无法编译、git 不可用、需由仓库所有者代为执行"在修复后不成立。**后续验证均由本会话真实执行**。

### 7.2 第二轮修复项

| ID | 问题 | 严重度 | 改法 | 状态 |
|---|---|---|---|---|
| **W-0** | **我在第一轮把 `whitespaceRegex` 静态字段声明在其调用者之后**（`HighRiskClickPolicy`），Kotlin 对象按声明顺序初始化 ⇒ `normalizedBlockedTerms` 计算时该字段仍是 `null` ⇒ **首次触碰该类即抛 `ExceptionInInitializerError`**。`SafetyGuard` 只是连锁失败（它引用 `HighRiskClickPolicy.blockedTerms`）。编译期完全看不出，只有 JVM 单元测试能发现 | **高** | 把字段声明移到调用点之前，两端都加注释说明顺序约束 | 已修 / 已由测试锁定 |
| W-1 | `tools/verify-release.ps1` 在 Windows PowerShell 5.1 下**连解析都失败**：文件是 UTF-8 无 BOM 且含中文，5.1 按 ANSI 读取并吞掉字符串结束引号。另有 `String.Contains(String, StringComparison)` 重载在 .NET Framework 不存在 | 高（发布工具链不可用） | ① 写入 UTF-8 BOM；② 改用 `IndexOf(..., StringComparison.Ordinal)`；③ 用 `try/catch + exit 0/1` 让退出码确定（原先只写终止错误，CI 可能判成功） | 已修 / 已双版本实测 |
| W-2 | 仓库无测试源集，`:app:testDebugUnitTest` 是 NO-SOURCE 占位 | 高（无回归防护） | 新建 `app/src/test/**`，引入 JUnit 4，落地 7 个测试类共 39 个用例 | 已修 / 39 通过 |
| W-3 | JSON 规则导入**整条替换** `AppPolicy`：导入条目省略 `customRulesEnabled` 时会回落为 `true`，静默重开该应用的自定义规则——与第一轮修的 F-01 同类 | 中 | 新增 `SettingsRepository.mergeImportedPolicy`，导入只改"默认规则"开关，保留 `customRulesEnabled` 与 `migratedFromBlacklist`；导入预览增加一条提示 | 已修 / 已由测试锁定 |
| W-4 | `CoordinateFallbackMatcher` 的锚点规范化在坐标兜底热路径上每次调用都编译 `"\\s+".toRegex()`（`RuleImportRiskPolicy` 同） | 低（性能） | 两处提为静态 `Regex` 字段 | 已修（全仓 `.toRegex()` 归零） |
| W-5 | `NodeScanner` 的 `while (queue.isNotEmpty() && visitedCount < MAX_VISITED_NODES)` 第二个条件不可达（`canEnqueueChild` 已把 `visited + queued` 限制在 500 以内） | 低（可读性） | 去掉死条件，并在 `NodeScanBudget.MAX_VISITED_NODES` 上注明"上界由 `canEnqueueChild` 保证" | 已修 |
| W-6 | `ScanMissClassifier` 的顺序缺陷：原先 `candidateCount == 0` 判断在安全原因之前，安全拒绝常伴随 `candidateCount == 0`，会被误记为"暂无候选" | 低（诊断） | 抽出纯函数分类器，安全原因优先判定 | 已修 / 已由测试锁定 |

### 7.3 保护匹配器的最终形态与实测差分

第一轮把 `ime/pay/cash/cmb/ccb/boc/icbc` 一起收窄，第二轮审查证明这**过度收窄**：`endsWith` 对 `pay` 有用（`tenpay`、`jdpaysdk`），但对 `ime` 有害（`time`、`prime` 都以 `ime` 结尾）。最终只保留一处最小改动：

- **只有 `ime` 收窄**为"整个点分段相等"（覆盖 `com.lge.ime`、`com.sogou.ime`、`com.baidu.ime.android`）。
- 其余财务短词（`pay`/`cash`/`cmb`/`ccb`/`boc`/`icbc`/`bank`/`wallet`/`login`/`settings`）**保持改动前的全名匹配** —— 它们在词内承载真实信号（`tenpay`、`jdpaysdk`、`paybyphone`、`mycashier`），而常见包名词不会偶然包含它们。

**实测差分**（把改动前算法从 `git show HEAD:` 取出后在本机重实现，对真实包名逐条比较）：

| 结果 | 内容 |
|---|---|
| 丢失保护（真实包名） | 仅 3 个，且都是要消除的误报：`com.example.timekeeper`、`com.prime.video`、`com.time.mobile` |
| 新增保护 | 0 |
| 恢复的保护 | `com.citics.*`（第一轮被误删的 `citic` 关键词已恢复） |
| 已知取舍 | 含 `settings` 的包名（如 `com.example.settingshelper`）仍被视为系统界面 —— 这是**改动前就有的既有行为**，第二轮未改动；我曾错误声称它被修复，见 §7.4 |

### 7.4 第二轮更正记录

| 原表述 | 更正 |
|---|---|
| 第一轮报告称"未删除任何关键词" | **该陈述当时是假的**：我在改写 `SafetyGuard` 关键词列表时误删了 `"citic"`，导致 `com.citics.*` 中信证券系 App 失去保护。已恢复，并被 `SafetyGuardMatchingTest` 锁定 |
| 第一轮报告称 `isSystemPackage` 修复使 `com.example.settingshelper` 不再被判为系统包 | **方向说反了且结论错误**：`settings` 一直是全名匹配，该包始终为 true（改动前也是）。真正被改坏的是 `com.android.settings` 等 7 个系统包，第二轮已修正 |
| 第一轮报告称 F-13（坐标面积上限）是有效修复 | 审查证明该检查**在当前调用路径下不可达**（上游 `relaxedSelection` 已保证面积 ≤35%）。现保留为防御性代码并如实标注，验收项 V-13 标注为不可观测 |
| 第一轮报告 §2.5 提到 `verify-release.ps1` 的 `-SkipOriginCheck` | **无此参数**。`-CheckVersionOnly` 模式下已直接跳过分支/工作区/origin/APK/SHA256 检查 |
| 第一轮报告称"`isNewer` 改为复用 `compareParts`" | `isNewer` 已整个删除（零调用点）；比较逻辑只保留 `compareParts` 一处 |

### 7.5 第二轮验证结果（全部真实执行）

| 检查 | 结果 |
|---|---|
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL |
| `:app:assembleRelease`（R8 + 资源压缩 + lintVital） | ✅ BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | ✅ **39 tests, 0 failures**（不再是 NO-SOURCE） |
| `verify-release.ps1 -CheckVersionOnly`（**pwsh 7**） | ✅ exit 0 |
| 同上（**Windows PowerShell 5.1**） | ✅ exit 0 |
| 负向用例（错误版本号）双版本 | ✅ 均 exit 1 |
| 缺 `-Sha256` 的完整模式 | ✅ exit 1 |
| `git diff --check` | ✅ 无输出 |
| `app/schemas` | ✅ 无变化 |
| `uses-permission` | ✅ 恰好 2 条 |

测试类与用例数：`ClickLogHitClassificationTest`(5)、`ImportedPolicyMergeTest`(5)、`NodeScanBudgetTest`(7)、`SafetyGuardMatchingTest`(6)、`ScanMissClassifierTest`(6)、`AppPolicyMergeTest`(5)、`VersionComparatorTest`(5)。

### 7.6 仍需真机/网络验证（本会话无法完成）

坐标兜底与自更新的端到端行为、启动器节流的 CPU 收益、F-07 取舍的现场效果、`ACTION_CLICK` 被接受但无效的实际频率、GitHub 资产 CDN 主机是否落在自更新的主机白名单内、TalkBack 下的无障碍语义（本轮未改 UI 无障碍）。

