# DSports Platform Baseline Audit

**Date:** 2026-10-05
**Scope:** Full codebase (`backend/`, `frontend/`, migrations, docs) as of branch `dsports_Code`, commit `4552785` **plus uncommitted working-tree changes** (review, shipment, wishlist, notification modules are untracked).
**Purpose:** Establish a verified baseline of the existing e-commerce platform before any AI/GenAI work begins. Code is treated as the source of truth; existing documentation is validated against it.

> **Status (2026-10-06): baseline hardened; P0 and P1 remediated and verified. AI readiness NOT yet declared.**
> - **Verified in the running application:** browser journey in real Chrome; 72 API lifecycle and security checks; capture decline/timeout and refund failure scenarios; 500 unit/container tests and 15 integration tests.
> - **What changed:** see [09-remediation-log.md](09-remediation-log.md) (P0) and [10-final-hardening.md](10-final-hardening.md) (payments, reservations, refunds, outbox, security, state machines).
> - **Why AI readiness isn't declared:** the latest instructions were cut off mid-way, so the remaining mandatory gates are unknown. Open items are listed in [10 §9](10-final-hardening.md#9-open-items).
>
> The findings below describe the state **at audit time** and are kept unchanged as the baseline record.

---

## How findings were verified

Every finding carries one of these evidence levels:

| Tag | Meaning |
|---|---|
| **[RUN]** | Reproduced at runtime: built the jar, booted it against a throwaway database, and exercised the HTTP API, or ran the test suite |
| **[CODE]** | Confirmed by reading the implementation (file and line cited) |
| **[DB]** | Confirmed against the live `dsports` database (read-only queries) or migration SQL |
| **[INF]** | Inferred from code structure. Likely, but not reproduced |

Runtime checks used a scratch database (`dsports_audit`, since dropped) and an export of `HEAD` built in a temp directory. The working tree and the `dsports` dev database were not modified.

---

## Executive summary

The codebase has a solid architectural skeleton: modular monolith, ports and adapters per module, rich domain models, optimistic locking, refresh-token rotation with reuse detection, and a centralised error map. **However, the order-to-cash flow has never worked end to end, and the current working tree does not boot.**

### Top 10 findings

| # | Finding | Evidence | Severity |
|---|---|---|---|
| 1 | **Application fails to start**: duplicate bean `orderQueryService` (Review vs Shipment). With overriding forced, Review's port is unsatisfied | [RUN] | P0 |
| 2 | **Flyway duplicate versions**: `V19` (checkout + notification) and `V20` (checkout discount + shipment). Flyway aborts with *"Found more than one migration with version 19"* | [RUN] | P0 |
| 3 | **Every handled error returns HTTP 200**. The real status exists only in the JSON body. Axios therefore treats errors as successes | [RUN] | P0 |
| 4 | **Placing an order always fails (500)**: `OrderItemEntity` has a pre-assigned UUID without `Persistable`, so R2DBC issues an UPDATE instead of an INSERT | [RUN] | P0 |
| 5 | Malformed UUIDs, malformed JSON, bad enums, unknown routes, and **`@PreAuthorize` denials all return `500 INTERNAL_ERROR`** | [RUN] | P0 |
| 6 | **Inventory is never reserved** at order placement (the port named `reserveInventory` only checks availability), so overselling is possible. Availability also ignores `reserved_quantity` | [CODE] | P1 |
| 7 | **Cart is never checked out**: `markCartAsCheckedOut(order.getCheckoutId())` passes a *checkout* ID as a *cart* ID. Separately, the `carts` CHECK constraint forbids `COMPLETED` | [CODE] [DB] | P1 |
| 8 | **Customers can refund their own successful payments at any time**, with no return, order-state, or admin linkage. `REFUNDED` is persisted even when the gateway refund fails | [CODE] | P1 |
| 9 | **Frontend never calls `/payments/{id}/capture`**, so payments stop at `AUTHORIZED` and orders are never confirmed through the UI | [CODE] | P1 |
| 10 | **4 failing unit tests** and **5 frontend TypeScript errors** (`npm run build` fails). Billing, Returns, Franchise, and Admin are empty modules | [RUN] | P0/P1 |

### Live dev database state [DB]

- Flyway is at **V22**. `V23` (payment `PROCESSING` status), `V24` (reviews), `V25` (wishlist), and the clashing notification/shipment migrations have **never been applied**.
- Data: **0 orders, 0 payments**, 15 checkouts (7 PENDING, 2 VALIDATED, 6 CANCELLED), 1 active cart. The purchase flow has never completed against a real database.

---

## Document index

| Doc | Content |
|---|---|
| [01-capability-status.md](01-capability-status.md) | Capability status matrix (implemented / partial / mocked / missing) and requirement traceability |
| [02-api-and-security.md](02-api-and-security.md) | Full endpoint inventory, authz matrix, request/response validation gaps, security findings |
| [03-business-rules.md](03-business-rules.md) | Workflow-by-workflow validation: happy path, negative paths, concurrency, idempotency, partial failure |
| [04-database.md](04-database.md) | Schema, migrations, constraints, indexes, entity-to-table map, drift |
| [05-reactive-errors-observability.md](05-reactive-errors-observability.md) | Reactive pipeline issues, the standard error contract (proposed), observability baseline |
| [06-testing.md](06-testing.md) | Test inventory, results, coverage gaps |
| [07-documentation-discrepancies.md](07-documentation-discrepancies.md) | Where `architecture.md`, `ROADMAP.md`, and the ADRs disagree with the code |
| [08-gap-backlog.md](08-gap-backlog.md) | Prioritised implementation backlog (P0–P3) with the recommended order of work |
| [09-remediation-log.md](09-remediation-log.md) | What was fixed, root causes, decisions, verification evidence, final cart state machine, still-open items |
| [10-final-hardening.md](10-final-hardening.md) | Gate status, all state machines, transaction and outbox design, idempotency and security matrices, verification evidence, open items |

---

## Readiness for the AI layer

The AI roadmap (product search, shopping assistant, support agent) depends on three things that are not yet true:

1. **A reliable order/payment/shipment state machine.** A support agent answering "where is my order?" needs order state that is correct.
2. **A consistent error contract with correct HTTP status codes.** Agents and tool-calling layers branch on status codes.
3. **Correlation IDs flowing through logs and responses.** These are needed to trace an AI interaction across modules.

**Recommendation:** complete backlog items P0 (all) and P1 (order-to-cash, security) before starting AI Phase 1.
