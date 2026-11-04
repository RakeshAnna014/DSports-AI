"""Budget policy, hard constraints, scoring and diversity: pure functions, no I/O."""
from __future__ import annotations

import pytest

from app.reco.policy import (Band, BudgetPolicy, RecoContext, Scenario, bands_for, diversify, hard_violation, rank, score, widest)

POLICY = BudgetPolicy()


def card(pid: str, price: float, *, sport="Cricket", category="Grips", brand="SG", role="grip", in_stock=True, mrp=None, rating=None, attrs=None) -> dict:
    return {"product_id": pid, "name": f"Product {pid}", "sport": sport, "category": category, "brand": brand, "price": price, "mrp": mrp,
            "in_stock": in_stock, "attributes": attrs or {}, "rating": rating, "_role": role}


BAT = card("bat", 10_000, category="Bats", role="bat", brand="MRF")


# ------------------------------------------------------------------------------------------------ complementary ceiling

def test_default_complementary_ceiling_is_twenty_percent_of_the_effective_price():
    band = bands_for(Scenario.COMPLEMENTARY, 10_000, None, POLICY)[0]
    assert band.high == 2_000 and band.source == "policy" and band.pct == 0.20
    assert "20%" in band.label and "₹2,000" in band.label


@pytest.mark.parametrize("price,inside", [(0.01, True), (1_999.99, True), (2_000, True), (2_000.01, False), (2_500, False)])
def test_ceiling_boundary_is_inclusive(price, inside):
    assert bands_for(Scenario.COMPLEMENTARY, 10_000, None, POLICY)[0].contains(price) is inside


def test_the_percentage_is_configuration_not_a_constant():
    ten = BudgetPolicy(complementary_ceiling_pct=0.10, fallback_pcts=(0.15,))
    assert bands_for(Scenario.COMPLEMENTARY, 10_000, None, ten)[0].high == 1_000
    assert [b.high for b in bands_for(Scenario.COMPLEMENTARY, 10_000, None, ten)] == [1_000, 1_500]


def test_fallback_bands_are_ordered_labelled_and_never_below_the_base():
    bands = bands_for(Scenario.COMPLEMENTARY, 10_000, None, POLICY)
    assert [b.high for b in bands] == [2_000, 3_000, 4_000]
    assert [b.source for b in bands] == ["policy", "fallback", "fallback"]
    assert all("Widened" in b.label for b in bands[1:])
    assert widest(bands).high == 4_000


def test_an_explicit_budget_takes_precedence_and_is_never_widened():
    bands = bands_for(Scenario.COMPLEMENTARY, 10_000, 1_200, POLICY)
    assert len(bands) == 1 and bands[0].high == 1_200 and bands[0].source == "explicit"
    assert not bands[0].contains(1_200.01)


def test_similar_band_is_a_symmetric_tolerance_around_the_reference_price():
    band = bands_for(Scenario.SIMILAR, 10_000, None, POLICY)[0]
    assert (band.low, band.high) == (7_500, 12_500)


def test_upgrade_band_is_above_the_reference_and_respects_an_explicit_ceiling():
    band = bands_for(Scenario.UPGRADE, 10_000, None, POLICY)[0]
    assert (band.low, band.high) == (11_500, 20_000)
    capped = bands_for(Scenario.UPGRADE, 10_000, 15_000, POLICY)[0]
    assert (capped.low, capped.high) == (11_500, 15_000)


def test_personalized_band_comes_from_the_profile_price_range_only():
    assert bands_for(Scenario.PERSONALIZED, None, None, POLICY, (500, 8_000))[0].source == "profile"
    assert bands_for(Scenario.PERSONALIZED, None, None, POLICY)[0].source == "none"


# ------------------------------------------------------------------------------------------------ hard constraints

def ctx(scn=Scenario.COMPLEMENTARY, **kw) -> RecoContext:
    return RecoContext(scenario=scn, reference=BAT, reference_role=("Cricket", "bat"), **kw)


@pytest.mark.parametrize("candidate,reason", [
    (card("x", 100, in_stock=False), "out_of_stock"),
    (card("x", 0), "no_price"),
    (card("bat", 100), "is_reference"),
    (card("x", 100, sport="Football"), "incompatible_sport"),
    (card("x", 100, role="bat"), "not_complementary"),
    (card("x", 100, role=None), "not_complementary"),
])
def test_hard_constraints_reject(candidate, reason):
    assert hard_violation(candidate, ctx()) == reason


def test_already_bought_products_are_never_recommended():
    assert hard_violation(card("owned", 100), ctx(purchased_ids={"owned"})) == "already_bought"


def test_a_complement_in_the_same_sport_with_a_different_role_is_allowed():
    assert hard_violation(card("grip1", 100), ctx()) is None


def test_hard_constraints_do_not_depend_on_the_budget_band():
    """A fallback band can never make an out-of-stock or incompatible product legal: the check does not even take a band."""
    assert hard_violation(card("x", 2_500, in_stock=False), ctx()) == "out_of_stock"
    assert hard_violation(card("x", 2_500, sport="Football"), ctx()) == "incompatible_sport"


def test_similar_requires_same_category_and_role_and_upgrade_requires_a_higher_price():
    sim = ctx(Scenario.SIMILAR)
    assert hard_violation(card("b2", 9_000, category="Bats", role="bat"), sim) is None
    assert hard_violation(card("g", 9_000, category="Grips", role="grip"), sim) == "not_comparable"
    up = ctx(Scenario.UPGRADE)
    assert hard_violation(card("b3", 9_500, category="Bats", role="bat"), up) == "not_an_upgrade"
    assert hard_violation(card("b4", 14_000, category="Bats", role="bat"), up) is None


def test_adult_only_products_are_never_shown_for_a_child_profile():
    child = ctx(profile={"age_band": "CHILD_8_12", "personalization_enabled": True, "preferences": {}})
    assert hard_violation(card("x", 100, attrs={"ageGroup": "adult"}), child) == "not_for_child_profile"
    assert hard_violation(card("y", 100, attrs={"ageGroup": "junior"}), child) is None


def test_frequently_bought_together_skips_kit_rules_but_not_stock():
    fbt = ctx(Scenario.FREQUENTLY_BOUGHT_TOGETHER)
    assert hard_violation(card("m", 100, sport="Fitness", role=None), fbt) is None
    assert hard_violation(card("m", 100, sport="Fitness", in_stock=False), fbt) == "out_of_stock"


# ------------------------------------------------------------------------------------------------ scoring

def test_profile_preferences_raise_the_score_and_explain_why():
    profile = {"age_band": "ADULT", "personalization_enabled": True, "preferences": {"sports": ["Cricket"], "brands": ["SG"], "categories": ["Grips"],
                                                                                    "usage_level": "intermediate", "price_min": 100, "price_max": 5_000}}
    c = card("g", 400, attrs={"skillLevel": "intermediate"})
    with_profile, why = score(c, ctx(profile=profile))
    without, _ = score(c, ctx())
    assert with_profile > without
    assert any("sport in your profile" in w for w in why) and any("brand you follow" in w for w in why) and any("price range in your profile" in w for w in why)


def test_preferences_are_ignored_when_personalization_is_switched_off():
    off = {"age_band": "ADULT", "personalization_enabled": False, "preferences": {"sports": ["Cricket"], "brands": ["SG"]}}
    assert score(card("g", 400), ctx(profile=off))[0] == score(card("g", 400), ctx())[0]


def test_discount_reason_is_only_given_when_the_original_price_is_really_higher():
    _, real = score(card("g", 400, mrp=500), ctx())
    _, fake = score(card("g", 400, mrp=400), ctx())
    _, none = score(card("g", 400, mrp=300), ctx())
    assert any("20% off" in w for w in real) and not any("off" in w for w in fake) and not any("off" in w for w in none)


def test_similar_scores_closer_prices_higher():
    sim = ctx(Scenario.SIMILAR)
    near, _ = score(card("a", 9_800, category="Bats", role="bat"), sim)
    far, _ = score(card("b", 7_600, category="Bats", role="bat"), sim)
    assert near > far


def test_co_purchase_evidence_is_scored_and_stated_with_its_real_count():
    fbt = ctx(Scenario.FREQUENTLY_BOUGHT_TOGETHER, copurchase_orders={"g": 4})
    s, why = score(card("g", 400), fbt)
    assert any("in 4 orders" in w for w in why)
    assert s > score(card("h", 400), fbt)[0]


def test_ranking_is_deterministic_with_stable_tie_breaks():
    items = [card("b", 300), card("a", 300), card("c", 200)]
    first = [c["product_id"] for c in rank(items, ctx())]
    assert first == [c["product_id"] for c in rank(list(reversed(items)), ctx())]
    assert first[0] == "c"          # equal scores: the lower price first, then name


def test_diversity_limits_one_brand_and_one_category():
    ranked = rank([card(str(i), 100 + i, brand="SG", category="Grips") for i in range(5)] + [card("z", 900, brand="MRF", category="Balls")], ctx())
    out = diversify(ranked, 4, 2)
    assert sum(1 for c in out if c["brand"] == "SG") == 2 and any(c["product_id"] == "z" for c in out)


def test_band_contains_handles_open_ends():
    assert Band(None, None, "none").contains(1) and Band(10, None, "x").contains(10) and not Band(10, None, "x").contains(9.99)
