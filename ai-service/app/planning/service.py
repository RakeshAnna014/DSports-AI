"""Sales & Inventory Planning: the operations the API and the assistant call. Permission and scope checks live here (the frontend only mirrors them); every consequential change is audited.
Calculations are in the pure modules; SQL is in the repositories. Nothing here calls an LLM, and nothing orders, transfers, publishes or prices anything in DSports."""
from __future__ import annotations

import logging
import math
import re
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from typing import Any, Sequence

from app.config.settings import Settings
from app.inventory.planning import Reference
from app.inventory.scenarios import Elasticity
from app.planning import forecast_run, pilot as pilot_mod, regions, scenarios as scen, variance, workflow
from app.planning import inventory as inv
from app.planning.calendar import Calendar
from app.planning.dbutil import DEMO_SRC, IMPORTED, LIVE, SCOPE_SOURCES
from app.planning.ingest import IngestError, PlanningIngest, SalesFeedClient
from app.planning.repository import PlanningRepository
from app.planning.repo_plans import _Conflict
from app.planning.service_insights import InsightsOps
from app.planning.workflow import PlanError

log = logging.getLogger(__name__)
MAX_EDITS = 500
MAX_PAGE = 200
MAX_IMPORT_ROWS = 5000
MAX_SCENARIO_PRODUCTS = 150


class PlanningError(Exception):
    def __init__(self, status: int, code: str, message: str, detail: Any = None):
        super().__init__(message)
        self.status, self.code, self.message, self.detail = status, code, message, detail


@dataclass
class Actor:
    hash: str
    roles: list[str] = field(default_factory=list)
    token: str | None = field(default=None, repr=False)


def _num(v: Any, name: str, *, lo: float = 0.0, hi: float = 1_000_000.0) -> float:
    try:
        x = float(v)
    except (TypeError, ValueError):
        raise PlanningError(422, "INVALID_NUMBER", f"{name} must be a number") from None
    if not math.isfinite(x) or x < lo or x > hi:
        raise PlanningError(422, "INVALID_NUMBER", f"{name} must be between {lo:g} and {hi:g}")
    return x


def _date(v: Any, name: str) -> date:
    try:
        return v if isinstance(v, date) and not isinstance(v, datetime) else date.fromisoformat(str(v)[:10])
    except ValueError:
        raise PlanningError(422, "INVALID_DATE", f"{name} must be a date (YYYY-MM-DD)") from None


class PlanningService(InsightsOps):
    def __init__(self, repo: PlanningRepository, settings: Settings, dsports: Any, hash_user):
        self.repo, self.settings, self.dsports, self.hash_user = repo, settings, dsports, hash_user
        self.llm = None                                   # set by the service wiring: only the analysis summary may use it, never a calculation
        self.cal = Calendar(settings.planning_timezone, settings.planning_week_start, settings.planning_fy_start_month)
        self.state_map = regions.parse_map(settings.planning_state_region_map)
        self.cfg = {"transfer_lead_days": settings.planning_transfer_lead_days, "cross_region_days": settings.planning_transfer_cross_region_days,
                    "transfer_min_qty": settings.planning_transfer_min_qty, "transfer_unit_cost": settings.planning_transfer_unit_cost}

    # ------------------------------------------------------------------------------------------------ permissions and scope
    def require_planner(self, a: Actor) -> None:
        if not workflow.is_planner(a.roles):
            raise PlanningError(403, "FORBIDDEN", "Sales & Inventory Planning is restricted to planners, managers and administrators")

    def require_admin(self, a: Actor) -> None:
        if not workflow.has_any(a.roles, workflow.ADMIN_ROLES):
            raise PlanningError(403, "FORBIDDEN", "Only an administrator can do this")

    def require_all_scope(self, a: Actor) -> None:
        if not workflow.sees_everything(a.roles):
            raise PlanningError(403, "FORBIDDEN", "This needs a planner, category manager or administrator (it spans every store)")

    async def allowed(self, a: Actor) -> list[str] | None:
        """None = every location; otherwise the store ids the actor may see and edit (possibly none)."""
        return None if workflow.sees_everything(a.roles) else await self.repo.scope_stores(a.hash)

    async def _plan(self, plan_id: str) -> dict[str, Any]:
        try:
            plan = await self.repo.get_plan(plan_id)
        except Exception:  # noqa: BLE001  (a malformed id)
            plan = None
        if plan is None:
            raise PlanningError(404, "NOT_FOUND", "Plan not found")
        return plan

    async def _through(self, source: str) -> date | None:
        best = None
        for s in SCOPE_SOURCES[source]:
            d = await self.repo.data_through(s)
            best = d if best is None or (d is not None and d > best) else best
        return best

    @staticmethod
    def _filters(f: dict[str, Any] | None) -> dict[str, Any]:
        keys = ("sport", "category", "region", "zone", "stores", "product_id", "q", "week_from", "week_to", "has_target", "has_adjustment", "confidence")
        return {k: v for k, v in (f or {}).items() if k in keys and v not in (None, "", [])}

    # ------------------------------------------------------------------------------------------------ data: ingest, demo, readiness
    async def ingest(self, a: Actor, mode: str = "INCREMENTAL") -> dict[str, Any]:
        self.require_admin(a)
        if not a.token:
            raise PlanningError(401, "NO_TOKEN", "The administrator's token is needed to read the DSports sales feed")
        feed = SalesFeedClient(self.dsports)
        ing = PlanningIngest(self.repo, feed, self.cal, self.state_map, restate_days=45)
        try:
            res = await ing.run(a.hash, a.token, mode=mode)
        except IngestError as exc:
            raise PlanningError(409 if exc.code == "INGEST_RUNNING" else 422 if exc.code == "INVALID_MODE" else 502, exc.code, exc.message) from None
        await self.repo.audit(a.hash, a.roles, "INGEST", res.run_id, f"INGEST_{res.status}", after=res.__dict__)
        return {**res.__dict__, "reconciliation": await self.repo.reconcile_live()}

    async def seed_demo(self, a: Actor) -> dict[str, Any]:
        self.require_admin(a)
        if not self.settings.planning_enable_demo:
            raise PlanningError(403, "DEMO_DISABLED", "The demonstration data is disabled (PLANNING_ENABLE_DEMO)")
        try:
            out = await self.repo.seed_demo(self.cal)
        except ValueError as exc:
            raise PlanningError(409, "NO_INVENTORY_HISTORY", str(exc)) from None
        await self.repo.audit(a.hash, a.roles, "DEMO", None, "DEMO_SEEDED", after=out)
        return out

    async def readiness(self) -> dict[str, Any]:
        c = await self.repo.readiness_counts()
        min_w = self.settings.planning_min_history_weeks
        live, demo_, imp = c[LIVE], c[DEMO_SRC], c[IMPORTED]
        lines = c.get("live_lines") or {}

        def level(weeks: int, real: bool) -> str:
            if not real:
                return "DEMONSTRATION_ONLY"
            return "RELIABLE" if weeks >= 104 else "LIMITED_ESTIMATE" if weeks >= min_w else "INSUFFICIENT"
        caps = [
            {"key": "online_sales_actuals", "label": "Online sales actuals (real DSports orders)", "status": level(live["weeks"], True) if live["rows"] else "UNAVAILABLE",
             "evidence": f"{live['weeks']} week(s), {lines.get('orders', 0)} order(s) on {lines.get('order_days', 0)} distinct day(s), {live['products']} product(s); first week {live['first_week']}, data through {live['data_through']}",
             "note": f"Weekly forecasting needs at least {min_w} closed weeks; reliable annual seasonality needs about two years. Real orders are web orders from one warehouse, not stores."},
            {"key": "store_level_history", "label": "Store-level sales history", "status": level(imp["weeks"], True) if imp["rows"] and imp["weeks"] >= min_w else "DEMONSTRATION_ONLY" if demo_["rows"] else "UNAVAILABLE",
             "evidence": f"synthetic {demo_['weeks']} week(s) over {demo_['locations']} store(s); imported {imp['weeks']} week(s)",
             "note": "DSports has no physical stores. The only store-level history is a labelled synthetic split of the Inventory AI's synthetic regional history, plus whatever is imported."},
            {"key": "store_hierarchy", "label": "Region, zone and store hierarchy", "status": "DEMONSTRATION_ONLY" if (c["stores"] or {}).get("demo_stores") else "UNAVAILABLE",
             "evidence": f"{(c['stores'] or {}).get('regions', 0)} region(s), {(c['stores'] or {}).get('stores', 0)} store(s) ({(c['stores'] or {}).get('demo_stores', 0)} demonstration), {(c['stores'] or {}).get('online', 0)} online location(s)",
             "note": "No real store master exists. Replace the demonstration network by loading real stores (pln_location) and importing their weekly actuals."},
            {"key": "stock_by_store", "label": "Stock and incoming supply by store", "status": "DEMONSTRATION_ONLY" if (c["stock"] or {}).get("positions") else "UNAVAILABLE",
             "evidence": f"{(c['stock'] or {}).get('positions', 0)} position(s) as of {(c['stock'] or {}).get('as_of')}", "note": "DSports tracks sellable stock per warehouse, not per store: store stock is the Inventory AI's regional position split across the demonstration stores."},
            {"key": "returns_cancellations", "label": "Returns and cancellations", "status": "RELIABLE" if live["rows"] else "UNAVAILABLE", "evidence": f"{lines.get('excluded', 0)} line(s) excluded as not a sale",
             "note": "Taken from the order feed (cancelled orders, refunds, approved returns). The synthetic history has no returns."},
            {"key": "stockout_history", "label": "Stock-out history", "status": "DEMONSTRATION_ONLY" if demo_["with_stockout_info"] else "UNAVAILABLE", "evidence": f"{demo_['with_stockout_info']} store-week(s) with stock-out days (synthetic)",
             "note": "Real stock-out periods are not recorded in DSports, so a real zero-sales week cannot be told from an out-of-stock week."},
            {"key": "promotion_history", "label": "Promotion and discount history", "status": "DEMONSTRATION_ONLY" if c.get("promotions_in_history") else "UNAVAILABLE",
             "evidence": f"{c.get('promotions_in_history', 0)} synthetic discounted day(s)", "note": "The commerce promotions are not exposed to the AI service, so real promotion effects cannot be measured; the forecast does not model promotions."},
            {"key": "supplier_reference", "label": "Supplier lead time, MOQ, case pack and cost", "status": "DEMONSTRATION_ONLY" if (c["catalogue"] or {}).get("skus") else "UNAVAILABLE",
             "evidence": f"{(c['catalogue'] or {}).get('with_lead_time', 0)} of {(c['catalogue'] or {}).get('skus', 0)} SKU(s) have a lead time; {(c['catalogue'] or {}).get('with_cost', 0)} have a cost",
             "note": "These are the Inventory AI's assumed POC reference values, not supplier contracts."},
        ]
        runs = {s: await self.repo.current_run(s) for s in (DEMO_SRC, LIVE)}
        forecasting = {
            DEMO_SRC: {"status": "DEMONSTRATION_ONLY", "reason": "Built on synthetic store-level history. It demonstrates the method; it is NOT a validated business forecast.", "run": runs[DEMO_SRC] and runs[DEMO_SRC]["id"]},
            LIVE: {"status": level(live["weeks"], True) if live["rows"] else "UNAVAILABLE",
                   "reason": (f"{live['weeks']} closed week(s) of online sales (need {min_w} for a series to use its own pattern, about 104 for seasonality): forecasts are low-confidence fallbacks." if live["weeks"] < min_w
                              else "Online sales only (no stores)."), "run": runs[LIVE] and runs[LIVE]["id"]},
        }
        return {"capabilities": caps, "forecasting": forecasting, "live": {**live, "lines": lines}, "synthetic": demo_, "imported": imp, "last_ingest": c["last_ingest"], "reconciliation": await self.repo.reconcile_live(),
                "thresholds": {"min_history_weeks": min_w, "min_eval_observations": self.settings.planning_min_eval_observations, "min_eval_units": self.settings.planning_min_eval_units},
                "conventions": {"week_start": ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"][self.settings.planning_week_start], "timezone": self.settings.planning_timezone, "fy_start_month": self.settings.planning_fy_start_month,
                                "net_units": "gross - cancelled - returned (a return or refund is subtracted once; PENDING_PAYMENT and CREATED are not sales)",
                                "restatement": "a later cancellation or approved return restates the ORIGINAL sale week"}}

    async def locations(self, a: Actor) -> dict[str, Any]:
        self.require_planner(a)
        allowed = await self.allowed(a)
        items = await self.repo.list_locations()
        if allowed is not None:
            ok = set(allowed)
            keep = {i["id"] for i in items if i["id"] in ok}
            parents = {i["parent_id"] for i in items if i["id"] in ok}
            grand = {i["parent_id"] for i in items if i["id"] in parents}
            keep |= {p for p in parents | grand if p}
            items = [i for i in items if i["id"] in keep]
        return {"items": items, "scope": "ALL" if allowed is None else "GRANTED"}

    async def grant_scope(self, a: Actor, user_id: str, location_id: str, revoke: bool = False) -> dict[str, Any]:
        self.require_admin(a)
        if await self.repo.location(location_id) is None:
            raise PlanningError(404, "NOT_FOUND", "Location not found")
        h = self.hash_user(user_id)
        if revoke:
            n = await self.repo.revoke_scope(h, location_id)
        else:
            await self.repo.grant_scope(h, location_id, a.hash)
            n = 1
        await self.repo.audit(a.hash, a.roles, "SCOPE", h, "SCOPE_REVOKED" if revoke else "SCOPE_GRANTED", after={"location": location_id})
        return {"actor": h, "location_id": location_id, "revoked": revoke, "changed": n}

    # ------------------------------------------------------------------------------------------------ forecasts
    async def run_forecast(self, a: Actor, scope: str) -> dict[str, Any]:
        self.require_all_scope(a)
        try:
            out = await forecast_run.execute(self.repo, self.cal, self.settings, scope, a.hash)
        except forecast_run.ForecastError as exc:
            raise PlanningError(exc.status, exc.code, exc.message) from None
        await self.repo.audit(a.hash, a.roles, "FORECAST", out["run_id"], "FORECAST_RUN", after={k: out[k] for k in ("scope", "series", "ok", "fallback", "insufficient", "as_of_week")})
        return out

    async def forecast_overview(self, a: Actor, source: str) -> dict[str, Any]:
        self.require_planner(a)
        run = await self.repo.current_run(source)
        if run is None:
            return {"run": None, "message": "No forecast has been generated for this data scope yet."}
        return {"run": run, "counts": await self.repo.missing_forecast_counts(run["id"]), "runs": await self.repo.list_runs(source, 5)}

    async def forecast_series(self, a: Actor, run_id: str | None, source: str, f: dict[str, Any], page: int, size: int) -> dict[str, Any]:
        self.require_planner(a)
        run = await self.repo.get_run(run_id) if run_id else await self.repo.current_run(source)
        if run is None:
            raise PlanningError(404, "NO_FORECAST", "No forecast run exists for this data scope")
        out = await self.repo.forecast_page(run["id"], self._filters(f) | {k: f[k] for k in ("status",) if f.get(k)}, await self.allowed(a), page, min(size, MAX_PAGE))
        return {**out, "run": {k: run[k] for k in ("id", "source", "as_of_week", "first_week", "horizon_weeks", "model_version", "created_at")}}

    async def evaluation(self, a: Actor, run_id: str | None, source: str, scope: str | None, horizon: str | None) -> dict[str, Any]:
        """Accuracy of the chosen method on the held-out origin, compared with the MA4 baseline. A figure on too few observations is withheld (its value is None, `sufficient` is False)."""
        self.require_planner(a)
        run = await self.repo.get_run(run_id) if run_id else await self.repo.current_run(source)
        if run is None:
            raise PlanningError(404, "NO_FORECAST", "No forecast run exists for this data scope")
        rows = await self.repo.eval_rows(run["id"], scope, horizon)
        for r in rows:
            if not r["sufficient"]:
                r["wape"] = r["bias"] = r["mae"] = r["baseline_wape"] = None
                r["note"] = f"Only {r['n_obs']} observation(s) / {r['actual_units']:.0f} unit(s): too few to quote an accuracy."
            else:
                r["note"] = None
            r["better_than_baseline"] = None if r["wape"] is None or r["baseline_wape"] is None else r["wape"] < r["baseline_wape"]
        sel = run["selection"] or {}
        return {"run": {k: run[k] for k in ("id", "source", "as_of_week", "first_week", "horizon_weeks", "model_version", "n_series", "n_ok", "n_fallback", "n_insufficient", "created_at")},
                "features": run["features"], "method_choice": sel.get("table"), "chosen": sel.get("chosen"), "selection_origins": sel.get("selection_origins"), "interval": sel.get("interval"),
                "heldout_by_bucket": sel.get("heldout_by_bucket"), "baseline": sel.get("baseline"), "rows": rows,
                "notice": ("Accuracy is measured on the last weeks of history the model did not see, against a plain 4-week average baseline. " +
                           ("This is SYNTHETIC history: the figures describe the method on generated data, not business accuracy." if run["source"] == DEMO_SRC else
                            "This is real online history; with few weeks the evaluation is withheld."))}

    # ------------------------------------------------------------------------------------------------ plans
    async def create_plan(self, a: Actor, *, name: str, source: str, fy: int | None, period: str, number: int | None, scope: dict[str, Any], run_id: str | None) -> dict[str, Any]:
        self.require_planner(a)
        if not name or len(name.strip()) < 3:
            raise PlanningError(422, "INVALID_NAME", "Give the plan a name of at least 3 characters")
        run = await self.repo.get_run(run_id) if run_id else await self.repo.current_run(source)
        if run is None or run["status"] != "SUCCEEDED":
            raise PlanningError(409, "NO_FORECAST", f"There is no forecast for {source}: generate one first")
        horizon = [date.fromisoformat(run["first_week"]) + timedelta(weeks=i) for i in range(run["horizon_weeks"])]
        fy_ = fy or self.cal.fy_year(horizon[0])
        try:
            wanted = set(self.cal.period_weeks(period, fy_, number)) if period != "HORIZON" else set(horizon)
        except ValueError as exc:
            raise PlanningError(422, "INVALID_PERIOD", str(exc)) from None
        weeks = sorted(set(horizon) & wanted)
        if not weeks:
            raise PlanningError(422, "NO_WEEKS", f"The forecast covers {horizon[0]} to {horizon[-1]}: none of those weeks is in the chosen period")
        allowed = await self.allowed(a)
        if allowed is not None:
            scope = {**scope, "stores": [s for s in (scope.get("stores") or allowed) if s in allowed]}
            if not scope["stores"]:
                raise PlanningError(403, "OUT_OF_SCOPE", "You have no stores in scope")
        try:
            plan = await self.repo.create_plan(name=name.strip(), fy=fy_, weeks=weeks, source=run["source"], run_id=run["id"], scope=scope, actor=a.hash)
        except ValueError as exc:
            raise PlanningError(422, "EMPTY_PLAN", str(exc)) from None
        return plan

    async def plan_view(self, a: Actor, plan_id: str) -> dict[str, Any]:
        self.require_planner(a)
        plan = await self._plan(plan_id)
        stores = await self.repo.plan_location_ids(plan_id)
        allowed = await self.allowed(a)
        if allowed is not None and not (set(stores) & set(allowed)):
            raise PlanningError(403, "OUT_OF_SCOPE", "This plan has no store in your scope")
        counts = await self.repo.pool.fetchrow(
            """SELECT COUNT(*) AS lines, COUNT(*) FILTER (WHERE manager_forecast IS NOT NULL) AS adjusted, COUNT(*) FILTER (WHERE target_units IS NOT NULL) AS with_target,
                      COUNT(*) FILTER (WHERE model_forecast IS NULL) AS no_forecast, COUNT(*) FILTER (WHERE confidence = 'LOW') AS low_confidence, COUNT(DISTINCT location_id) AS stores,
                      COUNT(DISTINCT product_id) AS products, SUM(model_forecast) AS model_units, SUM(COALESCE(manager_forecast, model_forecast)) AS planned_units, SUM(target_units) AS target_units,
                      SUM(approved_forecast) AS approved_units FROM pln_plan_line WHERE plan_id = $1::uuid""", plan_id)
        view = dict(plan)
        view["counts"] = {k: (float(v) if v is not None and not isinstance(v, int) else v) for k, v in dict(counts).items()}
        view["allowed_next"] = workflow.allowed_next(plan["status"], a.roles, actor=a.hash, submitted_by=plan.get("submitted_by"), allow_self_approval=self.settings.planning_allow_self_approval)
        view["editable"] = plan["status"] in workflow.EDITABLE and workflow.is_planner(a.roles)
        view["fy_label"] = self.cal.fy_label(plan["fy"])
        view["weeks"] = [self.cal.describe_week(w) for w in await self.repo.plan_weeks(plan_id)]
        view["data_scope"] = {"source": plan["source"], "synthetic": plan["source"] == DEMO_SRC,
                              "label": "DEMONSTRATION data: synthetic stores and sales. Not validated business predictions." if plan["source"] == DEMO_SRC else "Live online orders (no stores)."}
        return view

    async def list_plans(self, a: Actor, status: str | None, fy: int | None) -> dict[str, Any]:
        self.require_planner(a)
        items = await self.repo.list_plans(status, fy)
        allowed = await self.allowed(a)
        if allowed is not None:
            ok = set(allowed)
            keep = []
            for p in items:
                if set(await self.repo.plan_location_ids(p["id"])) & ok:
                    keep.append(p)
            items = keep
        for p in items:
            p["fy_label"] = self.cal.fy_label(p["fy"])
        return {"items": items}

    async def plan_lines(self, a: Actor, plan_id: str, f: dict[str, Any], sort: str, page: int, size: int) -> dict[str, Any]:
        self.require_planner(a)
        plan = await self._plan(plan_id)
        allowed = await self.allowed(a)
        size = max(1, min(size, MAX_PAGE))
        out = await self.repo.workspace(plan, self._filters(f), sort, max(0, page), size, allowed, await self._through(plan["source"]))
        out["plan"] = {"id": plan["id"], "status": plan["status"], "version": plan["version"], "source": plan["source"], "editable": plan["status"] in workflow.EDITABLE}
        return out

    def _validate_edits(self, edits: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
        if not edits:
            raise PlanningError(422, "NO_EDITS", "No edits were sent")
        if len(edits) > MAX_EDITS:
            raise PlanningError(422, "TOO_MANY_EDITS", f"At most {MAX_EDITS} lines can be edited in one request")
        seen, out = set(), []
        for i, e in enumerate(edits):
            for req in ("product_id", "location_id", "week_start", "expected_line_version"):
                if e.get(req) in (None, ""):
                    raise PlanningError(422, "INVALID_EDIT", f"Edit {i + 1}: {req} is required")
            try:
                uuid.UUID(str(e["product_id"]))
            except ValueError:
                raise PlanningError(422, "INVALID_EDIT", f"Edit {i + 1}: product_id is not an id") from None
            week = _date(e["week_start"], "week_start")
            if not self.cal.is_week_start(week):
                raise PlanningError(422, "INVALID_WEEK", f"Edit {i + 1}: {week} is not the first day of a planning week")
            key = (str(e["product_id"]), e["location_id"], week)
            if key in seen:
                raise PlanningError(422, "DUPLICATE_EDIT", f"Edit {i + 1}: the same product, store and week appears twice")
            seen.add(key)
            clean: dict[str, Any] = {"product_id": str(e["product_id"]), "location_id": e["location_id"], "week_start": week, "expected_line_version": int(e["expected_line_version"])}
            if "target_units" in e:
                clean["target_units"] = None if e["target_units"] is None else _num(e["target_units"], "target_units")
            if "manager_forecast" in e:
                if e["manager_forecast"] is None:
                    clean["manager_forecast"] = None
                else:
                    clean["manager_forecast"] = _num(e["manager_forecast"], "manager_forecast")
                    if e.get("reason_code") not in workflow.REASON_CODES:
                        raise PlanningError(422, "REASON_REQUIRED", f"Edit {i + 1}: a forecast adjustment needs a reason code ({', '.join(workflow.REASON_CODES)})")
                    if e["reason_code"] == "OTHER" and len((e.get("note") or "").strip()) < 5:
                        raise PlanningError(422, "NOTE_REQUIRED", f"Edit {i + 1}: reason OTHER needs an explanation")
                    clean["reason_code"], clean["note"] = e["reason_code"], (e.get("note") or "").strip() or None
            if len(clean) == 4:
                raise PlanningError(422, "INVALID_EDIT", f"Edit {i + 1}: nothing to change")
            out.append(clean)
        return out

    async def edit_lines(self, a: Actor, plan_id: str, edits: Sequence[dict[str, Any]]) -> dict[str, Any]:
        self.require_planner(a)
        plan = await self._plan(plan_id)
        try:
            workflow.can_edit(plan["status"], a.roles)
        except PlanError as exc:
            raise PlanningError(exc.status, exc.code, exc.message) from None
        clean = self._validate_edits(edits)
        allowed = await self.allowed(a)
        try:
            out = await self.repo.edit_lines(plan_id, clean, a.hash, a.roles, allowed)
        except _Conflict as c:
            raise PlanningError(409, "EDIT_CONFLICT", "Nothing was saved: some lines changed or are not yours to edit", {"conflicts": c.conflicts}) from None
        except PermissionError as exc:
            raise PlanningError(409, "PLAN_FROZEN", f"The plan is {str(exc).split(':')[-1]}: create a revision to change it") from None
        except LookupError:
            raise PlanningError(404, "NOT_FOUND", "Plan not found") from None
        out["plan"] = await self.repo.get_plan(plan_id)
        return out

    async def growth_targets(self, a: Actor, plan_id: str, growth_pct: float, basis: str, f: dict[str, Any], overwrite: bool) -> dict[str, Any]:
        self.require_planner(a)
        plan = await self._plan(plan_id)
        try:
            workflow.can_edit(plan["status"], a.roles)
        except PlanError as exc:
            raise PlanningError(exc.status, exc.code, exc.message) from None
        g = _num(growth_pct, "growth_pct", lo=-90, hi=500)
        if basis not in ("MODEL", "MANAGER"):
            raise PlanningError(422, "INVALID_BASIS", "basis must be MODEL or MANAGER")
        try:
            out = await self.repo.bulk_growth_targets(plan_id, g, basis, self._filters(f), a.hash, a.roles, await self.allowed(a), overwrite)
        except PermissionError as exc:
            raise PlanningError(409, "PLAN_FROZEN", f"The plan is {str(exc).split(':')[-1]}") from None
        out["plan"] = await self.repo.get_plan(plan_id)
        return out

    async def transition(self, a: Actor, plan_id: str, target: str, reason: str | None, expected_version: int) -> dict[str, Any]:
        self.require_planner(a)
        plan = await self._plan(plan_id)
        try:
            workflow.check_transition(plan["status"], target, a.roles, actor=a.hash, submitted_by=plan.get("submitted_by"), reason=reason, allow_self_approval=self.settings.planning_allow_self_approval)
        except PlanError as exc:
            await self.repo.audit(a.hash, a.roles, "ACCESS", plan_id, "DENIED", after={"target": target, "code": exc.code})
            raise PlanningError(exc.status, exc.code, exc.message) from None
        allowed = await self.allowed(a)
        if allowed is not None:
            outside = set(await self.repo.plan_location_ids(plan_id)) - set(allowed)
            if outside:
                raise PlanningError(403, "OUT_OF_SCOPE", f"The plan includes {len(outside)} store(s) outside your scope, so you cannot {target.lower().replace('_', ' ')} it")
        if target == "SUBMITTED":
            n = await self.repo.pool.fetchval("SELECT COUNT(*) FROM pln_plan_line WHERE plan_id = $1::uuid AND (manager_forecast IS NOT NULL OR model_forecast IS NOT NULL OR target_units IS NOT NULL)", plan_id)
            if not n:
                raise PlanningError(422, "EMPTY_PLAN", "The plan has no forecast or target to submit")
        try:
            out = await self.repo.set_status(plan_id, target, a.hash, a.roles, int(expected_version), reason, snapshot_approved=target == "APPROVED", supersede_parent=target == "APPROVED")
        except _Conflict as c:
            raise PlanningError(409, "VERSION_CONFLICT", "The plan changed since you opened it: reload and try again", {"conflicts": c.conflicts}) from None
        return await self.plan_view(a, out["id"])

    async def revise(self, a: Actor, plan_id: str, name: str | None) -> dict[str, Any]:
        self.require_planner(a)
        await self._plan(plan_id)
        try:
            new = await self.repo.revise(plan_id, a.hash, a.roles, name)
        except PermissionError as exc:
            kind = str(exc).split(":")[0]
            raise PlanningError(409, kind, "Only an approved or published plan can be revised" if kind == "NOT_REVISABLE" else "A revision of this plan is already open") from None
        return await self.plan_view(a, new["id"])

    async def impact(self, a: Actor, plan_id: str, product_id: str, location_id: str) -> dict[str, Any]:
        """What a manager's forecast changes downstream for ONE product and store: the stock assessment with the model forecast against the plan's forecast."""
        self.require_planner(a)
        plan = await self._plan(plan_id)
        allowed = await self.allowed(a)
        if allowed is not None and location_id not in allowed:
            raise PlanningError(403, "OUT_OF_SCOPE", "This store is outside your scope")
        data = await self.repo.product_store_inputs(plan, {"stores": [location_id]}, None, [product_id])
        rows = [r for r in data["lines"] if str(r["product_id"]) == product_id]
        if not rows:
            raise PlanningError(404, "NOT_FOUND", "Nothing planned for this product and store")
        stock = data["stock"].get((rows[0]["product_id"], location_id))
        sku = data["skus"].get(rows[0]["product_id"])
        if stock is None or sku is None:
            return {"available": False, "reason": "No store stock position is recorded for this product and store, so the downstream stock effect cannot be calculated."}
        as_of = stock["as_of"]
        weeks = [r["week_start"] for r in rows]
        ref = Reference(sku["lead_time_days"], sku["moq"], sku["case_pack"], float(sku["cost"]) if sku["cost"] is not None else None, float(sku["price"]))
        arrivals = tuple(((e - as_of).days if (e - as_of).days > 0 else 0, q) for e, q in data["incoming"].get((rows[0]["product_id"], location_id), []))

        def assess(values: list[float | None]):
            if any(v is None for v in values):
                return None
            s = inv.StoreInput(location_id, "", stock["on_hand"], stock["reserved"], arrivals, [float(v) for v in values])
            return inv.assess_store(as_of, weeks, s, ref)
        before = assess([r["model"] for r in rows])
        after = assess([r["used"] for r in rows])
        if before is None or after is None:
            return {"available": False, "reason": "There is no model forecast for this product and store, so there is nothing to compare."}

        def view(x):
            return {"risk_level": x.risk_level, "order_qty": x.reorder_qty, "coverage_days": x.coverage_days, "stockout_date": x.stockout_date.isoformat() if x.stockout_date else None, "safety_stock": x.safety_stock,
                    "forecast_30": x.forecast_30, "status": x.status}
        return {"available": True, "as_of": as_of.isoformat(), "model": view(before), "plan": view(after), "stock": {"sellable": stock["on_hand"], "reserved": stock["reserved"], "incoming": sum(q for _, q in arrivals)},
                "note": "Store-level assessment with the Inventory AI's rules; the purchase quantity here is for this store alone (MOQ is applied across stores in the allocation)."}

    # ------------------------------------------------------------------------------------------------ performance
    def _week_keys(self, weeks: Sequence[date], grain: str) -> dict[date, str]:
        out = {}
        for w in weeks:
            d = self.cal.describe_week(w)
            out[w] = {"month": d["month"], "quarter": f"{self.cal.fy_label(d['fy'])} Q{d['fy_quarter']}", "fy": self.cal.fy_label(d["fy"])}.get(grain, w.isoformat())
        return out

    async def performance(self, a: Actor, plan_id: str, grain: str, f: dict[str, Any], basis: str) -> dict[str, Any]:
        self.require_planner(a)
        plan = await self._plan(plan_id)
        through = await self._through(plan["source"])
        allowed = await self.allowed(a)
        flt = self._filters(f)
        weeks = await self.repo.plan_weeks(plan_id)
        use_model = basis == "MODEL"
        try:
            rows = await self.repo.report(plan, grain, flt, allowed, self._week_keys(weeks, grain), through, use_model)
            total = (await self.repo.report(plan, "total", flt, allowed, {}, through, use_model))[:1]
        except ValueError as exc:
            raise PlanningError(422, "INVALID_GRAIN", str(exc)) from None
        approved = plan["status"] in ("APPROVED", "PUBLISHED")
        return {"plan": {"id": plan["id"], "name": plan["name"], "status": plan["status"], "source": plan["source"]}, "grain": grain, "rows": rows, "total": total[0] if total else None,
                "basis": "MODEL" if use_model else ("APPROVED" if approved else "MANAGER_OR_MODEL"), "data_through": through.isoformat() if through else None,
                "formulas": {"forecast_variance": "actual - forecast", "target_variance": "actual - target", "target_attainment_pct": "actual / target x 100 (when the target is positive)"},
                "notice": ("Forecast variance is against the APPROVED forecast." if approved and not use_model else
                           "The plan is not approved: variance is against the manager's forecast where there is one, otherwise the model's." if not use_model else "Variance is against the original MODEL forecast.") +
                          " Open weeks have no actual and are not counted; variances use only the rows that have both values."}

    async def investigate(self, a: Actor, plan_id: str, product_id: str, location_id: str, week_start: date) -> dict[str, Any]:
        self.require_planner(a)
        plan = await self._plan(plan_id)
        allowed = await self.allowed(a)
        if allowed is not None and location_id not in allowed:
            raise PlanningError(403, "OUT_OF_SCOPE", "This store is outside your scope")
        through = await self._through(plan["source"])
        page = await self.repo.workspace(plan, {"product_id": product_id, "stores": [location_id], "week_from": week_start, "week_to": week_start}, "week", 0, 1, None, through)
        if not page["items"]:
            raise PlanningError(404, "NOT_FOUND", "That product, store and week is not in the plan")
        line = page["items"][0]
        wk = await self.repo.pool.fetchrow("SELECT gross_units, cancelled_units, returned_units, net_units, stockout_days FROM pln_sales_week WHERE product_id = $1::uuid AND location_id = $2 AND week_start = $3 AND data_source = ANY($4::text[]) "
                                           "ORDER BY (data_source = 'IMPORTED') DESC LIMIT 1", product_id, location_id, week_start, list(SCOPE_SOURCES[plan["source"]]))
        series = await self.repo.pool.fetchrow("SELECT history_weeks, confidence FROM pln_forecast_series WHERE run_id = $1 AND product_id = $2::uuid AND location_id = $3", plan["forecast_run_id"], product_id, location_id)
        recent = await self.repo.workspace(plan, {"product_id": product_id, "stores": [location_id], "week_to": week_start - timedelta(days=1)}, "week", 0, 8, None, through)
        pairs = [(r["model_forecast"], r["actual_units"]) for r in recent["items"] if r["week_closed"] and r["actual_units"] is not None and r["model_forecast"] is not None]
        bias = None
        if len(pairs) >= 3 and sum(p[1] for p in pairs) > 0:
            bias = (sum(p[0] for p in pairs) - sum(p[1] for p in pairs)) / sum(p[1] for p in pairs) * 100
        sport = (await self.repo.pool.fetchval("SELECT sport FROM inv_sku WHERE product_id = $1::uuid", product_id)) or ""
        events = [f"{e['name']} ({e['sport']})" for e in await self.repo.pool.fetch("SELECT name, sport FROM inv_event WHERE lower(sport) = lower($1) AND start_date <= $3 AND end_date >= $2", sport, week_start, week_start + timedelta(days=6))]
        adjs = [x for x in await self.repo.adjustments_for([product_id], week_start, week_start) if x["location_id"] == location_id]
        res = variance.investigate(actual=line["actual_units"], forecast=line["approved_forecast"] if line["approved_forecast"] is not None else line["manager_forecast"] if line["manager_forecast"] is not None else line["model_forecast"],
                                   target=line["target_units"], week_closed=line["week_closed"], stockout_days=None if wk is None else wk["stockout_days"], returned_units=None if wk is None else wk["returned_units"],
                                   cancelled_units=None if wk is None else wk["cancelled_units"], gross_units=None if wk is None else wk["gross_units"], history_weeks=None if series is None else series["history_weeks"],
                                   confidence=None if series is None else series["confidence"], recent_bias_pct=bias, adjustments=adjs, events=events, pct_threshold=self.settings.planning_variance_pct,
                                   min_units=self.settings.planning_variance_min_units)
        return {**res, "line": line, "thresholds": {"percent": self.settings.planning_variance_pct, "units": self.settings.planning_variance_min_units}}

    # ------------------------------------------------------------------------------------------------ actuals, imports, corrections
    async def actuals(self, a: Actor, source: str, week_from: date, week_to: date, dim: str, f: dict[str, Any]) -> dict[str, Any]:
        self.require_planner(a)
        if source not in SCOPE_SOURCES:
            raise PlanningError(422, "INVALID_SCOPE", "source must be POC_SYNTHETIC or DSPORTS_LIVE")
        through = await self._through(source)
        try:
            rows = await self.repo.weekly_actuals(source, (self.cal.week_start(week_from), self.cal.week_start(week_to)), dim, self._filters(f), await self.allowed(a), through)
        except ValueError as exc:
            raise PlanningError(422, "INVALID_DIM", str(exc)) from None
        return {"rows": rows, "data_through": through.isoformat() if through else None, "source": source, "dim": dim,
                "notice": "A week is CLOSED only when its last day is on or before the data-through date. Gross, cancelled, returned and net units follow the documented sales convention."
                          + (" Live weeks with no lines are confirmed zeros and are not listed." if source == LIVE else "")}

    async def import_actuals(self, a: Actor, batch_id: str, rows: Sequence[dict[str, Any]], note: str | None, data_through: date | None) -> dict[str, Any]:
        self.require_planner(a)
        if not re.fullmatch(r"[A-Za-z0-9_.:-]{6,64}", batch_id or ""):
            raise PlanningError(422, "INVALID_BATCH", "batch_id must be 6 to 64 letters, digits or _.:-")
        if not rows:
            raise PlanningError(422, "NO_ROWS", "No rows were sent")
        if len(rows) > MAX_IMPORT_ROWS:
            raise PlanningError(422, "TOO_MANY_ROWS", f"At most {MAX_IMPORT_ROWS} rows per import")
        allowed = await self.allowed(a)
        sku_map = {r["sku"]: str(r["product_id"]) for r in await self.repo.pool.fetch("SELECT sku, product_id FROM inv_sku")}
        pids = set(sku_map.values())
        locs = {l["id"]: l for l in await self.repo.list_locations()}
        today = self.cal.today()
        clean, errors, seen = [], [], set()
        for i, r in enumerate(rows, start=1):
            problems = []
            pid = str(r.get("product_id") or sku_map.get(r.get("sku") or "", ""))
            if pid not in pids:
                problems.append("unknown product (give product_id or a catalogue sku)")
            loc = locs.get(r.get("location_id"))
            if loc is None or loc["level"] != "STORE" or loc["channel"] != "STORE":
                problems.append("location must be a STORE (the online feed cannot be imported over)")
            elif allowed is not None and loc["id"] not in allowed:
                problems.append("store is outside your scope")
            try:
                week = _date(r.get("week_start"), "week_start")
                if not self.cal.is_week_start(week):
                    problems.append("week_start is not the first day of a planning week")
                if self.cal.week_end(week) > today:
                    problems.append("the week has not ended: only closed weeks can be imported")
            except PlanningError as exc:
                problems.append(exc.message)
                week = None
            try:
                gross = int(r.get("gross_units", r.get("net_units", 0)))
                canc, ret = int(r.get("cancelled_units", 0)), int(r.get("returned_units", 0))
                net = int(r["net_units"]) if r.get("net_units") is not None else gross - canc - ret
                if min(gross, canc, ret, net) < 0 or max(gross, canc, ret, net) > 1_000_000:
                    problems.append("units must be between 0 and 1,000,000")
                elif net != gross - canc - ret:
                    problems.append("net_units must equal gross - cancelled - returned")
            except (TypeError, ValueError):
                problems.append("units must be whole numbers")
                gross = canc = ret = net = 0
            if not problems:
                key = (pid, r["location_id"], week)
                if key in seen:
                    problems.append("the same product, store and week appears twice")
                seen.add(key)
            if problems:
                errors.append({"row": i, "errors": problems})
            else:
                clean.append({"product_id": pid, "location_id": r["location_id"], "week_start": week, "gross_units": gross, "cancelled_units": canc, "returned_units": ret, "net_units": net,
                              "gross_revenue": r.get("gross_revenue"), "discount": r.get("discount"), "net_revenue": r.get("net_revenue")})
        if errors:
            raise PlanningError(422, "IMPORT_REJECTED", f"{len(errors)} row(s) are invalid: nothing was imported", {"errors": errors[:50], "invalid_rows": len(errors)})
        through = data_through or max(self.cal.week_end(r["week_start"]) for r in clean)
        return await self.repo.import_actuals(a.hash, a.roles, batch_id, clean, (note or "")[:300] or None, through)

    async def adjust_actual(self, a: Actor, e: dict[str, Any]) -> dict[str, Any]:
        self.require_planner(a)
        allowed = await self.allowed(a)
        if allowed is not None and e.get("location_id") not in allowed:
            raise PlanningError(403, "OUT_OF_SCOPE", "This store is outside your scope")
        loc = await self.repo.location(e.get("location_id", ""))
        if loc is None or loc["level"] != "STORE":
            raise PlanningError(404, "NOT_FOUND", "Store not found")
        week = _date(e.get("week_start"), "week_start")
        if not self.cal.is_week_start(week) or self.cal.week_end(week) > self.cal.today():
            raise PlanningError(422, "INVALID_WEEK", "Corrections apply to a closed planning week")
        if e.get("reason_code") not in workflow.ADJUSTMENT_REASONS:
            raise PlanningError(422, "INVALID_REASON", f"reason_code must be one of {', '.join(workflow.ADJUSTMENT_REASONS)}")
        delta = int(_num(e.get("delta_units"), "delta_units", lo=-100000, hi=100000))
        if delta == 0:
            raise PlanningError(422, "INVALID_DELTA", "A correction of 0 units changes nothing")
        if len((e.get("note") or "").strip()) < 10:
            raise PlanningError(422, "NOTE_REQUIRED", "Explain the correction in at least 10 characters")
        return await self.repo.create_adjustment({**e, "week_start": week, "delta_units": delta, "note": e["note"].strip()}, a.hash, a.roles)

    async def decide_adjustment(self, a: Actor, adj_id: str, approve: bool, note: str | None) -> dict[str, Any]:
        self.require_planner(a)
        if not workflow.is_approver(a.roles):
            raise PlanningError(403, "FORBIDDEN", "Only a regional manager, category manager or administrator can decide a correction")
        cur = await self.repo.get_adjustment(adj_id)
        if cur is None:
            raise PlanningError(404, "NOT_FOUND", "Correction not found")
        if cur["requested_by"] == a.hash and not self.settings.planning_allow_self_approval:
            raise PlanningError(403, "SELF_APPROVAL", "You requested this correction: someone else must decide it")
        allowed = await self.allowed(a)
        if allowed is not None and cur["location_id"] not in allowed:
            raise PlanningError(403, "OUT_OF_SCOPE", "This store is outside your scope")
        if not approve and not (note or "").strip():
            raise PlanningError(422, "REASON_REQUIRED", "A reason is required to reject a correction")
        out = await self.repo.decide_adjustment(adj_id, "APPROVED" if approve else "REJECTED", a.hash, a.roles, note)
        if out is None:
            raise PlanningError(409, "ALREADY_DECIDED", "This correction has already been decided")
        return out

    async def adjustments(self, a: Actor, status: str | None) -> dict[str, Any]:
        self.require_planner(a)
        return {"items": await self.repo.list_adjustments(status, await self.allowed(a))}

    # ------------------------------------------------------------------------------------------------ inventory planning
    async def generate_allocation(self, a: Actor, plan_id: str, f: dict[str, Any]) -> dict[str, Any]:
        self.require_planner(a)
        plan = await self._plan(plan_id)
        if plan["source"] != DEMO_SRC:
            raise PlanningError(409, "NO_STORE_STOCK", "Store-level stock exists only for the demonstration network. The online (live) data has no per-store stock, so no allocation can be calculated for it.")
        allowed = await self.allowed(a)
        data = await self.repo.product_store_inputs(plan, self._filters(f), allowed)
        by: dict[Any, dict[str, list[Any]]] = {}
        for r in data["lines"]:
            by.setdefault(r["product_id"], {}).setdefault(r["location_id"], []).append(r)
        recs_out, trs_out, missing = [], [], []
        as_of_default = None
        for pid, stores in by.items():
            sku = data["skus"].get(pid)
            if sku is None:
                continue
            ref = Reference(sku["lead_time_days"], sku["moq"], sku["case_pack"], float(sku["cost"]) if sku["cost"] is not None else None, float(sku["price"]))
            inputs, weeks = [], None
            for loc, rows in stores.items():
                stock = data["stock"].get((pid, loc))
                if stock is None:
                    missing.append(loc)
                    continue
                as_of = stock["as_of"]
                as_of_default = as_of
                weeks = [r["week_start"] for r in rows]
                used = [r["used"] for r in rows]
                if any(v is None for v in used):
                    used = []
                lo, hi = None, None
                if used and all(r["model_lo"] is not None and r["model_hi"] is not None for r in rows):
                    ratio = [(float(u) / float(r["model"])) if r["model"] else 1.0 for u, r in zip(used, rows)]
                    lo = [float(r["model_lo"]) * k for r, k in zip(rows, ratio)]
                    hi = [float(r["model_hi"]) * k for r, k in zip(rows, ratio)]
                info = data["locations"].get(loc)
                arrivals = tuple((max(0, (eta - as_of).days), q) for eta, q in data["incoming"].get((pid, loc), []))
                opened = info["opened_on"] if info and info["opened_on"] and info["opened_on"] > as_of else None
                inputs.append(inv.StoreInput(loc, info["region"] if info else "?", stock["on_hand"], stock["reserved"], arrivals, [float(v) for v in used], lo, hi, rows[0]["confidence"], opened))
            if not inputs or weeks is None:
                continue
            recs_, trs, _ = inv.plan_product(as_of_default, weeks, inputs, ref, **self.cfg)
            for r in recs_:
                recs_out.append({"product_id": str(pid), "location_id": r.location_id, "as_of": as_of_default, "action": r.action, "risk_level": r.risk_level, "required_stock": r.required_stock, "on_hand": r.sellable,
                                 "reserved": r.reserved, "incoming": r.incoming, "shortage": r.shortage, "surplus": r.surplus, "recommended_qty": r.recommended_qty, "order_by": r.order_by, "arrival_date": r.arrival_date,
                                 "safety_stock": r.safety_stock, "coverage_days": r.coverage_days, "confidence": r.confidence, "reason": r.reason,
                                 "detail": {**r.detail, "transfer_in": r.transfer_in_qty, "transfer_out": r.transfer_out_qty, "stockout_date": r.stockout_date.isoformat() if r.stockout_date else None,
                                            "forecast_basis": "APPROVED" if plan["status"] in ("APPROVED", "PUBLISHED") else "MANAGER_OR_MODEL"}})
            for t in trs:
                trs_out.append({"product_id": str(pid), "from_location": t.from_location, "to_location": t.to_location, "qty": t.qty, "lead_days": t.lead_days, "arrival_date": t.arrival_date, "reason": t.reason,
                                "avoids_order_qty": t.avoids_order_qty, "detail": t.detail})
        if not recs_out:
            raise PlanningError(409, "NOTHING_TO_PLAN", "No product and store in scope has both a forecast and a stock position")
        saved = await self.repo.save_allocations(plan_id, recs_out, trs_out, a.hash, a.roles, None)
        return {**saved, "stores_without_stock_position": len(set(missing)), "summary": await self.repo.alloc_summary(plan_id, allowed),
                "notice": "Recommendations are drafts. Nothing has been ordered or transferred; approving one records a decision only."}

    async def allocations(self, a: Actor, plan_id: str, f: dict[str, Any], page: int, size: int, status: str | None, only_actions: bool) -> dict[str, Any]:
        self.require_planner(a)
        await self._plan(plan_id)
        allowed = await self.allowed(a)
        flt = self._filters(f) | {k: f[k] for k in ("risk", "action") if f.get(k)}
        out = await self.repo.list_allocations(plan_id, flt, allowed, max(0, page), max(1, min(size, MAX_PAGE)), status, only_actions)
        out["summary"] = await self.repo.alloc_summary(plan_id, allowed)
        out["transfers"] = await self.repo.list_transfers(plan_id, allowed, None, 100)
        return out

    async def decide_recommendation(self, a: Actor, kind: str, rec_id: str, approve: bool) -> dict[str, Any]:
        self.require_planner(a)
        if not workflow.is_approver(a.roles):
            raise PlanningError(403, "FORBIDDEN", "Only a regional manager, category manager or administrator can approve or reject a recommendation")
        table = "pln_alloc_rec" if kind == "allocation" else "pln_transfer_rec"
        row = await self.repo.pool.fetchrow(f"SELECT * FROM {table} WHERE id = $1::uuid", rec_id)
        if row is None:
            raise PlanningError(404, "NOT_FOUND", "Recommendation not found")
        allowed = await self.allowed(a)
        if allowed is not None:
            locs = [row["location_id"]] if kind == "allocation" else [row["from_location"], row["to_location"]]
            if not set(locs) <= set(allowed):
                raise PlanningError(403, "OUT_OF_SCOPE", "This recommendation involves a store outside your scope")
        out = await self.repo.decide_recommendation(table, rec_id, "APPROVED" if approve else "REJECTED", a.hash, a.roles)
        if out is None:
            raise PlanningError(409, "ALREADY_DECIDED", "This recommendation is not a draft any more")
        out["notice"] = "Recorded. No purchase order or stock transfer has been created: that needs the procurement and warehouse processes."
        return out

    # ------------------------------------------------------------------------------------------------ scenarios
    async def create_scenario(self, a: Actor, *, name: str, kind: str, params: dict[str, Any], plan_id: str, f: dict[str, Any]) -> dict[str, Any]:
        results, flt = await self._run_scenario(a, kind, params, plan_id, f)
        saved = await self.repo.save_scenario(a.hash, plan_id, name.strip() or kind, kind, {**params, "filters": flt}, results)
        await self.repo.audit(a.hash, a.roles, "SCENARIO", saved["id"], "SCENARIO_CREATED", after={"kind": kind, "params": params, "products": len(results["items"])})
        return saved

    async def simulate_scenario(self, a: Actor, *, kind: str, params: dict[str, Any], plan_id: str, f: dict[str, Any]) -> dict[str, Any]:
        """The same calculation without saving anything (used by the assistant: a question is not a decision)."""
        results, _ = await self._run_scenario(a, kind, params, plan_id, f)
        return results

    async def _run_scenario(self, a: Actor, kind: str, params: dict[str, Any], plan_id: str, f: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        self.require_planner(a)
        plan = await self._plan(plan_id)
        if plan["source"] != DEMO_SRC:
            raise PlanningError(409, "NO_STORE_STOCK", "Scenarios need store-level stock, which exists only for the demonstration network")
        try:
            scen.validate(kind, params)
        except scen.ScenarioError as exc:
            raise PlanningError(422, "INVALID_SCENARIO", str(exc)) from None
        flt = self._filters(f)
        if kind == "TRANSFER":
            flt = flt | {"product_id": params["product_id"]}
        elif not any(flt.get(k) for k in ("sport", "category", "product_id", "q")):
            raise PlanningError(422, "SCOPE_REQUIRED", "Choose a sport, category or product for the scenario (at most 150 products are evaluated)")
        allowed = await self.allowed(a)
        data = await self.repo.product_store_inputs(plan, flt, allowed)
        el = await self.repo.elasticities()
        by: dict[Any, dict[str, list[Any]]] = {}
        for r in data["lines"]:
            by.setdefault(r["product_id"], {}).setdefault(r["location_id"], []).append(r)
        if len(by) > MAX_SCENARIO_PRODUCTS:
            raise PlanningError(422, "SCOPE_TOO_BROAD", f"{len(by)} products are in scope; narrow it to at most {MAX_SCENARIO_PRODUCTS}")
        ctxs, as_of, weeks = [], None, None
        for pid, stores in by.items():
            sku = data["skus"].get(pid)
            if sku is None:
                continue
            inputs = []
            for loc, rows in stores.items():
                stock = data["stock"].get((pid, loc))
                if stock is None or any(r["used"] is None for r in rows):
                    continue
                as_of = stock["as_of"]
                weeks = [r["week_start"] for r in rows]
                lo = [float(r["model_lo"]) for r in rows] if all(r["model_lo"] is not None for r in rows) else None
                hi = [float(r["model_hi"]) for r in rows] if all(r["model_hi"] is not None for r in rows) else None
                info = data["locations"].get(loc)
                arrivals = tuple((max(0, (eta - as_of).days), q) for eta, q in data["incoming"].get((pid, loc), []))
                inputs.append(inv.StoreInput(loc, info["region"] if info else "?", stock["on_hand"], stock["reserved"], arrivals, [float(r["used"]) for r in rows], lo, hi, rows[0]["confidence"],
                                             info["opened_on"] if info and info["opened_on"] and info["opened_on"] > as_of else None))
            if not inputs:
                continue
            e = el.get(sku["category"])
            elast = None if e is None else Elasticity(sku["category"], float(e["elasticity"]), float(e["elasticity_lo"]), float(e["elasticity_hi"]), float(e["pull_forward"]), int(e["n_promo_days"]), e["basis"])
            ctxs.append(scen.ProductCtx(str(pid), sku["name"], sku["sport"], sku["category"], float(sku["price"]), float(sku["cost"]) if sku["cost"] is not None else None,
                                        Reference(sku["lead_time_days"], sku["moq"], sku["case_pack"], float(sku["cost"]) if sku["cost"] is not None else None, float(sku["price"])), inputs, elast))
        if not ctxs or as_of is None or weeks is None:
            raise PlanningError(409, "NOTHING_TO_EVALUATE", "Nothing in scope has a forecast and a store stock position")
        try:
            results = scen.evaluate(kind, params, ctxs, as_of, weeks, self.cfg)
        except scen.ScenarioError as exc:
            raise PlanningError(422, "INVALID_SCENARIO", str(exc)) from None
        results["plan"] = {"id": plan["id"], "name": plan["name"], "status": plan["status"]}
        results["scope"] = flt
        return results, flt

    async def scenarios(self, a: Actor, plan_id: str | None) -> dict[str, Any]:
        self.require_planner(a)
        return {"items": await self.repo.list_scenarios(plan_id)}

    async def scenario(self, a: Actor, sid: str) -> dict[str, Any]:
        self.require_planner(a)
        s = await self.repo.get_scenario(sid)
        if s is None:
            raise PlanningError(404, "NOT_FOUND", "Scenario not found")
        return s

    async def compare_scenarios(self, a: Actor, ids: Sequence[str]) -> dict[str, Any]:
        self.require_planner(a)
        if not 2 <= len(ids) <= 5:
            raise PlanningError(422, "INVALID_COMPARE", "Choose between 2 and 5 scenarios to compare")
        out = []
        for i in ids:
            s = await self.scenario(a, i)
            out.append({"id": s["id"], "name": s["name"], "kind": s["kind"], "params": s["params"], "status": s["status"], "summary": s["results"]["summary"], "limits": s["results"]["limits"]})
        return {"scenarios": out, "notice": "Simulated outcomes. None of them is part of an approved plan or of actual performance."}

    async def adopt_scenario(self, a: Actor, sid: str) -> dict[str, Any]:
        """Only a GROWTH_TARGET scenario can be adopted, and only as TARGETS on a plan that is still editable: a target is what the business chooses to aim for, not a forecast."""
        self.require_planner(a)
        s = await self.scenario(a, sid)
        if s["kind"] != "GROWTH_TARGET":
            raise PlanningError(409, "NOT_ADOPTABLE", "Only a growth-target scenario can be adopted (as targets). Other scenarios inform decisions; they change no plan")
        if s["status"] == "ADOPTED":
            raise PlanningError(409, "ALREADY_ADOPTED", "This scenario has already been adopted")
        if not s["plan_id"]:
            raise PlanningError(409, "NO_PLAN", "The scenario is not tied to a plan")
        flt = {k: v for k, v in (s["params"].get("filters") or {}).items() if k in ("sport", "category", "region", "zone", "stores", "product_id", "q")}
        out = await self.growth_targets(a, s["plan_id"], float(s["params"]["growth_pct"]), "MANAGER", flt, True)
        await self.repo.mark_scenario_adopted(sid, a.hash)
        await self.repo.audit(a.hash, a.roles, "SCENARIO", sid, "SCENARIO_ADOPTED", after=out)
        return {**out, "scenario_id": sid}

    # ------------------------------------------------------------------------------------------------ pilots (Phase 5 products)
    async def propose_pilot(self, a: Actor, opportunity_id: str, duration_weeks: int, comparable_sport: str | None, comparable_category: str | None, source: str = DEMO_SRC) -> dict[str, Any]:
        self.require_all_scope(a)
        if not 2 <= int(duration_weeks) <= 26:
            raise PlanningError(422, "INVALID_DURATION", "A pilot lasts between 2 and 26 weeks")
        try:
            opp = await self.repo.opportunity(opportunity_id)
        except Exception:  # noqa: BLE001
            opp = None
        if opp is None or opp["record_kind"] != "PRODUCT_CANDIDATE":
            raise PlanningError(404, "NOT_FOUND", "That Product & Supplier Intelligence record is not a product candidate")
        if opp["provenance"] not in ("LIVE_EXTERNAL_SOURCE", "BUYER_ENTERED") or opp["status"] not in ("APPROVED_OPPORTUNITY", "SUPPLIER_RESEARCH", "QUOTE_COMPARISON", "APPROVED_FOR_ONBOARDING"):
            raise PlanningError(409, "NOT_APPROVED", f"The product must be a live or buyer-entered candidate that a category manager approved as a business opportunity (it is {opp['status']}, {opp['provenance']}): demo, test and unapproved products are never piloted")
        sport, category = comparable_sport or opp["sport"], comparable_category or opp["category"]
        run = await self.repo.current_run(source)
        if run is None:
            raise PlanningError(409, "NO_FORECAST", "There is no sales history scope to compare against: generate a forecast first")
        as_of_week = date.fromisoformat(run["as_of_week"])
        if not sport or not category:
            raise PlanningError(422, "SPORT_CATEGORY_REQUIRED", "The product has no sport or category: name the comparable sport and category")
        inputs = await self.repo.pilot_comparable_inputs(sport, category, source, 13, as_of_week)
        comps = pilot_mod.pick_comparables({"product_id": None, "sport": sport, "category": category, "selling_price": opp.get("selling_price")}, inputs["catalogue"])
        est = pilot_mod.estimate(comps, inputs["rates"], inputs["history_weeks"], inputs["regions"], int(duration_weeks))
        if est["status"] != "ESTIMATED":
            est["category_options"] = await self.repo.sport_categories(sport)
            est["hint"] = f"Comparables were taken from {sport} / {category}. Phase 5 and the Inventory AI name categories differently: pick the closest of {', '.join(est['category_options'])} as comparable_category."
        saved = await self.repo.save_pilot(a.hash, opportunity_id, opp["name"], est.get("stores", []), int(duration_weeks), {**est, "comparable_sport": sport, "comparable_category": category,
                                                                                                                                  "data_scope": source, "product": {k: opp[k] for k in ("name", "brand", "model", "status", "provenance")}})
        await self.repo.audit(a.hash, a.roles, "PILOT", saved["id"], "PILOT_PROPOSED", after={"opportunity": opportunity_id, "status": est["status"], "stores": len(est.get("stores", []))})
        return saved

    async def pilots(self, a: Actor) -> dict[str, Any]:
        self.require_planner(a)
        return {"items": await self.repo.list_pilots()}

    async def pilot_decide(self, a: Actor, pid: str, status: str, note: str | None) -> dict[str, Any]:
        self.require_all_scope(a)
        if not workflow.is_approver(a.roles):
            raise PlanningError(403, "FORBIDDEN", "Only a category manager or administrator can approve or stop a pilot")
        p = await self.repo.get_pilot(pid)
        if p is None:
            raise PlanningError(404, "NOT_FOUND", "Pilot not found")
        if status not in ("APPROVED", "RUNNING", "CANCELLED", "EXPAND", "CONTINUE_TESTING", "DISCONTINUE"):
            raise PlanningError(422, "INVALID_STATUS", "Unsupported pilot status")
        if p["estimate"].get("status") != "ESTIMATED" and status in ("APPROVED", "RUNNING"):
            raise PlanningError(409, "NO_ESTIMATE", "A pilot with no estimate cannot be approved: pick comparable products first")
        out = await self.repo.update_pilot(pid, status=status, decided_by=a.hash, note=note)
        await self.repo.audit(a.hash, a.roles, "PILOT", pid, f"PILOT_{status}", reason=note)
        return {**(out or {}), "notice": "Recorded as a planning decision. No product is published, no stock is bought and no price is changed from here."}

    async def pilot_observe(self, a: Actor, pid: str, observations: Sequence[dict[str, Any]]) -> dict[str, Any]:
        self.require_planner(a)
        p = await self.repo.get_pilot(pid)
        if p is None:
            raise PlanningError(404, "NOT_FOUND", "Pilot not found")
        known = {s["location_id"] for s in p["stores"]}
        clean = []
        for o in observations:
            if o.get("location_id") not in known:
                raise PlanningError(422, "INVALID_STORE", f"{o.get('location_id')} is not a pilot store")
            clean.append({"week_start": _date(o.get("week_start"), "week_start").isoformat(), "location_id": o["location_id"], "units": int(_num(o.get("units"), "units", hi=100000)),
                          "available_days": int(_num(o.get("available_days", 7), "available_days", hi=7))})
        merged = {(o["week_start"], o["location_id"]): o for o in p["observations"]} | {(o["week_start"], o["location_id"]): o for o in clean}
        updated = await self.repo.update_pilot(pid, observations=list(merged.values()), status="RUNNING" if p["status"] in ("APPROVED", "PROPOSED") else None)
        await self.repo.audit(a.hash, a.roles, "PILOT", pid, "PILOT_OBSERVED", after={"added": len(clean)})
        return {**(updated or {}), "assessment": pilot_mod.reassess(p["estimate"], list(merged.values()))}

    async def pilot_assess(self, a: Actor, pid: str) -> dict[str, Any]:
        self.require_planner(a)
        p = await self.repo.get_pilot(pid)
        if p is None:
            raise PlanningError(404, "NOT_FOUND", "Pilot not found")
        return {"pilot": p, "assessment": pilot_mod.reassess(p["estimate"], p["observations"])}

    # ------------------------------------------------------------------------------------------------ dashboard and analysis
    async def dashboard(self, a: Actor, source: str, plan_id: str | None, f: dict[str, Any], period: str | None, fy: int | None, number: int | None) -> dict[str, Any]:
        self.require_planner(a)
        allowed = await self.allowed(a)
        flt = self._filters(f)
        run = await self.repo.current_run(source)
        plans = (await self.repo.list_plans(None, None, 200))
        plan = None
        if plan_id:
            plan = await self._plan(plan_id)
        else:
            record = [p for p in plans if p["source"] == source and p["status"] in ("PUBLISHED", "APPROVED")] or [p for p in plans if p["source"] == source]
            plan = record[0] if record else None
        out: dict[str, Any] = {"source": source, "freshness": {"data_through": (await self._through(source)).isoformat() if await self._through(source) else None,
                                                                "last_forecast": run and run["created_at"], "forecast_as_of_week": run and run["as_of_week"], "last_ingest": (await self.repo.list_ingest_runs(1) or [None])[0]},
                               "data_scope": {"source": source, "synthetic": source == DEMO_SRC}, "plan": None, "period": None, "metrics": {}, "queues": {}}
        waiting = [p for p in plans if p["status"] in ("SUBMITTED", "UNDER_REVIEW")]
        out["queues"] = {"plans_awaiting_approval": len(waiting), "plans_in_draft": sum(1 for p in plans if p["status"] in ("DRAFT", "RETURNED")), "forecasts_low_confidence": None}
        if run:
            counts = await self.repo.missing_forecast_counts(run["id"])
            out["queues"]["forecasts_low_confidence"] = counts.get("low_confidence")
            out["queues"]["series_without_forecast"] = counts.get("insufficient")
            ev = await self.repo.eval_rows(run["id"], "OVERALL", "ALL")
            sel = next((r for r in ev if r["model"] == "SELECTED"), None)
            out["metrics"]["forecast_accuracy"] = None if sel is None else {"wape": sel["wape"] if sel["sufficient"] else None, "bias": sel["bias"] if sel["sufficient"] else None,
                                                                          "baseline_wape": sel["baseline_wape"] if sel["sufficient"] else None, "n_obs": sel["n_obs"], "sufficient": sel["sufficient"],
                                                                          "basis": "held-out weeks, " + ("synthetic data" if source == DEMO_SRC else "online orders")}
        if plan:
            out["plan"] = {"id": plan["id"], "name": plan["name"], "status": plan["status"], "fy_label": self.cal.fy_label(plan["fy"]), "version": plan["version"]}
            weeks = await self.repo.plan_weeks(plan["id"])
            if period and fy:
                try:
                    wanted = set(self.cal.period_weeks(period, fy, number))
                except ValueError:
                    wanted = set(weeks)
                weeks = [w for w in weeks if w in wanted]
            out["period"] = {"first_week": weeks[0].isoformat() if weeks else None, "last_week": weeks[-1].isoformat() if weeks else None, "weeks": len(weeks)}
            if weeks:
                flt2 = flt | {"week_from": weeks[0], "week_to": weeks[-1]}
                through = await self._through(source)
                tot = await self.repo.report(plan, "total", flt2, allowed, {}, through, False)
                t = tot[0] if tot else {}
                margs: list[Any] = [uuid.UUID(plan["id"])]
                mflt = self.repo._line_filter(flt2, margs, "pl", allowed)
                model_total = await self.repo.pool.fetchval("SELECT SUM(pl.model_forecast) FROM pln_plan_line pl JOIN inv_sku k ON k.product_id = pl.product_id JOIN pln_location l ON l.id = pl.location_id "
                                                            f"JOIN pln_location par ON par.id = l.parent_id WHERE pl.plan_id = $1{mflt}", *margs)
                out["metrics"].update({"model_forecast_units": float(model_total) if model_total is not None else None, "plan_forecast_units": t.get("forecast"), "target_units": t.get("target"), "actual_units": t.get("actual"),
                                       "target_attainment_pct": t.get("target_attainment_pct"), "forecast_variance": t.get("forecast_variance"), "forecast_variance_pct": t.get("forecast_variance_pct"),
                                       "lines": t.get("lines"), "lines_with_actual": t.get("with_actual"), "lines_with_target": t.get("with_target")})
                summ = await self.repo.alloc_summary(plan["id"], allowed)
                out["queues"].update({"stores_at_stockout_risk": await self.repo.pool.fetchval("SELECT COUNT(DISTINCT location_id) FROM pln_alloc_rec WHERE plan_id = $1::uuid AND status <> 'SUPERSEDED' AND risk_level = 'HIGH'" +
                                                                                               ("" if allowed is None else " AND location_id = ANY($2::text[])"), *([plan["id"]] + ([] if allowed is None else [list(allowed)]))),
                                      "products_to_replenish": summ.get("products_to_order"), "units_to_order": summ.get("units_to_order"), "stock_lines_with_surplus": summ.get("with_surplus"),
                                      "allocation_generated_at": summ.get("generated_at")})
        else:
            out["message"] = "There is no plan for this data scope yet: generate a forecast and create a plan."
        return out

    async def regional_analysis(self, a: Actor, source: str, weeks_back: int, region: str | None) -> dict[str, Any]:
        self.require_planner(a)
        through = await self._through(source)
        if through is None:
            raise PlanningError(404, "NO_DATA", "No sales data for this scope")
        last = self.cal.last_closed_week(through)
        first = self.cal.add_weeks(last, -(max(4, min(weeks_back, 104)) - 1))
        allowed = await self.allowed(a)
        matrix = await self.repo.category_matrix(source, first, last, allowed)
        stores = await self.repo.store_category_rows(source, first, last, allowed)
        top = await self.repo.top_products(source, first, last, allowed, region, 15)
        min_units = self.settings.planning_min_eval_units
        total_units = sum(m["net_units"] for m in matrix) or 0
        by_cat: dict[tuple[str, str], float] = {}
        by_reg: dict[str, float] = {}
        for m in matrix:
            by_cat[(m["sport"], m["category"])] = by_cat.get((m["sport"], m["category"]), 0) + m["net_units"]
            by_reg[m["region"]] = by_reg.get(m["region"], 0) + m["net_units"]
        insights = []
        for m in matrix:
            key = (m["sport"], m["category"])
            if not by_reg.get(m["region"]) or not total_units or not by_cat.get(key):
                continue
            share_region = m["net_units"] / by_reg[m["region"]]
            share_all = by_cat[key] / total_units
            rel = share_region / share_all - 1 if share_all else 0
            enough = m["net_units"] >= min_units and m["store_weeks"] >= 8
            stockout_share = m["weeks_stockout"] / m["store_weeks"] if m["store_weeks"] else 0
            if abs(rel) >= 0.25:
                insights.append({"region": m["region"], "sport": m["sport"], "category": m["category"], "region_share_pct": round(share_region * 100, 1), "overall_share_pct": round(share_all * 100, 1),
                                 "relative_difference_pct": round(rel * 100), "net_units": m["net_units"], "store_weeks": m["store_weeks"], "stockout_week_share_pct": round(stockout_share * 100),
                                 "confidence": "LOW" if (not enough or stockout_share >= 0.2) else "MEDIUM",
                                 "statement": f"{m['sport']} {m['category']} is {abs(round(rel * 100))}% {'above' if rel > 0 else 'below'} its overall share of units in {m['region']} "
                                              f"({share_region * 100:.1f}% against {share_all * 100:.1f}%)." + (" Low sample: not a reliable preference." if not enough else "") +
                                              (f" {round(stockout_share * 100)}% of its store-weeks had stock-outs, so sales may understate demand." if stockout_share >= 0.2 else "")})
        insights.sort(key=lambda i: (i["confidence"] != "MEDIUM", -abs(i["relative_difference_pct"])))
        per_store: dict[str, dict[str, Any]] = {}
        for r in stores:
            s = per_store.setdefault(r["location_id"], {"location_id": r["location_id"], "store_name": r["store_name"], "region": r["region"], "net_units": 0.0, "net_revenue": 0.0, "store_weeks": 0, "weeks_stockout": 0})
            s["net_units"] += r["net_units"]
            s["net_revenue"] += r["net_revenue"]
            s["store_weeks"] = max(s["store_weeks"], r["store_weeks"])
            s["weeks_stockout"] += r["weeks_stockout"]
        ranking = sorted(per_store.values(), key=lambda s: -s["net_units"])
        for i, s in enumerate(ranking, 1):
            s["rank"] = i
            s["stockout_share_pct"] = None
        med: dict[str, float] = {}
        for reg in {s["region"] for s in ranking}:
            vals = sorted(s["net_units"] for s in ranking if s["region"] == reg)
            med[reg] = vals[len(vals) // 2] if vals else 0
        for s in ranking:
            m_ = med.get(s["region"]) or 0
            s["vs_region_median_pct"] = None if not m_ else round((s["net_units"] / m_ - 1) * 100)
        return {"source": source, "weeks": {"first": first.isoformat(), "last": last.isoformat()}, "matrix": matrix, "store_ranking": ranking, "category_by_store": stores, "top_products": top, "insights": insights[:25],
                "notice": ("Computed from net units of closed weeks. " + ("The history is SYNTHETIC: differences between stores and regions come from the generator, not from real customers. " if source == DEMO_SRC else "") +
                           "Differences are relative shares, shown with their sample size; a low-sample or stock-out-affected difference is marked LOW confidence and is not a preference.")}
