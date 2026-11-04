"""Recommendation scenarios chosen from chat: rules pick the scenario, the pipeline supplies every number, a model failure changes nothing."""
from __future__ import annotations

import pytest

from app.agent.schemas import ChatRequest
from app.router.tier0 import reco_scenario
from tests.fakes import BAT_MRF, BAT_SG, BAT_SS, GLOVES, HELMET, FakeDSports, FakeLLM
from tests.helpers import make_rig


@pytest.mark.parametrize("message,scenario", [
    ("Show me premium options", "UPGRADE"),
    ("is there a higher-end version of this", "UPGRADE"),
    ("anything better than this?", None),
    ("show similar bats", "SIMILAR"),
    ("what are some alternatives", "SIMILAR"),
    ("a cheaper option please", "SIMILAR"),
    ("what accessories go with this", "COMPLEMENTARY"),
    ("what goes with this bat", "COMPLEMENTARY"),
    ("what do people usually buy together with this", "FREQUENTLY_BOUGHT_TOGETHER"),
    ("frequently bought together?", "FREQUENTLY_BOUGHT_TOGETHER"),
    ("do other customers also buy something with it", "FREQUENTLY_BOUGHT_TOGETHER"),
    ("I want a cricket bat", None),
])
def test_scenario_is_chosen_by_rules_from_the_wording(message, scenario):
    assert reco_scenario(message) == scenario


async def ask(rig, message, *, product=BAT_MRF, conv=None):
    return await rig.chat.chat(rig.alice, ChatRequest(message=message, conversation_id=conv, selected_product_id=product))


async def test_premium_is_only_shown_when_asked_for_and_uses_the_upgrade_band():
    rig = await make_rig()
    plain = await ask(rig, "what goes with this bat")
    assert plain.intent == "RECOMMEND"
    assert all(i["price"] <= 0.4 * 6499 for i in (plain.recommendations or {}).get("items", []))      # never a premium product by default

    premium = await ask(rig, "show me premium options")
    rec = premium.recommendations
    assert rec["scenario"] == "UPGRADE" and rec["items"]
    assert {i["product_id"] for i in rec["items"]} <= {BAT_SS, BAT_SG}                                  # pricier bats only
    assert all(i["price"] > 6499 for i in rec["items"])
    assert "Premium" in premium.message


async def test_similar_requests_keep_using_the_existing_alternative_search():
    rig = await make_rig()
    r = await ask(rig, "show me similar bats")
    assert r.intent == "PRODUCT_SEARCH" and not (r.recommendations or {}).get("scenario")      # the existing, tested path; the SIMILAR scenario is reached from the product page/API


async def test_an_explicit_budget_in_the_message_takes_precedence():
    rig = await make_rig()
    rec = (await ask(rig, "premium options under 8000")).recommendations
    assert rec["scenario"] == "UPGRADE"
    assert rec["budget"]["applied"]["source"] == "explicit" and rec["budget"]["applied"]["high"] == 8000
    assert {i["product_id"] for i in rec["items"]} == {BAT_SS}


async def test_complementary_with_nothing_inside_any_band_says_so_instead_of_inventing_one():
    rig = await make_rig()
    r = await ask(rig, "what accessories go with this")
    # Bat is Rs 6,499; the cheapest in-stock kit item here costs more than 40% of it, so there must be nothing, and the answer must say so
    assert r.recommendations is None and ("Nothing in stock" in r.message or "No in-stock" in r.message)


async def test_reasons_come_from_pipeline_metadata_not_from_the_model():
    rig = await make_rig()
    r = await ask(rig, "show me premium options")
    for item in r.recommendations["items"]:
        assert item["reasons"][0] == "A premium alternative to MRF Cricket Bat - Virgin"
        assert any("more than MRF Cricket Bat - Virgin" in x for x in item["reasons"])


async def test_a_model_outage_does_not_change_or_break_a_scenario_recommendation():
    class Down(FakeLLM):
        async def complete(self, *a, **k):          # any model call fails
            raise RuntimeError("model unavailable")
    rig = await make_rig(llm=Down())
    r = await ask(rig, "show me premium options")
    assert r.status == "ok" and r.recommendations["items"] and r.recommendations["meta"]["llm_tokens"] == 0


async def test_without_a_product_in_context_scenario_words_do_not_hijack_a_normal_search():
    rig = await make_rig()
    r = await rig.chat.chat(rig.alice, ChatRequest(message="show me similar cricket bats under 8000"))
    assert r.intent != "RECOMMEND"


async def test_frequently_bought_together_needs_evidence_and_never_makes_it_up():
    dsports = FakeDSports()
    rig = await make_rig(dsports=dsports)
    none = await ask(rig, "frequently bought together?")
    assert none.recommendations is None and "not enough purchase history" in none.message
    dsports.co_purchases[BAT_MRF] = [{"productId": GLOVES, "orders": 1}, {"productId": HELMET, "orders": 6}]
    real = await ask(rig, "frequently bought together?")
    assert [i["product_id"] for i in real.recommendations["items"]] == [HELMET]            # one order is not evidence
    assert any("in 6 orders" in r for r in real.recommendations["items"][0]["reasons"])
