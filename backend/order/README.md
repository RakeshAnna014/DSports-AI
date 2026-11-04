# Order module (`dsports-order`)

Checkout (address, delivery, promotion, validation), order placement and cancellation, admin order management, promotions, the sales feed used by planning, and the co-purchase insight used by recommendations.

| | |
|---|---|
| API | `/api/v1/checkout`, `/api/v1/orders`, `/api/v1/admin/orders`, `/api/v1/admin/sales/lines` (ADMIN), `/api/v1/insights/co-purchases` |
| Controllers | PublicCheckoutController, CheckoutPromotionController, PublicOrderController, AdminOrderController, AdminSalesFeedController, CoPurchaseController |
| Tables | `checkouts`, `checkout_items`, `orders`, `order_items`, `promotions`, `promotion_scopes`, `promotion_redemptions` (V19-V21, V34, V47) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 13 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Status machine (`OrderStatus`): CREATED, PENDING_PAYMENT, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, CANCELLED, REFUNDED; a paid order can only be fulfilled or refunded, never cancelled.
* `PlaceOrderUseCase` is one transaction (order, reservations, cart, checkout, outbox) and rejects a second order for the same checkout.
* Unpaid orders are cancelled by `ExpireUnpaidOrdersJob` in `bootstrap`.
* The sales feed exposes order lines without names, addresses or phone numbers.

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
