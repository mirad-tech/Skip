# Skip 发布指南

本文档说明本地构建、签名、发布前检查和回滚流程。不要把签名文件、密码、密钥写入仓库。

## 1. 发布前准备

1. 确认版本号：
   - `app/build.gradle.kts` 中 `versionCode`
   - `app/build.gradle.kts` 中 `versionName`
2. 确认发布文档已更新：
   - `README.md`
   - `docs/README.md`
   - `RELEASE_NOTES.md`
3. 确认 `sample_rules.json` 可导入。
4. 确认没有新增无关敏感权限。

## 2. 本地签名配置

仓库只保留 `keystore.properties.example`。真实文件只放在本地：

- `keystore.properties`
- `release.keystore`

示例字段：

```properties
storeFile=release.keystore
storePassword=your-local-password
keyAlias=your-key-alias
keyPassword=your-local-password
```

检查真实签名文件是否未被 Git 跟踪：

```powershell
git ls-files keystore.properties release.keystore
```

预期：没有输出。

## 3. 自动化验证

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
git diff --check
.\gradlew.bat :app:assembleRelease
```

通过标准：

- 单元测试 0 failed。
- debug APK 构建成功。
- diff 空白检查无错误。
- release APK 构建成功。

## 4. 权限检查

检查 Manifest：

```powershell
Select-String -Path app\src\main\AndroidManifest.xml -Pattern "uses-permission"
Select-String -Path app\src\debug\AndroidManifest.xml -Pattern "uses-permission"
```

预期：

- 仅出现手动更新需要的 `INTERNET` 和 `REQUEST_INSTALL_PACKAGES`。
- 不出现定位、通讯录、相机、麦克风、短信、外部存储权限。
- 主 Manifest 仅通过 service 声明 `android.permission.BIND_ACCESSIBILITY_SERVICE`。

## 5. 合规检查

每次发布前确认以下边界仍然成立：

- 产品定位为本地自动点击辅助工具 / 开屏页面助手，不宣传为广告破解、广告屏蔽或绕过工具。
- 不复制、不逆向、不照搬同类产品代码，不提交历史对照或逆向计划文件。
- 默认本地处理，不上传屏幕内容、规则、日志、统计或个人数据。
- 联网仅用于用户在关于页手动检测新版本和下载更新 APK，访问 GitHub Releases。
- 未接入广告 SDK、统计 SDK 或远程规则订阅。
- 导入、导出和诊断包生成必须由用户主动触发。
- 首次启动展示明显披露页，用户未主动同意前不引导开启无障碍。
- 用户拒绝后仍可查看隐私说明、权限说明和设置。
- 不自动点击同意、授权、允许、支付、购买、确认支付、登录、注册、隐私政策、用户协议、安装、删除、卸载、转账、发送、提交等高风险控件。
- 命中高风险内容时不执行 `ACTION_CLICK`、手势点击或坐标兜底，只写安全日志，原因标记为 `blocked_by_safety_policy`。
- 坐标兜底默认关闭，不用于内置规则，只能用于用户手动创建或主动导入的低风险规则。
- 坐标兜底规则必须绑定包名、限制启动后时间窗口、包含锚点、设置点击冷却，并通过高风险点击保护。
- `keystore.properties` 和 `release.keystore` 不被 Git 跟踪，签名文件、密码和密钥未写入仓库。

## 6. release 产物

release APK 输出路径通常为：

```text
app/build/outputs/apk/release/app-release.apk
```

## 7. 固定的 GitHub 发布闭环

除非项目所有者明确要求使用分支或 Pull Request，否则 Skip 的日常版本发布直接在干净且已同步的 `main` 上完成，不新建发布分支。

必须按以下顺序执行，不得只推送源码版本后停止：

1. `git fetch --prune --tags origin`，确认当前分支为 `main`、工作区干净，且本地 `main` 与 `origin/main` 一致。
2. 更新 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`。
3. 强制重新执行单元测试、AndroidTest 源码编译、Debug 构建和签名 Release 构建。
4. 验证 APK 内嵌版本、v2 签名和历史发布包签名证书，生成版本化文件 `downloads/Skip-v<version>-release.apk` 与真实 SHA256。
5. 同步更新 `README.md`、`docs/README.md` 和 `RELEASE_NOTES.md`：三处版本、APK 文件名、下载链接和 SHA256 必须一致，不得保留“尚未发布”占位文字。
6. 运行发布元数据校验：

   ```powershell
   .\tools\verify-release.ps1 -Version <version> -VersionCode <code> -Sha256 <sha256> -AllowDirty
   ```

7. 精确暂存本次发布文件，运行 `git diff --cached --check`，提交后直接执行 `git push origin main`。
8. 在已推送的 `main` 提交上创建并推送 annotated tag：

   ```powershell
   git tag -a v<version> -m "Skip v<version>"
   git push origin v<version>
   ```

9. 使用版本化 APK、真实发布说明创建正式且标记为 Latest 的 GitHub Release：

    ```powershell
    gh release create v<version> downloads\Skip-v<version>-release.apk --repo mirad-tech/Skip --verify-tag --latest --title "Skip v<version>" --notes-file <release-notes-file>
    ```

10. 重新拉取标签并执行最终回读验证：

    ```powershell
    git fetch --prune --tags origin
    .\tools\verify-release.ps1 -Version <version> -VersionCode <code> -Sha256 <sha256> -VerifyGitHubRelease
    ```

11. 确认 GitHub Release 页面、APK 下载 URL、资产 SHA256、`main`、tag 和本地工作区全部一致后，发布才算完成。

## 8. 回滚流程

如果发布后发现阻塞问题：

1. 暂停分发当前 APK。
2. 记录问题设备、系统版本、复现路径和日志。
3. 回滚到上一版已验证 APK。
4. 在修复分支添加回归测试。
5. 重新执行 `RELEASE_TEST_MATRIX.md`。

## 9. 发布阻塞条件

出现以下任一情况不得发布：

- 单元测试失败。
- debug 或 release 构建失败。
- `README.md`、`docs/README.md`、`RELEASE_NOTES.md` 与 APK 的版本、文件名或 SHA256 不一致。
- Manifest 出现无关敏感权限。
- 首次披露无法展示。
- 未同意即跳转系统无障碍设置。
- 高风险按钮会被点击。
- 坐标兜底可在无包名、无锚点、无冷却或高风险场景执行。
- 签名文件、密码、密钥被 Git 跟踪。
