# iOS 开发交接 · 局域网只读用量

实施起点是项目发起人确认的[功能契约](README.md)。同一对话处理 `magpie-ios/` 内的 SwiftUI、平台服务、Xcode 配置及测试。共享模块由 Android/KMP 对话交付。

## 从现有页面接入

1. 从 `magpie-ios/magpie-ios/ContentView.swift` 进入 `UsagePage.swift`。保留原型的导航、时间选择器、详情弹层和已有的辅助功能标识，把概览和请求列表改成接受只读状态与操作回调。用预览或测试数据源构造加载、空、受限和部分定价等状态。
2. 在用量入口提供首次连接与编辑连接的原生界面，使用安全输入框，凭据按 origin 存入 Keychain。断开并清除连接时删除对应条目，截图、错误文本和日志均不包含密钥。保持原生返回手势、大字号和 VoiceOver 的可读顺序。
3. 在用户首次连接时触发本地网络访问，配置真实用途的 `NSLocalNetworkUsageDescription`。本轮采用手动地址和受信 HTTPS；设备上的局域网权限、证书信任和网关鉴权分别验证。系统拒绝后给出到设置检查权限、再回应用重试的路径。[Apple 本地网络隐私说明](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)
4. 共享模块可供导入后，用一个薄的 Swift 适配层把共享状态送到主线程，并将操作传回。结束观察时清理订阅；视图重建时复用正确的连接上下文。演示数据源仅用于预览、测试或标明的演示模式。SwiftUI 展示已解析结果；协议解码、授权解释和分页由共享层维护。

## 与 KMP 对接

iOS 对话先以受控状态完成 UI 和测试。收到 framework 名称、Gradle 命令和最小 Swift 调用样例后，在本仓库现有 Xcode 工程里配置依赖。从 Xcode 的正常 Build 路径验证模拟器和设备架构，记录所用 Xcode、Kotlin 与 SDK。确认支持的目标和工具链后，可采用 Kotlin 官方的直接集成方式。在 KMP 尚未接通时，进度报告写明已验证的是原生展示。[Kotlin 的 Xcode 直接集成说明](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)

## 验证与交付

从仓库根目录检查现有 scheme 并构建模拟器版本。

```sh
xcodebuild -list -project magpie-ios/magpie-ios.xcodeproj
xcodebuild -project magpie-ios/magpie-ios.xcodeproj -scheme magpie-ios \
  -configuration Debug -destination 'generic/platform=iOS Simulator' build
```

当前应用的 iOS 部署目标为 27.2，工程只有应用 target。实施时为本功能接入可运行的测试 target 与 scheme 测试动作。用可用模拟器的具体 destination 执行 `xcodebuild test`，记录运行的用例数。重点验证受限概览后仍能进入请求页，以及较慢的旧结果和前后台切换。按[共同验收用例](acceptance.md)记录自动化和真机结果。真机需满足工程部署目标，采用设备持有人的本地签名配置。

**确认后可直接发给 iOS 对话**

> 我已确认局域网只读用量的设计。请从 docs/usage/ios.md 开始，按其中链接读取共同契约和验收用例，负责 magpie-ios/。保留已有原型改动。先用受控状态完成 SwiftUI 和平台测试，收到共享模块后接真实数据。分别汇报模拟器测试、KMP 对接和真机验收结果。
