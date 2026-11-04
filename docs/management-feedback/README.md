# Management feedback: tickets, profiles/family, budget-aware recommendations

Design decisions: [ADR-012](../adr/ADR-012-profiles-and-budget-aware-recommendations.md). Built on the Phase 3 support domain ([ADR-011](../adr/ADR-011-customer-support-resolution.md)).

## What exists now

| Area | Customer | Staff / system |
|---|---|---|
| Tickets | "Raise a ticket" form (7 categories, optional order, review step, idempotent submit) from the support page, the account menu and the profile page; own ticket list/detail/replies; chatbot proposes a ticket and creates it only after confirmation | Dashboard: Open / In progress / Resolved / Unresolved / Unassigned, counts by category and priority; paginated table filtered by ticket number, status, category, priority, assignee ("assigned to me"), created date range, customer; assign, change priority (reason required, staff-only audit), status, reply, internal note, history |
| Profiles | Optional preferences (sports from the real catalogue, brands, categories, teams, usage level, price range, sizes, on/off switch); up to 6 family profiles (age band only, no login, hard delete); switch active profile; owner vs member clearly labelled | Stored in Java (`identity`), enforced server-side |
| Recommendations | Product page: Goes with this / Bought together / Similar / Premium, "Adjust budget", "Show premium alternatives", factual reasons, real prices, add to cart; home page "Picked for you" only when genuinely personalised | Deterministic pipeline, no model call, Prometheus metrics |

## APIs

New or changed (all in OpenAPI `/api-docs`):

* `POST /api/v1/support/staff/cases/{ref}/priority` (new); `GET /api/v1/support/staff/cases` gained `createdFrom`, `createdTo`, `customerId`.
* `GET /api/customers/me/profiles`, `GET|PUT /api/customers/me/profiles/active`, `GET /api/customers/me/profiles/{id}`, `PUT|DELETE /api/customers/me/profiles/{id}/preferences`, `POST /api/customers/me/family-profiles`, `PUT|DELETE /api/customers/me/family-profiles/{id}` (new).
* `GET /api/v1/insights/co-purchases?productId=` (new, anonymous aggregate).
* AI service `GET /v1/recommendations` gained `referenceProductId`, `scenario`, `profileId`, `budget` (legacy `productIds` mode unchanged). New READ tools `get_shopper_profile`, `get_co_purchases`.

## Migrations

* `V45__support_ticket_categories_and_indexes.sql`: categories `ORDER_ISSUE`, `TECHNICAL_ISSUE`; indexes on created date, category/status, priority/status.
* `V46__create_shopper_profiles.sql`: `shopper_profiles`, `shopper_active_profile`.

Both apply forward only (Flyway); nothing is dropped.

## Configuration (management can change these)

| Variable | Default | Meaning |
|---|---|---|
| `AI_RECO_COMPLEMENTARY_CEILING_PCT` | `0.20` | Complementary items are kept to this share of the reference product's effective price (₹10,000 → ₹2,000) |
| `AI_RECO_COMPLEMENTARY_FALLBACK_PCTS` | `0.30,0.40` | Labelled widening bands, tried only when fewer than `AI_RECO_MIN_CANDIDATES` fit |
| `AI_RECO_MIN_CANDIDATES` | `2` | Real candidates needed before a band is accepted |
| `AI_RECO_SIMILAR_TOLERANCE_PCT` | `0.25` | "Similar" price window (±) |
| `AI_RECO_UPGRADE_MIN_MULTIPLE` / `_MAX_MULTIPLE` | `1.15` / `2.0` | "Premium" window as multiples of the reference price |
| `AI_RECO_COPURCHASE_MIN_ORDERS` | `2` | Distinct orders required to call a pair "bought together" (also enforced by Java: `app.recommendations.copurchase-min-orders`) |
| `AI_RECO_MAX_PER_GROUP` | `2` | Diversity: at most this many results per category and per brand |

An explicit customer budget always overrides the percentage and is never widened.

## Run and demo

1. Start the stack (`e2e/ai/start-backend.sh`, `e2e/ai/start-ai.sh`, `pnpm exec vite` in `frontend/`). Migrations V45/V46 apply on backend start.
2. Customer: sign in → account menu → *Contact support* → raise a ticket. Profile page → add Tennis, price up to ₹12,000 → add a family member → switch. Open "Head Tennis Racquet - Performance" (≈₹10,499 → ceiling ₹2,099.80) and use *Adjust budget* / *Show premium alternatives*.
3. Staff: grant `SUPPORT_EXECUTIVE` by SQL (there is no role UI): `INSERT INTO customer_roles (customer_id, role) SELECT id, 'SUPPORT_EXECUTIVE' FROM customers WHERE email='…'`, then open `/support-cases`.

## Verification commands and results (2026-10-13)

| Check | Command | Result |
|---|---|---|
| Backend unit | `mvn package` (backend/) | 548 tests, 0 failures |
| Backend integration (Testcontainers) | `mvn test -Pintegration-test` with `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine -Dapi.version=1.44` | 46 tests, 0 failures |
| AI service | `uv run pytest -m "not integration and not live"` | 1158 passed |
| Frontend | `pnpm exec vitest run`, `pnpm build` | 33 tests passed; build OK |
| Real-stack API E2E (new) | `uv run --project ai-service python e2e/ai/mgmt_feedback_e2e.py` | 66/66 |
| Real-browser E2E (new) | `cd e2e && node mgmt-feedback-ui.mjs ../scratch_shots` | 16/16 steps |
| Existing API E2E | `bash e2e/api-e2e.sh` | 122 passed, 0 failed |
| Existing browser checkout/payment/refund | `node e2e/ui-flow.mjs happy …` | passed, no console/5xx errors |

## Known gaps (honest list)

* **Evidence upload is not built**: the platform has no file-storage architecture (storage, scanning, retention); the form says so.
* **No SLA exists, so no "overdue" metric** is shown.
* **Catalogue limits**: only 2 of 134 products are labelled junior, so a child's Cricket profile is honestly empty (adult-only products are never shown to under-13 profiles); the nearest product to ₹10,000 is ₹10,499 (ceiling ₹2,099.80); ₹10,000 exactly is covered by unit tests.
* "Frequently bought together" shows data only where ≥2 real orders back a pair; demo data has little.
* Not built: demographic fields (gender, language), recommendation impression/click analytics, home sections beyond "Picked for you"; the Phase 1/2/3 live-LLM suites were not re-run in this pass (Gemini quota), only their unit suites.
* Chat "similar" requests keep using the existing alternative search; the SIMILAR scenario is reached from the product page and the API.
