# Identity Module — Infrastructure Layer Review

## 1. Why Infrastructure Exists

The Infrastructure layer is the outermost ring in Clean Architecture. Its sole responsibility is to connect the Domain and Application layers to external technologies — databases, message buses, security libraries, notification services, and third-party APIs.

Infrastructure exists to **isolate the core business logic from technology concerns**. Without it, framework annotations like `@Table`, `@Id`, and `@Column` would leak into the Domain, coupling business rules to a specific persistence technology. Infrastructure adapters absorb this coupling so the Domain never needs to change when the database, security library, or notification provider is replaced.

## 2. Why Repository Adapter Exists

The `UserRepositoryAdapter` bridges the **Application port** (`UserRepository` interface) with the **Spring Data R2DBC repository** (`UserR2dbcRepository`).

**What it does:**
- Accepts domain-level `User` objects and `Email`/`UserId` value objects
- Delegates to `UserPersistenceMapper` for conversion to/from `UserEntity`
- Calls the R2DBC repository for actual data access
- Blocks on reactive Mono results (the Application layer uses synchronous `Optional`)

**Why it's separate from the port interface:**
The port (`UserRepository` in `application/port`) defines the contract — it knows nothing about databases. The adapter (`UserRepositoryAdapter` in `infrastructure/persistence/adapter`) fulfills that contract using R2DBC. This follows Dependency Inversion: the Application layer defines what it needs, and Infrastructure provides it.

Without the adapter, the port interface would need to import R2DBC types, violating Clean Architecture.

## 3. Why Persistence Entity is Different from Domain Aggregate

`UserEntity` (infrastructure) and `User` (domain) serve fundamentally different purposes:

| Aspect | `User` (Domain Aggregate) | `UserEntity` (Persistence Entity) |
|--------|--------------------------|-----------------------------------|
| Purpose | Enforce business invariants | Map to database rows |
| Annotations | None — pure Java | `@Table`, `@Id`, `@Column` |
| Behavior | Factory methods, state transitions, security rules | None — dumb data holder |
| Collections | `Set<UserRole>`, `Set<AuthenticationProvider>` | Comma-separated `String` |
| Constructor | Private — only via factories | Public no-arg (R2DBC requirement) |
| Domain events | Tracks and exposes events | Not stored |
| Framework coupling | Zero | High (Spring Data R2DBC) |

**Why they must be separate:**
- A single class cannot simultaneously satisfy DDD aggregate rules (private constructor, invariant enforcement) and ORM requirements (public no-arg constructor, mutable fields, framework annotations)
- Changes to the database schema (column rename, table split) should not affect the Domain
- Changes to business rules should not require database migration coordination

**Reconstruction via Reflection:**
The `User` domain aggregate has a private constructor — this is intentional (aggregates should be created only through factory methods that enforce invariants). The `UserPersistenceMapper` uses Java reflection to reconstruct `User` from `UserEntity` during reads. This is an accepted trade-off in Clean Architecture when the Domain does not expose a reconstruction factory. Write operations always go through `User.register()` or `User.registerWithOAuth()`, which enforce all business rules.

## 4. Why Mapper Exists

`UserPersistenceMapper` has only one responsibility: convert between `User` (domain) and `UserEntity` (persistence).

**What it handles:**
- `UserId` (UUID wrapper) → `UUID` and back
- `Email` (validated value object) → `String` and back
- `CustomerName` (firstName + lastName) → Two `String` columns and back
- `PhoneNumber` (optional value object) → Nullable `String` column and back
- `UserStatus` (enum) → `String` column and back
- `Set<UserRole>` → Comma-separated `String` and back
- `Set<AuthenticationProvider>` → Comma-separated `String` and back
- `Instant` timestamps — direct mapping

**What it does NOT do:**
- No business rules or validation (these belong in Domain Value Objects)
- No logging, auditing, or event publishing
- No data transformation beyond type conversion

**Why a dedicated mapper instead of inline conversion in the adapter:**
- Single Responsibility — the adapter coordinates, the mapper converts
- Testability — the mapper can be unit-tested independently
- Avoids duplication — if another adapter needs the same conversion, the mapper is reusable

## 5. Why BCrypt Belongs Here

BCrypt password hashing lives in `BCryptPasswordEncoderAdapter` in the infrastructure layer because:

- **Technology concern** — hashing algorithm choice is an infrastructure decision. The Domain should not care whether passwords are hashed with BCrypt, Argon2, or PBKDF2.
- **Library dependency** — `BCryptPasswordEncoder` comes from Spring Security (`spring-security-crypto`). Importing Spring Security classes into the Domain would violate framework independence.
- **Replaceable** — if the hashing algorithm needs to change (e.g., to Argon2 for enhanced security), only the `BCryptPasswordEncoderAdapter` changes. The `PasswordEncoder` port and all use cases remain untouched.

The `PasswordEncoder` port interface in `application/port` defines the contract (`encode` and `matches`), keeping the Application layer decoupled from the hashing implementation.

## 6. Why Spring Annotations Never Belong in Domain

The Domain layer is verified to be completely framework-independent (established in Architecture Review RC5). Zero Spring annotations exist in `com.dsports.identity.domain.*`.

**Why this matters:**
- **Testability** — Domain objects can be unit-tested without Spring context. Tests run in milliseconds, not seconds.
- **Portability** — the domain logic could be extracted to a separate library or used in a non-Spring application.
- **Technology evolution** — if the project migrates from Spring Data R2DBC to JDBC, jOOQ, or a different framework, the Domain changes exactly zero lines.
- **Focus** — domain experts (and developers reading domain code) should see business rules, not framework plumbing.

**Enforcement pattern:**
```
Domain (zero imports outside java.* and com.dsports.shared.domain.kernel)
    ↑
Application (imports only Domain types and standard Java)
    ↑
Infrastructure (imports Spring, R2DBC, BCrypt — implements Application ports)
```

## 7. How This Design Enables Database Replacement

The layered architecture makes it possible to replace PostgreSQL with another database without changing the Domain or Application layers:

**Current stack:** PostgreSQL + Spring Data R2DBC

**Steps to replace with, e.g., MySQL + JDBC:**

1. Create `UserJdbcRepository` — a new Spring Data JDBC repository or manual `JdbcTemplate`-based repository
2. Create `UserJdbcRepositoryAdapter` — implements `UserRepository` port using the JDBC repository
3. Optionally create `UserJdbcPersistenceMapper` — if the entity structure differs
4. Update `IdentityInfrastructureConfiguration` — replace `UserRepositoryAdapter` bean with `UserJdbcRepositoryAdapter` bean

**Files that change:**
- `UserEntity.java` — different annotations (`@Table` remains, column types may differ)
- `UserR2dbcRepository.java` → replaced by JDBC equivalent
- `UserRepositoryAdapter.java` → replaced by JDBC adapter
- `UserPersistenceMapper.java` — may remain unchanged (still maps Domain ↔ Entity)
- `IdentityInfrastructureConfiguration.java` — bean wiring changes

**Files that do NOT change:**
- Any file in `domain/`
- Any file in `application/` (ports, use cases, commands, results)
- The `UserRepository` port interface

**Why this works:**
The Application layer depends on the `UserRepository` interface (abstraction), not on `UserR2dbcRepository` (concrete implementation). Spring's DI container wires the correct adapter at runtime via `IdentityInfrastructureConfiguration`. The Application never imports anything from `infrastructure/persistence/`.

---

## Technical Debt & Future Improvements

### Sprint 1 — Temporary Persistence Model

Roles and authentication providers are stored as **comma-separated strings** in the `roles` and `auth_providers` columns of the `users` table.

**Why this is acceptable for Sprint 1:**
- Only 8 roles and 5 authentication providers exist — cardinality is low and bounded
- No queries filter by individual role or provider membership yet
- Avoids creating 2-3 additional tables (role, auth_provider, user_role, user_auth_provider) before the schema is stable
- Comma-separated storage keeps the initial migration simple and reversible

**Risks and limitations:**
- No referential integrity at the database level
- Role/provider membership queries require string parsing in application code
- Updating a single role/provider requires reading and rewriting the entire user row
- Enum renames require a data migration

### Sprint 2+ — Normalized Tables (Planned Migration)

Replace comma-separated columns with normalized many-to-many relationships:

```
users                    user_roles                roles
┌──────────┐            ┌──────────────┐          ┌──────────────┐
│ id (PK)  │◄───────────│ user_id (FK) │──────────►│ name (PK)    │
│ email    │            │ role (FK)    │          │ hierarchy    │
│ ...      │            └──────────────┘          └──────────────┘
└──────────┘
```

Similarly for `user_auth_providers`.

**Migration approach:**
1. Create normalized tables in a Flyway migration
2. Write SQL to migrate data from comma-separated columns to normalized tables
3. Add application-layer repository methods for role/provider queries
4. Deprecate and remove the comma-separated columns after verified migration

---

## Files Created

| File | Purpose |
|------|---------|
| `infrastructure/persistence/entity/UserEntity.java` | R2DBC entity mapping to `users` table |
| `infrastructure/persistence/repository/UserR2dbcRepository.java` | Spring Data R2DBC repository |
| `infrastructure/persistence/mapper/UserPersistenceMapper.java` | Domain ↔ Entity conversion |
| `infrastructure/persistence/adapter/UserRepositoryAdapter.java` | Implements `UserRepository` port |
| `infrastructure/security/BCryptPasswordEncoderAdapter.java` | Implements `PasswordEncoder` port |
| `infrastructure/event/SpringEventPublisherAdapter.java` | Implements `EventPublisher` port |
| `infrastructure/notification/NotificationGatewayStub.java` | Implements `NotificationGateway` port |
| `infrastructure/oauth/OAuthProviderGatewayStub.java` | Implements `OAuthProviderGateway` port |
| `infrastructure/config/IdentityInfrastructureConfiguration.java` | Spring `@Configuration` wiring all beans |

## Files Modified

| File | Change |
|------|--------|
| `pom.xml` (root — dependency management) | Added `spring-security-crypto:6.5.5` managed dependency |
| `pom.xml` (identity module) | Added `spring-security-crypto` (no version — resolved from parent) |
| `domain/model/User.java` | Made `createdAt` non-final; added `reconstitute()` factory for aggregate reconstruction without reflection |
