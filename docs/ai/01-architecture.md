# 01 — Customer AI Architecture

> **Status:** implemented and verified against the real stack (see [09-evaluation.md](09-evaluation.md) and [13-e2e.md](13-e2e.md)).
> Decision record: [ADR-009](../adr/ADR-009-python-ai-service.md). Index of this documentation: end of this file.

## Principle

The DSports Java backend remains the **single source of truth** for everything commercial. The AI layer reads that truth through the
same public REST APIs a customer's browser uses; it never owns, duplicates or invents it. An LLM may *explain* a price, a stock level,
an order status or a return decision. It may never *decide* or *produce* one.

## Responsibility split

```text
Java (DSports backend):
  Commerce domain
  Price
  Inventory
  Orders
  Payments
  Refunds
  Shipments
  Returns
  Authentication/authorization

Python (AI service, ai-service/):
  AI orchestration
  Intent understanding
  LangGraph
  RAG
  Embeddings
  MCP
  LLM interaction
  AI-specific state/observability
```

## Architecture

```text
DSports React (existing app, MainLayout mounts the assistant)
      │  /ai-api  (Vite proxy → :8090; same bearer token + refresh as /api)
      ▼
AI service (FastAPI, Python 3.13)
  ├─ guard_in            input guardrails (fixed refusals, no model)
  ├─ router              Tier 0 rules │ Tier 1 gemini-2.5-flash-lite (JSON) │ Tier 2 gemini-2.5-flash
  ├─ LangGraph Customer Agent ── shopping │ orders/returns │ support subgraphs
  ├─ typed tool registry ──────────────► MCP server facade (/mcp/, streamable HTTP, same registry)
  ├─ RAG: Tika(+OCR) → chunks → text-embedding-005 → pgvector + FTS (RRF)
  ├─ confirmation tokens, audit, usage/cost, metrics, circuit breaker
  └─ guard_out           grounding check of every model-written answer
      │ REST + the CUSTOMER's own JWT + X-Correlation-Id
      ▼
DSports Spring backend :8080  (catalog, pricing, inventory, customer, cart, orders, shipments, payments, refunds, returns, reviews)

Postgres (pgvector image) — two databases:  dsports (commerce, Flyway V1–V42)   dsports_ai (derived AI state only)
Tika :9998 (Tesseract OCR)          Vertex AI: Gemini + embeddings
```

* **REST only.** The AI service has no access to `dsports`; the Java backend does not depend on the AI service. If the AI service or
  Gemini is down, the storefront, checkout, payments and order pages are unaffected (verified: scenario M and the browser journey).
* **Authentication.** The AI service validates the customer's bearer token (shared `JWT_SECRET`; HS256/384/512 — the backend picks
  the strength from the secret length) and forwards the same token to DSports, so Spring's ownership checks (403/404) are the real
  authorization. No service credential exists. Tools never take a customer id; the identity is the verified token. Guests are
  supported for product discovery, product knowledge and policy questions only.
* **Writes.** Returns and "add to cart" need an explicit confirmation: a single-use, 5-minute, customer-/tool-/argument-bound token,
  live revalidation, an `Idempotency-Key`, and an audit row. The assistant never places an order or a payment.
* **Data separation.** `dsports_ai` holds embeddings, the product index (descriptive facts only, **no price or stock**), documents/
  chunks, conversations, pending actions, audit and usage. Customers appear only as an HMAC hash.

## Where things are documented

| Doc | Topic |
|---|---|
| [02](02-backend-ai-readiness.md) | Java additions that made this possible (search, attributes, batch lookups, shipment-by-order, Returns, review fix) |
| [03](03-infrastructure.md) | pgvector, `dsports_ai`, Tika, compose, verification record of Phase A |
| [04](04-ai-service.md) | service foundation, JWT, config, Vertex smoke test |
| [05](05-customer-agent.md) | the agent graph, tiers, ranking, state, failure behaviour |
| [06](06-rag.md) | ingestion, OCR, chunking, embeddings, retrieval, citations |
| [07](07-mcp-tools.md) | tool registry and MCP |
| [08](08-security.md) | threat controls |
| [09](09-evaluation.md) | evaluation dataset, thresholds, results |
| [10](10-observability.md) | logs, metrics, usage, cost |
| [11](11-frontend.md) | React integration |
| [12](12-local-development.md) | running and testing |
| [13](13-e2e.md) | end-to-end scenarios A–M and regression results |

## Known limitations

* Access tokens are stored in browser `localStorage` (pre-existing, `docs/baseline/`); the assistant inherits that exposure.
* The catalogue is 50 sample products; V41/V42 enrich it with **sample data** (attributes, descriptions, INR prices). The RAG corpus is
  a **sample** written for this project. Quality on real catalogue/documents is untested.
* Single-instance Phase 1: rate limits and caches are in-process; there is no Redis/Kafka by design.
