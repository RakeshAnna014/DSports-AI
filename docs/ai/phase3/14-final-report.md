# 14. Phase 3 final report

Date: 2026-10-13. Nothing is committed. Evidence: `e2e/validation-results/20261012-200629` (Java), `e2e/validation-results/20261012-202456` (full regression), `ai-service/eval/results/`, and the re-runs listed below.

## 1. Executive status

**Status: functionally complete and verified for the scenarios tested; NOT "100% complete".** Classification: *ready for a management demonstration on the POC stack*, *not* production-ready.

- Verified: real persisted case backend, investigation workflow, confirmed case creation, staff console, ownership and role enforcement, dev evaluation 45/45, Phase 1/2 regression green after re-runs (details in §10).
- Unproven or open: held-out set 23/24 (H08 is a genuine miss); several results needed a re-run because of Gemini quota; no single clean one-shot `validate-all` run exists (explained in §10); item list in §11.

## 2. Java support-case backend

New module `backend/support` (migration V44 applied forward-only to the real `dsports` database): aggregate with validated lifecycle (OPEN, INVESTIGATING, WAITING_FOR_CUSTOMER, WAITING_FOR_EXTERNAL_PARTY, RESOLVED), escalation state (NONE/REQUESTED/ACCEPTED, accepted only by a staff assignment), `PriorityPolicy`, idempotent creation (`Idempotency-Key`, advisory lock; CREATED/REPLAYED/ALREADY_OPEN), optimistic locking, append-only events, backend-verified evidence snapshots.
APIs (OpenAPI-annotated): customer `/api/v1/support/cases` (create, list, get, messages, escalate, reopen); staff `/api/v1/support/staff` (cases, case, assign, notes, replies, status, resolve, analytics). See docs 02 and 03.

## 3. Python investigation workflow, tools and MCP

`ai-service/app/support/`: classify (rules then model, validated) → identify order/product → parallel READ-tool investigation with budget and timeouts → pure-function decision → deterministic text → confirmed `create_support_case`. Evidence is kept as verified facts, inferences and missing information. Tools: READ `get_return_status`, `get_customer_support_cases`, `get_support_case`, `search_support_policy`; WRITE (confirmation required) `create_support_case`, `add_case_message`, `request_case_escalation`, `reopen_support_case`. MCP exposes the same registry; writes only through propose/confirm. See docs 04 and 06.

## 4. Customer and staff UI

Inside the existing assistant (no fourth chatbot): investigation evidence, resolution summary, case card, timeline, escalation status, confirmation dialog. Customer page `/support`, staff console `/support-cases` (role-gated). `tsc -b` exit 0 and Vite build OK. Browser: `e2e/support-ui.mjs` 23 steps passed (customer, staff, access control, AI down, API failing); Phase 1 and 2 browser journeys passed in the regression.

## 5. Security evidence

Customer sees only own cases (another's case is a uniform 404); staff routes need `SUPPORT_EXECUTIVE` or `ADMIN`; internal notes and priority reasons exist only in staff DTOs; `BACKEND_VERIFIED` cannot be forged; confirmation tokens are single-use and bound to customer, tool and arguments; input guard blocks cross-customer, internal-information and staff-action requests; internal documents (`sup_`, `inv_`) are excluded in SQL for customers. Tests: Java `SupportCaseIntegrationTest` 14 (Testcontainers), Python security and conversation tests, live tests 11/11, browser access-control steps. Evaluation: zero unauthorized state changes and zero cross-customer disclosure in every run. The first held-out run found three guard phrasings that were not refused (no state changed); they were then patched. See doc 08.

## 6. Lifecycle, persistence, idempotency, audit

Covered by Java unit tests (`SupportCaseTest` 14, `PriorityPolicyTest` 6), the integration test (14) and live tests (create/replay/duplicate/foreign order/forged evidence). `support_case_events` is append-only; AI-side confirmation audit as in Phase 1. Real cases exist in the real database, created through the live stack. Analytics report `automaticallyResolved = 0` by design.

## 7. RAG, policy citations and integration limits

Customer procedures (`corpus/`) and internal procedures (`corpus_support/`, `sup_procedure`) ingested through Tika, pgvector; all are labelled demo documents. Answers cite the source document and section; when nothing is found the assistant says so. Real-database tests for isolation: 5. **No** carrier, warehouse, proof-of-delivery, image, exchange or live-agent integration exists, and none is simulated. No official SLA or policy is claimed. See docs 05 and 13.

## 8. Development evaluation

45 cases (51 turns). First run 41/45 (D03, D11, D13, D24 fixed; D11 was my over-broad expectation, corrected). Final code: **45/45, 51/51 turns, all gates met** (`support-dev-final-code-20261013.json`).
S09: the consolidated run once reported `runner error: PermissionError(13, 'Access is denied')` and 44/45; S09 passed alone and in later full runs. **Root cause not identified** (traceback was not captured); the runner now records the traceback, and the error is not suppressed or counted as a pass. A different run scored 44/45 because D28 hit `OUT_OF_SCOPE` during a quota problem; D28 passed alone and in the final full run.

## 9. Held-out evaluation

- **First run (preserved, composite): 15/24.** H01–H14 8/14 (log lines only; no per-answer JSON survived), H15–H21 4/7, H22–H24 3/3. Failures: H03, H05, H06, H07 (harness: backend auto-cancel of unpaid orders was blamed on the assistant), H08, H10, H17, H19, H20.
- **Regression A: 17/24** — six of seven failures were `OUT_OF_SCOPE` from `LLM_QUOTA`, an environmental block, not a pass and not a model-quality result; H08 the seventh.
- Six blocked cases (H01, H04, H07, H11, H14, H15) re-run when quota returned: **6/6 pass** (regression, not unseen).
- **Full regression on the final code: 23/24, all gates met.** The only failure is **H08 (genuine miss, expectation unchanged)**.
- Caveats: all post-first-run results are regression because code changed after seeing failures; the recorded sha256 of `holdout.yaml` no longer matches the file (harness `copies` entries were added later), and the file is not under version control, so unchanged expectations cannot be proven by hash; only 24 cases.
Per-case output and artifact names: doc 10.

## 10. Java results and Phase 1/2 regression

Java (`validate-all.sh --only java-`, exit 0 for both steps, no skips): **546 module tests, 0 failures/errors/skipped; 36 integration tests, 0 failures** (Support 14, Returns 7, Identity 7, Inventory 4, Order 4). The Flyway migration-version test accepts V44.

Full regression (`validate-all.sh --skip-build`, 2026-10-12): **PASS 30, FAIL 6, SKIP 4, BLOCKED 0** as recorded. Each failure was then examined:

| Step | Recorded | Cause | Re-run result |
|---|---|---|---|
| py-live | 61 pass, 2 fail | Gemini 429 `RESOURCE_EXHAUSTED` (Vertex test) and the support chat journey | Vertex live 4/4; support live 11/11 after a test correction (below) |
| sup-live | exit 5 | **my script error**: missing `AI_RUN_LIVE_TESTS=1` / `-m live`, nothing collected | script fixed; 11/11 pass |
| eval-main | fail | F06 expectation obsolete by design; 401s (token expiry during a long slow run); content failures during LLM timeouts/quota | **83/83 pass** on re-run |
| eval-holdout1 (Phase 1) | 25/26 (H08 rag text) | LLM wording variance during degraded model | **26/26** on re-run |
| cust-baseline-flow | payment step timeout | transient (the same flow passed later in the same run as `shop-with-ai-down`) | pass |
| cust-multisport | M12 timeout | likely quota/timeouts | pass |

Other recorded results: api-e2e 122 passed/0 failed; Python unit 1083 passed (1084 after one new regression test, 142 deselected live tests); py-integration 79 passed; inventory unit 491 and Phase 2 evals/browser/failure steps all passed (inventory dev 56/56 turns); support unit step 164; customer journeys and A–M scenarios passed; `shop-with-ai-down` passed (the shop works with the AI service stopped); `tsc` and Vite build passed; Phase 3 dev eval, support-data and support browser steps passed.

Intentional Phase 1 behaviour change: the old answer "I can't create a support ticket" (case F06) is replaced by real case creation. F06 was updated to expect the new behaviour (intent `CASE_ESCALATE`, still states no live agent); the router test for "human agent" was updated likewise. These two edits changed expectations to match a documented decision (ADR-011), not to hide a failure.
Test correction: the live test `test_the_chat_journey_reflects_what_staff_actually_did` previously relied on "my order" meaning "the latest order"; after the D13 fix the assistant correctly asks which order when several exist, so the test now names the order. All its assertions are unchanged.

**Not achieved:** one uninterrupted `validate-all.sh` run with every step green. The evidence is the original run plus the targeted re-runs above; that distinction is deliberate.

## 11. Limitations, commands, artifacts

Limitations: doc 13 (no automatic resolution, no image assessment, no delivery proof, no live agents, no SLA, double charge impossible by schema, deterministic wording, pattern-based guard, English only, no notifications, held-out caveats, 15-minute JWT can expire during very long evaluations, Gemini quota/timeouts can invalidate runs).
Cost note: Gemini is billed pay-per-use; the account in use showed an ended free trial and an "administrator must verify" warning, and the preflight reports project `myteam-ai` while the billing page viewed was `cl-demo-dev-490105` — confirm which project is billed.
Commands: doc 11 and `e2e/README.md`. Artifacts: `e2e/validation-results/*`, `ai-service/eval/results/support-*`, `scratch_shots/`.

## 12. Recommendation and demo sequence

Recommend a management demonstration on the POC stack and a further review of the held-out set with a fresh, unseen set before any production claim. Demo: doc 12 (A missing delivery, B payment, C delayed refund, D damaged product, E "What happened to my complaint?", then the staff console and the refusals), preceded by `python e2e/ai/support_data.py --out scratch_shots/world.json`. Before the demo confirm Gemini quota/billing.

## Repository review (final)

No credentials found in 1,288 changed/untracked files scanned by pattern (`ai-service/.env` is gitignored). `git diff` shows many pre-existing uncommitted Phase 1/2/baseline changes; Phase 3 changes are the new `backend/support`, `ai-service/app/support`, support tools, router/guard/graph edits, frontend support feature, corpora, evaluation, e2e and docs. The working tree has 231+ changed paths and nothing is committed, so Phase 3 cannot be isolated by a commit boundary.
