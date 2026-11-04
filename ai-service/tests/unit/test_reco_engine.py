"""The recommendation pipeline end to end with a scripted DSports (no network, no model)."""
from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from app.config.settings import Settings
from app.errors import ApiException
from app.reco.engine import RecoRequest, recommend_with_scenario
from app.reco.policy import Scenario

BAT_ID = "00000000-0000-0000-0000-0000000000a1"
OTHER_ID = "00000000-0000-0000-0000-0000000000a2"
PROFILE_ID = "00000000-0000-0000-0000-0000000000b1"
FOREIGN_PROFILE = "00000000-0000-0000-0000-0000000000b9"


def pid(n: int) -> str:
    return f"00000000-0000-0000-0000-{n:012d}"


def product(id_: str, name: str, price: float, *, category: str, role: str, sport="Cricket", brand="SG", in_stock=True, mrp=None, attrs=None, qty=50) -> dict:
    return {"product_id": id_, "name": name, "brand": brand, "category": category, "sport": sport, "price": price, "mrp": mrp or price, "in_stock": in_stock,
            "available_quantity": qty, "attributes": {"kitRole": role, **(attrs or {})}, "image_url": f"/img/{name}.png", "rating": {"average": 4.4, "count": 12}}


class FakeResult:
    def __init__(self, ok: bool, data: Any = None, code: str | None = None):
        self.ok, self.data, self.error_code = ok, data, code


class FakeRuntime:
    """Answers the tools the pipeline calls from scripted data and records every call."""

    def __init__(self, catalogue: list[dict], *, signed_in=True, profile: dict | None = None, orders: list[dict] | None = None,
                 pairs: list[dict] | None = None, settings: Settings | None = None):
        self.catalogue = {p["product_id"]: p for p in catalogue}
        self.principal = SimpleNamespace(token="jwt") if signed_in else None
        self.profile, self.orders, self.pairs = profile, orders or [], pairs or []
        self.services = SimpleNamespace(settings=settings or Settings())
        self.ctx = SimpleNamespace(max_calls=8, calls=0)
        self.log: list[tuple[str, dict]] = []

    async def call(self, name: str, args: dict) -> FakeResult:
        self.log.append((name, args))
        self.ctx.calls += 1
        if name == "get_product":
            p = self.catalogue.get(str(args["product_id"]))
            return FakeResult(True, {"product": dict(p)}) if p else FakeResult(False, code="NOT_FOUND")
        if name == "search_products":
            hits = [dict(p) for p in self.catalogue.values() if all((args.get(k) is None) or (str(p.get(k, "")).lower() == str(args[k]).lower())
                                                                    for k in ("sport", "category"))
                    and (args.get("max_price") is None or p["price"] <= args["max_price"]) and (args.get("min_price") is None or p["price"] >= args["min_price"])]
            return FakeResult(True, {"products": hits[: args.get("limit", 5)]})
        if name == "get_shopper_profile":
            if args.get("profile_id") and str(args["profile_id"]) == FOREIGN_PROFILE:
                return FakeResult(False, code="NOT_FOUND")
            return FakeResult(True, {"profile": self.profile}) if self.profile else FakeResult(False, code="NOT_FOUND")
        if name == "get_orders":
            return FakeResult(True, {"orders": self.orders})
        if name == "get_co_purchases":
            return FakeResult(True, {"product_id": args["product_id"], "minimum_orders": 2, "pairs": self.pairs})
        raise AssertionError(f"unexpected tool {name}")


BAT = product(BAT_ID, "MRF Genius Bat", 10_000, category="Bat", role="bat", brand="MRF")


def cricket_catalogue(extra: list[dict] | None = None) -> list[dict]:
    """Uses the real Cricket kit roles and categories from kits.json (bat, batting-gloves, batting-pads, helmet, arm-guard, ball ...)."""
    return [BAT,
            product(pid(1), "SG Arm Guard", 350, category="Accessories", role="arm-guard", mrp=500),
            product(pid(2), "Leather Ball", 450, category="Ball", role="ball", brand="Kookaburra"),
            product(pid(3), "Thigh Guard", 600, category="Accessories", role="thigh-guard", brand="Cosco"),
            product(pid(4), "Premium Gloves", 1_999, category="Gloves", role="batting-gloves", brand="Adidas"),
            product(pid(5), "Pro Helmet", 2_400, category="Helmet", role="helmet", brand="Masuri"),      # above the 20% ceiling, below 30%
            product(pid(6), "Elite Pads", 3_500, category="Gloves", role="batting-pads", brand="Puma"),
            product(pid(7), "Out Of Stock Ball", 120, category="Ball", role="ball", in_stock=False),
            *(extra or [])]


def run(rt: FakeRuntime, **kw):
    return recommend_with_scenario(rt, RecoRequest(**kw))


# ------------------------------------------------------------------------------------------------ complementary

async def test_complementary_default_ceiling_is_20_percent_of_the_reference_price():
    out = await run(FakeRuntime(cricket_catalogue()), reference_product_id=BAT_ID, limit=6)
    prices = [i["price"] for i in out["items"]]
    assert out["scenario"] == "COMPLEMENTARY" and prices and max(prices) <= 2_000
    assert out["budget"]["applied"]["high"] == 2_000 and out["budget"]["applied"]["source"] == "policy" and out["budget"]["fallback_used"] is False
    assert all(i["product_id"] not in (BAT_ID, pid(7)) for i in out["items"])         # the reference and the out-of-stock grip
    assert out["meta"]["llm_tokens"] == 0


async def test_every_item_has_price_availability_discount_and_factual_reasons():
    out = await run(FakeRuntime(cricket_catalogue()), reference_product_id=BAT_ID, limit=6)
    guard = next(i for i in out["items"] if i["product_id"] == pid(1))
    assert guard["price"] == 350 and guard["original_price"] == 500 and guard["discount_pct"] == 30 and guard["availability"] == "In stock"
    assert guard["image_url"] and guard["reasons"][0].startswith("Goes with MRF Genius Bat")
    assert any("30% off" in r for r in guard["reasons"])
    plain = next(i for i in out["items"] if i["product_id"] == pid(2))
    assert plain["original_price"] is None and plain["discount_pct"] is None            # no invented discount


async def test_too_few_items_widen_the_band_with_a_clear_label_but_never_relax_stock_or_compatibility():
    cat = [BAT, product(pid(1), "SG Arm Guard", 150, category="Accessories", role="arm-guard"), product(pid(5), "Pro Helmet", 2_400, category="Helmet", role="helmet"),
           product(pid(7), "OOS Gloves", 2_500, category="Gloves", role="batting-gloves", in_stock=False),
           product(pid(8), "Football", 2_600, category="Ball", role="ball", sport="Football")]
    out = await run(FakeRuntime(cat), reference_product_id=BAT_ID, limit=6)
    ids = {i["product_id"] for i in out["items"]}
    assert out["budget"]["fallback_used"] is True and out["budget"]["applied"]["pct"] == 0.30 and "Widened" in out["budget"]["applied"]["label"]
    assert pid(5) in ids and pid(7) not in ids and pid(8) not in ids
    assert [t["source"] for t in out["budget"]["tried"]] == ["policy", "fallback"]


async def test_an_explicit_customer_budget_wins_over_the_percentage_and_is_never_widened():
    out = await run(FakeRuntime(cricket_catalogue()), reference_product_id=BAT_ID, budget=500, limit=6)
    assert out["budget"]["applied"]["source"] == "explicit" and out["budget"]["applied"]["high"] == 500
    assert {i["product_id"] for i in out["items"]} == {pid(1), pid(2)} and out["budget"]["fallback_used"] is False
    nothing = await run(FakeRuntime(cricket_catalogue()), reference_product_id=BAT_ID, budget=50)
    assert nothing["items"] == [] and "budget" in nothing["message"].lower()


async def test_the_percentage_is_configurable():
    s = Settings(AI_RECO_COMPLEMENTARY_CEILING_PCT="0.05", AI_RECO_COMPLEMENTARY_FALLBACK_PCTS="")
    out = await run(FakeRuntime(cricket_catalogue(), settings=s), reference_product_id=BAT_ID, limit=6)
    assert out["budget"]["applied"]["high"] == 500 and all(i["price"] <= 500 for i in out["items"])


async def test_already_purchased_products_are_excluded():
    orders = [{"items": [{"product_id": pid(1), "unit_price": 150}]}]
    out = await run(FakeRuntime(cricket_catalogue(), orders=orders, profile=None), reference_product_id=BAT_ID, limit=6)
    assert pid(1) not in {i["product_id"] for i in out["items"]}


# ------------------------------------------------------------------------------------------------ other scenarios

async def test_similar_stays_inside_the_tolerance_and_in_the_same_category():
    cat = [BAT, product(pid(11), "Alt Bat A", 9_000, category="Bat", role="bat", brand="SS"), product(pid(12), "Alt Bat B", 13_000, category="Bat", role="bat"),
           product(pid(13), "Cheap Bat", 3_000, category="Bat", role="bat"), product(pid(14), "Pads", 9_000, category="Gloves", role="batting-pads")]
    out = await run(FakeRuntime(cat), reference_product_id=BAT_ID, scenario=Scenario.SIMILAR)
    assert {i["product_id"] for i in out["items"]} == {pid(11)} and out["budget"]["applied"]["low"] == 7_500


async def test_upgrade_shows_only_pricier_comparable_products():
    cat = [BAT, product(pid(11), "Pro Bat", 14_000, category="Bat", role="bat"), product(pid(12), "Cheaper", 8_000, category="Bat", role="bat"),
           product(pid(13), "Too Dear", 30_000, category="Bat", role="bat")]
    out = await run(FakeRuntime(cat), reference_product_id=BAT_ID, scenario=Scenario.UPGRADE)
    assert [i["product_id"] for i in out["items"]] == [pid(11)] and "more than" in " ".join(out["items"][0]["reasons"])


async def test_frequently_bought_together_needs_real_evidence_and_never_invents_it():
    none = await run(FakeRuntime(cricket_catalogue(), pairs=[]), reference_product_id=BAT_ID, scenario=Scenario.FREQUENTLY_BOUGHT_TOGETHER)
    assert none["items"] == [] and "not enough purchase history" in none["message"] and none["suggested_scenario"] == "COMPLEMENTARY"
    weak = await run(FakeRuntime(cricket_catalogue(), pairs=[{"product_id": pid(1), "orders": 1}]), reference_product_id=BAT_ID,
                     scenario=Scenario.FREQUENTLY_BOUGHT_TOGETHER)
    assert weak["items"] == []                                                         # below the configured minimum
    real = await run(FakeRuntime(cricket_catalogue(), pairs=[{"product_id": pid(1), "orders": 5}]), reference_product_id=BAT_ID,
                     scenario=Scenario.FREQUENTLY_BOUGHT_TOGETHER)
    assert [i["product_id"] for i in real["items"]] == [pid(1)] and any("in 5 orders" in r for r in real["items"][0]["reasons"])
    assert real["evidence"]["pairs"] == 1


async def test_a_guest_gets_no_frequently_bought_together_and_a_sign_in_hint():
    out = await run(FakeRuntime(cricket_catalogue(), signed_in=False, pairs=[{"product_id": pid(1), "orders": 9}]), reference_product_id=BAT_ID,
                    scenario=Scenario.FREQUENTLY_BOUGHT_TOGETHER)
    assert out["items"] == [] and "Sign in" in out["message"]


# ------------------------------------------------------------------------------------------------ profiles and personalization

def profile(**prefs) -> dict:
    base = {"sports": [], "brands": [], "categories": [], "teams": [], "usage_level": None, "price_min": None, "price_max": None, "sizes": {}, "attributes": {},
            "empty": not any(prefs.values())}
    return {"profile_id": PROFILE_ID, "kind": "OWNER", "display_name": "Me", "age_band": None, "personalization_enabled": True, "preferences": {**base, **prefs}}


async def test_personalized_uses_profile_sports_brand_and_price_range_and_explains_it():
    cat = cricket_catalogue([product(pid(20), "Football Boots", 2_000, category="Boots", role="boots", sport="Football", brand="Nike")])
    out = await run(FakeRuntime(cat, profile=profile(sports=["Cricket"], brands=["Adidas"], price_max=3_000)), scenario=Scenario.PERSONALIZED, limit=6)
    assert out["personalized"] is True and out["profile"]["personalization_applied"] is True
    assert out["items"][0]["product_id"] == pid(4)                                     # the brand the customer follows ranks first
    assert all(i["sport"] == "Cricket" and i["price"] <= 3_000 for i in out["items"])
    assert any("brand you follow" in r for r in out["items"][0]["reasons"])


async def test_incomplete_profile_falls_back_to_catalogue_top_picks_and_says_so():
    out = await run(FakeRuntime(cricket_catalogue(), profile=profile()), scenario=Scenario.PERSONALIZED, limit=3)
    assert out["items"] and out["personalized"] is False and "Add what you play" in out["message"]


async def test_disabled_personalization_ignores_preferences():
    p = profile(sports=["Cricket"], brands=["Adidas"])
    p["personalization_enabled"] = False
    out = await run(FakeRuntime(cricket_catalogue(), profile=p), scenario=Scenario.PERSONALIZED, limit=3)
    assert out["profile"]["personalization_applied"] is False and "Add what you play" in out["message"]


async def test_a_family_profile_uses_its_own_preferences_never_the_account_holders_history():
    child = profile(sports=["Cricket"])
    child.update(kind="FAMILY", display_name="Aarav", age_band="CHILD_8_12")
    orders = [{"items": [{"product_id": pid(99), "unit_price": 50_000}]}]
    rt = FakeRuntime(cricket_catalogue(), profile=child, orders=orders)
    rt.catalogue[pid(99)] = product(pid(99), "Dad's Bat", 50_000, category="Bats", role="bat", brand="Kookaburra")
    out = await run(rt, scenario=Scenario.PERSONALIZED, profile_id=PROFILE_ID, limit=4)
    assert out["profile"]["display_name"] == "Aarav" and out["personalization_note"] == "Purchase history is not used for this profile."
    assert all("bought" not in r for i in out["items"] for r in i["reasons"])


async def test_a_child_profile_asks_for_junior_products_and_explains_when_only_adult_ones_exist():
    child = profile(sports=["Cricket"])
    child.update(kind="FAMILY", display_name="Aarav", age_band="CHILD_8_12")
    adult_only = [product(pid(31), "Pro Bat", 4_000, category="Bat", role="bat", attrs={"ageGroup": "adult"}),
                  product(pid(32), "Pro Gloves", 1_500, category="Gloves", role="batting-gloves", attrs={"ageGroup": "adult"})]
    rt = FakeRuntime(adult_only, profile=child)
    out = await run(rt, scenario=Scenario.PERSONALIZED, profile_id=PROFILE_ID)
    assert out["items"] == [] and out["personalized"] is False
    assert "suitable for juniors" in out["message"] and "Aarav" in out["message"]
    assert any(n == "search_products" and a.get("age_group") == "junior" for n, a in rt.log)       # junior products are asked for first
    assert out["meta"]["rejected"] == {"not_for_child_profile": 2}

    junior = product(pid(33), "Junior Bat", 2_500, category="Bat", role="bat", attrs={"ageGroup": "junior"})
    ok = await run(FakeRuntime([*adult_only, junior], profile=child), scenario=Scenario.PERSONALIZED, profile_id=PROFILE_ID)
    assert [i["product_id"] for i in ok["items"]] == [pid(33)] and ok["personalized"] is True


async def test_another_accounts_profile_is_a_404_not_a_leak():
    with pytest.raises(ApiException) as e:
        await run(FakeRuntime(cricket_catalogue(), profile=profile()), scenario=Scenario.PERSONALIZED, profile_id=FOREIGN_PROFILE)
    assert e.value.status == 404 and e.value.code == "PROFILE_NOT_FOUND"


async def test_a_guest_cannot_use_a_profile():
    with pytest.raises(ApiException) as e:
        await run(FakeRuntime(cricket_catalogue(), signed_in=False), scenario=Scenario.PERSONALIZED, profile_id=PROFILE_ID)
    assert e.value.status == 401


# ------------------------------------------------------------------------------------------------ validation

async def test_scenarios_that_need_a_reference_reject_a_missing_one():
    with pytest.raises(ApiException) as e:
        await run(FakeRuntime(cricket_catalogue()), scenario=Scenario.SIMILAR)
    assert e.value.status == 400


async def test_unknown_reference_and_bad_ids_and_bad_budget():
    with pytest.raises(ApiException) as e:
        await run(FakeRuntime(cricket_catalogue()), reference_product_id=OTHER_ID)
    assert e.value.status == 404
    with pytest.raises(ApiException):
        await run(FakeRuntime(cricket_catalogue()), reference_product_id="not-a-uuid")
    with pytest.raises(ApiException):
        await run(FakeRuntime(cricket_catalogue()), reference_product_id=BAT_ID, budget=-5)


@pytest.mark.parametrize("budget", [0, -1, -0.01])
async def test_zero_and_negative_budgets_are_rejected_not_treated_as_no_budget(budget):
    with pytest.raises(ApiException) as e:
        await run(FakeRuntime(cricket_catalogue()), reference_product_id=BAT_ID, budget=budget)
    assert e.value.status == 400


async def test_a_reference_without_a_current_price_cannot_produce_a_price_based_recommendation():
    unpriced = dict(BAT, price=None)
    with pytest.raises(ApiException) as e:
        await run(FakeRuntime([unpriced, *cricket_catalogue()[1:]]), reference_product_id=BAT_ID)
    assert e.value.status == 422 and e.value.code == "NO_PRICE"


async def test_the_ceiling_is_rounded_to_paise_and_a_product_exactly_on_it_is_included():
    bat = product(BAT_ID, "Odd Price Bat", 1234.56, category="Bat", role="bat")
    exactly = product(pid(41), "On The Line", 246.91, category="Ball", role="ball")          # 20% of 1234.56 = 246.912 -> 246.91
    under = product(pid(43), "Well Under", 100.00, category="Gloves", role="batting-gloves")   # two eligible items so the base band is accepted without widening
    just_over = product(pid(42), "A Paisa Over", 246.92, category="Accessories", role="arm-guard")
    out = await run(FakeRuntime([bat, exactly, under, just_over]), reference_product_id=BAT_ID, limit=6)
    assert out["budget"]["applied"]["high"] == 246.91 and out["budget"]["fallback_used"] is False
    assert {i["product_id"] for i in out["items"]} == {pid(41), pid(43)}                       # exactly on the ceiling is in, one paisa over is out


async def test_duplicate_candidates_from_several_searches_appear_once():
    dup = product(pid(51), "Same Ball", 300, category="Ball", role="ball")
    out = await run(FakeRuntime([BAT, dup, dict(dup)]), reference_product_id=BAT_ID, limit=6)
    assert [i["product_id"] for i in out["items"]].count(pid(51)) <= 1


async def test_the_pipeline_never_calls_a_model_and_only_reads():
    rt = FakeRuntime(cricket_catalogue(), profile=profile(sports=["Cricket"]))
    await run(rt, reference_product_id=BAT_ID)
    assert {n for n, _ in rt.log} <= {"get_product", "search_products", "get_shopper_profile", "get_orders", "get_co_purchases"}
