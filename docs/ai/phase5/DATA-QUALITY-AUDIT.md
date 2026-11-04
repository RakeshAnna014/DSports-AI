# Phase 5 data-quality audit (2026-10-15)

Scope: the Product & Supplier Intelligence data in the AI database (`dsports_ai`, tables `src_*`) as it stood before the overhaul. Method: trace every suspicious record to the code path that wrote it, decide whether it is real, test, or unknowable, and fix the cause rather than clean the data. **Nothing was deleted.**

## Findings: where each suspicious record came from

| What looked wrong | Origin (traced) | Real or not | Root cause | Treatment |
|---|---|---|---|---|
| `Zorball…` / `Zorbball… Paddles` demand records, many of them | `e2e/ai/sourcing_e2e.py`: two throw-away customers ask the real assistant for a made-up product word (`zorbball` + 5 random letters), and the research run turned that into an "opportunity" | **Test data** (real mechanism, fake demand) | The run had no notion of provenance, and a customer search was stored as if it were a product | Classified `TEST_FIXTURE` by the migration; new runs store searches as `DEMAND_GAP` records, never products |
| `E2E Smash Racket vyeef` (and ten like it) | `e2e/sourcing-ui.mjs` adds a product through the real form with a random suffix | **Test data** | The form had no "this is test data" option, so test entries were indistinguishable from buyer entries | Classified `TEST_FIXTURE`; the add-product and add-supplier forms now have an explicit test checkbox, and the browser journey uses it |
| `E2E Supplier P 3477` (and Q) | Same two scripts create two suppliers per run | **Test data** | Same | Classified `TEST_FIXTURE` (40 supplier rows); hidden from the live supplier directory |
| Repeated 43.9% margins | The scripts use identical inputs every run (unit price 1000, freight 5000, 500 units, selling price 1800 gives 43.9%). The arithmetic is correct and deterministic; the repetition is the same hand-calculated example saved against many test products | Real arithmetic over **test** inputs | The saved calculation recorded no product, offer, quantity or currency, so a margin shown next to a product could not be tied to what produced it, and test margins fed live KPIs | Every calculation now stores a `basis` (product, offer, quantity, currency, price, tax basis, time) and a label. The margin KPI counts only margins saved against an active offer a buyer entered or verified, on live records. Tests prove two products with different costs never share a calculation |
| High scores on weak evidence | Scoring v1 gave a number whenever 35% of the model had data, and fixtures scored like real products | Computed honestly from weak or fixture inputs | One blended number hid how thin the evidence was; no provenance in the score | Scoring v2 reports opportunity, evidence confidence, data completeness, supplier risk and financial status separately. A single weak source cannot give high confidence, unknown provenance is capped at low confidence, and "Not enough evidence" replaces an invented score |
| Records "approved for onboarding" with incomplete proposals | The scripts approve straight through, and the proposal listed what was missing but approval did not depend on it | Test flow | Approval and catalogue readiness were one thing | Two separate approvals (business opportunity, then sourcing preparation) and an independent server-side readiness check. Approval never orders, prices or publishes. Seven demand gaps that an earlier version had moved to "approved for onboarding" were returned to review (audited, history kept) |
| Runs with zero external records | No research source was configured, so a run could only read customer searches (it reported `PARTIAL` with an "unavailable" reason, which was honest but useless) | Honest | No real source existed | A real source now exists (the manufacturer's own site, below). A run with no source still says so |
| Table-only UI, no pictures | The first UI was a data grid | n/a | No image model | Card grid with pictures, filters, a 9-section detail view |

Conclusion: every suspicious record was produced by this repository's own scripts. None was fabricated by the AI, and none came from a real buyer. The defect was structural: the system could not tell test, demo, buyer, customer-demand and live-source records apart, and used all of them alike.

## How records are classified now

`provenance`: `LIVE_EXTERNAL_SOURCE`, `BUYER_ENTERED`, `CUSTOMER_DEMAND_SIGNAL`, `DEMO_FIXTURE`, `TEST_FIXTURE`, `UNKNOWN_PROVENANCE`. The first three are "live"; fixtures and unknown records are excluded from the dashboard, the default lists, supplier directory, scoring inputs, margin KPIs and the assistant, and are counted separately ("excluded").

Migration rules (idempotent, `schema_sourcing.sql`, run at service start): a row with no provenance becomes `DEMO_FIXTURE` if it was demo data; `TEST_FIXTURE` if it matches the repository's own script patterns (`E2E …`, brand `e2e…`, `zorb…`); `CUSTOMER_DEMAND_SIGNAL` if it came from customer searches (and `record_kind = DEMAND_GAP`); `LIVE_EXTERNAL_SOURCE` only if it came from a research run **and has a stored source URL**; `BUYER_ENTERED` if entered through the API; otherwise `UNKNOWN_PROVENANCE`. A research-run row with no source URL is deliberately not trusted as live.

A buyer can reclassify a record with a reason (audited): to buyer-entered, test or demo, never to "live external source" (only a run sets that). Unknown records cannot be approved until classified.

## State of the real database after the overhaul (read from `dsports_ai`)

| Table | Provenance | Rows |
|---|---|---|
| Product candidates | live external source (Victor Sport official site) | 15 |
| Product candidates | test fixture | 11 |
| Demand gaps | customer demand signal | 2 |
| Demand gaps | test fixture | 12 |
| Suppliers | live external source (the brand's local distributor, unverified) | 1 |
| Suppliers | test fixture | 40 |
| Quotes | test fixture | 44 (no real quotation exists) |

The 15 live products are real product pages read from the manufacturer's site by the research runs in this session (see the README). Two of them, "Thruster Onigiri" and "THRUSTER Onigiri", are two distinct pages of the same site with the same name; they are flagged as probable duplicates for a buyer to review and are not merged.

## What was deliberately not done

- No row was deleted, and no test fixture was removed: tests rely on reproducible fixtures, and the filter makes them harmless.
- No legacy decision history was rewritten. Where a state was corrected, a `LEGACY_STATE_CORRECTED` decision records the old state, the new state and the reason.
- The classification rules are pattern based. A real record whose name starts with `E2E ` or `zorb` would be mislabelled as test data; the buyer reclassification action exists for that.
