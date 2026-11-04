from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.sourcing import normalize as n
from app.sourcing import scoring as s

NOW = datetime(2026, 10, 15, 12, 0, tzinfo=timezone.utc)


def test_model_numbers_compare_across_punctuation_and_case():
    assert n.normalize_model("AX-88 D") == n.normalize_model("ax88d")
    assert n.identity_key("Yonex Astrox 88 D", "YONEX", "AX-88 D") == n.identity_key("Astrox 88D Pro", "Yonex", "ax88d")


def test_gtin_checksum():
    assert n.valid_gtin("4006381333931") == "4006381333931"
    assert n.valid_gtin("4006381333932") is None
    assert n.identity_key("x", gtin="4006381333931") == "gtin:4006381333931"


def test_identity_falls_back_to_name_tokens_order_independent():
    assert n.identity_key("Pro Badminton Racket Carbon") == n.identity_key("carbon racket badminton")


def test_exact_catalogue_match_by_brand_and_model_and_by_gtin():
    cat = [{"id": "p1", "name": "Yonex Astrox 88D", "brand": "Yonex", "model": "AX88D"}, {"id": "p2", "name": "Other", "gtin": "4006381333931"}]
    assert n.match_catalogue({"name": "whatever", "brand": "YONEX", "model": "ax-88d"}, cat).level == "EXACT"
    assert n.match_catalogue({"name": "x", "gtin": "4006381333931"}, cat).product_id == "p2"


def test_similar_name_is_only_similar_never_exact():
    cat = [{"id": "p1", "name": "Carbon Badminton Racket Lite", "brand": "Li-Ning"}]
    m = n.match_catalogue({"name": "Carbon Badminton Racket Ultra", "brand": "Li-Ning"}, cat)
    assert m.level in ("SIMILAR", "PROBABLE_DUPLICATE") and m.product_id == "p1" and "similar" in m.reason.lower()
    assert n.match_catalogue({"name": "Football Boots Size 9"}, cat).level == "NONE"


def test_duplicates_identical_key_and_near_duplicate_needing_review():
    existing = [{"id": "o1", "name": "Carbon Badminton Racket Ultra Light", "brand": "Li-Ning", "identity_key": "name:li ning|x"}]
    key, possible = n.find_duplicates({"name": "Carbon Badminton Racket Ultra Light 2", "brand": "Li-Ning"}, existing)
    assert key is None and possible and possible[0]["id"] == "o1"
    key2, none = n.find_duplicates({"name": "Carbon Badminton Racket Ultra Light 2", "brand": "Yonex"}, existing)
    assert key2 is None and none == []                                     # a different brand is never a near-duplicate


@pytest.mark.parametrize("url", ["ftp://shop.example.com/a", "https://user:pw@shop.example.com/a", "https://shop.example.com:8443/a", "https://evil.example.org/a",
                                 "https://127.0.0.1/a", "file:///etc/passwd", "https:///nohost"])
def test_unsafe_urls_are_refused(url):
    with pytest.raises(n.UnsafeUrl):
        n.check_url(url, ["example.com", "127.0.0.1"], resolver=lambda h: ["93.184.216.34"])


def test_allowed_host_resolving_to_private_address_is_refused():
    for ip in ("10.0.0.5", "192.168.1.1", "169.254.169.254", "::1", "127.0.0.1"):
        with pytest.raises(n.UnsafeUrl):
            n.check_url("https://shop.example.com/p", ["example.com"], resolver=lambda h, ip=ip: [ip])


def test_public_allowed_host_is_accepted_and_one_bad_address_among_many_blocks():
    assert n.check_url("https://shop.example.com/p", ["example.com"], resolver=lambda h: ["93.184.216.34"]) == "https://shop.example.com/p"
    with pytest.raises(n.UnsafeUrl):
        n.check_url("https://shop.example.com/p", ["example.com"], resolver=lambda h: ["93.184.216.34", "10.1.1.1"])


# ------------------------------------------------------------------------------------------------ scoring
def good_signals(**kw):
    sig = {"unmet_searches": 12, "unmet_customers": 8, "catalogue_match": "NONE", "gross_margin_pct": 32.0, "financial": {"status": "QUOTED"},
           "evidence": [{"source": "brand-site", "verified_at": NOW - timedelta(days=5)}, {"source": "distributor", "verified_at": NOW - timedelta(days=10)}],
           "quotes": [{"status": "ACTIVE", "verification": "QUOTE_ENTERED", "authorised": True, "lead_time_days": 20, "moq_cost": 50000},
                      {"status": "ACTIVE", "verification": "VERIFIED", "authorised": True, "lead_time_days": 30, "moq_cost": 80000}], "working_capital_limit": 500000}
    sig.update(kw)
    return sig


def test_strong_opportunity_scores_high_with_explanations_and_separate_risk():
    r = s.score(good_signals(), now=NOW)
    assert r["status"] == "SCORED" and r["opportunity"] >= 70 and r["priority"] == "HIGH" and r["confidence"] in ("MEDIUM", "HIGH")
    assert r["supplier_risk"]["level"] == "LOW" and r["reasons"] and r["model_version"] == "v2"
    assert abs(sum(d["weight"] for d in r["dimensions"]) - sum(r["weights"][d["dimension"]] for d in r["dimensions"])) < 0.01


def test_missing_data_is_not_zero_and_is_listed():
    r = s.score(good_signals(gross_margin_pct=None, unmet_searches=None), now=NOW)
    assert "margin" in r["missing"] and "unmet_demand" in r["missing"]
    assert all(d["dimension"] not in ("margin", "unmet_demand") for d in r["dimensions"])


def test_too_little_data_gives_no_number():
    r = s.score({"catalogue_match": "NONE"}, now=NOW)
    assert r["status"] == "INSUFFICIENT_EVIDENCE" and r["opportunity"] is None and r["priority"] is None
    assert s.score({}, now=NOW)["status"] == "INSUFFICIENT_EVIDENCE"


def test_high_score_cannot_hide_high_supplier_risk():
    risky = good_signals(quotes=[{"status": "ACTIVE", "verification": "PUBLIC_LISTING", "authorised": None, "lead_time_days": None}])
    r = s.score(risky, now=NOW)
    assert r["supplier_risk"]["level"] in ("MEDIUM", "HIGH")
    assert r["priority"] != "HIGH" or r["supplier_risk"]["level"] != "HIGH"


def test_expired_quotes_are_high_risk_and_stale_evidence_lowers_confidence():
    r = s.score(good_signals(quotes=[{"status": "EXPIRED", "verification": "QUOTE_ENTERED"}]), now=NOW)
    assert r["supplier_risk"]["level"] == "HIGH"
    stale = s.score(good_signals(evidence=[{"source": "a", "verified_at": NOW - timedelta(days=400)}], quotes=[]), now=NOW)
    fresh = s.score(good_signals(quotes=[]), now=NOW)
    ev = lambda r: next(d for d in r["dimensions"] if d["dimension"] == "market_evidence")["score"]  # noqa: E731
    assert ev(stale) < ev(fresh)


def test_already_sold_product_is_not_a_gap():
    r = s.score(good_signals(catalogue_match="EXACT"), now=NOW)
    assert next(d for d in r["dimensions"] if d["dimension"] == "catalogue_gap")["score"] == 0.0


def test_no_quotes_means_unknown_risk_and_next_action_asks_for_one():
    r = s.score({"unmet_searches": 5, "unmet_customers": 4, "catalogue_match": "NONE", "evidence": [{"source": "x", "verified_at": NOW}]}, now=NOW)
    assert r["supplier_risk"]["level"] == "UNKNOWN" and "quotation" in r["next_action"].lower()


# ------------------------------------------------------------------------------------------------ model v2: gaps, financial basis, provenance, identity
def test_a_demand_gap_is_a_signal_awaiting_product_discovery_and_gets_no_score():
    r = s.score({"record_kind": "DEMAND_GAP", "unmet_searches": 9, "unmet_customers": 5, "catalogue_match": "NONE"}, now=NOW)
    assert r["status"] == "AWAITING_PRODUCT_DISCOVERY" and r["opportunity"] is None and r["priority"] is None
    assert "demand signal" in r["reasons"][0] and "not a product" in r["reasons"][0] and r["supplier_risk"]["level"] == "UNKNOWN"


def test_margin_counts_only_with_a_financial_basis_and_estimated_is_labelled():
    base = good_signals()
    no_basis = s.score({k: v for k, v in base.items() if k != "financial"}, now=NOW)
    assert "margin" in no_basis["missing"] and no_basis["financial_status"] == "NONE"            # a margin number with no stated basis is not used
    est = s.score({**base, "financial": {"status": "ESTIMATED"}}, now=NOW)
    assert est["financial_status"] == "ESTIMATED" and "ESTIMATED from a public listing" in next(d for d in est["dimensions"] if d["dimension"] == "margin")["why"]
    assert s.score({**base, "financial": {"status": "INCOMPLETE"}}, now=NOW)["financial_status"] == "INCOMPLETE"


def test_fixture_scores_are_labelled_and_unknown_provenance_never_reaches_high_confidence():
    fx = s.score(good_signals(provenance="TEST_FIXTURE"), now=NOW)
    assert fx["reasons"][0].startswith("Fixture data") and fx["provenance"] == "TEST_FIXTURE"
    unk = s.score(good_signals(provenance="UNKNOWN_PROVENANCE"), now=NOW)
    assert unk["confidence"] == "LOW" and unk["priority"] != "HIGH" and any("Provenance is unknown" in x for x in unk["reasons"])


def test_product_identity_dimension_rewards_a_complete_record_and_cites_evidence_ids():
    full = {"brand": True, "identifier": True, "description": True, "specs": 5, "image": "EXACT_MODEL"}
    none = {"brand": False, "identifier": False, "description": False, "specs": 0, "image": None}
    f = s.score(good_signals(identity=full, evidence=[{"id": 11, "source": "brand", "verified_at": NOW, "source_type": "OFFICIAL_BRAND_PAGE"}]), now=NOW)
    n = s.score(good_signals(identity=none), now=NOW)
    score_of = lambda r: next(d["score"] for d in r["dimensions"] if d["dimension"] == "product_identity")  # noqa: E731
    assert score_of(f) == 100.0 and score_of(n) == 0.0 and f["evidence_refs"] == [11]
    assert "official brand" in next(d["why"] for d in f["dimensions"] if d["dimension"] == "market_evidence")


def test_a_single_weak_source_with_nothing_else_is_not_enough_evidence():
    r = s.score({"catalogue_match": "NONE", "evidence": [{"source": "x", "verified_at": NOW}]}, now=NOW)
    assert r["status"] == "INSUFFICIENT_EVIDENCE" and r["opportunity"] is None and "Not enough evidence" in r["reasons"][-1]


def test_catalogue_match_levels_change_the_gap_score():
    sc = lambda lvl: next(d["score"] for d in s.score(good_signals(catalogue_match=lvl), now=NOW)["dimensions"] if d["dimension"] == "catalogue_gap")  # noqa: E731
    assert sc("NONE") > sc("SIMILAR") > sc("REVIEW") > sc("PROBABLE_DUPLICATE") > sc("EXACT") == 0.0