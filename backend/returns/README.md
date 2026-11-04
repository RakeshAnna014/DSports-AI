# Returns module (`dsports-returns`)

Return eligibility and return requests for delivered orders; administrators approve or reject.

| | |
|---|---|
| API | `GET /api/v1/orders/{orderId}/returns/eligibility`, `/api/v1/returns`, `/api/v1/admin/returns` (ADMIN) |
| Controllers | ReturnController, AdminReturnController |
| Tables | `return_requests` (V40) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 4 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Eligibility is computed on the server: owned order, delivered, inside the window, quantity not already requested.
* Status: REQUESTED, APPROVED, REJECTED. Approved quantities feed the planning sales convention.
* Creation accepts an `Idempotency-Key`.

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
