<div align="center">

# Magpie Mobile

[English](README.md) · **简体中文**

**在手机上查看 Magpie 网关的 Agent 用量。**

面向 [Magpie](https://github.com/yetone/magpie) 用户的独立 Android 和 iOS 开源客户端。

**[下载 Android 内测 APK](https://github.com/AndyChen1024/magpie-mobile/releases)** · **[从源码运行](#本地开发)** · **[工程文档](#工程文档)** · **[问题反馈](https://github.com/AndyChen1024/magpie-mobile/issues)**

</div>

## 当前进度

项目处于早期开发阶段。Android 9 或更新版本可在 GitHub Releases 的预发布条目中下载 APK，安装后打开 **更多 → 用量 → 查看演示数据** 即可体验。iOS 可从源码运行。

| 平台 | 当前实现 |
| --- | --- |
| Android | 使用 Kotlin 和 Jetpack Compose。已接入局域网只读用量，可按今天、最近 7 天和最近 30 天查看调用次数、token、已知费用及请求明细。也可离线查看演示数据。 |
| iOS | 使用 SwiftUI，局域网用量正在独立开发分支中集成。 |

## 本地开发

克隆仓库并检出要体验的分支，然后按平台运行。

- **Android**。在 Android Studio 打开 `magpie-android/`，使用 Android SDK 37 和 Gradle JDK 25 同步项目，然后在 Android 9（API 28）或更新的设备或模拟器上运行 `app`。
- **iOS**。在 Mac 上用 Xcode 打开 `magpie-ios/magpie-ios.xcodeproj`，选择 `magpie-ios` Scheme 和 iOS 27.2 或更新版本的模拟器。真机调试时请选择自己的开发团队。

### 在 Android 上查看用量

1. 打开 **更多 → 用量**，选择 **查看演示数据**，即可预览页面。
2. 要查看自己的数据，在运行 Magpie 的电脑上开启局域网共享，准备手机可访问的 HTTPS 网关地址和网关密钥。网关证书须受应用信任，并匹配访问地址。[Magpie 局域网共享说明](https://github.com/yetone/magpie/blob/426a707a19ff7f481989f03c49519bf4dba1e593/docs/integrating.md#L50)
3. 让手机和主机接入同一局域网，在 **用量 → 连接主机** 填写地址和密钥。连接后可切换时间范围，查看用量概览或翻阅请求明细。

## 工程文档

[![移动端总体架构，展示局域网用量查询和规划中的远程会话链路](docs/diagrams/system-architecture.png)](docs/architecture.md)

| 文档 | 内容 |
| --- | --- |
| [局域网只读用量设计](docs/usage/README.md) | 网关接口、双端分工与验收用例 |
| [Kotlin Multiplatform 共享模块](shared/README.md) | 用量查询、分页、构建测试和 Swift 接入 |
| [移动端工程架构](docs/architecture.md) | 系统边界、双端工程结构、KMP 组件调用与用量数据流 |
| [移动端 UI 设计](docs/mobile-ui.md) | 页面地图、手机布局、用量详情与远程会话扩展线框 |
| [Git 工作流与发布规范](docs/git-workflow.md) | Magpie 上游实践、主干集成、双端验收和发布 |
| [工程原则（讨论稿）](docs/CONSTITUTION.md) | 跨端职责、接口权限和验收原则 |
| [Agent 工作约定](AGENTS.md) | 仓库改动、验证和协作约定 |

## 反馈与贡献

欢迎在 [GitHub Issues](https://github.com/AndyChen1024/magpie-mobile/issues) 报告问题或讨论功能。报告连接或用量问题时，请附上手机系统、Magpie 主机版本和复现步骤，并隐去网关密钥等敏感信息。

提交代码改动时，在提交正文记录受影响的平台和验证结果。使用 PR 时，注明验证过的提交。[Git 工作流](docs/git-workflow.md#pr-验收与-github-门禁)

## 远程会话的中继方案

远程续聊的首轮内测计划由项目方在 Fly.io 上托管 Paseo Relay。中继负责在手机与用户主机之间转发端到端加密消息，任务由主机上的 Agent 执行。局域网用量查询直接连接 Magpie 网关。[Paseo Relay 开源项目](https://github.com/getpaseo/paseo-relay)

## 许可证

项目代码采用 [MIT 许可证](LICENSE)。随 Android 工程分发的 Gradle Wrapper 遵循 Apache-2.0，其许可声明保留在启动脚本和 Wrapper JAR 中。

感谢 [Magpie](https://github.com/yetone/magpie) 及其贡献者。移动端沿用的上游资源保留了 [MIT 许可声明](magpie-android/app/src/main/assets/licenses/magpie.txt)。
