# 3. Case lifecycle

Allowed transitions, exactly as in `CaseStatus` (`backend/support/.../CaseStatus.java:23-35`):

| From | To |
|---|---|
| OPEN | INVESTIGATING, WAITING_FOR_CUSTOMER, RESOLVED |
| INVESTIGATING | WAITING_FOR_CUSTOMER, WAITING_FOR_EXTERNAL_PARTY, RESOLVED |
| WAITING_FOR_CUSTOMER, WAITING_FOR_EXTERNAL_PARTY | INVESTIGATING, RESOLVED |
| RESOLVED | INVESTIGATING (reopen: the customer within the window, or staff at any time) |

(WAITING_FOR_EXTERNAL_PARTY can only be reached from INVESTIGATING. An earlier sketch of this lifecycle in this document was looser; the table above follows the code.) The aggregate rejects any other move (`409 INVALID_STATUS_TRANSITION`). Resolution requires an outcome and a note.

## Escalation is separate from status

`escalation_state`: `NONE` → `REQUESTED` → `ACCEPTED`.

- `REQUESTED`: the customer, or a rule at creation, asked for a person to look at it. **No person has accepted it.** Customer text says so.
- `ACCEPTED`: set only when a staff member assigns the case to themselves (also moves `OPEN` to `INVESTIGATING`).

## Priority

Computed by `PriorityPolicy`, explainable, reasons stored (staff-only): category base level; order value at or above `app.support.high-value-threshold` (default 10000) bumps one level;
a repeat contact for the same order bumps one; an explicit safety statement from the customer sets the top level. No hidden scoring.

## Idempotency and duplicates

`POST` with an `Idempotency-Key` is serialised by `pg_advisory_xact_lock` on `(user, key)`. Outcomes: `CREATED`, `REPLAYED` (same key: same case returned), `ALREADY_OPEN`
(an unresolved case for the same order and category exists: it is returned, the customer message is added to it, no duplicate is created).

## Reopen

A customer may reopen a RESOLVED case within `app.support.reopen-window` (default `P7D`); staff may reopen at any time. Reopening increments `reopened_count`.

## Concurrency

Optimistic locking (`@Version`); staff mutations carry `expectedVersion`. Every transition writes a `support_case_events` row in the same transaction.
