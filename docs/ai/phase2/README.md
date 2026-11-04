# Phase 2: AI-powered Inventory Intelligence

Phase 1 helps **customers** decide what to buy. Phase 2 helps the **business** decide what to stock, replenish, clear and promote, with
the existing DSports Java platform still the source of truth.

> **Read this first.** The demand history, stock positions, purchase orders, supplier data, lead times, costs, regions and events in this
> phase are a **labelled synthetic proof-of-concept dataset** (`POC_SYNTHETIC` / `POC_REFERENCE`): DSports does not yet hold usable history
> (see [02](02-data-model.md)). Every accuracy number in these documents measures the pipeline on that data. It is not DSports' demand
> forecast accuracy. Catalogue, prices and current sellable stock are real DSports data.

| # | Document | Contents |
|---|---|---|
| 01 | [Overview](01-inventory-intelligence-overview.md) | architecture, principles, what exists, demo flow |
| 02 | [Data model](02-data-model.md) | real-data audit, synthetic generator, mart tables, data quality |
| 03 | [Forecasting](03-forecasting.md) | cleaning, models, backtests, metrics, intervals, versioning |
| 04 | [Stock risk](04-stock-risk.md) | days of cover, probability, HIGH/MEDIUM/LOW rules |
| 05 | [Replenishment](05-replenishment.md) | quantities, MOQ, order dates, expediting |
| 06 | [Overstock and clearance](06-overstock-clearance.md) | excess, aging, health classes, clearance candidates |
| 07 | [Event impact](07-event-impact.md) | estimated uplift, evidence, fallbacks, readiness, regions |
| 08 | [Promotion scenarios](08-promotion-scenarios.md) | elasticity, scenario maths, margin floor, selective plan |
| 09 | [Inventory agent](09-inventory-agent.md) | LangGraph supervisor, tools, MCP, guardrails |
| 10 | [Security](10-security.md) | authorization, audit, document separation, prompt injection |
| 11 | [Evaluation](11-evaluation.md) | datasets, results, what was and was not tuned |
| 12 | [Cost optimization](12-cost-optimization.md) | where the LLM is and is not used, tokens, cost, latency |
| 13 | [Runbook](13-runbook.md) | commands, API contracts, tests, troubleshooting |
| 14 | [Final stabilization report](14-final-stabilization-report.md) | audit results, defects fixed, 35-scenario suite, measured performance, limitations, the one validation command |

Architecture decision: [ADR-010](../../adr/ADR-010-inventory-intelligence.md).

## Data audit and forecast evaluation re-verified 2026-10-14 (supersedes older counts in this folder)

Counted directly in the running databases, not copied from earlier reports.

| Fact | Value |
|---|---|
| Real commerce orders (`dsports.orders`) | 1,895 over **10 distinct days** (2026-09-02 to 2026-10-13); the count grows with every E2E run that places orders |
| Products / products ever ordered | 134 / 18 |
| Warehouses | none in the Java model (the mart has one POC warehouse and 5 POC regions) |
| Mart history (`dsports_ai.inv_demand_daily`) | 488,093 rows, 2024-10-08 to 2026-10-07, **100% `POC_SYNTHETIC`** |
| Stock snapshots / positions / purchase orders / events | 244,550 / 670 / 309 / 15 (all POC synthetic or POC reference) |
| Current model | `ridge_regression`, trained on 134 series, as-of 2026-10-08 |
| Time-based holdout (last 90 days, 10,049 series-days) | WAPE 0.386 (ridge) vs 0.431 (28-day moving average baseline): 10.5 % better; 90-day sum WAPE 0.074; 80 % interval coverage 0.75 to 0.86 |

Interpretation: these accuracy figures describe the pipeline on synthetic history. They say nothing about accuracy on real DSports demand, which has 10 days of real orders over 18 products: far too little to train or validate a forecast. Real cost, supplier, lead-time and regional data do not exist in the commerce database; the mart uses clearly labelled POC reference values for them.
