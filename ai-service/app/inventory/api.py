"""Inventory Intelligence REST API (internal). Every route requires a verified token with the ADMIN role; the frontend hiding the page is
a convenience, this dependency is the enforcement. Denied attempts are audited. All routes are read-only except `models/train`
(recomputes the persisted forecast) and `scenario` (a calculation that is logged); nothing here changes DSports prices, orders or stock.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time

from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, ConfigDict, Field

from app.auth.jwt import Principal, current_principal
from app.errors import ApiException
from app.inventory import pipeline
from app.inventory.copilot import AskRequest, InventoryResponse
from app.inventory.repository import InvalidRequest, InventoryRepository, InventoryUnavailable
from app.obs import metrics
from app.obs.context import correlation_id_var

log = logging.getLogger(__name__)
router = APIRouter(prefix="/v1/inventory", tags=["inventory-intelligence"])
_train_lock = asyncio.Lock()


async def admin(request: Request, principal: Principal = Depends(current_principal)) -> Principal:
    if not principal.is_admin:
        store = getattr(request.app.state, "store", None)
        if store is not None:
            try:
                await store.pool.execute("INSERT INTO inv_audit (actor_hash, action, detail, correlation_id) VALUES ($1,'DENIED',$2::jsonb,$3)", principal.customer_hash,
                                         json.dumps({"path": request.url.path[:120]}), correlation_id_var.get())
            except Exception:
                log.warning("could not audit a denied inventory request", exc_info=True)
        metrics.GUARD_BLOCKS.labels("inventory", "forbidden").inc()
        raise ApiException(403, "FORBIDDEN", "Inventory intelligence is restricted to authorised business users")
    return principal


def repo(request: Request, _: Principal = Depends(admin)) -> InventoryRepository:
    """Depends on `admin`, so authentication and the role check always run BEFORE anything about the service's state is revealed."""
    r = getattr(request.app.state.services, "inventory", None)
    if r is None:
        raise ApiException(503, "INVENTORY_UNAVAILABLE", "Inventory intelligence needs the AI database, which is not configured or reachable")
    return r


async def _run(coro):
    try:
        return await coro
    except InventoryUnavailable as exc:
        raise ApiException(404, "INSUFFICIENT_DATA", str(exc)) from None
    except InvalidRequest as exc:
        raise ApiException(422, "INVALID_REQUEST", str(exc)) from None


async def _with_provenance(r: InventoryRepository, payload: dict) -> dict:
    """Adds the run / model / as-of / data-source provenance block: a response is never just numbers."""
    return {**payload, "provenance": await _run(r.provenance())}


@router.get("/overview")
async def overview(r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _run(r.overview())


@router.get("/taxonomy")
async def taxonomy(r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return {"sports": await r.taxonomy()}


@router.get("/forecast")
async def forecast_aggregate(sport: str | None = None, category: str | None = None, r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _run(r.forecast_aggregate(sport=sport, category=category))


@router.get("/forecast/{product_id}")
async def forecast_product(product_id: str, r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _run(r.forecast(product_id))


@router.get("/products")
async def products(sport: str | None = None, category: str | None = None, q: str | None = None, r: InventoryRepository = Depends(repo),
                   _: Principal = Depends(admin)):
    return await _with_provenance(r, {"products": await r.positions(sport=sport, category=category, product=q, limit=200)})


@router.get("/risk")
async def risk(sport: str | None = None, category: str | None = None, level: str | None = Query(None, pattern="^(HIGH|MEDIUM|LOW)$"),
               scenario: str = "BASELINE", limit: int = Query(30, ge=1, le=200), r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _with_provenance(r, {"scenario": scenario, "risks": await _run(r.stock_risks(sport=sport, category=category, level=level, scenario=scenario, limit=limit)), "data_source": "POC_SYNTHETIC"})


@router.get("/replenishment")
async def replenishment(sport: str | None = None, category: str | None = None, scenario: str = "BASELINE", limit: int = Query(40, ge=1, le=200),
                        r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _with_provenance(r, {"scenario": scenario, "recommendations": await _run(r.replenishment(sport=sport, category=category, scenario=scenario, limit=limit)), "recommendation_status": "AI Recommendation: no purchase order was created.", "data_source": "POC_SYNTHETIC"})


@router.get("/overstock")
async def overstock(sport: str | None = None, category: str | None = None, older_than_days: int | None = Query(None, ge=1, le=1000),
                    limit: int = Query(40, ge=1, le=200), r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _with_provenance(r, {"products": await _run(r.overstock(sport=sport, category=category, min_age_days=older_than_days, limit=limit)), "data_source": "POC_SYNTHETIC"})


@router.get("/slow-moving")
async def slow_moving(sport: str | None = None, category: str | None = None, r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _with_provenance(r, {"products": await _run(r.slow_moving(sport=sport, category=category, limit=100)), "data_source": "POC_SYNTHETIC"})


@router.get("/aging")
async def aging(sport: str | None = None, category: str | None = None, r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _with_provenance(r, await _run(r.aging_distribution(sport=sport, category=category)))


@router.get("/clearance")
async def clearance(sport: str | None = None, category: str | None = None, limit: int = Query(15, ge=1, le=50), r: InventoryRepository = Depends(repo),
                    p: Principal = Depends(admin)):
    return await _with_provenance(r, await _run(r.clearance_candidates(sport=sport, category=category, limit=limit, actor_hash=p.customer_hash)))


@router.get("/events")
async def events(r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _with_provenance(r, {"events": await _run(r.events()), "data_source": "POC_REFERENCE"})


@router.get("/events/{event_id}")
async def event_impact(event_id: str, r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _with_provenance(r, await _run(r.event_impact(event_id)))


@router.get("/regions")
async def regions(sport: str | None = None, category: str | None = None, product_id: str | None = None, event_id: str | None = None,
                  r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _with_provenance(r, await _run(r.regions(sport=sport, category=category, product_id=product_id, event_id=event_id)))


@router.get("/usage")
async def usage(hours: int = Query(168, ge=1, le=24 * 90), r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return await _run(r.usage_summary(hours))


@router.get("/quality")
async def quality(r: InventoryRepository = Depends(repo), _: Principal = Depends(admin)):
    return {"model": await _run(r.model_info()), "data_quality": await _run(r.data_quality())}


class ScenarioBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sport: str | None = Field(None, max_length=40)
    category: str | None = Field(None, max_length=40)
    productIds: list[str] | None = Field(None, max_length=100)
    discounts: list[Annotated[float, Field(ge=0.0, le=70.0, allow_inf_nan=False)]] = Field(default_factory=lambda: [30.0, 50.0], min_length=1, max_length=6)
    days: int = Field(14, ge=3, le=60)
    slowMovingOnly: bool = False


@router.post("/scenario")
async def scenario(body: ScenarioBody, r: InventoryRepository = Depends(repo), p: Principal = Depends(admin)):
    """What-if calculation. Logged, not executed: it never touches a price."""
    metrics.REQUESTS.labels("inv_scenario_api", "inventory", "ok").inc()
    return await _run(r.scenario(product_ids=body.productIds, sport=body.sport, category=body.category, discounts=body.discounts, days=body.days,
                                 health=["Slow Moving", "Critical Aging"] if body.slowMovingOnly else None, actor_hash=p.customer_hash))


@router.post("/models/train")
async def train(request: Request, r: InventoryRepository = Depends(repo), p: Principal = Depends(admin)):
    """Manual retraining of the persisted forecast (about 20 seconds). One run at a time; the assistant never calls this."""
    if _train_lock.locked():
        raise ApiException(409, "TRAINING_IN_PROGRESS", "A training run is already in progress")
    async with _train_lock:
        started = time.perf_counter()
        try:
            out = await pipeline.train(r.pool, actor_hash=p.customer_hash, correlation_id=correlation_id_var.get())
        except ValueError as exc:
            raise ApiException(409, "NO_DATA", str(exc)) from None
        metrics.REQUESTS.labels("inv_train", "inventory", "ok").inc()
        out["total_ms"] = int((time.perf_counter() - started) * 1000)
        return out


@router.post("/copilot", response_model=InventoryResponse)
async def copilot(body: AskRequest, request: Request, p: Principal = Depends(admin)) -> InventoryResponse:
    if getattr(request.app.state.services, "inventory", None) is None:
        raise ApiException(503, "INVENTORY_UNAVAILABLE", "Inventory intelligence needs the AI database, which is not configured or reachable")
    try:
        return await request.app.state.inventory_copilot.ask(p, body, correlation_id_var.get())
    except PermissionError:
        raise ApiException(403, "FORBIDDEN", "Inventory intelligence is restricted to authorised business users") from None
