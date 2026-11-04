# 02 — API Inventory, Validation & Security

Evidence tags: **[RUN]** · **[CODE]** · **[DB]** · **[INF]**. See [README](README.md).

## 1. Endpoint inventory

**Auth column:** `public` = `permitAll` in `SecurityConfig`; `auth` = any authenticated user; `ADMIN(path)` = the `/api/admin/**` rule; `ADMIN(@PA)` = `@PreAuthorize`; `ADMIN(inline)` = a role check hand-written in the controller method.

| Module | Method & path | Auth | Ownership check | Body validation |
|---|---|---|---|---|
| identity | POST `/api/auth/register` | public | — | `@Valid` RegisterRequest |
| identity | POST `/api/auth/login` | public | — | `@Valid` on **LoginUserCommand** (application command used as API DTO) |
| identity | POST `/api/auth/refresh` | public | — | `@Valid` RefreshTokenCommand |
| identity | POST `/api/auth/logout` | auth | userId from JWT | `@Valid` |
| identity | GET/PUT `/api/customers/me` | auth | JWT subject | `@Valid` (names only; phone/URL rely on domain VOs) |
| identity | GET/POST `/api/customers/me/addresses`, PUT/DELETE `/{id}`, PUT `/{id}/default` | auth | JWT subject | `@Valid`, but `type` is parsed with `AddressType.valueOf`, so a bad value returns a 500 [RUN] |
| catalog | GET `/api/catalog/{sports,categories,brands,products,products/{id}}` | public | — | `sortBy` is whitelisted, but an invalid value throws `IllegalArgumentException` and returns a 500 [RUN] |
| catalog | `/api/admin/catalog/**` (≈25 endpoints) | ADMIN(path) + ADMIN(@PA) | — | `@Valid` |
| inventory | GET `/api/inventory`, `/api/inventory/{productId}` | **public** | — | — |
| inventory | `/api/admin/inventory/**` | ADMIN(path) + @PA | — | `@Valid` |
| pricing | GET `/api/prices`, `/api/prices/{id}` | **public** | — | — |
| pricing | `/api/admin/prices/**` | ADMIN(path) + @PA | — | `@Valid` |
| cart | GET/DELETE `/api/v1/cart`, POST `/items`, PUT/DELETE `/items/{id}` | auth | JWT subject | `@Valid` |
| order | `/api/v1/checkout` (create, get, active, address, delivery, validate, cancel) | auth | yes (`CHECKOUT_NOT_OWNED_BY_CUSTOMER`) | `@Valid` |
| order | POST/GET `/api/v1/orders`, GET `/{id}`, PUT `/{id}/cancel` | auth | yes | `@Valid` |
| order | GET/PUT `/api/v1/admin/orders/{id}[/status]` | ADMIN(@PA) **only**, not covered by the path rule | — | `@Valid` |
| payment | POST `/api/v1/payments`, `/capture`, `/cancel`, `/refund`; GET `/{id}`, `/history` | auth | yes | `@Valid` |
| review | POST/PUT/GET `/api/v1/reviews…`, GET `/product/{id}[/summary]` | auth (**reading requires login**) | yes | `@Valid` |
| review | POST `/api/v1/reviews/admin/{id}/moderate` | **ADMIN(inline)** | — | `@Valid` |
| shipment | POST `/api/v1/shipments` | auth | order owner | **no `@Valid`, no constraints** |
| shipment | GET `/api/v1/shipments/{id}` | auth | yes (admin bypass) | — |
| shipment | GET `/api/v1/shipments/track/{trackingNumber}` | auth (the OpenAPI text says "Public") | **none** | — |
| shipment | POST `/{id}/courier`, PUT `/admin/{id}/status` | **ADMIN(inline)** | — | **no `@Valid`** |
| notification | GET `/api/v1/notifications`, `/{id}` | auth (admins see all) | yes | — |
| notification | POST `/api/v1/notifications/{id}/retry` | **ADMIN(inline)** | — | — |
| wishlist | `/api/v1/wishlist` (get, count, add, remove, move-to-cart, clear) | auth | JWT subject | `@Valid` |
| platform | `/actuator/health` public; `/actuator/info`, `/metrics` authenticated; `/swagger-ui`, `/api-docs` public | | | |

**Path versioning is inconsistent.** Identity, catalog, inventory, and pricing use `/api/...`. Cart, checkout, order, payment, review, shipment, notification, and wishlist use `/api/v1/...`. The architecture doc mandates `/api/v1`.

## 2. Verified HTTP behaviour [RUN]

Tested against the `HEAD` build on a scratch database.

| Scenario | Expected | Actual |
|---|---|---|
| GET unknown product id | 404 | **HTTP 200**, body `{"status":404,"code":"PRODUCT_NOT_FOUND"}` |
| GET `/products/abc` (malformed UUID) | 400 | **HTTP 200**, body `status:500 INTERNAL_ERROR` |
| `sortBy=foo` | 400 | **HTTP 200**, body `status:500` |
| Register with an invalid body | 400 | **HTTP 200**, body `status:400 VALIDATION_ERROR` with field errors (body correct) |
| Register a duplicate email | 409 | **HTTP 200**, body `status:409` |
| Malformed JSON | 400 | **HTTP 200**, body `status:500` |
| Customer calls an `@PreAuthorize` admin endpoint | 403 | **HTTP 200**, body `status:500` |
| Customer calls `/api/admin/**` | 403 | HTTP 403, plain text `Access Denied` (not ApiError) |
| No token on a protected route | 401 | HTTP 401, `{"message":"Authentication required"}` (not ApiError) |
| Address `type: "HOME"` (the enum is SHIPPING, …) | 400 | **HTTP 200**, body `status:500` |
| Unknown route `/api/nope` | 404 | **HTTP 200**, body `status:500` |
| Error body `correlationId` | echoes the generated ID | always `null` unless the client sent the header |

**Root cause:** every `@ExceptionHandler` in `backend/bootstrap/.../GlobalExceptionHandler.java` returns `Mono<ApiError>` with neither `ResponseEntity` nor `@ResponseStatus`, so WebFlux defaults to 200. There are no handlers for `ServerWebInputException`, `ResponseStatusException`, `AccessDeniedException`, `DataIntegrityViolationException`, or `OptimisticLockingFailureException`, so all of them fall into `handleUnknown`. `GlobalExceptionHandlerTest` calls the handler methods directly, which is why tests never caught this.

**Frontend impact:** axios only rejects on status codes of 400 and above. Every business error, such as insufficient stock or a duplicate email, reaches `.then()` as if it succeeded, and `catch` branches never run.

## 3. Request validation gaps

| ID | Gap | Location | Evidence |
|---|---|---|---|
| V-01 | Shipment create, courier, and status request records have no `@Valid` and no constraints (null `orderId`, null enums, empty address all reach the domain) | `ShipmentController` | [CODE] |
| V-02 | Enum parsing in controllers or use cases throws `IllegalArgumentException`, which becomes a 500 (`AddressType.valueOf`, `DeliveryMethod.fromCode` (guarded by `@Pattern`), `ProductFilter` sort) | identity, order, catalog | [RUN] |
| V-03 | Path variables of type `UUID` that are malformed become a 500 | all controllers | [RUN] |
| V-04 | Pagination: `size` is silently clamped to 1–100 and a negative `page` becomes 0 (catalog). Admin lists, order history, payment history, and inventory lists are **unbounded `Flux`** with no paging | catalog, order, payment, inventory | [CODE] |
| V-05 | List responses carry no total count or page metadata, so the UI cannot build a pager | catalog, reviews, notifications | [CODE] |
| V-06 | Password policy is length 8–128 only. No breached-password or complexity rule | `RegisterRequest` | [CODE] |
| V-07 | Address DTO fields have no length limits (`@Size`). Postal code and country rely on domain VOs | `CreateAddressRequest` | [CODE] |
| V-08 | Review `title` and `comment` have no `@Size`. `rating` is a primitive `int`, so a missing value defaults to 0 and fails with a range message instead of "required" | `ReviewController` | [CODE] |
| V-09 | Client-supplied payment `amount` is redundant. It is validated against the order total (good), but the server should derive it | `CreatePaymentRequest` | [CODE] |

## 4. Security findings

| ID | Finding | Risk | Evidence |
|---|---|---|---|
| **S-01** | **Customer self-refund.** `POST /payments/{id}/refund` lets the owner refund any SUCCESS payment at any time. It does not check order state (a DELIVERED order can be refunded and the goods kept), it is not linked to a return, and it needs no admin approval. `payment.refund()` runs *before* the gateway call, and a gateway failure is only logged, so `REFUNDED` is persisted either way | Financial loss | [CODE] `RefundPaymentUseCase` |
| **S-02** | `@PreAuthorize` denials surface as 500 (AccessDeniedException is caught by the catch-all) | Misleading clients, noisy alerts | [RUN] |
| **S-03** | Admin authorisation is enforced three different ways: path rule, `@PreAuthorize`, and inline `isAdmin()` checks. Inline checks in Review, Shipment, and Notification are easy to omit on new endpoints, and paths like `/api/v1/admin/**` and `/api/v1/*/admin/**` are not covered by the `/api/admin/**` rule | Authz drift | [CODE] |
| **S-04** | **Shipment tracking IDOR.** Any authenticated user can fetch any shipment by tracking number and receive the full shipping address and userId. The OpenAPI text calls it public | PII exposure | [CODE] |
| **S-05** | Public `GET /api/inventory` exposes every warehouse ID, reserved quantity, and reorder level. Public `GET /api/prices` returns DRAFT and SCHEDULED (unreleased) prices. Public product detail returns ARCHIVED products | Business data leak | [CODE] |
| **S-06** | Error messages leak internals: `CartR2dbcRepositoryAdapter` and `ProductR2dbcRepositoryAdapter` put `DataIntegrityViolationException.getMessage()` (SQL and constraint text) into API messages. Value-object `toString` leaks too (`ProductId[value=…]`) | Info disclosure | [CODE] [RUN] |
| **S-07** | Access and refresh tokens are stored in `localStorage`, readable by any XSS | Token theft | [CODE] `frontend/src/api/client.ts` |
| **S-08** | **Refresh stampede.** Parallel requests that get 401 each call `/auth/refresh` with the same token. Rotation revokes it, the second call trips reuse detection, `revokeByUserId` runs, and the user is logged out of every device | Availability / UX | [CODE] client.ts + `RefreshTokenUseCase` |
| S-09 | No path to provision ADMIN (no seed, no API). Admins are created by hand-edited SQL, with no audit trail | Ops / audit | [CODE] [DB] |
| S-10 | No rate limiting on `/auth/login`, `/auth/register`, or `/auth/refresh`. Account lockout exists, but it enables a targeted lockout DoS | Abuse | [CODE] |
| S-11 | JWT is HS256 with a shared secret (the doc says RS256). No `iss`/`aud` claims. The `local` profile has a default secret, which is acceptable for local use only | Hardening | [CODE] |
| S-12 | No CORS configuration. Works in dev through the Vite proxy, but a separate frontend origin will be blocked or need ad-hoc changes | Deployment | [CODE] |
| S-13 | No route guards in the SPA (no protected routes or admin role gate). The backend enforces access, but the UX leaks pages | UX | [CODE] `frontend/src/routes/index.tsx` |
| S-14 | `docker-compose.yml` and `application-local.yml`/`-test.yml` contain the DB password `dsports123` as a default. Acceptable for local use only. Make sure no prod profile inherits it (`application-prod.yml` correctly has no defaults) | Secret hygiene | [CODE] |

**What works well:** JWT signature validation, refresh-token hashing (SHA-256) with rotation and reuse detection, account lockout, ownership checks on checkout, order, payment, notification, and shipment-by-id, generic login failure messages (no user enumeration through login), stateless sessions, and CSRF disabled appropriately for bearer auth.
