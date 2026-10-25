# 开发与测试

## 1. 环境

| 组件 | 版本 |
|---|---|
| JDK | 17，仓库在 21 上也能运行 |
| Maven | 3.9+ |
| Node | 20+ |
| pnpm | 通过 corepack 启用 |
| SQLite | 随 JDBC 驱动，不需要单独安装 |

仓库内的 `scripts/build-*.sh` 默认使用 `.toolchain/` 下的 JDK、Maven 与 pnpm store。宿主机已配置好工具链时可以不使用它们。

## 2. 初始化

```bash
cp .env.example .env
# 填写 ONEBOT_TOKEN、OPENCODE_GO_API_KEY、SILICONFLOW_API_KEY、ADMIN_PASSWORD
```

`.env` 必须放在仓库根目录或 `server/` 的上级目录。启动时会从工作目录向上查找工程根，再从工程根读取 `.env`。

## 3. 构建

```bash
# 前端，产物同步到 server/web-dist
./scripts/build-web.sh

# 后端，隔离构建，不打断 IDE
./scripts/build-server.sh                # clean test
./scripts/build-server.sh package -DskipTests
```

`build-server.sh` 把源码复制到 `.toolchain/server-ci/` 后构建。不要对 `server/pom.xml` 直接执行 `mvn clean`，正在运行的 JVM 会因 classpath 被删除而抛出 `NoClassDefFoundError`。

## 4. 本地运行

```bash
cd deploy && docker compose up -d
cd server && mvn spring-boot:run
```

或使用构建产物：

```bash
java -jar server/target/qqbot-server-0.0.1-SNAPSHOT.jar
```

前端开发模式：

```bash
cd web
pnpm install
pnpm dev
```

公开站为 `http://localhost:5173/public.html`，管理后台为 `http://localhost:5173/admin.html`。开发服务器把 `/api` 与 `/admin/api` 代理到 8080。

不接 QQ 也能验证整条链路：`tests/manual/fake-onebot-server.mjs` 模拟 NapCat，把事件 POST 到本机服务，模型仍使用真实配置。

## 5. 测试分层

| 层 | 位置 | 作用 | 是否需要外部服务 |
|---|---|---|---|
| 单元与集成 | `server/src/test/java` | 业务逻辑、持久化、配置 | 否 |
| 架构约束 | `ArchitectureTest` | 14 条依赖规则 | 否 |
| 检索基线 | `tests/golden/retrieval-golden.tsv` | 召回质量回归 | 需要语料与向量 API |
| UI 行为 | `tests/ui/` | 横向溢出、命中区、主题、控制台错误 | 需要 Playwright 与运行中的前端 |
| 手工探针 | `tests/manual/` | 并发、协议层、引用图片、端到端问答 | 需要运行中的服务 |
| 数据清理 | `tests/cleanup-sql/` | 清理探针写入的测试数据 | 需要数据库 |

### 5.1 后端测试

```bash
./scripts/build-server.sh test
```

架构测试与普通单测一起执行。任何越界依赖都会让构建失败，这是有意的设计，不用 `@Disabled` 绕过。

### 5.2 检索门禁

```bash
./scripts/golden-gate.sh
./scripts/golden-gate.sh --rerank off
```

门禁需要 `server/data/qa/qqbot.sqlite` 与 `SILICONFLOW_API_KEY`。缺任何一项都视为失败，不静默跳过。逐条明细写在 `.toolchain/verify/golden-detail.tsv`。

### 5.3 UI 测试

三个脚本共用 Playwright，通过 `PW_ROOT` 指定模块目录：

```bash
export PW_ROOT=file:///path/to/node_modules/
node tests/ui/audit.mjs --base http://127.0.0.1:5173
node tests/ui/shoot.mjs --out tests/screenshots/ui --tag current --theme both
node tests/ui/verify-theme.mjs
```

`audit.mjs` 检查横向溢出、命中区尺寸、控制台错误与主题一致性，有溢出时退出码为 1。`shoot.mjs` 生成双主题、双视口截图，产物写入已被忽略的目录。`verify-theme.mjs` 验证主题持久化与首帧行为。

截图目录 `tests/screenshots/` 不进入版本控制。

### 5.4 手工探针

| 脚本 | 用途 |
|---|---|
| `tests/manual/fake-onebot-server.mjs` | 假 NapCat，验证完整链路 |
| `tests/manual/loadtest-concurrency.mjs` | 并发提问与模型延迟压测 |
| `tests/manual/probe-quote-image.mjs` | 引用文字、图片、SSRF 防护 |
| `tests/manual/probe-kb-e2e.mjs` | 知识库端到端，建议指向隔离实例 |
| `tests/manual/napcat-status.mjs` | 协议层健康检查 |
| `tests/manual/setup-napcat-autologin.mjs` | 配置快速登录与设备标识备份 |
| `tests/manual/watch-napcat-login.mjs` | 观察重启后的登录过程 |

探针会直接写入数据库或调用真实模型。运行前确认指向隔离实例，运行后用 `tests/cleanup-sql/` 清理测试数据。

## 6. 提交前检查

```bash
./scripts/preflight.sh
```

完整流程包括：

1. 检查 `.env` 关键项。
2. 后端测试。
3. 后端打包。
4. 前端类型检查与构建。
5. 检查数据库是否存在。
6. 检索门禁。

任一环节失败即退出非零。发布前必须通过。

## 7. 代码约定

| 约定 | 说明 |
|---|---|
| 模块边界 | 新增包时同步更新 `ArchitectureTest` 的包名单 |
| 配置访问 | 业务包不直接依赖 `config`，通过只读策略接口获取配置 |
| SQL | 只允许出现在 `persistence`，业务侧使用仓储 |
| 发送 | 只通过 `OutboundSender`，不直接调用协议客户端 |
| 模型 | 只允许 `llm` 依赖模型 SDK |
| 注释 | 关键实现要说明为什么这样做，而不是重复代码在做什么 |
| 文档 | 改动检索、知识库格式、部署方式时同步更新对应文档 |

## 8. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动后后台 503 | 没读到 `.env` | 从仓库根或 `server/` 启动，核对启动日志 |
| 前端页面 404 | `server/web-dist` 未构建 | 执行 `./scripts/build-web.sh` |
| 检索为空 | 数据库路径不对或语料未导入 | 检查启动日志中的数据库绝对路径 |
| 端口冲突 | NapCat 与业务层端口被占用 | 3000 给 NapCat，8080 给业务层 |
| Windows 上 pnpm 报 EACCES | 容器与宿主机共用 `node_modules` | 删除 `web/node_modules`，重新安装；构建使用脚本的隔离副本 |
| 门禁报缺少语料 | 没有生产库 | 先初始化并导入语料 |
| UI 测试找不到 Playwright | 未设置 `PW_ROOT` | 指向包含 `@playwright/test` 的 `node_modules` |

## 9. 新增能力检查清单

1. 是否落在现有模块边界内，是否触发新的依赖方向。
2. 是否需要新的配置项。需要热改时加入 `SettingsWhitelist`。
3. 是否改变检索行为。改变则补黄金集样本并跑门禁。
4. 是否新增持久化表。迁移必须幂等，兼容老库。
5. 是否新增对外接口。公开接口使用独立 DTO，不复用管理接口。
6. 是否更新文档与 README 中的能力清单。
