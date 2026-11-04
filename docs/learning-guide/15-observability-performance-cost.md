# 15. Observability, performance and cost

Only items traceable to code or to a recorded report are included. **Measured** = a number printed by a harness or script; **estimate** = computed from a configured price table or extrapolated; **UNKNOWN** = no evidence.

## 1. What is implemented

| Capability | Implementation | Status |
|---|---|---|
| Structured JSON logs | `JsonFormatter` (`ai-service/app/obs/logging.py:52-70`): timestamp, level, logger, message, `correlationId`, `requestId`, `conversationId`, extras, exception; redaction of sensitive keys and value patterns (lines 15-49) | IMPLEMENTED, TESTED (`tests/unit/test_observability.py`, 15) |
| Request correlation | `install_request_context` (`obs/middleware.py:16-39`) sets `X-Correlation-Id` (accepts `[A-Za-z0-9._-]{1,64}` or generates a UUID, `obs/context.py:13-19`), `X-Request-Id`; forwarded to Java (`DSportsClient`) where `%X{correlationId}` appears in every log line (`application.yml:90-92`) | IMPLEMENTED |
| Per-turn log line | `ChatService.chat` logs intent, tier, status, number of tools, `llm_calls`, tokens in/out, latency, grounded-fallback flag, error code, customer hash, cost estimate, route source (`service.py:172-177`) | IMPLEMENTED |
| Per-model-call usage | `UsageRecorder.record` (`obs/usage.py:58-84`): logs `ai.usage` and writes a `usage` row (model, tier, intent label, tokens, latency, outcome, estimated cost); persistence failure never breaks a request | IMPLEMENTED, TESTED |
| Prometheus metrics | `GET /metrics`, registry in `obs/metrics.py`: `ai_requests_total{intent,tier,outcome}`, `ai_llm_tokens_total{model,direction}`, `ai_llm_latency_seconds{model,operation}`, `ai_tool_latency_seconds{tool,status}`, `ai_rag_queries_total{result}`, `ai_rag_hit_ratio`, `ai_retrieval_latency_seconds{kind}`, `ai_cost_usd_total{model}`, `ai_guard_blocks_total{stage,category}`, `ai_grounding_fallbacks_total`, `ai_llm_breaker_open`, plus inventory `inventory_train_duration_seconds`, `inventory_scenarios_total`, `inventory_model_holdout_wape{model}` | IMPLEMENTED. No Prometheus server or Grafana dashboard definitions were found (`UNKNOWN`/NOT IMPLEMENTED) |
| Tracing | no OpenTelemetry/tracing dependency or code found | NOT IMPLEMENTED (correlation id only) |
| Response transparency | every `ChatResponse.meta`: tools used with status/latency, models, tokens, estimated cost, grounding report, retrieval details (`schemas.py:48-61`) | IMPLEMENTED |
| Health | `/health` (process only), `/health/dependencies` (DB, DSports `/actuator/health`, Tika, breaker state; LLM not called) (`routes.py:49-82`); Java Actuator `health,info,metrics` | IMPLEMENTED |
| Java business metrics | `BusinessMetricsListener`, `ObservabilityConfig`; error counters in `GlobalExceptionHandler` (MeterRegistry) | IMPLEMENTED (content not inspected in depth) |
| Error tracking service (Sentry etc.) | none found | NOT IMPLEMENTED |
| Cost estimation | `estimate_cost` from `AI_PRICE_TABLE_JSON` only; **no built-in prices**: a model without an entry gets `cost_estimate = null` (`usage.py:39-44`) | IMPLEMENTED |

## 2. Measured values from recorded runs

All on one developer machine, one user at a time, local network, sample/synthetic data; **no load test was ever run**, so nothing here supports a production-load claim.

**Phase 1 main evaluation** (`docs/ai/09-evaluation.md`, 83 cases, 114 turns, price table `gemini-2.5-flash` $0.30 in / $2.50 out and `gemini-2.5-flash-lite` $0.10 in / $0.40 out per 1M tokens, as configured on 2026-10-07):
| Measure | Value | Kind |
|---|---|---|
| Turns that used a model | 66 of 114 | measured |
| Tier distribution (turns) | T0 48, T1 4, T2 62 | measured |
| Mean tokens per model request | 1,175 in / 126.5 out | measured |
| Estimated cost of the run | US$0.039 (US$0.00034 per turn) | **estimate** (price table) |
| End-to-end latency p50 / p95 / max | 3.2 s / 5.2 s / 11.5 s | measured |
| Server-side latency p50 / p95 | 1.1 s / 3.1 s | measured |
| Tool latency p50 | `search_products` 131 ms, `get_orders` 14 ms, `track_order` 12 ms, `create_return` 14 ms | measured |

**Phase 2** (`docs/ai/phase2/14-final-stabilization-report.md` section 10, `e2e/ai/inventory_measure.py`): dashboard/data endpoints p50/p95 /overview 33/42 ms, /forecast 66 ms p50, /clearance 46/61 ms, /regions 102/122 ms, /events 32/35 ms (15 requests each); model calls made by 185 dashboard and scenario requests: **0**; copilot p50 1.6 s / p95 3.3 s (10 questions), dev suite p50 1.9 s / p95 7.2 s (56 turns); max 3 tool calls per answer observed (limit 8); training of 134 series 14.8 s; scenario 25 ms; 10 questions 17,619 in / 1,517 out tokens, **estimated** US$0.0091 (US$0.0009 per question); dev suite 76,101 / 7,400 tokens, estimated US$0.0413 (US$0.00074 per turn); log scan 0 findings. A latency artefact (IPv6 `localhost` resolution adding ~2 s) was found and fixed by using `127.0.0.1`.

**Phase 3 dev evaluation, final code** (`ai-service/eval/results/support-dev-final-code-20261013.json`, `metrics`): 45 cases / 51 turns; latency p50 **83 ms**, p95 **3,331 ms**; **5 LLM calls** in the whole run; 8,589 tokens in / 923 out; estimated cost **US$0.0044**. These small numbers reflect that the support workflow is rules plus tools and that the case text is deterministic; they are measured from the harness, with the price table configured at the time. Held-out regression on the final code: 8 LLM calls, 9,532 / 987 tokens, estimated US$0.0020.

**Not measured / UNKNOWN:** Java endpoint latency under load; vector retrieval latency at scale (the corpora have a few dozen chunks); memory/CPU of the AI service; Vertex cost on the real bill (only the estimate exists); cost of a full `validate-all.sh` run (not summed from one source).

## 3. Cost control summary
Doc 06 section 3 lists the implemented levers (Tier 0, cache, plain-search shortcut, token budget, thinking off, no model call for refusals/dashboard reads/case text). The model is billed pay-per-use by Google to the Cloud project that owns the credentials; check Billing -> Reports and set a budget alert there. The repository contains **no billing data**.

## 4. Troubleshooting guide

| Symptom | How to investigate | Likely causes in this code |
|---|---|---|
| **High LLM latency** | `ai_llm_latency_seconds` histogram or `ai.usage` log lines (`latency_ms`, `model`, `tier`, `intent`); `meta.latency_ms` vs `meta.tools[*].latency_ms` in the response | Gemini slowness (the Vertex client timeout is `AI_TIMEOUT_S` 30 s; timeouts show as `LLM_TIMEOUT` and trigger one retry); an embedding region far from the caller (`AI_EMBEDDING_LOCATION`); IPv6 `localhost` resolution (use `127.0.0.1`) |
| **High token consumption** | `ai_llm_tokens_total`, `usage` table grouped by `intent` and `tier`, `meta.tokens_*`; check `llm_calls` per turn | many Tier-1 calls (a rule is missing in Tier 0; the 1-hour cache does not cover context-dependent or identifying messages), long product lists, thinking enabled (`AI_THINKING_BUDGET` must be 0) |
| **Gemini quota failures** | `usage.outcome = 'LLM_QUOTA'`, `ai_llm_breaker_open`, `/health/dependencies` (`llm.circuit_breaker`), responses with `error_code AI_UNAVAILABLE` or unexpected `OUT_OF_SCOPE` intent | Vertex per-minute quota / billing state of the Google Cloud project; the breaker opens after 3 consecutive failures for 30 s, so errors continue briefly after quota returns; evaluation runs during a quota block are invalid (doc 14) |
| **Slow vector retrieval** | `ai_retrieval_latency_seconds{kind}`, `meta.retrieval` (`latency_ms`, `embedding_ms`, `candidates`) | embedding latency dominates (query embedding is cached per normalised text); HNSW/GIN indexes missing or the pgvector extension absent; large `AI_RETRIEVAL_CANDIDATES` |
| **Slow Java endpoints** | Java Actuator `/actuator/metrics`, log lines with `correlationId`; compare `meta.tools[*].latency_ms` for the same correlation id | N+1 patterns (e.g. `get_orders` fetches up to 8 order details), missing indexes, scheduled job contention |
| **Tool-call failures** | `ai_tool_latency_seconds{tool,status}` (status = `ok` or the error code), `meta.tools`, `tool call` log lines | `UPSTREAM_TIMEOUT`/`UPSTREAM_UNAVAILABLE` (Java down), `FORBIDDEN`/`NOT_FOUND` (ownership), `TOOL_BUDGET_EXCEEDED` (more than 8 calls), `INVALID_ARGUMENTS` |
| **Unexpected model routing** | `meta.route_source` (`rules|model|cache|fallback`), `meta.tier`, `ai_requests_total{intent,tier,outcome}` | a Tier-0 rule matched earlier than expected (rules are order-sensitive: `tier0.classify`), or a Tier-1 fallback after `LLM_*` errors |
| **Duplicate state-changing requests** | AI `audit` rows by `args_hash`/`conversation_id` (`PROPOSED`, `CONFIRMED`, `CONFIRM_REJECTED:REPLAY_OR_UNKNOWN`, `EXECUTED`); Java `support_case_events` and the `(user_id, idempotency_key)` unique constraints | double click (blocked by single-use token), client retry (idempotent replay returns `REPLAYED`/`ALREADY_OPEN`) |

## 5. Gaps
No dashboards/alerts, no tracing, no log shipping, no retention policy for `audit`/`usage`/`conversations` (doc 12), no load or soak test, and no recorded real-cost reconciliation.
