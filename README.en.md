# Q&A Bot

[简体中文](README.md) ｜ **English**

A question comes in, the bot searches its own knowledge base, an LLM phrases the answer. No model training involved:
this project is retrieval, prompt orchestration, safety and cost control, plus a public site and an admin console you
can try in a browser.

| Dimension | Currently | What to change |
|---|---|---|
| Messaging platform | QQ, via NapCat / OneBot 11 | `onebot/` and `incoming/` |
| Knowledge corpus | Enshrouded only | Import new material following the [format spec](docs/md/知识库文档格式规范.md) |
| Bot persona | `AGENTS.md`, template `AGENTS.md.example` | Edit that one file, no rebuild |
| Chat model | opencode-go, DeepSeek as fallback | Admin Settings page, or `llm/` |

[Quick start](#run-it-locally) · [Deployment](docs/md/公网部署指南.md) · [System manual](docs/md/系统说明书.md) · [Architecture](docs/diagrams/system-architecture.html)

Project documentation is written in Chinese.

## Features

- Knowledge-base QA: body vectors, terminology keywords and a title-vector gate recall in parallel, fused with RRF,
  reranked to the top 5. When the material is thin the bot says so instead of inventing answers.
- Commands: `/help`, `/ping` and friends reply instantly, bypassing rate limits and never calling a model.
- Safety layer: five local stages, plus an outbound sensitive-word filter.
- Cost budget: per-user, per-group and global daily quotas with a global token cap; over budget it degrades.
- Quoted messages and images are read; image questions go to a vision model.
- Q&A plaza: a help request stays in its group, votes promote good answers into improvement proposals.

Three entry points: QQ groups, the public site `public.html`, and the admin console `admin.html`.

## Tech stack

Java 17 / Spring Boot 3.5 / Maven｜LangChain4j｜single-file SQLite｜Vue 3 / Vite 6 / TypeScript｜NapCat / OneBot 11｜Nginx / systemd｜JUnit 5 / ArchUnit / Playwright

## Layout and conventions

```text
server/   Spring Boot monolith, the only Java that ships
web/      both frontends: public/ admin/ shared/ theme/
deploy/   NapCat container and Nginx config
scripts/  build and self-check scripts
docs/     md/ sources, html/ rendered, diagrams/ figures
tests/    golden set, probes, Playwright
plans/    planning documents
```

Four invariants are enforced by tests: `onebot/` is the only place that knows the OneBot protocol; `OneBotApiClient`
is the only exit for sending messages; `llm/` is the only place that knows which model is used; `guard/` is the only
place allowed to refuse.

## Data and configuration

- Database: `server/data/qa/qqbot.sqlite`. The path resolves against the process working directory, so always start from `server/`.
- Precedence: command line and environment variables → `server/config/overrides.yml` → `application.yml`.
- Secrets live in `.env` at the repository root; template is `.env.example`.
- Not tracked: `.env`, `AGENTS.md`, `deploy/data/`, `server/data/`, `server/web-dist/`, `web/node_modules/`, self-hosted fonts.

## Run it locally

Requires JDK 17+, Maven 3.9+, Node 20+ with pnpm, and Docker.

```bash
cp .env.example .env                 # ONEBOT_TOKEN, model keys, SILICONFLOW_API_KEY, ADMIN_PASSWORD
cd deploy && docker compose up -d    # start the protocol layer and log in to QQ
./scripts/build-web.sh               # build the frontend into server/web-dist/
cd server && mvn spring-boot:run     # start the business layer on :8080
./scripts/health-check.sh            # self-check
```

Frontend dev mode: `cd web && pnpm install && pnpm dev`, public site at 5173/public.html, console at 5173/admin.html.

## Deployment

NapCat container, a systemd-managed jar, Nginx in front. Steps are in the [deployment guide](docs/md/公网部署指南.md).
To update: `./scripts/build-web.sh` and `./scripts/build-server.sh package -DskipTests`, swap the jar, `systemctl restart`.

Host and container are two separate environments; container-side builds always go through `.toolchain/`.

## Documentation

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
