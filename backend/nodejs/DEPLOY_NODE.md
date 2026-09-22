# Debian / Ubuntu Node.js 部署

项目直接运行在 Node.js 20+ 上：

- 业务数据由 SQLite 文件持久化；
- 运行配置、缓存和订阅正文保存在同一个 SQLite 文件中；
- 管理控制台静态文件由 Node.js 返回；
- 安全状态由进程内每小时任务定期清理；
- migrations 在每次启动时自动、幂等执行。

## 1. 准备服务器

安装 Node.js 20 或更高版本。`better-sqlite3` 通常会下载预编译包；如果服务器平台没有对应包，还需安装编译工具：

```bash
sudo apt update
sudo apt install -y build-essential python3
```

## 2. 安装和构建

把项目放到 `/opt/firefly-vpn`，然后执行：

```bash
cd /opt/firefly-vpn
npm ci
npm run typecheck
npm test
npm run build
cp .env.example .env
```

编辑 `.env`，至少替换 `ADMIN_ROUTE` 和 `ADMIN_TOKEN`。建议生成随机值：

```bash
openssl rand -hex 24
openssl rand -base64 18 | tr -d '=+/\n' | head -c 16; echo
```

默认数据库位于 `data/firefly.sqlite`。首次启动会自动建立目录、创建数据库，并执行 `database/migrations` 中所有尚未执行的迁移。

如果需要继承旧 Cloudflare D1/KV 数据，请不要先启动空数据库；按照 [`导出D1和KV数据.md`](./导出D1和KV数据.md) 生成新的导入数据库，再设置 `DB_PATH`。

## 3. 启动验证

```bash
npm start
curl http://127.0.0.1:3000/health
```

成功响应示例：

```json
{"ok":true,"data":{"status":"ok"}}
```

本机管理控制台地址是 `http://127.0.0.1:3000/<ADMIN_ROUTE>/`。配置下方的 nginx 反向代理后，可通过站点域名访问。

## 4. systemd 常驻运行

先创建低权限用户并授权数据目录：

```bash
sudo useradd --system --home /opt/firefly-vpn --shell /usr/sbin/nologin firefly
sudo mkdir -p /opt/firefly-vpn/data
sudo chown -R firefly:firefly /opt/firefly-vpn/data
sudo chmod 600 /opt/firefly-vpn/.env
```

安装仓库中的示例服务：

```bash
sudo cp deploy/firefly-vpn.service.example /etc/systemd/system/firefly-vpn.service
sudo systemctl daemon-reload
sudo systemctl enable --now firefly-vpn
sudo systemctl status firefly-vpn
journalctl -u firefly-vpn -f
```

如果 Node 不在 `/usr/bin/node`，用 `command -v node` 找到实际路径并修改服务文件的 `ExecStart`。

## 5. nginx 和 HTTPS

将 `deploy/nginx.conf.example` 复制到 nginx 站点配置，修改域名后启用。使用该反向代理配置时，在 `.env` 中设为 `TRUST_PROXY=true`，这样审计与 IP 限流会读取 nginx 覆盖后的 `X-Forwarded-For`。不要在 Node 端口直接暴露公网的同时启用此选项。

```bash
sudo cp deploy/nginx.conf.example /etc/nginx/sites-available/firefly-vpn
sudo ln -s /etc/nginx/sites-available/firefly-vpn /etc/nginx/sites-enabled/firefly-vpn
sudo nginx -t
sudo systemctl reload nginx
```

随后可用 Certbot 或现有网关配置 TLS。生产环境应只对外开放 80/443，并保持 Node 默认监听 `127.0.0.1`。

## 运维说明

- 备份时复制 `data/firefly.sqlite`、`-wal` 和 `-shm` 文件，或先停止服务再只复制主数据库文件。
- SQLite 适合单机、单 Node 进程部署；不要使用 PM2 cluster 启动多个写入进程，也不要把数据库放到 NFS。
- 更新代码后运行 `npm ci && npm run build`，再执行 `sudo systemctl restart firefly-vpn`。新迁移会在启动时自动应用。
- `npm run dev` 启动 Node 开发服务器。
