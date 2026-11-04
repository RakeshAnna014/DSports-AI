"""Batch pipeline: seed the mart, check data quality, train / evaluate / forecast, assess, persist.

    seed   catalogue + live price/stock (DSports REST) -> inv_sku; generate the POC history -> demand / stock / position / POs / events
    train  mart -> clean -> backtest candidates -> select -> forecast -> event impact + elasticity -> assess -> persist (is_current)

The assistant NEVER runs this: it reads the persisted results. `train` is a manual operation (CLI or POST /v1/inventory/models/train);
the design allows a scheduler to call the same function later.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from collections import defaultdict
from dataclasses import asdict
from datetime import date, timedelta

import numpy as np

from app.inventory import events as ev
from app.inventory import forecasting as F
from app.inventory import generator as G
from app.inventory import planning as P
from app.inventory import quality
from app.obs import metrics as obs_metrics
from app.inventory import scenarios as S
from app.shop.catalog import Catalog

log = logging.getLogger(__name__)
REGIONS = G.REGION_CODES


# ------------------------------------------------------------------------------------------------------ seed

async def seed(pool, catalog: Catalog, as_of: date | None = None) -> dict:
    """(Re)build the mart: live catalogue facts + POC reference + POC synthetic history. Idempotent (replaces every inv_* data table)."""
    as_of = as_of or date.today()
    tax = await catalog.taxonomy()
    products = await catalog.list_products()
    ids = [p["id"] for p in products]
    prices, stock = await catalog.prices(ids), await catalog.stock(ids)
    skus = [G.SkuRef(p["id"], p["sku"], p["name"], tax.sports.get(p["sportId"], "?"), tax.categories.get(p["categoryId"], "?"),
                     tax.brands.get(p.get("brandId")), float(prices[p["id"]].selling_price), prices[p["id"]].mrp,
                     stock[p["id"]].available_quantity if p["id"] in stock else None)
            for p in products if p["id"] in prices]
    return await seed_skus(pool, skus, as_of)


async def seed_skus(pool, skus: list[G.SkuRef], as_of: date) -> dict:
    """The catalogue-independent half of `seed`: generate the POC history for `skus` and load every inv_* data table."""
    started = time.perf_counter()
    h = G.generate(skus, as_of)
    gen_ms = int((time.perf_counter() - started) * 1000)
    ref = {r["product_id"]: r for r in h.reference}
    async with pool.acquire() as conn, conn.transaction():
        for table in ("inv_forecast", "inv_assessment", "inv_event_impact", "inv_elasticity", "inv_region_share", "inv_model_run",
                      "inv_demand_daily", "inv_stock_snapshot", "inv_position", "inv_purchase_order", "inv_event", "inv_region", "inv_sku",
                      "inv_scenario_run", "inv_dq_report"):
            await conn.execute(f"TRUNCATE {table} CASCADE")
        await conn.executemany("INSERT INTO inv_region (code, name, data_source) VALUES ($1,$2,$3)",
                               [(c, n, G.REFERENCE_SOURCE) for c, n in G.REGIONS.items()])
        await conn.executemany(
            """INSERT INTO inv_sku (product_id, sku, name, sport, category, brand, price, mrp, cost, supplier, lead_time_days, moq, case_pack,
                                    dsports_available, data_source) VALUES ($1::uuid,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,'POC_REFERENCE')""",
            [(s.product_id, s.sku, s.name, s.sport, s.category, s.brand, s.price, s.mrp, ref[s.product_id]["cost"], ref[s.product_id]["supplier"],
              ref[s.product_id]["lead_time_days"], ref[s.product_id]["moq"], ref[s.product_id]["case_pack"], s.dsports_available) for s in skus])
        await conn.executemany(
            "INSERT INTO inv_event (id, name, event_type, sport, start_date, end_date, popularity, regions, data_source) VALUES ($1,$2,$3,$4,$5,$6,$7,$8::jsonb,$9)",
            [(e["id"], e["name"], e["event_type"], e["sport"], e["start_date"], e["end_date"], e["popularity"], json.dumps(e["regions"]),
              e["data_source"]) for e in h.events])
        event_ids = [e["id"] for e in h.events]
        rows = []
        for p, s in enumerate(skus):
            for r, code in enumerate(REGIONS):
                units, so, miss = h.demand[p, r], h.stockout[p, r], h.missing[p, r]
                for t, day in enumerate(h.days):
                    if miss[t]:
                        continue                                    # injected defect: the day is absent from the mart
                    k = int(h.event_idx[p, t])
                    rows.append((s.product_id and uuid.UUID(s.product_id), code, day, int(units[t]), float(h.unit_price[p, t]),
                                 float(h.discount[p, t]), event_ids[k] if k >= 0 else None, bool(so[t]), G.DATA_SOURCE))
        await conn.copy_records_to_table("inv_demand_daily", records=rows, columns=[
            "product_id", "region", "day", "units", "unit_price", "discount_pct", "event_id", "stockout", "data_source"])
        snap_days = h.days[-G.SNAPSHOT_DAYS:]
        snaps = [(uuid.UUID(s.product_id), code, d, int(h.on_hand[p, r, i]), int(h.incoming[p, r, i]), G.DATA_SOURCE)
                 for p, s in enumerate(skus) for r, code in enumerate(REGIONS) for i, d in enumerate(snap_days)]
        await conn.copy_records_to_table("inv_stock_snapshot", records=snaps, columns=["product_id", "region", "day", "on_hand", "incoming", "data_source"])
        await conn.executemany(
            "INSERT INTO inv_position (product_id, region, on_hand, oldest_age_days, avg_age_days, last_receipt, as_of, data_source) VALUES ($1::uuid,$2,$3,$4,$5,$6,$7,$8)",
            [(s.product_id, code, h.position[p][r]["on_hand"], h.position[p][r]["oldest_age_days"], h.position[p][r]["avg_age_days"],
              h.position[p][r]["last_receipt"], as_of, G.DATA_SOURCE) for p, s in enumerate(skus) for r, code in enumerate(REGIONS)])
        await conn.executemany(
            "INSERT INTO inv_purchase_order (id, product_id, region, qty, ordered_on, eta, status, data_source) VALUES ($1,$2::uuid,$3,$4,$5,$6,'OPEN',$7)",
            [(uuid.uuid4(), o["product_id"], o["region"], o["qty"], o["ordered_on"], o["eta"], G.DATA_SOURCE) for o in h.purchase_orders])
    return {"as_of": as_of.isoformat(), "skus": len(skus), "history_days": G.HISTORY_DAYS, "demand_rows": len(rows), "snapshot_rows": len(snaps),
            "open_purchase_orders": len(h.purchase_orders), "events": len(h.events), "generation_ms": gen_ms, "data_source": G.DATA_SOURCE,
            "scripted_situations": {k: sum(1 for v in h.situations.values() if v == k) for k in (G.AT_RISK, G.OVERSTOCK, G.SLOW, G.NORMAL)}}


# ------------------------------------------------------------------------------------------------------ load

class Mart:
    """The mart as dense arrays (P, R, D). NaN = the day is absent from the mart."""

    def __init__(self) -> None:
        self.skus: list[dict] = []
        self.index: dict[str, int] = {}
        self.days: list[date] = []
        self.units = np.zeros(0)
        self.stockout = np.zeros(0, dtype=bool)
        self.discount = np.zeros(0)
        self.event_idx = np.zeros(0, dtype=int)
        self.events: list[dict] = []
        self.positions: dict[str, dict[str, dict]] = {}
        self.arrivals: dict[str, dict[str, list[tuple[int, int]]]] = {}
        self.as_of: date | None = None


async def load_mart(pool) -> Mart:
    m = Mart()
    m.skus = [dict(r) | {"product_id": str(r["product_id"]), "price": float(r["price"]), "mrp": None if r["mrp"] is None else float(r["mrp"]),
                         "cost": None if r["cost"] is None else float(r["cost"])}
              for r in await pool.fetch("SELECT * FROM inv_sku ORDER BY sport, category, name")]
    if not m.skus:
        return m
    m.index = {s["product_id"]: i for i, s in enumerate(m.skus)}
    m.events = [dict(r) | {"regions": json.loads(r["regions"]) if isinstance(r["regions"], str) else r["regions"], "popularity": float(r["popularity"])}
                for r in await pool.fetch("SELECT * FROM inv_event ORDER BY start_date")]
    ev_index = {e["id"]: i for i, e in enumerate(m.events)}
    bounds = await pool.fetchrow("SELECT min(day) AS lo, max(day) AS hi FROM inv_demand_daily")
    m.days = [bounds["lo"] + timedelta(days=i) for i in range((bounds["hi"] - bounds["lo"]).days + 1)]
    P_, R_, D_ = len(m.skus), len(REGIONS), len(m.days)
    m.units = np.full((P_, R_, D_), np.nan)
    m.stockout = np.zeros((P_, R_, D_), dtype=bool)
    m.discount = np.zeros((P_, D_))
    m.event_idx = np.full((P_, D_), -1, dtype=int)
    rindex = {c: i for i, c in enumerate(REGIONS)}
    d0 = bounds["lo"]
    async with pool.acquire() as conn:
        async with conn.transaction():
            async for r in conn.cursor("SELECT product_id, region, day, units, discount_pct, event_id, stockout FROM inv_demand_daily", prefetch=20000):
                p, ri, t = m.index[str(r["product_id"])], rindex[r["region"]], (r["day"] - d0).days
                m.units[p, ri, t] = r["units"]
                m.stockout[p, ri, t] = r["stockout"]
                m.discount[p, t] = max(m.discount[p, t], float(r["discount_pct"]))
                if r["event_id"]:
                    m.event_idx[p, t] = ev_index[r["event_id"]]
    for r in await pool.fetch("SELECT * FROM inv_position"):
        m.positions.setdefault(str(r["product_id"]), {})[r["region"]] = dict(r)
        m.as_of = r["as_of"]
    for r in await pool.fetch("SELECT product_id, region, qty, eta FROM inv_purchase_order WHERE status = 'OPEN'"):
        m.arrivals.setdefault(str(r["product_id"]), {}).setdefault(r["region"], []).append(((r["eta"] - m.as_of).days, r["qty"]))
    return m


# ------------------------------------------------------------------------------------------------------ train

def _trend_pct(y: np.ndarray) -> float | None:
    if len(y) < 112:
        return None
    recent, before = float(y[-28:].mean()), float(y[-84:-28].mean())
    return None if before <= 0.05 else 100.0 * (recent / before - 1.0)


async def train(pool, *, actor_hash: str | None = None, correlation_id: str | None = None, horizon: int = F.HORIZON) -> dict:
    started = time.perf_counter()
    m = await load_mart(pool)
    if not m.skus or m.as_of is None:
        raise ValueError("The inventory mart is empty: run `python -m app.inventory.cli seed` first.")
    start = m.days[0]
    series: dict[str, F.Cleaned] = {}
    shares: dict[str, np.ndarray] = {}
    for pid, p in m.index.items():
        tot = np.nansum(m.units[p], axis=1)
        sh = tot / tot.sum() if tot.sum() > 0 else np.full(len(REGIONS), 1 / len(REGIONS))
        shares[pid] = sh
        series[pid] = F.clean_series(m.units[p], m.stockout[p], m.discount[p], m.event_idx[p], sh)

    dq = quality.report(m, series)
    dq["checks"].append(await quality.snapshot_check(pool))
    dq["issue_count"] = sum(c["status"] in ("WARN", "FAIL") for c in dq["checks"])
    # ---- model selection on the selection origins, held-out accuracy on the holdout origin
    eligible = {pid: c for pid, c in series.items() if int((~c.mask).sum()) >= F.MIN_CLEAN_DAYS and len(c.y) >= 120}
    sel_bt, sigma = await asyncio.to_thread(F.run_backtest, eligible, start, F.SELECTION_ORIGINS, horizon)   # CPU-bound: keep the event loop free
    sel = F.pooled_metrics(sel_bt)
    best = min(F.MODEL_NAMES, key=lambda k: (sel[k].wape if sel[k].n_days and not np.isnan(sel[k].wape) else float("inf")))   # NaN (all-zero actuals) never wins
    hold_bt, _ = await asyncio.to_thread(F.run_backtest, eligible, start, (F.HOLDOUT_ORIGIN,), horizon)
    hold = F.pooled_metrics(hold_bt)
    q = F.interval_quantiles(sel_bt, best)
    metrics_json = {
        "selection_origins_days_before_end": list(F.SELECTION_ORIGINS), "holdout_origin_days_before_end": F.HOLDOUT_ORIGIN,
        "selection": {k: _m(sel[k]) | {"sum_wape": {str(h): _r(F.sum_wape(sel_bt, k, h)) for h in F.SUM_HORIZONS}} for k in F.MODEL_NAMES},
        "holdout": {k: _m(hold[k]) | {"sum_wape": {str(h): _r(F.sum_wape(hold_bt, k, h)) for h in F.SUM_HORIZONS}} for k in F.MODEL_NAMES},
        "selected": best, "baseline": "moving_average_28",
        "holdout_improvement_vs_baseline_pct": _r(100 * (1 - hold[best].wape / hold["moving_average_28"].wape)) if hold["moving_average_28"].wape else None,
        "interval_coverage_holdout": {str(h): _r(F.coverage(hold_bt, q, best, h)) for h in F.SUM_HORIZONS}, "interval_nominal": 0.8,
        "series_modelled": len(eligible), "series_total": len(series),
        "note": "Accuracy is measured on POC SYNTHETIC history; it shows the pipeline works, not how accurate it would be on real DSports demand.",
    }
    # ---- final forecasts
    run_id = uuid.uuid4()
    fitted = {pid: F.ridge_insample(c.y, start) for pid, c in series.items()}
    forecasts: dict[str, np.ndarray | None] = {}
    sigma_daily: dict[str, float] = {}
    pooled_sigma = float(np.nanmedian([v[best] for v in sigma.values() if not np.isnan(v.get(best, np.nan))] or [1.0]))
    for pid, c in series.items():
        if pid not in eligible:
            forecasts[pid] = None
            continue
        forecasts[pid] = F.predict(best, c.y, start, horizon)
        s = sigma.get(pid, {}).get(best, float("nan"))
        sigma_daily[pid] = pooled_sigma if np.isnan(s) else s

    # ---- events and promotion elasticity
    per_product = [{"sport": s["sport"], "category": s["category"], "raw": series[s["product_id"]].raw, "fitted": fitted[s["product_id"]],
                    "unusable": series[s["product_id"]].unusable, "discount": m.discount[m.index[s["product_id"]]],
                    "event": m.event_idx[m.index[s["product_id"]]] >= 0} for s in m.skus]
    past = [(e, ev.measure_event(e, m.days, per_product)) for e in m.events if e["end_date"] < m.as_of]
    upcoming = [e for e in m.events if e["start_date"] >= m.as_of]
    categories_by_sport: dict[str, list[str]] = defaultdict(list)
    for s in m.skus:
        if s["category"] not in categories_by_sport[s["sport"]]:
            categories_by_sport[s["sport"]].append(s["category"])
    impact_rows, impact_by_event = [], {}
    for e, evidence in past:
        for x in evidence:
            impact_rows.append((run_id, e["id"], x.sport, x.category, x.uplift, x.lo, x.hi, 1, x.n_products, "HISTORICAL", "{}"))
    for e in upcoming:
        est = ev.estimate_upcoming(e, past, categories_by_sport[e["sport"]])
        impact_by_event[e["id"]] = est
        for cat, v in est.items():
            impact_rows.append((run_id, e["id"], e["sport"], cat, v["uplift"], v["lo"], v["hi"], v["n_events"], v["n_products"], v["basis"],
                                json.dumps({k: v[k] for k in v if k in ("note", "same_event_type")})))
    by_cat: dict[str, list[dict]] = defaultdict(list)
    for s, pp in zip(m.skus, per_product, strict=True):
        by_cat[s["category"]].append(pp)
    elasticity = {c: S.estimate_elasticity(c, rows) for c, rows in by_cat.items()}

    # ---- assessments (baseline + one per upcoming event, per product of the event's sport)
    def interval_fn(h: int, total: float):
        return F.interval_for_sum(total, total / max(h, 1), h, q)

    assessments: list[tuple[str, str, P.Assessment]] = []
    for s in m.skus:
        pid = s["product_id"]
        pos_by_region = m.positions.get(pid, {})
        on_hand = sum(r["on_hand"] for r in pos_by_region.values())
        arrivals: dict[int, int] = {}
        for lst in m.arrivals.get(pid, {}).values():
            for k, qty in lst:
                arrivals[k] = arrivals.get(k, 0) + qty
        position = P.Position(on_hand, tuple(sorted(arrivals.items())), max((r["oldest_age_days"] for r in pos_by_region.values()), default=0),
                              float(np.average([float(r["avg_age_days"]) for r in pos_by_region.values()], weights=[max(r["on_hand"], 1) for r in pos_by_region.values()]))
                              if pos_by_region else 0.0)
        ref = P.Reference(s["lead_time_days"], s["moq"], s["case_pack"], float(s["cost"]) if s["cost"] is not None else None, float(s["price"]))
        trend = _trend_pct(series[pid].y)
        base = forecasts[pid]
        assessments.append((pid, "BASELINE", P.assess(m.as_of, position, ref, base, sigma_daily.get(pid), interval_fn, trend_pct=trend)))
        if base is None:
            continue
        for e in upcoming:
            if e["sport"] != s["sport"] or e["id"] not in impact_by_event:
                continue
            u = impact_by_event[e["id"]].get(s["category"])
            if not u or (e["start_date"] - m.as_of).days >= horizon:
                continue
            adj = ev.apply_to_forecast(base, m.as_of, e, u["uplift"])
            a = P.assess(m.as_of, position, ref, adj, sigma_daily.get(pid), interval_fn, trend_pct=trend)
            a.detail["event"] = {"id": e["id"], "name": e["name"], "uplift": u["uplift"], "uplift_range": [u["lo"], u["hi"]], "basis": u["basis"],
                                 "window": [e["start_date"].isoformat(), e["end_date"].isoformat()]}
            assessments.append((pid, f"EVENT:{e['id']}", a))

    duration_ms = int((time.perf_counter() - started) * 1000)
    async with pool.acquire() as conn, conn.transaction():
        await conn.execute("UPDATE inv_model_run SET is_current = FALSE WHERE is_current")
        await conn.execute(
            """INSERT INTO inv_model_run (id, as_of, model_name, model_version, training_start, training_end, horizon_days, n_series, interval_scale,
                                          metrics, features, data_source, duration_ms, is_current)
               VALUES ($1,$2,$3,$4,$5,$6,$7,$8,1.0,$9::jsonb,$10::jsonb,$11,$12,TRUE)""",
            run_id, m.as_of, best, F.MODEL_VERSION, m.days[0], m.days[-1], horizon, len(eligible), json.dumps(metrics_json),
            json.dumps({"cleaning": "stock-out, promotion (+7 day dip), event and missing days imputed by weekday median",
                        "ridge": "log1p(units) ~ trend + weekday + annual Fourier(2) (when >= 400 days)", "service_level": 0.95,
                        "review_days": P.REVIEW_DAYS}), G.DATA_SOURCE, duration_ms)
        fc_rows = []
        for s in m.skus:
            pid = s["product_id"]
            c = series[pid]
            f = forecasts[pid]
            if f is None:
                fc_rows.append((run_id, uuid.UUID(pid), best, F.MODEL_VERSION, m.days[0], m.days[-1], "INSUFFICIENT_DATA",
                                "Insufficient data to produce a reliable forecast.", None, None, None, None, int((~c.mask).sum()), None))
                continue
            sd = sigma_daily[pid]
            lo = np.maximum(f - 1.2816 * sd, 0.0)
            hi = f + 1.2816 * sd
            wape = None
            fc_rows.append((run_id, uuid.UUID(pid), best, F.MODEL_VERSION, m.days[0], m.days[-1], "OK", None, f.round(3).tolist(), lo.round(3).tolist(),
                            hi.round(3).tolist(), float(sd), int((~c.mask).sum()), wape))
        await conn.executemany(
            """INSERT INTO inv_forecast (run_id, product_id, model_name, model_version, training_start, training_end, status, reason, daily_mean,
                                         daily_lo, daily_hi, sigma_daily, history_days, wape_backtest) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)""", fc_rows)
        await conn.executemany("INSERT INTO inv_event_impact (run_id, event_id, sport, category, uplift, uplift_lo, uplift_hi, n_events, n_products, basis, detail) "
                               "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11::jsonb)", impact_rows)
        await conn.executemany("INSERT INTO inv_elasticity (run_id, category, elasticity, elasticity_lo, elasticity_hi, pull_forward, n_promo_days, basis) "
                               "VALUES ($1,$2,$3,$4,$5,$6,$7,$8)",
                               [(run_id, c, -e.e, -e.hi, -e.lo, e.pull_forward, e.n_promo_days, e.basis) for c, e in elasticity.items()])
        await conn.executemany("INSERT INTO inv_region_share (run_id, product_id, region, share) VALUES ($1,$2,$3,$4)",
                               [(run_id, uuid.UUID(pid), REGIONS[r], float(sh[r])) for pid, sh in shares.items() for r in range(len(REGIONS))])
        await conn.executemany(_ASSESS_SQL, [_assess_row(run_id, pid, scen, m.as_of, a) for pid, scen, a in assessments])
        await conn.execute("INSERT INTO inv_dq_report (id, report) VALUES ($1,$2::jsonb)", uuid.uuid4(), json.dumps(dq, default=str))
        await conn.execute("INSERT INTO inv_audit (actor_hash, action, detail, correlation_id) VALUES ($1,'TRAIN',$2::jsonb,$3)", actor_hash,
                           json.dumps({"run_id": str(run_id), "model": best, "series": len(eligible), "duration_ms": duration_ms}), correlation_id)
    obs_metrics.INV_TRAIN_SECONDS.observe(duration_ms / 1000)
    if hold[best].n_days:
        obs_metrics.INV_HOLDOUT_WAPE.labels(best).set(hold[best].wape)
    return {"run_id": str(run_id), "as_of": m.as_of.isoformat(), "model": best, "model_version": F.MODEL_VERSION, "duration_ms": duration_ms,
            "metrics": metrics_json, "assessments": len(assessments), "events": [e["id"] for e in upcoming], "data_quality_issues": dq["issue_count"]}


def _m(x: F.Metrics) -> dict:
    return {"wape": _r(x.wape), "mae": _r(x.mae), "rmse": _r(x.rmse), "mape": _r(x.mape), "bias": _r(x.bias), "n_days": x.n_days}


def _r(v: float | None) -> float | None:
    return None if v is None or (isinstance(v, float) and np.isnan(v)) else round(float(v), 4)


_ASSESS_COLS = ["run_id", "product_id", "scenario", "as_of", "status", "reason", "on_hand", "incoming", "lead_time_days", "daily_demand", "forecast_7",
                "forecast_30", "forecast_90", "days_of_cover", "stockout_date", "p_stockout_lt", "safety_stock", "reorder_point", "risk_level",
                "reorder_qty", "order_by", "arrival_date", "priority", "excess_units", "excess_value", "coverage_days", "overstock_level",
                "health_class", "age_days", "detail"]
_ASSESS_SQL = (f"INSERT INTO inv_assessment ({', '.join(_ASSESS_COLS)}) VALUES (" +
               ", ".join(f"${i + 1}" + ("::jsonb" if c == "detail" else "") for i, c in enumerate(_ASSESS_COLS)) + ")")


def _assess_row(run_id, pid: str, scenario: str, as_of: date, a: P.Assessment) -> tuple:
    return (run_id, uuid.UUID(pid), scenario, as_of, a.status, a.reason, a.on_hand, a.incoming, a.lead_time_days, a.daily_demand, a.forecast_7,
            a.forecast_30, a.forecast_90, a.days_of_cover, a.stockout_date, a.p_stockout_lt, a.safety_stock, a.reorder_point, a.risk_level,
            a.reorder_qty, a.order_by, a.arrival_date, a.priority, a.excess_units, a.excess_value, a.coverage_days, a.overstock_level,
            a.health_class, a.age_days, json.dumps(a.detail, default=str))
