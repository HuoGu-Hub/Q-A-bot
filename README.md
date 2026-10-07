# 飘雪喵 · 问答机器人

**简体中文** ｜ [English](README.en.md)

> 一个**问答机器人**：把「群聊里的一句提问」变成「先查我们自己的知识库、再由大模型组织成人话」的回答。
> 它**不训练模型** —— 做的是 **检索 + 提示词编排 + 安全与成本控制**；
> 外面还带一个**公开站**（给群友看资料）和一个**管理后台**（给自己维护知识库）。

**适配现状** —— 下面两个"目前只有"是现状，但结构上都是可替换的：

| 维度 | 当前 | 换成别的要动哪里 |
|---|---|---|
| 消息平台 | **只适配了 QQ**（经 NapCat / OneBot 11）| `onebot/`（协议细节）+ `incoming/`（唯一入口）；检索 / 知识库 / 安全这些机器人逻辑不用动 |
| 知识库语料 | **只装了《雾锁王国》** | 纯数据：按[文档格式规范](docs/md/知识库文档格式规范.md)导入新语料；wiki 抓取的来源在配置里 |
| 机器人人设 | `AGENTS.md`（不进仓库，模板 `AGENTS.md.example`）| 改这一份文件即可，**不用重新打包** |
| 对话模型 | opencode-go（默认）→ DeepSeek（降级）| 后台「设置」页；`llm/` 是唯一知道「用哪个模型」的地方 |

[快速开始](#八本地跑起来) · [部署](docs/md/公网部署指南.md) · [系统说明书](docs/md/系统说明书.md) · [架构图](docs/diagrams/system-architecture.html)

## 目录

1. [功能一览](#一功能一览)
2. [技术栈](#二技术栈)
3. [系统架构](#三系统架构)
4. [接口设计](#四接口设计)
5. [模块与文件分布](#五模块与文件分布)
6. [数据与配置](#六数据与配置)
7. [工程质量与验证](#七工程质量与验证)
8. [本地跑起来](#八本地跑起来)
9. [部署到生产](#九部署到生产)
10. [文档索引](#十文档索引)

---

## 一、功能一览

系统有三个入口：

| 入口 | 给谁用 | 形态 |
|---|---|---|
| **QQ 群** | 群友 | @ 机器人提问 / `/命令`（**当前唯一适配的平台**）|
| **公开站** | 群友与访客 | `public.html`（`/`）—— 浏览资料库、问答广场 |
| **管理后台** | 站长 | `admin.html`（`/admin`）—— 看板、知识库、设置 |

### 1.1 群聊侧（当前 = QQ）

- **知识库问答**：A 路（正文向量）+ B 路（术语关键词）+ C 路（标题向量闸门）三路召回，
  RRF 融合 + 重排取 top-5，拼成 `<<<KNOWLEDGE>>>` 交给模型组织回答；资料不足时明确说"没查到"，不编。
- **指令系统**：话术类指令（`/help` `/ping` …）**秒回，不走限流、不调模型**，支持变量渲染。
- **安全中间层**：`access → mention → rate-limit → content-gate → inbound-words` 五层**零成本**拦截
  （全在本地判断，不花 token），出口还有敏感词过滤。
- **成本预算**：每人 / 每群 / 全局每日额度 + 全局 token 上限，超了就降级而不是失控。
- **引用消息与图片**：能读被引用的文字与图片；带图提问走视觉模型。
- **问答广场**：群里发「求助：xxx」→ 只发到当前群，群友投票，被认可的回答沉淀成改进提案。

### 1.2 公开站

首页（轮播 + 站点文案）、资料库（分类浏览 / 搜索 / 词条详情）、问答广场、关于。

### 1.3 管理后台

看板（统计、命中率、来源）、问答记录、**知识库四个面板**（文档 / 词条 / 分类 / 提案）、
指令管理、设置（配置中心）、模型、日志、页面文案与轮播、广场治理、安全指标。

### 1.4 知识库 —— 系统的核心资产

- 两条**平级**的摄入链：
  1. 人工 / AI 按规范写好的 `.md` 文档 → 管理后台「文档」面板导入；
  2. wiki 自动批量抓取（CLI 批处理，**可缺省** —— 抓不到就人工补，核心照常运转；当前抓的是《雾锁王国》wiki）。
- 两条链在「写块」这一步汇合，之后**写入即生效**：块索引与词条表按语料版本自动失效 / 补齐，
  不需要重启，也不需要调用方记得做什么。
- 所有需要长期保留的**人工成果**（词条中文名、分类映射、提案…）都落在同一个 SQLite 文件里。

---

## 二、技术栈

| 层 | 选型 |
|---|---|
| 后端 | **Java 17**、**Spring Boot 3.5.16**（单体 + 内嵌 Tomcat）、Maven 3.9+ |
| 模型接入 | **LangChain4j 1.20.0**（`langchain4j-open-ai`，OpenAI 协议兼容）—— 多厂商就是建多个实例 + 降级链 |
| 存储 | **SQLite**（`sqlite-jdbc 3.53.4.0`）单文件；知识库与统计**同库** |
| 表格导入导出 | Apache POI 5.3.0（词条表 xlsx） |
| 前端 | **Vue 3.5**（`<script setup>`）+ vue-router 4 + **Vite 6** + TypeScript 5.7 + 自研组件库（`web/src/shared/ui/`，17 个）|
| QQ 协议端 | **NapCat**（Docker，OneBot 11）—— 业务层完全不碰 QQ 协议 |
| 外部服务 | 对话模型 **opencode-go**（默认，带视觉）→ **DeepSeek** 降级；向量模型 **硅基流动 `BAAI/bge-m3`**（1024 维）|
| 反向代理 | Nginx（TLS + 限流 + IP 白名单） |
| 进程托管 | systemd（`-Xmx512m`，`ProtectSystem=strict`） |
| 工程质量 | JUnit 5 + AssertJ + Mockito + **ArchUnit**（架构护栏：越界依赖会让构建失败）；Playwright 脚本（`tests/`）|

---

## 三、系统架构

```text
        QQ 用户
           │  @机器人 / /命令
           ▼
   ┌───────────────────┐        ┌──────────────────────────────┐
   │ NapCat  (Docker)  │        │  大模型（OpenAI 兼容）        │
   │ OneBot 11 协议端   │        │  opencode-go（默认，带视觉）  │
   │ 只翻译协议，无业务  │        │  → deepseek（降级链）        │
   └─────────┬─────────┘        └──────────────▲───────────────┘
             │ POST /onebot/event              │
             │ /send_group_msg …               │
             ▼                                  │
   ┌──────────────────────────────────────────┐ │
   │  Spring Boot 业务层 :8080                 │ │
   │  incoming → router → agent → llm          │ │
   │     ├─ guard/   五层零成本拦截             │ │
   │     ├─ command/ 指令系统                  │ │
   │     ├─ kb/      A/B/C 三路检索 ───────────┼─┘ embedding
   │     ├─ media/   图片缓存与清理             │   SiliconFlow bge-m3
   │     └─ qa/      异步记录                  │
   │  onebot/  唯一与 NapCat 打交道的包         │
   └───────────────┬──────────────────────────┘
                   ▼
        server/data/qa/qqbot.sqlite
        统计 / 原文 / 词条 / 分类 / 块+向量 / 标题向量 /
        提案 / 指令 / 广场投票与求助 / 后台访问

   前端（Vue3）→ server/web-dist/
   public.html（公开站）  admin.html（后台）
                   ▲
              Nginx :443
     www.example.com   → 公开站（限流 60r/m）
     admin.example.com → 后台（IP 白名单）
```

### 3.1 一条消息的生命周期

1. NapCat 上报 `POST /onebot/event` → 控制层**立即返回 200**，事件丢进线程池（core=max=8、队列 100，排队超 20 秒直接丢弃并礼貌回一句）；
2. 忽略机器人自己发的消息 → 黑名单准入 → 指令匹配（话术类到此结束，不调模型）；
3. Guard 五层 → 成本预算 → 解析引用消息；
4. `ChatService`：检索知识库（A/B/C 三路 + RRF + 重排）→ 组装提示词 → 调模型（带降级链）；
5. 出站敏感词过滤 → `OutboundSender`（间隔 + 抖动 + 分片 + 合并转发）→ `OneBotApiClient` → NapCat → QQ；
6. 同时：问答记录**异步**落库，绝不阻塞回答线程。

### 3.2 四条结构铁律（写进测试的）

1. `onebot/` 是唯一允许出现 OneBot 协议细节的地方；
2. `OneBotApiClient` 是全工程唯一能发消息的出口；
3. `llm/` 是唯一知道「用哪个模型」的地方；
4. `guard/` 是唯一有权说「不」的地方。

外加一条数据侧约定：**需要长期保留的人工成果都落在同一个 SQLite 文件**
（知识库块存储复用 `QaStore` 的连接 —— 两个连接开同一个 SQLite 文件会让 WAL 共享内存冲突）。

这些不只是文档：`ArchitectureTest`（ArchUnit）把其中若干条变成了**会失败的测试**，
还包括「知识库核心不得依赖摄入来源」「摄入来源不得持有内部索引 / 词条表」这类耦合约束。

### 3.3 图（可直接打开）

| 图 | 看什么 |
|---|---|
| [系统架构图](docs/diagrams/system-architecture.html) | 组件拓扑、分层与依赖方向 |
| [消息流程图](docs/diagrams/message-workflow.html) | 一条消息从上报到回复的完整分支 |
| [知识库数据流](docs/diagrams/kb-dataflow.html) | 两条摄入链 → 存储 → 检索三路 |
| [问答广场闭环](docs/diagrams/plaza-loop.html) | 求助 → 投票 → 提案 → 采纳 |

---

## 四、接口设计

三组前缀，鉴权与限流各不相同：

| 前缀 | 用途 | 鉴权 | 限流 |
|---|---|---|---|
| `/onebot/**` | NapCat 上报入口（`POST /onebot/event`）| Token | — |
| `/api/public/**` | 公开站只读 + 投票 / 降级 | 无 | 应用层 60 次/分/IP + Nginx 一层 |
| `/admin/api/**` | 管理后台 | 会话 Cookie（未登录 401 / 未配置密码 503）| 登录接口额外限流 |

几条约定：

1. **唯一上报入口**：`POST /onebot/event` 先立即返回 200、再异步处理 —— 上报方不该等我们。
2. **公开接口**以只读为主：`/site` `/stats` `/kb/search` `/kb/browse` `/kb/page` `/carousel` `/plaza/*`。
3. **后台绝不裸奔**：`ADMIN_PASSWORD` 为空时整个 `/admin` 返回 **503**；未登录时 API 返回 **401 JSON**、
   页面重定向到登录页（`/admin/api/login` 除外）。
4. **返回形态**：成功 `{"ok":true, …}`，失败 `{"ok":false,"error":"人话说明"}`。
5. **双入口 SPA**：`/public.html` 与 `/admin.html` 两个入口，其余前端路由由服务端回落转发。

完整端点清单（按域分组，含知识库四个面板的全部接口）见
[系统说明书 §七](docs/md/系统说明书.md)。

---

## 五、模块与文件分布

### 5.1 仓库顶层

| 目录 | 职责 |
|---|---|
| `server/` | **业务层**（Spring Boot 单体，唯一进生产的 Java 代码）|
| `web/` | **前端双端源码**：`public/` 公开站、`admin/` 后台、`shared/` 组件与 API、`theme/` 设计令牌 |
| `deploy/` | 生产部署：`docker-compose.yml`（只跑 NapCat）+ `nginx/qqbot.conf` |
| `scripts/` | 构建与自检：`build-server.sh` `build-web.sh` `preflight.sh` `health-check.sh` `validate-kb-doc.sh` |
| `docs/` | `md/`（文档源）+ `html/`（渲染版）+ `diagrams/`（架构 / 流程 / 数据流图）|
| `tests/` | 验证脚本与夹具：`golden/`（检索黄金集）、`manual/`、`cleanup-sql/`、`screenshots/` |
| `plans/` | 规划类文档 |
| `NapCatQQ/` | 协议层源码**参考**（约 493MB，不进仓库，仅本地查阅）|
| `data/`、`server/data/` | 运行时数据（SQLite / 图片 / 日志）—— **不进仓库** |

### 5.2 后端包地图（`server/src/main/java/com/example/qqbot/`）

| 包 | 职责 |
|---|---|
| `config/` | 配置模型 + 启动期校验 + `.env` 加载 |
| `incoming/` · `router/` · `agent/` | 事件入口 → 编排 → 组装上下文并调模型 |
| `onebot/` | 协议细节（**唯一**）：编解码、动作客户端、出站节流 |
| `guard/`（+`stage/`）| 安全与成本：五层拦截、限流、预算、出站过滤 |
| `command/` | 指令系统：匹配、存储、变量渲染 |
| `kb/` | 知识库：`block`（块 + 索引）`wiki`（抓取与清洗）`doc`（文档导入）`term`（词条）`category` `proposal` `map` |
| `llm/` | 多厂商路由与降级（**唯一**知道用哪个模型）|
| `media/` | 图片获取、缓存、存储防护、临时文件清理 |
| `qa/` | 问答记录与统计 |
| `plaza/` | 问答广场：投票、聚合、降级 |
| `publicapi/` | 公开站接口（独立 DTO，不暴露内部模型）|
| `admin/` | 后台鉴权与看板 |
| `settings/` | 配置中心（白名单 + 覆盖层文件）|
| `site/` | 公开站文案与轮播 |
| `logs/` | 日志缓冲与脱敏 |
| `persistence/` | **所有 SQL 与 `java.sql` 都在这里** |
| `trace/` · `time/` · `files/` | 检索轨迹、时间与文件小工具 |

### 5.3 前端结构（`web/src/`）

| 目录 | 职责 |
|---|---|
| `public/` | 公开站：路由、视图、组件 |
| `admin/` | 管理后台：路由、视图（知识库面板在 `views/kb/`）|
| `shared/` | 自研 UI 组件（`ui/`）、API 客户端与类型、通用 composable |
| `theme/` | 设计令牌与主题（「雾中灵火」v2）|

---

## 六、数据与配置

- **数据库**：`server/data/qa/qqbot.sqlite` —— ⚠️ 路径按**进程工作目录**解析，
  所以务必从 `server/` 目录启动；换个目录启动等于换了一整套数据。
- **存什么**：问答记录与统计、知识库块 + 向量、标题向量、词条、分类、提案、指令、广场投票与求助、后台访问。
- **配置三层优先级**（高 → 低）：命令行 / 环境变量 → `server/config/overrides.yml`（后台「设置」页写入的覆盖层）→ `application.yml`。
- **密钥**：仓库根的 `.env`（模板 `.env.example`）—— `.env` **永不进仓库**；
  机器人人设是 `AGENTS.md`，同样不进仓库（仓库里是 `AGENTS.md.example` 模板）。
- **不进仓库的还有**：`deploy/data/`（NapCat 登录态与日志，日志在 debug 级别会明文记 Token）、
  `server/data/`、`server/web-dist/`、`web/node_modules/`、自托管字体。

---

## 七、工程质量与验证

| 做什么 | 怎么跑 |
|---|---|
| 全量测试 | `./scripts/build-server.sh`（容器侧隔离构建）或 `mvn -f server/pom.xml test` |
| 检索质量 | `tests/golden/` 黄金集 + `RetrievalGoldenTest`（`-Dqqbot.golden.rerank=off` 可关重排做对比）|
| 架构护栏 | `ArchitectureTest`（ArchUnit）：依赖方向越界 → 构建直接失败 |
| 上线前 | `./scripts/preflight.sh` |
| 上线后 | `./scripts/health-check.sh`（默认探 `http://127.0.0.1:8080`）|
| 知识库文档格式 | `./scripts/validate-kb-doc.sh` |

当前规模：**58 个测试类 / 431 个用例**（其中 3 个是需要联网的 E2E，默认跳过）。

---

## 八、本地跑起来

前置：**JDK 17+**、**Maven 3.9+**、**Node 20+ / pnpm**、**Docker**（跑 NapCat）。

```bash
# 1) 配置密钥（仓库根目录）
cp .env.example .env       # 填 ONEBOT_TOKEN / 模型 key / SILICONFLOW_API_KEY / ADMIN_PASSWORD

# 2) 起协议端（登录 QQ：看容器日志里的 WebUI 地址）
cd deploy && docker compose up -d && docker compose logs -f

# 3) 构建前端 → 同步到 server/web-dist/
./scripts/build-web.sh

# 4) 起业务层（:8080）
cd server && mvn spring-boot:run

# 5) 自检
./scripts/health-check.sh
```

前端开发模式（热更新，`/api` 与 `/admin/api` 会代理到 8080）：

```bash
cd web && pnpm install && pnpm dev
# 公开站 → http://localhost:5173/public.html
# 管理后台 → http://localhost:5173/admin.html
```

不碰 QQ 也能验全链路：见 `tests/manual/` 与 `scripts/health-check.sh`。

---

## 九、部署到生产

形态：**NapCat 容器 + systemd 托管 jar + Nginx 反代**（TLS / 限流 / IP 白名单）。

- 完整步骤：[公网部署指南](docs/md/公网部署指南.md)（也有 [HTML 版](docs/html/公网部署指南.html)）；
- 协议层：[协议层搭建指南](docs/md/协议层搭建指南.md)、[NapCat 新手接入](docs/md/napcat-新手接入指南.md)；
- 更新流程：`./scripts/build-web.sh` + `./scripts/build-server.sh package -DskipTests` → 换 jar → `systemctl restart`。

⚠️ **宿主与容器是两套环境，不要混用**：容器侧构建一律走 `.toolchain/`（隔离 `server/target` 与
`web/node_modules`），否则会出现「类找不到」「pnpm 报 EACCES」这类看着莫名的问题 —— 原因写在脚本顶部注释里。

---

## 十、文档索引

| 想干什么 | 看哪份 |
|---|---|
| 了解系统全貌 | [系统说明书](docs/md/系统说明书.md) |
| 部署上线 | [公网部署指南](docs/md/公网部署指南.md) |
| 接 QQ / 协议层 | [协议层搭建指南](docs/md/协议层搭建指南.md) · [NapCat 新手接入](docs/md/napcat-新手接入指南.md) · [登录丢失排查](docs/md/NapCat登录丢失排查与解决.md) |
| 维护知识库 | [知识库运营手册](docs/md/知识库运营手册.md) · [导入与使用手册](docs/md/知识库导入与使用手册.md) · [文档格式规范](docs/md/知识库文档格式规范.md) |
| 运维命令速查 | [运维命令速查](docs/md/知识库运维命令速查.md) |
| 检索为什么这么设计 | [检索基线](docs/md/检索基线.md) · [回答策略与知识库设施设计](docs/md/回答策略与知识库设施设计.md) |
| 改摄入 / 写新的导入器 | [知识库摄入边界与激活协议](docs/md/知识库摄入边界与激活协议.md) |
| wiki 清洗的缺陷与修复记录 | [清洗缺陷清单与修复方案](docs/md/知识库wiki清洗缺陷清单与修复方案.md) |
| 数据大屏口径 | [数据大屏数据准确性核查](docs/md/数据大屏数据准确性核查.md) |
| 后端 / 前端各自的细节 | [server/README.md](server/README.md) · [web/README.md](web/README.md) |

---

## 许可

仓库未附许可证文件。如需使用、转载或二次分发，请先联系作者。
