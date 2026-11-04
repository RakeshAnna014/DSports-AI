# 2. Case data model

Migration `backend/support/src/main/resources/db/migration/V44__create_support_cases.sql` (forward-only, applied to the real `dsports` database; no table of another module is altered).

## Tables

| Table | Purpose | Notable constraints |
|---|---|---|
| `support_cases` | one row per case (the aggregate) | unique `case_number` (`CASE-YYYYMMDD-NNNNNN`, from `support_case_seq`); unique `(user_id, idempotency_key)`; `@Version` optimistic lock; `ck_support_resolved_has_outcome` (a RESOLVED case must carry an outcome) |
| `support_case_messages` | customer, staff and system messages | `internal` flag; CHECK: an internal message must be authored by STAFF |
| `support_case_events` | append-only audit/timeline | never updated or deleted by the application |

Key `support_cases` columns (as in V44): `user_id`, `order_id?`, `order_number?`, `product_id?`, `product_name?`, `payment_id?`, `return_id?`, `refund_reference?`, `category` (12 values), `subcategory?`,
`status`, `priority`, `priority_reasons` (JSON text), `subject`, `description`, `order_status_at_creation?`, `shipment_status_at_creation?`, `order_total?`, `currency?`,
`investigation_summary?`, `evidence` / `policy_citations` / `missing_information` (JSON text; each evidence item carries a provenance), `resolution_recommendation?`, `resolution_outcome?`,
`resolution_note?`, `resolved_by?`, `assigned_queue?`, `assigned_to?`, `escalation_state`, `escalation_reason?`, `safety_related`, `source` (`WEB` or `AI_ASSISTANT`), `idempotency_key`,
`reopen_count`, `version`, `created_at`, `updated_at`, `resolved_at?`.
(Correction 2026-10-13: an earlier version of this paragraph listed `summary`, `first_response_at` and `reopened_count`; those names do not exist in the migration.)

## Evidence provenance

Each evidence item has a `source`:

| Source | Meaning | Who may set it |
|---|---|---|
| `BACKEND_VERIFIED` | read by the Java backend itself at creation (order, shipment, payments, returns) | only the backend |
| `AI_INVESTIGATION` | what the assistant gathered and concluded (facts, inferences, missing information) | the creation request |
| `CUSTOMER` | said by the customer | customer messages |
| `STAFF` | added by a support executive | staff routes |

A request that claims `BACKEND_VERIFIED` is stored as `AI_INVESTIGATION`. Staff screens show the groups separately.

## Customer vs staff views

Separate response types. The customer type has no `internal` messages, no `priority_reasons`, no assignee identity, and no staff notes. Staff types include all of them.

## REST API (OpenAPI-annotated)

Customer, `/api/v1/support/cases` (authenticated; identity from the token only):
`POST` (header `Idempotency-Key`; 201 on create, 200 on replay or when an equivalent open case exists), `GET ?unresolved=`, `GET /{ref}`, `POST /{ref}/messages`,
`POST /{ref}/escalate`, `POST /{ref}/reopen`. `{ref}` is the case number or id; another customer's case is a 404.

Staff, `/api/v1/support/staff` (`SUPPORT_EXECUTIVE` or `ADMIN`): `GET cases` (filters), `GET cases/{ref}`, `POST cases/{ref}/assign|notes|replies|status|resolve`, `GET analytics`.
Mutations take `expectedVersion` (a stale value is a 409).

Errors use the platform `ApiError` contract, mapped in `GlobalExceptionHandler` (lines 400-408): `CASE_NOT_FOUND` and `RELATED_RECORD_NOT_FOUND` 404; `INVALID_STATUS_TRANSITION`, `IDEMPOTENCY_KEY_REUSED`, `OPTIMISTIC_LOCKING_CONFLICT`, `CASE_RESOLVED`, `REOPEN_NOT_ALLOWED` 409; `VALIDATION_ERROR` 400; `INTERNAL_ERROR` 500.
(Correction 2026-10-13: an earlier version named `INVALID_TRANSITION`, `VERSION_CONFLICT` and `ALREADY_RESOLVED`; those codes do not exist.)
