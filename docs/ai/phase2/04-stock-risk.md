# 04. Stock-out risk

Deterministic code: `app/inventory/planning.py` (`assess`, `projected_cover`). The language model never calculates any of this.

## Inputs (per product, as of the model run)

On-hand stock (sum of regions), open purchase orders with their ETAs, supplier lead time (LT), the persisted 90-day baseline forecast,
the one-day forecast error sigma from the backtests, and the calibrated 80% interval of the horizon sum.

Time convention: the position is the stock at the start of `as_of`; forecast day 1 is `as_of`; an order with ETA `as_of + k` days is available from day `k + 1`.

## Days of cover

Walk the forecast day by day, adding arrivals; **days of cover** is the first day the projected stock reaches zero, fractional (stock left that
day divided by that day's demand). If it never reaches zero within 90 days the cover is "90+" (stored as null). Example from the brief:
50 units, 12 a day, nothing incoming: 50 / 12 = **4.2 days**.

## Probability of running out

The chance of running out **before replenishment could arrive**, taking the worst of these checkpoints: the lead time, and the arrival day of
every open order that lands inside the lead time. At each checkpoint,

```
P(stock-out) = P( demand until the checkpoint  >  stock on hand + orders that landed before it )
demand ~ Normal(forecast sum, sd)   where sd = (hi - lo) / (2 x 1.2816)  of the calibrated 80% interval for that horizon
```

(fallback if no interval: `sigma_daily x sqrt(days)`). Checking every arrival matters: stock can run out *before* a delivery lands even when
the delivery is large enough to cover the whole lead time.

## Risk level (documented thresholds)

| Level | Rule |
|---|---|
| **HIGH** | days of cover < supplier lead time, **or** P(stock-out) >= 50% |
| **MEDIUM** | days of cover < lead time + 7, **or** P(stock-out) >= 20% |
| **LOW** | otherwise |

Cover exactly equal to the lead time is a coin flip (P = 50%) and is therefore HIGH. A product with **no recorded lead time** gets status
`MISSING_LEAD_TIME`: risk from cover alone (HIGH below 14 days, MEDIUM below 30), no order quantity, and the reason says so.

## Open orders that arrive too late

If the first open order lands after the projected stock-out, the product is flagged `incoming_arrives_after_stockout` and, when the open
order already covers the target quantity, the action is **EXPEDITE_INCOMING** (not "order more"): re-ordering cannot help. Of the 13 HIGH-risk
SKUs in the demo data, most carry this flag.

## Worked example (the brief's)

Cricket bat: 50 on hand, 12 a day, LT 7, sigma 3: cover 4.2 days, risk HIGH, explanation "projected to fall below safety stock before the next
replenishment arrives", order recommended now (see [05](05-replenishment.md)). Reproduced by `tests/inventory/test_planning.py` and case R1 of the numeric evaluation.

## Event-adjusted risk

For an upcoming event the same function runs on the event-adjusted forecast (baseline x uplift inside the event window), stored under
scenario `EVENT:<id>` ([07](07-event-impact.md)).

## Where it is shown

Stock Risk tab (ranking, coverage bars, click a product for the forecast chart and a "Why this rating" list built from the same numbers), the
Overview "Top stock-out risks", and the copilot. Regional risk uses the regional demand share ([07](07-event-impact.md)).
