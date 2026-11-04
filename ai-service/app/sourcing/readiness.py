"""Onboarding readiness: is this product's record complete enough to hand to the catalogue team?

This is deliberately INDEPENDENT of the workflow status. Discovery, business approval, supplier qualification and catalogue readiness are different stages:
    DISCOVERED -> UNDER_REVIEW -> SHORTLISTED -> APPROVED_OPPORTUNITY (a business decision) -> SUPPLIER_RESEARCH -> QUOTE_COMPARISON -> APPROVED_FOR_ONBOARDING (prepare sourcing/onboarding)
A buyer may approve an opportunity while information is still missing: the decision is kept and the missing fields are shown, but the proposal cannot be marked ready (and so cannot be reviewed)
until every BLOCKING item is satisfied. Nothing here publishes a product, sets a price or authorises a purchase.
"""
from __future__ import annotations

from typing import Any, Iterable

from app.sourcing import specs as spec_mod


def _item(code: str, label: str, ok: bool, hint: str, blocking: bool = True) -> dict[str, Any]:
    return {"code": code, "label": label, "ok": ok, "blocking": blocking, "hint": hint}


def evaluate(opp: dict[str, Any], images: Iterable[dict[str, Any]], quotes: Iterable[dict[str, Any]], calcs: Iterable[dict[str, Any]], evidence: Iterable[dict[str, Any]],
             *, image_exception: str | None = None, quote_id: str | None = None, quantity: int | None = None, selling_price: float | None = None) -> dict[str, Any]:
    images, quotes, calcs, evidence = list(images), list(quotes), list(calcs), list(evidence)
    items: list[dict[str, Any]] = []
    if opp.get("record_kind") == "DEMAND_GAP":
        return {"applicable": False, "ready": False, "items": [], "missing": [], "summary": "A customer-demand gap is not a product: link or discover a real product first."}
    items.append(_item("name", "Product name", bool(opp.get("name")), "A product name from the source."))
    items.append(_item("brand", "Brand", bool(opp.get("brand")), "The brand is not known. Do not guess: find it on the source page or enter it as a buyer."))
    items.append(_item("sport", "Sport", bool(opp.get("sport")), "The sport this product is for."))
    items.append(_item("category", "Category", bool(opp.get("category")), "The product category (for example Rackets)."))
    items.append(_item("description", "Description", bool((opp.get("description") or "").strip()), "A product description from the source or written by a buyer."))
    items.append(_item("identifier", "Model number, manufacturer SKU or GTIN", bool(opp.get("gtin") or opp.get("model") or opp.get("mfr_sku")),
                       "No model number, SKU or GTIN is recorded: the exact product is not identified. (An approved identifier exception is not implemented: enter the identifier.)"))
    family = spec_mod.family_of(opp.get("category"), opp.get("name"), opp.get("product_type"))
    missing_specs = spec_mod.missing_specs(family, opp.get("specs") or {})
    items.append(_item("specifications", "Required specifications", not missing_specs,
                       ("Not stated by any source: " + ", ".join(missing_specs) + ". They are shown as missing, never generated.") if missing_specs else "All required specifications are present."))
    def usable(i: dict[str, Any]) -> bool:
        """A usable image is not reported as a mismatch, is not known to be broken or blocked, and states where it came from (a source page, or a buyer who entered it)."""
        if i.get("reported") or i.get("status") in ("BROKEN", "BLOCKED", "UNSUPPORTED"):
            return False
        return bool(i.get("source_page_url") or i.get("source_type") == "BUYER_ENTERED")
    has_image = any(usable(i) for i in images)
    items.append(_item("image", "Product image with its source", has_image or bool(image_exception),
                       "No usable image is recorded. Add an image reference with its source, or record an explicit reason why none is needed." if not image_exception
                       else f"No image; exception recorded: {image_exception}"))
    items.append(_item("evidence", "Source evidence", any(e.get("url") for e in evidence) or any(e.get("kind") == "BUYER_NOTE" for e in evidence), "At least one source URL, or a buyer note, supporting the product."))
    q = next((x for x in quotes if x["id"] == quote_id), None) if quote_id else None
    items.append(_item("quotation", "A chosen supplier quotation", q is not None and q.get("source_kind") in ("QUOTE_ENTERED", "VERIFIED") and q.get("status") == "ACTIVE",
                       "Choose an active supplier quotation that a buyer entered or verified (a public listing is not a quotation)."))
    calc = next((c for c in calcs if c.get("quote_id") == quote_id and (c.get("result", {}).get("values") or {}).get("landed_cost_per_unit")), None) if quote_id else None
    items.append(_item("calculation", "A saved landed-cost calculation for that quotation", calc is not None, "Save a landed-cost calculation for the chosen quotation."))
    items.append(_item("selling_price", "Proposed selling price", bool(selling_price or opp.get("selling_price")), "Enter the intended selling price (a plan, not a live price)."))
    items.append(_item("quantity", "Proposed purchase quantity", bool(quantity), "Enter the intended order quantity."))
    missing = [i for i in items if not i["ok"]]
    blocking = [i for i in missing if i["blocking"]]
    return {"applicable": True, "ready": not blocking, "items": items, "missing": [i["label"] for i in missing], "blocking_count": len(blocking),
            "summary": "Ready for catalogue review." if not blocking else f"Not ready: {len(blocking)} required item(s) missing."}
