# ADR-015: Sales & Inventory Planning Intelligence (Phase 6)

Status: accepted. Builds on ADR-009 (Python AI service), ADR-010 (inventory intelligence) and ADR-014 (product & supplier intelligence).

## Context

The business wants plans per SKU x store x week with forecasts, targets, actuals, variance explanation and inventory recommendations. Real data today is online orders only; there are no real stores.

## Decision

1. **Where it lives.** In the existing AI service and its `dsports_ai` database (`pln_*` tables). No new service, database or forecasting platform. Java gains one ADMIN-only, read-only, paged sales feed (`order` module) and an approved-returns read port (`returns` module); the AI service never reads commerce tables.
2. **One sales convention**, computed once at ingestion and documented in `conventions.py`, so gross, cancelled, returned and net are never derived twice. Late cancellations and returns restate the original week; a restatement window re-reads recent orders.
3. **Statistical, testable forecasting** (moving average baseline, seasonal methods, ETS, Croston-SBA, top-down, comparable-store fallback) with the method chosen on selection origins and accuracy reported on a held-out origin. No LLM produces a number.
4. **Five separate values** per line (model, manager, target, approved, actual); the model forecast is frozen; approval snapshots the approved forecast; per-line optimistic locking; revisions are new plans; an append-only audit log.
5. **Reuse, not duplicate**, the Phase 2 inventory rules; recommendations are drafts, transfers before purchases, MOQ and case pack applied once per product.
6. **Honest data labelling.** A source tag on every row (`DSPORTS_LIVE`, `IMPORTED`, `POC_SYNTHETIC`, `POC_REFERENCE`); demonstration scope is explicit; thin-sample accuracy is withheld.
7. **Authorization in the service**: role plus location scope (`pln_scope`), approver roles, no self-approval by default. The assistant has read-only tools with `required_any_role`.

## Alternatives considered

- Planning in the Java backend: rejected, the forecasting stack and Phase 2 rules are in Python and Java should stay commerce-only.
- A hierarchical-reconciliation library or external forecasting platform: rejected for now; top-down splits and consistent rollups meet the need and keep the method inspectable.
- Letting the LLM draft forecasts or explanations of cause: rejected; variance output separates recorded facts from labelled hypotheses.

## Consequences

- Store-level numbers are only as good as the data behind them. Until real store sales exist the UI states that they are demonstration data and accuracy is not a business claim.
- The sales feed is a new public surface of the backend (ADMIN only); it carries no personal data.
- Approval has no effect outside the planning tables.
