# 12. Cost and latency

## Where a language model is and is not used

| Work | Done by | LLM tokens |
|---|---|---|
| demand forecasting, intervals, backtests, model selection | numpy code (about 21 s per training run, on demand) | **0** |
| days of cover, stock-out probability, safety stock, reorder point, order quantity | `planning.py` | **0** |
| overstock, aging, health classes, clearance ranking | `planning.py`, `scenarios.py` | **0** |
| event uplift, regional split, transfers | `events.py`, `repository.py` | **0** |
| promotion scenarios and the recommendation | `scenarios.py` | **0** |
| aggregation, sorting, filtering, ranking in every table and chart | SQL / Python | **0** |
| understanding the question | rules (Tier 0); Gemini 2.5 Flash-Lite only when rules fail | 0 in 45 of 47 development turns; the 2 that needed it were the adversarial "ignore your rules" / "show customer orders" prompts |
| explaining the result in business language | Gemini 2.5 Flash (Tier 2), about 110 words | about 1,500 in / 150 out |
| answering policy questions | hybrid retrieval over pgvector + the same Tier 2 explanation | about 2,000 in |

The UI tabs (Overview, Forecast, Stock Risk, Replenishment, Overstock, Promotions, Events) call the REST API and use **no model at all**.
Only the copilot's explanation sentence costs tokens, and it is optional: if the model is down the structured answer and the deterministic headline are returned.

## Measured (real Gemini via Vertex AI; costs are ESTIMATES from the configured price table: Flash $0.30 / $2.50, Flash-Lite $0.10 / $0.40 per million tokens in / out)

| Run | Turns | LLM calls | Tokens in / out | Estimated cost | Per question | p50 / p95 latency |
|---|---|---|---|---|---|---|
| development set (first run) | 44 | 40 | 67,737 / 6,288 | $0.0359 | $0.00082 | 3.8 s / 4.4 s * |
| development set (final) | 47 | 42 | 70,185 / 6,597 | $0.0374 | $0.00080 | 3.7 s / 4.4 s * |
| held-out set v1 (first run) | 20 | 21 | 24,242 / 2,656 | $0.0137 | $0.00068 | 3.7 s / 6.7 s * |
| 10 mixed questions (stabilization audit, `inventory_measure.py`) | 10 | 9 | 17,619 / 1,607 | $0.0093 | $0.00093 | **1.6 s / 3.2 s** |

\* **These end-to-end latencies are inflated by about 2 s of client overhead** that the audit found: the Python test helper called `http://localhost:...`, and on Windows `localhost` resolves to IPv6 first, so every request waited about 2 s before falling back to IPv4 (measured: 2.07 s per request through `localhost`, 0.03 s through `127.0.0.1`). The helper now uses `127.0.0.1`. The token counts and costs are unaffected. The last row is the corrected measurement.

Roughly **$0.0007 to $0.0008 per copilot question**, or about **$0.75 per 1,000 questions**, plus a one-off embedding cost for the six policy documents.
The `GET /v1/inventory/usage` endpoint reports the live totals by intent, model, tokens, estimated cost, p50/p95 latency and errors.

### Measured latency of the deterministic views (127.0.0.1, 15 requests each, this laptop)

| Endpoint | p50 | p95 |
|---|---|---|
| overview, risk, replenishment, overstock, slow-moving, aging, events, quality | 29 to 31 ms | 31 to 35 ms |
| forecast (portfolio) / one product | 61 ms / 16 ms | 64 ms / 31 ms |
| clearance candidates (runs the scenario engine) | 46 ms | 47 ms |
| regions | 92 ms | 97 ms |
| scenario (`POST`, 60 products, 2 discounts) | 41 ms | |

Copilot: p50 1.6 s, p95 3.2 s (dominated by the Gemini call). Retraining: 12 s (explicit `POST /models/train` only).
185 deterministic requests (views plus scenarios) caused **0** model calls; 10 copilot questions caused no retraining and used at most 3 tool calls each (limit 8).
These are single-machine, single-user measurements. **No load test was run** and nothing here says anything about concurrency or production capacity.

Outliers exist: one Tier-1 classification call took 37 s in the held-out run (an upstream delay), which is why every call has a timeout and the structured answer is returned if it fails.

## What keeps it cheap

- Forecasts are **persisted**; questions read `inv_forecast` / `inv_assessment`. The model is never retrained per question (`models/train` is a separate, manual operation).
- The prompt carries compact facts (top rows and totals, not 90-day arrays); at most 7,000 characters; output capped at 600 tokens and asked to stay under 110 words.
- Tier 0 rules route almost every business question without a model; Tier 1 is used only for the rest.
- No retries on grounding failure: a rejected explanation is replaced by the free deterministic headline instead of a second call.
- Thinking tokens are off (`AI_THINKING_BUDGET=0`) as in Phase 1.

A comparison with "ask the LLM to do the calculations" is not measured here and is not claimed; the structural point is that those calculations would need the raw series in the
prompt and could not be validated, which the design avoids.
