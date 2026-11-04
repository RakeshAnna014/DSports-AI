# 6. Tool registry and MCP

Defined in `ai-service/app/tools/support_tools.py` and registered with the existing typed registry. The MCP server exposes the same registry; WRITE tools are reachable over MCP only through
`propose_action` / `confirm_action`, never directly.

No tool takes a customer id: identity comes from the verified token. `case_ref` is validated against `CASE-\d{8}-\d{6}` and only whitelisted fields of a response are returned.

## READ tools

| Tool | Backend call | Returns |
|---|---|---|
| `get_return_status` | `GET /api/v1/returns` | the customer's returns and states |
| `get_customer_support_cases` | `GET /api/v1/support/cases` | the customer's cases (customer view) |
| `get_support_case` | `GET /api/v1/support/cases/{ref}` | one case: status, timeline, support messages, escalation |
| `search_support_policy` | RAG | cited procedure passages (customer documents only) |

(Order, shipment, payment and refund lookups are the Phase 1 tools.)

## WRITE tools (all require confirmation)

| Tool | Backend call | Notes |
|---|---|---|
| `create_support_case` | `POST /api/v1/support/cases` | `Idempotency-Key` from the proposal; duplicate-aware |
| `add_case_message` | `POST /api/v1/support/cases/{ref}/messages` | customer message only |
| `request_case_escalation` | `POST .../escalate` | sets `REQUESTED`; never `ACCEPTED` |
| `reopen_support_case` | `POST .../reopen` | within the window, otherwise the backend refuses |

`prepare` runs before the confirmation card is shown: it re-reads the case, rejects what cannot work (resolved case for a message, unknown reference) and shows exact wording of what will be sent.

## Confirmation machinery (unchanged from Phase 1)

Single-use token bound to customer + tool + argument hash, five-minute expiry, revalidation at execution, idempotency key, audit row for proposal, confirmation and execution.
A changed argument, another customer, a reused or expired token are all refused (tests: `test_security_support.py`, `tests/live/test_stack_support.py`).

## What is deliberately absent

No tool resolves, closes, assigns, prioritises, refunds, cancels, approves or notes anything, and none reads internal notes or other customers' cases. The Phase 2 test that pins the exact set of
WRITE tools was updated to the four above, which is the entire new write surface.
