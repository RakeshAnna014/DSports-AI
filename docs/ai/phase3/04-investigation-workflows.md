# 4. Investigation workflows

Code: `ai-service/app/support/` (`classify.py`, `investigate.py`, `resolve.py`, `respond.py`, `flow.py`).

## Stages

`classify` (rules, then the Tier-1 model, validated against the `Complaint` enum) → `identify` the order and product from the customer's own orders (ask with tappable,
server-owned choices when ambiguous; a correction such as "It was the running shoes" is accepted) → `investigate` (READ tools run in parallel with a per-request budget and
timeouts; a failed lookup becomes "could not verify") → `decide` (pure function) → `respond` → optional confirmed case creation.

## Three kinds of statement, never mixed

| Kind | Rule |
|---|---|
| **Verified fact** | read from a DSports API in this turn (order status, shipment status and dates, payment attempts and results, refund state, return state) |
| **Inference** | deterministic reading of the facts ("shipment shows no movement for 5 days, the stall threshold is 3"). Presented as a possibility |
| **Missing** | what could not be established (no proof of delivery in DSports, photos cannot be assessed, carrier unreachable) |

The customer sees at most 10 facts; the rest stay in the case.

## Complaint types and what is checked

| Scenario | Type(s) | Checks |
|---|---|---|
| A missing delivery | not arrived, delayed, delivered-not-received | order and shipment status, tracking events, ETA, stall days (`AI_SUPPORT_STALL_DAYS`, default 3), delivered flag, address snapshot, delivery-problem procedure |
| B payment | payment pending/failed, charged twice, charged but order cancelled | payments for the order, attempts vs captured, unsettled payments, other orders adopted when the customer did not name one |
| C refund | refund delayed/not received | refund records, status and age, return state, refund procedure |
| D product | damaged, defective, wrong item/size, not as described | order contents, delivery state, return eligibility and existing returns, damaged-product procedure; no image assessment |
| E return/exchange | return status/rejected, exchange | return records, eligibility; exchange is not a DSports capability and is said so |
| F general | vague, wants a person, policy question | asks for the missing detail; a hypothetical policy question is answered from policy without opening a case |

A "Where is my order?" status question stays a Phase 1 answer; only a complaint enters this workflow.

## Conversation context

`conv.slots["support"]` keeps the stage: `awaiting_order`, `awaiting_product`, `awaiting_details`, `investigated`, `case_proposed`, `case_open`, `awaiting_case_message`.
"What happened to my complaint?" resolves to the customer's latest unresolved case (or asks which, with choices) and reads the live case, never the conversation memory.
