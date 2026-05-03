# Skip

我想做一个很简单的小工具：打开 App 时，如果页面上出现“跳过”“跳过广告”“Skip”这类按钮，就让手机自己帮我点一下。

这个项目的方向、边界和验收要求由我提出，具体代码实现、项目结构整理、README 编写以及上传到 GitHub 的过程都交给 Codex 完成。它更像是一次我和 Codex 协作完成的 Android MVP：我负责判断这个工具应该是什么、不应该做什么，Codex 负责把它落到代码里。

Skip 的目标不是破解广告，也不是绕过任何 App 的安全机制。它只是一个基于 Android 官方无障碍服务的本地辅助工具，只有在用户主动授权以后，才会对白名单 App 的界面节点做本地识别和点击。

## 为什么做它

很多 App 的开屏页都会有一个很小的“跳过”按钮。它不是什么复杂操作，但每天反复点几次，确实有点烦。

所以 Skip 的第一版只解决一个很窄的问题：在可控范围内，尽量自动完成这类重复点击。为了避免误触，它默认只对白名单 App 生效，也会避开系统设置、支付、银行、输入法、密码管理器等敏感场景。

我更希望它是一个克制的工具，而不是一个“什么都想管”的后台服务。

## 当前状态

这是一个 MVP 版本，已经具备基础可用能力：

| 项目 | 内容 |
| --- | --- |
| App 版本 | `1.2.0` |
| Version Code | `3` |
| 开发语言 | Kotlin |
| 界面 | Jetpack Compose + Material 3 |
| 最低系统 | Android 9.0 / API 28 |
| 目标系统 | Android 16 / API 36 |
| 核心能力 | AccessibilityService |

## 它能做什么

Skip 会在用户开启无障碍服务后监听窗口变化，读取当前界面的无障碍节点，并在满足条件时尝试点击疑似“跳过广告”的按钮。

目前支持识别：

- 节点文字，例如“跳过”“跳过广告”“Skip”
- 节点描述，也就是 `contentDescription`
- 控件 ID，例如包含 `skip`、`splash_skip`、`close_ad` 之类的资源名

它不会看到一个“关闭”就立刻点，而是会结合关键词、控件 ID、节点位置、是否可点击等因素做简单评分。分数不够就不操作。

## 首页长什么样

我希望首页尽量干净，不把配置项全堆出来。

首页只保留：

- App 名称
- 一句短副标题
- 无障碍服务是否开启
- “开启服务 / 已开启”按钮
- “更多”入口

白名单、关键词、日志、隐私说明这些内容都放在“更多”里。

## 更多功能

更多页面里目前有这些基础功能：

- 自动跳过总开关
- App 白名单管理
- 关键词规则管理
- 点击日志
- 安全保护说明
- 隐私说明
- 关于页面

点击日志只记录时间、包名和命中的规则名，不记录完整屏幕文本。

## 安全边界

这是我对这个项目比较坚持的部分。

Skip 不做这些事：

- 不 Root
- 不 Hook
- 不使用 Xposed / LSPosed
- 不改包
- 不抓包
- 不注入其他 App
- 不隐藏图标
- 不强制保活
- 不后台截图
- 不绕过用户授权
- 不上传屏幕内容
- 不请求短信、联系人、相册、定位等无关权限

默认不处理这些类型的 App：

- 系统设置
- 应用安装器
- 支付类 App
- 银行类 App
- 钱包、金融、证券、保险类 App
- 密码管理器
- 输入法

## 默认规则

默认关键词包含：

- 跳过
- 跳过广告
- 跳过此广告
- 跳过开屏广告
- 跳过视频广告
- 立即跳过
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
- `splash_skip_btn`
- `tt_splash_skip`
- `ksad_skip`
- `gdt_skip`
- `close_ad`
- `ad_close`

这些规则都保存在本地，后续可以在 App 里继续调整。

## 工作流程

大致逻辑是这样：

```text
窗口变化
  ↓
读取 rootInActiveWindow
  ↓
检查总开关、白名单和安全保护名单
  ↓
扫描 AccessibilityNodeInfo 节点树
  ↓
匹配 text / contentDescription / viewIdResourceName
  ↓
计算候选节点分数
  ↓
找到可点击节点或向上查找可点击父节点
  ↓
执行 ACTION_CLICK
  ↓
写入本地点击日志
```

为了减少误触，服务还加了这些限制：

- 同一次点击间隔至少 1000ms
- 同一 App 启动后的前 10 秒内才尝试自动点击
- 避免短时间重复点击同一个目标
- 不点击密码输入框和文本输入框
- 不点击面积过大的区域

## 项目结构

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

## 构建

在项目根目录执行：

```powershell
.\gradlew.bat assembleDebug
```

Debug APK 会生成在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 使用方式

1. 安装 APK。
2. 打开 Skip。
3. 点击首页的“开启服务”。
4. 在系统无障碍设置里找到“开屏广告跳过助手”，手动开启。
5. 回到 Skip，进入“更多”。
6. 在“App 白名单”里添加目标 App 包名。
7. 冷启动目标 App。
8. 如果开屏页出现匹配规则的跳过按钮，Skip 会尝试自动点击。
9. 可以在“点击日志”里查看时间、包名和命中的规则名。

## 隐私说明

Skip 的配置和日志都只保存在本机。

它不会上传屏幕内容，不会记录完整页面文本，也不会读取短信、联系人、相册或定位信息。点击日志只保留最少信息：时间、包名、命中的规则。

## 后续想法

后面如果继续做，我会优先考虑这些方向：

- 规则导入导出
- 规则测试器
- 更稳的 App 启动识别
- 节点快照调试能力
- 多语言支持
- 深色模式细节优化

## License

许可证以仓库中的 `LICENSE` 文件为准。
