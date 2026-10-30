# Cloudflare Access 接入

当前状态：`firefire.online` 的 NS 是 DNSPod，源站是 `193.112.199.24`，单域名同时承载公开站与后台。Cloudflare Access 不能直接套在 DNSPod 上，必须先把域名的 NS 迁到 Cloudflare。这一步只能由域名持有人操作。

## 0. 迁移前

1. 在 DNSPod 导出全部 DNS 记录，尤其是 MX、TXT、CNAME。只迁 A 记录会把邮件或验证记录弄丢。
2. 在 Cloudflare 添加站点 `firefire.online`，选择 Free 计划，按提示导入记录。
3. 到域名注册商把 NS 改成 Cloudflare 给的两条。等待生效，通常几分钟到 24 小时。
4. 确认 `firefire.online` 的 A 记录仍是 `193.112.199.24`，并打开橙色云朵代理。

迁移期间公开站可能短暂解析不一致，建议在低峰做。

## 1. 创建 Access 应用

Cloudflare 控制台 → Zero Trust → Access → Applications → Add an application → Self-hosted。

| 项 | 值 |
|---|---|
| Application name | qqbot admin |
| Session duration | 24 hours |
| Application domain | `firefire.online`，path 填 `admin*` |

路径用 `admin*`，这样只拦后台，公开站不受影响。不要用 `admin/`，否则 `/admin` 与 `/admin.html` 可能漏掉。

Policy：

- Action：Allow
- Include：Emails，填团队成员邮箱；或 Emails ending in 你的域名
- 登录方式：One-time PIN 最省事；有 Google/GitHub 身份源就接 SSO

Free 计划最多 50 个用户，够小团队用。

## 2. 源站只允许 Cloudflare 回源

Access 只保护经过 Cloudflare 的请求。源站 IP 如果还能被直接访问，攻击者可以绕过 Access。两步都要做。

### 2.1 Nginx 取真实 IP

把 `deploy/nginx/cloudflare-real-ip.conf` 放到：

```bash
sudo mkdir -p /etc/nginx/snippets
sudo cp deploy/nginx/cloudflare-real-ip.conf /etc/nginx/snippets/
```

在 `/etc/nginx/sites-available/qqbot.conf` 的 HTTPS server 块里取消这一行注释：

```nginx
include /etc/nginx/snippets/cloudflare-real-ip.conf;
```

然后：

```bash
sudo nginx -t && sudo systemctl reload nginx
```

地址段会变，接完后定期用 `https://www.cloudflare.com/ips-v4` 与 `ips-v6` 刷新这个文件。

### 2.2 UFW 只放 Cloudflare

先把 443 的全放行删掉，再按 Cloudflare 段逐条加。**不要先删再加**，中间会断站；先加新规则，再删旧的。

```bash
# 先加 Cloudflare 回源段
for cidr in $(curl -fsS https://www.cloudflare.com/ips-v4) $(curl -fsS https://www.cloudflare.com/ips-v6); do
  ufw allow from "$cidr" to any port 443 proto tcp
done

# 确认新规则在，再删旧的 Anywhere 443
ufw status numbered
ufw delete allow 443/tcp
```

80 端口保留给 HTTP 跳转与证书；如果也想收紧，按同样方式只放 Cloudflare。

### 2.3 验证

从一台不是 Cloudflare 的机器直接打源站 IP：

```bash
curl -k --max-time 5 https://193.112.199.24/ -H 'Host: firefire.online'
```

期望超时或拒绝。通过域名访问：

```bash
curl -I https://firefire.online/admin/
```

期望 302 到 `*.cloudflareaccess.com`，登录后再到应用自己的登录页。

## 3. 应用侧仍然要登录

Cloudflare Access 是外层身份，应用自己的账号体系仍然生效：

1. 浏览器先过 Cloudflare 的邮箱验证码或 SSO。
2. 再输入 qqbot 后台账号密码。
3. 角色仍是 `VIEWER` / `EDITOR` / `ADMIN`。

这叫双层登录，不是重复建设：Cloudflare 负责“谁能到门口”，应用负责“进来后能做什么”。以后想只登录一次，需要应用校验 Cloudflare Access JWT，属于另一项改造。

## 4. 与现有功能的关系

- NapCat 走 `http://host.docker.internal:8080/onebot/event`，不经过 Cloudflare，不受影响。
- 公开站仍是公开的，只有 `/admin*` 被 Access 拦。
- 后台 API 的 `X-Real-IP` 会变成真实客户端 IP，登录锁定与审计不再按 Cloudflare 节点 IP 计算。
- 8080 仍然只允许 Docker 网段与回环，不需要对 Cloudflare 开放。

## 5. 回滚

1. Cloudflare 控制台删除 Access 应用，后台恢复为直接公网登录。
2. UFW 恢复 `ufw allow 443/tcp`。
3. 删除 Nginx 里的 `include`，`nginx -t && systemctl reload nginx`。
4. 如 DNS 也要回退，把 NS 改回 DNSPod。

回滚不会动应用数据库和账号。
