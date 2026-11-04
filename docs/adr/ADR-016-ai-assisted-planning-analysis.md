# ADR-016: AI-assisted planning analysis, the review queue and the shared analytics UI

Status: accepted. Extends ADR-015 (Sales & Inventory Planning). Reuses ADR-009 (Python AI service) and ADR-010 (inventory intelligence).

## Context

Phase 6 produced forecasts, plans and approvals. Planners still had to find, by hand, where a forecast disagreed with recent sales, which stores or categories needed attention, and which plans were waiting for a decision. The three internal screens (planning, inventory, product intelligence) also looked and behaved differently.

## Decision

1. **Findings are deterministic; the LLM only words them.** `insights.py` computes findings from stored records (forecast against the last closed weeks, closed-week variance, repeated shortfalls, category trends, stock risk from the plan's allocation draft, data readiness). Each finding separates `facts` from `hypotheses`, lists evidence links, provenance, freshness, assumptions, risks, confidence and a next step. A proposal (a manager forecast: 50% recent run-rate, 50% model) is only ever proposed.
2. **The LLM step is optional, bounded and verified.** One `reasoning`-tier call receives only the findings, writes at most five bullets, and its text is kept only if every number in it appears in the findings (the Inventory AI's grounding check); otherwise the rules-based summary is used and the reason is stored. A model failure leaves the findings intact. The assistant's tool never calls a model. Usage is recorded by the existing LLM usage recorder.
3. **Everything is persisted** (`pln_analysis`, `pln_analysis_action`): plan version, forecast run and model version, data-through date, who ran it, whether the summary was model-worded, token counts, and who adopted or dismissed each finding.
4. **Adopting is an ordinary edit.** It goes through the same version-checked, role- and scope-checked, audited line edit as a person typing the number, needs an editable plan and records reason `DATA_CORRECTION`. It cannot approve anything.
5. **One review queue** lists plans awaiting a decision with their regions, data scope, evidence strength and a region/zone/store breakdown. Bulk approve/return/reject previews every consequence first, is all-or-nothing on eligibility, re-checks every plan with its own rules (role, scope, no self-approval), and **approving plans built on demonstration data needs an explicit server-checked acknowledgement** (`acknowledge_synthetic`).
6. **A shared analytics UI** (`frontend/src/components/analytics`): provenance chips derived from persisted source values (an unknown value is "Unavailable", never guessed), confidence meters, KPI cards that show why a figure is unavailable, gap-aware time series, heatmaps with hatched no-data cells, ranked variance bars, coverage bars, a page hero and workflow stepper. No new charting dependency.

## Alternatives considered

- Asking the LLM to analyse tables: rejected; it would invent or mis-state numbers and cost a call per slice.
- One finding per SKU-store: rejected; findings are grouped (category, store, region) and proposals are capped.
- A client-only "are you sure" for demonstration plans: rejected; the server enforces it.

## Consequences

- Findings depend on the stored history: with a few live weeks most analyses report "not analysed, needs ...".
- The analysis surfaced two real forecasting defects while it was being built (intermittent series were selected on WAPE alone and forecast almost nothing; unknown weeks inside a series could yield NaN forecasts). Both are fixed in `forecast.py` and covered by tests; the reported synthetic accuracy changed as a result (see the Phase 6 README).
