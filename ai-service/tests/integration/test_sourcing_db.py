"""Product & Supplier Intelligence against a REAL PostgreSQL.

Covers: research persistence through the real provider code (fed with fixtures that mimic the Victor site's structure, so NO live network is used here), idempotent runs, images, supplier discovery,
demand gaps, provenance isolation of fixtures from live metrics, calculation association, the approval stages and readiness gates, the supplier verification ladder, the migration backfill and the
REST API with roles. Uses its own throwaway database (SOURCING_TEST_DATABASE_URL, falling back to AI_TEST_DATABASE_URL) and drops only the src_* tables."""
from __future__ import annotations

import asyncio
import json
import os
import uuid
from types import SimpleNamespace

import asyncpg
import httpx
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.sourcing.fetch import SafeFetcher
from app.sourcing.repository import SourcingRepository
from app.sourcing.service import Actor, SourcingError, SourcingService
from app.sourcing.victor import VictorSportProvider
from app.store.db import Store
from tests.conftest import make_settings, make_token
from tests.sourcing.fixtures import CDN, HOST, public_resolver, victor_handler

DSN = os.environ.get("SOURCING_TEST_DATABASE_URL") or os.environ.get("AI_TEST_DATABASE_URL")
pytestmark = [pytest.mark.integration, pytest.mark.skipif(not DSN, reason="SOURCING_TEST_DATABASE_URL / AI_TEST_DATABASE_URL is not set")]
TABLES = ("src_demand_signals", "src_proposals", "src_decisions", "src_scores", "src_calcs", "src_images", "src_quotes", "src_suppliers", "src_evidence", "src_opportunities", "src_records", "src_runs")
BUYER = Actor("buyer-hash", ["PROCUREMENT_BUYER"])
APPROVER = Actor("approver-hash", ["CATEGORY_MANAGER"])
FX = json.dumps({"USD": {"rate": 83, "source": "test rate sheet", "as_of": "2026-10-14"}})


class FakeCatalog:
    async def list_products(self):
        return [{"id": "p1", "name": "Yonex Starter Racket", "sku": "YX-1", "brandId": "b1", "sportId": "s1", "categoryId": "c1"}]

    async def taxonomy(self):
        return SimpleNamespace(brands={"b1": "Yonex"}, sports={"s1": "Badminton"}, categories={"c1": "Rackets"})


def make_service(repo, handler=None, **settings):
    s = make_settings(SOURCING_FX_RATES=FX, SOURCING_ALLOWED_HOSTS=f"{HOST},{CDN}", SOURCING_IMAGE_HOSTS=CDN, **settings)
    fetcher = SafeFetcher([HOST, CDN], transport=httpx.MockTransport(handler or victor_handler()), resolver=public_resolver, min_interval_s=0)
    return SourcingService(repo, s, FakeCatalog(), providers=[], typed_providers=[VictorSportProvider(fetcher, max_products=10)], fetcher=fetcher)


@pytest.fixture
async def world():
    conn = await asyncpg.connect(DSN)
    for t in TABLES:
        await conn.execute(f"DROP TABLE IF EXISTS {t} CASCADE")
    await conn.close()
    store = await Store.connect(DSN, 30, 768)
    repo = SourcingRepository(store.pool)
    yield make_service(repo), repo, store
    await store.close()


async def live_list(repo, **f):
    return (await repo.list_opportunities({"record_kind": "PRODUCT_CANDIDATE", **f}, 0, 50))[0]


async def research(svc, **params):
    return await svc.start_research(BUYER, params, wait=True)


async def a_candidate(svc, repo, name="Thruster K 100"):
    await research(svc, keywords=["thruster"])
    return next(o for o in await live_list(repo) if o["name"] == name)


# ------------------------------------------------------------------------------------------------ research: real extraction, persistence, idempotency
@pytest.mark.asyncio
async def test_research_persists_real_structure_products_with_provenance_specs_evidence_images_and_a_supplier(world):
    svc, repo, _ = world
    run = await research(svc)
    assert run["status"] == "SUCCEEDED" and run["stats"]["created"] == 3 and run["stats"]["invalid"] == 1 and run["stats"]["images_valid"] == 3 and run["stats"]["suppliers_created"] == 1
    items = await live_list(repo)
    assert len(items) == 3
    tk = next(o for o in items if o["name"] == "Thruster K 100")
    assert tk["provenance"] == "LIVE_EXTERNAL_SOURCE" and tk["record_kind"] == "PRODUCT_CANDIDATE" and tk["brand"] == "Victor" and tk["mfr_sku"] == "TK-100" and tk["category"] == "Rackets"
    assert tk["source_title"].startswith("THRUSTER K 100 |") and tk["specs"]["frame_material"]["value"] == "Graphite + Resin" and tk["product_url"].endswith("/product/thruster-k-11")
    ev = await repo.list_evidence(tk["id"])
    assert ev[0]["kind"] == "SOURCE_RECORD" and ev[0]["url"] == tk["product_url"] and ev[0]["source_type"] == "OFFICIAL_BRAND_PAGE"
    imgs = await repo.list_images(tk["id"])
    assert len(imgs) == 1 and imgs[0]["match_level"] == "EXACT_MODEL" and imgs[0]["status"] == "VALID" and imgs[0]["is_primary"] and imgs[0]["source_page_url"] == tk["product_url"] and imgs[0]["rights_note"]
    sups, _ = await repo.list_suppliers(None, None, None, 0, 10)
    assert [s["name"] for s in sups] == ["VICTOR India"] and sups[0]["provenance"] == "LIVE_EXTERNAL_SOURCE" and sups[0]["verification_status"] == "DISCOVERED"
    assert sups[0]["website"] is None                                                                # nothing is claimed beyond what the page said
    assert tk["catalogue_match"]["level"] == "NONE"
    detail = await repo.run_products(run["id"])
    assert "Thruster K 100" in {p["name"] for p in detail}


@pytest.mark.asyncio
async def test_repeating_a_run_refreshes_and_never_duplicates_products_evidence_images_or_suppliers(world):
    svc, repo, _ = world
    await research(svc)
    first = (await live_list(repo))[0]
    first_ev = await repo.list_evidence(first["id"])
    again = await research(svc)
    assert again["stats"]["created"] == 0 and again["stats"]["suppliers_created"] == 0
    assert len(await live_list(repo)) == 3 and len(await repo.list_images(first["id"])) == 1
    assert len(await repo.list_evidence(first["id"])) == len(first_ev)
    assert (await repo.list_suppliers(None, None, None, 0, 10))[1] == 1


@pytest.mark.asyncio
async def test_a_run_is_asynchronous_reports_progress_and_always_ends_in_a_recorded_state(world):
    svc, repo, _ = world
    started = await svc.start_research(BUYER, {"keywords": ["gold"]})
    assert started["status"] == "RUNNING"
    for _ in range(100):
        run = await repo.get_run(started["id"])
        if run["status"] != "RUNNING":
            break
        await asyncio.sleep(0.1)
    assert run["status"] == "SUCCEEDED" and run["finished_at"]
    assert any(s["name"] == "victor-sport" and s["status"] == "ok" for s in run["sources"])


@pytest.mark.asyncio
async def test_a_failing_source_is_reported_and_the_rest_is_kept_never_a_fake_success(world):
    svc, repo, _ = world
    run = await research(make_service(repo, victor_handler(fail={"sitemap_products"})))
    assert run["status"] in ("PARTIAL", "FAILED")
    src = next(s for s in run["sources"] if s["name"] == "victor-sport")
    assert src["status"] == "error" and "sitemap" in src["reason"] and run["stats"]["created"] == 0
    run2 = await research(make_service(repo, victor_handler(fail={"challenger-9500-cd"})))
    assert run2["stats"]["created"] == 2
    none = SourcingService(repo, make_settings(), FakeCatalog(), providers=[], typed_providers=[])
    run3 = await none.start_research(BUYER, {"keywords": ["zz"]}, wait=True)
    assert run3["status"] in ("PARTIAL", "FAILED") and any("No research source is configured" in (s.get("reason") or "") for s in run3["sources"]) and run3["stats"]["created"] == 0


@pytest.mark.asyncio
async def test_a_broken_image_is_recorded_and_never_shown_as_the_product_picture(world):
    svc, repo, _ = world

    def handler(request):
        if request.url.host == CDN:
            return httpx.Response(404)
        return victor_handler()(request)
    run = await research(make_service(repo, handler), keywords=["thruster"])
    assert run["stats"]["created"] == 1 and run["stats"]["images_valid"] == 0
    tk = (await live_list(repo))[0]
    assert (await repo.list_images(tk["id"]))[0]["status"] == "BROKEN" and tk["image_url"] is None


# ------------------------------------------------------------------------------------------------ demand gaps are not products
@pytest.mark.asyncio
async def test_customer_searches_become_demand_gaps_not_products_and_cannot_be_approved_quoted_or_scored(world):
    svc, repo, _ = world
    await repo.record_demand("c1", "pickleball paddles", None, 3, "LOW_RESULTS", "pickleball")
    await repo.record_demand("c2", "pickleball", None, 3, "LOW_RESULTS", "pickleball")
    await research(svc, keywords=["gold"])
    gaps = (await repo.list_opportunities({"record_kind": "DEMAND_GAP"}, 0, 10))[0]
    assert len(gaps) == 1 and gaps[0]["provenance"] == "CUSTOMER_DEMAND_SIGNAL" and gaps[0]["record_kind"] == "DEMAND_GAP"
    assert "pickleball" not in " ".join(o["name"].lower() for o in await live_list(repo))
    ev = await repo.list_evidence(gaps[0]["id"])
    assert ev[0]["source_type"] == "CUSTOMER_SEARCHES" and '"c1"' not in json.dumps(ev)
    score = await svc.score(gaps[0]["id"])
    assert score["status"] == "AWAITING_PRODUCT_DISCOVERY" and score["opportunity"] is None
    with pytest.raises(SourcingError):
        await svc.change_status(APPROVER, gaps[0]["id"], "APPROVED_OPPORTUNITY", None, gaps[0]["version"])
    sup = (await repo.list_suppliers(None, None, None, 0, 5))[0][0]
    with pytest.raises(SourcingError) as e:
        await svc.add_quote(BUYER, gaps[0]["id"], {"supplier_id": sup["id"], "offered_product": "xx", "currency": "INR", "unit_price": 1.0, "source_kind": "QUOTE_ENTERED"})
    assert e.value.code == "NOT_A_PRODUCT"


# ------------------------------------------------------------------------------------------------ provenance isolation
@pytest.mark.asyncio
async def test_fixtures_and_unknown_records_are_excluded_from_live_lists_and_kpis_but_visible_on_request(world):
    svc, repo, store = world
    await svc.seed_demo(BUYER)
    await research(svc, keywords=["thruster"])
    names = {o["name"] for o in await live_list(repo)}
    assert names == {"Thruster K 100"}
    everything = {o["name"] for o in await live_list(repo, include_fixtures=True)}
    assert "Thruster K 100" in everything and len(everything) > 1
    d = await svc.dashboard()
    assert d["scope"] == "live" and d["total_products"] == 1 and d["with_supplier_quotes"] == 0 and d["margin_at_least_25_pct"] == 0
    assert d["excluded"]["fixtures"] >= 1
    assert [s["name"] for s in (await repo.list_suppliers(None, None, None, 0, 20))[0]] == ["VICTOR India"]


@pytest.mark.asyncio
async def test_a_buyer_can_classify_provenance_with_a_reason_but_never_into_live_external(world):
    svc, repo, _ = world
    opp = await repo.create_opportunity({"identity_key": "name:x|y", "name": "Legacy thing"}, "MANUAL", "t", provenance="UNKNOWN_PROVENANCE")
    with pytest.raises(SourcingError) as e:
        await svc.classify(BUYER, opp["id"], "LIVE_EXTERNAL_SOURCE", "trust me")
    assert e.value.code == "INVALID_PROVENANCE"
    with pytest.raises(SourcingError):
        await svc.classify(BUYER, opp["id"], "TEST_FIXTURE", "  ")
    out = await svc.classify(BUYER, opp["id"], "TEST_FIXTURE", "created by an old script")
    assert out["provenance"] == "TEST_FIXTURE"


@pytest.mark.asyncio
async def test_the_migration_backfills_legacy_rows_conservatively_without_deleting_anything(world):
    svc, repo, store = world
    pool = store.pool
    await pool.execute("ALTER TABLE src_opportunities ALTER COLUMN provenance DROP NOT NULL")
    ins = "INSERT INTO src_opportunities (id, identity_key, name, brand, origin, created_by, provenance) VALUES (gen_random_uuid(), $1, $2, $3, $4, 'legacy', NULL)"
    for key, name, brand, origin in (("bm:e2ebrand|e2eabc", "E2E Smash Racket abc", "E2EBrand", "MANUAL"), ("demand:protein supplements", "Protein Supplements", None, "DEMAND_SIGNAL"),
                                     ("bm:acme|x1", "Acme X1 Racket", "Acme", "MANUAL"), ("name:zzz", "Odd one", None, "LIVE_RESEARCH")):
        await pool.execute(ins, key, name, brand, origin)
    await store.ensure_schema()
    got = {r["name"]: r["provenance"] for r in await pool.fetch("SELECT name, provenance FROM src_opportunities")}
    assert got["E2E Smash Racket abc"] == "TEST_FIXTURE" and got["Protein Supplements"] == "CUSTOMER_DEMAND_SIGNAL" and got["Acme X1 Racket"] == "BUYER_ENTERED"
    assert got["Odd one"] == "UNKNOWN_PROVENANCE"
    assert (await pool.fetchval("SELECT count(*) FROM src_opportunities")) == 4
    # reconciliation of rows written by earlier versions: e2e-script "zorb..." gaps are test data, and a gap that was moved into a product-only state goes back to review, audited
    gap = await repo.create_opportunity({"identity_key": "demand:zorbballqqqqq", "name": "Zorbballqqqqq Paddles", "record_kind": "DEMAND_GAP"}, "DEMAND_SIGNAL", "t", provenance="CUSTOMER_DEMAND_SIGNAL")
    real = await repo.create_opportunity({"identity_key": "demand:protein bars", "name": "Protein Bars", "record_kind": "DEMAND_GAP"}, "DEMAND_SIGNAL", "t", provenance="CUSTOMER_DEMAND_SIGNAL")
    await pool.execute("UPDATE src_opportunities SET status = 'APPROVED_FOR_ONBOARDING' WHERE id = ANY($1::uuid[])", [uuid.UUID(gap["id"]), uuid.UUID(real["id"])])
    await store.ensure_schema()
    after = {r["name"]: (r["provenance"], r["status"]) for r in await pool.fetch("SELECT name, provenance, status FROM src_opportunities WHERE record_kind = 'DEMAND_GAP'")}
    assert after["Zorbballqqqqq Paddles"] == ("TEST_FIXTURE", "UNDER_REVIEW") and after["Protein Bars"] == ("CUSTOMER_DEMAND_SIGNAL", "UNDER_REVIEW")
    notes = await pool.fetch("SELECT from_status, actor_hash FROM src_decisions WHERE action = 'LEGACY_STATE_CORRECTED'")
    assert len(notes) == 2 and {n["from_status"] for n in notes} == {"APPROVED_FOR_ONBOARDING"} and {n["actor_hash"] for n in notes} == {"system:migration"}
    await store.ensure_schema()
    assert (await pool.fetchval("SELECT count(*) FROM src_decisions WHERE action = 'LEGACY_STATE_CORRECTED'")) == 2          # idempotent: nothing is corrected twice


# ------------------------------------------------------------------------------------------------ calculations belong to the product and offer they were made for
@pytest.mark.asyncio
async def test_two_products_with_different_costs_never_share_a_calculation_or_margin(world):
    svc, repo, _ = world
    await research(svc)
    items = {o["name"]: o for o in await live_list(repo)}
    a, b = items["Thruster K 100"], items["Gold Shuttle"]
    sup = await repo.create_supplier({"name": "Real Wholesaler Ltd", "type": "WHOLESALER"}, "MANUAL", "t", provenance="BUYER_ENTERED")
    qa = await svc.add_quote(BUYER, a["id"], {"supplier_id": sup["id"], "offered_product": "Thruster K 100", "currency": "INR", "unit_price": 1000.0, "shipping_cost": 5000.0, "source_kind": "QUOTE_ENTERED"})
    qb = await svc.add_quote(BUYER, b["id"], {"supplier_id": sup["id"], "offered_product": "Gold Shuttle", "currency": "INR", "unit_price": 1500.0, "shipping_cost": 9000.0, "source_kind": "QUOTE_ENTERED"})
    ra = await svc.calculate(BUYER, a["id"], {"quote_id": qa["id"], "quantity": 500, "selling_price": 1800, "save": True}, True)
    rb = await svc.calculate(BUYER, b["id"], {"quote_id": qb["id"], "quantity": 500, "selling_price": 1800, "save": True}, True)
    assert ra["values"]["gross_margin_pct"] != rb["values"]["gross_margin_pct"]
    assert ra["basis"]["opportunity_id"] == a["id"] and rb["basis"]["opportunity_id"] == b["id"] and ra["basis"]["quote_id"] == qa["id"]
    listed = {o["name"]: o for o in await live_list(repo)}
    assert listed["Thruster K 100"]["calc_quote_id"] == qa["id"] and listed["Gold Shuttle"]["calc_quote_id"] == qb["id"]
    assert listed["Thruster K 100"]["margin"] != listed["Gold Shuttle"]["margin"]
    assert listed["Thruster K 100"]["calc_basis"]["quantity"] == 500                                  # the basis is an object in lists, not a JSON string
    d = await svc.dashboard()                                                                        # regression: the dashboard reads that basis (it once crashed on a string)
    assert d["with_supplier_quotes"] == 2 and d["margin_at_least_25_pct"] in (0, 1, 2)
    with pytest.raises(SourcingError) as e:
        await svc.calculate(BUYER, b["id"], {"quote_id": qa["id"], "quantity": 500, "selling_price": 1800}, True)
    assert e.value.status == 404


# ------------------------------------------------------------------------------------------------ stages and readiness
@pytest.mark.asyncio
async def test_business_approval_is_separate_from_onboarding_approval_and_readiness_gates_the_proposal(world):
    svc, repo, _ = world
    await research(svc, keywords=["challenger"])
    cand = (await live_list(repo))[0]                                                               # no SKU/model on its page
    v = cand["version"]
    for t in ("UNDER_REVIEW", "SHORTLISTED"):
        v = (await svc.change_status(BUYER, cand["id"], t, None, v))["version"]
    with pytest.raises(SourcingError) as e:                                                         # the page gave no SKU/model: the business approval is refused, nothing is invented
        await svc.change_status(APPROVER, cand["id"], "APPROVED_OPPORTUNITY", "worth pursuing", v)
    assert "product identity" in e.value.message
    r = await svc.readiness(cand["id"])
    assert not r["ready"] and "Model number, manufacturer SKU or GTIN" in r["missing"]


@pytest.mark.asyncio
async def test_a_complete_record_produces_a_ready_proposal(world):
    svc, repo, _ = world
    cand = await a_candidate(svc, repo)
    v = cand["version"]
    for t in ("UNDER_REVIEW", "SHORTLISTED"):
        v = (await svc.change_status(BUYER, cand["id"], t, None, v))["version"]
    v = (await svc.change_status(APPROVER, cand["id"], "APPROVED_OPPORTUNITY", None, v))["version"]
    v = (await svc.change_status(APPROVER, cand["id"], "SUPPLIER_RESEARCH", None, v))["version"]    # leaving an approved opportunity is a senior decision
    v = (await svc.change_status(BUYER, cand["id"], "QUOTE_COMPARISON", None, v))["version"]
    sup = await repo.create_supplier({"name": "Real Wholesaler Ltd", "type": "WHOLESALER"}, "MANUAL", "t", provenance="BUYER_ENTERED")
    q = await svc.add_quote(BUYER, cand["id"], {"supplier_id": sup["id"], "offered_product": "x", "currency": "INR", "unit_price": 1000.0, "source_kind": "QUOTE_ENTERED", "valid_until": "2030-01-01"})
    await svc.calculate(BUYER, cand["id"], {"quote_id": q["id"], "quantity": 100, "selling_price": 2000, "save": True}, True)
    await svc.change_status(APPROVER, cand["id"], "APPROVED_FOR_ONBOARDING", None, v, quote_id=q["id"])
    prop = await svc.build_proposal(BUYER, cand["id"], {"quote_id": q["id"], "quantity": 100, "selling_price": 2000})
    assert prop["status"] == "READY_FOR_REVIEW" and prop["validation"]["ready"]


@pytest.mark.asyncio
async def test_data_survives_a_restart(world):
    svc, repo, store = world
    await research(svc)
    await store.close()
    store2 = await Store.connect(DSN, 30, 768)
    try:
        r2 = SourcingRepository(store2.pool)
        assert (await r2.list_opportunities({"record_kind": "PRODUCT_CANDIDATE"}, 0, 10))[1] == 3
    finally:
        await store2.close()


# ------------------------------------------------------------------------------------------------ REST API
def _client():
    return TestClient(create_app(make_settings(AI_DATABASE_URL=DSN, SOURCING_ALLOWED_HOSTS=f"{HOST},{CDN}", SOURCING_IMAGE_HOSTS=CDN)), raise_server_exceptions=False)


def _h(*roles):
    return {"Authorization": f"Bearer {make_token(roles=roles)}"}


def test_api_authorisation_scope_and_a_buyer_flow():
    async def reset():
        conn = await asyncpg.connect(DSN)
        for t in TABLES:
            await conn.execute(f"DROP TABLE IF EXISTS {t} CASCADE")
        await conn.close()
    asyncio.run(reset())
    with _client() as c:
        assert c.get("/v1/sourcing/dashboard").status_code == 401
        for role in ("CUSTOMER", "SUPPORT_EXECUTIVE"):
            assert c.get("/v1/sourcing/dashboard", headers=_h(role)).status_code == 403
            assert c.get("/v1/sourcing/opportunities", headers=_h(role)).status_code == 403
        buyer, boss = _h("PROCUREMENT_BUYER"), _h("CATEGORY_MANAGER")
        empty = c.get("/v1/sourcing/dashboard", headers=buyer).json()
        assert empty["total_products"] == 0 and empty["scope"] == "live"
        assert c.post("/v1/sourcing/demo/seed", headers=buyer).status_code == 201
        assert c.get("/v1/sourcing/opportunities", headers=buyer).json()["total"] == 0
        assert c.get("/v1/sourcing/opportunities?include_fixtures=true", headers=buyer).json()["total"] >= 1
        made = c.post("/v1/sourcing/opportunities", headers=buyer, json={"name": "API Test Racket", "brand": "victor sport", "model": "AT-1", "sport": "Badminton", "category": "Rackets", "is_test": True})
        assert made.status_code == 201 and made.json()["provenance"] == "TEST_FIXTURE" and made.json()["brand"] == "Victor"
        oid = made.json()["id"]
        assert c.get("/v1/sourcing/opportunities?q=API", headers=buyer).json()["total"] == 0
        img = c.post(f"/v1/sourcing/opportunities/{oid}/images", headers=buyer, json={"url": "https://tracker.example.net/x.png"})
        assert img.status_code == 422
        run = c.post("/v1/sourcing/runs", headers=buyer, json={"wait": True}).json()
        assert run["status"] in ("PARTIAL", "FAILED") and any(s["status"] == "unavailable" for s in run["sources"])
        dash = c.get("/v1/sourcing/dashboard", headers=boss).json()
        assert dash["total_products"] == 0 and dash["excluded"]["fixtures"] >= 2
