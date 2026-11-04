# End-to-end checks

Run against a locally running stack: backend on `:8080` (Vite proxies `/api` there) and the Vite dev server (`pnpm exec vite`) in
`frontend/` on `:3000`. Use a throwaway database, because the scripts create users, orders and an admin role.

## API lifecycle and security (`api-e2e.sh`, 122 checks)

```bash
bash e2e/api-e2e.sh <db_name> 8080
```

Covers:
- **Order and payment lifecycle:** order → payment → capture, with stock reservation, fulfilment and release, and cart/checkout state.
- **Idempotency:** duplicate order, payment, capture, refund and review.
- **Isolation (IDOR):** one customer against another across orders, payments, refunds, shipments and tracking.
- **Admin-only APIs.**
- **Returns:** eligibility (delivered, window, partial and remaining quantity, product not in order), create, `Idempotency-Key` retry and reuse, invalid quantities, IDOR, admin approve/reject, and 8-way concurrent creation against 2 remaining units. Needs a **fresh** database: the review check assumes no earlier approved reviews, so re-runs on a used database fail that one check.
- **Refunds:** request, approve, partial and full.
- **Shipment progression:** order DELIVERED, then verified-purchase reviews and moderation.
- **Outbox delivery.**
- **The error contract.**

It needs `docker` access to the `dsports-postgres` container for state assertions, and it grants ADMIN to a test user via SQL (no admin provisioning API exists yet).

## Browser journey (`ui-flow.mjs`, Playwright + installed Chrome)

```bash
cd e2e && npm install
node ui-flow.mjs happy           <email> 00000000-0000-0000-0000-000000001005
node ui-flow.mjs capture-failure <email> <productId>   # backend: --payment.mock.capture-mode=failure
node ui-flow.mjs capture-timeout <email> <productId>   # backend: --payment.mock.capture-mode=timeout --app.payment.gateway-timeout=PT3S
```

Each run registers, logs in, adds a product to the cart, checks out with a new address, places the order and pays.
- `happy` asserts the success page shows a **CONFIRMED** order, that a forged success URL is not shown as success, and that a refund can be requested.
- `capture-failure` asserts the failure page and a successful cancellation.
- `capture-timeout` asserts that "Retry confirmation" is offered and that no false success is shown.

The script fails on any console error, page error or HTTP 5xx. In the timeout scenario the 504 is the expected outcome.

## Customer AI (real stack)

Needs the backend on `:8080`, Postgres with pgvector, Tika, the AI service on `:8090` and real Vertex credentials; see
[docs/ai/12-local-development.md](../docs/ai/12-local-development.md). Nothing is mocked.

```
bash e2e/ai/start-ai.sh                                          # AI service (detached) with a high rate limit and the price table; start-ai.ps1 is the PowerShell equivalent
python e2e/ai/ai_scenarios.py [A B ... M]                         # scenarios A-M (64 checks); no args = all
python e2e/ai/ai_ui_setup.py                                      # a real customer with a delivered order -> JSON
node e2e/ai-ui.mjs <email> <orderNumber> <orderId>                # Playwright journey through the chat drawer (13 steps); Vite on :3000
node e2e/ai-commerce.mjs <email> <password> <orderNumber> [shotDir]  # Playwright: the AI-powered Home page (22 steps, screenshots)
node e2e/ai-product-context.mjs <email> <password> [shotDir]       # Playwright: Ask AI on a product, product-contextual conversation (16 steps)
node e2e/ai-redesign.mjs <email> <password> <shoesOrder> <batOrder> [shotDir]   # Playwright: premium UX, typed responses, interactive return selection (ai/ai_ui_setup2.py)
node e2e/ai-multisport.mjs [shotDir]                              # Playwright: 11 sports, 134 products with real images, filters, bundle, cart, demo customers (M1-M14)
python e2e/ai/seed_demo_customers.py                              # four demo customers with real purchase histories (DEMO DATA ONLY)
bash e2e/run-ai-ui.sh [shotDir]                                   # the four customer journeys above, each with a fresh customer
```

### Phase 2: Inventory Intelligence (internal, business users)

```
python e2e/ai/inventory_setup.py                                  # an ADMIN business account and a normal customer -> JSON
node e2e/inventory-ui.mjs <adminEmail> <customerEmail> <password> [shotDir]   # real Chrome, 23 steps: access control, every tab, copilot conversation, failure, mobile
python e2e/ai/inventory_probe.py [--json out.json]                # the 16 business questions from the brief against the real copilot
python e2e/ai/inventory_ask.py "question" "follow-up" ...         # ask the copilot one or more questions in one conversation
python e2e/ai/inventory_real_data_audit.py                        # what real DSports data exists (why the Phase 2 history is synthetic)
```

```
bash e2e/ai/failure-instances.sh start|stop                       # three REAL faulty AI instances: Gemini down :8091, database down :8092, insufficient data :8093
python e2e/ai/prepare_failure_mart.py                             # small throwaway mart (dsports_ai_test) where one product has ~20 days of history
node e2e/inventory-failures.mjs <adminEmail> <password> <victimId> <victimName> [shotDir]   # browser view of each failure: clear message, no crash, storefront unaffected
python e2e/ai/inventory_measure.py                                # MEASURED latency, call counts, stored-forecast reuse, log hygiene (exits non-zero when an invariant fails)
```

Needs the trained inventory run (`python -m app.inventory.cli seed && ... train` in `ai-service/`). Results: [docs/ai/phase2/11-evaluation.md](../docs/ai/phase2/11-evaluation.md).

### Phase 3: Customer Support and Case Resolution

```
python e2e/ai/support_data.py --out scratch_shots/world.json [--copies N] [--only scenario,...]   # builds a REAL world through the public APIs (customers, orders, payments, shipments, returns)
node e2e/support-ui.mjs scratch_shots/world.json scratch_shots    # real Chrome, 23 steps: customer investigation + case, staff console, access control, AI down
python e2e/ai/support_ask.py "message" ...                        # ask the assistant one or more messages as a world customer
cd ai-service && uv run python -u eval/run_support_eval.py --suite dev|holdout   # evaluation on the real stack (do not create orders/cases while it runs)
cd ai-service && uv run pytest tests/live/test_stack_support.py   # Java API, MCP and chat journey against the running stack
```

Needs `cd ai-service && uv run python -m app.support.cli ingest-corpus` (internal support documents). Results and the first-run held-out record: [docs/ai/phase3/10-evaluation.md](../docs/ai/phase3/10-evaluation.md).

Scenario L ingests and removes a poisoned document with the service's own CLI, so it must run with the service's virtualenv
(`ai-service/.venv`), which the script selects itself. Scenario M starts a second AI instance on `:8091` with unusable credentials.
Results are recorded in [docs/ai/13-e2e.md](../docs/ai/13-e2e.md).

`ai-commerce.mjs` needs `AI_DOWN_BASE` (default `http://localhost:8091`): a second AI instance started with an unusable
`GOOGLE_APPLICATION_CREDENTIALS` (see scenario M). Its last step routes the browser's `/ai-api` calls to that instance to prove the
page behaves when Gemini is genuinely unavailable. It verifies every product shown against the Java catalog, price and inventory APIs.

### Phases 4-6 and the security gate (real stack)

```
python e2e/ai/engineering_e2e.py                                  # Engineering AI: ingest, cluster, trace, role gate, audit
python e2e/ai/sourcing_e2e.py                                     # Product & Supplier Intelligence: roles, demand gap -> opportunity, quotes, comparison, workflow, proposal
python e2e/ai/sourcing_live_e2e.py                                # the one real research source through the SafeFetcher (needs outbound network)
python e2e/ai/sourcing_data.py > scratch_shots/sourcing-world.json && node e2e/sourcing-ui.mjs scratch_shots/sourcing-world.json scratch_shots
python e2e/ai/planning_e2e.py                                     # Sales & Inventory Planning: ingest, forecast, plans, approvals, scope, scenarios
python e2e/ai/planning_audit.py                                   # labelling, scope, analysis, review and side-effect checks
python e2e/ai/planning_data.py > scratch_shots/planning-world.json && node e2e/planning-ui.mjs scratch_shots/planning-world.json scratch_shots
python e2e/ai/planning_data.py --cleanup                          # removes the world the previous command created
python e2e/ai/security_e2e.py [--matrix docs/security/AUTHORIZATION-MATRIX.md]   # forged tokens, role matrix, hostile input, prompt injection, log hygiene; regenerates the matrix
```
Each Python script ends with `ALL PASS` or a list of failed checks and a non-zero exit; each browser script ends with `RESULT: all steps passed`. Run the `*_data.py` generator right before its browser script (the world file holds users and ids created for that run).

## Consolidated validation (`validate-all.sh`)

```
bash e2e/validate-all.sh                  # everything, about 60 minutes (Maven builds, API E2E on a fresh database, Python unit/integration/live, evals, browser journeys, failure journeys)
bash e2e/validate-all.sh --skip-build     # without the two Maven builds
bash e2e/validate-all.sh --only inv-      # only steps whose name starts with a prefix (setup/teardown outside the subset is skipped too)
bash e2e/validate-all.sh --with-holdout2  # also re-run the consumed holdout v2 (informational)
```

Each step is PASS only if it exited 0 **and** printed its success marker; BLOCKED means a precondition (Docker, Gemini) is missing (an environment fact, not an application result); SKIP means not run.
Docker tests are never skipped silently: Testcontainers needs `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` (the script sets it) and the build uses `-Dapi.version=1.44`.
Results: `e2e/validation-results/<timestamp>/` (one log per step, `summary.tsv`, `SUMMARY.md`). The real `dsports` database is never dropped or recreated (API E2E uses `dsports_e2e_val`).
