# 08 — Gap Analysis & Implementation Backlog

Priorities:
- **P0**: blocks build, boot, or core flow; fix first.
- **P1**: correctness or security of order-to-cash; required before the AI layer.
- **P2**: important quality and contract work.
- **P3**: hygiene.

Effort: **S** (≤½ day), **M** (1–2 days), **L** (3+ days).

Per `AGENTS.md`, each item is implemented as **one user story per review cycle**. Items that touch the same files are grouped so commits stay small and compilable.

> **Progress (2026-10-06):** ✅ marks items fixed **and verified at runtime**. See [09](09-remediation-log.md) (P0) and [10](10-final-hardening.md) (P1, security). Still open: P0-7 (commit or decide on the untracked work), S-08 (single-flight token refresh), S-09 (admin provisioning), and the items in [10 §9](10-final-hardening.md#9-open-items).
>
> **Notes on partial items (◐):**
> - **P1-5:** cart and checkout stock checks now use sellable stock (on hand − reserved) and the cumulative cart quantity, and placement reserves atomically. **Not done:** re-pricing at checkout validation.
> - **S-02/03:** `@PreAuthorize` denials return 403, and `/api/v1/admin/**` now has a path rule. **Not done:** some inline `isAdmin()` checks remain in review, shipment and notification controllers (they work; verified 403).
> - **P2-4:** done: correlation ID in MDC, business and outbox metrics. **Not done:** structured JSON logs and tracing.

## P0 — Make the platform build, boot, and place an order

| ID | Item | Area | Effort | Refs |
|---|---|---|---|---|
| P0-1 ✅ | Rename the colliding `orderQueryService` beans (Review vs Shipment) to module-specific names, for example `reviewOrderQueryService` and `shipmentOrderQueryService`. Add a `@SpringBootTest` context smoke test *(remaining: the smoke test is not written yet; boot was verified at runtime instead)* | bootstrap, review, shipment | S | [01](01-capability-status.md) |
| P0-2 ✅ | Renumber `V19__create_notification_tables` → `V26` and `V20__create_shipment_tables` → `V27` (safe: the dev DB is at V22). Adopt a numbering policy (ADR) and a CI duplicate-version check. Remove `validate-on-migrate: false` and the stray `Untitled-1.pgsql` *(done: renumbering + classpath duplicate-version test. Remaining: ADR, removing `validate-on-migrate: false`, deleting the stray file)* | db | S | [04 §1](04-database.md) |
| P0-3 ✅ | Fix `OrderItemEntity` INSERT (implement `Persistable` as was done for `CheckoutItemEntity`). Audit every child entity with app-assigned IDs (shipment tracking, wishlist items, review votes, notification) | order (+ others) | S | [03](03-business-rules.md) |
| P0-4 ✅ | Global exception handler returns `ResponseEntity<ApiError>` with the correct HTTP status. Add handlers for `ServerWebInputException`, `ResponseStatusException`, `AccessDeniedException`, `DataIntegrityViolationException`, `OptimisticLockingFailureException`. Return ApiError from the 401 entry point and a new 403 handler. Echo the generated correlationId. Add WebTestClient tests per category | bootstrap | M | [05 §2](05-reactive-errors-observability.md) |
| P0-5 ✅ | Fix the 4 failing unit tests (decide the intended `stockOut` event contract; update the stale `ValidateCheckoutUseCaseTest`) | inventory, order | S | [06](06-testing.md) |
| P0-6 ✅ | Fix the 5 frontend `tsc` errors so `npm run build` passes | frontend | S | [06](06-testing.md) |
| P0-7 | Commit or decide on the untracked modules (review, shipment, wishlist, notification src) and the uncommitted bootstrap and inventory changes. Today the branch's committed state and the working tree differ substantially | repo | S | [README](README.md) |
| P0-8 ✅ | Add `carts` CHECK value `COMPLETED` (new migration) | cart db | S | [04 D-01](04-database.md) |

**Exit criterion for P0:** the app boots on an empty DB, all tests pass, `npm run build` passes, and a manual order can be placed.

## P1 — Order-to-cash correctness

| ID | Item | Effort | Refs |
|---|---|---|---|
| P1-1 ✅ | Pass the **cart ID** (`checkoutData.cartId()`) to `markCartAsCheckedOut`, not the checkout ID | S | [03 Cart](03-business-rules.md#cart) |
| P1-2 ✅ | **Real inventory reservation** at order placement (reserve per item with optimistic locking, release on cancel, payment failure, or expiry). Compute availability as `available − reserved` in every port | M | [03 Order](03-business-rules.md#order) |
| P1-3 ✅ | Make `PlaceOrderUseCase` transactional (a use-case-level `TransactionalOperator`). Order number from a DB sequence. `UNIQUE(orders.checkout_id)`. Optional `Idempotency-Key` header | M | [03](03-business-rules.md#order), [04 D-02](04-database.md) |
| P1-4 ✅ | Reject expired checkouts at order placement. Move the checkout to a terminal `COMPLETED` state after the order | S | [03 Checkout](03-business-rules.md#checkout) |
| P1-5 ◐ | Price integrity: re-price at checkout validation from the active, effective, currency-matched price, and surface price changes to the UI. Fix cart stock checks to use the cumulative quantity | M | [03 Cart](03-business-rules.md#cart) |
| P1-6 ✅ | Payment: one non-terminal payment per order (partial unique index + check). Block payment for CANCELLED or REFUNDED orders. Capture verifies the order is still PENDING_PAYMENT | M | [03 Payment](03-business-rules.md#payment), [04 D-03](04-database.md) |
| P1-7 ✅ | Wire the **frontend payment flow to capture** (or make the mock gateway authorise and capture in one step and document it) | S | [01](01-capability-status.md) |
| P1-8 ✅ | Rework post-payment processing: transactional outbox (or `AFTER_COMMIT` listener) with retry. No swallowed errors. Deterministic warehouse allocation. Shipment auto-create uses the order address snapshot | L | [05 R-01](05-reactive-errors-observability.md) |
| P1-9 ✅ | Order cancel releases reservations and voids or refunds the payment through a domain event | M | [03 Order](03-business-rules.md#order) |
| P1-10 ✅ | Capture recipient name and phone on addresses and populate the order snapshot | S | [03 Order](03-business-rules.md#order) |

| P1-11 ✅ | **New (N-5):** `OrderR2dbcRepositoryAdapter.save()` overwrites the entity version with the DB version before saving, which defeats optimistic locking on orders | S | [09 §5](09-remediation-log.md#5-still-open-not-in-this-p0-scope) |
| P1-12 ✅ | **New (N-6):** Review `userPurchasedProduct` adapter treats `userId` as an order ID and always returns false, so **review creation always fails** | S | [09 §5](09-remediation-log.md#5-still-open-not-in-this-p0-scope) |

## P1 — Security

| ID | Item | Effort | Refs |
|---|---|---|---|
| S-01 ✅ | **Remove customer self-refund.** Refund becomes admin-only (or return-driven later). Mark REFUNDED only after the gateway succeeds, otherwise use a `REFUND_FAILED` / retry state | S | [02 S-01](02-api-and-security.md#4-security-findings) |
| S-02/03 ◐ | Consolidate admin authz: put all admin routes under `/api/v1/admin/**` with one path rule plus `@PreAuthorize`. Remove inline `isAdmin()` checks | M | [02](02-api-and-security.md) |
| S-04 ✅ | Tracking endpoint: either public with a redacted response (status and events only), or restricted to the owner or an admin | S | [02](02-api-and-security.md) |
| S-05 ✅ | Public APIs return only sellable data: ACTIVE products, current effective prices, and a stock-status flag instead of raw inventory rows | S | [02](02-api-and-security.md) |
| S-06 ✅ | Never put exception or SQL messages in API responses | S | [02](02-api-and-security.md) |
| S-08 | SPA single-flight token refresh (queue requests while a refresh is in flight) | S | [02](02-api-and-security.md) |
| S-09 | Admin provisioning: a bootstrap admin from environment variables on first start, or a SUPER_ADMIN-only role-grant API with an audit log | M | [02](02-api-and-security.md) |
| R-02 ✅ | Move BCrypt off the event loop | S | [05](05-reactive-errors-observability.md) |

## P2 — Contracts, validation, observability, tests

| ID | Item | Effort |
|---|---|---|
| P2-1 | Unify API paths under `/api/v1` (keep deprecated aliases for one release) | M |
| P2-2 | `@Valid` plus constraints on shipment, review, and address DTOs. Enum binding returns 400. Separate the login API DTO from `LoginUserCommand` | S |
| P2-3 | Paginate every list endpoint and return a `Page<T>{content,page,size,totalElements}` | M |
| P2-4 | Correlation ID → MDC (context-propagation `ThreadLocalAccessor`). Structured JSON logs. Micrometer tracing (OTel bridge). Prometheus plus business counters | M |
| P2-5 ✅ | Timeouts (and bounded retries where idempotent) on every gateway port | S |
| P2-6 | DB constraints D-04…D-10 and timestamp/money type standardisation | M |
| P2-7 | Test baseline: Testcontainers setup that fails loudly, repository integration tests, the order-to-cash API test, concurrency tests, a CI workflow (backend + frontend) | L |
| P2-8 ✅ | Reviews readable anonymously (`GET /api/v1/reviews/product/**` permitAll, APPROVED only) | S |
| P2-9 | Make tax and delivery charges configuration-driven (not hard-coded in the domain). Document the GST assumptions | M |
| P2-10 | Frontend: route guards and an admin role gate. Currency formatting defaults to INR (`formatPrice` defaults to USD). Handle the `ApiError.code` field | M |

## P3 — Documentation & hygiene

| ID | Item | Effort |
|---|---|---|
| P3-1 | Update `ROADMAP.md` progress and `architecture.md` per [07](07-documentation-discrepancies.md). Move ADR-001…007 into `docs/adr/`. Add ADRs for the decisions listed in 07 | M |
| P3-2 | One `README.md` per implemented module (purpose, endpoints, tables, events published and consumed) | M |
| P3-3 | Consolidate the PR summaries. Delete the empty `database/` folders or use them | S |
| P3-4 | Rate limiting on the auth endpoints | M |
| P3-5 | Root `README.md` (currently `# DSports-AI -- Hi`): local setup, profiles, env vars, how to run tests | S |

## Not yet started (scope decisions needed)

These are **Missing** capabilities from the target domain. They are listed for planning, not as defects:

| Capability | Note |
|---|---|
| Returns and refunds workflow (eligibility, window, partial, RMA) | Module empty. Refunds should hang off returns (S-01) |
| Billing / invoices (GST invoice, numbering, PDF) | Module empty |
| Franchise / store inventory | Module empty. Inventory has `warehouse_id` but no store model |
| Admin UI and reporting | No admin frontend at all |
| Product variants and attributes | *Decide* (see [07](07-documentation-discrepancies.md)) |
| Offers / discounts / coupons | `discount_amount` columns only |
| Keyword product search | Prerequisite for AI Phase 1 (semantic search should layer on top of a working lexical baseline) |
| Guest cart and merge | ADR-005 |

## Recommended sequence

1. **P0-7 → P0-2 → P0-1 → P0-3 → P0-8 → P0-5 → P0-6** (boot and build green), then **P0-4** (error contract).
2. **P1-1, P1-4, P1-7, S-01** (small, high-impact correctness and security fixes).
3. **P1-2 + P1-3 + P1-6** (reservation, atomic order, payment uniqueness: one story each).
4. **P1-8 + P1-9** (reliable post-payment and cancel flows) and **P2-7's order-to-cash API test** as the regression net.
5. **P2-4** (observability baseline), then the remaining P2 and P3 items in parallel with Returns and Billing scoping.
6. **AI Phase 1 starts** once steps 1–4 are done and the order-to-cash API test is green in CI.
