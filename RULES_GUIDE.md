# Skip 规则指南

规则用于描述“在哪个应用、什么时间窗口内、识别什么低风险控件、如何点击”。规则只应服务于本地开屏页面助手场景，不用于支付、授权、登录、隐私同意等高风险场景。

## 基本原则

- 每条规则必须绑定具体 `packageName`。
- `standard` 通用规则固定在应用进入前台后的 8 秒内生效。
- `precise` 精确规则绑定具体 Activity，窗口允许 3～15 秒；当前 Activity 有有效精确规则时只执行精确规则，否则回退通用规则。
- 优先使用文字、内容描述、View ID 和区域匹配。
- 尽量避免 `area=any`。
- 不要把最低分设置过低。
- 坐标兜底默认关闭，只用于低风险、锚点明确、包名明确的规则。

## 顶层字段

```json
{
  "schemaVersion": 3,
  "name": "本地规则包",
  "version": 1,
  "author": "local",
  "updateTime": "2026-05-14",
  "description": "本地低风险规则",
  "appPolicies": [],
  "apps": []
}
```

## appPolicies

`appPolicies` 可导入按应用开关：

- `packageName`：目标应用包名。
- `defaultRuleEnabled`：是否启用默认规则。
- `customRulesEnabled`：是否启用自定义规则。

Skip 自身包名会被忽略或拒绝。系统、支付、银行、安装器、权限页等受保护应用不会执行规则。

## apps

每个 App 至少包含：

- `packageName`
- `appName`
- `rules`

`rules` 不能为空。

## rule 字段

- `id`：规则唯一 ID。
- `kind`：`standard` 或 `precise`；旧 schema 2 缺少该字段时按 `standard`。
- `name`：规则名称。
- `enabled`：是否启用。
- `activityName`：Activity 名称，默认 `*`。
- `matchTexts`：节点文字关键词。
- `matchContentDescriptions`：内容描述关键词。
- `matchViewIds`：View ID 关键词。
- `textMatchMode`：`contains`、`exact`、`regex`。
- `contentDescriptionMatchMode`：`contains`、`exact`、`regex`。
- `viewIdMatchMode`：`contains`、`exact`、`regex`。
- `area`：控件大概区域。
- `action`：当前只支持 `click`。
- `priority`：优先级。
- `cooldownMs`：点击冷却时间，建议不低于 800。
- `validDurationMs`：普通规则固定 8000；精确规则钳制到 3000～15000；坐标兜底最多 6000。
- `minScore`：最低匹配分，建议不低于 70。
- `coordinateFallback`：坐标兜底，默认关闭。

## area

可选值：

- `top_left`
- `top_center`
- `top_right`
- `middle_left`
- `center`
- `middle_right`
- `bottom_left`
- `bottom_center`
- `bottom_right`
- `any`

`any` 会增加误触风险，只建议用于文字极明确的规则。

## 精确规则

精确规则必须绑定具体包名和非空、非 `*` Activity，所有匹配模式固定为 `exact`，冷却时间不得低于 800ms，并满足以下一类：

- 完整 View ID（例如 `com.example.app:id/ad_skip`）且 `minScore >= 70`；或
- 精确文本/内容描述、非 `any` 区域且 `minScore >= 80`。

内置精确规则受“默认开屏规则”开关控制。证据不足时不得猜测第三方 View ID；B站、ChatGPT、Grok 等应用没有稳定证据时不应硬编码规则。

## 高风险词

规则中不得包含以下高风险内容：

`同意`、`授权`、`允许`、`支付`、`购买`、`确认支付`、`登录`、`注册`、`隐私政策`、`用户协议`、`安装`、`删除`、`卸载`、`转账`、`发送`、`提交`

命中后：

- 不执行点击。
- 只写安全日志。
- 原因标记为 `blocked_by_safety_policy`。

## 坐标兜底

`coordinateFallback` 示例：

```json
{
  "enabled": false,
  "xRatio": 0.9,
  "yRatio": 0.12,
  "anchorTexts": ["开屏提示"]
}
```

启用坐标兜底必须同时满足：

- 只能用于用户手动创建或主动导入的规则。
- 必须绑定包名。
- 必须限制启动后的时间窗口。
- 必须有锚点规则。
- 必须有点击冷却时间。
- 不允许包含高风险词。
- 不允许用于支付、授权、登录、隐私同意等场景。

## 安全示例

完整示例见 [sample_rules.json](sample_rules.json)。

## 导入失败常见原因

- JSON 格式错误。
- `apps` 为空。
- `packageName` 为空。
- `rules` 为空。
- `rule id` 为空。
- `action` 不是 `click`。
- `area` 不合法。
- `minScore` 不在 0 到 100。
- 坐标比例不在 0 到 1。
- 坐标兜底缺少锚点。
- 包含高风险词。

## 导入时实际生效的额外规则

以下行为由导入实现强制，容易与直觉不符，导入前请先确认：

- **导入的规则一律为停用状态。** 无论 JSON 中 `enabled` 写什么，导入后规则的 `enabled` 都被置为 `false`，需要在「规则列表」或应用详情页手动启用。这是为了避免一次导入就改变实际点击行为。
- **`apps[].enabled` 不生效。** 该字段只会在导入结果里产生一条提示，不影响应用是否参与自动化。
- **`schemaVersion` 大于 3 的规则包会被直接拒绝。** 当前支持的导入格式版本上限为 3。
- **导入有规模与内容上限**，超出即失败或在预览中要求额外确认：文件最大 256 KB、JSON 嵌套最深 16 层、最多 50 个应用、最多 100 条规则、每个应用最多 20 条规则、正则最长 256 字符、单个匹配值最长 128 字符。
- **部分写法会要求额外确认**（不是失败，但会在预览中提示）：`contains` 关键词只有 2～3 个字符、使用正则、`area` 为 `any`、只使用完整 View ID、`minScore` 落在 60～69、启用坐标兜底，以及任何第三方 JSON 规则包。
- `appPolicies` 除 `packageName`、`defaultRuleEnabled`、`customRulesEnabled` 外，还接受 `migratedFromBlacklist`。

## 导出后再导入的注意点

导出的规则包会带上 `apps[].enabled = true`，但如上文所述该字段在导入时会被忽略，且所有规则都会被置为停用。因此**导出→导入不是等价往返**：启用状态会丢失，需要重新逐条启用。
