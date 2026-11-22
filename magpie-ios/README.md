# iOS 局域网用量开发

用量入口在「更多 → 用量」。连接使用一条按网关 HTTPS origin 绑定的 Keychain 记录。SwiftUI 订阅 MagpieShared 提供的概览和请求状态，iOS 负责凭据保存、HTTPS 传输和系统权限提示。

## 构建

在含有 `shared/` 的检出根目录生成 Debug framework。

```sh
./magpie-android/gradlew -p magpie-android :shared:assembleMagpieSharedDebugXCFramework
```

应用最低支持 iOS 16.4，与 [Paseo 的 App Store 兼容性要求](https://apps.apple.com/us/app/paseo-remote-coding-agents/id6758887924) 对齐。打开 `magpie-ios.xcodeproj`，使用 `magpie-ios` Scheme 和 Apple Silicon 上满足该要求的 iPhone 模拟器。

Xcode 默认从 `../shared/build/XCFrameworks/debug/MagpieShared.xcframework` 读取 Debug framework。共享模块在另一检出时，将 Xcode 的 `MAGPIE_SHARED_XCFRAMEWORK` 构建设置指向对应的完整路径。

缺少 framework 时可以运行应用，通过「查看示例用量」浏览原型。真机连接使用相应架构的 framework、设备本地签名和受设备信任的 Mac 网关 HTTPS 证书。

## 测试

`magpie-ios` Scheme 的 Test 动作包含平台单元测试和界面测试。当前 Xcode 随附的 XCTest 要求 iOS 17.0 或更新版本，运行 Test 时请选择满足该版本的设备或模拟器。界面测试用合成数据验证连接、受限概览、请求详情及分页重试。连接 MagpieShared 的测试用受控 HTTP 响应验证真实共享模块和 Swift 传输的接口。局域网系统授权、真实证书信任和主机数据核对在真机执行。
