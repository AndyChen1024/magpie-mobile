# 局域网只读用量 · 设计审阅稿

状态为待项目发起人确认。本文约定第一个真实功能，平台实施说明见 [iOS](ios.md) 和 [Android 与 KMP](android.md)，共同的完成条件见[验收用例](acceptance.md)。后续中继、续聊、审批与新建任务按各自的功能设计排期。

## 待确认的选择

| 建议 | 对使用和开发的影响 |
| --- | --- |
| 第一条真实路径为“连接一台 Mac → 查看概览 → 浏览分页请求”。 | 先接通现有用量页的两个标签。真实连接模式只显示已接入的数据和操作。已有演示页面可以继续作为明确标注的示例。 |
| 手动填写网关地址与已有网关密钥，首轮使用应用信任的 HTTPS。 | Mac 需开启局域网共享，证书须与输入的主机名或 IP 匹配。只有 HTTP、证书未受信或强制客户端证书的环境暂不在此方案的真机验收范围内。 |
| 两个开发对话并行，Android 对话同时负责第一个 KMP 模块。 | 两端可同时实现原生页面。共享模块可导入后，iOS 接上同一套查询与状态逻辑。 |

## 用户路径

1. 从用量页进入连接设置，输入 Mac 的网关地址和密钥。接受形如 `https://host:port` 或末尾附带 `/v1` 的地址，统一得到同一个 origin（协议、主机和端口）。路径歧义、URL 内的账号密码、查询参数及片段在发请求前提示修正。
2. 用户点“连接”后请求本地网络权限（适用时），打开默认的“今天”。概览受限时保留“请求”入口。主机信息显示在用量页，重新配置地址或密钥会重新查询。
3. 用户可切换今天、最近 7 天、最近 30 天，查看调用数、错误数、输入与输出 token 和已知费用。请求页按服务端顺序展示时间、Agent、模型，以及可用的状态。翻页失败可重试当前页。图表、分组和搜索可随后扩展，真实模式不以演示值填充。
4. 用户可以断开并清除已保存连接。密钥绑定到对应 origin，使用平台安全存储，换主机需提供该主机的凭据。移除、改密钥或主机切换时清除旧查询状态。网关密钥只发给已配置的 origin，拒绝重定向至其他 origin 和协议降级。正式连接使用平台的 TLS 信任校验。[上游局域网接入](https://github.com/yetone/magpie/blob/426a707a19ff7f481989f03c49519bf4dba1e593/docs/integrating.md#L50) · [上游 HTTPS 配置](https://github.com/yetone/magpie/blob/426a707a19ff7f481989f03c49519bf4dba1e593/internal/gateway/https.go#L22)

## 网关契约

核对版本为 Magpie `426a707a1`。手机使用已有网关密钥，通过 `Authorization: Bearer <key>` 认证。无需管理权限。查询显式携带 `period=today|7d|30d`；日期窗口由主机计算。比较结果时采用同一主机、时段和授权范围。[路由与授权](https://github.com/yetone/magpie/blob/426a707a19ff7f481989f03c49519bf4dba1e593/internal/gateway/usage_api.go#L14) · [时间范围](https://github.com/yetone/magpie/blob/426a707a19ff7f481989f03c49519bf4dba1e593/internal/usage/usage.go#L540)

| 请求 | 客户端读取的语义 |
| --- | --- |
| `GET /v1/magpie/usage?period=7d` | 概览字段位于 JSON 顶层。`calls`、`errors` 是主机汇总，token 总数按 `input + output`。费用 `cost` 单位为美元，`unpriced > 0` 时注明有请求缺少价格。零费用和未知费用分别呈现。 |
| `GET /v1/magpie/usage/requests?period=7d&offset=0&limit=50` | `rows` 是该页，`offset` 是服务端返回的起点，`total` 是匹配的记录数。下一页从 `offset + rows.count` 开始，到达总数或遇到空页时停止。手动刷新从第一页替换。行时间字段为 `t`，优先显示 `agentName`，可回退到 `agent`；展示 `model`。缺少有效 HTTP 状态的记录显示“未提供”。单条记录只有 `priced=true` 才展示其 `cost`。 |
| 汇总返回 `403` 且 `error.code=gateway_key_held` | 概览提示“此密钥只能查看自己的请求”，允许打开请求页，由服务端限定该密钥的记录。其他 `403` 说明访问被拒，`401` 提示检查密钥，TLS 错误、网络错误和无法解析的响应分别给出对应提示。 |

汇总和明细各自保留加载、成功、空、拒绝和失败状态。解析必需字段失败时显示数据错误。发现新字段可继续读取已知字段。分页失败时保留已加载行，对同一次加载操作只追加一次。数据源正在变化时，offset 分页可能移动，用户可刷新重读。[汇总字段与计价](https://github.com/yetone/magpie/blob/426a707a19ff7f481989f03c49519bf4dba1e593/internal/usage/usage.go#L561) · [分页结构](https://github.com/yetone/magpie/blob/426a707a19ff7f481989f03c49519bf4dba1e593/internal/usageapi/usageapi.go#L275) · [上游授权测试](https://github.com/yetone/magpie/blob/426a707a19ff7f481989f03c49519bf4dba1e593/internal/gateway/usage_api_test.go#L93)

## 两端交接

| 负责方 | 可独立推进 | 交接物 |
| --- | --- | --- |
| Android 对话 | 维护 `magpie-android/`，在仓库根建立拟议的 `shared/` 模块，由现有 Gradle Wrapper 构建。实现共享查询、解析、状态转换、脱敏样例和共用测试。 | Android 可调用的模块、设备与模拟器可用的 iOS framework、可复现构建命令、Swift 导入名与最小调用样例。报告所在工作区或修订版本，便于 iOS 读取同一份实现。 |
| iOS 对话 | 维护 `magpie-ios/`，先按下列行为做 SwiftUI 展示、平台凭据存储和可注入的展示样例。 | 在 Xcode 侧接入共享 framework，验证实际 Swift 接口、取消订阅和主线程更新，并反馈遇到的调用差异。 |

共享接口以行为为准。原生端提供选中的连接及安全读取凭据的能力，发出连接、选时段、打开标签、重试、加载下一页和断开操作。共享层提供当前主机标识、时段、概览状态、明细状态与是否可翻页。凭据仅用于发出请求。切换主机、密钥或时段时令旧请求失效，即使其回调先后顺序发生变化。原生层决定导航、弹层、文本格式与权限交互。共享 API 的具体 Kotlin/Swift 签名随首个双端编译样例交接。

两个对话开始前先确认能读到本稿和当前原型。文档及部分原型在编写本稿时仍是未提交的工作区内容。若使用独立检出，先同步同一份起点。并行期间按目录分工；共享契约的改动集中记录在本页，告知另一端后再使用新语义。
