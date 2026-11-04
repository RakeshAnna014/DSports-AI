"""Operational persistence besides plans: actuals and their controlled corrections, scope grants, stock inputs, allocation and transfer recommendations, scenarios, pilots,
data-readiness counts and the regional/category analysis queries."""
from __future__ import annotations

import uuid
from datetime import date, timedelta
from typing import Any, Sequence

from app.planning.dbutil import DEMO_SRC, IMPORTED, LIVE, SCOPE_SOURCES, dumps, money, num, rec, recs
from app.planning.repo_plans import REGION_EXPR


class ReportsMixin:
    # ------------------------------------------------------------------------------------------------ scope
    async def grant_scope(self, actor_hash: str, location_id: str, granted_by: str) -> None:
        await self._pool.execute("INSERT INTO pln_scope (actor_hash, location_id, granted_by) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING", actor_hash, location_id, granted_by)

    async def revoke_scope(self, actor_hash: str, location_id: str) -> int:
        return int((await self._pool.execute("DELETE FROM pln_scope WHERE actor_hash = $1 AND location_id = $2", actor_hash, location_id)).split()[-1])

    async def scope_stores(self, actor_hash: str) -> list[str]:
        ids = [r["location_id"] for r in await self._pool.fetch("SELECT location_id FROM pln_scope WHERE actor_hash = $1", actor_hash)]
        return await self.descendant_stores(ids) if ids else []

    async def list_scope(self, actor_hash: str | None = None) -> list[dict[str, Any]]:
        return recs(await self._pool.fetch("SELECT * FROM pln_scope WHERE ($1::text IS NULL OR actor_hash = $1) ORDER BY actor_hash, location_id", actor_hash))

    # ------------------------------------------------------------------------------------------------ actuals
    async def weekly_actuals(self, source: str, weeks: tuple[date, date], dim: str, f: dict[str, Any], allowed: Sequence[str] | None, through: date | None) -> list[dict[str, Any]]:
        """Actual sales by week and one dimension, with the source and whether each week is closed. A closed week of the live feed with no lines is a confirmed zero: it is simply absent here
        and the caller fills it; a week that is not closed is reported as open."""
        sources = list(SCOPE_SOURCES[source])
        dim_expr = {"store": "b.location_id", "region": "CASE WHEN b.par_level = 'REGION' THEN b.par_id ELSE b.par_parent END", "zone": "b.parent_id", "sport": "b.sport", "category": "b.sport || ' / ' || b.category",
                    "product": "b.name", "total": "'ALL'"}.get(dim)
        if dim_expr is None:
            raise ValueError("dim must be one of store, region, zone, sport, category, product, total")
        args: list[Any] = [sources, weeks[0], weeks[1]]
        where = ["w.data_source = ANY($1::text[])", "w.week_start >= $2", "w.week_start <= $3"]

        def add(sql: str, v: Any) -> None:
            args.append(v)
            where.append(sql.format(f"${len(args)}"))
        if allowed is not None:
            add("w.location_id = ANY({}::text[])", list(allowed))
        for key, expr in (("sport", "k.sport = {}"), ("category", "k.category = {}"), ("region", f"({REGION_EXPR}) = {{}}"), ("zone", "l.parent_id = {}")):
            if f.get(key):
                add(expr, f[key])
        if f.get("stores"):
            add("w.location_id = ANY({}::text[])", list(f["stores"]))
        if f.get("product_id"):
            add("w.product_id = {}::uuid", f["product_id"])
        rows = await self._pool.fetch(
            f"""WITH best AS (
                  SELECT DISTINCT ON (w.product_id, w.location_id, w.week_start) w.*, k.sport, k.category, k.name, l.parent_id, par.id AS par_id, par.level AS par_level, par.parent_id AS par_parent
                  FROM pln_sales_week w JOIN inv_sku k ON k.product_id = w.product_id JOIN pln_location l ON l.id = w.location_id JOIN pln_location par ON par.id = l.parent_id
                  WHERE {' AND '.join(where)} ORDER BY w.product_id, w.location_id, w.week_start, (w.data_source = 'IMPORTED') DESC)
                SELECT {dim_expr} AS gkey, b.week_start,
                       SUM(b.gross_units) AS gross_units, SUM(b.cancelled_units) AS cancelled_units, SUM(b.returned_units) AS returned_units, SUM(b.net_units) AS net_units,
                       SUM(b.gross_revenue) AS gross_revenue, SUM(b.discount) AS discount, SUM(b.net_revenue) AS net_revenue, SUM(COALESCE(b.stockout_days, 0)) AS stockout_days,
                       COUNT(*) AS rows, ARRAY_AGG(DISTINCT b.data_source) AS sources
                FROM best b GROUP BY gkey, b.week_start ORDER BY b.week_start, gkey""", *args)
        out = recs(rows)
        for r in out:
            r["week_closed"] = bool(through and date.fromisoformat(r["week_start"]).toordinal() + 6 <= through.toordinal())
        return out

    async def adjustments_for(self, product_ids: Sequence[str], week_from: date, week_to: date) -> list[dict[str, Any]]:
        return recs(await self._pool.fetch("SELECT * FROM pln_actual_adjustment WHERE status = 'APPROVED' AND product_id = ANY($1::uuid[]) AND week_start BETWEEN $2 AND $3", [uuid.UUID(p) for p in product_ids], week_from, week_to))

    async def import_actuals(self, actor: str, roles: Sequence[str], batch_id: str, rows: Sequence[dict[str, Any]], note: str | None, data_through: date | None) -> dict[str, Any]:
        """Load weekly store actuals. Idempotent on `batch_id`. Only STORE-channel locations can be imported into: the live online feed is never overwritten by hand."""
        async with self._pool.acquire() as conn, conn.transaction():
            await conn.execute("SELECT pg_advisory_xact_lock(hashtext($1))", batch_id)
            prev = await conn.fetchrow("SELECT * FROM pln_import_batch WHERE batch_id = $1", batch_id)
            if prev is not None:
                return {"batch_id": batch_id, "already_applied": True, "rows_applied": prev["rows_applied"], "rows_total": prev["rows_total"]}
            for r in rows:
                await conn.execute(
                    """INSERT INTO pln_sales_week (product_id, location_id, week_start, data_source, gross_units, cancelled_units, returned_units, net_units, gross_revenue, discount, net_revenue, line_count, aggregated_at)
                       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,0, now())
                       ON CONFLICT (product_id, location_id, week_start, data_source) DO UPDATE SET gross_units=$5, cancelled_units=$6, returned_units=$7, net_units=$8, gross_revenue=$9, discount=$10,
                              net_revenue=$11, aggregated_at=now()""",
                    uuid.UUID(r["product_id"]), r["location_id"], r["week_start"], IMPORTED, r["gross_units"], r["cancelled_units"], r["returned_units"], r["net_units"],
                    money(r.get("gross_revenue") or 0), money(r.get("discount") or 0), money(r.get("net_revenue") or 0))
            if data_through:
                await self.set_data_through(IMPORTED, data_through, conn)
            await conn.execute("INSERT INTO pln_import_batch (batch_id, created_by, rows_total, rows_applied, data_through, note) VALUES ($1,$2,$3,$3,$4,$5)", batch_id, actor, len(rows), data_through, note)
            await self.audit(actor, roles, "ACTUAL", batch_id, "IMPORTED", after={"rows": len(rows), "data_through": data_through.isoformat() if data_through else None, "note": note}, conn=conn)
        return {"batch_id": batch_id, "already_applied": False, "rows_applied": len(rows), "rows_total": len(rows)}

    async def create_adjustment(self, e: dict[str, Any], actor: str, roles: Sequence[str]) -> dict[str, Any]:
        r = await self._pool.fetchrow(
            """INSERT INTO pln_actual_adjustment (id, product_id, location_id, week_start, delta_units, delta_revenue, reason_code, note, requested_by) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING *""",
            uuid.uuid4(), uuid.UUID(e["product_id"]), e["location_id"], e["week_start"], e["delta_units"], num(e.get("delta_revenue"), 2), e["reason_code"], e["note"], actor)
        await self.audit(actor, roles, "ACTUAL", str(r["id"]), "ADJUSTMENT_REQUESTED", after={k: str(v) for k, v in e.items()}, reason=e["note"])
        return rec(r) or {}

    async def list_adjustments(self, status: str | None = None, allowed: Sequence[str] | None = None, limit: int = 100) -> list[dict[str, Any]]:
        return recs(await self._pool.fetch(
            """SELECT a.*, k.name AS product_name, l.name AS store_name FROM pln_actual_adjustment a JOIN inv_sku k ON k.product_id = a.product_id JOIN pln_location l ON l.id = a.location_id
               WHERE ($1::text IS NULL OR a.status = $1) AND ($2::text[] IS NULL OR a.location_id = ANY($2::text[])) ORDER BY a.requested_at DESC LIMIT $3""", status, None if allowed is None else list(allowed), limit))

    async def decide_adjustment(self, adj_id: str, status: str, actor: str, roles: Sequence[str], note: str | None) -> dict[str, Any] | None:
        r = await self._pool.fetchrow("UPDATE pln_actual_adjustment SET status = $2, decided_by = $3, decided_at = now(), decision_note = $4 WHERE id = $1::uuid AND status = 'PENDING' RETURNING *", adj_id, status, actor, note)
        if r is not None:
            await self.audit(actor, roles, "ACTUAL", adj_id, f"ADJUSTMENT_{status}", reason=note)
        return rec(r)

    async def get_adjustment(self, adj_id: str) -> dict[str, Any] | None:
        return rec(await self._pool.fetchrow("SELECT * FROM pln_actual_adjustment WHERE id = $1::uuid", adj_id))

    # ------------------------------------------------------------------------------------------------ stock and allocation
    async def product_store_inputs(self, plan: dict[str, Any], f: dict[str, Any], allowed: Sequence[str] | None, product_ids: Sequence[str] | None = None) -> dict[str, Any]:
        """Everything the allocation needs, in a few queries: per product and store, the weekly forecast used by the plan, its interval, the stock position and the incoming quantities."""
        args: list[Any] = [uuid.UUID(plan["id"])]
        flt = self._line_filter(f, args, "pl", allowed)
        extra = ""
        if product_ids:
            args.append([uuid.UUID(p) for p in product_ids])
            extra = f" AND pl.product_id = ANY(${len(args)}::uuid[])"
        lines = await self._pool.fetch(
            f"""SELECT pl.product_id, pl.location_id, pl.week_start, COALESCE(pl.approved_forecast, pl.manager_forecast, pl.model_forecast) AS used, pl.model_forecast AS model, pl.model_lo, pl.model_hi, pl.confidence
                FROM pln_plan_line pl JOIN inv_sku k ON k.product_id = pl.product_id JOIN pln_location l ON l.id = pl.location_id JOIN pln_location par ON par.id = l.parent_id
                WHERE pl.plan_id = $1{flt}{extra} ORDER BY pl.product_id, pl.location_id, pl.week_start""", *args)
        pids = list({r["product_id"] for r in lines})
        locs = list({r["location_id"] for r in lines})
        stock = {(r["product_id"], r["location_id"]): r for r in await self._pool.fetch("SELECT * FROM pln_stock_position WHERE product_id = ANY($1::uuid[]) AND location_id = ANY($2::text[])", pids, locs)}
        incoming: dict[tuple[Any, Any], list[tuple[date, int]]] = {}
        for r in await self._pool.fetch("SELECT product_id, location_id, qty, eta FROM pln_incoming WHERE product_id = ANY($1::uuid[]) AND location_id = ANY($2::text[])", pids, locs):
            incoming.setdefault((r["product_id"], r["location_id"]), []).append((r["eta"], int(r["qty"])))
        skus = {r["product_id"]: r for r in await self._pool.fetch("SELECT * FROM inv_sku WHERE product_id = ANY($1::uuid[])", pids)}
        locinfo = {r["id"]: r for r in await self._pool.fetch(f"SELECT l.id, l.name, l.status, l.opened_on, ({REGION_EXPR}) AS region FROM pln_location l JOIN pln_location par ON par.id = l.parent_id WHERE l.id = ANY($1::text[])", locs)}
        return {"lines": lines, "stock": stock, "incoming": incoming, "skus": skus, "locations": locinfo}

    async def elasticities(self) -> dict[str, Any]:
        return {r["category"]: r for r in await self._pool.fetch("SELECT e.* FROM inv_elasticity e JOIN inv_model_run r ON r.id = e.run_id AND r.is_current")}

    async def save_allocations(self, plan_id: str, recs_: Sequence[dict[str, Any]], transfers: Sequence[dict[str, Any]], actor: str, roles: Sequence[str], product_ids: Sequence[str] | None) -> dict[str, int]:
        """Replace the plan's DRAFT recommendations for these products with a fresh set (earlier ones become SUPERSEDED, decided ones are kept). Nothing here orders or moves stock."""
        async with self._pool.acquire() as conn, conn.transaction():
            sel = " AND product_id = ANY($2::uuid[])" if product_ids else ""
            a: list[Any] = [uuid.UUID(plan_id)] + ([[uuid.UUID(p) for p in product_ids]] if product_ids else [])
            await conn.execute(f"UPDATE pln_alloc_rec SET status = 'SUPERSEDED' WHERE plan_id = $1 AND status = 'DRAFT'{sel}", *a)
            await conn.execute(f"UPDATE pln_transfer_rec SET status = 'SUPERSEDED' WHERE plan_id = $1 AND status = 'DRAFT'{sel}", *a)
            for r in recs_:
                await conn.execute(
                    """INSERT INTO pln_alloc_rec (id, plan_id, product_id, location_id, as_of, action, risk_level, required_stock, on_hand, reserved, incoming, shortage, surplus, recommended_qty, order_by, arrival_date,
                                                  safety_stock, coverage_days, confidence, reason, detail) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21::jsonb)""",
                    uuid.uuid4(), uuid.UUID(plan_id), uuid.UUID(r["product_id"]), r["location_id"], r["as_of"], r["action"], r["risk_level"], r["required_stock"], r["on_hand"], r["reserved"], r["incoming"],
                    r["shortage"], r["surplus"], r["recommended_qty"], r["order_by"], r["arrival_date"], r["safety_stock"], r["coverage_days"], r["confidence"], r["reason"], dumps(r["detail"]))
            for t in transfers:
                await conn.execute("INSERT INTO pln_transfer_rec (id, plan_id, product_id, from_location, to_location, qty, lead_days, arrival_date, reason, avoids_order_qty, detail) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11::jsonb)",
                                   uuid.uuid4(), uuid.UUID(plan_id), uuid.UUID(t["product_id"]), t["from_location"], t["to_location"], t["qty"], t["lead_days"], t["arrival_date"], t["reason"], t["avoids_order_qty"], dumps(t["detail"]))
            await self.audit(actor, roles, "ALLOCATION", plan_id, "GENERATED", after={"recommendations": len(recs_), "transfers": len(transfers), "products": len({r["product_id"] for r in recs_})}, conn=conn)
        return {"recommendations": len(recs_), "transfers": len(transfers)}

    async def list_allocations(self, plan_id: str, f: dict[str, Any], allowed: Sequence[str] | None, page: int, size: int, status: str | None = None, only_actions: bool = False) -> dict[str, Any]:
        args: list[Any] = [uuid.UUID(plan_id)]
        where = "a.plan_id = $1"
        if status:
            args.append(status)
            where += f" AND a.status = ${len(args)}"
        else:
            where += " AND a.status <> 'SUPERSEDED'"
        if only_actions:
            where += " AND a.action <> 'NONE'"
        for key, expr in (("sport", "k.sport = ${}"), ("category", "k.category = ${}"), ("risk", "a.risk_level = ${}"), ("action", "a.action = ${}"), ("region", f"({REGION_EXPR}) = ${{}}")):
            if f.get(key):
                args.append(f[key])
                where += " AND " + expr.format(len(args))
        if f.get("stores"):
            args.append(list(f["stores"]))
            where += f" AND a.location_id = ANY(${len(args)}::text[])"
        if allowed is not None:
            args.append(list(allowed))
            where += f" AND a.location_id = ANY(${len(args)}::text[])"
        base = f"FROM pln_alloc_rec a JOIN inv_sku k ON k.product_id = a.product_id JOIN pln_location l ON l.id = a.location_id JOIN pln_location par ON par.id = l.parent_id WHERE {where}"
        total = await self._pool.fetchval(f"SELECT COUNT(*) {base}", *args)
        rows = await self._pool.fetch(f"SELECT a.*, k.name AS product_name, k.sku, k.sport, k.category, l.name AS store_name, ({REGION_EXPR}) AS region {base} "
                                      f"ORDER BY CASE a.risk_level WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, a.recommended_qty DESC, k.name, a.location_id LIMIT ${len(args) + 1} OFFSET ${len(args) + 2}", *args, size, page * size)
        return {"items": recs(rows), "total": int(total), "page": page, "size": size}

    async def alloc_summary(self, plan_id: str, allowed: Sequence[str] | None) -> dict[str, Any]:
        r = await self._pool.fetchrow(
            """SELECT COUNT(*) FILTER (WHERE status <> 'SUPERSEDED') AS recs, COUNT(*) FILTER (WHERE status <> 'SUPERSEDED' AND risk_level = 'HIGH') AS high_risk,
                      COUNT(*) FILTER (WHERE status <> 'SUPERSEDED' AND action = 'ORDER') AS to_order, COUNT(*) FILTER (WHERE status <> 'SUPERSEDED' AND surplus > 0 AND action = 'NONE') AS with_surplus,
                      COALESCE(SUM(recommended_qty) FILTER (WHERE status <> 'SUPERSEDED'), 0) AS units_to_order, COUNT(DISTINCT product_id) FILTER (WHERE status <> 'SUPERSEDED' AND action = 'ORDER') AS products_to_order,
                      MAX(created_at) AS generated_at FROM pln_alloc_rec WHERE plan_id = $1::uuid AND ($2::text[] IS NULL OR location_id = ANY($2::text[]))""", plan_id, None if allowed is None else list(allowed))
        t = await self._pool.fetchrow("SELECT COUNT(*) FILTER (WHERE status <> 'SUPERSEDED') AS transfers, COALESCE(SUM(qty) FILTER (WHERE status <> 'SUPERSEDED'), 0) AS units FROM pln_transfer_rec WHERE plan_id = $1::uuid", plan_id)
        return {**(rec(r) or {}), "transfers": t["transfers"], "transfer_units": t["units"]}

    async def list_transfers(self, plan_id: str, allowed: Sequence[str] | None, status: str | None = None, limit: int = 200) -> list[dict[str, Any]]:
        return recs(await self._pool.fetch(
            """SELECT t.*, k.name AS product_name, k.sku, fl.name AS from_name, tl.name AS to_name FROM pln_transfer_rec t JOIN inv_sku k ON k.product_id = t.product_id
               JOIN pln_location fl ON fl.id = t.from_location JOIN pln_location tl ON tl.id = t.to_location
               WHERE t.plan_id = $1::uuid AND t.status <> 'SUPERSEDED' AND ($2::text IS NULL OR t.status = $2)
                 AND ($3::text[] IS NULL OR t.from_location = ANY($3::text[]) OR t.to_location = ANY($3::text[])) ORDER BY k.name, t.to_location LIMIT $4""",
            plan_id, status, None if allowed is None else list(allowed), limit))

    async def decide_recommendation(self, table: str, rec_id: str, status: str, actor: str, roles: Sequence[str]) -> dict[str, Any] | None:
        if table not in ("pln_alloc_rec", "pln_transfer_rec"):
            raise ValueError("unknown table")
        r = await self._pool.fetchrow(f"UPDATE {table} SET status = $2, decided_by = $3, decided_at = now() WHERE id = $1::uuid AND status = 'DRAFT' RETURNING *", rec_id, status, actor)
        if r is not None:
            await self.audit(actor, roles, "ALLOCATION" if table == "pln_alloc_rec" else "TRANSFER", rec_id, f"RECOMMENDATION_{status}", after={"status": status})
        return rec(r)

    # ------------------------------------------------------------------------------------------------ scenarios and pilots
    async def save_scenario(self, actor: str, plan_id: str | None, name: str, kind: str, params: dict[str, Any], results: dict[str, Any]) -> dict[str, Any]:
        r = await self._pool.fetchrow("INSERT INTO pln_scenario (id, created_by, plan_id, name, kind, params, results) VALUES ($1,$2,$3,$4,$5,$6::jsonb,$7::jsonb) RETURNING *",
                                      uuid.uuid4(), actor, uuid.UUID(plan_id) if plan_id else None, name, kind, dumps(params), dumps(results))
        return rec(r) or {}

    async def list_scenarios(self, plan_id: str | None = None, limit: int = 50) -> list[dict[str, Any]]:
        return recs(await self._pool.fetch("SELECT id, created_at, created_by, plan_id, name, kind, params, status, results->'summary' AS summary FROM pln_scenario WHERE ($1::uuid IS NULL OR plan_id = $1::uuid) "
                                           "ORDER BY created_at DESC LIMIT $2", plan_id, limit))

    async def get_scenario(self, sid: str) -> dict[str, Any] | None:
        return rec(await self._pool.fetchrow("SELECT * FROM pln_scenario WHERE id = $1::uuid", sid))

    async def mark_scenario_adopted(self, sid: str, actor: str) -> None:
        await self._pool.execute("UPDATE pln_scenario SET status = 'ADOPTED', adopted_by = $2, adopted_at = now() WHERE id = $1::uuid", sid, actor)

    async def save_pilot(self, actor: str, opportunity_id: str, name: str, stores: list[dict[str, Any]], duration: int, estimate: dict[str, Any]) -> dict[str, Any]:
        r = await self._pool.fetchrow("INSERT INTO pln_pilot (id, created_by, opportunity_id, product_name, stores, duration_weeks, estimate) VALUES ($1,$2,$3,$4,$5::jsonb,$6,$7::jsonb) RETURNING *",
                                      uuid.uuid4(), actor, uuid.UUID(opportunity_id), name, dumps(stores), duration, dumps(estimate))
        return rec(r) or {}

    async def list_pilots(self, limit: int = 50) -> list[dict[str, Any]]:
        return recs(await self._pool.fetch("SELECT * FROM pln_pilot ORDER BY created_at DESC LIMIT $1", limit))

    async def get_pilot(self, pid: str) -> dict[str, Any] | None:
        return rec(await self._pool.fetchrow("SELECT * FROM pln_pilot WHERE id = $1::uuid", pid))

    async def update_pilot(self, pid: str, *, status: str | None = None, observations: list[dict[str, Any]] | None = None, decided_by: str | None = None, note: str | None = None) -> dict[str, Any] | None:
        r = await self._pool.fetchrow(
            """UPDATE pln_pilot SET status = COALESCE($2, status), observations = COALESCE($3::jsonb, observations), decided_by = COALESCE($4, decided_by),
                      decided_at = CASE WHEN $4::text IS NULL THEN decided_at ELSE now() END, note = COALESCE($5, note) WHERE id = $1::uuid RETURNING *""",
            pid, status, None if observations is None else dumps(observations), decided_by, note)
        return rec(r)

    async def pilot_comparable_inputs(self, sport: str, category: str, source: str, recent_weeks: int, as_of_week: date) -> dict[str, Any]:
        """Same-sport, same-category catalogue products with their average weekly units per store over the recent closed weeks, and how many weeks of history each store has."""
        sources = list(SCOPE_SOURCES[source])
        first = as_of_week - timedelta(weeks=recent_weeks - 1)
        prods = recs(await self._pool.fetch("SELECT product_id, name, sku, price, category, sport FROM inv_sku WHERE lower(sport) = lower($1) AND lower(category) = lower($2)", sport, category))
        rows = await self._pool.fetch(
            """SELECT w.product_id, w.location_id, AVG(w.net_units)::float AS rate FROM pln_sales_week w WHERE w.data_source = ANY($1::text[]) AND w.week_start BETWEEN $2 AND $3 AND w.product_id = ANY($4::uuid[])
               GROUP BY 1, 2""", sources, first, as_of_week, [uuid.UUID(p["product_id"]) for p in prods])
        rates: dict[str, dict[str, float]] = {}
        totals: dict[str, float] = {}
        for r in rows:
            rates.setdefault(str(r["product_id"]), {})[r["location_id"]] = float(r["rate"])
            totals[str(r["product_id"])] = totals.get(str(r["product_id"]), 0.0) + float(r["rate"])
        for p in prods:
            p["weekly_units"] = totals.get(p["product_id"], 0.0)
        hist = {r["location_id"]: int(r["n"]) for r in await self._pool.fetch("SELECT location_id, COUNT(DISTINCT week_start) AS n FROM pln_sales_week WHERE data_source = ANY($1::text[]) GROUP BY 1", sources)}
        regions_ = {r["id"]: r["region"] for r in await self._pool.fetch(f"SELECT l.id, ({REGION_EXPR}) AS region FROM pln_location l JOIN pln_location par ON par.id = l.parent_id WHERE l.level = 'STORE'")}
        return {"catalogue": prods, "rates": rates, "history_weeks": hist, "regions": regions_}

    async def taxonomy(self) -> dict[str, list[str]]:
        """The sports and the categories under each, from the catalogue the Inventory AI knows (for filters)."""
        out: dict[str, list[str]] = {}
        for r in await self._pool.fetch("SELECT sport, category FROM inv_sku GROUP BY 1, 2 ORDER BY 1, 2"):
            out.setdefault(r["sport"], []).append(r["category"])
        return out

    async def sport_categories(self, sport: str) -> list[str]:
        return [r["category"] for r in await self._pool.fetch("SELECT DISTINCT category FROM inv_sku WHERE lower(sport) = lower($1) ORDER BY 1", sport)]

    async def opportunity(self, opportunity_id: str) -> dict[str, Any] | None:
        return rec(await self._pool.fetchrow("SELECT id, name, brand, model, sport, category, status, provenance, record_kind, selling_price, product_url FROM src_opportunities WHERE id = $1::uuid", opportunity_id))

    # ------------------------------------------------------------------------------------------------ data readiness and analysis
    async def readiness_counts(self) -> dict[str, Any]:
        out: dict[str, Any] = {}
        for src in (LIVE, DEMO_SRC, IMPORTED):
            r = await self._pool.fetchrow(
                """SELECT COUNT(*) AS rows, COUNT(DISTINCT week_start) AS weeks, COUNT(DISTINCT product_id) AS products, COUNT(DISTINCT location_id) AS locations, MIN(week_start) AS first_week, MAX(week_start) AS last_week,
                          COALESCE(SUM(net_units), 0) AS net_units, COUNT(*) FILTER (WHERE stockout_days IS NOT NULL) AS with_stockout_info FROM pln_sales_week WHERE data_source = $1""", src)
            d = rec(r) or {}
            d["data_through"] = (await self.data_through(src)).isoformat() if await self.data_through(src) else None
            out[src] = d
        out["live_lines"] = rec(await self._pool.fetchrow(
            """SELECT COUNT(*) AS lines, COUNT(*) FILTER (WHERE counted) AS counted, COUNT(*) FILTER (WHERE NOT counted) AS excluded, COUNT(*) FILTER (WHERE location_id = 'ONL-UNK' AND counted) AS unknown_region,
                      COUNT(DISTINCT order_id) AS orders, COUNT(DISTINCT (placed_at AT TIME ZONE 'UTC')::date) AS order_days, MIN(placed_at) AS first_order, MAX(placed_at) AS last_order,
                      COUNT(*) FILTER (WHERE product_id NOT IN (SELECT product_id FROM inv_sku)) AS unmapped_products FROM pln_sales_line""") or {})
        out["catalogue"] = rec(await self._pool.fetchrow("SELECT COUNT(*) AS skus, COUNT(*) FILTER (WHERE lead_time_days IS NOT NULL) AS with_lead_time, COUNT(*) FILTER (WHERE cost IS NOT NULL) AS with_cost, "
                                                         "MIN(data_source) AS source FROM inv_sku"))
        out["stock"] = rec(await self._pool.fetchrow("SELECT COUNT(*) AS positions, MAX(as_of) AS as_of, MIN(data_source) AS source FROM pln_stock_position"))
        out["stores"] = rec(await self._pool.fetchrow("SELECT COUNT(*) FILTER (WHERE level='STORE' AND channel='STORE') AS stores, COUNT(*) FILTER (WHERE level='STORE' AND channel='ONLINE') AS online, "
                                                      "COUNT(*) FILTER (WHERE level='STORE' AND channel='STORE' AND data_source='POC_REFERENCE') AS demo_stores, COUNT(*) FILTER (WHERE level='REGION') AS regions FROM pln_location"))
        out["last_ingest"] = (await self.list_ingest_runs(1) or [None])[0]
        out["promotions_in_history"] = await self._pool.fetchval("SELECT COUNT(*) FROM inv_demand_daily WHERE discount_pct > 0")
        return out

    async def category_matrix(self, source: str, week_from: date, week_to: date, allowed: Sequence[str] | None, exclude_stockout_weeks: bool = True) -> list[dict[str, Any]]:
        """Net units by region x sport x category over closed weeks, plus the store count and units per store-week, for assortment comparisons. Weeks with stock-out days are reported separately."""
        sources = list(SCOPE_SOURCES[source])
        return recs(await self._pool.fetch(
            f"""WITH best AS (SELECT DISTINCT ON (w.product_id, w.location_id, w.week_start) w.* FROM pln_sales_week w WHERE w.data_source = ANY($1::text[]) AND w.week_start BETWEEN $2 AND $3
                                AND ($4::text[] IS NULL OR w.location_id = ANY($4::text[])) ORDER BY w.product_id, w.location_id, w.week_start, (w.data_source = 'IMPORTED') DESC)
                SELECT ({REGION_EXPR}) AS region, k.sport, k.category, SUM(b.net_units) AS net_units, SUM(b.net_revenue) AS net_revenue, COUNT(DISTINCT b.location_id) AS stores, COUNT(*) AS store_weeks,
                       SUM(b.net_units) FILTER (WHERE COALESCE(b.stockout_days, 0) < 3) AS units_unconstrained, COUNT(*) FILTER (WHERE COALESCE(b.stockout_days, 0) < 3) AS weeks_unconstrained,
                       COUNT(*) FILTER (WHERE COALESCE(b.stockout_days, 0) >= 3) AS weeks_stockout
                FROM best b JOIN inv_sku k ON k.product_id = b.product_id JOIN pln_location l ON l.id = b.location_id JOIN pln_location par ON par.id = l.parent_id
                GROUP BY 1, 2, 3 ORDER BY 1, 2, 3""", sources, week_from, week_to, None if allowed is None else list(allowed)))

    async def store_category_rows(self, source: str, week_from: date, week_to: date, allowed: Sequence[str] | None) -> list[dict[str, Any]]:
        sources = list(SCOPE_SOURCES[source])
        return recs(await self._pool.fetch(
            f"""WITH best AS (SELECT DISTINCT ON (w.product_id, w.location_id, w.week_start) w.* FROM pln_sales_week w WHERE w.data_source = ANY($1::text[]) AND w.week_start BETWEEN $2 AND $3
                                AND ($4::text[] IS NULL OR w.location_id = ANY($4::text[])) ORDER BY w.product_id, w.location_id, w.week_start, (w.data_source = 'IMPORTED') DESC)
                SELECT b.location_id, l.name AS store_name, ({REGION_EXPR}) AS region, k.sport, k.category, SUM(b.net_units) AS net_units, SUM(b.net_revenue) AS net_revenue,
                       COUNT(*) FILTER (WHERE COALESCE(b.stockout_days, 0) >= 3) AS weeks_stockout, COUNT(*) AS store_weeks
                FROM best b JOIN inv_sku k ON k.product_id = b.product_id JOIN pln_location l ON l.id = b.location_id JOIN pln_location par ON par.id = l.parent_id
                GROUP BY 1, 2, 3, 4, 5 ORDER BY 1, 4, 5""", sources, week_from, week_to, None if allowed is None else list(allowed)))

    async def top_products(self, source: str, week_from: date, week_to: date, allowed: Sequence[str] | None, region: str | None, limit: int = 15) -> list[dict[str, Any]]:
        sources = list(SCOPE_SOURCES[source])
        return recs(await self._pool.fetch(
            f"""WITH best AS (SELECT DISTINCT ON (w.product_id, w.location_id, w.week_start) w.* FROM pln_sales_week w WHERE w.data_source = ANY($1::text[]) AND w.week_start BETWEEN $2 AND $3
                                AND ($4::text[] IS NULL OR w.location_id = ANY($4::text[])) ORDER BY w.product_id, w.location_id, w.week_start, (w.data_source = 'IMPORTED') DESC)
                SELECT b.product_id, k.name, k.sku, k.sport, k.category, SUM(b.net_units) AS net_units, SUM(b.net_revenue) AS net_revenue, COUNT(DISTINCT b.location_id) AS stores
                FROM best b JOIN inv_sku k ON k.product_id = b.product_id JOIN pln_location l ON l.id = b.location_id JOIN pln_location par ON par.id = l.parent_id
                WHERE ($5::text IS NULL OR ({REGION_EXPR}) = $5) GROUP BY 1, 2, 3, 4, 5 ORDER BY net_units DESC, k.name LIMIT $6""", sources, week_from, week_to, None if allowed is None else list(allowed), region, limit))

    async def missing_forecast_counts(self, run_id: str) -> dict[str, Any]:
        return rec(await self._pool.fetchrow("SELECT COUNT(*) FILTER (WHERE status = 'INSUFFICIENT_DATA') AS insufficient, COUNT(*) FILTER (WHERE status = 'FALLBACK') AS fallback, COUNT(*) FILTER (WHERE confidence = 'LOW') AS low_confidence, "
                                             "COUNT(*) AS series FROM pln_forecast_series WHERE run_id = $1::uuid", run_id)) or {}

    async def forecast_page(self, run_id: str, f: dict[str, Any], allowed: Sequence[str] | None, page: int, size: int) -> dict[str, Any]:
        args: list[Any] = [uuid.UUID(run_id)]
        where = "s.run_id = $1"
        for key, expr in (("sport", "k.sport = ${}"), ("category", "k.category = ${}"), ("region", f"({REGION_EXPR}) = ${{}}"), ("confidence", "s.confidence = ${}"), ("status", "s.status = ${}")):
            if f.get(key):
                args.append(f[key])
                where += " AND " + expr.format(len(args))
        if f.get("stores"):
            args.append(list(f["stores"]))
            where += f" AND s.location_id = ANY(${len(args)}::text[])"
        if allowed is not None:
            args.append(list(allowed))
            where += f" AND s.location_id = ANY(${len(args)}::text[])"
        if f.get("q"):
            args.append(f"%{f['q']}%")
            where += f" AND (k.name ILIKE ${len(args)} OR k.sku ILIKE ${len(args)})"
        base = f"FROM pln_forecast_series s JOIN inv_sku k ON k.product_id = s.product_id JOIN pln_location l ON l.id = s.location_id JOIN pln_location par ON par.id = l.parent_id WHERE {where}"
        total = await self._pool.fetchval(f"SELECT COUNT(*) {base}", *args)
        rows = await self._pool.fetch(
            f"""SELECT s.product_id, k.name AS product_name, k.sku, k.sport, k.category, s.location_id, l.name AS store_name, ({REGION_EXPR}) AS region, s.method, s.bucket, s.confidence, s.status, s.reason, s.history_weeks,
                       (SELECT jsonb_agg(jsonb_build_object('week_start', f.week_start, 'forecast', f.forecast, 'lo', f.lo, 'hi', f.hi, 'baseline', f.baseline) ORDER BY f.week_start)
                        FROM pln_forecast f WHERE f.run_id = s.run_id AND f.product_id = s.product_id AND f.location_id = s.location_id) AS weeks
                {base} ORDER BY k.name, s.location_id LIMIT ${len(args) + 1} OFFSET ${len(args) + 2}""", *args, size, page * size)
        out = recs(rows)
        for r in out:
            r["weeks"] = r["weeks"] if isinstance(r["weeks"], list) else __import__("json").loads(r["weeks"] or "[]")
        return {"items": out, "total": int(total), "page": page, "size": size}
