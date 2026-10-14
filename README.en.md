# Piaoxue Miao · Q&A Bot

<p align="center">
  <b>Turns a question asked in a group chat into an answer that searches our own knowledge base first</b><br>
  Retrieval + prompt orchestration + safety and cost control. No model training involved.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white" alt="Java">
  <img src="https://img.shields.io/badge/Spring_Boot-3.5.16-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Vue-3.5-42B883?logo=vuedotjs&logoColor=white" alt="Vue">
  <img src="https://img.shields.io/badge/SQLite-3.53-003B57?logo=sqlite&logoColor=white" alt="SQLite">
  <img src="https://img.shields.io/badge/QQ-OneBot_11-12B7F5" alt="QQ">
</p>

> 📖 **Docs**: [System manual](docs/md/系统说明书.md) ｜ [Deployment guide](docs/md/公网部署指南.md) ｜ [Architecture](docs/diagrams/system-architecture.html) ｜ [简体中文](README.md)

Project documentation is written in Chinese.

---

## 1. Overview

A member mentions the bot, the bot searches its own knowledge base, and an LLM phrases the answer.

| Entry point | For whom | Form |
|---|---|---|
| QQ groups | Members | Mention the bot, or use `/commands` |
| Public site | Members and visitors | `public.html`, browse the library and the Q&A plaza |
| Admin console | Maintainer | `admin.html`, dashboard, knowledge base, settings |

| Dimension | Currently | What to change |
|---|---|---|
| Messaging platform | QQ, via NapCat / OneBot 11 | `onebot/` and `incoming/` |
| Knowledge corpus | Enshrouded only | Import new material following the [format spec](docs/md/知识库文档格式规范.md) |
| Bot persona | `system.md`, template `system.md.example` | Edit that one file, no rebuild |
| Chat model | opencode-go, DeepSeek as fallback | Admin Settings page, or `llm/` |

Four invariants are enforced by `ArchitectureTest`; a violating dependency fails the build:

| Invariant | Meaning |
|---|---|
| `onebot/` | The only place that knows the OneBot protocol |
| `OneBotApiClient` | The only exit for sending messages |
| `llm/` | The only place that knows which model is used |
| `guard/` | The only place allowed to refuse |

## ⚙️ How it works

![System architecture](docs/diagrams/system-architecture.png)

The path of one message: event intake → five guard stages → three recall paths → RRF fusion and cross-encoder rerank → LLM answer → chunked reply.

| Stage | What happens |
|---|---|
| Intake and routing | Deduplicated, then 204 immediately; REST and hot configuration reload share the same layer |
| Safety and cost | Access, mention, rate limit, content gate and inbound words all decided locally, plus an outbound sensitive-word filter |
| Retrieval | Body vectors and terminology keywords recall in parallel; a title-vector gate pins an exact name; RRF fusion then a cross-encoder rerank, top 5 |
| Answering | Retrieved material is handed over as `<<<KNOWLEDGE>>>`; the model may call a read-only `lookup` tool; thin material is said so |
| Outbound | Chunked sending, statistics and raw text persisted asynchronously |

## 2. Features

| Capability | Description |
|---|---|
| Knowledge-base QA | Three recall paths plus rerank, top 5 handed to the model; thin material is never invented |
| Tool calling | The model may call a read-only `lookup`; the allow-list denies by default, anonymous callers are refused, the loop is capped at 3 rounds |
| Commands | `/help`, `/ping` and friends reply instantly, bypassing rate limits and never calling a model |
| Safety layer | Five local stages at zero token cost, plus an outbound sensitive-word filter |
| Cost budget | Per-user, per-group and global daily quotas with a global token cap; over budget it degrades |
| Images and quotes | Reads quoted text and images; image questions go to a vision model |
| Replying with images | An `[[img:source:name]]` marker in a reply becomes a real image in the same message; sources plug in via `ImageSource`, currently `kb:` (knowledge-base images) |
| Q&A plaza | A help request stays in its group; votes promote good answers into improvement proposals |

## 3. Upstream dependencies

| Service | Purpose | Configuration |
|---|---|---|
| [OpenCode Go](https://opencode.ai) | Chat model, default, supports vision | `OPENCODE_GO_API_KEY`, `OPENCODE_GO_MODEL` |
| [DeepSeek](https://api.deepseek.com) | Chat model, fallback chain | `DEEPSEEK_API_KEY` |
| [SiliconFlow](https://siliconflow.cn) | Embeddings `BAAI/bge-m3`, rerank `BAAI/bge-reranker-v2-m3` | `SILICONFLOW_API_KEY` |
| [Aliyun DashScope](https://dashscope.aliyun.com) | Alternative embeddings, off by default | `DASHSCOPE_API_KEY` |
| [NapCat](https://github.com/NapNeko/NapCatQQ) | QQ protocol layer, OneBot 11 | `ONEBOT_API_BASE`, `ONEBOT_TOKEN` |
| Enshrouded wiki | Corpus source | Wiki fetch settings in the console |

## 4. Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 17, Spring Boot 3.5.16, Maven 3.9+ |
| Model access | LangChain4j 1.20.0, OpenAI-compatible protocol; multiple vendors mean multiple instances plus a fallback chain |
| Storage | Single-file SQLite, sqlite-jdbc 3.53.4.0; knowledge base and statistics share one database |
| Spreadsheet import and export | Apache POI 5.3.0 |
| Frontend | Vue 3.5, vue-router 4, Vite 6, TypeScript 5.7, in-house component library `web/src/shared/ui` |
| Protocol layer | NapCat, Docker, OneBot 11 |
| Reverse proxy and supervision | Nginx, systemd |
| Engineering quality | JUnit 5, AssertJ, Mockito, ArchUnit 1.3.0, Playwright |

## 🗂 Layout

```text
server/   Spring Boot monolith, the only Java that ships
web/      both frontends: public/ admin/ shared/ theme/
deploy/   NapCat container and Nginx config
scripts/  build, self-check, regression gate, wiki import
docs/     md/ sources, html/ rendered, diagrams/ figures
tests/    golden set, probes, Playwright
plans/    planning documents
```

## 🛠 Development environment

| Item | Version / note |
|---|---|
| JDK | 17 or newer; this repository runs on 21 |
| Build | Maven 3.9+ |
| Frontend | Node 20+ with pnpm |
| Database | SQLite at `server/data/qa/qqbot.sqlite`; the path resolves against the process working directory, so always start from `server/` |
| Protocol layer | Docker, NapCat container |
| Tests | 486 backend cases; `vue-tsc` plus `vite build` on the frontend |

### Run it locally

```bash
cp .env.example .env                 # ONEBOT_TOKEN, model keys, SILICONFLOW_API_KEY, ADMIN_PASSWORD
cd deploy && docker compose up -d    # start the protocol layer and log in to QQ
./scripts/build-web.sh               # build the frontend into server/web-dist/
cd server && mvn spring-boot:run     # start the business layer on :8080
./scripts/health-check.sh            # self-check
```

Frontend dev mode: `cd web && pnpm install && pnpm dev`, public site at 5173/public.html, console at 5173/admin.html.

## 5. Deployment

NapCat container, a systemd-managed jar, Nginx in front. Steps are in the [deployment guide](docs/md/公网部署指南.md).

| Task | Command |
|---|---|
| Build the frontend | `./scripts/build-web.sh` |
| Build the backend | `./scripts/build-server.sh package -DskipTests` |
| Update | swap the jar, then `systemctl restart` |
| Pre-flight check | `./scripts/preflight.sh` |
| Retrieval regression | `./scripts/golden-gate.sh` |

Host and container are two separate environments; container-side builds always go through `.toolchain/`.

## 6. Documentation index

| I want to | Read |
|---|---|
| Understand the system | [系统说明书](docs/md/系统说明书.md) |
| Deploy it | [公网部署指南](docs/md/公网部署指南.md) |
| Wire up QQ | [协议层搭建指南](docs/md/协议层搭建指南.md) · [NapCat 新手接入](docs/md/napcat-新手接入指南.md) · [登录丢失排查](docs/md/NapCat登录丢失排查与解决.md) |
| Maintain the knowledge base | [知识库运营手册](docs/md/知识库运营手册.md) · [导入与使用手册](docs/md/知识库导入与使用手册.md) · [文档格式规范](docs/md/知识库文档格式规范.md) |
| Look up ops commands | [运维命令速查](docs/md/知识库运维命令速查.md) |
| Retrieval design | [检索基线](docs/md/检索基线.md) · [回答策略与知识库设施设计](docs/md/回答策略与知识库设施设计.md) |
| Change ingestion | [知识库摄入边界与激活协议](docs/md/知识库摄入边界与激活协议.md) |
| Backend and frontend details | [server/README.md](server/README.md) · [web/README.md](web/README.md) |

## License

No licence file is included. Contact the author before use, redistribution or derivative works.
