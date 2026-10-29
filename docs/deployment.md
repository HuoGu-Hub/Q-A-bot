# 部署与运维

## 1. 部署形态

```text
公网
  │
  ├── www.example.com    公开站
  └── admin.example.com  管理后台，IP 白名单
          │
        Nginx
          │
   Spring Boot :8080
     ├── /api/public/**   公开只读
     ├── /admin/api/**    会话鉴权
     └── /onebot/event    NapCat 上报
          │
      SQLite 与本地缓存

NapCat 容器 :3000 / :3001 / :6099
```

NapCat 只绑定 `127.0.0.1`，不暴露到公网。业务层监听 `0.0.0.0`，因为容器上报的源地址是 Docker 网桥，不是回环地址。

## 2. 前置条件

| 项 | 要求 |
|---|---|
| 内存 | 2 GB 起。应用约占 400 MB，块与向量常驻内存 |
| 磁盘 | 20 GB。数据库、图片缓存、瓦片缓存、日志 |
| 系统 | Debian 或 Ubuntu，示例使用 Debian 13 |
| 运行时 | JRE 17 或更高。Debian 13 起默认只提供 21，向下兼容 |
| 构建 | Maven 3.9+，Node 20+。只在构建机需要，服务器不需要 |
| 容器 | Docker 与 Docker Compose |
| 出网 | QQ、模型 API、wiki.gg |

```bash
sudo apt update
# 运行时只要 JRE；中文字体装不上会让地图出图的中文变成方块
sudo apt install -y openjdk-21-jre-headless nginx sqlite3 curl git fonts-noto-cjk
# 下面三行只在构建机上需要，产物上传到服务器的做法见 plans/html/本地上传部署操作手册.html
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
sudo corepack enable
```

## 3. 协议层

`deploy/docker-compose.yml` 只运行 NapCat 一个容器。

```bash
cd deploy
# deploy/.env 由 compose 自动读取，按需调整 NAPCAT_UID 与 NAPCAT_GID
docker compose pull
docker compose up -d
docker compose logs -f
```

日志中会出现 WebUI 地址与 Token。浏览器打开 `http://127.0.0.1:6099/webui?token=...`，使用专用小号扫码登录。

### 3.1 OneBot 配置

在 WebUI 的「网络配置」中建立两项：

| 方向 | 类型 | 配置 |
|---|---|---|
| 业务层调用 NapCat | HTTP 服务器 | Host `0.0.0.0`，端口 3000，消息格式 Array，保留 Token |
| NapCat 上报业务层 | HTTP 客户端 | URL `http://host.docker.internal:8080/onebot/event`，消息格式 Array，关闭 reportSelfMessage，Token 填与 `.env` 的 `ONEBOT_TOKEN` 相同的值 |

Host 必须是 `0.0.0.0`。Docker 把连接转发到容器网卡，监听回环地址会拒绝宿主机流量。Token 不能留空，否则 NapCat 会把 Host 强制改回 `127.0.0.1`。

业务层会校验上报请求的 `Authorization: Bearer <ONEBOT_TOKEN>`。没配 Token 时入口直接拒绝，不会降级放行。Nginx 对公网的 `/onebot/` 一律返回 404，NapCat 必须直连 `http://host.docker.internal:8080/onebot/event`。

### 3.1.1 从本机访问 WebUI

`6099` 只绑定 `127.0.0.1`，公网直连必然失败，这是安全设计。不要改 compose，也不要在安全组开放它。

本机 PowerShell 建 SSH 隧道：

```powershell
ssh -N -L 6099:127.0.0.1:6099 qqbot@服务器IP
```

保持这个窗口不关，浏览器打开 `http://127.0.0.1:6099/webui`。SSH 端口不是 22 时加 `-p`，用密钥时加 `-i`。

SSH 用户用你平时登录服务器的那一个，通常是 `root`。`qqbot` 只是服务运行用户，不一定是 SSH 账号；给 `qqbot` 配 SSH 密钥是另一件事，隧道不需要它。

WebUI Token 从服务器日志或配置里取：

```bash
cd /opt/qqbot/deploy
docker compose logs napcat | grep -iE 'webui|token' | tail -20
# 或者
docker exec napcat sh -c 'grep -R "token" /app/napcat/config | head'
```

SSH 隧道不通时，可以直接用 `docker cp` 改配置：

```bash
docker exec napcat ls /app/napcat/config
docker cp napcat:/app/napcat/config/onebot11_<QQ>.json ./onebot11.json
# 本地改好 Token 后传回
docker cp ./onebot11.json napcat:/app/napcat/config/onebot11_<QQ>.json
docker restart napcat
```

三处 Token 必须一致：根目录 `.env` 的 `ONEBOT_TOKEN`、WebUI「HTTP 服务器」Token、WebUI「HTTP 客户端」Token。

Linux 上需要给容器加 `host.docker.internal` 映射：

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

### 3.2 登录稳定性

掉登录通常由设备指纹变化引起。腾讯的设备标识由 `machine-id` 与网卡 MAC 计算。

```bash
docker exec napcat cat /sys/class/net/eth0/address
docker exec napcat cat /etc/machine-id > deploy/data/machine-id
```

把 MAC 写入 `deploy/.env` 的 `NAPCAT_MAC`，并挂载 `machine-id`。两者固定后，重建容器不会被视为新设备。

快速登录账号通过 WebUI「配置 → 登录配置 → 快速登录 QQ」设置，不要通过重建容器修改。

### 3.3 验证

```bash
TOKEN=你的AccessToken
curl -s http://127.0.0.1:3000/get_login_info \
  -H "Authorization: Bearer $TOKEN"
```

返回 `status: ok` 且 `user_id` 为机器人 QQ 号，说明协议层可用。

## 4. 应用配置

```bash
cd /opt/qqbot
cp .env.example .env
chmod 600 .env
```

| 变量 | 必填 | 用途 |
|---|---|---|
| `ONEBOT_TOKEN` | 是 | 与 NapCat 的 HTTP 服务器、HTTP 客户端 Token 一致；入站也用它校验 |
| `ONEBOT_API_BASE` | 否 | 默认 `http://127.0.0.1:3000` |
| `OPENCODE_GO_API_KEY` | 是 | 默认对话 provider |
| `OPENCODE_GO_MODEL` | 否 | 默认模型名 |
| `DEEPSEEK_API_KEY` | 否 | 降级 provider |
| `SILICONFLOW_API_KEY` | 是 | 向量与重排 |
| `DASHSCOPE_API_KEY` | 否 | 备用向量，默认关闭 |
| `ADMIN_PASSWORD` | 是 | 首次启动用它创建 admin 账号；之后登录以账号表为准 |

其余配置在 `server/src/main/resources/application.yml`，可被环境变量、`config/overrides.yml` 与命令行参数覆盖。后台「设置」页只修改白名单内的键。

### 4.1 路径

数据库、知识库、图片、瓦片与日志都是相对进程工作目录的路径。默认 `WorkingDirectory=/opt/qqbot/server`，数据落在 `/opt/qqbot/server/data/`。

启动日志会打印解析后的绝对路径，搜索 `[路径]` 即可核对。修改工作目录等于切换到一套新的空数据，迁移时必须连同 `data/` 一起移动。

要彻底与工作目录解耦，可在 `EnvironmentFile` 中写绝对路径：

```bash
APP_PERSISTENCE_DB=/opt/qqbot/data/qqbot.sqlite
APP_KB_DIR=/opt/qqbot/data/kb
APP_MEDIA_TEMPIMAGES_DIR=/opt/qqbot/data/tmp-images
APP_MEDIA_KBIMAGES_DIR=/opt/qqbot/data/kb-images
APP_MEDIA_WIKIIMAGES_DIR=/opt/qqbot/data/wiki-images
APP_KB_MAP_TILE_DIR=/opt/qqbot/data/map-tiles
APP_SITE_CAROUSEL_DIR=/opt/qqbot/data/site-images
```

### 4.2 主机防火墙

`deploy/server/bootstrap.sh` 默认安装并启用 ufw：只放行 22、80、443，8080 只允许 `172.16.0.0/12` 与回环。SSH 不是 22 时先设置 `SSH_PORT`；已有自定义防火墙时用 `ENABLE_FIREWALL=0` 跳过。

云安全组仍是主防线，必须确认 8080、3000、3001、6099 不对公网开放。改动后用外部主机 `nc -zv` 或 `nmap` 复验，不要只看配置文件。

### 4.3 多人后台的身份层

后台账号是应用自己的密码体系，没有 MFA。多人公网使用时，建议在 Nginx 前再加一层：

| 方式 | 用户怎么进 | 代价 |
|---|---|---|
| Cloudflare Access | 浏览器打开后台，按邮箱收验证码或用 SSO | 域名要托管在 Cloudflare；源站只允许 Cloudflare 回源或走 Tunnel |
| Tailscale、WireGuard | 先连 VPN，再打开后台 | 每个用户装客户端，但不是 SSH 密钥 |
| Authelia、oauth2-proxy | 自建统一登录页 | 多一个容器与维护成本 |

没有身份层时，至少做到：每人独立账号，不共用 `admin`；密码 12 位以上；只给需要的人 EDITOR 或 ADMIN；定期看 `admin_audit`。

## 5. 构建

```bash
cd /opt/qqbot
./scripts/build-web.sh
cd server && mvn -q clean package -DskipTests
```

`build-web.sh` 把前端产物同步到 `server/web-dist/`，后端直接托管。服务器上没有 IDE 占用 classpath，可以直接在 `server/` 中构建，产物位于 `server/target/`，与 systemd 单元中的路径一致。

本地开发机应改用 `./scripts/build-server.sh`。该脚本把源码复制到 `.toolchain/server-ci/` 后构建，不会删除 IDE 正在运行的 `target/classes`。

## 6. systemd

```ini
[Unit]
Description=QQ Bot
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=qqbot
WorkingDirectory=/opt/qqbot/server
EnvironmentFile=/opt/qqbot/.env
ExecStart=/usr/bin/java -Xmx512m -Dfile.encoding=UTF-8 -jar /opt/qqbot/server/target/qqbot-server-0.0.1-SNAPSHOT.jar
Restart=always
RestartSec=10

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/qqbot/server/data

[Install]
WantedBy=multi-user.target
```

`-Xmx512m` 是 2 GB 机器上的保守值。使用绝对路径配置时，`ReadWritePaths` 也要同步修改，否则 `ProtectSystem=strict` 会阻止写入。

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now qqbot
sudo systemctl status qqbot
```

## 7. Nginx

按域名形态选一份配置，不要同时启用多份：

```bash
# 只有一张证书：公开站和后台共用 firefire.online
sudo cp deploy/nginx/qqbot-domain.conf /etc/nginx/sites-available/qqbot.conf
# 或者：两个域名，后台走独立域名
# sudo cp deploy/nginx/qqbot.conf /etc/nginx/sites-available/qqbot.conf
sudo ln -s /etc/nginx/sites-available/qqbot.conf /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

`qqbot-domain.conf` 是单域名配置，`server_name` 写 `firefire.online` 与 `www`。`qqbot.conf` 是双域名模板，里面的 `admin.example.com` 只是占位符；没有对应 DNS 与证书时不要启用它。

需要修改两处：

1. `server_name` 改成实际域名。
2. 只有一个管理员且有固定 IP 时，按配置里的 `allow`/`deny` 注释把后台锁到你的 IP。多人后台保持公开，靠账号密码与登录限流。

后台默认公网可达，有账号的人直接浏览器访问：

```text
https://admin.example.com/admin/
```

SSH 隧道只用于应急和首次配置，不是普通后台用户的入口：

```bash
ssh -L 8080:127.0.0.1:8080 user@服务器
# 浏览器打开 http://127.0.0.1:8080/admin/
```

多人后台建议在 Nginx 前再加一层身份代理，见 4.3。

关键约束：

| 项 | 值 | 原因 |
|---|---|---|
| 公开站 `/admin/` | `return 404` | 公网域名下不暴露后台入口 |
| 公开站 `/admin.html` | `return 404` | 后台前端壳子也不放行 |
| 公网 `/onebot/` | `return 404` | 事件入口只给 NapCat 直连 8080 |
| 管理后台 | 账号密码 + 登录限流 | 多人公网访问；单管理员可改用 IP 白名单 |
| 公开 API 限流 | 60 次/分钟 | 与应用层限流形成双层 |
| 登录接口限流 | 5 次/分钟 | 防密码爆破 |
| `client_max_body_size` | 16m | 与 Spring 上传上限对齐，否则只看到 413 |
| 8080 | 只允许 Docker 网段与回环 | 公网 8080 会绕过 TLS、限流与后台白名单 |

HTTPS 使用 certbot：

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d www.example.com -d admin.example.com
```

## 8. 上线验证

| # | 检查 | 期望 |
|---|---|---|
| 1 | 打开公开站 | 首页正常 |
| 2 | 搜索一个词 | 返回结果 |
| 3 | 公网域名访问 `/admin/` | 404 |
| 4 | 公网访问后台域名 | 登录页，不是 404 |
| 5 | 未登录访问 `/admin/` | 跳转登录页 |
| 6 | 错误密码 | 401 |
| 7 | 正确密码 | 进入统计页 |
| 8 | 刷新后台子路由 | 前端 fallback 正常 |
| 9 | 群内 @ 机器人 | 正常回答，后台有记录 |
| 10 | 连续请求公开 API | 出现 429 |
| 11 | 无 Token POST `/onebot/event` | 公网 404，直连 8080 为 401 |
| 12 | 外部扫描 8080、3000、3001、6099 | 全部不可达 |
| 13 | 编辑账号访问 `/admin/api;x/users` | 400 或 403，审计有记录 |
| 14 | 登录响应 Cookie | 含 `Secure; HttpOnly; SameSite` |

启用单管理员 `allow` 白名单时，非白名单 IP 访问后台应为 403。

```bash
./scripts/health-check.sh https://www.example.com
```

## 9. 备份与恢复

需要备份的内容只有两项：

```bash
# 全部人工成果与知识库
sqlite3 /opt/qqbot/server/data/qa/qqbot.sqlite \
  ".backup /backup/qqbot-$(date +%F).sqlite"

# 首页轮播图
cp -r /opt/qqbot/server/data/site-images /backup/
```

块、向量、词条、分类、问答统计、页面文案、轮播清单都在同一个 SQLite 中。图片缓存、瓦片缓存与日志可以重建，不需要备份。

恢复时停止服务，替换数据库文件，再启动。数据库使用 WAL，备份命令会生成一致快照，不要直接复制正在写入的 `.sqlite` 文件。

## 10. 升级

```bash
cd /opt/qqbot
git pull
./scripts/build-web.sh
cd server && mvn -q clean package -DskipTests
sudo systemctl restart qqbot
```

发布前建议执行：

```bash
./scripts/preflight.sh
```

该脚本检查密钥、后端测试、打包、前端构建、数据库存在性与检索门禁。门禁无法度量时视为失败。

## 11. 排障

| 现象 | 检查 |
|---|---|
| 机器人不回复 | `journalctl -u qqbot` 搜索「连不上 NapCat」；检查 Token 与 3000 端口 |
| 公开站 502 | 服务是否启动；Nginx error log |
| 后台打不开 | IP 是否在白名单；库里有没有账号；首次部署时 `ADMIN_PASSWORD` 是否为空 |
| 后台返回 503 | `.env` 未加载或密码为空 |
| 页面 404 但接口正常 | `server/web-dist` 未构建或工作目录不对 |
| 检索突然变慢 | 向量或重排 API 是否可用；查看 A 路失败日志 |
| 检索结果为空 | 数据库是否存在；块数与向量数是否正常 |
| 磁盘增长快 | 临时图片清理、日志滚动、瓦片缓存上限 |
| 导入后无变化 | 导入是否成功；块数与语料版本是否变化 |

## 12. 安全清单

| 项 | 要求 |
|---|---|
| NapCat 端口 | 只绑定 `127.0.0.1`，安全组不开放 3000、3001、6099 |
| WebUI | 只通过 SSH 隧道访问 |
| 应用 8080 | 安全组不开放；主机防火墙只允许 Docker 网段与回环 |
| OneBot Token | 随机长串，HTTP 服务器与 HTTP 客户端填同一个值；入站无 Token 直接 401 |
| 管理后台 | 独立域名、HTTPS、VIEWER/EDITOR/ADMIN 分权；多人公网访问建议加身份代理 |
| 聊天采集 | 默认关闭；开启前确认群白名单，保留期清理每天执行 |
| 公开接口 | 独立 DTO，上线后抓包确认无隐私字段 |
| 日志 | 文件与 journal 权限 600；后台日志页保持脱敏 |
| 账号 | 使用专用小号，不与日常号共用 |
| 备份 | 数据库、轮播图、语音、知识库图片、配置定期异地保存 |
