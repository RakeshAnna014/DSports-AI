# 10 — Final Baseline Hardening

**Date:** 2026-10-06
**Scope:** every remaining correctness, security and reliability item after [09](09-remediation-log.md): payment capture in the UI, inventory reservation, refund authorisation, transaction boundaries, post-payment reliability, review creation, optimistic locking, the shipment address, plus a full security, state-machine, idempotency, reactive, database, observability, frontend and API-contract sweep.
**Rule applied:** an item is marked done only after it was verified against the running application (browser and/or API) and the database.

## 1. Gate status

| # | Gate | Status | Evidence |
|---|---|---|---|
| 2 | UI payment completes authorise → capture → order CONFIRMED | ✅ | Real Chrome journey ([§8.1](#81-browser-real-chrome-via-playwright)): success page shows CONFIRMED. DB: payment SUCCESS, order CONFIRMED, cart COMPLETED, stock fulfilled, shipment created, 3 notifications |
| 2 | UI handles capture decline, timeout/retry, duplicate capture, already paid, errors, loading | ✅ | Browser: decline → failure page → cancel. Timeout → "Retry confirmation" with no false success. API: duplicate capture returns the same SUCCESS |
| 3 | Inventory reservation, no overselling under concurrency | ✅ | Testcontainers: 10 customers racing for 1 unit → exactly 1 reservation. 20 for 5 → never more than 5. DB CHECK blocks bypass |
| 4 | Refund authorisation and safe refund lifecycle | ✅ | Customers can only *request*. Admin approves. A gateway decline leaves the payment untouched. Retry completes. Idempotency key enforced |
| 5 | Transaction boundaries | ✅ | Placement, cancellation, capture completion, refund outcome and shipment progression each run in one local transaction |
| 6 | Reliable post-payment processing | ✅ | Synchronous local completion plus a transactional outbox (retry, backoff, FAILED state, metrics, admin view). Fire-and-forget listeners removed |
| 7 | Review creation | ✅ | Adapter fixed and verified-purchase rules enforced. 6 negative cases verified live |
| 8 | Optimistic locking | ✅ | Order, payment and checkout bypasses removed. Testcontainers: a stale writer gets 409 instead of overwriting |
| 9 | Shipment address from the order snapshot | ✅ | Live: shipment street = order snapshot ("1 MG Road, Flat 4"; the UI-entered "42 Stadium Road") |
| 10 | Security review | ✅ (open items in [§9](#9-open-items)) | 19 authorisation/IDOR checks pass live. Data leaks fixed |
| 11 | State machines documented and enforced | ✅ | [§2](#2-state-machines) |
| 12 | Idempotency | ✅ | [§4](#4-idempotency) |
| 13 | Reactive review | ✅ | [§6](#6-reactive-review) |
| 14–15 | Database and Flyway safety | ✅ | V32–V38 constraints. Build fails on duplicate, misnamed or destructive migrations. Checksum validation re-enabled locally |
| 16 | Error handling | ✅ | 0 handled errors as HTTP 200. New codes mapped. Live probes pass |
| 17 | Observability | ✅ | Correlation ID in every log line (verified), business metrics, outbox metrics, no secrets in logs |
| 18 | Frontend audit | ✅ (lint open) | `tsc` 0 errors. Production build OK. No console errors or 5xx in browser runs. ESLint has no config (pre-existing) |
| 19 | API contract | ✅ | All 61 frontend calls exist in the live OpenAPI (117 operations). Contracts fixed at the source |
| 20 | Tests | ✅ | 500 unit/container + 15 integration (Testcontainers) + 72 API E2E + 3 browser scenarios, all passing |
| — | **AI readiness** | ⚠️ **Not declared** | The instructions for this phase were cut off in section 20, so any later mandatory gates are unknown. See [§10](#10-ai-readiness) |

## 2. State machines

Every lifecycle is enforced in its aggregate. Invalid transitions return **409** (`INVALID_STATUS_TRANSITION` / `INVALID_REFUND_STATUS` / `INVALID_RESERVATION_TRANSITION`). Clients can never set a payment- or system-controlled state directly.

**Cart:** `ACTIVE → CHECKED_OUT` (order placed) `→ COMPLETED` (payment captured). `ACTIVE → ABANDONED`. Items can only change while ACTIVE.

**Checkout:** `PENDING → VALIDATED (→ READY_FOR_PAYMENT) → COMPLETED` (an order was placed from it; new). `PENDING|VALIDATED → EXPIRED|CANCELLED`. A COMPLETED checkout is no longer "active", so a customer can start a new checkout immediately.

**Order:**
```
CREATED → PENDING_PAYMENT ──capture──► CONFIRMED → PROCESSING → SHIPPED → DELIVERED
   │            │                         │            │           │          │
   └──cancel────┴──► CANCELLED            └────────────┴───────────┴──────────┴──► REFUNDED (full refund)
```
- Cancellation is allowed **only before payment** (customer, admin, or expiry after `app.orders.payment-window`, default 30 min). It releases stock and voids open payments.
- Paid orders end through fulfilment or a refund.
- Admins can set only PROCESSING / SHIPPED / DELIVERED / CANCELLED. Each goes through the domain method (events, rules), so the old `updateStatus` bypass is gone.
- Shipment progress drives the order: PICKED_UP / IN_TRANSIT / OUT_FOR_DELIVERY → SHIPPED, and DELIVERED → DELIVERED.

**Payment** (`SUCCESS` = captured):
```
CREATED → PENDING → PROCESSING → AUTHORIZED ─beginCapture→ PROCESSING → SUCCESS → PARTIALLY_REFUNDED → REFUNDED
                        └→ FAILED           ├→ CANCELLED (void)   └→ FAILED (capture declined)   └──────→ REFUNDED
```
- A persisted `PROCESSING` means a gateway call is in flight or its outcome is unknown. It blocks order cancellation (409 `PAYMENT_IN_PROGRESS`) and can be retried.
- At most one live payment per order (DB partial unique index). FAILED/CANCELLED attempts allow a new one.

**Refund:** `REQUESTED → PROCESSING → COMPLETED`. `PROCESSING → FAILED → PROCESSING` (retry). `REQUESTED → REJECTED`. The payment's `refunded_amount` changes **only** on COMPLETED.

**Inventory reservation** (new `inventory_reservations`, one row per order line):

| Event | Reservation | `available_quantity` (on hand) | `reserved_quantity` |
|---|---|---|---|
| Order placed | → RESERVED | — | + qty |
| Payment captured | RESERVED → FULFILLED | − qty | − qty |
| Order cancelled / expired | RESERVED → RELEASED | — | − qty |

Sellable = on hand − reserved. DB CHECKs: `available ≥ 0`, `reserved ≥ 0`, `reserved ≤ available`. Concurrent writers are serialised by the row's `@Version` (stale UPDATE → bounded retry against fresh stock).

**Shipment:** `CREATED → READY_FOR_PICKUP → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED`, with `FAILED → READY_FOR_PICKUP` and `PICKED_UP|IN_TRANSIT → RETURNED`. Created only for paid orders, one per order (unique index), addressed to the order snapshot.

**Outbox message:** `PENDING → PROCESSING (leased) → DONE`, or `→ PENDING` (retry with backoff) `→ FAILED` after `app.outbox.max-attempts` (default 8).

## 3. Transaction boundaries and reliability

| Operation | One local transaction | Outside the transaction | Recorded in the outbox (delivered asynchronously) |
|---|---|---|---|
| Place order | order + items, stock reservation (all lines), cart → CHECKED_OUT, checkout → COMPLETED | — | order-placed notification |
| Authorise payment | payment row | gateway authorisation (timeout) | payment-failed notification, void of an orphan authorisation |
| Capture | ① claim: payment → PROCESSING ③ payment SUCCESS + order CONFIRMED + reservations FULFILLED + cart COMPLETED | ② gateway capture (timeout, idempotent) | shipment creation, order-confirmed and payment-success notifications |
| Cancel unpaid order | order CANCELLED, reservations RELEASED, open payments CANCELLED | — | gateway void, order-cancelled notification |
| Refund | ① claim: refund → PROCESSING ③ refund COMPLETED + payment refunded amount (+ order REFUNDED) | ② gateway refund (timeout, idempotent on refund ID) | refund-completed notification |
| Shipment status | shipment + tracking + order progression | courier booking (on creation, timeout) | order shipped/delivered notifications |

Why this is safe:
- **No partial local state.** Each local step commits atomically.
- **No external call holds a DB transaction open.**
- **Crash-safe gateway outcomes.** A crash after the gateway succeeded leaves a claimed (PROCESSING) record that is completed by retrying, and the gateway returns the original result.
- **Follow-up work is never lost.** It commits with the business change and is retried until DONE or visibly FAILED (`GET /api/v1/admin/outbox`, `POST /api/v1/admin/outbox/{id}/retry`, metric `dsports.outbox.messages{result=failed}`).
- **Events after commit.** Domain events are published only after commit.

## 4. Idempotency

| Operation | Mechanism | Duplicate result |
|---|---|---|
| Place order | `existsByCheckoutId` + **UNIQUE(orders.checkout_id)** | 409 `DUPLICATE_ORDER` |
| Create checkout | completed checkouts are no longer active; one active per customer | 409 `ACTIVE_CHECKOUT_EXISTS` |
| Stock reservation | UNIQUE(order_id, product_id); same quantity → no-op | no second reservation (unit + DB tests) |
| Create payment | returns the open attempt + **partial UNIQUE(payments.order_id)** | same payment returned; a concurrent loser gets 409 and its authorisation is voided |
| Capture | captured payment returned unchanged; gateway idempotent per authorisation | 200 with the same SUCCESS payment |
| Refund request | **`Idempotency-Key` header**, UNIQUE(payment_id, idempotency_key) | same refund returned |
| Refund payout | refund ID is the gateway idempotency key; version-checked claim | completed refund cannot be processed again (409) |
| Shipment | returns the existing shipment; UNIQUE(shipments.order_id) | same shipment |
| Notification | UNIQUE(user_id, type, reference_id) partial index; existence check | no duplicate notification |
| Review | existence check + **UNIQUE(product_id, user_id)** | 409 `DUPLICATE_REVIEW` |
| Outbox enqueue | UNIQUE(event_type, aggregate_id), `ON CONFLICT DO NOTHING` | enqueued once |

## 5. Security review

**Verified live** (`e2e/api-e2e.sh`): customer B gets 403/404 on customer A's order (read, cancel), payment (read, pay, capture), refunds (list, request), shipment (read, track). Customers get 403 on admin order, refund and outbox APIs, refund approval, and shipment creation. A missing or forged JWT gets 401.

| Fixed in this phase | Before | After |
|---|---|---|
| Self-refund | Any customer could refund their own captured payment instantly | Customers create a refund **request** only. Payout is an admin action |
| Refund on gateway failure | Payment marked REFUNDED even if the gateway refused | Refund FAILED, payment untouched, retry later |
| Shipment tracking | Any logged-in user could read any shipment (incl. address) by tracking number | Owner or admin only. Others get 404 |
| Customer-created shipments with arbitrary address | Allowed | Admin/system only; address from the order snapshot |
| Public inventory | Warehouse IDs, reserved quantities, reorder levels; on-hand shown as "available" | One summary per product with **sellable** quantity and status |
| Public prices | DRAFT/SCHEDULED/ARCHIVED prices visible | ACTIVE only |
| Public product detail | ARCHIVED products returned | 404 |
| Public reviews | PENDING/REJECTED/HIDDEN reviews listed (and only to logged-in users) | APPROVED only, public |
| Client-trusted capture data | Capture body carried `transactionId`/`gatewayReference` | No body; server-side data only. `gatewayReference` removed from responses |
| Client-trusted payment amount in UI | Amount taken from the URL | Taken from the order fetched from the API (the server also validates) |
| Cart stock check | Checked only the added increment against on-hand stock | Checks quantity in cart + increment against sellable stock (`AddToCartUseCaseTest`) |
| Admin path rule | Only `/api/admin/**` | Also `/api/v1/admin/**` (defence in depth next to `@PreAuthorize`) |
| Correlation-ID log injection | Any header value logged | Only `[A-Za-z0-9._-]{1,64}`; otherwise replaced |
| BCrypt on event loop | Blocking CPU on Netty threads | `boundedElastic` scheduler |

Never trusted from the client: price (server price), discount (none), payment status, order status (only fulfilment states, admin-only, via domain rules), inventory quantity, customer ID (always from the JWT).

## 6. Reactive review

| Location | Pattern | Verdict |
|---|---|---|
| `PaymentSucceededEventHandler`, `DomainEventNotificationHandler` (8), `OrderConfirmedEventListener` | `@EventListener` + `.subscribe()` | **Removed**, replaced by synchronous transactional completion + outbox |
| `OutboxDispatcher`, `ExpireUnpaidOrdersJob`, `ExpiredRefreshTokenCleanupJob` | `@Scheduled` + `.subscribe(onNext, onError)` | Valid background usage: error handler, re-entrancy guard, and a bounded batch per tick |
| `.block()` in `src/main` | — | None |
| Gateway / courier calls | — | `timeout(...)` added (payment `app.payment.gateway-timeout`, default 10 s; courier 10 s) |
| `switchIfEmpty` after a `flatMap` that can complete empty | — | Found and fixed in stock reservation (it would have reserved twice; caught by a test) |
| Eager `.then(port.call())` arguments | — | Side-effecting steps wrapped in `Mono.defer` |

## 7. Database changes (V32–V38)

| Version | Module | Change |
|---|---|---|
| V32 | inventory | stock CHECKs, status CHECK, `inventory_reservations` |
| V33 | bootstrap | `outbox_events` |
| V34 | order | UNIQUE `orders.checkout_id`, `order_number_seq`, checkout status CHECK (incl. COMPLETED), order item price and currency CHECKs |
| V35 | payment | `refunded_amount` + CHECK, `PARTIALLY_REFUNDED`, partial UNIQUE live payment per order, `refunds` table |
| V36 | shipment | UNIQUE `shipments.order_id`, UNIQUE `shipment_number` |
| V37 | review | UNIQUE (product_id, user_id) |
| V38 | notification | partial UNIQUE (user_id, type, reference_id) |

Build-time guards (`FlywayMigrationVersionTest`): duplicate versions, files Flyway would ignore, and destructive statements (`DROP TABLE/SCHEMA/COLUMN`, `TRUNCATE`, `DELETE FROM`). Verified: a clean DB migrates V1→V38. The existing-dev-DB path was verified V22→V31 in phase 09. V32–V38 only add constraints compatible with existing data (checked before writing them).

## 8. Verification evidence

### 8.1 Browser (real Chrome via Playwright, `e2e/ui-flow.mjs`)

| Scenario | Steps | Result |
|---|---|---|
| happy | register → login → add to cart → checkout + new address → validate → place order → pay (authorise + capture) → success page → forged success URL → refund request | All steps OK. **No console errors, page errors or 5xx** |
| capture-failure | … → pay → declined → failure page → Pay now offered → cancel order | All OK. DB: payment FAILED, order CANCELLED, reservation RELEASED, stock back to seed (60/2), no shipment |
| capture-timeout | … → pay → gateway silent → "Retry confirmation" | OK. Payment PROCESSING, order PENDING_PAYMENT. Cancel during capture → 409. After gateway recovery, retry → SUCCESS, order CONFIRMED, 1 payment row |

DB after the happy journey: order CONFIRMED · payment SUCCESS (UPI) · cart COMPLETED · reservation FULFILLED · inventory 80/3 → 79/3 · shipment READY_FOR_PICKUP to the UI-entered "42 Stadium Road" · notifications ORDER_PLACED, PAYMENT_SUCCESS, ORDER_CONFIRMED · refund REQUESTED · outbox all DONE.

### 8.2 API (`e2e/api-e2e.sh`): **72 / 72 passed**, 0 ERROR log lines

### 8.3 Refund failure handling (live, gateway in failure mode)

Request (201 REQUESTED) → admin approve → **FAILED** ("Mock refund declined"), payment still SUCCESS with refunded 0.00 → gateway recovered → approve again → **COMPLETED (attempts 2)**, payment and order REFUNDED, exactly one notification.

### 8.4 Automated tests

| Suite | Result |
|---|---|
| `mvn clean install` (unit + Testcontainers DB tests) | **500 passed, 0 failed, 0 skipped** |
| `mvn test -Pintegration-test` | **15 passed**: user repository 7, stock-reservation concurrency 4, order optimistic locking 4 |
| Frontend | `tsc --noEmit` 0 errors · `vite build` OK |

New tests in this phase include: `StockReservationUseCasesTest` (13), `StockReservationConcurrencyIntegrationTest` (4), `CapturePaymentUseCaseTest` (9), `CreatePaymentUseCaseTest` (rewritten, 10), `RefundUseCasesTest` (11), `PaymentTest` refund/capture cases, `OrderStateMachineTest`, `OrderOptimisticLockingIntegrationTest` (4), `PlaceOrderUseCaseTest` (rewritten), `ReviewOrderQueryServiceTest` (5, real adapter), `CreateReviewUseCaseTest` (rewritten), `CreateShipmentUseCaseTest` (rewritten), the tracking IDOR test, `MockPaymentGatewayTest` (idempotency), public price visibility, and the migration safety tests.

### 8.5 Observability (live)

- A request sent with `X-Correlation-Id: trace-me-123` produced `WARN [trace-me-123] … GlobalExceptionHandler`, including on R2DBC driver threads.
- Metrics: `dsports.orders.placed{currency}`, `dsports.orders.cancelled`, `dsports.payments.captured`, `dsports.payments.failed`, `dsports.refunds.completed`, `dsports.api.errors{code,status}` (covers `INSUFFICIENT_STOCK`, checkout and gateway failures), `dsports.outbox.messages{type,result}`.
- A scan of the application log for passwords and tokens found 0 matches.

## 9. Open items

| Item | Why not done | Suggested next step |
|---|---|---|
| No admin provisioning (admins are granted by SQL) | Needs a decision: bootstrap admin from env vars vs a SUPER_ADMIN role API | ADR + small story |
| No rate limiting on `/api/auth/*` | Infrastructure decision (gateway vs in-app) | Before public exposure |
| Frontend `npm run lint` fails (no `eslint.config.js` for ESLint 9) | Requires adding dev dependencies (typescript-eslint, react-hooks plugin) | Add the config and dependencies in a tooling story |
| Testcontainers needs `-Dapi.version=1.44` + `DOCKER_HOST` with Docker Engine 29 | Upgrading Testcontainers needs network access to Maven Central | Upgrade to ≥ 1.21 and drop the pin |
| No CI workflow (`.github/workflows` empty) | Out of scope for this phase | `mvn verify` + `-Pintegration-test` + frontend build |
| Guest cart is local-only, not merged on login (ADR-005) | Feature, not a defect | Decide: implement or supersede ADR-005 |
| Returns, billing/invoices, franchise, admin UI, discounts, keyword search | Missing features (planned modules) | Roadmap |
| Shipment stores no recipient name/phone (they stay on the order snapshot) | Needs a schema change | Add when integrating a real courier |
| JWT HS256 without `iss`/`aud`; tokens in `localStorage` | Hardening, not a functional gap | Before external clients or AI services consume tokens |
| Prices are frozen when an item is added to the cart (no re-pricing at checkout validation) | Needs a product decision on how to present price changes | Re-price at validation and show the difference |
| Some admin checks are inline `isAdmin()` (review, shipment, notification) | They work (verified 403); consolidation is a refactor | Move to `@PreAuthorize` |
| `AuthenticationUseCase` is dead code | Not referenced anywhere | Remove |
| Refund does not restock goods | Physical returns belong to the (missing) Returns module | Returns module |

## 10. AI readiness

**Not declared.** All gates listed in this phase's instructions up to section 20 pass, with the evidence above. The instructions were cut off inside section 20 ("Refund tests"), however, so any later mandatory gates are unknown and cannot be claimed as passed. Once the remaining sections are provided and checked, this section can be updated.
