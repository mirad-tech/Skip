# 安全跳过候选解析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持安全策略的前提下，让默认规则能点击启动八秒内右上角的小型纯“跳过 / skip”控件，并提高父容器点击与坐标兜底的节点解析一致性。

**Architecture:** 新增纯 Kotlin 的 `DefaultStandaloneSkipPolicy`，把可审计的纯跳过放行条件从 `ScoreEvaluator` 的硬拒绝中拆出。`ClickExecutor` 产生同时包含严格与受限放宽动作路径的 `ClickCandidateResolution`；评分选择路径，普通点击与坐标复验复用候选的视觉边界、身份和祖先安全语义。

**Tech Stack:** Kotlin、Android AccessibilityService / AccessibilityNodeInfo、JUnit 4、AndroidX Instrumentation、Gradle。

## Global Constraints

- 默认纯“跳过 / skip”仅限内置规则、应用前台八秒内、右上小候选、非受保护包和低风险节点链。
- 普通“关闭 / × / close”仍需广告或开屏信号；不得因本次改动使用受限放宽路径。
- 先执行 `ACTION_CLICK`；手势仅在延迟前完成包名、输入焦点、敏感页面和身份复验后使用候选视觉中心，绝不使用大父容器中心。
- 不改变用户规则 JSON 格式、坐标兜底的锚点/包名/冷却/时间窗约束或受保护包清单。
- 所有新失败原因使用稳定的英文 snake_case，必须进入现有日志诊断链路。

---

## 文件结构

- Create: `app/src/main/java/com/example/skip/engine/DefaultStandaloneSkipPolicy.kt` — 纯“跳过 / skip”的纯函数资格判定和稳定失败原因。
- Modify: `app/src/main/java/com/example/skip/engine/ClickExecutor.kt` — 解析候选、严格动作路径、受限放宽动作路径、祖先安全语义和坐标身份合并。
- Modify: `app/src/main/java/com/example/skip/engine/ScoreEvaluator.kt` — 根据规则和候选解析结果选择严格或受限动作路径。
- Modify: `app/src/main/java/com/example/skip/engine/RuleMatcher.kt` — 只从评分选定的动作路径构造 `MatchResult`，并检查完整节点链。
- Modify: `app/src/main/java/com/example/skip/engine/CurrentTargetRevalidator.kt` — 在命中坐标处选择带身份和可点击链的最小候选，而非裸最小节点。
- Modify: `app/src/main/java/com/example/skip/model/MatchResult.kt`、`app/src/main/java/com/example/skip/service/ClickFlowState.kt`、`app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt` — 把受限纯跳过授权沿匹配、延迟和手势兜底状态传递。
- Modify: `app/src/main/java/com/example/skip/model/ClickLog.kt`、`app/src/main/java/com/example/skip/service/ClickLogEventFactory.kt`、`app/src/main/java/com/example/skip/data/LogRepository.kt` — 记录纯跳过是否由受限策略放行。
- Modify: `app/src/test/java/com/example/skip/SafetyAndLogUnitTest.kt` — 纯函数策略、状态传播、坐标身份和日志序列化测试。
- Modify: `app/src/androidTest/java/com/example/skip/GeminiRegressionInstrumentedTest.kt`、`app/src/debug/java/com/example/skip/ScannerFixtureActivity.kt` — 真实无障碍树上的父容器与装饰子节点回归夹具。
- Modify: `LOG_DIAGNOSTIC_GUIDE.md` — 说明新增失败原因及排查分支。

### Task 1: 建立纯跳过资格策略与单元测试

**Files:**
- Create: `app/src/main/java/com/example/skip/engine/DefaultStandaloneSkipPolicy.kt`
- Test: `app/src/test/java/com/example/skip/SafetyAndLogUnitTest.kt`

**Interfaces:**
- Produces: `DefaultStandaloneSkipPolicy.evaluate(context: DefaultStandaloneSkipContext): DefaultStandaloneSkipDecision`。
- Produces: `DefaultStandaloneSkipPolicy.isStandaloneSkipLabel(text: String, contentDescription: String): Boolean`。
- Consumes: `ClickTargetInfo`、`ClickTargetSelection`、`RuleArea`、`RuleSource` 和 `HighRiskClickPolicy`。

- [ ] **Step 1: 编写失败的资格策略测试**

在 `SafetyAndLogUnitTest.kt` 增加下列测试。辅助目标使用现有 `testClickTarget`，放宽动作路径使用 `ClickTargetSelection(node = mockNode, ...)` 的构造不应引入 Mockito；因此把策略输入抽取为不携带节点的 `ResolvedActionPath`。

```kotlin
@Test
fun defaultStandaloneSkipPolicyAllowsOnlySmallTopRightBuiltInCandidate() {
    val allowed = DefaultStandaloneSkipPolicy.evaluate(
        DefaultStandaloneSkipContext(
            ruleSource = RuleSource.BuiltIn,
            appElapsedMs = 7_999L,
            area = RuleArea.TopRight,
            candidateAreaRatio = 0.015f,
            candidate = testClickTarget(920, 40, 1_000, 100, text = "跳过"),
            actionPath = ResolvedActionPath(parentDepth = 1, hasSafeClickableTarget = true),
            ancestorSafetyTexts = emptyList()
        )
    )

    assertTrue(allowed.allowed)
    assertEquals("", allowed.reason)
}

@Test
fun defaultStandaloneSkipPolicyRejectsWrongAreaLargeLateOrSensitiveCandidate() {
    fun decision(
        area: RuleArea = RuleArea.TopRight,
        elapsed: Long = 1_000L,
        ratio: Float = 0.01f,
        safety: List<String> = emptyList()
    ) = DefaultStandaloneSkipPolicy.evaluate(
        DefaultStandaloneSkipContext(
            ruleSource = RuleSource.BuiltIn,
            appElapsedMs = elapsed,
            area = area,
            candidateAreaRatio = ratio,
            candidate = testClickTarget(920, 40, 1_000, 100, text = "跳过"),
            actionPath = ResolvedActionPath(parentDepth = 1, hasSafeClickableTarget = true),
            ancestorSafetyTexts = safety
        )
    )

    assertEquals("standalone_skip_not_top_right", decision(area = RuleArea.Center).reason)
    assertEquals("standalone_skip_window_expired", decision(elapsed = 8_001L).reason)
    assertEquals("standalone_skip_candidate_too_large", decision(ratio = 0.021f).reason)
    assertEquals("standalone_skip_unsafe_ancestor", decision(safety = listOf("登录")).reason)
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests com.example.skip.SafetyAndLogUnitTest.defaultStandaloneSkipPolicyAllowsOnlySmallTopRightBuiltInCandidate --tests com.example.skip.SafetyAndLogUnitTest.defaultStandaloneSkipPolicyRejectsWrongAreaLargeLateOrSensitiveCandidate`

Expected: FAIL，提示 `DefaultStandaloneSkipPolicy`、`DefaultStandaloneSkipContext` 或 `ResolvedActionPath` 未定义。

- [ ] **Step 3: 实现最小且封闭的策略**

创建 `DefaultStandaloneSkipPolicy.kt`。常量和失败原因必须固定如下；`isStandaloneSkipLabel` 只接受完全等于 `跳过` 或不区分大小写的 `skip`，不可匹配倒计时、关闭或长文本。

```kotlin
internal data class ResolvedActionPath(
    val parentDepth: Int,
    val hasSafeClickableTarget: Boolean
)

internal data class DefaultStandaloneSkipContext(
    val ruleSource: RuleSource,
    val appElapsedMs: Long,
    val area: RuleArea,
    val candidateAreaRatio: Float,
    val candidate: ClickTargetInfo,
    val actionPath: ResolvedActionPath,
    val ancestorSafetyTexts: List<String>
)

internal data class DefaultStandaloneSkipDecision(val allowed: Boolean, val reason: String = "")

internal object DefaultStandaloneSkipPolicy {
    const val MAX_ELAPSED_MS = 8_000L
    const val MAX_CANDIDATE_AREA_RATIO = 0.02f
    private const val MAX_ACTION_PARENT_DEPTH = 2

    fun isStandaloneSkipLabel(text: String, contentDescription: String): Boolean =
        listOf(text, contentDescription).any { value ->
            value.trim() == "跳过" || value.trim().equals("skip", ignoreCase = true)
        }

    fun evaluate(context: DefaultStandaloneSkipContext): DefaultStandaloneSkipDecision {
        if (context.ruleSource != RuleSource.BuiltIn) return DefaultStandaloneSkipDecision(false, "standalone_skip_rule_source_forbidden")
        if (context.appElapsedMs > MAX_ELAPSED_MS) return DefaultStandaloneSkipDecision(false, "standalone_skip_window_expired")
        if (context.area != RuleArea.TopRight) return DefaultStandaloneSkipDecision(false, "standalone_skip_not_top_right")
        if (context.candidateAreaRatio <= 0f || context.candidateAreaRatio > MAX_CANDIDATE_AREA_RATIO) return DefaultStandaloneSkipDecision(false, "standalone_skip_candidate_too_large")
        if (!context.actionPath.hasSafeClickableTarget || context.actionPath.parentDepth > MAX_ACTION_PARENT_DEPTH) return DefaultStandaloneSkipDecision(false, "standalone_skip_no_safe_action_path")
        if (context.candidate.input || context.candidate.password || !context.candidate.enabled || !context.candidate.visibleToUser) return DefaultStandaloneSkipDecision(false, "standalone_skip_candidate_unsafe")
        if (!HighRiskClickPolicy.evaluateTexts(context.ancestorSafetyTexts + listOf(context.candidate.text, context.candidate.contentDescription, context.candidate.viewId)).allowed) return DefaultStandaloneSkipDecision(false, "standalone_skip_unsafe_ancestor")
        return DefaultStandaloneSkipDecision(true)
    }
}
```

- [ ] **Step 4: 运行策略测试，确认通过**

Run: `./gradlew.bat testDebugUnitTest --tests com.example.skip.SafetyAndLogUnitTest.defaultStandaloneSkipPolicyAllowsOnlySmallTopRightBuiltInCandidate --tests com.example.skip.SafetyAndLogUnitTest.defaultStandaloneSkipPolicyRejectsWrongAreaLargeLateOrSensitiveCandidate`

Expected: PASS，两个测试均通过。

- [ ] **Step 5: 提交策略单元**

```powershell
git add -- app/src/main/java/com/example/skip/engine/DefaultStandaloneSkipPolicy.kt app/src/test/java/com/example/skip/SafetyAndLogUnitTest.kt
git commit -m "feat: add guarded standalone skip policy"
```

### Task 2: 解析候选节点与安全父动作路径

**Files:**
- Modify: `app/src/main/java/com/example/skip/engine/ClickExecutor.kt`
- Modify: `app/src/main/java/com/example/skip/engine/ScoreEvaluator.kt`
- Modify: `app/src/main/java/com/example/skip/engine/RuleMatcher.kt`
- Modify: `app/src/main/java/com/example/skip/model/MatchResult.kt`
- Modify: `app/src/androidTest/java/com/example/skip/GeminiRegressionInstrumentedTest.kt`
- Modify: `app/src/debug/java/com/example/skip/ScannerFixtureActivity.kt`

**Interfaces:**
- Produces: `ClickExecutor.resolveCandidate(node: AccessibilityNodeInfo): ClickCandidateResolution`。
- Produces: `ScoreEvaluation.clickSelection: ClickTargetSelection?` 与 `ScoreEvaluation.standaloneSkipAllowed: Boolean`。
- Consumes: Task 1 的 `DefaultStandaloneSkipPolicy`。

- [ ] **Step 1: 编写失败的无障碍树回归测试**

在 `ScannerFixtureActivity.Scenario` 添加 `StandaloneSkipInsideClickableParent`。夹具使用一个不可点击、边界为 `Rect(944, 48, 1_016, 112)`、文字为 `跳过` 的 `TextView`，其第一层父 `FrameLayout` 可点击、无高风险文本且边界为 `Rect(896, 24, 1_040, 136)`。在仪器测试添加：

```kotlin
@Test
fun standaloneSkipInSmallClickableParentMatchesBuiltInRule() {
    ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.StandaloneSkipInsideClickableParent
    ActivityScenario.launch(ScannerFixtureActivity::class.java).use {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        val match = NodeScanner.findBestMatch(root, listOf(builtInDefaultRule("com.example.news", "News")), 1_000L)

        assertNotNull(match)
        assertEquals("跳过", match!!.matchedKeyword)
        assertEquals(ClickTargetSourceLog.ClickableParent, match.clickTargetSource)
        assertEquals(1, match.clickedParentDepth)
        assertTrue(match.standaloneSkipAllowed)
    }
}

@Test
fun standaloneSkipAfterEightSecondsDoesNotMatchBuiltInRule() {
    ScannerFixtureActivity.scenario = ScannerFixtureActivity.Scenario.StandaloneSkipInsideClickableParent
    ActivityScenario.launch(ScannerFixtureActivity::class.java).use {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        assertNull(NodeScanner.findBestMatch(root, listOf(builtInDefaultRule("com.example.news", "News")), 8_001L))
    }
}
```

- [ ] **Step 2: 运行仪器测试，确认失败**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.skip.GeminiRegressionInstrumentedTest#standaloneSkipInSmallClickableParentMatchesBuiltInRule,com.example.skip.GeminiRegressionInstrumentedTest#standaloneSkipAfterEightSecondsDoesNotMatchBuiltInRule`

Expected: FAIL，场景枚举和 `MatchResult.standaloneSkipAllowed` 不存在，且当前评分会给纯跳过 `standalone_skip_forbidden`。

- [ ] **Step 3: 实现候选解析与受限路径选择**

在 `ClickExecutor.kt` 增加解析结果。严格路径沿用 `defaultRule = true` 的既有大小限制；放宽路径仅供 Task 1 策略通过后使用，仍使用既有 35% 总屏占比和安全点击检查。

```kotlin
data class ClickCandidateResolution(
    val candidate: ClickTargetInfo,
    val strictSelection: ClickTargetSelection?,
    val relaxedSelection: ClickTargetSelection?,
    val ancestorSafetyTexts: List<String>
) {
    fun actionPathFor(selection: ClickTargetSelection?): ResolvedActionPath =
        ResolvedActionPath(parentDepth = selection?.parentDepth ?: Int.MAX_VALUE, hasSafeClickableTarget = selection != null)
}

fun resolveCandidate(node: AccessibilityNodeInfo): ClickCandidateResolution {
    return ClickCandidateResolution(
        candidate = describeTarget(node),
        strictSelection = findClickableSelection(node, defaultRule = true),
        relaxedSelection = findClickableSelection(node, defaultRule = false),
        ancestorSafetyTexts = node.collectAncestorSafetyTexts()
    )
}
```

`collectAncestorSafetyTexts()` 必须从候选节点开始向上最多四层，收集 text、contentDescription、viewId 和 className。`ScoreEvaluator.evaluate` 的第四个参数改为 `ClickCandidateResolution`：先完成关键词、区域与基础安全判断；若标签为纯跳过且规则是内置规则，调用 Task 1 策略并仅在成功时选择 `relaxedSelection`；其他所有候选只选择 `strictSelection`。`ScoreEvaluation` 必须携带最终 `clickSelection` 和 `standaloneSkipAllowed`。

`RuleMatcher.evaluate` 必须使用 `resolution.candidate` 作为视觉候选、使用 `scoredRule.clickSelection` 作为动作节点，并把 `resolution.ancestorSafetyTexts` 纳入 `HighRiskClickPolicy.evaluateTexts`。为 `MatchResult` 新增 `standaloneSkipAllowed: Boolean`，并在此处赋值。

- [ ] **Step 4: 运行定向与既有回归测试，确认通过**

Run: `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.skip.GeminiRegressionInstrumentedTest`

Expected: PASS；新增纯跳过测试通过，且 `mobileTicketHomeAnnouncementCloseIsNotDefaultSplashCandidate`、`chromeAttachmentAddIsNotDefaultSplashCandidate`、`bilibiliDanmakuCloseIsNotDefaultSplashCandidate`、`bilibiliSearchClearButtonIsNotDefaultSplashCandidate` 仍通过。

- [ ] **Step 5: 提交候选解析单元**

```powershell
git add -- app/src/main/java/com/example/skip/engine/ClickExecutor.kt app/src/main/java/com/example/skip/engine/ScoreEvaluator.kt app/src/main/java/com/example/skip/engine/RuleMatcher.kt app/src/main/java/com/example/skip/model/MatchResult.kt app/src/androidTest/java/com/example/skip/GeminiRegressionInstrumentedTest.kt app/src/debug/java/com/example/skip/ScannerFixtureActivity.kt
git commit -m "feat: resolve safe standalone skip candidates"
```

### Task 3: 用解析结果修正坐标命中与延迟复验

**Files:**
- Modify: `app/src/main/java/com/example/skip/engine/ClickExecutor.kt`
- Modify: `app/src/main/java/com/example/skip/engine/CurrentTargetRevalidator.kt`
- Modify: `app/src/test/java/com/example/skip/SafetyAndLogUnitTest.kt`
- Modify: `app/src/androidTest/java/com/example/skip/GeminiRegressionInstrumentedTest.kt`
- Modify: `app/src/debug/java/com/example/skip/ScannerFixtureActivity.kt`

**Interfaces:**
- Produces: `ClickExecutor.targetWithActionIdentity(candidate, action): ClickTargetInfo`。
- Produces: `CurrentTargetRevalidator.snapshotAtPoint` 返回的 `CoordinateFallbackTargetSnapshot.target` 使用候选边界和链路身份。
- Consumes: Task 2 的 `ClickCandidateResolution`。

- [ ] **Step 1: 编写失败的坐标身份测试**

在 `SafetyAndLogUnitTest.kt` 增加纯数据测试，证明装饰子节点没有自身文本或 View ID 时，父节点的 `id/splash_skip` 可成为稳定身份，但候选边界仍保持子节点的小边界：

```kotlin
@Test
fun coordinateTargetKeepsChildBoundsAndUsesClickableParentIdentity() {
    val child = testClickTarget(920, 40, 980, 100).copy(text = "", viewId = "", nodeClickable = false, parentClickable = true)
    val parent = testClickTarget(896, 24, 1_016, 128, viewId = "com.example.news:id/splash_skip")

    val merged = ClickExecutor.targetWithActionIdentity(child, parent)

    assertEquals(testRect(920, 40, 980, 100), merged.bounds)
    assertEquals("com.example.news:id/splash_skip", merged.viewId)
    assertTrue(merged.parentClickable)
}
```

在相同夹具中增加仪器测试：从子节点中心调用 `CurrentTargetRevalidator.snapshotAtPoint`，断言结果不为空、`target.viewId` 为父节点 ID、`target.bounds` 为子节点边界、`hasClickableNodeOrAncestor` 为 true。

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests com.example.skip.SafetyAndLogUnitTest.coordinateTargetKeepsChildBoundsAndUsesClickableParentIdentity`

Expected: FAIL，`targetWithActionIdentity` 尚未定义。

- [ ] **Step 3: 实现身份合并与点位候选排序**

在 `ClickExecutor.kt` 实现以下身份合并规则：边界、输入与可见性永远来自候选节点；text、contentDescription、viewId 按“候选非空优先，否则动作节点”的顺序合并；`parentClickable` 取候选父可点击或动作节点存在二者之一。

```kotlin
fun targetWithActionIdentity(candidate: ClickTargetInfo, actionTarget: ClickTargetInfo?): ClickTargetInfo =
    candidate.copy(
        text = candidate.text.ifBlank { actionTarget?.text.orEmpty() },
        contentDescription = candidate.contentDescription.ifBlank { actionTarget?.contentDescription.orEmpty() },
        viewId = candidate.viewId.ifBlank { actionTarget?.viewId.orEmpty() },
        parentClickable = candidate.parentClickable || actionTarget != null
    )
```

`CurrentTargetRevalidator.snapshotAtPoint` 对每个包含坐标的可见节点调用 `resolveCandidate`，仅保留 `relaxedSelection != null` 且合并后目标具备坐标身份的项；按候选面积升序选择。构造 `CoordinateFallbackTargetSnapshot` 时保留解析得到的祖先安全文本，`hasClickableNodeOrAncestor = true`。不得把动作父容器的边界替换为候选边界，也不得接受无身份的泛用 View。

- [ ] **Step 4: 运行坐标相关测试，确认通过**

Run: `./gradlew.bat testDebugUnitTest --tests com.example.skip.SafetyAndLogUnitTest.coordinateTargetKeepsChildBoundsAndUsesClickableParentIdentity`

Run: `./gradlew.bat testDebugUnitTest --tests com.example.skip.SafetyAndLogUnitTest.coordinateFallbackRevalidationRejectsUnsafeTargetAndAncestor --tests com.example.skip.SafetyAndLogUnitTest.coordinateFallbackRevalidationAllowsStableLowRiskTarget --tests com.example.skip.SafetyAndLogUnitTest.coordinateFallbackRejectsUnlabeledGenericClickableView`

Expected: PASS；新增合并测试与既有安全拒绝/允许测试全部通过。

- [ ] **Step 5: 提交坐标复验单元**

```powershell
git add -- app/src/main/java/com/example/skip/engine/ClickExecutor.kt app/src/main/java/com/example/skip/engine/CurrentTargetRevalidator.kt app/src/test/java/com/example/skip/SafetyAndLogUnitTest.kt app/src/androidTest/java/com/example/skip/GeminiRegressionInstrumentedTest.kt app/src/debug/java/com/example/skip/ScannerFixtureActivity.kt
git commit -m "fix: resolve coordinate targets through safe action paths"
```

### Task 4: 将受限放行状态贯穿延迟点击、手势和日志

**Files:**
- Modify: `app/src/main/java/com/example/skip/model/MatchResult.kt`
- Modify: `app/src/main/java/com/example/skip/service/ClickFlowState.kt`
- Modify: `app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt`
- Modify: `app/src/main/java/com/example/skip/model/ClickLog.kt`
- Modify: `app/src/main/java/com/example/skip/service/ClickLogEventFactory.kt`
- Modify: `app/src/main/java/com/example/skip/data/LogRepository.kt`
- Test: `app/src/test/java/com/example/skip/SafetyAndLogUnitTest.kt`

**Interfaces:**
- Produces: `standaloneSkipAllowed: Boolean` on `MatchResult`、`ClickMatchSnapshot`、`PendingClick` 和 `ClickLog`。
- Consumes: Task 2 的 `ScoreEvaluation.standaloneSkipAllowed`。

- [ ] **Step 1: 编写失败的状态和日志测试**

```kotlin
@Test
fun allowedStandaloneSkipStateSurvivesPendingClickAndLogSerialization() {
    val pending = ClickFlowStateMachine.startFromMatch(
        packageName = "com.example.news",
        appName = "News",
        match = testMatchSnapshot(matchedKeyword = "跳过", standaloneSkipAllowed = true),
        activeRules = emptyList(),
        signature = "standalone-skip",
        eventContext = testEventContext(),
        delayBeforeClickMs = 100L
    )
    val fields = LogRepository.clickLogJsonFields(ClickLog(standaloneSkipAllowed = pending.standaloneSkipAllowed))

    assertTrue(pending.standaloneSkipAllowed)
    assertEquals(true, fields["standaloneSkipAllowed"])
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests com.example.skip.SafetyAndLogUnitTest.allowedStandaloneSkipStateSurvivesPendingClickAndLogSerialization`

Expected: FAIL，测试辅助方法和数据模型没有 `standaloneSkipAllowed`。

- [ ] **Step 3: 最小实现状态传播与安全手势条件**

在 `MatchResult`、`ClickMatchSnapshot`、`PendingClick` 和 `ClickLog` 添加默认值为 `false` 的 `standaloneSkipAllowed`；在 `ClickMatchSnapshot.from`、`ClickFlowStateMachine.basePending`、`relocateToMatch`、`ClickLogEventFactory.build` 和 `LogRepository` JSON 的写入/读取路径中逐一传递。

将 `SkipAccessibilityService.runGestureFallback` 的纯跳过拦截改为：

```kotlin
if (SafetyGuard.isProtectedPackage(pending.packageName) ||
    pending.isLargeCandidateBounds ||
    (pending.textKeywordIsStandaloneSkip && !pending.standaloneSkipAllowed)
) {
    // 保持现有 finishPendingClick(... "gesture_fallback_blocked") 分支。
}
```

保持 `StableClickDelayPolicy` 对所有纯跳过使用默认稳定延迟；允许的纯跳过只有在现有 `CurrentTargetRevalidator` 通过后才进入手势，且 `updated.candidate` 必须继续是候选小边界。

- [ ] **Step 4: 运行状态、日志和手势安全回归测试**

Run: `./gradlew.bat testDebugUnitTest --tests com.example.skip.SafetyAndLogUnitTest.allowedStandaloneSkipStateSurvivesPendingClickAndLogSerialization --tests com.example.skip.SafetyAndLogUnitTest.highConfidenceBuiltInSkipViewIdsClickWithoutStableDelay --tests com.example.skip.SafetyAndLogUnitTest.coordinateAndNormalGesturePathsShareCurrentTargetRevalidation`

Expected: PASS；受限授权能持久化，既有延迟策略和普通/坐标手势共用复验的断言不变。

- [ ] **Step 5: 提交状态与日志单元**

```powershell
git add -- app/src/main/java/com/example/skip/model/MatchResult.kt app/src/main/java/com/example/skip/service/ClickFlowState.kt app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt app/src/main/java/com/example/skip/model/ClickLog.kt app/src/main/java/com/example/skip/service/ClickLogEventFactory.kt app/src/main/java/com/example/skip/data/LogRepository.kt app/src/test/java/com/example/skip/SafetyAndLogUnitTest.kt
git commit -m "feat: audit guarded standalone skip clicks"
```

### Task 5: 更新诊断说明并执行完整验证

**Files:**
- Modify: `LOG_DIAGNOSTIC_GUIDE.md`
- Test: `app/src/test/java/com/example/skip/SafetyAndLogUnitTest.kt`
- Test: `app/src/androidTest/java/com/example/skip/GeminiRegressionInstrumentedTest.kt`

**Interfaces:**
- Consumes: Tasks 1–4 的稳定失败原因和 `standaloneSkipAllowed` 日志字段。

- [ ] **Step 1: 编写失败的文档一致性测试**

在 `SafetyAndLogUnitTest.kt` 增加：

```kotlin
@Test
fun diagnosticGuideDocumentsStandaloneSkipDecisions() {
    val guide = File("../LOG_DIAGNOSTIC_GUIDE.md").readText()
    listOf(
        "standalone_skip_not_top_right",
        "standalone_skip_window_expired",
        "standalone_skip_candidate_too_large",
        "standalone_skip_no_safe_action_path",
        "standalone_skip_unsafe_ancestor",
        "standaloneSkipAllowed"
    ).forEach { value -> assertTrue("missing diagnostic: $value", guide.contains(value)) }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests com.example.skip.SafetyAndLogUnitTest.diagnosticGuideDocumentsStandaloneSkipDecisions`

Expected: FAIL，指南尚未包含纯跳过失败原因和日志字段。

- [ ] **Step 3: 更新指南**

在 `LOG_DIAGNOSTIC_GUIDE.md` 的失败原因表新增纯跳过分支：

```markdown
| `standalone_skip_not_top_right` | 纯“跳过 / skip”不在右上区域 | 保持默认规则；为该应用创建精确自定义规则。 |
| `standalone_skip_window_expired` | 已超过前台八秒限制 | 检查开屏加载时机，不扩大默认纯跳过时间窗。 |
| `standalone_skip_candidate_too_large` | 候选占屏超过 2% | 不自动放宽；使用具有稳定 View ID 的自定义规则。 |
| `standalone_skip_no_safe_action_path` | 未找到两层内的安全可点击路径 | 检查无障碍树或使用带锚点的坐标规则。 |
| `standalone_skip_unsafe_ancestor` | 候选链包含高风险语义 | 不执行自动点击。 |
```

同时说明 `standaloneSkipAllowed=true` 表示该次点击通过了默认纯跳过的全部限制，不能等同于对任意“跳过”文本的放行。

- [ ] **Step 4: 运行完整验证**

Run: `./gradlew.bat testDebugUnitTest`

Expected: PASS，所有 JVM 测试通过。

Run: `./gradlew.bat connectedDebugAndroidTest`

Expected: PASS；设备或模拟器可用时所有无障碍树回归测试通过。

Run: `./gradlew.bat assembleDebug`

Expected: BUILD SUCCESSFUL，生成 debug APK。

Run: `git diff --check; git status --short`

Expected: 无空白错误；仅显示本任务的预期改动。

- [ ] **Step 5: 提交文档和验证单元**

```powershell
git add -- LOG_DIAGNOSTIC_GUIDE.md app/src/test/java/com/example/skip/SafetyAndLogUnitTest.kt
git commit -m "docs: explain guarded standalone skip diagnostics"
```

## 自检

- 覆盖关系：Task 1 实现八秒、右上、小尺寸和链路安全门槛；Task 2 保证默认规则只为纯跳过选择放宽路径；Task 3 使坐标初选与复验使用同一身份；Task 4 防止延迟和手势丢失授权状态；Task 5 验证并记录全部新分支。
- 占位检查：计划中的接口、常量、失败原因、测试名、命令和提交消息均为具体值。
- 类型一致性：`standaloneSkipAllowed` 从 `ScoreEvaluation` 写入 `MatchResult`，再进入 `ClickMatchSnapshot`、`PendingClick`、`ClickLog`；`ResolvedActionPath` 只作为纯策略输入，避免将 `AccessibilityNodeInfo` 引入 JVM 单元测试。
