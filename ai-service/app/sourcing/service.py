"""Product & Supplier Intelligence orchestration: research runs, catalogue matching, demand signals, quotes, calculations, scoring, workflow and onboarding proposals.

All money and scores come from calc.py / scoring.py (deterministic). External content is evidence, never instruction. Nothing here can place an order, contact a supplier,
publish a product or change a price: the AI service has no such operation, and approval only records a buyer's decision.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from typing import Any

from app.config.settings import Settings
from app.sourcing import calc, normalize, scoring, workflow
from app.sourcing.fetch import SafeFetcher
from app.sourcing.providers import FeedProvider, build_providers
from app.sourcing import images as img_mod
from app.sourcing import readiness as readiness_mod
from app.sourcing import specs as spec_mod
from app.sourcing.repository import FIXTURE_PROVENANCES, LIVE_PROVENANCES, PROVENANCES, SourcingRepository
from app.sourcing.research import ResearchRunner
from app.sourcing.typed_providers import build_typed_providers
from app.sourcing.workflow import WorkflowError

log = logging.getLogger(__name__)
PRESENT = ("PUBLIC_LISTING", "QUOTE_ENTERED", "VERIFIED", "AI_ESTIMATE")
SOURCE_LABEL = {"PUBLIC_LISTING": "Publicly listed", "QUOTE_ENTERED": "Entered from a supplier quotation", "VERIFIED": "Verified by a buyer", "AI_ESTIMATE": "AI estimate (unconfirmed)"}


class SourcingError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


def _now() -> datetime:
    return datetime.now(timezone.utc)


def title(text: str) -> str:
    return " ".join(w.capitalize() for w in text.split())


@dataclass
class Actor:
    hash: str
    roles: list[str]


class SourcingService:
    def __init__(self, repo: SourcingRepository, settings: Settings, catalog: Any, providers: list[FeedProvider] | None = None, problems: list[str] | None = None,
                 typed_providers: list[Any] | None = None, fetcher: SafeFetcher | None = None):
        self.repo, self.settings, self.catalog = repo, settings, catalog
        hosts = [h.strip().lower() for h in settings.sourcing_allowed_hosts.split(",") if h.strip()]
        image_hosts = [h.strip().lower() for h in settings.sourcing_image_hosts.split(",") if h.strip()]
        self.problems_from_config: list[str] = []
        if providers is None:
            providers, problems = build_providers(settings.sourcing_feeds, hosts, settings.sourcing_timeout_s, settings.sourcing_max_bytes)
        self.providers, self.config_problems = providers, list(problems or [])
        self.fetcher = fetcher or SafeFetcher(list(dict.fromkeys(hosts + image_hosts)), timeout_s=max(settings.sourcing_timeout_s, 15.0), max_bytes=min(settings.sourcing_max_bytes * 2, 4_000_000),
                                              min_interval_s=settings.sourcing_min_interval_s)
        if typed_providers is None:
            typed_providers, more = build_typed_providers(settings.sourcing_providers, self.fetcher, hosts)
            self.config_problems += more
        self.typed_providers = typed_providers
        try:
            normalize.set_brand_aliases(json.loads(settings.sourcing_brand_aliases) if settings.sourcing_brand_aliases else None)
        except (ValueError, AttributeError):
            self.config_problems.append("SOURCING_BRAND_ALIASES is not a JSON object")
        self._feed_meta = self._read_feed_meta(settings.sourcing_feeds)
        self._weights = self._read_weights(settings.sourcing_weights)
        self._catalogue: tuple[float, list[dict[str, Any]]] | None = None
        self.research = ResearchRunner(self)

    @staticmethod
    def error(status: int, code: str, message: str) -> SourcingError:
        return SourcingError(status, code, message)

    # ------------------------------------------------------------------------------------------ configuration
    @staticmethod
    def _read_feed_meta(raw: str | None) -> dict[str, dict[str, Any]]:
        try:
            return {str(f["name"])[:60]: f for f in json.loads(raw or "[]") if isinstance(f, dict) and f.get("name")}
        except ValueError:
            return {}

    @staticmethod
    def _read_weights(raw: str | None) -> dict[str, float] | None:
        if not raw:
            return None
        try:
            w = {k: float(v) for k, v in json.loads(raw).items() if k in scoring.DEFAULT_WEIGHTS and float(v) >= 0}
            return {**scoring.DEFAULT_WEIGHTS, **w}
        except (ValueError, AttributeError):
            return None

    def fx(self, currency: str) -> dict[str, Any] | None:
        """An operator-configured rate WITH its source and date. Anything else is 'no rate' (never guessed)."""
        if currency == self.settings.sourcing_base_currency:
            return {"rate": 1.0, "source": "base currency", "as_of": None}
        try:
            entry = json.loads(self.settings.sourcing_fx_rates or "{}").get(currency)
        except ValueError:
            return None
        if isinstance(entry, dict) and entry.get("rate") and entry.get("source") and entry.get("as_of") and float(entry["rate"]) > 0:
            return {"rate": float(entry["rate"]), "source": str(entry["source"]), "as_of": str(entry["as_of"])}
        return None

    def sources_status(self) -> dict[str, Any]:
        external = [{"name": p.name, "kind": "official brand website" if getattr(p, "source_type", "") == "OFFICIAL_BRAND_PAGE" else "feed", "host_configured": True} for p in self.typed_providers]
        external += [{"name": p.name, "kind": f"feed ({p.fmt})", "host_configured": True} for p in self.providers]
        return {"external_feeds": external, "configuration_problems": self.config_problems, "external_configured": bool(external), "demand_signals": True,
                "fx_currencies": sorted([c for c in json.loads(self.settings.sourcing_fx_rates or "{}") if self.fx(c)] if self._fx_json_ok() else []),
                "base_currency": self.settings.sourcing_base_currency, "score_model": scoring.MODEL_VERSION, "allowed_hosts": self.fetcher.allowed}

    def _fx_json_ok(self) -> bool:
        try:
            json.loads(self.settings.sourcing_fx_rates or "{}")
            return True
        except ValueError:
            return False

    # ------------------------------------------------------------------------------------------ catalogue (read only, through the public REST API)
    async def catalogue_rows(self) -> list[dict[str, Any]]:
        if self._catalogue and asyncio.get_event_loop().time() - self._catalogue[0] < 300:
            return self._catalogue[1]
        rows = await self.catalog.list_products()
        tax = await self.catalog.taxonomy()
        out = [{"id": r.get("id"), "name": r.get("name"), "sku": r.get("sku"), "brand": tax.brands.get(r.get("brandId"), ""), "sport": tax.sports.get(r.get("sportId"), ""),
                "category": tax.categories.get(r.get("categoryId"), "")} for r in rows]
        self._catalogue = (asyncio.get_event_loop().time(), out)
        return out

    # ------------------------------------------------------------------------------------------ research (see research.py)
    async def start_research(self, actor: Actor, params: dict[str, Any], wait: bool = False) -> dict[str, Any]:
        if not workflow.is_buyer(actor.roles):
            raise SourcingError(403, "FORBIDDEN", "Only buyers can start research")
        return await self.research.start(actor, params, wait=wait)

    # ------------------------------------------------------------------------------------------ quotes
    def quote_view(self, q: dict[str, Any], today: date | None = None) -> dict[str, Any]:
        today = today or _now().date()
        valid_until = date.fromisoformat(q["valid_until"]) if q.get("valid_until") else None
        quoted_on = date.fromisoformat(q["quoted_on"]) if q.get("quoted_on") else None
        warnings = []
        if valid_until and valid_until < today:
            status = "EXPIRED"
        elif not valid_until and quoted_on and (today - quoted_on).days > self.settings.sourcing_quote_stale_days:
            status = "STALE"
            warnings.append(f"No validity date and the quote is {(today - quoted_on).days} days old.")
        else:
            status = "ACTIVE"
            if not valid_until:
                warnings.append("The quote has no validity date.")
        if q["source_kind"] == "PUBLIC_LISTING":
            warnings.append("A public listing is not a quotation: confirm the price, MOQ and availability with the supplier.")
        if q["source_kind"] == "AI_ESTIMATE":
            warnings.append("This is an AI estimate, not a supplier figure.")
        if q["equivalence"] != "IDENTICAL":
            warnings.append("Equivalent alternative, not the identical product: " + (q.get("equivalence_note") or "differences not described."))
        return {**q, "status": status, "label": SOURCE_LABEL[q["source_kind"]], "warnings": warnings}

    async def add_quote(self, actor: Actor, opp_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        self._need_buyer(actor)
        opp = await self._opp(opp_id)
        if payload["source_kind"] == "VERIFIED":
            raise SourcingError(422, "USE_VERIFY", "A quote becomes VERIFIED through the verify action, by a buyer, after checking it")
        sup = await self.repo.get_supplier(payload["supplier_id"])
        if sup is None:
            raise SourcingError(404, "SUPPLIER_NOT_FOUND", "That supplier does not exist")
        if opp.get("record_kind") == "DEMAND_GAP":
            raise SourcingError(409, "NOT_A_PRODUCT", "A customer-demand gap is not a product: find or link a real product candidate before recording supplier offers")
        # a quote is a fixture if either side of it is: a fixture supplier or product must never produce a number that looks live
        prov = next((p for p in (opp.get("provenance"), sup.get("provenance")) if p in FIXTURE_PROVENANCES), "BUYER_ENTERED")
        origin = "DEMO" if prov == "DEMO_FIXTURE" else "MANUAL"
        quote = await self.repo.add_quote(opp_id, payload, origin, actor.hash, provenance=prov)
        await self.repo.add_decision(opp_id, actor.hash, "QUOTE_ADDED", reason=None, detail={"quote_id": quote["id"], "supplier": sup["name"], "source_kind": payload["source_kind"]})
        return self.quote_view({**quote, "supplier_name": sup["name"]})

    async def verify_quote(self, actor: Actor, quote_id: str) -> dict[str, Any]:
        self._need_buyer(actor)
        q = await self.repo.get_quote(quote_id)
        if q is None:
            raise SourcingError(404, "NOT_FOUND", "Quote not found")
        if q["source_kind"] == "AI_ESTIMATE":
            raise SourcingError(409, "ESTIMATE", "An AI estimate cannot be verified: record the supplier's actual quotation instead")
        if q["source_kind"] == "PUBLIC_LISTING":
            raise SourcingError(409, "PUBLIC_LISTING", "A public listing is not a quotation. Enter the supplier's quotation (with its reference) and verify that")
        out = await self.repo.verify_quote(quote_id, actor.hash)
        await self.repo.add_decision(q["opportunity_id"], actor.hash, "QUOTE_VERIFIED", detail={"quote_id": quote_id})
        return self.quote_view(out)  # type: ignore[arg-type]

    async def update_quote(self, actor: Actor, quote_id: str, fields: dict[str, Any]) -> dict[str, Any]:
        self._need_buyer(actor)
        q = await self.repo.get_quote(quote_id)
        if q is None:
            raise SourcingError(404, "NOT_FOUND", "Quote not found")
        out = await self.repo.update_quote(quote_id, fields)
        # editing the figures of a verified quote voids the verification: the buyer verified the OLD numbers
        if q["source_kind"] == "VERIFIED" and any(k in fields for k in ("unit_price", "moq", "price_breaks", "currency", "shipping_cost", "valid_until")):
            out = await self.repo.void_verification(quote_id)
        await self.repo.add_decision(q["opportunity_id"], actor.hash, "QUOTE_UPDATED", detail={"quote_id": quote_id, "fields": sorted(fields)})
        return self.quote_view(out)  # type: ignore[arg-type]

    # ------------------------------------------------------------------------------------------ calculations
    def build_inputs(self, quote: dict[str, Any] | None, quantity: int | None, selling_price: float | None, a: dict[str, Any]) -> tuple[calc.CostInputs, list[str]]:
        warnings: list[str] = []
        raw: dict[str, Any] = {"base_currency": self.settings.sourcing_base_currency, "quantity": quantity, "selling_price": selling_price}
        for k in ("duty_pct", "non_recoverable_tax_pct", "handling_per_unit", "packaging_per_unit", "other_fixed_costs", "defect_pct", "price_includes_tax", "sales_tax_pct",
                  "variable_cost_per_unit", "variable_cost_pct", "target_margin_pct"):
            if a.get(k) is not None:
                raw[k] = a[k]
        if quote:
            oc = quote.get("other_costs") or {}
            for k in ("duty_pct", "handling_per_unit", "packaging_per_unit", "other_fixed_costs"):
                if raw.get(k) is None and oc.get(k) is not None:
                    raw[k] = oc[k]
            raw.update({"unit_price": quote["unit_price"], "currency": quote["currency"], "moq": quote.get("moq"), "price_breaks": quote.get("price_breaks") or []})
            rate = None
            if quote["currency"] != self.settings.sourcing_base_currency:
                if a.get("fx_rate") is not None:
                    rate = {"rate": a["fx_rate"], "source": a.get("fx_source") or "buyer-entered", "as_of": a.get("fx_as_of")}
                else:
                    rate = self.fx(quote["currency"])
                if rate:
                    raw.update({"fx_rate": rate["rate"], "fx_source": rate["source"], "fx_as_of": rate["as_of"]})
            fx_for_ship = Decimal(str(rate["rate"])) if rate else Decimal(1)
            if a.get("freight_total") is not None:
                raw["freight_total"] = a["freight_total"]
            elif quote.get("shipping_cost") is not None:
                raw["freight_total"] = float(Decimal(str(quote["shipping_cost"])) * fx_for_ship)
            else:
                warnings.append("No shipping cost is recorded: the landed cost excludes freight.")
        return calc.parse_inputs(raw), warnings

    async def calculate(self, actor: Actor, opp_id: str, payload: dict[str, Any], save: bool) -> dict[str, Any]:
        self._need_buyer(actor)
        opp = await self._opp(opp_id)
        quote = None
        if payload.get("quote_id"):
            quote = await self.repo.get_quote(payload["quote_id"])
            if quote is None or quote["opportunity_id"] != opp_id:
                raise SourcingError(404, "QUOTE_NOT_FOUND", "That quote does not belong to this opportunity")
        a = payload.get("assumptions") or {}
        price = payload.get("selling_price", opp.get("selling_price"))
        try:
            if quote is None:
                # no saved quotation: the buyer typed the figures (e.g. an informal offer); they are used as given and nothing is looked up or guessed
                manual = payload.get("manual") or {}
                inputs, warnings = calc.parse_inputs({"base_currency": self.settings.sourcing_base_currency, **manual, **{k: v for k, v in a.items() if v is not None},
                                                      "quantity": payload.get("quantity"), "selling_price": price}), []
            else:
                inputs, warnings = self.build_inputs(quote, payload.get("quantity"), price, a)
        except (ValueError, TypeError) as exc:
            raise SourcingError(422, "INVALID_INPUT", str(exc)) from None
        result = calc.compute(inputs)
        body = result.as_dict()
        body["warnings"] = warnings
        if payload.get("quantities"):
            body["by_quantity"] = calc.compare_quantities(inputs, [int(q) for q in payload["quantities"]][:12])
        if payload.get("sensitivity", True):
            body["sensitivity"] = calc.sensitivity(inputs)
        body["inputs_echo"] = {"quote_id": payload.get("quote_id"), "quantity": payload.get("quantity"), "selling_price": price, "assumptions": a}
        # WHAT this number belongs to, stored with it: a margin shown anywhere must be traceable to one product, one offer, one quantity, one price and one moment
        body["basis"] = {"opportunity_id": opp_id, "opportunity_name": opp["name"], "provenance": opp.get("provenance"), "quote_id": payload.get("quote_id"),
                         "supplier": quote.get("supplier_name") if quote else None, "quote_source_kind": quote.get("source_kind") if quote else "TYPED_BY_BUYER",
                         "currency": (quote or {}).get("currency") or (payload.get("manual") or {}).get("currency"), "quantity": payload.get("quantity"), "selling_price": price,
                         "price_includes_tax": bool(a.get("price_includes_tax")), "calculated_at": _now().isoformat(),
                         "label": ("Quoted landed cost" if quote and quote.get("source_kind") in ("QUOTE_ENTERED", "VERIFIED") else "Estimated landed cost (from a public listing or typed figures)")
                                  if result.status == "OK" else "Incomplete cost calculation"}
        if save:
            snap = await self.repo.save_calc(opp_id, payload.get("quote_id"), body["inputs_echo"], body, actor.hash, basis=body["basis"])
            body["snapshot_id"] = snap["id"]
            if price is not None and payload.get("selling_price") is not None:
                await self.repo.set_selling_price(opp_id, price)
            await self.repo.add_decision(opp_id, actor.hash, "CALCULATION_SAVED", detail={"snapshot_id": snap["id"], "status": body["status"]})
        return body

    async def compare(self, actor: Actor, opp_id: str, quantity: int, selling_price: float | None, assumptions: dict[str, Any]) -> dict[str, Any]:
        self._need_buyer(actor)
        opp = await self._opp(opp_id)
        price = selling_price if selling_price is not None else opp.get("selling_price")
        rows = []
        for q in await self.repo.list_quotes(opp_id):
            view = self.quote_view(q)
            try:
                inputs, warnings = self.build_inputs(q, quantity, price, assumptions)
                res = calc.compute(inputs).as_dict()
            except ValueError as exc:
                res, warnings = {"status": "INVALID", "issues": [str(exc)], "values": {}, "missing": [], "assumptions": [], "cost_drivers": [], "missing_for_margin": []}, []
            rows.append({"quote": view, "calculation": res, "warnings": warnings + view["warnings"]})
        usable = [r for r in rows if r["calculation"]["status"] == "OK" and r["quote"]["status"] == "ACTIVE"]
        by_landed = sorted(usable, key=lambda r: Decimal(r["calculation"]["values"]["landed_cost_per_unit"]))
        by_price = sorted(usable, key=lambda r: Decimal(r["calculation"]["values"]["unit_price"]) * Decimal(str(self.fx_rate_of(r["quote"]))))
        summary: dict[str, Any] = {"quantity": quantity, "compared": len(rows), "usable": len(usable), "lowest_landed_quote": by_landed[0]["quote"]["id"] if by_landed else None,
                                   "lowest_unit_price_quote": by_price[0]["quote"]["id"] if by_price else None, "explanation": None}
        if by_landed and by_price and by_landed[0]["quote"]["id"] != by_price[0]["quote"]["id"]:
            a, b = by_price[0], by_landed[0]
            summary["explanation"] = (f"{a['quote']['supplier_name']} has the lowest unit price, but {b['quote']['supplier_name']} has the lower landed cost per sellable unit "
                                      f"({b['calculation']['values']['landed_cost_per_unit']} vs {a['calculation']['values']['landed_cost_per_unit']}): compare the freight, duty, handling and price-break lines.")
        elif by_landed:
            summary["explanation"] = f"{by_landed[0]['quote']['supplier_name']} has both the lowest unit price and the lowest landed cost at {quantity} units."
        if len(rows) > len(usable):
            summary["excluded"] = [{"quote_id": r["quote"]["id"], "supplier": r["quote"]["supplier_name"], "why": r["calculation"]["issues"] or r["calculation"]["missing"] or [r["quote"]["status"]]}
                                   for r in rows if r not in usable]
        for r in rows:
            r["rank_by_landed_cost"] = (by_landed.index(r) + 1) if r in by_landed else None
        return {"opportunity_id": opp_id, "selling_price": price, "summary": summary, "offers": rows}

    def fx_rate_of(self, quote: dict[str, Any]) -> float:
        if quote["currency"] == self.settings.sourcing_base_currency:
            return 1.0
        fx = self.fx(quote["currency"])
        return fx["rate"] if fx else 1.0

    # ------------------------------------------------------------------------------------------ scoring
    async def score(self, opp_id: str, save: bool = True) -> dict[str, Any]:
        opp = await self._opp(opp_id)
        evidence = await self.repo.list_evidence(opp_id)
        quotes = [self.quote_view(q) for q in await self.repo.list_quotes(opp_id)]
        since = _now() - timedelta(days=self.settings.sourcing_lookback_days)
        signals: dict[str, Any] = {"record_kind": opp.get("record_kind"), "provenance": opp.get("provenance"), "catalogue_match": (opp.get("catalogue_match") or {}).get("level"),
                                   "working_capital_limit": self.settings.sourcing_working_capital_limit}
        demand = None
        if opp.get("record_kind") == "DEMAND_GAP":
            ev = next((e for e in evidence if e["kind"] == "CUSTOMER_DEMAND"), None)
            nums = [int(x) for x in re.findall(r"\d+", (ev or {}).get("claim", ""))[:2]]
            if len(nums) == 2:
                signals["unmet_searches"], signals["unmet_customers"] = nums
        else:
            terms = [t for t in {(opp.get("model") or "").lower(), (opp.get("mfr_sku") or "").lower(), *[w for w in normalize.tokens(opp["name"]) if len(w) > 4 and w not in spec_mod.FAMILY_WORDS]} if t and len(t) > 3][:5]
            demand = await self.repo.demand_for(terms, since) if terms else None
            if demand is not None and await self.repo.demand_total(since) > 0:
                signals["unmet_searches"], signals["unmet_customers"] = demand["searches"], demand["customers"]
        signals["evidence"] = [{"id": e["id"], "source": e["source_label"], "source_type": e.get("source_type"), "verified_at": datetime.fromisoformat(e["verified_at"]) if e.get("verified_at") else None,
                                "kind": e["kind"]} for e in evidence if e["kind"] in ("SOURCE_RECORD", "CATALOGUE", "INVENTORY_SIGNAL")]
        imgs = [i for i in await self.repo.list_images(opp_id) if not i["reported"] and i["status"] in ("VALID", "UNCHECKED")]
        best_image = next((lvl for lvl in ("EXACT_MODEL", "LIKELY_MATCH", "SIMILAR_REFERENCE") if any(i["match_level"] == lvl for i in imgs)), None)
        if opp.get("record_kind") != "DEMAND_GAP":
            signals["identity"] = {"brand": bool(opp.get("brand")), "identifier": bool(opp.get("gtin") or opp.get("model") or opp.get("mfr_sku")), "description": bool(opp.get("description")),
                                   "specs": len(opp.get("specs") or {}), "image": best_image}
        # the financial basis: the latest saved calculation, and what kind of offer it was calculated from
        calcs = await self.repo.list_calcs(opp_id, 8)
        fin: dict[str, Any] = {"status": "NONE"}
        if calcs:
            latest = calcs[0]
            vals = (latest["result"].get("values") or {})
            qrow = next((q for q in quotes if q["id"] == latest.get("quote_id")), None)
            if vals.get("gross_margin_pct") is not None and latest["result"].get("status") == "OK":
                fin = {"status": "QUOTED" if qrow and qrow["source_kind"] in ("QUOTE_ENTERED", "VERIFIED") and qrow["status"] == "ACTIVE" else "ESTIMATED",
                       "basis": latest.get("basis") or latest["inputs"]}
                signals["gross_margin_pct"] = float(vals["gross_margin_pct"])
            else:
                fin = {"status": "INCOMPLETE", "basis": latest.get("basis") or latest["inputs"]}
        signals["financial"] = fin
        signals["quotes"] = [{"status": q["status"] if q["status"] == "ACTIVE" else "EXPIRED", "verification": "VERIFIED" if q["source_kind"] == "VERIFIED" else q["source_kind"],
                              "authorised": (normalize.normalize_brand(opp.get("brand")) in [normalize.normalize_brand(b) for b in q.get("authorised_brands") or []]) if q.get("authorised_brands") else None,
                              "lead_time_days": q.get("lead_time_days"), "moq_cost": (q["moq"] or 0) * q["unit_price"] * self.fx_rate_of(q) if q.get("moq") else None} for q in quotes
                             if q["source_kind"] != "AI_ESTIMATE"]
        result = scoring.score(signals, self._weights)
        result["inputs_summary"] = {"demand_window_days": self.settings.sourcing_lookback_days, "demand": demand,
                                    "inventory_signal": "not available: Inventory AI has no per-product demand for items outside the catalogue"}
        if save:
            await self.repo.save_score(opp_id, scoring.MODEL_VERSION, result)
        return result

    # ------------------------------------------------------------------------------------------ workflow
    async def change_status(self, actor: Actor, opp_id: str, target: str, reason: str | None, expected_version: int, quote_id: str | None = None) -> dict[str, Any]:
        opp = await self._opp(opp_id)
        quotes = [self.quote_view(q) for q in await self.repo.list_quotes(opp_id)]
        active = [q for q in quotes if q["status"] == "ACTIVE" and q["source_kind"] in ("QUOTE_ENTERED", "VERIFIED")]
        calcs = await self.repo.list_calcs(opp_id, 10)
        complete = any((c["result"].get("values") or {}).get("gross_margin_pct") is not None for c in calcs)
        evidence = await self.repo.list_evidence(opp_id)
        try:
            workflow.check_transition(opp["status"], target, actor.roles, reason, active_quotes=len(active), has_complete_calc=complete, record_kind=opp.get("record_kind") or "PRODUCT_CANDIDATE",
                                      provenance=opp.get("provenance"), has_identity=bool(opp.get("brand") and (opp.get("model") or opp.get("mfr_sku") or opp.get("gtin"))),
                                      has_evidence=any(e.get("url") for e in evidence) or any(e["kind"] == "BUYER_NOTE" for e in evidence))
        except WorkflowError as exc:
            raise SourcingError(exc.status, exc.code, exc.message) from None
        if quote_id is not None and not any(q["id"] == quote_id for q in quotes):
            raise SourcingError(404, "QUOTE_NOT_FOUND", "That offer does not belong to this product")
        updated = await self.repo.set_status(opp_id, target, expected_version)
        if updated is None:
            raise SourcingError(409, "VERSION_CONFLICT", "Someone else changed this opportunity. Reload it and try again.")
        if target == "APPROVED_FOR_ONBOARDING" and quote_id:
            await self.repo.set_approved_quote(opp_id, quote_id)
        await self.repo.add_decision(opp_id, actor.hash, "STATUS_CHANGE", opp["status"], target, (reason or "").strip() or None, detail={"quote_id": quote_id} if quote_id else None)
        return updated

    async def add_note(self, actor: Actor, opp_id: str, text: str) -> None:
        self._need_buyer(actor)
        await self._opp(opp_id)
        await self.repo.add_decision(opp_id, actor.hash, "NOTE", reason=text.strip()[:2000])

    def _need_buyer(self, actor: Actor) -> None:
        if not workflow.is_buyer(actor.roles):
            raise SourcingError(403, "FORBIDDEN", "This action is restricted to buyers")

    async def _opp(self, opp_id: str) -> dict[str, Any]:
        try:
            opp = await self.repo.get_opportunity(opp_id)
        except ValueError:
            opp = None
        if opp is None:
            raise SourcingError(404, "NOT_FOUND", "Opportunity not found")
        return opp

    async def classify(self, actor: Actor, opp_id: str, provenance: str, reason: str) -> dict[str, Any]:
        """A buyer reviews where a record really came from. Only the classifications a person can legitimately decide are allowed (never LIVE_EXTERNAL_SOURCE: that is earned by a source adapter)."""
        self._need_buyer(actor)
        if provenance not in ("BUYER_ENTERED", "CUSTOMER_DEMAND_SIGNAL", "DEMO_FIXTURE", "TEST_FIXTURE", "UNKNOWN_PROVENANCE"):
            raise SourcingError(422, "INVALID_PROVENANCE", "A person can classify a record as buyer-entered, customer-demand, demo, test or unknown; live external provenance comes only from a research source")
        opp = await self._opp(opp_id)
        if opp["provenance"] == "LIVE_EXTERNAL_SOURCE":
            raise SourcingError(409, "LIVE_RECORD", "A record fetched from an external source keeps that provenance")
        if provenance in ("DEMO_FIXTURE", "TEST_FIXTURE") and not self.settings.sourcing_enable_demo:
            raise SourcingError(403, "DEMO_DISABLED", "Fixture classification is disabled (SOURCING_ENABLE_DEMO)")
        if not reason.strip():
            raise SourcingError(422, "REASON_REQUIRED", "Say why the classification is changing")
        out = await self.repo.set_provenance(opp_id, provenance)
        await self.repo.add_decision(opp_id, actor.hash, "PROVENANCE_CLASSIFIED", reason=reason.strip()[:500], detail={"from": opp["provenance"], "to": provenance})
        return out  # type: ignore[return-value]

    # ------------------------------------------------------------------------------------------ images
    async def add_image(self, actor: Actor, opp_id: str, url: str, match_level: str, note: str | None) -> dict[str, Any]:
        self._need_buyer(actor)
        await self._opp(opp_id)
        if match_level not in img_mod.MATCH_LEVELS:
            raise SourcingError(422, "INVALID_MATCH_LEVEL", f"match_level must be one of {', '.join(img_mod.MATCH_LEVELS)}")
        if not self.fetcher.allows_host(url):
            raise SourcingError(422, "IMAGE_HOST_NOT_ALLOWED", "That image host is not on the allow-list (SOURCING_IMAGE_HOSTS). Ask an administrator to add it, or use an image from a configured source.")
        row = await self.repo.add_image(opp_id, {"url": url, "source_page_url": None, "source_type": "BUYER_ENTERED", "match_level": match_level, "attribution": "Added by a buyer",
                                                 "rights_note": (note or "Added by a buyer: confirm you have the right to use this image."), "position": 99}, actor.hash)
        if row is None:
            raise SourcingError(409, "DUPLICATE_IMAGE", "That image is already recorded for this product")
        status, detail, ctype, length = await img_mod.validate_image(self.fetcher, url)
        await self.repo.update_image_check(row["id"], status, detail, ctype, length)
        await self.repo.add_decision(opp_id, actor.hash, "IMAGE_ADDED", detail={"image_id": row["id"], "match_level": match_level, "status": status})
        return await self.repo.get_image(row["id"])  # type: ignore[return-value]

    async def report_image(self, actor: Actor, image_id: str, reason: str) -> dict[str, Any]:
        self._need_buyer(actor)
        im = await self.repo.get_image(image_id)
        if im is None:
            raise SourcingError(404, "NOT_FOUND", "Image not found")
        out = await self.repo.report_image(image_id, reason.strip()[:300] or "reported as not matching the product", actor.hash)
        await self.repo.add_decision(im["opportunity_id"], actor.hash, "IMAGE_REPORTED", reason=reason.strip()[:300] or None, detail={"image_id": image_id})
        return out  # type: ignore[return-value]

    async def set_primary_image(self, actor: Actor, opp_id: str, image_id: str) -> None:
        self._need_buyer(actor)
        im = await self.repo.get_image(image_id)
        if im is None or im["opportunity_id"] != opp_id or im["reported"]:
            raise SourcingError(404, "NOT_FOUND", "Image not found for this product")
        await self.repo.set_primary_image(opp_id, image_id)
        await self.repo.add_decision(opp_id, actor.hash, "IMAGE_PRIMARY_CHANGED", detail={"image_id": image_id})

    async def recheck_image(self, actor: Actor, image_id: str) -> dict[str, Any]:
        self._need_buyer(actor)
        im = await self.repo.get_image(image_id)
        if im is None:
            raise SourcingError(404, "NOT_FOUND", "Image not found")
        status, detail, ctype, length = await img_mod.validate_image(self.fetcher, im["url"])
        await self.repo.update_image_check(image_id, status, detail, ctype, length)
        return await self.repo.get_image(image_id)  # type: ignore[return-value]

    # ------------------------------------------------------------------------------------------ supplier verification ladder
    STAGES = ("DISCOVERED", "WEBSITE_CHECKED", "IDENTITY_VERIFIED", "BRAND_AUTHORISATION_VERIFIED")

    async def set_supplier_stage(self, actor: Actor, supplier_id: str, stage: str, note: str | None, evidence_url: str | None) -> dict[str, Any]:
        """Verification is a ladder a person climbs with evidence, one rung at a time. Seeing a supplier in a public listing is DISCOVERED; the rest are buyer decisions (the last two need an approver)."""
        self._need_buyer(actor)
        if stage not in self.STAGES:
            raise SourcingError(422, "INVALID_STAGE", f"stage must be one of {', '.join(self.STAGES)}")
        sup = await self.repo.get_supplier(supplier_id)
        if sup is None:
            raise SourcingError(404, "NOT_FOUND", "Supplier not found")
        cur = self.STAGES.index(sup["verification_status"]) if sup["verification_status"] in self.STAGES else 0
        new = self.STAGES.index(stage)
        if new > cur + 1:
            raise SourcingError(409, "SKIPPED_STAGE", f"A supplier moves one stage at a time: it is {sup['verification_status']}, the next stage is {self.STAGES[cur + 1]}")
        if stage in ("IDENTITY_VERIFIED", "BRAND_AUTHORISATION_VERIFIED") and not workflow.is_approver(actor.roles):
            raise SourcingError(403, "FORBIDDEN", "Verifying a supplier's identity or brand authorisation needs an approver (category manager or administrator)")
        if new > cur and not ((note or "").strip() or evidence_url):
            raise SourcingError(422, "EVIDENCE_REQUIRED", "Say what you checked (a note or a source URL) before moving a supplier up a stage")
        out = await self.repo.set_supplier_stage(supplier_id, stage, actor.hash, ((note or "").strip() + (f" [{evidence_url}]" if evidence_url else "")).strip() or None)
        if stage == "BRAND_AUTHORISATION_VERIFIED":
            brands = list(dict.fromkeys([*(sup.get("authorised_brands") or []), *(sup.get("brands") or [])]))
            await self.repo.update_supplier(supplier_id, {"authorised_brands": brands})
        return out  # type: ignore[return-value]

    # ------------------------------------------------------------------------------------------ readiness and onboarding proposal
    async def readiness(self, opp_id: str, quote_id: str | None = None, quantity: int | None = None, selling_price: float | None = None, image_exception: str | None = None) -> dict[str, Any]:
        opp = await self._opp(opp_id)
        quotes = [self.quote_view(q) for q in await self.repo.list_quotes(opp_id)]
        qid = quote_id or opp.get("approved_quote_id")
        return readiness_mod.evaluate(opp, await self.repo.list_images(opp_id), quotes, await self.repo.list_calcs(opp_id, 20), await self.repo.list_evidence(opp_id),
                                      image_exception=image_exception, quote_id=qid, quantity=quantity, selling_price=selling_price)

    async def build_proposal(self, actor: Actor, opp_id: str, proposal: dict[str, Any]) -> dict[str, Any]:
        self._need_buyer(actor)
        opp = await self._opp(opp_id)
        if opp.get("record_kind") == "DEMAND_GAP":
            raise SourcingError(409, "NOT_A_PRODUCT", "A customer-demand gap is not a product: no onboarding proposal can be prepared for it")
        if opp["status"] != "APPROVED_FOR_ONBOARDING":
            raise SourcingError(409, "NOT_APPROVED", "A proposal can only be prepared for an opportunity that is approved for onboarding")
        quotes = [self.quote_view(q) for q in await self.repo.list_quotes(opp_id)]
        chosen = next((q for q in quotes if q["id"] == proposal.get("quote_id")), None)
        calcs = await self.repo.list_calcs(opp_id, 20)
        calc_row = next((c for c in calcs if c["quote_id"] == proposal.get("quote_id") and (c["result"].get("values") or {}).get("landed_cost_per_unit")), None)
        evidence = await self.repo.list_evidence(opp_id)
        imgs = [i for i in await self.repo.list_images(opp_id) if not i["reported"]]
        score = await self.repo.latest_score(opp_id)
        values = (calc_row or {}).get("result", {}).get("values", {})
        ready = readiness_mod.evaluate(opp, imgs, quotes, calcs, evidence, image_exception=(proposal.get("image_exception") or "").strip() or None, quote_id=proposal.get("quote_id"),
                                       quantity=proposal.get("quantity"), selling_price=proposal.get("selling_price"))
        payload = {
            "product": {"name": opp["name"], "source_title": opp.get("source_title"), "brand": opp.get("brand"), "model": opp.get("model"), "mfr_sku": opp.get("mfr_sku"), "gtin": opp.get("gtin"),
                        "manufacturer": opp.get("manufacturer"), "sport": opp.get("sport"), "category": opp.get("category"), "description": opp.get("description"),
                        "specifications": spec_mod.flat(opp.get("specs") or {}), "product_url": opp.get("product_url")},
            "images": [{"url": i["url"], "match_level": i["match_level"], "source_page_url": i.get("source_page_url"), "source_type": i["source_type"], "attribution": i.get("attribution"),
                        "rights_note": i.get("rights_note"), "status": i["status"]} for i in imgs],
            "image_exception": (proposal.get("image_exception") or "").strip() or None,
            "provenance": opp.get("provenance"),
            "supplier": None if not chosen else {"quote_id": chosen["id"], "supplier": chosen["supplier_name"], "source": chosen["label"], "reference": chosen.get("reference"), "valid_until": chosen.get("valid_until"),
                                                 "lead_time_days": chosen.get("lead_time_days"), "warranty": chosen.get("warranty"), "return_terms": chosen.get("return_terms")},
            "purchase": {"proposed_quantity": proposal.get("quantity"), "landed_cost_per_unit": values.get("landed_cost_per_unit"), "total_procurement_cost": values.get("total_procurement_cost"),
                         "assumptions": (calc_row or {}).get("result", {}).get("assumptions")},
            "pricing": {"proposed_selling_price": proposal.get("selling_price") or opp.get("selling_price"), "gross_margin_pct": values.get("gross_margin_pct"), "target_margin_pct": proposal.get("target_margin_pct"),
                        "calculation_snapshot": (calc_row or {}).get("id")},
            "rationale": {"relevance": opp.get("relevance"), "catalogue_match": opp.get("catalogue_match"), "score": None if not score else {k: score["result"].get(k) for k in ("opportunity", "confidence", "supplier_risk", "status", "financial_status")}},
            "evidence": [{"claim": e["claim"], "source": e["source_label"], "url": e.get("url"), "checked": e.get("verified_at")} for e in evidence[:20]],
            "risks": (score["result"].get("supplier_risk", {}).get("factors") if score else ["No score has been calculated yet."]),
            "required_approvals": ["Category manager sign-off on assortment", "Finance approval of the purchase (NOT granted by this proposal)", "Catalogue team to create the product through the normal process"],
            "notice": "This proposal is a persisted draft. It does not create, publish or price a product and it does not authorise a purchase.",
        }
        validation = {"missing_mandatory": [i["label"] for i in ready["items"] if not i["ok"] and i["blocking"]], "ready": ready["ready"], "items": ready["items"],
                      "checked_against": "DSports catalogue fields (name, brand, sport, category, description, price), category specifications, an image with provenance, and the sourcing requirements; the catalogue has no draft API"}
        saved = await self.repo.save_proposal(opp_id, payload, validation, "READY_FOR_REVIEW" if ready["ready"] else "DRAFT", actor.hash)
        await self.repo.add_decision(opp_id, actor.hash, "PROPOSAL_PREPARED", detail={"proposal_id": saved["id"], "missing": len(validation["missing_mandatory"]), "ready": ready["ready"]})
        return saved

    # ------------------------------------------------------------------------------------------ dashboard
    async def dashboard(self) -> dict[str, Any]:
        """Every number is for LIVE data only (LIVE_EXTERNAL_SOURCE, BUYER_ENTERED, CUSTOMER_DEMAND_SIGNAL). Demonstration/test fixtures and unreviewed legacy rows are counted separately and never mixed in."""
        counts = await self.repo.counts_by_status("PRODUCT_CANDIDATE")
        gaps = await self.repo.counts_by_status("DEMAND_GAP")
        opps, total = await self.repo.list_opportunities({"record_kind": "PRODUCT_CANDIDATE"}, 0, 500, "updated")
        with_quotes = comparable = margin_ok = blocked = with_image = exact_image = 0
        for o in opps:
            quotes = [self.quote_view(q) for q in await self.repo.list_quotes(o["id"])]
            if quotes:
                with_quotes += 1
            if o.get("image_url"):
                with_image += 1
                exact_image += 1 if o.get("image_match_level") == "EXACT_MODEL" else 0
            # a margin counts only if it was saved for THIS product from an offer a buyer entered/verified, and that offer is still active
            basis = o.get("calc_basis") or {}
            offer = next((q for q in quotes if q["id"] == o.get("calc_quote_id")), None)
            if o.get("margin") is not None and offer and offer["source_kind"] in ("QUOTE_ENTERED", "VERIFIED") and offer["status"] == "ACTIVE" and basis.get("opportunity_id") in (o["id"], None) and o["margin"] >= 25:
                margin_ok += 1
            if o["status"] not in ("REJECTED", "APPROVED_FOR_ONBOARDING") and (not quotes or o.get("margin") is None):
                blocked += 1
            if len([q for q in quotes if q["status"] == "ACTIVE" and q["source_kind"] in ("QUOTE_ENTERED", "VERIFIED")]) >= 2:
                comparable += 1
        runs = await self.repo.list_runs(5)
        since = _now() - timedelta(days=self.settings.sourcing_lookback_days)
        by_prov = await self.repo.counts_by_provenance()
        sums = lambda kind, provs: sum(by_prov.get(kind, {}).get(p, 0) for p in provs)  # noqa: E731
        return {"as_of": _now().isoformat(), "scope": "live", "total_products": total, "by_status": counts, "awaiting_review": counts.get("DISCOVERED", 0) + counts.get("UNDER_REVIEW", 0),
                "shortlisted": counts.get("SHORTLISTED", 0), "approved_opportunity": counts.get("APPROVED_OPPORTUNITY", 0), "approved_for_onboarding": counts.get("APPROVED_FOR_ONBOARDING", 0),
                "with_supplier_quotes": with_quotes, "with_comparable_offers": comparable, "margin_at_least_25_pct": margin_ok, "blocked_by_missing_information": blocked,
                "with_product_image": with_image, "with_exact_model_image": exact_image,
                "demand_gaps": {"total": sum(gaps.values()), "by_status": gaps}, "by_provenance": {k: dict(v) for k, v in by_prov.items()},
                "excluded": {"fixtures": sums("PRODUCT_CANDIDATE", FIXTURE_PROVENANCES) + sums("DEMAND_GAP", FIXTURE_PROVENANCES), "unknown_provenance": sums("PRODUCT_CANDIDATE", ("UNKNOWN_PROVENANCE",)) + sums("DEMAND_GAP", ("UNKNOWN_PROVENANCE",))},
                "recent_runs": runs, "demand_signals_in_window": await self.repo.demand_total(since), "window_days": self.settings.sourcing_lookback_days,
                "notes": ["Counts are for live data only: demonstration and test fixtures are listed separately and never included.", "Margins count only when saved for the product from an active offer a buyer entered or verified; they are estimates, not realised results.",
                          "Seasonal and sporting-event opportunities are not shown: no event calendar source is configured."],
                "sources": self.sources_status()}

    # ------------------------------------------------------------------------------------------ labelled demonstration data
    async def seed_demo(self, actor: Actor) -> dict[str, Any]:
        if not self.settings.sourcing_enable_demo:
            raise SourcingError(403, "DEMO_DISABLED", "Demonstration data is disabled (SOURCING_ENABLE_DEMO)")
        self._need_buyer(actor)
        label = "DEMO DATA — NOT A LIVE SUPPLIER QUOTE"
        created = {"opportunities": 0, "suppliers": 0, "quotes": 0}
        suppliers = {}
        for name, typ, country in (("DEMO Supplier Alpha (sample)", "AUTHORISED_DISTRIBUTOR", "IN"), ("DEMO Supplier Beta (sample)", "WHOLESALER", "CN")):
            s = await self.repo.find_supplier(name, "DEMO")
            if not s:
                s = await self.repo.create_supplier({"name": name, "type": typ, "country": country, "categories": ["Badminton"], "brands": ["DemoBrand"],
                                                     "authorised_brands": ["DemoBrand"] if typ == "AUTHORISED_DISTRIBUTOR" else [], "authorisation_note": label}, "DEMO", actor.hash)
                created["suppliers"] += 1
            suppliers[name] = s
        for name, sport, cat, model, price in (("DEMO Carbon Badminton Racket (sample)", "Badminton", "Rackets", "DEMO-R1", 4999), ("DEMO Pro Shuttlecock Tube (sample)", "Badminton", "Accessories", "DEMO-S1", 899)):
            key = normalize.identity_key(name, "DemoBrand", model)
            if await self.repo.find_by_identity(key, "DEMO"):
                continue
            catalogue = []
            try:
                catalogue = await self.catalogue_rows()
            except Exception:  # noqa: BLE001
                pass
            match = normalize.match_catalogue({"name": name, "brand": "DemoBrand", "model": model}, catalogue)
            opp = await self.repo.create_opportunity({"identity_key": key, "name": name, "brand": "DemoBrand", "model": model, "sport": sport, "category": cat, "selling_price": price,
                                                      "description": f"{label}. A sample product used to demonstrate the buyer workflow.", "catalogue_match": match.__dict__,
                                                      "relevance": "Demonstration only.", "last_verified": _now(), "unanswered": ["This is demonstration data."]}, "DEMO", actor.hash)
            created["opportunities"] += 1
            await self.repo.add_evidence(opp["id"], {"kind": "BUYER_NOTE", "source_label": label, "claim": "Seeded demonstration product", "verified_at": _now()})
            await self.repo.add_decision(opp["id"], actor.hash, "DISCOVERED", to_status="DISCOVERED", detail={"source": "demo seed"})
            for sup_name, unit, freight, moq, lead, breaks in (("DEMO Supplier Alpha (sample)", 2100.0, 3000.0, 100, 14, [{"min_quantity": 500, "unit_price": 1950.0}]),
                                                              ("DEMO Supplier Beta (sample)", 1900.0, 60000.0, 300, 45, [])):
                await self.repo.add_quote(opp["id"], {"supplier_id": suppliers[sup_name]["id"], "offered_product": name, "model": model, "currency": "INR", "unit_price": unit, "moq": moq,
                                                      "price_breaks": breaks, "shipping_cost": freight, "shipping_note": label, "lead_time_days": lead, "quoted_on": _now().date(),
                                                      "valid_until": _now().date() + timedelta(days=60), "source_kind": "QUOTE_ENTERED", "reference": "DEMO-NOT-A-QUOTE"}, "DEMO", actor.hash)
                created["quotes"] += 1
        return {"created": created, "label": label}
