# Support module (`dsports-support`)

Persisted customer support cases: customer messages, escalation and reopen, and the staff console (assign, priority, notes, replies, status, resolve, report, analytics).

| | |
|---|---|
| API | `/api/v1/support/cases` (customer), `/api/v1/support/staff` (SUPPORT_EXECUTIVE or ADMIN) |
| Controllers | SupportCaseController, StaffSupportController |
| Tables | `support_cases`, `support_case_messages`, `support_case_events` (V44, V45) |
| Layout | `domain` (no framework imports) / `application` (use cases, ports) / `infrastructure` (R2DBC adapters, configuration) / `interfaces` (controllers, DTOs); wired in `bootstrap/.../config` |
| Tests | 3 Java test file(s) under `src/test`; integration tests need Docker (`mvn -B -o test -Pintegration-test -Dapi.version=1.44`) |

## Business rules
* Statuses: OPEN, INVESTIGATING, WAITING_FOR_CUSTOMER, WAITING_FOR_EXTERNAL_PARTY, RESOLVED, with explicit transitions.
* Customers never see internal notes or other customers' cases; creation, messages, escalation and reopen are rate limited per user.
* The AI service creates a case only after the customer's explicit confirmation, through this API.

## More
Service guide: [docs/services/java-backend.md](../../docs/services/java-backend.md). Endpoint reference: [docs/api/java-backend.md](../../docs/api/java-backend.md). Database: [docs/DATABASE.md](../../docs/DATABASE.md). Authorization: [docs/security/AUTHORIZATION-MATRIX.md](../../docs/security/AUTHORIZATION-MATRIX.md).
