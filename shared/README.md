# MagpieShared 用量模块

本模块提供局域网只读用量的查询、解析、状态和分页。接口对照 Magpie `426a707a1`。Android 通过 `:shared` 依赖同一套逻辑，iOS 通过静态 XCFramework 导入。Android 和 iOS 分别实现凭据存储、HTTPS 传输和系统权限。

## 构建与测试

在 `magpie-android/` 使用项目的 Gradle Wrapper。Gradle 9.6.0、AGP 9.5.1 和 Android SDK 37 沿用现有工程配置。新增 KMP 后，Kotlin 和 Compose 编译插件共用版本 2.4.21。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :shared:testAndroidHostTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :shared:assembleMagpieSharedDebugXCFramework
```

XCFramework 构建需要安装 Xcode 的 Apple Silicon Mac，生成文件为 `shared/build/XCFrameworks/debug/MagpieShared.xcframework`，包含 iPhone 真机与 Apple Silicon 模拟器的 arm64 切片。可用 `:shared:linkDebugFrameworkIosArm64` 和 `:shared:linkDebugFrameworkIosSimulatorArm64` 单独构建。

## Swift 接入

在 Xcode 将静态 XCFramework 加入链接项，设置为 **Do Not Embed**，在 Swift 中使用 `import MagpieShared`。[Swift 调用样例](examples/UsageBridgeSmoke.swift) 使用合成响应演示订阅、连接、切换标签、改时间范围与取消观察。

在主线程创建 `UsageController`。`observe` 会推送初始状态与后续变更，观察回调在主线程执行。页面结束观察时调用 `UsageObservation.cancel()`，连接状态持有者销毁时调用 `UsageController.close()`。

调用 `UsageOrigin.companion.parse(raw:)` 校验并规范化网关地址。用 `connect` 从“今天／概览”开始，或用 `restore` 恢复已保存的界面选择。Keychain 适配器只返回与该 origin 对应的密钥。URLSession 适配器将 `UsageHttpRequest.authorization` 放入请求头，为每个请求保留系统证书及主机名校验，并拒绝跟随重定向。返回响应状态码和 JSON，或用 `UsageFailure` 表示 TLS、网络或局域网权限失败。界面通过 `openTab`、`selectPeriod`、`refresh`、`retry` 和 `loadMore` 发出操作。

两端共用的 Kotlin 测试位于 `shared/src/commonTest`，包括受限密钥、计价、分页失败重试及旧响应乱序。Android Compose 和 Keystore 测试位于 `magpie-android/app/src/androidTest`。真实局域网验收时，用实际手机和已开启共享的 Mac，在同一时段与授权范围核对概览和一页明细。
