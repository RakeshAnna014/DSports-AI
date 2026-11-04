"""Structured responses: a `type` the UI can render, tappable choices that resolve server-side, and explainable recommendations."""
import pytest

from app.agent.schemas import ChatRequest
from tests.fakes import BAT_MRF, BAT_SS, SHOES, USER_A
from tests.helpers import make_rig


@pytest.fixture
async def rig():
    r = await make_rig()
    r.dsports.add_order(USER_A, "ORD-20261001-000001", "DELIVERED", [(SHOES, 1)], shipment_status="DELIVERED", placed="2026-10-01T10:00:00Z")
    r.dsports.add_order(USER_A, "ORD-20260915-000002", "SHIPPED", [(BAT_MRF, 1)], shipment_status="IN_TRANSIT", placed="2026-09-15T10:00:00Z")
    r.dsports.add_order(USER_A, "ORD-20261004-000005", "DELIVERED", [(BAT_SS, 1)], shipment_status="DELIVERED", placed="2026-10-04T10:00:00Z")
    return r


async def say(rig, message, *, conv=None, choice=None, who="alice"):
    principal = None if who is None else getattr(rig, who)
    return await rig.chat.chat(principal, ChatRequest(message=message, conversation_id=conv, choice_id=choice))


@pytest.mark.parametrize("message,kind", [
    ("cricket bat", "PRODUCT_LIST"),
    ("Show my cart", "CART"),
    ("Where is my latest order?", "ORDER_TRACKING"),
    ("hello", "TEXT"),
])
async def test_each_response_has_a_renderable_type(rig, message, kind):
    assert (await say(rig, message)).type == kind


async def test_orders_comparison_and_reviews_types(rig):
    assert (await say(rig, "Show my latest order")).type == "ORDER_LIST"
    first = await say(rig, "cricket bat")
    assert (await say(rig, "Compare the first two", conv=first.conversation_id)).type == "PRODUCT_COMPARISON"


async def test_an_ambiguous_return_becomes_tappable_choices_with_stable_options(rig):
    r = await say(rig, "can I return the bat")
    assert r.type == "RETURN_PRODUCT_SELECTION" and r.selection["purpose"] == "RETURN"
    assert r.selection["prompt"] == "Which product would you like to return?"
    options = r.selection["options"]
    assert {o["name"] for o in options} >= {"SS Cricket Bat - Prime"} and all(o["choice_id"] for o in options)
    assert "order_id" not in str(options)        # ids stay on the server: the client only holds opaque choice ids


async def test_tapping_a_choice_checks_exactly_that_product_and_offers_the_return(rig):
    r = await say(rig, "can I return my latest order")
    if r.selection is None:                       # the latest order has a single product: eligibility straight away
        assert r.eligibility
        return
    pick = next(o for o in r.selection["options"] if "Bat" in o["name"])
    done = await say(rig, pick["name"], conv=r.conversation_id, choice=pick["choice_id"])
    assert done.type == "RETURN_OPTIONS" and done.eligibility["product_name"] == pick["name"] and done.meta.llm_calls <= 1
    assert any(s.label == "Return this item" and s.choice_id for s in done.suggestions)


async def test_the_return_action_asks_for_confirmation_and_creates_nothing_until_confirmed(rig):
    r = await say(rig, "can I return the bat")
    pick = r.selection["options"][0]
    checked = await say(rig, pick["name"], conv=r.conversation_id, choice=pick["choice_id"])
    action = next(s for s in checked.suggestions if s.label == "Return this item")
    proposed = await say(rig, "Return this item", conv=checked.conversation_id, choice=action.choice_id)
    assert proposed.requires_confirmation and proposed.type == "CONFIRMATION" and rig.dsports.returns == []


async def test_a_forged_stale_or_foreign_choice_id_does_nothing(rig):
    r = await say(rig, "can I return the bat")
    forged = await say(rig, "Return the SG bat", conv=r.conversation_id, choice="deadbeef0000")
    assert forged.selection is None or forged.disclaimers
    assert any("no longer available" in d for d in forged.disclaimers) and rig.dsports.returns == []
    good = r.selection["options"][0]["choice_id"]
    other = await say(rig, "x", choice=good, who="bob")                         # another customer cannot replay it
    assert any("no longer available" in d for d in other.disclaimers)
    again = await say(rig, "can I return the bat", conv=r.conversation_id)
    stale = await say(rig, "x", conv=r.conversation_id, choice=good)            # replaced by the newer response's offers
    assert again.selection and any("no longer available" in d for d in stale.disclaimers) or stale.eligibility is not None


async def test_follow_up_chips_follow_the_response(rig):
    products = await say(rig, "cricket bat")
    assert [s.label for s in products.suggestions][:2] == ["Compare these", "Which one is best for a beginner?"]
    tracking = await say(rig, "Where is my latest order?")
    labels = [s.label for s in tracking.suggestions]
    assert "Return an item from this order" in labels and "Reorder" in labels


async def test_recommendations_are_real_products_with_reasons_and_a_bundle(rig):
    rec = await rig.chat.recommend(rig.alice, [BAT_MRF], 4)
    assert rec["title"] == "Complete your setup" and rec["personalized"] is True
    assert rec["items"], "the catalogue has products that complement a bat"
    for item in rec["items"]:
        assert item["reasons"] and item["product_id"] not in (BAT_MRF, SHOES, BAT_SS) and item["price"] > 0
    assert rec["bundle"] is None or rec["bundle"]["total"] > 0


async def test_a_guest_gets_no_personalised_recommendations_without_a_product(rig):
    rec = await rig.chat.recommend(None, [], 4)
    assert rec["personalized"] is False and rec["items"] == []
    with_anchor = await rig.chat.recommend(None, [BAT_MRF], 4)
    assert with_anchor["personalized"] is False and with_anchor["title"] == "Complete your setup"


# ------------------------------------------------------------------------------------------------ multi-sport recommendations (kits are data)

from tests.fakes import BANDS, GYM_BAG, RUN_BOTTLE, RUN_SHOES, RUN_SOCKS, RUN_WATCH, GLOVES, HELMET   # noqa: E402


async def _customer_with(rig, *product_ids):
    rig.dsports.orders.clear()
    for i, pid in enumerate(product_ids):
        rig.dsports.add_order(USER_A, f"ORD-2026100{i + 1}-00000{i + 1}", "DELIVERED", [(pid, 1)], placed=f"2026-10-0{i + 1}T10:00:00Z")


async def test_a_bat_completes_with_cricket_kit_not_with_other_sports(rig):
    rec = await rig.chat.recommend(rig.alice, [BAT_MRF], 4)
    names = {i["name"] for i in rec["items"]}
    assert {"MRF Cricket Gloves - Pro", "SS Cricket Helmet - Elite"} <= names
    assert all(i["sport"] == "Cricket" for i in rec["items"] if not i.get("cross_sport"))
    assert any("Completes your cricket kit" in r for i in rec["items"] for r in i["reasons"])


async def test_running_shoes_complete_with_running_items_and_explain_why(rig):
    await _customer_with(rig)
    rec = await rig.chat.recommend(rig.alice, [RUN_SHOES], 4)
    names = {i["name"] for i in rec["items"] if not i.get("cross_sport")}
    assert {"Puma Running Socks - Pack of 3", "Decathlon Hydration Bottle - 750 ml"} <= names
    assert all(i["sport"] == "Running" for i in rec["items"] if not i.get("cross_sport"))
    assert any("running routine" in r for i in rec["items"] for r in i["reasons"])


async def test_cross_sport_items_only_come_with_their_explanation(rig):
    await _customer_with(rig)
    rec = await rig.chat.recommend(rig.alice, [RUN_SHOES], 6)
    cross = [i for i in rec["items"] if i.get("cross_sport")]
    assert cross, "running shoes have a documented relation to fitness"
    assert all(i["reasons"][0].startswith("Related sport") for i in cross)
    assert {i["name"] for i in cross} <= {"Puma Gym Bag - Duffel", "Decathlon Resistance Bands - Set of 5"}


async def test_a_multi_sport_customer_gets_each_sport_not_only_one(rig):
    await _customer_with(rig, BAT_MRF, RUN_SHOES)
    rec = await rig.chat.recommend(rig.alice, [], 4)
    sports = {i["sport"] for i in rec["items"] if not i.get("cross_sport")}
    assert {"Cricket", "Running"} <= sports and rec["personalized"] is True
    purchased = {BAT_MRF, RUN_SHOES}
    assert not purchased & {i["product_id"] for i in rec["items"]}


async def test_history_reasons_mention_only_what_the_orders_show(rig):
    await _customer_with(rig, BAT_MRF)
    rec = await rig.chat.recommend(rig.alice, [], 4)
    gloves = next(i for i in rec["items"] if i["product_id"] == GLOVES)
    assert any(r == "You bought MRF before (MRF Cricket Bat - Virgin)" for r in gloves["reasons"])
    assert not any("usual price range" in r for r in gloves["reasons"])          # 3,299 is below what this customer paid (6,499): no false claim
    helmet = next(i for i in rec["items"] if i["product_id"] == HELMET)
    assert any("usual price range" in r for r in helmet["reasons"])                # 4,799 is inside it
    assert all("Running" not in r for i in rec["items"] for r in i["reasons"] if i["sport"] == "Cricket")


async def test_what_is_already_in_the_cart_is_not_recommended_again(rig):
    await _customer_with(rig)
    rec = await rig.chat.recommend(rig.alice, [BAT_MRF, GLOVES], 4)
    assert GLOVES not in {i["product_id"] for i in rec["items"]} and BAT_MRF not in {i["product_id"] for i in rec["items"]}
    assert HELMET in {i["product_id"] for i in rec["items"]}


async def test_complete_my_kit_builds_a_bundle_from_the_sports_kit(rig):
    r = await say(rig, "complete my cricket kit")
    assert r.type == "RECOMMENDATIONS" and r.meta.llm_calls == 0
    names = {i["name"] for i in r.recommendations["items"]}
    assert {"MRF Cricket Gloves - Pro", "SS Cricket Helmet - Elite"} <= names
    assert r.recommendations["bundle"]["total"] > 0 and all(i["reasons"] for i in r.recommendations["items"])


async def test_a_home_gym_is_built_within_the_budget_and_never_over_it(rig):
    r = await say(rig, "I want to build a home gym under ₹2,500")
    assert r.type == "RECOMMENDATIONS" and r.recommendations["bundle"]["total"] <= 2500
    assert all(i["sport"] == "Fitness" and i["price"] <= 2500 for i in r.recommendations["items"])
    tiny = await say(rig, "I want to build a home gym under ₹100")
    assert tiny.recommendations is None and "couldn't put together" in tiny.message


async def test_recommend_something_for_me_uses_only_the_customers_own_history(rig):
    await _customer_with(rig, BAT_MRF, RUN_SHOES)
    r = await say(rig, "Recommend something for me")
    assert r.type == "RECOMMENDATIONS" and r.recommendations["personalized"] is True
    assert {i["sport"] for i in r.recommendations["items"]} >= {"Cricket", "Running"}
    guest = await say(rig, "Recommend something for me", who=None)
    assert guest.recommendations is None and "Sign in" in guest.message


async def test_latest_order_means_that_order_even_when_older_orders_have_other_products(rig):
    await _customer_with(rig, SHOES, BAT_SS)                      # the bat is the newest order
    r = await say(rig, "Is my latest order eligible for return?")
    assert r.type == "RETURN_OPTIONS" and r.eligibility["product_name"] == "SS Cricket Bat - Prime"


async def test_a_latest_order_with_several_products_asks_which_one_as_cards(rig):
    rig.dsports.orders.clear()
    rig.dsports.add_order(USER_A, "ORD-20261001-000001", "DELIVERED", [(SHOES, 1)], shipment_status="DELIVERED", placed="2026-10-01T10:00:00Z")
    rig.dsports.add_order(USER_A, "ORD-20261005-000002", "DELIVERED", [(BAT_MRF, 1), (GLOVES, 1)], shipment_status="DELIVERED", placed="2026-10-05T10:00:00Z")
    r = await say(rig, "Is my latest order eligible for return?")
    assert r.type == "RETURN_PRODUCT_SELECTION"
    assert {o["name"] for o in r.selection["options"]} == {"MRF Cricket Bat - Virgin", "MRF Cricket Gloves - Pro"}
    assert {o["order_number"] for o in r.selection["options"]} == {"ORD-20261005-000002"}


def test_plural_nouns_take_no_article():
    from app.agent.recommend import _a
    assert _a("batting pads") == "batting pads" and _a("dumbbells") == "dumbbells" and _a("cricket bat") == "a cricket bat" and _a("arm guard") == "an arm guard"
    assert _a("kit bag") == "a kit bag" and _a("football socks") == "football socks" and _a("swimming goggles") == "swimming goggles"


async def test_naming_a_new_sport_after_a_search_is_a_fresh_search_not_advice_on_what_is_shown(rig):
    first = await say(rig, "cricket bat")
    r = await say(rig, "Which running shoes are best for beginners?", conv=first.conversation_id)
    assert r.intent == "PRODUCT_SEARCH" and all(p["sport"] == "Running" for p in r.products)
