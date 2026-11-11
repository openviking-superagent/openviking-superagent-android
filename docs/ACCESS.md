# 远程访问与鉴权取舍

## 为什么不能直接填电脑的局域网 IP

`packages/web/src/server.mjs` 和 `packages/daemon/src/http-api.mjs` 都只接受 loopback 的 Host：

- 主机名必须是 `localhost`、`127.0.0.1` 或 `[::1]`
- 端口必须等于服务实际监听的端口
- 有 `Origin` 时还必须同源，但原生 App 不发 `Origin`

所以手机访问 `http://192.168.1.10:3737` 时，服务端看到 `Host: 192.168.1.10:3737`，会直接返回 403。这个检查是防 DNS rebinding 的，不是登录。

可选路径只有三类：

1. **访问网关**（`bridge/server.mjs`）：监听局域网/Tailscale 地址，校验 Bearer token，转发时把 Host 写成 `127.0.0.1:3737`。
2. **隧道**：SSH 本地转发或 Tailscale/WireGuard 提供的网络层隧道，让手机实际连到 `127.0.0.1:3737`。
3. **改服务端**：把 Web 绑到 `0.0.0.0` 并自己加认证和 Host 处理，再在前面放 TLS。需要改上游代码或反向代理，默认安装没有这层能力。

App 的“服务地址 + 访问令牌”对应第 1 类；令牌留空、地址填 `http://127.0.0.1:3737` 对应第 2 类。

## 网关

```bash
TOKEN=$(openssl rand -hex 24)
SUPERAGENT_BRIDGE_TOKEN="$TOKEN" node bridge/server.mjs \
  --target http://127.0.0.1:3737 \
  --host 0.0.0.0 \
  --port 8787
```

行为：

- 只允许 `GET /api/instances`、`GET /api/activity`、`GET /health`、`GET /ready`
- 没有令牌返回 401；令牌用 SHA-256 + `timingSafeEqual` 比较
- 不转发客户端 `Authorization`，避免令牌被上游日志记录
- 上游响应上限 8 MB，超时 15 秒
- `--host` 不是 loopback 且没有令牌时拒绝启动
- 不带 TLS，TLS 交给 Tailscale/WireGuard/反向代理

令牌可以放在环境变量里，避免出现在 shell history：

```bash
export SUPERAGENT_BRIDGE_TOKEN="$(openssl rand -hex 24)"
node bridge/server.mjs --target http://127.0.0.1:3737 --host 0.0.0.0 --port 8787
echo "$SUPERAGENT_BRIDGE_TOKEN"
```

### systemd（可选）

```ini
[Unit]
Description=SuperAgent mobile gateway
After=network-online.target

[Service]
Environment=SUPERAGENT_BRIDGE_TOKEN=replace-me
ExecStart=/usr/bin/node /opt/superagent-mobile/bridge/server.mjs --target http://127.0.0.1:3737 --host 0.0.0.0 --port 8787
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

网关本身没有守护进程管理，实例重启或升级不影响它；控制台端口变了就改 `--target`。

## SSH 隧道

在笔记本或服务器上：

```bash
ssh -N -L 3737:127.0.0.1:3737 user@server
```

手机通过 USB 时也可以用 ADB：

```bash
adb reverse tcp:3737 tcp:3737
```

然后 App 填 `http://127.0.0.1:3737`。本地端口建议保持 3737：Host 校验会比较端口，映射到 8787 会得到 403。如果一定要用别的本地端口，走网关而不是直接映射。

Android SSH 客户端做本地转发时同理：本地监听端口填 3737，目标填 `127.0.0.1:3737`。

## Tailscale / WireGuard

只把网关绑到 tailnet 地址，不绑 `0.0.0.0`：

```bash
tailscale ip -4
# 100.x.y.z
SUPERAGENT_BRIDGE_TOKEN="$TOKEN" node bridge/server.mjs \
  --target http://127.0.0.1:3737 \
  --host 100.x.y.z \
  --port 8787
```

手机在同一个 tailnet 里，App 填 `http://100.x.y.z:8787`。这样即使令牌在 HTTP 里明文传输，也只在 WireGuard 加密的链路里。

## TLS 反向代理

推荐代理到网关而不是直接代理到控制台，因为网关处理 token 和 Host：

```nginx
server {
    listen 443 ssl;
    server_name superagent.example.com;
    # ssl_certificate / ssl_certificate_key ...

    location / {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Authorization $http_authorization;
        proxy_read_timeout 30s;
    }
}
```

Caddy 2.8+：

```caddyfile
superagent.example.com {
    reverse_proxy 127.0.0.1:8787
}
```

App 填 `https://superagent.example.com`，令牌填网关的 token。不要把 8787 直接映射到公网。

如果非要直接把控制台代理出去，必须同时做两件事，缺一不可：

```nginx
proxy_pass http://127.0.0.1:3737;
proxy_set_header Host 127.0.0.1:3737;
```

再加一层认证（Basic Auth、OAuth、mTLS 等）。但 App 只会发 Bearer token，不会弹 Basic Auth 登录框，所以这种写法更适合浏览器，不适合本 App。

## 风险清单

- **内容敏感**：活动流包含聊天原文、模型思考、工具输入输出、文件路径。拿到接口就等于拿到这些内容。
- **令牌是共享密钥**：没有用户体系、没有轮换、没有速率限制。泄露后换 token 并重启网关。
- **明文存储**：App 的 token 在 SharedPreferences 里，没有硬件级加密。不要在共享手机上保存长期令牌。
- **HTTP 明文**：网关默认不带 TLS。公网必须加 HTTPS 或只走 Tailscale/WireGuard。
- **只读不等于无风险**：接口能读多个实例的完整执行历史，不要把网关暴露给不受信任的人。

如果以后控制台原生支持 token/远程访问，这个网关可以删掉，App 的地址和令牌字段仍然可用。
