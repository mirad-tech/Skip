# Skip 日志诊断包使用指南

诊断包用于复现和定位“未跳过、误跳过、被系统限制、被安全策略阻止”等问题。它只在用户主动点击导出时生成 JSON 文件，不联网、不自动上传，也不新增任何权限。

## 复现和导出

1. 在 Skip 中保持需要测试的规则、应用开关和安全模式状态。
2. 打开目标 App，复现一次问题场景，例如未跳过、误跳过、高风险按钮被阻止、无障碍服务被系统限制。
3. 回到 Skip，进入“日志与隐私”中的“点击日志”。
4. 点击“导出诊断包”。
5. 在系统文件选择器中保存 `skip_diagnostic_yyyyMMdd_HHmmss.json`。
6. 将该 JSON 文件发给开发者分析。

## 诊断包包含什么

- `schemaVersion`、`exportTime`、`skipVersion`：用于确认导出格式、导出时间和 App 版本。
- `device`：品牌、厂商、型号、Android 版本、SDK、ROM 类型。
- `runtimeState`：总开关、安全模式、调试日志、披露同意、无障碍服务状态、服务连接/活跃/中断时间、最近失败原因。
- `rulesSnapshot`：当前规则、规则包、应用策略、默认规则模板、默认关键词、View ID 关键词、坐标兜底状态。
- `clickLogs`：脱敏后的点击日志，包含阶段、失败原因、候选数量、分数、时间窗、坐标兜底、阻止原因，以及 `ruleKind`、`planScope`、`effectiveRuleWindowMs`、`candidateRelocated`、`relocationMethod` 等字段。
- `ruleLogs`：规则创建、导入、失败记录。
- `diagnosticSummary`：本地聚合后的常见原因计数。

## 我会重点看哪些字段

- `runtimeState.accessibilityServiceEnabled`：判断无障碍服务是否实际开启。
- `runtimeState.masterEnabled` 和 `runtimeState.safetyModeEnabled`：判断总开关或安全模式是否影响点击。
- `rulesSnapshot.appPolicies`：判断目标应用是否关闭了默认规则或自定义规则。
- `rulesSnapshot.defaultRuleRuntime`：判断默认规则当前时间窗、最低分、位置和冷却参数。
- `clickLogs[].stage`：判断流程停在无候选、分数不足、时间窗外、点击失败还是效果未知。
- `clickLogs[].failureReason` 和 `clickLogs[].blockedReason`：判断具体失败原因。
- `clickLogs[].candidateCount`、`score`、`minScore`：判断规则是否命中但分数不足。
- `clickLogs[].elapsedSinceForegroundMs`、`defaultRuleWindowMs`、`isWithinDefaultRuleWindow`：判断是否错过启动时间窗。
- `clickLogs[].rootWindowNull` 和 `canRetrieveWindowContent`：判断系统是否没有提供可读取窗口。
- `clickLogs[].clickTargetSource`、`isFixedCoordinateClick`：判断是否走了坐标兜底或被坐标兜底限制。
- `clickLogs[].standaloneSkipAllowed`：判断该次点击是否通过默认纯“跳过 / skip”限制。
- `diagnosticSummary.categoryCounts`：快速定位无候选、低分、冷却、时间窗、安全阻止、坐标兜底限制、窗口为空、包名变化等常见原因。

## 纯“跳过 / skip”诊断

默认规则只会对精确的纯“跳过 / skip”候选放宽匹配，并且仍要求位于右上区域、前台八秒内、候选足够小且存在安全可点击路径。出现以下失败原因时，可按下表排查：

| 失败原因 | 含义 | 建议处理 |
| --- | --- | --- |
| `standalone_skip_not_top_right` | 纯“跳过 / skip”不在右上区域 | 不使用普通/自定义纯标签规则放宽；改用带明确广告/开屏语义的文本或稳定 View ID。 |
| `standalone_skip_window_expired` | 已超过前台八秒限制 | 检查开屏加载时机，不扩大默认纯跳过时间窗。 |
| `standalone_skip_candidate_too_large` | 候选占屏超过 2% | 不使用普通/自定义纯标签规则放宽；改用带明确广告/开屏语义的文本或稳定 View ID。 |
| `standalone_skip_no_safe_action_path` | 未找到两层内的安全可点击路径 | 检查无障碍树或使用带锚点的坐标规则。 |
| `standalone_skip_unsafe_ancestor` | 候选链包含高风险语义 | 不执行自动点击。 |
| `standalone_skip_label_not_exact` | 候选标签不是精确的“跳过 / skip”（例如关闭、×、close 或倒计时） | 保持默认规则；仅为稳定且精确的目标创建自定义规则。 |
| `standalone_skip_rule_source_forbidden` | 只有内置默认规则评估受限纯跳过 | 不使用普通/自定义纯标签规则放宽；改用带明确广告/开屏语义的文本或稳定 View ID。 |
| `standalone_skip_candidate_unsafe` | 候选处于禁用、不可见、输入或密码状态，必须拒绝 | 检查目标控件状态，不绕过候选安全限制。 |
| `standalone_skip_forbidden` | 普通/自定义规则不能放宽精确纯跳过 | 应使用带明确广告/开屏语义的文本或稳定 View ID。 |

`standaloneSkipAllowed=true` 表示该次点击通过了默认纯“跳过 / skip”的全部限制，不能等同于对任意包含“跳过 / skip”文本的放行。

## 隐私边界

- 诊断包不会自动上传，必须由用户主动导出和发送。
- 导出使用系统文件选择器，不需要外部存储读写权限。
- 日志文本会经过脱敏，邮箱、手机号、身份证号、银行卡号和长数字会被替换。
- 不导出完整页面内容，不记录账号、密码、验证码、支付信息。
- 如果发现诊断包仍包含不应出现的信息，请不要发送该文件，并反馈需要继续收紧脱敏规则。
