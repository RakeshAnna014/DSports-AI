# ADR-020: Topic guardrails for the AI assistant

Status: accepted (implemented in `ai-service/app/guard/topic*.py`, wired into the Customer Agent graph). Details and how to run the evaluation: `docs/ai/18-topic-guardrails.md`.

## Context

The assistant answered anything its classifier could route. Off-topic questions ("write CSS", "who won the IPL") each cost a Tier 1 classifier call, a few mis-routed ones could even reach retrieval and the explainer model, and nothing in code distinguished a customer from an admin for business questions. The owner asked for topic guardrails that save tokens and keep the assistant on DSports.

## Decision

1. **Five topics, one role policy in code.** SHOP, BOOKING, CRICKET, BUSINESS, OFF_TOPIC. Guests and customers get SHOP, BOOKING and CRICKET; admins also get BUSINESS; OFF_TOPIC is refused for everyone. The audience comes from the verified principal (`audience_of`), never from a prompt. Existing tool gates and the backend's ownership checks are unchanged.
2. **Step 1, free rules (zero tokens).** Order: the existing security guard, then DENY rules (always win, also against conversation context), BUSINESS rules, STRONG allow rules, otherwise UNSURE. Only a clause that hits a deny rule is split off; a message that hits none is never split or rewritten (so "a bat for a 12 year old and under 3000" stays one query). Weak words ("price", "size", "hero", "mat") never allow a message on their own.
3. **Step 2, a label-only call, only when unsure.** Cheapest model, ~100-token prompt, `max_output_tokens=5`, temperature 0, reply validated against the topic enum, context-free results cached. An invalid label or an unavailable model gives the fixed "please rephrase" reply with chips (no guess).
4. **Context only fills in "unsure".** A pending confirmation, an open support flow or products on screen turn an unsure message into allowed; they never override a deny or business rule.
5. **Every refusal is a fixed template with chips** (message chips and link chips); nothing from the message is echoed. Refusals from the security guard now carry chips too.
6. **A mixed message** answers the in-scope clause (passed on unchanged) and appends one fixed sentence declining the rest. A BUSINESS clause in a customer's message refuses the whole message.
7. **Booking and cricket have no assistant tools yet**, so they get fixed replies with links, and cricket rules and definitions come from a reviewed glossary (`app/agent/data/cricket_glossary.json`, word for word, no model). Rules and definitions are CRICKET; trivia, results and news are OFF_TOPIC; coaching is answered from the glossary when an entry matches, otherwise "coaching tips are coming soon". An admin's business question gets a fixed pointer to the staff AI workspace.
8. **Defence in depth.** A refused turn cannot call the explainer (`TopicBlocked` in `Runtime.ask`). `AI_TOPIC_GATE=false` switches the gate off.
9. **Metrics.** `ai_topic_decisions_total`, `ai_topic_refusals_total{topic,audience}`, `ai_topic_label_calls_total`, and `ai_topic_tokens_saved_estimate_total`, an ESTIMATE from the classifier's measured average usage minus the label call's own tokens. Labels carry no message text.

## Alternatives considered

* **Fold the label into the Tier 1 classifier** (one call). Rejected: every off-topic message would then pay the full classifier prompt, which is the cost being removed. Trade-off accepted: an unsure but in-scope message costs a ~150-token label call plus the normal classifier call.
* **Rely on the system prompt** ("only answer DSports questions"). Rejected: it costs tokens on every call, can be talked out of, and is not testable as policy.
* **Use the Tier 0 rules as allow evidence everywhere.** Rejected for the knowledge rules ("how to use ...") because they match plenty of off-topic questions; only the account, support and recommend families are reused (and only after deny and business rules).
* **Refuse when unsure.** Rejected (owner decision): more wrong refusals; the fixed "please rephrase" reply is used instead.

## Consequences and known limits

* The free rules decide about 92% of the dev set but only **69% of the first held-out run**; the rest need the label call, whose live accuracy has not been measured (see the evaluation section of `docs/ai/18-topic-guardrails.md`).
* Targets are measured, not guaranteed: 0 off-topic reaching the explainer holds by construction for every refused message and in the tests; whether the live label model labels an unsure off-topic message correctly is an open measurement.
* A sample of ~44 in-scope held-out messages cannot show "< 3% wrongly refused" with confidence: zero errors still allows a true rate of about 8% (Wilson 95% upper bound). More held-out messages (the owner's 25, then real traffic) are needed.
* The glossary is a summary of the standard laws; leagues differ, and every answer says so.
