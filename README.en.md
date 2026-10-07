# Piaoxue Miao · Q&A Bot

[简体中文](README.md) ｜ **English**

> A **question-answering bot**: it turns “a question asked in a group chat” into an answer that **first searches our own
> knowledge base**, which an LLM then phrases in plain language. It **does not train models** — it does
> **retrieval + prompt orchestration + safety & cost control**. A **public site** and an **admin console** ride along.

**Current adaptations** — the two “currently only” items below are the present state, and both are replaceable by design:

| Dimension | Currently | What you change to move it |
|---|---|---|
| Messaging platform | **QQ only** (via NapCat / OneBot 11) | `onebot/` (protocol details) + `incoming/` (the single entry point); retrieval, knowledge base and safety logic stay untouched |
| Knowledge corpus | **Enshrouded only** | Pure data: import new material following the [document format spec](docs/md/知识库文档格式规范.md); wiki sources live in configuration |
| Bot persona | `AGENTS.md` (not tracked; template `AGENTS.md.example`) | Edit that one file — **no rebuild needed** |
| Chat model | opencode-go (default) → DeepSeek (fallback) | Admin “Settings” page; `llm/` is the only place that knows the model |

*Project documentation is currently written in Chinese; this file is an English overview.*

[Quick start](#8-run-it-locally) · [Deployment](docs/md/公网部署指南.md) · [System manual](docs/md/系统说明书.md) · [Architecture diagram](docs/diagrams/system-architecture.html)

## Table of contents

1. [Features](#1-features)
2. [Tech stack](#2-tech-stack)
3. [Architecture](#3-architecture)
4. [Interface design](#4-interface-design)
5. [Modules and file layout](#5-modules-and-file-layout)
6. [Data and configuration](#6-data-and-configuration)
7. [Engineering quality and verification](#7-engineering-quality-and-verification)
8. [Run it locally](#8-run-it-locally)
9. [Deploy to production](#9-deploy-to-production)
10. [Documentation index](#10-documentation-index)

---

## 1. Features

Three entry points:

| Entry point | For whom | Form |
|---|---|---|
| **QQ groups** | Players | @-mention the bot / `/commands` (**the only adapted platform today**) |
| **Public site** | Players and visitors | `public.html` (`/`) — browse the library and the Q&A plaza |
| **Admin console** | Maintainer | `admin.html` (`/admin`) — dashboard, knowledge base, settings |

### 1.1 Group chat side (currently QQ)

- **Knowledge-base QA**: three recall paths — A (body vectors) + B (terminology keywords) + C (title-vector gate) —
  fused with RRF and reranked down to the top 5, wrapped as `<<<KNOWLEDGE>>>` and handed to the LLM.
  When the material is insufficient the bot says so instead of inventing numbers.
- **Command system**: canned-reply commands (`/help`, `/ping`, …) answer **instantly, bypass rate limits and never call a model**;
  they support variable rendering.
- **Safety layer**: five **zero-cost** stages — `access → mention → rate-limit → content-gate → inbound-words` —
  all decided locally (no tokens spent), plus an outbound sensitive-word filter.
- **Cost budget**: per-user / per-group / global daily quotas and a global token cap; over budget it degrades instead of running away.
- **Quoted messages & images**: reads quoted text and images; image questions go to a vision model.
- **Q&A plaza**: a group member posts “求助: …” (help request) → it is published to that group only; votes promote good answers into improvement proposals.

### 1.2 Public site

Home (carousel + site copy), library (category browsing / search / entry detail), Q&A plaza, about.

### 1.3 Admin console

Dashboard (stats, hit rate, sources), QA records, **four knowledge-base panels** (documents / terms / categories / proposals),
command management, settings (configuration centre), models, logs, page copy & carousel, plaza moderation, safety metrics.

### 1.4 The knowledge base — the core asset

- Two **peer-level** ingestion paths:
  1. `.md` documents written by a human or an AI to a fixed format → imported from the admin “Documents” panel;
  2. automated wiki harvesting (a CLI batch job, **optional** — if it cannot run, you fill the gap by hand and the core keeps
- Both paths converge on “write a block”, after which **writes take effect immediately**: the block index and the term table
  invalidate/refresh themselves based on a corpus version stamp — no restart, and no caller has to remember anything.
- Everything that must survive long-term (Chinese term names, category mapping, proposals, …) lives in a single SQLite file.

---

## 2. Tech stack

| Layer | Choice |
|---|---|
| Backend | **Java 17**, **Spring Boot 3.5.16** (monolith with embedded Tomcat), Maven 3.9+ |
| Model access | **LangChain4j 1.20.0** (`langchain4j-open-ai`, OpenAI-compatible) — multi-vendor = multiple instances + a fallback chain |
| Storage | **SQLite** (`sqlite-jdbc 3.53.4.0`), single file; knowledge base and analytics share one database |
| Spreadsheets | Apache POI 5.3.0 (term table in xlsx) |
| Frontend | **Vue 3.5** (`<script setup>`) + vue-router 4 + **Vite 6** + TypeScript 5.7 + in-house components (`web/src/shared/ui/`, 17 of them) |
| QQ protocol | **NapCat** (Docker, OneBot 11) — business code never touches the QQ protocol |
| External services | Chat model **opencode-go** (default, vision-capable) → **DeepSeek** as fallback; embeddings via **SiliconFlow `BAAI/bge-m3`** (1024-dim) |
| Reverse proxy | Nginx (TLS + rate limiting + IP allow-list) |
| Process manager | systemd (`-Xmx512m`, `ProtectSystem=strict`) |
| Engineering quality | JUnit 5 + AssertJ + Mockito + **ArchUnit** (architecture guard: a forbidden dependency fails the build); Playwright scripts under `tests/` |

---

## 3. Architecture

```text
        QQ users
           │  @bot / /commands
           ▼
   ┌───────────────────┐        ┌──────────────────────────────┐
   │ NapCat  (Docker)  │        │  LLM (OpenAI-compatible)      │
   │ OneBot 11 gateway │        │  opencode-go (default, vision)│
   │ protocol only     │        │  → deepseek (fallback chain)  │
   └─────────┬─────────┘        └──────────────▲───────────────┘
             │ POST /onebot/event              │
             │ /send_group_msg …               │
             ▼                                  │
   ┌──────────────────────────────────────────┐ │
   │  Spring Boot business layer :8080         │ │
   │  incoming → router → agent → llm          │ │
   │     ├─ guard/   five zero-cost stages     │ │
   │     ├─ command/ command system            │ │
   │     ├─ kb/      A/B/C retrieval ──────────┼─┘ embeddings
   │     ├─ media/   image cache & cleanup      │   SiliconFlow bge-m3
   │     └─ qa/      async recording            │
   │  onebot/  the only package talking to NapCat│
   └───────────────┬──────────────────────────┘
                   ▼
        server/data/qa/qqbot.sqlite
        analytics / raw Q&A / terms / categories / blocks+vectors /
        title vectors / proposals / commands / plaza / admin visits

   Frontend (Vue 3) → server/web-dist/
   public.html (public site)   admin.html (admin console)
                   ▲
              Nginx :443
     www.example.com   → public site (60 req/min)
     admin.example.com → admin console (IP allow-list)
```

### 3.1 Lifecycle of one message

1. NapCat reports `POST /onebot/event` → the controller **returns 200 immediately** and hands the event to a thread pool
   (core=max=8, queue 100; if the queue waits more than 20 s the message is dropped with a polite reply);
2. ignore the bot's own messages → block-list admission → command matching (canned replies stop here, no model call);
3. the five guard stages → cost budget → resolve the quoted message;
4. `ChatService`: retrieve from the knowledge base (A/B/C + RRF + rerank) → assemble the prompt → call the model (with fallback);
5. outbound sensitive-word filter → `OutboundSender` (pacing + jitter + splitting + merged forwarding) → `OneBotApiClient` → NapCat → QQ;
6. meanwhile QA records are written **asynchronously** and never block the reply path.

### 3.2 Four structural invariants (enforced by tests)

1. `onebot/` is the only place allowed to know OneBot protocol details;
2. `OneBotApiClient` is the single exit point for sending messages;
3. `llm/` is the only place that knows which model is in use;
4. `guard/` is the only place allowed to say “no”.

Plus one data-side convention: **every long-lived human artefact lives in the same SQLite file**
(the knowledge-base block store reuses `QaStore`'s connection — two connections to the same SQLite file clash on WAL shared memory).

These are not just prose: `ArchitectureTest` (ArchUnit) turns several of them into **failing tests**, including
“the knowledge-base core must not depend on an ingestion source” and “ingestion sources must not hold the internal index or term table”.

### 3.3 Diagrams (open them directly)

| Diagram | What it shows |
|---|---|
| [System architecture](docs/diagrams/system-architecture.html) | Component topology, layers, dependency direction |
| [Message workflow](docs/diagrams/message-workflow.html) | Every branch from ingest to reply |
| [Knowledge-base data flow](docs/diagrams/kb-dataflow.html) | Two ingestion paths → storage → three retrieval paths |
| [Plaza loop](docs/diagrams/plaza-loop.html) | Help request → votes → proposal → adoption |

---

## 4. Interface design

Three prefixes with different auth and rate limits:

| Prefix | Purpose | Auth | Rate limit |
|---|---|---|---|
| `/onebot/**` | NapCat ingest entry (`POST /onebot/event`) | Token | — |
| `/api/public/**` | Public site reads + voting / fallback | none | 60 req/min/IP in the app + one Nginx layer |
| `/admin/api/**` | Admin console | session cookie (401 when anonymous, 503 when no password configured) | extra limit on login |

Conventions:

1. **Single ingest entry**: `POST /onebot/event` returns 200 first and processes asynchronously — the reporter must not wait for us.
2. **Public APIs** are mostly read-only: `/site` `/stats` `/kb/search` `/kb/browse` `/kb/page` `/carousel` `/plaza/*`.
3. **The console is never left open**: with an empty `ADMIN_PASSWORD` the whole `/admin` returns **503**; when anonymous,
   APIs return **401 JSON** and pages redirect to the login page (except `/admin/api/login`).
4. **Response shape**: success `{"ok":true, …}`, failure `{"ok":false,"error":"plain-language reason"}`.
5. **Two SPA entries**: `/public.html` and `/admin.html`; all other frontend routes are forwarded server-side.

The full endpoint list (grouped by domain, including all four knowledge-base panels) is in
[系统说明书 §七](docs/md/系统说明书.md) (Chinese).

---

## 5. Modules and file layout

### 5.1 Repository top level

| Directory | Responsibility |
|---|---|
| `server/` | **Business layer** (Spring Boot monolith — the only Java that ships to production) |
| `web/` | **Frontend sources** for both apps: `public/`, `admin/`, `shared/` (components, API clients), `theme/` (design tokens) |
| `deploy/` | Production deployment: `docker-compose.yml` (NapCat only) + `nginx/qqbot.conf` |
| `scripts/` | Build & self-checks: `build-server.sh` `build-web.sh` `preflight.sh` `health-check.sh` `validate-kb-doc.sh` |
| `docs/` | `md/` (documentation source) + `html/` (rendered) + `diagrams/` (architecture / workflow / data-flow diagrams) |
| `tests/` | Verification scripts and fixtures: `golden/` (retrieval golden set), `manual/`, `cleanup-sql/`, `screenshots/` |
| `plans/` | Planning documents |
| `NapCatQQ/` | Protocol-layer source **for reference** (~493 MB, not tracked; local browsing only) |
| `data/`, `server/data/` | Runtime data (SQLite / images / logs) — **not tracked** |

### 5.2 Backend package map (`server/src/main/java/com/example/qqbot/`)

| Package | Responsibility |
|---|---|
| `config/` | Configuration models, startup validation, `.env` loading |
| `incoming/` · `router/` · `agent/` | Event entry → orchestration → context assembly and model call |
| `onebot/` | Protocol details (**the only place**): codec, action client, outbound pacing |
| `guard/` (+`stage/`) | Safety and cost: five stages, rate limiting, budget, outbound filter |
| `command/` | Command system: matching, storage, variable rendering |
| `kb/` | Knowledge base: `block` (blocks + index) `wiki` (harvesting & cleaning) `doc` (document import) `term` (terms) `category` `proposal` `map` |
| `llm/` | Multi-vendor routing and fallback (**the only place** that knows the model) |
| `media/` | Image fetching, caching, storage guard, temp-file cleanup |
| `qa/` | QA records and analytics |
| `plaza/` | Q&A plaza: voting, aggregation, fallback |
| `publicapi/` | Public-site APIs (dedicated DTOs, internal models never leak) |
| `admin/` | Console authentication and dashboard |
| `settings/` | Configuration centre (allow-list + override file) |
| `site/` | Public-site copy and carousel |
| `logs/` | Log buffering and masking |
| `persistence/` | **All SQL and `java.sql` live here** |
| `trace/` · `time/` · `files/` | Retrieval trace, time and file helpers |

### 5.3 Frontend layout (`web/src/`)

| Directory | Responsibility |
|---|---|
| `public/` | Public site: routes, views, components |
| `admin/` | Admin console: routes, views (knowledge-base panels in `views/kb/`) |
| `shared/` | In-house UI components (`ui/`), API clients and types, shared composables |
| `theme/` | Design tokens and theme (“Misty Spirit Fire” v2) |

---

## 6. Data and configuration

- **Database**: `server/data/qa/qqbot.sqlite` — ⚠️ the path is resolved against the **process working directory**,
  so always start from `server/`; starting elsewhere means a completely different dataset.
- **What it stores**: QA records and analytics, knowledge-base blocks + vectors, title vectors, terms, categories, proposals, commands, plaza votes and help requests, admin visits.
- **Configuration precedence** (high → low): command line / environment variables → `server/config/overrides.yml` (written by the admin “Settings” page) → `application.yml`.
- **Secrets**: `.env` at the repository root (template: `.env.example`) — `.env` **never enters the repository**.
  The bot persona is `AGENTS.md`, also untracked (the repository ships `AGENTS.md.example`).
- **Also untracked**: `deploy/data/` (NapCat login state and logs — debug logs contain the token in clear text),
  `server/data/`, `server/web-dist/`, `web/node_modules/`, self-hosted fonts.

---

## 7. Engineering quality and verification

| Task | How to run it |
|---|---|
| Full test suite | `./scripts/build-server.sh` (isolated container-side build) or `mvn -f server/pom.xml test` |
| Retrieval quality | `tests/golden/` golden set + `RetrievalGoldenTest` (`-Dqqbot.golden.rerank=off` disables reranking for comparison) |
| Architecture guard | `ArchitectureTest` (ArchUnit): a forbidden dependency direction fails the build |
| Pre-flight | `./scripts/preflight.sh` |
| Post-flight | `./scripts/health-check.sh` (defaults to `http://127.0.0.1:8080`) |
| Knowledge-base document format | `./scripts/validate-kb-doc.sh` |

Current size: **58 test classes / 431 test cases** (3 of them are network-dependent E2E tests, disabled by default).

---

## 8. Run it locally

Prerequisites: **JDK 17+**, **Maven 3.9+**, **Node 20+ / pnpm**, **Docker** (for NapCat).

```bash
# 1) Configure secrets (repository root)
cp .env.example .env       # ONEBOT_TOKEN / model keys / SILICONFLOW_API_KEY / ADMIN_PASSWORD

# 2) Start the protocol layer (log in to QQ; the WebUI URL is printed in the container logs)
cd deploy && docker compose up -d && docker compose logs -f

# 3) Build the frontend → sync into server/web-dist/
./scripts/build-web.sh

# 4) Start the business layer (:8080)
cd server && mvn spring-boot:run

# 5) Self-check
./scripts/health-check.sh
```

Frontend dev mode (HMR; `/api` and `/admin/api` are proxied to 8080):

```bash
cd web && pnpm install && pnpm dev
# public site   → http://localhost:5173/public.html
# admin console → http://localhost:5173/admin.html
```

You can exercise the whole chain without touching QQ: see `tests/manual/` and `scripts/health-check.sh`.

---

## 9. Deploy to production

Shape: **NapCat container + a systemd-managed jar + Nginx reverse proxy** (TLS / rate limiting / IP allow-list).

- Step-by-step: [公网部署指南](docs/md/公网部署指南.md) (Chinese; [HTML version](docs/html/公网部署指南.html)).
- Protocol layer: [协议层搭建指南](docs/md/协议层搭建指南.md), [NapCat 新手接入](docs/md/napcat-新手接入指南.md).
- Updating: `./scripts/build-web.sh` + `./scripts/build-server.sh package -DskipTests` → swap the jar → `systemctl restart`.

⚠️ **Host and container are two environments — never mix them.** Container-side builds always go through `.toolchain/`
(isolating `server/target` and `web/node_modules`). Otherwise you get bewildering failures such as `NoClassDefFoundError`
or `pnpm EACCES`; the reasoning is documented at the top of each script.

---

## 10. Documentation index

The detailed documentation is currently written in Chinese.

| I want to… | Read |
|---|---|
| Understand the whole system | [系统说明书](docs/md/系统说明书.md) |
| Deploy it | [公网部署指南](docs/md/公网部署指南.md) |
| Wire up QQ / the protocol layer | [协议层搭建指南](docs/md/协议层搭建指南.md) · [NapCat 新手接入](docs/md/napcat-新手接入指南.md) · [登录丢失排查](docs/md/NapCat登录丢失排查与解决.md) |
| Maintain the knowledge base | [知识库运营手册](docs/md/知识库运营手册.md) · [导入与使用手册](docs/md/知识库导入与使用手册.md) · [文档格式规范](docs/md/知识库文档格式规范.md) |
| Look up an ops command | [运维命令速查](docs/md/知识库运维命令速查.md) |
| See why retrieval is built this way | [检索基线](docs/md/检索基线.md) · [回答策略与知识库设施设计](docs/md/回答策略与知识库设施设计.md) |
| Change ingestion / write a new importer | [知识库摄入边界与激活协议](docs/md/知识库摄入边界与激活协议.md) |
| Review wiki-cleaning defects and fixes | [清洗缺陷清单与修复方案](docs/md/知识库wiki清洗缺陷清单与修复方案.md) |
| Check data-screen figures | [数据大屏数据准确性核查](docs/md/数据大屏数据准确性核查.md) |
| Backend / frontend specifics | [server/README.md](server/README.md) · [web/README.md](web/README.md) |

---

## License

No licence file is included. For use, redistribution or derivative works, please contact the author first.
