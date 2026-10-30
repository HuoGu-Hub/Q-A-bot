# Cloudflare Access 接入

## 先理解三件事

这三个东西很容易混：

| 名字 | 作用 | 你现在的情况 |
|---|---|---|
| 域名注册商 | 域名在哪买的，改 NS 要去这里 | 腾讯云或其他注册商 |
| DNS 托管商 | 谁在回答“firefire.online 指向哪个 IP” | DNSPod |
| Cloudflare | 新的 DNS 托管商，同时提供 Access 登录门 | 还没接 |

NS 就是域名的“地址簿”。现在地址簿在 DNSPod，所以要先把地址簿交给 Cloudflare，Cloudflare 才能在你的后台前面加一道登录门。

这一步不是装软件，是改域名的 NS。改完之前，Cloudflare Access 无法启用。

## 0. 开工前准备

你需要有：

- DNSPod 账号，能看 <code>firefire.online</code> 的记录。
- 域名注册商账号，能改 NS。
- Cloudflare 账号，免费注册。
- 一个能收邮件的邮箱，Access 用它发验证码。

还要确认一件事：域名现在有没有用来收邮件。如果有 MX 记录，迁移时不能漏，否则邮箱会断。

## 1. 从 DNSPod 导出记录

登录 DNSPod 控制台，进入 <code>firefire.online</code> 的“记录管理”。

先把现有记录抄下来，至少包括：

- A 记录：<code>@</code> 和 <code>www</code>，现在应该都指向 <code>193.112.199.24</code>
- MX 记录：邮件服务
- TXT 记录：SPF、DKIM、域名验证
- CNAME 记录：其他子域

DNSPod 如果有“导出记录”，直接导出。没有就截图或逐条复制。

不要在这一步删任何记录。DNSPod 还会继续工作，直到 NS 真正切走。

## 2. 在 Cloudflare 添加站点

1. 登录 Cloudflare，点“Add a site”。
2. 输入 <code>firefire.online</code>，选 Free 计划。
3. Cloudflare 会自动扫描现有 DNS 记录。
4. 对照第 1 步抄下来的记录，缺的补上，多的删掉。
5. 确认 <code>@</code> 和 <code>www</code> 的 A 记录都指向 <code>193.112.199.24</code>，并且云朵是橙色，表示走 Cloudflare 代理。
6. 点继续，Cloudflare 会给你两条 NS，类似 <code>xxx.ns.cloudflare.com</code>。

这两条 NS 是后面要填到注册商的，不是填到 DNSPod。

## 3. 到注册商改 NS

去你买域名的平台，找到 <code>firefire.online</code> 的“DNS 服务器”或“Nameservers”设置。

把原来的：

~~~text
john.dnspod.net
modify.dnspod.net
~~~

改成 Cloudflare 给你的两条。不要保留 DNSPod 的 NS。

保存后等待生效，通常几分钟到 24 小时。Cloudflare 首页会从“Pending Nameserver Update”变成“Active”。

验证：

~~~bash
nslookup -type=ns firefire.online
~~~

看到 <code>*.ns.cloudflare.com</code> 就对了。

## 4. 先确认站点正常，再开 Access

NS 生效后，先不要急着建 Access。先打开：

~~~text
https://firefire.online/
https://firefire.online/admin/
~~~

两个都应该正常。

如果打不开，先查 Cloudflare 的 SSL/TLS 设置：

- 选“Full (strict)”。
- 不要选“Flexible”，那会让 Cloudflare 到源站走 HTTP。
- 源站证书还是 <code>/etc/nginx/certs/qqbot.crt</code>，它要覆盖 <code>firefire.online</code>。

如果出现 522，说明 Cloudflare 连不到源站 443；检查 UFW 是否还放行 443，以及 Nginx 是否正常。

这一步正常后再往下。

## 5. 建 Access 应用

进入 Cloudflare Zero Trust 控制台，左侧选 Access → Applications → Add an application → Self-hosted。

按下面填：

| 字段 | 填什么 |
|---|---|
| Application name | qqbot admin |
| Session duration | 24 hours |
| Application domain | <code>firefire.online</code> |
| Path | <code>admin*</code> |

Path 用 <code>admin*</code>，这样 <code>/admin</code>、<code>/admin/</code>、<code>/admin.html</code>、<code>/admin/api/...</code> 都被拦住。公开站不受影响。

如果界面不接受 <code>admin*</code>，就建两个应用，Path 分别填 <code>admin</code> 和 <code>admin.html</code>。

然后建 Policy：

- Policy name：team
- Action：Allow
- Include：Emails，填团队每个人的邮箱
- 需要更严就再加 Require，比如邮箱域名必须是你的公司域名

保存后，在 Access 的 Authentication 里确认 One-time PIN 是开启的。这是最省事的方式，用户不用装任何东西，收邮件验证码就能进。

Free 计划最多 50 个用户，小团队够用。

## 6. 测试 Access

用无痕窗口打开：

~~~text
https://firefire.online/admin/
~~~

期望顺序：

1. 先跳到 <code>https://&lt;你的团队名&gt;.cloudflareaccess.com/...</code>
2. 输入邮箱，收验证码
3. 验证通过后跳回 <code>https://firefire.online/admin/</code>
4. 再看到 qqbot 自己的登录页
5. 输入后台账号密码

这就叫双层登录：Cloudflare 管“谁能到门口”，qqbot 管“进来能做什么”。

如果直接看到 qqbot 登录页，说明 Access 没拦住，检查：

- Path 是不是 <code>admin*</code>
- 域名的云朵是不是橙色
- 应用是不是 Self-hosted
- 有没有点 Save

## 7. 锁源站

Access 只保护经过 Cloudflare 的请求。如果别人知道源站 IP，还能绕过 Access 直接访问。所以要做两步。

### 7.1 Nginx 取真实 IP

把仓库里的 <code>deploy/nginx/cloudflare-real-ip.conf</code> 放到服务器：

~~~bash
sudo mkdir -p /etc/nginx/snippets
sudo cp deploy/nginx/cloudflare-real-ip.conf /etc/nginx/snippets/
~~~

在 <code>/etc/nginx/sites-available/qqbot.conf</code> 的 HTTPS server 块里加：

~~~nginx
include /etc/nginx/snippets/cloudflare-real-ip.conf;
~~~

然后：

~~~bash
sudo nginx -t && sudo systemctl reload nginx
~~~

### 7.2 UFW 只放 Cloudflare

先把 Cloudflare 的回源段加进去，再删 443 的全放行。顺序反了会断站。

~~~bash
for cidr in $(curl -fsS https://www.cloudflare.com/ips-v4) $(curl -fsS https://www.cloudflare.com/ips-v6); do
  ufw allow from "$cidr" to any port 443 proto tcp
done

ufw status numbered
ufw delete allow 443/tcp
~~~

80 端口保留给 HTTP 跳转和证书。8080 不用动，它只给 Docker 和回环。

验证：从一台不是 Cloudflare 的机器直接打源站 IP，应该连不上。

## 8. 常见问题

**Access 登录后一直循环**

检查浏览器有没有禁用第三方 Cookie；Cloudflare Access 需要它。再检查 Path 是否覆盖了当前 URL。

**收不到验证码**

检查垃圾邮件；One-time PIN 的发件人是 Cloudflare。企业邮箱可能拦，换个人邮箱先测。

**公开站也跳 Access**

Path 写成了 <code>/</code> 或空。改成 <code>admin*</code>。

**后台 502 / 522**

Cloudflare 连不到源站。检查 Nginx、443、UFW。不要把 443 全删了再慢慢加。

**邮箱断了**

MX 记录没导入 Cloudflare。回 DNSPod 对照补上。

## 9. 回滚

如果迁移后发现问题：

1. 到注册商把 NS 改回：

   ~~~text
   john.dnspod.net
   modify.dnspod.net
   ~~~

2. 等 NS 生效。
3. Cloudflare 那边的记录先别删，留作对照。

Access 单独回滚：

1. 删除 Cloudflare Access 应用。
2. 恢复 UFW 的 <code>ufw allow 443/tcp</code>。
3. 删除 Nginx 的 <code>include</code>，<code>nginx -t && systemctl reload nginx</code>。

应用数据库和账号不受影响。
