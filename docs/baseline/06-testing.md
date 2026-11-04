# 06 — Testing Baseline

Evidence tags: **[RUN]** · **[CODE]** · **[DB]** · **[INF]**. See [README](README.md).

> **Update 2026-10-06:** all failures below are fixed. Current result: **430 unit + container tests passing, 0 skipped**, plus integration profile 7/7. Container tests now actually execute. See [09 §6](09-remediation-log.md#6-final-test-run).
>
> **Running the DB tests locally (Docker Desktop / Engine 29+ on Windows):**
> ```bash
> export DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine   # the active `docker context`
> mvn clean install -Dapi.version=1.44                           # unit + container tests
> mvn test -Pintegration-test -Dapi.version=1.44                 # @Tag("integration") tests
> ```
> Without these settings, Testcontainers' default Docker API version is rejected (HTTP 400) and the 4 container classes silently report "Tests run: 0". Upgrading Testcontainers to ≥ 1.21 should remove the API pin. Surefire now forces `-Duser.timezone=UTC`, matching the application.

## 1. Results (2026-10-05, working tree, `mvn install -Dmaven.test.failure.ignore=true`) [RUN]

| Module | Tests | Failures | Test classes (main focus) |
|---|---|---|---|
| identity | 47 | 0 | use cases (login, logout, refresh, profile), VOs, JWT provider, CustomerController |
| catalog | 70 | 0 | domain models, 7 use cases, admin and public controller (method-level) |
| inventory | 25 | **3** | `InventoryItemTest` (stock-out event count), CreateInventoryUseCase |
| pricing | 47 | 0 | 7 use cases, Price domain, controllers |
| cart | 20 | 0 | `CartTest` (domain only) |
| order | 35 | **1** | Checkout domain, Create/Validate checkout (**failing**), PublicCheckoutController |
| payment | 37 | 0 | Payment domain, CreatePayment, MockGateway, controller |
| review | 16 | 0 | CreateReview, Rating, controller |
| shipment | 28 | 0 | 4 use cases, domain, mock courier, controller |
| notification | 33 | 0 | 2 use cases, domain, 3 mock providers, controller |
| bootstrap | 11 | 0 | `GlobalExceptionHandlerTest` (direct method calls) |
| wishlist | 0 | — | **no tests** |
| **Total** | **369** | **4** | |

**Failures:**
- `InventoryItemTest.shouldStockOut`, `…LowStockEvent…`, and `…OutOfStockEvent…` expect 1 or 2 events, but `stockOut()` now also emits `InventoryUpdatedEvent`. Decide which side is correct: the test or the domain.
- `ValidateCheckoutUseCaseTest.shouldValidateCheckoutSuccessfully` verifies `findById` was called ≥2 times, but the implementation calls it once. The test is stale.

With plain `mvn test` (fail-fast per module), these failures cause **review, shipment, notification, and bootstrap tests to be skipped**. `AGENTS.md` states "Never ignore failing tests."

**Database tests silently do not run.**
- `CatalogMigrationTest`, `CheckoutMigrationTest`, `PriceMigrationTest`, and `PriceR2dbcRepositoryAdapterTest` are *not* tagged, so they run in the unit phase. Each starts a Testcontainers Postgres in `@BeforeAll` and calls `assumeTrue(false, "Docker is required…")` on failure. On this machine Testcontainers could not reach Docker (Rancher Desktop), and surefire reported **"Tests run: 0"** for all four, with no failure and no skip count. They look green but verify nothing [RUN].
- `UserRepositoryIntegrationTest` is the only `@Tag("integration")` test. It is excluded by default and runs with `mvn -Pintegration-test test`. It was not executed in this audit.
- Each migration test loads only its own module's classpath, so none of them could detect the cross-module duplicate V19/V20.
- Recommendation: fail loudly when Docker is missing in CI (no `assumeTrue`), configure Testcontainers for Rancher Desktop locally (`DOCKER_HOST`/`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`), and tag every container test consistently.

**Frontend:** no test runner is configured (no Vitest or Jest) and there are no tests. `tsc` (strict) currently **fails with 5 errors** (unused imports in `NotificationHistoryPage.tsx`, `ShipmentPage.tsx`, and `notificationStore.ts`), so `npm run build` fails [RUN].

## 2. Coverage gaps that hid real defects

| Gap | Defect it would have caught |
|---|---|
| No HTTP-level tests (`WebTestClient` + the real `GlobalExceptionHandler` + `SecurityConfig`). Controller tests call methods directly and assert on `Mono` values | Every error returning HTTP 200 (E-01). `@PreAuthorize` producing 500 (S-02). Bad UUID/JSON producing 500 |
| No application-context smoke test (`@SpringBootTest` boot) | Duplicate `orderQueryService` bean. App fails to start |
| No migration test over the **whole** classpath | Duplicate V19/V20 |
| No repository integration test for `OrderR2dbcRepositoryAdapter` | `order_items` UPDATE-instead-of-INSERT (order placement 500) |
| No `PlaceOrderUseCase` test | Checkout ID passed as cart ID. No reservation. Non-atomic flow |
| No `PaymentSucceededEventHandler` test | Swallowed errors. Cart `COMPLETED` CHECK violation |
| No `RefundPaymentUseCase` test | Unrestricted refunds. Refund persisted on gateway failure |
| No cart use-case tests | Cumulative quantity vs stock |
| No concurrency tests | Duplicate orders, payments, and order numbers |
| No end-to-end test (API or Playwright) | UI never calls capture |

## 3. Recommended test baseline (for the backlog)

1. **Context smoke test** in bootstrap: `@SpringBootTest` + Testcontainers Postgres. Boots the app and runs all migrations. This is the single highest-value test.
2. **Web-layer contract tests** with `@WebFluxTest` or `WebTestClient` against the real advice and security: one test per error category from the [error contract](05-reactive-errors-observability.md#proposed-standard-error-contract-for-review).
3. **Repository integration tests** (Testcontainers) for each adapter that saves child rows: order, checkout, cart, shipment, wishlist.
4. **Order-to-cash API test**: register → add to cart → checkout → order → pay → capture → assert order CONFIRMED, inventory decremented, cart COMPLETED, shipment created, notification created.
5. **Concurrency tests**: parallel place-order on the same checkout, and parallel payments on the same order.
6. Frontend: add Vitest + React Testing Library for the API client error handling and checkout and payment flows. Add `tsc --noEmit` and `eslint` to CI.
7. CI: `.github/workflows/` is **empty**. Add backend `mvn verify` (unit + integration) and frontend `build` + `lint`.
