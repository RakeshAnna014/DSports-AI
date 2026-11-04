# Phase 6: Sales & Inventory Planning Intelligence

Decision support for planning units per **SKU x store x week**, with rollups to category, sport, zone, region, month, quarter and financial year.
It lives in the Python AI service (`ai-service/app/planning/`, tables `pln_*` in the `dsports_ai` database), reads sales only through the Java
backend's public feed, reuses the Phase 2 Inventory AI rules for stock, and has a frontend at `/sales-planning`. Architecture decision: [ADR-015](../../adr/ADR-015-sales-planning.md).

## Rules (also in AGENTS.md)

- Forecasts, variances, replenishment and scenarios are deterministic code over persisted data. An LLM never produces a number; the assistant (`/ask`) is a deterministic router over 11 read-only tools.
- Five values are never merged: **model forecast** (frozen at generation), **manager forecast**, **target**, **approved forecast** (snapshotted on approval) and **actual**.
- Java commerce stays authoritative. The AI service never writes commerce tables, never orders, moves stock, publishes or prices. Approval records a decision only.
- Data is labelled by source: `DSPORTS_LIVE` (real online orders), `IMPORTED` (controlled import), `POC_SYNTHETIC` (demonstration stores and history), `POC_REFERENCE`.
- Insufficient data is stated, not filled: a missing closed week is a confirmed zero only for live data; accuracy on thin samples is withheld.

## What is real and what is not

| Input | Status |
|---|---|
| Online orders (sales feed from the Java `order` and `returns` modules) | Real, but the history is short (a few closed weeks), so live-scope forecasts are fallbacks and accuracy is withheld |
| Store network (regions, zones, stores), store-level history, store stock | **Demonstration data**, generated deterministically, labelled everywhere |
| Held-out accuracy shown in the UI | Measured on the synthetic history: it says how the method behaves on that data, not how DSports will forecast |
| Promotions, stock-out history, supplier terms, store cost | Not available: the related views say so |
| "Store actual" import | A controlled, idempotent, audited fixture path until a real store feed exists |

## Sales convention (`conventions.py`)

Recognised statuses: CONFIRMED, PROCESSING, SHIPPED, DELIVERED, REFUNDED. CREATED and PENDING_PAYMENT are not sales.
`gross` = recognised or cancelled lines; `cancelled` = CANCELLED lines; `returned` = max(approved return quantity, quantity if REFUNDED), capped, for non-cancelled lines;
`net = gross - cancelled - returned`. Revenue is after pro-rata discount. A sale belongs to the business-timezone week of `placed_at`; a later cancellation or return restates that original week
(returns do not touch the order's `updated_at`, so ingestion re-reads a restatement window, default 45 days). Weeks start on Monday and the financial year on 1 April by default (configurable).

## Ingestion

`POST /v1/planning/ingest` (ADMIN) pulls `/api/v1/admin/sales/lines` (Java, ADMIN-only, keyset-paged, max 1000 per page, no personal data: city/state/country only) with the admin token,
upserts lines with the convention precomputed, re-sums only the touched weekly keys, and reconciles lines to weekly totals. Runs are recorded in `pln_ingest_run`.

## Forecasting (`forecast.py`, version `weekly-v1`)

Candidates: 4-week moving average (the baseline), seasonal naive, seasonal level, damped ETS, Croston-SBA for intermittent series and a top-down split. Fallbacks: a comparable-store split, and
INSUFFICIENT_DATA when there is no history. The method is chosen per volume bucket on selection origins by WAPE + absolute bias; accuracy is reported on a **separate held-out origin** (no leakage), WAPE and bias,
against the baseline; prediction intervals come from held-out residuals; stock-out weeks are imputed in training only. On the demonstration data the held-out WAPE is about 0.64 against 0.69 for the baseline with a bias of about +10%: a modest gain, and **synthetic**. (An earlier version reported 0.62 and -2%; that overall bias was a cancellation of a -76% under-forecast of intermittent series and a +9 to +15% over-forecast elsewhere. Selecting on WAPE alone rewards forecasting zero for sparse demand, so the criterion now includes the absolute bias. The held-out weeks are never used to choose a method, and no constant was tuned on them.)

## Plans

DRAFT -> SUBMITTED -> UNDER_REVIEW -> APPROVED -> PUBLISHED, plus return, reject and withdraw. Each line has a `line_version`; a stale save is refused as a conflict and nothing is saved.
A revision is a new plan (revision + 1) that supersedes its parent when approved. Every change is appended to `pln_audit`. No self-approval unless `PLANNING_ALLOW_SELF_APPROVAL`.

## Roles and scope

ADMIN, CATEGORY_MANAGER and SALES_PLANNER see everything; REGIONAL_MANAGER and STORE_MANAGER only the locations granted in `pln_scope`. Approvers: ADMIN, CATEGORY_MANAGER, REGIONAL_MANAGER.
Planners edit but cannot approve. A customer gets 403 from every planning endpoint. Enforcement is in the service; the frontend only hides controls.

## Inventory

`inventory.py` calls the Phase 2 `assess` and `round_order` functions. Reserved stock is excluded, incoming stock counted once, MOQ and case pack applied once per product across stores,
transfers are offered before purchases, and every recommendation is a DRAFT. No purchase order or stock movement is created; approving a recommendation says so.

## Scenarios and pilots

Scenarios (GROWTH_TARGET, PROMOTION, LEAD_TIME, SAFETY_STOCK, TRANSFER) are saved simulations. They never change a plan; only a GROWTH_TARGET scenario can be adopted, and then only as targets on an editable plan.
A promotion uses elasticity from `inv_elasticity` or says it has none. A pilot starts from a Phase 5 opportunity that is approved and live-sourced or buyer-entered and yields a LOW-confidence range from comparable products.

## API

`/v1/planning/...` (the frontend proxies `/ai-api`). Main groups: `readiness`, `locations`, `taxonomy`, `ingest`, `forecast/{runs,overview,series,evaluation}`, `plans` (create, lines, growth-targets, transition, revise, impact, performance, investigate, audit),
`actuals` (list, import, adjustments), `plans/{id}/allocations`, `scenarios`, `pilots`, `ask`, `dashboard`, `analysis/regional`, `audit`. Interactive docs: the AI service's `/docs`. The Java feed: `GET /api/v1/admin/sales/lines` in the backend OpenAPI.

## Configuration (`ai-service/.env.example`)

`PLANNING_TIMEZONE`, `PLANNING_WEEK_START`, `PLANNING_FY_START_MONTH`, `PLANNING_HORIZON_WEEKS`, `PLANNING_LOOKBACK_WEEKS`, `PLANNING_MIN_HISTORY_WEEKS`, `PLANNING_BACKTEST_ORIGINS`, `PLANNING_BACKTEST_STEP_WEEKS`,
`PLANNING_MIN_EVAL_OBSERVATIONS`, `PLANNING_MIN_EVAL_UNITS`, `PLANNING_STATE_REGION_MAP`, `PLANNING_VARIANCE_PCT`, `PLANNING_VARIANCE_MIN_UNITS`, `PLANNING_TRANSFER_LEAD_DAYS`, `PLANNING_TRANSFER_CROSS_REGION_DAYS`,
`PLANNING_TRANSFER_MIN_QTY`, `PLANNING_TRANSFER_UNIT_COST` (unset: no transfer cost is claimed), `PLANNING_ALLOW_SELF_APPROVAL`, `PLANNING_ENABLE_DEMO`.

## Runbook

1. Start Postgres, the Java backend (`e2e/ai/start-backend.sh`), the AI service (`e2e/ai/start-ai.sh`) and Vite. The AI service loads `schema_planning.sql` on start.
2. As ADMIN: `POST /v1/planning/ingest` (live orders), optionally `POST /v1/planning/demo/seed` (labelled demonstration network) and `POST /v1/planning/forecast/runs` (about 20 s).
3. Grant roles in `customer_roles` (SALES_PLANNER, CATEGORY_MANAGER, REGIONAL_MANAGER, STORE_MANAGER) and scope with `POST /v1/planning/scope`.
4. Open `/sales-planning`. The Data & forecasts tab shows readiness per input.
5. If a forecast run fails, the previous current run stays current; rerun it. Ingestion is idempotent and can be repeated.

## AI-assisted analysis (ADR-016)

`POST /v1/planning/plans/{id}/analysis` computes findings from stored records and optionally has the model word a short summary. Each finding has `facts` (from records) and `hypotheses` (labelled possibilities), evidence links to the planning screens, provenance, freshness, assumptions, risks, confidence, expected impact where it can be calculated, a suggested next step, and, where justified, a **proposal** (manager forecast = half the recent weekly run-rate, half the model forecast). What cannot be analysed is listed with what would enable it (for example, category trends need eight closed weeks, store analysis needs a store feed).

- The model sees only the findings; its text is kept only if every number in it is in the findings (otherwise the rules-based summary is used and the reason is stored). The assistant's `get_planning_analysis` tool never calls a model.
- `POST /analyses/{id}/findings/{fid}/adopt` applies a proposal as an ordinary, version-checked, audited manager-forecast edit (reason `DATA_CORRECTION`) on an editable plan; `/dismiss` records that a person set it aside. Neither approves anything.
- Persisted in `pln_analysis` (plan version, forecast run, model version, data-through date, model and token use) and `pln_analysis_action`.

## Review queue

`GET /v1/planning/review/queue`, `GET /plans/{id}/breakdown?by=region|zone|store`, `POST /review/bulk`. The queue lists plans waiting for a decision with their regions, data scope and evidence strength. Bulk approve, return or reject previews each plan's consequence first, applies only if **every** selected plan is eligible (role, scope, no self-approval), and refuses to approve plans built on demonstration data unless `acknowledge_synthetic` is sent. Approving freezes the plan's lines; it orders, moves, publishes and prices nothing.

## Screens (`/sales-planning`, `/inventory-ai`, `/product-intelligence`)

All three use one header, one colour language for forecast / target / actual and for provenance, and the shared components in `frontend/src/components/analytics`. Planning gained a chart-based dashboard (forecast, target and actual by week, category comparison, variance ranking, region-by-sport heatmap, stock-out risk, AI insights), an AI analysis tab, a review queue, heatmap, coverage and accuracy charts and a workflow stepper. Inventory AI gained the shared header and cards and a notice that its stock is demonstration data held per product and region (never split across stores). Product Intelligence gained a pipeline, source diagnostics (what is configured, what the last run stored, exactly what to set when nothing is configured), a landed-cost breakdown, an offer comparison by landed cost with the kind of each figure, score-by-dimension bars, a workflow stepper and a link from an approved opportunity to a store pilot in planning.

## Validation log

- Data labelling and separation (`e2e/ai/planning_audit.py`, real stack): every demonstration response is labelled; live responses contain no demonstration store or source; the live actuals total equals the live weekly aggregate in the database; online sales are attributed only to `ONL-*` locations; lines with no mappable region are counted (`unknown_region`), not guessed; accuracy on the few live weeks is withheld; store and regional managers see and analyse only their scope; recommendations and approvals change no order, payment, stock or product.
- Methodology review: method choice uses only the selection origins; the seasonal index, shares, parent series and stock-out imputation use only the training window of each origin; missing actuals are skipped, never zero; WAPE and bias come from one function; intervals are calibrated on held-out residuals and no interval coverage is claimed. Defects found and fixed: selection on WAPE alone (above); unknown weeks inside a series produced NaN forecasts; a route-order bug in the new endpoints, caught by looking at the screen.

## Needed before store-level forecasts can be called validated

A real store sales feed (store identifier on each sale), at least two years of store history with stock-out and promotion records, supplier terms for store replenishment, and a procurement or transfer API before any recommendation can be executed. None of these exists today.

## Tests

- `uv run pytest tests/planning` (calendar, conventions, forecasting without leakage, workflow, variance, inventory, scenarios, pilot)
- `uv run pytest -m integration tests/integration/test_planning_db.py` with `PLANNING_TEST_DATABASE_URL` (a throwaway database)
- `mvn -pl order,returns -Pintegration-test` for the sales feed and approved-returns reader
- `pnpm vitest run src/features/planning`
- `python e2e/ai/planning_e2e.py` (real stack API journey, including real orders), python e2e/ai/planning_audit.py (labelling, scope, analysis, review and side-effect checks) and `node e2e/planning-ui.mjs` after `python e2e/ai/planning_data.py > world.json` (real browser; `--cleanup` afterwards)

## Known limits

Store-level data and forecasts are demonstration data; live history is a few weeks; there are no real stores, stock-outs, promotions or supplier terms; accuracy figures are synthetic;
no procurement or transfer API exists, so recommendations stay drafts; regional preferences are discovered from the data shown, never assumed.
