# PR #9 — Customer Profile Management

## Aggregate Responsibilities

The `User` aggregate is the single Aggregate Root for customer profile management. It owns all profile state including name, phone, profile image, and date of birth alongside authentication and authorization data.

### Profile fields managed by the aggregate:

| Field | Type | Mutable | Validation |
|-------|------|---------|------------|
| `firstName` | String (via `CustomerName` VO) | Yes | Not blank, max 100 chars |
| `lastName` | String (via `CustomerName` VO) | Yes | Not blank, max 100 chars |
| `phoneNumber` | `PhoneNumber` VO (optional) | Yes | E.164 format |
| `profileImageUrl` | String (optional) | Yes | (stored as-is) |
| `dateOfBirth` | `DateOfBirth` VO (optional) | Yes | Not in the future, max 150 years ago |
| `email` | `Email` VO | **No** | Immutable after registration |
| `userId` | `UserId` VO | **No** | Immutable after creation |
| `roles` | Set of `UserRole` | **No** | Managed by admin, not profile |
| `authProviders` | Set of `AuthenticationProvider` | **No** | Managed by auth linking |

## Architecture

Clean Architecture + DDD with reactive WebFlux:

```
Interfaces (CustomerController, DTOs)
    ↓
Application (GetCustomerProfileUseCase, UpdateCustomerProfileUseCase)
    ↓
Domain (User, CustomerName, PhoneNumber, DateOfBirth, UserProfileManagementService)
    ↓
Infrastructure (UserR2dbcRepositoryAdapter, CustomerEntity, CustomerEntityMapper)
```

## API Flow

### GET /api/customers/me

```
Client → JWT Token → SecurityContext → CustomerController.getProfile(authentication)
    → GetCustomerProfileUseCase.execute(userId)
    → UserRepository.findById(userId)
    → User aggregate
    → CustomerProfileResult
    → CustomerProfileResponse (JSON)
```

### PUT /api/customers/me

```
Client → JWT Token + Body → SecurityContext → CustomerController.updateProfile(request, authentication)
    → UpdateCustomerProfileUseCase.execute(command)
    → VO Validation (CustomerName.of, PhoneNumber.from, DateOfBirth.from)
    → UserRepository.findById(userId)
    → UserProfileManagementService.updateProfile(user, newName, newPhone, newImage, newDob)
    → User.updateProfile() (package-private, mutates aggregate)
    → UserRepository.save(user)
    → CustomerProfileResult
    → CustomerProfileResponse (JSON)
```

## Sequence Diagram

```
┌──────────┐     ┌───────────────────┐     ┌─────────────────────┐     ┌──────────────┐     ┌──────────────┐
│  Client   │     │ CustomerController │     │ UpdateProfileUseCase│     │ UserRepository│     │   Database   │
└────┬─────┘     └─────────┬─────────┘     └──────────┬──────────┘     └──────┬───────┘     └──────┬───────┘
     │  PUT /api/customers/me│                       │                       │                      │
     │  + JWT + JSON body   │                       │                       │                      │
     ├──────────────────────►│                       │                       │                      │
     │                       │  Execute(command)     │                       │                      │
     │                       ├──────────────────────►│                       │                      │
     │                       │                       │  Validate VOs         │                      │
     │                       │                       │  (CustomerName, Phone,│                      │
     │                       │                       │   DateOfBirth)        │                      │
     │                       │                       │                       │                      │
     │                       │                       │  findById(userId)     │                      │
     │                       │                       ├──────────────────────►│                      │
     │                       │                       │      User Aggregate   │  SELECT ...           │
     │                       │                       │◄──────────────────────┤──────────────────────►│
     │                       │                       │                       │◄──────────────────────┤
     │                       │                       │                       │                      │
     │                       │                       │  updateProfile(...)   │                      │
     │                       │                       │  (via Domain Service) │                      │
     │                       │                       │                       │                      │
     │                       │                       │  save(user)           │                      │
     │                       │                       ├──────────────────────►│  INSERT ... ON CONFLICT
     │                       │                       │                       ├─────────────────────►│
     │                       │                       │                       │◄─────────────────────┤
     │                       │                       │◄──────────────────────┤                      │
     │                       │        200 OK         │                       │                      │
     │                       │   CustomerProfileResp │                       │                      │
     │◄──────────────────────┤◄──────────────────────┤                       │                      │
```

## Why Email is Immutable

Email serves as the primary identifier for authentication and account recovery. Changing email in a profile update endpoint would:

1. **Introduce security risk**: An attacker who gains temporary access could change the email and lock out the legitimate owner.
2. **Break authentication contracts**: The login flow uses email as the unique lookup key. Changing it mid-session without re-verification is unsafe.
3. **Domain invariant**: The domain model enforces email immutability by not exposing it in the `updateProfile` method signature.

Email changes should follow a dedicated "Change Email" flow with verification (future PR).

## Why userId Comes from SecurityContext

The `userId` is extracted from the JWT token's `sub` claim via `Authentication.getPrincipal()`, NOT from the request body. This ensures:

1. **Implicit authorization**: Users can only access their own profile. There is no way to specify another user's ID.
2. **Tamper-proof**: A malicious client cannot modify the `userId` in the request body to access another user's data.
3. **Clean API contract**: The request body only contains the profile fields to update, keeping the API surface small.

```
Client sends:  { "firstName": "Jane", "lastName": "Smith" }
Server reads:  userId = JWT.sub
Never accept:  userId in the request body
```

## Architecture Decisions

| Decision | Rationale |
|----------|-----------|
| Reused `User` aggregate | Profile fields naturally belong to the existing `User` aggregate. A separate `CustomerProfile` aggregate would introduce unnecessary complexity and cross-aggregate consistency concerns. |
| Reused `UserRepository` | `UserRepository` already provides `findById()` and `save()` — exactly what's needed. Creating a separate `CustomerProfileRepository` port would duplicate logic without benefit. |
| Package-private `updateProfile` | Follows the existing pattern where simple state mutations on `User` are package-private and accessed via `UserProfileManagementService` domain service. Invariants (immutable email/userId) are enforced by the method signature. |
| `DateOfBirth` Value Object | Encapsulates validation (not in future, reasonable range) at the domain level. Consistent with `PhoneNumber`, `Email`, `CustomerName` VOs. |
| `Mono.defer` in use case | Wraps VO construction in a deferred execution context so that validation errors are captured in the reactive pipeline error channel, not thrown synchronously. |
| Flyway V5 migration | Adds `profile_image_url TEXT` and `date_of_birth DATE` columns. All nullable because both are optional profile fields. Non-disruptive, backward-compatible. |
| No admin APIs | Profile management is self-service only. Admin profile management (view all, edit any) would be a separate concern with its own authorization boundary. |
