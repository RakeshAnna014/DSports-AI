# AI service platform (`ai-service/`, port 8090): shared foundation

Everything in this document applies to every AI area (customer assistant, support, inventory, engineering, sourcing, planning). Area-specific behaviour is in the sibling guides.

## 1. Business purpose
One Python service that adds understanding, retrieval and explanation on top of the Java commerce system, plus internal decision-support modules for staff. It is deliberately **not** a second commerce system: it never owns price, stock, orders, payments, refunds, shipments, returns or support cases ([ADR-009](../adr/ADR-009-python-ai-service.md)).

## 2. Entry points
* `app/main.py` `create_app(settings)` builds the FastAPI app: lifespan opens the database store (if reachable), builds `Services` (`app/services.py`: HTTP client, LLM, embedder, retriever, usage recorder), `ChatService`, `InventoryCopilot`, the MCP app, and mounts the routers. Without a database or model the service still starts and reports `DEGRADED` on `/health/dependencies`.
* Routers: `app/api/routes.py` (chat, confirm, offers, recommendations, support conversation, health, metrics, me) and one `api.py` per area (`inventory`, `engineering`, `sourcing`, `planning`, `support/staff_api.py`).
* `app/cli.py` and `app/<area>/cli.py`: index products, ingest corpora, seed/train inventory, ingest logs.
* Start: `bash e2e/ai/start-ai.sh` or `uv run uvicorn app.main:create_app --factory --port 8090`.

## 3. Request lifecycle
1. `app/obs/middleware.py`: correlation id (accepts `X-Correlation-Id` or creates one), structured JSON logging with redaction, request metrics.
2. Route dependency `current_principal` / `optional_principal` (`app/auth/jwt.py`): verifies the bearer token (signature, `exp`, `sub`, `iss`, `aud`; HS256 family only) and builds a `Principal` (user id, roles, customer hash = HMAC of the id, never the raw id in logs).
3. The route (or the agent) calls tools through the **registry** (`app/tools/registry.py`): strict argument models (unknown fields such as `customerId` are rejected), authentication and role gate (`required_role` / `required_any_role`), a per-request call budget, and, for WRITE tools, a confirmation check.
4. Tools reach Java only through `DSportsClient` with the caller's own bearer token and a request timeout (`AI_TIMEOUT_S`, default 30 s; no automatic retry of Java calls); Java's ownership checks are the real enforcement.
5. Errors become the same envelope as Java's (`app/errors.py`): `status, error, code, message, path, correlationId, timestamp`; unexpected errors are a generic 500; database data errors (malformed ids) are 422.

## 4. Business rules (cross-cutting)
* Numbers in internal modules come from deterministic code; the model may only word them, and its text is checked (`app/guard/grounding.py`, per-area validators).
* Text from users, catalogue, supplier pages, logs or stored records is **untrusted data**: `app/guard/untrusted.py` removes instruction-like text, caps length and fences it as `<untrusted_data>`.
* Consequential actions (return, reorder, support case, message, escalation, reopen) are two steps: a proposal computed from live data, then `POST /v1/confirm` with a single-use token (`app/guard/confirmation.py`) HMAC-bound to the caller, the tool and the argument hash, valid 5 minutes, executed with an idempotency key and audited.
* Internal tools are read-only and role-gated; no tool takes SQL, a file path, a shell command or a free URL.

## 5. Data ownership
Database `dsports_ai` (separate from Java's `dsports`), schemas loaded idempotently at start:

| Area | Tables |
|---|---|
| Platform (`app/store/schema.sql`, `schema_rag.sql`) | `ai_meta`, `conversations`, `pending_actions`, `audit`, `usage`, `documents`, `chunks` (pgvector), `product_index` |
| Support staff assistance | `support_ai_actions` |
| Inventory | `inv_*` (sku, region, demand_daily, stock_snapshot, position, purchase_order, event, event_impact, forecast, model_run, assessment, elasticity, scenario_run, dq_report, audit, region_share) |
| Engineering | `eng_logs`, `eng_deployments`, `eng_incident_status`, `eng_audit` |
| Sourcing | `src_*` (opportunities, suppliers, quotes, evidence, scores, calcs, decisions, proposals, runs, records, images, demand_signals) |
| Planning | `pln_*` (location, scope, sales_line, sales_week, forecast_run, forecast_series, forecast, plan, plan_line, actual_adjustment, stock_position, incoming, alloc_rec, transfer_rec, scenario, pilot, eval, analysis, analysis_action, audit, import_batch, ingest_run, data_through) |

## 6. External dependencies
Java backend (REST), PostgreSQL + pgvector, Apache Tika (documents, OCR), Gemini on Vertex AI (`AI_INTENT_MODEL`, `AI_REASONING_MODEL`, `AI_EMBEDDING_MODEL`), and allow-listed public web sites for sourcing. Model access is wrapped by `app/llm/resilient.py` (one retry, circuit breaker `AI_BREAKER_FAILURES` / `AI_BREAKER_COOLDOWN_S`, timeouts).

## 7. Security and authorization
Roles come only from the signed token and are enforced in the service for every route and tool. Matrices: [../api/ai-tools.md](../api/ai-tools.md) (65 tools, generated), [../security/AUTHORIZATION-MATRIX.md](../security/AUTHORIZATION-MATRIX.md) (observed on the real stack). Production hardening: docs/redoc/openapi disabled when `AI_ENV` is `prod`/`production`; `/metrics` needs an admin or `AI_METRICS_TOKEN` outside `local`/`test`/`dev`; health configuration detail is admin-only. Threat model: [../security/THREAT-MODEL.md](../security/THREAT-MODEL.md).

## 8. Failure handling and observability
| Failure | Behaviour |
|---|---|
| Model unavailable / quota | breaker opens; Tier-0 rule flows, deterministic recommendations, forecasts and findings still work; chat returns a structured `ai_unavailable`/fallback |
| Database down at start | service runs `DEGRADED`; DB-backed routes return an error envelope |
| Java backend down | tools return typed errors; the assistant never claims a write succeeded unless Java confirmed it |
| Tika down | ingestion fails and says so; chat is unaffected |
Observability: JSON logs (redacted) with correlation id; Prometheus `/metrics`; per-call token and cost *estimate* in `usage`; `GET /health/dependencies`. No tracing backend, alerting or central log platform exists.

## 9. Run and test
See [../../ai-service/README.md](../../ai-service/README.md). Unit `uv run pytest -q`; integration `uv run pytest -m integration` with the three `*_TEST_DATABASE_URL` variables (throwaway databases); security suite `tests/security/`.

## 10. Implemented / partial / missing
Implemented: everything above. Partial: single instance only (in-memory rate limiter and call budgets, no shared cache). Missing: asymmetric signing, token revocation, retention rules for conversations and audit rows. First files: `app/main.py`, `app/auth/jwt.py`, `app/tools/registry.py`, `app/guard/untrusted.py`, `app/guard/confirmation.py`, `app/errors.py`.
