# 04 — AI Service (Phase B foundation)

> **Status:** foundation only. The service authenticates requests, reaches Gemini/Vertex and its own database, and is
> observable. It does **not** yet answer any customer question. See [What is NOT implemented](#what-is-not-implemented).

## Why Python

LangGraph (agent workflows), the official MCP SDK, the Vertex/Gemini SDKs and evaluation tooling are most mature in
Python, and AI traffic is slower, costlier and less predictable than checkout. A separate process keeps an AI outage
or cost spike from touching commerce. Decision and trade-offs: [ADR-009](../adr/ADR-009-python-ai-service.md).

## Java / Python boundary

```text
Java:
Commerce domain
Price
Inventory
Orders
Payments
Refunds
Shipments
Returns
Authentication/authorization

Python:
AI orchestration
Intent understanding
LangGraph
RAG
Embeddings
MCP
LLM interaction
AI-specific state/observability
```

The service talks to DSports only over its public REST APIs (`app/tools/dsports_http.py` is the async client
foundation) and owns only derived AI state in the `dsports_ai` database.

## Authentication flow

1. The browser signs in with DSports and holds the usual access token.
2. A request to the AI service carries `Authorization: Bearer <that token>`.
3. The service verifies the signature with the shared secret (`JWT_SECRET`), requires `exp` and `sub`, and takes the
   identity **only** from the verified `sub` (must be a UUID) and `roles`. No request field is ever used as an identity
   (tested: `customerId`/`userId` query values and an `X-User-Id` header are ignored).
4. The raw user id is not logged, stored or returned. A keyed hash (`HMAC-SHA256(AI_CUSTOMER_HASH_KEY, id)`, 32 hex)
   identifies the customer in logs, usage and audit.
5. When tools exist, the same token is forwarded to DSports; Spring's ownership checks stay the authorization
   boundary. The AI service never authorizes commerce resources itself.

> **Algorithm finding.** The plan assumed HS256. The Java backend uses jjwt, which chooses the HMAC strength from the
> secret length (32–47 bytes → HS256, 48–63 → HS384, ≥64 → HS512). The local backend (56-byte secret) issues **HS384**
> tokens, and a first version of this service that pinned HS256 rejected them. The service now accepts the three HMAC
> variants and rejects `none` and every asymmetric algorithm (tested). Verified against a token issued by the real
> backend.

## Vertex AI / Gemini configuration

All via environment variables (`.env.example`). Credentials come from Application Default Credentials
(`GOOGLE_APPLICATION_CREDENTIALS`); nothing is hard-coded and the credential is never read by our code or logged.

| Variable | Default | Meaning |
|---|---|---|
| `GOOGLE_APPLICATION_CREDENTIALS` | – | service-account key file (must exist; path not exposed) |
| `GOOGLE_CLOUD_PROJECT` | project of the credential | override only if needed |
| `AI_VERTEX_LOCATION` | `us-central1` | **data-residency decision, see below** |
| `AI_INTENT_MODEL` | `gemini-2.5-flash-lite` | cheap structured-output tier |
| `AI_REASONING_MODEL` | `gemini-2.5-flash` | synthesis tier |
| `AI_EMBEDDING_MODEL` / `AI_EMBEDDING_DIM` | `text-embedding-005` / `768` | stored with the vectors' config |
| `AI_MAX_TOKENS`, `AI_TEMPERATURE`, `AI_TOP_K`, `AI_MAX_TOOL_CALLS`, `AI_TIMEOUT_S` | 1024, 0.2, 5, 8, 30 | limits |
| `AI_PRICE_TABLE_JSON` | `{}` | operator-supplied USD per 1M tokens per model; empty ⇒ no cost estimate |

**Embedding re-index design.** On startup the service records `embedding_model|dimension` in `ai_meta`. If either later
differs, `/health/dependencies` reports `embedding_config: reindex_required` and the recorded value is *not*
overwritten until a re-index completes (that job comes with RAG). The live test fails if `AI_EMBEDDING_DIM` does not
match what the model actually returns.

## Vertex smoke test — exact result (2026-10-07)

Run with the project's configured credential, through the service's own LLM layer (no mocks, no stubs):

| Check | Result |
|---|---|
| Credential type / identity | service account; `client_email` and `project_id` present (key never printed) |
| Project resolved by ADC | `myteam-ai` (`GOOGLE_CLOUD_PROJECT` unset) |
| Token exchange | OK |
| Vertex AI API reachable | yes: model catalog listed in `us-central1` (129 models), `global`, `asia-south1` |
| Service Usage / IAM introspection | `serviceusage.services.get` denied (403) and `testIamPermissions` rejected the probed names; **informational only — invocation works** |
| Gemini request, `gemini-2.5-flash-lite` (`us-central1`) | OK, JSON returned, 15 in / 5 out tokens, 0.9–5.8 s |
| Gemini request, `gemini-2.5-flash` (`us-central1`) | OK, JSON returned, 15 in / 5 out tokens, ≈1 s |
| `gemini-2.5-flash` in `asia-south1` | OK (only Gemini model listed there) |
| `gemini-3.5-flash`, `gemini-3.5-flash-lite` (`us-central1`) | listed in the catalog but **404 for this project** — not used |
| Embedding `text-embedding-005` | OK, **768** dimensions, 0.3–2.1 s (`us-central1` and `asia-south1`) |
| Embedding `gemini-embedding-001` | OK, 3072 dimensions (not selected) |
| Inside the Docker container | same results with the credential mounted read-only |

Notes: a model's *self-reported* name in the JSON it returns is unreliable (it answered with other model names); the
service reports the configured model id. Catalog listing does not imply access (the 3.5 models). Latency of the first
call includes client and token set-up.

**Decisions for you**
1. **Project.** The only credential is for project `myteam-ai`, not obviously a DSports project. Usage and cost land
   there. Provide a DSports project/credential, or accept this one, before production.
2. **Location.** `us-central1` has every selected model; `asia-south1` has `gemini-2.5-flash` and the embeddings but not
   `flash-lite`. Customer text leaves India either way unless you choose `asia-south1` (then both tiers would use
   `gemini-2.5-flash`).

## AI database (`dsports_ai`)

Derived AI state only — never product, price, stock, order or payment data, and no raw customer ids.

| Table | Purpose |
|---|---|
| `ai_meta` | key/value: `schema_version`, `embedding_config` |
| `conversations` | per-conversation summary/state, owned by `customer_hash`, 24 h expiry |
| `audit` | proposals/confirmations/executions of state-changing actions (used from the returns phase) |
| `usage` | one row per model call: model, tokens in/out, latency, estimated cost, tier/intent, correlation id |

The schema is `app/store/schema.sql`, applied idempotently at startup under an advisory lock. Three tables do not yet
justify a migration framework; revisit when the schema starts to evolve. No embeddings or product index exist.

## Usage and cost

Each Gemini/embedding call writes one structured log line and (when the database is available) one `usage` row. Cost is
computed only from `AI_PRICE_TABLE_JSON`; a model without an entry has `cost_estimate = NULL`, and every cost carries
`cost_is_estimate = true`. No price is built into the code. Persistence failures are logged and never fail a request.

## Observability

- `X-Correlation-Id` is propagated if it matches `[A-Za-z0-9._-]{1,64}`, otherwise a UUID is generated; it is returned
  in the response header and in every error body, and will be forwarded to DSports. `X-Request-Id` is generated per
  request. `conversationId` is added to log lines when set.
- Logs are JSON on stdout. The request log records method, path (no query string), status, duration and customer hash.
- A redaction layer removes JWTs, `Bearer` values, e-mail addresses, connection strings, private keys and raw UUIDs
  from messages, and any field whose name suggests a secret or personal data (authorization, password, token, email,
  phone, address, customer/user id …). Token *counts* are kept. Tested against the real output stream.

## Health

- `GET /health` — process liveness. No dependency, no LLM.
- `GET /health/dependencies` — always 200; reports `database` (UP/DOWN/DISABLED), `dsports_backend`, and whether the LLM
  is `CONFIGURED` (never called), overall `UP` or `DEGRADED`, plus effective non-secret configuration. A database that is
  down at startup does not stop the service.

## Security rules

- Identity only from the verified token; never from the request. Raw customer ids are never logged or stored.
- Secrets only via environment; none in the image, repository, logs, health output or error bodies.
- Errors use one contract (`status, error, code, message, path, correlationId, timestamp`); unexpected errors reveal
  nothing about internals, and validation errors never echo input values.
- Container runs as a non-root user. The credential file is mounted read-only and never copied.
- Known, inherited: tokens live in browser `localStorage` and the signing secret is shared between two runtimes.

## Local startup

See `ai-service/README.md`. In short: `docker compose up -d postgres`, start the backend, then
`uv run uvicorn app.main:create_app --factory --port 8090` in `ai-service/`, or
`docker compose --profile ai up -d ai-service`.

## Verification (2026-10-07)

| Suite | Result |
|---|---|
| Unit (`pytest`, hermetic) | 67 passed, 0 failed (at that step; the current counts are in [13](13-e2e.md)) |
| Integration (`-m integration`, real PostgreSQL with pgvector) | 8 passed, 0 failed |
| Live (`-m live`, real Vertex AI) | 4 passed, 0 failed |
| Container | builds; healthy; DB UP, DSports UP; a token issued by the real DSports backend returns 200, a tampered or missing token 401; no token/secret/e-mail/password in the logs |
| Compose profile `ai` | `ai-service` starts and reports `UP` with `database`, `dsports_backend` UP and `llm` CONFIGURED |

## State at the end of the foundation step (historical)

At that point the service had no discovery, RAG, workflows, MCP tools, assistant UI or guardrails. All of those were built afterwards and
are documented in 05–11; the only items from that original list that are still **not** implemented are rate limiting beyond the
in-process per-customer limiter, distributed tracing, and any dashboard/alerting (see [10](10-observability.md)).
