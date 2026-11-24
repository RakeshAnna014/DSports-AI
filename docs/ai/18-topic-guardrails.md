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

| held-out, same run with the real label model (`holdout-live-20261124-050001.json`) | rules + live label call (`gemini-2.5-flash-lite`) | 54 by rules, 24 by the label call | 2 (both label calls) | 1 of 44 (`HC01` "what does retired hurt mean", labelled OFF_TOPIC; 2.3%, Wilson 95% upper bound 11.8%) | 1 (`HO13` "what is Virat Kohli's highest score", labelled CRICKET) | 0 |

Label call, live, on the 24 messages the rules left unsure: 22 of 24 labelled correctly (92%), all 24 returned a usable label, average 151.8 input tokens and 3.0 output tokens, average latency 1,021 ms (maximum 4,546 ms). The one leaked off-topic message was labelled CRICKET, so it is answered with the fixed "not in my glossary" reply and never reaches a model; the one wrongly refused in-scope message got the fixed refusal. The rules were not changed after this run.

### The owner's 25 messages (added to the held-out set after the first runs, `holdout-rules-20261124-054734.json` and `holdout-live-20261124-054805.json`)

Written and labelled by the owner (customer and admin expectation per message); no rule was changed before this run. Whole held-out set is now 109 messages.

| | Customer as expected | Admin as expected | Notes |
|---|---|---|---|
| rules only | 20 of 25 (4 unsure) | 20 of 25 | miss: `OW11` "is java a bat brand you sell" refused by the code deny rule (the word java); unsure: `OW14`, `OW17`, `OW19`, `OW20` |
| rules + live label | 24 of 25 | 24 of 25 | the only miss is `OW11`; the label call fixed `OW14` (CRICKET), `OW17`, `OW19`, `OW20` (OFF_TOPIC) |

Whole 109-message set, live: label call 26 of 28 on the unsure messages (avg 151.8 input / 3.1 output tokens, avg 1,000 ms, max 5,253 ms); 2 of 59 in-scope messages refused (`HC01` "what does retired hurt mean", `OW11`; 3.4%), 1 off-topic message allowed (`HO13`, labelled CRICKET and answered by a fixed reply), 0 business messages leaked to a customer. `OW12` ("what is a free hit") is identical to a dev message (`C09`); it is kept as labelled and named in the guard test.

### Changes made after these runs (not measured on unseen data)

* A message containing a distinctive glossary term is CRICKET by rule (commit `7deb577`). This was written for `HC01`, so a rules-only re-run of the held-out set after it (`holdout-rules-20261124-055647.json`) is not an unbiased measurement for that message: held-out rules-only now decides 76 of 103 plain messages, 1 in-scope refused (`OW11`), 0 leaked, 18 off-topic still unsure. Generic glossary words (over, wide, duck, average, bumper, run out, follow on) still need the word cricket and a rules question.
* A failed label call now replies "The assistant is temporarily unavailable" with a Try again chip (status `ai_unavailable`); an invalid label keeps "please rephrase".
* The glossary gained "retired hurt / retired out" and lost its law numbers (they were written from memory and are unreliable).
* "java" is a bat brand as well as a language: the code rule no longer refuses it when a gear or brand word is next to it ("is java a bat brand you sell" is shopping, which was the `OW11` miss); "what is java" and "explain java streams" stay refused. This was written for `OW11`, so the held-out set is no longer unbiased for that message. The stored result files were not edited and still show the miss.

## Refused-message log

Every OFF_TOPIC refusal and every BUSINESS refusal (a non-admin asking a business question) is written to `topic_refusals` in the AI database. A row has four fields: topic, role of the asker (GUEST, CUSTOMER or ADMIN), the message text and the time. There is no customer id or hash, no conversation id and no correlation id, so a row cannot be tied to a person. The text has e-mail addresses, tokens, ids and any run of six or more digits (phone and card numbers) removed and is cut to 300 characters before it is stored. "Please rephrase" replies and "temporarily unavailable" replies are not refusals and are not stored. A failed write is logged and never changes the reply. Without a database the log keeps the latest 500 entries in memory.

Staff read it at `GET /v1/topic/refusals` (ADMIN only, checked on the server before anything else; filters `topic`, `role`, `since`, `limit` up to 1000; `format=csv` for a file whose cells that start with `=`, `+`, `-` or `@` are defused against spreadsheet formulas) and on the page Admin > Refused messages. A retention period for these rows is not set yet; it belongs with the other retention jobs (planned for the launch-readiness gate).

What this does and does not show: the free rules made no wrong decision on either set, but they generalise less well than they fit (92% decided on dev, 69% on held-out). The undecided messages go to the label call; its accuracy on them has **not** been measured live. Worst case (every unsure message labelled wrongly), the held-out run would refuse 6 of 44 in-scope messages and let 15 of 24 off-topic ones reach the normal pipeline (where Tier 1 and the existing grounding checks still apply). The live run above replaced that worst case with a measured number. The rules were not changed after either run.

Attacks (8 dev, 3 held-out) were all stopped before any model call, by the security guard or the topic rules.

### Tests

`tests/unit/test_topic_policy.py` (role x topic matrix), `test_topic_rules.py` (dev set, never-split queries, deny rules beat context, gear-vs-cricket regressions), `test_topic_gate.py` (label call shape, cache, invalid output, model down), `test_topic_gate_agent.py` (end to end: call counts, chips, mixed, roles, the explainer invariant, the off switch), `test_cricket_glossary.py`, `test_topic_metrics.py`, `test_topic_eval_runner.py`.
