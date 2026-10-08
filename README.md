# 问答机器人

**简体中文** ｜ [English](README.en.md)

提问 → 知识库检索 → 大模型回复。不训练模型，做的是检索、提示词编排、安全与成本控制。
另有公开站与管理后台，可在网页上直接体验。

| 维度 | 当前 | 换成别的要动哪里 |
|---|---|---|
| 消息平台 | QQ，经 NapCat / OneBot 11 | `onebot/` 与 `incoming/` |
| 知识库语料 | 《雾锁王国》 | 按[文档格式规范](docs/md/知识库文档格式规范.md)导入新语料 |
| 机器人人设 | `AGENTS.md`，模板 `AGENTS.md.example` | 只改这份文件，不用重新打包 |
| 对话模型 | opencode-go，降级 DeepSeek | 后台「设置」页，或 `llm/` |

[快速开始](#本地跑起来) · [部署](docs/md/公网部署指南.md) · [系统说明书](docs/md/系统说明书.md) · [架构图](docs/diagrams/system-architecture.html)

## 能力

- 知识库问答：正文向量、术语关键词、标题向量三路召回，RRF 融合后重排取 top-5，资料不足就直说没查到。
- 指令系统：`/help`、`/ping` 一类话术秒回，不走限流也不调模型。
- 安全中间层：五层本地拦截，出口再做敏感词过滤。
- 成本预算：每人、每群、全局每日额度与 token 上限，超限降级。
- 图片与引用：能读被引用的文字与图片。
- 问答广场：群内发起求助，投票认可的回答沉淀为改进提案。

三个入口：QQ 群、公开站 `public.html`、管理后台 `admin.html`。

## 技术栈

Java 17 / Spring Boot 3.5 / Maven｜LangChain4j｜SQLite 单文件｜Vue 3 / Vite 6 / TypeScript｜NapCat / OneBot 11｜Nginx / systemd｜JUnit 5 / ArchUnit / Playwright

## 结构与约定

```text
server/   Spring Boot 单体，唯一进生产的 Java
web/      前端双端源码：public/ admin/ shared/ theme/
deploy/   NapCat 容器与 Nginx 配置
scripts/  构建与自检脚本
docs/     md/ 文档源、html/ 渲染版、diagrams/ 图
tests/    黄金集、探针、Playwright
plans/    规划文档
```

四条铁律写进了测试：`onebot/` 是唯一知道 OneBot 协议的地方；`OneBotApiClient` 是唯一的发消息出口；`llm/` 是唯一知道用哪个模型的地方；`guard/` 是唯一有权拒绝的地方。

## 数据与配置

- 数据库在 `server/data/qa/qqbot.sqlite`，路径按进程工作目录解析，务必从 `server/` 启动。
- 配置优先级：命令行与环境变量 → `server/config/overrides.yml` → `application.yml`。
- 密钥放在仓库根 `.env`，模板 `.env.example`。
- 不进仓库：`.env`、`AGENTS.md`、`deploy/data/`、`server/data/`、`server/web-dist/`、`web/node_modules/`、自托管字体。

## 本地跑起来

需要 JDK 17+、Maven 3.9+、Node 20+ 与 pnpm、Docker。

```bash
cp .env.example .env                 # 填 ONEBOT_TOKEN、模型 key、SILICONFLOW_API_KEY、ADMIN_PASSWORD
cd deploy && docker compose up -d    # 起协议端并登录 QQ
./scripts/build-web.sh               # 构建前端并同步到 server/web-dist/
cd server && mvn spring-boot:run     # 起业务层 :8080
./scripts/health-check.sh            # 自检
```

前端开发模式：`cd web && pnpm install && pnpm dev`，公开站为 5173/public.html，后台为 5173/admin.html。

## 部署

形态是 NapCat 容器、systemd 托管 jar、Nginx 反代，步骤见[公网部署指南](docs/md/公网部署指南.md)。
更新流程：`./scripts/build-web.sh` 与 `./scripts/build-server.sh package -DskipTests`，换 jar 后 `systemctl restart`。

宿主与容器是两套环境，容器侧构建一律走 `.toolchain/`。

## 文档

| 想干什么 | 看哪份 |
|---|---|
| 了解全貌 | [系统说明书](docs/md/系统说明书.md) |
| 部署上线 | [公网部署指南](docs/md/公网部署指南.md) |
| 接 QQ | [协议层搭建指南](docs/md/协议层搭建指南.md) · [NapCat 新手接入](docs/md/napcat-新手接入指南.md) · [登录丢失排查](docs/md/NapCat登录丢失排查与解决.md) |
| 维护知识库 | [知识库运营手册](docs/md/知识库运营手册.md) · [导入与使用手册](docs/md/知识库导入与使用手册.md) · [文档格式规范](docs/md/知识库文档格式规范.md) |
| 运维命令 | [运维命令速查](docs/md/知识库运维命令速查.md) |
| 检索设计 | [检索基线](docs/md/检索基线.md) · [回答策略与知识库设施设计](docs/md/回答策略与知识库设施设计.md) |
| 改摄入 | [知识库摄入边界与激活协议](docs/md/知识库摄入边界与激活协议.md) |
| 前后端细节 | [server/README.md](server/README.md) · [web/README.md](web/README.md) |

## 许可

未附许可证文件。使用、转载或二次分发前请联系作者。
