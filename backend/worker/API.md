# Firefly VPN Edge API

本文档描述纯匿名设备身份模式的后端接口。项目不提供邮箱账号注册、邮箱绑定、密码登录或账号资料接口。

## 通用约定

- API 前缀：`/api/v2`。
- JSON 响应均使用 UTF-8，并携带 `Cache-Control: no-store`。
- 成功响应：`{"ok":true,"data":...}`。
- 失败响应：`{"ok":false,"error":"<machine-code>","requestId":"..."}`。
- 请求可传 `X-Request-ID`；缺失或不合法时由服务端生成。
- JSON 请求必须使用 `Content-Type: application/json`，默认限制 64 KiB。

设备认证接口必须同时携带：

```http
Authorization: Bearer <device-token>
X-Firefly-Device-ID: <64 位小写 hex device id>
```

Device Token 只在首次注册和凭据轮换成功时返回，服务端只保存 SHA-256 Base64URL 哈希。

## 公共接口

### `GET /health`

返回 Worker 健康状态，不检查外部订阅源。

### `GET /api/v2/bootstrap`

返回 Crypto V2 版本、算法、公告、双端应用更新和公开运行设置。服务端内部的订阅拉取超时不会下发。

- `appUpdate`：移动端更新配置，关闭时为 `null`。
- `pcAppUpdate`：PC 端更新配置，关闭时为 `null`。

两者启用时使用相同结构：

```json
{
  "versionCode": 34,
  "versionName": "3.4.0",
  "downloadUrl": "https://example.com/firefly-setup.exe",
  "force": false,
  "changelog": "本次更新说明"
}
```

`versionCode` 是用于比较大小的非负整数；`versionName` 是展示版本号；`downloadUrl` 是安装包地址；`force` 表示是否强制更新；`changelog` 是更新说明。PC 端读取 `data.pcAppUpdate`，移动端继续读取原有的 `data.appUpdate`，因此旧移动端接口保持兼容。

### `POST /api/v2/devices/enroll`

首次匿名设备注册。请求：

```json
{
  "deviceId": "<64-lowercase-hex>",
  "platform": "android",
  "deviceName": "Pixel",
  "publicKey": "<P-256-SPKI-base64url>",
  "cryptoVersion": 2
}
```

`platform` 可为 `android`、`windows`、`macos`。`deviceId` 携带客户端硬件身份材料的 SHA-256。首次创建返回 `201` 和 Device Token；已存在设备且公钥相同时必须携带原 Token。卸载重装产生新公钥时，服务端先执行账号/设备封禁检查；状态正常才会原子重新绑定公钥和 Token、返回 `200` 与新 Token，旧 Token 立即失效。请求与响应字段保持不变。

## 设备认证接口

### `GET /api/v2/accounts/me`

返回当前用户账号及安全设备摘要，不包含邮箱、密码或完整密钥材料。

### `DELETE /api/v2/accounts/me`

删除当前匿名身份：account 标记为 `deleted`，其全部设备标记为 `revoked`，并清理设备 Replay Challenge。操作完成后所有原 Device Token 立即无法通过认证。

### `GET /api/v2/devices`

返回当前匿名归属下的设备安全摘要。不会返回完整公钥、Device Token 或 Token 哈希。

### `POST /api/v2/devices/:deviceId/revoke`

撤销同一匿名归属下的其他设备。目标设备必须属于当前 account，且不能是当前请求设备。

### `POST /api/v2/devices/:deviceId/rotate-key`

目标 ID 必须等于认证设备 ID。请求：

```json
{"publicKey":"<new-P-256-SPKI-base64url>"}
```

服务端原子更新公钥和 Token 哈希，清除该设备的 Replay Challenge，并返回一次性新 Token。旧 Token 立即失效。

### `GET /api/v2/subscriptions`

返回所有启用订阅源的安全目录，不暴露 external upstream URL 或订阅正文。

### `GET /api/v2/subscriptions/:id/content`

除设备认证头外还必须携带：

```http
X-Firefly-Crypto-Version: 2
X-Firefly-Challenge: <16-byte-base64url>
```

Challenge 对每台设备只能使用一次。响应是 `P256-HKDF-SHA256-A256GCM` Envelope，TTL 为 300 秒；订阅正文不会通过其他客户端接口返回。

### `POST /api/v2/usage/report`

```json
{
  "sessionId": "unique-session-id",
  "uploadBytes": 123456,
  "downloadBytes": 789012
}
```

以 `deviceId + sessionId` 幂等。身份归属只取认证上下文，忽略客户端伪造的 accountId；日/月周期使用 `Asia/Shanghai`。

## 管理接口

管理页面入口为 `/<ADMIN_ROUTE>/`，管理 API 前缀为 `/<ADMIN_ROUTE>/api`。先使用 Admin Token 登录：

```http
POST /<ADMIN_ROUTE>/api/session/login
Authorization: Bearer <ADMIN_TOKEN>
```

登录成功返回有效期 30 天的 HS256 JWT。除登录接口外，全部管理 API 要求：

```http
Authorization: Bearer <JWT>
```

JWT 绑定当前 Admin Token 配置并带唯一 `jti`；修改 Admin Token、修改 JWT 私钥或主动登出后，对应旧会话失效。

### 概览与 Analytics

- `GET /dashboard`：账号、设备、今日/月度流量及连续 30 日流量。
- `GET /analytics?days=30&months=12`：每日活跃/新增账号、每日/月度流量、累计流量和平台设备分布。`days` 范围 1–90，`months` 范围 1–24。

### 用户账号

- `GET /accounts`
- `GET /accounts/:id`
- `POST /accounts/:id/ban`
- `POST /accounts/:id/unban`

账号响应不包含邮箱或密码字段。封禁后该账号下所有设备认证立即失败；写操作进入审计日志。

### 设备

- `GET /devices`
- `POST /devices/:id/ban`
- `POST /devices/:id/unban`
- `POST /devices/:id/revoke`

设备响应只包含短公钥指纹，不包含完整公钥或 Token 信息。

### 订阅源

- `GET /subscriptions`
- `GET /subscriptions/:id`
- `POST /subscriptions`
- `PATCH /subscriptions/:id`
- `DELETE /subscriptions/:id`
- `POST /subscriptions/:id/test`
- `POST /subscriptions/:id/refresh`

订阅源分为 `sourceType: "managed"`（托管）与 `sourceType: "external"`（外源 + 托管）。托管模式必须有非空 `managedContent`，不使用 `sourceUrl`；组合模式必须有无用户名/密码的 HTTPS `sourceUrl`，`managedContent` 可省略或用空字符串/`null` 清空。两种模式可互相切换；切换为组合模式时保留的托管正文也须符合合并格式。新建时若不传 `sourceType`，由是否提供 `sourceUrl` 推断模式。组合模式可传 `mergeMode`：`external_first`（默认）、`managed_first`、`interleave_external_first` 或 `interleave_managed_first`，分别对应订阅在前、托管在前和两种交替顺序；没有托管内容时保持外源原文。合并支持 URI 列表或 Base64 节点订阅，保留外源编码；协议识别覆盖 VMess、VLESS、Trojan、Shadowsocks、Hysteria/Hysteria2（含 `hy2`）、TUIC、AnyTLS、ShadowTLS、Snell、WireGuard、SSH、SOCKS、HTTP(S) CONNECT、Naive 等 sing-box 主流协议。Clash YAML/JSON 等格式可作为纯托管或单独外源正文，不能与托管节点混合。合并后总大小限制为 2 MiB。

托管正文保存在 KV；外源拉取启用超时、2 MiB 响应限制、HTML/错误页检测及分层缓存。网络/超时以及 HTTP 408、429、5xx 会在 500 ms、1500 ms 退避后最多重试两次，三次均失败后才更新异常状态或进入故障回退。外源正文与刷新时间共用一个带 metadata 的 KV 键，内容仍按订阅源 TTL 刷新，常规 KV 持久化最多每小时一次；运行配置和正文的重复读取优先命中实例缓存或数据中心本地 Cache API。组合模式可传 `externalHealthEnabled` 单独控制健康监测与故障回退，新建时默认为 `true`；启用后每小时检查，普通拉取失败时使用最近一次成功内容。列表会返回 `externalHealthStatus`、最近检测/成功时间、错误码和 `externalFallbackActive`。订阅源可保存最多 200 个字符的可选备注。列表、创建、修改响应和审计日志不返回托管正文；仅管理员读取单条详情时返回已配置的 `managedContent`，供编辑回填。`POST /subscriptions/:id/refresh` 仅适用于组合模式，强制刷新外源并返回合并内容摘要，不使用故障回退。

### 运行设置与审计

- `GET /settings`
- `PUT /settings`
- `PUT /settings/admin-access`：可更新访问路径、Admin Token 与 JWT 私钥；JWT 私钥长度为 32–128 个字符且不会通过 API 回显，未手动设置时自动生成 UUID。
- `POST /session/login`：校验 Admin Token 并记录登录操作与访问 IP。
- `POST /session/logout`：记录登出操作与访问 IP。
- `GET /audit?page=1&pageSize=20&from=<ISO时间>&to=<ISO时间>&action=<操作>&requestId=<请求标识>&targetId=<目标标识>`。
- `DELETE /audit`：请求体使用 `{ "from": "<ISO时间>", "to": "<ISO时间>" }` 按范围清理（开始、结束允许只传一项），或使用 `{ "all": true }` 全部清理。

审计列表支持 5–100 条/页；时间、操作、请求标识和目标标识均为可选条件，请求标识与目标标识支持部分匹配。新增日志同时保存访问 IP 和 IP 哈希，审计接口受 JWT 保护；升级前的历史记录只显示 IP 哈希。管理控制台还可将当前筛选条件下的全部匹配记录导出为 UTF-8 CSV，或按时间范围/全部清理。清理动作自身会作为一条新审计记录保留。

控制台使用分组表单维护公告、更新配置、公开链接与 external 拉取超时。

管理访问参数请求示例：

```json
{
  "accessPath": "new-private-console-path",
  "newAdminToken": "new-token-123"
}
```

`accessPath` 长度为 8–128，只允许字母、数字、下划线和连字符。`newAdminToken` 可省略以保留现有 Token；提交新 Token 时长度为 8–16。服务端在 KV `admin:access:v1` 中只保存 Token 的 SHA-256 Base64URL 哈希，API 永不回显 Token。修改后旧路径或旧 Token 立即失效，环境变量仅作为尚未配置 KV 覆盖时的回退；历史较长的环境变量 Token 仍可用于登录和轮换。

遗失运行时凭据时，管理员可通过 Wrangler 删除 KV `admin:access:v1`，恢复环境变量中的路径与 Token。

`POST /ai/insights` 是可选功能；当前未配置实现时返回 `feature_disabled`。

## 常见错误码

- `invalid_request`：JSON、字段或请求格式不合法。
- `invalid_device_id` / `invalid_public_key` / `invalid_challenge`。
- `unauthorized`：Token 或设备身份不匹配。
- `account_banned` / `account_deleted`。
- `device_banned` / `device_revoked` / `device_conflict`（并发重新绑定冲突）。
- `subscription_not_found` / `subscription_unavailable` / `unsupported_subscription_format`（混合内容不是兼容的节点 URI 或 Base64 格式）。
- `unsupported_crypto_version` / `replay_detected` / `rate_limited`。
- `payload_too_large` / `upstream_failed` / `not_found` / `internal_error`。

## 后台维护

Wrangler Cron 每小时第 17 分钟清理过期的 `crypto_replay_nonces` 和 D1 限流窗口。管理登录使用 D1 原子计数以支持动态开关和次数；设备相关生产限流使用 Cloudflare Rate Limiting Binding，本地测试在缺少原生绑定时回退到 D1。设备活跃时间每 15 分钟至多落库一次。数据库结构只通过 `database/migrations` 变更，Worker 启动期间不会建表或改表。
