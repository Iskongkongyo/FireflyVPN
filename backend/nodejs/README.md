# Firefly VPN Backend

> 本项目运行于 Node.js 20+，完整 Debian/Ubuntu 部署步骤见 [`DEPLOY_NODE.md`](./DEPLOY_NODE.md)。

FireflyVPN 的 TypeScript/Hono 后端，使用本地 SQLite 持久化业务与键值数据，并将 Crypto V2 协议隔离在独立安全模块中。

当前版本采用纯匿名设备身份模式，已经包含：

- 模块化 Node.js 入口、路由、请求 ID、CORS 和统一 JSON 错误。
- `GET /health` 与 `GET /api/v2/bootstrap`。
- `POST /api/v2/devices/enroll` 匿名设备注册。
- 每设备独立 Device Token、Token 哈希存储及统一设备认证器。
- 基于 SQLite 原子计数的管理登录、注册 IP/deviceId 与已认证设备限流、64 KiB JSON 上限和严格输入校验。
- 自动、幂等执行的 SQLite schema migration。
- SQLite 键值存储、运行时配置模型与安全默认配置。
- Crypto V2 Base64URL、P-256 SPKI 导入、ECDH、HKDF-SHA256、AES-256-GCM 和固定 AAD。
- 纯托管或外源 + 托管两种订阅模式、持久缓存、超时/大小/HTML/URL 安全检查。
- 设备认证订阅目录、Challenge 原子防重放和 Crypto V2 密文下发。
- 使用 `ADMIN_TOKEN` 登录并签发可撤销的 30 天 JWT，由 JWT 保护订阅 CRUD、测试、刷新 API 及审计日志。
- 与仓库 Android/.NET 实现一致的跨平台测试向量及篡改测试。

用户账号只作为服务端内部的数据归属 ID，不作为可登录身份。当前后端已具备匿名设备注册、设备凭据轮换、订阅安全下发、用量统计、用户账号管理与日常运维能力。

## 本地开发

要求 Node.js 20 或更高版本。

```bash
npm install
npm run typecheck
npm test
npm run build
cp .env.example .env
npm run dev
```

默认监听 `http://127.0.0.1:3000`（以 `.env` 为准）。可访问：

```text
GET /health
GET /api/v2/bootstrap
POST /api/v2/devices/enroll
GET /api/v2/subscriptions
GET /api/v2/subscriptions/:id/content
GET /api/v2/accounts/me
DELETE /api/v2/accounts/me
GET /api/v2/devices
POST /api/v2/devices/:deviceId/revoke
POST /api/v2/devices/:deviceId/rotate-key
POST /api/v2/usage/report
```

请先修改 `.env` 中的 `ADMIN_ROUTE` 与 `ADMIN_TOKEN` 示例值；示例值未替换时服务会拒绝启动。默认数据库是 `data/firefly.sqlite`，首次启动自动建库并执行 migrations。

## 运行时配置

Key 为 `runtime:config`，值示例：

```json
{
  "notice": {
    "enabled": false,
    "id": "",
    "title": "",
    "content": "",
    "showOnce": true
  },
  "appUpdate": null,
  "pcAppUpdate": null,
  "settings": {
    "websiteUrl": "",
    "feedbackEmail": "",
    "feedbackUrl": "",
    "githubUrl": "",
    "subscriptionFetchTimeoutMs": 15000
  }
}
```

`subscriptionFetchTimeoutMs` 供服务端拉取 external 订阅时使用，不会由 bootstrap 下发给客户端。

## 匿名设备注册

客户端采集平台硬件身份材料并计算 SHA-256（64 位小写 hex），同时生成本机 P-256 密钥对，然后调用：

```http
POST /api/v2/devices/enroll
Content-Type: application/json

{
  "deviceId": "<64-lowercase-hex>",
  "platform": "android",
  "deviceName": "Pixel",
  "publicKey": "<P-256-SPKI-base64url>",
  "cryptoVersion": 2
}
```

首次成功返回 `201`，其中 `deviceToken` 仅在首次注册或重装后的凭据重新绑定时返回：

```json
{
  "ok": true,
  "data": {
    "accountId": "<server-generated-id>",
    "deviceId": "<device-id>",
    "deviceToken": "<store-in-platform-secure-storage>",
    "cryptoVersion": 2,
    "alreadyEnrolled": false
  }
}
```

同一设备重复调用时必须携带原 Token：

```http
Authorization: Bearer <device-token>
```

公钥相同且 Token 有效时返回 `alreadyEnrolled: true`，但不会再次返回 Token。卸载重装后，同一硬件身份会提交相同 `deviceId` 和新的公钥；服务端必须先检查账号与设备状态：已封禁或已撤销时拒绝，状态正常时原子替换公钥和 Token，并返回新的 `deviceToken`。旧 Token 立即失效。SQLite 仅保存 Token 的 SHA-256 Base64URL 哈希。

## 设备管理与凭据轮换

`GET /api/v2/accounts/me` 返回用户账号自身及安全设备摘要；`DELETE /api/v2/accounts/me` 会将用户账号标记为 deleted、撤销其全部设备并立即阻断原 Token。该接口不提供邮箱或账号资料能力。

携带当前设备认证头调用 `GET /api/v2/devices`，可查看同一匿名归属下的安全设备摘要。响应不包含完整公钥、Token 或 Token 哈希。

`POST /api/v2/devices/:deviceId/revoke` 只能撤销同一匿名归属下的其他设备，不能用 deviceId 越权，也不允许当前设备自撤销。

设备私钥需要更换时调用：

```http
POST /api/v2/devices/<current-device-id>/rotate-key
Content-Type: application/json
Authorization: Bearer <current-device-token>
X-Firefly-Device-ID: <current-device-id>

{"publicKey":"<new-P-256-SPKI-base64url>"}
```

轮换受已认证 deviceId 限流。成功后返回只出现一次的新 Device Token，旧 Token 立即失效，同时清理该设备的防重放记录。

## 订阅源管理 API

管理端先通过 `ADMIN_ROUTE` 隐藏路径和 `ADMIN_TOKEN` 登录：

```http
Authorization: Bearer <ADMIN_TOKEN>
```

登录响应会返回有效期 30 天的 HS256 JWT，后续管理 API 使用 `Authorization: Bearer <JWT>`。JWT 绑定当前 Admin Token 版本并支持登出撤销。管理登录限流默认启用，同一管理入口下每个 IP 每分钟最多尝试 3 次，超过后返回 `429 rate_limited`；可在“其他设置”页面的“登录相关设置”底部关闭或调整为 1–60 次。

已实现：

```text
GET    /<ADMIN_ROUTE>/api/subscriptions
GET    /<ADMIN_ROUTE>/api/subscriptions/:id
POST   /<ADMIN_ROUTE>/api/subscriptions
PATCH  /<ADMIN_ROUTE>/api/subscriptions/:id
DELETE /<ADMIN_ROUTE>/api/subscriptions/:id
POST   /<ADMIN_ROUTE>/api/subscriptions/:id/test
POST   /<ADMIN_ROUTE>/api/subscriptions/:id/refresh
```

创建“外源 + 托管”订阅源示例：

```json
{
  "id": "main",
  "name": "主线路",
  "sourceType": "external",
  "sourceUrl": "https://example.com/subscription",
  "externalHealthEnabled": true,
  "managedContent": "vless://...",
  "enabled": true,
  "sortOrder": 0,
  "cacheTtlSeconds": 300
}
```

`sourceType` 有两种模式：`managed`（托管）要求非空 `managedContent`，不使用 `sourceUrl`；`external`（外源 + 托管）要求无用户名/密码的 HTTPS `sourceUrl`，`managedContent` 可省略。修改组合模式时传空字符串或 `null` 可清空托管正文；纯托管模式不能清空正文。两种模式可以互相切换，切换为组合模式时若保留已有托管正文，正文须符合可合并格式。纯托管创建示例：`{"id":"managed","name":"托管线路","sourceType":"managed","managedContent":"vless://..."}`。组合模式用 `mergeMode` 控制节点顺序：`external_first`（默认，订阅在前）、`managed_first`（托管在前）、`interleave_external_first`（交替且订阅先）或 `interleave_managed_first`（交替且托管先）；没有托管内容时保持外源原文。合并支持节点 URI 列表及其 Base64 编码，保留外源编码形式；协议识别覆盖 sing-box 主流出站及常见别名，包括 VMess、VLESS、Trojan、Shadowsocks、Hysteria/Hysteria2（`hy2`）、TUIC、AnyTLS、ShadowTLS、Snell、WireGuard、SSH、SOCKS、HTTP(S) CONNECT 与 Naive。Clash YAML/JSON 等格式可用于纯托管或单独外源正文，不能与托管节点混合。合并后总大小不得超过 2 MiB。

外源响应限制为 2 MiB，带超时、HTML/明显错误页检查和 TTL 缓存。网络/超时以及 HTTP 408、429、5xx 会在 500 ms、1500 ms 退避后最多重试两次，三次均失败后才判定拉取失败。Node.js 会在每次请求和跳转前解析主机名，并拒绝解析到回环、内网、链路本地、保留或文档地址的目标。真实外源 URL 不会下发给客户端。外源正文与刷新时间一起保存在 SQLite 键值表中，每次成功刷新都会持久化。纯托管订阅可直接创建和编辑，无需外源链接。每个外源都可用 `externalHealthEnabled` 单独控制健康监测与故障回退，新建外源默认启用；服务每小时主动检查，普通拉取失败时下发最近一次成功内容，并在管理列表记录正常、异常和回退状态。强制刷新始终访问源站，失败时返回错误，不用缓存掩盖检测结果。

托管正文只保存在键 `subscription:managed:<id>`。列表、创建、修改响应和审计日志均不包含正文；只有受管理员 JWT 保护的单条详情接口会返回已配置的 `managedContent`，供控制台编辑时回填。运行配置与外源、托管正文使用短时进程内缓存吸收重复读取，源的 `updatedAt` 参与缓存键，后台修改后不会继续命中旧版本。`refresh` 只刷新外源缓存，返回合并后的内容摘要。

## 管理控制台

部署后访问：

```text
https://<your-domain>/<ADMIN_ROUTE>/
```

初始 `ADMIN_ROUTE` 位于 `.env`。页面只在登录时使用 `ADMIN_TOKEN`，随后将服务端签发的 30 天 JWT 保存在浏览器 `localStorage`，从而减少重复登录。控制台新设置的 Admin Token 长度必须为 8–16 个字符；已有的较长环境变量 Token 仍可继续登录，以便平滑轮换。

控制台提供：

- 用户账号、活跃数据等趋势图，以及合并今日数据的 30 日流量折线图和精确明细。
- 默认明亮主题，并可在右上角切换明暗主题。
- 两种订阅模式的新增、查询、修改、删除、备注、校验与缓存刷新。
- 用户账号列表、分页检索、流量摘要、设备详情、账号封禁与解封。
- 设备列表、分页检索、公钥短指纹、封禁、解封和撤销。
- 每日活跃/新增账号、每日/月度流量、累计流量和平台分布统计。
- 通过分组输入表单分别维护移动端 `appUpdate` 与 PC 端 `pcAppUpdate`，无需手写 JSON。
- 在设置页修改管理端访问路径，轮换 `ADMIN_TOKEN` 或 32–128 字符的 JWT 私钥；未手动设置 JWT 私钥时，后端会生成 UUID，私钥永不回显。
- 管理操作审计日志，支持组合筛选、分页、将全部筛选结果下载为 UTF-8 CSV，以及按时间范围或全部清理。

对应管理 API 还包括：

```text
GET  /<ADMIN_ROUTE>/api/dashboard
GET  /<ADMIN_ROUTE>/api/analytics?days=30&months=12
GET  /<ADMIN_ROUTE>/api/accounts?page=1&pageSize=20&q=<关键词>
GET  /<ADMIN_ROUTE>/api/accounts/:id
POST /<ADMIN_ROUTE>/api/accounts/:id/ban
POST /<ADMIN_ROUTE>/api/accounts/:id/unban
GET  /<ADMIN_ROUTE>/api/devices?page=1&pageSize=20&q=<关键词>
POST /<ADMIN_ROUTE>/api/devices/:id/ban
POST /<ADMIN_ROUTE>/api/devices/:id/unban
POST /<ADMIN_ROUTE>/api/devices/:id/revoke
GET  /<ADMIN_ROUTE>/api/settings
PUT  /<ADMIN_ROUTE>/api/settings
PUT  /<ADMIN_ROUTE>/api/settings/admin-access
POST /<ADMIN_ROUTE>/api/session/login
POST /<ADMIN_ROUTE>/api/session/logout
GET  /<ADMIN_ROUTE>/api/audit?page=1&pageSize=20&from=<ISO时间>&to=<ISO时间>&action=<操作>&requestId=<请求标识>&targetId=<目标标识>
DELETE /<ADMIN_ROUTE>/api/audit
```

审计日志支持按时间范围、操作、请求标识和目标标识组合筛选及服务端分页。管理端登录与登出也会写入审计日志，并记录直连地址或可信反向代理提供的访问 IP；升级前的历史日志没有原始 IP，控制台会显示其已有 IP 哈希。清理接口接受开始/结束 ISO 时间（至少一项）或 `{ "all": true }`；清理完成后会保留本次清理操作的审计记录。

`ADMIN_ROUTE` 与 `ADMIN_TOKEN` 环境变量是首次启动和运行时配置不可用时的回退值。通过控制台更新后，运行时覆盖保存在 SQLite 键值表的 `admin:access:v1`；访问路径保存为明文，Token 只保存 SHA-256 Base64URL 哈希。JWT 私钥保存在 SQLite 的 `admin_security_settings`，只用于服务端签名和校验且不会通过接口回显。修改 Admin Token 或 JWT 私钥后控制台会主动退出登录，请妥善保存新值。

如果遗失了新路径或 Token，先停止服务，再删除运行时覆盖，即可恢复使用 `.env` 中的路径与 Token：

```bash
sqlite3 data/firefly.sqlite "DELETE FROM node_kv WHERE key='admin:access:v1';"
```

## 命令行更新订阅源

`scripts/update_subscription.py` 通过受保护的管理 API 修改单个订阅源，不直接操作 SQLite，也不会改动名称、启用状态、排序、备注和缓存 TTL。脚本仅依赖 Python 3.10+ 标准库。

建议通过环境变量提供后台路径与 Token，避免 Token 出现在命令历史中：

```powershell
$env:FIREFLY_ADMIN_ROUTE = "<ADMIN_ROUTE>"
$env:FIREFLY_ADMIN_TOKEN = "<ADMIN_TOKEN>"
```

从 UTF-8 文件更新托管正文（也可用 `--managed-file -` 从标准输入读取）：

```powershell
python scripts/update_subscription.py origin --managed-file .\subscription.txt
python scripts/update_subscription.py origin --clear-managed  # 仅适用于外源 + 托管模式
```

更新外源链接，或在提交前只检查目标：

```powershell
python scripts/update_subscription.py node2 --external-url "https://example.com/subscription"
python scripts/update_subscription.py node2 --external-url "https://example.com/subscription" --dry-run
python scripts/update_subscription.py node2 --external-url "https://example.com/subscription" --managed-file .\subscription.txt
python scripts/update_subscription.py node2 --managed-only --managed-file .\subscription.txt
```

默认会在写入前显示不含正文和敏感 URL 参数的摘要，并要求输入 `yes`。自动化场景可传入 `--yes`。可通过 `--base-url` 指向其他部署，默认使用 `https://ly.202132.xyz`。

## Crypto V2 对接

协议常量不可修改：

```text
version      2
algorithm    P256-HKDF-SHA256-A256GCM
HKDF info    FireflyVPN-Subscription-V2
response TTL 300 seconds
```

客户端长期密钥使用 P-256，上传 DER SPKI 的无 padding Base64URL 公钥，私钥永不离开设备。每次订阅读取生成 16-byte Challenge，并发送：

```http
Authorization: Bearer <device-token>
X-Firefly-Device-ID: <64-lowercase-hex>
X-Firefly-Crypto-Version: 2
X-Firefly-Challenge: <16-byte-base64url>
```

服务端每次响应生成新的临时 P-256 密钥、32-byte salt 与 12-byte AES-GCM nonce。AAD 字段按以下顺序以换行连接：

```text
2
P256-HKDF-SHA256-A256GCM
<deviceId>
<subscriptionId>
<challenge>
<issuedAt>
<expiresAt>
```

固定向量在 `test/vectors/crypto-v2.json`。它与仓库已有的 Android 和 .NET 测试数据使用相同密钥与密文。

## 流量上报

携带设备认证头调用：

```http
POST /api/v2/usage/report
Content-Type: application/json
Authorization: Bearer <device-token>
X-Firefly-Device-ID: <device-id>

{
  "sessionId": "unique-session-id",
  "uploadBytes": 123456,
  "downloadBytes": 789012
}
```

服务端只使用认证上下文中的 account/device 归属，忽略客户端额外提交的身份字段。同一 deviceId + sessionId 只累计一次，重复请求返回 `duplicate: true`。日/月统计统一使用 `Asia/Shanghai`。

## 客户端接入顺序

1. 请求 `/api/v2/bootstrap` 并确认版本与算法。
2. 生成设备 ID 与本机 P-256 密钥，调用 `/api/v2/devices/enroll`。
3. 将 Device Token 存入平台安全存储。
4. 获取 enabled 订阅目录。
5. 每次使用新 Challenge 请求订阅密文。
6. 校验 Envelope 身份、时间和 Challenge，再执行 ECDH/HKDF/AES-GCM 解密。
7. 解密失败时拒绝降级到明文协议。

## 目录边界

- `src/node/server.ts` 是 Node.js HTTP 服务入口。
- `src/app` 负责环境、上下文与路由装配。
- `src/foundation` 只放业务无关的基础能力。
- `src/modules` 按业务域组织。
- `database/migrations` 是唯一 schema 变更来源。
- `public/console` 存放静态管理台。

Node 进程每小时清理过期 Challenge 与 SQLite 限流窗口，并检查已启用健康监测的外源。设备活跃时间每 15 分钟至多更新一次，流量上报不会重复更新该字段。高频临时表使用 `WITHOUT ROWID`，并以较低的写入成本保留原子防重放与幂等约束。始终跳过邮箱、注册资料和密码相关账号功能。数据库结构只通过 `database/migrations` 变更并在启动时自动应用。

完整接口约定见 [`API.md`](./API.md)。
