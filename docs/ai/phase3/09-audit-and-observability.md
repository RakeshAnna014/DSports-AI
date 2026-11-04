# 9. Audit and observability

## Audit

| Where | What |
|---|---|
| `support_case_events` (Java, append-only) | CREATED, MESSAGE, STATUS_CHANGED, ASSIGNED, ESCALATION_REQUESTED, NOTE_ADDED, RESOLVED, REOPENED with actor type and id, timestamp, details |
| AI audit table (Phase 1) | proposal, confirmation, execution and refusal of every WRITE tool, with customer hash and argument hash |
| Application logs | correlation id propagated from the AI service to the backend (`X-Correlation-Id`); no tokens, no message bodies |

The customer sees the timeline of their case (without internal events). Staff see everything.

## Metrics

AI service (Prometheus): requests by intent/outcome, guard blocks by category, tool latency, tokens/cost. Backend: Actuator metrics as before.

## Staff analytics

`GET /api/v1/support/staff/analytics` computed from persisted cases only:

| Metric (`StaffSupportController.analytics`, lines 103-135) | Definition |
|---|---|
| `totalCases`, `unresolvedCases` | all cases; cases whose status is not RESOLVED |
| `byStatus`, `byCategory`, `unresolvedByPriority` | counts |
| `escalationRequestedNotYetAccepted`, `escalationAccepted` | unresolved cases where a person was asked for but nobody has taken the case; cases a support executive has taken |
| `unassignedUnresolved` | unresolved cases with no assignee |
| `resolvedByOutcome`, `resolvedByHuman` | counts by resolution outcome; resolved cases (all are resolved by a person) |
| `automaticallyResolved` | **always 0**: only a human support executive can resolve a case |
| `reopenedCases`, `casesWithRepeatContact` | cases with `reopen_count > 0`; cases whose customer has more than one case about the same order |
| `averageUnresolvedAgeHours`, `oldestUnresolvedAgeHours` | mean / maximum hours since creation of unresolved cases (null when there are none); no SLA is defined, so none is measured |
| `createdByAssistant`, `createdByWeb` | informational source labels (not a trust signal) |

No cost saving, deflection, first-response-time, resolution-time or satisfaction figure is computed or implied. (Correction 2026-10-13: an earlier version of this table listed "average time to first response" and "average time to resolution"; the code does not compute them. The table above follows the code.)
