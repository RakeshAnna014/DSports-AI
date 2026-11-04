# ADR-011: Customer Support Resolution (Phase 3): a real case backend in Java, the investigation workflow in the AI service

## Status

Accepted (2026-10-12). Implemented; see `docs/ai/phase3/`.

## Context

Phase 1 answers shopping and order questions. Until Phase 3 the assistant said, truthfully, that DSports had no ticketing API ("I can't create a support ticket").
The brief requires a support capability that investigates a complaint from real records, retrieves policy, recommends a resolution, creates and tracks a REAL case,
escalates honestly, and never fakes a case ID, an investigation or a resolution.

Verified facts that shaped the design (audit of the repository before any code was written):

- There was **no case, ticket or complaint domain** in the Java backend. `UserRole` already contained `SUPPORT_EXECUTIVE`, which no endpoint used.
- The Java backend owns orders, payments, refunds, shipments and returns. The AI service reaches them only through public REST with the customer's own JWT.
- A `UNIQUE` index (`uq_payments_order_open`) means a second successful payment for one order cannot exist, so a true double charge is impossible in this schema.
- The shipment API exposes status and tracking events, but no proof of delivery, no package contents and no carrier integration. Returns have no pickup concept.
- The AI service already has the typed tool registry, MCP facade, single-use confirmation tokens, idempotency, audit, RAG, and the Phase 1 customer agent.

## Decision

1. **The case system of record is a new Java module `support`** (bounded context, same layering as `returns`; migration V44; tables `support_cases`,
   `support_case_messages`, `support_case_events`). The AI service never stores a case; it creates and reads them through the public API with the customer's token.
2. **Identity and ownership are enforced by the backend.** The customer is always the authenticated user. A case that belongs to someone else is answered exactly like one that
   does not exist (`404 CASE_NOT_FOUND`). Staff routes require `SUPPORT_EXECUTIVE` or `ADMIN`, at the security configuration and again by method security.
3. **Escalation is not a status.** `escalation_state` is `NONE`, `REQUESTED` (a person has been asked) or `ACCEPTED` (a support executive actually took the case). Only
   a staff assignment moves `REQUESTED` to `ACCEPTED`. The assistant and the UI say "nobody has taken it yet" until the backend says otherwise.
4. **The backend verifies; the assistant proposes.** At creation the backend snapshots its own view of the order, shipment and payments as `BACKEND_VERIFIED` evidence. What the
   assistant submits is stored as `AI_INVESTIGATION` (a caller can never claim `BACKEND_VERIFIED`). Staff see both, separately labelled.
5. **Models understand, deterministic code decides.** Complaint classification is rules first, a cheap model second. The investigation (parallel, read-only, bounded tool calls)
   and the resolution decision are pure functions of the evidence. The LLM never decides refunds, returns, escalation or case outcomes, and customer text is composed from
   verified facts (nothing a model wrote can enter a case).
6. **Creating a case is a confirmed WRITE** using the existing machinery: a proposal bound to the customer, the tool and the arguments hash; a single-use, expiring token;
   revalidation right before execution; `Idempotency-Key`; audit. "Please raise it" while a proposal is waiting answers that confirmation.
7. **No new write capabilities beyond the customer's own case.** The assistant cannot resolve, close, assign, prioritise, refund, cancel, approve or reverse anything. Requests to
   do so, to see other customers' cases, or to see internal notes are refused by the input guard before any model or tool runs.
8. **One customer assistant, no fourth chatbot.** Support is a subgraph of the existing Customer Agent. The UI renders structured investigation and case components inside the
   existing assistant, plus a customer "Support cases" page and a protected staff console (`/support-cases`).
9. **Internal support documents are separated in SQL.** Document types prefixed `sup_` are never returned to customers (like `inv_` for Phase 2).

## Alternatives considered

- *Case storage in the AI database.* Rejected: ownership, roles, audit and the transactional link to orders belong with the commerce backend, and the AI service must stay replaceable.
- *A third-party helpdesk.* Rejected for the POC: new vendor, credentials and data flow for no gain in learning or verifiability.
- *Let the model decide whether to refund or escalate.* Rejected: forbidden by the brief and not testable.
- *Escalation as a case status.* Rejected: it would let a request look like an acceptance.
- *Auto-resolving cases.* Rejected: there is no backend action that can safely resolve a complaint; "automatic resolution" is therefore reported as 0, with a definition.

## Consequences

- A real, persisted, owner-scoped case lifecycle exists with an audit trail; the assistant's claims about cases can be checked against it.
- Several complaint types end in "a person must review this": the product's value is a verified, evidence-attached case, not an invented answer.
- Image evidence, carrier or warehouse contact, delivery proof, exchanges and live agents do not exist in DSports; the assistant says so and never simulates them.
- Policy and SLA values in the sample documents are demo values (labelled); no official SLA is claimed anywhere.
