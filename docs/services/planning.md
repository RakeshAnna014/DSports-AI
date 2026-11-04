# Sales & Inventory Planning

Code: `ai-service/app/planning/` plus the Java sales feed (`GET /api/v1/admin/sales/lines`). Depth: [../ai/phase6/README.md](../ai/phase6/README.md), [ADR-015](../adr/ADR-015-sales-planning.md), [ADR-016](../adr/ADR-016-ai-assisted-planning-analysis.md). UI: `/sales-planning`.

## 1. Business purpose
Decision support for planning per SKU × store × week, rolled up to category, sport, zone, region, month, quarter and financial year: a model forecast, a manager forecast, a target, an approved forecast and the actuals, with variance, replenishment and transfer recommendations, scenarios and pilots, and an approval workflow.

## 2. Entry points
`/v1/planning/*` (54 operations) and 11 read-only tools (`get_planning_overview`, `get_weekly_forecast`, `get_forecast_accuracy`, `get_plan_vs_actual`, `explain_variance`, `get_inventory_recommendations`, `get_regional_analysis`, `get_store_hierarchy`, `get_data_readiness`, `simulate_planning_scenario`, `get_scenarios_and_pilots`) plus `get_planning_analysis`. The assistant (`ask.py`) is a deterministic router over those tools.

## 3. Request lifecycle
*Ingest*: `POST /v1/planning/ingest` (ADMIN) pulls the Java sales feed with the admin's token (keyset pages, no personal data), applies the sales convention once (`conventions.py`: net = gross − cancelled − returned; only CONFIRMED, PROCESSING, SHIPPED, DELIVERED, REFUNDED count as sales, never CREATED or PENDING_PAYMENT) and re-sums touched weeks. *Forecast*: `forecast_run.py` runs `forecast.py` (`weekly-v1`: moving average baseline, seasonal naive, seasonal level, damped ETS, Croston-SBA, top-down, comparable-store fallback), choosing a method on selection origins by WAPE plus absolute bias and reporting accuracy on a separate held-out origin; the previous run stays current if a run fails. *Plan*: `workflow.py` (`DRAFT → SUBMITTED → UNDER_REVIEW → APPROVED → PUBLISHED`, plus return, reject, withdraw) with version-checked line edits and an audit row in the same transaction. *Analyse*: `insights.py` finds facts and hypotheses deterministically; `service_insights.py` may ask a model to word a summary which is kept only if every number in it is in the findings.

## 4. Business rules
Model forecast, manager forecast, target, approved forecast and actual are always separate; the model forecast is never overwritten. Accuracy on thin samples is withheld; a missing week is a confirmed zero only for live data. Replenishment reuses the Inventory Intelligence rules (reserved stock excluded, incoming counted once, MOQ and case pack applied once, transfers offered before purchases). Scenarios (GROWTH_TARGET, PROMOTION, LEAD_TIME, SAFETY_STOCK, TRANSFER) never change a plan; only a growth-target scenario can be adopted, as targets. Approval, recommendations and pilots never order, move stock, publish or price. Self-approval is refused unless `PLANNING_ALLOW_SELF_APPROVAL`; plans built on demonstration data cannot be bulk-approved without a server-checked acknowledgement.

## 5. Data ownership
`dsports_ai.pln_*` (schema `planning/schema_planning.sql`). Live online orders come from Java (read-only). **Store network, store-level history and store stock are demonstration data** (`POC_SYNTHETIC`, labelled everywhere); the held-out accuracy shown describes the method on that data, not business accuracy; live history is a few weeks.

## 6. External dependencies
Java sales feed (ADMIN token), the Inventory Intelligence functions, Gemini only for optional summary wording.

## 7. Security and authorization
Roles per route on the server: `ADMIN`, `CATEGORY_MANAGER`, `SALES_PLANNER` see everything; `REGIONAL_MANAGER` and `STORE_MANAGER` only the locations granted in `pln_scope`, applied on every list, detail, edit, analyse, decide and bulk path. Approvers: `ADMIN`, `CATEGORY_MANAGER`, `REGIONAL_MANAGER`; planners edit but cannot approve; customers, support and buyers get 403 (observed in the [matrix](../security/AUTHORIZATION-MATRIX.md)). Edits use optimistic `line_version`, row locks and an audit append; per-person analysis rate limit (`PLANNING_ANALYSES_PER_MIN`). Tests: `tests/planning`, `tests/integration/test_planning_db.py` (scope, concurrency, replay, self-approval, frozen plan), `e2e/ai/planning_audit.py`.

## 8. Failure handling and observability
A failed forecast run keeps the previous current run; ingestion is idempotent and can be repeated; a model failure falls back to the rules-based summary and stores why. Every change is in `pln_audit`; `pln_data_through` states how fresh each input is; readiness (`GET /v1/planning/readiness`) names every missing input.

## 9. Run and test
```bash
# stack up (see SETUP-AND-DEMO), then as ADMIN:
#   POST /v1/planning/ingest   POST /v1/planning/demo/seed   POST /v1/planning/forecast/runs
cd ai-service && uv run pytest -q tests/planning
PLANNING_TEST_DATABASE_URL=... uv run pytest -m integration tests/integration/test_planning_db.py
python e2e/ai/planning_e2e.py ; python e2e/ai/planning_audit.py
python e2e/ai/planning_data.py > scratch_shots/planning-world.json && node e2e/planning-ui.mjs scratch_shots/planning-world.json scratch_shots ; python e2e/ai/planning_data.py --cleanup
```

## 10. Implemented / partial / missing
Implemented: ingestion, forecasting, plans and approvals, variance, inventory and transfer recommendations, scenarios, pilots, AI-assisted analysis and review queue, UI. Partial: store-level everything is demonstration data. Missing: a real store feed, stock-out and promotion history, supplier terms, any procurement or transfer API (so recommendations stay drafts).

## 11. First files to read
`planning/conventions.py`, `forecast.py`, `workflow.py`, `repo_plans.py`, `insights.py`, `service_insights.py`, `api.py`, then `schema_planning.sql`.
