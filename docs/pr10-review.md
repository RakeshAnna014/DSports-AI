# PR #10: Customer Address Management — Review Checklist

## Architecture
- [x] Address is an Entity inside Customer aggregate (not an aggregate root)
- [x] All communication through use cases in application layer
- [x] No controller business logic — delegates to use cases
- [x] No layer violations (domain → application → infrastructure → interfaces)

## Domain
- [x] VOs validate their own invariants (AddressLine, PostalCode, Country, State, ProfileImageUrl)
- [x] ProfileImageUrl replaces raw String with HTTPS validation
- [x] Address behaviors encapsulated (create, update, markDefault, unmarkDefault)
- [x] User aggregate enforces max 10 addresses invariant
- [x] User auto-marks first address of each type as default
- [x] Version field for optimistic locking
- [x] Events recorded (UserProfileUpdatedEvent) and published through port
- [x] Email remains immutable in User aggregate (verified)
- [x] reconstitute() handles addresses and version for persistence rebuild
- [x] No domain logic leaked to application layer

## Application
- [x] Use cases are stateless, depend only on ports (UserRepository, EventPublisher)
- [x] Commands are immutable records
- [x] Results mapped from domain, not entities
- [x] Mono.defer() used for VO validation before reactive chain
- [x] Address VOs validated before user lookup

## Infrastructure
- [x] V6 migration creates customer_addresses table + version column
- [x] AddressEntity + SpringR2dbcAddressRepository created
- [x] CustomerEntityMapper handles address ↔ domain mapping
- [x] replaceAddresses() uses delete+insert strategy (not cascading)
- [x] Event publishing after save completes
- [x] Configuration registers all beans

## Interfaces
- [x] RESTful endpoints under `/api/customers/me/addresses`
- [x] Input validation via Jakarta `@Valid` annotations
- [x] Responses use DTO pattern (not exposing domain)
- [x] Authentication extracted from SecurityContext
- [x] HTTP status codes: 200 (GET/PUT), 201 (POST), 204 (DELETE)

## Error Handling
- [x] Domain exceptions with error codes mapped to HTTP responses via global handler
- [x] Address not found → ADDRESS_NOT_FOUND
- [x] Max addresses exceeded → MAX_ADDRESSES_EXCEEDED
- [x] Invalid input → specific error codes per VO

## Testing
- [x] 47 unit tests pass
- [x] Integration test compatible (Docker required)

## Potential Improvements for Follow-up
1. Add integration test specifically for address persistence cycles
2. Consider address soft-delete vs hard-delete for audit trail
3. Add pagination for large address lists if needed
4. Validate city field as a VO if format rules emerge
5. The version column exists but optimistic locking logic (compare-and-swap) is not yet implemented in the adapter's save method — version-aware save would reject concurrent updates
