# Skip 项目 Codex 接手文档

> 生成时间：2026-07-12 16:16（Asia/Shanghai）  
> 主仓库：`S:\Android\AndroidStudioProjects\Skip`  
> 当前功能工作树：`S:\Android\AndroidStudioProjects\Skip\.worktrees\safe-skip-candidate-resolution`

## 0. 接手结论

当前工作不是从 `main` 直接继续开发。受限“纯跳过 / skip”功能及三项 P1 安全加固已经提交在独立分支 `codex/safe-skip-candidate-resolution`，但尚未完成设备仪器测试、文档同步和合并。

最重要的合并门槛是：连接 Android 设备或启动模拟器，运行 `GeminiRegressionInstrumentedTest`。当前 `adb devices -l` 没有列出设备，因此不得把功能分支合并到 `main`，也不得声称真实无障碍树验证完成。

## 1. 项目简介与技术栈

Skip 是本地运行的 Android 开屏页面辅助工具。用户主动开启无障碍服务后，它根据内置规则、自定义规则或导入规则扫描无障碍节点，辅助点击低风险的“跳过”“关闭”类控件；支付、授权、登录、安装、删除、转账等高风险语义由安全策略阻止。

主要技术信息：

- 单模块 Android 应用：`:app`。
- 包名/namespace：`com.example.skip`。
- 当前版本：`1.0.9`，`versionCode=17`。
- Kotlin `2.2.10`，Jetpack Compose、Material 3。
- Android Gradle Plugin `9.2.0`，Gradle Wrapper `9.4.1`。
- `compileSdk=36.1`、`targetSdk=36`、`minSdk=28`。
- Java/Kotlin 编译目标：Java 11；Gradle daemon toolchain 配置为 JDK 21。
- 测试：JUnit 4、AndroidX Test、Espresso、Compose UI Test。
- 数据主要保存在本地 SharedPreferences/JSON；项目没有后端、数据库服务或生产部署链路。
- 无障碍服务声明见 `app/src/main/AndroidManifest.xml` 和 `app/src/main/res/xml/accessibility_service_config.xml`，允许读取窗口内容、报告 View ID 和执行手势。

## 2. 当前任务与目标

当前任务是提高默认规则对开屏纯“跳过 / skip”按钮的命中率，同时保持安全边界。目标条件必须同时满足：

- 仅内置规则；应用进入前台不超过 8 秒。
- 候选位于右上，面积占屏不超过 2%。
- 所有非空 `text` / `contentDescription` 都严格等于 `跳过` 或忽略大小写的 `skip`。
- 两层内存在安全可点击动作节点。
- 候选到动作节点的完整链路不能包含可编辑、密码、`EditText`、`ACTION_SET_TEXT`、禁用或不可见节点。
- 受保护应用、敏感页面和活动输入状态继续阻止点击。
- 优先执行 `ACTION_CLICK`；手势兜底前必须基于当前节点树重新执行完整授权判断。
- 普通 `关闭 / close / ×` 不获得这条放宽路径。

本轮原定收口步骤是：同步设计/实施文档 → 完整验证 → 设备仪器测试 → 快进合并 `main` → 合并后复验和清理工作树。用户随后要求先生成本接手文档，因此上述收口尚未继续。

## 3. 状态分层

### 3.1 已完成并有代码证据

以下内容已提交在 `codex/safe-skip-candidate-resolution`，当前 HEAD 为 `8279037ee6cf9f1f0f74b2bae60a7b4135fa2736`：

1. **严格纯标签判断**
   - `DefaultStandaloneSkipPolicy.isStandaloneSkipLabel` 要求至少一个非空标签，并对所有非空标签执行严格判断。
   - 冲突示例 `text="关闭"`、`contentDescription="跳过"` 返回 `standalone_skip_label_not_exact`。
   - 证据：`app/src/main/java/com/example/skip/engine/DefaultStandaloneSkipPolicy.kt:29-45`。

2. **完整动作路径安全检查**
   - `ResolvedActionPath` 已增加 `hasUnsafeNode`。
   - `ClickExecutor` 从候选到最大第四层父节点收集危险节点深度，并在选定动作路径内拒绝危险节点。
   - 检查项包括 `isEditable`、密码、`EditText`、`ACTION_SET_TEXT`、禁用和不可见。
   - 证据：`app/src/main/java/com/example/skip/engine/ClickExecutor.kt:30-36,342-382,502-516`。

3. **手势前重新授权**
   - `CoordinateFallbackTargetSnapshot` 已携带 `actionParentDepth` 和 `hasUnsafeActionNode`。
   - `runGestureFallback` 先合并候选边界与动作节点身份，再执行当前节点复验。
   - 若 `standaloneSkipAllowed=true`，仍会重新计算当前前台时间、区域、面积、动作深度、链路安全和标签，并调用 `StandaloneSkipGestureRevalidationPolicy`。
   - 证据：`app/src/main/java/com/example/skip/engine/CoordinateFallbackMatcher.kt:535-541`、`app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt:1073-1126`。

4. **父容器动作身份与小候选边界分离**
   - `ClickExecutor.targetWithActionIdentity` 保留子节点小边界，并在子节点缺少文字/描述/View ID 时补入动作父节点身份。
   - 手势仍使用重新验证后的候选中心，不使用大父容器中心。

5. **授权状态和日志兼容**
   - `standaloneSkipAllowed` 已贯穿 `MatchResult`、`ClickMatchSnapshot`、`PendingClick`、`ClickLog` 和 JSON 持久化。
   - 旧日志缺失该字段时由 `optBoolean` 默认恢复为 `false`。
   - `LOG_DIAGNOSTIC_GUIDE.md` 已记录所有 `standalone_skip_*` 原因。

6. **提交记录**
   - `51bc02d feat: add guarded standalone skip policy`
   - `820eb37 fix: enforce exact standalone skip labels`
   - `f305dfa feat: resolve safe standalone skip candidates`
   - `99bc515 fix: resolve coordinate targets through safe action paths`
   - `4c604a5 feat: audit guarded standalone skip clicks`
   - `ef22467 docs: explain guarded standalone skip diagnostics`
   - `2bfda1e fix: revalidate guarded skip gesture targets`
   - `6c65d3f fix: tighten guarded skip identity checks`
   - `8279037 fix: preserve action identity during gesture revalidation`

### 3.2 已实现，但尚未完成最终验证

- `GeminiRegressionInstrumentedTest` 中已有真实无障碍树夹具，覆盖小文字子节点、可点击父容器、可编辑中间链路和坐标身份继承；测试代码已编译打包，但当前没有设备，尚未执行。
- 2026-07-11 18:33 的最新磁盘测试报告记录了三项 P1 的 7 个定向 JVM 测试：`7 tests / 0 failures / 0 errors / 0 skipped`。
- 本 Session 早期在同一 HEAD 上真实执行过完整 JVM 测试并得到 `183/183` 通过，也成功执行过 `assembleDebug packageDebugAndroidTest`；但后续定向运行已经覆盖 XML 报告，因此新 Session 必须重新跑全量命令后才能再次声称完整通过。
- 当前构建产物存在：
  - `app/build/outputs/apk/debug/app-debug.apk`，31,587,378 bytes，时间 `2026-07-11 17:58:45 +08:00`。
  - `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`，2,463,269 bytes，时间 `2026-07-11 17:58:47 +08:00`。
- 以上 APK 只能证明打包曾成功，不能替代设备上的仪器测试或真实应用验证。

### 3.3 未完成

按优先级排列：

1. **最高优先级／合并阻塞：设备仪器测试**
   - 当前 `adb devices -l` 仅显示标题，没有设备或模拟器。
   - 必须运行 `GeminiRegressionInstrumentedTest`，重点确认 `standaloneSkipInsideEditableActionPathIsRejected`、父容器点击和坐标节点夹具。

2. **高优先级：同步过时设计和实施文档**
   - `docs/superpowers/plans/2026-07-10-safe-skip-candidate-resolution.md` 仍保留旧 `.any` 标签逻辑、缺少 `hasUnsafeNode`，并只写了通过 `CurrentTargetRevalidator` 后进入手势。
   - 当前代码已经更严格，文档必须改为“所有非空标签严格匹配、完整动作链安全、手势前重跑完整策略”。
   - `docs/superpowers/specs/2026-07-10-safe-skip-candidate-resolution-design.md` 也应补充上述三个 P1 验收条件。

3. **高优先级：重新执行完整验证**
   - 完整 JVM 测试。
   - Debug APK 和 AndroidTest APK 重新打包。
   - `git diff --check main..HEAD`。

4. **设备通过后：提交文档同步并快进合并**
   - 文档提交建议：`docs: align guarded skip plan with safety hardening`。
   - 重新确认 `main...codex/safe-skip-candidate-resolution` 为 `0 <N>` 单向领先，再执行 `git merge --ff-only`。
   - 合并后必须在 `main` 重跑 JVM 测试和打包。

5. **最后：清理工作树和本地功能分支**
   - 只能在合并及合并后验证通过后执行。
   - 从主仓库目录移除 `.worktrees/safe-skip-candidate-resolution`，执行 `git worktree prune`，再删除本地功能分支。

## 4. 当前 Git 状态

### 主工作区

- 路径：`S:\Android\AndroidStudioProjects\Skip`
- 分支：`main`
- HEAD：`b7ac32b8ee0af391433a2f8f0df2b8b580bf4075`
- 上游：`origin/main`
- 本地相对上游：ahead 3。
- 本文档生成前状态：clean。
- 本文档生成后预期状态：`?? docs/CODEX_HANDOFF.md`；这是本轮唯一允许的未跟踪文件，不要误删，也不要把它混入功能分支提交。

### 功能工作树

- 路径：`S:\Android\AndroidStudioProjects\Skip\.worktrees\safe-skip-candidate-resolution`
- 分支：`codex/safe-skip-candidate-resolution`
- HEAD：`8279037ee6cf9f1f0f74b2bae60a7b4135fa2736`
- `git status --porcelain=v1`：无输出，工作树干净。
- 相对 `main`：`0 9`，即没有落后、领先 9 个提交。
- 相对 `main` 的变更：16 个文件，约 `1175 insertions / 103 deletions`。
- 未提交文件：无。
- 未跟踪文件：无。

### 远端和合并注意事项

- 远端：`origin = https://github.com/mirad-tech/Skip.git`。
- 本轮明确禁止 push 和创建 PR。
- `main` 已领先 `origin/main` 3 个文档/忽略配置提交；不要先执行不加判断的 `git pull` 或重置。
- 功能分支从当前 `main` 的 `b7ac32b` 分出，因此在主分支无新提交时可快进合并。

## 5. 关键文件、接口和调用链

### 应用入口和设置

- `app/src/main/java/com/example/skip/MainActivity.kt`：Compose 应用入口、首次披露、无障碍设置跳转、应用/规则/日志页面导航。
- `app/src/main/java/com/example/skip/AppNavigation.kt`：页面状态和返回关系。
- `app/src/main/java/com/example/skip/data/RuleRepository.kt`：默认关键词、View ID、默认规则配置和本地规则存储。
- `app/src/main/java/com/example/skip/data/SettingsRepository.kt`：总开关、安全模式、披露确认等本地设置。

### 当前核心接口

- `DefaultStandaloneSkipPolicy.isStandaloneSkipLabel(text, contentDescription): Boolean`
- `DefaultStandaloneSkipPolicy.evaluate(context): DefaultStandaloneSkipDecision`
- `ClickExecutor.resolveCandidate(node): ClickCandidateResolution`
- `ClickCandidateResolution.actionPathFor(selection): ResolvedActionPath`
- `ClickExecutor.targetWithActionIdentity(candidate, actionTarget): ClickTargetInfo`
- `CurrentTargetRevalidator.revalidateAtPoint(...)`
- `StandaloneSkipGestureRevalidationPolicy.evaluate(...)`

### 默认规则主调用链

```text
SkipAccessibilityService.onAccessibilityEvent
  -> RulePlanProvider.plan
  -> NodeScanner.scan
  -> RuleMatcher.evaluate
  -> ClickExecutor.resolveCandidate
  -> ScoreEvaluator.evaluate
  -> MatchResult / ClickMatchSnapshot / PendingClick
  -> SkipAccessibilityService.startStableClick
  -> relocateAndClick（重新扫描和迁移候选）
  -> ClickExecutor.click（ACTION_CLICK）
  -> verifyActionClick
  -> runGestureFallback（仅在需要时）
  -> CurrentTargetRevalidator.revalidateAtPoint
  -> StandaloneSkipGestureRevalidationPolicy.evaluate
  -> ClickExecutor.gestureClick
```

关键入口当前行号：

- `SkipAccessibilityService.kt:67`：`onAccessibilityEvent`。
- `NodeScanner.kt:9`：BFS 扫描节点树。
- `RuleMatcher.kt:9`：候选解析、评分和高风险复核。
- `SkipAccessibilityService.kt:790`：延迟后的重新扫描与点击。
- `SkipAccessibilityService.kt:1032`：手势兜底。

### 日志调用链

```text
MatchResult
  -> ClickMatchSnapshot
  -> PendingClick
  -> ClickLogEventFactory.build
  -> LogRepository JSON 持久化
```

`standaloneSkipAllowed` 必须在整条链上保持，旧 JSON 没有该字段时默认 `false`。

## 6. 启动、构建、测试和环境

### 环境事实

- 当前 `JAVA_HOME`：`C:\Users\ASL\.jdks\ms-17.0.17`。
- Gradle daemon toolchain：JDK 21，由 `gradle/gradle-daemon-jvm.properties` 指定。
- Android SDK：`S:\Android\Sdk`（`local.properties` 中以 Java Properties 转义形式保存）。
- ADB：`1.0.41`，platform-tools `37.0.0-14910828`。
- `keystore.properties` 当前不存在；Debug 构建不受影响，Release 构建会因缺少签名属性失败。
- 当前没有物理 `AGENTS.md` 文件；新 Session 如收到注入式项目指令，应优先遵守注入内容。

### 推荐工作目录

继续功能时必须进入功能工作树：

```powershell
Set-Location -LiteralPath 'S:\Android\AndroidStudioProjects\Skip\.worktrees\safe-skip-candidate-resolution'
git status --short
git branch --show-current
```

### JVM 定向回归

```powershell
.\gradlew.bat testDebugUnitTest `
  --tests com.example.skip.SafetyAndLogUnitTest.defaultStandaloneSkipPolicyRejectsConflictingNonBlankLabels `
  --tests com.example.skip.SafetyAndLogUnitTest.defaultStandaloneSkipPolicyRejectsUnsafeNodeInResolvedActionPath `
  --tests com.example.skip.SafetyAndLogUnitTest.actionPathSafetyTreatsCustomEditableNodeAsUnsafeWithoutOtherInputSignals `
  --tests com.example.skip.SafetyAndLogUnitTest.gestureRevalidationRejectsChangedLabelEvenWhenViewIdStillMatches `
  --tests com.example.skip.SafetyAndLogUnitTest.gestureRevalidationRejectsStandaloneSkipAfterEightSeconds `
  --tests com.example.skip.SafetyAndLogUnitTest.gestureRevalidationRejectsStandaloneSkipActionParentDeeperThanTwo `
  --tests com.example.skip.SafetyAndLogUnitTest.gestureRevalidationAllowsCurrentSafeSmallExactStandaloneSkip
```

### 完整 JVM 和打包

本机曾因系统提交内存不足导致 Gradle daemon 崩溃，建议固定低并发和 1 GB heap：

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"
.\gradlew.bat assembleDebug packageDebugAndroidTest --rerun-tasks --no-daemon --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"
git diff --check main..HEAD
```

### 设备仪器测试

```powershell
S:\Android\Sdk\platform-tools\adb.exe devices -l
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.skip.GeminiRegressionInstrumentedTest
```

没有设备时第二条命令不能作为通过；应停止并报告“等待设备或模拟器”。

### Release 构建

只有在用户明确要求发布，并提供本地 `keystore.properties` 与签名文件后再运行：

```powershell
.\gradlew.bat assembleRelease
```

禁止在日志或接手文档中写出签名密码和密钥内容。

## 7. 已知报错、风险、阻塞点和待确认事项

### 已知报错

- 曾出现 `Gradle build daemon disappeared unexpectedly`。
- JVM 崩溃文件当时明确记录：`There is insufficient memory for the Java Runtime Environment to continue`，根因是系统提交/分页文件接近耗尽，不是测试断言失败。
- 使用 `--max-workers=2` 和 `-Xmx1024m` 后相同测试与打包命令成功。

### 当前阻塞点

- **设备缺失是唯一明确的合并硬阻塞。** 仪器测试代码已打包，但没有运行。
- 设计/实施文档与代码存在漂移，合并前必须修正。

### 主要风险

- 新 Session 若只查看主工作区，会误以为纯跳过代码尚未实现；实际代码在功能 worktree。
- 若直接在 `main` 重做功能，会与现有 9 个提交重复或冲突。
- 若跳过手势重新授权，View ID 不变但标签已改变时可能误点。
- 若只检查最终可点击父节点，不检查中间链路，可能穿过输入/密码节点。
- 若在没有设备验证时合并，真实 Android 无障碍树结构仍是未知风险。
- `docs/CODEX_HANDOFF.md` 位于主工作区且未提交；清理命令不得误删。

### 待确认

- 待确认：用户准备使用物理 Android 设备还是 Android Studio 模拟器完成仪器测试。
- 待确认：仪器测试通过后，是否仍按原计划只做本地快进合并，不 push、不创建 PR。
- 待确认：接手文档最终是否需要单独提交；当前用户明确要求本轮不要提交 Git。

## 8. 推荐的新 Session 执行顺序

1. 阅读本文件，并分别检查主工作区和功能 worktree 的 `git status`、分支和 HEAD；不要假设状态未变化。
2. 进入 `safe-skip-candidate-resolution` 工作树，确认 HEAD 至少包含 `8279037`，且无未知改动。
3. 对照实际代码同步两份 superpowers 设计/实施文档：严格全标签、完整动作链、手势重新授权、动作身份合并。
4. 运行七个 P1 定向 JVM 回归。
5. 使用低内存参数运行完整 JVM 测试和 Debug/AndroidTest 打包，并记录精确测试总数。
6. 检查 ADB；若无设备，停止在这里并报告阻塞，不提交、不合并。
7. 有设备后运行完整 `GeminiRegressionInstrumentedTest`，检查报告中没有失败或跳过。
8. 运行 `git diff --check main..HEAD`，审查最终 diff，只提交文档同步；不要改业务代码，除非验证暴露真实缺陷并先补失败测试。
9. 再次确认分支相对 `main` 单向领先，然后从主仓库执行 `git merge --ff-only codex/safe-skip-candidate-resolution`。
10. 在 `main` 重跑完整 JVM 测试和打包；全部通过后再移除工作树、`git worktree prune`、删除本地功能分支。
11. 保留主工作区的 `docs/CODEX_HANDOFF.md`，除非用户明确决定提交或删除。

## 9. 可直接发送给新 Codex Session 的接手 Prompt

```text
请接手 Android 项目 S:\Android\AndroidStudioProjects\Skip。

先完整阅读 docs/CODEX_HANDOFF.md，并实际重新检查主工作区与 S:\Android\AndroidStudioProjects\Skip\.worktrees\safe-skip-candidate-resolution 的 Git 状态，不要只复述文档。

当前目标是收口 codex/safe-skip-candidate-resolution 分支上的受限纯“跳过 / skip”功能。代码中的三个 P1 已实现：冲突标签全部拒绝、候选到动作节点的完整链路安全检查、手势前基于当前节点重新执行完整授权。当前最重要的阻塞是没有连接 Android 设备，仪器测试尚未运行；在 connectedDebugAndroidTest 真正通过前不要合并 main，也不要声称完成。

请按以下顺序执行：
1. 在功能 worktree 同步过时的设计和实施文档，使其与当前代码一致；不要无理由重写已经提交的业务代码。
2. 运行七个 P1 定向 JVM 测试、完整 JVM 测试和 assembleDebug/packageDebugAndroidTest，使用 --max-workers=2 与 -Xmx1024m 降低 Gradle 内存峰值。
3. 检查 adb devices -l；无设备时明确报告阻塞并停止。有设备时运行 GeminiRegressionInstrumentedTest。
4. 设备测试通过后检查最终 diff，提交文档同步，再按用户确认执行本地 fast-forward 合并、main 上复验和工作树清理。

硬约束：中文沟通；保留用户现有改动；不要 push、不要创建 PR、不要部署；不要泄露签名或密钥；测试结果必须来自本 Session 的真实命令输出。
```
