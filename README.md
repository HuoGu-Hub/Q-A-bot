# 飘雪喵 · 群问答与运营机器人

<p align="center">
  <b>群里问一句，先查自己的知识库，再由大模型组织成人话</b><br>
  板块定时、地图出图、官网与后台，都跑在同一个进程里
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white" alt="Java">
  <img src="https://img.shields.io/badge/Spring_Boot-3.5.16-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Vue-3.5-42B883?logo=vuedotjs&logoColor=white" alt="Vue">
  <img src="https://img.shields.io/badge/SQLite-3.53-003B57?logo=sqlite&logoColor=white" alt="SQLite">
  <img src="https://img.shields.io/badge/QQ-OneBot_11-12B7F5" alt="QQ">
</p>

> 📖 **文档**：[系统说明书](docs/md/系统说明书.md) ｜ [公网部署指南](docs/md/公网部署指南.md) ｜ [架构图](docs/diagrams/system-architecture.html) ｜ [English](README.en.md)

---

## 一、项目说明

群友 @ 机器人提问，系统先在自有知识库里检索，再把检索到的内容交给大模型组织成回答。同一套后端还负责斜杠命令、定时板块、交互地图与官网内容。

| 入口 | 给谁用 | 形态 |
|---|---|---|
| QQ 群 | 群友 | @ 机器人提问、`/命令`、板块定时投递 |
| 公开站 | 群友与访客 | `public.html`：资料库、问答广场、交互地图、关于 |
| 管理后台 | 站长 | `admin.html`：看板、知识库、板块与命令、地图、站点、系统 |

| 维度 | 当前 | 换成别的要动哪里 |
|---|---|---|
| 消息平台 | QQ，经 NapCat / OneBot 11 | `onebot/` 与 `incoming/` |
| 知识库语料 | 《雾锁王国》 | 按[文档格式规范](docs/md/知识库文档格式规范.md)导入新语料 |
| 机器人人设 | `system.md`，模板 `system.md.example` | 改这份文件后重启，不走后台 |
| 群与个人补充提示 | `bot_prompt` 表 | 群里 `/prompt`，或后台「提示词」 |
| 对话模型 | OpenCode Go，降级 DeepSeek | 后台「模型」页只换 model-name，厂商与降级链在配置 |

四条铁律加十条结构规则由 `ArchitectureTest` 强制，越界依赖会让构建失败：

| 铁律 | 含义 |
|---|---|
| `onebot/` | 唯一知道 OneBot 协议的地方 |
| `OneBotApiClient` | 唯一发消息出口 |
| `llm/` | 唯一知道用哪个模型的地方 |
| `guard/` | 唯一有权拒绝的地方 |

## ⚙️ 工作原理

![系统架构](docs/diagrams/system-architecture.png)

一条消息的路径：事件接入与分诊 → 先判命令，命中就地秒回 → 五层 Guard → 两路召回与重排 → 模型作答 → 分片回群。到点要发的板块走另一条路：写一条记录，由投递器扫表发出。

| 环节 | 做什么 |
|---|---|
| 接入与分诊 | 去重后立即返回 204，按正文首字符分投问答池与命令池；排队超 60 秒回一句"人多稍等" |
| 命令与板块 | 斜杠命令在 Guard 之前解析渲染，不调模型也不进问答统计；板块把"到点要发"写成记录，15 秒扫一次抢行投递 |
| 安全与成本 | 准入、提及、限流、内容闸门、入站词五层本地判定，零 token 成本；出口再做一次敏感词过滤 |
| 检索 | 正文向量与术语关键词两路召回，标题向量闸门命中即置顶，RRF 融合后交交叉编码器重排取 top-5 |
| 作答 | 检索结果以 `<<<KNOWLEDGE>>>` 交给模型，可回调只读的 `lookup`、`locate`、`search_titles` 三个工具；资料不足时直说没查到 |
| 出站 | 摘掉图片标记、取图、过滤、分片、控节奏，唯一出口 |
| 地图出图 | 模型只负责抄一行取图凭据，坐标换算与 Java2D 截图都在服务端完成，取不到图就只回文字 |

## 二、功能

### 群友能用的

| 能力 | 说明 |
|---|---|
| 知识库问答 | 两路召回加重排，取 top-5 交给模型；资料不足不编造 |
| 工具调用 | 模型可回调只读工具 `lookup`、`locate`、`search_titles`；白名单默认拒绝，无调用方身份一律拒绝，轮次上限 3 |
| 指令系统 | `/help`、`/ping` 一类话术秒回，不走限流也不调模型 |
| 板块与定时 | `/板块 /create` 建一条到点要发的公告，支持每天、每周 1-7、每 2-30 天、共 N 次 |
| 地点问答 | 问某处怎么走，回一段说明并附一张带点位的地图截图 |
| 图片与引用 | 读被引用的文字与图片，带图提问走视觉模型 |
| 回复带图 | 回复里的 `[[img:来源:名字]]` 换成真图，与正文同一条消息发出；来源按 `ImageSource` 扩展，现有 `kb:` 知识库配图与 `map:` 地图截图 |
| 问答广场 | 群内发起求助，投票认可的回答沉淀为改进提案 |

### 站长能改的

| 能力 | 说明 |
|---|---|
| 权限 | 六档档位加单命令与全局两级名单；板块用负数键共享同一张名单表 |
| 板块与命令 | 参数表、投递方式、说明文案、list/help 回复、变量插入，都在后台拼 |
| 知识库 | 文档、词条、分类、wiki 抓取、改进提案 |
| 地图 | 点位增删改、给 wiki 点位打备注、启停图层、整体偏移复核、瓦片预热 |
| 站点 | 首页轮播与各页文案；正文默认值在代码里，表里只存覆盖值，清空即回默认 |
| 提示词与模型 | 群与个人补充提示；模型页测连通性、拉模型列表、热切换 model-name |
| 统计与日志 | 问答原文与派生指标、关键词、命中率、后台访问；日志面板走 SSE 实时看 |
| 配置中心 | 白名单键热生效并写回 `config/overrides.yml`，改字段不重建 Bean |

## 三、上游依赖

| 服务 | 用途 | 配置项 |
|---|---|---|
| [OpenCode Go](https://opencode.ai) | 对话模型，默认，支持视觉与工具 | `OPENCODE_GO_API_KEY`、`OPENCODE_GO_MODEL` |
| [DeepSeek](https://api.deepseek.com) | 对话模型，降级链 | `DEEPSEEK_API_KEY` |
| [硅基流动](https://siliconflow.cn) | 向量 `BAAI/bge-m3`，重排 `BAAI/bge-reranker-v2-m3` | `SILICONFLOW_API_KEY` |
| [阿里云百炼](https://dashscope.aliyun.com) | 备用向量，默认关闭 | `DASHSCOPE_API_KEY` |
| [NapCat](https://github.com/NapNeko/NapCatQQ) | QQ 协议端，OneBot 11 | `ONEBOT_API_BASE`、`ONEBOT_TOKEN` |
| 《雾锁王国》wiki | 语料与交互地图来源 | 后台 wiki 抓取与地图同步配置 |

## 四、技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17，Spring Boot 3.5.16，Maven 3.9+ |
| 模型接入 | LangChain4j 1.20.0，OpenAI 兼容协议，多厂商即多实例加降级链 |
| 存储 | SQLite 单文件，sqlite-jdbc 3.53.4.0，33 张表，WAL，单连接单锁 |
| 地图出图 | Java2D 按视口裁瓦片、描点、编码 JPEG，瓦片落磁盘缓存 |
| 表格导入导出 | Apache POI 5.3.0 |
| 前端 | Vue 3.5，vue-router 4，Vite 6，TypeScript 5.7，自研组件库 `web/src/shared/ui` |
| 协议端 | NapCat，Docker，OneBot 11 |
| 反向代理与托管 | Nginx，systemd |
| 工程质量 | JUnit 5，AssertJ，Mockito，ArchUnit 1.3.0，Playwright |

## 🗂 项目结构

```text
server/   Spring Boot 单体，唯一进生产的 Java；322 个源文件，951 个用例
web/      前端双端：public/ 7 个页面，admin/ 33 个面板，shared/ 与 theme/
deploy/   NapCat 容器与 Nginx 配置
scripts/  构建、自检、回归门禁、wiki 导入
docs/     md/ 文档源，html/ 渲染版，diagrams/ 架构图
data/     SQLite、地图瓦片、日志与临时图
tests/    黄金集、探针、Playwright
plans/    规划文档
```

## 🛠 开发环境

| 项目 | 版本 / 说明 |
|---|---|
| JDK | 17 起，本仓库在 21 上运行 |
| 构建 | Maven 3.9+ |
| 前端 | Node 20+ 与 pnpm；93 个文件约 2.5 万行 |
| 数据库 | SQLite，文件在 `server/data/qa/qqbot.sqlite`，路径按进程工作目录解析，务必从 `server/` 启动 |
| 协议端 | Docker，NapCat 容器 |
| 测试 | 后端 951 个用例，前端 `vue-tsc` 加 `vite build` |

### 本地跑起来

```bash
cp .env.example .env                 # 填 ONEBOT_TOKEN、模型 key、SILICONFLOW_API_KEY、ADMIN_PASSWORD
cd deploy && docker compose up -d    # 起协议端并登录 QQ
./scripts/build-web.sh               # 构建前端并同步到 server/web-dist/
cd server && mvn spring-boot:run     # 起业务层 :8080
./scripts/health-check.sh            # 自检
```

前端开发模式：`cd web && pnpm install && pnpm dev`，公开站为 5173/public.html，后台为 5173/admin.html。

## 五、部署

NapCat 容器、systemd 托管 jar、Nginx 反代，步骤见[公网部署指南](docs/md/公网部署指南.md)。

| 操作 | 命令 |
|---|---|
| 构建前端 | `./scripts/build-web.sh` |
| 构建后端 | `./scripts/build-server.sh package -DskipTests` |
| 更新 | 换 jar 后 `systemctl restart` |
| 上线前自检 | `./scripts/preflight.sh` |
| 检索回归 | `./scripts/golden-gate.sh` |
| 校验知识库文档 | `./scripts/validate-kb-doc.sh` |

宿主与容器是两套环境，容器侧构建一律走 `.toolchain/`。

## 六、文档索引

| 想干什么 | 看哪份 |
|---|---|
| 了解全貌 | [系统说明书](docs/md/系统说明书.md) |
| 部署上线 | [公网部署指南](docs/md/公网部署指南.md) |
| 接 QQ | [协议层搭建指南](docs/md/协议层搭建指南.md) · [NapCat 新手接入](docs/md/napcat-新手接入指南.md) · [登录丢失排查](docs/md/NapCat登录丢失排查与解决.md) |
| 维护知识库 | [知识库运营手册](docs/md/知识库运营手册.md) · [导入与使用手册](docs/md/知识库导入与使用手册.md) · [文档格式规范](docs/md/知识库文档格式规范.md) |
| 运维命令 | [运维命令速查](docs/md/知识库运维命令速查.md) |
| 检索设计 | [检索基线](docs/md/检索基线.md) · [回答策略与知识库设施设计](docs/md/回答策略与知识库设施设计.md) |
| 改摄入 | [知识库摄入边界与激活协议](docs/md/知识库摄入边界与激活协议.md) |
| 数据口径 | [数据大屏数据准确性核查](docs/md/数据大屏数据准确性核查.md) |
| 前后端细节 | [server/README.md](server/README.md) · [web/README.md](web/README.md) |

## 许可

未附许可证文件。使用、转载或二次分发前请联系作者。
