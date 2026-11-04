# 7. Resolution and escalation

`ai-service/app/support/resolve.py::decide` is a pure function of the investigation. No model is involved. Its output (an `Outcome`) is:

| Outcome | Meaning | What the customer gets |
|---|---|---|
| `ANSWER` | the facts answer it (e.g. in transit, within the expected window) | status and next check; no case unless asked |
| `ASK` | a detail is missing | one concrete question |
| `SAFE_STEP` | a customer action is available (e.g. start a return they are eligible for) | the step, nothing executed |
| `CREATE_CASE` | a person is needed or facts need recording | a confirmed case with the evidence |
| `ESCALATE` | rules say a person must look at it (safety, high value, repeated contact, customer asks) | the same confirmed case plus an escalation **request** |
| `UNVERIFIABLE` | a critical lookup failed | says so, offers a case; no conclusion drawn |

## Rule examples (full table in code and tests)

- Shipment delivered but customer says not received → missing proof of delivery (DSports has none) → case; priority up.
- Delivery stalled for at least the configured days, or ETA passed → case with the stall facts.
- Several payment attempts, one captured, order still unpaid or cancelled → case "payment captured but order not confirmed"; a confirmed duplicate capture (not possible in this schema; covered in unit tests only) → ESCALATE.
- Refund exists and is pending: state it; no promise of a date. No refund record for a cancelled paid order → case.
- Return rejected → no overturn offered; case so staff can review.
- Damaged/defective/wrong item → return path if eligible, plus a case with the description; photos are not assessed.
- Customer states danger or injury → ESCALATE at top priority, regardless of any other rule.
- Disputes ("that's wrong", "you're lying") and "I want a human" → ESCALATE request; the assistant says it cannot connect a live agent.

## Priority and the human step

Priority comes from the Java `PriorityPolicy` (doc 03). An escalation **recommendation** is what the assistant says; an **accepted** case is one a support executive has taken. The UI and the replies
keep these distinct ("A person has been asked to review this; nobody has taken it yet").

## What the assistant never does

Never decides or promises a refund, replacement, compensation, timeline or SLA; never states a case is resolved unless the backend says `RESOLVED`; never claims a person is working on it unless `ACCEPTED`.
