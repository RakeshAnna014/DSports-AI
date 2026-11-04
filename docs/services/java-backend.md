# Java commerce backend (`backend/`, port 8080)

## 1. Business purpose
The authoritative system for identity, catalogue, price, stock, cart, checkout, orders, payments, refunds, shipments, returns, reviews, wishlist, notifications, promotions and support cases. Nothing else may own or invent these facts; the Python AI service reads them through this service's REST API with the caller's own token.

## 2. Entry points and structure
* Application class: `bootstrap/src/main/java/com/dsports/DSportsApplication.java`. `bootstrap` is the only runnable module; it wires every other module (`config/*ModuleConfig.java`), owns the security chain, the error contract, the outbox and the scheduled jobs.
* Maven modules (`backend/pom.xml`), each a bounded context with `domain / application / infrastructure / interfaces` packages (domain has no framework imports; use cases are plain classes wired in `bootstrap`):

| Module | Owns | Main API prefix | Java main / test files |
|---|---|---|---|
| `shared` | ports and result types shared by modules (outbox port, transaction runner) | none | 8 / 0 |
| `identity` | customers, roles, refresh tokens, addresses, shopper and family profiles, JWT issue/verify | `/api/auth`, `/api/customers/me/**` | 113 / 13 |
| `catalog` | sports, categories, brands, products, images, attributes | public `GET /api/catalog/**`; admin `/api/admin/catalog/**` | 122 / 17 |
| `pricing` | prices and their lifecycle (draft, active, scheduled, archived) | `GET /api/prices/**`; admin `/api/admin/prices` | 40 / 12 |
| `inventory` | stock levels, reservations per order | `GET /api/inventory/{productId}`; admin `/api/admin/inventory` | 59 / 5 |
| `cart` | one active cart per customer | `/api/v1/cart` | 45 / 2 |
| `order` | checkout, promotions, orders, sales feed, co-purchase insight | `/api/v1/checkout`, `/api/v1/orders`, `/api/v1/admin/orders`, `/api/v1/admin/sales`, `/api/v1/insights` | 118 / 13 |
| `payment` | payments, refunds, gateway port | `/api/v1/payments`, `/api/v1/admin/refunds` | 59 / 6 |
| `shipment` | shipments and tracking | `/api/v1/shipments` | 46 / 8 |
| `returns` | return eligibility and requests | `/api/v1/orders/{id}/returns/eligibility`, `/api/v1/returns`, `/api/v1/admin/returns` | 21 / 4 |
| `review` | product reviews and moderation | `/api/v1/reviews` | 41 / 4 |
| `wishlist` | wishlist and move-to-cart | `/api/v1/wishlist` | 42 / 0 |
| `notification` | in-app notifications | `/api/v1/notifications` | 36 / 7 |
| `support` | support cases, messages, staff workflow, report | `/api/v1/support/cases`, `/api/v1/support/staff` | 24 / 3 |
| `bootstrap` | wiring, security, error contract, outbox, jobs, fulfilment board | `/api/v1/admin/fulfilment`, `/api/v1/admin/outbox`, `/actuator` | 27 / 7 |
| `admin`, `ai`, `billing`, `franchise` | **empty module shells (no Java sources)** | none | 0 / 0 |

Endpoint-level reference: [../api/java-backend.md](../api/java-backend.md) (generated from the running service's OpenAPI document).

## 3. Request lifecycle
Netty (WebFlux) → `CorrelationIdFilter` (accepts or creates `X-Correlation-Id`, puts it in the log pattern and the response) → `RateLimitWebFilter` (fixed window per address, in-memory, auth and ticket routes) → `JwtWebFilter` (reads `Authorization: Bearer`, verifies signature, expiry, issuer and audience, builds the authentication from the token's `sub` and `roles`) → `SecurityConfig` route rules → controller (maps the HTTP request to a command, takes the user id **only** from `Authentication`) → use case (application layer; owns the transaction and the business rules) → repository port → R2DBC adapter → PostgreSQL. Errors anywhere become the uniform `ApiError` body through `exception/GlobalExceptionHandler` (status, error, code, message, path, correlationId, timestamp, validation errors; no stack trace).

## 4. Business rules worth knowing
* **Order**: `CREATED → PENDING_PAYMENT → CONFIRMED → PROCESSING → SHIPPED → DELIVERED`; `CANCELLED` only before payment; `REFUNDED` after payment (`OrderStatus.canTransitionTo`). A paid order can never be cancelled, only refunded.
* **Placing an order** (`PlaceOrderUseCase`) is one transaction: order and items, stock reservations, cart marked checked-out, checkout completed, "order placed" notification recorded in the outbox. A second order for the same checkout is rejected (`DUPLICATE_ORDER`).
* **Capturing a payment** (`CapturePaymentUseCase`) has three phases: claim (transaction: order must await payment, payment `AUTHORIZED → PROCESSING`), gateway capture (no transaction held, with a timeout, idempotent per authorisation), complete (transaction: payment `SUCCESS`, order `CONFIRMED`, stock fulfilled, cart completed, notification in the outbox). Retrying is safe.
* **Payment gateway**: only `MockPaymentGateway` exists (`payment.mock.mode`, `payment.mock.capture-mode`, `payment.mock.refund-mode` = success / failure / timeout). No real provider is integrated.
* **Refunds**: customers request, an administrator approves or rejects (`/api/v1/admin/refunds/{id}/approve|reject`). **Returns**: eligibility is computed server-side (owned order, delivered, inside the window, quantity not already requested); an administrator approves or rejects.
* **Shipment**: created from the outbox after payment; status machine `CREATED → READY_FOR_PICKUP → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED` with `FAILED` and `RETURNED` branches.
* **Unpaid orders** expire through `jobs/ExpireUnpaidOrdersJob` (interval `app.orders.expiry-check-interval`, default 60 s) which cancels each unpaid order in its own transaction (`CancelOrderUseCase.executeAsSystem`): stock reservations are released, open payment authorisations are voided and a cancellation notification is recorded.
* **Sales feed** `/api/v1/admin/sales/lines` (ADMIN) feeds the planning module: it exposes order lines without customer names, addresses or phone numbers.

## 5. Data ownership
Database `dsports`, schema managed only by Flyway (`V1`–`V47`, 47 files under the modules' `src/main/resources/db/migration`). See [../DATABASE.md](../DATABASE.md). The AI service never connects to this database.

## 6. Cross-module reliability: the transactional outbox
`R2dbcOutboxWriter` writes a row to `outbox_events` in the **same transaction** as the business change. `OutboxDispatcher` (scheduled, `app.outbox.poll-interval`, default 1 s) claims up to `app.outbox.batch-size` (20) rows with `FOR UPDATE SKIP LOCKED` and a lease (`app.outbox.lease`, 5 min), then calls `OutboxHandlers`, which are idempotent: create shipment, void a payment authorisation, and the order/payment/refund notifications. A failed row is retried up to `app.outbox.max-attempts` (8) with its last error stored; `POST /api/v1/admin/outbox/{id}/retry` (ADMIN) re-queues one. Domain events are published in-process only after commit.

## 7. Security and authorization
`SecurityConfig` (first match wins): public `POST /api/auth/register|login|refresh`, `GET /actuator/health`, public catalogue/price/stock/product-review reads and (non-production) API docs; `/actuator/**` and `/api/admin/**`, `/api/v1/admin/**` need `ADMIN`; `/api/v1/support/staff/**` needs `SUPPORT_EXECUTIVE` or `ADMIN`; everything else needs authentication. Object-level ownership (orders, payments, shipments, returns, cases, profiles) is checked in the use cases, not in the filter chain. Tokens: HS256, 15-minute access and 7-day refresh, `iss`/`aud` required. Roles are never assignable through an API; they are granted by SQL on purpose. Observed matrix: [../security/AUTHORIZATION-MATRIX.md](../security/AUTHORIZATION-MATRIX.md). Policy tests: `bootstrap/.../security/SecurityPolicyTest` (33 tests).

## 8. Failure handling and observability
* Gateway timeout / unavailable → `GATEWAY_TIMEOUT` / `GATEWAY_ERROR`, the capture stays retryable.
* A failed follow-up job stays in the outbox with its error and attempt count.
* Logs: `logging.pattern` includes the correlation id; local output goes to the console (the start script redirects to `%TEMP%/dsports_backend.log`). Metrics and health under `/actuator` (ADMIN except health); business counters in `observability/BusinessMetricsListener`.
* Not present: distributed tracing, a log platform, alerting.

## 9. Run and test
```bash
cd backend && mvn -B -o clean install -DskipTests     # stop the running backend first (Windows locks the jar)
bash e2e/ai/start-backend.sh                          # detached, waits for /actuator/health
mvn -B -o package                                     # unit tests
mvn -B -o test -Pintegration-test -Dapi.version=1.44  # integration (Testcontainers; DOCKER_HOST on Windows)
bash e2e/api-e2e.sh                                   # real-stack API checks
```
Required environment (names only): `JWT_SECRET` (≥ 32 bytes), `JWT_ISSUER`, `JWT_AUDIENCE`, `DB_NAME`, `DB_PASSWORD`, `RATE_LIMIT_ENABLED`, `RATE_LIMIT_AUTH_PER_MINUTE`, `RATE_LIMIT_TICKETS_PER_MINUTE`, `payment.mock.mode` / `capture-mode` / `refund-mode`, `app.payment.gateway-timeout`. The `local` profile carries development-only defaults; `prod` has none and disables the API docs.

## 10. Implemented / partial / missing
Implemented and tested: everything in the table above except the four shells. Partial: payment (mock gateway only), notifications (in-app channel only). Missing: franchise, billing, admin back-office modules, role-assignment API, token revocation (roles live until the 15-minute token expires). First files to read: `SecurityConfig`, `JwtWebFilter`, `GlobalExceptionHandler`, `PlaceOrderUseCase`, `CapturePaymentUseCase`, `OutboxDispatcher`, `OutboxHandlers`.
