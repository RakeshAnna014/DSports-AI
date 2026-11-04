# Inventory Intelligence (decision support for stock)

Code: `ai-service/app/inventory/`. Depth: [../ai/phase2/](../ai/phase2/README.md) (14 documents), [ADR-010](../adr/ADR-010-inventory-intelligence.md). UI: `/inventory-ai/:tab?` (ADMIN).

## 1. Business purpose
Tell the business what is likely to stock out, what is overstocked, what to reorder and how much, what a promotion or event might do, and why. **Decision support only**: no price change, purchase order, transfer or promotion is ever executed.

## 2. Entry points
`/v1/inventory/*` (19 operations, ADMIN; see [../api/ai-service.md](../api/ai-service.md)), the inventory copilot (`inventory/copilot.py`, `understand.py`), 17 ADMIN-only `get_*` and `run_promotion_scenario` tools in the registry, CLI `uv run python -m app.inventory.cli seed|train`.

## 3. Request lifecycle
Question → rule-based understanding (`understand.py`) → typed tool calls (`tools.py`) → repository queries over the mart (`repository.py`) → deterministic analysis (`forecasting.py`, `planning.py` for stock risk and replenishment, `scenarios.py`, `events.py`, `quality.py`) → evidence-backed answer; an optional model call only re-words it and is checked by `grounding.py` (numbers must come from tool results).

## 4. Business rules
* Forecasts come from a persisted model run (`inv_model_run`, `inv_forecast`): cleaning, candidate models, a time-based backtest (last 90 days held out) against a 28-day moving-average baseline, prediction intervals; the best model per series is stored.
* Stock risk is computed from days of cover and a probability of stock-out into HIGH/MEDIUM/LOW; replenishment applies lead time, MOQ and case pack; overstock and clearance use ageing and health classes; promotions use stored elasticity and a margin floor.
* Real inputs: catalogue, prices, current sellable stock (via Java). **Synthetic / reference inputs: demand history, stock positions, purchase orders, suppliers, lead times, costs, regions and events** (`data_source` = `POC_SYNTHETIC` or `POC_REFERENCE` on every row, labelled in every answer and screen). The held-out accuracy (re-verified 2026-10-14: WAPE 0.386 vs 0.431 baseline) describes the pipeline on synthetic history, not business accuracy.

## 5. Data ownership
`dsports_ai.inv_*` tables; the AI service never writes Java tables. Real orders exist for only about 10 distinct days and 18 products, which is why history is synthetic ([../ai/phase2/README.md](../ai/phase2/README.md)).

## 6. External dependencies
Java REST for catalogue, price and stock; Gemini optionally for wording; no other system. There are no warehouses in the Java model; the mart has one POC warehouse and five POC regions.

## 7. Security and authorization
ADMIN only on the server for the REST routes and for these tools (`required_role="ADMIN"`; the planning tools additionally allow planning roles). Observed: customers and support 403, admin 200 ([matrix](../security/AUTHORIZATION-MATRIX.md)). Every call is audited (`inv_audit`); question text and stored names are fenced as untrusted; no tool takes SQL or paths.

## 8. Failure handling and observability
Model down: the deterministic answer is returned with a note. Missing data: the answer says which input is missing and which readiness check failed (`quality.py`, `inv_dq_report`). Usage and cost are recorded; correlation id in logs.

## 9. Run and test
`uv run python -m app.inventory.cli seed && uv run python -m app.inventory.cli train` (synthetic data, a labelled POC), then open `/inventory-ai`. Tests: `uv run pytest -q tests/inventory`, `tests/integration/test_inventory_db.py`, `tests/unit/test_inventory_authz_matrix.py`, `e2e/inventory-ui.mjs`, `e2e/ai/inventory_*.py`, evaluation `eval/inventory/`. The held-out inventory set was consumed on its first run and is now regression-only.

## 10. Implemented / partial / missing
Implemented: forecasting, risk, replenishment, clearance, events, promotion scenarios, copilot, UI. Partial: no real demand history, no supplier or lead-time data, no purchase-order execution. Missing by design: any write to commerce systems.

## 11. First files to read
`inventory/pipeline.py`, `forecasting.py`, `planning.py`, `tools.py`, `copilot.py`, `grounding.py`, `schema_inventory.sql`.
