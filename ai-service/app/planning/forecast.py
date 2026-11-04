"""Weekly demand forecasting for SKU x store series. Pure numpy: no I/O, no LLM. Everything that matters is a named constant or a documented rule.

Candidate methods (all produce non-negative weekly units for horizons 1..H):
  MA4              mean of the last 4 weeks, flat. The BASELINE every claimed improvement is measured against.
  SEASONAL_NAIVE   the same week one year earlier (needs >= 52 weeks).
  SEASONAL_LEVEL   level of the last 8 de-seasonalised weeks x the seasonal index of the week (index learned per SPORT from the training window only).
  ETS_DAMPED       exponential smoothing with a damped trend on the de-seasonalised series, x the seasonal index (alpha chosen by one-step error).
  CROSTON_SBA      Croston with the Syntetos-Boylan correction, for intermittent demand (many zero weeks); no seasonality is applied.
  TOP_DOWN         the same SEASONAL_LEVEL forecast on the store's product x REGION total, divided over the region's stores by each store's share of the last 26 training weeks.
Fallbacks when a series has too little history: TOP_DOWN with the shares that exist (8..min_history weeks), COMPARABLE_STORE (region total x the store's size factor share) below 8 weeks,
and no forecast at all (INSUFFICIENT_DATA) for a product with no sales anywhere: a new product is planned as a pilot, not forecast from nothing.

Method choice is per VOLUME BUCKET (INTERMITTENT / LOW / MEDIUM / HIGH), by pooled WAPE + |bias| over SELECTION origins; the accuracy that is reported comes from a HELD-OUT origin whose test
weeks come after every selection test window, so the chosen method has never seen them. Training windows never contain a week at or after the forecast origin (no leakage); the
seasonal index, the shares and the intervals are all recomputed from the training window of each origin.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date
from typing import Callable, Iterable, Sequence

import numpy as np

VERSION = "weekly-v1"
BASELINE = "MA4"
CANDIDATES = ("MA4", "SEASONAL_NAIVE", "SEASONAL_LEVEL", "ETS_DAMPED", "CROSTON_SBA", "TOP_DOWN")
ALL_METHODS = CANDIDATES + ("COMPARABLE_STORE",)
HORIZON_GROUPS = {"H1": (1, 1), "H2_4": (2, 4), "H5_8": (5, 8), "H9_PLUS": (9, 10_000)}
MIN_WEEKS_FOR_SEASONALITY = 65           # below this no seasonal index is learned (the index is 1); with under two years some week slots have a single observation, which the smoothing softens
SHARE_WEEKS = 26
STOCKOUT_CENSOR_DAYS = 3                 # a week with this many stock-out days under-states demand: it is imputed in the TRAINING data, never in the actuals it is judged against
COMPARABLE_BELOW_WEEKS = 8
BUCKETS = ("INTERMITTENT", "LOW", "MEDIUM", "HIGH")


@dataclass
class SeriesInput:
    product_id: str
    location_id: str
    sport: str
    category: str
    region: str
    y: np.ndarray                         # weekly net units over the common week axis; NaN where the store did not trade / the week is unknown
    stockout_days: np.ndarray | None = None
    size_factor: float = 1.0
    opened_on: date | None = None
    status: str = "ACTIVE"
    first_forecast_week: int = 0          # index into the future weeks from which this store trades (a PLANNED store)

    @property
    def key(self) -> tuple[str, str]:
        return self.product_id, self.location_id


@dataclass
class SeriesForecast:
    product_id: str
    location_id: str
    status: str                           # OK | FALLBACK | INSUFFICIENT_DATA
    method: str
    bucket: str | None
    confidence: str                       # HIGH | MEDIUM | LOW | NONE
    reason: str | None
    history_weeks: int
    zero_share: float | None
    forecast: np.ndarray = field(default_factory=lambda: np.zeros(0))
    lo: np.ndarray = field(default_factory=lambda: np.zeros(0))
    hi: np.ndarray = field(default_factory=lambda: np.zeros(0))
    baseline: np.ndarray = field(default_factory=lambda: np.zeros(0))
    inputs: dict = field(default_factory=dict)


# ------------------------------------------------------------------------------------------------ helpers
def week_slot(week_start: date) -> int:
    """0..51: the week of the year (days since 1 January / 7, capped), used to line up the same week in different years."""
    return min(51, (week_start.timetuple().tm_yday - 1) // 7)


def valid_len(y: np.ndarray) -> int:
    """Weeks of history: from the first known week to the end."""
    idx = np.flatnonzero(~np.isnan(y))
    return 0 if idx.size == 0 else int(len(y) - idx[0])


def trim(y: np.ndarray) -> np.ndarray:
    idx = np.flatnonzero(~np.isnan(y))
    return y[idx[0]:] if idx.size else y[:0]


def impute_censored(y: np.ndarray, so: np.ndarray | None) -> np.ndarray:
    """Replace weeks cut short by stock-outs with the mean of the previous (up to 8) uncensored weeks. Applies to TRAINING data only."""
    if so is None or not len(y):
        return y
    out = y.copy()
    flagged = np.nan_to_num(so, nan=0.0) >= STOCKOUT_CENSOR_DAYS
    for i in np.flatnonzero(flagged):
        prev = [out[j] for j in range(max(0, i - 8), i) if not flagged[j] and not np.isnan(out[j])]
        if prev:
            out[i] = float(np.mean(prev))
    return out


def fill_unknown(y: np.ndarray) -> np.ndarray:
    """Weeks inside a series that are UNKNOWN (NaN: the store was not trading, or the week was not recorded) cannot be fed to a method as they are: one NaN makes a whole level NaN. They are replaced by the
    mean of the previous (up to 8) known weeks, or the series mean when there is none. Applies to TRAINING data only; an unknown week is never turned into a measured zero."""
    if not len(y) or not np.isnan(y).any():
        return y
    out = y.copy()
    known = out[~np.isnan(out)]
    overall = float(known.mean()) if known.size else 0.0
    for i in np.flatnonzero(np.isnan(out)):
        prev = [out[j] for j in range(max(0, i - 8), i) if not np.isnan(out[j])]
        out[i] = float(np.mean(prev)) if prev else overall
    return out


def seasonal_index(weeks: Sequence[date], group_mean: np.ndarray) -> np.ndarray:
    """A 52-slot index (mean 1) from a group's mean weekly units per active series: ratio to the linear trend of the training window, averaged by week-of-year, circularly smoothed.
    Needs at least MIN_WEEKS_FOR_SEASONALITY weeks."""
    n = len(group_mean)
    idx = np.ones(52)
    if n < MIN_WEEKS_FOR_SEASONALITY or not np.isfinite(group_mean).any():
        return idx
    g = np.nan_to_num(group_mean, nan=0.0)
    t = np.arange(n, dtype=float)
    ma = np.polyval(np.polyfit(t, g, 1), t)                       # the slow trend; a short moving average would absorb an ANNUAL pattern and hide it
    ratio = np.where(ma > 0, g / np.where(ma > 0, ma, 1), np.nan)
    sums, cnt = np.zeros(52), np.zeros(52)
    for i, w in enumerate(weeks[:n]):
        if not math.isnan(ratio[i]):
            k = week_slot(w)
            sums[k] += ratio[i]
            cnt[k] += 1
    raw = np.where(cnt > 0, sums / np.maximum(cnt, 1), 1.0)
    smooth = (np.roll(raw, 1) + raw + np.roll(raw, -1)) / 3.0
    smooth = np.clip(smooth, 0.5, 2.0)
    return smooth / smooth.mean()


def bucket_of(y_train: np.ndarray) -> tuple[str, float]:
    recent = y_train[-SHARE_WEEKS:] if len(y_train) else y_train
    recent = recent[~np.isnan(recent)]
    if recent.size == 0:
        return "INTERMITTENT", 1.0
    zero_share = float((recent == 0).mean())
    mean = float(recent.mean())
    if zero_share >= 0.35:
        return "INTERMITTENT", zero_share
    return ("LOW" if mean < 5 else "MEDIUM" if mean < 20 else "HIGH"), zero_share


# ------------------------------------------------------------------------------------------------ methods
def m_ma4(y: np.ndarray, H: int, **_: object) -> np.ndarray:
    tail = y[-4:]
    return np.full(H, float(tail.mean()) if tail.size else 0.0)


def m_snaive(y: np.ndarray, H: int, **_: object) -> np.ndarray:
    if len(y) < 52 + 4:
        return m_ma4(y, H)
    out = np.empty(H)
    for h in range(H):
        j = len(y) + h - 52
        out[h] = y[j] if j < len(y) else y[-1]
    return np.maximum(out, 0.0)


def _deseason(y: np.ndarray, weeks_hist: Sequence[date], idx: np.ndarray) -> np.ndarray:
    return np.array([v / idx[week_slot(w)] for v, w in zip(y, weeks_hist)])


def m_seasonal_level(y: np.ndarray, H: int, *, weeks_hist: Sequence[date], weeks_fut: Sequence[date], idx: np.ndarray, **_: object) -> np.ndarray:
    if len(y) < 4:
        return m_ma4(y, H)
    d = _deseason(y, weeks_hist, idx)
    level = float(d[-8:].mean())
    return np.maximum(np.array([level * idx[week_slot(w)] for w in weeks_fut[:H]]), 0.0)


def m_ets(y: np.ndarray, H: int, *, weeks_hist: Sequence[date], weeks_fut: Sequence[date], idx: np.ndarray, phi: float = 0.9, **_: object) -> np.ndarray:
    if len(y) < 8:
        return m_ma4(y, H)
    d = _deseason(y, weeks_hist, idx)
    best = None
    for alpha in (0.1, 0.2, 0.3, 0.5):
        level, trend, sse = d[:4].mean(), 0.0, 0.0
        for t in range(4, len(d)):
            pred = level + phi * trend
            sse += (d[t] - pred) ** 2
            new_level = alpha * d[t] + (1 - alpha) * (level + phi * trend)
            trend = 0.1 * (new_level - level) + (1 - 0.1) * phi * trend
            level = new_level
        if best is None or sse < best[0]:
            best = (sse, level, trend)
    _, level, trend = best  # type: ignore[misc]
    out, damp = [], 0.0
    for h in range(1, H + 1):
        damp += phi ** h
        out.append(max(0.0, (level + damp * trend) * idx[week_slot(weeks_fut[h - 1])]))
    return np.array(out)


def m_croston_sba(y: np.ndarray, H: int, alpha: float = 0.1, **_: object) -> np.ndarray:
    nz = np.flatnonzero(y > 0)
    if nz.size == 0:
        return np.zeros(H)
    z, p, last = float(y[nz[0]]), float(nz[0] + 1), nz[0]
    for t in nz[1:]:
        z = alpha * y[t] + (1 - alpha) * z
        p = alpha * (t - last) + (1 - alpha) * p
        last = t
    return np.full(H, max(0.0, (1 - alpha / 2.0) * z / max(p, 1.0)))


BASE_METHODS: dict[str, Callable[..., np.ndarray]] = {"MA4": m_ma4, "SEASONAL_NAIVE": m_snaive, "SEASONAL_LEVEL": m_seasonal_level, "ETS_DAMPED": m_ets, "CROSTON_SBA": m_croston_sba}


# ------------------------------------------------------------------------------------------------ metrics
def metric_row(actual: np.ndarray, fc: np.ndarray) -> dict[str, float | int | None]:
    a, f = np.asarray(actual, dtype=float), np.asarray(fc, dtype=float)
    m = ~np.isnan(a) & ~np.isnan(f)
    a, f = a[m], f[m]
    total = float(a.sum())
    return {"n_obs": int(a.size), "actual": total, "abs_err": float(np.abs(a - f).sum()), "signed_err": float((f - a).sum()),
            "wape": (float(np.abs(a - f).sum() / total) if total > 0 else None), "bias": (float((f - a).sum() / total) if total > 0 else None),
            "mae": (float(np.abs(a - f).mean()) if a.size else None)}


def _fin(v: float | None) -> float | None:
    """Rounded, or None for a missing / non-finite value (JSON has no NaN)."""
    return None if v is None or not math.isfinite(v) else round(float(v), 4)


def horizon_group(h: int) -> str:
    return next(g for g, (lo, hi) in HORIZON_GROUPS.items() if lo <= h <= hi)


@dataclass
class Obs:
    """One forecast of one series-week against its actual, at one origin."""
    series: int
    origin: int
    h: int
    actual: float
    method_fc: dict[str, float]


# ------------------------------------------------------------------------------------------------ the forecaster
class Forecaster:
    def __init__(self, weeks: Sequence[date], future: Sequence[date], series: Sequence[SeriesInput], *, min_history: int = 26, selection_origins: int = 3, step: int = 4,
                 min_eval_obs: int = 30, min_eval_units: float = 100.0):
        self.weeks, self.future, self.series = list(weeks), list(future), list(series)
        self.T, self.H = len(self.weeks), len(self.future)
        self.min_history, self.selection_origins, self.step = min_history, selection_origins, step
        self.min_eval_obs, self.min_eval_units = min_eval_obs, min_eval_units
        self._sport_ix = {s.key: i for i, s in enumerate(self.series)}
        self._parents: dict[tuple[str, str], list[int]] = {}
        for i, s in enumerate(self.series):
            self._parents.setdefault((s.product_id, s.region), []).append(i)
        self._parent_cache: dict[int, dict[tuple[str, str], np.ndarray]] = {}

    # ---- indexes learned from a training window
    def _sport_indexes(self, t0: int) -> dict[str, np.ndarray]:
        out: dict[str, np.ndarray] = {}
        by_sport: dict[str, list[int]] = {}
        for i, s in enumerate(self.series):
            by_sport.setdefault(s.sport, []).append(i)
        for sport, members in by_sport.items():
            mat = np.vstack([self.series[i].y[:t0] for i in members])
            active = (~np.isnan(mat)).sum(axis=0)
            mean = np.where(active > 0, np.nansum(mat, axis=0) / np.maximum(active, 1), np.nan)
            out[sport] = seasonal_index(self.weeks[:t0], mean)
        return out

    def _parent_series(self, t0: int) -> dict[tuple[str, str], np.ndarray]:
        if t0 in self._parent_cache:
            return self._parent_cache[t0]
        out = {}
        for key, members in self._parents.items():
            mat = np.vstack([self.series[i].y[:t0] for i in members])
            out[key] = np.nansum(mat, axis=0)
        self._parent_cache[t0] = out
        return out

    def _shares(self, key: tuple[str, str], t0: int) -> dict[int, float]:
        members = self._parents[key]
        lo = max(0, t0 - SHARE_WEEKS)
        tot = {i: float(np.nansum(self.series[i].y[lo:t0])) for i in members}
        s = sum(tot.values())
        return {i: (v / s if s > 0 else 0.0) for i, v in tot.items()}

    # ---- one series, one origin, every candidate
    def forecast_series(self, i: int, t0: int, weeks_fut: Sequence[date], H: int, idxs: dict[str, np.ndarray], parents: dict[tuple[str, str], np.ndarray],
                        methods: Iterable[str] = CANDIDATES) -> dict[str, np.ndarray]:
        s = self.series[i]
        y_raw = trim(s.y[:t0])
        so = None if s.stockout_days is None else s.stockout_days[:t0][len(s.y[:t0]) - len(y_raw):]
        y = fill_unknown(impute_censored(y_raw, so))
        n_hist = len(y)
        start = t0 - n_hist
        weeks_hist = self.weeks[start:t0]
        idx = idxs[s.sport]
        out: dict[str, np.ndarray] = {}
        for m in methods:
            if m == "TOP_DOWN":
                pk = (s.product_id, s.region)
                pary = parents[pk]
                pstart = int(np.flatnonzero(pary > 0)[0]) if (pary > 0).any() else 0
                pyy = pary[pstart:]
                pfc = m_seasonal_level(pyy, H, weeks_hist=self.weeks[pstart:t0], weeks_fut=weeks_fut, idx=idx) if len(pyy) >= 4 else np.zeros(H)
                share = self._shares(pk, t0).get(i, 0.0)
                out[m] = pfc * share
            elif n_hist == 0:
                out[m] = np.zeros(H)
            else:
                out[m] = np.maximum(np.nan_to_num(BASE_METHODS[m](y, H, weeks_hist=weeks_hist, weeks_fut=weeks_fut, idx=idx), nan=0.0, posinf=0.0, neginf=0.0), 0.0)
        return out

    # ---- rolling-origin backtest
    def backtest(self, origins: Sequence[int], methods: Iterable[str] = CANDIDATES) -> list[Obs]:
        """`origins` are training-end indexes t0 (the forecast covers weeks t0 .. t0+H-1 of the history). Only series with at least `min_history` weeks before t0 take part."""
        methods = list(methods)
        obs: list[Obs] = []
        for k, t0 in enumerate(origins):
            H = min(self.H, self.T - t0)
            if H <= 0:
                continue
            idxs = self._sport_indexes(t0)
            parents = self._parent_series(t0)
            fut = self.weeks[t0:t0 + H]
            for i, s in enumerate(self.series):
                if valid_len(s.y[:t0]) < self.min_history:
                    continue
                actual = s.y[t0:t0 + H]
                if np.isnan(actual).all():
                    continue
                fcs = self.forecast_series(i, t0, fut, H, idxs, parents, methods)
                for h in range(H):
                    if np.isnan(actual[h]):
                        continue
                    obs.append(Obs(i, k, h + 1, float(actual[h]), {m: float(fcs[m][h]) for m in methods}))
        return obs

    def select_methods(self, selection_obs: list[Obs]) -> tuple[dict[str, str], dict[str, dict]]:
        """Per volume bucket, the candidate with the lowest WAPE + |bias| on the selection origins (ties go to the simpler method). Returns (bucket -> method, the full table)."""
        by_bucket: dict[str, list[Obs]] = {b: [] for b in BUCKETS}
        for o in selection_obs:
            t0 = self._origin_t0(o.origin)
            by_bucket[bucket_of(trim(self.series[o.series].y[:t0]))[0]].append(o)
        chosen, table = {}, {}
        order = list(CANDIDATES)
        for b, rows in by_bucket.items():
            if not rows:
                chosen[b], table[b] = BASELINE, {"n_obs": 0, "note": "no observations: the baseline is used"}
                continue
            a = np.array([r.actual for r in rows])
            scores, biases = {}, {}
            for m in CANDIDATES:
                f = np.array([r.method_fc[m] for r in rows])
                row = metric_row(a, f)
                scores[m], biases[m] = row["wape"], row["bias"]
            # Selection score = WAPE + |bias|. WAPE alone rewards under-forecasting sparse series (a forecast of zero scores exactly 100%, while an unbiased mean forecast of an intermittent series
            # usually scores worse), which would pick a method that predicts almost nothing. Adding the absolute bias removes that incentive. Only the SELECTION origins are used here.
            ok = {m: w + abs(biases[m] or 0.0) for m, w in scores.items() if w is not None and math.isfinite(w) and math.isfinite(biases[m] or 0.0)}
            best = min(ok, key=lambda m: (round(ok[m], 6), order.index(m))) if ok else BASELINE
            chosen[b] = best
            table[b] = {"n_obs": len(rows), "actual_units": float(a.sum()), "wape": {m: _fin(w) for m, w in scores.items()},
                        "bias": {m: _fin(v) for m, v in biases.items()}, "score": {m: round(v, 4) for m, v in ok.items()}, "chosen": best,
                        "criterion": "WAPE + |bias| on the selection origins (the held-out weeks are never used to choose)"}
        return chosen, table

    _origin_ts: list[int] = []

    def _origin_t0(self, k: int) -> int:
        return self._origin_ts[k]

    # ---- the whole run
    def run(self) -> dict:
        T, H = self.T, self.H
        sel_origins = [T - 2 * H - j * self.step for j in range(self.selection_origins)]
        sel_origins = [t for t in sel_origins if t >= self.min_history + 4]
        held_t0 = T - H
        results: dict = {"selection_origins": sel_origins, "heldout_origin": held_t0}
        self._origin_ts = sel_origins
        sel_obs = self.backtest(sel_origins) if sel_origins else []
        chosen, table = self.select_methods(sel_obs)
        results["selection"] = table
        results["chosen"] = chosen
        # held-out origin: train to T-H, forecast the last H closed weeks with EVERY candidate (so the table is honest) and judge the chosen one
        self._origin_ts = [held_t0]
        held_obs = self.backtest([held_t0]) if held_t0 >= self.min_history else []
        results["held_obs"] = held_obs
        results["held_t0"] = held_t0
        # interval calibration from the held-out residuals of the chosen method, per horizon group: normalised error (actual - f) / sqrt(f + 1)
        cal: dict[str, tuple[float, float]] = {}
        norm: dict[str, list[float]] = {g: [] for g in HORIZON_GROUPS}
        for o in held_obs:
            b = bucket_of(trim(self.series[o.series].y[:held_t0]))[0]
            f = o.method_fc[chosen.get(b, BASELINE)]
            norm[horizon_group(o.h)].append((o.actual - f) / math.sqrt(f + 1.0))
        for g, vals in norm.items():
            cal[g] = (float(np.quantile(vals, 0.10)), float(np.quantile(vals, 0.90))) if len(vals) >= 30 else (-1.2816, 1.2816)
        results["interval"] = {g: {"q10": round(v[0], 3), "q90": round(v[1], 3), "calibrated": len(norm[g]) >= 30, "n": len(norm[g])} for g, v in cal.items()}
        self._cal = cal
        # final forecasts: train on all closed history, forecast the future weeks
        idxs = self._sport_indexes(T)
        parents = self._parent_series(T)
        self._origin_ts = []
        finals = [self._final(i, T, idxs, parents, chosen, table) for i in range(len(self.series))]
        results["series"] = finals
        results["held_by_bucket"] = self._held_bucket_wape(held_obs, held_t0, chosen)
        return results

    def _held_bucket_wape(self, held_obs: list[Obs], t0: int, chosen: dict[str, str]) -> dict[str, dict]:
        out = {}
        for b in BUCKETS:
            rows = [o for o in held_obs if bucket_of(trim(self.series[o.series].y[:t0]))[0] == b]
            if not rows:
                continue
            a = np.array([r.actual for r in rows])
            m = metric_row(a, np.array([r.method_fc[chosen.get(b, BASELINE)] for r in rows]))
            m["baseline_wape"] = metric_row(a, np.array([r.method_fc[BASELINE] for r in rows]))["wape"]
            m["sufficient"] = m["n_obs"] >= self.min_eval_obs and m["actual"] >= self.min_eval_units
            out[b] = m
        return out

    def _interval(self, f: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        lo, hi = np.zeros_like(f), np.zeros_like(f)
        for h in range(len(f)):
            q10, q90 = self._cal[horizon_group(h + 1)]
            r = math.sqrt(f[h] + 1.0)
            lo[h], hi[h] = max(0.0, f[h] + q10 * r), max(f[h], f[h] + q90 * r)
        return lo, hi

    def _final(self, i: int, T: int, idxs, parents, chosen: dict[str, str], table: dict[str, dict]) -> SeriesForecast:
        s = self.series[i]
        n = valid_len(s.y)
        pk = (s.product_id, s.region)
        parent_has_history = bool((parents[pk] > 0).any())
        base_inputs = {"history_weeks": n, "first_week": None if n == 0 else self.weeks[T - n].isoformat(), "stockout_adjusted": s.stockout_days is not None, "seasonal_index": "sport"}
        zero_share = None
        if n == 0 and s.status == "PLANNED" or (n == 0 and s.first_forecast_week > 0):
            reason = "The store has not opened yet: a COMPARABLE forecast from its region and size, low confidence."
            f = self._comparable(i, T, idxs, parents)
            return self._pack(s, "FALLBACK", "COMPARABLE_STORE", None, "LOW", reason, n, None, f, base_inputs | {"comparable": "region total x size share"})
        if n == 0 or not parent_has_history:
            return SeriesForecast(s.product_id, s.location_id, "INSUFFICIENT_DATA", "NONE", None, "NONE", "No sales history for this product in this store or region: plan it as a pilot, do not forecast it.",
                                  n, None, inputs=base_inputs)
        y = trim(s.y)
        bucket, zero_share = bucket_of(y)
        if n < COMPARABLE_BELOW_WEEKS:
            f = self._comparable(i, T, idxs, parents)
            return self._pack(s, "FALLBACK", "COMPARABLE_STORE", bucket, "LOW", f"Only {n} week(s) of history: the region's forecast shared by store size (a comparable), not the store's own pattern.",
                              n, zero_share, f, base_inputs | {"comparable": "region total x size share"})
        if n < self.min_history:
            f = self.forecast_series(i, T, self.future, self.H, idxs, parents, ("TOP_DOWN",))["TOP_DOWN"]
            return self._pack(s, "FALLBACK", "TOP_DOWN", bucket, "LOW", f"{n} weeks of history (under {self.min_history}): the region's forecast split by this store's share so far.", n, zero_share, f,
                              base_inputs | {"top_down_share_weeks": min(n, SHARE_WEEKS)})
        method = chosen.get(bucket, BASELINE)
        f = self.forecast_series(i, T, self.future, self.H, idxs, parents, (method,))[method]
        conf = self._confidence(bucket, method, n, table)
        return self._pack(s, "OK", method, bucket, conf, None, n, zero_share, f, base_inputs | {"bucket": bucket, "method_choice": table.get(bucket, {}).get("wape")})

    def _comparable(self, i: int, T: int, idxs, parents) -> np.ndarray:
        s = self.series[i]
        pk = (s.product_id, s.region)
        pary = parents[pk]
        pstart = int(np.flatnonzero(pary > 0)[0]) if (pary > 0).any() else 0
        pfc = m_seasonal_level(pary[pstart:], self.H, weeks_hist=self.weeks[pstart:T], weeks_fut=self.future, idx=idxs[s.sport]) if len(pary[pstart:]) >= 4 else np.zeros(self.H)
        trading = [j for j in self._parents[pk] if self.series[j].status == "ACTIVE" or j == i]
        total_size = sum(self.series[j].size_factor for j in trading) or 1.0
        out = pfc * (s.size_factor / total_size)
        if s.first_forecast_week:
            out[: s.first_forecast_week] = 0.0
        return out

    def _confidence(self, bucket: str, method: str, n: int, table: dict[str, dict]) -> str:
        info = table.get(bucket, {})
        w = (info.get("wape") or {}).get(method)
        if bucket == "INTERMITTENT" or w is None or info.get("n_obs", 0) < self.min_eval_obs:
            return "LOW"
        if n >= 52 and w <= 0.45:
            return "HIGH"
        return "MEDIUM" if w <= 0.85 else "LOW"

    def _pack(self, s: SeriesInput, status: str, method: str, bucket: str | None, conf: str, reason: str | None, n: int, zero_share: float | None, f: np.ndarray, inputs: dict) -> SeriesForecast:
        f = np.maximum(np.asarray(f, dtype=float), 0.0)
        lo, hi = self._interval(f)
        base = np.maximum(m_ma4(trim(s.y), self.H), 0.0) if n else np.zeros(self.H)
        if s.first_forecast_week:
            f[: s.first_forecast_week] = 0.0
            lo[: s.first_forecast_week] = 0.0
            hi[: s.first_forecast_week] = 0.0
            base = base.copy()
            base[: s.first_forecast_week] = 0.0
        return SeriesForecast(s.product_id, s.location_id, status, method, bucket, conf, reason, n, zero_share, f, lo, hi, base, inputs)
