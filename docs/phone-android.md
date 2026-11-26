# Android 连接手机

在 Android Magpie 底栏打开「更多 → 连接手机」。在 Mac 版 Magpie 的同名插件页开启后台连接，生成配对码，再用 Android App 扫描。授权后页面显示 Mac 名称。「刷新 Mac 名称」每次向 Mac 发起一次新的读取。重新打开 Android App 时会用已保存的身份重连。Mac 拒绝授权后，页面显示「授权已失效」，可用新配对码重新授权。

Mac 插件基线为 `16fdec8`。Android 使用 Paseo v2 中继路由和 0.16.2 protected 通道，以配对码里的 Mac X25519 公钥验证 Noise 握手。Android 最低为 API 28，使用 Bouncy Castle 的 Ed25519 签名和 Android Keystore 中的 AES-GCM 密钥保存设备身份。配对资料存入 App 的免备份目录，只有通过验证的 offer 字段和加密后的 Ed25519 种子写入文件。首次配对在发送授权请求前保存身份，遇到回执丢失时可用同一身份查询授权结果。

中继使用 TLS 时可连接 offer 指定的地址。无 TLS 的 WebSocket 只允许通过回环地址连接本地测试中继，应用消息仍经过 protected 通道。

连接断开时，可用「立即重连」继续使用已保存的授权，也可用「重新扫码」替换配对。取消扫码、配对码无效或替换失败后，App 会继续使用原配对。替换成功并保存后，App 才连接新配对。

## 本地复现

1. 按 [Mac 插件说明](../magpie-plugin/README.md) 准备基线插件和已绑定到 Mac `127.0.0.1:18400` 的中继。先检查该端口的 `/ready`。
2. 在仓库根目录构建 Debug 并运行测试。连接一台已授予 USB 调试权限的手机，或启动一个独立模拟器。将 `ANDROID_SERIAL` 设为 `adb devices -l` 对应行的序列号。使用与已有安装相同的签名安装 Debug。

   ```sh
   curl --noproxy '*' -fsS http://127.0.0.1:18400/ready
   (cd magpie-android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :shared:testAndroidHostTest)
   adb devices -l
   adb -s "$ANDROID_SERIAL" reverse tcp:18400 tcp:18400
   adb -s "$ANDROID_SERIAL" install -r magpie-android/app/build/outputs/apk/debug/app-debug.apk
   ```

3. 在 Mac 插件界面生成配对码。Android 扫码后对照页面显示的 Mac 名称与 Mac 系统的「电脑名称」，并检查 Mac 插件的设备列表。关闭插件页，再点「刷新 Mac 名称」。
4. 强制停止并重开 Android，再打开「连接手机」。核对 Mac 仍列出同一设备，Android 收到新的读取回执。

   ```sh
   adb -s "$ANDROID_SERIAL" shell am force-stop com.andychen.magpie_android
   adb -s "$ANDROID_SERIAL" shell am start -n com.andychen.magpie_android/.MainActivity
   adb -s "$ANDROID_SERIAL" logcat -d -s MagpiePhone:D
   ```

5. 在隔离环境暂停测试用的 Mac 伴随进程或中继，观察 Android 断线。恢复服务后，核对原设备身份可读取。保持手机连接，从 Mac 撤销这台设备，再在手机刷新，检查 `host.get` 收到 `device_not_authorized`。停掉中继并重开 Android，页面应直接显示重新配对入口。要验证新连接也被拒绝，请恢复中继，重新扫码配对，先结束 App，再从 Mac 撤销，重开 App 后检查 `device.auth` 返回同一错误。
6. 真机通过 USB 线和 ADB reverse 的结果登记为 USB 联调。使用模拟器时登记为模拟器 ADB reverse。蜂窝切换需要 Mac 和手机均可达的中继，单独验收。

Debug 日志仅记录允许列出的授权结果及读取请求 ID、回执的 8 字符前缀。公开反馈只需注明使用的网络路径、操作和结果。

使用模拟器复现相机联调时，可在启动命令中使用 `-camera-back "imagefile:$CAMERA_FRAME"`。从隔离插件页面取二维码图像，在仅当前用户可读的临时目录准备相机输入。保持二维码模块等比缩放和四周留白，码完整落在扫码框内。验证完成后删除临时输入。

## 本地验收记录

2026-11-25，Mac 基线插件 `16fdec8` 的页面在隔离浏览器中运行。Android 使用 API 30 模拟器，通过 ADB reverse 到现有的 Mac 本地中继。二维码由插件页面签发，经模拟器相机输入交给 Android 扫码器。应用成功显示与 Mac 系统设置一致的电脑名称。

| 操作 | 来源与结果 |
| --- | --- |
| 首次配对 | 模拟器相机。Mac 接受 `device.pair` 并列出设备。Android 的存储文件包含公开 offer、AES-GCM IV 和加密后的种子。 |
| 关闭 Mac 插件页后刷新 | 模拟器。离开插件页面后，当前连接完成新的读取。 |
| 杀掉并重开 Android | 模拟器。原设备的 `device.auth` 成功，Mac 仍列出同一设备。 |
| 断线和恢复 | 模拟器。暂停隔离 Mac 伴随进程后显示断开，恢复后沿用原设备重新认证和读取。 |
| Mac 撤销 | 模拟器。当前连接的 `host.get` 和重开后的 `device.auth` 均收到 `device_not_authorized`。页面提示重新配对。 |
| 签名和握手交叉验证 | Mac 参考客户端，以 protected 方式分别配对和重连。Android 单测对照参考实现生成的 Ed25519 签名，覆盖重连签名末尾的 LF。 |

模拟器相机还验证了权限拒绝、非 Magpie 二维码、配对码被替换、两分钟自然过期和中继不可达。

首次联调时 Android 和共享模块各 7 项单测通过，模拟器上 5 项设备测试通过。随后新增撤销后重开 App 的 JVM 回归，覆盖旧存储格式和原中继离线时重新扫码，Android 单测增至 8 项。设备测试包括现有用量页面和凭据存储的样例数据回归。以下命令从仓库根目录执行。

```sh
(cd magpie-android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :shared:testAndroidHostTest :app:assembleDebugAndroidTest :app:assembleRelease)
adb -s "$ANDROID_SERIAL" install -r magpie-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$ANDROID_SERIAL" shell am instrument -w com.andychen.magpie_android.test/androidx.test.runner.AndroidJUnitRunner
```

### 真机 USB 联调

2026-11-25，Android 17（API 37）真机。用同签名 Debug APK 保留数据更新，通过 USB ADB reverse 访问 Mac 的 `127.0.0.1:18400` 中继。手机使用系统相机权限和实际镜头扫描 Mac 隔离插件页显示的二维码。App 显示的名称与 Mac 系统设置一致。Android 侧的局域网权限处于未授予状态。

| 操作 | 真机验收结果 |
| --- | --- |
| 首次扫码 | `device.pair` 成功，Mac 插件列出一台测试设备。 |
| 关闭 Mac 插件页后刷新 | 原连接取得新的请求 ID 和回执。 |
| 杀掉并重开 Android | Android 进程已更换，原身份通过 `device.auth`。Mac 的授权设备仍为同一台。 |
| 断线和恢复 | 暂停隔离 Mac 伴随进程后，真机显示「已断开」。恢复后原身份通过认证并读取。 |
| Mac 撤销 | 当前连接的 `host.get` 和重开后的 `device.auth` 均收到 `device_not_authorized`。页面显示重新配对入口，当前进程停止自动重试。 |

Android 17 首次运行设备测试时，Espresso 3.5.1 在用量 UI 用例初始化阶段因 `InputManager.getInstance()` 不可用而失败。仅将设备测试依赖更新至 Espresso 3.7.0 后，Android 17 真机和 API 30 模拟器各通过全部 5 项设备测试。[AndroidX Test 发布说明](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)

API 28 运行时和蜂窝切换待验收。
