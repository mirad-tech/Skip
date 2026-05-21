# Skip

## 项目说明

这是我个人完全依靠 Codex 开发的一款开屏页面辅助工具，用来减少重复点击“跳过”“关闭”类控件的手动操作。如果你有幸看到这个项目，或者正在使用它，欢迎顺手点一个 Star，这会是对我继续维护的很大鼓励。

如果使用过程中遇到任何问题，可以直接提交 Issues，也可以发送邮件到 `miradwhitehead@gmail.com`。邮件标题请带上 `skip项目`，方便我整理和跟进。我会定期让 Codex 拉取这些反馈，并根据问题继续修复和改进项目。

Skip 是一款本地运行的 Android 开屏页面辅助工具。它基于 Android 无障碍服务，在用户主动授权、完成用途说明并启用规则后，辅助点击明确的“跳过”“关闭”类低风险控件，减少重复手动操作。

当前版本：`1.0.2`

## 下载

- [下载 Skip 1.0.2 release APK](https://github.com/mirad-tech/Skip/releases/download/v1.0.2/Skip-v1.0.2-release.apk)
- SHA256：`670558DE4F87B235A307C975370C828AEAA8AB8B4D0B7038E6BF40419195C266`

这是手动安装的 Android APK，未上架应用商店。安装时如系统提示“未知来源应用”，需要用户自行确认是否继续安装。

## 核心能力

- 本地规则：支持默认规则、自定义规则和 JSON 规则导入。
- 按应用管理：可为不同 App 单独配置规则、黑名单策略和启用状态。
- 安全模式：可只记录命中结果，不执行自动点击。
- 日志与统计：记录命中、点击、失败、冷却和安全策略阻止等事件。
- 诊断导出：用户主动导出本地 JSON 诊断包，用于排查未命中或误命中。
- 图标外观：提供多套启动图标外观，可在应用内切换。

## 快速开始

1. 安装 `Skip-v1.0.2-release.apk`，或从当前源码构建安装包。
2. 打开 Skip，阅读首次启动披露内容。
3. 在应用内进入无障碍用途说明页。
4. 跳转到系统无障碍设置，手动开启 Skip 服务。
5. 回到 Skip，在“应用管理”中选择目标 App。
6. 按需启用默认规则、自定义规则或黑名单策略。
7. 在“日志与隐私”中查看运行记录、统计信息和诊断导出入口。

## 文档

- [文档首页](docs/README.md)
- [快速开始](docs/getting-started.md)
- [应用管理与规则](docs/rules-and-apps.md)
- [日志与诊断](docs/logs-and-reports.md)
- [安全与隐私边界](docs/safety-and-privacy.md)
- [发布说明](RELEASE_NOTES.md)

## 安全与隐私

Skip 默认本地处理，不上传屏幕内容、规则、日志、统计或个人数据。

- 不申请 `INTERNET` 权限。
- 不读取短信、联系人、相册、定位、相机、麦克风、电话或账号。
- 不申请外部存储读写权限，导入导出使用系统文件选择器。
- 不 Root、不 Hook、不改包、不抓包、不注入。
- 不自动点击支付、授权、登录、注册、隐私同意、安装、删除、转账、发送、提交等高风险按钮。

## 构建验证

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
git diff --check
```

Release 构建需要本地 `keystore.properties` 和签名文件。真实签名文件、密码和密钥不得提交到仓库。

## 许可证

本项目使用 [MIT License](LICENSE) 开源。
