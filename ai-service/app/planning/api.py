"""Sales & Inventory Planning REST API (internal). Every route needs a verified token with a planning role (SALES_PLANNER, STORE_MANAGER, REGIONAL_MANAGER, CATEGORY_MANAGER or ADMIN);
what each role may do (edit, approve, publish, see which stores) is enforced in `PlanningService`, and denied attempts are audited. The frontend only mirrors those rules.
Nothing here places an order, moves stock, publishes a product, or changes a price or any other commerce data."""
from __future__ import annotations

import logging
from datetime import date
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, ConfigDict, Field

from app.auth.jwt import Principal, current_principal
from app.errors import ApiException
from app.obs import metrics
from app.planning import workflow
from app.planning.service import Actor, PlanningError, PlanningService

log = logging.getLogger(__name__)
router = APIRouter(prefix="/v1/planning", tags=["sales-inventory-planning"])
SOURCE_RE = "^(POC_SYNTHETIC|DSPORTS_LIVE)$"


async def actor(request: Request, principal: Principal = Depends(current_principal)) -> Actor:
    if not workflow.is_planner(principal.roles):
        svc = getattr(request.app.state.services, "planning", None)
        if svc is not None:
            try:
                await svc.repo.audit(principal.customer_hash, principal.roles, "ACCESS", None, "DENIED", after={"path": request.url.path[:120]})
            except Exception:  # noqa: BLE001
                log.warning("could not audit a denied planning request", exc_info=True)
        metrics.GUARD_BLOCKS.labels("planning", "forbidden").inc()
        raise ApiException(403, "FORBIDDEN", "Sales & Inventory Planning is restricted to planners, managers and administrators")
    return Actor(principal.customer_hash, list(principal.roles), principal.token)


def service(request: Request, _: Actor = Depends(actor)) -> PlanningService:
    """Depends on `actor`, so the role check always runs BEFORE anything about the service's state is revealed."""
    svc = getattr(request.app.state.services, "planning", None)
    if svc is None:
        raise ApiException(503, "PLANNING_UNAVAILABLE", "Sales & Inventory Planning needs the AI database, which is not configured or reachable")
    return svc


async def run(coro):
    try:
        return await coro
    except PlanningError as exc:
        raise ApiException(exc.status, exc.code, exc.message, {"info": exc.detail} if exc.detail else None) from None
    except ValueError as exc:
        raise ApiException(422, "INVALID_REQUEST", str(exc)) from None


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class IngestIn(Strict):
    mode: str = Field("INCREMENTAL", pattern="^(INCREMENTAL|BACKFILL)$")


class ScopeIn(Strict):
    user_id: str = Field(min_length=8, max_length=64)
    location_id: str = Field(min_length=1, max_length=40)
    revoke: bool = False


class ForecastRunIn(Strict):
    scope: str = Field(pattern=SOURCE_RE)


class PlanScope(Strict):
    sport: str | None = Field(None, max_length=40)
    category: str | None = Field(None, max_length=40)
    region: str | None = Field(None, max_length=8)
    zone: str | None = Field(None, max_length=16)
    stores: list[Annotated[str, Field(max_length=40)]] | None = Field(None, max_length=200)
    product_id: str | None = Field(None, max_length=40)


class PlanIn(Strict):
    name: str = Field(min_length=3, max_length=120)
    source: str = Field(pattern=SOURCE_RE)
    period: str = Field("HORIZON", pattern="^(HORIZON|FY|Q|M)$")
    fy: int | None = Field(None, ge=2000, le=2100)
    number: int | None = Field(None, ge=1, le=12)
    scope: PlanScope = Field(default_factory=PlanScope)
    run_id: str | None = Field(None, max_length=40)


class EditIn(Strict):
    product_id: str = Field(max_length=40)
    location_id: str = Field(max_length=40)
    week_start: date
    expected_line_version: int = Field(ge=1)
    target_units: float | None = None
    manager_forecast: float | None = None
    reason_code: str | None = Field(None, max_length=30)
    note: str | None = Field(None, max_length=500)


class EditsIn(Strict):
    edits: list[EditIn] = Field(min_length=1, max_length=500)


class GrowthIn(Strict):
    growth_pct: float = Field(ge=-90, le=500)
    basis: str = Field("MODEL", pattern="^(MODEL|MANAGER)$")
    overwrite: bool = False
    filters: PlanScope = Field(default_factory=PlanScope)


class TransitionIn(Strict):
    status: str = Field(max_length=20)
    reason: str | None = Field(None, max_length=500)
    expected_version: int = Field(ge=1)


class ReviseIn(Strict):
    name: str | None = Field(None, min_length=3, max_length=120)


class ImportRow(Strict):
    product_id: str | None = Field(None, max_length=40)
    sku: str | None = Field(None, max_length=60)
    location_id: str = Field(max_length=40)
    week_start: date
    gross_units: int | None = None
    cancelled_units: int = 0
    returned_units: int = 0
    net_units: int | None = None
    gross_revenue: float | None = Field(None, ge=0)
    discount: float | None = Field(None, ge=0)
    net_revenue: float | None = Field(None, ge=0)


class ImportIn(Strict):
    batch_id: str = Field(max_length=64)
    rows: list[ImportRow] = Field(min_length=1, max_length=5000)
    note: str | None = Field(None, max_length=300)
    data_through: date | None = None


class AdjustIn(Strict):
    product_id: str = Field(max_length=40)
    location_id: str = Field(max_length=40)
    week_start: date
    delta_units: int
    delta_revenue: float | None = None
    reason_code: str = Field(max_length=30)
    note: str = Field(min_length=10, max_length=500)


class DecisionIn(Strict):
    approve: bool
    note: str | None = Field(None, max_length=500)


class ScenarioIn(Strict):
    plan_id: str = Field(max_length=40)
    name: str = Field(min_length=2, max_length=100)
    kind: str = Field(max_length=20)
    params: dict[str, Any] = Field(default_factory=dict)
    filters: PlanScope = Field(default_factory=PlanScope)


class CompareIn(Strict):
    ids: list[str] = Field(min_length=2, max_length=5)


class PilotIn(Strict):
    opportunity_id: str = Field(max_length=40)
    duration_weeks: int = Field(8, ge=2, le=26)
    comparable_sport: str | None = Field(None, max_length=40)
    comparable_category: str | None = Field(None, max_length=40)
    source: str = Field("POC_SYNTHETIC", pattern=SOURCE_RE)


class PilotDecisionIn(Strict):
    status: str = Field(max_length=20)
    note: str | None = Field(None, max_length=500)


class ObservationIn(Strict):
    week_start: date
    location_id: str = Field(max_length=40)
    units: int = Field(ge=0, le=100000)
    available_days: int = Field(7, ge=0, le=7)


class ObservationsIn(Strict):
    observations: list[ObservationIn] = Field(min_length=1, max_length=200)


def _flt(sport: str | None, category: str | None, region: str | None, zone: str | None, store: list[str] | None, q: str | None = None, product_id: str | None = None,
         week_from: date | None = None, week_to: date | None = None, has_target: bool | None = None, has_adjustment: bool | None = None, confidence: str | None = None) -> dict[str, Any]:
    return {"sport": sport, "category": category, "region": region, "zone": zone, "stores": store, "q": q, "product_id": product_id, "week_from": week_from, "week_to": week_to,
            "has_target": has_target, "has_adjustment": has_adjustment, "confidence": confidence}


# ------------------------------------------------------------------------------------------------ data
@router.get("/readiness")
async def readiness(s: PlanningService = Depends(service)):
    return await run(s.readiness())


@router.get("/locations")
async def locations(a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.locations(a))


@router.get("/taxonomy")
async def taxonomy(a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return {"sports": await s.repo.taxonomy()}


@router.post("/scope")
async def scope(body: ScopeIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    """Grant (or revoke) a user's scope over a region, zone or store. Administrators only. The user is identified by their DSports user id."""
    return await run(s.grant_scope(a, body.user_id, body.location_id, body.revoke))


@router.post("/demo/seed", status_code=201)
async def demo_seed(a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    """Build the labelled DEMONSTRATION store network and synthetic store-level history from the Inventory AI's synthetic data. Administrators only."""
    return await run(s.seed_demo(a))


@router.post("/ingest", status_code=202)
async def ingest(body: IngestIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    """Read the DSports sales feed (real online orders) with the administrator's own token and rebuild the weekly aggregates it touches. Idempotent."""
    return await run(s.ingest(a, body.mode))


@router.get("/ingest/runs")
async def ingest_runs(s: PlanningService = Depends(service)):
    return {"items": await s.repo.list_ingest_runs(20), "reconciliation": await s.repo.reconcile_live()}


# ------------------------------------------------------------------------------------------------ forecasts
@router.post("/forecast/runs", status_code=201)
async def forecast_run(body: ForecastRunIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.run_forecast(a, body.scope))


@router.get("/forecast/overview")
async def forecast_overview(source: str = Query("POC_SYNTHETIC", pattern=SOURCE_RE), a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.forecast_overview(a, source))


@router.get("/forecast/series")
async def forecast_series(source: str = Query("POC_SYNTHETIC", pattern=SOURCE_RE), run_id: str | None = None, sport: str | None = None, category: str | None = None, region: str | None = None,
                          store: Annotated[list[str] | None, Query()] = None, q: str | None = Query(None, max_length=60), confidence: str | None = Query(None, pattern="^(HIGH|MEDIUM|LOW|NONE)$"),
                          status: str | None = Query(None, pattern="^(OK|FALLBACK|INSUFFICIENT_DATA)$"), page: int = Query(0, ge=0, le=100000), size: int = Query(25, ge=1, le=200),
                          a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    f = _flt(sport, category, region, None, store, q, confidence=confidence)
    if status:
        f["status"] = status
    return await run(s.forecast_series(a, run_id, source, f, page, size))


@router.get("/forecast/evaluation")
async def evaluation(source: str = Query("POC_SYNTHETIC", pattern=SOURCE_RE), run_id: str | None = None, scope: str | None = Query(None, pattern="^(OVERALL|SPORT|CATEGORY|REGION|STORE|BUCKET|METHOD|CANDIDATE)$"),
                     horizon: str | None = Query(None, pattern="^(ALL|H1|H2_4|H5_8|H9_PLUS)$"), a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.evaluation(a, run_id, source, scope, horizon))


# ------------------------------------------------------------------------------------------------ plans
@router.post("/plans", status_code=201)
async def create_plan(body: PlanIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.create_plan(a, name=body.name, source=body.source, fy=body.fy, period=body.period, number=body.number, scope=body.scope.model_dump(exclude_none=True), run_id=body.run_id))


@router.get("/plans")
async def list_plans(status: str | None = Query(None, pattern="^(DRAFT|SUBMITTED|UNDER_REVIEW|APPROVED|PUBLISHED|REJECTED|RETURNED|SUPERSEDED)$"), fy: int | None = None,
                     a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.list_plans(a, status, fy))


@router.get("/plans/{plan_id}")
async def get_plan(plan_id: str, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.plan_view(a, plan_id))


@router.get("/plans/{plan_id}/lines")
async def plan_lines(plan_id: str, sport: str | None = None, category: str | None = None, region: str | None = None, zone: str | None = None, store: Annotated[list[str] | None, Query()] = None,
                     q: str | None = Query(None, max_length=60), product_id: str | None = None, week_from: date | None = None, week_to: date | None = None, has_target: bool | None = None,
                     has_adjustment: bool | None = None, confidence: str | None = Query(None, pattern="^(HIGH|MEDIUM|LOW|NONE)$"),
                     sort: str = Query("product", pattern="^(product|store|week|model|target|variance)$"), page: int = Query(0, ge=0, le=1000000), size: int = Query(50, ge=1, le=200),
                     a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.plan_lines(a, plan_id, _flt(sport, category, region, zone, store, q, product_id, week_from, week_to, has_target, has_adjustment, confidence), sort, page, size))


@router.patch("/plans/{plan_id}/lines")
async def edit_lines(plan_id: str, body: EditsIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    """Edit targets and manager forecasts. Each line carries the version the editor saw: if any line changed meanwhile, NOTHING is saved and the conflicting lines are returned (409)."""
    return await run(s.edit_lines(a, plan_id, [e.model_dump(exclude_unset=True, mode="json") for e in body.edits]))


@router.post("/plans/{plan_id}/growth-targets")
async def growth_targets(plan_id: str, body: GrowthIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.growth_targets(a, plan_id, body.growth_pct, body.basis, body.filters.model_dump(exclude_none=True), body.overwrite))


@router.post("/plans/{plan_id}/transition")
async def transition(plan_id: str, body: TransitionIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.transition(a, plan_id, body.status, body.reason, body.expected_version))


@router.post("/plans/{plan_id}/revise", status_code=201)
async def revise(plan_id: str, body: ReviseIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.revise(a, plan_id, body.name))


@router.get("/plans/{plan_id}/impact")
async def impact(plan_id: str, product_id: str, location_id: str, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.impact(a, plan_id, product_id, location_id))


@router.get("/plans/{plan_id}/performance")
async def performance(plan_id: str, grain: str = Query("category", pattern="^(sku|category|sport|store|zone|region|week|month|quarter|fy|total)$"), basis: str = Query("PLAN", pattern="^(PLAN|MODEL)$"),
                      sport: str | None = None, category: str | None = None, region: str | None = None, zone: str | None = None, store: Annotated[list[str] | None, Query()] = None,
                      q: str | None = Query(None, max_length=60), product_id: str | None = Query(None, max_length=40), week_from: date | None = None, week_to: date | None = None,
                      a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.performance(a, plan_id, grain, _flt(sport, category, region, zone, store, q, product_id, week_from, week_to), basis))


@router.get("/plans/{plan_id}/investigate")
async def investigate(plan_id: str, product_id: str, location_id: str, week_start: date, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.investigate(a, plan_id, product_id, location_id, week_start))


@router.get("/plans/{plan_id}/audit")
async def plan_audit(plan_id: str, limit: int = Query(100, ge=1, le=500), a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    await run(s.plan_view(a, plan_id))                      # planner role, existence and scope check
    return {"items": await s.repo.audit_for_plan(plan_id, limit)}


# ------------------------------------------------------------------------------------------------ actuals
@router.get("/actuals")
async def actuals(source: str = Query("POC_SYNTHETIC", pattern=SOURCE_RE), week_from: date = Query(...), week_to: date = Query(...), dim: str = Query("total", pattern="^(store|region|zone|sport|category|product|total)$"),
                  sport: str | None = None, category: str | None = None, region: str | None = None, zone: str | None = None, store: Annotated[list[str] | None, Query()] = None,
                  product_id: str | None = None, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.actuals(a, source, week_from, week_to, dim, _flt(sport, category, region, zone, store, None, product_id)))


@router.post("/actuals/import", status_code=201)
async def import_actuals(body: ImportIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    """Load weekly store actuals (closed weeks, store channel only). Idempotent on batch_id; any invalid row rejects the whole batch; audited."""
    return await run(s.import_actuals(a, body.batch_id, [r.model_dump(exclude_unset=True, mode="json") for r in body.rows], body.note, body.data_through))


@router.post("/actuals/adjustments", status_code=201)
async def adjust_actual(body: AdjustIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    """Ask for a correction to an actual. It never overwrites the authoritative figure: once approved it is shown as a separate delta."""
    return await run(s.adjust_actual(a, body.model_dump(mode="json")))


@router.get("/actuals/adjustments")
async def adjustments(status: str | None = Query(None, pattern="^(PENDING|APPROVED|REJECTED)$"), a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.adjustments(a, status))


@router.post("/actuals/adjustments/{adj_id}/decision")
async def decide_adjustment(adj_id: str, body: DecisionIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.decide_adjustment(a, adj_id, body.approve, body.note))


# ------------------------------------------------------------------------------------------------ inventory planning
@router.post("/plans/{plan_id}/allocations/generate", status_code=201)
async def generate_allocation(plan_id: str, sport: str | None = None, category: str | None = None, region: str | None = None, store: Annotated[list[str] | None, Query()] = None,
                              q: str | None = Query(None, max_length=60), a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.generate_allocation(a, plan_id, _flt(sport, category, region, None, store, q)))


@router.get("/plans/{plan_id}/allocations")
async def allocations(plan_id: str, sport: str | None = None, category: str | None = None, region: str | None = None, store: Annotated[list[str] | None, Query()] = None,
                      q: str | None = Query(None, max_length=60), risk: str | None = Query(None, pattern="^(HIGH|MEDIUM|LOW)$"), action: str | None = Query(None, pattern="^(ORDER|EXPEDITE_INCOMING|TRANSFER_IN|NONE|REVIEW)$"),
                      status: str | None = Query(None, pattern="^(DRAFT|APPROVED|REJECTED)$"), only_actions: bool = False, page: int = Query(0, ge=0, le=100000), size: int = Query(50, ge=1, le=200),
                      a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    f = _flt(sport, category, region, None, store, q)
    f.update({"risk": risk, "action": action})
    return await run(s.allocations(a, plan_id, f, page, size, status, only_actions))


@router.post("/allocations/{rec_id}/decision")
async def decide_allocation(rec_id: str, body: DecisionIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.decide_recommendation(a, "allocation", rec_id, body.approve))


@router.post("/transfers/{rec_id}/decision")
async def decide_transfer(rec_id: str, body: DecisionIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.decide_recommendation(a, "transfer", rec_id, body.approve))


# ------------------------------------------------------------------------------------------------ scenarios and pilots
@router.post("/scenarios", status_code=201)
async def create_scenario(body: ScenarioIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.create_scenario(a, name=body.name, kind=body.kind, params=body.params, plan_id=body.plan_id, f=body.filters.model_dump(exclude_none=True)))


@router.get("/scenarios")
async def scenarios(plan_id: str | None = None, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.scenarios(a, plan_id))


@router.get("/scenarios/{sid}")
async def scenario(sid: str, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.scenario(a, sid))


@router.post("/scenarios/compare")
async def compare(body: CompareIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.compare_scenarios(a, body.ids))


@router.post("/scenarios/{sid}/adopt")
async def adopt(sid: str, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.adopt_scenario(a, sid))


@router.post("/pilots", status_code=201)
async def propose_pilot(body: PilotIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.propose_pilot(a, body.opportunity_id, body.duration_weeks, body.comparable_sport, body.comparable_category, body.source))


@router.get("/pilots")
async def pilots(a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.pilots(a))


@router.post("/pilots/{pid}/decision")
async def pilot_decision(pid: str, body: PilotDecisionIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.pilot_decide(a, pid, body.status, body.note))


@router.post("/pilots/{pid}/observations")
async def pilot_observations(pid: str, body: ObservationsIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.pilot_observe(a, pid, [o.model_dump(mode="json") for o in body.observations]))


@router.get("/pilots/{pid}/assessment")
async def pilot_assessment(pid: str, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.pilot_assess(a, pid))


class AskIn(Strict):
    question: str = Field(min_length=3, max_length=400)


# ------------------------------------------------------------------------------------------------ AI-assisted analysis and the review queue
class AnalysisIn(Strict):
    sport: str | None = Field(None, max_length=60)
    category: str | None = Field(None, max_length=60)
    region: str | None = Field(None, max_length=10)
    zone: str | None = Field(None, max_length=20)
    stores: list[str] | None = Field(None, max_length=50)
    product_id: str | None = Field(None, max_length=40)
    use_llm: bool = True


class BulkReviewIn(Strict):
    plan_ids: list[str] = Field(min_length=1, max_length=50)
    action: str = Field(pattern="^(APPROVE|RETURN|REJECT)$")
    note: str | None = Field(None, max_length=500)
    preview: bool = True
    acknowledge_synthetic: bool = False


@router.post("/plans/{plan_id}/analysis", status_code=201)
async def analyze_plan(plan_id: str, body: AnalysisIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    """Run the planning analysis: deterministic findings from stored records, an optional LLM-worded summary that must pass a numeric grounding check, persisted with its provenance."""
    flt = {k: v for k, v in body.model_dump().items() if k != "use_llm" and v not in (None, [])}
    return await run(s.analyze(a, plan_id, flt, body.use_llm))


@router.get("/plans/{plan_id}/analysis")
async def latest_analysis(plan_id: str, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.latest_analysis(a, plan_id))


@router.get("/analyses/{analysis_id}")
async def get_analysis(analysis_id: str, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.analysis(a, analysis_id))


@router.post("/analyses/{analysis_id}/findings/{finding_id}/adopt")
async def adopt_finding(analysis_id: str, finding_id: str, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    """Apply the finding's proposed manager forecast as an ordinary, audited, version-checked plan edit. It does not approve anything."""
    return await run(s.decide_finding(a, analysis_id, finding_id, True))


@router.post("/analyses/{analysis_id}/findings/{finding_id}/dismiss")
async def dismiss_finding(analysis_id: str, finding_id: str, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.decide_finding(a, analysis_id, finding_id, False))


@router.get("/review/queue")
async def review_queue(status: str | None = Query(None, pattern="^(SUBMITTED|UNDER_REVIEW|APPROVED|PUBLISHED|REJECTED|RETURNED)$"), region: str | None = Query(None, max_length=10),
                       a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.review_queue(a, status, region))


@router.get("/plans/{plan_id}/breakdown")
async def plan_breakdown(plan_id: str, by: str = Query("region", pattern="^(region|zone|store)$"), a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.plan_breakdown(a, plan_id, by))


@router.post("/review/bulk")
async def bulk_review(body: BulkReviewIn, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    """Approve, return or reject several plans. `preview` (the default) only reports eligibility and consequences; applying is all-or-nothing on eligibility and each plan still goes through its own checks."""
    return await run(s.bulk_review(a, body.plan_ids, body.action, body.note, body.preview, body.acknowledge_synthetic))

@router.post("/ask")
async def ask(body: AskIn, request: Request, principal: Principal = Depends(current_principal), a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    """Plain-language questions, answered from the typed planning tools (role-gated by the tool registry) and worded from their results. No model supplies a fact or a number."""
    from app.planning import ask as asker
    from app.tools.registry import ToolContext
    services = request.app.state.services
    ctx = ToolContext(principal=principal, dsports=services.dsports, settings=services.settings, services=services, max_calls=50)
    result = await asker.answer(services.registry, ctx, body.question)
    await s.repo.audit(a.hash, a.roles, "ACCESS", None, "ASK", after={"intent": result["intent"], "question_chars": len(body.question)})
    return result


# ------------------------------------------------------------------------------------------------ dashboard and analysis
@router.get("/dashboard")
async def dashboard(source: str = Query("POC_SYNTHETIC", pattern=SOURCE_RE), plan_id: str | None = None, period: str | None = Query(None, pattern="^(FY|Q|M)$"), fy: int | None = None, number: int | None = Query(None, ge=1, le=12),
                    sport: str | None = None, category: str | None = None, region: str | None = None, store: Annotated[list[str] | None, Query()] = None,
                    a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.dashboard(a, source, plan_id, _flt(sport, category, region, None, store), period, fy, number))


@router.get("/analysis/regional")
async def regional(source: str = Query("POC_SYNTHETIC", pattern=SOURCE_RE), weeks: int = Query(26, ge=4, le=104), region: str | None = None, a: Actor = Depends(actor), s: PlanningService = Depends(service)):
    return await run(s.regional_analysis(a, source, weeks, region))


@router.get("/audit")
async def audit(entity: str | None = Query(None, max_length=20), entity_id: str | None = Query(None, max_length=120), limit: int = Query(100, ge=1, le=500), a: Actor = Depends(actor),
                s: PlanningService = Depends(service)):
    try:
        s.require_admin(a)
    except PlanningError as exc:
        raise ApiException(exc.status, exc.code, exc.message) from None
    return {"items": await s.repo.audit_log(entity, entity_id, limit)}
