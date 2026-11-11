# SuperAgent 手机端（Android）

在手机上看 OpenViking SuperAgent 的执行过程。数据来自 SuperAgent Web 控制台已有的两个只读接口：`/api/instances` 和 `/api/activity`。

桌面控制台按回合展示“输入 → 思考 / 工具调用 → 输出”，这个 App 在手机上做同样的事：收到的消息、模型实际收到的输入、思考片段、工具参数与输出、最终回复，以及服务状态和系统事件。

## 能做什么

- 按回合分组的执行流，和桌面控制台一致
- 展开工具调用的输入、输出、错误，长文本可复制
- 实时轮询当前会话，有新回合自动滚动；离开底部后出现“最新”
- 切换当前会话、历史会话或全部会话
- 多实例切换（`/api/instances` 返回多个时）
- 服务状态、今日收发量、需关注数量、系统事件
- 深色 / 浅色跟随系统
- 地址和令牌只保存在本机

只读。App 不会发消息、点审批、改文件，也没有任何写接口。

## 安装

从 [Releases](https://github.com/openviking-superagent/openviking-superagent-android/releases) 下载 `SuperAgent-Mobile-debug.apk`，或从 Actions 的 `superagent-mobile-debug-apk` 工件下载。

```bash
adb install SuperAgent-Mobile-debug.apk
```

要求 Android 8.0（API 26）及以上。APK 使用调试签名，方便直接侧载；重新构建后如果要覆盖安装，可能需要先卸载旧版本。

## 连接

控制台默认只监听 `127.0.0.1:3737`，并且服务端会校验 Host。手机直接用局域网 IP 访问会得到 403，这不是 App 的问题。两种可行方式：

### 方式 A：访问网关（host + token，推荐给手机）

不需要隧道。在运行 SuperAgent 的电脑上，从本仓库运行一个只读网关，手机直接连它：

```bash
# 生成一个共享令牌
TOKEN=$(openssl rand -hex 24)

SUPERAGENT_BRIDGE_TOKEN="$TOKEN" \
  node bridge/server.mjs \
  --target http://127.0.0.1:3737 \
  --host 0.0.0.0 \
  --port 8787

echo "token: $TOKEN"
```

手机和电脑在同一局域网（或同一个 Tailscale 网络）时，App 里填：

- 服务地址：`http://<电脑的局域网或 Tailscale IP>:8787`
- 访问令牌：上面生成的 `$TOKEN`

网关只转发 `GET /api/instances`、`/api/activity`、`/health`、`/ready`，并把 Host 改写成 `127.0.0.1:3737`，所以控制台不需要改监听地址。`--host 0.0.0.0` 不带令牌会拒绝启动。

不想每次敲参数就用配置文件：

```bash
cp bridge/config.example.json bridge/config.local.json
chmod 600 bridge/config.local.json
# 改 host / port / token
node bridge/server.mjs --config bridge/config.local.json
```

`--token-file` 可以从单独的文件读令牌；CLI 参数覆盖环境变量，环境变量覆盖配置文件。

### 方式 B：SSH 隧道（备选）

如果机器已经开了 SSH，也可以把控制台的**原端口**映射到手机或内网：

```bash
ssh -N -L 3737:127.0.0.1:3737 <user>@<server>
```

Android 上的 SSH 客户端（Termux、JuiceSSH 等）也可以做本地端口转发。然后 App 填 `http://127.0.0.1:3737`，令牌留空。

端口要保持 3737，因为控制台的 Host 校验同时检查主机名和端口。更多反向代理写法见 [docs/ACCESS.md](docs/ACCESS.md)。

## 安全边界

控制台本身没有登录，只靠 loopback + Host 校验挡住浏览器里的跨站请求。把它暴露到网络上必须自己加一层访问控制：

- 网关的令牌是共享密钥，只走 HTTP 时不加密。仅用于可信内网、Tailscale/WireGuard 或 SSH 隧道；不要把 8787 直接映射到公网。
- 令牌以明文保存在 App 的 SharedPreferences 里，没有使用 Android Keystore。手机丢失或共享设备上要注意。
- 网关只读、方法限 GET、响应上限 8 MB，不转发 Authorization 头，也不会把令牌写进日志。
- App 允许明文 HTTP（`usesCleartextTraffic`），这是为了本地隧道；公网部署请在前面加 HTTPS。

## 构建

需要 JDK 17 和 Android SDK 35。没有本机 SDK 时直接用 GitHub Actions。

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

推 `v*` 标签会触发 Release 工作流，把 APK 附到 GitHub Release：

```bash
git tag v1.0.0
git push origin v1.0.0
```

## 数据契约

App 只依赖控制台已经公开的 JSON 字段，不读 daemon 数据库：

```text
GET /api/instances
  -> { defaultInstance, instances: [{ id, label }] }

GET /api/activity?instance=<id>&epoch=current&limit=100[&before=<id>][&after=<id>]
  -> { service, overview, sessions, epoch, activities, nextCursor, hasNewer }
```

回合分组逻辑在 `app/src/main/java/ai/openviking/superagent/mobile/model/ActivityModel.kt`，对照 `packages/web/public/activity.js` 移植。遇到未知字段会忽略，旧版本缺少 `turn_started` 时也能补出隐式回合。

## 目录

```text
app/                       Android 客户端（Kotlin + Jetpack Compose）
bridge/server.mjs          只读访问网关，无第三方依赖
docs/ACCESS.md             隧道、反向代理和鉴权取舍
.github/workflows/         GitHub Actions：单测、APK、Release
```

## 已知限制

- 轮询间隔 1.5 秒，不是 websocket；后台时轮询仍会继续，省电策略可能延迟。
- 单条工具详情最多显示 3 万字，复制按钮取全文。
- 不支持消息内的 Markdown 渲染、图片预览、审批操作。
- 控制台以后如果改接口字段，App 需要跟着更新。

## License

AGPL-3.0，与 OpenViking SuperAgent 保持一致。
