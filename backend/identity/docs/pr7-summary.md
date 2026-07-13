# PR #7 — Enterprise Exception Handling and API Error Standardization

**Date:** 2026-07-13

## Why Centralized Exception Handling Exists

Before this PR, every thrown exception (domain, validation, unexpected) was handled by Spring Boot's default error handler, which returns an HTML error page or a generic Problem Detail JSON. This creates several problems:

1. **Inconsistent responses** — each error type returns a different shape; clients must parse multiple formats
2. **Information leakage** — default handlers may expose stack traces, internal messages, or framework internals
3. **No machine-readable codes** — clients cannot programmatically distinguish "email already exists" from "invalid email" (both are 400 with different text)
4. **No correlation ID** — debugging production errors requires manually correlating logs with error responses
5. **No validation detail** — Bean Validation errors return generic 400 with no field-level information

A centralized handler solves all five problems in one place.

## Why Controllers Never Catch Exceptions

Controllers are the outermost layer and should remain thin. Their job is to:
- Extract request parameters
- Delegate to application use cases
- Return the response

Catching exceptions in controllers would:
- Duplicate error-handling logic across every endpoint
- Mix HTTP status mapping with business logic
- Make it easy to forget handling an exception type, leading to 500 errors

By using `@RestControllerAdvice`, a single class handles ALL exceptions across ALL controllers. Adding a new endpoint cannot accidentally bypass error handling.

## Why ApiError Exists

`ApiError` is an immutable record that defines the single shape of every error response:

```json
{
  "status": 409,
  "error": "Conflict",
  "code": "DUPLICATE_EMAIL",
  "message": "Email already registered",
  "path": "/api/auth/register",
  "correlationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "timestamp": "2026-07-13T10:30:00Z",
  "validationErrors": null
}
```

Without a standard DTO, each handler method would construct its own map or use Spring's default structure. `ApiError.of(...)` factory method ensures every response has the same required fields.

## How Domain Exceptions Become HTTP Responses

The `GlobalExceptionHandler` maps `IdentityDomainException` → HTTP status via a static `Map<ErrorCode, HttpStatus>`:

| ErrorCode | HTTP Status | Reason |
|-----------|-------------|--------|
| `DUPLICATE_EMAIL` | 409 Conflict | Resource conflict — email is already taken |
| `INVALID_EMAIL` | 400 Bad Request | Client sent an invalid email format |
| `INVALID_PHONE_NUMBER` | 400 Bad Request | Client sent an invalid phone number |
| `INVALID_CUSTOMER_NAME` | 400 Bad Request | Client sent an invalid name |
| `INVALID_STATUS_TRANSITION` | 409 Conflict | Cannot perform the requested status change |
| `MISSING_ROLE` | 400 Bad Request | Required role not provided |
| `DUPLICATE_ROLE` | 409 Conflict | Role already assigned |
| `OAUTH_PROVIDER_ALREADY_LINKED` | 409 Conflict | Provider already linked to another account |
| `MAX_AUTH_PROVIDERS_EXCEEDED` | 409 Conflict | Maximum number of auth providers reached |
| `USER_NOT_FOUND` | 404 Not Found | Requested user does not exist |
| `INVALID_PASSWORD` | 401 Unauthorized | Password does not match |
| `ACCOUNT_LOCKED` | 423 Locked | Account is temporarily locked |
| `GENERIC` | 500 Internal Server Error | Unexpected domain error |
| `VALIDATION_ERROR` | 400 Bad Request | Bean validation failure |
| `INTERNAL_ERROR` | 500 Internal Server Error | Non-domain unexpected error |

The mapping is explicit, auditable, and easy to extend.

## How Correlation ID Helps Production Debugging

The `CorrelationIdFilter` (existing) reads `X-Correlation-Id` from the request header or generates a UUID, adds it to the response header, and puts it in the Reactor context.

The `GlobalExceptionHandler` reads the correlation ID from the request headers and includes it in every error response. This lets operations:
1. Find the exact request in logs using the correlation ID
2. Trace the error across services (if the caller propagates the header)
3. Correlate frontend errors with backend logs

## Architecture

### New Package Structure

```
backend/
  shared/
    src/main/java/com/dsports/shared/
      api/
        ApiError.java                          ← Immutable error response DTO
        ApiError.ValidationError.java           ← Nested record for field-level errors
  bootstrap/
    src/main/java/com/dsports/exception/
      GlobalExceptionHandler.java              ← @RestControllerAdvice (central handler)
    src/test/java/com/dsports/exception/
      GlobalExceptionHandlerTest.java          ← 11 unit tests
      ExceptionTestController.java             ← Test controller (for future integration tests)
  identity/
    src/main/java/com/dsports/identity/domain/exception/
      ErrorCode.java                           ← Extended with USER_NOT_FOUND, INVALID_PASSWORD, ACCOUNT_LOCKED, VALIDATION_ERROR, INTERNAL_ERROR
      IdentityDomainException.java             ← Unchanged
```

### Exception Hierarchy

```
RuntimeException
  └── IdentityDomainException           ← Domain exceptions from identity module
      └── ErrorCode                     ← Machine-readable code enum
  └── WebExchangeBindException          ← @Valid request body failures (Spring)
  └── ConstraintViolationException      ← @Validated parameter failures (Jakarta)
  └── RuntimeException                  ← Any unhandled exception (500)
```

All are handled by `GlobalExceptionHandler`.

### Error Response Example

**Duplicate Email (409 Conflict):**
```json
{
  "status": 409,
  "error": "Conflict",
  "code": "DUPLICATE_EMAIL",
  "message": "Email already registered",
  "path": "/api/auth/register",
  "correlationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "timestamp": "2026-07-13T10:30:00Z",
  "validationErrors": null
}
```

**Validation Failure (400 Bad Request):**
```json
{
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "path": "/api/auth/register",
  "correlationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "timestamp": "2026-07-13T10:30:00Z",
  "validationErrors": [
    { "field": "email", "message": "must be a valid email" },
    { "field": "name", "message": "must not be blank" }
  ]
}
```

**Unexpected Error (500 Internal Server Error):**
```json
{
  "status": 500,
  "error": "Internal Server Error",
  "code": "INTERNAL_ERROR",
  "message": "An unexpected error occurred",
  "path": "/api/orders",
  "correlationId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "timestamp": "2026-07-13T10:30:00Z",
  "validationErrors": null
}
```

## Logging Behavior

| Status Range | Log Level | Stack Trace |
|---|---|---|
| 4xx (client errors) | WARN | No stack trace |
| 5xx (server errors) | ERROR | Full stack trace |

The handler never logs the exception message for unexpected errors in the response body (to avoid leaking sensitive data). The internal exception message IS logged at ERROR level with stack trace for debugging.

## Future Extension Points

### Problem Details (RFC 7807 / RFC 9457)
Spring Boot 3.4 supports `ProblemDetail` as a standard error format. If we want to adopt RFC 9457 in the future, we can:
1. Make `ApiError` extend `ProblemDetail` or add a `type` field
2. Add a `type` URI field pointing to error documentation
3. Add an `instance` field pointing to the specific error occurrence

### Localization (i18n)
`ApiError.message` is currently hardcoded in English. To support localization:
1. Add a `Locale` parameter to `ApiError.of()`
2. Use Spring's `MessageSource` to resolve messages from `ErrorCode`
3. The `message` field becomes locale-aware without changing the `code` field (which remains machine-readable)

### Error Catalog
Create a centralized error catalog (`docs/error-catalog.md`) that documents every `ErrorCode`, its HTTP mapping, typical scenarios, and remediation guidance for API consumers.

### Security Event Logging
Add dedicated security logging for `401 Unauthorized` and `423 Locked` responses to feed SIEM systems, distinct from general application logging.
