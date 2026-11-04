# 03. Demand forecasting

No language model is involved in this document's subject. Code: `app/inventory/forecasting.py`; pipeline: `app/inventory/pipeline.py`.

## Series and cleaning

The target is **baseline demand**: daily units per product (all regions) at the regular price, outside event windows, not stock-censored.
Days that are not baseline are *imputed*, not deleted, with the median of the same weekday over the previous 8 clean weeks:

| Day type | Why it is not baseline |
|---|---|
| stock-out (>= 20% of regional demand censored) | sales on such a day show supply, not demand |
| promotion and the 7 days after | modelled by the scenario engine; the dip is a separate effect |
| event window | modelled by the event-impact model |
| missing region-day | rebuilt from observed regions scaled by their share, then flagged |
| negative sales (returns or corrections booked as negative units) | treated as an anomaly: missing and unusable, counted in the data-quality report, never used as demand |

Counts are reported in the data-quality report; nothing is cleaned silently.

## Candidate models (numpy only)

| Model | Description |
|---|---|
| `moving_average_28` | flat mean of the last 28 days: the baseline to beat |
| `seasonal_naive_7` | mean of each weekday over the last 4 weeks |
| `holt_winters` | damped additive trend and additive weekly seasonality; smoothing parameters by grid search on one-step error |
| `ridge_regression` | `log1p(units)` on trend, weekday and (with >= 400 days) annual Fourier terms; closed-form ridge; back-transformed with variance correction |

Technology is only used where it earns its place: ARIMA, Prophet and gradient boosting were not added. On this data the regression beat
the baselines on a held-out period (below); more libraries would add weight without evidence. `predict(model, ...)` is the single interface,
so a stronger model can be added and compared the same way.

## Evaluation design (no leakage)

- **Selection origins**: 90-day forecasts made 330, 300, 270, 240, 210 and 180 days before the end of history. They choose the model (lowest pooled WAPE)
  and calibrate the intervals. Each fold's test window ends at or before the start of the held-out window (`origin - 90 >= 90`), enforced by an assertion at import time
  and a unit test.
- **Held-out origin**: 90 days before the end (it forecasts the final 90 days). Never used to choose or calibrate. The reported accuracy comes from here.
- **Correction (2026-10-09 audit):** the first version used origins 210/180/150/120. The 120-day fold's test window (days -120 to -30) overlapped the held-out window
  (-90 to 0), so model selection and interval calibration had seen actuals from the period later reported as unseen. The claim "never used" was therefore not strictly true.
  The origins were moved; the held-out accuracy did not change (a held-out fit never depended on selection) and the model chosen is the same, but the selection figures and the
  interval coverage changed slightly (below). The leakage is now impossible by construction.
- Metrics are computed on **unmasked days only**: a masked day has no clean ground truth. Reported: WAPE, MAE, RMSE, MAPE (non-zero days), bias,
  and the WAPE of 7, 30 and 90-day **sums** (what replenishment actually consumes; daily error is much larger than the error of a sum).

## Results (held-out period, 134 SKUs, synthetic data)

| Model | Daily WAPE | MAE | RMSE | MAPE | Bias | WAPE of 7-day sums | 30-day | 90-day |
|---|---|---|---|---|---|---|---|---|
| **ridge_regression (selected)** | **38.6%** | 1.68 | 2.45 | 50.9% | -1.1% | **15.1%** | **9.5%** | **7.4%** |
| holt_winters | 41.9% | 1.82 | 2.70 | 55.2% | 0.0% | 16.8% | 13.2% | 15.4% |
| moving_average_28 (baseline) | 43.1% | 1.88 | 2.82 | 56.9% | +3.2% | 17.4% | 15.4% | 17.2% |
| seasonal_naive_7 | 45.8% | 1.99 | 3.03 | 60.2% | +3.4% | 17.4% | 15.8% | 17.3% |

On the (leak-free) selection origins the same ranking holds (ridge 38.0% daily WAPE vs 42.3% baseline; WAPE of 90-day sums 9.5% vs 17.1%). The selected model improves daily WAPE by
**10.5%** relative to the baseline and the WAPE of 90-day totals from 17.2% to 7.4%.

How to read it honestly:

- Daily WAPE of about 39% is mostly irreducible noise of low-volume count data (many SKU-region-days sell 0 or 1 unit), not poor modelling; the totals that planning uses are far more accurate.
- This is accuracy **on synthetic data generated with exactly the structure the models can represent** (weekly pattern, trend, annual season). On real demand it will be worse, and event/promotion timing would matter more. The numbers show the pipeline works and the evaluation is sound; they are not a promise.
- Every SKU is forecast with the globally selected model; there is no per-SKU model tuning (it would overfit three or four origins).

## Intervals

The 80% interval of a horizon sum is `forecast_sum x (1 + q10..q90 of the relative error)`, with the quantiles measured per volume bucket
(low < 1 unit/day, mid, high) on the selection origins. Coverage is **measured on the held-out origin, not assumed**:

| Horizon | Nominal | Measured on held-out data |
|---|---|---|
| 7 days | 80% | 86.3% |
| 30 days | 80% | **75.4%** (slightly narrow) |
| 90 days | 80% | 85.0% |

The daily band on charts is `mean +/- 1.28 x` the one-day error measured in the backtests.

## What is stored (every forecast is auditable)

`inv_model_run`: model name and version, training start/end, horizon, number of series, features, per-model selection and held-out metrics,
interval coverage, duration, `as_of`, data source, `is_current`. `inv_forecast`: per product the 90 daily means and bounds, one-day sigma, history length,
the run id (so older runs stay comparable), model name and version, training window, status (`OK` / `INSUFFICIENT_DATA`) and reason.
Older runs are kept; the newest is flagged current. Training twice on identical data gives identical forecasts (tested).

## Insufficient data

A SKU with fewer than 56 clean days (or fewer than 120 days of history) gets status `INSUFFICIENT_DATA`, **no forecast numbers**, and the message
"Insufficient data to produce a reliable forecast." Its assessment has no risk, no order quantity and no cover. The UI and the copilot show that message.

## Retraining

Manual by design for the POC: `POST /v1/inventory/models/train` (ADMIN, one run at a time, about 20 s, CPU work off the event loop) or
`python -m app.inventory.cli train`. Forecast questions never train a model; they read the persisted run. A scheduler can call the same function later.

## Latency

Full training run: about 12 s for 134 SKUs on a laptop (measured; backtests dominate). Reading a persisted forecast: 16 to 61 ms measured over 127.0.0.1 (see [12](12-cost-optimization.md)).
