# Skip 发布说明

## 1.0.13

日期：2026-07-26

### 无障碍服务结构

- 将窗口选择、输入法窗口识别、点击验证、待执行点击协调、反馈提示和事件日志从无障碍服务主体拆分为独立组件。
- 将开屏恢复的重试代次、会话计数和终止状态集中到专用状态对象，保留原有延迟点击、重定位、安全阻止和重试语义。
- 核心待点击状态机与重试链路保持等价，降低后续修改时跨越整份大型文件的风险。

### 日志存储结构

- 将点击日志缓冲、限流、JSON 编解码、数据库实体映射和旧数据迁移拆分为独立组件。
- 将规则日志持久化从点击日志仓储中分离，同时保留现有序列化格式、迁移键和有界重试策略。
- `LogRepository` 继续作为稳定入口，现有调用方无需迁移。

### 回归保护

- 新增开屏恢复状态测试，覆盖过期回调、重试计数、终止会话和完整重置。
- 修复坐标兜底源码范围断言在函数移动后可能静默检查整个文件的问题。

### 版本信息

- `versionCode`：21
- `versionName`：`1.0.13`
- 当前处于发布准备阶段；GitHub Release 尚未创建，下载仍指向已发布的 `1.0.12`。

### 验证摘要

- JVM 单元测试已强制重新执行：237 tests，0 failures，0 errors，0 skipped。
- Android instrumentation 测试源码已编译通过；本机未启动 ADB 设备环境，未执行真机测试。
- Debug 与签名 Release APK 构建通过；R8、资源压缩、release lint 和 APK Signature Scheme v2 验证通过。
- Release 签名证书与历史发布包一致，Room schema 版本保持为 1。
- Manifest 权限仍仅包含 `INTERNET` 与 `REQUEST_INSTALL_PACKAGES`，签名配置和密钥文件未被 Git 跟踪。

## 1.0.12

日期：2026-07-22

### 自动化状态与导航

- 已授权无障碍服务时，首页可以直接恢复自动化，不再重复进入用途说明页。
- 从系统设置入口查看无障碍配置时会保留暂停状态和返回位置，避免意外开启主开关。
- 安全模式切换会立即同步到首页；首页保持原有简洁布局，仅复用主按钮区分自动化与观察模式。

### 日志与统计准确性

- 将日志事件总数统一标记为“事件”，避免误称为规则命中次数。
- 应用详情显示最近真实执行模式；没有相关日志时不再误报为通用规则回退。
- 点击日志导出当前筛选结果，并明确 7 天、1000 条保留范围以及实时待写入和丢弃数量。
- 运行诊断将服务活跃时间与失败原因分别限流，强制记录失败原因时不再绕过活跃时间节流。

### 兼容与回归测试

- 调试扫描 Fixture 根据实际屏幕宽度定位右上角目标，兼容折叠屏和大屏测试环境。
- 增加自动化导航、执行模式标签、首页按钮状态和诊断节流回归测试。

### 版本信息

- `versionCode`：20
- `versionName`：`1.0.12`

### 发布文件

- Release APK：`Skip-v1.0.12-release.apk`
- SHA256：`70FAEEDF4000A0FDEE3E01B10FD0A582AFFFD0DFBF65678AAF9613A10B9BC26C`

### 验证摘要

- JVM 单元测试已强制重新执行并通过。
- Android instrumentation 测试源码已编译通过；本机无连接设备，未执行真机测试。
- Debug 与签名 Release APK 构建通过，Room schema 版本保持为 1。

## 1.0.11

日期：2026-07-22

### 规则安全与精确匹配

- 精确规则只在当前候选实际满足完整 View ID 或精确标签与区域证据时进入点击。
- 精确“跳过 / Skip”重用完整点击父链安全检查，继续阻断输入、密码、文本设置和高风险祖先。
- 输入法阻断集合支持设置观察和 5 秒事件级刷新，并识别输入法窗口类型。
- JSON Merge 会按导入规则规范化 Activity，合并后重新校验，任一无效结果都会整批失败并显示原因。

### 日志存储与生命周期

- 界面和无障碍服务不再等待日志迁移，存储故障不影响核心自动化。
- 损坏旧日志会本地隔离，Room 写入失败时保留在有界 FIFO 队列中并按固定退避自动重试。
- 服务销毁改为异步 flush，不再在主线程等待数据库。
- 安全日志按前台会话降噪，候选丢失和变化日志始终保留。
- 日志、统计和应用详情页面会在存储不可用时显示进程内缓存和明确的降级状态。

### 版本信息

- `versionCode`：19
- `versionName`：`1.0.11`

### 发布文件

- Release APK：`Skip-v1.0.11-release.apk`
- SHA256：`A71529255D17E45BDEA9F75F7B0EDE919A6F2E2C337AE0E9A2193764BCD3AC18`

### 验证摘要

- JVM 单元测试已强制重新执行并通过。
- Android instrumentation 测试源码已编译通过；本机无连接设备，未执行真机测试。
- Debug APK 构建通过，Room schema 版本保持为 1。

## 1.0.10

发布日期：2026-07-12

### 受限纯跳过

- 默认规则可识别启动八秒内、右上角的小型纯“跳过 / skip”控件；所有非空文字或内容描述都必须严格等于“跳过”或 `skip`。
- 小型文字子节点可以使用安全的可点击父容器执行动作，但候选到动作节点的完整路径只要包含可编辑、密码、`EditText`、`ACTION_SET_TEXT`、禁用或不可见节点，就不会点击。
- 手势兜底会针对当前无障碍节点树重新执行完整授权；目标标签、区域、面积、动作深度或安全状态变化时不继续点击。
- 日志保留受限纯跳过授权状态和失败原因，便于区分未命中与安全阻止。

### 版本信息

- `versionCode`：18
- `versionName`：`1.0.10`

### 发布文件

- Release APK：`Skip-v1.0.10-release.apk`
- SHA256：`3FC55B19CBC86ADC5B98691867E2B0A9E1FEFD098B6A7B0E6A660866F1C9D471`

### 验证摘要

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin assembleRelease --rerun-tasks --no-daemon --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8"
git diff --check
```

- JVM 单元测试：183 tests，0 failures，0 errors，0 skipped。
- Release APK 已完成签名、R8、资源压缩和 release lint 验证。
- `GeminiRegressionInstrumentedTest` 未在本机设备执行，真机验证由发布者单独完成。

## 1.0.9

发布日期：2026-07-01

### 安全与可靠性

- 延迟点击和普通手势兜底在执行前重新校验当前目标，页面、包名、输入框、敏感内容或目标身份变化时不再继续点击。
- 坐标兜底在分发手势前重新解析坐标下的真实控件，并复用高风险点击策略；不确定时记录安全阻止而不是继续点击。
- Activity 作用域规则在当前页面身份未知或发生变化时 fail closed，避免同包其它页面复用待执行点击。
- 导入阶段阻断可匹配空字符串的 regex，运行时忽略零长度 regex 命中，降低过宽规则误命中风险。
- 从仓库历史中移除未引用的 Word 复现附件，保持公开仓库只保留源码、文档和必要示例。

### 版本信息

- `versionCode`：17
- `versionName`：`1.0.9`

### 发布文件

- Release APK：`Skip-v1.0.9-release.apk`
- SHA256：`57EB5E150536F272ABC3BB2E04353EBC1D912853DCA4388E5293405318B5A463`

### 验证摘要

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:assembleRelease
git diff --check
```

## 1.0.8

发布日期：2026-06-23

### 热修内容

- 修复应用内更新的 SHA-256 digest 格式误判：GitHub Release API 返回的 `sha256:<64hex>` 在解析为裸 64 位 hex 后，更新校验器现在仍会严格校验并接受该 SHA-256 值。
- 不放宽 md5、sha1、空 digest、非 hex 或非 64 位 digest；SHA-256 不匹配时仍会删除下载的 APK。

### 升级说明

- 1.0.7 的应用内更新可能因 digest 格式误判失败，不能通过应用内更新直接升级到 1.0.8。
- 1.0.7 用户需要从 GitHub Release 手动下载 v1.0.8 APK 覆盖安装一次；从 v1.0.8 起后续应用内更新恢复正常。

### 版本信息

- `versionCode`：16
- `versionName`：`1.0.8`

### 发布文件

- Release APK：`Skip-v1.0.8-release.apk`
- SHA256：`77D36CEE603C982E82E434D0DDF823EBE5AD9EEADDE423DB4879EFB30A10742A`

### 验证摘要

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:assembleRelease
git diff --check
```

## 1.0.7

发布日期：2026-06-23

### 安全与可靠性

- 加固更新链路：GitHub Release asset digest 缺失、格式错误或 SHA-256 不匹配时阻断并删除 APK。
- 安装前校验 APK 包名、versionCode 与签名证书指纹，避免错误或不可信 APK 被安装。
- 检查更新改为两步流程：先展示可用版本信息，再由用户确认下载与安装。
- 加固坐标兜底：统一时间窗，点击前重新校验包名、锚点、目标身份、bounds、高风险词、输入框与敏感页面。
- 坐标兜底不再允许空白目标、仅 className 目标或搜索输入框清除按钮目标。
- 加固 JSON / 自定义规则导入：危险 regex、泛化 View ID、短匹配词、高风险词、异常坐标兜底等会被阻断。
- 第三方 JSON 规则默认停用，并对 regex、area=any、完整 View ID、坐标兜底等显示额外风险确认。
- 自定义规则与 JSON 规则现在同样受输入框清除按钮保护。
- 收紧中文“跳过”语义：普通页面的纯“跳过”以及“跳过设置 / 登录 / 授权 / 更新”等敏感语义不会被 View ID 绕过。

### 验证与兼容说明

- 增加 Release / R8 构建验收矩阵与安全回归测试。
- 出于安全考虑，部分过宽的第三方规则可能需要重新确认或保持停用。
- 坐标兜底比旧版本更保守，建议只用于明确、低风险、稳定的目标；建议先自用观察。

### 已知问题与升级说明

- 1.0.7 的应用内更新可能因 SHA-256 digest 格式误判失败。
- 升级到 1.0.8 时请从 GitHub Release 手动覆盖安装一次；此后应用内更新会恢复正常。

### 版本信息

- `versionCode`：15
- `versionName`：`1.0.7`

### 发布文件

- Release APK：`Skip-v1.0.7-release.apk`
- SHA256：`FEC5D817A278072298E2CA5DFBF8AA7CCB93949EEAFCBD128FA091CB0869D939`

### 验证摘要

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:assembleRelease
git diff --check
```

## 1.0.6

发布日期：2026-06-20

### 更新内容

- 点击日志的 JSON 序列化与偏好写入改为后台串行执行，清空日志与延迟写入不会再发生旧快照回写。
- 正则匹配增加有界缓存；导入阶段保留原始正则源码并预校验语法，`\\Q...\\E` 等合法转义在运行时保持相同语义。
- View ID 正则优先匹配原始资源 ID，并兼容历史规范化规则。
- 补充高风险“更新”拦截与搜索输入框清除按钮规避，降低自动化误触风险。
- 新增日志持久化、正则兼容、输入框规避和扫描回归测试；仪器测试增加 1000 条点击日志 JSON 往返基准。

### 版本信息

- `versionCode`：14
- `versionName`：`1.0.6`

### 发布文件

- Release APK：`Skip-v1.0.6-release.apk`
- SHA256：`7A5C535E0C8C43EB60F97B3564E74A2631F6D6A5105578E505326AC089CB9F54`

### 验证摘要

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:assembleRelease
git diff --check
```

## 1.0.5

发布日期：2026-06-19

### 更新内容

- 在用户正在编辑文本或搜索框已聚焦时暂停自动点击，避免键盘打开后误触清除、关闭或相邻控件。
- 关于页新增手动检测更新能力，仅在用户主动触发时访问 GitHub Releases。
- 点击关于页顶部版本卡片即可检测新版本；发现新版本后会下载、校验 APK，并交给系统安装器安装。
- 新增 GitHub Release APK 解析、SHA-256 校验、包名和版本校验，损坏或不匹配的 APK 不会进入安装流程。
- 统一无障碍权限用途页和隐私/权限说明页的卡片排版与按钮风格。
- 更新隐私和权限文案：联网仅用于手动检测/下载更新，不上传屏幕内容、规则、日志、统计或个人数据。

### 版本信息

- `versionCode`：13
- `versionName`：`1.0.5`

### 发布文件

- Release APK：`Skip-v1.0.5-release.apk`
- SHA256：`17F9EAB3C05C67AD5E8E7D22292242847A13728D146C8E627C549944AFEBAAE3`

### 验证摘要

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
git diff --check
```

## 1.0.4

发布日期：2026-06-17

### 更新内容

- 默认关闭 Chrome 内置开屏跳过规则，避免搜索页左上角 `+` 被误点；Chrome 自定义规则仍可显式启用。
- 收紧默认规则的广告信号识别，避免 `attachments_add` 这类普通 View ID 被当作广告信号。
- 移除 B 站泛关闭控件特权加分，避免视频页 `关闭弹幕` 被误点。
- 增加延迟点击前的重定位一致性检查，防止 100ms 稳定等待后换成语义不同的新目标。
- 补充 Chrome `+`、B 站 `关闭弹幕` 和 B 站开屏 `跳过 5` 的回归验证。

### 版本信息

- `versionCode`：12
- `versionName`：`1.0.4`

### 发布文件

- Release APK：`Skip-v1.0.4-release.apk`
- SHA256：`ECB7A5765137EE0D995503B59F49A5D5743F802E6FDBBEB21CDFFE846E07F65C`

### 验证摘要

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
git diff --check
```

## 1.0.3

发布日期：2026-06-13

### 更新内容

- 统一点击日志结果语义，让成功、失败和安全阻止记录更一致。
- 将规则生命周期变更从 UI 层移出，集中到规则仓储处理。
- 抽离无障碍服务中的点击流程状态，降低服务主体复杂度。
- 保留隐私、权限、说明等共享页面的返回来源。
- 修复首次披露页后的无障碍用途说明返回来源，避免从系统 Hub 进入时丢失返回目标。

### 版本信息

- `versionCode`：11
- `versionName`：`1.0.3`

### 发布文件

- Release APK：`Skip-v1.0.3-release.apk`
- SHA256：`3E2F0BB83F91E22164964084F49724170C2DD0E93E64769C1126B72895421AE9`

### 验证摘要

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
git diff --check
```

## 1.0.2

发布日期：2026-05-22

### 更新内容

- 修复安全扫描报告指出的坐标兜底边界：分发固定坐标手势前必须解析并检查坐标下真实控件。
- 统一高风险点击策略，导入、保存、运行时检查都覆盖文本、content description、View ID 和坐标锚点。
- 执行 `activityName` 规则作用域，避免 Activity 级规则在同包其它页面误命中。
- JSON 导入预览展示目标应用、规则、匹配字段、动作、坐标兜底和额外确认风险点。
- 关闭 Android 自动备份中的本地自动化状态备份，并明确诊断报告为“文本脱敏但保留元数据”。
- 将首页操作文案从“关闭服务”调整为“暂停自动化”，暂停后不再持久化禁用状态事件日志。

### 版本信息

- `versionCode`：10
- `versionName`：`1.0.2`

### 发布文件

- Release APK：`Skip-v1.0.2-release.apk`
- SHA256：`670558DE4F87B235A307C975370C828AEAA8AB8B4D0B7038E6BF40419195C266`

### 验证摘要

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
git diff --check
```

## 1.0.0

发布日期：2026-05-19

### 更新内容

- 首个 GitHub 正式发布版本，定位为本地运行的 Android 开屏页面辅助工具。
- 仅在用户主动开启无障碍服务、完成用途说明并启用规则后工作。
- 支持默认规则、自定义规则、按应用管理、黑名单策略、点击日志、统计页和诊断包导出。
- 支持本地 JSON 规则包导入，并校验包名、匹配条件、高风险词和坐标兜底限制。
- 提供首次启动披露、无障碍用途说明、隐私说明、权限说明和系统兼容说明。
- 提供多套启动图标外观，可在应用内切换。

### 安全和隐私边界

- 默认本地处理，不上传屏幕内容、规则、日志、统计或个人数据。
- 不接入广告 SDK、统计 SDK 或联网 SDK。
- 不申请 `INTERNET`、外部存储、定位、相机、麦克风、短信、联系人、电话或账号权限。
- 不自动点击支付、授权、登录、注册、隐私同意、安装、删除、转账、发送、提交等高风险按钮。
- 坐标兜底默认关闭，只允许在低风险、包名明确、锚点明确、冷却明确的规则中使用。

### 发布文件

- Release APK：`https://github.com/mirad-tech/Skip/releases/download/v1.0.0/Skip-v1.0.0-release.apk`
- SHA256：`A73F1AFD0ABDB5FBC898D00D50DE2E70B1C42722BC4353DCF1DC8ECFA4F89473`

### 验证摘要

本地自动化验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
git diff --check
```

真机无障碍行为仍建议按 `RELEASE_TEST_MATRIX.md` 做人工验证。
