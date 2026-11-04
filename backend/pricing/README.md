# Pricing module (`dsports-pricing`)

Product prices and their lifecycle; the public price read used by the storefront and the AI service.

| | |
|---|---|
| API | public `GET /api/prices/**`; admin `/api/admin/prices/**` (ADMIN) |
| Controllers | PublicPriceController, AdminPriceController |
| Tables | `prices` (V14; seed V17, V42) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 12 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Admin price lifecycle: update, activate, schedule, archive. Only the active price is public.
* The AI service never sets or changes a price; it reads this API only.

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
