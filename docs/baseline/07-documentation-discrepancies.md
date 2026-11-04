# 07 — Documentation vs Implementation Discrepancies

The code is the source of truth. Each row says what the document claims and what the code actually does. The "Action" column says whether the **doc** should change (the code is right) or the **code** should change (the doc states a requirement that still holds). Items marked *Decide* need an owner decision.

## `ROADMAP.md`

| Claim | Reality | Action |
|---|---|---|
| "Current Sprint: Sprint 0, Story 0.3 Infrastructure" and Sprints 1–4 "Pending" | Identity, catalog, pricing, inventory, cart, checkout, order, payment, review, shipment, notification, and wishlist all exist (to varying degrees) | **Doc**: update the progress table from [01-capability-status](01-capability-status.md) |
| Sprint 3 includes "Billing, Invoice" | The billing module is empty | Doc: mark as not started |
| Sprint 4: "Franchise, Admin Dashboard, Reports, Returns" | All empty. Reviews, shipments, wishlist, and notifications were built instead (not on the roadmap) | *Decide*: re-baseline the roadmap. Reviews and wishlist need roadmap entries ("every new feature must first be added to the roadmap") |
| Definition of Done: "Unit tests pass, OpenAPI updated, reviewed, merged" | 4 failing tests. Four modules are untracked or uncommitted | Code / process |

## `docs/architecture.md`

| § | Claim | Reality | Action |
|---|---|---|---|
| 4.1 | Catalog has **variants**. Inventory tracks variants | No variants anywhere. Product-level only | *Decide*: variants are core for cricket gear (bat size, glove hand, pad size). Keep them as a requirement (Code) or defer (Doc) |
| 4.1 | Order has `OrderAddress`, `OrderStatusHistory` | Address is a JSON/text snapshot on `orders`. **No status history table** | Code (status history is needed for support and AI) or Doc |
| 4.1 | Payment entity `PaymentTransaction` | `Payment` + `payment_audit` | Doc |
| 4.1 | Notification = "Email dispatch", owns no data | In-app plus mock email/SMS/push, and it owns `notifications` and `notification_templates` tables | Doc |
| 4.1 | Modules billing, returns, franchise, admin | Empty modules | Doc: mark them as placeholders |
| 4.1 | No review, shipment, or wishlist modules | Implemented (untracked) | Doc: add them. ADR-008 covers reviews only |
| 5 | "Fail-fast for externals — timeouts" | No timeouts on any gateway call | Code |
| 7.2 | ER diagram with ProductVariant, OrderAddress, OrderStatusHistory, Invoice, ReturnRequest, Refund, FranchiseStore | None of these exist | Doc: replace with the as-built ER map in [04-database](04-database.md) |
| 8 | "Versioned via URL prefix `/api/v1/...`" | Mixed `/api/` and `/api/v1/` | Code: migrate the identity, catalog, inventory, and pricing paths (keep aliases during the transition) |
| 8 | Standard success envelope `{status, data, meta, errors}` | Not used. Raw DTOs and `Flux` arrays are returned | *Decide*: recommend **Doc** (adopt raw DTOs plus a `Page<T>` wrapper for lists only). An envelope adds churn for no benefit |
| 8 | Error response `{status:"error", errors:[…]}` | `ApiError` (different shape), and HTTP status is always 200 | Doc: adopt the [proposed `ApiError` contract](05-reactive-errors-observability.md#proposed-standard-error-contract-for-review). Code: fix the statuses |
| 8 | Cursor-based pagination for catalog and order history | Offset pagination on catalog only. Order history is unpaginated | Code (paging) + Doc (offset is acceptable for now) |
| 8 | **Idempotency key on order creation** | Not implemented | Code |
| 9.2 | JWT signed with **RS256** | HS256 shared secret | *Decide*: HS256 is acceptable for a monolith. Update the doc, or switch if tokens will be validated by other services (AI services may be) |
| 9.3 | Authorization matrix with Franchise and Warehouse roles | The roles exist in the `UserRole` enum, but no endpoint uses them | Doc |
| 13/14 | Health endpoint `GET /api/v1/health`, auth at `/api/v1/auth/*` | `/actuator/health`, `/api/auth/*` | Doc |
| 16 | Rate limiting (Sprint 7) | None | Backlog |
| 18 | **ADR-005 "Guest Cart → User Cart Merge"** (accepted) | No guest cart. Every cart endpoint requires authentication | *Decide*: implement it or mark the ADR superseded |
| 18 | ADR-004 "Payment provider abstraction, mock first" | Implemented as `PaymentGateway` port + `MockPaymentGateway` ✅ | — |

## `docs/identity-domain-design.md`

Not reviewed line by line (2,153 lines). Spot checks match the code: refresh-token hashing, rotation, reuse detection, and lockout. Any OAuth sections describe stubs only.

## ADRs

| Item | Finding |
|---|---|
| `docs/adr/` | Contains only **ADR-008** (reviews). ADRs 001–007 live inline in `architecture.md` §18 |
| Missing ADRs for decisions already in code | Per-module Flyway migrations on a shared classpath and the version numbering policy. HS256 JWT. Spring application events for cross-module integration (vs outbox). Cross-module ports wired in `bootstrap` configs. Mock payment and courier gateways. Address-snapshot-as-text on orders |
| Recommendation | Move ADR-001…007 into `docs/adr/` as individual files and add ADRs for the decisions above |

## Module READMEs (`AGENTS.md`: "Every module → README")

Only `backend/review/README.md` exists. The other 11 implemented modules have none.

## PR summaries

`docs/pr*-summary.md` and `backend/docs/pr*-summary.md` are split across two folders, and PR 19, 20, and 23 summaries describe features (notifications, shipments, wishlist) that are uncommitted. Recommendation: consolidate into a single `docs/changelog/` (or rely on PR descriptions) and treat these summaries as historical, not as specs.
