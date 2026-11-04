from __future__ import annotations

import httpx
import pytest

from app.sourcing.fetch import SafeFetcher
from app.sourcing.typed_providers import build_typed_providers
from app.sourcing.victor import VictorSportProvider, parse_distributors
from tests.sourcing.fixtures import CDN, DISTRIBUTORS, HOST, public_resolver, victor_handler


def provider(handler=None, max_products=10, **kw):
    f = SafeFetcher([HOST, CDN], transport=httpx.MockTransport(handler or victor_handler()), resolver=public_resolver, min_interval_s=0)
    return VictorSportProvider(f, max_products=max_products, **kw), f


@pytest.mark.asyncio
async def test_extracts_real_structure_products_with_specs_images_and_model_codes():
    p, _ = provider()
    r = await p.fetch({"sport": "Badminton"})
    assert r.status == "ok" and r.stats["selected"] == 4
    by = {c.name: c for c in r.candidates}
    assert set(by) == {"THRUSTER K 100", "CHALLENGER 9500 CD", "GOLD SHUTTLE"}             # the empty page is invalid, not a product
    tk = by["THRUSTER K 100"]
    assert tk.brand == "Victor" and tk.mfr_sku == "TK-100" and tk.model == "TK-100" and tk.category == "Rackets" and tk.sport == "Badminton" and tk.source_type == "OFFICIAL_BRAND_PAGE"
    assert tk.specs["frame_material"]["value"] == "Graphite + Resin" and tk.specs["release_note"]["value"].startswith("2015 NEW")
    assert tk.images and tk.images[0].match_level == "EXACT_MODEL" and tk.identity_confirmed and tk.url.endswith("/product/thruster-k-11")
    assert tk.source_title.startswith("THRUSTER K 100 |")
    assert by["CHALLENGER 9500 CD"].mfr_sku is None and by["CHALLENGER 9500 CD"].model is None        # no SKU on the page: left empty, never invented
    assert by["GOLD SHUTTLE"].category == "Shuttlecocks"
    assert r.stats["invalid"] == 1 and r.skipped and "not a product page" in r.skipped[0]["why"]


@pytest.mark.asyncio
async def test_keywords_and_category_narrow_the_pages_read():
    p, f = provider()
    r = await p.fetch({"keywords": ["challenger"]})
    assert [c.name for c in r.candidates] == ["CHALLENGER 9500 CD"] and r.stats["pages_requested"] == 1


@pytest.mark.asyncio
async def test_supplier_discovery_is_limited_to_the_requested_country_and_says_it_is_unverified():
    p, _ = provider(supplier_countries=("India",))
    r = await p.fetch({})
    assert [s.name for s in r.suppliers] == ["VICTOR India"]
    s = r.suppliers[0]
    assert s.country == "India" and s.email == "info@victorsport.in" and s.website is None and "NOT verified" in s.source_note and s.source_url.endswith("/page/distributors")
    both, _ = provider(supplier_countries=("India", "China"))
    assert len((await both.fetch({})).suppliers) == 2


def test_parse_distributors_reads_cards():
    cards = parse_distributors(DISTRIBUTORS)
    assert [c["country"] for c in cards] == ["China", "India"] and cards[1]["phone"] == "+91-9205688015" and cards[1]["address"].startswith("PO Begumpur")


@pytest.mark.asyncio
async def test_other_brands_or_sports_are_reported_not_faked():
    p, f = provider()
    assert (await p.fetch({"brand": "Yonex"})).reason.startswith("This source covers the Victor brand only")
    assert (await p.fetch({"sport": "Football"})).reason == "This source covers badminton only."
    assert f.requests == 0


@pytest.mark.asyncio
async def test_a_down_source_is_an_error_with_a_reason_and_a_partial_failure_keeps_the_rest():
    p, _ = provider(victor_handler(fail={"sitemap_products"}))
    r = await p.fetch({})
    assert r.status == "error" and "sitemap" in r.reason and r.candidates == []
    p2, _ = provider(victor_handler(fail={"challenger-9500-cd"}))
    r2 = await p2.fetch({})
    assert r2.status == "ok" and len(r2.candidates) == 2 and "1 of 4" in r2.reason and any("challenger" in s["url"] for s in r2.skipped)


@pytest.mark.asyncio
async def test_robots_disallowing_the_site_stops_the_run_instead_of_fetching():
    def handler(request):
        if request.url.path == "/robots.txt":
            return httpx.Response(200, text="User-agent: *\nDisallow: /\n", headers={"content-type": "text/plain"})
        return victor_handler()(request)
    p, f = provider(handler)
    r = await p.fetch({})
    assert r.status == "error" and "ROBOTS_DISALLOWED" in r.reason and r.candidates == []


def test_typed_provider_configuration_requires_allowed_hosts_and_reports_problems():
    f = SafeFetcher([HOST], resolver=public_resolver)
    ok, problems = build_typed_providers('[{"type":"victor_sport","max_products":5}]', f, [HOST])
    assert len(ok) == 1 and problems == []
    none, problems = build_typed_providers('[{"type":"victor_sport"}]', f, [])
    assert none == [] and "needs www.victorsport.com in SOURCING_ALLOWED_HOSTS" in problems[0]
    assert build_typed_providers('[{"type":"nope"}]', f, [HOST])[1][0].startswith("provider #1: unknown type")
    assert build_typed_providers("not json", f, [HOST])[1] == ["SOURCING_PROVIDERS is not a JSON list"]
    assert build_typed_providers('[{"type":"victor_sport","enabled":false}]', f, [HOST]) == ([], [])
