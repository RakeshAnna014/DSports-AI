# Shared module (`dsports-shared`)

Ports and types shared by the modules: the outbox port (`OutboxWriter`, `OutboxMessage`, `OutboxMessageTypes`), the transaction runner port and common result and error types.

| | |
|---|---|
| API | none |
| Controllers | none |
| Tables | none (the `outbox_events` table belongs to `bootstrap`, V33) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 0 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Contains no business rules and must not depend on any other module.

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
