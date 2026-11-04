# 13. Runbook

## Prerequisites

PostgreSQL with pgvector (`docker compose up -d postgres`), the AI database `dsports_ai`, Apache Tika (`docker compose up -d tika`), the DSports backend on :8080
(the seed reads the real catalogue, prices and stock through its public API), Google credentials for Gemini and embeddings (see [Phase 1 local development](../12-local-development.md)).
`JWT_SECRET` must be the same in the backend and the AI service.

## First-time setup (from `ai-service/`)

```
uv sync
uv run python -m app.inventory.cli seed            # catalogue + POC reference + POC SYNTHETIC history (replaces all inv_* data; ~5 s)
uv run python -m app.inventory.cli train           # data quality, backtests, forecasts, event impact, elasticity, assessments (~21 s)
uv run python -m app.inventory.cli ingest-corpus   # internal policies: Tika -> chunks -> embeddings -> pgvector (document types inv_*)
uv run python -m app.inventory.cli status
bash ../e2e/ai/start-ai.sh                         # AI service on :8090 (AI_RATE_LIMIT_PER_MIN is raised for evaluation only)
```

`scripts/make_inventory_corpus.py` regenerates `supplier-lead-times-and-moq.md` from the same POC reference data the calculations use.
Open `http://localhost:3000/inventory-ai` signed in as an **ADMIN** user (the E2E helper `e2e/ai/inventory_setup.py` creates one).

## Operations

| Task | Command / call |
|---|---|
| retrain the forecast | `POST /v1/inventory/models/train` (UI: **Refresh forecast**) or `python -m app.inventory.cli train`; one run at a time (409 otherwise) |
| rebuild the synthetic mart | `python -m app.inventory.cli seed [--as-of YYYY-MM-DD]` then `train` |
| re-ingest policies | `python -m app.inventory.cli ingest-corpus [--force]` |
| usage, tokens, cost, audit counts | `GET /v1/inventory/usage?hours=168` |
| model and data quality | `GET /v1/inventory/quality` |
| audit trail | table `inv_audit` (actions `TRAIN`, `QUERY`, `CLEARANCE`, `DENIED`), `inv_scenario_run` |
| real-data audit | `python e2e/ai/inventory_real_data_audit.py` |

Scheduling later: call `pipeline.train(pool)` from a job; nothing else is needed. History is kept per run (`inv_model_run`), so a new model can be compared with the previous one.

## API (all `ADMIN`; base `/v1/inventory`, through the shop at `/ai-api/v1/inventory`)

| Method and path | Returns |
|---|---|
| `GET /overview` | health counts, inventory value, excess, upcoming events, model info |
| `GET /taxonomy`, `GET /products` | sports/categories, product positions |
| `GET /forecast?sport&category`, `GET /forecast/{productId}` | persisted forecast, history, interval, assessment |
| `GET /risk?sport&category&level&scenario&limit` | stock-out ranking |
| `GET /replenishment?sport&category&scenario` | order quantities, dates, priorities |
| `GET /overstock?older_than_days`, `GET /slow-moving`, `GET /aging`, `GET /clearance` | excess, classes, age buckets, candidates |
| `GET /events`, `GET /events/{id}` | events; uplift evidence and per-SKU readiness |
| `GET /regions?sport&category&product_id&event_id&excess_only` | regional demand, stock, cover, suggested transfers |
| `POST /scenario` `{sport, category, productIds, discounts[], days, slowMovingOnly}` | what-if (calculation only, logged) |
| `POST /models/train` | retrain; returns metrics |
| `GET /quality`, `GET /usage` | model and data-quality report; LLM usage and cost |
| `POST /copilot` `{message, conversationId?}` | structured answer; see [09](09-inventory-agent.md) |

Errors use the common `ApiError` shape: `401 UNAUTHORIZED`, `403 FORBIDDEN`, `404 INSUFFICIENT_DATA`, `409 TRAINING_IN_PROGRESS`, `422 VALIDATION_ERROR`, `503 INVENTORY_UNAVAILABLE`.
OpenAPI: `http://localhost:8090/docs` (tag `inventory-intelligence`).

## Tests

```
cd ai-service
uv run pytest                                                   # unit (hermetic, no network or database)
AI_TEST_DATABASE_URL=postgresql://.../dsports_ai_test uv run pytest -m integration   # real PostgreSQL + pgvector, incl. tests/integration/test_inventory_db.py
uv run python eval/run_inventory_eval.py --suite numeric-dev|numeric-holdout          # deterministic evaluation, no stack
uv run python eval/run_inventory_eval.py --suite dev|holdout                          # real stack, real Gemini (the holdout is meant to be run ONCE)
cd ../e2e && node inventory-ui.mjs <adminEmail> <customerEmail> <password> <shotDir>  # real Chrome, 23 steps (accounts: ai/inventory_setup.py)
cd ../frontend && npm run build                                                        # tsc -b + vite build
```

### Consolidated validation

```
bash e2e/validate-all.sh [--skip-build] [--only <prefix>] [--with-holdout2]     # Phase 1 + Phase 2 on the real stack; details in e2e/README.md
```
Result per step is PASS / FAIL / BLOCKED / SKIP in `e2e/validation-results/<timestamp>/SUMMARY.md`. Failure behaviour is validated against real faulty instances (`e2e/ai/failure-instances.sh`).
Measured (not configured) performance: `python e2e/ai/inventory_measure.py`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `404 INSUFFICIENT_DATA: No inventory forecast exists yet` | run `seed` then `train` |
| `503 INVENTORY_UNAVAILABLE` | the AI database is not configured or reachable (`AI_DATABASE_URL`); the storefront is unaffected |
| `403` for a signed-in staff user | their token lacks the `ADMIN` role (the Java backend grants it; tokens last 15 minutes: sign in again after a role change) |
| copilot answers show the plain headline and no explanation | the model is unavailable or its wording failed a guard (`llm.used` / `llm.grounding_fallback` in the response) |
| "I couldn't find a product, sport or category called ..." | the capitalised name matches nothing in the catalogue; use a catalogue name or a sport |
| forecasts look stale | the mart's `as_of` is the seed date; reseed to move the synthetic history forward |
| policy answers say nothing was found | run `ingest-corpus` (Tika must be up) |
