# NapCat 掉登录与账号风控：根因与解决

> ⚠️ **先看这个**：如果你遇到的不只是掉登录，而是**账号被冻结、要求身份认证（扫脸）**，
> 请直接跳到第四部分 —— 那是腾讯风控层面的问题，和第八部分的「设备指纹」是两码事。

> 第一部分结论：**腾讯的设备标识 GUID = MD5(/etc/machine-id + MAC 地址)**，
> 而容器重建会让这两个值变化 → 腾讯认为是新设备 → 登录票据作废 → 必须重新扫码。

---

## 一、根因（从 NapCat 源码里挖出来的）

NapCat 自己的代码注释（`packages/napcat-webui-backend/src/utils/guid.ts:142`）写得很直白：

```
 * GUID 生成算法:
 *   GUID = MD5( /etc/machine-id + MAC地址 )
```

也就是说，**设备指纹由两个东西决定**：

| 输入 | 存在哪 | 容器重建后会变吗 |
|---|---|---|
| MAC 地址 | NapCat 记在 `data/qq/nt_qq/global/nt_data/msf/machine-info` | 记录本身不变，但**容器实际的 MAC 会变** ❌ |
| `/etc/machine-id` | **容器自己的文件系统里** | **可能变** ❌ |

任一个变了 → GUID 变了 → 腾讯把这次登录当成「陌生设备」→ 触发风控 → 登录票据失效。

### 为什么是容器重建而不是重启

- `docker compose restart` —— 容器不重建，**MAC 和 machine-id 都不变** ✅
- `docker compose up -d`（配置有改动时）—— **容器被销毁重建**，Docker 分配新的随机 MAC ❌

### ⚠️ 这一点我有一份责任

前面为了加日志目录、改配置，我多次让你执行 `docker compose up -d` ——
**每次都会重建容器，每次都可能让 GUID 变化**。这是导致你频繁掉登录的直接原因之一。
以后改 compose 之前，先按下面的方案把 MAC 固定住。

---

## 二、社区里搜到的前例（你的情况不是个例）

| 说法 | 出处 | 可信度 |
|---|---|---|
| 「**每次重建容器都会重新生成 MAC 地址**，建议尽量用 restart 而非重建；若必须重建，可固定 MAC」 | NapCat-Docker 相关教程 | ✅ 与源码分析一致 |
| 「Linux 下 NapCat 使用 `/etc/machine-id` 的 MD5 与 MAC 等生成设备 GUID，WebUI 内提供 GUIDManager 可查看/备份/恢复」 | NapCat 文档 | ✅ 源码确认 |
| 「新号特别容易掉，挂上一到两周稳定后就好很多」 | LINUX DO 社区 | ⚠️ 经验之谈 |
| 「长时间不登录（>7 天）仍可能要求重新验证」 | 社区 | ⚠️ 需注意 |
| 「加 `-e NAPCAT_QQ=机器人QQ号` 可在重启时快速登录」 | NapCat-Docker | ✅ 建议加 |

---

## 三、解决方案（按顺序做）

### 第 1 步：先把当前的设备标识取出来（**关键，别跳过**）

⚠️ **直接改 MAC 会立刻掉登录** —— 必须先把现在正在用的值记下来，然后固定成同一个值。

```bash
# 1) 当前的 MAC 地址
docker exec napcat cat /sys/class/net/eth0/address

# 2) 容器内的 machine-id
docker exec napcat cat /etc/machine-id

# 3) NapCat 记下来的设备 MAC（应该和第 1 步一致）
cat deploy/data/qq/nt_qq/global/nt_data/msf/machine-info | tail -c 17; echo
```

**把前两个值抄下来。**

### 第 2 步：固定 MAC 地址

在 `deploy/docker-compose.yml` 的 `services.napcat` 下加一行（值换成第 1 步查到的）：

```yaml
    mac_address: "9a:c2:cc:a8:3c:33"    # ← 换成你的实际值，冒号分隔
```

### 第 3 步：固定 machine-id（如果第 1 步查到的不是固定值）

先把当前值写进一个文件：

```bash
docker exec napcat cat /etc/machine-id > deploy/data/machine-id
```

然后在 compose 里加挂载：

```yaml
    volumes:
      - ./data/machine-id:/etc/machine-id:ro
```

> 怎么判断要不要做：**重建一次容器，再查一次 `/etc/machine-id`，如果变了就必须固定。**

### 第 4 步：启用快速登录（避免每次重启都要扫码）

**推荐做法：在 WebUI 里设置，不用重建容器。**

> WebUI → **配置** → **登录配置** → **快速登录 QQ** → 填你的机器人 QQ 号 → 保存

这个页面里还有一个 **GUID 管理器**，可以查看/备份/恢复设备标识 —— 对第一部分的问题很有用。

**为什么推荐 WebUI 而不是改 compose**：改 compose 要 `up -d` 重建容器，
而重建会改变设备指纹（见第一部分），可能又触发一次风控。

---

**备选做法：用环境变量**（需要重建容器，所以建议和固定 MAC 一起做）

⚠️ 变量名是 **`NAPCAT_QUICK_ACCOUNT`**，不是 `NAPCAT_QQ`（后者在源码里根本不存在）。

```
# deploy/.env
NAPCAT_QUICK_ACCOUNT=100000003
```

```yaml
# deploy/docker-compose.yml
    environment:
      NAPCAT_QUICK_ACCOUNT: "${NAPCAT_QUICK_ACCOUNT:-}"
```

**关于 `-q` 命令行参数**：源码里确实支持（`packages/napcat-shell/base.ts:727`），
格式是 `-q <QQ号>`，但它主要用于 **Shell / AppImage 版**部署。
Docker 里用上面的环境变量等价且更简单（环境变量的逻辑在 `base.ts:543`）。

**可选的密码回退**：快速登录失败时可以自动用密码回退（同样不推荐明文）：

```yaml
      NAPCAT_QUICK_PASSWORD_MD5: "${NAPCAT_QUICK_PASSWORD_MD5:-}"   # 32 位 MD5，推荐
      NAPCAT_QUICK_PASSWORD: "${NAPCAT_QUICK_PASSWORD:-}"           # 明文，不推荐
```

### 第 5 步：改变操作习惯

| 场景 | 该用什么 |
|---|---|
| 只想重启 NapCat | `docker compose restart` ✅ |
| 改了 compose（加挂载/环境变量） | `docker compose up -d`（会重建，但 MAC 已固定就安全了） |
| 只是想让它重连 | 别动容器，去 WebUI 点重连 |

### 第 6 步：备份登录态

```bash
# 登录态全在 deploy/data/qq 里，定期备份
tar czf napcat-login-backup-$(date +%Y%m%d).tar.gz -C deploy/data qq
```

出问题时把这个目录恢复回去，通常就能免扫码恢复登录。

---

## 四、验证是否修好了

做完第 2~4 步后，**故意重建一次容器**，然后检查：

```bash
# 重建前后各执行一次，对比输出
echo "machine-id: $(docker exec napcat cat /etc/machine-id)"
echo "mac        : $(docker exec napcat cat /sys/class/net/eth0/address)"
```

**两个值都不变 → 修好了。** 之后重建容器应该能自动快速登录，不用扫码。

---

## 五、如果已经掉登录了怎么恢复

1. **先别再重建容器**（每重建一次情况可能更糟）
2. 把之前备份的 `deploy/data/qq` 恢复回去
3. 如果没备份：只能重新扫码，但**扫码前先把 MAC 和 machine-id 固定住**，否则下次还会掉
4. WebUI 的 **日志/设备** 页面可以看到当前的 GUID，掉登录前后对比能确认是不是 GUID 变了

---

## 六、还有两件事要知道

1. **新号更容易掉**：社区反馈新注册的号前期风控严，挂一两周稳定后会明显好转。
2. **别频繁重启**：即使固定了标识，短时间内反复重启仍可能触发风控。
3. **超过 7 天不登录**：可能被要求重新验证。

---

## 四、更严重的问题：账号被冻结 / 要求身份认证

> 如果你的号**不只是掉登录，而是被冻结、要上传身份证/扫脸才能解封**，
> 那问题不在你的配置，而在**腾讯的第三方协议风控**。

### 4.1 为什么会被冻结

| 原因 | 说明 |
|---|---|
| **协议被检测** | NapCat 本质是 hook 官方 NTQQ 客户端。腾讯只要检测到注入/hook，就按「外挂」处理 |
| **版本问题（最直接）** | **GitHub Issue #1728**：最新版 NapCat/QQ 会被风控频繁踢下线，几小时内就掉 |
| **端口暴露** | 2025.9.5 有攻击者扫未设 token 的公网 OneBot 服务发 `send_msg`，导致**大量账号被永久封禁** |
| **新号** | 社区共识：新注册的 QQ 号**基本 100% 会被风控一次**，且往往冻结几次 |
| **IP / 环境** | 机房 IP、境外 IP、频繁换 IP 风控严重；Windows 环境比 Linux 更容易被踢 |
| **离开时间** | 超过 7 天不登录可能被要求重新验证 |

### 4.2 社区验证过的解决方案（按有效性排序）

**① 降版本（社区最推荐）**

```yaml
# deploy/docker-compose.yml
image: mlikiowa/napcat-docker:v4.15.19
```

社区实测：**连续在线 63+ 小时不掉**。同时有人建议把 QQ 版本回退到 3.2.21-42086 / 3.2.23-44343。

```bash
cd deploy
# 改完 image 后
docker compose pull && docker compose up -d
```

**② 关闭端口公网暴露（已帮你改好）**

```yaml
ports:
  - "127.0.0.1:3000:3000"    # ← 只绑本机
  - "127.0.0.1:3001:3001"
  - "127.0.0.1:6099:6099"
```

业务层就在同一台机器上，**没有任何理由让 OneBot 接口暴露到公网**。

**③ 用专用小号，不要用常用号**

这是最重要的一条。冻结会**逐步升级**：先同意协议 → 再上传身份证 → 再扫脸 → 最后可能变成**只读账号**。
用日常号做实验，代价太大。

**④ 养号**

新号前期风控严，社区反馈**挂一到两周稳定后会明显好转**。前期别猛发消息、别频繁重启。

**⑤ IP 要和手机 QQ 的地区接近**

本地家宽最稳。机房/云服务器 IP 风控严重，境外 IP 更糟。

**⑥ 固定设备指纹**（见第一部分）

把 MAC 和 machine-id 固定住，减少「陌生设备」的判定次数。

### 4.3 解封流程

1. 手机 QQ → 账号被冻结提示 → 按引导操作
2. 依次可能是：**同意规则 → 上传身份证 → 扫脸认证**
3. 社区经验：如果扫脸一直失败，可以**先卸载机器人环境、装官方 QQ 登录一次**再试

### 4.4 ⚠️ 必须知道的现实

> **第三方协议这条路，永远存在被封的风险。**
>
> - NapCat 已经是同类方案里风险最低的（基于官方 NTQQ，不是纯逆向协议）
> - 但腾讯的风控策略一直在变，**任何第三方方案都不能保证不封号**
> - 如果你的场景**要求稳定、可对外提供服务**，应该认真考虑
>   **QQ 官方开放平台**（零封号风险，代价是能力受限、审核门槛）
>
> 用第三方协议的合理定位是：**个人使用、专用小号、接受偶尔掉线和封号风险**。

---

## 附：一句话总结

**两个层面的问题，别混在一起：**

| 现象 | 根因 | 解决 |
|---|---|---|
| 掉登录、要重新扫码 | 设备指纹（GUID）变了 | 固定 MAC + machine-id + 开快速登录（第一~三部分） |
| **被冻结、要身份认证** | **腾讯第三方协议风控** | **降版本 + 关端口 + 专用小号 + 养号**（第四部分） |

> 关机重启本身不会「导致」封号，但它会触发一次完整的重新登录 ——
> 正好撞上风控窗口。**版本旧一点、端口收起来、号养熟一点**，这两件事都会好很多。

---

## 八、自动登录：现状与验证（2026-09-21 实测）

### 8.1 通过 WebUI API 查到的真实状态

NapCat 的 WebUI 提供了一套完整的登录管理接口（鉴权算法就是 `sha256(token + '.napcat')`），
可以直接查状态。实测结果：

| 检查项 | 结果 |
|---|---|
| 登录状态 | ✅ 已登录在线（`loginPhase: ready`，示例助手 100000003） |
| **快速登录 QQ 配置** | ❌ **空的 —— 这就是重启后要扫码的原因** |
| 平台 | linux |
| `/etc/machine-id` | `8e1509f19f844df08b0c4497e27dcc37` |
| machine-info 里的 MAC | `9a-c2-cc-a8-3c-33` |
| 算出的设备 GUID | `a886f57a0cf0104a762c4fcd6fa3220b` |
| 设备标识备份 | ❌ 一个都没有 |

### 8.2 自动登录的完整链路

重启后能不能自动登录，取决于**这三件事同时成立**：

```
① 登录票据持久化        deploy/data/qq 已挂载            ✅ 已满足
② 快速登录账号已配置    autoLoginAccount 或环境变量       ❌ 原来是空的
③ 设备指纹没变          MAC + /etc/machine-id 稳定        ⚠️ 重建容器会变
```

第 ② 条的判定逻辑在 NapCat 源码 `base.ts:543`：

```javascript
const hasAutoLogin = process.env['NAPCAT_QUICK_ACCOUNT'] || (quickLoginList.length > 0);
if (hasAutoLogin || NAPCAT_QUICK_PASSWORD...) { hasAttemptedFallback = true; }
await WebUiDataRuntime.runWebUiConfigQuickFunction();   // 用 autoLoginAccount 去快速登录
```

而 `runWebUiConfigQuickFunction` 里：

```javascript
const autoLoginAccount = process.env['NAPCAT_QUICK_ACCOUNT'] || WebUiConfig.getAutoLoginAccount();
if (!autoLoginAccount) { /* 什么都不做，直接走二维码 */ }
```

**所以没有 `autoLoginAccount` 就一定会出二维码 —— 这正是你遇到的情况。**

### 8.3 已经做好的配置 ✅

通过 WebUI API 完成了三件事（**全程没有重建容器**）：

| 动作 | 结果 |
|---|---|
| 创建设备标识备份 | `machine-info.bak.20260921024218` |
| 设置快速登录 QQ | `autoLoginAccount = "100000003"`（已写入 webui.json） |
| 记录设备指纹 | MAC / machine-id / GUID 三个值都已记下 |

### 8.4 怎么验证（重要）

**推荐用 `restart` 而不是关机**，因为它不重建容器，设备指纹绝对不变：

```bash
# 终端 1：开始监控
node tests/manual/watch-napcat-login.mjs

# 终端 2：重启（注意是 restart，不是 up -d）
cd deploy && docker compose restart napcat
```

终端 1 会实时打印登录阶段：

```
  [    0.0s] 服务不可达（正在重启中…）
  [   12.3s] loginPhase=offline  isLogin=false  coreReady=false
  [   28.7s] loginPhase=reconnecting  isLogin=false  coreReady=false
  [   41.2s] loginPhase=ready  isLogin=true  coreReady=true

✅ 登录完成（用时 41.2 秒）
```

**看到从 `offline` 直接变 `ready`、全程没有停在 `qrcode` 阶段 → 自动登录成功。**
**如果停在 `qrcode` → 说明快速登录失败了，得扫码。**

### 8.5 万一自动登录失败怎么办

NapCat 会自动降级，按这个顺序尝试：

```
① 快速登录（用缓存的票据）   → 失败
② 密码回退（需要配密码环境变量）→ 没配就跳过
③ 二维码                    → 需要人工扫码
```

想补上第 ② 层，可以配：

```yaml
# deploy/docker-compose.yml
NAPCAT_QUICK_PASSWORD_MD5: "你的QQ密码的32位MD5"
```

> ⚠️ 配密码等于把账号密码交给机器人，**安全性明显下降**。除非你确实需要，
> 否则不建议 —— 扫码一次之后票据就能重新生效。

### 8.6 一键恢复设备标识（万一又掉登录）

如果确认是设备指纹被人为改了（比如重建容器），可以恢复：

```bash
# 通过 WebUI：配置 → 登录配置 → GUID 管理器 → 从备份恢复
# 或者调接口：
#   POST /api/QQLogin/RestoreLinuxMachineInfoBackup  {"fileName":"machine-info.bak.20260921024218"}
```

恢复后设备指纹回到备份时的状态，腾讯就不会认为是新设备了。

