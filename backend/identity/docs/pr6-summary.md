# PR #6 — Identity Infrastructure Persistence Layer

**Date:** 2026-07-13

## Summary

Replaced the initial naive persistence layer (single `UserEntity` with comma-separated roles/providers + Spring Data R2DBC `ReactiveCrudRepository`) with a normalized, production-ready implementation using `DatabaseClient`, Flyway migrations, and proper join tables.

## Key Changes

### 1. Database Schema (Flyway Migration)

`V1__create_identity_tables.sql` creates three tables:

- **`customers`** — Core user data: id, email, password_hash, first_name, last_name, phone_country_code, phone_number, status, language, timezone, marketing_consent, created_at, updated_at
- **`customer_roles`** — Normalized join table: customer_id + role (ENUM), unique constraint on (customer_id, role)
- **`customer_auth_providers`** — Normalized join table: customer_id + provider (ENUM) + provider_user_id, unique constraint on (customer_id, provider)

Indexes on `customers(email)` (unique), `customer_roles(customer_id)`, `customer_auth_providers(provider_user_id)`.

### 2. Persistence Entities (3 classes, not 1)

| Entity | Table | Purpose |
|--------|-------|---------|
| `CustomerEntity` | `customers` | Maps to the core user row |
| `CustomerRoleEntity` | `customer_roles` | Maps each role assignment as a separate row |
| `CustomerAuthProviderEntity` | `customer_auth_providers` | Maps each auth provider link as a separate row |

Separate entities per table instead of comma-separated strings because:
- Roles and auth providers are queryable via SQL (no parsing)
- Future entities (RefreshToken, EmailVerificationToken) follow the same normalized pattern
- No risk of exceeding column length with accumulated values
- Database-level referential integrity

### 3. Entity Mapper

`CustomerEntityMapper` handles:
- `CustomerEntity <-> User` (core fields)
- `CustomerRoleEntity <-> RoleType` (collection)
- `CustomerAuthProviderEntity <-> AuthProviderLink` (collection)
- Mapper is stateless and injectable; uses `User.reconstitute(...)` for aggregate reconstruction (per architecture review)

### 4. Repository Adapter

`UserR2dbcRepositoryAdapter` implements `UserRepository` port via `DatabaseClient`:

| Method | Implementation |
|--------|---------------|
| `save(User)` | Upsert via `INSERT ... ON CONFLICT (email) DO UPDATE` — deletes and re-inserts roles and auth providers in the same transaction |
| `findByEmail(Email)` | Queries `customers` + joins for roles and auth providers |
| `findById(UserId)` | Same as findByEmail but by primary key |
| `existsByEmail(Email)` | Lightweight `SELECT COUNT(*)` — never loads the full aggregate |

Uses `DatabaseClient` instead of `ReactiveCrudRepository` because:
- Join-table inserts require multiple statements in a transaction
- `ReactiveCrudRepository` doesn't support batch inserts for child collections
- `DatabaseClient` gives full control over SQL for complex queries

### 5. Port Interface Extension

Added `existsByEmail(Email)` to `Application/port/UserRepository.java`:
- Enables lightweight email uniqueness checks without loading the full aggregate
- Used by `UserFactory` to validate email uniqueness before creation
- Avoids loading the entire User object (with roles, providers, addresses) just to check existence

### 6. Wiring

`IdentityInfrastructureConfiguration` now creates:
- `CustomerEntityMapper`
- `UserR2dbcRepositoryAdapter` (with `DatabaseClient` + `CustomerEntityMapper`)
- Removed old `UserPersistenceMapper`, `UserR2dbcRepository`, `UserRepositoryAdapter` beans

### 7. Integration Test

`UserRepositoryIntegrationTest` with Testcontainers PostgreSQL + Flyway:
- 7 test methods: full save/retrieve cycle, findById, existsByEmail (true + false), not-found handling, update/persist across calls, duplicate email rejection
- Tests are `@Testcontainers` + `@SpringBootTest` with `@DynamicPropertySource`

## Architecture Decisions

### ADR-6.1: DatabaseClient over ReactiveCrudRepository

**Context:** Need upsert + batch insert for join tables.

**Decision:** Use `DatabaseClient` directly. Let the adapter write SQL. This gives us `ON CONFLICT` upserts and transactional multi-row inserts that `ReactiveCrudRepository.saveAll()` and `@Modifying @Query` cannot cleanly express.

**Consequence:** More SQL to maintain, but each query is explicit and optimized. No hidden N+1 queries from ORM auto-fetching.

### ADR-6.2: Normalized Join Tables over Embedded Collections

**Context:** Roles and auth providers are many-to-many with the User aggregate.

**Decision:** Separate tables with foreign keys. Not comma-separated columns or JSONB.

**Consequence:** More tables but proper relational integrity, queryable by role/provider without parsing, no size limits, and consistent with how future entities (RefreshToken, Address) will be stored.

### ADR-6.3: Reconstitute Pattern for Aggregate Loading

**Context:** Loading a User from persistence must reconstruct a valid aggregate.

**Decision:** Use `User.reconstitute(...)` factory method (package-private) instead of reflection or exposing setters.

**Consequence:** The aggregate root controls its own reconstruction. Mapper calls `reconstitute()` with all fields. No bypassing of domain invariants.

## Files Changed

```
identity/pom.xml                                          (deps: testcontainers-junit-jupiter, assertj, postgresql JDBC, r2dbc-postgresql)
identity/src/main/resources/db/migration/V1__create_identity_tables.sql  (NEW)
identity/src/main/java/.../application/port/UserRepository.java           (+ existsByEmail)
identity/src/main/java/.../infrastructure/config/IdentityInfrastructureConfiguration.java  (updated wiring)
identity/src/main/java/.../infrastructure/persistence/adapter/UserRepositoryAdapter.java   (DELETED)
identity/src/main/java/.../infrastructure/persistence/entity/UserEntity.java                (DELETED)
identity/src/main/java/.../infrastructure/persistence/mapper/UserPersistenceMapper.java     (DELETED)
identity/src/main/java/.../infrastructure/persistence/repository/UserR2dbcRepository.java   (DELETED)
identity/src/main/java/.../infrastructure/persistence/entity/CustomerEntity.java            (NEW)
identity/src/main/java/.../infrastructure/persistence/entity/CustomerRoleEntity.java         (NEW)
identity/src/main/java/.../infrastructure/persistence/entity/CustomerAuthProviderEntity.java (NEW)
identity/src/main/java/.../infrastructure/persistence/mapper/CustomerEntityMapper.java       (NEW)
identity/src/main/java/.../infrastructure/persistence/repository/UserR2dbcRepositoryAdapter.java (NEW)
identity/src/test/java/.../persistence/UserRepositoryIntegrationTest.java  (NEW)
```

## Future Extension Points

1. **RefreshToken persistence** — Will follow same normalized pattern: `refresh_tokens` table + `RefreshTokenEntity` + collection mapper method in `CustomerEntityMapper` + transactional save with parent User.
2. **Address persistence** — Same pattern: `customer_addresses` table + `CustomerAddressEntity`.
3. **Pagination** — `CustomerEntityMapper` already supports offset/limit for future list queries on `UserRepository`.
4. **Audit fields** — `created_at`/`updated_at` columns ready. Can populate via mapper or database defaults.
5. **Soft delete** — Add `deleted_at` column to `customers` table, filter in all adapter queries.
