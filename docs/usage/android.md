# Android 与 KMP 开发交接 · 局域网只读用量

实施起点是项目发起人确认的[功能契约](README.md)。本对话负责 `magpie-android/` 及拟新增的根目录 `shared/`。iOS 对话负责 Xcode 工程和 Swift 侧接入。

## 先交付能被两端调用的共享模块

1. 以现有 `magpie-android/gradlew`、版本目录及 settings 为构建入口，引入指向根 `shared/` 的 KMP 模块。核对当前 Gradle、Android Gradle Plugin、Kotlin 与 Xcode 组合。先让 Android 和 iOS 模拟器各编译一次对同一个共享类型的调用，并构建设备架构。解析兼容性时，列出确需调整的版本和影响。
2. 共享层实现时间范围、主机查询上下文、请求构造、JSON 解析、状态和分页。原生端提供安全凭据访问和平台传输所需的实现。共享逻辑接受可替换的传输实现，使测试能控制返回顺序。合成样例、协议映射和竞态回归归此模块所有。
3. 尽早把 framework 导入名、模拟器与设备的构建命令、可用代码位置和实际可编译的 Swift 调用样例交给 iOS 对话。样例覆盖订阅状态、发起操作和取消观察。将 iOS 发现的导出类型或生命周期问题修在共享接口，并告知对方复验。

## Android 原生接入

1. 从 `MagpieApp.kt` 找到用量导航，修改 `UsageScreen.kt` 的数据入口。菜单、返回、弹层和详情由 Compose 管理。让页面接收不可变状态与回调，由页面外的状态持有者收集共享结果。保存时间范围等 UI 选择，页面重建时依当前连接恢复查询。[Compose 状态提升](https://developer.android.com/develop/ui/compose/state-hoisting)
2. 连接表单采用安全输入，凭据以 Android Keystore 保护的加密存储持久化，排除凭据备份。断开并清除连接时删除对应条目。证书校验使用目标主机及应用信任链。用真实设备检查目标证书链能否被应用信任。
3. 现有应用 `targetSdk=37`，主清单尚未声明网络权限。接入时声明 `INTERNET`。对于本稿采用的手动直连，在 Android 17 及以上声明并按运行时规则申请 `ACCESS_LOCAL_NETWORK`，旧系统按对应平台规则处理。拒绝、撤销以及返回设置后的重试需要分别验证。[Android 本地网络权限](https://developer.android.com/privacy-and-security/local-network-permission)

如果 Mac 使用 Magpie 内置 CA，在 Magpie 的网络与共享设置中导出 `magpie-ca.crt`，将文件传到 Android，再从用量页的连接表单选择「导入 CA」。App 将该 CA 与网关的 HTTPS 地址和密钥一起保存，并校验证书链和主机名。没有选择 CA 时仍使用 Android 默认的信任设置。

## 验证与交付

在 `magpie-android/` 运行已有入口。设备测试命令需要已连接的模拟器或测试设备。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

编写本稿时已用 Gradle dry-run 确认上述任务存在。设备测试依赖首次解析时未能从 Maven Central 下载 JUnit Platform Launcher 1.13.3；实施时需验证依赖解析并实际运行设备测试。

为新模块补充实际的共享测试、framework 构建任务名和结果。模板加法测试只验证测试入口。新增的 Compose 测试至少走一遍“用量 → 受限概览 → 自己的请求”，并验证重试或重建后没有重复记录。真机阶段按[共同验收用例](acceptance.md)核对同一授权范围的 Mac 数据。

**确认后可直接发给 Android 对话**

> 我已确认局域网只读用量的设计。请从 docs/usage/android.md 开始，按其中链接读取共同契约和验收用例，负责 magpie-android/ 和 shared/。保留已有原型改动。先交付可供 iOS 导入的最小共享模块与 Swift 调用样例，再完成 Compose 接入、共同用例与本端真机验收。交接时给出代码位置、命令和实际结果。
