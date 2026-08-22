# Accessibility Service Memory Kill Fix Plan

> **For agentic workers:** Execute in this session. Steps use checkbox syntax.

**Goal:** Stop vivo OriginOS from killing Skip as `jvmMemLeak` and leaving `SkipAccessibilityService` in AccessibilityManager `Crashed services`.

**Architecture:** Drop high-frequency `TYPE_WINDOW_CONTENT_CHANGED` work before any node IPC, cap nodes visited per scan, and log time-window expiry once per foreground session. Do not reverse Android 13 prefetch-off. Do not widen click/safety scope.

**Tech Stack:** Kotlin, AccessibilityService, JUnit 4.

**Device evidence (iQOO 12 Pro / V2329A, Android 16, OriginOS 6, Skip 1.0.15):**

- 2026-08-22 21:59: process killed `LOW_MEMORY` / `by rms for extype=jvmMemLeak level=2` / RSS 390MB / importance 125
- Now: Enabled=Skip, Bound={}, Crashed={Skip}
- No `FATAL EXCEPTION` for `com.example.skip` in crash logcat
- Same pattern in `logs/crash-20260812-024117` (312MB / 375MB / 368MB / 396MB)

**Why 1.0.14–1.0.15 failed:** they disabled framework prefetch/cache but still walked the full tree on every content-changed event and logged every time-window miss.

## Global Constraints

- Keep `setCacheEnabled(false)` and `NO_PREFETCH = 0` on API 33+
- Do not add permissions
- Do not click outside existing safety/time-window/cooldown rules
- Precise rules may use up to `PreciseRulePolicy.MAX_WINDOW_MS` (15000)

## GitNexus impact

- `processAccessibilityEvent`: LOW (1 direct caller)
- `NodeScanner`: MEDIUM (5 direct callers) — warn before editing
- `AccessibilityNodeAccess`: MEDIUM — do not change in this fix

---

### Task 1: Event work policy

**Files:**
- Create: `app/src/main/java/com/example/skip/service/AccessibilityEventWorkPolicy.kt`
- Create: `app/src/test/java/com/example/skip/AccessibilityEventWorkPolicyTest.kt`
- Modify: `SkipAccessibilityService.onAccessibilityEvent`

- [x] Failing tests for: window-state always processed; content-changed dropped after rule window; content-changed coalesced to 300ms; pending click never dropped
- [x] Implement policy and gate `onAccessibilityEvent` before `withCacheBoundary`

### Task 2: Scan node budget

**Files:**
- Create: `app/src/main/java/com/example/skip/engine/NodeScanBudget.kt`
- Modify: `app/src/main/java/com/example/skip/engine/NodeScanner.kt`
- Test: `app/src/test/java/com/example/skip/NodeScanBudgetUnitTest.kt`

- [x] Cap visited nodes at 500
- [x] Source assertion that `NodeScanner.scan` uses the budget

### Task 3: Time-window log once per session

**Files:**
- Modify: `SkipAccessibilityService.processAccessibilityEvent`

- [x] Log `SkippedByTimeWindow` once per package+foregroundStartTime

### Task 4: Verify

- [x] `:app:testDebugUnitTest` passed (including new policy/budget tests and SafetyAndLogUnitTest 163)
- [x] `:app:assembleRelease` succeeded
- [x] Installed over device `com.example.skip`; AccessibilityManager Bound=Skip, Crashed={}
