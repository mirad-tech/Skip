# 安全政策

## 支持范围

Skip 仅对当前最新 GitHub Release 提供后续修复。报告时请写明受影响版本、Android 版本和相关组件。

## 私下报告

如果问题可能导致越权点击、安全策略绕过、敏感信息暴露、规则导入边界绕过、APK 更新校验失效或签名供应链问题，请不要先创建公开 Issue。

请发送邮件至 `miradwhitehead@gmail.com`，标题使用 `[Skip Security] 问题摘要`，并尽量包含：

- 受影响版本与设备/Android 版本。
- 问题的影响与必要前置条件。
- 可重复的最小步骤。
- 已做脱敏的日志、规则或概念验证。
- 如果已知，可能的缓解方案。

请不要发送真实密码、验证码、私钥、签名密码、支付信息或与问题无关的完整用户数据。

## 本地调试残留物

维护者在本地调试时可能在仓库目录下留下**含第三方 App 界面内容的材料**，例如 `logs/`：

- 录屏与逐帧截图（`screenrecord.mp4`、`frame_*.jpg`）。
- 无障碍节点树 dump（`ui_before.xml`、`ui_after.xml`），其中包含第三方应用的界面文本、控件 ID 与布局。
- `logcat` 抓取文件与手动导出的诊断包（`skip_diagnostic_*.json`）。

这些路径已被 `.gitignore` 排除，**不会被提交**，但它们物理存在于工作区，误打包或误分享会造成第三方界面信息泄漏。分享仓库快照、打包源码归档或上传附件前，请先清理：

```powershell
Remove-Item -Recurse -Force .\logs
```

同理，`downloads/`、`dist/` 与仓库根目录下的 `*.apk` 属于本地发布产物，也不应随源码分发。

普通故障、兼容性问题和功能建议请使用 [GitHub Issues](https://github.com/mirad-tech/Skip/issues/new/choose)。
