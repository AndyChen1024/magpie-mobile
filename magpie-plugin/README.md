# Magpie 连接手机

这是 macOS Magpie 的本地插件。第一版提供配对、授权设备列表、撤销和经中继查询 Mac 显示名称。插件和参考客户端复用 Paseo 0.16.2 的 v2 中继路由与 protected 加密通道。Mac 端用设备的 Ed25519 签名核验授权，并在每次读取时重新检查权限。

## 安装和打开

1. 用 Node.js 和 npm 准备自包含的插件目录。在 `magpie-plugin/` 中运行：

   ```sh
   npm ci --ignore-scripts
   npm run package
   ```

2. 把 `dist/` 放在会长期保留的位置，用 Magpie CLI 安装该本地目录。CLI 记录路径，运行时从该目录加载插件。

   ```sh
   /Applications/magpie.app/Contents/MacOS/magpie plugin add "$PWD/dist"
   ```

3. 启动本地中继后，在 Magpie 侧栏打开「连接手机」，点击「开启后台连接」。显示「已连接」后即可生成配对码。配对码有效期两分钟，生成新码会替换旧码。
4. 停用前在插件页面点击「关闭后台连接」，再到 Magpie「插件」停用或移除。后台任务由用户的 macOS LaunchAgent 管理，关闭窗口或退出 Magpie 后继续运行。关闭后台任务后会保留设备列表，重新开启后可以继续使用原授权。若已经移除了插件，可用原目录重装并关闭后台任务。

第一次开启后台连接会注册一个用户级 LaunchAgent。它使用此版本 Magpie 运行插件所用的 Bun，插件目录或 Bun 路径改变后可关闭再开启以更新任务。桌面管理页面默认使用 `127.0.0.1:18647`。如有端口冲突，退出当前隔离实例后通过 `magpie plugin options <本地插件路径> '{"port":18657}'` 设置空闲端口。授权与主机密钥位于 `magpie data` 所示文件目录的 `connect-phone/` 中，目录权限为 `0700`，文件权限为 `0600`。

## 本地复现

以下命令从 `magpie-plugin/` 执行。测试使用 Docker Desktop、两个独立的设备身份和一个隔离的 Magpie 配置。普通安装也可以只启动下列中继，再从自己的 Magpie 打开插件。

### 启动中继

已有 `magpie-paseo-relay-e2e:5f4b7636-arm64` 镜像时可直接使用。全新环境在 Apple Silicon 上按已验证的 Dockerfile 从固定官方提交构建：

```sh
export PLUGIN_DIR="$PWD"
export PHONE_LAB="$(mktemp -d /private/tmp/magpie-phone.XXXXXX)"
chmod 700 "$PHONE_LAB"
mkdir "$PHONE_LAB/relay"
relay_rev=5f4b7636af41a85ef20200c0b7e99dd1ea2ee1f2
curl -fLsS "https://codeload.github.com/getpaseo/paseo-relay/tar.gz/$relay_rev" \
  | tar -xz --strip-components=1 -C "$PHONE_LAB/relay"
docker build --platform linux/arm64 -f "$PLUGIN_DIR/tools/relay.Dockerfile" \
  -t magpie-paseo-relay-e2e:5f4b7636-arm64 "$PHONE_LAB/relay"
docker run --rm -d --name magpie-phone-lab -p 127.0.0.1:18400:4000 \
  magpie-paseo-relay-e2e:5f4b7636-arm64
curl --noproxy '*' -fsS http://127.0.0.1:18400/ready
```

### 使用隔离的 Magpie

在另一个终端导出同一个 `PHONE_LAB` 和 `PLUGIN_DIR`。复制应用可以在自己的 Magpie 已打开时使用独立的应用标识。在本地 App 副本上使用临时签名：

```sh
mkdir -p "$PHONE_LAB"/{home,config,data,state,cache,codex}
ditto /Applications/magpie.app "$PHONE_LAB/Magpie Phone Lab.app"
/usr/libexec/PlistBuddy -c 'Set :CFBundleIdentifier com.magpie.phone.lab' \
  "$PHONE_LAB/Magpie Phone Lab.app/Contents/Info.plist"
codesign --force --sign - "$PHONE_LAB/Magpie Phone Lab.app"
(
  export HOME="$PHONE_LAB/home" XDG_CONFIG_HOME="$PHONE_LAB/config"
  export XDG_DATA_HOME="$PHONE_LAB/data" XDG_CACHE_HOME="$PHONE_LAB/cache"
  export XDG_STATE_HOME="$PHONE_LAB/state" CODEX_HOME="$PHONE_LAB/codex"
  export MAGPIE_NO_ACCOUNTS=1 MAGPIE_ADDR=127.0.0.1:18654
  magpie_lab="$PHONE_LAB/Magpie Phone Lab.app/Contents/MacOS/magpie"
  "$magpie_lab" plugin add "$PLUGIN_DIR/dist" &&
  "$magpie_lab" app
)
```

若使用已有的 Bun，可在子 shell 里设置 `MAGPIE_BUN` 为其绝对路径。复现重启时可再运行同一子 shell，已存在的配置与授权目录会继续使用。也可在隔离配置中运行同版本的 `magpie web --addr 127.0.0.1:18645 --no-open` 验证页面代理路径，登录链接只在本机打开。

### 配对、重连和撤销

打开「连接手机」，开启后台连接并等待「已连接」。点击「生成配对码」，在页面选中并复制配对链接。用不回显的输入写进临时文件：

```sh
umask 077
printf '粘贴配对链接后按回车 '
IFS= read -r -s phone_pair_link
printf '\n'
printf '%s\n' "$phone_pair_link" > "$PHONE_LAB/pair-link"
unset phone_pair_link
node tools/test-client.mjs "$PHONE_LAB/phone-a.json" pair "$PHONE_LAB/pair-link" "Test phone A"
rm -f "$PHONE_LAB/pair-link"
```

成功时客户端输出 protected 模式、请求 ID 和由 Mac 生成的回执。用原设备文件反复读，每次生成新的请求 ID 和回执：

```sh
node tools/test-client.mjs "$PHONE_LAB/phone-a.json" read
```

- 切到 Magpie 的其他页面后再读一次，核对 ID 和回执变化。需要在本机终端查看名称时，可给 `read` 附加 `--show-name`。
- 退出并重启 Magpie，在重新打开插件前再读一次。原设备文件即可重连。
- `docker stop magpie-phone-lab` 后，页面显示「未连接」，客户端读失败。重新执行上面的 `docker run` 并等页面显示「已连接」，再用原文件读。
- 可生成另一个配对码，将 `phone-a.json` 和设备名换成另一组。回到原页面撤销 A，再用 A 的原文件读，预期退出码为 1，返回 `device_not_authorized`。B 仍可读取。
- 测完后在页面关闭后台连接，退出隔离 Magpie 并停止该测试容器。临时目录含设备私钥和本地测试身份，完成后删除该目录。

## 已验证环境

| 组件 | 本地验证版本 |
| --- | --- |
| Magpie | macOS 0.1.4759，发布提交 `95e6a4180866afde186e0f1a25369388c7fd7e7f` |
| 插件与运行时 | 0.1.0，Bun 1.2.18，参考客户端 Node.js 24.15.0 |
| Paseo | relay/e2ee 与 protocol 0.16.2，上游发布提交 `2ee30f4164af42ba209236ab3a31bf9532592fc3` |
| Elixir 中继 | 官方提交 `5f4b7636af41a85ef20200c0b7e99dd1ea2ee1f2`，release 0.1.0，Linux ARM64，Elixir 1.20.2 / Erlang 29.0.2，Docker 29.8.2 |

Magpie 的页面服务在加载页面时可拉起插件宿主。隔离探针销毁 iframe 后，宿主仍能经中继回答新请求。插件宿主按需启动并随 Magpie 进程结束，首版用独立后台任务保障退出及重启期间的访问。[Magpie v0.1.4759 页面服务](https://github.com/yetone/magpie/blob/95e6a4180866afde186e0f1a25369388c7fd7e7f/internal/gui/pluginpage.go)

Paseo 的 protected 握手验证主机公钥，客户端握手密钥是临时的。原 daemon 使用共享口令或本机凭据准入，准入身份是 owner。首版伴随服务直接复用同版本的端到端加密库和中继，提供独立的设备准入与主机名称接口。Paseo daemon 0.16.2 的原生接口在本轮用于源码核对。[Paseo 握手实现](https://github.com/getpaseo/paseo/blob/2ee30f4164af42ba209236ab3a31bf9532592fc3/packages/relay/src/secure-channel.ts) · [Paseo 准入实现](https://github.com/getpaseo/paseo/blob/2ee30f4164af42ba209236ab3a31bf9532592fc3/packages/server/src/server/session-admission-auth.ts)

| 项目 | 本地验收结果 |
| --- | --- |
| 首次配对 | 参考客户端读取同一页面的配对链接，配对后返回与系统 ComputerName 相符的名称，用过的配对码被拒绝。 |
| 关闭页面后读取 | 原生窗口切离页面及 Web 界面销毁 iframe 后均收到新回执。最终打包版的首次读取请求为 `445154f7…`，销毁页面后为 `c8ac4bfc…`。 |
| 重启恢复 | 退出及重启原生 Magpie 后、再次打开页面前，原设备均可读取。后台任务进程单独重启后仍保留设备授权和撤销结果。 |
| 中继恢复 | 停止容器时页面显示「未连接」且客户端读失败。恢复容器后页面显示「已连接」，原设备无需重新配对。 |
| 设备撤销 | 从页面撤销 A 后，A 的当前连接和新连接都收到 `device_not_authorized`，B 仍可读取。 |

## Android 接口

扫码解析 `magpie-mobile://pair#offer=…&ticket=…`。`offer` 是 Base64URL 编码的 UTF-8 JSON，采用 Paseo offer v2。保存 `serverId`、`daemonPublicKeyB64`、`relay.endpoint`、`relay.useTls`、`minimumSecurityMode` 和设备 Ed25519 私钥。配对完成后丢弃单次 `ticket`。设备私钥使用 Android 安全存储，局域网用量凭据继续由现有模块管理。

1. 通过 `buildRelayWebSocketUrl` 等价实现连接中继的 `/ws`，使用 `role=client`、`serverId`、`v=2`。要求 Paseo protected，校验配对给出的 Mac 公钥。加密通道建立后，服务端发送带 `serverId` 和随机 `nonce` 的 `challenge`。
2. 初次发送 `device.pair`，字段为 `v: 1`、`id`、`ticket`、`label`、`publicKey`、`signature`。重连时对新挑战发送 `device.auth`，字段为 `v: 1`、`id`、`publicKey`、`signature`。公钥为 Ed25519 原始 32 字节的无填充 Base64URL，签名为 64 字节的同种编码。
3. 使用设备私钥签名 UTF-8 文本。依次用 LF 拼接 `magpie-connect-phone/v1`、`serverId`、`nonce`、操作名、`publicKey`、`ticket`。重连时最后一项为空，因此签名文本以 LF 结尾。
4. 授权回执返回 `deviceId`。然后发送 `{"v":1,"type":"host.get","id":"每次新建"}`，核对回执中的同一 `id`、`hostId`、`displayName`、`receipt`。收到 `device_not_authorized` 时提示需要重新配对。参考实现见 [协议](protocol.mjs) 和 [客户端](tools/test-client.mjs)。

### 已配对设备的用量

Mac 的「已授权设备」列表可单独开启或关闭每台设备的用量访问。已有设备默认关闭。生成配对码前可为本次新设备勾选「允许本次配对的新设备查看用量」，生成后复位。用同一设备密钥重新配对会保留现有权限，完整撤销设备会清除权限。

已通过 `device.pair` 或 `device.auth` 的手机，可在同一 protected 会话发送 `usage.summary`（顶层 `period`）或 `usage.requests`（顶层 `period`、`offset`、`limit`）。`period` 取 `today`、`7d`、`30d`，请求明细从 `offset: 0` 开始，传 `limit: 50`。成功返回原 `type: "result"` 信封中的 `{usage: {...}}`。明细保留 `offset`、`total` 和 `rows`，用 `offset + rows.length` 计算下一页。为适应 Android 的 protected 帧限制，单页可能少于 50 条。

| 错误 | 含义 |
| --- | --- |
| `device_not_authorized` | 设备会话未获授权、已撤销或被重新配对替换 |
| `usage_permission_denied` | 设备用量访问关闭，或查询期间权限被撤销 |
| `usage_unavailable` | 当前本机网关不可用，或响应不符合契约 |
| `invalid_request` | 已授权的查询参数不合法 |
| `unsupported_request` | 旧版 Mac 插件未实现此协议 |

插件只向回环网关发出 `GET /v1/magpie/usage` 和 `GET /v1/magpie/usage/requests`。以 Magpie 插件宿主给出的 `MAGPIE_ADDR` 为准，缺省为 `127.0.0.1:3425`。本机保存的地址随页面服务初始化更新，切换端口或预设后应打开对应实例的「连接手机」页面。手机直连的手动 HTTPS 用量入口由 Android 独立处理。

真实验收时，在隔离的 Magpie 实例加载本轮插件包，关闭再开启其后台连接以运行新版 companion。使用 Android 真机可达的测试中继与隔离设备身份，检查旧设备默认拒绝、打开后能加载概览及连续两页明细、关闭后刷新被拒绝、保留密钥重新配对后权限保持，以及完整撤销后会话和重连均被拒绝。对照同一 Magpie 网关、时间范围和稳定的测试用量记录，并检查配置非默认回环端口后仍查询对应实例。

下一项真机验收先让 Mac 的配对 offer 使用 Android 真机可达的测试中继地址。扫码一次，关闭 Mac 插件页，杀掉并重开 Android App，用同一设备身份重新拿到新回执。再验证 Mac 撤销后当前连接和重连都被拒绝。手机蜂窝切换需要两端可达的中继，本轮中继只绑定 Mac 的回环地址。
