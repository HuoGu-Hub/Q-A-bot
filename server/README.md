# server —— QQ Bot 业务层

协议端是 **NapCat**（OneBot 11），本工程是它背后的业务层。

```
QQ 用户 ─▶ NapCat(Docker) ──HTTP上报──▶ 本工程 ──HTTP动作──▶ NapCat ─▶ QQ
                          /onebot/event      /send_group_msg
                                │
                                ├─▶ GuardPipeline（安全中间层，零成本五层）
                                └─▶ ChatService ─▶ LlmRouter ─▶ 大模型
                                                   （多厂商 + 降级链）
```

**当前进度：M2.5 —— 大模型已接通（多厂商 + 降级链），安全中间层已装上。**

---

## 一、已经有的能力

### 大模型

| 能力 | 说明 |
|---|---|
| 真正调用大模型 | 回复由模型生成 |
| 多厂商可插拔 | 加厂商只要改 yml，**不用动一行 Java** |
| 自动降级链 | 主模型挂了/超时/额度用完，自动切下一个（已实测） |
| 人格独立成文件 | `resources/prompts/system.md`，改完重启即可，不用重新编译 |

### 安全中间层

判定顺序 **从最便宜到最贵**，前五层零成本，跑完才轮到花钱的模型：

```
1. access         谁有资格（私聊关闭、群/用户黑名单）
2. mention        群里必须 @ 我
3. rate-limit     每群 10 条/分钟，每人 1 次/分钟
4. content-gate   纯表情/图片无文字 → 回兜底词，不调模型
5. inbound-words  命中敏感词 → 回拒绝话术，不调模型
---------------- 到这里都还没花一分钱 ----------------
6. 大模型
7. outbound-words 出站过滤（比入站宽松）
```

| 能力 | 说明 |
|---|---|
| **只允许群聊** | 私聊默认关闭。策略写错也会按「关闭」处理（失败即拒绝） |
| **必须 @ 才回** | 群里日常聊天永远进不了大模型 |
| **双层限流** | 群级 + 用户级，滑动窗口精确计数 |
| **纯表情兜底** | 无文字内容直接回兜底词，不浪费 token |
| **敏感词表** | 独立文件管理（`resources/words/*.txt`），改完重启生效 |
| **应急开关** | `kill-switch: true` 立刻完全不回复 |

### 引用消息 与 图片理解

| 能力 | 说明 |
|---|---|
| **引用消息** | 用户引用某条消息再 @ 机器人时，会调 OneBot 的 @B@get_msg@B@ 把被引用的**文字和图片**都取回来一起喂给模型 |
| **图片理解** | 用户发的图片会被下载 → base64 → 交给支持视觉的模型 |
| **SSRF 防护** | 图片 URL 只允许公网 http/https，拒绝回环/内网/链路本地/CGNAT 地址 |

> ### 哪些模型能看图（实测）
> | 模型 | 识图 | 备注 |
> |---|---|---|
> | @B@deepseek-v4.1-flash@B@ | ✅ | **推理模型**，有「思考 token」，需给足 max-tokens |
> | @B@glm-5.3-flash@B@ | ✅ | 非推理，token 消耗少一半，但单价贵 |
> | @B@mimo-v2.5@B@ / @B@deepseek-v4-flash-vision-exp@B@ | ❌ | 返回空 |
> | @B@mimo-v2-omni@B@ | ❌ | 上游报错 |
>
> 换成不支持视觉的模型时，记得把 @B@app.llm.providers.opencode-go.capabilities@B@ 里的 @B@vision@B@ 删掉。
>
> ### ⚠️ 推理模型必须给足 max-tokens
> @B@deepseek-v4.1-flash@B@ 这类推理模型会先「思考」再回答，
> **思考消耗的 token 也算在 max-tokens 里**。给太小会出现
> 「思考完就没预算写答案」→ @B@content@B@ 返回空字符串 → 机器人发空白消息。
>
> 实测 1024 以上就正常，配置里默认给了 **8192**。
> 注意这是**上限不是目标** —— 调高本身不花钱，只有真的生成那么多才计费。
> 即使这样也做了**空回复兜底**：空内容会当成失败自动走降级链。
>
> ### 推理强度 reasoning-effort
> @B@low@B@ / @B@medium@B@ / @B@high@B@，留空 = 不传。只对推理模型有意义。
>
> **实测结论（别期待太高）**：OpenCode Go 接受这个参数，但模型端执行得不严格 ——
> 复杂问题思考 token 约降 20%（135 → 108），简单问题甚至可能反效果（24 → 53）。
> 设成 @B@low@B@ 至少不会更差，而且是个标准参数，换模型也能用。
>
> ⚠️ 非推理模型（如 @B@glm-5.3-flash@B@）收到这个参数可能报错，所以只有推理模型才配。

---

## 二、在 IDEA 里跑起来

### 1. 打开工程

IDEA → **Open** → 选中 `server/pom.xml` → 以 Maven 项目打开。

### 2. 配置密钥：用 `.env` 文件

仓库根目录放一个 `.env`：

```
ONEBOT_API_BASE=http://127.0.0.1:3000
ONEBOT_TOKEN=你在NapCat里设的那个Token
OPENCODE_GO_API_KEY=你的密钥
OPENCODE_GO_MODEL=模型ID
```

工程启动时会自动读它（`spring.config.import`），**IDEA 和命令行都生效，不用装插件**。
从模板复制即可：`cp .env.example .env`

> ⚠️ 三个注意点：
> 1. **properties 格式，不是 shell 脚本**：一行一个 `KEY=value`，**值不要加引号**
> 2. `.env` 已被 `.gitignore` 排除；要提交的是模板 `.env.example`
> 3. 想临时覆盖，**IDEA 的环境变量优先级更高**（环境变量 > `.env` > application.yml 默认值）

### 3. 确认模型名

`OPENCODE_GO_MODEL` 要用真实 ID，查一下：

```bash
curl https://opencode.ai/zen/go/v1/models
```

> ⚠️ OpenCode Go 的模型分三种接口，**只有走 `/chat/completions` 的能用本工程**：
> - ✅ **GLM / Kimi / DeepSeek / MiMo / LongCat** —— OpenAI 兼容
> - ❌ Qwen / MiniMax —— 走 `/messages`（Anthropic 格式）
> - ❌ Grok / GPT —— 走 `/responses`

### 4. 运行

启动成功的标志：

```
[LLM] provider 就绪：opencode-go  model=mimo-v2.5  baseUrl=https://opencode.ai/zen/go/v1
[LLM] 降级链：opencode-go -> deepseek
[GUARD] 安全中间层已启用
[GUARD]   顺序        ：access -> mention -> rate-limit -> content-gate -> inbound-words
[GUARD]   私聊策略    ：off
[GUARD]   限流        ：每群 10 条/分钟，每人 1 次/分钟，超限处理=silent
协议层连通 ✓  机器人：示例助手（QQ 100000003）
```

> ⚠️ **IDEA 正跑着的时候，别在容器里对着 `server/pom.xml` 跑 mvn**（2026-10-01 踩过，见
> `scripts/build-server.sh` 头注释）：
> IDEA 的运行 classpath 就是仓库里的 `server/target/classes`。容器那边一 `clean`／重编，
> 就把正在运行的 JVM 的 classpath 从脚底下抽走了 —— 启动时用不到的类（尤其是嵌套类，比如
> `BudgetGuard$Verdict`、`SiteTextService$BlockView`）要等第一次被调用才去磁盘上找，
> 这时文件已经被删掉或还没写完，于是抛出：
>
> ```
> java.lang.NoClassDefFoundError: com/example/qqbot/guard/BudgetGuard$Verdict
> Caused by: java.lang.ClassNotFoundException: com/example/qqbot/guard/BudgetGuard$Verdict
> ```
>
> 看着像「代码里少了个类」，其实代码没问题 —— 是热路径被人从底下抽走了。
> 出现后重启一次应用即可；容器里要构建请走 `./scripts/build-server.sh`
> （复制到 `.toolchain/server-ci/` 再编，产物在同名 `target/` 下，与 IDEA 的目录互不干扰）。

---

## 三、在群里刷屏之前，先用测试台验证

`../tests/manual/fake-onebot-server.mjs` 会**假装自己是 NapCat**，
但**大模型走你 `.env` 里的真实配置** —— 一次不碰 QQ 的真实端到端测试。

```bash
node tests/manual/fake-onebot-server.mjs
```

它会注入 8 组场景并逐项断言（只有 1 组会真的调模型，其余都是零成本拦截）：

```
1. A 群@ 有文字          → 调大模型回复              ✅
2. B 群@ 纯表情          → 兜底词，不调模型           ✅
3. C 群@ 提示注入        → 拒绝话术，不调模型         ✅
4. D 群里没 @            → 忽略                       ✅
5. E 私聊                → 忽略（私聊已关闭）          ✅
6. A 一分钟内再 @        → 被「每人每分钟 1 次」拦下   ✅
7. 机器人自己发的消息     → 忽略                       ✅
8. 另一个群 11 人刷纯表情 → 只回 10 条，第 11 被群限流  ✅
```

> ⚠️ 假 NapCat 占了 **3000** 端口，跟真 NapCat 冲突。二选一：
> `cd deploy && docker compose stop`，或者 `FAKE_API_PORT=3999 node ...` 并同步改 `.env`。

---

## 四、配置项

### 协议层

| 配置 | 环境变量 | 默认值 |
|---|---|---|
| `server.address` | — | `0.0.0.0`（**不要改成 127.0.0.1**） |
| `app.onebot.api-base` | `ONEBOT_API_BASE` | `http://127.0.0.1:3000` |
| `app.onebot.access-token` | `ONEBOT_TOKEN` | 空 |

### 安全中间层（`app.guard.*`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `guard.enabled` | `true` | 总开关（调试时可整个关掉） |
| `guard.kill-switch` | `false` | 应急开关，打开后完全不回复 |
| `guard.access.require-mention-in-group` | `true` | 群里必须 @ |
| `guard.access.private-chat-policy` | `"off"` | all / whitelist / off（**必须加引号**） |
| `guard.access.group-blacklist` | `[]` | 群黑名单 |
| `guard.access.user-blacklist` | `[]` | 用户黑名单 |
| `guard.rate-limit.per-group-per-minute` | `10` | 每群每分钟最多几条 |
| `guard.rate-limit.per-user-per-minute` | `1` | 每人每分钟最多几次 |
| `guard.rate-limit.on-limit` | `silent` | silent / notify-once |
| `guard.content-gate.no-text-reply` | — | 纯表情/图片时的兜底话术 |
| `guard.words.inbound-file` | `classpath:words/inbound.txt` | 入站词表位置 |
| `guard.words.outbound-file` | `classpath:words/outbound.txt` | 出站词表位置 |
| `guard.file-access.blocked-extensions` | `[".env", ...]` | 禁止读取的尾缀 |

> ### ⚠️ YAML 的 `off` 陷阱
> YAML 会把**不加引号的** `off` / `on` / `yes` / `no` 解析成布尔值。
> 所以 `private-chat-policy` 必须写成 `"off"`。
> （代码里做了兜底：认不出来的一律按「关闭私聊」处理，但配置还是写对更好。）

### 关键词表怎么维护

词表在 `src/main/resources/words/` 下，一行一个词，`#` 开头是注释：

- `inbound.txt` —— 命中后**不调模型**，直接回拒绝话术
- `outbound.txt` —— 命中后把整条回复换成兜底话术（比入站宽松）

改完**重启**生效。想改成外部文件（不用重新编译）：

```yaml
inbound-file: file:./words/inbound.txt
```

#### 排查误判：回复里带上命中的词

拒绝话术模板里写 `{word}`，就会被替换成**实际命中的词**：

```yaml
inbound-refusal-text: "喵喵喵？「{word}」这个词我不能聊呢喵~"
outbound-fallback-text: "主人不让我说「{word}」，我们换个话题吧～"
```

效果：

```
用户：最近新闻里那个洗钱案判了，你怎么看
机器人：喵喵喵？「洗钱」这个词我不能聊呢喵~     ← 一眼看出是「洗钱」误判
```

日志里也会写清楚是哪个词、以及去哪个文件删：

```
[GUARD] 入站命中词表「洗钱」→ 已拒绝（未调用大模型）。
        如果是误判，去 classpath:words/inbound.txt 里删掉这一行
```

> ⚠️ 回显意味着命中的词会被**发到群里**。调试期开着方便，等词表稳定了
> 建议把 `{word}` 从模板里删掉，恢复成不带词的回复。

### 大模型

| 配置 | 说明 |
|---|---|
| `app.llm.default-provider` | 默认用哪个厂商 |
| `app.llm.fallback-providers` | 降级顺序，例如 `[deepseek, qwen-vl]` |
| `app.llm.system-prompt-file` | 人格文件。默认 `AGENTS.md`，**权威副本在仓库根目录** |
| `app.llm.providers.<名字>.base-url` | 接口地址 |
| `app.llm.providers.<名字>.api-key` | **留空 = 跳过这个厂商** |
| `app.llm.providers.<名字>.model-name` | 模型 ID |
| `app.llm.providers.<名字>.capabilities` | `chat` / `vision` / `tools` |
| `app.llm.providers.<名字>.headers` | 额外请求头（OpenCode Go 需要） |

**加一个新厂商**只要这样，不用碰代码：

```yaml
app:
  llm:
    providers:
      zhipu:
        base-url: https://open.bigmodel.cn/api/paas/v4
        api-key: ${ZHIPU_API_KEY:}
        model-name: glm-4-plus
        capabilities: [chat]
```

### 管理后台

浏览器里看统计（S3）。**必须先设密码**：

```bash
# .env
ADMIN_PASSWORD=你的密码
```

然后访问 `http://127.0.0.1:8080/admin/`（会自动跳到登录页）。

| 配置 | 默认值 | 说明 |
|---|---|---|
| `app.admin.enabled` | `true` | 总开关 |
| `app.admin.password` | 空 | **留空 = 整个 /admin 返回 503** |
| `app.admin.session-hours` | `24` | 登录有效期 |
| `app.admin.record-visits` | `true` | 记录后台访问 |

**安全设计**：

- **没配密码 = 后台完全关闭**（503），不会退化成"无密码可访问"
- 密码比对用**常量时间比较**（先各自 SHA-256 再比），不泄露长度/前缀
- 会话是内存 token + HttpOnly cookie，重启即失效
- 后台**只读**：所有 API 都是 GET，不能改任何数据
- 访问统计里 IP **只存哈希**

**页面**：`login.html`（登录）、`index.html`（看板）、`glossary.html`（术语表管理）——
单文件 + 原生 fetch，零构建链。

**API**：

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/admin/api/overview` `keywords` `misses` `cosine` `sources` `records` `visits` `session` | 只读统计 |
| POST | `/admin/api/annotate` | 给记录打标（good / bad / no_source / hallucination） |
| GET | `/admin/api/glossary` | 读术语表（支持 `?q=` 搜索） |
| POST | `/admin/api/glossary` | 新增/更新术语，**立即热重载** |
| POST | `/admin/api/glossary/status` | 只改状态（draft / verified / rejected） |
| POST | `/admin/api/glossary/delete` | 删除术语 |

### 闭环：发现问题 → 修 → 验证

```text
① 看板「未命中：连词都没认出来」里发现「灵火祭坛」
② 去术语表管理页，把 Flame Altar 的中文名改成「灵火祭坛、火焰祭坛」
③ 保存 → 立即生效，不用重启
④ 再问一次 → 认出来了 → 命中知识库 → 答对
```

**追问检测**：同一个人 60 秒内又问一次，自动把**上一条**标为 `follow_up` ——
这是不花钱的准确率信号（答好了通常不会马上再问）。
它**只覆盖未标注的记录**，不会覆盖你的人工结论。

### 系统日志

管理后台 `/admin/logs` 页，**两个数据源刻意分开**：

| Tab | 来源 | 说明 |
|---|---|---|
| **业务层** | 本进程内存环形缓冲 + SSE | 实时跟随、级别过滤、搜索 |
| **NapCat** | 读另一个容器的日志文件 | 文件列表 + 只读尾部 |

**分开的价值在于定位故障层级**：

```text
群里 @ 机器人
   ├─ NapCat 日志里没有这条消息  → 协议层问题（掉线/风控）
   ├─ NapCat 有、业务层没有      → 上报失败（ECONNREFUSED）
   ├─ 两层都有但没回复           → Guard 拦了 / 模型挂了
   └─ 都正常但答得不对           → 检索或 prompt 问题
```

| 配置 | 默认值 | 说明 |
|---|---|---|
| `app.logs.enabled` | `true` | 总开关 |
| `app.logs.buffer-size` | `2000` | 内存保留条数（DEBUG 不进缓冲，太吵） |
| `app.logs.mask-sensitive` | `true` | **脱敏**：token/密钥/QQ号/带签名的 URL |
| `app.logs.napcat-log-dir` | `../deploy/data/napcat/logs` | NapCat 日志目录，留空则关闭该 Tab |
| `app.logs.stream-timeout-minutes` | `30` | SSE 连接最长存活 |
| `app.logs.max-streams` | `10` | 并发日志流上限 |

**日志落三处**（`logback-spring.xml`）：

1. 控制台 → systemd 收进 journal
2. **滚动文件** `data/logs/qqbot.log` —— ★ **进程崩了之后唯一留存的地方**
3. 内存环形缓冲 → 网页实时页

> ⚠️ **网页和进程是一体的**：进程一崩，网页也跟着没了。
> 想看崩溃瞬间发生了什么，**只能靠文件**（`tail -100 data/logs/qqbot.log`）。

### 模型配置（只改模型 ID）

管理后台 `/admin/models` 页。**只有模型 ID 可以改**，其余全部只读 ——
接口地址、API Key、能力标记改错的代价是"机器人直接哑掉"或"密钥泄露"。

| 能力 | 说明 |
|---|---|
| **只读展示** | API Key（**掩码，永不回显**）、接口地址、能力/温度/超时 |
| **可改** | 模型 ID —— 下拉（拉取可用列表）+ 手动输入 |
| **测试连通性** | 建临时客户端发最小请求，**不改动任何状态** |
| **保存** | 默认 `verify=true`：**先测再换，测不通就拒绝保存** |
| **热生效** | 改完立即生效，**不用重启** |

热生效的原理：`models` 是个 Map，用新 model-name 重建客户端后替换其中一项；
正在进行的请求持有的旧引用仍然有效，天然无缝。

> ⚠️ **配置覆盖层只有一个写入口**。`config/overrides.yml` 是 YAML，
> **不能有重复的顶层键** —— 曾经 `SettingsService` 和 `ModelController` 各写各的，
> 生成了两个 `app:` 块，YAML 解析直接失败、服务起不来。
> 现在统一走 `OverridesFile`：读全量 → 合并 → **按路径树整份重写**。

### 指令系统

管理后台 `/admin/commands` 页。

| 项 | 说明 |
|---|---|
| 触发格式 | 群里 **@ + /命令 + 完全相等**；私聊只用 `/` |
| 处理位置 | **准入之后、Guard 之前** —— 命令秒回、不受限流、不走模型 |
| 内置命令 | `/help` `/list` `/ping` `/stats`（不可删，可改回复） |
| `{cmd.list}` | 渲染所有指令 —— `/help` 就是用它实现的（**不写死**） |
| 变量 | 15 个：`{user}` `{time}` `{kb.count}` `{qa.total}` … |
| 高级变量 | `{user.id}` `{group.id}` **默认关闭**（会在群里打出 QQ 号） |

### 问答记录与统计

每次问答都会**异步**记一条结构化记录，用来做使用统计和知识库质量评估。

| 配置 | 默认值 | 说明 |
|---|---|---|
| `app.qa.enabled` | `true` | 记录总开关（关掉后回答流程完全不变） |
| `app.qa.db` | `./data/qa/qa.sqlite` | SQLite 文件 |
| `app.qa.retention-days` | `180` | **原文**保留天数，`0` = 永不删除 |
| `app.qa.queue-capacity` | `2000` | 异步队列容量，满了丢弃并计数 |
| `app.qa.cleanup-cron` | 每天 4:30 | 清理任务 cron |

**两层数据策略**：

| 层 | 存什么 | 生命周期 |
|---|---|---|
| `qa_raw` | 问题原文 / 引用原文 / 回答原文 | **定期删** |
| `qa_stat` | 时间 / 群 / 用户 / 检索指标 / 耗时 / 标注 | **永久** |
| `qa_keyword` | 从问题抽出的游戏名词 | **永久** |

> **删原文不需要先做聚合** —— 统计需要的字段本来就在 `qa_stat` 里。
> 清理任务只删 `qa_raw`，统计表和关键词表一行不动。

**三条硬性约束**（都有单测守着）：

1. 投递**立即返回**，绝不在回答线程里碰数据库
2. 队列**有界**，满了丢弃并计数 —— 宁可少记一条，不可积压内存
3. 存储故障时**只记日志**，机器人照常回答

> 记下来的 `best_cosine`（最高余弦）是调 `app.kb.min-score` 阈值的依据；
> `hit_count = 0` 的问题排行则直接告诉我们要给术语表补哪些词。

**看统计报表**（S2）：

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--app.qa.report.enabled=true --spring.main.web-application-type=none"
```

只看某一段：`--app.qa.report.section=misses`
（`all` / `overview` / `keywords` / `misses` / `cosine` / `sources`）

报表用**只读连接**读同一个 SQLite 文件，所以可以一边跑机器人一边看报表。

### 媒体文件（临时图片 / 知识库图片）

图片会以两种**生命周期完全相反**的身份存在，必须物理隔离：

| 目录 | 身份 | 谁能删 |
|---|---|---|
| `data/tmp-images/` | 临时图片：用户发来的图、生成/转发用的中间产物 | 定时清理，按**最后使用时间** |
| `data/kb-images/` | 知识库图片：长期资产 | **任何清理逻辑都不能碰** |

> 目录里的 `inbound/` 是**图片缓存**（见下），`outbound/` 留给以后生成/转发的中间产物。

| 配置 | 默认值 | 说明 |
|---|---|---|
| `app.media.max-images-per-message` | `3` | **单条消息最多处理几张图**（内存闸门，见下）。`0` = 不限制 |
| `app.media.temp-images.enabled` | `true` | 清理总开关 |
| `app.media.temp-images.dir` | `./data/tmp-images` | 相对路径以**程序工作目录**为准，启动日志会打印绝对路径 |
| `app.media.temp-images.retention-hours` | `24` | **最后使用时间**超过这个时长的文件才会被删 |
| `app.media.temp-images.cleanup-interval-minutes` | `60` | 清理频率。**进程启动后会立刻先清一轮** |
| `app.media.temp-images.max-delete-per-run` | `500` | 单轮删除上限，防止一次性打满磁盘 IO |
| `app.media.temp-images.max-total-size-mb` | `200` | 缓存总量上限，超出后按最后使用时间淘汰（LRU）。`0` = 不限制 |
| `app.media.kb-images.dir` | `./data/kb-images` | 知识库图片目录 |

#### 图片缓存：同一张图只下载一次

**为什么需要**：部署到服务器后每月流量有限，而原来的行为是「每有人 @ 一张图就重新下载一遍」。
QQ 的图片 URL 是 **rkey 签名的、会过期**的（实测过期后返回 `download url has expired`），
所以不能靠 URL 复用，必须自己存一份。

**怎么认出「同一张图」**：用 OneBot 图片段里的 `file` 字段 ——
QQ 用图片内容的 MD5 给文件命名（真实样本 `65A82BE1AE2810AEB287D78C2260C823.jpg`），
这个值**在下载之前就能拿到**。所以缓存命中时**一个字节的网络流量都不花**。

| 场景 | 行为 |
|---|---|
| 首次收到某张图 | 下载 → 落盘到 `tmp-images/inbound/<md5>.jpg` |
| 再次有人发/引用这张图 | **不下载**，直接读本地文件，日志打 `图片缓存命中，跳过下载` |
| `file` 不是 MD5（别的客户端） | 退化为不缓存，功能不受影响 |

**「最后使用时间」是怎么算的**：**不用系统 atime** —— 实测工作区是 `noatime` 挂载，
atime 根本不更新。做法是**每次命中都把文件 mtime 刷成当前时间**，
于是「mtime = 最后一次被使用的时间」，清理任务直接按它删即可，不需要额外维护索引。

**两级淘汰**：先按保留时长删过期文件，再按最后使用时间把总量压回 `max-total-size-mb` 以内。
只做 TTL 不够 —— 热门群可能一小时内就塞进几百兆，保留时长还没到磁盘就先满了。

#### 单条消息图片上限（内存闸门）

磁盘缓存省的是**流量**，不省**内存** —— 命中缓存后依然是「读文件 → 转 Base64」，
单张峰值约占文件大小的 **2.33 倍**（`byte[]` 1 倍 + Base64 字符串 1.33 倍）。

真正的内存风险来自两个因素相乘：

| 因素 | 现状 |
|---|---|
| 事件线程池并发 | `app.async.core-pool-size: 8` |
| 单条消息图片数 | **原来是无限的** |

一条带 10 张 1.3MB 图的群消息 = 10 × 3MB = 30MB，× 8 并发 = **240MB**，
而 2G 内存的机器上 JVM 默认最大堆只有约 512MB。

所以 `app.media.max-images-per-message` 默认 **3**：只取前 N 张，
超出的**只记日志、不回话**（避免"限制"本身变成刷屏）：

```
[MEDIA] 一条消息带了 7 张图，超过上限 3，只处理前 3 张（群=100000002 用户=100000001）
```

> 注意：**降采样没有做** —— 它会改变图片内容、可能影响"看清图里的小字"这类识别效果。
> 代价是**上传给模型 API 的流量没有优化**（每次视觉调用都要把 Base64 传出去）。

**配错就直接拒绝启动**（靠"写配置时小心点"是防不住的）：

- 两个目录配成**同一个路径**；
- 知识库目录配到了临时目录**里面**（递归清理会连它一起删）；
- 临时目录不在 `app.guard.file-access.allowed-roots` 范围内
  （否则清理会被 PathGuard 全部拦下，看起来"跑了"但什么都没删）。

每轮清理都会打一行日志，删了多少、释放多少一眼可见：

```
[MEDIA] 图片缓存目录：/app/.../data/tmp-images/inbound（当前占用 0 B）
[MEDIA] 图片缓存命中，跳过下载：key=65a82be1ae2810aeb287d78c2260c823 大小=1.3 MB 类型=image/jpeg
[MEDIA] 临时图片清理：候选 12 个，删除 12 个，跳过 0 个，失败 0 个，释放 8.4 MB，清理空目录 2 个，超限淘汰 0 个（保留 24 小时，目录 /app/.../data/tmp-images）
[MEDIA] 图片缓存超过上限 200 MB，按最后使用时间淘汰 37 个文件，释放 61.2 MB，当前 180.0 MB
```

> 清理**每个文件都过一遍 `PathGuard`** —— 目录物理隔离是第一道防线，`PathGuard` 是第二道。

---

## 五、目录结构

```
src/main/java/com/example/qqbot/
├─ QqbotServerApplication.java   启动入口 + 协议层连通性自检
├─ config/
│  ├─ OneBotProperties.java        app.onebot.*
│  ├─ GuardProperties.java         app.guard.*（安全中间层全部配置）
│  ├─ LlmProperties.java           app.llm.*（多厂商配置模型）
│  ├─ KbProperties.java            app.kb.*（知识库检索）
│  ├─ QaProperties.java            app.qa.*（问答记录）
│  ├─ MediaProperties.java         app.media.*（媒体目录与清理策略）
│  └─ AsyncConfig.java             事件处理线程池
├─ onebot/                    ★  唯一跟 NapCat 打交道的地方
│  ├─ BotIdentity.java             机器人自己的 QQ 号
│  ├─ model/OneBotEvent.java       OneBot 11 事件 POJO
│  ├─ model/ImageRef.java          一张图：缓存键(MD5) + URL + 大小
│  ├─ codec/MessageCodec.java      消息段解析与构造
│  └─ client/OneBotApiClient.java  ★ 全工程唯一发消息的出口
├─ incoming/                     事件入口 (HTTP Controller)
├─ router/MessageRouter.java     编排：Guard → 模型 → 出站过滤 → 发送
├─ agent/ChatService.java        组装上下文 + 调模型 + 兜底话术
├─ media/                     ★  图片文件的目录与生命周期
│  ├─ MediaStorageGuard.java      启动校验：临时/知识库目录必须物理隔离，配错拒绝启动
│  ├─ ImageCache.java             图片缓存：按内容 MD5 复用，命中零下载；LRU 控制总量
│  ├─ ImageFetcher.java           先查缓存再下载（含 SSRF 防护与大小上限）
│  └─ TempImageCleaner.java       定时清理 + 超限淘汰（每个文件都过 PathGuard）
├─ qa/                        ★  问答记录与统计
│  ├─ QaStore.java                SQLite 存储：建表 + 幂等迁移 + 批量写 + 清理
│  ├─ QaRecorder.java             异步单线程队列（有界、丢弃计数、吞异常）
│  ├─ QaCollector.java            组装记录：抽关键词、序列化检索结果
│  └─ QaCleaner.java              到期清理（只删原文，不动统计）
├─ llm/                       ★  多厂商路由
│  ├─ LlmRouter.java               建模型 / 降级 / 日志
│  └─ LlmException.java
└─ guard/                     ★  安全中间层
   ├─ GuardPipeline.java           按顺序跑完所有 stage
   ├─ GuardStage.java              接口（name + check）
   ├─ GuardResult.java             Pass / Drop / Reply
   ├─ GuardContext.java            判定上下文
   ├─ RateLimiter.java             滑动窗口计数
   ├─ WordList.java                词表加载与匹配
   ├─ OutboundFilter.java          出站过滤
   ├─ PathGuard.java               文件访问守卫（预留给未来的文件工具）
   ├─ MessageDeduplicator.java     消息去重
   └─ stage/
      ├─ AccessControlStage.java   1 准入
      ├─ MentionRequiredStage.java 2 必须 @
      ├─ RateLimitStage.java       3 频率限制
      ├─ ContentGateStage.java     4 纯表情兜底
      └─ InboundWordStage.java     5 入站敏感词

src/main/resources/
├─ application.yml
├─ prompts/system.md             人格与边界（改这个不用重新编译）
└─ words/
   ├─ inbound.txt                入站词表
   └─ outbound.txt               出站词表
```

### 四条结构铁律

1. **`onebot/` 包是唯一允许出现 OneBot 协议细节的地方**
2. **`OneBotApiClient` 是全工程唯一能发消息的出口**
3. **`llm/` 包是唯一知道「用哪个模型」的地方**
4. **`guard/` 包是唯一有权说「不」的地方**

### ⚠️ PathGuard 的使用契约

`PathGuard` 是一个**策略组件，它自己拦不住任何东西**。
将来任何要读文件的代码（文件工具 / 知识库加载 / 上传文件解析），
**都必须先调用 `PathGuard.check()` 并确认 allowed 才能继续读**，否则形同虚设。

> 第一个真实调用方是 `media/TempImageCleaner`：删每个文件之前都会先过一遍 `PathGuard.check()`，
> 被拦下的文件只记日志、绝不删除。

---

## 六、下一步

| 阶段 | 内容 | 状态 |
|---|---|---|
| **M3** | 联网搜索 / 工具调用（走 OpenCode Go） | 待办 |
| **M4** | 知识库检索（RAG） | 待办 |
| **M5** | 图片理解 | `capabilities: [vision]` 已预留 |
| **M6** | 定时消息推送 | 不受 @ 限制，走独立通道 |
| **待设计** | **多用户并发 @ 的处理**（串行化 / 排队 / 合并） | 未定方案 |

> 明确**不做**的：多轮上下文记忆（按当前使用场景不需要）、成本预算。
