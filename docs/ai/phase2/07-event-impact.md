# 07. Tournament and event impact

Code: `app/inventory/events.py`, `repository.event_impact`, `repository.regions`. No "tournament = +X%" constant exists anywhere.

## Event knowledge layer

`inv_event`: id, name, type (tournament, world cup, marathon, olympics), sport, **demand window** (run-up included), popularity (1.0 = typical),
regional weights, data source. The 15 events (past and upcoming) are **POC reference data**, every name contains "(POC)", and they are not a real
calendar. Upcoming in the demo: Premier Cricket Tournament 2026 (in 28 days), Football League Season (50), City Marathon (60), Open Tennis (75).

## The model: measure, transfer, label

1. **Measure.** For every *past* event and every product of its sport: `uplift = actual units in the window / baseline the model expected for those days`,
   over days with usable ground truth. Event days were excluded from the baseline's training data, so the baseline does not "know" the event. Per (event, category) the median
   across products is the uplift; the 10th to 90th percentile across products is its range.
2. **Transfer.** An upcoming event is estimated from past events of the same sport (preferring the same event type), per category, after normalising
   each past uplift by its popularity: `uplift_new = 1 + (uplift_hist_norm - 1) x popularity_new^0.6`.
3. **Fallbacks, always labelled in `basis`:** `HISTORICAL` (measured for this category at past events of the same sport) > `HISTORICAL_SPORT_LEVEL` (the category had too few products to measure: the sport-wide average) > `HISTORICAL_OTHER_SPORT` (same event type, other sports) > `POC_ASSUMPTION`
   (uplift 1.3, range 1.0 to 1.6, used only when nothing exists, e.g. the City Marathon's jerseys and wearables). A `POC_ASSUMPTION` is shown in a different colour and called "POC assumption", never "estimated".

The event-adjusted forecast is `baseline x uplift` inside the window and unchanged outside it.

## Result for the Premier Cricket Tournament 2026 (synthetic history)

| Category | Estimated uplift | Range | Evidence |
|---|---|---|---|
| Jersey | x2.70 | 2.29 to 2.72 | 1 past event, 3 products |
| Shoes | x2.21 | 1.94 to 2.47 | 1 past event |
| Helmet | x2.05 | 1.82 to 2.21 | **sport-wide average**: only one cricket helmet SKU, so the category itself could not be measured (`HISTORICAL_SPORT_LEVEL`) |
| Ball | x1.93 | 1.62 to 2.20 | 1 past event |
| Kit Bag | x1.85 | 1.69 to 2.01 | other sports (not seen at a cricket event) |
| Bat | x1.84 | 1.73 to 1.88 | 1 past event |
| Gloves | x1.83 | 1.61 to 2.24 | 1 past event |
| Accessories | x1.76 | 1.73 to 1.77 | 1 past event |

Uncertainty is real: with one or two past events per sport the range reflects the spread across products, not event-to-event variation.
Marathon accessories came out x3.17 in the same way; its jerseys and wearables are POC assumptions.

## Readiness

For each product of the sport: on hand + incoming against the event-adjusted demand until the end of the window, giving the **projected
shortage**, the event-adjusted risk, and the recommended order and its date. The demo reports 16 of 19 cricket SKUs projected short
(12,403 units) before replenishment, 8 at high risk.

## Regional demand

`repository.regions` splits a product's forecast by its recent regional share. For an event, shares are multiplied by the event's regional
weights and renormalised, so the national total is preserved and the split changes. Region data is POC synthetic. Cover per region and
suggested transfers (surplus above 60 days to regions under 21 days) are suggestions only.

## Not done

No event ingestion from a real calendar, no event-to-event variance model, no cannibalisation between events, no weather or promotion
interaction. These need real history.
