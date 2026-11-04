"""Inventory Intelligence tools, registered in the SAME typed registry as the customer tools (and therefore exposed through the same
MCP facade). All are READ tools restricted to ADMIN by the registry itself. None of them changes a price, a purchase order, a stock
level or a promotion: there is deliberately no WRITE inventory tool in Phase 2 (decision support only).

Every numeric result comes from persisted model output or deterministic code. A tool never asks an LLM for a number.
"""
from __future__ import annotations

from typing import Annotated, Literal

from pydantic import Field

from app.guard.untrusted import sanitize_untrusted
from app.inventory.repository import SOURCE_NOTE, InvalidRequest, InventoryRepository, InventoryUnavailable
from app.tools.dsports_client import ToolError
from app.tools.registry import Kind, StrictArgs, Tool, ToolContext

ADMIN = "ADMIN"
Level = Literal["HIGH", "MEDIUM", "LOW"]


class Selection(StrictArgs):
    sport: str | None = Field(None, max_length=40, description="e.g. Cricket, Football, Running")
    category: str | None = Field(None, max_length=40, description="e.g. Bat, Shoes, Jersey")
    product: str | None = Field(None, max_length=80, description="part of a product name or SKU")


class NoArgs(StrictArgs):
    pass


class PositionArgs(Selection):
    limit: int = Field(15, ge=1, le=50)


class ProductArgs(StrictArgs):
    product_id: str = Field(min_length=36, max_length=36)
    days: int = Field(60, ge=7, le=365)


class SalesArgs(Selection):
    days: int = Field(90, ge=14, le=365)


class ForecastArgs(Selection):
    product_id: str | None = Field(None, min_length=36, max_length=36)


class RiskArgs(Selection):
    level: Level | None = None
    within_days: int | None = Field(None, ge=1, le=90, description="only products projected to run out within this many days")
    event_id: str | None = Field(None, max_length=60, description="assess against an event-adjusted forecast")
    limit: int = Field(10, ge=1, le=50)


class ReplenishArgs(Selection):
    event_id: str | None = Field(None, max_length=60)
    limit: int = Field(15, ge=1, le=50)


class OverstockArgs(Selection):
    older_than_days: int | None = Field(None, ge=1, le=1000)
    limit: int = Field(15, ge=1, le=50)


class RegionArgs(Selection):
    product_id: str | None = Field(None, min_length=36, max_length=36)
    event_id: str | None = Field(None, max_length=60)
    excess_only: bool = False


class EventArgs(StrictArgs):
    event_id: str | None = Field(None, max_length=60)
    sport: str | None = Field(None, max_length=40)


class ScenarioArgs(Selection):
    discounts: list[Annotated[float, Field(ge=0.0, le=70.0, allow_inf_nan=False)]] = Field(default_factory=lambda: [30.0, 50.0], min_length=1, max_length=6)
    days: int = Field(14, ge=3, le=60)
    slow_moving_only: bool = False


class ClearanceArgs(Selection):
    limit: int = Field(10, ge=1, le=30)


class PolicyArgs(StrictArgs):
    question: str = Field(min_length=3, max_length=300)
    limit: int = Field(4, ge=1, le=8)


def _repo(ctx: ToolContext) -> InventoryRepository:
    repo = getattr(ctx.services, "inventory", None)
    if repo is None:
        raise ToolError("INVENTORY_UNAVAILABLE", "Inventory intelligence is not available (the AI database is not configured)")
    return repo


async def _guard(call):
    try:
        return await call
    except InventoryUnavailable as exc:
        raise ToolError("INSUFFICIENT_DATA", str(exc)) from None
    except InvalidRequest as exc:
        raise ToolError("INVALID_REQUEST", str(exc)) from None


def _actor(ctx: ToolContext) -> str | None:
    return ctx.principal.customer_hash if ctx.principal else None


async def _product_ids(repo: InventoryRepository, a: Selection) -> list[str]:
    return [s["product_id"] for s in await repo.resolve(sport=a.sport, category=a.category, product=a.product)]


async def get_inventory_overview(ctx: ToolContext, a: NoArgs):
    return await _guard(_repo(ctx).overview())


async def get_inventory(ctx: ToolContext, a: PositionArgs):
    rows = await _repo(ctx).positions(sport=a.sport, category=a.category, product=a.product, limit=a.limit)
    return {"products": rows, "count": len(rows), "data_source": "POC_SYNTHETIC", "note": SOURCE_NOTE}


async def get_inventory_snapshot(ctx: ToolContext, a: PositionArgs):
    rows = await _repo(ctx).positions(sport=a.sport, category=a.category, product=a.product, limit=a.limit)
    return {"as_of": (await _guard(_repo(ctx).run()))["as_of"], "products": rows, "data_source": "POC_SYNTHETIC"}


async def get_inventory_history(ctx: ToolContext, a: ProductArgs):
    return await _guard(_repo(ctx).stock_history(a.product_id, a.days))


async def get_sales_history(ctx: ToolContext, a: SalesArgs):
    return await _guard(_repo(ctx).sales_history(sport=a.sport, category=a.category, days=a.days,
                                                 product_id=(await _product_ids(_repo(ctx), a) or [None])[0] if a.product else None))


async def get_demand_forecast(ctx: ToolContext, a: ForecastArgs):
    repo = _repo(ctx)
    pid = a.product_id or ((await _product_ids(repo, a))[:1] or [None])[0] if (a.product_id or a.product) else None
    if pid:
        f = await _guard(repo.forecast(pid))
        return f
    return await _guard(repo.forecast_aggregate(sport=a.sport, category=a.category))


async def get_stock_risk(ctx: ToolContext, a: RiskArgs):
    repo = _repo(ctx)
    scenario = f"EVENT:{a.event_id}" if a.event_id else "BASELINE"
    rows = await _guard(repo.stock_risks(sport=a.sport, category=a.category, level=a.level, scenario=scenario, within_days=a.within_days, limit=a.limit))
    return {"scenario": scenario, "risks": rows, "count": len(rows), "total_matching": getattr(rows, "total", len(rows)), "data_source": "POC_SYNTHETIC",
            "method": "days of cover from the persisted forecast; risk HIGH when cover < supplier lead time or P(stock-out before replenishment) >= 50%"}


async def get_replenishment_recommendation(ctx: ToolContext, a: ReplenishArgs):
    repo = _repo(ctx)
    scenario = f"EVENT:{a.event_id}" if a.event_id else "BASELINE"
    rows = await _guard(repo.replenishment(sport=a.sport, category=a.category, scenario=scenario, limit=a.limit))
    return {"scenario": scenario, "recommendations": rows, "count": len(rows), "total_matching": getattr(rows, "total", len(rows)), "data_source": "POC_SYNTHETIC",
            "recommendation_status": "AI Recommendation: no purchase order was created.",
            "method": "order quantity = demand over lead time + review period + safety stock - on hand - incoming, rounded to MOQ / case pack"}


async def get_overstock_products(ctx: ToolContext, a: OverstockArgs):
    repo = _repo(ctx)
    rows = await _guard(repo.overstock(sport=a.sport, category=a.category, min_age_days=a.older_than_days, limit=a.limit))
    return {"products": rows, "count": len(rows), "total_matching": getattr(rows, "total", len(rows)), "data_source": "POC_SYNTHETIC",
            "method": "excess = on hand + incoming - 90-day forecast; HIGH overstock = at least 180 days of cover"}


async def get_slow_moving_products(ctx: ToolContext, a: Selection):
    rows = await _guard(_repo(ctx).slow_moving(sport=a.sport, category=a.category))
    return {"products": rows, "count": len(rows), "data_source": "POC_SYNTHETIC"}


async def get_inventory_by_region(ctx: ToolContext, a: RegionArgs):
    return await _guard(_repo(ctx).regions(sport=a.sport, category=a.category, product_id=a.product_id, event_id=a.event_id, excess_only=a.excess_only))


async def get_events(ctx: ToolContext, a: NoArgs):
    events = await _guard(_repo(ctx).events())
    return {"events": events, "data_source": "POC_REFERENCE", "note": "Events are POC reference data, not a real calendar."}


async def get_event_impact(ctx: ToolContext, a: EventArgs):
    repo = _repo(ctx)
    event_id = a.event_id
    if not event_id:
        upcoming = [e for e in await _guard(repo.events()) if e["status"] == "UPCOMING" and (not a.sport or e["sport"].lower() == a.sport.lower())]
        if not upcoming:
            raise ToolError("NO_EVENT", "No upcoming event matches that request")
        event_id = upcoming[0]["id"]
    return await _guard(repo.event_impact(event_id))


async def run_promotion_scenario(ctx: ToolContext, a: ScenarioArgs):
    repo = _repo(ctx)
    pids = await _product_ids(repo, a) if a.product else None
    return await _guard(repo.scenario(product_ids=pids, sport=a.sport, category=a.category, discounts=a.discounts, days=a.days,
                                      health=["Slow Moving", "Critical Aging"] if a.slow_moving_only else None, actor_hash=_actor(ctx)))


async def get_clearance_candidates(ctx: ToolContext, a: ClearanceArgs):
    return await _guard(_repo(ctx).clearance_candidates(sport=a.sport, category=a.category, limit=a.limit, actor_hash=_actor(ctx)))


async def get_forecast_quality(ctx: ToolContext, a: NoArgs):
    repo = _repo(ctx)
    info = await _guard(repo.model_info())
    return {"model": info, "data_quality": await _guard(repo.data_quality())}


async def get_inventory_policy(ctx: ToolContext, a: PolicyArgs):
    retriever = ctx.services.retriever
    if retriever is None:
        raise ToolError("KNOWLEDGE_UNAVAILABLE", "The business knowledge base is not available right now")
    r = await retriever.search_chunks(a.question, limit=a.limit, internal=True)
    chunks = []
    for h in r.hits:
        text, removed = sanitize_untrusted(h.payload["content"], 1500)    # documents are data, never instructions
        chunks.append({"chunk_id": h.id, "source": h.payload["source"], "title": h.payload.get("title"), "page": h.payload["page"],
                       "document_type": h.payload["document_type"], "section": h.payload.get("section"), "text": text,
                       "similarity": round(h.similarity, 4) if h.similarity is not None else None, "flagged_injection": bool(removed)})
    return {"found": bool(chunks), "chunks": chunks, "note": "SAMPLE internal policies written for the POC, not DSports' real policies."}


_D = {
    "get_inventory_overview": "Management summary: counts of healthy / at-risk / overstocked / slow-moving SKUs, inventory value, upcoming events.",
    "get_inventory": "Current stock position per product (on hand, incoming, age, by region, supplier, lead time, MOQ, cost).",
    "get_inventory_snapshot": "Current stock snapshot per product with stock-risk fields.",
    "get_inventory_history": "Daily stock level history of one product.",
    "get_sales_history": "Weekly units sold for a sport / category / product and the change versus the previous period.",
    "get_demand_forecast": "Persisted demand forecast (7/30/90 days, 80% interval, model and version) for a product or a sport/category.",
    "get_stock_risk": "Stock-out risk ranking from days of cover and probability of running out before replenishment, optionally event-adjusted.",
    "get_replenishment_recommendation": "Deterministic replenishment quantities, order-by dates and priority (recommendation only).",
    "get_overstock_products": "Overstocked and aged products with excess units and value.",
    "get_slow_moving_products": "Slow-moving and critically aged products.",
    "get_inventory_by_region": "Regional demand, stock, days of cover and suggested inter-region transfers.",
    "get_events": "Past and upcoming sporting events (POC reference data).",
    "get_event_impact": "Estimated demand uplift of an event by category and per-SKU readiness (stock vs event-adjusted demand).",
    "run_promotion_scenario": "What-if discount scenarios (units, revenue, margin, ending stock, stock-out risk, clearance days). Calculation only.",
    "get_clearance_candidates": "Products suitable for clearance with a recommended discount from the scenario engine (recommendation only).",
    "get_forecast_quality": "Model accuracy metrics (WAPE/MAE/RMSE), interval coverage and the data-quality report.",
    "get_inventory_policy": "Search the internal inventory, replenishment, clearance and event policies (cited passages).",
}


def tools() -> list[Tool]:
    spec = [
        ("get_inventory_overview", NoArgs, get_inventory_overview), ("get_inventory", PositionArgs, get_inventory),
        ("get_inventory_snapshot", PositionArgs, get_inventory_snapshot), ("get_inventory_history", ProductArgs, get_inventory_history),
        ("get_sales_history", SalesArgs, get_sales_history), ("get_demand_forecast", ForecastArgs, get_demand_forecast),
        ("get_stock_risk", RiskArgs, get_stock_risk), ("get_replenishment_recommendation", ReplenishArgs, get_replenishment_recommendation),
        ("get_overstock_products", OverstockArgs, get_overstock_products), ("get_slow_moving_products", Selection, get_slow_moving_products),
        ("get_inventory_by_region", RegionArgs, get_inventory_by_region), ("get_events", NoArgs, get_events),
        ("get_event_impact", EventArgs, get_event_impact), ("run_promotion_scenario", ScenarioArgs, run_promotion_scenario),
        ("get_clearance_candidates", ClearanceArgs, get_clearance_candidates), ("get_forecast_quality", NoArgs, get_forecast_quality),
        ("get_inventory_policy", PolicyArgs, get_inventory_policy),
    ]
    return [Tool(name, _D[name], Kind.READ, args, handler, requires_auth=True, required_role=ADMIN) for name, args, handler in spec]
