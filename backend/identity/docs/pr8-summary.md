# PR #8 — JWT Authentication and Stateless Security

**Date:** 2026-07-14

**Review-Enhanced:** 2026-07-14

## Authentication vs Authorization

**Authentication** ("who are you?") verifies identity. The user provides credentials (email + password), the system validates them, and issues a JWT access token.

**Authorization** ("what can you do?") determines access rights. The JWT carries the user's roles (CUSTOMER, ADMIN, etc.), and Spring Security checks these against the requested resource.

In this PR, we implement authentication. Authorization is enforced by `SecurityConfig` via the role information embedded in the JWT.

## Why JWT is Stateless

A JWT access token is self-contained — all information needed to verify the user's identity is inside the token itself. The server does NOT need to:
- Query a database on every request to validate the session
- Maintain server-side session state
- Scale session stores across instances

This enables horizontal scaling on Cloud Run / Kubernetes — any instance can verify any token without shared session state.

## Why Refresh Token is Stored (But Access Token is NOT)

| Aspect | Access Token | Refresh Token |
|--------|-------------|---------------|
| **Stored server-side?** | No | Yes (in `refresh_tokens` table) |
| **Format** | Signed JWT (HS256) | Opaque UUID string |
| **Stored format** | N/A | SHA-256 hash |
| **Lifetime** | Short (15 min) | Long (7 days) |
| **Revocable?** | No (stateless) | Yes (server-side toggle) |

**Access Token is not stored** because that would defeat the purpose of stateless authentication. Every request would need a database lookup, eliminating the scalability benefit.

**Refresh Token is stored as a SHA-256 hash** to protect against database breaches. The raw token is only known to the client. On lookup, the incoming token is hashed and compared against the stored hash.

**Refresh Token is stored** because it must be revocable. When a user logs out, the refresh token is marked `revoked = true` in the database. Even if an attacker obtains the refresh token after logout, it cannot be used. This is the **token rotation** strategy — every refresh operation issues a new refresh token and revokes the old one.

## Token Rotation

When a refresh token is used to obtain new tokens:

1. Old refresh token is **revoked** (marked as revoked in the database)
2. New access token and refresh token are **issued**
3. If a revoked refresh token is presented, ALL refresh tokens for that user are revoked (breach detection)

This limits the window of vulnerability. If a refresh token is stolen, it becomes invalid after the legitimate user refreshes their tokens.

## Session Strategy

Multiple concurrent sessions (devices) are supported via independent refresh tokens. Each login creates a new refresh token. Logout only revokes the specific token presented, leaving other sessions intact.

This is the recommended approach — users can be logged in on multiple devices simultaneously.

## Logout Strategy

Logout marks the specific refresh token as revoked. The authenticated user's ID (from the JWT) is verified against the refresh token's owner before revocation, preventing one user from revoking another's session.

```
POST /api/auth/logout
Body: { "refreshToken": "..." }
Response: 204 No Content
```

## Security Architecture

```
                    ┌─────────────────────────────────────────────────┐
                    │              Client (Frontend)                   │
                    │    Stores access_token (memory/httpOnly cookie)  │
                    │    Stores refresh_token (secure httpOnly cookie) │
                    └──────────────┬──────────────────────────────────┘
                                   │
                    Authorization: Bearer <access_token>
                                   │
                                   ▼
                    ┌─────────────────────────────────────────────────┐
                    │           SecurityWebFilterChain                 │
                    │  ┌───────────────────────────────────────────┐   │
                    │  │  Public endpoints (permitAll):            │   │
                    │  │  /api/auth/register                      │   │
                    │  │  /api/auth/login                         │   │
                    │  │  /api/auth/refresh                       │   │
                    │  │  /actuator/health                        │   │
                    │  │  /swagger-ui/**, /api-docs/**            │   │
                    │  └───────────────────────────────────────────┘   │
                    │  All other endpoints → authenticated             │
                    └──────────────┬──────────────────────────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────────────────────────────┐
                    │       JwtAuthenticationConverter                 │
                    │  Extracts "Bearer xxx" from Authorization header│
                    │  Creates JwtAuthenticationToken(token)          │
                    └──────────────┬──────────────────────────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────────────────────────────┐
                    │       JwtAuthenticationManager                   │
                    │  Calls JwtTokenProvider.validate(token)          │
                    │  Extracts userId + roles from claims             │
                    │  Creates UsernamePasswordAuthenticationToken     │
                    └──────────────┬──────────────────────────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────────────────────────────┐
                    │       ReactiveSecurityContextHolder              │
                    │  Sets SecurityContext for the current request    │
                    └─────────────────────────────────────────────────┘
```

### Login Flow

```
POST /api/auth/login
Body: { "email": "...", "password": "..." }

Controller
  │
  ├── LoginUseCase.execute(LoginUserCommand)
  │     ├── userRepository.findByEmail(email)          ──→ customers table
  │     ├── Check user status (locked, disabled, deleted)
  │     ├── passwordEncoder.matches(password, hash)
  │     ├── user.updateLastLogin()
  │     ├── userRepository.save(user)
  │     ├── tokenProvider.generateAccessToken(user)     ──→ Signed JWT
  │     ├── tokenProvider.generateRefreshToken()        ──→ Opaque UUID
  │     ├── RefreshToken.create(userId, token, expiry)
  │     └── refreshTokenRepository.save(refreshToken)   ──→ refresh_tokens table
  │
  └── Return { accessToken, refreshToken, userId, email, roles }
```

### Refresh Flow

```
POST /api/auth/refresh
Body: { "refreshToken": "..." }

Controller
  │
  ├── RefreshTokenUseCase.execute(RefreshTokenCommand)
  │     ├── refreshTokenRepository.findByToken(token)   ──→ refresh_tokens table
  │     ├── Check revoked, expired
  │     ├── userRepository.findById(userId)
  │     ├── Revoke old refresh token
  │     ├── Generate new access token + refresh token
  │     ├── Persist new refresh token
  │     └── Return { accessToken, refreshToken }
```

## Sequence Diagram

```
Client                  AuthController          LoginUseCase            DB
  │                          │                      │                   │
  │──── POST /api/auth/login─│                      │                   │
  │     {email, password}    │                      │                   │
  │                          │──execute(command)───▶│                   │
  │                          │                      │──SELECT user─────▶│
  │                          │                      │◄──user data──────│
  │                          │                      │──verify password──│
  │                          │                      │──generate JWT─────│
  │                          │                      │──generate UUID────│
  │                          │                      │──INSERT refresh──▶│
  │                          │                      │◄──done───────────│
  │                          │◄──LoginResult────────│                   │
  │◄─200 {access,refresh}────│                      │                   │
  │                          │                      │                   │
  │──── POST /api/auth/refresh─────────────────────▶│                   │
  │     {refreshToken}       │                      │                   │
  │                          │──execute(command)───▶│                   │
  │                          │                      │──SELECT token────▶│
  │                          │                      │◄──refresh data───│
  │                          │                      │──revoke old──────▶│
  │                          │                      │──generate new─────│
  │                          │                      │──INSERT new──────▶│
  │                          │◄──RefreshTokenResult─│                   │
  │◄─200 {access,refresh}────│                      │                   │
  │                          │                      │                   │
  │──── POST /api/auth/logout──────────────────────▶│                   │
  │     {refreshToken}       │                      │                   │
  │                          │──execute(command)───▶│                   │
  │                          │                      │──mark revoked────▶│
  │◄─204 No Content─────────│                      │                   │
```

## JWT Claims

### Access Token (JWT)
```json
{
  "jti": "7a1b2c3d-...",
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "roles": ["CUSTOMER"],
  "provider": "EMAIL",
  "iat": 1720934400,
  "exp": 1720935300
}
```

### JWT ID (`jti`) Claim

Every access token includes a unique `jti` (JWT ID) claim — a random UUID. This enables:
- **Token blacklisting** (future): Revoke specific access tokens before expiry using a Redis blacklist
- **Audit logging**: Correlate access tokens with server-side events
- **Replay detection** (future): Reject reused tokens during their validity window

### Refresh Token (Opaque UUID)
```
550e8400-e29b-41d4-a716-446655440000-550e8400-e29b-41d4-a716-446655440001
```

Stored as a **SHA-256 hash** in the `refresh_tokens` table with `id`, `user_id`, `token`, `expires_at`, `created_at`, `revoked`, `device_name`, `user_agent`, `ip_address`, `last_used_at`.

## Reactive Architecture

Every layer in the authentication pipeline is fully reactive (non-blocking):

- **Repository ports** return `Mono<T>` / `Flux<T>` — never `Optional<T>` or raw values
- **Repository adapters** use Spring Data R2DBC `DatabaseClient` — no `.block()` or `.blockOptional()`
- **Use cases** compose reactive pipelines with `flatMap()`, `then()`, `switchIfEmpty()`
- **Controller** returns `Mono<ResponseEntity<T>>` — never blocks on request processing

No blocking calls exist in the reactive flow. The event loop is never starved.

## Cleanup Strategy

A scheduled job (`ExpiredRefreshTokenCleanupJob`) runs daily at 3 AM:

```java
@Scheduled(cron = "0 0 3 * * ?")
public void deleteExpiredTokens() {
    refreshTokenRepository.deleteExpired(Instant.now())
            .subscribe(count -> log.info("Deleted {} expired tokens", count));
}
```

The job uses the repository's `deleteExpired(Instant now)` method, which issues:
```sql
DELETE FROM refresh_tokens WHERE expires_at < :now
```

This prevents the `refresh_tokens` table from growing unbounded.

## Typed API Responses

Controller responses use explicit DTO records instead of `Map.of()`:

| Endpoint | Success DTO | Error DTO |
|----------|------------|-----------|
| `POST /api/auth/login` | `LoginResponse` (userId, email, roles, accessToken, refreshToken) | `ErrorResponse` |
| `POST /api/auth/refresh` | `RefreshResponse` (accessToken, refreshToken) | `ErrorResponse` |
| `POST /api/auth/logout` | `204 No Content` | `ErrorResponse` |

## Typed Failure Reasons

`RefreshTokenResult` uses `RefreshFailureReason` enum instead of string literals:

```java
public enum RefreshFailureReason {
    TOKEN_NOT_FOUND,
    TOKEN_REVOKED,
    TOKEN_EXPIRED,
    USER_NOT_FOUND,
    USER_DISABLED,
    USER_DELETED,
    USER_LOCKED
}
```

This provides type safety and prevents typos in failure comparisons.

## Refresh Security (User Status Validation)

Before issuing new tokens, `RefreshTokenUseCase` validates:
1. Token is not revoked
2. Token is not expired
3. User exists
4. User is not deleted → `RefreshFailureReason.USER_DELETED`
5. User is not disabled → `RefreshFailureReason.USER_DISABLED`
6. User can login (not locked) → `RefreshFailureReason.USER_LOCKED`

If any validation fails, no new tokens are generated and an appropriate failure reason is returned.

## Session Metadata

Each refresh token stores optional session metadata:

| Field | Source | Purpose |
|-------|--------|---------|
| `deviceName` | Client-provided in login request | Identify the device |
| `userAgent` | Extracted from HTTP `User-Agent` header | Browser/OS identification |
| `ipAddress` | Extracted from request remote address | Geo-location / audit |
| `lastUsedAt` | Set at creation time | Track last usage |

Metadata is captured during login and preserved during token rotation.

## Security Configuration

```yaml
app:
  jwt:
    secret: ${JWT_SECRET}          # HS256 key (min 256 bits) — NO DEFAULT
    access-token-expiration: 15m   # Short-lived
    refresh-token-expiration: 7d   # Long-lived
```

**The JWT secret has no default value.** If `JWT_SECRET` environment variable is not set, the application fails at startup. This prevents accidental deployment with a weak default secret.

Spring Security is configured to be fully stateless:
- CSRF disabled (JWT is immune to CSRF)
- HTTP session disabled
- Form login disabled
- Basic auth disabled

## New/Modified Files

### PR #8 (Original)

```
identity/pom.xml                                 (+ spring-boot-starter-security, spring-security-config, jjwt)
identity/src/main/resources/db/migration/V2__create_refresh_tokens_table.sql  (NEW)
identity/src/main/java/.../domain/model/RefreshTokenId.java                   (NEW)
identity/src/main/java/.../domain/model/RefreshToken.java                     (NEW)
identity/src/main/java/.../application/port/TokenProvider.java                (NEW)
identity/src/main/java/.../application/port/RefreshTokenRepository.java        (NEW)
identity/src/main/java/.../application/command/RefreshTokenCommand.java        (NEW)
identity/src/main/java/.../application/command/LogoutCommand.java              (NEW)
identity/src/main/java/.../application/result/LoginResult.java                (NEW)
identity/src/main/java/.../application/result/RefreshTokenResult.java         (NEW)
identity/src/main/java/.../application/usecase/LoginUseCase.java              (NEW)
identity/src/main/java/.../application/usecase/RefreshTokenUseCase.java       (NEW)
identity/src/main/java/.../application/usecase/LogoutUseCase.java             (NEW)
identity/src/main/java/.../infrastructure/security/JwtTokenProvider.java      (NEW, implements TokenProvider)
identity/src/main/java/.../infrastructure/security/JwtAuthenticationManager.java (NEW)
identity/src/main/java/.../infrastructure/security/JwtAuthenticationConverter.java (NEW)
identity/src/main/java/.../infrastructure/security/JwtAuthenticationToken.java (NEW)
identity/src/main/java/.../infrastructure/security/JwtWebFilter.java          (NEW)
identity/src/main/java/.../infrastructure/persistence/entity/RefreshTokenEntity.java  (NEW)
identity/src/main/java/.../infrastructure/persistence/repository/RefreshTokenR2dbcRepositoryAdapter.java (NEW)
identity/src/main/java/.../infrastructure/config/IdentityInfrastructureConfiguration.java (UPDATED)
identity/src/main/java/.../interfaces/AuthController.java                     (NEW)
bootstrap/src/main/java/.../security/SecurityConfig.java                      (NEW)
bootstrap/src/main/resources/application.yml                                   (UPDATED: app.jwt section)

identity/src/test/java/.../infrastructure/security/JwtTokenProviderTest.java  (NEW, 8 tests)
identity/src/test/java/.../application/usecase/LoginUseCaseTest.java          (NEW, 4 tests)
identity/src/test/java/.../application/usecase/RefreshTokenUseCaseTest.java   (NEW, 4 tests)
identity/src/test/java/.../application/usecase/LogoutUseCaseTest.java         (NEW, 2 tests)
```

### Review Comment Changes

```
identity/src/main/java/.../application/port/RefreshTokenHasher.java             (RENAMED from TokenHasher, SHA-256 hashing port)
identity/src/main/java/.../infrastructure/security/Sha256RefreshTokenHasher.java (RENAMED from Sha256TokenHasher, SHA-256 implementation)
identity/src/main/java/.../application/port/RefreshTokenRepository.java          (MODIFIED, reactive Mono returns + deleteExpired)
identity/src/main/java/.../infrastructure/persistence/repository/RefreshTokenR2dbcRepositoryAdapter.java (MODIFIED, reactive, no .block())
identity/src/main/java/.../application/usecase/LoginUseCase.java                (MODIFIED, reactive + token hashing + device metadata)
identity/src/main/java/.../application/usecase/RefreshTokenUseCase.java         (MODIFIED, reactive + token hashing + user status checks)
identity/src/main/java/.../application/usecase/LogoutUseCase.java               (MODIFIED, reactive + token hashing + ownership verification)
identity/src/main/java/.../application/command/LogoutCommand.java                (MODIFIED, added userId field)
identity/src/main/java/.../application/command/LoginUserCommand.java            (MODIFIED, added deviceName, userAgent, ipAddress)
identity/src/main/java/.../interfaces/AuthController.java                       (MODIFIED, reactive + authenticated user in logout + typed DTOs)
identity/src/main/java/.../interfaces/dto/LoginResponse.java                    (NEW, typed login response)
identity/src/main/java/.../interfaces/dto/RefreshResponse.java                  (NEW, typed refresh response)
identity/src/main/java/.../interfaces/dto/LogoutResponse.java                   (NEW, typed logout response)
identity/src/main/java/.../interfaces/dto/ErrorResponse.java                    (NEW, typed error response)
identity/src/main/java/.../application/result/RefreshFailureReason.java         (NEW, typed failure enum)
identity/src/main/java/.../application/result/RefreshTokenResult.java           (MODIFIED, uses RefreshFailureReason enum)
identity/src/main/java/.../domain/model/RefreshToken.java                       (MODIFIED, added session metadata fields)
identity/src/main/java/.../infrastructure/persistence/entity/RefreshTokenEntity.java  (MODIFIED, added session metadata columns)
identity/src/main/java/.../infrastructure/persistence/repository/ExpiredRefreshTokenCleanupJob.java (MODIFIED, uses repository port)
identity/src/main/java/.../infrastructure/security/JwtWebFilter.java            (MODIFIED, returns 401 on auth errors)
identity/src/main/java/.../infrastructure/security/JwtTokenProvider.java        (MODIFIED, added jti claim)
identity/src/main/java/.../infrastructure/config/IdentityInfrastructureConfiguration.java (MODIFIED, wired RefreshTokenHasher)
identity/src/main/resources/db/migration/V4__add_session_metadata.sql           (NEW)
bootstrap/src/main/resources/application.yml                                    (MODIFIED, removed default JWT secret)
identity/docs/pr8-summary.md                                                    (MODIFIED, this file)

identity/src/test/java/.../infrastructure/security/JwtTokenProviderTest.java    (MODIFIED, +2 tests for null/short secret → 11 total)
identity/src/test/java/.../application/usecase/LoginUseCaseTest.java            (MODIFIED, +1 test for multi-device → 5 total)
identity/src/test/java/.../application/usecase/RefreshTokenUseCaseTest.java     (MODIFIED, +3 tests for user status → 7 total)
identity/src/test/java/.../application/usecase/LogoutUseCaseTest.java           (MODIFIED, reactive + ownership verification → 3 tests)
identity/src/test/java/.../infrastructure/persistence/repository/ExpiredRefreshTokenCleanupJobTest.java (NEW)
```

## Future Extensions

1. **Registration endpoint** — `/api/auth/register` is already configured as public in SecurityConfig. Wire `RegisterUserUseCase` when the endpoint is implemented.
2. **RS256 (asymmetric keys)** — Replace HS256 with RS256 for production. Microservices verify with public key; only auth service holds private key.
3. **Token blacklist** — For immediate access token revocation, maintain a Redis blacklist of revoked JWT IDs (`jti` claim). The `jti` claim is already present in access tokens.
4. **Rate limiting** — Add rate limiting on `/api/auth/login` to prevent brute force attacks.
5. **MFA** — Add two-factor authentication flow with temporary tokens.
6. **OAuth2 login** — Implement Google/Apple OAuth2 login flow using Spring Security's OAuth2 client.
