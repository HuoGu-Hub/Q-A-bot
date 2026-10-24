# Piaoxue Miao · Group Q&A and Operations Bot

<p align="center">
  <b>Ask in the group; the bot searches its own knowledge base first, then lets the model phrase the answer</b><br>
  Scheduled boards, map snapshots, the public site and the admin console all run in one process
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white" alt="Java">
  <img src="https://img.shields.io/badge/Spring_Boot-3.5.16-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Vue-3.5-42B883?logo=vuedotjs&logoColor=white" alt="Vue">
  <img src="https://img.shields.io/badge/SQLite-3.53-003B57?logo=sqlite&logoColor=white" alt="SQLite">
  <img src="https://img.shields.io/badge/QQ-OneBot_11-12B7F5" alt="QQ">
</p>

> 📖 **Docs**: [System Manual](docs/md/系统说明书.md) ｜ [Deployment Guide](docs/md/公网部署指南.md) ｜ [Architecture](docs/diagrams/system-architecture.html) ｜ [中文](README.md)

---

## 1. What this is

A group member @-mentions the bot; the system retrieves from its own knowledge base and hands the result to a model. The same backend also serves slash commands, scheduled boards, an interactive map and the public site.

| Entry | Audience | Form |
|---|---|---|
| QQ group | Members | @-mention to ask, `/command`, scheduled board delivery |
| Public site | Members and visitors | `public.html`: library, plaza, interactive map, about |
| Admin console | Operator | `admin.html`: dashboard, knowledge base, boards and commands, map, site, system |

| Dimension | Current | Where to change it |
|---|---|---|
| Messaging platform | QQ via NapCat / OneBot 11 | `onebot/` and `incoming/` |
| Corpus | Enshrouded | Import new corpus per the [format spec](docs/md/知识库文档格式规范.md) |
| Persona | `system.md`, template `system.md.example` | Edit and restart; not editable in the console |
| Group and per-user addenda | `bot_prompt` table | `/prompt` in the group, or the console |
| Chat model | OpenCode Go, DeepSeek fallback | Console swaps the model name; providers and fallback live in config |

Four iron rules plus ten structural rules are enforced by `ArchitectureTest`; a violating dependency fails the build:

| Rule | Meaning |
|---|---|
| `onebot/` | The only place that knows the OneBot protocol |
| `OneBotApiClient` | The only outbound message channel |
| `llm/` | The only place that knows which model is used |
| `guard/` | The only place allowed to refuse |

## ⚙️ How it works

![System architecture](docs/diagrams/system-architecture.png)

A message travels: intake and dispatch → command check, answered in place on a hit → five-layer Guard → two-way recall and rerank → model answer → chunked reply. Scheduled boards take another path: one row is written, and a delivery runner scans the table.

| Stage | Responsibility |
|---|---|
| Intake and dispatch | Deduplicate, return 204 immediately, route to the chat pool or the command pool by the first character; a queue wait over 60s yields a busy notice |
| Commands and boards | Slash commands are parsed and rendered before Guard, with no model call and no QA record; a board writes "what to send when" as a row, and a 15s scan delivers it |
| Safety and cost | Access, mention, rate limit, content gate and inbound words are judged locally at zero token cost; outbound content is filtered once more |
| Retrieval | Body vector and term keyword recall, a title-vector gate that pins a hit to the top, RRF fusion, then a cross-encoder rerank down to top-5 |
| Answering | Retrieval results reach the model as `<<<KNOWLEDGE>>>`; the model may call the read-only `lookup`, `locate` and `search_titles` tools; thin evidence yields an honest "not found" |
| Outbound | Strip image markers, resolve images, filter, chunk, pace; the single exit |
| Map snapshots | The model only copies an image reference; coordinate projection and Java2D rendering happen server-side, and a missing tile degrades to text only |

## 2. Features

### For group members

| Capability | Detail |
|---|---|
| Knowledge Q&A | Two-way recall plus rerank, top-5 to the model; no fabrication when evidence is thin |
| Tool calling | Read-only tools `lookup`, `locate` and `search_titles`; deny by default, deny without caller identity, at most 3 round trips |
| Commands | `/help`, `/ping` and similar reply instantly without rate limiting or model calls |
| Boards and scheduling | `/board /create` schedules an announcement: daily, weekly 1-7, every 2-30 days, or N times total |
| Place answers | Ask where something is; get a description plus a map snapshot with the point marked |
| Images and quotes | Reads quoted text and images; image questions go to a vision model |
| Inline images | `[[img:source:name]]` becomes a real image in the same message; sources extend via `ImageSource`, currently `kb:` and `map:` |
| Plaza | Members raise a help request; voted answers become improvement proposals |

### For the operator

| Capability | Detail |
|---|---|
| Permissions | Six tiers plus per-command and global lists; boards share the same table under negative keys |
| Boards and commands | Parameter table, delivery mode, help text, list/help replies and variable insertion, all assembled in the console |
| Knowledge base | Documents, terms, categories, wiki import, proposals |
| Map | Add or edit points, annotate wiki markers, toggle layers, re-check the offset, warm tiles |
| Site | Home carousel and page copy; defaults live in code, the table stores overrides only, clearing restores defaults |
| Prompts and models | Group and per-user addenda; test connectivity, list vendor models, hot-swap the model name |
| Stats and logs | QA text and derived metrics, keywords, hit rate, admin visits; a live log panel over SSE |
| Config centre | Whitelisted keys apply live and are written back to `config/overrides.yml` |

## 3. Upstream services

| Service | Purpose | Config |
|---|---|---|
| [OpenCode Go](https://opencode.ai) | Chat model, default, vision and tools | `OPENCODE_GO_API_KEY`, `OPENCODE_GO_MODEL` |
| [DeepSeek](https://api.deepseek.com) | Chat model, fallback chain | `DEEPSEEK_API_KEY` |
| [SiliconFlow](https://siliconflow.cn) | Embeddings `BAAI/bge-m3`, rerank `BAAI/bge-reranker-v2-m3` | `SILICONFLOW_API_KEY` |
| [Aliyun DashScope](https://dashscope.aliyun.com) | Backup embeddings, off by default | `DASHSCOPE_API_KEY` |
| [NapCat](https://github.com/NapNeko/NapCatQQ) | QQ protocol endpoint, OneBot 11 | `ONEBOT_API_BASE`, `ONEBOT_TOKEN` |
| Enshrouded wiki | Corpus and interactive map source | Console wiki and map sync settings |

## 4. Stack

| Layer | Choice |
|---|---|
| Backend | Java 17, Spring Boot 3.5.16, Maven 3.9+ |
| Model access | LangChain4j 1.20.0 over the OpenAI-compatible protocol; multiple vendors are multiple instances plus a fallback chain |
| Storage | Single SQLite file, sqlite-jdbc 3.53.4.0, 33 tables, WAL, one connection and one lock |
| Map rendering | Java2D crops tiles to the viewport, draws markers, encodes JPEG; tiles cached on disk |
| Spreadsheets | Apache POI 5.3.0 |
| Frontend | Vue 3.5, vue-router 4, Vite 6, TypeScript 5.7, in-house components under `web/src/shared/ui` |
| Protocol endpoint | NapCat in Docker, OneBot 11 |
| Proxy and hosting | Nginx, systemd |
| Engineering | JUnit 5, AssertJ, Mockito, ArchUnit 1.3.0, Playwright |

## 🗂 Layout

```text
server/   Spring Boot monolith, the only Java in production; 322 sources, 951 tests
web/      Dual frontend: public/ with 7 pages, admin/ with 33 panels, shared/ and theme/
deploy/   NapCat container and Nginx config
scripts/  Build, health check, regression gate, wiki import
docs/     md/ sources, html/ rendered, diagrams/
data/     SQLite, map tiles, logs and temporary images
tests/    Golden set, probes, Playwright
plans/    Planning notes
```

## 🛠 Development

| Item | Version / note |
|---|---|
| JDK | 17 or newer; this repo runs on 21 |
| Build | Maven 3.9+ |
| Frontend | Node 20+ and pnpm; 93 files, about 25k lines |
| Database | SQLite at `server/data/qa/qqbot.sqlite`, resolved from the process working directory, so start from `server/` |
| Protocol endpoint | Docker with a NapCat container |
| Tests | 951 backend cases, plus `vue-tsc` and `vite build` on the frontend |

### Run it locally

```bash
cp .env.example .env                 # ONEBOT_TOKEN, model keys, SILICONFLOW_API_KEY, ADMIN_PASSWORD
cd deploy && docker compose up -d    # start the protocol endpoint and log in to QQ
./scripts/build-web.sh               # build the frontend into server/web-dist/
cd server && mvn spring-boot:run     # start the service on :8080
./scripts/health-check.sh            # self check
```

Frontend dev mode: `cd web && pnpm install && pnpm dev`; public site on 5173/public.html, console on 5173/admin.html.

## 5. Deployment

NapCat container, systemd-managed jar and Nginx reverse proxy; see the [deployment guide](docs/md/公网部署指南.md).

| Task | Command |
|---|---|
| Build frontend | `./scripts/build-web.sh` |
| Build backend | `./scripts/build-server.sh package -DskipTests` |
| Update | Replace the jar, then `systemctl restart` |
| Pre-flight | `./scripts/preflight.sh` |
| Retrieval regression | `./scripts/golden-gate.sh` |
| Validate KB documents | `./scripts/validate-kb-doc.sh` |

Host and container are two environments; container-side builds go through `.toolchain/`.

## 6. Docs

| Goal | Read |
|---|---|
| Overall picture | [System Manual](docs/md/系统说明书.md) |
| Deploy | [Deployment Guide](docs/md/公网部署指南.md) |
| Connect QQ | [Protocol setup](docs/md/协议层搭建指南.md) · [NapCat intro](docs/md/napcat-新手接入指南.md) · [Login loss](docs/md/NapCat登录丢失排查与解决.md) |
| Maintain the KB | [Operations manual](docs/md/知识库运营手册.md) · [Import guide](docs/md/知识库导入与使用手册.md) · [Format spec](docs/md/知识库文档格式规范.md) |
| Ops commands | [Quick reference](docs/md/知识库运维命令速查.md) |
| Retrieval design | [Baseline](docs/md/检索基线.md) · [Answer strategy](docs/md/回答策略与知识库设施设计.md) |
| Ingestion rules | [Ingestion boundaries](docs/md/知识库摄入边界与激活协议.md) |
| Data definitions | [Dashboard accuracy review](docs/md/数据大屏数据准确性核查.md) |
| Code details | [server/README.md](server/README.md) · [web/README.md](web/README.md) |

## License

No license file is attached. Contact the author before use, redistribution or derivative work.
