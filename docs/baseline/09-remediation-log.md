# 09 — Remediation Log (P0)

**Date:** 2026-10-05
**Scope:** P0 items from [08-gap-backlog.md](08-gap-backlog.md): startup, Flyway, error handling, order creation, and cart lifecycle. Also fixes for defects that were found *while verifying* those items end to end.
**Rule applied:** an item is marked fixed only after its behaviour was verified against the running application and the database, not because it compiles.

## 1. Summary

| Backlog ID | Issue | Status | Verified by |
|---|---|---|---|
| P0-1 | Duplicate `orderQueryService` bean (Review vs Shipment) | ✅ Fixed | App boots ([§3.1](#31-p0-1--duplicate-orderqueryservice-bean)) |
| P0-2 | Flyway duplicate V19/V20 | ✅ Fixed | Clean DB → V31 OK. Dev-DB copy V22 → V31 OK. Classpath test |
| P0-3 | `OrderItemEntity` UPDATE instead of INSERT | ✅ Fixed (+2 latent siblings) | `orders=1`, `order_items=1` after a real order |
| P0-4 | Errors returned HTTP 200. Framework errors returned 500 | ✅ Fixed | 16 HTTP-level tests + 17 live probes |
| P0-5 | 4 failing unit tests | ✅ Fixed | Full suite green |
| P0-6 | Frontend `tsc` errors (build broken) | ✅ Fixed | `tsc --noEmit` and `vite build` pass |
| P0-8 + P1-1 | Cart lifecycle: wrong ID passed; `COMPLETED` forbidden by DB | ✅ Fixed | Cart ACTIVE → CHECKED_OUT → COMPLETED observed in DB |
| *new* N-1 | Seeded rows (version 0) could never be updated: duplicate INSERT | ✅ Fixed | Post-payment inventory update succeeds |
| *new* N-2 | `fulfillReservation` did not reduce on-hand stock | ✅ Fixed | Inventory 25 → 23 after buying 2 |
| *new* N-3 | Mock courier tracking number overflowed `VARCHAR(20)` | ✅ Fixed | Shipment reaches READY_FOR_PICKUP |
| *new* N-4 | DB tests silently ran 0 tests; integration profile selected 0 tests | ✅ Fixed (+3 test-code bugs they hid) | 36 container tests now execute and pass |
| P0-7 | Untracked modules / uncommitted changes | ⏳ Open, needs owner | Nothing was committed (commits only on request) |

## 2. Verification evidence

### 2.1 Startup and migrations

```
Clean DB (dsports_verify)
  → Flyway: "Successfully applied 31 migrations … now at version v31"
  → "Started DSportsApplication"                       ✅  (no bean-definition errors)

Copy of the existing dev DB (CREATE DATABASE dsports_devcopy TEMPLATE dsports; at V22)
  → Flyway validated existing history, applied V23 … V31 (9 migrations)   ✅
  → same end-to-end flow passes, 0 ERROR log lines                         ✅
  → original `dsports` DB untouched (still V22)
```

### 2.2 Error contract: live probes against the running app

| Scenario | Before | After |
|---|---|---|
| Unknown product id | 200 (body 404) | **404** `PRODUCT_NOT_FOUND` |
| Malformed UUID | 200 (body 500) | **400** `INVALID_PARAMETER` |
| Invalid `sortBy` | 200 (body 500) | **400** `VALIDATION_ERROR` |
| Invalid body | 200 (body 400) | **400** `VALIDATION_ERROR` + field errors |
| Malformed JSON | 200 (body 500) | **400** `MALFORMED_REQUEST` |
| Duplicate email | 200 (body 409) | **409** `DUPLICATE_EMAIL` |
| No token / garbage token | 401 `{"message"}` | **401** `AUTHENTICATION_REQUIRED` (ApiError) |
| Customer → `/api/admin/**` | 403 plain text | **403** `ACCESS_DENIED` (ApiError) |
| Customer → `@PreAuthorize` endpoint | 200 (body 500) | **403** `ACCESS_DENIED` |
| Customer → inline admin check | 200 (body 500) | **403** `ACCESS_DENIED` |
| Address `type: "HOME"` | 200 (body 500) | **400** `VALIDATION_ERROR` |
| Unknown route | 200 (body 500) | **404** `ROUTE_NOT_FOUND` |
| Wrong HTTP method | 200 (body 500) | **405** `METHOD_NOT_ALLOWED` |
| Another user's order | 200 (body 404) | **404** `ORDER_NOT_FOUND` |
| Duplicate order for a checkout | — | **409** `DUPLICATE_ORDER` |
| Payment amount ≠ order total | — | **400** `AMOUNT_MISMATCH` |
| `correlationId` in error body | always `null` | generated ID, identical to the `X-Correlation-Id` response header. A client-supplied ID is echoed |

### 2.3 End-to-end commerce flow (clean DB and dev-DB copy, identical results)

```
register → login → add to cart (200) → add address → create checkout → select address (200)
→ select delivery (200) → validate (200) → place order (201) → place again (409 DUPLICATE_ORDER)
   DB: order PENDING_PAYMENT, order_items = 1, cart CHECKED_OUT
→ pay wrong amount (400 AMOUNT_MISMATCH) → create payment (201, AUTHORIZED) → capture (200, SUCCESS)
   DB: order CONFIRMED · payment SUCCESS · cart COMPLETED (0 items)
       inventory 25 → 23 on hand · shipment READY_FOR_PICKUP
       notifications ORDER_PLACED, PAYMENT_SUCCESS, ORDER_CONFIRMED
→ add to cart again (200, new ACTIVE cart)
Application log: 0 ERROR lines
```

### 2.4 Tests

| Suite | Before | After |
|---|---|---|
| Backend unit tests (`mvn test`) | 369 run, **4 failing**, 4 modules skipped on fail-fast | all passing, 28+ new tests (see §4) |
| Container DB tests (Testcontainers) | reported "Tests run: 0" (silently skipped) | 29 tests executing and passing |
| `-Pintegration-test` | selected 0 tests | `UserRepositoryIntegrationTest` 7/7 |
| Frontend `tsc` | 5 errors | 0 errors. `vite build` succeeds |

Final counts are in [§6](#6-final-test-run).

## 3. Changes, root causes and decisions

### 3.1 P0-1 — Duplicate `orderQueryService` bean

- **Root cause:** Review and Shipment each correctly own a *different* port interface (`review…OrderQueryService`, `shipment…OrderQueryService`), but both adapter `@Bean` methods were named `orderQueryService`, and Spring bean names are global.
- **Fix:** module-scoped bean names `reviewOrderQueryService` / `shipmentOrderQueryService`, following the existing `reviewEventPublisher` / `shipmentEventPublisher` convention. Bean overriding was **not** enabled. A scan confirmed there are no other duplicate `@Bean` method names or component class names.
- **Files:** `ReviewInfrastructureConfiguration`, `ShipmentInfrastructureConfiguration`.

### 3.2 P0-2 — Flyway version conflicts

- **Analysis:** `V19__create_notification_tables` and `V20__create_shipment_tables` are self-contained (no FKs to other modules, no data dependencies) and never ran in the dev DB (which is at V22). No code references them. Only the PR summary docs do.
- **Fix:** renamed them to **V26** and **V27** (after the pending V23–V25). Updated the references in `backend/docs/pr19-summary.md` and `pr20-summary.md`. No migration was deleted.
- **Regression guard:** `bootstrap/.../FlywayMigrationVersionTest` scans `classpath*:db/migration` (all modules) and fails on duplicate versions. It immediately caught a *stale* `V19` left in `notification/target`, so **always build with `mvn clean`** after renaming migrations.
- **Final sequence:** V1 … V31, contiguous.

### 3.3 P0-4 — Error handling and the API error contract

- **Root cause:** every `@ExceptionHandler` returned `Mono<ApiError>` with no `ResponseEntity` or `@ResponseStatus`, so WebFlux sent 200. Framework exceptions had no handlers and fell through to the 500 catch-all. Security returned non-ApiError bodies.
- **Fix:**
  - All handlers return `ResponseEntity<ApiError>`. The existing per-module code → status maps were kept as the convention (400/404/409/410/423/502).
  - New handlers: `ServerWebInputException` → 400 (`INVALID_PARAMETER` / `MISSING_PARAMETER` / `MALFORMED_REQUEST`), `ResponseStatusException` → its own status (`ROUTE_NOT_FOUND` 404, `METHOD_NOT_ALLOWED` 405, controller 401/403, …), `AccessDeniedException` → 403, `AuthenticationException` → 401, `OptimisticLockingFailureException` → 409, `DataIntegrityViolationException` → 409 `DATA_CONFLICT` (generic message, no SQL).
  - 5xx responses never carry the exception or domain message. It is logged, not returned.
  - The security entry point (401) and a new access-denied handler (403) write the same `ApiError` through the shared `ApiErrors` factory.
  - `correlationId` is resolved from the request header, else from the response header that `CorrelationIdFilter` sets.
  - Validation-type 500s fixed at the source: address `type` is validated with `@Pattern(SHIPPING|BILLING)`, and catalog `sortBy` throws `CatalogDomainException(VALIDATION_ERROR)`.
- **422 decision:** not introduced. The codebase convention is 400 for validation and 409 for business conflicts. Using 422 for one class of errors would make the contract inconsistent.
- **Frontend:** pages already read `err.response.data.message` and now receive real error statuses. Added `src/api/errors.ts` (`ApiError` type, `getApiErrorMessage`) and used it where pages showed the generic axios message (Register, Payment, Payment history). The 401 refresh interceptor now skips `/auth/*` endpoints, so a failed login is a plain 401 and no longer triggers a refresh attempt.
- **Contract:** documented in [05 §2](05-reactive-errors-observability.md#proposed-standard-error-contract-for-review), now marked *implemented*.

### 3.4 P0-3 — Order items saved with UPDATE

- **Root cause:** Spring Data R2DBC decides INSERT vs UPDATE via `isNew()`: new if the `@Id` is null *or* a primitive `@Version` is 0. `OrderEntity` has `@Version int` (works). `OrderItemEntity` has an app-assigned UUID and no version, so it is always "existing" and gets an UPDATE, which matches no row.
- **Why not copy `cf70a75` blindly:** that commit added `Persistable` to `CheckoutItemEntity`. The same mechanism is correct here because the adapter *always* replaces an order's items (delete + insert), so every outgoing item is new. The mapper sets `isNew = true` and the entity documents why.
- **Sibling scan:** every entity with neither `@Version` nor `Persistable` was checked for how it is written. `ShipmentTrackingEntity` and `HelpfulVoteEntity` had the same latent bug (append-only rows with pre-assigned IDs saved via `repository.save`) and got the same fix. The others use explicit INSERT SQL or `entityTemplate.insert` and are unaffected.

### 3.5 P0-8 / P1-1 — Cart lifecycle

- **Root cause 1:** `PlaceOrderUseCase` called `markCartAsCheckedOut(order.getCheckoutId())`. The port expects a **cart** ID, so the lookup found nothing and the adapter silently did nothing.
- **Root cause 2:** `ck_cart_status_valid` (V18) predates `Cart.complete()` and omitted `COMPLETED`.
- **Fix:**
  - The use case passes `checkoutData.cartId()`.
  - The adapter now **fails** with `CART_NOT_FOUND` instead of silently doing nothing.
  - **V28** extends the CHECK constraint, keeping it rather than removing it.
  - The order is persisted *before* the cart is closed. Until P1-3 makes placement transactional, a failure therefore leaves "order saved, cart still active" (recoverable) rather than "cart closed, no order" (lost basket).
- **Final cart state machine** (from the domain model, now consistent with the DB):

```
            add/update/remove/clear items allowed only here
                         ┌────────┐
           create ──────►│ ACTIVE │──── abandon() ────► ABANDONED (terminal)
                         └───┬────┘
         order placed        │ checkout()   (PlaceOrderUseCase → CartCheckoutPort)
                             ▼
                       ┌────────────┐
                       │ CHECKED_OUT│  read-only; the customer gets a new ACTIVE cart on next add
                       └─────┬──────┘
   payment succeeded         │ complete()   (PaymentSucceededEventHandler; clears items)
                             ▼
                       ┌───────────┐
                       │ COMPLETED │  terminal
                       └───────────┘
DB: one ACTIVE cart per user (partial unique index uq_carts_user_active)
```

  Known gap (P1-9): if the order is cancelled or the payment never succeeds, the cart stays `CHECKED_OUT`. Items are not restored to a new cart.
- **Tests:** `CartTest` gained five transition tests (complete, complete-from-active rejected, double complete, mutations on completed, checkout of abandoned). The new `PlaceOrderUseCaseTest` asserts that the cart ID (not the checkout ID) is used, that the order is saved before the cart is closed, that the cart is not closed when the order save fails, and that duplicate orders are rejected.

### 3.6 New defects found during end-to-end verification

| ID | Defect | Root cause | Fix |
|---|---|---|---|
| N-1 | After payment, inventory save threw `duplicate key inventory_pkey`. The PG transaction aborted, so the order stayed PENDING_PAYMENT and the cart wasn't completed (the audit's [INF] "transaction poisoning" confirmed) | Seed migrations relied on `version DEFAULT 0`. A primitive `@Version` of 0 means "new" to Spring Data, so **all 174 seeded rows** (sports, categories, brands, products, prices, inventory) could never be updated through `save()`. App-written rows always have version ≥ 1 | **V29/V30/V31** (one per owning module): `SET version = 1 WHERE version = 0` and `DEFAULT 1`. Safe because the app never writes 0 |
| N-2 | Stock never decreased after a sale | Uncommitted `fulfillReservation()` decremented `reserved` but not on-hand `available`. In this model reservations are a subset of on-hand stock (`reserve()` keeps `available`, and `adjust()` caps `reserved ≤ available`) | Decrement both. Three new domain tests |
| N-3 | Shipment courier assignment failed: `value too long for varchar(20)` | `MockCourierGateway` returned `"mock-ref-<uuid>"` (45 chars) as `providerReference`, which both use cases persist as the tracking number | The mock returns the `TRK-XXXXXXXX` tracking number as `providerReference` (consistent with the use cases and their tests). New test guards the column width |
| N-4 | Container tests reported "Tests run: 0" | Testcontainers' default Docker API version is rejected by Docker Engine 29 (min API 1.40, returns 400), and the tests `assumeTrue`-skip | Run with `-Dapi.version=1.44` and `DOCKER_HOST` set to the active Docker context (see [06](06-testing.md)) |
| N-4a | `-Pintegration-test` selected 0 tests | Empty `<excludedGroups/>` is inherited, so `integration` was both included and excluded | Profile surefire config uses `combine.self="override"` |
| N-4b | Hidden test-code bugs surfaced once tests ran | Price adapter test: `bind(null)` for `effective_to`, and an invalid scenario (two ACTIVE prices for one product). Identity integration test: R2DBC URL with credentials in the query string, and `bind(null)` for nullable columns | Fixed in the tests. Production code was not involved |
| N-4c | Container JDBC connections failed on IST machines (`invalid TimeZone "Asia/Calcutta"`) | The JVM default zone is a legacy alias that the `postgres:16` image rejects. The app forces UTC, the tests didn't | Surefire `argLine -Duser.timezone=UTC` (tests now match runtime) |

## 4. Files changed

**Backend, production**
- `bootstrap/.../exception/GlobalExceptionHandler.java` (rewritten handlers), `exception/ApiErrors.java` (new), `security/SecurityConfig.java`, `config/CorrelationIdFilter.java` (public header constant), `config/OrderModuleConfig.java` (cart port fails loudly)
- `review/.../ReviewInfrastructureConfiguration.java`, `shipment/.../ShipmentInfrastructureConfiguration.java` (bean names)
- `order/.../PlaceOrderUseCase.java`, `order/.../OrderItemEntity.java`, `order/.../OrderEntityMapper.java`
- `shipment/.../ShipmentTrackingEntity.java`, `ShipmentEntityMapper.java`, `MockCourierGateway.java`
- `review/.../HelpfulVoteEntity.java`, `ReviewEntityMapper.java`
- `inventory/.../InventoryItem.java` (`fulfillReservation`)
- `identity/.../CreateAddressRequest.java`, `UpdateAddressRequest.java`; `catalog/.../ProductR2dbcRepositoryAdapter.java` (sort validation)
- Migrations: renamed `V26__create_notification_tables.sql`, `V27__create_shipment_tables.sql`. New `V28__allow_completed_cart_status.sql`, `V29__mark_seeded_catalog_rows_persisted.sql`, `V30__mark_seeded_price_rows_persisted.sql`, `V31__mark_seeded_inventory_rows_persisted.sql`
- `backend/pom.xml` (surefire UTC, integration profile override)

**Backend, tests**
- New: `GlobalExceptionHandlerHttpTest` (16), `FlywayMigrationVersionTest` (2), `PlaceOrderUseCaseTest` (4), `OrderEntityMapperTest` (1)
- Updated: `GlobalExceptionHandlerTest`, `CartTest` (+5), `InventoryItemTest` (3 fixed, +3), `ValidateCheckoutUseCaseTest`, `MockCourierGatewayTest` (+1), `PriceR2dbcRepositoryAdapterTest`, `UserRepositoryIntegrationTest`

**Frontend**
- New `src/api/errors.ts`. Updated `api/client.ts`, `RegisterPage.tsx`, `PaymentPage.tsx`, `PaymentHistoryPage.tsx`. Removed unused imports in `NotificationHistoryPage.tsx`, `ShipmentPage.tsx`, `notificationStore.ts`

## 5. Still open (not in this P0 scope)

These were confirmed during verification and remain on the [backlog](08-gap-backlog.md):

- **P1-7:** the UI calls create-payment but never `/capture`, so a payment made through the UI stays AUTHORIZED and the order is not confirmed. The API flow works (verified). The UI flow needs either a capture call or an authorise-and-capture mock.
- **P1-2:** order placement still only *checks* stock and never reserves it (oversell risk). The post-payment path falls back to `stockOut`.
- **P1-3 / P1-8:** placement and post-payment processing are not transactional or durable (fire-and-forget listeners, no outbox).
- **P1-4:** checkout has no terminal "ordered" state. A used checkout counts as active for up to 30 minutes.
- **P1-8:** the auto-created shipment uses an `"N/A"` address instead of the order's address snapshot.
- **S-01:** customers can refund their own SUCCESS payments.
- **New N-5:** `OrderR2dbcRepositoryAdapter.save()` copies the DB row's version onto the entity before saving, which defeats optimistic locking for orders.
- **New N-6:** Review's `userPurchasedProduct(userId, productId)` adapter treats its first argument as an order ID, so it always returns false, and **every review creation fails with `PRODUCT_NOT_PURCHASED`**.
- **P0-7:** commit or decide on the untracked modules (review, shipment, wishlist, notification) and this remediation work.
- **Local test setup:** Testcontainers needs `DOCKER_HOST` and `-Dapi.version=1.44` on this machine. Upgrading Testcontainers (≥ 1.21) would remove the API pin.

## 6. Final test run

`mvn clean install -Dapi.version=1.44` with `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`:

| Suite | Result |
|---|---|
| All modules (19/19) | **BUILD SUCCESS** |
| Unit + container tests | **430 run, 0 failures, 0 errors, 0 skipped** |
| Container DB tests that actually executed | CatalogMigrationTest 8, PriceMigrationTest 8, CheckoutMigrationTest 7, PriceR2dbcRepositoryAdapterTest 6 |
| `mvn test -Pintegration-test` | UserRepositoryIntegrationTest **7/7** |
| Frontend | `tsc --noEmit`: 0 errors. `vite build`: success (chunk-size advisory only) |

Without the Docker settings, the unit suite still passes, but the 4 container classes report "Tests run: 0" (see N-4).
