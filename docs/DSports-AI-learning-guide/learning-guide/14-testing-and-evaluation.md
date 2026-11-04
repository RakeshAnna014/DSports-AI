# 14. Testing, evaluation and validation evidence

## 1. Five kinds of check, five different meanings

| Kind | What it is | Typical question it answers | Deterministic? |
|---|---|---|---|
| **Software test** (unit / integration) | a program asserts exact behaviour of code with controlled inputs (scripted model, fake or real database) | "Does this function/endpoint do what we specified?" | yes |
| **AI evaluation** | a dataset of conversations run against the *real* system (real Gemini, real Java, real database); scored on intent, tools, facts, safety | "How often does the whole assistant behave acceptably on realistic phrasings?" | no (model wording varies; structure and facts are what is gated) |
| **Held-out evaluation** | an evaluation set written *before* tuning and run once per code state to estimate generalisation. After its failures are fixed, re-runs are **regression**, not an unseen result | "Does it generalise to phrasings we didn't tune on?" | no |
| **Live provider test** | a test that calls the real provider (Vertex AI) or the running stack (`pytest -m live`, `AI_RUN_LIVE_TESTS=1`) | "Does the integration with the real service work (credentials, model ids, schemas)?" | mostly |
| **Browser test** | Playwright drives real Chrome against the running stack | "Does the user journey work end to end including the UI, with no console errors or 5xx?" | mostly |

A passing software test proves the code matches its specification in those cases. It does **not** prove model quality, customer outcomes or production readiness. An evaluation percentage on a few dozen cases is a measurement of those cases; **100 % on a small set is not production accuracy** (the same engineer wrote and tuned the sets, sets are small, the data are sample/synthetic, and a model can behave differently tomorrow).

## 2. The suites: where, how to run, what a pass means, what it does not prove

| Suite | Location | Run | Pass means | Does not prove |
|---|---|---|---|---|
| Java unit/module tests | `backend/*/src/test` | `cd backend && mvn -B -o clean install '-Dapi.version=1.44'` (set `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`) | domain rules, use cases, mappers, exception mapping, migrations (Testcontainers) | behaviour of a deployed system; wishlist has no tests |
| Java integration (Testcontainers) | `*IntegrationTest` | `mvn -B -o verify -Pintegration-test '-Dapi.version=1.44'` | real-PostgreSQL behaviour: idempotency, concurrency, constraints, ownership | scale/performance |
| Python unit | `ai-service/tests/unit`, `tests/inventory`, `tests/support` | `cd ai-service && uv run pytest tests` (live and integration tests are deselected by markers) | routing, guards, grounding, registry, confirmation, forecasting math, support flow with a **scripted fake DSports and fake model** | real Gemini wording; real Java |
| Python integration | `tests/integration` (`-m integration`, needs `AI_TEST_DATABASE_URL`) | `uv run pytest -m integration` | real PostgreSQL + pgvector: ingestion, retrieval isolation, inventory mart, 35 Phase 2 scenarios | provider behaviour |
| Python live | `tests/live` (`AI_RUN_LIVE_TESTS=1 uv run pytest -m live`) | needs the running stack and Gemini | real Vertex calls, real MCP over HTTP, real Tika/OCR, real Java support API | stability under quota limits |
| API E2E | `e2e/api-e2e.sh` | `bash e2e/validate-all.sh` step `api-e2e` (fresh database `dsports_e2e_val`, port 18081) | 122 checks over the Java REST surface | UI |
| Browser journeys | `e2e/*.mjs`, `e2e/run-ai-ui.sh` | Node + Playwright against the running stack | customer, inventory and support journeys, access control, AI-down | other browsers/devices |
| Scenario tests | `e2e/ai/ai_scenarios.py` (scenarios A-M, 64 checks) | via `validate-all.sh` | Phase 1 acceptance scenarios on the real stack | |
| MCP tests | `tests/unit/test_mcp.py` (6), `tests/live/test_stack_mcp.py` (5), `test_stack_mcp_inventory.py` (6) | | schema exposure, propose/confirm, authN/authZ over MCP | |
| Development evaluations | `ai-service/eval/cases.yaml` (Phase 1, 83 cases), `eval/inventory/dev.yaml`, `eval/support/dev.yaml` (45) | `uv run python eval/run_eval.py`, `run_inventory_eval.py --suite dev`, `run_support_eval.py --suite dev` | the fixes hold on the cases they were built from | generalisation |
| Held-out evaluations | `eval/holdout.yaml` (26), `eval/inventory/holdout.yaml`/`holdout2.yaml`, `eval/support/holdout.yaml` (24) | `... --suite holdout` | first-run result = generalisation estimate; later = regression | |
| Failure-injection | `e2e/ai/failure-instances.sh`, `e2e/inventory-failures.mjs`, `tests/support` failure tests | three real faulty AI instances (Gemini down :8091, DB down :8092, insufficient data :8093) | the UI shows a clear message and the storefront keeps working | |
| Security tests | `tests/unit/test_guards.py`, `test_inventory_*`, `tests/support/test_security_support.py`, Java ownership tests, authz matrices, `inventory_measure.py` log scan | | each control in doc 13 | exhaustive attack coverage |
| Consolidated regression | `e2e/validate-all.sh` | `& 'C:\Program Files\Git\bin\bash.exe' e2e/validate-all.sh [--skip-build] [--only prefix]` | each step PASS only if exit code 0 **and** its success marker is found; BLOCKED = missing precondition; SKIP = not selected | a single run is a snapshot |

Test counts by Python file (from `def test_` counts): `tests/unit` 21 files (agent 63, tools 40, guards 29, router 23, structured responses 22, chunking 18, ranking 17, observability 15, api_chat 14, auth 13, inventory copilot 13, inventory guards 12, app 10, product context 9, inventory authz 9, tika 8, config 6, mcp 6, ...), `tests/inventory` 67 across 8 files, `tests/support` 76 across 6 files, `tests/integration` (inventory_db 11, inventory_safety 4, phase2 scenarios 34, rag_db 16, store 8, support_documents 5), `tests/live` (mcp 5, mcp inventory 6, rag 5, support 11, vertex 3). Pytest counts parametrised cases separately (see below).

## 3. Latest recorded evidence

**Java** (`e2e/validation-results/20261012-200629`, exit 0): 546 module tests, 0 failures/errors/skipped; 36 integration tests, 0 failures (support 14, returns 7, identity 7, inventory 4, order 4).

**Python** (Phase 3 consolidated run `e2e/validation-results/20261012-202456`, plus later re-runs): unit 1083 passed in that run, 1084 after one regression test added in this session (142 deselected live tests); integration 79 passed; `tests/support` step 164 passed (parametrised).

**Consolidated run `20261012-202456` as recorded:** PASS 30, FAIL 6, SKIP 4, BLOCKED 0. The six FAILs and what happened (all examined; none left unexplained):

| Step | Recorded | Cause | Re-run result |
|---|---|---|---|
| `py-live` | 61 pass / 2 fail | Gemini `429 RESOURCE_EXHAUSTED` (a Vertex test and the support chat journey) | Vertex live 4/4; support live 11/11 (after a test correction, below) |
| `sup-live` | exit 5 (no tests collected) | **my script error**: the live tests need `AI_RUN_LIVE_TESTS=1` and `-m live`; fixed in `validate-all.sh` | 11/11 |
| `eval-main` (Phase 1) | content failures + 401s | LLM timeouts/quota during the run; one obsolete Phase 1 expectation (F06); long run outlived the 15-minute token (401) | **83/83** on re-run (`eval-20261013-100909.json`) |
| `eval-holdout1` (Phase 1) | 25/26 | model wording variance (H08 "separate" text) during a degraded model period | **26/26** (`holdout-20261013-101026.json`) |
| `cust-baseline-flow` | payment step timeout | transient; the same flow passed later in the same run as `shop-with-ai-down` | pass |
| `cust-multisport` | M12 timeout | likely quota/timeouts | pass |

**Important distinction:** these are *individual successful re-runs*, **not a single clean consolidated regression run**. No single uninterrupted `validate-all.sh` run with every step green exists for the final code. The evidence is the original run plus targeted re-runs, stated as such.

Test corrections made during this validation (disclosed): (1) `test_the_chat_journey_reflects_what_staff_actually_did` assumed "my order" meant the latest order; after fix D13 ("my order" no longer means "latest") the assistant correctly asks which order, so the test now names the order (assertions unchanged); (2) Phase 1 evaluation case **F06** expected "can't create a support ticket", which Phase 3 intentionally replaced; it now expects `CASE_ESCALATE` and the honest "can't connect you to a person live" text (decision recorded in ADR-011).

## 4. Evaluation results by phase

### Phase 1 (real stack, real Gemini)
Main set 83 cases / 114 turns: 83/83, intent 1.00, tool selection 1.00, hit@5 1.00, groundedness 1.00, hallucinated product/price 0, unauthorized 0 (`docs/ai/09-evaluation.md`; re-run 2026-10-13: 83/83). Held-out (26 cases): **first run 24/26** (H18, H21); 26/26 afterwards is regression. Disclosed dataset change: S02/S09 expectations were widened after the catalogue grew from 50 to 134 products (`docs/ai/09-evaluation.md`, last section).

### Phase 2
Dev 56/56 turns. Holdout v1 first run **17/20, intent 0.90, one flagged "claims execution"** (later reclassified as a wording/instrumentation false positive; the original sentence cannot be recovered, and is not reconstructed). Holdout v2 first run **25/29, intent 0.8966, missed the 0.90 gate**; both holdouts are consumed. Unauthorized actions are measured by protected-table fingerprints and non-read tool calls: 0 (`docs/ai/phase2/14-final-stabilization-report.md` sections 3-5, 12). Forecast accuracy (held-out daily WAPE 38.6 % for the selected ridge model versus 43.1 % baseline) is measured on **synthetic** data (doc 10).

### Phase 3 (runner `ai-service/eval/run_support_eval.py`)
Worlds are built through the real public APIs (`e2e/ai/support_data.py`); results judged from the backend's own records: per-customer state fingerprints (zero unauthorized state changes), an independent grounding check (every case number/status/amount exists in the backend), forbidden-claim patterns, and a cross-customer probe. Gates: intent >= 0.90, investigation correctness >= 0.90, case idempotency 1.0, zero unauthorized state changes / cross-customer disclosure / false claims / invented case numbers.

* **Dev (45 cases / 51 turns):** first run 41/45 (D03, D11, D13, D24; fixed, D11 was an over-broad expectation of mine); final code **45/45 (all gates met)** (`ai-service/eval/results/support-dev-final-code-20261013.json`). In the consolidated run dev scored 44/45: the failure was S09 in one earlier run (a Windows `PermissionError` in the runner, cause unidentified; the runner now stores tracebacks) and D28 in another (`OUT_OF_SCOPE` during quota exhaustion). Both passed when re-run.
* **Held-out (24 cases), first run, composite 15/24:** H01-H14 8/14 (log lines only: `support-holdout-first-run-PARTIAL-crashed.log/.err`; no per-answer JSON survived), H15-H21 4/7 (`...PARTIAL-H15-H21.log/.err`), H22-H24 3/3 (`support-holdout-20261012-194220.json`). First-run failures: H03, H05, H06, H07 (harness defect: the backend's auto-cancel of unpaid orders changed a global fingerprint and was blamed on the assistant), H08, H10, H17, H19, H20 (three guard phrasings not refused; no state changed).
* **Regression runs (not unseen):** A: 17/24 with six `OUT_OF_SCOPE` failures caused by Gemini quota (environmental: the usage log shows `outcome: LLM_QUOTA`), not counted as a pass or as a model-quality result; B: the six cases re-run when quota returned: 6/6 (`support-holdout-REGRESSION-quota-retry-H01-H04-H07-H11-H14-H15-20261013.json`); C: full re-runs on the final code **23/24, all gates met** (`support-holdout-REGRESSION-full-final-code-20261013.json`, `support-holdout-REGRESSION-final-code-20261013.json`).
* **Label caveat:** the three `...REGRESSION...` result JSON files carry the label `FIRST RUN (unseen)` inside them (their runs used a temporary output directory that had no preserved first-run file, so the runner mislabelled them). They are regression runs; the files were preserved unedited and the mislabel is explained in `ai-service/eval/results/SUPPORT-RESULTS-NOTE.md`. Also, `support-holdout-20261012-195623.json` (17/24) recorded `llm_calls = 0`: every model call had failed from quota, which independently corroborates the environmental classification.
* **The genuine held-out miss, H08:** "escalate my case" is routed to `CASE_ESCALATE`; the expectation said `COMPLAINT`. The expectation was not changed after seeing the result, so it stays a recorded miss (one can argue the assistant is right, but editing the expectation afterwards would invalidate the set).

### Evaluation dataset integrity limitations (stated, not smoothed over)
* `eval/support/holdout.yaml` is **untracked in git** and its current SHA-256 (`57b2ab2a...`) **does not match** the recorded hash (`96d06bae...` in `eval/results/support-holdout.sha256`): world `copies` counts (harness data) were added after the hash was taken when the runner crashed on missing worlds. No expectation was intentionally edited, but the hash cannot prove that.
* The first Phase 3 held-out run exists as three partial executions; for H01-H14 only pass/fail log lines survive.
* The "regression" label on some later partial runs of H15-H24 is an artefact of file naming; H15-H24 were genuine first executions.
* All evaluation sets were written by the engineer (with the assistant) who built the system; sets are small (24-114 turns); catalogue/corpus/forecast data are samples or synthetic.
* Provider quota (Vertex 429) and a 15-minute JWT can invalidate long evaluation runs; runners now classify runner errors separately from answer failures.

## 5. How to tell a model failure from a test-runner failure
Look at the evidence class: a **runner error** has an exception text (`PermissionError`, `AssertionError: (401, ...)`, `KeyError copies`) and no meaningful answer; a **provider/environment failure** shows in the usage log (`outcome: LLM_QUOTA`, `LLM_TIMEOUT`) and in answers that are fallbacks (`OUT_OF_SCOPE`, deterministic text with an "AI unavailable" disclaimer); a **model/logic failure** shows a real answer that violates an expectation (wrong intent, a false claim, a missing refusal) and reproduces with a healthy provider. Always re-run the same case alone with a healthy provider before classifying.

## 6. Known runner issues recorded
Port collisions with another project's Spring services on 8081/8090 (scripts now report BLOCKED instead of killing foreign processes); `npm.cmd` not found (Node is called directly); UTF-8 BOM from `Set-Content -Encoding utf8` breaks javac/YAML; the backend's 30-minute unpaid-order cancellation can invalidate old evaluation worlds; Testcontainers silently skip without `DOCKER_HOST`/`-Dapi.version`; one unexplained intermittent stop of a Phase 1 browser journey in an earlier run (Phase 2 report section 11).
