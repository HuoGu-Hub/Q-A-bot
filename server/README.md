# server

Spring Boot 单体，承担 QQ 事件处理、知识库检索与作答、命令与板块、地图出图、公开站接口与管理后台接口。

## 运行

```bash
cp ../.env.example ../.env
mvn spring-boot:run
```

服务监听 `0.0.0.0:8080`。数据库、语料、图片与日志默认落在 `server/data/`，路径相对进程工作目录。启动日志会打印解析后的绝对路径。

在仓库根目录构建：

```bash
./scripts/build-server.sh package -DskipTests
```

直接对 `server/pom.xml` 执行 `mvn clean` 会删除 IDE 正在使用的 `target/classes`，运行中的进程会抛出 `NoClassDefFoundError`。

## 模块

| 包 | 职责 |
|---|---|
| `onebot` | 协议模型、消息编解码、HTTP 客户端、出站发送 |
| `incoming` | 事件上报入口，去重后返回 204 |
| `router` | 分诊、排队、准入、命令与问答编排 |
| `guard` | 准入、限流、内容闸门、敏感词、预算、出站过滤与节奏 |
| `agent` | 对话消息组装与模型调用 |
| `command` | 命令定义、解析、权限、板块与定时投递 |
| `kb` | 语料、召回、融合、重排、注入、工具 |
| `kb.block` | 块存储、文档导入、标题向量 |
| `kb.wiki` | wiki 抓取、清洗、分块、导入 |
| `kb.map` | 地图同步、点位、瓦片、截图渲染、预热 |
| `llm` | 模型路由、降级链、系统提示词、工具装配 |
| `qa` | 问答记录、统计、保留期清理 |
| `media` | 图片下载、缓存、标记替换、存储隔离 |
| `prompt` | 群与个人补充提示、系统回复 |
| `publicapi` | 公开站只读接口与 SPA 路由回退 |
| `admin` | 管理后台鉴权、统计、地图、轮播 |
| `site` | 页面文案与轮播 |
| `settings` | 配置白名单、热更新、覆盖层持久化 |
| `persistence` | SQLite 连接、建表、仓储 |
| `config` | 配置属性与装配 |
| `logs` | 环形缓冲、脱敏、SSE 日志流 |
| `text` / `time` / `trace` | 文本工具、时间工具、检索追踪 DTO |

## 依赖边界

以下规则由 `ArchitectureTest` 强制执行，违反即构建失败：

1. 只有 `llm` 依赖模型 SDK，只有 `llm` 与 `kb` 出现框架检索类型。
2. 业务包不得依赖 `onebot.client`，只有 `onebot.outbound` 与 `router` 能接触发送客户端。
3. `guard` 不得依赖 `kb`、`qa`、`llm`，保证零成本执行。
4. `java.sql` 只允许出现在 `persistence`。
5. `kb` 不得依赖 `qa`、`publicapi`、`admin`、`settings`。
6. `kb` 核心不得反向依赖 `kb.wiki`、`kb.map`、`kb.block`、`kb.catalog`、`kb.category`。
7. 业务包不得依赖 `config`，配置通过只读策略接口注入。
8. 业务包不得注入 `GuardProperties` 这一整块配置对象。

新增包时需要同步更新 `ArchitectureTest` 中的包名单。

## 关键约定

| 约定 | 说明 |
|---|---|
| 发送 | 所有消息经 `OutboundSender`，由它完成过滤、分片、合并转发与节奏控制 |
| 配置 | 业务侧声明只读接口，由 `config` 实现。配置对象被长期持有，热改即时生效 |
| 文件访问 | 任何读文件的代码必须先调用 `PathGuard.check()`，确认允许后再读 |
| 数据库 | 业务侧只使用仓储，不出现 SQL；建表迁移必须幂等并兼容老库 |
| 检索 | 算法只保留一份，存储通过 `KbCorpus` 接口替换 |
| 图 | 命中块中的 `[[img:来源:名字]]` 由出站层替换，模型只负责原样抄写 |

## 测试

```bash
./scripts/build-server.sh test
./scripts/golden-gate.sh
```

架构测试、单元测试与检索门禁的说明见 [docs/development.md](../docs/development.md)。
