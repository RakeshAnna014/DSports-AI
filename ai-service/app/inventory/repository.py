"""Read side of Inventory Intelligence: everything is served from the persisted run (inv_*), never recomputed by a model per question.
The only live calculation is the what-if scenario engine, which is arithmetic over the persisted forecast.

All results are JSON-ready dicts. Every payload carries `data_source` so the UI and the assistant can say what the numbers are.
"""
from __future__ import annotations

import json
import uuid
from datetime import date, timedelta
from typing import Any

import numpy as np

from app.inventory import planning as P
from app.inventory import scenarios as S
from app.inventory.generator import DATA_SOURCE, REGIONS
from app.obs import metrics as obs_metrics

SOURCE_NOTE = ("POC SYNTHETIC history and POC reference data (cost, supplier, lead time, regions, events). "
               "Current catalogue price and stock levels are DSports reference data; the planning position is a POC scenario.")


class Rows(list):
    """A page of results that remembers how many rows matched in total (so 'top 10 of 13' is never reported as '10')."""
    total: int = 0


class InventoryUnavailable(Exception):
    """No trained run exists (or the mart is empty). Carries a business-readable message."""


class InvalidRequest(Exception):
    """The request itself is impossible (a 95% discount, 0 days, a negative number): a client error, not missing data."""


def _f(v: Any) -> Any:
    if v is None:
        return None
    if isinstance(v, (date,)):
        return v.isoformat()
    if hasattr(v, "quantize"):                # Decimal
        return float(v)
    if isinstance(v, uuid.UUID):
        return str(v)
    return v


def _row(r) -> dict:
    return {k: _f(v) for k, v in dict(r).items()}


class InventoryRepository:
    def __init__(self, pool):
        self.pool = pool
        self._run: dict | None = None

    # ------------------------------------------------------------------------------------------ run / metadata
    async def provenance(self) -> dict:
        """Where the numbers of a response come from: the persisted model run (forecast, elasticity, event impact, positions) and the data labels."""
        meta = await self.run()
        return {"run_id": meta["id"], "model_name": meta["model_name"], "model_version": meta["model_version"], "as_of": meta["as_of"],
                "training_start": meta["training_start"], "training_end": meta["training_end"],
                "demand_history": "POC_SYNTHETIC", "stock_positions_and_purchase_orders": "POC_SYNTHETIC",
                "cost_supplier_lead_time_moq_regions_events": "POC_REFERENCE", "catalogue_and_prices": "DSPORTS_LIVE (snapshot taken when the mart was seeded)"}

    async def run(self) -> dict:
        r = await self.pool.fetchrow("SELECT * FROM inv_model_run WHERE is_current ORDER BY created_at DESC LIMIT 1")
        if r is None:
            raise InventoryUnavailable("No inventory forecast exists yet. Seed the data and run training first (POST /v1/inventory/models/train).")
        d = _row(r)
        d["id"] = str(r["id"])
        d["metrics"] = json.loads(r["metrics"]) if isinstance(r["metrics"], str) else r["metrics"]
        d["features"] = json.loads(r["features"]) if isinstance(r["features"], str) else r["features"]
        d["created_at"] = r["created_at"].isoformat()
        return d

    async def _run_id(self) -> uuid.UUID:
        return uuid.UUID((await self.run())["id"])

    async def model_info(self) -> dict:
        run = await self.run()
        return {"run_id": run["id"], "model_name": run["model_name"], "model_version": run["model_version"], "as_of": run["as_of"],
                "training_start": run["training_start"], "training_end": run["training_end"], "horizon_days": run["horizon_days"],
                "series": run["n_series"], "created_at": run["created_at"], "metrics": run["metrics"], "features": run["features"],
                "data_source": run["data_source"], "note": SOURCE_NOTE}

    async def data_quality(self) -> dict:
        r = await self.pool.fetchrow("SELECT report, created_at FROM inv_dq_report ORDER BY created_at DESC LIMIT 1")
        if r is None:
            raise InventoryUnavailable("No data-quality report exists yet.")
        rep = json.loads(r["report"]) if isinstance(r["report"], str) else r["report"]
        return rep | {"created_at": r["created_at"].isoformat()}

    # ------------------------------------------------------------------------------------------ resolve
    async def resolve(self, *, sport: str | None = None, category: str | None = None, product: str | None = None,
                      product_id: str | None = None) -> list[dict]:
        where, args = ["TRUE"], []
        if product_id:
            try:
                args.append(uuid.UUID(product_id))
            except ValueError:
                raise InvalidRequest("product_id must be a product id (a UUID)") from None
            where.append(f"product_id = ${len(args)}")
        for col, val in (("sport", sport), ("category", category)):
            if val:
                args.append(val)
                where.append(f"lower({col}) = lower(${len(args)})")
        if product:
            args.append(f"%{product}%")
            where.append(f"(name ILIKE ${len(args)} OR sku ILIKE ${len(args)})")
        rows = await self.pool.fetch(f"SELECT product_id, sku, name, sport, category, brand, price, cost, supplier, lead_time_days, moq, case_pack "
                                     f"FROM inv_sku WHERE {' AND '.join(where)} ORDER BY sport, category, name", *args)
        return [_row(r) for r in rows]

    async def taxonomy(self) -> dict:
        rows = await self.pool.fetch("SELECT sport, category, count(*) n FROM inv_sku GROUP BY 1,2 ORDER BY 1,2")
        out: dict[str, list[str]] = {}
        for r in rows:
            out.setdefault(r["sport"], []).append(r["category"])
        return out

    # ------------------------------------------------------------------------------------------ overview
    async def overview(self, scenario: str = "BASELINE") -> dict:
        run = await self._run_id()
        r = await self.pool.fetchrow(
            """SELECT count(*) AS total,
                      count(*) FILTER (WHERE status = 'INSUFFICIENT_DATA') AS insufficient,
                      count(*) FILTER (WHERE risk_level IN ('HIGH','MEDIUM')) AS at_risk,
                      count(*) FILTER (WHERE risk_level = 'HIGH') AS stockout_high,
                      count(*) FILTER (WHERE risk_level = 'MEDIUM') AS stockout_medium,
                      count(*) FILTER (WHERE overstock_level IN ('HIGH','MEDIUM')) AS overstock,
                      count(*) FILTER (WHERE health_class IN ('Slow Moving','Critical Aging')) AS slow_moving,
                      count(*) FILTER (WHERE age_days > 90) AS aging_over_90,
                      count(*) FILTER (WHERE health_class = 'Healthy') AS healthy,
                      count(*) FILTER (WHERE health_class = 'Watch') AS watch,
                      count(*) FILTER (WHERE health_class = 'Slow Moving') AS slow_only,
                      count(*) FILTER (WHERE health_class = 'Critical Aging') AS critical_aging,
                      coalesce(sum(excess_units),0) AS excess_units, coalesce(sum(excess_value),0) AS excess_value,
                      coalesce(sum(reorder_qty),0) AS reorder_units,
                      count(*) FILTER (WHERE reorder_qty > 0) AS replenish_skus
               FROM inv_assessment WHERE run_id = $1 AND scenario = $2""", run, scenario)
        stock = await self.pool.fetchrow(
            "SELECT coalesce(sum(a.on_hand * s.cost),0) AS value, coalesce(sum(a.on_hand),0) AS units FROM inv_assessment a JOIN inv_sku s USING (product_id) "
            "WHERE a.run_id=$1 AND a.scenario='BASELINE'", run)
        events = await self.events()
        out = _row(r) | {"inventory_value_at_cost": float(stock["value"]), "inventory_units": int(stock["units"]), "scenario": scenario}
        out["clearance_candidates"] = int(await self.pool.fetchval(
            "SELECT count(*) FROM inv_assessment WHERE run_id=$1 AND scenario='BASELINE' AND overstock_level IN ('HIGH','MEDIUM') "
            "AND health_class IN ('Slow Moving','Critical Aging')", run))
        out["upcoming_events"] = [e for e in events if e["status"] == "UPCOMING"]
        out["model"] = await self.model_info()
        out["data_source"] = DATA_SOURCE
        out["data_note"] = SOURCE_NOTE
        return out

    # ------------------------------------------------------------------------------------------ assessments
    _SELECT = ("SELECT a.*, s.sku, s.name, s.sport, s.category, s.brand, s.price, s.cost, s.supplier, s.moq, s.case_pack "
               "FROM inv_assessment a JOIN inv_sku s USING (product_id)")

    async def _assessments(self, scenario: str, where: str = "TRUE", args: list | None = None, order: str = "s.name", limit: int = 50) -> list[dict]:
        run = await self._run_id()
        args = [run, scenario] + (args or [])
        rows = await self.pool.fetch(f"{self._SELECT} WHERE a.run_id = $1 AND a.scenario = $2 AND {where} ORDER BY {order} LIMIT 500", *args)
        out = Rows()
        for r in rows[:int(limit)]:
            d = _row(r)
            det = r["detail"]
            d["detail"] = json.loads(det) if isinstance(det, str) else det
            out.append(d)
        out.total = len(rows)
        return out

    def _filters(self, sport, category, args: list, where: list) -> None:
        for col, val in (("sport", sport), ("category", category)):
            if val:
                args.append(val)
                where.append(f"lower(s.{col}) = lower(${len(args) + 2})")

    async def stock_risks(self, *, sport: str | None = None, category: str | None = None, level: str | None = None, scenario: str = "BASELINE",
                          within_days: int | None = None, limit: int = 20) -> list[dict]:
        args: list = []
        where = ["a.risk_level IS NOT NULL"]
        self._filters(sport, category, args, where)
        if level:
            args.append(level.upper())
            where.append(f"a.risk_level = ${len(args) + 2}")
        if within_days is not None:
            args.append(float(within_days))
            where.append(f"a.days_of_cover IS NOT NULL AND a.days_of_cover <= ${len(args) + 2}")
        order = ("CASE a.risk_level WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, a.p_stockout_lt DESC NULLS LAST, "
                 "a.days_of_cover NULLS LAST, s.name")
        if level is None:                      # "at risk" means HIGH or MEDIUM, with or without a time window
            where.append("a.risk_level IN ('HIGH','MEDIUM')")
        return await self._assessments(scenario, " AND ".join(where), args, order, limit)

    async def replenishment(self, *, sport: str | None = None, category: str | None = None, scenario: str = "BASELINE", limit: int = 30,
                            order_within_days: int | None = None) -> list[dict]:
        args: list = []
        where = ["(a.reorder_qty > 0 OR (a.detail->>'action') = 'EXPEDITE_INCOMING')"]
        self._filters(sport, category, args, where)
        if order_within_days is not None:
            args.append(date.fromisoformat((await self.run())["as_of"]))
            where.append(f"a.order_by <= (${len(args) + 2}::date + {int(order_within_days)})")
        order = ("CASE a.priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, a.order_by NULLS LAST, a.days_of_cover NULLS LAST")
        return await self._assessments(scenario, " AND ".join(where), args, order, limit)

    async def overstock(self, *, sport: str | None = None, category: str | None = None, min_age_days: int | None = None,
                        health: list[str] | None = None, limit: int = 30) -> list[dict]:
        args: list = []
        where = ["a.status <> 'INSUFFICIENT_DATA'"]
        self._filters(sport, category, args, where)
        if min_age_days is not None:
            args.append(int(min_age_days))
            where.append(f"a.age_days > ${len(args) + 2}")
        if health:
            args.append(health)
            where.append(f"a.health_class = ANY(${len(args) + 2}::text[])")
        if min_age_days is None and not health:
            where.append("a.overstock_level IN ('HIGH','MEDIUM')")
        return await self._assessments("BASELINE", " AND ".join(where), args, "a.excess_value DESC NULLS LAST, a.excess_units DESC", limit)

    async def aging_distribution(self, *, sport: str | None = None, category: str | None = None) -> dict:
        """How many SKUs (and how much stock) sit in each age bucket of their OLDEST units, over ALL products in the selection."""
        args: list = []
        where = ["a.status <> 'INSUFFICIENT_DATA'"]
        self._filters(sport, category, args, where)
        rows = await self._assessments("BASELINE", " AND ".join(where), args, "s.name", 500)
        buckets = [("0-29 days", 0, 30), ("30-59 days", 30, 60), ("60-89 days", 60, 90), ("90-119 days", 90, 120), ("120+ days", 120, 10**9)]
        out = [{"bucket": name, "skus": 0, "units": 0, "value": 0.0} for name, _, _ in buckets]
        for r in rows:
            age = r["age_days"] or 0
            for i, (_, lo, hi) in enumerate(buckets):
                if lo <= age < hi:
                    out[i]["skus"] += 1
                    out[i]["units"] += int(r["on_hand"] or 0)
                    out[i]["value"] += float(r["on_hand"] or 0) * float(r["cost"] or 0)
                    break
        for o in out:
            o["value"] = round(o["value"], 2)
        return {"buckets": out, "total_skus": len(rows), "data_source": DATA_SOURCE}

    async def slow_moving(self, *, sport: str | None = None, category: str | None = None, limit: int = 30) -> list[dict]:
        return await self.overstock(sport=sport, category=category, health=["Slow Moving", "Critical Aging"], limit=limit)

    async def assessment(self, product_id: str, scenario: str = "BASELINE") -> dict | None:
        rows = await self._assessments(scenario, "a.product_id = $3::uuid", [product_id], "s.name", 1)
        return rows[0] if rows else None

    # ------------------------------------------------------------------------------------------ forecast
    async def forecast(self, product_id: str, history_days: int = 90) -> dict:
        run = await self._run_id()
        r = await self.pool.fetchrow("SELECT f.*, s.name, s.sku, s.sport, s.category FROM inv_forecast f JOIN inv_sku s USING (product_id) "
                                     "WHERE f.run_id = $1 AND f.product_id = $2::uuid", run, product_id)
        if r is None:
            raise InventoryUnavailable("No such product in the inventory data.")
        meta = await self.run()
        base = {"product_id": product_id, "name": r["name"], "sku": r["sku"], "sport": r["sport"], "category": r["category"], "status": r["status"],
                "model_name": r["model_name"], "model_version": r["model_version"], "training_start": _f(r["training_start"]),
                "training_end": _f(r["training_end"]), "forecast_created_at": meta["created_at"], "data_source": DATA_SOURCE}
        if r["status"] != "OK":
            return base | {"reason": r["reason"] or "Insufficient data to produce a reliable forecast.", "history": [], "daily": []}
        as_of = date.fromisoformat(meta["as_of"])
        mean = [float(x) for x in r["daily_mean"]]
        lo, hi = [float(x) for x in r["daily_lo"]], [float(x) for x in r["daily_hi"]]
        hist = await self.pool.fetch(
            "SELECT day, sum(units) AS units FROM inv_demand_daily WHERE product_id = $1::uuid AND day >= $2 GROUP BY day ORDER BY day",
            product_id, as_of - timedelta(days=history_days))
        a = await self.assessment(product_id)
        return base | {"as_of": meta["as_of"], "sigma_daily": float(r["sigma_daily"]), "history_days": r["history_days"],
                       "daily": [{"day": (as_of + timedelta(days=i)).isoformat(), "mean": round(mean[i], 2), "lo": round(lo[i], 2), "hi": round(hi[i], 2)}
                                 for i in range(len(mean))],
                       "history": [{"day": h["day"].isoformat(), "units": int(h["units"])} for h in hist],
                       "totals": {str(h): {"mean": round(sum(mean[:h]), 1)} for h in (7, 30, 90)},
                       "assessment": a}

    async def forecast_aggregate(self, *, sport: str | None = None, category: str | None = None, history_days: int = 120) -> dict:
        """Sum of the persisted product forecasts (and of history) for a sport / category: the chart on the Forecast tab."""
        run = await self._run_id()
        meta = await self.run()
        as_of = date.fromisoformat(meta["as_of"])
        where, args = ["f.run_id = $1", "f.status = 'OK'"], [run]
        for col, val in (("sport", sport), ("category", category)):
            if val:
                args.append(val)
                where.append(f"lower(s.{col}) = lower(${len(args)})")
        rows = await self.pool.fetch(f"SELECT f.daily_mean, f.daily_lo, f.daily_hi, f.product_id FROM inv_forecast f JOIN inv_sku s USING (product_id) WHERE {' AND '.join(where)}", *args)
        if not rows:
            raise InventoryUnavailable("Insufficient data to produce a reliable forecast for that selection.")
        mean = np.sum([np.array(r["daily_mean"]) for r in rows], axis=0)
        sigma = float(np.sqrt(np.sum([(np.array(r["daily_hi"]) - np.array(r["daily_mean"])) ** 2 for r in rows], axis=0).mean()) / 1.2816)
        ids = [r["product_id"] for r in rows]
        hist = await self.pool.fetch(
            "SELECT day, sum(units) AS units FROM inv_demand_daily WHERE product_id = ANY($1::uuid[]) AND day >= $2 GROUP BY day ORDER BY day",
            ids, as_of - timedelta(days=history_days))
        return {"sport": sport, "category": category, "products": len(rows), "as_of": meta["as_of"], "data_source": DATA_SOURCE,
                "model_name": meta["model_name"], "model_version": meta["model_version"],
                "daily": [{"day": (as_of + timedelta(days=i)).isoformat(), "mean": round(float(mean[i]), 1),
                           "lo": round(max(float(mean[i]) - 1.2816 * sigma, 0.0), 1), "hi": round(float(mean[i]) + 1.2816 * sigma, 1)} for i in range(len(mean))],
                "history": [{"day": h["day"].isoformat(), "units": int(h["units"])} for h in hist],
                "totals": {str(h): round(float(mean[:h].sum()), 1) for h in (7, 30, 90)}}

    # ------------------------------------------------------------------------------------------ usage / cost
    async def usage_summary(self, hours: int = 24 * 7) -> dict:
        """LLM usage of the inventory features (intent labels `inventory_*`), estimated cost, latency, and what the audit trail recorded."""
        rows = await self.pool.fetch(
            """SELECT intent, model, count(*) AS calls, coalesce(sum(tokens_in),0) AS tokens_in, coalesce(sum(tokens_out),0) AS tokens_out,
                      coalesce(sum(cost_estimate),0) AS cost, percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) AS p50,
                      percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95, count(*) FILTER (WHERE outcome <> 'ok') AS errors
               FROM usage WHERE intent LIKE 'inventory\\_%' AND ts > now() - make_interval(hours => $1) GROUP BY intent, model ORDER BY calls DESC""", hours)
        audit = await self.pool.fetch("SELECT action, count(*) AS n FROM inv_audit WHERE ts > now() - make_interval(hours => $1) GROUP BY action", hours)
        trains = await self.pool.fetch("SELECT created_at, model_name, duration_ms FROM inv_model_run ORDER BY created_at DESC LIMIT 5")
        total_calls = sum(r["calls"] for r in rows)
        return {"window_hours": hours, "llm_calls": total_calls, "tokens_in": int(sum(r["tokens_in"] for r in rows)), "tokens_out": int(sum(r["tokens_out"] for r in rows)),
                "estimated_cost_usd": round(float(sum(r["cost"] for r in rows)), 6), "cost_is_estimate": True,
                "by_intent": [{"intent": r["intent"], "model": r["model"], "calls": r["calls"], "tokens_in": int(r["tokens_in"]), "tokens_out": int(r["tokens_out"]),
                               "cost_usd": round(float(r["cost"]), 6), "p50_ms": round(float(r["p50"])), "p95_ms": round(float(r["p95"])), "errors": r["errors"]} for r in rows],
                "audit": {r["action"]: r["n"] for r in audit}, "recent_training_runs": [{"at": r["created_at"].isoformat(), "model": r["model_name"], "duration_ms": r["duration_ms"]} for r in trains],
                "note": "No language model is used for forecasting, arithmetic, sorting or scenarios: LLM calls are only for understanding (rare) and explanation."}

    # ------------------------------------------------------------------------------------------ history
    async def sales_history(self, *, sport: str | None = None, category: str | None = None, product_id: str | None = None, days: int = 90) -> dict:
        """Weekly units sold (history only, no model) with the change versus the previous equal period. Stock-out days under-state demand."""
        skus = await self.resolve(sport=sport, category=category, product_id=product_id)
        ids = [uuid.UUID(s["product_id"]) for s in skus]
        if not ids:
            raise InventoryUnavailable("No products match that selection.")
        as_of = date.fromisoformat((await self.run())["as_of"])
        rows = await self.pool.fetch(
            """SELECT date_trunc('week', day)::date AS week, sum(units) AS units, sum(units) FILTER (WHERE stockout) AS censored_units,
                      count(*) FILTER (WHERE stockout) AS stockout_days
               FROM inv_demand_daily WHERE product_id = ANY($1::uuid[]) AND day >= $2 GROUP BY 1 ORDER BY 1""", ids, as_of - timedelta(days=days * 2))
        cut = as_of - timedelta(days=days)
        recent = sum(int(r["units"]) for r in rows if r["week"] >= cut)
        before = sum(int(r["units"]) for r in rows if r["week"] < cut)
        return {"selection": {"sport": sport, "category": category, "product_id": product_id, "products": len(ids)}, "days": days,
                "weekly": [{"week": r["week"].isoformat(), "units": int(r["units"]), "stockout_days": int(r["stockout_days"])} for r in rows],
                "units_recent": recent, "units_previous": before,
                "change_pct": None if before == 0 else round(100.0 * (recent / before - 1.0), 1), "data_source": DATA_SOURCE,
                "note": "Units sold; days with stock-outs under-state demand."}

    async def stock_history(self, product_id: str, days: int = 60) -> dict:
        as_of = date.fromisoformat((await self.run())["as_of"])
        rows = await self.pool.fetch(
            "SELECT day, sum(on_hand) AS on_hand, sum(incoming) AS incoming FROM inv_stock_snapshot WHERE product_id = $1::uuid AND day >= $2 GROUP BY day ORDER BY day",
            product_id, as_of - timedelta(days=days))
        if not rows:
            raise InventoryUnavailable("No stock history exists for that product.")
        sku = (await self.resolve(product_id=product_id))[0]
        return {"product_id": product_id, "name": sku["name"], "days": days, "data_source": DATA_SOURCE,
                "series": [{"day": r["day"].isoformat(), "on_hand": int(r["on_hand"]), "incoming": int(r["incoming"])} for r in rows]}

    async def positions(self, *, sport: str | None = None, category: str | None = None, product: str | None = None, limit: int = 25) -> list[dict]:
        skus = await self.resolve(sport=sport, category=category, product=product)
        if not skus:
            return []
        run = await self._run_id()
        ids = [uuid.UUID(s["product_id"]) for s in skus][:200]
        rows = await self.pool.fetch(
            "SELECT p.product_id, p.region, p.on_hand, p.oldest_age_days, p.avg_age_days FROM inv_position p WHERE p.product_id = ANY($1::uuid[])", ids)
        a = {str(r["product_id"]): r for r in await self.pool.fetch(
            "SELECT product_id, incoming, days_of_cover, risk_level FROM inv_assessment WHERE run_id=$1 AND scenario='BASELINE' AND product_id = ANY($2::uuid[])", run, ids)}
        out: dict[str, dict] = {}
        for sku in skus[:200]:
            out[sku["product_id"]] = {"product_id": sku["product_id"], "name": sku["name"], "sport": sku["sport"], "category": sku["category"], "on_hand": 0,
                                       "oldest_age_days": 0, "by_region": {}, "dsports_live_available": None, "supplier": sku["supplier"],
                                       "lead_time_days": sku["lead_time_days"], "moq": sku["moq"], "case_pack": sku["case_pack"], "unit_cost": sku["cost"]}
        for r in rows:
            o = out[str(r["product_id"])]
            o["on_hand"] += int(r["on_hand"])
            o["oldest_age_days"] = max(o["oldest_age_days"], int(r["oldest_age_days"]))
            o["by_region"][r["region"]] = int(r["on_hand"])
        live = {str(r["product_id"]): r["dsports_available"] for r in await self.pool.fetch(
            "SELECT product_id, dsports_available FROM inv_sku WHERE product_id = ANY($1::uuid[])", ids)}
        for pid, o in out.items():
            o["dsports_live_available"] = live.get(pid)
            if pid in a:
                o["incoming"] = int(a[pid]["incoming"] or 0)
                o["days_of_cover"] = _f(a[pid]["days_of_cover"])
                o["risk_level"] = a[pid]["risk_level"]
        return sorted(out.values(), key=lambda o: -o["on_hand"])[:limit]

    # ------------------------------------------------------------------------------------------ events
    async def events(self) -> list[dict]:
        run = await self._run_id()
        as_of = date.fromisoformat((await self.run())["as_of"])
        rows = await self.pool.fetch("SELECT * FROM inv_event ORDER BY start_date")
        out = []
        for r in rows:
            d = _row(r)
            d["regions"] = json.loads(r["regions"]) if isinstance(r["regions"], str) else r["regions"]
            d["status"] = "UPCOMING" if r["start_date"] >= as_of else "PAST"
            d["starts_in_days"] = (r["start_date"] - as_of).days
            out.append(d)
        return out

    async def event_impact(self, event_id: str) -> dict:
        run = await self._run_id()
        event = next((e for e in await self.events() if e["id"] == event_id), None)
        if event is None:
            raise InventoryUnavailable(f"Unknown event: {event_id}")
        rows = await self.pool.fetch("SELECT * FROM inv_event_impact WHERE run_id = $1 AND event_id = $2 ORDER BY category", run, event_id)
        impact = [_row(r) | {"detail": json.loads(r["detail"]) if isinstance(r["detail"], str) else r["detail"]} for r in rows]
        readiness = await self._assessments(f"EVENT:{event_id}", "TRUE", [], "CASE a.risk_level WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, a.days_of_cover NULLS LAST", 200)
        base = {r["product_id"]: r for r in await self._assessments("BASELINE", "s.sport = $3", [event["sport"]], "s.name", 500)}
        for r in readiness:
            b = base.get(r["product_id"])
            r["baseline"] = {k: b[k] for k in ("forecast_30", "forecast_90", "days_of_cover", "risk_level", "reorder_qty")} if b else None
            window_start = date.fromisoformat(event["start_date"])
            r["projected_shortage_by_event_end"] = None
            if r["on_hand"] is not None and r["forecast_90"] is not None:
                end_idx = (date.fromisoformat(event["end_date"]) - date.fromisoformat((await self.run())["as_of"])).days + 1
                need = None
                f = await self.pool.fetchval("SELECT daily_mean FROM inv_forecast WHERE run_id=$1 AND product_id=$2::uuid", run, r["product_id"])
                uplift = r["detail"].get("event", {}).get("uplift")
                if f and uplift:
                    arr = np.array(f)
                    s0 = max(0, (window_start - date.fromisoformat((await self.run())["as_of"])).days)
                    arr[s0:end_idx] *= uplift
                    need = float(arr[:end_idx].sum())
                    r["event_window_demand"] = round(float(arr[s0:end_idx].sum()), 1)
                    r["baseline_window_demand"] = round(float(np.array(f)[s0:end_idx].sum()), 1)
                    r["projected_shortage_by_event_end"] = int(max(0, need - r["on_hand"] - r["incoming"]))
        return {"event": event, "impact": impact, "readiness": readiness, "data_source": DATA_SOURCE,
                "assumption_note": "Uplift is estimated from past events of the same sport (see basis); rows marked POC_ASSUMPTION are NOT measured."}

    # ------------------------------------------------------------------------------------------ regions
    async def regions(self, *, sport: str | None = None, category: str | None = None, product_id: str | None = None, event_id: str | None = None,
                      excess_only: bool = False) -> dict:
        run = await self._run_id()
        meta = await self.run()
        skus = await self.resolve(sport=sport, category=category, product_id=product_id)
        if excess_only:                       # only the overstocked products: where does the excess sit, and is any region short?
            over = {r["product_id"] for r in await self.overstock(sport=sport, category=category, limit=500)}
            skus = [s for s in skus if s["product_id"] in over]
        ids = [uuid.UUID(s["product_id"]) for s in skus]
        if not ids:
            raise InventoryUnavailable("No products match that selection.")
        shares = await self.pool.fetch("SELECT product_id, region, share FROM inv_region_share WHERE run_id=$1 AND product_id = ANY($2::uuid[])", run, ids)
        fc = {r["product_id"]: np.array(r["daily_mean"]) for r in await self.pool.fetch(
            "SELECT product_id, daily_mean FROM inv_forecast WHERE run_id=$1 AND status='OK' AND product_id = ANY($2::uuid[])", run, ids)}
        pos = await self.pool.fetch("SELECT product_id, region, on_hand FROM inv_position WHERE product_id = ANY($1::uuid[])", ids)
        recent = await self.pool.fetch(
            "SELECT region, sum(units) AS u FROM inv_demand_daily WHERE product_id = ANY($1::uuid[]) AND day >= $2 GROUP BY region",
            ids, date.fromisoformat(meta["as_of"]) - timedelta(days=90))
        prev = await self.pool.fetch(
            "SELECT region, sum(units) AS u FROM inv_demand_daily WHERE product_id = ANY($1::uuid[]) AND day >= $2 AND day < $3 GROUP BY region",
            ids, date.fromisoformat(meta["as_of"]) - timedelta(days=180), date.fromisoformat(meta["as_of"]) - timedelta(days=90))
        share = {(r["product_id"], r["region"]): float(r["share"]) for r in shares}
        out = {c: {"region": c, "name": REGIONS_NAME[c], "demand_30d": 0.0, "stock": 0, "demand_90d_history": 0, "growth_pct": None} for c in REGIONS}
        weights = {}
        if event_id:
            ev = next((e for e in await self.events() if e["id"] == event_id), None)
            weights = ev["regions"] if ev else {}
        for pid, daily in fc.items():
            raw = {c: share.get((pid, c), 0.2) * weights.get(c, 1.0) for c in REGIONS}
            z = sum(raw.values())
            for c in REGIONS:
                out[c]["demand_30d"] += float(daily[:30].sum()) * raw[c] / z
        for r in pos:
            out[r["region"]]["stock"] += int(r["on_hand"])
        pr = {r["region"]: float(r["u"]) for r in prev}
        for r in recent:
            out[r["region"]]["demand_90d_history"] = int(r["u"])
            if pr.get(r["region"]):
                out[r["region"]]["growth_pct"] = round(100 * (float(r["u"]) / pr[r["region"]] - 1), 1)
        total_demand = sum(v["demand_30d"] for v in out.values()) or 1.0
        for v in out.values():
            v["demand_30d"] = round(v["demand_30d"], 1)
            v["demand_share_pct"] = round(100 * v["demand_30d"] / total_demand, 1)
            v["days_of_cover"] = round(v["stock"] / (v["demand_30d"] / 30), 1) if v["demand_30d"] > 0 else None
        rows = sorted(out.values(), key=lambda v: -v["demand_30d"])
        # transfers: move from regions with more than 60 days of cover to regions below 21 days (greedy, deterministic)
        deficit = {v["region"]: max(0, int(np.ceil(v["demand_30d"] / 30 * 30 - v["stock"]))) for v in rows if v["days_of_cover"] is not None and v["days_of_cover"] < 21}
        surplus = {v["region"]: max(0, int(v["stock"] - np.ceil(v["demand_30d"] / 30 * 60))) for v in rows if v["days_of_cover"] is None or v["days_of_cover"] > 60}
        moves = []
        for d_region, need in sorted(deficit.items(), key=lambda kv: -kv[1]):
            for s_region in sorted(surplus, key=lambda k: -surplus[k]):
                qty = min(need, surplus[s_region])
                if qty > 0:
                    moves.append({"from": s_region, "to": d_region, "units": qty})
                    surplus[s_region] -= qty
                    need -= qty
        return {"selection": {"sport": sport, "category": category, "product_id": product_id, "products": len(skus), "event_id": event_id, "excess_only": excess_only},
                "regions": rows, "transfers": moves, "data_source": DATA_SOURCE,
                "note": "Regions and regional stock are POC synthetic (DSports has no regional dimension). Demand = persisted forecast x recent regional share"
                        + (" x the event's regional weights" if event_id else "") + ". Transfers: surplus above 60 days of cover to regions below 21 days."}

    # ------------------------------------------------------------------------------------------ scenarios / clearance
    async def _items(self, product_ids: list[str]) -> list[S.Item]:
        run = await self._run_id()
        ids = [uuid.UUID(p) for p in product_ids]
        skus = {str(r["product_id"]): r for r in await self.pool.fetch("SELECT * FROM inv_sku WHERE product_id = ANY($1::uuid[])", ids)}
        fcs = {str(r["product_id"]): r for r in await self.pool.fetch(
            "SELECT product_id, daily_mean FROM inv_forecast WHERE run_id=$1 AND status='OK' AND product_id = ANY($2::uuid[])", run, ids)}
        base = {str(r["product_id"]): r for r in await self.pool.fetch(
            "SELECT product_id, on_hand, age_days, excess_units, detail FROM inv_assessment WHERE run_id=$1 AND scenario='BASELINE' AND product_id = ANY($2::uuid[])", run, ids)}
        el = {r["category"]: S.Elasticity(r["category"], -float(r["elasticity"]), -float(r["elasticity_hi"]), -float(r["elasticity_lo"]),
                                          float(r["pull_forward"]), r["n_promo_days"], r["basis"])
              for r in await self.pool.fetch("SELECT * FROM inv_elasticity WHERE run_id=$1", run)}
        arr = {}
        meta = await self.run()
        as_of = date.fromisoformat(meta["as_of"])
        for r in await self.pool.fetch("SELECT product_id, qty, eta FROM inv_purchase_order WHERE status='OPEN' AND product_id = ANY($1::uuid[])", ids):
            arr.setdefault(str(r["product_id"]), []).append(((r["eta"] - as_of).days, int(r["qty"])))
        items = []
        for pid in product_ids:
            if pid not in fcs or pid not in skus:
                continue
            s = skus[pid]
            e = el.get(s["category"]) or S.Elasticity(s["category"], S.PRIOR_ELASTICITY[0], S.PRIOR_ELASTICITY[1], S.PRIOR_ELASTICITY[2],
                                                       S.PRIOR_PULL_FORWARD, 0, "POC_ASSUMPTION")
            items.append(S.Item(pid, s["name"], float(s["price"]), None if s["cost"] is None else float(s["cost"]), int(base[pid]["on_hand"]),
                                tuple(sorted(arr.get(pid, []))), tuple(float(x) for x in fcs[pid]["daily_mean"]), e, int(base[pid]["age_days"] or 0), int(base[pid]["excess_units"] or 0)))
        return items

    async def scenario(self, *, product_ids: list[str] | None = None, sport: str | None = None, category: str | None = None,
                       discounts: list[float] | None = None, days: int = S.DEFAULT_DURATION, health: list[str] | None = None,
                       actor_hash: str | None = None, limit_products: int = 60) -> dict:
        """What-if over a product selection. `health` restricts to e.g. slow-moving products."""
        if product_ids is None:
            if health:
                rows = await self.overstock(sport=sport, category=category, health=health, limit=limit_products)
                product_ids = [r["product_id"] for r in rows]
            else:
                product_ids = [s["product_id"] for s in await self.resolve(sport=sport, category=category)][:limit_products]
        if not product_ids:
            raise InventoryUnavailable("No products match that selection.")
        ds = sorted({0.0, *(float(d) for d in (discounts or [30.0, 50.0]))})
        if any(d != d or d < 0 or d > S.MAX_DISCOUNT for d in ds):
            raise InvalidRequest(f"Discounts must be between 0 and {S.MAX_DISCOUNT:g} percent.")
        if not isinstance(days, int) or days < 1 or days > 365:
            raise InvalidRequest("The promotion duration must be between 1 and 365 days.")
        items = await self._items(product_ids)
        if not items:
            raise InventoryUnavailable("Insufficient data to produce a reliable forecast for those products.")
        try:
            result = S.evaluate(items, ds, days)
        except ValueError as exc:
            raise InvalidRequest(str(exc)) from None
        meta = await self.run()
        result["products"] = len(items)
        result["selection"] = {"sport": sport, "category": category, "health": health}
        result["data_source"] = DATA_SOURCE
        result["provenance"] = await self.provenance()
        result["recommendation_status"] = "AI Recommendation: decision support only. Prices stay owned by the DSports pricing engine; nothing was changed."
        obs_metrics.INV_SCENARIOS.labels("copilot" if actor_hash else "api").inc()
        await self.pool.execute("INSERT INTO inv_scenario_run (id, run_id, actor_hash, params, results) VALUES ($1,$2,$3,$4::jsonb,$5::jsonb)",
                                uuid.uuid4(), await self._run_id(), actor_hash,
                                json.dumps({"product_count": len(items), "discounts": ds, "days": days, "sport": sport, "category": category, "health": health,
                                            "model": f"{meta['model_name']} {meta['model_version']}", "as_of": meta["as_of"]}),
                                json.dumps({"scenarios": result["scenarios"], "recommended_discount_pct": result["recommended_discount_pct"]}))
        result["items"] = {k: v for k, v in result["items"].items()} if len(items) <= 12 else {}
        return result

    async def clearance_candidates(self, *, sport: str | None = None, category: str | None = None, limit: int = 15, actor_hash: str | None = None) -> dict:
        rows = await self.overstock(sport=sport, category=category, health=["Slow Moving", "Critical Aging"], limit=200)
        rows = [r for r in rows if (r["excess_units"] or 0) > 0 and r["overstock_level"] in ("HIGH", "MEDIUM")]
        if not rows:
            return {"candidates": [], "products": 0, "data_source": DATA_SOURCE, "note": "No clearance candidates match that selection."}
        items = await self._items([r["product_id"] for r in rows])
        ev = S.evaluate(items, [0.0, 10.0, 20.0, 30.0, 40.0, 50.0], 30)
        best = {p["product_id"]: p for p in ev["per_product_best"]}
        by_id = {r["product_id"]: r for r in rows}
        cands = []
        for pid, r in by_id.items():
            b = best.get(pid, {})
            cands.append({"product_id": pid, "name": r["name"], "sport": r["sport"], "category": r["category"], "on_hand": r["on_hand"], "age_days": r["age_days"],
                          "forecast_90": r["forecast_90"], "excess_units": r["excess_units"], "excess_value": r["excess_value"],
                          "coverage_days": r["coverage_days"], "health_class": r["health_class"], "overstock_level": r["overstock_level"],
                          "recommended_discount_pct": b.get("best_discount_pct"), "expected_units_sold_30d": b.get("units_sold"),
                          "net_benefit": b.get("net_benefit"),
                          "reason": (f"{r['on_hand']} units on hand vs a 90-day forecast of {r['forecast_90']:.0f} ({r['coverage_days']:.0f} days of cover), "
                                     f"oldest stock {r['age_days']} days old")})
        cands.sort(key=lambda c: (c["recommended_discount_pct"] is None, -(c["excess_value"] or 0)))
        await self.pool.execute("INSERT INTO inv_audit (actor_hash, action, detail) VALUES ($1,'CLEARANCE',$2::jsonb)", actor_hash,
                                json.dumps({"candidates": len(cands), "sport": sport, "category": category}))
        return {"candidates": cands[:limit], "products": len(cands), "total_excess_units": sum(c["excess_units"] or 0 for c in cands),
                "total_excess_value": round(sum(c["excess_value"] or 0 for c in cands), 2), "horizon_days": 30, "elasticity": ev["elasticity"],
                "data_source": DATA_SOURCE, "recommendation_status": "AI Recommendation: nothing was changed. Executing a clearance needs approval in the DSports pricing engine."}


REGIONS_NAME = {"N": "North", "S": "South", "E": "East", "W": "West", "C": "Central"}
