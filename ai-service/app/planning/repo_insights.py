"""SQL behind the AI planning analysis and the review queue. Read-only except for the analysis record and the adopt/dismiss log; nothing here touches plans, lines or commerce data."""
from __future__ import annotations

import uuid
from datetime import date, timedelta
from typing import Any, Sequence

from app.planning.dbutil import SCOPE_SOURCES, dumps, rec, recs
from app.planning.repo_plans import REGION_EXPR

_FROM_LINES = "FROM pln_plan_line pl JOIN inv_sku k ON k.product_id = pl.product_id JOIN pln_location l ON l.id = pl.location_id JOIN pln_location par ON par.id = l.parent_id"
_FROM_WEEKS = "FROM pln_sales_week pl JOIN inv_sku k ON k.product_id = pl.product_id JOIN pln_location l ON l.id = pl.location_id JOIN pln_location par ON par.id = l.parent_id"
ANALYSIS_FILTERS = ("sport", "category", "region", "zone", "stores", "product_id")


class InsightsMixin:
    # ------------------------------------------------------------------------------------------------ evidence for one plan
    async def analysis_evidence(self, plan: dict[str, Any], f: dict[str, Any], allowed: Sequence[str] | None, through: date | None, n_recent_cap: int = 4) -> dict[str, Any]:
        pid = uuid.UUID(plan["id"])
        sources = list(SCOPE_SOURCES[plan["source"]])
        flt_in = {k: v for k, v in f.items() if k in ANALYSIS_FILTERS}
        weeks = await self.plan_weeks(plan["id"])                         # type: ignore[attr-defined]
        next_weeks = weeks[:4]
        first = weeks[0] if weeks else None
        run = await self.get_run(str(plan["forecast_run_id"])) if plan.get("forecast_run_id") else None   # type: ignore[attr-defined]
        recent: list[date] = []
        if first and through:
            w = first - timedelta(days=7)
            for _ in range(60):
                if len(recent) >= n_recent_cap:
                    break
                if w + timedelta(days=6) <= through:
                    recent.append(w)
                w -= timedelta(days=7)
        hist_weeks = await self._pool.fetchval(                           # type: ignore[attr-defined]
            f"SELECT COUNT(DISTINCT week_start) FROM pln_sales_week WHERE data_source = ANY($1::text[]) AND week_start + 6 <= $2::date", sources, through or date(1970, 1, 1))
        # --- the lines of the plan: how many are low confidence or have no forecast
        args: list[Any] = [pid]
        flt = self._line_filter(flt_in, args, "pl", allowed)               # type: ignore[attr-defined]
        lines = rec(await self._pool.fetchrow(                              # type: ignore[attr-defined]
            f"SELECT COUNT(*) AS lines, COUNT(*) FILTER (WHERE pl.confidence = 'LOW') AS low_confidence, COUNT(*) FILTER (WHERE pl.model_forecast IS NULL) AS no_forecast {_FROM_LINES} WHERE pl.plan_id = $1{flt}", *args)) or {}
        # --- forecast of the next weeks against the last closed weeks, per series
        recent_rows: list[dict[str, Any]] = []
        if recent and next_weeks:
            args = [pid, next_weeks, sources, recent]
            flt = self._line_filter(flt_in, args, "pl", allowed)           # type: ignore[attr-defined]
            recent_rows = recs(await self._pool.fetch(                       # type: ignore[attr-defined]
                f"""WITH fc AS (
                      SELECT pl.product_id, pl.location_id, k.name, k.sport || ' / ' || k.category AS category_label,
                             AVG(COALESCE(pl.approved_forecast, pl.manager_forecast, pl.model_forecast)) AS f_avg, AVG(pl.model_forecast) AS model_avg
                      {_FROM_LINES} WHERE pl.plan_id = $1 AND pl.week_start = ANY($2::date[]){flt} GROUP BY 1, 2, 3, 4)
                    SELECT fc.*, COALESCE(rc.r_sum, 0) AS r_sum, rc.so AS stockout_days, COALESCE(rc.n, 0) AS rc_n
                    FROM fc LEFT JOIN LATERAL (
                      SELECT SUM(x.net_units) AS r_sum, SUM(x.stockout_days) AS so, COUNT(*) AS n FROM (
                        SELECT DISTINCT ON (w.week_start) w.week_start, w.net_units, w.stockout_days FROM pln_sales_week w
                        WHERE w.product_id = fc.product_id AND w.location_id = fc.location_id AND w.data_source = ANY($3::text[]) AND w.week_start = ANY($4::date[])
                        ORDER BY w.week_start, (w.data_source = 'IMPORTED') DESC) x) rc ON TRUE
                    WHERE fc.f_avg IS NOT NULL AND fc.f_avg <> 'NaN'::float8"""
                , *args))
            if plan["source"] != "DSPORTS_LIVE":                              # demonstration: a series with no recent rows is unknown, not zero
                recent_rows = [r for r in recent_rows if r["rc_n"] >= len(recent)]
        # --- closed plan weeks: report rows and per-store week ratios
        variance_rows: list[dict[str, Any]] = []
        for grain in ("category", "store") if plan["source"] != "DSPORTS_LIVE" else ("category",):
            for r in await self.report(plan, grain, flt_in, allowed, {}, through):   # type: ignore[attr-defined]
                variance_rows.append({**r, "dim": grain})
        store_weeks: list[dict[str, Any]] = []
        if through:
            args = [pid, sources, through]
            flt = self._line_filter(flt_in, args, "pl", allowed)           # type: ignore[attr-defined]
            store_weeks = recs(await self._pool.fetch(                       # type: ignore[attr-defined]
                f"""SELECT pl.location_id, pl.week_start, SUM(w.net_units) AS a, SUM(COALESCE(pl.approved_forecast, pl.manager_forecast, pl.model_forecast)) AS f
                    {_FROM_LINES} JOIN LATERAL (SELECT w0.net_units FROM pln_sales_week w0 WHERE w0.product_id = pl.product_id AND w0.location_id = pl.location_id AND w0.week_start = pl.week_start
                                               AND w0.data_source = ANY($2::text[]) ORDER BY (w0.data_source = 'IMPORTED') DESC LIMIT 1) w ON TRUE
                    WHERE pl.plan_id = $1 AND pl.week_start + 6 <= $3::date{flt} GROUP BY 1, 2""", *args))
        # --- trend: the last N closed weeks against the N before, by category
        trend_rows: list[dict[str, Any]] = []
        closed = [r["week_start"] for r in await self._pool.fetch(           # type: ignore[attr-defined]
            "SELECT DISTINCT week_start FROM pln_sales_week WHERE data_source = ANY($1::text[]) AND week_start + 6 <= $2::date ORDER BY 1 DESC LIMIT 8", sources, through or date(1970, 1, 1))]
        if len(closed) >= 8:
            args = [sources, closed[:4], closed[4:8]]
            flt = self._line_filter(flt_in, args, "pl", allowed)           # type: ignore[attr-defined]
            trend_rows = recs(await self._pool.fetch(                        # type: ignore[attr-defined]
                f"""SELECT k.sport || ' / ' || k.category AS category_label, SUM(pl.net_units) FILTER (WHERE pl.week_start = ANY($2::date[])) AS last,
                           SUM(pl.net_units) FILTER (WHERE pl.week_start = ANY($3::date[])) AS prior
                    {_FROM_WEEKS} WHERE pl.data_source = ANY($1::text[]){flt} GROUP BY 1""", *args))
        # --- stock risk from the plan's allocation drafts
        summary = await self.alloc_summary(plan["id"], allowed)             # type: ignore[attr-defined]
        top_risks: list[dict[str, Any]] = []
        avoided = 0.0
        if summary.get("recs"):
            top_risks = recs(await self._pool.fetch(                         # type: ignore[attr-defined]
                """SELECT r.location_id, k.name, r.on_hand, r.incoming, r.shortage, r.coverage_days FROM pln_alloc_rec r JOIN inv_sku k ON k.product_id = r.product_id
                   WHERE r.plan_id = $1 AND r.status <> 'SUPERSEDED' AND r.risk_level = 'HIGH' AND ($2::text[] IS NULL OR r.location_id = ANY($2::text[])) ORDER BY r.shortage DESC NULLS LAST LIMIT 8""",
                pid, None if allowed is None else list(allowed)))
            avoided = float(await self._pool.fetchval("SELECT COALESCE(SUM(avoids_order_qty), 0) FROM pln_transfer_rec WHERE plan_id = $1 AND status <> 'SUPERSEDED'", pid) or 0)   # type: ignore[attr-defined]
        # --- the largest forecasts of the first plan week
        focus_rows: list[dict[str, Any]] = []
        if first:
            args = [pid, first]
            flt = self._line_filter(flt_in, args, "pl", allowed)           # type: ignore[attr-defined]
            focus_rows = recs(await self._pool.fetch(                        # type: ignore[attr-defined]
                f"""SELECT k.name, pl.location_id, COALESCE(pl.approved_forecast, pl.manager_forecast, pl.model_forecast) AS f, pl.confidence
                    {_FROM_LINES} WHERE pl.plan_id = $1 AND pl.week_start = $2 AND COALESCE(pl.approved_forecast, pl.manager_forecast, pl.model_forecast) IS NOT NULL{flt}
                    ORDER BY 3 DESC LIMIT 8""", *args))
        return {"plan": {"id": plan["id"], "name": plan["name"], "status": plan["status"], "source": plan["source"], "version": plan["version"]},
                "data_through": through.isoformat() if through else None, "forecast_generated": run and run["created_at"], "model_version": run and run["model_version"],
                "forecast_run_id": run and run["id"], "history_weeks": int(hist_weeks or 0), "lines": {k: int(v or 0) for k, v in lines.items()},
                "plan_first_week": first.isoformat() if first else None, "plan_weeks": len(next_weeks), "plan_weeks_list": [w.isoformat() for w in next_weeks],
                "n_recent": len(recent), "recent_rows": recent_rows, "variance_rows": variance_rows, "store_weeks": store_weeks, "trend_rows": trend_rows,
                "alloc": {"generated": bool(summary.get("recs")), "high_risk": int(summary.get("high_risk") or 0), "with_surplus": int(summary.get("with_surplus") or 0), "units_to_order": float(summary.get("units_to_order") or 0),
                          "transfers": int(summary.get("transfers") or 0), "transfers_avoiding_orders": avoided, "top_risks": [{**t, "shortage": float(t["shortage"] or 0), "on_hand": float(t["on_hand"] or 0), "incoming": float(t["incoming"] or 0), "coverage_days": t["coverage_days"]} for t in top_risks]},
                "focus_rows": [{**r, "f": float(r["f"])} for r in focus_rows]}

    # ------------------------------------------------------------------------------------------------ persistence of an analysis
    async def save_analysis(self, plan: dict[str, Any], actor: str, filters: dict[str, Any], ev: dict[str, Any], summary: str, summary_source: str, llm: dict[str, Any],
                            findings: list[dict[str, Any]], unavailable: list[dict[str, str]]) -> dict[str, Any]:
        aid = uuid.uuid4()
        await self._pool.execute(                                           # type: ignore[attr-defined]
            """INSERT INTO pln_analysis (id, plan_id, created_by, source, filters, data_through, forecast_run_id, model_version, plan_version, summary, summary_source, llm, findings, unavailable)
               VALUES ($1,$2,$3,$4,$5::jsonb,$6,$7,$8,$9,$10,$11,$12::jsonb,$13::jsonb,$14::jsonb)""",
            aid, uuid.UUID(plan["id"]), actor, plan["source"], dumps(filters), date.fromisoformat(ev["data_through"]) if ev["data_through"] else None,
            uuid.UUID(ev["forecast_run_id"]) if ev["forecast_run_id"] else None, ev["model_version"], plan["version"], summary, summary_source, dumps(llm), dumps(findings), dumps(unavailable))
        return await self.get_analysis(str(aid))                            # type: ignore[return-value]

    async def get_analysis(self, analysis_id: str) -> dict[str, Any] | None:
        try:
            r = rec(await self._pool.fetchrow("SELECT * FROM pln_analysis WHERE id = $1::uuid", analysis_id))   # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001  (a malformed id)
            return None
        if r:
            r["actions"] = recs(await self._pool.fetch("SELECT finding_id, action, actor_hash, at, detail FROM pln_analysis_action WHERE analysis_id = $1::uuid ORDER BY id", analysis_id))   # type: ignore[attr-defined]
        return r

    async def latest_analysis(self, plan_id: str) -> dict[str, Any] | None:
        row = await self._pool.fetchrow("SELECT id FROM pln_analysis WHERE plan_id = $1::uuid ORDER BY created_at DESC LIMIT 1", plan_id)   # type: ignore[attr-defined]
        return await self.get_analysis(str(row["id"])) if row else None

    async def record_analysis_action(self, analysis_id: str, finding_id: str, action: str, actor: str, detail: dict[str, Any]) -> None:
        await self._pool.execute("INSERT INTO pln_analysis_action (analysis_id, finding_id, action, actor_hash, detail) VALUES ($1::uuid,$2,$3,$4,$5::jsonb)",   # type: ignore[attr-defined]
                                 analysis_id, finding_id, action, actor, dumps(detail))

    # ------------------------------------------------------------------------------------------------ the review queue
    async def review_queue(self, statuses: Sequence[str], allowed: Sequence[str] | None, region: str | None, limit: int = 100) -> list[dict[str, Any]]:
        """Plans waiting for (or recently given) a decision, each with its totals and the regions and stores it covers."""
        rows = recs(await self._pool.fetch(                                  # type: ignore[attr-defined]
            f"""SELECT p.id, p.name, p.status, p.source, p.version, p.fy, p.first_week, p.last_week, p.scope, p.revision, p.created_by, p.submitted_by, p.submitted_at, p.decided_by, p.decided_at, p.note,
                       COUNT(pl.*) AS lines, COUNT(DISTINCT pl.location_id) AS stores, COUNT(DISTINCT pl.product_id) AS products,
                       COUNT(*) FILTER (WHERE pl.confidence = 'LOW') AS low_confidence_lines, COUNT(*) FILTER (WHERE pl.model_forecast IS NULL) AS no_forecast_lines,
                       SUM(pl.model_forecast) AS model_units, SUM(COALESCE(pl.manager_forecast, pl.model_forecast)) AS planned_units, SUM(pl.target_units) AS target_units,
                       COUNT(*) FILTER (WHERE pl.manager_forecast IS NOT NULL) AS adjusted_lines,
                       ARRAY_AGG(DISTINCT ({REGION_EXPR})) AS regions
                FROM pln_plan p JOIN pln_plan_line pl ON pl.plan_id = p.id JOIN pln_location l ON l.id = pl.location_id JOIN pln_location par ON par.id = l.parent_id
                WHERE p.status = ANY($1::text[]) AND ($2::text[] IS NULL OR pl.location_id = ANY($2::text[])) AND ($3::text IS NULL OR ({REGION_EXPR}) = $3)
                GROUP BY p.id ORDER BY (p.status = 'UNDER_REVIEW') DESC, p.submitted_at NULLS LAST, p.created_at DESC LIMIT $4""",
            list(statuses), None if allowed is None else list(allowed), region, limit))
        return rows

    async def plan_breakdown(self, plan_id: str, by: str, allowed: Sequence[str] | None) -> list[dict[str, Any]]:
        """Per region or store: the plan's lines, model and planned forecast, target, adjusted and low-confidence lines (so a reviewer can see where to look)."""
        key = {"region": f"({REGION_EXPR})", "store": "pl.location_id", "zone": "l.parent_id"}.get(by)
        if key is None:
            raise ValueError("by must be region, zone or store")
        return recs(await self._pool.fetch(                                  # type: ignore[attr-defined]
            f"""SELECT {key} AS gkey, MIN(l.name) AS label, COUNT(*) AS lines, COUNT(DISTINCT pl.product_id) AS products, SUM(pl.model_forecast) AS model_units,
                       SUM(COALESCE(pl.approved_forecast, pl.manager_forecast, pl.model_forecast)) AS planned_units, SUM(pl.target_units) AS target_units,
                       COUNT(*) FILTER (WHERE pl.manager_forecast IS NOT NULL) AS adjusted_lines, COUNT(*) FILTER (WHERE pl.confidence = 'LOW') AS low_confidence_lines,
                       COUNT(*) FILTER (WHERE pl.model_forecast IS NULL) AS no_forecast_lines
                FROM pln_plan_line pl JOIN pln_location l ON l.id = pl.location_id JOIN pln_location par ON par.id = l.parent_id
                WHERE pl.plan_id = $1::uuid AND ($2::text[] IS NULL OR pl.location_id = ANY($2::text[])) GROUP BY 1 ORDER BY 1""", plan_id, None if allowed is None else list(allowed)))
