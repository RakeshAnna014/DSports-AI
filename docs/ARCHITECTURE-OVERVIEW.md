# DSports-AI architecture overview

One-page map of what exists. Decisions: ADR-009 (Python AI service), ADR-010 (Inventory Intelligence), ADR-011 (support resolution), ADR-012 (profiles and recommendations), ADR-013 (Engineering AI), ADR-014 (Product & Supplier Intelligence), ADR-015 and ADR-016 (planning and AI-assisted analysis) in [adr/](adr/). Per-service detail: [services/](services/README.md).

## 1. Service boundaries and data ownership

```mermaid
flowchart LR
  B[Browser: React 19 + MUI] -->|/api| J[Java backend :8080<br/>Spring WebFlux, R2DBC, Flyway]
  B -->|/ai-api| A[AI service :8090<br/>FastAPI, LangGraph, MCP]
  A -->|public REST + the customer's own JWT| J
  J --- D[(PostgreSQL dsports<br/>commerce, support cases, shopper profiles)]
  A --- V[(PostgreSQL dsports_ai<br/>pgvector chunks, conversations, usage,<br/>inv_* mart, eng_* logs)]
  A --> T[Tika + Tesseract :9998]
  A --> G[Gemini on Vertex AI]
  L[Backend / AI / Postgres logs] -. ingested by CLI .-> A
```

Java is authoritative for catalogue, price, stock, cart, orders, payments, refunds, shipments, returns, support cases, profiles/preferences and all authorization. The AI service owns orchestration, retrieval, explanation, evaluation, usage records, the derived inventory mart and the log store. It never stores or invents a commerce fact; it forwards the caller's token so Java's ownership checks (403/404) are the real enforcement.

## 2. Customer AI request flow

```mermaid
sequenceDiagram
  participant U as Customer
  participant A as AI service
  participant R as Router (rules → cheap LLM)
  participant T as Tool registry
  participant J as Java API
  U->>A: POST /v1/chat (JWT)
  A->>R: classify (Tier 0 rules first; LLM only if unsure)
  R->>T: typed tool call (READ)
  T->>J: GET with the customer's JWT
  J-->>T: authoritative data (or 403/404)
  T-->>A: result (trimmed, untrusted text fenced)
  A-->>U: structured answer; a WRITE returns a proposal + single-use confirmation token
  U->>A: POST /v1/confirm (token bound to user, tool, args hash)
  A->>J: write with Idempotency-Key
```

Writes (return, reorder, support case/message/escalation/reopen) always go through a proposal, an explicit confirmation, an idempotency key and an audit row. ADMIN-only tools (inventory, engineering) are refused by the registry itself for every caller, REST or MCP.

## 3. Recommendation pipeline (deterministic, no model call)

```mermaid
flowchart LR
  S[scenario] --> R[reference product + profile + history] --> C[candidates from live search] --> H{hard constraints:<br/>stock, price, owned, sport/role,<br/>child age rule} --> BB[budget band:<br/>explicit > 20% ceiling > labelled fallbacks] --> SC[score] --> DV[diversity] --> OUT[results + factual reasons]
```

## 4. Inventory Intelligence

Live catalogue + POC reference data + **POC SYNTHETIC** history (`data_source` on every row) → data quality → time-based backtest (last 90 days held out) vs baselines → persisted forecasts, assessments (stock-out risk, replenishment, overstock, events, scenarios) → deterministic tools. The LLM only understands questions and explains numbers; a grounding check rejects any number not present in tool output. ADMIN only; decision support, nothing is executed.

## 5. Engineering AI

```mermaid
flowchart LR
  F1[backend stdout log] & F2[AI JSON log] & F3[docker logs] --> P[parsers] --> RD[redaction at ingest] --> SG[error signature + category] --> ST[(eng_logs)]
  Q[engineer's question] --> U[rule-based filters] --> RP[bounded typed queries] --> ST
  RP --> AN[clusters, trend, trace, deployment timing] --> FH[facts + labelled hypotheses] --> SM[summary]
  SM -. optional .-> LLM[LLM re-words evidence] --> GD{claim guard: citations real,<br/>no unhedged cause} -->|ok| OUT[answer]
  GD -->|fails| DET[deterministic summary]
  SM --> OUT
```

ADMIN only (REST and tools); instruction-like log text is withheld; synthetic records are labelled; denials and investigations are audited without log content.

## 6. Failure handling and observability

* LLM failure: circuit breaker, Tier-0 flows still work, structured `ai_unavailable`; recommendations and Engineering AI summaries are deterministic and unaffected.
* Downstream failure: tools return typed errors; the assistant never reports a write as done unless Java confirmed it.
* Correlation: `X-Correlation-Id` is propagated Java ↔ AI and appears in both logs (the basis of Engineering AI tracing). Usage (tokens, latency, cost *estimate*) is recorded per call; metrics at `/metrics` (AI) and Actuator (Java).
* Not present: central log platform, tracing backend, alerting, frontend log collection (see STATUS.md).

## 7. Authentication and authorization flow

```mermaid
sequenceDiagram
  participant B as Browser
  participant J as Java :8080
  participant A as AI service :8090
  B->>J: POST /api/auth/login (rate limited per address)
  J-->>B: access token (15 min) + refresh token (7 d), HS256, iss=dsports, aud=dsports-api, roles from customer_roles
  B->>J: /api/... Authorization: Bearer (JwtWebFilter verifies signature, exp, iss, aud)
  J->>J: SecurityConfig path rules, then ownership checks inside the use case
  B->>A: /ai-api/... same bearer token (shared secret, issuer and audience)
  A->>A: verify_token, role gate per route and per tool
  A->>J: tool call with the SAME bearer token
  J-->>A: data, or 403/404 from Java's own ownership checks
  B->>J: on 401 the Axios interceptor posts the refresh token once, then replays the request
```

The shared HS256 secret, `localStorage` token storage and 15-minute role lifetime are known risks (R1 to R3 in [security/THREAT-MODEL.md](security/THREAT-MODEL.md)). Roles are never accepted from request headers or bodies.

## 8. Commerce transaction flow (real code path)

```mermaid
flowchart TD
  C["Cart: POST /api/v1/cart/items"] --> K["Checkout: address, delivery, optional promotion, validate"]
  K --> P["PlaceOrderUseCase: ONE transaction<br/>order + items, stock reservations, cart checked out,<br/>checkout completed, outbox NOTIFY_ORDER_PLACED"]
  P --> O[Order PENDING_PAYMENT]
  O --> Pay["Create payment: gateway authorise -> payment AUTHORIZED"]
  Pay --> Cap["CapturePaymentUseCase<br/>1 claim (tx): order awaits payment, payment PROCESSING<br/>2 gateway capture (no tx, timeout, idempotent)<br/>3 complete (tx): payment SUCCESS, order CONFIRMED,<br/>stock fulfilled, cart COMPLETED, outbox NOTIFY_PAYMENT_SUCCEEDED"]
  Cap --> Ob["OutboxDispatcher: create shipment, notifications"]
  Ob --> S["Shipment CREATED -> ... -> DELIVERED, admin-driven status updates"]
  S --> R["Return request -> admin approve/reject; refund request -> admin approve -> gateway refund"]
  O -. unpaid after the window .-> X["ExpireUnpaidOrdersJob: cancel, release stock, void authorisation"]
```

The gateway is `MockPaymentGateway`. Order, payment, shipment and return status machines are in the enums `OrderStatus`, `PaymentStatus`, `ShipmentStatus`, `ReturnStatus`. A paid order can only end through fulfilment or a refund.

## 9. Forecasting and planning flow

```mermaid
flowchart LR
  J["(Java orders + approved returns)"] -->|GET /api/v1/admin/sales/lines, ADMIN token| I["ingest.py: convention net = gross - cancelled - returned"]
  I --> W["(pln_sales_line, pln_sales_week)"]
  D["demo seed: stores, store history"] -.POC_SYNTHETIC.-> W
  W --> F["forecast_run.py + forecast.py: select on origins, evaluate on a held-out origin"]
  F --> FS["(pln_forecast_run, pln_forecast_series, pln_forecast)"]
  FS --> PL["plans: model, manager, target, approved, actual kept separate"]
  PL --> WF["workflow.py: version check, role and scope, audit in the same transaction"]
  PL --> IN["insights.py findings -> optional model wording -> numbers re-checked"]
  FS --> INV["inventory.py: Inventory Intelligence rules -> DRAFT recommendations"]
```

No step writes to the Java database, orders, moves stock, publishes or prices.

## 10. Product discovery and supplier sourcing flow

```mermaid
flowchart LR
  Q["Shopper search with no result"] -->|redacted, hashed| DG["(demand gap)"]
  RUN["POST /v1/sourcing/runs"] --> SF["SafeFetcher: allow-list, public IPs only,<br/>pinned connection, per-hop redirect check, size/type/time caps"]
  SF --> EX["extract, normalise, specs, images"] --> REC["(src_records / opportunities with provenance)"]
  B["Buyer enters supplier + quotation"] --> QU["(src_quotes)"]
  REC & QU --> CALC["calc.py landed cost, margin"] --> SC["scoring.py: score, confidence, supplier risk"]
  SC --> WF2["workflow.py: approver role, stale-version check, decision log"]
  WF2 --> PR["Onboarding proposal for the catalogue team: no publish, no order, no price"]
```

## 11. Timeouts, retries and limits (from code and settings)

| Where | Value |
|---|---|
| Payment gateway call (Java) | `app.payment.gateway-timeout` default 10 s; capture is idempotent and safe to retry |
| Outbox (Java) | poll 1 s, batch 20, lease 5 min, max 8 attempts, `FOR UPDATE SKIP LOCKED` |
| Unpaid order expiry (Java) | check every 60 s |
| Rate limits (Java, in memory) | auth routes 20/min per address; case creation 10/min and case messages 30/min per user |
| AI call to Java | `AI_TIMEOUT_S` default 30 s; no automatic retry |
| Model calls | one retry; circuit breaker opens after `AI_BREAKER_FAILURES` (3) and cools down `AI_BREAKER_COOLDOWN_S` (30 s) |
| One chat request | `AI_REQUEST_TIMEOUT_S` 90 s, `AI_MAX_ITERATIONS` 12, `AI_MAX_TOOL_CALLS` 8, `AI_REQUEST_TOKEN_BUDGET` 8000 |
| Confirmation token | 300 s, single use |
| External research fetch | `SOURCING_TIMEOUT_S` 10 s, size cap `SOURCING_MAX_BYTES`, `SOURCING_RUNS_PER_HOUR` 6 |
| Planning analysis | `PLANNING_ANALYSES_PER_MIN` 6 per person |
