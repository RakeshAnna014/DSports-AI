"""All SQL of the planning module (AI database, `pln_*`; reads the Inventory AI's `inv_*` tables, never writes them and never touches the commerce database)."""
from __future__ import annotations

import json
import uuid
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from typing import Any, Iterable, Sequence

import asyncpg
import numpy as np

from app.planning import demo, regions
from app.planning.dbutil import money
from app.planning.calendar import Calendar
from app.planning.repo_insights import InsightsMixin
from app.planning.repo_plans import PlansMixin
from app.planning.repo_reports import ReportsMixin

from app.planning.dbutil import DEMO_SRC, IMPORTED, JSON_COLS, LIVE, SOURCES, dumps, rec, recs  # noqa: F401  (re-exported for the other planning modules)


class PlanningRepository(PlansMixin, ReportsMixin, InsightsMixin):
    def __init__(self, pool: asyncpg.Pool):
        self._pool = pool

    @property
    def pool(self) -> asyncpg.Pool:
        return self._pool

    # ------------------------------------------------------------------------------------------------ audit
    async def audit(self, actor: str, roles: Sequence[str], entity: str, entity_id: str | None, action: str, *, before: Any = None, after: Any = None,
                    reason: str | None = None, conn: asyncpg.Connection | None = None) -> None:
        sql = "INSERT INTO pln_audit (actor_hash, roles, entity, entity_id, action, before, after, reason, correlation_id) VALUES ($1,$2,$3,$4,$5,$6::jsonb,$7::jsonb,$8,$9)"
        from app.obs.context import correlation_id_var
        args = (actor, ",".join(roles), entity, entity_id, action, None if before is None else dumps(before), None if after is None else dumps(after), reason, correlation_id_var.get())
        if conn is not None:
            await conn.execute(sql, *args)
        else:
            await self._pool.execute(sql, *args)

    async def audit_log(self, entity: str | None = None, entity_id: str | None = None, limit: int = 100) -> list[dict[str, Any]]:
        rows = await self._pool.fetch("SELECT * FROM pln_audit WHERE ($1::text IS NULL OR entity = $1) AND ($2::text IS NULL OR entity_id = $2) ORDER BY id DESC LIMIT $3", entity, entity_id, limit)
        return recs(rows)

    async def audit_for_plan(self, plan_id: str, limit: int = 100) -> list[dict[str, Any]]:
        """Everything that happened to a plan and its lines (line ids are `<plan id>:<product>:<store>:<week>`)."""
        rows = await self._pool.fetch("SELECT * FROM pln_audit WHERE entity_id = $1 OR entity_id LIKE $1 || ':%' ORDER BY id DESC LIMIT $2", plan_id, limit)
        return recs(rows)

    # ------------------------------------------------------------------------------------------------ locations
    async def list_locations(self, level: str | None = None) -> list[dict[str, Any]]:
        return recs(await self._pool.fetch("SELECT * FROM pln_location WHERE ($1::text IS NULL OR level = $1) ORDER BY level, id", level))

    async def location(self, location_id: str) -> dict[str, Any] | None:
        return rec(await self._pool.fetchrow("SELECT * FROM pln_location WHERE id = $1", location_id))

    async def descendant_stores(self, location_ids: Sequence[str]) -> list[str]:
        """The STORE ids at or under the given locations (a store id returns itself; a region or zone returns every store below)."""
        rows = await self._pool.fetch(
            """WITH RECURSIVE t AS (SELECT id, level FROM pln_location WHERE id = ANY($1::text[])
                                    UNION ALL SELECT c.id, c.level FROM pln_location c JOIN t ON c.parent_id = t.id)
               SELECT DISTINCT id FROM t WHERE level = 'STORE' ORDER BY id""", list(location_ids))
        return [r["id"] for r in rows]

    async def ensure_online_locations(self) -> None:
        """Region rows (if missing) and one ONLINE location per region, so web orders have a place in the hierarchy. Idempotent."""
        async with self._pool.acquire() as conn, conn.transaction():
            for code, name in regions.REGION_NAMES.items():
                await conn.execute("INSERT INTO pln_location (id, name, level, channel, data_source) VALUES ($1,$2,'REGION','NONE','CONFIG') ON CONFLICT (id) DO NOTHING", code, name)
                await conn.execute("INSERT INTO pln_location (id, name, level, parent_id, channel, data_source) VALUES ($1,$2,'STORE',$3,'ONLINE','CONFIG') ON CONFLICT (id) DO NOTHING",
                                   regions.online_location(code), f"{name} online orders", code)

    # ------------------------------------------------------------------------------------------------ ingest (DSports live)
    async def start_ingest(self, actor: str, mode: str) -> dict[str, Any] | None:
        await self._pool.execute("UPDATE pln_ingest_run SET status = 'FAILED', finished_at = now(), error = 'abandoned: the service stopped while it was running' "
                                 "WHERE status = 'RUNNING' AND started_at < now() - interval '30 minutes'")
        try:
            r = await self._pool.fetchrow("INSERT INTO pln_ingest_run (id, mode, requested_by) VALUES ($1,$2,$3) RETURNING *", uuid.uuid4(), mode, actor)
        except asyncpg.UniqueViolationError:
            return None
        await self.ensure_online_locations()
        return rec(r)

    async def ingest_cursor(self) -> tuple[datetime | None, str | None]:
        r = await self._pool.fetchrow("SELECT cursor_updated_at, cursor_line_id FROM pln_ingest_run WHERE status = 'SUCCEEDED' AND cursor_updated_at IS NOT NULL ORDER BY finished_at DESC LIMIT 1")
        return (r["cursor_updated_at"], str(r["cursor_line_id"])) if r else (None, None)

    async def finish_ingest(self, run_id: str, status: str, res: Any, cursor: tuple[datetime | None, str | None] | None, error: str | None) -> None:
        ts, line = cursor if cursor else (None, None)
        await self._pool.execute(
            """UPDATE pln_ingest_run SET status = $2, finished_at = now(), cursor_updated_at = $3, cursor_line_id = $4::uuid, lines_read = $5, lines_changed = $6, lines_unchanged = $7,
                      lines_excluded = $8, weeks_touched = $9, error = $10, detail = $11::jsonb WHERE id = $1::uuid""",
            run_id, status, ts, line, res.lines_read, res.lines_changed, res.lines_unchanged, res.lines_excluded, res.weeks_touched, error,
            dumps({k: getattr(res, k) for k in ("unknown_region_lines", "unknown_status_lines", "bad_rows", "unknown_products", "restated_lines", "data_through")}))

    async def list_ingest_runs(self, limit: int = 10) -> list[dict[str, Any]]:
        return recs(await self._pool.fetch("SELECT * FROM pln_ingest_run ORDER BY started_at DESC LIMIT $1", limit))

    _LINE_COLS = ("line_id", "order_id", "order_number", "product_id", "sku", "quantity", "order_status", "placed_at", "cancelled_at", "currency", "unit_price", "line_total", "allocated_discount",
                  "approved_return_qty", "ship_city", "ship_state", "location_id", "week_start", "counted", "gross_units", "cancelled_units", "returned_units", "net_units", "gross_revenue",
                  "discount", "net_revenue", "source_updated_at")

    async def upsert_lines(self, lines: list[dict[str, Any]]) -> tuple[int, int, set[tuple[Any, ...]], int]:
        """Insert new lines and update changed ones. Returns (changed, unchanged, keys of weeks to re-aggregate, lines whose product is unknown to the Inventory AI).
        A line is unchanged when every stored value is identical, so re-reading the same feed rewrites nothing."""
        if not lines:
            return 0, 0, set(), 0
        cols = self._LINE_COLS
        ids = [ln["line_id"] for ln in lines]
        async with self._pool.acquire() as conn, conn.transaction():
            old = {r["line_id"]: r for r in await conn.fetch(f"SELECT {', '.join(cols)} FROM pln_sales_line WHERE line_id = ANY($1::uuid[])", ids)}
            changed, unchanged, keys = 0, 0, set()
            for ln in lines:
                prev = old.get(ln["line_id"])
                if prev is not None and all(_same(prev[c], ln[c]) for c in cols):
                    unchanged += 1
                    continue
                if prev is not None:
                    keys.add((prev["product_id"], prev["location_id"], prev["week_start"]))      # it moved weeks or location: the old key must be recomputed too
                keys.add((ln["product_id"], ln["location_id"], ln["week_start"]))
                await conn.execute(
                    f"INSERT INTO pln_sales_line ({', '.join(cols)}, ingested_at) VALUES ({', '.join('$' + str(i + 1) for i in range(len(cols)))}, now()) "
                    f"ON CONFLICT (line_id) DO UPDATE SET {', '.join(f'{c} = EXCLUDED.{c}' for c in cols if c != 'line_id')}, ingested_at = now()",
                    *[ln[c] for c in cols])
                changed += 1
            pids = list({ln["product_id"] for ln in lines})
            known = {r["product_id"] for r in await conn.fetch("SELECT product_id FROM inv_sku WHERE product_id = ANY($1::uuid[])", pids)}
            unknown = sum(1 for ln in lines if ln["product_id"] not in known)
        return changed, unchanged, keys, unknown

    async def reaggregate_live(self, keys: set[tuple[Any, ...]]) -> int:
        """Rebuild `pln_sales_week` for exactly the (product, location, week) keys that changed, from the stored lines: the aggregate is a plain SUM, so it always reconciles."""
        n = 0
        async with self._pool.acquire() as conn, conn.transaction():
            for product_id, location_id, week in keys:
                row = await conn.fetchrow(
                    """SELECT COALESCE(SUM(gross_units),0) g, COALESCE(SUM(cancelled_units),0) c, COALESCE(SUM(returned_units),0) r, COALESCE(SUM(net_units),0) n,
                              COALESCE(SUM(gross_revenue),0) gr, COALESCE(SUM(discount),0) d, COALESCE(SUM(net_revenue),0) nr, COUNT(*) FILTER (WHERE counted) lc
                       FROM pln_sales_line WHERE product_id = $1 AND location_id = $2 AND week_start = $3""", product_id, location_id, week)
                if row["lc"] == 0:
                    await conn.execute("DELETE FROM pln_sales_week WHERE product_id=$1 AND location_id=$2 AND week_start=$3 AND data_source=$4", product_id, location_id, week, LIVE)
                else:
                    await conn.execute(
                        """INSERT INTO pln_sales_week (product_id, location_id, week_start, data_source, gross_units, cancelled_units, returned_units, net_units, gross_revenue, discount, net_revenue,
                                                       line_count, aggregated_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12, now())
                           ON CONFLICT (product_id, location_id, week_start, data_source) DO UPDATE SET gross_units=$5, cancelled_units=$6, returned_units=$7, net_units=$8, gross_revenue=$9,
                                  discount=$10, net_revenue=$11, line_count=$12, aggregated_at=now()""",
                        product_id, location_id, week, LIVE, row["g"], row["c"], row["r"], row["n"], row["gr"], row["d"], row["nr"], row["lc"])
                n += 1
        return n

    async def mark_live_data_through(self, cal: Calendar) -> date:
        through = cal.today() - timedelta(days=1)
        await self._pool.execute("INSERT INTO pln_data_through (data_source, data_through) VALUES ($1,$2) ON CONFLICT (data_source) DO UPDATE SET data_through = $2, updated_at = now()", LIVE, through)
        return through

    async def data_through(self, source: str) -> date | None:
        return await self._pool.fetchval("SELECT data_through FROM pln_data_through WHERE data_source = $1", source)

    async def set_data_through(self, source: str, day: date, conn: asyncpg.Connection | None = None) -> None:
        sql = ("INSERT INTO pln_data_through (data_source, data_through) VALUES ($1,$2) ON CONFLICT (data_source) DO UPDATE SET "
               "data_through = GREATEST(pln_data_through.data_through, $2), updated_at = now()")
        await (conn or self._pool).execute(sql, source, day)

    async def reconcile_live(self) -> dict[str, Any]:
        """Aggregates versus the stored lines: they must agree to the unit and the cent. Anything else is reported, not hidden."""
        r = await self._pool.fetchrow(
            """SELECT (SELECT COALESCE(SUM(net_units),0) FROM pln_sales_line) AS line_net, (SELECT COALESCE(SUM(net_units),0) FROM pln_sales_week WHERE data_source = $1) AS week_net,
                      (SELECT COALESCE(SUM(gross_units),0) FROM pln_sales_line) AS line_gross, (SELECT COALESCE(SUM(gross_units),0) FROM pln_sales_week WHERE data_source = $1) AS week_gross,
                      (SELECT COALESCE(SUM(net_revenue),0) FROM pln_sales_line) AS line_rev, (SELECT COALESCE(SUM(net_revenue),0) FROM pln_sales_week WHERE data_source = $1) AS week_rev,
                      (SELECT COUNT(*) FROM pln_sales_line) AS lines, (SELECT COUNT(*) FROM pln_sales_line WHERE NOT counted) AS excluded""", LIVE)
        d = rec(r) or {}
        d["reconciled"] = d["line_net"] == d["week_net"] and d["line_gross"] == d["week_gross"] and abs(d["line_rev"] - d["week_rev"]) < 0.005
        return d

    # ------------------------------------------------------------------------------------------------ demo network and history
    async def seed_demo(self, cal: Calendar, as_of: date | None = None) -> dict[str, Any]:
        """Build (or rebuild) the labelled demonstration network, its store-level weekly history, stock and open orders from the Inventory AI's synthetic data. Idempotent."""
        info = await self._pool.fetchrow("SELECT MAX(day) AS last_day, MIN(day) AS first_day FROM inv_demand_daily")
        if info is None or info["last_day"] is None:
            raise ValueError("The Inventory AI has no demand history to build the demonstration from: run `python -m app.inventory.cli seed` first")
        last_day: date = info["last_day"]
        stock_as_of = await self._pool.fetchval("SELECT MAX(as_of) FROM inv_position") or last_day
        layout = demo.store_layout(as_of or stock_as_of)
        ws = cal.week_start_day
        async with self._pool.acquire() as conn, conn.transaction():
            await conn.execute("DELETE FROM pln_stock_position WHERE data_source = $1", DEMO_SRC)
            await conn.execute("DELETE FROM pln_incoming WHERE data_source = $1", DEMO_SRC)
            await conn.execute("DELETE FROM pln_sales_week WHERE data_source = $1", DEMO_SRC)
            for code, name in regions.REGION_NAMES.items():
                await conn.execute("INSERT INTO pln_location (id, name, level, channel, data_source) VALUES ($1,$2,'REGION','NONE','POC_REFERENCE') ON CONFLICT (id) DO NOTHING", code, name)
            zones_done: set[str] = set()
            for s in layout:
                if s.zone not in zones_done:
                    zones_done.add(s.zone)
                    await conn.execute("INSERT INTO pln_location (id, name, level, parent_id, channel, data_source) VALUES ($1,$2,'ZONE',$3,'NONE','POC_REFERENCE') ON CONFLICT (id) DO NOTHING",
                                       s.zone, f"{demo.REGION_NAMES[s.region]} Zone {s.zone[-1]}", s.region)
                await conn.execute(
                    """INSERT INTO pln_location (id, name, level, parent_id, channel, status, opened_on, size_factor, data_source) VALUES ($1,$2,'STORE',$3,'STORE',$4,$5,$6,'POC_REFERENCE')
                       ON CONFLICT (id) DO UPDATE SET name=$2, parent_id=$3, status=$4, opened_on=$5, size_factor=$6""", s.id, s.name, s.zone, s.status, s.opened_on, s.size_factor)
            by_region: dict[str, list[demo.StoreSpec]] = {}
            for s in layout:
                by_region.setdefault(s.region, []).append(s)
            sport_of = {r["product_id"]: r["sport"] for r in await conn.fetch("SELECT product_id, sport FROM inv_sku")}
            rows = await conn.fetch(
                f"""SELECT product_id, region, {demo.week_sql(ws)} AS week_start, SUM(units)::int AS units, SUM(units * unit_price / (1 - discount_pct / 100.0)) AS gross_rev,
                           SUM(units * unit_price) AS net_rev, SUM(CASE WHEN stockout THEN 1 ELSE 0 END)::int AS so_days, COUNT(*)::int AS ndays
                    FROM inv_demand_daily GROUP BY product_id, region, week_start ORDER BY product_id, region, week_start""")
            out: list[tuple] = []
            current: tuple[Any, Any] | None = None
            rng = None
            for r in rows:
                if r["ndays"] < 7:
                    continue                                       # a partial week is not a week of history: it is left out rather than shown as low sales
                key = (r["product_id"], r["region"])
                if key != current:
                    current, rng = key, demo._rng("split", str(r["product_id"]), r["region"])
                stores = by_region.get(r["region"], [])
                probs = demo.store_probs(stores, sport_of.get(r["product_id"], ""), r["week_start"])
                parts = demo.split_units(r["units"], probs, rng)
                if r["units"] > 0 and probs.sum() > 0:
                    gross_per, net_per = float(r["gross_rev"]) / r["units"], float(r["net_rev"]) / r["units"]
                else:
                    gross_per = net_per = 0.0
                for s, u in zip(stores, parts):
                    if probs[stores.index(s)] == 0:
                        continue                                    # a store that was not open has no row (unknown is not zero)
                    u = int(u)
                    out.append((r["product_id"], s.id, r["week_start"], DEMO_SRC, u, 0, 0, u, money(u * gross_per), money(u * (gross_per - net_per)), money(u * net_per), u, r["so_days"]))
            await conn.copy_records_to_table("pln_sales_week", records=out, columns=("product_id", "location_id", "week_start", "data_source", "gross_units", "cancelled_units", "returned_units",
                                                                                     "net_units", "gross_revenue", "discount", "net_revenue", "line_count", "stockout_days"))
            await self.set_data_through(DEMO_SRC, last_day, conn)
            # stock and open orders: the Inventory AI's regional position, split by the stores' current share
            sku_sport = sport_of
            stock_rows, incoming_rows = [], []
            for p in await conn.fetch("SELECT product_id, region, on_hand, as_of FROM inv_position"):
                stores = by_region.get(p["region"], [])
                probs = demo.store_probs([s for s in stores if s.status == "ACTIVE"], sku_sport.get(p["product_id"], ""), p["as_of"])
                active = [s for s in stores if s.status == "ACTIVE"]
                for s, q in zip(active, demo.allocate(int(p["on_hand"]), probs)):
                    stock_rows.append((p["product_id"], s.id, p["as_of"], int(q), 0, DEMO_SRC))
            await conn.copy_records_to_table("pln_stock_position", records=stock_rows, columns=("product_id", "location_id", "as_of", "on_hand", "reserved", "data_source"))
            for po in await conn.fetch("SELECT id, product_id, region, qty, eta FROM inv_purchase_order WHERE status = 'OPEN'"):
                active = [s for s in by_region.get(po["region"], []) if s.status == "ACTIVE"]
                probs = demo.store_probs(active, sku_sport.get(po["product_id"], ""), stock_as_of)
                for s, q in zip(active, demo.allocate(int(po["qty"]), probs)):
                    if q > 0:
                        incoming_rows.append((uuid.uuid4(), po["product_id"], s.id, int(q), po["eta"], f"PO {str(po['id'])[:8]}", DEMO_SRC))
            await conn.copy_records_to_table("pln_incoming", records=incoming_rows, columns=("id", "product_id", "location_id", "qty", "eta", "ref", "data_source"))
        return {"stores": len(layout), "week_rows": len(out), "stock_rows": len(stock_rows), "incoming_rows": len(incoming_rows), "history_through": last_day.isoformat(), "stock_as_of": stock_as_of.isoformat(),
                "note": demo.seasonal_note()}


def _same(a: Any, b: Any) -> bool:
    if isinstance(a, Decimal) or isinstance(b, Decimal):
        try:
            return Decimal(str(a if a is not None else 0)) == Decimal(str(b if b is not None else 0))
        except Exception:  # noqa: BLE001
            return False
    return a == b
