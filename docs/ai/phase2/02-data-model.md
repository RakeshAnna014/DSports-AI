# 02. Data model, data sources and data quality

## What real data exists (audited, not assumed)

`python e2e/ai/inventory_real_data_audit.py` queries the real DSports database. Result on 2026-10-08:

| Question | Finding |
|---|---|
| Orders | *(figures in this table are the 2026-10-08 audit; re-counted on 2026-10-14 in [README.md](README.md#data-audit-and-forecast-evaluation-re-verified-2026-10-14-supersedes-older-counts-in-this-folder))* 374, between 2026-09-02 and 2026-10-08, but placed on only **4 distinct days** (10 in September, 364 in October) |
| Who | 344 distinct customers: throwaway test accounts created by the E2E scripts, not real shoppers |
| Products sold | 15 of 134 catalogue products (404 units) |
| Status mix | 327 DELIVERED, 26 CANCELLED, 18 CONFIRMED, 2 PENDING_PAYMENT, 1 SHIPPED; 0 duplicate order numbers |
| Inventory | 134 rows, **1 warehouse**, 11,485 units, **no movements table**: only a current snapshot |
| Cost, supplier, lead time, MOQ | **not stored anywhere** |
| Regions | none (shipments carry a state, but only 2 states, all from tests) |
| Events | none |
| Returns | 54 (test) |

Verdict: far too little, too recent and too artificial to train or evaluate a forecast. The brief says not to fake history and call it real,
so Phase 2 uses a clearly labelled synthetic dataset and states it on every screen and API payload.

## Data sources and labels

| Label | Meaning | Examples |
|---|---|---|
| `DSPORTS_LIVE` | read from the DSports public REST APIs at seed time | catalogue (sport, category, brand), selling price, MRP, sellable stock (kept as `dsports_available`, reference only) |
| `POC_REFERENCE` | assumed reference data DSports does not hold | purchase cost, supplier, lead time, MOQ, case pack, regions, events |
| `POC_SYNTHETIC` | generated history and positions | daily demand, stock snapshots, current stock position by region, FIFO age, open purchase orders |

Every row in the demand, snapshot, position and purchase-order tables carries `data_source`. An integration test asserts that no row in any
table carries a label outside `POC_SYNTHETIC` / `POC_REFERENCE` and that every event name contains "(POC)".

The planning position (what the assessments use) is the synthetic position, **not** DSports' live stock, because the live stock (up to 501
units, 11,485 in total) is not consistent with a two-year synthetic demand history. The live number is kept next to it for reference.

## The synthetic generator (`app/inventory/generator.py`)

Deterministic: each SKU is seeded by its own SKU code, so the same SKUs and `as_of` always give identical data, and adding a SKU does not
change another SKU's history (unit-tested). It is not used by the models in any other way than as data.

| Feature | How it is generated |
|---|---|
| Volume | fast / medium / slow movers, scaled by sport and price tier |
| Trend and seasons | per-SKU growth (declining for some), weekly pattern (weekend uplift), annual sport season |
| Events | 15 POC events (past and upcoming) with a run-up ramp and event-specific uplift by category, popularity and regional weights |
| Promotions | 4-6 own promotion windows per SKU plus two site-wide sales; true price elasticity by category; a post-promotion dip |
| Regions | 5 POC regions with sport-specific tilts |
| Noise | gamma-Poisson (over-dispersed counts) |
| Stock | FIFO simulation per SKU and region with a periodic-review policy driven by a naive planner, so organic stock-outs occur and **censor** demand |
| Defects injected for the data-quality checks | ~0.2% of region-days missing; write-downs (stock corrections) |
| Scripted situations | pinned to named categories so the demo has something to find: cricket bats, gloves, a ball and some running/football shoes at stock-out risk; some jerseys, a kit bag overstocked; some tennis/badminton jerseys slow. Documented in `_PINNED`; the models are never told |

Generated on 2026-10-08: 488,093 demand rows, 244,550 snapshot rows, 15 events, 309 open purchase orders; 13 SKUs scripted at risk,
11 overstock, 12 slow, 98 normal.

## Mart tables (AI database `dsports_ai`, prefix `inv_`)

`inv_sku`, `inv_region`, `inv_event`, `inv_demand_daily`, `inv_stock_snapshot`, `inv_position`, `inv_purchase_order` (data);
`inv_model_run`, `inv_forecast`, `inv_event_impact`, `inv_elasticity`, `inv_region_share`, `inv_assessment` (model output);
`inv_scenario_run`, `inv_audit`, `inv_dq_report` (operations). Created idempotently at service start
(`app/inventory/schema_inventory.sql`). Nothing is written to the transactional database.

Why not a separate analytical database: 134 SKUs and about 0.5 million rows do not justify one; the tables are isolated by prefix and
queried only through `app/inventory/repository.py`, so they can move later.

## Data-quality checks (reported, never silently fixed)

Run on every training run and stored in `inv_dq_report`; shown on the Forecast tab. Latest result:

| Check | Status | Finding |
|---|---|---|
| history length | OK | 730 days per SKU and region |
| missing dates | WARN | 1,007 of 489,100 SKU-region-days absent (0.21%); rebuilt from observed regions scaled by share, flagged imputed |
| duplicates | OK | primary key makes them impossible |
| negative units | OK | 0 |
| zero-sales days | INFO | 56.6% of SKU-region-days (slow movers; kept as real zeros) |
| stock-out days | WARN | 20,971 SKU-region-days with unmet demand: product-days where at least 20% of demand was censored are excluded from training and evaluation |
| promotion and event days | INFO | 15,197 promotion (+7 day dip) and 3,591 event product-days modelled separately |
| outliers | INFO | 23 baseline days beyond 6 robust standard deviations; kept |
| insufficient history | OK | every SKU has at least 56 clean days; otherwise "Insufficient data to produce a reliable forecast" |
| reference data | OK | no SKU lacks lead time, cost or MOQ (a missing lead time yields status `MISSING_LEAD_TIME` and no order quantity) |
| stock corrections | WARN | 199 unexplained stock decreases kept as recorded |

Cancelled orders, returns and duplicate orders belong to the transactional database and are covered by the audit script above, not
by the mart.

## Provenance on every response

Every data endpoint and every Copilot answer carries a `provenance` object so that a number can always be traced to a stored run and a data class:

| Field | Meaning |
|---|---|
| `run_id`, `model_name`, `model_version` | the persisted model run that produced the forecast, elasticity, event impact and positions |
| `as_of`, `training_start`, `training_end` | the data date and the training window of that run |
| `demand_history`, `stock_positions_and_purchase_orders` | `POC_SYNTHETIC` |
| `cost_supplier_lead_time_moq_regions_events` | `POC_REFERENCE` |
| `catalogue_and_prices` | `DSPORTS_LIVE` (a snapshot taken when the mart was seeded) |

Scenario runs additionally store the run and model information in `inv_scenario_run`, so a logged what-if can be tied to the model that produced its inputs.
Negative unit values in history are treated as anomalies (missing, counted in the data-quality report), never as demand. Seeding twice is reproducible and does not duplicate rows
(`tests/integration/test_inventory_db.py`).
## Replacing the POC data with real data

Provide, per SKU and day: units sold (net of cancellations and returns), stock snapshots or movements, discounts, and for the business:
purchase cost, supplier, lead time, MOQ, regions, an event calendar and open purchase orders. Load them into the same tables with
`data_source = 'DSPORTS'`; remove `generator.py` from the seed path. The models, planning rules, tools, API and UI are unchanged.
