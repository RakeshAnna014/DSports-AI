# Wishlist module (`dsports-wishlist`)

A customer's wishlist, with move-to-cart.

| | |
|---|---|
| API | `/api/v1/wishlist` |
| Controllers | PublicWishlistController |
| Tables | `wishlists`, `wishlist_items` (V25) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 0 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Owner-scoped. This module has no unit tests of its own (0 test classes); it is exercised by the frontend and e2e journeys.

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
