# 17. Promotions, case-linked chat, support reporting, staff AI workspace, Engineering incidents

Design record for the master implementation mission (October 2026). Each section says what exists, what is deliberately **not** claimed, and what a person must still decide.

## 1. Checkout address fix
Root cause: identity error codes (invalid postal code, state, country, and so on) had no HTTP mapping, so a validation failure surfaced as HTTP 500. `GlobalExceptionHandler` now maps them to 400/404/409, and the checkout form validates before submitting (`addressValidation.ts`). Tests: handler unit tests, `CheckoutAddress.test.tsx`, and `e2e/ai/checkout_features_e2e.py`.

## 2. Discounts and promo codes
- Migration `V47__promotions.sql`: promotions, redemptions, product scopes. Seeded offers are **DEMO / synthetic** and labelled so.
- Calculation is backend-authoritative (`PromotionCalculator`). One promotion per checkout, only while the checkout is PENDING. Redemption happens inside the order transaction under a row lock; usage counts ignore CANCELLED orders.
- API: `GET /api/v1/checkout/{id}/promotions`, `POST` and `DELETE /api/v1/checkout/{id}/promotion`.
- AI-recommended offers: `GET /ai-api/v1/offers/recommend?checkoutId=`. Ranking is deterministic; a reason is shown only when it is true of this customer's cart, orders or profile. No model call. A switched-off personalisation setting or a family profile means no history is used.
- **REQUIRES BUSINESS DECISION:** tax is currently computed on the pre-discount subtotal and the discount is subtracted afterwards. Confirm the treatment before real money is involved.

## 3. Case-linked support chat
A chat can be opened about one of the customer's own cases (`caseRef`). The case is re-validated with the customer's token on every turn; someone else's case is "not found". The transcript is kept in the conversation state (30-day TTL) and can be resumed (`GET /ai-api/v1/support/conversation?caseRef=`). The assistant never opens a second ticket for a linked case, and every write still goes through the existing confirmation token. After the assistant talks, the page re-reads the case from the backend.

## 4. Support reporting (staff)
`GET /api/v1/support/staff/report?range=today|yesterday|last7|last30|custom[&from&to]` aggregates in SQL, never in the browser. Days are counted in `app.support.business-zone` (default **UTC**, **REQUIRES BUSINESS DECISION**: set the real business time zone). Three kinds of number are kept apart and defined in the response: period metrics (events inside the period), "now" metrics (current state), and the cohort of the period's tickets that are still unresolved. The end of a period is exclusive. No SLA is defined, so none is reported.

## 5. Staff AI workspace and insights
- `POST /ai-api/v1/support/staff/cases/{ref}/assist` returns a plan tailored to the ticket: verified facts, what is unknown, ranked **hypotheses** (never a stated cause), steps from the per-category playbook (`app/support/playbooks.py`), knowledge excerpts (sample documents), an engineering signal where relevant, a customer-reply draft, and suggested actions.
- The assistant **never resolves, replies, assigns or changes anything**. Each action is a button the staff member confirms; the console then calls the existing backend endpoint with the staff member's own token, so the backend still checks role, lifecycle and version. Live order and shipment facts are read only for an ADMIN; a support executive is told plainly that they are not available.
- Resolving is never offered as ready: the person chooses the outcome, writes the note and ticks each verification.
- Action trail (`support_ai_actions`, append only): SUGGESTED → ATTEMPTED → SUCCEEDED or FAILED. A success cannot be recorded without an attempt, and an action id the plan never offered is refused. Only a whitelist of non-content fields is stored.
- Roles: `SUPPORT_EXECUTIVE` or `ADMIN`; anyone else gets 403. The frontend only hides the UI.
- `GET /ai-api/v1/support/staff/insights` compares the period with the previous equal period and labels every statement OBSERVATION or HYPOTHESIS, always with its denominator. Engineering correlation is labelled a correlation in time, not a cause.
- Chatbot effectiveness: the customer answers "Did this help?" (`POST /ai-api/v1/support/feedback`). A rate is reported **only** from those answers; with none, the response says a rate cannot be stated. A conversation that ended without a ticket is not counted as resolved.
- Limitation: web-form tickets do not record whether the customer tried the assistant first, so "could have been avoided" is a hypothesis.

## 6. Engineering AI: live and historical logs, incidents, investigation, prompt
- Near-live: `POST /ai-api/v1/engineering/refresh` re-reads the configured real sources (log files and the Postgres container). It is idempotent and throttled; a source that cannot be read is reported as unavailable with the reason. Freshness is LIVE, DELAYED, STALE or SYNTHETIC and describes the stored data, not the health of the service.
- Incidents are **derived groups** of log records by error signature (`SIG-xxxxxxxx` is a fingerprint, not a ticket id). Ranking: severity, recency, trend, volume (capped) and impact (capped), so raw volume alone never wins. Default 10 per page, with offset paging.
- Investigation uses logs before and after, cross-service requests, dependency errors, git history and source files. Git and source evidence are *candidates*; every cause is a HYPOTHESIS. There is no source of configuration-change data, and the report says so.
- The coding prompt is built only from redacted, safe fields and states that it fixes nothing.
- Access is ADMIN only on the server and every investigation is audited. Synthetic records are labelled and excluded from real views unless asked for.

## Evidence (this session)
Targeted tests and real-stack scripts only; the **full consolidated validation has not been re-run** after these changes.
- Python: unit + support suites 1052 passed; engineering integration 32; staff-assist unit 13, DB integration 2.
- Frontend: `tsc -b` clean, `pnpm lint` clean, support and assistant tests 25 passed (plus earlier checkout 18, engineering 22, fulfilment 8).
- Real stack: `e2e/ai/support_staff_e2e.py` ALL PASS; `e2e/ai/case_chat_and_incidents_e2e.py` ALL PASS (real Gemini, real logs); `e2e/ai/checkout_features_e2e.py` and `verify_return_scenarios.py` passed earlier.

## Open items
SLA, evidence upload and retention, JWT in `localStorage`, shared HS256 secret between services, per-instance rate limiting, no dependency scanner, business time zone, tax on discounts, unsupported return scenarios. See [STATUS.md](../STATUS.md).
