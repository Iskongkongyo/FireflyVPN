# Firefly VPN Edge

FireflyVPN 的新一代 Cloudflare Workers 后端。项目采用 TypeScript、Hono、D1、KV 与 Workers Static Assets，并将 Crypto V2 协议隔离在独立安全模块中。

当前版本采用纯匿名设备身份模式，已经包含：

- 模块化 Worker 入口、路由、请求 ID、CORS 和统一 JSON 错误。
- `GET /health` 与 `GET /api/v2/bootstrap`。
- `POST /api/v2/devices/enroll` 匿名设备注册。
- 每设备独立 Device Token、Token 哈希存储及统一设备认证器。
- 注册 IP/deviceId 限流、64 KiB JSON 上限和严格输入校验。
- 完整首版 D1 schema migration。
- KV 运行时配置模型与安全的默认配置。
- Crypto V2 Base64URL、P-256 SPKI 导入、ECDH、HKDF-SHA256、AES-256-GCM 和固定 AAD。
- managed/external 订阅源、KV 缓存、超时/大小/HTML/URL 安全检查。
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
npm run db:local
npm run dev
```

本地 Worker 默认地址由 Wrangler 输出。可访问：

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

复制开发变量示例并填入管理端强随机 Secret：

```bash
cp .dev.vars.example .dev.vars
```

不要提交 `.dev.vars`。

## 创建 Cloudflare 资源

登录 Wrangler 后创建 KV 和 D1：

```bash
npx wrangler login
npx wrangler kv namespace create CONFIG
npx wrangler d1 create firefly-vpn-edge
```

把命令返回的 KV ID、D1 database ID 写入 `wrangler.jsonc`，替换全零占位值。生产环境设置 Secret：

```bash
npx wrangler secret put ADMIN_TOKEN
```

随后初始化远端数据库并部署：

```bash
npm run db:remote
npm run deploy
```

最后在 `wrangler.jsonc` 添加自己的 custom domain route，或在 Cloudflare Dashboard 中配置域名。仓库已生成随机 `ADMIN_ROUTE`，也可以在首次部署前自行更换。

## KV 配置

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

公钥相同且 Token 有效时返回 `alreadyEnrolled: true`，但不会再次返回 Token。卸载重装后，同一硬件身份会提交相同 `deviceId` 和新的公钥；服务端必须先检查账号与设备状态：已封禁或已撤销时拒绝，状态正常时原子替换公钥和 Token，并返回新的 `deviceToken`。旧 Token 立即失效。D1 仅保存 Token 的 SHA-256 Base64URL 哈希。

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

轮换受 IP、deviceId 与 Token 三维限流。成功后返回只出现一次的新 Device Token，旧 Token 立即失效，同时清理该设备的防重放记录。

## 订阅源管理 API

管理端先通过 `ADMIN_ROUTE` 隐藏路径和 `ADMIN_TOKEN` 登录：

```http
Authorization: Bearer <ADMIN_TOKEN>
```

登录响应会返回有效期 30 天的 HS256 JWT，后续管理 API 使用 `Authorization: Bearer <JWT>`。JWT 绑定当前 Admin Token 版本并支持登出撤销。

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

创建 managed 订阅示例：

```json
{
  "id": "main",
  "name": "主线路",
  "sourceType": "managed",
  "managedContent": "vless://...",
  "enabled": true,
  "sortOrder": 0,
  "cacheTtlSeconds": 300
}
```

创建 external 订阅时将 `sourceType` 设为 `external` 并提供 `sourceUrl`。外部地址只允许 HTTPS；响应限制为 2 MiB，带超时、HTML/明显错误页检查和 KV TTL 缓存。真实 external URL 不会下发给客户端。

managed 正文只保存在 `subscription:managed:<id>`。列表、创建、修改响应和审计日志均不包含正文；只有受管理员 JWT 保护的单条详情接口会返回 `managedContent`，供控制台编辑时回填。`refresh` 仅用于 external 源。

## 管理控制台

部署后访问：

```text
https://<worker-domain>/<ADMIN_ROUTE>/
```

当前仓库配置的 `ADMIN_ROUTE` 位于 `wrangler.jsonc`。页面只在登录时使用 `ADMIN_TOKEN`，随后将服务端签发的 30 天 JWT 保存在浏览器 `localStorage`，从而减少重复登录。控制台新设置的 Admin Token 长度必须为 8–16 个字符；已有的较长部署 Secret 仍可继续登录，以便平滑轮换。

控制台提供：

- 用户账号、活跃数据等趋势图，以及合并今日数据的 30 日流量折线图和精确明细。
- 默认明亮主题，并可在右上角切换明暗主题。
- 托管/外部订阅源的新增、查询、修改、删除、备注、校验与缓存刷新。
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

审计日志支持按时间范围、操作、请求标识和目标标识组合筛选及服务端分页。管理端登录与登出也会写入审计日志，并记录 Cloudflare 提供的访问 IP；升级前的历史日志没有原始 IP，控制台会显示其已有 IP 哈希。清理接口接受开始/结束 ISO 时间（至少一项）或 `{ "all": true }`；清理完成后会保留本次清理操作的审计记录。

`ADMIN_ROUTE` 与 `ADMIN_TOKEN` 环境变量是首次启动和 KV 配置不可用时的回退值。通过控制台更新后，运行时覆盖保存在 KV `admin:access:v1`；访问路径保存为明文，Token 只保存 SHA-256 Base64URL 哈希。JWT 私钥保存在 D1 的 `admin_security_settings`，只用于服务端签名和校验且不会通过接口回显。修改 Admin Token 或 JWT 私钥后控制台会主动退出登录，请妥善保存新值。

如果遗失了新路径或 Token，可从已登录 Wrangler 的终端删除运行时覆盖，恢复使用 `wrangler.jsonc` 中的路径和 Worker Secret：

```bash
npx wrangler kv key delete admin:access:v1 --binding CONFIG --remote
```

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

- `src/index.ts` 只暴露 Worker。
- `src/app` 负责环境、上下文与路由装配。
- `src/foundation` 只放业务无关的基础能力。
- `src/modules` 按业务域组织。
- `database/migrations` 是唯一 schema 变更来源。
- `public/console` 存放静态管理台，不把 HTML 拼进 Worker 源码。

Cron Trigger 每小时清理过期 Challenge 与限流窗口；请求路径仍保留低概率尽力清理，避免定时任务延迟时无限积累。始终跳过邮箱、注册资料和密码相关账号功能。禁止在 Worker 启动时创建或修改表。

完整接口约定见 [`API.md`](./API.md)。

## 可清理的演示数据

需要预览 Dashboard、设备状态、流量趋势和订阅管理效果时，可以写入固定 `demo` 数据：

```bash
npm run demo:d1:remote
npm run demo:kv:remote
```

演示集包含 4 个用户账号、4 台不同平台/状态设备、14 天日流量、6 个月月流量及一条 managed 演示订阅。示例节点使用 `example.com`，仅供展示，不可实际连接。脚本是幂等的，重复执行不会不断增加账号和设备。

预览结束后清理：

```bash
npm run demo:cleanup:d1:remote
npm run demo:cleanup:kv:remote
```

清理脚本只匹配固定 demo ID 和 `demo-main`，不会删除正常注册的数据。
