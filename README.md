# DSports-AI

An AI-assisted sports commerce platform, built as an enterprise-style learning project. Version 1 sells cricket equipment (the demo catalogue also holds other sports). It is a Java/Spring WebFlux commerce backend, a React storefront and staff workspaces, and a separate Python AI service.

**Status in one paragraph.** The commerce flow, the customer assistant, support cases, inventory intelligence, engineering log investigation, product/supplier intelligence and sales planning are implemented and exercised by automated tests and real-stack scripts. Several of them run on **synthetic or demonstration data**, the payment gateway is a **mock**, and there has been **no penetration test, external audit or production load test**. The application is not production-ready. The evidence for every claim is in [docs/STATUS.md](docs/STATUS.md).

## What is in the repository

| Part | Location | Port | Technology |
|---|---|---|---|
| Storefront and staff workspaces | [frontend/](frontend/) | 3000 | React 19, TypeScript, Vite, MUI, React Router, TanStack Query, Axios |
| Commerce backend (authoritative for price, stock, orders, payments, refunds, shipments, returns, support cases, identity) | [backend/](backend/) | 8080 | Java 21, Spring Boot WebFlux, R2DBC, Flyway, Maven |
| AI service (assistant, inventory, engineering, sourcing and planning intelligence) | [ai-service/](ai-service/) | 8090 | Python 3.13, FastAPI, LangGraph, MCP, asyncpg, uv |
| PostgreSQL with pgvector | `docker-compose.yml` | 5432 | databases `dsports` (Java) and `dsports_ai` (Python) |
| Apache Tika with Tesseract (document text and OCR) | `docker-compose.yml` | 9998 | used only by the AI service ingestion |
| Model provider | external | n/a | Gemini on Vertex AI (optional: without it the deterministic parts still work) |
| End-to-end scripts and the consolidated validation | [e2e/](e2e/) | n/a | bash, Python, Playwright (Node) |

The browser talks to the backend through `/api` and to the AI service through `/ai-api` (both proxied by Vite). The AI service reads commerce data only through the backend's public REST API, forwarding the caller's own token, and never writes commerce tables. Details: [docs/ARCHITECTURE-OVERVIEW.md](docs/ARCHITECTURE-OVERVIEW.md).

## Prerequisites

Docker, Java 21 and Maven, Node 22 and **pnpm** (the lockfile is `pnpm-lock.yaml`), Python 3.13 and [uv](https://docs.astral.sh/uv/), Chrome for browser tests, and for live AI a Google service-account key with Vertex AI access. On Windows run the `.sh` scripts with Git Bash.

## Quick start

```bash
cp ai-service/.env.example ai-service/.env     # then fill in the values; the file is git-ignored and must never be committed
docker compose up -d postgres && docker compose --profile ai up -d tika
(cd backend && mvn -B -o clean install -DskipTests)   # stop a running backend first (it locks the jar)
bash e2e/ai/start-backend.sh                          # Flyway applies the migrations (V1 to V47)
bash e2e/ai/start-ai.sh
(cd frontend && pnpm install && pnpm exec vite)       # http://localhost:3000
```

The backend and the AI service must share the same `JWT_SECRET`, `JWT_ISSUER` (default `dsports`) and `JWT_AUDIENCE` (default `dsports-api`), otherwise every AI call returns 401. Full configuration, seed data, roles, troubleshooting and the demo runbook: [docs/SETUP-AND-DEMO.md](docs/SETUP-AND-DEMO.md). Start-up order, health checks, logs and recovery: [docs/OPERATIONS.md](docs/OPERATIONS.md).

## Running the tests

| Layer | Command |
|---|---|
| Java unit | `cd backend && mvn -B -o package` |
| Java integration (Testcontainers) | `cd backend && mvn -B -o test -Pintegration-test -Dapi.version=1.44` (needs Docker) |
| Python unit | `cd ai-service && uv run pytest -q` |
| Python integration | `cd ai-service && uv run pytest -m integration` with `AI_TEST_DATABASE_URL`, `SOURCING_TEST_DATABASE_URL`, `PLANNING_TEST_DATABASE_URL` pointing at throwaway databases |
| Frontend | `cd frontend && pnpm exec tsc -b && pnpm lint && pnpm exec vitest run && pnpm build` |
| Real-stack scripts and browser journeys | see [e2e/README.md](e2e/README.md) |
| Everything, with PASS / FAIL / BLOCKED / SKIP per step | `bash e2e/validate-all.sh` (about an hour; writes `e2e/validation-results/<timestamp>/SUMMARY.md`) |

The last recorded results are in [docs/VALIDATION-MATRIX.md](docs/VALIDATION-MATRIX.md) and [docs/STATUS.md](docs/STATUS.md); which documented commands were actually executed is in [docs/OPERATIONS.md §7](docs/OPERATIONS.md).

## Documentation

Start at the index: **[docs/README.md](docs/README.md)**. The main entries:

| Read this | For |
|---|---|
| [docs/STATUS.md](docs/STATUS.md) | What is complete, partial, synthetic or blocked, with evidence |
| [docs/ARCHITECTURE-OVERVIEW.md](docs/ARCHITECTURE-OVERVIEW.md) | Boundaries and the real request, transaction and AI flows (Mermaid) |
| [docs/services/](docs/services/README.md) | One guide per service: frontend, Java backend, AI platform, customer AI, support, inventory, engineering, sourcing, planning |
| [docs/DATABASE.md](docs/DATABASE.md) | Schemas, ownership, migrations and data provenance |
| [docs/api/](docs/api/README.md) | Endpoint references generated from the services' own OpenAPI documents |
| [docs/SECURITY.md](docs/SECURITY.md), [docs/security/](docs/security/) | Controls, threat model, authorization matrix, residual risks |
| [docs/learning-guide/](docs/learning-guide/00-master-learning-guide.md) | Code-grounded study guide and a suggested reading order |
| [docs/adr/](docs/adr/) | Architecture decisions |

Working rules for contributors and AI assistants: [AGENTS.md](AGENTS.md).

## Known limitations (summary)

* The payment gateway is a mock (`MockPaymentGateway`); no real payment provider is integrated.
* Inventory, planning and several demo datasets are **synthetic**, labelled as such, and their accuracy figures are not business accuracy.
* Tokens are kept in `localStorage`, signing uses one shared HS256 secret, rate limiters are in-memory, and no Java or Python dependency scanner has been run. See the residual-risk table in [docs/security/THREAT-MODEL.md](docs/security/THREAT-MODEL.md).
* The `franchise`, `billing`, `admin` and `ai` Maven modules are empty shells (no code).
* The whole working tree is uncommitted at the time of writing.
