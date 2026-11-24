# 18. Topic guardrails

Decision record: `docs/adr/ADR-020-assistant-topic-guardrails.md`. Code: `ai-service/app/guard/topic.py` (topics and role policy), `topic_rules.py` (free rules), `topic_label.py` (label call), `topic_gate.py` (orchestration), `topic_templates.py` (fixed replies and chips), `topic_metrics.py`; `app/agent/cricket.py` and `app/agent/data/cricket_glossary.json`; the node `topic_gate` in `app/agent/graph.py`.

## What the assistant answers

| Role | SHOP | BOOKING | CRICKET | BUSINESS | OFF_TOPIC |
|---|---|---|---|---|---|
| Guest | yes | yes | yes | refused | refused |
| Customer | yes | yes | yes | refused | refused |
| Admin | yes | yes | yes | pointer to the staff AI workspace | refused |

* SHOP: products, gear advice, stock availability of a product ("is X in stock"), the customer's own orders, returns, refunds.
* BOOKING: venues, courts, slots, own bookings. The assistant has no booking tools yet: it replies with fixed text and links (Courts and venues, My bookings).
* CRICKET: rules and definitions (answered from the reviewed glossary), own matches/scores/stats/teams (fixed pointer to the pages), coaching (glossary entry if one matches, otherwise "coming soon"). Trivia, results and news are OFF_TOPIC.
* BUSINESS: sales, revenue, margins, warehouse stock numbers, forecasts, suppliers, other customers' data, internal operations.

## Flow of a message

1. Security guard (existing): injection, credential/prompt extraction, cross-customer, staff actions. Fixed refusal with chips.
2. Topic gate: free rules; if unsure and no live context, one label-only call; the role policy decides; refusals, pointers and glossary answers are fixed text (no model).
3. Only an allowed, in-scope message continues to Tier 0 / Tier 1 routing and the normal tools. A mixed message continues with only its in-scope part.

## Configuration, metrics, operations

* `AI_TOPIC_GATE` (default true) turns the gate off.
* Metrics: `ai_topic_decisions_total{audience,stage,decision}`, `ai_topic_refusals_total{topic,audience}`, `ai_topic_label_calls_total{result}`, `ai_topic_tokens_saved_estimate_total{audience}` (an estimate, see ADR-020). Audience is guest, customer or admin.
* To add a glossary entry: edit `cricket_glossary.json` (id, title, lower-case terms, a short answer, source). `tests/unit/test_cricket_glossary.py` checks the shape, that no term is in two entries and that answers make no DSports, price or person claims.

## Evaluation

Datasets: `ai-service/eval/topic/dev.yaml` (173 messages, tuned against) and `holdout.yaml` (84 messages, written after the rules were frozen, never tuned against). English, Hinglish, a few Devanagari and Telugu-script messages, tricky in-scope messages ("is this bat good for a leather ball", "what is LBW"), attacks, mixed messages. Run:

```
cd ai-service
uv run python eval/topic/run_topic_eval.py --cases eval/topic/dev.yaml
uv run python eval/topic/run_topic_eval.py --cases eval/topic/holdout.yaml --out eval/topic/results   # once per code state
uv run python eval/topic/run_topic_eval.py --cases eval/topic/holdout.yaml --live                      # asks the real label model about the unsure messages (needs Vertex credentials)
```

### Results

| Set | Run | Rules decide | Wrong decisions | In-scope wrongly refused | Off-topic leaked | Business leaked to customer |
|---|---|---|---|---|---|---|
| dev | free rules | 142 of 154 plain messages | 0 | 0 of 92 | 0 (9 unsure) | 0 |
| held-out, first run (`eval/topic/results/holdout-rules-20261124-045318.json`) | free rules | 54 of 78 plain messages | 0 | 0 of 44 (Wilson 95% upper bound 8.0%) | 0 (15 of 24 unsure) | 0 (3 of 10 unsure) |

What this does and does not show: the free rules made no wrong decision on either set, but they generalise less well than they fit (92% decided on dev, 69% on held-out). The undecided messages go to the label call; its accuracy on them has **not** been measured live. Worst case (every unsure message labelled wrongly), the held-out run would refuse 6 of 44 in-scope messages and let 15 of 24 off-topic ones reach the normal pipeline (where Tier 1 and the existing grounding checks still apply). A live run (`--live`) is needed to replace that worst case with a measured number. The rules were not changed after the held-out run.

Attacks (8 dev, 3 held-out) were all stopped before any model call, by the security guard or the topic rules.

### Tests

`tests/unit/test_topic_policy.py` (role x topic matrix), `test_topic_rules.py` (dev set, never-split queries, deny rules beat context, gear-vs-cricket regressions), `test_topic_gate.py` (label call shape, cache, invalid output, model down), `test_topic_gate_agent.py` (end to end: call counts, chips, mixed, roles, the explainer invariant, the off switch), `test_cricket_glossary.py`, `test_topic_metrics.py`, `test_topic_eval_runner.py`.
