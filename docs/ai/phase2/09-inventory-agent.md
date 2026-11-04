# 09. Inventory Intelligence agent (the AI Inventory Copilot)

Code: `app/inventory/copilot.py` (graph, nodes, composition), `understand.py` (rules), `grounding.py` (guards), `tools.py` (tools).

## One supervisor, focused workflows

A single LangGraph (`StateGraph`) with **15 nodes**, one per business capability, selected by the understood intent. No agent per tool.

```
understand ─► route ─► overview | stock_risk | replenish | overstock | slow | clearance | promotion | compare
                       event | forecast | region | policy | quality | refuse | unknown  ─► compose ─► response
```

| Step | What happens | Model? |
|---|---|---|
| understand | rules extract intent and entities (sport, category, product, discounts, "older than N days", "next month", "top N"); names that match nothing are detected | no (Tier 0) |
| (fallback) | only when the rules cannot place the question: Gemini Flash-Lite returns JSON `{intent, sport, category}` | Tier 1, rare |
| node | calls registered tools (below), builds the structured `blocks` for the UI and a deterministic `headline` | no |
| compose | explains the facts in business language, at most about 110 words | Tier 2 (Gemini 2.5 Flash) |
| guards | numeric grounding; scenario consistency; fall back to the headline if either fails | no |

Follow-ups work through conversation memory (intent, entities, last scenario) owned by the caller: "Why?" re-runs the risk tools plus
`get_sales_history` and `get_demand_forecast` for the drivers; "Which scenario is better?" reuses the last scenario's selection and discounts.

## Tools (17, all READ, role ADMIN)

`get_inventory_overview`, `get_inventory`, `get_inventory_snapshot`, `get_inventory_history`, `get_sales_history`, `get_demand_forecast`, `get_stock_risk`,
`get_replenishment_recommendation`, `get_overstock_products`, `get_slow_moving_products`, `get_inventory_by_region`, `get_events`, `get_event_impact`,
`run_promotion_scenario`, `get_clearance_candidates`, `get_forecast_quality`, `get_inventory_policy`. Registered in the **same registry** as the customer tools,
with strict argument models (unknown fields such as `newPrice` or `userId` are rejected) and a `required_role = ADMIN` enforced in the registry for every caller.
The MCP facade exposes them like any other READ tool; calls are still role-gated. **There is no WRITE tool** for prices, purchase orders, transfers or promotions.

## What the explanation may and may not do

The prompt gives the model the facts as JSON (`evidence`, also returned to the client) and rules: use only numbers in the facts, never calculate or count,
no lakh/crore, no numbers as words, recommend only what the engine concluded, name products exactly, say once that the data is POC synthetic, at most 110 words.

| Guard | Rule | On failure |
|---|---|---|
| numeric grounding | every number (and number word) in the text must equal a number in the facts after rounding **or truncating** to the precision written (4.17 may be written 4.2, 4.1 or 4; never 5; "eight" is rejected unless 8 is a fact; "lakh/crore/million" are never accepted) | the deterministic headline is returned, `llm.grounding_fallback = true`, a warning is shown |
| scenario consistency | a recommended discount percentage must equal the engine's recommended discount; if the engine recommends none, none may be recommended | same |
| input guard | injection, credential, escalation and customer-data requests (the Phase 1 guard plus a customer-data pattern) | status `REFUSED`, intent `BLOCKED`, no model call, no tool |
| impossible promotion | a discount outside 1%-70% or negative | status `INVALID_REQUEST`, no tool, nothing calculated (it is never silently replaced by 30%/50%) |
| unknown names | a capitalised name that matches no product/sport/category/brand is not answered with portfolio-wide data | "I couldn't find ... so I won't guess", status `UNAVAILABLE` |
| execute requests | "create a purchase order", "change the price", "launch a promotion", "transfer stock" | status `REFUSED`; no tool and no model call |
| insufficient data | tool reports no forecast | "Insufficient data to produce a reliable forecast.", status `INSUFFICIENT_DATA` |

## Response contract (`POST /v1/inventory/copilot`)

`conversationId, intent, status (OK | INSUFFICIENT_DATA | UNAVAILABLE | INVALID_REQUEST | REFUSED | UNKNOWN | ERROR), message, headline, blocks[], tools[], sources[], provenance{run_id, model_name, model_version, as_of, data labels},
warnings[], dataSource, recommendationLabel ("AI Recommendation (decision support: nothing was executed)" or null), llm{used, model, tokensIn, tokensOut,
grounding_fallback, latency_ms, understanding}, suggestions[], evidence{}`. Blocks are typed (`risk_table`, `replenishment_table`, `overstock_table`, `clearance_table`,
`forecast_chart`, `scenario_comparison`, `event_readiness`, `region_table`, `sources`, `quality`, `overview`, `notice`) and rendered by the same React components as the tabs.

## Failure behaviour

| Failure | Behaviour |
|---|---|
| model unavailable (Tier 1 or 2) | the structured answer and the deterministic headline are still returned (`llm.used = false`); tested |
| AI database unavailable | routes return `503 INVENTORY_UNAVAILABLE` (after authorization); the storefront is unaffected |
| no trained run | `404 INSUFFICIENT_DATA` "No inventory forecast exists yet ... run training first" |
| tool error / timeout | status `UNAVAILABLE` or `ERROR` with a plain message; never a guess |

## Observability

Every request records its LLM calls in the existing `usage` table with intent labels `inventory_*` (tokens, latency, estimated cost; see [12](12-cost-optimization.md));
Prometheus counters/histograms for requests, tool latency, guard blocks, grounding fallbacks, training duration, scenarios and held-out WAPE; `inv_audit` rows for every query
(intent, tool names, latency, token counts, the first 200 characters of the question), training run and denied attempt. No secrets, tokens or personal data are logged.
