# DSports AI Service

A FastAPI service (port 8090) that holds everything AI-related in DSports: the customer assistant (one LangGraph agent, tools, MCP, RAG), support assistance, Inventory Intelligence, Engineering AI (log investigation), Product & Supplier Intelligence, and Sales & Inventory Planning. It reads commerce data only through the Java backend's public REST API with the **caller's own JWT** and never writes commerce tables; Java stays the source of truth for price, stock, orders, payments, refunds, shipments, returns and support cases. Anything numeric in the internal modules (forecasts, margins, scores, findings) is deterministic code; a language model only understands questions, orchestrates tools and re-words already-computed results, and its output is validated before use.

Guides: [../docs/services/](../docs/services/README.md) (one per area), [../docs/ai/](../docs/ai) (per-phase detail), [ADR-009](../docs/adr/ADR-009-python-ai-service.md). Endpoint reference: [../docs/api/ai-service.md](../docs/api/ai-service.md) (135 operations, generated from the service's own OpenAPI document).

## Endpoint families

| Prefix | Area | Who may call |
|---|---|---|
| `POST /v1/chat`, `POST /v1/confirm`, `/v1/offers`, `/v1/recommendations`, `/v1/support/conversation`, `/v1/support/feedback` | customer assistant, offers, recommendations, case conversation | guests for product Q&A; signed-in customers for account tools |
| `/mcp/` | MCP server (streamable HTTP) exposing the same tool registry, same role gates | bearer token |
| `/v1/inventory/*` | Inventory Intelligence (19 operations) | `ADMIN` |
| `/v1/engineering/*` | Engineering AI (12) | `ADMIN` |
| `/v1/sourcing/*` | Product & Supplier Intelligence (37) | buyer roles: `PROCUREMENT_BUYER`, `CATEGORY_MANAGER`, `ADMIN` (per route) |
| `/v1/planning/*` | Sales & Inventory Planning (54) | planning roles: `SALES_PLANNER`, `CATEGORY_MANAGER`, scoped `REGIONAL_MANAGER` / `STORE_MANAGER`, `ADMIN` (per route) |
| `/v1/support/staff/*` | support-staff assistance (assist, actions, insights) | `SUPPORT_EXECUTIVE`, `ADMIN` |
| `GET /v1/me` | identity derived from the verified token | any valid token |
| `GET /health` | liveness only | anyone |
| `GET /health/dependencies` | database, Tika, Java backend, model state (never calls the model); configuration detail for administrators only | anyone / admin detail |
| `GET /metrics` | Prometheus metrics | open only when `AI_ENV` is `local`, `test` or `dev`; otherwise an admin token or the scrape token `AI_METRICS_TOKEN` |

Roles come only from the signed token; every route also enforces them on the server. The observed role-by-route matrix is [../docs/security/AUTHORIZATION-MATRIX.md](../docs/security/AUTHORIZATION-MATRIX.md).

## Run

```bash
cp .env.example .env   # JWT_SECRET (same as the backend), JWT_ISSUER, JWT_AUDIENCE, AI_CUSTOMER_HASH_KEY, AI_DATABASE_URL, GOOGLE_APPLICATION_CREDENTIALS
uv sync
uv run python -m app.cli index-products        # derived product index
uv run python -m app.cli ingest-corpus         # sample knowledge base (needs Tika on AI_TIKA_URL)
uv run uvicorn app.main:create_app --factory --port 8090
```

Normally started with `bash ../e2e/ai/start-ai.sh` (detached, health-checked). Tables are created idempotently at start-up (`app/store`, `app/*/schema_*.sql`). Docker: `docker compose --profile ai up -d --build ai-service` from the repository root; the image carries the sample corpus and no secrets.

## Tests

```bash
uv run pytest -q                                          # unit tests: no network, database or credentials (fake backend, scripted model)
AI_TEST_DATABASE_URL=... SOURCING_TEST_DATABASE_URL=... PLANNING_TEST_DATABASE_URL=... uv run pytest -m integration   # real PostgreSQL + pgvector, throwaway databases (their tables are dropped)
AI_RUN_LIVE_TESTS=1 AI_TEST_DATABASE_URL=... uv run pytest -m live   # real Vertex, Tika + OCR, MCP over HTTP (stack running)
uv run python eval/run_eval.py                            # evaluation against the real stack (start the service with AI_RATE_LIMIT_PER_MIN=1000)
```
`tests/security/` holds the route sweep (every OpenAPI operation against bad credentials and every role), the tool/role matrix, SSRF and hostile-source tests and the prompt-injection tests.

## Layout

```text
app/api, app/main.py     routes (chat, confirm, health, metrics, me), app factory          app/auth     token verification (signature, exp, sub, iss, aud)
app/agent, app/router    LangGraph agent, Tier 0 rules, Tier 1 classifier, extractors       app/shop, app/reco   live catalogue search, deterministic ranking, recommendations
app/tools, app/mcp       typed registry (role gates, READ/WRITE), DSports client, MCP       app/guard    input guard, untrusted-text fencing, grounding, confirmation tokens
app/rag                  Tika client, chunking, embeddings, ingest, retrieval               app/llm      provider-neutral types, Vertex Gemini, retry + circuit breaker
app/inventory            Inventory Intelligence (mart, forecasts, risk, copilot)            app/engineering   log ingest, redaction, clustering, investigation
app/sourcing             research, providers, SafeFetcher, scoring, calculator, workflow    app/planning      forecasts, plans, variance, workflow, scenarios, analysis
app/support              support-case flow, staff assistance                                app/store    PostgreSQL stores (conversations, pending actions, audit, usage)
app/obs                  correlation ids, redacting JSON logs, usage/cost, metrics          app/config   environment settings (names only in .env.example)
corpus*/, eval/          SAMPLE (synthetic) documents and evaluation sets                   tests/       unit, integration, live, security
```
