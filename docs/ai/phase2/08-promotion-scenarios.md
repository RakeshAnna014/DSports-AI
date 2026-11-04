# 08. Promotion and clearance what-if

Deterministic code: `app/inventory/scenarios.py`. A calculation, not an action: **it never changes a price.** Every run is logged (`inv_scenario_run`).

## Demand response

```
promo demand = baseline demand over the window x (1 - d) ^ (-e)
```

`e` is the category's **price elasticity of demand** (positive magnitude here), **estimated** by regressing log(actual / baseline) on log(1 - d)
over historical promotion days (event and stock-censored days excluded), bootstrapped over products for a range. The estimate also yields the **pull-forward**
(share of the extra demand borrowed from the 7 days after a promotion). With fewer than 40 promotion days a category falls back to a flagged POC prior
(e = 1.5, range 1.0 to 2.2) and the output says so.

| Category | Estimated e | Range | Basis | Injected in the synthetic data |
|---|---|---|---|---|
| Accessories | 2.11 | 2.00 to 2.19 | measured (1,445 promo days) | 2.6 |
| Jersey | 2.05 | 1.86 to 2.22 | measured | 2.4 |
| Ball | 1.67 | 1.59 to 1.78 | measured | 2.0 |
| Gloves | 1.72 | 1.39 to 2.01 | measured | 1.7 |
| Bat | 1.45 | 1.26 to 1.67 | measured | 1.5 |
| Equipment | 1.39 | 1.24 to 1.57 | measured | 1.7 |
| Kit Bag | 1.37 | 1.12 to 1.65 | measured | 1.8 |
| Shoes | 1.06 | 0.93 to 1.17 | measured | 1.6 |
| Helmet | 0.98 | 0.96 to 1.01 | measured (61 days) | 1.3 |
| Bike, Wearables | 1.50 | 1.00 to 2.20 | **POC assumption** (too few promo days) | 1.1 / 1.2 |

Because the generator is known, this table is a **validation of the method**: the estimates have the right order and magnitude but are
**biased low** (3% to 35% under the injected values); the bootstrap ranges describe sampling error only and do not contain the truth for most
categories. The likely cause is that demand on promotion days is capped by stock and by the baseline's own fit. Treat elasticity as approximate; the scenario output carries the range.

## Scenario results (per product, then summed)

Units sold (capped by stock available inside the window, including deliveries landing in it), revenue `sold x price x (1-d)`, margin
`sold x (price x (1-d) - cost)`, ending stock, clearance days (`on hand / promo daily rate`), stock-out risk (promo demand vs stock: HIGH at >= 100%, MEDIUM at >= 80%),
the units range from the elasticity range, inventory reduction versus baseline (units and value at cost), and the lowest unit margin.

## Choosing: net benefit under a margin floor

```
net_benefit(d) = margin(d) - margin(0) + carrying_cost_avoided(d) + writedown_avoided(d)
carrying_cost_avoided = extra units x cost x 25% x min(baseline clearance days, 365) / 365
writedown_avoided     = min(extra units, excess units) x cost x rate(age)        rate: 15% at 60 days rising to 40% at 240+ days
```

A scenario is eligible only if **every product's unit margin stays at or above 10%** of the discounted price (sample clearance policy). The recommended
uniform discount is the eligible one with the best positive net benefit; if none, the result says **no single discount keeps every product above the floor**.
Alongside, the **selective plan** gives each product its own best eligible discount. Example (24 slow-moving products, 14 days): 30% sells 788 units (baseline 406) for
Rs 955,916 revenue and Rs 133,357 margin but breaks the floor for some product, 50% makes the margin negative, so the engine recommends no uniform discount and
shows a selective plan that discounts 13 of the 24 products for a net benefit of Rs 19,988.

## Assumptions printed with every result

Elasticity per category (measured or POC assumption), 25% annual holding cost, the write-down schedule, the 10% margin floor, 14-day default window,
purchase cost is POC reference data. Changing any of them is a code constant, not a prompt.

## Not done

No cross-product cannibalisation, no halo effects, no price-ending or competitor effects, no automatic promotion launch, no budget optimiser.
