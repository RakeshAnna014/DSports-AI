# Notification module (`dsports-notification`)

In-app notifications created from outbox work (order placed, confirmed, shipped, delivered, cancelled, payment succeeded or failed, refund completed).

| | |
|---|---|
| API | `/api/v1/notifications` |
| Controllers | NotificationController |
| Tables | `notifications`, `notification_templates` (V26, V38) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 7 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Unique per user, type and reference, so a redelivered outbox message creates no duplicate.
* Only the in-app channel is implemented.

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
