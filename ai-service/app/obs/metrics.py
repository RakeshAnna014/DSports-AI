"""Prometheus metrics. Labels are low-cardinality and never contain customer data."""
from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram, generate_latest

REGISTRY = CollectorRegistry()

REQUESTS = Counter("ai_requests_total", "Assistant requests", ["intent", "tier", "outcome"], registry=REGISTRY)
LLM_TOKENS = Counter("ai_llm_tokens_total", "Model tokens", ["model", "direction"], registry=REGISTRY)
LLM_LATENCY = Histogram("ai_llm_latency_seconds", "Model call latency", ["model", "operation"], registry=REGISTRY,
                        buckets=(0.25, 0.5, 1, 2, 4, 8, 16, 32))
TOOL_LATENCY = Histogram("ai_tool_latency_seconds", "Tool call latency", ["tool", "status"], registry=REGISTRY,
                         buckets=(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10))
RAG_QUERIES = Counter("ai_rag_queries_total", "Knowledge retrievals", ["result"], registry=REGISTRY)  # hit | miss
RAG_HIT_RATIO = Gauge("ai_rag_hit_ratio", "Share of knowledge retrievals that returned at least one chunk",
                      registry=REGISTRY)
RETRIEVAL_LATENCY = Histogram("ai_retrieval_latency_seconds", "Hybrid retrieval latency", ["kind"], registry=REGISTRY,
                              buckets=(0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5))
COST_USD = Counter("ai_cost_usd_total", "ESTIMATED model cost in USD (from AI_PRICE_TABLE_JSON)", ["model"],
                   registry=REGISTRY)
GUARD_BLOCKS = Counter("ai_guard_blocks_total", "Requests or outputs stopped by a guardrail", ["stage", "category"],
                       registry=REGISTRY)
GROUNDING_FALLBACKS = Counter("ai_grounding_fallbacks_total", "Answers replaced because a claim was not grounded",
                              registry=REGISTRY)
RECO_REQUESTS = Counter("ai_reco_requests_total", "Recommendation pipeline runs", ["scenario", "outcome", "band"], registry=REGISTRY)  # outcome: results | empty | error
RECO_LATENCY = Histogram("ai_reco_latency_seconds", "Recommendation pipeline latency", ["scenario"], registry=REGISTRY,
                         buckets=(0.05, 0.1, 0.25, 0.5, 1, 2, 4, 8))
RECO_CANDIDATES = Histogram("ai_reco_candidates_evaluated", "Candidates evaluated per run", ["scenario"], registry=REGISTRY,
                            buckets=(0, 1, 2, 5, 10, 20, 40, 80))
BREAKER_STATE = Gauge("ai_llm_breaker_open", "1 while the LLM circuit breaker is open", registry=REGISTRY)

_rag = {"hit": 0, "miss": 0}


def record_rag(hit: bool) -> None:
    key = "hit" if hit else "miss"
    _rag[key] += 1
    RAG_QUERIES.labels(key).inc()
    RAG_HIT_RATIO.set(_rag["hit"] / (_rag["hit"] + _rag["miss"]))


def render() -> bytes:
    return generate_latest(REGISTRY)

# --- Inventory Intelligence (Phase 2): model execution, scenarios, held-out accuracy (labels are low-cardinality; no product or user data)
INV_TRAIN_SECONDS = Histogram("inventory_train_duration_seconds", "Duration of a forecast training run", registry=REGISTRY,
                              buckets=(2, 5, 10, 20, 40, 80, 160))
INV_SCENARIOS = Counter("inventory_scenarios_total", "Promotion what-if calculations", ["source"], registry=REGISTRY)
INV_HOLDOUT_WAPE = Gauge("inventory_model_holdout_wape", "Held-out daily WAPE of the current forecast model", ["model"], registry=REGISTRY)

# --- Topic gate (docs/ai/topic-guardrails.md). Labels are low-cardinality: topic, audience (guest|customer|admin), stage, decision. No message text, no customer data.
TOPIC_DECISIONS = Counter("ai_topic_decisions_total", "Topic gate decisions", ["audience", "stage", "decision"], registry=REGISTRY)   # decision: allowed | refused | unsure | mixed
TOPIC_REFUSALS = Counter("ai_topic_refusals_total", "Messages the topic gate refused, by topic and role", ["topic", "audience"], registry=REGISTRY)
TOPIC_LABEL_CALLS = Counter("ai_topic_label_calls_total", "Label-only model calls made by the topic gate", ["result"], registry=REGISTRY)           # model | cache | invalid | error
TOPIC_TOKENS_SAVED = Counter("ai_topic_tokens_saved_estimate_total", "ESTIMATED model tokens not spent because the topic gate answered with a fixed reply "
                             "(the classifier's average measured usage per avoided call, minus the label call's own tokens)", ["audience"], registry=REGISTRY)
