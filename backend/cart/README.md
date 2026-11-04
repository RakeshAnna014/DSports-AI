# Cart module (`dsports-cart`)

One active cart per customer: add, update and remove items; the cart is marked checked-out when an order is placed and completed when payment succeeds.

| | |
|---|---|
| API | `/api/v1/cart` |
| Controllers | PublicCartController |
| Tables | `carts`, `cart_items` (V18; `COMPLETED` status V28) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 2 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* The user id comes only from the authentication, never from the request.

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
