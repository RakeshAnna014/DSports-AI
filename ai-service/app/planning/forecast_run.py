"""Running the weekly forecast end to end: read the actuals of a data scope, build the series, forecast, evaluate and persist as a versioned run. The numbers come from
`app.planning.forecast`; this module only moves data. An LLM is never involved."""
from __future__ import annotations

import logging
import time
import uuid
from collections import defaultdict
from datetime import date, timedelta
from typing import Any

import numpy as np

from app.planning import forecast as fc
from app.planning.calendar import Calendar
from app.planning.repository import DEMO_SRC, IMPORTED, LIVE, PlanningRepository, dumps, rec, recs

log = logging.getLogger(__name__)
SCOPE_SOURCES = {DEMO_SRC: (DEMO_SRC, IMPORTED), LIVE: (LIVE,)}
FEATURES_USED = ["weekly net units (the actual-sales convention)", "sport-level seasonality learned from the training window", "stock-out weeks imputed in training (when stock-out days are known)",
                 "store and region shares of the last 26 weeks", "store opening dates and size factors (fallbacks for new and planned stores)"]
FEATURES_NOT_USED = ["selling price and discounts (history is stored; no validated price model is applied to the baseline)", "promotion calendar",
                     "sporting events (shown as context on the plan, not applied to the baseline: past events are already inside the seasonal pattern)", "weather", "competitor activity"]


class ForecastError(Exception):
    def __init__(self, code: str, message: str, status: int = 409):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status


async def load_inputs(repo: PlanningRepository, cal: Calendar, scope: str, lookback_weeks: int, horizon: int) -> dict[str, Any]:
    """The common week axis and one SeriesInput per product x trading location for a data scope."""
    sources = SCOPE_SOURCES.get(scope)
    if sources is None:
        raise ForecastError("INVALID_SCOPE", f"scope must be one of {', '.join(SCOPE_SOURCES)}", 422)
    through = None
    for s in sources:
        d = await repo.data_through(s)
        through = d if through is None or (d is not None and d > through) else through
    if through is None:
        raise ForecastError("NO_DATA", f"There is no {scope} sales data yet" + (": seed the demonstration data first" if scope == DEMO_SRC else ": ingest the DSports sales feed first"))
    last_closed = cal.last_closed_week(through)
    first_week = await repo.pool.fetchval("SELECT MIN(week_start) FROM pln_sales_week WHERE data_source = ANY($1::text[]) AND week_start <= $2", list(sources), last_closed)
    if first_week is None:
        raise ForecastError("NO_DATA", f"There is no closed week of {scope} sales data yet")
    weeks = cal.weeks(max(first_week, cal.add_weeks(last_closed, -(lookback_weeks - 1))), last_closed)
    future = [cal.add_weeks(last_closed, k) for k in range(1, horizon + 1)]
    pos = {w: i for i, w in enumerate(weeks)}
    locs = {r["id"]: r for r in await repo.list_locations()}

    def region_of(loc: str) -> str:
        node = locs.get(loc)
        while node and node["level"] != "REGION":
            node = locs.get(node["parent_id"]) if node["parent_id"] else None
        return node["id"] if node else "UNK"

    skus = {str(r["product_id"]): r for r in await repo.pool.fetch("SELECT product_id, sport, category, name, sku FROM inv_sku")}
    rows = await repo.pool.fetch(
        """SELECT product_id, location_id, week_start, data_source, net_units, stockout_days FROM pln_sales_week
           WHERE data_source = ANY($1::text[]) AND week_start >= $2 AND week_start <= $3""", list(sources), weeks[0], last_closed)
    cells: dict[tuple[str, str], np.ndarray] = {}
    so: dict[tuple[str, str], np.ndarray] = {}
    rank = {DEMO_SRC: 0, LIVE: 0, IMPORTED: 1}                      # an imported store actual (a person vouched for it) outranks a synthetic one for the same week
    seen_rank: dict[tuple[str, str, int], int] = {}
    for r in rows:
        key = (str(r["product_id"]), r["location_id"])
        arr = cells.setdefault(key, np.full(len(weeks), np.nan))
        i = pos.get(r["week_start"])
        if i is None:
            continue
        k3 = (*key, i)
        if k3 in seen_rank and seen_rank[k3] > rank[r["data_source"]]:
            continue
        seen_rank[k3] = rank[r["data_source"]]
        arr[i] = r["net_units"]
        if r["stockout_days"] is not None:
            so.setdefault(key, np.full(len(weeks), np.nan))[i] = r["stockout_days"]
    series: list[fc.SeriesInput] = []
    trading = [l for l in locs.values() if l["level"] == "STORE" and (l["channel"] == "ONLINE" if scope == LIVE else l["channel"] == "STORE")]
    # LIVE: the feed is complete, so a week without lines is a CONFIRMED zero, from the first week in which anything was sold. DEMO: a week with no row means the store was not trading (unknown, not zero).
    first_live = min((i for arr in cells.values() for i in np.flatnonzero(~np.isnan(arr))[:1]), default=0)
    for pid, sku in skus.items():
        for loc in trading:
            key = (pid, loc["id"])
            y = cells.get(key)
            if scope == LIVE:
                if y is None:
                    continue
                y = y.copy()
                y[first_live:][np.isnan(y[first_live:])] = 0.0
            elif y is None:
                y = np.full(len(weeks), np.nan)
            first_future = 0
            if loc["opened_on"] and date.fromisoformat(loc["opened_on"]) > last_closed:          # a store that opens during the horizon is forecast only from its opening week
                opening_week = cal.week_start(date.fromisoformat(loc["opened_on"]))
                first_future = next((k for k, w in enumerate(future) if w >= opening_week), len(future))
            series.append(fc.SeriesInput(pid, loc["id"], sku["sport"], sku["category"], region_of(loc["id"]), y, so.get(key), float(loc["size_factor"] or 1), loc["opened_on"], loc["status"], first_future))
    if scope == LIVE:
        sold = {s.product_id for s in series if np.nansum(s.y) > 0}
        series = [s for s in series if s.product_id in sold]
    return {"weeks": weeks, "future": future, "series": series, "through": through, "last_closed": last_closed, "sources": sources, "locations": locs, "skus": skus}


def evaluation_rows(run_id: str, results: dict, fcst: fc.Forecaster, min_obs: int, min_units: float, skus: dict, region_of: dict) -> list[tuple]:
    """Accuracy of the chosen method on the HELD-OUT origin, by scope and horizon, with the MA4 baseline alongside; and every candidate overall for comparison."""
    held, t0, chosen = results["held_obs"], results["held_t0"], results["chosen"]
    groups: dict[tuple[str, str, str], list[tuple[float, float, float, int]]] = defaultdict(list)
    cand: dict[tuple[str, str], list[tuple[float, float]]] = defaultdict(list)
    for o in held:
        s = fcst.series[o.series]
        b = fc.bucket_of(fc.trim(s.y[:t0]))[0]
        m = chosen.get(b, fc.BASELINE)
        sel, base = o.method_fc[m], o.method_fc[fc.BASELINE]
        scopes = [("OVERALL", "ALL"), ("SPORT", s.sport), ("CATEGORY", f"{s.sport} / {s.category}"), ("REGION", s.region), ("STORE", s.location_id), ("BUCKET", b), ("METHOD", m)]
        hg = fc.horizon_group(o.h)
        for sc, key in scopes:
            for g in ("ALL", hg):
                groups[(sc, key, g)].append((o.actual, sel, base, o.series))
        for name in fc.CANDIDATES:
            for g in ("ALL", hg):
                cand[(name, g)].append((o.actual, o.method_fc[name]))
    out: list[tuple] = []
    for (sc, key, g), vals in groups.items():
        a = np.array([v[0] for v in vals])
        m_sel = fc.metric_row(a, np.array([v[1] for v in vals]))
        m_base = fc.metric_row(a, np.array([v[2] for v in vals]))
        n_series = len({v[3] for v in vals})
        ok = m_sel["n_obs"] >= min_obs and m_sel["actual"] >= min_units
        out.append((uuid.UUID(run_id), sc, str(key), "SELECTED", g, int(m_sel["n_obs"]), n_series, float(m_sel["actual"]), m_sel["wape"], m_sel["bias"], m_sel["mae"], m_base["wape"], ok))
    for (name, g), vals in cand.items():
        a = np.array([v[0] for v in vals])
        m = fc.metric_row(a, np.array([v[1] for v in vals]))
        ok = m["n_obs"] >= min_obs and m["actual"] >= min_units
        out.append((uuid.UUID(run_id), "CANDIDATE", name, name, g, int(m["n_obs"]), 0, float(m["actual"]), m["wape"], m["bias"], m["mae"], None, ok))
    return out


async def execute(repo: PlanningRepository, cal: Calendar, settings: Any, scope: str, actor_hash: str) -> dict[str, Any]:
    """Create a forecast run for a data scope. The previous run stays (versioned); the new one becomes `is_current` only if it succeeds."""
    t_start = time.monotonic()
    run_id = str(uuid.uuid4())
    horizon = settings.planning_horizon_weeks
    data = await load_inputs(repo, cal, scope, settings.planning_lookback_weeks, horizon)
    if not data["series"]:
        raise ForecastError("NO_SERIES", "No product has sales history in this data scope")
    await repo.pool.execute(
        "INSERT INTO pln_forecast_run (id, created_by, source, as_of_week, first_week, horizon_weeks, model_version, status) VALUES ($1,$2,$3,$4,$5,$6,$7,'RUNNING')",
        uuid.UUID(run_id), actor_hash, scope, data["last_closed"], data["future"][0], horizon, fc.VERSION)
    try:
        f = fc.Forecaster(data["weeks"], data["future"], data["series"], min_history=settings.planning_min_history_weeks, selection_origins=settings.planning_backtest_origins // 2 or 1,
                          step=settings.planning_backtest_step_weeks, min_eval_obs=settings.planning_min_eval_observations, min_eval_units=settings.planning_min_eval_units)
        results = f.run()
        finals: list[fc.SeriesForecast] = results["series"]
        region_of = {s.location_id: s.region for s in data["series"]}
        evals = evaluation_rows(run_id, results, f, settings.planning_min_eval_observations, settings.planning_min_eval_units, data["skus"], region_of)
        n_ok = sum(1 for s in finals if s.status == "OK")
        n_fb = sum(1 for s in finals if s.status == "FALLBACK")
        n_ins = sum(1 for s in finals if s.status == "INSUFFICIENT_DATA")
        selection = {"chosen": results["chosen"], "table": results["selection"], "selection_origins": results["selection_origins"], "heldout_origin_week": data["weeks"][results["held_t0"]].isoformat() if results["held_t0"] >= 0 and results["held_t0"] < len(data["weeks"]) else None,
                     "interval": results["interval"], "heldout_by_bucket": results["held_by_bucket"], "closed_weeks": len(data["weeks"]), "baseline": fc.BASELINE}
        features = {"used": FEATURES_USED, "not_used": FEATURES_NOT_USED, "data_scope": scope, "sources": list(data["sources"]), "data_through": data["through"].isoformat(),
                    "history_weeks": len(data["weeks"]), "stockout_adjusted": any(s.stockout_days is not None for s in data["series"])}
        async with repo.pool.acquire() as conn, conn.transaction():
            await conn.copy_records_to_table("pln_forecast_series", records=[
                (uuid.UUID(run_id), uuid.UUID(s.product_id), s.location_id, s.method, s.bucket, s.confidence, s.status, s.reason, int(s.history_weeks), s.zero_share, dumps(s.inputs)) for s in finals],
                columns=("run_id", "product_id", "location_id", "method", "bucket", "confidence", "status", "reason", "history_weeks", "zero_share", "inputs"))
            recs_f = []
            for s in finals:
                if s.status == "INSUFFICIENT_DATA":
                    continue
                for h, w in enumerate(data["future"]):
                    recs_f.append((uuid.UUID(run_id), uuid.UUID(s.product_id), s.location_id, w, h + 1, _d(s.forecast[h]), _d(s.lo[h]), _d(s.hi[h]), _d(s.baseline[h])))
            await conn.copy_records_to_table("pln_forecast", records=recs_f, columns=("run_id", "product_id", "location_id", "week_start", "horizon", "forecast", "lo", "hi", "baseline"))
            if evals:
                await conn.copy_records_to_table("pln_eval", records=evals, columns=("run_id", "scope", "scope_key", "model", "horizon_group", "n_obs", "n_series", "actual_units", "wape", "bias", "mae",
                                                                                      "baseline_wape", "sufficient"))
            await conn.execute("UPDATE pln_forecast_run SET is_current = FALSE WHERE source = $1 AND is_current", scope)
            await conn.execute("UPDATE pln_forecast_run SET status='SUCCEEDED', n_series=$2, n_ok=$3, n_fallback=$4, n_insufficient=$5, selection=$6::jsonb, features=$7::jsonb, duration_ms=$8, is_current=TRUE WHERE id=$1",
                               uuid.UUID(run_id), len(finals), n_ok, n_fb, n_ins, dumps(selection), dumps(features), int((time.monotonic() - t_start) * 1000))
        return {"run_id": run_id, "status": "SUCCEEDED", "scope": scope, "series": len(finals), "ok": n_ok, "fallback": n_fb, "insufficient": n_ins, "forecast_rows": len(recs_f),
                "evaluation_rows": len(evals), "as_of_week": data["last_closed"].isoformat(), "first_week": data["future"][0].isoformat(), "duration_ms": int((time.monotonic() - t_start) * 1000),
                "chosen": results["chosen"]}
    except Exception as exc:
        log.exception("forecast run failed")
        await repo.pool.execute("UPDATE pln_forecast_run SET status='FAILED', error=$2 WHERE id=$1", uuid.UUID(run_id), f"{type(exc).__name__}: {str(exc)[:300]}")
        raise


def _d(x: float):
    from decimal import Decimal
    return Decimal(f"{float(x):.3f}")
