# Skip 发布测试矩阵

发布前必须完成自动化命令和人工真机验证。自动化通过不等于可以发布，仍需完成设备和场景矩阵。

## 自动化命令

每个阶段完成后运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:compileDebugAndroidTestKotlin
git diff --check
```

`assembleRelease` 是当前唯一的 release-like 验证：它会执行正式 release 的签名校验、R8 (`minifyReleaseWithR8`)、资源压缩和 release lint。每次发布前都必须实际运行，不能用 Debug 构建替代。

当前不新增 release-like 构建变体：

- 项目只有 Debug AndroidTest 任务，正式 Release 依赖本机签名配置。
- 新增 minified Debug / r8Regression 变体会改变 build type、签名和 AndroidTest 组合，超出本轮最小安全收口范围。
- 后续如需要在设备上跑 minified AndroidTest，应单独设计 debug 签名的 release-like 变体，且不得改变正式 Release 的签名、R8 或发布配置。

需要设备验证时运行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

本轮自动化结果（2026-06-19，1.0.5）：

| 命令 | 结果 | 备注 |
| --- | --- | --- |
| `.\gradlew.bat :app:testDebugUnitTest` | 通过 | exit 0 |
| `.\gradlew.bat :app:assembleDebug` | 通过 | exit 0 |
| `.\gradlew.bat :app:assembleRelease` | 通过 | exit 0，R8 / shrinkResources 执行完成 |
| `git diff --check` | 通过 | exit 0，仅有 CRLF 工作区提示，无空白错误 |

## Manifest 权限检查

- [x] 检查 `app/src/main/AndroidManifest.xml` 仅因手动更新声明 `INTERNET` 和 `REQUEST_INSTALL_PACKAGES`。
- [x] 检查无定位、通讯录、相机、麦克风、短信、外部存储权限。
- [x] 检查无障碍服务只使用 `BIND_ACCESSIBILITY_SERVICE`。
- [x] 检查 debug manifest 未额外加入敏感权限。

## 设备矩阵

| 平台 | 设备/系统 | 状态 | 备注 |
| --- | --- | --- | --- |
| Android 9 | 待测 | 未测 | 真机或模拟器 |
| Android 10 | 待测 | 未测 | 真机或模拟器 |
| Android 11 | 待测 | 未测 | 真机或模拟器 |
| Android 12 | 待测 | 未测 | 真机或模拟器 |
| Android 13 | 待测 | 未测 | 真机或模拟器 |
| Android 14 | 待测 | 未测 | 真机或模拟器 |
| Android 15 | 待测 | 未测 | 真机或模拟器 |
| Android 16 | 待测 | 未测 | 真机或模拟器 |

## ROM 矩阵

| ROM / 厂商 | 状态 | 重点 |
| --- | --- | --- |
| 原生 Android | 未测 | 无障碍开关、后台恢复 |
| MIUI / HyperOS | 未测 | 权限页、后台限制、桌面保护 |
| ColorOS | 未测 | 无障碍保活、后台限制 |
| OriginOS | 未测 | 无障碍服务恢复 |
| HarmonyOS 兼容 Android 场景 | 未测 | 权限路径、后台策略 |
| One UI | 未测 | 安装器、权限控制器保护 |

## 功能矩阵

| 场景 | 预期结果 | 状态 |
| --- | --- | --- |
| 首次启动 | 显示披露页 | 未测 |
| 未勾选同意 | 不能继续到无障碍设置 | 未测 |
| 勾选同意 | 进入无障碍用途说明 | 未测 |
| 点击去系统设置开启 | 打开系统无障碍设置 | 未测 |
| 拒绝披露 | 返回应用但不引导授权 | 未测 |
| 隐私说明 | 显示本地处理、不上传，联网仅用于手动检测/下载更新 | 未测 |
| 权限说明 | 显示无障碍、网络更新、安装 APK 请求和文档选择器用途 | 未测 |
| 总开关关闭 | 不执行点击 | 未测 |
| 应用默认规则关闭 | 该应用默认规则不执行 | 未测 |
| 应用自定义规则关闭 | 该应用自定义规则不执行 | 未测 |
| 低风险开屏控件 | 时间窗内可辅助点击 | 未测 |
| 无按钮页面 | 不点击 | 未测 |
| 高风险按钮“同意” | 不点击，记录 `blocked_by_safety_policy` | 未测 |
| 高风险按钮“支付” | 不点击，记录 `blocked_by_safety_policy` | 未测 |
| 高风险按钮“登录” | 不点击，记录 `blocked_by_safety_policy` | 未测 |
| 坐标兜底默认关闭 | 不执行坐标点击 | 未测 |
| 坐标兜底无锚点 | 规则不能保存或导入 | 未测 |
| 坐标兜底无包名 | 规则不能保存或导入 | 未测 |
| 坐标兜底超出 6 秒 | 不执行点击 | 未测 |
| 坐标兜底命中高风险词 | 不点击，记录安全阻止 | 未测 |
| 导入安全示例规则 | 导入成功 | 未测 |
| 导入高风险规则 | Hard Block，不保存规则，显示明确原因 | 未测 |
| 导入宽 regex / 低 minScore / area=any | Hard Block 或需要额外确认 | 未测 |
| 导入纯 View ID | 泛化 ID Hard Block；完整低风险 ID 需要额外确认 | 未测 |
| 导入坐标兜底规则 | 缺包名、缺锚点、短锚点或高风险词 Hard Block | 未测 |
| JSON apps[].enabled | 显示该字段不生效的提示，不误导为应用开关 | 未测 |
| JSON 文件资源边界 | 超大文件、超多规则或嵌套过深被拒绝 | 未测 |
| JSON 规则初始状态 | 默认停用，本地确认后再启用 | 未测 |
| JSON 导入线程 | 文件读取与解析不阻塞 UI | 未测 |
| 自定义规则命中搜索清除按钮 | 不点击 | 未测 |
| JSON 规则命中搜索清除按钮 | 不点击 | 未测 |
| 纯中文“跳过”普通页面 | 不点击 | 未测 |
| `跳过 5` / `跳过广告` | 可在低风险开屏或广告场景命中 | 未测 |
| `跳过登录` / `跳过更新` / `跳过授权` | 不点击 | 未测 |
| 导出日志 | JSON 不含完整页面内容 | 未测 |
| 导出诊断包 | JSON 包含设备、运行时、规则快照、点击日志、规则日志和摘要 | 未测 |
| 诊断包隐私检查 | JSON 不含完整页面内容、账号、密码、验证码、支付信息 | 未测 |
| 未跳过场景诊断 | 复现后摘要能体现无候选、低分、时间窗或窗口为空等原因 | 未测 |
| 高风险阻止诊断 | 复现后摘要能体现安全阻止和 `blocked_by_safety_policy` | 未测 |
| 统计页 | 显示安全阻止和坐标兜底次数 | 未测 |
| release APK 安装 | 可安装并打开 | 未测 |
| release APK R8 后功能 | 无崩溃，核心流程可用 | 未测 |
| 关于页版本卡片 | 点击后检测新版本，最新版显示“已是最新版本” | 通过 |

## 发布记录模板

| 项目 | 结果 |
| --- | --- |
| 测试日期 |  |
| 测试版本 |  |
| 测试人员 |  |
| 单元测试 | 2026-05-14 自动化通过 |
| debug 构建 | 2026-05-14 自动化通过 |
| release 构建 | 2026-05-14 自动化通过 |
| 权限检查 | 2026-05-14 源码 Manifest 与合并 Manifest 通过 |
| 主要设备 |  |
| 阻塞问题 |  |
| 发布结论 |  |
