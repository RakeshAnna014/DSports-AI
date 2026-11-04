"""Product & Supplier Intelligence REST API (internal). Reading needs a buyer role (PROCUREMENT_BUYER, CATEGORY_MANAGER or ADMIN); approving and reopening decisions need an approver
(CATEGORY_MANAGER or ADMIN). The frontend only hides the pages: these dependencies, and the checks again inside the service, are the enforcement. Denied attempts are audited.
Nothing here places an order, contacts a supplier, publishes a product or changes a price."""
from __future__ import annotations

import logging
from datetime import date, datetime
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.auth.jwt import Principal, current_principal
from app.errors import ApiException
from app.obs import metrics
from app.obs.context import correlation_id_var
from app.sourcing import calc, workflow
from app.sourcing.repository import PROVENANCES, SourcingRepository
from app.sourcing.service import Actor, SourcingError, SourcingService

log = logging.getLogger(__name__)
router = APIRouter(prefix="/v1/sourcing", tags=["product-supplier-intelligence"])
TYPES = "^(MANUFACTURER|AUTHORISED_DISTRIBUTOR|WHOLESALER|REGIONAL_DISTRIBUTOR|IMPORTER|DIRECT_SUPPLIER)$"


async def buyer(request: Request, principal: Principal = Depends(current_principal)) -> Actor:
    if not workflow.is_buyer(principal.roles):
        svc = getattr(request.app.state.services, "sourcing", None)
        if svc is not None:
            try:
                await svc.repo.add_decision(None, principal.customer_hash, "DENIED", detail={"path": request.url.path[:120]})
            except Exception:  # noqa: BLE001
                log.warning("could not audit a denied sourcing request", exc_info=True)
        metrics.GUARD_BLOCKS.labels("sourcing", "forbidden").inc()
        raise ApiException(403, "FORBIDDEN", "Product & Supplier Intelligence is restricted to buyers and category managers")
    return Actor(principal.customer_hash, list(principal.roles))


def service(request: Request, actor: Actor = Depends(buyer)) -> SourcingService:
    """Depends on `buyer`, so the role check always runs BEFORE anything about the service's state is revealed."""
    svc = getattr(request.app.state.services, "sourcing", None)
    if svc is None:
        raise ApiException(503, "SOURCING_UNAVAILABLE", "Product & Supplier Intelligence needs the AI database, which is not configured or reachable")
    return svc


async def run(coro):
    try:
        return await coro
    except SourcingError as exc:
        raise ApiException(exc.status, exc.code, exc.message) from None
    except ValueError as exc:                    # malformed UUID and similar
        raise ApiException(422, "INVALID_REQUEST", str(exc)) from None


class ResearchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    sport: str | None = Field(None, max_length=40)
    brand: str | None = Field(None, max_length=60)
    category: str | None = Field(None, max_length=60)
    keywords: list[Annotated[str, Field(min_length=2, max_length=40)]] = Field(default_factory=list, max_length=10)
    lookback_days: int | None = Field(None, ge=1, le=365)
    wait: bool = False                      # true: answer when the run has finished (tests, scripts); default: answer at once and let the run report its progress


class OpportunityCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=2, max_length=200)
    brand: str | None = Field(None, max_length=80)
    model: str | None = Field(None, max_length=80)
    gtin: str | None = Field(None, max_length=20)
    manufacturer: str | None = Field(None, max_length=100)
    sport: str | None = Field(None, max_length=40)
    category: str | None = Field(None, max_length=80)
    description: str | None = Field(None, max_length=1500)
    relevance: str | None = Field(None, max_length=500)
    selling_price: float | None = Field(None, gt=0)
    mfr_sku: str | None = Field(None, max_length=60)
    product_url: str | None = Field(None, max_length=500, pattern=r"^https?://")
    is_test: bool = False                   # automated tests/scripts: stored as TEST_FIXTURE and left out of live metrics (needs SOURCING_ENABLE_DEMO)


class OpportunityPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    selling_price: float | None = Field(None, gt=0)
    trend_note: str | None = Field(None, max_length=500)
    unanswered: list[str] | None = Field(None, max_length=20)
    expected_version: int


class StatusChange(BaseModel):
    model_config = ConfigDict(extra="forbid")
    status: str
    reason: str | None = Field(None, max_length=1000)
    expected_version: int
    quote_id: str | None = None             # when approving for onboarding preparation: the offer the buyer chose


class ImageIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: str = Field(min_length=8, max_length=800, pattern=r"^https?://")
    match_level: str = Field("UNVERIFIED", pattern="^(EXACT_MODEL|LIKELY_MATCH|SIMILAR_REFERENCE|UNVERIFIED)$")
    note: str | None = Field(None, max_length=300)


class ReportIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    reason: str = Field(min_length=3, max_length=300)


class ProvenanceIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    provenance: str = Field(pattern="^(BUYER_ENTERED|CUSTOMER_DEMAND_SIGNAL|DEMO_FIXTURE|TEST_FIXTURE|UNKNOWN_PROVENANCE)$")
    reason: str = Field(min_length=3, max_length=500)


class StageIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    stage: str = Field(pattern="^(DISCOVERED|WEBSITE_CHECKED|IDENTITY_VERIFIED|BRAND_AUTHORISATION_VERIFIED)$")
    note: str | None = Field(None, max_length=500)
    evidence_url: str | None = Field(None, max_length=500, pattern=r"^https?://")


class LinkIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    candidate_id: str


class NoteIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    text: str = Field(min_length=1, max_length=2000)


class SupplierIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=2, max_length=120)
    type: str = Field(pattern=TYPES)
    website: str | None = Field(None, max_length=300)
    contact: dict[str, str] = Field(default_factory=dict)
    country: str | None = Field(None, max_length=60)
    region: str | None = Field(None, max_length=80)
    categories: list[str] = Field(default_factory=list, max_length=20)
    brands: list[str] = Field(default_factory=list, max_length=30)
    authorised_brands: list[str] = Field(default_factory=list, max_length=30)
    authorisation_note: str | None = Field(None, max_length=500)
    is_test: bool = False                   # automated tests/scripts: stored as TEST_FIXTURE and left out of live supplier lists


class SupplierPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    website: str | None = Field(None, max_length=300)
    contact: dict[str, str] | None = None
    country: str | None = None
    region: str | None = None
    categories: list[str] | None = None
    brands: list[str] | None = None
    authorised_brands: list[str] | None = None
    authorisation_note: str | None = None


class PriceBreakIn(BaseModel):
    min_quantity: int = Field(gt=0)
    unit_price: float = Field(gt=0)


class QuoteIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    supplier_id: str
    equivalence: str = Field("IDENTICAL", pattern="^(IDENTICAL|EQUIVALENT_ALTERNATIVE)$")
    equivalence_note: str | None = Field(None, max_length=500)
    offered_product: str = Field(min_length=2, max_length=200)
    model: str | None = Field(None, max_length=80)
    currency: str = Field(min_length=3, max_length=3)
    unit_price: float = Field(gt=0)
    moq: int | None = Field(None, gt=0)
    price_breaks: list[PriceBreakIn] = Field(default_factory=list, max_length=12)
    shipping_cost: float | None = Field(None, ge=0)
    shipping_note: str | None = Field(None, max_length=200)
    other_costs: dict[str, float] = Field(default_factory=dict)
    lead_time_days: int | None = Field(None, ge=0, le=730)
    availability: str | None = Field(None, max_length=200)
    warranty: str | None = Field(None, max_length=300)
    return_terms: str | None = Field(None, max_length=300)
    certifications: str | None = Field(None, max_length=300)
    payment_terms: str | None = Field(None, max_length=200)
    quoted_on: date | None = None
    valid_until: date | None = None
    source_kind: str = Field("QUOTE_ENTERED", pattern="^(PUBLIC_LISTING|QUOTE_ENTERED|VERIFIED|AI_ESTIMATE)$")
    source_url: str | None = Field(None, max_length=500, pattern=r"^https?://")
    reference: str | None = Field(None, max_length=80)


    @model_validator(mode="after")
    def _dates_in_order(self) -> "QuoteIn":
        if self.quoted_on and self.valid_until and self.valid_until < self.quoted_on:
            raise ValueError("valid_until cannot be before quoted_on")
        return self


class QuotePatch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    unit_price: float | None = Field(None, gt=0)
    moq: int | None = Field(None, gt=0)
    price_breaks: list[PriceBreakIn] | None = None
    shipping_cost: float | None = Field(None, ge=0)
    lead_time_days: int | None = Field(None, ge=0, le=730)
    availability: str | None = None
    warranty: str | None = None
    return_terms: str | None = None
    certifications: str | None = None
    payment_terms: str | None = None
    quoted_on: date | None = None
    valid_until: date | None = None
    reference: str | None = None


class Assumptions(BaseModel):
    model_config = ConfigDict(extra="forbid")
    fx_rate: float | None = Field(None, gt=0)
    fx_source: str | None = Field(None, max_length=120)
    fx_as_of: str | None = Field(None, max_length=20)
    freight_total: float | None = Field(None, ge=0)
    duty_pct: float | None = Field(None, ge=0, le=300)
    non_recoverable_tax_pct: float | None = Field(None, ge=0, le=100)
    handling_per_unit: float | None = Field(None, ge=0)
    packaging_per_unit: float | None = Field(None, ge=0)
    other_fixed_costs: float | None = Field(None, ge=0)
    defect_pct: float | None = Field(None, ge=0, lt=100)
    price_includes_tax: bool | None = None
    sales_tax_pct: float | None = Field(None, ge=0, le=100)
    variable_cost_per_unit: float | None = Field(None, ge=0)
    variable_cost_pct: float | None = Field(None, ge=0, lt=100)
    target_margin_pct: float | None = Field(None, gt=0, lt=100)


class CalcRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    quote_id: str | None = None
    quantity: int | None = Field(None, gt=0)
    selling_price: float | None = Field(None, gt=0)
    assumptions: Assumptions = Field(default_factory=Assumptions)
    manual: dict[str, Any] | None = None
    quantities: list[int] | None = Field(None, max_length=12)
    sensitivity: bool = True
    save: bool = False


class CompareRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    quantity: int = Field(gt=0)
    selling_price: float | None = Field(None, gt=0)
    assumptions: Assumptions = Field(default_factory=Assumptions)


class ProposalRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    quote_id: str
    quantity: int = Field(gt=0)
    selling_price: float | None = Field(None, gt=0)
    target_margin_pct: float | None = Field(None, gt=0, lt=100)
    image_exception: str | None = Field(None, max_length=300)      # an explicit, recorded reason why no product image is needed


def dump(m: BaseModel) -> dict[str, Any]:
    return m.model_dump(exclude_unset=True, mode="json")


# ------------------------------------------------------------------------------------------------ dashboard and runs
@router.get("/dashboard")
async def dashboard(s: SourcingService = Depends(service)):
    return await run(s.dashboard())


@router.get("/sources")
async def sources(s: SourcingService = Depends(service)):
    return s.sources_status()


@router.post("/runs", status_code=202)
async def start_run(body: ResearchRequest, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    d = dump(body)
    wait = bool(d.pop("wait", False))
    result = await run(s.start_research(actor, d, wait=wait))
    await s.repo.add_decision(None, actor.hash, "RESEARCH_RUN", detail={"run_id": result.get("id"), "status": result.get("status")})
    return result


@router.get("/runs")
async def list_runs(limit: int = Query(20, ge=1, le=100), s: SourcingService = Depends(service)):
    return {"runs": await s.repo.list_runs(limit)}


@router.get("/runs/{run_id}")
async def get_run(run_id: str, s: SourcingService = Depends(service)):
    r = await run(s.repo.get_run(run_id))
    if r is None:
        raise ApiException(404, "NOT_FOUND", "Run not found")
    return r


@router.get("/runs/{run_id}/results")
async def run_results(run_id: str, s: SourcingService = Depends(service)):
    """What a run found: the products whose source records were retrieved by this run (with their evidence), so a buyer can open each result."""
    r = await run(s.repo.get_run(run_id))
    if r is None:
        raise ApiException(404, "NOT_FOUND", "Run not found")
    return {"run": r, "products": await s.repo.run_products(run_id)}


# ------------------------------------------------------------------------------------------------ opportunities
@router.get("/opportunities")
async def list_opportunities(s: SourcingService = Depends(service), status: Annotated[list[str] | None, Query()] = None, sport: str | None = None, category: str | None = None,
                             brand: str | None = None, product_type: str | None = None, origin: str | None = Query(None, pattern="^(LIVE_RESEARCH|DEMAND_SIGNAL|MANUAL|DEMO)$"),
                             provenance: Annotated[list[str] | None, Query()] = None, include_fixtures: bool = False,
                             record_kind: str | None = Query(None, pattern="^(PRODUCT_CANDIDATE|DEMAND_GAP|ALL)$"), kind: str | None = Query(None, pattern="^(new_product|catalogue_gap)$"),
                             q: str | None = Query(None, max_length=80), evidence: str | None = Query(None, pattern="^(fresh|stale)$"), has_supplier: bool | None = None,
                             has_image: bool | None = None, image_match: str | None = Query(None, pattern="^(EXACT_MODEL|LIKELY_MATCH|SIMILAR_REFERENCE|UNVERIFIED)$"),
                             catalogue_match: str | None = Query(None, pattern="^(EXACT|PROBABLE_DUPLICATE|SIMILAR|REVIEW|NONE)$"),
                             supplier_verification: str | None = Query(None, pattern="^(DISCOVERED|WEBSITE_CHECKED|IDENTITY_VERIFIED|BRAND_AUTHORISATION_VERIFIED)$"), has_margin: bool | None = None,
                             risk: str | None = Query(None, pattern="^(LOW|MEDIUM|HIGH|UNKNOWN)$"), min_margin: float | None = None, max_margin: float | None = None, min_score: float | None = None,
                             confidence: str | None = Query(None, pattern="^(LOW|MEDIUM|HIGH)$"), demand: str | None = Query(None, pattern="^(STRONG|MODERATE|WEAK)$"),
                             researched_from: datetime | None = None, researched_to: datetime | None = None,
                             sort: str = Query("updated", pattern="^(updated|first_seen|name|score|margin|last_seen)$"), page: int = Query(0, ge=0, le=10000), size: int = Query(20, ge=1, le=100)):
    for p in provenance or []:
        if p not in PROVENANCES:
            raise ApiException(422, "INVALID_PROVENANCE", f"provenance must be one of {', '.join(PROVENANCES)}")
    f = {"status": status, "sport": sport, "category": category, "brand": brand, "product_type": product_type, "origin": origin, "provenance": provenance, "include_fixtures": include_fixtures,
         "record_kind": record_kind or ("PRODUCT_CANDIDATE" if not kind else None), "kind": kind, "q": q, "evidence": evidence, "has_supplier": has_supplier, "has_image": has_image,
         "image_match": image_match, "catalogue_match": catalogue_match, "supplier_verification": supplier_verification, "has_margin": has_margin, "risk": risk, "min_margin": min_margin,
         "max_margin": max_margin, "min_score": min_score, "confidence": confidence, "demand": demand, "researched_from": researched_from, "researched_to": researched_to}
    items, total = await run(s.repo.list_opportunities({k: v for k, v in f.items() if v is not None and v is not False}, page, size, sort))
    return {"items": items, "total": total, "page": page, "size": size,
            "scope": "all provenance" if provenance else ("including demonstration/test fixtures and unreviewed records" if include_fixtures else "live data only")}


@router.post("/opportunities", status_code=201)
async def create_opportunity(body: OpportunityCreate, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    from app.sourcing import normalize
    d = dump(body)
    is_test = bool(d.pop("is_test", False))
    if is_test and not s.settings.sourcing_enable_demo:
        raise ApiException(403, "DEMO_DISABLED", "Test fixtures are disabled (SOURCING_ENABLE_DEMO)")
    d["brand"] = normalize.display_brand(d.get("brand")) if d.get("brand") else None
    key = normalize.identity_key(d["name"], d.get("brand"), d.get("model"), d.get("gtin"), d.get("mfr_sku"))
    if await s.repo.find_by_identity(key, "MANUAL"):
        raise ApiException(409, "DUPLICATE", "An opportunity for this product already exists")
    try:
        catalogue = await s.catalogue_rows()
    except Exception:  # noqa: BLE001
        catalogue = []
    match = normalize.match_catalogue({**d, "sku": d.get("mfr_sku")}, catalogue)
    _, possible = normalize.find_duplicates(d, await s.repo.existing_identities())
    opp = await s.repo.create_opportunity({**d, "identity_key": key, "gtin": normalize.valid_gtin(d.get("gtin")), "catalogue_match": match.__dict__, "possible_dupes": possible,
                                           "last_verified": datetime.now().astimezone(), "source_title": d["name"], "model_name": d.get("model")},
                                          "MANUAL", actor.hash, provenance="TEST_FIXTURE" if is_test else "BUYER_ENTERED")
    await s.repo.add_decision(opp["id"], actor.hash, "DISCOVERED", to_status="DISCOVERED", detail={"source": "entered by a buyer"})
    await s.repo.add_evidence(opp["id"], {"kind": "BUYER_NOTE", "source_label": "Entered by a buyer", "claim": f"A buyer entered this product: {d['name']}" + (f" ({d['relevance']})" if d.get("relevance") else ""),
                                          "source_type": "BUYER_NOTE", "verified_at": datetime.now().astimezone()})
    return opp


PROVENANCE_LABEL = {"LIVE_EXTERNAL_SOURCE": "Found by a configured research source (source page linked as evidence)", "BUYER_ENTERED": "Entered by a buyer",
                    "CUSTOMER_DEMAND_SIGNAL": "Derived from customer searches (interest only, not a product)", "DEMO_FIXTURE": "DEMO DATA: not real research",
                    "TEST_FIXTURE": "TEST DATA: created by an automated test", "UNKNOWN_PROVENANCE": "Unknown provenance: not reviewed, excluded from live metrics"}


@router.get("/opportunities/{opp_id}")
async def get_opportunity(opp_id: str, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    from app.sourcing import normalize, specs as spec_mod
    opp = await run(s._opp(opp_id))
    quotes = [s.quote_view(q) for q in await s.repo.list_quotes(opp_id)]
    score = await s.repo.latest_score(opp_id)
    images = await s.repo.list_images(opp_id)
    family = spec_mod.family_of(opp.get("category"), opp.get("name"))
    brand = normalize.normalize_brand(opp.get("brand")) if opp.get("brand") else None
    possible_suppliers = []
    if brand and opp.get("record_kind") != "DEMAND_GAP":
        suppliers, _ = await s.repo.list_suppliers(None, None, None, 0, 50, provenance=["LIVE_EXTERNAL_SOURCE", "BUYER_ENTERED"])
        quoted = {q["supplier_id"] for q in quotes}
        possible_suppliers = [{"id": x["id"], "name": x["name"], "type": x["type"], "country": x.get("country"), "verification_status": x["verification_status"], "discovery_url": x.get("discovery_url"),
                               "discovery_note": x.get("discovery_note")} for x in suppliers if x["id"] not in quoted and brand in [normalize.normalize_brand(b) for b in x.get("brands") or []]]
    return {"opportunity": opp, "evidence": await s.repo.list_evidence(opp_id), "quotes": quotes, "score": score["result"] if score else None,
            "score_at": score["created_at"] if score else None, "calculations": await s.repo.list_calcs(opp_id, 10), "history": await s.repo.list_decisions(opp_id),
            "proposals": await s.repo.list_proposals(opp_id), "allowed_next": workflow.allowed_next(opp["status"], actor.roles, opp.get("record_kind") or "PRODUCT_CANDIDATE"),
            "images": images, "specifications": spec_mod.flat(opp.get("specs") or {}), "missing_specifications": spec_mod.missing_specs(family, opp.get("specs") or {}),
            "readiness": await s.readiness(opp_id), "possible_suppliers": possible_suppliers,
            "provenance_label": PROVENANCE_LABEL.get(opp["provenance"], opp["provenance"]), "is_fixture": opp["provenance"] in ("DEMO_FIXTURE", "TEST_FIXTURE"),
            "origin_label": {"DEMO": "DEMO DATA — NOT REAL RESEARCH", "DEMAND_SIGNAL": "From customer searches (interest, not purchase intent)", "LIVE_RESEARCH": "From a configured source",
                             "MANUAL": "Entered by a buyer"}[opp["origin"]]}


@router.get("/opportunities/{opp_id}/readiness")
async def get_readiness(opp_id: str, quote_id: str | None = None, quantity: int | None = Query(None, gt=0), selling_price: float | None = Query(None, gt=0),
                        image_exception: str | None = Query(None, max_length=300), s: SourcingService = Depends(service)):
    """Is this product's record complete enough for the catalogue team? Independent of the decision status."""
    return await run(s.readiness(opp_id, quote_id, quantity, selling_price, image_exception))


@router.patch("/opportunities/{opp_id}")
async def patch_opportunity(opp_id: str, body: OpportunityPatch, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    d = dump(body)
    version = d.pop("expected_version")
    await run(s._opp(opp_id))
    out = await s.repo.update_opportunity(opp_id, d, version)
    if out is None:
        raise ApiException(409, "VERSION_CONFLICT", "Someone else changed this opportunity. Reload it and try again.")
    await s.repo.add_decision(opp_id, actor.hash, "UPDATED", detail={"fields": sorted(d)})
    return out


@router.post("/opportunities/{opp_id}/status")
async def change_status(opp_id: str, body: StatusChange, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    return await run(s.change_status(actor, opp_id, body.status, body.reason, body.expected_version, body.quote_id))


@router.post("/opportunities/{opp_id}/provenance")
async def classify(opp_id: str, body: ProvenanceIn, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    """A buyer reviews where a record came from (for example marks legacy rows as test data, or confirms an unknown record was buyer-entered). Audited."""
    return await run(s.classify(actor, opp_id, body.provenance, body.reason))


@router.post("/gaps/{gap_id}/link")
async def link_gap(gap_id: str, body: LinkIn, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    """Link a customer-demand gap to a REAL product candidate. Only a buyer does this, and only to a product record (never to another gap)."""
    gap = await run(s._opp(gap_id))
    cand = await run(s._opp(body.candidate_id))
    if gap.get("record_kind") != "DEMAND_GAP" or cand.get("record_kind") == "DEMAND_GAP":
        raise ApiException(422, "INVALID_LINK", "Link a demand gap to a product candidate")
    out = await s.repo.link_gap(gap_id, body.candidate_id)
    await s.repo.add_decision(gap_id, actor.hash, "GAP_LINKED", detail={"candidate_id": body.candidate_id, "candidate": cand["name"]})
    await s.repo.add_decision(body.candidate_id, actor.hash, "GAP_LINKED", detail={"gap_id": gap_id, "gap": gap["name"]})
    return out


@router.post("/opportunities/{opp_id}/images", status_code=201)
async def add_image(opp_id: str, body: ImageIn, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    return await run(s.add_image(actor, opp_id, body.url, body.match_level, body.note))


@router.post("/opportunities/{opp_id}/images/{image_id}/primary")
async def primary_image(opp_id: str, image_id: str, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    await run(s.set_primary_image(actor, opp_id, image_id))
    return {"ok": True}


@router.post("/images/{image_id}/report")
async def report_image(image_id: str, body: ReportIn, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    """Report an image that does not show this product: it is hidden from cards and the proposal, and the report is audited."""
    return await run(s.report_image(actor, image_id, body.reason))


@router.post("/images/{image_id}/recheck")
async def recheck_image(image_id: str, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    return await run(s.recheck_image(actor, image_id))


@router.post("/opportunities/{opp_id}/notes", status_code=201)
async def add_note(opp_id: str, body: NoteIn, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    await run(s.add_note(actor, opp_id, body.text))
    return {"ok": True}


@router.post("/opportunities/{opp_id}/score")
async def rescore(opp_id: str, s: SourcingService = Depends(service)):
    return await run(s.score(opp_id))


# ------------------------------------------------------------------------------------------------ suppliers and quotes
@router.get("/suppliers")
async def list_suppliers(s: SourcingService = Depends(service), q: str | None = Query(None, max_length=80), type: str | None = Query(None, pattern=TYPES),
                         origin: str | None = Query(None, pattern="^(LIVE_RESEARCH|MANUAL|DEMO)$"), provenance: Annotated[list[str] | None, Query()] = None, include_fixtures: bool = False,
                         stage: str | None = Query(None, pattern="^(DISCOVERED|WEBSITE_CHECKED|IDENTITY_VERIFIED|BRAND_AUTHORISATION_VERIFIED)$"),
                         page: int = Query(0, ge=0), size: int = Query(20, ge=1, le=100)):
    for p in provenance or []:
        if p not in PROVENANCES:
            raise ApiException(422, "INVALID_PROVENANCE", f"provenance must be one of {', '.join(PROVENANCES)}")
    items, total = await s.repo.list_suppliers(q, type, origin, page, size, provenance=provenance, include_fixtures=include_fixtures, stage=stage)
    return {"items": items, "total": total, "page": page, "size": size, "scope": "live data only" if not (provenance or include_fixtures) else "including fixtures"}


@router.post("/suppliers", status_code=201)
async def create_supplier(body: SupplierIn, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    d = dump(body)
    is_test = bool(d.pop("is_test", False))
    if is_test and not s.settings.sourcing_enable_demo:
        raise ApiException(403, "DEMO_DISABLED", "Test fixtures are disabled (SOURCING_ENABLE_DEMO)")
    if await s.repo.find_supplier(body.name, "MANUAL"):
        raise ApiException(409, "DUPLICATE", "A supplier with this name already exists")
    sup = await s.repo.create_supplier(d, "MANUAL", actor.hash, provenance="TEST_FIXTURE" if is_test else "BUYER_ENTERED")
    await s.repo.add_decision(None, actor.hash, "SUPPLIER_ADDED", detail={"supplier_id": sup["id"]})
    return sup


@router.get("/suppliers/{supplier_id}")
async def get_supplier(supplier_id: str, s: SourcingService = Depends(service)):
    sup = await run(s.repo.get_supplier(supplier_id))
    if sup is None:
        raise ApiException(404, "NOT_FOUND", "Supplier not found")
    offers = await s.repo.supplier_offers(supplier_id)
    return {"supplier": sup, "offers": [s.quote_view(o) for o in offers], "provenance_label": PROVENANCE_LABEL.get(sup["provenance"], sup["provenance"]),
            "ladder": list(s.STAGES), "is_fixture": sup["provenance"] in ("DEMO_FIXTURE", "TEST_FIXTURE")}


@router.patch("/suppliers/{supplier_id}")
async def patch_supplier(supplier_id: str, body: SupplierPatch, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    d = dump(body)
    out = await run(s.repo.update_supplier(supplier_id, d))
    if out is None:
        raise ApiException(404, "NOT_FOUND", "Supplier not found")
    await s.repo.add_decision(None, actor.hash, "SUPPLIER_UPDATED", detail={"supplier_id": supplier_id, "fields": sorted(d)})
    return out


@router.post("/suppliers/{supplier_id}/stage")
async def supplier_stage(supplier_id: str, body: StageIn, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    """Move a supplier one rung up (or down) the verification ladder with evidence. Identity and brand-authorisation verification need an approver."""
    out = await run(s.set_supplier_stage(actor, supplier_id, body.stage, body.note, body.evidence_url))
    await s.repo.add_decision(None, actor.hash, "SUPPLIER_STAGE", reason=(body.note or "")[:300] or None, detail={"supplier_id": supplier_id, "stage": body.stage})
    return out


@router.post("/opportunities/{opp_id}/quotes", status_code=201)
async def add_quote(opp_id: str, body: QuoteIn, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    d = dump(body)
    d["currency"] = d["currency"].upper()
    return await run(s.add_quote(actor, opp_id, d))


@router.patch("/quotes/{quote_id}")
async def patch_quote(quote_id: str, body: QuotePatch, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    return await run(s.update_quote(actor, quote_id, dump(body)))


@router.post("/quotes/{quote_id}/verify")
async def verify_quote(quote_id: str, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    return await run(s.verify_quote(actor, quote_id))


# ------------------------------------------------------------------------------------------------ calculations
@router.post("/opportunities/{opp_id}/calculate")
async def calculate(opp_id: str, body: CalcRequest, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    payload = body.model_dump(exclude_none=True, mode="json")
    payload["assumptions"] = {k: v for k, v in payload.get("assumptions", {}).items() if v is not None}
    return await run(s.calculate(actor, opp_id, payload, body.save))


@router.post("/opportunities/{opp_id}/compare")
async def compare(opp_id: str, body: CompareRequest, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    a = {k: v for k, v in body.assumptions.model_dump(mode="json").items() if v is not None}
    return await run(s.compare(actor, opp_id, body.quantity, body.selling_price, a))


@router.post("/calculator")
async def stateless_calculator(body: dict[str, Any], actor: Actor = Depends(buyer)):
    """The same deterministic calculator without an opportunity (for a quick what-if). Nothing is saved."""
    try:
        res = calc.compute(calc.parse_inputs(body))
    except (ValueError, TypeError, KeyError) as exc:
        raise ApiException(422, "INVALID_INPUT", str(exc)) from None
    return res.as_dict()


@router.post("/opportunities/{opp_id}/proposal", status_code=201)
async def prepare_proposal(opp_id: str, body: ProposalRequest, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    return await run(s.build_proposal(actor, opp_id, dump(body)))


@router.get("/proposals")
async def list_proposals(s: SourcingService = Depends(service)):
    return {"proposals": await s.repo.list_proposals()}


@router.post("/proposals/{proposal_id}/review")
async def review_proposal(proposal_id: str, actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    if not workflow.is_approver(actor.roles):
        raise ApiException(403, "FORBIDDEN", "Only an approver can mark a proposal as reviewed")
    current = await run(s.repo.get_proposal(proposal_id))
    if current is None:
        raise ApiException(404, "NOT_FOUND", "Proposal not found")
    if current["status"] != "READY_FOR_REVIEW" or not (current.get("validation") or {}).get("ready"):
        missing = ", ".join((current.get("validation") or {}).get("missing_mandatory") or []) or "required information"
        raise ApiException(409, "NOT_READY", f"This proposal is not ready for catalogue review: still needed: {missing}. Complete the record and prepare a new proposal.")
    out = await run(s.repo.set_proposal_status(proposal_id, "REVIEWED"))
    await s.repo.add_decision(out["opportunity_id"], actor.hash, "PROPOSAL_REVIEWED", detail={"proposal_id": proposal_id})
    return out


@router.post("/demo/seed", status_code=201)
async def seed_demo(actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    """Creates clearly labelled DEMO rows (never real research or quotes). Idempotent."""
    return await run(s.seed_demo(actor))


class AskRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    question: str = Field(min_length=3, max_length=400)


@router.post("/ask")
async def ask(body: AskRequest, request: Request, principal: Principal = Depends(current_principal), actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    """Plain-language questions, answered from the typed sourcing tools (role-gated by the tool registry) and worded from their results. No model supplies a fact or number."""
    from app.sourcing import ask as asker
    from app.tools.registry import ToolContext
    services = request.app.state.services
    ctx = ToolContext(principal=principal, dsports=services.dsports, settings=services.settings, services=services, max_calls=50)
    result = await asker.answer(services.registry, ctx, body.question)
    await s.repo.add_decision(None, actor.hash, "ASK", detail={"intent": result["intent"], "question_chars": len(body.question)})
    return result


@router.get("/queue")
async def approval_queue(actor: Actor = Depends(buyer), s: SourcingService = Depends(service)):
    """Shortlisted and in-progress opportunities plus those waiting for an approval decision."""
    items, total = await s.repo.list_opportunities({"status": ["SHORTLISTED", "SUPPLIER_RESEARCH", "QUOTE_COMPARISON", "NEEDS_MORE_EVIDENCE", "APPROVED_FOR_ONBOARDING"]}, 0, 100, "updated")
    return {"items": items, "total": total, "can_approve": workflow.is_approver(actor.roles)}
