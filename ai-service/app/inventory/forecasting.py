"""Demand forecasting: classical statistical models, evaluated honestly. NO LLM is involved anywhere in this module.

Series: daily units per product, cleaned to BASELINE demand (regular price, no event, not stock-censored), because promotions and
events are modelled separately (scenarios.py, events.py) and a stock-out day shows sales, not demand.

Candidate models (all deterministic, numpy only):
  moving_average_28   flat mean of the last 28 days                              the naive baseline
  seasonal_naive_7    mean of the same weekday over the last 4 weeks               weekly pattern only
  holt_winters        damped additive trend + additive weekly seasonality, parameters by grid search on one-step SSE
  ridge_regression    log1p target on trend + weekday + annual Fourier terms, ridge closed form

Evaluation: rolling-origin backtests. Selection origins pick the model and calibrate the interval; ONE later origin is held out and
never used for either choice, and is what the reported accuracy comes from. Metrics: WAPE, MAE, RMSE, MAPE (non-zero days) and bias,
on unmasked days only (a masked day has no clean ground truth).

Intervals: relative-error quantiles of the horizon sum, measured in the backtests per volume bucket, applied to the forecast sum. The
coverage of the resulting 80% interval is MEASURED on the held-out origin and stored, not assumed.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date, timedelta

import numpy as np

MODEL_VERSION = "1.0.0"
MODEL_NAMES = ("moving_average_28", "seasonal_naive_7", "holt_winters", "ridge_regression")
HORIZON = 90
MIN_CLEAN_DAYS = 56
HOLDOUT_ORIGIN = 90                            # forecasts days [end-90, end): never used for selection or calibration
# Selection folds forecast HORIZON days from each origin. For the held-out window to be truly unseen, EVERY selection fold's test window must end
# at or before the start of the held-out window: origin - HORIZON >= HOLDOUT_ORIGIN (days before the end of history).
SELECTION_ORIGINS = (330, 300, 270, 240, 210, 180)
SUM_HORIZONS = (7, 30, 90)


def selection_windows_overlap_holdout(origins: tuple[int, ...] = SELECTION_ORIGINS, horizon: int = HORIZON, holdout: int = HOLDOUT_ORIGIN) -> list[int]:
    """Origins whose test window reaches into the held-out window (must be empty)."""
    return [o for o in origins if o - horizon < holdout]


assert not selection_windows_overlap_holdout(), "model selection must not look at the held-out period"
_LOW, _HIGH = 0.10, 0.90                        # the 80% interval


@dataclass
class Cleaned:
    y: np.ndarray                  # baseline demand, masked days imputed
    mask: np.ndarray               # True where the day was imputed (promotion, event, stock-out, missing)
    raw: np.ndarray                # total observed units with missing regions scaled up (NaN if all missing)
    unusable: np.ndarray = field(default_factory=lambda: np.zeros(0, dtype=bool))   # stock-censored or missing: no ground truth at all
    counts: dict[str, int] = field(default_factory=dict)


def clean_series(units: np.ndarray, stockout: np.ndarray, discount: np.ndarray, event_idx: np.ndarray, shares: np.ndarray) -> Cleaned:
    """units (R, D) with NaN for a missing region-day; stockout (R, D); discount (D,) percent; event_idx (D,) -1 = none; shares (R,)."""
    negative = np.nan_to_num(units, nan=0.0) < 0                              # negative sales (returns / corrections) are not demand: treated as missing
    units = np.where(negative, np.nan, units)
    present = ~np.isnan(units)
    covered = (present * shares[:, None]).sum(axis=0)                        # share of demand that was observed that day
    total = np.where(covered > 0, np.nansum(units, axis=0) / np.maximum(covered, 1e-9), np.nan)
    censored = ((stockout & present) * shares[:, None]).sum(axis=0) >= 0.20
    promo = discount > 0
    post_promo = np.zeros_like(promo)
    for k in range(1, 8):                                                     # the post-promotion dip is not baseline demand either
        post_promo[k:] |= promo[:-k]
    event = event_idx >= 0
    missing = np.isnan(total)
    mask = censored | promo | post_promo | event | missing
    counts = {"negative_values": int(negative.sum()), "stockout_days": int(censored.sum()), "promo_days": int((promo | post_promo).sum()), "event_days": int(event.sum()),
              "missing_days": int(missing.sum()), "imputed_days": int(mask.sum())}
    y = np.where(missing, 0.0, total).astype(float)
    return Cleaned(y=_impute(y, mask), mask=mask, raw=total, unusable=censored | missing, counts=counts)


def _impute(y: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """Replace masked days with the median of the same weekday over the previous 8 clean weeks (falls back to the clean mean)."""
    out = y.copy()
    clean = ~mask
    overall = float(y[clean].mean()) if clean.any() else 0.0
    for t in np.flatnonzero(mask):
        idx = [t - 7 * k for k in range(1, 9) if t - 7 * k >= 0 and clean[t - 7 * k]]
        if not idx:
            idx = [t + 7 * k for k in range(1, 9) if t + 7 * k < len(y) and clean[t + 7 * k]]
        out[t] = float(np.median(y[idx])) if idx else overall
    return out


# ------------------------------------------------------------------------------------------------------ models

def moving_average_28(y: np.ndarray, start: date, horizon: int) -> np.ndarray:
    return np.full(horizon, float(y[-28:].mean()))


def seasonal_naive_7(y: np.ndarray, start: date, horizon: int) -> np.ndarray:
    """Mean of each weekday over the last 4 weeks (indexed by t % 7, so the cycle continues exactly after the last observation)."""
    n = len(y)
    recent = np.arange(max(0, n - 28), n)
    pattern = np.array([float(y[recent[recent % 7 == k]].mean()) if (recent % 7 == k).any() else float(y.mean()) for k in range(7)])
    return np.maximum(pattern[(np.arange(n, n + horizon)) % 7], 0.0)


_HW_GRID = np.array([(a, b, g) for a in (0.05, 0.15, 0.3, 0.5) for b in (0.01, 0.05) for g in (0.05, 0.2)])
_PHI = 0.97


def holt_winters(y: np.ndarray, start: date, horizon: int) -> np.ndarray:
    n = len(y)
    if n < 28:
        return moving_average_28(y, start, horizon)
    a, b, g = _HW_GRID[:, 0], _HW_GRID[:, 1], _HW_GRID[:, 2]
    k = len(a)
    first = y[:14]
    level = np.full(k, first.mean())
    trend = np.zeros(k)
    dow_mean = np.array([y[w:14 * 4:7].mean() for w in range(7)]) if n >= 28 else np.zeros(7)
    seas = np.tile((dow_mean - dow_mean.mean())[:, None], (1, k))        # (7, k), index by t % 7
    sse = np.zeros(k)
    for t in range(n):
        s = seas[t % 7]
        pred = level + _PHI * trend + s
        err = y[t] - pred
        if t >= 7:
            sse += err * err
        new_level = level + _PHI * trend + a * err
        trend = _PHI * trend + b * a * err
        seas[t % 7] = s + g * (1 - a) * err
        level = new_level
    best = int(np.argmin(sse))
    # refit-free: the last state of the best parameter set is the forecast origin
    lv, tr = level[best], trend[best]
    out = np.empty(horizon)
    damp = 0.0
    for h in range(1, horizon + 1):
        damp += _PHI ** h
        out[h - 1] = lv + damp * tr + seas[(n + h - 1) % 7, best]
    return np.maximum(out, 0.0)


def _design(start: date, n: int, offset: int, total_scale: float, annual: bool) -> np.ndarray:
    t = np.arange(offset, offset + n)
    cols = [np.ones(n), t / total_scale]
    wd = np.array([(start + timedelta(days=int(i))).weekday() for i in t])
    cols += [(wd == w).astype(float) for w in range(1, 7)]
    if annual:
        doy = np.array([(start + timedelta(days=int(i))).timetuple().tm_yday for i in t], dtype=float)
        for k in (1, 2):
            cols += [np.sin(2 * np.pi * k * doy / 365.25), np.cos(2 * np.pi * k * doy / 365.25)]
    return np.column_stack(cols)


def ridge_regression(y: np.ndarray, start: date, horizon: int, lam: float = 1.0) -> np.ndarray:
    """`start` is the date of y[0]. Trend + weekday + (with >= 1 year of history) annual Fourier terms on log1p demand."""
    n = len(y)
    annual = n >= 400
    scale = 365.0
    x = _design(start, n, 0, scale, annual)
    target = np.log1p(y)
    penalty = lam * np.eye(x.shape[1])
    penalty[0, 0] = 0.0
    beta = np.linalg.solve(x.T @ x + penalty, x.T @ target)
    resid = target - x @ beta
    s2 = float(resid.var())
    xf = _design(start, horizon, n, scale, annual)
    return np.maximum(np.expm1(xf @ beta + 0.5 * s2), 0.0)


def ridge_insample(y: np.ndarray, start: date) -> np.ndarray:
    """The ridge model's own fitted baseline over the history (used to MEASURE event and promotion effects against the baseline)."""
    n = len(y)
    annual = n >= 400
    x = _design(start, n, 0, 365.0, annual)
    penalty = np.eye(x.shape[1])
    penalty[0, 0] = 0.0
    beta = np.linalg.solve(x.T @ x + penalty, x.T @ np.log1p(y))
    s2 = float((np.log1p(y) - x @ beta).var())
    return np.maximum(np.expm1(x @ beta + 0.5 * s2), 0.0)


_MODELS = {"moving_average_28": moving_average_28, "seasonal_naive_7": seasonal_naive_7, "holt_winters": holt_winters,
           "ridge_regression": ridge_regression}


def predict(model: str, y: np.ndarray, start: date, horizon: int = HORIZON) -> np.ndarray:
    return _MODELS[model](y, start, horizon)


# ------------------------------------------------------------------------------------------------------ evaluation

@dataclass
class Metrics:
    wape: float
    mae: float
    rmse: float
    mape: float | None
    bias: float
    n_days: int


def metrics(actual: np.ndarray, forecast: np.ndarray) -> Metrics:
    if actual.size == 0:
        return Metrics(float("nan"), float("nan"), float("nan"), None, float("nan"), 0)
    err = forecast - actual
    denom = float(np.abs(actual).sum())
    nz = actual > 0
    mape = float(np.mean(np.abs(err[nz]) / actual[nz])) if nz.any() else None
    return Metrics(float(np.abs(err).sum() / denom) if denom > 0 else float("nan"), float(np.abs(err).mean()),
                   float(math.sqrt(np.mean(err ** 2))), mape, float(err.sum() / denom) if denom > 0 else float("nan"), int(actual.size))


def _volume_bucket(daily_mean: float) -> str:
    return "low" if daily_mean < 1.0 else "mid" if daily_mean < 5.0 else "high"


@dataclass
class Backtest:
    """Pooled errors of every candidate model over a set of origins."""
    actual: dict[str, list[np.ndarray]] = field(default_factory=lambda: {m: [] for m in MODEL_NAMES})
    forecast: dict[str, list[np.ndarray]] = field(default_factory=lambda: {m: [] for m in MODEL_NAMES})
    sum_errors: dict[tuple[str, str, int], list[float]] = field(default_factory=dict)   # (model, bucket, h) -> relative errors
    sum_pairs: dict[tuple[str, int], list[tuple[float, float]]] = field(default_factory=dict)   # (model, h) -> (actual sum, forecast sum)
    sigma: dict[str, list[float]] = field(default_factory=dict)                         # model -> per-day absolute residuals (per series)


def run_backtest(series: dict[str, Cleaned], start: date, origins: tuple[int, ...], horizon: int = HORIZON,
                 models: tuple[str, ...] = MODEL_NAMES) -> tuple[Backtest, dict[str, dict[str, float]]]:
    """For every series and origin: fit on the data before the origin, forecast `horizon` days, compare with the unmasked actuals.
    Returns pooled errors and per-series per-model residual std (for the daily sigma)."""
    bt = Backtest()
    resid: dict[str, dict[str, list[float]]] = {pid: {m: [] for m in models} for pid in series}
    for pid, c in series.items():
        n = len(c.y)
        for o in origins:
            cut = n - o
            if cut < 120:
                continue
            truth_mask = ~c.mask[cut:cut + horizon]
            window = c.raw[cut:cut + horizon]
            for m in models:
                f = predict(m, c.y[:cut], start, min(horizon, n - cut))
                ok = truth_mask[:len(f)] & ~np.isnan(window[:len(f)])
                if not ok.any():
                    continue
                a, fo = window[:len(f)][ok], f[ok]
                bt.actual[m].append(a)
                bt.forecast[m].append(fo)
                resid[pid][m].extend((a - fo).tolist())
                for h in SUM_HORIZONS:
                    sel = ok.copy()
                    sel[h:] = False
                    if sel.sum() >= max(3, h // 2):
                        fs = float(f[sel].sum())
                        act = float(window[:len(f)][sel].sum())
                        rel = (act - fs) / max(fs, 1.0)
                        bt.sum_pairs.setdefault((m, h), []).append((act, fs))
                        bt.sum_errors.setdefault((m, _volume_bucket(float(c.y[:cut][-28:].mean())), h), []).append(rel)
    sigma = {pid: {m: (float(np.std(r)) if len(r) > 5 else float("nan")) for m, r in per.items()} for pid, per in resid.items()}
    return bt, sigma


def pooled_metrics(bt: Backtest) -> dict[str, Metrics]:
    return {m: metrics(np.concatenate(bt.actual[m]) if bt.actual[m] else np.array([]),
                       np.concatenate(bt.forecast[m]) if bt.forecast[m] else np.array([])) for m in MODEL_NAMES}


def interval_quantiles(bt: Backtest, model: str) -> dict[tuple[str, int], tuple[float, float]]:
    """(bucket, horizon) -> (q10, q90) of the relative error of the horizon sum. Buckets with < 12 observations borrow the pool."""
    out: dict[tuple[str, int], tuple[float, float]] = {}
    for h in SUM_HORIZONS:
        pool = [e for (m, b, hh), v in bt.sum_errors.items() if m == model and hh == h for e in v]
        for bucket in ("low", "mid", "high"):
            vals = bt.sum_errors.get((model, bucket, h), [])
            use = vals if len(vals) >= 12 else pool
            if len(use) >= 5:
                out[(bucket, h)] = (float(np.quantile(use, _LOW)), float(np.quantile(use, _HIGH)))
            else:
                out[(bucket, h)] = (-0.5, 0.5)
    return out


def interval_for_sum(forecast_sum: float, daily_mean: float, h: int, q: dict[tuple[str, int], tuple[float, float]]) -> tuple[float, float]:
    """80% interval of the sum over the next h days. h between the evaluated horizons uses the next evaluated one (conservative)."""
    bucket = _volume_bucket(daily_mean)
    key = min((x for x in SUM_HORIZONS if x >= h), default=SUM_HORIZONS[-1])
    lo, hi = q.get((bucket, key), (-0.5, 0.5))
    base = max(forecast_sum, 1.0)
    return max(0.0, forecast_sum + lo * base), forecast_sum + hi * base


def coverage(bt_holdout: Backtest, q: dict[tuple[str, int], tuple[float, float]], model: str, h: int) -> float | None:
    """Share of held-out horizon sums that fell inside the interval derived from the selection origins."""
    inside = total = 0
    for (m, bucket, hh), v in bt_holdout.sum_errors.items():
        if m != model or hh != h:
            continue
        lo, hi = q.get((bucket, h), (-0.5, 0.5))
        inside += sum(lo <= e <= hi for e in v)
        total += len(v)
    return inside / total if total else None


def sum_wape(bt: Backtest, model: str, h: int) -> float | None:
    """WAPE of the horizon-sum forecast (what replenishment actually consumes): sum |actual - forecast| / sum actual."""
    pairs = bt.sum_pairs.get((model, h), [])
    denom = sum(a for a, _ in pairs)
    return sum(abs(a - f) for a, f in pairs) / denom if pairs and denom > 0 else None
