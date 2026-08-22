# Skip

[![Latest Release](https://img.shields.io/github/v/release/mirad-tech/Skip?display_name=tag&sort=semver)](https://github.com/mirad-tech/Skip/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Skip 是一款本地运行的 Android 开屏页面辅助工具。在用户主动开启无障碍服务、完成用途说明并启用规则后，它可辅助点击明确的“跳过”“关闭”类低风险控件，减少重复手动操作。

## 下载

当前源码版本：`1.0.16`

- 最新已发布版本：[下载 Skip 1.0.16 release APK](https://github.com/mirad-tech/Skip/releases/download/v1.0.16/Skip-v1.0.16-release.apk)
- SHA256：`FD89453FAB1AB2A2BD010E96365A6E3E302C5386CD5500C33F92A1B9BAAAAC62`
- 系统要求：Android 9（API 28）及以上版本。

这是手动安装的 Android APK，未上架应用商店。安装时如系统提示“未知来源应用”，需要用户自行确认是否继续。

<details>
<summary>从 1.0.7 升级</summary>

1.0.7 的应用内更新可能因 SHA-256 digest 格式误判失败，不能通过应用内更新直接升级到最新版。请从 GitHub Release 手动下载 v1.0.16 APK 覆盖安装一次；从 v1.0.8 起后续应用内更新恢复正常。

</details>

## 核心能力

- 本地规则：支持默认规则、自定义规则和 JSON 规则导入。
- 按应用管理：可为不同 App 单独配置规则、黑名单策略和启用状态。
- 安全模式：可只记录命中结果，不执行自动点击。
- 日志与统计：记录命中、点击、失败、冷却和安全策略阻止等事件。
- 诊断导出：用户主动导出本地 JSON 诊断包，用于排查未命中或误命中。
- 图标外观：提供多套启动图标外观，可在应用内切换。

## 快速开始

1. 安装最新已发布的 `Skip-v1.0.16-release.apk`，或从当前 `1.0.16` 源码构建安装包。
2. 打开 Skip，阅读首次启动披露内容。
3. 在应用内进入无障碍用途说明页。
4. 跳转到系统无障碍设置，手动开启 Skip 服务。
5. 回到 Skip，在“应用管理”中选择目标 App。
6. 按需启用默认规则、自定义规则或黑名单策略。
7. 在“日志与隐私”中查看运行记录、统计信息和诊断导出入口。

## 安全与隐私

Skip 默认本地处理，不上传屏幕内容、规则、日志、统计或个人数据。联网仅用于你在关于页手动检测新版本和下载更新 APK，访问 GitHub Releases。

- `INTERNET` 权限仅用于手动检测新版本和下载更新 APK。
- `REQUEST_INSTALL_PACKAGES` 仅用于下载更新 APK 后交给系统安装器。
- 不读取短信、联系人、相册、定位、相机、麦克风、电话或账号。
- 不申请外部存储读写权限，导入导出使用系统文件选择器。
- 不 Root、不 Hook、不改包、不抓包、不注入。
- 不自动点击支付、授权、登录、注册、隐私同意、安装、删除、转账、发送、提交等高风险按钮。

安全漏洞请不要在公开 Issue 中披露，请按 [安全政策](SECURITY.md) 中的方式报告。

## 文档

用户文档：

- [使用文档](docs/README.md)
- [规则指南](RULES_GUIDE.md)
- [日志诊断包指南](LOG_DIAGNOSTIC_GUIDE.md)
- [发布说明](RELEASE_NOTES.md)

开发与维护：

- [贡献指南](CONTRIBUTING.md)
- [发布指南](RELEASE_GUIDE.md)
- [发布测试矩阵](RELEASE_TEST_MATRIX.md)
- [安全政策](SECURITY.md)

## 开发验证

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:assembleDebug
git diff --check
```

完整发布验证见 [发布指南](RELEASE_GUIDE.md)。Release 构建需要本地 `keystore.properties` 和签名文件；真实签名文件、密码和密钥不得提交到仓库。

## 反馈与贡献

- 普通故障与功能建议：[创建 GitHub Issue](https://github.com/mirad-tech/Skip/issues/new/choose)。
- 代码与文档改进：参阅 [贡献指南](CONTRIBUTING.md)。
- 安全或隐私漏洞：按 [安全政策](SECURITY.md) 私下报告。

提交诊断包、截图或日志前，请先删除不希望公开的应用名称、包名、账号信息和其他敏感内容。

## 许可证

本项目使用 [MIT License](LICENSE) 开源。
