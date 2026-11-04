# 01. Inventory Intelligence: overview

## Purpose

Give business users actionable inventory decisions, each with the reason behind it:

- what is likely to **stock out**, and when;
- what to **replenish**, how much, by when, and whether to expedite an open order;
- what is **overstocked**, **slow moving** or **aging**, and which products are **clearance** candidates;
- how a **tournament or event** changes demand and whether stock is sufficient;
- how demand differs **by region**, and where excess could move;
- what happens under a **30% or 50% discount** (units, revenue, margin, ending stock, clearance time, stock-out risk).

## The principle that shapes everything

```
FORECASTING / MATHS (deterministic code)        vs        LLM (language only)
  demand forecast, intervals                               understand the question
  days of cover, stock-out probability                     choose which tools to run
  safety stock, reorder point, order quantity              explain results in business language
  overstock, aging, health class                           summarise for management
  event uplift, promotion scenarios                        cite policy documents
```

The LLM never produces a number. Every number in an explanation is checked against the tool results of the same response; an answer
that contains a number that is not in the data is replaced by the verified deterministic summary (see [09](09-inventory-agent.md)).

## Architecture

```
Business user (ADMIN) ──► /inventory-ai  (React: Overview, Forecast, Stock Risk, Replenishment, Overstock, Promotions, Events + Copilot)
                               │  /ai-api/v1/inventory/*   (same JWT as the shop)
                               ▼
                      AI service (FastAPI)  ── admin dependency on every route
                       ├─ REST read API ──────────────┐
                       └─ Inventory Supervisor (LangGraph) ─► typed tool registry (READ, role ADMIN) ◄── MCP facade
                                                               │
                      ┌────────────────────────────────────────┴──────────────────────────────┐
                      ▼                                                                       ▼
            Persisted results (inv_* tables, AI database)                       Internal policy RAG (pgvector, document types inv_*)
            forecasts · assessments · event impact · elasticity · audit
                      ▲
                      │ manual / scheduled-later job:  seed → clean → backtest → select → forecast → assess
            Mart: demand, stock, positions, purchase orders, events, reference data  (POC synthetic + POC reference)
                      ▲
            DSports REST (public): catalogue, prices, current sellable stock  ← the real source of truth
```

No new microservice, no second database, no new infrastructure: one focused domain inside the existing AI service
([ADR-010](../../adr/ADR-010-inventory-intelligence.md)). The AI never queries the Java database and never writes to it.

## What was built

| Area | Implemented |
|---|---|
| Data | reproducible POC generator; 134 SKUs x 5 regions x 730 days; data-quality report; real-data audit script |
| Forecasting | 4 models, rolling-origin backtests, held-out evaluation, calibrated 80% intervals, versioned runs |
| Planning | days of cover, stock-out probability, safety stock, reorder point, replenishment, overstock, aging, health classes |
| Events | estimated uplift from past events with evidence, POC-assumption fallback, per-SKU readiness, regional split |
| Scenarios | elasticity from promotion history, discount scenarios, margin floor, per-product selective plan, clearance ranking |
| Agent | LangGraph supervisor with 15 workflow nodes, 17 typed tools, MCP exposure, grounding and consistency guards |
| RAG | 6 internal policy documents (Tika, embeddings, pgvector), cited answers, isolated from customer retrieval |
| Security | ADMIN-only routes and tools, audited denials, no write tools, refusal of execute requests |
| UI | internal route `/inventory-ai` with charts built on real series, product drill-down with reasons, copilot dock |
| Quality | 117 inventory-specific Python tests (107 unit + 10 real-PostgreSQL integration), 2 backend ordering tests, 60 numeric and 67 copilot evaluation turns, real-browser E2E (23 steps) |

## What it deliberately does not do

It does not change prices, create or approve purchase orders, transfer stock, launch promotions or modify DSports data. It is decision
support: every recommendation is labelled "AI Recommendation" and says that nothing was executed.

## Management demo flow

1. Open **Overview**: health counts, top risks, recommended actions, the cricket tournament in 28 days.
2. Copilot: "Are we ready for the tournament?" shows uplift by category with its evidence, SKUs projected short, quantities and order-by dates, regional demand.
3. "Which products are at risk?" then "What should we replenish?" with quantities and dates, including open orders to expedite.
4. "What about our slow-moving inventory?" then "Which products should go on clearance?"
5. "What if we run 30% off?" then "What if we run 50% off?" then "Which is better?" The engine's conclusion and the trade-off, including the margin floor.

Every figure comes from the models; the screen states that the data is POC synthetic.
