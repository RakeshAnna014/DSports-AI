# 05 — Reactive Architecture, Error Handling & Observability

Evidence tags: **[RUN]** · **[CODE]** · **[DB]** · **[INF]**. See [README](README.md).

## 1. Reactive pipeline review

The scan covered `.block()`, `.subscribe()`, `subscribeOn`/`publishOn`, timeouts, retries, and blocking work on event-loop threads. **There are no `.block()` calls in production code, and no JDBC is used at runtime** (JDBC is used only by Flyway at startup). Good baseline.

| ID | Issue | Location | Impact | Evidence |
|---|---|---|---|---|
| R-01 | **Fire-and-forget `.subscribe()` inside `@EventListener`** | `PaymentSucceededEventHandler`, `DomainEventNotificationHandler` (8 listeners), `OrderConfirmedEventListener` | Work is detached from the request: no back-pressure, no Reactor context (correlation ID lost), errors only logged, lost on shutdown or crash, and listeners may run before the publisher's transaction commits | [CODE] |
| R-02 | **BCrypt hashing and verification on Netty event-loop threads** | `RegisterUserUseCase`, `LoginUseCase` → `BCryptPasswordEncoderAdapter` | A CPU-bound call of about 50–300 ms per request starves the event loop under login load. Wrap it in `Mono.fromCallable(..).subscribeOn(Schedulers.boundedElastic())` (or a dedicated bounded scheduler) | [CODE] |
| R-03 | **No timeouts** on outbound "gateway" calls (payment, courier, notification providers) | `CreatePaymentUseCase`, `CapturePaymentUseCase`, `RefundPaymentUseCase`, `CreateShipmentUseCase`, notification use cases | Harmless with mocks. With real providers, a hung call holds a connection-pool slot and the request open indefinitely. The architecture doc promises "fail-fast with timeouts" | [CODE] |
| R-04 | Domain events published with `Mono.fromRunnable(publish).subscribeOn(boundedElastic())` | catalog, inventory, and pricing repository adapters | Acceptable (listeners are synchronous), but the pattern is inconsistent with other modules that publish inline. Pick one | [CODE] |
| R-05 | Transactions are scoped in **repository adapters** (`TransactionalOperator` per `save`), not in use cases | cart, catalog, inventory, pricing, shipment adapters | Multi-aggregate use cases (place order, post-payment) are not atomic. The transaction belongs at the use-case or application-service boundary | [CODE] |
| R-06 | `Mono.when(list)` in `ValidateCheckoutUseCase` runs stock checks concurrently and errors on the first failure. Fine. `Flux.flatMap` stock checks elsewhere are unbounded in concurrency, which is fine at ≤50 items | | Informational | [CODE] |
| R-07 | Scheduled refresh-token cleanup uses `.subscribe()` from `@Scheduled`. Acceptable for a nightly job, but it has no error handler or metrics | `ExpiredRefreshTokenCleanupJob` | Low | [CODE] |
| R-08 | `ResponseStatusException` is thrown synchronously from `extractUserId(...)` (outside the reactive chain). It works, but it is then swallowed by the catch-all handler and becomes a 500 | several controllers | Covered by E-02 | [CODE] |

**Recommended pattern for cross-module side effects** (needed before AI agents rely on order state):
A transactional outbox (an `outbox_events` table written in the same transaction as the aggregate) plus a single reactive poller or dispatcher with retry and dead-letter handling. Alternatively, as a smaller first step, use `@TransactionalEventListener(phase = AFTER_COMMIT)` with listeners that return `Mono` and are subscribed with error handling and retry. The outbox is what the existing ROADMAP's "Kafka later" will need anyway.

## 2. Error handling

### Current state

| ID | Finding | Evidence |
|---|---|---|
| E-01 | **HTTP status is always 200 for handled errors.** The status appears only inside the body | [RUN] |
| E-02 | No handlers for `ServerWebInputException` (bad UUID/JSON/enum/param), `ResponseStatusException`, `AccessDeniedException`, `AuthenticationException`, `DataIntegrityViolationException`, `OptimisticLockingFailureException`, `DuplicateKeyException`, or `TimeoutException`. All of them become `500 INTERNAL_ERROR` | [RUN] |
| E-03 | Three different error shapes: `ApiError` (advice), `{"message": "..."}` (401 entry point and the identity `ErrorResponse` DTO on login/refresh), and plain text `Access Denied` (403) | [RUN] |
| E-04 | `correlationId` in `ApiError` is read from the *request* header, so it is `null` whenever the server generated the ID | [RUN] |
| E-05 | Each module has its own `*ErrorCode` enum plus a hand-maintained `Map<ErrorCode, HttpStatus>` in one 575-line advice. Unmapped codes silently become 500 or 400 (the defaults differ per module). This is a duplication and drift risk | [CODE] |
| E-06 | Domain messages are passed straight to clients, including raw DB messages in `GENERIC` codes (see security issue S-06) | [CODE] |
| E-07 | The `spring.jackson.default-problem-detail: true` property in `application.yml` is not a Spring Boot property and has no effect | [CODE] |

### Proposed standard error contract (for review)

> **Implemented 2026-10-06** (see [09 §3.3](09-remediation-log.md#33-p0-4--error-handling-and-the-api-error-contract)). E-01 to E-04 and E-06 are resolved. The table below is the live contract. Notes:
> - 422 is deliberately not used: validation is 400 and business conflicts are 409.
> - Codes implemented for framework errors: `INVALID_PARAMETER`, `MISSING_PARAMETER`, `MALFORMED_REQUEST`, `AUTHENTICATION_REQUIRED`, `ACCESS_DENIED`, `ROUTE_NOT_FOUND`, `METHOD_NOT_ALLOWED`, `OPTIMISTIC_LOCKING_CONFLICT`, `DATA_CONFLICT`, `INTERNAL_ERROR`.
> - Still open: 429 (no rate limiting yet) and 503/504 (no timeouts yet), and E-05 (per-module status maps are still hand-maintained).
> - The frontend type is `ApiError` in `frontend/src/api/errors.ts`.

Keep the existing `ApiError` shape (the frontend already reads `response.data.message`) and make it authoritative:

```json
{
  "status": 409,
  "error": "Conflict",
  "code": "INSUFFICIENT_STOCK",
  "message": "Only 3 units of 'MRF Cricket Bat - Virgin' are available.",
  "path": "/api/v1/cart/items",
  "correlationId": "4f1c…",
  "timestamp": "2026-10-05T09:35:22.629Z",
  "validationErrors": [ { "field": "quantity", "message": "must be greater than 0" } ]
}
```

Rules:

1. The HTTP status **must** equal `status`. Every handler returns `ResponseEntity<ApiError>`.
2. `code` is a stable, documented, SCREAMING_SNAKE identifier and is the client's branching key. `message` is human-readable and may change.
3. `correlationId` is always present (the generated ID, echoed in the `X-Correlation-Id` header).
4. 5xx responses never include exception messages, SQL, class names, or stack traces. Log them server-side together with the correlationId.
5. The same shape is used for 401 (the security entry point), 403 (an access-denied handler), and 404 for unknown routes.

| Category | HTTP | `code` examples |
|---|---|---|
| Validation (bean / input binding) | 400 | `VALIDATION_ERROR`, `MALFORMED_REQUEST`, `INVALID_PARAMETER` |
| Authentication | 401 | `AUTHENTICATION_REQUIRED`, `TOKEN_EXPIRED`, `INVALID_CREDENTIALS` |
| Authorization / ownership | 403 | `ACCESS_DENIED`, `*_NOT_OWNED_BY_USER` |
| Not found | 404 | `*_NOT_FOUND`, `ROUTE_NOT_FOUND` |
| Conflict / state / concurrency | 409 | `INVALID_STATUS_TRANSITION`, `DUPLICATE_*`, `OPTIMISTIC_LOCKING_CONFLICT`, `INSUFFICIENT_STOCK` |
| Expired | 410 | `CHECKOUT_EXPIRED` |
| Locked | 423 | `ACCOUNT_LOCKED` |
| Rate limited | 429 | `RATE_LIMITED` |
| Downstream failure | 502 | `PAYMENT_GATEWAY_ERROR`, `COURIER_ERROR`, `PROVIDER_ERROR` |
| Downstream timeout / retries exhausted | 504 / 503 | `UPSTREAM_TIMEOUT`, `SERVICE_UNAVAILABLE` |
| Unexpected | 500 | `INTERNAL_ERROR` (generic message only) |

Implementation suggestion (for review, not done): introduce `shared.domain.DomainException(code, httpStatusHint)`, or have each `*ErrorCode` enum carry its own status, so one `@ExceptionHandler(DomainException.class)` replaces the 12 per-module maps.

## 3. Observability baseline

| Capability | Status | Evidence / notes |
|---|---|---|
| Correlation ID header | 🟡 | `CorrelationIdFilter` generates or echoes `X-Correlation-Id` and writes it into the Reactor `Context` |
| Correlation ID in logs | 🔴 | The log pattern includes `[%X{correlationId}]`, but **request logs show `[]`** [RUN]. Nothing bridges the Reactor Context to MDC: no `ContextRegistry` `ThreadLocalAccessor` is registered, and `Hooks.enableAutomaticContextPropagation()` is not called. `spring.reactor.context-propagation=enable` alone does not populate a plain `Context.of("correlationId")` key into MDC |
| Correlation ID in error body | 🔴 | null (E-04) |
| Request ID | ⬜ | Only the correlation ID. Acceptable if documented as the same thing |
| Structured (JSON) logging | ⬜ | Plain text pattern. Spring Boot 3.4 supports `logging.structured.format.console=ecs` natively |
| Error logging | 🟡 | 4xx logged at WARN and 5xx at ERROR with stack. The catch-all also logs expected client errors (bad UUID) as ERROR |
| Business-event logging | 🟡 | INFO logs on order placed, payment created/captured, and shipment created. Nothing consistent (no event name, orderId, or userId fields) |
| Health | ✅ | `/actuator/health` with liveness and readiness groups and an R2DBC indicator [RUN] |
| Readiness/liveness probes | ✅ | `management.endpoint.health.probes.enabled=true` |
| Metrics | 🟡 | `/actuator/metrics` exposed (authenticated). No Prometheus registry and no custom business metrics (orders placed, payment failures, stock-outs) |
| Distributed tracing | ⬜ | No `micrometer-tracing` or OpenTelemetry bridge. Needed for AI-call tracing later |
| Audit trail | 🟡 | `payment_audit` table only. No audit for admin actions (price changes, stock adjustments, order status overrides) |

**Minimum observability baseline before the AI layer:** (1) the correlation ID reliably in MDC, response headers, and error bodies; (2) structured JSON logs; (3) `micrometer-tracing-bridge-otel` with W3C trace context; (4) a Prometheus endpoint plus about 6 business counters; (5) an admin-action audit log.
