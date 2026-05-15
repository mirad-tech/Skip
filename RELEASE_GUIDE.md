# Skip 发布指南

本文档说明本地构建、签名、发布前检查和回滚流程。不要把签名文件、密码、密钥写入仓库。

## 1. 发布前准备

1. 确认版本号：
   - `app/build.gradle.kts` 中 `versionCode`
   - `app/build.gradle.kts` 中 `versionName`
2. 确认发布文档已更新：
   - `README.md`
   - `RELEASE_NOTES.md`
   - `COMPLIANCE_CHECKLIST.md`
   - `RELEASE_TEST_MATRIX.md`
   - `RULES_GUIDE.md`
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

- 不出现 `INTERNET`。
- 不出现定位、通讯录、相机、麦克风、短信、外部存储权限。
- 主 Manifest 仅通过 service 声明 `android.permission.BIND_ACCESSIBILITY_SERVICE`。

## 5. release 产物

release APK 输出路径通常为：

```text
app/build/outputs/apk/release/app-release.apk
```

发布前必须在真机安装 release APK，验证：

- 首次披露流程。
- 无障碍授权流程。
- 高风险按钮阻止。
- 坐标兜底限制。
- 规则导入导出。
- 日志和统计。

## 6. 回滚流程

如果发布后发现阻塞问题：

1. 暂停分发当前 APK。
2. 记录问题设备、系统版本、复现路径和日志。
3. 回滚到上一版已验证 APK。
4. 在修复分支添加回归测试。
5. 重新执行 `RELEASE_TEST_MATRIX.md`。

## 7. 发布阻塞条件

出现以下任一情况不得发布：

- 单元测试失败。
- debug 或 release 构建失败。
- Manifest 出现无关敏感权限。
- 首次披露无法展示。
- 未同意即跳转系统无障碍设置。
- 高风险按钮会被点击。
- 坐标兜底可在无包名、无锚点、无冷却或高风险场景执行。
- 签名文件、密码、密钥被 Git 跟踪。
