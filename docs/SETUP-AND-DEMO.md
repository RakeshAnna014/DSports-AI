# DSports-AI: setup, testing and demo runbook

Single entry point for running, testing and demonstrating the project on one machine. Deeper material is linked, not repeated.
Status of every requirement: [STATUS.md](STATUS.md). Architecture: [ARCHITECTURE-OVERVIEW.md](ARCHITECTURE-OVERVIEW.md).

## 1. Prerequisites

Docker (Postgres with pgvector, Tika), Java 21 + Maven, Node 22 + **pnpm** (the frontend lockfile is `pnpm-lock.yaml`; do not use `npm install`, it cannot read the pnpm layout), Python 3.13 + [uv](https://docs.astral.sh/uv/), Chrome (browser E2E), and for live AI a Google service-account key with Vertex AI access.

## 2. Ports, databases and health checks

| Component | Port | Health |
|---|---|---|
| Frontend (Vite, proxies `/api`→8080 and `/ai-api`→8090) | 3000 | `GET http://localhost:3000/` |
| Java backend | 8080 | `GET /actuator/health` |
| AI service | 8090 | `GET /health` (liveness only), `GET /health/dependencies` (DB, Tika, LLM breaker state; never calls the model) |
| PostgreSQL (`pgvector/pgvector:pg16`) | 5432 | `docker exec dsports-postgres pg_isready -U dsports` |
| Tika + Tesseract | 9998 | `GET /tika` |

Databases in the one Postgres: `dsports` (commerce, owned by Java/Flyway), `dsports_ai` (AI service: vectors, conversations, usage, Inventory Intelligence `inv_*`, Engineering AI `eng_*`), `dsports_ai_test` (throwaway, for integration tests: its tables are dropped by tests).

## 3. Configuration

Never commit secrets: `ai-service/.env` is git-ignored. Copy `ai-service/.env.example` and fill:

| Variable | Notes |
|---|---|
| `JWT_SECRET` | ≥ 32 bytes; **must be identical** for the backend and the AI service, otherwise every AI call returns 401 |
| `JWT_ISSUER`, `JWT_AUDIENCE` | default `dsports` and `dsports-api`; tokens must carry both and both services must agree |
| `AI_ENV` | `local` (default), `test`, `dev` keep `/metrics` and the API docs open; any other value (for example `prod`) closes the docs and protects `/metrics` (`AI_METRICS_TOKEN` or an administrator token) |
| `PLANNING_*`, `SOURCING_*` | planning and sourcing behaviour (week start, horizon, allow-listed hosts, research providers, exchange rates); names and comments in `ai-service/.env.example` |
| `AI_CUSTOMER_HASH_KEY` | ≥ 16 bytes |
| `AI_DATABASE_URL` | DSN of `dsports_ai` |
| `GOOGLE_APPLICATION_CREDENTIALS`, `GOOGLE_CLOUD_PROJECT` | Vertex AI access (live AI only) |
| `DB_NAME`, `DB_PASSWORD` (backend) | defaults in `application-local.yml` are local-development defaults only; `application-prod.yml` has none |
| `RATE_LIMIT_ENABLED`, `RATE_LIMIT_AUTH_PER_MINUTE` (default 20), `RATE_LIMIT_TICKETS_PER_MINUTE` (default 10) | Java rate limiter (in-memory, per instance; see [SECURITY.md](SECURITY.md)). The `local` profile raises both limits to 100000 so test harnesses are not blocked; set them low to demonstrate a 429 |
| `AI_RECO_*` | recommendation budget rules (see [management-feedback](management-feedback/README.md)); `ENG_BACKEND_LOG`, `ENG_AI_LOG` for Engineering AI log paths |

Full AI variable table: [ai/12-local-development.md](ai/12-local-development.md).

## 4. Start (order matters) and stop

```bash
docker compose up -d postgres && docker compose --profile ai up -d tika
bash e2e/ai/start-backend.sh      # stops only THIS project's backend, starts the jar detached, waits for health; Flyway applies new migrations forward
bash e2e/ai/start-ai.sh           # same for the AI service (creates/updates its tables idempotently)
cd frontend && pnpm install && pnpm exec vite     # or: pnpm dev
```

Build the jar first with `cd backend && mvn -B -o clean install -DskipTests` (stop the backend before rebuilding: Windows locks the running jar).
On Windows run the `.sh` scripts with Git Bash (`& 'C:\Program Files\Git\bin\bash.exe' e2e/ai/start-ai.sh`). Stop: `bash e2e/ai/free-port.sh 8080` (8090, 3000): it only stops processes whose command line belongs to this project and refuses to touch another project's process.

**Migrations**: Flyway is forward-only and runs at backend start (current head V47, confirmed against `flyway_schema_history`). Never drop `dsports`; for destructive experiments use a throwaway database (the consolidated validation creates `dsports_e2e_val`).

## 5. Seed data

```bash
cd ai-service
uv run python -m app.cli index-products        # product embeddings
uv run python -m app.cli ingest-corpus         # sample knowledge base (SYNTHETIC demo documents, labelled as such)
uv run python -m app.support.cli ingest-corpus # support policy/procedure documents (SYNTHETIC demo content, not an official SLA)
uv run python -m app.inventory.cli seed && uv run python -m app.inventory.cli train   # POC SYNTHETIC history + forecast
uv run python -m app.engineering.cli ingest --service backend     # real backend log -> Engineering AI
uv run python -m app.engineering.cli synthetic                    # the labelled synthetic incident
```

Planning and sourcing (as an `ADMIN` token; both are idempotent and demonstration rows are labelled):

```text
POST /v1/planning/ingest                 # live online orders from the Java sales feed
POST /v1/planning/demo/seed              # labelled SYNTHETIC store network and history (optional)
POST /v1/planning/forecast/runs          # about 20 s
POST /v1/sourcing/runs                   # research run through the allow-listed provider
POST /v1/sourcing/demo/seed              # labelled DEMO records (optional)
```
Roles for these screens (`SALES_PLANNER`, `CATEGORY_MANAGER`, `REGIONAL_MANAGER`, `STORE_MANAGER`, `PROCUREMENT_BUYER`) are granted by SQL like the others; scoped managers also need a row in `pln_scope` (`POST /v1/planning/scope`).

Privileged roles are **not** assignable through any API (only a domain method exists). Grant by SQL on purpose:
`INSERT INTO customer_roles (customer_id, role) SELECT id,'ADMIN' FROM customers WHERE email='…'` (roles used: `ADMIN`, `SUPPORT_EXECUTIVE`).

## 6. Tests: exact commands

| Layer | Command | Needs |
|---|---|---|
| Java unit | `cd backend && mvn -B -o package` | nothing external |
| Java integration (Testcontainers) | `cd backend && mvn -B -o test -Pintegration-test -Dapi.version=1.44` with `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` on Windows | Docker |
| Python unit | `cd ai-service && uv run pytest -q` (integration and live tests are deselected by default) | nothing external (fake DSports, fake LLM) |
| Python integration | `AI_TEST_DATABASE_URL=…/dsports_ai_test SOURCING_TEST_DATABASE_URL=…/dsports_ai_src_test PLANNING_TEST_DATABASE_URL=…/dsports_ai_pln_test uv run pytest -m integration` (each database must exist and is **throwaway**) | Postgres |
| Python live (real Vertex) | `AI_RUN_LIVE_TESTS=1 AI_TEST_DATABASE_URL=… uv run pytest -m live` | stack up + Gemini quota |
| Frontend | `cd frontend && pnpm exec tsc -b && pnpm lint && pnpm exec vitest run && pnpm build` | nothing external (API mocked per test) |
| Real-stack API/browser E2E | `bash e2e/api-e2e.sh`, `uv run --project ai-service python e2e/ai/mgmt_feedback_e2e.py`, `uv run --project ai-service python e2e/ai/engineering_e2e.py`, `cd e2e && node mgmt-feedback-ui.mjs ../scratch_shots` (also `ui-flow.mjs`, `support-ui.mjs`, `inventory-ui.mjs`; planning, sourcing and security: `python e2e/ai/planning_e2e.py`, `planning_audit.py`, `sourcing_e2e.py`, `security_e2e.py`, and `node e2e/planning-ui.mjs` / `sourcing-ui.mjs <world.json> scratch_shots` after `planning_data.py` / `sourcing_data.py > world.json`) | full stack |
| Everything, with honest PASS/FAIL/BLOCKED/SKIP | `bash e2e/validate-all.sh [--skip-build] [--only <prefix>]` → `e2e/validation-results/<timestamp>/SUMMARY.md` (~1 hour) | full stack + Docker + Gemini |

**Evaluation integrity**: `ai-service/eval/` separates development sets, regression runs and held-out sets. The first run of a held-out set is preserved in `eval/results/` and labelled; later runs are regression. Never edit a held-out expectation to change a score (see [ai/phase3/10-evaluation.md](ai/phase3/10-evaluation.md)). Mocks/synthetic data: Python unit tests use a fake DSports and a scripted LLM; integration tests use a real Postgres but fake LLM; live tests and E2E use real services; Inventory data is POC SYNTHETIC; the Engineering AI incident is SYNTHETIC and labelled.

## 7. Demo runbook

URLs are the local defaults above. Customers register at `/register`; roles are granted as in §5.

1. **Customer shopping and recommendations** (customer): open `http://localhost:3000/products/00000000-0000-0000-0000-000000001070` (Head Tennis Racquet, ≈₹10,499). The "Recommended with…" block shows *Goes with this*: only in-stock tennis items at or below 20 % of the price (₹2,099.80) with the rule stated. Use *Adjust budget* (₹500 is applied as given and never widened) and *Show premium alternatives* (on a bat product). Add a recommended item to the cart.
2. **Customer AI**: header button "Ask DSports AI": "show lightweight cricket bats under ₹8000", then "what goes with this bat" after choosing a product. Account questions require sign-in.
3. **Support and returns**: `/support` → *Raise a ticket* (review step, then submit). In the assistant: "My order has not arrived" → investigation → a proposal that creates a ticket **only after you confirm**. Staff (`SUPPORT_EXECUTIVE` or `ADMIN`): `/support-cases` → search by ticket number, take it, change priority (reason required), reply, internal note; customer view never shows the note.
4. **Profiles and budget controls**: `/profile` → pick Tennis and a price range, add a family member (age band only), switch the active profile; the home page "Picked for you" follows the active profile and disappears when nothing relevant exists.
5. **Inventory Intelligence** (`ADMIN`): `/inventory-ai`. All data is labelled POC SYNTHETIC; ask the copilot "which products will stock out in 14 days?" and open the evidence behind each answer.
6. **Engineering AI** (`ADMIN`): `python -m app.engineering.cli synthetic`, then `/engineering-ai`, choose "Synthetic only", ask "Why did checkout fail?": clusters, a deployment-timing hypothesis (marked not confirmed), evidence records L<id> with correlation ids, synthetic labels, and a withheld injected log line. Choose "Real only" and ask "Show recurring errors in the backend in the last 3 days" for the real recurring warning.

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Every AI call 401 | `JWT_SECRET` differs between backend and `ai-service/.env`: export it from `.env` before `java -jar` (the start scripts do) |
| `npm install` fails with `Invalid Version` | the frontend is a pnpm project: use `pnpm install` |
| Jar cannot be rebuilt ("Unable to rename") | stop the backend first (`bash e2e/ai/free-port.sh 8080`) |
| 29 Testcontainers tests silently skipped | set `DOCKER_HOST` and `-Dapi.version=1.44` |
| Vite serves stale/empty files after external edits | restart Vite |
| Live AI steps BLOCKED / `LLM_QUOTA` | Gemini quota or billing: record as BLOCKED, do not retry in a loop |
| Engineering AI shows no sources | run the `ingest` commands in §5; the AI log file is overwritten at each service restart |
| Port held by another project | the scripts report it and refuse to kill it; use another port |
