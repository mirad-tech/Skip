# Skip 规则指南

规则用于描述“在哪个应用、什么时间窗口内、识别什么低风险控件、如何点击”。规则只应服务于本地开屏页面助手场景，不用于支付、授权、登录、隐私同意等高风险场景。

## 基本原则

- 每条规则必须绑定具体 `packageName`。
- 默认只在应用进入前台后的 6 秒内生效。
- 优先使用文字、内容描述、View ID 和区域匹配。
- 尽量避免 `area=any`。
- 不要把最低分设置过低。
- 坐标兜底默认关闭，只用于低风险、锚点明确、包名明确的规则。

## 顶层字段

```json
{
  "schemaVersion": 2,
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
- `validDurationMs`：导入后统一收紧到 6000。
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
