# Payment module (`dsports-payment`)

Payment authorisation, capture, cancellation, history, refund requests and the admin refund workflow, behind a gateway port.

| | |
|---|---|
| API | `/api/v1/payments`, `/api/v1/admin/refunds`, `/api/v1/admin/payments/{id}/refunds` (ADMIN) |
| Controllers | PublicPaymentController, AdminRefundController |
| Tables | `payments`, `payment_audit` (V22, V23), `refunds` (V35) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 6 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Only `MockPaymentGateway` is implemented (`payment.mock.mode`, `capture-mode`, `refund-mode`); no real provider is integrated.
* Capture is claim (transaction) then gateway call (no transaction, timeout `app.payment.gateway-timeout`) then completion (transaction); it is safe to retry.
* Create/capture/refund requests are idempotent (`Idempotency-Key` on refund and payment routes).

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
