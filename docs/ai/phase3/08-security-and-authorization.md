# 8. Security and authorization

## Layers (defence in depth)

1. **Input guard** (`app/guard/input.py`, deterministic, before any model or tool): categories `cross_customer`, `internal_info`, `staff_action`, `credentials`, `prompt_extraction`, `escalation`, `injection`.
   Phase 3 added named persons' cases, "all/every case", staff notes, risk/priority logic, and staff-only verbs (close/resolve/assign cases; refund, reimburse, "pay me back", approve, cancel, reverse, change priority).
2. **Tools** take identity from the token only; WRITE tools need a confirmation token.
3. **Java backend**: customers see only their own cases (other's = 404, indistinguishable from missing); staff routes need `SUPPORT_EXECUTIVE`/`ADMIN` in `SecurityConfig` and `@PreAuthorize`; internal notes
   and priority reasons exist only in staff DTOs; `BACKEND_VERIFIED` cannot be forged; every mutation is audited.
4. **Retrieval**: internal documents excluded in SQL for customers.
5. **Output guard**: every case number, status, product and amount in an answer must appear in this turn's tool results.

## Role model

| Role | Can |
|---|---|
| Customer | create/read/message/escalate/reopen own cases |
| `SUPPORT_EXECUTIVE` | list and read all cases, assign, internal notes, customer replies, change status, resolve, analytics |
| `ADMIN` | same as `SUPPORT_EXECUTIVE` |

## Evidence (tests)

- Java `SupportCaseIntegrationTest` (14, Testcontainers): ownership, 404 uniformity, internal notes hidden, role gates, idempotency, transitions, optimistic lock.
- Python `tests/support/test_security_support.py`, `test_cases_conversation.py`: guard, forged ids, token reuse, cross-customer, internal-document isolation, injection in case text and in a poisoned document.
- `tests/live/test_stack_support.py` (11) and `e2e/support-ui.mjs` (23 steps incl. customer blocked from the staff console, bad token, AI service down).
- Evaluation gates: zero unauthorized state changes, zero cross-customer disclosure (doc 10).

## Residual risks (also in doc 13)

JWT HS256 shared secret and `localStorage` tokens are unchanged from Phase 1. The input guard is pattern-based: it is a first layer, and the held-out run found three phrasings it missed (state was not changed because no tool could do it; patterns were then extended).
