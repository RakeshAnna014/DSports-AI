# Identity module (`dsports-identity`)

Registration, login, refresh and logout, JWT issue and verification, customer profile, addresses, roles, and the shopper and family profiles used for budget-aware recommendations.

| | |
|---|---|
| API | `/api/auth/*`, `/api/customers/me`, `/api/customers/me/addresses`, `/api/customers/me/profiles`, `/api/customers/me/family-profiles` |
| Controllers | AuthController, CustomerController, AddressController, ShopperProfileController |
| Tables | `customers`, `customer_roles`, `customer_auth_providers`, `refresh_tokens`, `customer_addresses`, `shopper_profiles`, `shopper_active_profile` (V1-V6, V46) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 13 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Access tokens are HS256, 15 minutes; refresh tokens 7 days; `iss` and `aud` are required on verification (`JwtTokenProvider`).
* Roles are rows in `customer_roles` and are never assignable through an API (only a domain method exists); they are granted by SQL on purpose.
* Register ignores any role field in the request (mass-assignment test in `security_e2e.py`).
* Login, register and refresh are rate limited per address by `RateLimitWebFilter` in `bootstrap`.

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
