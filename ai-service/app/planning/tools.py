"""Sales & Inventory Planning tools, registered in the SAME typed registry as every other tool (and so exposed through the same MCP facade).

All are READ tools restricted to the planning roles by the registry itself, whoever calls (REST agent or MCP); store and region scope is applied again inside `PlanningService`.
There is deliberately NO write tool: editing a forecast or target, submitting, approving or publishing a plan, importing or correcting actuals, and approving a recommendation are
actions a person takes in the planning screens (they carry their own confirmation, version check and audit). Nothing here orders, transfers, publishes or prices anything. A tool never
asks an LLM for a number: every figure is read from persisted records or computed by the deterministic planning code.
"""
from __future__ import annotations

from datetime import date
from typing import Annotated, Literal

from pydantic import Field

from app.planning.service import Actor, PlanningError, PlanningService
from app.planning.workflow import PLANNER_ROLES
from app.tools.dsports_client import ToolError
from app.tools.registry import Kind, StrictArgs, Tool, ToolContext

Source = Literal["POC_SYNTHETIC", "DSPORTS_LIVE"]
UUID36 = Annotated[str, Field(min_length=36, max_length=36)]


class Scope(StrictArgs):
    source: Source = "POC_SYNTHETIC"
    sport: str | None = Field(None, max_length=40)
    category: str | None = Field(None, max_length=40)
    region: str | None = Field(None, max_length=8)
    store: str | None = Field(None, max_length=40)
    q: str | None = Field(None, max_length=60, description="product name or SKU text")


class NoArgs(StrictArgs):
    pass


class ForecastArgs(Scope):
    limit: int = Field(15, ge=1, le=40)


class PlanScopeArgs(Scope):
    plan_id: UUID36 | None = None
    grain: Literal["sku", "category", "sport", "store", "zone", "region", "week", "month", "quarter", "fy", "total"] = "category"
    basis: Literal["PLAN", "MODEL"] = "PLAN"


class AccuracyArgs(StrictArgs):
    source: Source = "POC_SYNTHETIC"
    scope: Literal["OVERALL", "SPORT", "CATEGORY", "REGION", "STORE", "BUCKET", "CANDIDATE"] = "OVERALL"


class RecommendationArgs(Scope):
    plan_id: UUID36 | None = None
    risk: Literal["HIGH", "MEDIUM", "LOW"] | None = None
    only_actions: bool = True
    limit: int = Field(15, ge=1, le=40)


class InvestigateArgs(StrictArgs):
    plan_id: UUID36
    product_id: UUID36
    location_id: str = Field(max_length=40)
    week_start: date


class RegionalArgs(StrictArgs):
    source: Source = "POC_SYNTHETIC"
    weeks: int = Field(26, ge=4, le=104)
    region: str | None = Field(None, max_length=8)


class AnalysisArgs(Scope):
    plan_id: UUID36 | None = None
    refresh: bool = Field(False, description="run a new analysis instead of reading the latest one")


class ScenarioArgs(Scope):
    plan_id: UUID36 | None = None
    kind: Literal["GROWTH_TARGET", "PROMOTION", "LEAD_TIME", "SAFETY_STOCK"]
    growth_pct: float | None = Field(None, ge=-50, le=200)
    discount_pct: float | None = Field(None, gt=0, le=70)
    weeks: int | None = Field(None, ge=1, le=12)
    delta_days: int | None = Field(None, ge=-60, le=180)
    service_level: float | None = Field(None, gt=0.5, lt=0.9999)


def _svc(ctx: ToolContext) -> PlanningService:
    svc = getattr(ctx.services, "planning", None)
    if svc is None:
        raise ToolError("PLANNING_UNAVAILABLE", "Sales & Inventory Planning is not available (the AI database is not configured)")
    return svc


def _actor(ctx: ToolContext) -> Actor:
    return Actor(ctx.principal.customer_hash, list(ctx.principal.roles), ctx.principal.token)


async def _guard(call):
    try:
        return await call
    except PlanningError as exc:
        raise ToolError(exc.code, exc.message) from None


def _filters(a: Scope) -> dict:
    out = {"sport": a.sport, "category": a.category, "region": a.region, "stores": [a.store] if a.store else None, "q": a.q}
    return {k: v for k, v in out.items() if v}


async def _plan_id(svc: PlanningService, actor: Actor, source: str, given: str | None) -> str:
    if given:
        return given
    plans = (await svc.list_plans(actor, None, None))["items"]
    pick = [p for p in plans if p["source"] == source and p["status"] in ("PUBLISHED", "APPROVED")] or [p for p in plans if p["source"] == source]
    if not pick:
        raise ToolError("NO_PLAN", f"There is no {source} plan yet: generate a forecast and create a plan first")
    return pick[0]["id"]


async def get_planning_overview(ctx: ToolContext, a: Scope):
    return await _guard(_svc(ctx).dashboard(_actor(ctx), a.source, None, _filters(a), None, None, None))


async def get_data_readiness(ctx: ToolContext, a: NoArgs):
    return await _guard(_svc(ctx).readiness())


async def get_store_hierarchy(ctx: ToolContext, a: NoArgs):
    return await _guard(_svc(ctx).locations(_actor(ctx)))


async def get_weekly_forecast(ctx: ToolContext, a: ForecastArgs):
    return await _guard(_svc(ctx).forecast_series(_actor(ctx), None, a.source, _filters(a), 0, a.limit))


async def get_plan_vs_actual(ctx: ToolContext, a: PlanScopeArgs):
    svc, actor = _svc(ctx), _actor(ctx)
    return await _guard(svc.performance(actor, await _plan_id(svc, actor, a.source, a.plan_id), a.grain, _filters(a), a.basis))


async def get_forecast_accuracy(ctx: ToolContext, a: AccuracyArgs):
    return await _guard(_svc(ctx).evaluation(_actor(ctx), None, a.source, a.scope, "ALL"))


async def get_inventory_recommendations(ctx: ToolContext, a: RecommendationArgs):
    svc, actor = _svc(ctx), _actor(ctx)
    f = {**_filters(a), **({"risk": a.risk} if a.risk else {})}
    return await _guard(svc.allocations(actor, await _plan_id(svc, actor, a.source, a.plan_id), f, 0, a.limit, None, a.only_actions))


async def explain_variance(ctx: ToolContext, a: InvestigateArgs):
    return await _guard(_svc(ctx).investigate(_actor(ctx), a.plan_id, a.product_id, a.location_id, a.week_start))


async def get_regional_analysis(ctx: ToolContext, a: RegionalArgs):
    return await _guard(_svc(ctx).regional_analysis(_actor(ctx), a.source, a.weeks, a.region))


async def simulate_planning_scenario(ctx: ToolContext, a: ScenarioArgs):
    svc, actor = _svc(ctx), _actor(ctx)
    params = {k: v for k, v in {"growth_pct": a.growth_pct, "discount_pct": a.discount_pct, "weeks": a.weeks, "delta_days": a.delta_days, "service_level": a.service_level}.items() if v is not None}
    return await _guard(svc.simulate_scenario(actor, kind=a.kind, params=params, plan_id=await _plan_id(svc, actor, a.source, a.plan_id), f=_filters(a)))


async def get_planning_analysis(ctx: ToolContext, a: AnalysisArgs):
    svc, actor = _svc(ctx), _actor(ctx)
    plan_id = await _plan_id(svc, actor, a.source, a.plan_id)
    latest = None if a.refresh else await _guard(svc.latest_analysis(actor, plan_id))
    if latest and latest.get("id"):
        return latest
    return await _guard(svc.analyze(actor, plan_id, _filters(a), use_llm=False))      # the tool never calls a model: findings are deterministic, the wording is the caller's


async def get_scenarios_and_pilots(ctx: ToolContext, a: NoArgs):
    svc, actor = _svc(ctx), _actor(ctx)
    return {"scenarios": (await _guard(svc.scenarios(actor, None)))["items"][:10], "pilots": (await _guard(svc.pilots(actor)))["items"][:10]}


_D = {
    "get_planning_overview": "Totals for the current plan of a data scope: model forecast, plan forecast, target, actual, attainment, forecast accuracy, queues (plans awaiting approval, stores at risk, products to replenish) and data freshness.",
    "get_data_readiness": "Which planning capabilities rest on real data, limited data, synthetic demonstration data or nothing, with the evidence for each. Read this before trusting any forecast.",
    "get_store_hierarchy": "The region, zone and store hierarchy the caller may see (demonstration network unless real stores were loaded).",
    "get_weekly_forecast": "Weekly forecast per product and store with the method, confidence, history length and prediction range. Insufficient-data series are listed, not filled.",
    "get_plan_vs_actual": "Forecast, target and actual compared at a chosen grain (sku, category, store, region, week, month, quarter, financial year) with variances over closed weeks only.",
    "get_forecast_accuracy": "Held-out forecast accuracy (WAPE and bias) against a 4-week-average baseline. A figure on too few observations is withheld.",
    "get_inventory_recommendations": "Store-level stock requirement, replenishment quantity, order-by date, risk and reason, plus recommended store-to-store transfers. Drafts: nothing is ordered or moved.",
    "explain_variance": "Facts and labelled hypotheses for one product, store and week where actual differs from forecast or target. Never states a cause.",
    "get_regional_analysis": "Sales mix by region, sport and category with sample sizes, store ranking and evidence-based assortment differences marked LOW confidence when the sample is small or stock-outs are involved.",
    "simulate_planning_scenario": "A what-if (growth target, promotion, supplier lead time, safety stock) over the stored forecast. Nothing is saved or changed.",
    "get_planning_analysis": "The AI-assisted planning analysis of a plan: findings (observed facts separate from labelled hypotheses) with evidence links, provenance, confidence, limitations and any proposed forecast adjustment. Computed from stored records; nothing is applied.",
    "get_scenarios_and_pilots": "Saved planning scenarios and new-product pilot proposals (Product & Supplier Intelligence products) with their status.",
}


def tools() -> list[Tool]:
    spec = [("get_planning_overview", Scope, get_planning_overview), ("get_data_readiness", NoArgs, get_data_readiness), ("get_store_hierarchy", NoArgs, get_store_hierarchy),
            ("get_weekly_forecast", ForecastArgs, get_weekly_forecast), ("get_plan_vs_actual", PlanScopeArgs, get_plan_vs_actual), ("get_forecast_accuracy", AccuracyArgs, get_forecast_accuracy),
            ("get_inventory_recommendations", RecommendationArgs, get_inventory_recommendations), ("explain_variance", InvestigateArgs, explain_variance),
            ("get_regional_analysis", RegionalArgs, get_regional_analysis), ("simulate_planning_scenario", ScenarioArgs, simulate_planning_scenario),
            ("get_planning_analysis", AnalysisArgs, get_planning_analysis), ("get_scenarios_and_pilots", NoArgs, get_scenarios_and_pilots)]
    return [Tool(name, _D[name], Kind.READ, args, handler, requires_auth=True, required_any_role=tuple(sorted(PLANNER_ROLES))) for name, args, handler in spec]
