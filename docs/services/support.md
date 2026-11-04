# Customer support and case resolution

Code: Java `backend/support` (cases, authority) and Python `ai-service/app/support` (investigation, assistance). Depth: [../ai/phase3/](../ai/phase3/README.md), [ADR-011](../adr/ADR-011-customer-support-resolution.md).

## 1. Business purpose
Let a customer describe a problem, have the assistant verify it against the customer's own records and the policy documents, and (after confirmation) open a **real, persisted case** that staff work in a protected console.

## 2. Entry points
Java: `/api/v1/support/cases/**` (customer) and `/api/v1/support/staff/**` (`SUPPORT_EXECUTIVE` or `ADMIN`). Python: the assistant's support intents (`agent/support.py`), `GET /v1/support/conversation`, `POST /v1/support/feedback`, and staff assistance `/v1/support/staff/cases/{ref}/assist|actions` and `/v1/support/staff/insights`. UI: `/support` (customer cases), `/support-cases` (staff console).

## 3. Request lifecycle
Chat: classify the complaint type (`support/classify.py`) → deterministic investigation over tools (`support/investigate.py`: orders, shipment, payment, refund, return, existing cases) → resolution and priority by rules (`support/resolve.py`) → policy retrieval with citations → a proposal; only `/v1/confirm` calls Java to create the case. Staff assistance (`support/staff_assist.py`) summarises a case and drafts replies that a staff member must choose to send.

## 4. Business rules
Case status machine (`CaseStatus`): `OPEN`, `INVESTIGATING`, `WAITING_FOR_CUSTOMER`, `WAITING_FOR_EXTERNAL_PARTY`, `RESOLVED`, with explicit allowed transitions; reopen and escalate are customer actions; "escalation requested" and "escalation accepted" are different states. A case number or status is shown only if Java returned it. The assistant never resolves, refunds, cancels, assigns or re-prioritises, and customers never see internal notes or other customers' cases. Java `RateLimitWebFilter` limits case creation (default 10 per user per minute) and messages, escalations and reopens (30).

## 5. Data ownership
Java `dsports`: support case, message, note and event tables (migrations V44, V45). Python `dsports_ai`: `support_ai_actions`. Policy documents: `corpus_support/` (**demonstration content; no official SLA is claimed**).

## 6. External dependencies
Java REST, Gemini (wording only), pgvector policy retrieval.

## 7. Security and authorization
Customer endpoints are owner-scoped in the use cases. Staff endpoints need `SUPPORT_EXECUTIVE`/`ADMIN` in `SecurityConfig` and again in the Python routes (observed: customer 403, support 200, admin 200 in the [authorization matrix](../security/AUTHORIZATION-MATRIX.md)). WRITE tools are confirmed and idempotent; support text is fenced as untrusted data. Evidence: `SecurityPolicyTest`, `tests/security/`, the Phase 3 evaluation (held-out sets are preserved, never retuned).

## 8. Failure handling and observability
If the model is down the investigation, decision and proposal are still produced by rules; a failed case creation is reported honestly (no invented case number). Audit: Java case events and the Python `audit` table.

## 9. Run and test
Seed policy documents: `uv run python -m app.support.cli ingest-corpus`. Tests: `uv run pytest -q -k support`, the Java `support` module tests, `e2e/support-ui.mjs`, `e2e/ai/case_chat_and_incidents_e2e.py`.

## 10. Implemented / partial / missing
Implemented: customer cases, staff console, assistant investigation and confirmed creation. Missing: evidence or photo upload (no file-storage design), live-agent hand-off, official SLA. Partial: the Java `support` module has only 3 test classes (the policy, assistant and e2e suites cover the rest).

## 11. First files to read
`backend/support/.../domain/model/CaseStatus.java` and the support use cases, then `ai-service/app/support/flow.py`, `investigate.py`, `resolve.py` and `tools/support_tools.py`.
