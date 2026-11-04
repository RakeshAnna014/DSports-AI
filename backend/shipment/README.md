# Shipment module (`dsports-shipment`)

One shipment per paid order, courier assignment, tracking and status progression.

| | |
|---|---|
| API | `/api/v1/shipments` (customer read by order, track by number; admin status and courier) |
| Controllers | ShipmentController |
| Tables | `shipments`, `shipment_tracking` (V27, V36) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 8 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Status machine: CREATED, READY_FOR_PICKUP, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, with FAILED and RETURNED branches.
* Created by the outbox after payment succeeds; creation is idempotent per order.

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
