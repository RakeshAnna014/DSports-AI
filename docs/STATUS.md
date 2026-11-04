# DSports-AI: completion status (single source of truth)

Audited and re-verified on **2026-10-14** against the running local stack. Statuses: `VERIFIED_COMPLETE`, `IMPLEMENTED_NOT_FULLY_VERIFIED`, `PARTIALLY_IMPLEMENTED`, `PENDING`, `BLOCKED`, `DEFERRED_WITH_REASON`. Nothing is marked complete from documentation alone: "evidence" below is a command that was executed in this audit, with its result. Where an item was not re-run, it says so. How to reproduce everything: [SETUP-AND-DEMO.md](SETUP-AND-DEMO.md) (`bash e2e/validate-all.sh`).

## 0. Stabilization pass (2026-10-16): current evidence and capability matrix

Scope: documentation, validation and security of what exists; no new features. Branch `dsports_Code`, HEAD `4552785`, everything uncommitted (342 changed paths at the last run; the staged deletion of `frontend/src/components/ProductFilters.tsx` is preserved). Where this section disagrees with older sections below, this section wins.

### Validation results (executed in this pass)
| Suite | Command | Result |
|---|---|---|
| Consolidated, real stack | `bash e2e/validate-all.sh` (run `20261016-135413`, about 1 hour) | **PASS 64, FAIL 2, BLOCKED 0, SKIP 2** |
| Failed steps, rerun | `bash e2e/validate-all.sh --only p6-` (run `20261016-145251`) | `p6-e2e` and `p6-browser` **PASS** (all 9 selected steps PASS) |
| Java | in the consolidated run: `java-build` (unit) and `java-integration` | PASS: 610 unit tests (581 in an earlier run without the Docker-dependent tests) and 63 integration tests, 0 failures |
| Python unit | `uv run pytest -q` | 1641 passed |
| Python integration | `uv run pytest -m integration` with three throwaway databases | 184 passed |
| Frontend | `pnpm exec tsc -b`, `pnpm lint`, `pnpm exec vitest run`, `pnpm build` | 0 type errors, 0 lint warnings, 198 passed, build OK |
| Security | `sec-unit`, `sec-fe-audit`, `sec-e2e` (consolidated) | PASS (`security_e2e.py` 47 checks) |

Not run: `inv-eval-holdout2` and `sup-eval-holdout` (SKIP on purpose: consumed held-out sets, first runs preserved); OWASP dependency-check and `pip-audit` (no scanner configured); any penetration test. The two failures in the first run were investigated in a follow-up and are explained in [VALIDATION-MATRIX.md](VALIDATION-MATRIX.md): `p6-browser` failed because `planning_e2e.py` (which had crashed first) left an approved correction that the browser journey's world cleanup did not remove; reproduced and fixed in the harness (`planning_data.py`), regression `--only p6-` 9/9 PASS (run `20261016-164235`). **Still open:** the trigger of the `p6-e2e` crash, a one-off `PermissionError` launching `docker exec`, whose cause was not found.

### Capability matrix
Legend: V = implemented and verified (executed evidence above or in the cited document); I = implemented, not fully verified; P = partial; N = not implemented; B = blocked by configuration, data or decision.

| Capability | Class | Evidence | Limits that matter |
|---|---|---|---|
| Authentication, JWT (iss/aud/exp), roles | V | `SecurityPolicyTest` (33), route sweep, `security_e2e` | roles are granted by SQL; no revocation (15-minute lifetime); tokens in `localStorage` |
| Catalogue, price, stock, cart | V | `api-e2e`, `ui-flow`, Java tests | catalogue and prices are demonstration seed data |
| Checkout, orders, order state machine | V | `api-e2e`, Java integration | orders in the dev database are test-generated |
| Payments | P | capture/refund/timeout paths tested | **mock gateway only**; no real provider |
| Refunds, returns, shipments, notifications | V | `api-e2e` | in-app notifications only; shipment status is admin-driven |
| Wishlist, reviews, promotions | V / I | e2e and Java tests; wishlist has no module unit tests | |
| Support cases (Java) and staff console | V | `mgmt-api`, `mgmt-browser`, `sup-*` | no evidence upload, no SLA |
| Customer AI assistant, RAG, MCP, recommendations | V | `py-live`, `scenarios-A-M`, `eval-main`, `cust-*` | needs the model; knowledge base is synthetic sample content |
| Support assistance and case chat | V | `sup-live`, `sup-eval-dev`, `master-case-chat-incidents` | held-out miss H08 remains visible |
| Inventory Intelligence | V (pipeline) | `inv-*` | all history is **synthetic**; accuracy is not business accuracy |
| Engineering AI | V (local POC) | `eng-*` | frontend logs, central platform, alerting not connected |
| Product & Supplier Intelligence | V / P | `p5-*`, `sourcing_live_e2e` | one real source with no prices; margins need buyer-entered quotes; no procurement link |
| Sales & Inventory Planning | V (workflow) / P (data) | `p6-*` | store network, store history and stock are **synthetic**; live history is a few weeks; recommendations stay drafts |
| Security controls | V (project's own tests) | `sec-*`, [security/](security/THREAT-MODEL.md) | no penetration test; residual risks R1-R11 |
| Franchise, billing, admin back office | N | empty module shells | |
| Dependency scanning (Java, Python), CI pipeline | N / B | none configured | |
| Retention, backup, restore | N | none defined | |

### Distinctions this system requires
Live commerce data vs synthetic: only catalogue, orders and payments created through the Java API are live records of this system, and in the development database they are test-generated; all store, demand-history, stock-position and supplier data in inventory and planning is synthetic and labelled. Online orders vs physical-store sales: only online orders exist as real records. Real quotations vs listings and estimates: a quotation exists only if a buyer entered or verified it. Forecast output vs business accuracy: held-out accuracy is measured on synthetic history. AI recommendation vs executed action: no AI path orders, moves stock, publishes, prices, refunds or resolves; writes need explicit confirmation and are limited to the caller's own return, reorder-to-cart and support case. **The application is not production-ready**: see the residual risks and missing items above.

## 1. Baseline (before this audit's changes)

* Branch `dsports_Code`, HEAD `4552785` ("complete PR #18 Payment Management"). **Nothing since that commit has ever been committed**: 267 changed paths at the start (117 modified, 7 deleted, 143 untracked paths; 1 pre-existing staged file `frontend/src/components/ProductFilters.tsx`). All of it is earlier user/AI work and was preserved untouched except where listed in §6.
* Ports verified: frontend 3000, Java 8080, AI 8090, Tika 9998, Postgres 5432 (container `dsports-postgres`, databases `dsports`, `dsports_ai`, `dsports_ai_test`, `dsports_e2e_val`).
* Pre-existing failures found: `pnpm lint` fails because **no ESLint configuration exists** (not caused by this work); `.gitignore` did not ignore screenshots/stackdumps (fixed). No pre-existing failing tests were found: the first consolidated run's two customer-journey failures were caused by a regression introduced earlier in this work (§6) and fixed.

## 2. Completion matrix

| # | Workstream | Evidence executed 2026-10-14 | Status | Remaining gap |
|---|---|---|---|---|
| 1 | Commerce backend (catalogue, cart, checkout, orders, payments, refunds, returns, shipments) | Java unit 548 passed and integration (Testcontainers) 46 passed (run earlier in this audit session; **no Java change since**, so not re-run); API E2E on a fresh database 122/122; browser checkout→payment→refund `ui-flow` passed; `fe-catalog` 134 products, 11 sports | VERIFIED_COMPLETE | No rate limiting on Java APIs; CORS handled only by the Vite proxy (no direct-browser config); both documented in `docs/baseline/` |
| 2 | Customer AI: discovery, comparison, context, orders, returns, support journeys | `py-live` 63 passed (real Vertex, Tika+OCR, MCP over HTTP); scenarios A–M 64/64 (real Chrome/Gemini); journeys drawer/commerce/context/redesign + multi-sport all PASS; AI-down shop flow no console/5xx errors | VERIFIED_COMPLETE | Catalogue has 11 sports but kit/role data exists for each; junior coverage thin (§3) |
| 3 | MCP and tool authorization | `tests/unit` role/identity matrices; `py-live` MCP | VERIFIED_COMPLETE | none found |
| 4 | RAG (Tika, OCR, chunking, pgvector, citations, injection defence) | `py-integration` 97 passed (real Postgres/pgvector), `py-live` | VERIFIED_COMPLETE | Corpus is **SYNTHETIC demo content**, labelled; no real manufacturer documents |
| 5 | Tiered routing, usage and cost | Unit and live tests; Phase 1 eval `eval-main` 83/83 cases (regression/dev set) | VERIFIED_COMPLETE | Costs are **estimates** from `AI_PRICE_TABLE_JSON`, labelled; no measured billing figures; Gemini billing shows an ended free trial |
| 6 | Customer AI evaluation integrity | Phase 1 held-out v1: 26/26 (regression: set consumed); support held-out: preserved results show **23/24**, only **H08** fails (`intent 'CASE_ESCALATE' not in ['COMPLAINT']`), expectation untouched; not re-run (first run preserved on purpose) | IMPLEMENTED_NOT_FULLY_VERIFIED | **H08 is a genuine open miss.** `holdout.yaml` hash differs from the recorded one (harness `copies` entries added later; disclosed in `docs/ai/phase3/10-evaluation.md`); two regression result files carry a wrong "FIRST RUN (unseen)" label (disclosed, not edited); file is not under version control |
| 7 | Support tickets: customer form, staff console, priority with reason, audit, role gate | `mgmt-api` 66/66 checks (real stack); `mgmt-browser` 16/16 steps (real Chrome); Java support integration 15; `sup-live` 11, `sup-unit` 167, `sup-eval-dev` 45/45 (51/51 turns), `sup-browser` PASS | VERIFIED_COMPLETE | Evidence upload BLOCKED, overdue/SLA BLOCKED (§3) |
| 8 | Profiles, family profiles, personalization | `mgmt-api`, `mgmt-browser`, frontend 41 tests; no endpoint assigns roles; family profiles are rows with no credentials | VERIFIED_COMPLETE | Junior catalogue coverage (§3) |
| 9 | Budget-aware recommendations (complementary 20 %, similar, bought-together, premium, personalised) | 26 engine tests incl. new boundary cases (zero/negative budget → 400, missing price → 422, paise rounding on the ceiling, duplicates); `mgmt-api` live boundaries | VERIFIED_COMPLETE | Bought-together shows data only with ≥2 real orders (sparse by design) |
| 10 | Inventory Intelligence | `inv-unit` 491; numeric dev/holdout 30/30 + 30/30; `inv-measure` no findings; `inv-eval-dev` 56/56 turns, no threshold missed; `inv-browser` and `inv-failures` PASS (real Chrome) | VERIFIED_COMPLETE (pipeline, on synthetic data) | See §4: all history is POC SYNTHETIC; no real forecast can be validated |
| 11 | Engineering AI (log monitoring, incident intelligence) | `eng-unit` 51, `eng-integration` 18 (real Postgres), `eng-eval` 13/13 checks, `eng-e2e` 24/24 on the live service incl. a real Gemini summary accepted by the evidence guard; frontend page tests | VERIFIED_COMPLETE (local-first POC) | Frontend logs, a central platform, automatic deployment feed, metrics/traces are not connected (§5) |
| 12 | Security review | Role gates (401/403) tested for tickets, staff, inventory, engineering; prompt-injection/IDOR suites (`py-unit`, scenarios, eval); secret scan found only documented local-dev defaults (`dsports123`) and fake test keys; no role-assignment endpoint | IMPLEMENTED_NOT_FULLY_VERIFIED | No external penetration test; no Java rate limiting; JWT in `localStorage` (XSS surface, documented in `docs/baseline/`) |
| 13 | Migrations | V45, V46 applied forward to the real DB at backend start and by Testcontainers tests; V44 earlier | VERIFIED_COMPLETE | Forward-only: no rollback scripts (Flyway community) |
| 14 | Frontend quality | `tsc -b` exit 0, `vite build` OK, vitest 41/41 | VERIFIED_COMPLETE | ESLint config added later; see §8 (supersedes "BLOCKED") |
| 15 | Documentation | This file, SETUP-AND-DEMO, ARCHITECTURE-OVERVIEW, ADR-012/013, phase 4 doc, README, roadmap matrix | VERIFIED_COMPLETE | Older per-phase docs keep dated figures, flagged where superseded |
| 16 | Git hygiene | Nothing committed | DEFERRED_WITH_REASON | 269 mixed paths (earlier work, this audit's work, one pre-existing staged file) cannot be split into accurate logical commits without risking attribution errors; the owner should review and commit |

## 3. Known gaps, classified

| Gap | Class | Decision |
|---|---|---|
| Support-ticket evidence/photo upload | Blocked by a product/security decision | No file-storage design exists. Needs object storage, type/size limits, malware scanning, access control and retention. **Not built**; no insecure local storage added |
| Overdue-ticket metric / SLA | Blocked by a product decision | No SLA exists; clock semantics (business hours, pause while waiting for customer) undefined. **Not built**, not invented |
| Junior-product coverage (2 of 134 products labelled junior) | High-value enhancement | Deferred: changing the catalogue seed (Flyway V43 spec, `kits.json`) alters evaluation fixtures and counts (`fe-catalog` 134, eval 83) and needs a deliberate catalogue decision. The child-profile rule stays strict: adult-only items are never shown to under-13 profiles; the UI says so when empty |
| Sparse bought-together evidence | Optional / by design | Requires real order volume; demo data has little |
| Language/gender preferences, impression/click analytics, extra home sections | Optional future work | Not implemented to avoid unnecessary personal data and invasive tracking |
| H08 held-out miss | Required for a clean eval, not a product defect | Left visible; changing the expectation or the held-out file would defeat the held-out |

## 4. Inventory Intelligence: data and forecast quality (counted 2026-10-14)

Real commerce data: 1,895 orders on **10 distinct days** (2026-09-02 to 2026-10-13); 134 products, **18 ever ordered**; no warehouse concept in Java; no cost, supplier, lead-time or event data. The forecasting mart in `dsports_ai` is **100 % `POC_SYNTHETIC`/POC reference** (488,093 daily demand rows, 2024-10-08 to 2026-10-07, 134 SKUs, 244,550 stock snapshots, 309 purchase orders, 15 events, 5 regions). Time-based holdout (last 90 days): ridge WAPE 0.386 vs 28-day moving average 0.431 (10.5 % better), interval coverage 0.75 to 0.86 at nominal 0.80. These figures show the pipeline works; they **say nothing about accuracy on real DSports demand**, which has far too little history to model. Details: `docs/ai/phase2/README.md`.

## 5. Engineering AI: what is connected

Connected and verified: backend stdout log (22,805 lines parsed), AI-service JSON log, Postgres container logs, a labelled synthetic incident. Not connected: frontend logs, any central logging platform, CI/CD deployments (recorded manually), metrics and traces. The assistant can state facts from log records, group errors, correlate by correlation id, report deployment *timing*, and offer labelled LOW/MEDIUM-confidence hypotheses; it cannot confirm a root cause, execute remediation, or see anything not logged. Real finding produced from real logs: the "Could not expire unpaid order" warning recurring every minute. Details: `docs/ai/phase4/README.md`.

## 6. Changes made during this audit (beyond the earlier phases)

* **Regression found and fixed**: my management-feedback home-page change replaced the original order-history "Personalized for you" section; consolidated validation failed `cust-journey-redesign` and `cust-multisport`. `PersonalizedSection` now keeps the original behaviour for an account holder without preferences, uses the profile-driven scenario when preferences exist or a family profile is active, and personalises nothing when switched off (tests added; both journeys and the checkout flow pass again).
* Engineering AI trend rule corrected (a 513→1005 rise was called STEADY because the threshold was exactly 2×; now 1.5×) with tests.
* Validation harness: added management-feedback and Engineering AI steps to `e2e/validate-all.sh`; fixed three harness environment bugs (Git Bash lacking `node`/`docker`/`uv` on PATH); made the real-log count check race-free.
* Added 6 recommendation edge-case tests; `.gitignore` rules for screenshots, stackdumps and credential-like files; removed a stray `bash.exe.stackdump`.
* Docs: README, STATUS, SETUP-AND-DEMO, ARCHITECTURE-OVERVIEW, Phase 2 data audit, a pnpm correction in `docs/ai/12-local-development.md`.

## 7. Consolidated validation result (this audit)

`bash e2e/validate-all.sh --skip-build` (java-build/java-integration skipped, see row 1): first run PASS 37, FAIL 6 (2 real regressions from §6, 1 load-sensitive checkout step that passed on re-run, 3 harness PATH problems), SKIP 4 (consumed held-out sets, deliberately). After fixes the failed steps were re-run individually with `--only` and all PASS (`fe-unit`, `mgmt-`, `eng-e2e` 24/24, `cust-` 9/9). Evidence: `e2e/validation-results/20261014-114019/` (the full run) and the later `--only` re-run directories; their `SUMMARY.md`/`summary.tsv` files are untracked and the per-step `*.log` files are git-ignored (`*.log`), so the logs stay local.

## 8. Release-hardening addendum (2026-10-14, later session)

Supersedes the matching statements above (rows 1, 6, 12, 14 and §3 H08). Status words follow the release brief: COMPLETE AND VERIFIED, IMPLEMENTED / PARTIALLY VERIFIED, POC ONLY, SYNTHETIC DATA, BLOCKED BY EXTERNAL DEPENDENCY, REQUIRES BUSINESS DECISION, NOT IMPLEMENTED, KNOWN SECURITY RISK.

| Item | Status | Why / impact | Workaround | Exact next action | Blocks demo? |
|---|---|---|---|---|---|
| ESLint | COMPLETE AND VERIFIED | `frontend/eslint.config.js` added; 16 findings fixed in code (typed error handling via `getApiErrorMessage`, hook dependencies, chip colour typing); one justified line-level disable on `InvBlock.data`. `pnpm lint` exit 0, `tsc -b` exit 0, vitest 41/41, `vite build` OK (all run this session) | — | — | No |
| Java rate limiting (auth per address, tickets per user) | COMPLETE AND VERIFIED for the POC (auth limit live; ticket limit unit-only); POC ONLY for multi-instance | 6 unit tests; live second instance returned 401×3 then 429 + `Retry-After: 60`; in-memory per instance. Full backend suite after the change: 554 tests, 0 failures, 0 errors, 0 skipped (`mvn test`, run this session; was 548 before the 6 new tests) | Disable or raise with `RATE_LIMIT_*` env vars | Restart the :8080 backend from a fresh build to activate it; move counters to a shared store before multi-instance deployment | No (production: yes) |
| H08 (support held-out) | PARTIALLY VERIFIED | A real defect (human-request lost with no open case) was fixed with new unit tests; the held-out intent-label expectation still disagrees with the router. The held-out set is now **consumed**; no post-fix held-out run exists | None | Write a fresh unseen support set before any new unseen claim | No |
| JWT in `localStorage` | KNOWN SECURITY RISK | XSS can read tokens | Short access tokens, no HTML injection sinks in the SPA (0 uses of `dangerouslySetInnerHTML`) | ADR then cookie-based session | Production only |
| Ticket evidence upload | NOT IMPLEMENTED — REQUIRES BUSINESS DECISION | No storage, retention or scanning exists | Text-only tickets | Decide storage + retention, then build with validation tests | No |
| Ticket SLA / overdue | NOT IMPLEMENTED — REQUIRES BUSINESS DECISION | No official SLA; none invented or displayed | None | Business to supply SLA clock rules | No |
| Dependency vulnerability scan | NOT IMPLEMENTED | No scanner in the repo; none run | — | Add `pnpm audit` / OWASP check to CI | No |
| Inventory history | SYNTHETIC DATA | See §4 | Labelled in UI | Real warehouse/cost/supplier feeds | No |

Security detail: [SECURITY.md](SECURITY.md). H08 detail: `docs/ai/phase3/10-evaluation.md`.

## 9. Master implementation addendum (2026-10-15)

Detail: [docs/ai/17-promotions-reporting-and-staff-assist.md](ai/17-promotions-reporting-and-staff-assist.md) and [docs/ai/16-demo-fulfilment-and-return-scenarios.md](ai/16-demo-fulfilment-and-return-scenarios.md). The full consolidated validation **was** re-run afterwards: see §10.

| Item | Status | Note |
|---|---|---|
| Checkout address 500 | COMPLETE AND VERIFIED | Unmapped identity error codes now map to 4xx; form validates first |
| Promo codes and AI offers | IMPLEMENTED / PARTIALLY VERIFIED | Real-stack script passed; seeded offers are DEMO. REQUIRES BUSINESS DECISION: tax on discounted orders |
| Fulfilment console and Return scenarios | IMPLEMENTED / PARTIALLY VERIFIED, DEMO DATA | R3 is SYNTHETIC back-dating; unsupported scenarios are NOT IMPLEMENTED, REQUIRES BUSINESS DECISION |
| Case-linked support chat | COMPLETE AND VERIFIED (real Gemini, real backend, targeted) | |
| Engineering incidents, investigation, coding prompt | COMPLETE AND VERIFIED on real local logs (targeted) | Causes are hypotheses; no config-change source exists |
| Support reporting by date | COMPLETE AND VERIFIED (targeted) | REQUIRES BUSINESS DECISION: business time zone (default UTC) |
| Staff AI workspace, playbooks, action trail | COMPLETE AND VERIFIED (targeted, real stack) | Advice only; the person confirms and resolves |
| AI support insights and chatbot effectiveness | IMPLEMENTED / PARTIALLY VERIFIED | Effectiveness uses customer feedback only; no data yet means no rate is stated |

**Unpaid-order finding (investigated, no logic changed).** A payment left in PROCESSING is never reconciled or timed out: there is no job that expires or re-checks it, so the order stays waiting indefinitely (two stale MOCK payments observed locally). REQUIRES BUSINESS DECISION: the timeout, and whether it cancels the order or asks the provider.

## 10. Final integration validation and Phase 5 (2026-10-15)

`bash e2e/validate-all.sh` (full, real stack: Java build + integration, API E2E, Python unit/integration/live, evaluations, browser journeys, support, management, engineering, master-mission and Phase 5 steps). Evidence: `e2e/validation-results/20261015-145027/` (the last full run) plus the targeted re-runs named below; logs stay local.

| Run | Result |
|---|---|
| First full run (`20261015-113951`) | 3 FAIL: `cust-journey-context`, `cust-multisport` (M12), `cust-baseline-flow` |
| Second full run (`20261015-134015`) | 2 FAIL: `py-integration`, `cust-baseline-flow` (the earlier three were fixed or re-classified, see below) |
| Last full run (`20261015-145027`) | **55 PASS, 1 FAIL (`p5-e2e`), 0 BLOCKED, 2 SKIP** (the two skips are the consumed held-out sets, by design). Java 572 tests, 0 failures; frontend 112 tests; Python unit 1355; Python integration 124; API E2E 122/122 |
| After the last fix: `--only p5-` | PASS 7: `p5-unit`, `p5-db-integration`, `p5-e2e` (also 3 of 3 stand-alone), `p5-browser` |
| After the last fix: `--only py-integration` | PASS, 124 passed (12m48s). A first attempt ended with 1 fixture-setup timeout in `test_rag_db.py` after 4h14m of wall-clock time: the machine had been suspended mid-run (ENVIRONMENT, not a product result); the re-run on an awake machine passed |

**Every failure, its cause, and the fix (nothing was skipped or weakened to pass):**

| Step | Classification | Root cause | Fix |
|---|---|---|---|
| `cust-journey-context` | PRODUCT DEFECT | The chat request always carried `caseRef: null`, and the browser test asserts the exact request fields | Send `caseRef` only for a case-linked conversation |
| `cust-baseline-flow` (address step) | HARNESS (deliberate product change) | Country became a select list (a free-text value used to reach the backend and fail with HTTP 500); the script still typed into it | Script picks the option |
| `cust-baseline-flow` (payment step, ~50% failure) | HARNESS RACE | `getByRole('button', {name:'Continue'})` is a substring match and also resolved the checkout page's still-mounted "Continue to Payment" button during the route change; the click never reached the payment page (proved with a probe that showed the spinner and an unchanged step) | `exact: true`. 6 of 6 runs pass afterwards (was about 3 of 6) |
| `cust-multisport` M12 | TIMING / DEMO DATA | The seeded `demo.cricket` order was delivered 8 Oct; its 7-day return window (real policy) expired on 15 Oct, so "not eligible" was the correct answer | `e2e/ai/refresh_cricket_return_order.py` adds one fresh delivered DEMO order when the newest is stale; wired as `cust-multisport-prep` |
| `py-integration` (`test_the_ai_database_holds_no_commerce_data`) | TEST GUARD vs NEW SCHEMA | `src_opportunities.selling_price` is the buyer's planned price for a candidate product, not a DSports price, but the guard cannot tell | Guard extended (not relaxed): `src_*` tables get their own rule: no customer, order, payment, stock or MRP columns, and `selling_price` only on `src_opportunities` |
| `--only cust-` killed the backend | HARNESS | The Java-build branch called `kill_port 8080` even when no Java step was selected | Guarded with `relevant java-` |
| `p5-e2e` (once) | PRODUCT WEAKNESS | Unmet-demand signals were grouped by the exact phrase; the assistant's slot extraction varies ("x paddles" vs "x"), splitting one demand | Signals now carry a key term (the longest term unknown to the catalogue) and are grouped by it; the fullest wording names the opportunity. Integration test added; 3 of 3 real-stack runs pass |

Real defects the full pass found and fixed in my own new work (not caught by unit tests): quote dates arrived as ISO strings and asyncpg needs `date` objects (HTTP 500), a full page load of `/product-intelligence` and `/engineering-ai` showed "restricted" to a signed-in user until the session was hydrated, an unknown stored role would have broken a user's login (`UserRole.valueOf`), and zero-row search results never occur with semantic search so unmet demand needed a term-level rule.

### Phase 5: Product & Supplier Intelligence

Detail: [docs/ai/phase5/README.md](ai/phase5/README.md), [ADR-014](adr/ADR-014-product-supplier-intelligence.md).

| Item | Status | Note |
|---|---|---|
| Opportunity discovery from customer demand, buyer entries and DEMO data | COMPLETE AND VERIFIED | 89 unit tests, 11 DB integration tests, 44-check real-stack script, 20-step browser journey |
| Market research from external sources | **ONE REAL SOURCE CONNECTED AND LIVE-VERIFIED** (Victor Sport official site, badminton); generic feed adapter implemented, no feed supplied | Live runs read real product pages, specifications, images and the brand's local distributor. The pages have no prices. Yonex/Nivia sites were not usable from the test machine. With no source configured a run is `PARTIAL` with an explicit "unavailable" reason |
| Data-quality overhaul (provenance, demand gaps, product identity, images, supplier ladder, scoring v2, margin basis, separate approval and readiness) | COMPLETE AND VERIFIED on the real stack | [DATA-QUALITY-AUDIT.md](ai/phase5/DATA-QUALITY-AUDIT.md). All suspicious records traced to the repository's own e2e scripts, none deleted; 12 gaps relabelled test data and 7 wrongly approved gaps returned to review (audited). Verified by unit tests, 14 DB integration tests, the API e2e, the live e2e and a browser journey that runs live research through the UI |
| Suppliers, quotes (entered / verified / public listing / AI estimate), comparison | COMPLETE AND VERIFIED | Hand-computed numbers reproduced on the real stack and in the browser |
| Landed cost, margin, break-even, target price, sensitivity | COMPLETE AND VERIFIED | Deterministic `Decimal` code, 27 tests; formulas in the Phase 5 README. REQUIRES BUSINESS DECISION: duty/tax treatment and allocation rules |
| Score with separate confidence and supplier risk | COMPLETE AND VERIFIED | `INSUFFICIENT_EVIDENCE` when under 35% of the model has data |
| Workflow, roles, audit, onboarding proposal | COMPLETE AND VERIFIED | Approval changes nothing in commerce data (proved by before/after snapshots); proposals are drafts, the catalogue has no draft API |
| Seasonal and sporting-event opportunities | NOT IMPLEMENTED | No event calendar source |
| Inventory signal for new products | NOT AVAILABLE | Inventory AI forecasts only products with sales history; the score lists the dimension as missing |
| Buyer role administration | NOT IMPLEMENTED | Roles are granted by SQL (`customer_roles`); the Java identity module now accepts `PROCUREMENT_BUYER` and `CATEGORY_MANAGER` |

### Phase 6: Sales & Inventory Planning (2026-10-16)

Details: `docs/ai/phase6/README.md`, ADR-015. Rules in AGENTS.md.

| Capability | Status | Evidence |
|---|---|---|
| Java sales feed (ADMIN, paged, no personal data) and approved-returns reader | COMPLETE AND VERIFIED | 5 + 2 Testcontainers integration tests; reconciled live: 2,737 lines ingested, 2 excluded |
| Actual-sales convention, weekly aggregation, restatement, reconciliation | COMPLETE AND VERIFIED | Unit tests plus a real order placed in the e2e: not counted while pending payment, +2 net in the right SKU/region/week after pay and delivery |
| Store/region hierarchy and store-level history | **DEMONSTRATION DATA ONLY** | Deterministic synthetic network, labelled POC_SYNTHETIC in every API and screen; no real stores exist |
| Weekly forecasts (6 methods, fallbacks, backtest on a held-out origin) | IMPLEMENTED; accuracy is synthetic | Held-out WAPE about 0.62 against 0.69 for the 4-week baseline on synthetic history: not a business accuracy claim. Live-scope forecasts are fallbacks and accuracy is withheld (4 live weeks) |
| Plans: five separate values, approval workflow, optimistic locking, audit | COMPLETE AND VERIFIED | 88 unit + 40 DB integration tests; 75-check real-stack e2e; 26-step real-browser journey |
| Plan vs actual, variance investigation (facts vs hypotheses) | COMPLETE AND VERIFIED | Hand-computed variances; promotion history reported UNAVAILABLE where none exists |
| Allocation, replenishment, transfers (reusing Phase 2 rules) | COMPLETE AND VERIFIED, drafts only | No procurement or transfer API exists; approval records a decision and says nothing was ordered or moved |
| Scenarios, Phase 5 pilots, 11 read-only assistant tools | COMPLETE AND VERIFIED | Scenario never changes a plan; pilot is LOW confidence and needs an approved opportunity |
| Stock-out history, promotions, supplier terms, store cost, real store sales feed | NOT AVAILABLE | The related views say so; store actuals enter only through the controlled import |

Validation this session: `uv run pytest` 1,490 passed (unit); `-m integration` 167 passed (with `PLANNING_TEST_DATABASE_URL`, `SOURCING_TEST_DATABASE_URL`, `AI_TEST_DATABASE_URL`; two older tests were adjusted: a UUID-substring flake in the sourcing test and the schema-ownership test now covers `pln_*`); frontend `tsc -b` and `eslint src` clean, `vitest run` 140 passed; Java `identity, order, returns, bootstrap` unit tests 572 passed plus 5 + 2 integration; `planning_e2e.py` 75/75, `planning-ui.mjs` 26/26, `sourcing_e2e.py` 53/53 (regression).

### Phase 6 stabilization and AI-assisted planning (2026-10-16, later session)

Added: AI-assisted planning analysis (ADR-016), a review queue with bulk review, a shared analytics UI used by planning, inventory and product intelligence, and Product Intelligence source diagnostics, landed-cost breakdown, offer comparison, score bars and a link to store pilots. Details: `docs/ai/phase6/README.md`.

**Defects found and fixed during this pass:** (1) forecast method selection used WAPE alone, so intermittent series were forecast at about zero (held-out bias -76% for that bucket); selection now uses WAPE + |bias| on the selection weeks only. The reported synthetic held-out figures changed from WAPE 0.62 / bias -2% to **WAPE 0.64 / bias +10%** (baseline 0.69): the earlier overall bias was a cancellation. Still synthetic, not a business accuracy claim. (2) Unknown weeks inside a series could yield NaN forecasts; methods are now NaN-safe and non-finite values are stored as null. (3) A route-order bug hid the regional analysis behind the new analysis routes (caught on screen). (4) After a bulk review the dialog re-ran its preview with no plans (caught by a test). (5) The Phase 5 e2e and the live-research e2e were not re-runnable (shared words and an earlier quotation); they are now.

**Executed in this pass (all observed):**

| Suite | Result |
|---|---|
| `uv run pytest` (unit) | 1,505 passed |
| `uv run pytest -m integration` (with the three throwaway test databases) | 177 passed |
| Java `mvn -o test` (all 20 modules) / `mvn -o verify -Pintegration-test` | 543 passed / 63 passed (no Java change since; the backend was stopped before rebuilding the jar) |
| Frontend `tsc -b`, `eslint src`, `vitest run` | clean, clean, 183 passed (16 files) |
| `planning_e2e.py` (real stack, real orders) | 75/75 |
| `planning_audit.py` (labelling, live-versus-demonstration, scope, analysis, review, side effects) | 56/56 |
| `planning-ui.mjs` (real Chrome) | 31/31 steps |
| `sourcing_e2e.py` / `sourcing_live_e2e.py` (real Victor Sport pages) / `sourcing-ui.mjs` | 53/53 / 22/22 / 25/25 |
| `inventory-ui.mjs` (real Chrome) | all steps passed |
| Customer AI `ai_scenarios.py`, main eval (83 cases), Inventory AI numeric suites (30/30 and 30/30), measurement (no findings), dev eval (56/56 turns) | passed |
| Commerce `api-e2e.sh` on a fresh database | 122/122 |

Not run in this pass: the held-out Inventory AI suite (consumed by design), the live Gemini unit suite, and a second pass of the Support/Engineering browser journeys (their code was not touched).

**What is and is not real:** online orders are real and thin (a few closed weeks); store network, store history and store stock are demonstration data; no real stock-out, promotion, supplier-term or store-cost history exists; no procurement or transfer API exists, so every recommendation is a draft. Product Intelligence has one live external source (the Victor Sport official site, which publishes no prices).
## 11. Verdict

`READY_FOR_INTERNAL_REVIEW`, and `READY FOR INTERNAL DEMO` with the caveats said out loud: synthetic inventory history, no evidence upload or SLA, only one connected market-research source (the Victor Sport site, which publishes no prices, so margins need buyer-entered quotations), unconnected production log sources, tax-on-discount and time-zone decisions open, and the whole working tree uncommitted. Not production-ready: JWT in `localStorage`, shared signing secret, per-instance rate limiting, no dependency scan, no external security review, roles granted by SQL.

## Security gate pass (2026-10-16) - verification results
Project's own testing only; no penetration test, audit or certification.
- Java: unit 581 passed, integration 63 passed (BUILD SUCCESS).
- Python: unit 1641 passed; integration (`pytest -m integration`, three throwaway databases) 184 passed.
- Frontend: tsc 0 errors, eslint 0, vitest 198 passed, vite build OK.
- Real stack: security_e2e, planning_e2e, planning_audit, sourcing_e2e all `ALL PASS`; planning-ui and sourcing-ui browser journeys passed.
- Not run: inventory-ui / Support / Engineering browser journeys, held-out inventory eval, OWASP dependency-check, pip-audit, any penetration test.
- Details: docs/security/THREAT-MODEL.md, docs/security/AUTHORIZATION-MATRIX.md, docs/SECURITY.md.
