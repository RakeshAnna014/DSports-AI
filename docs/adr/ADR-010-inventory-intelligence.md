# ADR-010: Inventory Intelligence (Phase 2) inside the Python AI service

## Status

Accepted (2026-10-08). Implemented; see `docs/ai/phase2/`.

## Context

Phase 1 helps customers decide what to buy. Phase 2 helps the business decide what to stock, replenish, clear and promote.
The brief requires real forecasting (not an LLM guessing numbers), explainable recommendations, tournament/event impact,
promotion what-ifs, internal-only access and no autonomous execution.

Facts that shaped the design (verified, see `02-data-model.md`):

- (Counts as of the decision date; re-counted 2026-10-14 in docs/ai/phase2/README.md: 1,895 orders on 10 days, 18 of 134 products ordered.) The DSports database had 374 orders on 4 distinct days (test traffic from 344 throwaway accounts, 15 of 134 products), one warehouse,
  only a current stock snapshot (no movements) and no cost, supplier, lead time, region or event data. That is not enough to forecast from.
- The AI service already provides authentication, a typed tool registry, MCP, LangGraph, retrieval over pgvector, usage and cost tracking,
  and evaluation tooling.

## Decision

1. **Models calculate, the LLM explains.** Forecasting, stock risk, replenishment, overstock, event impact and promotion scenarios are
   deterministic Python/numpy code over persisted results. The LLM understands the question, orchestrates tools and explains. Every number in
   an explanation is checked against the tool results; an answer with a number that is not in the data is replaced by the verified summary.
2. **One focused domain, no new microservice.** The code lives in the existing AI service (`app/inventory/`), with its own tables
   (`inv_*`) in the existing AI database (`dsports_ai`), never in the transactional commerce database. A separate analytical database is
   not justified for a 134-SKU proof of concept; the tables are isolated by prefix and can move later.
3. **Synthetic history is explicit.** A deterministic generator creates two years of daily demand, stock, purchase orders and events.
   Every row is labelled `POC_SYNTHETIC` or `POC_REFERENCE`; the UI and every API payload say so. Real DSports catalogue, price and current
   stock are read through the public REST APIs and are labelled as such.
4. **Decision support only.** There are no WRITE tools for inventory. Prices are owned by the DSports pricing engine; purchase orders by
   procurement. The copilot refuses requests to execute and labels every recommendation "AI Recommendation".
5. **Internal only.** Every inventory route and tool requires the ADMIN role, enforced in the backend (route dependency and the tool
   registry), audited on denial. Internal documents use document types prefixed `inv_` and are excluded from customer retrieval in SQL.
6. **One supervisor graph.** A single LangGraph with one node per business capability, using the same typed tool registry and MCP facade.

## Alternatives considered

- *Let Gemini forecast.* Rejected: not reproducible, not evaluable, not cheap, and forbidden by the brief.
- *Prophet / XGBoost / ARIMA.* Not adopted: the data is synthetic, a ridge regression with weekly and annual terms beat the baselines on the
  held-out period, and every extra library would add weight without evidence. The model interface allows adding one when real history exists.
- *A separate forecasting microservice or a second database.* Rejected as artificial complexity at this scale.
- *Writing forecasts into the Java database.* Rejected: analytical load and AI-derived data do not belong in the transactional schema.

## Consequences

- Forecast accuracy figures describe the pipeline on synthetic data, not DSports demand. They must never be quoted as business accuracy.
- Replacing the generator with real history (orders, movements, supplier data) is a data-engineering task, not a redesign: the mart schema,
  models, planning rules, tools, API and UI stay.
- Retraining is manual (`POST /v1/inventory/models/train` or the CLI). The function is the same a scheduler would call.
