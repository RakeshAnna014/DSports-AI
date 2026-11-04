"""Plan persistence: creating a plan from a forecast run, the paginated workspace query, concurrency-safe line edits, workflow transitions and the plan-versus-actual report."""
from __future__ import annotations

import uuid
from datetime import date
from typing import Any, Sequence

from app.planning.dbutil import SCOPE_SOURCES, dumps, num, rec, recs

LINE_SORT = {"product": "k.name, pl.location_id, pl.week_start", "store": "pl.location_id, k.name, pl.week_start", "week": "pl.week_start, k.name, pl.location_id",
             "model": "pl.model_forecast DESC NULLS LAST, pl.product_id, pl.location_id, pl.week_start", "target": "pl.target_units DESC NULLS LAST, pl.product_id, pl.location_id, pl.week_start",
             "variance": "variance_abs DESC NULLS LAST, pl.product_id, pl.location_id, pl.week_start"}

REGION_EXPR = "CASE WHEN par.level = 'REGION' THEN par.id ELSE par.parent_id END"


class PlansMixin:
    # ------------------------------------------------------------------------------------------------ forecast runs
    async def current_run(self, source: str) -> dict[str, Any] | None:
        return rec(await self._pool.fetchrow("SELECT * FROM pln_forecast_run WHERE source = $1 AND is_current AND status = 'SUCCEEDED'", source))

    async def get_run(self, run_id: str) -> dict[str, Any] | None:
        return rec(await self._pool.fetchrow("SELECT * FROM pln_forecast_run WHERE id = $1::uuid", run_id))

    async def list_runs(self, source: str | None = None, limit: int = 20) -> list[dict[str, Any]]:
        return recs(await self._pool.fetch("SELECT id, created_at, created_by, source, as_of_week, first_week, horizon_weeks, model_version, status, n_series, n_ok, n_fallback, n_insufficient, duration_ms, is_current, error "
                                           "FROM pln_forecast_run WHERE ($1::text IS NULL OR source = $1) ORDER BY created_at DESC LIMIT $2", source, limit))

    async def eval_rows(self, run_id: str, scope: str | None = None, horizon: str | None = None) -> list[dict[str, Any]]:
        return recs(await self._pool.fetch("SELECT * FROM pln_eval WHERE run_id = $1::uuid AND ($2::text IS NULL OR scope = $2) AND ($3::text IS NULL OR horizon_group = $3) ORDER BY scope, scope_key, model, horizon_group",
                                           run_id, scope, horizon))

    # ------------------------------------------------------------------------------------------------ plans
    def _line_filter(self, f: dict[str, Any], args: list[Any], alias_line: str = "pl", allowed: Sequence[str] | None = None) -> str:
        """The WHERE fragments shared by the workspace, the counts and the report. `allowed` is the caller's store scope (None = everything)."""
        w: list[str] = []

        def add(sql: str, *vals: Any) -> None:
            for v in vals:
                args.append(v)
            w.append(sql.format(*[f"${len(args) - len(vals) + 1 + i}" for i in range(len(vals))]))
        if allowed is not None:
            add(f"{alias_line}.location_id = ANY({{}}::text[])", list(allowed))
        if f.get("sport"):
            add("k.sport = {}", f["sport"])
        if f.get("category"):
            add("k.category = {}", f["category"])
        if f.get("region"):
            add(f"({REGION_EXPR}) = {{}}", f["region"])
        if f.get("zone"):
            add("l.parent_id = {}", f["zone"])
        if f.get("stores"):
            add(f"{alias_line}.location_id = ANY({{}}::text[])", list(f["stores"]))
        if f.get("product_id"):
            add(f"{alias_line}.product_id = {{}}::uuid", f["product_id"])
        if f.get("q"):
            add("(k.name ILIKE {0} OR k.sku ILIKE {0})", f"%{f['q']}%")
        if f.get("week_from"):
            add(f"{alias_line}.week_start >= {{}}", f["week_from"])
        if f.get("week_to"):
            add(f"{alias_line}.week_start <= {{}}", f["week_to"])
        if f.get("has_target") is True:
            w.append(f"{alias_line}.target_units IS NOT NULL")
        if f.get("has_target") is False:
            w.append(f"{alias_line}.target_units IS NULL")
        if f.get("has_adjustment") is True:
            w.append(f"{alias_line}.manager_forecast IS NOT NULL")
        if f.get("has_adjustment") is False:
            w.append(f"{alias_line}.manager_forecast IS NULL")
        if f.get("confidence"):
            add(f"{alias_line}.confidence = {{}}", f["confidence"])
        return (" AND " + " AND ".join(w)) if w else ""

    async def create_plan(self, *, name: str, fy: int, weeks: Sequence[date], source: str, run_id: str, scope: dict[str, Any], actor: str, parent: str | None = None, revision: int = 1,
                          allowed: Sequence[str] | None = None) -> dict[str, Any]:
        plan_id = uuid.uuid4()
        args: list[Any] = [plan_id, list(weeks), uuid.UUID(run_id)]
        extra = self._line_filter({k: v for k, v in scope.items() if k in ("sport", "category", "region", "zone", "stores", "product_id")}, args, "s", allowed)
        async with self._pool.acquire() as conn, conn.transaction():
            await conn.execute("INSERT INTO pln_plan (id, name, fy, first_week, last_week, source, forecast_run_id, scope, created_by, parent_plan_id, revision) VALUES ($1,$2,$3,$4,$5,$6,$7,$8::jsonb,$9,$10,$11)",
                               plan_id, name, fy, min(weeks), max(weeks), source, uuid.UUID(run_id), dumps(scope), actor, uuid.UUID(parent) if parent else None, revision)
            n = await conn.execute(
                f"""INSERT INTO pln_plan_line (plan_id, product_id, location_id, week_start, model_forecast, model_lo, model_hi, confidence)
                    SELECT $1, s.product_id, s.location_id, w.week_start, f.forecast, f.lo, f.hi, s.confidence
                    FROM pln_forecast_series s
                    JOIN unnest($2::date[]) AS w(week_start) ON TRUE
                    JOIN inv_sku k ON k.product_id = s.product_id
                    JOIN pln_location l ON l.id = s.location_id
                    JOIN pln_location par ON par.id = l.parent_id
                    LEFT JOIN pln_forecast f ON f.run_id = s.run_id AND f.product_id = s.product_id AND f.location_id = s.location_id AND f.week_start = w.week_start
                    WHERE s.run_id = $3 AND (f.week_start IS NOT NULL OR s.status = 'INSUFFICIENT_DATA'){extra}""", *args)
            count = int(n.split()[-1])
            if count == 0:
                raise ValueError("No product and store in the chosen scope has a forecast for these weeks")
            await self.audit(actor, [], "PLAN", str(plan_id), "CREATED", after={"name": name, "fy": fy, "weeks": [min(weeks).isoformat(), max(weeks).isoformat()], "lines": count, "source": source, "run_id": run_id,
                                                                                  "scope": scope, "parent": parent, "revision": revision}, conn=conn)
        return {**(rec(await self._pool.fetchrow("SELECT * FROM pln_plan WHERE id = $1", plan_id)) or {}), "lines": count}

    async def get_plan(self, plan_id: str) -> dict[str, Any] | None:
        return rec(await self._pool.fetchrow("SELECT * FROM pln_plan WHERE id = $1::uuid", plan_id))

    async def list_plans(self, status: str | None = None, fy: int | None = None, limit: int = 100) -> list[dict[str, Any]]:
        return recs(await self._pool.fetch(
            """SELECT p.*, (SELECT COUNT(*) FROM pln_plan_line l WHERE l.plan_id = p.id) AS line_count, (SELECT COUNT(*) FROM pln_plan_line l WHERE l.plan_id = p.id AND l.manager_forecast IS NOT NULL) AS adjusted_lines,
                      (SELECT COUNT(*) FROM pln_plan_line l WHERE l.plan_id = p.id AND l.target_units IS NOT NULL) AS target_lines
               FROM pln_plan p WHERE ($1::text IS NULL OR p.status = $1) AND ($2::int IS NULL OR p.fy = $2) ORDER BY p.created_at DESC LIMIT $3""", status, fy, limit))

    async def plan_location_ids(self, plan_id: str) -> list[str]:
        return [r["location_id"] for r in await self._pool.fetch("SELECT DISTINCT location_id FROM pln_plan_line WHERE plan_id = $1::uuid", plan_id)]

    async def plan_weeks(self, plan_id: str) -> list[date]:
        return [r["week_start"] for r in await self._pool.fetch("SELECT DISTINCT week_start FROM pln_plan_line WHERE plan_id = $1::uuid ORDER BY 1", plan_id)]

    async def workspace(self, plan: dict[str, Any], f: dict[str, Any], sort: str, page: int, size: int, allowed: Sequence[str] | None, through: date | None) -> dict[str, Any]:
        """One page of the weekly planning grid. Everything the grid shows comes from ONE query: no per-row calls. Forecast, target and actual are separate columns."""
        sources = list(SCOPE_SOURCES[plan["source"]])
        args: list[Any] = [uuid.UUID(plan["id"]), plan["source"], sources]
        flt = self._line_filter(f, args, "pl", allowed)
        base = f"""FROM pln_plan_line pl
                   JOIN inv_sku k ON k.product_id = pl.product_id
                   JOIN pln_location l ON l.id = pl.location_id
                   JOIN pln_location par ON par.id = l.parent_id
                   WHERE pl.plan_id = $1{flt}"""
        cargs: list[Any] = [uuid.UUID(plan["id"])]
        cflt = self._line_filter(f, cargs, "pl", allowed)
        total = await self._pool.fetchval(f"SELECT COUNT(*) FROM pln_plan_line pl JOIN inv_sku k ON k.product_id = pl.product_id JOIN pln_location l ON l.id = pl.location_id "
                                          f"JOIN pln_location par ON par.id = l.parent_id WHERE pl.plan_id = $1{cflt}", *cargs)
        order = LINE_SORT.get(sort, LINE_SORT["product"])
        args2 = list(args)
        args2.extend([size, page * size])
        rows = await self._pool.fetch(
            f"""SELECT pl.product_id, k.name AS product_name, k.sku, k.sport, k.category, pl.location_id, l.name AS store_name, ({REGION_EXPR}) AS region, l.parent_id AS zone, pl.week_start,
                       pl.model_forecast, pl.model_lo, pl.model_hi, pl.confidence, pl.manager_forecast, pl.target_units, pl.approved_forecast, pl.adj_reason, pl.adj_note, pl.adj_by, pl.adj_at, pl.line_version,
                       lf.forecast AS latest_forecast, lf.run_id AS latest_run_id,
                       a.net_units AS authoritative_units, a.data_source AS actual_source, COALESCE(adj.delta, 0) AS adjustment_units,
                       CASE WHEN a.net_units IS NULL THEN NULL ELSE a.net_units + COALESCE(adj.delta, 0) END AS actual_units,
                       sp.on_hand AS sellable_stock, sp.reserved AS reserved_stock, inc.qty AS incoming_stock,
                       ABS(COALESCE(pl.approved_forecast, pl.manager_forecast, pl.model_forecast) - (COALESCE(a.net_units, 0) + COALESCE(adj.delta, 0))) AS variance_abs
                {base.replace('WHERE pl.plan_id = $1', '''
                LEFT JOIN LATERAL (SELECT f2.forecast, f2.run_id FROM pln_forecast f2 JOIN pln_forecast_run r2 ON r2.id = f2.run_id AND r2.is_current AND r2.source = $2
                                   WHERE f2.product_id = pl.product_id AND f2.location_id = pl.location_id AND f2.week_start = pl.week_start LIMIT 1) lf ON TRUE
                LEFT JOIN LATERAL (SELECT w.net_units, w.data_source FROM pln_sales_week w WHERE w.product_id = pl.product_id AND w.location_id = pl.location_id AND w.week_start = pl.week_start
                                   AND w.data_source = ANY($3::text[]) ORDER BY (w.data_source = 'IMPORTED') DESC LIMIT 1) a ON TRUE
                LEFT JOIN LATERAL (SELECT SUM(x.delta_units) AS delta FROM pln_actual_adjustment x WHERE x.product_id = pl.product_id AND x.location_id = pl.location_id AND x.week_start = pl.week_start
                                   AND x.status = 'APPROVED') adj ON TRUE
                LEFT JOIN pln_stock_position sp ON sp.product_id = pl.product_id AND sp.location_id = pl.location_id
                LEFT JOIN LATERAL (SELECT SUM(i.qty) AS qty FROM pln_incoming i WHERE i.product_id = pl.product_id AND i.location_id = pl.location_id) inc ON TRUE
                WHERE pl.plan_id = $1''')}
                ORDER BY {order} LIMIT ${len(args) + 1} OFFSET ${len(args) + 2}""", *args2)
        out = []
        for r in recs(rows):
            wk = date.fromisoformat(r["week_start"])
            closed = bool(through and wk.toordinal() + 6 <= through.toordinal())
            r["week_closed"] = closed
            if not closed:
                r["actual_units"] = None                     # an open week's actual is incomplete: shown as unavailable, never as a low number
            elif r["actual_units"] is None and plan["source"] == "DSPORTS_LIVE":
                r["actual_units"] = float(r["adjustment_units"] or 0)       # the live feed is complete: a closed week with no lines is a CONFIRMED zero
            out.append(r)
        return {"items": out, "total": int(total), "page": page, "size": size}

    async def edit_lines(self, plan_id: str, edits: Sequence[dict[str, Any]], actor: str, roles: Sequence[str], allowed: Sequence[str] | None) -> dict[str, Any]:
        """Apply line edits atomically. A line whose version is not the one the caller saw is a CONFLICT: nothing at all is applied and the conflicting lines are returned."""
        changed: list[dict[str, Any]] = []
        conflicts: list[dict[str, Any]] = []
        async with self._pool.acquire() as conn, conn.transaction():
            plan = await conn.fetchrow("SELECT id, status, version FROM pln_plan WHERE id = $1::uuid FOR UPDATE", plan_id)
            if plan is None:
                raise LookupError("Plan not found")
            if plan["status"] not in ("DRAFT", "RETURNED"):
                raise PermissionError(f"PLAN_FROZEN:{plan['status']}")
            for e in edits:
                key = (uuid.UUID(e["product_id"]), e["location_id"], date.fromisoformat(str(e["week_start"])[:10]))
                if allowed is not None and e["location_id"] not in allowed:
                    conflicts.append({**_key(e), "code": "OUT_OF_SCOPE", "message": "This store is outside your scope"})
                    continue
                cur = await conn.fetchrow("SELECT * FROM pln_plan_line WHERE plan_id = $1::uuid AND product_id = $2 AND location_id = $3 AND week_start = $4 FOR UPDATE", plan_id, *key)
                if cur is None:
                    conflicts.append({**_key(e), "code": "NOT_IN_PLAN", "message": "This product, store and week is not part of the plan"})
                    continue
                if int(e["expected_line_version"]) != cur["line_version"]:
                    conflicts.append({**_key(e), "code": "VERSION_CONFLICT", "message": "Someone else changed this line", "current_version": cur["line_version"],
                                      "current": {"target_units": _f(cur["target_units"]), "manager_forecast": _f(cur["manager_forecast"])}})
                    continue
                sets, vals = [], []
                before = {"target_units": _f(cur["target_units"]), "manager_forecast": _f(cur["manager_forecast"]), "adj_reason": cur["adj_reason"]}
                after = dict(before)
                for field in ("target_units", "manager_forecast"):
                    if field in e:
                        vals.append(num(e[field]))
                        sets.append(f"{field} = ${len(vals) + 5}")
                        after[field] = None if e[field] is None else float(e[field])
                if "manager_forecast" in e:
                    vals.extend([e.get("reason_code") if e["manager_forecast"] is not None else None, e.get("note") if e["manager_forecast"] is not None else None])
                    sets.extend([f"adj_reason = ${len(vals) + 4}", f"adj_note = ${len(vals) + 5}"])
                    after["adj_reason"] = e.get("reason_code") if e["manager_forecast"] is not None else None
                if not sets:
                    continue
                await conn.execute(f"UPDATE pln_plan_line SET {', '.join(sets)}, adj_by = $5, adj_at = now(), line_version = line_version + 1 WHERE plan_id = $1::uuid AND product_id = $2 AND location_id = $3 AND week_start = $4",
                                   plan_id, key[0], key[1], key[2], actor, *vals)
                await self.audit(actor, roles, "PLAN_LINE", f"{plan_id}:{key[0]}:{key[1]}:{key[2]}", "EDITED", before=before, after=after, reason=e.get("reason_code") or e.get("note"), conn=conn)
                changed.append({**_key(e), "line_version": cur["line_version"] + 1})
            if conflicts:
                raise _Conflict(conflicts)                      # rolls the whole batch back: a partial save would hide which lines a person actually changed
            if changed:
                await conn.execute("UPDATE pln_plan SET version = version + 1, updated_at = now() WHERE id = $1::uuid", plan_id)
        return {"changed": len(changed), "lines": changed}

    async def set_status(self, plan_id: str, new_status: str, actor: str, roles: Sequence[str], expected_version: int, reason: str | None, *, snapshot_approved: bool = False,
                         supersede_parent: bool = False) -> dict[str, Any]:
        async with self._pool.acquire() as conn, conn.transaction():
            cur = await conn.fetchrow("SELECT * FROM pln_plan WHERE id = $1::uuid FOR UPDATE", plan_id)
            if cur is None:
                raise LookupError("Plan not found")
            if cur["version"] != expected_version:
                raise _Conflict([{"code": "VERSION_CONFLICT", "message": "The plan changed since you opened it", "current_version": cur["version"]}])
            cols = {"SUBMITTED": ("submitted_by", "submitted_at"), "APPROVED": ("decided_by", "decided_at"), "REJECTED": ("decided_by", "decided_at"), "RETURNED": ("decided_by", "decided_at"),
                    "PUBLISHED": ("published_by", "published_at")}.get(new_status)
            sets = "status = $2, version = version + 1, updated_at = now(), note = COALESCE($3, note)"
            if cols:
                sets += f", {cols[0]} = $4, {cols[1]} = now()"
            if new_status == "DRAFT":
                sets += ", submitted_by = NULL, submitted_at = NULL"
            await conn.execute(f"UPDATE pln_plan SET {sets} WHERE id = $1::uuid", plan_id, new_status, reason, *([actor] if cols else []))
            if snapshot_approved:
                await conn.execute("UPDATE pln_plan_line SET approved_forecast = COALESCE(manager_forecast, model_forecast) WHERE plan_id = $1::uuid", plan_id)
                if cur["parent_plan_id"] and supersede_parent:
                    await conn.execute("UPDATE pln_plan SET status = 'SUPERSEDED', version = version + 1, updated_at = now() WHERE id = $1 AND status IN ('APPROVED', 'PUBLISHED')", cur["parent_plan_id"])
                    await self.audit(actor, roles, "PLAN", str(cur["parent_plan_id"]), "SUPERSEDED", reason=f"replaced by revision {cur['revision']}", conn=conn)
            await self.audit(actor, roles, "PLAN", plan_id, f"STATUS_{new_status}", before={"status": cur["status"], "version": cur["version"]}, after={"status": new_status, "version": cur["version"] + 1}, reason=reason, conn=conn)
        return await self.get_plan(plan_id) or {}

    async def revise(self, plan_id: str, actor: str, roles: Sequence[str], name: str | None) -> dict[str, Any]:
        """A new plan (revision + 1) with every line copied, including targets and manager forecasts. The approved plan stays untouched until the revision is approved."""
        async with self._pool.acquire() as conn, conn.transaction():
            old = await conn.fetchrow("SELECT * FROM pln_plan WHERE id = $1::uuid FOR UPDATE", plan_id)
            if old is None:
                raise LookupError("Plan not found")
            if old["status"] not in ("APPROVED", "PUBLISHED"):
                raise PermissionError(f"NOT_REVISABLE:{old['status']}")
            open_rev = await conn.fetchval("SELECT id FROM pln_plan WHERE parent_plan_id = $1 AND status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'RETURNED')", old["id"])
            if open_rev:
                raise PermissionError(f"REVISION_OPEN:{open_rev}")
            new_id = uuid.uuid4()
            await conn.execute("INSERT INTO pln_plan (id, name, fy, first_week, last_week, source, forecast_run_id, scope, created_by, parent_plan_id, revision) "
                               "SELECT $1, $2, fy, first_week, last_week, source, forecast_run_id, scope, $3, id, revision + 1 FROM pln_plan WHERE id = $4", new_id, name or f"{old['name']} (revision {old['revision'] + 1})", actor, old["id"])
            n = await conn.execute("INSERT INTO pln_plan_line (plan_id, product_id, location_id, week_start, model_forecast, model_lo, model_hi, confidence, manager_forecast, target_units, adj_reason, adj_note, adj_by, adj_at) "
                                   "SELECT $1, product_id, location_id, week_start, model_forecast, model_lo, model_hi, confidence, manager_forecast, target_units, adj_reason, adj_note, adj_by, adj_at "
                                   "FROM pln_plan_line WHERE plan_id = $2", new_id, old["id"])
            await self.audit(actor, roles, "PLAN", str(new_id), "REVISION_CREATED", after={"parent": str(old["id"]), "revision": old["revision"] + 1, "lines": int(n.split()[-1])}, conn=conn)
        return await self.get_plan(str(new_id)) or {}

    async def bulk_growth_targets(self, plan_id: str, growth_pct: float, basis: str, f: dict[str, Any], actor: str, roles: Sequence[str], allowed: Sequence[str] | None, overwrite: bool) -> dict[str, Any]:
        """Set targets to a growth over a chosen baseline for every line in scope. `basis`: MODEL (the model forecast), MANAGER (manager forecast, else model). Lines with no baseline are skipped and counted."""
        col = "pl.model_forecast" if basis == "MODEL" else "COALESCE(pl.manager_forecast, pl.model_forecast)"
        a1: list[Any] = [uuid.UUID(plan_id), float(growth_pct), overwrite, actor]
        flt1 = self._line_filter(f, a1, "pl", allowed)
        a2: list[Any] = [uuid.UUID(plan_id)]
        flt2 = self._line_filter(f, a2, "pl", allowed)
        async with self._pool.acquire() as conn, conn.transaction():
            plan = await conn.fetchrow("SELECT status FROM pln_plan WHERE id = $1 FOR UPDATE", uuid.UUID(plan_id))
            if plan is None:
                raise LookupError("Plan not found")
            if plan["status"] not in ("DRAFT", "RETURNED"):
                raise PermissionError(f"PLAN_FROZEN:{plan['status']}")
            res = await conn.execute(
                f"""UPDATE pln_plan_line pl SET target_units = ROUND(({col}) * (1 + $2::numeric / 100.0), 3), adj_by = $4, adj_at = now(), line_version = pl.line_version + 1
                    FROM inv_sku k, pln_location l, pln_location par
                    WHERE pl.plan_id = $1 AND k.product_id = pl.product_id AND l.id = pl.location_id AND par.id = l.parent_id AND ({col}) IS NOT NULL AND ($3 OR pl.target_units IS NULL){flt1}""", *a1)
            n = int(res.split()[-1])
            skipped = int(await conn.fetchval(f"SELECT COUNT(*) FROM pln_plan_line pl JOIN inv_sku k ON k.product_id = pl.product_id JOIN pln_location l ON l.id = pl.location_id "
                                              f"JOIN pln_location par ON par.id = l.parent_id WHERE pl.plan_id = $1 AND ({col}) IS NULL{flt2}", *a2))
            if n:
                await conn.execute("UPDATE pln_plan SET version = version + 1, updated_at = now() WHERE id = $1", uuid.UUID(plan_id))
            await self.audit(actor, roles, "PLAN", plan_id, "BULK_GROWTH_TARGETS", after={"growth_pct": growth_pct, "basis": basis, "filters": f, "lines_set": n, "lines_without_baseline": skipped, "overwrite": overwrite}, conn=conn)
        return {"lines_set": n, "lines_without_baseline": skipped}
    # ------------------------------------------------------------------------------------------------ the plan-versus-actual report
    async def report(self, plan: dict[str, Any], grain: str, f: dict[str, Any], allowed: Sequence[str] | None, week_keys: dict[date, str], through: date | None, use_model: bool = False) -> list[dict[str, Any]]:
        """Forecast, target and actual rolled up to a grain. Variances are computed ONLY over the rows that have both sides, and the row counts say how many that was."""
        sources = list(SCOPE_SOURCES[plan["source"]])
        key_expr = {"sku": "k.name", "product": "k.name", "category": "k.sport || ' / ' || k.category", "sport": "k.sport", "store": "pl.location_id", "zone": "l.parent_id", "region": REGION_EXPR,
                    "week": "pl.week_start::text", "month": "wk.key", "quarter": "wk.key", "fy": "wk.key", "total": "'ALL'"}.get(grain)
        if key_expr is None:
            raise ValueError("grain must be one of sku, category, sport, store, zone, region, week, month, quarter, fy, total")
        wk_join = "JOIN unnest($4::date[], $5::text[]) AS wk(week_start, key) ON wk.week_start = pl.week_start" if grain in ("month", "quarter", "fy") else ""
        args: list[Any] = [uuid.UUID(plan["id"]), plan["source"], sources]
        if grain in ("month", "quarter", "fy"):
            args += [list(week_keys), list(week_keys.values())]
        else:
            args += [[], []]
        flt = self._line_filter(f, args, "pl", allowed)
        fcol = "pl.model_forecast" if use_model else "COALESCE(pl.approved_forecast, pl.manager_forecast, pl.model_forecast)"
        closed_sql = f"pl.week_start + 6 <= '{through.isoformat()}'::date" if through else "FALSE"
        missing = "0" if plan["source"] == "DSPORTS_LIVE" else "NULL"          # live feed: complete, so a closed week with no lines is a confirmed zero; elsewhere a missing week is unknown, not zero
        rows = await self._pool.fetch(
            f"""WITH r AS (
                  SELECT {key_expr} AS gkey, pl.week_start, {fcol} AS f, pl.target_units AS t, pl.model_forecast AS m,
                         CASE WHEN NOT ({closed_sql}) THEN NULL ELSE COALESCE(w.net_units, {missing}) + COALESCE(adj.delta, 0) END AS a, COALESCE(adj.delta, 0) AS adj
                  FROM pln_plan_line pl
                  JOIN inv_sku k ON k.product_id = pl.product_id
                  JOIN pln_location l ON l.id = pl.location_id
                  JOIN pln_location par ON par.id = l.parent_id
                  {wk_join}
                  LEFT JOIN LATERAL (SELECT w0.net_units, w0.data_source FROM pln_sales_week w0 WHERE w0.product_id = pl.product_id AND w0.location_id = pl.location_id AND w0.week_start = pl.week_start
                                     AND w0.data_source = ANY($3::text[]) ORDER BY (w0.data_source = 'IMPORTED') DESC LIMIT 1) w ON TRUE
                  LEFT JOIN LATERAL (SELECT SUM(x.delta_units) AS delta FROM pln_actual_adjustment x WHERE x.product_id = pl.product_id AND x.location_id = pl.location_id AND x.week_start = pl.week_start
                                     AND x.status = 'APPROVED') adj ON TRUE
                  WHERE pl.plan_id = $1 AND $2::text IS NOT NULL AND cardinality($4::date[]) >= 0 AND cardinality($5::text[]) >= 0{flt})
                SELECT gkey, COUNT(*) AS lines, COUNT(a) AS with_actual, COUNT(f) AS with_forecast, COUNT(t) AS with_target,
                       SUM(a) AS actual, SUM(f) AS forecast, SUM(t) AS target, SUM(m) AS model,
                       SUM(a) FILTER (WHERE f IS NOT NULL) AS a_f, SUM(f) FILTER (WHERE a IS NOT NULL) AS f_a,
                       SUM(a) FILTER (WHERE t IS NOT NULL) AS a_t, SUM(t) FILTER (WHERE a IS NOT NULL) AS t_a, SUM(adj) AS adjustments, MIN(week_start) AS first_week
                FROM r GROUP BY gkey ORDER BY MIN(week_start), gkey""", *args)
        out = []
        for r in recs(rows):
            fv = None if r["a_f"] is None or r["f_a"] is None else round(r["a_f"] - r["f_a"], 3)
            tv = None if r["a_t"] is None or r["t_a"] is None else round(r["a_t"] - r["t_a"], 3)
            r["forecast_variance"] = fv
            r["forecast_variance_pct"] = None if fv is None or not r["f_a"] else round(fv / r["f_a"] * 100, 1)
            r["target_variance"] = tv
            r["target_attainment_pct"] = None if tv is None or not r["t_a"] or r["t_a"] <= 0 else round(r["a_t"] / r["t_a"] * 100, 1)
            out.append(r)
        return out


class _Conflict(Exception):
    def __init__(self, conflicts: list[dict[str, Any]]):
        super().__init__("conflict")
        self.conflicts = conflicts


def _key(e: dict[str, Any]) -> dict[str, Any]:
    return {"product_id": str(e["product_id"]), "location_id": e["location_id"], "week_start": str(e["week_start"])[:10]}


def _f(v: Any) -> float | None:
    return None if v is None else float(v)
