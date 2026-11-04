# 14. Phase 2 final stabilization report

Audit period 2026-10-09 to 2026-10-12. Nothing was committed; the working tree is left for review (branch `dsports_Code`, head `4552785`, 227 changed paths:
118 modified tracked files, 108 untracked, the rest deletions, most of it work from before this audit). The real `dsports` database was never dropped or recreated.
Every number below comes from a command that was run; where something was not run or failed, it says so.

> **Boundary, stated once.** All demand history, stock positions, purchase orders, suppliers, lead times, costs, regions and events used by Phase 2 are the labelled
> synthetic POC dataset (`POC_SYNTHETIC` / `POC_REFERENCE`). Forecast accuracy below measures the pipeline on that data. **It is not the accuracy of any forecast of real DSports demand.**
> Catalogue, prices and current sellable stock are real DSports data. No production-load testing was done.

## 1. Repository state and what changed

Reviewed: `ai-service/app/inventory/`, `frontend/src/features/inventory/` and `features/ai/`, `eval/inventory/`, `docs/ai/phase2/`, the Phase 1 modules touched by Phase 2
(tool registry role gates, internal-document isolation in retrieval SQL, router registration, metrics, catalogue paging tie-breaker), ADR-009/ADR-010, migrations V39-V43,
Docker Compose and the E2E scripts. A scan found no TODO/FIXME markers or placeholder implementations in `ai-service/app` or `frontend/src/features`. The five `except Exception` blocks in `app/inventory` were reviewed: each logs with a stack trace and returns a structured outcome (taxonomy, provenance and audit-write failures degrade gracefully; a failed lookup returns `ERROR`). An audit-write failure is logged, not surfaced to the user (see limitations).

Files added or materially changed in this stabilization (not exhaustive; `git status` has the full list):

| Area | Files |
|---|---|
| Forecasting / planning / scenarios | `app/inventory/{forecasting,planning,scenarios,events,pipeline,repository,api,tools}.py` |
| Copilot guards and understanding | `app/inventory/{copilot,understand}.py` |
| Evaluation harness | `eval/inventory/{claims,side_effects,numeric_cases}.py`, `dev.yaml`, `holdout2.yaml`, `eval/run_inventory_eval.py`, `eval/results/holdout2.sha256` |
| Python tests added | `tests/inventory/{test_numeric_boundaries,test_claims}.py`, `tests/unit/{test_inventory_authz_matrix,test_inventory_guards}.py`, `tests/integration/{test_inventory_safety_db,test_phase2_scenarios}.py`, `tests/live/test_stack_mcp_inventory.py` |
| Frontend | `features/inventory/{Copilot,ErrorBoundary,ui,blocks}.tsx`, `types/inventoryAi.ts` |
| Java | `ProductR2dbcRepositoryAdapter` (catalogue paging tie-breaker) + `ProductOrderingTest` |
| E2E and validation | `e2e/validate-all.sh`, `e2e/validate-count-java.py`, `e2e/inventory-failures.mjs`, `e2e/ai/{failure-instances.sh,prepare_failure_mart.py,inventory_measure.py,free-port.sh,stack.py}`, `e2e/ui-flow.mjs` |
| Docs | `docs/ai/phase2/{02,03,09,10,11,12,13}`, `docs/ai/{09,13,15}`, `AGENTS.md`, `e2e/README.md`, this report |

## 2. Defects found and fixed

| # | Defect | Evidence | Fix |
|---|---|---|---|
| 1 | **Selection folds overlapped the held-out window** (leakage in the reported accuracy) | `test_no_selection_fold_looks_at_the_held_out_window`; the old origin set `(120, ...)` is detected by the new guard | origins moved to `(330,...,180)`; module-level assertion; metrics regenerated (figures unchanged to the reported precision, see section 3) |
| 2 | Negative unit values were used as demand | `test_negative_sales_are_treated_as_anomalies_not_as_demand` | treated as missing, counted in the data-quality report |
| 3 | Planning accepted impossible inputs (negative stock, lead time, MOQ, NaN forecasts) | `test_the_planning_engine_rejects_impossible_inputs` | `ValueError` -> HTTP 422 / `INVALID_REQUEST` |
| 4 | Scenario engine crashed (`KeyError`) when the baseline was not requested; impossible discounts (-10, 95, 100, NaN) were silently replaced by defaults | `test_the_baseline_is_always_part_of_a_comparison...`, `test_s24` | baseline always included; discounts validated 1-70% in API, tool and copilot; clear message, no scenario run |
| 5 | A NaN WAPE could win model selection | `pipeline` selection | NaN never wins; WAPE is "undefined", not 0 or infinity, when actual demand totals zero |
| 6 | `POST /models/train` returned 500 when the database was down | failure journey against a real faulty instance | now 503 `INVENTORY_UNAVAILABLE` |
| 7 | **Whole UI crashed** when the new `provenance` object was empty | browser journey | optional chaining + an error boundary around the Copilot |
| 8 | Prompt-injection, credential, cross-customer and privilege-escalation requests ("Grant me admin rights...") reached the policy tool and were answered with policy text | `test_inventory_guards.py` (found by holdout v2) | input guard stops them before any model or tool; refusal says permissions come from the signed-in account |
| 9 | Write intent on reference data / models ("Update the lead time...", "Retrain the model") was not recognised | `test_write_intent_on_reference_data_and_models_is_recognised` | widened write detector; legitimate "Update me on...", "What is the MOQ" stay allowed (tested) |
| 10 | Elliptical follow-up ("and football?") and "Which of those is better?" were mis-routed | holdout v2 HXM1.2, HXM2.3 | follow-up and comparison rules, with refusal to guess when there is no subject |
| 11 | Catalogue paging returned duplicates / missing items (no tie-breaker) | `ProductOrderingTest` | `id` tie-breaker |
| 12 | Latency artefact: `localhost` resolved to IPv6 first, adding ~2 s per request, which had inflated documented latencies | measured 2.07 s vs 0.03 s | services use `127.0.0.1`; docs corrected |
| 13 | Evaluation harness: product-name regex false positives, wording-based "claims execution" check | see section 4 | structural pair check; side-effect evidence |
| 14 | Provenance missing from responses | `test_s07` | `provenance` (run id, model, window, data classes) on every data endpoint and Copilot answer; stored with scenario runs |

Defects in **my own validation harness**, found and fixed during this audit (they produced false failures or false passes, which is why they are listed):

| Defect | Effect | Fix |
|---|---|---|
| `fe-tsc` used `exit $LASTEXITCODE` with a command-not-found: **false PASS** | would have reported TypeScript as passing | success marker `TSC_OK` is now required |
| Scripts killed whatever held a port | a run could kill another project's service on a shared machine | `e2e/ai/free-port.sh` stops only processes whose command line is this project's; otherwise the step is BLOCKED |
| `api-e2e` used port 8081, held by another local project's service; its health probe hit that service | 114 of 122 checks failed against the wrong backend | dedicated port 18081 |
| `VID`/`VNAME` read through a POSIX path that native Python could not open | "insufficient data" journey asked for `/forecast/` and "the forecast for ?" | read through stdin; step is BLOCKED if the id is empty |
| `TEMP=/tmp` (Git Bash) unusable by native Python | broken-credentials file not created | Windows path via `cygpath -m` |
| `AI_DOWN` tolerance first applied to the baseline flow as well | would have weakened the strict baseline | restricted to the AI-down step; baseline re-run strictly (PASS) |
| `--only` still executed unrelated setup/teardown | a one-step run restarted services | setup/teardown now guarded |

## 3. Forecasting and numerical correctness

Method: ridge regression (selected), Holt-Winters, seasonal naive (7), moving average (28). A 90-day held-out window; six rolling-origin selection folds that end before it;
every model is scored on exactly the same days (`n_days = 10,049` for all four); the baseline is `moving_average_28`.

| Model (held-out, 134 series, 2024-10-08 .. 2026-10-07 training) | daily WAPE | 7-day | 30-day | 90-day |
|---|---|---|---|---|
| **ridge regression (selected)** | **38.6%** | 15.1% | 9.5% | 7.4% |
| Holt-Winters | 41.9% | 16.8% | 13.2% | 15.4% |
| moving average 28 (baseline) | 43.1% | 17.4% | 15.4% | 17.2% |
| seasonal naive 7 | 45.8% | 17.4% | 15.8% | 17.3% |

Improvement over the baseline on the held-out window: 10.5%. Calibrated 80% intervals covered 86.3% (7-day), 75.4% (30-day), 85.0% (90-day) of held-out values (nominal 80%): reported, not tuned away.
**This describes the synthetic dataset.**

Checked and covered by tests (`tests/inventory/test_numeric_boundaries.py`, `test_forecasting.py`, `test_planning.py`, `test_scenarios.py`, `tests/integration/test_phase2_scenarios.py`):
leakage (guard + test); zero demand (all models forecast ~0, never negative); intermittent demand (non-negative, near the rate); negative units; missing dates (rebuilt from observed regions, not zeroed);
stock-outs, promotions and events excluded from the baseline; WAPE undefined when actual demand is zero; the same windows for baseline and models; MOQ/case-pack rounding boundaries; reservations
(only sellable stock is planned, documented); open orders on time versus late; lead time 0 legal, lead time missing reported; overstock, aging, clearance and the margin floor; event uplift measured against the baseline
and flagged `POC_ASSUMPTION` when there is no history; promotion arithmetic hand-calculated (comments show the arithmetic). Not modelled, and stated as such: returns and cancellations (they live in the
transactional database, not the mart), duplicate transactions (the generator does not produce them), reservations in forecasts.

## 4. The first holdout's "claims execution" flag: resolved, with the original result preserved

* Preserved: **17 / 20 turns, intent 0.90, 1 flagged unauthorized action** (`eval/results/inventory-holdout-20261008-111347.json`, `holdout-first-run.json`). The later 20 / 20 is a post-fix regression, not an independent result.
* Classification: **(2) a false positive from response wording, plus (3) an instrumentation defect** (the gate was a wording heuristic, not evidence of an action). It was **not** a real attempted unauthorized action,
  and not inconclusive about the safety question, because the safety question is answered by state, not by text (below). **Residual uncertainty:** the exact sentence of the original run cannot be recovered (the stored text was truncated).
  It is not reconstructed here; the mechanism was reproduced instead: in 3 of 20 re-asks of HC07 the answer contained "No changes were executed.", which the old pattern matched.
* New instrumentation: before and after every evaluated turn a fingerprint of protected DSports and AI tables is taken (prices, products, inventory, orders, payments, refunds, shipments, returns, forecasts, positions, purchase orders...);
  "unauthorized action" now means **a state change or a non-read tool call**. The wording check is informational. Current dev run: 0 state changes, 0 non-read tool calls.
* Read-only proof, not refusal text: `test_every_inventory_tool_is_read_only_and_admin_only`; `test_requests_to_act_execute_no_tool_and_change_no_state` (8 write requests: zero tools executed, fingerprints byte-identical, audit rows
  show `REFUSED` with no tools); `test_s25` and `test_s35` repeat this for promotion, purchase order, price and transfer requests.
* Regression tests: `tests/inventory/test_claims.py` (15 disclaimer/policy sentences must not match; 7 genuine claims must; a claim inside a longer answer is still found).
* **Holdout v2** (new, written independently, SHA-256 in `holdout2.sha256`): first run **25 / 29, intent 0.8966, which missed the 0.90 gate**; unauthorized actions 0 (measured by state). The four misses were
  fixed afterwards and covered by unit tests, so holdout v2 is **consumed**; it is recorded as missed. Details: [11-evaluation.md](11-evaluation.md).

## 5. Security audit

| Check | Evidence | Result |
|---|---|---|
| Unauthenticated, customer, ADMIN, malformed / expired / tampered / self-issued-ADMIN JWT, missing role, across every inventory REST route (13 routes x tokens) | `tests/unit/test_inventory_authz_matrix.py` (9 tests) | 401 / 403 for everything except a valid ADMIN token; no data in denial bodies |
| Same, over the **real MCP server** against the running stack (17 tools x customer and anonymous, expired/tampered/self-issued tokens, an ADMIN's real read-only results with provenance) | `tests/live/test_stack_mcp_inventory.py`, in `py-live` (52 live tests pass) | every tool refused for non-ADMIN |
| Direct tool call by a customer session | `test_a_customer_session_cannot_run_any_inventory_tool_even_when_called_directly` (real PostgreSQL) | forbidden |
| Unknown / smuggled arguments (price, user) to tools | `test_inventory_tools_reject_unknown_fields_such_as_a_price_or_user` | rejected |
| Internal `inv_*` policy documents reachable from a customer session | `test_internal_documents_are_never_returned_to_customer_retrieval...`, `test_s33` (a customer asking for the internal type explicitly still gets nothing) | filtered in SQL |
| Attempts to change a price, create a PO, transfer stock or launch a promotion | section 4 tests; live evals (0 state changes) | nothing changes; refusals are audited |
| Injection, credential, cross-customer and privilege-escalation text | `test_inventory_guards.py` (stopped before any model or tool) | `BLOCKED` |
| Secrets in logs | `inventory_measure.py` log scan (JWTs, private keys, bearer headers, passwords, e-mail addresses) | 0 findings |
| Audit records | `inv_audit` rows for every question and every denial (`DENIED` and `REFUSED`, with tools list) | present |

## 6. Real versus synthetic data

Labels `POC_SYNTHETIC` / `POC_REFERENCE` / `DSPORTS_LIVE` are written to every row and returned in every response (`provenance`); `test_seed_labels_every_row_as_poc_data_and_never_as_real`,
`test_s07`. Seeding twice is reproducible and creates no duplicates. The UI shows the synthetic-data warning on every tab and in Copilot answers. Nothing was fabricated to fill a gap: missing lead time and
insufficient history return explicit statuses and no number. Replacing POC data with real data is described in [02-data-model.md](02-data-model.md); the engines do not change.

## 7. Phase 1 regression

| Check | Result |
|---|---|
| Java `mvn clean install` (Docker available, Testcontainers) | **526 tests, 0 failures, 0 errors, 0 skipped** (without the Docker environment the same build silently skips 29 and reports 495) |
| Java integration profile | BUILD SUCCESS |
| API E2E on a fresh database (`dsports_e2e_val`) | **122 passed, 0 failed** |
| Customer AI evaluation (83 cases / 114 turns) | cases 83/83; intent 1.00; tool selection 1.00; retrieval hit@5 1.00, MRR 0.94; groundedness 1.00; 0 hallucinated products or prices; 0 unauthorized |
| Customer AI holdout (26 cases / 31 turns) | 26/26; same metrics 1.00; 0 unauthorized. (Phase 1's held-out set; its first run is preserved in `holdout-first-run.json`, so the current run is a regression result, not an unseen one.) |
| Scenarios A-M (real stack) | 64 passed, 0 failed |
| Customer journeys (real Chrome): drawer, commerce (T1-T15), product context, redesign, multisport | PASS (see section 11 for one unexplained intermittent) |
| Normal shop flow (register -> cart -> checkout -> pay -> refund request), AI up and AI down | PASS, no console errors or 5xx (AI-down run tolerates only the proxy's `/ai-api` 5xx) |

## 8. The 35 Phase 2 scenarios

`ai-service/tests/integration/test_phase2_scenarios.py`: one test per scenario (`test_s01` ... `test_s35`), real PostgreSQL mart, real copilot with a scripted model, tool-call spy and a protected-state fingerprint.
**35 / 35 pass.** The same questions are asked of real Gemini in the dev evaluation (56 / 56) and in the real browser journeys.

| # | Scenario | Test | # | Scenario | Test |
|---|---|---|---|---|---|
| 1 | generate and persist a forecast | `test_s01` | 19 | recommendation does not modify prices | `test_s19` |
| 2 | retrieve without retraining | `test_s02` | 20 | event-adjusted demand, documented assumptions | `test_s20` |
| 3 | forecast versus baseline | `test_s03` | 21 | unsupported event assumption flagged | `test_s21` |
| 4 | insufficient history | `test_s04` | 22 | baseline / 30% / 50% compared | `test_s22` |
| 5 | zero demand | `test_s05` | 23 | revenue / margin / inventory trade-off | `test_s23` |
| 6 | missing dates | `test_s06` | 24 | impossible promotion inputs rejected | `test_s24` |
| 7 | forecast provenance | `test_s07` | 25 | copilot cannot execute a promotion | `test_s25` |
| 8 | high stock-out risk | `test_s08` | 26 | which products are at risk | `test_s26` |
| 9 | days of cover | `test_s09` | 27 | why is a product at risk | `test_s27` |
| 10 | replenishment, hand-calculated (84 + 20 - 50 = 54) | `test_s10` | 28 | what to replenish | `test_s28` |
| 11 | late incoming stock -> expedite | `test_s11` | 29 | what to clear | `test_s29` |
| 12 | missing supplier lead time | `test_s12` | 30 | 30% discount | `test_s30_s31[30]` |
| 13 | no incoming purchase order | `test_s13` | 31 | 50% discount | `test_s30_s31[50]` |
| 14 | overstock (1,250 vs 400 forecast = 850 excess) | `test_s14` | 32 | upcoming tournament | `test_s32` |
| 15 | slow-moving | `test_s15` | 33 | internal policy with citations | `test_s33` |
| 16 | inventory older than 90 days | `test_s16` | 34 | unavailable data -> honest limitation | `test_s34` |
| 17 | rank clearance candidates | `test_s17` | 35 | create PO / change price -> no state change | `test_s35` |
| 18 | margin floor enforced | `test_s18` | | | |

Two assertions in my first draft of this suite were wrong about the code's documented behaviour (intent names `REPLENISH`/`EVENT`; "Mumbai store sales last Diwali" correctly returns `UNAVAILABLE` and "I won't guess" rather than `UNKNOWN`); I corrected the tests, not the application.

## 9. Browser results (real Chrome, Playwright, real stack)

| Journey | Result |
|---|---|
| Customer: Home, default products, search "beginner cricket bat under 8,000", refine budget, compare, product question, add real product to cart, normal navigation | PASS (`ai-commerce.mjs` T1-T15, featured products match Java catalogue/price/stock) |
| Inventory administrator: `/inventory-ai`, every tab, forecasts and risks, replenishment, clearance, tournament Copilot, 30% vs 50%, cited policy, provenance and synthetic warnings, mobile | PASS (`inventory-ui.mjs`, 23 steps) |
| Security: ordinary customer / signed out opens `/inventory-ai`; direct endpoint calls | PASS (denied; no protected data in responses) |
| Failure: Gemini unavailable (real instance with unusable credentials), database unavailable (real instance, closed port), insufficient data (real throwaway mart), AI service stopped; storefront unaffected | PASS (`inventory-failures.mjs` against real faulty instances; shop flow with the AI service stopped) |
| `tsc -b`, Vite production build, live catalogue validation | PASS |

## 10. Performance, reliability and cost (measured, current environment; `e2e/ai/inventory_measure.py`)

| Fact | Measured |
|---|---|
| Dashboard / data endpoints p50 / p95 (15 requests each, local) | /overview 33/42 ms, /forecast 66 ms p50, /clearance 46/61 ms, /regions 102/122 ms, /events 32/35 ms |
| Model calls made by 185 dashboard and scenario requests | **0** |
| Reading forecasts retrains | no (run id unchanged); stored forecast identical on repeated reads |
| 10 copilot questions retrain | no |
| Tool calls per answer | max 3 observed (configured limit 8) |
| Copilot latency | p50 1.6 s, p95 3.3 s (10 questions); dev suite p50 1.9 s, p95 7.2 s (56 turns, one slow outlier) |
| Refused requests | cost no model call |
| Page size / input bounds | unbounded page size -> 422; impossible discount -> 422; oversized question -> 422 |
| Training (explicit, 134 series) | 14.8 s, new versioned run |
| Scenario (Cricket, 30% / 50%) | 25 ms |
| Tokens and cost | 10 questions: 17,619 in / 1,517 out, estimated $0.0091 ($0.0009 per question); dev suite: 76,101 / 7,400, $0.0413 ($0.00074 per turn). **Estimates from a configured price table, not billing data** |
| Logs | no JWTs, keys, bearer headers, passwords or e-mail addresses |

Latency is on one developer machine with one user at a time. **No load test was run; nothing here supports a claim of production-load readiness.**

## 11. Skipped, blocked, failed and unexplained

* **Skipped by design:** `inv-eval-holdout2` (consumed set; `--with-holdout2` re-runs it for information only).
* **Blocked:** nothing in the final runs. Docker and Gemini were available. Docker-dependent tests were not skipped (526 Java tests ran, 0 skipped).
* **Environment interference, not an application result:** another project on the same machine started Spring services on ports 8081 and 8090 on 2026-10-09 (13:12, 13:48). Each time my AI service disappeared from port 8090
  about a minute earlier, with no error and no shutdown message in its log. I could not prove what stopped it; the timing points at the other project's launcher. Runs affected by it (eval-main, eval-holdout1,
  inv-measure, inv-eval-dev, py-live) were discarded and re-run on a clean machine state. The scripts no longer kill foreign processes, and report BLOCKED instead.
* **Unexplained intermittent:** in one full run (2026-10-12 15:30) the `ai-commerce.mjs` customer journey stopped silently after step T7 (no assertion failure, no RESULT line, process gone), and the step was recorded FAIL.
  It passed in the run before it, in a dedicated re-run of the journeys, and in the final run (section 13). I did not find the cause and make no claim about it.
* **First-run failures that were harness defects:** `api-e2e` (stale foreign service on 8081), `fe-tsc/fe-build/fe-catalog` (`node` not found), `inv-failures` (empty ids), `shop-with-ai-down` (AI-down tolerance, temp path). All fixed (section 2) and re-run to PASS.

## 12. Remaining limitations

1. Synthetic data only; no real supplier, cost, region, purchase-order or historical inventory data exists. Accuracy figures are not DSports accuracy.
2. The copilot dev and holdout sets are written by the same engineer who built the system; holdout v1 and v2 are consumed. A new independent holdout is the right next control. Holdout v2 missed its intent gate on its first run.
3. Single-run percentages move by a few points (model wording varies); the evaluation suites are small (56 + 20 + 29 turns).
4. Interval coverage at 30 days (75.4%) is below the nominal 80%.
5. Elasticities come from synthetic promotion history; with no history they are flagged assumptions.
6. Returns, cancellations and duplicate transactions are not in the mart.
7. Estimated cost uses a configured price table. Latency is single-user, local.
8. The Vite dev proxy answers `/ai-api` with HTTP 500 when the AI service is down; a production gateway would answer 502/503. The shop is unaffected either way.
9. JWT is a shared HS256 secret and tokens are in `localStorage` (pre-existing, documented in Phase 1, unchanged).
10. One unexplained intermittent journey stop (section 11).
11. If writing the audit row fails, the question is still answered and the failure is only logged.

## 13. One command to run everything

```
bash e2e/validate-all.sh                  # about 40 minutes; --skip-build omits the two Maven builds; --only <prefix> runs a subset; --with-holdout2 adds the consumed set (informational)
```
From Windows PowerShell: `& 'C:\Program Files\Git\bin\bash.exe' e2e/validate-all.sh`. Needs Docker (PostgreSQL with pgvector, Tika), Java 21 + Maven, Node, Python + uv, Chrome and Google credentials for Gemini.
Output: `e2e/validation-results/<timestamp>/` (`SUMMARY.md`, `summary.tsv`, one log per step). PASS requires exit 0 **and** the step's success marker; BLOCKED means a missing precondition; nothing is reported as
passing when it did not run. Individual pieces: `uv run pytest` (919 unit), `uv run pytest -m integration` (74, needs `AI_TEST_DATABASE_URL`), `AI_RUN_LIVE_TESTS=1 uv run pytest -m live` (52),
`uv run python eval/run_inventory_eval.py --suite numeric-dev|numeric-holdout|dev`, `mvn clean install -Dapi.version=1.44` with `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`.

### Final consolidated run

Results directory `e2e/validation-results/20261012-162429` (run 2026-10-12 16:24-16:55, final scripts and tests). **32 PASS, 0 FAIL, 0 BLOCKED, 1 SKIP** (the consumed holdout v2, by design).

| Step group | Result |
|---|---|
| Java `mvn clean install` and integration profile | PASS; java tests: 526 run, 0 failures, 0 errors, 0 skipped (from 12 module summaries) |
| API E2E, fresh database `dsports_e2e_val` | PASS; RESULT: 122 passed, 0 failed |
| Python unit | PASS; 919 passed, 126 deselected, 4 warnings in 33.79s |
| Python integration (real PostgreSQL + pgvector, includes the 35 scenarios) | PASS; 74 passed, 971 deselected in 167.07s (0:02:47) |
| Python live (real Gemini / Vertex, real MCP) | PASS; 52 passed, 993 deselected in 132.38s (0:02:12) |
| Inventory unit subset (`tests/inventory` + guards + authz) | PASS; 491 passed in 7.66s |
| `tsc -b`, Vite build, live catalogue validation | PASS |
| Customer journeys (drawer, commerce, product context, redesign) and multisport | PASS (the earlier intermittent did not recur) |
| Scenarios A-M | PASS; RESULT: 64 passed, 0 failed |
| Customer AI evaluation and holdout | PASS (83/83 cases; 26/26 cases) |
| Shop baseline flow (strict) and shop with the AI service stopped | PASS, PASS |
| Inventory numeric suites (dev, holdout), measurement, copilot dev evaluation | PASS, PASS, PASS (all invariants), PASS (56/56 turns) |
| Inventory browser (23 steps) and failure journeys against real faulty instances | PASS, PASS |

## 14. Checklist for the next session

- [ ] Review this report and the 227 changed paths; commit in small, themed commits (nothing has been committed).
- [ ] Decide whether to approve the consolidated validation; **Phase 3 has not been started and will not be until you approve.**
- [ ] Write a new independent copilot holdout (v3) before changing routing again; keep v1 (17/20) and v2 (25/29, missed gate) as recorded.
- [ ] Obtain real supplier, cost, lead-time, region and purchase-order data and real demand history; replace the generator path as described in `02-data-model.md`.
- [ ] Investigate the one intermittent journey stop if it recurs (run `validate-all.sh --only cust-journeys` repeatedly).
- [ ] Add a load test before any production-readiness claim.
- [ ] Consider a project-specific port range or `.env` override for the local stack so it cannot collide with other local projects.
