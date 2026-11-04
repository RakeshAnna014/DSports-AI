"""Research runs: fetch from configured providers, extract, normalise, match against the catalogue, deduplicate, persist evidence/images/suppliers, rescore, and report what really happened.

Rules this module enforces:
  * a run is created RUNNING and always ends SUCCEEDED / PARTIAL / FAILED with a reason per provider; a successful HTTP response is NOT a discovery unless a valid product (name + source URL) was extracted and stored
  * repeated runs are idempotent: the same product (identity key) is refreshed, never duplicated; identical evidence and images are not added twice
  * provider failures are isolated (one failing source does not hide another's results) and always visible
  * customer-demand signals become DEMAND_GAP records, never products; a gap is linked to a real product only by a buyer
  * nothing is invented: a missing SKU, price or specification stays missing
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
from datetime import datetime, timedelta, timezone
from typing import Any

from app.sourcing import images as img_mod
from app.sourcing import normalize, specs
from app.sourcing.providers import Candidate, ProviderResult, SupplierCandidate
from app.sourcing.repository import LIVE_PROVENANCES

log = logging.getLogger(__name__)
SOURCE_LABELS = {"OFFICIAL_BRAND_PAGE": "official brand page", "AUTHORISED_DISTRIBUTOR": "authorised distributor page", "PUBLIC_LISTING": "public listing", "FEED": "configured feed"}


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _title(text: str) -> str:
    return " ".join(w.capitalize() for w in text.split())


class ResearchRunner:
    def __init__(self, svc: Any):
        self.svc = svc
        self._tasks: set[asyncio.Task] = set()

    # ------------------------------------------------------------------------------------------ starting a run
    async def start(self, actor: Any, params: dict[str, Any], wait: bool = False) -> dict[str, Any]:
        s = self.svc
        clean = {"sport": (params.get("sport") or "").strip() or None, "category": (params.get("category") or "").strip() or None,
                 "keywords": sorted({k.strip().lower() for k in params.get("keywords") or [] if k.strip()})[:10], "brand": (params.get("brand") or "").strip() or None,
                 "lookback_days": int(params.get("lookback_days") or s.settings.sourcing_lookback_days)}
        phash = hashlib.sha256(json.dumps(clean, sort_keys=True).encode()).hexdigest()[:32]
        await s.repo.fail_stale_runs()
        recent = [r for r in await s.repo.list_runs(50) if r["requested_by"] == actor.hash and r["started_at"] > (_now() - timedelta(hours=1)).isoformat()]
        if len(recent) >= s.settings.sourcing_runs_per_hour:
            raise s.error(429, "RATE_LIMITED", f"At most {s.settings.sourcing_runs_per_hour} research runs per hour; try again later")
        run = await s.repo.start_run(actor.hash, clean, phash)
        if run is None:
            existing = await s.repo.running_run(phash)
            raise s.error(409, "RUN_IN_PROGRESS", f"The same research is already running (run {existing['id'] if existing else '?'})")
        if wait:
            return await self._guarded(run, clean, actor)
        task = asyncio.create_task(self._guarded(run, clean, actor))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return run

    async def _guarded(self, run: dict[str, Any], params: dict[str, Any], actor: Any) -> dict[str, Any]:
        try:
            return await self._execute(run, params, actor)
        except Exception as exc:  # noqa: BLE001 - a run must always end in a recorded state
            log.exception("research run failed")
            await self.svc.repo.finish_run(run["id"], "FAILED", [], {}, error=f"{type(exc).__name__}: {str(exc)[:200]}")
            return await self.svc.repo.get_run(run["id"]) or run

    # ------------------------------------------------------------------------------------------ the run
    async def _execute(self, run: dict[str, Any], params: dict[str, Any], actor: Any) -> dict[str, Any]:
        s = self.svc
        stats: dict[str, int] = {"products_extracted": 0, "created": 0, "refreshed": 0, "duplicates": 0, "invalid": 0, "skipped": 0, "images_found": 0, "images_valid": 0, "images_failed": 0,
                                 "suppliers_found": 0, "suppliers_created": 0, "suppliers_refreshed": 0, "demand_gaps_new": 0, "demand_gaps_updated": 0, "rescored": 0,
                                 "catalogue_exact": 0, "catalogue_probable_duplicate": 0, "catalogue_similar": 0, "catalogue_review": 0, "catalogue_none": 0}
        sources: list[dict[str, Any]] = []
        providers = list(s.typed_providers) + list(s.providers)

        async def progress() -> None:
            await s.repo.update_run_progress(run["id"], sources, stats)

        if not providers:
            sources.append({"name": "external research sources", "status": "unavailable", "records": 0,
                            "reason": "No research source is configured (SOURCING_PROVIDERS or SOURCING_FEEDS, with SOURCING_ALLOWED_HOSTS). Only customer-demand signals were processed."})
        for problem in s.config_problems:
            sources.append({"name": "configuration", "status": "error", "records": 0, "reason": problem})
        catalogue: list[dict[str, Any]] = []
        try:
            catalogue = await s.catalogue_rows()
        except Exception:  # noqa: BLE001
            sources.append({"name": "DSports catalogue", "status": "unavailable", "records": 0, "reason": "The catalogue could not be read, so catalogue matches are not available in this run."})
        existing = await s.repo.existing_identities()
        touched: list[str] = []
        for provider in providers:
            entry = {"name": provider.name, "status": "running", "records": 0, "reason": None}
            sources.append(entry)
            await progress()
            try:
                res: ProviderResult = await provider.fetch(params)
            except Exception as exc:  # noqa: BLE001 - one provider must not take the run down
                log.exception("provider %s failed", provider.name)
                res = ProviderResult(provider.name, "error", reason=f"{type(exc).__name__}: {str(exc)[:160]}")
            entry.update({"status": res.status, "reason": res.reason, "records": len(res.candidates), "suppliers": len(res.suppliers), "provider_stats": res.stats, "skipped": res.skipped[:20]})
            stats["skipped"] += len(res.skipped)
            stats["invalid"] += int(res.stats.get("invalid", 0))
            for cand in res.candidates:
                opp_id = await self._ingest_candidate(run["id"], cand, catalogue, existing, stats, actor)
                if opp_id:
                    touched.append(opp_id)
            for sup in res.suppliers:
                await self._ingest_supplier(sup, stats, actor)
            if res.status == "ok" and not res.candidates and not res.suppliers and not res.reason:
                entry["reason"] = "The source answered but no valid product was extracted."
            await progress()
        # customer demand: searches the catalogue could not satisfy, as DEMAND GAPS (a search term is not a product)
        since = _now() - timedelta(days=params["lookback_days"])
        groups = await s.repo.demand_groups(since, s.settings.sourcing_demand_min_customers)
        total_signals = await s.repo.demand_total(since)
        for g in groups:
            await self._ingest_gap(g, catalogue, existing, stats, actor)
        sources.append({"name": "customer demand signals", "status": "ok", "records": len(groups), "reason": None if total_signals else "Informational: no unmet customer searches have been recorded in this window."})
        for opp_id in dict.fromkeys(touched):                                   # recalculate scores for what this run created or refreshed
            try:
                await s.score(opp_id)
                stats["rescored"] += 1
            except Exception:  # noqa: BLE001
                log.info("could not rescore %s", opp_id, exc_info=True)
        bad = [x for x in sources if x["status"] in ("error", "unavailable")]
        ok = [x for x in sources if x["status"] == "ok" and x["name"] != "customer demand signals"] or [x for x in sources if x["status"] == "ok"]
        status = "SUCCEEDED" if not bad else ("PARTIAL" if ok else "FAILED")
        if not providers:
            status = "PARTIAL"                                               # nothing external ran: never "succeeded"
        await s.repo.finish_run(run["id"], status, sources, stats, error=None if status != "FAILED" else "no source could be used")
        return await s.repo.get_run(run["id"]) or run

    # ------------------------------------------------------------------------------------------ products
    async def _ingest_candidate(self, run_id: str, c: Candidate, catalogue: list[dict[str, Any]], existing: list[dict[str, Any]], stats: dict[str, int], actor: Any) -> str | None:
        s = self.svc
        if not c.name or not c.url:
            stats["invalid"] += 1
            return None
        stats["products_extracted"] += 1
        brand = normalize.display_brand(c.brand) if c.brand else None
        cand = {"name": c.name, "brand": brand, "model": c.model, "gtin": c.gtin, "sku": c.sku, "mfr_sku": c.mfr_sku}
        key = normalize.identity_key(c.name, brand, c.model, c.gtin, c.mfr_sku or c.sku)
        _, possible = normalize.find_duplicates(cand, [e for e in existing if e.get("identity_key") != key])
        match = normalize.match_catalogue(cand, catalogue)
        stats[{"EXACT": "catalogue_exact", "PROBABLE_DUPLICATE": "catalogue_probable_duplicate", "SIMILAR": "catalogue_similar", "REVIEW": "catalogue_review", "NONE": "catalogue_none"}[match.level]] += 1
        record_id = await s.repo.save_record(run_id, c.provider, c.url, c.content_hash, c.excerpt, {"name": c.name, "brand": brand, "model": c.model, "sku": c.mfr_sku, "specs": len(c.specs)})
        facts = {"source_title": c.source_title, "description": c.description, "specs": c.specs, "product_url": c.url, "catalogue_match": match.__dict__}
        found = await s.repo.find_by_identity(key, "LIVE_RESEARCH")
        if found:
            await s.repo.update_candidate_facts(found["id"], facts)
            opp_id = found["id"]
            stats["refreshed"] += 1
            stats["duplicates"] += 1
        else:
            created = await s.repo.create_opportunity({
                "identity_key": key, "name": _title(c.name) if c.name.isupper() else c.name, "brand": brand, "manufacturer": normalize.display_brand(c.manufacturer) if c.manufacturer else brand,
                "model": c.model, "gtin": normalize.valid_gtin(c.gtin), "sport": c.sport, "category": c.category, "description": c.description, "specs": c.specs, "catalogue_match": match.__dict__,
                "possible_dupes": possible, "last_verified": c.retrieved_at, "relevance": self.relevance(match), "unanswered": self.unanswered(c, match), "source_title": c.source_title,
                "model_name": c.model_name, "mfr_sku": c.mfr_sku, "variant": c.variant, "product_url": c.url}, "LIVE_RESEARCH", actor.hash, provenance="LIVE_EXTERNAL_SOURCE")
            opp_id = created["id"]
            existing.append({"id": opp_id, "name": c.name, "brand": brand, "identity_key": key})
            stats["created"] += 1
            await s.repo.add_decision(opp_id, actor.hash, "DISCOVERED", to_status="DISCOVERED", detail={"provider": c.provider, "run_id": run_id, "url": c.url})
        host = c.url.split("/")[2] if "://" in c.url else c.url
        claim = f"Listed on the {SOURCE_LABELS.get(c.source_type, 'source')} ({host}): {c.source_title or c.name}"
        if not await s.repo.evidence_exists(opp_id, "SOURCE_RECORD", c.provider, claim):
            await s.repo.add_evidence(opp_id, {"record_id": record_id, "kind": "SOURCE_RECORD", "source_label": c.provider, "url": c.url, "claim": claim, "excerpt": c.excerpt,
                                               "retrieved_at": c.retrieved_at, "verified_at": c.retrieved_at, "source_type": c.source_type})
        await self.ingest_images(opp_id, c, stats, actor)
        return opp_id

    async def ingest_images(self, opp_id: str, c: Candidate, stats: dict[str, int], actor: Any) -> None:
        s = self.svc
        has_primary = any(i["is_primary"] and not i["reported"] for i in await s.repo.list_images(opp_id))
        for ref in c.images:
            stats["images_found"] += 1
            row = await s.repo.add_image(opp_id, {"url": ref.url, "source_page_url": ref.source_page_url, "source_type": ref.source_type, "match_level": ref.match_level,
                                                  "attribution": ref.attribution, "rights_note": ref.rights_note, "is_primary": ref.is_primary and not has_primary, "position": ref.position}, actor.hash)
            if row is None:
                continue
            status, detail, ctype, length = await img_mod.validate_image(s.fetcher, ref.url)
            await s.repo.update_image_check(row["id"], status, detail, ctype, length)
            stats["images_valid" if status == "VALID" else "images_failed"] += 1

    # ------------------------------------------------------------------------------------------ suppliers
    async def _ingest_supplier(self, sup: SupplierCandidate, stats: dict[str, int], actor: Any) -> None:
        s = self.svc
        stats["suppliers_found"] += 1
        existing = await s.repo.find_supplier(sup.name, "LIVE_RESEARCH")
        contact = {k: v for k, v in (("email", sup.email), ("phone", sup.phone), ("address", sup.address)) if v}
        if existing:
            await s.repo.refresh_supplier_discovery(existing["id"], contact, sup.source_url, sup.source_note)
            stats["suppliers_refreshed"] += 1
            return
        await s.repo.create_supplier({"name": sup.name, "type": sup.type, "website": sup.website, "contact": contact, "country": sup.country, "brands": sup.brands, "categories": [],
                                      "verification_status": "DISCOVERED", "discovery_url": sup.source_url, "discovery_note": sup.source_note}, "LIVE_RESEARCH", actor.hash,
                                     provenance="LIVE_EXTERNAL_SOURCE")
        stats["suppliers_created"] += 1

    # ------------------------------------------------------------------------------------------ customer demand gaps
    async def _ingest_gap(self, g: dict[str, Any], catalogue: list[dict[str, Any]], existing: list[dict[str, Any]], stats: dict[str, int], actor: Any) -> None:
        s = self.svc
        name = _title(g["longest_phrase"] if len(g["longest_phrase"]) <= 80 else g["query_norm"])
        match = normalize.match_catalogue({"name": name}, catalogue)
        if match.level == "EXACT":
            return
        key = f"demand:{g['demand_key']}"
        found = await s.repo.find_by_identity(key, "DEMAND_SIGNAL")
        claim = f"{g['searches']} search(es) from {g['customers']} customer(s) found no matching product"
        if found:
            opp_id = found["id"]
            await s.repo.touch_opportunity(opp_id, _now(), match.__dict__)
            stats["demand_gaps_updated"] += 1
        else:
            created = await s.repo.create_opportunity({
                "identity_key": key, "name": name, "sport": g.get("sport"), "catalogue_match": match.__dict__, "last_verified": _now(),
                "relevance": "Customers searched for this and the catalogue did not have a matching product.", "trend_note": "Customer interest only: a search is not proof of purchase intent, or that the product exists.",
                "unanswered": ["Which real products (brand and model) match what customers asked for?", "Is it available from a supplier, and at what cost?"], "record_kind": "DEMAND_GAP"},
                "DEMAND_SIGNAL", actor.hash, provenance="CUSTOMER_DEMAND_SIGNAL")
            opp_id = created["id"]
            stats["demand_gaps_new"] += 1
            await s.repo.add_decision(opp_id, actor.hash, "DISCOVERED", to_status="DISCOVERED", detail={"source": "customer demand signals"})
        if not await s.repo.evidence_exists(opp_id, "CUSTOMER_DEMAND", "DSports Customer AI", claim):
            await s.repo.add_evidence(opp_id, {"kind": "CUSTOMER_DEMAND", "source_label": "DSports Customer AI", "claim": claim, "retrieved_at": _now(), "verified_at": _now(), "source_type": "CUSTOMER_SEARCHES",
                                               "excerpt": f"first {g['first_ts'][:10]}, last {g['last_ts'][:10]} (anonymised: no conversation text or customer identity is kept)"})

    # ------------------------------------------------------------------------------------------ wording
    @staticmethod
    def relevance(match: normalize.Match) -> str:
        return {"NONE": "Nothing like it is in the DSports catalogue.", "SIMILAR": f"A related product is in the catalogue ({match.reason}): this may extend the range rather than fill a gap.",
                "PROBABLE_DUPLICATE": f"DSports probably already sells this ({match.reason}): confirm before treating it as new.", "REVIEW": f"The catalogue match needs a person to decide ({match.reason}).",
                "EXACT": "DSports already sells this product."}[match.level]

    @staticmethod
    def unanswered(c: Candidate, match: normalize.Match) -> list[str]:
        q = []
        if not c.gtin and not c.mfr_sku and not c.model:
            q.append("No model number, SKU or GTIN was found on the source page: the exact product is not yet identified.")
        if not c.public_price:
            q.append("No public price: a supplier quotation is needed before any cost or margin can be calculated.")
        q += ["Who supplies it in the required region, at what quotation, MOQ and lead time?", "Is there customer demand for it?"]
        fam = specs.family_of(c.category, c.name, c.description)
        missing = specs.missing_specs(fam, c.specs)
        if missing:
            q.append("Specifications not stated by the source: " + ", ".join(missing) + ".")
        if match.level in ("SIMILAR", "PROBABLE_DUPLICATE", "REVIEW"):
            q.append("How does it differ from the similar catalogue product?")
        return q
