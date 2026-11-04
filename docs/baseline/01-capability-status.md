# 01 — Capability Status & Traceability

Evidence tags: **[RUN]** runtime-verified · **[CODE]** code-verified · **[DB]** database-verified · **[INF]** inferred. See [README](README.md).

> **Changes since the audit (2026-10-06, verified at runtime; see [09](09-remediation-log.md)):**
> - **Order placement:** 🔴 → ✅. Orders and order items persist, and a duplicate submit returns 409.
> - **Post-payment processing:** 🔴 → 🟡. Order CONFIRMED, cart COMPLETED, and stock decremented after capture, but still fire-and-forget (P1-8).
> - **Inventory in the purchase flow:** 🔴 → 🟡. Stock is now deducted on payment, but there is still no reservation at order time (P1-2).
> - **Cart lifecycle:** ✅ ACTIVE → CHECKED_OUT → COMPLETED.
> - **Wishlist, Reviews, Shipments, Notifications:** 🚫 → deployable (migrations apply, app boots).
>   - Reviews: creation always fails (P1-12).
>   - Shipments: auto-created with an `N/A` address.
> - **Errors:** correct HTTP statuses everywhere.
> - **UI payment:** still never captures (P1-7).

## Status definitions

| Status | Meaning |
|---|---|
| ✅ **Implemented** | Backend, persistence, and UI exist and the happy path works |
| 🟡 **Partial** | Exists but has functional gaps or defects that break part of the workflow |
| 🔴 **Broken** | Exists but the primary happy path fails |
| 🧪 **Mocked** | Works only against an in-process mock (no real provider) |
| 🚫 **Not deployable** | Code exists but the application cannot start with it, or its tables do not exist |
| ⬜ **Missing** | No implementation (an empty module or nothing at all) |
| 📝 **Planned only** | Mentioned in the docs or roadmap only |

## Module inventory

| Module | Main source files | Tables (migration) | Tracked in git? | Notes |
|---|---|---|---|---|
| shared | 4 | — | yes | `ApiError`, `BaseEntity`, `DomainEvent`, `ValueObject` |
| identity | 119 | customers, customer_roles, customer_auth_providers, refresh_tokens, customer_addresses (V1–V6) | yes | |
| catalog | 142 | sports, categories, brands, products, product_images (V7–V12, V15) | yes | |
| inventory | 53 | inventory (V13, V16) | yes | uncommitted change: `fulfillReservation()` |
| pricing | 54 | prices (V14, V17) | yes | |
| cart | 47 | carts, cart_items (V18) | yes | |
| order (checkout + order) | 103 | checkouts, checkout_items, orders, order_items (V19, V20, V21) | yes | |
| payment | 55 | payments, payment_audit (V22, V23) | yes | V23 not applied in the dev DB |
| review | 44 | reviews, review_helpful_votes (V24) | **no** | V24 not applied |
| shipment | 53 | shipments, shipment_tracking (**V20 — clashes**) | **no** | |
| notification | 45 | notifications, notification_templates (**V19 — clashes**) | **no** (src) | |
| wishlist | 43 | wishlists, wishlist_items (V25) | **no** | V25 not applied |
| billing / returns / franchise / admin / ai | 0 | — | pom only | Empty placeholders |

## Capability status matrix

| Capability | Backend | DB | Frontend | Tests | Status | Key gaps |
|---|---|---|---|---|---|---|
| Registration | `POST /api/auth/register` | customers | RegisterPage | none at use-case level | 🟡 | Errors return HTTP 200 [RUN]. BCrypt runs on the event loop [CODE]. Check-then-insert race backed by a unique index, but the violation surfaces as a 500 [INF] |
| Login / JWT | `POST /api/auth/login` | refresh_tokens | LoginPage | LoginUseCaseTest | 🟡 | Lockout implemented. Login reuses an application command as its API DTO. HS256, not the documented RS256 |
| Refresh token rotation | `POST /api/auth/refresh` | refresh_tokens | axios interceptor | RefreshTokenUseCaseTest | 🟡 | Rotation and reuse detection work, but concurrent 401s in the SPA each refresh with the same token, so the second attempt triggers reuse detection and revokes all sessions [CODE] |
| Logout | `POST /api/auth/logout` | refresh_tokens | authStore | LogoutUseCaseTest | ✅ | |
| Role management | — | customer_roles | — | — | ⬜ | No API or seed to grant ADMIN. Admins can only be created by hand in SQL |
| OAuth | stub gateway | customer_auth_providers | — | — | 📝 | `OAuthProviderGatewayStub` |
| Customer profile | `GET/PUT /api/customers/me` | customers | ProfilePage | 2 use-case tests + controller test | ✅ | |
| Address management | `/api/customers/me/addresses` CRUD + default | customer_addresses | Inside CheckoutPage only | none | 🟡 | An invalid `type` returns a 500 [RUN]. No address management in Profile. Name and phone are not captured, so order snapshots have empty `fullName`/`phone` [CODE] |
| Sports / Categories / Brands | public GET + admin CRUD/archive | sports, categories, brands | filters | domain + use-case tests | ✅ | No admin UI |
| Products + images | public list/detail + admin CRUD, images | products, product_images | ProductsPage, ProductDetailPage | yes | 🟡 | Public detail returns ARCHIVED products [CODE]. No FKs from products to brand/category/sport [DB] |
| Product search | `GET /api/catalog/products?brandId&categoryId&sportId&sortBy&page&size` | products | ProductFilters | — | 🟡 | **No keyword search.** No total count or page metadata. An invalid `sortBy` returns a 500 [RUN] |
| Product variants / attributes | — | — | — | — | ⬜ | The architecture documents variants. None exist; cart and order work at the product level |
| Inventory (admin ops) | `/api/admin/inventory` create/stock-in/out/reserve/release/adjust | inventory | — | 2 tests (**3 failing**) | 🟡 | No CHECK constraints. Multi-warehouse rows with no allocation strategy |
| Inventory in the purchase flow | cross-module ports | inventory | — | none | 🔴 | Nothing reserves stock. Availability ignores `reserved_quantity`. Post-payment deduction errors are swallowed [CODE] |
| Pricing | admin draft/schedule/activate/archive, public GET | prices | price display | 8 test classes | 🟡 | Consumers ignore `effective_from/to` and currency. The public API exposes DRAFT and SCHEDULED prices [CODE] |
| Offers / discounts | — | `discount_amount` columns (always 0) | — | — | ⬜ | |
| Cart | `/api/v1/cart` | carts, cart_items | CartPage | CartTest only | 🟡 | Stock check ignores quantity already in the cart. Prices are frozen at add time. `COMPLETED` is forbidden by a DB CHECK [DB] |
| Guest cart / merge | — | — | — | — | ⬜ | Cart requires authentication |
| Wishlist | `/api/v1/wishlist` incl. move-to-cart | wishlists (V25) | WishlistPage | none | 🚫 | Untracked, migration unapplied, no tests |
| Checkout | `/api/v1/checkout` create/address/delivery/validate/cancel | checkouts, checkout_items | CheckoutPage | 3 tests (**1 failing**) | 🟡 | Price not revalidated. Tax (18%) and delivery charges hard-coded in the domain. Checkout never moves past VALIDATED after an order |
| Order placement | `POST /api/v1/orders` | orders, order_items | CheckoutPage → OrderSuccess | **none** | 🔴 | **Always 500** (order_items INSERT/UPDATE bug) [RUN]. Not transactional. Order number = `count()+1` |
| Order history / detail | `GET /api/v1/orders[/id]` | orders | OrdersPage, OrderDetailPage | none | 🟡 | No pagination |
| Order cancel | `PUT /api/v1/orders/{id}/cancel` | orders | OrderDetailPage | none | 🟡 | Does not release inventory, cancel the payment, or trigger a refund. `OrderCancelledEvent` is consumed only by notifications [CODE] |
| Admin order management | `GET/PUT /api/v1/admin/orders/{id}` | orders | API client only, no page | none | 🟡 | No list/search. A non-admin caller gets 500 instead of 403 [RUN] |
| Payment | `/api/v1/payments` create/capture/get/history/cancel/refund | payments, payment_audit | PaymentPage | 4 test classes | 🧪🟡 | Mock gateway. UI never captures. Several payments per order are allowed |
| Post-payment processing | `PaymentSucceededEventHandler` | orders, inventory, carts | — | **none** | 🔴 | Fire-and-forget `.subscribe()`. Errors swallowed. Cart completion violates the DB CHECK |
| Billing / invoice | — | — | — | — | ⬜ | Empty module |
| Notifications | in-app history/detail/retry; 8 event listeners | notifications (V19 clash) | NotificationCenter, pages | 7 test classes | 🧪🚫 | Mock email/SMS/push. Not deployable |
| Delivery / shipment | `/api/v1/shipments` create/get/track/courier/status | shipments (V20 clash) | ShipmentPage, TrackingPage | 7 test classes | 🧪🚫 | Not deployable. Auto-create uses an `"N/A"` address. Tracking exposes the address to any logged-in user |
| Returns | — | — | — | — | ⬜ | Empty module |
| Refunds | `POST /api/v1/payments/{id}/refund` | payments | PaymentHistory | none | 🟡 | Customer self-service with no return linkage (security issue S-01) |
| Reviews & ratings | `/api/v1/reviews` | reviews (V24) | ProductReviewsSection | 3 test classes | 🚫 | Not deployable. Reading reviews requires login. No unique (product, user) constraint |
| Franchise / store inventory | — | — | — | — | ⬜ | Empty module |
| Admin dashboard / reporting | — | — | **no admin UI** | — | ⬜ | Admin capability exists only as raw APIs |
| AI | — | — | — | — | 📝 | Correctly not started |

## Requirement traceability matrix (order-to-cash core)

Requirements come from `ROADMAP.md` Sprints 1–4 and `architecture.md` §4. "Tests" means automated tests that exercise the behaviour, not just the class.

| Requirement | API | Controller → Use case | Repository → Table | UI | Tests | Status |
|---|---|---|---|---|---|---|
| User registers with email/password | `POST /api/auth/register` | AuthController → RegisterUserUseCase | UserR2dbcRepositoryAdapter → customers | RegisterPage | ⚠ none for RegisterUserUseCase | 🟡 |
| User logs in and receives a JWT | `POST /api/auth/login` | AuthController → LoginUseCase | → customers, refresh_tokens | LoginPage | LoginUseCaseTest | 🟡 |
| Refresh token rotation | `POST /api/auth/refresh` | → RefreshTokenUseCase | → refresh_tokens | client.ts interceptor | RefreshTokenUseCaseTest | 🟡 |
| Customer manages addresses | `/api/customers/me/addresses` | AddressController → Create/Update/Delete/SetDefault | SpringR2dbcAddressRepository → customer_addresses | CheckoutPage (inline) | ⚠ none | 🟡 |
| Browse products with filters | `GET /api/catalog/products` | PublicCatalogController → GetProductsUseCase | ProductR2dbcRepositoryAdapter → products, product_images | ProductsPage | PublicCatalogControllerTest (sports/categories/brands only) | 🟡 |
| Product details | `GET /api/catalog/products/{id}` | → GetProductUseCase | → products | ProductDetailPage | ⚠ none | 🟡 |
| Add to cart with stock/price check | `POST /api/v1/cart/items` | PublicCartController → AddToCartUseCase | CartR2dbcRepositoryAdapter → carts, cart_items | ProductDetailPage, CartPage | ⚠ CartTest (domain only) | 🟡 |
| Start checkout from cart | `POST /api/v1/checkout` | PublicCheckoutController → CreateCheckoutUseCase | CheckoutR2dbcRepositoryAdapter → checkouts | CheckoutPage | CreateCheckoutUseCaseTest | 🟡 |
| Select address and delivery | `POST /checkout/{id}/address`, `/delivery` | → SelectShippingAddress / SelectDeliveryMethod | → checkouts | CheckoutPage | ⚠ none | ✅ [RUN] |
| Validate checkout | `POST /checkout/{id}/validate` | → ValidateCheckoutUseCase | → checkouts | CheckoutPage | ValidateCheckoutUseCaseTest (**failing**) | 🟡 |
| Place order (idempotent) | `POST /api/v1/orders` | PublicOrderController → PlaceOrderUseCase | OrderR2dbcRepositoryAdapter → orders, order_items | CheckoutPage | ⚠ **none** | 🔴 [RUN] |
| Reserve inventory on order | (internal) | OrderModuleConfig.inventoryReservationPort | — (check only) | — | ⚠ none | 🔴 |
| Pay for order | `POST /api/v1/payments` | PublicPaymentController → CreatePaymentUseCase | → payments | PaymentPage | CreatePaymentUseCaseTest | 🧪🟡 |
| Capture → confirm order | `POST /payments/{id}/capture` → event | → CapturePaymentUseCase → PaymentSucceededEventHandler | → payments, orders, inventory, carts | **not called** | ⚠ none | 🔴 |
| Ship order | `POST /api/v1/shipments`, OrderConfirmed listener | ShipmentController → CreateShipmentUseCase | → shipments | ShipmentPage | CreateShipmentUseCaseTest | 🚫 |
| Notify customer | (events) | DomainEventNotificationHandler → CreateNotificationUseCase | → notifications | NotificationCenter | CreateNotificationUseCaseTest | 🚫 |
| Invoice | — | — | — | — | — | ⬜ |
| Return and refund | refund endpoint only | RefundPaymentUseCase | → payments | PaymentHistoryPage | ⚠ none | 🟡 / ⬜ |
