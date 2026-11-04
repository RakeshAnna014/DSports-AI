# 06. Overstock, slow-moving stock, aging and clearance

Deterministic code: `planning.assess`, `planning.health_class`, `repository.clearance_candidates`, `scenarios.py`.

## Excess and coverage

```
excess units    = max(0, on hand + incoming - 90-day forecast)           (the brief: 1,250 in stock vs 400 forecast = 850 excess)
excess value    = excess units x purchase cost                           (POC reference cost)
coverage days   = on hand / average daily forecast
overstock       HIGH if coverage >= 180 days and excess > 0;  MEDIUM if >= 120 days
```

## Inventory age

Each SKU-region holds FIFO batches; **oldest age** is the age of the oldest units still on hand, **average age** is quantity-weighted. The SKU
uses the oldest age across regions (conservative). The Overstock tab shows the aging distribution over **all** SKUs (server-side buckets).

## Health classes

| Class | Rule (first match) |
|---|---|
| **Critical Aging** | oldest stock >= 120 days **and** coverage >= 150 days |
| **Slow Moving** | coverage >= 120 days, or (oldest >= 90 days and coverage >= 90), or (demand down >= 25% and coverage >= 90) |
| **Watch** | coverage >= 75 days, or oldest >= 75 days, or demand down >= 15% |
| **Healthy** | otherwise |

The demand trend compares the last 28 days with the 56 before (baseline series). Latest run: 77 Healthy, 33 Watch, 11 Slow Moving, 13 Critical
Aging; 45 SKUs older than 90 days; 17 overstocked.

## Clearance candidates

Products that are Slow Moving or Critical Aging **and** overstocked (HIGH/MEDIUM) **and** have excess units. For each, the scenario engine
([08](08-promotion-scenarios.md)) evaluates 0 to 50% discounts over 30 days and returns the discount with the best **net benefit** that keeps the unit margin above the 10% floor, with the
expected units sold. A candidate for which no discount is profitable above the floor is shown as "No profitable discount": that is reported, not hidden.

Why a write-down term exists: without it the engine saw only margin and carrying cost, and recommended *no* discount for a cricket kit bag with
909 units against a 203-unit forecast and stock 216 days old. Units that would otherwise stay unsold keep aging and later need a deeper markdown
or a write-off, so the net benefit includes `units cleared x cost x write-down rate(age)` (15% from 60 days, rising linearly to 40% at 240 days).
This rate is a **POC assumption**, printed with every scenario result.

## Authority

The AI recommends. The DSports pricing and promotion engine stays authoritative; executing a clearance needs approval, existing pricing rules and
an audit record there. Nothing in this module writes a price.

## Regional redistribution

`GET /regions?excess_only=true` analyses where the excess sits. If every region holds well over 60 days of cover (the demo's finding for excess stock),
no transfer can help and the answer says clearance is the lever. Otherwise surplus above 60 days is matched greedily to regions under 21 days
(sample warehouse policy), as a suggestion only.
