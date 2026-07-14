# PR #8 — JWT Authentication and Stateless Security

**Date:** 2026-07-14

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
| **Format** | Signed JWT | Opaque UUID string |
| **Lifetime** | Short (15 min) | Long (7 days) |
| **Revocable?** | No (stateless) | Yes (server-side toggle) |

**Access Token is not stored** because that would defeat the purpose of stateless authentication. Every request would need a database lookup, eliminating the scalability benefit.

**Refresh Token is stored** because it must be revocable. When a user logs out, the refresh token is marked `revoked = true` in the database. Even if an attacker obtains the refresh token after logout, it cannot be used. This is the **token rotation** strategy — every refresh operation issues a new refresh token and revokes the old one.

## Token Rotation

When a refresh token is used to obtain new tokens:

1. Old refresh token is **revoked** (marked as revoked in the database)
2. New access token and refresh token are **issued**
3. If a revoked refresh token is presented, ALL refresh tokens for that user are revoked (breach detection)

This limits the window of vulnerability. If a refresh token is stolen, it becomes invalid after the legitimate user refreshes their tokens.

## Logout Strategy

Logout is simple: mark the refresh token as revoked. No server-side session to clear. The client should discard the access token (it will expire within 15 minutes anyway).

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
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "roles": ["CUSTOMER"],
  "provider": "EMAIL",
  "iat": 1720934400,
  "exp": 1720935300
}
```

### Refresh Token (Opaque UUID)
```
550e8400-e29b-41d4-a716-446655440000-550e8400-e29b-41d4-a716-446655440001
```

Stored in `refresh_tokens` table with `id`, `user_id`, `token`, `expires_at`, `created_at`, `revoked`.

## Security Configuration

```yaml
app:
  jwt:
    secret: ${JWT_SECRET}          # HS256 key (min 256 bits)
    access-token-expiration: 15m   # Short-lived
    refresh-token-expiration: 7d   # Long-lived
```

Spring Security is configured to be fully stateless:
- CSRF disabled (JWT is immune to CSRF)
- HTTP session disabled
- Form login disabled
- Basic auth disabled

## New/Modified Files

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

## Future Extensions

1. **Registration endpoint** — `/api/auth/register` is already configured as public in SecurityConfig. Wire `RegisterUserUseCase` when the endpoint is implemented.
2. **RS256 (asymmetric keys)** — Replace HS256 with RS256 for production. Microservices verify with public key; only auth service holds private key.
3. **Token blacklist** — For immediate access token revocation, maintain a Redis blacklist of revoked JWT IDs (`jti` claim).
4. **Rate limiting** — Add rate limiting on `/api/auth/login` to prevent brute force attacks.
5. **MFA** — Add two-factor authentication flow with temporary tokens.
6. **OAuth2 login** — Implement Google/Apple OAuth2 login flow using Spring Security's OAuth2 client.
