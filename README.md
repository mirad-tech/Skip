# Skip

> **本项目完全由 Codex 开发，并由 Codex 完成代码上传到 GitHub；我只负责提供产品方向、功能边界和验收要求。**

一个本地化的 Android 屏幕辅助点击工具 MVP。Skip 基于 Android 官方无障碍服务能力，在用户主动授权后，识别白名单 App 开屏页中的“跳过 / 跳过广告 / Skip / 关闭广告”等按钮，并尝试自动点击，减少重复操作。

## 项目定位

Skip 是一个本机辅助工具，不是破解广告工具。

- 不使用 Root、Hook、Xposed、LSPosed、改包、抓包或注入方案
- 不绕过其他 App 的安全机制
- 不隐藏图标，不强制保活
- 不做后台截图
- 不上传屏幕内容
- 不请求短信、联系人、相册、定位等无关权限
- 只有用户主动开启无障碍服务后才会工作

## 当前版本

| 项目 | 内容 |
| --- | --- |
| App 版本 | `1.2.0` |
| Version Code | `3` |
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 最低系统 | Android 9.0 / API 28 |
| 目标系统 | Android 16 / API 36 |

## 功能概览

### 极简首页

首页只保留最核心的状态和操作：

- App 名称与短副标题
- 当前无障碍服务状态：已开启 / 未开启
- 主按钮：开启服务 / 已开启
- 更多入口

### 更多功能

- 自动跳过总开关
- App 白名单管理
- 关键词管理
- 点击日志
- 安全保护说明
- 隐私说明
- 关于页面

### 无障碍自动点击

服务监听：

- `TYPE_WINDOW_STATE_CHANGED`
- `TYPE_WINDOW_CONTENT_CHANGED`

核心流程：

1. 获取 `rootInActiveWindow`
2. 读取当前包名
3. 判断总开关是否开启
4. 判断当前 App 是否在白名单
5. 跳过系统设置、安装器、支付、银行、钱包、密码管理器、输入法等敏感 App
6. 扫描无障碍节点树
7. 匹配节点的 `text`、`contentDescription`、`viewIdResourceName`
8. 根据关键词、控件 ID、节点位置、可点击状态计算分数
9. 达到阈值后点击当前节点或可点击父节点

## 默认规则

默认关键词包含：

- 跳过
- 跳过广告
- 跳过此广告
- 跳过开屏广告
- 关闭广告
- 关闭推广
- Skip
- skip
- Skip Ad
- Skip Ads

默认控件 ID 片段包含：

- `skip`
- `skip_ad`
- `ad_skip`
- `skip_btn`
- `splash_skip`
- `tt_splash_skip`
- `ksad_skip`
- `gdt_skip`
- `close_ad`
- `ad_close`

## 安全机制

Skip 内置多层防误触策略：

- 非白名单 App 不处理
- 同一 App 启动后的前 10 秒内才尝试自动点击
- 点击间隔不少于 1000ms
- 避免连续重复点击同一目标
- 不点击密码输入框和文本输入框
- 不点击过大的可点击区域
- “关闭”这类泛化词需要结合广告、跳过、开屏等上下文加分
- 默认保护系统设置、安装器、支付、银行、钱包、金融、密码管理器、输入法等 App

## 目录结构

```text
app/src/main/java/com/example/skip/
├── MainActivity.kt
├── data/
│   ├── LogRepository.kt
│   ├── RuleRepository.kt
│   └── SettingsRepository.kt
├── engine/
│   ├── ClickExecutor.kt
│   ├── NodeScanner.kt
│   ├── RuleMatcher.kt
│   ├── SafetyGuard.kt
│   └── ScoreEvaluator.kt
├── model/
│   ├── ClickLog.kt
│   ├── MatchResult.kt
│   └── SkipRule.kt
├── service/
│   └── SkipAccessibilityService.kt
├── ui/
│   ├── common/
│   ├── home/
│   ├── keywords/
│   ├── logs/
│   ├── more/
│   ├── privacy/
│   ├── theme/
│   └── whitelist/
└── util/
    ├── AccessibilityUtil.kt
    └── PackageUtil.kt
```

## 构建运行

在项目根目录执行：

```powershell
.\gradlew.bat assembleDebug
```

生成的 Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 使用步骤

1. 安装 APK。
2. 打开 Skip。
3. 点击首页“开启服务”。
4. 在系统无障碍设置中找到“开屏广告跳过助手”并手动开启。
5. 返回 Skip，进入“更多”。
6. 在“App 白名单”中添加目标 App 包名。
7. 冷启动目标 App，观察开屏页是否自动点击跳过按钮。
8. 回到 Skip 的“点击日志”查看时间、包名和命中规则。

## 隐私说明

Skip 的规则和日志全部保存在本地。点击日志只记录：

- 时间
- 包名
- 命中的关键词或规则名

不会记录完整屏幕文本，不会上传日志，不会采集短信、联系人、相册或定位信息。

## 后续计划

- 规则导入导出
- 规则测试器
- App 启动识别优化
- 节点快照调试能力
- 多语言支持
- 深色模式细节优化

## 许可证

本项目使用仓库内的 `LICENSE` 文件所声明的许可证。
