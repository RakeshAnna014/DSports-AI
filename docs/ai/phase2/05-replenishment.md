# 05. Replenishment recommendations

Deterministic code: `planning.assess`, `planning.round_order`. **Recommendation only: no purchase order is ever created.**

## Method

```
service level          95%   (z = 1.645)
demand_LT              sum of forecast over the supplier lead time
sd_LT                  from the calibrated 80% interval of the lead-time sum  (fallback sigma_daily x sqrt(LT))
safety stock           ceil(1.645 x sd_LT)
reorder point          demand_LT + safety stock
cycle                  lead time + review period (7 days)
target level           demand over the cycle + safety stock
position               on hand + all open purchase orders
need                   target - position
order quantity         round up to the case pack, then lift to the MOQ (kept a multiple of the case pack); 0 if need <= 0
```

The brief's example is reproduced with a zero review period: on hand 50, demand over the lead time 84, safety stock 20, nothing incoming,
`84 + 20 - 50 = 54` units (`test_replenishment_matches_the_worked_example`).

## When to order

- **HIGH** risk: order today (`order_by = as_of`), and say so even if the order cannot prevent the first stock-out; the arrival date is `order_by + LT`.
- otherwise the **latest** date that avoids breaching safety stock: `as_of + max(0, breach_day - LT)`, where `breach_day` is the first day the projected stock falls to the safety stock.

## Priority and action

| Situation | Action | Priority |
|---|---|---|
| need > 0 | `ORDER` | the stock risk level |
| need <= 0 but an open order lands after the projected stock-out, risk HIGH/MEDIUM | `EXPEDITE_INCOMING` | the stock risk level |
| otherwise | none | none |

## Supplier data

Supplier, lead time, MOQ and case pack are POC reference data ([02](02-data-model.md)): sport to supplier with lead times of 6 to 24 days and
MOQs of 2 to 48. The internal policy document `supplier-lead-times-and-moq.md` is generated from the same data, so the document and the
calculation cannot disagree. A purchase order above INR 500,000 needs the Head of Procurement's approval (sample policy); the assistant
never raises or approves one.

## Where it is shown

Replenishment tab (priority, quantity, order-by and arrival dates, on hand, incoming, demand in lead time, safety stock, supplier), the
event "Replenish before the event" table, the copilot, and the Overview "Recommended actions". The UI states "no purchase order was created".

## Not done

No purchase-order creation, no supplier ordering, no budget optimisation across SKUs, no supplier price breaks: out of Phase 2 scope.
