# Inventory module (`dsports-inventory`)

Stock levels per product and the stock reservations that tie stock to an order.

| | |
|---|---|
| API | public `GET /api/inventory/{productId}`; admin `/api/admin/inventory/**` (ADMIN: stock-in, stock-out, reserve, release, adjust, reorder level) |
| Controllers | PublicInventoryController, AdminInventoryController |
| Tables | `inventory` (V13), `inventory_reservations` (V32) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 5 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Placing an order reserves stock inside the order transaction; capture fulfils the reservation; cancellation or expiry releases it.
* The public endpoint exposes sellable quantity and status only.

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
