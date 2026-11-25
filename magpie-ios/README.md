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

## 连接手机

在 iOS 的「更多 → 连接手机」扫描 Mac 版 Magpie 的配对码，或复制链接后在同一页使用系统粘贴按钮。配对成功后显示 Mac 名称。「刷新 Mac 名称」会发起新的读取。返回前台或重开 App 时会用钥匙串中保存的身份重新授权。Mac 撤销后，下一次读取或重新授权会提示重新配对。

联调以 [Mac 插件的本地复现步骤](../magpie-plugin/README.md#本地复现)为起点。模拟器用 Debug 构建，可直接粘贴 `127.0.0.1:18400` 中继生成的配对链接。配对码有效期为两分钟。连接正常后，在 Mac 切出插件页、在手机刷新名称并重开 App，再从 Mac 撤销设备分别验证当前连接和重连。

真机的回环地址指向手机本身。请在隔离的测试副本中把 offer 的中继端点设为真机可达的局域网地址，并确认该地址转到测试中继。在真机打开扫码时允许相机权限，使用局域网地址时按系统提示允许本地网络访问。Debug 允许回环和 RFC 1918 IPv4 地址上的 `ws` 测试中继，仍要求 Paseo protected；Release 要求 `wss`。工程保留系统 ATS 默认配置。[Apple 本地网络权限说明](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)

`PhoneConnectionTests` 覆盖配对链接、签名文本、回执匹配、钥匙串恢复，以及 Paseo 0.16.2 的握手和记录字节。联调只记录请求 ID 和回执的截断前缀，以及错误码和名称是否匹配，配对链接、票据和二维码只在配对时使用。
