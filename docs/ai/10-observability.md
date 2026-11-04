# 10 — Observability and cost

## Per request

Every assistant response carries `meta`: `requestId`, `correlationId`, the tools called (name, status, latency), `llmCalls`, tokens
in/out, models used, `latencyMs`, `costEstimateUsd`, grounding result (`checked`, `violations`, `fallback`), retrieval details
(`k`, latency, embedding latency, candidate/ranked names, cited sources) and the effective slots. The same facts are logged as one
structured JSON line (`assistant turn`) with the **customer hash** (never the raw id), intent, tier, status, tools, tokens, latency,
cost, grounding fallback and error code, plus one `model usage` line per model call and one `tool call` line per tool call.
`X-Correlation-Id` is accepted (safe tokens only) or generated, echoed in the response, and **forwarded to DSports**, so one id links
the browser, the AI service logs and the Java logs. `X-Request-Id` is per request.

## Persisted

`dsports_ai.usage`: ts, correlation id, request id, conversation id, customer hash, operation (generate/embed), model, tier, intent,
tokens in/out, latency, estimated cost, currency, `cost_is_estimate`, outcome. `dsports_ai.audit` for state-changing actions.

## Metrics (`GET /metrics`, Prometheus text)

| Metric | Labels |
|---|---|
| `ai_requests_total` | intent, tier, outcome |
| `ai_llm_tokens_total` | model, direction |
| `ai_llm_latency_seconds` | model, operation |
| `ai_tool_latency_seconds` | tool, status |
| `ai_retrieval_latency_seconds` | kind |
| `ai_rag_queries_total`, `ai_rag_hit_ratio` | result / – |
| `ai_cost_usd_total` | model (**estimate**) |
| `ai_guard_blocks_total` | stage, category |
| `ai_grounding_fallbacks_total` | – |
| `ai_llm_breaker_open` | – |

Labels are low-cardinality; there are no customer, order or product-instance labels (tested).

## Cost

Cost is computed only from `AI_PRICE_TABLE_JSON` (USD per 1M tokens) and is always marked an estimate; with no table it is `null`.
The evaluation run used the paid-tier standard prices published at ai.google.dev (Gemini Developer API; checked 2026-10-07):
`gemini-2.5-flash` $0.30 in / $2.50 out, `gemini-2.5-flash-lite` $0.10 in / $0.40 out per 1M tokens. Vertex AI list prices may
differ; set the table from your own contract. Embedding calls are logged with latency but have no token count from the API and are not
priced.

## Health

`/health` (liveness, never touches dependencies) and `/health/dependencies` (database + index counts, DSports, Tika, LLM
configured/breaker state; the LLM is never called by health).

## What is not there

No distributed tracing (OpenTelemetry), no log shipping, no dashboards/alerts: Phase 1 exposes the signals; wiring them to a stack is
operations work. Logs go to stdout.
