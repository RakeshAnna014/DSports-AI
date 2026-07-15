# PR #10: Customer Address Management + Part 2 Architecture Improvements

## Summary

Implements full Address CRUD within the Customer aggregate, plus Part 2 improvements including `ProfileImageUrl` value object, `UserProfileUpdatedEvent`, and optimistic locking.

## Domain Layer

### Value Objects Created
- `AddressId` — UUID wrapper, mirrors `UserId` pattern
- `AddressType` — `SHIPPING | BILLING` enum
- `AddressLine` — max 255 chars, not blank
- `Country` — ISO 3166-1 alpha-2 validated
- `State` — max 100 chars, not blank
- `PostalCode` — max 20 alphanumeric
- `ProfileImageUrl` — HTTPS-only, max 2048, rejects `javascript:`/`data:` URIs

### Entity Created
- `Address` — entity inside Customer aggregate (not an aggregate root)
  - Factory: `create()` and `reconstitute()` methods
  - Behaviors: `update()`, `markDefault()`, `unmarkDefault()`

### Aggregate Update (User)
- Added `private final List<Address> addresses`
- Added `private int version` for optimistic locking
- Changed `profileImageUrl` from `String` → `ProfileImageUrl`
- **Address behaviors**: `addAddress()`, `updateAddress()`, `removeAddress()`, `setDefaultAddress()`
- **Validation**: max 10 addresses per user; auto-mark default for first of each type
- Event publishing: `updateProfile()` now records `UserProfileUpdatedEvent`

### Event Created
- `UserProfileUpdatedEvent` — extends `DomainEvent`, carries `userId`, `changedAt`, `changedFields`

### Error Codes Added
`INVALID_ADDRESS`, `INVALID_POSTAL_CODE`, `INVALID_COUNTRY`, `INVALID_STATE`, `INVALID_PROFILE_IMAGE_URL`, `ADDRESS_NOT_FOUND`, `MAX_ADDRESSES_EXCEEDED`, `OPTIMISTIC_LOCKING_CONFLICT`

## Application Layer

### Commands
- `CreateAddressCommand`, `UpdateAddressCommand`, `DeleteAddressCommand`, `SetDefaultAddressCommand`

### Results
- `AddressResult` — single address record
- `AddressListResult` — wraps `List<AddressResult>`

### Use Cases
- `GetAddressesUseCase` — list all addresses for user
- `CreateAddressUseCase` — validates VOs, invokes `user.addAddress()`, saves
- `UpdateAddressUseCase` — validates VOs, invokes `user.updateAddress()`, saves
- `DeleteAddressUseCase` — invokes `user.removeAddress()`, saves
- `SetDefaultAddressUseCase` — invokes `user.setDefaultAddress()`, saves

## Infrastructure Layer

### Database Migration (V6)
- `customer_addresses` table with foreign key to `customers`
- `version` column added to `customers` table

### New Entities & Repositories
- `AddressEntity` — R2DBC entity mapped to `customer_addresses`
- `SpringR2dbcAddressRepository` — Spring Data R2DBC repository

### Mapper & Adapter Updates
- `CustomerEntityMapper`: maps `version` field; new `toAddresses()` method; updated `toDomain()` to accept `List<AddressEntity>`
- `UserR2dbcRepositoryAdapter`:
  - Address loading in `findById()`/`findByEmail()` via `loadUserWithRolesProvidersAndAddresses()`
  - Address persistence in `save()` via `replaceAddresses()` (delete removed + save current)
  - Event publishing after save: publishes `UserProfileUpdatedEvent` through `EventPublisher`

### Configuration
- All new use cases registered as `@Bean` in `IdentityInfrastructureConfiguration`
- `SpringR2dbcAddressRepository` and `EventPublisher` injected into adapter

## Interfaces Layer

### DTOs
- `CreateAddressRequest` — validated `@NotBlank` on required fields
- `UpdateAddressRequest` — same validation as create
- `AddressResponse` — includes `from(AddressResult)` factory
- `AddressListResponse` — wraps `List<AddressResponse>`

### AddressController
- `GET /api/customers/me/addresses` — list addresses
- `POST /api/customers/me/addresses` — create address
- `PUT /api/customers/me/addresses/{addressId}` — update address
- `DELETE /api/customers/me/addresses/{addressId}` — delete address
- `PUT /api/customers/me/addresses/{addressId}/default` — set as default

## Part 2 Changes Applied
1. **ProfileImageUrl VO** — replaces raw String; HTTPS validation
2. **UserProfileUpdatedEvent** — published on profile update
3. **Optimistic locking** — `version` field on User/CustomerEntity

## Test Results
- **47 unit tests pass** (all non-integration)
- **1 integration test** skipped (Docker not available — Testcontainers requirement)
- **0 compilation errors**
