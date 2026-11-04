"""Product & Supplier Intelligence tools, registered in the SAME typed registry as every other tool (and so exposed through the same MCP facade).

All are READ tools restricted to the buyer roles by the registry itself, whoever calls (REST agent or MCP). There is deliberately no tool that starts research, records or
verifies a quote, changes a status, approves, contacts a supplier, places an order, publishes a product or sets a price: those are buyer actions in the UI. A tool never asks an
LLM for a number: every figure is read from persisted records or computed by calc.py / scoring.py.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Annotated, Literal

from pydantic import Field

from app.sourcing import calc
from app.sourcing.service import Actor, SourcingError, SourcingService
from app.sourcing.workflow import BUYER_ROLES
from app.tools.dsports_client import ToolError
from app.tools.registry import Kind, StrictArgs, Tool, ToolContext

Status = Literal["DISCOVERED", "UNDER_REVIEW", "SHORTLISTED", "SUPPLIER_RESEARCH", "QUOTE_COMPARISON", "APPROVED_FOR_ONBOARDING", "REJECTED", "ON_HOLD", "NEEDS_MORE_EVIDENCE"]
UUID36 = Annotated[str, Field(min_length=36, max_length=36)]


class NoArgs(StrictArgs):
    pass


class SearchArgs(StrictArgs):
    query: str | None = Field(None, max_length=80)
    sport: str | None = Field(None, max_length=40)
    brand: str | None = Field(None, max_length=60)
    status: Status | None = None
    kind: Literal["new_product", "catalogue_gap"] | None = None
    limit: int = Field(10, ge=1, le=25)


class OppArgs(StrictArgs):
    opportunity_id: UUID36


class DemandArgs(StrictArgs):
    days: int = Field(30, ge=1, le=365)
    min_customers: int = Field(1, ge=1, le=50)
    limit: int = Field(10, ge=1, le=25)


class CompareArgs(StrictArgs):
    opportunity_id: UUID36
    quantity: int = Field(gt=0, le=1_000_000)
    selling_price: float | None = Field(None, gt=0)


class LandedArgs(StrictArgs):
    quantity: int = Field(gt=0, le=1_000_000)
    unit_price: float = Field(gt=0)
    currency: str = Field("INR", min_length=3, max_length=3)
    fx_rate: float | None = Field(None, gt=0, description="1 unit of the quote currency in INR; required when the currency is not INR")
    moq: int | None = Field(None, gt=0)
    freight_total: float = Field(0, ge=0)
    duty_pct: float = Field(0, ge=0, le=300)
    non_recoverable_tax_pct: float = Field(0, ge=0, le=100)
    handling_per_unit: float = Field(0, ge=0)
    packaging_per_unit: float = Field(0, ge=0)
    defect_pct: float = Field(0, ge=0, lt=100)
    selling_price: float | None = Field(None, gt=0)
    price_includes_tax: bool = False
    sales_tax_pct: float = Field(0, ge=0, le=100)
    target_margin_pct: float | None = Field(None, gt=0, lt=100)


def _svc(ctx: ToolContext) -> SourcingService:
    svc = getattr(ctx.services, "sourcing", None)
    if svc is None:
        raise ToolError("SOURCING_UNAVAILABLE", "Product & Supplier Intelligence is not available (the AI database is not configured)")
    return svc


def _actor(ctx: ToolContext) -> Actor:
    return Actor(ctx.principal.customer_hash, list(ctx.principal.roles))     # the registry has already checked authentication and the buyer role


async def _guard(call):
    try:
        return await call
    except SourcingError as exc:
        raise ToolError(exc.code, exc.message) from None


async def get_sourcing_overview(ctx: ToolContext, a: NoArgs):
    return await _guard(_svc(ctx).dashboard())


async def search_product_opportunities(ctx: ToolContext, a: SearchArgs):
    svc = _svc(ctx)
    f = {k: v for k, v in {"q": a.query, "sport": a.sport, "brand": a.brand, "status": [a.status] if a.status else None, "kind": a.kind}.items() if v}
    if not a.kind:
        f["record_kind"] = "PRODUCT_CANDIDATE"                  # a customer demand gap is not a product: it is never offered as one to compare, score or quote
    items, total = await svc.repo.list_opportunities(f, 0, a.limit, "updated")          # live data only: demonstration, test and unclassified records are excluded
    return {"total": total, "opportunities": [{"id": o["id"], "name": o["name"], "brand": o["brand"], "sport": o["sport"], "status": o["status"], "origin": o["origin"],
                                               "provenance": o.get("provenance"), "has_image": bool(o.get("image_url")), "score": o.get("opp_score"), "supplier_risk": o.get("risk"),
                                               "estimated_margin_pct": o.get("margin"), "demo": o["origin"] == "DEMO"} for o in items],
            "note": "Live data only. Scores and margins are estimates from stored calculations; a margin exists only where a supplier quotation was entered. Demonstration and test data are excluded."}


async def get_opportunity_evidence(ctx: ToolContext, a: OppArgs):
    svc = _svc(ctx)
    opp = await _guard(svc._opp(a.opportunity_id))
    ev = await svc.repo.list_evidence(a.opportunity_id)
    quotes = [svc.quote_view(q) for q in await svc.repo.list_quotes(a.opportunity_id)]
    return {"opportunity": {"id": opp["id"], "name": opp["name"], "status": opp["status"], "origin": opp["origin"], "catalogue_match": opp["catalogue_match"]},
            "evidence": [{"id": e["id"], "claim": e["claim"], "source": e["source_label"], "url": e.get("url"), "retrieved_at": e["retrieved_at"], "checked_at": e.get("verified_at")} for e in ev],
            "offers": [{"supplier": q["supplier_name"], "kind": q["label"], "currency": q["currency"], "unit_price": q["unit_price"], "moq": q.get("moq"), "status": q["status"],
                        "warnings": q["warnings"]} for q in quotes],
            "unanswered": opp.get("unanswered") or []}


async def get_opportunity_score(ctx: ToolContext, a: OppArgs):
    return await _guard(_svc(ctx).score(a.opportunity_id, save=False))


async def get_unmet_demand(ctx: ToolContext, a: DemandArgs):
    svc = _svc(ctx)
    groups = await svc.repo.demand_groups(datetime.now(timezone.utc) - timedelta(days=a.days), a.min_customers)
    return {"window_days": a.days, "groups": [{"phrase": g["query_norm"], "sport": g["sport"], "searches": g["searches"], "customers": g["customers"],
                                               "first": g["first_ts"][:10], "last": g["last_ts"][:10]} for g in groups[: a.limit]],
            "note": "Searches that found no product. These show customer interest, not purchase intent. Phrases are redacted; no conversation text or identity is kept."}


async def compare_supplier_offers(ctx: ToolContext, a: CompareArgs):
    return await _guard(_svc(ctx).compare(_actor(ctx), a.opportunity_id, a.quantity, a.selling_price, {}))


async def calculate_landed_cost(ctx: ToolContext, a: LandedArgs):
    svc = _svc(ctx)
    raw = a.model_dump(exclude_none=True)
    raw["base_currency"] = svc.settings.sourcing_base_currency
    raw["currency"] = raw["currency"].upper()
    if raw["currency"] != raw["base_currency"] and "fx_rate" not in raw:
        fx = svc.fx(raw["currency"])
        if fx:
            raw.update({"fx_rate": fx["rate"], "fx_source": fx["source"], "fx_as_of": fx["as_of"]})
    return calc.compute(calc.parse_inputs(raw)).as_dict()


_D = {
    "get_sourcing_overview": "Counts of product opportunities by status and origin, recent research runs and which sources are connected. Estimates are labelled; demo data is separate.",
    "search_product_opportunities": "Find product opportunities (new products and catalogue gaps) by name, sport, brand, status or type. Returns ids to use with the other sourcing tools.",
    "get_opportunity_evidence": "The evidence behind one opportunity (claims with source, URL and check date), its supplier offers with their source labels, and what is still unanswered.",
    "get_opportunity_score": "The explainable opportunity score of one opportunity: dimensions with weights, evidence confidence, supplier risk and what is missing. May say INSUFFICIENT_EVIDENCE.",
    "get_unmet_demand": "Customer searches that found no product, grouped and anonymised, with distinct-customer counts. Interest, not purchase intent.",
    "compare_supplier_offers": "Compare the recorded supplier offers for an opportunity at one order quantity: landed cost per sellable unit, margin at a selling price, rank, warnings.",
    "calculate_landed_cost": "Deterministic landed cost, gross margin, markup, break-even and target-margin price from the given inputs. Missing inputs give INCOMPLETE, never a guess.",
}


def tools() -> list[Tool]:
    spec = [("get_sourcing_overview", NoArgs, get_sourcing_overview), ("search_product_opportunities", SearchArgs, search_product_opportunities),
            ("get_opportunity_evidence", OppArgs, get_opportunity_evidence), ("get_opportunity_score", OppArgs, get_opportunity_score), ("get_unmet_demand", DemandArgs, get_unmet_demand),
            ("compare_supplier_offers", CompareArgs, compare_supplier_offers), ("calculate_landed_cost", LandedArgs, calculate_landed_cost)]
    return [Tool(name, _D[name], Kind.READ, args, handler, requires_auth=True, required_any_role=tuple(sorted(BUYER_ROLES))) for name, args, handler in spec]
