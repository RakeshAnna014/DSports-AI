# 1. Support architecture

```
React: assistant (investigation + case cards)   /support (my cases)   /support-cases (staff console)
        |  /ai-api/v1/chat, /v1/confirm                |  /api/v1/support/...             |  /api/v1/support/staff/...
        v                                              v                                  v
Python AI service (FastAPI)                      Java backend :8080 (Spring WebFlux, R2DBC, PostgreSQL)
  Customer Agent (LangGraph)                       module `support`  <-- ports --> orders, shipments, payments, returns (owner-scoped)
   guard_in -> route (Tier 0 rules | Tier 1 model)
   support subgraph:
     complaint -> identify order/product -> investigate (READ tools, parallel, bounded)
              -> decide (pure function) -> respond -> propose create_support_case -> [customer confirms] -> backend creates the case
     case_status | case_message | case_escalate | case_reopen | case_create
   guard_out -> structured response (`support` payload)
  Typed tool registry (+ MCP)      RAG: Tika -> pgvector (procedures; internal sup_* separated)
```

## Responsibilities

| Concern | Java (authoritative) | Python AI service |
|---|---|---|
| Orders, payments, refunds, shipments, returns | owns and decides | reads through public REST with the customer's JWT |
| Support cases | persists, authorises, audits, validates the lifecycle, counts analytics | creates/reads them through REST; never stores a case |
| Language understanding | none | rules first, then the cheap model for intent and complaint type |
| Investigation and resolution decision | none | deterministic code over tool results |
| Policy | none | RAG over cited procedures |
| Authorisation | `SUPPORT_EXECUTIVE`/`ADMIN` on staff routes; ownership on every customer route | input guard, role gates in the registry; never relied on for safety |

## One request, end to end

1. `guard_in` stops injection, other customers' data, internal information and staff-only actions before any model or tool (deterministic refusals).
2. The router classifies the message: Tier 0 rules (zero tokens) for the brief's phrasings and the case verbs, the Tier-1 model otherwise. The model proposes an intent and a
   complaint type; the type is validated against the enum.
3. The workflow resolves the order and product from the customer's own orders (or asks, with server-owned tappable choices), then gathers evidence with parallel READ tools
   within the per-request tool budget. A lookup that fails is recorded as unverified, never guessed.
4. `decide` returns an outcome: answer, ask for a detail, safe next step, create a case, escalate (case plus a review request), or "could not verify". The response lists
   verified facts, possibilities and what could not be verified.
5. For a case, the service proposes `create_support_case`. Nothing exists until the customer confirms; then the backend creates the case, snapshots its own evidence and
   returns the number, status, priority and escalation state, which are what the customer sees.

## What was reused

The typed tool registry, MCP facade (WRITE tools only through `propose_action`/`confirm_action`), confirmation service, idempotency, audit table, retrieval, Tika ingestion,
conversation state, server-owned offers (`choiceId`), the grounding guard, the React chat components and the evaluation approach from Phase 1, and the ADMIN-style
internal-document separation from Phase 2. No new service, database, LLM provider or vector store was introduced.
