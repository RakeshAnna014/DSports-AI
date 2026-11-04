"""Persistence for Product & Supplier Intelligence (asyncpg). SQL only: rules live in calc.py, scoring.py, workflow.py and service.py."""
from __future__ import annotations

import json
import uuid
from datetime import date, datetime
from typing import Any, Iterable, Sequence

import asyncpg

OPP_SORT = {"updated": "o.updated_at DESC", "first_seen": "o.first_seen DESC", "name": "lower(o.name) ASC", "score": "sc.opp_score DESC NULLS LAST", "margin": "m.margin DESC NULLS LAST", "last_seen": "o.last_seen DESC NULLS LAST"}


def _j(v: Any) -> Any:
    if isinstance(v, str):
        try:
            return json.loads(v)
        except ValueError:
            return v
    return v


def row(r: asyncpg.Record | None) -> dict[str, Any] | None:
    if r is None:
        return None
    d = dict(r)
    for k, v in list(d.items()):
        if k in ("specs", "catalogue_match", "possible_dupes", "unanswered", "contact", "price_breaks", "other_costs", "inputs", "result", "payload", "validation", "detail", "sources", "stats", "params", "structured", "basis", "calc_basis", "variant", "verification_history"):
            d[k] = _j(v)
        elif isinstance(v, uuid.UUID):
            d[k] = str(v)
        elif isinstance(v, (datetime, date)):
            d[k] = v.isoformat()
        elif hasattr(v, "quantize"):          # Decimal
            d[k] = float(v)
    return d


def rows(rs: Iterable[asyncpg.Record]) -> list[dict[str, Any]]:
    return [row(r) for r in rs]  # type: ignore[misc]


def dumps(v: Any) -> str:
    return json.dumps(v, default=str)


PROVENANCES = ("LIVE_EXTERNAL_SOURCE", "BUYER_ENTERED", "CUSTOMER_DEMAND_SIGNAL", "DEMO_FIXTURE", "TEST_FIXTURE", "UNKNOWN_PROVENANCE")
LIVE_PROVENANCES = ("LIVE_EXTERNAL_SOURCE", "BUYER_ENTERED", "CUSTOMER_DEMAND_SIGNAL")    # what counts in live business metrics; fixtures and unreviewed legacy rows never do
FIXTURE_PROVENANCES = ("DEMO_FIXTURE", "TEST_FIXTURE")


def default_provenance(origin: str) -> str:
    return {"LIVE_RESEARCH": "LIVE_EXTERNAL_SOURCE", "MANUAL": "BUYER_ENTERED", "DEMAND_SIGNAL": "CUSTOMER_DEMAND_SIGNAL", "DEMO": "DEMO_FIXTURE"}.get(origin, "UNKNOWN_PROVENANCE")


def as_date(v: Any) -> date | None:
    """DATE columns need real dates: an API payload carries ISO strings."""
    if v is None or v == "":
        return None
    return v if isinstance(v, date) and not isinstance(v, datetime) else date.fromisoformat(str(v)[:10])


class SourcingRepository:
    def __init__(self, pool: asyncpg.Pool):
        self._pool = pool

    # ------------------------------------------------------------------------------------------ runs
    async def start_run(self, requested_by: str, params: dict[str, Any], params_hash: str) -> dict[str, Any] | None:
        """None when an identical run is already RUNNING (the caller reports the existing one instead of starting a duplicate)."""
        try:
            r = await self._pool.fetchrow("INSERT INTO src_runs (id, requested_by, params, params_hash) VALUES ($1,$2,$3::jsonb,$4) RETURNING *",
                                          uuid.uuid4(), requested_by, dumps(params), params_hash)
        except asyncpg.UniqueViolationError:
            return None
        return row(r)

    async def running_run(self, params_hash: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("SELECT * FROM src_runs WHERE params_hash = $1 AND status = 'RUNNING'", params_hash))

    async def finish_run(self, run_id: str, status: str, sources: list[dict[str, Any]], stats: dict[str, Any], error: str | None = None) -> None:
        await self._pool.execute("UPDATE src_runs SET status=$2, finished_at=now(), sources=$3::jsonb, stats=$4::jsonb, error=$5 WHERE id=$1",
                                 uuid.UUID(run_id), status, dumps(sources), dumps(stats), error)

    async def update_run_progress(self, run_id: str, sources: list[dict[str, Any]], stats: dict[str, Any]) -> None:
        await self._pool.execute("UPDATE src_runs SET sources=$2::jsonb, stats=$3::jsonb WHERE id=$1 AND status='RUNNING'", uuid.UUID(run_id), dumps(sources), dumps(stats))

    async def refresh_supplier_discovery(self, supplier_id: str, contact: dict[str, Any], url: str, note: str) -> None:
        """The same public source was read again: update what it says now and when it was last seen, without touching anything a buyer verified."""
        await self._pool.execute("UPDATE src_suppliers SET contact = contact || $2::jsonb, discovery_url = COALESCE(discovery_url, $3), discovery_note = COALESCE(discovery_note, $4), "
                                 "discovered_at = now(), updated_at = now() WHERE id = $1", uuid.UUID(supplier_id), dumps(contact), url, note)

    async def fail_stale_runs(self, older_than_minutes: int = 30) -> int:
        """A run left RUNNING by a crashed process must not block the same research for ever."""
        r = await self._pool.execute("UPDATE src_runs SET status='FAILED', finished_at=now(), error='interrupted: the service stopped before the run finished' "
                                     "WHERE status='RUNNING' AND started_at < now() - make_interval(mins => $1)", older_than_minutes)
        return int(r.split()[-1])

    async def list_runs(self, limit: int = 20) -> list[dict[str, Any]]:
        return rows(await self._pool.fetch("SELECT * FROM src_runs ORDER BY started_at DESC LIMIT $1", limit))

    async def get_run(self, run_id: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("SELECT * FROM src_runs WHERE id = $1", uuid.UUID(run_id)))

    async def save_record(self, run_id: str | None, provider: str, url: str, content_hash: str, excerpt: str, structured: dict[str, Any]) -> int:
        r = await self._pool.fetchrow(
            "INSERT INTO src_records (run_id, provider, url, content_hash, excerpt, structured) VALUES ($1,$2,$3,$4,$5,$6::jsonb) "
            "ON CONFLICT (provider, url, content_hash) DO UPDATE SET retrieved_at = now(), run_id = EXCLUDED.run_id RETURNING id",
            uuid.UUID(run_id) if run_id else None, provider, url, content_hash, excerpt, dumps(structured))
        return int(r["id"])

    # ------------------------------------------------------------------------------------------ opportunities
    async def existing_identities(self, origin: str | None = None) -> list[dict[str, Any]]:
        q = "SELECT id, name, brand, identity_key, status FROM src_opportunities" + (" WHERE origin = $1" if origin else "")
        return rows(await (self._pool.fetch(q, origin) if origin else self._pool.fetch(q)))

    async def find_by_identity(self, identity_key: str, origin: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("SELECT * FROM src_opportunities WHERE identity_key=$1 AND origin=$2", identity_key, origin))

    async def create_opportunity(self, o: dict[str, Any], origin: str, actor: str, provenance: str | None = None) -> dict[str, Any]:
        provenance = provenance or o.get("provenance") or default_provenance(origin)
        kind = o.get("record_kind") or ("DEMAND_GAP" if origin == "DEMAND_SIGNAL" else "PRODUCT_CANDIDATE")
        r = await self._pool.fetchrow(
            """INSERT INTO src_opportunities (id, identity_key, name, brand, manufacturer, model, gtin, sport, category, product_type, segment, description, specs, trend_note,
                                              relevance, origin, catalogue_match, possible_dupes, unanswered, last_verified, created_by, selling_price,
                                              provenance, record_kind, source_title, model_name, mfr_sku, variant, product_url, last_seen)
               VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13::jsonb,$14,$15,$16,$17::jsonb,$18::jsonb,$19::jsonb,$20,$21,$22,$23,$24,$25,$26,$27,$28::jsonb,$29,$30) RETURNING *""",
            uuid.uuid4(), o["identity_key"], o["name"], o.get("brand"), o.get("manufacturer"), o.get("model"), o.get("gtin"), o.get("sport"), o.get("category"), o.get("product_type"),
            o.get("segment"), o.get("description"), dumps(o.get("specs") or {}), o.get("trend_note"), o.get("relevance"), origin, dumps(o.get("catalogue_match") or {}),
            dumps(o.get("possible_dupes") or []), dumps(o.get("unanswered") or []), o.get("last_verified"), actor, o.get("selling_price"),
            provenance, kind, o.get("source_title"), o.get("model_name"), o.get("mfr_sku"), dumps(o.get("variant") or {}), o.get("product_url"), o.get("last_seen") or o.get("last_verified"))
        return row(r)  # type: ignore[return-value]

    async def update_candidate_facts(self, opp_id: str, facts: dict[str, Any]) -> None:
        """A re-fetch of the same source page: refresh what the source says now (specs, description, title) and when it was last seen. Never overwrites a buyer's own fields with an empty value."""
        await self._pool.execute(
            """UPDATE src_opportunities SET last_seen = now(), last_verified = now(), updated_at = now(),
                      source_title = COALESCE($2, source_title), description = COALESCE($3, description), specs = CASE WHEN $4::jsonb = '{}'::jsonb THEN specs ELSE $4::jsonb END,
                      product_url = COALESCE($5, product_url), catalogue_match = COALESCE($6::jsonb, catalogue_match)
               WHERE id = $1""", uuid.UUID(opp_id), facts.get("source_title"), facts.get("description"), dumps(facts.get("specs") or {}), facts.get("product_url"),
            dumps(facts["catalogue_match"]) if facts.get("catalogue_match") is not None else None)

    async def set_provenance(self, opp_id: str, provenance: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("UPDATE src_opportunities SET provenance=$2, updated_at=now() WHERE id=$1 RETURNING *", uuid.UUID(opp_id), provenance))

    async def link_gap(self, gap_id: str, candidate_id: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("UPDATE src_opportunities SET linked_candidate_id=$2, updated_at=now() WHERE id=$1 AND record_kind='DEMAND_GAP' RETURNING *", uuid.UUID(gap_id), uuid.UUID(candidate_id)))

    async def touch_opportunity(self, opp_id: str, last_verified: datetime, catalogue_match: dict[str, Any] | None = None) -> None:
        await self._pool.execute("UPDATE src_opportunities SET last_verified=$2, updated_at=now(), catalogue_match=COALESCE($3::jsonb, catalogue_match) WHERE id=$1",
                                 uuid.UUID(opp_id), last_verified, dumps(catalogue_match) if catalogue_match is not None else None)

    async def get_opportunity(self, opp_id: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("SELECT * FROM src_opportunities WHERE id=$1", uuid.UUID(opp_id)))

    @staticmethod
    def scope_clause(alias: str, f: dict[str, Any], add) -> None:
        """The one rule every list, count and KPI uses: unless a caller asks for them, demonstration/test fixtures and unreviewed legacy rows are left out."""
        prov = f.get("provenance")
        if prov:
            add(f"{alias}.provenance = ANY(?::text[])", list(prov))
        elif not f.get("include_fixtures"):
            add(f"{alias}.provenance = ANY(?::text[])", list(LIVE_PROVENANCES))

    async def list_opportunities(self, f: dict[str, Any], page: int, size: int, sort: str = "updated") -> tuple[list[dict[str, Any]], int]:
        where, args = ["TRUE"], []

        def add(cond: str, value: Any) -> None:
            args.append(value)
            where.append(cond.replace("?", f"${len(args)}"))

        self.scope_clause("o", f, add)
        kind = f.get("record_kind") or ("PRODUCT_CANDIDATE" if f.get("kind") == "new_product" else "DEMAND_GAP" if f.get("kind") == "catalogue_gap" else None)
        if kind and kind != "ALL":
            add("o.record_kind = ?", kind)
        if f.get("status"):
            add("o.status = ANY(?::text[])", list(f["status"]))
        if f.get("sport"):
            add("lower(o.sport) = lower(?)", f["sport"])
        if f.get("category"):
            add("lower(o.category) = lower(?)", f["category"])
        if f.get("brand"):
            add("lower(o.brand) = lower(?)", f["brand"])
        if f.get("product_type"):
            add("lower(o.product_type) = lower(?)", f["product_type"])
        if f.get("origin"):
            add("o.origin = ?", f["origin"])
        if f.get("catalogue_match"):
            add("o.catalogue_match->>'level' = ?", f["catalogue_match"])
        if f.get("q"):
            add("(o.name ILIKE '%' || ? || '%' OR o.brand ILIKE '%' || ? || '%' OR o.model ILIKE '%' || ? || '%' OR o.mfr_sku ILIKE '%' || ? || '%' OR o.source_title ILIKE '%' || ? || '%')", f["q"])
        if f.get("researched_from"):
            add("o.first_seen >= ?", f["researched_from"])
        if f.get("researched_to"):
            add("o.first_seen < ?", f["researched_to"])
        if f.get("evidence") == "fresh":
            where.append("o.last_verified >= now() - interval '30 days'")
        elif f.get("evidence") == "stale":
            where.append("(o.last_verified IS NULL OR o.last_verified < now() - interval '30 days')")
        if f.get("has_supplier") is True:
            where.append("EXISTS (SELECT 1 FROM src_quotes q WHERE q.opportunity_id = o.id)")
        elif f.get("has_supplier") is False:
            where.append("NOT EXISTS (SELECT 1 FROM src_quotes q WHERE q.opportunity_id = o.id)")
        if f.get("has_image") is True:
            where.append("EXISTS (SELECT 1 FROM src_images i WHERE i.opportunity_id = o.id AND NOT i.reported AND i.status <> 'BROKEN')")
        elif f.get("has_image") is False:
            where.append("NOT EXISTS (SELECT 1 FROM src_images i WHERE i.opportunity_id = o.id AND NOT i.reported AND i.status <> 'BROKEN')")
        if f.get("image_match"):
            add("EXISTS (SELECT 1 FROM src_images i WHERE i.opportunity_id = o.id AND NOT i.reported AND i.match_level = ?)", f["image_match"])
        if f.get("supplier_verification"):
            add("EXISTS (SELECT 1 FROM src_quotes q JOIN src_suppliers su ON su.id = q.supplier_id WHERE q.opportunity_id = o.id AND su.verification_status = ?)", f["supplier_verification"])
        if f.get("has_margin") is True:
            where.append("m.margin IS NOT NULL")
        elif f.get("has_margin") is False:
            where.append("m.margin IS NULL")
        if f.get("risk"):
            add("sc.risk = ?", f["risk"])
        if f.get("min_margin") is not None:
            add("m.margin >= ?", float(f["min_margin"]))
        if f.get("max_margin") is not None:
            add("m.margin <= ?", float(f["max_margin"]))
        if f.get("min_score") is not None:
            add("sc.opp_score >= ?", float(f["min_score"]))
        if f.get("demand"):
            add("sc.demand_band = ?", f["demand"])
        if f.get("confidence"):
            add("sc.confidence = ?", f["confidence"])
        # the latest score snapshot, the latest saved margin (with the basis it was calculated on) and the primary image are joined in once, so filters and sorting use stored values
        base = """FROM src_opportunities o
                  LEFT JOIN LATERAL (SELECT (result->>'opportunity')::float AS opp_score, result->'supplier_risk'->>'level' AS risk, result->>'confidence' AS confidence, result->>'status' AS score_status,
                                            CASE WHEN (SELECT (d->>'score')::float FROM jsonb_array_elements(result->'dimensions') d WHERE d->>'dimension' = 'unmet_demand') >= 66 THEN 'STRONG'
                                                 WHEN (SELECT (d->>'score')::float FROM jsonb_array_elements(result->'dimensions') d WHERE d->>'dimension' = 'unmet_demand') >= 33 THEN 'MODERATE'
                                                 WHEN (SELECT 1 FROM jsonb_array_elements(result->'dimensions') d WHERE d->>'dimension' = 'unmet_demand') IS NOT NULL THEN 'WEAK' END AS demand_band
                                     FROM src_scores WHERE opportunity_id = o.id ORDER BY created_at DESC, id DESC LIMIT 1) sc ON TRUE
                  LEFT JOIN LATERAL (SELECT (c.result->'values'->>'gross_margin_pct')::float AS margin, c.id AS calc_id, c.created_at AS calc_at, c.quote_id AS calc_quote_id, c.basis AS calc_basis
                                     FROM src_calcs c WHERE c.opportunity_id = o.id AND c.result->'values' ? 'gross_margin_pct' ORDER BY c.created_at DESC LIMIT 1) m ON TRUE
                  LEFT JOIN LATERAL (SELECT i.url AS image_url, i.match_level AS image_match_level, i.status AS image_status, i.source_page_url AS image_source_url, i.attribution AS image_attribution
                                     FROM src_images i WHERE i.opportunity_id = o.id AND NOT i.reported AND i.status <> 'BROKEN'
                                     ORDER BY i.is_primary DESC, CASE i.match_level WHEN 'EXACT_MODEL' THEN 0 WHEN 'LIKELY_MATCH' THEN 1 WHEN 'SIMILAR_REFERENCE' THEN 2 ELSE 3 END, i.position, i.retrieved_at LIMIT 1) im ON TRUE"""
        sel = ("SELECT o.*, sc.opp_score, sc.risk, sc.confidence, sc.score_status, sc.demand_band, m.margin, m.calc_id, m.calc_at, m.calc_quote_id, m.calc_basis, "
               "im.image_url, im.image_match_level, im.image_status, im.image_source_url, im.image_attribution, "
               "(SELECT count(*) FROM src_quotes qq WHERE qq.opportunity_id = o.id) AS quote_count " + base)
        cond = " AND ".join(where)
        total = await self._pool.fetchval(f"SELECT count(*) {base} WHERE {cond}", *args)
        order = OPP_SORT.get(sort, OPP_SORT["updated"])
        data = await self._pool.fetch(f"{sel} WHERE {cond} ORDER BY {order}, o.id LIMIT {int(size)} OFFSET {int(page) * int(size)}", *args)
        return rows(data), int(total)

    async def update_opportunity(self, opp_id: str, fields: dict[str, Any], expected_version: int | None = None) -> dict[str, Any] | None:
        allowed = {"selling_price", "trend_note", "relevance", "unanswered", "segment", "product_type", "description", "possible_dupes"}
        sets, args = [], [uuid.UUID(opp_id)]
        for k, v in fields.items():
            if k not in allowed:
                continue
            args.append(dumps(v) if k in ("unanswered", "possible_dupes") else v)
            sets.append(f"{k} = ${len(args)}" + ("::jsonb" if k in ("unanswered", "possible_dupes") else ""))
        if not sets:
            return await self.get_opportunity(opp_id)
        cond = ""
        if expected_version is not None:
            args.append(expected_version)
            cond = f" AND version = ${len(args)}"
        r = await self._pool.fetchrow(f"UPDATE src_opportunities SET {', '.join(sets)}, updated_at = now(), version = version + 1 WHERE id = $1{cond} RETURNING *", *args)
        return row(r)

    async def set_approved_quote(self, opp_id: str, quote_id: str) -> None:
        await self._pool.execute("UPDATE src_opportunities SET approved_quote_id=$2 WHERE id=$1", uuid.UUID(opp_id), uuid.UUID(quote_id))

    async def set_selling_price(self, opp_id: str, price: float) -> None:
        """The buyer's working selling price. Deliberately NOT a version bump: the version guards status decisions, and saving a calculation must not invalidate the page a buyer is on."""
        await self._pool.execute("UPDATE src_opportunities SET selling_price=$2, updated_at=now() WHERE id=$1", uuid.UUID(opp_id), price)

    async def set_status(self, opp_id: str, new_status: str, expected_version: int) -> dict[str, Any] | None:
        """Compare-and-set on the version the buyer was looking at; None means someone else changed the opportunity first."""
        return row(await self._pool.fetchrow("UPDATE src_opportunities SET status=$2, updated_at=now(), version=version+1 WHERE id=$1 AND version=$3 RETURNING *",
                                             uuid.UUID(opp_id), new_status, expected_version))

    async def counts_by_status(self, record_kind: str = "PRODUCT_CANDIDATE", fixtures: bool = False) -> dict[str, int]:
        """Counts by status for LIVE data only (or only fixtures, when fixtures=True): the same rule as every list."""
        scope = FIXTURE_PROVENANCES if fixtures else LIVE_PROVENANCES
        return {r["status"]: int(r["n"]) for r in await self._pool.fetch(
            "SELECT status, count(*) AS n FROM src_opportunities WHERE provenance = ANY($1::text[]) AND record_kind = $2 GROUP BY status", list(scope), record_kind)}

    async def counts_by_provenance(self) -> dict[str, dict[str, int]]:
        out: dict[str, dict[str, int]] = {}
        for r in await self._pool.fetch("SELECT record_kind, provenance, count(*) AS n FROM src_opportunities GROUP BY 1, 2"):
            out.setdefault(r["record_kind"], {})[r["provenance"]] = int(r["n"])
        return out

    # ------------------------------------------------------------------------------------------ evidence
    async def add_evidence(self, opp_id: str, e: dict[str, Any]) -> int:
        r = await self._pool.fetchrow(
            "INSERT INTO src_evidence (opportunity_id, record_id, kind, source_label, url, claim, excerpt, retrieved_at, verified_at, conflicts_with, source_type) "
            "VALUES ($1,$2,$3,$4,$5,$6,$7,COALESCE($8, now()),$9,$10,$11) RETURNING id",
            uuid.UUID(opp_id), e.get("record_id"), e["kind"], e["source_label"], e.get("url"), e["claim"], e.get("excerpt"), e.get("retrieved_at"), e.get("verified_at"), e.get("conflicts_with") or [], e.get("source_type"))
        return int(r["id"])

    async def evidence_exists(self, opp_id: str, kind: str, source_label: str, claim: str) -> bool:
        return bool(await self._pool.fetchval("SELECT 1 FROM src_evidence WHERE opportunity_id=$1 AND kind=$2 AND source_label=$3 AND claim=$4 LIMIT 1", uuid.UUID(opp_id), kind, source_label, claim))

    async def list_evidence(self, opp_id: str) -> list[dict[str, Any]]:
        return rows(await self._pool.fetch("SELECT * FROM src_evidence WHERE opportunity_id=$1 ORDER BY retrieved_at DESC, id DESC", uuid.UUID(opp_id)))

    # ------------------------------------------------------------------------------------------ images (references with provenance; nothing is downloaded or redistributed)
    async def add_image(self, opp_id: str, im: dict[str, Any], actor: str) -> dict[str, Any] | None:
        """None when this image URL is already recorded for the product (re-fetching a page must not duplicate its gallery)."""
        r = await self._pool.fetchrow(
            """INSERT INTO src_images (id, opportunity_id, url, source_page_url, source_type, match_level, attribution, rights_note, status, status_detail, content_type, content_length,
                                       checked_at, is_primary, position, added_by)
               VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16) ON CONFLICT (opportunity_id, url) DO NOTHING RETURNING *""",
            uuid.uuid4(), uuid.UUID(opp_id), im["url"], im.get("source_page_url"), im["source_type"], im["match_level"], im.get("attribution"), im.get("rights_note"),
            im.get("status") or "UNCHECKED", im.get("status_detail"), im.get("content_type"), im.get("content_length"), im.get("checked_at"), bool(im.get("is_primary")), int(im.get("position") or 0), actor)
        return row(r)

    async def list_images(self, opp_id: str, include_reported: bool = True) -> list[dict[str, Any]]:
        return rows(await self._pool.fetch(
            "SELECT * FROM src_images WHERE opportunity_id=$1" + ("" if include_reported else " AND NOT reported") + " ORDER BY is_primary DESC, position, retrieved_at", uuid.UUID(opp_id)))

    async def get_image(self, image_id: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("SELECT * FROM src_images WHERE id=$1", uuid.UUID(image_id)))

    async def update_image_check(self, image_id: str, status: str, detail: str | None, content_type: str | None, length: int | None) -> None:
        await self._pool.execute("UPDATE src_images SET status=$2, status_detail=$3, content_type=$4, content_length=$5, checked_at=now() WHERE id=$1", uuid.UUID(image_id), status, detail, content_type, length)

    async def report_image(self, image_id: str, reason: str, actor: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("UPDATE src_images SET reported=TRUE, reported_reason=$2, reported_by=$3, is_primary=FALSE WHERE id=$1 RETURNING *", uuid.UUID(image_id), reason, actor))

    async def set_primary_image(self, opp_id: str, image_id: str) -> None:
        await self._pool.execute("UPDATE src_images SET is_primary = (id = $2) WHERE opportunity_id = $1", uuid.UUID(opp_id), uuid.UUID(image_id))

    async def delete_image(self, image_id: str) -> None:
        await self._pool.execute("DELETE FROM src_images WHERE id=$1", uuid.UUID(image_id))

    # ------------------------------------------------------------------------------------------ suppliers and quotes
    async def create_supplier(self, s: dict[str, Any], origin: str, actor: str, provenance: str | None = None) -> dict[str, Any]:
        provenance = provenance or s.get("provenance") or default_provenance(origin)
        stage = s.get("verification_status") or "DISCOVERED"
        history = [{"stage": stage, "at": datetime.now().astimezone().isoformat(), "by": actor, "note": s.get("discovery_note") or "recorded"}]
        r = await self._pool.fetchrow(
            """INSERT INTO src_suppliers (id, name, type, website, contact, country, region, categories, brands, authorised_brands, authorisation_note, verification_status, origin, created_by,
                                          provenance, discovery_url, discovery_note, discovered_at, verification_history)
               VALUES ($1,$2,$3,$4,$5::jsonb,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19::jsonb) RETURNING *""",
            uuid.uuid4(), s["name"], s["type"], s.get("website"), dumps(s.get("contact") or {}), s.get("country"), s.get("region"), s.get("categories") or [], s.get("brands") or [],
            s.get("authorised_brands") or [], s.get("authorisation_note"), stage, origin, actor, provenance, s.get("discovery_url"), s.get("discovery_note"),
            s.get("discovered_at") or (datetime.now().astimezone() if s.get("discovery_url") else None), dumps(history))
        return row(r)  # type: ignore[return-value]

    async def find_supplier(self, name: str, origin: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("SELECT * FROM src_suppliers WHERE lower(name)=lower($1) AND origin=$2", name, origin))

    async def set_supplier_stage(self, supplier_id: str, stage: str, actor: str, note: str | None) -> dict[str, Any] | None:
        """Move one rung on the verification ladder and append who did it, when and why to the supplier's history."""
        entry = {"stage": stage, "at": datetime.now().astimezone().isoformat(), "by": actor, "note": note}
        return row(await self._pool.fetchrow(
            "UPDATE src_suppliers SET verification_status=$2, verification_history = verification_history || $3::jsonb, updated_at=now() WHERE id=$1 RETURNING *",
            uuid.UUID(supplier_id), stage, dumps([entry])))

    async def get_supplier(self, supplier_id: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("SELECT * FROM src_suppliers WHERE id=$1", uuid.UUID(supplier_id)))

    async def list_suppliers(self, q: str | None, type_: str | None, origin: str | None, page: int, size: int, provenance: list[str] | None = None,
                             include_fixtures: bool = False, stage: str | None = None) -> tuple[list[dict[str, Any]], int]:
        where, args = ["TRUE"], []
        if provenance:
            args.append(list(provenance))
            where.append(f"s.provenance = ANY(${len(args)}::text[])")
        elif not include_fixtures:
            args.append(list(LIVE_PROVENANCES))
            where.append(f"s.provenance = ANY(${len(args)}::text[])")
        if stage:
            args.append(stage)
            where.append(f"s.verification_status = ${len(args)}")
        if q:
            args.append(q)
            where.append(f"(s.name ILIKE '%' || ${len(args)} || '%' OR ${len(args)} = ANY(s.brands) OR ${len(args)} = ANY(s.categories))")
        if type_:
            args.append(type_)
            where.append(f"s.type = ${len(args)}")
        if origin:
            args.append(origin)
            where.append(f"s.origin = ${len(args)}")
        cond = " AND ".join(where)
        total = await self._pool.fetchval(f"SELECT count(*) FROM src_suppliers s WHERE {cond}", *args)
        data = await self._pool.fetch(f"SELECT s.*, (SELECT count(*) FROM src_quotes q WHERE q.supplier_id = s.id) AS quote_count FROM src_suppliers s WHERE {cond} "
                                      f"ORDER BY lower(s.name) LIMIT {int(size)} OFFSET {int(page) * int(size)}", *args)
        return rows(data), int(total)

    async def update_supplier(self, supplier_id: str, fields: dict[str, Any]) -> dict[str, Any] | None:
        allowed = {"website", "contact", "country", "region", "categories", "brands", "authorised_brands", "authorisation_note", "type"}
        sets, args = [], [uuid.UUID(supplier_id)]
        for k, v in fields.items():
            if k in allowed:
                args.append(dumps(v) if k == "contact" else v)
                sets.append(f"{k} = ${len(args)}" + ("::jsonb" if k == "contact" else ""))
        if not sets:
            return await self.get_supplier(supplier_id)
        return row(await self._pool.fetchrow(f"UPDATE src_suppliers SET {', '.join(sets)}, updated_at=now() WHERE id=$1 RETURNING *", *args))

    async def add_quote(self, opp_id: str, q: dict[str, Any], origin: str, actor: str, provenance: str | None = None) -> dict[str, Any]:
        r = await self._pool.fetchrow(
            """INSERT INTO src_quotes (id, opportunity_id, supplier_id, equivalence, equivalence_note, offered_product, model, currency, unit_price, moq, price_breaks, shipping_cost,
                                       shipping_note, other_costs, lead_time_days, availability, warranty, return_terms, certifications, payment_terms, quoted_on, valid_until,
                                       source_kind, source_url, reference, origin, entered_by, provenance)
               VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11::jsonb,$12,$13,$14::jsonb,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24,$25,$26,$27,$28) RETURNING *""",
            uuid.uuid4(), uuid.UUID(opp_id), uuid.UUID(q["supplier_id"]), q.get("equivalence") or "IDENTICAL", q.get("equivalence_note"), q["offered_product"], q.get("model"), q["currency"],
            q["unit_price"], q.get("moq"), dumps(q.get("price_breaks") or []), q.get("shipping_cost"), q.get("shipping_note"), dumps(q.get("other_costs") or {}), q.get("lead_time_days"),
            q.get("availability"), q.get("warranty"), q.get("return_terms"), q.get("certifications"), q.get("payment_terms"), as_date(q.get("quoted_on")), as_date(q.get("valid_until")), q["source_kind"],
            q.get("source_url"), q.get("reference"), origin, actor, provenance or q.get("provenance") or default_provenance(origin))
        return row(r)  # type: ignore[return-value]

    async def supplier_offers(self, supplier_id: str) -> list[dict[str, Any]]:
        return rows(await self._pool.fetch(
            "SELECT q.*, s.name AS supplier_name, o.name AS opportunity_name, o.id AS opportunity_id FROM src_quotes q JOIN src_suppliers s ON s.id = q.supplier_id "
            "JOIN src_opportunities o ON o.id = q.opportunity_id WHERE q.supplier_id = $1 ORDER BY q.created_at DESC", uuid.UUID(supplier_id)))

    async def run_products(self, run_id: str) -> list[dict[str, Any]]:
        """The products a run touched: those with a source record retrieved by it (newly created or refreshed), with their primary image."""
        return rows(await self._pool.fetch(
            """SELECT DISTINCT ON (o.id) o.id, o.name, o.brand, o.model, o.mfr_sku, o.sport, o.category, o.status, o.provenance, o.catalogue_match, o.first_seen, o.last_verified,
                      (SELECT url FROM src_images i WHERE i.opportunity_id = o.id AND NOT i.reported AND i.status <> 'BROKEN' ORDER BY i.is_primary DESC, i.position LIMIT 1) AS image_url,
                      (SELECT match_level FROM src_images i WHERE i.opportunity_id = o.id AND NOT i.reported AND i.status <> 'BROKEN' ORDER BY i.is_primary DESC, i.position LIMIT 1) AS image_match_level,
                      (o.first_seen >= r.started_at) AS created_by_this_run, rec.url AS source_url
               FROM src_records rec JOIN src_runs r ON r.id = rec.run_id
               JOIN src_evidence e ON e.record_id = rec.id JOIN src_opportunities o ON o.id = e.opportunity_id
               WHERE rec.run_id = $1 ORDER BY o.id""", uuid.UUID(run_id)))

    async def get_quote(self, quote_id: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("SELECT q.*, s.name AS supplier_name FROM src_quotes q JOIN src_suppliers s ON s.id = q.supplier_id WHERE q.id=$1", uuid.UUID(quote_id)))

    async def list_quotes(self, opp_id: str) -> list[dict[str, Any]]:
        return rows(await self._pool.fetch(
            "SELECT q.*, s.name AS supplier_name, s.type AS supplier_type, s.verification_status AS supplier_verification, s.authorised_brands, s.country AS supplier_country, "
            "s.website AS supplier_website FROM src_quotes q JOIN src_suppliers s ON s.id = q.supplier_id WHERE q.opportunity_id=$1 ORDER BY q.created_at", uuid.UUID(opp_id)))

    async def update_quote(self, quote_id: str, fields: dict[str, Any]) -> dict[str, Any] | None:
        allowed = {"unit_price", "moq", "price_breaks", "shipping_cost", "shipping_note", "other_costs", "lead_time_days", "availability", "warranty", "return_terms", "certifications",
                   "payment_terms", "quoted_on", "valid_until", "source_url", "reference", "currency", "equivalence", "equivalence_note", "offered_product", "model", "source_kind"}
        sets, args = [], [uuid.UUID(quote_id)]
        for k, v in fields.items():
            if k in allowed:
                args.append(dumps(v) if k in ("price_breaks", "other_costs") else as_date(v) if k in ("quoted_on", "valid_until") else v)
                sets.append(f"{k} = ${len(args)}" + ("::jsonb" if k in ("price_breaks", "other_costs") else ""))
        if not sets:
            return await self.get_quote(quote_id)
        await self._pool.execute(f"UPDATE src_quotes SET {', '.join(sets)}, updated_at=now() WHERE id=$1", *args)
        return await self.get_quote(quote_id)

    async def verify_quote(self, quote_id: str, verifier: str) -> dict[str, Any] | None:
        await self._pool.execute("UPDATE src_quotes SET source_kind='VERIFIED', verified_by=$2, verified_at=now(), updated_at=now() WHERE id=$1", uuid.UUID(quote_id), verifier)
        return await self.get_quote(quote_id)

    async def void_verification(self, quote_id: str) -> dict[str, Any] | None:
        await self._pool.execute("UPDATE src_quotes SET source_kind='QUOTE_ENTERED', verified_by=NULL, verified_at=NULL, updated_at=now() WHERE id=$1", uuid.UUID(quote_id))
        return await self.get_quote(quote_id)

    # ------------------------------------------------------------------------------------------ calculations, scores, decisions, proposals
    async def save_calc(self, opp_id: str, quote_id: str | None, inputs: dict[str, Any], result: dict[str, Any], actor: str, basis: dict[str, Any] | None = None) -> dict[str, Any]:
        r = await self._pool.fetchrow("INSERT INTO src_calcs (id, opportunity_id, quote_id, inputs, result, created_by, basis) VALUES ($1,$2,$3,$4::jsonb,$5::jsonb,$6,$7::jsonb) RETURNING *",
                                      uuid.uuid4(), uuid.UUID(opp_id), uuid.UUID(quote_id) if quote_id else None, dumps(inputs), dumps(result), actor, dumps(basis or {}))
        return row(r)  # type: ignore[return-value]

    async def list_calcs(self, opp_id: str, limit: int = 20) -> list[dict[str, Any]]:
        return rows(await self._pool.fetch("SELECT * FROM src_calcs WHERE opportunity_id=$1 ORDER BY created_at DESC LIMIT $2", uuid.UUID(opp_id), limit))

    async def save_score(self, opp_id: str, version: str, result: dict[str, Any]) -> None:
        await self._pool.execute("INSERT INTO src_scores (opportunity_id, model_version, result) VALUES ($1,$2,$3::jsonb)", uuid.UUID(opp_id), version, dumps(result))

    async def latest_score(self, opp_id: str) -> dict[str, Any] | None:
        r = await self._pool.fetchrow("SELECT * FROM src_scores WHERE opportunity_id=$1 ORDER BY created_at DESC, id DESC LIMIT 1", uuid.UUID(opp_id))
        return row(r)

    async def add_decision(self, opp_id: str | None, actor: str, action: str, from_status: str | None = None, to_status: str | None = None, reason: str | None = None,
                           detail: dict[str, Any] | None = None) -> None:
        await self._pool.execute("INSERT INTO src_decisions (opportunity_id, actor_hash, action, from_status, to_status, reason, detail) VALUES ($1,$2,$3,$4,$5,$6,$7::jsonb)",
                                 uuid.UUID(opp_id) if opp_id else None, actor, action, from_status, to_status, reason, dumps(detail or {}))

    async def list_decisions(self, opp_id: str) -> list[dict[str, Any]]:
        return rows(await self._pool.fetch("SELECT * FROM src_decisions WHERE opportunity_id=$1 ORDER BY at, id", uuid.UUID(opp_id)))

    async def save_proposal(self, opp_id: str, payload: dict[str, Any], validation: dict[str, Any], status: str, actor: str) -> dict[str, Any]:
        r = await self._pool.fetchrow("INSERT INTO src_proposals (id, opportunity_id, status, payload, validation, created_by) VALUES ($1,$2,$3,$4::jsonb,$5::jsonb,$6) RETURNING *",
                                      uuid.uuid4(), uuid.UUID(opp_id), status, dumps(payload), dumps(validation), actor)
        return row(r)  # type: ignore[return-value]

    async def list_proposals(self, opp_id: str | None = None) -> list[dict[str, Any]]:
        if opp_id:
            return rows(await self._pool.fetch("SELECT * FROM src_proposals WHERE opportunity_id=$1 ORDER BY created_at DESC", uuid.UUID(opp_id)))
        return rows(await self._pool.fetch("SELECT p.*, o.name AS opportunity_name FROM src_proposals p JOIN src_opportunities o ON o.id=p.opportunity_id ORDER BY p.created_at DESC LIMIT 100"))

    async def get_proposal(self, proposal_id: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("SELECT * FROM src_proposals WHERE id=$1", uuid.UUID(proposal_id)))

    async def set_proposal_status(self, proposal_id: str, status: str) -> dict[str, Any] | None:
        return row(await self._pool.fetchrow("UPDATE src_proposals SET status=$2, updated_at=now() WHERE id=$1 RETURNING *", uuid.UUID(proposal_id), status))

    # ------------------------------------------------------------------------------------------ demand signals
    async def record_demand(self, customer_hash: str, query_norm: str, sport: str | None, result_count: int, kind: str, key_term: str | None = None) -> None:
        await self._pool.execute("INSERT INTO src_demand_signals (customer_hash, query_norm, sport, result_count, kind, key_term) VALUES ($1,$2,$3,$4,$5,$6)",
                                 customer_hash, query_norm, sport, result_count, kind, key_term)

    async def demand_groups(self, since: datetime, min_customers: int = 1) -> list[dict[str, Any]]:
        """One group per demand: signals sharing a key term are the same request in different words. The displayed phrase is the most common one (the longest on a tie)."""
        return rows(await self._pool.fetch(
            """SELECT COALESCE(key_term, query_norm) AS demand_key,
                      (array_agg(query_norm ORDER BY length(query_norm) DESC))[1] AS longest_phrase,
                      mode() WITHIN GROUP (ORDER BY query_norm) AS query_norm,
                      max(sport) AS sport, count(*) AS searches, count(DISTINCT customer_hash) AS customers, min(ts) AS first_ts, max(ts) AS last_ts
               FROM src_demand_signals WHERE ts >= $1 GROUP BY COALESCE(key_term, query_norm)
               HAVING count(DISTINCT customer_hash) >= $2 ORDER BY customers DESC, searches DESC, last_ts DESC LIMIT 100""", since, min_customers))

    async def demand_for(self, terms: Sequence[str], since: datetime) -> dict[str, Any]:
        if not terms:
            return {"searches": 0, "customers": 0, "queries": []}
        pats = [f"%{t}%" for t in terms]
        r = await self._pool.fetchrow("SELECT count(*) AS searches, count(DISTINCT customer_hash) AS customers FROM src_demand_signals WHERE ts >= $1 AND query_norm ILIKE ANY($2::text[])", since, pats)
        sample = await self._pool.fetch("SELECT DISTINCT query_norm FROM src_demand_signals WHERE ts >= $1 AND query_norm ILIKE ANY($2::text[]) LIMIT 5", since, pats)
        return {"searches": int(r["searches"]), "customers": int(r["customers"]), "queries": [s["query_norm"] for s in sample]}

    async def demand_total(self, since: datetime) -> int:
        return int(await self._pool.fetchval("SELECT count(*) FROM src_demand_signals WHERE ts >= $1", since) or 0)
