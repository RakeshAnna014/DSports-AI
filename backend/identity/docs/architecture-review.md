# Identity Domain — Architecture Review Report

## Review Scope

Domain layer of `dsports-identity` module. All review comments applied on 2026-07-12.

---

## Changes Applied

### RC1 — User Aggregate Public API Reduction (HIGH)

| Removed Public Method | Rationale | Replaced By |
|---|---|---|
| `User.changePhone(PhoneNumber)` | Pure setter — no business invariant enforced. Phone mutation is state update without guard conditions. | `UserProfileManagementService.changePhone(User, PhoneNumber)` calling package-private `User.updatePhone()` |
| `User.changePasswordHash(String)` | Pure setter — no invariant. Password hash is an opaque value set by infrastructure (hashing happens outside domain). | `UserProfileManagementService.changePasswordHash(User, String)` calling package-private `User.updatePasswordHash()` |

**Justification:** Both methods are data-mutation-only with no domain rule enforcement. In DDD, an Aggregate Root should only expose behavior that protects its consistency boundary. Scalar field updates without guard conditions belong in a Domain Service that can later coordinate cross-aggregate concerns (e.g., password-reuse validation, phone-number verification).

**Retained on User:** `changeEmail(Email)` — enforces the invariant that email can only change when the User is in `ACTIVE` or `PENDING_VERIFICATION` status.

### RC2 — Exception Hierarchy Consolidation (HIGH)

**Before:** 8 exception classes (1 base + 7 leaf exceptions)

**After:** 1 base exception + 1 ErrorCode enum — 2 files total.

| Removed Class | ErrorCode |
|---|---|
| `InvalidEmailException` | `ErrorCode.INVALID_EMAIL` |
| `InvalidPhoneNumberException` | `ErrorCode.INVALID_PHONE_NUMBER` |
| `InvalidCustomerNameException` | `ErrorCode.INVALID_CUSTOMER_NAME` |
| `InvalidStatusTransitionException` | `ErrorCode.INVALID_STATUS_TRANSITION` |
| `MissingRoleException` | `ErrorCode.MISSING_ROLE` |
| `DuplicateRoleException` | `ErrorCode.DUPLICATE_ROLE` |
| `OAuthProviderAlreadyLinkedException` | `ErrorCode.OAUTH_PROVIDER_ALREADY_LINKED` |
| *(implicit)* | `ErrorCode.MAX_AUTH_PROVIDERS_EXCEEDED` |
| *(implicit)* | `ErrorCode.GENERIC` |

`IdentityDomainException` now carries:
- `ErrorCode` enum for programmatic discrimination
- `Map<String, Object> context` for structured error metadata (e.g., `role`, `userId`, `provider`)

**Trade-offs:**
- **Pro:** Reduces class count from 8 → 2; simplifies catch/handler logic (single catch + switch on ErrorCode); easier to map to API error codes.
- **Pro:** Structured context map replaces per-class accessor methods (e.g., `MissingRoleException.role()` becomes `exception.getContext().get("role")`).
- **Con:** Loss of type-level distinction — cannot catch `DuplicateRoleException` specifically without checking ErrorCode. Acceptable because domain exceptions are always caught generically at the boundary handler, never in application logic.

### RC3 — UserRole Authorization Model (MEDIUM)

No functional changes. Added ADR-TODO block to `UserRole.java` documenting:

**Current (Sprint 1):** Hierarchical levels (0–7). `user.getRole().isAtLeast(ADMIN)` determines authorization.

**Future direction:** Role → Permission mapping. `user.hasPermission(Permission.READ_PRODUCT)` enables granular, composable, role-orthogonal authorization.

**Why hierarchy is acceptable for Sprint 1:**
- Simple to implement and reason about for 8 known roles.
- No requirement for dynamic permission assignment yet.
- Avoids premature abstraction.

### RC4 — Password Handling Future Direction (MEDIUM)

No functional changes. Added TODO-SPRINT-2 comment to `User.java` above the `passwordHash` field.

**Current (Sprint 1):** Single `passwordHash` string on User. Sufficient for email-based registration — one credential type, one hash.

**Future direction:** Credential Aggregate hierarchy:
- `PasswordCredential` (hash, salt, algorithm)
- `OAuthCredential` (provider, subject, refresh token)
- `OTPCredential` (phone, code, expiry)

This would allow User to manage multiple credential types through a unified interface (`user.getCredentials().ofType(OAuthCredential.class)`).

### RC5 — Framework Independence Verification (HIGH)

**Result: PASS — Domain layer is completely framework-independent.**

All `com.dsports.identity.domain.*` files import only:
- `java.*` (standard library)
- `com.dsports.shared.domain.kernel.*` (ValueObject, DomainEvent — also framework-free)
- Cross-references within `com.dsports.identity.domain.*`

Zero dependencies on:
- Spring Framework / Spring Boot annotations
- Spring Security
- JWT libraries
- R2DBC / persistence annotations
- OAuth SDKs
- Lombok

The `pom.xml` includes `spring-boot-starter-webflux` and `spring-boot-starter-data-r2dbc` at the module level — these are used by Infrastructure and Application layers, not the Domain.

### RC6 — Value Object Verification (HIGH)

**Result: PASS — All 4 Value Objects satisfy domain invariants.**

| Property | UserId | Email | PhoneNumber | CustomerName |
|---|---|---|---|---|
| Immutable (final class, final fields) | ✓ | ✓ | ✓ | ✓ |
| Self-validating (rejects invalid state at construction) | ✓ | ✓ | ✓ | ✓ |
| Value-based equality (equals/hashCode on fields) | ✓ | ✓ | ✓ | ✓ |
| No framework annotations | ✓ | ✓ | ✓ | ✓ |
| Private constructor + static factory | ✓ | ✓ | ✓ | ✓ |

### RC7 — Aggregate Size Assessment (LOW)

**User Aggregate metrics:**

| Dimension | Count |
|---|---|
| Public methods (total) | ~37 |
| Public behavior methods (excl. getters) | ~23 |
| Getters | 14 |
| Package-private mutation methods (new) | 2 |
| Cyclomatic complexity (qualitative) | Low — most methods are linear with 1–3 branches |

**Responsibility groups after RC1 changes:**
1. Identity lifecycle (7 methods) — factory, status transitions, deletion
2. Profile management (1 public) — email changes only
3. Role management (4 methods) — assign, remove, query
4. Auth provider management (3 methods) — link, query
5. Security (7 methods) — login tracking, lock check
6. Domain events (2 methods) — get, clear
7. Getters (14)

**Assessment:** The User Aggregate is cohesive but broad. All methods serve the single concern of "managing a user's identity lifecycle." The 2 orchestration methods moved to `UserProfileManagementService` improve the separation.

**No size reduction action recommended for Sprint 1.** If the aggregate grows further (e.g., adding MFA, device management, preferences), consider splitting into bounded sub-aggregates (UserAuthentication, UserProfile, UserPreferences).

---

## Summary of Changes

| File | Action |
|---|---|
| `domain/model/User.java` | Modified — removed 2 public methods, added 2 package-private methods, updated exception references, added password TODO |
| `domain/model/UserProfileManagementService.java` | **NEW** — orchestrates phone/password updates |
| `domain/exception/IdentityDomainException.java` | Modified — added ErrorCode + context map |
| `domain/exception/ErrorCode.java` | **NEW** — 10 error codes |
| `domain/exception/*Exception.java` (7 files) | **DELETED** — consolidated into ErrorCode |
| `domain/model/Email.java` | Modified — updated exception references |
| `domain/model/PhoneNumber.java` | Modified — updated exception references |
| `domain/model/CustomerName.java` | Modified — updated exception references |
| `domain/model/UserStatus.java` | Modified — updated exception references |
| `domain/model/UserRole.java` | Modified — added ADR-TODO for permission-based auth |

## Changes Intentionally Deferred

| Concern | Rationale |
|---|---|
| Password → Credential Aggregate | Sprint 1 scope — single credential type sufficient |
| Role → Permission authorization | No requirement for granular permissions yet |
| User Aggregate splitting | Currently cohesive; revisit if responsibilities grow |
| Value Object → Java Records | Would break backward compatibility with existing serialization; defer to Sprint 2 |

## Future ADR Recommendations

1. **ADR-002: Credential Aggregate** — Extract passwordHash into polymorphic credential model when adding OAuth or OTP authentication.
2. **ADR-003: Permission-Based Authorization** — Replace hierarchy levels with Role → Permission mapping when granular access control is required.
3. **ADR-004: Aggregate Decomposition** — Split User into bounded sub-aggregates if the model exceeds ~40 behavior methods.
